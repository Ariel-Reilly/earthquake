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

## 输出契约（R11 E1，必须遵守）

同一个 `models/xgboost_fusion.json` 被多份脚本写入，但目标函数不同，**输出口径不能混用**：

| 脚本 | objective | 原生输出 | 推理端处理 |
|------|-----------|----------|-----------|
| `train_fusion_v3.py` | `reg:squarederror` | [0, 100] 风险分 | 直接使用 |
| `train_fusion_v1/v2.py`、`app/training/train_xgboost.py` | `binary:logistic` | [0, 1] 概率 | ×100 映射为分值 |

口径的唯一真相在 `app/models/model_contract.py`：推理端从模型本体读 `objective`，
二分类输出会 ×100 后再分档。此前推理端无条件当 [0, 100] 用，二分类模型一旦重训，
所有评分落到 0~1，全库判 LOW。

因此 v1/v2 已加覆盖保护：目标文件存在时必须显式 `--force` 才会写入，
避免手动跑一次废弃脚本就把线上模型的口径换掉。生产模型请用 v3。

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
