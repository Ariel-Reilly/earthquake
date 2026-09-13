"""
反馈路由：POST /api/ai/feedback / GET /api/ai/feedback/stats / POST /api/ai/model/retrain。

设计目标：
  - 让值班员/系统对 AI 判定结果给出"实际是否发生"的反馈；
  - 反馈存储到 feedback_store（内存版），后续可换 PostgreSQL/Redis；
  - 提供 stats 端点供前端展示混淆矩阵 + precision/recall/F1；
  - 提供 retrain 端点触发 train_xgboost.py 重新训练（异步，避免阻塞）。

请求示例：
  POST /api/ai/feedback
  {
    "hazard_code": "GS-001",
    "predicted_level": "HIGH",
    "predicted_score": 75.3,
    "predicted_at": "2026-09-07T08:00:00Z",
    "outcome": "true_positive",
    "occurred": true,
    "confidence": 0.9,
    "feedback_by": "operator-zhang",
    "note": "08:30 收到现场报告，山体出现新裂缝"
  }
"""
from __future__ import annotations

import logging
import subprocess
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional

from fastapi import APIRouter, BackgroundTasks, HTTPException
from pydantic import BaseModel, Field

from app.models.feedback_store import (
    VALID_OUTCOMES,
    get_feedback_store,
)

router = APIRouter(prefix="/api/ai", tags=["feedback"])
logger = logging.getLogger(__name__)


# ---------- DTO ---------- #
class FeedbackPayload(BaseModel):
    hazard_code: str = Field(..., min_length=1, max_length=64)
    predicted_level: str = Field(..., pattern="^(LOW|MEDIUM|HIGH|CRITICAL)$")
    predicted_score: float = Field(0.0, ge=0.0, le=100.0)
    predicted_at: str = Field("")
    outcome: str = Field(..., description="true_positive / true_negative / false_positive / false_negative")
    occurred: bool
    confidence: float = Field(1.0, ge=0.0, le=1.0)
    feedback_by: str = Field("anonymous", max_length=64)
    note: str = Field("", max_length=500)


class RetrainRequest(BaseModel):
    min_samples: int = Field(20, ge=1, le=1000, description="最少训练样本数；不足则跳过")
    force: bool = Field(False, description="即使样本不足也强行跑训练")


# ---------- 端点 ---------- #
@router.post("/feedback")
async def submit_feedback(payload: FeedbackPayload) -> Dict[str, Any]:
    """提交一条反馈记录。"""
    store = get_feedback_store()
    outcome = payload.outcome.strip().lower()
    if outcome not in VALID_OUTCOMES:
        raise HTTPException(
            status_code=400,
            detail=f"outcome 必须是 {sorted(VALID_OUTCOMES)} 之一，收到 {outcome!r}",
        )
    record = store.add(payload.model_dump())
    logger.info(
        "AI 反馈入库: hazard=%s outcome=%s level=%s score=%.2f by=%s",
        record["hazard_code"], record["outcome"], record["predicted_level"],
        record["predicted_score"], record["feedback_by"],
    )
    return {
        "code": 0,
        "message": "ok",
        "data": {
            "id": record["id"],
            "total_records": store.stats()["total_records"],
        },
    }


@router.get("/feedback/list")
async def list_feedback(limit: int = 50) -> Dict[str, Any]:
    """列出最近 N 条反馈。"""
    store = get_feedback_store()
    items = store.list_recent(limit)
    return {"code": 0, "message": "ok", "data": items}


@router.get("/feedback/stats")
async def feedback_stats() -> Dict[str, Any]:
    """获取反馈统计（混淆矩阵 + precision/recall/F1 + 总记录数）。"""
    store = get_feedback_store()
    stats = store.stats()
    # 加上 adaptive_normalizer 的可观测性快照
    from app.models.adaptive_normalizer import get_adaptive_normalizer
    normalizer_stats = get_adaptive_normalizer().all_stats()
    return {
        "code": 0,
        "message": "ok",
        "data": {
            **stats,
            "adaptive_normalizer": normalizer_stats,
        },
    }


@router.post("/model/retrain")
async def trigger_retrain(
    body: Optional[RetrainRequest] = None,
    background: BackgroundTasks = None,  # type: ignore[assignment]
) -> Dict[str, Any]:
    """
    触发 train_xgboost.py 重训（异步后台执行）。

    返回立即响应 {"status": "queued" / "started" / "skipped"}，
    训练结果通过 stdout 日志查看，后续可加 WebSocket 推送。
    """
    req = body or RetrainRequest()
    store = get_feedback_store()
    sample_count = store.stats()["total_records"]
    if sample_count < req.min_samples and not req.force:
        return {
            "code": 0,
            "message": "skipped",
            "data": {
                "reason": "样本不足",
                "current_samples": sample_count,
                "required_samples": req.min_samples,
            },
        }

    def _run_train() -> None:
        logger.info("开始后台训练 xgboost_fusion，当前样本数=%d", sample_count)
        try:
            # 调用同 repo 的 train_xgboost.py 脚本
            script = Path(__file__).resolve().parent.parent.parent / "app" / "training" / "train_xgboost.py"
            if not script.exists():
                logger.warning("训练脚本 %s 不存在，跳过", script)
                return
            proc = subprocess.run(
                [sys.executable, str(script)],
                capture_output=True,
                text=True,
                timeout=600,
            )
            if proc.returncode == 0:
                logger.info("训练成功: %s", proc.stdout[-500:])
            else:
                logger.error("训练失败 (rc=%d): %s", proc.returncode, proc.stderr[-500:])
        except Exception as e:  # noqa: BLE001
            logger.exception("训练异常: %s", e)

    if background is not None:
        background.add_task(_run_train)
        return {"code": 0, "message": "queued", "data": {"samples": sample_count}}
    else:
        # 没有 BackgroundTasks（直接调用），同步跑
        _run_train()
        return {"code": 0, "message": "started", "data": {"samples": sample_count}}