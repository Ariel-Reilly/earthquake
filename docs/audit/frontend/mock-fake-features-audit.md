# 前端"功能虚假"问题全面审查报告

> 审查范围：`d:\Projects\DisasterGuard\frontend\src`
> 审查日期：2026-10-03
> 审查维度：mock 数据使用、硬编码数据、假表单提交、假 CRUD 操作、图表统计数据来源

---

## 一、总体架构说明

项目采用了 **"后端优先 + mock 降级"** 的双模式架构：
- 默认环境变量 `VITE_USE_BACKEND=true`（见 `src/.env`），强制走真实后端
- 当 `VITE_USE_BACKEND=false` 或后端不可达时，自动降级到本地 mock 数据
- mock 数据统一存放在 `src/services/mock/` 目录下，共 9 个模块

**重要前提**：虽然架构上设计了环境变量切换机制，但以下问题属于 **"页面/组件内直接硬编码数据，完全不经过 API 层"** 的情况，即使在后端模式下也会展示假数据，构成真正的"功能虚假"问题。

---

## 二、严重问题：页面直接硬编码假数据（不经过 API 层）

### 【P0 级】社交媒体舆情监测页面 — 全站假数据

| 项目 | 详情 |
|------|------|
| **文件路径** | `src/views/ai/SocialMediaPage.vue` |
| **问题描述** | 整个页面的所有数据都是前端硬编码的假数据，没有调用任何后端 API。用户看到的舆情统计、帖子列表、热力图全部是模拟数据，但界面呈现为真实功能。 |
| **涉及功能模块** | 社交媒体舆情监测 /dashboard/social-media |
| **硬编码数据清单** | 1. `stats` — 舆情统计（总数1247、正面312、中性689、负面246、热点5、疑似8）<br>2. `trendData` — 12小时热度趋势折线图数据<br>3. `hotspotRegions` — 5个热点地区及增长趋势<br>4. `posts` — 至少6条社交媒体帖子（微博/微信/Twitter），包含内容、情感、置信度、评论数、点赞数等 |
| **代码行号** | 第88-200行左右 |
| **风险等级** | 🔴 严重 — 用户以为在看真实舆情数据，实际全是假的 |

---

### 【P0 级】通知渠道配置页面 — 渠道列表全是假数据

| 项目 | 详情 |
|------|------|
| **文件路径** | `src/views/system/NotificationChannelsPage.vue` |
| **问题描述** | 渠道列表数据是前端硬编码的 mock 数据，代码注释明确标注"渠道列表（mock）"。用户看到的 5 个渠道（短信/APP推送/邮件/LED屏/Webhook）及其健康状态、发送量、成功率全部是假的。 |
| **涉及功能模块** | 通知渠道配置 /dashboard/notification-channels |
| **硬编码数据清单** | 1. `channels` — 5个渠道配置（阿里云短信、APP推送、邮件、LED大屏等）<br>2. 每个渠道包含：启用状态、优先级、健康状态、今日发送量、成功率、降级通道<br>3. `channelTypes` — 7种渠道类型定义（含字段配置） |
| **代码行号** | 第141-200行左右 |
| **风险等级** | 🔴 严重 — 用户以为配置了真实的通知渠道，实际全部是展示用的假数据 |

---

### 【P1 级】移动端指挥页面 — 全部假数据

| 项目 | 详情 |
|------|------|
| **文件路径** | `src/views/mobile/MobileCommandPage.vue` |
| **问题描述** | 整个移动端指挥页面的 KPI 数据、预警列表、待办事项全部是前端硬编码假数据。注释明确写着"KPI 数据（mock，真实场景由 dashboardApi 拉取）"。 |
| **涉及功能模块** | 移动端指挥 /mobile |
| **硬编码数据清单** | 1. `kpi` — 6项核心KPI（待确认3、活跃预警7、应急事件2、设备在线率94.2%等）<br>2. `warnings` — 预警列表（多条假预警） |
| **代码行号** | 第52-100行左右 |
| **风险等级** | 🟠 高 — 移动端作为指挥入口，展示的全是假数据，可能误导决策 |

---

