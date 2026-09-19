"""
STAC 客户端抽象 + 故障转移链。

调用顺序（可在 search_with_fallback() 中调整）：
  ① Copernicus Data Space(CDSE) — stac.dataspace.copernicus.eu/v1
       OAuth bearer,官方数据源,Sentinel-1 GRD / Sentinel-2 L2A / Sentinel-3 / DEM
  ② Element84 Earth Search AWS — earth-search.aws.element84.com/v1
       免认证,Sentinel-2 L2A(由 AWS 镜像,内容与 CDSE 一致但 URL 不同)
  ③ USGS Earthdata CMR — cmr.earthdata.nasa.gov/stac/LPCLOUD
       免认证,Landsat 8/9 Collection 2 / Sentinel-2(部分)

如果全部失败,调用方应降级到 mock 生成(已在 routers/sentinel.py 保留此路径)。
"""
from __future__ import annotations

import logging
import os
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

import aiohttp

from app.utils.cdse_token import CdseAuthError, CdseTokenCache, is_cdse_configured
from app.utils.http_client import (
    DEFAULT_TIMEOUT,
    HttpClientHolder,
    race_first_non_empty,
)

LOG = logging.getLogger("ai.stac")


@dataclass
class StacSearchResult:
    provider: str
    features: List[Dict[str, Any]] = field(default_factory=list)
    note: Optional[str] = None
    request_id: Optional[str] = None


class StacClientError(RuntimeError):
    """STAC 客户端调用失败（不含凭证错误,凭证错走 CdseAuthError）。"""


# ============================================================================
# 客户端实现
# ============================================================================
class CdseStacClient:
    """Copernicus Data Space STAC (新版,2024+ OAuth-bearer)。

    旧版 sh.dataspace.copernicus.eu/api/v1/catalog/1.0.0/search 已被官方
    迁移废弃;新端点 stac.dataspace.copernicus.eu/v1/search 走 Keycloak OAuth。
    """

    BASE_URL = "https://stac.dataspace.copernicus.eu/v1/"

    def __init__(self, base_url: Optional[str] = None):
        self.base_url = (
            (base_url or os.environ.get("COPERNICUS_STAC_URL") or self.BASE_URL)
            .rstrip("/")
            + "/"
        )

    async def search(
        self,
        bbox: List[float],
        datetime_range: str,
        collections: List[str],
        query: Optional[Dict[str, Any]] = None,
        limit: int = 1,
        sortby: Optional[List[Dict[str, str]]] = None,
    ) -> StacSearchResult:
        token = await CdseTokenCache.instance().get_token()
        body: Dict[str, Any] = {
            "bbox": bbox,
            "datetime": datetime_range,
            "collections": collections,
            "limit": limit,
        }
        if query:
            body["query"] = query
        if sortby:
            body["sortby"] = sortby

        url = f"{self.base_url}search"
        headers = {
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            "Accept": "application/geo+json",
        }
        session = await HttpClientHolder.instance().session()
        try:
            async with session.post(url, json=body, headers=headers) as resp:
                text = await resp.text()
                if resp.status in (401, 403):
                    raise CdseAuthError(
                        f"CDSE STAC 鉴权失败（HTTP {resp.status}），"
                        f"可能是 token 过期或 scope 不够：{text[:200]}"
                    )
                if resp.status != 200:
                    raise StacClientError(
                        f"CDSE STAC 搜索失败（HTTP {resp.status}）：{text[:300]}"
                    )
                data = await resp.json()
        except aiohttp.ClientError as exc:
            raise StacClientError(f"CDSE STAC 网络错误：{exc!s}") from exc

        return StacSearchResult(
            provider="copernicus-dataspace",
            features=data.get("features", []),
            request_id=data.get("id"),
        )


class EarthSearchAwsClient:
    """Element84 Earth Search AWS STAC(Sentinel-2 L2A 镜像,免认证)。

    数据集与 CDSE 一致(都是 Element84 索引),但走 AWS CloudFront/CDN,
    国内访问偶尔比 CDSE 快。出现 CDSE 401/超时/限流时自动 fallback。
    """

    BASE_URL = "https://earth-search.aws.element84.com/v1/"

    async def search(
        self,
        bbox: List[float],
        datetime_range: str,
        collections: List[str],
        query: Optional[Dict[str, Any]] = None,
        limit: int = 1,
        sortby: Optional[List[Dict[str, str]]] = None,
    ) -> StacSearchResult:
        body: Dict[str, Any] = {
            "bbox": bbox,
            "datetime": datetime_range,
            "collections": collections,
            "limit": limit,
        }
        if query:
            body["query"] = query
        if sortby:
            body["sortby"] = sortby

        url = f"{self.BASE_URL}search"
        session = await HttpClientHolder.instance().session()
        try:
            async with session.post(url, json=body) as resp:
                text = await resp.text()
                if resp.status != 200:
                    raise StacClientError(
                        f"Earth Search STAC 失败（HTTP {resp.status}）：{text[:200]}"
                    )
                data = await resp.json()
        except aiohttp.ClientError as exc:
            raise StacClientError(f"Earth Search 网络错误：{exc!s}") from exc

        return StacSearchResult(
            provider="earth-search-aws",
            features=data.get("features", []),
            note="CDSE fallback：Element84 在 AWS 的 Sentinel-2 L2A 镜像",
        )


