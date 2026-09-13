# 项目结构优化方案（草案 v0.1）

> 目标：在 **不破坏业务** 的前提下，提升项目整体的 **可读性与可维护性**。
> 范围：前端 `src/`、后端 `cn.geosentry.*`、AI 服务 `ai-service/app/`、配置与文档。
> 原则：**渐进式重构** — 每次只动一个域，提交即通过，不引入功能回退。

---

## 0. 当前结构快照（基线）

| 维度 | 数据 |
|---|---|
| 前端 Vue 视图 | **50** 个（**13** 个 > 30KB，**单文件体积偏大**） |
| 前端 Components | **26** 个文件，已按业务分子目录（confirmation / notification / profile / route-planning / rule / warning）|
| 前端 Services | **18** 个 `.js`，全部平铺在 `src/services/`，命名风格混杂（`aiApi.js` vs `aiService.js` / `dashboardApi.js` vs `dashboardApi.js`）|
| 前端 Stores | `auth` 单 store，无业务域 store |
| 后端包 | **21** 个，最大的 `ai`(35) / `device`(22) / `notification`(22) / `auth`(18) / `ruleengine`(18) |
| 后端大文件 | 暂未统计（多个 Controller > 12KB 嫌疑） |
| AI 服务 | `app/` 下 4 个目录（models / routers / schemas / training）+ `main.py` |
| 文档 | `DEVELOPER.md`、`UNIFIED_EVENT_STATEMACHINE.md` |

---

## 1. 主要问题清单（按影响排序）

### 🔴 P0 - 影响协作效率，必须改

| # | 问题 | 位置 | 后果 |
|---|---|---|---|
| 1 | **前端 Views 平铺 50 个文件** | `src/views/` | 寻找页面靠记忆，无法按业务域扫描 |
| 2 | **前端 Services 命名混杂 + 平铺** | `src/services/` | `aiApi.js` vs `weatherApi.js` 与 `weatherService.js`、`dashboardApi.js` 同类名风格不统一 |
| 3 | **Services 同时存在 mock 与真实两套实现** | `src/services/*.js` + `src/services/mock/` | 离线/在线切换逻辑分散在多处，缺乏单一开关 |
| 4 | **后端 ai 包体量过大（35 个类）** | `backend/.../ai/` | 控制器、服务、调度、DTO、实体、仓库混在一起 |
| 5 | **前端没有业务域 Pinia store** | `src/stores/` | 所有页面内自己 `ref/reactive`，跨页共享状态重复维护 |

### 🟡 P1 - 影响长期维护

| # | 问题 | 位置 | 后果 |
|---|---|---|---|
| 6 | **后端控制器存在业务逻辑泄漏** | `backend/.../warning/WarningController.java` 等大文件 | 事务边界模糊，单元测试难写 |
| 7 | **重复的"导出"模式** | `backend/.../common/export/*ExportRow.java` + `ExcelExportService` | 各业务导出模式同质，新增模块需重复样板 |
| 8 | **AI 服务 `app/` 缺少清晰分层** | `ai-service/app/models` 13 个文件、`routers` 14 个文件平铺 | 模型与路由一一对应关系不直观，缺少 `services/` 中介层 |
| 9 | **前端大体积页面**（13 个 > 30KB） | `OverviewPage.vue` / `HazardsListPage.vue` 等 | 单文件 SFC 模板/脚本/样式混杂，难以协作 |
| 10 | **后端无统一异常/响应包装检查** | `backend/.../common/exception/GlobalExceptionHandler.java` | 各 Controller 自行 `try-catch`，错误信息风格不一 |
| 11 | **后端无统一的 Service 基类/CRUD 模板** | `backend/.../*/service/*.java` | 重复样板代码（log、事务、权限校验） |

### 🟢 P2 - 工程规范类

| # | 问题 | 位置 |
|---|---|---|
| 12 | 文档不完整（只有 DEVELOPER + UNIFIED_EVENT_STATE） | `docs/` |
| 13 | 没有 CONTRIBUTING / CHANGELOG / 编码规范 | 根目录 |
| 14 | 前端 ESLint 配置不严（仅 `eslint src/`，缺规则集） | `eslint.config.js` |
| 15 | 后端无 Checkstyle/Spotless 统一格式化 | `backend/pom.xml` |

---

## 2. 改造方案（按域拆分）

### 🎨 2.1 前端结构重构（建议先做，收益最大）

#### 2.1.1 Views 按业务域分层

