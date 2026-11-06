"""
图像识别路由。

两个入口，共用同一套检测内核（YoloDetector）：
  - POST /api/ai/detect        设备直传 multipart 文件（嵌入式相机场景）
  - POST /api/ai/detect-image  按 URL 拉取图片（图像事件链路 / MinIO 预签名地址）

模型缺失时自动降级为 mock。
"""
from __future__ import annotations

import asyncio
import logging
import os
import tempfile
import uuid
from concurrent.futures import ThreadPoolExecutor
from typing import List, Optional
from urllib.parse import urlsplit

import aiohttp
from fastapi import APIRouter, File, Form, HTTPException, UploadFile
from pydantic import BaseModel, Field

from app.models.yolo_model import get_detector
from app.utils.http_client import HttpClientHolder

logger = logging.getLogger("ai.detection")

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


# ---------------------------------------------------------------------------
# POST /api/ai/detect-image：按 URL 识别
#
# 与 /detect 的区别只在"图片来源"：这里由服务端主动下载，供图像事件链路
# （Java 侧把 MinIO 预签名地址丢过来）使用，避免调用方为了传图再中转一遍字节流。
# ---------------------------------------------------------------------------

# 单张图片上限：与 /detect 的 multipart 限制保持一致，避免两个入口口径不同
MAX_IMAGE_BYTES = 10 * 1024 * 1024

# 下载超时（秒）。与推理超时分开：慢响应应尽快失败，
# 否则会把只有 2 个线程的推理池连同下载一起拖住。
DOWNLOAD_TIMEOUT_SECONDS = float(os.getenv("DETECT_IMAGE_TIMEOUT_SECONDS", "10"))

# SSRF 白名单：默认只放行本机与容器内常见服务名。
#
# 为什么用主机白名单而不是"屏蔽私网段"：本服务的图片来源本来就是内网 MinIO
# （backend 配的是 http://localhost:19000），按私网段拦截会把正常业务一起挡掉。
# 白名单是这里唯一能同时满足"可用"与"不可被当作内网探测跳板"的约束。
# 部署到别的环境时用 DETECT_IMAGE_ALLOWED_HOSTS 覆盖（逗号分隔，可带端口）。
_ALLOWED_HOSTS = {
    h.strip().lower()
    for h in os.getenv(
        "DETECT_IMAGE_ALLOWED_HOSTS",
        "localhost,127.0.0.1,minio,minio:9000,host.docker.internal",
    ).split(",")
    if h.strip()
}

# 业务类别 → 灾种码。只有模型真的是业务模型（输出类别数 == yolo_model.CLASSES）时才用。
CLASS_TO_DISASTER = {
    "crack": "collapse",      # 裂缝/剥落 → 崩塌类灾种的前兆
    "landslide": "landslide",  # 滑坡
}

# 无检出时"无异常"结论的置信度（模型确实在跑且没框，才能给高置信）
EMPTY_CONFIDENCE = 0.99

DISASTER_LABELS = {"collapse": "崩塌", "landslide": "滑坡", "none": "无异常"}


class DetectImageRequest(BaseModel):
    image_url: str = Field(min_length=1, description="图片 URL，须为 http/https 且在服务端白名单内")
    device_code: Optional[str] = Field(default=None, description="设备编码，仅用于日志回溯，可为空")
    hazard_code: Optional[str] = Field(default=None, description="隐患点编码，仅用于日志回溯，可为空")


class DetectImageResponse(BaseModel):
    # 向上层（Java 事件链路）直接给业务结论，而不是让它自己去猜 COCO 类别
    disaster_type: str = Field(serialization_alias="disasterType", description="collapse / landslide / none；模型非业务模型时恒为 none")
    confidence: float = Field(ge=0, le=1, description="业务置信度；无有效判定时为 0")
    detections: List[DetectionItem] = Field(default_factory=list, description="原始检测框，便于排查")
    detection_count: int
    image_url: str
    device_code: Optional[str] = None
    model: str
    mock: bool = False
    message: str = "ok"


def _validate_image_url(raw: str) -> str:
    """校验图片 URL 的协议与主机，拦截 SSRF。

    Args:
        raw: 调用方传入的 image_url。

    Returns:
        原样返回合法 URL。

    Raises:
        HTTPException: 400 协议/格式非法，403 主机不在白名单。
    """
    try:
        parsed = urlsplit(raw.strip())
    except ValueError as exc:
        raise HTTPException(400, f"image_url 格式非法: {exc}")

    if parsed.scheme not in ("http", "https"):
        # file:// / ftp:// 能直接读本地文件或探测内网，一律拒绝
        raise HTTPException(400, "image_url 仅支持 http/https")
    if not parsed.hostname:
        raise HTTPException(400, "image_url 缺少主机名")

    host = parsed.hostname.lower()
    netloc = parsed.netloc.lower()
    if host not in _ALLOWED_HOSTS and netloc not in _ALLOWED_HOSTS:
        raise HTTPException(
            403,
            f"image_url 主机不在白名单内: {host}（可用 DETECT_IMAGE_ALLOWED_HOSTS 扩展）",
        )
    return raw.strip()


