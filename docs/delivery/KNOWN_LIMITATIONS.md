# 已知限制与诚实边界（Known Limitations）

> 适用版本：DisasterGuard / 御灾智巡 v2.0（工作区快照 2026-11-12）
> 本清单只收录**代码层面可证实**的限制，不把"未验证"包装成"已实现"。
> 每条给出证据路径；标注「待确认」的条目须由甲方业务方/部署人员拍板后再决定是否补开发。
> 行号基于当前工作区快照，并行修复可能造成漂移。

---

## 〇、已修复项（留档备查）

| 原问题 | 处理结果 | 证据 |
|---|---|---|
| 后端权限注册表缺 `tasks:*` / `backup:*` / `plans:*` / `reports:*`，相关模块全角色 403 | 前端 `ROLE_PERMISSION_MAP` 补齐 13 个权限码并按角色分配，重新运行生成脚本后后端注册表覆盖全部 79 个后端必需权限码（`required - registry = 0`） | `frontend/src/stores/auth.js:150-290`；`backend/.../common/security/PermissionRegistry.java`；`scripts/fix/sync-permission-registry.mjs` |
| `event_sign.sign_id` 误加 UNIQUE，多人会签插入第二行即 500 | 实体去掉 `unique`、schema 改普通索引、启动时幂等删除旧库唯一约束、提供手工迁移 SQL | `EventSign.java:22-30`；`schema.sql:598-621`；`DatabaseSchemaPatchInitializer.java`；`scripts/db/migrations/20261112_event_sign_drop_sign_id_unique.sql` |
| 「我的工作台」纯占位（统计全 0、无接口） | 改为真实 `/tasks` + `/todo/summary` 数据，失败显示"—"与错误提示；新增指挥大屏/移动端/预警/巡检快捷入口 | `frontend/src/views/system/WorkbenchPage.vue` |
| 前端 eslint 14 处存量错误 | 已全部清零（`no-console` 删除调试日志、`curly` 自动修复）；CI 全量 lint 从 advisory 翻转为阻断 | `.github/workflows/frontend-ci.yml`；`npx eslint src/` 退出码 0 |
| 实时地图时间轴"回放"只弹提示不换数据 | 显式禁用播放/拖动并标注"演示刻度，历史回放待后端接口"，不再伪装成可用功能 | `frontend/src/views/monitor/RealTimeMapPage.vue:65-72,544-570` |
| Analytics 巡检报告/时间对比接口为服务端模拟数据却无标识 | 后端响应新增 `synthetic:true / dataSource:SIMULATED / note`；前端展示"演示数据，待接入真实统计"标注，并删除主 Tab 硬编码 92.4% | `AnalyticsController.java:96-116`；`frontend/src/views/data/AnalyticsPage.vue` |
| **WS 预警推送全部静默失败**（运行时实测发现）：`RealtimeBroadcastService` 用裸 `new ObjectMapper()`，`WarningDto.triggeredAt`（OffsetDateTime）序列化抛异常，前端收不到任何 `warning` 推送，只能靠 10s 轮询 | 改为注入 Spring `ObjectMapper` 并显式注册 `JavaTimeModule`；实测创建预警后 WS 收到 `type=warning` 消息（含 level/status/lon/lat/triggeredAt） | `backend/.../realtime/RealtimeBroadcastService.java:32-60`；验收记录 `docs/delivery/ACCEPTANCE_LOG_20261112.md` 第四节 |

### 2026-11-13 干净机器部署演练补修（A3）

以下 4 项均为**全新数据卷首次部署**才会触发的 P0 阻断，老库因历史数据/表已存在而长期未暴露。
全部由隔离环境实跑发现并修复，复现与证据见 `DEPLOYMENT_GUIDE.md` §9。

