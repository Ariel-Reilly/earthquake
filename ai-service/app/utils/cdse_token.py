"""
Copernicus Data Space OAuth2 client_credentials token cache.

负责：
  - 读取 COPERNICUS_CLIENT_ID / COPERNICUS_CLIENT_SECRET（来自 .env，永不硬编码）
  - 内存 cache，提前 60s 自动 refresh，避免热点过期抖动
  - asyncio.Lock 保证并发安全
  - 凭证缺失时给出明确的中文报错，定位到配置入口

使用：
    from app.utils.cdse_token import CdseTokenCache, is_cdse_configured

    if is_cdse_configured():
        token = await CdseTokenCache.instance().get_token()
        headers = {"Authorization": f"Bearer {token}"}
"""
from __future__ import annotations

import asyncio
import logging
import os
import time
from typing import Optional

import aiohttp

from app.utils.http_client import HttpClientHolder

LOG = logging.getLogger("ai.cdse_token")

# 默认 token 端点（CDSE Keycloak realm），可用 COPERNICUS_TOKEN_URL 覆盖
_TOKEN_URL_DEFAULT = (
    "https://identity.dataspace.copernicus.eu/auth/realms/CDSE"
    "/protocol/openid-connect/token"
)

# 在 token 真正过期前多少秒就开始刷新，避免请求 1s 前过期导致 401
_REFRESH_SKEW_SECONDS = 60

# token 接口应当秒回,15s 足够（用共享 session 时仍可短超时）
_TOKEN_HTTP_TIMEOUT = aiohttp.ClientTimeout(total=15)


class CdseAuthError(RuntimeError):
    """Copernicus Data Space 凭证 / 网络异常。"""


class CdseTokenCache:
    """进程级单例 token 缓存。线程（协程）安全。

    并发模型（重要）：
      - 所有 get_token() 调用都先 async with self._lock 排队
      - 第一个进入的检查到 token 临近过期/缺失,执行 refresh
      - 后到的直接返回刚刷好的 token
      ⇒ 隐式单飞：N 个并发请求只触发 1 次 OAuth POST
    """

    _instance: Optional["CdseTokenCache"] = None

    def __init__(self) -> None:
        self._token: Optional[str] = None
        self._expires_at_monotonic: float = 0.0
        self._lock = asyncio.Lock()

    @classmethod
    def instance(cls) -> "CdseTokenCache":
        if cls._instance is None:
            cls._instance = cls()
        return cls._instance

    # 对外 API
    async def get_token(self, *, force_refresh: bool = False) -> str:
        """返回当前可用 Bearer token；必要时刷新。

        force_refresh=True 用于：rotate secret 后立即失效旧 token。
        """
        cid, sec = self._credentials()
        if not cid or not sec:
            raise CdseAuthError(
                "Copernicus Data Space 凭证未配置。请在 .env 中设置 "
                "COPERNICUS_CLIENT_ID 和 COPERNICUS_CLIENT_SECRET "
                "（从 https://dataspace.copernicus.eu/profile/applications 创建的 "
                "Confidential OAuth client 复制）。"
            )
        async with self._lock:
            now = time.monotonic()
            if (
                not force_refresh
                and self._token
                and now < self._expires_at_monotonic - _REFRESH_SKEW_SECONDS
            ):
                return self._token
            await self._refresh_locked(cid, sec)
            assert self._token is not None
            return self._token

    async def invalidate(self) -> None:
        """清空缓存（用于 rotate secret 后强制下次重新申请）。"""
        async with self._lock:
            self._token = None
            self._expires_at_monotonic = 0.0
            LOG.info("CDSE token cache invalidated")

    # 内部
    @staticmethod
    def _credentials() -> tuple[str, str]:
        return (
            os.environ.get("COPERNICUS_CLIENT_ID", "").strip(),
            os.environ.get("COPERNICUS_CLIENT_SECRET", "").strip(),
        )

    async def _refresh_locked(self, client_id: str, client_secret: str) -> None:
        token_url = os.environ.get("COPERNICUS_TOKEN_URL", _TOKEN_URL_DEFAULT).strip()
        payload = {
            "grant_type": "client_credentials",
            "client_id": client_id,
            "client_secret": client_secret,
        }
        session = await HttpClientHolder.instance().session()
        try:
            async with session.post(
                token_url,
                data=payload,
                timeout=_TOKEN_HTTP_TIMEOUT,
            ) as resp:
                text = await resp.text()
                if resp.status != 200:
                    # 截断响应避免泄露内部细节
                    raise CdseAuthError(
                        f"Copernicus token 申请失败（HTTP {resp.status}）："
                        f"{text[:200]}"
                    )
                data = await resp.json()
        except aiohttp.ClientError as exc:
            raise CdseAuthError(
                f"Copernicus token 端点网络错误：{exc!s}（token_url={token_url}）"
            ) from exc
        except asyncio.TimeoutError as exc:
            raise CdseAuthError(f"Copernicus token 端点超时：{token_url}") from exc

        access_token = data.get("access_token")
        if not access_token:
            raise CdseAuthError(
                f"Copernicus token 响应缺少 access_token：{str(data)[:200]}"
            )
        # CDSE 默认返回 expires_in=600；按需 refresh，保守处理
        expires_in = int(data.get("expires_in", 600))
        self._token = access_token
        self._expires_at_monotonic = time.monotonic() + expires_in
        LOG.info("CDSE token 已刷新，expires_in=%ds", expires_in)


def is_cdse_configured() -> bool:
    """凭证是否存在。用于 STAC 客户端链决定是否尝试 CDSE 主链。"""
    cid, sec = CdseTokenCache._credentials()
    return bool(cid) and bool(sec)
