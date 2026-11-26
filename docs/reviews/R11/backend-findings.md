# DisasterGuard 后端冗余代码与底层逻辑冲突审查（R11 · backend）

- **审查时间**：2026-11-26
- **审查范围**：`backend/src/main/java`（315 个类）+ `backend/src/test`（50 个类）+ `backend/src/main/resources/{application*.yml,schema.sql,data.sql}` + 与根目录 `.env.example` / `docker-compose*.yml` 的同源对照
- **基线**：HEAD `b81311f`（工作区干净）、Spring Boot 3.4 / Java 17
- **审查方式**：纯静态只读——逐文件读码 + 全仓 `grep` 引用检索（java/yml/sql/xml/js/md）+ 全量类/方法/Repository 方法与权限码的自动交叉比对
- **未执行**（按要求）：未跑 `mvn`、未启动 docker、未连真实库、未修改任何被跟踪文件
- **已排除已修项**：已读 `.workbuddy/memory/MEMORY.md` 的「已修勿重报」清单、`docs/reviews/CODE_REVIEW_R10.md`、`CODE_REVIEW_R10_FIX.md`；R10 的 6 个 P1 / 13 个 P2 及 A1/A2 修复不再重报，本文只报告**读码可证**的新问题，并在相关结论处标注与 R10 结论的**冲突**。

---

## 概要（各项计数）

| 维度 | 数量 | 说明 |
|---|---|---|
| **P0** | **1** | AI 综合评分 → 预警联动恒定失败且被静默吞掉（外键 vs 硬编码 rule_code） |
| **P1** | **9** | 权限码双轨/读权限守写操作、已读覆盖投递状态、送达账本分裂、状态机被新建预警打回、AI 预警与自动解除互斥、重启重播种、等级映射 6 套、DSL 双写覆盖、`ai_comprehensive_score.risk_level` 取值域与代码枚举相反 |
| **P2** | **25** | 死代码/死配置/重复实现/口径漂移/并发与线程池重复/schema 重复块等 |
| 冗余代码清单条目 | 25 | 见 §「冗余代码清单」（P2-1 ~ P2-25） |
| 逻辑冲突清单条目 | 22 | 见 §「逻辑冲突清单」（含 P0/P1 拆分项） |
| 确认无人调用的公开方法 | 13 个（另 3 个成片死方法共 ~130 行） | 全仓 main+test 检索均为 0 调用 |
| 未使用的 Repository 方法 | 34 个 | Spring Data 派生方法全仓 0 调用 |
| 永不生效的配置项/字段 | 6 类 | 整类 `AppProperties`（4 字段全无人注入）、其中 `jwtExpireMinutes` 无对应 yml 键、`SecurityConfigProd.devUsersEnabled`、`SystemSettingService` 阈值/渠道白名单、`warning_event.rule_snapshot`（字段存在但永不写入）、compose 传给 backend 但全仓 0 消费的 `EMQX_WS_PORT`/`EMQX_HTTP_PORT`（`docker-compose.yml:190-191`） |
| 权限码三方漂移统计 | registry 125 码 / data.sql 115 码 / 实际 `hasAuthority` 80 码 | registry 中 45 个从未被后端校验；data.sql 有 9 个码只存在于库 |
| 未使用的权限码（三处定义、后端 0 引用） | 2 个：`ai:model:manage`、`ai:model:edit` | 前端按钮还在用它判权 |
| schema.sql 重复 DDL | 14 张表被定义两次（两个 109 行近乎相同的整块） | 见 P2-21 |

> 关键结论：**主链（设备上报 → 规则引擎 → 预警落库 → 前端）代码层面自洽**；破口集中在 ①AI 综合评分→预警 的外键闭环 + 等级词表、②权限码三源漂移、③通知「投递状态」与「已读状态」共用一列、④统一事件状态机被业务写法绕过、⑤dev/docker 每次重启重播种。其中 AI 联动链有**两个互相独立的断点**：P0-1（写入违反外键）与 P1-9（查询/映射的等级词表相反），任修其一都不会让该链路恢复。

---

## P0

### P0-1　AI 综合评分 → 预警联动恒定失败，且被当成「并发去重」静默吞掉

**位置**
- `backend/src/main/java/cn/disasterguard/warning/engine/AiWarningScheduler.java:37`（`RULE_CODE_AI = "AI_COMPREHENSIVE"`）、`:114`（`event.setRuleCode(RULE_CODE_AI)`）、`:124-131`（catch `DataIntegrityViolationException` → `return false`，只打 `log.debug`）
- `backend/src/main/resources/schema.sql:197`：`rule_code VARCHAR(64) REFERENCES warning_rule(rule_code) ON DELETE SET NULL`
- `backend/src/main/java/cn/disasterguard/config/DatabaseForeignKeyInitializer.java:41-43`：启动期幂等补 `fk_warning_rule`（即使老库缺 FK 也会被补上）
- `backend/src/main/resources/data.sql:623-631`：`warning_rule` 只播种 `R001`–`R006`，**没有** `AI_COMPREHENSIVE`

**证据**
```java
// AiWarningScheduler.java:111-131（节选）
private boolean createAiWarning(AiComprehensiveScore s) {
  WarningEvent event = new WarningEvent();
  event.setRuleCode(RULE_CODE_AI);              // "AI_COMPREHENSIVE"
  ...
  try { warningRepository.save(event); }
  catch (DataIntegrityViolationException ex) {
    log.debug("AI 预警并发去重，未写入 hazard={}: {}", s.getHazardCode(), ex.getMessage());
    return false;                               // 注释声称这是"并发去重"
  }
```
```sql
-- schema.sql:194-198
CREATE TABLE IF NOT EXISTS warning_event (
  ...
  rule_code VARCHAR(64) REFERENCES warning_rule(rule_code) ON DELETE SET NULL,
```
```text
$ grep -rn "AI_COMPREHENSIVE" --include=*.java --include=*.sql
AiWarningScheduler.java:37 / :28(注释)
（data.sql / migration 脚本：0 命中 → warning_rule 中没有该 rule_code）
$ grep -n "INSERT INTO warning_rule" -A9 data.sql    # 只有 R001~R006
```

**为什么是冲突（不是"待确认"）**
- `warning_event.rule_code` 指向 `warning_rule.rule_code`，且 `DatabaseForeignKeyInitializer` 会在每次启动把 `fk_warning_rule` 补齐——**任何由 `data.sql` 初始化的库都必然带这条外键**；
- `AI_COMPREHENSIVE` 不是 `warning_rule` 的合法行 → 每次 `INSERT` 都抛 `ForeignKeyViolation`（JDBC 映射为 `DataIntegrityViolationException`）；
- 该异常被 `AiWarningScheduler:124` 当成"并发去重"降级为 `return false`，只写 `log.debug` → **不报错、不告警、监控看不到**，10 分钟一轮永远重试、永远失败。

**影响**
- 「AI 综合评分 → 预警」这条链路在干净部署上 **0 产出**：`source='AI_AUTO'` 的 AI_COMPREHENSIVE 预警永远不会落库；
- `docs/DEVELOPER.md:729`、`data.sql` 等文档仍在对外描述该链路"自动创建 warning_event"——属于**文档与运行态相反**；
- 若某历史库里已存在 `AI_COMPREHENSIVE` 行（旧版本无 FK 时写入），则 `DatabaseForeignKeyInitializer` 补约束时会 `ADD CONSTRAINT` 失败并让启动失败，风险方向相反但同样是阻断级。
- 补充：这条失败与 R10 报告 §六 的 `source=AI_AUTO` 并不矛盾——那是 `WarningCreateService` 里 AI 融合成功后写的 **RULE 系**预警的 source 字段，不是本 scheduler。
- 补充 2：**即使只删外键，链路仍不会恢复**——`AiWarningScheduler` 的候选查询按 `MEDIUM/HIGH/CRITICAL` 过滤，而 `data.sql` 种子的 `risk_level` 是 `orange/yellow/red`（见 P1-9）。两处必须一起修。

**修复建议（二选一）**
1. 在 `data.sql` 增加一条系统伪规则（`rule_code='AI_COMPREHENSIVE'`, `metric_code='ai_score'`, `expression='ai_score >= 60'`, `enabled=false`），把 rule 表当"来源字典"用；或
2. 去掉 `warning_event.rule_code → warning_rule` 的硬外键（改普通列 + 应用层校验），保留历史归属（`warning_rule` 删除时本就走 `disableInWarningRule` 停用而非硬删）。
同时把 catch 分支拆开：只有"唯一键冲突"才算并发去重，外键/非空等约束错误必须 `log.error` 并计入失败数（`AiWarningScheduler:99-101` 目前会把失败数与"未写入"一起吞掉）。

