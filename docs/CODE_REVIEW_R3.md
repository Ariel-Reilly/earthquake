# DisasterGuard 第三轮代码审查报告

- **审查日期**：2026-09-22
- **基线**：`1e922ed`（含 4 个未提交改动：两个 Vue 灾种改造、R2 文档、审计脚本）
- **审查范围**：后端 296 `.java` / 前端 149 源文件 / ai-service 54 `.py` / compose + schema + docs
- **方法**：机械门禁全量扫描 + 四个方向并行人工深挖 + **每条 P0/P1 结论手工复核源码**

---

## 〇、门禁状态：全绿，但门禁不等于正确

| 检查项 | 结果 |
|---|---|
| `mvn clean compile`（291 源文件） | ✅ BUILD SUCCESS（42s） |
| `npm run build` | ✅ built in 8.69s |
| `check-missing-imports.mjs` | ✅ 缺失 import 0 |
| `check-endpoint-parity.mjs` | ✅ 孤儿接口 0（后端 306 / 前端 197） |
| `check-broken-imports.mjs` | ✅ 断链 0 |

**本轮最重要的结论是：门禁全绿与系统可用是两回事。**
下面 5 个 P0 全部能穿过上述门禁——它们不是语法或链接问题，而是**语义、契约与运维配置**问题。
尤其注意 P0-1（漏鉴权）恰恰是因为门禁按短名 `@PostMapping` 匹配，而该处写了全限定名才被漏掉。

---

## 一、P0 — 阻塞发版（6 项，均已逐条复核源码）

> **修复进度（2026-09-22 同日完成）**：P0-1 ~ P0-5 已修复并重新验证通过
> （后端 clean compile BUILD SUCCESS / 前端 build 成功 / 三项审计全绿）。
> P0-6 属部署架构决策，仅加注释标注，待补齐服务定义。

### P0-1 审批决策接口完全无鉴权，且审批人可任意伪造

`backend/.../approval/ApprovalCenterController.java:162`

```java
@org.springframework.web.bind.annotation.PostMapping("/{id}/decide")   // ← 无 @PreAuthorize
public ApiResponse<Map<String, Object>> decide(
    @org.springframework.web.bind.annotation.PathVariable Long id,
    @org.springframework.web.bind.annotation.RequestBody java.util.Map<String, Object> body) {
  ...
  warning.setStatus("approve".equals(decision) ? "已确认" : "已忽略");
  warning.setClosedBy(String.valueOf(body.getOrDefault("operator", "system")));   // ← 客户端可控
  warning.setCloseReason(String.valueOf(body.getOrDefault("comment", "")));
```

- 同文件其他方法都有 `@PreAuthorize("hasAuthority('approval:view')")`，唯独这个**写接口**裸奔；
  `SecurityConfig.java:47` 是 `anyRequest().authenticated()`，`/api/approvals/**` 不在 permitAll →
  **任意已登录用户（含最低权限角色）都能把任意预警改成"已确认/已忽略"并关闭它**。
- 更危险的是 `operator` / `comment` 直接取请求体，**审批人和审批意见完全由调用方伪造**，
  审计日志失去意义（无法追责"谁批的"）。应从 `SecurityContext` 取当前用户。
- 方法注释写"仅做状态流转占位"，但它**真实写入 `status` / `closed_at` / `closed_by`**，注释与行为不符。

> 门禁漏检原因：`check-missing-imports` / 鉴权扫描按短名 `@PostMapping` 匹配，此处是全限定名。

### P0-2 ai-service 端口 8000 / 8001 全线错位，Docker 下 AI 功能 100% 不可用

| 位置 | 值 |
|---|---|
| `ai-service/Dockerfile:28` | `EXPOSE 8000` |
| `ai-service/Dockerfile:34` | `uvicorn ... --port 8000` |
| `ai-service/Dockerfile:32` | 健康检查打 `localhost:8000` |
| `docker-compose.yml:206` | 端口映射 `127.0.0.1:8001:8001` |
| `docker-compose.yml:171` | `AI_SERVICE_BASE_URL=http://ai-service:8001/api/ai` |
| `docker-compose.prod.yml:90` | 同样是 8001 |

