"""Database design audit for GeoSentry schema - v3 (clean)."""
import re
from collections import defaultdict

SCHEMA_PATH = r'D:\earthquake\backend\src\main\resources\schema.sql'
DATA_PATH = r'D:\earthquake\backend\src\main\resources\data.sql'

with open(SCHEMA_PATH, encoding='utf-8') as f:
    raw_schema = f.read()
with open(DATA_PATH, encoding='utf-8') as f:
    data = f.read()

# Normalize schema: remove comments, collapse whitespace, but preserve newlines
schema = re.sub(r'--[^\n]*', '', raw_schema)
# Collapse multi-space to single space (but keep newlines for context)
schema = ' '.join(schema.split())

# 1. Parse table column definitions (find column lists within parens)
table_pat = re.compile(r'CREATE TABLE IF NOT EXISTS (\w+)\s*\((.*?)\s*\)\s*;', re.DOTALL)
tables = {}
for m in table_pat.finditer(schema):
    name = m.group(1)
    body = m.group(2)
    cols = []
    # split body by comma (but commas inside JSON/VARCHAR sizes aren't problematic here)
    for col_def in re.split(r',\s*(?![^(]*\))', body):
        col_def = col_def.strip()
        if not col_def:
            continue
        mm = re.match(r'(\w+)\s+', col_def)
        if mm:
            kw = mm.group(1)
            if kw in ('PRIMARY', 'FOREIGN', 'UNIQUE', 'CONSTRAINT', 'CHECK', 'INDEX', 'EXCLUDE'):
                continue
            cols.append((kw, col_def))
    tables[name] = cols

# 2. Find PK and UNIQUE columns
pk_cols = {}
for tbl, cols in tables.items():
    for cname, cdef in cols:
        if 'PRIMARY KEY' in cdef.upper():
            pk_cols[tbl] = cname
            break

unique_inline = defaultdict(set)
unique_table = defaultdict(set)
for tbl, cols in tables.items():
    for cname, cdef in cols:
        if 'UNIQUE' in cdef.upper() and 'NOT NULL' in cdef.upper():
            unique_inline[tbl].add(cname)
# Parse table-level UNIQUE
for m in re.finditer(r'UNIQUE\s*\(([^)]+)\)', schema):
    cols = [c.strip() for c in m.group(1).split(',')]
    # Find which table this belongs to (search backward)
    idx = m.start()
    last_table = None
    for tm in table_pat.finditer(schema):
        if tm.start() < idx < tm.end():
            last_table = tm.group(1)
            break
    if last_table:
        for c in cols:
            unique_table[last_table].add(c)

# 3. Foreign keys
fk_pat = re.compile(r'(\w+)\s+VARCHAR\(\d+\)\s*(NOT NULL\s+)?REFERENCES\s+(\w+)\((\w+)\)(?:\s+(ON\s+DELETE\s+\w+(?:\s+\w+)?))?', re.IGNORECASE)
fks = []
for m in fk_pat.finditer(schema):
    fks.append({
        'from_table': None,  # filled below by context
        'from_col': m.group(1),
        'to_table': m.group(3),
        'to_col': m.group(4),
        'on_delete': (m.group(5) if m.lastindex and m.lastindex >= 5 and m.group(5) else '').strip(),
        'not_null': bool(m.group(2)),
        'pos': m.start(),
    })

# Associate FK with table by finding CREATE TABLE boundary
fk_boundaries = []
for tm in table_pat.finditer(schema):
    fk_boundaries.append((tm.start(), tm.end(), tm.group(1)))
fk_boundaries.sort()
for fk in fks:
    pos = fk['pos']
    for start, end, tbl in fk_boundaries:
        if start <= pos <= end:
            fk['from_table'] = tbl
            break

print('='*70)
print(f'1. FOREIGN KEYS ({len(fks)} found)')
print('='*70)
for fk in fks:
    flag = 'NOT NULL' if fk['not_null'] else 'nullable'
    print(f"   {fk['from_table']:25}.{fk['from_col']:20} -> {fk['to_table']:25}.{fk['to_col']:15} [{flag}] {fk['on_delete']}")

# 4. Validate FK targets
issues = []
for fk in fks:
    target = fk['to_table']
    if target not in tables:
        issues.append(f"   [X] {fk['from_table']}.{fk['from_col']} -> {target} (TABLE MISSING)")
        continue
    target_col = fk['to_col']
    if target_col not in [c for c, _ in tables[target]]:
        issues.append(f"   [X] {fk['from_table']}.{fk['from_col']} -> {target}.{target_col} (COL MISSING)")
        continue
    is_pk = pk_cols.get(target) == target_col
    is_unique = target_col in unique_inline.get(target, set()) or target_col in unique_table.get(target, set())
    if not (is_pk or is_unique):
        issues.append(f"   [!] {fk['from_table']}.{fk['from_col']} -> {target}.{target_col} (target NOT PK/UNIQUE)")
