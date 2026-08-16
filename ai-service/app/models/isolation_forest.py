"""
Isolation Forest 异常检测。

自动检测 models/isolation_forest.joblib 是否存在：
- 存在：用 scikit-learn 加载并做真实推理
- 不存在：用 3-sigma 启发式检测
"""
from __future__ import annotations

import math
from pathlib import Path
from typing import List, Dict, Any, Optional

import numpy as np

MODELS_DIR = Path(__file__).resolve().parent.parent.parent / "models"


class AnomalyDetector:
    """Isolation Forest 异常检测封装。"""

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

    def detect(self, data: List[float]) -> Dict[str, Any]:
        """检测输入序列中的异常点。"""
        if not data:
            return {"is_anomaly": False, "anomaly_indices": [], "score": 0.0, "model": "heuristic-empty", "mock": True}

        arr = np.array(data, dtype=np.float64).reshape(-1, 1)
        if self.model_loaded and self.model is not None:
            try:
                return self._run_isolation_forest(arr)
            except Exception as e:
                print(f"[AnomalyDetector] 推理异常: {e}，降级为 3-sigma")
        return self._run_3sigma(arr.flatten())

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
        """基于 3σ 原则的启发式检测。"""
        mean = float(np.mean(arr))
        std = float(np.std(arr)) if len(arr) > 1 else 1.0
        std = std if std > 1e-9 else 1.0

        # 更新运行时统计（指数移动平均）
        if self._running_mean is None:
            self._running_mean = mean
        else:
            alpha = 0.2
            self._running_mean = alpha * mean + (1 - alpha) * self._running_mean
        self._n += len(arr)

        anomaly_indices = [int(i) for i, v in enumerate(arr) if abs(v - self._running_mean) > 3 * std]
        is_anomaly = len(anomaly_indices) > 0
        # 综合异常分数：偏离均值的最大 sigma 倍数（负 = 异常）
        max_dev = float(np.max(np.abs(arr - mean) / std))
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
