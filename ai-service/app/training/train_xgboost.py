"""
训练脚本：根据反馈数据 + 合成数据训练 XGBoost fusion 模型。

调用方式：
  1. POST /api/ai/model/retrain → 后台跑 train()
  2. python -m app.training.train_xgboost [--force] [--samples 2000]

数据流：
  FeedbackStore.to_training_rows() + SyntheticDataset.generate()
    → 合并/去重/平衡
    → XGBoost.train(params, dtrain, num_boost_round=120)
    → 验证集 evaluation
    → models/xgboost_fusion.json（供 xgboost_model.FusionRiskModel 加载）
    → models/history/xgboost_fusion-YYYYMMDD-HHMMSS.json（历史归档）

训练数据 schema：
  - features: 7 个 fusion 因子（displacement_rate/displacement_total/rainfall_24h/
              rainfall_7d/crack_width/earthquake_mag/site_grade）
  - label: occurred (1=发生, 0=未发生)

样本策略（v1.5.0 起）：
  - 优先用 FeedbackStore 真实反馈（带权重 = 真实标注 × 3）
  - 不足时自动生成合成数据补足到 min_samples（默认 1500）
  - 合成数据基于物理规则（位移+降雨+裂缝耦合），与地理学经验一致
"""
from __future__ import annotations

import argparse
import json
import logging
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import numpy as np

# 让脚本能从 ai-service/ 根目录 import
SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent.parent
sys.path.insert(0, str(REPO_ROOT))

from app.models.feedback_store import get_feedback_store
from app.models.xgboost_model import DEFAULT_WEIGHTS, NORM_MAX
from app.training.synthetic_data import SyntheticDataset

# 模型输出路径（与 xgboost_model.py 的 MODELS_DIR 一致）
MODELS_DIR = REPO_ROOT / "models"
DEFAULT_OUTPUT = MODELS_DIR / "xgboost_fusion.json"
DEFAULT_HISTORY_DIR = MODELS_DIR / "history"
DEFAULT_META_PATH = MODELS_DIR / "xgboost_fusion.meta.json"

logger = logging.getLogger("train_xgboost")
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)


def _load_feedback_rows() -> List[Dict]:
    """从 FeedbackStore 拉真实反馈样本。

    只有携带 ``factors``（提交反馈时由后端附上的真实指标快照）的记录才是可用训练样本。
    此前这里用 predicted_score 反推 7 维特征，等于把模型自己的输出当输入，
    属于标签泄漏，训出来的模型指标虚高、上线无效。现在不再反推。
    """
    store = get_feedback_store()
    rows = store.to_training_rows()
    out = []
    for r in rows:
        raw = r.get("factors") or {}
        factors = {
            k: float(v)
            for k, v in raw.items()
            if isinstance(v, (int, float)) and not isinstance(v, bool)
        }
        out.append({
            "factors": factors,
            "label": int(r.get("occurred", 0)),
            "source": "feedback",
            "has_features": bool(factors),
        })
    return out


def _augment_with_synthetic(
    feedback_rows: List[Dict],
    target_samples: int,
) -> List[Dict]:
    """用合成数据补足到 target_samples。"""
    need = target_samples - len(feedback_rows)
    if need <= 0:
        return feedback_rows
    logger.info("真实反馈 %d 条，合成补足 %d 条", len(feedback_rows), need)
    synth = SyntheticDataset.generate(n=need)
    return feedback_rows + synth