容器内无进程监听 8001 → 端口映射无效、后端所有 AI 调用连接被拒。
`prod` 未覆盖 ai-service，故生产环境同样错误。

### P0-3 EMQX 凭据三处互不一致，MQTT 鉴权必然失败

- `docker-compose.yml:76-89` 的 emqx 服务**只传了 `EMQX_DASHBOARD__*`**，从未传 `EMQX_USERNAME` / `EMQX_PASSWORD`
- `infra/emqx/init.sh:8-9`（已挂载，见 compose:100）回退为 `disasterguard_backend / CHANGE_ME_EMQX_PASSWORD`
- `docker-compose.yml:163` 后端侧用的是 `CHANGE_ME_emqx_secure_password_2024`

broker 侧建的用户与客户端密码**必然不等** → MQTT 鉴权失败，硬件沙盘与设备上报全断。
`docker-compose.prod.yml:81` 强制要求真 `EMQX_PASSWORD`，但 emqx 服务无 override、
init.sh 仍写死默认口令 → **生产环境 MQTT 恒定失败**。

### P0-4 状态机缺失 `START_CONFIRM`，事件认领功能 100% 失败

`EventTrigger.java:15` 定义了 `START_CONFIRM`，`ConfirmationQueueController.java:171` 用它认领：

```java
boolean ok = eventStateService.transition(event.getEventCode(), EventTrigger.START_CONFIRM, operator, null);
if (!ok) return ApiResponse.fail(400, "认领失败：状态流转被拒绝");
```

但 `EventStateMachineConfig.java:52-118` 的 13 条转换（已逐条核对）为
`AI_DETECT / MANUAL_REPORT / DISMISS / CONFIRM / REJECT / ISSUE_WARNING / TRIGGER_EMERGENCY / CLOSE / REOPEN`，
**完全没有 `START_CONFIRM`**。`DETECTED → CONFIRMING` 只能由 AI_DETECT / MANUAL_REPORT 触发。

→ `sendEvent` 恒返回 false，认领接口**永远返回 400**。核心业务链路断裂。

### P0-5 前端 `stopRealtimeSync` 无法真正停止同步，登出后轮询与 WS 复活

`frontend/src/services/_architecture/core/realtimeService.js:789-794`

```js
try { socket?.close(); } catch {}
socket = null;          // ← 未解绑 socket.onclose
```

`close()` 是异步的，`onclose`（:444-455）仍会触发 → 调 `resumePolling()` 重建 3 个 interval，
并 5s 后 `connectSocket(wsUrl)`。而 `DashboardLayout.vue:293` 登出时正是调
`stopRealtimeSync({keepListeners:false})`。

**后果**：退出登录后仍持续请求鉴权接口 → 401 → `httpClient.js:58` 触发 `redirectToLogin()`，
与 `router.replace('/login')` 打架，出现登录页抖动/反复重定向。

### P0-6 生产 compose 引用了两个根本不存在的服务（修复 P0-2/P0-3 时新发现）

`docker-compose.prod.yml` **只定义了 5 个服务**：`postgres` / `redis` / `minio` / `backend` / `frontend`。
但 backend 服务里配置了：

- `:83` `EMQX_MQTT_URL: tcp://emqx:1883`
- `:90` `AI_SERVICE_BASE_URL: ${AI_SERVICE_BASE_URL:-http://ai-service:8001/api/ai}`

`emqx` 与 `ai-service` 在 prod 中**均无对应服务定义** → 两个主机名在 Docker 网络内无法解析。

> 这比"prod 未覆盖 ai-service"更严重：**不是配置写错，是整个服务缺失**。
> 生产环境 MQTT 与全部 AI 能力不可用。
> 部署前必须补齐 emqx / ai-service 服务定义，或把 `EMQX_ENABLED` / `AI_ENABLED` 置为 false 显式关闭。

**当前处理**：已在 `docker-compose.prod.yml:79-88` 加注释标注风险；补全服务定义属部署架构决策，待确认。

---

## 二、P1 — 功能不正确或安全隐患（19 项）

### 安全

