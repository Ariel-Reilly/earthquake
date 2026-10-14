# 前端性能审查报告

**审查范围**：`d:\Projects\DisasterGuard\frontend`
**审查日期**：2026-10-03
**审查维度**：打包体积、首屏加载、运行时性能、资源优化、API 请求、状态管理

---

## 问题总览

| 严重程度 | 数量 | 说明 |
|---------|------|------|
| P0 严重 | 4 | 内存泄漏 / 重复计算 / 大资源 |
| P1 高优 | 5 | 体积优化 / 构建配置 / 重复请求 |
| P2 中优 | 4 | 体验优化 / 轮询策略 |
| P3 低优 | 2 | 细节优化 |

---

## P0 - 严重问题（必须修复）

### 1. RealTimeMapPage 中 trackPlayTimer 未清理，存在内存泄漏

**文件**：`src/views/monitor/RealTimeMapPage.vue`
**位置**：第 237 行定义，第 289 行创建，第 203-208 行 `onBeforeUnmount`

**问题描述**：
- 第 237 行定义了 `let trackPlayTimer = null`（设备轨迹播放定时器）
- 第 289 行在 `startTrackPlayback()` 中通过 `setInterval` 创建
- 第 203-208 行的 `onBeforeUnmount` 只清理了 `playTimer`（时间线播放），**遗漏了 `trackPlayTimer`**
- 当用户在轨迹播放过程中离开页面，定时器会持续运行，导致内存泄漏和回调异常

**当前代码**：
```js
onBeforeUnmount(() => {
  if (stopRealtime) stopRealtime()
  stopRealtimeSync()
  if (playTimer) clearInterval(playTimer)
  if (mapController?.destroy) mapController.destroy()
  // 缺少：if (trackPlayTimer) clearInterval(trackPlayTimer)
})
```

**优化建议**：
```js
onBeforeUnmount(() => {
  if (stopRealtime) stopRealtime()
  stopRealtimeSync()
  if (playTimer) clearInterval(playTimer)
  if (trackPlayTimer) clearInterval(trackPlayTimer)  // 新增
  if (mapController?.destroy) mapController.destroy()
})
```

---

### 2. MobileCommandPage 中 online/offline 事件监听未移除

**文件**：`src/views/mobile/MobileCommandPage.vue`
**位置**：第 125-126 行 `onMounted` 中

**问题描述**：
- 在 `onMounted` 中注册了 `window.addEventListener("online", ...)` 和 `window.addEventListener("offline", ...)`
- 组件没有对应的 `onBeforeUnmount` 来移除这些监听器
- 每次进入/离开该页面都会新增两个全局事件监听器，造成内存泄漏

**当前代码**：
```js
onMounted(async () => {
  window.addEventListener("online", () => { isOnline.value = true; });
  window.addEventListener("offline", () => { isOnline.value = false; });
  await loadData();
});
```

**优化建议**：
```js
function handleOnline() { isOnline.value = true; }
function handleOffline() { isOnline.value = false; }

onMounted(async () => {
  window.addEventListener("online", handleOnline);
  window.addEventListener("offline", handleOffline);
  await loadData();
});

onBeforeUnmount(() => {
  window.removeEventListener("online", handleOnline);
  window.removeEventListener("offline", handleOffline);
});
```

---

### 3. AnalyticsPage 模板中直接调用函数，每次渲染重复计算

**文件**：`src/views/data/AnalyticsPage.vue`
**位置**：第 78-82 行定义，第 213、237、251、270、291 行模板中调用

**问题描述**：
- `monthlyBars`、`warningTypes`、`deviceTrend`、`heatmap`、`riskZones` 被定义为**普通函数**而非 `computed`
- 在模板的 `v-for` 中直接以函数调用形式使用：`v-for="item in monthlyBars()"`
- Vue 每次重新渲染都会重新执行这些函数（即使依赖数据未变化）
- 这些函数用于 `v-for`，意味着每次渲染都要遍历整个数组重建 DOM

