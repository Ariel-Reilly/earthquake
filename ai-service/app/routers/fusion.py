"""
融合预警路由：POST /api/ai/fusion-risk

输入多因子字典，返回综合风险评分和等级。XGBoost 模型缺失时降级为加权融合。
"""
from __future__ import annotations

from typing import Dict

from fastapi import APIRouter
from pydantic import BaseModel, Field

from app.models.xgboost_model import get_fusion_model, DEFAULT_WEIGHTS

router = APIRouter(prefix="/api/ai", tags=["fusion"])


class FusionRequest(BaseModel):
    hazard_code: str = Field(min_length=1, description="灾害点编码")
    factors: Dict[str, float] = Field(
        description="风险因子字典：displacement_rate/displacement_total/rainfall_24h/rainfall_7d/crack_width/earthquake_mag/site_grade"
    )


class FusionResponse(BaseModel):
    hazard_code: str
    risk_level: str = Field(description="LOW / MEDIUM / HIGH / CRITICAL")
    risk_score: float = Field(ge=0, le=100)
    confidence: float = Field(ge=0, le=1)
    model: str
    mock: bool = False
    message: str = "ok"


@router.post("/fusion-risk", response_model=FusionResponse)
async def fusion_risk(req: FusionRequest):
    result = get_fusion_model().score(req.factors)
    return FusionResponse(
        hazard_code=req.hazard_code,
        risk_level=result["risk_level"],
        risk_score=result["risk_score"],
        confidence=result["confidence"],
        model=result["model"],
        mock=result.get("mock", False),
    )


@router.get("/fusion/status")
async def fusion_status():
    fm = get_fusion_model()
    return {
        "model_loaded": fm.is_loaded(),
        "model_path": str(fm.model_path),
        "default_weights": DEFAULT_WEIGHTS,
    }