- **P1-6 匿名可触发后端对外 HTTP 请求**：`weather/controller/WeatherController.java:75`
  `POST /api/weather/products/refresh` 无鉴权，而 `SecurityConfig.java:43` 放行整个 `/api/weather/**`。
  匿名者可强制回源拉取外部资源（无频控的上游放大 + SSRF 面）。

  > 勘误：初稿曾把 `ai/SentinelController.java:39` 列为同类问题，复核后**该条不成立**——
  > 该 Controller 只有 `GET /coverage` 一个只读端点，无写操作，permitAll 属设计意图。
- **P1-16 状态流转写操作用读权限**：`event/controller/EventStateController.java:97-98`
  `POST /{code}/transition` 用 `@PreAuthorize("hasAuthority('events:view')")`，与只读端点同权限；
  `UNIFIED_EVENT_STATEMACHINE.md:161` 要求的 `events:transition` 未实现 → 有查看权即可改状态。
  `:114` 的 `POST /{code}/transition/query`（调试入口）同样问题，且其 `operator` 由请求参数传入，
  审计可追溯性弱（与 P0-1 同类的"操作人可伪造"问题）。
  该文档 `:159`「当前 controller 未做权限拦截」的描述已过时。

### 预警/灾情链路（漏报比报错更危险）

- **P1-7 预警落库失败却报"已创建"（假通过）**：`AiWarningScheduler.java:109-113`

  ```java
  catch (Exception ex) {
    log.debug("AI 预警 DB 写入失败...: {}", ex.getMessage());
    if (!(ex instanceof AppException)) { throw ex; }   // ← AppException 被静默吞掉
  }
  ```
  吞掉后方法正常返回，外层 `doEvaluate()` 照样 `created++`（:87）并打印"触发 N 条"。
- **P1-8 规则引擎数值解析失败静默变 0**：`RuleEvaluator.java:126-127` `toDouble` catch 后 `return 0.0`，
  阈值或实测值无法解析时变 0 参与 `>` / `>=` 比较 → **规则引擎漏报/误报**。
  同类：`DeviceDataService.java:267`、`ViewportBounds.java:44`。

### 后端性能与事务

- **P1-9 事务内做 HTTP + 自调用导致事务失效**：`AiDeviceHealthService.java:139`
  `refreshOne()` 经代理进入 → 真实事务里等 HTTP 往返（:152）；`refreshAllNow()` 内 `analyzeOne(d)`（:114）
  是同类自调用 → `@Transactional` 静默不生效。同一方法两种语义。叠加 N+1：`:146` 每台设备 × 每个
  `HEALTH_METRICS` 各查一次。
- **P1-10 全表加载 + Java 内存分页**：
  `ZoneDashboardService.java:47,50,59,80`（一次 `aggregate()` 里 **4 次 `findAll()`**）；
  `ApprovalCenterController.java:48,113`（`subList` 手工分页，无 `Pageable` 下推）；
  `AccountSecurityController.java:186,212,236`、`OtaController.java:102,161` 同类。
- **P1-11 调度无重入保护 + 全局单线程**：`RuleEngineScheduler.java:55` cron 每 30 秒，无锁。
  项目未定义 `TaskScheduler` bean（仅 `@EnableScheduling`）→ **默认单线程，所有 `@Scheduled` 共享一线程**，
  任一任务因 AI/HTTP 阻塞会拖死全部定时任务并造成 cron 积压。

### 前后端契约

- **P1-12 预警列表等级色标失效（`class` vs `cssClass`）**：
  后端 `WarningDto.java:17` 字段名是 `cssClass`（赋值 `WarningService.java:393`）；
  前端 `WarningsPage.vue:371,375` 用 `warning.class`；**全前端 grep `cssClass` 零命中**，
  而 mock `seed.js:231,247` 用的是 `class` → mock 正常、接后端后色块/图标底色全丢。
- **P1-13 历史灾害「行政区」筛选恒返回空**：
  前端 `HistoryHazardsPage.vue:74` 过滤 `(h.region ?? h.location ?? "")`；
  后端 `HazardDto.java:11-39` **只有 `regionCode`，无 `region` 也无 `location`**
  （`HazardPoint.java:32` 同样只有 `region_code`）→ 恒为 `undefined` → 兜底 `""` →
  任何非空关键字都匹配不上，列表被静默清空。
