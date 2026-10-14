# DisasterGuard 前端代码未实现功能审查报告

**审查范围**: `d:\Projects\DisasterGuard\frontend\src`
**审查目录**: `src/views`、`src/components`、`src/services`
**审查日期**: 2026-10-03

---

## 目录

1. [TODO / FIXME 等注释标记](#1-todo--fixme-等注释标记)
2. [UI 展示但无实际交互逻辑的功能](#2-ui-展示但无实际交互逻辑的功能)
3. [硬编码 Mock 数据 / 模拟数据](#3-硬编码-mock-数据--模拟数据)
4. [标注"真实场景"但未对接后端的功能](#4-标注真实场景但未对接后端的功能)
5. [图表 / 可视化 Placeholder（无真实图表库）](#5-图表--可视化-placeholder无真实图表库)
6. [Services 层 Mock 数据汇总（dashboardApi）](#6-services-层-mock-数据汇总dashboardapi)
7. [调试代码遗留（console.log / alert）](#7-调试代码遗留consolelog--alert)
8. [路由与页面实现对照](#8-路由与页面实现对照)

---

## 1. TODO / FIXME 等注释标记

### 1.1 AI 洞察页面 - 预警发布接口未实现

| 项目 | 详情 |
|------|------|
| **文件** | `src/views/ai/AiInsightPage.vue` |
| **行号** | 第 91 行 |
| **问题描述** | `handlePublishWarning` 函数中只有 `emit('notify', '正在创建预警...')` 和 TODO 注释，未实际调用预警创建接口 |
| **代码片段** | ```javascript
async function handlePublishWarning() {
  emit('notify', '正在创建预警...')
  // TODO: 调用预警创建接口
}
``` |
| **影响** | 用户点击"发布预警"按钮后仅显示提示，无实际效果 |

---

## 2. UI 展示但无实际交互逻辑的功能

### 2.1 顶部导航搜索框 - 无搜索逻辑

| 项目 | 详情 |
|------|------|
| **文件** | `src/layouts/DashboardLayout.vue` |
| **行号** | 第 652 行、第 654 行 |
| **问题描述** | 顶部搜索框的回车和搜索按钮点击事件只调用 `notify('已更新搜索条件')` 并关闭弹窗，没有任何实际搜索逻辑 |
| **代码片段** | ```html
<input v-model="searchTerm" autofocus 
       placeholder="搜索灾害点、任务、设备或预警" 
       @keyup.enter="() => { notify('已更新搜索条件'); showSearch = false }" />
<button class="search-submit-btn" title="搜索" 
        @click="() => { notify('已更新搜索条件'); showSearch = false }">
  <Search :size="14" />
</button>
``` |
| **影响** | 用户输入搜索词后无任何搜索结果，功能完全不可用 |

### 2.2 备份恢复 - 下载功能（占位）

| 项目 | 详情 |
|------|------|
| **文件** | `src/views/system/BackupRecoveryPage.vue` |
| **行号** | 第 219-221 行 |
| **问题描述** | `downloadBackup` 函数只显示 notify 提示"后端未连接时无效"，没有实际下载逻辑 |
| **代码片段** | ```javascript
function downloadBackup(record) {
  emit('notify', `已开始下载：${record.name}（后端未连接时无效）`)
}
``` |
| **影响** | 备份文件下载功能不可用 |

### 2.3 应急页面 - 应急力量数据硬编码

| 项目 | 详情 |
|------|------|
| **文件** | `src/views/warning/EmergencyPage.vue` |
| **行号** | 第 71-72 行 |
| **问题描述** | 应急力量（11 人）和复盘报告（12 份）数据硬编码在模板中，不从后端获取 |
| **代码片段** | ```html
<div class="stat-card">
  <div class="stat-icon blue"><UsersRound :size="19" /></div>
  <div class="stat-copy">
    <span>应急力量</span>
    <strong>11 <small>人</small></strong>
    <em class="positive">在线值守 <i>随时出动</i></em>
  </div>
</div>
``` |
| **影响** | 数据永远是固定值，无法反映真实情况 |

---

## 3. 硬编码 Mock 数据 / 模拟数据

### 3.1 移动端指挥页面 - 全部数据硬编码

| 项目 | 详情 |
|------|------|
| **文件** | `src/views/mobile/MobileCommandPage.vue` |
| **行号** | 第 53-103 行 |
| **问题描述** | KPI 数据、预警列表、待确认队列全部为硬编码的 mock 数据，loadData 函数只用 setTimeout 模拟延迟 |
| **数据项** | `kpi`（6 项指标）、`warnings`（3 条预警）、`pendingConfirm`（3 条待确认） |
| **代码片段** | ```javascript
// 1. KPI 数据（mock，真实场景由 dashboardApi 拉取）
const kpi = ref({
  pendingConfirm: 3,
  activeWarnings: 7,
  emergencyEvents: 2,
  deviceOnline: 94.2,
  unreadMessages: 12,
  pendingApprovals: 5,
});
``` |
| **影响** | 移动端页面完全是演示数据，无真实业务逻辑 |

### 3.2 预案管理页面 - 预案数据硬编码兜底

| 项目 | 详情 |
|------|------|
| **文件** | `src/views/system/PlanManagePage.vue` |
| **行号** | 第 46-131 行 |
| **问题描述** | `samplePlans`（3 条预案）和 `sampleLaunches`（3 条启动记录）为硬编码数据，API 失败时作为 fallback |
| **代码片段** | ```javascript
const samplePlans = [
  { id: "plan-001", name: "滑坡橙色预警 · 城镇撤离预案", ... },
  { id: "plan-002", name: "崩塌红色预警 · 山区抢险预案", ... },
  { id: "plan-003", name: "泥石流黄色预警 · 沟谷巡查预案", ... },
]
// ...
dashboardApi.listPlans().catch(() => ({ records: samplePlans, total: samplePlans.length })),
``` |
| **影响** | 后端不可用时显示演示数据，用户可能误以为是真实数据 |

### 3.3 预警创建页面 - 模板数据硬编码

| 项目 | 详情 |
|------|------|
| **文件** | `src/views/warning/WarningCreatePage.vue` |
| **行号** | 第 60-89 行 |
| **问题描述** | 预警模板数据硬编码在前端，只覆盖 landslide / collapse / debris_flow 三个灾种，未从后端模板库获取 |
| **代码片段** | ```javascript
const templates = [
  { id: "tpl-landslide-orange", disasterType: "landslide", level: "orange", ... },
  { id: "tpl-collapse-red", disasterType: "collapse", level: "red", ... },
  { id: "tpl-debris-yellow", disasterType: "debris_flow", level: "yellow", ... },
  { id: "tpl-custom", disasterType: "custom", level: "blue", ... },
]
``` |
| **影响** | 新增灾种（如台风、洪水等）无对应模板，模板管理功能无法联动 |

### 3.4 用户管理页面 - 权限矩阵硬编码

| 项目 | 详情 |
|------|------|
| **文件** | `src/views/system/UserManagementPage.vue` |
| **行号** | 第 42-77 行 |
| **问题描述** | 权限矩阵 `permissionMatrix` 硬编码了 28 个模块 × 7 个角色的权限对照，不从后端动态获取 |
| **代码片段** | ```javascript
const permissionMatrix = {
  '综合态势大屏': { admin: '●', system_admin: '●', ... },
  '实时监测地图': { admin: '●', system_admin: '●', ... },
  // ... 共 28 个模块
}
``` |
| **影响** | 权限配置变更时前端无法同步，需要手动改代码 |

---

## 4. 标注"真实场景"但未对接后端的功能

### 4.1 帮助中心 - 反馈提交

| 项目 | 详情 |
|------|------|
| **文件** | `src/views/system/HelpCenterPage.vue` |
| **行号** | 第 42-54 行 |
| **问题描述** | `submitFeedback` 函数用 `setTimeout` 模拟提交，未调用真实接口 |
| **代码片段** | ```javascript
async function submitFeedback() {
  // 真实场景：await dashboardApi.submitFeedback(feedback.value)
  await new Promise((r) => setTimeout(r, 800));
  feedbackSuccess.value = true;
  // ...
}
``` |
| **影响** | 用户提交的反馈不会被保存 |

### 4.2 社交媒体舆情 - 全部功能未对接

| 项目 | 详情 |
|------|------|
| **文件** | `src/views/ai/SocialMediaPage.vue` |
| **行号** | 第 269、289、294 行 |
| **问题描述** | 三处核心功能均标注"真实场景"但未实现：<br>1. 获取舆情数据（`getSocialMediaTrends`）<br>2. 转为疑似灾害（`createHazardFromSocialMedia`）<br>3. 打开原帖（`window.open(post.url)`） |
| **代码片段** | ```javascript
// 真实场景：const data = await dashboardApi.getSocialMediaTrends(filter)
await new Promise((r) => setTimeout(r, 600));
// 真实场景：await dashboardApi.createHazardFromSocialMedia(post)
router.push("/dashboard/confirmation-workbench");
// 真实场景：window.open(post.url)
console.log("Open:", post.id);
``` |
| **影响** | 社交媒体舆情模块为纯演示，无任何真实数据流转 |

### 4.3 通知渠道配置 - 测试与开关未对接

| 项目 | 详情 |
|------|------|
| **文件** | `src/views/system/NotificationChannelsPage.vue` |
| **行号** | 第 282-298 行 |
| **问题描述** | 渠道连接测试、启用/禁用切换、保存渠道均用 setTimeout 模拟，未调用后端接口 |
| **代码片段** | ```javascript
async function testConnection(channel) {
  // 真实场景：await dashboardApi.testChannel(channel)
  await new Promise((r) => setTimeout(r, 1200));
  alert(`✓ ${channel.name} 连接测试成功`);
}
async function toggleChannel(channel) {
  channel.enabled = !channel.enabled;
  // 真实场景：await dashboardApi.updateChannel(channel)
}
``` |
| **影响** | 渠道配置变更不会持久化，刷新后恢复原状 |

### 4.4 移动端指挥 - 确认与发布功能未对接

| 项目 | 详情 |
|------|------|
| **文件** | `src/views/mobile/MobileCommandPage.vue` |
| **行号** | 第 133、153、178 行 |
| **问题描述** | 三处核心操作均为本地模拟：<br>1. 加载 KPI 数据（`getMobileKpi`）<br>2. 快速确认/驳回（`confirmHazard`）<br>3. 视频会商（WebRTC） |
| **代码片段** | ```javascript
// 真实场景：const data = await dashboardApi.getMobileKpi()
await new Promise((r) => setTimeout(r, 600));
// 真实场景：await dashboardApi.confirmHazard(item.id, approve)
// 本地直接过滤数组
// 真实场景：打开视频会商 URL 或启动 WebRTC
``` |
| **影响** | 移动端所有操作都是本地模拟，不与后端同步 |

### 4.5 路径规划 - 附近资源点 Mock

| 项目 | 详情 |
|------|------|
| **文件** | `src/views/warning/RoutePlanningPage.vue` |
| **行号** | 第 88 行 |
| **问题描述** | 附近资源点数据为 mock 生成（当前避障点附近 + 默认救援队），不从后端获取 |
| **代码片段** | ```javascript
// mock：附近资源点 = 当前避障点附近 + 默认救援队
``` |
| **影响** | 路径规划的资源点不是真实可用的应急资源 |

### 4.6 备份恢复 - 校验进度模拟

| 项目 | 详情 |
|------|------|
| **文件** | `src/views/system/BackupRecoveryPage.vue` |
| **行号** | 第 179-192 行 |
| **问题描述** | 备份校验的进度条用 for 循环 + setTimeout 模拟推进，结果数据也是伪造的 |
| **代码片段** | ```javascript
// 模拟进度推进
for (let p = 10; p <= 100; p += 15) {
  await new Promise((r) => setTimeout(r, 120))
  verifyProgress.value = { ...verifyProgress.value, [record.id]: Math.min(p, 100) }
}
verifyResult.value = {
  ...verifyResult.value,
  [record.id]: {
    sha256: record.checksum ?? 'a'.repeat(64),
    recordCount: record.recordCount ?? 0,
    md5: record.md5 ?? 'b'.repeat(32),
    finishedAt: new Date().toISOString(),
  },
}
``` |
| **影响** | 备份校验结果不可信，纯演示效果 |

### 4.7 AI 模型管理 - 重训任务模拟添加

| 项目 | 详情 |
|------|------|
| **文件** | `src/views/ai/ModelManagePage.vue` |
| **行号** | 第 385-395 行 |
| **问题描述** | 模型重训任务在 catch 分支中用模拟数据添加，前端本地生成 ID |
| **代码片段** | ```javascript
// 模拟添加
retrainJobs.value.unshift({
  id: `retrain-${Date.now()}`,
  modelId: selectedDriftModel.value.id,
  modelName: selectedDriftModel.value.name,
  status: 'RUNNING',
  progress: 0,
  startedAt: new Date().toISOString(),
})
emit('notify', '重训任务已创建（模拟）')
``` |
| **影响** | 后端不可用时重训任务为本地模拟，刷新后消失 |

---

## 5. 图表 / 可视化 Placeholder（无真实图表库）

### 5.1 数据分析页面 - 4 个图表均为 CSS 模拟

| 项目 | 详情 |
|------|------|
| **文件** | `src/views/data/AnalyticsPage.vue` |
| **行号** | 第 391、410、439、471 行 |
| **问题描述** | 巡检报告区域的 4 个图表全部使用纯 CSS/div 模拟，未集成任何图表库（ECharts/Chart.js 等）：<br>1. 巡检覆盖率趋势（折线图）<br>2. 误报/漏报堆叠柱（堆叠柱图）<br>3. 模型性能趋势（多线折线图）<br>4. 一致性矩阵（混淆矩阵 3x3） |
| **代码片段** | ```html
<div class="insp-chart-placeholder line-chart-placeholder">
  <div class="insp-line-rail">
    <div v-for="(v, i) in (inspectionReport?.coverageTrend ?? [60,65,72,...])"
         :key="i" class="insp-line-node"
         :style="{ left: `${(i / 11) * 100}%`, bottom: `${v}%` }">
      <b></b>
    </div>
    <div class="insp-line-path"></div>
  </div>
</div>
``` |
| **影响** | 图表无法交互、无法响应数据变化、无动画效果，且数据为硬编码默认值 |

### 5.2 预警送达页面 - 截图占位

| 项目 | 详情 |
|------|------|
| **文件** | `src/views/warning/WarningDeliveryPage.vue` |
| **行号** | 第 538 行 |
| **问题描述** | 送达记录中的截图使用 `thumb-placeholder` 占位 div，无真实图片 |
| **代码片段** | ```html
<div class="thumb-placeholder" :title="row.screenshot ?? '截图占位'">
``` |
| **影响** | 用户无法查看预警发布时的实际截图 |

---

## 6. Services 层 Mock 数据汇总（dashboardApi）

### 6.1 概览

| 指标 | 数值 |
|------|------|
| **dashboardApi 总方法数** | 约 110+ 个 |
| **返回 mock 数据的方法数** | **107 个** |
| **文件** | `src/services/dashboardApi.js` |
| **mock 数据模块** | `src/services/mock/` 下 7 个子模块 |

### 6.2 Mock 数据模块结构

```
src/services/mock/
├── index.js          # 统一聚合入口
├── seed.js           # 种子数据（hazardPoints, tasks, devices 等）
├── empty.js          # 零值/空集占位返回（空分页、ok、空详情等）
├── ai.js             # AI 相关 mock（综合分析、设备健康、任务运行时等）
├── analytics.js      # 分析统计 mock（报表、区域看板、哨兵覆盖等）
├── workflow.js       # 工作流 mock（确认队列、消息、待办、审批等）
├── operations.js     # 运营/资源 mock（资源调度、数据源等）
├── system.js         # 系统管理 mock（用户、角色、审计、监控等）
└── runtime.js        # 运行时 mock（路径规划、设备轨迹等）
```

### 6.3 明确由 mock 兜底的核心接口分类

#### 6.3.1 AI 分析类（8 个）
| 接口 | Mock 来源 | 文件行号 |
|------|-----------|----------|
| `getAiComprehensiveAll` | `mock.ai.getAiComprehensiveAllMock` | 501 |
| `getAiComprehensiveCached` | `mock.ai.aiComprehensiveCachedMock` | 507 |
| `getAiComprehensiveRefresh` | `mock.ai.aiComprehensiveRefreshMock` | 513 |
| `getAiDeviceHealthAll` | `mock.ai.aiDeviceHealthAllMock` | 548 |
| `getAiDeviceHealthRefresh` | `mock.ai.aiDeviceHealthRefreshMock` | 560 |
| `getTaskRuntime` | `mock.ai.getTaskRuntimeMock` | 801 |
| `getTaskExecutionLog` | `mock.ai.getTaskExecutionLogMock` | 814 |
| `modelFamilyStats` | `mock.ai.modelFamilyStatsMock` | 968 |

#### 6.3.2 工作流/审批类（12+ 个）
| 接口 | Mock 来源 | 文件行号 |
|------|-----------|----------|
| `getConfirmationQueue` | `mock.workflow.getConfirmationQueueMock` | 592 |
| `getConfirmationQueueStats` | `mock.workflow.confirmationQueueStatsMock` | 597 |
| `getWarningStats` | `mock.workflow.warningStatsMock` | 642 |
| `getMessages` | `mock.workflow.getMessagesMock` | 772 |
| `getMessageStats` | `mock.workflow.messageStatsMock` | 777 |
| `listMyConfirmations` | `mock.workflow.listMyConfirmationsMock` | 844 |
| `listMyApprovals` | `mock.workflow.listMyApprovalsMock` | 849 |
| `listMyPendingWarnings` | `mock.workflow.listMyPendingWarningsMock` | 854 |
| `listMyOpenTasks` | `mock.workflow.listMyOpenTasksMock` | 859 |

#### 6.3.3 系统管理类（8+ 个）
| 接口 | Mock 来源 | 文件行号 |
|------|-----------|----------|
| `getUsers` | `mock.system.getUsersMock` | 725 |
| `getRoles` | `mock.system.getRolesMock` | 751 |
| `getAuditLogs` | `mock.system.getAuditLogsMock` | 759 |
| `getSystemMonitor` | `mock.system.getSystemMonitorMock` | 764 |
| `listModels` | `mock.system.modelsListMock` | 951 |
| `getModelDetail` | `mock.system.modelDetailMock` | 961 |

#### 6.3.4 数据分析类（6+ 个）
| 接口 | Mock 来源 | 文件行号 |
|------|-----------|----------|
| `getAnalytics` | `mock.analytics.getAnalyticsMock` | 355 |
| `getModuleMeta` | `mock.analytics.moduleMetaMock` | 360 |
| `getSentinelCoverage` | `mock.analytics.sentinelCoverageMock` | 792 |
| `getZoneSummary` | `mock.analytics.getZoneSummaryMock` | 928 |
| `getZoneRiskTrend` | `mock.analytics.getZoneRiskTrendMock` | 939 |
| `getInspectionReport` | `mock.analytics.getInspectionReportMock` | 1092 |

#### 6.3.5 写操作空成功壳（大量）
以下接口在 mock 模式下统一返回 `{ ok: true }`，无任何实际持久化：

| 分类 | 接口示例 | 数量 |
|------|----------|------|
| 用户管理 | createUser / updateUser / deleteUser | 3 |
| 设备管理 | updateDevice / deleteDevice / diagnoseDevice | 3 |
| 预警管理 | updateWarning / confirmWarning / closeWarning / dismissWarning / resolveWarning | 5 |
| 数据同步 | triggerSyncTask / pauseSyncTask / testDataSource | 3 |
| 预案管理 | listPlans / createPlan / updatePlan / deletePlan / launchPlan | 5+ |
| 报表模板 | createReportTemplate / deleteReportTemplate / runReportTemplate | 3+ |
| 通知模板 | createNoticeTemplate / updateNoticeTemplate / deleteNoticeTemplate | 3 |
| 备份恢复 | createBackup / deleteBackup / verifyBackup / restoreBackup | 4 |
| 人员管理 | createPersonnel / updatePersonnel / deletePersonnel | 3 |
| 其他 | markMessageRead / markAllMessagesRead / redeliver / decideApproval 等 | 10+ |

### 6.4 后端暂未实现的特殊标注

| 项目 | 详情 |
|------|------|
| **文件** | `src/services/_architecture/domain/aiApi.js` |
| **行号** | 第 301 行 |
| **问题描述** | AI 反馈统计接口后端暂未实现，直接调 Python 端点绕过 |
| **代码片段** | ```javascript
// 后端暂未实现 GET /ai/feedback/stats，直接调 Python 端点
``` |

---

## 7. 调试代码遗留（console.log / alert）

### 7.1 console.log 遗留

| 文件 | 行号 | 代码 | 说明 |
|------|------|------|------|
| `src/views/ai/SocialMediaPage.vue` | 295 | `console.log("Open:", post.id);` | 打开原帖功能未实现，用 console.log 代替 |

### 7.2 alert 弹窗（应替换为统一 notify）

| 文件 | 行号 | 代码 | 说明 |
|------|------|------|------|
| `src/views/system/NotificationChannelsPage.vue` | 287 | `alert(✓ ${channel.name} 连接测试成功)` | 测试成功用 alert 弹窗 |
| `src/views/system/NotificationChannelsPage.vue` | 289 | `alert(✗ 测试失败: ${e.message})` | 测试失败用 alert 弹窗 |
| `src/views/hazard/HazardDetailPage.vue` | 700 | `alert(error instanceof Error ? error.message : reason)` | 错误提示用 alert |

---

## 8. 路由与页面实现对照

### 8.1 路由完整性检查

**结论**: 路由中定义的所有页面均有对应的组件文件实现，无"路由定义了但页面不存在"的情况。

### 8.2 路由总数统计

| 分类 | 数量 |
|------|------|
| 顶级路由（欢迎页/大屏/移动端） | 3 |
| Dashboard 子路由 | 约 40+ 个 |
| 错误页路由 | 2（403、500） |
| 404 路由 | 1（内联组件） |

### 8.3 部分页面虽存在但功能不完整（详见上文）

以下页面虽已实现 UI，但核心功能未对接后端：

| 页面 | 路由 | 不完整程度 |
|------|------|------------|
| 移动端指挥 | `/mobile` | 全部数据硬编码 + 操作本地模拟 |
| 社交媒体舆情 | `/dashboard/social-media` | 全部功能未对接后端 |
| 帮助中心 | `/dashboard/help` | 反馈提交为模拟 |
| 通知渠道配置 | `/dashboard/notification-channels` | 测试/开关/保存为模拟 |
| 备份恢复 | `/dashboard/backup-recovery` | 校验进度模拟 + 下载占位 |

---

## 总结

### 问题分类统计

| 类别 | 数量 | 严重程度 |
|------|------|----------|
| TODO 注释标记 | 1 处 | 中 |
| UI 无交互逻辑 | 3 处 | 高 |
| 硬编码 Mock 数据 | 4 处 | 高 |
| "真实场景"未对接 | 7 处 | 高 |
| 图表 Placeholder | 2 处（4+个图表） | 中 |
| dashboardApi Mock 接口 | 107 个 | 高 |
| 后端暂未实现标注 | 1 处 | 中 |
| 调试代码遗留 | 4 处 | 低 |
| 路由缺失页面 | 0 处 | - |

### 核心建议

1. **高优先级**: 移动端指挥页面、社交媒体舆情页面需尽快对接后端，否则为纯演示页面
2. **高优先级**: 顶部搜索框需实现实际搜索功能或暂时隐藏入口
3. **高优先级**: 通知渠道配置的 alert 弹窗应替换为统一的 notify 组件
4. **中优先级**: Analytics 页面的 4 个 CSS 模拟图表建议接入真实图表库
5. **中优先级**: 预案管理、用户管理等页面的硬编码数据应移除，完全依赖后端 API
6. **低优先级**: 清理 console.log 和 alert 等调试代码
