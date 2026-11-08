# DisasterGuard 核心用户路径端到端连通性追踪报告

## 路径一：登录 → 仪表盘 → 灾害列表 → 创建预警

**状态：FULLY CONNECTED（完全连通）**

### 完整调用链

| 层级 | 文件:行 | 关键函数/类 |
|------|---------|-------------|
| 前端页面 | `frontend/src/views/WelcomePage.vue:255-289` | `submitLogin()` |
| 前端 API | `frontend/src/services/dashboardApi.js:146-164` | `loginRequest()` |
| 前端 Store | `frontend/src/stores/auth.js:307-312` | `setSession()` |
| 后端 Controller | `backend/src/main/java/cn/disasterguard/auth/AuthController.java:53-92` | `AuthController.login()` |
| 后端 JWT | `backend/src/main/java/cn/disasterguard/auth/jwt/JwtService.java:108-120` | `generateToken()` |
| 前端重定向 | `frontend/src/views/WelcomePage.vue:279-284` | `getHomePathByRole()` → `/dashboard` |
| 前端页面 | `frontend/src/views/OverviewPage.vue` | 概览仪表盘 |
| 前端页面 | `frontend/src/views/hazard/HazardsListPage.vue` | 灾害列表 |
| 前端 Composable | `frontend/src/composables/useHazardList.js:22-65` | `loadData()` → `dashboardApi.getHazards()` |
| 前端 API | `frontend/src/services/dashboardApi.js:178-189` | `getHazards()` → `GET /api/hazards` |
| 后端 Controller | `backend/src/main/java/cn/disasterguard/hazard/HazardController.java:48-61` | `HazardController.list()` |
| 前端页面 | `frontend/src/views/warning/WarningCreatePage.vue:196-221` | `submit()` → `dashboardApi.createWarning()` |
| 前端 API | `frontend/src/services/dashboardApi.js:720-726` | `createWarning()` → `POST /api/warnings` |
| 后端 Controller | `backend/src/main/java/cn/disasterguard/warning/WarningController.java:50-54` | `WarningController.create()` |
| 后端 Service | `backend/src/main/java/cn/disasterguard/warning/service/WarningService.java:327-352` | `createManual()` |
| 后端 Entity | `WarningEvent` | 持久化到 `warning_event` 表 |
| 前端页面 | `frontend/src/views/warning/WarningsPage.vue:143-170` | `loadData()` → `dashboardApi.getWarnings()` |
| 后端 Controller | `backend/src/main/java/cn/disasterguard/warning/WarningController.java:56-67` | `WarningController.list()` |

### 字段名匹配验证

**登录请求：**
- 前端: `{ username, password, role }`
- 后端 `LoginRequest`: `(username, password, role)`
- 结果: 完全匹配 ✓

**登录响应：**
- 后端 `LoginResponse`: `(token, UserProfile(name, role, username))`
- 前端解析: `{ token: data.token, user: data.user }`
- 结果: 完全匹配 ✓

**创建预警请求：**
- 前端提交字段: `title, level, hazardCode, hazardName, hazardType, region, lon, lat, bufferKm, channels, content, validUntil, operator`
- 后端 `WarningCreateRequest`: `(title, level, hazardCode, hazardName, hazardType, region, lon, lat, bufferKm, channels, content, validUntil, operator)`
- 结果: 完全匹配 ✓

### 编码归一化（前后端差异已处理）

| 字段 | 前端值 | 后端存储值 | 处理位置 |
|------|--------|-----------|----------|
| `level` | `red / orange / yellow / blue` (英文码) | `红色预警 / 橙色预警 / ...` (中文) | `WarningService.normalizeLevel()` : 355-369 |
| `hazardType` | `landslide / collapse / debris_flow` (英文码) | `landslide / collapse / ...` (英文码) | `WarningService.normalizeHazardType()` : 372-386 |

> 注意：`WarningCreateRequest` 的 Javadoc 注释说前端 hazardType 用"中文标签"，但实际前端 `WarningCreatePage.vue:44` 使用的是英文码 `landslide`。`normalizeHazardType()` 做了双向兼容（已是英文码则直接返回），因此功能不受影响，但文档与实际不一致。

### 预警与灾害点关联验证

