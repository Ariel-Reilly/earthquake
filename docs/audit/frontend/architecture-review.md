# 前端架构与代码质量审查报告

> 审查范围：`frontend/src/` 目录
> 审查日期：2026-10-03
> 项目：DisasterGuard 地脉智巡 - 前端

---

## 总览

本次审查覆盖 6 大维度，共发现 **18 项**架构/代码质量问题，按严重程度分为三档：

| 严重程度 | 数量 | 典型问题 |
|---------|------|---------|
| 🔴 严重 (P0/P1) | 6 | 服务层架构分裂、上帝服务文件、auth store 职责过载 |
| 🟡 中等 (P2) | 7 | 组件复用率低、composables 利用不足、地图逻辑分散 |
| 🟢 轻微 (P3) | 5 | 目录结构缺项、类型安全缺失、格式不统一 |

---

## 🔴 严重问题 (P0 / P1)

### P0-1 服务层架构分裂：两套模式并存，迁移状态不明确

**严重程度**：P0  
**涉及文件**：
- `services/dashboardApi.js`（1990 行，上帝对象）
- `services/aiApi.js` ~ `services/weatherService.js`（根目录 11 个文件）
- `services/_architecture/core/`（8 个核心模块）
- `services/_architecture/domain/`（13 个领域服务）

**问题描述**：

services 目录下存在两套完全不同的架构模式：

1. **旧架构（dashboardApi 上帝对象）**：
   - `dashboardApi.js` 一个文件包含了几乎所有业务的 API 调用（灾害、设备、预警、应急、用户、审批、报表、备份、OTA、通知模板、预案、数据目录、路径规划...）
   - 直接 import mock 数据，mock 与真实接口耦合在同一个文件
   - 每个方法内部手动判断 `USE_BACKEND` 走真实接口或 mock

2. **新架构（_architecture 分层）**：
   - core 层：httpClient、runtime、errors、realtimeService、mapService、healthService、geocodeService、sentinelService
   - domain 层：按领域拆分的 13 个服务文件
   - 设计更清晰，职责分离

3. **过渡层（根目录 re-export）**：
   - 根目录 11 个服务文件中，有 11 个是纯 `export * from './_architecture/...'` 的转发
   - 但 `dashboardApi.js` 不在其中，它是独立的大文件，没有被 _architecture 取代

**风险**：
- 新人无法判断应该 import 哪个服务，两套 API 调用风格不一致
- dashboardApi 持续膨胀，已达 1990 行且仍在增长
- mock 数据与真实接口耦合，无法独立替换
- 状态不清晰：是"正在迁移"还是"永久共存"？没有明确的迁移计划

**优化建议**：
1. 明确架构决策：全面迁移到 _architecture 分层架构
2. 将 dashboardApi.js 按领域拆分到 `_architecture/domain/` 下：
   - `hazardService.js`（灾害点 CRUD + 列表 + 视口查询）
   - `deviceService.js`（设备 CRUD + 诊断 + OTA）
   - `warningService.js`（预警 CRUD + 状态流转 + 投递）
   - `emergencyService.js`（应急事件 + 资源调度）
   - `userService.js`（用户 + 角色 + 审计）
   - `systemService.js`（系统监控 + 备份 + 同步）
   - `workflowService.js`（确认 + 审批 + 待办 + 消息）
   - `reportService.js`（报表 + 导出）
   - `inspectionService.js`（巡检 + 现场核查）
3. 迁移完成后删除 dashboardApi.js，根目录仅保留 index.js 统一导出

---

### P0-2 _architecture 内部不一致：aiApi.js 未使用统一 httpClient

**严重程度**：P0  
**涉及文件**：
- `services/_architecture/domain/aiApi.js`（343 行）
- `services/_architecture/core/httpClient.js`（112 行）

**问题描述**：

`_architecture/domain/aiApi.js` 没有使用 `_architecture/core/httpClient.js` 提供的统一 `request()` 函数，而是自己实现了一套完整的 fetch 封装：

