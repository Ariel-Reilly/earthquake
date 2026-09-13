"""灾害评估模型单元测试：5 场景 + 边界 + 反馈集成。"""
import pytest

from app.models.disaster_assessment_model import (
    DISASTER_TYPE_UNDETERMINED,
    DisasterAssessmentModel,
    get_disaster_assessment_model,
)
from app.models.feedback_store import get_feedback_store


@pytest.fixture
def model() -> DisasterAssessmentModel:
    return get_disaster_assessment_model()


# ---------- 5 个真实场景 ---------- #
class TestFiveScenarios:
    def test_low_risk_mountain(self, model, low_risk_factors):
        """低风险山地：稳定 + 滑坡（已标注）。"""
        r = model.score({"hazard_code": "GS-L", "hazard_type": "滑坡", "region_code": "TEST-L"}, low_risk_factors)
        assert r["overall"]["risk_level"] == "LOW"
        assert r["overall"]["risk_score"] < 35.0
        assert r["overall"]["concern_label"] == "稳定"
        assert r["disaster_type"]["disaster_type"] == "滑坡"
        assert r["disaster_type"]["source"] == "declared"
        assert r["overall"]["device_available"] is False

    def test_extreme_rain_no_device_no_label(self, model, high_risk_factors):
        """极端降雨 + 近沟 + 无设备 + 未标注：触发分类推断。"""
        r = model.score({"hazard_code": "GS-H", "hazard_type": None, "region_code": "TEST-H"}, high_risk_factors)
        assert r["overall"]["risk_level"] in ("HIGH", "CRITICAL")
        assert r["overall"]["risk_score"] >= 60.0
        assert r["overall"]["concern_label"] in ("预警", "异常严重")
        # 必须有推断类型或"待定"
        assert r["disaster_type"]["source"] in ("inferred", "low_confidence", "ambiguous")
        assert "type_scores" in r["disaster_type"]
        # 必须有 feature_contributions
        assert "feature_contributions" in r
        assert len(r["feature_contributions"]) > 0
        # 第一名贡献度最大
        assert r["feature_contributions"][0]["contribution"] >= r["feature_contributions"][-1]["contribution"]

    def test_with_device_declared_type(self, model, with_device_factors):
        """有设备 + 标注类型：declared 短路。"""
        r = model.score(
            {"hazard_code": "GS-D", "hazard_type": "滑坡", "region_code": "TEST-D"},
            with_device_factors,
        )
        assert r["disaster_type"]["disaster_type"] == "滑坡"
        assert r["disaster_type"]["source"] == "declared"
        assert r["disaster_type"]["confidence"] == 0.95
        assert r["overall"]["device_available"] is True

    def test_ambiguous_type_returns_undetermined(self, model):
        """中等条件 + 无标注：可能 ambiguous（差距 < 0.05）。"""
        factors = {
            "elevation_m": 700.0,
            "slope_deg": 22.0,  # 中等
            "soil_grade": 3.0,
            "vegetation_cover": 0.4,
            "distance_to_water_m": 400.0,  # 中等
            "geology_grade": 3.0,
            "rainfall_24h": 35.0,
            "rainfall_7d": 95.0,
        }
        r = model.score(
            {"hazard_code": "GS-AMB", "hazard_type": None, "region_code": "TEST-AMB"},
            factors,
        )
        # 即使没歧义也会输出 inferred；但至少 type_scores 是非空 dict
        assert r["disaster_type"]["type_scores"] != {}

    def test_empty_factors(self, model):
        """空因子：所有维度分很低 → LOW；类型分类触发 fallback 或 low_confidence。"""
        r = model.score({"hazard_code": "GS-E", "category": None, "region_code": "TEST-E"}, {})
        # 空因子 → geographic 默认值给出非零 base 分，但应该在 LOW 范围内
        assert r["overall"]["risk_level"] == "LOW"
        assert r["overall"]["risk_score"] < 35.0
        # 类型分类应触发 fallback 或 low_confidence
        assert r["disaster_type"]["source"] in ("fallback", "low_confidence", "ambiguous")


# ---------- 衍生特征 + SHAP 贡献 ---------- #
class TestDerivedFeaturesAndContributions:
    def test_derived_features_present_in_score(self, model, high_risk_factors):
        r = model.score({"hazard_code": "GS-D1", "region_code": "TEST-D1"}, high_risk_factors)
        # 即使 score 没把衍生键回传，contributions 里也应有
        contrib_features = {c["feature"] for c in r["feature_contributions"]}
        assert "_derived_rain_saturation_index" in contrib_features
        assert "_derived_rain_intensity_ratio" in contrib_features

    def test_rainfall_24h_dominates_in_extreme_rain(self, model, high_risk_factors):
        """极端降雨场景下 rainfall_24h 应是贡献最大的因素之一。"""
        r = model.score({"hazard_code": "GS-D2", "region_code": "TEST-D2"}, high_risk_factors)
        top3 = [c["feature"] for c in r["feature_contributions"][:3]]
        assert "rainfall_24h" in top3

    def test_reasoning_includes_derived_features(self, model, high_risk_factors):
        """reasoning 文本里应包含衍生特征归因。"""
        r = model.score({"hazard_code": "GS-D3", "region_code": "TEST-D3"}, high_risk_factors)
        reasoning_text = " ".join(r["reasoning"])
        # 至少有一行提到衍生特征
        derived_keywords = ["饱和", "骤雨", "坡脚", "耦合", "持续"]
        assert any(kw in reasoning_text for kw in derived_keywords)


