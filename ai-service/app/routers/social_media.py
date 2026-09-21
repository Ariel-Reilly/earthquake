"""
社交媒体与文本分析路由 v2.0：POST /api/ai/social-media/analyze

功能
====
对灾情相关文本（微博/微信/应急热线等）进行：
  1. 情感极性分析（恐慌 → 正常）
  2. 告警等级评估（0-4 级）
  3. 关键词提取（泥石流/塌方/道路中断等灾害标签）
  4. 位置信息抽取（从文本中提取经纬度或地名）
  5. 时间线分析（按时间聚合舆情热度变化）

数据源接入说明
==============
当前为框架实现（mock 返回），实际生产环境需接入：
  - 国内：微博开放平台（需企业认证）/ 微信公众号舆情 API / 地方应急热线
  - 国际：Twitter/X API v2（需 Academic 权限获取历史数据）
  - 新闻：NewsAPI / 今日头条搜索 API
接入后只需替换 `SocialMediaAnalyzer.analyze()` 中的 mock 实现。

输入字段（SocialMediaAnalysisRequest）
=====================================
  texts: List[Dict] — 每条文本含 {content, author, timestamp, source, location}
    - content: str   文本内容（必填）
    - author: str    发布者（可选）
    - timestamp: str ISO 8601 时间（可选）
    - source: str    来源平台：weibo / wechat / twitter / news / hotline（可选）
    - location: str  位置描述或经纬度（可选）

输出字段（SocialMediaAnalysisResponse）
=======================================
  overall: {alert_level, sentiment_score, emergency_level, total_posts, hazard_keywords}
  per_source: Dict[str, {alert_level, sentiment, count}]
  top_keywords: List[str]   高频灾害标签
  location_clusters: List[Dict]  位置聚类（提取的灾害区域）
  timeline: List[Dict]       按小时聚合的舆情热度
  mock: bool                是否为 mock 数据
"""

from __future__ import annotations

import math
import re
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

router = APIRouter(prefix="/api/ai", tags=["social-media"])


# 灾害关键词词表（用于告警等级评估和关键词提取）
ALERT_KEYWORDS = {
    # 紧急（4级）
    4: ["泥石流", "山体滑坡", "滑坡", "崩塌", "道路中断", "道路坍塌", "桥梁冲毁",
        "大规模塌方", "山洪暴发", "决堤", "大范围积水", "紧急疏散", "紧急转移",
        "landslide", "debris flow", "collapse", "road blocked", "evacuation",
        "flash flood", "dam failure"],
    # 高（3级）
    3: ["塌方", "落石", "边坡失稳", "裂缝扩大", "地面沉降", "墙体开裂",
        "监测预警", "路面裂缝", "护坡损坏", "水位上涨",
        "rockfall", "sinkhole", "subsidence", "crack", "water level rising"],
    # 中（2级）
    2: ["降雨", "暴雨", "持续降雨", "土壤含水率", "路面积水", "排水不畅",
        "heavy rain", "flooding", "poor drainage", "soil saturated"],
    # 低（1级）
    1: ["天气", "阴天", "多云", "小雨", "晴",
        "cloudy", "light rain", "overcast"],
}

PANIC_KEYWORDS = [
    "完了", "完蛋了", "可怕", "吓死", "救命", "快跑", "逃", "危险",
    "崩溃", "绝望", "救救", "怎么办", "太恐怖",
    "oh no", "help", "danger", "emergency", "flee",
]

NORMAL_KEYWORDS = [
    "安全", "正常", "没事", "放心", "恢复", "已处理", "已抢通",
    "safe", "ok", "fine", "recovered", "cleared",
]

# 位置描述正则（经纬度）
COORD_RE = re.compile(r"(\d+\.?\d*)[°,]\s*(\d+\.?\d*)[°NSE]|[Nn]?(\d+\.?\d*)\s*[°]\s*(\d+\.?\d*)\s*[″']")
# 位置描述关键词
LOCATION_KEYWORDS = ["镇", "乡", "村", "县", "区", "市", "省", "路", "桥", "隧道", "村口", "山上", "河边"]


