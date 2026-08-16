"""
时序预测路由：POST /api/ai/forecast

输入历史序列，预测未来 N 步。LSTM 模型缺失时降级为移动平均启发式。
"""
from __future__ import annotations

from typing import List

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field, field_validator

from app.models.lstm_model import get_forecaster

router = APIRouter(prefix="/api/ai", tags=["forecast"])


class ForecastRequest(BaseModel):
    device_code: str = Field(min_length=1, description="设备编码")
    metric: str = Field(default="displacement", description="指标：displacement/rainfall/...")
    horizon: int = Field(default=7, ge=1, le=30, description="预测步数")
    history: List[float] = Field(min_length=2, description="历史观测序列，建议 >= 30 效果最佳")

    @field_validator("history")
    @classmethod
    def check_history_length(cls, v: List[float]) -> List[float]:
        if len(v) < 2:
            raise ValueError("history 至少需要 2 个数据点")
        if len(v) > 1000:
            raise ValueError("history 长度不能超过 1000")
        return v


class ForecastResponse(BaseModel):
    device_code: str
    metric: str
    horizon: int
    predictions: List[float]
    confidence: float = Field(ge=0, le=1)
    model: str
    mock: bool = False
    message: str = "ok"


@router.post("/forecast", response_model=ForecastResponse)
async def forecast(req: ForecastRequest):
    """对历史序列做未来 horizon 步预测。"""
    result = get_forecaster().predict(req.history, req.horizon)
    return ForecastResponse(
        device_code=req.device_code,
        metric=req.metric,
        horizon=req.horizon,
        predictions=result["predictions"],
        confidence=result["confidence"],
        model=result["model"],
        mock=result.get("mock", False),
    )


@router.get("/forecast/status")
async def forecast_status():
    fc = get_forecaster()
    return {
        "model_loaded": fc.is_loaded(),
        "model_path": str(fc.model_path),
        "input_size": fc.input_size,
    }
