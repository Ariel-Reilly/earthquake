#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""精确检测 frontend request/get/post 调用 vs 后端 Controller 映射关系"""
import os, re, glob
from pathlib import Path

# 由脚本自身位置推导仓库根目录，避免把开发机绝对路径写死在源码里。
# scripts/audit/orphan_endpoint_scan.py -> parents[2] 即仓库根。
ROOT = str(Path(__file__).resolve().parents[2])

def find_calls(content, func_name):
    """在代码中找出 func_name(...) 的调用，返回括号内的参数字符串"""
    results = []
    i = 0
    pat = re.compile(r'\b' + re.escape(func_name) + r'\s*\(')
    while True:
        m = pat.search(content, i)
        if not m: break
        start = m.end() - 1
        depth = 1
        j = start + 1
        while j < len(content) and depth > 0:
            ch = content[j]
            if ch == '(':
                depth += 1
            elif ch == ')':
                depth -= 1
            elif ch == '`':
                j += 1
                while j < len(content) and content[j] != '`':
                    if content[j] == '\\': j += 2; continue
                    j += 1
            elif ch == "'" or ch == '"':
                q = ch
                j += 1
                while j < len(content) and content[j] != q:
                    if content[j] == '\\': j += 2; continue
                    j += 1
            j += 1
        arg = content[start+1:j-1]
        results.append(arg)
        i = j
    return results

def norm_path(p):
    """规范化路径：小写、替换变量、去查询字符串、补齐 /api 前缀"""
    p = re.sub(r'\$\{[^}]*\}', '*VAR*', p)
    p = p.split('?')[0].rstrip()
    if not p.startswith('/'):
        p = '/' + p
    p = re.sub(r'/+', '/', p).rstrip('/') or '/'
    p = re.sub(r'\{[^}]+\}', '*VAR*', p)
    m = re.match(r'https?://[^/]+(/.*)$', p)
    if m:
        p = m.group(1)
    while p.endswith('/*VAR*'):
        p = p[:-len('/*VAR*')].rstrip('/')
    while p.endswith('*VAR*'):
        p = p[:-len('*VAR*')].rstrip('/')
    p = p.rstrip('}').rstrip()
    return p

# === 后端接口扫描 ===
backend_eps = {}
for path in glob.glob(f'{ROOT}/backend/src/main/java/cn/disasterguard/**/*Controller.java', recursive=True):
    rel = os.path.relpath(path, ROOT).replace(os.sep, '/')
    src = open(path, encoding='utf-8', errors='ignore').read()
    base = ''
    for line in src.splitlines():
        m = re.search(r'@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]+)"', line)
        if m: base = m.group(1); continue
        m2 = re.search(r'@(Get|Post|Put|Delete|Patch)Mapping', line)
        if not m2: continue
        verb = m2.group(1).upper()
        mp = re.search(r'"([^"]*)"', line)
        sub = mp.group(1) if mp else ''
        full = (base + sub).strip()
        if not full.startswith('/'): full = '/' + full
        full = re.sub(r'/+', '/', full).rstrip('/') or '/'
        backend_eps[(verb, full)] = rel
print(f'[后端] 接口数 {len(backend_eps)}')

# === 前端调用扫描 ===
fp_calls = []
fp_files = (list(Path(f'{ROOT}/frontend/src/services').glob('*.js')) +
            list(Path(f'{ROOT}/frontend/src/services/_architecture').rglob('*.js')) +
            list(Path(f'{ROOT}/frontend/src/views').rglob('*.vue')) +
            list(Path(f'{ROOT}/frontend/src/components').rglob('*.vue')) +
            list(Path(f'{ROOT}/frontend/src/stores').rglob('*.js')))

for fp in fp_files:
    rel = os.path.relpath(str(fp), ROOT).replace(os.sep, '/')
    try:
        content = open(fp, encoding='utf-8').read()
    except UnicodeDecodeError:
        content = open(fp, encoding='utf-8', errors='ignore').read()
    for arg in find_calls(content, 'request'):
        sm = re.search(r'[`"\']([^`"\']+)[`"\']', arg)
        if not sm: continue
        path = sm.group(1)
        if '/' not in path: continue
        if 'http' in path: continue
        verb = 'GET'
        mv = re.search(r"method\s*[:=]\s*[`\"']([A-Z]+)[`\"']", arg)
        if mv: verb = mv.group(1)
        fp_calls.append((verb, path, rel))
    for verb_s, verb_l in [('get', 'GET'), ('post', 'POST'), ('put', 'PUT'), ('delete', 'DELETE')]:
        for arg in find_calls(content, verb_s):
            sm = re.search(r'[`"\']([^`"\']+)[`"\']', arg)
            if not sm: continue
            path = sm.group(1)
            if '/' not in path: continue
            if 'http' in path: continue
            fp_calls.append((verb_l, path, rel))
    for arg in find_calls(content, 'fetch'):
        sm = re.search(r'[`"\']([^`"\']+)[`"\']', arg)
        if not sm: continue
        path = sm.group(1)
        if '/' not in path: continue
        if path.startswith('${') and path.endswith('}'): continue
        verb = 'GET'
        mv = re.search(r"method\s*[:=]\s*[`\"']([A-Z]+)[`\"']", arg)
        if mv: verb = mv.group(1)
        fp_calls.append((verb, path, rel))

