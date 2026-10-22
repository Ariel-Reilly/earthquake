"""
综合风险分析路由：POST /api/ai/comprehensive

接收图像/降水/地理/设备四维因子，返回综合分析报告：
  - 总体 risk_score / risk_level / confidence / model
  - 四维度各自 sub_score + details（图像/降水/地理/设备）
  - reasoning 数组：可解释的风险归因
  - suggested_actions 数组：可执行建议

这是 fusion-risk 的超集，区别在于：
  - fusion-risk 只输出单分数；comprehensive 输出"多维度明细 + 推理 + 建议"
  - fusion-risk 因子以"设备位移/裂缝"为主；comprehensive 把"图像/降水/地理"显式建模为独立维度
"""
from __future__ import annotations

from typing import Dict, Optional

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from app.models.comprehensive_model import get_comprehensive_model

router = APIRouter(prefix="/api/ai", tags=["comprehensive"])


class ComprehensiveRequest(BaseModel):
    hazard_code: str = Field(min_length=1, description="灾害点编码")
    factors: Dict[str, float] = Field(
        default_factory=dict,
        description=(
            "综合分析因子，支持以下键：\n"
            "  图像维度  image_anomaly_score, image_anomaly_count, image_source\n"
            "  降水维度  rainfall_24h, rainfall_7d\n"
            "  地理维度  elevation_m, slope_deg, soil_grade, vegetation_cover, distance_to_water_m, geology_grade, geology_label\n"
            "  设备维度  displacement_rate, displacement_total, crack_width, earthquake_mag, site_grade"
        ),
    )
    # 可选：上次 inspection 时间（前端在 UI 上展示用，AI 评分时也参考）
    last_inspection_at: Optional[str] = Field(
        default=None, description="最近一次现场巡检时间（ISO 8601）"
    )
    # 可选：区域编码（用于自适应归一化；不同区域独立滑动窗口）
    region_code: Optional[str] = Field(
        default=None, description="区域编码（默认 = hazard_code）"
    )


class ComprehensiveResponse(BaseModel):
    hazard_code: str
    overall: Dict
    dimensions: Dict
    reasoning: list
    suggested_actions: list
    model: str
    mock: bool = False
    message: str = "ok"


@router.post("/comprehensive", response_model=ComprehensiveResponse)
# R3 P1-15：改为同步 def，FastAPI 自动走线程池（避免同步评分阻塞事件循环）
def comprehensive(req: ComprehensiveRequest):
    factors = dict(req.factors or {})
    region_code = req.region_code or req.hazard_code or "_default"
    # last_inspection_at 不直接进评分模型，挂在响应里给前端展示
    if not factors:
        raise HTTPException(
            status_code=400,
            detail=(
                "factors 不能为空；至少需要 image_anomaly_score / rainfall_24h / rainfall_7d"
                " 三类中的一项"
            ),
        )

    model = get_comprehensive_model()
    result = model.score(factors, region_code=region_code)
    return ComprehensiveResponse(
        hazard_code=req.hazard_code,
        overall=result["overall"],
        dimensions=result["dimensions"],
        reasoning=result["reasoning"],
        suggested_actions=result["suggested_actions"],
        model=result["overall"]["model"],
        mock=result["overall"]["mock"],
    )


@router.get("/comprehensive/status")
async def comprehensive_status():
    from app.models.comprehensive_model import DIMENSION_WEIGHTS
    cm = get_comprehensive_model()
    return {
        "model_loaded": cm.is_loaded(),
        "model_path": str(cm.model_path),
        "dimension_weights": DIMENSION_WEIGHTS,
        "feature_engineering_enabled": True,
    }