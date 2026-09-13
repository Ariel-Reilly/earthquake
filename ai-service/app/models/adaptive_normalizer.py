"""
自适应归一化器：用「最近窗口 P95」替代硬编码 NORM_MAX。

为什么需要自适应：
  - 硬编码 NORM_MAX（如 rainfall_24h=150mm）来源于"经验上限"，但中国不同区域差异极大
    （西北 50mm 已极端，东南 200mm 才算异常）；
  - 单点因素"超阈值"就触发风险，会导致不同区域模型分数不可比；
  - 用最近窗口的 P95 当上限，让"比本地历史同期高 95% 的样本"才是异常，
    风险评分更贴合本地实际。

工作原理：
  - 每个 (factor_key, region_key) 二元组维护一个滑动窗口统计器（默认窗口 = 最近 200 次观测）；
  - 每次 fit_one(value) 推进窗口；get_dynamic_max() 返回 P95（5% 截断，避免极端值干扰）；
  - 当窗口样本数 < 10 时回退到 fallback_max（硬编码兜底）；
  - 线程安全用 threading.Lock 简单保护。

API：
  normalizer = get_adaptive_normalizer()
  normalizer.fit_one("rainfall_24h", region_code="CN-YN-001", value=85.0)
  max_val = normalizer.get_dynamic_max("rainfall_24h", region_code="CN-YN-001", fallback=150.0)
  stats = normalizer.snapshot("rainfall_24h", region_code="CN-YN-001")
"""
from __future__ import annotations

import math
import threading
from collections import defaultdict, deque
from typing import Deque, Dict, Optional, Tuple


# 因子 → 兜底上限（当窗口样本不足时使用）
FALLBACK_NORM_MAX: Dict[str, float] = {
    "rainfall_24h": 150.0,
    "rainfall_7d": 400.0,
    "slope_deg": 60.0,
    "soil_grade": 5.0,
    "vegetation_cover": 1.0,
    "distance_to_water_m": 5000.0,
    "geology_grade": 5.0,
    "elevation_m": 3000.0,
    "displacement_rate": 30.0,
    "displacement_total": 200.0,
    "crack_width": 50.0,
    "earthquake_mag": 7.0,
    "site_grade": 5.0,
}


def _p95(values: Deque[float]) -> float:
    """计算样本的 95 分位数（线性插值）。"""
    if not values:
        return 0.0
    sorted_vals = sorted(values)
    n = len(sorted_vals)
    if n == 1:
        return float(sorted_vals[0])
    # 95 分位数位置 = (n-1) * 0.95
    pos = (n - 1) * 0.95
    lower = int(math.floor(pos))
    upper = int(math.ceil(pos))
    if lower == upper:
        return float(sorted_vals[lower])
    frac = pos - lower
    return float(sorted_vals[lower] + (sorted_vals[upper] - sorted_vals[lower]) * frac)


