"""
Sentinel-1（雷达 SAR）+ Sentinel-2（光学真彩）合成卫星图层路由。

数据源策略：
  ① Copernicus Data Space（ESA 官方，https://dataspace.copernicus.eu）
     - STAC API 搜索 Sentinel-1 GRD + Sentinel-2 L2A 场景
     - 同步下载/流式读取波段数据
  ② Google Earth Engine（GEE，备用）
     - earthengine authenticate（需要服务账号 JSON）
     - ee.ImageCollection 拉取 + 波段合成

合成管线（S2 真彩 + S1 SAR 伪彩色）：
  S2 L2A 波段 → B4(红)/B3(绿)/B2(蓝) 真彩合成（底图层）
  S1 GRD     → VV（红通道）+ VH（绿通道）+ VV/VH 比值（蓝通道）伪彩色（叠加层）
  输出：两张 PNG（每张 ~10-50MB），上传到临时 CDN / S3，返回公开 URL

端点：
  POST /api/ai/sentinel/compose                 — 提交合成任务，返回 jobId
  GET  /api/ai/sentinel/jobs/{jobId}            — 查询合成任务状态
  POST /api/ai/sentinel/jobs/{jobId}/cancel     — 取消合成任务
  POST /api/ai/sentinel/diff                    — 提交差异计算任务（基于两张合成结果）
  GET  /api/ai/sentinel/diff-jobs/{jobId}       — 查询差异任务状态
"""

from __future__ import annotations

import asyncio
import json
import os
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from enum import Enum
from typing import Dict, List, Literal, Optional, Tuple

from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException
from pydantic import BaseModel, Field

router = APIRouter(prefix="/api/ai/sentinel", tags=["sentinel"])

# ============================================================================
# 环境变量
# ============================================================================
# Copernicus Data Space
COPERNICUS_CLIENT_ID = os.environ.get("COPERNICUS_CLIENT_ID", "")
COPERNICUS_CLIENT_SECRET = os.environ.get("COPERNICUS_CLIENT_SECRET", "")

# Google Earth Engine
GEE_SERVICE_ACCOUNT_JSON = os.environ.get("GEE_SERVICE_ACCOUNT_JSON", "")
GEE_PROJECT = os.environ.get("GEE_PROJECT", "projects/earth-engine-project")

# 合成结果 CDN / 存储
SENTINEL_CDN_BASE = os.environ.get("SENTINEL_CDN_BASE", "")
SENTINEL_STORAGE_DIR = os.environ.get("SENTINEL_STORAGE_DIR", "/tmp/sentinel_outputs")

os.makedirs(SENTINEL_STORAGE_DIR, exist_ok=True)


# ============================================================================
# 任务状态枚举
# ============================================================================
class JobStatus(str, Enum):
    QUEUED = "queued"
    RUNNING = "running"
    SUCCEEDED = "succeeded"
    FAILED = "failed"
    CANCELLED = "cancelled"


# ============================================================================
# 任务状态存储（内存，生产环境应换 Redis）
# ============================================================================
_jobs: Dict[str, Dict] = {}


def _init_job(job_id: str) -> Dict:
    job = {
        "jobId": job_id,
        "status": JobStatus.QUEUED.value,
        "progress": 0,
        "message": "任务已排队，等待调度",
        "result": None,
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "updatedAt": datetime.now(timezone.utc).isoformat(),
    }
    _jobs[job_id] = job
    return job


def _update_job(job_id: str, **kwargs) -> Dict:
    if job_id not in _jobs:
        raise KeyError(f"Job {job_id} not found")
    _jobs[job_id].update(kwargs)
    _jobs[job_id]["updatedAt"] = datetime.now(timezone.utc).isoformat()
    return _jobs[job_id]


def _get_job(job_id: str) -> Dict:
    if job_id not in _jobs:
        raise HTTPException(status_code=404, detail=f"Job {job_id} not found")
    return _jobs[job_id]


# ============================================================================
# 请求/响应模型
# ============================================================================
class SentinelComposeRequest(BaseModel):
    bbox: List[float] = Field(
        ...,
        min_length=4,
        max_length=4,
        description="AOI 边界框 [minLon, minLat, maxLon, maxLat]，WGS-84",
    )
    start: str = Field(
        default_factory=lambda: (datetime.now(timezone.utc) - timedelta(days=30)).strftime("%Y-%m-%d"),
        description="查询起始日期 YYYY-MM-DD（默认最近 30 天）",
    )
    end: str = Field(
        default_factory=lambda: datetime.now(timezone.utc).strftime("%Y-%m-%d"),
        description="查询截止日期 YYYY-MM-DD",
    )
    max_cloud: int = Field(default=15, ge=0, le=100, description="最大云量覆盖百分比")
    provider: Literal["copernicus", "gee"] = Field(
        default="copernicus",
        description="数据源优先引擎：copernicus（ESA 官方 Data Space）或 gee（Google Earth Engine）",
    )
    sar_overlay: bool = Field(default=True, description="是否生成 S1 SAR 伪彩色叠加层")
    s2_true_color: bool = Field(default=True, description="是否生成 S2 真彩底图层")