| 原问题 | 处理结果 | 证据 |
|---|---|---|
| 空卷 `compose up` 后 postgres `Exited(3)` | `initdb.d` 只挂载不依赖业务表的 `001_create_extensions.sql` / `003_archive_operation_log.sql`；`002_geom_triggers.sql` / `004_insert_demo_rules.sql` 保留作历史参考并加醒目说明 | `docker-compose.yml` postgres volumes；`infra/postgres/init/002_*.sql`、`004_*.sql` 文件头 |
| 空库首启失败：`data.sql` #21 违反 `emergency_event_warning_code_fkey` | 预警种子已停用，应急响应 `warning_code` 改为子查询取值（老库保持关联，空库降级 NULL） | `backend/src/main/resources/data.sql` §16 |
| 规则镜像 6 条 INSERT 全失败、规则缓存 0 条 | 系统同步规则的 `created_by` 由不存在的 `SYSTEM` 改为 NULL | `WarningRuleSyncService.java` |
| 规则入库后 6 条「解析规则失败」 | `rule.actions` 由对象改为 `List<RuleAction>` 对应的数组结构 | `WarningRuleSyncService.java`；`RuleEngineService.toDto` |

> 修复后空库首启 0 ERROR、`rule` 表 6 条、`WarningCreateService` 按真实指标自动产出预警。

### 2026-11-13 决策落地补修（A2-2 / A6-1 / A6-2 / A6-5 / B1 / B7）

| 原问题 / 决策 | 处理结果 | 证据 |
|---|---|---|
| A6-1 管理员代签默认禁用 | 决策「允许管理员代签」：`sign:proxy` 仅授予 admin，其余角色仍 403；每次代签写 warn 审计日志 | `data.sql` 权限码 93；`frontend/src/stores/auth.js`；`PermissionRegistry.java`；`SignProxyPermissionTest` |
| A6-2 / B1 时相对比页面缺失（原 L-05） | 决策「必做」：新增独立页面、Java 网关代理、菜单/路由/权限；无真实影像时只提示降级、不展示假图 | `SatelliteComparePage.vue`、`SentinelCompareController.java`、`satelliteCompareApi.js`；`ACCEPTANCE_LOG` §三十一 |
| A6-5 时间显示口径 | 决策「跟随浏览器时区」，不做代码改动；服务端 `TZ=Asia/Shanghai` 仅用于日志/调度 | 本文件 L-15；`OPERATOR_CARD.md` §1 |
| A2-2 演示材料归属 | 决策「单独材料包」：19 个 PPTX + 演示脚本 + 交付文档单独打包，**不入库** | `output/delivery-materials/…zip`（含 `MANIFEST.md` + SHA256） |
| B7-3 值班员操作材料 | 新增一页纸操作卡（登录/预警处置/分析详情/代签/时相对比/时区） | `docs/delivery/OPERATOR_CARD.md` |

> 时相对比仍受外部影像源约束：**没有 CDSE/GEE 凭证或可访问的公开 STAC 源时，页面只显示降级提示**，
> 这不是功能未完成，而是刻意的诚实边界（对应 C3 / L-04）。

---

## 一、AI 能力边界

### L-01 多模态融合 / 模型漂移：后端网关为显式 501 桩

- **现状**：`GET/POST /api/ai/drift/**`、`/api/ai/multimodal/**` 在后端固定返回 `501 NOT_IMPLEMENTED + deprecated:true`，不转发到 ai-service；前端若直连 8001 才可能拿到数据。
- **证据**：`backend/src/main/java/cn/disasterguard/ai/AiDeprecatedGapController.java:14-30,37-45,47-78`；前端调用侧 `frontend/src/services/_architecture/domain/driftMonitorService.js`、`multiModalService.js`。
- **影响**：查后端网关时"模型漂移/多模态"能力不可用；页面会显示「漂移检测服务不可用」而非伪造曲线（`frontend/src/views/ai/ModelManagePage.vue:347-352`）。
- **边界说明**：ai-service 侧 `/api/ai/multimodal/*` 与 `/api/ai/drift/*` 有实现，但**返回的是演示/mock 数据**（`ai-service/app/routers/multimodal.py:121-131` 的 `_demo_meta()` 固定 `mock:true`），不是生产模型结论。
- **建议**：交付演示时明确说明该模块为"演示能力"；若甲方需要真实能力，需单独排期接入模型与网关转发。