### 【P1 级】系统监控页面 — 告警列表硬编码

| 项目 | 详情 |
|------|------|
| **文件路径** | `src/views/system/SystemMonitorPage.vue` |
| **问题描述** | 系统监控页面的实时告警列表是前端硬编码的 5 条假数据。虽然服务状态、API调用、存储、CPU/GPU 趋势等数据走 `dashboardApi.getSystemMonitor()`，但告警列表完全是假的。 |
| **涉及功能模块** | 系统监控 /dashboard/system-monitor |
| **硬编码数据清单** | `alerts` — 5条告警（AI Service GPU利用率高、TDengine延迟高、设备离线、OSM降级、备份完成） |
| **代码行号** | 第57-63行 |
| **风险等级** | 🟠 高 — 用户看到的"实时告警"是静态假数据，可能错过真实告警 |

---

### 【P1 级】路径规划页面 — 资源点硬编码

| 项目 | 详情 |
|------|------|
| **文件路径** | `src/views/warning/RoutePlanningPage.vue` |
| **问题描述** | 路径规划页面的"附近资源点"数据是前端硬编码的 5 个假资源点。代码注释明确写着"mock：附近资源点 = 当前避障点附近 + 默认救援队"。 |
| **涉及功能模块** | 灾区路径规划 /dashboard/route-planning |
| **硬编码数据清单** | `resources` — 5个资源点（龙安镇应急救援队、县医院急救点、武警救援中队、应急物资储备库、省第二消防队） |
| **代码行号** | 第87-95行 |
| **风险等级** | 🟠 高 — 资源调度决策依赖假数据，可能导致调度失误 |

---

### 【P1 级】资源调度页面 — 灾害点坐标硬编码

| 项目 | 详情 |
|------|------|
| **文件路径** | `src/views/warning/ResourceDispatchPage.vue` |
| **问题描述** | 资源调度页面的 `HAZARD_SEED` 灾害点列表是硬编码的假数据，`useEventLocation()` 函数直接从本地数组查找坐标，不调用后端 API。 |
| **涉及功能模块** | 资源调度指挥 /dashboard/resource-dispatch |
| **硬编码数据清单** | `HAZARD_SEED` — 5个灾害点（青龙坡滑坡、大岭村崩塌、白石沟泥石流等） |
| **代码行号** | 第78-84行 |
| **风险等级** | 🟠 高 — 路径规划起点使用假坐标，结果不可靠 |

---

### 【P2 级】OTA 升级页面 — 样例数据硬编码

| 项目 | 详情 |
|------|------|
| **文件路径** | `src/views/device/OtaManagePage.vue` |
| **问题描述** | OTA 升级页面在离线状态下使用硬编码的样例数据。虽然正常模式下应走后端 API，但样例数据（sampleFirmware、sampleDevices、sampleTasks）作为 fallback 存在，且代码中未明确区分展示。 |
| **涉及功能模块** | OTA 与边缘升级 /dashboard/devices/ota |
| **硬编码数据清单** | 1. `sampleFirmware` — 4个固件版本样例<br>2. `sampleDevices` — 6个设备样例<br>3. `sampleTasks` — 4个OTA任务样例 |
| **代码行号** | 第91-182行 |
| **风险等级** | 🟡 中 — 在后端不可达时展示假数据，用户可能误以为是真实设备 |

---

## 三、严重问题：页面直接 import mock 数据（绕过环境变量切换）

### 【P0 级】会签与转派页面 — 专家池直接使用 mock 数据

| 项目 | 详情 |
|------|------|
| **文件路径** | `src/views/hazard/ConfirmationSignPage.vue` |
| **问题描述** | 会签页面直接从 `../../services/mock/runtime` 导入 `EXPERT_POOL` 和 `SIGN_MODE_OPTIONS`，**绕过了 dashboardApi 的环境变量切换机制**。即使在后端模式下，专家池数据也永远是假的 5 个人。 |
| **涉及功能模块** | 会签与转派 /dashboard/confirmation-sign |
| **直接导入的 mock** | `EXPERT_POOL`（5位专家：刘国梁、周晓彤、高志远、韩雪清、杨思齐）<br>`SIGN_MODE_OPTIONS`（并行/串行会签选项） |
| **代码行号** | 第27-30行 |
| **风险等级** | 🔴 严重 — 会签功能的专家选择永远是假数据，无论是否连接后端 |
| **修复建议** | 通过 API 获取真实专家库列表，移除直接 import mock |

