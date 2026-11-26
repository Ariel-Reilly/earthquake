"""漂移检测的"不得假绿"契约（R11 E5）。

背景：``DriftMonitor.detect()`` 的整体状态初始为 NORMAL，遇到"没有基线"
或"当前样本不足"时循环不执行，直接返回 NORMAL + "各特征分布正常"。
前端据此渲染绿色正常态——运维会以为模型稳定，实际从未比较过任何东西。

这里锁死：没有比较就只能是 NO_BASELINE / INSUFFICIENT_DATA。
"""
from __future__ import annotations

import numpy as np

from app.models.drift_monitor import DriftMonitor


def _monitor(baseline: dict | None = None) -> DriftMonitor:
    m = DriftMonitor("test_model")
    m.baseline_data = baseline or {}
    return m


def test_no_baseline_is_not_normal():
    result = _monitor().detect({"displacement_rate": [1.0] * 50})
    assert result.overall_status == DriftMonitor.STATUS_NO_BASELINE
    assert result.compared_features == 0
    assert "正常" not in result.recommendation
    assert result.features == {}


def test_empty_current_data_is_insufficient():
    rng = np.random.default_rng(42)
    m = _monitor({"displacement_rate": rng.normal(5, 1, 300)})
    result = m.detect({})
    assert result.overall_status == DriftMonitor.STATUS_INSUFFICIENT
    assert result.compared_features == 0


def test_too_few_samples_is_insufficient_not_normal():
    rng = np.random.default_rng(42)
    m = _monitor({"rainfall_24h": rng.normal(40, 5, 300)})
    result = m.detect({"rainfall_24h": [40.0, 41.0, 39.0]})

    assert result.overall_status == DriftMonitor.STATUS_INSUFFICIENT
    assert result.compared_features == 0
    feat = result.features["rainfall_24h"]
    assert feat.drift_level == "INSUFFICIENT"


def test_same_distribution_is_normal_after_real_comparison():
    rng = np.random.default_rng(7)
    base = rng.normal(50, 5, 500)
    current = rng.normal(50, 5, 500)
    result = _monitor({"rainfall_24h": base}).detect({"rainfall_24h": current})

    assert result.compared_features == 1
    assert result.overall_status == "NORMAL"
    assert result.features["rainfall_24h"].drift_level == "NORMAL"


def test_shifted_distribution_is_significant():
    rng = np.random.default_rng(7)
    base = rng.normal(10, 1, 500)
    current = rng.normal(30, 1, 500)
    result = _monitor({"displacement_rate": base}).detect({"displacement_rate": current})

    assert result.compared_features == 1
    assert result.overall_status == "SIGNIFICANT_DRIFT"
    assert result.features["displacement_rate"].drift_level == "SIGNIFICANT"