```js
// aiApi.js 内的重复实现
async function request(path, options = {}) {
  const token = getAuthToken();
  const headers = { "Content-Type": "application/json", ... };
  const resp = await fetch(`${API_BASE}${path}`, { ...options, headers });
  // ...错误处理
}
```

同时：
- 直接 import `getAuthToken` from `../../../stores/auth.js`（跨越三层目录）
- 错误处理与 httpClient.js 不一致（没有 401 自动重定向、没有 createApiError 归一化）
- `API_BASE` 重复定义，没有使用 runtime.js 的统一配置
- `getFeedbackStats()` 方法甚至直接绕过后端网关，直连 Python AI 服务

**风险**：
- 认证逻辑不一致，某些接口可能漏带 token
- 401 处理不一致，AI 相关接口过期时不会自动跳登录
- 错误类型不统一，调用方需要做两套错误处理
- 维护成本翻倍，修改请求逻辑需要改两处

**优化建议**：
1. aiApi.js 全面改用 `_architecture/core/httpClient.js` 的 `request()`
2. 直连 Python 的 `getFeedbackStats()` 应通过后端网关代理
3. 所有 domain 服务统一走 httpClient，禁止直接调用 fetch

---

### P1-1 auth.js 职责过载：认证 + 权限 + 路由守卫 + JWT 解析 + 重定向

**严重程度**：P1  
**涉及文件**：`stores/auth.js`（392 行）

**问题描述**：

auth.js 一个文件承担了至少 7 种职责：

| 职责 | 代码位置 | 是否应属于 store |
|------|---------|----------------|
| 认证状态存储（token/user） | 288-323 行 | 是 |
| 角色定义（7 种角色 + 描述） | 18-26 行 | 否（静态配置） |
| 角色-菜单映射（7 角色 × 数十路径） | 50-140 行 | 否（权限配置） |
| 角色-权限映射（7 角色 × 上百权限点） | 142-272 行 | 否（权限配置） |
| 角色名规范化函数 | 45-48 行 | 否（工具函数） |
| JWT 过期检查（base64 解码） | 369-382 行 | 否（认证工具） |
| 登录重定向 + 防重入锁 | 385-392 行 | 否（路由/导航逻辑） |
| 兼容层函数（10+ 个 deprecated 函数） | 325-361 行 | 否（过渡代码） |

**具体问题**：
1. **静态配置混入 store**：ROLE_OPTIONS、ROLE_MENU_MAP、ROLE_PERMISSION_MAP 是纯静态数据，不随运行时变化，不应放在 store 文件中
2. **权限判断逻辑在 store**：`hasPermission()`、`canAccessPath()` 是权限计算逻辑，不是状态变更
3. **JWT 解析在 store**：`isTokenExpired()` 是纯函数，应放在 utils 或 auth 工具模块
4. **导航逻辑在 store**：`redirectToLogin()` 直接操作 `window.location`，属于副作用
5. **兼容层代码量大**：deprecated 函数占了约 40 行，注释说"Step 8 移除"，但没有明确的清理计划

**风险**：
- 文件持续膨胀，难以维护
- 静态配置和运行时状态混在一起，修改角色配置可能意外影响运行时逻辑
- 权限逻辑散落在 store，无法在非组件环境（如 service 层）复用

**优化建议**：
1. 拆分为 4 个模块：
   - `constants/roles.js`：ROLE_OPTIONS、ROLE_MENU_MAP、ROLE_PERMISSION_MAP、normalizeRole
   - `stores/auth.js`：仅保留 token/user 状态 + setSession/clearSession 等核心 action
   - `utils/auth.js`：isTokenExpired、getAuthHeaders 等工具函数
   - `utils/permissions.js`：hasPermission、canAccessPath 等权限判断

2. 路由守卫逻辑移到 `router/guards.js`
3. 制定兼容层清理时间表，在确认无调用后删除 deprecated 函数

---

### P1-2 dashboardApi.js 上帝服务：1990 行，60+ 个接口，跨 15+ 业务域

**严重程度**：P1  
**涉及文件**：`services/dashboardApi.js`（1990 行）

**问题描述**：

dashboardApi.js 是一个巨型服务对象，包含了以下业务域的 API 调用：

