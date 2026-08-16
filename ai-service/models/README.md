# AI 模型文件目录

将训练好的模型文件放入此目录，ai-service 启动时自动加载：

| 文件名 | 模型类型 | 训练脚本 | 推理依赖 |
|--------|----------|----------|----------|
| `yolo_crack.onnx` | 图像识别（裂缝/落石） | `scripts/train_crack_yolo.py` | `onnxruntime`, `opencv-python`（可选） |
| `lstm_displacement.pt` | 时序预测（位移趋势） | `scripts/train_lstm_displacement.py` | `torch` |
| `isolation_forest.joblib` | 异常检测 | `scripts/train_isolation_forest.py` | `scikit-learn`, `joblib` |
| `xgboost_fusion.json` | 融合预警（风险评分） | `scripts/train_xgboost_fusion.py` | `xgboost` |

模型缺失时 ai-service 自动降级为启发式/mock 实现，不阻塞后端主流程。

## 快速启动（合成数据演示训练）

```bash
cd ai-service

# 1. 时序预测
pip install torch numpy
python scripts/train_lstm_displacement.py --data ./any.csv   # 无 CSV 时用合成数据
# 输出: models/lstm_displacement.pt

# 2. 异常检测
pip install scikit-learn joblib
python scripts/train_isolation_forest.py --data ./any.csv
# 输出: models/isolation_forest.joblib

# 3. 融合预警
pip install xgboost
python scripts/train_xgboost_fusion.py --data ./any.csv
# 输出: models/xgboost_fusion.json

# 4. 图像识别（需要 GPU + ultralytics + 裂缝数据集）
pip install ultralytics
python scripts/train_crack_yolo.py --data ./datasets/crack --export-onnx
# 输出: runs/crack/yolov8n/weights/best.pt + models/yolo_crack.onnx
```

## 验证

启动 ai-service 后访问各 status 端点确认模型加载：

```bash
curl http://localhost:8081/api/ai/detect/status
curl http://localhost:8081/api/ai/forecast/status
curl http://localhost:8081/api/ai/anomaly/status
curl http://localhost:8081/api/ai/fusion/status
```

`model_loaded: true` 表示真实推理就绪，`false` 表示降级为启发式。