---

## P1

### P1-1　权限模型三源漂移：AI 写操作全部用「读权限码」守，`ai:model:*` 后端 0 引用；另有 `hasRole('ADMIN')` 硬编码与 `rule:*`/`rules:*` 单复数漂移

**位置 / 证据**
- `backend/src/main/java/cn/disasterguard/ai/AiController.java`：**37 个端点全部** `@PreAuthorize("hasAuthority('ai:insight:view')")`（88、98、105、141、148、161、268、299、314、329、336、359、368、400、445、456、465、472、490、497、511、581、620、633、658、724、746、776、814、878、891、903、916、936、966、1017、1043 行）。其中写/破坏性操作为：
  - `POST /api/ai/model/reload`（633）、`POST /api/ai/models/{versionId}/activate`（936）、`POST /api/ai/models/activate`（1017）、`DELETE /api/ai/models/{versionId}`（1043）、`POST /api/ai/models/training-jobs`（878）、`.../{id}/start`（891）、`.../{id}/stop`（903）、`POST /api/ai/social-media/analyze`（966）
- 权限码定义处均有 `ai:model:manage` / `ai:model:edit`：
  - `data.sql:130-131`（`sys_permission` 种子）、`data.sql:451-452`（授给 `ai_engineer`）
  - `PermissionRegistry.java:26`（admin）、`:122-123`（ai_engineer）
  - `frontend/src/stores/auth.js:307-308`；`frontend/src/views/ai/ModelManagePage.vue:578/584/841`（按钮用 `ai:model:edit` 判权）
- 后端 `ai:model:manage` / `ai:model:edit` 在 `hasAuthority` 中 **0 次出现**（脚本统计：实际校验码共 80 个，注册表 125 个，45 个注册码从未被校验）。
- 而 `ai:insight:view` 授给了 **admin / attendant / expert / ai_engineer** 四个角色（`PermissionRegistry.java:25/57/73/123`）→ 值班员、专家都能删模型、启停训练任务。
- `hasRole('ADMIN')` 硬编码（绕过权限码）共 7 个端点：`WarningController.java:151`（`PUT /api/warnings/{id}`）、`:228`（`DELETE`）、`AiAnalysisController.java:41/49`、`AdminApplicationController.java:41/52/60`；另有 `AttachmentController.java:73` 的 `hasAuthority('hazards:delete') or hasRole('ADMIN')`。`system_admin`（role_code 下划线、无 `ROLE_ADMIN`）因此**永远无权**改/删预警，尽管它有后台管理权限。
- data.sql 权限码单复数漂移：
  - `data.sql:195-198` 播种 `rule:view/rule:create/rule:edit/rule:delete`，并授给 `expert`（`:426`）与 `ai_engineer`（`:453-456`）；
  - 后端校验与 `PermissionRegistry` 全部用**复数** `rules:view/edit/create/delete`（`RuleEngineController` 7+6 处；`PermissionRegistry.java:42/124`）→ 库中这 4 个授权对运行时**零作用**（JWT authority 来自 registry，不读 `sys_role_permission`）。
  - 另有 9 个只存在于 `data.sql`、后端与前端都不认识的码：`device_map:view`、`history:view`、`history:export`、`monitor:alert`、`warnings:publish`、`rule:view/create/edit/delete`。

**为什么是冲突**：项目唯一授权真相源是 `PermissionRegistry`（`DbUserDetailsService:89`、`JwtAuthenticationFilter:73`），数据库 `sys_role_permission` 只用于展示；但 data.sql 里仍在维护另一套码表（且拼写不同），前端又用第三套（`auth.js`）。三处只要有一处改动就静默失效。R10/A1 已强调"改权限必须三处同步"，现状是**同步机制之外还留着 4 个死码和 1 组单复数近义码**，且 `ai:model:*` 只在四处"声明"、无一处"执行"。

**影响**
1. 越权面：任何 `ai:insight:view` 持有者可删除/上线模型、启停训练、触发 AI 重载；
2. 前端"按钮灰掉"被当成已鉴权 → 直接调 API 可绕过；
3. `system_admin` 无法执行预警更新/删除，与 RoleController 给它"账号、审计、监控、备份与系统设置"的定位冲突；
4. 权限矩阵页（`RoleController.permissionMatrix`）显示 `rules:*` 已授权，但 DB 里授的是 `rule:*`——运维在 DB 侧核对会得到反向结论。

**修复建议**
- 写操作统一改 `ai:model:manage`（训练任务/上线/删除）与 `ai:model:edit`（参数/重载），读操作保持 `ai:insight:view`；`AiController` 逐端点替换后补 1 个 `AiControllerPermissionTest`；
- `hasRole('ADMIN')` 改为权限码（新增 `warnings:edit`/`warnings:delete`，或复用 `warnings:close`+`warnings:create` 组合），避免 `ROLE_` 大小写与下划线角色的耦合；
- data.sql 删除 `rule:*`、`device_map:view`、`history:*`、`monitor:alert`、`warnings:publish` 或改成 `rules:*`；`sync-permission-registry.mjs` 生成时对"库里存在但注册表不存在"的码直接报错。

### P1-2　通知「已读」覆盖「投递状态」：`notification_record.status` 一列两义，送达/重试统计失真

**位置 / 证据**
- `backend/src/main/java/cn/disasterguard/notification/MessageController.java:126-137`（`markRead` → `record.setStatus("READ")`）、`:145-162`（`markAllRead` → 同样覆盖）
- 投递状态由 `NotificationDispatcher.java:88-101` 写入：`SENDING / BLOCKED / MOCK_SENT / FAILED / SENT / PARTIAL`（另有 `NO_CHANNEL` 由 `NotificationRouter:93` 写、`PENDING` 由 `:218` 写）
- 读取路径把 status 当"投递状态"精确匹配：`NotificationService.records(status=...)`（`NotificationService.java:75-81` → `NotificationRecordRepository:34-63`）、`NotificationDispatcher.retry`（只重试 `FAILED`/`BLOCKED`）、`MessageStatsController.java:54`（`countByStatus("FAILED")`）
- `MessageStatsController.java:20-33` 的注释也承认"已读唯一口径 = status == READ"，但同一注释上一段又说 `PENDING -> SENT -> PARTIAL/DELIVERED -> FAILED`——**同一列被两套语义定义**。

**为什么是冲突**：一条 `MOCK_SENT`/`BLOCKED`/`FAILED` 的通知只要用户点"已读"就变成 `READ`：
- `GET /api/notification/records?status=MOCK_SENT` 再也查不到它；
- `retry` 不再把它列入可重试集合（原本 `BLOCKED` 是可补发的，见 `NotificationDispatcher:119-124`）；
- `stats.mockSent/blocked/failed` 全部下降，而「未读数」上升；
- `MEMORY.md §一` 定义的"record 六种终态"被第 7 个值 `READ` 破坏。

**影响**：演示/交付口径「六种终态 + 回执 BLOCKED 可重试」在**点过已读之后不再成立**；运维看到的失败数会随用户阅读行为变化。

**修复建议**：新增 `read_at TIMESTAMPTZ`/`read_by` 列（或独立 `message_read` 表），已读只写这两列；`MessageStatsController.unread` 改 `countByReadAtIsNull()`；`findByFilters` 的 `readFilter` 分支改为 `r.readAt IS [NOT] NULL`。若为了兼容前端必须保留 `status='READ'`，则投递状态需另开 `delivery_status` 列并迁移。

### P1-3　预警送达页数据源分裂：`warning_delivery_record` 无任何写入方，真实送达在 `notification_record`

**位置 / 证据**
- `backend/src/main/java/cn/disasterguard/warning/WarningDeliveryController.java:72-124`（`GET /api/warnings/{id}/delivery/summary` 读 `warning_delivery_record` 聚合）、`:127-159`（records）、`:169-203`（redeliver 只改 `payload.status`）
- 全仓检索 `warning_delivery_record` 的写入：只有 `WarningDeliveryController` 本身；**没有任何 INSERT**
```text
$ grep -rn "WarningDeliveryRecord|deliveryRecordRepository" backend/src
→ 仅 WarningDeliveryController + 实体 + Repository（findByRecordIdOrderByIdAsc / findMaxId）
$ grep -rn "warning_delivery_record" backend/src
→ schema.sql:1574 / :1693（且重复定义，见 P2-21）+ WarningDeliveryController 注释
```
- 真实送达写的是另一套表/另一套词表：`NotificationRouter.java:233-246` 写 `notification_receipt`（`SENT/MOCK_SENT/BLOCKED/FAILED/RATE_LIMITED`，`SENT` 才写 `sentAt`）、`:158`，与 `WarningDeliveryController` 的 `DELIVERED/SENT/FAILED/PENDING`（`:69-70`、`:86-89`）**互不相交**。

