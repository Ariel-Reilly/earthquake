# DisasterGuard 前端「未实现 / 虚假 / 写死」功能核查报告

**核查范围**: `D:\Projects\DisasterGuard\frontend\src`（辅以后端 / ai-service / 引用关系取证）
**核查日期**: 2026-10-03
**核查方式**: 逐文件取证 + 跨端交叉验证（前端页面 ↔ 前端 API 层 ↔ 后端 Controller ↔ ai-service 路由）
**与本报告的关系**: 本报告在今日早前 4 份报告（`frontend-comprehensive-audit-report.md` 等）基础上**复核并追加根因分析**，不重复结论性描述，新增第 2、4 章的量化证据与"最后一公里"根因。

---

## 〇、修复进度（2026-10-03 第二批，含运行时验证）

### 已完成修复

| # | 文件 | 问题 | 修复方式 | 验证 |
|---|------|------|---------|------|
| 1 | `views/system/NotificationChannelsPage.vue` | 硬编码 5 渠道 + mock 日志 + `alert` + `setTimeout` 假操作 | 接 `notificationService` 四方法；`CHANNEL_CODE` 映射；"灾种×级别"矩阵聚合为渠道卡片；删除改为"停用"语义 | GET/PUT `/api/notification/channel-config` 实测 200 |
| 2 | `views/hazard/ConfirmationSignPage.vue` | 直接 `import EXPERT_POOL`（mock） | 改调 `getUsers({role:'expert'})`，`items ?? records` 双兜底 | `/api/users?role=expert` 实测 200，返回 1 名专家 |
| 3 | `views/ai/SocialMediaPage.vue` | 成都区县硬编码假数据 | 接 `aiApi.analyzeSocialMedia`；补 `timestamp` 驱动 timeline/per_source/location_clusters；`demoMode` 角标 + 错误重试 | 实测 200，三类聚合均有真实产出 |
| 4 | `views/hazard/HazardDetailPage.vue` | claim/confirm/reject/transfer 四操作假执行 | 接真实 API + `resolveEventId()` 反查事件主键 | 编译通过 |
| 5 | `views/ai/AiInsightPage.vue` | `handlePublishWarning` TODO（且 `defineEmits` 缺失使代码成死码） | 接 `createWarning` + `RISK_TO_WARNING_LEVEL` 映射 + 补 `emit` | 编译通过 |
| 6 | `components/rule/RuleList.vue` | 加载失败静默降级为 mock | 改空态 + `loadError` + 重试按钮 | 编译通过 |
| 7 | `layouts/DashboardLayout.vue` | 顶部搜索框无逻辑 | `Promise.allSettled` 并发搜 4 类实体 + 序号防竞态 | 构建通过 |
| 8 | `backend/.../ai/AiController.java` | **无社媒代理端点** | 新增 `POST /social-media/analyze`；归一化 texts（字符串→`{content}`） | 实测 200（修复前 503/422） |
| 9 | `services/_architecture/domain/aiApi.js` | 前端无法经网关调 ai-service | 新增 `analyzeSocialMedia` | 同上 |
| 10 | `driftMonitorService.js` / `multiModalService.js` / `src/.env` | **AI 端口 8000 写错（应 8001）** | 三处统一为 8001 + 多变量兜底 | 静态核对 |
| 11 | `services/dashboardApi.js` `normalizePaged` | **只认 `records`，丢弃 `items`** → `/api/users` 数据静默丢失 | 改为 `records ?? items` 双兼容 | 实测确认后端确返 `items` |

### 运行时抓出的两个真 Bug（静态审查未发现）

1. **`normalizePaged` 字段不兼容**：`GET /api/users` 返回 `items`，函数只认 `records` → 归一化成空数组。
   项目内 `ConfirmationSignPage`/`UserManagementPage` 各自写了 `?? items` 补丁，本次收拢到统层。
   **根因是后端分页字段自身不统一**（users=items / records=records），建议后端统一（未改）。
2. **社媒代理 texts 类型不匹配**：ai-service 契约 `texts: List[TextItem]`（对象数组），
   后端 `@SuppressWarnings("unchecked")` 强转 `List<Map<String,Object>>` 因泛型擦除不报错，
   下游直接 422 → 网关 503。已在网关层归一化。