class TextItem(BaseModel):
    """单条文本条目。"""
    content: str = Field(min_length=1, description="文本内容")
    author: Optional[str] = Field(default=None, description="发布者昵称")
    timestamp: Optional[str] = Field(default=None, description="发布时间 ISO 8601")
    source: Optional[str] = Field(default=None, description="来源：weibo / wechat / twitter / news / hotline")
    location: Optional[str] = Field(default=None, description="位置描述或经纬度")


class SocialMediaAnalysisRequest(BaseModel):
    """社交媒体文本分析请求。"""
    texts: List[TextItem] = Field(
        min_length=1,
        description="待分析文本列表（最多 200 条）",
        max_length=200,
    )
    # 地理范围过滤（可选）
    bbox: Optional[List[float]] = Field(
        default=None,
        description="[minLon, minLat, maxLon, maxLat]，过滤指定经纬度范围的文本",
    )
    # 时间范围过滤（可选）
    time_range_hours: Optional[int] = Field(
        default=None,
        ge=1, le=720,
        description="仅分析最近 N 小时的文本（默认全量）",
    )


class SocialMediaAnalysisResponse(BaseModel):
    """社交媒体分析结果。"""
    total_posts: int
    sources: List[str]
    overall: Dict[str, Any]
    per_source: Dict[str, Dict[str, Any]]
    top_keywords: List[Dict[str, Any]]
    location_clusters: List[Dict[str, Any]]
    timeline: List[Dict[str, Any]]
    mock: bool = False
    message: str = "ok"


def _extract_keywords(text: str) -> List[str]:
    """从文本中提取灾害关键词。"""
    found = []
    text_lower = text.lower()
    for level, keywords in ALERT_KEYWORDS.items():
        for kw in keywords:
            if kw.lower() in text_lower:
                found.append({"keyword": kw, "level": level})
    return found


def _analyze_sentiment(text: str) -> float:
    """计算情感极性分数：-1（恐慌）~ +1（正常）。"""
    text_lower = text.lower()
    panic_count = sum(1 for kw in PANIC_KEYWORDS if kw in text_lower)
    normal_count = sum(1 for kw in NORMAL_KEYWORDS if kw in text_lower)
    if panic_count + normal_count == 0:
        return 0.0
    # 恐慌词权重略高（1.2×）
    score = (normal_count * 1.0 - panic_count * 1.2) / max(panic_count + normal_count, 1)
    return max(-1.0, min(1.0, score))


def _analyze_alert_level(keywords: List[Dict], sentiment: float) -> int:
    """综合关键词和情感计算告警等级（0-4）。"""
    if not keywords:
        return 0
    max_level = max(k["level"] for k in keywords)
    if sentiment < -0.3 and max_level >= 3:
        return min(4, max_level + 1)
    if sentiment < -0.5:
        return max(max_level, 1)
    return max_level


def _parse_timestamp(ts: Optional[str]) -> Optional[datetime]:
    """解析时间字符串。"""
    if not ts:
        return None
    try:
        return datetime.fromisoformat(ts.replace("Z", "+00:00"))
    except Exception:
        return None


def _extract_location(item: TextItem) -> Optional[Dict[str, Any]]:
    """从文本和 location 字段提取位置信息。"""
    search_in = f"{item.location or ''} {item.content}"
    # 经纬度
    m = COORD_RE.search(search_in)
    if m:
        try:
            lon = float(m.group(1) or m.group(3))
            lat = float(m.group(2) or m.group(4))
            if -180 <= lon <= 180 and -90 <= lat <= 90:
                return {"type": "coordinate", "lon": lon, "lat": lat, "raw": m.group(0)}
        except (ValueError, TypeError):
            pass
    # 地名关键词
    for kw in LOCATION_KEYWORDS:
        idx = search_in.find(kw)
        if idx >= 0:
            start = max(0, idx - 4)
            end = min(len(search_in), idx + len(kw) + 4)
            return {"type": "text", "raw": search_in[start:end].strip(), "keyword": kw}
    return None


