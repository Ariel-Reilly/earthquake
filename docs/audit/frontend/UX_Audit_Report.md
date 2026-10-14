# DisasterGuard 前端 UX 问题全面审查报告

**审查范围**：`frontend/src` 目录下所有页面与组件
**审查维度**：加载状态、错误处理、空状态、交互体验、一致性、可访问性
**严重程度分级**：P0 (阻断性) / P1 (严重) / P2 (一般) / P3 (轻微)

---

## 严重程度总览

| 级别 | 数量 | 说明 |
|------|------|------|
| P0 阻断性 | 2 | 直接影响核心功能使用或用户安全 |
| P1 严重 | 8 | 显著影响用户体验，需优先修复 |
| P2 一般 | 12 | 影响体验流畅度，建议修复 |
| P3 轻微 | 7 | 细节优化，可按需排期 |

---

## P0 - 阻断性问题

### P0-1 全局无错误边界 (Error Boundary)，运行时错误导致白屏

**严重程度**：P0
**涉及文件**：
- `App.vue` - 根组件无错误捕获
- `main.js` - 应用入口无全局错误处理

**问题描述**：
Vue 3 应用未配置 `errorHandler` 全局错误处理，也没有组件级的错误边界。当任意组件渲染或生命周期钩子中抛出未捕获错误时，整个应用会白屏，用户无法感知问题也无法恢复。

**优化建议**：
```js
// main.js 中添加全局错误处理
app.config.errorHandler = (err, instance, info) => {
  console.error('[Global Error]', err, info)
  // 展示全局错误提示，并提供刷新/返回首页按钮
}
```
同时封装一个 `<ErrorBoundary>` 组件，用于包裹路由视图，局部错误不影响整体应用。

---

### P0-2 危险操作使用原生 `window.confirm`，与整体设计风格脱节且无品牌一致性

**严重程度**：P0
**涉及文件**（共 10 处）：
- `views/system/UserManagementPage.vue:168` - 删除用户
- `views/system/ReportCenterPage.vue:292` - 删除报表模板
- `views/system/PlanManagePage.vue:228` - 删除预案
- `views/system/NotificationChannelsPage.vue:318` - 删除渠道
- `views/system/BackupRecoveryPage.vue:206,315` - 删除备份/策略
- `views/system/NoticeTemplatePage.vue:237` - 删除通知模板
- `views/device/OtaManagePage.vue:278` - 删除固件
- `views/ai/AiTaskManagePage.vue:155` - 删除任务
- `components/rule/RuleList.vue:157` - 删除规则
- `views/hazard/ConfirmationWorkbenchPage.vue:147` - 确认灾害

**问题描述**：
所有删除/确认等危险操作均调用浏览器原生 `window.confirm`，弹出框样式与系统整体设计语言完全脱节，且在不同浏览器/操作系统下表现不一致，缺乏专业感。

**优化建议**：
统一封装一个 `ConfirmDialog` 组件（可复用 WarningsPage 中已有的 `close-dialog` 模式），支持：
- 自定义标题、描述、确认/取消按钮文案
- 危险操作红色强调
- 按钮 loading 状态
- ESC 关闭 / 点击遮罩关闭

---

## P1 - 严重问题

### P1-1 列表页加载状态简陋，仅有文字提示，无骨架屏

**严重程度**：P1
**涉及文件**（主要页面）：
- `views/hazard/HazardsListPage.vue:159` - "AI 监测初始化中…" 仅一行文字
- `views/warning/WarningsPage.vue:315` - "预警数据加载中..." 仅一行文字
- `views/device/DevicesPage.vue:110` - "设备数据加载中..." 仅一行文字
- `views/system/UserManagementPage.vue` - 表格内 loading
- `views/system/AuditLogPage.vue` - 表格内 loading
- `views/ai/AiTaskManagePage.vue` - 任务列表加载

**问题描述**：
大部分列表页在数据加载时只显示一行简单的文字提示，用户无法感知内容结构，加载过程中页面布局跳动大（CLS 高），体验较差。

**正面案例**：
- `views/warning/WarningDeliveryPage.vue` - 已实现完整骨架屏（skeleton-row/skeleton-card/skeleton-table）
- `views/monitor/WeatherPage.vue` - 气象卡片骨架屏（wp-card-skeleton）