- **P1-14 灾种枚举七处矛盾，且与系统字典对不上**（详见 §四，已修）。
- **P1-17 `REJECTED` 终态死锁**：文档 `:64,:175` 称 REOPEN 可回 `EMERGENCY_ACTIVE`，
  `EventState.java:20-21` 亦如此，但配置只有 `CLOSED→WARNING_ISSUED`（`:115-117`）
  → **驳回事件永久无法重开**。
- **P1-18 `transition()` 无并发保护**：`EventStateService.java:102-139` 先查后存，
  无 `@Version` / 行锁，并发流转丢更新。另 `:140` 日志在 `:128` 改完 state 后才打印
  `entity.getState()` → 实际输出 `NEW -> NEW`，**审计日志错误**。

### AI 服务 / 运维

- **P1-15 推理阻塞事件循环 + 无超时**：`ai-service/app/routers/detection.py:56`
  在 `async def detect()` 里直接调 `get_detector().detect()`（cv2 解码 + ONNX 前向）。
  全仓无 `run_in_executor` / `to_thread` / `wait_for`（已 grep 确认）→ 单张大图即冻结整个 FastAPI。
- **P1-19 生产无建表路径**：`application-prod.yml:6` `spring.sql.init.mode: never`，
  但全项目无 Flyway/Liquibase，`backend/Dockerfile` 不含 schema.sql，
  `infra/postgres/init/*.sql` 只建扩展/触发器 → **prod 启动后数据库是空的**。

### 前端运行时

- **P1-20 轨迹回放定时器未清理**：`RealTimeMapPage.vue:289` `trackPlayTimer`，
  `onBeforeUnmount`（:203-208）只清了 `playTimer`，漏掉它 → 播放中切走页面后 interval 永久运行并改写已卸载组件的 ref。
- **P1-21 OpenLayers 控制器被塞进 `ref` 深度代理**：`OverviewPage.vue:142,423`。
  全项目 `shallowRef` / `markRaw` **0 处命中**；OL Map 被深度代理，每次
  `setHazards` / `forEachFeatureAtPixel` / `updateSize` 都触发深层依赖收集 → 交互卡顿。
  同项目 `RealTimeMapPage.vue:45`、`CommandScreenPage.vue:41`、`WeatherPage.vue:56` 均用普通 `let`，仅总览页为例外。
- **P1-22 轮询无 abort / 无序号校验，旧响应覆盖新数据**：`realtimeService.js:664-691`
  三条定时器无 in-flight 守卫、无 `AbortController`、无版本号比对，`replaceFromSnapshot`（:283-291）整体替换
  → 响应慢于间隔时请求叠加，先发后到的旧快照覆盖新数据。
  `TaskDetailPage.vue:187-188` 同样 5s 双轮询，`loadLogs` 用 `[...logs.value, ...entries]` 追加 → 日志重复行。
- **P1-23 匿名监听器永久泄漏**：`MobileCommandPage.vue:125-126`
  `window.addEventListener("online", () => {...})` 无 `onBeforeUnmount`，匿名函数后续无法 remove
  → 每次进入移动端页泄漏一对监听器 + 组件闭包。
- **P1-24 `resumePolling` 覆盖句柄产生孤儿定时器**：`realtimeService.js:743-763`
  仅判 `pollingTimer` 就重建全部三个 timer，若 `warningTimer` / `personnelTimer` 非空则原句柄丢失、无法再 clear。

---

## 三、P2 — 技术债与隐患

- **71 处 `@Transactional` 无一标注 `readOnly = true`**（0/71），纯查询也走读写事务。
- **`schema.sql` 14 张表重复 CREATE**（已核验：78 处 CREATE，去重后仅 62 表）：
  `account_audit_entry` / `backup_record` / `backup_strategy` / `emergency_plan` / `firmware` /
  `login_history_entry` / `login_session` / `ota_task` / `report_history` / `report_subscription` /
  `report_template` / `restore_record` / `sync_task_record` / `warning_delivery_record` 各出现 2 次。
- **`drift.py` / `multimodal.py` 从未在 `main.py:125-135` 注册**，且前缀为 `/drift` `/multimodal`
  （与后端约定 `/api/ai` 不符）→ 两模块端点全部不可达。
