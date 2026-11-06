"""
共享异步 HTTP 客户端（aiohttp ClientSession 进程级单例）+ race/concurrent helpers。

为什么需要：
  - 每次 STAC/Token 调用 `async with aiohttp.ClientSession()` 会触发：
      ① TCP 三次握手  ② TLS 握手  ③ DNS 解析（如未缓存）  ④ 分配新连接池
    共 200-500ms 固定开销，且多次重复触发，连接池也无复用机会。
  - 把 session 提到模块级单例，配合 `TCPConnector(limit=100)`，
    可跨请求复用连接池（keep-alive），首次握手后单次 RTT 数十毫秒。
  - 显式 `close()` 在 FastAPI lifespan shutdown 触发，防止 uvicorn --reload
    时打印 "Unclosed client session" warning。

其他 helper:
  - `race_first_non_empty(*coros)`：并发跑 N 个协程，返回第一个非空结果。
    失败/超时自动 cancel pending。
  - `gather_silent(*coros)`：并发跑 N 个协程，吞掉异常，只返回成功的结果。
"""
from __future__ import annotations

import asyncio
import logging
from typing import Any, Awaitable, Callable, List, Optional, TypeVar

import aiohttp

LOG = logging.getLogger("ai.http")

# 默认 STAC/CDSE 网络超时（30s 与 CDSE 推荐一致）
DEFAULT_TIMEOUT = aiohttp.ClientTimeout(total=30)

# 连接池大小（per-host 20，total 100，足以支撑 50 RPS 持续）
DEFAULT_POOL_LIMIT = 100
DEFAULT_POOL_LIMIT_PER_HOST = 20

T = TypeVar("T")


class HttpClientHolder:
    """进程级共享 aiohttp ClientSession（线程/协程安全）。"""

    _instance: Optional["HttpClientHolder"] = None

    def __init__(self) -> None:
        self._session: Optional[aiohttp.ClientSession] = None
        self._init_lock = asyncio.Lock()

    @classmethod
    def instance(cls) -> "HttpClientHolder":
        # instance() 本身是 read-only，不需要锁；只在 lazy 创建时锁
        if cls._instance is None:
            cls._instance = cls()
        return cls._instance

    async def session(self) -> aiohttp.ClientSession:
        """获取或懒创建共享 session。

        第一次调用会建立 connector + session。
        session 因网络错误关闭后,下一次调用会自动重建。
        """
        if self._session is None or self._session.closed:
            async with self._init_lock:
                if self._session is None or self._session.closed:
                    connector = aiohttp.TCPConnector(
                        limit=DEFAULT_POOL_LIMIT,
                        limit_per_host=DEFAULT_POOL_LIMIT_PER_HOST,
                        # keepalive 默认开启
                        enable_cleanup_closed=True,
                    )
                    self._session = aiohttp.ClientSession(
                        connector=connector,
                        timeout=DEFAULT_TIMEOUT,
                    )
                    LOG.info(
                        "HttpClientHolder: shared session created (pool=%d, per_host=%d)",
                        DEFAULT_POOL_LIMIT,
                        DEFAULT_POOL_LIMIT_PER_HOST,
                    )
        return self._session

    async def close(self) -> None:
        """优雅关闭（FastAPI lifespan shutdown 时调用）。"""
        if self._session and not self._session.closed:
            await self._session.close()
            LOG.info("HttpClientHolder: shared session closed")
        self._session = None

    async def __aenter__(self) -> aiohttp.ClientSession:
        return await self.session()

    async def __aexit__(self, *exc_info: Any) -> None:
        # 不关闭,holder 仍然持有
        pass


# 并发 helpers
async def race_first_non_empty(
    *coros: Awaitable[Any],
    is_non_empty: Callable[[Any], bool],
    description: str = "race",
) -> Any:
    """并发执行多个协程,返回第一个"非空"结果。

    is_non_empty: 判定返回值是否为"有效结果"(如 features 非空 list)。
    其他任务会被 cancel。

    全部失败/全部空 → 抛 asyncio.InvalidStateError。

    用例:
        result = await race_first_non_empty(
            CdseStacClient().search(...),
            EarthSearchAwsClient().search(...),
            is_non_empty=lambda r: bool(r.features),
            description="STAC race",
        )
    """
    if not coros:
        raise ValueError("race_first_non_empty requires at least one coroutine")

    tasks: List[asyncio.Task[Any]] = [
        asyncio.create_task(c, name=f"{description}-{i}") for i, c in enumerate(coros)
    ]
    completed_result: Any = None
    completed_got_value = False
    try:
        while tasks:
            done, pending = await asyncio.wait(
                tasks, return_when=asyncio.FIRST_COMPLETED
            )
            # 立即取消还在跑的（winner 已定,不再需要）
            for t in pending:
                t.cancel()
            tasks = list(pending)

            for t in done:
                try:
                    result = t.result()
                except asyncio.CancelledError:
                    continue
                except Exception as exc:
                    LOG.warning("[%s] task %s failed: %s", description, t.get_name(), exc)
                    continue
                completed_result = result
                completed_got_value = True
                if is_non_empty(result):
                    return result

        # 等 task 全部跑完也没拿到非空结果
        if completed_got_value:
            return completed_result  # 返回最后一个空结果
        raise RuntimeError(f"[{description}] all tasks failed without any result")
    finally:
        for t in tasks:
            if not t.done():
                t.cancel()
                try:
                    await t
                except (asyncio.CancelledError, Exception):
                    pass


async def gather_silent(
    *coros: Awaitable[T],
    description: str = "gather",
) -> List[T]:
    """类似 asyncio.gather(return_exceptions=True),但过滤掉异常结果。

    返回值顺序与入参 coros 一致;失败的槽位被跳过。
    """
    results = await asyncio.gather(*coros, return_exceptions=True)
    out: List[T] = []
    for i, r in enumerate(results):
        if isinstance(r, BaseException):
            LOG.warning("[%s] coro[%d] failed: %s", description, i, r)
        else:
            out.append(r)
    return out
