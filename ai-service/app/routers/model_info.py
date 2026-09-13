"""
模型信息端点：暴露当前 XGBoost fusion 模型的加载状态 / 训练指标 / 特征重要性，
并支持重新加载磁盘模型（用于训练后立即热加载）。
"""
from __future__ import annotations

import json
import logging
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from app.models.xgboost_model import (
    DEFAULT_WEIGHTS,
    MODELS_DIR,
    FusionRiskModel,
    get_fusion_model,
)
from app.training.train_xgboost import train_xgboost

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/ai/model", tags=["ai-model"])

META_PATH = MODELS_DIR / "xgboost_fusion.meta.json"


def _load_meta() -> Dict[str, Any]:
    if not META_PATH.exists():
        return {}
    try:
        return json.loads(META_PATH.read_text(encoding="utf-8"))
    except Exception as e:  # noqa: BLE001
        logger.warning("meta 加载失败: %s", e)
        return {}


@router.get("/info")
async def model_info() -> Dict[str, Any]:
    """当前模型状态 + 训练指标 + 特征重要性。"""
    model = get_fusion_model()
    meta = _load_meta()
    return {
        "model_loaded": model.is_loaded(),
        "model_path": str(model.model_path),
        "model_exists_on_disk": model.model_path.exists(),
        "feature_names": list(DEFAULT_WEIGHTS.keys()),
        "weights": DEFAULT_WEIGHTS,
        "trained_at": meta.get("trained_at"),
        "version": meta.get("version"),
        "samples_train": meta.get("samples_train"),
        "samples_val": meta.get("samples_val"),
        "pos_rate_train": meta.get("pos_rate_train"),
        "scale_pos_weight": meta.get("scale_pos_weight"),
        "best_iteration": meta.get("best_iteration"),
        "training_seconds": meta.get("training_seconds"),
        "train_metrics": meta.get("train_metrics"),
        "val_metrics": meta.get("val_metrics"),
        "feature_importance": meta.get("feature_importance"),
        "history_files": _list_history_files(),
        "queried_at": datetime.now(timezone.utc).isoformat(),
    }


@router.post("/reload")
async def model_reload() -> Dict[str, Any]:
    """重新加载磁盘上的模型（训练后用）。

    重建 FusionRiskModel 单例并触发 _load()。
    """
    from app.models import xgboost_model as xm

    xm._fusion_model = None  # type: ignore[attr-defined]
    model = get_fusion_model()
    return {
        "reloaded": True,
        "model_loaded": model.is_loaded(),
        "model_path": str(model.model_path),
        "reloaded_at": datetime.now(timezone.utc).isoformat(),
    }


class RetrainRequest(BaseModel):
    samples: int = 1500
    val_ratio: float = 0.2
    force: bool = False


@router.post("/retrain")
async def model_retrain(req: RetrainRequest | None = None) -> Dict[str, Any]:
    """同步训练新模型（小数据集下 1-3s 完成；生产应改为 BackgroundTasks）。

    训练完成后自动 reload。
    """
    req = req or RetrainRequest()
    result = train_xgboost(
        target_samples=req.samples if req.force else max(req.samples, 100),
        val_ratio=req.val_ratio,
    )
    # 训练成功 → reload
    if result.get("status") == "trained":
        from app.models import xgboost_model as xm

        xm._fusion_model = None  # type: ignore[attr-defined]
        new_model = get_fusion_model()
        result["reloaded"] = new_model.is_loaded()
        result["model_path"] = str(new_model.model_path)
    return result


def _list_history_files() -> list[Dict[str, Any]]:
    history_dir = MODELS_DIR / "history"
    if not history_dir.exists():
        return []
    out = []
    for p in sorted(history_dir.glob("xgboost_fusion-*.json"), reverse=True):
        try:
            st = p.stat()
            out.append({
                "filename": p.name,
                "size_bytes": st.st_size,
                "modified_at": datetime.fromtimestamp(st.st_mtime, timezone.utc).isoformat(),
            })
        except OSError:
            continue
    return out[:10]  # 最多 10 条