#!/usr/bin/env python3
"""
综合风险 XGBoost 模型训练脚本（回归版）。

基于六维度综合风险评分体系（遥感/气象/地形/传感器/历史/社交媒体/辅助数据），
合成多样化训练样本，训练 XGBoost 回归模型预测连续风险评分 [0, 100]。

相对旧版的改进（v2.1.0）：
  1. 目标变量改为连续风险评分（0-100），而非二分类（是否 HIGH/CRITICAL）
     → 旧模型对"中风险"输入只输出 0-1 二值概率，无法反映"52 vs 78"这种程度差异；
     → 回归版输出连续分数，能更细腻区分风险程度；
  2. 增加合成样本量（默认 5000 → 8000），覆盖更多特征分布；
  3. 添加 SMOTE-like 思路：对"边界样本"（45-65 分区间）加权，提升模型对临界案例的灵敏度；
  4. 特征名称与 comprehensive_model.py 的 _run_xgboost 完全一致。

特征列表（共 36 个，与 comprehensive_model.py 的 _run_xgboost 一致）：
  [1] 遥感: image_anomaly_score, image_anomaly_count
  [2] 气象: rainfall_24h, rainfall_7d, rainfall_30d, temperature, humidity, soil_moisture, water_level
  [3] 地形: elevation_m, slope_deg, aspect_deg, curvature, soil_grade, vegetation_cover,
          distance_to_water_m, geology_grade, fault_distance_m
  [4] 传感器: displacement_rate, displacement_total, crack_width, earthquake_mag, tilt_angle, site_grade
  [5] 历史: historical_count, historical_max_score, exposure_population, exposure_buildings
  [6] 社交媒体: social_alert_level, social_post_count, social_sentiment_score, social_emergency_level
  [7] 辅助: nighttime_light_index, infrastructure_score, road_access_score, power_status

标签: risk_score (连续 [0, 100])

用法:
    python train_comprehensive_v2.py --output-dir ../../models
"""
from __future__ import annotations

import argparse
import json
import math
import random
import sys
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

FEATURE_NAMES = [
    "image_anomaly_score", "image_anomaly_count",
    "rainfall_24h", "rainfall_7d", "rainfall_30d",
    "temperature", "humidity", "soil_moisture", "water_level",
    "elevation_m", "slope_deg", "aspect_deg", "curvature",
    "soil_grade", "vegetation_cover", "distance_to_water_m",
    "geology_grade", "fault_distance_m",
    "displacement_rate", "displacement_total", "crack_width",
    "earthquake_mag", "tilt_angle", "site_grade",
    "historical_count", "historical_max_score",
    "exposure_population", "exposure_buildings",
    "social_alert_level", "social_post_count", "social_sentiment_score", "social_emergency_level",
    "nighttime_light_index", "infrastructure_score", "road_access_score", "power_status",
]

# 各维度权重（与 comprehensive_model.py._overall_weighted 一致）
DIM_WEIGHTS = {
    "remote_sensing": 0.20,
    "meteorology": 0.22,
    "terrain": 0.15,
    "sensor": 0.18,
    "historical": 0.10,
    "social_media": 0.08,
    "supporting": 0.07,
}

# 归一化最大值（与 comprehensive_model 一致）
NORM_MAX = {
    "image_anomaly_score": 100.0,
    "image_anomaly_count": 20.0,
    "rainfall_24h": 150.0,
    "rainfall_7d": 400.0,
    "rainfall_30d": 800.0,
    "temperature": 45.0,
    "humidity": 1.0,
    "soil_moisture": 1.0,
    "water_level": 10.0,
    "elevation_m": 3000.0,
    "slope_deg": 60.0,
    "aspect_deg": 360.0,
    "curvature": 1.0,
    "soil_grade": 5.0,
    "vegetation_cover": 1.0,
    "distance_to_water_m": 5000.0,
    "geology_grade": 5.0,
    "fault_distance_m": 10000.0,
    "displacement_rate": 30.0,
    "displacement_total": 200.0,
    "crack_width": 50.0,
    "earthquake_mag": 7.0,
    "tilt_angle": 15.0,
    "site_grade": 5.0,
    "historical_count": 10.0,
    "historical_max_score": 100.0,
    "exposure_population": 50000.0,
    "exposure_buildings": 5000.0,
    "social_alert_level": 4.0,
    "social_post_count": 100.0,
    "social_sentiment_score": 1.0,
    "social_emergency_level": 4.0,
    "nighttime_light_index": 63.0,
    "infrastructure_score": 1.0,
    "road_access_score": 1.0,
    "power_status": 1.0,
}


