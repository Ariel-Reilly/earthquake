"""pytest 共享 fixtures：典型因子样本 + 随机种子。"""
import random
import pytest

from app.models.feature_engineering import compute_derived_features
from app.models.adaptive_normalizer import get_adaptive_normalizer
from app.models.feedback_store import get_feedback_store


@pytest.fixture(autouse=True)
def reset_state(tmp_path):
    """每个测试前重置单例，确保隔离。

    R11 E1 附带修复：``FeedbackStore.clear()`` 只清内存，写入仍会 append 到
    ``models/feedback/feedback.jsonl``。此前每跑一次 pytest，仓库里的反馈样本
    就被写入一批 ``feedback_by="test"`` 的假数据，进而污染后续重训的训练集。
    这里把持久化路径重定向到 tmp_path，测试不再改动仓库文件。
    """
    random.seed(42)
    get_adaptive_normalizer()._data.clear()  # type: ignore[attr]
    store = get_feedback_store()
    store.clear()
    store._persist_path = tmp_path / "feedback.jsonl"  # type: ignore[attr-defined]
    yield


# 典型样本：低风险山地
LOW_RISK_FACTORS = {
    "elevation_m": 1200.0,
    "slope_deg": 8.0,
    "soil_grade": 1.5,
    "vegetation_cover": 0.75,
    "distance_to_water_m": 2000.0,
    "geology_grade": 1.5,
    "geology_label": "完整花岗岩",
    "rainfall_24h": 5.0,
    "rainfall_7d": 25.0,
}

# 典型样本：极端降雨 + 近沟
HIGH_RISK_FACTORS = {
    "elevation_m": 600.0,
    "slope_deg": 18.0,
    "soil_grade": 2.5,
    "vegetation_cover": 0.35,
    "distance_to_water_m": 180.0,
    "geology_grade": 3.5,
    "geology_label": "石灰岩",
    "rainfall_24h": 110.0,
    "rainfall_7d": 260.0,
}

# 典型样本：有设备 + 标注滑坡
WITH_DEVICE_FACTORS = {
    **HIGH_RISK_FACTORS,
    "displacement_rate": 5.5,
    "displacement_total": 80.0,
    "crack_width": 12.0,
}


@pytest.fixture
def low_risk_factors() -> dict:
    return dict(LOW_RISK_FACTORS)


@pytest.fixture
def high_risk_factors() -> dict:
    return dict(HIGH_RISK_FACTORS)


@pytest.fixture
def with_device_factors() -> dict:
    return dict(WITH_DEVICE_FACTORS)


@pytest.fixture
def derived_low() -> dict:
    """对 low_risk_factors 算衍生特征。"""
    return compute_derived_features(LOW_RISK_FACTORS)


@pytest.fixture
def derived_high() -> dict:
    """对 high_risk_factors 算衍生特征。"""
    return compute_derived_features(HIGH_RISK_FACTORS)