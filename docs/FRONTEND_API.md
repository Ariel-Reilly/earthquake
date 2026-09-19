# 前端功能与接口对照表

> 本文档是 **DisasterGuard 前端功能** 与 **后端 REST API** 的完整对照参考。
> 由 `src/router/index.js` 路由定义、`src/services/dashboardApi.js` 服务层定义及 `backend/src/main/java/cn/disasterguard/**/*Controller.java` 控制器层三重交叉验证。
>
> **更新时间**: 2026-09-16
> **接口 Base URL**: `http://localhost:8080/api`（可通过 `VITE_API_BASE_URL` 配置）
> **认证方式**: JWT Bearer Token（除 `/api/auth/login`、`/api/system/health` 外均需登录）

---

## 一、认证模块

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 登录 | `/` | `/api/auth/login` | POST | `{username, password, role}` → `{token, user}` |
| 登出 | 全局 | `/api/auth/logout` | POST | 清除服务端会话 |
| 修改密码 | `/dashboard/account-security` | `/api/auth/change-password` | POST | `{oldPassword, newPassword}` |
| WebSocket 票据 | 全局实时 | `/api/auth/ws-ticket` | GET | 为已认证用户签发一次性 WebSocket 票据 |

---

## 二、监测与态势

### 2.1 总览首页

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 总览指标卡片 | `/dashboard` | `/api/dashboard/overview` | GET | 灾害点/设备/预警/应急统计 |
| 图表数据 | `/dashboard` | `/api/dashboard/charts` | GET | 趋势图、ECharts 数据 |
| 分析数据 | `/dashboard` | `/api/dashboard/analytics` | GET | 含灾害点列表 |
| 导出总览报表 | `/dashboard` | `/api/dashboard/overview/export` | POST | Excel 下载 |
| 实时数据同步 | `/dashboard` | WebSocket `/ws` | WS | 设备/灾害点/预警实时推送 |
| 实时数据同步（轮询兜底） | `/dashboard` | 多端点轮询 | GET | `VITE_REALTIME_POLL_INTERVAL` |
| 预警高频轮询 | `/dashboard` | `/api/warnings/preview` | GET | `VITE_WARNING_POLL_INTERVAL`，仅活跃预警 |
| 人员位置 | `/dashboard` | `/api/personnel/positions` | GET | 值守人员实时坐标 |
| AI 实时监测 | `/dashboard` AI 面板 | `/api/hazards/ai-monitor` | GET | 按 `discovered_at DESC` 排序，最多 50 条 |

### 2.2 实时地图

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 灾害点视口 | `/dashboard/real-time-map` | `/api/hazards/viewport` | GET | `?bbox=&zoom=&keyword=&level=&type=` |
| 设备视口 | `/dashboard/real-time-map` | `/api/devices/viewport` | GET | `?bbox=&zoom=&state=&keyword=` |
| 预警视口 | `/dashboard/real-time-map` | `/api/warnings/viewport` | GET | `?bbox=&zoom=&keyword=&status=` |
| 实时同步（WS） | `/dashboard/real-time-map` | `/ws` | WS | 设备增量推送 |

### 2.3 设备地图

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 全量设备地图 | `/dashboard/device-map` | `/api/device-map` | GET | 全量设备坐标 |
| 设备轨迹查询 | `/dashboard/device-map` | `/api/device-map/trajectory/{deviceCode}` | GET | `?from=&to=` |
| 设备轨迹回放 | `/dashboard/device-map` | `/api/devices/{id}/track` | GET | 同上（旧路径兼容） |

---

## 三、灾害点管理

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 灾害点列表 | `/dashboard/hazards` | `/api/hazards` | GET | 分页 `?page=&size=&keyword=&level=&type=` |
| 灾害点详情 | `/dashboard/hazards/:id` | `/api/hazards/{id}` | GET | 完整详情 |
| 新增灾害点 | `/dashboard/hazards` | `/api/hazards` | POST | |
| 更新灾害点 | `/dashboard/hazards/:id` | `/api/hazards/{id}` | PUT | |
| 删除灾害点 | `/dashboard/hazards/:id` | `/api/hazards/{id}` | DELETE | |
| 导出灾害点 | `/dashboard/hazards` | `/api/hazards/export` | POST | Excel 下载 |
| GeoJSON 导出 | `/dashboard/hazards` | `/api/hazards/geojson` | GET | |
| 灾害关联事件 | `/dashboard/hazards/:id` | 并发请求 | GET | `hazards/{id}` + `/warnings` + `/emergency` 客户端 join |
| AI 综合分析（全部） | `/dashboard/hazards` | `/api/ai/comprehensive-analysis/all` | GET | 每 5 分钟 scheduler 刷新 |
| AI 综合分析（单点缓存） | `/dashboard/hazards/:id` | `/api/ai/comprehensive-analysis/cached?hazardCode=` | GET | 不触发推理 |
| AI 综合分析（强制刷新） | `/dashboard/hazards` | `/api/ai/comprehensive-analysis/refresh` | POST | 全部重算 |
| AI 综合分析（单点刷新） | `/dashboard/hazards/:id` | `/api/ai/comprehensive-analysis/refresh-one?hazardCode=` | POST | 单点重算 |
| 附件上传 | 灾害详情页 | `/api/attachments` | POST | `multipart/form-data` |
| 附件列表 | 灾害详情页 | `/api/attachments?businessType=hazard&businessCode=` | GET | |

