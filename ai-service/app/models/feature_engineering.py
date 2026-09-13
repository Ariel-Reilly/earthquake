"""
特征工程：基于原始因子派生「业务语义更强」的衍生特征。

所有衍生特征都满足以下契约：
  - 输入：factors dict（包含原始因子）
  - 输出：factors dict（追加 _derived_ 前缀的衍生键）
  - 不依赖外部模型，纯算术；缺因子时给出 0.0 默认值

衍生因子清单：
  _derived_rain_saturation_index    降雨饱和指数（0-1）
        = (rainfall_7d / 250) * (soil_grade / 5) * (1 - vegetation_cover)
        含义：7 天累计降雨叠加土壤易蚀性、植被缺失的综合饱和风险
        业务解释：土壤含水率过高 + 土壤易蚀 + 植被稀疏 → 高饱和风险

  _derived_rain_intensity_ratio      骤雨强度（0-1）
        = (rainfall_24h / rainfall_7d) if rainfall_7d > 0 else 0.0
        含义：24h 降雨占 7d 的比例，超过 0.5 即"短时间内集中强降水"
        业务解释：越接近 1 → 越短时强降水；越接近 0 → 长期阴雨

  _derived_rain_duration_days        有效降雨持续天数（0-7）
        = 7 * rain_24h / max(rain_7d, 1)
        含义：根据 24h / 7d 比值回推"近 7 天里有几天下了有效雨"
        业务解释：>= 3 天即"持续降雨型"灾害风险

  _derived_foot_erosion_index        坡脚侵蚀指数（0-1）
        = (slope_deg / 60) * (1 - distance_to_water_m / 5000)
        含义：陡坡叠加近水体，坡脚冲刷风险
        业务解释：陡坡 + 河沟边 → 滑坡/泥石流前兆

  _derived_disp_derivative           位移加速度（mm/day^2）
        = last_disp_rate - mean(disp_rate)
        含义：最近位移速率相对历史基线的偏离
        业务解释：> 0 表示位移在加速；加速度越大风险越高
        注意：需要 disp_series 列表输入，调用方负责传 metrics[disp_rate]

  _derived_geology_distance_score    地质 + 距水耦合（0-1）
        = geology_grade/5 * (1 - distance_to_water_m/5000)
        含义：脆弱地质叠加活跃水文
        业务解释：可溶岩 + 地下水活跃 → 塌陷/地裂缝主诱因

所有衍生特征都返回在 [0,1] 或接近原始单位的物理量，方便下游归一化直接复用。
"""
from __future__ import annotations

from typing import Any, Dict, List, Optional


# 衍生因子默认值（缺因子时给出）
DEFAULT_DERIVED = {
    "_derived_rain_saturation_index": 0.0,
    "_derived_rain_intensity_ratio": 0.0,
    "_derived_rain_duration_days": 0.0,
    "_derived_foot_erosion_index": 0.0,
    "_derived_geology_distance_score": 0.0,
}


def _safe(value: Optional[float], default: float = 0.0) -> float:
    """容忍 None / NaN / Inf，返回 default。"""
    if value is None:
        return default
    try:
        f = float(value)
    except (TypeError, ValueError):
        return default
    if f != f or f in (float("inf"), float("-inf")):
        return default
    return f


def _clamp(value: float, lo: float = 0.0, hi: float = 1.0) -> float:
    return max(lo, min(hi, value))


def compute_rain_saturation(
    rainfall_7d: Optional[float],
    soil_grade: Optional[float],
    vegetation_cover: Optional[float],
) -> float:
    """
    降雨饱和指数 = (7d 累计 / 250) * (土壤易蚀性 / 5) * (1 - 植被覆盖)

    物理含义：长期降雨把水分渗进土壤，植被稀少 + 土壤易蚀的情况下极易饱和失稳。
    """
    r = _safe(rainfall_7d, 0.0)
    s = _safe(soil_grade, 1.0)
    v = _safe(vegetation_cover, 0.5)
    return _clamp((r / 250.0) * (s / 5.0) * (1.0 - v))


def compute_rain_intensity_ratio(
    rainfall_24h: Optional[float],
    rainfall_7d: Optional[float],
) -> float:
    """
    骤雨强度 = 24h / 7d（短时集中降雨的占比）。

    返回 0-1：值越大代表降雨越集中在最近 24 小时（骤雨型）；值越小代表长期阴雨。
    """
    r24 = _safe(rainfall_24h, 0.0)
    r7 = _safe(rainfall_7d, 0.0)
    if r7 <= 0.0:
        return 0.0
    return _clamp(r24 / r7, 0.0, 1.0)


def compute_rain_duration_days(
    rainfall_24h: Optional[float],
    rainfall_7d: Optional[float],
) -> float:
    """
    有效降雨持续天数 = 7 * (24h / 7d)
    反推近 7 天里"下了有效雨的天数"。

    边界：24h 比 7d 大很多时（极端骤雨）会被钳制到 7.0。
    """
    r24 = _safe(rainfall_24h, 0.0)
    r7 = _safe(rainfall_7d, 1.0)  # 防止除零
    if r7 <= 0.0:
        return 0.0
    raw = 7.0 * r24 / r7
    return _clamp(raw, 0.0, 7.0)