class SocialMediaAnalyzer:
    """
    社交媒体文本分析器 v2.0。

    当前为 mock 实现，流程：
      1) 逐条文本情感分析（关键词 + 正则）
      2) 关键词提取（告警等级）
      3) 来源分组统计
      4) 位置信息抽取
      5) 时间线聚合

    实际接入时替换 analyze() 方法即可：
      - Weibo: weibo-python SDK → statuses/search
      - Twitter: tweepy → api.search_recent_tweets
      - 应急热线: HTTP API → 文本提取 + 分类
    """

    def analyze(self, texts: List[TextItem], bbox: Optional[List[float]] = None) -> Dict[str, Any]:
        if not texts:
            return self._empty_result()

        # 过滤 bbox
        filtered = texts
        if bbox and len(bbox) == 4:
            filtered = []
            for t in texts:
                loc = _extract_location(t)
                if loc and loc.get("type") == "coordinate":
                    lon, lat = loc["lon"], loc["lat"]
                    if not (bbox[0] <= lon <= bbox[2] and bbox[1] <= lat <= bbox[3]):
                        continue
                filtered.append(t)

        # 逐条分析
        all_keywords: List[Dict] = []
        all_sentiments: List[float] = []
        source_stats: Dict[str, Dict] = {}
        locations: List[Dict] = []
        timeline_raw: Dict[str, int] = {}  # hour -> count

        for t in filtered:
            content = t.content
            source = t.source or "unknown"

            # 情感
            sentiment = _analyze_sentiment(content)
            all_sentiments.append(sentiment)

            # 关键词
            keywords = _extract_keywords(content)
            all_keywords.extend(keywords)

            # 告警等级
            alert_level = _analyze_alert_level(keywords, sentiment)
            emergency_level = 1 if alert_level >= 3 else 0

            # 来源统计
            if source not in source_stats:
                source_stats[source] = {
                    "count": 0, "sentiment_sum": 0.0,
                    "max_alert": 0, "emergency_sum": 0
                }
            source_stats[source]["count"] += 1
            source_stats[source]["sentiment_sum"] += sentiment
            source_stats[source]["max_alert"] = max(source_stats[source]["max_alert"], alert_level)
            source_stats[source]["emergency_sum"] += emergency_level

            # 位置
            loc = _extract_location(t)
            if loc:
                locations.append({**loc, "source": source, "content_preview": content[:50]})

            # 时间线
            ts = _parse_timestamp(t.timestamp)
            if ts:
                hour_key = ts.strftime("%Y-%m-%d %H:00")
            else:
                hour_key = "unknown"
            timeline_raw[hour_key] = timeline_raw.get(hour_key, 0) + 1

        # 总体统计
        total = len(filtered)
        avg_sentiment = sum(all_sentiments) / total if total else 0.0
        max_alert = max((k["level"] for k in all_keywords), default=0)
        overall_alert = _analyze_alert_level(
            [{"keyword": "agg", "level": max_alert}],
            avg_sentiment
        )

        # 来源分组结果
        per_source = {}
        for src, stats in source_stats.items():
            cnt = stats["count"]
            per_source[src] = {
                "count": cnt,
                "sentiment": round(stats["sentiment_sum"] / cnt, 3) if cnt else 0.0,
                "alert_level": stats["max_alert"],
                "emergency_count": stats["emergency_sum"],
            }

        # Top 关键词（去重 + 按频率）
        kw_count: Dict[str, int] = {}
        for kw_info in all_keywords:
            kw = kw_info["keyword"]
            kw_count[kw] = kw_count.get(kw, 0) + 1
        top_keywords = sorted(
            [{"keyword": k, "count": c, "level": next((kk["level"] for kk in all_keywords if kk["keyword"] == k), 1)}
             for k, c in kw_count.items()],
            key=lambda x: (x["level"], x["count"]), reverse=True
        )[:15]

        # 位置聚类（简化：按 keyword 聚类）
        loc_clusters: Dict[str, Dict] = {}
        for loc in locations:
            raw = loc.get("raw", "")
            if loc["type"] == "coordinate":
                key = f"{loc['lon']:.2f},{loc['lat']:.2f}"
            else:
                key = raw[:10]  # 简化key
            if key not in loc_clusters:
                loc_clusters[key] = {"representative": raw, "count": 0, "type": loc["type"], "items": []}
            loc_clusters[key]["count"] += 1
            loc_clusters[key]["items"].append({"source": loc.get("source", ""), "preview": loc.get("content_preview", "")[:30]})

        # 时间线（排序）
        timeline = sorted(
            [{"hour": k, "count": v} for k, v in timeline_raw.items() if k != "unknown"],
            key=lambda x: x["hour"]
        )

        return {
            "total_posts": total,
            "sources": list(source_stats.keys()),
            "overall": {
                "alert_level": overall_alert,
                "alert_label": {0: "无", 1: "低", 2: "中", 3: "高", 4: "紧急"}.get(overall_alert, "未知"),
                "sentiment_score": round(avg_sentiment, 3),
                "sentiment_label": "恐慌" if avg_sentiment < -0.3 else "正常" if avg_sentiment > 0.3 else "中性",
                "emergency_level": 1 if overall_alert >= 3 else 0,
                "max_alert_level": max_alert,
            },
            "per_source": per_source,
            "top_keywords": top_keywords,
            "location_clusters": sorted(loc_clusters.values(), key=lambda x: x["count"], reverse=True)[:10],
            "timeline": timeline,
            "mock": True,
        }

    def _empty_result(self) -> Dict[str, Any]:
        return {
            "total_posts": 0,
            "sources": [],
            "overall": {"alert_level": 0, "alert_label": "无", "sentiment_score": 0.0, "sentiment_label": "中性", "emergency_level": 0, "max_alert_level": 0},
            "per_source": {},
            "top_keywords": [],
            "location_clusters": [],
            "timeline": [],
            "mock": True,
        }