---

## 四、巡检任务

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 任务列表 | `/dashboard/ai-tasks` | `/api/tasks` | GET | 分页 `?page=&size=&status=&hazardCode=` |
| 任务详情 | `/dashboard/ai-tasks/:id` | `/api/tasks/{id}` | GET | |
| 创建任务 | `/dashboard/ai-tasks` | `/api/tasks` | POST | |
| 更新任务 | `/dashboard/ai-tasks/:id` | `/api/tasks/{id}` | PUT | |
| 更新任务状态 | `/dashboard/ai-tasks/:id` | `/api/tasks/{id}/status` | PATCH | `{status}` |
| 删除任务 | `/dashboard/ai-tasks/:id` | `/api/tasks/{id}` | DELETE | |
| 导出任务 | `/dashboard/ai-tasks` | `/api/tasks/export` | POST | |
| 任务运行时指标 | AI 任务页 | `/api/tasks/{id}/runtime` | GET | CPU/GPU/内存/时长/重试次数 |
| 任务执行日志 | AI 任务页 | `/api/tasks/{id}/log?since=` | GET | 增量游标拉取 |
| 重试任务 | AI 任务页 | `/api/tasks/{id}/retry` | POST | |
| 终止任务 | AI 任务页 | `/api/tasks/{id}/terminate` | POST | |

---

## 五、预警与处置

### 5.1 预警列表 & 详情

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 预警列表 | `/dashboard/warnings` | `/api/warnings` | GET | 分页 `?page=&size=&status=&level=` |
| 预警统计（顶栏红点） | 全局 | `/api/warnings/stats` | GET | 按状态分组计数 |
| 预警详情 | `/dashboard/warnings/:id` | `/api/warnings/{id}` | GET | |
| 创建预警 | `/dashboard/warnings/create` | `/api/warnings` | POST | |
| 更新预警 | `/dashboard/warnings/:id` | `/api/warnings/{id}` | PUT | |
| 确认预警 | `/dashboard/warnings/:id` | `/api/warnings/{id}/confirm` | POST | |
| 派发预警 | `/dashboard/warnings/:id` | `/api/warnings/{id}/dispatch` | POST | |
| 关闭预警 | `/dashboard/warnings/:id` | `/api/warnings/{id}/close` | POST | `{reason}` |
| 驳回预警 | `/dashboard/warnings/:id` | `/api/warnings/{id}/dismiss` | POST | |
| 解除预警（自动） | `/dashboard/warnings/:id` | `/api/warnings/{id}/resolve` | POST | |
| 解除预警预览 | `/dashboard/warnings/:id/resolve` | `/api/warnings/{id}/resolve/preview` | GET | 预览通知文案 |
| 解除范围查询 | `/dashboard/warnings/:id/resolve` | `/api/warnings/{id}/resolve/scope` | GET | 行政区域下钻 |
| 导出预警报表 | `/dashboard/warnings` | `/api/warnings/export` | POST | |

### 5.2 预警发布与回执

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 发布汇总 | `/dashboard/warnings/:id/delivery` | `/api/warnings/{id}/delivery/summary` | GET | 应发/已送达/已阅读/已确认 |
| 发布明细记录 | `/dashboard/warnings/:id/delivery` | `/api/warnings/{id}/delivery/records` | GET | `?channel=&status=` |
| 重发预警 | `/dashboard/warnings/:id/delivery` | `/api/warnings/{id}/delivery/redeliver` | POST | `{channel, targetIds}` |

### 5.3 应急事件

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 应急事件列表 | `/dashboard/emergency` | `/api/emergency` | GET | `?size=200` |
| 应急事件详情 | `/dashboard/emergency` | `/api/emergency/{id}` | GET | |
| 关闭应急事件 | `/dashboard/emergency` | `/api/emergency/{id}/close` | POST | |
| 导出应急记录 | `/dashboard/emergency` | `/api/emergency/export` | POST | |

---

## 六、AI 智能分析

### 6.1 AI 助手 & 综合分析

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| AI 综合分析列表 | `/dashboard/ai-insight` | `/api/ai/comprehensive-analysis/all` | GET | 落库数据，每 5 分钟刷新 |
| AI 综合分析（单点） | `/dashboard/ai-insight` | `/api/ai/comprehensive-analysis` | GET | `?hazardCode=` 实时推理 |
| 强制刷新全部 | `/dashboard/ai-insight` | `/api/ai/comprehensive-analysis/refresh` | POST | 管理端"一键分析" |
| AI 聊天入口 | AI 助手组件 | `/api/ai/chat` | POST | 关键词分发 |
| 提交 AI 反馈 | `/dashboard/ai-insight` | `/api/ai/feedback` | POST | 反馈混淆矩阵 |
| 反馈统计 | `/dashboard/ai-insight` | `/api/ai/feedback/stats` | GET | 直接调 Python 端点 |

### 6.2 AI 图像识别

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 图像识别（巡检照片） | 巡检上报页 | `/api/ai/detect` | POST | `multipart/file` + `modelType` |

