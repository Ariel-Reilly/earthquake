# 架构文档（architecture/）

> 最后更新：2026-11-14 ｜ 适用版本：`v1.8.11-delivery`（`47db7de`）
> 性质：架构文档与 **ADR 索引**。当前权威架构总览仍在 `docs/ARCHITECTURE.md`，本目录用于逐步迁移与沉淀 ADR。

## 1. 现有权威文档

| 文档 | 内容 |
|---|---|
| `docs/ARCHITECTURE.md` | 系统分层、模块依赖、关键时序（**当前权威**） |
| `docs/UNIFIED_EVENT_STATEMACHINE.md` | 统一事件状态机（7 状态 × 12 转移） |
| `docs/FRONTEND_API.md` | 前端功能 ↔ 后端接口映射 |

## 2. ADR 索引（Architecture Decision Records）

| 编号 | 标题 | 状态 | 说明 |
|---|---|---|---|
| ADR-003 | 预警状态机与流转约束 | **待建**（先链到 UNIFIED_EVENT_STATEMACHINE） | 见 `ADR-003-warning-state.md` |
| ADR-004 | AI 降级与 fallback 策略 | 待建 | drift / multimodal / sentinel 的降级边界（L-01 / L-04） |
| ADR-005 | 前端时区本地化（A6-5） | 待建 | 跟随浏览器时区，`triggeredAt` 为准 |

## 3. 约定

- 新增架构决策 → 本目录加 `ADR-00x-*.md`，并在上表登记；
- 每篇 ADR 顶部写「状态 / 日期 / 关联代码」；
- 历史修复过程不写进代码注释，写进 ADR 或 `docs/delivery/`。
