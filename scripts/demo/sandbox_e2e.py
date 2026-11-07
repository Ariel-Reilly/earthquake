#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
沙盘 → 平台 端到端预警演练驱动（GL-Sandbox E2E）

完整链路（全部是真实组件，没有 Mock）：
    剧本 / ESP32 传感器
        → sandbox_gateway.py（标定 + 时间压缩 + 哨兵注入）
        → EMQX :11883  topic=disasterguard/{deviceCode}/data
        → MqttDataSubscriber（后端订阅）
        → device_metric（PostgreSQL）
        → RuleEngineScheduler（每 5s 拉最新指标求值）
        → warning_event（预警落库，状态=待确认）
        → 前端实时推送 / 预警列表

用法：
    # 先确保 5 个容器 + 后端已启动（本脚本只做体检，不负责拉起服务）
    python scripts/demo/sandbox_e2e.py --check
    # 跑一次完整演练（自动重置上一轮数据）
    python scripts/demo/sandbox_e2e.py --scenario s1 --reset
    # 只跑到某个阶段（步数 = 剧本轮次，interval 默认 2s）
    python scripts/demo/sandbox_e2e.py --scenario s3 --steps 100 --reset

依赖：psycopg2（用 ai-service/.venv 里的解释器可直接跑）
"""

from __future__ import annotations

import argparse
import json
import os
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ENV_FILE = ROOT / ".env"
GATEWAY = ROOT / "hardware-sandbox" / "gateway" / "sandbox_gateway.py"
TOOLS_DIR = ROOT / "hardware-sandbox" / "tools"

CONTAINERS = [
    "disasterguard-postgres",
    "disasterguard-redis",
    "disasterguard-emqx",
    "disasterguard-minio",
    "disasterguard-tdengine",
]

# 各剧本的"看点指标"，用于实时打印数据推进
SCENARIO_PROBE = {
    "s1": [("SB-RAIN-01", "rainfall_24h", "24h雨量mm"),
           ("SB-GNSS-01", "displacement_rate", "位移速率mm/h")],
    "s2": [("SB-CRACK-01", "crack_width_rate", "裂缝速率mm/d"),
           ("SB-INCL-01", "tilt_angle", "倾角°")],
    "s3": [("WS-RIVER-01", "water_level", "水位m"),
           ("WS-RIVER-01", "rainfall_6h", "6h雨量mm")],
    "s4": [("SB-GNSS-01", "displacement_rate", "位移速率mm/h")],
}


# ------------------------------------------------------------------ 配置

def load_env(path: Path) -> dict:
    """极简 .env 解析：KEY=VALUE，忽略注释与空行。不打印任何值。"""
    env = {}
    if not path.exists():
        return env
    for line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, val = line.partition("=")
        env[key.strip()] = val.strip().strip('"').strip("'")
    return env


def db_config(env: dict) -> dict:
    return {
        "host": env.get("POSTGRES_HOST", "127.0.0.1"),
        "port": int(env.get("POSTGRES_PORT", "15432")),
        "user": env.get("POSTGRES_USER", "disasterguard"),
        "password": env.get("POSTGRES_PASSWORD", ""),
        "dbname": env.get("POSTGRES_DB", "disasterguard"),
    }


def resolve_python() -> str:
    """优先用项目内 venv（装了 pyyaml / paho-mqtt / psycopg2）。"""
    cand = ROOT / "ai-service" / ".venv" / "Scripts" / "python.exe"
    if cand.exists():
        return str(cand)
    return sys.executable


# ------------------------------------------------------------------ 体检

def check_containers() -> bool:
    ok = True
    print("[1/4] 容器状态")
    for name in CONTAINERS:
        try:
            out = subprocess.run(
                ["docker", "inspect", "-f", "{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{end}}", name],
                capture_output=True, text=True, timeout=10,
            )
            status = out.stdout.strip() or "missing"
        except Exception as exc:  # noqa: BLE001
            status = f"error:{exc}"
        good = status.startswith("running")
        ok = ok and good
        print(f"      {'OK ' if good else '!! '}{name:<26} {status}")
    return ok


def check_backend(port: int = 8080) -> bool:
    print(f"[2/4] 后端 /api/system/health (:{port})")
    url = f"http://127.0.0.1:{port}/api/system/health"
    try:
        with urllib.request.urlopen(url, timeout=5) as resp:  # noqa: S310
            print(f"      OK  HTTP {resp.status}")
            return resp.status == 200
    except urllib.error.HTTPError as exc:
        print(f"      !!  HTTP {exc.code}（后端已起但端点被拦截？）")
    except Exception as exc:  # noqa: BLE001
        print(f"      !!  连接失败: {exc}")
        print("          请先启动后端并显式开启 MQTT：")
        print("            export EMQX_ENABLED=true")
        print("            export DISASTERGUARD_WARNING_RULE_ENGINE_CRON='*/5 * * * * *'")
        print("            bash scripts/audit/mvn.sh spring-boot:run")
    return False


def check_sandbox_devices(cfg: dict) -> bool:
    print("[3/4] 沙盘设备注册情况")
    try:
        import psycopg2
    except ImportError:
        print("      !!  缺少 psycopg2： pip install psycopg2-binary")
        return False
    try:
        with psycopg2.connect(**cfg) as conn, conn.cursor() as cur:
            cur.execute(
                "SELECT count(*) FROM monitoring_device "
                "WHERE device_code LIKE 'SB-%' OR device_code LIKE 'WS-%'"
            )
            n = cur.fetchone()[0]
            cur.execute(
                "SELECT count(*) FROM monitoring_device "
                "WHERE (device_code LIKE 'SB-%' OR device_code LIKE 'WS-%') "
                "AND hazard_code LIKE 'HZ-SBOX-%'"
            )
            bound = cur.fetchone()[0]
    except Exception as exc:  # noqa: BLE001
        print(f"      !!  查询失败: {exc}")
        return False
    print(f"      设备 {n} 台，其中已绑定沙盘隐患点 {bound} 台")
    if n == 0:
        print("      !!  未注册：先执行 hardware-sandbox/tools/register_devices.sql")
        return False
    if bound < n:
        print(f"      !!  {n - bound} 台未绑定隐患点，规则引擎会丢弃其数据")
        return False
    return True


def _emqx_host_port(env: dict) -> tuple[str, int]:
    """从 EMQX_MQTT_URL 解析宿主机与端口，解析不了就用 compose 默认映射。"""
    raw = env.get("EMQX_MQTT_URL", "tcp://127.0.0.1:11883")
    clean = raw.split("//", 1)[-1]
    host, _, port = clean.partition(":")
    host = host or "127.0.0.1"
    try:
        return host, int(port or 11883)
    except ValueError:
        return host, 11883


def _dashboard_port(env: dict) -> int:
    """EMQX Dashboard 在宿主机上的映射端口（compose 默认 28083）。"""
    raw = env.get("EMQX_HTTP_HOST_PORT", "")
    if raw and ":" in raw:
        return int(raw.rsplit(":", 1)[1])
    if raw.isdigit():
        return int(raw)
    return 28083


def _emqx_api_login(env: dict) -> tuple[str | None, str]:
    """用 Dashboard 账号换 API token，返回 (token, base_url)。"""
    user = env.get("EMQX_DASHBOARD_USER", "admin")
    pwd = env.get("EMQX_DASHBOARD_PASSWORD", "")
    if not pwd:
        return None, ""
    base = f"http://127.0.0.1:{_dashboard_port(env)}"
    body = json.dumps({"username": user, "password": pwd}).encode()
    req = urllib.request.Request(f"{base}/api/v5/login", data=body,
                                 headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:  # noqa: S310
            token = json.loads(resp.read().decode()).get("token")
            return token, base
    except Exception:  # noqa: BLE001
        return None, base


def check_emqx(cfg: dict, env: dict) -> bool:
    """真实探测 broker 可达 + 后端确实订阅成功（R10 P1-6）。

    旧实现无条件 return True，于是体检永远打印"链路体检通过"，
    但后端可能根本没开 MQTT（EMQX_ENABLED=false），一条数据都进不来。
    """
    print("[4/4] EMQX 订阅是否生效")
    host, port = _emqx_host_port(env)
    mqtt_url = f"tcp://{host}:{port}"

    try:
        with socket.create_connection((host, port), timeout=3):
            print(f"      OK  broker 可达 {mqtt_url}")
    except Exception as exc:  # noqa: BLE001
        print(f"      !!  broker 不可达 {mqtt_url}: {exc}")
        print("          先启动容器： docker compose up -d emqx")
        return False

    token, base = _emqx_api_login(env)
    if not token:
        print(f"      !!  无法登录 EMQX API（{base}），无法确认后端是否已订阅")
        print("          检查 .env 的 EMQX_DASHBOARD_USER / EMQX_DASHBOARD_PASSWORD")
        return False

    try:
        req = urllib.request.Request(f"{base}/api/v5/clients?limit=200",
                                     headers={"Authorization": f"Bearer {token}"})
        with urllib.request.urlopen(req, timeout=5) as resp:  # noqa: S310
            clients = json.loads(resp.read().decode()).get("data", [])
    except Exception as exc:  # noqa: BLE001
        print(f"      !!  查询客户端列表失败: {exc}")
        return False

    subscribers = [c for c in clients
                   if str(c.get("clientid", "")).startswith("disasterguard-backend-")
                   and c.get("connected")]
    if not subscribers:
        print("      !!  没有已连接的后端订阅者（clientid 前缀 disasterguard-backend-）")
        print("          最常见原因：EMQX_ENABLED=false 或后端未重启")
        print("          自检：后端日志应出现 'EMQX 已订阅主题: disasterguard/+/data'")
        return False

    print(f"      OK  后端订阅者在线的实例 {len(subscribers)} 个 → 数据链路通")
    return True


# ------------------------------------------------------------------ 重置

def reset_sandbox(cfg: dict) -> None:
    """复用 register_devices.py 的重置 SQL，保证只有一份实现。"""
    sys.path.insert(0, str(TOOLS_DIR))
    from register_devices import RESET_SQL  # noqa: PLC0415

    import psycopg2  # noqa: PLC0415

    with psycopg2.connect(**cfg) as conn, conn.cursor() as cur:
        cur.execute(RESET_SQL)
    print("[RESET] 已清空沙盘指标 + 归档未关闭预警 + 清理评分日志")


# ------------------------------------------------------------------ 演练

def run(scenario: str, steps: int, interval: float, cfg: dict, python: str,
        gateway_log_path: Path) -> int:
    import psycopg2

    conn = psycopg2.connect(**cfg)
    conn.autocommit = True
    cur = conn.cursor()
    cur.execute("SELECT COALESCE(MAX(id), 0) FROM warning_event")
    last_id = cur.fetchone()[0]
    start_id = last_id
    created = 0

    cmd = [python, str(GATEWAY), "--mode", "scenario", "--scenario", scenario,
           "--interval", str(interval)]
    if steps > 0:
        cmd += ["--steps", str(steps)]

    print(f"\n[RUN] 剧本 {scenario}，{steps if steps else '不限'} 步 × {interval}s")
    print("[RUN] 数据流：沙盘 → EMQX → 后端 → device_metric → 规则引擎 → warning_event\n")

    # 网关输出必须落到文件而不是 PIPE：PIPE 不消费时会被写满（Windows 约 4KB），
    # 网关进程随即阻塞在 write 上，表现为「数据突然停了」且没有任何报错。
    gateway_log = open(gateway_log_path, "w", encoding="utf-8")  # noqa: SIM115
    print(f"[RUN] 网关日志：{gateway_log_path}")

    proc = subprocess.Popen(cmd, cwd=str(GATEWAY.parent),
                            stdout=gateway_log, stderr=subprocess.STDOUT,
                            text=True, encoding="utf-8", errors="ignore")

    probes = SCENARIO_PROBE.get(scenario, [])
    tick = 0
    interrupted = False
    try:
        while proc.poll() is None:
            time.sleep(interval)
            tick += 1
            # 当前指标快照
            snapshot = []
            for dev, metric, label in probes:
                cur.execute(
                    "SELECT value FROM device_metric WHERE device_code=%s AND metric_code=%s "
                    "ORDER BY recorded_at DESC LIMIT 1", (dev, metric),
                )
                row = cur.fetchone()
                snapshot.append(f"{label}={row[0] if row else '-'}")

            # 本轮新增预警
            cur.execute(
                "SELECT rule_code, hazard_code, warning_level, trigger_value "
                "FROM warning_event WHERE id > %s ORDER BY id", (last_id,),
            )
            new_warnings = cur.fetchall()
            if new_warnings:
                created += len(new_warnings)
                last_id = None  # 下面重新取最大 id
                for w in new_warnings:
                    print(f"  >>> 预警 {w[0]} | {w[1]} | {w[2]} | {w[3]}")
                cur.execute("SELECT COALESCE(MAX(id), 0) FROM warning_event")
                last_id = cur.fetchone()[0]

            if tick % 5 == 0:
                print(f"  [{tick * interval:6.0f}s] " + "  ".join(snapshot))
    except KeyboardInterrupt:
        print("\n[STOP] 手动中断")
        interrupted = True
        proc.terminate()
    finally:
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
        gateway_log.close()
        print(f"\n[RUN] 网关退出（code={proc.returncode}），共上报 {tick} 轮")

    summarize(cur, start_id)
    cur.close()
    conn.close()

    # P2-1：旧实现无条件 return 0，网关崩了也算成功，自动化无法判定失败
    if interrupted:
        return 130
    if proc.returncode not in (0, None):
        print(f"[FAIL] 网关异常退出（code={proc.returncode}），详见上方日志路径")
        return 1
    if tick == 0:
        print("[FAIL] 网关一轮都没跑起来（tick=0），检查剧本文件与 paho-mqtt 依赖")
        return 1
    if created == 0:
        print("[WARN] 数据已跑通但本轮没有触发任何预警（剧本未到触发点，或规则/阈值不匹配）")
        return 3
    print(f"[OK] 本轮新增 {created} 条预警")
    return 0


def summarize(cur, since_id: int) -> None:
    print("\n" + "=" * 74)
    print("本轮沙盘预警结果（status=待确认表示等待人工复核）")
    print("=" * 74)
    # P2-2：按开跑时的 last_id 取下界，只统计本轮新增；旧实现查全库最近 10 条，
    # 上一轮残留的预警会被当成这一轮的结果。
    # 注意：LIKE 的 % 必须走参数或写成 %%，否则 psycopg2 会把它当占位符
    # （R10 修复时踩过：LIKE 'HZ-SBOX-%' 直接 IndexError: tuple index out of range）
    cur.execute(
        "SELECT rule_code, hazard_code, warning_level, trigger_value, status, "
        "       to_char(triggered_at, 'HH24:MI:SS') "
        "FROM warning_event WHERE id > %s AND hazard_code LIKE %s "
        "ORDER BY id", (since_id, 'HZ-SBOX-%')
    )
    rows = cur.fetchall()
    if not rows:
        print("  （本轮无新增）—— 检查：后端是否 EMQX_ENABLED=true、网关 interval 是否 >=1s、"
              "剧本是否跑到触发点")
        return
    print(f"  {'规则':<6}{'隐患点':<14}{'等级':<10}{'触发值':<28}{'状态':<8}{'时间'}")
    for r in rows:
        print(f"  {r[0]:<6}{r[1]:<14}{r[2]:<10}{r[3]:<28}{r[4]:<8}{r[5]}")


# ------------------------------------------------------------------ main

def main() -> int:
    # 重定向到文件时保持行缓冲，否则日志要等进程结束才落盘，现场看不到数据在动
    try:
        sys.stdout.reconfigure(encoding="utf-8", line_buffering=True)
    except Exception:  # noqa: BLE001
        pass

    ap = argparse.ArgumentParser(description="沙盘 → 平台 端到端预警演练")
    ap.add_argument("--scenario", default="s1", choices=["s1", "s2", "s3", "s4"],
                    help="剧本：s1 滑坡 / s2 崩塌 / s3 江洪 / s4 容错")
    ap.add_argument("--steps", type=int, default=0, help="网关轮次，0=按剧本跑完（s1 约 165 轮）")
    ap.add_argument("--interval", type=float, default=2.0, help="上报间隔秒（后端唯一索引要求 >=1）")
    ap.add_argument("--reset", action="store_true", help="先重置上一轮沙盘数据（二次演示必做）")
    ap.add_argument("--check", action="store_true", help="只做链路体检，不跑数据")
    args = ap.parse_args()

    env = load_env(ENV_FILE)
    cfg = db_config(env)
    python = resolve_python()

    ok = check_containers()
    ok = check_backend() and ok
    ok = check_sandbox_devices(cfg) and ok
    ok = check_emqx(cfg, env) and ok
    if not ok:
        print("\n[FAIL] 体检未通过，先修上面的 !! 项")
        return 1
    if args.check:
        print("\n[OK] 链路体检通过")
        return 0

    if args.reset:
        reset_sandbox(cfg)
    else:
        print("[WARN] 未加 --reset：上一轮未关闭的预警会让同一规则不再建单（表现为哑火）。")
        print("       R10 P1-4 起，静默超过 auto-resolve-after-minutes（默认 30 分钟）会自动解除；")
        print("       现场连演请加 --reset 立即清零。")

    if args.steps <= 0:
        args.steps = {"s1": 165, "s2": 110, "s3": 100, "s4": 95}.get(args.scenario, 120)

    import tempfile  # noqa: PLC0415

    gateway_log_path = Path(tempfile.gettempdir()) / f"dg-gateway-{args.scenario}.log"
    return run(args.scenario, args.steps, args.interval, cfg, python, gateway_log_path)


if __name__ == "__main__":
    sys.exit(main())
