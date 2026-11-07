#!/usr/bin/env python3
"""回填 xgboost_fusion.meta.json 的 feature_importance / val_metrics。

背景：v3（train_fusion_v3.py）重构时把 MAE / RMSE / 分档一致性 / gain 重要性
都只打印到 stdout，没有写进 meta，导致 meta 里查不到这两块。
本脚本**不重新训练、不改模型权重**，只：
  1. 加载磁盘上的 Booster，用 ``get_score(importance_type='gain')`` 取真实重要性；
  2. 用 ``synthesize_samples(n_samples, seed)`` 重放训练时的同一份合成数据
     （``random.Random(seed)`` + ``RandomState(42).permutation`` 均确定性，可复现），
     在**同一验证集**上重算 MAE / RMSE / 分档一致性；
  3. 把结果合并进现有 meta（其余字段原样保留），旧 meta 自动备份。

诚实性说明：评估集是合成数据（meta 里写 ``eval_dataset: synthetic``），
不是真实灾害样本；这里的指标只能说明"模型拟合了生成规则"，不能当作真实预测精度。

用法（ai-service 目录下）：
    E:/anaconda3/python.exe scripts/training/backfill_fusion_meta.py
    E:/anaconda3/python.exe scripts/training/backfill_fusion_meta.py --dry-run
"""
from __future__ import annotations

import argparse
import json
import shutil
import sys
from datetime import datetime
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from train_fusion_v3 import FEATURE_COLS, synthesize_samples  # noqa: E402

DEFAULT_MODELS_DIR = HERE.parent.parent / "models"


def level_of(scores: np.ndarray) -> np.ndarray:
    return np.where(
        scores >= 80, "CRITICAL",
        np.where(scores >= 60, "HIGH", np.where(scores >= 35, "MEDIUM", "LOW")),
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--models-dir", default=str(DEFAULT_MODELS_DIR))
    parser.add_argument("--dry-run", action="store_true", help="只打印，不写文件")
    args = parser.parse_args()

    models_dir = Path(args.models_dir)
    model_path = models_dir / "xgboost_fusion.json"
    meta_path = models_dir / "xgboost_fusion.meta.json"
    if not model_path.exists():
        print(f"[ERROR] 模型不存在: {model_path}")
        return 2
    if not meta_path.exists():
        print(f"[ERROR] meta 不存在: {meta_path}")
        return 2

    meta = json.loads(meta_path.read_text(encoding="utf-8"))
    n_samples = int(meta.get("n_samples", 8000))
    eval_seed = int(meta.get("val_metrics", {}).get("eval_seed", 42))

    try:
        import xgboost as xgb
    except ImportError:
        print("[ERROR] 未安装 xgboost")
        return 2

    booster = xgb.Booster()
    booster.load_model(str(model_path))
    n_feature_meta = int(meta.get("n_features", len(FEATURE_COLS)))
    if n_feature_meta != len(FEATURE_COLS):
        print(f"[WARN] meta.n_features={n_feature_meta} 与 FEATURE_COLS={len(FEATURE_COLS)} 不一致")

    raw = booster.get_score(importance_type="gain")
    importance = {k: float(raw.get(k, 0.0)) for k in FEATURE_COLS}
    print("[backfill] 特征重要性 (gain):")
    for k, v in sorted(importance.items(), key=lambda x: -x[1]):
        print(f"  {k}: {v:.1f}")

    # 重放同一份合成数据（与 train_fusion_v3 的默认参数一致）
    X, y = synthesize_samples(n_total=n_samples, seed=eval_seed)
    perm = np.random.RandomState(eval_seed).permutation(len(X))
    X, y = X[perm], y[perm]
    split = int(len(X) * 0.85)
    X_val, y_val = X[split:], y[split:]

    dval = xgb.DMatrix(X_val, label=y_val, feature_names=FEATURE_COLS)
    y_pred = booster.predict(dval)
    mae = float(np.mean(np.abs(y_pred - y_val)))
    rmse = float(np.sqrt(np.mean((y_pred - y_val) ** 2)))
    agreement = float(np.mean(level_of(y_val) == level_of(y_pred)))
    print(f"[backfill] 验证集 n={len(y_val)} MAE={mae:.3f} RMSE={rmse:.3f} 分档一致性={agreement:.1%}")

    meta["feature_importance"] = importance
    meta["feature_importance_type"] = "gain"
    meta["val_metrics"] = {
        "mae": mae,
        "rmse": rmse,
        "level_agreement": agreement,
        "eval_dataset": "synthetic",
        "eval_seed": eval_seed,
    }
    meta["meta_backfilled_at"] = datetime.now().astimezone().isoformat()

    if args.dry_run:
        print("[backfill] --dry-run：未写入")
        return 0

    backup = meta_path.with_suffix(
        f".meta.backup.{datetime.now().strftime('%Y%m%d_%H%M%S')}.json"
    )
    shutil.copy2(meta_path, backup)
    meta_path.write_text(json.dumps(meta, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"[backfill] 已写入 {meta_path}（备份 {backup.name}）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