### L-02 AI 模型训练：无训练任务表/调度器，相关端点 501

- **现状**：模型训练下发/调度/停止接口一律返回 501，不假装成功。
- **证据**：`backend/src/main/java/cn/disasterguard/ai/AiController.java:841-866`。
- **影响**：模型管理页的"重训"链路不可用（页面有对应提示）。
- **待确认**：是否需要交付前补训练任务表与调度器（工作量大，建议列为二期）。

### L-03 推理结果可能为 mock 数据（模型/依赖缺失时）

- **现状**：ai-service 在模型文件或推理依赖缺失时仍返回 200，但响应带 `mock:true`（detection/anomaly/forecast/fusion/social/comprehensive 等路由均有该字段）。
- **证据**：`ai-service/app/routers/detection.py:55,112,312,343`；`ai-service/app/routers/anomaly.py:114,133`；`ai-service/app/routers/fusion.py:31,45`；`ai-service/app/routers/comprehensive.py:55,83`。
- **影响**：健康检查 `UP` 不代表模型真实可用；验收时须核对响应中的 `mock` 字段。
- **建议**：交付前对 `GET /api/ai/model/health`、`/api/ai/model/info` 留存截图，确认 `mock=false` 与模型版本。

### L-04 图像识别/卫星影像依赖外部数据源与凭证

- **CDSE（Copernicus）**：`COPERNICUS_CLIENT_ID/SECRET` 为空时降级到公开 STAC（Element84/USGS），可用但受第三方服务可用性影响（`frontend/.env.example:156-171`）。
- **Sentinel 差异计算**：`SENTINEL_CDN_BASE` 为空时结果图落本地 `file://`，前端（浏览器）无法直接访问；仓库内 `frontend/tests/test_satellite_diff.py` 为人工端到端脚本，不是自动化测试。
- **外部瓦片**：未配置高德/天地图 Key 时底图降级到 ESRI Clarity 等公开源，存在境外源可用性/合规性风险（`frontend/.env.example:103-121`、`frontend/src/services/_architecture/core/mapService.js:160-200`）。
- **待确认**：甲方现场网络是否允许访问境外地图/STAC 服务；若不允，需提前采购高德 Key 或改用内网瓦片。

---

## 二、功能完整性边界

### L-05 时相对比（灾前/灾后）— 本轮已实现，剩余外部数据源边界

- **决策**：A6-2「时相对比必做」（2026-11-13），B1 已关闭；不再是 redirect 占位。
- **现状**：`/dashboard/satellite-compare` 指向 `SatelliteComparePage.vue`（选灾害点/手填 AOI → 两期合成 → 差异热力图 + 变化率 + Top 热点）；前端统一走 Java 网关 `/api/ai/sentinel/**`（JWT + `satellite:view`），不再直连 ai-service 8001；图片由网关代理为同源地址。
- **证据**：`frontend/src/views/ai/SatelliteComparePage.vue`、`frontend/src/services/satelliteCompareApi.js`、`backend/.../ai/SentinelCompareController.java`、`frontend/src/router/index.js`、`frontend/src/data/dashboardData.js`；`SentinelCompareControllerTest`、`scripts/delivery/verify-sentinel-chain.mjs`。
- **剩余边界（非缺陷）**：真实卫星影像仍需 CDSE/GEE 凭证或可访问的公开 STAC 源（见 C3 / L-04）。无真实影像时 ai-service 明确返回 `dataSource=mock`，页面**只显示降级原因、不提交差异任务、不展示假图**（B1-4）。