**优化建议**：
1. 抽取通用 `<SkeletonList>` / `<SkeletonTable>` 组件
2. 所有列表页首屏加载时显示骨架屏，切换分页时可简化为顶部 loading 条
3. 骨架屏样式与实际内容结构对应，减少布局跳动

---

### P1-2 弹窗/抽屉不支持 ESC 键关闭

**严重程度**：P1
**涉及文件**：所有 modal / drawer 组件
- `components/FormModal.vue`
- `views/hazard/HazardsListPage.vue` - 灾害详情抽屉
- `views/warning/WarningsPage.vue` - 关闭原因弹窗
- `components/event/EventDetailDrawer.vue`
- `components/PersonnelRouteDrawer.vue`
- `views/system/ApprovalCenterPage.vue` - 审批抽屉
- `layouts/DashboardLayout.vue` - 任务创建弹窗、搜索面板等

**问题描述**：
全项目搜索未发现任何 `Escape` / `keydown.Escape` 相关的键盘事件处理。所有弹窗和抽屉都只能通过点击关闭按钮或遮罩关闭，不符合桌面端应用的键盘操作预期。

**优化建议**：
1. 在 `FormModal.vue` 等基础组件中统一封装 ESC 关闭逻辑
2. 使用 `onKeyStroke` 或原生 `keydown` 事件监听
3. 多层弹窗时只关闭最上层

---

### P1-3 搜索/筛选操作缺少防抖，频繁触发请求

**严重程度**：P1
**涉及文件**：
- `views/hazard/HazardsListPage.vue` + `composables/useHazardList.js:80` - searchTerm 变化直接 `loadData(1)`，无防抖
- `views/device/DevicesPage.vue` - 设备搜索
- `views/system/UserManagementPage.vue` - 用户筛选（filter 用 watch 触发）
- `views/system/AuditLogPage.vue:96` - 日志筛选（watch 触发 loadLogs）
- `views/ai/AiTaskManagePage.vue` - 任务筛选

**问题描述**：
除地图地址搜索（`useOverviewSearch.js` 有 280ms 防抖）外，所有列表页的搜索/筛选输入都是实时触发 API 请求，用户输入过程中会产生大量无效请求，既浪费后端资源，也可能导致请求竞态（后发先至导致结果错乱）。

**正面案例**：
- `composables/useOverviewSearch.js` - 实现了完整的状态机 + 防抖 + AbortController

**优化建议**：
1. 搜索输入框统一加 300ms 防抖
2. 下拉筛选（select）可立即触发但需 AbortController 取消上一次请求
3. 抽取通用 `useDebouncedSearch` composable

---

### P1-4 分页仅有"上一页/下一页"，不支持跳转指定页

**严重程度**：P1
**涉及文件**（所有列表页）：
- `views/hazard/HazardsListPage.vue:227-233`
- `views/warning/WarningsPage.vue:395-401`
- `views/device/DevicesPage.vue:161-167`

**问题描述**：
所有列表分页只有"上一页"和"下一页"按钮，当数据量大（如数百页）时，用户无法快速跳转到目标页，操作效率极低。

**优化建议**：
1. 分页组件增加页码数字按钮（首尾 + 当前页前后各 2 页 + ...省略号）
- 增加"跳转至第 X 页"输入框
- 显示总页数和总条数

---

### P1-5 列表页无批量操作能力

**严重程度**：P1
**涉及文件**：
- `views/hazard/HazardsListPage.vue` - 灾害列表
- `views/warning/WarningsPage.vue` - 预警列表
- `views/device/DevicesPage.vue` - 设备列表
- `views/system/UserManagementPage.vue` - 用户列表
- `views/system/AuditLogPage.vue` - 审计日志
- `views/ai/AiTaskManagePage.vue` - AI 任务列表

**问题描述**：
所有列表页均为单选模式，不支持批量选择和批量操作。当需要批量删除、批量导出、批量修改状态时，用户只能逐个操作，效率极低。

**优化建议**：
1. 为表格/列表增加复选框列，支持全选/多选
2. 顶部出现批量操作工具栏（批量删除、批量导出、批量修改状态等）
3. 批量操作前需确认弹窗

---

### P1-6 多处 catch 静默吞错，用户无感知