---

### 【P1 级】规则列表组件 — 失败时使用 mock 规则

| 项目 | 详情 |
|------|------|
| **文件路径** | `src/components/rule/RuleList.vue` |
| **问题描述** | 规则列表组件在 API 调用失败时，使用 `getMockRules()` 返回的示例规则数据。注释写着"使用示例数据"，但用户界面不会提示这是假数据。 |
| **涉及功能模块** | 规则引擎 /dashboard/rule-engine |
| **硬编码数据清单** | `getMockRules()` — 示例规则列表 |
| **代码行号** | 第78-79行 |
| **风险等级** | 🟠 高 — API失败时静默降级到假数据，用户无法区分 |

---

## 四、架构级问题：dashboardApi 中大量函数在 mock 模式返回假数据

以下函数在 `VITE_USE_BACKEND=false` 或后端不可达时返回 mock 假数据。
**注意**：默认 `VITE_USE_BACKEND=true`，正常部署下这些函数走真实后端。
但如果后端服务异常导致自动降级，用户看到的将是假数据且可能不知情。

### 4.1 统计分析类（mock/analytics.js）

| 函数名 | 功能 | mock 数据内容 |
|--------|------|--------------|
| `getAnalytics` | 统计分析总览 | 风险趋势、月度巡检、预警类型分布、设备趋势、热力图、风险区域（全部硬编码图表数据） |
| `getTimeRangeComparison` | 多时相对比分析 | 6个KPI对比、5个灾种分布、5个区域分布、24小时序列数据（伪随机生成） |
| `getInspectionReport` | AI 巡检报告 | 覆盖率、发现数、误报率、漏报率、趋势图、模型F1、混淆矩阵 |
| `getZoneSummary` / `getZoneRiskTrend` | 区域专项大屏 | KPI、14天风险趋势、TOP灾害、关联预警/应急 |
| `moduleMetaMock` | 模块元信息 | "地脉智巡 / 本地模拟模式" |

### 4.2 AI 相关类（mock/ai.js）

| 函数名 | 功能 | mock 数据内容 |
|--------|------|--------------|
| `getAiComprehensiveAllMock` | AI 综合分析列表 | 从 hazardPoints 派生的风险评分列表（Math.random 生成） |
| `getTaskRuntimeMock` | 任务运行时监控 | CPU/GPU/内存/状态（基于 taskId 哈希生成） |
| `getTaskExecutionLogMock` | 任务执行日志 | 8-20条日志样本（基于 taskId 哈希选取） |
| `getDisasterForecastMock` | 灾害预测 | 时序预测数据（正弦波+随机数生成） |
| `getFusionRiskMock` | 融合风险评估 | 风险等级、评分、置信度（哈希生成） |
| `getDisasterAssessmentMock` | 灾损评估 | 七维度评分、推理链、建议动作（哈希生成） |
| `runIndependentAssessmentMock` | 独立测算 | 多点评估结果（坐标哈希生成） |
| `modelFamilyStatsMock` / `trainingJobsMock` | 模型统计/训练任务 | 空骨架或零值 |

### 4.3 工作流类（mock/workflow.js）

| 函数名 | 功能 | mock 数据内容 |
|--------|------|--------------|
| `getConfirmationQueueMock` | 人工确认队列 | 2条示例确认任务 |
| `listMyConfirmationsMock` | 待我确认 | 从 hazardPoints 前8条派生 |
| `listMyApprovalsMock` | 待我审批 | 从 warnings 筛选派生 |
| `listMyPendingWarningsMock` | 待发布预警 | 从 warnings 筛选派生 |
| `listMyOpenTasksMock` | 待跟踪任务 | 任务+预警混合派生 |
| `getMessagesMock` | 消息中心 | 5条示例消息 |
| `getUnifiedEventsMock` | 统一事件中心 | 7条覆盖所有状态的示例事件 |

