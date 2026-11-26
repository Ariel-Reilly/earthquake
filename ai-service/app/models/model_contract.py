"""融合模型的输出契约：objective → 分值口径 / 模型标识的唯一映射。

背景（R11 E1）
--------------
仓内 4 份脚本都会写 ``models/xgboost_fusion.json``，但目标函数不同：

- ``scripts/training/train_fusion_v3.py``：``reg:squarederror``，输出 [0, 100] 连续评分
- ``scripts/training/train_fusion_v1.py`` / ``v2.py``：``binary:logistic``，输出 [0, 1] 概率
- ``app/training/train_xgboost.py``：``binary:logistic``，且被
  ``POST /api/ai/model/retrain`` 直接调用

推理端此前无条件把输出当 [0, 100] 用（``max(0, min(100, pred))``）。于是一旦有人
点了"重训"，模型文件被二分类模型覆盖，所有预测值落到 0~1，``_score_to_level``
全部判 LOW —— 全库预警静默消失，且没有任何报错。

这里把"输出怎么解释"显式化为契约：

1. **objective 从模型本体读取**（``booster.save_config()``），不信任可能过期的
   sidecar meta。v1 只写模型不写 meta，旧逻辑会让 meta 停留在 v3 的 regression，
   造成二次误判。
2. 按 objective 归一到统一的分值口径（0~100 的 risk_score）。
3. 未知 objective 一律拒绝，由调用方降级到加权融合 —— 宁可明确降级，
   也不要把概率当分数静默产出。
"""
from __future__ import annotations

import json
from typing import Any, Dict, Optional, Tuple

# 回归：输出即 [0, 100] 风险分
OBJECTIVE_REGRESSION = "reg:squarederror"
# 二分类：输出为 P(高风险)，需 ×100 映射到分值口径
OBJECTIVE_BINARY = "binary:logistic"

MODEL_ID_REGRESSION = "xgboost-fusion-v3"
MODEL_ID_BINARY = "xgboost-fusion-binary"

#: objective → (模型标识, 原生输出上限)。原生输出乘 ``raw_max * 100`` 归一到分值。
#: 回归模型原生已是 0~100，故 raw_max=1.0 且不再放大（见 normalize_prediction）。
_SPEC: Dict[str, Dict[str, Any]] = {
    OBJECTIVE_REGRESSION: {
        "model_id": MODEL_ID_REGRESSION,
        "native_range": "score_0_100",
        "output_scale": "risk_score",
    },
    OBJECTIVE_BINARY: {
        "model_id": MODEL_ID_BINARY,
        "native_range": "probability_0_1",
        "output_scale": "probability_x100",
    },
}

SUPPORTED_OBJECTIVES = frozenset(_SPEC)


def spec_for(objective: Optional[str]) -> Optional[Dict[str, Any]]:
    """返回 objective 对应的契约描述；不支持时返回 None。"""
    if not objective:
        return None
    return _SPEC.get(objective)


def is_supported(objective: Optional[str]) -> bool:
    return objective in SUPPORTED_OBJECTIVES


def detect_objective(booster: Any) -> Optional[str]:
    """从 Booster 本体读取目标函数名。

    ``save_config()`` 的结构为 ``{"learner": {"objective": {"name": ...}}}``；
    xgboost 各版本均提供该方法，失败时返回 None 交由调用方降级。
    """
    try:
        cfg = json.loads(booster.save_config())
        name = cfg.get("learner", {}).get("objective", {}).get("name")
        return str(name) if name else None
    except Exception:  # noqa: BLE001 - 结构因版本而异，读不到就交给上层降级
        return None


def normalize_prediction(raw: float, objective: Optional[str]) -> Tuple[float, str]:
    """把模型原生输出归一到 [0, 100] 风险分。

    Returns:
        (score, model_id)

    Raises:
        ValueError: objective 不受支持。调用方必须捕获并降级，
            不得把未识别的输出当分数使用。
    """
    spec = spec_for(objective)
    if spec is None:
        raise ValueError(f"不支持的模型 objective: {objective!r}")

    # 二分类概率 → 分值；回归原生已是分值
    score = float(raw) * 100.0 if spec["native_range"] == "probability_0_1" else float(raw)
    score = max(0.0, min(100.0, score))
    return round(score, 2), str(spec["model_id"])


def read_meta_objective(meta: Optional[Dict[str, Any]]) -> Optional[str]:
    """从 sidecar meta 读 objective，仅用于与模型本体交叉校验/展示。"""
    if not meta:
        return None
    value = meta.get("objective")
    return str(value) if value else None
