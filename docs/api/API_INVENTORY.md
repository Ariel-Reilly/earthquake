# API 返回形态清单与统一施工图

> 最后更新：2026-11-15 · 盘点基线 commit `04b5217`（tag `v1.8.12-delivery`）· 批次 1 落地于 `v1.8.13-delivery`
> 适用：`frontend/src/services/dashboardApi.js`（2382 行 / 236 方法）与 `backend/**/*Controller.java`（53 个）
> 关联：[`CONTRACT.md`](CONTRACT.md) §1 分页/时间/ID 红线 · [`../INVENTORY.md`](../INVENTORY.md) §7.1 迁移记录

## 1. 为什么需要这份清单

页面层「猜分页形态」的写法（`data.records ?? data.items ?? data.list`）已经在前端清了 9 处，
但**根因不在页面**：`dashboardApi` 的列表方法有三种返回风格，同一个字段名在不同端点指向不同结构。
清页面只能治标，改 API 层才能治本。

本清单是施工图：**每个列表方法的真实返回形态、消费点数量、迁移风险**，用于分批改造而不靠猜。

## 2. 后端真实形态（实测，非估算）

121 个 GET 端点，返回体经 `ApiResponse<T>` 统一包裹（`request()` 已解包 `.data`），因此前端看到的是 `T`：

| 后端返回类型 | Controller 数 | 前端解包后形态 | 列表承载键 |
|---|---|---|---|
| `PageResponse<Map>` / `PageResponse<Dto>` | 13 | `{ records, total, page, size, pages }` | **`records`** |
| `ApiResponse<Map<String,Object>>` | 44 | 对象 | **`items`**（15 处实测），少数为业务字段 |
| `ApiResponse<List<...>>` | 25 | **裸数组** | —（本身即列表） |

关键实测结论：

- 后端 `ApiResponse<Map>` 的列表承载键**统一是 `items`**（实测 15 处：`/ai/models`、`/ai/models/training-jobs`、
  `/account/login-history`、`/account/audit`、`/backup/records`、`/backup/restores`、`/audit/logs`、
  `/confirmation/queue`、`/event/list`、`/warnings/{id}/delivery/records`、`/sentinel/coverage` 等）。
- **后端没有 `list` 键**。前端读到的 `.list` 全部来自 `dashboardApi` 自己的包装（`getHazards` / `getTasks` /
  `getDevices` / `getWarnings` 等 6 处 `{ list: normalized.records, ... }`）或 mock 层。
- mock 层同样三形态并存：`mock.empty.emptyPaged` → `{records}`；`mock.workflow.listMyConfirmationsMock()` → `{list,total}`。

> 所以「三种形态」的准确定义是：**`records`（真分页）/ `items`（Map 内嵌）/ 裸数组**，
> 外加一层**前端包装出的 `list`**。统一目标必须同时覆盖后端三形态与前端包装层。

## 3. 前端 API 层现状

`dashboardApi` 236 个方法，按返回风格分（**下表为 `v1.8.12` 盘点基线**）：

| 风格 | 数量 | 说明 |
|---|---|---|
| 写操作（POST/PUT/PATCH/DELETE） | 110 | 不属于本次范围（返回单对象/操作结果） |
| 已统一分页 `normalizePaged` → `{records,...}` | 15 | `getHazards`、`getTasks`、`getDevices`、`getWarnings`、`getPersonnel`、`getResources`、`getUsers`、`getApplications`、`getAuditLogs`、`getMessages`、`getDeliveryRecords` 等 |
| 列表方法裸透传 `return request(...)` | **23** | **全部 `list*` 方法**，是本次主要目标 |
| 页面/API 层猜形态 | 4 | `getAiMonitorHazards`、`getConfirmationQueue`、`getDeviceTrack`、`listUnifiedEvents` |
| 单对象 / 统计 / 详情（无关） | 其余 | `getHazardDetail`、`getWarningStats` 等，无需改造 |

> **批次 1 后（`v1.8.13`）复扫**：`_scan_api_shapes.mjs` 实测
> `paged(records)` **15 → 33**（+18，与上表一一对应）、`list*` 裸透传 **23 → 5**；
> 剩余待迁为 §5 的 C 级 11 个 + D 级 3 个（其中部分不是 `list*` 命名），
> 另有 6 处 `{list:...}` 包装待移除。上表数字保留作盘点基线，不作为当前状态。

## 4. 目标契约（唯一形态）

所有**列表方法**统一返回：

```js
{ records: any[], total: number, page: number, size: number, pages: number }
```

- 实现方式：`return normalizePage(await request(...))`（`frontend/src/api/client.js`）。
- 真分页端点：原样透传 `total/page/size/pages`。
- Map 内嵌 `items` 端点：由 `normalizePage` 提升为 `records`。
- 裸数组端点：包成 `records`，`total = length`、`page = 1`、`size = length`、`pages = 1`。
- **不再新增 `list` 字段**；既有 6 处 `{list:...}` 包装在消费点同步改造后移除。