### 4.4 运营资源类（mock/operations.js）

| 函数名 | 功能 | mock 数据内容 |
|--------|------|--------------|
| `getResourcesMock` | 资源调度库 | 3类资源（队伍/物资/场所）各1条 |
| `getDataSourcesMock` | 数据源配置 | 6个数据源（气象/卫星/地震/地图） |

### 4.5 系统管理类（mock/system.js）

| 函数名 | 功能 | mock 数据内容 |
|--------|------|--------------|
| `getUsersMock` | 用户管理 | 5个示例用户（李晨曦、王建国、陈晓峰等） |
| `getRolesMock` | 角色管理 | 4个角色（管理员/值班员/专家/运维员） |
| `getAuditLogsMock` | 日志审计 | 5条示例操作日志 |
| `getSystemMonitorMock` | 系统监控 | 服务状态、API调用、存储、CPU/GPU趋势 |

### 4.6 运行时派生类（mock/runtime.js）

| 函数名 | 功能 | mock 数据内容 |
|--------|------|--------------|
| `getDeviceTrackMock` | 设备轨迹回放 | 20个点的圆周运动轨迹（哈希生成） |
| `planRouteMock` | 路径规划（旧版） | 最快/最安全两套方案（直线+抖动） |
| `planRoutesWithObstaclesMock` | 路径规划（含避障） | 最快/最安全/最短三套方案（正弦波+绕行） |
| `getObstacleAreasMock` | 避障区域 | 从高风险灾害点派生的多边形 |
| `previewWarningResolveMock` | 预警解除预览 | 3套模板文案 |
| `getUserPreferenceMock` | 用户通知偏好 | 默认配置（存 localStorage） |
| `listSignRecordsMock` / `createSignMock` 等 | 会签功能 | 存 localStorage 的本地假数据 |
| `getZoneKpiMock` / `getZoneTrendMock` 等 | 区域专项大屏 | KPI+趋势+TOP灾害（伪随机生成） |
| `reportPersonnelPositionMock` | 人员位置上报 | 本地回执（不上报服务器） |
| `EXPERT_POOL` | 专家池 | 5位虚构专家 |
| `PERSONNEL_DEMO` | 人员演示数据 | 5位虚构人员 |

---

## 五、表单提交/CRUD 假操作问题

### 5.1 灾害详情页 — 认领功能是假操作

| 项目 | 详情 |
|------|------|
| **文件路径** | `src/views/hazard/HazardDetailPage.vue` |
| **问题描述** | `claimItem()` 函数中，代码注释明确写着"TODO: 后端实现 POST /api/confirmation/{id}/claim 后替换此处"，当前直接修改前端本地状态，不调用任何 API。 |
| **涉及功能** | 人工确认工作台的"认领"操作 |
| **代码行号** | 第61-73行 |
| **风险等级** | 🟠 高 — 用户点击"认领"后以为成功了，实际数据未同步到后端 |

### 5.2 会签功能 — mock 模式下全是本地存储操作

| 项目 | 详情 |
|------|------|
| **文件路径** | `src/services/mock/runtime.js`（listSignRecordsMock / createSignMock / signConfirmMock / signRejectMock / transferEventMock） |
| **问题描述** | mock 模式下，会签的创建、确认、驳回、转派全部操作 localStorage，数据不持久化到后端，刷新页面后虽然还在（因为存在 localStorage），但多端不同步、无真实审批流。 |
| **涉及功能** | 会签与转派 /dashboard/confirmation-sign |
| **风险等级** | 🟠 高 — 会签是核心业务流程，假操作可能导致责任不清 |

### 5.3 通知渠道配置 — 所有操作都是前端假操作

| 项目 | 详情 |
|------|------|
| **文件路径** | `src/views/system/NotificationChannelsPage.vue` |
| **问题描述** | 渠道列表是硬编码的假数据，渠道的启用/禁用、编辑、删除、测试连接等操作全部是前端假操作，不会保存到后端。 |
| **涉及功能** | 通知渠道配置 /dashboard/notification-channels |
| **风险等级** | 🔴 严重 — 用户以为配置了通知渠道，实际预警发布时可能无法送达 |