def compute_foot_erosion(
    slope_deg: Optional[float],
    distance_to_water_m: Optional[float],
) -> float:
    """
    坡脚侵蚀指数 = (坡度 / 60) * (1 - 距水体 / 5000)
    陡坡 + 近水体 → 坡脚冲刷型滑坡 / 泥石流前兆。
    """
    slope = _safe(slope_deg, 0.0)
    dist = _safe(distance_to_water_m, 5000.0)
    return _clamp((slope / 60.0) * (1.0 - dist / 5000.0))


def compute_geology_distance_score(
    geology_grade: Optional[float],
    distance_to_water_m: Optional[float],
) -> float:
    """
    地质 × 距水耦合 = (地质脆弱性 / 5) * (1 - 距水体 / 5000)
    可溶岩 + 地下水活跃 → 塌陷 / 地裂缝主诱因。
    """
    geo = _safe(geology_grade, 1.0)
    dist = _safe(distance_to_water_m, 5000.0)
    return _clamp((geo / 5.0) * (1.0 - dist / 5000.0))


def compute_disp_derivative(disp_series: Optional[List[float]]) -> float:
    """
    位移加速度 = last - mean(history)（mm/day 单位，越大越危险）。

    Args:
        disp_series: 时序（最新值在末尾）。空/长度 < 2 返回 0.0。
    Returns:
        派生加速度值（可能为负，代表减速；但通常我们关心 > 0）。
    """
    if not disp_series or len(disp_series) < 2:
        return 0.0
    series = [_safe(v, 0.0) for v in disp_series]
    mean = sum(series) / len(series)
    return _safe(series[-1] - mean, 0.0)


def compute_derived_features(
    factors: Dict[str, Any],
    disp_series: Optional[List[float]] = None,
) -> Dict[str, Any]:
    """
    一站式计算所有衍生特征，原 factors 不被修改；返回新 dict。

    Args:
        factors: 原始因子 dict（可能含 rainfall_24h/rainfall_7d/soil_grade/vegetation_cover
                 /slope_deg/distance_to_water_m/geology_grade 等）
        disp_series: 可选位移速率时序，用于 _derived_disp_derivative
    Returns:
        新 dict，原 factors + _derived_* 键
    """
    out: Dict[str, Any] = dict(factors)  # 浅拷贝

    out["_derived_rain_saturation_index"] = compute_rain_saturation(
        factors.get("rainfall_7d"),
        factors.get("soil_grade"),
        factors.get("vegetation_cover"),
    )
    out["_derived_rain_intensity_ratio"] = compute_rain_intensity_ratio(
        factors.get("rainfall_24h"),
        factors.get("rainfall_7d"),
    )
    out["_derived_rain_duration_days"] = compute_rain_duration_days(
        factors.get("rainfall_24h"),
        factors.get("rainfall_7d"),
    )
    out["_derived_foot_erosion_index"] = compute_foot_erosion(
        factors.get("slope_deg"),
        factors.get("distance_to_water_m"),
    )
    out["_derived_geology_distance_score"] = compute_geology_distance_score(
        factors.get("geology_grade"),
        factors.get("distance_to_water_m"),
    )

    if disp_series is not None:
        out["_derived_disp_derivative"] = compute_disp_derivative(disp_series)
    else:
        out["_derived_disp_derivative"] = _safe(factors.get("_derived_disp_derivative"), 0.0)

    return out


def get_derived_feature_names() -> List[str]:
    """返回所有衍生特征名（方便模型和文档引用）。"""
    return [
        "_derived_rain_saturation_index",
        "_derived_rain_intensity_ratio",
        "_derived_rain_duration_days",
        "_derived_foot_erosion_index",
        "_derived_geology_distance_score",
        "_derived_disp_derivative",
    ]


def get_derived_feature_descriptions() -> Dict[str, str]:
    """返回衍生特征的物理含义（前端展示 / 文档引用）。"""
    return {
        "_derived_rain_saturation_index": (
            "降雨饱和指数：长期降雨 × 土壤易蚀性 × 植被缺失；"
            "值越大土壤含水率越接近失稳临界"
        ),
        "_derived_rain_intensity_ratio": (
            "骤雨强度：24h 降雨占 7d 的比例；接近 1 即短时集中强降水"
        ),
        "_derived_rain_duration_days": (
            "降雨持续天数：反推近 7 天里下了几天有效雨；≥3 天即持续型风险"
        ),
        "_derived_foot_erosion_index": (
            "坡脚侵蚀指数：陡坡 × 近水体；典型滑坡/泥石流前兆"
        ),
        "_derived_geology_distance_score": (
            "地质 × 距水耦合：脆弱地质 × 活跃水文；塌陷 / 地裂缝主诱因"
        ),
        "_derived_disp_derivative": (
            "位移加速度：最近位移速率相对历史基线的偏离（mm/day^2）；"
            "> 0 即位移在加速"
        ),
    }