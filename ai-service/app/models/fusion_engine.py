"""
多模态融合引擎 v1.0

功能：
- 融合卫星影像、时序预测、社交媒体舆情等多模态数据
- 加权融合各模态评分
- 生成可解释的融合结果
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from datetime import datetime
from typing import Any, Dict, List, Optional

import numpy as np


@dataclass
class ModalScore:
    """单模态评分"""
    modal: str
    score: float  # 0-100
    confidence: float  # 0-1
    details: Dict[str, Any]


@dataclass
class FusionResult:
    """融合结果"""
    event_id: str
    fused_score: float  # 0-100
    level: str  # CRITICAL / HIGH / MEDIUM / LOW
    confidence: float  # 0-1
    contributions: Dict[str, float]  # 模态 -> 贡献度 (0-1)
    modal_scores: Dict[str, ModalScore]
    explanation: str
    modal_details: Dict[str, Any]
    created_at: str


class FusionEngine:
    """
    多模态融合引擎

    默认权重：
    - satellite: 0.40 (卫星影像)
    - forecast: 0.30 (时序预测)
    - social_media: 0.15 (社交媒体)
    - device: 0.15 (设备数据)
    """

    DEFAULT_WEIGHTS = {
        "satellite": 0.40,
        "forecast": 0.30,
        "social_media": 0.15,
        "device": 0.15
    }

    # 风险等级阈值
    LEVEL_THRESHOLDS = [
        (80.0, "CRITICAL"),  # 红色预警
        (60.0, "HIGH"),       # 橙色预警
        (35.0, "MEDIUM"),     # 黄色预警
        (0.0, "LOW")          # 蓝色预警
    ]

    MODAL_NAMES = {
        "satellite": "卫星影像",
        "forecast": "时序预测",
        "social_media": "社交媒体舆情",
        "device": "设备数据"
    }

    def __init__(self, weights: Optional[Dict[str, float]] = None):
        self.weights = weights or self.DEFAULT_WEIGHTS.copy()
        self._normalize_weights()

    def _normalize_weights(self) -> None:
        """归一化权重"""
        total = sum(self.weights.values())
        if total > 0:
            self.weights = {k: v / total for k, v in self.weights.items()}

    def _score_to_level(self, score: float) -> str:
        """评分转等级"""
        for threshold, level in self.LEVEL_THRESHOLDS:
            if score >= threshold:
                return level
        return "LOW"

    def _score_to_color(self, level: str) -> str:
        """等级转颜色"""
        return {
            "CRITICAL": "#e06060",  # 红色
            "HIGH": "#e0a050",     # 橙色
            "MEDIUM": "#d9c040",   # 黄色
            "LOW": "#6dbfa5"      # 蓝色/绿色
        }.get(level, "#7a8d89")

    def fuse(
        self,
        modal_scores: Dict[str, ModalScore],
        event_id: Optional[str] = None
    ) -> FusionResult:
        """
        融合多模态评分

        Args:
            modal_scores: 各模态评分 {模态: ModalScore}
            event_id: 事件 ID

        Returns:
            融合结果
        """
        # 1. 计算加权融合分数
        weighted_sum = 0.0
        weight_sum = 0.0
        contributions = {}

        for modal, score_obj in modal_scores.items():
            weight = self.weights.get(modal, 0.0)
            if weight > 0:
                weighted_sum += weight * score_obj.score
                weight_sum += weight
                contributions[modal] = weight

        # 归一化融合分数
        if weight_sum > 0:
            fused_score = weighted_sum / weight_sum * 100
        else:
            fused_score = 0.0

        # 2. 计算各模态贡献度（相对于加权分数的比例）
        if weighted_sum > 0:
            contributions = {
                k: v * modal_scores[k].score / weighted_sum
                for k, v in contributions.items()
                if k in modal_scores
            }
        else:
            contributions = {k: 0.0 for k in modal_scores}

        # 3. 一致性检查（标准差越小，置信度越高）
        scores = [s.score for s in modal_scores.values()]
        if len(scores) > 1:
            std = np.std(scores)
            confidence = max(0.3, 1.0 - std / 50.0)  # 标准差越小，置信度越高
        else:
            confidence = 0.5

        # 4. 风险等级
        level = self._score_to_level(fused_score)

        # 5. 生成解释
        explanation = self._generate_explanation(
            fused_score, level, modal_scores, contributions
        )

        # 6. 构建详情
        modal_details = {
            modal: {
                "score": score_obj.score,
                "desc": self._get_modal_desc(modal, score_obj),
                "confidence": score_obj.confidence
            }
            for modal, score_obj in modal_scores.items()
        }

        return FusionResult(
            event_id=event_id or "",
            fused_score=round(fused_score, 1),
            level=level,
            confidence=round(confidence, 3),
            contributions={k: round(v, 3) for k, v in contributions.items()},
            modal_scores=modal_scores,
            explanation=explanation,
            modal_details=modal_details,
            created_at=datetime.now().isoformat()
        )

    def _get_modal_desc(self, modal: str, score_obj: ModalScore) -> str:
        """获取模态描述"""
        details = score_obj.details or {}

        if modal == "satellite":
            anomaly_count = details.get("anomaly_count", 0)
            source = details.get("source", "unknown")
            if score_obj.score >= 60:
                return f"检测到 {anomaly_count} 处异常，{source} 显示显著形变"
            elif score_obj.score >= 30:
                return f"发现 {anomaly_count} 处轻微异常"
            return "未检测到明显异常"

        elif modal == "forecast":
            trend = details.get("trend", "stable")
            acceleration = details.get("acceleration", 0)
            if trend == "increasing":
                return f"预测位移加速趋势（{acceleration:.2f} mm/d²）"
            elif trend == "decreasing":
                return "预测位移趋于稳定"
            return "预测趋势平稳"

        elif modal == "social_media":
            post_count = details.get("post_count", 0)
            sentiment = details.get("sentiment", 0)
            if post_count > 50:
                return f"舆情扩散（{post_count} 条相关帖文），情感偏{self._sentiment_label(sentiment)}"
            elif post_count > 10:
                return f"存在少量舆情（{post_count} 条），情感{sentiment}"
            return "无明显舆情"

        elif modal == "device":
            displacement_rate = details.get("displacement_rate", 0)
            crack_width = details.get("crack_width", 0)
            if displacement_rate > 5:
                return f"位移速率偏快（{displacement_rate:.2f} mm/d）"
            elif crack_width > 10:
                return f"裂缝扩展（{crack_width:.1f} mm）"
            return "设备数据正常"

        return "无详细信息"

    def _sentiment_label(self, sentiment: float) -> str:
        """情感标签"""
        if sentiment < -0.3:
            return "负面"
        elif sentiment > 0.3:
            return "正面"
        return "中性"

    def _generate_explanation(
        self,
        fused_score: float,
        level: str,
        modal_scores: Dict[str, ModalScore],
        contributions: Dict[str, float]
    ) -> str:
        """生成自然语言解释"""
        parts = []

        # 整体评分
        parts.append(f"综合风险评分为 {fused_score:.1f} 分，")

        # 主导模态
        sorted_modals = sorted(
            contributions.items(),
            key=lambda x: x[1],
            reverse=True
        )

        if sorted_modals:
            top_modal, top_contrib = sorted_modals[0]
            modal_name = self.MODAL_NAMES.get(top_modal, top_modal)

            if top_contrib > 0.3:
                parts.append(f"{modal_name}贡献最大（{top_contrib*100:.0f}%）")

                # 主导模态的具体信息
                if top_modal in modal_scores:
                    desc = self._get_modal_desc(top_modal, modal_scores[top_modal])
                    parts.append(f"，{desc}。")
                else:
                    parts.append("。")
            else:
                parts.append("各模态贡献相对均衡。")
        else:
            parts.append("数据不足，无法生成详细解释。")

        # 建议
        if level == "CRITICAL":
            parts.append("建议立即发布红色预警，组织紧急撤离。")
        elif level == "HIGH":
            parts.append("建议发布橙色预警，加强监测和值守。")
        elif level == "MEDIUM":
            parts.append("建议发布黄色预警，保持关注。")
        else:
            parts.append("建议持续观察，保持常规监测。")

        return "".join(parts)


# 全局融合引擎
_fusion_engine = FusionEngine()


def get_fusion_engine(weights: Optional[Dict[str, float]] = None) -> FusionEngine:
    """获取融合引擎实例"""
    global _fusion_engine
    if weights:
        return FusionEngine(weights)
    return _fusion_engine
