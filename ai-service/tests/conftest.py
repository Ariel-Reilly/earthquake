"""pytest 共享 fixtures：典型因子样本 + 随机种子。"""
import random
import pytest

from app.models.feature_engineering import compute_derived_features
from app.models.adaptive_normalizer import get_adaptive_normalizer
from app.models.feedback_store import get_feedback_store


@pytest.fixture(autouse=True)
def reset_state():
    """每个测试前重置单例，确保隔离。"""
    random.seed(42)
    get_adaptive_normalizer()._data.clear()  # type: ignore[attr]
    get_feedback_store().clear()
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