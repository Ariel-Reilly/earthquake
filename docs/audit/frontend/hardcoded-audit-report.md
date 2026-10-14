# 前端代码硬编码问题审查报告

**审查范围**: `d:\Projects\DisasterGuard\frontend\src`
**重点目录**: `src/views`、`src/services`、`src/components`、`src/utils`、`src/data`
**生成时间**: 2026-10-03

---

## 目录

1. [硬编码的 URL / API 地址 / 端口号](#1-硬编码的-url--api-地址--端口号)
2. [写死的经纬度坐标 / 地图中心点](#2-写死的经纬度坐标--地图中心点)
3. [硬编码的用户数据 / 权限数据 / 配置数据](#3-硬编码的用户数据--权限数据--配置数据)
4. [Magic Number（魔法数字）](#4-magic-number魔法数字)
5. [分页大小 / 超时时间 / 轮询间隔等配置写死](#5-分页大小--超时时间--轮询间隔等配置写死)
6. [硬编码的颜色值 / 样式值](#6-硬编码的颜色值--样式值)
7. [条件判断中写死的字符串值](#7-条件判断中写死的字符串值)
8. [localhost / 127.0.0.1 / 硬编码 IP 地址](#8-localhost--127001--硬编码-ip-地址)

---

## 1. 硬编码的 URL / API 地址 / 端口号

### 1.1 后端 API 基址 fallback（多处重复）

| 文件 | 行号 | 硬编码内容 | 严重程度 | 修复建议 |
|------|------|-----------|----------|----------|
| `services/_architecture/core/runtime.js` | 22 | `"http://localhost:8080/api"` | 高 | 统一由 runtime.js 导出 API_BASE，其他模块引用而非各自定义默认值 |
| `services/_architecture/domain/aiApi.js` | 9 | `"http://localhost:8080/api"` | 高 | 改为从 runtime.js 导入 `getApiBaseUrl()` |
| `services/_architecture/domain/deviceMapService.js` | 19 | `'http://localhost:8080/api'` | 高 | 改为从 runtime.js 导入 |

**问题说明**: 同一默认值 `http://localhost:8080/api` 在至少 3 个文件中重复定义，环境变量未统一管理。

### 1.2 AI 服务地址（多处重复且不一致）

| 文件 | 行号 | 硬编码内容 | 严重程度 | 修复建议 |
|------|------|-----------|----------|----------|
| `services/_architecture/domain/aiApi.js` | 305 | `"http://localhost:8001/api/ai"` | 高 | 使用 `import.meta.env.VITE_AI_SERVICE_BASE_URL`，与 .env 中变量名一致 |
| `services/_architecture/core/sentinelService.js` | 34 | `'http://localhost:8001/api/ai'` | 高 | 同上，且当前变量名 `VITE_AI_SERVICE_BASE` 与 .env 中 `VITE_AI_SERVICE_BASE_URL` 不一致 |
| `services/_architecture/domain/satelliteCompareService.js` | 25 | `'http://localhost:8001/api/ai'` | 高 | 统一环境变量名 |
| `services/_architecture/domain/driftMonitorService.js` | 9 | `"http://localhost:8000/api"` | 高 | 端口 8000 与其他 AI 服务 8001 不一致，需确认是否为不同服务 |
| `services/_architecture/domain/multiModalService.js` | 9 | `"http://localhost:8000/api"` | 高 | 同上 |

**问题说明**: AI 服务地址存在端口不一致（8000 vs 8001），环境变量名也不一致（`VITE_AI_SERVICE_URL` vs `VITE_AI_SERVICE_BASE_URL`）。

### 1.3 第三方 API URL 硬编码

| 文件 | 行号 | 硬编码内容 | 严重程度 | 修复建议 |
|------|------|-----------|----------|----------|
| `services/_architecture/core/geocodeService.js` | 149 | `https://restapi.amap.com/v3/place/text` | 中 | 提取到配置文件或环境变量 |
| `services/_architecture/core/geocodeService.js` | 176 | `https://nominatim.openstreetmap.org/search` | 中 | 提取到配置文件 |
| `services/_architecture/core/geocodeService.js` | 243 | `https://restapi.amap.com/v3/geocode/regeo` | 中 | 同上 |
| `services/_architecture/core/geocodeService.js` | 261 | `https://nominatim.openstreetmap.org/reverse` | 中 | 同上 |
| `services/_architecture/core/mapService.js` | 93 | `https://t0.tianditu.gov.cn/` | 中 | 天地图瓦片 URL 应可配置 |
| `services/_architecture/core/mapService.js` | 102 | `https://webrd01.is.autonavi.com/` | 中 | 高德瓦片 URL 应可配置 |
| `services/_architecture/domain/weatherApi.js` | 2 | `https://api.openweathermap.org/data/2.5/weather` | 中 | 已用环境变量 fallback，建议增加 API key 配置 |
| `services/_architecture/domain/weatherService.js` | 26 | `https://image.nmc.cn` | 中 | 国家气象数据 URL 应可配置 |
| `services/_architecture/domain/weatherService.js` | 27 | `http://img.nsmc.org.cn/PORTAL/NSMC/XML` | 中 | 同上（注意是 HTTP 非 HTTPS） |
| `services/_architecture/domain/weatherService.js` | 54-56 | CORS 代理 URL 列表（3个） | 中 | 代理地址应集中配置 |

### 1.4 UI 展示用硬编码服务地址

| 文件 | 行号 | 硬编码内容 | 严重程度 | 修复建议 |
|------|------|-----------|----------|----------|
| `views/settings/SettingsPage.vue` | 265, 276 | `'http://localhost:8080/api'` | 中 | 应从 runtime 配置动态读取 |
| `views/settings/SettingsPage.vue` | 266, 277 | `'mqtt://mqtt.geo-sentry.local:1883'` | 中 | MQTT 地址应来自后端配置接口 |
| `views/system/NotificationChannelsPage.vue` | 155 | `https://dysmsapi.aliyuncs.com` | 中 | 渠道配置 mock 数据，应来自后端 |
| `views/system/NotificationChannelsPage.vue` | 197 | `http://192.168.1.100:8080` | 中 | LED 控制器地址 mock，应来自后端 |
| `views/system/NotificationChannelsPage.vue` | 211 | `https://emergency-api.example.com/webhook` | 低 | Webhook 示例地址，mock 数据 |

---

## 2. 写死的经纬度坐标 / 地图中心点

### 2.1 默认地图中心点（多处重复 107.21, 30.61）

| 文件 | 行号 | 硬编码内容 | 严重程度 | 修复建议 |
|------|------|-----------|----------|----------|
| `services/_architecture/core/mapService.js` | 263 | `center = [107.21, 30.61]` | 高 | 应从用户偏好/后端配置读取默认中心点 |
| `services/_architecture/core/mapService.js` | 985 | `center = [107.21, 30.61]` | 高 | Cesium 版本，同上 |
| `views/OverviewPage.vue` | 424 | `center: [107.21, 30.61]` | 高 | 总览页地图中心，同上 |
| `views/monitor/WeatherPage.vue` | 109 | `center: [107.21, 30.61]` | 高 | 气象页地图中心，同上 |

**问题说明**: 坐标 `[107.21, 30.61]`（重庆石柱县附近）作为默认地图中心点在至少 4 处硬编码。应从后端获取用户管辖区域中心，或存放在用户偏好中。

### 2.2 其他硬编码中心点

| 文件 | 行号 | 硬编码内容 | 严重程度 | 修复建议 |
|------|------|-----------|----------|----------|
| `views/monitor/DeviceMapPage.vue` | 187 | `center: ol.fromLonLat([104.065, 30.575])` | 高 | 成都坐标，应与其他页面统一或可配置 |
| `views/screen/CommandScreenPage.vue` | 351 | `center: [104.06, 30.67]` | 高 | 指挥大屏中心，同上 |
| `services/_architecture/domain/weatherApi.js` | 29 | `lat = 30.61, lon = 107.21` | 中 | 天气 API 默认坐标，应从用户位置或配置获取 |

### 2.3 气象图层固定范围（中国区域）

| 文件 | 行号 | 硬编码内容 | 严重程度 | 修复建议 |
|------|------|-----------|----------|----------|
| `services/_architecture/domain/weatherService.js` | 408 | `EXTENT_CHINA_FY4A = [70, 0, 140, 60]` | 低 | 气象卫星全国范围，属于业务常量，建议保留但命名清晰 |
| `services/_architecture/domain/weatherService.js` | 410 | `EXTENT_CHINA_NMC = [73, 16, 135, 54]` | 低 | 同上 |
| `services/_architecture/core/mapService.js` | 144, 473, 483 | `imageExtent: [107.0, 30.4, 107.4, 30.8]` | 中 | 示例气象叠加图层范围，应为动态数据 |

### 2.4 卫星对比 AOI 预设区域

| 文件 | 行号 | 硬编码内容 | 严重程度 | 修复建议 |
|------|------|-----------|----------|----------|
| `views/monitor/SatelliteComparePage.vue` | 60-64 | 九寨沟/汶川/玉树/鲁甸/雅安 5 个 AOI 预设 bbox | 中 | 应从后端"关注区域"配置获取，而非前端写死 |
| `views/monitor/SatelliteComparePage.vue` | 78 | `bbox: [103.30, 30.80, 103.80, 31.20]` | 中 | 默认选中汶川，应可配置 |

### 2.5 路径规划示例坐标

| 文件 | 行号 | 硬编码内容 | 严重程度 | 修复建议 |
|------|------|-----------|----------|----------|
| `views/warning/RoutePlanningPage.vue` | 71 | `project(120.15, 30.28)` | 中 | 杭州坐标作为起点示例，应从灾害点或用户输入获取 |
| `views/warning/RoutePlanningPage.vue` | 75 | `project(120.35, 30.45)` | 中 | 终点示例，同上 |
| `views/warning/ResourceDispatchPage.vue` | 101 | `cx = 107.3 + Math.random() * 0.1` | 中 | 随机生成资源位置，mock 数据 |

### 2.6 mock 种子数据中的灾害点坐标

| 文件 | 行号 | 硬编码内容 | 严重程度 | 修复建议 |
|------|------|-----------|----------|----------|
| `services/mock/seed.js` | 60-97+ | 5 个灾害点的经纬度（HZ-001~HZ-005） | 低 | mock 数据，后端连通后不影响 |
| `services/mock/runtime.js` | 484-488 | 5 名值守人员的经纬度 | 低 | mock 数据 |
| `services/_architecture/core/realtimeService.js` | 300-301 | `baseLon = 107.11 + ...`, `baseLat = 30.42 + ...` | 中 | 人员位置模拟生成逻辑，基于固定基准点 |

---

## 3. 硬编码的用户数据 / 权限数据 / 配置数据

### 3.1 用户数据 mock

| 文件 | 行号 | 硬编码内容 | 严重程度 | 修复建议 |
|------|------|-----------|----------|----------|
| `services/mock/system.js` | 17-55 | 5 个用户账号（lichenxi/admin, wangjianguo/attendant 等） | 中 | mock 数据，后端连通后应从 API 获取 |
| `services/mock/system.js` | 71-95 | 角色列表（admin/attendant/expert/maintenance） | 中 | 角色定义应来自后端权限系统 |
| `services/mock/runtime.js` | 266-288 | `EXPERT_POOL` 专家池（7 名专家） | 中 | 专家列表应来自后端 |
| `services/_architecture/core/realtimeService.js` | 32-50+ | `PERSONNEL_ROSTER` 值守人员档案（至少 5 人） | 中 | 人员档案应来自人员管理模块 |

### 3.2 权限 / 角色硬编码

| 文件 | 行号 | 硬编码内容 | 严重程度 | 修复建议 |
|------|------|-----------|----------|----------|
| `components/PermissionButton.vue` | 需确认 | 权限判断逻辑中的角色字符串 | 中 | 应使用统一的权限枚举常量 |
| `services/mock/system.js` | 19, 28, 37, 46, 55 | role: "admin"/"attendant"/"expert"/"maintenance" | 中 | 角色编码应定义为常量或从后端字典获取 |

### 3.3 通知渠道配置硬编码

| 文件 | 行号 | 硬编码内容 | 严重程度 | 修复建议 |
|------|------|-----------|----------|----------|
| `views/system/NotificationChannelsPage.vue` | 56-139 | `channelTypes` 6 种渠道类型定义（短信/APP/邮件/LED/Webhook/声光） | 中 | 渠道类型应从后端配置获取，字段定义也应动态化 |
| `views/system/NotificationChannelsPage.vue` | 142-240+ | `channels` mock 渠道实例数据 | 低 | mock 数据 |

### 3.4 导航菜单硬编码

| 文件 | 行号 | 硬编码内容 | 严重程度 | 修复建议 |
|------|------|-----------|----------|----------|
| `data/dashboardData.js` | 20-77 | `dashboardMenu` 菜单配置（约 40 个菜单项） | 中 | 菜单应可由后端权限系统动态下发，当前全量写死在前端 |
| `data/dashboardData.js` | 83-129 | `moduleMeta` 页面标题映射 | 低 | 可接受，但与路由元信息重复定义 |

### 3.5 地区 / 区域硬编码

| 文件 | 行号 | 硬编码内容 | 严重程度 | 修复建议 |
|------|------|-----------|----------|----------|
| `views/ai/SocialMediaPage.vue` | 70 | `["全部地区", "成都市", "绵阳市", "德阳市", "雅安市", "阿坝州"]` | 中 | 地区列表应从后端行政区划接口获取 |
| `views/monitor/ZoneDashboardPage.vue` | 需确认 | `REGION_OPTIONS` 区域选项 | 中 | 同上 |
| `services/mock/seed.js` | 59, 70, 82, 94 | 龙安镇/石桥镇/清河镇 等乡镇名称 | 低 | mock 数据 |

### 3.6 用户偏好默认值

| 文件 | 行号 | 硬编码内容 | 严重程度 | 修复建议 |
|------|------|-----------|----------|----------|
| `services/mock/runtime.js` | 223-238 | `DEFAULT_USER_PREFERENCE` 通知渠道默认开关 | 中 | 默认偏好应与后端一致或从后端获取 |
| `stores/userPreference.js` | 各处 | 主题/语言/静默时段等默认值 | 低 | 本地默认值可接受，但应可被后端配置覆盖 |

---

## 4. Magic Number（魔法数字）

### 4.1 地图相关魔法数字

| 文件 | 行号 | 硬编码内容 | 严重程度 | 修复建议 |
|------|------|-----------|----------|----------|
| `services/_architecture/core/mapService.js` | 68-70 | `200 / 1000 / 5000` 精度阈值 → 缩放等级映射 | 中 | 提取为 `ACCURACY_ZOOM_THRESHOLDS` 命名常量 |
| `services/_architecture/core/mapService.js` | 84-85 | `500 / 2000` 精度阈值 → 等级映射 | 中 | 提取为 `GPS_ACCURACY_GRADES` 常量 |
| `services/_architecture/core/mapService.js` | 678 | `timeout: 25_000` GPS 定位超时 | 中 | 提取为 `GPS_LOCATION_TIMEOUT_MS` 常量 |
| `services/_architecture/core/mapService.js` | 798 | `setTimeout(pickBest, 8_000)` 最佳定位等待 | 中 | 提取为 `GPS_WAIT_FOR_BEST_MS` 常量 |
| `utils/coordTransform.js` | 34 | `a = 6378245.0` 地球长半轴 | 低 | 物理常量，已赋值给变量 a（但命名不清晰，建议改为 `EARTH_SEMI_MAJOR_AXIS`） |
| `utils/coordTransform.js` | 35 | `ee = 0.00669342162296594323` 偏心率平方 | 低 | 物理常量，建议命名为 `EARTH_ECCENTRICITY_SQ` |
| `utils/coordTransform.js` | 93 | `i < 15` 迭代次数 | 中 | 提取为 `GCJ02_ITERATIONS = 15` |
| `utils/geo.js` | 3 | `R = 6371000` 地球半径 | 低 | 物理常量，建议命名为 `EARTH_RADIUS_METERS` |
| `utils/geo.js` | 15 | `value >= 1000` 米转公里阈值 | 低 | 单位换算，可接受 |

### 4.2 图表 / Canvas 尺寸

| 文件 | 行号 | 硬编码内容 | 严重程度 | 修复建议 |
|------|------|-----------|----------|----------|
| `views/hazard/FieldInspectionPage.vue` | 263-264 | `W = 600, H = 240` 轨迹图画布尺寸 | 低 | 已定义为常量，命名可更清晰 |
| `views/ai/DisasterForecastPage.vue` | 130-131 | `W = 600, H = 200` 预测图画布尺寸 | 低 | 同上 |
| `views/ai/DamageAssessmentPage.vue` | 183-187 | `RADAR_W = 320, RADAR_H = 280, RADAR_R = 100` 雷达图尺寸 | 低 | 已定义为常量 |
| `views/warning/RoutePlanningPage.vue` | 41-42 | `MAP_W = 880, MAP_H = 520` 路径规划地图尺寸 | 低 | 已定义为常量 |
| `views/system/PersonnelManagePage.vue` | 148-149 | `W = 720, H = 420` 人员分布图画布 | 低 | 已定义为常量 |
| `components/event/StateMachineDiagram.vue` | 104-105 | `SVG_W = 900, SVG_H = 110` 状态机图尺寸 | 低 | 已定义为常量 |
| `views/monitor/SatelliteComparePage.vue` | 296-297 | `width = 1024, height = 512` 导出图片尺寸 | 中 | 应提取为命名常量 `EXPORT_IMAGE_WIDTH/HEIGHT` |
| `views/ai/ModelManagePage.vue` | 240, 267, 408 | `w = 280, h = 60/50, pad = 4` 迷你图尺寸 | 低 | 部分已提取，部分散落 |
| `views/monitor/ZoneDashboardPage.vue` | 168-171 | `max = 100, width = 540, height = 180` | 中 | 散落的魔法数字，建议提取为常量 |

### 4.3 业务逻辑中的魔法数字

| 文件 | 行号 | 硬编码内容 | 严重程度 | 修复建议 |
|------|------|-----------|----------|----------|
| `views/device/DevicesPage.vue` | 200, 205 | `> 50 ? ... : > 20 ? ...` AI 评分阈值（50/20） | 中 | 风险等级阈值应定义为常量或从后端获取 |
| `views/device/DeviceDetailPage.vue` | 136, 141 | 同样的 50/20 阈值 | 中 | 同上，且与 DevicesPage 重复 |
| `views/monitor/SatelliteComparePage.vue` | 85 | `threshold: 30` 变化检测阈值 | 中 | 应提取为 `DEFAULT_CHANGE_THRESHOLD` |
| `views/monitor/SatelliteComparePage.vue` | 87 | `maxCloud: 15` 最大云量 | 中 | 应提取为 `DEFAULT_MAX_CLOUD_COVERAGE` |
| `views/settings/SettingsPage.vue` | 289-292 | 密码强度评分逻辑（长度>=8, 大小写, 数字+特殊） | 低 | 密码策略硬编码，应与后端一致 |
| `views/system/BackupRecoveryPage.vue` | 180 | `p = 10; p <= 100; p += 15` 模拟进度步长 | 低 | mock 数据 |
| `services/dashboardApi.js` | 29 | `_latency = 240` mock 延迟 | 低 | mock 数据 |

### 4.4 列表 / 日志上限

| 文件 | 行号 | 硬编码内容 | 严重程度 | 修复建议 |
|------|------|-----------|----------|----------|
| `views/hazard/TaskDetailPage.vue` | 36 | `LOG_MAX = 200` | 低 | 已命名，可接受 |
| `views/ai/ModelManagePage.vue` | 155 | `JOB_LOG_MAX = 300` | 低 | 已命名，可接受 |
| `services/_architecture/core/geocodeService.js` | 176 | `limit=8` 搜索结果上限 | 中 | 应提取为 `GEOCODE_SEARCH_LIMIT` 常量 |

---

## 5. 分页大小 / 超时时间 / 轮询间隔等配置写死

### 5.1 轮询间隔（realtimeService）

| 文件 | 行号 | 硬编码内容 | 严重程度 | 修复建议 |
|------|------|-----------|----------|----------|
| `services/_architecture/core/realtimeService.js` | 13 | `pollingInterval = 15000` (15s) | 高 | 应从环境变量或配置读取，支持运行时调整 |
| `services/_architecture/core/realtimeService.js` | 14 | `warningInterval = 10000` (10s) | 高 | 同上 |
| `services/_architecture/core/realtimeService.js` | 15 | `personnelInterval = 30000` (30s) | 高 | 同上 |
| `services/_architecture/core/realtimeService.js` | 20 | `fallbackPollingInterval = 60000` (60s) | 中 | WS 兜底轮询间隔，应可配置 |
| `services/_architecture/core/realtimeService.js` | 21 | `fallbackWarningInterval = 40000` (40s) | 中 | 同上 |
| `services/_architecture/core/realtimeService.js` | 22 | `fallbackPersonnelInterval = 120000` (120s) | 中 | 同上 |
| `services/_architecture/core/realtimeService.js` | 582 | `WS_SILENCE_THRESHOLD_MS = 90_000` (90s) | 中 | WS 静默检测阈值，应可配置 |

### 5.2 健康检查 / 重连间隔

| 文件 | 行号 | 硬编码内容 | 严重程度 | 修复建议 |
|------|------|-----------|----------|----------|
| `services/_architecture/core/runtime.js` | 25 | `REPROBE_INTERVAL_MS = 30_000` (30s) | 中 | 后端复检间隔，应可配置 |
| `services/_architecture/core/runtime.js` | 86 | `AbortSignal.timeout(8000)` (8s) | 中 | 健康检查超时，应提取为常量 |
| `services/_architecture/core/healthService.js` | 25 | `probeIntervalMs = 30_000` (30s) | 中 | 健康检查间隔，应可配置 |

### 5.3 Sentinel / AI 任务轮询

| 文件 | 行号 | 硬编码内容 | 严重程度 | 修复建议 |
|------|------|-----------|----------|----------|
| `services/_architecture/core/sentinelService.js` | 122 | `timeoutMs ?? 5 * 60 * 1000` (5min) | 中 | 任务超时默认值，应可配置 |
| `services/_architecture/domain/satelliteCompareService.js` | 120 | `timeoutMs ?? 5 * 60 * 1000` (5min) | 中 | 同上 |
| `services/_architecture/domain/satelliteCompareService.js` | 278-279 | `intervalMs = 1500`, `timeoutMs = 5 * 60 * 1000` | 中 | 差异计算轮询间隔和超时 |
| `views/OverviewPage.vue` | 904 | `{ timeoutMs: 5 * 60 * 1000 }` | 中 | 总览页 Sentinel 任务超时 |

### 5.4 分页大小写死

| 文件 | 行号 | 硬编码内容 | 严重程度 | 修复建议 |
|------|------|-----------|----------|----------|
| `views/ai/DisasterForecastPage.vue` | 69 | `pageSize: 200` | 中 | 应使用统一的分页常量或从配置读取 |
| `views/ai/AiInsightPage.vue` | 152 | `pageSize: 200` | 中 | 同上 |
| `views/ai/DamageAssessmentPage.vue` | 55 | `pageSize: 200` | 中 | 同上 |
| `views/hazard/FieldInspectionPage.vue` | 58 | `pageSize: 100` | 中 | 同上 |
| `services/mock/empty.js` | 24 | `paged(size = 20)` | 低 | mock 默认分页大小 |

### 5.5 UI 动画 / 提示超时

| 文件 | 行号 | 硬编码内容 | 严重程度 | 修复建议 |
|------|------|-----------|----------|----------|
| `layouts/DashboardLayout.vue` | 221 | `2600` ms toast 显示时长 | 低 | 可接受，但建议提取为常量 |
| `stores/auth.js` | 391 | `2000` ms 重定向延迟 | 低 | 同上 |
| `views/warning/WarningResolvePage.vue` | 100 | `900` ms 跳转延迟 | 低 | 同上 |
| `views/ai/SocialMediaPage.vue` | 270 | `600` ms 模拟加载 | 低 | mock |
| `views/system/HelpCenterPage.vue` | 47, 50 | `800` / `3000` ms | 低 | mock |
| `views/system/NotificationChannelsPage.vue` | 286, 303 | `1200` / `600` ms | 低 | mock |
| `components/confirmation/SignForm.vue` | 12 | `[12, 24, 48, 72]` 超时选项（小时） | 中 | 会签超时选项应可配置 |
| `composables/useOverviewSearch.js` | 19 | `DEBOUNCE_MS = 280` | 低 | 已命名常量，可接受 |
| `views/system/MessageCenterPage.vue` | 127 | `300` ms 搜索防抖 | 低 | 建议提取为常量 |
| `views/system/EventCenterPage.vue` | 262 | `200` ms 过滤防抖 | 低 | 同上 |

### 5.6 缓存 TTL

| 文件 | 行号 | 硬编码内容 | 严重程度 | 修复建议 |
|------|------|-----------|----------|----------|
| `views/OverviewPage.vue` | 76 | `USER_LOCATION_TTL_MS = 24 * 60 * 60 * 1000` (24h) | 低 | 已命名，可接受 |
| `services/_architecture/domain/weatherService.js` | 71 | `ttl = 5 * 60 * 1000` (5min) | 中 | 气象数据缓存 TTL，应可配置 |
| `data/dashboardData.js` | 142 | `BADGE_REFRESH_INTERVAL_MS = 60_000` (1min) | 低 | 已命名，可接受 |

---

## 6. 硬编码的颜色值 / 样式值

### 6.1 WelcomePage 大量内联颜色（最严重）

**文件**: `views/WelcomePage.vue`
**数量**: 100+ 处硬编码颜色值

| 代表性行号 | 硬编码颜色 | 使用场景 | 严重程度 |
|-----------|-----------|----------|----------|
| 593 | `#3caa82` | 状态指示器-已完成 | 高 |
| 594 | `#deb154` | 状态指示器-进行中 | 高 |
| 595 | `#e07859` | 状态指示器-逾期 | 高 |
| 815-816 | `#edf7f3`, `#17333b` | 页面背景/文字 | 高 |
| 869-870 | `#0f4e47`, `#d8f4dd`, `#b7e6cb` | 欢迎卡片 | 高 |
| 920-922 | `#2b7778`, `#3fae97`, `#246f70` | 按钮渐变 | 高 |
| 1082-1084 | `#e9855f`, `#deb154`, `#63d19c` | 状态圆点 | 高 |
| 1216-1218 | `#e07859`, `#deb154`, `#6a9fd6` | 地图标记 | 高 |

**修复建议**: WelcomePage 是颜色硬编码的重灾区，应建立完整的设计系统（Design System），使用 CSS 变量（如 `--color-primary`, `--color-success`）统一管理。

### 6.2 设备 / AI 健康相关颜色

| 文件 | 行号 | 硬编码颜色 | 严重程度 | 修复建议 |
|------|------|-----------|----------|----------|
| `views/device/DevicesPage.vue` | 200, 205 | `#d57252`, `#c69132`, `#4c8b87` | 中 | AI 风险等级颜色应统一为语义化变量 |
| `views/device/DeviceDetailPage.vue` | 136, 141, 146 | 同上 + `#4f8f99`, `#6dbfa5` | 中 | 同上 |
| `views/device/OtaManagePage.vue` | 467 | `#e7f0ed`, `#2a8154` | 低 | 状态标签颜色 |

### 6.3 资源调度 / 预警相关颜色

| 文件 | 行号 | 硬编码颜色 | 严重程度 | 修复建议 |
|------|------|-----------|----------|----------|
| `views/warning/ResourceDispatchPage.vue` | 294 | `#c7553b`, `#2a8154`, `#9baead` | 中 | 资源状态颜色应使用状态枚举颜色映射 |
| `views/warning/ResourceDispatchPage.vue` | 354 | `#4f8f99`, `#d9c040` | 中 | 路径类型标签颜色 |
| `views/warning/ResourceDispatchPage.vue` | 386 | `#c7553b`, `#d7a546`, `#5d95c1`, `#9baead` | 中 | 优先级颜色（P0/P1/P2） |

### 6.4 数据分析页颜色

| 文件 | 行号 | 硬编码颜色 | 严重程度 | 修复建议 |
|------|------|-----------|----------|----------|
| `views/data/AnalyticsPage.vue` | 252 | `#4f8f99` | 低 | 柱状图颜色 |
| `views/data/AnalyticsPage.vue` | 424-430 | `#d9c040`, `#e06060` | 中 | 误报/漏报颜色，应使用语义化变量 |
| `views/data/AnalyticsPage.vue` | 460 | `#4f8f99` | 低 | 模型名称图例色 |
| `views/data/DataCatalogPage.vue` | 383-398 | `#4f8f99`, `#6dbfa5`, `#e0a050`, `#d9c040` | 中 | 数据资产进度条颜色 |

### 6.5 通知渠道颜色

| 文件 | 行号 | 硬编码颜色 | 严重程度 | 修复建议 |
|------|------|-----------|----------|----------|
| `views/system/NotificationChannelsPage.vue` | 61 | `#6366f1` (短信) | 中 | 渠道图标颜色应可配置 |
| `views/system/NotificationChannelsPage.vue` | 75 | `#22c55e` (APP推送) | 中 | 同上 |
| `views/system/NotificationChannelsPage.vue` | 89 | `#3b82f6` (邮件) | 中 | 同上 |
| `views/system/NotificationChannelsPage.vue` | 104 | `#f59e0b` (LED屏) | 中 | 同上 |
| `views/system/NotificationChannelsPage.vue` | 117 | `#8b5cf6` (Webhook) | 中 | 同上 |
| `views/system/NotificationChannelsPage.vue` | 130 | `#ef4444` (声光报警) | 中 | 同上 |

### 6.6 OverviewPage 颜色数组

| 文件 | 行号 | 硬编码颜色 | 严重程度 | 修复建议 |
|------|------|-----------|----------|----------|
| `views/OverviewPage.vue` | 2509, 2523 | `['#4f8f99','#e0a050','#d9c040','#6dbfa5','#7a8d89','#b27f9a']` | 中 | 分类饼图颜色序列，应提取为设计系统调色板常量 |

### 6.7 设备地图设备类型颜色

| 文件 | 行号 | 硬编码颜色 | 严重程度 | 修复建议 |
|------|------|-----------|----------|----------|
| `views/monitor/DeviceMapPage.vue` | 437 | `'#7c3aed'` (无人机) | 中 | 设备类型颜色应集中定义 |
| `views/monitor/DeviceMapPage.vue` | 438 | `'#ea580c'` (车辆) | 中 | 同上 |
| `views/monitor/DeviceMapPage.vue` | 439 | `'#16a34a'` (手持) | 中 | 同上 |

---

## 7. 条件判断中写死的字符串值

### 7.1 状态字符串（最常见）

| 文件 | 行号 | 硬编码字符串 | 使用场景 | 严重程度 | 修复建议 |
|------|------|-------------|----------|----------|----------|
| `views/hazard/TaskDetailPage.vue` | 78 | `'SUCCESS'`, `'FAILED'`, `'TERMINATED'`, `'TIMEOUT'` | 任务完成状态判断 | 中 | 提取为 `TASK_STATUS` 枚举常量 |
| `views/device/OtaManagePage.vue` | 186-189 | `"running"`, `"failed"`, `"success"` | OTA 任务状态过滤 | 中 | 提取为 `OTA_STATUS` 枚举 |
| `views/device/OtaManagePage.vue` | 205, 438 | `"all"`, `"firmware"` | 标签页/过滤判断 | 低 | 可接受，但建议用常量 |
| `views/mobile/MobileCommandPage.vue` | 347, 352 | `'draft'`, `'published'` | 预警状态判断 | 中 | 应使用 `WARNING_STATUS` 枚举 |
| `views/warning/WarningsPage.vue` | 37 | `['已关闭', '已忽略', '已解除']` | 归档状态列表 | 中 | 中文状态值，应使用英文编码 + 字典映射 |
| `views/monitor/HistoryHazardsPage.vue` | 85-87 | `"高风险"`, `"关注"`, `"已闭环"`, `"已处置"` | 灾害等级/状态过滤 | 中 | 中文值直接用于判断，应使用英文编码 |
| `views/device/DevicesPage.vue` | 17-22 | `'在线'`, `'离线'`, `'CRITICAL'`, `'WARN'`, `'ATTENTION'` | 设备状态/AI健康等级 | 中 | 中文 + 英文混合，应统一为枚举 |
| `views/ai/SocialMediaPage.vue` | 256, 259 | `"positive"`, `"negative"` | 情感判断 | 中 | 应提取为 `SENTIMENT` 枚举 |

### 7.2 模式 / 类型字符串

| 文件 | 行号 | 硬编码字符串 | 使用场景 | 严重程度 | 修复建议 |
|------|------|-------------|----------|----------|----------|
| `views/monitor/SatelliteComparePage.vue` | 90, 213, 221 | `'swipe'`, `'flicker'`, `'sidebyside'` | 对比模式 | 中 | 提取为 `COMPARE_MODE` 常量 |
| `views/monitor/SatelliteComparePage.vue` | 95 | `'before'`, `'after'` | 闪烁切换侧 | 低 | 可接受 |
| `views/monitor/SatelliteComparePage.vue` | 132, 141 | `'custom'` | 自定义预设判断 | 低 | 可接受 |
| `views/monitor/RealTimeMapPage.vue` | 137 | `"satellite"`, `"terrain"` | 底图样式判断 | 中 | 应提取为 `MAP_STYLE` 枚举 |
| `views/monitor/RealTimeMapPage.vue` | 142-146 | `"hazard"`, `"device"`, `"warning"`, `"aiMonitor"`, `"weather"` | 图层显隐判断 | 中 | 图层 key 字符串，建议提取为常量 |
| `views/monitor/DeviceMapPage.vue` | 111-112 | `'固定'`, `'移动'` | 移动性过滤 | 中 | 中文值，应使用英文编码 |
| `views/monitor/DeviceMapPage.vue` | 437-439 | `'drone'`, `'vehicle'`, `'handheld'` | 设备类型判断 | 中 | 应提取为 `DEVICE_TYPE` 枚举 |
| `views/ai/RuleEnginePage.vue` | 55, 68, 75, 89 | `'list'`, `'edit'`, `'test'` | 视图模式 | 低 | 可接受，建议提取为常量 |

### 7.3 runtime 模式字符串

| 文件 | 行号 | 硬编码字符串 | 使用场景 | 严重程度 | 修复建议 |
|------|------|-------------|----------|----------|----------|
| `services/_architecture/core/runtime.js` | 31-35 | `"backend"`, `"mock"`, `"probing"` | 运行时模式 | 中 | 已集中定义，但建议导出为常量对象 |
| `stores/realtime.js` | 47-51 | `'idle'`, `'ws'`, `'polling'`, `'degraded'`, `'down'` | 实时连接状态 | 中 | 状态字符串应提取为枚举 |

### 7.4 角色 / 权限字符串

| 文件 | 行号 | 硬编码字符串 | 使用场景 | 严重程度 | 修复建议 |
|------|------|-------------|----------|----------|----------|
| `services/mock/system.js` | 19, 28, 37, 46, 55 | `"admin"`, `"attendant"`, `"expert"`, `"maintenance"` | 角色编码 | 中 | 应定义 `USER_ROLE` 枚举常量 |
| `services/mock/runtime.js` | 484-488 | `"值班员"`, `"现场运维"`, `"应急指挥"`, `"领域专家"` | 人员角色（中文） | 中 | 中文角色名直接使用，应通过字典映射 |

---

## 8. localhost / 127.0.0.1 / 硬编码 IP 地址

### 8.1 localhost 后端地址（fallback 默认值）

| 文件 | 行号 | 硬编码内容 | 严重程度 | 修复建议 |
|------|------|-----------|----------|----------|
| `services/_architecture/core/runtime.js` | 22 | `http://localhost:8080/api` | 高 | 已用环境变量 fallback，需确保生产构建时正确配置 |
| `services/_architecture/domain/aiApi.js` | 9 | `http://localhost:8080/api` | 高 | 同上，且重复定义 |
| `services/_architecture/domain/deviceMapService.js` | 19 | `http://localhost:8080/api` | 高 | 同上，且重复定义 |
| `services/_architecture/domain/aiApi.js` | 305 | `http://localhost:8001/api/ai` | 高 | AI 服务 fallback |
| `services/_architecture/core/sentinelService.js` | 34 | `http://localhost:8001/api/ai` | 高 | 同上 |
| `services/_architecture/domain/satelliteCompareService.js` | 25 | `http://localhost:8001/api/ai` | 高 | 同上 |
| `services/_architecture/domain/driftMonitorService.js` | 9 | `http://localhost:8000/api` | 高 | 端口不一致 |
| `services/_architecture/domain/multiModalService.js` | 9 | `http://localhost:8000/api` | 高 | 同上 |

### 8.2 UI 展示用 localhost

| 文件 | 行号 | 硬编码内容 | 严重程度 | 修复建议 |
|------|------|-----------|----------|----------|
| `views/settings/SettingsPage.vue` | 265, 276 | `http://localhost:8080/api` | 中 | 服务状态展示，应从 runtime 动态读取 |

### 8.3 内网 IP 地址（mock 数据）

| 文件 | 行号 | 硬编码内容 | 严重程度 | 修复建议 |
|------|------|-----------|----------|----------|
| `views/system/NotificationChannelsPage.vue` | 107 | `http://192.168.1.100:8080` (placeholder) | 低 | LED 控制器地址示例，仅占位符 |
| `views/system/NotificationChannelsPage.vue` | 133 | `192.168.1.200` (placeholder) | 低 | 声光报警设备 IP 示例 |
| `views/system/NotificationChannelsPage.vue` | 197 | `http://192.168.1.100:8080` (mock 数据) | 低 | mock 渠道配置 |
| `views/system/NotificationChannelsPage.vue` | 225 | `192.168.1.200` (mock 数据) | 低 | mock 渠道配置 |
| `services/mock/system.js` | 109, 118, 127, 136, 145 | `10.0.0.21`, `10.0.0.34`, `10.0.0.42`, `10.0.0.18` | 低 | 服务器 IP mock 数据 |

### 8.4 .env 配置文件

| 文件 | 行号 | 内容 | 说明 |
|------|------|------|------|
| `src/.env` | 5 | `VITE_API_BASE_URL=http://localhost:8080/api` | 开发环境默认值，正常 |
| `src/.env` | 18 | `VITE_AI_SERVICE_BASE_URL=http://localhost:8000/api/ai` | 开发环境默认值，正常 |
| `src/.env` | 21 | `VITE_WS_URL=ws://localhost:8080/ws` | 开发环境默认值，正常 |

**注意**: .env 中的 localhost 是开发环境预期配置，但代码中的 fallback 默认值应与 .env 一致。当前发现 `VITE_AI_SERVICE_BASE_URL` 在 .env 中是 8000 端口的 `/api/ai`，但部分代码中 fallback 为 8001 端口，存在不一致。

---

## 总结与优先级建议

### 高优先级（立即修复）

1. **API 地址分散定义**: runtime.js、aiApi.js、deviceMapService.js 各自定义默认 API_BASE，应统一由 runtime.js 导出
2. **AI 服务端口不一致**: 8000 vs 8001，环境变量名也不一致（`VITE_AI_SERVICE_URL` vs `VITE_AI_SERVICE_BASE_URL` vs `VITE_AI_SERVICE_BASE`）
3. **地图中心点硬编码**: `[107.21, 30.61]` 在至少 4 处重复，应从用户偏好或后端配置读取
4. **实时轮询间隔写死**: realtimeService 中 6 个间隔常量（15s/10s/30s/60s/40s/120s）应可配置

### 中优先级（迭代修复）

5. **第三方服务 URL 硬编码**: 高德/天地图/OpenStreetMap/气象服务等 URL 应集中配置
6. **状态字符串散落各处**: 任务状态、设备状态、预警状态等应定义统一枚举
7. **AI 风险阈值硬编码**: 50/20 等阈值在多处重复，应集中管理
8. **颜色值大量硬编码**: WelcomePage 等页面颜色值应迁移到 CSS 变量 / 设计系统
9. **分页大小不统一**: 200/100/20 等不同页面各自定义

### 低优先级（长期优化）

10. **mock 数据中的硬编码**: 后端连通后自然消除
11. **物理常量命名**: 地球半径、偏心率等虽为常量，但命名可更清晰
12. **UI 动画延迟**: 各类 setTimeout 毫秒数可提取为常量

### 改进建议

1. 建立 `src/config/` 目录，集中管理所有配置常量（API 地址、超时时间、阈值等）
2. 建立 `src/constants/` 目录，定义业务枚举（状态、角色、类型等）
3. 建立设计系统 CSS 变量，统一颜色、间距、字体等样式 token
4. 地图默认中心点、关注区域等应从后端"系统配置"接口获取
5. 使用 ESLint 规则（如 `no-magic-numbers`、`no-restricted-properties`）在开发阶段拦截硬编码问题