def load_training_data(
    target_samples: int = 1500,
    allow_synthetic: bool = False,
) -> Tuple[np.ndarray, np.ndarray, List[Dict], Dict]:
    """拉训练数据。

    **2026-11-05 变更**：此前"真实反馈"是用 predicted_score 反推 7 维特征
    （score<35 全 0、>=80 极端组合、中间按比例摊），等于把标签信息泄漏进特征，
    训出来的模型在真实数据上不可用，且对外表现为"已用真实反馈训练"。

    现在：
      * 只有带 ``factors``（真实指标快照）的反馈才进训练集；
      * 无 factors 的反馈被跳过并计数，绝不臆造特征；
      * 合成样本默认不启用（``allow_synthetic=False``），调用方显式开启时
        返回值里会带 ``synthetic_ratio``，避免把合成数据当真实训练成果。

    Returns:
        X, y, meta, dataset_info（含 real_samples / skipped_no_features / synthetic_samples）
    """
    feedback = _load_feedback_rows()
    real_rows = [r for r in feedback if r["has_features"]]
    skipped = len(feedback) - len(real_rows)

    synthetic_rows: List[Dict] = []
    if allow_synthetic:
        need = max(0, target_samples - len(real_rows))
        if need > 0:
            synthetic_rows = SyntheticDataset.generate(n=need)
            logger.warning(
                "真实样本 %d 条，按调用方要求合成补足 %d 条（合成数据不可当作真实训练成果）",
                len(real_rows), need,
            )

    merged = real_rows + synthetic_rows
    feature_names = list(DEFAULT_WEIGHTS.keys())
    X = []
    y = []
    meta = []
    for row in merged:
        factors = row["factors"]
        X.append([float(factors.get(name, 0.0)) for name in feature_names])
        y.append(int(row["label"]))
        meta.append(row)

    total = len(merged)
    info = {
        "real_samples": len(real_rows),
        "skipped_no_features": skipped,
        "synthetic_samples": len(synthetic_rows),
        "synthetic_ratio": round(len(synthetic_rows) / total, 4) if total else 0.0,
        "allow_synthetic": allow_synthetic,
    }
    return (
        np.asarray(X, dtype=np.float32),
        np.asarray(y, dtype=np.float32),
        meta,
        info,
    )


def _eval_metrics(booster, dmatrix, y_true) -> Dict[str, float]:
    """在给定 DMatrix 上计算 accuracy / precision / recall / f1 / logloss。"""
    from sklearn.metrics import accuracy_score, f1_score, log_loss, precision_score, recall_score

    pred_prob = booster.predict(dmatrix)
    pred_label = (pred_prob >= 0.5).astype(int)
    eps = 1e-7
    try:
        ll = float(log_loss(y_true, np.clip(pred_prob, eps, 1 - eps)))
    except Exception:  # noqa: BLE001
        ll = float("nan")
    return {
        "accuracy": round(float(accuracy_score(y_true, pred_label)), 4),
        "precision": round(float(precision_score(y_true, pred_label, zero_division=0)), 4),
        "recall": round(float(recall_score(y_true, pred_label, zero_division=0)), 4),
        "f1": round(float(f1_score(y_true, pred_label, zero_division=0)), 4),
        "logloss": round(ll, 4),
    }


def _feature_importance(booster, feature_names: List[str]) -> List[Dict]:
    """XGBoost 内置的 gain importance。"""
    raw = booster.get_score(importance_type="gain")
    out = []
    for name in feature_names:
        out.append({"feature": name, "importance": round(float(raw.get(name, 0.0)), 4)})
    out.sort(key=lambda x: x["importance"], reverse=True)
    return out


