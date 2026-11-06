# ai-service/scripts/training/

模型训练脚本，按版本迭代。

## 脚本一览

> ⚠️ **样本来源如实标注（2026-11-06）**：目前只有 `train_crack_yolo.py` 使用真实人工标注数据集
> （`datasets/disaster-v2`，770 图 / 2632 实例）。其余脚本的 `synthesize_sample*()` 用 `random`
> 合成训练样本，**对外不得宣称「基于真实监测数据训练」**。`train_xgboost` 已默认
> `allow_synthetic=False`，只有带 `factors` 的真实反馈样本才会进训练集。

| 脚本 | 版本 | 目标模型 | 状态 | 训练样本来源 |
|------|------|---------|------|---------|
| `train_comprehensive_v1.py` | v1 | comprehensive_v2.json | ⚠️ 已废弃（二分类） | 合成 |
| `train_comprehensive_v2.py` | v2.1.0 | comprehensive_v2.json | ⚠️ 可运行，合成样本 | 合成 |
| `train_fusion_v1.py` | v1 | xgboost_fusion.json | ⚠️ 已废弃（二分类） | 合成 |
| `train_fusion_v2.py` | v2 | xgboost_fusion.json | ⚠️ 已废弃（概率） | 合成 |
| `train_fusion_v3.py` | v3.0.0 | xgboost_fusion.json | ⚠️ 可运行，合成样本 | 合成 |
| `train_crack_yolo.py` | — | yolo_crack.onnx | ✅ 真实标注数据集 | disaster-v2（人工标注） |
| `train_isolation_forest.py` | — | isolation_forest.joblib | ⚠️ 可运行，默认合成 | 合成（可传真实 CSV 覆盖） |
| `train_lstm_displacement.py` | — | lstm_displacement.pt | ⚠️ 可运行，默认合成 | 合成（可传真实 CSV 覆盖） |

## 输出目录

默认输出到 `ai-service/models/`（所有脚本统一）。

- v2 / v3 的 `--output-dir` 默认值是 `Path(__file__).resolve().parent.parent.parent / "models"`，
  相对 `scripts/training/` 上溯 3 层到 `ai-service/`，正确。
- v1 的默认值也已同步修正为 3 层 parent。
- 手动指定：`--output-dir /path/to/models`

## 使用

从 `ai-service/` 根运行：

    python scripts/training/train_comprehensive_v2.py
    python scripts/training/train_fusion_v3.py

从 `scripts/training/` 运行也可，路径计算基于 `__file__`。

## 版本历史

- v1 → v2：二分类改回归，33 → 36 特征，样本量 5000 → 8000
- v2 → v3：概率输出改回归输出，训练样本生成对齐 comprehensive_v2

## 关联

- 模型加载：`app/models/comprehensive_model.py`、`app/models/xgboost_model.py`
- 模型回退路径：`ai-service/config.py` 兼容 `models/`（项目根），详见 `docs/DEVELOPER.md`
