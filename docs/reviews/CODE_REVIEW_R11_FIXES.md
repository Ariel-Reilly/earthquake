# R11 审查修复记录

> 对应报告：[`CODE_REVIEW_R11.md`](./CODE_REVIEW_R11.md)
> 分支：`release/delivery` · 起点 `8aad677` 之前 → 终点 `3144b3c`
> 规模：14 个修复提交 + 2 个文档提交

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

#### E4 灾种值域（复审后修正定性：不是产品决策，是写入边界没归一化）

AI 侧 `SUPPORTED_DISASTER_TYPES` 返回**中文**六类（含「塌陷」「其他」），
`HazardService#createFromAiDetection` 原样写 `hazard_type`，而库表 / `sys_dict` / 前端字典都是英文码 11 类。

| 动作 | 内容 |
|---|---|
| 新增 `common/dict/HazardTypeCodes` | 11 码 + 中文别名表 + `toCode()` / `toCodeOrOriginal()`；`塌陷 → subsidence`（不新增第 12 类 sinkhole）；`其他/待定/未知 → null` |
| 写入边界 | AI 建点与手工建点都先归一化；展示名仍用 AI 中文原文 |
| 去重 | 无类型时改用 `hazard_type IS NULL` 的派生查询 —— 派生查询传 `null` 会生成 `hazard_type = null`（SQL 恒不成立），否则每个调度周期重复建点 |
| 实体 | `HazardPoint.hazardType` 放宽为 nullable（物理列本就可空） |
| 去重实现 | 删除 `WarningService` 的私有映射表，三处口径合一（此前「塌陷」在 Java / 前端 / 别名表里分别是 subsidence / collapse / subsidence） |
| 存量 | `backend/migrate_hazard_type_codes.sql`：先备份快照再幂等 UPDATE（`unified_event` 基表 + `warning_event`） |
| 测试 | `HazardTypeCodesTest`(6) + `hazardTypes.spec.js`(5) |

### P1 — AI

| 报告项 | 修复 |
|---|---|
| 重训后全量判 LOW | `model_contract.py` 建立 objective →（model_id、分值口径）唯一映射，二分类 ×100；`train_xgboost.py` 及各 `train_fusion_v*.py` 落 `objective/output_type/output_scale/model_id` 到 meta；`/retrain` 返回 `model_type_changed` |
| 归一化双口径 | 三个模型统一为「诊断只读」（方案 A）：`SCORING_USES_ADAPTIVE_MAX=False`，评分恒用静态 `NORM_MAX`；窗口仍 fit/查询但只做诊断；`ComprehensiveResponse` 补 `adaptive_normalization` / `region_code`，`applied=0` 如实返回；删掉 device_health 里从未参与计算的 `local_p95` 死变量与其 `+adaptive-norm` 标签 |
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
| 前端 | `npx vitest run` | 25 文件 / **263 例全绿** |
| 前端 | `npx vite build` | 通过（ESLint 不覆盖 `.vue`，以 build 兜底） |
| 前端 | `npm run knip` | 可运行（未使用导出 16 组 / 重复导出 2 组，非阻断） |
| 后端 | `mvn -o compile` | 通过 |
| 后端 | `mvn -o test` | 164 例 0 失败；12 例 `PostgresTestBase` 需 Docker daemon（本机未启，既有环境限制） |
| AI | `pytest tests -q` | **123 passed / 7 skipped** |

新增契约测试：`AiWarningSeedContractTest`、`SeedIdempotencyContractTest`、`HazardTypeCodesTest`(6)、`test_model_contract.py`(11)、`test_drift_monitor.py`(5)、`test_adaptive_norm_determinism.py`(8)、`runtimeLiveBinding.spec.js`、`driftLevelMapping.spec.js`、`warningLevel.spec.js`(9)、`hazardTypes.spec.js`(5)。

需真实数据库的 4 类变更（schema 去重、seed 幂等、`notification_record.read_at` 新列、AI 伪规则）本机未验证，由 CI 的 12 个 `PostgresTestBase` 用例覆盖（ubuntu-latest 有 Docker）。

## 2.1 复审修正（与审查意见不一致的三处，均已实测）

| 审查意见 | 实测 | 修复取舍 |
|---|---|---|
| `adaptive_normalizer`「单例 + 可变 deque，**无锁**，并发写 dict/deque 无保护」 | `AdaptiveNormalizer` 自 `__init__` 起就有 `threading.Lock`，`fit_one` / `get_dynamic_max` / `get_stats` 全部在锁内读写 | 并发写不是风险点，未加锁；真问题是「窗口是**进程级共享状态**，由别的请求推进 → 同输入分数不可复现」，按此修复 |
| `device_health_model`「实际参与打分」 | `_score_series` 里 `local_p95 = get_dynamic_max(...)` 取了之后**从未参与计算**，分数用 `-raw * 100` | 该模型分数本来就是确定的；删掉死变量，并把 `model` 标签里的 `+adaptive-norm` 去掉（标签曾宣称一个没生效的能力）。真正受影响的只有 `disaster_assessment` 的 16 处分母 |
| P1-D1「主配置 `never` + `application-dev.yml` 显式 `always`」 | 12 个 `PostgresTestBase` 集成测试跑在 `default` profile，schema 正是靠主配置的 `always` 建出来的 | 只改这两行会让这批用例集体失败；需同时给测试侧显式注入 `sql.init.mode=always`。本 PR 不做（见第 3 节） |

## 3. 未做（需决策，非技术阻塞）

| 项 | 现状 | 为什么没动 |
|---|---|---|
| E7 前端直连 8001 | `AiDeprecatedGapController` 注释写明「不补代理」是刻意设计；生产回落 501 并显示「能力未接入」 | 死掉的那条直连链路（`sentinelService`）已删，剩余为文档化决策，宜写进 `KNOWN_LIMITATIONS` |
| A1 残项 | `dashboardApi` 61 个方法生产零引用 | 其中 16 个被 `apiPagedContract.spec.js` 显式锁定为「零消费但契约在」，删除需同时削弱契约测试；已加 knip 报告让零引用持续可见 |
| P1-D1 残余 | seed 幂等已修，但 `schema.sql` 仍含 `DROP VIEW IF EXISTS hazard_point`，dev 仍 `mode: always` → 每次重启仍走迁移块 | **改法不是「主配置 never + dev always」这么简单**：`PostgresTestBase` 的 12 个集成测试跑在 `default` profile 上，正是靠主配置的 `always` 建 schema；直接改会让这批用例集体失败。正确改法需同时给测试侧显式 `spring.sql.init.mode=always`（如 `@DynamicPropertySource`）。属部署/测试配置联动改动，留待单独 PR 并在 CI 上验证 |
| P2 批量 | `mock` 38 个 0 引用导出、后端 34 个死 Repository 派生方法、`styles.css` 130 个重复选择器 | 纯体积/整洁问题，改动面大且无行为收益，建议随对应模块重构一并处理 |

## 4. 推送前检查

- 建议按 4 个 PR 拆分（A 权限/P0 → B 状态/通知 → C AI/前端 → D 种子/schema/死代码），
  每个合并后把 `release/delivery` 重置到 main。
- 真实数据库验证依赖 CI（`PostgresTestBase` 12 例需 Docker），本地无法覆盖。