### L-06「我的工作台」曾为占位页（本轮已修复）

- **原现状**：页面仅展示 0 值统计卡、无任何接口调用。
- **本轮处理**：改为真实接口（`/api/tasks` + `/api/todo/summary`），失败时显示"—"与明确错误提示，不把 0 当业务结果；并增加指挥大屏、移动端、预警中心、AI 巡检任务快捷入口。
- **证据**：`frontend/src/views/system/WorkbenchPage.vue`（`loadData` / `stats` / `todoItems` / `quickLinks`）。
- **剩余边界**：待办/审批/消息详情仍在顶栏通知抽屉，工作台只做汇总入口（符合当前产品口径）。

### L-07 系统设置曾仅存 localStorage（本轮已修复）

- **原现状**：主题/语言/默认首页/免打扰时段等设置写入浏览器 `localStorage`，后端无对应配置表接口；换浏览器/清缓存即恢复默认，多终端不一致。
- **本轮处理**：新增 `system_setting` 表（启动幂等补建）+ `GET/PUT /api/settings`
  （`settings:view` / `settings:edit`），设置页保存时后端落库、读取时回填，`localStorage` 仅作离线兜底；
  浏览器自动化用例"保存 → 刷新 → 仍存在"已通过（`SM-16`）。证据：
  `backend/.../system/SystemSettingService.java`、`SystemSettingController.java`、
  `frontend/src/views/settings/SettingsPage.vue`（`loadRemoteSettings` / `persist`）。
- **剩余边界**：主题/语言/默认首页同时保留本地即时生效（避免刷新闪烁），跨设备一致性以服务器值为准。

### L-07b 预警「AI 分析详情」影像依赖真实上传

- **现状**：分析详情页的"现场影像"来自灾害点/预警附件图片 + 关联设备最近上传到 MinIO 的影像；
  指标来自真实 `device_metric` 时序（触发时刻 ±2h，缺失时退化为最新快照并明确标注）。
- **边界**：种子数据中的附件 URL 为历史路径，对象可能不存在（页面显示"影像地址已过期"占位）；
  演示前应在灾害点详情上传真实现场照片，或在设备模拟器上报图像。
- **证据**：`backend/.../warning/service/WarningAnalysisService.java`、
  `frontend/src/components/warning/WarningAnalysisDetail.vue`。

### L-08 部分"高级能力"为显式 501 桩（诚实边界，非遗漏）

- 备份校验：`backend/src/main/java/cn/disasterguard/backup/BackupController.java:225-235`（返回"备份校验尚未实现：当前备份记录为内存模拟数据，未落库"）。
- 数据资产访问申请：`backend/src/main/java/cn/disasterguard/governance/CatalogController.java:392-402`。
- **影响**：对应页面按钮会收到 501 明确提示，不会被误认为成功；交付时应在操作手册中标注"二期能力"。

---

## 三、数据与演示标识

### L-09 演示数据来源与标识

- **种子数据**：`backend/src/main/resources/data.sql` 初始化字典/角色/用户/规则/设备/轨迹/指标/审计等演示数据；脚本内已标注部分为演示样本（`:659`「新增演示设备」、`:723-729`「移除写死的演示预警」、`:828-839`「演示轨迹/演示实时数据」、`:869,886` 演示登录记录与附件）。
- **运行模式角标**：前端左下角「演示模式 / 后端连接中」角标，可在任意页面看到（`frontend/src/App.vue:14-44`）。
- **AI mock 标识**：见 L-03。
- **风险**：若"演示数据"被当成生产数据汇报，会误导验收。交付时必须说明"演示数据仅用于功能演示，生产上线前需清空/替换（`data.sql` 为幂等种子，不会自动删除业务数据）"。
- **待确认**：甲方是否要求在 UI 上增加全局「演示数据」水印（当前只有左下角模式角标，未对种子数据逐条打标）。

---

## 四、安全与工程门禁边界