### 门禁结果（全绿）

`mvn clean compile` 292 文件 BUILD SUCCESS · `check-missing-imports` 缺失 0 ·
`check-endpoint-parity` 后端 307/前端 209/孤儿 5（预先存在） · `check-broken-imports` 断链 0 ·
`check-timer-leaks` 全通过 · `vite build` ✓ 12.59s

### 复核状态（2026-11-06 代码级核证）

原"仍未修（下一批）"清单中的 9 项，经逐文件代码核证，**均已接入真实接口**，列表作废：

| 原"仍未修"项 | 核证结果 |
|---|---|
| MobileCommandPage 全假数据 | `grep mock\|写死\|假数据\|假告警` 0 命中，KPI/预警/待确认均走真实 API |
| SystemMonitorPage 假告警 | 行 57 注释确认告警"来自后端真实探测结果派生（服务 DOWN / CPU 内存超阈值）" |
| RoutePlanningPage 假资源点 | 行 97 注释确认"资源点来自后端 resource 表（GET /api/resources），非本地硬编码" |
| ResourceDispatchPage HAZARD_SEED 假坐标 | 行 80 注释确认 HAZARD_SEED 已移除，坐标取真实隐患点 |
| AnalyticsPage 4 个 CSS 模拟图表 | **本轮（2026-11-06）修复**：4 图改为绑定真实 `inspectionReport` 字段，删除全部硬编码兜底数组，缺数据诚实空态 |
| BackupRecoveryPage 伪进度 | 无 setTimeout/进度伪造残留，校验进度走真实任务状态 |
| HelpCenterPage 模拟提交 | `submitFeedback` 调 `dashboardApi.submitSupportFeedback`（真实落库），setTimeout 仅用于隐藏成功提示 |
| UserManagementPage 权限矩阵硬编码 | 行 94/101 确认矩阵来自 `GET /api/roles/permission-matrix`（权威源 `PermissionRegistry`），非 28×7 硬编码 |
| OtaManagePage/PlanManagePage sample 兜底 | 无 `samplePlans/sampleData/sampleDevices` 残留，列表/任务均走真实 API |

> 说明：上述核证基于文件名 + 关键字 grep + 关键行注释，未逐页做运行时联调。结论为"代码已无明显的硬编码 / mock 兜底"，与早前"最后一公里"批次的修复一致。

---

## 一、结论速览

用户反馈的三类问题**全部成立**，且数量可观：

| 类别 | 实测数量 | 其中"真·假功能"（任何模式下都假） |
|------|---------|--------------------------------|
| 功能未实现（有 UI 无逻辑 / TODO） | 12+ 处 | 3 处 |
| 功能虚假（假数据冒充真实功能） | 16+ 页面 | 3 处 P0 |
| 代码写死 / 硬编码 | 60+ 处 | 4 类高优 |
| **（新增）接口写好了但页面没接** | **5 个服务文件 + 3 个后端端点** | **5 处** ← 本轮核心新发现 |

**本轮最重要的新发现**：问题不只是"功能没做"，而是**"功能做了两遍、接了一半就断在最后一公里"**——后端 Controller、前端服务层都已写好并可用，但页面偏偏用了硬编码假数据（见第二章）。

---

## 二、🔴 核心新发现：「最后一公里」式虚假（本轮新证据）

前面的报告只说"页面用了假数据"。逐层取证后发现，**这些假数据对应的真实能力大多已经存在**，是典型的"管道铺到墙边、差一锤子"。

### 2.1 通知渠道配置 —— 后端 + 前端 API 层全都写好了，页面却用 mock