**影响**：预警详情页的「送达汇总/送达记录」恒为 0/空（`WarningDeliveryController` 自己的注释也写"库空即如实返回空列表"），而通知中心里其实有回执记录——**两个页面讲的是同一件事却对不上**；`redeliver` 永远是"无失败记录"。

**修复建议**：删除 `warning_delivery_record` 与三个 delivery 端点，`/api/warnings/{id}/delivery/*` 改为按 `warning_code` 关联查询 `notification_record` + `notification_receipt` 聚合（字段映射：`sent=count(SENT)`、`delivered=count(DELIVERED 回执)`、`failed=count(FAILED)`、`pending=count(PENDING)`）；若保留表，必须在 `NotificationRouter.createReceipt` 同步落一行——否则页面恒 0 的现状不会改变。另：`recordIdGen`（`:52`，`@PostConstruct` 里算 `findMaxId()` 赋值）全类无读取，属死字段。

### P1-4　新建预警把 `unified_event.state` 重置为 `DETECTED`，状态机可被业务写法打回；`dismiss`/`update` 又完全不推状态机

**位置 / 证据**
- `backend/src/main/java/cn/disasterguard/event/service/EventStateService.java:109-136`：`create()` 复用 `kind='HAZARD'` 已有行后**无条件** `existing.setState(initialState)`（`:123`），并把 `lastTransitionAt/lastTrigger` 重置为 `CREATE`；
- 调用方 `WarningCreateService.java:218-228`：**每条规则预警**都调 `createFromAiDetectionIsolated(hazardCode, title)`（`:80-83` 再进 `create(..., EventState.DETECTED)`）；
- `WarningService.java:256-266`（`dismiss` 写「已忽略」）、`:112-125`（`update` 可任意改 status）**都没有** `transitionUnifiedEvent(...)`；而 `confirm/dispatch/close/resolve` 都有（`:173/:187/:251/:288`）。

**为什么是冲突**：同一个隐患点（`hazard_code`）的生命周期里可能有多个规则先后命中（R001→R002，`RuleEngineScheduler` 只按 `rule|hazard` 去重）。第二条预警落库时会把**已经 `CONFIRMED/WARNING_ISSUED/CLOSED` 的统一事件状态打回 `DETECTED`**：
- 确认队列（`ConfirmationQueueController:72-73`、`:185` 按 `DETECTED/CONFIRMING` 统计）重新出现已处置灾害点，且红点数上升；
- `HazardService.submitForConfirmation`（`:274-300`）的"不重置已终态事件"承诺（`:264-267` 注释）被这条路径绕过；
- `dismiss` 后预警是「已忽略」但 `unified_event` 仍在 `CONFIRMING` → 队列红点不消失；`update` 直接把 status 改成终态时事件状态完全不动。

**影响**：状态机（`EventStateMachineConfig` 15+ 转移）与三张业务表的状态**没有单一闭环**；"已闭环"数据可能在下一轮规则命中后重新变成"待确认"。这是数据一致性问题，不只是展示问题。

**修复建议**：`create()` 复用行时只在 `existing.getState() == null`（或确属新事件）时写 `DETECTED`，已有状态保持不动（只补 `event_code/title/source`）；`WarningService.dismiss` 补 `EventTrigger.DISMISS`，`update` 根据新状态映射到对应 trigger 或直接禁用 `status` 字段的更新入口（改由专门的状态流转端点负责）。

### P1-5　AI 综合预警与自动解除的生命周期假设互斥：AI 预警 30 分钟后被自动解除，10 分钟后又被重建

**位置 / 证据**
- `AiWarningScheduler.java:111-121`：创建 `source='AI_AUTO'` 预警时**没有**设置 `occurCount/lastSeenAt`（全仓 grep 该文件中无这两个 setter），实体默认值只有 `occurCount=1`、`lastSeenAt=null`（`WarningEvent.java:95-100`）。
- `WarningEventRepository.java:51-55`：自动解除候选条件 `AND COALESCE(w.lastSeenAt, w.triggeredAt) < :cutoff`，且 `source IS NULL OR source <> 'MANUAL'` → **AI_AUTO 预警会被当作"静默超时"候选**。
- `WarningAutoResolveScheduler.java:74`：cron `0 * * * * *`（每分钟），默认窗口 30 分钟；`AiWarningScheduler.java:52`：cron `0 */10 * * * *`（每 10 分钟）。
- 自动解除后 `AiWarningScheduler` 的去重条件（`:69-77`：存在未关闭的 `AI_COMPREHENSIVE` 预警就跳过）**不再命中** → 重新建单。

**为什么是冲突**：两个 scheduler 对"AI 预警是否还需要继续挂起"的判定依据不同——自动解除看"指标是否还在命中"（规则语义），AI 预警根本没有"命中"概念，只能靠 `triggeredAt` 兜底；设备在线时（或该隐患点无设备 → `NO_DEVICE` 也放行解除，`WarningAutoResolveScheduler:117-122`）AI 预警必然在 ~40 分钟内被"自动解除 + 重建"循环一次。

**影响**：`warning_event` 出现周期性重复行；24h 预警数、P1-4 的 `occur_count`、通知派发全部被这个循环放大（演示时"预警数一直涨"）。

**修复建议**：`findStaleOpenWarnings` 增加 `AND (w.source IS NULL OR w.source NOT IN ('AI_AUTO'))`，或在 `AiWarningScheduler` 写入时维护 `lastSeenAt`（每次 AI 评分仍为 MEDIUM+ 时 bump）；同时对 AI 预警改用"评分回落"作为解除依据，不要复用静默窗口。

### P1-6　dev/docker 每次重启重跑 `schema.sql`+`data.sql`：页面改的规则阈值被种子覆盖、时序样本每重启累积

**位置 / 证据**
- `backend/src/main/resources/application.yml:33-37`：`spring.sql.init.mode: always`（**基础配置**，docker profile 继承）；
- `backend/src/main/resources/application-prod.yml:20-31`：`mode: never`（只有 prod 覆盖）；
- `data.sql:623-631`：`warning_rule` 种子 `ON CONFLICT (rule_code) DO UPDATE SET ... warning_level/expression...` → 页面/规则管理改过的阈值每次重启被**写回种子值**；
- `data.sql:871-885`：`device_metric` 种子用 `NOW() - INTERVAL 'n minute'` 生成 `recorded_at`，配 `ON CONFLICT DO NOTHING`；而唯一索引是 `(device_code, metric_code, recorded_at)`（`schema.sql:464-465`）→ **NOW() 每次都不同，冲突永不发生**，每重启多 13 行；`device_trajectory`（`:860-866`）同理。
- `CODE_REVIEW_R10_FIX.md §7` 写「规则阈值持久性：正常重启不会（`spring.sql.init` 非 always）」——与本条**直接冲突**。

**影响**
1. 演示/开发环境中"在规则管理页把 R004 阈值 5 改成 6"在下次重启后失效（且无任何提示）；
2. `device_metric` 每次重启累积重复样本，规则引擎取"最新值"不受影响，但 24h 雨量聚合（`sumByHazardRecentHours`）、趋势图、`device_metric` 表体积被污染；
3. 每次启动都执行 `schema.sql` 的 `DROP VIEW hazard_point` / `DROP TABLE hazard_point CASCADE`（`:97`、`:1397`），期间外键被卸下再由脚本与 `DatabaseForeignKeyInitializer` 补回——启动窗口内约束缺失，且未列入补丁表的外键会永久丢失。

**修复建议**：`application.yml` 的 `mode` 改 `never`，另建 `application-dev.yml` 显式 `mode: always`（或只对 dev 容器注入 `SPRING_SQL_INIT_MODE=always`）；`data.sql` 的时序种子改为固定时间戳或先 `DELETE ... WHERE source='SEED'`；在 `DEPLOYMENT.md` 明确"dev compose 重启会重播种"。

### P1-7　同一语义「AI/风险等级 → 预警等级」6 套实现，规则条件不一致