async def _download_image(url: str) -> bytes:
    """下载图片字节流，带大小上限与总时长超时。

    不用 `resp.read()`：它会把整个响应一次性读进内存，恶意大文件在触发上限
    判断前就已经打爆内存。这里分块累计，超限立即断开连接。

    Args:
        url: 已通过白名单校验的图片地址。

    Returns:
        图片原始字节。

    Raises:
        HTTPException: 413 超限，502 上游返回非 200，504 超时或网络错误。
    """
    session = await HttpClientHolder.instance().session()
    timeout = aiohttp.ClientTimeout(total=DOWNLOAD_TIMEOUT_SECONDS)

    try:
        # 禁止跟随重定向：否则白名单可被 302 绕过（跳到一个不受控的内网地址）
        async with session.get(url, timeout=timeout, allow_redirects=False) as resp:
            if resp.status != 200:
                raise HTTPException(502, f"下载图片失败，上游返回 HTTP {resp.status}")

            declared = resp.content_length
            if declared is not None and declared > MAX_IMAGE_BYTES:
                raise HTTPException(
                    413,
                    f"图片过大: {declared} 字节，上限 {MAX_IMAGE_BYTES // 1024 // 1024}MB",
                )

            payload = bytearray()
            async for chunk in resp.content.iter_chunked(64 * 1024):
                payload.extend(chunk)
                if len(payload) > MAX_IMAGE_BYTES:
                    raise HTTPException(
                        413,
                        f"图片过大，超过上限 {MAX_IMAGE_BYTES // 1024 // 1024}MB",
                    )
    except HTTPException:
        raise
    except asyncio.TimeoutError:
        raise HTTPException(504, f"下载图片超时（超过 {DOWNLOAD_TIMEOUT_SECONDS:.0f}s）")
    except aiohttp.ClientError as exc:
        raise HTTPException(502, f"下载图片失败: {exc}")

    if not payload:
        raise HTTPException(400, "图片内容为空")
    return bytes(payload)


@router.post("/detect-image", response_model=DetectImageResponse)
async def detect_image(req: DetectImageRequest):
    """按 URL 拉取图片并做目标检测，返回业务化的灾种结论。

    流程：白名单校验 → 下载（≤10MB / 10s）→ 线程池推理 → 业务映射。
    推理复用 /detect 的线程池与超时，避免两条链路各占一份 CPU。
    """
    url = _validate_image_url(req.image_url)
    logger.info(
        "【Python AI】收到图像请求 device=%s hazard=%s host=%s",
        req.device_code, req.hazard_code, urlsplit(url).netloc,
    )

    image_bytes = await _download_image(url)

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
    count = len(detections)

    # 只有"真实业务模型 + 非 mock"才允许给出灾种结论。
    # 否则（COCO 占位模型 / 启发式 mock）检出的框与灾害无关，绝不能映射成灾种，
    # 否则会对着一辆车报"泥石流"——这正是历史版本出现过的问题。
    if result.get("mock"):
        disaster_type, confidence = "none", 0.0
        message = "模型未加载，返回启发式结果，不构成灾种判定"
    elif not result.get("is_business_model"):
        disaster_type, confidence = "none", 0.0
        message = (
            f"当前模型 {result['model']} 输出 {result.get('num_classes')} 类，"
            "非灾害业务模型，不做灾种判定；请用 datasets/disaster-v2 训练后替换"
        )
    elif count > 0:
        best = max(detections, key=lambda d: d.confidence)
        disaster_type = CLASS_TO_DISASTER.get(best.class_name, "none")
        confidence = best.confidence
        message = "ok"
    else:
        disaster_type, confidence = "none", EMPTY_CONFIDENCE
        message = "ok"

    logger.info(
        "【Python AI】YOLO 推理完成，检测到 %d 个目标，业务映射为：%s（%s）",
        count, DISASTER_LABELS.get(disaster_type, disaster_type), message,
    )

    return DetectImageResponse(
        disaster_type=disaster_type,
        confidence=confidence,
        detections=detections,
        detection_count=count,
        image_url=url,
        device_code=req.device_code,
        model=result["model"],
        mock=result.get("mock", False),
        message=message,
    )