### 6.3 时序预测 & 异常检测

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 时序预测（显式） | 设备详情页 | `/api/ai/forecast` | POST | `{deviceCode, metric, horizon, history}` |
| 时序预测（自动） | 灾害详情页 | `/api/ai/forecast/auto` | GET | 自动从 DB 拉取设备历史 |
| 异常检测 | 设备详情页 | `/api/ai/anomaly` | POST | `{deviceCode, data}` |
| 融合风险评分（显式） | 预警页 | `/api/ai/fusion-risk` | POST | `{hazardCode, factors}` |
| 融合风险评分（自动） | 灾害详情页 | `/api/ai/fusion-risk/auto` | GET | 自动聚合真实因子 |

### 6.4 灾害评估

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 单独灾害评估 | 灾害详情页 | `/api/ai/disaster-assessment` | GET | `?hazardCode=` 基于地理+天气 |
| 灾害评估字典自检 | AI 结果页 | `/api/ai/disaster-assessment/status` | GET | |
| 全国独立测算 | `/dashboard/damage-assessment` | `/api/ai/disaster-assessment/independent` | POST | 不依赖监测设备 |

### 6.5 AI 模型管理

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 模型版本库 | `/dashboard/ai-models` | `/api/ai/models` | GET | `?family=&status=` |
| 模型版本详情 | `/dashboard/ai-models` | `/api/ai/models/{versionId}` | GET | 含特征重要性、训练曲线 |
| 模型族统计 | `/dashboard/ai-models` | `/api/ai/models/stats` | GET | 在线数/推理次数/F1 |
| 训练任务列表 | `/dashboard/ai-models` | `/api/ai/models/training-jobs` | GET | |
| 新建训练任务 | `/dashboard/ai-models` | `/api/ai/models/training-jobs` | POST | |
| 启动训练 | `/dashboard/ai-models` | `/api/ai/models/training-jobs/{id}/start` | POST | |
| 停止训练 | `/dashboard/ai-models` | `/api/ai/models/training-jobs/{id}/stop` | POST | |
| 训练日志 | `/dashboard/ai-models` | `/api/ai/models/training-jobs/{id}/log` | GET | |
| 激活模型版本 | `/dashboard/ai-models` | `/api/ai/models/{versionId}/activate` | POST | 设为生产版本 |
| 下线模型版本 | `/dashboard/ai-models` | `/api/ai/models/{versionId}` | DELETE | |
| AI 模型状态 | AI 页面 | `/api/ai/model/info` | GET | XGBoost 融合模型信息 |
| 重载 AI 模型 | AI 页面 | `/api/ai/model/reload` | POST | 热加载 |

### 6.6 AI 巡检报告

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| AI 巡检报告 | `/dashboard/ai-results` | `/api/analytics/inspection-report` | GET | `?days=&from=&to=` |

---

## 七、人工确认工作台

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 待确认队列 | `/dashboard/confirmation-workbench` | `/api/confirmation/queue` | GET | 按紧急度排序 |
| 待确认统计（顶栏红点） | 全局 | `/api/confirmation/queue/stats` | GET | |
| 认领任务 | `/dashboard/confirmation-workbench` | `/api/confirmation/queue/{id}/claim` | POST | `operator` |
| 转派任务 | `/dashboard/confirmation-workbench` | `/api/confirmation/queue/{id}/transfer` | POST | `{toUser, reason}` |
| 确认（落为正式灾害点） | `/dashboard/confirmation-workbench/:id` | `/api/confirmation/queue/{id}/confirm` | POST | |
| 驳回（归档） | `/dashboard/confirmation-workbench/:id` | `/api/confirmation/queue/{id}/reject` | POST | |

---

## 八、统一事件中心

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 统一事件列表 | `/dashboard/events` | `/api/event/list` | GET | `?type=&keyword=&state=` |
| 各状态计数统计 | `/dashboard/events` | `/api/event/stats` | GET | |
| 事件详情 | `/dashboard/events/:code` | `/api/event/{code}` | GET | |
| 触发状态流转 | `/dashboard/events/:code` | `/api/event/{code}/transition` | POST | 状态机推进 |
| 查询流转可能 | `/dashboard/events/:code` | `/api/event/{code}/transition/query` | POST | 预判可用动作 |

### 8.1 会签

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 发起会签 | `/dashboard/confirmation-sign` | `/api/events/{eventId}/sign` | POST | `{signers, mode, timeout, ...}` |
| 会签记录列表 | `/dashboard/confirmation-sign` | `/api/events/{eventId}/sign` | GET | |
| 会签人确认 | 会签详情页 | `/api/events/{eventId}/sign/{signId}/confirm` | PUT | `{opinion}` |
| 会签人驳回 | 会签详情页 | `/api/events/{eventId}/sign/{signId}/reject` | PUT | `{opinion}` |
| 转派事件 | 事件详情页 | `/api/events/{eventId}/transfer` | POST | `{toUser, reason}` |

---

## 九、预警相关辅助功能

### 9.1 路径规划

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 获取避障区域 | `/dashboard/route-planning` | `/api/path-planning/obstacles` | GET | 灾害影响多边形 |
| 计算路径（多方案） | `/dashboard/route-planning` | `/api/path-planning/calculate` | POST | fastest/safest/shortest |
| 导出 GeoJSON | `/dashboard/route-planning` | `/api/path-planning/export` | POST | |