```
src/views/
├── overview/                  # 总览、驾驶舱
│   ├── OverviewPage.vue
│   ├── ZoneDashboardPage.vue
│   └── RealTimeMapPage.vue
├── hazard/                    # 隐患 + 卫星对比
│   ├── HazardsListPage.vue
│   ├── HazardDetailPage.vue
│   ├── HistoryHazardsPage.vue
│   └── SatelliteComparePage.vue
├── warning/                   # 预警全生命周期
│   ├── WarningsPage.vue
│   ├── WarningDetailPage.vue
│   ├── WarningCreatePage.vue
│   ├── WarningDeliveryPage.vue
│   └── WarningResolvePage.vue
├── confirmation/              # 现场确认
│   ├── ConfirmationWorkbenchPage.vue
│   └── ConfirmationSignPage.vue
├── ai/                        # AI 业务
│   ├── AiTaskManagePage.vue
│   ├── TaskDetailPage.vue
│   ├── AiResultsPage.vue
│   ├── AiInsightPage.vue
│   └── ModelManagePage.vue
├── device/                    # 设备
│   ├── DevicesPage.vue
│   ├── DeviceDetailPage.vue
│   ├── DeviceMapPage.vue
│   └── OtaManagePage.vue
├── task/                      # 巡查任务
│   ├── TasksPage.vue
│   └── InspectionTaskPage.vue (如需独立)
├── rule-engine/               # 规则引擎
│   └── RuleEnginePage.vue
├── emergency/                 # 应急
│   ├── EmergencyPage.vue
│   ├── RoutePlanningPage.vue
│   └── ResourceDispatchPage.vue
├── weather/                   # 气象
│   └── WeatherPage.vue
├── system/                    # 系统管理（合并 Settings / AccountSecurity / Profile）
│   ├── SettingsPage.vue
│   ├── AccountSecurityPage.vue
│   ├── ProfilePage.vue
│   ├── UserManagementPage.vue
│   ├── AuditLogPage.vue
│   ├── SystemMonitorPage.vue
│   ├── BackupRecoveryPage.vue
│   ├── DataCatalogPage.vue
│   ├── DataSourcesPage.vue
│   ├── DataSyncPage.vue
│   ├── NoticeTemplatePage.vue
│   ├── PlanManagePage.vue
│   ├── ReportCenterPage.vue
│   └── AnalyticsPage.vue
├── message/                   # 消息聚合
│   ├── MessageCenterPage.vue
│   ├── TodoCenterPage.vue
│   └── ApprovalCenterPage.vue
├── auth/                      # 鉴权（公开）
│   ├── WelcomePage.vue
│   └── LoginPage.vue
└── error/                     # 错误页（公开）
    ├── ForbiddenPage.vue
    └── ServerErrorPage.vue
```

> **改动成本**：只需修改 `router/index.js` 的 import 路径，业务代码零改动。
> **迁移工具**：一次性脚本批量移动 + 改 import（10 分钟可完成）。

#### 2.1.2 Services 统一命名 + 按域分组

**命名规范**：统一使用 `<domain>Service.js`，废弃 `xxxApi.js` 后缀（API 调用细节封装在内部）。

```
src/services/
├── index.js                   # 统一导出（re-export）
├── core/                      # 通用基础
│   ├── httpClient.js          # axios 实例 + 拦截器
│   ├── mockSwitch.js          # mock / 真实切换开关
│   ├── permission.js          # 权限工具
│   └── realtimeService.js     # WebSocket 统一封装
├── domain/                    # 业务域
│   ├── authService.js         # 鉴权（合并 Login + Profile）
│   ├── overviewService.js     # 总览
│   ├── hazardService.js       # 隐患 + 历史
│   ├── warningService.js      # 预警全流程
│   ├── deviceService.js       # 设备 + MQTT 状态
│   ├── taskService.js         # 巡查任务
│   ├── aiService.js           # AI 任务 + 结果 + 研判
│   ├── modelService.js        # 模型 + 漂移监控
│   ├── ruleEngineService.js   # 规则引擎
│   ├── notificationService.js # 通知路由
│   ├── eventService.js        # 统一事件状态机
│   ├── emergencyService.js    # 应急 + 路径
│   ├── weatherService.js      # 气象
│   ├── systemService.js       # 用户/审计/字典
│   ├── reportService.js       # 报表导出
│   ├── messageService.js      # 消息 + 待办 + 审批
│   ├── mapService.js          # 地图基础
│   └── satelliteService.js    # 卫星对比
└── mock/                      # mock 统一目录（保留）
    ├── index.js
    ├── seed.js
    └── runtime.js
```

> **改动成本**：批量重命名 + 修改所有 `import` 引用（自动化脚本可完成）。
> **好处**：路径即语义，新增域不需要思考放哪。

#### 2.1.3 引入按域的 Pinia Store

