# 架构总览 · ARCHITECTURE

> 地脉智巡（DisasterGuard）系统架构与模块依赖关系
> 最后更新：2026-09-16

---

## 1. 系统概览

```
┌────────────────────────────────────────────────────────────────┐
│                       终端 / 用户                              │
│  ┌──────────┐  ┌──────────┐  ┌─────────────┐  ┌────────────┐ │
│  │ Web 仪表盘 │ │ 移动指挥 │  │  指挥大屏    │  │ 移动设备    │ │
│  │ /dashboard│ │ /mobile  │  │  /screen    │  │ MQTT/SDK   │ │
│  └─────┬─────┘  └────┬─────┘  └──────┬──────┘  └─────┬──────┘ │
└────────┼─────────────┼───────────────┼────────────────┼────────┘
         │ HTTP/WS     │ HTTPS         │ WebSocket      │ MQTT
         ▼             ▼               ▼                ▼
┌────────────────────────────────────────────────────────────────┐
│                 Spring Boot 3 后端 (8080)                       │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐      │
│  │  Controller │ Service │ │   DTO    │  │ Entity   │       │
│  └─────┬────┘  └────┬─────┘  └─────┬────┘  └─────┬─────┘       │
│        │            │              │              │             │
│        ▼            ▼              ▼              ▼             │
│  ┌──────────────────────────────────────────────────────┐      │
│  │          JPA Repositories (PostGIS / Hibernate)       │      │
│  └──────────────────────────┬───────────────────────────┘      │
└─────────────────────────────┼─────────────────────────────────┘
                              │
       ┌──────────────────────┼──────────────────────┐
       ▼                      ▼                      ▼
┌─────────────┐       ┌─────────────┐         ┌─────────────┐
│ PostgreSQL  │       │    Redis    │         │    MinIO    │
│ + PostGIS   │       │   缓存      │         │  对象存储    │
│  (15432)    │       │  (16379)    │         │  (19000)    │
└─────────────┘       └─────────────┘         └─────────────┘

┌────────────────────────────────────────────────────────────────┐
│             AI 微服务 FastAPI (8001) · 可选降级                  │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐       │
│  │ 检测      │  │ 预测      │  │ 融合评分  │  │ 异常检测  │       │
│  │ YOLOv8   │  │  LSTM    │  │ XGBoost  │  │ iForest  │       │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘       │
└────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────┐
│                    基础设施                                    │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐       │
│  │   EMQX   │  │ TDengine │  │   ...    │  │          │       │
│  │   MQTT   │  │ 时序     │  │          │  │          │       │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘       │
└────────────────────────────────────────────────────────────────┘
```

---

## 2. 核心数据流

### 2.1 灾害事件闭环（统一事件中心）

```
[数据源]                                [AI 发现]
   │                                       │
   ▼                                       ▼
多源数据 ─→ AI 巡检任务 ─→ AI 推理 ─→ 疑似事件 (state=AI_DETECTED)
                                          │
                                          ▼
                              [人工确认工作台]  (state=PENDING_CONFIRM)
                                          │
                            ┌─────────────┼─────────────┐
                            ▼                           ▼
                        确认 (CONFIRMED)             驳回 (REJECTED)
                            │                           │
                            ▼                           ▼
              [创建预警] (state=WARNING_PUBLISHED)   归档
                            │
                            ▼
                  [应急事件] (state=EMERGENCY_OPEN)
                            │
                            ▼
                  [处置中] (state=EMERGENCY_HANDLING)
                            │
                            ▼
                  [已闭环] (state=CLOSED) → 归档
```

详见 [docs/UNIFIED_EVENT_STATEMACHINE.md](UNIFIED_EVENT_STATEMACHINE.md)。

### 2.2 实时数据推送

```
设备 IoT ──MQTT──→ EMQX (1883) ──→ 后端 MqttListener
                                          │
                                          ▼
                              RealtimeBroadcastService
                                          │
                            ┌─────────────┼─────────────┐
                            ▼             ▼             ▼
                       WebSocket       SSE         持久化
                       /ws (ticket)    /sse        Postgres
                            │
                            ▼
                       前端 useRealtimeStore (Pinia)
```