def train_xgboost(
    output_path: Path = DEFAULT_OUTPUT,
    history_dir: Path = DEFAULT_HISTORY_DIR,
    meta_path: Path = DEFAULT_META_PATH,
    target_samples: int = 1500,
    val_ratio: float = 0.2,
    allow_synthetic: bool = False,
) -> Dict:
    """训练 XGBoost 模型并保存。

    allow_synthetic 默认 False：没有真实带特征的样本时就如实返回 skipped，
    而不是拿合成数据训一个模型继续对外声称"已训练"。

    Returns:
        包含 status / samples / model_path / archive_path / val_metrics /
        feature_importance / trained_at / training_seconds / dataset 的字典。
    """
    t0 = datetime.now(timezone.utc)
    X, y, meta, dataset_info = load_training_data(
        target_samples=target_samples,
        allow_synthetic=allow_synthetic,
    )

    if len(y) < 50:
        return {
            "status": "skipped",
            "reason": (
                f"可用训练样本过少 ({len(y)}<50)：真实样本需携带 device_metric 特征快照，"
                f"当前跳过 {dataset_info['skipped_no_features']} 条无特征反馈"
            ),
            "samples": int(len(y)),
            "dataset": dataset_info,
        }

    try:
        import xgboost as xgb
    except ImportError:
        return {
            "status": "skipped",
            "reason": "xgboost 未安装",
            "samples": int(len(y)),
            "dataset": dataset_info,
        }

    # 拆分 train / val
    rng = np.random.default_rng(42)
    n = len(y)
    idx = rng.permutation(n)
    cut = int(n * (1 - val_ratio))
    train_idx, val_idx = idx[:cut], idx[cut:]
    X_tr, y_tr = X[train_idx], y[train_idx]
    X_va, y_va = X[val_idx], y[val_idx]

    feature_names = list(DEFAULT_WEIGHTS.keys())
    dtrain = xgb.DMatrix(X_tr, label=y_tr, feature_names=feature_names)
    dval = xgb.DMatrix(X_va, label=y_va, feature_names=feature_names)

    # 正负样本失衡处理
    pos = int(y_tr.sum())
    neg = int(len(y_tr) - pos)
    scale_pos_weight = (neg / pos) if pos > 0 else 1.0

    params = {
        "objective": "binary:logistic",
        "max_depth": 5,
        "eta": 0.08,
        "eval_metric": ["logloss", "error"],
        "verbosity": 0,
        "subsample": 0.85,
        "colsample_bytree": 0.85,
        "min_child_weight": 3,
        "scale_pos_weight": scale_pos_weight,
        "seed": 42,
    }

    logger.info(
        "开始训练 XGBoost: n=%d (train=%d val=%d) pos_rate=%.3f spw=%.2f",
        n, len(y_tr), len(y_va), pos / max(1, len(y_tr)), scale_pos_weight,
    )
    evals_result = {}
    booster = xgb.train(
        params,
        dtrain,
        num_boost_round=200,
        evals=[(dtrain, "train"), (dval, "val")],
        early_stopping_rounds=20,
        verbose_eval=False,
        evals_result=evals_result,
    )

    # 保存模型
    MODELS_DIR.mkdir(parents=True, exist_ok=True)
    booster.save_model(str(output_path))

    # 历史归档
    history_dir.mkdir(parents=True, exist_ok=True)
    archive_path = history_dir / f"xgboost_fusion-{t0.strftime('%Y%m%d-%H%M%S')}.json"
    booster.save_model(str(archive_path))

    # 评估
    train_metrics = _eval_metrics(booster, dtrain, y_tr)
    val_metrics = _eval_metrics(booster, dval, y_va)
    importance = _feature_importance(booster, feature_names)

    # 元数据（供 model_info 端点读取）
    meta_obj = {
        "version": "1.5.0",
        "trained_at": t0.isoformat(),
        "samples_total": int(n),
        "samples_train": int(len(y_tr)),
        "samples_val": int(len(y_va)),
        "pos_rate_train": round(float(y_tr.mean()), 4),
        "scale_pos_weight": round(float(scale_pos_weight), 4),
        "best_iteration": int(getattr(booster, "best_iteration", 0) or 0),
        "feature_names": feature_names,
        "norm_max": {k: float(v) for k, v in NORM_MAX.items()},
        "feature_importance": importance,
        "train_metrics": train_metrics,
        "val_metrics": val_metrics,
        "model_path": str(output_path),
        "archive_path": str(archive_path),
        "training_seconds": round((datetime.now(timezone.utc) - t0).total_seconds(), 2),
        # 训练数据构成：真实样本 / 丢弃的无特征反馈 / 合成样本占比。
        # 对外展示模型效果时必须连带这一项，避免把合成数据训练结果当真实成果。
        "dataset": dataset_info,
    }
    meta_path.write_text(json.dumps(meta_obj, ensure_ascii=False, indent=2), encoding="utf-8")

    logger.info(
        "训练完成: val_acc=%.4f val_f1=%.4f loss=%.4f best_iter=%d 耗时=%.1fs",
        val_metrics["accuracy"], val_metrics["f1"], val_metrics["logloss"],
        meta_obj["best_iteration"], meta_obj["training_seconds"],
    )

    return meta_obj


def main() -> int:
    parser = argparse.ArgumentParser(description="训练 XGBoost fusion 模型")
    parser.add_argument("--output", type=str, default=str(DEFAULT_OUTPUT))
    parser.add_argument("--history-dir", type=str, default=str(DEFAULT_HISTORY_DIR))
    parser.add_argument("--meta-path", type=str, default=str(DEFAULT_META_PATH))
    parser.add_argument("--samples", type=int, default=1500, help="目标训练样本数")
    parser.add_argument("--val-ratio", type=float, default=0.2)
    parser.add_argument("--force", action="store_true", help="即使样本不足也尝试训练")
    args = parser.parse_args()

    result = train_xgboost(
        output_path=Path(args.output),
        history_dir=Path(args.history_dir),
        meta_path=Path(args.meta_path),
        target_samples=max(50, args.samples) if args.force else args.samples,
        val_ratio=args.val_ratio,
    )
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["status"] == "trained" else 1


if __name__ == "__main__":
    sys.exit(main())