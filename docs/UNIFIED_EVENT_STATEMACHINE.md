# 统一事件状态机（v1.7.0+ P3-4）

## 背景

当前系统的"事件"概念分散在三张表里：

| 表 | 状态字段 | 业务含义 |
|---|---|---|
| `hazard_point` | `status` | 灾害点档案状态 |
| `warning_event` | `status` | 预警生命周期 |
| `emergency_event` | `status` | 应急事件处置流程 |

它们之间靠外键串联（`warning_event.hazard_code → hazard_point.hazard_code`、
`emergency_event.warning_code → warning_event.warning_code`），但**没有一个顶层的
`event_id` 把三者串成同一个生命周期**。带来的实际问题：

1. AI 推理结果、设备告警、人工上报可能生成三种不同的事件，状态机不统一
2. 前端用户无法在一个地方看到"这件事从发现到处置完毕的全过程"
3. 后端业务逻辑散落在三个 Service 里，状态流转靠 if/else 拼接，没有强校验

## 解决方案

引入 **Spring StateMachine** + **`unified_event` 表**，把三个分散的事件状态
收敛到一套有限状态机。

### 顶层架构

```
┌───────────────────────────────────────────────────────────────┐
│  unified_event (P3-4 新增)                                     │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ event_code: UFE-20260912-A4F1   ← 顶层事件号              │ │
│  │ state: WARNING_ISSUED          ← 当前状态机状态          │ │
│  │ hazard_code: HZD-001            ← 反向引用               │ │
│  │ warning_code: WR-2026-001                                │ │
│  │ emergency_code: —                                        │ │
│  │ current_state_since / last_transition_at / last_trigger  │ │
│  │ close_reason / closed_by (终态字段)                       │ │
│  └──────────────────────────────────────────────────────────┘ │
└───────────────────┬─────────────┬──────────────┬──────────────┘
                    │             │              │
              ┌─────▼────┐  ┌─────▼─────┐  ┌─────▼──────┐
              │ hazard_  │  │ warning_  │  │ emergency_ │
              │ point    │  │ event     │  │ event      │
              └──────────┘  └───────────┘  └────────────┘
```

### 状态枚举（`EventState.java`）

```
DETECTED ─AI_DETECT/MANUAL_REPORT──> CONFIRMING
DETECTED ─DISMISS──────────────────> CLOSED
CONFIRMING ─CONFIRM───────────────> CONFIRMED
CONFIRMING ─REJECT────────────────> REJECTED (终态)
CONFIRMING ─DISMISS───────────────> CLOSED (终态)
CONFIRMED ─ISSUE_WARNING──────────> WARNING_ISSUED
CONFIRMED ─TRIGGER_EMERGENCY──────> EMERGENCY_ACTIVE
CONFIRMED ─DISMISS────────────────> CLOSED (终态)
WARNING_ISSUED ─TRIGGER_EMERGENCY─> EMERGENCY_ACTIVE
WARNING_ISSUED ─CLOSE─────────────> CLOSED (终态)
EMERGENCY_ACTIVE ─CLOSE───────────> CLOSED (终态)
CLOSED ─REOPEN─────────────────────> WARNING_ISSUED
```

### 事件枚举（`EventTrigger.java`）

`AI_DETECT / MANUAL_REPORT / START_CONFIRM / CONFIRM / REJECT / DISMISS /
ISSUE_WARNING / TRIGGER_EMERGENCY / CLOSE / REOPEN`

每个事件对应状态机里一条 `transition`，非法的状态组合直接被 Spring StateMachine
拒绝，业务层收不到 `false`。

## 关键代码

| 文件 | 职责 |
|---|---|
| `cn/geosentry/event/EventState.java` | 7 个状态枚举 |
| `cn/geosentry/event/EventTrigger.java` | 10 个事件枚举 |
| `cn/geosentry/event/entity/UnifiedEventEntity.java` | unified_event 表 JPA 实体 |
| `cn/geosentry/event/repository/UnifiedEventRepository.java` | JPA 仓储 |
| `cn/geosentry/event/EventStateMachineConfig.java` | `@EnableStateMachineFactory` 配置 |
| `cn/geosentry/event/service/EventStateService.java` | 状态流转 / 桥接三方表 |
| `cn/geosentry/event/controller/EventStateController.java` | `/api/event/*` REST API |

