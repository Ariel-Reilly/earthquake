"""
图像识别路由：POST /api/ai/detect

接收 multipart 图片上传，调用 YOLO 检测裂缝/落石。
模型缺失时自动降级为 mock。
"""
from __future__ import annotations

import asyncio
import os
import tempfile
import uuid
from concurrent.futures import ThreadPoolExecutor
from typing import List, Optional

from fastapi import APIRouter, File, Form, HTTPException, UploadFile
from pydantic import BaseModel, Field

from app.models.yolo_model import get_detector

router = APIRouter(prefix="/api/ai", tags=["detection"])

# R3 P1-15：推理（cv2 解码 + ONNX 前向）是 CPU 密集型同步调用。
# 此前在 async def 里直接调用，会阻塞整个事件循环——单张大图即可冻结
# 全部并发请求（其他路由、健康检查全部无响应）。
# 这里放到独立线程池执行，并加超时保护，避免单次推理拖死整个服务。
_DETECT_EXECUTOR = ThreadPoolExecutor(max_workers=2, thread_name_prefix="detect")
DETECT_TIMEOUT_SECONDS = float(os.getenv("DETECT_TIMEOUT_SECONDS", "30"))


def _run_detection(image_bytes: bytes):
    """在线程池中执行：get_detector() 首次调用会加载模型，同样耗时，故整体放入。"""
    return get_detector().detect(image_bytes)


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

    # R3 P1-15：放进线程池 + 超时保护，不再阻塞事件循环
    #
    # ⚠️ R5 P2-3：asyncio.wait_for 语义说明 —— 超时仅取消外层 await，
    # 不会取消已提交给 _DETECT_EXECUTOR 的任务。极端情况下（推理线程挂起、
    # 大图、CPU 竞争），超时后实际推理线程仍会继续占满 ThreadPoolExecutor
    # 直至自然结束，期间所有新请求会全部排队（max_workers=2 即最多堆积
    # 2 个；后续请求才会触发 504 拒绝）。这是 Python 生态下的已知取舍，
    # 真正"取消"需要把推理也拆成可中断的协程或接 C 端 cancel token，本期
    # 暂不引入。运维侧缓解：监控 _DETECT_EXECUTOR 队列长度 + 减小单图尺寸上限。
    loop = asyncio.get_running_loop()
    try:
        result = await asyncio.wait_for(
            loop.run_in_executor(_DETECT_EXECUTOR, _run_detection, image_bytes),
            timeout=DETECT_TIMEOUT_SECONDS,
        )
    except asyncio.TimeoutError:
        raise HTTPException(
            504, f"检测超时（超过 {DETECT_TIMEOUT_SECONDS:.0f}s），请缩小图片或稍后重试"
        )

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