**当前代码**：
```js
const monthlyBars = () => charts.value?.monthlyInspection ?? []
const warningTypes = () => charts.value?.warningType ?? []
const deviceTrend = () => charts.value?.deviceTrend ?? []
const heatmap = () => charts.value?.heatmap ?? []
const riskZones = () => charts.value?.riskZones ?? []
```

**优化建议**：改为 `computed` 属性，利用 Vue 的缓存机制：
```js
const monthlyBars = computed(() => charts.value?.monthlyInspection ?? [])
const warningTypes = computed(() => charts.value?.warningType ?? [])
const deviceTrend = computed(() => charts.value?.deviceTrend ?? [])
const heatmap = computed(() => charts.value?.heatmap ?? [])
const riskZones = computed(() => charts.value?.riskZones ?? [])
```
模板中去掉括号：`v-for="item in monthlyBars"`

---

### 4. mascot.jpg 未压缩，体积达 204KB

**文件**：`public/mascot.jpg`
**使用位置**：`src/components/BrandMascot.vue` 第 9 行

**问题描述**：
- 图片体积 **204.78 KB**，未经过压缩优化
- 用于欢迎页（首屏）和 AI 助手对话框，首屏加载时就会请求
- 未使用 WebP / AVIF 等现代格式

**优化建议**：
1. 使用工具压缩图片（如 Squoosh、sharp），目标体积控制在 50KB 以内
2. 转换为 WebP 格式（可减少 30-50% 体积），保留 JPG 作为降级
3. 在 BrandMascot 组件中使用 `<picture>` 标签提供多格式：
```html
<picture>
  <source srcset="/mascot.webp" type="image/webp">
  <img src="/mascot.jpg" alt="品牌形象" class="mascot-img" loading="lazy">
</picture>
```

---

## P1 - 高优先级问题

### 5. OverviewPage 体积过大（65KB），首屏加载负担重

**文件**：`src/views/OverviewPage.vue`
**打包产物**：`OverviewPage-BFclZ2yI.js` 约 **65.35 KB**（所有页面中最大）

**问题描述**：
- OverviewPage 是登录后的首页，直接影响首屏后首次交互速度
- 该页面集成了地图、KPI 卡片、预警列表、人员管理、对比分析、气象图层等大量功能
- 文件行数极多（2700+ 行），包含大量模板和逻辑
- `loadData()` 一次性并行调用 4 个 API + 天气 + AI 评分，共 6 个请求

**优化建议**：
1. **组件拆分**：将以下区域拆为独立异步组件
   - 对比分析面板（compareKpiCards、compareCategorySlices 等约 300 行模板）
   - 人员值班面板（dutyTeamStats、sortedPersonnel 等）
   - AI 健康度徽标与详情
2. **数据懒加载**：非关键数据（AI 评分、气象 overlay）延迟到首屏渲染后再加载
3. **虚拟列表**：预警列表和灾害点列表使用虚拟滚动（当前直接渲染全部数据）

---

### 6. cesium 依赖体积大，vite-plugin-cesium 可能引入额外开销

**文件**：`package.json`、`vite.config.js`、`src/services/_architecture/core/mapService.js`

**问题描述**：
- `cesium` v1.144 是非常大的库（打包后数 MB），主要用于 HazardDetailPage 的 3D 视角
- 代码中已使用动态导入（`await import('cesium')`），这是正确的按需加载方式
- 但 `vite-plugin-cesium` 插件可能在构建时做了额外处理，需要确认是否产生了不必要的静态依赖
- `vite.config.js` 的 `manualChunks` 中没有为 cesium 单独分块