if issues:
    for i in issues: print(i)
else:
    print('   [OK] All FK targets exist and are PK/UNIQUE')

# 5. Logical FK columns (code-level FK only)
print('\n' + '='*70)
print('2. LOGICAL FK COLUMNS (no REFERENCES clause, application-managed)')
print('='*70)
critical_logical_fks = [
    ('hazard_point', 'region_code', 'region'),
    ('hazard_point', 'source', None),
    ('inspection_task', 'region_code', 'region'),
    ('inspection_task', 'hazard_code', 'hazard_point'),
    ('inspection_record', 'hazard_code', 'hazard_point'),
    ('inspection_record', 'task_code', 'inspection_task'),
    ('monitoring_device', 'region_code', 'region'),
    ('monitoring_device', 'hazard_code', 'hazard_point'),
    ('warning_event', 'rule_code', 'warning_rule'),
    ('warning_event', 'hazard_code', 'hazard_point'),
    ('warning_event', 'warning_level', 'sys_dict'),
    ('emergency_event', 'warning_code', 'warning_event'),
    ('emergency_event', 'hazard_code', 'hazard_point'),
    ('risk_score_log', 'hazard_code', 'hazard_point'),
    ('ai_analysis_result', 'business_type', None),
    ('ai_device_health', 'device_code', 'monitoring_device'),
    ('ai_comprehensive_score', 'hazard_code', 'hazard_point'),
    ('device_metric', 'hazard_code', 'hazard_point'),
    ('device_metric', 'device_code', 'monitoring_device'),
    ('device_trajectory', 'device_code', 'monitoring_device'),
    ('confirmation_task', 'hazard_code', 'hazard_point'),
    ('user_message', 'user_id', 'sys_user'),
    ('user_todo', 'user_id', 'sys_user'),
    ('approval', 'applicant_id', 'sys_user'),
    ('approval', 'approver_id', 'sys_user'),
    ('plan', 'created_by', 'sys_user'),
    ('resource', 'region_code', 'region'),
    ('sys_user', 'role_code', 'sys_role'),
    ('rule', 'created_by', 'sys_user'),
]
print('   Logical FKs without REFERENCES (orphan risk if app allows):')
for tbl, col, ref in critical_logical_fks:
    if tbl in tables and col in [c for c, _ in tables[tbl]]:
        ref_str = f'-> {ref}' if ref else ''
        print(f'      {tbl:25}.{col:25} {ref_str}')

# 6. sys_role_permission integrity
print('\n' + '='*70)
print('3. ROLE / PERMISSION DATA INTEGRITY')
print('='*70)
sys_perm_codes = set()
sys_perm_block = re.search(r"INSERT INTO sys_permission[^;]*;", data, re.DOTALL)
if sys_perm_block:
    # First column is code
    rows_text = sys_perm_block.group(0)[sys_perm_block.group(0).index('VALUES'):]
    for m in re.finditer(r"\('([\w:]+)',", rows_text):
        sys_perm_codes.add(m.group(1))

role_codes = set()
role_block = re.search(r"INSERT INTO sys_role[^;]*;", data, re.DOTALL)
if role_block:
    rows_text = role_block.group(0)[role_block.group(0).index('VALUES'):]
    for m in re.finditer(r"\('([\w_]+)',", rows_text):
        role_codes.add(m.group(1))

user_block = re.search(r"INSERT INTO sys_user[^;]*;", data, re.DOTALL)
user_info = []  # (username, role_code)
if user_block:
    rows_text = user_block.group(0)[user_block.group(0).index('VALUES'):]
    for m in re.finditer(r"\('(\w+)',\s*'[^']+',\s*'[^']+',\s*'[^']+',\s*'[^']+',\s*'([^']+)'", rows_text):
        user_info.append((m.group(1), m.group(2)))

print(f'   Defined roles:           {len(role_codes)}: {sorted(role_codes)}')
print(f'   Defined permissions:     {len(sys_perm_codes)}')
print(f'   Users (with role_code):  {len(user_info)}')
for u, r in user_info:
    valid = 'OK' if r in role_codes else 'BAD'
    print(f'      {u:15} -> {r:25} [{valid}]')