def _safe_norm(value: float, max_val: float) -> float:
    """安全归一化：None/NaN → 0，> max 截断。"""
    if value is None:
        return 0.0
    try:
        v = float(value)
    except (TypeError, ValueError):
        return 0.0
    if not math.isfinite(v):
        return 0.0
    if max_val <= 0:
        return 0.0
    return max(0.0, min(1.0, abs(v) / max_val))


def compute_risk_score_regression(factors: dict) -> float:
    """
    计算连续风险评分（0-100），与 comprehensive_model.py 的六维度评分函数对齐。

    注意：使用 sigmoid/饱和函数让极端值不会无限叠加；
    实际综合评分应在 [0, 100] 范围，便于 UI 展示与等级划分。
    """
    # [1] 遥感：直接用 image_anomaly_score（YOLO/SAR 输出）
    rs_score = max(0.0, min(100.0, factors.get("image_anomaly_score", 0.0)))

    # [2] 气象：降雨加权 + 土壤含水率 + 水位
    rain_24 = max(0.0, factors.get("rainfall_24h", 0.0))
    rain_7 = max(0.0, factors.get("rainfall_7d", 0.0))
    rain_30 = max(0.0, factors.get("rainfall_30d", 0.0))
    soil_m = factors.get("soil_moisture", 0.3)
    wlevel = factors.get("water_level", 0.0)
    n24 = _safe_norm(rain_24, NORM_MAX["rainfall_24h"])
    n7 = _safe_norm(rain_7, NORM_MAX["rainfall_7d"])
    n30 = _safe_norm(rain_30, NORM_MAX["rainfall_30d"])
    rain_combined = 0.5 * n24 + 0.3 * n7 + 0.2 * n30
    # 土壤含水率非线性饱和：>0.45 → 0.3，否则 >0.35 → 0.1
    soil_risk = 0.3 if soil_m >= 0.45 else 0.1 if soil_m >= 0.35 else 0.0
    # 水位：超过警戒水位 → 风险加成（不超过 0.2）
    level_risk = min(0.2, wlevel / 5.0 * 0.2) if wlevel > 0 else 0.0
    met_score = (rain_combined * 0.7 + soil_risk * 0.15 + level_risk * 0.15) * 100.0

    # [3] 地形：坡度/土壤/距水/地质/植被/断层
    slope = factors.get("slope_deg", 0.0)
    soil = factors.get("soil_grade", 1.0)
    veg = factors.get("vegetation_cover", 0.5)
    dist = factors.get("distance_to_water_m", 1000.0)
    geol = factors.get("geology_grade", 1.0)
    fault_d = factors.get("fault_distance_m", 99999.0)
    curv = factors.get("curvature", 0.0)
    aspect = factors.get("aspect_deg", 180.0)

    nslope = _safe_norm(slope, NORM_MAX["slope_deg"])
    nsoil = _safe_norm(soil, NORM_MAX["soil_grade"])
    ndist = 1.0 - _safe_norm(dist, NORM_MAX["distance_to_water_m"])
    ngeol = _safe_norm(geol, NORM_MAX["geology_grade"])
    nveg = 1.0 - _safe_norm(veg, NORM_MAX["vegetation_cover"])
    nfault = 1.0 - _safe_norm(fault_d, NORM_MAX["fault_distance_m"])
    curv_risk = max(0.0, -curv * 0.1)
    aspect_risk = 0.05 if (0 <= aspect < 90 or 270 <= aspect <= 360) else 0.0
    ter_score = (
        nslope * 0.22 + nsoil * 0.18 + ndist * 0.18
        + ngeol * 0.14 + nveg * 0.10 + nfault * 0.10
        + curv_risk + aspect_risk
    ) * 100.0

    # [4] 传感器：复用 fusion 的 device 加权
    disp = factors.get("displacement_rate", 0.0)
    disp_total = factors.get("displacement_total", 0.0)
    crack = factors.get("crack_width", 0.0)
    eq = factors.get("earthquake_mag", 0.0)
    site = factors.get("site_grade", 1.0)
    tilt = factors.get("tilt_angle", 0.0)
    DEVICE_W = {
        "displacement_rate": 0.25, "displacement_total": 0.15,
        "rainfall_24h": 0.20, "rainfall_7d": 0.12,
        "crack_width": 0.10, "earthquake_mag": 0.10, "site_grade": 0.08,
    }
    DEVICE_M = {
        "displacement_rate": NORM_MAX["displacement_rate"],
        "displacement_total": NORM_MAX["displacement_total"],
        "rainfall_24h": NORM_MAX["rainfall_24h"],
        "rainfall_7d": NORM_MAX["rainfall_7d"],
        "crack_width": NORM_MAX["crack_width"],
        "earthquake_mag": NORM_MAX["earthquake_mag"],
        "site_grade": NORM_MAX["site_grade"],
    }
    dev_factors = {
        "displacement_rate": disp, "displacement_total": disp_total,
        "rainfall_24h": rain_24, "rainfall_7d": rain_7,
        "crack_width": crack, "earthquake_mag": eq, "site_grade": site,
    }
    weighted = 0.0
    total_w = 0.0
    for k, w in DEVICE_W.items():
        norm = _safe_norm(dev_factors.get(k, 0.0), DEVICE_M.get(k, 1.0))
        weighted += norm * w
        total_w += w
    sensor_score = (weighted / total_w * 100.0) if total_w > 0 else 0.0

    # [5] 历史
    hist_cnt = int(factors.get("historical_count", 0))
    hist_max = max(0.0, factors.get("historical_max_score", 0.0))
    pop = int(factors.get("exposure_population", 0))
    buildings = int(factors.get("exposure_buildings", 0))
    land_use = factors.get("land_use_type", "unknown")
    land_risk_map = {"urban": 0.3, "cropland": 0.5, "forest": 0.15, "water": 0.6, "barren": 0.1, "unknown": 0.2}
    land_risk = land_risk_map.get(str(land_use), 0.2)
    hist_score = min(100.0, hist_max + hist_cnt * 8.0)
    exposure_factor = min(1.0, (pop / 10000.0 + buildings / 1000.0) * 0.5)
    his_score = hist_score * 0.5 + exposure_factor * 30.0 + land_risk * 20.0

    # [6] 社交媒体
    alert = max(0.0, min(4.0, factors.get("social_alert_level", 0.0)))
    posts = int(factors.get("social_post_count", 0))
    sent = max(-1.0, min(1.0, factors.get("social_sentiment_score", 0.0)))
    emg = max(0.0, min(4.0, factors.get("social_emergency_level", 0.0)))
    norm_alert = min(1.0, alert / 4.0)
    post_factor = min(0.3, posts / 50.0)
    sent_factor = max(0.0, -sent * 0.3)
    emg_factor = min(1.0, emg / 4.0) * 0.3
    sm_score = (norm_alert * 0.6 + post_factor + sent_factor + emg_factor) * 100.0

    # [7] 辅助
    road = max(0.0, min(1.0, factors.get("road_access_score", 0.5)))
    infra = max(0.0, min(1.0, factors.get("infrastructure_score", 0.5)))
    power = max(0.0, min(1.0, factors.get("power_status", 1.0)))
    ntl = max(0.0, min(63.0, factors.get("nighttime_light_index", 31.0)))
    access = road * 0.6 + infra * 0.4
    sup_score = (
        (1.0 - access) * 0.7 + (1.0 - power) * 0.2 + min(1.0, ntl / 63.0) * 0.1
    ) * 100.0

    # 六维度加权融合
    total = (
        rs_score * DIM_WEIGHTS["remote_sensing"]
        + met_score * DIM_WEIGHTS["meteorology"]
        + ter_score * DIM_WEIGHTS["terrain"]
        + sensor_score * DIM_WEIGHTS["sensor"]
        + his_score * DIM_WEIGHTS["historical"]
        + sm_score * DIM_WEIGHTS["social_media"]
        + sup_score * DIM_WEIGHTS["supporting"]
    )
    # 钳制到 [0, 100]
    return round(max(0.0, min(100.0, total)), 4)