class UsgsEarthdataCmClient:
    """NASA Earthdata CMR STAC(Landsat 8/9 + Sentinel-2 部分,免认证)。

    适合历史对比(Landsat 1972+ 长时间序列)和 CDSE/Earth Search 都挂时
    的最后一根公开 STAC 救命稻草。
    """

    BASE_URL = "https://cmr.earthdata.nasa.gov/stac/LPCLOUD/"

    async def search(
        self,
        bbox: List[float],
        datetime_range: str,
        collections: List[str],
        query: Optional[Dict[str, Any]] = None,
        limit: int = 1,
        sortby: Optional[List[Dict[str, str]]] = None,
    ) -> StacSearchResult:
        body: Dict[str, Any] = {
            "bbox": bbox,
            "datetime": datetime_range,
            "collections": collections,
            "limit": limit,
        }
        if query:
            body["query"] = query
        if sortby:
            body["sortby"] = sortby

        url = f"{self.BASE_URL}search"
        session = await HttpClientHolder.instance().session()
        try:
            async with session.post(url, json=body) as resp:
                text = await resp.text()
                if resp.status != 200:
                    raise StacClientError(
                        f"USGS STAC 失败（HTTP {resp.status}）：{text[:200]}"
                    )
                data = await resp.json()
        except aiohttp.ClientError as exc:
            raise StacClientError(f"USGS STAC 网络错误：{exc!s}") from exc

        return StacSearchResult(
            provider="usgs-earthdata",
            features=data.get("features", []),
            note="CDSE fallback：NASA Earthdata CMR Landsat/Sentinel 镜像",
        )


# 默认 fallback 顺序：CDSE → Earth Search → USGS
DEFAULT_CLIENT_FACTORIES = [CdseStacClient, EarthSearchAwsClient, UsgsEarthdataCmClient]

# Earth Search + USGS 并发时的 lower bound（防止单个空 result 抢先）
_RACE_TIEBREAK_ORDER = ["copernicus-dataspace", "earth-search-aws", "usgs-earthdata"]


async def search_with_fallback(
    bbox: List[float],
    datetime_range: str,
    collections: List[str],
    query: Optional[Dict[str, Any]] = None,
    limit: int = 1,
    sortby: Optional[List[Dict[str, str]]] = None,
    *,
    skip_cdse: bool = False,
    client_factories: Optional[List[type]] = None,
) -> StacSearchResult:
    """STAC 搜索 + 智能 fallback。

    策略（v1.9.1 性能优化）：
      Stage 1 — 若 CDSE 凭证可用且未 skip,**单独跑 CDSE（首选数据源）**。
                CDSE 通则直接返回;CDSE 抛错/超时/空 features 才进入 Stage 2。
      Stage 2 — Earth Search + USGS 并发 race:
                两者同时发起,谁先返回非空 features 就用谁。
                节省 ~3-6 秒（对比 v1.9.0 串行 fallback）。
    """
    factories = client_factories or DEFAULT_CLIENT_FACTORIES
    if skip_cdse or not is_cdse_configured():
        LOG.info(
            "跳过 CDSE 单独阶段(skip=%s, configured=%s),直接并发 fallback",
            skip_cdse,
            is_cdse_configured(),
        )
    else:
        # Stage 1: 先独跑 CDSE（带较长超时,尊重"数据源优先级"）
        try:
            cdse = CdseStacClient()  # 用默认 base
            # 如果传入 factories 列表中没有 CdseStacClient,跳过
            if any(issubclass(f, CdseStacClient) for f in factories):
                LOG.info("Stage 1: 单独跑 CDSE(优先数据源)")
                cdse_coro = cdse.search(bbox, datetime_range, collections, query, limit, sortby)
                result = await cdse_coro
                if result.features:
                    LOG.info("CDSE 命中 %d features,直接返回", len(result.features))
                    return result
                LOG.info("CDSE 返回 0 features,进入 Stage 2 race")
        except (CdseAuthError, StacClientError) as exc:
            LOG.warning("CDSE 失败,进入 Stage 2 race: %s", exc)
        except Exception as exc:
            LOG.exception("CDSE 意外错误(开发期应捕获): %s", exc)

    # Stage 2: Earth Search + USGS race(并发)
    race_clients = [f() for f in factories if not issubclass(f, CdseStacClient)]
    if not race_clients:
        raise StacClientError("无可用 STAC 客户端(全部已被 skip)")

    coros = [
        c.search(bbox, datetime_range, collections, query, limit, sortby)
        for c in race_clients
    ]

    def _is_non_empty(r: StacSearchResult) -> bool:
        return bool(r.features)

    try:
        winner = await race_first_non_empty(
            *coros,
            is_non_empty=_is_non_empty,
            description="STAC race",
        )
        return winner
    except RuntimeError:
        # race 全部空：再单独跑一次 USGS（顺序兜底，retry 概念）
        last_exc: Optional[Exception] = None
        for client in race_clients:
            try:
                r = await client.search(bbox, datetime_range, collections, query, limit, sortby)
                if r.features:
                    return r
            except (CdseAuthError, StacClientError) as exc:
                last_exc = exc
                continue
        raise StacClientError(
            f"所有 STAC 客户端都未返回数据。客户端："
            f"{[type(c).__name__ for c in race_clients]}。"
            f"最近错误：{last_err_msg(last_exc)}"
        )


def last_err_msg(exc: Optional[BaseException]) -> str:
    if exc is None:
        return "无"
    return f"{type(exc).__name__}: {exc!s}"