### 9.2 资源调度

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 资源列表 | `/dashboard/resource-dispatch` | `/api/resources` | GET | `?page=&size=&type=&status=` |
| 资源指派 | `/dashboard/resource-dispatch` | `/api/resources/assign` | POST | |
| 路径规划（旧） | `/dashboard/resource-dispatch` | `/api/resources/route` | POST | 与 path-planning 并存 |

---

## 十、数据与设备

### 10.1 设备管理

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 设备列表 | `/dashboard/devices` | `/api/devices` | GET | 分页 |
| 设备详情 | `/dashboard/devices/:id` | `/api/devices/{id}` | GET | 含设备指标 |
| 创建设备 | `/dashboard/devices` | `/api/devices` | POST | |
| 更新设备 | `/dashboard/devices/:id` | `/api/devices/{id}` | PUT | |
| 删除设备 | `/dashboard/devices/:id` | `/api/devices/{id}` | DELETE | |
| 设备诊断 | `/dashboard/devices/:id` | `/api/devices/{id}/diagnose` | POST | |
| 导出设备报表 | `/dashboard/devices` | `/api/devices/export` | POST | |
| 设备轨迹 | `/dashboard/devices/:id` | `/api/devices/{id}/track` | GET | `?from=&to=` |

### 10.2 OTA 固件升级

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 固件包列表 | `/dashboard/devices/ota` | `/api/ota/firmwares` | GET | `?deviceType=&keyword=` |
| 上传固件 | `/dashboard/devices/ota` | `/api/ota/firmwares` | POST | `multipart/form-data` |
| 删除固件 | `/dashboard/devices/ota` | `/api/ota/firmwares/{firmwareId}` | DELETE | |
| 升级任务列表 | `/dashboard/devices/ota` | `/api/ota/tasks` | GET | `?status=&deviceId=` |
| 新建升级任务 | `/dashboard/devices/ota` | `/api/ota/tasks` | POST | |
| 取消升级任务 | `/dashboard/devices/ota` | `/api/ota/tasks/{taskId}/cancel` | POST | |
| 重试升级任务 | `/dashboard/devices/ota` | `/api/ota/tasks/{taskId}/retry` | POST | |
| 升级任务详情 | `/dashboard/devices/ota` | `/api/ota/tasks/{taskId}` | GET | 含步骤时间轴 |

### 10.3 数据源配置

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 数据源列表 | `/dashboard/data-sources` | `/api/datasources` | GET | |
| 切换数据源 | `/dashboard/data-sources` | `/api/datasources/{code}/toggle` | POST | `{enabled}` |
| 测试连接 | `/dashboard/data-sources` | `/api/datasources/{code}/test` | POST | |

### 10.4 数据同步监控

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 同步统计 | `/dashboard/data-sync` | `/api/sync/stats` | GET | 在线/异常/延迟 |
| 同步任务列表 | `/dashboard/data-sync` | `/api/sync/tasks` | GET | 含实时状态 |
| 同步历史 | `/dashboard/data-sync` | `/api/sync/history` | GET | `?from=&to=&task=&status=` |
| 完整性报告 | `/dashboard/data-sync` | `/api/sync/integrity` | GET | |
| 异常值告警列表 | `/dashboard/data-sync` | `/api/sync/anomalies` | GET | |
| 触发同步任务 | `/dashboard/data-sync` | `/api/sync/tasks/{id}/trigger` | POST | |
| 暂停同步任务 | `/dashboard/data-sync` | `/api/sync/tasks/{id}/pause` | POST | |
| 忽略异常值 | `/dashboard/data-sync` | `/api/sync/anomalies/{id}/ignore` | POST | `{reason}` |

---

## 十一、气象 & 遥感

### 11.1 气象数据（外部 NSMC/NMC）

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 卫星云图（中国区域） | `/dashboard/weather` | NSMC XML 直接获取 | - | `FY4A_AGRI_IMG_REGI_MTCC_GLL.xml` |
| 全圆盘卫星云图 | `/dashboard/weather` | NSMC XML 直接获取 | - | `FY4A_AGRI_IMG_DISK_MTCC_NOM.xml` |
| 全国雷达拼图 | `/dashboard/weather` | NMC PNG 直接获取 | - | 6 分钟更新 |
| 24h 降水量 | `/dashboard/weather` | NMC PNG 直接获取 | - | |
| 24h 积雪深度 | `/dashboard/weather` | NMC PNG 直接获取 | - | |
| 农业干旱监测 | `/dashboard/weather` | NMC PNG 直接获取 | - | |
| 气象产品（后端代理） | `/dashboard/weather` | `/api/weather/products` | GET | 后端缓存模式 |
| 单产品最新帧 | `/dashboard/weather` | `/api/weather/products/latest?kind=` | GET | |
| 强制刷新产品 | `/dashboard/weather` | `/api/weather/products/refresh` | POST | |
| 气象图例 | `/dashboard/weather` | `/api/weather/legends` | GET | |
| 图片代理（CORS 兜底） | `/dashboard/weather` | `/api/weather/image/{kind}` | GET | |

### 11.2 卫星遥感对比

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| Sentinel 覆盖范围 | `/dashboard/satellite-compare` | `/api/sentinel/coverage` | GET | `?bbox=` |

---

## 十二、巡检与现场