# Check user_todo / user_message / approval user references
print('\n   User FK references in other tables:')
user_refs = defaultdict(set)
for tbl in ['user_message', 'user_todo', 'approval']:
    blk = re.search(rf"INSERT INTO {tbl}[^;]*;", data, re.DOTALL)
    if not blk: continue
    for m in re.finditer(r"WHERE username='(\w+)'", blk.group(0)):
        user_refs[tbl].add(m.group(1))
for tbl, refs in user_refs.items():
    print(f'      {tbl}: {sorted(refs)}')

# Cross-check
defined_users = set(u for u, _ in user_info)
all_refs = set().union(*user_refs.values()) if user_refs else set()
orphan = all_refs - defined_users
if orphan:
    print(f'\n   [!] ORPHAN user references: {sorted(orphan)}')
else:
    print('\n   [OK] All user FK references valid')

# 7. Cross-table data integrity
print('\n' + '='*70)
print('4. CROSS-TABLE DATA INTEGRITY (demo data)')
print('='*70)
# Extract all HZ codes
hz_codes = set(re.findall(r"\('(HZ-\w+)'", data))

# monitoring_device.hazard_code refs
dev_block = re.search(r"INSERT INTO monitoring_device[^;]*;", data, re.DOTALL)
dev_haz_refs = set()
if dev_block:
    rows_text = dev_block.group(0)[dev_block.group(0).index('VALUES'):]
    for m in re.finditer(r"\('(\w+)',\s*'[^']+',\s*'\w+',\s*(?:'(HZ-\w+)'|NULL),\s*'", rows_text):
        if m.group(2): dev_haz_refs.add(m.group(2))
print(f'   hazard codes defined: {sorted(hz_codes)}')
print(f'   device.hazard_code refs: {sorted(dev_haz_refs)}')
print(f'   orphan device->hazard refs: {sorted(dev_haz_refs - hz_codes)}')

# warning_event.hazard_code, rule_code refs
wr_codes = set()
wr_block = re.search(r"INSERT INTO warning_rule[^;]*;", data, re.DOTALL)
if wr_block:
    for m in re.finditer(r"\('(\w+)'", wr_block.group(0)):
        wr_codes.add(m.group(1))
rule_codes = set()
rule_block = re.search(r"INSERT INTO rule[^;]*;", data, re.DOTALL)
if rule_block:
    for m in re.finditer(r"\('(\w+)'", rule_block.group(0)):
        rule_codes.add(m.group(1))
all_rule_codes = wr_codes | rule_codes

we_block = re.search(r"INSERT INTO warning_event[^;]*;", data, re.DOTALL)
we_rule_refs = set()
we_haz_refs = set()
if we_block:
    rows_text = we_block.group(0)[we_block.group(0).index('VALUES'):]
    for m in re.finditer(r"\('(\w+)',\s*'(\w+)',\s*'(HZ-\w+)'", rows_text):
        we_rule_refs.add(m.group(2))
        we_haz_refs.add(m.group(3))
print(f'\n   rule codes defined (warning_rule + rule): {sorted(all_rule_codes)}')
print(f'   warning_event.rule_code refs: {sorted(we_rule_refs)}')
print(f'   orphan warning->rule refs: {sorted(we_rule_refs - all_rule_codes)}')
print(f'   warning_event.hazard_code refs: {sorted(we_haz_refs)}')
print(f'   orphan warning->hazard refs: {sorted(we_haz_refs - hz_codes)}')

# emergency_event refs
em_block = re.search(r"INSERT INTO emergency_event[^;]*;", data, re.DOTALL)
em_warn_refs = set()
em_haz_refs = set()
if em_block:
    rows_text = em_block.group(0)[em_block.group(0).index('VALUES'):]
    for m in re.finditer(r"\('(\w+)',\s*'(\w+)',\s*'(HZ-\w+)'", rows_text):
        em_warn_refs.add(m.group(2))
        em_haz_refs.add(m.group(3))
we_codes = set()
if we_block:
    for m in re.finditer(r"\('(\w+)'", we_block.group(0)):
        we_codes.add(m.group(1))
print(f'\n   warning codes defined: {sorted(we_codes)}')
print(f'   emergency.warning_code refs: {sorted(em_warn_refs)}')
print(f'   orphan emergency->warning refs: {sorted(em_warn_refs - we_codes)}')
print(f'   emergency.hazard_code refs: {sorted(em_haz_refs)}')
print(f'   orphan emergency->hazard refs: {sorted(em_haz_refs - hz_codes)}')

