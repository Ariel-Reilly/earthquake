#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""精确检测 frontend request/get/post 调用 vs 后端 Controller 映射关系"""
import re, glob
from pathlib import Path

ROOT = r'd:\Projects\DisasterGuard'

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
    rel = path.replace(ROOT + '\', '').replace('\', '/')
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
    rel = str(fp).replace(ROOT + '\', '').replace('\', '/')
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

KNOWN_GAPS = {
    ('GET', '/api/ai/drift'): '[2026-09-21] ModelManagePage 引用，后端未实现',
    ('GET', '/api/ai/multimodal'): '[2026-09-21] AiInsightPage 引用，后端未实现',
}

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

print(f'\n[后端未被前端引用] {len(unused)} 个（已扣除 fuzzy 匹配）\n')
by_file = {}
for v, p, f in unused:
    by_file.setdefault(f, []).append((v, p))
for f in sorted(by_file):
    print(f'>>> {f}')
    for v, p in sorted(by_file[f]):
        print(f'    {v:6s} {p}')
if KNOWN_GAPS:
    print(f'\n○ 已知缺口（已登记基线，共 {len(KNOWN_GAPS)})：')
    for (verb, path), note in KNOWN_GAPS.items():
        print(f'    {verb} {path}   {note}')