### 12.1 现场巡检

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| GPS 围栏校验 | `/dashboard/field-inspection` | `/api/inspection/gps-check` | POST | `{taskCode, hazardCode, lon, lat, allowedRadius, inspector}` |
| 提交巡检 | `/dashboard/field-inspection` | `/api/inspection/submit` | POST | `{taskCode, hazardCode, ...}` |
| 巡检上报示例 | 移动端联调 | `/api/inspection/example` | GET | 参考 payload |
| 按任务查巡检记录 | 任务详情页 | `/api/inspection/task/{taskCode}` | GET | |
| 按灾害点查巡检记录 | 灾害详情页 | `/api/inspection/hazard/{hazardCode}` | GET | |

### 12.2 人员管理

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 人员位置列表 | `/dashboard/personnel` | `/api/personnel/positions` | GET | |
| 位置上报（移动端） | 移动端 | `/api/personnel/positions/report` | POST | `{userId, lon, lat, taskCode, hazardCode, speed, heading}` |

---

## 十三、AI 设备健康评分

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 全量设备健康评分 | 设备列表页 | `/api/ai/device-health/all` | GET | 按 `healthScore` 升序 |
| 单设备健康评分 | 设备详情页 | `/api/ai/device-health` | GET | `?deviceCode=` |
| 强制刷新所有设备 | 设备管理页 | `/api/ai/device-health/refresh` | POST | |
| 强制刷新单台设备 | 设备详情页 | `/api/ai/device-health/refresh-one` | POST | `?deviceCode=` |

---

## 十四、区域专项大屏

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 区域 KPI | `/dashboard/zone/:kind?/:region?` | `/api/dashboard/zone/kpi` | GET | `?region=&kind=&range=` |
| 区域趋势 | `/dashboard/zone/:kind?/:region?` | `/api/dashboard/zone/trend` | GET | `?range=24h/7d/30d/...` |
| 区域重点灾害点 | `/dashboard/zone/:kind?/:region?` | `/api/dashboard/zone/top-hazards` | GET | |
| 区域关联预警 | `/dashboard/zone/:kind?/:region?` | `/api/dashboard/zone/related-warnings` | GET | |
| 区域关联应急 | `/dashboard/zone/:kind?/:region?` | `/api/dashboard/zone/related-emergencies` | GET | |
| 灾种/区域聚合 | `/dashboard/zone` | `/api/zones/summary` | GET | |
| 风险趋势 | `/dashboard/zone` | `/api/zones/risk-trend` | GET | |

---

## 十五、系统管理

### 15.1 用户与权限

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 用户列表 | `/dashboard/user-management` | `/api/users` | GET | 分页 `?page=&size=&keyword=&role=` |
| 创建用户 | `/dashboard/user-management` | `/api/users` | POST | |
| 更新用户 | `/dashboard/user-management` | `/api/users/{id}` | PUT | |
| 删除用户 | `/dashboard/user-management` | `/api/users/{id}` | DELETE | |
| 角色列表 | `/dashboard/user-management` | `/api/roles` | GET | |

### 15.2 审计日志

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 审计日志列表 | `/dashboard/audit-log` | `/api/audit/logs` | GET | `?page=&size=&type=&operator=&from=&to=` |

### 15.3 系统监控

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 系统监控数据 | `/dashboard/system-monitor` | `/api/system/monitor` | GET | CPU/内存/磁盘/DB 连接池 |
| 健康检查 | 内部 | `/api/system/health` | GET | Vite proxy → 后端健康状态 |

### 15.4 消息中心

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 消息列表 | `/dashboard/message-center` | `/api/messages` | GET | `?page=&size=&read=` |
| 消息统计（顶栏红点） | 全局 | `/api/messages/stats` | GET | |
| 标记已读 | `/dashboard/message-center` | `/api/messages/{id}/read` | POST | |
| 全部标已读 | `/dashboard/message-center` | `/api/messages/read-all` | POST | |

### 15.5 通知渠道 & 模板

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 通知渠道配置 | `/dashboard/notification-channels` | `/api/notification/channel-config` | GET/PUT | |
| 发送通知 | `/dashboard/notification-channels` | `/api/notification/send` | POST | |
| 发送记录 | `/dashboard/notification-channels` | `/api/notification/records` | GET | |
| 回执详情 | `/dashboard/notification-channels` | `/api/notification/receipts/{recordId}` | GET | |
| 测试发送 | `/dashboard/notification-channels` | `/api/notification/test` | POST | |
| 通知模板列表 | `/dashboard/notice-templates` | `/api/notice-templates` | GET | `?hazardType=&level=&channel=&status=` |
| 通知模板详情 | `/dashboard/notice-templates` | `/api/notice-templates/{id}` | GET | |
| 新建/更新模板 | `/dashboard/notice-templates` | `/api/notice-templates` | POST/PUT | |
| 删除模板 | `/dashboard/notice-templates` | `/api/notice-templates/{id}` | DELETE | |
| 切换模板启用 | `/dashboard/notice-templates` | `/api/notice-templates/{id}/toggle` | POST | |

---

## 十六、待办 & 审批中心

### 16.1 待办中心

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 待办汇总（顶栏红点） | 全局 | `/api/todo/summary` | GET | `{confirmation, approval, pendingWarnings, openEmergency}` |
| 待我确认 | `/dashboard/todo` | `/api/todo/confirmations` | GET | |
| 待我审批 | `/dashboard/todo` | `/api/todo/approvals` | GET | |
| 待发布预警 | `/dashboard/todo` | `/api/todo/warnings` | GET | |
| 待跟踪任务 | `/dashboard/todo` | `/api/todo/tasks` | GET | 含已派发任务和预警进度 |

