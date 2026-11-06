"""2026-11-06 契约修复的回归测试。

只依赖本地模型与 Pydantic 校验，不连数据库、不调外部服务、不加载推理权重。
覆盖三处修复：
  1. score_social_media 不再把 0-4 口径的 social_alert_level 减 1；
  2. AnomalyRequest 在类型校验前兼容 Java 侧传的一维 List[float]（否则 422）；
  3. 一维补齐成 5 个共线特征后，路由如实标 mock=True，不再伪装成真实多维检测。
"""
import pytest

from app.models.comprehensive_model import SOCIAL_ALERT_NORM_MAX, score_social_media
from app.routers.anomaly import AnomalyRequest


def test_social_alert_level_4_is_not_downgraded():
    """上游口径 0-4（4=紧急），历史实现 -1 后按 3 计算，最高档被降一级。"""
    result = score_social_media({"social_alert_level": 4.0})

    assert SOCIAL_ALERT_NORM_MAX == 4.0
    assert result["social_alert_level"] == 4
    # 只有告警等级时，归一化打满 0.6 权重 → 60 分；若仍 -1 则只有 45 分。
    assert result["score"] == 60.0, result


def test_social_alert_level_scores_are_monotonic():
    scores = [
        score_social_media({"social_alert_level": float(level)})["score"]
        for level in range(5)
    ]

    assert scores == sorted(scores)
    assert scores[0] == 0.0
    assert scores[-1] == 60.0
    assert scores[4] > scores[3]


def test_anomaly_normalizes_1d_series_to_5d_matrix():
    """AiServiceClient#anomaly 传的是 List<Double>，必须在 Pydantic 校验前归一化。"""
    req = AnomalyRequest(device_code="SB-TEST", data=[1.0, 2.0, 3.0])

    assert req.data == [[1.0] * 5, [2.0] * 5, [3.0] * 5]


def test_anomaly_keeps_2d_matrix_unchanged():
    matrix = [
        [1.0, 2.0, 3.0, 4.0, 5.0],
        [2.0, 3.0, 4.0, 5.0, 6.0],
        [3.0, 4.0, 5.0, 6.0, 7.0],
    ]

    assert AnomalyRequest(device_code="SB-TEST", data=matrix).data == matrix


def test_anomaly_rejects_inconsistent_rows():
    with pytest.raises(ValueError):
        AnomalyRequest(
            device_code="SB-TEST",
            data=[[1.0, 2.0, 3.0, 4.0, 5.0], [1.0, 2.0], [1.0, 2.0, 3.0, 4.0, 5.0]],
        )


def test_anomaly_1d_input_is_flagged_as_synthetic():
    req = AnomalyRequest(device_code="SB-TEST", data=[1.0, 2.0, 3.0])
    assert req.input_was_1d is True


def test_anomaly_2d_input_is_not_flagged():
    matrix = [[1.0, 2.0, 3.0, 4.0, 5.0]] * 3
    assert AnomalyRequest(device_code="SB-TEST", data=matrix).input_was_1d is False


def test_anomaly_rejects_non_5d_matrix():
    with pytest.raises(ValueError):
        AnomalyRequest(device_code="SB-TEST", data=[[1.0, 2.0, 3.0, 4.0]] * 3)


def test_anomaly_route_marks_1d_result_as_mock(monkeypatch):
    """一维补齐成 5 个共线特征后，即便 detector 加载成功也不能报 mock=False。"""
    import app.routers.anomaly as anomaly_router

    class FakeDetector:
        FEATURE_NAMES = ["displacement_rate", "crack_width", "tilt_angle",
                         "rainfall", "vibration_freq"]

        def detect(self, data):
            return {
                "is_anomaly": False,
                "anomaly_indices": [],
                "score": 0.1,
                "model": "fake",
                "mock": False,
            }

    monkeypatch.setattr(anomaly_router, "get_detector", lambda: FakeDetector())
    resp = anomaly_router.anomaly(
        anomaly_router.AnomalyRequest(device_code="SB-TEST", data=[1.0, 2.0, 3.0])
    )
    assert resp.mock is True
    assert "共线" in resp.message
