# AI 模型文件目录

> **⚠ 权重使用范围（2026-11-06 项目决策：本项目不商用）**
> `yolo_crack.onnx` 由 `datasets/disaster-v2` 训练，其中滑坡影像来自
> CAS Landslide Dataset（**CC-BY-NC-4.0，禁止商业使用**）。该权重仅限教学 / 竞赛演示 /
> 项目内部测试使用，**不得随商业交付分发**。商用前必须换源重训，详见
> `datasets/disaster-v2/DATASET_CARD.md` §6.5 与 `DEPLOYMENT.md`「6. 当前阶段使用边界」。

将训练好的模型文件放入此目录，ai-service 启动时自动加载：

| 文件 | 模型类型 | 训练脚本 | 推理依赖 |
|------|----------|----------|----------|
| `yolo_crack.onnx` | 图像目标检测（crack / landslide） | `scripts/training/train_crack_yolo.py` | `onnxruntime`、`opencv-python` |
| `lstm_displacement.pt` | 时序预测（位移趋势） | `scripts/training/train_lstm_displacement.py` | `torch` |
| `isolation_forest.joblib` | 异常检测 | `scripts/training/train_isolation_forest.py` | `scikit-learn`、`joblib` |
| `xgboost_fusion.json` | 融合预警（风险评分） | `scripts/training/train_fusion_v3.py` | `xgboost` |

模型缺失时，ai-service 自动降级为启发式/mock 实现，不阻塞后端主流程。

---

## ✓ 关于当前仓库里的 `yolo_crack.onnx`（2026-11-06 重建为 clean 版）

现在的 `yolo_crack.onnx` 是**用 `datasets/disaster-v2`（clean 重建版）真实训练出来的 2 类检测模型**，
输出维度 `[1, 6, 8400]`（4 个 bbox + 2 类），类别 `["crack", "landslide"]`。
onnxruntime 加载后 `is_business_model=True`，对真实裂缝图输出 crack P/R 在 0.6–0.9 量级，
对无人机滑坡图输出 landslide 在 0.6 量级——不再是空结果。

> 相对上一版（inpaint 重建）：只改了旋转裁切的填充策略——由 TELEA inpaint 改为"裁到最大内接矩形、
> 不生成任何像素"，消除了人造涂抹纹理伪影；统计数字因此略有上升（见 `DATASET_CARD.md` §5）。

| 项目 | 值 |
|---|---|
| 骨干/规模 | YOLOv8n（3.13 M 参数，8.6 GFLOPs） |
| 训练数据 | `datasets/disaster-v2`（770 图 / 2632 实例 / 2 类，clean 重建） |
| 训练配置 | imgsz 640、batch 16、30 epoch、CPU（本机 torch 为 CPU 版） |
| 训练耗时 | 约 50 分钟（CPU，i9-13980HX） |
| 权重来源 | `runs/detect/runs/detect/v2-clean-n30/weights/best.pt`（epoch 29 最佳） |

**实测指标（不要外推）**：

| 评估集 | 类别 | P | R | mAP50 | mAP50-95 |
|---|---|---|---|---|---|
| val（106 图 / 272 实例） | all | 0.642 | 0.519 | **0.564** | 0.240 |
| test（71 图 / 206 实例） | all | 0.432 | 0.359 | **0.327** | 0.144 |

**各类别 mAP50-95**（val / test）：crack **0.261 / 0.153**、landslide **0.218 / 0.134**。
滑坡类因为 CAS 标注是"语义区域外接框"，且 test 仅 31 张滑坡图，单图波动就会拉动整类指标，
故 test 比 val 低约 0.2；**对外报数应以 test 为准**，val 只能说明训练收敛情况。

> 同架构、同 30 epoch、同测试集（71 图 / 206 实例）下，用旧 disaster-v1 数据训练的对照组
> `v1-n30` 仅拿到 test mAP50=0.150 / mAP50-95=0.076。v2-clean 的提升（+117% / +88%）
> 可归因于数据升级（512×512 原始分辨率 + 内接矩形去伪影），而非架构或超参变化。
> 详见 `datasets/disaster-v2/DATASET_CARD.md` §8。

现在的加载判定逻辑（`app/models/yolo_model.py`）仍然按输出维度把关：

- 类别数 == `len(CLASSES)`（当前 2 类）→ 业务模型，正常使用
- 类别数 == 80 → COCO 占位，日志告警，`routers/detection.py` 返回 `disaster_type=none`
- 其它类别数 → 判为不兼容并告警，避免类别名错位导致误报灾种

历史占位文件已归档到 `models/history/yolo_crack.coco80.onnx`（COCO 80 类，对裂缝图 0 框），保留仅供追溯。

---

## 训练

数据：`datasets/disaster-v2`（770 图 / 2632 实例 / 2 类，clean 重建，来源与限制见该目录的 `DATASET_CARD.md`）。

```bash
cd ai-service
pip install ultralytics

# 训练 + 导出 ONNX（直接覆盖 models/yolo_crack.onnx）
python scripts/training/train_crack_yolo.py \
    --data ./datasets/disaster-v2 \
    --epochs 30 --imgsz 640 --export-onnx
```

产物：`runs/detect/runs/detect/<name>/weights/best.pt` + `models/yolo_crack.onnx` + 同名 `.meta.json`。

训练中断后可以续训，不必从头再来（权重里存了优化器状态与轮次）：

```bash
python -c "from ultralytics import YOLO; YOLO('runs/detect/runs/detect/v2-clean-n30/weights/last.pt').train(resume=True)"
```

> 本机 `torch` 是 CPU 版（`.venv` 与 `E:\anaconda3` 均为 `+cpu`），有 RTX 4070 也用不上。
> 想提速需装 CUDA 版 torch（约 2.5 GB），否则 30 epoch 就是 50 分钟量级。

**要继续提精度，按性价比排序**：① 再补 CAS 的 Lombok / 九寨沟 / Tiburon 子集（数据量翻倍）→
② 换 YOLOv8s（mAP 通常 +3~5 个点，CPU 训练时间翻倍）→ ③ 加到 100 epoch 并开 mosaic 收尾退火。

其余三个模型的训练（**注意：当前这些模型的训练样本仍是合成数据**，见 `DATASET_CARD.md` 与整改报告）：

```bash
pip install torch numpy
python scripts/training/train_lstm_displacement.py --data ./any.csv

pip install scikit-learn joblib
python scripts/training/train_isolation_forest.py --data ./any.csv

pip install xgboost
python scripts/training/train_fusion_v3.py --data ./any.csv
```

---

## 验证

ai-service 实际监听 **8001** 端口（不是 8081）：

```bash
curl http://localhost:8001/api/ai/detect/status
curl http://localhost:8001/api/ai/forecast/status
curl http://localhost:8001/api/ai/anomaly/status
curl http://localhost:8001/api/ai/fusion/status
```

`model_loaded: true` 表示真实推理就绪，`false` 表示降级为启发式。
对检测模型还要额外看 `num_classes` 与 `is_business_model`：
`num_classes=80` 意味着仍是 COCO 占位，不能当作灾害识别能力。