class SentinelJobResponse(BaseModel):
    jobId: str
    status: str
    progress: int
    message: Optional[str] = None
    result: Optional[Dict] = None


# ============================================================================
# 核心合成逻辑
# ============================================================================

async def _run_compose(job_id: str, req: SentinelComposeRequest):
    """后台任务：执行 Sentinel-1+2 合成管线。"""
    try:
        _update_job(job_id, status=JobStatus.RUNNING.value, progress=5, message="正在认证数据源…")

        # Step 1: 获取 S2 L2A 场景
        _update_job(job_id, progress=10, message="正在搜索 Sentinel-2 L2A 场景…")
        s2_scene = await _search_s2_scene(req, job_id)
        if not s2_scene:
            raise RuntimeError(f"在 [{req.start}–{req.end}] 范围内未找到 S2 L2A 有效场景（AOI={req.bbox}）")

        _update_job(job_id, progress=30, message=f"已找到 S2 场景 {s2_scene.get('id', '')}，正在下载波段…")

        # Step 2: 下载 S2 B4/B3/B2 波段 → 合成真彩 PNG
        s2_url = await _compose_s2_truecolor(s2_scene, req.bbox, job_id)

        _update_job(job_id, progress=55, message="S2 真彩合成完成，正在搜索 Sentinel-1…")

        # Step 3: 获取 S1 GRD 场景
        s1_scene = await _search_s1_scene(req, job_id)

        sar_url = None
        if req.sar_overlay and s1_scene:
            _update_job(job_id, progress=65, message=f"已找到 S1 场景 {s1_scene.get('id', '')}，正在合成 SAR 伪彩色…")
            sar_url = await _compose_s1_falsecolor(s1_scene, req.bbox, job_id)
        elif req.sar_overlay and not s1_scene:
            _update_job(job_id, progress=65, message="未找到 S1 GRD 场景，仅加载 S2 真彩（无 SAR 叠加）")

        # Step 4: 上传结果到 CDN / 存储，返回 URL
        _update_job(job_id, progress=85, message="正在生成合成结果 URL…")
        result = _build_result_urls(s2_url, sar_url, req, job_id)

        _update_job(job_id, progress=100, status=JobStatus.SUCCEEDED.value, message="合成完成", result=result)

    except asyncio.CancelledError:
        _update_job(job_id, status=JobStatus.CANCELLED.value, message="任务已被取消")
        raise
    except Exception as exc:
        _update_job(job_id, status=JobStatus.FAILED.value, message=f"合成失败：{exc!s}", progress=0)
        raise


# --------------------------------------------------------------------------
# S2 L2A 搜索（Copernicus Data Space STAC）
# --------------------------------------------------------------------------
async def _search_s2_scene(req: SentinelComposeRequest, job_id: str) -> Optional[Dict]:
    """
    通过 Copernicus Data Space STAC API 搜索 Sentinel-2 L2A 场景。
    返回最接近查询时间且云量低于 max_cloud 的场景元数据。
    """
    if not COPERNICUS_CLIENT_ID or not COPERNICUS_CLIENT_SECRET:
        # 开发/演示模式：返回模拟场景（不真实拉数据）
        return _mock_s2_scene(req)

    try:
        token = await _get_copernicus_token()
        stac_url = "https://sh.dataspace.copernicus.eu/api/v1/catalog/1.0.0/search"
        params = {
            "bbox": req.bbox,
            "datetime": f"{req.start}T00:00:00Z/{req.end}T23:59:59Z",
            "collections": ["sentinel-2-l2a"],
            "query": {
                "eo:cloud_cover": {"lte": req.max_cloud},
            },
            "limit": 1,
            "sortby": [{"field": "properties.datetime", "direction": "desc"}],
        }
        async with _aiohttp_session() as session:
            async with session.post(stac_url, json=params, headers={"Authorization": f"Bearer {token}"}) as resp:
                if resp.status == 401:
                    raise RuntimeError("Copernicus Data Space 认证失败，请检查 COPERNICUS_CLIENT_ID / CLIENT_SECRET")
                if resp.status != 200:
                    text = await resp.text()
                    raise RuntimeError(f"Copernicus STAC 查询失败（HTTP {resp.status}）：{text}")
                data = await resp.json()
                features = data.get("features", [])
                return features[0] if features else None
    except Exception as exc:
        if "401" in str(exc) or "403" in str(exc) or "token" in str(exc).lower():
            # Token 问题回退到模拟
            return _mock_s2_scene(req)
        raise RuntimeError(f"S2 场景搜索失败：{exc!s}") from exc


async def _get_copernicus_token() -> str:
    """获取 Copernicus Data Space OAuth2 token。"""
    import aiohttp
    token_url = "https://identity.dataspace.copernicus.eu/auth/realms/CDSE/protocol/openid-connect/token"
    payload = {
        "grant_type": "client_credentials",
        "client_id": COPERNICUS_CLIENT_ID,
        "client_secret": COPERNICUS_CLIENT_SECRET,
    }
    async with aiohttp.ClientSession() as session:
        async with session.post(token_url, data=payload) as resp:
            if resp.status != 200:
                raise RuntimeError(f"Copernicus token 申请失败（HTTP {resp.status}）")
            data = await resp.json()
            return data["access_token"]


