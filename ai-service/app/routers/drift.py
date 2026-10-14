"""
漂移监控 API 路由
"""

from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from app.models.drift_monitor import DriftMonitor, get_drift_monitor

# R3：本模块此前从未在 main.py 注册，且前缀为 /drift，
# 与项目约定的 /api/ai 不符（后端 AiController 按 /api/ai/drift 调用）。
# 改为 /api/ai/drift 并在 main.py 注册，端点才可达。
# 注意：本模块的 /detect 是 /api/ai/drift/detect，
# 与 detection.py 的 /api/ai/detect 不冲突。
router = APIRouter(prefix="/api/ai/drift", tags=["drift"])


class DetectRequest(BaseModel):
    """漂移检测请求"""
    modelId: str
    currentData: List[Dict[str, float]]


class DetectResponse(BaseModel):
    """漂移检测响应"""
    modelId: str
    overallStatus: str
    features: Dict[str, Any]
    recommendation: str
    detectedAt: str


class RetrainRequest(BaseModel):
    """重训请求"""
    modelId: str
    reason: str
    dataset: Optional[str] = None


class RetrainResponse(BaseModel):
    """重训响应"""
    jobId: str
    status: str
    message: str


class BaselineRequest(BaseModel):
    """基线数据请求"""
    modelId: str
    data: Dict[str, List[float]]
    description: Optional[str] = ""



@router.post("/detect", response_model=DetectResponse)
# R3 P1-15：改为同步 def，FastAPI 自动走线程池（同上）
def detect_drift(request: DetectRequest) -> DetectResponse:
    """
    检测数据漂移

    对输入数据与基线数据进行比较，计算 PSI 和 KS 指标。
    """
    monitor = get_drift_monitor(request.modelId)

    # 转换数据格式
    current_data: Dict[str, List[float]] = {}
    if request.currentData:
        # 假设 currentData 是列表，每项包含多个特征
        for item in request.currentData:
            for key, value in item.items():
                if key not in current_data:
                    current_data[key] = []
                current_data[key].append(float(value))

    result = monitor.detect(current_data)

    return DetectResponse(
        modelId=result.model_id,
        overallStatus=result.overall_status,
        features={
            k: {
                "psi": v.psi,
                "ks": v.ks_stat,
                "p_value": v.p_value,
                "level": v.drift_level,
                "message": v.message
            }
            for k, v in result.features.items()
        },
        recommendation=result.recommendation,
        detectedAt=result.detected_at
    )


@router.get("/trend/{model_id}")
async def get_drift_trend(
    model_id: str,
    feature: Optional[str] = None,
    days: int = 30
) -> Dict[str, Any]:
    """
    获取漂移趋势

    Args:
        model_id: 模型 ID
        feature: 可选，指定特征
        days: 趋势天数
    """
    # TODO: 从数据库加载历史漂移记录
    # 这里返回模拟数据
    return {
        "modelId": model_id,
        "feature": feature,
        "days": days,
        "trend": [
            {
                "timestamp": (datetime.now() - timedelta(days=i)).isoformat(),
                "psi": 0.05 + (i % 5) * 0.02 + 0.01 * (hash(str(i)) % 3)
            }
            for i in range(days, 0, -1)
        ]
    }


@router.get("/features/{model_id}")
async def get_feature_drift_detail(
    model_id: str,
    feature: str
) -> Dict[str, Any]:
    """
    获取特征漂移详情

    Args:
        model_id: 模型 ID
        feature: 特征名
    """
    monitor = get_drift_monitor(model_id)

    # TODO: 返回特征的详细分布信息
    # 这里返回模拟数据
    return {
        "modelId": model_id,
        "feature": feature,
        "baselineStats": {
            "mean": 45.2,
            "std": 12.5,
            "min": 10.0,
            "max": 80.0,
            "count": 5000
        },
        "currentStats": {
            "mean": 52.8,
            "std": 14.2,
            "min": 15.0,
            "max": 90.0,
            "count": 500
        },
        "psi": 0.18,
        "ks": 0.12,
        "driftLevel": "SLIGHT"
    }



@router.get("/baseline/{model_id}")
async def get_baseline(model_id: str) -> Dict[str, Any]:
    """
    获取基线数据

    Args:
        model_id: 模型 ID
    """
    monitor = get_drift_monitor(model_id)

    return {
        "modelId": model_id,
        "features": list(monitor.baseline_data.keys()),
        "sampleCounts": {
            k: len(v) for k, v in monitor.baseline_data.items()
        }
    }


@router.post("/baseline")
async def update_baseline(request: BaselineRequest) -> Dict[str, Any]:
    """
    更新基线数据

    Args:
        request: 基线数据
    """
    monitor = get_drift_monitor(request.modelId)
    monitor.update_baseline(request.data, request.description or "")

    return {
        "modelId": request.modelId,
        "status": "success",
        "message": "基线数据已更新"
    }



@router.post("/retrain", response_model=RetrainResponse)
async def trigger_retrain(request: RetrainRequest) -> RetrainResponse:
    """
    触发模型重训

    Args:
        request: 重训请求
    """
    import uuid

    # TODO: 实际触发重训任务
    # 这里返回模拟结果
    job_id = f"retrain-{uuid.uuid4().hex[:8]}"

    return RetrainResponse(
        jobId=job_id,
        status="STARTED",
        message=f"重训任务已创建，模型: {request.modelId}，原因: {request.reason}"
    )


@router.get("/retrain/{job_id}")
async def get_retrain_status(job_id: str) -> Dict[str, Any]:
    """
    获取重训状态

    Args:
        job_id: 重训任务 ID
    """
    # TODO: 从任务队列获取状态
    # 这里返回模拟数据
    return {
        "jobId": job_id,
        "status": "RUNNING",
        "progress": 45,
        "startedAt": datetime.now().isoformat(),
        "estimatedFinishAt": (datetime.now() + timedelta(minutes=30)).isoformat(),
        "logs": [
            "正在加载训练数据...",
            "数据预处理完成，5000 条样本",
            "开始训练 XGBoost 模型...",
            "Epoch 10/100, Loss: 0.234"
        ]
    }