### 2.3 权限验证流

```
HTTP 请求
   │
   ▼
JwtAuthFilter (解析 JWT → SecurityContext)
   │
   ▼
PermissionEvaluator (检查路径 + 操作权限)
   │
   ├─ 无 token   → 401
   ├─ token 过期 → 401
   ├─ 无路径权限 → 403
   └─ 无操作权限 → 403
```

---

## 3. 模块依赖图

### 3.1 前端按域分组

```
src/views/
├── ai/                          AI 业务域
│   ├── AiTaskManagePage        任务管理
│   ├── AiResultsPage           推理结果
│   ├── AiInsightPage           综合研判
│   ├── ModelManagePage         模型管理
│   ├── DisasterForecastPage    灾害预测
│   ├── DamageAssessmentPage    灾损评估
│   └── SocialMediaPage         舆情监测
├── warning/                     预警业务域
│   ├── WarningsPage            列表
│   ├── WarningDetailPage       详情
│   ├── WarningCreatePage       创建
│   ├── WarningResolvePage      解除
│   ├── WarningDeliveryPage     下发
│   ├── EmergencyPage           应急
│   ├── ResourceDispatchPage    资源调度
│   └── RoutePlanningPage       路径规划
├── hazard/                      灾害业务域
│   ├── HazardsListPage
│   ├── HazardDetailPage
│   ├── ConfirmationWorkbenchPage
│   ├── ConfirmationSignPage
│   ├── FieldInspectionPage
│   └── TaskDetailPage
├── monitor/                     监测域
│   ├── RealTimeMapPage
│   ├── SatelliteComparePage
│   ├── WeatherPage
│   ├── ZoneDashboardPage
│   ├── HistoryHazardsPage
│   └── DeviceMapPage
├── data/                        数据域
│   ├── DataSourcesPage
│   ├── DataSyncPage
│   ├── DataCatalogPage
│   └── AnalyticsPage
├── device/                      设备域
│   ├── DevicesPage
│   ├── DeviceDetailPage
│   └── OtaManagePage
├── system/                      系统域
│   ├── UserManagementPage
│   ├── AuditLogPage
│   ├── SystemMonitorPage
│   ├── MessageCenterPage
│   ├── TodoCenterPage
│   ├── ApprovalCenterPage
│   ├── EventCenterPage
│   ├── NoticeTemplatePage
│   ├── NotificationChannelsPage
│   ├── PlanManagePage
│   ├── ReportCenterPage
│   ├── BackupRecoveryPage
│   ├── HelpCenterPage
│   └── PersonnelManagePage
├── settings/                    设置域
│   ├── SettingsPage
│   ├── ProfilePage
│   └── AccountSecurityPage
├── screen/                      大屏（公开）
│   └── CommandScreenPage
├── mobile/                      移动端（公开）
│   └── MobileCommandPage
├── auth/                        公开
│   └── WelcomePage
└── error/                       公开
    ├── ForbiddenPage
    └── ServerErrorPage
```

### 3.2 后端模块（cn.disasterguard.*）

| 包 | 主要职责 | 关键 Controller |
|---|---|---|
| `auth` | 登录、JWT、用户资料、偏好 | `AuthController` |
| `hazard` | 灾害事件 CRUD、人工确认 | `HazardController`、`HazardDetailController` |
| `warning` | 预警全生命周期、6 态流转 | `WarningController` |
| `emergency` | 应急事件、资源调度 | `EmergencyController` |
| `event` | 统一事件中心（v1.7+） | `EventStateController` |
| `device` | 设备注册、MQTT 接入、OTA | `DeviceController`、`OtaController` |
| `ai` | AI 服务调用、漂移监控 | `AiController` |
| `notification` | 通知路由、模板、渠道 | `NotificationController` |
| `ruleengine` | 规则引擎 CRUD | `RuleEngineController` |
| `inspection` | 巡检任务、上报 | `InspectionController` |
| `personnel` | 值守人员、位置上报 | `PersonnelController` |
| `attachment` | MinIO 附件管理 | `AttachmentController` |
| `dashboard` | 综合数据看板 | `DashboardController` |
| `system` | 健康检查、审计、字典 | `SystemController` |
| `common` | 基类、异常、导出、权限 | — |

