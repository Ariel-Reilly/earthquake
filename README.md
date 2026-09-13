# 地脉智巡 · 地质灾害智能巡检与预警管理平台

> Geohazard Inspection Dashboard (GeoSentry)

基于 Spring Boot 3 + Vue 3 的一体化地质灾害监测、巡检、预警与应急响应平台。

---

## 快速启动

```bash
# 1. 启动全部服务（PostgreSQL/Redis/MinIO/EMQX/TDengine/后端/前端/AI推理）
docker compose up -d

# 2. 或分模块开发（推荐）
# 基础服务
docker compose up -d postgres redis minio emqx tdengine

# 前端（项目根目录）
npm install
npm run dev

# 后端（backend 目录）
#   需先设置环境变量（见下方"宿主机直跑后端"）
mvn spring-boot:run
```

---

## 端口约定

以下为 **docker-compose 开发模式**下的宿主机端口映射：

| 服务 | 宿主机端口 | 容器内端口 | 说明 |
|------|-----------|-----------|------|
| 前端开发服务器 | `5173` | — | Vite dev server |
| 前端（Docker） | `5173` | `80` | Nginx 反代 API/WS |
| 后端（Docker） | `8080` | `8080` | Spring Boot |
| 后端（Prod Docker） | `8081` | `8080` | 生产模式映射 |
| 后端（Maven 直跑） | `8080` | — | `mvn spring-boot:run` |
| PostgreSQL | `15432` | `5432` | PostGIS 15 |
| Redis | `16379` | `6379` | Redis 7 + 密码 |
| MinIO API | `19000` | `9000` | 对象存储 |
| MinIO Console | `19001` | `9001` | Web 管理台 |
| EMQX MQTT | `11883` | `1883` | MQTT Broker 5.9 |
| EMQX Dashboard | `28083` | `18083` | Broker 管理台 |
| TDengine TCP | `16030` | `6030` | 时序数据库 |
| TDengine HTTP | `16041` | `6041` | JDBC REST 接口 |
| AI 推理服务 | `8000` | `8000` | FastAPI（可选） |

---

## 后端 API 概览

前缀 `/api`，除认证/健康检查外需 `Authorization: Bearer <token>`。

| 模块 | 核心端点 |
|------|---------|
| 认证 | `POST /auth/login`、`POST /auth/logout`、`POST /auth/change-password`、`GET /auth/ws-ticket` |
| 系统 | `GET /system/health`、Swagger `/swagger-ui.html` |
| 仪表盘 | `GET /dashboard/overview`、`GET /dashboard/charts`、`GET /dashboard/analytics`、`GET /dashboard/summary` |
| 灾害点 | CRUD + `/viewport` + `/geojson` + `/export` |
| 巡检任务 | CRUD + `PATCH /{id}/status` + `/export` |
| 巡检上报 | `POST /inspection/gps-check`、`POST /inspection/submit` |
| 监测设备 | CRUD + `/viewport` + `POST /{id}/data`（鉴权 X-Device-Key） + `/export` |
| 预警事件 | CRUD + `/preview` + `/viewport` + 六态流转（confirm/dispatch/close/dismiss/resolve） |
| 应急处置 | CRUD（橙/红预警派发时自动生成） |
| 附件 | `POST /attachments`（MinIO 真实写入）+ presigned URL 刷新 |
| AI 推理 | `POST /ai/detect`、`/forecast`、`/anomaly`、`/fusion-risk`（可降级） |
| 值守人员 | `GET /personnel/positions`、`POST /personnel/positions/report` |
| 实时推送 | WebSocket `/ws`（一次性 ticket 鉴权） |

---

## 环境配置

复制 `.env.example` 为 `.env`，根据实际环境修改。关键配置项：

```bash
# 前端
VITE_API_BASE_URL=http://localhost:8080/api   # 开发模式后端地址
VITE_USE_BACKEND=true                          # false=纯mock，演示用
VITE_WS_URL=                                    # 留空自动推导

# 后端（Maven 直跑时必须设置）
POSTGRES_PORT=15432          # Docker 映射端口
REDIS_PORT=16379
JWT_SECRET=<32+字符强随机密钥>
DEV_USERS_ENABLED=true        # 开发环境可用内存账号 fallback
```

---

## 宿主机直跑后端（Windows PowerShell）

Docker 映射了非标准端口，需要显式设置环境变量：

```powershell
$env:POSTGRES_PORT='15432'
$env:REDIS_PORT='16379'
$env:MINIO_ENDPOINT='http://localhost:19000'
$env:TDENGINE_URL='jdbc:TAOS-RS://localhost:16041/geohazard'
$env:JWT_SECRET='your-strong-secret-at-least-32-chars-long-x'
$env:DEV_USERS_ENABLED='true'

cd backend
mvn spring-boot:run
```

数据库初始化：首次启动自动执行 `schema.sql` + `data.sql`（幂等）。

---

## 技术栈

| 层级 | 技术 |
|------|------|
| 前端 | Vue 3.5 + Vite 6 + Vue Router 4 + OpenLayers 10 + Cesium 1.144 + Lucide Icons |
| 后端 | Spring Boot 3.4 + Java 17 + Spring Data JPA + Hibernate Spatial (PostGIS) + Spring Security (JWT) |
| AI 服务 | Python FastAPI + YOLOv8 + LSTM + Isolation Forest + XGBoost（可选，可降级） |
| 基础设施 | PostgreSQL 15 + PostGIS + Redis 7 + MinIO + EMQX 5 + TDengine |

---

## 多源数据融合体系

AI 智能巡检监测自然灾害需要多源数据融合，本系统整合以下六类核心数据：

### 一、核心遥感影像数据（用于识别地表灾害特征）