| 业务域 | 方法数 | 估算行数 |
|-------|-------|---------|
| 灾害点管理 | 7 | ~70 |
| 巡检任务 | 7 | ~60 |
| 设备管理 | 8 | ~70 |
| 预警管理 | 9 | ~80 |
| 应急事件 | 3 | ~30 |
| 人员定位 | 2 | ~20 |
| AI 综合分析 | 6 | ~60 |
| AI 设备健康 | 4 | ~40 |
| 确认/会签工作流 | 6 | ~50 |
| 资源调度 | 2 | ~20 |
| 数据源/同步 | 8 | ~70 |
| 用户/角色/审计 | 6 | ~50 |
| 消息/待办/审批 | 8 | ~70 |
| OTA 升级 | 7 | ~60 |
| 通知模板/预案 | 8 | ~70 |
| 备份恢复 | 9 | ~90 |
| 报表中心 | 7 | ~70 |
| 数据资产目录 | 6 | ~60 |
| 路径规划 | 3 | ~30 |
| 个人资料/偏好 | 7 | ~60 |
| 统一事件中心 | 7 | ~70 |
| 灾害预测/评估 | 5 | ~50 |
| 其他（登录、附件、GPS、区域、对比...） | ~15 | ~150 |

**总计约 60+ 个方法，1990 行代码**

此外还有大量附带逻辑：
- 分页归一化函数 `normalizePaged`
- 兜底数据源 `fallback()`
- mock 数据 import
- AI 分数注入辅助函数 `attachAiScores`、`attachAiDeviceHealth`
- 状态机计算 `computeAvailableTriggers`、`getStateMachineGraph`
- 响应延迟模拟 `response()`
- 区域数据聚合 `getZoneSummary`（内部并行调用 5 个接口）

**风险**：
- 单文件过大，可读性差，查找困难
- 修改任何业务的 API 都可能影响其他业务（git 冲突风险高）
- mock 与真实接口耦合，无法独立测试
- 无法按业务域做按需加载（tree-shaking 失效）

**优化建议**：
按领域拆分为独立服务文件，参考 `_architecture/domain/` 的模式，将 dashboardApi 的内容逐步迁移过去。

---

### P1-3 列表页 + 详情模式重复：3 个页面各自实现一套

**严重程度**：P1  
**涉及文件**：
- `views/hazard/HazardsListPage.vue`
- `views/warning/WarningsPage.vue`
- `views/device/DevicesPage.vue`

**问题描述**：

三个列表页都实现了几乎相同的模式：

```
统计卡片 (stats-grid + stat-card × 4)
    ↓
面板 + 列表 (panel + warning-list + warning-item)
    ↓
选中项 → 跳转详情页 (router.push)
    ↓
分页 (paging ref + changePage 函数)
    ↓
加载状态 (loading ref)
```

重复的具体模式：
1. **统计卡片**：3 个页面各有 3-4 张 stat-card，结构完全一致（图标 + 标题 + 数值 + 描述）
2. **列表项结构**：warning-item 模式（左侧色块/图标 + 标题行 + 描述行）
3. **分页逻辑**：paging ref + changePage(step) 函数，写法几乎一样
4. **加载状态**：loading ref + v-if 加载中提示
5. **AI 分数合并**：每个页面都先拉 AI 数据，再 map 合并到列表项
6. **选中项详情跳转**：selectedId ref + openDetail 函数

仅 HazardsListPage 抽取了 `useHazardList` composable，其他两个页面完全写在组件内。

**风险**：
- 修改列表样式需要改 3 个地方
- 分页 bug 修复需要同步到 3 个文件
- 新增列表页时又会复制一份，雪球越滚越大

**优化建议**：
1. 抽取通用组件：
   - `components/common/StatCard.vue` - 统计卡片
   - `components/common/StatsGrid.vue` - 统计卡片网格
   - `components/common/DataList.vue` - 通用列表容器
   - `components/common/PagePagination.vue` - 分页器
2. 抽取通用 composable：
   - `composables/useDataList.js` - 列表数据 + 分页 + 加载状态 + 选中项