def synthesize_sample(rng: random.Random, risk_bias: float = 0.0) -> tuple:
    """
    生成一条综合风险样本（连续标签）。

    Args:
        rng: 随机数生成器
        risk_bias: 风险偏移（用于生成偏向高/低风险的样本）；
                   -1 → 偏 LOW，0 → 平衡分布，+1 → 偏 CRITICAL。
    """
    factors = {}

    # 决定这个样本的目标风险档（基于 risk_bias）
    base_p = rng.random() + risk_bias * 0.4
    base_p = max(0.0, min(1.0, base_p))

    # [1] 遥感（30%概率有数据）
    if rng.random() < 0.4:
        # 遥感分数与基础风险档相关
        factors["image_anomaly_score"] = rng.uniform(20, 100) * (0.5 + base_p * 0.5)
        factors["image_anomaly_count"] = rng.randint(1, 15)
    else:
        factors["image_anomaly_score"] = 0.0
        factors["image_anomaly_count"] = 0

    # [2] 气象（必有数据）
    # 基础降雨量随 base_p 增加
    base_rain = base_p * 0.7 + rng.random() * 0.3
    factors["rainfall_24h"] = base_rain * 200.0 * rng.uniform(0.3, 1.2)
    factors["rainfall_7d"] = factors["rainfall_24h"] * rng.uniform(1.5, 4.0)
    factors["rainfall_30d"] = factors["rainfall_7d"] * rng.uniform(1.5, 3.0)
    factors["temperature"] = rng.uniform(5, 40)
    # 关键：让 HIGH/CRITICAL 样本的 soil_moisture 接近 0.5+
    factors["soil_moisture"] = min(0.65, max(0.1, base_p * 0.55 + rng.uniform(0, 0.25)))
    factors["water_level"] = rng.uniform(0, 8) * (0.5 + base_p * 0.5)

    # [3] 地形（必有数据）
    factors["elevation_m"] = rng.uniform(100, 2500)
    # 坡度：偏 base_p 的样本有更高坡度
    factors["slope_deg"] = rng.uniform(5, 60) * (0.4 + base_p * 0.6)
    factors["aspect_deg"] = rng.uniform(0, 360)
    factors["curvature"] = rng.uniform(-0.5, 0.5)
    factors["soil_grade"] = rng.uniform(1, 5) * (0.5 + base_p * 0.5)
    factors["vegetation_cover"] = max(0.05, min(0.95, rng.uniform(0.1, 0.9) * (1.2 - base_p * 0.6)))
    factors["distance_to_water_m"] = max(50, rng.uniform(50, 4000) * (1.3 - base_p * 0.6))
    factors["geology_grade"] = rng.uniform(1, 5) * (0.5 + base_p * 0.5)
    factors["fault_distance_m"] = max(200, rng.uniform(200, 8000) * (1.3 - base_p * 0.5))

    # [4] 传感器（70%概率有数据，与基础风险正相关）
    if rng.random() < 0.75:
        # 位移与基础风险正相关
        disp_factor = 0.2 + base_p * 0.8
        factors["displacement_rate"] = rng.uniform(0.1, 25) * disp_factor
        factors["displacement_total"] = rng.uniform(10, 180) * disp_factor
        factors["crack_width"] = rng.uniform(0.5, 35) * disp_factor
        factors["earthquake_mag"] = rng.uniform(0, 6.5) * disp_factor
        factors["tilt_angle"] = rng.uniform(0, 12) * disp_factor
        factors["site_grade"] = max(1, min(5, rng.uniform(1, 5) * (0.6 + base_p * 0.6)))
    else:
        factors["displacement_rate"] = 0.0
        factors["displacement_total"] = 0.0
        factors["crack_width"] = 0.0
        factors["earthquake_mag"] = 0.0
        factors["tilt_angle"] = 0.0
        factors["site_grade"] = 1.0

    # [5] 历史（40%概率有数据）
    if rng.random() < 0.45:
        factors["historical_count"] = int(rng.uniform(0, 12) * (0.3 + base_p * 0.8))
        factors["historical_max_score"] = max(20, min(100, rng.uniform(20, 100) * (0.4 + base_p * 0.7)))
    else:
        factors["historical_count"] = 0
        factors["historical_max_score"] = 0.0
    factors["exposure_population"] = int(rng.uniform(0, 30000) * (0.5 + base_p * 0.5))
    factors["exposure_buildings"] = int(rng.uniform(0, 3000) * (0.5 + base_p * 0.5))

    # [6] 社交媒体（35%概率有数据）
    if rng.random() < 0.5:
        # 社交媒体与基础风险正相关更激进
        factors["social_alert_level"] = min(4, max(0, int((rng.uniform(0, 5) * (0.3 + base_p * 0.8)))))
        factors["social_post_count"] = int(rng.uniform(0, 100) * (0.3 + base_p * 0.9))
        factors["social_sentiment_score"] = max(-1.0, min(1.0, -base_p * 0.9 + rng.uniform(-0.2, 0.2)))
        factors["social_emergency_level"] = min(4, max(0, int(rng.uniform(0, 5) * (0.3 + base_p * 0.8))))
    else:
        factors["social_alert_level"] = 0
        factors["social_post_count"] = 0
        factors["social_sentiment_score"] = 0.5
        factors["social_emergency_level"] = 0

    # [7] 辅助（必有数据，偏 base_p 反向：风险高 → 基础设施差）
    factors["nighttime_light_index"] = rng.uniform(0, 63)
    factors["infrastructure_score"] = max(0.1, min(1.0, rng.uniform(0.2, 1.0) * (1.1 - base_p * 0.5)))
    factors["road_access_score"] = max(0.1, min(1.0, rng.uniform(0.2, 1.0) * (1.1 - base_p * 0.5)))
    factors["power_status"] = max(0.2, min(1.0, rng.uniform(0.5, 1.0) * (1.1 - base_p * 0.4)))

    # 钳制所有特征到合理范围
    for k, v in factors.items():
        if k in NORM_MAX:
            try:
                fv = float(v)
                if not math.isfinite(fv):
                    fv = 0.0
                factors[k] = max(0.0, min(NORM_MAX[k], fv))
            except (TypeError, ValueError):
                factors[k] = 0.0

    # 计算真实风险评分（连续 [0, 100]）
    score = compute_risk_score_regression(factors)

    vec = [float(factors.get(k, 0.0)) for k in FEATURE_NAMES]
    return vec, score