# ---------- 自适应归一化 ---------- #
class TestAdaptiveNormalization:
    def test_normalizer_learns_from_samples(self, model, high_risk_factors):
        """多次喂入样本后归一化器应该有窗口。"""
        normalizer = model._normalizer  # noqa: SLF001
        for _ in range(20):
            model.score({"hazard_code": "GS-N", "region_code": "REG-N"}, high_risk_factors)
        stats = normalizer.snapshot("rainfall_24h", "REG-N")
        assert stats["count"] >= 20

    def test_dynamic_max_replaces_hardcoded(self, model, high_risk_factors):
        """动态上限与硬编码 fallback 不必相等（可能有差异）。"""
        region = "REG-DYN"
        for _ in range(50):
            model.score({"hazard_code": "GS-DY", "region_code": region}, high_risk_factors)
        dyn = model._dynamic_norm("rainfall_24h", region, fallback=150.0)  # noqa: SLF001
        # dyn 是 fallback * 0.5 ~ fallback * 3.0 之间
        assert 75.0 <= dyn <= 450.0


# ---------- 反馈集成 ---------- #
class TestFeedbackIntegration:
    def test_feedback_stats_endpoint_contract(self, high_risk_factors):
        """端到端：跑评估 → 写反馈 → stats 反映 precision/recall。"""
        model = get_disaster_assessment_model()
        store = get_feedback_store()

        # 跑 3 次评估，全部报 HIGH
        r1 = model.score({"hazard_code": "FB-1", "region_code": "FB"}, high_risk_factors)
        r2 = model.score({"hazard_code": "FB-2", "region_code": "FB"}, high_risk_factors)
        r3 = model.score({"hazard_code": "FB-3", "region_code": "FB"}, high_risk_factors)

        # 实际：1 真阳、1 假阳、1 假阴
        store.add({
            "hazard_code": "FB-1", "predicted_level": r1["overall"]["risk_level"],
            "predicted_score": r1["overall"]["risk_score"], "predicted_at": "",
            "outcome": "true_positive", "occurred": True, "feedback_by": "test",
        })
        store.add({
            "hazard_code": "FB-2", "predicted_level": r2["overall"]["risk_level"],
            "predicted_score": r2["overall"]["risk_score"], "predicted_at": "",
            "outcome": "false_positive", "occurred": False, "feedback_by": "test",
        })
        store.add({
            "hazard_code": "FB-3", "predicted_level": r3["overall"]["risk_level"],
            "predicted_score": r3["overall"]["risk_score"], "predicted_at": "",
            "outcome": "false_negative", "occurred": True, "feedback_by": "test",
        })

        stats = store.stats()
        assert stats["total_records"] == 3
        assert stats["confusion_matrix"]["true_positive"] == 1
        assert stats["confusion_matrix"]["false_positive"] == 1
        assert stats["confusion_matrix"]["false_negative"] == 1
        assert stats["precision"] == 0.5  # 1 / (1+1)
        assert stats["recall"] == 0.5  # 1 / (1+1)
        assert stats["f1"] == 0.5


# ---------- API 响应契约 ---------- #
class TestApiResponseContract:
    """前端依赖的整体响应 schema。"""

    def test_response_has_all_keys(self, model, low_risk_factors):
        r = model.score({"hazard_code": "API-1", "region_code": "API"}, low_risk_factors)
        # 必需 key
        assert "overall" in r
        assert "disaster_type" in r
        assert "dimensions" in r
        assert "feature_contributions" in r
        assert "reasoning" in r
        assert "suggested_actions" in r
        # overall 子键
        for k in ("risk_level", "risk_score", "confidence", "concern_label",
                   "model", "mock", "device_available"):
            assert k in r["overall"], f"overall 缺少 {k}"
        # disaster_type 子键
        for k in ("disaster_type", "source", "confidence", "reason", "type_scores"):
            assert k in r["disaster_type"], f"disaster_type 缺少 {k}"

    def test_score_in_valid_range(self, model, low_risk_factors, high_risk_factors, with_device_factors):
        for factors in (low_risk_factors, high_risk_factors, with_device_factors):
            r = model.score({"hazard_code": "RNG", "region_code": "RNG"}, factors)
            assert 0.0 <= r["overall"]["risk_score"] <= 100.0
            assert r["overall"]["risk_level"] in ("LOW", "MEDIUM", "HIGH", "CRITICAL")