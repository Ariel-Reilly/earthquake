#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
GL-Sandbox 设备注册 / 预警清理

  python register_devices.py                 # 注册隐患点 + 设备
  python register_devices.py --reset-warnings  # 仅清理上一轮预警（二次演示前必做）
  python register_devices.py --check         # 只做注册结果自查

若本机没有 psycopg2，可改用 psql 执行同目录下的 register_devices.sql：
  psql -h localhost -p 15432 -U postgres -d disasterguard -f register_devices.sql
"""

from __future__ import annotations

import argparse
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SQL_FILE = os.path.join(HERE, "register_devices.sql")

RESET_SQL = """
UPDATE warning_event
   SET status = '已解除', close_reason = '沙盘演示重置',
       closed_at = NOW(), updated_at = NOW()
 WHERE hazard_code LIKE 'HZ-SBOX-%'
   AND status NOT IN ('已关闭','已忽略','已解除');
"""

CHECK_SQL = """
SELECT device_code, device_type, hazard_code, online_status, install_location
  FROM monitoring_device
 WHERE device_code LIKE 'SB-%' OR device_code LIKE 'WS-%'
 ORDER BY device_code;
"""

HAZARD_CHECK_SQL = """
SELECT hazard_code, name, hazard_type, risk_level
  FROM hazard_point
 WHERE hazard_code LIKE 'HZ-SBOX-%'
 ORDER BY hazard_code;
"""


def connect(args):
    try:
        import psycopg2
    except ImportError:
        sys.exit("[ERR] 需要 psycopg2： pip install psycopg2-binary\n"
                 "      或改用： psql -h {h} -p {p} -U {u} -d {d} -f register_devices.sql"
                 .format(h=args.host, p=args.port, u=args.user, d=args.db))
    return psycopg2.connect(host=args.host, port=args.port, user=args.user,
                            password=args.password, dbname=args.db)


def main():
    ap = argparse.ArgumentParser(description="GL-Sandbox 设备注册")
    ap.add_argument("--host", default="localhost")
    ap.add_argument("--port", type=int, default=15432, help="PostGIS 宿主机映射端口")
    ap.add_argument("--user", default="postgres")
    ap.add_argument("--password", default=os.environ.get("PGPASSWORD", "postgres"))
    ap.add_argument("--db", default="postgres")
    ap.add_argument("--reset-warnings", action="store_true", help="仅清理沙盘预警")
    ap.add_argument("--check", action="store_true", help="仅自查")
    args = ap.parse_args()

    conn = connect(args)
    conn.autocommit = True
    cur = conn.cursor()

    if args.check:
        cur.execute(HAZARD_CHECK_SQL)
        print("[隐患点]")
        for r in cur.fetchall():
            print(f"  {r[0]}  {r[1]}  {r[2]}  {r[3]}")
        cur.execute(CHECK_SQL)
        rows = cur.fetchall()
        print(f"[设备] 共 {len(rows)} 台")
        for r in rows:
            flag = "OK " if r[2] else "!! "  # hazard_code 为空的规则引擎会丢弃
            print(f"  {flag}{r[0]}  {r[1]}  hazard={r[2]}  {r[3]}  {r[4]}")
        bad = [r for r in rows if not r[2]]
        if bad:
            print(f"[FAIL] {len(bad)} 台设备未绑定隐患点，规则引擎不会研判其数据")
        cur.close()
        conn.close()
        return

    if args.reset_warnings:
        cur.execute(RESET_SQL)
        print(f"[OK] 已归档 {cur.rowcount} 条沙盘预警（演示可重新触发）")
        cur.close()
        conn.close()
        return

    with open(SQL_FILE, "r", encoding="utf-8") as f:
        sql = f.read()
    cur.execute(sql)
    print("[OK] 隐患点 + 设备注册完成")

    cur.execute(CHECK_SQL)
    rows = cur.fetchall()
    print(f"[OK] 当前沙盘设备 {len(rows)} 台：")
    for r in rows:
        print(f"  {r[0]}  {r[1]}  hazard={r[2]}")
    print("\n[提示] 每次重新演示前执行： python register_devices.py --reset-warnings")

    cur.close()
    conn.close()


if __name__ == "__main__":
    main()
