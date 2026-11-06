"""
模型信息端点：暴露当前所有模型的加载状态 / 训练指标 / 特征重要性，
并支持重新加载磁盘模型（用于训练后立即热加载）。
"""
from __future__ import annotations

import json
import logging
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from app.models.xgboost_model import (
    DEFAULT_WEIGHTS,
    MODELS_DIR,
    FusionRiskModel,
    get_fusion_model,
)
from app.models.comprehensive_model import DIMENSION_WEIGHTS, get_comprehensive_model
from app.training.train_xgboost import train_xgboost

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/api/ai/model", tags=["ai-model"])

FUSION_META_PATH = MODELS_DIR / "xgboost_fusion.meta.json"
COMPREHENSIVE_META_PATH = MODELS_DIR / "comprehensive_v2.meta.json"
LSTM_META_PATH = MODELS_DIR / "lstm_displacement.meta.json"
IFOREST_META_PATH = MODELS_DIR / "isolation_forest.meta.json"


def _read_meta(path: Path) -> Dict[str, Any]:
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception as e:  # noqa: BLE001
        logger.warning("meta 加载失败 %s: %s", path, e)
        return {}


def _model_status(path: Path, name: str, meta: Dict[str, Any] = None) -> Dict[str, Any]:
    """返回单个模型的磁盘状态、内存加载状态、元数据。"""
    info: Dict[str, Any] = {
        "name": name,
        "path": str(path),
        "exists_on_disk": path.exists(),
        "size_bytes": path.stat().st_size if path.exists() else 0,
    }
    if meta:
        info.update({
            "trained_at": meta.get("trained_at"),
            "version": meta.get("version"),
            "feature_count": meta.get("n_features") or meta.get("feature_count"),
        })
    return info


@router.get("/info")
async def model_info() -> Dict[str, Any]:
    """当前所有模型的状态 + 训练指标 + 特征重要性。"""
    fusion = get_fusion_model()
    comprehensive = get_comprehensive_model()
    fusion_meta = _read_meta(FUSION_META_PATH)
    comprehensive_meta = _read_meta(COMPREHENSIVE_META_PATH)
    lstm_meta = _read_meta(LSTM_META_PATH)
    iforest_meta = _read_meta(IFOREST_META_PATH)

    # 探测所有模型文件状态
    all_models = [
        _model_status(MODELS_DIR / "yolo_crack.onnx", "YOLO 裂缝/落石检测"),
        _model_status(MODELS_DIR / "lstm_displacement.pt", "LSTM 位移预测", lstm_meta),
        _model_status(MODELS_DIR / "isolation_forest.joblib", "Isolation Forest 异常检测", iforest_meta),
        _model_status(MODELS_DIR / "xgboost_fusion.json", "XGBoost 融合预警", fusion_meta),
        _model_status(MODELS_DIR / "comprehensive_v2.json", "综合风险分析", comprehensive_meta),
    ]

    return {
        "models": all_models,
        # XGBoost fusion 模型详细信息
        "fusion_model": {
            "model_loaded": fusion.is_loaded(),
            "model_path": str(fusion.model_path),
            "model_exists_on_disk": fusion.model_path.exists(),
            "feature_names": list(DEFAULT_WEIGHTS.keys()),
            "weights": DEFAULT_WEIGHTS,
        },
        # 综合风险模型详细信息
        "comprehensive_model": {
            "model_loaded": comprehensive.is_loaded(),
            "model_path": str(comprehensive.model_path),
            "model_exists_on_disk": comprehensive.model_path.exists(),
            "dimension_weights": DIMENSION_WEIGHTS,
            "feature_names": comprehensive_meta.get("feature_names", []),
            "n_features": comprehensive_meta.get("n_features"),
            "n_samples_trained": comprehensive_meta.get("n_samples"),
        },
        # 元数据（历史训练信息）
        "xgboost_meta": {
            "trained_at": fusion_meta.get("trained_at"),
            "version": fusion_meta.get("version"),
            "samples_train": fusion_meta.get("samples_train"),
            "samples_val": fusion_meta.get("samples_val"),
            "pos_rate_train": fusion_meta.get("pos_rate_train"),
            "scale_pos_weight": fusion_meta.get("scale_pos_weight"),
            "best_iteration": fusion_meta.get("best_iteration"),
            "training_seconds": fusion_meta.get("training_seconds"),
            "train_metrics": fusion_meta.get("train_metrics"),
            "val_metrics": fusion_meta.get("val_metrics"),
            "feature_importance": fusion_meta.get("feature_importance"),
            "history_files": _list_history_files(),
        },
        "comprehensive_meta": comprehensive_meta,
        "lstm_meta": lstm_meta,
        "iforest_meta": iforest_meta,
        "queried_at": datetime.now(timezone.utc).isoformat(),
    }