> 为什么统一为 `records` 而非裸数组：`PageResponse` 是后端既有标准类型（`common/api/PageResponse.java`），
> 前端只保留一种读取方式（`.records`）才能彻底消除三分支；裸数组会丢失分页元信息，无法承载真分页端点。

## 5. 全量清单与风险分级

风险 = 消费点数量 × 消费点读法。`0 消费` 为纯 API 改动（零回归）；`≥5 消费且页面猜形态` 必须配契约测试。

### A 级：零消费点（11 个）— 可直接改，零回归

| 方法 | 后端路径 | 后端形态 |
|---|---|---|
| `getSyncHistory` | `/sync/history` | `PageResponse` |
| `listMyConfirmations` | `/todo/confirmations` | 裸数组 |
| `listMyApprovals` | `/todo/approvals` | 裸数组 |
| `listMyPendingWarnings` | `/todo/warnings` | 裸数组 |
| `listPlans` | `/plans` | Map |
| `listPlanLaunches` | `/plans/launches` | 裸数组 |
| `listBackups` | `/backup/records` | Map（`items`）|
| `listBackupStrategies` | `/backup/strategies` | 裸数组 |
| `listRestoreHistory` | `/backup/restores` | Map（`items`）|
| `listReportTemplates` | `/reports/templates` | Map（`items`）|
| `listReportHistory` | `/reports/history` | Map（`items`）|

> 这 11 个当前无前端消费点（页面未接入或走 mock）。改造后若后续页面接入，直接读 `.records` 即可。

### B 级：已用 `normalize*` 消费（7 个）— 可直接改，零回归

页面已通过 `normalizeList` / `normalizePage` 读取，天然兼容 `.records`。

| 方法 | 后端路径 | 消费点 |
|---|---|---|
| `listMyOpenTasks` | `/todo/tasks` | `TodoCenterTab` ×1（`normalizeList`）|
| `listModels` | `/ai/models` | `ModelManagePage` ×1 |
| `getModelTrainingJobs` | `/ai/models/training-jobs` | `ModelManagePage` ×1 |
| `listNoticeTemplates` | `/notice-templates` | `NoticeTemplatePage` ×1 |
| `listActiveSessions` | `/account/sessions` | `AccountSecurityPage`、`SettingsPage` ×2 |
| `listDataAssets` | `/catalog/assets` | `DataCatalogPage` ×1 |
| `listCatalogUsage` | `/catalog/usage` | `DataCatalogPage` ×2 |

### C 级：单消费点且直读（11 个）— 需**同一 commit 内**改 API + 消费点

| 方法 | 后端路径 | 消费点 | 消费点当前读法 |
|---|---|---|---|
| `getSyncTasks` | `/sync/tasks` | `DataSyncPage` ×1 | `normalizeList`（安全）|
| `listFirmware` | `/ota/firmwares` | `OtaManagePage` ×1 | `toRecords`→`normalizeList`（安全）|
| `listOtaTasks` | `/ota/tasks` | `OtaManagePage` ×1 | 同上（安全）|
| `listLoginHistory` | `/account/login-history` | `AccountSecurityPage` ×1 | 直读 `.items` |
| `listAccountAudit` | `/account/audit` | `AccountSecurityPage` ×1 | 直读 `.items` |
| `listApprovals` | `/approvals` | `ApprovalCenterTab` ×1 | 直读 `.items` |
| `getAiDeviceHealthAll` | `/ai/device-health/all` | `DevicesPage` ×1 | 直读 `.list` |
| `listUnifiedEvents` | `/event/list` | `EventCenterPage` ×2 | 猜 `records/items/数组` |
| `listSignRecords` | `/events/{id}/sign` | `ConfirmationSignPage` ×2 | 猜 `list/数组` |
| `getHazardsByViewport` | `/hazards/viewport` | `OverviewPage` ×2 | 已 paged（安全）|
| `getAiMonitorHazards` | `/hazards/ai-monitor` | `AiResultsPage`、`RealTimeMapPage` ×2 | `?? []` 兜底 |

### D 级：多消费点（3 个）— 必须配契约测试，单独一轮

| 方法 | 后端路径 | 消费点 | 风险说明 |
|---|---|---|---|
| `getHazards` | `/hazards` | **14** | 同时被读 `.list`、`.records`、裸数组；涉及列表页/详情页/工作台/大屏/总览/资源调度 |
| `getAiComprehensiveAll` | `/ai/comprehensive-analysis/all` | **7** | 读 `.items` + `.list` + `Array.isArray`；AI 研判首屏与总览依赖 |
| `getConfirmationQueue` | `/confirmation/queue` | **6** | 读 `.items`；确认工作台/会签/隐患详情/移动端共用 |

