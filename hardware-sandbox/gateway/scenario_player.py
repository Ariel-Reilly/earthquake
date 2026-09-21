#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
GL-Sandbox 剧本编排器

按"演示时钟"驱动物理量曲线，并同步下发执行器指令（泵 / 步进 / 舵机）。
剧本数值是**标定后的真实量纲**，可直接命中后端规则阈值，保证汇报节奏可控。

时间轴单位为演示秒（已含 360× 时间压缩）。
由 gateway/sandbox_gateway.py --mode scenario 调用。
"""

from __future__ import annotations

import bisect


# ---------------------------------------------------------------- 剧本定义
# 每个关键点: (演示秒, {物理量: 值})
# act = 执行器指令 {pump: 占空比%, stepper: 0-100, servo: 角度, drain: 0/1}

S1_LANDSLIDE = [
    (0,   dict(rain24=12.0, soil=45.0, disp_rate=0.3, tilt=3.2), dict(pump=0)),
    (30,  dict(rain24=18.0, soil=48.0, disp_rate=0.4), dict(pump=35)),
    (90,  dict(rain24=31.0, soil=58.0, disp_rate=0.8), dict(pump=60)),
    (150, dict(rain24=46.0, soil=71.0, disp_rate=1.4), dict(pump=80)),
    # 03:20  R001: rainfall_24h > 50 AND slope > 30  ->  黄色预警
    (200, dict(rain24=53.0, soil=79.0, disp_rate=2.2), dict(pump=90, stepper=10)),
    (240, dict(rain24=68.0, soil=85.0, disp_rate=3.6), dict(pump=100, stepper=35)),
    # 04:40  R002: rainfall_24h > 80 AND displacement_rate > 5  ->  橙色预警
    (280, dict(rain24=84.0, soil=89.0, disp_rate=6.4), dict(pump=100, stepper=70)),
    (300, dict(rain24=92.0, soil=91.0, disp_rate=12.0), dict(pump=60, stepper=100)),
    (320, dict(rain24=96.0, soil=92.0, disp_rate=4.0), dict(pump=20, stepper=100)),
    (360, dict(rain24=98.0, soil=90.0, disp_rate=0.9), dict(pump=0, stepper=100)),
]

S2_COLLAPSE = [
    (0,   dict(rain24=20.0, crack_rate=0.8, crack_width=12.0, tilt=3.2), dict()),
    (60,  dict(rain24=28.0, crack_rate=1.6, crack_width=14.0, tilt=3.8), dict()),
    # R006: crack_width_rate > 2.0 AND rainfall_24h > 30  ->  黄色预警
    (110, dict(rain24=35.0, crack_rate=2.3, crack_width=16.5, tilt=5.5), dict()),
    (150, dict(rain24=38.0, crack_rate=3.5, crack_width=19.0, tilt=9.0), dict(servo=45)),
    (180, dict(rain24=40.0, crack_rate=4.0, crack_width=22.0, tilt=12.0), dict(servo=90)),
]

S3_FLOOD = [
    (0,   dict(rain24=30.0, rain6h=20.0, water=170.0, pond=0.0), dict(drain=0)),
    (60,  dict(rain24=55.0, rain6h=55.0, water=172.5, pond=2.0), dict(pump=60)),
    (120, dict(rain24=88.0, rain6h=88.0, water=174.6, pond=6.0), dict(pump=85)),
    # R003: water_level > warning_level(175.0) AND rainfall_6h > 100  ->  红色预警
    (170, dict(rain24=110.0, rain6h=105.0, water=175.8, pond=12.0), dict(pump=100, drain=1)),
    (200, dict(rain24=124.0, rain6h=118.0, water=176.9, pond=22.0), dict(pump=90, drain=1)),
    (240, dict(rain24=132.0, rain6h=125.0, water=177.5, pond=30.0), dict(pump=40, drain=1)),
]

S4_FAULT = [
    # 0-60s 正常；60-120s 让 SB-GNSS-01 离线（考察离线检测）；
    # 120-150s 恢复并注入跳变噪声（考察 AI 异常 vs 规则误报）
    (0,   dict(rain24=15.0, soil=50.0, disp_rate=0.5), dict()),
    (60,  dict(rain24=18.0, soil=52.0, disp_rate=0.6), dict(), ["SB-GNSS-01"]),
    (120, dict(rain24=22.0, soil=54.0, disp_rate=0.7), dict(), ["SB-GNSS-01"]),
    (125, dict(rain24=22.0, soil=54.0, disp_rate=28.0), dict()),   # 跳变噪声
    (140, dict(rain24=23.0, soil=55.0, disp_rate=0.8), dict()),
    (180, dict(rain24=25.0, soil=56.0, disp_rate=0.7), dict()),
]

# hazards：本剧本"生效的隐患点"。网关只上报属于这些隐患点的设备。
# 作用：避免串台——例如 S3 的雨量很大，若不隔离会顺带把滑坡点的 R001/R002 也打出来。
SCENARIOS = {
    "s1": ("S1 暴雨型滑坡（主剧本 6min）", S1_LANDSLIDE, ["HZ-SBOX-01"]),
    "s2": ("S2 危岩崩塌（3min）", S2_COLLAPSE, ["HZ-SBOX-02"]),
    "s3": ("S3 江洪+城市内涝（4min）", S3_FLOOD, ["HZ-SBOX-03", "HZ-SBOX-04"]),
    "s4": ("S4 反例与容错（3min）", S4_FAULT, ["HZ-SBOX-01"]),
}

WARNING_LEVEL = 175.0
SLOPE = 32.0
ELEV = 245.0


# ---------------------------------------------------------------- 播放器

class Scenario:
    def __init__(self, key: str, title: str, keys: list, hazards: list | None = None):
        self.key = key
        self.title = title
        self.times = [k[0] for k in keys]
        self.keys = keys
        self.hazards = hazards or []
        self._disp_total = 0.0
        self._last_t = 0.0

    def _interp(self, t: float) -> tuple[dict, dict, list]:
        idx = bisect.bisect_right(self.times, t)
        if idx == 0:
            k = self.keys[0]
            return dict(k[1]), dict(k[2]), list(k[3]) if len(k) > 3 else []
        if idx >= len(self.keys):
            k = self.keys[-1]
            return dict(k[1]), dict(k[2]), list(k[3]) if len(k) > 3 else []
        k0, k1 = self.keys[idx - 1], self.keys[idx]
        t0, t1 = k0[0], k1[0]
        f = 0.0 if t1 == t0 else (t - t0) / (t1 - t0)
        vals = {}
        for key in k0[1]:
            v0 = k0[1].get(key)
            v1 = k1[1].get(key, v0)
            vals[key] = v0 + (v1 - v0) * f if isinstance(v0, (int, float)) else v0
        act = dict(k0[2])
        off = list(k0[3]) if len(k0) > 3 else []
        return vals, act, off

    def tick(self, demo_sec: float) -> tuple[dict, dict, list]:
        """
        返回 (目标物理量, 执行器指令, 离线设备列表)
        """
        vals, act, off = self._interp(demo_sec)
        dt = max(0.0, demo_sec - self._last_t)
        self._last_t = demo_sec
        # 位移总量由速率积分得到（mm/h -> mm）
        self._disp_total += vals.get("disp_rate", 0.0) * dt / 3600.0
        vals["disp_total"] = round(self._disp_total, 2)
        return vals, act, off

    def to_payloads(self, vals: dict) -> dict:
        """目标物理量 -> {device_code: {metric: value}}"""
        rain24 = vals.get("rain24", 0.0)
        rain6 = vals.get("rain6h", rain24 * 0.78)
        rain1 = vals.get("rain1h", rain24 * 0.22)
        soil = vals.get("soil", 45.0)
        water = vals.get("water", 168.0)
        pond = vals.get("pond", 0.0)
        disp_rate = vals.get("disp_rate", 0.0)
        crack_rate = vals.get("crack_rate", 0.0)
        crack_w = vals.get("crack_width", 0.0)
        tilt = vals.get("tilt", 3.2)

        return {
            "SB-RAIN-01": {
                "rainfall_1h": round(rain1, 2),
                "rainfall_6h": round(rain6, 2),
                "rainfall_24h": round(rain24, 2),
                "rainfall_30d": round(rain24 * 1.6 + 40.0, 2),
            },
            "SB-SOIL-01": {
                "soil_moisture": round(soil, 1),
                "pore_pressure": round(soil / 100.0 * 12.0, 2),
            },
            "SB-GNSS-01": {
                "displacement_rate": round(disp_rate, 2),
                "displacement_total": round(vals.get("disp_total", 0.0), 2),
                "slope": SLOPE,
                "elevation_m": ELEV,
            },
            "SB-INCL-01": {"tilt_angle": round(tilt, 2)},
            "SB-CRACK-01": {
                "crack_width": round(crack_w, 2),
                "crack_width_rate": round(crack_rate, 2),
                "rainfall_24h": round(rain24, 2),
            },
            "WS-RIVER-01": {
                "water_level": round(water, 2),
                "warning_level": WARNING_LEVEL,
                "rainfall_6h": round(rain6, 2),
            },
            "SB-FLOOD-01": {
                "ponding_depth": round(pond, 1),
                "rainfall_1h": round(rain1, 2),
            },
        }


def load(name: str) -> Scenario:
    key = (name or "s1").lower()
    if key not in SCENARIOS:
        raise KeyError(f"未知剧本 {name}，可选: {', '.join(SCENARIOS)}")
    title, keys, hazards = SCENARIOS[key]
    print(f"[INFO] 剧本 {key}: {title}  生效隐患点={hazards}")
    return Scenario(key, title, keys, hazards)


if __name__ == "__main__":
    # 自检：打印各剧本关键时间点的指标，确认能命中规则阈值
    for k in SCENARIOS:
        sc = load(k)
        print(f"\n=== {sc.title} ===")
        base = sc.keys[0][1]
        for name, test in (
            ("R001 雨量>50", lambda v: v.get("rain24", 0) > 50),
            ("R002 雨量>80 且 位移>5", lambda v: v.get("rain24", 0) > 80 and v.get("disp_rate", 0) > 5),
            ("R003 水位>175 且 6h雨量>100", lambda v: v.get("water", 0) > WARNING_LEVEL and v.get("rain6h", 0) > 100),
            ("R006 裂缝速率>2 且 雨量>30", lambda v: v.get("crack_rate", 0) > 2.0 and v.get("rain24", 0) > 30),
        ):
            hit = [t for t, v, *_ in sc.keys if test(v)]
            print(f"  {name}: {'命中 @ ' + str(hit[0]) + 's' if hit else '未命中'}")