### 5.4 个人设置 — mock 模式下修改不持久化到后端

| 项目 | 详情 |
|------|------|
| **文件路径** | `src/services/dashboardApi.js`（getProfile / updateProfile / changeMyPassword / getUserPreference / updateUserPreference） |
| **问题描述** | mock 模式下，修改个人信息、密码、通知偏好等操作：<br>- `updateProfile` 返回本地构造的成功响应<br>- `changeMyPassword` 硬编码密码 "123456" 才能通过<br>- `updateUserPreference` 存 localStorage |
| **涉及功能** | 个人中心 / 账号安全 / 通知偏好 |
| **风险等级** | 🟡 中 — mock 模式下密码修改是假的，用户可能误以为已修改 |

### 5.5 预警解除预览 — mock 模式下是假模板

| 项目 | 详情 |
|------|------|
| **文件路径** | `src/services/mock/runtime.js`（previewWarningResolveMock） |
| **问题描述** | mock 模式下，预警解除通知预览使用 3 套硬编码模板文案，不是真实生成的通知内容。 |
| **涉及功能** | 预警解除 /dashboard/warnings/:id/resolve |
| **风险等级** | 🟡 中 — 预览内容可能与实际发送内容不一致 |

---

## 六、图表/统计数据来源核查

| 页面 | 文件路径 | 数据来源 | 正常模式 | mock 模式 | 风险 |
|------|----------|----------|----------|-----------|------|
| 统计分析页 | `views/data/AnalyticsPage.vue` | `dashboardApi.getAnalytics()` | 真实 API | 硬编码图表 | 🟡 中 |
| 统计分析-巡检报告 | `views/data/AnalyticsPage.vue` | `dashboardApi.getInspectionReport()` | 真实 API | 伪随机生成 | 🟡 中 |
| 多时相对比 | `views/OverviewPage.vue` | `dashboardApi.getTimeRangeComparison()` | 真实 API | 伪随机生成 | 🟡 中 |
| 区域专项大屏 | `views/monitor/ZoneDashboardPage.vue` | `dashboardApi.getZoneSummary()` 等 | 真实 API | 伪随机生成 | 🟡 中 |
| 系统监控-趋势图 | `views/system/SystemMonitorPage.vue` | `dashboardApi.getSystemMonitor()` | 真实 API | 硬编码趋势 | 🟡 中 |
| **社交媒体-全部图表** | `views/ai/SocialMediaPage.vue` | **前端硬编码** | **假数据** | **假数据** | 🔴 严重 |
| 设备地图-轨迹 | `views/monitor/DeviceMapPage.vue` | `dashboardApi.getDeviceTrack()` | 真实 API | 哈希生成轨迹 | 🟡 中 |
| AI 模型管理-KPI | `views/ai/ModelManagePage.vue` | `dashboardApi.getModelFamilyStats()` | 真实 API | 空骨架/零值 | 🟢 低 |

---

## 七、mock 目录文件清单及被使用情况

| mock 文件 | 包含数据 | 被引用方式 | 直接使用的页面 |
|-----------|----------|-----------|---------------|
| `mock/seed.js` | 基础演示数据（灾害点/设备/预警/任务等） | dashboardApi fallback | （通过 dashboardApi 间接使用） |
| `mock/ai.js` | AI 评分/任务运行/预测/评估 | dashboardApi | （通过 dashboardApi 间接使用） |
| `mock/analytics.js` | 统计图表/对比分析/巡检报告 | dashboardApi | （通过 dashboardApi 间接使用） |
| `mock/workflow.js` | 确认队列/待办/消息/事件中心 | dashboardApi | （通过 dashboardApi 间接使用） |
| `mock/operations.js` | 资源库/数据源 | dashboardApi | （通过 dashboardApi 间接使用） |
| `mock/system.js` | 用户/角色/日志/系统监控/模型 | dashboardApi | （通过 dashboardApi 间接使用） |
| `mock/runtime.js` | 轨迹/路径/会签/偏好/区域大屏 | dashboardApi + **直接 import** | **ConfirmationSignPage.vue**（直接 import EXPERT_POOL） |
| `mock/empty.js` | 空分页/登录/上传回执等 | dashboardApi | （通过 dashboardApi 间接使用） |
| `mock/index.js` | 统一聚合入口 | dashboardApi | （通过 dashboardApi 间接使用） |