**严重程度**：P1
**涉及文件**：
- `composables/useHazardList.js:49` - `catch (_) { /* 忽略，AI 不可用 */ }` - AI 评分加载失败无提示
- `views/warning/WarningsPage.vue:92` - `catch (_) { aiList = [] }` - AI 综合分加载失败静默
- `views/device/DevicesPage.vue:39` - `catch (_) { aiList = [] }` - AI 健康评分加载失败静默
- `views/monitor/RealTimeMapPage.vue:93-96` - 多个 `.catch(() => [])` - 地图图层加载失败静默
- `views/hazard/ConfirmationWorkbenchPage.vue:115,121` - AI 分析和灾害详情加载失败静默

**问题描述**：
多处 API 请求失败被静默 catch，用户完全不知道数据加载失败，可能误以为"没有数据"或"数据就是这样"，导致决策依据不准确。

**优化建议**：
1. 非核心数据加载失败（如 AI 评分）：在对应区域显示"AI 评分暂不可用"占位 + 重试按钮
2. 核心数据加载失败：显示错误提示 + 重试按钮
3. 禁止使用 `catch (_) { }` 空吞错，至少记录日志

---

### P1-7 网络断开/后端不可达时用户知情度不足

**严重程度**：P1
**涉及文件**：
- `App.vue` - 运行模式指示器（左下角）
- `services/_architecture/core/httpClient.js` - HTTP 客户端

**问题描述**：
当前仅在左下角有一个微小的"运行模式指示器"（后端连接中/演示模式），当后端不可用时切换到 mock 模式，用户可能不会注意到这个细微变化，误以为看到的是真实数据。

此外，当后端服务在使用过程中断开（如网络波动），已加载的页面不会给出任何提示，用户继续操作时才会发现问题。

**优化建议**：
1. 后端首次连接失败时，在页面顶部显示醒目的 Banner 提示"当前为演示模式，数据为模拟数据"
2. 添加心跳检测，后端中途断开时弹 Toast 或 Banner 提示
3. mock 模式下的操作按钮明确标注"演示"状态，避免误导

---

### P1-8 表单缺少回车提交支持

**严重程度**：P1
**涉及文件**（大部分表单）：
- `layouts/DashboardLayout.vue` - 全局搜索框有回车但只关闭面板不搜索
- `views/WelcomePage.vue` - 登录表单
- `views/warning/WarningCreatePage.vue` - 预警创建表单
- `views/system/UserManagementPage.vue` - 用户编辑表单
- `views/ai/AiTaskManagePage.vue` - 任务创建表单
- `views/data/DataSourcesPage.vue` - 数据源编辑表单
- `components/rule/RuleEditor.vue` - 规则编辑器

**问题描述**：
大部分表单不支持回车键提交，用户填写完表单后必须移动鼠标点击"提交"按钮，操作效率低。

**正面案例**：
- `composables/useOverviewSearch.js` - 地图搜索支持回车提交
- `views/data/DataCatalogPage.vue` - 标签输入支持回车添加

**优化建议**：
1. 所有表单包裹在 `<form @submit.prevent="submit">` 中
2. 提交按钮设为 `type="submit"`
3. 登录等关键表单可监听 `@keyup.enter`

---

## P2 - 一般问题

### P2-1 空状态样式简陋，缺少插画和引导操作

**严重程度**：P2
**涉及文件**：
- `views/hazard/HazardsListPage.vue:221-226` - "当前条件下没有匹配的灾害点"，纯文字
- `views/device/DevicesPage.vue` - 无显式空状态（列表为空时空白）
- `views/system/UserManagementPage.vue:288` - "暂无用户"，纯文字
- `views/system/AuditLogPage.vue:154` - "该时间范围无操作日志"，纯文字
- `views/ai/AiTaskManagePage.vue` - 任务列表为空时无空状态

**问题描述**：
多数页面的空状态只有一行灰色文字，缺乏：
- 空状态插画/图标（降低用户焦虑感）
- 清晰的文案说明（为什么空？）
- 引导操作按钮（"去添加"、"清空筛选"等）

**正面案例**：
- `views/warning/WarningsPage.vue:365-368` - 有 Inbox 图标 + 文案
- `views/system/MessageCenterPage.vue:231` - 有空状态样式
- `views/hazard/ConfirmationWorkbenchPage.vue:295` - 有空状态样式

**优化建议**：
1. 抽取通用 `<EmptyState>` 组件，支持 icon / title / description / action 四个插槽
2. 区分"首次使用为空"和"筛选后为空"两种场景
3. 筛选后为空提供"清空筛选条件"快捷操作

---

### P2-2 按钮命名不一致（确认/提交/保存/确定混用）

