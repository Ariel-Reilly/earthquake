#!/usr/bin/env python3
"""重训练 XGBoost 融合预警模型（v2）：基于业务场景的多样化样本生成。

改进点：
 1. 不再生成完全随机的 [0, max] 样本（那种数据点几乎没有信号）
 2. 生成多档位（LOW/MEDIUM/HIGH/CRITICAL）的合成样本，与 comprehensive 一致
 3. 适度回归风险评分（0-100），再归一化到 [0,1] 输出概率
 4. 用 scale_pos_weight 处理不平衡
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


def compute_risk_score(factors: dict) -> float:
    """与 comprehensive_v2 中的 device 维度对齐：连续 [0, 100]。"""
    DEVICE_W = {
        "displacement_rate": 0.25,
        "displacement_total": 0.15,
        "rainfall_24h": 0.20,
        "rainfall_7d": 0.12,
        "crack_width": 0.10,
        "earthquake_mag": 0.10,
        "site_grade": 0.08,
    }
    weighted = 0.0
    total_w = 0.0
    for k, w in DEVICE_W.items():
        v = max(0.0, float(factors.get(k, 0.0)))
        norm = min(1.0, v / NORM_MAX[k])
        weighted += norm * w
        total_w += w
    return (weighted / total_w) * 100.0 if total_w > 0 else 0.0


def synthesize_samples(n_total: int = 5000, seed: int = 42):
    """
    生成多档位样本，覆盖 LOW/MEDIUM/HIGH/CRITICAL。

    distribution:
        - LOW (40%):         bias=-0.5
        - MEDIUM (30%):      bias=0.0
        - HIGH (20%):        bias=0.5
        - CRITICAL (10%):    bias=1.0 (更多边界样本)
    """
    rng = random.Random(seed)
    samples = []
    labels = []  # 二分类：risk_score >= 60 → 1

    distribution = [
        (-0.5, int(n_total * 0.40)),  # LOW
        (0.0,  int(n_total * 0.30)),  # MEDIUM
        (0.5,  int(n_total * 0.20)),  # HIGH
        (1.0,  int(n_total * 0.10)),  # CRITICAL
    ]
    for bias, count in distribution:
        for _ in range(count):
            sample_rng = random.Random(rng.randint(0, 2**31 - 1))
            base_p = sample_rng.random()
            base_p = max(0.0, min(1.0, base_p + bias * 0.45))

            factors = {
                "displacement_rate": max(0.0, sample_rng.uniform(0, NORM_MAX["displacement_rate"]) * (0.3 + base_p * 0.9)),
                "displacement_total": max(0.0, sample_rng.uniform(0, NORM_MAX["displacement_total"]) * (0.3 + base_p * 0.8)),
                "rainfall_24h": max(0.0, sample_rng.uniform(0, NORM_MAX["rainfall_24h"]) * (0.2 + base_p * 1.2)),
                "rainfall_7d": max(0.0, sample_rng.uniform(0, NORM_MAX["rainfall_7d"]) * (0.2 + base_p * 1.0)),
                "crack_width": max(0.0, sample_rng.uniform(0, NORM_MAX["crack_width"]) * (0.2 + base_p * 1.3)),
                "earthquake_mag": max(0.0, sample_rng.uniform(0, NORM_MAX["earthquake_mag"]) * (0.1 + base_p * 0.9)),
                "site_grade": max(1.0, min(5.0, sample_rng.uniform(1, 5) * (0.4 + base_p * 0.8))),
            }
            score = compute_risk_score(factors)
            samples.append([factors[k] for k in FEATURE_COLS])
            labels.append(1 if score >= 60 else 0)

    X = np.array(samples, dtype=np.float32)
    y = np.array(labels, dtype=np.int32)
    print(f"[train] 合成 {len(X)} 个样本，正例 {int(y.sum())}/{len(y)} ({y.mean() * 100:.1f}%)")
    print(f"[train] risk_score 分布: min={compute_risk_score_batch(X).min():.1f}, "
          f"mean={compute_risk_score_batch(X).mean():.1f}, "
          f"max={compute_risk_score_batch(X).max():.1f}")
    return X, y


def compute_risk_score_batch(X):
    """批量计算 risk_score，用于统计分布。"""
    DEVICE_W = {
        "displacement_rate": 0.25,
        "displacement_total": 0.15,
        "rainfall_24h": 0.20,
        "rainfall_7d": 0.12,
        "crack_width": 0.10,
        "earthquake_mag": 0.10,
        "site_grade": 0.08,
    }
    out = np.zeros(len(X))
    for i, k in enumerate(FEATURE_COLS):
        out += np.clip(X[:, i] / NORM_MAX[k], 0, 1) * DEVICE_W[k]
    return out * 100.0 / sum(DEVICE_W.values())


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
    print(f"[train] 训练集正例比例: {y_train.mean():.2%}  验证集正例比例: {y_val.mean():.2%}")

    dtrain = xgb.DMatrix(X_train, label=y_train, feature_names=FEATURE_COLS)
    dval = xgb.DMatrix(X_val, label=y_val, feature_names=FEATURE_COLS)

    # 计算正负例比例用于 scale_pos_weight
    n_pos = int((y_train == 1).sum())
    n_neg = int((y_train == 0).sum())
    scale_pos_weight = max(1.0, n_neg / max(1, n_pos))
    print(f"[train] scale_pos_weight: {scale_pos_weight:.2f}")

    params = {
        "objective": "binary:logistic",
        "eval_metric": ["logloss", "auc", "error"],
        "max_depth": 5,
        "eta": 0.08,
        "subsample": 0.85,
        "colsample_bytree": 0.85,
        "min_child_weight": 3,
        "scale_pos_weight": scale_pos_weight,
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

    # 元数据
    meta = {
        "version": "2.0.0",
        "objective": "binary:logistic",
        "trained_at": datetime.now(timezone.utc).isoformat(),
        "n_samples": int(n),
        "n_train": int(len(X_train)),
        "n_val": int(len(X_val)),
        "n_features": len(FEATURE_COLS),
        "feature_names": FEATURE_COLS,
        "norm_max": NORM_MAX,
        "best_iteration": int(model.best_iteration),
        "best_score": float(model.best_score),
    }
    meta_path = output_dir / "xgboost_fusion.meta.json"
    meta_path.write_text(json.dumps(meta, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"[train] 元数据已保存: {meta_path}")

    # 评估
    print("\n[train] 验证集评估:")
    y_pred_proba = model.predict(dval)
    y_pred = (y_pred_proba >= 0.5).astype(int)
    from sklearn.metrics import classification_report, confusion_matrix
    print(classification_report(y_val, y_pred, target_names=["LOW", "HIGH+"]))
    print("Confusion Matrix:")
    print(confusion_matrix(y_val, y_pred))

    # 特征重要性
    print("\n[train] 特征重要性 (gain):")
    importance = model.get_score(importance_type="gain")
    for k, v in sorted(importance.items(), key=lambda x: -x[1]):
        print(f"  {k}: {v:.1f}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--n-samples", type=int, default=5000)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--output-dir", default=str(Path(__file__).resolve().parent.parent.parent / "models"))
    args = parser.parse_args()

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    X, y = synthesize_samples(n_total=args.n_samples, seed=args.seed)
    train(X, y, output_dir)


if __name__ == "__main__":
    main()