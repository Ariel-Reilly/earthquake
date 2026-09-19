"""
CDSE / STAC fallback 链路端到端验证脚本。

用法（先启动 ai-service venv，激活 .env）：
    cd ai-service
    .venv\\Scripts\\Activate.ps1
    python -m ai_service.tools.test_cdse \\
        --aoi 104.07,30.67,104.15,30.75 \\
        --days 30 \\
        --cloud 15

或（推荐，pytest 风格）:
    cd ai-service
    python -m tools.test_cdse --aoi 104.07,30.67,104.15,30.75

行为：
  1) 如果 COPERNICUS_CLIENT_ID / SECRET 配置了 → 走 CDSE → Earth Search → USGS 链
  2) 如果没配置 → 仍然跑 fallback 链路,只跳过 CDSE,验证公开源可用
  3) 输出每个 provider 的尝试结果 + 最终命中的场景元数据 + asset URLs

退出码：
  0  命中至少 1 个 S2 + 1 个 S1 场景（业务可用）
  1  命令行参数错误
  2  STAC 链路全部失败（凭证或网络问题）
  3  STAC 链路跑通但 AOI 内无云量符合要求的数据
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
from pathlib import Path
from typing import List, Optional, Tuple


def _force_utf8_stdout() -> None:
    """Windows 默认 GBK 编码无法输出 ▶✓✗ 等 Unicode,强制 UTF-8。
    Linux/macOS 默认就是 UTF-8,不影响。
    """
    for stream_name in ("stdout", "stderr"):
        stream = getattr(sys, stream_name, None)
        if stream is None:
            continue
        try:
            # Python 3.7+ reconfigure
            stream.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]
        except (AttributeError, OSError):
            # 老 Python 或已关闭 stream,跳过
            pass


_force_utf8_stdout()


def _bootstrap_dotenv() -> None:
    """从 ai-service/.env 或项目根 .env 注入环境变量（仅当变量未设置时）。"""
    try:
        from dotenv import load_dotenv
    except ImportError:
        return
    candidates = [
        Path(__file__).resolve().parent.parent / ".env",  # ai-service/.env
        Path(__file__).resolve().parent.parent.parent / ".env",  # repo root
    ]
    for p in candidates:
        if p.exists():
            load_dotenv(p, override=False)


_bootstrap_dotenv()


# 现在才导入 utils(它们会读 env)
from app.utils.cdse_token import CdseAuthError, is_cdse_configured  # noqa: E402
from app.utils.stac_clients import (  # noqa: E402
    CdseStacClient,
    EarthSearchAwsClient,
    StacClientError,
    StacSearchResult,
    UsgsEarthdataCmClient,
    search_with_fallback,
)


def parse_aoi(s: str) -> List[float]:
    """--aoi 'lon_min,lat_min,lon_max,lat_max' → [lon_min, lat_min, lon_max, lat_max]"""
    parts = [x.strip() for x in s.split(",")]
    if len(parts) != 4:
        raise argparse.ArgumentTypeError(f"AOI 必须 4 个数字(逗号分隔),收到 {len(parts)}")
    try:
        vals = [float(x) for x in parts]
    except ValueError as exc:
        raise argparse.ArgumentTypeError(f"AOI 解析失败:{exc}")
    lon_min, lat_min, lon_max, lat_max = vals
    if not (-180 <= lon_min <= 180 and -180 <= lon_max <= 180):
        raise argparse.ArgumentTypeError("AOI 经度必须在 [-180, 180]")
    if not (-90 <= lat_min <= 90 and -90 <= lat_max <= 90):
        raise argparse.ArgumentTypeError("AOI 纬度必须在 [-90, 90]")
    if lon_min >= lon_max or lat_min >= lat_max:
        raise argparse.ArgumentTypeError("AOI 必须 min < max")
    return vals


def parse_args(argv: Optional[List[str]] = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(
        description="DisasterGuard CDSE / STAC fallback 验证工具",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例：
  python -m tools.test_cdse --aoi 104.07,30.67,104.15,30.75
  python -m tools.test_cdse --aoi 107.0,30.4,107.4,30.8 --days 60 --cloud 20 --skip-cdse
        """,
    )
    p.add_argument(
        "--aoi",
        type=parse_aoi,
        required=True,
        help="AOI 边界框 [minLon, minLat, maxLon, maxLat]",
    )
    p.add_argument("--days", type=int, default=30, help="回溯天数(默认 30)")
    p.add_argument("--cloud", type=int, default=15, help="最大云量百分比(默认 15)")
    p.add_argument(
        "--start",
        type=str,
        default=None,
        help="起始日期 YYYY-MM-DD(默认 today - days)",
    )
    p.add_argument(
        "--end",
        type=str,
        default=None,
        help="结束日期 YYYY-MM-DD(默认 today)",
    )
    p.add_argument(
        "--skip-cdse",
        action="store_true",
        help="跳过 CDSE,只测 Earth Search + USGS fallback(用于验证降级链路)",
    )
    p.add_argument(
        "--print-assets",
        action="store_true",
        help="打印每个命中断面的 asset 列表(诊断用)",
    )
    p.add_argument(
        "--json",
        action="store_true",
        help="以 JSON 格式输出结果(便于脚本解析)",
    )
    return p.parse_args(argv)