```
src/stores/
├── index.js
├── auth.js              # 现有
├── realtime.js          # WebSocket 连接状态、消息流
├── userPreference.js    # 通知偏好、主题
├── hazard.js            # 当前选中隐患
├── warning.js           # 预警过滤器、当前操作预警
├── ai.js                # AI 任务进度
└── device.js            # 在线设备缓存
```

#### 2.1.4 大体积页面拆分

对 13 个 > 30KB 的页面，按"模板 + 业务子组件"拆分：

| 页面 | 行数估计 | 拆分方向 |
|---|---|---|
| `OverviewPage.vue` | ~80KB | 拆出 `WeatherLegend` / `QuickActions` / `RiskSummaryCard` 等子组件 |
| `HazardsListPage.vue` | ~? | 拆出筛选器/表格/地图子组件 |
| `WarningsPage.vue` | ~? | 拆出预警卡片/过滤器 |
| `DeviceMapPage.vue` | ~? | 拆出图层控制/轨迹回放 |
| `RealTimeMapPage.vue` | ~? | 拆出实时数据浮层 |
| 其余 | — | 视情况 |

> 拆分原则：**每子组件 ≤ 200 行**，不超过 1KB 行；保持 SFC 可读性。

---

### ☕ 2.2 后端结构重构

#### 2.2.1 大包瘦身

`ai/` 包 35 个文件 — 按职责拆分：

```
backend/.../ai/
├── controller/              # 仅放 Controller
├── service/
│   ├── forecasting/         # 预测
│   ├── detection/           # 检测
│   ├── fusion/              # 多模态融合
│   ├── deviceHealth/        # 设备健康
│   ├── drift/               # 漂移监控
│   └── assessment/          # 灾害评估
├── scheduler/               # 定时任务
├── client/                  # AI 服务调用客户端
├── dto/
├── entity/
└── repository/
```

`notification/` 包 22 个文件 — channel 已经是子目录，可继续保持；其它可下沉 `core`：

```
backend/.../notification/
├── controller/
├── service/                 # NotificationRouter, NotificationService
├── channel/                 # sms/email/app/screen/soundlight/webhook
├── config/                  # 通道配置
├── dto/
├── entity/
└── repository/
```

`auth/` 包 18 个 — 拆出 `profile` 与 `preference` 子包（已有 UserProfile/UserPreference 实体）：

```
backend/.../auth/
├── controller/              # AuthController, UserProfileController
├── service/
│   ├── AuthService.java
│   ├── UserProfileService.java
│   └── UserPreferenceService.java
├── jwt/                     # JwtService, JwtProperties
├── security/                # DbUserDetailsService, PermissionRegistry
├── dto/
├── entity/                  # SysUser, UserProfile, UserPreference
└── repository/
```

#### 2.2.2 统一 Service 基类 + 通用模板

```java
public abstract class BaseCrudService<E, D> {
    protected abstract JpaRepository<E, ?> repository();
    protected abstract D toDto(E e);
    protected abstract E fromDto(D d);
    // ... 通用 CRUD + 审计字段自动填充
}
```

#### 2.2.3 统一导出工具下沉

`common/export/` 已经存在，**升级为**：
- `ExcelExportService`（已有）+ `ExportRow` SPI 接口
- 业务方只需实现 `ExportRow<T>` 接口即可

---

### 🤖 2.3 AI 服务结构

```
ai-service/app/
├── main.py
├── core/                     # 配置、日志、异常
│   ├── config.py
│   ├── logging.py
│   └── exceptions.py
├── api/                      # 路由层（rename from routers/）
│   └── v1/
│       ├── forecasting.py
│       ├── detection.py
│       ├── fusion.py
│       ├── anomaly.py
│       ├── device_health.py
│       ├── drift.py
│       ├── assessment.py
│       ├── comprehensive.py
│       ├── multimodal.py
│       ├── sentinel.py
│       ├── social_media.py
│       ├── feedback.py
│       └── model_info.py
├── services/                 # 业务服务层（新增中介层）
│   ├── forecasting_service.py
│   ├── detection_service.py
│   ├── fusion_service.py
│   └── ...
├── models/                   # 模型定义 + 推理
├── schemas/                  # Pydantic schema
├── training/                 # 训练流水线
└── tests/
```

> 路由层只做：参数解析 → 调 service → 返回 schema。
> 重推理/特征工程逻辑放到 `services/`，便于测试与复用。

---

### 📚 2.4 文档与配置

```
docs/
├── README.md
├── DEVELOPER.md                # 现有
├── ARCHITECTURE.md             # 新增：架构总览
├── UNIFIED_EVENT_STATEMACHINE.md   # 现有
├── module/                     # 各业务域文档
│   ├── hazard.md
│   ├── warning.md
│   ├── ai.md
│   ├── notification.md
│   └── rule-engine.md
├── api/                        # OpenAPI 摘要
└── ops/                        # 部署/运维
    ├── docker.md
    └── troubleshooting.md
```