| 数据名称 | 传感器/卫星 | 空间分辨率 | 时间分辨率 | 用途 | 推荐接入方式 |
|---|---|---|---|---|---|
| Sentinel-1 SAR | C波段雷达 | 10 m（GRD） | 6天（双星） | 洪水、滑坡、地表形变（InSAR） | Copernicus Data Space / GEE |
| Sentinel-2 多光谱 | MSI | 10/20/60 m | 5天（双星） | 植被指数、水体提取、火灾迹地 | Copernicus Data Space / Sentinel Hub |
| Landsat 8/9 | OLI/TIRS | 15/30/100 m | 16天 | 长期土地覆盖变化、历史对比 | USGS EarthExplorer / GEE |
| MODIS / VIIRS | 中分辨率 | 250/500/1000 m | 每日多次 | 大范围火点、气溶胶、洪水快速初判 | NASA GIBS / FIRMS API / GEE |
| 高分辨率商业（可选） | Maxar/Planet | <1 m | 按需 | 建筑物损毁评估 | Planet API |

### 二、气象与水文数据（用于触发条件分析和预警）

| 数据名称 | 内容 | 时空分辨率 | 用途 | 推荐接入来源 |
|---|---|---|---|---|
| ERA5 再分析 | 降雨、温度、风速、湿度、气压等 | 0.25° 逐小时 | 降雨阈值分析、干旱指数 | ECMWF CDS API |
| GPM IMERG | 全球卫星降雨 | 0.1° 每30分钟 | 实时降雨监测、滑坡/洪水触发 | NASA GES DISC |
| SMAP 土壤湿度 | 雷达/辐射计土壤水分 | 9 km / 3 km | 干旱监测、滑坡前期饱和度 | NSIDC / EarthData |
| 河流水位（可选） | 水文站实测 | 站点级 | 洪水演进验证 | GRDC |

### 三、地形与地质数据（用于易发性评估和特征提取）

| 数据名称 | 内容 | 分辨率 | 用途 | 推荐接入来源 |
|---|---|---|---|---|
| SRTM DEM | 全球数字高程模型 | 30 m | 坡度、坡向、汇流分析 | NASA EarthData / GEE |
| Copernicus DEM | 全球DEM | 30 m | 更高精度地形分析 | Copernicus Data Space |
| 地质图/断层数据 | 岩性、构造 | 矢量 | 地震、滑坡易发性因子 | USGS / 地质调查局 |
| 土壤类型 | 土壤物理属性 | 矢量/栅格 | 渗透性、滑坡敏感性 | FAO HWSD / SoilGrids |

### 四、历史灾害与承灾体数据（用于模型训练和风险评估）

| 数据名称 | 内容 | 用途 | 推荐接入来源 |
|---|---|---|---|
| EM-DAT | 全球灾害事件库 | 模型训练标签、风险区划 | EM-DAT 官网 |
| GDACS | 全球灾害警报和影响评估 | 实时灾害事件触发 | GDACS RSS/API |
| WorldPop / GHS | 人口密度分布 | 风险评估、影响人口估算 | WorldPop API |
| OpenStreetMap | 建筑物、道路、土地利用 | 承灾体暴露度评估 | OSM API |

### 五、地面传感器与实时观测数据（用于快速预警和验证）

| 数据名称 | 内容 | 用途 | 推荐接入方式 |
|---|---|---|---|
| USGS 地震目录 | 实时地震事件 | 地震触发报警 | USGS FDSN API |
| 现场 IoT 传感器（可选） | 雨量计、位移计、倾斜仪、水位计 | 高时间分辨率前兆监测 | 第三方 IoT 平台 |

### 六、辅助数据（可选，用于增强分析或可视化）

| 数据名称 | 内容 | 用途 | 推荐接入来源 |
|---|---|---|---|
| NASA Black Marble | 夜间灯光 | 断电范围评估 | NASA LAADS / GEE |
| 社交媒体 API | 微博/Twitter/X 帖文 | 灾情现场信息 | 微博/Twitter API |
| 行政区划矢量 | 国/省/市边界 | 结果分区统计 | Natural Earth / GADM |

详细数据接入说明见 [docs/DEVELOPER.md](docs/DEVELOPER.md) §24。

---

## 文档

详细开发者文档见 [docs/DEVELOPER.md](docs/DEVELOPER.md)，覆盖架构、API、数据库、权限、部署、二次开发。

---

## 项目结构

```
earthquake/
├── src/                    # 前端 Vue 3 源码
├── backend/                # Spring Boot 后端
├── ai-service/             # FastAPI AI 推理服务（可选）
├── infra/                  # 基础设施初始化脚本
│   ├── emqx/               # EMQX broker 认证初始化
│   ├── nginx/              # 生产 Nginx 反代配置
│   ├── postgres/           # PostGIS 扩展 + 归档函数
│   └── tdengine/           # TDengine 初始化
├── Dockerfile.backend      # 后端多阶段构建
├── Dockerfile.frontend     # 前端多阶段构建（Nginx）
├── docker-compose.yml      # 开发模式编排
├── docker-compose.prod.yml # 生产模式覆盖
├── .env.example            # 环境变量模板
├── package.json            # 前端依赖
└── README.md               # 本文档
```

---

## 默认账号（开发模式）

| 用户名 | 密码 | 角色 |
|--------|------|------|
| admin | 123456 | 管理员 |
| attendant | 123456 | 值班员 |
| maintenance | 123456 | 运维员 |

> 生产环境必须修改种子密码或跳过 data.sql（见 `docs/DEVELOPER.md` §14 第 43 条）。

---

_本项目为学生创业项目，技术栈与功能按实际迭代持续更新。_