**严重程度**：P2
**涉及文件**：
- `components/FormModal.vue:10` - 默认 `confirmText: '确认'`
- `views/data/DataSourcesPage.vue:251` - "保存"
- `views/settings/SettingsPage.vue:354` - "保存设置"
- `views/system/PlanManagePage.vue:498,514` - "保存" / "确认启动"
- `views/warning/WarningDeliveryPage.vue:650` - "确认重发"
- `views/warning/WarningsPage.vue:510` - "确认关闭" / "确认标记为误报" / "确认自动解除"
- `views/ai/AiTaskManagePage.vue:328` - 新建任务按钮（无显式文字，看 slot）

**问题描述**：
主要操作按钮的命名缺乏统一规范：
- 表单提交：有的叫"保存"，有的叫"确认"，有的叫"提交"
- 弹窗确认：有的叫"确认"，有的叫"确定"
- 取消按钮：有的叫"取消"，有的叫"关闭"

**优化建议**：
制定按钮命名规范：
| 场景 | 按钮文案 |
|------|----------|
| 新增表单提交 | 提交 |
| 编辑表单提交 | 保存 |
| 弹窗确认操作 | 确认 + 操作名（如"确认删除"） |
| 弹窗取消 | 取消 |
| 抽屉关闭 | 关闭 |

---

### P2-3 可访问性不足：大量图标按钮无 aria-label

**严重程度**：P2
**涉及文件**（几乎所有仅图标按钮）：
- `views/OverviewPage.vue` - 地图工具栏按钮
- `views/monitor/RealTimeMapPage.vue` - 地图控制按钮
- `layouts/DashboardLayout.vue` - 侧边栏折叠、搜索、通知等按钮
- `components/WeatherOverlayLegend.vue` - 图例切换按钮

**问题描述**：
项目中大量使用纯图标按钮（没有文字只有 lucide 图标），但绝大多数缺少 `aria-label` 属性，屏幕阅读器用户无法理解按钮功能。

**正面案例**（已正确实现）：
- `components/FormModal.vue:26` - 关闭按钮有 `aria-label="关闭"`
- `views/warning/WarningsPage.vue:474` - 关闭按钮有 `aria-label="关闭"`
- `components/BrandIcon.vue:8` - 有 `:aria-label="label"`
- `views/WelcomePage.vue:781` - 密码显示切换有 aria-label

**优化建议**：
1. 所有纯图标按钮必须添加 `aria-label`
2. 封装 `<IconButton>` 基础组件，强制要求 aria-label 属性
3. 按钮的 `title` 属性也可作为视觉用户的 hover 提示

---

### P2-4 缺少全局快捷键支持

**严重程度**：P2
**涉及文件**：全项目

**问题描述**：
系统没有任何全局快捷键支持，高频操作（如搜索、新建、刷新、切换页面）都必须用鼠标操作，重度用户效率低。

**优化建议**：
1. 添加全局快捷键：
   - `Ctrl/Cmd + K`：打开全局搜索
   - `Ctrl/Cmd + N`：新建（根据当前页面上下文）
   - `F5 / Ctrl/Cmd + R`：刷新当前列表数据
   - `Esc`：关闭弹窗/抽屉
   - `1-9`：侧边栏快捷导航（可选）
2. 在帮助中心增加快捷键说明页面

---

### P2-5 日期格式化散落在各页面，未统一封装

**严重程度**：P2
**涉及文件**（超过 15 处）：
- `views/hazard/TaskDetailPage.vue:98` - 本地 formatDate
- `views/ai/ModelManagePage.vue:294` - 本地 formatDate
- `views/system/BackupRecoveryPage.vue:408` - 本地 formatDate
- `views/settings/AccountSecurityPage.vue:312` - 本地 formatTime
- `views/system/AuditLogPage.vue:93` - 本地 timeText
- `views/hazard/HazardsListPage.vue:135` - 相对时间 fmtRelTime
- 以及更多文件中直接内联 `new Date().toLocaleString('zh-CN', { hour12: false })`

**问题描述**：
日期格式化逻辑散落在各个组件中，重复代码多，且格式不统一：
- 有的显示完整日期时间（`toLocaleString`）
- 有的只显示日期（`.slice(0, 16)` 截取）
- 有的显示相对时间（"2小时前"）
- 相对时间逻辑只在 HazardsListPage 有，其他页面没有

