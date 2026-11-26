# R11 审查修复记录

> 对应报告：[`CODE_REVIEW_R11.md`](./CODE_REVIEW_R11.md)
> 分支：`release/delivery` · 起点 `8aad677` 之前 → 终点 `ebd7a41`（13 个修复提交 + 1 个报告归档）
> 规模：79 文件 · +3546 / −887 行

## 1. 已修复

### P0

| 报告项 | 问题 | 修复 | 验证 |
|---|---|---|---|
| P0-1 | AI 综合预警写入 `warning_event.rule_code=AI_COMPREHENSIVE` 但 `warning_rule` 无此记录 → 外键必违约，异常被 `catch` 静默吞掉，AI 预警从不落库 | `data.sql` 播种伪规则 + `RuleInitializer` 启动期幂等兜底；`AiWarningScheduler` 只把 SQLState `23505` 当并发去重，其余如实计入失败 | `AiWarningSeedContractTest` |

### P1 — 权限

| 报告项 | 修复 |
|---|---|
| A1 三源漂移 | `PermissionRegistry` 由脚本生成（`scripts/fix/sync-permission-registry.mjs`），正则支持 `-`；删除幽灵码 `inspection:gps-check`；`data.sql` 补齐缺失码 |
| A2 写操作读权限 | `AiController` 7 个写端点由 `ai:insight:view` 拆到 `ai:model:manage` / `ai:insight:refresh` |
| A3 `hasRole('ADMIN')` | 7 处改为具体权限码（`WarningController`、`AiAnalysisController`、`AttachmentController`、`AdminApplicationController` → `users:approve`） |
| A5 前端权限映射 | `auth.js` / `router/index.js` 4 个路由映射修正，菜单-路由-权限冲突 10 → 0 |

### P1 — 状态机

- `EventStateService` 增加终态保护：已闭环事件不再被新建预警打回 `DETECTED`。
- `WarningService.update/dismiss` 补推状态机；`TodoCenterController` 终态改用 `EmergencyService.STATUS_RESOLVED`。
- 新增 `WarningStatus`（6 常量 + `TERMINAL` + `isTerminal()`）：终态集合由 **7 份副本收敛为 1**，约 20 处状态比较/写入改引用常量；`resolve` 判据改为 `!isTerminal()`，与其注释原意一致。

### P1 — 通知

- `notification_record` 拆 `read_at` 与投递 `status`，已读不再覆盖投递结果；`WarningDeliveryController` 改读 `notification_record + notification_receipt`，重发走真实 `NotificationDispatcher.retry`。
- `ApprovalCenter`：请求体 `{decision, comment}` 对象化（原来传字符串被 `JSON.stringify` 成标量 → 400），状态映射前后端统一。

### P1 — 契约 / 数据

- 灾种/等级归一化、`reactive` 取 `.value` 修复（`HazardsListPage`、`ConfirmationWorkbenchPage`）。
- 退出登录补调 `/auth/logout`。
- 图像置信度阈值配置化（默认 0.35），与 AI 侧对齐。
- `schema.sql` 删除 14 张表重复 DDL（两个 118 行整块逐字相同）。

### P1 — AI

| 报告项 | 修复 |
|---|---|
| 重训后全量判 LOW | `model_contract.py` 建立 objective →（model_id、分值口径）唯一映射，二分类 ×100；`train_xgboost.py` 及各 `train_fusion_v*.py` 落 `objective/output_type/output_scale/model_id` 到 meta；`/retrain` 返回 `model_type_changed` |
| 归一化双口径 | 见「未做」 |
| 漂移三条假绿 | 删 hash 造假 PSI 曲线、删硬编码 `mean=45.2/psi=0.18`；新增 `NO_BASELINE` / `INSUFFICIENT_DATA` / `compared_features`；scipy 惰性导入 + numpy KS 降级；前端 `normalizeDriftLevel` + `ModelManagePage` 未判定时禁用重训 |

### P1 — 前端运行时

- `USE_BACKEND` 由导入期常量改为 `export let` + `subscribeRuntime`（181 处读取点零改动），修「模式切换后仍走 mock、徽标却说真数据」。
- `USE_LIVE_PERSONNEL` 改函数 `useLivePersonnel()`；`NotificationChannelsPage.backendMode` 改 computed。

### 本轮新增（死代码 / 一致性）

| 项 | 内容 |
|---|---|
| 等级映射 8→1 | 新增 `utils/warningLevel.js`（`normalizeWarningLevel` / `warningLevelLabel` / `warningLevelRank` / `warningLevelColor`，含地图低饱和色板），10 个页面改为只保留各自调色板、判定单源。修掉：实时推送红色预警兜底成 blue、地图红橙同色、移动端等级恒「未知」、灾害列表把橙色预警判成 red、`RouteForm` 的 `levelColor` 写在并列 `<script>` 导致模板取不到（避障列表非空即渲染报错） |
| 死代码 | 删除 `sentinelService`（286 行，自结构重组后 0 引用，且绕网关直连 8001） |

## 2. 验证

| 栈 | 命令 | 结果 |
|---|---|---|
| 前端 | `npx vitest run` | 24 文件 / **258 例全绿** |
| 前端 | `npx vite build` | 通过（ESLint 不覆盖 `.vue`，以 build 兜底） |
| 后端 | `mvn -o compile` | 通过 |
| 后端 | `mvn -o test` | 158 例 0 失败；12 例 `PostgresTestBase` 需 Docker daemon（本机未启，既有环境限制） |
| AI | `pytest tests -q` | **115 passed / 7 skipped** |

新增契约测试：`AiWarningSeedContractTest`、`SeedIdempotencyContractTest`、`test_model_contract.py`(11)、`test_drift_monitor.py`(5)、`runtimeLiveBinding.spec.js`、`driftLevelMapping.spec.js`、`warningLevel.spec.js`(9)。

## 3. 未做（需决策，非技术阻塞）

| 项 | 现状 | 为什么没动 |
|---|---|---|
| E4 灾种值域三套 | AI 中文六类含字典没有的「塌陷」，`crack→collapse` 与 `ground_fissure` 语义冲突 | 塌陷是否新增灾种、存量数据怎么迁移属产品决定 |
| E6 归一化双口径 | `comprehensive_model` 声明 `applied=0` 且字段被 response_model 丢弃 | 代码注释已明确「只读诊断、不改全局 NORM_MAX（避免并发串扰）」，是有意为之；真启用需把 `max_val` 改成每次推理的局部参数，属独立改造 |
| E7 前端直连 8001 | `AiDeprecatedGapController` 注释写明「不补代理」是刻意设计 | 死掉的那条直连链路（`sentinelService`）已删，剩余为文档化决策 |
| A1 残项 | `dashboardApi` 54 个方法无 `dashboardApi.*` 引用 | 其中 9 个被 `apiPagedContract.spec.js` 显式锁定为「零消费但契约在」（A 组），删除需同时削弱契约测试，收益（打包体积）与代价不匹配 |
| P2 批量 | `mock` 38 个 0 引用导出、后端 34 个死 Repository 派生方法、`styles.css` 130 个重复选择器 | 纯体积/整洁问题，改动面大且无行为收益，建议随对应模块重构一并处理 |

## 4. 推送前检查

- 所有提交均在本地 `release/delivery`，**未推送**。
- `git status` 干净，可直接 `git push` 开 PR。
