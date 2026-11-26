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
from app.models.model_contract import (
    detect_objective,
    is_supported,
    normalize_prediction,
)


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
        # objective 从模型本体读取，决定输出怎么解释（见 model_contract）
        self.objective: Optional[str] = None
        # 训练时的真实特征顺序。DMatrix 的 feature_names 必须与之逐位一致，
        # 否则同名但错位的列会被静默当成正确输入。
        self.feature_names: list[str] = list(DEFAULT_WEIGHTS.keys())
        self._load()

    def _load(self) -> None:
        if not self.model_path.exists():
            print(f"[FusionRiskModel] 未找到模型: {self.model_path}，降级为加权融合")
            return
        try:
            import xgboost as xgb
            booster = xgb.Booster()
            booster.load_model(str(self.model_path))
        except ImportError:
            print("[FusionRiskModel] xgboost 未安装，降级为加权融合")
            return
        except Exception as e:
            print(f"[FusionRiskModel] 模型加载失败: {e}，降级为加权融合")
            return

        # 特征顺序以模型本体为准（训练时写入），不是以本文件的常量顺序为准。
        trained_features = list(getattr(booster, "feature_names", None) or [])
        if trained_features:
            if set(trained_features) != set(DEFAULT_WEIGHTS):
                print(
                    "[FusionRiskModel] 模型特征集与推理端不一致 "
                    f"(model={trained_features}, expected={list(DEFAULT_WEIGHTS)})，降级为加权融合"
                )
                return
            self.feature_names = trained_features

        # objective 决定分值口径：回归已是 0~100，二分类是 0~1 概率。
        # 不读 sidecar meta —— v1 只写模型不写 meta，meta 会停留在上一版的目标函数上。
        objective = detect_objective(booster)
        if not is_supported(objective):
            print(
                f"[FusionRiskModel] 模型 objective 不受支持: {objective!r}，"
                "降级为加权融合（避免把概率当分数静默产出）"
            )
            return

        self.model = booster
        self.objective = objective
        self.model_loaded = True
        print(f"[FusionRiskModel] 模型加载成功: {self.model_path} (objective={objective})")

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
        """XGBoost 推理。

        输出口径由模型自身的 objective 决定（见 ``model_contract``）：
        - ``reg:squarederror``（v3）：原生即 [0, 100] 风险分
        - ``binary:logistic``（v1/v2/train_xgboost）：原生是 P(高风险)，
          必须 ×100 才能进入分档逻辑

        历史上这里无条件当 [0, 100] 用，二分类模型一重训就让所有评分落到
        0~1 → 全量 LOW。objective 已在 ``_load`` 里校验过，这里不会静默猜。
        """
        import xgboost as xgb
        # 按模型训练时的真实列序构造向量（_load 已校验特征集合一致）
        feature_names = self.feature_names
        vec = np.array([[factors.get(name, 0.0) for name in feature_names]], dtype=np.float32)
        dmatrix = xgb.DMatrix(vec, feature_names=feature_names)
        raw = float(self.model.predict(dmatrix)[0])
        score, model_id = normalize_prediction(raw, self.objective)
        return {
            "risk_level": self._score_to_level(score),
            "risk_score": score,
            "confidence": round(min(0.95, 0.5 + 0.005 * score), 4),
            "model": model_id,
            "objective": self.objective,
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
            "objective": None,
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
