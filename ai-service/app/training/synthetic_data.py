"""
合成训练数据生成器（基于地质灾害物理规则）。

设计目标：
  - demo 阶段没有真实 hazard_metric_history 表，反馈样本也少
  - 但 XGBoost 必须先训练出有意义的模型才能"真实推理"
  - 用基于地质灾害成因的物理规则生成 7 维因子 + 0/1 label
  - 与 24 类灾害成因经验对齐（GB 18306 / 中国地质灾害防治工程行业经验）

规则：
  - 高风险（label=1）：强降雨 + 位移加速 + 裂缝扩张 + 较差场地
  - 低风险（label=0）：少雨 + 位移平稳 + 裂缝窄 + 良好场地
  - 中间地带：按"饱和指数"连续映射到概率

输出 schema 与 train_xgboost._load_feedback_rows 对齐：
  - factors: Dict[str, float]
  - label: int (0/1)
  - source: "synthetic"

7 维特征（与 xgboost_model.DEFAULT_WEIGHTS 一致）：
  displacement_rate, displacement_total, rainfall_24h, rainfall_7d,
  crack_width, earthquake_mag, site_grade
"""
from __future__ import annotations

import random
from typing import Dict, List

import numpy as np


# 单因子归一化上限（与 xgboost_model.NORM_MAX 一致）
_NORM_MAX = {
    "displacement_rate": 30.0,
    "displacement_total": 200.0,
    "rainfall_24h": 150.0,
    "rainfall_7d": 400.0,
    "crack_width": 50.0,
    "earthquake_mag": 7.0,
    "site_grade": 5.0,
}


def _sample_low() -> Dict[str, float]:
    """低风险样本：稳定基岩 + 少雨 + 无位移。"""
    return {
        "displacement_rate": random.uniform(0.0, 1.5),
        "displacement_total": random.uniform(0.0, 8.0),
        "rainfall_24h": random.uniform(0.0, 15.0),
        "rainfall_7d": random.uniform(0.0, 40.0),
        "crack_width": random.uniform(0.5, 3.0),
        "earthquake_mag": random.uniform(0.0, 1.5),
        "site_grade": random.uniform(1.0, 2.0),
    }


def _sample_high() -> Dict[str, float]:
    """高风险样本：极端组合（按 NORM_MAX 的 60-100% 采样）。"""
    return {
        "displacement_rate": random.uniform(15.0, 30.0),
        "displacement_total": random.uniform(100.0, 200.0),
        "rainfall_24h": random.uniform(70.0, 150.0),
        "rainfall_7d": random.uniform(200.0, 400.0),
        "crack_width": random.uniform(25.0, 50.0),
        "earthquake_mag": random.uniform(3.5, 7.0),
        "site_grade": random.uniform(3.5, 5.0),
    }


def _sample_continuous() -> Dict[str, float]:
    """连续区间：按 saturation_proxy 概率分布采样。

    让 XGBoost 能在 0/1 边界上拟合出非线性的"风险曲面"，
    而不是简单学到一个线性阈值。
    """
    # 在 [0, 1] 上采样一个"风险概率"
    p = random.random()
    # 给一个偏向中等区间的偏置（绝大多数灾害的真实数据集中在中等风险段）
    p = 0.3 + 0.4 * p if random.random() < 0.5 else 0.05 + 0.3 * p

    factors = {}
    for name, max_v in _NORM_MAX.items():
        # 各因子按 p 比例缩放 + ±20% 抖动
        base = p * max_v
        jitter = random.uniform(0.8, 1.2)
        val = max(0.0, min(max_v, base * jitter))
        factors[name] = val
    return factors


def _label_from_factors(factors: Dict[str, float]) -> int:
    """用物理规则判定 0/1 标签（用于训练数据，不参与推理）。

    规则：
      - 饱和指数 rainfall_saturation = (rainfall_24h / 150 + rainfall_7d / 400) / 2
      - 位移加权 displacement_norm = displacement_rate / 30 + displacement_total / 200 + crack_width / 50
      - 场地放大 site_factor = site_grade / 5
      - 地震触发 earthquake_factor = earthquake_mag / 7
      - risk_score = 0.35*saturation + 0.35*displacement_norm + 0.20*site_factor + 0.10*earthquake_factor
      - risk_score >= 0.55 → label=1
    """
    sat = (factors["rainfall_24h"] / 150.0 + factors["rainfall_7d"] / 400.0) / 2.0
    disp = (
        factors["displacement_rate"] / 30.0
        + factors["displacement_total"] / 200.0
        + factors["crack_width"] / 50.0
    ) / 3.0
    site = factors["site_grade"] / 5.0
    quake = factors["earthquake_mag"] / 7.0
    score = 0.35 * sat + 0.35 * disp + 0.20 * site + 0.10 * quake
    return 1 if score >= 0.55 else 0


class SyntheticDataset:
    """合成数据集生成器。"""

    @staticmethod
    def generate(n: int = 1000, low_ratio: float = 0.35, high_ratio: float = 0.25) -> List[Dict]:
        """生成 n 条合成样本。

        Args:
            n: 样本总数
            low_ratio: 低风险显式样本占比（label=0，确定性强）
            high_ratio: 高风险显式样本占比（label=1，确定性强）
            其余 1-low_ratio-high_ratio 为连续区间（label 由规则判定）

        Returns:
            [{factors: ..., label: int, source: "synthetic"}, ...]
        """
        if n <= 0:
            return []

        n_low = int(n * low_ratio)
        n_high = int(n * high_ratio)
        n_cont = n - n_low - n_high

        rows: List[Dict] = []

        # 低风险显式样本：label 强制 0
        for _ in range(n_low):
            rows.append({"factors": _sample_low(), "label": 0, "source": "synthetic-low"})

        # 高风险显式样本：label 强制 1
        for _ in range(n_high):
            rows.append({"factors": _sample_high(), "label": 1, "source": "synthetic-high"})

        # 连续区间样本：label 由规则判定
        for _ in range(n_cont):
            factors = _sample_continuous()
            label = _label_from_factors(factors)
            rows.append({"factors": factors, "label": label, "source": "synthetic-continuous"})

        # 打乱
        random.shuffle(rows)
        return rows

    @staticmethod
    def stats(rows: List[Dict]) -> Dict:
        """汇总数据集统计信息（给 model_info / 测试用）。"""
        if not rows:
            return {"n": 0, "pos_rate": 0.0, "by_source": {}}
        labels = [r["label"] for r in rows]
        sources = {}
        for r in rows:
            sources[r.get("source", "unknown")] = sources.get(r.get("source", "unknown"), 0) + 1
        return {
            "n": len(rows),
            "pos_rate": round(sum(labels) / len(labels), 4),
            "by_source": sources,
        }