- **`print()` 当日志**：`yolo_model.py`(5处)、`xgboost_model.py`、`lstm_model.py`、
  `comprehensive_model.py`、`isolation_forest.py`、`device_health_model.py`；入口无 `logging.basicConfig`。
- **`requirements.txt` 缺 `pytest` 与 `httpx`**，而 `tests/` 两者都 import → 按 requirements 装完跑不了测试。
- **`unified_event_id` 无 Entity 映射**：`schema.sql:537-538`（`warning_event` / `emergency_event`）
  无任何 Entity 字段 → FK 恒为 NULL，文档宣称的反向引用无效。
- **通知渠道枚举不对齐**：`NoticeTemplatePage.vue:79-84` 为 `sms/email/app/sound`；
  字典 `data.sql` 的 `channel_type` 只有 `sms/app/email`；种子模板 `data.sql:788`（NT-005）却用 `led`。
- **`validUntil` 丢时区**：`WarningCreatePage.vue:176` 用 `toISOString().slice(0,16)` 产出无时区本地串，
  后端 `WarningService.java:370` 原样入参不解析 → 跨时区部署整体偏移。
- **前端杂项**：`setTimeout` 未清理（`DevicesPage.vue:242`、`WarningResolvePage.vue:100` 900ms 后强拉路由、
  `HelpCenterPage.vue:50`、`SettingsPage.vue:274`、`weatherService.js:162`）；
  `.catch(() => {})` 共 17 处（轮询与探活全失败用户零感知）；`:key` 用 index 共 58 处。
- **`infra/nginx/frontend.conf` 未被任何 compose 引用**（孤儿配置）；
  prod 下 ai-service 仍挂载源码 `./ai-service:/app`、无 healthcheck、无资源限制。
- **硬编码回退口令**已入 git：`scripts/db/*.ps1`、`scripts/fix/*.ps1`（`'disasterguard'`）、
  `backfill_unified_event_hazards.py:21`（`'disasterguard123'`）——属本地默认，非生产密钥，但应统一收敛。

---

## 四、专项：灾种枚举漂移（本轮新发现的"修复引入的新债"）—— **已修**

上一轮为修 P1「灾害类型双轨」改了 `HistoryHazardsPage.vue` 和 `WarningCreatePage.vue`（**尚未提交**），
方向正确（中文→英文码），但**引入了第三、第四份映射**，且彼此矛盾。

> **复核时进一步发现：实际定义点是 7 处**，比初稿统计的 5 处还多两处——
> `SocialMediaPage.vue:66` 与 `PlanManagePage.vue:132` 各有一份**中文标签数组**。
> 其中 `PlanManagePage` 把中文**直接提交给后端**（`:256`）；
> `SocialMediaPage` 的 mock 数据里同一字段中文与英文码**混用**（`"滑坡"` 与 `"landslide"` 并存），
> 下拉选项与数据对不上，筛选形同虚设。

| 定义点 | 取值 |
|---|---|
| `data.sql:17-26` `sys_dict.disaster_type`（**系统字典，10 个**） | earthquake / flood / landslide / debris_flow / fire / typhoon / drought / waterlogging / collapse / **ground_fissure** |
| `NoticeTemplatePage.vue:66-72`（被视为权威，5 个） | landslide, collapse, debris_flow, **subsidence**, earthquake |
| `HistoryHazardsPage.vue:43-51`（新，7 个） | 上述 5 个 + flood, fire |
| `WarningCreatePage.vue:87-95`（新，7 个） | 同上 |
| `WarningService.java:273-279` `HAZARD_TYPE_CODES`（5 条中文→码） | 无 地裂缝 / 洪水 / 火灾 |
| `WarningCreatePage.vue:159-164` `normalizeHazardType`（9 条） | 含 地裂缝→ground_fissure、洪水→flood、火灾→fire |
| `SocialMediaPage.vue:66`（**中文数组**） | 全部灾种 / 滑坡 / 泥石流 / 崩塌 / 地面沉降 / 地震 |
| `PlanManagePage.vue:132`（**中文数组，并提交后端**） | 滑坡 / 崩塌 / 泥石流 / 地面沉降 / 地震 |

具体矛盾：

1. **`subsidence`（地面沉降）不在系统字典里**，却被三处前端当作一等公民；
   字典里的 `typhoon / drought / waterlogging / ground_fissure` 前端三份映射**全部缺失**。