| # | 位置 | 实现 | 与其它实现的差异 |
|---|---|---|---|
| 1 | `warning/engine/WarningCreateService.java:118-147` | `CRITICAL→红 / HIGH→橙 / MEDIUM 仅当原为蓝才→黄 / LOW 且 confidence>0.7 才→蓝`，另有 `finalLevel=level` 兜底 | 唯一带"降级"逻辑与置信度门槛 |
| 2 | `warning/engine/AiWarningScheduler.java:138-147` | `CRITICAL→红 / HIGH→橙 / MEDIUM→黄 / LOW→蓝` | 不看置信度，"MEDIUM 恒黄" |
| 3 | `device/listener/ImageAnalysisListener.java:218-228` | `CRITICAL→红 / HIGH→橙 / LOW→蓝 / default→黄` | **缺 MEDIUM 分支**（落 default），与 #2 表面一致但写法不同 |
| 4 | `warning/service/WarningService.java:386-392`（`LEVEL_LABELS`）+ `:432-446`（`normalizeLevel`） | `red/orange/yellow/blue → 中文`；中文含"预警"原样；未知→黄 | 唯一以"英文码"为键 |
| 5 | `ruleengine/WarningRuleSyncService.java:50-54` + `:361-379` | `红橙黄蓝 ↔ red/orange/yellow/blue`；`toLevelCode` 用 `contains("红")`/`substring(0,2)` | 反向映射的独立副本 |
| 6 | `confirmation/ConfirmationQueueController.java:482-489` | `红→1/橙→2/黄→3/蓝或提醒→4/其它→5` | 只做排序优先级，但又是另一套枚举 |

附加：`WarningService.cssClass()`（`:611-617`）与 `isHighLevel()`（`:236-238`）第 7、8 套"含红/橙"判断；`AiInsightService.translateLevel`（`:390-399`）与 `translateDeviceLevel`（`:297-306`）是第 9、10 套标签映射。

**为什么是冲突**：同一条 AI 评分（如 `MEDIUM` + 原规则"蓝色预警"）走规则引擎会升为黄（#1），走 AI 评分联动是黄（#2），走图像识别落"default 黄"（#3 因为没写 MEDIUM）——三处"巧合一致"；而 `LOW + confidence>0.7` 在 #1 会**降级为蓝**、在 #2 会**直接是蓝**、在 #4 若前端传 "low" 会**回落黄**。同一个分数在不同入口落不同等级，且没有任何单测覆盖这 6 份的等价性。

**影响**：预警等级（红/橙/黄/蓝）是派发、应急联动（`WarningService.isHighLevel`）、通知模板、前端样式（`cssClass`）的共同输入；等级在不同来源不一致会直接改变联动行为（橙/红才生成应急事件）。

**修复建议**：抽 `cn.disasterguard.warning.WarningLevel`（枚举 + `fromAiRiskLevel(String, double confidence, String ruleLevel)` + `fromCode(String)` + `toCssClass()`），6 处全部委托；用参数化单测钉住"同一分数全入口同等级"。

### P1-8　规则保存时 `dsl_text` 被无条件覆盖；"条件 → 表达式"存在两套编译实现

**位置 / 证据**
- `ruleengine/RuleEngineService.java:413-435`（`applyRequest`）：
```java
if (request.conditions() != null) {
  rule.setConditions(objectMapper.writeValueAsString(request.conditions()));
  RuleCompiler.ConditionNode node = compiler.buildConditionTree(toCanonicalConditions(request.conditions()));
  rule.setDslText(compiler.toDsl(node));        // :419 生成 DSL
  ...
}
rule.setDslText(request.dslText());             // :434 无条件覆盖为请求里的 dslText（常为 null）
```
- 第二套编译：`ruleengine/WarningRuleSyncService.java:251-294`（`compile(conditionsJson)`：只认 `op/operator/comparator`，`contains/in` 直接判为不可下发，忽略 `unit`，默认 AND），`RuleCompiler.toDsl` 则是另一套 `(a AND b)` 带括号格式（`RuleCompiler.java:190-221`）。

**为什么是冲突**：同一份 `RuleDto.RuleConditions` 能被两条路径序列化成两种不同 DSL：
- 页面保存后 `rule.dsl_text` 被清空（前端只传 `conditions`），而 `WarningRuleSyncService` 用 `conditions` 编译下发 → `warning_rule.expression` 正确、`rule.dsl_text` 为 null；下次启动 `mirrorInto`（`:171-174`）再从 `warning_rule` 反向覆盖回来，形成"删了又长"；
- `RuleEngineService.toDto` 把 `dslText` 原样给前端（`:597`），规则管理页"表达式"列为空，而 `WarningRuleSyncService.effectiveExpressions` 显示的是引擎里的表达式 → 同一行两个表达式。
- `WarningRuleSyncService.compile` 不支持 `contains/in`（`:273-277`），`RuleCompiler` 支持（`:140`）——页面能保存但引擎"静默未下发"（只在 `WarningRuleSyncService:201` WARN）。

**影响**：规则管理页/预警引擎两处表达式展示不一致；页面保存后 `dsl_text` 丢失；新增 `contains` 规则会得到"保存成功但永不生效"。

**修复建议**：删 `:434` 的覆盖（或明确"请求显式带 dslText 才覆盖"），`dsl_text` 只由 conditions 派生；把 `WarningRuleSyncService.compile` 改为复用 `RuleCompiler.toDsl` 的产物（或直接让引擎读 `Rule.dsl`），一套编译链。

### P1-9　`ai_comprehensive_score.risk_level` 的 DB 取值域与代码枚举相反（color 词 vs `LOW/MEDIUM/HIGH/CRITICAL`）

**位置 / 证据**
- 代码侧唯一取值域（大写风险词）：
  - `ai/entity/AiComprehensiveScore.java:30-32` 注释与列定义：`/** LOW / MEDIUM / HIGH / CRITICAL */`
  - `ai/service/AiRiskEnricher.java:35-56`：`LEVEL_LABEL/LEVEL_COLOR/LEVEL_TO_CLASSIC` 只以大写字面量为键；`:86` `isElevated()` 只认 `CRITICAL/HIGH`
  - `warning/engine/AiWarningScheduler.java:63-64`：`scoreRepository.findByRiskLevelIn(List.of("MEDIUM","HIGH","CRITICAL"))`
  - `ai/AiController.java:373-377`：接口字典 `riskLevels = [LOW, MEDIUM, HIGH, CRITICAL]`
- DB 侧种子（小写颜色词）：
```sql
-- data.sql:712-733
INSERT INTO ai_comprehensive_score (hazard_code, risk_level, ...) VALUES
('HZ-001','orange',78.50,...),
('HZ-002','yellow',62.30,...),
('HZ-003','red',   93.20,...),
('HZ-004','orange',71.10,...),
('HZ-005','orange',72.00,...)
ON CONFLICT (hazard_code) DO NOTHING;
```
- 另有第三条写入路径：`AiInsightService.java:326` 把 AI 服务返回值原样落库（`result.overall().riskLevel()`，实测为 `MEDIUM/HIGH/CRITICAL`）。

**为什么是冲突**：`ai_comprehensive_score.risk_level` 同时存在两套词表——调度器写大写风险词、`data.sql` 写小写颜色词。所有消费方按大写词查/映射：
- `AiWarningScheduler` 一次都选不中种子行（即使 P0-1 的外键问题修好，dev 库仍 0 AI 预警）；
- `AiRiskEnricher` 对 `orange` 的 `LEVEL_LABEL.getOrDefault` 回落原值 → `HazardDto.aiLevelLabel` 直接返回英文 `"orange"`；`levelColor` 落灰 `#7b8f8c`；`classicLevel` 为 `null` → `HazardService.toDto:414` 回退静态等级，导致"AI 高风险排序/筛选"（`HazardService.page` 的排序键 `aiScore` 仍在，但等级筛选 `:469-474` 失配）对 5 个种子灾害点全部失效；
- `/api/ai/comprehensive-analysis/cached`（`AiController:313-321` → `AiInsightService.toAttachmentMap`）把 `riskLevel` 原样给前端，前端 `.ai-tag-*` 样式与中文标签按大写词匹配 → 演示库首屏显示 "orange" 而非"高风险"。

**影响**：所谓"AI 单一可信源"（`AiRiskEnricher` 类注释）在演示库上返回混合词表，前端 AI 标签/颜色/排序/预警联动全部失配；这是 R7 §4"AI 研判"演示链路的隐藏断点。

