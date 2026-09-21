#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
GL-Sandbox 端到端自检

专门解决"数据明明进去了，预警就是不来"这一类问题——
在做演示之前，静态地把规则链路检查一遍。

  python selftest.py              # 静态检查配置
  python selftest.py --ping       # 额外测试 MQTT 连通性
  python selftest.py --probe      # 额外发一条测试报文（需要 paho-mqtt）

检查项
------
  1. 设备是否都绑定了 hazard_code（未绑定的指标会被规则引擎丢弃）
  2. 每条预警规则的"哨兵指标"是否有人上报（★ 漏了规则永不触发）
  3. 规则表达式里的字段，在其目标隐患点下是否齐全
  4. 同一隐患点下是否有重复的 metric_code（会互相覆盖，结果不确定）
  5. 上报间隔是否 >= 1s（否则被唯一索引去重吞掉）
"""

from __future__ import annotations

import argparse
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
GATEWAY_DIR = os.path.dirname(HERE)
DEFAULT_CFG = os.path.join(GATEWAY_DIR, "gateway", "config.yaml")

# 与 backend/src/main/resources/data.sql 中 warning_rule 保持一致
RULES = [
    ("R001", "滑坡黄色预警（雨量+坡度）", "rainfall_24h_slope",
     "rainfall_24h > 50 AND slope > 30", ["rainfall_24h", "slope"]),
    ("R002", "滑坡橙色预警（雨量+位移）", "rainfall_24h_disp",
     "rainfall_24h > 80 AND displacement_rate > 5", ["rainfall_24h", "displacement_rate"]),
    ("R003", "洪水红色预警（水位+雨量）", "water_level_rain6h",
     "water_level > warning_level AND rainfall_6h > 100",
     ["water_level", "warning_level", "rainfall_6h"]),
    ("R006", "崩塌黄色预警（裂缝扩展）", "crack_rate_rain",
     "crack_width_rate > 2.0 AND rainfall_24h > 30", ["crack_width_rate", "rainfall_24h"]),
]


def load_cfg(path):
    try:
        import yaml
    except ImportError:
        sys.exit("[ERR] 需要 pyyaml： pip install pyyaml")
    with open(path, "r", encoding="utf-8") as f:
        return yaml.safe_load(f)


def main():
    ap = argparse.ArgumentParser(description="GL-Sandbox 自检")
    ap.add_argument("--config", default=DEFAULT_CFG)
    ap.add_argument("--ping", action="store_true", help="测试 MQTT 连通性")
    ap.add_argument("--probe", action="store_true", help="发一条测试报文")
    args = ap.parse_args()

    cfg = load_cfg(args.config)
    devices = cfg.get("devices", [])
    ok = True

    print("=" * 68)
    print(" GL-Sandbox 自检报告")
    print("=" * 68)

    # 1. hazard_code 绑定
    print("\n[1] 设备隐患点绑定")
    no_hazard = [d for d in devices if not d.get("hazard")]
    for d in devices:
        mark = "OK " if d.get("hazard") else "FAIL"
        print(f"  {mark} {d['code']:<14} hazard={d.get('hazard')}")
    if no_hazard:
        ok = False
        print(f"  -> {len(no_hazard)} 台未绑定隐患点，其数据不会进入规则引擎")

    # 2 & 3. 哨兵覆盖 + 表达式字段
    print("\n[2] 规则哨兵指标覆盖（★ 缺失则规则永不触发）")
    hazard_metrics = {}
    hazard_sentinels = {}
    for d in devices:
        h = d.get("hazard")
        if not h:
            continue
        hazard_metrics.setdefault(h, set()).update(d.get("metrics") or [])
        hazard_sentinels.setdefault(h, set()).update(d.get("sentinels") or [])

    for code, name, sentinel, expr, fields in RULES:
        owners = [h for h, s in hazard_sentinels.items() if sentinel in s]
        if not owners:
            ok = False
            print(f"  FAIL {code} {name}: 无任何隐患点上报哨兵 '{sentinel}'")
            continue
        for h in owners:
            have = hazard_metrics[h] | hazard_sentinels[h]
            missing = [f for f in fields if f not in have]
            if missing:
                ok = False
                print(f"  FAIL {code} @{h}: 表达式缺字段 {missing}  ({expr})")
            else:
                print(f"  OK   {code} @{h}: 可触发  ({expr})")

    # 4. 同隐患点重复 metric
    print("\n[3] 同一隐患点内 metric_code 冲突")
    conflict = False
    for h in sorted(hazard_metrics.keys()):
        seen = {}
        for d in devices:
            if d.get("hazard") != h:
                continue
            for m in (d.get("metrics") or []) + (d.get("sentinels") or []):
                if m in seen and seen[m] != d["code"]:
                    print(f"  FAIL {h}: '{m}' 被 {seen[m]} 与 {d['code']} 重复上报")
                    conflict = True
                    ok = False
                seen[m] = d["code"]
        if not conflict:
            print(f"  OK   {h}: 无冲突")
    conflict = False  # 重置，逐个 hazard 判定

    # 5. 上报间隔
    print("\n[4] 上报节奏")
    interval = float(cfg.get("publish", {}).get("interval_sec", 2))
    if interval < 1.0:
        ok = False
        print(f"  FAIL interval={interval}s < 1s，同毫秒会被 (device,metric,ts) 唯一索引去重")
    else:
        print(f"  OK   interval={interval}s")

    # 6. 规则调度周期提醒
    print("\n[5] 后端规则调度（需人工确认）")
    print("  默认 cron = */30 * * * * *（30s）。演示期建议收紧：")
    print('    disasterguard.warning.rule-engine-cron: "*/5 * * * * *"')

    # MQTT
    if args.ping or args.probe:
        print("\n[6] MQTT 连通性")
        mq = cfg.get("mqtt", {})
        try:
            import paho.mqtt.client as mqtt
            c = mqtt.Client(client_id="gl-sandbox-selftest")
            u = mq.get("username")
            if u:
                c.username_pw_set(u, mq.get("password", ""))
            c.connect(mq.get("host", "127.0.0.1"), int(mq.get("port", 11883)), 10)
            c.loop_start()
            print(f"  OK   已连接 {mq.get('host')}:{mq.get('port')}")
            if args.probe:
                dev = devices[0]
                topic = f"disasterguard/{dev['code']}/data"
                payload = '{"ts":%d,"metrics":{"selftest":1.0}}' % (int(__import__("time").time() * 1000))
                c.publish(topic, payload, qos=int(mq.get("qos", 1)))
                print(f"  OK   已发送探测报文 -> {topic}")
                print("       预期后端日志出现：设备状态已变更为在线 / 或 '无有效指标'")
            c.loop_stop()
            c.disconnect()
        except Exception as e:
            ok = False
            print(f"  FAIL MQTT 连接失败: {e}")
            print("       确认 EMQX 已启动： docker compose ps emqx")

    print("\n" + "=" * 68)
    print(" 结论：" + ("全部通过，可以上场演示" if ok else "存在问题，请先修复（见 FAIL 项）"))
    print("=" * 68)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
