"""自适应归一化的确定性契约（R11 E6）。

背景：`AdaptiveNormalizer` 是**进程级**共享窗口（maxlen=200），
`get_dynamic_max()` 的 P95 会随任意一次请求的 `fit_one` 而变。
历史实现里 disaster_assessment / device_health 的评分直接用它当分母，
于是「同一份输入，隔一次调用分数就不同」；而窗口又由别的请求
（/comprehensive 的全量因子、/detect-batch 的 MON-xxx 临时区域）推进，
等价于「A 请求的样本决定 B 请求的分母」。

当前口径（SCORING_USES_ADAPTIVE_MAX=False）：评分恒用静态上限，
窗口只做诊断。本文件锁死这条不变量。
"""
from __future__ import annotations

import pytest

from app.models.adaptive_normalizer import (
    SCORING_USES_ADAPTIVE_MAX,
    get_adaptive_normalizer,
)
from app.models.device_health_model import get_device_health_scorer
from app.models.disaster_assessment_model import get_disaster_assessment_model

FACTORS = {
    "rainfall_24h": 120.0,
    "rainfall_7d": 260.0,
    "slope_deg": 32.0,
    "soil_grade": 3.0,
    "vegetation_cover": 0.4,
    "distance_to_water_m": 800.0,
    "geology_grade": 3.0,
    "elevation_m": 900.0,
}

DEVICE_METRICS = {
    "disp_rate": [2.0, 2.1, 1.9, 2.05, 2.0, 2.2, 4.5, 6.8, 7.2, 6.5, 5.8, 4.9, 9.0],
    "disp_total": [10.0 + i * 0.2 for i in range(13)],
    "crack_width": [3.0 + (0.1 if i % 5 == 0 else 0) for i in range(13)],
    "tilt": [0.5] * 13,
}


def test_scoring_does_not_use_adaptive_max():
    """默认口径：评分不采用窗口 P95。改成 True 必须先补确定性方案（见模块注释）。"""
    assert SCORING_USES_ADAPTIVE_MAX is False


def test_disaster_assessment_score_is_reproducible():
    """同一输入连续两次评分必须完全一致，即使窗口已被大量样本推进过。"""
    model = get_disaster_assessment_model()
    hazard = {"hazard_code": "HZ-DET-001", "hazard_type": None}

    first = model.score(hazard, dict(FACTORS))
    # 灌入会显著改变 P95 的样本，模拟「其它请求推进了共享窗口」
    normalizer = get_adaptive_normalizer()
    for value in (5.0, 8.0, 12.0, 15.0, 3000.0, 3000.0, 3000.0, 3000.0, 3000.0, 3000.0, 3000.0, 3000.0):
        normalizer.fit_one("elevation_m", value, "CN-TEST-000")

    second = model.score(hazard, dict(FACTORS))

    assert first["overall"]["risk_score"] == second["overall"]["risk_score"]
    assert first["overall"]["risk_level"] == second["overall"]["risk_level"]
    assert first["dimensions"] == second["dimensions"]


def test_device_health_score_is_reproducible():
    """设备健康分同样必须可复现（旧实现在 _score_series 里推进窗口并按 P95 归一）。"""
    scorer = get_device_health_scorer()

    first = scorer.score("DEV-DET-001", {k: list(v) for k, v in DEVICE_METRICS.items()})
    normalizer = get_adaptive_normalizer()
    for value in (0.5, 1.0, 1.5, 2.0, 30.0, 30.0, 30.0, 30.0, 30.0, 30.0, 30.0):
        normalizer.fit_one("disp_rate", value, "DEV-DET-001")

    second = scorer.score("DEV-DET-001", {k: list(v) for k, v in DEVICE_METRICS.items()})

    assert first["health_score"] == second["health_score"]
    assert first["health_level"] == second["health_level"]
    assert first["anomaly_by_metric"] == second["anomaly_by_metric"]


def test_device_model_tag_does_not_claim_adaptive_norm():
    """model 标签不再宣称「+adaptive-norm」——评分并没有用它（纸面功能）。"""
    scorer = get_device_health_scorer()
    result = scorer.score("DEV-DET-002", {k: list(v) for k, v in DEVICE_METRICS.items()})
    assert "adaptive-norm" not in result["model"]


@pytest.mark.parametrize("bad", [None, "", "   "])
def test_normalizer_ignores_invalid_values(bad):
    """窗口只接受有限数值，避免脏数据把 P95 带偏（诊断也要可信）。"""
    normalizer = get_adaptive_normalizer()
    normalizer.fit_one("slope_deg", bad, "_test_invalid")
    stats = normalizer.get_stats("slope_deg", "_test_invalid")
    assert stats["count"] == 0


def test_comprehensive_api_exposes_adaptive_status():
    """/comprehensive 必须把诊断字段真的返回给调用方。

    历史问题：模型算出了 adaptive_normalization，但 response_model 没这个字段，
    FastAPI 静默丢弃 —— 前端看不到 applied 到底是 0 还是 1。
    """
    from fastapi.testclient import TestClient

    from app.main import app

    client = TestClient(app)
    resp = client.post(
        "/api/ai/comprehensive",
        json={
            "hazard_code": "HZ-DET-API-001",
            "factors": {"rainfall_24h": 120.0, "rainfall_7d": 260.0, "slope_deg": 32.0},
        },
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert "adaptive_normalization" in body
    assert body["adaptive_normalization"]["applied"] == 0
    assert body["adaptive_normalization"]["enabled"] is False
    assert body["region_code"] == "HZ-DET-API-001"