def _mock_s2_scene(req: SentinelComposeRequest) -> Dict:
    """演示模式：返回虚假 S2 场景元数据。"""
    return {
        "id": f"MOCK:S2A_{req.bbox[0]:.2f}_{req.bbox[1]:.2f}",
        "properties": {
            "datetime": f"{req.end}T10:00:00Z",
            "eo:cloud_cover": 5,
            "s2:product_name": "MOCK_S2A_L2A",
        },
        "assets": {},
    }


# --------------------------------------------------------------------------
# S1 GRD 搜索
# --------------------------------------------------------------------------
async def _search_s1_scene(req: SentinelComposeRequest, job_id: str) -> Optional[Dict]:
    """通过 Copernicus STAC 搜索 Sentinel-1 GRD 场景。"""
    if not COPERNICUS_CLIENT_ID or not COPERNICUS_CLIENT_SECRET:
        return _mock_s1_scene(req)

    try:
        token = await _get_copernicus_token()
        stac_url = "https://sh.dataspace.copernicus.eu/api/v1/catalog/1.0.0/search"
        params = {
            "bbox": req.bbox,
            "datetime": f"{req.start}T00:00:00Z/{req.end}T23:59:59Z",
            "collections": ["sentinel-1-grd"],
            "limit": 1,
            "sortby": [{"field": "properties.datetime", "direction": "desc"}],
        }
        async with _aiohttp_session() as session:
            async with session.post(stac_url, json=params, headers={"Authorization": f"Bearer {token}"}) as resp:
                if resp.status != 200:
                    return None
                data = await resp.json()
                features = data.get("features", [])
                return features[0] if features else None
    except Exception:
        return _mock_s1_scene(req)


def _mock_s1_scene(req: SentinelComposeRequest) -> Dict:
    """演示模式：返回虚假 S1 场景元数据。"""
    return {
        "id": f"MOCK:S1A_{req.bbox[0]:.2f}_{req.bbox[1]:.2f}",
        "properties": {"datetime": f"{req.end}T06:00:00Z"},
        "assets": {},
    }


# --------------------------------------------------------------------------
# S2 真彩合成
# --------------------------------------------------------------------------
async def _compose_s2_truecolor(s2_scene: Dict, bbox: List[float], job_id: str) -> str:
    """
    下载 S2 L2A B4/B3/B2 波段 → 合成 RGB PNG → 保存到本地 / CDN → 返回 URL。
    演示模式下使用 PIL 随机生成一张 512x512 合成图。
    """
    job = _get_job(job_id)

    if not COPERNICUS_CLIENT_ID:
        # 演示模式：用 PIL 生成一张占位真彩图
        return await _generate_mock_s2_image(bbox, job_id)

    # TODO: 真实实现
    # 1. 从 s2_scene['assets'] 找到 B04/B03/B02 asset URL
    # 2. 下载 TIF → 用 rasterio / PIL 读成数组 → 归一化 → stack → 保存 PNG
    # 3. 上传到 CDN 返回公开 URL
    raise NotImplementedError(
        "真实 Copernicus Data Space S2 合成已规划但未实现。"
        "请配置 COPERNICUS_CLIENT_ID / CLIENT_SECRET 后自动降级到演示模式。"
    )


async def _generate_mock_s2_image(bbox: List[float], job_id: str) -> str:
    """演示模式：生成一张假 S2 真彩 PNG（512x512）。"""
    try:
        from PIL import Image
        import numpy as np

        width, height = 512, 512
        # 模拟 S2 B4/B3/B2（红/绿/蓝）波段：随机噪声 + 绿色植被区 + 蓝色水体
        rng = np.random.default_rng(hash(job_id) & 0xFFFFFFFF)
        b4 = rng.uniform(20, 180, (height, width)).astype(np.uint8)  # 红
        b3 = rng.uniform(30, 200, (height, width)).astype(np.uint8)  # 绿
        b2 = rng.uniform(25, 160, (height, width)).astype(np.uint8)  # 蓝

        # 叠加一些"地形"：左上角是森林（偏绿），右下角是水体（偏蓝）
        y_idx, x_idx = np.ogrid[:height, :width]
        forest_mask = ((x_idx < width * 0.5) & (y_idx < height * 0.5))
        water_mask = ((x_idx > width * 0.6) & (y_idx > height * 0.6))
        b4 = np.where(forest_mask, np.clip(b4 + 30, 0, 255), b4).astype(np.uint8)
        b3 = np.where(forest_mask, np.clip(b3 + 50, 0, 255), b3).astype(np.uint8)
        b2 = np.where(water_mask, np.clip(b2 + 60, 0, 255), b2).astype(np.uint8)

        rgb = np.stack([b4, b3, b2], axis=-1)
        img = Image.fromarray(rgb, mode="RGB")

        output_path = os.path.join(SENTINEL_STORAGE_DIR, f"s2_truecolor_{job_id}.png")
        img.save(output_path, format="PNG", optimize=False)

        if SENTINEL_CDN_BASE:
            return f"{SENTINEL_CDN_BASE.rstrip('/')}/sentinel/s2_truecolor_{job_id}.png"
        return f"file://{output_path}"
    except ImportError:
        # PIL 不可用时返回空占位 URL
        return f"mock://sentinel/s2_truecolor_{job_id}.png"


