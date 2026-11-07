"""XGBoost 模型加载与推理测试。

口径说明（2026-11-06 修正）：当前磁盘上是 **v3 回归模型**
（``objective = reg:squarederror``，输出 [0,100] 连续 risk_score）。
历史断言按 v2 分类口径写成 ``model == "xgboost-fusion"`` 和 ``val_metrics.f1``，
与实现不符，导致全量 pytest 长期 3 例失败。这里改为断言 v3 的真实约定：
模型标识带 ``-v3`` 后缀、meta 提供 gain 重要性、回归指标用 MAE/RMSE/分档一致性。
"""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from app.models.xgboost_model import MODELS_DIR, FusionRiskModel, get_fusion_model

# v3 回归模型的对外标识；与 app/models/xgboost_model.py::_run_xgboost 保持一致。
EXPECTED_MODEL_ID = "xgboost-fusion-v3"


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
        assert result["model"] == EXPECTED_MODEL_ID
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
        assert result["model"] == EXPECTED_MODEL_ID

    def test_meta_json_exists(self):
        meta_path = MODELS_DIR / "xgboost_fusion.meta.json"
        if not meta_path.exists():
            pytest.skip("meta 未生成")
        meta = json.loads(meta_path.read_text(encoding="utf-8"))

        # 基础结构
        assert meta["version"].startswith("3.")
        assert meta["objective"] == "reg:squarederror"
        assert meta["n_features"] == 7

        # 特征重要性：v3 训练脚本此前只 print 不落盘，2026-11-06 已补齐并回填
        assert "feature_importance" in meta, "meta 缺少 feature_importance（按 gain 落盘）"
        assert meta.get("feature_importance_type") == "gain"
        assert len(meta["feature_importance"]) == 7
        assert set(meta["feature_importance"]) == set(meta["feature_names"])
        assert all(v >= 0 for v in meta["feature_importance"].values())
        assert sum(meta["feature_importance"].values()) > 0

        # 回归模型没有 f1；指标用 MAE / RMSE / 分档一致性。
        # 评估集是合成数据（eval_dataset=synthetic），阈值只用于防回归劣化。
        vm = meta["val_metrics"]
        assert vm["eval_dataset"] in ("synthetic", "real")
        assert vm["rmse"] < 2.0, f"验证集 RMSE 劣化: {vm['rmse']}"
        assert vm["mae"] < 1.5, f"验证集 MAE 劣化: {vm['mae']}"
        assert vm["level_agreement"] >= 0.9, f"分档一致性劣化: {vm['level_agreement']}"
        # best_score 是 early_stopping 记录的 val RMSE，应与重算值一致
        assert abs(meta["best_score"] - vm["rmse"]) < 0.01