### 16.2 审批中心

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 审批列表 | `/dashboard/approvals` | `/api/approvals` | GET | `?status=&type=&keyword=` |
| 审批统计 | `/dashboard/approvals` | `/api/approvals/stats` | GET | |
| 审批决策 | `/dashboard/approvals` | `/api/approvals/{id}/decide` | POST | `{decision:'approve'|'reject', comment}` |

---

## 十七、规则引擎

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 规则列表 | `/dashboard/rule-engine` | `/api/rules` | GET | |
| 规则详情 | `/dashboard/rule-engine` | `/api/rules/{id}` | GET | |
| 创建规则 | `/dashboard/rule-engine` | `/api/rules` | POST | |
| 更新规则 | `/dashboard/rule-engine` | `/api/rules/{id}` | PUT | |
| 删除规则 | `/dashboard/rule-engine` | `/api/rules/{id}` | DELETE | |
| 启用/停用规则 | `/dashboard/rule-engine` | `/api/rules/{id}/toggle` | PUT | |
| 测试规则 | `/dashboard/rule-engine` | `/api/rules/test` | POST | |
| 规则执行日志 | `/dashboard/rule-engine` | `/api/rules/{id}/logs` | GET | |
| 所有执行日志 | `/dashboard/rule-engine` | `/api/rules/logs` | GET | |
| 版本回滚 | `/dashboard/rule-engine` | `/api/rules/{id}/rollback` | POST | |
| 字段字典 | `/dashboard/rule-engine` | `/api/rules/fields` | GET | |
| 可用操作符 | `/dashboard/rule-engine` | `/api/rules/operators` | GET | |
| 刷新规则缓存 | `/dashboard/rule-engine` | `/api/rules/reload` | POST | |

---

## 十八、预案与报表

### 18.1 应急预案

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 预案列表 | `/dashboard/plans` | `/api/plans` | GET | `?hazardType=&level=&status=` |
| 预案详情 | `/dashboard/plans` | `/api/plans/{planId}` | GET | |
| 新建/更新预案 | `/dashboard/plans` | `/api/plans` | POST/PUT | |
| 删除预案 | `/dashboard/plans` | `/api/plans/{planId}` | DELETE | |
| 模拟启动预案 | `/dashboard/plans` | `/api/plans/{planId}/simulate-launch` | POST | |
| 预案启动记录 | `/dashboard/plans` | `/api/plans/launches` | GET | |

### 18.2 报表中心

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 报表模板库 | `/dashboard/reports` | `/api/reports/templates` | GET | `?category=&keyword=` |
| 新建报表模板 | `/dashboard/reports` | `/api/reports/templates` | POST | |
| 删除报表模板 | `/dashboard/reports` | `/api/reports/templates/{id}` | DELETE | |
| 触发报表生成 | `/dashboard/reports` | `/api/reports/templates/{id}/run` | POST | |
| 新增/更新订阅 | `/dashboard/reports` | `/api/reports/subscriptions` | POST | |
| 报表历史 | `/dashboard/reports` | `/api/reports/history` | GET | `?templateId=&status=&trigger=` |
| 重跑历史任务 | `/dashboard/reports` | `/api/reports/history/{id}/rerun` | POST | |
| 取消进行中任务 | `/dashboard/reports` | `/api/reports/history/{id}/cancel` | POST | |

---

## 十九、备份与恢复

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 备份记录列表 | `/dashboard/backup-recovery` | `/api/backup/records` | GET | `?type=&source=&status=&page=&size=` |
| 创建备份任务 | `/dashboard/backup-recovery` | `/api/backup/records` | POST | `{scope, remark}` |
| 触发备份校验 | `/dashboard/backup-recovery` | `/api/backup/records/{id}/verify` | POST | |
| 触发恢复 | `/dashboard/backup-recovery` | `/api/backup/records/{id}/restore` | POST | `{environment, confirm}` |
| 删除备份记录 | `/dashboard/backup-recovery` | `/api/backup/records/{id}` | DELETE | |
| 备份策略列表 | `/dashboard/backup-recovery` | `/api/backup/strategies` | GET | |
| 新建/更新策略 | `/dashboard/backup-recovery` | `/api/backup/strategies` | POST/PUT | |
| 删除策略 | `/dashboard/backup-recovery` | `/api/backup/strategies/{id}` | DELETE | |
| 立即执行策略 | `/dashboard/backup-recovery` | `/api/backup/strategies/{id}/run` | POST | |
| 恢复历史 | `/dashboard/backup-recovery` | `/api/backup/restores` | GET | |

---

## 二十、账号与安全

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 账号安全配置 | `/dashboard/account-security` | `/api/account/security` | GET | 基本信息/2FA/备用码/会话 |
| 启用/关闭 2FA | `/dashboard/account-security` | `/api/account/security/2fa` | POST | `{enabled, methods}` |
| 重新生成备用码 | `/dashboard/account-security` | `/api/account/security/backup-codes/regenerate` | POST | |
| 登录历史 | `/dashboard/account-security` | `/api/account/login-history` | GET | `?page=&size=&status=` |
| 活跃会话列表 | `/dashboard/account-security` | `/api/account/sessions` | GET | |
| 登出指定会话 | `/dashboard/account-security` | `/api/account/sessions/{id}` | DELETE | |
| 操作审计 | `/dashboard/account-security` | `/api/account/audit` | GET | `?page=&size=&type=` |

