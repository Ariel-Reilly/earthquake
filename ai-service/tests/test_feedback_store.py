"""反馈存储单元测试：增删查 + 混淆矩阵 + 训练导出。"""
import pytest

from app.models.feedback_store import get_feedback_store


def _make_record(outcome: str, occurred: bool, level: str = "HIGH", score: float = 60.0):
    return {
        "hazard_code": "T-001",
        "predicted_level": level,
        "predicted_score": score,
        "predicted_at": "2026-09-07T00:00:00Z",
        "outcome": outcome,
        "occurred": occurred,
        "confidence": 0.9,
        "feedback_by": "tester",
        "note": "",
    }


class TestFeedbackCRUD:
    def test_add_returns_id_and_timestamp(self):
        s = get_feedback_store()
        r = s.add(_make_record("true_positive", True))
        assert "id" in r
        assert "feedback_at" in r
        assert r["id"] >= 1

    def test_invalid_outcome_raises(self):
        s = get_feedback_store()
        with pytest.raises(ValueError, match="outcome 必须是"):
            s.add(_make_record("invalid_outcome", True))

    def test_missing_hazard_code_raises(self):
        s = get_feedback_store()
        with pytest.raises(ValueError, match="hazard_code"):
            s.add({"predicted_level": "HIGH", "outcome": "true_positive", "occurred": True})

    def test_list_recent_sorted_newest_first(self):
        s = get_feedback_store()
        for outcome in ("true_positive", "false_positive", "true_negative", "false_negative"):
            s.add(_make_record(outcome, True))
        items = s.list_recent(10)
        assert len(items) == 4
        # 最新的应该最后加的 false_negative
        assert items[0]["outcome"] == "false_negative"
        assert items[-1]["outcome"] == "true_positive"

    def test_list_by_hazard(self):
        s = get_feedback_store()
        s.add({**_make_record("true_positive", True), "hazard_code": "A"})
        s.add({**_make_record("false_positive", False), "hazard_code": "B"})
        s.add({**_make_record("true_negative", False), "hazard_code": "A"})
        items = s.list_by_hazard("A")
        assert len(items) == 2


class TestConfusionMatrix:
    def test_full_matrix(self):
        s = get_feedback_store()
        s.add(_make_record("true_positive", True))
        s.add(_make_record("true_positive", True))
        s.add(_make_record("false_positive", False))
        s.add(_make_record("false_negative", True))
        cm = s.confusion_matrix()
        assert cm["true_positive"] == 2
        assert cm["false_positive"] == 1
        assert cm["false_negative"] == 1
        assert cm["true_negative"] == 0

    def test_precision_recall(self):
        s = get_feedback_store()
        # 2 TP, 1 FP, 1 FN → precision = 2/3, recall = 2/3
        s.add(_make_record("true_positive", True))
        s.add(_make_record("true_positive", True))
        s.add(_make_record("false_positive", False))
        s.add(_make_record("false_negative", True))
        pr = s.precision_recall()
        assert pr["precision"] == pytest.approx(0.6667, abs=0.001)
        assert pr["recall"] == pytest.approx(0.6667, abs=0.001)
        assert pr["f1"] == pytest.approx(0.6667, abs=0.001)

    def test_zero_feedback(self):
        s = get_feedback_store()
        pr = s.precision_recall()
        assert pr["precision"] == 0.0
        assert pr["recall"] == 0.0
        assert pr["f1"] == 0.0

    def test_only_true_negatives(self):
        s = get_feedback_store()
        for _ in range(5):
            s.add(_make_record("true_negative", False))
        pr = s.precision_recall()
        # 无正样本 → precision = 0 (0 / 0), recall = 0 (0 / 0)
        assert pr["precision"] == 0.0
        assert pr["recall"] == 0.0
        assert pr["support"] == 0  # 真实正样本数


class TestTrainingExport:
    def test_to_training_rows_includes_label(self):
        s = get_feedback_store()
        s.add(_make_record("true_positive", True))
        s.add(_make_record("false_positive", False))
        rows = s.to_training_rows()
        assert len(rows) == 2
        assert rows[0]["occurred"] == 1
        assert rows[1]["occurred"] == 0
        assert "predicted_score" in rows[0]
        assert "predicted_level" in rows[0]


class TestClear:
    def test_clear_resets_state(self):
        s = get_feedback_store()
        s.add(_make_record("true_positive", True))
        assert s.stats()["total_records"] == 1
        s.clear()
        assert s.stats()["total_records"] == 0


class TestAPIResponseContract:
    """feedback router 响应字段契约。"""

    def test_stats_has_all_keys(self):
        s = get_feedback_store()
        s.add(_make_record("true_positive", True))
        s.add(_make_record("false_positive", False))
        stats = s.stats()
        for k in (
            "total_records", "by_predicted_level", "confusion_matrix",
            "precision", "recall", "f1", "total_feedback", "support",
        ):
            assert k in stats, f"stats 缺少 {k}"