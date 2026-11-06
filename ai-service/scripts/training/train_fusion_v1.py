#!/usr/bin/env python3
"""
XGBoost 融合预警模型训练脚本。

输入因子 CSV（每行一个样本，列包含各风险因子 + 目标 risk_level），训练二分类/多分类。

CSV 列示例:
    displacement_rate, displacement_total, rainfall_24h, rainfall_7d,
    crack_width, earthquake_mag, site_grade, risk_score
目标: risk_score >= 60 → 1 (预警), 否则 0

用法:
    python train_fusion_v1.py --data ./fusion_factors.csv --output-dir ../../models
"""
from __future__ import annotations

import argparse
import random
import sys
from pathlib import Path

import numpy as np


FEATURE_COLS = [
    "displacement_rate", "displacement_total", "rainfall_24h", "rainfall_7d",
    "crack_width", "earthquake_mag", "site_grade",
]

DEFAULT_WEIGHTS = {
    "displacement_rate": 0.25, "displacement_total": 0.15, "rainfall_24h": 0.20,
    "rainfall_7d": 0.12, "crack_width": 0.10, "earthquake_mag": 0.10, "site_grade": 0.08,
}

NORM_MAX = {
    "displacement_rate": 30.0, "displacement_total": 200.0, "rainfall_24h": 150.0,
    "rainfall_7d": 400.0, "crack_width": 50.0, "earthquake_mag": 7.0, "site_grade": 5.0,
}


def load_csv(path: Path):
    if not path.exists():
        print(f"[WARN] CSV 不存在: {path}，生成合成数据演示")
        return synthesize_samples()

    import csv
    X, y = [], []
    with path.open(newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            sample = []
            for col in FEATURE_COLS:
                try:
                    sample.append(float(row.get(col, 0)))
                except ValueError:
                    sample.append(0.0)
            X.append(sample)
            score_col = row.get("risk_score")
            if score_col:
                y.append(1 if float(score_col) >= 60 else 0)
            else:
                # 根据归一化加权和自动标注
                norm_sum = sum(min(1.0, max(0.0, sample[i] / NORM_MAX[FEATURE_COLS[i]])) * DEFAULT_WEIGHTS[FEATURE_COLS[i]]
                              for i in range(len(FEATURE_COLS)))
                y.append(1 if norm_sum >= 0.6 else 0)

    print(f"[train] 加载 {len(X)} 个样本，正例 {sum(y)}/{len(y)} ({sum(y) / len(y) * 100:.1f}%)")
    return np.array(X, dtype=np.float32), np.array(y, dtype=np.int32)


def synthesize_samples():
    """生成合成因子样本 + 标签。"""
    X, y = [], []
    for _ in range(3000):
        sample = [random.uniform(0, NORM_MAX[c]) for c in FEATURE_COLS]
        norm_sum = sum(min(1.0, sample[i] / NORM_MAX[FEATURE_COLS[i]]) * DEFAULT_WEIGHTS[FEATURE_COLS[i]]
                       for i in range(len(FEATURE_COLS)))
        X.append(sample)
        y.append(1 if norm_sum >= 0.6 else 0)
    return np.array(X, dtype=np.float32), np.array(y, dtype=np.int32)


def train(X: np.ndarray, y: np.ndarray, output_path: Path):
    try:
        import xgboost as xgb
    except ImportError:
        print("[ERROR] 请先安装 xgboost: pip install xgboost")
        sys.exit(1)

    # 切分
    n = len(X)
    perm = np.random.permutation(n)
    X, y = X[perm], y[perm]
    split = int(n * 0.8)
    X_train, y_train = X[:split], y[:split]
    X_val, y_val = X[split:], y[split:]

    dtrain = xgb.DMatrix(X_train, label=y_train, feature_names=FEATURE_COLS)
    dval = xgb.DMatrix(X_val, label=y_val, feature_names=FEATURE_COLS)

    params = {
        "objective": "binary:logistic",
        "eval_metric": "auc",
        "max_depth": 4,
        "eta": 0.05,
        "subsample": 0.8,
        "colsample_bytree": 0.8,
        "scale_pos_weight": max(1, (y_train == 0).sum() / max(1, (y_train == 1).sum())),
        "seed": 42,
    }
    model = xgb.train(
        params,
        dtrain,
        num_boost_round=500,
        evals=[(dtrain, "train"), (dval, "val")],
        early_stopping_rounds=30,
        verbose_eval=50,
    )

    output_path.parent.mkdir(parents=True, exist_ok=True)
    model.save_model(str(output_path))
    print(f"[train] 模型已保存: {output_path}")

    # 简单特征重要性
    importance = model.get_score(importance_type="gain")
    print("[train] 特征重要性 (gain):")
    for k, v in sorted(importance.items(), key=lambda x: -x[1]):
        print(f"  {k}: {v:.1f}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", required=True)
    parser.add_argument("--output-dir", default=str(Path(__file__).resolve().parent.parent.parent / "models"))
    args = parser.parse_args()

    X, y = load_csv(Path(args.data))
    output_path = Path(args.output_dir) / "xgboost_fusion.json"
    train(X, y, output_path)


if __name__ == "__main__":
    main()
