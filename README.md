# 御灾智巡 · 基于多源数据融合与AI智能研判的自然灾害监测预警与应急指挥平台

> DisasterGuard · v1.7.0
>
> 🆕 **新加入项目？** 5 分钟入门 → [docs/ONBOARDING/README.md](docs/ONBOARDING/README.md)

基于 **Spring Boot 3 + Vue 3 + FastAPI** 的一体化自然灾害监测、巡检、预警与应急响应平台。

---

## ✨ 特性

- 🛰️ **多源数据融合** — Sentinel-1/2、Landsat、ERA5、GPM、SMAP、IoT 传感器、社交媒体
- 🤖 **AI 智能研判** — YOLOv8 检测、LSTM 时序预测、XGBoost 融合评分、Isolation Forest 异常检测、模型漂移监控
- 🌐 **统一事件中心** — 跨 hazard / warning / emergency 的 7 状态 × 12 转移，`EventCenterPage` 可视化 + `EventDetailDrawer` 详情回放
- 📱 **多端适配** — Web 仪表盘 / 指挥大屏（`/screen`）/ 移动指挥终端（`/mobile`，PWA）
- 🔐 **细粒度权限** — 7 角色 × 34 模块 × 4 操作（view/create/edit/delete），`UserManagementPage` 内置权限矩阵
- ⚡ **实时推送** — WebSocket（一次性 ticket 鉴权）+ EMQX MQTT 设备接入
- 📑 **57 条业务路由 / 54 个页面** — 覆盖监测、AI、预警、应急、数据、系统全业务域

---

## 🚀 快速启动

### 方法 1：一键脚本（推荐）

#### Windows PowerShell

```powershell
# 方式 A：一键全栈（后台并行启动，推荐）
.\start-dev.ps1 -All              # 立即返回，所有服务在后台运行
.\start-dev.ps1 -All -Wait        # 等全部就绪后报告（约 30–60 秒）

# 方式 B：分终端启动（各占一个终端窗口）
.\start-dev.ps1 -Infra -Up       # 首次需启动 Docker 基础设施
.\start-dev.ps1 -Backend          # 终端 1
.\start-dev.ps1 -Ai               # 终端 2
.\start-dev.ps1 -Frontend         # 终端 3

# 日常运维
.\start-dev.ps1 -Status           # 健康度速览（端口 / Docker / Git）
.\start-dev.ps1 -Doctor          # 诊断依赖问题
.\start-dev.ps1 -Stop            # 停止所有本地服务
.\start-dev.ps1 -Restart         # 重启所有服务
```

#### Linux / macOS / WSL

```bash
chmod +x start-dev.sh

# 方式 A：一键全栈（后台并行启动）
./start-dev.sh all               # 立即返回
./start-dev.sh all --wait       # 等全部就绪后报告

# 方式 B：分终端启动
./start-dev.sh infra up         # 首次需启动 Docker 基础设施
./start-dev.sh backend          # 终端 1
./start-dev.sh ai               # 终端 2
./start-dev.sh frontend         # 终端 3

# 日常运维
./start-dev.sh status           # 健康度速览
./start-dev.sh doctor           # 诊断依赖问题
./start-dev.sh stop             # 停止所有服务
./start-dev.sh restart          # 重启所有服务
```

> **注意：** `run-frontend.ps1` 和 `run-backend.ps1` 已删除，功能已合并到 `start-dev.ps1` / `start-dev.sh`（统一入口）。

### 方法 2：Docker Compose（一体化）

```bash
# 全部服务（开发模式）
docker compose up -d

# 浏览器访问
#   前端:    http://localhost:5173
#   后端:    http://localhost:8080/swagger-ui.html
#   AI 服务: http://localhost:8001/docs
```

### 启动后访问

| 入口 | URL | 说明 |
|---|---|---|
| 主仪表盘 | http://localhost:5173 | 默认登录入口 |
| 移动指挥终端 | http://localhost:5173/mobile | 手机端极简指挥（PWA） |
| 指挥大屏 | http://localhost:5173/screen | 全屏展示（无需登录） |
| 后端 API | http://localhost:8080/swagger-ui.html | Swagger UI |
| AI 服务 | http://localhost:8001/docs | FastAPI 自动文档 |
| MinIO 控制台 | http://localhost:19001 | `disasterguard` / `<POSTGRES_PASSWORD from .env>` |

---

## 🌐 端口约定

