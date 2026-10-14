#!/usr/bin/env python3
"""
综合风险 XGBoost 模型训练脚本。

基于六维度综合风险评分体系（遥感/气象/地形/传感器/历史/社交媒体/辅助数据），
合成多样化训练样本，训练 XGBoost 二分类模型预测 CRITICAL/HIGH 预警。

特征列表（共 33 个，与 comprehensive_model.py 的 _run_xgboost 一致）：
  [1] 遥感: image_anomaly_score, image_anomaly_count
  [2] 气象: rainfall_24h, rainfall_7d, rainfall_30d, temperature, humidity, soil_moisture, water_level
  [3] 地形: elevation_m, slope_deg, aspect_deg, curvature, soil_grade, vegetation_cover,
          distance_to_water_m, geology_grade, fault_distance_m
  [4] 传感器: displacement_rate, displacement_total, crack_width, earthquake_mag, tilt_angle, site_grade
  [5] 历史: historical_count, historical_max_score, exposure_population, exposure_buildings
  [6] 社交媒体: social_alert_level, social_post_count, social_sentiment_score, social_emergency_level
  [7] 辅助: nighttime_light_index, infrastructure_score, road_access_score, power_status

标签: risk_score >= 60 → 1 (HIGH/CRITICAL 预警), 否则 0

用法:
    python train_comprehensive_xgboost.py --output-dir ../models
"""
from __future__ import annotations

import json
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

# 各维度权重（用于生成真实标签）
DIM_WEIGHTS = {
    "remote_sensing": 0.20,
    "meteorology": 0.22,
    "terrain": 0.15,
    "sensor": 0.18,
    "historical": 0.10,
    "social_media": 0.08,
    "supporting": 0.07,
}

# 各特征归一化最大值
NORM_MAX = {
    "image_anomaly_score": 100.0,
    "image_anomaly_count": 20,
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
    "historical_count": 10,
    "historical_max_score": 100.0,
    "exposure_population": 50000,
    "exposure_buildings": 5000,
    "social_alert_level": 4.0,
    "social_post_count": 100,
    "social_sentiment_score": 1.0,
    "social_emergency_level": 4.0,
    "nighttime_light_index": 63.0,
    "infrastructure_score": 1.0,
    "road_access_score": 1.0,
    "power_status": 1.0,
}


def compute_risk_score(factors: dict) -> float:
    """基于六维度加权的综合风险评分（与 comprehensive_model.py 一致）。"""
    # [1] 遥感
    raw_rs = factors.get("image_anomaly_score", 0.0)
    rs_score = max(0.0, min(100.0, raw_rs))

    # [2] 气象
    r24 = max(0.0, factors.get("rainfall_24h", 0.0))
    r7 = max(0.0, factors.get("rainfall_7d", 0.0))
    r30 = max(0.0, factors.get("rainfall_30d", 0.0))
    soil = factors.get("soil_moisture", 0.3)
    wlevel = factors.get("water_level", 0.0)
    n24 = min(1.0, r24 / 150.0)
    n7 = min(1.0, r7 / 400.0)
    n30 = min(1.0, r30 / 800.0)
    rain_combined = 0.5 * n24 + 0.3 * n7 + 0.2 * n30
    soil_risk = 0.3 if soil >= 0.45 else 0.1 if soil >= 0.35 else 0.0
    met_score = (rain_combined * 0.7 + soil_risk) * 100.0

    # [3] 地形
    slope = factors.get("slope_deg", 0.0)
    dist = factors.get("distance_to_water_m", 1000.0)
    veg = factors.get("vegetation_cover", 0.5)
    geol = factors.get("geology_grade", 1.0)
    nslope = min(1.0, slope / 60.0)
    ndist = max(0.0, 1.0 - min(1.0, dist / 5000.0))
    nveg = max(0.0, 1.0 - min(1.0, veg / 1.0))
    ngeol = min(1.0, geol / 5.0)
    ter_score = (nslope * 0.22 + ndist * 0.18 + ngeol * 0.14 + nveg * 0.10) * 100.0

    # [4] 传感器
    disp = factors.get("displacement_rate", 0.0)
    crack = factors.get("crack_width", 0.0)
    eq = factors.get("earthquake_mag", 0.0)
    site = factors.get("site_grade", 1.0)
    DEVICE_WEIGHTS = {
        "displacement_rate": 0.25, "displacement_total": 0.15,
        "rainfall_24h": 0.20, "rainfall_7d": 0.12,
        "crack_width": 0.10, "earthquake_mag": 0.10, "site_grade": 0.08,
    }
    DEVICE_MAX = {
        "displacement_rate": 30.0, "displacement_total": 200.0,
        "rainfall_24h": 150.0, "rainfall_7d": 400.0,
        "crack_width": 50.0, "earthquake_mag": 7.0, "site_grade": 5.0,
    }
    dev_factors = {
        "displacement_rate": disp, "displacement_total": factors.get("displacement_total", 0.0),
        "rainfall_24h": r24, "rainfall_7d": r7,
        "crack_width": crack, "earthquake_mag": eq, "site_grade": site,
    }
    total_w, weighted = 0.0, 0.0
    for k, w in DEVICE_WEIGHTS.items():
        raw = dev_factors.get(k, 0.0)
        norm = min(1.0, max(0.0, raw / DEVICE_MAX.get(k, 1.0)))
        weighted += norm * w
        total_w += w
    sensor_score = (weighted / total_w * 100.0) if total_w > 0 else 0.0

    # [5] 历史
    hist_cnt = int(factors.get("historical_count", 0))
    hist_max = max(0.0, factors.get("historical_max_score", 0.0))
    pop = int(factors.get("exposure_population", 0))
    his_score = min(100.0, hist_max + hist_cnt * 8.0)

    # [6] 社交媒体
    alert = max(0.0, min(4.0, factors.get("social_alert_level", 0.0) - 1.0))
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
    access = road * 0.6 + infra * 0.4
    sup_score = ((1.0 - access) * 0.7 + (1.0 - power) * 0.2) * 100.0

    # 加权融合
    total = (
        rs_score * DIM_WEIGHTS["remote_sensing"]
        + met_score * DIM_WEIGHTS["meteorology"]
        + ter_score * DIM_WEIGHTS["terrain"]
        + sensor_score * DIM_WEIGHTS["sensor"]
        + his_score * DIM_WEIGHTS["historical"]
        + sm_score * DIM_WEIGHTS["social_media"]
        + sup_score * DIM_WEIGHTS["supporting"]
    )
    return min(100.0, max(0.0, total))