@router.post("/reload")
async def model_reload(model_name: str = "all") -> Dict[str, Any]:
    """
    重新加载磁盘上的模型（训练后用）。

    Args:
        model_name: 'all' / 'fusion' / 'comprehensive' / 'lstm' / 'iforest' / 'yolo'
    """
    reloaded: Dict[str, Any] = {}

    if model_name in ("all", "fusion"):
        from app.models import xgboost_model as xm
        xm._fusion_model = None  # type: ignore[attr-defined]
        m = get_fusion_model()
        reloaded["fusion"] = {
            "reloaded": True,
            "model_loaded": m.is_loaded(),
            "model_path": str(m.model_path),
        }

    if model_name in ("all", "comprehensive"):
        from app.models import comprehensive_model as cm_mod
        cm_mod._comprehensive_model = None  # type: ignore[attr-defined]
        m = get_comprehensive_model()
        reloaded["comprehensive"] = {
            "reloaded": True,
            "model_loaded": m.is_loaded(),
            "model_path": str(m.model_path),
        }

    if model_name in ("all", "lstm"):
        from app.models import lstm_model as lm
        lm._forecaster = None  # type: ignore[attr-defined]
        m = lm.get_forecaster()
        reloaded["lstm"] = {
            "reloaded": True,
            "model_loaded": m.is_loaded(),
            "model_path": str(m.model_path),
        }

    if model_name in ("all", "iforest"):
        from app.models import isolation_forest as ifo
        ifo._detector = None  # type: ignore[attr-defined]
        m = ifo.get_detector()
        reloaded["iforest"] = {
            "reloaded": True,
            "model_loaded": m.is_loaded(),
            "model_path": str(m.model_path),
        }

    if model_name in ("all", "yolo"):
        from app.models import yolo_model as ym
        ym._detector = None  # type: ignore[attr-defined]
        m = ym.get_detector()
        reloaded["yolo"] = {
            "reloaded": True,
            "model_loaded": m.is_loaded(),
            "model_path": str(m.model_path),
            "model_variant": m.model_variant,
            "is_business_model": m.is_business_model,
            "num_classes": m.num_classes,
        }

    return {
        "reloaded": reloaded,
        "reloaded_at": datetime.now(timezone.utc).isoformat(),
    }


@router.get("/health")
async def model_health() -> Dict[str, Any]:
    """
    轻量级健康检查：每个模型跑一次最小推理（dummy 输入），验证可用性。
    """
    results: Dict[str, Any] = {}

    # YOLO
    try:
        from app.models.yolo_model import get_detector
        det = get_detector()
        import numpy as np
        import cv2
        dummy = np.full((640, 640, 3), 200, dtype=np.uint8)
        _, buf = cv2.imencode('.jpg', dummy)
        r = det.detect(buf.tobytes())
        results["yolo"] = {
            "ok": True,
            "model_loaded": det.is_loaded(),
            "inference_ok": True,
            "model_variant": det.model_variant,
            "is_business_model": det.is_business_model,
            "num_classes": det.num_classes,
        }
    except Exception as e:
        results["yolo"] = {"ok": False, "error": str(e)}

    # LSTM
    try:
        from app.models.lstm_model import get_forecaster
        fc = get_forecaster()
        r = fc.predict([1.0, 1.2, 1.5, 1.8, 2.0], horizon=3)
        results["lstm"] = {
            "ok": True,
            "model_loaded": fc.is_loaded(),
            "inference_ok": True,
            "sample_predictions": r["predictions"][:3],
        }
    except Exception as e:
        results["lstm"] = {"ok": False, "error": str(e)}

    # Isolation Forest
    try:
        from app.models.isolation_forest import get_detector
        det = get_detector()
        r = det.detect([[1.0, 1.0, 0.5, 2.0, 1.5], [1.2, 1.1, 0.4, 2.1, 1.4]])
        results["iforest"] = {
            "ok": True,
            "model_loaded": det.is_loaded(),
            "inference_ok": True,
        }
    except Exception as e:
        results["iforest"] = {"ok": False, "error": str(e)}

    # XGBoost Fusion
    try:
        from app.models.xgboost_model import get_fusion_model
        fm = get_fusion_model()
        r = fm.score({"displacement_rate": 1.0, "rainfall_24h": 10})
        results["fusion"] = {
            "ok": True,
            "model_loaded": fm.is_loaded(),
            "inference_ok": True,
            "sample_score": r["risk_score"],
        }
    except Exception as e:
        results["fusion"] = {"ok": False, "error": str(e)}

    # Comprehensive
    try:
        from app.models.comprehensive_model import get_comprehensive_model
        cm = get_comprehensive_model()
        r = cm.score({"rainfall_24h": 30, "slope_deg": 25}, region_code="health_check")
        results["comprehensive"] = {
            "ok": True,
            "model_loaded": cm.is_loaded(),
            "inference_ok": True,
            "sample_score": r["overall"]["risk_score"],
        }
    except Exception as e:
        results["comprehensive"] = {"ok": False, "error": str(e)}

    overall_ok = all(v.get("ok") for v in results.values())
    return {
        "status": "UP" if overall_ok else "DEGRADED",
        "results": results,
        "checked_at": datetime.now(timezone.utc).isoformat(),
    }


class RetrainRequest(BaseModel):
    samples: int = Field(1500, ge=100, le=100000, description="训练样本数，上限防止 DoS；超出系统内存时自动截断")
    val_ratio: float = Field(0.2, ge=0.05, le=0.4, description="验证集比例 5%-40%")
    force: bool = Field(False, description="true 时跳过最低样本检查（慎用）")


@router.post("/retrain")
async def model_retrain(req: RetrainRequest | None = None) -> Dict[str, Any]:
    """同步训练新模型（小数据集下 1-3s 完成；生产应改为 BackgroundTasks）。

    训练完成后自动 reload。
    """
    req = req or RetrainRequest()
    result = train_xgboost(
        target_samples=req.samples,
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