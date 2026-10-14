# AI Service 模块（独立运行）

> DisasterGuard 地质灾害智能巡检 AI 推理服务 v3.0
> 自包含部署 — 模型、配置、脚本全部位于 `ai-service/` 目录内

## 目录结构

```
ai-service/
├── app/                    # 应用代码（FastAPI + 5 个 AI 模型）
│   ├── config.py          # 配置：默认从 ai-service/models/ 加载
│   ├── main.py             # FastAPI 入口
│   ├── models/             # 5 个 AI 模型实现
│   ├── routers/            # 12 个 API 路由模块
│   ├── utils/              # 工具（HTTP 客户端等）
│   ├── training/           # 旧版训练代码
│   └── schemas/            # Pydantic schemas
├── models/                 # 模型文件（自包含）
│   ├── yolo_crack.onnx
│   ├── xgboost_fusion.json + meta
│   ├── comprehensive_v2.json + meta
│   ├── lstm_displacement.pt + norm + meta
│   └── isolation_forest.joblib + meta
├── scripts/                # 训练 / 测试 / 工具脚本
│   ├── _bootstrap.py       # 路径自包含助手（脚本入口）
│   ├── verify_models.py
│   ├── test_disaster_scenarios.py
│   ├── test_api_endpoints.py
│   ├── quick_api_check.py
│   ├── run_service.py      # 服务独立启动器
│   ├── download_models.py
│   ├── train_*.py
│   └── test_*.py
├── outputs/                # 运行时输出（Sentinel 合成图等）
├── requirements.txt        # Python 依赖
└── README.md               # 本文件
```

## 快速开始

### 1. 安装依赖

```bash
cd ai-service
pip install -r requirements.txt
```

### 2. 验证环境

```bash
# 快速验证 5 个模型加载 + 推理
python scripts/verify_models.py

# 启动前环境检查
python scripts/run_service.py --check
```

### 3. 启动服务

```bash
# 默认端口 8001，启用热重载（开发）
python scripts/run_service.py

# 自定义端口
python scripts/run_service.py --port 9001

# 生产模式（禁用热重载）
python scripts/run_service.py --no-reload --host 0.0.0.0 --port 8001
```

### 4. 测试 API

```bash
# 端到端验证（需先启动服务）
python scripts/quick_api_check.py

# 12 个灾害场景验证（不需服务）
python scripts/test_disaster_scenarios.py
```

## 5 个 AI 模型

| 模型 | 文件 | 大小 | 任务 | 验证指标 |
|------|------|------|------|----------|
| YOLO 目标检测 | `yolo_crack.onnx` | 13 MB | 裂缝/落石识别 | COCO 80 类 |
| XGBoost Fusion v3 | `xgboost_fusion.json` | 3.6 MB | 设备/传感器融合 | MAE=0.74 / RMSE=0.96 |
| Comprehensive v2 | `comprehensive_v2.json` | 3.6 MB | 七维度综合风险 | MAE=0.80 / 36 特征 |
| LSTM 位移预测 | `lstm_displacement.pt` | 73 KB | 时序预测 | PyTorch LSTM |
| Isolation Forest | `isolation_forest.joblib` | 2.0 MB | 异常检测 | scikit-learn |

## 配置说明

### 模型路径

默认从 `ai-service/models/` 加载（自包含），无需配置。

可覆盖：
```bash
# 环境变量
export MODELS_DIR=/path/to/models

# 命令行参数
python scripts/run_service.py --models-dir /path/to/models
```

### 路径解析策略（`app/config.py`）

```
1. 环境变量 MODELS_DIR     (最高优先级)
2. ai-service/models/      (自包含部署，默认)
3. <repo>/models/          (兼容旧版，缺失时回退)
```

### 其他环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `AI_SERVICE_ROOT` | 自动推导 | ai-service 根目录 |
| `MODELS_DIR` | `ai-service/models/` | 模型目录 |
| `SENTINEL_STORAGE_DIR` | `ai-service/outputs/sentinel/` | 合成图存储 |
| `AI_CORS_ORIGINS` | `http://localhost:5173` | CORS 允许来源 |
| `AI_LOG_LEVEL` | `INFO` | 日志级别 |