## 集成步骤（迁移路径）

### 第一步：建表（已落地）

`schema.sql` 末尾新增：

```sql
CREATE TABLE IF NOT EXISTS unified_event (
  id BIGSERIAL PRIMARY KEY,
  event_code VARCHAR(64) NOT NULL UNIQUE,
  state VARCHAR(32) NOT NULL,
  hazard_code VARCHAR(64),
  warning_code VARCHAR(64),
  emergency_code VARCHAR(64),
  ...
);
ALTER TABLE hazard_point ADD COLUMN IF NOT EXISTS unified_event_id BIGINT ...;
ALTER TABLE warning_event ADD COLUMN IF NOT EXISTS unified_event_id BIGINT ...;
ALTER TABLE emergency_event ADD COLUMN IF NOT EXISTS unified_event_id BIGINT ...;
```

### 第二步：现有 Service 接入（在三个 Controller/Service 内部）

`HazardService.create()` 改成：

```java
String eventCode = eventStateService.createFromAiDetection(
    hazard.getHazardCode(),
    hazard.getName()
);
hazard.setUnifiedEventId(eventStateService.findByEventCode(eventCode).getId());
hazardRepo.save(hazard);
```

`WarningService.dispatch()` 改成：

```java
eventStateService.transition(
    eventCodeForWarning, EventTrigger.ISSUE_WARNING, operator, null
);
```

`EmergencyService.close()` 改成：

```java
eventStateService.transition(
    eventCodeForEmergency, EventTrigger.CLOSE, operator, closeReason
);
```

### 第三步：TodoCenterPage 切换数据源

`TodoCenterPage.vue` 当前调用的 `getTodoSummary()` 已经聚合了四类待办。
后续可以替换为：

```js
const stats = await dashboardApi.getEventStats();  // = EventStateController#stats
```

让待办数量直接由状态机的活动态数量驱动。

## API 端点

| 方法 | 路径 | 用途 |
|---|---|---|
| GET  | `/api/event/stats`     | 各状态计数（前端可绘制饼图） |
| GET  | `/api/event/active`    | 活动事件列表（未进入终态） |
| GET  | `/api/event/{code}`    | 单事件详情（含 hazard/warning/emergency 关联码） |
| POST | `/api/event/{code}/transition` | 触发状态流转，body：`{trigger, operator, reason}` |

## 安全约束（待接入）

当前 controller 未做权限拦截。生产环境应通过 `PermissionRegistry` 注册：

- `/api/event/{code}/transition` → `event:transition`
- `/api/event/*`（查询）    → `event:view`

## 前端接入点

1. **DashboardLayout 顶栏红点**：把 `getEventStats()` 的活动态数量加到 `/dashboard/todo` 的红点计算里
2. **TodoCenterPage**：未来可完全替换为基于 unified_event 的视图
3. **MessageCenterPage**：预警派发 / 应急启动消息统一从 unified_event 的 lastTransitionAt 推送

## 注意事项

1. 状态机是"工厂模式"——每次 `sendEvent` 都用新实例，避免请求间状态污染
2. `DefaultStateMachineContext` 用于把 unified_event 当前 state 恢复到新实例
3. `transition()` 失败返回 `false`，业务层必须显式处理，不能默默吞掉
4. 终态（CLOSED / REJECTED）除 REOPEN 外拒绝任何事件，保证审计完整性

## 后续可扩展

- 状态变更触发 WebSocket 广播（已对接 RealtimeBroadcastService）
- 状态停留时长超 SLA 自动触发"超时升级"
- 与 LiteFlow 集成实现可视化规则编排
- 状态快照导出做态势复盘