**修复建议**：`data.sql:713/717/721/725/729` 改 `orange→HIGH`、`yellow→MEDIUM`、`red→CRITICAL`（`HZ-003 red 93.2` 应为 CRITICAL）；在 `AiRiskEnricher.Snapshot.load` 增加"未知等级 WARN 日志 + 计数"，避免下一次词表漂移继续静默；补 `AiComprehensiveScoreTest` 断言 `risk_level` 只能落 4 个大写值。

---

## P2

> 编号与后文两张清单一一对应（清单里含级别列）。这里每条一句话说明。

1. **P2-1 三片死方法**：`AiComprehensiveAnalysisSupport.java:466/527/560` 的 `aggregateRemoteSensingFactors/aggregateHistoricalFactors/aggregateSupportingFactors` 全仓 0 调用（约 130 行），而同文件 `aggregateFactors():135` 已产出同一批 key（`image_anomaly_score` 见 323 vs 515、`historical_count` 336 vs 548）。
2. **P2-2 13 个 0 调用公开方法**：`AiInsightService.findElevated:150`、`AiRiskEnricher.sortByRisk:123`、`SensitiveDataCipher.decrypt:110`、`RealtimeBroadcastService.connectionCount:117`、`PathPlanningService.planAsync:96`、`WeatherService.fetchAllLatest:144`、`DeviceMapService.mobileDeviceCodes:159`、`EventStateService.linkWarning:225` + `linkEmergency:234`、`PermissionRegistry.permissionsListOf:144`、`AiWarningScheduler.evaluateNow:150`、`RuleEngineService.evaluateAndTrigger:347`、`ConfirmationQueueController.queryRestriction:494`。
3. **P2-3 34 个未使用 Repository 方法**（如 `findByBizType/findByBizId/countByBizTypeAndStatus/...`，见清单）。
4. **P2-4 整类死配置**：`config/AppProperties.java:5-11` 被 `PropertiesConfig.java:9` 注册但从未注入；其中 `jwtExpireMinutes` 对应键 `disasterguard.jwt-expire-minutes` 在 yml/`.env*` 中都不存在（`MEMORY.md §五` 已注明 `JWT_EXPIRE_MINUTES` 为死配置，但实体类未清）。
5. **P2-5 死字段**：`config/SecurityConfigProd.java:39-40` 的 `devUsersEnabled` 注入后从未使用。
6. **P2-6 `rule_snapshot` 永不写入**：`warning/entity/WarningEvent.java:66-67` 的字段/getter/setter 全仓 0 调用，但 `data.sql:752` 注释与 `DEVELOPER.md` 宣称"写入可追溯的 rule_snapshot"。
7. **P2-7 孤儿列 / 实体与 schema 相反**：`schema.sql:551` 的 `unified_event_id` 无任何实体字段；`WarningEvent.hazardCode` 标注 `nullable=false`（实体 `:25`）而 schema `:427` 明确 `DROP NOT NULL`。
8. **P2-8 死兼容分支**：`WarningDto.java:42-49` 的 18 参构造器 0 调用；`WarningService.java:96 listAll`、`:300 resolve(id,operator)` 0 调用；`WarningDto.desc`（`:512` 取 `triggerValue`）与 `triggerValue` 字段重复；`time`（`:513`）用无时区 `MM-dd HH:mm` 格式化**实体自身 offset** → AI 路径写 UTC、规则路径写本地（`AiWarningScheduler:121` vs `WarningCreateService:195`），新建响应与重读数据库相差 8 小时。
9. **P2-9 接口参数误导**：`WarningController.java:76-79` `list()` 声明 `bbox`/`zoom` 却未传入 `warningService.page(...)`。
10. **P2-10 限流语义错误**：`RateLimitFilter.java:71-74` 超限返回 HTTP **400** 而 body `code=429`；`:112-121` 的"滑动窗口"实为固定窗口（计数到 1 分钟整体清零）。
11. **P2-11 消息列表双实现 + 全表写**：`MessageController.java:145-162` `markAllRead` 载入 `findAll()` 逐条 `save`；`/api/messages`（信封 `items`，`MessageController:105-112`）与 `/api/notification/records`（信封 `records`，`NotificationService:87-93`）是同一张表的两套列表接口/两套 DTO。
12. **P2-12 模型激活双端点**：`AiController.java:935`（`POST /models/{versionId}/activate`）与 `:1016`（`POST /models/activate`）逻辑逐行相同（都只 setStatus ONLINE + deployedAt + updatedAt）。
13. **P2-13 设置页阈值/渠道是死配置**：`system/SystemSettingService.java:33-38` 白名单存了 `displacementThreshold/rainfallThreshold/warningChannels/...`，全仓无后端消费方（前端仅 `SettingsPage.vue:45-47/561-582` 自有用）；规则引擎读 `warning_rule`、通知渠道读 `notification_channel_config` → 设置页改阈值/渠道对实际行为零影响。
14. **P2-14 区域大屏硬编码/伪造指标且未标注**：`dashboard/service/ZoneDashboardService.java:56`（`aiAccuracy=0.91`）、`:71-73`（置信度 `0.95-idx*0.07`、"5.0mm/d"、"40mm"）、`:91-94`（硬编码两条"应急事件" `E-2024-001/002`）、`:107`（`Math.sin` 生成趋势），响应无 `synthetic` 标记（对比 `AnalyticsController.java:114-118` 会标 `synthetic=true`）；且 `ZoneDashboardController.java:43-60` 注释声称 trend 已支持 region/kind 过滤，但 `aggregate()` 的 KPI/trend 完全不使用这两个参数（region 只当展示文案）。
15. **P2-15 应急"终态"字面量不闭环**：`todo/TodoCenterController.java:61-67` 用 `contains("关闭")||contains("归档")` 排除终态，而应急终态是 `已闭环`（`EmergencyService.java:32`）→ 已闭环应急被计为"进行中"；同文件 `:52-53` 的 confirmation 计数含 `CONFIRMING`，`:79-90` 的列表只列 `DETECTED` → 红点数与点开后的条数不一致。
16. **P2-16 预警终态字面量重复 6+ 处**：`List.of("已关闭","已忽略","已解除")` 硬编码在 `WarningController:102`、`WarningService:106`、`WarningAutoResolveScheduler:42`、`RuleEngineScheduler:128`、`AiWarningScheduler:72/158`、`DashboardService:48`；`已发布` 从未被任何代码写入，却在 `ApprovalCenterController:58` 参与 `approved` 统计、在 `WarningController:88-89` 注释里被当作既有状态；`MessageStatsController:49` 注释的 `PENDING→SENT→PARTIAL/DELIVERED` 流转也不存在于代码（`DELIVERED` 只出现在旧注释里）。
17. **P2-17 `DeviceService` 死分支与重复 Specification**：`:252-257` `withinBboxFromLocation(String location, String bbox)` 恒 `return false` 且 `location` 未用（设备无坐标即被视口过滤掉）；`:43-57` 与 `:88-102` 两份 20 行近乎相同的 keyword/state `Specification`。
18. **P2-18 `HazardService` 假上限**：`:359-365` `listAiMonitoredRecent(limit)` 自称上限 200，底层 `findTop50BySourceOrderByDiscoveredAtDesc` 固定截断 50 → `limit>50` 静默失效。
19. **P2-19 指标码两套并存**：`device/scheduler/DeviceMetricSimulator.java:67-110` 中文 `device_type`（`GNSS位移/倾角计/雨量计`）产出旧码 `disp_rate/disp_total/tilt/rain_1h/rain_24h`，英文 `device_type`（`gnss/inclinometer/rain_gauge`）产出标准码 `displacement_rate/tilt_angle/rainfall_24h`；`DEFAULT` 兜底也是旧码 `disp_rate`。规则引擎（`data.sql:623-631`）只读标准码 → 中文类型设备被模拟时**任何规则都不会命中**。类注释 `:34` 仍写旧码，与实际实现（`:85-96`）自相矛盾。
20. **P2-20 `RuleEvaluator` 与 `WarningRuleEvaluator` 语义相反**：`ruleengine/RuleEvaluator.java:78-81` "字段不存在视为 0 或 false" 后参与比较（`x < 5` 会误命中），而 `:160-164` 的 `toDoubleOrNull` 注释明确写"**不要**返回 0.0 —— 0.0 是合法业务值，会把数据缺失伪装成数值 0"；另一求值器 `warning/engine/WarningRuleEvaluator.java:87-91` 则"缺指标直接不判定/不误报"。同一平台两套求值语义。
21. **P2-21 `schema.sql` 整块重复**：14 张表被 `CREATE TABLE IF NOT EXISTS` 定义两次（`schema.sql:1474-1582` 与 `1593-1701` 两个 109 行块，`Compare-Object` 仅差 2 行空行/注释），其中 `warning_delivery_record` 甚至在 `:1574` / `:1693` 重复。改列只改第一处时第二处被 `IF NOT EXISTS` 静默忽略。
22. **P2-22 schema 内联外键与补丁器命名不一致 → 同列可能双外键**：`schema.sql:197` 内联 FK 由 PG 自动命名为 `warning_event_rule_code_fkey`，而 `DatabaseForeignKeyInitializer.java:41-43` 查找/新增的是 `fk_warning_rule` → 全新库上会**再建一条同义外键**（`emergency_event.warning_code` 同理，`:215-218` vs `:47-49`）。
23. **P2-23 两份 SecurityFilterChain 重复**：`config/SecurityConfig.java:30-83` 与 `config/SecurityConfigProd.java:46-88` 约 60 行几乎逐行相同（permitAll 列表、filter 顺序、csrf/session 配置），仅 Swagger 分支与 `@Profile` 不同。当前 `@Profile("!prod")`/`("prod")` 互斥所以不会双 bean，但任何一处加固（如新增匿名白名单）都必须改两份。
24. **P2-24 线程池重复造轮子 + MQTT 双重重连**：`AiForecastScheduler.java:70`、`AiStartupWarmup.java:45`、`WeatherService.java:108`、`MqttDataSubscriber.java:92` 各自 `new` 线程池/调度器（已有 `AsyncConfig`、`SchedulingConfig`）；`MqttDataSubscriber.java:92-99` 的拒绝策略注释写"丢弃最旧消息"，实际执行器是丢弃**新任务**；`:159-167` `setAutomaticReconnect(true)` 与 `:119-151` 手动重连循环两套机制并存。
25. **P2-25 compose 注入死环境变量**：`docker-compose.yml:190-191` 把 `EMQX_WS_PORT`/`EMQX_HTTP_PORT` 注入 backend 容器，但 backend Java/yml、frontend、`scripts/**`、`hardware-sandbox/**` 全仓 0 引用（端口由 emqx 服务的 `ports` 段自行映射）；prod compose 也不注入 → 该两行是纯死配置，且容易让人误以为后端会读它们。