_analyzer: Optional[SocialMediaAnalyzer] = None


def get_analyzer() -> SocialMediaAnalyzer:
    global _analyzer
    if _analyzer is None:
        _analyzer = SocialMediaAnalyzer()
    return _analyzer


@router.post("/social-media/analyze", response_model=SocialMediaAnalysisResponse)
async def analyze_social_media(req: SocialMediaAnalysisRequest):
    """社交媒体文本分析：情感/告警等级/关键词/位置/时间线。

    用法示例：
      POST /api/ai/social-media/analyze
      {
        "texts": [
          {"content": "龙安镇突发泥石流，道路中断，请过往车辆绕行！", "source": "weibo", "timestamp": "2026-09-07T14:30:00Z"},
          {"content": "省道S301 K45处边坡落石，已设置警示标志", "source": "news"}
        ],
        "bbox": [106.5, 30.0, 108.0, 31.0]
      }
    """
    result = get_analyzer().analyze(req.texts, req.bbox or None)
    return SocialMediaAnalysisResponse(
        total_posts=result["total_posts"],
        sources=result["sources"],
        overall=result["overall"],
        per_source=result["per_source"],
        top_keywords=result["top_keywords"],
        location_clusters=result["location_clusters"],
        timeline=result["timeline"],
        mock=result["mock"],
    )


@router.get("/social-media/status")
async def social_media_status():
    """自检：接口状态和可接入的数据源。"""
    return {
        "status": "ok",
        "mock": True,
        "supported_sources": ["weibo", "wechat", "twitter", "news", "hotline"],
        "alert_levels": {0: "无", 1: "低", 2: "中", 3: "高", 4: "紧急"},
        "note": (
            "当前为 mock 实现，情感分析基于关键词匹配。"
            "生产环境请接入微博/微信/应急热线 API，"
            "替换 app/routers/social_media.py 中 SocialMediaAnalyzer.analyze() 方法即可。"
        ),
    }