- `WarningService.createManual()` : 331-337 — 强制校验 `hazardCode` 非空且在 `hazard_point` 表中存在
- `WarningEvent.hazardCode` 列设为 `NOT NULL`（`WarningEvent.java:25-26`）
- 结果: 关联正确 ✓

### 错误处理

| 环节 | 前端 | 后端 | 状态 |
|------|------|------|------|
| 登录空值 | `validateLogin()` 校验用户名/密码非空 | `@NotBlank` 校验 | ✓ 双层校验 |
| 登录失败 | try/catch 显示错误消息 | 返回 401 "用户名或密码错误" | ✓ |
| 创建预警空值 | `canNext` 计算属性分步校验必填项 | `createManual()` 校验 title/hazardCode 非空 | ✓ 双层校验 |
| 灾害点不存在 | 无显式处理（catch 通用错误） | 抛出 400 "隐患点不存在: xxx" | ⚠ 前端仅显示通用错误消息 |

### 认证流完整性

- ✅ JWT 生成（`JwtService.generateToken`）
- ✅ JWT 前端存储（localStorage）
- ✅ JWT 过期检查（`auth.js:376-389` `isTokenExpired()`）
- ✅ JWT 后端验证（`JwtAuthenticationFilter`）
- ✅ JWT 黑名单 / 吊销（改密时调用 `jwtService.revoke()`）
- ⚠ 登出接口仅返回成功，未吊销 token（`AuthController.logout()` : 94-97）
- ✅ WebSocket ticket 机制（`/auth/ws-ticket`）

---

## 路径二：AI 检测 → 统一事件 → 确认工作台

**状态：FULLY CONNECTED（完全连通）**

### 完整调用链

| 层级 | 文件:行 | 关键函数/类 |
|------|---------|-------------|
| 调度器 | `backend/src/main/java/cn/disasterguard/ai/scheduler/AiHazardMonitorScheduler.java:90-198` | `monitor()` → `tick()` |
| AI 客户端 | `AiServiceClient.detectHazardsBatch()` | 调用 Python AI 服务 |
| 灾害 Service | `backend/src/main/java/cn/disasterguard/hazard/service/HazardService.java:213-257` | `createFromAiDetection()` |
| 事件 Service | `backend/src/main/java/cn/disasterguard/event/service/EventStateService.java:64-67` | `createFromAiDetection()` |
| 事件 Entity | `UnifiedEventEntity` | 持久化到 `unified_event` 表，state=DETECTED |
| 前端页面 | `frontend/src/views/hazard/ConfirmationWorkbenchPage.vue` | 确认工作台 |
| 前端 API | `frontend/src/services/dashboardApi.js:672-680` | `getConfirmationQueue()` → `GET /api/confirmation/queue` |
| 后端 Controller | `backend/src/main/java/cn/disasterguard/confirmation/ConfirmationQueueController.java:96-145` | `queue()` |
| 认领动作 | `ConfirmationQueueController.java:224-261` | `claim()` → `transition(START_CONFIRM)` |
| 确认动作 | `ConfirmationQueueController.java:328-367` | `confirm()` → `transition(CONFIRM)` |
| 驳回动作 | `ConfirmationQueueController.java:376-419` | `reject()` → `transition(REJECT)` |
| 状态机 | `backend/src/main/java/cn/disasterguard/event/EventStateMachineConfig.java` | Spring StateMachine 配置 |
| 状态 Service | `EventStateService.java:146-190` | `transition()` |

### AI 检测是否创建统一事件？

**是。** 链路完整：

1. `AiHazardMonitorScheduler.tick()` 每 90 秒扫描区域网格点
2. 调用 `aiClient.detectHazardsBatch()` 获取 AI 高风险检测结果
3. 对每个检测结果调用 `hazardService.createFromAiDetection()`
4. `HazardService.createFromAiDetection()` : 253-254 — 保存 `HazardPoint` 后，调用 `eventStateService.createFromAiDetection(hazardCode, name)`
5. `EventStateService.createFromAiDetection()` : 64-67 — 创建 `UnifiedEventEntity`，初始状态 `DETECTED`，来源 `AI_AUTO`

### 确认队列是否显示 AI 检测事件？

**是。**