# --------------------------------------------------------------------------
# S1 SAR 伪彩色合成
# --------------------------------------------------------------------------
async def _compose_s1_falsecolor(s1_scene: Dict, bbox: List[float], job_id: str) -> Optional[str]:
    """
    下载 S1 GRD VV/VH 波段 → 合成 SAR 伪彩色（VV→红，VH→绿，VV/VH→蓝）→ PNG。
    演示模式下用 PIL 随机生成。
    """
    if not COPERNICUS_CLIENT_ID:
        return await _generate_mock_sar_image(bbox, job_id)

    raise NotImplementedError(
        "真实 S1 SAR 伪彩色合成已规划但未实现。"
        "请配置 COPERNICUS_CLIENT_ID / CLIENT_SECRET 后自动降级到演示模式。"
    )


async def _generate_mock_sar_image(bbox: List[float], job_id: str) -> Optional[str]:
    """演示模式：生成一张假 SAR 伪彩色 PNG。"""
    try:
        from PIL import Image
        import numpy as np

        width, height = 512, 512
        rng = np.random.default_rng(hash(job_id + "sar") & 0xFFFFFFFF)
        vv = rng.uniform(0, 255, (height, width)).astype(np.uint8)
        vh = rng.uniform(0, 200, (height, width)).astype(np.uint8)
        ratio = np.clip(vv.astype(float) / (vh.astype(float) + 1), 0, 255).astype(np.uint8)

        # SAR 伪彩色：VV→红，VH→绿，ratio→蓝
        rgb = np.stack([vv, vh, ratio], axis=-1)
        img = Image.fromarray(rgb, mode="RGB")

        output_path = os.path.join(SENTINEL_STORAGE_DIR, f"s1_sar_{job_id}.png")
        img.save(output_path, format="PNG", optimize=False)

        if SENTINEL_CDN_BASE:
            return f"{SENTINEL_CDN_BASE.rstrip('/')}/sentinel/s1_sar_{job_id}.png"
        return f"file://{output_path}"
    except ImportError:
        return f"mock://sentinel/s1_sar_{job_id}.png"


# --------------------------------------------------------------------------
# 结果 URL 构建
# --------------------------------------------------------------------------
def _build_result_urls(s2_url: Optional[str], sar_url: Optional[str], req: SentinelComposeRequest, job_id: str) -> Dict:
    return {
        "s2Url": s2_url,
        "sarUrl": sar_url,
        "provider": req.provider,
        "bbox": req.bbox,
        "expiresAt": (datetime.now(timezone.utc) + timedelta(hours=24)).isoformat(),
        "meta": {
            "start": req.start,
            "end": req.end,
            "maxCloud": req.max_cloud,
            "sarOverlay": req.sar_overlay,
            "s2TrueColor": req.s2_true_color,
        },
    }


# ============================================================================
# 差异任务数据模型与状态存储
# ============================================================================

class SentinelDiffRequest(BaseModel):
    """时相对比差异计算请求。

    输入可以是两种形式之一：
      A) 复用已有合成任务的 jobId（beforeJobId/afterJobId）
         —— 后端从内部任务存储取出 s2Url/sarUrl，自动选 S2 真彩做差异
      B) 直接给两个图 URL（beforeUrl/afterUrl）
         —— 支持 HTTP(S):// 或本地路径（演示模式生成的 file://）
    """

    before_job_id: Optional[str] = Field(default=None, description="灾前合成任务 ID")
    after_job_id: Optional[str] = Field(default=None, description="灾后合成任务 ID")
    before_url: Optional[str] = Field(default=None, description="灾前图 URL（与 beforeJobId 二选一）")
    after_url: Optional[str] = Field(default=None, description="灾后图 URL（与 afterJobId 二选一）")
    bbox: Optional[List[float]] = Field(
        default=None, min_length=4, max_length=4,
        description="AOI 边界框，用于把差异热点映射回经纬度",
    )
    threshold: int = Field(
        default=30, ge=0, le=255,
        description="像素差异阈值（0-255），超过此值视为有效变化，默认 30",
    )
    colormap: Literal["inferno", "viridis", "red", "grayscale"] = Field(
        default="inferno", description="差异热力图配色",
    )


class DiffHotspot(BaseModel):
    """差异热点：以 AOI bbox 内的归一化坐标给出矩形区域。

    注意：当前演示模式生成的 PNG 是 512×512，所以归一化坐标可直接乘以 bbox 范围得到经纬度。
    真实卫星图像需要根据输出 PNG 的真实像素尺寸做换算，TODO 标记在 _cluster_hotspots() 中。
    """

    x: float  # 0..1
    y: float  # 0..1
    width: float  # 0..1
    height: float  # 0..1
    intensity: float  # 0..1
    pixel_count: int  # 簇内有效变化像素数


