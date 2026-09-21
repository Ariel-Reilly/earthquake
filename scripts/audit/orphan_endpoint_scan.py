#!/usr/bin/env python3
"""精确扫描：所�?frontend request/get/post 调用 vs 后端 Controller 端点�?""
import re, glob
from pathlib import Path

ROOT = r'd:\DisasterGuard'

def find_calls(content, func_name):
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
    """Normalize a path: lowercase, strip ${...}, drop trailing slash, drop query."""
    p = re.sub(r'\$\{[^}]*\}', '*VAR*', p)
    p = p.split('?')[0].rstrip()
    if not p.startswith('/'):
        p = '/' + p
    p = re.sub(r'/+', '/', p).rstrip('/') or '/'
    # Replace {id}/{name}/etc with *VAR*
    p = re.sub(r'\{[^}]+\}', '*VAR*', p)
    # Drop trailing /VAR* chained ONLY IF the path starts WITHOUT preceding / for the var,
    # i.e. it's an append like /tasks${withQuery(...)} �?/tasks.
    # We detect this by checking if original (pre-trim) had no '/' before *VAR*
    # Simpler: keep *VAR* and rely on fuzzy match
    # Drop leading *VAR*? (rare)
    # Strip http://host prefix to keep only the path
    m = re.match(r'https?://[^/]+(/.*)$', p)
    if m:
        p = m.group(1)
    # Strip trailing /*VAR* and bare *VAR*
    while p.endswith('/*VAR*'):
        p = p[:-len('/*VAR*')].rstrip('/')
    while p.endswith('*VAR*'):
        p = p[:-len('*VAR*')].rstrip('/')
    # Strip trailing junk like extra }
    p = p.rstrip('}').rstrip()
    return p

# === 后端 ===
backend_eps = {}  # (verb, path) -> file
for path in glob.glob(f'{ROOT}/backend/src/main/java/cn/disasterguard/**/*Controller.java', recursive=True):
    rel = path.replace(ROOT + '\\', '').replace('\\', '/')
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
print(f'[后端] 端点�? {len(backend_eps)}')

# === 前端 ===
fp_calls = []
fp_files = (list(Path(f'{ROOT}/src/services').glob('*.js')) +
            list(Path(f'{ROOT}/src/services/_architecture').rglob('*.js')) +
            list(Path(f'{ROOT}/src/views').rglob('*.vue')) +
            list(Path(f'{ROOT}/src/components').rglob('*.vue')) +
            list(Path(f'{ROOT}/src/stores').rglob('*.js')))

for fp in fp_files:
    rel = str(fp).replace(ROOT + '\\', '').replace('\\', '/')
    try:
        content = open(fp, encoding='utf-8').read()
    except UnicodeDecodeError:
        content = open(fp, encoding='utf-8', errors='ignore').read()
    # request() calls
    for arg in find_calls(content, 'request'):
        # Skip calls inside backticks that have no path
        sm = re.search(r'[`"\']([^`"\']+)[`"\']', arg)
        if not sm: continue
        path = sm.group(1)
        if '/' not in path: continue
        if 'http' in path: continue
        verb = 'GET'
        mv = re.search(r"method\s*[:=]\s*[`\"']([A-Z]+)[`\"']", arg)
        if mv: verb = mv.group(1)
        fp_calls.append((verb, path, rel))
    # http shortcuts
    for verb_s, verb_l in [('get', 'GET'), ('post', 'POST'), ('put', 'PUT'), ('delete', 'DELETE')]:
        for arg in find_calls(content, verb_s):
            sm = re.search(r'[`"\']([^`"\']+)[`"\']', arg)
            if not sm: continue
            path = sm.group(1)
            if '/' not in path: continue
            if 'http' in path: continue
            fp_calls.append((verb_l, path, rel))
    # raw fetch() calls - extract URL and method from options
    for arg in find_calls(content, 'fetch'):
        sm = re.search(r'[`"\']([^`"\']+)[`"\']', arg)
        if not sm: continue
        path = sm.group(1)
        if '/' not in path: continue
        if path.startswith('${') and path.endswith('}'): continue
        if 'localhost' in path or 'http' in path:
            # might have API_BASE prefix already - try parsing
            pass
        verb = 'GET'
        mv = re.search(r"method\s*[:=]\s*[`\"']([A-Z]+)[`\"']", arg)
        if mv: verb = mv.group(1)
        fp_calls.append((verb, path, rel))
    # Also: ${API_BASE}/xxx patterns in any template literal (as static prefix)
    # Re-scan whole content for `${API_BASE}/xxx` or `${BASE}/xxx`
    pattern_api = re.compile(r'\$\{(?:API_BASE|BASE|BACKEND_BASE|DEVICE_MAP_ENDPOINT|TRAJECTORY_ENDPOINT|HEALTH_PATH)[^}]*\}\s*[`"\']?/*([A-Za-z0-9_/\-\.\$\{\}]+)')
    for m in pattern_api.finditer(content):
        rel_part = m.group(1)
        if '/' in rel_part:
            # find nearest verb (look back)
            start = m.start()
            verb = 'GET'
            win = content[max(0,start-400):start]
            mv = re.search(r"method\s*[:=]\s*[`\"']([A-Z]+)[`\"']", win)
            if mv: verb = mv.group(1)
            fp_calls.append((verb, '/' + rel_part.split('${')[0].rstrip('/'), rel))

# Normalize and group
fp_norm_keys = {}  # (verb, normalized) -> set of files
for verb, path, file in fp_calls:
    norm = norm_path(path)
    if not norm.startswith('/api'):
        norm = '/api' + (norm if norm.startswith('/') else '/' + norm)
    fp_norm_keys.setdefault((verb, norm), set()).add(file)

print(f'[前端] 唯一 (verb, path) �? {len(fp_norm_keys)}')

# === 模糊匹配：backend (verb, path) 是否被任�?frontend (verb,*) 匹配�?===
def fuzzy_match(be_path, fe_path):
    """Match backend path against frontend path allowing *VAR* anywhere, including trailing-only."""
    if be_path == fe_path:
        return True
    bp = re.sub(r'\{[^}]+\}', '*VAR*', be_path)
    if bp == fe_path:
        return True
    # Backend has placeholder, frontend might have it too, or might NOT.
    # Remove trailing *VAR* from FE then compare to BE (with placeholders)
    fe_stripped = fe_path
    while fe_stripped.endswith('/*VAR*'):
        fe_stripped = fe_stripped[:-len('/*VAR*')].rstrip('/')
    while fe_stripped.endswith('*VAR*'):
        fe_stripped = fe_stripped[:-len('*VAR*')].rstrip('/')
    if bp == fe_stripped or fe_path == fe_stripped and bp.startswith(fe_path):
        return True
    # Reverse: backend path is a prefix of frontend path (with optional trailing vars)
    if fe_path.startswith(bp + '*VAR*') or fe_path == bp:
        return True
    if fe_stripped and (bp == fe_stripped or be_path == fe_stripped):
        return True
    # Also: be path with trailing placeholder may match fe_path without that placeholder
    if bp.endswith('*VAR*'):
        bp_prefix = bp[:-len('*VAR*')].rstrip('/')
        if fe_path == bp_prefix or fe_path.startswith(bp_prefix + '/'):
            return True
    bs = be_path.strip('/').split('/')
    fs = fe_path.strip('/').split('/')
    if len(bs) == len(fs):
        for a, b in zip(bs, fs):
            a2 = re.sub(r'\{[^}]+\}', '*VAR*', a)
            if a2 != b and a2 != '*VAR*' and b != '*VAR*':
                break
        else:
            return True
    return False

# Index frontend by path
fe_by_path = {}
for verb, path in fp_norm_keys:
    fe_by_path.setdefault(path, set()).add(verb)

unused = []
for (verb, path), file in backend_eps.items():
    matched = False
    # Match same verb first
    if path in fe_by_path and verb in fe_by_path[path]:
        matched = True
    if not matched:
        # Try wildcard-verb (frontend may use POST while backend declares GET)
        if path in fe_by_path and fe_by_path[path]:
            matched = True
    if not matched:
        # Try fuzzy: any frontend path that matches segment-for-segment
        for fe_path in fe_by_path:
            if fuzzy_match(path, fe_path):
                matched = True
                break
    if not matched:
        unused.append((verb, path, file))

print(f'\n[后端实现但前端可能未调用] {len(unused)} 个（�?fuzzy 匹配）：\n')
by_file = {}
for v, p, f in unused:
    by_file.setdefault(f, []).append((v, p))
for f in sorted(by_file):
    print(f'>>> {f}')
    for v, p in sorted(by_file[f]):
        print(f'    {v:6s} {p}')
