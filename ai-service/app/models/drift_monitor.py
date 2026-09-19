"""
模型漂移监控模块 v1.0

功能：
- 计算特征级 PSI（Population Stability Index）
- 计算 KS 检验统计量
- 检测数据分布漂移
- 支持基线数据存储
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import numpy as np
from scipy import stats

MODELS_DIR = Path(__file__).resolve().parent.parent.parent / "models"
BASELINE_DIR = MODELS_DIR / "baseline"


@dataclass
class DriftResult:
    """漂移检测结果"""
    feature: str
    psi: float
    ks_stat: float
    p_value: float
    drift_level: str  # NORMAL / SLIGHT / SIGNIFICANT
    message: str


@dataclass
class OverallDriftResult:
    """整体漂移检测结果"""
    model_id: str
    overall_status: str  # NORMAL / SLIGHT_DRIFT / SIGNIFICANT_DRIFT
    features: Dict[str, DriftResult]
    recommendation: str
    detected_at: str


class DriftMonitor:
    """
    漂移监控器

    使用 PSI 和 KS 检验检测数据分布漂移

    PSI 阈值：
    - PSI < 0.1: 正常
    - 0.1 <= PSI < 0.25: 轻微漂移
    - PSI >= 0.25: 显著漂移
    """

    THRESHOLD_SLIGHT = 0.1
    THRESHOLD_SIGNIFICANT = 0.25
    DEFAULT_BUCKETS = 10

    def __init__(self, model_id: str):
        self.model_id = model_id
        self.baseline_data: Dict[str, np.ndarray] = {}
        self._load_baseline()

    def _load_baseline(self) -> None:
        """加载基线数据"""
        baseline_file = BASELINE_DIR / f"{self.model_id}_baseline.json"
        if not baseline_file.exists():
            return

        import json
        with open(baseline_file, "r") as f:
            data = json.load(f)

        for feature, values in data.items():
            self.baseline_data[feature] = np.array(values)

    def _calculate_psi(
        self,
        expected: np.ndarray,
        actual: np.ndarray,
        buckets: int = DEFAULT_BUCKETS
    ) -> float:
        """
        计算群体稳定性指数 (PSI)

        PSI = Σ ((实际占比 - 期望占比) * ln(实际占比 / 期望占比))

        Args:
            expected: 期望数据（基线）
            actual: 实际数据（当前）
            buckets: 分桶数量

        Returns:
            PSI 值
        """
        # 过滤无效值
        expected = expected[~np.isnan(expected) & ~np.isinf(expected)]
        actual = actual[~np.isnan(actual) & ~np.isinf(actual)]

        if len(expected) < 10 or len(actual) < 10:
            return 0.0

        # 计算分位点
        breakpoints = np.linspace(0, 100, buckets + 1)

        # 使用期望数据的分位数作为断点
        expected_bins = []
        for i in range(len(breakpoints) - 1):
            p_low = np.percentile(expected, breakpoints[i])
            p_high = np.percentile(expected, breakpoints[i + 1])
            expected_bins.append((p_low, p_high))

        # 计算每个桶中的比例
        expected_counts = np.zeros(buckets)
        actual_counts = np.zeros(buckets)

        for i, (low, high) in enumerate(expected_bins):
            if i == 0:
                expected_counts[i] = np.sum(expected <= high) / len(expected)
                actual_counts[i] = np.sum(actual <= high) / len(actual)
            elif i == buckets - 1:
                expected_counts[i] = np.sum(expected >= low) / len(expected)
                actual_counts[i] = np.sum(actual >= low) / len(actual)
            else:
                expected_counts[i] = np.sum((expected >= low) & (expected < high)) / len(expected)
                actual_counts[i] = np.sum((actual >= low) & (actual < high)) / len(actual)

        # 避免除零
        expected_counts = np.where(expected_counts == 0, 1e-6, expected_counts)
        actual_counts = np.where(actual_counts == 0, 1e-6, actual_counts)

        # 归一化为比例
        expected_counts = expected_counts / expected_counts.sum()
        actual_counts = actual_counts / actual_counts.sum()

        # 计算 PSI
        psi = np.sum(
            (actual_counts - expected_counts) * np.log(actual_counts / expected_counts)
        )

        return max(0.0, float(psi))

    def _calculate_ks(
        self,
        expected: np.ndarray,
        actual: np.ndarray
    ) -> Tuple[float, float]:
        """
        计算 KS 检验统计量

        Args:
            expected: 期望数据（基线）
            actual: 实际数据（当前）

        Returns:
            (KS 统计量, p 值)
        """
        # 过滤无效值
        expected = expected[~np.isnan(expected) & ~np.isinf(expected)]
        actual = actual[~np.isnan(actual) & ~np.isinf(actual)]

        if len(expected) < 10 or len(actual) < 10:
            return 0.0, 1.0

        ks_stat, p_value = stats.ks_2samp(expected, actual)
        return float(ks_stat), float(p_value)

    def _classify_drift(self, psi: float) -> str:
        """根据 PSI 值分类漂移级别"""
        if psi < self.THRESHOLD_SLIGHT:
            return "NORMAL"
        elif psi < self.THRESHOLD_SIGNIFICANT:
            return "SLIGHT"
        else:
            return "SIGNIFICANT"

    def detect(
        self,
        current_data: Dict[str, List[float]]
    ) -> OverallDriftResult:
        """
        检测漂移

        Args:
            current_data: 当前数据 {特征名: [值列表]}

        Returns:
            漂移检测结果
        """
        features = {}
        overall_level = "NORMAL"
        significant_features = []
        slight_features = []

        for feature, values in current_data.items():
            if feature not in self.baseline_data:
                continue

            expected = self.baseline_data[feature]
            actual = np.array(values)

            # 计算 PSI
            psi = self._calculate_psi(expected, actual)

            # 计算 KS
            ks_stat, p_value = self._calculate_ks(expected, actual)

            # 分类
            drift_level = self._classify_drift(psi)

            # 生成消息
            if drift_level == "NORMAL":
                message = f"特征 {feature} 分布正常，PSI={psi:.4f}"
            elif drift_level == "SLIGHT":
                message = f"特征 {feature} 存在轻微漂移，PSI={psi:.4f}，建议关注"
                slight_features.append(feature)
            else:
                message = f"特征 {feature} 存在显著漂移，PSI={psi:.4f}，建议重训模型"
                significant_features.append(feature)

            features[feature] = DriftResult(
                feature=feature,
                psi=round(psi, 4),
                ks_stat=round(ks_stat, 4),
                p_value=round(p_value, 4),
                drift_level=drift_level,
                message=message
            )

            # 更新整体状态
            if drift_level == "SIGNIFICANT":
                overall_level = "SIGNIFICANT_DRIFT"
            elif drift_level == "SLIGHT" and overall_level == "NORMAL":
                overall_level = "SLIGHT_DRIFT"

        # 生成建议
        recommendation = self._generate_recommendation(
            overall_level, significant_features, slight_features
        )

        return OverallDriftResult(
            model_id=self.model_id,
            overall_status=overall_level,
            features=features,
            recommendation=recommendation,
            detected_at=datetime.now().isoformat()
        )

    def _generate_recommendation(
        self,
        overall_status: str,
        significant: List[str],
        slight: List[str]
    ) -> str:
        """生成建议"""
        if overall_status == "NORMAL":
            return "各特征分布正常，模型运行稳定，建议保持常规监测。"

        parts = []
        if significant:
            parts.append(f"以下特征存在显著漂移: {', '.join(significant)}，建议立即重训模型。")
        if slight:
            parts.append(f"以下特征存在轻微漂移: {', '.join(slight)}，建议持续关注。")

        if significant:
            parts.append("建议收集最新数据后重训模型，并评估新模型效果。")
        else:
            parts.append("建议持续监测，如漂移加剧则重训。")

        return " ".join(parts)

    def update_baseline(
        self,
        data: Dict[str, List[float]],
        description: str = ""
    ) -> bool:
        """
        更新基线数据

        Args:
            data: 新基线数据
            description: 描述信息

        Returns:
            是否成功
        """
        BASELINE_DIR.mkdir(parents=True, exist_ok=True)

        import json

        # 合并新数据
        for feature, values in data.items():
            if feature in self.baseline_data:
                # 合并：保留基线数据，追加新数据（限制总数）
                combined = list(self.baseline_data[feature]) + values
                if len(combined) > 10000:  # 最多保留 10000 条
                    combined = combined[-10000:]
                self.baseline_data[feature] = np.array(combined)
            else:
                self.baseline_data[feature] = np.array(values)

        # 保存到文件
        baseline_file = BASELINE_DIR / f"{self.model_id}_baseline.json"
        save_data = {k: v.tolist() for k, v in self.baseline_data.items()}

        metadata = {
            "model_id": self.model_id,
            "description": description,
            "updated_at": datetime.now().isoformat(),
            "features": list(self.baseline_data.keys()),
            "sample_counts": {k: len(v) for k, v in self.baseline_data.items()},
            "data": save_data
        }

        with open(baseline_file, "w") as f:
            json.dump(metadata, f, indent=2)

        return True

    def get_trend(
        self,
        history: List[Dict[str, Any]],
        feature: Optional[str] = None
    ) -> List[Dict[str, Any]]:
        """
        获取漂移趋势

        Args:
            history: 历史漂移记录
            feature: 可选，指定特征

        Returns:
            趋势数据
        """
        if feature:
            trend = [
                {"timestamp": r["timestamp"], "psi": r["features"].get(feature, {}).get("psi", 0)}
                for r in history
                if feature in r.get("features", {})
            ]
        else:
            # 整体 PSI（取平均）
            trend = [
                {
                    "timestamp": r["timestamp"],
                    "psi": np.mean([f.get("psi", 0) for f in r.get("features", {}).values()])
                }
                for r in history
            ]

        return sorted(trend, key=lambda x: x["timestamp"])


# 全局漂移监控器缓存
_monitors: Dict[str, DriftMonitor] = {}


def get_drift_monitor(model_id: str) -> DriftMonitor:
    """获取漂移监控器实例"""
    if model_id not in _monitors:
        _monitors[model_id] = DriftMonitor(model_id)
    return _monitors[model_id]