---

## 二十一、个人设置

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 个人资料（读取） | `/dashboard/profile` | `/api/profile` | GET | |
| 个人资料（更新） | `/dashboard/profile` | `/api/profile` | PUT | `{name, phone, org, ...}` |
| 修改密码 | `/dashboard/profile` | `/api/profile/password` | PUT | |
| 通知偏好 | `/dashboard/profile` | `/api/profile/notification-preference` | GET/PUT | |
| 主题与语言 | `/dashboard/settings` | `/api/profile/theme-language` | PUT | `{theme, language}` |

---

## 二十二、数据资产目录

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 目录 KPI 概览 | `/dashboard/data-catalog` | `/api/catalog/summary` | GET | 资源总数/表数量/字段数 |
| 数据资源列表 | `/dashboard/data-catalog` | `/api/catalog/assets` | GET | `?type=&category=&source=&keyword=&page=&size=` |
| 资源详情 | `/dashboard/data-catalog` | `/api/catalog/assets/{id}` | GET | 含字段/血缘/标签 |
| 申请访问权限 | `/dashboard/data-catalog` | `/api/catalog/assets/{id}/access-requests` | POST | `{scenario, duration, reason}` |
| 更新资源标签 | `/dashboard/data-catalog` | `/api/catalog/assets/{id}/tags` | PUT | `{tags}` |
| 资源使用排行 | `/dashboard/data-catalog` | `/api/catalog/usage` | GET | 按 7/30 天查询次数 |
| 订阅变更通知 | `/dashboard/data-catalog` | `/api/catalog/assets/{id}/subscribe-change` | POST | |

---

## 二十三、多时相对比 & 分析

| 前端功能 | 页面路由 | 调用接口 | 方法 | 说明 |
|---------|---------|---------|------|------|
| 多时相对比数据 | `/dashboard/analytics` | `/api/analytics/compare` | GET | `?base=&compare=&mode=` |

---

## 二十四、公共页面

| 页面 | 路由 | 认证 | 说明 |
|------|------|------|------|
| 欢迎页/登录 | `/` | 可选 | 根据 token 状态跳转 |
| 指挥大屏 | `/screen` | 无需登录 | 全屏展示，URL 直接访问 |
| 移动指挥端 | `/mobile` | 无需登录 | 移动端适配 |
| 403 禁止 | `/dashboard/error/403` | 无需登录 | 权限不足 |
| 500 服务器错误 | `/dashboard/error/500` | 无需登录 | 服务异常 |
| 404 | `/:pathMatch(.*)` | 无需登录 | 页面不存在 |

---

## 附录：接口快速索引（按 HTTP 方法）

### GET

```
/api/auth/ws-ticket
/api/dashboard/overview
/api/dashboard/charts
/api/dashboard/analytics
/api/dashboard/summary
/api/dashboard/zone/kpi
/api/dashboard/zone/trend
/api/dashboard/zone/top-hazards
/api/dashboard/zone/related-warnings
/api/dashboard/zone/related-emergencies
/api/hazards
/api/hazards/viewport
/api/hazards/{id}
/api/hazards/ai-monitor
/api/hazards/geojson
/api/tasks
/api/tasks/{id}
/api/tasks/{id}/runtime
/api/tasks/{id}/log
/api/devices
/api/devices/viewport
/api/devices/{id}
/api/devices/{id}/track
/api/device-map
/api/device-map/trajectory/{deviceCode}
/api/warnings
/api/warnings/stats
/api/warnings/viewport
/api/warnings/preview
/api/warnings/{id}
/api/warnings/{id}/resolve/preview
/api/warnings/{id}/resolve/scope
/api/emergency
/api/emergency/{id}
/api/ai/comprehensive-analysis
/api/ai/comprehensive-analysis/all
/api/ai/comprehensive-analysis/cached
/api/ai/disaster-assessment
/api/ai/disaster-assessment/status
/api/ai/fusion-risk/auto
/api/ai/forecast/auto
/api/ai/device-health
/api/ai/device-health/all
/api/ai/feedback/stats
/api/ai/model/info
/api/ai/health
/api/ai/models
/api/ai/models/{versionId}
/api/ai/models/stats
/api/ai/models/training-jobs
/api/ai/models/training-jobs/{id}/log
/api/analytics/inspection-report
/api/analytics/compare
/api/confirmation/queue
/api/confirmation/queue/stats
/api/event/stats
/api/event/active
/api/event/list
/api/event/{code}
/api/events/{eventId}/sign
/api/todo/summary
/api/todo/confirmations
/api/todo/approvals
/api/todo/warnings
/api/todo/tasks
/api/approvals
/api/approvals/stats
/api/users
/api/roles
/api/audit/logs
/api/system/monitor
/api/system/health
/api/messages
/api/messages/stats
/api/personnel/positions
/api/inspection/task/{taskCode}
/api/inspection/hazard/{hazardCode}
/api/inspection/example
/api/notifications/records
/api/notifications/receipts/{recordId}
/api/notice-templates
/api/notice-templates/{id}
/api/datasources
/api/rules
/api/rules/{id}
/api/rules/{id}/logs
/api/rules/logs
/api/rules/fields
/api/rules/operators
/api/sync/stats
/api/sync/tasks
/api/sync/history
/api/sync/integrity
/api/sync/anomalies
/api/ota/firmwares
/api/ota/tasks
/api/ota/tasks/{taskId}
/api/plans
/api/plans/{planId}
/api/plans/launches
/api/reports/templates
/api/reports/history
/api/backup/records
/api/backup/strategies
/api/backup/restores
/api/account/security
/api/account/login-history
/api/account/sessions
/api/account/audit
/api/profile
/api/profile/notification-preference
/api/catalog/summary
/api/catalog/assets
/api/catalog/assets/{id}
/api/catalog/usage
/api/path-planning/obstacles
/api/resources
/api/sentinel/coverage
/api/weather/products
/api/weather/products/latest
/api/weather/legends
/api/zones/summary
/api/zones/risk-trend
```