| 层级 | 文件 | 状态 |
|------|------|------|
| 后端实体 | `backend/.../notification/entity/NotificationChannelConfig.java` | ✅ 已实现（含 disaster_type/level/channels JSON/priority_channels） |
| 后端服务 | `backend/.../notification/NotificationService.java:144,164` | ✅ `getChannelConfigs()` / `updateChannelConfig()` 已实现 |
| 后端端点 | `backend/.../notification/NotificationController.java:75,84` | ✅ `GET /api/notification/channel-config`、`PUT /api/notification/channel-config` 已上线 |
| 前端 API | `frontend/src/services/_architecture/domain/notificationService.js:59,67` | ✅ `getChannelConfigs()` / `updateChannelConfig()` 已封装 |
| **前端页面** | `frontend/src/views/system/NotificationChannelsPage.vue:141` | ❌ **`// ── 渠道列表（mock） ──` 硬编码 5 个渠道；`testConnection`/`toggleChannel` 用 setTimeout 假操作** |

**结论**：三层链路全部就绪，唯独页面没调用。这不是"缺后端"，而是**接线断了**。修复成本远低于报告先前估计。

### 2.2 社交媒体舆情 —— ai-service 分析能力已上线，前端却全页硬编码

| 层级 | 文件 | 状态 |
|------|------|------|
| AI 服务 | `ai-service/app/routers/social_media.py` | ✅ `POST /api/ai/social-media/analyze` 已实现情感分析/告警等级/关键词/位置聚类/时间线，且响应带 `mock: bool` 标识 |
| 前端页面 | `frontend/src/views/ai/SocialMediaPage.vue:89-116` | ❌ `stats`(1247条)/`trendData`/`hotspotRegions`(龙泉驿区等)/`posts` 全硬编码；第 269/289/295 行注释"真实场景：..."但未实现 |

**特别严重**：硬编码的热点地区是成都的区县（龙泉驿区/青白江区/都江堰市），而系统其他部分默认中心点是重庆石柱（`[107.21, 30.61]`）——**假数据连地理口径都跟真实业务不一致**。

### 2.3 会签与转派 —— 服务层已封装，页面直接 import mock 绕过

| 层级 | 文件 | 状态 |
|------|------|------|
| 前端 API 层 | `services/_architecture/domain/confirmationSignService.js` | ✅ list/create/confirm/reject/transfer 全部封装好了 |
| 前端数据层 | `services/dashboardApi.js:1850+` | ✅ `listSignRecords`/`createSign`/`confirmSign`/`rejectSign`/`transferEvent` 已存在 |
| **前端页面** | `views/hazard/ConfirmationSignPage.vue:28,30,355` | ❌ **直接 `import { EXPERT_POOL } from '../../services/mock/runtime'`**，专家池永远是 5 个虚构人物（刘国梁/周晓彤等），**绕过环境变量，后端模式下也是假的** |

### 2.4 五个「已写好但零引用」的服务文件（孤岛代码）

实测引用次数（排除 `_architecture` 自身）：

| 服务文件 | 外部引用 | 语义 | 对应页面现状 |
|---------|---------|------|-------------|
| `domain/notificationService.js` | 2 个组件 | 通知/渠道配置 | 页面用 mock |
| `domain/routePlanningService.js` | **0** | 路径规划/避障 | 页面直接用 `dashboardApi.getObstacleAreas()` + mock 资源点 |
| `domain/confirmationSignService.js` | **0** | 会签/转派 | 页面直接读 mock 专家池 |
| `domain/profileService.js` | **0** | 个人资料 | 个人设置页未接入 |
| `domain/hazardMapService.js` | **0** | 灾害视口加载 | 地图页未使用 viewport 接口 |

**这 5 个文件的共同特征**：都对应一个"功能虚假"的页面。说明团队曾按架构规范写了服务层，但页面改版时**没有回头接线**。

---

## 三、功能虚假问题清单（复核确认）

### 3.1 P0 —— 任何模式下都是假数据

| # | 页面 | 路由 | 文件:行 | 假数据 | 真实能力是否已存在 |
|---|------|------|---------|--------|------------------|
| 1 | 社交媒体舆情 | `/dashboard/social-media` | `views/ai/SocialMediaPage.vue:89-116` | stats/趋势/热点/6条帖子 | ✅ ai-service 已就绪（§2.2） |
| 2 | 通知渠道配置 | `/dashboard/notification-channels` | `views/system/NotificationChannelsPage.vue:141` | 5渠道+健康状态+发送量 | ✅ 后端+API层已就绪（§2.1） |
| 3 | 会签专家池 | `/dashboard/confirmation-sign` | `views/hazard/ConfirmationSignPage.vue:30` | 5位虚构专家 | ✅ 服务层已封装（§2.3） |