| 服务 | 宿主机端口 | 容器内端口 | 说明 |
|------|-----------|-----------|------|
| 前端开发服务器 | `5173` | — | Vite dev server |
| 前端（Docker） | `5173` | `80` | Nginx 反代 API/WS |
| 后端 | `8080` | `8080` | Spring Boot |
| 后端（Prod） | `8081` | `8080` | 生产模式映射 |
| PostgreSQL | `15432` | `5432` | PostGIS 15 |
| Redis | `16379` | `6379` | Redis 7 |
| MinIO API | `19000` | `9000` | 对象存储 |
| MinIO Console | `19001` | `9001` | Web 管理台 |
| EMQX MQTT | `11883` | `1883` | MQTT Broker 5.9 |
| EMQX Dashboard | `28083` | `18083` | Broker 管理台 |
| TDengine TCP | `16030` | `6030` | 时序数据库 |
| TDengine HTTP | `16041` | `6041` | JDBC REST 接口 |
| AI 推理服务 | `8001` | `8001` | FastAPI（可选） |

---

## 🔑 默认账号（开发模式）

| 用户名 | 密码 | 角色 | 可见菜单 |
|---|---|---|---|
| admin | 123456 | 管理员 | 全部 |
| system_admin | 123456 | 系统管理员 | 系统/数据 |
| emergency_commander | 123456 | 应急指挥官 | 预警/应急/资源 |
| attendant | 123456 | 值班员 | 态势/AI/确认 |
| expert | 123456 | 专家 | 态势/AI 研判 |
| ai_engineer | 123456 | AI 工程师 | AI 模型/数据源 |
| maintenance | 123456 | 运维 | 设备/系统监控 |

> 生产环境必须修改种子密码或跳过 `data.sql`。

---

## 📚 文档导航

| 文档 | 路径 | 内容 |
|---|---|---|
| **文档索引** | [docs/README.md](docs/README.md) | 按"了解 → 开发 → 运维 → 接口"的阅读路径组织 |
| **架构总览** | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 系统分层、数据流、模块依赖、关键时序图 |
| **开发者文档** | [docs/DEVELOPER.md](docs/DEVELOPER.md) | 开发环境、技术栈、API、数据库、权限、部署、二次开发 |
| **前端接口对照** | [docs/FRONTEND_API.md](docs/FRONTEND_API.md) | 前端功能 ↔ 后端 REST 接口完整映射 |
| **统一事件状态机** | [docs/UNIFIED_EVENT_STATEMACHINE.md](docs/UNIFIED_EVENT_STATEMACHINE.md) | 统一事件中心 7 状态 × 12 转移 |
| **Copernicus 接入** | [docs/CDSE_SETUP.md](docs/CDSE_SETUP.md) | CDSE OAuth + Sentinel-1/2 STAC fallback 链路 |
| **变更日志** | [CHANGELOG.md](CHANGELOG.md) | 版本演进记录 |
| **贡献指南** | [CONTRIBUTING.md](CONTRIBUTING.md) | 提交规范、代码风格、PR 流程 |

---

## 🧱 技术栈

| 层级 | 技术 |
|---|---|
| 前端 | Vue 3.5 + Vite 6 + Vue Router 4 + Pinia + OpenLayers 10 + Cesium 1.144 + Lucide Icons |
| 后端 | Spring Boot 3.4 + Java 17 + Spring Data JPA + Hibernate Spatial (PostGIS) + Spring Security (JWT) |
| AI 服务 | Python 3.11 + FastAPI + YOLOv8 + LSTM + Isolation Forest + XGBoost |
| 基础设施 | PostgreSQL 15 + PostGIS + Redis 7 + MinIO + EMQX 5 + TDengine 3 |

---

## 📂 项目结构

