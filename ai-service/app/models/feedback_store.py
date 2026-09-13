"""
反馈存储：记录"AI 判定 vs 实际事件"对照，供后续模型训练 / 阈值校准使用。

存储模型：
  feedback = {
    "id": int,                 # 自增
    "hazard_code": str,        # 灾害点编码
    "predicted_level": str,    # AI 当时判定的等级 (LOW/MEDIUM/HIGH/CRITICAL)
    "predicted_score": float,  # AI 当时打的风险分
    "predicted_at": str,       # AI 判定时间 ISO 8601
    "outcome": str,            # true_positive / true_negative / false_positive / false_negative
    "occurred": bool,          # 实际是否发生了灾害
    "confidence": float,       # 反馈者自评可信度 0-1
    "feedback_at": str,        # 反馈时间
    "feedback_by": str,        # 反馈者 (user_id)
    "note": str,               # 备注
  }

outcome 解释（机器学习混淆矩阵标准命名）：
  true_positive   AI 预测会出事 + 实际也出事
  true_negative   AI 预测不会 + 实际也没事
  false_positive  AI 预测会出事 + 实际没事（误报）
  false_negative  AI 预测不会 + 实际出事（漏报）

数据保留策略：内存 deque(maxlen=10000)；持久化后续可换 PostgreSQL/Redis。
"""
from __future__ import annotations

import threading
import time
from collections import deque
from typing import Any, Dict, List, Optional


VALID_OUTCOMES = {"true_positive", "true_negative", "false_positive", "false_negative"}


class FeedbackStore:
    """反馈存储（线程安全，内存版）。"""

    def __init__(self, max_size: int = 10000) -> None:
        self._lock = threading.Lock()
        self._items: deque = deque(maxlen=max_size)
        self._next_id = 1

    def add(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        """
        添加一条反馈记录，自动补 id/feedback_at。

        Args:
            payload: 必须包含 hazard_code/predicted_level/predicted_score/
                     predicted_at/occurred/outcome/confidence
        Returns:
            补齐字段后的完整记录
        """
        outcome = str(payload.get("outcome", "")).strip().lower()
        if outcome not in VALID_OUTCOMES:
            raise ValueError(
                f"outcome 必须是 {VALID_OUTCOMES} 之一，收到 {outcome!r}"
            )
        if "hazard_code" not in payload:
            raise ValueError("payload 缺少 hazard_code")
        if "predicted_level" not in payload:
            raise ValueError("payload 缺少 predicted_level")
        if "occurred" not in payload:
            raise ValueError("payload 缺少 occurred (bool)")
        if "predicted_score" not in payload:
            payload["predicted_score"] = 0.0
        if "predicted_at" not in payload:
            payload["predicted_at"] = ""
        if "confidence" not in payload:
            payload["confidence"] = 1.0
        if "feedback_by" not in payload:
            payload["feedback_by"] = "anonymous"
        if "note" not in payload:
            payload["note"] = ""

        with self._lock:
            record = dict(payload)
            record["id"] = self._next_id
            record["feedback_at"] = int(time.time() * 1000)  # epoch ms
            self._next_id += 1
            self._items.append(record)
        return record

    def list_recent(self, limit: int = 50) -> List[Dict[str, Any]]:
        """返回最近 N 条反馈（默认 50）。"""
        with self._lock:
            items = list(self._items)
        return items[-limit:][::-1]  # 最新的在前

    def list_by_hazard(self, hazard_code: str) -> List[Dict[str, Any]]:
        """按灾害点过滤全部历史反馈。"""
        with self._lock:
            return [r for r in self._items if r.get("hazard_code") == hazard_code]

    def confusion_matrix(self) -> Dict[str, int]:
        """汇总整个反馈库的混淆矩阵。"""
        with self._lock:
            items = list(self._items)
        out = {k: 0 for k in VALID_OUTCOMES}
        for r in items:
            k = r.get("outcome")
            if k in out:
                out[k] += 1
        return out

    def precision_recall(self) -> Dict[str, float]:
        """
        计算 precision / recall / F1。
        precision = TP / (TP + FP)
        recall    = TP / (TP + FN)
        F1        = 2*P*R / (P+R)
        """
        cm = self.confusion_matrix()
        tp = cm["true_positive"]
        fp = cm["false_positive"]
        fn = cm["false_negative"]
        precision = tp / (tp + fp) if (tp + fp) > 0 else 0.0
        recall = tp / (tp + fn) if (tp + fn) > 0 else 0.0
        f1 = (
            2 * precision * recall / (precision + recall)
            if (precision + recall) > 0
            else 0.0
        )
        return {
            "precision": round(precision, 4),
            "recall": round(recall, 4),
            "f1": round(f1, 4),
            "total_feedback": tp + fp + fn + cm["true_negative"],
            "support": tp + fn,  # 真实正样本数
        }

    def stats(self) -> Dict[str, Any]:
        """返回统计信息（前端/管理面板用）。"""
        cm = self.confusion_matrix()
        pr = self.precision_recall()
        with self._lock:
            count = len(self._items)
            by_level: Dict[str, int] = {}
            for r in self._items:
                lvl = r.get("predicted_level", "UNKNOWN")
                by_level[lvl] = by_level.get(lvl, 0) + 1
        return {
            "total_records": count,
            "by_predicted_level": by_level,
            "confusion_matrix": cm,
            **pr,
        }

    def to_training_rows(self) -> List[Dict[str, Any]]:
        """
        导出训练样本（用于 train_xgboost.py）。
        每条样本包含：hazard_code/label（1=发生/0=未发生）+ score/level。
        真实 label 用 occurred 字段（true=1, false=0）。
        """
        with self._lock:
            items = list(self._items)
        return [
            {
                "hazard_code": r.get("hazard_code"),
                "predicted_score": float(r.get("predicted_score", 0.0)),
                "predicted_level": r.get("predicted_level", "LOW"),
                "outcome": r.get("outcome"),
                "occurred": 1 if r.get("occurred") else 0,
                "confidence": float(r.get("confidence", 1.0)),
                "feedback_at": r.get("feedback_at"),
            }
            for r in items
        ]

    def clear(self) -> None:
        """清空（仅测试用）。"""
        with self._lock:
            self._items.clear()
            self._next_id = 1


# 单例
_store: Optional[FeedbackStore] = None
_store_lock = threading.Lock()


def get_feedback_store() -> FeedbackStore:
    global _store
    if _store is None:
        with _store_lock:
            if _store is None:
                _store = FeedbackStore()
    return _store