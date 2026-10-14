# ai-service/scripts/

本目录存放**可手动运行的工具脚本**，与 `app/` 业务代码、
`tests/` pytest 单测分离。

## 分类

| 类别 | 文件示例 | 特点 |
|------|---------|------|
| 服务管理 | `run_service.py` | 启动/检查 FastAPI |
| 模型工具 | `download_models.py`、`verify_models.py`、`build_yolo_onnx.py` | 模型下载/校验/转换 |
| 训练脚本 | `train_*.py` | 模型训练（版本化） |
| 手动测试 | `test_*.py` | 打印人类可读输出，非 pytest |

## test_*.py 文件说明

⚠️ **本目录下的 5 个 `test_*.py` 不是单元测试**，它们：

- 手动运行，输出人类可读信息
- 无 assert，无 pytest fixture
- 全部 `from _bootstrap import setup_path`
- **不能移到子目录**（`_bootstrap` 同目录依赖）

真正的 pytest 单测在 `ai-service/tests/` 下，由 CI 自动运行。

| 文件 | 用途 | 调用命令 | 需服务 |
|------|------|---------|--------|
| `test_api_endpoints.py` | HTTP API 端点测试 | `python ai.py api` | ✅ |
| `test_disaster_scenarios.py` | 12 灾害场景端到端 | `python ai.py scenarios` | ❌ |
| `test_models.py` | 4 模型加载/推理 | `python ai.py test` | ❌ |
| `test_comprehensive_v2.py` | Comprehensive v2 手工验证 | `python scripts/test_comprehensive_v2.py` | ❌ |
| `test_yolo.py` | YOLO 合成图像检测 | `python scripts/test_yolo.py` | ❌ |

## _bootstrap.py

所有脚本运行前需要它把 `ai-service/` 加入 `sys.path`，
使脚本能 `import app.models.*`。**不要移动或删除**。