- `ConfirmationQueueController.queue()` : 104-107 — 默认状态范围为 `DETECTED + CONFIRMING`（即"待确认"）
- AI 检测创建的事件状态为 `DETECTED`，因此会出现在队列中
- 队列项通过 `toQueueItem()` : 424-456 补充灾害详情（名称、类型、风险等级等）

### 状态机流转验证

状态机配置在 `EventStateMachineConfig.java`，相关流转：

```
DETECTED ──START_CONFIRM──> CONFIRMING ──CONFIRM──> CONFIRMED
                              │
                              └──REJECT──> REJECTED (终态)
```

- ✅ 认领：`DETECTED → CONFIRMING` via `START_CONFIRM`（R3 P0-4 已修复）
- ✅ 确认：`CONFIRMING → CONFIRMED` via `CONFIRM`
- ✅ 驳回：`CONFIRMING → REJECTED` via `REJECT`
- ✅ 状态机校验：`EventStateService.transition()` 使用 `sm.sendEvent(trigger)`，非法跳转返回 false
- ✅ 终态保护：`REJECTED` 和 `CLOSED` 为终态，除 `REOPEN` 外不再接受事件

### 已知问题

1. **确认队列分页效率低**：`ConfirmationQueueController.queue()` : 105-127 — 先加载所有状态的全部事件到内存，再过滤、排序、分页。数据量大时会有性能问题。
2. **认领后 assignee 存储位置不规范**：认领人信息写入 `unified_event.closed_by` 字段（`EventStateService.java:182`），字段名与用途不符（closed_by 本应存终态操作人）。
3. **转派信息无独立 assignee 字段**：转派后接收人也写入 `closed_by`，依赖 EventTransfer 表最新记录区分"已认领"与"已转派"。

---

## 路径三：预警 → 通知送达 → 用户接收

**状态：PARTIALLY CONNECTED（部分连通）— 核心链路断裂**

### 调用链追踪

#### 3.1 预警派发（Warning Dispatch）

| 层级 | 文件:行 | 关键函数/类 |
|------|---------|-------------|
| 前端 API | `frontend/src/services/dashboardApi.js:359-361` | `dispatchWarning()` → `POST /api/warnings/{id}/dispatch` |
| 后端 Controller | `backend/src/main/java/cn/disasterguard/warning/WarningController.java:137-141` | `dispatch()` |
| 后端 Service | `backend/src/main/java/cn/disasterguard/warning/service/WarningService.java:153-171` | `dispatch()` |

**关键发现 — 断点 1：WarningService.dispatch() 不触发通知**

`WarningService.dispatch()` 只做了以下事情：
1. 设置 `status = "处理中"`
2. 设置 `dispatchedAt = now()`
3. 广播预警更新（WebSocket）
4. **橙/红预警联动创建应急事件**（调用 `emergencyService.createFromWarning()`）

**完全没有调用 NotificationService，也没有创建任何通知记录。**

证据：`WarningService.java` 全文搜索 `notification` / `Notification` — **0 匹配**。

#### 3.2 通知系统基础设施（存在但独立）

| 组件 | 文件:行 | 状态 |
|------|---------|------|
| NotificationController | `backend/src/main/java/cn/disasterguard/notification/NotificationController.java:28-34` | 存在，`POST /api/notification/send` |
| NotificationService | `NotificationService.java:56-58` | 存在，`send()` → `router.route()` |
| NotificationRouter | `NotificationRouter.java:80-99` | 存在，创建记录 + 异步发送 |
| NotificationDispatcher | `NotificationDispatcher.java:59-85` | 存在，`@Async` 异步发送 |
| 通知记录 | `notification_record` 表 | 存在 |
| 通知回执 | `notification_receipt` 表 | 存在 |

通知系统基础设施完整，但**与预警派发完全解耦**——需要手动调用 `/api/notification/send` 才会发送，预警派发不会自动触发。

#### 3.3 渠道发送器（全部 Mock）

| 渠道 | 文件 | 实现状态 |
|------|------|---------|
| 短信 (SMS) | `notification/channel/SmsSender.java` | Mock — 仅打日志 `[SMS Mock]`，无真实网关 |
| APP 推送 | `notification/channel/AppSender.java` | Mock — 仅打日志 `[APP Mock]`，无真实推送 |
| 邮件 (EMAIL) | `notification/channel/EmailSender.java` | Mock — 仅打日志 `[EMAIL Mock]`，无真实邮件服务 |
| 声光 | `notification/channel/SoundLightSender.java` | 待确认 |
| 大屏 | `notification/channel/ScreenSender.java` | 待确认 |
| Webhook | `notification/channel/WebhookSender.java` | 待确认 |