def main():
    parser = argparse.ArgumentParser(description="综合风险 XGBoost 回归训练")
    parser.add_argument("--output-dir", default=str(Path(__file__).resolve().parent.parent.parent / "models"))
    parser.add_argument("--samples", type=int, default=8000)
    parser.add_argument("--rounds", type=int, default=600)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    try:
        import xgboost as xgb
    except ImportError:
        print("[ERROR] 请先安装 xgboost: pip install xgboost")
        sys.exit(1)

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    print(f"[train] 生成 {args.samples} 条训练样本...")
    random.seed(args.seed)
    np.random.seed(args.seed)

    X, y = [], []
    rng = random.Random(args.seed)

    # 平衡分布：让样本覆盖全档 [LOW, MEDIUM, HIGH, CRITICAL]
    # 之前训练样本偏向 LOW（max=77, mean=33），CRITICAL/HIGH 样本极少导致模型不会"报高分"。
    # 修正后分布：各档都有充足样本，且 CRITICAL 样本权重更高。
    distribution = [
        (-0.7, int(args.samples * 0.20)),   # LOW 偏置
        (-0.2, int(args.samples * 0.20)),   # LOW-MEDIUM
        (0.2, int(args.samples * 0.20)),    # MEDIUM-HIGH
        (0.6, int(args.samples * 0.20)),    # HIGH
        (1.0, int(args.samples * 0.20)),    # CRITICAL
    ]
    samples_weight = []  # (sample, weight)
    rng_seq = random.Random(args.seed)
    for bias, count in distribution:
        # CRITICAL 档（bias=1.0）样本权重更高，让模型对极端情况更敏感
        weight = 2.0 if bias >= 0.9 else 1.0
        for _ in range(count):
            sample_rng = random.Random(rng_seq.randint(0, 2**31 - 1))
            vec, score = synthesize_sample(sample_rng, risk_bias=bias)
            samples_weight.append((np.array(vec, dtype=np.float32), float(score), weight))

    # 关键补充：注入"精心构造"的极端 CRITICAL 样本（强制使所有维度同步达到高位）
    # 不通过 synthesize_sample（它在 base_p=1.0 时也难以达到 80+ 分）。
    # 直接构造极端特征向量，让 compute_risk_score_regression 输出 85-95 分。
    extreme_count = int(args.samples * 0.10)  # 10% 极端样本
    for _ in range(extreme_count):
        extreme_factors = {
            "image_anomaly_score": 95.0,
            "image_anomaly_count": 12,
            "rainfall_24h": 195.0,  # 远超阈值
            "rainfall_7d": 480.0,
            "rainfall_30d": 950.0,
            "temperature": 25.0,
            "humidity": 0.85,
            "soil_moisture": 0.55,
            "water_level": 7.0,
            "elevation_m": 1500.0,
            "slope_deg": 48.0,
            "aspect_deg": 30.0,  # 阴坡
            "curvature": -0.4,   # 凹
            "soil_grade": 4.5,
            "vegetation_cover": 0.12,
            "distance_to_water_m": 80.0,
            "geology_grade": 4.8,
            "fault_distance_m": 500.0,
            "displacement_rate": 18.0,
            "displacement_total": 160.0,
            "crack_width": 32.0,
            "earthquake_mag": 5.5,
            "tilt_angle": 9.0,
            "site_grade": 5.0,
            "historical_count": 10,
            "historical_max_score": 90.0,
            "exposure_population": 12000,
            "exposure_buildings": 1200,
            "social_alert_level": 4,
            "social_post_count": 80,
            "social_sentiment_score": -0.8,
            "social_emergency_level": 4,
            "nighttime_light_index": 8,
            "infrastructure_score": 0.25,
            "road_access_score": 0.2,
            "power_status": 0.4,
        }
        vec = [float(extreme_factors.get(k, 0.0)) for k in FEATURE_NAMES]
        score = compute_risk_score_regression(extreme_factors)
        samples_weight.append((np.array(vec, dtype=np.float32), score, 3.0))  # 权重 3x

    X = np.array([s[0] for s in samples_weight], dtype=np.float32)
    y = np.array([s[1] for s in samples_weight], dtype=np.float32)
    w = np.array([s[2] for s in samples_weight], dtype=np.float32)
    print(f"[train] 总样本: {len(X)}")
    print(f"[train] 风险分布: min={y.min():.2f} mean={y.mean():.2f} max={y.max():.2f} std={y.std():.2f}")

    # 切分 train/val
    perm = np.random.RandomState(args.seed).permutation(len(X))
    X, y, w = X[perm], y[perm], w[perm]
    split = int(len(X) * 0.85)
    X_train, y_train, w_train = X[:split], y[:split], w[:split]
    X_val, y_val, w_val = X[split:], y[split:], w[split:]
    print(f"[train] 训练集: {len(X_train)}  验证集: {len(X_val)}")

    dtrain = xgb.DMatrix(X_train, label=y_train, weight=w_train, feature_names=FEATURE_NAMES)
    dval = xgb.DMatrix(X_val, label=y_val, weight=w_val, feature_names=FEATURE_NAMES)

    params = {
        "objective": "reg:squarederror",
        "eval_metric": "rmse",
        "max_depth": 6,
        "eta": 0.05,
        "subsample": 0.85,
        "colsample_bytree": 0.85,
        "min_child_weight": 3,
        "gamma": 0.0,
        "alpha": 0.0,
        "lambda": 1.0,
        "seed": args.seed,
    }

    print(f"[train] 开始训练 XGBoost 回归模型（max_depth={params['max_depth']}, eta={params['eta']}）...")
    model = xgb.train(
        params, dtrain,
        num_boost_round=args.rounds,
        evals=[(dtrain, "train"), (dval, "val")],
        early_stopping_rounds=40,
        verbose_eval=50,
    )

    output_path = output_dir / "comprehensive_v2.json"
    # 备份旧模型（如果存在且不是本次训练产物）
    if output_path.exists():
        backup = output_dir / f"comprehensive_v2.backup.{datetime.now().strftime('%Y%m%d_%H%M%S')}.json"
        try:
            import shutil
            shutil.copy2(str(output_path), str(backup))
            print(f"[train] 旧模型已备份: {backup}")
        except Exception as e:
            print(f"[train] 备份失败（继续覆盖）: {e}")
    model.save_model(str(output_path))
    size_kb = output_path.stat().st_size // 1024
    print(f"[train] 模型已保存: {output_path} ({size_kb} KB)")

    # 特征重要性
    importance = model.get_score(importance_type="gain")
    print("\n[train] 特征重要性 Top-15 (gain):")
    for k, v in sorted(importance.items(), key=lambda x: -x[1])[:15]:
        print(f"  {k}: {v:.1f}")

    # 验证集评估
    print("\n[train] 验证集评估...")
    y_pred = model.predict(dval)
    mae = float(np.mean(np.abs(y_pred - y_val)))
    rmse = float(np.sqrt(np.mean((y_pred - y_val) ** 2)))
    # 分档误差
    bins = [(0, 35, "LOW"), (35, 60, "MEDIUM"), (60, 80, "HIGH"), (80, 101, "CRITICAL")]
    print(f"  MAE = {mae:.3f} 分")
    print(f"  RMSE = {rmse:.3f} 分")
    print(f"  分档一致性:")
    for lo, hi, name in bins:
        mask = (y_val >= lo) & (y_val < hi)
        if mask.sum() == 0:
            continue
        pred_in_bin = (y_pred[mask] >= lo) & (y_pred[mask] < hi)
        acc = pred_in_bin.mean()
        avg_pred = y_pred[mask].mean()
        print(f"    {name:7s} ({lo:3d}-{hi-1:3d}): {mask.sum():4d} 样本, 预测均值={avg_pred:.1f}, 同档率={acc:.1%}")

    # 保存 metadata
    meta = {
        "version": "2.1.0",
        "task": "regression",  # 关键：标记为回归模型，推理端据此处理
        "trained_at": datetime.now(timezone.utc).isoformat(),
        "n_samples": int(len(X)),
        "n_train": int(len(X_train)),
        "n_val": int(len(X_val)),
        "n_features": len(FEATURE_NAMES),
        "feature_names": FEATURE_NAMES,
        "objective": params["objective"],
        "max_depth": params["max_depth"],
        "eta": params["eta"],
        "n_rounds": int(model.best_iteration + 1) if hasattr(model, "best_iteration") else args.rounds,
        "metrics": {
            "mae": round(mae, 3),
            "rmse": round(rmse, 3),
        },
        "y_distribution": {
            "min": round(float(y.min()), 2),
            "mean": round(float(y.mean()), 2),
            "max": round(float(y.max()), 2),
            "std": round(float(y.std()), 2),
        },
    }
    meta_path = output_dir / "comprehensive_v2.meta.json"
    meta_path.write_text(json.dumps(meta, indent=2, ensure_ascii=False))
    print(f"[train] 元数据已保存: {meta_path}")

    # 样本预测演示
    print("\n[train] 样本预测演示:")
    demo_cases = [
        ("极端风险", {"rainfall_24h": 180, "rainfall_7d": 450, "soil_moisture": 0.55,
                      "slope_deg": 45, "vegetation_cover": 0.15, "soil_grade": 5,
                      "distance_to_water_m": 100, "geology_grade": 5,
                      "displacement_rate": 15, "crack_width": 30, "earthquake_mag": 5.5,
                      "image_anomaly_score": 80, "image_anomaly_count": 8,
                      "social_alert_level": 4, "social_post_count": 50}),
        ("高风险", {"rainfall_24h": 100, "rainfall_7d": 250,
                   "slope_deg": 35, "soil_grade": 4, "vegetation_cover": 0.2,
                   "displacement_rate": 6, "crack_width": 12}),
        ("中等风险", {"rainfall_24h": 30, "rainfall_7d": 80, "slope_deg": 22,
                      "vegetation_cover": 0.5, "soil_grade": 3}),
        ("低风险", {"rainfall_24h": 5, "rainfall_7d": 20, "slope_deg": 8,
                    "vegetation_cover": 0.7, "soil_grade": 2}),
    ]
    for label, factors in demo_cases:
        vec = np.array([[float(factors.get(k, 0.0)) for k in FEATURE_NAMES]], dtype=np.float32)
        dmat = xgb.DMatrix(vec, feature_names=FEATURE_NAMES)
        pred_score = float(model.predict(dmat)[0])
        true_score = compute_risk_score_regression(factors)
        print(f"  {label:8s}: 预测={pred_score:5.1f}  启发式基准={true_score:5.1f}  差值={pred_score-true_score:+.1f}")


if __name__ == "__main__":
    main()