**优化建议**：
1. 在 `utils/` 下创建 `format.js`，统一封装：
   - `formatDateTime(iso)` - 标准日期时间
   - `formatDate(iso)` - 仅日期
   - `formatRelative(iso)` - 相对时间（x分钟前/x小时前/x天前）
2. 所有页面统一调用

---

### P2-6 按钮点击后部分缺少 disabled + loading 视觉反馈

**严重程度**：P2
**涉及文件**：
- `views/hazard/HazardsListPage.vue` - "新增"、"导出"等 PermissionButton 点击后无 loading
- `views/device/DevicesPage.vue:229-244` - "诊断"、"删除"、"AI重算"等按钮无 loading 状态
- `views/system/UserManagementPage.vue` - 用户列表操作按钮无 loading
- `layouts/DashboardLayout.vue` - 侧边栏菜单操作无 loading

**问题描述**：
部分按钮（尤其是 PermissionButton 封装的操作）点击后没有 disabled 状态和 loading 指示器，用户可能不知道操作正在进行，会重复点击导致多次提交。

**正面案例**（已正确实现）：
- `views/warning/WarningsPage.vue:509-511` - 弹窗确认按钮有 disabled + "提交中..." 文字
- `components/FormModal.vue:33-36` - 通用弹窗有 loading 状态
- `views/ai/AiInsightPage.vue:373-376` - 批量刷新按钮有 loading

**优化建议**：
1. `PermissionButton` 组件增加 `loading` prop，支持加载状态
2. 所有异步操作按钮都应在请求期间 disabled + 显示 loading 指示
3. 防止重复点击的防抖也可作为兜底

---

### P2-7 搜索结果为空时提示不够明确

**严重程度**：P2
**涉及文件**：
- `views/hazard/HazardsListPage.vue:221-226` - "当前条件下没有匹配的灾害点"
- `views/device/DevicesPage.vue` - 搜索为空时无特殊提示
- `views/system/UserManagementPage.vue` - 筛选为空时表格空行
- `views/system/AuditLogPage.vue` - "该时间范围无操作日志"

**问题描述**：
当搜索/筛选无结果时，用户无法判断是：
1. 真的没有数据
2. 搜索条件输入错误
3. 数据加载失败了

目前的提示无法帮助用户区分这些情况。

**优化建议**：
1. 搜索无结果时显示："未找到与「关键词」匹配的结果"，并提供"清空搜索条件"按钮
2. 筛选无结果时显示："当前筛选条件下无数据"，并提供"重置筛选"按钮
3. 加载失败导致为空时，显示错误状态而非空状态

---

### P2-8 地图数据加载指示器不明显

**严重程度**：P2
**涉及文件**：
- `views/OverviewPage.vue` - 主地图
- `views/monitor/RealTimeMapPage.vue:48-49` - 有 loading 和 mapError 状态

**问题描述**：
地图是页面核心组件，但加载状态只有一个全局 loading 变量，用户无法区分：
- 地图底图正在加载
- 灾害点数据正在加载
- 气象图层正在加载
- 设备数据正在加载

各图层加载时没有独立的进度指示。

**优化建议**：
1. 地图容器右上角增加加载指示器（小 spinner）
2. 各图层切换时在图层按钮上显示 loading 状态
3. 加载失败的图层显示错误标记 + 重试按钮

---

### P2-9 表单标签使用包裹式 label，部分无 for/id 关联

**严重程度**：P2
**涉及文件**（多数表单）：
- `layouts/DashboardLayout.vue:738-751` - `<label>任务名称<input /></label>` 包裹式
- `views/ai/AiTaskManagePage.vue:299-322` - 包裹式 label
- `views/data/DataCatalogPage.vue:655-706` - 包裹式 label
- `views/device/OtaManagePage.vue:585-591` - 包裹式 label

**问题描述**：
虽然包裹式 label 也能关联（点击 label 聚焦 input），但存在以下问题：
1. 屏幕阅读器对包裹式 label 的支持不如 for/id 关联方式稳定
2. 部分 label 内包含多个元素时，点击行为可能不符合预期
3. 样式控制不如独立 label 灵活

**优化建议**：
1. 优先使用 `<label for="inputId">` + `<input id="inputId">` 的显式关联
2. 复杂表单布局使用独立 label 更可控
3. 简单行内表单可保留包裹式但需确保语义正确

---

### P2-10 全局错误提示方式单一（只有 Toast）