# approval biz_id
ap_block = re.search(r"INSERT INTO approval[^;]*;", data, re.DOTALL)
ap_biz_refs = []
if ap_block:
    rows_text = ap_block.group(0)[ap_block.group(0).index('VALUES'):]
    # biz_id is 3rd col: (approval_code, biz_type, biz_id, title, ...)
    for m in re.finditer(r"\('(\w+)',\s*'([\w_]+)',\s*'(\w+)'", rows_text):
        ap_biz_refs.append((m.group(2), m.group(3)))
print(f'\n   approval entries: {ap_biz_refs}')
for btype, bid in ap_biz_refs:
    if btype == 'WARNING_PUBLISH':
        valid = bid in we_codes
        print(f'      {btype} {bid}: {"OK" if valid else "ORPHAN (no such warning)"}')

# 8. Index coverage on FK-like columns
print('\n' + '='*70)
print('5. INDEX COVERAGE ON FK-LIKE COLUMNS')
print('='*70)
# Extract indexes (single-line after our normalization)
idx_pat = re.compile(r'CREATE\s+(UNIQUE\s+)?INDEX\s+IF NOT EXISTS\s+(\w+)\s+ON\s+(\w+)\s*\(([^)]+)\)', re.IGNORECASE)
indexes = defaultdict(list)  # tbl -> [(idx_name, [cols])]
for m in idx_pat.finditer(schema):
    is_unique = m.group(1) is not None
    idx_name = m.group(2)
    tbl = m.group(3)
    cols = [c.strip().split()[0] for c in m.group(4).split(',')]
    indexes[tbl].append((idx_name, cols, is_unique))

hot_paths = [
    ('hazard_point', ['region_code', 'risk_level', 'source', 'discovered_at']),
    ('monitoring_device', ['hazard_code', 'region_code', 'online_status', 'device_type']),
    ('inspection_task', ['hazard_code', 'region_code', 'status']),
    ('inspection_record', ['task_code', 'hazard_code']),
    ('warning_event', ['hazard_code', 'rule_code', 'status', 'warning_level']),
    ('emergency_event', ['warning_code', 'hazard_code', 'status']),
    ('rule', ['disaster_type', 'level', 'enabled']),
    ('rule_execution_log', ['rule_id', 'executed_at']),
    ('ai_device_health', ['device_code', 'health_level']),
    ('ai_comprehensive_score', ['hazard_code', 'risk_level', 'disaster_type', 'concern_label', 'analyzed_at']),
    ('device_metric', ['hazard_code', 'device_code', 'metric_code', 'recorded_at']),
    ('device_trajectory', ['device_code', 'recorded_at']),
    ('confirmation_task', ['hazard_code', 'status', 'claimed_by', 'priority']),
    ('user_message', ['user_id', 'read_status', 'created_at']),
    ('user_todo', ['user_id', 'status', 'deadline']),
    ('approval', ['biz_type', 'biz_id', 'status', 'applicant_id', 'approver_id']),
    ('resource', ['res_type', 'status', 'region_code']),
    ('plan', ['disaster_type', 'status']),
    ('notice_template', ['disaster_type', 'channel', 'enabled']),
    ('data_sync_task', ['data_source_id', 'status']),
    ('sys_dict', ['type']),
    ('sys_role_permission', ['role_id', 'perm_code']),
    ('user_preference', ['user_id']),
    ('user_profile', ['username']),
    ('event_sign', ['event_id', 'signer_id', 'status']),
    ('event_transfer', ['event_id']),
    ('risk_score_log', ['hazard_code', 'calculated_at']),
    ('operation_log', ['operator', 'created_at']),
    ('ai_analysis_result', ['business_type', 'business_code']),
    ('file_attachment', ['business_type', 'business_code']),
    ('drift_monitor_record', ['model_id', 'detected_at']),
    ('multimodal_fusion_record', ['event_id', 'created_at']),
]
print('   Tables with missing indexes on hot FK/filter columns:')
missing_count = 0
for tbl, hot_cols in hot_paths:
    if tbl not in tables:
        continue
    table_col_names = {c for c, _ in tables[tbl]}
    for col in hot_cols:
        if col not in table_col_names:
            continue
        indexed = False
        for idx_name, idx_cols, _ in indexes.get(tbl, []):
            if idx_cols[0] == col or col in idx_cols:
                indexed = True
                break
        if not indexed:
            print(f'      [!] {tbl:30}.{col:25} (hot path, no index)')
            missing_count += 1
print(f'   Total missing indexes: {missing_count}')