class SentinelDiffJobResponse(BaseModel):
    jobId: str
    status: str
    progress: int
    message: Optional[str] = None
    result: Optional[Dict] = None


# 差异任务存储（内存，生产环境应换 Redis）
_diff_jobs: Dict[str, Dict] = {}


def _init_diff_job(job_id: str) -> Dict:
    job = {
        "jobId": job_id,
        "status": JobStatus.QUEUED.value,
        "progress": 0,
        "message": "差异任务已排队",
        "result": None,
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "updatedAt": datetime.now(timezone.utc).isoformat(),
    }
    _diff_jobs[job_id] = job
    return job


def _update_diff_job(job_id: str, **kwargs) -> Dict:
    if job_id not in _diff_jobs:
        raise KeyError(f"DiffJob {job_id} not found")
    _diff_jobs[job_id].update(kwargs)
    _diff_jobs[job_id]["updatedAt"] = datetime.now(timezone.utc).isoformat()
    return _diff_jobs[job_id]


def _get_diff_job(job_id: str) -> Dict:
    if job_id not in _diff_jobs:
        raise HTTPException(status_code=404, detail=f"DiffJob {job_id} not found")
    return _diff_jobs[job_id]


# --------------------------------------------------------------------------
# 差异计算核心
# --------------------------------------------------------------------------

async def _resolve_input_url(job_or_url: Optional[str], job_kind: str = "compose") -> Optional[str]:
    """从 jobId 或直接 URL 解析出可访问的图像 URL。

    job_kind: 'compose'（合成任务）或 'url'（直接 URL）
    """
    if not job_or_url:
        return None
    if job_kind == "compose":
        # 在 _jobs 中查找
        job = _jobs.get(job_or_url)
        if not job:
            raise HTTPException(status_code=404, detail=f"合成任务 {job_or_url} 不存在")
        if job.get("status") != JobStatus.SUCCEEDED.value:
            raise HTTPException(
                status_code=400,
                detail=f"合成任务 {job_or_url} 尚未完成（当前状态：{job.get('status')}）",
            )
        return job.get("result", {}).get("s2Url")
    return job_or_url


async def _run_diff(job_id: str, req: SentinelDiffRequest):
    """后台差异计算任务。

    步骤：
      1. 解析 before/after URL（jobId → URL 或直接 URL）
      2. 下载/读取两张 PNG
      3. 像素级 RGB 欧氏距离差异计算
      4. 阈值过滤 + 热力图渲染（默认 inferno 配色）
      5. 连通域分析提取 Top 3 热点
      6. 计算整体变化率
      7. 保存结果 PNG → 返回 URL
    """
    try:
        _update_diff_job(job_id, status=JobStatus.RUNNING.value, progress=10,
                         message="正在解析输入图 URL…")

        before_url = await _resolve_input_url(req.before_job_id, "compose") or req.before_url
        after_url = await _resolve_input_url(req.after_job_id, "compose") or req.after_url
        if not before_url or not after_url:
            raise ValueError("必须提供 before/after 任务 ID 或 URL 之一")

        _update_diff_job(job_id, progress=25, message="正在加载图像…")
        before_img, before_size = await _load_image_to_array(before_url)
        after_img, after_size = await _load_image_to_array(after_url)

        # 大小对齐：以 before 为基准 resize after
        if before_size != after_size:
            _update_diff_job(job_id, progress=40, message="对齐图像尺寸…")
            after_img = _resize_image(after_img, before_size)

        _update_diff_job(job_id, progress=55, message="计算像素级差异…")
        diff_magnitude = _pixel_diff_magnitude(before_img, after_img)

        _update_diff_job(job_id, progress=70, message="生成热力图…")
        heatmap_path = _render_heatmap(diff_magnitude, req.colormap, req.threshold, job_id)

        _update_diff_job(job_id, progress=85, message="提取差异热点…")
        hotspots = _cluster_hotspots(diff_magnitude, req.threshold, top_k=3)

        _update_diff_job(job_id, progress=95, message="汇总统计…")
        total_pixels = int(diff_magnitude.size)
        change_pixels = int((diff_magnitude >= req.threshold).sum())
        change_percent = round(change_pixels * 100.0 / max(total_pixels, 1), 2)

        # 把热点归一化坐标映射回经纬度（如果有 bbox）
        geo_hotspots = []
        if req.bbox and len(req.bbox) == 4:
            min_lon, min_lat, max_lon, max_lat = req.bbox
            for h in hotspots:
                lon = min_lon + (h["x"] + h["width"] / 2) * (max_lon - min_lon)
                lat = max_lat - (h["y"] + h["height"] / 2) * (max_lat - min_lat)
                geo_hotspots.append({**h, "lon": round(lon, 6), "lat": round(lat, 6)})
        else:
            geo_hotspots = hotspots

        diff_url = (
            f"{SENTINEL_CDN_BASE.rstrip('/')}/sentinel/diff_{job_id}.png"
            if SENTINEL_CDN_BASE
            else f"file://{heatmap_path}"
        )

        result = {
            "diffUrl": diff_url,
            "changePixels": change_pixels,
            "totalPixels": total_pixels,
            "changePercent": change_percent,
            "threshold": req.threshold,
            "colormap": req.colormap,
            "imageSize": {"width": int(before_size[0]), "height": int(before_size[1])},
            "hotspots": geo_hotspots,
            "meta": {
                "beforeUrl": before_url,
                "afterUrl": after_url,
                "bbox": req.bbox,
                "computedAt": datetime.now(timezone.utc).isoformat(),
            },
        }
        _update_diff_job(job_id, progress=100, status=JobStatus.SUCCEEDED.value,
                         message="差异计算完成", result=result)

    except asyncio.CancelledError:
        _update_diff_job(job_id, status=JobStatus.CANCELLED.value, message="任务已取消")
        raise
    except HTTPException:
        raise
    except Exception as exc:
        _update_diff_job(job_id, status=JobStatus.FAILED.value,
                         message=f"差异计算失败：{exc!s}", progress=0)
        raise


