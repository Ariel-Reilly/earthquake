"""
多模态融合 API 路由
"""

from typing import Any, Dict, List, Optional

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from app.models.fusion_engine import FusionEngine, ModalScore, get_fusion_engine

# R3：同 drift.py —— 此前从未在 main.py 注册，且前缀 /multimodal 与项目
# 约定的 /api/ai 不符。改为 /api/ai/multimodal 并注册，端点才可达。
router = APIRouter(prefix="/api/ai/multimodal", tags=["multimodal"])


class FuseRequest(BaseModel):
    """融合请求"""
    eventId: str
    modals: List[str]  # 启用的模态列表
    location: Optional[Dict[str, float]] = None  # {lng, lat}
    timeRange: Optional[str] = "24h"
    # 各模态评分数据
    satelliteData: Optional[Dict[str, Any]] = None
    forecastData: Optional[Dict[str, Any]] = None
    socialMediaData: Optional[Dict[str, Any]] = None
    deviceData: Optional[Dict[str, Any]] = None


class FuseResponse(BaseModel):
    """融合响应"""
    eventId: str
    fusedScore: float
    level: str
    levelColor: str
    confidence: float
    contributions: Dict[str, float]
    explanation: str
    modalDetails: Dict[str, Any]
    createdAt: str


class InsightRequest(BaseModel):
    """研判请求"""
    eventId: str
    refresh: bool = False



@router.post("/fuse", response_model=FuseResponse)
async def fuse_modals(request: FuseRequest) -> FuseResponse:
    """
    多模态融合

    将卫星影像、时序预测、社交媒体舆情、设备数据等多个模态融合，
    输出综合风险评分和各模态贡献度。
    """
    engine = get_fusion_engine()

    modal_scores: Dict[str, ModalScore] = {}

    # 处理卫星影像
    if "satellite" in request.modals and request.satelliteData:
        data = request.satelliteData
        modal_scores["satellite"] = ModalScore(
            modal="satellite",
            score=data.get("score", 0),
            confidence=data.get("confidence", 0.5),
            details=data
        )

    # 处理时序预测
    if "forecast" in request.modals and request.forecastData:
        data = request.forecastData
        modal_scores["forecast"] = ModalScore(
            modal="forecast",
            score=data.get("score", 0),
            confidence=data.get("confidence", 0.5),
            details=data
        )

    # 处理社交媒体
    if "social_media" in request.modals and request.socialMediaData:
        data = request.socialMediaData
        modal_scores["social_media"] = ModalScore(
            modal="social_media",
            score=data.get("score", 0),
            confidence=data.get("confidence", 0.5),
            details=data
        )

    # 处理设备数据
    if "device" in request.modals and request.deviceData:
        data = request.deviceData
        modal_scores["device"] = ModalScore(
            modal="device",
            score=data.get("score", 0),
            confidence=data.get("confidence", 0.5),
            details=data
        )

    if not modal_scores:
        raise HTTPException(status_code=400, detail="未提供有效的模态数据")

    result = engine.fuse(modal_scores, request.eventId)

    return FuseResponse(
        eventId=result.event_id,
        fusedScore=result.fused_score,
        level=result.level,
        levelColor=engine._score_to_color(result.level),
        confidence=result.confidence,
        contributions=result.contributions,
        explanation=result.explanation,
        modalDetails=result.modal_details,
        createdAt=result.created_at
    )


@router.get("/insight/{event_id}")
async def get_insight(event_id: str) -> Dict[str, Any]:
    """
    获取融合研判

    从数据库获取已有的融合结果。
    """
    # TODO: 从数据库加载融合记录
    # 这里返回模拟数据

    return {
        "eventId": event_id,
        "fusedScore": 87.5,
        "level": "HIGH",
        "levelColor": "#e0a050",
        "confidence": 0.82,
        "contributions": {
            "satellite": 0.55,
            "forecast": 0.25,
            "social_media": 0.15,
            "device": 0.05
        },
        "explanation": "综合风险评分为 87.5 分，卫星影像贡献最大（55%），影像显示青龙坡区域出现明显地表形变，同时时序预测也提供了 25% 的贡献。建议发布橙色预警。",
        "modalDetails": {
            "satellite": {
                "score": 85,
                "desc": "检测到 3 处异常，Sentinel-2 显示显著形变",
                "confidence": 0.92
            },
            "forecast": {
                "score": 72,
                "desc": "预测位移加速趋势（0.85 mm/d²）",
                "confidence": 0.78
            },
            "social_media": {
                "score": 65,
                "desc": "舆情扩散（52 条相关帖文），情感偏负面",
                "confidence": 0.65
            },
            "device": {
                "score": 60,
                "desc": "位移速率偏快（6.2 mm/d）",
                "confidence": 0.88
            }
        },
        "createdAt": "2026-09-13T10:30:00Z"
    }


@router.get("/contributions/{event_id}")
async def get_contributions(event_id: str) -> Dict[str, Any]:
    """
    获取贡献度详情
    """
    # TODO: 从数据库加载
    return {
        "eventId": event_id,
        "contributions": {
            "satellite": {
                "weight": 0.40,
                "contribution": 0.55,
                "score": 85
            },
            "forecast": {
                "weight": 0.30,
                "contribution": 0.25,
                "score": 72
            },
            "social_media": {
                "weight": 0.15,
                "contribution": 0.15,
                "score": 65
            },
            "device": {
                "weight": 0.15,
                "contribution": 0.05,
                "score": 60
            }
        }
    }


@router.post("/refresh")
async def refresh_insight(request: InsightRequest) -> Dict[str, Any]:
    """
    手动刷新融合研判

    重新获取各模态数据并融合。
    """
    # TODO: 实际执行刷新逻辑
    return {
        "eventId": request.eventId,
        "status": "success",
        "message": "融合研判已刷新"
    }



class WeightConfigRequest(BaseModel):
    """权重配置请求"""
    disasterType: str
    weights: Dict[str, float]


@router.get("/weights/{disaster_type}")
async def get_weights(disaster_type: str) -> Dict[str, Any]:
    """
    获取权重配置
    """
    # TODO: 从数据库加载
    return {
        "disasterType": disaster_type,
        "weights": {
            "satellite": 0.40,
            "forecast": 0.30,
            "social_media": 0.15,
            "device": 0.15
        }
    }


@router.put("/weights/{disaster_type}")
async def update_weights(
    disaster_type: str,
    request: WeightConfigRequest
) -> Dict[str, Any]:
    """
    更新权重配置
    """
    # TODO: 保存到数据库
    return {
        "disasterType": disaster_type,
        "weights": request.weights,
        "updatedAt": "2026-09-13T10:30:00Z"
    }
