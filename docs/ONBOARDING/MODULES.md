# 模块职责总览

> 本文是**导航层**，不重复已有文档。深度内容请点击对应链接。
> 环境搭建见 `docs/DEVELOPER.md` 第 4 章。

---

## 一、三大核心模块

### 1. ai-service（Python / FastAPI）

**职责**：模型推理 + 训练，供 backend 通过 HTTP 调用。

**入口**：

- `ai.py` —— CLI（run / check / test / scenarios / api 子命令）
- `web.py` —— uvicorn 启动
- `app/main.py` —— FastAPI 装配

**子包**：

| 目录 | 职责 |
|------|------|
| `app/models/` | 模型类（XGBoost / LSTM / YOLO / Isolation Forest / 融合） |
| `app/routers/` | 14 个 API 路由（见下表） |
| `app/schemas/` | Pydantic 请求/响应模型 |
| `app/training/` | 应用内训练（被 `/api/ai/model/retrain` 调用） |
| `app/utils/` | 通用工具 |
| `scripts/` | 独立工具脚本（含 `training/` 8 个训练脚本） |
| `models/` | 模型权重 + meta（不入 git） |
| `tests/` | pytest 单元测试 |

**14 个路由**：

| 路由 | 用途 |
|------|------|
| `anomaly` | 异常检测（Isolation Forest） |
| `comprehensive` | 综合风险分析（XGBoost 回归） |
| `dashboard` | 浏览器可视化入口 + 概览 API |
| `detection` | 图像识别（YOLO） |
| `device_health` | 设备健康度 |
| `disaster_assessment` | 灾害评估 |
| `drift` | 数据漂移监控 |
| `feedback` | 用户反馈收集（用于重训练） |
| `forecast` | 时序预测（LSTM） |
| `fusion` | 融合预警（XGBoost fusion） |
| `model_info` | 模型元数据 + meta 路径 |
| `multimodal` | 多模态融合 |
| `sentinel` | Sentinel 影像处理 |
| `social_media` | 社交媒体风险分析 |

**延伸阅读**：

- `ai-service/README.md`
- `ai-service/scripts/README.md`
- `ai-service/scripts/training/README.md`
- `docs/guides/AI_MODULE_IMPROVEMENT_20260929.md`

---

### 2. backend（Java / Spring Boot）

**职责**：业务编排 + 数据库 + 认证授权 + 对外 REST API。

**顶层包按职责分组**：

**基础设施**：

| 包 | 职责 |
|----|------|
| `aop/` | 切面（操作日志等） |
| `common/` | 公共响应、异常、安全过滤器、Excel 导出 |
| `config/` | 配置类（Spring / DB / Properties 统一注册） |

**认证与权限**：

| 包 | 职责 |
|----|------|
| `auth/` | JWT 认证、用户资料、账户安全（DTO 在 `auth/dto/`） |
| `system/` | 角色、权限、字典、菜单 |
| `personnel/` | 人员管理 |

**业务核心**：

| 包 | 职责 |
|----|------|
| `hazard/` | 隐患点管理 |
| `inspection/` | 巡检任务与记录 |
| `device/` | 监测设备 + 指标 + OTA |
| `warning/` | 预警事件 + 规则引擎 |
| `emergency/` | 应急事件 + 资源调度 |
| `event/` | 统一事件状态机 |
| `task/` `todo/` | 任务 + 待办中心 |
| `approval/` | 审批流 |
| `confirmation/` | 确认签字 |
| `ruleengine/` | 规则引擎 |
| `notification/` | 消息通知（DTO 在 `notification/dto/`） |
| `report/` | 报表 |
| `dashboard/` | 仪表盘（DTO 在 `dashboard/dto/`） |
| `analytics/` | 分析统计 |
| `region/` | 行政区划 |
| `weather/` | 气象数据 |
| `governance/` | 数据源治理 |
| `audit/` | 审计日志 |
| `attachment/` | 文件附件 |
| `backup/` | 备份恢复 |
| `realtime/` | WebSocket 实时推送 |
| `health/` | 系统健康监控 |
| `ai/` | 调用 ai-service 的客户端 + Controller + Scheduler |

**延伸阅读**：

- `docs/ARCHITECTURE.md`
- `docs/DEVELOPER.md` 第 3 章
- `docs/reviews/CODE_REVIEW*.md`

---

### 3. frontend（Vue 3 + Vite）

**职责**：Web UI。按业务域组织 View，Service 层统一走后端 API。

**顶层目录**：

| 目录 | 职责 |
|------|------|
| `main.js` / `App.vue` | 应用入口 + 根组件 |
| `styles.css` | 全局样式 |
| `components/` | 可复用组件（`base/` 为 iOS 风格基础组件） |
| `composables/` | Composition API 复用逻辑 |
| `data/` | 静态数据 |
| `layouts/` | 布局组件 |
| `router/` | 路由配置 |
| `services/` | API 服务层（薄层 + `_architecture/` 内部实现） |
| `stores/` | Pinia 状态管理 |
| `utils/` | 工具函数 |
| `views/` | 页面（按业务域分子目录） |

**延伸阅读**：

- `docs/FRONTEND_API.md`
- `frontend/src/services/README.md`
- `docs/audit/frontend/`

---

## 二、辅助模块

| 模块 | 职责 | 文档 |
|------|------|------|
| `hardware-sandbox/` | 硬件沙箱（Revit / IFC / 3D 模型） | `docs/HARDWARE_SANDBOX.md` |
| `infra/` | Docker 初始化（postgres / tdengine / emqx） | 目录内 README |
| `scripts/` | 顶层工具（db / audit / fix / env / migration） | `scripts/README.md` |

---

## 三、跨模块数据流

详见 `docs/ONBOARDING/DATA_FLOW.md`。

---

**最后更新**：2026-10-15