def synthesize_sample(rng: random.Random) -> tuple:
    """生成一条综合风险样本。"""
    factors = {}

    # [1] 遥感（30%概率有数据）
    if rng.random() < 0.3:
        factors["image_anomaly_score"] = rng.uniform(30, 100)
        factors["image_anomaly_count"] = rng.randint(1, 15)
    else:
        factors["image_anomaly_score"] = 0.0
        factors["image_anomaly_count"] = 0

    # [2] 气象（必有数据）
    factors["rainfall_24h"] = rng.uniform(0, 200)
    factors["rainfall_7d"] = rng.uniform(0, 500)
    factors["rainfall_30d"] = rng.uniform(0, 800)
    factors["temperature"] = rng.uniform(5, 40)
    factors["humidity"] = rng.uniform(0.3, 0.95)
    factors["soil_moisture"] = rng.uniform(0.1, 0.6)
    factors["water_level"] = rng.uniform(0, 8)

    # [3] 地形（必有数据）
    factors["elevation_m"] = rng.uniform(100, 2500)
    factors["slope_deg"] = rng.uniform(5, 55)
    factors["aspect_deg"] = rng.uniform(0, 360)
    factors["curvature"] = rng.uniform(-0.5, 0.5)
    factors["soil_grade"] = rng.uniform(1, 5)
    factors["vegetation_cover"] = rng.uniform(0.1, 0.9)
    factors["distance_to_water_m"] = rng.uniform(50, 4000)
    factors["geology_grade"] = rng.uniform(1, 5)
    factors["fault_distance_m"] = rng.uniform(200, 8000)

    # [4] 传感器（70%概率有数据）
    if rng.random() < 0.7:
        factors["displacement_rate"] = rng.uniform(0.1, 15)
        factors["displacement_total"] = rng.uniform(10, 180)
        factors["crack_width"] = rng.uniform(0.5, 30)
        factors["earthquake_mag"] = rng.uniform(0, 5.5)
        factors["tilt_angle"] = rng.uniform(0, 8)
        factors["site_grade"] = rng.uniform(1, 5)
    else:
        factors["displacement_rate"] = 0.0
        factors["displacement_total"] = 0.0
        factors["crack_width"] = 0.0
        factors["earthquake_mag"] = 0.0
        factors["tilt_angle"] = 0.0
        factors["site_grade"] = 1.0

    # [5] 历史（40%概率有数据）
    if rng.random() < 0.4:
        factors["historical_count"] = rng.randint(0, 10)
        factors["historical_max_score"] = rng.uniform(20, 100)
    else:
        factors["historical_count"] = 0
        factors["historical_max_score"] = 0.0
    factors["exposure_population"] = rng.randint(0, 30000)
    factors["exposure_buildings"] = rng.randint(0, 3000)

    # [6] 社交媒体（35%概率有数据）
    if rng.random() < 0.35:
        factors["social_alert_level"] = rng.randint(1, 5)
        factors["social_post_count"] = rng.randint(0, 80)
        factors["social_sentiment_score"] = rng.uniform(-1, 1)
        factors["social_emergency_level"] = rng.randint(0, 4)
    else:
        factors["social_alert_level"] = 0
        factors["social_post_count"] = 0
        factors["social_sentiment_score"] = 0.5
        factors["social_emergency_level"] = 0

    # [7] 辅助（必有数据）
    factors["nighttime_light_index"] = rng.uniform(0, 63)
    factors["infrastructure_score"] = rng.uniform(0.2, 1.0)
    factors["road_access_score"] = rng.uniform(0.2, 1.0)
    factors["power_status"] = rng.uniform(0.5, 1.0)

    score = compute_risk_score(factors)
    label = 1 if score >= 60 else 0  # HIGH/CRITICAL → 预警

    vec = [factors.get(k, 0.0) for k in FEATURE_NAMES]
    return vec, label, score


