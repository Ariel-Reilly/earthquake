import re, os
imports_by_target = {}
for dp, _, files in os.walk('src'):
    for fn in files:
        if not fn.endswith(('.vue', '.js')): continue
        full = os.path.join(dp, fn)
        try:
            content = open(full, encoding='utf-8', errors='ignore').read()
        except: continue
        pat = re.compile(r'from\s+["\']([./\w\-]*services/[a-zA-Z][\w\-]*)["\']')
        for m in pat.finditer(content):
            target = m.group(1)
            full_target = os.path.normpath(os.path.join(os.path.dirname(full), target) + '.js')
            rel = os.path.relpath(full_target, 'src').replace('\\', '/').lstrip('./')
            imports_by_target.setdefault(rel, set()).add(os.path.relpath(full, 'src').replace('\\', '/'))

for k in sorted(imports_by_target):
    print(k, '<-', len(imports_by_target[k]), 'files')
    for f in sorted(imports_by_target[k])[:3]:
        print('   ', f)