3. 参考 useHazardList 的模式，推广到所有列表页

---

### P1-4 地图相关逻辑分散在 6 个页面，无统一封装

**严重程度**：P1  
**涉及文件**：
- `views/OverviewPage.vue`（综合态势地图）
- `views/monitor/RealTimeMapPage.vue`（实时监测地图）
- `views/monitor/DeviceMapPage.vue`（设备地图）
- `views/monitor/WeatherPage.vue`（气象地图）
- `views/monitor/SatelliteComparePage.vue`（时相对比）
- `views/warning/ResourceDispatchPage.vue`（资源调度地图）
- `views/screen/CommandScreenPage.vue`（指挥大屏）

**问题描述**：

6+ 个页面都直接操作 OpenLayers（或其他地图库），每个页面都有自己的：
- 地图初始化逻辑（创建 Map、View、layers）
- 点位渲染逻辑（灾害点/设备点/人员点）
- 弹窗/tooltip 逻辑
- 图层切换逻辑
- 缩放/定位工具
- 气象图层叠加
- 搜索 + 选点 + 反向地理编码

虽然 `services/_architecture/core/mapService.js` 做了一些封装，但覆盖面有限。
`useOverviewSearch` 只解决了搜索一个场景。

**风险**：
- 地图库升级时需要逐个修改 6+ 页面
- 地图相关 bug 可能在某些页面存在但其他页面没有（不一致性）
- 每个地图页面代码量都很大（OverviewPage 可能超过 1000 行）
- 新人上手地图功能成本高

**优化建议**：
1. 建立统一的地图 composable 体系：
   - `composables/useMap.js` - 地图初始化、销毁、resize
   - `composables/useMapMarkers.js` - 点位渲染与交互
   - `composables/useMapLayers.js` - 图层管理与切换
   - `composables/useMapWeather.js` - 气象图层叠加
2. 抽取通用地图组件：
   - `components/map/BaseMap.vue` - 基础地图容器
   - `components/map/MapSearchBox.vue` - 搜索框
   - `components/map/MapLayerControl.vue` - 图层控制器
   - `components/map/MapLegend.vue` - 图例
3. 地图相关逻辑从 views 下移到 composables 和 components

---

## 🟡 中等问题 (P2)

### P2-1 通用组件严重不足，components 以业务组件为主

**严重程度**：P2  
**涉及文件**：`components/` 目录

**问题描述**：

components 目录下共 29 个组件，分类如下：

| 类别 | 数量 | 组件 |
|------|------|------|
| 业务组件（特定页面用） | 20+ | EventDetailDrawer、RuleEditor、SignForm、ChannelMatrix、RouteForm 等 |
| 通用基础组件 | 3 | FormModal、PermissionButton、BrandIcon |
| 视觉/动效组件 | 5 | AnimatedDashboardBackground、AnimatedLoginBackground 等 |
| 其他 | 2-3 | AiAssistant、ServiceHealthBanner 等 |

**缺少的通用组件**：
- ❌ DataTable / 数据表格组件
- ❌ Pagination / 分页组件
- ❌ StatCard / 统计卡片
- ❌ StatusTag / 状态标签（预警等级、设备状态、灾害等级等）
- ❌ SearchBar / 搜索栏
- ❌ EmptyState / 空状态
- ❌ LoadingSpinner / 加载指示器
- ❌ ConfirmDialog / 确认对话框
- ❌ Drawer / 抽屉（详情抽屉模式在多处重复）
- ❌ Tabs / 标签页
- ❌ Badge / 徽标
- ❌ Progress / 进度条

**风险**：
- UI 不一致：同一个"状态标签"在不同页面样式可能不同
- 重复劳动：每个新页面都要从头写基础组件
- 维护成本高：修改基础样式需要改很多地方

**优化建议**：
1. 建立 `components/common/` 目录，存放通用基础组件
2. 优先抽取使用率最高的 5 个：StatCard、StatusTag、Pagination、SearchBar、Drawer
3. 制定组件规范：Props 命名、事件命名、样式约定

---

### P2-2 composables 利用严重不足，大量逻辑写在组件内

