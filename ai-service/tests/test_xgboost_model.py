"""XGBoost 模型加载与推理测试。"""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from app.models.xgboost_model import MODELS_DIR, FusionRiskModel, get_fusion_model


class TestFusionRiskModel:
    def test_model_path_exists(self):
        m = FusionRiskModel()
        assert m.model_path == MODELS_DIR / "xgboost_fusion.json"

    def test_singleton(self):
        a = get_fusion_model()
        b = get_fusion_model()
        assert a is b

    def test_load_real_model(self):
        """如果磁盘上有 xgboost_fusion.json，模型应加载成功。"""
        if not (MODELS_DIR / "xgboost_fusion.json").exists():
            pytest.skip("真实模型未训练")
        m = FusionRiskModel()
        assert m.is_loaded()

    def test_high_factors_yields_critical(self):
        """极端高风险因子应给 CRITICAL。"""
        m = get_fusion_model()
        if not m.is_loaded():
            pytest.skip("模型未加载")
        result = m.score({
            "displacement_rate": 25.0,
            "displacement_total": 180.0,
            "rainfall_24h": 130.0,
            "rainfall_7d": 350.0,
            "crack_width": 40.0,
            "earthquake_mag": 5.5,
            "site_grade": 4.5,
        })
        assert result["risk_level"] == "CRITICAL"
        assert result["model"] == "xgboost-fusion"
        assert result["mock"] is False

    def test_low_factors_yields_low(self):
        """低风险因子应给 LOW。"""
        m = get_fusion_model()
        if not m.is_loaded():
            pytest.skip("模型未加载")
        result = m.score({
            "displacement_rate": 0.5,
            "displacement_total": 5.0,
            "rainfall_24h": 5.0,
            "rainfall_7d": 20.0,
            "crack_width": 1.5,
            "earthquake_mag": 0.5,
            "site_grade": 1.5,
        })
        assert result["risk_level"] == "LOW"
        assert result["model"] == "xgboost-fusion"

    def test_meta_json_exists(self):
        meta_path = MODELS_DIR / "xgboost_fusion.meta.json"
        if not meta_path.exists():
            pytest.skip("meta 未生成")
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        assert "feature_importance" in meta
        assert "val_metrics" in meta
        assert meta["val_metrics"]["f1"] >= 0.85
        assert len(meta["feature_importance"]) == 7