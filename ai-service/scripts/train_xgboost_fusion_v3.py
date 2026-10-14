#!/usr/bin/env python3
"""XGBoost 融合预警模型 v3：回归式输出。

改进点：
 1. 改为回归（reg:squarederror），输出连续 risk_score [0, 100]
 2. 训练样本生成与 comprehensive_v2 中的 device 维度完全一致
 3. 模型输出即为最终评分，不需要 * 100 的转换
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

FEATURE_COLS = [
    "displacement_rate", "displacement_total", "rainfall_24h", "rainfall_7d",
    "crack_width", "earthquake_mag", "site_grade",
]

NORM_MAX = {
    "displacement_rate": 30.0,
    "displacement_total": 200.0,
    "rainfall_24h": 150.0,
    "rainfall_7d": 400.0,
    "crack_width": 50.0,
    "earthquake_mag": 7.0,
    "site_grade": 5.0,
}

DEVICE_W = {
    "displacement_rate": 0.25,
    "displacement_total": 0.15,
    "rainfall_24h": 0.20,
    "rainfall_7d": 0.12,
    "crack_width": 0.10,
    "earthquake_mag": 0.10,
    "site_grade": 0.08,
}


def compute_risk_score(factors: dict) -> float:
    """device 维度加权评分 [0, 100]（与 comprehensive_v2 中的 sensor 维度一致）。"""
    weighted = 0.0
    total_w = 0.0
    for k, w in DEVICE_W.items():
        v = max(0.0, float(factors.get(k, 0.0)))
        norm = min(1.0, v / NORM_MAX[k])
        weighted += norm * w
        total_w += w
    return (weighted / total_w) * 100.0 if total_w > 0 else 0.0


def synthesize_samples(n_total: int = 8000, seed: int = 42):
    """多样化样本：覆盖 LOW/MEDIUM/HIGH/CRITICAL 四档位。"""
    rng = random.Random(seed)
    samples = []
    scores = []

    distribution = [
        (-0.7, int(n_total * 0.20)),  # LOW 偏置
        (-0.2, int(n_total * 0.20)),  # LOW-MEDIUM
        (0.2,  int(n_total * 0.20)),  # MEDIUM-HIGH
        (0.6,  int(n_total * 0.25)),  # HIGH
        (1.0,  int(n_total * 0.15)),  # CRITICAL
    ]
    for bias, count in distribution:
        weight = 2.0 if bias >= 0.9 else 1.0
        for _ in range(count):
            sample_rng = random.Random(rng.randint(0, 2**31 - 1))
            base_p = sample_rng.random()
            base_p = max(0.0, min(1.0, base_p + bias * 0.5))

            factors = {
                "displacement_rate": max(0.0, sample_rng.uniform(0, NORM_MAX["displacement_rate"]) * (0.3 + base_p * 0.9)),
                "displacement_total": max(0.0, sample_rng.uniform(0, NORM_MAX["displacement_total"]) * (0.3 + base_p * 0.7)),
                "rainfall_24h": max(0.0, sample_rng.uniform(0, NORM_MAX["rainfall_24h"]) * (0.2 + base_p * 1.2)),
                "rainfall_7d": max(0.0, sample_rng.uniform(0, NORM_MAX["rainfall_7d"]) * (0.2 + base_p * 1.0)),
                "crack_width": max(0.0, sample_rng.uniform(0, NORM_MAX["crack_width"]) * (0.2 + base_p * 1.3)),
                "earthquake_mag": max(0.0, sample_rng.uniform(0, NORM_MAX["earthquake_mag"]) * (0.1 + base_p * 0.9)),
                "site_grade": max(1.0, min(5.0, sample_rng.uniform(1, 5) * (0.4 + base_p * 0.8))),
            }
            score = compute_risk_score(factors)
            samples.append([factors[k] for k in FEATURE_COLS])
            scores.append(score)

    X = np.array(samples, dtype=np.float32)
    y = np.array(scores, dtype=np.float32)
    print(f"[train] 合成 {len(X)} 个样本")
    print(f"[train] risk_score 分布: min={y.min():.1f}, mean={y.mean():.1f}, max={y.max():.1f}, std={y.std():.1f}")
    return X, y


def train(X: np.ndarray, y: np.ndarray, output_dir: Path):
    try:
        import xgboost as xgb
    except ImportError:
        print("[ERROR] 请先安装 xgboost")
        sys.exit(1)

    # 切分
    n = len(X)
    perm = np.random.RandomState(42).permutation(n)
    X, y = X[perm], y[perm]
    split = int(n * 0.85)
    X_train, y_train = X[:split], y[:split]
    X_val, y_val = X[split:], y[split:]

    print(f"[train] 训练集: {len(X_train)}  验证集: {len(X_val)}")

    dtrain = xgb.DMatrix(X_train, label=y_train, feature_names=FEATURE_COLS)
    dval = xgb.DMatrix(X_val, label=y_val, feature_names=FEATURE_COLS)

    params = {
        "objective": "reg:squarederror",
        "eval_metric": "rmse",
        "max_depth": 6,
        "eta": 0.05,
        "subsample": 0.85,
        "colsample_bytree": 0.85,
        "min_child_weight": 3,
        "seed": 42,
    }
    model = xgb.train(
        params,
        dtrain,
        num_boost_round=600,
        evals=[(dtrain, "train"), (dval, "val")],
        early_stopping_rounds=40,
        verbose_eval=50,
    )

    # 备份旧模型
    output_path = output_dir / "xgboost_fusion.json"
    if output_path.exists():
        backup = output_dir / f"xgboost_fusion.backup.{datetime.now().strftime('%Y%m%d_%H%M%S')}.json"
        try:
            import shutil
            shutil.copy2(str(output_path), str(backup))
            print(f"[train] 旧模型已备份: {backup}")
        except Exception as e:
            print(f"[train] 备份失败（继续覆盖）: {e}")

    model.save_model(str(output_path))
    size_kb = output_path.stat().st_size // 1024
    print(f"[train] 模型已保存: {output_path} ({size_kb} KB)")

    # 评估
    print("\n[train] 验证集评估:")
    y_pred = model.predict(dval)
    mae = float(np.mean(np.abs(y_pred - y_val)))
    rmse = float(np.sqrt(np.mean((y_pred - y_val) ** 2)))
    print(f"  MAE = {mae:.3f} 分")
    print(f"  RMSE = {rmse:.3f} 分")
    bins = [(0, 35, "LOW"), (35, 60, "MEDIUM"), (60, 80, "HIGH"), (80, 101, "CRITICAL")]
    print(f"  分档一致性:")
    for lo, hi, name in bins:
        mask = (y_val >= lo) & (y_val < hi)
        if mask.sum() == 0:
            continue
        pred_in_bin = (y_pred[mask] >= lo) & (y_pred[mask] < hi)
        acc = pred_in_bin.mean()
        avg_pred = y_pred[mask].mean()
        print(f"    {name:7s} ({lo:3d}-{hi-1:3d}): {mask.sum():4d} 样本, 预测均值={avg_pred:.1f}, 同档率={acc:.1%}")

    # 元数据
    meta = {
        "version": "3.0.0",
        "objective": "reg:squarederror",
        "output_type": "regression",
        "trained_at": datetime.now(timezone.utc).isoformat(),
        "n_samples": int(n),
        "n_train": int(len(X_train)),
        "n_val": int(len(X_val)),
        "n_features": len(FEATURE_COLS),
        "feature_names": FEATURE_COLS,
        "norm_max": NORM_MAX,
        "device_weights": DEVICE_W,
        "best_iteration": int(model.best_iteration),
        "best_score": float(model.best_score),
    }
    meta_path = output_dir / "xgboost_fusion.meta.json"
    meta_path.write_text(json.dumps(meta, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"[train] 元数据已保存: {meta_path}")

    # 特征重要性
    print("\n[train] 特征重要性 (gain):")
    importance = model.get_score(importance_type="gain")
    for k, v in sorted(importance.items(), key=lambda x: -x[1]):
        print(f"  {k}: {v:.1f}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--n-samples", type=int, default=8000)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--output-dir", default=str(Path(__file__).resolve().parent.parent.parent / "models"))
    args = parser.parse_args()

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    X, y = synthesize_samples(n_total=args.n_samples, seed=args.seed)
    train(X, y, output_dir)


if __name__ == "__main__":
    main()