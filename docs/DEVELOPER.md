# 地脉智巡 · 开发者文档

> 地质灾害点智能巡检与预警管理平台（Geohazard Inspection Dashboard）
> 本文档面向参与本项目的开发者，覆盖架构、代码组织、核心模块、接口、数据库、部署与二次开发指南。

---

## 目录

1. [项目简介](#1-项目简介)
2. [技术栈](#2-技术栈)
3. [整体架构与目录结构](#3-整体架构与目录结构)
4. [本地开发环境搭建](#4-本地开发环境搭建)
5. [前端开发指南](#5-前端开发指南)
6. [后端开发指南](#6-后端开发指南)
7. [API 参考](#7-api-参考)
8. [数据库设计](#8-数据库设计)
9. [权限模型](#9-权限模型)
10. [实时数据通道](#10-实时数据通道)
11. [配置与环境变量](#11-配置与环境变量)
12. [Docker 部署](#12-docker-部署)
13. [二次开发指南](#13-二次开发指南)
14. [常见问题与注意事项](#14-常见问题与注意事项)

---

## 1. 项目简介

「地脉智巡」是一个面向地质灾害监测、巡检、预警和应急响应的综合管理平台。系统围绕 **灾害点 → 监测设备 → 巡检任务 → 预警事件 → 应急联动** 的业务主线，为区县/乡镇级别的地质灾害防治提供一体化支撑。

系统主要能力：

- **态势总览**：二维地图（OpenLayers）+ 三维视角（Cesium）+ 实时预警 + 天气 + 值班力量
- **灾害点管理**：隐患点档案、风险等级、威胁对象、空间坐标、关联记录
- **巡检任务**：任务创建、分派、GPS 签到、围栏校验、巡检上报闭环
- **监测设备**：设备在线状态、电量信号、上报趋势、离线告警
- **预警中心**：预警确认 → 派发 → 关闭的闭环流程
- **应急处置**：转移安置、专家会商、现场管控、处置复盘
- **统计分析**：风险趋势、巡检完成率、预警分布、区域热力

### 当前阶段说明

- **前端**：以真实后端为主。应用启动时会先探测后端健康状态，核心业务操作不再静默回退到本地 mock；若后端不可用，相关接口会给出明确错误提示。HazardDetailPage 展示 AI 位移趋势预测（未来 7 天柱状图 + 置信度）；WarningDetailPage 展示 AI 融合风险评分（等级 + 分数 + 置信度）；aiApi.js 封装 4 个端点调用。
- **后端**：已具备主要业务模块实现（灾害点/任务/设备/预警/巡检/附件/认证/统计/应急/值守人员位置/实时推送/AI 推理集成），预警六态状态机、应急 CRUD（含橙/红预警派发自动联动）、Analytics 真实聚合、WebSocket `/ws` + JWT 鉴权广播、EMQX MQTT 桥接（broker 侧认证通过 `infra/emqx/init.sh` 自动创建 BCrypt 用户）、TDengine 时序写入主链路、值守人员 Redis 实时缓存、设备数据上报 X-Device-Key 鉴权、**设备离线自动心跳**（`DeviceOnlineChecker` 每 60s 扫描 `last_report_at` 超时设备置离线并广播）、**AI 后端全链路**（AiServiceClient + AiForecastScheduler 从 DB 拉取历史、RuleEngineScheduler AI 融合升级预警等级、DeviceDataService 异步异常检测）均已落地。剩余缺口：真实 AI 模型文件需放入 `ai-service/models/`（训练脚本已就绪）、EMQX broker TLS 证书（broker 自身配置，代码已预留 username/password 鉴权）。

---

## 2. 技术栈

### 前端

| 类别       | 技术                                                        |
| ---------- | ----------------------------------------------------------- |
| 框架       | Vue 3（Options/Composition API 混用，多为 `<script setup>`) |
| 构建       | Vite 8（`@vitejs/plugin-vue` + `vite-plugin-cesium`）       |
| 路由       | vue-router (web history)                                    |
| 地图（2D） | OpenLayers 10（`mapService.js`）                            |
| 地图（3D） | Cesium 1.144（按需加载）                                    |

### 后端

| 类别      | 技术                                                                         |
| --------- | ---------------------------------------------------------------------------- |
| 框架      | Spring Boot 3.4.1                                                            |
| 语言      | Java 17                                                                      |
| 持久层    | Spring Data JPA + Hibernate                                                  |
| 空间数据  | Hibernate Spatial + PostGIS（`hibernate-spatial`，JTS `Point`，EPSG:4326）   |
| 安全      | Spring Security + JWT（jjwt 0.12.6）                                         |
| 缓存      | Spring Data Redis（已配置）                                                  |
| 对象存储  | MinIO（Minio SDK 8.5.12，已配置）                                            |
| 文档      | SpringDoc OpenAPI（Spring Boot 3 / WebMvc UI）                               |
| 校验      | Spring Boot Validation                                                       |
| AI 客户端 | Spring RestClient（`cn.geosentry.ai.AiServiceClient`，调用独立 Python 服务） |

### AI 推理服务（`ai-service/`，FastAPI）

| 类别     | 技术                                                        |
| -------- | ----------------------------------------------------------- |
| 框架     | Python FastAPI + Uvicorn                                    |
| 目标检测 | Ultralytics YOLOv8 + ONNX Runtime（模型缺失时降级为启发式） |
| 时序预测 | PyTorch LSTM（模型缺失时降级为线性外推）                    |
| 异常检测 | Scikit-learn Isolation Forest（降级为 Z-score）             |
| 融合预警 | XGBoost（降级为加权启发式）                                 |
| 部署     | Docker（独立容器 `ai-service`，端口 8000）                  |

> AI 服务为**可选增强**：模型/依赖缺失也能启动并返回 mock 结果，后端降级为纯规则模式，不影响既有业务。

### 基础设施（Docker Compose）

- PostgreSQL 15 + PostGIS
- Redis 7
- MinIO
- EMQX（MQTT 消息服务，已编排）
- TDengine（时序数据库，已编排）
- ai-service（FastAPI AI 推理服务，已编排）

---

## 3. 整体架构与目录结构

### 3.1 架构简图

```
┌─────────────────────────────────────────────┐
│  前端 SPA（Vue 3 + Vite）                    │
│  - views / components / composables         │
│  - services: dashboardApi / aiApi /         │
│    mapService / realtimeService / weatherApi│
│  - stores: 角色与权限 (auth.js)              │
└──────────────┬──────────────────────────────┘
               │ REST (Bearer JWT) / WebSocket
┌──────────────▼──────────────────────────────┐
│  后端 Spring Boot (容器内 8080 / 宿主机 8081)  │
│  auth · hazard · task · inspection · device │
│  warning · emergency · dashboard · system   │
│  + ai (AiServiceClient → AI 服务, 可降级)     │
└──────┬───────────────────────────────────────┘
       │ REST
┌──────▼──────────────────────────────────────┐
│  AI 推理服务 ai-service (FastAPI, 8000)      │
│  detect / forecast / anomaly / fusion-risk  │
└──────┬──────────────────────────────────────┘
       │
┌──────▼──────────────────────────────────────┐
│  基础设施                                       │
│  PostgreSQL+PostGIS / Redis / MinIO / EMQX /
│  TDengine                                    │
└─────────────────────────────────────────────┘
```

> AI 服务是**可选增强**：不可用或关闭时，后端降级为纯规则模式，其余业务不受影响。

### 3.2 目录结构

```
earthquake/
├── index.html                 # 前端 HTML 入口
├── package.json               # 前端依赖与脚本
├── vite.config.js             # Vite 配置（含 Cesium 插件）
├── .env.example               # 环境变量示例
├── docker-compose.yml         # 全栈容器编排
├── README.md                  # 基础说明
├── docs/
│   └── DEVELOPER.md           # 本文档
├── src/                       # 前端源码
│   ├── main.js                # 应用入口
│   ├── App.vue                # 根组件
│   ├── styles.css             # 全局样式
│   ├── router/index.js        # 路由 + 守卫 + 权限
│   ├── stores/auth.js         # 角色/权限/登录态
│   ├── layouts/DashboardLayout.vue  # 主布局
│   ├── services/              # 服务层
│       │   ├── dashboardApi.js    # 统一 API 封装（真实/模拟双模式）
│       │   ├── aiApi.js           # AI 服务客户端（detect/forecast/anomaly/fusion）
│       │   ├── mapService.js      # OpenLayers / Cesium 地图
│       │   ├── realtimeService.js # 实时数据（WS + 轮询 + 模拟）
│       │   ├── weatherApi.js      # 天气接口
│       │   └── hazardMapService.js# 视口数据加载
│   ├── composables/useHazardList.js  # 灾害点列表逻辑
│   ├── utils/geo.js           # 地理计算工具
│   ├── data/dashboardData.js  # 内置 mock 静态数据
│   ├── components/            # 通用组件
│   └── views/                 # 页面
├── backend/                   # 后端源码（Spring Boot）
│   ├── pom.xml
│   └── src/main/
│       ├── java/cn/geosentry/
│       │   ├── GeosentryApplication.java
│       │   ├── ai/            # AI 客户端 + 结果持久化（RestClient + JPA）
│       │   ├── auth/          # 登录 + JWT
│       │   ├── config/        # 安全/CORS/OpenAPI/属性配置
│       │   ├── common/        # 统一返回、异常、Geo、安全过滤器
│       │   ├── hazard/        # 灾害点
│       │   ├── task/          # 巡检任务
│       │   ├── inspection/    # 巡检记录（GPS 围栏）
│       │   ├── device/        # 监测设备（HTTP + EMQX MQTT 上报 + TDengine 主链路已接入）
│       │   ├── warning/       # 预警（六态状态机 + 规则引擎联动）
│       │   ├── emergency/     # 应急（完整 CRUD，橙/红预警派发时自动生成）
│       │   ├── personnel/     # 值守人员位置（Redis 实时缓存 + 移动端上报 /positions/report）
│       │   ├── dashboard/     # 仪表盘（真实表聚合 + analytics + summary 元数据）
│       │   ├── realtime/      # WebSocket /ws + 广播服务（RealtimeBroadcastService）
│       │   ├── attachment/    # 附件
│       │   └── health/        # 健康检查
│       └── resources/
│           ├── application.yml / -dev / -prod / -docker
│           ├── schema.sql     # 建表脚本（纯 DDL，spring.sql.init 幂等执行）
│           └── data.sql       # 种子数据（幂等）
├── ai-service/                # AI 推理服务（Python FastAPI）
│   ├── app/
│   │   ├── main.py            # FastAPI 入口（含 /api/ai/* 路由）
│   │   ├── routers/           # detection / forecast / anomaly / fusion
│   │   ├── models/            # 模型加载与推理（含降级实现）
│   │   └── schemas/           # Pydantic 模型
│   ├── Dockerfile
│   ├── requirements.txt
│   └── models/                # 存放训练好的模型文件（缺失则降级）
├── infra/postgres/init/       # PostGIS 扩展初始化（docker-entrypoint 首次建卷执行）
└── dist/                      # 前端构建产物
```

---

## 4. 本地开发环境搭建

### 4.1 环境要求

- Node.js 18+（推荐 20）
- JDK 17
- Maven 3.8+
- Docker + Docker Compose（可选，用于基础服务）

### 4.2 三步启动

```bash
# 1) 前端（进入项目根目录）
npm install
npm run dev        # 默认 5173 端口

# 2) 基础服务（Postgres/Redis/MinIO/EMQX/TDengine）
docker compose up -d

# 3) 后端（进入 backend 目录）
# 需要先安装 Maven 3.8+ 并加入 PATH；若提示“mvn 无法识别”，请先安装/配置 Maven
mvn spring-boot:run   # 默认监听 8080

# 备选：不想装 Maven 时，可直接用 Docker 启动后端（推荐，项目已内置后端容器编排）
# docker compose up -d backend   # 启动后访问 http://localhost:8081

# 端口速查（Docker 方式）：
#   后端 8081 = 容器内 8080，前端 5173，Postgres 15432，Redis 16379
#   MinIO API 19000 / 控制台 19001，EMQX MQTT 11883 / 控制台 28083，TDengine 16030/16041
```

> **宿主机直跑后端（`mvn spring-boot:run`）需要设置环境变量**，因为 Docker 把 Postgres/Redis 映射到了非标准端口，且 `JWT_SECRET` 无默认值：

```bash
# PowerShell（Windows）
$env:POSTGRES_PORT='15432'
$env:REDIS_PORT='16379'
$env:JWT_SECRET='replace-me-with-a-strong-random-secret-min-32-chars-x!'
# 可选：dev 模式保留内存 fallback 用户；生产设为 false
$env:DEV_USERS_ENABLED='true'
mvn spring-boot:run

# bash（macOS/Linux）
export POSTGRES_PORT=15432 REDIS_PORT=16379 JWT_SECRET='replace-me-with-a-strong-random-secret-min-32-chars-x!' DEV_USERS_ENABLED=true
mvn spring-boot:run
```

> 数据库初始化：`schema.sql` + `data.sql` 由 `spring.sql.init.mode: always` 在每次启动时幂等执行（建表/索引/种子），首次启动自动完成。

### 4.3 前端运行模式

前端启动时会先调用后端健康检查，优先进入真实后端模式；若健康检查失败或显式设置为不可用，则前端会明确提示当前不可用，而不是静默伪造成功。

| 配置                     | 行为                                                                                                                                                                                                                                                        |
| ------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `VITE_USE_BACKEND=true`  | 强制使用真实后端（`VITE_API_BASE_URL`，默认 `http://localhost:8081/api`）                                                                                                                                                                                   |
| `VITE_USE_BACKEND=false` | **纯 mock 模式**——完全不走后端，使用 `dashboardApi.js` 顶部 `seedState` 演示数据集（hazardPoints / tasks / devices / warnings / incidents / overviewStats）。所有读操作返回 seedState 数据；写操作/导出返回本地 mock 成功（不会持久化），页面刷新后变更丢失 |
| 未设置                   | 启动时自动探测 `GET /api/system/health`，成功则进入后端模式，失败则报错                                                                                                                                                                                     |

> 生产部署必须使用 `VITE_USE_BACKEND=true` 并确保后端可用，禁止 mock 模式上线。mock 模式仅供无后端环境下的界面演示。

---

## 5. 前端开发指南

### 5.1 应用入口

- `src/main.js`：创建应用、挂载路由、引入 `styles.css`。
- `src/App.vue`：根组件（全局路由视图）。
- `src/router/index.js`：全部路由定义 + 全局前置守卫。

### 5.2 路由表

所有业务页面挂在 `DashboardLayout`（带侧边菜单）之下：

| 路径            | 视图              | 说明                       |
| --------------- | ----------------- | -------------------------- |
| `/login`        | LoginPage         | 登录（公开）               |
| `/`             | OverviewPage      | 态势总览                   |
| `/hazards`      | HazardsListPage   | 灾害点列表                 |
| `/hazards/:id`  | HazardDetailPage  | 灾害点详情（含 3D）        |
| `/tasks`        | TasksPage         | 巡检任务 + GPS 签到 + 上报 |
| `/tasks/:id`    | TaskDetailPage    | 任务详情                   |
| `/devices`      | DevicesPage       | 监测设备                   |
| `/devices/:id`  | DeviceDetailPage  | 设备详情                   |
| `/warnings`     | WarningsPage      | 预警中心                   |
| `/warnings/:id` | WarningDetailPage | 预警详情                   |
| `/emergency`    | EmergencyPage     | 应急处置                   |
| `/analytics`    | AnalyticsPage     | 统计分析                   |

> 另存在 `src/views/HazardsPage.vue`、`TaskListPage.vue` 作为早期/精简版视图，当前路由未直接引用。

### 5.3 路由守卫逻辑（`router.beforeEach`）

依次执行：

1. 若 `to.meta.public`（如登录页）→ 放行。
2. 无 token → 跳转 `/login?redirect=原路径`。
3. 当前角色不可访问该路径（`canAccessPath`）→ 跳转到该角色首页。
4. 路由对应权限码不满足（`hasPermission`）→ 跳转到角色首页。

### 5.4 服务层封装（核心）

`src/services/dashboardApi.js` 是唯一的数据访问入口，全部接口导出为 `dashboardApi.*`。

设计要点：

- `request()`：用 `fetch` 请求后端，约定后端返回 `{ code, message, data, timestamp }`，`code === 0` 为成功。
- `normalizePaged()`：统一分页形状（后端统一返回 `PageResponse{records,total,page,size,pages}` 五字段，无历史兼容分支）。
- `withQuery()`：构造查询字符串。
- `requireBackend()`：在当前不允许模拟成功的场景下，如果后端不可用则抛出明确错误，避免“看起来成功但实际没同步”的假象。
- `seedState`：**仅供 `VITE_USE_BACKEND=false`（mock 模式）使用**的演示数据集（hazardPoints / tasks / devices / warnings / incidents / overviewStats）。生产模式下 `fallback()` 直接返回空数组，不会误用。详见 `dashboardApi.js` 顶部 `USE_BACKEND` gating 逻辑。
- 详情聚合：`getHazardDetail` / `getDeviceDetail` / `getTaskDetail` / `getWarningDetail` 返回 `{ item, history, attachments, linked }`，其中 `linked` 为可跳转的关联实体数组 `{ type, label, path }`（由后端或服务层关系推导），详情页以此渲染跨模块导航。
- `loginRequest()`：登录，成功返回 `{ token, user }`。

所有业务方法（getHazards / getTasks / getDevices / getWarnings / getAnalytics / getEmergency / uploadAttachment / gpsCheck / submitInspection / export\* 等）均走真实后端；后端不可用时直接报错提示，不再静默回退为“伪成功”。

### 5.5 地图（`services/mapService.js`）

- `createOpenLayersController(container, opts)`：创建二维地图控制器，返回 `{ map, setMode, setCenter, setHazards, setDevices, setWarnings, setPersonnel, fitItems, destroy, ... }`。
- 底图：
  - 天地图（`VITE_TDT_TOKEN`）矢量/注记/影像
  - 无 token 时回退 OpenStreetMap / ArcGIS 影像
  - `VITE_TDT_ATTRIBUTION` 设置版权
- 图层：灾害点（hazard）、设备（device）、预警（warning，红色/橙色/蓝色分级样式）、值守人员（personnel）均带**聚合（Cluster）**与差异化样式。
- 点选联动：态势页点击灾害点弹出详情浮层、点击设备跳转设备详情、点击预警跳转预警详情、点击值守人员打开轨迹抽屉。
- 值守人员轨迹：`setPersonnelTrails()` 用 `LineString` 绘制。
- 3D：`createCesiumViewer()` / `addCesiumPoint()` / `destroyCesiumViewer()`，在灾害点详情页按需动态 import 加载。

### 5.6 实时数据（`services/realtimeService.js`）

提供统一的实时状态订阅：

- 数据源优先级：WebSocket 推送（`VITE_WS_URL`）→ 预警高频轮询（`VITE_WARNING_POLL_INTERVAL`，默认 10s）→ 设备/汇总低频轮询（兜底 15s）→ 值守人员轮询。
- **WS 降级策略**（已实现，不是规划项）：三条轮询 timer（pollingTimer / warningTimer / personnelTimer）**始终独立运行**，与 WebSocket 状态无关。WebSocket 连接失败（onerror）或中断（onclose）时，5 秒后自动重连（重连时重新取 token，处理 token 刷新场景）。即使 WS 长期不可用，轮询持续兜底，实时数据不会中断。
- 状态缓存：`state = { warnings, devices, personnel }`。
- API：`startRealtimeSync` / `stopRealtimeSync` / `onRealtimeUpdate(listener)` / `getRealtimeState`。
- 事件类型：`snapshot`、`poll`、`warning-poll`、`warning`、`device`、`personnel`、`personnel-poll`、`personnel-motion`、`socket-open`、`socket-close`、`socket-error`。
- **幂等合并**：WebSocket 增量与轮询全量均通过 `upsert`/`mergeSnapshot` 按 `version + updatedAt` 做新旧比较，后到的旧数据不覆盖新状态，避免重连补发重复与界面回退（`isNewer`）。
- **值守人员数据模型**：前端 `PERSONNEL_ROSTER` 常量（姓名/角色/区域/电话/班组/班次）提供人员元数据，坐标来源分三层：
  1. 移动端上报 → `POST /api/personnel/positions/report` → Redis key `personnel:pos:{userId}`（TTL 120s）→ WebSocket 广播
  2. `GET /api/personnel/positions` 优先读 Redis，再与 DB fallback 合并（`inspection_record` 最近签到坐标 + `inspection_task` 关联 `hazard_point` 坐标）
  3. `VITE_USE_PERSONNEL_LIVE=true` 时前端仅轮询后端，关闭本地轨迹推演
- 轮询间隔：`VITE_REALTIME_POLL_INTERVAL`（默认 15s）、`VITE_WARNING_POLL_INTERVAL`（默认 10s）、`VITE_PERSONNEL_POLL_INTERVAL`（默认 30s）。

### 5.7 天气（`services/weatherApi.js`）

- `getRealtimeWeather()`：调 OpenWeatherMap（`VITE_WEATHER_API_KEY` / `VITE_WEATHER_API_URL`），无 Key 时返回内置回退数据。
- `getWeatherAlertLevel()`：根据降水概率/湿度/温度计算「高/中/低」预警等级。

### 5.8 权限与通用组件

- `PermissionButton.vue`：按钮级权限控制。`permission` 存在则放行（回调 `{ allowed: true }`），否则拦截并回调 `{ allowed: false, reason }`。
- `FormModal.vue`：通用弹窗（标题/插槽/确认/取消）。
- `AppFeedback.vue`：全局 loading / error / success 反馈条。
- `PersonnelTooltip.vue` / `PersonnelRouteDrawer.vue`：值守人员悬浮提示与轨迹抽屉。

### 5.9 注释与约定

- 页面统一通过 `emit('notify', msg)` 向上弹出轻提示。
- 布局通过 props/事件与页面交互（如 `open-task-modal`、`go-to`）。
- 新增操作类接口请先检查对应权限码并用到 `PermissionButton`。

---

## 6. 后端开发指南

### 6.1 分层

每个业务域按 Spring 惯例分层：

```
controller/  → REST 接口（@RestController + ApiResponse<T>）
service/     → 业务逻辑（@Service + @Transactional）
repository/  → Spring Data JPA 接口
entity/      → JPA 实体
dto/         → 请求/响应记录（record）
```

### 6.2 统一返回结构与错误处理

- 返回包装：`cn.geosentry.common.api.ApiResponse<T>`：`{ code, message, data, timestamp }`，`code=0` 成功。
- 分页包装：`cn.geosentry.common.api.PageResponse<T>`：`{ records, total, page, size, pages }`（**统一结构**，所有 Controller 分页接口均返回此形状。历史上曾存在不含 `pages` 的 `PagedResponse`，现已废弃删除）。
- 业务异常：`AppException(code, message)`。
- 全局异常处理：`GlobalExceptionHandler`（`@RestControllerAdvice`），统一处理：
  - `AppException` → HTTP 400 + 业务 code/message
  - `MethodArgumentNotValidException` → 400 + 首个字段校验消息
  - 其他异常 → 500「系统繁忙，请稍后重试」

### 6.3 安全与鉴权

- 配置：`config/SecurityConfig.java`
  - 无状态 Session（`STATELESS`）、禁用 CSRF、关闭 httpBasic/formLogin。
  - 放行：`/swagger-ui/**`、`/v3/api-docs/**`、`/api/system/health`、`/api/auth/login`。
  - `/api/auth/ws-ticket` **不在** permitAll 列表中：需携带有效 JWT 访问（`JwtAuthenticationFilter` 拦截后放行），签发一次性 WebSocket ticket。
  - 其他请求需认证。
  - `UserDetailsService`：`auth/security/DbUserDetailsService`（**DB 优先 + 内存 fallback**）。从 `sys_user` 表加载用户并校验 BCrypt 密码；`DEV_USERS_ENABLED=true` 时 DB 未命中回退到 admin/attendant/maintenance 三个硬编码账号（便于无 DB 演示）。生产环境务必设置 `DEV_USERS_ENABLED=false`。
- 过滤器：`common/security/JwtAuthenticationFilter`
  - 从 `Authorization: Bearer <token>` 读取并解析 JWT，写入 `SecurityContext`。
  - 把 `role` claim 映射为 `ROLE_<ROLE>` + `PermissionRegistry` 注册的所有权限码 authority。
- 设备鉴权：`common/security/DeviceAuthFilter`（注入 `ROLE_DEVICE` + `device:report` 权限）
  - 校验 `X-Device-Key` header 与 `monitoring_device.api_key` 列匹配。
  - `DEVICE_AUTH_STRICT=false`（默认，迁移期）：设备未登记 api_key 时允许上报但 WARN 日志；
  - `DEVICE_AUTH_STRICT=true`（生产环境）：未登记 api_key 的设备直接返回 403。
- JWT：`auth/jwt/JwtService` + `JwtProperties`
  - 过期时间默认 `PT2H`（见 `JWT_EXPIRE`），密钥来自 `JWT_SECRET`。**启动时强制校验**：长度 ≥32 且不在弱密钥黑名单（change-me / default-secret 等）。不满足则应用启动即失败（`IllegalStateException`）。
- WebSocket 鉴权：`realtime/JwtHandshakeInterceptor`
  - 推荐：客户端先调 `GET /api/auth/ws-ticket` 获取**一次性 60s ticket**，用 `ws://host/ws?ticket=xxx` 连接。ticket 一次消费，反向代理日志里只看到随机 token。
  - 兼容：直接 `ws://host/ws?token=<JWT>`（旧客户端）。注意：长期 JWT 会暴露在服务器日志中。
  - 拦截器同时支持两种方式，握手失败返回 401。
- EMQX 鉴权：
  - `realtime/EmqxSecurityPreflight` 启动时检查：`EMQX_ENABLED=true` 但未配置 `EMQX_USERNAME`/`EMQX_PASSWORD` 时输出清晰 WARN，提醒 MQTT broker 侧无认证，任何客户端可伪造设备数据。
  - 实际用户名密码在 `application.yml` 的 `emqx.username`/`emqx.password` 配置，需与 EMQX broker 的认证用户一致。
- CORS：`config/CorsConfig`，允许 `geosentry.frontend-origin`（默认 `http://localhost:5173`），允许携带凭证。

> ⚠️ 当前 CORS 与安全配置已在持续收敛中；如你在本地开发时遇到跨域或鉴权失败，请优先检查 `FRONTEND_ORIGIN`、`VITE_API_BASE_URL` 与登录态是否一致。生产环境不应保留过宽的跨域白名单。

### 6.4 业务域说明

- **auth**：`POST /api/auth/login` 签发 JWT；登录响应 `LoginResponse{ token, user{name,role,username} }`。JWT 携带 `role` claim；后端从 `PermissionRegistry` 按角色注入权限码 authority，配合 `@EnableMethodSecurity` + `@PreAuthorize` 做方法级授权（权限码与前端 `ROLE_PERMISSION_MAP` 一致）。
- **hazard**：灾害点 CRUD、视口查询（PostGIS `ST_MakeEnvelope` + GiST，见 `HazardPointRepository.findInViewport`）、GeoJSON。新增/修改经 `GeometryFactory` 生成 `geom`。导出走 EasyExcel 生成真实 xlsx（`/export`）。
- **task**：任务分页/CRUD/状态更新（`PATCH /{id}/status`，已完成置 progress=100）/导出（EasyExcel）。
- **inspection**：GPS 围栏校验（`/inspection/gps-check`，Haversine 距离）、提交巡检记录（`/inspection/submit`，写入时生成 `geom`）、按任务/灾害点查询记录。
- **device**：设备分页（JPA Specification）/视口/CRUD/诊断（`POST /{id}/diagnose`）/导出。**数据上报链路**：`POST /api/devices/{code}/data` 经 `DeviceAuthFilter` 校验 `X-Device-Key` header（匹配 `monitoring_device.api_key` 列），通过后写入 `device_metric` 表并异步触发 AI 异常检测（见 `DeviceDataService`）。同时同步写入 TDengine 时序存储（`tdengine.enabled=true`），try-catch 不影响 PG 事务。EMQX MQTT 桥接（`MqttDataSubscriber`）订阅 `geohazard/{deviceCode}/data`，鉴权由 broker 侧 username/password 处理。
- **warning**：预警分页（Specification）/视口/详情/**预览（`GET /preview`）**/更新（`PUT /{id}`，admin）/确认/派发/关闭/忽略/解除。状态流转已补全为六态：`待确认 → 已确认 → 处理中 → 已关闭 / 已忽略 / 已解除`（`confirm`/`dispatch`/`close`/`dismiss`/`resolve`）。`close(id, reason, operator)` 按 reason 语义分流，关闭/忽略/解除均落 `closed_at` + `close_reason` + `closed_by`（`warning_event` 表新增两列）。**预警规则引擎**：`RuleEngineScheduler` 定时评估 `warning_rule`，命中且未闭环时自动生成预警，并可联动 AI 融合评分（见 §10.5）。预警等级文案映射：含「橙」→ orange、含「黄」→ yellow、其余 → blue。
- **emergency**：应急处置完整 CRUD（列表查询 `emergency_event` 表、创建、关闭 `DELETE /{id}` 标记已闭环）。`EmergencyController` + `EmergencyService` 已落地，不再依赖 `pageOfSamples` 演示数据。**预警派发联动自动生成应急事件**已实现：`WarningService.dispatch` 检测到橙色/红色预警时自动调用 `EmergencyService.createFromWarning` 创建应急事件，同一预警未闭环前不重复创建（幂等三层防御：exists 检查 + PG 部分唯一索引 + DataIntegrityViolationException 兜底）。
- **dashboard**：overview / charts / analytics 均对真实表聚合（灾害点/设备/预警/任务），见 `DashboardService`。`analytics` 端点的 heatmap 按 `hazard_point.region_code` 真实聚合生成 `RegionRisk`（intensity/risk），riskZones 按区域风险分布生成。死代码 `pageOfSamples` / `notImplemented` 已清理。
- **personnel**：`GET /api/personnel/positions`（`PersonnelController`）返回值守人员实时位置，**三层叠加**：① Redis 有实时上报（`POST /positions/report`）时优先读 Redis 并与 DB 值守花名册合并；② Redis 无数据时走 DB fallback（`inspection_record` 最近签到坐标 + `inspection_task` 关联 `hazard_point` 坐标）；③ 前端演示模式（`VITE_USE_PERSONNEL_LIVE=false`）走 `PERSONNEL_ROSTER` mock 轨迹推演。
- **attachment**：附件上传已接入 MinIO 真实对象写入（`MinioClient.putObject`），返回可访问 URL；删除时同步删除对象（见 `AttachmentService`）。
- **health**：`GET /api/system/health` → 健康探针。
- **ai**：`cn.geosentry.ai` 包，包含 `AiServiceClient`（RestClient 调用独立 FastAPI 服务，四大能力：detect / forecast / anomaly / fusion-risk）、`AiAnalysisController`（透传推理 + 分析记录查询）、`AiForecastScheduler`（定时批量预测，默认关闭）、`AiAnalysisService`（AI 结果持久化到 `ai_analysis_result`，含降级）。AI 服务不可用或 `ai.enabled=false` 时抛出 `AiUnavailableException`，上层应降级为纯规则模式。

### 6.5 OpenAPI / Swagger

- UI：`/swagger-ui.html`
- JSON：`/v3/api-docs`
- 标题：Geosentry API（`config/OpenApiConfig`）

---

## 7. API 参考

> 前缀：`/api`。请求需带 `Authorization: Bearer <token>`（除标注公开的接口）。

### 7.1 认证

| 方法 | 路径                        | 说明                                                                                             | 公开               |
| ---- | --------------------------- | ------------------------------------------------------------------------------------------------ | ------------------ |
| POST | `/api/auth/login`           | 登录，返回 token                                                                                 | ✅                 |
| POST | `/api/auth/logout`          | 退出                                                                                             | ✅（无状态，占位） |
| POST | `/api/auth/change-password` | 当前登录用户修改自己的密码（需 old_password + new_password，≥8 字符含字母和数字）                | 🔒（需有效 JWT）   |
| GET  | `/api/auth/ws-ticket`       | 为已认证用户签发 WebSocket 一次性 ticket（60s TTL，一次性消费），用于替代在 query 中传递长期 JWT | 🔒（需有效 JWT）   |

### 7.2 系统

| 方法 | 路径                 | 说明                                                                                                                                |
| ---- | -------------------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| GET  | `/api/system/health` | 健康检查（默认检测 PostgreSQL 连通性，DB 不可用时返回 503 + status=DEGRADED；加 `?detailed=true` 补充 JVM 堆/线程数/TDengine 状态） |

### 7.3 仪表盘

| 方法 | 路径                       | 说明                                                                                                                                                                                                    |
| ---- | -------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| GET  | `/api/dashboard/overview`  | 总览指标卡片                                                                                                                                                                                            |
| GET  | `/api/dashboard/charts`    | 图表数据（分析页）                                                                                                                                                                                      |
| GET  | `/api/dashboard/analytics` | 统计分析聚合（`{ hazardPoints, overviewStats, charts }`，charts 含 riskTrend/monthlyInspection/warningType/deviceTrend/heatmap/riskZones，均为真实表聚合）                                              |
| GET  | `/api/dashboard/summary`   | 后端元数据：版本号、模块计数（hazardPoints/monitoringDevices/warningEvents/warningRules/inspectionTasks/inspectionRecords/emergencyEvents）、特性开关（ruleEngine/aiFusion/emergencyLinkage/webSocket） |

### 7.4 灾害点

| 方法   | 路径                    | 说明                                                     |
| ------ | ----------------------- | -------------------------------------------------------- |
| GET    | `/api/hazards`          | 分页列表（page/size/keyword/regionCode/level/type/bbox） |
| GET    | `/api/hazards/viewport` | 视口内灾害点                                             |
| GET    | `/api/hazards/geojson`  | GeoJSON 输出                                             |
| GET    | `/api/hazards/{id}`     | 详情（含 history/attachments/linked）                    |
| POST   | `/api/hazards`          | 新增                                                     |
| PUT    | `/api/hazards/{id}`     | 更新                                                     |
| DELETE | `/api/hazards/{id}`     | 删除                                                     |
| POST   | `/api/hazards/export`   | 导出（EasyExcel 真实 xlsx 二进制流）                     |

### 7.5 巡检任务

| 方法   | 路径                     | 说明                                 |
| ------ | ------------------------ | ------------------------------------ |
| GET    | `/api/tasks`             | 分页列表（page/size/keyword/status） |
| GET    | `/api/tasks/{id}`        | 详情                                 |
| POST   | `/api/tasks`             | 新增                                 |
| PUT    | `/api/tasks/{id}`        | 更新                                 |
| PATCH  | `/api/tasks/{id}/status` | 更新状态                             |
| DELETE | `/api/tasks/{id}`        | 删除                                 |
| POST   | `/api/tasks/export`      | 导出（EasyExcel 真实 xlsx 二进制流） |

### 7.6 巡检上报

| 方法 | 路径                                  | 说明                                                             |
| ---- | ------------------------------------- | ---------------------------------------------------------------- |
| POST | `/api/inspection/gps-check`           | GPS 围栏校验（taskCode/hazardCode/lon/lat/allowedRadius）        |
| POST | `/api/inspection/submit`              | 提交巡检记录                                                     |
| GET  | `/api/inspection/task/{taskCode}`     | 按任务查记录                                                     |
| GET  | `/api/inspection/hazard/{hazardCode}` | 按灾害点查记录                                                   |
| GET  | `/api/inspection/example`             | 巡检上报接口示例 payload（含完整 JSON 结构说明，移动端联调参考） |

### 7.7 监测设备

| 方法   | 路径                             | 说明                                                                                                                                                                                                 |
| ------ | -------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| GET    | `/api/devices`                   | 分页（page/size/keyword/state/bbox/zoom）                                                                                                                                                            |
| GET    | `/api/devices/viewport`          | 视口设备                                                                                                                                                                                             |
| GET    | `/api/devices/{id}`              | 详情                                                                                                                                                                                                 |
| POST   | `/api/devices`                   | 新增                                                                                                                                                                                                 |
| PUT    | `/api/devices/{id}`              | 更新                                                                                                                                                                                                 |
| DELETE | `/api/devices/{id}`              | 删除                                                                                                                                                                                                 |
| POST   | `/api/devices/{id}/diagnose`     | 设备诊断                                                                                                                                                                                             |
| POST   | `/api/devices/export`            | 导出（EasyExcel 真实 xlsx 二进制流）                                                                                                                                                                 |
| POST   | `/api/devices/{deviceCode}/data` | 设备数据上报（`DeviceAuthFilter` 校验 `X-Device-Key` header 与 `monitoring_device.api_key` 匹配；未登记 api_key 的设备允许上报但 WARN 提示。写入 `device_metric`，供规则引擎；异步触发 AI 异常检测） |

### 7.8 预警

| 方法   | 路径                          | 说明                                                                                                             |
| ------ | ----------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| GET    | `/api/warnings`               | 分页（page/size/keyword/status）                                                                                 |
| GET    | `/api/warnings/viewport`      | 视口预警（bbox/keyword/status）                                                                                  |
| GET    | `/api/warnings/preview`       | 活跃预警预览（排除已关闭/已忽略/已解除）                                                                         |
| GET    | `/api/warnings/{id}`          | 详情                                                                                                             |
| PUT    | `/api/warnings/{id}`          | 更新（admin；title/level/status）                                                                                |
| POST   | `/api/warnings/{id}/confirm`  | 确认（→ 已确认）                                                                                                 |
| POST   | `/api/warnings/{id}/dispatch` | 派发（→ 处理中）                                                                                                 |
| POST   | `/api/warnings/{id}/close`    | 关闭（body `{reason}`；reason="误报"→已忽略、"自动解除"→已解除、其他→已关闭；记录 `close_reason` + `closed_by`） |
| POST   | `/api/warnings/{id}/dismiss`  | 误报忽略（→ 已忽略，固定 reason="误报"）                                                                         |
| POST   | `/api/warnings/{id}/resolve`  | 自动解除（→ 已解除，固定 reason="自动解除"）                                                                     |
| DELETE | `/api/warnings/{id}`          | 删除（admin）                                                                                                    |
| POST   | `/api/warnings/export`        | 导出（EasyExcel 真实 xlsx 二进制流）                                                                             |

### 7.9 应急

| 方法   | 路径                  | 说明                                                                                                                         |
| ------ | --------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| GET    | `/api/emergency`      | 事件列表（查询 `emergency_event` 表，支持分页）                                                                              |
| POST   | `/api/emergency`      | 创建应急事件（手动触发；橙/红预警派发时 `WarningService.dispatch` 自动调用 `createFromWarning`，同一预警未闭环前不重复创建） |
| DELETE | `/api/emergency/{id}` | 关闭应急事件（标记为已闭环，需 `emergency:close` 权限）                                                                      |

### 7.10 附件

| 方法   | 路径                                           | 说明                                                                                                      |
| ------ | ---------------------------------------------- | --------------------------------------------------------------------------------------------------------- |
| GET    | `/api/attachments?businessType=&businessCode=` | 附件列表（两个参数均为必填）                                                                              |
| POST   | `/api/attachments` (multipart/form-data)       | 上传（Query 携带 businessType / businessCode，文件字段名 `file`；MinIO 真实对象写入，返回 presigned URL） |
| GET    | `/api/attachments/{id}`                        | 附件详情（每次访问自动刷新 presigned URL，避免 MinIO 7 天过期）                                           |
| POST   | `/api/attachments/{id}/refresh-url`            | 强制刷新附件 presigned URL（前端检测到 403/410 时调用），返回新 URL                                       |
| DELETE | `/api/attachments/{id}`                        | 删除（同步删除 MinIO 对象，失败时兜底仅删元数据）                                                         |

### 7.11 AI 接入

> 后端通过 `AiServiceClient`（RestClient）调用独立 `ai-service`（FastAPI）。AI 服务不可用或 `AI_ENABLED=false` 时，业务调用自动降级，不阻塞主流程。公开的推理接口仍返回 503（`AiUnavailableException`），由调用方决定是否降级。

#### 后端代理接口（透传 AI 服务）

| 方法 | 路径                            | 说明                                                |
| ---- | ------------------------------- | --------------------------------------------------- |
| POST | `/api/ai/detect` (multipart)    | 图像识别，字段名 `file`；`modelType` 可选           |
| POST | `/api/ai/forecast`              | 时序预测 `{deviceCode, metric, horizon, history[]}` |
| POST | `/api/ai/anomaly`               | 异常检测 `{deviceCode, data[]}`                     |
| POST | `/api/ai/fusion-risk`           | 融合预警 `{hazardCode, factors{}}`                  |
| POST | `/api/ai/analysis/forecast/run` | 手动触发一次批量预测（联调用）                      |

#### AI 分析记录

| 方法   | 路径                                           | 说明             |
| ------ | ---------------------------------------------- | ---------------- |
| GET    | `/api/ai/analysis?businessType=&businessCode=` | 查询 AI 分析记录 |
| DELETE | `/api/ai/analysis/{id}`                        | 删除 AI 分析记录 |

#### AI 服务自身接口（`ai-service`，端口 8000）

| 方法 | 路径                  | 说明                         |
| ---- | --------------------- | ---------------------------- |
| GET  | `/api/ai/health`      | AI 服务健康检查              |
| POST | `/api/ai/detect`      | 图像识别（multipart `file`） |
| POST | `/api/ai/forecast`    | 时序预测                     |
| POST | `/api/ai/anomaly`     | 异常检测                     |
| POST | `/api/ai/fusion-risk` | 融合预警                     |
| GET  | `/api/ai/docs`        | FastAPI 自动文档（Swagger）  |

> AI 服务定义了**统一的 ApiResponse 包装**（`code` / `message` / `data`），与后端风格一致，便于解析。

### 7.12 值守人员

生产模式（`VITE_USE_PERSONNEL_LIVE=true`）下，移动端每 10~30s 上报一次 GPS，后端以 Redis 缓存（TTL 120s）维护实时位置，并通过 WebSocket 广播给前端。位置获取优先级：Redis 实时上报 → DB fallback（巡检签到 / 任务关联灾害点）。

| 方法 | 路径                              | 说明                                                                                                                                                                                                                       |
| ---- | --------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| GET  | `/api/personnel/positions`        | 返回值守人员实时位置列表。Redis 有数据时优先读 Redis 并与 DB 值守花名册合并；Redis 无数据时走 DB fallback（inspection_record 最近签到 + inspection_task 关联 hazard_point 坐标）。                                         |
| POST | `/api/personnel/positions/report` | 移动端实时位置上报（**需有效 JWT**，不再 permitAll）。请求体：`{ userId, name, lon, lat, taskCode?, hazardCode?, speed?, heading? }`。写入 Redis key `personnel:pos:{userId}`（TTL 120s），同时通过 WebSocket 广播给前端。 |

> 该接口已由 `PersonnelController` 实现。Redis 不可用时优雅降级（返回 DB fallback），不阻塞业务。

### 7.13 实时推送（WebSocket）

后端提供原生 WebSocket 端点，用于推送预警、应急事件、值守人员位置变更。

| 路径  | 协议                              | 说明                                                                                                                                                                                                                                                                        |
| ----- | --------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `/ws` | 原生 WebSocket（非 STOMP/SockJS） | **推荐**：`ws://host:port/ws?ticket=${ticket}`（一次性 60s ticket，安全）；**兼容**：`ws://host:port/ws?token=${authToken}`（旧客户端，JWT 会暴露在服务器日志）。生产环境设 `WS_REQUIRE_TICKET=true` 强制禁用 raw JWT。握手失败返回 401。消息格式 `{ type, data, version }` |

**消息类型**：`warning`（预警状态变更）、`emergency`（应急事件新建/闭环）、`personnel`（值守人员位置更新）、`device`（设备状态变更）。

> **消息类型 payload 示例**：
>
> - `warning`: `{"type":"warning","data":{"warningCode":"WG-001","status":"已确认","warningLevel":"红色","title":"青龙坡位移超限"},"version":1001}`
> - `emergency`: `{"type":"emergency","data":{"eventCode":"EM-001","status":"处置中","title":"青龙坡滑坡应急启动"},"version":1002}`
> - `personnel`: `{"type":"personnel","data":{"username":"周小雨","hazardCode":"HZ-001","longitude":107.21,"latitude":30.61,"status":"在现场"},"version":1003}`
> - `device`: `{"type":"device","data":{"deviceCode":"DV-001","onlineStatus":"离线","batteryLevel":23},"version":1004}`

前端（`src/services/realtimeService.js`）连接时**优先**调 `GET /api/auth/ws-ticket` 获取一次性 ticket，用 `?ticket=` 连接；失败时 fallback 到 `?token=`。重连时重新取 ticket，自动处理 token 刷新。基于 `version` 字段做幂等 upsert，丢弃过期/重复消息。后端 `RealtimeBroadcastService` 在 WarningService 的 confirm/dispatch/close/dismiss/resolve/create、EmergencyService 的 createFromWarning/resolve、DeviceDataService 的在线状态变化检测、PersonnelController 的位置上报中自动触发广播。

> `version` 字段为服务端递增计数器（`AtomicLong.incrementAndGet()`），每次广播自动 +1。**初始值为 `System.currentTimeMillis()`**（毫秒级时间戳，约 1.7e12 起），保证服务重启后 version 仍单调递增，不会因归零导致前端丢弃新消息。前端基于 `version` 做幂等 upsert——只接收 version 更大的新消息，丢弃重复/过期消息。
> **注意**：不要将 version 当作时间戳解析，它只是递增序号。

> 配置开关：环境变量 `WS_REQUIRE_TICKET=true`（prod 推荐）强制仅接受 ticket，禁用 raw JWT；`WS_ENABLED=true` 启用 WebSocket（默认启用）。

### 7.14 设备数据链路

| 方法 | 路径                                | 说明                                                                                                                                                                                                                                                                                                                                                                 |
| ---- | ----------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| POST | `/api/devices/{code}/data`          | HTTP 上报指标（主通道之一）。鉴权：`DeviceAuthFilter` 校验 `X-Device-Key` header 与 `monitoring_device.api_key` 匹配。未登记 api_key 的设备允许上报但 WARN 提示（便于迁移）。解析 JSON 写入 `device_metric` 表，异步触发 Isolation Forest 异常检测（命中则保存 AI 分析记录）。同时支持 TDengine 时序写入（`tdengine.enabled=true` 时生效，try-catch 不影响 PG 事务） |
| —    | `MQTT: geohazard/{deviceCode}/data` | EMQX 桥接（`MqttDataSubscriber`），Spring Boot 启动时自动订阅（`emqx.enabled=true`）。鉴权由 broker 侧 username/password 处理（代码已预留 `emqx.username`/`emqx.password` 参数）。解析 JSON 后调用 `DeviceDataService.ingest()` 走同一主链路。EMQX 不可达时仅 WARN + 指数退避重连，不阻塞应用启动                                                                    |

**TDengine 时序存储**（已接入主链路）：`DeviceDataService.ingest` 在写入 PostgreSQL 的 `device_metric` 表后，自动调用 `TdengineMetricService.writeMetric()` 同步写入 TDengine。启用方式：

1. Maven 依赖（已在 `backend/pom.xml` 声明 scope=provided）：`com.taosdata.jdbc:taos-jdbcdriver:3.3.0`——部署时将 `taos-jdbcdriver-3.3.0.jar` 放入后端 classpath 即可；
2. 配置 `tdengine.enabled=true`；
3. try-catch 包裹，TDengine 不可用时不影响 PG 事务（日志 WARN）。

> **双通道协调策略**：HTTP 为主动上报（设备批量推送），MQTT 为实时补充（broker 转发后端订阅）。
> 去重语义：PostgreSQL 唯一索引 `idx_device_metric_unique(device_code, metric_code, recorded_at)` 保证幂等，
> 重复到达的第二条被 catch 并跳过（DEBUG 日志），保留先到者。
> 设备端必须发送服务端时间戳（若设备时钟漂移 < 1s，`recorded_at` 精度到秒仍可能视为重复，这是设计预期）。
> 若设备需高精度时间，建议毫秒级上报并使用自定义去重键。

---

## 8. 数据库设计

### 8.1 表清单（`backend/src/main/resources/schema.sql`）

| 表                                                                                                                                                   | 用途                                                                                                                                                                                                                                                                                                                                                                                                |
| ---------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `sys_role`                                                                                                                                           | 角色（menu_permissions / button_permissions）                                                                                                                                                                                                                                                                                                                                                       |
| `region`                                                                                                                                             | 行政区划（含 path 层级）                                                                                                                                                                                                                                                                                                                                                                            |
| `sys_user`                                                                                                                                           | 用户                                                                                                                                                                                                                                                                                                                                                                                                |
| `sys_menu`                                                                                                                                           | 菜单/权限点                                                                                                                                                                                                                                                                                                                                                                                         |
| `hazard_point`                                                                                                                                       | 灾害点（含 `geom geometry(Point,4326)` PostGIS 空间字段）                                                                                                                                                                                                                                                                                                                                           |
| `inspection_task`                                                                                                                                    | 巡检任务（含 `hazard_code` 关联字段，供值守人员位置 fallback 使用）                                                                                                                                                                                                                                                                                                                                 |
| `inspection_record`                                                                                                                                  | 巡检上报记录（签到坐标/距离/异常标记）                                                                                                                                                                                                                                                                                                                                                              |
| `monitoring_device`                                                                                                                                  | 监测设备（含 longitude/latitude/geom PostGIS 字段、`api_key VARCHAR(64)` 鉴权密钥列）                                                                                                                                                                                                                                                                                                               |
| `device_metric`                                                                                                                                      | **设备上报指标样本**（device_code/metric_code/value，供规则引擎消费）                                                                                                                                                                                                                                                                                                                               |
| `warning_rule`                                                                                                                                       | 预警规则（metric_code + expression，由规则引擎 `RuleEngineScheduler` 评估）                                                                                                                                                                                                                                                                                                                         |
| `warning_event`                                                                                                                                      | 预警事件（六态状态机：待确认/已确认/处理中/已关闭/已忽略/已解除；含 `close_reason` + `closed_by` 审计字段；rule_snapshot 文本快照）                                                                                                                                                                                                                                                                 |
| `emergency_event`                                                                                                                                    | 应急事件（完整 CRUD，关闭时标记 status=已闭环）                                                                                                                                                                                                                                                                                                                                                     |
| `file_attachment`                                                                                                                                    | 附件元数据（storage_path/file_url，对象真实写 MinIO）                                                                                                                                                                                                                                                                                                                                               |
| `risk_score_log`                                                                                                                                     | AI 融合评分原始快照，字段：score(NUMERIC 概率值)、risk_level(AI/规则判定等级)、source(VARCHAR 来源标记：AI_FUSED=AI 融合 / RULE_ONLY=规则等级 AI 降级 / MANUAL=人工录入)、factors(JSONB 融合因子快照)、calculated_at。`RuleEngineScheduler.createWarning` 每次 AI 融合评分后写入，用于后续模型迭代回溯训练集。查询：`SELECT * FROM risk_score_log WHERE hazard_code=? ORDER BY calculated_at DESC;` |
| 按来源过滤：`SELECT * FROM risk_score_log WHERE source='AI_FUSED' ORDER BY calculated_at DESC;` 或 `WHERE source='RULE_ONLY'` 筛选 AI 降级时段的记录 |
| `operation_log`                                                                                                                                      | 统一操作审计日志表（**已落地**）。`OperationLogAspect` AOP 切面自动拦截所有 Controller 层带 `@PreAuthorize` 注解的 POST/PUT/DELETE 请求，记录 operator / module / action / HTTP 方法 / URL / 耗时 / IP。密码等敏感字段在请求体中被自动截断。                                                                                                                                                        |
| `ai_analysis_result`                                                                                                                                 | AI 分析结果（image/forecast/anomaly/fusion，result_json 文本）                                                                                                                                                                                                                                                                                                                                      |

### 8.2 关键索引

```
-- 空间（GiST，EPSG:4326）
idx_hazard_geom   hazard_point USING GIST (geom)
idx_record_geom   inspection_record USING GIST (geom)
idx_device_geom   monitoring_device USING GIST (geom)

-- 常规查询
idx_hazard_region_level   hazard_point(region_code, risk_level)
idx_hazard_type           hazard_point(hazard_type)
idx_task_status_region    inspection_task(status, region_code)
idx_task_hazard           inspection_task(hazard_code)
idx_record_task_hazard    inspection_record(task_code, hazard_code)
idx_device_status_region  monitoring_device(online_status, region_code)
idx_warning_status_level  warning_event(status, warning_level)
idx_warning_hazard        warning_event(hazard_code)
idx_emergency_status      emergency_event(status)
-- 预警→应急 幂等：同一预警最多一个未闭环应急事件（PostgreSQL 部分唯一索引）
idx_emergency_warning_unique  emergency_event(warning_code) WHERE status != '已闭环'
idx_attachment_biz        file_attachment(business_type, business_code)
idx_ai_result_biz         ai_analysis_result(business_type, business_code)
-- 设备数据查询与去重
-- HTTP/MQTT 双通道去重：同一设备+指标+时间戳 唯一
CREATE UNIQUE INDEX IF NOT EXISTS idx_device_metric_unique
    ON device_metric(device_code, metric_code, recorded_at);
-- 替代冗余普通索引 idx_device_metric_code_time，唯一索引自动覆盖查询路径
```

### 8.3 空间字段与查询

- 空间列统一为 `geometry(Point, 4326)`：`hazard_point.geom`、`inspection_record.geom`、`monitoring_device.geom`。
- `geom` 由**应用层**维护（`hazard` / `inspection` 服务在写入时通过 `GeometryFactory`（SRID 4326）从经纬度生成，见 `HazardService.apply` / `InspectionService.submit`），保证入库即带空间坐标；种子数据在 `data.sql` 直接以 `ST_SetSRID(ST_MakePoint(...), 4326)` 写入。
- 视口查询走 PostGIS：`geom && ST_MakeEnvelope(:minLon, :minLat, :maxLon, :maxLat, 4326)`（GiST 索引加速），见 `HazardPointRepository.findInViewport`；`HazardService` 的 page / listByViewport / geojson 均复用该查询。
- 距离查询示例：`ST_DWithin(geom::geography, ..., :radiusMeters)`（`findWithinDistance`）。
- JPA 映射依赖 `hibernate-spatial`（pom 已加入），实体字段类型为 JTS `org.locationtech.jts.geom.Point`。

### 8.4 数据库初始化策略

- **扩展引导**：`infra/postgres/init/001_create_extensions.sql`（docker-entrypoint-initdb.d，首次建卷时执行 `postgis`、`uuid-ossp`）。
- **建表 + 种子**：`backend/src/main/resources/schema.sql` + `data.sql`，经 `spring.sql.init.mode: always` 在每次启动时幂等执行（`CREATE TABLE IF NOT EXISTS` / `ON CONFLICT DO NOTHING` / `ADD COLUMN IF NOT EXISTS`）。
  - 注意：`spring.sql.init` 基于 ScriptUtils 按 `;` 切分、不识别 PL/pgSQL `$$` 块，因此 `schema.sql` 只放纯 DDL。
- **外键补齐**：`config/DatabaseForeignKeyInitializer`（ApplicationRunner）在既有数据卷上幂等补缺失外键（`pg_constraint` 检查 + 单条 DDL）。
- **formula 幂等种子**：无唯一键的表（`file_attachment`）用 `INSERT ... SELECT ... WHERE NOT EXISTS`，避免每次重启重复插入。`risk_score_log` / `operation_log` 为运行时写入表，无种子，无需处理。

### 8.5 种子数据（`data.sql`）

- 3 个角色（admin/attendant/maintenance）、1 城市 3 乡镇、3 个用户（BCrypt 哈希，见配置说明）。
- 5 个灾害点、3 个任务、4 台设备、3 条预警规则、3 条预警事件、1 条应急事件、若干附件与风险日志。
- 业务关联以编码为主：`hazard_code`、`task_code`、`device_code`、`warning_code`、`event_code`，便于前端详情页与附件查询联动；数据库层已建立同名字段的外键并配合理删除策略（级联/置空）。

---

## 9. 权限模型

### 9.1 前端（`src/stores/auth.js`）

- **角色**：`admin`（管理员）、`attendant`（值班员）、`maintenance`（运维员）。
- **角色首页**：admin/attendant → `/`；maintenance → `/devices`。
- **菜单可见性** `ROLE_MENU_MAP`：
  - admin：全部 7 个模块
  - attendant：除「设备」外 6 个模块
  - maintenance：总览/灾害点/设备/分析
- **权限码** `ROLE_PERMISSION_MAP`：形如 `模块:动作`，例如 `hazards:create`、`warnings:close`。
  - admin 拥有全部权限；
  - attendant 无 `hazards:delete`、`tasks:delete`、`devices:*` 等；
  - maintenance 只有查看/导出 + 设备全部操作 + 概览/分析。

### 9.2 前端权限落地

- 路由守卫：按角色可访问路径 + 路由权限码（`routePermissionMap`）。
- 按钮：`PermissionButton`。
- 本地存储 Key：`geosentry_token`、`geosentry_user`。

### 9.3 后端权限

- 数据库层：`sys_role` + `sys_user.role_code` 已预留。
- 当前运行时：JWT 携带 `role` claim；`JwtAuthenticationFilter` 注入 `ROLE_<ROLE>` 角色 authority，并按角色从 `PermissionRegistry` 注入权限码 authority（与前端 `ROLE_PERMISSION_MAP` 一致）。
- 方法级授权已落地：`SecurityConfig` 启用 `@EnableMethodSecurity`，写/删/导出等关键接口标注 `@PreAuthorize`（如 `hasAuthority('hazards:delete')`、`hasRole('ADMIN')`），未授权返回 403，见 `PermissionRegistry` 与各 Controller。

---

## 10. 实时数据通道

### 10.1 数据流

```
数据源（设备/预警/人员）  ──►  WebSocket(可选) / REST 轮询  ──►  realtimeService 状态
                                                                   │
                                                     onRealtimeUpdate(listener) 订阅
                                                                   │
                                                        态势总览页同步到地图/列表
```

### 10.2 使用方式

```js
// 启动（如 OverviewPage.onMounted）
startRealtimeSync({ wsUrl, pollingInterval, personnelInterval, snapshot })
// 订阅
const dispose = onRealtimeUpdate((payload) => { ... })
dispose() // 取消订阅
stopRealtimeSync({ keepListeners: false })
```

### 10.3 值守人员数据源与轨迹

- **数据源**：前端 `PERSONNEL_ROSTER` 常量提供人员元数据（姓名/角色/所属区域/电话/班组/班次），**不依赖设备点位**——设备只关联"监测责任人"，动态值守力量来自当日排班。坐标来源三层叠加：Redis 实时位置（移动端上报）→ DB fallback（巡检签到坐标 + 任务关联灾害点坐标）→ 前端本地轨迹推演（仅演示模式）。
- **演示模式**（默认）：`pollPersonnel()` 从档案生成 8 名人员（含区域/电话/班次），`animatePersonnel()` 每 10s 沿本地轨迹推进，用于态势页演示。
- **生产模式**（`VITE_USE_PERSONNEL_LIVE=true`）：前端轮询 `GET /personnel/positions` 展示实时位置（Redis 优先，DB fallback 兜底），禁用本地轨迹推演。移动端上报已落地：通过 `POST /personnel/positions/report`（需 JWT）每 10~30s 上传 GPS 坐标，后端写入 Redis key `personnel:pos:{userId}`（TTL 120s）并通过 WebSocket 广播给前端。

### 10.4 预警状态机与应急联动

```
待确认 ──confirm──► 已确认 ──dispatch──► 处理中 ──close(reason)──► 已关闭 / 已忽略 / 已解除
                      │                         ▲
                      └──dismiss──► 已忽略       └──resolve──► 已解除
```

- 后端已实现六态流转（`WarningService.confirm/dispatch/close/dismiss/resolve`）：
  - `confirm`：`待确认 → 已确认`，落 `confirmed_at`
  - `dispatch`：`已确认 → 处理中`，落 `dispatched_at`
  - `close(id, reason, operator)`：**允许从任何非终态流转**（包括待确认直接关闭/忽略/解除，无需先走 confirm→dispatch）。按 reason 语义分流——reason="误报" → `已忽略`，reason="自动解除" → `已解除`，其他 → `已关闭`；落 `closed_at` + `close_reason` + `closed_by`
  - `dismiss(id, operator)`：`处理中/已确认 → 已忽略`，固定 reason="误报"
  - `resolve(id, operator)`：`处理中/已确认 → 已解除`，固定 reason="自动解除"
- `warning_event` 表新增 `close_reason VARCHAR(255)` 与 `closed_by VARCHAR(64)` 两列（`schema.sql` 已加 `ADD COLUMN IF NOT EXISTS`），关闭/忽略/解除均记录原因与操作人，支撑审计与统计。
- 前端 `dashboardApi` 已同步实现 `dismissWarning` / `resolveWarning` / `closeWarning(payload.reason)`，调用后端专用端点；Analytics 页 mock fallback 与后端真实响应结构对齐。
- 「派发时按等级联动生成应急事件」已实现：`WarningService.dispatch` 检测到橙色/红色预警时自动调用 `EmergencyService.createFromWarning` 创建应急事件。**幂等机制三层防御**：(1) 应用层快速路径 `existsByWarningCodeAndStatusNot` 检查；(2) 数据库部分唯一索引 `CREATE UNIQUE INDEX ... ON emergency_event(warning_code) WHERE status != '已闭环'`（PostgreSQL）强制同一预警最多一个未闭环事件；(3) 异常兜底 `catch DataIntegrityViolationException` 处理并发竞态，返回 null 表示"已存在无需新建"。

### 10.5 预警规则引擎

`warning_rule` + `RuleEngineScheduler`（`@Scheduled`，默认每 30s，可用 `RULE_ENGINE_CRON` 调整）实现「自动触发 → 去重 → 可选 AI 融合」：

1. 拉取 `warning_rule` 中 `enabled=true` 的规则。
2. 拉取各设备**最新**指标（`device_metric`，来自设备上报链路）。
3. 按 `hazard_code` 归并指标快照，`RuleEvaluator` 解析表达式求值：
   - 简单形式：`"disp_rate > 5"`
   - JSON 结构化（规划）：`{"operator":"AND","conditions":[{"metric":"..","comparator":">","value":..}]}`

> **当前限制**：规则引擎目前仅支持简单比较表达式（如 `disp_rate > 5`、`rainfall >= 50.0`），
> 由 Spring Expression Language 解析。JSON 结构化表达式（支持 AND/OR 组合、阈值范围、指标组合）
> 已列为规划项，预计 v2 版本引入（表达式 AST 解析器）。
> 当前建议通过多条规则组合（rule-engine-cron 每 30s 轮询）实现复杂条件。

4. **去重**：同一 `(rule_code, hazard_code)` 已有未闭环预警时不再重复触发（通过 `WarningEventRepository.existsByRuleCodeAndHazardCodeAndStatusNot` 数据库索引查询，已补 `idx_warning_status_level` 覆盖）。
5. 命中后生成预警（初始状态 **待确认**）；若 AI 服务启用，先调 `fusionRisk` 校验并可能升级等级，同时落 `risk_score_log` 记录因子快照。AI 不可用时降级沿用规则等级。**AI/规则输出均不自动派发，强制人工复核**。

表达式求值与结构见 `RuleEvaluator`、`RuleEngineScheduler`。开关：`geosentry.warning.rule-engine-enabled`（默认 true）。

---

## 11. 配置与环境变量

### 11.1 前端（`.env` / `.env.example`）

| 变量                           | 默认                                | 说明                                     |
| ------------------------------ | ----------------------------------- | ---------------------------------------- |
| `VITE_API_BASE_URL`            | `http://localhost:8081/api`         | 后端基址（宿主机映射端口）               |
| `VITE_WS_URL`                  | —                                   | WebSocket 地址（实时）                   |
| `VITE_REALTIME_POLL_INTERVAL`  | 15000                               | 设备/汇总基础轮询兜底(ms)                |
| `VITE_WARNING_POLL_INTERVAL`   | 10000                               | 预警核心数据高频轮询(ms)                 |
| `VITE_PERSONNEL_POLL_INTERVAL` | 30000                               | 值守人员位置轮询(ms)                     |
| `VITE_USE_PERSONNEL_LIVE`      | `false`                             | 生产模式：人员坐标由移动端上报后端       |
| `VITE_TDT_TOKEN`               | —                                   | 天地图 token                             |
| `VITE_TDT_ATTRIBUTION`         | 地图数据                            | 地图版权                                 |
| `VITE_MAP_TILE_URL`            | OSM                                 | 矢量底图兜底                             |
| `VITE_SATELLITE_TILE_URL`      | ArcGIS                              | 影像兜底                                 |
| `VITE_CESIUM_ION_TOKEN`        | —                                   | Cesium Ion token                         |
| `VITE_WEATHER_API_KEY`         | —                                   | 天气 API Key                             |
| `VITE_WEATHER_API_URL`         | OpenWeatherMap                      | 天气接口地址                             |
| `VITE_USE_BACKEND`             | 自动探测 / 可显式设为 true 或 false | 真实后端模式控制，默认启动时探测健康状态 |

### 11.2 后端（`application*.yml`，支持环境变量覆盖）

| 变量                                    | 默认                                    | 说明                                                                                                           |
| --------------------------------------- | --------------------------------------- | -------------------------------------------------------------------------------------------------------------- |
| `SERVER_PORT`                           | 8080                                    | 后端监听端口（容器内）；Docker 时映射到宿主机 8081                                                             |
| `FRONTEND_ORIGIN`                       | http://localhost:5173                   | CORS 允许来源                                                                                                  |
| `JWT_SECRET`                            | 必须 ≥ 32 字符强随机                    | JWT 签名密钥。长度不足或匹配弱密码时应用启动失败。生成：`openssl rand -base64 48`                              |
| `JWT_EXPIRE`                            | PT2H                                    | 过期时长（ISO-8601 Duration）                                                                                  |
| `JWT_ISSUER`                            | geosentry                               | JWT 签发者                                                                                                     |
| `DEV_USERS_ENABLED`                     | true（dev）/ false（prod 强制）         | 是否启用硬编码账号 fallback。prod ProductionConfigValidator 会拦截 true                                        |
| `DEVICE_AUTH_STRICT`                    | false（dev）/ true（prod 强制）         | 设备上报严格模式（未登记 api_key 的请求被拒）。prod 强制 true                                                  |
| `EMQX_USERNAME` / `EMQX_PASSWORD`       | 空（prod 需显式设置）                   | EMQX broker 侧认证凭据（需与 infra/emqx/init.sh 创建的用户一致）。prod 空值会被 ProductionConfigValidator 拦截 |
| `DEVICE_OFFLINE_THRESHOLD_MIN`          | 5                                       | 设备离线判定阈值（分钟），`DeviceOnlineChecker` 每 60s 扫描 `last_report_at` 是否超过此阈值                    |
| `POSTGRES_*`                            | localhost/5432/geosentry                | 数据库连接                                                                                                     |
| `REDIS_*`                               | localhost/6379/redis123                 | Redis                                                                                                          |
| `FILE_BASE_URL`                         | http://localhost:9000                   | 附件基础 URL                                                                                                   |
| `MINIO_ENDPOINT`                        | http://localhost:19000                  | MinIO 服务地址（Docker 内 http://minio:9000）                                                                  |
| `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` | minioadmin/minioadmin123（dev）         | MinIO 凭据。prod 需显式设置为非弱密码值（长度 ≥ 16）                                                           |
| `MINIO_BUCKET`                          | geosentry                               | 附件对象存储桶                                                                                                 |
| `EMQX_*`                                | 见编排                                  | MQTT                                                                                                           |
| `TDENGINE_URL`                          | jdbc:TAOS-RS://localhost:6041/geohazard | TDengine JDBC URL                                                                                              |
| `TDENGINE_USERNAME`                     | root                                    | TDengine 用户名                                                                                                |
| `TDENGINE_PASSWORD`                     | taosdata                                | TDengine 密码                                                                                                  |
| `TDENGINE_ENABLED`                      | false                                   | 是否启用 TDengine 时序写入。需同时将 `taos-jdbcdriver-3.3.0.jar` 放入后端 classpath                            |
| `WS_REQUIRE_TICKET`                     | false（dev）/ true（prod）              | 强制 WebSocket 连接只能使用一次性 ticket（禁止 raw JWT query）。prod 推荐开启                                  |
| `AI_SERVICE_BASE_URL`                   | http://localhost:8000/api/ai            | AI 推理服务地址（Docker 内 http://ai-service:8000/api/ai）                                                     |
| `AI_ENABLED`                            | true                                    | AI 功能总开关；关闭后降级为纯规则模式                                                                          |
| `AI_TIMEOUT_MS`                         | 30000                                   | AI 调用超时（毫秒）                                                                                            |
| `AI_SCHEDULER_ENABLED`                  | false                                   | 定时批量预测开关（默认关闭）                                                                                   |
| `AI_FORECAST_HORIZON`                   | 7                                       | 预测未来 N 天                                                                                                  |
| `AI_FORECAST_CRON`                      | `0 0 2 * * *`                           | 定时预测 cron 表达式                                                                                           |
| `RULE_ENGINE_ENABLED`                   | true                                    | 预警规则引擎开关                                                                                               |
| `RULE_ENGINE_CRON`                      | `*/30 * * * * *`                        | 规则引擎评估 cron                                                                                              |
| `TZ`                                    | Asia/Shanghai                           | 时区                                                                                                           |

> 配置文件选择：默认 `application.yml`；另提供 `application-dev.yml`（显示 SQL、`cn.geosentry` 调试日志）、`application-prod.yml`（关闭 SQL、禁用 SQL init、WARN 日志）与 `application-docker.yml`（容器网络下连接 `postgres/redis/minio/emqx/tdengine`）。docker-compose 中后端设置 `SPRING_PROFILES_ACTIVE=docker` 时应使用该 profile，避免回退到 `localhost`。

---

## 12. Docker 部署

`docker-compose.yml` 编排服务：postgres、redis、minio、emqx、tdengine、backend、frontend（端口见 README）。

```bash
docker compose up -d            # 启动全部
docker compose ps               # 查看状态
docker compose logs backend     # 查看后端日志
docker compose down             # 停止
```

Volumes：`postgres_data`、`redis_data`、`minio_data`、`emqx_data/log`、`tdengine_data/log`、`m2_cache`、`node_modules`。

网络：`geosentry-net`。

PostGIS 初始化：`infra/postgres/init/001_create_extensions.sql`（首次建卷时由 docker-entrypoint-initdb.d 启用 `postgis`、`uuid-ossp`；新卷还必须至少启动一次后端，由 `spring.sql.init` 完成建表与种子数据初始化）。若希望重建数据库：

```bash
docker compose down -v            # 删除数据卷（含 m2_cache/node_modules，会触发重新构建）
docker compose up -d              # 重新初始化
```

### 生产模式 docker-compose 覆盖文件

项目根目录提供了 `docker-compose.prod.yml`，与 `docker-compose.yml` 配合使用时覆盖 backend 和 frontend 服务为生产模式：

| 项目                   | docker-compose.yml（dev）            | docker-compose.prod.yml（覆盖）                                          |
| ---------------------- | ------------------------------------ | ------------------------------------------------------------------------ |
| backend 镜像           | maven:...-temurin-17（源码挂载运行） | Dockerfile.backend 多阶段构建 jar 镜像                                   |
| SPRING_PROFILES_ACTIVE | docker                               | **prod**（触发 ProductionConfigValidator）                               |
| 安全默认值             | DEV_USERS_ENABLED=true               | DEV_USERS_ENABLED=false, DEVICE_AUTH_STRICT=true, WS_REQUIRE_TICKET=true |
| 前端构建               | 无 mock 拦截                         | VITE_USE_BACKEND!=true 时构建失败                                        |
| 健康检查               | 无                                   | backend + frontend 均有 healthcheck                                      |
| 资源限制               | 无                                   | 2 CPU / 1GB 内存上限                                                     |

启动方式：

```bash
# 日常启动（首次部署除外）
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build

# 首次部署：先手动初始化 PostgreSQL（详见 §14 第 43 条），再启动全栈
docker compose -f docker-compose.yml up -d postgres
# ... 执行 schema.sql / data.sql / 修改种子密码 ...
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
```

`docker-compose.prod.yml` 关键配置片段：

```yaml
services:
  backend:
    build:
      context: .
      dockerfile: Dockerfile.backend
    environment:
      SPRING_PROFILES_ACTIVE: prod
      DEV_USERS_ENABLED: "false"
      DEVICE_AUTH_STRICT: "true"
      WS_REQUIRE_TICKET: "true"
    depends_on:
      postgres:
        condition: service_healthy # 等待 PG 健康（pg_isready）再启动
      redis:
        condition: service_healthy
      minio:
        condition: service_healthy
      emqx:
        condition: service_healthy
    healthcheck:
      # Dockerfile.backend 已 apk add curl，确保镜像内可用
      test: ["CMD", "curl", "-sf", "http://localhost:8080/api/system/health"]
      interval: 30s
      timeout: 5s
      retries: 5
      start_period: 60s
    deploy:
      resources:
        limits:
          cpus: "2"
          memory: 1G
    restart: unless-stopped
```

> **首次部署特殊说明**：`depends_on: service_healthy` 只确保 PostgreSQL 容器启动且 `pg_isready` 通过，不确保业务表已建完。生产首次部署必须先按 §14 第 43 条步骤 1–5 手动执行 schema.sql + data.sql + 修改种子密码，再启动全栈。后续重启直接用 `docker compose up -d` 即可。
>
> **healthcheck 与 restart 关系**：`healthcheck` 不健康**不会**触发 `restart: unless-stopped`（这是 Docker 设计）。健康检查持续失败时容器保持运行但标记为 `unhealthy`，需要通过外部监控告警。如果希望 unhealthy 自动重启，改用 `restart: on-failure` 或外部编排（如 k8s liveness probe + restartPolicy）。

---

## 13. 二次开发指南

### 13.1 新增一个业务页面

1. 在 `src/views/` 新建 `XxxPage.vue`。
2. 在 `src/router/index.js` 注册路由并加入 `routePermissionMap`。
3. 在 `src/stores/auth.js` 的 `ROLE_MENU_MAP` 与 `ROLE_PERMISSION_MAP` 补充菜单与权限码。
4. 在 `src/data/dashboardData.js` 的 `dashboardMenu` / `moduleMeta` 补充入口与标题。
5. 如需后端数据，在 `dashboardApi` 增加方法（真实 + 模拟双分支）。

### 13.2 新增后端接口

1. 新增 DTO（record）+ 校验注解。
2. 新增 Entity（若需要持久化）。
3. Repository 继承 `JpaRepository` / `JpaSpecificationExecutor`。
4. Service 编写业务逻辑（`@Service` + `@Transactional`）。
5. Controller 返回 `ApiResponse.success(...)`。
6. 用 Swagger UI（`/swagger-ui.html`）自测。

### 13.3 接入真实后端（关键约定）

前端统一假设后端返回：

```json
{ "code": 0, "message": "success", "data": { ... }, "timestamp": 1720000000000 }
```

分页数据建议统一返回 `records` 形状：

```json
{ "records": [...], "total": 10, "page": 1, "size": 10, "pages": 1 }
```

如果后端仍存在历史兼容输出，请尽快收敛到单一结构，减少前端兼容分支。

鉴权：除登录/健康/文档外均需 `Authorization: Bearer <token>`。

### 13.4 导出功能

已落地：后端用 **EasyExcel**（`cn.geosentry.common.export.ExcelExportService` + `*ExportRow` DTO）将业务数据生成 xlsx 二进制流返回，并设置 `Content-Disposition` 下载响应头。四个导出接口（灾害点 / 巡检任务 / 监测设备 / 预警事件）均走此链路，见 `ExcelExportService.export`。文件命名使用 URL 编码（`filename*=UTF-8''`）以支持中文文件名。扩容新导出：新增 `*ExportRow`（`@ExcelProperty`）+ 对应 Service 的 `exportRows()`。

### 13.5 附件（MinIO 对象存储（已落地））

已实现：`AttachmentService.upload` 通过 `MinioClient.putObject()` 把 multipart 文件流真实写入 MinIO（bucket 见 `MINIO_BUCKET`，默认 `geosentry`），成功后可返回对象访问 URL；`delete` 同步删除 `removeObject`，避免存储泄漏。`storage_path` / `file_url` 字段保存实际对象路径与访问地址。配置见 `minio.endpoint / .access-key / .secret-key / .bucket`。

---

## 14. 常见问题与注意事项

1. **前端后端模式**：确认 `VITE_USE_BACKEND` 与后端健康状态，避免接口误判。
2. **JWT 密钥（强制校验）**：`JWT_SECRET` 必须不少于 32 字符且不在弱密钥黑名单（change-me / secret / default-secret 等）。**不满足则应用启动失败**（`IllegalStateException`）。生成方式：`openssl rand -base64 48`。
3. **CORS**：生产环境不要保留过宽白名单，跨域与鉴权失败时优先检查 `FRONTEND_ORIGIN`、`VITE_API_BASE_URL` 与登录态。
4. **用户认证模式**：`DEV_USERS_ENABLED=true`（默认）时 DB 未命中会 fallback 到内存账号（admin/attendant/maintenance，密码 `123456`）。生产环境务必设为 `false`，强制走 `sys_user` 表的 BCrypt 密码哈希。
5. **设备上报严格模式**：`DEVICE_AUTH_STRICT=false`（默认）允许未登记 `api_key` 的设备上报（WARN 提示）。生产环境设为 `true`，未登记密钥的设备直接 403。
6. **EMQX 鉴权检查**：`EMQX_ENABLED=true` 但未配置 `EMQX_USERNAME`/`EMQX_PASSWORD` 时，启动阶段会输出大段 WARN，提醒 broker 侧无认证，任何客户端可伪造设备数据。生产必须同时配置两端。
7. **应用配置文件**：docker-compose 使用 `SPRING_PROFILES_ACTIVE=docker` 时应提供并启用 `application-docker.yml`，避免回退到本地地址。
8. **地图 Token**：天地图 / Cesium Ion / 天气 API 均需要单独申请并配置；缺失时自动回退到 OSM/ArcGIS/内置数据。
9. **空间字段**：`hazard_point.geom` 等空间列依赖 PostGIS 扩展（`infra/postgres/init` 或 `schema.sql` 均会 `CREATE EXTENSION IF NOT EXISTS postgis`）；`geom` 由应用层生成，视口查询走 `ST_MakeEnvelope` + GiST 索引。
10. **已落地项（持续闭环）**：导出（EasyExcel 真实 xlsx）、附件 MinIO 真实写入、方法级授权（`@PreAuthorize`）、设备数据上报链路、预警规则引擎、仪表盘真实聚合、**预警状态机补全**（六态）、**橙/红预警派发自动联动生成应急事件**（三层幂等防御：exists 检查 + PG 部分唯一索引 + DataIntegrityViolationException 兜底）、**值守人员完整链路**（Redis 实时缓存 + `POST /positions/report` + WebSocket 广播）、**WebSocket 实时推送 + 一次性 ticket 鉴权**（前端自动先调 `GET /api/auth/ws-ticket`，失败 fallback raw JWT；WS URL 未配置时从 `VITE_API_BASE_URL` 自动推导 http→ws、/api→/ws）、**EMQX MQTT 桥接 + 启动鉴权预检 + broker 侧认证初始化**（`EmqxSecurityPreflight` 预检 + `infra/emqx/init.sh` 首次启动创建 MQTT 认证用户）、**TDengine 接入主链路**、**设备数据上报鉴权 + 严格模式**、**设备离线自动心跳**（`DeviceOnlineChecker` 每 60s 扫描 `last_report_at` 超 5min 的在线设备置离线并广播）、**JWT 弱密钥 fail-fast**（启动时校验 ≥32 字符 + 精确黑名单 + 正则强度校验（纯数字/纯字母/全同字符），失败应用启动崩溃）、**DB 用户认证替代内存 fallback**、**登录密码真实验证**、**后端 AI 全链路**、**前端 AI 展示**、**前端 Docker 生产部署**（多阶段构建：Vite 构建产物 → nginx 反向代理 `/api` 和 `/ws`，同域部署无需 CORS）、**分页结构统一**（废弃 `PagedResponse`，所有接口统一返回 `PageResponse{records,total,page,size,pages}` 五字段）、**normalizePaged 前端兼容分支清理**（已删除 `list` 字段兼容逻辑）、**EMQX entrypoint 自动初始化**（broker 首次启动自动执行 `init.sh` 创建 MQTT 认证用户）、**设备数据双通道去重**（PG 唯一索引 + catch DataIntegrityViolationException）、**预警规则引擎去重优化**（仓储方法索引查询替代全表扫描）、**附件 URL 自动刷新**（每次详情访问生成新 presigned URL + 显式 refresh-url 端点）、**规则引擎 AI 融合落 risk_score_log**（因子快照 JSONB，用于模型迭代回溯）、**AI 预警强制人工复核**（AI/规则输出均生成"待确认"状态预警，不自动派发）、**OperationLogAspect 审计**（AOP 自动记录所有写操作）、**MinIO 弱密钥 fail-fast**（prod 模式下检测默认凭证阻断启动）、**WebSocket require-ticket 强制模式**（prod 推荐禁用 raw JWT fallback）、ProductionConfigValidator（prod 下 7 项安全配置 fail-fast）、EMQX init.sh 3 次重试 + entrypoint 失败阻断、Dockerfile.backend TDengine JDBC 自动下载、Dockerfile.frontend mock 模式阻断、POST /api/auth/change-password 端点补全、种子用户密码 BCrypt hash 校验、应用配置 prod profile 强制安全默认值、健康检查端点默认检测 PostgreSQL（SELECT 1）、索引冗余清理（idx_device_metric_code_time 已删除，唯一索引覆盖查询）、DeviceDataService TDengine 3 次指数退避重试、risk_score_log 补充 source 列（AI_FUSED / RULE_ONLY）。**剩余缺口**：**真实 AI 模型文件**（需放入 `ai-service/models/`，训练脚本已就绪，可用合成数据快速演示）、EMQX broker TLS 证书（broker 自身配置，代码已预留 username/password 鉴权）、TDengine JDBC 驱动手动部署（仅**宿主机直跑后端**时需要手动放入 classpath；Docker 部署已由 `Dockerfile.backend` 多阶段构建自动从 Maven Central 下载到 `/app/optional-lib/` 并通过 `JarLauncher` 加载）、数据库迁移工具引入（当前依赖 spring.sql.init 幂等执行，复杂变更建议后续引入 Flyway）。生产环境首次部署需手动执行 schema.sql + data.sql（prod 禁用 spring.sql.init），详见 §14 第 43 条。
11. **schema.sql 限制**：`spring.sql.init` 无法解析 PL/pgSQL `$$` 块（ScriptUtils 按分号切分），因此触发器/函数不要放进 `schema.sql`；既卷外键补齐统一走 `DatabaseForeignKeyInitializer`。
12. **Java 记录/命名**：后端大量使用 `record`（DTO），修改字段后注意前端字段名一致性（驼峰 vs 下划线）。
13. **AI 服务**：独立 `ai-service`（FastAPI，端口 8000）。模型/依赖缺失时服务仍可启动并返回 mock 结果；后端 `AI_ENABLED=false` 或 AI 服务不可用时自动降级为纯规则模式。部署前需能拉取 `python:3.11-slim` 基镜像与推理依赖（onnxruntime/torch/xgboost 体积较大）。
14. **AI 人工复核**：AI 输出（detect/forecast/anomaly/fusion）定位为辅助决策，必须人工复核后才进入正式预警流程；结果持久化于 `ai_analysis_result` 便于审计。
15. **方法级权限**：`SecurityConfig` 已启用 `@EnableMethodSecurity`，`JwtAuthenticationFilter` 从 `PermissionRegistry` 按角色注入权限码 authority。写/删/导出等关键接口标注了 `@PreAuthorize`；权限码与前端 `ROLE_PERMISSION_MAP` 一致。未带对应权限的调用返回 403。
16. **预警规则引擎**：`RuleEngineScheduler` 默认每 30s 评估。规则命中但 AI 服务未部署时，日志会出现 `AI 融合评分不可用，沿用规则等级` 的 WARN（属预期降级，不影响触发）。同 `(rule,hazard)` 未闭环前不重复触发。
17. **设备上报鉴权**：`POST /api/devices/{code}/data` 由 `DeviceAuthFilter` 拦截，要求请求头 `X-Device-Key`；过滤器从 `monitoring_device.api_key` 列校验。设备未登记 api_key 时允许上报（WARN 日志提示，便于迁移）；EMQX MQTT 链路的鉴权由 broker 自身配置（`emqx.username`/`emqx.password`）处理，代码中已预留用户名密码参数。
18. **WebSocket 鉴权**：`/ws` 端点通过 `JwtHandshakeInterceptor` 校验握手请求。**推荐方式**：前端先调 `GET /api/auth/ws-ticket`（需 JWT）获取 60s 一次性 ticket，再用 `ws://host:port/ws?ticket=${ticket}` 连接——ticket 一次性消费，反向代理日志里只看到随机 token。**兼容方式**：`ws://host:port/ws?token=${authToken}`（直接把 JWT 放 query，浏览器 WebSocket API 不支持自定义 Header）。前端 `realtimeService.js` 已实现：**优先 ticket，失败 fallback raw JWT**（重连时重新取 ticket，处理 token 刷新）。WS URL 未配置时自动从 `VITE_API_BASE_URL` 推导 http→ws、/api→/ws。
19. **人员位置上报鉴权**：`POST /api/personnel/positions/report` 已从 permitAll 收口为 authenticated，需携带有效 JWT。移动端可复用 Web 端签发的 token（`/api/auth/login` 返回），建议设置较长过期时间（如 24h）。
20. **schema 文本列**：`device_metric.raw_payload`、`warning_event.rule_snapshot`、`ai_analysis_result.result_json` 均以 `TEXT` 存储（实体映射 String）；若改回 `JSONB` 需同步调整实体类型或加 `::jsonb` 转换，避免 `column is of type jsonb but expression is of type character varying` 报错。
21. **种子数据明文密码**：`data.sql` 预置三个演示用户（admin/attendant/maintenance），BCrypt 哈希对应明文密码均为 `123456`。生产环境**两种做法**：(a) 执行 data.sql 后、启动 backend 前用 SQL UPDATE 改密码（见 §14 #43 步骤 5）；(b) 跳过 data.sql 注入，启动后用 SQL INSERT 创建管理员（此时 sys_user 为空，ProductionConfigValidator 旁路放行）。
22. **geom 字段维护**：`hazard_point.geom` / `monitoring_device.geom` 等空间列**仅由应用层通过 GeometryFactory 自动生成**——写入时同时更新经度/纬度/geom 三列。**禁止直接用 SQL 修改经纬度而不更新 geom**（空间索引查询会返回错误结果）。如果必须绕过应用层运维，请执行 `UPDATE ... SET geom = ST_SetSRID(ST_MakePoint(longitude, latitude), 4326) WHERE ...`。
23. **MinIO presigned URL 过期**：附件上传返回的 presigned URL 有效期 7 天（MinIO 默认），过期后前端需重新请求。`/api/attachments/{code}` 接口会检查过期并返回 410 Gone + 新签名 URL 响应头（`X-New-Url`）。生产部署建议设置 CDN 反向代理 + 自定义域名，避免直接暴露 MinIO 端口。
24. **EMQX broker 侧认证配置**：`docker-compose.yml` 编排了 EMQX 5.x 并挂载 `infra/emqx/` 目录作为配置。首次启动时执行 `infra/emqx/init.sh` 会创建 Built-in Database 认证器 + `geosentry_backend` MQTT 用户（BCrypt 密码来自 `EMQX_PASSWORD`）。**如果跳过此初始化步骤**（如手动 docker-compose up 未挂载目录），broker 侧无认证，任何客户端可连接。验证方式：访问 `http://localhost:28083`（Dashboard）→ 认证 → Users，应能看到 `geosentry_backend` 用户。
25. **PostgreSQL 初始化顺序**：`docker-compose.yml` 已配置 postgres 健康检查（`pg_isready`）+ backend `depends_on: condition: service_healthy`，避免首次启动后端连不上数据库。但如果 postgres 容器重建后丢失数据卷，`docker-entrypoint-initdb.d` 只在**空数据卷**时执行，已存在数据不会重跑。此时需手动删除数据卷 `docker volume rm geosentry_postgres_data` 再重建。
26. **Docker 前端部署**：前端已改为多阶段构建（`Dockerfile.frontend` + `infra/nginx/frontend.conf`），不再使用 `npm run dev`。构建时环境变量作为 build args 注入：`VITE_API_BASE_URL=/api`、`VITE_WS_URL=/ws`（同域部署，由 nginx 反向代理到 backend）。如果需要开发调试，可单独启动前端 `npm run dev`（5173 端口），docker-compose 的 frontend 服务不会冲突（只映射 backend）。
27. **DeviceAuthFilter 绕过 JWT 的原理**：`/api/devices/{deviceCode}/data` 路径不在 SecurityConfig 的 permitAll 列表里，但 DeviceAuthFilter 先于 JwtAuthenticationFilter 执行（`addFilterBefore(deviceAuthFilter, JwtAuthenticationFilter.class)`），校验通过后注入 `ROLE_DEVICE` 权限的 Authentication 到 SecurityContext。后续 JwtAuthenticationFilter 读取 SecurityContext 已存在 Authentication 就跳过。**注意**：如果 JwtAuthenticationFilter 的 `shouldNotFilter` 不对 device data 路径放行，可能覆盖 DeviceAuthFilter 的认证——当前配置已正确处理。
28. **EMQX broker 认证自动初始化**：docker-compose 已通过自定义 entrypoint（`infra/emqx/emqx-entrypoint.sh`）在 EMQX 启动后自动执行 `init.sh`，创建 Built-in Database 认证器 + `geosentry_backend` MQTT 用户（BCrypt 密码来自 `EMQX_PASSWORD`）。无需手动执行。验证：`http://localhost:28083` → Users。
29. **设备数据双通道去重**：HTTP 与 MQTT 上报均走 `DeviceDataService.ingest`，PostgreSQL 有唯一索引 `idx_device_metric_unique(device_code, metric_code, recorded_at)` 防止重复样本。重复时 catch `DataIntegrityViolationException` 并跳过（DEBUG 日志），不影响主流程。
30. **附件 presigned URL 自动刷新**：MinIO presigned URL 有效期 7 天。后端 `GET /api/attachments/{id}` 每次访问自动生成新 URL 并更新 DB；前端也可调用 `POST /api/attachments/{id}/refresh-url` 强制刷新。建议前端检测到图片 403 时自动调用刷新。
31. **操作审计日志**：`OperationLogAspect`（AOP）自动拦截所有带 `@PreAuthorize` 注解的 Controller 写方法（POST/PUT/DELETE），记录 operator / module / action / HTTP 方法 / URL / 耗时 / IP 到 `operation_log` 表。密码等敏感字段自动截断。审计失败不影响主流程。
32. **预警规则引擎性能**：`RuleEngineScheduler.hasOpenWarning` 已从全表扫描改为 `WarningEventRepository.existsByRuleCodeAndHazardCodeAndStatusNot` 数据库索引查询，避免设备量增大时性能下降。
33. **ProductionConfigValidator 作用**：`@Profile("prod")` 组件，启动时自动校验 7 项安全配置（DEV_USERS_ENABLED、DEVICE_AUTH_STRICT、WS_REQUIRE_TICKET、EMQX 凭据、FRONTEND_ORIGIN、种子用户密码、MINIO 密钥强度）。**7 项全部 fail-fast：命中即抛 `IllegalStateException` 阻断启动**，与其他安全配置策略一致。**唯一旁路**：`sys_user` 表为空（未执行 data.sql、无任何用户记录）时跳过种子密码检查，允许首次部署启动后通过 API 或 SQL 创建管理员。dev profile 下不加载，避免本地开发被拦截。
34. **种子用户密码重置**：data.sql 中三个种子用户密码均为 BCrypt("123456")。prod profile 禁用 SQL init，首次部署也不自动执行 data.sql，因此**统一采用 SQL UPDATE 方案**：
    - **首次部署**：执行 data.sql 注入种子数据后、启动 backend **之前**，用 htpasswd 生成新 BCrypt hash + `UPDATE sys_user`（详见下方 §14 第 43 条步骤 5）。否则 ProductionConfigValidator 检测到默认密码会阻断启动；
    - **已运行系统**：让用户登录后调用 `POST /api/auth/change-password` 修改，或 DBA 直接执行 `UPDATE sys_user SET password_hash = '<BCryptHash>' WHERE username = 'admin'`。

    > 注意：修改 data.sql 中的 BCrypt hash 对 prod 无意义（prod 不自动执行 data.sql），仅适用于 dev profile。

35. **EMQX init.sh 失败处理**：broker 认证初始化脚本内置 3 次重试（每次间隔 5s）。全部失败后 entrypoint 返回非零退出码，`docker restart unless-stopped` 自动重启容器重试。避免 broker 在无认证状态下持续运行。

36. **Docker 镜像构建**：后端使用 `Dockerfile.backend` 多阶段构建，自动从 Maven Central 下载 `taos-jdbcdriver:3.3.0` 放入 `/app/optional-lib/`，通过 `java -cp` 加载（Spring Boot JarLauncher 机制）。前端 `Dockerfile.frontend` 强制校验 `VITE_USE_BACKEND=true`，否则构建失败。

37. **operation_log 归档策略**：归档脚本 `infra/postgres/init/003_archive_operation_log.sql` 已存在，包含 `operation_log_archive` 归档表 DDL 与 `archive_operation_logs(retention_days)` 归档函数。生产环境建议每日凌晨 3:00 执行（通过 pg_cron 或外部调度器），默认保留 90 天在线数据。归档表在首次执行时由 `CREATE TABLE IF NOT EXISTS` 自动创建，结构与 `operation_log` 完全一致（无索引减少归档写入开销）。

38. **AI 降级审计追踪**：规则引擎在 AI 服务不可用时降级沿用规则等级，同时在 risk_score_log 中写入 `source='RULE_ONLY'` 标记，便于后续评估 AI 融合覆盖率。

39. **geom 字段数据库触发器规划**：当前 geom 完全由应用层维护（EntityListener）。直接 SQL 修改 longitude/latitude 而不更新 geom 会导致空间索引返回错误结果。建议 v2 版本引入 BEFORE UPDATE/INSERT 触发器强制同步。当前运维规范：禁止直接 SQL 修改经纬度列。

40. **DeviceDataService 双写一致性**：先写 PostgreSQL（事务内），再 try-catch 写 TDengine。PG 写入成功但 TDengine 失败时，PG 数据已持久化但 TDengine 缺样本。长期运行可能出现时序数据与关系库不一致。建议 v2 版本引入补偿队列（Redis Stream）处理 TDengine 写入失败。

41. **设备数据去重保留先到者**：HTTP/MQTT 上报均走 `DeviceDataService.ingest`，PostgreSQL 唯一索引 `idx_device_metric_unique(device_code, metric_code, recorded_at)` 防止重复样本。重复时 catch `DataIntegrityViolationException` 并跳过（DEBUG 日志），不影响主流程。

    ⚠️ **精度限制**：`recorded_at` 类型为 `TIMESTAMPTZ`，精度**截断到秒**。位移计、雨量计等高于 1Hz 的传感器，同一秒内的多个不同样本会被误判为重复而丢弃。

    **规避措施（当前版本）**：
    - 在数据接入层做**聚合**：将高频传感器数据降采样到 1Hz（取均值或首尾值）；
    - 不要将位移计等高频设备的原始上报端直接接入，可通过边缘网关聚合后再上报。

    **v2 规划**：引入 `recorded_at TIMESTAMPTZ(3)`（毫秒精度）或增加服务端接收时间戳 `received_at` 作为辅助去重字段，支持亚秒级精度。

42. **TDengine 驱动在 Docker 中的加载**：`Dockerfile.backend` **构建阶段**尝试从 Maven Central 下载 `taos-jdbcdriver:3.3.0` 放入 `/app/optional-lib/`，通过 `java -cp /app/app.jar:/app/optional-lib/* org.springframework.boot.loader.launch.JarLauncher` 加载。若 Maven Central 不可达，**Docker 镜像构建会中断**（非运行时才报错），需提前将驱动放入本地 Maven 仓库（`mvn install:install-file -Dfile=taos-jdbcdriver-3.3.0.jar -DgroupId=com.taosdata.jdbc -DartifactId=taos-jdbcdriver -Dversion=3.3.0 -Dpackaging=jar`）。

43. **生产环境数据库初始化**：prod profile 禁用 `spring.sql.init.mode=never`，首次部署时不会自动建表。初始化步骤：

    ```bash
    # ====== 步骤 1：启动空 PostGIS ======
    docker compose -f docker-compose.yml up -d postgres

    # ====== 步骤 2：启用 PostGIS 扩展 ======
    docker compose exec -T postgres psql -U geosentry -d geosentry \
      < infra/postgres/init/001_create_extensions.sql

    # ====== 步骤 3：建表（幂等，可重跑） ======
    docker compose exec -T postgres psql -U geosentry -d geosentry \
      < backend/src/main/resources/schema.sql

    # ====== 步骤 4：注入种子数据 ======
    docker compose exec -T postgres psql -U geosentry -d geosentry \
      < backend/src/main/resources/data.sql

    # ====== 步骤 5：强制修改种子密码 ======
    # （否则 ProductionConfigValidator 检测到默认密码会阻断 prod 启动）

    # 5a) 生成新 BCrypt hash（交互输入新密码）
    htpasswd -nbBC 10 "" '<your_strong_password>'
    # 示例输出：$2b$10$...

    # 5b) 注入数据库（替换 <BCRYPT_HASH> 为上一步输出）
    docker compose exec -T postgres psql -U geosentry -d geosentry \
      -c "UPDATE sys_user SET password_hash='<BCRYPT_HASH>' WHERE username IN ('admin','attendant','maintenance');"
    ```

    > **BCrypt 前缀兼容性**：`htpasswd` 默认生成 `$2y$` 前缀，Spring Security 的 `BCryptPasswordEncoder` 使用 `$2a$` 前缀，两者算法兼容可互验。若遇到校验失败，改用 Spring Security 生成：写一段临时 Java 调用 `new BCryptPasswordEncoder().encode(password)` 即可。

    **替代方案（推荐给安全敏感场景）**：**删除 data.sql 种子注入，启动后手动创建管理员**。
    种子数据中三个用户密码均为 `123456`，存在已知默认凭据风险。更安全的做法：

    ```bash
    # 跳过步骤 4（不执行 data.sql），直接启动全栈
    # 此时 sys_user 表为空，ProductionConfigValidator 跳过种子密码检查
    docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build

    # 启动后用 SQL 直接创建管理员
    # htpasswd 先生成 hash，然后：
    docker compose exec -T postgres psql -U geosentry -d geosentry \
      -c "INSERT INTO sys_user (username, password_hash, full_name, role, status, created_at) \
          VALUES ('admin', '<BCRYPT_HASH>', '系统管理员', 'ADMIN', 'ENABLED', NOW());"
    ```

    > **为什么没有注册接口？** 出于安全考虑，未实现 `POST /api/auth/register` 公开注册端点。如果确实需要开放注册（如多租户场景），必须设计邀请码/审批机制。
    6. **最后启动全栈服务**：`docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build`
       此命令会启动 backend、frontend、ai-service 等全部服务。frontend 可随时启动（无依赖顺序要求），nginx 会代理 `/api` 和 `/ws` 到 backend。

### 生产依赖服务初始化检查清单

除 PostgreSQL 外，以下依赖服务需确认初始化状态（dev 模式自动处理，prod 需手动检查）：

| 服务         | 初始化项                                            | 自动/手动                                                                                                                     | 检查/修复命令                                                                                                                                                      |
| ------------ | --------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **EMQX**     | Built-in Database 认证器 + `geosentry_backend` 用户 | 自动（`infra/emqx/init.sh` entrypoint 执行）                                                                                  | `docker compose exec emqx emqx ctl users list` 查看用户列表；若缺失：`docker compose exec emqx sh /opt/emqx/init.sh`                                               |
| **MinIO**    | `geosentry` bucket                                  | **自动**（`AttachmentService` 首次附件上传时自动检查并创建）                                                                  | 检查 bucket 是否存在：`docker compose exec minio mc alias set myminio http://localhost:9000 $MINIO_ACCESS_KEY $MINIO_SECRET_KEY && mc ls myminio/`；可选预创建同上 |
| **TDengine** | `geosentry` 数据库 + `device_metric` 超级表         | 自动（首次写入时 `TdengineMetricService.writeMetric()` 执行 `CREATE DATABASE IF NOT EXISTS` + `CREATE STABLE IF NOT EXISTS`） | `docker compose exec tdengine taos -s "SHOW DATABASES;"` 确认数据库存在                                                                                            |
| **Redis**    | 无需初始化                                          | —                                                                                                                             | `docker compose exec redis redis-cli ping` 返回 PONG 即可                                                                                                          |

> **生产首次部署顺序**：按 #43 步骤 1–5 初始化 PostgreSQL → 执行步骤 6 启动全栈。`docker-compose.prod.yml` 已配置 backend 的 `depends_on: service_healthy`（postgres/redis/minio/emqx）+ healthcheck，会自动等待依赖就绪。frontend 无依赖顺序，可在 backend 之后或同时启动。

---

### 版本控制注意事项

项目根目录已提供 `.gitignore`，以下内容**不应提交到版本库**：

- `dist/` — 前端构建产物（`npm run build` 生成）
- `node_modules/` — 前端依赖目录
- `backend/target/` — Maven 编译产物
- `m2_cache/` — docker-compose 中 Maven 本地仓库卷
- `*.log`、`.env`、密钥文件、PEM/key 证书

建议在首次 clone 后执行 `git status` 确认无敏感文件被误跟踪。若仓库缺少 `.gitignore`，请从项目模板复制或手动创建以上条目。

_本文档基于仓库当前代码整理，随项目迭代请同步更新。_
