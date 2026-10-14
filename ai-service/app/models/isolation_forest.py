"""
Isolation Forest 异常检测。

自动检测 models/isolation_forest.joblib 是否存在：
- 存在：用 scikit-learn 加载并做真实推理
- 不存在：用 3-sigma 启发式检测

模型目录可通过环境变量 MODELS_DIR 覆盖。
"""
from __future__ import annotations

import math
from pathlib import Path
from typing import List, Dict, Any, Optional

import numpy as np

from app.config import MODELS_DIR


class AnomalyDetector:
    """
    Isolation Forest 异常检测封装。

    模型期望 5 维特征：[displacement_rate, crack_width, tilt_angle, rainfall, vibration_freq]
    输入格式：List[List[float]]，每行一个样本的 5 维特征
    （兼容旧格式 List[float] —— 将其视为 5 维重复向量，保留行为兼容）
    """

    # 训练时的特征名（供调用方参考）
    FEATURE_NAMES = [
        "displacement_rate", "crack_width", "tilt_angle", "rainfall", "vibration_freq"
    ]

    def __init__(self, model_path: Optional[str] = None, contamination: float = 0.1):
        self.contamination = contamination
        self.model_path = Path(model_path) if model_path else (MODELS_DIR / "isolation_forest.joblib")
        self.model = None
        self.model_loaded = False
        # 运行时统计（用于启发式 fallback）
        self._running_mean: Optional[float] = None
        self._running_std: float = 1.0
        self._n = 0
        self._load()

    def _load(self) -> None:
        if not self.model_path.exists():
            print(f"[AnomalyDetector] 未找到模型: {self.model_path}，降级为 3-sigma 检测")
            return
        try:
            import joblib
            self.model = joblib.load(str(self.model_path))
            self.model_loaded = True
            print(f"[AnomalyDetector] 模型加载成功: {self.model_path}")
        except ImportError:
            print("[AnomalyDetector] joblib 未安装，降级为 3-sigma 检测")
        except Exception as e:
            print(f"[AnomalyDetector] 模型加载失败: {e}，降级为 3-sigma 检测")

    def is_loaded(self) -> bool:
        return self.model_loaded

    def detect(self, data: List[List[float]]) -> Dict[str, Any]:
        """
        检测输入多维传感器数据中的异常样本。

        Args:
            data: List[List[float]]，每行一个样本的 5 维特征。
                  也支持 List[float]（旧格式），将自动构造 5 维向量。

        Returns:
            {"is_anomaly": bool, "anomaly_indices": List[int],
             "score": float, "model": str, "mock": bool}
        """
        if not data:
            return {"is_anomaly": False, "anomaly_indices": [], "score": 0.0, "model": "heuristic-empty", "mock": True}

        # 兼容旧格式 List[float]（每个元素是一个标量 → 重复填充为 5 维向量）
        if data and isinstance(data[0], (int, float)):
            raw_vals = list(data)
            data = [[v, v, v, v, v] for v in raw_vals]

        arr = np.array(data, dtype=np.float64)
        if arr.ndim == 1:
            arr = arr.reshape(-1, 1)  # 兼容单特征情况

        if self.model_loaded and self.model is not None:
            try:
                return self._run_isolation_forest(arr)
            except Exception as e:
                print(f"[AnomalyDetector] 推理异常: {e}，降级为 3-sigma")
        return self._run_3sigma(arr)

    def _run_isolation_forest(self, arr: np.ndarray) -> Dict[str, Any]:
        import sklearn as _sk  # noqa (确认 sklearn 可用)
        labels = self.model.predict(arr)  # 1=正常, -1=异常
        scores = self.model.decision_function(arr)  # <0 = 异常
        anomaly_indices = [int(i) for i, l in enumerate(labels) if l == -1]
        is_anomaly = len(anomaly_indices) > 0
        score = float(np.mean(scores))
        return {
            "is_anomaly": is_anomaly,
            "anomaly_indices": anomaly_indices,
            "score": round(score, 4),
            "model": "isolation-forest",
            "mock": False,
        }

    def _run_3sigma(self, arr: np.ndarray) -> Dict[str, Any]:
        """基于 3σ 原则的启发式检测（对每列独立检测）。"""
        # 取第一列作为标量序列
        if arr.ndim == 2:
            flat = arr[:, 0]
        else:
            flat = arr.flatten()

        mean = float(np.mean(flat))
        std = float(np.std(flat)) if len(flat) > 1 else 1.0
        std = std if std > 1e-9 else 1.0

        # 更新运行时统计（指数移动平均）
        if self._running_mean is None:
            self._running_mean = mean
        else:
            alpha = 0.2
            self._running_mean = alpha * mean + (1 - alpha) * self._running_mean
        self._n += len(flat)

        anomaly_indices = [int(i) for i, v in enumerate(flat) if abs(v - self._running_mean) > 3 * std]
        is_anomaly = len(anomaly_indices) > 0
        max_dev = float(np.max(np.abs(flat - mean) / std)) if len(flat) > 0 else 0.0
        score = -max_dev if is_anomaly else (3.0 - max_dev)
        return {
            "is_anomaly": is_anomaly,
            "anomaly_indices": anomaly_indices,
            "score": round(score, 4),
            "model": "heuristic-3sigma",
            "mock": True,
        }


_detector: Optional[AnomalyDetector] = None


def get_detector() -> AnomalyDetector:
    global _detector
    if _detector is None:
        _detector = AnomalyDetector()
    return _detector