所有渠道发送器均带有 `// TODO: 集成真实xxx` 注释，返回 `SendResult.success(UUID.randomUUID())`，模拟发送延迟 50-100ms。

#### 3.4 消息中心（Message Center）

| 层级 | 文件:行 | 说明 |
|------|---------|------|
| 前端 API | `dashboardApi.js:1002-1007` | `getMessages()` → `GET /api/messages` |
| 后端 Controller | `notification/MessageController.java:60-114` | 从 `notification_record` 表查询 |
| 已读标记 | `MessageController.java:132-143` | `status = "READ"` |

消息中心能正常工作（读取 `notification_record` 表），但**由于预警派发不创建通知记录，消息中心在真实场景下会是空的**。

#### 3.5 预警送达页（WarningDeliveryPage）

`WarningDeliveryPage.vue` 页面功能：
- 展示送达汇总（`GET /api/warnings/{id}/delivery/summary`）
- 展示各渠道送达记录（`GET /api/warnings/{id}/delivery/records`）
- 重发失败/全量（`POST /api/warnings/{id}/delivery/redeliver`）

`WarningDeliveryController` 读取的是 `warning_delivery_record` 表，与 `notification_record` 表是两张独立的表。

`redeliver` 端点也明确说明：**"当前未接入真实下发网关，不会真正送达"**（`WarningDeliveryController.java:196-201`）。

### 断点汇总

| 断点位置 | 类型 | 影响 |
|----------|------|------|
| `WarningService.dispatch()` : 153-171 | **核心链路断裂** | 预警派发不触发通知发送，用户收不到预警通知 |
| 所有 Channel Sender (Sms/Email/App) | Mock/Stub | 即使通知被触发，也不会真正送达用户设备 |
| `warning_delivery_record` vs `notification_record` | 数据隔离 | 两套独立的"送达记录"体系，互不关联 |
| `WarningDeliveryController.redeliver()` : 169-203 | 部分功能 | 重发仅改状态为 PENDING，无真实网关消费 |

### 字段不匹配

由于核心链路断裂，字段匹配并非主要问题。但值得注意：

- `MessageController.toDto()` : 185-209 — 做了大量字段归一化（`bizType→type`, `level→severity`, 新增 `unread`/`time` 等），说明历史上前后端字段名曾不一致，现在后端已做兼容。

---

## 总结

| 路径 | 状态 | 核心问题 |
|------|------|---------|
| 路径一：登录→仪表盘→灾害列表→创建预警 | **FULLY CONNECTED** | 无阻断性问题，登出未吊销 token 是小缺陷 |
| 路径二：AI检测→统一事件→确认工作台 | **FULLY CONNECTED** | 完全连通，状态机配置完整，仅存在分页效率和字段命名不规范问题 |
| 路径三：预警→通知送达→用户接收 | **PARTIALLY CONNECTED** | **预警派发与通知系统完全解耦**，渠道发送器全部为 Mock 实现 |

### 最高优先级修复建议

1. **P0：在 `WarningService.dispatch()` 中集成 NotificationService**
   - 当前预警派发仅改状态，不触发通知
   - 需要构建 `NotificationRequestDto` 并调用 `notificationService.send()`
   - 涉及文件：`WarningService.java`

2. **P0：打通 `warning_delivery_record` 与 `notification_record`**
   - 当前是两套独立体系，送达页读的是 `warning_delivery_record`，消息中心读的是 `notification_record`
   - 应统一数据来源或建立同步关系

3. **P1：接入真实通知渠道网关**
   - 短信：阿里云/腾讯云 SMS
   - APP 推送：极光/个推/FCM
   - 邮件：JavaMail/阿里云邮件
   - 当前全部为 Mock 实现，生产环境不可用

4. **P2：优化确认队列分页性能**
   - `ConfirmationQueueController.queue()` 当前全量加载后内存分页
   - 应改为 Specification + Pageable 的数据库分页
