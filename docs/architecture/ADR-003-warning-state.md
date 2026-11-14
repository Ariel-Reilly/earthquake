# ADR-003：预警状态机与流转约束

> 状态：**待建（Draft placeholder）** ｜ 日期：2026-11-14
> 当前权威来源：`docs/UNIFIED_EVENT_STATEMACHINE.md`（7 状态 × 12 转移）

## 背景

预警存在「确认 → 派发 → 关闭 / 解除」等流转，需要固定两条不变量：

- **终态不可再次流转**（重复流转返回 409）；
- **每次流转写审计日志**。

## 决策（占位）

- 状态与转移规则以 `docs/UNIFIED_EVENT_STATEMACHINE.md` 为准，本 ADR 只做索引与提炼；
- 代码侧不变量：终态再次流转返回 409；流转写审计。

## 待补

- 将状态机定义从 `UNIFIED_EVENT_STATEMACHINE.md` 提炼为本 ADR 的正式条目；
- 关联代码：`backend/src/main/java/cn/disasterguard/warning/service/WarningService.java`。