# 9. NOT NULL / constraint issues
print('\n' + '='*70)
print('6. COLUMN CONSTRAINT ISSUES')
print('='*70)
# A. sys_dict.value with same type duplicates?
sys_dict_block = re.search(r"INSERT INTO sys_dict[^;]*;", data, re.DOTALL)
if sys_dict_block:
    seen = {}
    duplicates = []
    rows_text = sys_dict_block.group(0)[sys_dict_block.group(0).index('VALUES'):]
    # 1st col is type, 3rd col is value (per INSERT column order)
    for m in re.finditer(r"\('(\w+)',\s*'[^']+',\s*'([^']+)'", rows_text):
        key = (m.group(1), m.group(2))
        if key in seen:
            duplicates.append(key)
        seen[key] = True
    print(f'   sys_dict: {len(seen)} entries, duplicates: {len(duplicates)}')
    for d in duplicates: print(f'      DUPLICATE: type={d[0]}, value={d[1]}')

# B. sys_role.code duplicates?
sys_role_block = re.search(r"INSERT INTO sys_role[^;]*;", data, re.DOTALL)
if sys_role_block:
    rows_text = sys_role_block.group(0)[sys_role_block.group(0).index('VALUES'):]
    codes = re.findall(r"\('(\w+)',", rows_text)
    dup_codes = [c for c in set(codes) if codes.count(c) > 1]
    print(f'   sys_role codes: {len(codes)}, unique: {len(set(codes))}, dup: {dup_codes}')

# C. sys_permission duplicates?
sys_perm_block = re.search(r"INSERT INTO sys_permission[^;]*;", data, re.DOTALL)
if sys_perm_block:
    rows_text = sys_perm_block.group(0)[sys_perm_block.group(0).index('VALUES'):]
    codes = re.findall(r"\('([\w:]+)',", rows_text)
    dup_codes = [c for c in set(codes) if codes.count(c) > 1]
    print(f'   sys_permission codes: {len(codes)}, unique: {len(set(codes))}, dup: {dup_codes}')

# 10. Permission granularity check
print('\n' + '='*70)
print('7. PERMISSION GRANULARITY CHECK')
print('='*70)
admin_perms = set()
admin_block = re.search(r"-- 超级管理员全部权限.*?ON CONFLICT", data, re.DOTALL)
if admin_block:
    # Find SELECT id FROM sys_role WHERE role_code = 'admin'
    admin_block2 = re.search(r"SELECT r\.id, p\.code.*?role_code = 'admin'", data, re.DOTALL)
    if admin_block2:
        # admin gets all perms via SELECT, no need to count here
        print('   admin: gets ALL permissions via SELECT (correct)')

# Count perms per role
role_perm_counts = {}
for role in role_codes:
    # Find block for this role
    pattern = rf"\(SELECT id FROM sys_role WHERE role_code='{role}'\),'([\w:]+)'\)"
    matches = re.findall(pattern, data)
    role_perm_counts[role] = len(set(matches))

print('\n   Permission counts per role:')
for r, c in sorted(role_perm_counts.items(), key=lambda x: -x[1]):
    print(f'      {r:25}: {c} perms')

# 11. JSON/JSONB consistency
print('\n' + '='*70)
print('8. JSON vs JSONB CONSISTENCY')
print('='*70)
for tbl, cols in tables.items():
    json_cols = [(c, d) for c, d in cols if 'JSON' in d.upper() and 'JSONB' not in d.upper()]
    jsonb_cols = [(c, d) for c, d in cols if 'JSONB' in d.upper()]
    if json_cols and jsonb_cols:
        print(f'   [!] {tbl} has both JSON and JSONB')
    if jsonb_cols:
        for c, _ in jsonb_cols:
            pass
    if json_cols:
        for c, _ in json_cols:
            print(f'      {tbl}.{c} uses JSON (consider JSONB for indexing)')

# 12. CHECK constraint on status columns
print('\n' + '='*70)
print('9. STATUS ENUM WITHOUT CHECK CONSTRAINTS')
print('='*70)
status_cols = []
for tbl, cols in tables.items():
    for cname, cdef in cols:
        if cname == 'status':
            status_cols.append(tbl)
print(f'   {len(status_cols)} tables have status column without CHECK constraint:')
# Just list, not all need CHECK
for t in status_cols[:20]:
    print(f'      {t}')
if len(status_cols) > 20:
    print(f'      ... and {len(status_cols)-20} more')

print('\n' + '='*70)
print('AUDIT COMPLETE')
print('='*70)