## 运行模式

### 开发模式（热重载）
```bash
python scripts/run_service.py
# uvicorn 自动监听文件变化
```

### 生产模式（多 worker）
```bash
python scripts/run_service.py --no-reload --workers 4 --host 0.0.0.0 --port 8001
```

### Docker 模式
```bash
# 镜像内启动
CMD ["python", "scripts/run_service.py", "--host", "0.0.0.0", "--port", "8001", "--no-reload"]
```

### 测试模式
```bash
# 模型加载验证（无服务）
python scripts/verify_models.py

# 灾害识别端到端（无服务）
python scripts/test_disaster_scenarios.py

# API 验证（需先启动服务）
python scripts/quick_api_check.py
```

## 从任意目录运行

所有 `scripts/*.py` 都支持从任意目录运行（通过 `_bootstrap.py` 自动解析路径）：

```bash
# 从仓库根目录
python ai-service/scripts/verify_models.py
python ai-service/scripts/run_service.py --port 8001

# 从 ai-service 目录
cd ai-service
python scripts/verify_models.py
python scripts/run_service.py

# 从 scripts 目录
cd ai-service/scripts
python verify_models.py

# 通过环境变量指定
AI_SERVICE_ROOT=/custom/path python /anywhere/verify_models.py
```

## API 端点（共 46 个）

主要端点：

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/ai/health` | 服务健康检查 |
| GET | `/api/ai/model/info` | 模型清单 |
| GET | `/api/ai/model/health` | 模型加载状态 |
| POST | `/api/ai/comprehensive` | 七维度综合风险分析 |
| POST | `/api/ai/fusion-risk` | 设备/传感器融合预警 |
| POST | `/api/ai/anomaly` | 异常检测 |
| POST | `/api/ai/forecast` | 时序预测 |
| POST | `/api/ai/disaster-assessment/score` | 灾害类型分类 |

完整列表：`http://localhost:8001/docs`（Swagger UI）

## 灾害识别能力

12 个真实灾害场景测试 100% 准确率：

| 场景 | 评分 | 等级 |
|------|------|------|
| 晴天正常 | 13.9 | LOW |
| 小雨 | 14.6 | LOW |
| 大雨持续 | 21.1 | LOW |
| 暴雨+陡坡+位移（滑坡前兆） | 61.3 | HIGH |
| 极端暴雨+泥石流前兆 | 85.7 | CRITICAL |
| 地震+余震滑坡 | 47.0 | MEDIUM |
| 历史高频点暴雨 | 28.3 | LOW |
| 矿山下沉 | 35.2 | MEDIUM |
| 城市内涝 | 42.6 | MEDIUM |
| 多灾种叠加 | 97.7 | CRITICAL |
| 河边大雨 | 22.8 | LOW |
| 历史无灾点 | 13.4 | LOW |

## 重新训练模型

```bash
# Comprehensive v2（8800 样本，MAE=0.80）
python scripts/train_comprehensive_xgboost_v2.py

# XGBoost Fusion v3（8000 样本，MAE=0.74）
python scripts/train_xgboost_fusion_v3.py

# 其他模型
python scripts/download_models.py --all  # 下载/训练所有模型
```

训练完成后模型自动保存到 `ai-service/models/`。

## 故障排查

### 模型加载失败
```bash
python scripts/verify_models.py
# 查看每个模型的 loaded 状态和路径
```

### 端口占用
```bash
python scripts/run_service.py --port 9001
```

### CORS 错误
```bash
AI_CORS_ORIGINS="http://localhost:5173,http://localhost:3000" python scripts/run_service.py
```

### 找不到 app 模块
确保从 `ai-service/` 目录运行，或设置 `AI_SERVICE_ROOT` 环境变量。

## 更多信息

- 改进总结：`docs/guides/AI_MODULE_IMPROVEMENT_20260929.md`
- 代码评审：`docs/reviews/CODE_REVIEW_R5.md`