**严重程度**：P2
**涉及文件**：
- `layouts/DashboardLayout.vue` - toast 通知系统
- 各页面通过 `emit('notify', message)` 触发

**问题描述**：
所有错误、成功、提示信息都使用同一种 Toast 样式，用户无法快速区分信息类型：
- 成功提示和错误提示视觉差异小
- 重要错误（如数据加载失败）一闪而过，用户可能没看到
- 没有错误详情展开能力

**优化建议**：
1. Toast 区分四种类型：success / error / warning / info，颜色和图标不同
2. 错误类型的 Toast 停留时间更长（如 5 秒），并提供"查看详情"
3. 严重错误使用 Alert Banner（常驻顶部）而非 Toast

---

### P2-11 表格列宽不固定，数据变化时列宽跳动

**严重程度**：P2
**涉及文件**：
- `views/system/UserManagementPage.vue` - 用户表格
- `views/system/AuditLogPage.vue` - 日志表格
- `views/system/BackupRecoveryPage.vue` - 备份表格
- `views/ai/ModelManagePage.vue` - 模型表格

**问题描述**：
表格列宽由内容自动撑开，不同页数据长度不同时列宽会变化，用户阅读时视线需要重新定位，体验不流畅。

**优化建议**：
1. 为表格各列设置固定宽度或最小宽度
2. 操作列固定宽度
3. 长内容用省略号截断，hover 显示完整内容

---

### P2-12 状态标签颜色/命名体系不完全统一

**严重程度**：P2
**涉及文件**：
- `views/hazard/HazardsListPage.vue` - 灾害等级：高风险/关注/正常（橙/黄/蓝）
- `views/warning/WarningsPage.vue` - 预警等级：红色/橙色/黄色/蓝色
- `views/device/DevicesPage.vue` - 设备状态：在线/离线（蓝/橙）
- `views/ai/ModelManagePage.vue` - 模型状态：颜色体系不同
- `views/system/BackupRecoveryPage.vue` - 备份状态：颜色体系不同

**问题描述**：
各模块的状态/等级标签虽然都用了颜色区分，但：
1. 命名体系不统一（有的用"高风险"，有的用"红色"，有的用"严重"）
2. 颜色语义不完全一致（有的模块橙色代表警告，有的代表严重）
3. 标签样式（圆角、padding、字体大小）各页面略有差异

**优化建议**：
1. 制定统一的状态标签规范：
   - 成功/正常：绿色
   - 警告/关注：黄色/橙色
   - 危险/严重：红色
   - 信息/进行中：蓝色
   - 禁用/归档：灰色
2. 封装统一的 `<StatusTag>` 组件，统一 props（type / text / color）

---

## P3 - 轻微问题

### P3-1 图片 alt 属性基本齐全，但可更具描述性

**严重程度**：P3
**涉及文件**：
- `views/monitor/SatelliteComparePage.vue` - alt="灾前"/"灾后"/"差异热力图"
- `components/BrandMascot.vue` - alt="地脉智巡品牌形象"
- `components/profile/ProfileInfo.vue:114` - alt="头像"

**问题描述**：
图片 alt 属性基本都有，但描述比较简略。对于重要的信息展示图片（如卫星对比图），alt 文本可以更具体，帮助视障用户理解内容。

**优化建议**：
1. 装饰性图片 alt 设为空字符串 `alt=""`（避免屏幕阅读器朗读无意义内容）
2. 信息性图片 alt 详细描述图片内容和含义
3. 头像图片可考虑 `alt="${用户名}的头像"` 动态生成

---

### P3-2 弹窗标题命名不统一

**严重程度**：P3
**涉及文件**：
- 有的弹窗标题是动词开头（"新建任务"、"编辑用户"）
- 有的是名词开头（"批量升级任务"、"关闭原因"）
- 有的标题带操作对象（"确认删除用户 XXX"）
- 有的标题很简短（"提示"）

**优化建议**：
统一弹窗标题命名规范：
- 创建类："新建{对象}"
- 编辑类："编辑{对象}"
- 确认类："确认{操作}"
- 详情类："{对象}详情"

---

### P3-3 缺少操作成功后的结果反馈细节

**严重程度**：P3
**涉及文件**：多数页面

**问题描述**：
大部分操作成功后只弹一句 Toast（如"已删除 XXX"），但缺少：
1. 操作后的数据变化展示（如列表自动刷新）
2. 撤销操作的入口（尤其是删除操作）
3. 跳转到相关页面的快捷入口

