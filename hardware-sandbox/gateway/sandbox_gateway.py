#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
GL-Sandbox 边缘网关：把沙盘上的真实传感器读数换算成真实量纲，
按 DisasterGuard 设备接入契约推送到 EMQX。

    ESP32 节点 --串口--> [本网关: 标定 + 时间压缩 + 哨兵注入] --MQTT--> EMQX
                                                                  --> Spring Boot
                                                                  --> 规则引擎 / AI

三种运行模式
------------
  serial     从串口读 ESP32（需要 pip install pyserial）
  dryrun     合成传感器数据，无硬件也能跑通全链路（需要 paho-mqtt 才能真发）
  scenario   按剧本驱动（gateway/scenario_player.py），数值可控，适合汇报

没有硬件时先这样验证：
    python sandbox_gateway.py --mode dryrun --print-only --once

真发数据：
    python sandbox_gateway.py --mode dryrun
    python sandbox_gateway.py --mode scenario --scenario s1
    python sandbox_gateway.py --mode serial
"""

from __future__ import annotations

import argparse
import json
import os
import random
import sys
import time
from collections import deque

try:
    import yaml
except ImportError:
    sys.exit("[ERR] 需要 pyyaml： pip install pyyaml")

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_CFG = os.path.join(HERE, "config.yaml")


# ---------------------------------------------------------------- 工具

def now_ms() -> int:
    return int(time.time() * 1000)


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


class RainHistory:
    """
    演示时钟下的降雨窗口统计。

    时间压缩后：真实 24h = 演示 240s（time_compress=360）。
    因此"过去真实 1 小时的雨量" == "过去演示 10 秒的累积增量"。
    用环形缓冲按演示时钟求增量，rainfall_1h/6h/24h 自动保持一致。
    """

    def __init__(self, time_compress: float):
        self.tc = time_compress
        self.buf = deque()          # (demo_sec, cumulative_mm)
        self.cumulative = 0.0

    def add(self, demo_sec: float, cumulative_mm: float):
        self.cumulative = cumulative_mm
        self.buf.append((demo_sec, cumulative_mm))
        # 只保留 24h 对应的演示窗口
        cutoff = demo_sec - 86400.0 / self.tc
        while len(self.buf) > 2 and self.buf[0][0] < cutoff:
            self.buf.popleft()

    def window_mm(self, real_seconds: float, demo_now: float) -> float:
        """过去 real_seconds（真实秒）内的降雨增量 mm。"""
        if not self.buf:
            return 0.0
        cutoff = demo_now - real_seconds / self.tc
        base = self.buf[0][1]
        for t, v in self.buf:
            if t >= cutoff:
                break
            base = v
        return max(0.0, self.cumulative - base)


# ---------------------------------------------------------------- 标定

class Calibrator:
    """沙盘原始量 -> 真实量纲。"""

    def __init__(self, cfg):
        c = cfg["calibration"]
        self.c = c
        self.tc = float(c["time_compress"])
        w = c["water"]
        self.w_base = float(w["base_m"])
        self.w_span = float(w["span_m"])
        self.w_depth = float(w["depth_mm"])
        self.rain = RainHistory(self.tc)
        self._prev = {}          # key -> (demo_sec, value)
        self._demo_t0 = time.time()

    def demo_sec(self) -> float:
        return time.time() - self._demo_t0

    # --- 单点换算
    def soil_moisture(self, adc: float) -> float:
        dry = float(self.c["soil_dry_adc"])
        wet = float(self.c["soil_wet_adc"])
        if dry == wet:
            return 0.0
        return clamp((dry - adc) / (dry - wet) * 100.0, 0.0, 100.0)

    def water_level(self, depth_mm: float) -> float:
        return self.w_base + clamp(depth_mm, 0.0, self.w_depth) / self.w_depth * self.w_span

    def crack_width(self, adc: float) -> float:
        zero = float(self.c["crack_zero_adc"])
        span = float(self.c["crack_span_adc"])
        travel = float(self.c["crack_travel_mm"])
        gain = float(self.c["crack_gain"])
        return clamp((adc - zero) / span, 0.0, 1.0) * travel * gain

    def _rate(self, key: str, value: float, gain: float, unit_seconds: float) -> float:
        """由沙盘位移求真实速率：value 单位/unit_seconds。"""
        now = self.demo_sec()
        prev = self._prev.get(key)
        self._prev[key] = (now, value)
        if prev is None:
            return 0.0
        dt_demo = now - prev[0]
        if dt_demo <= 1e-6:
            return 0.0
        delta_real = (value - prev[1]) * gain
        dt_real_sec = dt_demo * self.tc
        return delta_real / dt_real_sec * unit_seconds

    def displacement_rate(self, disp_mm: float) -> float:
        """mm/h"""
        return self._rate("disp", disp_mm, float(self.c["disp_gain"]), 3600.0)

    def crack_rate(self, crack_adc: float) -> float:
        """mm/day"""
        return self._rate("crack", crack_adc, float(self.c["crack_gain"]), 86400.0)


# ---------------------------------------------------------------- 指标组装

def build_payloads(raw: dict, cal: Calibrator, cfg, demo_sec: float) -> dict:
    """
    raw: {node_name: {sensor_key: value}}  ->  {device_code: {metric: value}}

    传感器命名约定（与 firmware/esp32_node.ino 一致）：
      Node-A: rain_tips, water_mm, water2_mm, pond_cm, temp_c, humidity
      Node-B: soil1_adc, soil2_adc, soil3_adc, disp1_mm, disp2_mm, pore_kpa
      Node-C: tilt1_deg, tilt2_deg, crack1_adc, crack2_adc
    """
    c = cfg["calibration"]
    a = raw.get("Node-A", {})
    b = raw.get("Node-B", {})
    cc = raw.get("Node-C", {})

    # 降雨：翻斗脉冲 -> 累积 mm（时间压缩下即"24h 累积"）
    tips = float(a.get("rain_tips", 0.0))
    rain_mm = tips * float(c["rain_bucket_mm"]) * float(c["rain_gain"])
    cal.rain.add(demo_sec, rain_mm)
    r1 = cal.rain.window_mm(3600.0, demo_sec)
    r6 = cal.rain.window_mm(21600.0, demo_sec)
    r24 = cal.rain.window_mm(86400.0, demo_sec)

    # 坡体：多点取均值/极值
    soils = [b.get(k) for k in ("soil1_adc", "soil2_adc", "soil3_adc") if b.get(k) is not None]
    soil = sum(cal.soil_moisture(s) for s in soils) / len(soils) if soils else 0.0
    disps = [b.get(k) for k in ("disp1_mm", "disp2_mm") if b.get(k) is not None]
    disp_mean = sum(disps) / len(disps) if disps else 0.0
    disp_total = max(disps) if disps else 0.0

    tilts = [cc.get(k) for k in ("tilt1_deg", "tilt2_deg") if cc.get(k) is not None]
    tilt = max(tilts) if tilts else 0.0
    cracks = [cc.get(k) for k in ("crack1_adc", "crack2_adc") if cc.get(k) is not None]
    crack_adc = max(cracks) if cracks else float(c["crack_zero_adc"])

    water = cal.water_level(a.get("water_mm", 0.0))

    out = {
        "SB-RAIN-01": {
            "rainfall_1h": round(r1, 2),
            "rainfall_6h": round(r6, 2),
            "rainfall_24h": round(r24, 2),
            "rainfall_30d": round(r24 * 1.6 + 40.0, 2),
        },
        "SB-SOIL-01": {
            "soil_moisture": round(soil, 1),
            "pore_pressure": round(float(b.get("pore_kpa", 0.0)), 2),
        },
        "SB-GNSS-01": {
            "displacement_rate": round(cal.displacement_rate(disp_mean), 2),
            "displacement_total": round(max(0.0, disp_total * float(c["disp_gain"])), 2),
            "slope": float(c["slope_deg"]),
            "elevation_m": float(c["elevation_m"]),
        },
        "SB-INCL-01": {"tilt_angle": round(float(tilt), 2)},
        "SB-CRACK-01": {
            "crack_width": round(cal.crack_width(crack_adc), 2),
            "crack_width_rate": round(cal.crack_rate(crack_adc), 2),
            "rainfall_24h": round(r24, 2),
        },
        "WS-RIVER-01": {
            "water_level": round(water, 2),
            "warning_level": float(c["warning_level_m"]),
            "rainfall_6h": round(r6, 2),
        },
        "SB-FLOOD-01": {
            "ponding_depth": round(float(a.get("pond_cm", 0.0)), 1),
            "rainfall_1h": round(r1, 2),
        },
    }
    return out


def inject_sentinels(payloads: dict, cfg) -> dict:
    """
    ★ 注入哨兵指标。

    后端 RuleEngineScheduler 有门控：
        if (!metrics.containsKey(rule.getMetricCode())) continue;
    而 warning_rule.metric_code 是组合键（rainfall_24h_disp 等），不是真实物理量。
    不注入这些键，规则永远不会触发——这是沙盘接入最容易踩的坑。
    """
    for dev in cfg["devices"]:
        code = dev["code"]
        if code not in payloads:
            continue
        for s in dev.get("sentinels") or []:
            payloads[code][s] = 1.0
    return payloads


# ---------------------------------------------------------------- 数据源

def read_serial(cfg):
    try:
        import serial
    except ImportError:
        sys.exit("[ERR] 串口模式需要 pyserial： pip install pyserial")
    ports = {}
    for s in cfg.get("serial", []):
        try:
            ports[s["node"]] = serial.Serial(s["port"], s.get("baud", 115200), timeout=1)
        except Exception as e:
            print(f"[WARN] 打开 {s['port']} 失败: {e}")
    while True:
        raw = {}
        for node, p in ports.items():
            try:
                line = p.readline().decode("utf-8", "ignore").strip()
                if line.startswith("{"):
                    raw[node] = json.loads(line)
            except Exception:
                pass
        yield raw


def synthetic_raw(state):
    """无硬件时合成传感器读数（含噪声），用于验证标定链路。"""
    st = state
    st["t"] += 1
    t = st["t"]
    rain_rate = st.get("rain_rate", 0.0)          # 斗/帧
    st["tips"] += rain_rate + random.uniform(0, 0.4) * (rain_rate > 0)
    st["disp"] += st.get("disp_speed", 0.0)
    st["crack"] += st.get("crack_speed", 0.0)
    st["water"] += st.get("water_speed", 0.0)
    st["pond"] += st.get("pond_speed", 0.0)

    def n(v, s):
        return v + random.uniform(-s, s)

    return {
        "Node-A": {
            "rain_tips": round(st["tips"]),
            "water_mm": clamp(n(st["water"], 0.4), 0, 80),
            "water2_mm": clamp(n(st["water"] * 0.9, 0.4), 0, 80),
            "pond_cm": clamp(n(st["pond"], 0.3), 0, 60),
            "temp_c": round(n(24.0, 0.3), 1),
            "humidity": round(clamp(n(78.0, 1.0), 0, 100), 1),
        },
        "Node-B": {
            "soil1_adc": round(n(2900 - st["soil"] * 17.2, 25)),
            "soil2_adc": round(n(2880 - st["soil"] * 16.9, 25)),
            "soil3_adc": round(n(2920 - st["soil"] * 17.5, 25)),
            "disp1_mm": round(n(st["disp"], 0.02), 3),
            "disp2_mm": round(n(st["disp"] * 0.94, 0.02), 3),
            "pore_kpa": round(clamp(n(st["soil"] * 0.12, 0.2), 0, 20), 2),
        },
        "Node-C": {
            "tilt1_deg": round(clamp(n(st["tilt"], 0.05), 0, 90), 2),
            "tilt2_deg": round(clamp(n(st["tilt"] * 0.87, 0.05), 0, 90), 2),
            "crack1_adc": round(512 + st["crack"] * 30),
            "crack2_adc": round(512 + st["crack"] * 24),
        },
    }


# ---------------------------------------------------------------- 发布

class Publisher:
    def __init__(self, cfg, print_only: bool):
        self.cfg = cfg["mqtt"]
        self.print_only = print_only
        self.client = None
        if not print_only:
            try:
                import paho.mqtt.client as mqtt
            except ImportError:
                sys.exit("[ERR] 发布需要 paho-mqtt： pip install paho-mqtt\n"
                        "      仅验证逻辑可加 --print-only")
            self.client = mqtt.Client(client_id=self.cfg.get("client_id"),
                                      protocol=mqtt.MQTTv311)
            u = self.cfg.get("username")
            if u:
                self.client.username_pw_set(u, self.cfg.get("password", ""))
            self.client.connect(self.cfg["host"], int(self.cfg["port"]),
                                int(self.cfg.get("keepalive", 30)))
            self.client.loop_start()

    def publish(self, device_code: str, metrics: dict):
        payload = {"ts": now_ms(), "metrics": metrics}
        topic = f"disasterguard/{device_code}/data"
        if self.print_only:
            print(f"[PUB] {topic}\n      {json.dumps(payload, ensure_ascii=False)}")
            return
        self.client.publish(topic, json.dumps(payload), qos=int(self.cfg.get("qos", 1)))
        print(f"[PUB] {topic} {len(metrics)} 指标")

    def publish_cmd(self, cmds: dict):
        """下行执行器指令。后端只订阅 disasterguard/+/data，不会误消费 cmd 主题。"""
        topic = self.cfg.get("cmd_topic", "disasterguard/sandbox/cmd")
        body = {"ts": now_ms(), "actuators": cmds}
        if self.print_only:
            print(f"[CMD] {topic} {json.dumps(body, ensure_ascii=False)}")
            return
        self.client.publish(topic, json.dumps(body), qos=int(self.cfg.get("qos", 1)))

    def close(self):
        if self.client:
            self.client.loop_stop()
            self.client.disconnect()


# ---------------------------------------------------------------- 主流程

def main():
    ap = argparse.ArgumentParser(description="GL-Sandbox 边缘网关")
    ap.add_argument("--config", default=DEFAULT_CFG)
    ap.add_argument("--mode", choices=["serial", "dryrun", "scenario"], default="dryrun")
    ap.add_argument("--scenario", default="s1", help="scenario 模式下的剧本名：s1/s2/s3/s4")
    ap.add_argument("--interval", type=float, default=None, help="覆盖 publish.interval_sec")
    ap.add_argument("--print-only", action="store_true", help="不连 MQTT，只打印 payload")
    ap.add_argument("--once", action="store_true", help="只跑一轮")
    ap.add_argument("--steps", type=int, default=0, help="跑 N 轮后退出，0=不限")
    args = ap.parse_args()

    with open(args.config, "r", encoding="utf-8") as f:
        cfg = yaml.safe_load(f)

    pub_cfg = cfg.get("publish", {})
    interval = args.interval or float(pub_cfg.get("interval_sec", 2))
    if interval < 1.0:
        print("[WARN] interval < 1s 会被后端 (device,metric,ts) 唯一索引去重，已抬到 1.0s")
        interval = 1.0

    cal = Calibrator(cfg)
    pub = Publisher(cfg, args.print_only)

    # 剧本模式：交给 scenario_player 驱动目标物理量
    player = None
    if args.mode == "scenario":
        sys.path.insert(0, HERE)
        try:
            import scenario_player
        except ImportError as e:
            sys.exit(f"[ERR] 载入 scenario_player 失败: {e}")
        player = scenario_player.load(args.scenario)

    state = {"t": 0, "tips": 12.0 / 0.5, "disp": 0.0, "crack": 0.0,
             "water": 20.0, "pond": 0.0, "soil": 45.0, "tilt": 3.2,
             "rain_rate": 0.0, "disp_speed": 0.0, "crack_speed": 0.0,
             "water_speed": 0.0, "pond_speed": 0.0}

    print(f"[INFO] 模式={args.mode} print_only={args.print_only} interval={interval}s")
    print("[INFO] Ctrl+C 退出")

    n = 0
    try:
        while True:
            demo_sec = cal.demo_sec()

            if args.mode == "serial":
                raw = next(read_serial(cfg))
                payloads = build_payloads(raw, cal, cfg, demo_sec)
            elif args.mode == "scenario":
                # 剧本直接给出标定后的物理量（可控），同时推进硬件执行器
                targets, cmds, offline = player.tick(demo_sec)
                payloads = player.to_payloads(targets)
                # 只上报本剧本生效隐患点下的设备，避免跨场景串台触发无关预警
                if player.hazards:
                    allowed = {d["code"] for d in cfg["devices"]
                               if d.get("hazard") in player.hazards}
                    payloads = {k: v for k, v in payloads.items() if k in allowed}
                for c in (offline or []):
                    payloads.pop(c, None)
                if cmds:
                    pub.publish_cmd(cmds)
            else:
                raw = synthetic_raw(state)
                payloads = build_payloads(raw, cal, cfg, demo_sec)

            payloads = inject_sentinels(payloads, cfg)

            for dev in cfg["devices"]:
                code = dev["code"]
                if code in payloads and payloads[code]:
                    pub.publish(code, payloads[code])

            n += 1
            if args.once or (args.steps and n >= args.steps):
                break
            time.sleep(interval)
    except KeyboardInterrupt:
        print("\n[INFO] 已停止")
    finally:
        pub.close()


if __name__ == "__main__":
    main()