async def _test_token_only() -> Tuple[bool, str]:
    """单独验证 token 申请是否成功(不等 STAC)。"""
    if not is_cdse_configured():
        return False, "未配置 COPERNICUS_CLIENT_ID / COPERNICUS_CLIENT_SECRET"
    try:
        from app.utils.cdse_token import CdseTokenCache

        token = await CdseTokenCache.instance().get_token()
        return True, f"token 长度 {len(token)} ({token[:8]}...)"
    except CdseAuthError as exc:
        return False, f"token 申请失败:{exc}"
    except Exception as exc:  # noqa: BLE001
        return False, f"未知错误:{exc!s}"


async def _search_one(
    collections: List[str],
    bbox: List[float],
    datetime_range: str,
    *,
    cloud: Optional[int] = None,
    skip_cdse: bool = False,
) -> StacSearchResult:
    """走 fallback 链搜一个 collection,带云量过滤(如适用)。"""
    query = None
    if cloud is not None and any("sentinel-2" in c for c in collections):
        query = {"eo:cloud_cover": {"lte": cloud}}
    return await search_with_fallback(
        bbox=bbox,
        datetime_range=datetime_range,
        collections=collections,
        query=query,
        limit=1,
        sortby=[{"field": "properties.datetime", "direction": "desc"}],
        skip_cdse=skip_cdse,
    )


def _fmt_item_summary(item: dict) -> str:
    props = item.get("properties", {})
    dt = props.get("datetime", "?")
    cloud = props.get("eo:cloud_cover")
    cloud_str = f", cloud={cloud}%" if cloud is not None else ""
    return f"id={item.get('id','?')} datetime={dt}{cloud_str}"