---

## 冗余代码清单

| # | 文件:行 | 类型 | 证据 | 建议 |
|---|---|---|---|---|
| P2-1 | `ai/service/AiComprehensiveAnalysisSupport.java:466,527,560` | 死方法（~130 行） | 全仓 main+test 0 调用；同文件 `aggregateFactors():135` 已产出相同 key（323/336/360 vs 515/548/...） | 删除三方法，保留一条聚合链 |
| P2-2 | `ai/service/AiInsightService.java:150`、`ai/service/AiRiskEnricher.java:123`、`auth/register/service/SensitiveDataCipher.java:110`、`realtime/RealtimeBroadcastService.java:117`、`emergency/service/PathPlanningService.java:96`、`weather/service/WeatherService.java:144`、`device/service/DeviceMapService.java:159`、`event/service/EventStateService.java:225,234`、`common/security/PermissionRegistry.java:144`、`warning/engine/AiWarningScheduler.java:150`、`ruleengine/RuleEngineService.java:347`、`confirmation/ConfirmationQueueController.java:494` | 死方法 | `grep` 全仓 0 调用（含测试）；`linkWarning/linkEmergency` 注释称"三个原 Service 调用"但无调用方 | 删除或补调用方；`decrypt` 若为未来审核页保留则加 `@Deprecated` 注明无入口 |
| P2-3 | `*/repository/*Repository.java` | 死 Repository 方法 ×34 | 全仓 0 调用（见下方清单） | 删除 |
| P2-4 | `config/AppProperties.java:5-11` | 整类死配置 | 仅 `PropertiesConfig.java:9` 注册，无任何注入点；`jwtExpireMinutes` 无对应配置键 | 删除类与注册 |
| P2-5 | `config/SecurityConfigProd.java:39-40` | 死字段 | `devUsersEnabled` 赋值后未使用 | 删除 |
| P2-6 | `warning/entity/WarningEvent.java:66-67` + `data.sql:752` | 死字段/不实声明 | `setRuleSnapshot` 全仓 0 调用 | 实现写入（在 `WarningCreateService.doPersist` 写 metrics 快照）或删字段与文档 |
| P2-7 | `schema.sql:551`、`warning/entity/WarningEvent.java:25` vs `schema.sql:427` | 孤儿列/实体-schema 相反 | 无实体字段映射 `unified_event_id`；`hazard_code NOT NULL` 与 `DROP NOT NULL` 相反 | 删列或补映射；实体改 `nullable=true` |
| P2-8 | `warning/dto/WarningDto.java:42-49`、`warning/service/WarningService.java:96,300,504-531` | 可删兼容分支 / 重复字段 | 18 参构造器 0 调用；`listAll`/`resolve(id,operator)` 0 调用；`desc` 与 `triggerValue` 同值 | 删兼容构造器与两方法；`desc` 映射到真正描述字段 |
| P2-9 | `warning/WarningController.java:76-79` | 死参数 | `bbox`/`zoom` 未传入 `page()` | 删除或实现 |
| P2-10 | `common/security/RateLimitFilter.java:71-74,112-121` | 语义错误 | body `code=429` 但 HTTP 400；"滑动窗口"实为固定窗口 | 改 429 + 真滑动窗口或改注释 |
| P2-11 | `notification/MessageController.java:145-162`；`MessageController:105-112` vs `NotificationService:87-93` | 重复实现/重复列表接口 | 同表两套列表（`items` vs `records`）、两套 DTO；已读逐条保存 | 统一为一个列表端点；已读改批量 SQL |
| P2-12 | `ai/AiController.java:935` 与 `:1016` | 重复实现 | 两个端点逻辑逐行相同 | 保留路径版，删 body 版（或反代） |
| P2-13 | `system/SystemSettingService.java:33-38` | 死配置（有 UI 无效果） | `displacementThreshold/rainfallThreshold/warningChannels/...` 无后端消费方 | 设置页删除阈值/渠道项，或接 `warning_rule`/`notification_channel_config` |
| P2-14 | `dashboard/service/ZoneDashboardService.java:56,71-73,91-94,107` | 硬编码/伪造数据 | `aiAccuracy=0.91`、假应急事件、`Math.sin` 趋势，响应无 `synthetic` | 接真实聚合；短期至少加 `synthetic:true` |
| P2-15 | `todo/TodoCenterController.java:61-67,52-53,79-90` | 状态字面量冲突 | 终态 `已闭环` 未被识别；summary 与 list 口径不同 | 统一用 `EmergencyService.STATUS_RESOLVED` 常量与共享状态集合 |
| P2-16 | 6+ 文件（见上） | 重复常量表 | `List.of("已关闭","已忽略","已解除")`；`已发布` 无写入方 | 抽 `WarningStatus` 常量/枚举 |
| P2-17 | `device/service/DeviceService.java:252-257,43-57 vs 88-102` | 死分支/重复代码 | 恒 `false` 且参数未用；两份相同 Specification | 删除死方法；Specification 抽方法 |
| P2-18 | `hazard/service/HazardService.java:359-365` | 假上限 | `findTop50...` 固定 50，参数声称 200 | 改 Repository 传 limit |
| P2-19 | `device/scheduler/DeviceMetricSimulator.java:67-110`（注释 `:34`） | 两套指标码 | 中文类型→旧码，英文类型→标准码；规则只读标准码 | 只保留标准码，删旧码分支 |
| P2-20 | `ruleengine/RuleEvaluator.java:78-81` vs `:160-164` / `warning/engine/WarningRuleEvaluator.java:87-91` | 求值语义相反 | 缺失字段按 0 比较 vs 缺失即不判定 | 统一为"缺失→不触发"；`evaluateAndTrigger` 清理后 `RuleEvaluator` 仅服务规则测试 |
| P2-21 | `schema.sql:1474-1582` 与 `1593-1701` | 重复 DDL 块 | 14 张表各定义两次，`Compare-Object` 仅差空行/注释 | 删除第二块 |
| P2-22 | `schema.sql:197/215-218` vs `config/DatabaseForeignKeyInitializer.java:41-49` | 重复约束 | 内联 FK 自动名 vs 补丁器名不同 → 同列双 FK | 内联 FK 改用显式 `CONSTRAINT fk_*` 名 |
| P2-23 | `config/SecurityConfig.java:30-83` / `config/SecurityConfigProd.java:46-88` | 重复实现 | 60 行两份 filter chain，仅 Swagger/Profile 不同 | 抽公共 `HttpSecurityCustomizer` |
| P2-24 | `AiForecastScheduler.java:70`、`AiStartupWarmup.java:45`、`WeatherService.java:108`、`MqttDataSubscriber.java:92-99,159-167` | 重复基础设施/双机制 | 4 处自建线程池；MQTT 拒绝策略注释与行为不符；自动重连+手动重连并存 | 复用 `AsyncConfig/SchedulingConfig`；统一重连策略 |
| P2-25 | `docker-compose.yml:190-191` | 死环境变量 | `EMQX_WS_PORT`/`EMQX_HTTP_PORT` 注入 backend 但全仓 0 消费 | 删除或让 `SystemMonitorController` 真正消费 |