**严重程度**：P2  
**涉及文件**：`composables/` 目录（仅 3 个文件）

**问题描述**：

composables 目录只有 3 个文件：
1. `useBodyScrollLock.js` - 滚动锁定
2. `useHazardList.js` - 灾害列表逻辑
3. `useOverviewSearch.js` - 总览页搜索

对于一个 45+ 页面的项目来说，3 个 composable 远远不够。

**应该抽取但还没抽取的逻辑**：

| 逻辑 | 重复出现的位置 | 建议 composable |
|------|--------------|----------------|
| 列表数据 + 分页 + 加载 | HazardsListPage / WarningsPage / DevicesPage 等 | `useDataList.js` |
| 地图初始化 + 图层管理 | 6+ 个地图页面 | `useMap.js` 系列 |
| 实时数据订阅 | 多个页面都有 onRealtimeUpdate | `useRealtime.js` |
| 表单验证与提交 | 新建/编辑表单类页面 | `useForm.js` |
| 导出功能 | 多个列表页的导出按钮 | `useExport.js` |
| 确认对话框 | 删除、关闭等操作 | `useConfirm.js` |
| 分页逻辑 | 至少 3 个列表页 | `usePagination.js` |
| 倒计时/相对时间 | 多个页面的 fmtRelTime | `useRelativeTime.js` |

**风险**：
- 组件内逻辑越来越复杂，单文件体积膨胀
- 相同逻辑的 bug 修复需要同步多个文件
- 单元测试困难（逻辑和视图耦合）

**优化建议**：
1. 优先抽取 `useDataList`、`usePagination`、`useConfirm` 三个高频复用逻辑
2. 新建组件/页面时，优先考虑逻辑是否可复用
3. 建立 composables 的命名和组织规范

---

### P2-3 状态管理分散：部分共享状态散落在组件和 service 中

**严重程度**：P2  
**涉及文件**：
- `stores/auth.js`
- `stores/realtime.js`
- `stores/userPreference.js`
- 各 views 页面

**问题描述**：

Pinia store 只有 3 个，大量本应提升到 store 的状态散落在各处：

| 状态 | 当前位置 | 是否应该在 store | 理由 |
|------|---------|----------------|------|
| 灾害列表数据 | HazardsListPage 组件内 | ❌ 不一定 | 单页面使用，不需要全局 |
| 实时通道状态 | realtime store | ✅ 已在 store | 多页面共享 |
| 用户偏好 | userPreference store | ✅ 已在 store | 全局共享 + 持久化 |
| 通知/消息未读数 | MessageCenterPage 内 | ⚠️ 应该提升 | topbar 也需要展示红点 |
| 待办数量 | TodoCenterPage 内 | ⚠️ 应该提升 | topbar 也需要展示 |
| 侧边栏折叠状态 | DashboardLayout 内 | ⚠️ 应该提升 | 持久化 + 多组件共享 |
| 当前选中的灾害点/设备 | 各列表页内 | ❌ 不一定 | 单页面内状态 |
| AI 健康状态 | realtime store 内 | ⚠️ 位置存疑 | 放在 realtime 里有点奇怪 |

store 的其他问题：
1. **auth store 没有 reset 方法**：登出时需要手动清理每个字段
2. **持久化机制不统一**：auth 自己手写 localStorage，userPreference 也自己手写，realtime 没有持久化
3. **缺少 $reset 支持**：Pinia 内置的 `$reset` 无法重置嵌套对象（如 aiHealth）

**优化建议**：
1. 新增 `ui.js` store，管理侧边栏折叠、主题等 UI 状态
2. 新增 `notifications.js` store，管理未读消息数、待办数等
3. 使用 `pinia-plugin-persistedstate` 统一持久化，取代手写 localStorage
4. 为所有 store 添加 reset action

---

### P2-4 目录结构缺项：缺少 constants/、config/、hooks/ 等常见目录

**严重程度**：P2  
**涉及文件**：`src/` 根目录结构

**问题描述**：