### L-10 ai-service 无鉴权，直连 8001 可被任意调用

- **现状**：FastAPI 服务不校验 JWT，任何可达 8001 的调用方都能调用推理接口；nginx 直连代理仅解决同源可达性，不构成鉴权。
- **证据**：`infra/nginx/frontend.conf:32-33`（注释明确写明"本代理只解决可达性 + 同源，不构成鉴权"）；`ai-service/app/main.py`（无鉴权中间件）。
- **缓解**：部署时用网络策略限制 8001 仅内网可达；生产建议把 AI 能力收回 Java 网关或补服务间凭证。
- **待确认**：甲方安全等保要求（若有，需在部署方案中写明网络隔离措施）。

### L-11 会签数据模型（sign_id 唯一约束本轮已修复；signerId 语义仍需统一）

1. ~~**`event_sign.sign_id` 被声明为 UNIQUE**，导致多人会签插入第二行 500~~ → **本轮已修复**：
   - 实体：`backend/src/main/java/cn/disasterguard/event/entity/EventSign.java:22-30`（去掉 `unique = true`，改普通索引 `idx_event_sign_sign`）；
   - 建表：`backend/src/main/resources/schema.sql:598-621`（`sign_id` 改普通列 + 普通索引）；
   - 旧库升级：启动时 `DatabaseSchemaPatchInitializer` 幂等删除唯一约束；也可手工执行 `scripts/db/migrations/20261112_event_sign_drop_sign_id_unique.sql`。
   - **仍待真机验证**：需要 PostgreSQL 上实测"选择 2 名以上会签人"完整流程（本轮无数据库环境）。
2. **signerId 语义前后端不一致**：
   - 发起会签时前端传 `sys_user.id` 字符串（`frontend/src/views/hazard/ConfirmationSignPage.vue:59-64`，`id: String(u.id ?? u.username)`）；
   - 确认按钮传的却是 `operator.name`（username，`ConfirmationSignPage.vue:356-361`）。
   - **缓解**：本次后端已兼容两种写法（`SignService.java:144-200`），但建议前端统一为 userId，避免歧义（前端页面属其他工作流 scope，本次未改）。

### L-12 会签管理员代签 — 本轮按决策开启（仅 admin）

- **决策**：A6-1「允许管理员代签」（2026-11-13）。`sign:proxy` 已写入权限种子并由生成脚本同步到 `PermissionRegistry`，**只授予 admin**；其余 6 个角色仍默认拒绝。
- **证据**：`backend/src/main/resources/data.sql`（权限码 93）、`frontend/src/stores/auth.js`、`backend/.../common/security/PermissionRegistry.java`、`backend/src/test/java/.../SignProxyPermissionTest.java`（2 用例：admin 放行 / expert 仍 403）。
- **审计**：`SignService.assertCanActOn` 在每次代签时写 `warn` 日志（含 signId / signerId / operator），冒名防护对无权限角色未削弱。
- **运维提示**：该权限可在角色权限矩阵中回收；回收后所有代签立即回到 403，无需改代码。

### L-13 前端 lint 存量（本轮已清零）

- **原现状**：`npx eslint src/` 报 14 errors（`useAiContext.js` no-console ×2、`weatherApi.js` curly ×11、`hazardEventBus.js` curly ×1）。
- **本轮处理**：删除 2 处调试 `console.log`，其余 12 处 `curly` 已 `--fix`；当前 `npx eslint src/` **0 error**。
- **证据**：`.github/workflows/frontend-ci.yml` 的全量 lint 步骤已移除 `continue-on-error`，改为阻断门禁。

### L-14 自动化测试覆盖有限

