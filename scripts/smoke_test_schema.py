"""Smoke test: load schema.sql + data.sql against a fresh DB, report failures."""
import sys
import time
import psycopg2

SCHEMA = r'D:\earthquake\backend\src\main\resources\schema.sql'
DATA   = r'D:\earthquake\backend\src\main\resources\data.sql'

# connect as postgres (default superuser, no password assumed to work locally)
conn = psycopg2.connect(dbname='postgres', user='postgres', host='localhost', port=5432, password='')
conn.autocommit = True
cur = conn.cursor()

# Drop & recreate test DB
cur.execute("DROP DATABASE IF EXISTS geosentry_smoke")
cur.execute("CREATE DATABASE geosentry_smoke OWNER postgres")
cur.close()
conn.close()
print('[smoke] created geosentry_smoke DB')

# Load schema.sql
conn = psycopg2.connect(dbname='geosentry_smoke', user='postgres', host='localhost', port=5432)
conn.autocommit = False
cur = conn.cursor()
print('[smoke] loading schema.sql ...')
t0 = time.time()
with open(SCHEMA, encoding='utf-8') as f:
    schema_sql = f.read()
try:
    cur.execute(schema_sql)
    conn.commit()
    print(f'[smoke] schema.sql OK in {time.time()-t0:.1f}s')
except psycopg2.Error as e:
    conn.rollback()
    print(f'[smoke] SCHEMA FAILED: {e}')
    sys.exit(1)

print('[smoke] loading data.sql ...')
t0 = time.time()
with open(DATA, encoding='utf-8') as f:
    data_sql = f.read()
try:
    cur.execute(data_sql)
    conn.commit()
    print(f'[smoke] data.sql OK in {time.time()-t0:.1f}s')
except psycopg2.Error as e:
    conn.rollback()
    print(f'[smoke] DATA FAILED: {e}')
    print('--- First few error rows for context ---')
    sys.exit(1)

# Verification queries
cur.execute("SELECT count(*) FROM unified_event WHERE kind='HAZARD'")
haz_count = cur.fetchone()[0]
print(f'[smoke] unified_event(HAZARD) rows = {haz_count}')

cur.execute("SELECT count(*) FROM hazard_point")
hp_count = cur.fetchone()[0]
print(f'[smoke] hazard_point rows (view) = {hp_count}')

cur.execute("SELECT kind, count(*) FROM unified_event GROUP BY kind ORDER BY kind")
for k, c in cur.fetchall():
    print(f'   unified_event[{k}] = {c}')

# Check FK constraints
cur.execute("""
    SELECT conname, conrelid::regclass, pg_get_constraintdef(oid)
    FROM pg_constraint
    WHERE conname IN (
      'fk_inspection_task_hazard','fk_inspection_record_hazard',
      'fk_monitoring_device_hazard','fk_warning_event_hazard',
      'fk_risk_score_log_hazard','fk_ai_comprehensive_score_hazard'
    )
""")
print('[smoke] FK constraints:')
for c in cur.fetchall():
    print(f'   {c[0]:38} on {c[1]}')

# Check that hazard_point is a view
cur.execute("""
    SELECT c.relname, c.relkind FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname='public' AND c.relname IN ('hazard_point','unified_event')
    ORDER BY c.relname
""")
for r in cur.fetchall():
    kind = {'r':'TABLE','v':'VIEW'}.get(r[1], r[1])
    print(f'   {r[0]:20} is a {kind}')

cur.close()
conn.close()
print('[smoke] all done')