### 3.2 P1 —— 高风险（假数据可能误导决策）

| # | 页面 | 文件:行 | 问题 |
|---|------|---------|------|
| 4 | 移动端指挥 | `views/mobile/MobileCommandPage.vue:53-103` | KPI/预警/待确认全硬编码；确认/驳回/视频会商均本地模拟 |
| 5 | 系统监控告警 | `views/system/SystemMonitorPage.vue:57-63` | "实时告警"是 5 条静态假告警 |
| 6 | 路径规划资源点 | `views/warning/RoutePlanningPage.vue:88` | 附近资源点为 mock 生成（龙安镇应急救援队等） |
| 7 | 资源调度坐标 | `views/warning/ResourceDispatchPage.vue:78-84` | `HAZARD_SEED` 硬编码灾害点（青龙坡滑坡等） |
| 8 | 灾害详情认领 | `views/hazard/HazardDetailPage.vue:61-73` | `claimItem()` 只改本地状态，注释"TODO: 后端实现后替换" |
| 9 | AI 洞察发布预警 | `views/ai/AiInsightPage.vue:91` | `handlePublishWarning` 仅有 notify + TODO |
| 10 | 顶部搜索框 | `layouts/DashboardLayout.vue:652,654` | 回车/点击只弹"已更新搜索条件"，无搜索逻辑 |
| 11 | 规则列表降级 | `components/rule/RuleList.vue:78-79` | API 失败时静默用 `getMockRules()`，UI 不提示 |

### 3.3 P2 —— 功能不完善 / 模拟流程

| # | 位置 | 问题 |
|---|------|------|
| 12 | `views/data/AnalyticsPage.vue:391,410,439,471` | 4 个图表（折线/堆叠柱/多线/混淆矩阵）纯 CSS div 模拟，**项目未装任何图表库**（package.json 无 echarts/chart.js） |
| 13 | `views/system/BackupRecoveryPage.vue:179-192,219` | 校验进度用 for+setTimeout 伪造；下载只弹提示 |
| 14 | `views/system/HelpCenterPage.vue:42-54` | 反馈提交 setTimeout 模拟，不落库 |
| 15 | `views/warning/WarningCreatePage.vue:60-89` | 预警模板硬编码，仅覆盖 3 个灾种 |
| 16 | `views/system/UserManagementPage.vue:42-77` | 权限矩阵硬编码 28 模块 × 7 角色 |
| 17 | `views/warning/EmergencyPage.vue:71-72` | 应急力量"11 人"/复盘"12 份"写死在模板 |
| 18 | `views/ai/ModelManagePage.vue:385-395` | 重训任务 catch 分支本地伪造 |
| 19 | `views/device/OtaManagePage.vue:91-182` | 固件/设备/任务三组 sample 数据兜底 |
| 20 | `views/system/PlanManagePage.vue:46-131` | 预案 samplePlans 兜底 |

---

## 四、代码写死问题（复核 + 量化）

### 4.1 API 基址与端口不一致（高优）

| 问题 | 涉及文件 |
|------|---------|
| `http://localhost:8080/api` 三处各自定义 fallback | `core/runtime.js`、`domain/aiApi.js`、`domain/deviceMapService.js` |
| AI 端口 8000 vs 8001 并存 | 8000：`driftMonitorService.js`、`multiModalService.js`；8001：`aiApi.js`、`sentinelService.js`、`satelliteCompareService.js` |
| AI 环境变量名三种写法 | `VITE_AI_SERVICE_URL` / `VITE_AI_SERVICE_BASE_URL` / `VITE_AI_SERVICE_BASE` |

> 注：8001 是正确端口（见项目记忆），**8000 的两处是 Bug**。

### 4.2 其它硬编码