### POST

```
/api/auth/login
/api/auth/logout
/api/auth/change-password
/api/dashboard/overview/export
/api/hazards               → 创建灾害点
/api/hazards/export
/api/tasks                 → 创建任务
/api/tasks/{id}/status     → 更新状态
/api/tasks/{id}/retry
/api/tasks/{id}/terminate
/api/devices               → 创建设备
/api/devices/{deviceCode}/data  → 数据上报
/api/devices/{id}/diagnose
/api/devices/export
/api/warnings              → 创建预警
/api/warnings/{id}/confirm
/api/warnings/{id}/dispatch
/api/warnings/{id}/close
/api/warnings/{id}/dismiss
/api/warnings/{id}/resolve
/api/warnings/{id}/delivery/redeliver
/api/warnings/export
/api/emergency/{id}/close
/api/emergency/export
/api/ai/detect
/api/ai/forecast
/api/ai/anomaly
/api/ai/fusion-risk
/api/ai/comprehensive-analysis/refresh
/api/ai/comprehensive-analysis/refresh-one
/api/ai/disaster-assessment/independent
/api/ai/feedback
/api/ai/model/reload
/api/ai/device-health/refresh
/api/ai/device-health/refresh-one
/api/ai/models/training-jobs
/api/ai/models/training-jobs/{id}/start
/api/ai/models/training-jobs/{id}/stop
/api/ai/models/{versionId}/activate
/api/ai/models/training-jobs/{id}/log
/api/ai/chat
/api/confirmation/queue/{id}/claim
/api/confirmation/queue/{id}/transfer
/api/confirmation/queue/{id}/confirm
/api/confirmation/queue/{id}/reject
/api/event/{code}/transition
/api/event/{code}/transition/query
/api/events/{eventId}/sign
/api/events/{eventId}/transfer
/api/events/{eventId}/sign/{signId}/confirm
/api/events/{eventId}/sign/{signId}/reject
/api/approvals/{id}/decide
/api/personnel/positions/report
/api/inspection/gps-check
/api/inspection/submit
/api/notification/send
/api/notification/test
/api/notification/retry/{recordId}
/api/datasources/{code}/toggle
/api/datasources/{code}/test
/api/sync/tasks/{id}/trigger
/api/sync/tasks/{id}/pause
/api/sync/anomalies/{id}/ignore
/api/weather/products/refresh
/api/path-planning/calculate
/api/path-planning/export
/api/resources/route
/api/resources/assign
/api/rules
/api/rules/{id}/toggle
/api/rules/test
/api/rules/{id}/rollback
/api/rules/reload
/api/ota/firmwares
/api/ota/tasks
/api/ota/tasks/{taskId}/cancel
/api/ota/tasks/{taskId}/retry
/api/plans
/api/plans/{planId}/simulate-launch
/api/reports/templates/{id}/run
/api/reports/subscriptions
/api/reports/history/{id}/rerun
/api/reports/history/{id}/cancel
/api/backup/records
/api/backup/records/{id}/verify
/api/backup/records/{id}/restore
/api/backup/strategies
/api/backup/strategies/{id}/run
/api/account/security/2fa
/api/account/security/backup-codes/regenerate
/api/catalog/assets/{id}/access-requests
/api/catalog/assets/{id}/subscribe-change
```

### PUT

```
/api/hazards/{id}
/api/tasks/{id}
/api/devices/{id}
/api/warnings/{id}
/api/emergency/{id}
/api/users/{id}
/api/notification/channel-config
/api/notice-templates/{id}
/api/plans/{planId}
/api/profile
/api/profile/password
/api/profile/notification-preference
/api/profile/theme-language
/api/account/sessions/{id}
/api/rules/{id}
/api/rules/{id}/toggle
/api/catalog/assets/{id}/tags
```

### DELETE

```
/api/hazards/{id}
/api/tasks/{id}
/api/devices/{id}
/api/warnings/{id}
/api/emergency/{id}
/api/users/{id}
/api/notice-templates/{id}
/api/rules/{id}
/api/ota/firmwares/{firmwareId}
/api/plans/{planId}
/api/reports/templates/{id}
/api/backup/records/{id}
/api/backup/strategies/{id}
/api/ai/models/{versionId}
```

### PATCH

```
/api/tasks/{id}/status
```