当前目录结构：
```
src/
├── components/      # 组件
├── composables/     # 组合式函数
├── data/            # 静态数据（仅 dashboardData.js）
├── layouts/         # 布局
├── router/          # 路由
├── services/        # 服务层
├── stores/          # 状态管理
├── utils/           # 工具函数
├── views/           # 页面
├── App.vue
├── main.js
└── styles.css
```

**缺少的常见目录**：
- ❌ `constants/` - 常量定义（目前角色常量在 auth.js，菜单配置在 data/dashboardData.js，灾害类型在 utils/hazardTypes.js）
- ❌ `config/` - 配置文件（环境变量读取、功能开关等）
- ❌ `directives/` - 自定义指令（权限指令等）
- ❌ `plugins/` - Vue 插件安装
- ❌ `assets/` - 静态资源（图片、字体等，可能在项目根目录的 public/ 下）
- ❌ `styles/` - 样式文件目录（styles.css 直接在根目录）

**当前目录结构不合理的地方**：
1. `data/dashboardData.js` 实际是菜单配置 + 模块元数据，应归为 constants 或 config
2. `utils/hazardTypes.js` 是常量定义，不是工具函数
3. `utils/query.js` 只有一个 withQuery 函数，可以合并到更通用的 utils
4. views 下部分子目录组织合理（ai/、hazard/、warning/ 等），但根目录直接放了 4 个页面文件（OverviewPage、WelcomePage、ForbiddenPage、ServerErrorPage）

**优化建议**：
```
src/
├── assets/          # 静态资源（图片、图标）
├── components/      # 组件
│   ├── common/      # 通用基础组件
│   ├── business/    # 业务组件
│   └── map/         # 地图相关组件
├── composables/     # 组合式函数
├── constants/       # 常量定义
│   ├── roles.js     # 角色/权限配置
│   ├── menu.js      # 菜单配置（从 data 迁移）
│   └── hazardTypes.js
├── config/          # 配置
│   └── index.js     # 环境变量统一读取
├── directives/      # 自定义指令
├── layouts/         # 布局
├── router/          # 路由
│   ├── index.js
│   └── guards.js    # 路由守卫（从 auth.js 迁移）
├── services/        # 服务层（统一到 _architecture 模式）
├── stores/          # 状态管理
├── styles/          # 样式
│   ├── index.css
│   ├── variables.css
│   └── reset.css
├── utils/           # 工具函数
├── views/           # 页面
│   └── common/      # 通用页面（403、404、500、Welcome）
├── App.vue
└── main.js
```

---

### P2-5 根目录服务文件与 _architecture 目录命名混乱

**严重程度**：P2  
**涉及文件**：`services/` 目录

**问题描述**：

1. **_architecture 目录名不规范**：以下划线开头暗示"内部/私有"，但它实际上是新架构的核心目录，所有服务最终都应该迁移到这里
2. **根目录文件角色不明确**：11 个根目录文件中，11 个是 re-export，1 个是独立大文件（dashboardApi.js），新人无法一眼判断哪些是"新架构"哪些是"旧架构"
3. **缺少统一入口**：没有 `services/index.js` 统一导出所有服务

**优化建议**：
1. 将 `_architecture/core/` 提升为 `services/core/`
2. 将 `_architecture/domain/` 提升为 `services/domain/`
3. 删除根目录的 re-export 文件，直接从 core/ 和 domain/ import
4. 或者保留根目录的 index.js 作为统一入口
5. mock/ 目录移到 services 下更合理的位置（如 `services/mock/`）

---

### P2-6 weatherService.js 业务逻辑过重，包含大量数据探测和缓存逻辑

**严重程度**：P2  
**涉及文件**：`services/_architecture/domain/weatherService.js`（463 行）

**问题描述**：

weatherService.js 不只是一个 API 服务层，它包含了大量业务逻辑：
- NSMC XML 清单解析（fetchXml + querySelector）
- NMC 产品图时间探测（96 次循环 + Image() 预加载探测）
- CORS 代理降级链（4 个代理依次尝试）
- 前端内存缓存（Map + TTL）
- 后端代理模式切换（USE_BACKEND 判断 + 降级）
- 图例配置（WEATHER_LEGENDS）
- 地理范围常量（EXTENT_*）
- 产品 URL 构建（buildNmcProductUrl switch-case）

