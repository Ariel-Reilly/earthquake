"""
异常检测路由：POST /api/ai/anomaly

输入时间序列数据，检测异常点。Isolation Forest 模型缺失时降级为 3-sigma 检测。
"""
from __future__ import annotations

from typing import List

from fastapi import APIRouter
from pydantic import BaseModel, Field

from app.models.isolation_forest import get_detector

router = APIRouter(prefix="/api/ai", tags=["anomaly"])


class AnomalyRequest(BaseModel):
    device_code: str = Field(min_length=1, description="设备编码")
    data: List[float] = Field(min_length=3, description="待检测序列，建议 >= 10")


class AnomalyResponse(BaseModel):
    device_code: str
    is_anomaly: bool
    anomaly_indices: List[int]
    score: float
    model: str
    mock: bool = False
    message: str = "ok"


@router.post("/anomaly", response_model=AnomalyResponse)
async def anomaly(req: AnomalyRequest):
    result = get_detector().detect(req.data)
    return AnomalyResponse(
        device_code=req.device_code,
        is_anomaly=result["is_anomaly"],
        anomaly_indices=result["anomaly_indices"],
        score=result["score"],
        model=result["model"],
        mock=result.get("mock", False),
    )


@router.get("/anomaly/status")
async def anomaly_status():
    det = get_detector()
    return {
        "model_loaded": det.is_loaded(),
        "model_path": str(det.model_path),
    }
