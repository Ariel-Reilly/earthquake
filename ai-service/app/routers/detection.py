"""
图像识别路由：POST /api/ai/detect

接收 multipart 图片上传，调用 YOLO 检测裂缝/落石。
模型缺失时自动降级为 mock。
"""
from __future__ import annotations

import os
import tempfile
import uuid
from typing import List, Optional

from fastapi import APIRouter, File, Form, HTTPException, UploadFile
from pydantic import BaseModel, Field

from app.models.yolo_model import get_detector

router = APIRouter(prefix="/api/ai", tags=["detection"])


class DetectionItem(BaseModel):
    class_name: str
    confidence: float = Field(ge=0, le=1)
    bbox: List[float] = Field(min_length=4, max_length=4, description="[x1, y1, x2, y2] 像素坐标")


class DetectResponse(BaseModel):
    detections: List[DetectionItem]
    processed_image_url: Optional[str] = None
    model: str
    mock: bool = False
    detection_count: int
    message: str = "ok"


@router.post("/detect", response_model=DetectResponse)
async def detect(
    file: UploadFile = File(...),
    model_type: str = Form("yolo", description="模型类型，预留扩展"),
):
    """
    对上传的图片做目标检测。

    图片通过 multipart 上传，后端返回检测框列表。
    若 models/yolo_crack.onnx 不存在，自动降级为启发式 mock（返回随机 0-2 个检测框）。
    """
    if file.size and file.size > 10 * 1024 * 1024:
        raise HTTPException(400, "图片大小不能超过 10MB")

    try:
        image_bytes = await file.read()
    except Exception as e:
        raise HTTPException(400, f"读取图片失败: {e}")

    result = get_detector().detect(image_bytes)

    detections = [
        DetectionItem(
            class_name=d["class_name"],
            confidence=d["confidence"],
            bbox=d["bbox"],
        )
        for d in result["detections"]
    ]

    return DetectResponse(
        detections=detections,
        processed_image_url=None,  # 真实服务可输出叠加后的图片 URL
        model=result["model"],
        mock=result.get("mock", False),
        detection_count=len(detections),
        message="ok",
    )


@router.get("/detect/status")
async def detect_status():
    """返回检测器状态（是否加载了真实模型）。"""
    det = get_detector()
    return {
        "model_loaded": det.is_loaded(),
        "model_path": str(det.model_path),
        "input_size": det.input_size,
    }