### 未使用的 Repository 方法（P2-3 明细，全仓 0 调用）

`AiComprehensiveScoreRepository.findByRiskLevel` · `AiDeviceHealthRepository.findByHealthLevelIn` / `countByHealthLevel` · `AiModelRepository.findByModelType` · `DataSyncTaskRepository.findByLastStatusNot` · `DeviceMetricRepository.findByDeviceCodeAndMetricCodeOrderByRecordedAtDesc` / `sumRecentHours` · `DeviceTrajectoryRepository.deleteByRecordedAtBefore` / `countByDeviceCode` · `FieldDictRepository.findByField` / `findBySource` / `findByType` · `HazardPointRepository.findWithinDistance` · `InspectionTaskRepository.existsByTaskCode` · `MonitoringDeviceRepository.existsByDeviceCode` · `NotificationReceiptRepository.findByRecordIdAndChannel` / `countByRecordIdAndStatus` / `countByChannelAndStatus` · `NotificationRecordRepository.findByBizType` / `findByBizId` / `findByBizIdAndStatus` / `countByBizTypeAndStatus` · `RegionRepository.findByRegionLevel` / `findByParentId` / `findByPathStartingWith` / `findMonitorableRegions` / `findMonitorableByParentId` / `findByRegionLevelInAndGeoTypeIn` · `ResourceRepository.existsByResCode` / `findByResType` / `findByLongitudeIsNotNullAndLatitudeIsNotNull` · `UnifiedEventRepository.findByWarningCode` / `findByEmergencyCode` · `WarningEventRepository.existsByWarningCode` · `WarningRuleRepository.existsByRuleCode`

> 检索方式：`grep -r "(?<![\w])<methodName>(?![\w])" backend/src --include=*.java`，排除 `*Repository.java` 自身。`findByRiskLevelIn`/`sumByHazardRecentHours` 等实际有调用者，**不在**此列表（避免误报）。

---

## 逻辑冲突清单

| # | 主题 | 冲突双方 | 证据 | 影响 | 建议 |
|---|---|---|---|---|---|
| P0-1 | AI 联动预警 rule_code | `AiWarningScheduler:37/114` ↔ `schema.sql:197` + `data.sql:623-631` | 硬编码 `AI_COMPREHENSIVE` 不在 `warning_rule`；FK 保证存在；异常被 `:124-131` 当并发去重吞掉 | AI→预警链路 0 产出且无错误日志 | 播种伪规则或去 FK；catch 区分约束类型 |
| P1-1a | AI 写操作鉴权 | `AiController` 37 端点 ↔ `ai:model:manage/edit` 定义处 | 全用 `ai:insight:view`；两个写权限码后端 0 引用 | 值班员/专家可删模型、启停训练 | 写操作换写权限码 |
| P1-1b | 角色硬编码 | `hasRole('ADMIN')` 7 处 ↔ `PermissionRegistry` 权限码体系 | `WarningController:151/228` 等；`system_admin` 无 `ROLE_ADMIN` | 更新/删除预警只有 admin 能做 | 改权限码 |
| P1-1c | 权限码单复数 | `data.sql:195-198`（`rule:*`）↔ `rules:*`（后端+registry） | DB 授权 4 行对运行时零作用；另 5 个 DB-only 码 | 库侧核对结论与运行态相反 | 统一复数并清理 9 个死码 |
| P1-2 | 已读 vs 投递状态 | `MessageController:133/151` ↔ `NotificationDispatcher:88-101` | 同一 `status` 列被写成 `READ`，抹掉 SENT/MOCK_SENT/BLOCKED/FAILED | 回执统计/重试失真，六终态被第 7 值破坏 | 新增 `read_at` 列 |
| P1-3 | 送达账本 | `WarningDeliveryController:72-203` ↔ `NotificationRouter:233-246` | `warning_delivery_record` 无 INSERT；真实回执在 `notification_record/receipt` | 送达页恒 0，与通知中心对不上 | 合并到通知回执 |
| P1-4 | 统一事件状态机 | `EventStateService:123` ↔ `EventStateMachineConfig` | 新建预警无条件 `setState(DETECTED)`；`dismiss/update` 不推状态机 | 已确认/已关闭事件被"打回待确认" | 复用行时保留状态；补齐 trigger |
| P1-5 | AI 预警生命周期 | `AiWarningScheduler:111-121` ↔ `WarningAutoResolveScheduler:96-142` + `WarningEventRepository:51-55` | AI 预警无 `lastSeenAt`，被 COALESCE(triggeredAt) 判静默；解除后 10 分钟内重建 | 周期性建单/解除，24h 统计虚高 | 排除 AI_AUTO 或维护 lastSeenAt |
| P1-6 | 播种 vs 运行态 | `application.yml:35`（always）↔ `application-prod.yml:31`（never）+ `R10_FIX §7` | dev/docker 每次重启重跑 data.sql；`warning_rule` DO UPDATE 覆盖阈值；`device_metric` NOW() 行每重启累积 | 页面改的阈值重启失效、样本表膨胀 | dev 才 always；种子时间固定 |
| P1-7 | 等级映射 6 套 | 见 §P1-7 表 | `MEDIUM`/`LOW` 在各实现的判定不同 | 同一分数不同入口等级不同 → 派发/应急联动不同 | 抽 `WarningLevel` 枚举 |
| P1-8 | 条件→表达式双实现 | `RuleEngineService:419/434` ↔ `WarningRuleSyncService:251-294` | DSL 先写后覆盖；两套编译规则（contains/in、unit） | 页面表达式为空、`contains` 规则静默不生效 | 单一编译链 |
| P1-9 | AI 评分等级取值域 | `data.sql:712-733`（orange/yellow/red）↔ `AiComprehensiveScore:30-32` + `AiRiskEnricher:35-56` + `AiWarningScheduler:63-64` + `AiController:373-377`（LOW/MEDIUM/HIGH/CRITICAL） | 同列两种词表；`getOrDefault` 回落原值、`findByRiskLevelIn` 选不中 | AI 标签/颜色/排序/预警联动对种子数据全部失配 | 种子改为大写风险词 + 未知等级告警 |
| P2-15a | 应急终态字面量 | `TodoCenterController:61-67` ↔ `EmergencyService:32` | 检查"关闭/归档"，真实终态"已闭环" | 已闭环应急算作进行中 | 用常量 |
| P2-15b | 待办口径 | `TodoCenterController:52-53/79-90` ↔ `:69-72`、`ApprovalCenterController:52-61` | summary 含 CONFIRMING，list 只 DETECTED；`approval` 两种公式 | 红点数与列表条数不符 | 抽共享查询 |
| P2-16a | 预警终态集合 | 6+ 处 `List.of("已关闭","已忽略","已解除")` | 重复硬编码 | 新增状态需改 6 处 | 抽常量 |
| P2-16b | 未产出的状态 | `ApprovalCenterController:58`、`WarningController:88-89`（注释）↔ 全仓无写入 `已发布` | 统计恒 0 的分支 | 展示"已发布 0"误导 | 删除或补写入 |
| P2-20 | 规则求值语义 | `RuleEvaluator:78-81` ↔ `:160-164` / `WarningRuleEvaluator:87-91` | 缺失字段=0 vs 缺失=不判定；`evaluateAndTrigger` 0 引用 | 规则测试页与预警引擎结论可能相反 | 统一语义 |
| P2-19 | 指标码 | `DeviceMetricSimulator:67-110` ↔ `data.sql:623-631` | 中文类型产旧码、规则读标准码 | 中文类型设备永不触发规则 | 只保留标准码 |
| P2-14 | 区域大屏 region/kind | `ZoneDashboardController:43-60` 注释 ↔ `ZoneDashboardService:45-115` | 注释称 trend 已支持过滤，实现完全忽略 region/kind | 切区域趋势图不变（注释声称已修的 bug 仍在） | 接真实过滤或改注释 |
| P2-13 | 设置页阈值 | `SystemSettingService:33-38` ↔ `warning_rule`/`notification_channel_config` | 设置页阈值/渠道无后端消费方 | 改设置对预警/通知零影响 | 删除或接线 |
| P2-22 | 外键双份 | `schema.sql:197` ↔ `DatabaseForeignKeyInitializer:41` | 命名不同 → 新库同列双 FK | 写放大、删除行为重复约束 | 显式同名约束 |
| P2-17 | 视口过滤 | `DeviceService:252-257` ↔ `DeviceService:120-127` | 无 hazard 设备的 `location` 参数被忽略且恒 false | 视口内设备被静默丢弃 | 实现或用设备坐标 |