- **前端**：`frontend/src/__tests__/` 仅 `auth.spec.js` 一个用例文件；UI 交互/告警/地图无自动化回归，依赖本清单人工冒烟。
- **后端**：`backend/src/test` 共 18 个测试类，其中 3 个 `@SpringBootTest` 需真实 PostgreSQL/Redis/MinIO，CI 中已显式排除（`.github/workflows/ci.yml:114-115`）。
- **ai-service**：`ai-service/tests/` 有 10 个 pytest 文件，但本次未执行（沙箱无依赖/服务）。
- **影响**：交付证据以人工冒烟 + 构建门禁为主，不能宣称"全覆盖自动化测试"。

---

## 五、其他

### L-15 时区/编码约定

- 服务默认 `TZ=Asia/Shanghai`（根 `.env.example`、`docker-compose.yml`）；时间字段后端用 `OffsetDateTime`（UTC+偏移）落库，前端按本地时区展示（如 `frontend/src/views/system/AuditLogPage.vue:123`、`frontend/src/views/monitor/WeatherPage.vue:216` 的 `toLocaleString('zh-CN', { hour12: false })`）。
- **决策已定（A6-5，2026-11-13）：跟随浏览器本地时区**，不再统一固定北京时间，**不做代码改动**（B7-2 因条件不满足而关闭）。服务端 `TZ=Asia/Shanghai` 继续用于日志与调度，与前端展示口径互不冲突。
- **现场提示**：演示/值班前确认浏览器所在机器的时区与系统时间；见 `OPERATOR_CARD.md` §1、§6。

### L-16 地图/外部服务合规

- 生产环境使用高德/天地图需商业授权与域名白名单；`VITE_AMAP_TOKEN` 未配置时降级到境外 ESRI 源，可能不符合测绘合规要求。
- **证据**：`frontend/.env.example:103-121`、`infra/nginx/frontend.conf:12-16`（CSP 白名单注释）。
- **待确认**：甲方是否已有高德企业 Key 及测绘资质编号。

### L-17 Analytics 巡检报告/时间对比为服务端模拟数据（已加显式标识）

- **现状**：`GET /api/analytics/inspection-report` 与 `/api/analytics/compare` 的指标由查询参数确定性生成（如 `coverage = 60 + seed % 30`），不是数据库真实统计。
- **本轮处理**：后端响应新增 `synthetic: true`、`dataSource: "SIMULATED"`、`note` 字段（两个接口均已加，运行时实测均返回 `synthetic=true`）；前端「AI 巡检报告」页展示"当前为演示数据"提示，主 Tab 的「巡检覆盖率」卡片改为读取该字段并标注"演示数据 · 待接入真实统计"（删除原硬编码 92.4%）。
- **证据**：`backend/.../analytics/AnalyticsController.java:96-116`；`frontend/src/views/data/AnalyticsPage.vue`；验收记录 `docs/delivery/ACCEPTANCE_LOG_20261112.md` 第三节。
- **待确认**：是否需要交付前把该报表接入真实巡检任务/事件统计（后端改造约 1-2 人日）。

### L-18 实时地图时间轴为演示刻度（已禁用交互）

- **现状**：地图页底部"最近 24h"时间轴仅生成 24 个刻度，拖动/播放只会弹提示，不会加载历史快照；后端目前没有按时间维度返回地图数据的接口。
- **本轮处理**：显式禁用播放按钮与滑杆，标题标注"演示刻度，历史回放待后端接口"，避免把假交互当功能交付。
- **证据**：`frontend/src/views/monitor/RealTimeMapPage.vue:65-72,544-570`。
- **待确认**：是否需要二期接入历史快照表/时序查询接口（约 3-5 人日）。

---

## 附：限制清单维护约定

1. 新增"未实现/降级/演示"能力时，必须在本文件登记并给出路径证据，禁止只在代码注释里写。
2. 「待确认」条目在交付评审会上逐条过，结论写入 `docs/delivery/DELIVERY_CHECKLIST.md` 的"遗留决策"区。
3. 本文件不修改旧文档（`docs/DEVELOPER.md`、`DEPLOYMENT.md` 等），旧文档中的过期描述以本文件与代码为准。
