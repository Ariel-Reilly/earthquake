#!/usr/bin/env python3
"""
Isolation Forest 异常检测模型训练脚本。

从 device_metric CSV 导出正常数据（异常点已剔除），训练 Isolation Forest。

用法:
    python train_isolation_forest.py --data ./device_metric.csv --output-dir ../../models
"""
from __future__ import annotations

import argparse
import random
import sys
from pathlib import Path

import numpy as np


def load_csv(path: Path, metric_filter: str = "displacement") -> np.ndarray:
    if not path.exists():
        print(f"[WARN] CSV 不存在: {path}，生成合成数据演示")
        return synthesize_normal()

    values = []
    import csv
    with path.open(newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            if metric_filter and row.get("metric_code") != metric_filter:
                continue
            try:
                values.append(float(row.get("value", 0)))
            except ValueError:
                continue
    print(f"[train] 加载 {len(values)} 个正常数据点")
    return np.array(values, dtype=np.float64).reshape(-1, 1)


def synthesize_normal() -> np.ndarray:
    """生成合成正常位移数据（带少量随机扰动）。"""
    arr = []
    base = 10.0
    for i in range(2000):
        val = base + 0.01 * i + random.gauss(0, 0.3)
        arr.append(round(val, 3))
    return np.array(arr, dtype=np.float64).reshape(-1, 1)


def train(X: np.ndarray, contamination: float, output_path: Path):
    try:
        from sklearn.ensemble import IsolationForest
        import joblib
    except ImportError:
        print("[ERROR] 请先安装 scikit-learn + joblib: pip install scikit-learn joblib")
        sys.exit(1)

    model = IsolationForest(
        n_estimators=200,
        contamination=contamination,
        random_state=42,
        n_jobs=-1,
    )
    model.fit(X)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    joblib.dump(model, str(output_path))

    # 简单验证：在训练集上预测
    preds = model.predict(X)
    n_anomaly = (preds == -1).sum()
    print(f"[train] 训练完成，输出: {output_path}")
    print(f"[train] 训练集大小: {len(X)}, 异常点比例: {n_anomaly}/{len(X)} ({n_anomaly / len(X) * 100:.1f}%)")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", required=True)
    parser.add_argument("--metric", default="displacement")
    parser.add_argument("--contamination", type=float, default=0.1)
    parser.add_argument("--output-dir", default=str(Path(__file__).resolve().parent.parent.parent / "models"))
    args = parser.parse_args()

    X = load_csv(Path(args.data), args.metric)
    output_path = Path(args.output_dir) / "isolation_forest.joblib"
    train(X, args.contamination, output_path)


if __name__ == "__main__":
    main()