根目录补充：
- `CONTRIBUTING.md`（贡献规范）
- `CHANGELOG.md`（版本日志）
- `.editorconfig`（统一缩进/换行）

---

### 🛠 2.5 工程化规范

#### 前端
- 强化 `eslint.config.js`：
  - 启用 `vue/multi-word-component-names`
  - 启用 `no-console` (warn)
  - 启用 `import/order`
- 添加 `vite-plugin-vue-devtools`
- 添加 `vitest` 单元测试脚手架（可选）

#### 后端
- 引入 **Spotless** + **Checkstyle**：
  - 4 空格缩进、import 排序、行宽 120
  - 统一 lombok 使用规范
- 添加 `springdoc-openapi` 自动 API 文档（已有 `OpenApiConfig`，可启用 UI）

#### CI
- 添加 `.github/workflows/lint.yml`
- 添加 `.github/workflows/test.yml`

---

## 3. 推荐实施顺序（不影响业务的渐进式）

| 阶段 | 工作量 | 影响面 | 风险 |
|---|---|---|---|
| **Step 1**：前端 Services 改名 + 分组 | 0.5 人日 | 中（自动改 import） | 低 |
| **Step 2**：前端 Views 按域分层 | 0.5 人日 | 仅路由配置 | 低 |
| **Step 3**：新增 `core/` 目录 + 抽出 httpClient/realtime | 1 人日 | 中 | 低 |
| **Step 4**：新增 Pinia 业务域 store（先做 realtime + userPreference） | 1 人日 | 中 | 中 |
| **Step 5**：前端大页面拆分（先拆 OverviewPage） | 1~2 人日 | 单文件 | 低 |
| **Step 6**：后端 `ai/` 包拆分（按 service 子域） | 1~2 人日 | 大但机械 | 中 |
| **Step 7**：后端 `auth/` 拆分 + Service 基类 | 1 人日 | 中 | 低 |
| **Step 8**：后端统一导出 + 异常规范化 | 0.5 人日 | 小 | 低 |
| **Step 9**：AI 服务新增 `core/` 与 `services/` | 0.5 人日 | 小 | 低 |
| **Step 10**：文档补齐 + 工程化（lint/format/CI） | 1~2 人日 | 跨端 | 低 |

**预估总工作量**：约 8~12 人日，可在 2 周内分 5 个 Sprint 完成。

---

## 4. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 路由/Import 批量改名漏改 | 用 `jscodeshift` 编写 codemod 脚本，跑两次 PR |
| Service 改名后调用方断链 | 保留旧文件一行 re-export 转发，逐步下架 |
| 后端包移动导致循环依赖 | 拆分前用 `mvn dependency:tree` 留基线，CI 中加依赖检查 |
| 大页面拆组件后样式丢失 | 拆前截图快照；子组件用 `<style scoped>` 隔离 |
| 文档未及时同步 | 在 PR 模板里强制要求勾选"已更新对应模块文档" |

---

## 5. 待确认事项（请你回复）

| # | 议题 | 候选方案 |
|---|---|---|
| 1 | 是否接受 **渐进式** 改造？（推荐） | ✅ / ❌ |
| 2 | 是否同意 **Services 统一改为 `<domain>Service.js`** 命名？ | ✅ / ❌ |
| 3 | 是否同意 **Views 按业务域分子目录**（12 个子域）？ | ✅ / ❌ |
| 4 | 是否同意 **后端 ai/ 包按 service 子域再拆一层**？ | ✅ / ❌ |
| 5 | 是否同意 **引入 Pinia store 业务域化**？ | ✅ / ❌ |
| 6 | 是否同意 **为 13 个大页面建拆分计划**（先 OverviewPage）？ | ✅ / ❌ |
| 7 | 是否需要 **保留旧路径的兼容转发**（推荐 1 个版本周期）？ | ✅ / ❌ |
| 8 | 是否需要 **CI lint + format 门禁**？ | ✅ / ❌ |

---

## 6. 实施准备（确认后我会做的事）

1. 创建分支 `refactor/structure-optimization`。
2. 按 Step 1~Step 10 **每步一个 commit**，逐 PR 合并。
3. 每步完成后：
   - 跑通 `npm run lint` / `mvn -DskipTests verify`。
   - 在 `docs/CHANGELOG.md` 追加一行。
   - 同步更新本文件状态为 ✅。
4. 全部完成后删除本文件或归档到 `docs/archive/`。

---

> 📌 本文件 **不会自动修改任何代码**，需要你回复第 5 节确认后再进入实施阶段。