def main():
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", default=str(Path(__file__).resolve().parent.parent / "models"))
    parser.add_argument("--samples", type=int, default=5000)
    args = parser.parse_args()

    try:
        import xgboost as xgb
    except ImportError:
        print("[ERROR] 请先安装 xgboost: pip install xgboost")
        sys.exit(1)

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    print("[train] 生成训练样本...")
    random.seed(42)
    X, y, scores = [], [], []
    for i in range(args.samples):
        rng = random.Random(i)
        vec, label, score = synthesize_sample(rng)
        X.append(vec)
        y.append(label)
        scores.append(score)

    X = np.array(X, dtype=np.float32)
    y = np.array(y, dtype=np.int32)
    scores = np.array(scores, dtype=np.float32)

    print(f"[train] 总样本: {len(X)}, 正例(预警): {y.sum()} ({y.sum()/len(y)*100:.1f}%)")

    # 切分
    n = len(X)
    perm = np.random.RandomState(42).permutation(n)
    X, y, scores = X[perm], y[perm], scores[perm]
    split = int(n * 0.8)
    X_train, y_train = X[:split], y[:split]
    X_val, y_val = X[split:], y[split:]

    dtrain = xgb.DMatrix(X_train, label=y_train, feature_names=FEATURE_NAMES)
    dval = xgb.DMatrix(X_val, label=y_val, feature_names=FEATURE_NAMES)

    params = {
        "objective": "binary:logistic",
        "eval_metric": "auc",
        "max_depth": 5,
        "eta": 0.05,
        "subsample": 0.8,
        "colsample_bytree": 0.8,
        "scale_pos_weight": max(1, (y_train == 0).sum() / max(1, (y_train == 1).sum())),
        "seed": 42,
    }

    print("[train] 开始训练 XGBoost...")
    model = xgb.train(
        params, dtrain,
        num_boost_round=500,
        evals=[(dtrain, "train"), (dval, "val")],
        early_stopping_rounds=30,
        verbose_eval=50,
    )

    output_path = output_dir / "comprehensive_v2.json"
    model.save_model(str(output_path))
    print(f"[train] 模型已保存: {output_path} ({output_path.stat().st_size // 1024} KB)")

    # 特征重要性
    importance = model.get_score(importance_type="gain")
    print("\n[train] 特征重要性 (gain):")
    for k, v in sorted(importance.items(), key=lambda x: -x[1]):
        print(f"  {k}: {v:.1f}")

    # 保存 metadata
    meta = {
        "version": "2.0.0",
        "trained_at": datetime.now(timezone.utc).isoformat(),
        "n_samples": int(args.samples),
        "n_positive": int(y.sum()),
        "positive_rate": round(float(y.sum() / len(y)), 4),
        "n_features": len(FEATURE_NAMES),
        "feature_names": FEATURE_NAMES,
        "num_boost_round": model.best_iteration + 1 if hasattr(model, 'best_iteration') else params.get('num_boost_round', 500),
    }
    meta_path = output_dir / "comprehensive_v2.meta.json"
    meta_path.write_text(json.dumps(meta, indent=2, ensure_ascii=False))
    print(f"[train] 元数据已保存: {meta_path}")

    # 验证模型
    print("\n[train] 验证预测...")
    dtest = xgb.DMatrix(X_val[:10], feature_names=FEATURE_NAMES)
    preds = model.predict(dtest)
    for i in range(min(5, len(preds))):
        pred_label = "预警" if preds[i] >= 0.5 else "正常"
        true_label = "预警" if y_val[i] == 1 else "正常"
        print(f"  样本{i+1}: 预测={pred_label}({preds[i]:.3f}) 真实={true_label} 风险评分={scores[split:][i]:.1f}")


if __name__ == "__main__":
    main()
