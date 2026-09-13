"""设备健康评分单元测试：在线/离线/缺指标/异常序列 4 个场景。"""
import pytest

from app.models.device_health_model import (
    HEALTH_METRICS,
    DeviceHealthScorer,
    get_device_health_scorer,
)


@pytest.fixture
def scorer() -> DeviceHealthScorer:
    return get_device_health_scorer()


# 4 个场景 fixture
@pytest.fixture
def online_normal_metrics() -> dict:
    """完全平稳的序列（无 3-sigma 异常）。"""
    return {
        "disp_rate": [2.0] * 10,
        "disp_total": [10.0] * 10,
        "crack_width": [3.0] * 10,
        "tilt": [0.5] * 10,
    }


@pytest.fixture
def online_anomalous_metrics() -> dict:
    """disp_rate 突然飙升到 8-10 mm/day。"""
    return {
        "disp_rate": [2.0, 2.1, 1.9, 2.05, 2.0, 2.2, 4.5, 6.8, 7.2, 6.5, 5.8, 4.9, 9.0],
        "disp_total": [10.0 + i * 0.2 for i in range(13)],
        "crack_width": [3.0 + (0.1 if i % 5 == 0 else 0) for i in range(13)],
        "tilt": [0.5] * 13,
    }


@pytest.fixture
def sparse_metrics() -> dict:
    """只有 1 条指标。"""
    return {
        "disp_rate": [2.0, 2.1, 1.9, 2.05, 2.0],
        # 其他指标缺失
    }


# ---------- 4 个核心场景 ---------- #
class TestFourScenarios:
    def test_online_normal(self, scorer, online_normal_metrics):
        """在线 + 所有指标正常。"""
        r = scorer.score(
            device_code="DEV-OK",
            metrics=online_normal_metrics,
            online_status="在线",
            last_report_at="2026-09-07T08:00:00Z",
        )
        assert r["health_level"] == "HEALTHY"
        assert r["health_score"] >= 80.0
        # disp_derivative 接近 0
        assert abs(r["disp_derivative"]) < 1.0

    def test_online_anomalous(self, scorer, online_anomalous_metrics):
        """在线 + 位移加速 + 异常序列。"""
        r = scorer.score(
            device_code="DEV-AN",
            metrics=online_anomalous_metrics,
            online_status="在线",
            last_report_at="2026-09-07T08:00:00Z",
        )
        # 异常 + 位移加速 → 应该不是 HEALTHY
        assert r["health_level"] != "HEALTHY"
        assert r["health_score"] < 80.0
        # disp_derivative > 0（位移在加速）
        assert r["disp_derivative"] > 0.0
        # 推理链里应有"加速"或"异常"提示
        reasoning_text = " ".join(r["reasoning"])
        assert "加速" in reasoning_text or "偏离" in reasoning_text or "异常" in reasoning_text

    def test_offline_with_stale_report(self, scorer, online_normal_metrics):
        """离线 + 上报超时严重。"""
        r = scorer.score(
            device_code="DEV-OFF",
            metrics=online_normal_metrics,
            online_status="离线",
            last_report_at="2026-09-07T00:00:00Z",  # 8 小时前
        )
        # 离线 + 超时 → 健康分应该低于 online_normal
        online_r = scorer.score(
            device_code="DEV-OK2",
            metrics=online_normal_metrics,
            online_status="在线",
            last_report_at="2026-09-07T08:00:00Z",
        )
        assert r["health_score"] < online_r["health_score"]
        # 推理里应提到离线
        reasoning_text = " ".join(r["reasoning"])
        assert "离线" in reasoning_text or "上报" in reasoning_text

    def test_sparse_metrics_penalty(self, scorer, sparse_metrics):
        """只有 1 条指标（覆盖 < 2）。"""
        r = scorer.score(
            device_code="DEV-SP",
            metrics=sparse_metrics,
            online_status="在线",
            last_report_at="2026-09-07T08:00:00Z",
        )
        # metric_coverage 应为 1（只有 disp_rate）
        assert r["metric_coverage"] == 1
        # 推理里应提示"覆盖不足"
        reasoning_text = " ".join(r["reasoning"])
        assert "不足" in reasoning_text or "覆盖" in reasoning_text


# ---------- 边界值 ---------- #
class TestEdgeCases:
    def test_empty_metrics(self, scorer):
        r = scorer.score(device_code="DEV-E", metrics={})
        # 空 metrics 没有覆盖任何指标 → 应标 0 并提示
        assert r["metric_coverage"] == 0
        reasoning_text = " ".join(r["reasoning"])
        assert "不足" in reasoning_text or "覆盖" in reasoning_text

    def test_too_short_series(self, scorer):
        r = scorer.score(
            device_code="DEV-S",
            metrics={"disp_rate": [2.0, 2.1]},  # 长度 < 3
        )
        # disp_rate 长度 < 3，被 _score_series 跳过（返回 (0.0, True)），但 metric 仍会写入 dict（值 0）
        assert "disp_rate" in r["anomaly_by_metric"]
        assert r["anomaly_by_metric"]["disp_rate"] == 0.0

    def test_score_in_valid_range(self, scorer, online_normal_metrics, online_anomalous_metrics):
        for m in (online_normal_metrics, online_anomalous_metrics, {}):
            r = scorer.score(device_code="DEV-R", metrics=m)
            assert 0.0 <= r["health_score"] <= 100.0
            assert r["health_level"] in ("HEALTHY", "ATTENTION", "WARN", "CRITICAL")


# ---------- 衍生特征 ---------- #
class TestDerivedFeature:
    def test_disp_derivative_present(self, scorer, online_anomalous_metrics):
        r = scorer.score(device_code="DEV-D", metrics=online_anomalous_metrics)
        assert "disp_derivative" in r
        assert r["disp_derivative"] > 0  # 加速

    def test_disp_derivative_in_contributions(self, scorer, online_anomalous_metrics):
        """加速度异常时 contributions 应包含 _derived_disp_derivative。"""
        r = scorer.score(device_code="DEV-DC", metrics=online_anomalous_metrics)
        contrib_features = {c["feature"] for c in r["feature_contributions"]}
        assert "_derived_disp_derivative" in contrib_features


# ---------- 响应契约 ---------- #
class TestResponseContract:
    def test_response_has_required_keys(self, scorer, online_normal_metrics):
        r = scorer.score(device_code="DEV-C", metrics=online_normal_metrics)
        for k in (
            "device_code", "health_score", "health_level", "anomaly_score",
            "forecast_risk", "metric_coverage", "anomaly_by_metric",
            "feature_contributions",  # v1.4.0
            "disp_derivative",  # v1.4.0
            "reasoning", "suggested_actions", "confidence",
            "model", "degraded", "analyzed_at",
        ):
            assert k in r, f"response 缺少 {k}"