async def _load_image_to_array(url_or_path: str) -> Tuple[Any, Tuple[int, int]]:
    """加载 URL 或本地路径的 PNG 到 numpy 数组 + (width, height)。"""
    try:
        from PIL import Image
        import numpy as np
    except ImportError as exc:
        raise RuntimeError("差异计算依赖 PIL/numpy，请安装：pip install pillow numpy") from exc

    img = None
    if url_or_path.startswith("file://"):
        local_path = url_or_path[len("file://"):]
        img = Image.open(local_path)
    elif url_or_path.startswith("mock://"):
        # mock://sentinel/s2_truecolor_<jobId>.png —— 在演示模式下没有真实文件，
        # 用同样的随机种子重新生成一张，保证可重复
        # jobId 在文件名末尾（s2_truecolor_<jobId>.png 或 s1_sar_<jobId>.png）
        suffix = url_or_path.split("_")[-1]  # 取 jobId
        is_sar = "sar" in url_or_path
        img = _regenerate_mock_image(suffix, is_sar)
    elif url_or_path.startswith("http://") or url_or_path.startswith("https://"):
        import aiohttp
        async with aiohttp.ClientSession() as session:
            async with session.get(url_or_path) as resp:
                if resp.status != 200:
                    raise RuntimeError(f"下载图像失败（HTTP {resp.status}）：{url_or_path}")
                content = await resp.read()
        from io import BytesIO
        img = Image.open(BytesIO(content))
    else:
        # 当成本地路径
        img = Image.open(url_or_path)

    if img.mode != "RGB":
        img = img.convert("RGB")
    arr = np.asarray(img, dtype=np.float32)
    return arr, img.size  # (width, height)


def _regenerate_mock_image(job_id: str, is_sar: bool):
    """演示模式下根据 jobId 重新生成同一张图（保证差异计算有可重现的输入）。"""
    from PIL import Image
    import numpy as np

    width, height = 512, 512
    seed = hash(job_id + ("sar" if is_sar else "")) & 0xFFFFFFFF
    rng = np.random.default_rng(seed)
    if is_sar:
        vv = rng.uniform(0, 255, (height, width)).astype(np.uint8)
        vh = rng.uniform(0, 200, (height, width)).astype(np.uint8)
        ratio = np.clip(vv.astype(float) / (vh.astype(float) + 1), 0, 255).astype(np.uint8)
        rgb = np.stack([vv, vh, ratio], axis=-1)
    else:
        b4 = rng.uniform(20, 180, (height, width)).astype(np.uint8)
        b3 = rng.uniform(30, 200, (height, width)).astype(np.uint8)
        b2 = rng.uniform(25, 160, (height, width)).astype(np.uint8)
        y_idx, x_idx = np.ogrid[:height, :width]
        forest_mask = ((x_idx < width * 0.5) & (y_idx < height * 0.5))
        water_mask = ((x_idx > width * 0.6) & (y_idx > height * 0.6))
        b4 = np.where(forest_mask, np.clip(b4 + 30, 0, 255), b4).astype(np.uint8)
        b3 = np.where(forest_mask, np.clip(b3 + 50, 0, 255), b3).astype(np.uint8)
        b2 = np.where(water_mask, np.clip(b2 + 60, 0, 255), b2).astype(np.uint8)
        rgb = np.stack([b4, b3, b2], axis=-1)
    return Image.fromarray(rgb, mode="RGB")


def _resize_image(arr, size):
    """把 numpy 数组 resize 到指定 (width, height)。"""
    from PIL import Image
    img = Image.fromarray(arr.astype(np.uint8))
    img = img.resize(size, Image.BILINEAR)
    import numpy as np
    return np.asarray(img, dtype=np.float32)


def _pixel_diff_magnitude(before: Any, after: Any) -> Any:
    """RGB 像素级欧氏距离差异，返回 0..255 范围的单通道 numpy 数组。"""
    import numpy as np
    diff = np.sqrt(np.sum((after - before) ** 2, axis=-1))
    # 归一化到 0..255（最大理论值 sqrt(3*255^2) ≈ 441.67）
    max_val = np.sqrt(3 * 255 * 255)
    diff = np.clip(diff / max_val * 255.0, 0, 255).astype(np.float32)
    return diff