**优化建议**：
1. 确认 `vite-plugin-cesium` 是否必要：如果只是用静态资源拷贝功能，可以手动配置
2. 在 `manualChunks` 中添加 cesium 分包（如果它被静态引用了）：
```js
manualChunks: {
  vue: ['vue', 'vue-router'],
  map: ['ol', 'ol/format/GeoJSON'],
  icons: ['lucide-vue-next'],
  // cesium 动态导入时 Vite 会自动分包，无需手动配置
}
```
3. 验证构建产物中 cesium 是否为独立 chunk，且未被首屏 chunk 引用

---

### 7. 深度 watch 过多（17 处），部分可优化

**涉及文件**：
- `src/views/monitor/HistoryHazardsPage.vue:146`
- `src/views/settings/SettingsPage.vue:82`
- `src/views/system/AuditLogPage.vue:96`
- `src/components/rule/RuleList.vue:183-186`
- `src/views/ai/AiResultsPage.vue:140`
- `src/components/rule/RuleEditor.vue:209`
- `src/views/system/UserManagementPage.vue:192`
- `src/views/system/MessageCenterPage.vue:123`
- `src/views/ai/AiTaskManagePage.vue:190`
- `src/components/profile/ThemeLanguage.vue:32`
- `src/views/system/ReportCenterPage.vue:363-364`
- `src/components/profile/ProfileInfo.vue:54`
- `src/views/system/NoticeTemplatePage.vue:300-303`
- `src/components/profile/NotificationPreference.vue:42`

**问题描述**：
- 共 17 处使用 `{ deep: true }` 的 watch
- 深度监听会递归遍历对象所有属性，对大对象性能影响显著
- 部分场景（如 `filter` 对象监听触发列表刷新）可以用更细粒度的监听替代

**优化建议**：
1. 对于仅需判断"是否变化"的场景（如 `watch(filter, () => {}, { deep: true })` 空回调），可直接移除
2. 对于表单脏检查场景（`watch(settings, () => { isDirty = true }, { deep: true })`），可考虑：
   - 改为 watch 具体字段（如果字段不多）
   - 使用 `JSON.stringify` 对比（仅在表单提交时校验）
3. 对于过滤器触发查询的场景，可结合防抖 + 浅层 watch：
```js
// 优化前
watch(filter, loadUsers, { deep: true })

// 优化后
watch(
  () => [filter.keyword, filter.status, filter.page, filter.pageSize],
  () => { debouncedLoad() },
  { immediate: true }
)
```

---

### 8. OverviewPage 与 realtimeService 存在重复请求风险

**文件**：`src/views/OverviewPage.vue`、`src/services/_architecture/core/realtimeService.js`

**问题描述**：
- OverviewPage 的 `loadData()` 调用：`getOverview()`、`getHazards()`、`getDevices()`、`getWarnings()`
- realtimeService 的轮询调用：`pollSnapshot()`、`pollWarningsPreview()`、`pollPersonnel()`
- 两者可能请求相同的后端接口（灾害点列表、预警列表、设备/人员数据）
- 在实时通道已连接且页面刚加载时，可能在几秒内请求两次相同数据

**优化建议**：
1. 统一数据来源：OverviewPage 优先从 realtimeService 的 store / 缓存读取数据
2. 页面加载时先检查 realtime store 是否已有有效数据（时间戳 < 30s），有则跳过 API 调用
3. 将 OverviewPage 的刷新按钮与 realtimeService 的手动刷新方法绑定

---

### 9. vite 构建配置优化不足

**文件**：`vite.config.js`

**问题描述**：
- 缺少 `minify: 'terser'` 配置（Vite 6 默认使用 esbuild，压缩率略低于 terser）
- 缺少 CSS 代码分割优化配置
- 缺少 `build.rollupOptions.output.assetFileNames` 等资源命名优化
- 没有配置 gzip / brotli 压缩插件（部署时由 Nginx 负责也可，但构建时预压缩更好）
- `chunkSizeWarningLimit` 设为 1024KB，阈值过高，掩盖了大 chunk 问题

