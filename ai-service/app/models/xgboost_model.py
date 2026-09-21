"""
XGBoost 融合预警模型：多因子综合风险评分。

自动检测 models/xgboost_fusion.json 是否存在：
- 存在：用 xgboost 加载并做真实推理
- 不存在：用加权线性融合（权重根据因子重要性手工设定）

模型目录可通过环境变量 MODELS_DIR 覆盖。
"""
from __future__ import annotations

import math
import random
from pathlib import Path
from typing import Dict, Any, Optional, Tuple

import numpy as np

from app.config import MODELS_DIR


# 默认因子权重（融合方向一致：值越大越危险）
DEFAULT_WEIGHTS: Dict[str, float] = {
    "displacement_rate": 0.25,   # 位移速率 mm/day
    "displacement_total": 0.15,   # 累计位移 mm
    "rainfall_24h": 0.20,         # 24h 降雨量 mm
    "rainfall_7d": 0.12,          # 7d 累计降雨量 mm
    "crack_width": 0.10,          # 裂缝宽度 mm
    "earthquake_mag": 0.10,       # 最近地震震级
    "site_grade": 0.08,           # 场地等级 1-5
}

# 各因子归一化最大值（超过则截断为 1）
NORM_MAX: Dict[str, float] = {
    "displacement_rate": 30.0,
    "displacement_total": 200.0,
    "rainfall_24h": 150.0,
    "rainfall_7d": 400.0,
    "crack_width": 50.0,
    "earthquake_mag": 7.0,
    "site_grade": 5.0,
}


class FusionRiskModel:
    """XGBoost 融合风险模型封装。"""

    def __init__(self, model_path: Optional[str] = None):
        self.model_path = Path(model_path) if model_path else (MODELS_DIR / "xgboost_fusion.json")
        self.model = None
        self.model_loaded = False
        self._load()

    def _load(self) -> None:
        if not self.model_path.exists():
            print(f"[FusionRiskModel] 未找到模型: {self.model_path}，降级为加权融合")
            return
        try:
            import xgboost as xgb
            booster = xgb.Booster()
            booster.load_model(str(self.model_path))
            self.model = booster
            self.model_loaded = True
            print(f"[FusionRiskModel] 模型加载成功: {self.model_path}")
        except ImportError:
            print("[FusionRiskModel] xgboost 未安装，降级为加权融合")
        except Exception as e:
            print(f"[FusionRiskModel] 模型加载失败: {e}，降级为加权融合")

    def is_loaded(self) -> bool:
        return self.model_loaded

    def score(self, factors: Dict[str, float]) -> Dict[str, Any]:
        """
        综合风险评分。

        Args:
            factors: 因子字典，如 {"displacement_rate": 5.2, "rainfall_24h": 120.0, ...}

        Returns:
            {"risk_level": "LOW/MEDIUM/HIGH/CRITICAL", "risk_score": float, "confidence": float, "model": str, "mock": bool}
        """
        if self.model_loaded and self.model is not None:
            try:
                return self._run_xgboost(factors)
            except Exception as e:
                print(f"[FusionRiskModel] 推理异常: {e}，降级为加权融合")
        return self._run_weighted(factors)

    def _run_xgboost(self, factors: Dict[str, float]) -> Dict[str, Any]:
        import xgboost as xgb
        # 按照训练时的特征顺序构造向量
        feature_names = list(DEFAULT_WEIGHTS.keys())
        vec = np.array([[factors.get(name, 0.0) for name in feature_names]], dtype=np.float32)
        dmatrix = xgb.DMatrix(vec, feature_names=feature_names)
        pred = float(self.model.predict(dmatrix)[0])
        # pred 在 [0,1] 区间（sigmoid 输出），放大到 [0,100]
        score = round(max(0.0, min(100.0, pred * 100.0)), 2)
        level = self._score_to_level(score)
        return {
            "risk_level": level,
            "risk_score": score,
            "confidence": round(min(0.95, 0.5 + 0.005 * score), 4),
            "model": "xgboost-fusion",
            "mock": False,
        }

    def _run_weighted(self, factors: Dict[str, float]) -> Dict[str, Any]:
        """加权线性融合：各因子归一化后按权重求和。"""
        total = 0.0
        total_weight = 0.0
        for name, weight in DEFAULT_WEIGHTS.items():
            raw = factors.get(name, 0.0)
            max_val = NORM_MAX.get(name, 1.0)
            norm = min(1.0, max(0.0, raw / max_val))
            total += norm * weight
            total_weight += weight
        if total_weight > 0:
            total /= total_weight
        score = round(total * 100.0, 2)
        level = self._score_to_level(score)
        # 置信度：因子覆盖越多越高
        covered = sum(1 for k in DEFAULT_WEIGHTS if k in factors)
        confidence = round(min(0.85, 0.3 + 0.07 * covered), 4)
        return {
            "risk_level": level,
            "risk_score": score,
            "confidence": confidence,
            "model": "heuristic-weighted-fusion",
            "mock": True,
        }

    @staticmethod
    def _score_to_level(score: float) -> str:
        if score >= 80:
            return "CRITICAL"
        if score >= 60:
            return "HIGH"
        if score >= 35:
            return "MEDIUM"
        return "LOW"

    # 公开的 device 维度加权评分，供 comprehensive_model 复用
    @classmethod
    def device_weighted_score(cls, factors: Dict[str, float]) -> Tuple[float, float]:
        """仅复用 device 维度 7 因子的加权融合，返回 (score, confidence)。"""
        instance = cls.__new__(cls)
        result = instance._run_weighted(factors)
        return result["risk_score"], result["confidence"]


_fusion_model: Optional[FusionRiskModel] = None


def get_fusion_model() -> FusionRiskModel:
    global _fusion_model
    if _fusion_model is None:
        _fusion_model = FusionRiskModel()
    return _fusion_model