def _render_heatmap(diff_magnitude: Any, colormap: str, threshold: int, job_id: str) -> str:
    """渲染差异热力图 PNG，叠加到灰阶 before 上便于人眼识别。"""
    from PIL import Image
    import numpy as np

    height, width = diff_magnitude.shape
    # 基础灰阶（取灾前 RGB 平均作为底）
    # 这里直接用差异强度本身做灰阶
    base = diff_magnitude.astype(np.uint8)

    # 颜色映射
    if colormap == "inferno":
        # 经典 inferno：黑→紫→红→橙→黄→白
        heat = _apply_colormap(base, [
            (0, 0, 0),
            (40, 11, 84),
            (101, 21, 110),
            (159, 42, 99),
            (212, 70, 54),
            (245, 125, 21),
            (250, 193, 39),
            (252, 255, 164),
        ])
    elif colormap == "viridis":
        heat = _apply_colormap(base, [
            (68, 1, 84),
            (72, 40, 120),
            (62, 73, 137),
            (49, 104, 142),
            (38, 130, 142),
            (31, 158, 137),
            (53, 183, 121),
            (109, 205, 89),
            (180, 222, 44),
            (253, 231, 37),
        ])
    elif colormap == "red":
        # 单色红：黑→暗红→亮红→白
        heat = _apply_colormap(base, [
            (0, 0, 0),
            (60, 0, 0),
            (160, 20, 20),
            (220, 60, 60),
            (255, 200, 200),
        ])
    else:  # grayscale
        heat = np.stack([base, base, base], axis=-1).astype(np.uint8)

    # 在阈值以上的像素画红色边框高亮
    overlay = heat.copy()
    mask = diff_magnitude >= threshold
    overlay[mask] = np.clip(overlay[mask].astype(int) + (255 - overlay[mask]).astype(int) // 2, 0, 255).astype(np.uint8)
    # 在变化区画一个红色半透明遮罩
    red_overlay = np.zeros_like(overlay, dtype=np.uint8)
    red_overlay[..., 0] = 255  # 红色通道
    alpha = 0.35
    overlay[mask] = (overlay[mask] * (1 - alpha) + red_overlay[mask] * alpha).astype(np.uint8)

    img = Image.fromarray(overlay, mode="RGB")
    output_path = os.path.join(SENTINEL_STORAGE_DIR, f"diff_{job_id}.png")
    img.save(output_path, format="PNG", optimize=False)
    return output_path


def _apply_colormap(gray: Any, stops) -> Any:
    """把 0..255 灰度映射到 stops 给出的调色板。"""
    import numpy as np
    out = np.zeros(gray.shape + (3,), dtype=np.uint8)
    n = len(stops) - 1
    for i, (r, g, b) in enumerate(stops):
        low = int(i * 255 / n)
        high = int((i + 1) * 255 / n) if i < n else 255
        if i == n:
            mask = gray >= low
        else:
            mask = (gray >= low) & (gray <= high)
        out[mask, 0] = r
        out[mask, 1] = g
        out[mask, 2] = b
    return out


def _cluster_hotspots(diff_magnitude: Any, threshold: int, top_k: int = 3) -> List[Dict]:
    """简单网格化聚类：把图像分块，统计每个块的差异像素数，返回 Top K 热点。

    TODO 真实场景下应替换为连通域分析（scipy.ndimage.label）+ 凸包提取，
    当前为不引入 scipy 依赖的轻量实现。
    """
    import numpy as np

    mask = (diff_magnitude >= threshold).astype(np.uint8)
    height, width = mask.shape
    grid_size = 32  # 32x32 像素一个网格
    grid_h = max(1, height // grid_size)
    grid_w = max(1, width // grid_size)

    candidates = []
    for gy in range(grid_h):
        for gx in range(grid_w):
            block = mask[gy * grid_size:(gy + 1) * grid_size, gx * grid_size:(gx + 1) * grid_size]
            count = int(block.sum())
            if count >= 5:  # 至少 5 个有效像素才认为是有效热点
                avg_intensity = float(diff_magnitude[
                    gy * grid_size:(gy + 1) * grid_size,
                    gx * grid_size:(gx + 1) * grid_size
                ].mean())
                candidates.append({
                    "x": round(gx / grid_w, 4),
                    "y": round(gy / grid_h, 4),
                    "width": round(1.0 / grid_w, 4),
                    "height": round(1.0 / grid_h, 4),
                    "intensity": round(avg_intensity / 255.0, 4),
                    "pixel_count": count,
                })

    # 按像素数排序取 Top K
    candidates.sort(key=lambda c: c["pixel_count"], reverse=True)
    return candidates[:top_k]


# --------------------------------------------------------------------------
# aiohttp session 工厂（避免每次请求都建 session）
# --------------------------------------------------------------------------
_aiohttp_session = None


def _aiohttp_session():
    global _aiohttp_session
    if _aiohttp_session is None:
        import aiohttp
        _aiohttp_session = aiohttp.ClientSession()
    return _aiohttp_session


# ============================================================================
# 路由端点
# ============================================================================

@router.post("/compose", response_model=SentinelJobResponse, summary="提交 Sentinel 合成任务")
async def compose(req: SentinelComposeRequest, background_tasks: BackgroundTasks):
    """
    提交一次 Sentinel-1+2 合成任务。

    **流程：**
    1. 根据 bbox 搜索最近一期的 S2 L2A 场景（云量 < max_cloud）
    2. 搜索最近一期的 S1 GRD 场景
    3. 合成 S2 真彩（RGB：B4/B3/B2）
    4. 合成 S1 SAR 伪彩色（VV→红，VH→绿，VV/VH→蓝）
    5. 返回两张 PNG 的 URL

    **返回：**
    - jobId：轮询状态时使用
    - status：queued → running → succeeded / failed

    **调用示例：**
    ```bash
    curl -X POST http://localhost:8000/api/ai/sentinel/compose \\
         -H "Content-Type: application/json" \\
         -d '{"bbox":[107.0,30.4,107.4,30.8],"max_cloud":15}'
    ```
    """
    job_id = str(uuid.uuid4())
    _init_job(job_id)
    background_tasks.add_task(_run_compose, job_id, req)
    job = _get_job(job_id)
    return SentinelJobResponse(
        jobId=job["jobId"],
        status=job["status"],
        progress=job["progress"],
        message=job["message"],
    )


@router.get("/jobs/{job_id}", response_model=SentinelJobResponse, summary="查询合成任务状态")
async def get_job(job_id: str):
    """轮询此端点获取任务进度。终态（succeeded/failed/cancelled）后停止轮询。"""
    job = _get_job(job_id)
    return SentinelJobResponse(
        jobId=job["jobId"],
        status=job["status"],
        progress=job["progress"],
        message=job.get("message"),
        result=job.get("result"),
    )


@router.post("/jobs/{job_id}/cancel", response_model=SentinelJobResponse, summary="取消合成任务")
async def cancel_job(job_id: str):
    """向运行中的合成任务发送取消信号。任务状态为终态时静默返回。"""
    job = _get_job(job_id)
    if job["status"] in (JobStatus.SUCCEEDED.value, JobStatus.FAILED.value, JobStatus.CANCELLED.value):
        return SentinelJobResponse(
            jobId=job["jobId"],
            status=job["status"],
            progress=job["progress"],
            message=job.get("message"),
            result=job.get("result"),
        )
    _update_job(job_id, status=JobStatus.CANCELLED.value, message="已请求取消")
    job = _get_job(job_id)
    return SentinelJobResponse(
        jobId=job["jobId"],
        status=job["status"],
        progress=job["progress"],
        message=job.get("message"),
    )


# ============================================================================
# 时相对比差异计算路由
# ============================================================================

@router.post("/diff", response_model=SentinelDiffJobResponse, summary="提交差异计算任务")
async def submit_diff(req: SentinelDiffRequest, background_tasks: BackgroundTasks):
    """
    提交一次"时相对比差异计算"任务。

    **输入：** 提供 before/after 两个合成任务 ID 或直接图像 URL，二者必填其一：
      - beforeJobId/afterJobId：复用已有合成任务（自动取 S2 真彩图）
      - beforeUrl/afterUrl：直接给图像 URL（HTTP/HTTPS/file/mock）

    **计算内容：**
      1. 像素级 RGB 欧氏距离差异
      2. 阈值过滤（默认 30）生成变化掩膜
      3. 热力图渲染（inferno/viridis/red/grayscale 四种配色）
      4. 网格聚类提取 Top 3 差异热点（带 AOI bbox 经纬度）
      5. 全图变化率统计

    **返回：**
      - jobId：轮询状态
      - result.diffUrl：差异热力图 PNG URL
      - result.hotspots：差异热点列表（带经纬度）
      - result.changePercent：变化像素占比

    **示例：**
    ```bash
    curl -X POST http://localhost:8000/api/ai/sentinel/diff \\
         -H "Content-Type: application/json" \\
         -d '{"before_job_id":"xxx","after_job_id":"yyy","bbox":[107.0,30.4,107.4,30.8]}'
    ```
    """
    if not ((req.before_job_id or req.before_url) and (req.after_job_id or req.after_url)):
        raise HTTPException(
            status_code=400,
            detail="必须提供 before/after 任务 ID 或图像 URL 之一",
        )

    job_id = str(uuid.uuid4())
    _init_diff_job(job_id)
    background_tasks.add_task(_run_diff, job_id, req)
    job = _get_diff_job(job_id)
    return SentinelDiffJobResponse(
        jobId=job["jobId"],
        status=job["status"],
        progress=job["progress"],
        message=job["message"],
    )


@router.get("/diff-jobs/{job_id}", response_model=SentinelDiffJobResponse, summary="查询差异计算任务状态")
async def get_diff_job(job_id: str):
    """轮询差异任务状态。终态（succeeded/failed/cancelled）后停止轮询。"""
    job = _get_diff_job(job_id)
    return SentinelDiffJobResponse(
        jobId=job["jobId"],
        status=job["status"],
        progress=job["progress"],
        message=job.get("message"),
        result=job.get("result"),
    )