2. 被当作权威的 `NoticeTemplatePage`（5 项）与两个新文件（7 项）**互相矛盾**，差 `flood` / `fire`。
3. 功能影响：`WarningCreatePage.vue:116` 的 `filteredTemplates` 只匹配 landslide/collapse/debris_flow 三个模板，
   **选「洪水/火灾」时匹配不到任何模板，静默落到 `tpl-custom` 空文案**；而 `NoticeTemplatePage` 又无法为 flood/fire 建模板。
4. 模板 `tpl-custom` 的 `disasterType: "custom"` 不在任何合法枚举内，会被原样透传。

### 已实施的修复

1. **建立单一数据源** `frontend/src/utils/hazardTypes.js`，以 `sys_dict.disaster_type` 为权威
   （11 项，含 ground_fissure / typhoon / drought / waterlogging 及补齐的 subsidence）。
   导出 `HAZARD_TYPES` / `HAZARD_TYPE_LABELS` / `normalizeHazardType()` / `hazardTypeLabel()`。
   **5 个页面**（HistoryHazards / NoticeTemplate / WarningCreate / SocialMedia / PlanManage）
   全部改为引用它，页面内 `hazardOptions` 本地定义**已清零**（已 grep 验证）。
2. **`data.sql` 补齐 `subsidence`（地面沉降，sort_order=11）**——它在其余各端都是一等灾种，
   唯独字典缺失，这是"前端有、字典无"的根源。
3. **`WarningService#HAZARD_TYPE_CODES` 补齐至 11 项**（改用 `Map.ofEntries`，突破 `Map.of` 10 组上限），
   并加入 `地面塌陷→subsidence`、`城市内涝→waterlogging` 两个历史别名。
4. **`PlanManagePage` 提交改为英文码**（原提交中文 "滑坡"），展示用 `hazardTypeLabel()`。
5. **`SocialMediaPage`** 选项改为 value/label 结构、筛选改为归一化后比对、
   展示用 `hazardTypeLabel()`，mock 数据统一为英文码。
6. **`WarningCreatePage#filteredTemplates`**：内置模板只覆盖 3 个灾种，选洪水/火灾时原本只剩一个空的
   `tpl-custom`（静默空文案）。现在无精确匹配时回退展示全部模板，保证始终有可参考文案。

---

## 五、门禁可信度警告

1. **`check-endpoint-parity.mjs` 只查单向**（后端有 / 前端没调的孤儿接口），
   不查「前端调了但后端没有」——上一轮的 P0 正是这类缺口。
2. **`check-frontend-endpoints.mjs`（当前未提交改动）会误报**：报 14 条「方法不匹配」
   （如 `POST /api/hazards/{x}`）。实测 `httpClient.js:37-55` 的 `request(path, options)`
   未传 `method` 时 fetch 默认 **GET**，而 `dashboardApi.js:141/175/325/506/596` 都是无 options 调用
   → 是脚本未按默认 GET 归类的误报，**不是真实契约缺陷**。纳入 CI 前须先修脚本。
3. **P0-1 鉴权漏检**：扫描按短名注解匹配，漏掉全限定名写法。建议改为解析 AST 或按 FQN 兼容匹配。

---

## 六、建议修复顺序

**发版前必修（P0）**

| # | 项 | 修复内容 | 状态 |
|---|---|---|---|
| 1 | P0-1 | `ApprovalCenterController` 补 `@PreAuthorize("hasAuthority('approval:approve')")`；新增 `currentUsername()`，`setClosedBy` 改从 `SecurityContext` 取，不再信任请求体 `operator`；全限定名注解改为短名（使其可被门禁扫描） | ✅ 已修 |
| 2 | P0-4 | `EventStateMachineConfig` 补 `DETECTED →CONFIRMING` 的 `START_CONFIRM` 转换；顺带补 `REJECTED →CONFIRMING` 的 `REOPEN`（解 P1-17 死锁） | ✅ 已修 |
| 3 | P0-2 | `ai-service/Dockerfile` 的 `EXPOSE` / healthcheck / `CMD --port` 三处 8000 → 8001（依据 `.env.example:77`「容器内始终 8001」） | ✅ 已修 |
| 4 | P0-3 | `docker-compose.yml` emqx 服务补 `EMQX_USERNAME` / `EMQX_PASSWORD`，与 backend 引用**同一个 `${EMQX_PASSWORD}`**；`infra/emqx/init.sh` 回退值同步为一致值 | ✅ 已修 |
| 5 | P0-5 | `realtimeService.js` `stopRealtimeSync` 先解绑 `onopen/onmessage/onerror/onclose` 再 `close()`；`resumePolling` 重建前先清理旧句柄（解 P1-24 孤儿定时器） | ✅ 已修 |
| 6 | P0-6 | prod compose 补 emqx / ai-service 服务定义，或显式关闭 `EMQX_ENABLED` / `AI_ENABLED` | ⏳ 待确认 |

