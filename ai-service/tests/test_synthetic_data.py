"""合成数据集测试。"""
from __future__ import annotations

import random

import pytest

from app.training.synthetic_data import SyntheticDataset, _label_from_factors


@pytest.fixture(autouse=True)
def seed_rng():
    random.seed(42)
    yield
    # 测试结束不重置；下一测试会重新 seed


class TestSyntheticDataset:
    def test_generate_count(self):
        rows = SyntheticDataset.generate(n=2000)
        assert len(rows) == 2000

    def test_pos_rate_in_range(self):
        rows = SyntheticDataset.generate(n=2000)
        labels = [r["label"] for r in rows]
        pos_rate = sum(labels) / len(labels)
        # 合成数据应同时覆盖 0 和 1，pos_rate 在 [0.15, 0.55] 之间
        assert 0.15 < pos_rate < 0.55

    def test_label_consistency_with_high_factors(self):
        """高因子组合应被标为 1。"""
        high_factors = {
            "displacement_rate": 25.0,
            "displacement_total": 180.0,
            "rainfall_24h": 130.0,
            "rainfall_7d": 350.0,
            "crack_width": 40.0,
            "earthquake_mag": 5.5,
            "site_grade": 4.5,
        }
        assert _label_from_factors(high_factors) == 1

    def test_label_consistency_with_low_factors(self):
        """低因子组合应被标为 0。"""
        low_factors = {
            "displacement_rate": 0.5,
            "displacement_total": 5.0,
            "rainfall_24h": 5.0,
            "rainfall_7d": 20.0,
            "crack_width": 1.5,
            "earthquake_mag": 0.5,
            "site_grade": 1.5,
        }
        assert _label_from_factors(low_factors) == 0

    def test_stats(self):
        rows = SyntheticDataset.generate(n=500)
        stats = SyntheticDataset.stats(rows)
        assert stats["n"] == 500
        assert 0.0 < stats["pos_rate"] < 1.0
        assert "synthetic-low" in stats["by_source"]
        assert "synthetic-high" in stats["by_source"]

    def test_generate_zero_returns_empty(self):
        assert SyntheticDataset.generate(n=0) == []