---

## 八、问题严重程度汇总

### 🔴 P0 级（严重 — 任何模式下都是假功能）

| # | 问题 | 文件 | 功能模块 |
|---|------|------|----------|
| 1 | 社交媒体舆情监测全页假数据 | `views/ai/SocialMediaPage.vue` | 社交媒体舆情 /dashboard/social-media |
| 2 | 通知渠道配置列表全是假数据 | `views/system/NotificationChannelsPage.vue` | 通知渠道 /dashboard/notification-channels |
| 3 | 会签专家池直接 import mock | `views/hazard/ConfirmationSignPage.vue` | 会签与转派 /dashboard/confirmation-sign |

### 🟠 P1 级（高 — mock 模式或特定条件下是假功能）

| # | 问题 | 文件 | 功能模块 |
|---|------|------|----------|
| 4 | 移动端指挥页全页假数据 | `views/mobile/MobileCommandPage.vue` | 移动端指挥 /mobile |
| 5 | 系统监控告警列表硬编码 | `views/system/SystemMonitorPage.vue` | 系统监控 /dashboard/system-monitor |
| 6 | 路径规划资源点硬编码 | `views/warning/RoutePlanningPage.vue` | 路径规划 /dashboard/route-planning |
| 7 | 资源调度灾害点坐标硬编码 | `views/warning/ResourceDispatchPage.vue` | 资源调度 /dashboard/resource-dispatch |
| 8 | 灾害详情认领操作是假的 | `views/hazard/HazardDetailPage.vue` | 人工确认工作台 |
| 9 | 规则列表失败时静默用假数据 | `components/rule/RuleList.vue` | 规则引擎 /dashboard/rule-engine |

### 🟡 P2 级（中 — 架构设计内的 mock 降级）

| # | 问题 | 影响范围 |
|---|------|----------|
| 10 | dashboardApi 中 50+ 函数在 mock 模式返回假数据 | 统计分析、AI 分析、工作流、系统管理、资源调度等 |
| 11 | OTA 升级样例数据 fallback | OTA 管理 /dashboard/devices/ota |
| 12 | 个人设置 mock 模式本地存储 | 个人中心/账号安全/通知偏好 |
| 13 | 会签 mock 模式 localStorage 操作 | 会签与转派 |

---

## 九、修复建议优先级

### 立即修复（P0）

1. **社交媒体舆情监测页**：接入真实社交媒体数据 API，或移除该功能入口并标注"开发中"
2. **通知渠道配置页**：接入真实渠道配置 API，或明确标注为"演示模式"
3. **会签专家池**：将 EXPERT_POOL 改为通过 API 获取真实用户/专家列表，移除直接 import mock

### 尽快修复（P1）

4. **移动端指挥页**：接入真实 API 或明确标注为演示版
5. **系统监控告警**：接入真实告警 API
6. **路径规划资源点**：通过 API 获取真实资源位置
7. **灾害详情认领**：实现认领 API 调用
8. **规则列表降级**：API 失败时显示错误提示而非静默展示假数据

### 规划修复（P2）

9. 完善所有 dashboardApi 函数的后端接口实现
10. mock 模式下增加明显的"演示模式"标识，避免用户误解
11. 统一降级策略：API 失败时显示"暂无数据"而非假数据
12. 增加环境变量外的运行时 mock 标识，在 UI 上明确提示

---

## 十、环境变量配置现状

文件：`src/.env`

```
VITE_USE_BACKEND=true
```

**当前默认是后端模式**，理论上大部分功能走真实 API。但存在以下风险：
1. 后端不可达时自动降级到 mock，用户可能不知情
2. 部分页面（如社交媒体、通知渠道）即使在后端模式下也是假数据
3. `ConfirmationSignPage.vue` 直接 import mock，不受环境变量控制

---

*报告生成完毕*
