# API 契约红线（CONTRACT）

> 最后更新：2026-11-14 ｜ 适用版本：`v1.8.11-delivery`（`47db7de`）
> 性质：前后端**唯一契约口径**。改动契约必须先改本文 + 加契约测试。

## 1. 分页

- 统一信封：`PageResponse{ records, total, page, size, pages }`（`common/api/PageResponse.java`，**已存在**）。
- `page` 从 **1** 开始；`pages` 为总页数。
- 前端**只在 `api/client` 归一化一次**；页面层不得再写 `records/items/list` 三套兼容分支（现存 121 / 83 / 77 处，属历史债，阶段 1 收敛）。
- **前端归一化实现**：`frontend/src/api/client.js` 的 `normalizePage() / normalizeList()`（纯函数 + 单测 14 例，覆盖 records/items/list/数组/空值/默认值/优先级/**pages 推导**）。
  - 缺 `pages` 时由 `total / size` 推导（`Math.ceil`），兼容只给 total/size 的不完整旧端点；显式 `pages` 优先；`size=0` 用列表长度兜底避免除零。
  - 迁移状态：`dashboardApi.normalizePaged` **已改为委托 `normalizePage`**（单点去重、行为等价，调用点不变）；页面层 `items/list` 兼容分支仍待阶段 1 小步清理。

## 2. 时间

- API 返回 ISO 绝对时刻 `triggeredAt`（带偏移，如 `2026-11-14T14:43:00+08:00`）。
- 前端展示统一走 `formatWarningTime(warning)`，按**浏览器时区**本地化；禁止直接渲染 `WarningDto.time`。
- `WarningDto.time` 为 **UTC 兼容快照**，标记待弃用；外部消费者（WS / 导出）一律以 `triggeredAt` 为准。

## 3. ID 语义

- 数据库数字 `id`：用于 `/warnings/{id}` 等**路径参数**。
- 业务 `code`：用于展示与跨系统引用，**禁止**拼进 `{id}` 路径（后端 `@PathVariable Long`，用 code 会 400）。
- `ZoneDashboardDto.RelatedWarning` 当前**无数字 `id`** → 区域专项关联预警**不可点击跳转**（P2，待后端补 `id`）。

## 4. Mock / 演示 / 未接入

- 禁止静默 mock；fallback 必须带来源标识（`demo:true` / `dataSource=mock`）+ 页面横幅/文案。
- 未实现能力统一：后端 `501` 或 `ApiResponse.fail(501, ...)`；前端显示「能力未接入」，**不得用 0 / 假数据冒充**。
- 清单见 `docs/delivery/KNOWN_LIMITATIONS.md`（L-01 drift/multimodal、L-04 卫星影像等）。

## 5. 权限

- 权限单一来源：`auth.js` → `sync-permission-registry.mjs` → 后端注册表；CI 校验 `required - registry == 0`。
- 已弃用端点（如 `/api/ai/drift*`）仍走 `ai:insight:view`，匿名调用返回 **403 而非 501**（刻意）。
