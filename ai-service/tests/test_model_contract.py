"""模型输出契约测试（R11 E1）。

锁死两条不变量，防止再次出现"重训一次全变 LOW"：

1. ``binary:logistic`` 的原生输出是 0~1 概率，必须 ×100 才进入 0~100 分档；
2. 未知 objective 一律拒绝，不允许被当成风险分静默使用。
"""
from __future__ import annotations

import json

import pytest

from app.models.model_contract import (
    MODEL_ID_BINARY,
    MODEL_ID_REGRESSION,
    OBJECTIVE_BINARY,
    OBJECTIVE_REGRESSION,
    detect_objective,
    is_supported,
    normalize_prediction,
    read_meta_objective,
    spec_for,
)


class _FakeBooster:
    def __init__(self, config: dict):
        self._config = config

    def save_config(self) -> str:
        return json.dumps(self._config)


class TestNormalizePrediction:
    def test_regression_passes_through(self):
        score, model_id = normalize_prediction(78.5, OBJECTIVE_REGRESSION)
        assert score == 78.5
        assert model_id == MODEL_ID_REGRESSION

    def test_binary_probability_scaled_to_score(self):
        # 0.82 的"高风险概率"应是 82 分（CRITICAL 档），而不是 0.82 分（LOW 档）
        score, model_id = normalize_prediction(0.82, OBJECTIVE_BINARY)
        assert score == 82.0
        assert model_id == MODEL_ID_BINARY

    def test_clamped_to_valid_range(self):
        assert normalize_prediction(-3.0, OBJECTIVE_REGRESSION)[0] == 0.0
        assert normalize_prediction(130.0, OBJECTIVE_REGRESSION)[0] == 100.0
        assert normalize_prediction(1.7, OBJECTIVE_BINARY)[0] == 100.0

    @pytest.mark.parametrize("objective", [None, "", "multi:softprob", "count:poisson"])
    def test_unsupported_objective_rejected(self, objective):
        with pytest.raises(ValueError):
            normalize_prediction(0.5, objective)

    def test_spec_model_ids(self):
        assert spec_for(OBJECTIVE_REGRESSION)["model_id"] == MODEL_ID_REGRESSION
        assert spec_for(OBJECTIVE_BINARY)["model_id"] == MODEL_ID_BINARY
        assert is_supported(OBJECTIVE_REGRESSION) and is_supported(OBJECTIVE_BINARY)


class TestDetectObjective:
    def test_reads_objective_from_booster_config(self):
        booster = _FakeBooster({"learner": {"objective": {"name": "binary:logistic"}}})
        assert detect_objective(booster) == "binary:logistic"

    def test_returns_none_on_broken_config(self):
        class _Broken:
            def save_config(self):
                raise RuntimeError("boom")

        assert detect_objective(_Broken()) is None


def test_read_meta_objective():
    assert read_meta_objective({"objective": "reg:squarederror"}) == "reg:squarederror"
    assert read_meta_objective({}) is None
    assert read_meta_objective(None) is None
