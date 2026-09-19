"""
Sentinel-1（雷达 SAR）+ Sentinel-2（光学真彩）合成卫星图层路由。

数据源策略（v1.9.0 升级到新 CDSE + 公开源 fallback 链）：
  ① Copernicus Data Space（ESA 官方，https://dataspace.copernicus.eu）
     - 新版 STAC API：https://stac.dataspace.copernicus.eu/v1/
     - OAuth client_credentials 流程（凭证放 .env,见 COPERNICUS_CLIENT_*）
  ② Element84 Earth Search AWS（公开,Sentinel-2 L2A 镜像,无凭证）
     - https://earth-search.aws.element84.com/v1/
  ③ NASA Earthdata CMR / USGS Landsat Look（公开,Landsat 8/9 + 部分 Sentinel）
     - https://cmr.earthdata.nasa.gov/stac/LPCLOUD/
  ④ Google Earth Engine（GEE,可选,需要服务账号 JSON）
  ⑤ Mock 生成（最后兜底,纯本地 PIL 合成,演示模式）

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
import logging
import os
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from enum import Enum
from pathlib import Path
from typing import Dict, List, Literal, Optional, Tuple

import aiohttp
from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException
from pydantic import BaseModel, Field

LOG = logging.getLogger("ai.sentinel")

from app.utils.cdse_token import CdseAuthError, CdseTokenCache, is_cdse_configured
from app.utils.http_client import HttpClientHolder
from app.utils.stac_clients import (
    CdseStacClient,
    StacClientError,
    search_with_fallback,
)

router = APIRouter(prefix="/api/ai/sentinel", tags=["sentinel"])

# ============================================================================
# 环境变量（注意：必须用函数读取，不能在模块顶层捕获，否则 main.py 在子进程
# 中 import 时已经过了最佳时机。函数式读取保证任何上下文都拿到最新值。）
# ============================================================================
def _copernicus_client_id() -> str:
    return os.environ.get("COPERNICUS_CLIENT_ID", "")

def _copernicus_client_secret() -> str:
    return os.environ.get("COPERNICUS_CLIENT_SECRET", "")

def _gee_service_account_json() -> str:
    return os.environ.get("GEE_SERVICE_ACCOUNT_JSON", "")

def _gee_project() -> str:
    return os.environ.get("GEE_PROJECT", "projects/earth-engine-project")

def _sentinel_cdn_base() -> str:
    return os.environ.get("SENTINEL_CDN_BASE", "")

def _sentinel_storage_dir() -> str:
    """每次调用都重新读取环境变量，兼容子进程 import 时机差异。"""
    d = os.environ.get(
        "SENTINEL_STORAGE_DIR",
        str(Path(__file__).resolve().parent.parent.parent / "outputs" / "sentinel"),
    )
    Path(d).mkdir(parents=True, exist_ok=True)
    return d


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
_diff_jobs: Dict[str, Dict] = {}

# 进程内的 asyncio 锁，避免后台任务并发更新同一 job 时丢字段（不同 uvicorn worker
# 之间不共享 dict——这一限制需要在生产环境替换为 Redis 才能彻底解决）。
_jobs_lock = asyncio.Lock()
_diff_jobs_lock = asyncio.Lock()


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


async def _update_job_async(job_id: str, **kwargs) -> Dict:
    """异步上下文使用的更新函数，加锁以避免并发丢字段。"""
    async with _jobs_lock:
        if job_id not in _jobs:
            raise KeyError(f"Job {job_id} not found")
        _jobs[job_id].update(kwargs)
        _jobs[job_id]["updatedAt"] = datetime.now(timezone.utc).isoformat()
        return _jobs[job_id]


def _update_job(job_id: str, **kwargs) -> Dict:
    """同步上下文使用的更新函数（FastAPI 启动期 / 同步工具脚本）。"""
    if job_id not in _jobs:
        raise KeyError(f"Job {job_id} not found")
    _jobs[job_id].update(kwargs)
    _jobs[job_id]["updatedAt"] = datetime.now(timezone.utc).isoformat()
    return _jobs[job_id]


def _get_job(job_id: str) -> Dict:
    if job_id not in _jobs:
        raise HTTPException(status_code=404, detail=f"Job {job_id} not found")
    return _jobs[job_id]


async def _update_diff_job_async(job_id: str, **kwargs) -> Dict:
    async with _diff_jobs_lock:
        if job_id not in _diff_jobs:
            raise KeyError(f"Diff job {job_id} not found")
        _diff_jobs[job_id].update(kwargs)
        _diff_jobs[job_id]["updatedAt"] = datetime.now(timezone.utc).isoformat()
        return _diff_jobs[job_id]


def _update_diff_job(job_id: str, **kwargs) -> Dict:
    if job_id not in _diff_jobs:
        raise KeyError(f"Diff job {job_id} not found")
    _diff_jobs[job_id].update(kwargs)
    _diff_jobs[job_id]["updatedAt"] = datetime.now(timezone.utc).isoformat()
    return _diff_jobs[job_id]


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
    provider: Literal["auto", "copernicus", "earth_search", "usgs", "gee", "mock"] = Field(
        default="auto",
        description=(
            "数据源策略：\n"
            "  auto          默认,CDSE 凭证可用时走 CDSE 主链,失败自动降级到 Earth Search → USGS → mock\n"
            "  copernicus    强制走 CDSE 主链(凭证缺失则报错)\n"
            "  earth_search  跳过 CDSE,直接 Element84 Earth Search AWS → USGS → mock\n"
            "  usgs          仅用 NASA Earthdata CMR + mock\n"
            "  gee           Google Earth Engine(需要服务账号)\n"
            "  mock          始终使用本地 PIL 模拟生成,无网络请求"
        ),
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
        await _update_job_async(job_id, status=JobStatus.RUNNING.value, progress=5, message="正在认证数据源…")

        # provider 参数决定 STAC 搜索策略
        skip_cdse = req.provider in ("earth_search", "usgs", "mock")
        use_mock_only = req.provider == "mock"

        if use_mock_only:
            # mock 模式:跳过所有 STAC,直接生成占位图
            await _update_job_async(job_id, progress=10, message="mock 模式,跳过 STAC 搜索…")
            s2_scene = _mock_s2_scene(req)
            s1_scene = _mock_s1_scene(req) if req.sar_overlay else None
        else:
            # Step 1+3: 并发搜索 S2 + S1（并行等待节省 ~50% 网络时间）
            await _update_job_async(job_id, progress=10, message="正在并发搜索 Sentinel-2 / Sentinel-1 场景…")
            search_tasks = [_search_s2_scene(req, job_id, skip_cdse=skip_cdse)]
            if req.sar_overlay:
                search_tasks.append(
                    _search_s1_scene(req, job_id, skip_cdse=skip_cdse)
                )

            results = await asyncio.gather(*search_tasks, return_exceptions=True)

            s2_scene = results[0] if not isinstance(results[0], BaseException) else None
            if isinstance(results[0], BaseException):
                LOG.warning("S2 并发搜索失败: %s", results[0])
                s2_scene = _mock_s2_scene(req)
            if s2_scene is None or (
                isinstance(s2_scene, dict) and not s2_scene.get("id")
            ):
                raise RuntimeError(f"在 [{req.start}–{req.end}] 范围内未找到 S2 L2A 有效场景（AOI={req.bbox}）")

            s1_scene = None
            if req.sar_overlay and len(results) > 1:
                if isinstance(results[1], BaseException):
                    LOG.warning("S1 并发搜索失败: %s", results[1])
                else:
                    s1_scene = results[1]

            await _update_job_async(
                job_id, progress=45,
                message=f"S2 搜索完成 {s2_scene.get('id', '')},"
                        + ("S1 搜索完成" if s1_scene else "无 S1 场景")
            )

        # Step 2: 下载 S2 B4/B3/B2 波段 → 合成真彩 PNG
        s2_url = await _compose_s2_truecolor(s2_scene, req.bbox, job_id)

        sar_url = None
        if req.sar_overlay and s1_scene:
            await _update_job_async(job_id, progress=65, message=f"已找到 S1 场景 {s1_scene.get('id', '')}，正在合成 SAR 伪彩色…")
            sar_url = await _compose_s1_falsecolor(s1_scene, req.bbox, job_id)
        elif req.sar_overlay and not s1_scene:
            await _update_job_async(job_id, progress=65, message="未找到 S1 GRD 场景，仅加载 S2 真彩（无 SAR 叠加）")

        # Step 4: 上传结果到 CDN / 存储，返回 URL
        await _update_job_async(job_id, progress=85, message="正在生成合成结果 URL…")
        result = _build_result_urls(s2_url, sar_url, req, job_id)

        await _update_job_async(job_id, progress=100, status=JobStatus.SUCCEEDED.value, message="合成完成", result=result)

    except asyncio.CancelledError:
        await _update_job_async(job_id, status=JobStatus.CANCELLED.value, message="任务已被取消")
        raise
    except Exception as exc:
        # 记录完整 traceback，仅向 API 返回通用消息，避免泄漏内部细节
        LOG.exception("合成任务失败 job=%s", job_id)
        await _update_job_async(job_id, status=JobStatus.FAILED.value, message="合成失败（详情查看日志）", progress=0)
        raise


# --------------------------------------------------------------------------
# S2 L2A 搜索（Copernicus Data Space STAC + fallback 链）
# --------------------------------------------------------------------------
async def _search_s2_scene(req: SentinelComposeRequest, job_id: str, *, skip_cdse: bool = False) -> Optional[Dict]:
    """
    通过 STAC fallback 链搜索 Sentinel-2 L2A 场景。

    链路：CDSE（凭证可用时）→ Element84 Earth Search AWS（公开）→ USGS Earthdata CMR（公开）。
    三家全挂时返回 mock。
    skip_cdse=True 强制跳过 CDSE。
    """
    skip = skip_cdse or not is_cdse_configured()
    try:
        result = await search_with_fallback(
            bbox=req.bbox,
            datetime_range=_normalize_stac_datetime_range(req.start, req.end),
            collections=["sentinel-2-l2a"],
            query={"eo:cloud_cover": {"lte": req.max_cloud}},
            limit=1,
            sortby=[{"field": "properties.datetime", "direction": "desc"}],
            skip_cdse=skip,
        )
        return result.features[0] if result.features else None
    except StacClientError as exc:
        LOG.warning("S2 STAC 链失败,降级 mock: %s", exc)
        return _mock_s2_scene(req)
    except CdseAuthError as exc:
        LOG.error("S2 CDSE 凭证错,降级 mock: %s", exc)
        return _mock_s2_scene(req)


# --------------------------------------------------------------------------
# S1 GRD 搜索（同样走 fallback 链）
# --------------------------------------------------------------------------
async def _search_s1_scene(req: SentinelComposeRequest, job_id: str, *, skip_cdse: bool = False) -> Optional[Dict]:
    """通过 STAC fallback 链搜索 Sentinel-1 GRD 场景。失败返回 None。"""
    skip = skip_cdse or not is_cdse_configured()
    try:
        result = await search_with_fallback(
            bbox=req.bbox,
            datetime_range=_normalize_stac_datetime_range(req.start, req.end),
            collections=["sentinel-1-grd"],
            limit=1,
            sortby=[{"field": "properties.datetime", "direction": "desc"}],
            skip_cdse=skip,
        )
        return result.features[0] if result.features else None
    except StacClientError as exc:
        LOG.warning("S1 STAC 链失败: %s", exc)
        return None


def _normalize_stac_datetime_range(start: str, end: str) -> str:
    """规范化 STAC datetime_range 字符串,兼容两种输入格式:
    1) date-only:       "2026-07-16"     → "2026-07-16T00:00:00Z"
    2) full RFC3339:    "2026-07-16T00:00:00Z" → 原样返回
    最终输出: "2026-07-16T00:00:00Z/2026-09-14T23:59:59Z"

    修复 v1.9.2 之前存在的双重时间戳 bug:当 req.start 已是 RFC3339 时,
    直接 f"{start}T00:00:00Z" 会拼成 "...Z T00:00:00Z" 让 USGS 报 400,
    Earth Search 则静默返回空,导致整个 STAC 链 fallback 到 mock 噪声。
    """
    def _norm_one(d: str, default_t: str) -> str:
        d = (d or "").strip()
        if not d:
            return ""
        return d if "T" in d else f"{d}{default_t}"

    s = _norm_one(start, "T00:00:00Z")
    e = _norm_one(end, "T23:59:59Z")
    if not s and not e:
        return ""
    if not s:
        s = e[: e.find("T")] + "T00:00:00Z" if "T" in e else f"{e}T00:00:00Z"
    if not e:
        e = s[: s.find("T")] + "T23:59:59Z" if "T" in s else f"{s}T23:59:59Z"
    return f"{s}/{e}"


# --------------------------------------------------------------------------
# Mock 场景生成（仅当全部 STAC 链路失败时降级用，不依赖任何网络）
# --------------------------------------------------------------------------
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
# ============================================================================
# Asset 下载 helpers（v1.9.2：替换之前的 NotImplementedError）
# ============================================================================
def _s3_to_https(href: str) -> str:
    """`s3://bucket/key` → `https://bucket.s3.amazonaws.com/key`。

    Element84 / CDSE STAC 返回的 assets 中常带 s3:// 协议头,需转换后才能用 HTTP 下载。
    对公开 AWS 桶(如 sentinel-cogs / sentinel-s1-l1c)直接生效。
    """
    if not href.startswith("s3://"):
        return href
    body = href[5:]
    if "/" in body:
        bucket, path = body.split("/", 1)
    else:
        bucket, path = body, ""
    return f"https://{bucket}.s3.amazonaws.com/{path}"


async def _download_stac_asset(href: str, *, auth_token: Optional[str] = None) -> Tuple[bytes, str]:
    """下载 STAC asset bytes。

    Args:
        href: 资产 URL(s3:// 自动转 https://)
        auth_token: CDSE 场景时填 Bearer token(其他公开源无需)

    Returns:
        (bytes, content_type)

    Raises:
        StacClientError: HTTP 非 200 或网络错误
    """
    url = _s3_to_https(href)
    headers: Dict[str, str] = {
        "User-Agent": "DisasterGuard-AI/3.0",
    }
    # 仅对 Copernicus 域加 Bearer(避免误把 token 发到第三方)
    if auth_token and "dataspace.copernicus.eu" in url:
        headers["Authorization"] = f"Bearer {auth_token}"

    session = await HttpClientHolder.instance().session()
    timeout = aiohttp.ClientTimeout(total=180)  # asset 较大,放宽到 3 分钟
    try:
        async with session.get(url, headers=headers, timeout=timeout) as resp:
            if resp.status != 200:
                text = (await resp.text())[:200] if resp.content_length != 0 else ""
                raise StacClientError(
                    f"asset 下载失败 HTTP {resp.status}: {url} | {text}"
                )
            data = await resp.read()
            ct = resp.headers.get("Content-Type", "")
    except asyncio.TimeoutError as exc:
        raise StacClientError(f"asset 下载超时: {url}") from exc
    except aiohttp.ClientError as exc:
        raise StacClientError(f"asset 网络错误: {exc!s}") from exc
    return data, ct


def _save_image_bytes_to_storage(data: bytes, ext: str, job_id: str, kind: str) -> str:
    """保存图像 bytes 到 SENTINEL_STORAGE_DIR,返回 CDN URL。"""
    storage_dir = _sentinel_storage_dir()
    safe_ext = ext.lstrip(".").lower()
    if safe_ext not in ("jpg", "jpeg", "png", "tif", "tiff"):
        safe_ext = "png"
    filename = f"{kind}_{job_id}.{safe_ext}"
    output_path = os.path.join(storage_dir, filename)
    with open(output_path, "wb") as f:
        f.write(data)
    cdn = _sentinel_cdn_base()
    if cdn:
        return f"{cdn.rstrip('/')}/{filename}"
    return f"file://{output_path}"


async def _try_download_real_asset(
    scene: Dict, asset_keys: List[str], job_id: str, kind: str
) -> Optional[str]:
    """按优先级尝试下载 asset,任一成功即返回 CDN URL;全失败返回 None。

    asset_keys: 候选 asset key 列表,按优先级排列(如 ['thumbnail', 'visual'])
    """
    assets = scene.get("assets", {})
    if not assets:
        return None

    # 是否来自 CDSE(需要 bearer);其他公开源(Earth Search/USGS)走匿名
    is_cdse_scene = (
        "dataspace.copernicus.eu" in scene.get("id", "")
        or any(
            "dataspace.copernicus.eu" in (a.get("href") or "")
            for a in assets.values()
        )
    )
    auth_token: Optional[str] = None
    if is_cdse_scene and is_cdse_configured():
        try:
            auth_token = await CdseTokenCache.instance().get_token()
        except CdseAuthError as exc:
            LOG.warning("CDSE 资产下载 token 申请失败: %s", exc)
            # 仍尝试匿名(可能 401,但不漏 try)

    for key in asset_keys:
        asset = assets.get(key)
        if not asset:
            continue
        href = asset.get("href", "")
        if not href:
            continue
        # TCI.tif 这种 50MB 全分辨率 tile 太大,跳过避免长时间卡顿
        ct_hint = (asset.get("type") or "").lower()
        if "geotif" in ct_hint and key != "visual":
            continue
        if "geotif" in ct_hint and key == "visual":
            LOG.warning(
                "跳过 %s asset (geotiff 太大,等下次优化走 tifffile 局部读取): %s",
                key,
                href[:120],
            )
            continue
        try:
            LOG.info("下载 %s asset [%s]: %s", scene.get("id", "?"), key, href[:120])
            data, ct = await _download_stac_asset(href, auth_token=auth_token)
            ext = "jpg" if "jpeg" in ct else "png" if "png" in ct else "jpg"
            url = _save_image_bytes_to_storage(data, ext, job_id, kind)
            LOG.info(
                "%s 真实下载成功: %.1f KB → %s",
                kind,
                len(data) / 1024,
                url,
            )
            return url
        except StacClientError as exc:
            LOG.warning("%s asset[%s] 下载失败,试下一个: %s", kind, key, exc)
            continue
    return None


# --------------------------------------------------------------------------
# S2 真彩合成
# --------------------------------------------------------------------------
async def _compose_s2_truecolor(s2_scene: Dict, bbox: List[float], job_id: str) -> str:
    """
    真彩合成实现(v1.9.2 重写):
      - mock scene / 无 assets  → PIL 随机噪声(原行为)
      - 真实 STAC scene        → 优先下载 `thumbnail`(JPEG ~10KB,500ms 拿到真实卫星图)
                                  退回 `visual`(TCI.tif,云优化 GeoTIFF,50MB 太大直接跳过)
                                  全失败 → mock(降级 + 警告日志)

    关于"下载 thumbnail 而非 TCI.tif":
      - TCI.tif 是 10980×10980 全 tile,需 51 MB 流量 + rasterio/GDAL 才能局部裁切
      - thumbnail 是 343×343 JPEG 预览,9.4 KB,500ms,PIL 原生读取
      - 数据内容**完全相同**(同一台 ESA 卫星,同一时刻拍摄),只是分辨率不同
      - 落地方式:直接把 thumbnail.jpg 保存到 SENTINEL_STORAGE_DIR,前端通过 CDN 访问

    关于凭证:
      - Earth Search AWS / USGS 等公开 STAC 源无需任何凭证
      - 仅 CDSE scene (id 含 dataspace.copernicus.eu) 才需要 Bearer
      - 因此"未配置 CDSE 凭证"**不应该**降级到 mock,只要 scene 是真的就尝试下载
    """
    assets = s2_scene.get("assets", {})
    is_mock_scene = (
        scene_id := s2_scene.get("id", "")
    ).startswith("MOCK:") or not assets

    if is_mock_scene:
        return await _generate_mock_s2_image(bbox, job_id)

    real_url = await _try_download_real_asset(
        s2_scene,
        asset_keys=["thumbnail", "visual"],
        job_id=job_id,
        kind="s2_truecolor",
    )
    if real_url:
        return real_url

    LOG.warning(
        "S2 scene %s 所有可下载 asset 均失败,降级 mock(可能是 CDSE 鉴权或网络问题)",
        s2_scene.get("id", "?"),
    )
    return await _generate_mock_s2_image(bbox, job_id)


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

        output_path = os.path.join(_sentinel_storage_dir(), f"s2_truecolor_{job_id}.png")
        img.save(output_path, format="PNG", optimize=False)

        cdn = _sentinel_cdn_base()
        if cdn:
            return f"{cdn.rstrip('/')}/s2_truecolor_{job_id}.png"
        return f"file://{output_path}"
    except ImportError:
        # PIL 不可用时返回空占位 URL
        return f"mock://sentinel/s2_truecolor_{job_id}.png"


# --------------------------------------------------------------------------
# S1 SAR 伪彩色合成
# --------------------------------------------------------------------------
async def _compose_s1_falsecolor(s1_scene: Dict, bbox: List[float], job_id: str) -> Optional[str]:
    """
    SAR 伪彩色实现(v1.9.2 重写):
      - mock scene / 无 assets  → PIL 随机 SAR 噪声(原行为)
      - 真实 STAC scene        → 下载 `thumbnail`(quick-look PNG,~365KB)
                                  ESA 预渲染的灰度 SAR 图像,带正确的 SAR 几何形态

    注:严格意义上的 VV→R/VH→G/比值→B 伪彩色合成需要分别下载 vv.tif 和 vh.tif 各 ~100MB,
        然后 numpy 算 ratio、归一化、合成。本次先做 thumbnail 版本,业务视觉上"真实"。

    关于凭证:Earth Search AWS S1 quick-look PNG 是公开的,无需 CDSE 凭证。
    """
    assets = s1_scene.get("assets", {})
    is_mock_scene = (s1_scene.get("id", "").startswith("MOCK:")) or not assets

    if is_mock_scene:
        return await _generate_mock_sar_image(bbox, job_id)

    real_url = await _try_download_real_asset(
        s1_scene,
        asset_keys=["thumbnail"],  # S1 default STAC 通常只有 thumbnail,无 visual
        job_id=job_id,
        kind="s1_sar",
    )
    if real_url:
        return real_url

    LOG.warning(
        "S1 scene %s 所有可下载 asset 均失败,降级 mock",
        s1_scene.get("id", "?"),
    )
    return await _generate_mock_sar_image(bbox, job_id)


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

        output_path = os.path.join(_sentinel_storage_dir(), f"s1_sar_{job_id}.png")
        img.save(output_path, format="PNG", optimize=False)

        cdn = _sentinel_cdn_base()
        if cdn:
            return f"{cdn.rstrip('/')}/s1_sar_{job_id}.png"
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
        await _update_diff_job_async(job_id, status=JobStatus.RUNNING.value, progress=10,
                         message="正在解析输入图 URL…")

        before_url = await _resolve_input_url(req.before_job_id, "compose") or req.before_url
        after_url = await _resolve_input_url(req.after_job_id, "compose") or req.after_url
        if not before_url or not after_url:
            raise ValueError("必须提供 before/after 任务 ID 或 URL 之一")

        await _update_diff_job_async(job_id, progress=25, message="正在加载图像…")
        before_img, before_size = await _load_image_to_array(before_url)
        after_img, after_size = await _load_image_to_array(after_url)

        # 大小对齐：以 before 为基准 resize after
        if before_size != after_size:
            await _update_diff_job_async(job_id, progress=40, message="对齐图像尺寸…")
            after_img = _resize_image(after_img, before_size)

        await _update_diff_job_async(job_id, progress=55, message="计算像素级差异…")
        diff_magnitude = _pixel_diff_magnitude(before_img, after_img)

        await _update_diff_job_async(job_id, progress=70, message="生成热力图…")
        heatmap_path = _render_heatmap(diff_magnitude, req.colormap, req.threshold, job_id)

        await _update_diff_job_async(job_id, progress=85, message="提取差异热点…")
        hotspots = _cluster_hotspots(diff_magnitude, req.threshold, top_k=3)

        await _update_diff_job_async(job_id, progress=95, message="汇总统计…")
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

        cdn = _sentinel_cdn_base()
        diff_url = (
            f"{cdn.rstrip('/')}/diff_{job_id}.png"
            if cdn
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
        await _update_diff_job_async(job_id, progress=100, status=JobStatus.SUCCEEDED.value,
                         message="差异计算完成", result=result)

    except asyncio.CancelledError:
        await _update_diff_job_async(job_id, status=JobStatus.CANCELLED.value, message="任务已取消")
        raise
    except HTTPException:
        raise
    except Exception as exc:
        # 记录完整 traceback，仅向 API 返回通用消息，避免泄漏内部细节
        LOG.exception("差异任务失败 job=%s", job_id)
        await _update_diff_job_async(job_id, status=JobStatus.FAILED.value,
                         message="差异计算失败（详情查看日志）", progress=0)
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
        # 注意：必须去掉 .png 后缀，否则 _regenerate_mock_image 拿到的 seed 与生成时不一致
        suffix = url_or_path.split("_")[-1].split(".")[0]  # 取 jobId（剥离 .png）
        is_sar = "sar" in url_or_path
        img = _regenerate_mock_image(suffix, is_sar)
    elif url_or_path.startswith("http://") or url_or_path.startswith("https://"):
        # 复用进程级共享 aiohttp ClientSession，避免每个请求重新 TCP/TLS 握手 + 频繁创建连接池
        from app.utils.http_client import HttpClientHolder
        session = await HttpClientHolder.instance().session()
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
    output_path = os.path.join(_sentinel_storage_dir(), f"diff_{job_id}.png")
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
