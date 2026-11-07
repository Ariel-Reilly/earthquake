"""
异常检测路由：POST /api/ai/anomaly

支持两种输入格式：
  1. 一维时间序列（List[float]）：先按 5 维重复填充成样本矩阵，再送检测器；
     用于兼容 Java 侧 AiServiceClient#anomaly 传的单指标历史序列。
  2. 多维特征矩阵（List[List[float]]）：对多传感器（5 维：位移/裂缝/倾斜/雨量/振动）
     做 Isolation Forest 多维异常检测。

一维归一化必须放在 Pydantic 类型校验之前（mode="before"），否则
`data: List[List[float]]` 会在校验阶段直接把 List[float] 打成 422。

Isolation Forest 模型缺失时降级为 3-sigma 检测。
"""
from __future__ import annotations

from typing import List, Optional, Union

from fastapi import APIRouter
from pydantic import BaseModel, Field, field_validator, model_validator

from app.models.isolation_forest import get_detector

router = APIRouter(prefix="/api/ai", tags=["anomaly"])


# 单条 /anomaly 与批量 /anomaly/batch 共用同一套输入归一化，避免单条修好、批量仍 422。
def _flag_one_dimensional_input(values):
    """由输入形态自动判定一维输入，不接受客户端伪造 input_was_1d。"""
    if isinstance(values, dict):
        raw = values.get("data")
        is_1d = isinstance(raw, list) and bool(raw) and all(
            isinstance(x, (int, float)) and not isinstance(x, bool) for x in raw
        )
        return {**values, "input_was_1d": is_1d}
    return values


def _normalize_data_shape(v):
    """把一维标量序列归一化成 5 维样本矩阵，兼容 Java 侧旧契约。

    只做几何补齐，不生成新的业务数值：每个标量在 5 个特征位上重复，
    IsolationForest 缺少真实多维输入时由路由响应的 mock 字段如实反映。
    """
    if not isinstance(v, list) or not v:
        return v
    out = []
    for item in v:
        if isinstance(item, bool):
            return v
        if isinstance(item, (int, float)):
            out.append([float(item)] * 5)
        elif isinstance(item, (list, tuple)):
            try:
                out.append([float(x) for x in item])
            except (TypeError, ValueError):
                return v
        else:
            return v  # 交给 Pydantic 报类型错误，别静默吞掉
    return out


def _check_data_shape(v: List[List[float]]) -> List[List[float]]:
    if len(v) < 3:
        raise ValueError("data 至少需要 3 个样本")
    if isinstance(v[0], list):
        dim = len(v[0])
        if dim != 5:
            raise ValueError(
                f"每行应为 5 维特征向量，实际为 {dim} 维；"
                "一维标量序列会自动补齐成 5 维（结果标为 mock）"
            )
        for i, row in enumerate(v):
            if len(row) != dim:
                raise ValueError(f"第 {i} 行维度 ({len(row)}) 与第一行 ({dim}) 不一致")
    return v


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
    # 由输入形态自动判定，不接受客户端伪造；用于把「一维补齐成共线特征」如实标成 mock。
    input_was_1d: bool = Field(default=False, exclude=True)

    @model_validator(mode="before")
    @classmethod
    def flag_one_dimensional_input(cls, values):
        return _flag_one_dimensional_input(values)

    @field_validator("data", mode="before")
    @classmethod
    def normalize_data_shape(cls, v):
        return _normalize_data_shape(v)

    @field_validator("data")
    @classmethod
    def check_data_shape(cls, v: List[List[float]]) -> List[List[float]]:
        return _check_data_shape(v)


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
    synthetic = bool(req.input_was_1d)
    return AnomalyResponse(
        device_code=req.device_code,
        is_anomaly=result["is_anomaly"],
        anomaly_indices=result["anomaly_indices"],
        score=result["score"],
        model=result["model"],
        # 一维标量被复制成 5 个完全共线的特征，即便模型权重存在也不是真实多维检测；
        # 不能因为 detector 加载成功就报 mock=False。
        mock=bool(result.get("mock", False)) or synthetic,
        message=(
            "一维标量输入已按 5 维共线特征补齐，结果仅为兼容旧契约的启发式判定，"
            "不代表真实多维异常检测"
        ) if synthetic else "ok",
        feature_names=det.FEATURE_NAMES if req.data and isinstance(req.data[0], list) else None,
    )


class BatchAnomalyItem(BaseModel):
    device_code: str = Field(min_length=1)
    data: List[List[float]] = Field(min_length=3)
    input_was_1d: bool = Field(default=False, exclude=True)

    @model_validator(mode="before")
    @classmethod
    def flag_one_dimensional_input(cls, values):
        return _flag_one_dimensional_input(values)

    @field_validator("data", mode="before")
    @classmethod
    def normalize_data_shape(cls, v):
        return _normalize_data_shape(v)

    @field_validator("data")
    @classmethod
    def check_data_shape(cls, v: List[List[float]]) -> List[List[float]]:
        return _check_data_shape(v)


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
        synthetic = bool(item.input_was_1d)
        results.append(AnomalyResponse(
            device_code=item.device_code,
            is_anomaly=r["is_anomaly"],
            anomaly_indices=r["anomaly_indices"],
            score=r["score"],
            model=r["model"],
            # 与单条接口一致：一维补齐成 5 个共线特征不能报成真实多维检测。
            mock=bool(r.get("mock", False)) or synthetic,
            message=(
                "一维标量输入已按 5 维共线特征补齐，结果仅为兼容旧契约的启发式判定"
            ) if synthetic else "ok",
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