```
earthquake/
├── src/                          # 前端 Vue 3 源码
│   ├── views/                    # 54 个业务页面，按域分子目录
│   │   ├── ai/                   # AI 研判 / 结果 / 模型 / 灾损 / 预测 / 舆情
│   │   ├── data/                 # 统计 / 目录 / 数据源 / 同步
│   │   ├── device/               # 设备管理 / OTA
│   │   ├── hazard/               # 灾害事件 / 确认 / 巡检 / 会签
│   │   ├── monitor/              # 实时地图 / 时相对比 / 气象 / 区域专项 / 设备地图
│   │   ├── screen/               # 指挥大屏（CommandScreenPage）
│   │   ├── settings/             # 个人设置 / 账号安全
│   │   ├── system/               # 用户 / 审计 / 消息 / 审批 / 事件中心 / 帮助 / 渠道
│   │   ├── warning/              # 预警 / 应急 / 资源调度 / 路径规划
│   │   ├── mobile/               # 移动指挥终端（PWA）
│   │   ├── OverviewPage.vue      # 综合态势大屏
│   │   ├── WelcomePage.vue       # 登录
│   │   ├── ForbiddenPage.vue     # 403
│   │   └── ServerErrorPage.vue   # 500
│   ├── components/               # 通用组件（含 event/ / profile/ / rule/ 等子目录）
│   ├── composables/              # 复用逻辑（useOverviewSearch 等）
│   ├── data/                     # 静态配置 + 菜单元数据（dashboardData.js）
│   ├── layouts/                  # DashboardLayout.vue
│   ├── router/                   # 57 条业务路由
│   ├── services/                 # 业务服务层
│   │   ├── _architecture/        # 新版分层结构
│   │   │   ├── core/             # httpClient / realtime / map / geocode / health / sentinel
│   │   │   └── domain/           # 各业务域服务（hazard/warning/ai/...）
│   │   ├── dashboardApi.js       # 统一 API 入口（按域聚合）
│   │   └── mock/                 # 离线 mock 数据（按域可独立切换）
│   ├── stores/                   # Pinia store（auth / realtime / userPreference）
│   └── utils/                    # 工具函数（坐标转换 / 格式化）
├── backend/                      # Spring Boot 后端
│   └── src/main/java/cn/disasterguard/
│       ├── auth/                 # 认证 + 权限
│       ├── hazard/               # 灾害事件
│       ├── warning/              # 预警
│       ├── emergency/            # 应急
│       ├── device/               # 设备
│       ├── ai/                   # AI 推理调用
│       ├── notification/         # 通知渠道
│       ├── ruleengine/           # 规则引擎
│       ├── device/               # 设备管理
│       ├── common/               # 通用基类 + 异常处理
│       └── ...
├── ai-service/                   # FastAPI AI 推理服务
│   └── app/
│       ├── main.py               # FastAPI 入口
│       ├── routers/              # 路由层
│       ├── models/               # 模型推理
│       ├── schemas/              # Pydantic schema
│       └── training/             # 训练流水线
├── scripts/                      # 维护脚本（按职责归档到 4 个子目录，详见 scripts/README.md）
│   ├── db/                       # 数据库：schema 审计 / 数据回填 / 种子生成
│   │   ├── audit_db.py
│   │   ├── backfill_unified_event_hazards.py
│   │   ├── gen_data_sql.py
│   │   ├── smoke_test_schema.py
│   │   └── check_jsonb_sql.py
│   ├── audit/                    # 代码 / 数据一致性审计
│   │   ├── orphan_endpoint_scan.py
│   │   ├── empty_feats.py
│   │   └── diagnose-schema-drift.ps1
│   ├── fix/                      # 一次性修复（保留以备复用）
│   │   ├── oneclick-fix.mjs      # 由 `npm run fix` 调用
│   │   └── fix-db-volume.ps1
│   └── env/                      # 端口 / 进程 / 环境变量探针
│       ├── check-ports.ps1
│       ├── check-procs.ps1
│       └── wait-docker.ps1
├── docs/                         # 开发者文档
│   ├── README.md                 # 文档索引（阅读路径建议）
│   ├── ARCHITECTURE.md           # 架构总览
│   ├── DEVELOPER.md              # 开发者文档（核心参考）
│   ├── FRONTEND_API.md           # 前端功能 ↔ 接口对照
│   ├── UNIFIED_EVENT_STATEMACHINE.md  # 统一事件状态机
│   └── CDSE_SETUP.md             # Copernicus Data Space 接入指南
├── infra/                        # 基础设施初始化
│   ├── emqx/                     # EMQX 认证
│   ├── nginx/                    # 生产 Nginx
│   ├── postgres/                 # PostGIS 扩展
│   └── tdengine/                 # TDengine 初始化
├── tests/                        # 测试
├── docker-compose.yml            # 开发模式
├── docker-compose.prod.yml       # 生产模式
├── backend/Dockerfile            # 后端多阶段构建
├── frontend/Dockerfile           # 前端多阶段构建（node 构建 + nginx 运行）
├── start-dev.ps1                 # Windows 一键启动（统一入口）
├── start-dev.sh                  # Linux/Mac/WSL 一键启动（统一入口）
├── .env.example
├── CHANGELOG.md
├── CONTRIBUTING.md
└── README.md
```

---

## 🧪 多源数据融合体系（详细见 [docs/DEVELOPER.md](docs/DEVELOPER.md) §24）

| 类别 | 数据源 | 用途 |
|---|---|---|
| 遥感影像 | Sentinel-1/2、Landsat、MODIS、VIIRS、Maxar | 地表变化检测 |
| 气象水文 | ERA5、GPM IMERG、SMAP、河流水位 | 触发条件 + 风险预测 |
| 地形地质 | SRTM DEM、Copernicus DEM、地质图、土壤类型 | 易发性评估 |
| 历史灾害 | EM-DAT、GDACS、WorldPop | 模型训练 + 风险区划 |
| 地面观测 | USGS 地震、IoT 雨量计/位移计 | 实时前兆 |
| 辅助 | NASA Black Marble、社交媒体、行政区划 | 断电评估 + 现场线索 |

---

## 📜 许可证

学生创业项目 · Copyright © 2025-2026 DisasterGuard Team