> D 级 3 个方法覆盖了前端的 27 个消费点，且都是核心链路（57/57 复测、37/37 冒烟的直接依赖）。
> 必须在演示/试运行稳定后再动，并且每改一个都要跑完整回归。

## 6. 实施批次

| 批次 | 范围 | 改动面 | 风险 | 状态 |
|---|---|---|---|---|
| 1 | A（11）+ B（7）= **18 个方法** | `dashboardApi.js` + `client.js` + `AccountSecurityPage.vue` + 契约测试 | 零（无消费点 / 消费点已 normalize） | ✅ **已完成**（`v1.8.13-delivery`，见 §7） |
| 2 | C（11 个） | API + 11 处消费点 | 低（单点，可逐页验证） | 待排期 |
| 3 | D（3 个）+ 移除 6 处 `{list:...}` 包装 | API + 27 处消费点 | 高（核心链路） | 演示后 |

**批次 1 的验收**：

- `normalizePage` 单测覆盖新增形态（Map `items` → `records`）；
- 全量 vitest 不减少、eslint 0、vite build 成功；
- 18 个方法逐个断言返回 `{records,total,page,size,pages}`（新增契约测试）。

**批次 2/3 的验收**：每改一个方法，必须附该消费页面的组件级回归 + 变异验证（沿用 `CONTRACT.md` §1 的迁移验收要求）。

## 7. 批次 1 执行记录（2026-11-15 收口，tag `v1.8.13-delivery`）

**改动面**：`dashboardApi.js` 的 18 个列表方法（`list*` / `getSyncHistory` / `listBackups` 等）由裸透传改为
`normalizePaged(await request(...))`；**mock 分支同步归一**（避免前后端两条路径返回形态不一致）；
`listActiveSessions` 的唯一直读消费点 `AccountSecurityPage.vue` 由 `.items` 改为 `.records`；
`client.js#fallbackPage` 空 fallback 的 `size` 由 `0` 兜底为 `1`（防止下游分页器除零）。

**契约测试**：新增 `frontend/src/__tests__/apiPagedContract.spec.js`（76 例）——18 个方法 × 3 种后端形态
（`records` 真分页 / `items` Map 内嵌 / 裸数组），断言返回键集合恒为 `{records,total,page,size,pages}`，
另覆盖 `items` 提升、裸数组补 `total/size/pages`、真分页原样透传、空响应不返回 undefined。

> **实测抓到 1 处漏改**：`listBackups` 的 `if (USE_BACKEND)` 分支仍 `return request(...)` 裸透传，
> 而 mock 分支已归一 —— 契约测试在 `items` 与「裸数组」两种形态下精确报出 2 例失败。
> 补齐后 **244/244 全绿**。教训：改返回风格时后端分支与 mock 分支必须**成对**归一，
> 只测 `records` 形态会假绿。

**门禁实测**（2026-11-15 本机，含本批改动）：

| 项 | 结果 |
|---|---|
| 前端 eslint / stylelint | 0 / 0 |
| 前端 vitest | **244 passed / 21 文件**（168 → 244，新增 76 例契约测试） |
| SFC 完整性 / vite build | 98/98 / 成功 10.43s |
| 注释门禁 / 自检 | 15 命中 / owner 6 / allowlist 9 / **裸 0**；selftest **19/19** |
| 后端编译 | `mvn.sh -o -q -DskipTests clean compile` 通过 |
| ai-service pytest | **102 passed / 4 skipped**（`ai-service/.venv/Scripts/python.exe`） |

未跑：后端全量 `mvn test`（Docker daemon 未启动，Testcontainers 依赖）、浏览器冒烟 37/37（服务未起）。

**剩余**：批次 2（C 级 11 个）、批次 3（D 级 3 个 + 移除 6 处 `{list:...}` 包装）仍待演示后排期；
因此 `dashboardApi` 三种返回风格**尚未完全统一**，§3 的现状表在批次 2/3 落地前不作废。

## 8. 附：口径与复现

本文所有数字来自脚本实测，不采信估算：

```bash
node scripts/quality/_scan_api_shapes.mjs        # 前端 236 方法 → 形态分布
node scripts/quality/_scan_backend_returns.mjs   # 后端 121 GET → 返回类型
node scripts/quality/_scan_map_keys.mjs          # ApiResponse<Map> 的列表键分布
node scripts/quality/_scan_api_consumers.mjs     # 各方法消费点数量与读法
```

统计口径：

- 消费点 = `frontend/src/**/*.{vue,js}` 中 `dashboardApi.<method>(` 或 `dashboardApi.<method>?.(` 的出现次数，**不含** `services/dashboardApi.js` 自身与 `__tests__`。
- 早期版本的扫描脚本用 `dashboardApi\.<method>\s*\(` 正则，漏掉了可选调用 `?.(`，把 `getAiMonitorHazards`（2 处真实消费）误判为 0 消费。已修正为 `\b\s*\??\.?\s*\(`。