---

## 验证为正确的部分（避免只报问题）

| 项 | 验证方式 | 结论 |
|---|---|---|
| 权限码"有人可用" | 把 `hasAuthority('...')` 全量提取（80 码）与 `PermissionRegistry`（125 码）比对 | **不存在**"被校验但注册表没有"的码（否则全员 403），这部分是安全的 |
| 事务自调用修复（R10 P1-2 / P1-4） | 读 `WarningCreateService.self`、`EventStateService.createFromAiDetectionIsolated`、`NotificationDispatcher`、`WarningAutoResolveScheduler` | 均通过跨 Bean 代理调用；事务/广播顺序（`afterCommit`）正确，未发现同类自调用绕过 |
| 规则求值器误报防护 | `WarningRuleEvaluator.java:57-109` | 缺失指标返回 empty（不误报）；AND/OR 拆分和"首条款取值"语义与 `trigger_value` 契约一致 |
| 状态机出口完整性 | `EventStateMachineConfig.java:54-143` | `CLOSED/REJECTED` 未用 `.end()`，`REOPEN` 真实可用；非法流转被 `sendEvent=false` 拒绝（R5/R10 修复有效） |
| 应急联动幂等 | `EmergencyService.java:73-110` + `schema.sql:455-459` | 部分唯一索引谓词 `status != '已闭环'` 与 `STATUS_RESOLVED` 常量一致，三层幂等成立 |
| 自动解除保守性 | `WarningAutoResolveScheduler.java:105-167` | 设备离线跳过解除、`NO_DEVICE` 明确日志、广播在提交后（A2 修复有效） |
| 通知 fail-closed | `TestRecipientGuard` + `NotificationRouter:113-121` + `NotificationDispatcher:71-101` | 空白名单全 BLOCKED；`SENT` 才写 `sentAt`；`MOCK_SENT` 单列统计；`SEND_PROBE` 不调 Sender（R6/R7 口径正确） |
| AI 融合写库路径的等级域 | `AiInsightService:326`（落库）↔ `AiRiskEnricher:35-56`（读） | 调度器写的 `MEDIUM/HIGH/CRITICAL` 与读取侧一致；P1-9 只是 `data.sql` 种子越域，不是全库脏数据 |
| 规则引擎写库的枚举域 | `WarningCreateService:194-199` / `WarningService:355/422` vs `warning_event.status` 六个中文值 | 写入值都在 `WarningService.RESOLVABLE_STATUSES`/终态集合内，未发现越域写入（`READ`/`已发布` 见 P1-2/P2-16） |
| 配置键同源抽查 | `application.yml` / `.env.example` / `docker-compose.yml` / `docker-compose.prod.yml` | `EMQX_ENABLED`、`REDIS_PASSWORD`、`MINIO_ROOT_*`、`DEVICE_DEMO_PRESENCE_ENABLED`、`AI_SERVICE_BASE_URL` 的默认值/环境变量名四处一致 |
| Profile 互斥 | `SecurityConfig` `@Profile("!prod")` vs `SecurityConfigProd` `@Profile("prod")` | 不会出现双 `SecurityFilterChain`/双 `PasswordEncoder` bean（虽然代码重复，见 P2-23） |
| 导出 DTO 全部在用 | 6 个 `*ExportRow` + `ExcelExportService` 引用统计 | `Dashboard/Device/Emergency/Hazard/Task/WarningExportRow` 均有引用，无死 DTO |
| 分页信封统一 | `PageResponse` 66 处引用 | 除 `/api/messages`、`/api/users`、`/api/confirmation/queue` 的 `items` 历史契约外，主流接口统一 `records`（已知跨端兼容，不重复报告） |

---

## 待确认项（不下结论，需环境或产品口径确认）

1. **P0-1 的前置事实**：本次未连库（docker 未启动），无法只读确认开发库 `warning_event` 上是否真有 `fk_warning_rule` 约束。若历史上被人为 `DROP CONSTRAINT` 过，则 P0-1 在**当前库**不触发（但全新部署仍必触发，因为 `schema.sql:197` 与 `DatabaseForeignKeyInitializer` 都会建）。建议 infra 起来后执行：`SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='warning_event'::regclass;` 与 `SELECT rule_code FROM warning_rule;`。
2. **AI 是否有数据**：`ai_comprehensive_score` 在目标环境是否有 `MEDIUM/HIGH/CRITICAL` 记录（调度器落库的大写词）；`data.sql` 种子的小写颜色词**永远不会被选中**（见 P1-9），因此"dev 库有 5 行 AI 评分"与"AI 联动预警为 0"可以同时成立。`ai.enabled` 与 ai-service 可用性只影响 `AiWarningScheduler` 的前置 return（`:79`），不影响 P0-1 的结论。
3. **`status='READ'` 是否为前端已依赖的契约**：若是，则 P1-2 的修复要"新增 read 列 + 迁移"而不是直接改 status，需与前端确认。
4. **`DeviceMetricSimulator` 中文 `device_type` 分支是否仍在用**：当前 `data.sql:687-701` 与沙盘网关都用英文类型；若确认无中文类型设备，可直接删旧码分支（P2-19）。
5. **`ImageAnalysisListener` 未绑定设备回落 `FALLBACK_HAZARD_CODE`**（`ImageAnalysisListener.java:213-214`）是否已在历史审查中作为"演示妥协"登记——未在 R10/R10_FIX 中出现，但可能与更早的 R7/R8 结论重复，故未单列条目。
6. **区域大屏 mock（P2-14）是否符合"演示可接受"定位**：项目定位教学/演示，但同一仓库的 `AnalyticsController:114-118` 已形成"合成数据必须标 `synthetic`"惯例，建议对齐而非保留。
7. **R006 的 `crack_width_rate` 只有沙盘网关上报**（`hardware-sandbox/gateway/config.yaml:137`）、`backend` 侧无生产方；真实设备替换沙盘后 R006 会静默失效——与 R10 P1-3 同类但属沙盘约定，是否接受需产品确认。
8. **`WarningService.close()` 用 `reason` 中文子串决定终态**（`:337-344`：含"解除"→已解除、含"误报"→已忽略）：这是"关闭原因"接口的既有语义，但会让一次普通关闭意外变成"已解除"（从而允许规则重新建单）；是否改为显式 `terminalStatus` 参数待确认。

---

### 附：本次使用的关键检索命令（可复核）

```bash
# 类/方法 0 引用（含 test）
grep -rn "(?<![\w])METHOD_NAME(?![\w])" backend/src --include=*.java
# 未使用 Repository 方法（排除自身文件后仍 0 命中）
for n in ...; do grep -rn "(?<![\w])$n(?![\w])" backend/src --include=*.java | grep -v "Repository.java"; done
# 权限码三方比对
grep -o "hasAuthority('[^']*')" -rh backend/src/main/java | sort -u           # 实际校验 80
grep -o '"[a-z]*:[a-z:]*"' backend/src/main/java/.../PermissionRegistry.java # 注册表 125
grep -oE "^\('[a-z][a-z0-9_]*:[a-z0-9:]+'" backend/src/main/resources/data.sql # 库种子 115
# 终态字面量重复
grep -rn '"已关闭", "已忽略", "已解除"' backend/src/main/java
# 指标码两套
grep -n "MetricSpec(" backend/src/main/java/cn/disasterguard/device/scheduler/DeviceMetricSimulator.java
# 重复 DDL
grep -n "CREATE TABLE IF NOT EXISTS" backend/src/main/resources/schema.sql | awk -F' ' '{print $NF}' | sort | uniq -d
```