**当前配置**：
```js
build: {
  chunkSizeWarningLimit: 1024,
  rollupOptions: {
    output: {
      manualChunks: {
        vue: ['vue', 'vue-router'],
        map: ['ol', 'ol/format/GeoJSON'],
        icons: ['lucide-vue-next'],
      },
    },
  },
},
```

**优化建议**：
```js
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import cesium from 'vite-plugin-cesium'
// import viteCompression from 'vite-plugin-compression' // 如需预压缩

export default defineConfig({
  plugins: [
    vue(),
    cesium(),
    // viteCompression({ algorithm: 'gzip' }), // 预生成 .gz 文件
  ],
  build: {
    target: 'es2018',
    cssCodeSplit: true,
    sourcemap: false,
    chunkSizeWarningLimit: 500, // 降低阈值，暴露问题
    rollupOptions: {
      output: {
        manualChunks: {
          vue: ['vue', 'vue-router', 'pinia'],
          map: ['ol'],
          icons: ['lucide-vue-next'],
        },
        // 更细粒度的 chunk 命名
        chunkFileNames: 'assets/js/[name]-[hash].js',
        entryFileNames: 'assets/js/[name]-[hash].js',
        assetFileNames: 'assets/[ext]/[name]-[hash].[ext]',
      },
    },
  },
})
```

---

## P2 - 中优先级问题

### 10. 图片缺少懒加载属性

**涉及文件**：
- `src/views/monitor/SatelliteComparePage.vue`（第 554、555、604、611、626 行，共 5 处 `<img>`）
- `src/views/monitor/WeatherPage.vue`（第 304 行起，气象图片）
- `src/components/profile/ProfileInfo.vue`（第 114 行头像）

**问题描述**：
- 所有 `<img>` 标签均未设置 `loading="lazy"`
- 卫星对比图和气象图都是大图，首屏外的图片也会立即加载

**优化建议**：
- 非首屏关键图片添加 `loading="lazy"`
- 首屏可见的大图可添加 `decoding="async"` 避免阻塞渲染
- 头像类小图片影响不大，但也建议加上

---

### 11. index.html 缺少资源预加载策略

**文件**：`index.html`

**问题描述**：
- 没有任何 `preload`、`prefetch`、`dns-prefetch` 配置
- 首屏关键资源（字体、首屏 JS chunk）未提前加载
- API 域名没有提前做 DNS 解析

**优化建议**：
```html
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>御灾智巡 · DisasterGuard</title>
  <!-- 预连接 API 域名，减少首次请求延迟 -->
  <link rel="dns-prefetch" href="//your-api-domain.com">
  <link rel="preconnect" href="//your-api-domain.com" crossorigin>
  <!-- 预加载首屏关键字体（如有自定义字体） -->
  <!-- <link rel="preload" href="/fonts/xxx.woff2" as="font" type="font/woff2" crossorigin> -->
</head>
```

---

### 12. realtimeService 轮询频率偏高，多定时器并行

**文件**：`src/services/_architecture/core/realtimeService.js`
**位置**：第 666-689 行

**问题描述**：
- 轮询模式下同时运行 4 个定时器：
  - `pollingTimer`：快照轮询，最小 15s
  - `warningTimer`：预警轮询，最小 5s
  - `personnelTimer`：人员轮询，最小 10s
  - `personnelRouteTimer`：人员轨迹动画，10s（仅演示模式）
- WebSocket 已连接时，降频兜底轮询仍有 3 个定时器运行
- 多个独立的 setInterval 会造成频繁的事件循环唤醒

**优化建议**：
1. 合并为单个定时器，按不同时间间隔分发不同类型的请求
2. WS 连接稳定时，进一步降低兜底轮询频率（如快照 60s、预警 30s）
3. 页面不可见时（`document.hidden`）暂停或降低轮询频率

---

### 13. 多页面独立轮询，缺乏统一调度