class AdaptiveNormalizer:
    """按 (factor, region) 维度的滑动窗口 P95 归一化器。"""

    # 最小有效样本数（低于此值回退 fallback）
    MIN_SAMPLES = 10

    # 滑动窗口容量
    WINDOW_SIZE = 200

    # P95 上限倍数（避免窗口被极端污染）：动态上限 = clamp(P95 * 1.5, fallback, fallback*3)
    SAFETY_CAP_MULTIPLIER = 1.5
    SAFETY_HARD_MAX_MULTIPLIER = 3.0

    def __init__(self) -> None:
        self._lock = threading.Lock()
        # data[(factor, region)] = deque[float]
        self._data: Dict[Tuple[str, str], Deque[float]] = defaultdict(
            lambda: deque(maxlen=self.WINDOW_SIZE)
        )

    def fit_one(self, factor_key: str, value: float, region_code: str = "_default") -> None:
        """单次观测推进窗口。"""
        if value is None:
            return
        try:
            f = float(value)
        except (TypeError, ValueError):
            return
        if not math.isfinite(f):
            return
        with self._lock:
            self._data[(factor_key, region_code)].append(f)

    def fit_batch(
        self, values: List[float], factor_key: str, region_code: str = "_default"
    ) -> None:
        """批量观测推进窗口。"""
        for v in values:
            self.fit_one(factor_key, v, region_code)

    def get_dynamic_max(
        self, factor_key: str, region_code: str = "_default", fallback: Optional[float] = None
    ) -> float:
        """
        获取自适应上限（未达最小样本数时回退 fallback 或 FALLBACK_NORM_MAX）。

        Args:
            factor_key: 因子键名（如 'rainfall_24h'）
            region_code: 区域编码（不同区域独立窗口）；缺省 '_default'
            fallback: 自定义兜底值；不传则用 FALLBACK_NORM_MAX[factor_key]
        Returns:
            上限值（绝对正数）
        """
        with self._lock:
            samples = self._data.get((factor_key, region_code))
        if not samples or len(samples) < self.MIN_SAMPLES:
            return fallback if fallback is not None else FALLBACK_NORM_MAX.get(factor_key, 1.0)

        p95 = _p95(samples)
        fallback_val = fallback if fallback is not None else FALLBACK_NORM_MAX.get(factor_key, 1.0)
        # 安全上限：避免 1 个极端样本让 P95 偏大
        capped = min(p95 * self.SAFETY_CAP_MULTIPLIER, fallback_val * self.SAFETY_HARD_MAX_MULTIPLIER)
        # 不低于 fallback 的 50%（避免极小值）；不低于 1e-6
        return max(max(capped, fallback_val * 0.5), 1e-6)

    def get_stats(self, factor_key: str, region_code: str = "_default") -> Dict[str, float]:
        """
        返回窗口统计（p50/p95/min/max/count），用于模型可观测性。
        """
        with self._lock:
            samples = self._data.get((factor_key, region_code))
        if not samples:
            return {
                "count": 0,
                "p50": 0.0,
                "p95": 0.0,
                "min": 0.0,
                "max": 0.0,
            }
        sorted_vals = sorted(samples)
        n = len(sorted_vals)
        return {
            "count": n,
            "p50": _p95_approx(sorted_vals, 0.50),
            "p95": _p95_approx(sorted_vals, 0.95),
            "min": float(sorted_vals[0]),
            "max": float(sorted_vals[-1]),
        }

    def snapshot(self, factor_key: str, region_code: str = "_default") -> Dict[str, float]:
        return self.get_stats(factor_key, region_code)

    def all_stats(self) -> Dict[str, Dict[str, Dict[str, float]]]:
        """导出所有 (factor, region) 的统计快照（用于监控/调试）。"""
        out: Dict[str, Dict[str, Dict[str, float]]] = {}
        with self._lock:
            keys = list(self._data.keys())
        for factor, region in keys:
            out.setdefault(factor, {})[region] = self.get_stats(factor, region)
        return out


def _p95_approx(sorted_vals, q: float) -> float:
    """计算 q 分位数（线性插值），内部使用避免重复排序。"""
    n = len(sorted_vals)
    if n == 0:
        return 0.0
    if n == 1:
        return float(sorted_vals[0])
    pos = (n - 1) * q
    lower = int(math.floor(pos))
    upper = int(math.ceil(pos))
    if lower == upper:
        return float(sorted_vals[lower])
    frac = pos - lower
    return float(sorted_vals[lower] + (sorted_vals[upper] - sorted_vals[lower]) * frac)


# 单例
_normalizer: Optional[AdaptiveNormalizer] = None
_normalizer_lock = threading.Lock()


def get_adaptive_normalizer() -> AdaptiveNormalizer:
    global _normalizer
    if _normalizer is None:
        with _normalizer_lock:
            if _normalizer is None:
                _normalizer = AdaptiveNormalizer()
    return _normalizer