---

## 4. 数据库分层

```
PostgreSQL 15 + PostGIS 15
├── 核心业务表
│   ├── sys_user / user_profile / user_preference
│   ├── hazard_event / hazard_history
│   ├── warning_event / warning_status_log
│   ├── emergency_event / emergency_action
│   ├── unified_event（v1.7 统一事件）
│   ├── ai_task / ai_result / ai_model
│   ├── rule_engine / rule_execution_log
│   └── notification_template / notification_log
├── 设备与传感器
│   ├── device / device_data
│   ├── device_group / device_firmware
│   └── personnel_position
├── 审计与日志
│   ├── operation_log（自动写入所有写操作）
│   └── login_log
└── 时序数据（TDengine）
    └── device_metrics（高频遥测）
```

---

## 5. 部署架构

### 5.1 开发模式

```
┌────────────────┐
│ Docker Compose │
├────────────────┤
│ postgres       │ (15432)
│ redis          │ (16379)
│ minio          │ (19000)
│ emqx           │ (11883/28083)
│ tdengine       │ (16030/16041)
└────────────────┘

宿主直跑：
- Spring Boot (8080)
- Vite dev (5173)
- AI FastAPI (8001)
```

### 5.2 生产模式

```
┌────────────────┐
│  Nginx 反代     │ (80/443)
│  ├─ /api →     │ backend:8080
│  ├─ /ws →      │ backend:8080/ws
│  └─ / →        │ frontend:80 (静态)
└────────────────┘

Docker Compose Prod：
- backend (8081)
- frontend (80)
- postgres / redis / minio / emqx / tdengine
```

---

## 6. 关键技术决策

| 议题 | 决策 | 理由 |
|---|---|---|
| 前后端分离 | Vue SPA + Spring Boot REST | 独立部署、技术栈灵活 |
| 鉴权 | JWT + 一次性 WebSocket ticket | 避免长连接 token 泄露 |
| 实时通信 | WebSocket + MQTT 双通道 | 业务推送 + 设备接入分离 |
| 地图引擎 | OpenLayers + Cesium 双引擎 | OL 处理二维业务，Cesium 处理三维地形 |
| 状态管理 | Pinia + Composable | 替代 Vuex，更轻量 |
| 服务层 | `_architecture/core` + `_architecture/domain` | 通用 vs 业务隔离，便于测试 |
| 时序数据 | TDengine 独立集群 | 避免 PostGIS 表膨胀 |
| 文件存储 | MinIO S3 兼容 | 避免本地文件系统依赖 |
| 灰度/降级 | AI 微服务可独立禁用 | 离线时仍可运行 |
| 移动端 | PWA 渐进式 Web App | 无需 App Store 发布 |

---

## 7. 安全模型

### 7.1 角色 × 模块矩阵

完整矩阵见 [docs/DEVELOPER.md](DEVELOPER.md) §7 与 `src/views/system/UserManagementPage.vue` 中"权限矩阵"标签页。

7 角色：
- `admin` — 管理员（全权限）
- `system_admin` — 系统管理员
- `emergency_commander` — 应急指挥官
- `attendant` — 值班员
- `expert` — 专家
- `ai_engineer` — AI 工程师
- `maintenance` — 运维

### 7.2 三层权限

1. **菜单权限** — 侧边栏可见性
2. **路径权限** — URL 可达性
3. **操作权限** — 单条记录 CRUD（编码格式：`<resource>:<action>`）

---

## 8. 性能指标（目标）

| 指标 | 目标 |
|---|---|
| 首屏加载（移动端 PWA） | < 2s（4G） |
| 实时地图缩放帧率 | ≥ 50 FPS |
| WebSocket 消息延迟 | < 500ms（局域网） |
| 灾害列表查询 | < 800ms（10 万条） |
| AI 推理单次响应 | < 2s（v100 GPU） |
| 并发用户 | 1000+ WebSocket 长连接 |

---

## 9. 演进路线

- **v1.7+**：统一事件中心、PWA 移动端、社交媒体舆情、通知渠道配置、帮助中心
- **v2.0（规划）**：多租户、SaaS 化、知识图谱、灾情仿真