**涉及页面**：
- `ConfirmationWorkbenchPage.vue` - 30s 轮询确认队列
- `ConfirmationSignPage.vue` - 15s 轮询
- `TaskDetailPage.vue` - 5s 运行时 + 日志轮询
- `MessageCenterPage.vue` - 45s 轮询消息
- `EventCenterPage.vue` - 30s 轮询事件
- `SystemMonitorPage.vue` - 30s 轮询系统监控
- `PersonnelManagePage.vue` - 30s 轮询人员
- `TodoCenterPage.vue` - 定时刷新待办

**问题描述**：
- 每个页面各自维护 setInterval，用户切换页面时反复创建/销毁定时器
- 部分数据（如人员、消息）在多个页面都有展示，存在重复请求
- 缺乏统一的后台数据刷新机制

**优化建议**：
1. 建立统一的 `pollingService`，集中管理所有轮询
2. 相同类型的数据（如人员列表）全局只维护一个刷新周期
3. 使用 `visibilitychange` 事件在页面后台时降低频率

---

## P3 - 低优先级优化

### 14. DashboardLayout 每秒更新时间，粒度太细

**文件**：`src/layouts/DashboardLayout.vue`
**位置**：第 480-482 行

**问题描述**：
- 使用 `setInterval` 每秒更新一次 `currentTime`
- 实际展示精度只需要到分钟（侧边栏显示的是 HH:mm）
- 每秒触发一次响应式更新，会导致相关组件重新渲染

**优化建议**：
```js
// 改为每分钟更新一次
timerId = window.setInterval(() => {
  currentTime.value = new Date()
}, 60_000)
// 初始化时对齐到下一分钟
const now = new Date()
const msToNextMinute = (60 - now.getSeconds()) * 1000 - now.getMilliseconds()
setTimeout(() => {
  currentTime.value = new Date()
  timerId = window.setInterval(() => {
    currentTime.value = new Date()
  }, 60_000)
}, msToNextMinute)
```

---

### 15. Pinia stores 中存在大量兼容层代码

**涉及文件**：
- `src/stores/auth.js` - 兼容 `getAuthToken`、`setAuthSession` 等函数 API
- `src/stores/userPreference.js` - 兼容 `getUserPreferenceSnapshot`、`setThemeSync` 等
- `src/stores/realtime.js` - 兼容 `getRealtimeStateSnapshot`

**问题描述**：
- 三个 store 都维护了"旧函数 API → store"的兼容层
- 这些兼容函数增加了打包体积
- 部分兼容函数使用 `JSON.parse(JSON.stringify(...))` 做深拷贝，有运行时开销

**优化建议**：
1. 制定迁移计划，逐步将所有调用方改为直接使用 store
2. 兼容层标记为 `@deprecated`，在后续版本中移除
3. 深拷贝场景评估是否必要，优先使用浅拷贝或结构化克隆

---

## 打包体积分析

### dist/assets 最大 JS 文件 TOP 10

| 排名 | 文件名 | 大小 (KB) | 说明 |
|------|--------|----------|------|
| 1 | map-DjTOSqbl.js | 300.52 | OpenLayers 库（manualChunks 拆分） |
| 2 | index-ahqNxq22.js | 190.17 | 主入口（含 App.vue、main.js、全局样式等） |
| 3 | vue-Cgkl_ZHd.js | 102.80 | Vue + Vue Router |
| 4 | icons-BUIJs8wf.js | 79.68 | lucide-vue-next 图标库 |
| 5 | OverviewPage-BFclZ2yI.js | 65.35 | 概览页（最大的业务页面） |
| 6 | mapService-Cu7Nyf0d.js | 29.84 | 地图服务模块 |
| 7 | ModelManagePage-DJAycufX.js | 27.63 | AI 模型管理页 |
| 8 | HazardDetailPage-SlI9NH_u.js | 26.87 | 灾害详情页 |
| 9 | BackupRecoveryPage-CtcDHzX6.js | 26.80 | 备份恢复页 |
| 10 | DataCatalogPage-Bqp_AKQm.js | 25.86 | 数据目录页 |

