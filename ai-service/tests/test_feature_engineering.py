"""特征工程模块单元测试：4 大衍生因子的边界值与一致性。"""
import math

import pytest

from app.models.feature_engineering import (
    compute_derived_features,
    compute_disp_derivative,
    compute_foot_erosion,
    compute_geology_distance_score,
    compute_rain_duration_days,
    compute_rain_intensity_ratio,
    compute_rain_saturation,
    get_derived_feature_names,
)


# ---------- rain_saturation ---------- #
class TestRainSaturation:
    def test_zero_when_no_rain(self):
        assert compute_rain_saturation(0.0, 5.0, 0.0) == pytest.approx(0.0, abs=1e-9)

    def test_extreme_saturation(self):
        # 7d=250, soil=5, 植被=0 → 1.0
        val = compute_rain_saturation(250.0, 5.0, 0.0)
        assert val == pytest.approx(1.0, abs=1e-9)

    def test_dense_vegetation_dampens(self):
        # 7d=250, soil=5, 植被=1.0 → 0.0
        val = compute_rain_saturation(250.0, 5.0, 1.0)
        assert val == pytest.approx(0.0, abs=1e-9)

    def test_clamp_to_unit(self):
        # 即使 7d > 250, soil > 5 也不超过 1.0
        val = compute_rain_saturation(500.0, 5.0, 0.0)
        assert 0.0 <= val <= 1.0

    def test_handles_none(self):
        assert compute_rain_saturation(None, None, None) == pytest.approx(0.0)


# ---------- rain_intensity_ratio ---------- #
class TestRainIntensityRatio:
    def test_pure_24h_concentrated(self):
        # 24h=80, 7d=100 → 0.8
        assert compute_rain_intensity_ratio(80.0, 100.0) == pytest.approx(0.8)

    def test_long_rain_low_ratio(self):
        # 24h=10, 7d=100 → 0.1
        assert compute_rain_intensity_ratio(10.0, 100.0) == pytest.approx(0.1)

    def test_zero_7d_returns_zero(self):
        assert compute_rain_intensity_ratio(50.0, 0.0) == 0.0

    def test_handles_none(self):
        assert compute_rain_intensity_ratio(None, None) == 0.0


# ---------- rain_duration_days ---------- #
class TestRainDurationDays:
    def test_full_seven_days(self):
        # 24h = 7d → 7.0
        assert compute_rain_duration_days(50.0, 50.0) == pytest.approx(7.0)

    def test_one_day_only(self):
        # 24h=10, 7d=70 → 1.0
        assert compute_rain_duration_days(10.0, 70.0) == pytest.approx(1.0)

    def test_clamp_to_seven(self):
        # 24h 比 7d 还大 → 钳到 7.0
        val = compute_rain_duration_days(100.0, 50.0)
        assert val == pytest.approx(7.0)


# ---------- foot_erosion ---------- #
class TestFootErosion:
    def test_steep_near_water(self):
        # slope=45, dist=100 → (45/60) * (1 - 100/5000) = 0.75 * 0.98 = 0.735
        assert compute_foot_erosion(45.0, 100.0) == pytest.approx(0.735, abs=1e-9)

    def test_flat_far_water(self):
        # slope=5°, dist=5000 → 0.0
        assert compute_foot_erosion(5.0, 5000.0) == pytest.approx(0.0)


# ---------- geology_distance_score ---------- #
class TestGeologyDistanceScore:
    def test_fragile_rock_active_water(self):
        # geo=5, dist=0 → 1.0
        assert compute_geology_distance_score(5.0, 0.0) == pytest.approx(1.0)

    def test_solid_rock_far_water(self):
        # geo=1, dist=5000 → 0.0
        assert compute_geology_distance_score(1.0, 5000.0) == pytest.approx(0.0)


# ---------- disp_derivative ---------- #
class TestDispDerivative:
    def test_constant_series_returns_zero(self):
        assert compute_disp_derivative([2.0] * 10) == pytest.approx(0.0)

    def test_accelerating_returns_positive(self):
        # last=8, mean=4 → +4
        assert compute_disp_derivative([2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0]) == pytest.approx(8.0 - (2+3+4+5+6+7+8)/7)

    def test_short_series_returns_zero(self):
        assert compute_disp_derivative([5.0]) == 0.0
        assert compute_disp_derivative([]) == 0.0

    def test_decelerating_returns_negative(self):
        # last=2, mean=5 → -3
        assert compute_disp_derivative([8.0, 6.0, 5.0, 4.0, 3.0, 2.0]) == pytest.approx(2.0 - (8+6+5+4+3+2)/6)


# ---------- compute_derived_features 集成 ---------- #
class TestComputeDerivedFeatures:
    def test_returns_all_six_keys(self, derived_low, derived_high):
        names = get_derived_feature_names()
        for d in (derived_low, derived_high):
            for n in names:
                assert n in d
            # 原始因子也被保留
            assert "rainfall_24h" in d

    def test_high_risk_higher_saturation(self, derived_low, derived_high):
        """高风险样本的 rain_saturation 应该高于低风险样本。"""
        assert (
            derived_high["_derived_rain_saturation_index"]
            > derived_low["_derived_rain_saturation_index"]
        )

    def test_high_risk_higher_intensity(self, derived_low, derived_high):
        """高风险 110mm/24h 占 260/7d 比 ≈ 0.42（骤雨型），低风险 5/25=0.2。"""
        assert (
            derived_high["_derived_rain_intensity_ratio"]
            > derived_low["_derived_rain_intensity_ratio"]
        )

    def test_does_not_mutate_input(self, high_risk_factors):
        original_keys = set(high_risk_factors.keys())
        derived = compute_derived_features(high_risk_factors)
        assert set(high_risk_factors.keys()) == original_keys  # 原 dict 没被加键
        assert "_derived_rain_saturation_index" in derived  # 但返回的加了

    def test_with_disp_series(self):
        factors = {"rainfall_24h": 50.0, "rainfall_7d": 100.0}
        disp = [2.0, 3.0, 5.0, 8.0]
        d = compute_derived_features(factors, disp_series=disp)
        assert d["_derived_disp_derivative"] > 0  # last=8, mean=4.5

    def test_no_nan_inf_in_output(self, high_risk_factors):
        d = compute_derived_features(high_risk_factors)
        for k, v in d.items():
            if isinstance(v, float):
                assert math.isfinite(v), f"{k} 不是有限数: {v}"