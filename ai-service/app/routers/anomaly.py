"""
异常检测路由：POST /api/ai/anomaly

支持两种输入格式：
  1. 一维时间序列（List[float]）：对单变量做异常点检测（3σ 启发式）
  2. 多维特征矩阵（List[List[float]]）：对多传感器（5 维：位移/裂缝/倾斜/雨量/振动）
     做 Isolation Forest 多维异常检测

Isolation Forest 模型缺失时降级为 3-sigma 检测。
"""
from __future__ import annotations

from typing import List, Optional, Union

from fastapi import APIRouter
from pydantic import BaseModel, Field, field_validator

from app.models.isolation_forest import get_detector

router = APIRouter(prefix="/api/ai", tags=["anomaly"])


class AnomalyRequest(BaseModel):
    device_code: str = Field(min_length=1, description="设备编码")
    data: List[List[float]] = Field(
        min_length=3,
        description=(
            "传感器样本矩阵，每行一个样本；每行应是 5 维向量："
            "[displacement_rate, crack_width, tilt_angle, rainfall, vibration_freq]。"
            "兼容旧格式：当数据全为标量时，自动按 5 维重复填充。"
        ),
    )

    @field_validator("data")
    @classmethod
    def check_data_shape(cls, v: List[List[float]]) -> List[List[float]]:
        if len(v) < 3:
            raise ValueError("data 至少需要 3 个样本")
        # 检查每行的维度一致性
        if isinstance(v[0], list):
            dim = len(v[0])
            for i, row in enumerate(v):
                if len(row) != dim:
                    raise ValueError(
                        f"第 {i} 行维度 ({len(row)}) 与第一行 ({dim}) 不一致"
                    )
        return v


class AnomalyResponse(BaseModel):
    device_code: str
    is_anomaly: bool
    anomaly_indices: List[int]
    score: float
    model: str
    mock: bool = False
    feature_names: Optional[List[str]] = None
    message: str = "ok"


@router.post("/anomaly", response_model=AnomalyResponse)
def anomaly(req: AnomalyRequest):
    """对设备的多维传感器数据做异常检测。"""
    det = get_detector()
    result = det.detect(req.data)
    return AnomalyResponse(
        device_code=req.device_code,
        is_anomaly=result["is_anomaly"],
        anomaly_indices=result["anomaly_indices"],
        score=result["score"],
        model=result["model"],
        mock=result.get("mock", False),
        feature_names=det.FEATURE_NAMES if req.data and isinstance(req.data[0], list) else None,
    )


class BatchAnomalyItem(BaseModel):
    device_code: str = Field(min_length=1)
    data: List[List[float]] = Field(min_length=3)


class BatchAnomalyRequest(BaseModel):
    items: List[BatchAnomalyItem] = Field(min_length=1, max_length=100)


class BatchAnomalyResponse(BaseModel):
    results: List[AnomalyResponse]
    total: int
    anomalies_found: int
    message: str = "ok"


@router.post("/anomaly/batch", response_model=BatchAnomalyResponse)
def anomaly_batch(req: BatchAnomalyRequest):
    """批量检测多台设备异常（最高 100 台/批）。"""
    det = get_detector()
    results: List[AnomalyResponse] = []
    anomalies_found = 0
    for item in req.items:
        r = det.detect(item.data)
        if r["is_anomaly"]:
            anomalies_found += 1
        results.append(AnomalyResponse(
            device_code=item.device_code,
            is_anomaly=r["is_anomaly"],
            anomaly_indices=r["anomaly_indices"],
            score=r["score"],
            model=r["model"],
            mock=r.get("mock", False),
            feature_names=det.FEATURE_NAMES if item.data and isinstance(item.data[0], list) else None,
        ))
    return BatchAnomalyResponse(
        results=results,
        total=len(results),
        anomalies_found=anomalies_found,
    )


@router.get("/anomaly/status")
async def anomaly_status():
    det = get_detector()
    return {
        "model_loaded": det.is_loaded(),
        "model_path": str(det.model_path),
        "feature_names": det.FEATURE_NAMES,
        "feature_descriptions": {
            "displacement_rate": "位移速率（mm/day）",
            "crack_width": "裂缝宽度（mm）",
            "tilt_angle": "倾斜角（°）",
            "rainfall": "雨量（mm）",
            "vibration_freq": "振动频率（Hz）",
        },
    }
