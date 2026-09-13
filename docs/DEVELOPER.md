# 地脉智巡 · 开发者文档

> 地质灾害点智能巡检与预警管理平台（Geohazard Inspection Dashboard）
> 本文档面向参与本项目的开发者，覆盖架构、代码组织、核心模块、接口、数据库、部署与二次开发指南。
> 最近更新：2026-09-13（Step 9：二次清理 + 文档同步）

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
15. [开发环境故障排除](#15-开发环境故障排除windows-docker-desktop)
16. [Sentinel-1+2 卫星合成图层](#16-sentinel-1--2-卫星合成图层)
17. [国家气象数据接入](#17-国家气象数据接入卫星云图--雷达--24h-降水--24h-积雪--干旱)
18. [AI 综合分析 —— 全项目后台引擎](#18-ai-综合分析--全项目后台引擎)
19. [AI 设备健康评分 —— 监测设备由 AI 研判](#19-ai-设备健康评分--监测设备由-ai-研判)
20. [AI 灾害评估 v1.3.0 —— 单独测算 + 灾害类型分类](#20-ai-灾害评估-v130--单独测算--灾害类型分类)
21. [AI 算法完善 v1.4.0 —— 衍生特征 + 自适应归一化 + SHAP 贡献度 + 反馈学习](#21-ai-算法完善-v140--衍生特征--自适应归一化--shap-贡献度--反馈学习)
22. [AI 算法完善 v1.5.0 —— 真实模型接入](#22-ai-算法完善-v150--真实模型接入)
23. [数据库全量入库 v1.5.0 —— 零虚拟数据](#23-数据库全量入库-v150--零虚拟数据)
24. [多源数据融合体系](#24-多源数据融合体系)
25. [AI 实时灾害监测 v1.6.0/v1.7.0 —— 全国版](#25-ai-实时灾害监测-v160v170--全国版)
26. [多源外部数据接入 v1.7.0 —— 真实免费 API](#26-多源外部数据接入-v170--真实免费-api)
27. [审计日志归档 v1.6.0 —— PostgreSQL 函数化归档](#27-审计日志归档-v160--postgresql-函数化归档)
28. [项目结构与清理规范（v1.7.0 Step 6/9）](#28-项目结构与清理规范v170-step-69)

> 本文档基于仓库 v1.7.0 当前代码整理，随项目迭代请同步更新。
> 章节 §1–§15 为核心开发文档，§16–§27 为按版本新增的特性专题章节（v1.4.0+ 演进），§28 为项目结构维护规范。

---

## 1. 项目简介

「地脉智巡」是一个面向地质灾害监测、巡检、预警和应急响应的综合管理平台。系统围绕 **灾害点 → 监测设备 → 巡检任务 → 预警事件 → 应急联动** 的业务主线，为区县/乡镇级别乃至全国范围的地质灾害防治提供一体化支撑。

系统主要能力：

- **态势总览**：二维地图（OpenLayers）+ 三维视角（Cesium）+ 实时预警 + 天气 + 值班力量
- **灾害点管理**：隐患点档案、风险等级、威胁对象、空间坐标、关联记录
- **巡检任务**：任务创建、分派、GPS 签到、围栏校验、巡检上报闭环
- **监测设备**：设备在线状态、电量信号、上报趋势、离线告警
- **预警中心**：预警确认 → 派发 → 关闭的闭环流程
- **应急处置**：转移安置、专家会商、现场管控、处置复盘
- **统计分析**：风险趋势、巡检完成率、预警分布、区域热力
- **AI 综合分析**（v1.6.0+）：**后台全项目引擎**，每 5 分钟全量刷新灾害点 AI 风险评分（图像 + 降水 + 地理 + 设备四维度），单一可信源驱动预警/巡检/分析/助手四模块
- **AI 设备健康评分**（v1.6.0+）：每台 monitoring_device 都由 AI 给出 0-100 健康分 + HEALTHY/ATTENTION/WARN/CRITICAL 四级
- **AI 灾害评估**（v1.3.0+）：基于"当地地理 + 当地天气"对每个 hazard 单独测算，输出 risk_level + concern_label + disaster_type（滑坡/崩塌/泥石流/地裂缝/地面沉降/塌陷）三件套
- **AI 实时灾害监测**（v1.6.0+ 全国版）：从 `region` 表动态加载全国乡镇/村级行政区域，按 `geo_type` 决定网格步长（MOUNTAIN=0.01° / HILL=0.02° / COASTAL=0.015°），每 90 秒批量调用 AI 检测，落库 `hazard_point`（source='AI_MONITOR'） + 自动联动预警
- **多源外部数据接入**（v1.7.0+）：Open-Meteo（气象 + 土壤湿度）、NASA Giovanni（GPM IMERG / SMAP）、USGS FDSN（实时地震）、Open-Elevation（SRTM）、GDACS（全球灾害警报）、OSM Overpass（承灾体暴露度）、Copernicus STAC（Sentinel-2 元数据）共 7 个真实公开 API 接入，含缓存 + 超时 + 失败降级
- **审计日志归档**（v1.6.0+）：operation_log 通过 PostgreSQL 函数 `archive_operation_logs(retention_days)` 每日凌晨 3:00 自动迁移到 `operation_log_archive`

### 当前阶段说明

- **前端**：以真实后端为主。应用启动时会先探测后端健康状态，核心业务操作不再静默回退到本地 mock；若后端不可用，相关接口会给出明确错误提示。HazardDetailPage 展示 AI 位移趋势预测（未来 7 天柱状图 + 置信度）；WarningDetailPage 展示 AI 融合风险评分（等级 + 分数 + 置信度）；AiInsightPage 展示综合分析报告（AI 分 + 关注度 + 灾害类型 + reasoning + suggestedActions + 特征贡献度条形图 + 反馈按钮）。应用右下角悬浮**地脉智巡助手**（AiAssistant，自然语言入口：哪些灾害点最危险 / 应急派单 / 综合报告 / 哪些设备异常），左下角显示**运行模式角标**（后端连接中 / 演示模式 / 检测中），数据来源一目了然。路由 `/` 是 WelcomePage（公开，登录弹窗 + 角色切换），所有业务页面统一在 `/dashboard/*` 之下（DashboardLayout，含侧边栏 + 顶栏 + 全局反馈条）。
- **后端**：已具备主要业务模块实现（灾害点/任务/设备/预警/巡检/附件/认证/统计/应急/值守人员位置/实时推送/AI 推理集成/审计归档），预警六态状态机、应急 CRUD（含橙/红预警派发自动联动、`POST /{id}/close` 闭环与导出）、Analytics 真实聚合、WebSocket `/ws` + JWT 鉴权广播（含 Origin 白名单）、EMQX MQTT 桥接（broker 侧认证通过 `infra/emqx/init.sh` 自动创建 BCrypt 用户）、TDengine 时序写入主链路（`infra/tdengine/init.sql` 容器首启建库建表 + 熔断器降级 + 异步 3 次重试）、值守人员 Redis 实时缓存（位置上报限本人，Redis SCAN 非阻塞遍历）、设备数据上报 X-Device-Key 鉴权、**设备离线自动心跳**（`DeviceOnlineChecker` 每 60s 游标分批扫描 `last_report_at` 超时设备置离线并广播）、**AI 后端全链路**（AiServiceClient + AiForecastScheduler 复用类级线程池从 DB 拉取历史批量预测、RuleEngineScheduler 经 `WarningCreateService` 两阶段创建预警 + AI 融合升级预警等级、DeviceDataService 设备侧时间戳去重 + 异步异常检测、**AiInsightService 综合分析 5 分钟调度落库** + **`AiWarningScheduler` 综合分→预警 10 分钟联动**、**AiDeviceHealthService 设备健康 5 分钟调度落库**、**AiHazardMonitorScheduler 实时监测 90 秒全国网格扫描**）、**ExternalDataService 多源外部数据接入**（7 个真实公开 API + 缓存 + 降级）、**OperationLogAspect AOP 审计**（自动拦截所有 `*Controller` 写方法）+ **OperationLogArchiver 每日归档** 均已落地。剩余缺口：真实 AI 模型文件需放入 `ai-service/models/`（训练脚本已就绪，可通过 `python -m app.training.train_xgboost --samples 2000` 一键训练）、EMQX broker TLS 证书（broker 自身配置，代码已预留 username/password 鉴权）、Copernicus Data Space OAuth token（已可读公开元数据，下载影像需 token）。

---

## 2. 技术栈

### 前端

| 类别       | 技术                                                                                      |
| ---------- | ----------------------------------------------------------------------------------------- |
| 框架       | Vue 3.5（Options/Composition API 混用，多为 `<script setup>`）                            |
| 构建       | Vite 6（`@vitejs/plugin-vue` ^5.2.3 + `vite-plugin-cesium@1.2.23`，含 `vite ^6.0.5`） |
| 路由       | vue-router 4（web history，`createWebHistory()`）                                         |
| 图标       | lucide-vue-next ^0.468.0                                                                 |
| 地图（2D） | OpenLayers 10（`mapService.js`，`ol ^10.10.0`）；Leaflet 已从代码和 `package.json` 中彻底移除 |
| 地图（3D） | Cesium 1.144（按需动态 import，`vite-plugin-cesium` 处理静态资源）                        |
| Lint       | ESLint 10（`eslint ^10.0.0` + `eslint.config.js` 扁平配置，`npm run lint` / `lint:fix`）   |

### 后端

| 类别      | 技术                                                                         |
| --------- | ---------------------------------------------------------------------------- |
| 框架      | Spring Boot 3.4.1                                                            |
| 语言      | Java 17                                                                      |
| 持久层    | Spring Data JPA + Hibernate                                                  |
| 空间数据  | Hibernate Spatial + PostGIS（`hibernate-spatial`，JTS `Point`，EPSG:4326）   |
| 安全      | Spring Security + JWT（jjwt 0.12.6）                                         |
| 缓存      | Spring Data Redis（已配置 Lettuce）                                           |
| 对象存储  | MinIO（Minio SDK 8.5.12，已配置）                                            |
| 文档      | SpringDoc OpenAPI（Spring Boot 3 / WebMvc UI）                               |
| 校验      | Spring Boot Validation                                                       |
| AI 客户端 | Spring RestClient（`cn.geosentry.ai.AiServiceClient`，调用独立 Python 服务） |
| 外部数据客户端 | Java HttpClient（`cn.geosentry.ai.service.ExternalDataService`，7 个真实公开 API：Open-Meteo / NASA Giovanni GPM / SMAP / USGS FDSN / Open-Elevation / GDACS / OSM Overpass / Copernicus STAC） |

### AI 推理服务（`ai-service/`，FastAPI）

| 类别     | 技术                                                        |
| -------- | ----------------------------------------------------------- |
| 框架     | Python FastAPI + Uvicorn                                    |
| 目标检测 | Ultralytics YOLOv8 + ONNX Runtime（模型缺失时降级为启发式） |
| 时序预测 | PyTorch LSTM（模型缺失时降级为线性外推）                    |
| 异常检测 | Scikit-learn Isolation Forest（降级为 Z-score）             |
| 融合预警 | XGBoost（降级为加权启发式）                                 |
| 综合风险 | **XGBoost fusion + 维度加权降级** + 衍生特征工程 + 自适应归一化 |
| 设备健康 | **异常检测 + 时序预测 + 加权融合**（单端点 `/device-health/score`）|
| 灾害评估 | **地理 + 降水 + 设备三维度 + 六类灾害投票**（`/disaster-assessment/score`）|
| 反馈学习 | FeedbackStore（deque(maxlen=10000)）+ `/feedback` + `/model/retrain` |
| 卫星影像 | Sentinel-1/2 合成图层（`/sentinel` 路由器，`/image`、`/layers`、`/composite`） |
| 社交媒体 | 社交媒体灾害预警聚合（`/social_media` 路由器）|
| 部署     | Docker（独立容器 `ai-service`，端口 8000）                  |

> AI 服务为**可选增强**：模型/依赖缺失也能启动并返回 mock 结果，后端降级为纯规则模式，不影响既有业务。Python 端 `app/training/train_xgboost.py` 已可基于合成数据 + FeedbackStore 一键训练真实模型文件 `models/xgboost_fusion.json`。

### 外部数据源（v1.7.0+，全部免费公开 API）

| 数据源                  | 类别      | 接入方式                                                       | 用法                                                          |
| ----------------------- | --------- | -------------------------------------------------------------- | ------------------------------------------------------------- |
| Open-Meteo              | 气象      | `https://api.open-meteo.com/v1/forecast`                       | 温度/湿度/降雨/气压/风速/土壤湿度（ERA5 + ERA5-Land）        |
| NASA Giovanni（GPM）    | 气象      | `https://api.giovanni.earthdata.nasa.gov/v1/timeseries`        | GPM IMERG 24h 累计降雨（小时精度）                          |
| NASA Giovanni（SMAP）    | 气象      | 同上端点切换 `data` 字段                                       | SMAP L3 地表土壤湿度（每日 m³/m³）                          |
| USGS FDSN Event Web     | 地震      | `https://earthquake.usgs.gov/fdsnws/event/1/query`             | 实时地震事件（按距离 + 震级筛选）                            |
| Open-Elevation          | 地形      | `https://api.open-elevation.com/api/v1/lookup`                | SRTM 30m DEM 海拔                                             |
| GDACS                    | 灾害      | `https://www.gdacs.org/gdacsapi/api/events/geteventlist`       | 全球灾害警报（地震/台风/洪水，按距离过滤）                    |
| OSM Overpass            | 暴露度    | `https://overpass-api.de/api/interpreter`                      | 土地利用 + 路网密度 + 建筑物数量                              |
| Copernicus Data Space STAC | 卫星    | `https://stac.dataspace.copernicus.eu/v1/search`               | Sentinel-2 元数据（公开查询，无需 OAuth）                    |

> 全部由 `cn.geosentry.ai.service.ExternalDataService` 统一接入，含 `ConcurrentHashMap` 缓存（默认 300s TTL）、10s 超时、失败降级（返回 null）。配置 `geosentry.external-data.enabled-sources` 可按需关闭任一数据源。

### 基础设施（Docker Compose）

- **PostGIS** `postgis/postgis:15-3.4-alpine` — PostgreSQL 15 + PostGIS 扩展
- **Redis 7** `redis:7-alpine` — 密码 `redis123`，开启 AOF 持久化
- **MinIO** `minio/minio:latest` — 对象存储，默认 bucket `geosentry`
- **EMQX 5.9** `emqx/emqx:5.9.0` — MQTT Broker，内置 BCrypt 认证（首次启动自动执行 `infra/emqx/emqx-entrypoint.sh` 创建 `geosentry_backend` 用户）
- **TDengine** `tdengine/tdengine:latest` — 时序数据库，支持 TCP（6030）和 HTTP REST（6041）
- **ai-service**（FastAPI AI 推理服务，可选增强，已编排）

### 关键后端依赖（补充）

| 依赖                    | 版本       | 作用                                                                                                |
| ----------------------- | ---------- | --------------------------------------------------------------------------------------------------- |
| taos-jdbcdriver         | 3.3.0      | TDengine JDBC（**compile scope**，自动打包进 Spring Boot fat jar，本地 `spring-boot:run` 与 Docker 镜像均无需额外处理） |
| easyexcel               | 3.3.4      | Excel 导出（灾害点/任务/设备/预警/应急/总览六个 `*ExportRow` DTO + `ExcelExportService`）           |
| org.eclipse.paho:mqttv3 | 1.2.5      | EMQX MQTT 客户端（`MqttDataSubscriber`）                                                            |
| springdoc-openapi        | 2.8.0      | Swagger UI + OpenAPI 3（启用 `KotlinDeprecatedPropertyCustomizer`，需要 `kotlin-reflect` runtime 依赖避免 `NoClassDefFoundError`） |
| kotlin-reflect          | 1.9.25     | SpringDoc 2.8.0 间接依赖（runtime scope），纯 Java 项目**也必须保留**，消除元数据读取异常 |
| jjwt                    | 0.12.6     | JWT 签发与解析（jjwt-api + jjwt-impl + jjwt-jackson 三件套，runtime scope）                         |
| spring-boot-starter-data-redis | —       | 值守人员位置缓存（`PersonnelController` 注入 `RedisTemplate<String, Object>`，通过 `RedisConfig` 显式构造 Lettuce 连接工厂；后续计划用于 WsTicket 持久化 / JWT 黑名单 / Session 共享） |

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
│  后端 Spring Boot (容器内 8080 / 宿主机 8080)  │
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
├── run-backend.ps1            # Windows 本地一键启动后端（端口检查/环境变量/mvn run）
├── run-frontend.ps1           # Windows 本地一键启动前端（npm run dev）
├── eslint.config.js           # ESLint 扁平分配置（ESLint 10）
├── docs/
│   └── DEVELOPER.md           # 本文档
├── src/                       # 前端源码
│   ├── main.js                # 应用入口
│   ├── App.vue                # 根组件（含右下角"运行模式"角标）
│   ├── styles.css             # 全局样式
│   ├── router/index.js        # 路由 + 守卫 + 权限
│   ├── stores/auth.js         # 角色/权限/登录态（含 JWT 过期本地校验）
│   ├── layouts/DashboardLayout.vue  # 主布局
│   ├── services/              # 服务层（按 core/domain 分层）
│   │   ├── _architecture/core/    # 通用基础（httpClient / realtimeService / mapService / geocodeService / healthService / runtime / errors）
│   │   ├── _architecture/domain/  # 业务域服务（hazard / warning / ai / device / notification / rule-engine / ...）
│   │   ├── mock/                  # 离线 mock 数据（ai / analytics / workflow / runtime / seed / system / operations / empty）
│   │   └── dashboardApi.js        # 兼容入口，按需 re-export（逐步淘汰）
│   ├── composables/useHazardList.js  # 灾害点列表逻辑
│   ├── composables/useOverviewSearch.js  # 总览页地图搜索
│   ├── utils/geo.js           # 地理计算工具
│   ├── utils/coordTransform.js # 坐标系转换（WGS84 / GCJ-02 / BD-09）
│   ├── data/dashboardData.js  # 菜单/角色元数据 + 离线 fallback 演示数据
│   ├── components/            # 通用组件（含 Animated*Background / BrandMascot / WeatherOverlayLegend / AiAssistant / PersonnelTooltip 等）
│   └── views/                 # 页面（含 WelcomePage / LoginPage / OverviewPage / HazardsListPage / HazardDetailPage / TasksPage / TaskDetailPage / DevicesPage / DeviceDetailPage / WarningsPage / WarningDetailPage / EmergencyPage / AnalyticsPage / WeatherPage / AiInsightPage / SettingsPage）
├── backend/                   # 后端源码（Spring Boot）
│   ├── pom.xml
│   └── src/main/
│       ├── java/cn/geosentry/
│       │   ├── GeosentryApplication.java
│       │   ├── ai/            # AI 客户端 + 结果持久化（RestClient + JPA）+ 调度器 + ExternalDataService + GeoContextResolver + 综合分析/设备健康/灾害评估/反馈
│       │   ├── auth/          # 登录 + JWT + 改密
│       │   ├── config/        # 安全/CORS/OpenAPI/属性配置 + AsyncConfig + RedisConfig + ExternalDataProperties + WeatherProperties + MinioCredentialValidator + ProductionConfigValidator + DatabaseForeignKeyInitializer + AppProperties
│       │   ├── audit/         # **操作审计（OperationLogAspect 已迁至 aop/） + 归档（OperationLogArchiver + OperationLog 实体 + 仓储）**
│       │   ├── aop/           # OperationLogAspect 操作审计切面（拦截所有 *Controller 写方法）
│       │   ├── common/        # 统一返回、异常、Geo、安全过滤器 + export/（EasyExcel 导出行）+ security/PermissionRegistry
│       │   ├── hazard/        # 灾害点（含 source='AI_MONITOR' 来源标记 + exposure_buildings/population/land_use 等承灾体字段）
│       │   ├── task/          # 巡检任务
│       │   ├── inspection/    # 巡检记录（GPS 围栏）
│       │   ├── device/        # 监测设备（HTTP + EMQX MQTT 上报 + TDengine 主链路 + 设备离线心跳 + 指标模拟器 + 指标清理）
│       │   ├── warning/       # 预警（六态状态机 + 规则引擎 + AI 综合分联动 AiWarningScheduler + engine/WarningCreateService 两阶段创建）
│       │   ├── emergency/     # 应急（完整 CRUD + 导出，橙/红预警派发时自动生成）
│       │   ├── personnel/     # 值守人员位置（Redis 实时缓存 + 移动端上报 /positions/report）
│       │   ├── dashboard/     # 仪表盘（真实表聚合 + analytics + summary 元数据 + 导出）
│       │   ├── realtime/      # WebSocket /ws + 广播服务（RealtimeBroadcastService）
│       │   ├── attachment/    # 附件（MinIO 上传/导出/删除，含大小与扩展名校验）
│       │   ├── weather/       # **国家气象数据接入（NSMC 卫星云图 / NMC 雷达 + 24h 降水 + 积雪 + 干旱）**
│       │   ├── region/        # **行政区划（v1.7.0 + center_lon/center_lat/geo_type，AiHazardMonitorScheduler 监控锚点）**
│       │   └── health/        # 健康检查
│       └── resources/
│           ├── application.yml / -dev / -prod / -docker
│           ├── schema.sql     # 建表脚本（纯 DDL，spring.sql.init 幂等执行）
│           └── data.sql       # 种子数据（幂等）
├── ai-service/                # AI 推理服务（Python FastAPI）
│   ├── app/
│   │   ├── main.py            # FastAPI 入口（含 /api/ai/* 路由，CORS 可用 AI_CORS_ORIGINS 配置）
│   │   ├── routers/           # detection / forecast / anomaly / fusion / comprehensive / device_health / disaster_assessment / feedback / model_info / sentinel / social_media
│   │   ├── models/            # 模型加载与推理（lstm/xgboost_model/yolo/isolation_forest + 综合/设备健康/灾害评估/反馈/特征工程/自适应归一化）+ __init__.py
│   │   ├── training/          # XGBoost 训练 + 合成数据生成器
│   │   ├── schemas/           # Pydantic 模型
│   ├── tests/                 # pytest 单元测试（73 用例：feature_engineering/disaster_assessment/device_health/feedback_store/xgboost_model/synthetic_data）
│   ├── scripts/               # 训练脚本（YOLO / LSTM / IsolationForest / XGBoost fusion）
│   ├── Dockerfile
│   ├── requirements.txt
│   └── models/                # 存放训练好的模型文件（缺失则降级）；models/history/ 归档目录
│       ├── xgboost_fusion.json        # 主模型（v1.5.0）
│       ├── xgboost_fusion.meta.json   # 训练元数据
│       └── history/                   # 历史归档（每次训练自动备份）
├── Dockerfile.backend         # 后端多阶段构建（根目录，内部 COPY backend/pom.xml + src）
├── Dockerfile.frontend       # 前端多阶段构建（基于 nginx:alpine）
├── docker-compose.yml        # dev 模式编排（postgres/redis/minio/emqx/tdengine/backend/frontend/ai-service）
├── docker-compose.prod.yml   # 生产模式覆盖文件（backend + frontend 生产配置）
├── infra/postgres/init/       # PostGIS 扩展初始化（docker-entrypoint 首次建卷执行）
├── infra/emqx/                # EMQX 认证初始化（emqx-entrypoint.sh + init.sh）
├── infra/tdengine/init.sql    # TDengine 建库 + device_metric 超级表（容器首启执行）
└── dist/                      # 前端构建产物
```

---

## 4. 本地开发环境搭建

### 4.1 环境要求

| 依赖                | 最低版本      | 用途                                      |
| ------------------- | ------------- | ----------------------------------------- |
| Docker Desktop      | 最新          | 基础设施容器（Postgres/Redis/MinIO/EMQX/TDengine），Windows/macOS 均可 |
| JDK                 | 17            | 后端编译运行（Spring Boot 3.4.1）         |
| Maven               | 3.8+          | 后端依赖管理与构建（需加入 PATH）          |
| Node.js             | 18+（推荐 20）| 前端构建与开发服务器（Vite 6）           |
| npm                 | 随 Node 安装  | 前端依赖管理                             |

> **Windows 用户**：Docker Desktop 安装后需确认 WSL2 后端已启用。Maven 安装后需手动加入系统 PATH（`mvn --version` 验证）。若 Docker Hub 拉取超时，参见 §15 Q1 配置镜像加速器。

### 4.2 启动方式总览

项目支持两种本地开发模式，按需选择：

| 模式             | 基础设施 | 后端                    | 前端                    | 适用场景                           |
| ---------------- | -------- | ----------------------- | ----------------------- | ---------------------------------- |
| **全 Docker**    | Docker   | Docker 容器              | Docker 容器（Nginx）    | 快速验证、CI 环境、无需本地 JDK/Maven |
| **混合模式**（推荐） | Docker   | 宿主机 `mvn spring-boot:run` | 宿主机 `npm run dev` | 日常开发调试，热重载、断点、日志实时查看 |

> 混合模式为**推荐开发模式**：基础设施（数据库/缓存/对象存储/MQTT）走 Docker，后端和前端在宿主机直跑，享受热重载和实时日志。Docker 后端容器内双 JVM（Maven + Spring Boot）占用约 700MB 内存，且 `mvn spring-boot:run` 不会自动热加载代码变更，不适合迭代开发（详见 §15 Q3）。

### 4.3 混合模式启动（推荐）

#### 步骤 1：启动基础设施容器

```bash
# 在项目根目录执行
docker compose up -d postgres redis minio emqx tdengine
```

首次启动会自动：
- PostgreSQL：创建 `geosentry` 数据库 + PostGIS 扩展（`infra/postgres/init/` 脚本）
- EMQX：创建 `geosentry_backend` MQTT 认证用户（`infra/emqx/emqx-entrypoint.sh`）
- 其余服务直接就绪

验证容器状态：

```bash
docker compose ps
# 所有服务应为 running + healthy
```

#### 步骤 2：启动后端（宿主机直跑）

**Windows PowerShell — 使用一键脚本（推荐）**：

```powershell
# 在项目根目录执行
.\run-backend.ps1
```

`run-backend.ps1` 自动完成：检测 8080 端口占用并自动停止 Docker 后端容器（`docker compose stop backend`）、用 `Get-NetTCPConnection` 检查基础设施端口（Postgres/Redis/MinIO）、设置环境变量、启动 `mvn -DskipTests spring-boot:run`。

**手动设置环境变量（不使用脚本时）**：

```powershell
# PowerShell（Windows）
$env:SPRING_PROFILES_ACTIVE = "default"
$env:POSTGRES_PORT   = "15432"     # Docker 映射的非标准端口
$env:REDIS_PORT      = "16379"
$env:REDIS_HOST      = "127.0.0.1" # Windows 上用 127.0.0.1 避免 IPv6 问题
$env:REDIS_PASSWORD  = "redis123"
$env:MINIO_ENDPOINT  = "http://127.0.0.1:19000"
$env:JWT_SECRET      = "geosentry-dev-secret-key-at-least-32-chars-long-xyz"
$env:DEV_USERS_ENABLED  = "true"  # 启用内存 fallback 用户（无 DB 时也能登录）
$env:DEVICE_AUTH_STRICT = "false" # 迁移期兼容未登记 api_key 的设备
$env:TDENGINE_ENABLED  = "false"  # 未启用 TDengine 时关闭，避免连接超时日志
$env:EMQX_ENABLED      = "false"  # 未启用 MQTT 时关闭
$env:AI_ENABLED        = "false"  # 未启动 ai-service 时关闭

cd backend
mvn -DskipTests spring-boot:run
```

```bash
# bash（macOS/Linux）
export SPRING_PROFILES_ACTIVE=default
export POSTGRES_PORT=15432 REDIS_PORT=16379 REDIS_HOST=127.0.0.1 REDIS_PASSWORD=redis123
export MINIO_ENDPOINT='http://127.0.0.1:19000'
export JWT_SECRET='geosentry-dev-secret-key-at-least-32-chars-long-xyz'
export DEV_USERS_ENABLED=true DEVICE_AUTH_STRICT=false
export TDENGINE_ENABLED=false EMQX_ENABLED=false AI_ENABLED=false
cd backend && mvn -DskipTests spring-boot:run
```

启动成功标志：

```
INFO  --- [geosentry-backend] [main] c.g.GeosentryApplication : Started GeosentryApplication in X.Xs
```

访问 `http://localhost:8080/swagger-ui.html` 确认 Swagger UI 可用。日志文件输出到 `backend/logs/backend.log`（同时输出到控制台）。

#### 步骤 3：启动前端（宿主机直跑）

> ⚠️ **必须先停掉 Docker 前端容器**（`docker compose stop frontend`）。Docker 前端容器内的 Nginx 反代指向 Docker 网络内的 `backend:8080`，而本地后端不在 Docker 网络中，会导致 **502 Bad Gateway**。详见 §15 Q10。

**Windows PowerShell — 使用一键脚本（推荐）**：

```powershell
# 在项目根目录执行
# 脚本会自动停掉 Docker 前端容器、检测后端 8080 端口、设置 Vite 环境变量
.\run-frontend.ps1
```

**手动启动**：

```bash
# 先停掉 Docker 前端容器（释放 5173 端口 + 避免 Nginx 502）
docker compose stop frontend

# 再启动本地 Vite dev server
npm install        # 首次或依赖变更时
npm run dev        # 默认 5173 端口
npm run lint       # ESLint 检查（eslint.config.js 扁平配置，可选 lint:fix 自动修复）
```

前端会自动探测后端健康状态（`GET /api/system/health`），探测成功则进入真实后端模式，右下角显示"后端连接中"角标。如需指定后端地址，在项目根目录创建 `.env` 文件：

```bash
# .env（参考 .env.example）
VITE_USE_BACKEND=true
VITE_API_BASE_URL=http://localhost:8080/api
VITE_WS_URL=ws://localhost:8080/ws
```

#### 步骤 4：验证登录

打开 `http://localhost:5173`，使用开发预置账号登录：

| 用户名         | 密码       | 角色         |
| -------------- | ---------- | ------------ |
| `admin`        | `123456`   | 系统管理员   |
| `attendant`    | `123456`   | 值班人员     |
| `maintenance`  | `123456`   | 维护人员     |

> 这三个账号由 `data.sql` 种子数据注入（BCrypt 哈希），`DEV_USERS_ENABLED=true` 时也可通过 `DbUserDetailsService` 内存 fallback 登录（无需数据库）。详见 §15 Q9。

### 4.4 全 Docker 启动

```bash
# 启动全部服务（含后端、前端、AI）
docker compose up -d

# 或仅启动核心服务（不含 AI）
docker compose up -d postgres redis minio emqx tdengine backend frontend
```

全 Docker 模式下：
- 后端容器内执行 `mvn -q -DskipTests spring-boot:run`，使用 `SPRING_PROFILES_ACTIVE=docker`
- 前端容器为多阶段构建（`Dockerfile.frontend`），Node 构建 + Nginx 运行
- 前端通过 Nginx 反向代理 `/api` 和 `/ws` 到 `backend:8080`，同域部署无 CORS 问题

### 4.5 端口速查

| 服务             | 宿主机端口 | 容器内端口 | 说明                         |
| ---------------- | ---------- | ---------- | ---------------------------- |
| 后端             | 8080       | 8080       | Spring Boot REST + WebSocket  |
| 前端（Vite dev） | 5173       | —          | 本地开发服务器               |
| 前端（Docker）   | 5173       | 80         | Nginx 反向代理               |
| PostgreSQL       | 15432      | 5432       | PostGIS 数据库               |
| Redis            | 16379      | 6379       | 缓存 + 值守人员位置          |
| MinIO API        | 19000      | 9000       | 对象存储 S3 API              |
| MinIO 控制台     | 19001      | 9001       | Web 管理界面                 |
| EMQX MQTT        | 11883      | 1883       | 设备数据上报                 |
| EMQX MQTTS       | 18883      | 8883       | TLS MQTT                      |
| EMQX WS          | 18083      | 8083       | WebSocket MQTT               |
| EMQX WSS         | 18084      | 8084       | TLS WebSocket                 |
| EMQX Dashboard   | 28083      | 18083      | Web 管理界面                 |
| TDengine TCP     | 16030      | 6030       | 时序数据原生协议             |
| TDengine HTTP    | 16041      | 6041       | 时序数据 REST API            |
| AI 推理服务      | 8000       | 8000       | FastAPI（可选）              |

> 生产模式（`docker-compose.prod.yml`）覆盖后端端口为 8081、前端为 80。

### 4.6 数据库与服务初始化

所有初始化均为**自动完成**，无需手动执行 SQL：

| 服务       | 初始化内容                          | 机制                                                              |
| ---------- | ----------------------------------- | ----------------------------------------------------------------- |
| PostgreSQL | 建表 + 索引 + 种子数据              | `spring.sql.init.mode: always`，每次启动幂等执行 `schema.sql` + `data.sql`（`CREATE TABLE IF NOT EXISTS` / `ON CONFLICT DO NOTHING`） |
| EMQX       | `geosentry_backend` MQTT 认证用户   | `infra/emqx/emqx-entrypoint.sh`，首次启动自动执行（BCrypt 密码）  |
| MinIO      | `geosentry` bucket                  | `AttachmentService` 首次附件上传时自动检查并创建                  |
| TDengine   | `geosentry` 数据库 + `device_metric` 超级表 | `TdengineMetricService.writeMetric()` 首次写入时 `CREATE DATABASE IF NOT EXISTS` + `CREATE STABLE IF NOT EXISTS` |

重建数据库（清空所有数据）：

```bash
docker compose down -v          # 删除数据卷
docker compose up -d postgres redis minio emqx tdengine   # 重新初始化
```

### 4.7 前端运行模式

前端启动时会先调用 `GET /api/system/health` 探测后端，优先进入真实后端模式；若探测失败或显式关闭，则前端给出明确错误提示，不会静默伪造成功。

| 配置                     | 行为                                                                                                                                                                                                                                                        |
| ------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `VITE_USE_BACKEND=true`  | 强制使用真实后端（`VITE_API_BASE_URL`，默认 `http://localhost:8080/api`）                                                                                                                                                                                   |
| `VITE_USE_BACKEND=false` | **纯 mock 模式**——完全不走后端，使用 `dashboardApi.js` 顶部 `seedState` 演示数据集（hazardPoints / tasks / devices / warnings / incidents / overviewStats）。所有读操作返回 seedState 数据；写操作/导出返回本地 mock 成功（不会持久化），页面刷新后变更丢失 |
| 未设置                   | 启动时自动探测 `GET /api/system/health`，成功则进入后端模式，失败则报错                                                                                                                                                                                     |

> 生产部署必须使用 `VITE_USE_BACKEND=true` 并确保后端可用，禁止 mock 模式上线。mock 模式仅供无后端环境下的界面演示。

---

## 5. 前端开发指南

### 5.1 应用入口

- `src/main.js`：创建应用、挂载路由、引入 `styles.css`。
- `src/App.vue`：根组件（全局路由视图 + **左下角运行模式角标**——启动时调 `getRuntimeMode()` 显示"后端连接中 / 演示模式 / 检测中" + **右下角地脉智巡助手**——AiAssistant 自然语言入口，可问"哪些灾害点最危险" / "应急" / "分析 GS-001" / "哪些设备异常"）。
- `src/router/index.js`：全部路由定义 + 全局前置守卫（含 JWT 过期本地校验与 404 兜底）。

### 5.2 路由表

所有业务页面挂在 `DashboardLayout`（带侧边菜单 + 顶栏 + 全局反馈条）之下，统一路径前缀 `/dashboard`。根路径 `/` 是公开的 `WelcomePage`（产品介绍 + 登录入口 + 角色切换）。

| 路径                          | 视图              | 说明                                                            |
| ----------------------------- | ----------------- | --------------------------------------------------------------- |
| `/`                           | WelcomePage       | 公开欢迎页（产品介绍 + 登录弹窗 + 角色切换入口）                |
| `/login`                      | 重定向到 `/?login=true` | 登录（公开，已登录用户跳转角色首页）                       |
| `/dashboard`                  | OverviewPage      | 态势总览（角色首页 admin/attendant → `/dashboard`）             |
| `/dashboard/devices`          | DevicesPage       | 监测设备（maintenance 角色首页）                                |
| `/dashboard/hazards`          | HazardsListPage   | 灾害点列表                                                      |
| `/dashboard/hazards/:id`      | HazardDetailPage  | 灾害点详情（含 3D）                                             |
| `/dashboard/tasks`            | TasksPage         | 巡检任务 + GPS 签到 + 上报                                      |
| `/dashboard/tasks/:id`        | TaskDetailPage    | 任务详情                                                        |
| `/dashboard/devices/:id`      | DeviceDetailPage  | 设备详情                                                        |
| `/dashboard/warnings`         | WarningsPage      | 预警中心                                                        |
| `/dashboard/warnings/:id`     | WarningDetailPage | 预警详情（含 AI 融合风险评分）                                  |
| `/dashboard/emergency`        | EmergencyPage     | 应急处置                                                        |
| `/dashboard/analytics`        | AnalyticsPage     | 统计分析                                                        |
| `/dashboard/weather`          | WeatherPage       | 气象数据中心（NSMC + NMC 六产品独立页面）                       |
| `/dashboard/ai-insight`       | AiInsightPage     | AI 综合分析（综合报告 + 反馈学习 + 灾害类型三件套）             |
| `/dashboard/settings`         | SettingsPage      | 系统设置（本地存储：基本 / 预警阈值 / 账号 / 数据保留）         |
| `/:pathMatch(.*)*`            | 404 兜底          | 路由不存在时显示 404 页面（公开）                               |

> 所有视图文件位于 `src/views/`，对应关系见路由表；旧版视图（`HazardsPage` / `TaskListPage`）已清理，不存在 `/tasks/xxxx` 之外的历史路径。`/dashboard/settings` 是新增页面（admin / attendant / maintenance 均可访问，见 §9.1），**当前 SettingsPage 是本地浏览器存储**（localStorage key `geosentry_settings`），**不与后端 API 同步**——生产环境如需全局生效需扩展 `PUT /api/system/settings` 后端接口。

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
- `downloadFile()`：以二进制流方式下载后端导出的 xlsx（导出接口不做 JSON 包装），页面导出按钮（总览/灾害点/任务/设备/预警/应急）统一走此方法。
- `initBackendMode()` / `getRuntimeMode()`：启动时探测后端健康状态并锁定运行模式（`backend` / `mock` / `probing`），`App.vue` 据此渲染右下角角标。
- 401 统一处理：`request()` 检测到 401（非 `/auth/` 路径）时调用 `redirectToLogin()`（内置并发锁，防止多个请求同时触发跳转），避免残留过期会话。
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

### 5.6 实时数据（`services/_architecture/core/realtimeService.js`）

> 服务从 `services/realtimeService.js` 已迁移到 `services/_architecture/core/realtimeService.js`，按 `_architecture/core` 通用基础层组织。

提供统一的实时状态订阅：

- 数据源优先级：WebSocket 推送（`VITE_WS_URL`）→ 预警高频轮询（`VITE_WARNING_POLL_INTERVAL`，默认 10s）→ 设备/汇总低频轮询（兜底 15s）→ 值守人员轮询。
- **WS 降级策略**（已实现，不是规划项）：三组轮询（pollingTimer / warningTimer / personnelTimer）与 WebSocket 状态联动——**WS 连接成功后暂停轮询，WS 断开/失败时恢复轮询兜底**（`pausePolling` / `resumePolling`），避免连接正常时重复请求。WebSocket 连接失败（onerror）或中断（onclose）时，5 秒后自动重连（重连时重新取 token/ticket，处理 token 刷新场景）。即使 WS 长期不可用，轮询持续兜底，实时数据不会中断。
- 状态缓存：`state = { warnings, devices, personnel }`。
- API：`startRealtimeSync` / `stopRealtimeSync` / `onRealtimeUpdate(listener)` / `getRealtimeState`。
- 事件类型：`snapshot`、`poll`、`warning-poll`、`warning`、`device`、`personnel`、`personnel-poll`、`personnel-motion`、`socket-open`、`socket-close`、`socket-error`。
- **幂等合并**：WebSocket 增量与轮询全量均通过 `upsert`/`mergeSnapshot` 按 `version + updatedAt` 做新旧比较，后到的旧数据不覆盖新状态，避免重连补发重复与界面回退（`isNewer`）。
- **值守人员数据模型**：前端 `PERSONNEL_ROSTER` 常量（姓名/角色/区域/电话/班组/班次）提供人员元数据，坐标来源分三层：
  1. 移动端上报 → `POST /api/personnel/positions/report` → Redis key `personnel:pos:{userId}`（TTL 120s）→ WebSocket 广播
  2. `GET /api/personnel/positions` 优先读 Redis，再与 DB fallback 合并（`inspection_record` 最近签到坐标 + `inspection_task` 关联 `hazard_point` 坐标）
  3. `USE_LIVE_PERSONNEL` 判定：`VITE_USE_PERSONNEL_LIVE=true` **或 `VITE_USE_BACKEND=true`** 时前端仅轮询后端、关闭本地轨迹推演；否则走 `PERSONNEL_ROSTER` 本地推演（演示）
- 轮询间隔：`VITE_REALTIME_POLL_INTERVAL`（默认 15s）、`VITE_WARNING_POLL_INTERVAL`（默认 10s）、`VITE_PERSONNEL_POLL_INTERVAL`（默认 30s）。

### 5.7 天气（`services/_architecture/domain/weatherService.js`）

> 已迁移到 `_architecture/domain/weatherService.js`，按业务域分组管理。`services/_architecture/core/geocodeService.js` 仍保留地理编码能力。

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
  - `AppException(code, message)` → 业务 code 映射为 HTTP 状态码（401/403/404/409/413/422 等，非 4xx/5xx 范围回退 400）+ 业务 code/message
  - `AiUnavailableException` → HTTP 503 + 提示 AI 服务不可用（上层据此降级）
  - `MethodArgumentNotValidException` → 400 + 首个字段校验消息
  - `ConstraintViolationException`（`@Validated` 参数约束）→ 400
  - `MissingServletRequestParameterException`（必填参数缺失）→ 400
  - `MethodArgumentTypeMismatchException`（参数类型不匹配）→ 400
  - 其他异常 → 500「系统繁忙，请稍后重试」+完整堆栈日志

### 6.2.1 异步线程池（`config/AsyncConfig`）

`@EnableAsync` + `taskExecutor`（`ThreadPoolTaskExecutor`）：核心线程 = max(4, CPU)、最大 = max(8, CPU×2)、有界队列 512、拒绝策略 `CallerRunsPolicy`（队列满时回退到调用线程执行，不丢任务），优雅停机等待 30s。用于 **AI 异常检测、TDengine 异步写入、WebSocket 广播**等 `@Async` 任务。替代 Spring 默认的 `SimpleAsyncTaskExecutor`（每次新建线程无上限，突发流量下易耗尽资源）。

### 6.3 安全与鉴权

- 配置：`config/SecurityConfig.java`
  - 无状态 Session（`STATELESS`）、禁用 CSRF、关闭 httpBasic/formLogin。
  - 放行：`/swagger-ui/**`、`/v3/api-docs/**`、`/api/system/health`、`/api/auth/login`。
  - `/api/auth/ws-ticket` **不在** permitAll 列表中：需携带有效 JWT 访问（`JwtAuthenticationFilter` 拦截后放行），签发一次性 WebSocket ticket。
  - `/api/devices/{deviceCode}/data`：仅允许 `ROLE_DEVICE`（由 `DeviceAuthFilter` 注入）。
  - 其他请求需认证。
  - ⚠️ **路径模式必须符合 Spring Security 6 `PathPatternParser` 规则**：`**` 只能出现在末尾，不能后面再跟路径段（如 `/**/data` 非法，需写 `/{code}/data`）。详见 §15 Q8。
  - `UserDetailsService`：`auth/security/DbUserDetailsService`（**DB 优先 + 内存 fallback**）。从 `sys_user` 表加载用户并校验 BCrypt 密码；`DEV_USERS_ENABLED=true` 时 DB 未命中回退到 admin/attendant/maintenance 三个硬编码账号（**fallback 账号也使用 BCrypt hash**，AuthController 统一校验明文 `123456`，不存在"任意密码可登录"的后门）。**默认关闭**（`application.yml` 默认 `false`），仅 `run-backend.ps1` 等在本地开发显式置 `true`。生产环境务必保持 `false`。
- 过滤器：`common/security/JwtAuthenticationFilter`
  - 从 `Authorization: Bearer <token>` 读取并解析 JWT，写入 `SecurityContext`。
  - 把 `role` claim 映射为 `ROLE_<ROLE>` + `PermissionRegistry` 注册的所有权限码 authority。
  - ⚠️ **异常捕获范围**：`try-catch` 仅包裹 token 解析逻辑，**不包含** `filterChain.doFilter()`。下游 Controller 异常会透传给 `GlobalExceptionHandler`，而非被误判为 401 导致前端踢回登录页。`filterChain.doFilter()` 在独立的 `try-finally` 中执行，`finally` 清理 `SecurityContext` 防止线程池复用污染。
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
  - **握手 Origin 白名单**：`WebSocketConfig` 通过 `setAllowedOriginPatterns` 限制允许连接的前端来源（`FRONTEND_ORIGIN`，支持逗号分隔多域），防止任意域名发起 WS 握手。
- EMQX 鉴权：
  - `realtime/EmqxSecurityPreflight` 启动时检查：`EMQX_ENABLED=true` 但未配置 `EMQX_USERNAME`/`EMQX_PASSWORD` 时输出清晰 WARN，提醒 MQTT broker 侧无认证，任何客户端可伪造设备数据。
  - 实际用户名密码在 `application.yml` 的 `emqx.username`/`emqx.password` 配置，需与 EMQX broker 的认证用户一致。
- CORS：`config/CorsConfig`，允许 `geosentry.frontend-origin`（默认 `http://localhost:5173`，**支持逗号分隔多域**），允许携带凭证，预检缓存 1h（`maxAge`）。

> ⚠️ 当前 CORS 与安全配置已在持续收敛中；如你在本地开发时遇到跨域或鉴权失败，请优先检查 `FRONTEND_ORIGIN`、`VITE_API_BASE_URL` 与登录态是否一致。生产环境不应保留过宽的跨域白名单。

### 6.4 业务域说明

- **auth**：`POST /api/auth/login` 签发 JWT；登录响应 `LoginResponse{ token, user{name,role,username} }`。JWT 携带 `role` claim；后端从 `PermissionRegistry` 按角色注入权限码 authority，配合 `@EnableMethodSecurity` + `@PreAuthorize` 做方法级授权（权限码与前端 `ROLE_PERMISSION_MAP` 一致）。`POST /api/auth/change-password` 接收 `old_password + new_password`（≥8 字符含字母和数字），成功更新 `sys_user.password_hash` 的 BCrypt 哈希。
- **region**（v1.7.0 新增）：行政区划管理，5 级（province / city / district / town / village），含 `center_lon/center_lat` AI 网格扫描锚点 + `geo_type`（PLAIN / HILL / MOUNTAIN / COASTAL）地貌类型，为 `AiHazardMonitorScheduler` 提供监控锚点。详见 §25。
- **hazard**：灾害点 CRUD、视口查询（PostGIS `ST_MakeEnvelope` + GiST，见 `HazardPointRepository.findInViewport`）、GeoJSON。新增/修改经 `GeometryFactory` 生成 `geom`。导出走 EasyExcel 生成真实 xlsx（`/export`）。v1.6.0 起 `hazard_point.source` 区分数据来源（`AI_MONITOR` / `MANUAL` / `DEMO`），`discovered_at` 是 AI 首次识别时间，`detection_confidence` 是 AI 给出的 0-1 置信度；v1.7.0 增加承灾体字段 `exposure_buildings / exposure_population / land_use_type / historical_max_score / historical_count / last_inspection_device_type`，由 `ExternalDataService` OSM Overpass 自动填入。
- **task**：任务分页/CRUD/状态更新（`PATCH /{id}/status`，已完成置 progress=100）/导出（EasyExcel）。
- **inspection**：GPS 围栏校验（`/inspection/gps-check`，Haversine 距离）、提交巡检记录（`/inspection/submit`，写入时生成 `geom`，围栏内提交会推进任务进度 +15）、按任务/灾害点查询记录。v1.6.0 起 `inspection_record.hazard_code` 允许为 NULL（hazard 可能还没被 AI 监测到）。
- **device**：设备分页（JPA Specification）/视口/CRUD/诊断（`POST /{id}/diagnose`）/导出。**数据上报链路**：`POST /api/devices/{code}/data` 经 `DeviceAuthFilter` 校验 `X-Device-Key` header（匹配 `monitoring_device.api_key` 列），通过后写入 `device_metric` 表并异步触发 AI 异常检测（见 `DeviceDataService`）。写入时**优先解析设备侧时间戳**（`ts` / `recorded_at` / `timestamp`，支持 epoch 秒/毫秒、ISO-8601、`yyyy-MM-dd HH:mm:ss`），作为去重键与上报时间的唯一来源，避免同一批次多指标共享服务端 `now()` 导致误判重复。TDengine 写入改为**异步执行**（`@Async`，独立于 PG 事务，3 次指数退避重试 200/400ms），`TdengineMetricService` 内置 30s 熔断器防止 TDengine 故障拖慢主链路。EMQX MQTT 桥接（`MqttDataSubscriber`）订阅 `geohazard/{deviceCode}/data`，鉴权由 broker 侧 username/password 处理；**解析与入库在独立消费线程池**（有界队列 1000，2 个 worker，队列满丢弃最旧消息并告警），不阻塞 EMQX 回调线程，clientId 带随机后缀支持多实例。**demo 阶段数据源**：`DeviceMetricSimulator`（`@Scheduled(fixedDelayString="${geosentry.device.metric-feed-interval-seconds:300000}")`）按 `device_type` 给每台设备生成对应 `metric_code` 的合成样本（GNSS → disp_rate/disp_total；裂缝计 → crack_width；倾角 → tilt；雨量计 → rain_1h/rain_24h），噪声按 `hazard.risk_level` 放大（高风险×1.8/关注×1.3/正常×0.7）。**指标清理**：`DeviceMetricCleanupScheduler` 默认每日凌晨 3:30 删除超过 `metric-retention-days`（默认 30）的旧样本，避免 `device_metric` 无限膨胀。**设备离线心跳**：`DeviceOnlineChecker` 每 60s 扫描 `monitoring_device.last_report_at` 超过 `offline-threshold-minutes`（默认 5min）的在线设备，置离线并通过 WebSocket 广播。
- **warning**：预警分页（Specification）/视口/详情/**预览（`GET /preview`）**/更新（`PUT /{id}`，admin）/确认/派发/关闭/忽略/解除。状态流转已补全为六态：`待确认 → 已确认 → 处理中 → 已关闭 / 已忽略 / 已解除`（`confirm`/`dispatch`/`close`/`dismiss`/`resolve`）。`close(id, reason, operator)` 按 reason 语义分流，关闭/忽略/解除均落 `closed_at` + `close_reason` + `closed_by`（`warning_event` 表新增两列 + `source` 列）。**预警规则引擎**：`RuleEngineScheduler` 定时评估 `warning_rule`，命中且未闭环时经 `WarningCreateService` 自动生成预警（两阶段：事务外 AI 融合评分 + 事务内写 warning_event/risk_score_log，详见 §10.5），并可联动 AI 融合评分。预警等级文案映射：含「橙」→ orange、含「黄」→ yellow、其余 → blue。**AI 综合分→预警**：`AiWarningScheduler`（`@Scheduled(cron="${geosentry.ai.comprehensive-to-warning-cron:0 */10 * * * *}")`，默认 10 分钟）扫描 `ai_comprehensive_score` 中 `risk_level ∈ {MEDIUM, HIGH, CRITICAL}` 的记录，自动创建 `warning_event`（`source='AI_AUTO'` + `rule_code='AI_COMPREHENSIVE'`），同一 hazard_code 只要存在未关闭 AI_COMPREHENSIVE 预警就不再重复生成（`findOpenWarnings` 批量预取 + Set 判重）。
- **emergency**：应急处置完整 CRUD（分页列表查询 `emergency_event` 表、`GET /{id}` 详情、`POST /{id}/close` 闭环并记录处置小结、`DELETE /{id}` 物理删除、`POST /export` 导出）。`EmergencyController` + `EmergencyService` 已落地，不再依赖 `pageOfSamples` 演示数据。**预警派发联动自动生成应急事件**已实现：`WarningService.dispatch` 检测到橙色/红色预警时自动调用 `EmergencyService.createFromWarning` 创建应急事件，同一预警未闭环前不重复创建（幂等三层防御：exists 检查 + PG 部分唯一索引 + DataIntegrityViolationException 兜底）。
- **dashboard**：overview / charts / analytics 均对真实表聚合（灾害点/设备/预警/任务），见 `DashboardService`——卡片计数与图表聚合全部走 DB 侧 `COUNT` / `GROUP BY`（`countByStatus` / `countDoneByMonth` / `countByLevel` / `countByMonth`），避免 `findAll + stream` 加载大表。`analytics` 端点的 heatmap 按 `hazard_point.region_code` 真实聚合生成 `RegionRisk`（intensity/risk），riskZones 按区域风险分布生成。另有 `POST /overview/export`（总览指标导出）与 `GET /summary`（模块计数 + 特性开关元数据）。死代码 `pageOfSamples` / `notImplemented` 已清理。
- **personnel**：`GET /api/personnel/positions`（`PersonnelController`，**需 `overview:view` 权限**）返回值守人员实时位置，**三层叠加**：① Redis 有实时上报（`POST /positions/report`）时优先读 Redis（**SCAN 非阻塞遍历** `personnel:pos:*`，而非 `KEYS *`）并与 DB 值守花名册合并；② Redis 无数据时走 DB fallback（`inspection_record` 最近签到坐标 + `inspection_task` 关联 `hazard_point` 坐标，预加载 hazard 坐标避免 N+1）；③ 前端演示模式走 `PERSONNEL_ROSTER` mock 轨迹推演。`POST /positions/report`（需 JWT）**限制只能上报自己的位置**（`JWT.username == payload.userId`，管理员可代报），Redis 不可用时优雅降级返回 `cached=false` 不阻塞移动端。
- **attachment**：附件上传已接入 MinIO 真实对象写入（`MinioClient.putObject`），返回 presigned URL；删除时同步删除对象（见 `AttachmentService`）。安全加固：50MB 大小上限、文件扩展名白名单（PDF/Office/图片/zip/常见工程格式）、文件名路径穿越清洗、objectKey 用 UUID 不含原始文件名、启动时预热 bucket。
- **weather**（v1.6.0 新增）：国家气象数据接入（NSMC + NMC），含 6 项产品（卫星云图/全圆盘云图/雷达回波/24h 降水/24h 积雪/干旱监测），详见 §17。
- **audit**（v1.6.0 新增）：`OperationLogArchiver`（`@Scheduled(cron="${geosentry.audit.archive-cron:0 0 3 * * *}")`，默认每日凌晨 3:00）调用 PostgreSQL 函数 `archive_operation_logs(retention_days)`（默认 90 天），将过期审计日志迁移到 `operation_log_archive` 归档表并从主表删除。归档函数与归档表 DDL 见 `infra/postgres/init/003_archive_operation_log.sql`，详见 §27。
- **health**：`GET /api/system/health` → 健康探针（默认检测 PostgreSQL 连通性，`?detailed=true` 补充 JVM 堆/线程数/TDengine 开关）。
- **ai**：`cn.geosentry.ai` 包，包含：
  - `AiServiceClient`（RestClient 调用独立 FastAPI 服务，七大能力：detect / forecast / anomaly / fusion-risk / comprehensive / device-health / disaster-assessment）
  - `AiController`（透传推理 + **自动取数端点** `forecast/auto` / `fusion-risk/auto`，从 DB 拉取历史指标/聚合因子构造请求）
  - `AiAnalysisController`（分析记录查询/删除 + 手动触发批量预测 `POST /analysis/forecast/run`）
  - `AiForecastScheduler`（定时批量预测，默认关闭，复用类级固定线程池，并发度 `AI_FORECAST_PARALLELISM` 默认 8）
  - `AiAnalysisService`（AI 结果持久化到 `ai_analysis_result`，含降级）
  - `AiComprehensiveAnalysisSupport`（综合分析四维度因子聚合）
  - `AiInsightService`（**综合分析 5 分钟调度落库** `ai_comprehensive_score` + `chat()` 自然语言分发，详见 §18）
  - `AiRiskEnricher`（AI 风险字段注入 `HazardDto`）
  - `AiDeviceHealthService`（**设备健康 5 分钟调度落库** `ai_device_health`，详见 §19）
  - `AiHazardMonitorScheduler`（**全国版实时灾害监测**，每 90s 从 `region` 加载监控区域 + 3×3 网格扫描 + 批量调 Python AI + 落库 `hazard_point` source='AI_MONITOR'，详见 §25）
  - `AiWarningScheduler`（**综合分→预警 10 分钟联动**，详见 §10.4）
  - `AiStartupWarmup`（`ApplicationReadyEvent` 启动后 3s 异步执行 insight + deviceHealth 全量预热，避免首次访问空白）
  - `GeoContextResolver`（按 `region_code` 查表 + 按 `hazard_type` 微调海拔/坡度/土壤/植被/距水/地质）
  - `ExternalDataService`（**多源外部数据接入**，7 个真实公开 API + 缓存 + 超时 + 失败降级，详见 §26）
  - `ExternalDataProperties`（`@ConfigurationProperties(prefix="geosentry.external-data")`）

ai-service 返回 snake_case 字段，DTO 通过 `@JsonAlias` 解反序列化、输出统一 camelCase，并带 `model`（模型名）与 `degraded`（是否降级/mock）字段。AI 服务不可用或 `ai.enabled=false` 时抛出 `AiUnavailableException`，上层应降级为纯规则模式。

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

| 方法 | 路径                         | 说明                                                                                                                                                                                                    |
| ---- | ---------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| GET  | `/api/dashboard/overview`    | 总览指标卡片（DB 侧 COUNT 聚合）                                                                                                                                                                        |
| GET  | `/api/dashboard/charts`      | 图表数据（分析页，DB 侧 GROUP BY 按月聚合）                                                                                                                                                             |
| GET  | `/api/dashboard/analytics`   | 统计分析聚合（`{ hazardPoints, overviewStats, charts }`，charts 含 riskTrend/monthlyInspection/warningType/deviceTrend/heatmap/riskZones，均为真实表聚合）                                              |
| GET  | `/api/dashboard/summary`     | 后端元数据：版本号、模块计数、特性开关（ruleEngine/aiFusion/emergencyLinkage/webSocket）                                                                                                                |
| POST | `/api/dashboard/overview/export` | 总览指标导出（EasyExcel xlsx 二进制流，需 `overview:export` 权限，`dashboardApi.exportOverviewReport`）                                                                                            |

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

| 方法   | 路径                     | 说明                                                                                                                                                                                           |
| ------ | ------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| GET    | `/api/emergency`         | 事件列表（查询 `emergency_event` 表，支持分页/page/size/keyword/status）                                                                                                                       |
| GET    | `/api/emergency/{id}`    | 事件详情                                                                                                                                                                                       |
| POST   | `/api/emergency/{id}/close` | 闭环（body 可选 `{ summary }`，置为"已闭环"并记录 `result_summary` + `closed_at`，需 `emergency:close` 权限）                                                                                 |
| DELETE | `/api/emergency/{id}`    | 物理删除事件记录（需 `emergency:close` 权限）；如需"闭环"语义请用 `POST /{id}/close`                                                                                                          |
| POST   | `/api/emergency/export`  | 导出（EasyExcel 真实 xlsx 二进制流，需 `emergency:export` 权限，`dashboardApi.exportEmergencyReport`）                                                                                         |

> 应急事件不提供手动创建端点——统一由预警派发自动生成（`WarningService.dispatch` 检测到橙/红预警时调用 `EmergencyService.createFromWarning`，同一预警未闭环前不重复创建），保持"应急必源于预警"的业务约束。

### 7.10 附件

| 方法   | 路径                                           | 说明                                                                                                      |
| ------ | ---------------------------------------------- | --------------------------------------------------------------------------------------------------------- |
| GET    | `/api/attachments?businessType=&businessCode=` | 附件列表（两个参数均为必填）                                                                              |
| POST   | `/api/attachments` (multipart/form-data)       | 上传（Query 携带 businessType / businessCode，文件字段名 `file`；MinIO 真实对象写入，返回 presigned URL） |
| GET    | `/api/attachments/{id}`                        | 附件详情（每次访问自动刷新 presigned URL，避免 MinIO 7 天过期）                                           |
| POST   | `/api/attachments/{id}/refresh-url`            | 强制刷新附件 presigned URL（前端检测到 403/410 时调用），返回新 URL                                       |
| DELETE | `/api/attachments/{id}`                        | 删除（同步删除 MinIO 对象，失败时兜底仅删元数据）                                                         |

### 7.10.1 区域与实时监测

| 方法 | 路径                                | 说明                                                                                                                                                                                                                                                              |
| ---- | ----------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| GET  | `/api/regions`                      | 行政区划列表（v1.7.0+；支持按 `regionLevel` 过滤 `town`/`village` 等）。`AiHazardMonitorScheduler` 在此表上构建监控锚点。                                                                                                                                       |
| —    | `AiHazardMonitorScheduler`          | **后台调度**（非 API）：每 90s 从 `region` 加载监控区域 + 按 `geo_type` 决定网格步长 + 批量调 Python AI 检测，落库 `hazard_point`（source='AI_MONITOR'），详见 §25。                                                                                                |

### 7.10.2 多源外部数据（v1.7.0+）

> 由 `cn.geosentry.ai.service.ExternalDataService` 统一接入 7 个公开 API，详见 §26。前端无需直接调用——`AiComprehensiveAnalysisSupport` 在综合分析时按需调用并自动缓存。

| 数据源                       | 内部方法                          | 配置开关                                            |
| ---------------------------- | --------------------------------- | --------------------------------------------------- |
| Open-Meteo（气象 + 土壤湿度）| `getMeteorology(lat, lon)`         | `geosentry.external-data.enabled-sources` 含 `openmeteo` |
| NASA Giovanni GPM IMERG        | `getGpmRainfall(lat, lon)`         | 含 `gpm`                                            |
| NASA Giovanni SMAP             | `getSmapSoilMoisture(lat, lon)`    | 含 `smap`                                           |
| USGS FDSN 实时地震           | `getEarthquake(lat, lon, km, mag)` | 含 `usgs`                                           |
| Open-Elevation SRTM 30m DEM   | `getElevation(lat, lon)`           | 含 `openelevation`                                  |
| GDACS 全球灾害警报            | `getGdacsEvents(lat, lon, km)`     | 含 `gdacs`                                          |
| OSM Overpass 暴露度          | `getOsmData(lat, lon, m)`          | 含 `osm`                                            |
| Copernicus Data Space STAC    | `getCopernicusImage(lat, lon)`     | 含 `copernicus`                                     |

### 7.11 AI 接入

> 后端通过 `AiServiceClient`（RestClient）调用独立 `ai-service`（FastAPI）。AI 服务不可用或 `AI_ENABLED=false` 时，业务调用自动降级，不阻塞主流程。公开的推理接口仍返回 503（`AiUnavailableException`），由调用方决定是否降级。

#### 后端代理接口（透传 AI 服务）

| 方法 | 路径                            | 说明                                                |
| ---- | ------------------------------- | --------------------------------------------------- |
| POST | `/api/ai/detect` (multipart)    | 图像识别，字段名 `file`；`modelType` 可选           |
| POST | `/api/ai/forecast`              | 时序预测 `{deviceCode, metric, horizon, history[]}`（显式传历史序列） |
| GET  | `/api/ai/forecast/auto`         | 时序预测自动取数 `?deviceCode=&metric=&horizon=&historyLimit=`（从 `device_metric` 拉最近 N 条历史，DESC→ASC 反转后送 AI；无数据时生成合成序列降级） |
| POST | `/api/ai/anomaly`               | 异常检测 `{deviceCode, data[]}`                     |
| POST | `/api/ai/fusion-risk`           | 融合预警 `{hazardCode, factors{}}`（显式传因子）    |
| GET  | `/api/ai/fusion-risk/auto`      | 融合预警自动取数 `?hazardCode=`（从 hazard + 关联设备最新指标聚合因子：site_grade 由 riskLevel 映射、displacement_rate/total/crack_width 取多设备最大值、rainfall_24h/rainfall_7d 由 `rain_1h` 按 24h/7d 窗口跨设备 SUM；缺失因子合成兜底） |
| GET  | `/api/ai/comprehensive-analysis` | **综合风险分析** `?hazardCode=`（图像现况 + 当地降水量 + 当地地理情况 三维度 + reasoning + suggestedActions 的报告） |
| GET  | `/api/ai/comprehensive-analysis/all` | **落库排行**（按分数倒序），前端各页消费 |
| POST | `/api/ai/comprehensive-analysis/refresh` | **强制全量刷新**（一键分析） |
| POST | `/api/ai/comprehensive-analysis/refresh-one` | 单条刷新 `?hazardCode=` |
| POST | `/api/ai/chat` | **自然语言入口**（POST `{message}` 或 GET `?q=`），返回 `{intent, reply, attachment}`；intents: `emergency`/`ranking`/`comprehensive`/`device_health`/`help`/`fallback` |
| GET  | `/api/ai/disaster-assessment` | **灾害评估 v1.3.0 单条测算** `?hazardCode=`，三件套 risk_level + concern_label + disaster_type |
| GET  | `/api/ai/disaster-assessment/status` | 灾害评估字典（disasterTypes / concernLabels / 权重） |
| POST | `/api/ai/feedback` | 提交 AI 反馈（`{hazard_code, predicted_level, predicted_score, predicted_at, outcome, occurred, confidence?, feedback_by?, note?}`） |
| GET  | `/api/ai/device-health/all` | 全量设备健康（按 healthScore 升序，异常在前） |
| GET  | `/api/ai/device-health` | 单条 `?deviceCode=` |
| POST | `/api/ai/device-health/refresh` | 强制全量重算 |
| POST | `/api/ai/device-health/refresh-one` | 单条重算 `?deviceCode=` |
| GET  | `/api/ai/model/info` | 模型信息（详见 §22.5） |
| POST | `/api/ai/model/reload` | 重载磁盘模型 |
| POST | `/api/ai/analysis/forecast/run` | 手动触发一次批量预测（联调用，仅 ADMIN）            |

> 综合分析响应示例（`comprehensive-analysis`）：
```jsonc
{
  "hazardCode": "GS-001",
  "hazardName": "青龙坡滑坡隐患点",
  "analyzedAt": "2026-09-07T11:00:00Z",
  "overall": { "riskLevel": "HIGH", "riskScore": 72.5, "confidence": 0.78, "model": "heuristic-dimension-fusion", "mock": true },
  "dimensions": {
    "image":         { "score": 65.0, "level": "HIGH", "source": "device_camera", "anomaly_count": 3, "details": "…" },
    "precipitation": { "score": 80.0, "level": "HIGH", "rainfall_24h": 95.5, "rainfall_7d": 230.0, "trend": "高", "details": "…" },
    "geographic":    { "score": 60.0, "level": "MEDIUM", "elevation_m": 850, "slope_deg": 32, "soil_grade": 3, "vegetation_cover": 0.45, "distance_to_water_m": 320, "geology_grade": 2, "geology_label": "沉积岩（砂泥岩互层）", "details": "…" },
    "device":        { "score": 37.0, "confidence": 0.79 }
  },
  "reasoning": ["24h 降雨 95 mm 接近高风险阈值…", "坡度 32° 较陡，强降雨下易诱发浅层滑坡"],
  "suggestedActions": ["加密雨量观测至 1h/次", "派遣现场核查人员，记录裂缝位置/长度/宽度变化"]
}
```

> 三维度因子来源（Java 端 `AiController.comprehensiveAnalysis` 自动聚合）：
>   - **图像现况** `image_anomaly_score`：当前没有设备摄像头/巡检照片表，按 `hazard_point.last_inspection_at` 距今时长算"过期巡检惩罚"（≤7d→0、7-30d→15、>30d→35、无记录→20）。
>   - **当地降水量** `rainfall_24h / rainfall_7d`：来自 `metric_repository.sumByHazardRecentHours(hazardCode, "rain_1h", N)`。
>   - **当地地理情况** `elevation_m / slope_deg / soil_grade / vegetation_cover / distance_to_water_m / geology_grade`：由 `cn.geosentry.ai.service.GeoContextResolver` 按 `region_code`（510322 龙川县 / 510322106 龙安镇 / 510000 四川盆地 / 513300 川西高原）查表 + 按 `hazardType` 微调（滑坡→坡度↑、泥石流→距水↓、塌陷→地质↑）。后续接真实 DEM 后只需替换 `resolve()` 实现。

> 响应均为 camelCase JSON：forecast → `{deviceCode, metric, horizon, predictions[], confidence, model, degraded}`；anomaly → `{deviceCode, isAnomaly, anomalyIndices[], score, model, degraded}`；fusion-risk → `{hazardCode, riskLevel(LOW/MEDIUM/HIGH/CRITICAL), riskScore(0-100), confidence, model, degraded}`；detect → `{detections[{className, confidence, bbox[]}], processedImageUrl, model, degraded, detectionCount}`。`degraded=true` 表示 ai-service 返回的是 mock/降级结果。

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
| POST | `/api/ai/comprehensive` | **综合风险分析**（Python 端，接收 14 因子：image_anomaly_score / rainfall_24h/7d / elevation_m / slope_deg / soil_grade / vegetation_cover / distance_to_water_m / geology_grade / displacement_rate / displacement_total / crack_width / earthquake_mag / site_grade / ...）。返回 overall + 四维度明细 + reasoning + suggestedActions。模型文件 `ai-service/models/comprehensive.json`（XGBoost）不存在时自动降级为维度加权 + 启发式 reasoning 生成。 |
| POST | `/api/ai/device-health/score` | **设备健康评分**（v1.6.0）：worstAnomaly(60%) + forecastRisk(30%) + 离线惩罚 + 覆盖度惩罚，输出 health_score/health_level/reasoning/actions，详见 §19。 |
| GET  | `/api/ai/device-health/status` | 设备健康模型加载状态     |
| POST | `/api/ai/disaster-assessment/score` | **灾害评估 v1.3.0**（hazard + 因子 → 三件套 risk_level + concern_label + disaster_type），详见 §20。 |
| GET  | `/api/ai/disaster-assessment/status` | 灾害评估字典自检（disasterTypes / concernLabels / 权重） |
| POST | `/api/ai/feedback` | 提交反馈（v1.4.0），写入 FeedbackStore（deque(maxlen=10000)），详见 §21.6 |
| GET  | `/api/ai/feedback/stats` | 反馈统计：混淆矩阵 + precision/recall/F1 + adaptive_normalizer 快照 |
| POST | `/api/ai/model/retrain` | 触发 XGBoost fusion 重训（`{min_samples=20, force=false}`），后台训练 + 自动 reload，详见 §22.4 |
| GET  | `/api/ai/model/info` | 模型信息（model_loaded / model_path / version / trained_at / val_metrics / feature_importance / history_files） |
| POST | `/api/ai/model/reload` | 重新加载磁盘模型（清除单例） |
| POST | `/api/ai/sentinel/layers` | **Sentinel-1/2 卫星图层元数据**（v1.7.0+）：返回合成图层定义 |
| GET  | `/api/ai/sentinel/image`     | **卫星图像代理** `?layer=...&lat=...&lon=...&date=...`，返回图像字节流 |
| GET  | `/api/ai/sentinel/composite` | **Sentinel-1+2 双源合成** `?lat=...&lon=...&date=...`：SAR 形变 + 多光谱真彩叠加 |
| GET  | `/api/ai/social_media/alerts` | **社交媒体灾害预警聚合**（v1.7.0+）：按区域聚合微博/推文关键词预警 |
| GET  | `/api/ai/detect/status`   | 图像识别模型加载状态         |
| GET  | `/api/ai/forecast/status` | 预测模型加载状态（model_loaded/model_path/input_size） |
| GET  | `/api/ai/anomaly/status`  | 异常检测模型加载状态         |
| GET  | `/api/ai/fusion/status`   | 融合模型加载状态 + 默认权重          |
| GET  | `/api/ai/docs`        | FastAPI 自动文档（Swagger）  |

> **注意**：ai-service 直接返回 Pydantic 模型的 JSON（**无 ApiResponse 包装层**，字段 snake_case），术语上的"可降级"由 `mock` 字段标识。后端 `AiServiceClient` 通过 `@JsonAlias` 反序列化 snake_case → camelCase，再统一按 `ApiResponse` 包装返回给前端。`AI_CORS_ORIGINS` 环境变量（逗号分隔）控制 ai-service 允许的来源，开发默认 `http://localhost:5173`。

### 7.12 值守人员

生产模式（`VITE_USE_PERSONNEL_LIVE=true` 或 `VITE_USE_BACKEND=true`）下，移动端每 10~30s 上报一次 GPS，后端以 Redis 缓存（TTL 120s）维护实时位置，并通过 WebSocket 广播给前端。位置获取优先级：Redis 实时上报 → DB fallback（巡检签到 / 任务关联灾害点）。

| 方法 | 路径                              | 说明                                                                                                                                                                                                                                                       |
| ---- | --------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| GET  | `/api/personnel/positions`        | 返回值守人员实时位置列表（**需 `overview:view` 权限**）。Redis 有数据时（**SCAN 非阻塞遍历** `personnel:pos:*`）优先读 Redis 并与 DB 值守花名册合并；Redis 无数据时走 DB fallback（inspection_record 最近签到 + inspection_task 关联 hazard_point 坐标，预加载坐标避免 N+1）。 |
| POST | `/api/personnel/positions/report` | 移动端实时位置上报（**需有效 JWT**）。请求体：`{ userId, name, lon, lat, taskCode?, hazardCode?, speed?, heading? }`。**JWT.username 与 payload.userId 必须一致**（管理员可代报），否则 403。写入 Redis key `personnel:pos:{userId}`（TTL 120s），同时通过 WebSocket 广播给前端。Redis 不可用时返回 `cached=false` 优雅降级不阻塞。 |

> 该接口已由 `PersonnelController` 实现。Redis 不可用时优雅降级（返回 DB fallback），不阻塞业务。惰性账号兜底：DB 无任务/无上报时返回 `samplePerson` 演示花名册（周小雨/王建国/陈晓峰）。

### 7.13 实时推送（WebSocket）

后端提供原生 WebSocket 端点，用于推送预警、应急事件、值守人员位置变更。

| 路径  | 协议                              | 说明                                                                                                                                                                                                                                                                        |
| ----- | --------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `/ws` | 原生 WebSocket（非 STOMP/SockJS） | **推荐**：`ws://host:port/ws?ticket=${ticket}`（一次性 60s ticket，安全）；**兼容**：`ws://host:port/ws?token=${authToken}`（旧客户端，JWT 会暴露在服务器日志）。生产环境设 `WS_REQUIRE_TICKET=true` 强制禁用 raw JWT。握手失败返回 401。消息格式 `{ type, data, version }` |

**消息类型**：`warning`（预警状态变更）、`emergency`（应急事件新建/闭环）、`personnel`（值守人员位置更新）、`device`（设备状态变更）。

> **消息类型 payload 示例**（`version` 初始值为 `System.currentTimeMillis()` 毫秒时间戳，完整值如 `1723747200001`，每次广播自动 +1）：
>
> - `warning`: `{"type":"warning","data":{"warningCode":"WG-001","status":"已确认","warningLevel":"红色","title":"青龙坡位移超限"},"version":1723747200001}`
> - `emergency`: `{"type":"emergency","data":{"eventCode":"EM-001","status":"处置中","title":"青龙坡滑坡应急启动"},"version":1723747200002}`
> - `personnel`: `{"type":"personnel","data":{"username":"周小雨","hazardCode":"HZ-001","longitude":107.21,"latitude":30.61,"status":"在现场"},"version":1723747200003}`
> - `device`: `{"type":"device","data":{"deviceCode":"DV-001","onlineStatus":"离线","batteryLevel":23},"version":1723747200004}`

前端（`src/services/_architecture/core/realtimeService.js`）连接时**优先**调 `GET /api/auth/ws-ticket` 获取一次性 ticket，用 `?ticket=` 连接；失败时 fallback 到 `?token=`。重连时重新取 ticket，自动处理 token 刷新。基于 `version` 字段做幂等 upsert，丢弃过期/重复消息。后端 `RealtimeBroadcastService` 在 WarningService 的 confirm/dispatch/close/dismiss/resolve/create、EmergencyService 的 createFromWarning/resolve、DeviceDataService 的在线状态变化检测、PersonnelController 的位置上报中自动触发广播。

> `version` 字段为服务端递增计数器（`AtomicLong.incrementAndGet()`），每次广播自动 +1。**初始值为 `System.currentTimeMillis()`**（毫秒级时间戳，约 1.7e12 起），保证服务重启后 version 仍单调递增，不会因归零导致前端丢弃新消息。前端基于 `version` 做幂等 upsert——只接收 version 更大的新消息，丢弃重复/过期消息。
> **注意**：不要将 version 当作时间戳解析，它只是递增序号。

> 配置开关：环境变量 `WS_REQUIRE_TICKET=true`（prod 推荐）强制仅接受 ticket，禁用 raw JWT；`WEBSOCKET_ENABLED=true` 启用 WebSocket（默认启用，设为 false 时前端自动降级为纯轮询）。

### 7.14 设备数据链路

| 方法 | 路径                                | 说明                                                                                                                                                                                                                                                                                                                                                                 |
| ---- | ----------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| POST | `/api/devices/{code}/data`          | HTTP 上报指标（主通道之一）。鉴权：`DeviceAuthFilter` 校验 `X-Device-Key` header 与 `monitoring_device.api_key` 匹配。未登记 api_key 的设备允许上报但 WARN 提示（便于迁移）。**时间戳解析**：优先取 payload 中 `ts` / `recorded_at` / `timestamp`（epoch 秒/毫秒、ISO-8601、`yyyy-MM-dd HH:mm:ss`），作为去重键；解析失败回退服务端 now()。解析 JSON 写入 `device_metric` 表，**异步**触发 Isolation Forest 异常检测（命中则保存 AI 分析记录）、**异步**写入 TDengine（3 次指数退避重试）。 |
| —    | `MQTT: geohazard/{deviceCode}/data` | EMQX 桥接（`MqttDataSubscriber`），Spring Boot 启动时自动订阅（`emqx.enabled=true`）。鉴权由 broker 侧 username/password 处理（代码已预留 `emqx.username`/`emqx.password` 参数）。**解析与入库在独立消费线程池**（有界队列 1000、2 个 worker，队列满丢弃最旧消息并告警），避免阻塞 EMQX 回调线程；clientId 带随机后缀支持多实例。EMQX 不可达时仅 WARN + 指数退避重连（上限 10min），不阻塞应用启动。 |

**TDengine 时序存储**（已接入主链路）：`DeviceDataService.ingest` 在写入 PostgreSQL 的 `device_metric` 表后，**自动异步调用** `TdengineMetricService.writeMetric()` 写入 TDengine。启用方式：

1. Maven 依赖已在 `backend/pom.xml` 声明（compile scope，自动打包进 fat jar，**无需手动下载**）：`com.taosdata.jdbc:taos-jdbcdriver:3.3.0`；
2. 配置 `tdengine.enabled=true`；
3. 异步写入 + 3 次指数退避重试（200/400ms），TDengine 不可用时不影响 PG 事务（日志 WARN）；
4. **熔断器**：连续失败打开 30s 熔断（`circuitOpen`），到期自动尝试恢复，防止 TDengine 故障拖慢主链路；
5. 数据库与超级表 `device_metric` 由 `infra/tdengine/init.sql` 在 TDengine 容器**首次启动时**创建（docker-entrypoint-initdb.d），子表 `device_metric_{deviceCode}` 在首次写入时自动创建（`CREATE TABLE IF NOT EXISTS ... USING device_metric TAGS(...)`，子表名缓存避免重复 DDL）。

> **双通道协调策略**：HTTP 为主动上报（设备批量推送），MQTT 为实时补充（broker 转发后端订阅）。
> 去重语义：PostgreSQL 唯一索引 `idx_device_metric_unique(device_code, metric_code, recorded_at)` 保证幂等，
> 重复到达的第二条被 catch 并跳过（DEBUG 日志），保留先到者。
> **时间戳约定**：`recorded_at` 优先采用设备侧时间戳（防重发、双通道同批冲突）；设备时钟与服务器偏差 < 1s 时精度到秒仍可能视为重复，这是设计预期。若设备需高精度时间，建议毫秒级上报（epoch ms）。

> ⚠️ **上报频率警告**：`recorded_at` 在数据库中精度截断到秒（`TIMESTAMPTZ` 无显式精度）。位移计、雨量计等传感器若以 >1Hz 频率上报，同一秒内的多个不同样本会被唯一索引判定为重复而丢弃——**不会报错、不会返回 4xx，静默丢失**。当前规避手段：
>
> 1. 在数据接入层做**聚合降采样**（如 1Hz 均值/首尾值）；
> 2. 使用边缘网关采集后统一上报；
> 3. v2 规划改为 `TIMESTAMPTZ(3)` 毫秒精度 + `received_at` 辅助去重字段。

> **device_metric 表结构**：`schema.sql` 中 `recorded_at TIMESTAMPTZ NOT NULL` + `UNIQUE (device_code, metric_code, recorded_at)`；`value NUMERIC(16,4)` 指标值；`quality_code VARCHAR(8)` 数据质量标记。

---

## 8. 数据库设计

### 8.1 表清单（`backend/src/main/resources/schema.sql`）

| 表                     | 用途                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| ---------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `sys_role`             | 角色（menu_permissions / button_permissions）                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `region`               | **行政区划**（v1.7.0+）。5 级（province / city / district / town / village），含 `center_lon`/`center_lat` AI 网格扫描锚点 + `geo_type` 地貌类型（PLAIN/HILL/MOUNTAIN/COASTAL），为 `AiHazardMonitorScheduler` 提供监控锚点                                                                                                                                                                                                                                                                                                       |
| `sys_user`             | 用户                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| `sys_menu`             | 菜单/权限点                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `hazard_point`         | 灾害点（含 `geom geometry(Point,4326)` PostGIS 空间字段）。v1.6.0+ 增加 `source`（AI_MONITOR/MANUAL/DEMO）+ `discovered_at`（AI 首次识别时间）+ `detection_confidence`（0-1 AI 置信度）；v1.7.0+ 增加承灾体字段 `exposure_buildings / exposure_population / land_use_type / historical_max_score / historical_count / last_inspection_device_type`                                                                                                                                                                       |
| `inspection_task`      | 巡检任务（含 `hazard_code` 关联字段，供值守人员位置 fallback 使用）                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| `inspection_record`    | 巡检上报记录（签到坐标/距离/异常标记；v1.6.0+ `hazard_code` 允许为 NULL——AI 监测未发现时也能上报）                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `monitoring_device`    | 监测设备（含 longitude/latitude/geom PostGIS 字段、`api_key VARCHAR(128)` 鉴权密钥列）                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| `device_metric`        | **设备上报指标样本**（device_code/metric_code/value，供规则引擎消费）                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| `warning_rule`         | 预警规则（metric_code + expression，由规则引擎 `RuleEngineScheduler` 评估）                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `warning_event`        | 预警事件（六态状态机：待确认/已确认/处理中/已关闭/已忽略/已解除；含 `close_reason` + `closed_by` 审计字段；`source` 列标记来源（RULE_ONLY / AI_AUTO / AI_DEGRADED / MANUAL）；v1.6.0+ 新增 `AI_COMPREHENSIVE` 规则码来源——`AiWarningScheduler` 10 分钟联动自动生成；rule_snapshot 文本快照）                                                                                                                                                                                                                                |
| `emergency_event`      | 应急事件（完整 CRUD，`POST /{id}/close` 闭环时标记 status=已闭环；含 `hazard_code` 关联）                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `file_attachment`      | 附件元数据（storage_path/file_url，对象真实写 MinIO；file_type 已放宽至 128 字符容纳长 MIME）                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `risk_score_log`       | AI 融合评分原始快照，字段：score(NUMERIC 概率值)、risk_level(AI/规则判定等级)、source(VARCHAR 来源标记：**AI_FUSED / AI_DEGRADED / RULE_ONLY** / MANUAL)、factors(JSONB 融合因子快照)、calculated_at。`WarningCreateService` 每次 AI 融合评分后写入（AI 不可用时也写 RULE_ONLY 记录保证可回溯），用于模型迭代回溯训练集。常用查询：`SELECT * FROM risk_score_log WHERE hazard_code=? ORDER BY calculated_at DESC;` ；按来源过滤：`WHERE source='AI_FUSED'` / `WHERE source='AI_DEGRADED'` 筛选 AI 降级时段、`WHERE source='RULE_ONLY'` 筛选纯规则时段 |
| `operation_log`        | 统一操作审计日志表（**已落地**）。`OperationLogAspect` AOP 切面按**类名后缀 `*Controller`** 自动拦截所有 Controller 的写方法（不再依赖 `@PreAuthorize` 注解），记录 operator（从 JWT 解析）/ module / action / HTTP 方法 / URL / 耗时 / IP。密码等敏感字段在请求体中被自动截断，审计失败不影响主流程。**每日 3:00 由 `OperationLogArchiver` 自动归档**：调用 PostgreSQL 函数 `archive_operation_logs(retention_days)`（默认 90 天），迁移到 `operation_log_archive` 并从主表删除。详见 §27。                                                                                                            |
| `ai_analysis_result`   | AI 分析结果（image/forecast/anomaly/fusion，result_json 文本）                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `ai_comprehensive_score` | **AI 综合分析落库表**（v1.6.0+）。`AiInsightService` 每 5 分钟全量刷所有 hazard，存：risk_level(LOW/MEDIUM/HIGH/CRITICAL) / risk_score / disaster_type(滑坡/崩塌/泥石流/地裂缝/地面沉降/塌陷/其他) / concern_label(稳定/关注/预警/异常严重) / disaster_type_source(declared/inferred/low_confidence/ambiguous) / disaster_type_confidence / device_available / dimensions_json / reasoning_json / suggested_actions_json / confidence / model / degraded / analyzed_at。`(hazard_code)` UNIQUE。详见 §18。                                                                                                                                                |
| `ai_device_health`     | **AI 设备健康评分落库表**（v1.6.0+）。`AiDeviceHealthService` 每 5 分钟全量刷所有 monitoring_device，存：health_level(HEALTHY/ATTENTION/WARN/CRITICAL) / health_score(0-100，越高越健康) / anomaly_score / forecast_risk / metric_coverage / last_anomaly_at / dimensions_json / reasoning_json / suggested_actions_json。`(device_code)` UNIQUE。详见 §19。                                                                                                                                                                       |

### 8.2 关键索引

```
-- 空间（GiST，EPSG:4326）
idx_hazard_geom   hazard_point USING GIST (geom)
idx_record_geom   inspection_record USING GIST (geom)
idx_device_geom   monitoring_device USING GIST (geom)

-- v1.7.0 region AI 监测锚点索引
idx_region_level           region(region_level)
idx_region_parent          region(parent_id)
idx_region_monitorable     region(region_level, geo_type) WHERE region_level IN ('village','town','district')

-- 常规查询
idx_hazard_region_level   hazard_point(region_code, risk_level)
idx_hazard_type           hazard_point(hazard_type)
-- v1.6.0 AI 监测实时灾害列表专用
idx_hazard_source         hazard_point(source, discovered_at DESC)
idx_hazard_discovered     hazard_point(discovered_at DESC) WHERE source = 'AI_MONITOR'
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
-- 规则引擎按 hazard 查询最新指标（findLatestByDeviceCodes + sumByHazardRecentHours）
idx_device_metric_hazard         device_metric(hazard_code)
idx_device_metric_hazard_recorded device_metric(hazard_code, recorded_at DESC)
-- v1.6.0 AI 综合分析 + 设备健康落库索引
uq_ai_comprehensive_hazard        ai_comprehensive_score(hazard_code) UNIQUE
idx_ai_score_level                ai_comprehensive_score(risk_level)
idx_ai_score_analyzed_at          ai_comprehensive_score(analyzed_at DESC)
idx_ai_score_disaster_type        ai_comprehensive_score(disaster_type)
idx_ai_score_concern_label        ai_comprehensive_score(concern_label)
uq_ai_device_health_device        ai_device_health(device_code) UNIQUE
idx_ai_device_health_level        ai_device_health(health_level)
idx_ai_device_health_analyzed_at  ai_device_health(analyzed_at DESC)
```

> 唯一索引 `idx_device_metric_unique` 已覆盖 `(device_code, metric_code, recorded_at)` 等值查询路径，因此不另建普通复合索引；新增的 `idx_device_metric_hazard_recorded` 用于规则引擎/AI 聚合按 hazard 拉最近时间窗的降序查询。

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
- **角色首页**：admin / attendant → `/dashboard`；maintenance → `/dashboard/devices`（运维员首屏直接进入设备监控）。`maintenance` 也可访问 `/dashboard` 主页。
- **菜单可见性** `ROLE_MENU_MAP`（路由前缀为 `/dashboard`，前导匹配 = 前缀路径均视为可见，如 `/dashboard/hazards` 包含 `/dashboard/hazards/:id`）：
  - admin：全部 10 个模块（`/dashboard`、`/dashboard/hazards`、`/dashboard/tasks`、`/dashboard/devices`、`/dashboard/warnings`、`/dashboard/weather`、`/dashboard/ai-insight`、`/dashboard/emergency`、`/dashboard/analytics`、`/dashboard/settings`）
  - attendant：除「设备」外 9 个模块（含 `/dashboard/settings`，不含 `/dashboard/devices`）
  - maintenance：总览/灾害点/设备/天气/AI 综合分析/统计分析/设置 7 个模块（无任务/预警/应急）
- **权限码** `ROLE_PERMISSION_MAP`（形如 `模块:动作`）：
  - admin 拥有全部权限：`overview:view/export`, `hazards:view/create/edit/delete/export`, `tasks:view/create/edit/delete/export`, `devices:view/create/edit/delete/export`, `warnings:view/confirm/dispatch/close/export`, `emergency:view/dispatch/close/export`, `analytics:view/export`, `weather:view/refresh`, `ai:insight:view/refresh`, `settings:view`
  - attendant：除 `hazards:delete`、`tasks:delete`、`devices:*`、`emergency:export`、`weather:refresh` 外其余权限
  - maintenance：只读 + 设备全部 CRUD + 概览/分析/AI 综合分析/天气/设置
- 路由守卫增加 **JWT 过期本地校验**（`isTokenExpired` base64 解码 exp，无需第三方 JWT 库）与 `redirectToLogin` 并发锁（防止多个 401 同时跳转）。

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
- **生产模式**（`VITE_USE_PERSONNEL_LIVE=true` **或 `VITE_USE_BACKEND=true`**）：前端轮询 `GET /personnel/positions` 展示实时位置（Redis 优先，DB fallback 兜底），禁用本地轨迹推演。移动端上报已落地：通过 `POST /personnel/positions/report`（需 JWT，**只能上报自己的位置**，管理员可代报）每 10~30s 上传 GPS 坐标，后端写入 Redis key `personnel:pos:{userId}`（TTL 120s）并通过 WebSocket 广播给前端。

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
- `warning_event` 表新增 `close_reason VARCHAR(255)`、`closed_by VARCHAR(64)` 与 `source VARCHAR(32)` 三列（`schema.sql` 已加 `ADD COLUMN IF NOT EXISTS`），关闭/忽略/解除均记录原因与操作人；`source` 标记预警来源（规则引擎生成：`RULE_ONLY` / `AI_AUTO` / `AI_DEGRADED`；手工创建：`MANUAL`），支撑审计与统计。
- 前端 `dashboardApi` 已同步实现 `dismissWarning` / `resolveWarning` / `closeWarning(payload.reason)`，调用后端专用端点；Analytics 页 mock fallback 与后端真实响应结构对齐。
- 「派发时按等级联动生成应急事件」已实现：`WarningService.dispatch` 检测到橙色/红色预警时自动调用 `EmergencyService.createFromWarning` 创建应急事件。**幂等机制三层防御**：(1) 应用层快速路径 `existsByWarningCodeAndStatusNot` 检查；(2) 数据库部分唯一索引 `CREATE UNIQUE INDEX ... ON emergency_event(warning_code) WHERE status != '已闭环'`（PostgreSQL）强制同一预警最多一个未闭环事件；(3) 异常兜底 `catch DataIntegrityViolationException` 处理并发竞态，返回 null 表示"已存在无需新建"。

### 10.5 预警规则引擎

`warning_rule` + `RuleEngineScheduler`（`@Scheduled`，默认每 30s，可用 `RULE_ENGINE_CRON` 调整）实现「自动触发 → 去重 → 可选 AI 融合」：

1. 拉取 `warning_rule` 中 `enabled=true` 的规则。
2. 用轻量投影 `findDeviceCodeHazardCodePairs()` 取设备/灾害点映射，再批量拉取各设备**最新**指标（`findLatestByDeviceCodes`，`DISTINCT ON (device_code, metric_code)`）。
3. 按 `hazard_code` 归并指标快照，`RuleEvaluator` 解析表达式求值：
   - 简单形式：`"disp_rate > 5"`
   - JSON 结构化（规划）：`{"operator":"AND","conditions":[{"metric":"..","comparator":">","value":..}]}`

> **当前限制**：规则引擎目前仅支持简单比较表达式（如 `disp_rate > 5`、`rainfall >= 50.0`），
> 由 Spring Expression Language 解析。JSON 结构化表达式（支持 AND/OR 组合、阈值范围、指标组合）
> 已列为规划项，预计 v2 版本引入（表达式 AST 解析器）。
> 当前建议通过多条规则组合（rule-engine-cron 每 30s 轮询）实现复杂条件。

4. **去重**：一次性批量预取所有未关闭预警（`findOpenWarnings(ruleCodes, hazardCodes, excludedStatuses)`，排除 已关闭/已忽略/已解除，**NULL-safe 处理 `status IS NULL`**），构建 `ruleCode|hazardCode` 集合做 O(1) 判重，替代循环内逐条 exists 查询。
5. 命中后委托 **`WarningCreateService`** 生成预警（独立 bean 保证 `@Transactional` 通过代理生效，规则引擎内部 `this` 调用会绕过代理）：
   - **阶段 1（事务外）**：AI 启用时调 `fusionRisk` 融合评分，`riskLevel` 映射升级等级（CRITICAL→红色预警、HIGH→橙色预警、MEDIUM 且规则含"蓝"→黄色预警）；AI 不可用/失败时沿用规则等级（`source=RULE_ONLY`），AI 降级结果标记 `AI_DEGRADED`。
   - **阶段 2（事务内）**：`warning_event` + `risk_score_log`（AI 未执行也写 RULE_ONLY 记录）原子写入，初始状态**待确认**。
6. **AI/规则输出均不自动派发，强制人工复核**。

表达式求值与结构见 `RuleEvaluator`、`RuleEngineScheduler`、`WarningCreateService`。开关：`geosentry.warning.rule-engine-enabled`（默认 true）。单条预警创建失败或 AI 故障均不中断整个调度周期。

---

## 11. 配置与环境变量

### 11.1 前端（`.env` / `.env.example`）

| 变量                           | 默认                                | 说明                                     |
| ------------------------------ | ----------------------------------- | ---------------------------------------- |
| `VITE_API_BASE_URL`            | `http://localhost:8080/api`         | 后端基址（后端默认宿主机端口 8080；prod 覆盖文件为 8081 时同步修改）               |
| `VITE_WS_URL`                  | —                                   | WebSocket 地址（实时）                   |
| `VITE_REALTIME_POLL_INTERVAL`  | 15000                               | 设备/汇总基础轮询兜底(ms)                |
| `VITE_WARNING_POLL_INTERVAL`   | 10000                               | 预警核心数据高频轮询(ms)                 |
| `VITE_PERSONNEL_POLL_INTERVAL` | 30000                               | 值守人员位置轮询(ms)                     |
| `VITE_USE_PERSONNEL_LIVE`      | `false`                             | 生产模式：人员坐标由移动端上报后端；**置为 true 或 `VITE_USE_BACKEND=true` 时自动启用**       |
| `VITE_TDT_TOKEN`               | —                                   | 天地图 token                             |
| `VITE_TDT_ATTRIBUTION`         | 地图数据                            | 地图版权                                 |
| `VITE_MAP_TILE_URL`            | OSM                                 | 矢量底图兜底                             |
| `VITE_SATELLITE_TILE_URL`      | ESRI World Imagery                   | 影像兜底（v1.7.1 起支持 7 个卫星源优先级切换，见 §19）  |
| `VITE_SENTINEL_TILE_URL`       | ArcGIS World_Imagery                | Sentinel-2 真彩图源（v1.7.1）                           |
| `VITE_MAPBOX_TOKEN`            | —                                   | Mapbox Satellite-v9 启用凭据（v1.7.1）                  |
| `VITE_CESIUM_ION_TOKEN`        | —                                   | Cesium Ion token                         |
| `VITE_WEATHER_API_KEY`         | —                                   | 天气 API Key                             |
| `VITE_WEATHER_API_URL`         | OpenWeatherMap                      | 天气接口地址                             |
| `VITE_USE_BACKEND`             | 自动探测 / 可显式设为 true 或 false | 真实后端模式控制，默认启动时探测健康状态 |

### 11.2 后端（`application*.yml`，支持环境变量覆盖）

| 变量                                    | 默认                                    | 说明                                                                                                           |
| --------------------------------------- | --------------------------------------- | -------------------------------------------------------------------------------------------------------------- |
| `SERVER_PORT`                           | 8080                                    | 后端监听端口（容器内/宿主机）；prod 覆盖文件映射为 8081                                                    |
| `FRONTEND_ORIGIN`                       | http://localhost:5173                   | CORS + WebSocket Origin 白名单，支持逗号分隔多域                                                          |
| `JWT_SECRET`                            | 必须 ≥ 32 字符强随机                    | JWT 签名密钥。长度不足或匹配弱密码时应用启动失败。生成：`openssl rand -base64 48`；docker-compose dev 不再显式注入，回落到 application.yml 开发默认值 |
| `JWT_EXPIRE`                            | PT2H                                    | 过期时长（ISO-8601 Duration）                                                                                  |
| `JWT_ISSUER`                            | geosentry                               | JWT 签发者                                                                                                     |
| `DEV_USERS_ENABLED`                     | false（默认）/ 本地脚本 true            | 是否启用硬编码账号 fallback。**默认关闭**（application.yml），`run-backend.ps1` 本地开发显式 true；prod ProductionConfigValidator 强制 false |
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
| `TDENGINE_ENABLED`                      | false                                   | 是否启用 TDengine 时序写入。JDBC 驱动已 compile scope 打包进 fat jar，直接设为 true 即可                       |
| `WS_REQUIRE_TICKET`                     | false（dev）/ true（prod）              | 强制 WebSocket 连接只能使用一次性 ticket（禁止 raw JWT query）。prod 推荐开启                                  |
| `WEBSOCKET_ENABLED`                     | true                                    | WebSocket 实时推送总开关。设为 false 时后端不启动 WebSocket 端点，前端会自动降级到纯轮询模式                   |
| `AI_SERVICE_BASE_URL`                   | http://localhost:8000/api/ai            | AI 推理服务地址（Docker 内 http://ai-service:8000/api/ai）                                                     |
| `AI_ENABLED`                            | true                                    | AI 功能总开关；关闭后降级为纯规则模式                                                                          |
| `AI_TIMEOUT_MS`                         | 30000                                   | AI 调用超时（毫秒）                                                                                            |
| `AI_SCHEDULER_ENABLED`                  | false                                   | 定时批量预测开关（默认关闭）                                                                                   |
| `AI_FORECAST_HORIZON`                   | 7                                       | 预测未来 N 天                                                                                                  |
| `AI_HISTORY_WINDOW`                     | 30（`geosentry.ai.history-window`，代码内 @Value 默认值）  | 定时批量预测每次拉取的历史序列长度；少于 5 条则跳过该设备（AiForecastScheduler）                             |
| `AI_FORECAST_PARALLELISM`               | 8（`geosentry.ai.forecast-parallelism`，代码内 @Value 默认值） | 批量预测并发线程数（类级固定线程池，daemon 线程），最小 2                                                     |
| `AI_FORECAST_CRON`                      | `0 0 2 * * *`                           | 定时预测 cron 表达式                                                                                           |
| `AI_CORS_ORIGINS`                       | http://localhost:5173                   | **ai-service（Python 侧）** CORS 允许来源，逗号分隔；docker-compose dev 默认 `http://localhost:5173,http://localhost:8080` |
| `AI_COMPREHENSIVE_CRON`                 | `0 */5 * * * *`                         | AI 综合分析全量刷新 cron（v1.6.0+，`AiInsightService.refreshAll`）                                          |
| `AI_TO_WARNING_CRON`                    | `0 */10 * * * *`                        | AI 综合分→预警联动 cron（v1.6.0+，`AiWarningScheduler`）                                                    |
| `AI_TO_WARNING_ENABLED`                 | true                                    | AI→预警联动总开关；关闭则 `AiWarningScheduler` 不执行                                                       |
| `AI_DEVICE_HEALTH_ENABLED`              | true                                    | AI 设备健康评分调度总开关                                                                                     |
| `AI_DEVICE_HEALTH_CRON`                 | `0 */5 * * * *`                         | AI 设备健康评分调度 cron                                                                                      |
| `AI_HAZARD_MONITOR_ENABLED`             | true                                    | **v1.6.0/v1.7.0 全国版实时灾害监测调度总开关**（`AiHazardMonitorScheduler`）                                  |
| `AI_HAZARD_MONITOR_INTERVAL_MS`         | 90000                                   | 实时灾害监测调度间隔（毫秒，90s）                                                                            |
| `AI_HAZARD_MONITOR_INITIAL_DELAY_MS`    | 8000                                    | 实时灾害监测启动后首次延迟（毫秒）                                                                          |
| `AI_HAZARD_MONITOR_MAX_REGIONS`         | 200                                     | 每次最多扫描 region 数（防超载）                                                                             |
| `AI_HAZARD_MONITOR_INCLUDE_PLAIN`       | false                                   | 是否包含 PLAIN 区域（默认跳过）                                                                              |
| `AI_HAZARD_MONITOR_MIN_LEVEL`           | town                                    | 最小行政级别过滤（village/town/district/city）                                                              |
| `AI_HAZARD_MONITOR_MIN_RISK_LEVEL`      | HIGH                                    | AI 检测阈值（CRITICAL/HIGH/MEDIUM/LOW），低于此等级不落库                                                  |
| `AI_STARTUP_WARMUP_ENABLED`             | true                                    | 启动后立即跑一次综合分析 + 设备健康，避免首次访问空白                                                       |
| `EXTERNAL_DATA_ENABLED`                 | true                                    | **v1.7.0 多源外部数据接入总开关**（`ExternalDataService`）                                                   |
| `EXTERNAL_DATA_TIMEOUT`                 | 10                                      | 外部 API HTTP 超时（秒）                                                                                     |
| `EXTERNAL_DATA_CACHE_TTL`               | 300                                     | 外部 API 缓存 TTL（秒）                                                                                      |
| `EXTERNAL_DATA_SOURCES`                 | `openmeteo,usgs,openelevation,gdacs,osm,gpm,smap,copernicus` | 启用的数据源（逗号分隔）                                                                                |
| `OPEN_METEO_URL`                        | `https://api.open-meteo.com/v1/forecast`| Open-Meteo API 端点（默认即可）                                                                              |
| `USGS_FDSN_URL`                         | `https://earthquake.usgs.gov/fdsnws/event/1/query` | USGS 地震查询 API 端点                                                                                |
| `OPEN_ELEVATION_URL`                    | `https://api.open-elevation.com/api/v1/lookup` | Open-Elevation SRTM API 端点                                                                          |
| `AUDIT_ARCHIVE_CRON`                    | `0 0 3 * * *`                           | **v1.6.0+ 审计日志归档 cron**（`OperationLogArchiver`，每日凌晨 3:00）                                     |
| `AUDIT_LOG_RETENTION_DAYS`              | 90                                      | 主表保留天数（超过后迁移到 `operation_log_archive`）                                                         |
| `RULE_ENGINE_ENABLED`                   | true                                    | 预警规则引擎开关                                                                                               |
| `RULE_ENGINE_CRON`                      | `*/30 * * * * *`                        | 规则引擎评估 cron                                                                                              |
| `DEVICE_OFFLINE_THRESHOLD_MIN`          | 5                                       | 设备离线判定阈值（分钟），`DeviceOnlineChecker` 扫描 `last_report_at`                                          |
| `DEVICE_OFFLINE_CHECK_INTERVAL_SEC`     | 60                                      | 设备离线检测扫描间隔（秒）                                                                                     |
| `METRIC_SIMULATOR_ENABLED`              | true                                    | 设备指标喂入器开关（demo 阶段 `DeviceMetricSimulator`，生产请关闭）                                          |
| `METRIC_FEED_INTERVAL_SECONDS`          | 300                                     | 设备指标喂入间隔（秒，5 分钟）                                                                                |
| `METRIC_CLEANUP_ENABLED`                | true                                    | 设备指标历史数据自动清理开关                                                                                   |
| `METRIC_RETENTION_DAYS`                 | 30                                      | 设备指标保留天数（超过后自动删除）                                                                             |
| `METRIC_CLEANUP_CRON`                   | `0 30 3 * * *`                          | 设备指标清理 cron（默认凌晨 3:30）                                                                             |
| `WS_TICKET_TTL_SEC`                     | 60                                      | WebSocket 一次性 ticket 有效期（秒）                                                                           |
| `TZ`                                    | Asia/Shanghai                           | 时区                                                                                                           |

> 配置文件选择与初始化策略：
>
> - **默认 `application.yml`**：`spring.sql.init.mode=always`，每次启动幂等执行 `schema.sql` + `data.sql`（建表 + 种子）。适用于本地开发（`mvn spring-boot:run`）。
> - **`application-docker.yml`**：docker-compose dev 模式（`SPRING_PROFILES_ACTIVE=docker`），**未覆盖** `spring.sql.init.mode`，继承默认值 `always`。首次启动容器时自动建表 + 种子；已存在数据卷时 `ON CONFLICT DO NOTHING` 跳过种子重复插入。重建数据库：`docker compose down -v && docker compose up -d`。
> - **`application-prod.yml`**：`spring.sql.init.mode=never`，生产首次部署**必须手动执行** schema.sql / data.sql（详见 §14 第 43 条）。
>
> 其他 profile 差异：dev 显示 SQL、`cn.geosentry` 调试日志；prod 关闭 SQL、WARN 日志、禁用 SQL init。docker 覆盖容器网络地址（postgres/redis/minio/emqx/tdengine）。所有 profile 均输出日志到 `logs/backend.log`（相对 `working_dir`，Docker 容器内 `/app/backend/logs/`，宿主机 `backend/logs/`）。

---

## 12. Docker 部署

`docker-compose.yml` 编排服务：postgres（PostGIS）、redis、minio、emqx、tdengine、backend、frontend、ai-service（端口见表 §4.5）。

```bash
docker compose up -d            # 启动全部
docker compose ps               # 查看状态
docker compose logs backend     # 查看后端日志
docker compose down             # 停止
```

Volumes：`postgres_data`、`redis_data`、`minio_data`、`emqx_data/log`、`tdengine_data/log`、`m2_cache`、`node_modules`。

网络：`geosentry-net`。

PostGIS 初始化：`infra/postgres/init/001_create_extensions.sql`（首次建卷时由 docker-entrypoint-initdb.d 启用 `postgis`、`uuid-ossp`；新卷还必须至少启动一次后端，由 `spring.sql.init` 完成建表与种子数据初始化）。TDengine：`infra/tdengine/init.sql` 挂载到容器 `docker-entrypoint-initdb.d`，**首次启动创建库与 `device_metric` 超级表**，并配置 healthcheck（`tdengine -s show servers`）。若希望重建数据库：

```bash
docker compose down -v            # 删除数据卷（含 m2_cache/node_modules，会触发重新构建）
docker compose up -d              # 重新初始化（dev 模式：SQL init 自动建表 + 种子）
```

> **dev/docker 模式的数据库初始化**：默认 `application.yml` 中 `spring.sql.init.mode=always`，docker-compose dev 使用 `SPRING_PROFILES_ACTIVE=docker` 未覆盖此项，**首次启动自动建表 + 种子数据**。已存在数据卷时 `CREATE TABLE IF NOT EXISTS` / `ON CONFLICT DO NOTHING` 保证幂等。生产模式 `mode=never`，必须手动执行（见 §14 第 43 条）。

> **JWT_SECRET**：dev 编排**不显式注入**（注释掉），回落到 `application.yml` 的开发默认值（`geosentry-dev-secret-do-not-use-in-prod-...`），避免本地重复配置；生产编排必须通过 `.env` / 环境变量覆盖为强随机密钥，否则 `ProductionConfigValidator` 会拒绝启动。

### 生产模式 docker-compose 覆盖文件

项目根目录提供了 `docker-compose.prod.yml`，与 `docker-compose.yml` 配合使用时覆盖 backend 和 frontend 服务为生产模式：

| 项目                   | docker-compose.yml（dev）                         | docker-compose.prod.yml（覆盖）                                          |
| ---------------------- | ------------------------------------------------- | ------------------------------------------------------------------------ |
| backend 镜像           | maven:3.9.9-eclipse-temurin-17（源码挂载运行）    | Dockerfile.backend 多阶段构建 jar 镜像                                   |
| 后端宿主机端口         | 8080                                              | **8081**                                                                 |
| 前端宿主机端口         | 5173                                              | **80**                                                                   |
| SPRING_PROFILES_ACTIVE | docker                                            | **prod**（触发 ProductionConfigValidator）                               |
| 安全默认值             | DEV_USERS_ENABLED=false, DEVICE_AUTH_STRICT=false | DEV_USERS_ENABLED=false, DEVICE_AUTH_STRICT=true, WS_REQUIRE_TICKET=true |
| 前端构建               | 无 mock 拦截                                      | VITE_USE_BACKEND!=true 时构建失败                                        |
| 健康检查               | backend 有（curl health），前端无                 | backend + frontend 均有 healthcheck                                      |
| 资源限制               | 无                                                | 2 CPU / 1GB 内存上限，预留 0.5 CPU / 256MB                               |
| 数据卷                 | m2_cache / node_modules（源码挂载）               | backend_logs（仅日志卷）                                                 |

启动方式：

```bash
# 日常启动（首次部署除外）
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build

# 首次部署：先手动初始化 PostgreSQL（详见 §14 第 43 条），再启动全栈
docker compose -f docker-compose.yml up -d postgres
# ... 执行 schema.sql / data.sql / 修改种子密码 ...
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
```

`docker-compose.prod.yml` 关键配置片段（**完整文件见仓库根目录**，此处仅展示变更部分）：

```yaml
services:
  backend:
    build:
      context: . # Dockerfile.backend 在根目录，内部 COPY backend/pom.xml 和 backend/src
      dockerfile: Dockerfile.backend
    environment:
      SPRING_PROFILES_ACTIVE: prod
      DEV_USERS_ENABLED: "false"
      DEVICE_AUTH_STRICT: "true"
      WS_REQUIRE_TICKET: "true"
    depends_on:
      postgres:
        condition: service_healthy # pg_isready
      redis:
        condition: service_healthy # redis-cli ping
      minio:
        condition: service_healthy # MinIO /minio/health/live
      emqx:
        condition: service_healthy # emqx ping（在 docker-compose.yml 中定义）
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

  frontend:
    build:
      context: .
      dockerfile: Dockerfile.frontend
    healthcheck:
      # Dockerfile.frontend 基于 nginx:alpine，含 wget
      test: ["CMD", "wget", "-qO-", "http://localhost/"]
      interval: 30s
      timeout: 5s
      retries: 5
      start_period: 15s
    restart: unless-stopped
```

> **关于 EMQX 的 healthcheck**：`condition: service_healthy` 要求被依赖服务必须定义 healthcheck。EMQX 在基础 `docker-compose.yml` 中已配置 `test: ["CMD", "emqx", "ping"]`，prod 覆盖文件**不重复定义**，自动继承 dev 的健康检查。MinIO 同理（`curl -f http://localhost:9000/minio/health/live`）。
>
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

已落地：后端用 **EasyExcel**（`cn.geosentry.common.export.ExcelExportService` + `*ExportRow` DTO，现有 `HazardExportRow` / `TaskExportRow` / `DeviceExportRow` / `WarningExportRow` / `EmergencyExportRow` / `DashboardExportRow`）将业务数据生成 xlsx 二进制流返回，并设置 `Content-Disposition` 下载响应头。**六个导出接口**（灾害点 / 巡检任务 / 监测设备 / 预警事件 / 应急事件 / 总览指标）均走此链路，见 `ExcelExportService.export`。文件命名使用 URL 编码（`filename*=UTF-8''`）以支持中文文件名。扩容新导出：新增 `*ExportRow`（`@ExcelProperty`）+ 对应 Service 的 `exportRows()`，对应导出接口标注 `@PreAuthorize` 权限码（`overview:export` / `hazards:export` 等，见 §9.1）。

### 13.5 附件（MinIO 对象存储（已落地））

已实现：`AttachmentService.upload` 通过 `MinioClient.putObject()` 把 multipart 文件流真实写入 MinIO（bucket 见 `MINIO_BUCKET`，默认 `geosentry`），成功后可返回 presigned URL；`delete` 同步删除 `removeObject`，避免存储泄漏。`storage_path` / `file_url` 字段保存实际对象路径与访问地址。配置见 `minio.endpoint / .access-key / .secret-key / .bucket`。

安全加固要点（二次开发时保持）：
- **大小上限**：单文件最大 50MB（超限返回 413）。
- **扩展名白名单**：PDF / Office / 图片 / csv/txt / zip/rar/7z / dwg/dxf/shp/geojson 等，其余拒绝（400）。
- **文件名清洗**：`sanitizeFileName()` 移除路径分隔符与控制字符、裁掉前导点/空格、限制长度 200，防止路径穿越。
- **objectKey 用 UUID**（`businessType/businessCode/<uuid>`），不携带原始文件名，杜绝 MinIO 内部路径穿越与文件名混淆。
- **启动预热 bucket**：`@PostConstruct` 检查 bucket 存在性，不存在自动创建（失败不阻断启动，上传时会重试）。
- 上传者身份取自 SecurityContext（JWT username），**不使用可伪造的 `X-User` header**。

---

## 14. 常见问题与注意事项

1. **前端后端模式**：确认 `VITE_USE_BACKEND` 与后端健康状态，避免接口误判。
2. **JWT 密钥（强制校验）**：`JWT_SECRET` 必须不少于 32 字符且不在弱密钥黑名单（change-me / secret / default-secret 等）。**不满足则应用启动失败**（`IllegalStateException`）。生成方式：`openssl rand -base64 48`。
3. **CORS**：生产环境不要保留过宽白名单，跨域与鉴权失败时优先检查 `FRONTEND_ORIGIN`、`VITE_API_BASE_URL` 与登录态。
4. **用户认证模式**：`DEV_USERS_ENABLED` **默认关闭**（`application.yml` 默认 `false`）。`run-backend.ps1` 等本地开发脚本显式置 `true`：此时 DB 未命中会 fallback 到内存账号（admin/attendant/maintenance，密码 `123456`，**BCrypt hash 统一校验**，不存在"任意密码可登录"后门）。生产环境务必保持 `false`，强制走 `sys_user` 表的 BCrypt 密码哈希。
5. **设备上报严格模式**：`DEVICE_AUTH_STRICT=false`（默认）允许未登记 `api_key` 的设备上报（WARN 提示）。生产环境设为 `true`，未登记密钥的设备直接 403。
6. **EMQX 鉴权检查**：`EMQX_ENABLED=true` 但未配置 `EMQX_USERNAME`/`EMQX_PASSWORD` 时，启动阶段会输出大段 WARN，提醒 broker 侧无认证，任何客户端可伪造设备数据。生产必须同时配置两端。
7. **应用配置文件**：docker-compose 使用 `SPRING_PROFILES_ACTIVE=docker` 时应提供并启用 `application-docker.yml`，避免回退到本地地址。
8. **地图 Token**：天地图 / Cesium Ion / 天气 API 均需要单独申请并配置；缺失时自动回退到 OSM/ArcGIS/内置数据。
9. **空间字段**：`hazard_point.geom` 等空间列依赖 PostGIS 扩展（`infra/postgres/init` 或 `schema.sql` 均会 `CREATE EXTENSION IF NOT EXISTS postgis`）；`geom` 由应用层生成，视口查询走 `ST_MakeEnvelope` + GiST 索引。
10. **已落地项（持续闭环）**：导出（EasyExcel 真实 xlsx）、附件 MinIO 真实写入、方法级授权（`@PreAuthorize`）、设备数据上报链路、预警规则引擎、仪表盘真实聚合、**预警状态机补全**（六态）、**橙/红预警派发自动联动生成应急事件**（三层幂等防御：exists 检查 + PG 部分唯一索引 + DataIntegrityViolationException 兜底）、**值守人员完整链路**（Redis 实时缓存 + `POST /positions/report` + WebSocket 广播）、**WebSocket 实时推送 + 一次性 ticket 鉴权**（前端自动先调 `GET /api/auth/ws-ticket`，失败 fallback raw JWT；WS URL 未配置时从 `VITE_API_BASE_URL` 自动推导 http→ws、/api→/ws）、**EMQX MQTT 桥接 + 启动鉴权预检 + broker 侧认证初始化**（`EmqxSecurityPreflight` 预检 + `infra/emqx/init.sh` 首次启动创建 MQTT 认证用户）、**TDengine 接入主链路 + compile scope 自动打包（无需手动部署驱动）**、**设备数据上报鉴权 + 严格模式**、**设备离线自动心跳**（`DeviceOnlineChecker` 每 60s 扫描 `last_report_at` 超 5min 的在线设备置离线并广播）、**JWT 弱密钥 fail-fast**（启动时校验 ≥32 字符 + 精确黑名单 + 正则强度校验（纯数字/纯字母/全同字符），失败应用启动崩溃）、**DB 用户认证替代内存 fallback**、**登录密码真实验证**、**后端 AI 全链路**、**前端 AI 展示**、**前端 Docker 生产部署**（多阶段构建：node:22-alpine 构建 + nginx:1.27-alpine 运行，反向代理 `/api` 和 `/ws`，同域部署无需 CORS）、**分页结构统一**（废弃 `PagedResponse`，所有接口统一返回 `PageResponse{records,total,page,size,pages}` 五字段）、**EMQX entrypoint 自动初始化**（broker 首次启动自动执行 `init.sh` 创建 MQTT 认证用户）、**设备数据双通道去重**（PG 唯一索引 + catch DataIntegrityViolationException）、**预警规则引擎去重优化**（仓储方法索引查询替代全表扫描）、**附件 URL 自动刷新**（每次详情访问生成新 presigned URL + 显式 refresh-url 端点）、**规则引擎 AI 融合落 risk_score_log**（因子快照 JSONB，用于模型迭代回溯）、**AI 预警强制人工复核**（AI/规则输出均生成"待确认"状态预警，不自动派发）、**OperationLogAspect 审计**（AOP 自动记录所有写操作）、**MinIO 弱密钥 fail-fast**（prod 模式下检测默认凭证阻断启动）、**WebSocket require-ticket 强制模式**（prod 推荐禁用 raw JWT fallback）、ProductionConfigValidator（prod 下 7 项安全配置 fail-fast）、EMQX init.sh 3 次重试 + entrypoint 失败阻断、Dockerfile.backend/frontend 完整多阶段构建、POST /api/auth/change-password 端点补全、种子用户密码 BCrypt hash 校验、应用配置 prod profile 强制安全默认值、健康检查端点默认检测 PostgreSQL（SELECT 1）、索引冗余清理（idx_device_metric_code_time 已删除，唯一索引覆盖查询）、DeviceDataService TDengine 3 次指数退避重试、risk_score_log 补充 source 列（AI_FUSED / RULE_ONLY）、**设备指标自动清理**（DeviceMetricCleanupScheduler，默认 30 天保留）、**AI 自动取数端点**（`GET /forecast/auto` 从 DB 拉历史、`GET /fusion-risk/auto` 聚合 hazard+设备真实因子，前端 HazardDetail/WarningDetail 直接消费）、**规则引擎去重批量预取**（`findOpenWarnings` 一次性构建 `rule|hazard` 集合，替代循环 exists）、**WarningCreateService 两阶段事务**（规则引擎内部 `this` 调用绕过 AOP 代理，委托独立 bean 保证 @Transactional；AI 评分放事务外、DB 写事务内原子）、**预警 source 列 + AI_DEGRADED 标记**、**AiForecastScheduler 类级固定线程池复用**（daemon 线程 + CountDownLatch 5min 兜底）、**AsyncConfig 显式线程池**（有界队列 + CallerRunsPolicy，防默认 SimpleAsyncExecutor 无限建线程）、**设备侧时间戳去重**（ts/recorded_at/timestamp 解析，兼容 epoch 秒/毫秒/ISO/「yyyy-MM-dd HH:mm:ss」）、**MQTT 独立消费线程池**（有界队列 1000 + 丢弃最旧告警 + clientId 随机后缀）、**TDengine init.sql + healthcheck + 熔断器**（容器首启建库建表，连续失败 30s 熔断自愈）、**值守人员上报限本人**（JWT.username 与 userId 一致性校验，管理员可代报）、**Redis SCAN 遍历**（替代 KEYS * 防阻塞生产 Redis）、**后端新增 SettingsPage + 运行模式角标**、**总览/应急导出**（dashboard export + emergency export + 应急 POST /{id}/close 闭环语义）、**附件上传安全加固**（50MB 上限 + 扩展名白名单 + 文件名清洗 + UUID objectKey + 上传者取 SecurityContext）、**RedisConfig 显式连接工厂**（修复 Lettuce 自动配置缺 bean）、**ESLint 10 扁平配置**（`npm run lint` / `lint:fix`）、**ai-service CORS 可配置**（`AI_CORS_ORIGINS` 环境变量）、**值守人员 DB fallback 预加载**（executor→task、inspector→最近签到、hazard→坐标 三张索引预加载，避免 N+1）。**剩余缺口**：**真实 AI 模型文件**（需放入 `ai-service/models/`，训练脚本已就绪，可用合成数据快速演示）、EMQX broker TLS 证书（broker 自身配置，代码已预留 username/password 鉴权）、数据库迁移工具引入（当前依赖 spring.sql.init 幂等执行，复杂变更建议后续引入 Flyway）。生产环境首次部署需手动执行 schema.sql + data.sql（prod 禁用 spring.sql.init），详见 §14 第 43 条。
11. **schema.sql 限制**：`spring.sql.init` 无法解析 PL/pgSQL `$$` 块（ScriptUtils 按分号切分），因此触发器/函数不要放进 `schema.sql`；既卷外键补齐统一走 `DatabaseForeignKeyInitializer`。
12. **Java 记录/命名**：后端大量使用 `record`（DTO），修改字段后注意前端字段名一致性（驼峰 vs 下划线）。
13. **AI 服务**：独立 `ai-service`（FastAPI，端口 8000）。模型/依赖缺失时服务仍可启动并返回 mock 结果；后端 `AI_ENABLED=false` 或 AI 服务不可用时自动降级为纯规则模式。部署前需能拉取 `python:3.11-slim` 基镜像与推理依赖（onnxruntime/torch/xgboost 体积较大）。
14. **AI 人工复核**：AI 输出（detect/forecast/anomaly/fusion）定位为辅助决策，必须人工复核后才进入正式预警流程；结果持久化于 `ai_analysis_result` 便于审计。
15. **方法级权限**：`SecurityConfig` 已启用 `@EnableMethodSecurity`，`JwtAuthenticationFilter` 从 `PermissionRegistry` 按角色注入权限码 authority。写/删/导出等关键接口标注了 `@PreAuthorize`；权限码与前端 `ROLE_PERMISSION_MAP` 一致。未带对应权限的调用返回 403。
16. **预警规则引擎**：`RuleEngineScheduler` 默认每 30s 评估，顶层 try-catch 兜底（单轮异常不终止后续调度）。规则命中后委托 `WarningCreateService` 创建（两阶段：事务外 AI 融合 + 事务内写库），AI 服务未部署时日志会出现 `AI 融合评分不可用，沿用规则等级` 的 WARN（属预期降级，不影响触发）；此时 `risk_score_log` 仍会写 RULE_ONLY 记录。同 `(rule,hazard)` 未闭环前不重复触发（批量预取集合判重）。
17. **设备上报鉴权**：`POST /api/devices/{code}/data` 由 `DeviceAuthFilter` 拦截，要求请求头 `X-Device-Key`；过滤器从 `monitoring_device.api_key` 列校验。设备未登记 api_key 时允许上报（WARN 日志提示，便于迁移）；EMQX MQTT 链路的鉴权由 broker 自身配置（`emqx.username`/`emqx.password`）处理，代码中已预留用户名密码参数。
18. **WebSocket 鉴权**：`/ws` 端点通过 `JwtHandshakeInterceptor` 校验握手请求。**推荐方式**：前端先调 `GET /api/auth/ws-ticket`（需 JWT）获取 60s 一次性 ticket，再用 `ws://host:port/ws?ticket=${ticket}` 连接——ticket 一次性消费，反向代理日志里只看到随机 token。**兼容方式**：`ws://host:port/ws?token=${authToken}`（直接把 JWT 放 query，浏览器 WebSocket API 不支持自定义 Header）。前端 `realtimeService.js` 已实现：**优先 ticket，失败 fallback raw JWT**（重连时重新取 ticket，处理 token 刷新）。WS URL 未配置时自动从 `VITE_API_BASE_URL` 推导 http→ws、/api→/ws。**Origin 白名单**：`WebSocketConfig` 通过 `setAllowedOriginPatterns` 限制可握手的来源（配置 `FRONTEND_ORIGIN`，逗号分隔多域），任意域名握手返回失败。
19. **人员位置上报鉴权**：`POST /api/personnel/positions/report` 需携带有效 JWT（已从 permitAll 收口为 authenticated），且**默认只能上报自己的位置**（JWT 主体与 payload.userId 一致，管理员可代报，否则 403）。移动端可复用 Web 端签发的 token（`/api/auth/login` 返回），建议设置较长过期时间（如 24h）。`GET /api/personnel/positions` 需要 `overview:view` 权限。
20. **schema 文本列**：`device_metric.raw_payload`、`warning_event.rule_snapshot`、`ai_analysis_result.result_json` 均以 `TEXT` 存储（实体映射 String）；若改回 `JSONB` 需同步调整实体类型或加 `::jsonb` 转换，避免 `column is of type jsonb but expression is of type character varying` 报错。
21. **种子数据明文密码**：`data.sql` 预置三个演示用户（admin/attendant/maintenance），BCrypt 哈希对应明文密码均为 `123456`。生产环境**两种做法**：(a) 执行 data.sql 后、启动 backend 前用 SQL UPDATE 改密码（见 §14 #43 步骤 5）；(b) 跳过 data.sql 注入，启动后用 SQL INSERT 创建管理员（此时 sys_user 为空，ProductionConfigValidator 旁路放行）。
22. **geom 字段维护**：`hazard_point.geom` / `monitoring_device.geom` 等空间列**仅由应用层通过 GeometryFactory 自动生成**——写入时同时更新经度/纬度/geom 三列。**禁止直接用 SQL 修改经纬度而不更新 geom**（空间索引查询会返回错误结果）。如果必须绕过应用层运维，请执行 `UPDATE ... SET geom = ST_SetSRID(ST_MakePoint(longitude, latitude), 4326) WHERE ...`。
23. **MinIO presigned URL 过期**：附件上传返回的 presigned URL 有效期 7 天（MinIO 默认），过期后前端需重新请求。后端 `GET /api/attachments/{id}` 每次访问都会**自动生成新 URL 并更新 DB**，前端也可显式调 `POST /api/attachments/{id}/refresh-url` 强制刷新（检测到图片 403/410 时调用，`dashboardApi.refreshAttachmentUrl` 已封装）。生产部署建议设置 CDN 反向代理 + 自定义域名，避免直接暴露 MinIO 端口。
24. **EMQX broker 侧认证配置**：`docker-compose.yml` 编排了 EMQX 5.x 并挂载 `infra/emqx/` 目录作为配置。首次启动时执行 `infra/emqx/init.sh` 会创建 Built-in Database 认证器 + `geosentry_backend` MQTT 用户（BCrypt 密码来自 `EMQX_PASSWORD`）。**如果跳过此初始化步骤**（如手动 docker-compose up 未挂载目录），broker 侧无认证，任何客户端可连接。验证方式：访问 `http://localhost:28083`（Dashboard）→ 认证 → Users，应能看到 `geosentry_backend` 用户。
25. **PostgreSQL 初始化顺序**：`docker-compose.yml` 已配置 postgres 健康检查（`pg_isready`）+ backend `depends_on: condition: service_healthy`，避免首次启动后端连不上数据库。但如果 postgres 容器重建后丢失数据卷，`docker-entrypoint-initdb.d` 只在**空数据卷**时执行，已存在数据不会重跑。此时需手动删除数据卷 `docker volume rm geosentry_postgres_data` 再重建。
26. **Docker 前端部署**：前端已改为多阶段构建（`Dockerfile.frontend` + `infra/nginx/frontend.conf`），不再使用 `npm run dev`。构建时环境变量作为 build args 注入：`VITE_API_BASE_URL=/api`、`VITE_WS_URL=/ws`（同域部署，由 nginx 反向代理到 backend）。如果需要开发调试，可单独启动前端 `npm run dev`（5173 端口），docker-compose 的 frontend 服务不会冲突（只映射 backend）。
27. **DeviceAuthFilter 绕过 JWT 的原理**：`/api/devices/{deviceCode}/data` 路径不在 SecurityConfig 的 permitAll 列表里，但 DeviceAuthFilter 先于 JwtAuthenticationFilter 执行（`addFilterBefore(deviceAuthFilter, JwtAuthenticationFilter.class)`），校验通过后注入 `ROLE_DEVICE` 权限的 Authentication 到 SecurityContext。后续 JwtAuthenticationFilter 读取 SecurityContext 已存在 Authentication 就跳过。**注意**：如果 JwtAuthenticationFilter 的 `shouldNotFilter` 不对 device data 路径放行，可能覆盖 DeviceAuthFilter 的认证——当前配置已正确处理。
28. **EMQX broker 认证自动初始化**：docker-compose 已通过自定义 entrypoint（`infra/emqx/emqx-entrypoint.sh`）在 EMQX 启动后自动执行 `init.sh`，创建 Built-in Database 认证器 + `geosentry_backend` MQTT 用户（BCrypt 密码来自 `EMQX_PASSWORD`）。无需手动执行。验证：`http://localhost:28083` → Users。
29. **设备数据双通道去重**：HTTP 与 MQTT 上报均走 `DeviceDataService.ingest`，PostgreSQL 有唯一索引 `idx_device_metric_unique(device_code, metric_code, recorded_at)` 防止重复样本。重复时 catch `DataIntegrityViolationException` 并跳过（DEBUG 日志），不影响主流程。
30. **附件 presigned URL 自动刷新**：MinIO presigned URL 有效期 7 天。后端 `GET /api/attachments/{id}` 每次访问自动生成新 URL 并更新 DB；前端也可调用 `POST /api/attachments/{id}/refresh-url` 强制刷新。建议前端检测到图片 403 时自动调用刷新。
31. **操作审计日志**：`OperationLogAspect`（AOP）自动拦截所有类名后缀 `*Controller` 的 Controller 写方法（POST/PUT/DELETE）——**不依赖 `@PreAuthorize` 注解**，即使未显式标注权限的写接口也会被审计。记录 operator（从 JWT 解析，失败回落 anonymous）/ module / action / HTTP 方法 / URL / 耗时 / IP 到 `operation_log` 表。密码等敏感字段自动截断，审计失败不影响主流程（`saveAsync` 捕获异常）。
32. **预警规则引擎性能**：去重判断已从循环内逐条 `existsByRuleCodeAndHazardCodeAndStatusNot` 改为**批量预取**——`WarningEventRepository.findOpenWarnings(ruleCodes, hazardCodes, excludedStatuses)` 一次拉取所有未关闭预警，构建 `ruleCode|hazardCode` 集合做 O(1) 判重（NULL-safe 处理 status）。指标拉取也使用轻量投影（`findDeviceCodeHazardCodePairs`）+ `findLatestByDeviceCodes`（`DISTINCT ON`）。
33. **ProductionConfigValidator 作用**：`@Profile("prod")` 组件，启动时自动校验 7 项安全配置（DEV_USERS_ENABLED、DEVICE_AUTH_STRICT、WS_REQUIRE_TICKET、EMQX 凭据、FRONTEND_ORIGIN、种子用户密码、MINIO 密钥强度）。**7 项全部 fail-fast：命中即抛 `IllegalStateException` 阻断启动**，与其他安全配置策略一致。**唯一旁路**：`sys_user` 表为空（未执行 data.sql、无任何用户记录）时跳过种子密码检查，允许首次部署启动后通过 API 或 SQL 创建管理员。dev profile 下不加载，避免本地开发被拦截。
34. **种子用户密码重置**：data.sql 中三个种子用户密码均为 BCrypt("123456")。prod profile 禁用 SQL init，首次部署也不自动执行 data.sql，因此**统一采用 SQL UPDATE 方案**：
    - **首次部署**：执行 data.sql 注入种子数据后、启动 backend **之前**，用 htpasswd 生成新 BCrypt hash + `UPDATE sys_user`（详见下方 §14 第 43 条步骤 5）。否则 ProductionConfigValidator 检测到默认密码会阻断启动；
    - **已运行系统**：让用户登录后调用 `POST /api/auth/change-password` 修改，或 DBA 直接执行 `UPDATE sys_user SET password_hash = '<BCryptHash>' WHERE username = 'admin'`。

    > 注意：修改 data.sql 中的 BCrypt hash 对 prod 无意义（prod 不自动执行 data.sql），仅适用于 dev profile。

35. **EMQX init.sh 失败处理**：broker 认证初始化脚本内置 3 次重试（每次间隔 5s）。全部失败后 entrypoint 返回非零退出码，`docker restart unless-stopped` 自动重启容器重试。避免 broker 在无认证状态下持续运行。

36. **Docker 镜像构建**：后端使用 `Dockerfile.backend` 多阶段构建（Stage 1: maven:3.9.9-eclipse-temurin-17，Stage 2: eclipse-temurin:17-jre-alpine）。TDengine JDBC 已改为 **compile scope**，会自动打包进 Spring Boot fat jar，无需额外处理。Dockerfile.backend 中仍保留 optional-lib 下载逻辑作为双保险（若未来改为 provided scope 或 Maven Central 不可达时不影响构建）。前端 `Dockerfile.frontend` 基于 `node:22-alpine` 构建 + `nginx:1.27-alpine` 运行，强制校验 `VITE_USE_BACKEND=true`，否则构建失败。

37. **operation_log 归档策略**：归档脚本 `infra/postgres/init/003_archive_operation_log.sql` 已存在，包含 `operation_log_archive` 归档表 DDL 与 `archive_operation_logs(retention_days)` 归档函数。生产环境建议每日凌晨 3:00 执行（通过 pg_cron 或外部调度器），默认保留 90 天在线数据。归档表在首次执行时由 `CREATE TABLE IF NOT EXISTS` 自动创建，结构与 `operation_log` 完全一致（无索引减少归档写入开销）。

38. **AI 降级审计追踪**：规则引擎在 AI 服务不可用时降级沿用规则等级，同时在 risk_score_log 中写入 `source='RULE_ONLY'` 标记，便于后续评估 AI 融合覆盖率。

39. **geom 字段数据库触发器规划**：当前 geom 完全由应用层维护（EntityListener）。直接 SQL 修改 longitude/latitude 而不更新 geom 会导致空间索引返回错误结果。建议 v2 版本引入 BEFORE UPDATE/INSERT 触发器强制同步。当前运维规范：禁止直接 SQL 修改经纬度列。

40. **DeviceDataService 双写一致性**：先写 PostgreSQL（事务内），再**异步**（`@Async`，经 `AsyncConfig` 线程池）写 TDengine，内置 3 次指数退避重试（200/400ms）+ 30s 熔断器。PG 写入成功但 TDengine 失败（重试后仍失败）时，PG 数据已持久化但 TDengine 缺样本。长期运行可能出现时序数据与关系库不一致。建议 v2 版本引入补偿队列（Redis Stream）处理 TDengine 写入失败。

41. **设备数据去重保留先到者**：HTTP/MQTT 上报均走 `DeviceDataService.ingest`，PostgreSQL 唯一索引 `idx_device_metric_unique(device_code, metric_code, recorded_at)` 防止重复样本。重复时 catch `DataIntegrityViolationException` 并跳过（DEBUG 日志），不影响主流程。

    ⚠️ **精度限制**：`recorded_at` 类型为 `TIMESTAMPTZ`，精度**截断到秒**。位移计、雨量计等高于 1Hz 的传感器，同一秒内的多个不同样本会被误判为重复而丢弃。

    **规避措施（当前版本）**：
    - 在数据接入层做**聚合**：将高频传感器数据降采样到 1Hz（取均值或首尾值）；
    - 不要将位移计等高频设备的原始上报端直接接入，可通过边缘网关聚合后再上报。

    **v2 规划**：引入 `recorded_at TIMESTAMPTZ(3)`（毫秒精度）或增加服务端接收时间戳 `received_at` 作为辅助去重字段，支持亚秒级精度。

42. **TDengine 驱动加载**：TDengine JDBC（`taos-jdbcdriver:3.3.0`）已改为 **compile scope**，Maven 构建时自动打包进 Spring Boot fat jar，**Docker 和宿主机均无需额外处理**。启用 TDengine 只需设置 `TDENGINE_ENABLED=true` 即可。数据库（`geohazard`）与超级表（`device_metric`）由 `infra/tdengine/init.sql` 在 TDengine 容器**首次启动时**创建（`docker-entrypoint-initdb.d` 机制）；`TdengineMetricService` 负责在首次写入某设备时自动创建其子表 `device_metric_{deviceCode}`（`CREATE TABLE IF NOT EXISTS ... USING device_metric TAGS(...)`，子表名缓存避免重复 DDL），并内置 30s 熔断器。Dockerfile.backend 中仍保留 optional-lib 下载逻辑作为双保险（JarLauncher classpath 中 optional-lib/\* 自动加载），但非必需。

43. **生产环境数据库初始化**：prod profile 禁用 `spring.sql.init.mode=never`，首次部署时不会自动建表。提供两种方案，按安全等级选择：

---

#### 方案 A：使用种子数据 + 修改密码（快速部署）

适合快速上线，或希望保留演示数据的场景。

```bash
# ====== 步骤 A1：启动空 PostGIS ======
docker compose -f docker-compose.yml up -d postgres

# ====== 步骤 A2：启用 PostGIS 扩展 ======
docker compose exec -T postgres psql -U geosentry -d geosentry \
  < infra/postgres/init/001_create_extensions.sql

# ====== 步骤 A3：建表（幂等，可重跑） ======
docker compose exec -T postgres psql -U geosentry -d geosentry \
  < backend/src/main/resources/schema.sql

# ====== 步骤 A4：注入种子数据（admin/attendant/maintenance，密码均为 123456） ======
docker compose exec -T postgres psql -U geosentry -d geosentry \
  < backend/src/main/resources/data.sql

# ====== 步骤 A5：强制修改种子密码（否则 ProductionConfigValidator 会阻断 prod 启动）======

# A5a) 生成新 BCrypt hash（选任一工具）
# 工具 1：htpasswd（Apache HTTP 工具，需安装：apt-get install apache2-utils / brew install httpd）
#   ⚠️ 密码若含 shell 特殊字符（$ ! ' " \），必须用单引号包裹或改用工具 2/3
htpasswd -nbBC 10 "" '<your_strong_password>'
# 示例输出：$2b$10$...

# 工具 2：Python bcrypt（需 pip install bcrypt）— 无 shell 特殊字符问题
python3 -c "import bcrypt; print(bcrypt.hashpw(b'<your_strong_password>', bcrypt.gensalt(10)).decode())"

# 工具 3：Spring Security BCryptPasswordEncoder（Java）
# new BCryptPasswordEncoder().encode("your_strong_password")

# A5b) 注入数据库（替换 <BCRYPT_HASH> 为上一步输出）
#   ⚠️ BCrypt hash 以 $2b$ / $2a$ / $2y$ 开头，包含多个 $ 符号
#   绝对不能用双引号包裹 SQL（Shell 会对 $ 做变量扩展，导致 hash 损坏）
#   必须用 here-doc + 单引号 <<'SQL' 语法，彻底避免 Shell 展开
docker compose exec -T postgres psql -U geosentry -d geosentry <<'SQL'
UPDATE sys_user SET password_hash='<BCRYPT_HASH>'
WHERE username IN ('admin','attendant','maintenance');
SQL

# ====== 步骤 A6：启动全栈 ======
# docker-compose.prod.yml 不覆盖 postgres 服务定义，步骤 A1 启动的 postgres 保持不变
# 此命令仅会启动 backend / frontend / ai-service 等服务，不会重建 postgres
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
```

> **为什么不能用双引号？** BCrypt hash `$2b$10$e7J.wokpqTMslzHzJq/ateJNEhEnLh/pgz0gXB1HEv1mp6qDaI5pq` 中，Shell 在双引号内会将 `$2` 解释为第二个位置参数（通常为空），`$2b` 被部分展开。结果是写入数据库的 hash 被截断/损坏，用户无法登录。here-doc + `<<'SQL'`（单引号包裹分隔符）是唯一安全的方式——Shell 不对 here-doc 内容做任何展开。

> **BCrypt 前缀兼容性**：`htpasswd` 默认生成 `$2y$` 前缀，Spring Security 的 `BCryptPasswordEncoder` 使用 `$2a$` 前缀，Python bcrypt 输出 `$2b$`，三者算法兼容可互验（Spring Security 5.x 已统一兼容所有三种前缀）。

---

#### 方案 B：跳过种子数据 + 手动创建管理员（更安全）

适合对安全敏感的生产环境，彻底避免默认凭据风险。

```bash
# ====== 步骤 B1：启动空 PostGIS ======
docker compose -f docker-compose.yml up -d postgres

# ====== 步骤 B2：启用扩展 + 建表（同方案 A）======
docker compose exec -T postgres psql -U geosentry -d geosentry \
  < infra/postgres/init/001_create_extensions.sql
docker compose exec -T postgres psql -U geosentry -d geosentry \
  < backend/src/main/resources/schema.sql

# ====== 步骤 B3：注入角色 + 菜单 + 区域（可选：如果需要权限体系）======
# 只执行 data.sql 的部分语句，不插入 sys_user
docker compose exec -T postgres psql -U geosentry -d geosentry \
  -c "INSERT INTO sys_role (role_code, role_name, description, menu_permissions, button_permissions)
      VALUES ('admin','管理员','拥有全部模块访问权限','*','*') ON CONFLICT (role_code) DO NOTHING;"

# ====== 步骤 B4：直接启动全栈（sys_user 表为空，ProductionConfigValidator 旁路放行）======
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build

# ====== 步骤 B5：启动后用 SQL 创建管理员 ======
# sys_user 表关键字段（schema.sql 第 36-48 行）：
#   username       VARCHAR(64)  NOT NULL UNIQUE
#   password_hash  VARCHAR(255) NOT NULL  — BCrypt hash（用上面任一工具生成）
#   real_name      VARCHAR(64)  NOT NULL  — 用户真实姓名（data.sql 用 real_name，不是 full_name）
#   phone          VARCHAR(32)
#   role_code      VARCHAR(64)  NOT NULL  — 小写：admin / attendant / maintenance
#   region_code    VARCHAR(64)            — 如 '100000'
#   status         VARCHAR(32)  NOT NULL DEFAULT '启用'
#   created_at / updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW() — 已由表结构设定默认值，无需显式写入

# 先准备 BCrypt hash（用 htpasswd 或 python3，见方案 A 步骤 A5a）
#   ⚠️ 同样使用 here-doc 避免 Shell 对 $ 做变量扩展
docker compose exec -T postgres psql -U geosentry -d geosentry <<'SQL'
INSERT INTO sys_user (username, password_hash, real_name, phone, role_code, region_code, status)
VALUES ('admin', '<BCRYPT_HASH>', '系统管理员', '', 'admin', '100000', '启用');
SQL
```

> **方案 B 的基础数据缺失警告**：此方案跳过了完整的 `data.sql`，可能导致以下基础数据表为空：
>
> | 表             | 影响                                                                           | 修复                                                              |
> | -------------- | ------------------------------------------------------------------------------ | ----------------------------------------------------------------- |
> | `sys_role`     | 仅插入了 `admin` 角色，缺少 `attendant` / `maintenance`                        | 可按需补充，或从 `data.sql` 复制完整 INSERT                       |
> | `region`       | 行政区划列表为空，区域筛选/统计失效                                            | 从 `data.sql` 复制 `INSERT INTO region ...` 语句                  |
> | `sys_menu`     | 菜单权限码定义缺失（但前端菜单硬编码，后端权限码由 `PermissionRegistry` 注册） | 非必需，可从 `data.sql` 复制                                      |
> | `warning_rule` | 预警规则为空，规则引擎无规则可评估                                             | **必需**，需从 `data.sql` 复制所有 `INSERT INTO warning_rule ...` |
>
> 最简单的做法：**保留完整的 `data.sql` 执行（即使用方案 A）**。如果坚持方案 B，可用以下命令从 data.sql 提取除 sys_user 外的所有 INSERT 语句：
>
> ```bash
> # 提取 data.sql 中除 sys_user 外的所有 INSERT，并执行
> grep -v "INSERT INTO sys_user" backend/src/main/resources/data.sql \
>   | grep -v "ON CONFLICT (username)" \
>   | docker compose exec -T postgres psql -U geosentry -d geosentry
> ```
>
> **步骤 B4 之后建议验证**：登录后若发现规则引擎不触发预警，请先执行以下检查：
>
> ```bash
> docker compose exec -T postgres psql -U geosentry -d geosentry -c "SELECT COUNT(*) FROM warning_rule;"
> docker compose exec -T postgres psql -U geosentry -d geosentry -c "SELECT COUNT(*) FROM region;"
> docker compose exec -T postgres psql -U geosentry -d geosentry -c "SELECT COUNT(*) FROM sys_role;"
> ```

> **为什么没有注册接口？** 出于安全考虑，未实现 `POST /api/auth/register` 公开注册端点。如果确实需要开放注册（如多租户场景），必须设计邀请码/审批机制。

> **前端启动时机**：方案 A 的步骤 A6 和方案 B 的步骤 B4 均会启动全栈（backend、frontend、ai-service）。frontend 无依赖顺序要求，nginx 代理 `/api` 和 `/ws` 到 backend。

### 生产依赖服务初始化检查清单

除 PostgreSQL 外，以下依赖服务需确认初始化状态（dev 模式自动处理，prod 需手动检查）：

| 服务         | 初始化项                                            | 自动/手动                                                                                                                     | 检查/修复命令                                                                                                                                                            |
| ------------ | --------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **EMQX**     | Built-in Database 认证器 + `geosentry_backend` 用户 | 自动（`infra/emqx/init.sh` entrypoint 执行）                                                                                  | `docker compose exec emqx emqx ctl users list` 查看用户列表（EMQX 5.x 使用 `emqx ctl`，旧版 4.x 用 `emqx_ctl`）；若缺失：`docker compose exec emqx sh /opt/emqx/init.sh` |
| **MinIO**    | `geosentry` bucket                                  | **自动**（`AttachmentService` 首次附件上传时自动检查并创建）                                                                  | 检查 bucket 是否存在：`docker compose exec minio mc alias set myminio http://localhost:9000 $MINIO_ACCESS_KEY $MINIO_SECRET_KEY && mc ls myminio/`；可选预创建同上       |
| **TDengine** | `geosentry` 数据库 + `device_metric` 超级表         | 自动（首次写入时 `TdengineMetricService.writeMetric()` 执行 `CREATE DATABASE IF NOT EXISTS` + `CREATE STABLE IF NOT EXISTS`） | `docker compose exec tdengine taos -s "SHOW DATABASES;"` 确认数据库存在                                                                                                  |
| **Redis**    | 无需初始化                                          | —                                                                                                                             | `docker compose exec redis redis-cli ping` 返回 PONG 即可                                                                                                                |

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

---

## 15. 开发环境故障排除（Windows / Docker Desktop）

### Q1：Docker Hub 连接超时 — `auth.docker.io` 无法访问

**症状**：`docker compose up -d` 报错 `failed to fetch oauth token` 或 `connectex: A connection attempt failed`

**原因**：Docker Hub（`docker.io`）在国内网络环境下常被阻断。

**解决方案**：

**方案 A — 配置镜像加速器（推荐，一劳永逸）**：

Docker Desktop → Settings → Docker Engine，在 JSON 中添加 `registry-mirrors`：

```json
{
  "registry-mirrors": [
    "https://docker.1ms.run",
    "https://docker.m.daocloud.io",
    "https://dockerproxy.com"
  ]
}
```

点击 **Apply & Restart**，再重试 `docker compose up -d`。

**方案 B — 手动拉取并重新打标签（临时方案）**：

```bash
# 从加速源拉取缺失的基础镜像
docker pull docker.1ms.run/library/nginx:1.27-alpine
docker pull docker.1ms.run/library/python:3.11-slim
docker pull docker.1ms.run/library/eclipse-temurin:17-jre-alpine

# 重新 tag 为标准名称
docker tag docker.1ms.run/library/nginx:1.27-alpine nginx:1.27-alpine
docker tag docker.1ms.run/library/python:3.11-slim python:3.11-slim
docker tag docker.1ms.run/library/eclipse-temurin:17-jre-alpine eclipse-temurin:17-jre-alpine
```

### Q2：前端 Docker 构建时 `npm install` 报 `ECONNRESET`

**原因**：`registry.npmjs.org` 在国内网络不稳定。

**解决方案**：跳过 Docker 构建，直接在宿主机运行前端：

```bash
# 只启动基础设施（跳过 frontend/ai-service Docker 构建）
docker compose up -d postgres redis minio emqx tdengine

# 前端本地运行（复用已有的 node_modules）
npm run dev
```

### Q3：后端 Docker 容器内 `mvn spring-boot:run` 反复重启 / 内存占用过高

**原因**：Maven（JVM）内再启动 Spring Boot（又一个 JVM），双 JVM 在容器内占用 700MB+ 内存，healthcheck 超时后被杀重启。

**推荐方案：后端本地直跑**：

```powershell
# PowerShell 设置环境变量
$env:POSTGRES_PORT='15432'
$env:REDIS_PORT='16379'
$env:REDIS_HOST='127.0.0.1'      # Windows 上用 127.0.0.1 而非 localhost
$env:REDIS_PASSWORD='redis123'
$env:MINIO_ENDPOINT='http://127.0.0.1:19000'
$env:JWT_SECRET='your-strong-secret-min-32-chars-long-xyz!'
$env:DEV_USERS_ENABLED='true'
$env:DEVICE_AUTH_STRICT='false'
$env:TDENGINE_ENABLED='false'   # 未启用 TDengine 时关闭，避免连接超时日志
$env:EMQX_ENABLED='false'       # 未启用 MQTT 时关闭

cd backend
mvn spring-boot:run
```

### Q4：EMQX 容器反复重启 — `chmod: Read-only file system`

**症状**：EMQX entrypoint 执行 `chmod +x init.sh` 时报 `Read-only file system`，加上 `set -e` 导致脚本退出、容器重启循环。

**原因**：docker-compose 中 `infra/emqx` 挂载为 `:ro`（只读），且 Windows NTFS 文件系统与 Linux 权限模型不兼容。

**已修复**：[`infra/emqx/emqx-entrypoint.sh`](infra/emqx/emqx-entrypoint.sh) 第 28 行已改为 `chmod +x ... || true`，让 chmod 失败不阻断脚本。

### Q5：后端启动报 `No qualifying bean of type 'RedisTemplate'`

**症状**：Spring Boot 报告 `personnelController` 无法找到 `RedisTemplate<String, Object>` bean，即使 debug 模式显示 `RedisAutoConfiguration` 条件全部匹配。

**原因**：Spring Boot 3.4 的 Lettuce 自动配置在某些 Windows + Docker Desktop 环境下不生效（原因待进一步定位）。

**已修复**：新增 [`backend/src/main/java/cn/geosentry/config/RedisConfig.java`](backend/src/main/java/cn/geosentry/config/RedisConfig.java) 显式创建 `LettuceConnectionFactory` + `RedisTemplate<String, Object>`，带 `@ConditionalOnMissingBean` 注解，不影响正常自动配置环境。

### Q6：后端启动报 `No qualifying bean of type 'JwtAuthenticationFilter'`

**症状**：`SecurityConfig.securityFilterChain` 参数 `JwtAuthenticationFilter` 无法注入。

**原因**：[`JwtAuthenticationFilter.java`](backend/src/main/java/cn/geosentry/common/security/JwtAuthenticationFilter.java) 是普通类，缺少 `@Component` 注解，Spring 不会自动注册为 bean。

**已修复**：已添加 `@Component` 注解。

### Q7：开发环境一键启动命令（推荐）

综合以上，Windows 开发环境推荐启动方式：

```bash
# 1) 基础设施（Docker）
docker compose up -d postgres redis minio emqx tdengine

# 2) 后端（本地直跑，避免 Docker 内双 JVM 内存问题）
#    需要先设置环境变量（见 Q3 PowerShell 命令）
cd backend && mvn spring-boot:run

# 3) 前端（本地直跑，复用已有 node_modules）
npm run dev

# 4) AI 服务（可选，未启用时跳过）
#    cd ai-service && pip install -r requirements.txt && uvicorn app:app
```

### Q8：登录成功后所有认证 API 返回 500 — `PatternParseException`

**症状**：`POST /api/auth/login` 返回 200 + 有效 JWT，但前端跳转到主界面后立即被踢回登录页。直接用 `curl` 或 Postman 测试，发现 **所有** 携带 `Authorization: Bearer <token>` 的请求均返回 `500 Internal Server Error`，响应体为 Tomcat 默认 HTML 错误页（非 JSON）。

**排查关键日志**：

```bash
docker compose logs backend --tail 200 | grep -A5 "PatternParseException"
```

关键异常栈：

```
org.springframework.web.util.pattern.PatternParseException:
  No more pattern data allowed after {*...} or ** pattern element
    at ...AuthorizationFilter.doFilter(AuthorizationFilter.java:96)
    at ...ExceptionTranslationFilter.doFilter(...)
    at ...JwtAuthenticationFilter.doFilterInternal(JwtAuthenticationFilter.java:78)
```

**根因**：`SecurityConfig.java` 中使用了 `/api/devices/**/data` 作为 `requestMatchers` 路径模式。Spring Security 6.x 默认使用 `PathPatternParser`（替代了 Spring 5 的 `AntPathRequestMatcher`），该解析器**不允许 `**` 后面再追加路径段**（`**` 只能出现在模式末尾）。因此 `/api/devices/**/data` 是非法模式。

异常发生在 `AuthorizationFilter`（Security 过滤器链末端，DispatcherServlet 之前），导致：
1. `GlobalExceptionHandler`（`@RestControllerAdvice`）**无法捕获**——它只能处理 Controller 方法内抛出的异常；
2. Tomcat 直接返回默认 HTML 500 错误页；
3. 前端收到非 JSON 的 500 响应，axios 拦截器判断为会话失效，清除 token 并重定向到登录页 → **"登录后直接卡退"**。

**已修复**：将 `SecurityConfig.java` 中的路径模式从 `**` 通配改为路径变量：

```java
// 修复前（非法模式）：
.requestMatchers("/api/devices/**/data").hasRole("DEVICE")

// 修复后（合法模式）：
.requestMatchers("/api/devices/{deviceCode}/data").hasRole("DEVICE")
```

`{deviceCode}` 匹配单个路径段（不含 `/`），语义与 `DeviceAuthFilter` 的 `shouldNotFilter` 逻辑一致（只处理 `POST /api/devices/<code>/data`）。

> **Spring Security 6 PathPattern 规则速查**：
> - `*` — 匹配单级路径段（不含 `/`），如 `/api/{*}` 匹配 `/api/foo` 但不匹配 `/api/foo/bar`
> - `**` — 只能出现在模式末尾，匹配剩余所有路径（含 `/`）
> - `{varName}` — 匹配单级路径段并捕获为变量
> - `/api/**/data` — **非法**（`**` 后面有路径段）
> - `/api/*/data` — 合法（`*` 匹配单级段）
> - `/api/{code}/data` — 合法（推荐写法，语义更清晰）

### Q9：开发用户密码

开发环境预置三个演示账号，密码统一为 `123456`（不是 `admin123`）：

| 用户名       | 角色          | 用途         |
| ------------ | ------------- | ------------ |
| `admin`      | admin         | 系统管理员   |
| `attendant`  | attendant     | 值班人员     |
| `maintenance`| maintenance   | 维护人员     |

- **Docker 部署**：`docker-compose.yml` 中 `DEV_USERS_ENABLED` 默认 `false`，但 `data.sql` 种子用户始终注入（`spring.sql.init.mode: always`），密码哈希为 `BCrypt("123456")`。
- **本地直跑**：`run-backend.ps1` 设置 `DEV_USERS_ENABLED=true`，即使 DB 无种子数据也能通过内存 fallback 登录。**fallback 账号同样使用 BCrypt hash（构造时用 `PasswordEncoder` 生成 `123456` 的哈希），AuthController 统一校验明文**——不是 `{noop}` 任意密码放行，不存在"任意密码可登录"的后门。**注意 `DEV_USERS_ENABLED` 默认已改为 `false`**，需要时显式开启。
- **生产环境**：`DEV_USERS_ENABLED=false` + `application-prod.yml` 禁用 SQL init，需手动创建管理员（见 §14 第 43 条）。

### Q10：混合模式下前端 502 Bad Gateway

**症状**：`run-backend.ps1` 成功启动本地后端（8080 端口 java 进程），但前端登录请求返回 `502 Bad Gateway`，URL 为 `http://localhost:5173/api/auth/login`。

**根因**：Docker 前端容器（`geosentry-frontend`）仍在运行，其 Nginx 配置将 `/api` 反代到 Docker 网络内的 `backend:8080`。`run-backend.ps1` 停掉了 Docker 后端容器，本地后端在宿主机上运行，但不在 Docker 网络中，Nginx 无法连接 → 502。

**判断方法**：

```powershell
# 检查 5173 端口是否被 Docker 占用
$conn = Get-NetTCPConnection -LocalPort 5173 -State Listen -ErrorAction SilentlyContinue
$proc = Get-Process -Id $conn.OwningProcess
$proc.ProcessName  # com.docker.backend = Docker 前端容器（502 原因）
                    # node = 本地 Vite dev（正常）
```

**修复**：

```bash
# 方案 1（推荐）：用 run-frontend.ps1 自动停 Docker 前端 + 启动本地 Vite
.\run-frontend.ps1

# 方案 2：手动操作
docker compose stop frontend   # 停掉 Docker 前端容器
npm run dev                     # 启动本地 Vite dev server
```

> **混合模式要点**：后端和前端都必须在宿主机直跑，不能一个在 Docker、一个在本地。Docker 前端 Nginx → Docker 网络内 `backend:8080`，本地后端 → `localhost:8080`，两者网络隔离。详见 §4.3 步骤 2-3。

---

## 16. Sentinel-1 + Sentinel-2 卫星合成图层

> **核心定位**：把 ESA Sentinel-1（雷达 SAR，C 波段 10 m）和 Sentinel-2（光学多光谱 10/20/60 m）
> 双源数据按 AOI（感兴趣区域）合成成可叠加在地图上的真彩 + SAR 伪彩底图——值班员在态势页可直接比对
> 灾害点与卫星影像，快速定位滑坡、崩塌、泥石流的形变异常与地表变化。
>
> 合成由 **ai-service（Python FastAPI）** 完成（`routers/sentinel.py`），Java 后端不参与，
> 仅透传请求 + 轮询任务状态。前端 `src/services/_architecture/core/sentinelService.js` 封装了
> `composeSentinelScene() / fetchSentinelJob() / pollSentinelJob() / cancelSentinelJob()` 一组工具方法。

### 16.1 数据源策略

| 数据源 | 用途 | 提供方 | 接入方式 |
|---|---|---|---|
| **Copernicus Data Space** | 主数据源（ESA 官方 2024+ 上线） | ESA Copernicus | STAC API + OAuth2 Client Credentials |
| **Google Earth Engine**（GEE） | 备用合成引擎 | Google | earthengine Python SDK + 服务账号 JSON |

> **演示模式**：未配置 Copernicus Client ID / Secret 或 GEE Service Account 时，
> Python 端使用 PIL + numpy 随机生成一张 1024×1024 的 S2 真彩 / S1 SAR 伪彩占位图
> （512×512 假图，但波段配色与真实 SAR 一致：VV→红、VH→绿、VV/VH→蓝），
> 上传到 `SENTINEL_STORAGE_DIR`（默认 `/tmp/sentinel_outputs`）。
> 前端可直接加载，**仅供界面交互演练**——真实形变必须接入 Copernicus。

### 16.2 合成管线

```
客户端 (`src/services/_architecture/core/sentinelService.js`)
   POST /api/ai/sentinel/compose  {bbox, start, end, max_cloud, provider, sar_overlay, s2_true_color}
            │
            ▼
Python ai-service (routers/sentinel.py)
   ┌─────────────────────────────────────────┐
   │ ① 验证 OAuth Token / GEE 凭据            │
   │ ② STAC 搜索 S2 L2A（云量 < max_cloud）   │
   │ ③ STAC 搜索 S1 GRD                       │
   │ ④ 下载 B4/B3/B2 波段 → S2 真彩 PNG      │
   │ ⑤ 下载 VV/VH 波段 → S1 SAR 伪彩 PNG      │
   │ ⑥ 上传到 SENTINEL_CDN_BASE 或本地存储    │
   │ ⑦ 返回 jobId + status=queued → running → │
   │    succeeded / failed / cancelled       │
   └─────────────────────────────────────────┘
            │
            ▼
客户端轮询 GET /api/ai/sentinel/jobs/{jobId}
   每次返回 status / progress(0..100) / result.{s2Url, sarUrl, expiresAt}
```

### 16.3 REST API（Python ai-service 直接暴露）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/ai/sentinel/compose` | 提交合成任务，请求体见下 |
| GET  | `/api/ai/sentinel/jobs/{jobId}` | 查询任务状态 |
| POST | `/api/ai/sentinel/jobs/{jobId}/cancel` | 取消运行中的任务 |

请求体 `SentinelComposeRequest`：

```json
{
  "bbox": [107.0, 30.4, 107.4, 30.8],   // [minLon, minLat, maxLon, maxLat] (WGS-84)
  "start": "2026-08-01",                 // 可选，默认最近 30 天
  "end":   "2026-08-31",                 // 可选，默认今天
  "max_cloud": 15,                       // 可选，0..100，默认 15
  "provider": "copernicus",              // 可选：copernicus（默认） / gee
  "sar_overlay": true,                   // 可选：是否生成 S1 SAR 伪彩叠加层
  "s2_true_color": true                  // 可选：是否生成 S2 真彩底图层
}
```

响应（POST /compose 与 GET /jobs/{jobId} 同构）：

```json
{
  "jobId": "5f4d8c7e-...",
  "status": "running",
  "progress": 45,
  "message": "S2 真彩合成完成，正在搜索 Sentinel-1…",
  "result": null
}
```

终态响应：

```json
{
  "jobId": "5f4d8c7e-...",
  "status": "succeeded",
  "progress": 100,
  "message": "合成完成",
  "result": {
    "s2Url": "https://cdn.example.com/sentinel/s2_truecolor_xxx.png",
    "sarUrl": "https://cdn.example.com/sentinel/s1_sar_xxx.png",
    "provider": "copernicus",
    "bbox": [107.0, 30.4, 107.4, 30.8],
    "expiresAt": "2026-09-08T12:34:56+00:00",
    "meta": { "start": "2026-08-01", "end": "2026-08-31", "maxCloud": 15 }
  }
}
```

### 16.4 任务状态机

```
queued ──► running ──► succeeded  (终态)
                │
                ├──► failed     (终态，message 含异常描述)
                │
                └──► cancelled  (终态，由用户主动取消)
```

- **进度语义**：`progress` 0..100，与 S2 搜索/下载、SAR 合成、上传各阶段对应。终态时固定为 100（success）或 0（fail/cancel）。
- **取消语义**：`POST /jobs/{jobId}/cancel` 仅在 `running` 状态生效；任务已是终态时静默返回成功（幂等）。
- **超时**：前端 `pollSentinelJob(jobId, onProgress, { timeoutMs: 5*60*1000 })` 默认 5 分钟超时；超时抛 Error 并附中文 message。
- **任务存储**：当前用 Python 进程内 `Dict[str, Dict]`（`_jobs`），**重启即丢失**；生产环境应替换为 Redis（key `sentinel:job:{jobId}` + TTL 24h）。

### 16.5 环境变量（ai-service 侧）

| 变量 | 默认 | 说明 |
|---|---|---|
| `COPERNICUS_CLIENT_ID` | 空 | Copernicus Data Space OAuth2 Client ID；为空时降级到演示模式 |
| `COPERNICUS_CLIENT_SECRET` | 空 | OAuth2 Client Secret；与 Client ID 配套 |
| `GEE_SERVICE_ACCOUNT_JSON` | 空 | Google Earth Engine 服务账号 JSON 路径；可选，启用 GEE 合成 |
| `GEE_PROJECT` | `projects/earth-engine-project` | GEE 项目 ID |
| `SENTINEL_CDN_BASE` | 空 | 结果 CDN 基础 URL；空则返回 `file://` 直链 |
| `SENTINEL_STORAGE_DIR` | `/tmp/sentinel_outputs` | 本地合成结果输出目录 |

> **申请 Copernicus Client ID**：注册 [Copernicus Data Space](https://dataspace.copernicus.eu/) →
> User Settings → OAuth Clients → Create New Client。客户端类型选 confidential，
> 权限勾选 `STAC search/read`。Client ID 与 Secret 写入 ai-service 环境变量。

### 16.6 前端封装（`src/services/_architecture/core/sentinelService.js`）

```js
import { composeSentinelScene, pollSentinelJob, describeJobStatus } from '@/services/sentinelService'

// 1) 提交合成任务
const { jobId, status, progress, message } = await composeSentinelScene({
  bbox: [107.0, 30.4, 107.4, 30.8],
  start: '2026-08-01',
  end:   '2026-08-31',
  maxCloud: 15,
  provider: 'copernicus',
})

// 2) 轮询直到终态（默认间隔 1500ms，最多 5 分钟）
const finalJob = await pollSentinelJob(jobId, (job) => {
  const { label, percent } = describeJobStatus(job)
  uiProgressBar.value = percent
  uiProgressLabel.value = label
})

// 3) 拿到结果 URL 后，叠加到 OL 地图
addOverlayLayer({
  zIndex: 250,
  url: finalJob.result.s2Url,    // 真彩底图
  opacity: 0.85,
})
if (finalJob.result.sarUrl) {
  addOverlayLayer({ zIndex: 251, url: finalJob.result.sarUrl, opacity: 0.55 })
}
```

`describeJobStatus()` 把 `status/progress` 转成中文展示文案：

| status | label | tone |
|---|---|---|
| `queued` | 排队中… | `pending` |
| `running` | 合成中（xx%） | `running` |
| `succeeded` | 合成完成 | `success` |
| `failed` | {message} | `error` |
| `cancelled` | 已取消 | `idle` |

### 16.7 后端 Java 透传（`AiController`）

Java 后端不直接处理 Sentinel 合成任务——前端或 AI 服务自身都直接调 Python 端点。
如需由 Java 统一代理（如鉴权 / 限流场景），可在 `AiController` 加转发端点：

```java
@PostMapping("/sentinel/compose")
public ApiResponse<Map<String, Object>> composeSentinel(@RequestBody Map<String, Object> body) {
  Map<String, Object> job = restTemplate.postForObject(
      properties.serviceBaseUrl() + "/sentinel/compose", body, Map.class);
  return ApiResponse.success(job);
}
```

> 当前 Java 侧仅在 §7.11「AI 服务自身接口」列表中给出 Sentinel 端点 URL；
> 前端 `services/_architecture/core/sentinelService.js` 默认 base 是 `import.meta.env.VITE_AI_SERVICE_BASE || '/api/ai'`，
> 生产部署时通过 Nginx 反向代理 `/api/ai/sentinel/*` 到 ai-service 容器 8000 端口。

### 16.8 降级与故障排查

| 故障点 | 表现 | 处理 |
|---|---|---|
| 未配 Copernicus Client ID/Secret | 返回 `_mock_s2_scene` 假场景 | UI 标注"演示模式"，prod 必须配置 OAuth |
| Copernicus STAC 401/403 | `_get_job` 抛 401 | 检查 `COPERNICUS_CLIENT_ID/SECRET`；过期需重新申请 |
| GEE 未认证 | 真实 S1/S2 拉取抛 earthengine 异常 | 配置 `GEE_SERVICE_ACCOUNT_JSON`、完成 `earthengine authenticate` |
| 任务超时（>5min） | `pollSentinelJob` 抛 Error | 检查 Copernicus 服务可用性 / 网络；jobId 仍在内存中可用 `cancelSentinelJob` 取消 |
| ai-service 重启 | 内存 `_jobs` 清空，已 submitted 任务丢失 | UI 显示「任务不存在」，引导用户重新提交 |
| Sentinel CDN 过期（24h） | `sarUrl/s2Url` 404 | 重新提交合成任务，URL 通过 `expiresAt` 提前预警 |

### 16.9 部署清单

| 服务 | 配置项 | 推荐 |
|---|---|---|
| ai-service | `COPERNICUS_CLIENT_ID/SECRET` | ✅ 生产必填 |
| ai-service | `SENTINEL_CDN_BASE` | 建议指向 OSS / MinIO 公网桶 |
| ai-service | `SENTINEL_STORAGE_DIR` | 挂载独立数据卷，避免 `/tmp` 满 |
| 前端 | `VITE_AI_SERVICE_BASE` | 生产建议 `/api/ai`（Nginx 反代），与其它 AI 端点统一 |
| 基础设施 | Nginx 反向代理 `/api/ai/sentinel` → ai-service:8000 | 避免浏览器 CORS |

### 16.10 单元测试

`ai-service/tests/` 当前未覆盖 Sentinel 合成管线（涉及 OAuth + 外部 STAC API）。
建议生产前补充：

```python
def test_compose_request_validation():
    # 缺 bbox / bbox 越界 / max_cloud 越界 → 422
    ...

def test_mock_s2_image_generation():
    # 演示模式 PIL 输出 512x512 RGB 数组
    ...

def test_job_lifecycle():
    # queued → running → succeeded；超时强制 fail
    ...
```

---

## 17. 国家气象数据接入（卫星云图 / 雷达 / 24h 降水 / 24h 积雪 / 干旱）

地质灾害（滑坡、崩塌、泥石流）与降水事件高度相关——24 小时内 50 mm 以上降水、连续降雨 5 天、土壤含水率超过阈值，往往直接触发灾害点复核流程。平台内置"国家气象数据中心"模块，把卫星云图、雷达拼图、降水量、积雪深度、干旱监测叠加到地图上，让值班员在做态势研判时能直接对比气象诱发因素。

### 17.1 数据源

| 产品 | 数据源 | 更新频率 | URL 模式 | 接入方式 |
|---|---|---|---|---|
| 卫星云图（FY-4A 中国区域） | 国家卫星气象中心 NSMC | 15 分钟 | `http://img.nsmc.org.cn/PORTAL/NSMC/XML/FY4A/FY4A_AGRI_IMG_REGI_MTCC_GLL.xml` | XML 清单第一项 |
| 全圆盘云图 | 国家卫星气象中心 NSMC | 15 分钟 | `http://img.nsmc.org.cn/PORTAL/NSMC/XML/FY4A/FY4A_AGRI_IMG_DISK_MTCC_NOM.xml` | XML 清单第一项 |
| 雷达回波（全国拼图） | 中央气象台 NMC | 6 分钟 | `https://image.nmc.cn/product/YYYY/MM/DD/RDP/medium/...JPG` | 时间倒推 + HEAD 探测 |
| 24h 降水量实况 | 中央气象台 NMC | 1 小时 | `https://image.nmc.cn/product/YYYY/MM/DD/RAIN24/medium/...jpg` | 时间倒推 + HEAD 探测 |
| 24h 积雪深度 | 中央气象台 NMC | 24 小时（每日 12 时） | `https://image.nmc.cn/product/YYYY/MM/DD/SDP/medium/...JPG` | 时间倒推 + HEAD 探测 |
| 干旱监测（农业干旱） | 中央气象台 NMC | 24 小时（每日 12 时） | `https://image.nmc.cn/product/YYYY/MM/DD/AGRI_DROUGHT/medium/...JPG` | 时间倒推 + HEAD 探测 |

两种数据源都是中央气象台 / 国家卫星气象中心的官方公开资源，**不需要 API key**，但需要服务器能访问外网。生产环境如不能直接访问公网，应在出网代理处将这两个域名放行。

### 17.2 架构与文件位置

```
backend/
├── src/main/java/cn/geosentry/weather/
│   ├── WeatherProductKind.java          # 6 个产品枚举 + extent + 官方刷新间隔
│   ├── controller/WeatherController.java # REST API（/api/weather/**）
│   ├── service/WeatherService.java      # 探测 + 缓存 + 图片代理转发
│   └── dto/                              # WeatherProductDto + WeatherLegendDto
└── src/main/java/cn/geosentry/config/
    ├── WeatherProperties.java           # @ConfigurationProperties(prefix="geosentry.weather")
    └── SecurityConfig.java              # /api/weather/** 白名单（无需 JWT）

src/
├── services/
│   ├── _architecture/core/geocodeService.js    # 地址 ↔ 经纬度
│   ├── _architecture/core/mapService.js        # OpenLayers + Cesium 双引擎
│   ├── _architecture/core/realtimeService.js   # WebSocket（ticket 鉴权）
│   ├── _architecture/core/sentinelService.js   # Sentinel-1/2 卫星合成
│   ├── _architecture/core/runtime.js           # 运行时环境（mock/真实切换）
│   ├── _architecture/core/errors.js            # 统一错误对象
│   ├── _architecture/core/healthService.js     # 服务健康检查
│   ├── _architecture/domain/weatherService.js  # 气象（NSMC + NMC + 后端代理）
│   ├── _architecture/domain/weatherApi.js      # OpenWeatherMap 实时天气（按需启用）
│   └── _architecture/domain/aiApi.js           # AI 服务客户端
├── views/WeatherPage.vue                # /dashboard/weather 独立页面（六卡片 + 主图）
├── components/WeatherOverlayLegend.vue  # OverviewPage 浮动 overlay 控制面板
└── stores/auth.js                       # ROLE_MENU_MAP + weather:view 权限
```

### 17.3 REST API（公共，无需鉴权）

| 端点 | 方法 | 说明 |
|---|---|---|
| `/api/weather/products` | GET | 一次性返回六项产品的最新帧元数据（含缓存命中） |
| `/api/weather/products/latest?kind=satellite` | GET | 拉取单个产品的最新帧，kind 取值：`satellite` / `satelliteDisk` / `radar` / `precip24h` / `snow24h` / `drought` |
| `/api/weather/products/refresh?kind=radr` | POST | 强制刷新单个产品（忽略缓存） |
| `/api/weather/legends` | GET | 六项产品的图例（颜色档位说明） |
| `/api/weather/image/{kind}` | GET | 代理转发最新一帧图片（CORS 兜底） |

返回示例（`/api/weather/products/latest?kind=radr`）：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "kind": "radar",
    "label": "雷达回波",
    "description": "中央气象台全国基本反射率拼图",
    "source": "nmc",
    "ok": true,
    "url": "https://image.nmc.cn/product/2026/09/07/RDP/medium/SEVP_NSMC_RDP_ACHN_LNO_PY_20260907123000000.JPG",
    "timestamp": "2026-09-07 12:30",
    "fetchedAt": "2026-09-07T12:34:08+08:00",
    "minLon": 73.0, "minLat": 16.0, "maxLon": 135.0, "maxLat": 54.0,
    "refreshIntervalMinutes": 6,
    "error": null
  },
  "timestamp": 1762514048123
}
```

### 17.4 配置项（`application.yml` → `geosentry.weather.*`）

```yaml
geosentry:
  weather:
    cache-enabled: ${WEATHER_CACHE_ENABLED:true}    # 是否对最新帧探测结果做内存缓存
    cache-ttl-seconds: ${WEATHER_CACHE_TTL_SECONDS:300}  # 缓存存活秒数
    proxy-enabled: ${WEATHER_PROXY_ENABLED:true}    # 是否启用 /api/weather/image/{kind} 代理
```

| 环境变量 | 默认 | 含义 |
|---|---|---|
| `WEATHER_CACHE_ENABLED` | `true` | 关闭后每次请求都会触发外网探测，不建议生产用 |
| `WEATHER_CACHE_TTL_SECONDS` | `300` | 探测结果缓存时长；卫星云图官方 15 分钟更新，设 300 偏保守 |
| `WEATHER_PROXY_ENABLED` | `true` | 关闭后 `/api/weather/image/{kind}` 返回 403，浏览器只能直连官方域 |

### 17.5 前端双模式：直连 / 后端代理

`weatherService.js` 支持两种数据获取方式：

| 模式 | 触发条件 | 说明 |
|---|---|---|
| 直连（默认） | `VITE_USE_BACKEND=false` | 前端直接 fetch NSMC XML + NMC 静态产品 URL，依赖浏览器 CORS；NSMC 不返回 CORS 头，因此前端内置 `allorigins.win` / `codetabs` / `corsproxy.io` 三个免费代理依次回退 |
| 后端代理 | `VITE_USE_BACKEND=true` | 通过 `/api/weather/products` 一次性拉六项产品的最新帧；图片源 URL 仍由官方域提供，浏览器直接 `<img>` 加载，与直连体验一致 |

两种模式自动互备：后端代理模式下，若 `/api/weather/*` 返回 4xx/5xx，前端自动回退直连（仅在浏览器能成功访问 NSMC/NMC 时生效），保证 overlay 不会整体崩。

### 17.6 地图叠加：OL ImageLayer

`src/services/mapService.js` 已封装 `createOpenLayersController` 中的 `createWeatherOverlayLayers()`、`setWeatherOverlay`、`refreshWeatherOverlay`、`setWeatherOverlayOpacity`、`getWeatherOverlayStates` 等接口：

- `refreshWeatherOverlay(kind, async () => payload)` — 接收 `{ url, timestamp }` 后立即刷新 OL ImageStatic 图源
- `setWeatherOverlay(kind, visible)` — 显示 / 隐藏
- `setWeatherOverlayOpacity(kind, 0..1)` — 调整透明度（默认 0.65）

图层 z-index = 200，位于底图与业务图层之间——保证灾害点 / 设备 / 预警标记不会被气象图源遮挡。

### 17.7 前端使用

#### OverviewPage（默认主页）
- 地图右上角浮动 `WeatherOverlayLegend` 组件，五个 overlay 各自独立开关
- 每个 overlay 项支持：开启 / 关闭、刷新（重新探测）、点开查看图例色带、调整透明度
- 顶部"刷新全部"按钮一次性预热六个产品

#### 气象数据中心（`/dashboard/weather`）
- 独立页面，左侧主图可叠加任意产品
- 右侧六张产品卡片，每张卡片：
  - 直接渲染最新一帧缩略图（`<img loading="lazy" referrerpolicy="no-referrer">`）
  - 显示发布时间（按官方 `timestamp` 字段，不是服务器时间）
  - 一键"叠加到主图" / 单独刷新 / 失败时重试

### 17.8 常见问题

#### Q11：地图右上角 overlay 开关变红 / 加载失败

**症状**：所有五个 overlay 都显示 `⚠ 失败` 徽章，控制台报 `NSMC HTTP 5xx` 或 `NMC product radar: no available slot in 600m`。

**根因**：
1. 容器内无外网访问权限（最常见于纯内网部署）；NSMC 和 NMC 都依赖出网
2. 公司代理拦截了 image.nmc.cn 或 img.nsmc.org.cn 的 HEAD 请求
3. 服务器时钟与北京时差超过 24 小时，导致时间倒推越过产品最早发布时间

**判断方法**：
```bash
# 在后端容器内 curl 测试连通性
docker exec geosentry-backend curl -I -m 5 https://image.nmc.cn/product/$(date -u +%Y/%m/%d)/RDP/medium/SEVP_NSMC_RDP_ACHN_LNO_PY_$(date -u +%Y%m%d%H%M)0000000.JPG

docker exec geosentry-backend curl -m 5 http://img.nsmc.org.cn/PORTAL/NSMC/XML/FY4A/FY4A_AGRI_IMG_REGI_MTCC_GLL.xml
```

**修复**：
1. 出网代理白名单 `*.nmc.cn`、`*.nsmc.org.cn`
2. 服务器启用 NTP / chrony 同步到北京时间
3. 临时绕过：把 `WEATHER_PROXY_ENABLED=false` 并让前端走直连（要求浏览器端能访问 NSMC/NMC）

#### Q12：缩略图能加载但主图叠加层变白

**症状**：`WeatherPage` 卡片能渲染缩略图，但点击"叠加到主图"后地图上没看到图源。

**根因**：当前视图的 zoom 太大（>12），图源 extent 是中国全境（73°E-135°E），单像素对应的地面距离已超过图源原图的精度上限，导致 OL 的 `imageExtent` 投投影到 3857 后落在可视范围之外。

**修复**：把主图缩放调到 5-7 级（中国全境视野），再点"叠加到主图"。

---

## 18. AI 综合分析 —— 全项目后台引擎

> **定位**：AI 综合分析（图像现况 + 当地降水量 + 当地地理情况 + 设备指标）不是孤立页面，
> 而是整个系统的**单一可信源（single source of truth）**。所有预警、分析、巡检、概览的
> 风险判断都基于它的输出。地脉智巡助手是它的自然语言入口。

### 18.1 数据流

```
                            ┌─────────────────────────────────────┐
                            │  Python ai-service                  │
  Java 聚合因子 ──────────▶ │  POST /api/ai/comprehensive         │
  (图像/降水/地理/设备)      │   ├ image_dimension.score           │
                            │   ├ precipitation_dimension.score   │
                            │   ├ geographic_dimension.score      │
                            │   ├ device_dimension.score          │
                            │   ├ XGBoost / 维度加权降级           │
                            │   └ reasoning + suggestedActions    │
                            └──────────────┬──────────────────────┘
                                           │
              ┌────────────────────────────▼───────────────────────────┐
              │  ai_comprehensive_score 表（UPSERT，每 5 分钟全量刷新）  │
              │  AiInsightService @Scheduled(0 */5 * * * *)            │
              └───┬──────────────┬──────────────┬──────────────┬───────┘
                  │              │              │              │
        ┌─────────▼───┐  ┌───────▼──────┐  ┌────▼─────┐  ┌─────▼──────┐
        │ 预警         │  │ 巡检         │  │ 分析      │  │ 概览/助手   │
        │AiWarning     │  │TasksPage     │  │Analytics │  │Overview /  │
        │Scheduler     │  │按分数排序    │  │AI 排行   │  │AiAssistant │
        │每 10 分钟    │  │加权完成率    │  │整体风险分 │  │灾害点排序   │
        │MEDIUM+→预警  │  │              │  │          │  │chat 分发   │
        └──────────────┘  └──────────────┘  └──────────┘  └────────────┘
```

### 18.2 核心文件

| 层 | 文件 | 职责 |
|---|---|---|
| Python | `ai-service/app/models/comprehensive_model.py` | 四维度评分 + reasoning/actions 生成 |
| Python | `ai-service/app/routers/comprehensive.py` | `POST /api/ai/comprehensive`、`GET /status` |
| Java | `ai/service/AiComprehensiveAnalysisSupport.java` | 因子聚合（降水/图像/地理/设备） |
| Java | `ai/service/AiInsightService.java` | **落库 + 5 分钟调度 + `chat()` 自然语言分发** |
| Java | `ai/entity/AiComprehensiveScore.java` | JPA 实体（unique on hazard_code） |
| Java | `warning/engine/AiWarningScheduler.java` | **AI 分 → 预警事件（每 10 分钟）** |
| 前端 | `views/AiInsightPage.vue` | `/dashboard/ai-insight` 综合报告页 |
| 前端 | `components/AiAssistant.vue` | 地脉智巡助手（接 `/api/ai/chat`） |

### 18.3 REST API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/ai/comprehensive-analysis?hazardCode=` | 单灾害点实时推理（不读缓存） |
| GET | `/api/ai/comprehensive-analysis/all` | 落库排行（按分数倒序），前端各页消费 |
| POST | `/api/ai/comprehensive-analysis/refresh` | 强制全量刷新（一键分析） |
| POST | `/api/ai/comprehensive-analysis/refresh-one?hazardCode=` | 单条刷新 |
| POST/GET | `/api/ai/chat` （`?q=`） | 自然语言入口，返回 `{intent, reply, attachment}` |

**chat 意图**：`emergency`（应急）/ `ranking`（TOP5 排行）/ `comprehensive`（单灾害点报告）/
`help` / `fallback`。demo 用关键词分发，生产可替换为 LLM 做函数调用。

### 18.4 落库表 `ai_comprehensive_score`

```sql
hazard_code            VARCHAR(64) UNIQUE → hazard_point(hazard_code)
risk_level             LOW / MEDIUM / HIGH / CRITICAL
risk_score             NUMERIC(5,2)
dimensions_json        TEXT  -- {image, precipitation, geographic, device}
reasoning_json         TEXT  -- string[]
suggested_actions_json TEXT  -- string[]
confidence / model / degraded / analyzed_at
```

### 18.5 配置项（`application.yml`）

```yaml
geosentry:
  ai:
    comprehensive-scheduler-cron: "0 */5 * * * *"          # 全量分析 + 落库
    comprehensive-to-warning-cron: "0 */10 * * * *"        # AI 分 → 预警联动
    comprehensive-to-warning-enabled: true                 # 关掉则不自动生成预警
```

### 18.6 降级策略

| 故障点 | 表现 | 处理 |
|---|---|---|
| Python ai-service 不可用 | `/comprehensive-analysis` 503 | 前端显示降级提示；落库调度跳过本轮，保留上次分数 |
| 落库表为空（首次启动） | 各页 AI 区域为空 | 显示"暂未分析"，可点"一键分析全部灾害点" |
| AI 模块整体不可用 | 助手 `chat` 失败 | `AiAssistant` 退化为本地 FAQ 问答，主流程不受影响 |

---

## 19. AI 设备健康评分 —— 监测设备由 AI 研判

> 继 AI 综合分析之后第二个"AI 后台"能力。**每台 monitoring_device** 都由 AI 给出
> health_score（0-100）+ health_level（HEALTHY / ATTENTION / WARN / CRITICAL）。
> DevicesPage / DeviceDetailPage / 概览 / 应急 / 助手排行榜全部消费这张表。

### 19.1 数据流

> **架构原则**：所有"AI 算法"集中在 Python ai-service，Java 仅做调度 / 落库 / 数据准备。
> 设备健康评分由 Python **一次端点** `/api/ai/device-health/score` 完成
> （多指标异常检测 + 位移预测 + 加权融合 + 推理链 + 建议生成）。

```
                            ┌─────────────────────────────────────┐
                            │  Python ai-service                  │
  Java 拉指标 (近 30 条) ───▶│  /api/ai/device-health/score          │
  disp_rate / disp_total /  │   内部分别调用 anomaly + forecast     │
  crack_width / tilt        │   加权融合 + reasoning/actions        │
  + online_status           └──────────────┬──────────────────────┘
  + last_report_at                         │
              ┌────────────────────────────▼───────────────────┐
              │  ai_device_health 表 (UPSERT, 每 5 分钟全量)    │
              │  AiDeviceHealthService @Scheduled(0 */5 * * *) │
              └───┬──────────┬──────────┬──────────┬───────────┘
                  │          │          │          │
           DevicesPage  DeviceDetail 概览/应急  助手排行
           (列表按分排序) (研判区)  (异常计数)  "哪些设备异常"
```

### 19.2 评分规则

| 因子 | 权重 | 来源 |
|---|---|---|
| **异常分 worstAnomaly** | 60% | ai-service `/anomaly` 对每条核心指标的 isolation-forest / z-score，0-100 |
| **预测风险 forecastRisk** | 30% | ai-service `/forecast` 对 disp_rate 序列的 7 天预测，>10mm 视为高风险 |
| **离线惩罚** | -10 / -25 | 设备 offline 或 last_report > 30min |
| **数据覆盖不足** | -5 | 参与评估的指标 < 2 条 |

`health_score` 区间映射 `health_level`：
- ≥80 → **HEALTHY** 健康
- 60-79 → **ATTENTION** 注意
- 40-59 → **WARN** 预警
- <40 → **CRITICAL** 严重

### 19.3 REST API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/ai/device-health/all` | 全量（按 healthScore 升序，异常在前） |
| GET | `/api/ai/device-health?deviceCode=` | 单条查询 |
| POST | `/api/ai/device-health/refresh` | 强制全量重算 |
| POST | `/api/ai/device-health/refresh-one?deviceCode=` | 单条重算 |

`chat` 也支持设备意图：用户问「哪些设备异常」/「设备健康」时，
`AiInsightService.chat()` 返回 `{intent: "device_health", attachment: {items, total}}`。

### 19.4 落库表 `ai_device_health`

```sql
device_code          VARCHAR(64) UNIQUE → monitoring_device(device_code)
health_level         HEALTHY / ATTENTION / WARN / CRITICAL
health_score         NUMERIC(5,2)            -- 0-100, 越高越健康
anomaly_score        NUMERIC(5,2)            -- 最严重指标的异常概率
forecast_risk        NUMERIC(5,2)            -- 7 天位移预测风险
metric_coverage      INTEGER                 -- 参与评估的指标条数
last_anomaly_at      TIMESTAMPTZ
dimensions_json      TEXT                    -- {anomalyByMetric, forecastRisk, onlineStatus, ...}
reasoning_json       TEXT
suggested_actions_json TEXT
```

### 19.5 配置项

```yaml
geosentry:
  ai:
    device-health-scheduler-enabled: ${AI_DEVICE_HEALTH_ENABLED:true}
    device-health-scheduler-cron:    ${AI_DEVICE_HEALTH_CRON:0 */5 * * * *}
```

### 19.6 降级策略

- Python 不可用：本台设备跳过评估，日志告警，下一轮 5 分钟重试，其它设备不受影响（`AiDeviceHealthService.analyzeOne` 的 try-catch）。
- Python 可用但模型文件缺失（如 `isolation_forest.joblib` / `lstm_displacement.pt` 不存在）：
  算法侧在 `degraded=true` 标位下用启发式（3σ / 移动平均）继续评分，结果正常落库。
- 设备无任何指标样本：落 `metric_coverage=0`，health_score 仅扣 5 分覆盖不足惩罚，仍写一行。
- 助手 `device_health_empty` 意图：引导用户点 "AI 重算"。

### 19.7 AI 算法集中化（python 端）

> 与 §18「AI 综合分析」保持同样的架构原则：
> Java `AiDeviceHealthService` 只做"取数据 → 调 Python → 落库"，所有启发式
> （worstAnomaly 加权、OFFLINE_PENALTY 阈值、reasoning/actions 生成、_score_to_level 分级）
> 都已迁入 `app/models/device_health_model.py`。Java 端的 `levelFromScore` 等静态方法已移除。
>
> 新增 Python 端点：`POST /api/ai/device-health/score`（算法主入口）、
> `GET /api/ai/device-health/status`（模型加载探测）。
>
> Java DTO：`dto/DeviceHealthRequestDto` / `dto/DeviceHealthResponseDto`（snake_case 透传 `@JsonAlias`）。

---

## 20. AI 灾害评估 v1.3.0 —— 单独测算 + 灾害类型分类

> **核心定位**：从 v1.3.0 起，AI 不再假设"必须有监测设备"——系统是基于
> 「**当地地理状况 + 当地天气状况**」+ 可选设备位移，AI 给出三件套：
> - `risk_level`   — LOW / MEDIUM / HIGH / CRITICAL
> - `concern_label`— 中文「**稳定 / 关注 / 预警 / 异常严重**」
> - `disaster_type`— **六大灾害类型分类**（滑坡 / 崩塌 / 泥石流 / 地裂缝 / 地面沉降 / 塌陷）
>
> 此能力是 §18「AI 综合分析」之后的**第二条独立测算链路**：可单独调，端到端不需要任何监测设备支撑。

### 20.1 数据流

```
                            ┌─────────────────────────────────────┐
                            │  Python ai-service                  │
  Java (hazard + factors) ─▶│  /api/ai/disaster-assessment/score   │
  hazard_point + 地理+ 天气  │   内部分别评 地理 / 降水 / 设备 3 维度 │
  + 可选设备位移             │   设备缺失时自动调整权重 0.55/0.45/0   │
  + hazardType(可能空)      │   输出 concern_label + disaster_type  │
                            └──────────────┬──────────────────────┘
                                           │
              ┌────────────────────────────▼────────────────────┐
              │  ai_comprehensive_score 落库（每 5 分钟全量调度）│
              │  AiInsightService.analyzeOnePersist()            │
              │  + 单条 immediate 端点 /disaster-assessment        │
              └───┬──────────┬──────────┬──────────┬──────────────┘
                  │          │          │          │
        HazardsListPage  HazardDetail  Overview  AiAssistant
        (AI 分排序+     (综合研判)    (地图)    ("灾害类型是
         类型徽标)                              什么"意图)
```

### 20.2 灾害类型分类算法

`app/models/disaster_assessment_model.py` 内置 6 大类型 + 30+ 启发式规则，按三步走：

1. **优先采用 hazard_point.hazard_type**：如果档案里已标 `滑坡`/`崩塌`/...，`source='declared'`、`confidence=0.95`，**完全短路** AI 推断，直接采纳。
2. **特征投票**：把 (slope/soil/vegetation/distance/geology/rain/displacement/crack) 转 13 个布尔特征，按类型权重投票（泥石流 30% 极强降雨 + 25% 近沟谷，地面沉降 15% 高压缩土 + 10% 稀疏植被……）。
3. **没把握 → "其他"**：`top_score < 0.20` 或与次高分差 < 0.05 时归"其他"，防止乱归类。

```python
SUPPORTED_DISASTER_TYPES = ["滑坡", "崩塌", "泥石流", "地裂缝", "地面沉降", "塌陷"]
```

### 20.3 关注度四级映射（业务可读）

| risk_level | concern_label | 视觉色  | 业务动作 |
|---|---|---|---|
| LOW | 稳定 | teal | 常规观测 |
| MEDIUM | **关注** | blue | 加派 1 次/2h 加密观测 |
| HIGH | **预警** | orange | 启动应急会商 + 现场警戒 |
| CRITICAL | **异常严重** | red | 24h 不间断监测 + 立即转移 |

### 20.4 权重 vs. 设备接入

```python
BASE_WEIGHTS = {"geographic": 0.50, "precipitation": 0.40, "device": 0.10}
# 设备缺失（has_device_factors()==False）时自动调整：
DEVICE_ABSENT_WEIGHTS = {"geographic": 0.55, "precipitation": 0.45, "device": 0.0}
# 此时 overall.mock=True, device_available=False，前端 UI 显式提示"未接入监测设备"
```

### 20.5 REST API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/ai/disaster-assessment?hazardCode=` | **单独测算主入口**：Java 聚合 hazard + 因子 → Python 灾害评估 → 三件套 |
| GET | `/api/ai/disaster-assessment/status` | 字典自检（disasterTypes / concernLabels / 权重） |
| POST | `/api/ai/comprehensive-analysis/refresh-one?hazardCode=` | 强制重算 + 写回 ai_comprehensive_score（落库路径，仍走 disaster-assessment 端点） |

### 20.6 ai_comprehensive_score 落库表 v1.3.0 新增列

```sql
disaster_type            VARCHAR(32)       -- 滑坡/崩塌/泥石流/地裂缝/地面沉降/塌陷/其他
concern_label            VARCHAR(32)       -- 稳定/关注/预警/异常严重
disaster_type_source     VARCHAR(32)       -- declared / inferred / fallback
disaster_type_confidence DOUBLE PRECISION  -- 分类置信 0-1
device_available         BOOLEAN           -- false = 本次评估完全依据 地理+天气，未纳设备
```

迁移路径在 `schema.sql` 通过 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` 幂等补齐，老库不留尾巴。

### 20.7 HazardDto 注入（前后端衔接）

`AiRiskEnricher.AiRiskBrief` v1.3.0 增加 4 字段（disasterType / disasterTypeSource / concernLabel / deviceAvailable），
AiRiskEnricher 自动注入到 `HazardDto`（已有 `aiLevel/aiScore/aiAnalyzedAt` 之外）。

前端通过：
- `dashboardApi.attachAiScores(rows, aiList)` 统一把字段透传给所有列表
- `HazardDto.aiDisasterType` / `aiConcernLabel` / `aiDeviceAvailable` 直接在 Vue 模板用

### 20.8 前端 AiInsightPage 三件套

综合评分卡顶部一次性展示三个标签：
- **风险等级** badge（青/蓝/橙/红）
- **关注度**「关注 / 预警 / 异常严重」业务标签
- **灾害类型**「滑坡 / 崩塌 / 泥石流 ...」（按 6 类型区分视觉色）

下方新增「灾害类型分类依据」段落，列出 AI 推理该类型的关键原因（坡度+降雨+沟谷距离等组合）。

### 20.9 降级策略

- **AI 不可用**：直接抛 `AiUnavailableException`，落库调度 catch 后跳过本条；前端按钮提示"AI 服务不可用"。**不阻塞主业务**。
- **Python 可用但模型缺失**：全部走 `heuristic-geo-weather` 启发式，`degraded=true`；灾害类型仍可推断（投票算法本身不依赖模型）。
- **完全无因子**（前端 bypass 错）：Python 端点返 400，前端 toast 提示。
- **hazardType 字典缺失**：自动落到"inferred"路径或"其他"，不影响总分。

---

## 21. AI 算法完善 —— 衍生特征 + 自适应归一化 + SHAP 贡献度 + 反馈学习（v1.4.0）

### 21.1 设计动机

v1.3.0 之前所有 AI 算法都用硬编码 NORM_MAX（如 rainfall_24h=150mm）和关键词投票分类，
两个明显短板：
1. **不同区域 NORM_MAX 应该不同**（西北 50mm 已极端，东南 200mm 才算异常），但硬编码值让
   区域间分数不可比；
2. **灾害类型分类用"特征命中即投票"**，无法解释"为什么是这个类型"。

v1.4.0 引入 4 个核心增强：

| 模块 | 文件 | 作用 |
|---|---|---|
| **特征工程** | `app/models/feature_engineering.py` | 6 个衍生特征：降雨饱和指数、骤雨强度、持续天数、坡脚侵蚀、地质×距水耦合、位移加速度 |
| **自适应归一化** | `app/models/adaptive_normalizer.py` | 按 (factor, region) 维护滑动窗口 P95，样本 < 10 时回退 fallback |
| **多因子分类** | `disaster_assessment_model.py` 的 `TYPE_FEATURES` | 把"关键词投票"升级为"Σ(归一化值 × 类型专属权重)"，并加 `TYPE_LEAD_MARGIN=0.05` 防歧义 |
| **SHAP-like 贡献** | `compute_feature_contributions()` | 每条 reasoning 带 contribution_score，前端条形图可视化 |
| **反馈学习闭环** | `feedback_store.py` + `routers/feedback.py` + `train_xgboost.py` | 值班员提交实际事件 → 存 FeedbackStore → /retrain 触发训练 |

### 21.2 衍生特征公式

```
_derived_rain_saturation_index  = (rainfall_7d / 250) × (soil_grade / 5) × (1 - vegetation_cover)
                                  含义：长期雨+易蚀土+稀疏植被 → 高饱和风险
_derived_rain_intensity_ratio    = rainfall_24h / rainfall_7d     (钳 [0,1])
                                  含义：24h 占比 → 接近 1 即"短时骤雨型"
_derived_rain_duration_days      = 7 × (rainfall_24h / rainfall_7d)  (钳 [0,7])
                                  含义：反推"近 7 天下了几天有效雨"
_derived_foot_erosion_index      = (slope_deg / 60) × (1 - distance_to_water_m / 5000)
                                  含义：陡坡×近水 → 坡脚冲刷型滑坡/泥石流前兆
_derived_geology_distance_score  = (geology_grade / 5) × (1 - distance_to_water_m / 5000)
                                  含义：脆弱地质×活跃水文 → 塌陷/地裂缝主诱因
_derived_disp_derivative         = last_disp_rate - mean(disp_rate_history)
                                  含义：位移加速度（mm/day²），> 0 即位移在加速
```

### 21.3 自适应归一化（P95 窗口）

每个 `(factor_key, region_code)` 维护一个 `deque(maxlen=200)` 滑动窗口：
- 每次 `score()` 都把当前观测值 `fit_one()` 推入窗口；
- `get_dynamic_max()` 返回 P95（线性插值），限制在 `[fallback × 0.5, fallback × 3.0]`；
- 窗口样本 < `MIN_SAMPLES=10` 时回退到 `FALLBACK_NORM_MAX` 硬编码值；
- 线程安全（`threading.Lock`）。

```python
normalizer = get_adaptive_normalizer()
normalizer.fit_one("rainfall_24h", 85.0, "CN-YN-001")
max_val = normalizer.get_dynamic_max("rainfall_24h", "CN-YN-001", fallback=150.0)
```

**为什么用 P95**：避免单点极端值污染上限；让"比本地历史同期高 95%"才算异常。

### 21.4 多因子加权分类

旧的"特征命中即投票"被 `TYPE_FEATURES` 表替代，每个灾害类型 = Σ(归一化特征值 × 类型专属权重)：

```python
DISASTER_TYPE_LANDSLIDE = {
    "_derived_rain_saturation_index": 0.30,
    "_derived_foot_erosion_index": 0.25,
    "slope_deg": 0.20,
    "geology_grade": 0.10,
    "rainfall_24h": 0.10,
    "_derived_disp_derivative": 0.05,
}
# 总和 1.0，缺因子时该维度贡献为 0（防止 distance=0 误判为"在水里"）
```

分类决策（v1.4.0）：
1. `hazardType` 已标注 → 短路返回 `source: "declared"`, `confidence: 0.95`；
2. 否则算 6 个 type 的得分；
3. `top_score < 0.18` → `source: "low_confidence"`, `disaster_type: "待定"`；
4. `top - second < 0.05` → `source: "ambiguous"`, `disaster_type: "待定"`；
5. 否则返回 winner + `type_scores` + Top-3 contributors。

### 21.5 SHAP-like 特征贡献度

`compute_feature_contributions(factors, region_code)` 返回：
```json
[
  {"feature": "rainfall_24h", "raw_value": 110.0, "norm_value": 0.73, "weight": 0.24, "contribution": 17.6},
  {"feature": "_derived_foot_erosion_index", "raw_value": 0.12, "norm_value": 0.12, "weight": 0.06, "contribution": 0.72},
  ...
]
```

前端用 `feature_contributions.slice(0, 8)` 渲染条形图，颜色按贡献度：
- `>= 20` 红（高风险因子）
- `>= 10` 橙（中风险）
- 其他绿

**非真 SHAP**：这是简化版"归一化值 × 维度权重"累加，不算边际贡献。
后续接 SHAP 库可无缝替换。

### 21.6 反馈学习闭环

数据流：
```
值班员/系统
  └─ POST /api/ai/feedback {hazard_code, predicted_level, predicted_score,
                              outcome, occurred, confidence, feedback_by, note}
       └─ Python routers/feedback.py → FeedbackStore.add()
            └─ deque(maxlen=10000) 内存存储；stats() 暴露 precision/recall/F1

后续触发训练：
  POST /api/ai/model/retrain {min_samples=20, force=false}
            └─ BackgroundTasks.add_task(_run_train)
                 └─ subprocess.run(python -m app.training.train_xgboost)
                      └─ FeedbackStore.to_training_rows() → XGBoost → models/xgboost_fusion.json
                           └─ 归档到 models/history/xgboost_fusion-YYYYMMDD-HHMMSS.json
```

**训练脚本骨架**（`app/training/train_xgboost.py`）：
- 读 FeedbackStore 转 `(X, y)`；
- 缺数据时 `skipped`（< `min_samples`）；
- 用 `binary:logistic` 训 XGBoost；
- 保存到 `models/xgboost_fusion.json` + 历史归档。

**当前限制**：训练数据只用 FeedbackStore（可能样本不足）；真实生产应同时拉
`hazard_metric_history` PostgreSQL 表补全 7 个原始因子。`train_xgboost.py` 已留
`load_training_data()` 扩展点。

### 21.7 单元测试

`ai-service/tests/` 4 个文件 61 个测试用例：

| 文件 | 用例数 | 覆盖 |
|---|---|---|
| `test_feature_engineering.py` | 21 | 4 大衍生因子的边界值 + compute_derived_features 集成 |
| `test_disaster_assessment.py` | 14 | 5 场景（低风险/极端/有设备/歧义/空因子）+ 衍生 + 自适应 + 反馈 + 响应契约 |
| `test_device_health.py` | 11 | 4 场景（在线正常/异常/离线/稀疏）+ 边界 + 衍生 + 契约 |
| `test_feedback_store.py` | 15 | CRUD + 混淆矩阵 + precision/recall + 训练导出 + 契约 |

运行：`cd ai-service && python -m pytest tests/ -v`

### 21.8 前端展示

`AiInsightPage.vue` 在原 reasoning + actions 之外，新增 **特征贡献度卡**：
- Top 8 因子横向条形图，颜色按贡献度；
- 「实际发生了 / 实际未发生」按钮 → POST /api/ai/feedback；
- 反馈状态显示「已反馈：实际发生」（绿）/「已反馈：未发生」（黄）。

`aiApi.js` 暴露：
- `submitFeedback(payload)` — POST /api/ai/feedback
- `getFeedbackStats()` — 直接调 Python `/api/ai/feedback/stats`（混淆矩阵 + adaptive_normalizer 快照）

### 21.9 Java 端新增

| 文件 | 改动 |
|---|---|
| `dto/FeedbackRequestDto.java` | record + `@JsonAlias`（snake_case 透传） |
| `AiServiceClient.java` | 加 `submitFeedback()` 方法 |
| `AiController.java` | 加 `@PostMapping("/feedback")` 端点 |

Java 端只做代理透传，所有算法 / 存储 / 训练逻辑都在 Python。

---

## 22. AI 算法完善 — 真实模型接入（v1.5.0）

> v1.4.0 把算法升级到「衍生特征 + 自适应归一化 + SHAP 贡献 + 反馈闭环」范式，但 XGBoost fusion
> 一直是 fallback 路径（`heuristic-weighted-fusion`）。v1.5.0 起在 demo 阶段先训练出真实 XGBoost 模型
> 文件，让推理路径自动从启发式切到真实模型。

### 22.1 模型文件结构

```
ai-service/models/
├── xgboost_fusion.json                  ← 主模型（XGBoost JSON 格式）
├── xgboost_fusion.meta.json             ← 训练元数据（指标/特征重要性/时间）
├── README.md                            ← models 目录说明
└── history/
    └── xgboost_fusion-YYYYMMDD-HHMMSS.json  ← 历史归档（每次训练自动备份）
```

**加载时机**：`app/models/xgboost_model.py` 单例 `_fusion_model` 启动时尝试加载 `xgboost_fusion.json`，
存在且合法 → `model_loaded=True`，推理走真实路径；否则 fallback 到加权融合 `mock=true`。

### 22.2 真实模型 ≠ fallback 的判定

| 来源 | `model` 字段 | `mock` 字段 |
|---|---|---|
| 真实 XGBoost | `xgboost-fusion` | `false` |
| 启发式 fallback | `heuristic-weighted-fusion` | `true` |
| disaster_assessment 整体（含 device 维度） | `heuristic-geo-weather+xgboost-fusion` | `false` |
| disaster_assessment 整体（无 device） | `heuristic-geo-weather+xgboost-fusion` | `true` |

前端 `AiInsightPage.vue` 综合卡显示「真实模型/启发式」徽标 + 训练时间 + 重载按钮。

### 22.3 训练数据来源

| 来源 | 占比 | 标注方式 |
|---|---|---|
| `FeedbackStore` 真实反馈 | 优先（如有） | 反推 7 维因子，weight × 3 |
| `SyntheticDataset` 合成数据 | 兜底（默认补到 1500 条） | 物理规则：`0.35*sat + 0.35*disp + 0.20*site + 0.10*quake ≥ 0.55 → label=1` |

合成数据生成器：`app/training/synthetic_data.py`，3 类样本：
- **synthetic-low**（35%）：低因子强制 label=0，确定性
- **synthetic-high**（25%）：高因子强制 label=1，确定性
- **synthetic-continuous**（40%）：连续区间按物理规则判定 label，让 XGBoost 学到非线形风险曲面

### 22.4 训练流程

```bash
# 命令行训练
python -m app.training.train_xgboost --samples 2000

# HTTP 触发（带热加载）
POST /api/ai/model/retrain {"samples": 2000, "val_ratio": 0.2}

# 训练后热加载
POST /api/ai/model/reload
```

训练参数（`train_xgboost.py`）：
- `max_depth=5`, `eta=0.08`, `subsample=0.85`, `colsample_bytree=0.85`
- `early_stopping_rounds=20`, `num_boost_round=200`
- 自动 `scale_pos_weight` 处理样本不均衡
- 8:2 train/val 拆分 + `seed=42`

### 22.5 API 端点（v1.5.0 新增）

| 端点 | 方法 | 作用 |
|---|---|---|
| `/api/ai/model/info` | GET | 模型路径/是否加载/训练时间/版本/样本数/val_metrics/feature_importance/history_files |
| `/api/ai/model/reload` | POST | 重新加载磁盘模型（清除单例） |
| `/api/ai/model/retrain` | POST | 同步训练新模型（小数据集 1-3s 完成）+ 自动 reload |

Java 透传端点（`AiController.java`）：
- `GET /ai/model/info` → `ApiResponse<Map<String, Object>>`
- `POST /ai/model/reload` → `ApiResponse<Map<String, Object>>`

### 22.6 真实数据冒烟（demo 训练结果）

| 场景 | risk_score | risk_level | model | mock |
|---|---|---|---|---|
| 极端高风险（位移25mm/d + 降雨130mm/24h + 场地4.5） | 99.87 | CRITICAL | xgboost-fusion | false |
| 低风险（位移0.5mm/d + 降雨5mm/24h + 场地1.5） | 0.12 | LOW | xgboost-fusion | false |

验证集指标：accuracy=0.995, precision=0.9826, recall=1.0, f1=0.9912
特征重要性 Top-3：site_grade (144.5), earthquake_mag (57.0), crack_width (50.0)

### 22.7 A/B 切换与生产建议

- demo 阶段：当前默认（合成 + 真实模型文件就绪）即可
- 接入生产：把 `load_training_data()` 里的 `_load_feedback_rows` 替换为「读 PostgreSQL
  `hazard_metric_history` 表 + JOIN feedback」即可，无需改其它代码
- 模型升级：训练后模型文件 + meta 自动覆盖，无需重启服务（用 `/model/reload` 即可）
- 多版本：A/B 测试时把 `MODELS_DIR` 改为可配置，每个实验用一个独立子目录

### 22.8 单元测试覆盖

- `tests/test_synthetic_data.py`（6 用例）：生成数量/正负比例/高低因子一致性
- `tests/test_xgboost_model.py`（6 用例）：路径/单例/加载/CRITICAL/LOW/meta 完整性

总计 **73 用例 passed in 0.94s**（v1.4.0 的 61 + 本次新增 12）。

---

## 23. 数据库全量入库 —— 零虚拟数据（v1.5.0）

> v1.5.0 起 demo 阶段任何页面看到的数据都必须来自 PostgreSQL。本节说明"无虚拟数据"的边界、
> data.sql 大规模种子、device_metric 持续喂入机制、AI 启动 warmup。

### 23.1 数据流全景

```
┌────────────────────────────────────────────────────────────────────┐
│  PostgreSQL + PostGIS                                              │
│  ├─ sys_role / sys_user / sys_menu / region     (3/3/9/12 行)     │
│  ├─ hazard_point                                 (25 行)           │
│  ├─ monitoring_device                            (60 行)           │
│  ├─ inspection_task                              (40 行)           │
│  ├─ inspection_record                            (150 行)          │
│  ├─ warning_rule / warning_event                 (5/50 行)         │
│  ├─ emergency_event                              (10 行)           │
│  ├─ file_attachment / risk_score_log             (3/3 行)          │
│  ├─ operation_log                                (30 行)           │
│  ├─ device_metric                                (~1500 行)        │← DeviceMetricSimulator 持续喂
│  ├─ ai_comprehensive_score                       (25 行)            │← AiInsightService.refreshAll
│  └─ ai_device_health                             (60 行)            │← AiDeviceHealthService.refreshAll
└────────────────────────────────────────────────────────────────────┘
        ↑                                          ↑
   启动加载 SQL                                  @Scheduled 5min
   data.sql (mode:always, ON CONFLICT)          + AI warmup 启动立即跑
```

### 23.2 data.sql 种子策略

文件：`backend/src/main/resources/data.sql`（1997 行，~210KB）

**生成器**：`scripts/gen_data_sql.py`（按 dependency order 拼 SQL，确保 FK 顺序不报错）

**幂等性**：每个 INSERT 段都用 `ON CONFLICT DO NOTHING`（PK/UNIQUE 约束兜底），所以重复启动不会报 duplicate key 错误。

**dependency order**（自上而下）：
1. sys_role / region / sys_user / sys_menu（无 FK）
2. hazard_point → region
3. monitoring_device → hazard_point
4. inspection_task → hazard_point + sys_user
5. inspection_record → inspection_task
6. warning_rule（独立）+ warning_event → warning_rule + hazard_point
7. emergency_event → warning_event
8. file_attachment / risk_score_log（独立）
9. operation_log（独立）
10. device_metric → monitoring_device + hazard_point
11. ai_comprehensive_score → hazard_point
12. ai_device_health → monitoring_device

**生成新种子**：
```bash
python scripts/gen_data_sql.py > backend/src/main/resources/data.sql
```

### 23.3 device_metric 持续喂入（demo 阶段）

文件：`backend/src/main/java/cn/geosentry/device/scheduler/DeviceMetricSimulator.java`

**触发**：`@Scheduled(fixedDelayString = "300000")`（5 分钟一跑，initialDelay=15s）

**生成规则**：
- 每个 monitoring_device 按 `device_type` 喂对应 metric_code：
  - GNSS位移 → `disp_rate` + `disp_total`
  - 裂缝计 → `crack_width`
  - 倾角计 → `tilt`
  - 雨量计 → `rain_1h` + `rain_24h`
- 噪声幅度按 hazard.risk_level：高风险×1.8 / 关注×1.3 / 正常×0.7
- 值在物理合理范围（如位移不允许负、雨量不允许负）
- batch save（60 台设备 100ms 内完成）

**关掉**：环境变量 `METRIC_SIMULATOR_ENABLED=false`

**生产替换**：注释指向 `MqttDataSubscriber`（MQTT 桥接）+ `POST /api/devices/{deviceCode}/data`（HTTP 上报）。

### 23.4 AI 启动 warmup

文件：`backend/src/main/java/cn/geosentry/ai/scheduler/AiStartupWarmup.java`

**触发**：`ApplicationReadyEvent`（Spring 启动就绪后 3s，异步执行）

**执行**：先 `insightService.refreshAllNow()`（25 个 hazard），再 `deviceHealthService.refreshAllNow()`（60 个 device）。

**效果**：应用启动后访问 /ai-insight 立即拿到 AI 评分（不用等 5 分钟 cron）。

**关掉**：`AI_STARTUP_WARMUP_ENABLED=false`

### 23.5 前端强制 backend 模式

文件：`src/.env`（v1.5.0 新增）

```bash
VITE_USE_BACKEND=true
VITE_API_BASE_URL=http://localhost:8080/api
```

**任何页面没有数据 = 数据库真的没数据，不是 mock**。

`AiInsightPage.vue` 已经从直接 `import { hazardPoints } from '../data/dashboardData'` 改为 `dashboardApi.getHazards()` API 调用。

`dashboardData.js` 改为纯展示性元数据保留，注释明确"仅作 demo 离线 fallback 用"。

### 23.6 端到端启动流程

```bash
# 1. 启动数据库 + Redis
docker compose up -d postgres redis

# 2. 启动后端（自动执行 schema.sql + data.sql）
cd backend
mvn spring-boot:run

# 3. 启动 Python AI 服务
cd ai-service
python -m uvicorn app.main:app --reload --port 8000

# 4. 启动前端
cd ..
npm run dev

# 访问 http://localhost:5173 → 25 个 hazard + 60 个 device + 50 个 warning 全部从数据库读取
```

### 23.7 单元测试

无新增测试（数据层不涉及 Python 算法）。新增 2 个调度器都是稳定运行：
- DeviceMetricSimulator 跑 60 台 < 100ms
- AiStartupWarmup 跑 25 个 hazard < 30s（异步，不阻塞启动）

---

## 24. 多源数据融合体系

> AI 智能巡检监测自然灾害需要多源数据融合，本系统整合以下六类核心数据。
> 详细数据源规范、分辨率、接入方式见各子章节。

### 24.1 核心遥感影像数据（用于识别地表灾害特征）

| 数据名称 | 传感器/卫星 | 空间分辨率 | 时间分辨率 | 用途 | 推荐接入方式 |
|---|---|---|---|---|---|
| Sentinel-1 SAR | C波段雷达 | 10 m（GRD） | 6天（双星） | 洪水、滑坡、地表形变（InSAR）、地震同震位移 | Copernicus Data Space API / GEE |
| Sentinel-2 多光谱 | MSI | 10/20/60 m | 5天（双星） | 植被指数、水体提取、火灾迹地、干旱监测 | Copernicus Data Space / Sentinel Hub |
| Landsat 8/9 | OLI/TIRS | 15/30/100 m | 16天 | 长期土地覆盖变化、历史对比 | USGS EarthExplorer / GEE |
| MODIS / VIIRS | 中分辨率 | 250/500/1000 m | 每日多次 | 大范围火点、气溶胶、洪水快速初判 | NASA GIBS / FIRMS API / GEE |
| 高分辨率商业（可选） | Maxar/Planet | <1 m | 按需 | 建筑物损毁评估、精细滑坡边界 | Planet API（学生可申请试用） |

### 24.2 气象与水文数据（用于触发条件分析和预警）

| 数据名称 | 内容 | 时空分辨率 | 用途 | 推荐接入来源 |
|---|---|---|---|---|
| ERA5 再分析 | 降雨、温度、风速、湿度、气压等 | 0.25° 逐小时 | 降雨阈值分析、干旱指数、台风路径环境场 | ECMWF CDS API（免费注册） |
| GPM IMERG | 全球卫星降雨 | 0.1° 每30分钟 | 实时降雨监测、滑坡/洪水降雨触发 | NASA GES DISC / EarthData API |
| SMAP 土壤湿度 | 雷达/辐射计土壤水分 | 9 km / 3 km | 干旱监测、滑坡前期土壤饱和度 | NSIDC / EarthData |
| 河流水位（可选） | 水文站实测 | 站点级 | 洪水演进验证 | 国家水文部门公开数据或 GRDC |

### 24.3 地形与地质数据（用于易发性评估和特征提取）

| 数据名称 | 内容 | 分辨率 | 用途 | 推荐接入来源 |
|---|---|---|---|---|
| SRTM DEM | 全球数字高程模型 | 30 m | 坡度、坡向、汇流分析、地形湿度指数 | NASA EarthData / GEE |
| Copernicus DEM | 全球DEM | 30 m（部分地区10 m） | 更高精度地形分析 | Copernicus Data Space（需注册） |
| 地质图/断层数据 | 岩性、构造 | 矢量 | 地震、滑坡易发性因子 | USGS / 各国地质调查局 |
| 土壤类型 | 土壤物理属性 | 矢量/栅格 | 渗透性、滑坡敏感性 | FAO HWSD / ISRIC SoilGrids |

### 24.4 历史灾害与承灾体数据（用于模型训练和风险评估）

| 数据名称 | 内容 | 用途 | 推荐接入来源 |
|---|---|---|---|
| EM-DAT | 全球灾害事件库（类型、时间、地点、损失） | 模型训练标签、风险区划 | EM-DAT 官网下载 CSV/API |
| GDACS | 全球灾害警报和影响评估（地震/洪水/台风） | 实时灾害事件信息，用于触发分析 | GDACS RSS/API |
| Copernicus EMS | 重大灾害快速测绘产品（洪水/火灾范围） | 高精度验证数据 | Copernicus EMS 网站下载 |
| WorldPop / GHS | 人口密度分布 | 风险评估、影响人口估算 | WorldPop API / GHSL |
| OpenStreetMap | 建筑物、道路、土地利用 | 承灾体暴露度、基础设施损毁评估 | OSM API / Overpass API |

### 24.5 地面传感器与实时观测数据（用于快速预警和验证）

| 数据名称 | 内容 | 用途 | 推荐接入方式 |
|---|---|---|---|
| USGS 地震目录 | 实时地震事件（震级、位置、深度） | 地震触发报警，SAR 形变分析触发 | USGS FDSN Event Web Service API |
| 全球地震台网（可选） | 地震波形（加速度） | 强震动预警 | IRIS DMC Web Services |
| 现场 IoT 传感器（可选） | 雨量计、位移计、倾斜仪、水位计 | 高时间分辨率前兆监测 | 自建或第三方 IoT 平台（如 ThingsBoard） |

### 24.6 辅助数据（可选，用于增强分析或可视化）

| 数据名称 | 内容 | 用途 | 推荐接入来源 |
|---|---|---|---|
| NASA Black Marble | 夜间灯光 | 断电范围评估、灾后影响 | NASA LAADS / GEE |
| 社交媒体 API | 微博/Twitter/X 帖文 | 灾情现场文字/图片，验证模型 | 微博开放平台 / Twitter API v2 |
| 行政区划矢量 | 国/省/市边界 | 结果分区统计 | Natural Earth / GADM |

### 24.7 因子 schema（与代码对齐）

| 维度 | 因子 | 单位 | 说明 |
|---|---|---|---|
| [1] 遥感影像 | image_anomaly_score | 0-100 | YOLO/卫星分析异常分数 |
| [1] 遥感影像 | image_source | str | sentinel1/sentinel2/landsat/modis/radar/drone/camera |
| [2] 气象水文 | rainfall_24h/7d/30d | mm | 累计降雨量 |
| [2] 气象水文 | temperature | °C | 气温 |
| [2] 气象水文 | soil_moisture | 0-1 | 土壤含水率 |
| [3] 地形地质 | elevation_m/slope_deg/aspect_deg | m/° | 地形参数 |
| [3] 地形地质 | geology_grade/fault_distance_m | 1-5/m | 地质参数 |
| [4] 传感器 | displacement_rate/crack_width | mm/day/mm | 实时监测 |
| [4] 传感器 | earthquake_mag/tilt_angle | M/° | 地震/倾斜 |
| [5] 历史灾情 | historical_count/max_score | int/0-100 | 历史统计 |
| [5] 历史灾情 | exposure_population/buildings | 人/栋 | 承灾体 |
| [6] 辅助 | social_alert_level (0-4) | int | 舆情等级 |
| [6] 辅助 | nighttime_light_index | 0-63 | 夜间灯光 |

---

## 25. AI 实时灾害监测 v1.6.0/v1.7.0 —— 全国版

> **核心动机**：v1.5.0 之前所有灾害点都是 `data.sql` 种子预置的——演示可以、生产不行。
> v1.6.0/v1.7.0 起，系统不再依赖人工预置灾害点，而是从 `region` 表动态加载全国乡镇/村级
> 行政区划，每 90 秒**网格扫描 + AI 检测 + 自动入库**，是真正的「全国版」实时灾害监测。

### 25.1 数据流

```
                            ┌─────────────────────────────────────┐
                            │  region 表 (5 级行政区划)           │
                            │   ├ region_code                     │
                            │   ├ center_lon / center_lat (锚点)  │
                            │   ├ geo_type (PLAIN/HILL/MOUNTAIN/  │
                            │   │             COASTAL 决定步长)    │
                            │   └ region_level (village/town/...) │
                            └────────────────┬────────────────────┘
                                             │
              ┌──────────────────────────────▼───────────────────────────────┐
              │  AiHazardMonitorScheduler (Java, @Scheduled fixedDelay 90s)  │
              │   1. loadMonitorableRegions() 过滤 + 限流 (maxRegionsPerTick)│
              │   2. buildGridPoints(regions)  3×3 = 9 点 / region           │
              │   3. currentWeatherSnapshot()    季节性 + 扰动天气           │
              │   4. aiClient.detectHazardsBatch(points, "HIGH")            │
              └──────────────────────────────┬───────────────────────────────┘
                                             │
              ┌──────────────────────────────▼───────────────────────────────┐
              │  Python ai-service: POST /api/ai/detect-batch               │
              │   ├ 输入: [{lon, lat, region_code, geo_type, weather, ...}]  │
              │   ├ 多维度推理: 地形+气象+历史+邻近性                       │
              │   └ 输出: [{region_code, hazard_type, lon, lat,            │
              │              risk_level, confidence, ...}]                   │
              └──────────────────────────────┬───────────────────────────────┘
                                             │
              ┌──────────────────────────────▼───────────────────────────────┐
              │  Java 落库: hazard_point                                     │
              │   ├ source='AI_MONITOR' (区别于 MANUAL/DEMO)                 │
              │   ├ discovered_at = now()                                    │
              │   ├ detection_confidence = AI 给出的 0-1 置信度             │
              │   └ risk_level 映射: CRITICAL/HIGH → 高风险 / MEDIUM → 关注  │
              │                                                               │
              │  + WebSocket 广播 (RealtimeBroadcastService.broadcastHazard) │
              └──────────────────────────────────────────────────────────────┘
```

### 25.2 核心文件

| 层 | 文件 | 职责 |
|---|---|---|
| Java | `ai/scheduler/AiHazardMonitorScheduler.java` | 主调度（90s 周期 + 动态 region + 3×3 网格 + 批量 AI + 落库 + 广播） |
| Java | `region/entity/Region.java` | region 实体（5 级 + 锚点 + geo_type） |
| Java | `region/repository/RegionRepository.java` | region JPA 仓储 |
| Java | `hazard/service/HazardService.java` | `createFromAiDetection()` 落库（自动生成 hazard_code）+ 防重复（已存在不创建） |
| Java | `realtime/RealtimeBroadcastService.java` | `broadcastHazard()` 推送给前端 WebSocket 订阅者 |
| Python | `ai-service/app/routers/sentinel.py`（或新检测端点） | `detect-batch` 端点：批量输入网格点 + 批量返回高风险点 |
| 前端 | `views/HazardsListPage.vue` | `/dashboard/hazards` 列表展示（按 `discovered_at DESC` 排序） |
| 前端 | `views/OverviewPage.vue` | `/dashboard` 实时地图标记 AI 新发现的灾害点 |

### 25.3 region 表关键字段（v1.7.0）

```sql
CREATE TABLE region (
  id BIGSERIAL PRIMARY KEY,
  parent_id BIGINT,
  region_code VARCHAR(64) NOT NULL UNIQUE,
  region_name VARCHAR(128) NOT NULL,
  region_level VARCHAR(32) NOT NULL,    -- province / city / district / town / village
  path TEXT,                            -- "100000/100100/100101" 层级路径
  sort_order INT NOT NULL DEFAULT 0,
  center_lon NUMERIC(10,6) NOT NULL DEFAULT 105.0,   -- AI 网格扫描锚点经度
  center_lat NUMERIC(10,6) NOT NULL DEFAULT 35.0,    -- AI 网格扫描锚点纬度
  geo_type VARCHAR(16) NOT NULL DEFAULT 'HILL',      -- PLAIN/HILL/MOUNTAIN/COASTAL
  ...
);
CREATE INDEX idx_region_monitorable ON region(region_level, geo_type)
  WHERE region_level IN ('village', 'town', 'district');
```

### 25.4 网格步长策略

| `geo_type` | 步长（度）| 步长（km）| 适用场景 |
|---|---|---|---|
| `MOUNTAIN` | 0.01° | ~1.1 km | 高山峡谷区，最密扫描 |
| `COASTAL` | 0.015° | ~1.6 km | 沿海台风/海啸敏感区 |
| `HILL` | 0.02° | ~2.2 km | 丘陵/低山，推荐默认 |
| `PLAIN` | 默认跳过 | — | 平原低风险（`include-plain=true` 时启用，步长 0.02°） |

每个 region 生成 **9 个扫描点**（中心 + 八方位），形成 3×3 网格。3×3 步长 0.02° = 4.4 km × 4.4 km 覆盖范围。

### 25.5 配置项

```yaml
geosentry:
  ai:
    hazard-monitor-enabled: ${AI_HAZARD_MONITOR_ENABLED:true}
    hazard-monitor-interval-ms: ${AI_HAZARD_MONITOR_INTERVAL_MS:90000}     # 90s
    hazard-monitor-initial-delay-ms: ${AI_HAZARD_MONITOR_INITIAL_DELAY_MS:8000}  # 启动后 8s
    hazard-monitor-max-regions-per-tick: ${AI_HAZARD_MONITOR_MAX_REGIONS:200}    # 防超载
    hazard-monitor-include-plain: ${AI_HAZARD_MONITOR_INCLUDE_PLAIN:false}        # 默认跳过平原
    hazard-monitor-min-level: ${AI_HAZARD_MONITOR_MIN_LEVEL:town}                # town+village
    hazard-monitor-min-risk-level: ${AI_HAZARD_MONITOR_MIN_RISK_LEVEL:HIGH}      # 触发阈值
```

### 25.6 天气快照

生产应从 `WeatherService` 或中央气象台 API 实时拉取当前气象。demo 阶段使用季节性模拟 + 随机扰动：

```java
long dayOfYear = OffsetDateTime.now().getDayOfYear();
double seasonal = Math.sin((dayOfYear - 80) / 365.0 * 2 * Math.PI);
// 春夏季（4-9 月）多雨，冬季干燥
double rain24h = seasonal > 0 ? 25.0 + seasonal * 80 : seasonal * 15;
double rain7d = seasonal > 0 ? 80.0 + seasonal * 220 : seasonal * 50;
```

生产替换为 `weatherService.getCurrent(lat, lon)` 即可，**结构不变**（`Map<String, Object>` 包含 `rainfall_24h` / `rainfall_7d` / `temperature` / `humidity`）。

### 25.7 hazard_point v1.6.0+ 新字段

```sql
ALTER TABLE hazard_point ADD COLUMN source VARCHAR(32) NOT NULL DEFAULT 'AI_MONITOR';
ALTER TABLE hazard_point ADD COLUMN discovered_at TIMESTAMPTZ;
ALTER TABLE hazard_point ADD COLUMN detection_confidence DOUBLE PRECISION;
-- 索引（AI 实时灾害列表专用）
CREATE INDEX idx_hazard_source     ON hazard_point(source, discovered_at DESC);
CREATE INDEX idx_hazard_discovered ON hazard_point(discovered_at DESC) WHERE source = 'AI_MONITOR';
```

### 25.8 落库幂等（防重复创建）

`HazardService.createFromAiDetection()` 通过以下方式防重复：

1. **应用层查重**：`existsBySourceAndLongitudeAndLatitudeAndLatitudeDelta()` 1km 内同源 + 同灾害类型 + 24h 内 → 返回 null（已存在）
2. **PostgreSQL 唯一索引**：`hazard_point.geom` 上的 GiST + 业务键约束保证并发安全
3. **WebSocket 广播**：`RealtimeBroadcastService.broadcastHazard()` 仅向新创建 hazard 推送（deduped=0 不广播）

### 25.9 降级策略

| 故障点 | 表现 | 处理 |
|---|---|---|
| Python ai-service 不可用 | `aiClient.detectHazardsBatch()` 抛异常 | catch + 日志 WARN + 跳过本轮，下一轮 90s 重试 |
| region 表为空 | `loadMonitorableRegions()` 返回 [] | 日志 INFO + 返回 `{ok: 0, reason: 'no_regions'}` |
| AI 服务返回 detections=[] | 无高风险点 | 正常返回 `{ok: 0, ai_detections: 0}` |
| AI 单条检测失败 | `detection_confidence=null` | warn 单条跳过，不中断整轮 |
| DB 写入失败 | 单条 `createFromAiDetection` 抛异常 | catch + log warn + 计入 skipped |
| hazard 已存在 | `existsBy...` true | 计入 deduped + 不广播 |
| 单 tick 超 200 region | 触发 `maxRegionsPerTick` 限流 | 下一 tick 继续扫描剩余 region |

### 25.10 监控与日志

每次 tick 输出结构化日志：

```
AI 灾害监测 tick 完成: 监控 47 个区域 / 423 网格点 / 发现 12 高风险 / 入库 8 / 去重 4 / 耗时 3241ms
```

后端管理可观察：
- `GET /api/dashboard/summary` 返回的 `hazardMonitor` 特性开关
- `region` 表当前覆盖范围（`SELECT COUNT(*) FROM region WHERE region_level IN ('town','village');`）
- `hazard_point` 中 `source='AI_MONITOR'` 的总数（`SELECT COUNT(*) FROM hazard_point WHERE source='AI_MONITOR';`）

### 25.11 单元测试

当前未单独测试（涉及 Python 端点）。建议在生产前补充：

```java
@Test void gridStepByGeoType_shouldMatch() {
  // 验证 MOUNTAIN → 0.01°, COASTAL → 0.015°, HILL → 0.02°
}

@Test void loadMonitorableRegions_shouldFilterPlain() {
  // 验证 include-plain=false 时 PLAIN 被过滤
}

@Test void createFromAiDetection_shouldBeIdempotent() {
  // 验证同区域 + 同类型 + 1km 内 + 24h 内二次调用返回 null
}
```

---

## 26. 多源外部数据接入 v1.7.0 —— 真实免费 API

> **核心动机**：v1.6.0 之前 AI 综合分析的"当地降水量/当地地理情况"完全靠 GeoContextResolver
> 查表 + 关键词合成。生产环境必须用**真实**的外部数据源——Open-Meteo（气象+土壤湿度）、
> NASA Giovanni（卫星降雨/土壤湿度）、USGS FDSN（实时地震）、Open-Elevation（DEM）、
> GDACS（全球灾害警报）、OSM Overpass（承灾体暴露度）、Copernicus STAC（卫星元数据）。
> **全部公开免费 / 无需 OAuth / 无密钥**，由 `ExternalDataService` 统一接入。

### 26.1 数据源总览

| 数据源 | API 端点 | 返回字段 | 接入方式 |
|---|---|---|---|
| **Open-Meteo** | `api.open-meteo.com/v1/forecast` | 温度/湿度/降雨/气压/风速/土壤湿度 0-1cm & 3-9cm | `getMeteorology(lat, lon)` |
| **NASA Giovanni GPM** | `api.giovanni.earthdata.nasa.gov/v1/timeseries` | GPM IMERG 24h 累计降雨（mm） | `getGpmRainfall(lat, lon)` |
| **NASA Giovanni SMAP** | 同上 | SMAP L3 地表土壤湿度（m³/m³ 归一化 0-1） | `getSmapSoilMoisture(lat, lon)` |
| **USGS FDSN Event** | `earthquake.usgs.gov/fdsnws/event/1/query` | 实时地震（event_id / magnitude / depth / distance） | `getEarthquake(lat, lon, km, mag)` |
| **Open-Elevation** | `api.open-elevation.com/api/v1/lookup` | SRTM 30m DEM 海拔（m） | `getElevation(lat, lon)` |
| **GDACS** | `www.gdacs.org/gdacsapi/api/events/geteventlist` | 全球灾害事件（EQ/TC/FL + 距离过滤） | `getGdacsEvents(lat, lon, radiusKm)` |
| **OSM Overpass** | `overpass-api.de/api/interpreter` | 土地利用 + 路网密度 + 建筑物数 | `getOsmData(lat, lon, radiusM)` |
| **Copernicus Data Space STAC** | `stac.dataspace.copernicus.eu/v1/search` | Sentinel-2 影像日期 + 云量 | `getCopernicusImage(lat, lon)` |

### 26.2 核心类

```
cn.geosentry.ai.service.ExternalDataService        # 7 个 API 的统一封装
cn.geosentry.config.ExternalDataProperties         # @ConfigurationProperties(prefix="geosentry.external-data")
cn.geosentry.ai.service.AiComprehensiveAnalysisSupport  # 综合分析时按需调用（fillFactorFromExternal）
```

### 26.3 调用模式

```java
ExternalDataService svc = ...;

// 1. Open-Meteo（最常用，5s 快速响应）
MeteorologyResult meteo = svc.getMeteorology(30.61, 107.21);
// → temperature / humidity / rainfall24hSum / soilMoisture

// 2. NASA GPM（卫星降雨，更权威，20s 慢）
GpmResult gpm = svc.getGpmRainfall(30.61, 107.21);
// → precipitationMm（24h 累计）

// 3. USGS 实时地震（5s）
EarthquakeResult eq = svc.getEarthquake(30.61, 107.21, 200.0, 4.0);
// → magnitude / depthKm / distanceKm / lat / lon

// 4. Open-Elevation SRTM DEM（5s）
ElevationResult elev = svc.getElevation(30.61, 107.21);
// → elevationM

// 5. GDACS 全球灾害（10s）
List<GdacsEvent> events = svc.getGdacsEvents(30.61, 107.21, 100.0);

// 6. OSM 承灾体暴露度（30s）
OsmResult osm = svc.getOsmData(30.61, 107.21, 500.0);
// → landUseType / roadCount / buildingCount

// 7. Copernicus Sentinel-2 元数据（10s）
CopernicusImageInfo cop = svc.getCopernicusImage(30.61, 107.21);
// → date / cloudCoverPct
```

### 26.4 缓存与降级

- **缓存**：`ConcurrentHashMap<String, CacheEntry>`，默认 TTL **300s**（可通过 `geosentry.external-data.cache-ttl-seconds` 调整）
- **超时**：默认 10s（Giovanni 20s，OSM 25s）
- **失败**：返回 `null` + 日志 WARN，**不抛异常**（调用方决定兜底策略）
- **降级链路**：`ExternalDataService` → null → `GeoContextResolver` 查表 → 启发式合成

### 26.5 配置项

```yaml
geosentry:
  external-data:
    enabled: ${EXTERNAL_DATA_ENABLED:true}                                    # 总开关
    timeout-seconds: ${EXTERNAL_DATA_TIMEOUT:10}                              # HTTP 超时
    cache-ttl-seconds: ${EXTERNAL_DATA_CACHE_TTL:300}                         # 缓存存活秒数
    open-meteo-url: ${OPEN_METEO_URL:https://api.open-meteo.com/v1/forecast}
    usgs-url: ${USGS_FDSN_URL:https://earthquake.usgs.gov/fdsnws/event/1/query}
    open-elevation-url: ${OPEN_ELEVATION_URL:https://api.open-elevation.com/api/v1/lookup}
    # 启用的数据源：openmeteo, usgs, openelevation, gdacs, osm, gpm, smap, copernicus
    enabled-sources: ${EXTERNAL_DATA_SOURCES:openmeteo,usgs,openelevation,gdacs,osm,gpm,smap,copernicus}
```

### 26.6 与综合分析的集成

`AiComprehensiveAnalysisSupport.comprehensiveAnalysis(hazardCode)` 调用顺序：

```
1. GeoContextResolver.resolve(hazardCode, hazardType) → 基础地理（海拔/坡度/土壤/植被/距水/地质）
2. ExternalDataService.getMeteorology(lon, lat) → 气象 + 土壤湿度（真实）
3. ExternalDataService.getGpmRainfall(lon, lat) → 卫星降雨（真实，失败兜底 Open-Meteo）
4. ExternalDataService.getElevation(lon, lat) → 海拔校准（真实，失败用 GeoContextResolver）
5. ExternalDataService.getEarthquake(lon, lat, 200km, 4.0M) → 触发性地震因子
6. ExternalDataService.getOsmData(lon, lat, 500m) → land_use_type / buildings → 写入 hazard_point
7. ExternalDataService.getGdacsEvents(lon, lat, 100km) → 周边灾害事件
8. 聚合 14 维因子 → ai-service /api/ai/comprehensive → 返回 overall + 4 维度 + reasoning + actions
```

### 26.7 限流与配额

| 数据源 | 限流 | 应对策略 |
|---|---|---|
| Open-Meteo | 10000 次/天（非商业免费） | 缓存 5min + 仅 hazard_code 变化时刷新 |
| NASA Giovanni | 1000 次/小时/IP（公共） | 缓存 5min + 仅综合分析触发 |
| USGS FDSN | 无显式限流 | 缓存 5min |
| Open-Elevation | 1000 次/天/IP | 缓存 5min |
| GDACS | 无显式限流 | 缓存 5min |
| OSM Overpass | 10000 次/天/IP | 缓存 5min + 不在巡检热路径调用 |
| Copernicus STAC | 50 次/天/authenticated, 公开查询无严格限流 | 缓存 5min |

> 大规模全国版实时监测会高频调用——单 tick 200 region × 9 点 = 1800 个检测，**只调 1 次 Python AI 批量端点**（`detect-batch`），而非 1800 次单独 HTTP。所有外部数据**只在综合分析时按需调用**（每 hazard 5 分钟一次），不会因实时监测而暴涨。

### 26.8 故障排查

```bash
# 手动测试 Open-Meteo 连通性
curl "https://api.open-meteo.com/v1/forecast?latitude=30.61&longitude=107.21&current=temperature_2m"

# 手动测试 USGS
curl "https://earthquake.usgs.gov/fdsnws/event/1/query?format=text&latitude=30.61&longitude=107.21&maxradiuskm=200&minmagnitude=4.0"

# 后端日志中搜索 ExternalData WARN
docker compose logs backend | grep "ExternalData"

# 关闭所有外部数据（强制走 GeoContextResolver 兜底）
EXTERNAL_DATA_ENABLED=false mvn spring-boot:run
```

---

## 27. 审计日志归档 v1.6.0 —— PostgreSQL 函数化归档

> **核心动机**：`operation_log` 表按 AOP 自动记录所有 Controller 写方法（详见 §10 + §14 第 31 条），
> 长期积累会膨胀到几亿行，拖慢 `operation_log` 主表的查询性能。生产环境要求**主表只保留近 N 天**
> （默认 90 天），过期数据迁移到 `operation_log_archive` 归档表——结构与主表完全一致但**无索引**，
> 减少归档写入开销，需要历史审计时通过 `WHERE created_at BETWEEN ...` 查询归档表。

### 27.1 数据流

```
┌────────────────────────────────────────────────────────────────────┐
│  OperationLogAspect (AOP) ─────────────────────────────────────┐    │
│  自动记录所有 *Controller 写方法                                  │    │
└────────────────────────────────────────────────────────────────┘    │
                                                                    ▼
                                                         ┌──────────────────┐
                                                         │  operation_log   │
                                                         │  主表（带索引）   │
                                                         └────────┬─────────┘
                                                                  │
                                                  每日凌晨 3:00   │
                                                  @Scheduled      │
                                                  ┌───────────────▼──────────────┐
                                                  │  OperationLogArchiver        │
                                                  │  JdbcTemplate 调用 PG 函数   │
                                                  │  archive_operation_logs(90)  │
                                                  └───────────────┬──────────────┘
                                                                  │
                                                                  ▼
                                              ┌────────────────────────────────┐
                                              │ archive_operation_logs() 函数  │
                                              │   1. INSERT INTO operation_log_│
                                              │      archive SELECT * FROM    │
                                              │      operation_log WHERE ...  │
                                              │   2. DELETE FROM operation_log │
                                              │      WHERE ...                │
                                              └────────────────────────────────┘
```

### 27.2 核心文件

| 层 | 文件 | 职责 |
|---|---|---|
| Java | `cn.geosentry.audit.scheduler.OperationLogArchiver.java` | `@Scheduled(cron="0 0 3 * * *")` 每日凌晨 3:00 调用 PG 函数 |
| Java | `cn.geosentry.audit.entity.OperationLog.java` | JPA 实体（主表） |
| Java | `cn.geosentry.audit.repository.OperationLogRepository.java` | JPA 仓储 |
| Java | `cn.geosentry.aop.OperationLogAspect.java` | AOP 切面，写入 `operation_log`（详见 §14 第 31 条） |
| SQL | `infra/postgres/init/003_archive_operation_log.sql` | 创建 `operation_log_archive` 表 + `archive_operation_logs(retention_days)` 函数 |
| 配置 | `geosentry.audit.archive-cron` + `geosentry.audit.log-retention-days` | 调度时间 + 保留天数 |

### 27.3 SQL 函数定义（`infra/postgres/init/003_archive_operation_log.sql`）

```sql
-- 1. 归档表（结构与主表一致，无索引）
CREATE TABLE IF NOT EXISTS operation_log_archive (
  id BIGSERIAL PRIMARY KEY,
  operator VARCHAR(64) NOT NULL,
  module_name VARCHAR(64) NOT NULL,
  action_name VARCHAR(128) NOT NULL,
  request_method VARCHAR(16),
  request_path VARCHAR(255),
  request_body TEXT,
  result_status VARCHAR(32),
  cost_ms BIGINT,
  ip_address VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL
);

-- 2. 归档函数：迁移 + 删除
CREATE OR REPLACE FUNCTION archive_operation_logs(retention_days INTEGER)
RETURNS TABLE(migrated INTEGER, deleted INTEGER) AS $$
DECLARE
  cutoff TIMESTAMPTZ := NOW() - (retention_days || ' days')::INTERVAL;
  mig_count INTEGER;
  del_count INTEGER;
BEGIN
  INSERT INTO operation_log_archive
  SELECT * FROM operation_log WHERE created_at < cutoff;
  GET DIAGNOSTICS mig_count = ROW_COUNT;

  DELETE FROM operation_log WHERE created_at < cutoff;
  GET DIAGNOSTICS del_count = ROW_COUNT;

  RETURN QUERY SELECT mig_count, del_count;
END;
$$ LANGUAGE plpgsql;
```

### 27.4 Java 调度器

```java
@Component
public class OperationLogArchiver {
  @Value("${geosentry.audit.log-retention-days:90}") int retentionDays;

  @Scheduled(cron = "${geosentry.audit.archive-cron:0 0 3 * * *}")
  void archive() {
    var rows = jdbc.queryForRowSet("SELECT * FROM archive_operation_logs(?)", retentionDays);
    if (rows.next()) {
      log.info("operation_log 归档完成：迁移 {} 条到 operation_log_archive，删除 {} 条",
               rows.getInt(1), rows.getInt(2));
    }
  }
}
```

**关键特性**：
- 失败时 `catch` + WARN 日志 + 跳过，**不影响主流程**
- 归档函数不存在时（如首次部署未执行 init 脚本），调度器 WARN 日志提示
- 函数返回 `(migrated, deleted)` 两列：分别记录迁移条数与删除条数

### 27.5 配置项

```yaml
geosentry:
  audit:
    archive-cron: 0 0 3 * * *           # 默认每日凌晨 3:00
    log-retention-days: 90              # 主表保留 90 天
```

环境变量覆盖：

```bash
AUDIT_ARCHIVE_CRON="0 0 2 * * *"        # 凌晨 2:00 归档
AUDIT_LOG_RETENTION_DAYS=180            # 保留半年
```

### 27.6 部署检查

```bash
# 1. 验证归档函数存在
docker compose exec postgres psql -U geosentry -d geosentry \
  -c "SELECT proname FROM pg_proc WHERE proname='archive_operation_logs';"
# 期望：proname = archive_operation_logs

# 2. 验证归档表存在
docker compose exec postgres psql -U geosentry -d geosentry \
  -c "\d operation_log_archive"

# 3. 手动触发归档（90 天）
docker compose exec postgres psql -U geosentry -d geosentry \
  -c "SELECT * FROM archive_operation_logs(90);"

# 4. 查看主表当前数据量
docker compose exec postgres psql -U geosentry -d geosentry \
  -c "SELECT COUNT(*), MIN(created_at), MAX(created_at) FROM operation_log;"

# 5. 查看归档表数据量
docker compose exec postgres psql -U geosentry -d geosentry \
  -c "SELECT COUNT(*), MIN(created_at), MAX(created_at) FROM operation_log_archive;"
```

### 27.7 历史审计查询

```sql
-- 查询某操作员近 1 年的所有操作（合并主表 + 归档表）
SELECT * FROM operation_log
WHERE operator = 'admin' AND created_at >= NOW() - INTERVAL '1 year'
UNION ALL
SELECT * FROM operation_log_archive
WHERE operator = 'admin' AND created_at >= NOW() - INTERVAL '1 year'
ORDER BY created_at DESC;

-- 按模块统计归档表操作量
SELECT module_name, COUNT(*) FROM operation_log_archive
WHERE created_at >= NOW() - INTERVAL '1 year'
GROUP BY module_name ORDER BY 2 DESC;
```

### 27.8 替代方案（v2 规划）

| 方案 | 优点 | 缺点 | 当前状态 |
|---|---|---|---|
| PostgreSQL 函数归档（当前） | 简单可靠，原生 SQL 性能好 | 仍占用 PG 存储 | ✅ v1.6.0 已落地 |
| 定时归档到 S3 / MinIO Parquet | 长期存储成本低，可对接 ClickHouse/BigQuery | 增加 ETL 复杂度 | 📋 规划中 |
| 替换为 ELK / Loki 等专用日志系统 | 全文检索 + 可视化 | 引入新组件 | 📋 规划中 |

---

## 28. 项目结构与清理规范（v1.7.0 Step 6/9）

> 自 v1.7.0 起，前端按域拆分（`views/ai|hazard|...`）、服务按 `_architecture/core|domain` 分层、Pinia 接管业务域 store、单页 80KB 的 `dashboardApi.js` 收敛到分层聚合器；本节定义这套结构如何长期维护、什么文件该留在仓库、什么该被 `.gitignore` 拒绝。

### 28.1 前端目录分层（v1.7.0 目标结构）

```
src/
├── views/
│   ├── ai/         data/       device/      hazard/     monitor/
│   ├── screen/     settings/   system/      warning/    mobile/
│   ├── OverviewPage.vue         # 跨域总览页
│   ├── WelcomePage.vue          # 登录
│   ├── ForbiddenPage.vue        # 403
│   └── ServerErrorPage.vue      # 500
├── components/                  # 通用组件（含 event/ profile/ rule/ 子目录）
├── composables/                 # 复用逻辑（useOverviewSearch 等）
├── data/dashboardData.js        # 菜单 / topbar eyebrow 唯一来源
├── layouts/DashboardLayout.vue  # 路由布局
├── router/index.js              # 57 条业务路由 + 权限 meta
├── services/
│   ├── _architecture/
│   │   ├── core/                # httpClient / realtime / map / geocode / health / sentinel
│   │   └── domain/              # 各业务域薄封装（hazard/warning/ai/...）
│   ├── dashboardApi.js          # 唯一对 View 暴露的入口
│   └── mock/                    # 离线 mock（按域可独立切换）
├── stores/                      # auth / realtime / userPreference（Pinia）
└── utils/                       # 坐标转换 / 格式化
```

**红线**：

- ❌ 不在 `views/` 直接 `import axios` —— 走 `services/_architecture/domain/*`
- ❌ 不在 View 里写跨页 state —— 提到 `stores/`（Pinia）或 `composables/`
- ❌ 不在 `src/services/` 顶层新增 `xxxService.js` —— 全部下沉到 `_architecture/`
- ❌ 不在 `_architecture/core/` 写业务字段 —— core 只做横切（http / ws / map）

### 28.2 后端目录分层（v1.7.0 目标结构）

```
backend/src/main/java/cn/geosentry/
├── auth/                # 鉴权 + JWT + 当前用户 Profile
├── hazard/              # 灾害点 + 状态机
├── warning/             # 预警事件 + 流转
├── event/               # 统一事件中心（7×12 状态机）
├── emergency/           # 应急联动
├── ai/                  # AI 推理客户端 + 调度器
├── notification/        # 通知渠道
├── ruleengine/          # 规则引擎
├── device/              # 监测设备 + OTA
├── realtime/            # WebSocket + 实时推送
├── dashboard/           # 综合态势聚合
├── personnel/           # 人员管理
├── region/              # 行政区划（AI 实时监测使用）
├── audit/               # 审计日志（含归档）
├── attachment/          # MinIO 附件
├── weather/             # 国家气象代理
├── aop/                 # OperationLogAspect 切面
├── common/              # 全局异常 / 公共基类
└── config/              # 配置类（JWT / Security / WebSocket）
```

**红线**：

- ❌ Controller 内禁止 `try-catch` —— 一律交给 `GlobalExceptionHandler`
- ❌ Service 禁止直接返回 `Map<String, Object>` —— 必须用 DTO
- ❌ 跨域枚举常量统一放在对应 module 下的 `constants/` 包（不允许散落）

### 28.3 `scripts/` 维护脚本清单（v1.7.0 收敛到 8 个）

| 文件 | 用途 | 调用时机 |
|---|---|---|
| `audit_db.py` | 数据库 schema 审计（字段 / 索引 / 关系 / JSONB 字面量校验） | 修改 `schema.sql` / `data.sql` 后必跑 |
| `backfill_unified_event_hazards.py` | 统一事件中心历史数据回填 | 升级到 v1.7.0 一次性 |
| `smoke_test_schema.py` | 启动前 schema 健康检查（不依赖 docker） | CI / 本地预检 |
| `gen_data_sql.py` | 重新生成 `data.sql` 种子数据 | 修改 entity 后 |
| `diagnose-schema-drift.ps1` | DB 列与 `schema.sql` 对齐检查 | 启动报 `column does not exist` 时 |
| `fix-db-volume.ps1` | 重建 Postgres 数据卷（最后手段） | 漂移无法修复时 |
| `check_jsonb_sql.py` | `data.sql` 内 JSONB 字面量预校验 | CI 流水线 |
| `oneclick-fix.mjs` | `==` / `!=` → `===` / `!==` 全量升级 | 由 `npm run fix` 调用 |

**红线**：

- ❌ 新增一次性迁移脚本请在 PR 描述里标注 "一次性，合并后删除"，否则将永久驻留
- ❌ 不要把脚本输出（`*.txt` / `*.log`）提交进仓库（已在 `.gitignore`）
- ❌ 不要把 docker compose / psql 调试命令留在脚本里 —— 改用文档

### 28.4 启动脚本（v1.7.0 统一入口）

```
.\start-dev.ps1 -Infra -Up      # 启动基础设施（Postgres / Redis / MinIO / EMQX / TDengine）
.\start-dev.ps1 -Infra -Down    # 停止基础设施
.\start-dev.ps1 -Infra -Reset   # ⚠ 销毁并重建基础设施（数据卷）
.\start-dev.ps1 -Backend        # 仅启动后端
.\start-dev.ps1 -Frontend       # 仅启动前端（默认）
.\start-dev.ps1 -Ai             # 仅启动 AI 微服务
.\start-dev.ps1 -Status         # 健康度速览（v1.7.0 新增，跨平台）
.\start-dev.ps1 -All            # 提示全栈模式所需 3 个终端命令

./start-dev.sh infra up|down|reset
./start-dev.sh {backend|frontend|ai|status|all}
```

> 旧的 `run-backend.ps1` / `run-frontend.ps1` 保留为内部子命令入口；当 `start-dev.ps1` 抛错需要手工排查时仍可单独使用。

### 28.5 已清理文件清单（v1.7.0 Step 6 + Step 9）

| 文件 / 目录 | 类型 | 清理原因 |
|---|---|---|
| `scripts/audit_output.txt` 等 3 个 | 调试输出 | Step 6 删除 |
| `scripts/test-exit.ps1` / `test-exit2.ps1` | 调试脚本 | Step 6 删除 |
| `scripts/count_statements.ps1` | 一次性 helper | Step 6 删除 |
| 17 个老版本兼容垫片（如 `src/services/hazardMapService.js`） | 旧版本兼容 | Step 6 合并到 `_architecture/domain/` |
| `scripts/rewrite-operation-log.ps1` | 一次性迁移 | Step 9 删除（data.sql 已固化） |
| `scripts/check-vue.cjs` | 一次性 Vue 检查 | Step 9 删除（已被 ESLint + `npm run lint` 覆盖） |
| `ai-service/models/history/xgboost_fusion-20260907-062517.json` | 模型历史快照 | Step 9 删除（与现役模型重复，且 history 目录已加入 gitignore） |
| 本地 `logs/` / `backend/logs/` | 开发期日志 | Step 9 清理（已在 gitignore） |
| `backend/.github/modernize/` / `.github/` | AI 工具残留 | Step 9 清理（已在 gitignore） |

### 28.6 `.gitignore` 红线清单

| 模式 | 防止误提交 |
|---|---|
| `node_modules/` `dist/` `.vite/` | 前端构建产物 |
| `backend/target/` | 后端构建产物 |
| `ai-service/**/__pycache__/` | Python 缓存 |
| `ai-service/models/*.pt` `*.onnx` `*.pkl` `*.joblib` | 模型权重（体积大） |
| `ai-service/models/history/` | 模型历史快照 |
| `ai-service/.venv/` | Python 虚拟环境 |
| `logs/` `backend/logs/` `*.log` | 开发期日志 |
| `.github/` `backend/.github/` | AI 工具运行时产物（modernize hook） |
| `.env` `.env.local` `*.secret` `*.key` `*.pem` | 密钥 |
| `postgres_data/` `redis_data/` `minio_data/` `emqx_data/` `tdengine_data/` | Docker 数据卷 |
| `m2_cache/` | Maven 仓库镜像 |

**红线**：

- ❌ 新增大型中间产物（模型 / checkpoint / 数据卷）前先确认 `.gitignore` 是否覆盖
- ❌ 提交前跑 `git status --ignored`，确认无新增 ignored 文件"漏出来"
- ❌ 任何包含密码 / token / 私钥的文件请走 `.env.local`（已在 gitignore）

---

### 文档版本与维护

- **当前文档版本**：v1.7.0（与代码库同步）
- **代码仓库版本**：`package.json` 中 `geohazard-inspection-dashboard@1.0.0`
- **维护说明**：本文档与代码仓库同源迭代。当新增功能模块、修改配置项、调整 API 或修改数据库表结构时，**必须同步更新对应章节**，否则新人 onboarding 与生产排障会偏离实际实现。
- **变更节奏**：
  - **§1–§15**（核心开发文档）：版本稳定，每个 PR 都可能微调
  - **§16–§27**（特性专题）：按版本演进，每个版本新增一节（如 §28 / §29 …）
  - 数据库 schema 变更必须在 §8.1 增删表条目，并在 §14 同步注意事项
  - 新增 API 端点必须在 §7 对应业务域小节添加
  - 新增配置项必须在 §11.1（前端）或 §11.2（后端）添加

### 反馈与协作

- **仓库地址**：`D:\earthquake`（当前开发仓库根目录）
- **配套脚本**：
  - `run-backend.ps1` — Windows 一键启动后端（自动停 Docker backend + 端口检查 + 设置环境变量）
  - `run-frontend.ps1` — Windows 一键启动前端（自动停 Docker frontend + 端口检查）
  - `scripts/gen_data_sql.py` — 重新生成 `data.sql` 种子数据
- **Swagger UI**：`http://localhost:8080/swagger-ui.html`（SpringDoc OpenAPI 2.8.0）
- **ai-service 文档**：`http://localhost:8000/api/ai/docs`（FastAPI 自动文档）

---

### _本文档基于仓库当前代码整理，随项目迭代请同步更新。_