### 依赖分析

| 依赖 | 体积级别 | 使用方式 | 优化空间 |
|------|---------|---------|---------|
| cesium | 超大（数 MB） | 动态导入（HazardDetailPage 3D 视角） | 已按需加载，确认构建产物独立 |
| ol (OpenLayers) | ~300 KB | 全局使用（地图核心） | 已拆分为独立 chunk，可考虑按模块导入 |
| lucide-vue-next | ~80 KB | 全局使用 | 已拆分，可考虑按需导入但 ROI 不高 |
| vue + vue-router + pinia | ~100 KB | 全局使用 | 已拆分，无优化空间 |

---

## 路由懒加载检查结果

**结论**：路由懒加载配置良好。
- 除 `WelcomePage` 和 `DashboardLayout` 外，所有页面组件均使用动态 `import()` 懒加载
- 按模块（overview、hazard、device、warning、data、monitor、ai、system 等）分组命名
- 404 页面以内联模板形式定义，不产生额外 chunk

**首屏加载链路**：
1. `index.html` → `main.js` → `App.vue` + `styles.css`
2. 欢迎页（`/`）：同步加载 `WelcomePage.vue`
3. 登录后进入 `/dashboard`：加载 `DashboardLayout.vue` + `OverviewPage.vue`（最大页面）

---

## v-for :key 检查结果

**结论**：所有 `v-for` 均正确设置了 `:key`，未发现遗漏。
- 大部分使用业务 ID 作为 key（`item.id`、`deviceCode` 等），质量较好
- 少数静态列表使用索引（`idx`、`i`）作为 key，由于列表是静态的不会重排，无性能问题

---

## 地图页面内存清理检查结果

| 页面 | playTimer | trackPlayTimer | 地图实例 | ResizeObserver | 实时通道 | 事件监听 |
|------|-----------|---------------|---------|----------------|---------|---------|
| RealTimeMapPage | 已清理 | **未清理** | 已 destroy | 未使用 | 已停止 | 无全局监听 |
| DeviceMapPage | 已清理 (setTimeout) | - | 已 setTarget(null) | 已 disconnect | 未使用 | 无全局监听 |
| WeatherPage | - | - | 已 destroy | 未使用 | 未使用 | 无全局监听 |
| SatelliteComparePage | flickerInterval 已清理 | - | - | 未使用 | 未使用 | 无全局监听 |

---

## 优化路线图建议

### 第一阶段（立即修复，1-2 天）
- [ ] 修复 RealTimeMapPage trackPlayTimer 内存泄漏（P0-1）
- [ ] 修复 MobileCommandPage 事件监听未移除（P0-2）
- [ ] AnalyticsPage 函数改 computed（P0-3）
- [ ] 压缩 mascot.jpg 并转 WebP（P0-4）

### 第二阶段（1 周内）
- [ ] 优化 vite.config.js 构建配置（P1-9）
- [ ] 梳理深度 watch，优化不必要的 deep 监听（P1-7）
- [ ] 确认 cesium 构建产物，评估 vite-plugin-cesium 必要性（P1-6）

### 第三阶段（2-4 周）
- [ ] OverviewPage 组件拆分和懒加载优化（P1-5）
- [ ] 统一数据来源，减少 OverviewPage 与 realtimeService 的重复请求（P1-8）
- [ ] 图片懒加载 + index.html 预加载策略（P2-10、11）
- [ ] realtimeService 轮询优化（P2-12）

### 第四阶段（长期优化）
- [ ] 建立统一轮询调度服务（P2-13）
- [ ] DashboardLayout 时间更新降频（P3-14）
- [ ] 移除 Pinia store 兼容层（P3-15）