**问题**：
1. 数据探测逻辑属于业务逻辑，不应放在 service 层
2. CORS 代理链是基础设施关注点
3. 图例和范围是配置/常量，不应混在 service 里
4. 缓存策略可以更通用

**优化建议**：
1. 图例和范围常量移到 `constants/weather.js`
2. CORS 代理逻辑封装到 `utils/corsProxy.js`
3. 通用缓存封装到 `utils/cache.js` 或 `services/core/cacheService.js`
4. 产品探测逻辑可以保留在 weatherService，但需要更清晰的分层

---

### P2-7 utils 目录组织松散，职责边界不清晰

**严重程度**：P2  
**涉及文件**：`utils/` 目录

**问题描述**：

utils 目录有 6 个文件：
- `coordTransform.js` - 坐标系转换（WGS-84 ↔ GCJ-02）
- `geo.js` - 距离计算 + 格式化
- `hazardTypes.js` - 灾害类型常量 + 归一化
- `query.js` - URL 查询字符串构建
- `safeMinMax.js` - 安全的最值查找

问题：
1. `hazardTypes.js` 是常量 + 领域相关函数，不是通用工具，应放在 constants/ 或 domain 相关目录
2. `coordTransform.js` 和 `geo.js` 都是地理相关，可以合并或放在 `utils/geo/` 子目录
3. 缺少很多常见工具：日期格式化、防抖节流、深拷贝、类型判断等
4. 没有 index.js 统一导出

**优化建议**：
1. `hazardTypes.js` → `constants/hazardTypes.js`
2. `coordTransform.js` + `geo.js` → `utils/geo/` 目录
3. 补充常用工具函数（date、format、validate 等）
4. 增加 `utils/index.js` 统一导出

---

## 🟢 轻微问题 (P3)

### P3-1 纯 JS 项目，无类型安全保障

**严重程度**：P3  
**涉及范围**：整个前端项目

**问题描述**：

项目使用纯 JavaScript，没有 TypeScript 类型检查：
- 组件 Props 只有运行时的 type 校验，没有编译时类型检查
- 接口返回数据无类型定义，全靠 JSDoc 注释
- Store state 无类型定义
- 函数参数和返回值无类型约束

**具体风险点**：
1. `dashboardApi.js` 的每个方法都靠 JSDoc 注释描述返回类型，但没有编译器检查
2. 灾害/预警/设备等 DTO 没有统一的类型定义，各页面自己猜字段
3. 状态流转的状态枚举（DETECTED/CONFIRMING 等）没有类型约束，拼写错误无法在编译时发现

**优化建议**：
1. 短期：增加 JSDoc 类型注释覆盖率，使用 `@typedef` 定义 DTO 类型
2. 中期：引入 JSDoc + ts-check（在 .js 文件顶部加 `// @ts-check`）
3. 长期：逐步迁移到 TypeScript（可按模块迁移，.js 和 .ts 共存）

---

### P3-2 组件 Props 校验不完整

**严重程度**：P3  
**涉及文件**：多个 components 下的 .vue 文件

**问题描述**：

以 FormModal.vue 为例：
```js
defineProps({
  open: { type: Boolean, default: false },
  eyebrow: { type: String, default: '' },
  title: { type: String, required: true },
  subtitle: { type: String, default: '' },
  confirmText: { type: String, default: '确认' },
  loading: { type: Boolean, default: false },
})
```

问题：
- 只有基本的 type + required + default
- 没有 validator（如 confirmText 最小长度、枚举值校验）
- 复杂类型（对象/数组）没有 default 工厂函数
- 没有自定义校验函数

**优化建议**：
1. 为枚举类型 Props 添加 validator
2. 对象/数组类型的 default 使用函数返回
3. 关键组件增加完整的 Props 校验

---

### P3-3 dashboardApi.js 存在格式/缩进问题

**严重程度**：P3  
**涉及文件**：`services/dashboardApi.js` 第 474、499 行等

**问题描述**：