fp_norm_keys = {}
for verb, path, file in fp_calls:
    norm = norm_path(path)
    if not norm.startswith('/api'):
        norm = '/api' + (norm if norm.startswith('/') else '/' + norm)
    fp_norm_keys.setdefault((verb, norm), set()).add(file)

print(f'[前端] 不同 (verb, path) 数 {len(fp_norm_keys)}')

# 2026-11-06：GET /api/ai/drift 与 GET /api/ai/multimodal 已由
# AiDeprecatedGapController 提供 501 + deprecated 桩，不再是「后端未实现」的缺口，
# 从基线移除，让扫描重新覆盖这两个映射。当前基线为空。
KNOWN_GAPS = {}

def fuzzy_match(be_path, fe_path):
    if be_path == fe_path: return True
    bp = re.sub(r'\{[^}]+\}', '*VAR*', be_path)
    if bp == fe_path: return True
    fe_stripped = fe_path
    while fe_stripped.endswith('/*VAR*'): fe_stripped = fe_stripped[:-len('/*VAR*')].rstrip('/')
    while fe_stripped.endswith('*VAR*'): fe_stripped = fe_stripped[:-len('*VAR*')].rstrip('/')
    if bp == fe_stripped or (fe_path == fe_stripped and bp.startswith(fe_path)): return True
    if fe_path.startswith(bp + '*VAR*') or fe_path == bp: return True
    if fe_stripped and (bp == fe_stripped or be_path == fe_stripped): return True
    if bp.endswith('*VAR*'):
        bp_prefix = bp[:-len('*VAR*')].rstrip('/')
        if fe_path == bp_prefix or fe_path.startswith(bp_prefix + '/'): return True
    bs = be_path.strip('/').split('/')
    fs = fe_path.strip('/').split('/')
    if len(bs) == len(fs):
        for a, b in zip(bs, fs):
            a2 = re.sub(r'\{[^}]+\}', '*VAR*', a)
            if a2 != b and a2 != '*VAR*' and b != '*VAR*': break
        else: return True
    return False

fe_by_path = {}
for verb, path in fp_norm_keys:
    fe_by_path.setdefault(path, set()).add(verb)

unused = []
for (verb, path), file in backend_eps.items():
    matched = False
    if path in fe_by_path and verb in fe_by_path[path]:
        matched = True
    if not matched:
        if path in fe_by_path and fe_by_path[path]:
            matched = True
    if not matched:
        for fe_path in fe_by_path:
            if fuzzy_match(path, fe_path):
                matched = True
                break
    if not matched and (verb, path) not in KNOWN_GAPS:
        unused.append((verb, path, file))

# 2026-11-06（R10 P2-5）：本脚本只认字面量路径，对「模板拼接」无能为力——
# 例如 aiApi 里 request(`/ai/xxx${path}`) 的基址段、dashboardApi 里
# `${base}/coverage` 这类写法，静态解析时会变成一个不完整的路径，
# 于是后端明明被调用却报成「未被引用」。这里再做一次末段可见性判断：
# 路径最后一段以字符串形式出现在前端源码里 → 归为「疑似误报」，
# 单独列出来人工复核，而不是混在真孤儿里让报告失真。
FE_SOURCE = ''
for fp in fp_files:
    try:
        FE_SOURCE += open(fp, encoding='utf-8', errors='ignore').read()
    except OSError:
        pass


def tail_segment_visible(path: str) -> bool:
    seg = path.rstrip('/').split('/')[-1]
    seg = re.sub(r'\{[^}]*\}|\*VAR\*', '', seg)
    if len(seg) < 3:
        return False
    return (f"'{seg}'" in FE_SOURCE or f'"{seg}"' in FE_SOURCE
            or f'/{seg}' in FE_SOURCE)


confirmed = [x for x in unused if not tail_segment_visible(x[1])]
suspected = [x for x in unused if tail_segment_visible(x[1])]

print(f'\n[后端未被前端引用] {len(confirmed)} 个确认 + {len(suspected)} 个疑似（末段在源码可见，需人工复核）\n')
by_file = {}
for v, p, f in confirmed:
    by_file.setdefault(f, []).append((v, p))
for f in sorted(by_file):
    print(f'>>> {f}')
    for v, p in sorted(by_file[f]):
        print(f'    {v:6s} {p}')

if suspected:
    print(f'\n△ 疑似误报（路径可能由模板拼接，末段字符串存在于前端源码）：{len(suspected)} 个')
    for v, p, _f in sorted(suspected, key=lambda x: x[1]):
        print(f'    {v:6s} {p}')

if KNOWN_GAPS:
    print(f'\n○ 已知缺口（已登记基线，共 {len(KNOWN_GAPS)})：')
    for (verb, path), note in KNOWN_GAPS.items():
        print(f'    {verb} {path}   {note}')