**优化建议**：
1. 重要操作成功后 Toast 增加"撤销"按钮（限时 5 秒）
2. 创建成功后可提供"查看详情"快捷链接
3. 删除成功后列表自动刷新并提示剩余条数

---

### P3-4 列表项 hover 状态不明显

**严重程度**：P3
**涉及文件**：
- `views/hazard/HazardsListPage.vue` - 灾害列表项
- `views/warning/WarningsPage.vue` - 预警列表项
- `views/device/DevicesPage.vue` - 设备列表项

**问题描述**：
列表项 hover 时视觉反馈较弱，用户不确定哪些区域可点击。

**优化建议**：
1. 增加 hover 时的背景色变化和轻微阴影
2. 鼠标指针改为 pointer
3. 可考虑 hover 时显示操作按钮（如查看详情、更多操作）

---

### P3-5 缺少数据刷新按钮和刷新时间显示

**严重程度**：P3
**涉及文件**：多数列表页

**问题描述**：
部分页面没有手动刷新按钮，用户想查看最新数据时只能刷新整个页面。也没有显示"数据更新于 X 分钟前"，用户不知道数据时效性。

**正面案例**：
- `views/monitor/WeatherPage.vue` - 有刷新相关逻辑
- `views/hazard/ConfirmationWorkbenchPage.vue:264` - 有刷新按钮

**优化建议**：
1. 每个列表页右上角增加刷新按钮
2. 显示"最后更新：X 分钟前"
3. 数据自动刷新时给出轻量提示

---

### P3-6 长列表无虚拟滚动，大数据量下性能问题

**严重程度**：P3
**涉及文件**：所有列表页

**问题描述**：
当前列表全部渲染 DOM，当数据量大（如数千条设备/灾害点）时会出现性能问题。虽然分页机制缓解了这个问题，但"查看全部"场景下仍可能一次性渲染大量 DOM。

**优化建议**：
1. 引入虚拟滚动（如 vue-virtual-scroller）
2. 或者限制单页最大条数，强制分页
3. 无限滚动加载作为替代方案

---

### P3-7 移动端适配不完善

**严重程度**：P3
**涉及文件**：Dashboard 下所有页面

**问题描述**：
系统主要面向桌面端设计，但在平板或小屏笔记本上：
- 侧边栏占用空间过大
- 双栏布局（列表+详情）空间紧张
- 表格横向滚动体验差

**优化建议**：
1. 平板尺寸下默认收起侧边栏
- 小屏下双栏改为单栏 + 抽屉式详情
- 表格支持卡片模式切换

---

## 修复优先级建议

### 第一阶段（P0 + P1 核心体验）
1. 全局错误边界 + 统一错误处理
2. 统一确认对话框组件（替换 window.confirm）
3. ESC 键关闭弹窗/抽屉
4. 搜索防抖 + AbortController
5. 列表页骨架屏
6. 分页组件升级（页码 + 跳转）

### 第二阶段（P2 体验优化）
7. 空状态组件统一化
8. 按钮命名规范 + 按钮 loading 状态
9. 可访问性提升（aria-label 等）
10. 日期格式化工具函数统一
11. 批量操作能力
12. 全局快捷键支持

### 第三阶段（P3 细节打磨）
13. 状态标签组件统一
14. 表格列宽规范化
15. 操作成功反馈增强
16. 刷新按钮 + 更新时间
17. 移动端适配优化

---

## 好的实践（值得保持和推广）

1. **地图搜索** (`useOverviewSearch.js`)：完整的状态机 + 防抖 + AbortController，是搜索功能的最佳实践范本
2. **预警关闭弹窗** (`WarningsPage.vue`)：有预设选项 + 输入框 + 错误提示 + loading 状态，交互完善
3. **权限按钮** (`PermissionButton.vue`)：统一封装权限控制，disabled 状态有视觉反馈
4. **运行模式指示器** (`App.vue`)：提示当前是后端还是 mock 模式，设计贴心
5. **骨架屏** (`WarningDeliveryPage.vue`)：已实现的骨架屏结构清晰，可作为范本
6. **天气卡片骨架屏** (`WeatherPage.vue`)：按卡片粒度显示骨架屏

---

*报告生成时间：2026-10-03*
*审查范围：frontend/src 目录下 40+ 个页面组件和 20+ 个通用组件*