async def main_async(argv: Optional[List[str]] = None) -> int:
    args = parse_args(argv)

    # 时间范围
    from datetime import datetime, timedelta, timezone

    end_dt = (
        datetime.strptime(args.end, "%Y-%m-%d").replace(tzinfo=timezone.utc)
        if args.end
        else datetime.now(timezone.utc)
    )
    start_dt = (
        datetime.strptime(args.start, "%Y-%m-%d").replace(tzinfo=timezone.utc)
        if args.start
        else end_dt - timedelta(days=args.days)
    )
    datetime_range = (
        f"{start_dt.strftime('%Y-%m-%d')}T00:00:00Z"
        f"/{end_dt.strftime('%Y-%m-%d')}T23:59:59Z"
    )

    report = {
        "config": {
            "aoi": args.aoi,
            "datetime_range": datetime_range,
            "max_cloud": args.cloud,
            "skip_cdse": args.skip_cdse,
            "cdse_configured": is_cdse_configured(),
        },
        "token_check": None,
        "s2_result": None,
        "s1_result": None,
        "errors": [],
    }

    if not args.json:
        print("=" * 64)
        print("  DisasterGuard · CDSE / STAC fallback 验证")
        print("=" * 64)
        print(f"  AOI           : {args.aoi}")
        print(f"  时间范围      : {datetime_range}")
        print(f"  最大云量      : {args.cloud}%")
        print(f"  跳过 CDSE     : {args.skip_cdse}")
        print(f"  CDSE 凭证可用 : {is_cdse_configured()}")
        print()

    # Step 1: token 检查(只在没 skip CDSE 且有凭证时跑)
    if not args.skip_cdse and is_cdse_configured():
        if not args.json:
            print("▶ Step 1: 验证 CDSE token 申请…")
        ok, msg = await _test_token_only()
        report["token_check"] = {"ok": ok, "detail": msg}
        if not args.json:
            print(f"  {'✓' if ok else '✗'} {msg}")
        if not ok and not args.skip_cdse:
            report["errors"].append(f"CDSE token 失败:{msg}")
            # 凭证错误也别中断,继续测 fallback
            if not args.json:
                print("  → 凭证错误,继续 fallback 链路(Earth Search + USGS)")
        if not args.json:
            print()

    # Step 2: S2 L2A 搜索
    if not args.json:
        print("▶ Step 2: 搜索 Sentinel-2 L2A 场景…")
    try:
        s2 = await _search_one(
            ["sentinel-2-l2a"],
            args.aoi,
            datetime_range,
            cloud=args.cloud,
            skip_cdse=args.skip_cdse,
        )
        report["s2_result"] = {
            "provider": s2.provider,
            "count": len(s2.features),
            "note": s2.note,
            "items": [
                {**_fmt_item_summary_dict(f), "assets": list(f.get("assets", {}).keys())}
                if args.print_assets
                else _fmt_item_summary_dict(f)
                for f in s2.features[:3]
            ],
        }
        if not args.json:
            print(f"  ✓ 命中 provider={s2.provider}, {len(s2.features)} 个场景")
            for f in s2.features[:1]:
                print(f"    - {_fmt_item_summary(f)}")
                if args.print_assets:
                    print(f"      assets: {list(f.get('assets', {}).keys())}")
            if s2.note:
                print(f"    ({s2.note})")
    except StacClientError as exc:
        report["s2_result"] = {"error": str(exc)}
        report["errors"].append(f"S2 搜索失败:{exc}")
        if not args.json:
            print(f"  ✗ S2 搜索失败:{exc}")
    if not args.json:
        print()

    # Step 3: S1 GRD 搜索(不需要 cloud 过滤)
    if not args.json:
        print("▶ Step 3: 搜索 Sentinel-1 GRD 场景…")
    try:
        s1 = await _search_one(
            ["sentinel-1-grd"],
            args.aoi,
            datetime_range,
            cloud=None,
            skip_cdse=args.skip_cdse,
        )
        report["s1_result"] = {
            "provider": s1.provider,
            "count": len(s1.features),
            "note": s1.note,
            "items": [
                {**_fmt_item_summary_dict(f), "assets": list(f.get("assets", {}).keys())}
                if args.print_assets
                else _fmt_item_summary_dict(f)
                for f in s1.features[:3]
            ],
        }
        if not args.json:
            print(f"  ✓ 命中 provider={s1.provider}, {len(s1.features)} 个场景")
            for f in s1.features[:1]:
                print(f"    - {_fmt_item_summary(f)}")
                if args.print_assets:
                    print(f"      assets: {list(f.get('assets', {}).keys())}")
            if s1.note:
                print(f"    ({s1.note})")
    except StacClientError as exc:
        report["s1_result"] = {"error": str(exc)}
        report["errors"].append(f"S1 搜索失败:{exc}")
        if not args.json:
            print(f"  ✗ S1 搜索失败:{exc}")
    if not args.json:
        print()

    # 汇总
    s2_ok = report["s2_result"] and report["s2_result"].get("count", 0) > 0
    s1_ok = report["s1_result"] and report["s1_result"].get("count", 0) > 0

    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2, default=str))

    if not args.json:
        print("=" * 64)
        if s2_ok and s1_ok:
            print("  ✅ PASS: S2 + S1 均命中,前端可正常生成合成图")
            print(f"     S2 来自: {report['s2_result']['provider']}")
            print(f"     S1 来自: {report['s1_result']['provider']}")
            print()
            print("  建议下一步:")
            print("    1. 测试 API:")
            print("       curl -X POST http://localhost:8001/api/ai/sentinel/compose \\")
            print("         -H 'Content-Type: application/json' \\")
            print(f"         -d '{{\"bbox\":{args.aoi},\"max_cloud\":{args.cloud}}}'")
            print("    2. 联调完成后,到")
            print("       https://dataspace.copernicus.eu/profile/applications")
            print("       点 DisasterGuard → Regenerate secret(因为本对话已泄露旧 secret)")
            return 0
        elif s2_ok or s1_ok:
            print("  ⚠️  PARTIAL: 仅一个产品命中")
            print(f"     S2: {'✓' if s2_ok else '✗'}, S1: {'✓' if s1_ok else '✗'}")
            print(f"     错误: {report['errors']}")
            return 3
        else:
            print("  ❌ FAIL: 全部 STAC 链路失败")
            print(f"     错误: {report['errors']}")
            if not is_cdse_configured() and not args.skip_cdse:
                print("     → 提示: 未配置 CDSE 凭证,但 Earth Search + USGS 也挂了")
                print("       检查网络/防火墙:能否访问 earth-search.aws.element84.com 和 cmr.earthdata.nasa.gov")
            return 2


def _fmt_item_summary_dict(item: dict) -> dict:
    """JSON 模式下 item 摘要。"""
    props = item.get("properties", {})
    return {
        "id": item.get("id"),
        "datetime": props.get("datetime"),
        "cloud_cover": props.get("eo:cloud_cover"),
    }


def main() -> int:
    async def _run() -> int:
        try:
            return await main_async()
        finally:
            # 在同一个事件循环里关闭共享 session,避免 "Unclosed client session" 警告
            try:
                from app.utils.http_client import HttpClientHolder
                await HttpClientHolder.instance().close()
            except Exception as exc:  # pragma: no cover
                print(f"[warn] 关闭 HttpClientHolder 失败: {exc}", file=sys.stderr)

    return asyncio.run(_run())


if __name__ == "__main__":
    sys.exit(main())
