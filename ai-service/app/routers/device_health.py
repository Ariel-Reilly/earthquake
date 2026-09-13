"""
设备健康评分路由：POST /api/ai/device-health/score

承接原 Java AiDeviceHealthService.analyzeOne 中的"AI 算法"部分：
  - 多指标异常检测（隔离森林 / 3σ fallback）
  - 位移序列时序预测（LSTM / 移动平均 fallback）
  - 加权融合 → health_score + 分级
  - 推理链 + 可执行建议生成

Java 端只负责：
  - 从 device_metric 拉取时序数据
  - 汇总设备在线状态 / 上报时间
  - 调本端点，把返回值 UPSERT 到 ai_device_health 表

请求字段与 Java DeviceHealthRequest DTO 对齐（snake_case → camelCase 通过 Java @JsonAlias）。
"""
from __future__ import annotations

from typing import Dict, List, Optional

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from app.models.device_health_model import get_device_health_scorer

router = APIRouter(prefix="/api/ai", tags=["device-health"])


class DeviceHealthRequest(BaseModel):
    device_code: str = Field(min_length=1, description="设备编码")
    metrics: Dict[str, List[float]] = Field(
        default_factory=dict,
        description=(
            "各指标时序，键取值：disp_rate / disp_total / crack_width / tilt；"
            "每段时序按 ASC（最新值在末尾），长度建议 >= 5"
        ),
    )
    online_status: Optional[str] = Field(
        default=None, description='设备在线状态字符串（如 "在线" / "离线"）'
    )
    last_report_at: Optional[str] = Field(
        default=None, description="最近一次上报时间（ISO 8601 字符串）"
    )


class DeviceHealthResponse(BaseModel):
    device_code: str
    health_score: float
    health_level: str
    anomaly_score: float
    forecast_risk: float
    metric_coverage: int
    anomaly_by_metric: Dict[str, float]
    reasoning: List[str]
    suggested_actions: List[str]
    confidence: float
    model: str
    degraded: bool
    analyzed_at: str
    last_anomaly_at: Optional[str] = None
    message: str = "ok"


@router.post("/device-health/score", response_model=DeviceHealthResponse)
async def device_health_score(req: DeviceHealthRequest):
    """对单台设备做 AI 健康评分。"""
    if not req.metrics:
        raise HTTPException(
            status_code=400,
            detail=(
                "metrics 不能为空；至少需要 disp_rate / disp_total / crack_width / tilt "
                "其中一个指标提供时序数据"
            ),
        )
    scorer = get_device_health_scorer()
    result = scorer.score(
        device_code=req.device_code,
        metrics=req.metrics,
        online_status=req.online_status,
        last_report_at=req.last_report_at,
    )
    return DeviceHealthResponse(
        device_code=result["device_code"],
        health_score=result["health_score"],
        health_level=result["health_level"],
        anomaly_score=result["anomaly_score"],
        forecast_risk=result["forecast_risk"],
        metric_coverage=result["metric_coverage"],
        anomaly_by_metric=result["anomaly_by_metric"],
        reasoning=result["reasoning"],
        suggested_actions=result["suggested_actions"],
        confidence=result["confidence"],
        model=result["model"],
        degraded=result["degraded"],
        analyzed_at=result["analyzed_at"],
        last_anomaly_at=result["last_anomaly_at"],
    )


@router.get("/device-health/status")
async def device_health_status():
    """暴露评分器状态，方便 Java 端 / 运维探测。"""
    scorer = get_device_health_scorer()
    return {
        "model_loaded": scorer._anomaly.is_loaded(),
        "anomaly_model_path": str(scorer._anomaly.model_path),
        "forecast_model_loaded": scorer._forecaster.is_loaded(),
        "forecast_model_path": str(scorer._forecaster.model_path),
        "weights": {
            "anomaly": scorer.WEIGHT_ANOMALY,
            "forecast": scorer.WEIGHT_FORECAST,
            "offline_penalty_mild": scorer.OFFLINE_PENALTY_MILD,
            "offline_penalty_severe": scorer.OFFLINE_PENALTY_SEVERE,
            "coverage_penalty": scorer.COVERAGE_PENALTY,
        },
    }
