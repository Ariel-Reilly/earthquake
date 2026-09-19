"""
v2.1.0 数据层统一 (Task #1 P1) —— 一致性回填脚本。

升级路径：
  应用启动 *之前* 必须先跑本脚本，把既有 hazard_point 物理表里的灾害数据
  搬运到 unified_event 表（kind='HAZARD'），随后 schema.sql 末尾的 v2.1.0
  迁移块会自动 DROP 物理 hazard_point 并替换为同名视图。

幂等：
  本脚本按 hazard_code 唯一匹配；同一 hazard_code 已存在于 unified_event
  时会被跳过（无论来源是否一致），可重复执行。

依赖：
  psycopg2-binary（pip install psycopg2-binary）

连接参数（按 backend/src/main/resources/application.yml 的默认值）：
  host     = POSTGRES_HOST or 'localhost'
  port     = POSTGRES_PORT or 5432
  database = POSTGRES_DB   or 'disasterguard'
  user     = POSTGRES_USER or 'disasterguard'
  password = POSTGRES_PASSWORD or 'disasterguard123'

退出码：
  0  成功（无论有没有搬运任何行）
  1  连接失败 / 数据库错误
  2  数据库里找不到 hazard_point 物理表（schema 变更已生效，无需回填）
"""
from __future__ import annotations

import os
import sys
import time
from typing import Iterable

import psycopg2
from psycopg2 import sql
from psycopg2.extras import execute_values

# ---------- CLI ----------

def parse_env():
    return {
        'host':     os.environ.get('POSTGRES_HOST', 'localhost'),
        'port':     int(os.environ.get('POSTGRES_PORT', '5432')),
        'database': os.environ.get('POSTGRES_DB', 'disasterguard'),
        'user':     os.environ.get('POSTGRES_USER', 'disasterguard'),
        'password': os.environ.get('POSTGRES_PASSWORD', 'disasterguard123'),
    }


def parse_args(argv: list[str]) -> dict:
    opts = parse_env()
    i = 1
    while i < len(argv):
        a = argv[i]
        if a == '--host' and i + 1 < len(argv):
            opts['host'] = argv[i + 1]; i += 2
        elif a == '--port' and i + 1 < len(argv):
            opts['port'] = int(argv[i + 1]); i += 2
        elif a == '--db' and i + 1 < len(argv):
            opts['database'] = argv[i + 1]; i += 2
        elif a == '--user' and i + 1 < len(argv):
            opts['user'] = argv[i + 1]; i += 2
        elif a == '--password' and i + 1 < len(argv):
            opts['password'] = argv[i + 1]; i += 2
        elif a in ('-h', '--help'):
            print(__doc__); sys.exit(0)
        else:
            print(f'Unknown arg: {a}', file=sys.stderr); sys.exit(1)
    return opts


# ---------- Helpers ----------

def relation_exists(cur, schema: str, name: str, kind: str) -> bool:
    """kind in {'table', 'view'}"""
    cur.execute(
        """
        SELECT 1 FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = %s AND c.relname = %s AND c.relkind = %s
        LIMIT 1
        """,
        (schema, name, 'r' if kind == 'table' else 'v'),
    )
    return cur.fetchone() is not None


def get_column_set(cur, table: str) -> set[str]:
    cur.execute(
        """
        SELECT column_name FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = %s
        """,
        (table,),
    )
    return {row[0] for row in cur.fetchall()}


def has_unified_event_kind_column(cur) -> bool:
    cur.execute(
        """
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'unified_event' AND column_name = 'kind'
        LIMIT 1
        """
    )
    return cur.fetchone() is not None


# ---------- Main ----------