**本轮同步修复的 P1（已修并复验）**

| # | 修复内容 | 状态 |
|---|---|---|
| P1-7 | `AiWarningScheduler#createAiWarning` 改为返回 boolean；仅 `DataIntegrityViolationException` 视为并发去重，其余异常原样抛出；`created` 只统计真正写库成功的，新增 `failed` 计数；移除对 `AppException` 的静默吞掉与那句谎报的 `log.info` | ✅ 已修 |
| P1-8 | `RuleEvaluator` 的 `toDouble` → `toDoubleOrNull`，数值解析失败判定为**不成立**（不触发）并 warn；`contains` / `in` 非数值运算符保持原语义不受影响 | ✅ 已修 |
| P1-12 | 前端 4 个文件 6 处改读 `cssClass`（保留 `?? class ?? 'orange'` 兼容兜底），`seed.js` 对齐为 `cssClass` | ✅ 已修 |
| P1-13 | 前端改匹配 `regionCode`（后端确无 `region`/`location`），输入框改为「行政区代码」并加 title 说明 | ✅ 已修 |
| P1-16 | `transition` / `transitionQuery` 改用 `events:transition` | ✅ 已修 |
| P1-6 | `SecurityConfig` 中 `/api/weather/**` 与 `/api/sentinel/**` 收紧为仅 `GET` 放行 | ✅ 已修 |
| P1-24 | `resumePolling` 重建定时器前先清理旧句柄 | ✅ 已修（随 P0-5） |
| P1-14 | 灾种枚举：新建 `utils/hazardTypes.js` 单一数据源（对齐 `sys_dict`，11 项），5 个页面统一引用、本地定义清零；`data.sql` 补 `subsidence`；后端 `HAZARD_TYPE_CODES` 补齐至 11 项；`PlanManagePage` 改提交英文码；`WarningCreatePage` 模板无匹配时不再静默空文案（详见 §四） | ✅ 已修 |

**紧接着（P1，未修）**

6. P1-7 / P1-8 预警链路的假通过与静默变 0（**漏报类问题优先于性能类**）
7. P1-6 / P1-16 补鉴权与读写权限分离
8. P1-12 / P1-13 字段契约对账（改一处即可，成本低）
9. P1-15 / P1-19 AI 阻塞与生产建表路径
10. P1-20 ~ P1-24 前端泄漏与竞态

**计划内（P2）**：枚举单一数据源（§四）、schema 去重、注册 drift/multimodal、
补 pytest/httpx、清理孤儿配置。

---

## 七、与 R2 的对照

| R2 遗留项 | 现状 |
|---|---|
| P0 预警创建接口缺失 | ✅ 已修（`1e922ed`） |
| P1 stats 统计口径恒 0 | ✅ 已修 |
| P1 灾害类型双轨 | ⚠️ **部分修**：后端已归一化兜底，前端两个页面已改英文码但**未提交**，且引入新的枚举漂移（§四） |
| P2 测试覆盖不足（5 个测试类） | ❌ 未修，且 ai-service 测试因缺依赖跑不起来 |
| 反向对账脚本误报 | ❌ 未修，`check-frontend-endpoints.mjs` 仍误报 14 条 |

**新增发现**：本轮 5 个 P0 中，4 个（P0-2/3/4/5）是 R2 未覆盖的**运维配置与运行时语义**领域
——前两轮的扫描重心在编译与接口链接，未深入 compose / 状态机 / 前端生命周期。