| 类别 | 位置 | 说明 |
|------|------|------|
| 地图中心点 | `core/mapService.js:263,985`、`views/OverviewPage.vue:424`、`views/monitor/WeatherPage.vue:109` | `[107.21, 30.61]` 四处硬编码；另成都坐标 `[104.065, 30.575]` 与主口径冲突 |
| 轮询间隔 | `core/realtimeService.js` | 6 个间隔常量写死（15s/10s/30s/60s/40s/120s） |
| 导航菜单 | `data/dashboardData.js` | 约 40 个菜单项写死，未按角色动态下发 |
| 假人名/假地名 | 22 个文件命中 | 青龙坡/大岭村/龙安镇/白石沟/刘国梁/周晓彤/李晨曦/王建国 等散布在 views 与 mock |

### 4.3 dashboardApi 的 mock 兜底规模

- 总方法数约 110+，其中 **107 个**在 `VITE_USE_BACKEND=false` 或后端不可达时返回 mock；
- 写操作（create/update/delete）**40+ 个**在 mock 模式下只返回 `{ ok: true }`，不持久化；
- **风险**：后端一抖动即静默降级，界面无任何"数据非真实"提示。

### 4.4 调试代码遗留

| 文件 | 行 | 代码 |
|------|-----|------|
| `views/ai/SocialMediaPage.vue` | 295 | `console.log("Open:", post.id)` |
| `views/system/NotificationChannelsPage.vue` | 287,289 | `alert(...)` |
| `views/hazard/HazardDetailPage.vue` | 700 | `alert(...)` |

---

## 五、根因分析

1. **架构先行、页面滞后**：`_architecture` 服务层按规范写全，但页面改版后未回接（5 个孤岛服务）。
2. **mock 兜底无标识**：降级到假数据时不打"演示模式"角标，用户无法区分真假。
3. **缺少真实性门禁**：CI 只查 import 完整性与端点对账（单向），**不检查"页面是否绕过了 API 层直接写数据"**。
4. **一次性脚本结论未复核**：早前报告的部分数量（如"硬编码 60+ 处"）来自正则粗扫，需人工复核（本次已复核主要项）。
5. **图表库缺失**：`AnalyticsPage` 用 CSS 模拟图表，是因为项目根本没装图表库，属于选型遗留。

---

## 六、修复建议（按性价比排序）

### 第一批：接线即可见效（成本最低、收益最高）

| # | 动作 | 触及文件 | 预期 |
|---|------|---------|------|
| 1 | 通知渠道页改调 `notificationService.getChannelConfigs/updateChannelConfig` | `NotificationChannelsPage.vue` | P0 假功能直接转真 |
| 2 | 会签页移除 `import EXPERT_POOL`，改经 API 取专家 | `ConfirmationSignPage.vue` | P0 假功能直接转真 |
| 3 | 社媒页接入 ai-service `/api/ai/social-media/analyze`，并展示 `mock` 标识 | `SocialMediaPage.vue` + `dashboardApi.js` | P0 假功能直接转真 |
| 4 | 顶部搜索框接真实全局搜索或先隐藏 | `DashboardLayout.vue` | 消除明显断头路 |

### 第二批：补逻辑

- 灾害详情 `claimItem()` 接认领接口；AI 洞察 `handlePublishWarning` 接预警创建；
- 系统监控告警、路径规划资源点、资源调度坐标改为 API 取数；
- `RuleList` 失败时改为显示错误而非假数据。

### 第三批：治理

- 统一 API 基址/端口（由 `runtime.js` 单一导出），修正 8000 → 8001；
- 建 `src/config/`（地址、超时、阈值）与 `src/constants/`（状态、角色、灾种枚举）；
- mock 模式全局"演示模式"角标；
- 引入 ECharts 替换 CSS 模拟图表；
- **新增 CI 门禁**：扫描 `views/` 下硬编码数据数组与直接 import mock，阻止"最后一公里"复发；
- 清理 `alert`/`console.log`。

---

## 七、已知局限（诚实说明）

- 本报告为**静态代码取证**，未启动前后端做运行时验证（结论均来自文件内容与引用关系，非运行时观测）；
- "107 个 mock 方法"沿用早前报告的粗扫计数，未逐条人工确认；
- 社交媒体的真实数据源（微博/微信/Twitter API）在 ai-service 中仍是框架实现，**接入真实数据源是业务前置条件**，非前端单方面可解。

---

*报告生成 — 2026-10-03*