def main() -> int:
    opts = parse_args(sys.argv)
    print(f'[backfill] connecting to {opts["user"]}@{opts["host"]}:{opts["port"]}/{opts["database"]}')
    t0 = time.time()
    try:
        conn = psycopg2.connect(
            host=opts['host'], port=opts['port'], dbname=opts['database'],
            user=opts['user'], password=opts['password'], connect_timeout=10,
        )
    except Exception as e:
        print(f'[backfill] FAILED to connect: {e}', file=sys.stderr)
        return 1

    conn.autocommit = False
    try:
        with conn.cursor() as cur:
            # Sanity: hazard_point is still a physical table
            if not relation_exists(cur, 'public', 'hazard_point', 'table'):
                if relation_exists(cur, 'public', 'hazard_point', 'view'):
                    print('[backfill] hazard_point 已为视图（schema 迁移已生效），跳过回填。')
                    return 0
                print('[backfill] hazard_point 不存在（数据库是全新实例？），无需回填。', file=sys.stderr)
                return 0

            if not has_unified_event_kind_column(cur):
                print(
                    '[backfill] unified_event 还没有 kind 列 —— schema.sql 末尾的 v2.1.0 迁移块未运行。\n'
                    '         请先临时应用 schema.sql 的迁移块部分（或临时把 v2.1.0 迁移块贴到 psql 执行），'
                    '         再跑本回填脚本。', file=sys.stderr,
                )
                return 1

            # Build SELECT from hazard_point (legacy physical table)
            legacy_cols = get_column_set(cur, 'hazard_point')
            print(f'[backfill] legacy hazard_point 列数 = {len(legacy_cols)}')

            # unified_event target columns we will populate
            target_cols = [
                'kind', 'event_code', 'state', 'source',
                'hazard_code', 'name', 'hazard_type', 'risk_level', 'region_code', 'owner',
                'hazard_status', 'longitude', 'latitude', 'geom', 'threat_object',
                'last_inspection_at', 'hazard_source', 'discovered_at', 'detection_confidence',
                'last_inspection_device_type', 'historical_count', 'historical_max_score',
                'exposure_population', 'exposure_buildings', 'land_use_type',
            ]
            unified_cols = get_column_set(cur, 'unified_event')
            for c in target_cols:
                if c not in unified_cols:
                    print(f'[backfill] FATAL: unified_event 缺少列 {c}（迁移块未跑完？）', file=sys.stderr)
                    return 1

            # Read legacy rows (full row, we'll pick columns we need)
            cur.execute(
                """
                SELECT id, hazard_code, name, hazard_type, risk_level, region_code, owner,
                       status, longitude, latitude, geom, threat_object, last_inspection_at,
                       source, discovered_at, detection_confidence,
                       last_inspection_device_type, historical_count, historical_max_score,
                       exposure_population, exposure_buildings, land_use_type, created_at
                FROM hazard_point
                """
            )
            legacy_rows = cur.fetchall()
            print(f'[backfill] legacy hazard_point 行数 = {len(legacy_rows)}')

            # Existing unified_event hazard codes (skip those)
            cur.execute(
                """
                SELECT hazard_code FROM unified_event
                WHERE kind = 'HAZARD' AND hazard_code IS NOT NULL
                """
            )
            existing = {row[0] for row in cur.fetchall()}
            print(f'[backfill] unified_event 已有 HAZARD 行 = {len(existing)}')

            to_insert: list[tuple] = []
            skipped = 0
            for r in legacy_rows:
                (legacy_id, hcode, name, htype, rlevel, rcode, owner,
                 status, lon, lat, geom, threat, last_insp,
                 src, disc_at, conf,
                 last_dev_type, hist_count, hist_max, exp_pop, exp_bld, land_use, created_at) = r
                if hcode is None:
                    skipped += 1
                    continue
                if hcode in existing:
                    skipped += 1
                    continue
                # Build unified_event row
                event_code = f'EVT-{hcode}'  # 唯一、可读、与 hazard_code 1:1 对应
                # source 字段（事件来源 AI_AUTO/MANUAL/RULE_ENGINE）— 从 hazard_source 推断
                src_upper = (src or '').upper()
                if src_upper in ('AI_MONITOR',):
                    evt_source = 'AI_AUTO'
                elif src_upper in ('MANUAL',):
                    evt_source = 'MANUAL'
                else:
                    evt_source = (src_upper or 'MANUAL')
                to_insert.append((
                    'HAZARD',          # kind
                    event_code,        # event_code
                    'DETECTED',        # state (default)
                    evt_source,        # source
                    hcode, name, htype, rlevel, rcode, owner,
                    status, lon, lat, geom, threat,
                    last_insp,
                    src,               # hazard_source
                    disc_at, conf,
                    last_dev_type, hist_count or 0, hist_max or 0.0,
                    exp_pop or 0, exp_bld or 0, land_use or 'unknown',
                ))

            print(f'[backfill] 待插入 = {len(to_insert)} 行（跳过 {skipped} 行：null/已存在）')

            if to_insert:
                insert_sql = sql.SQL(
                    """
                    INSERT INTO unified_event (
                        kind, event_code, state, source,
                        hazard_code, name, hazard_type, risk_level, region_code, owner,
                        hazard_status, longitude, latitude, geom, threat_object,
                        last_inspection_at, hazard_source, discovered_at, detection_confidence,
                        last_inspection_device_type, historical_count, historical_max_score,
                        exposure_population, exposure_buildings, land_use_type
                    ) VALUES %s
                    ON CONFLICT (hazard_code) WHERE kind='HAZARD' DO NOTHING
                    """
                )
                execute_values(cur, insert_sql, to_insert, page_size=200)
                inserted = cur.rowcount
                print(f'[backfill] 实际写入 unified_event = {inserted} 行')
            else:
                print('[backfill] 无需写入')

            conn.commit()

            # Final consistency check
            cur.execute("SELECT count(*) FROM hazard_point")
            legacy_after = cur.fetchone()[0]
            cur.execute("SELECT count(*) FROM unified_event WHERE kind='HAZARD'")
            unified_after = cur.fetchone()[0]
            print('-' * 60)
            print(f'[backfill] 验证：hazard_point 现有 {legacy_after} 行，unified_event(HAZARD) 现有 {unified_after} 行')
            if unified_after < legacy_after:
                print(
                    f'[backfill] WARN: unified_event({unified_after}) < hazard_point({legacy_after})，'
                    f'可能有 legacy 行未回填，请检查日志。',
                    file=sys.stderr,
                )

            print(f'[backfill] DONE in {time.time() - t0:.2f}s')
            return 0
    except psycopg2.Error as e:
        conn.rollback()
        print(f'[backfill] DB 错误: {e}', file=sys.stderr)
        return 1
    finally:
        conn.close()


if __name__ == '__main__':
    sys.exit(main())