代码中存在明显的格式问题：
```js
// 第 474 行：缩进异常
    getRelatedEvents: async (hazardId, hazardCode) => {
    requireBackend("上报人员定位");  // 缩进不对

// 第 499 行：函数前没有空行，格式拥挤
  },getAiComprehensiveAll: () => {
```

还有大量中文注释使用 `??` 替代（可能是编码问题或模板残留）：
- `@param {string|number} id ?? ID`
- `@deprecated ?? getZoneTrend ??`

**优化建议**：
1. 配置 ESLint + Prettier，统一代码格式
2. 修复所有注释中的 `??` 占位符
3. 启用保存时自动格式化

---

### P3-4 views 根目录散落 4 个页面文件

**严重程度**：P3  
**涉及文件**：
- `views/OverviewPage.vue`
- `views/WelcomePage.vue`
- `views/ForbiddenPage.vue`
- `views/ServerErrorPage.vue`

**问题描述**：

4 个页面文件直接放在 views 根目录，其他页面都在子目录中，不统一。

**优化建议**：
1. `OverviewPage.vue` → `views/dashboard/OverviewPage.vue` 或保留在 monitor/
2. `WelcomePage.vue` → `views/common/WelcomePage.vue`
3. `ForbiddenPage.vue` → `views/error/ForbiddenPage.vue`
4. `ServerErrorPage.vue` → `views/error/ServerErrorPage.vue`

---

### P3-5 缺少单元测试和 E2E 测试

**严重程度**：P3  
**涉及范围**：整个项目

**问题描述**：

审查中未发现任何测试文件（.test.js、.spec.js、.cy.js 等）。

**风险**：
- 重构时没有安全网（特别是服务层迁移这样的大改动）
- 工具函数、权限判断等核心逻辑没有测试保障
- bug 回归无法自动发现

**优化建议**：
1. 优先为 utils 下的纯函数添加单元测试（coordTransform、hazardTypes、safeMinMax）
2. 为权限判断逻辑添加单元测试（hasPermission、canAccessPath）
3. 为 store 的 actions/getters 添加测试
4. 服务层迁移完成后添加 API mock 测试

---

## 优化路线图建议

按优先级和依赖关系，建议分四个阶段推进：

### 第一阶段：架构止血（1-2 周）
1. 明确服务层架构决策，制定 dashboardApi 拆分计划
2. aiApi.js 改用统一 httpClient
3. auth.js 拆分常量到独立文件
4. 配置 ESLint + Prettier，修复格式问题

### 第二阶段：复用提升（2-3 周）
1. 抽取 5 个最高频通用组件（StatCard、StatusTag、Pagination、SearchBar、Drawer）
2. 抽取 3 个核心 composable（useDataList、usePagination、useConfirm）
3. 三个列表页改造为使用通用组件和 composable

### 第三阶段：架构统一（3-4 周）
1. dashboardApi 按领域拆分到 services/domain/
2. 移除 _architecture 目录层级，core 和 domain 直接在 services/ 下
3. 统一 store 持久化机制（pinia-plugin-persistedstate）
4. 目录结构调整（constants/、config/、styles/）

### 第四阶段：质量深化（持续）
1. 引入 TypeScript（或 JSDoc + ts-check）
2. 补充单元测试（工具函数 + store + 服务层）
3. 地图逻辑统一封装
4. 组件库文档化

---

## 附录：文件统计

| 目录 | 文件数 | 说明 |
|------|-------|------|
| components/ | 29 | 6 个子目录 + 23 个根组件 |
| views/ | 45+ | 11 个子目录 + 4 个根页面 |
| services/ | 35+ | 根目录 12 个 + _architecture 21 个 + mock/ 9 个 |
| stores/ | 3 | auth、realtime、userPreference |
| composables/ | 3 | useHazardList、useOverviewSearch、useBodyScrollLock |
| utils/ | 6 | 工具函数 |
| data/ | 1 | dashboardData.js（菜单配置） |
| layouts/ | 1 | DashboardLayout |
| router/ | 1 | index.js |

**总计**：约 120+ 个源文件，纯 JavaScript 项目
