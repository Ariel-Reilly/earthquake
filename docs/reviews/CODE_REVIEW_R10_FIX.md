# R10 深度审查 · 修复报告

> 日期：2026-11-06　｜　对象：`CODE_REVIEW_R10.md`（6 个 P1 + 13 个 P2）
> 结论：**6 个 P1、13 个 P2 全部闭环**，另修复 4 项连带缺陷。全量回归 120/120、AI 102 passed、前端 8/8 + build、门禁全绿，沙盘真实链路复跑通过。
> 项目定位不变：教学 / 竞赛演示 / 毕业设计 / 内部测试，**不商用**（CAS CC-BY-NC-4.0 数据与现有权重不换源）。

---

## 一、P1 修复清单

| # | 问题 | 修复落点 | 验证证据 |
|---|---|---|---|
| P1-1 | EMQX 5.9 默认 allow-all，无凭据可向 `disasterguard/+/data` 注入伪造数据并生成预警 | `infra/emqx/init.sh` 生成 `built_in_database` 用户引导文件 + HOCON 声明式认证；`docker-compose.yml` / `docker-compose.prod.yml` 同步注入 `EMQX_USERNAME/EMQX_PASSWORD`；后端 `application.yml` 与网关 `config.yaml` 同源引用 | 三种连接实测：匿名 **拒绝**、错误密码 **拒绝**、正确凭据 **连通**；无凭据注入复现已不成立；后端日志 `EMQX 已订阅主题: disasterguard/+/data` |
| P1-2 | `WarningCreateService#doPersist` 为同类自调用，`@Transactional` 不走代理，主记录失败时 `risk_score_log` 会成为孤儿 | 改为 `@Lazy self` 代理调用；`EventStateService#createFromAiDetection` 补事务；WebSocket 推送移到事务外（推送失败不回滚业务写） | 编译通过；`WarningManualCreateTest` 等既有事务用例全绿 |
| P1-3 | R003 表达式右值依赖 `warning_level` 哨兵指标——该指标只有沙盘伪造、固件没有、库中 0 行 | `data.sql` 改为 `water_level > 175.0 AND rainfall_6h > 100`（警戒水位是规则参数）；`scenario_player.py` 移除 `warning_level` 上报；`config.yaml` 删除全部 `sentinels`；`sandbox_gateway.py` 删除 `inject_sentinels()` | **s3 江洪剧本实测出 R003 红色预警**（触发值 `water_level=176.23`）；纯函数校验 payload 仅 `water_level`/`rainfall_6h`；库内 6 类哨兵假指标清理 130 行后为 0 |
| P1-4 | 预警只创建不解除，"已有未关闭预警就跳过"导致同规则对该点终身只报一次（二次演示哑火） | `warning_event` 增 `occur_count` / `last_seen_at` / `auto_resolved`（含存量回填）；`RuleEngineScheduler` 命中时累加计数与 `last_seen_at`；新增 `WarningAutoResolveScheduler`（cron 与窗口可配，默认 30 分钟） | **运行态实测**：启动后自动解除 2 条静默预警 `WG-D8D965EE`(occurCount=5)、`WG-FDA7B6E1`(occurCount=28)；新增单测 4 条钉住 4 个落库字段 |
| P1-5 | 规则管理页读写 `rule` 表，预警引擎读 `warning_rule`，两张表同编号不同义，页面改阈值对预警零影响 | 新增 `WarningRuleSyncService` 双向桥：启动把 `warning_rule` 镜像进 `rule`；页面增删改反向编译条件树写回 `warning_rule`；删除只停用不硬删（保住 `warning_event.rule_code` 外键）；`RuleDto` 增 `warningRuleExpression/warningRuleActive`，前端 `RuleList.vue` 增「生效状态」列 | 启动日志 `规则管理页已与预警引擎对齐：6 条`；接口实测把 R004 阈值 5→6 后 `warning_rule.expression` 变为 `tilt_angle > 6` 并已恢复；新增单测 4 条 |
| P1-6 | `.env` 中 `EMQX_ENABLED=false` 与 yml 默认 `true` 失配；`sandbox_e2e.py` 体检第 4 项无条件 `return True`（假通过） | `.env` / `.env.example` 统一为 `true`；`check_emqx()` 改为 **真实探测**：TCP 连通 → Dashboard API 登录 → 查客户端列表确认后端订阅者在线，失败返回 `False` 并阻断演练 | `--check` 4/4 通过，日志 `OK 后端订阅者在线的实例 1 个 → 数据链路通` |

---

## 二、P2 修复清单（13 项）

| # | 修复 |
|---|---|
| P2-1 | `sandbox_e2e.py` 退出码按 `proc.returncode` / `tick` / 新增预警数判定：网关崩溃返 1、中断返 130、零预警返 3，自动化可判失败 |
| P2-2 | `summarize()` 用开跑时的 `last_id` 做下界，只统计本轮；修掉 `LIKE 'HZ-SBOX-%'` 被 psycopg2 当占位符导致的 `IndexError` |
| P2-3 | 删除哨兵机制全部残留：`config.yaml` 的 `sentinels` 字段与误导注释、`sandbox_gateway.py` 的 `inject_sentinels()` 与调用点、`scenario_player.py` 的 `warning_level` 上报 |
| P2-4 | `aiApi.js` 抽出 `fetchApi()` 统一拦截 401 → `redirectToLogin()` + 中文提示，13 处裸 `fetch` 全部走该包装 |
| P2-5 | `check-endpoint-parity.mjs` 正则纳入 `fetch/fetchApi`，并识别 `/api/` 已带前缀的情况；`orphan_endpoint_scan.py` 把结果拆成「确认未引用 / 疑似模板拼接误报」两类并列证据 |
| P2-6 | `data.sql` **不再单独播种 `rule` 表**（旧种子是 R004=设备离线 / R005=地震 / R003 引用哨兵），改由启动同步服务从 `warning_rule` 单一真相源生成；同步服务新建行补 `actions`（JSON NOT NULL） |
| P2-7 | `AiServiceClient` 加简易熔断：运输层连续失败 N 次 → 冷却期快速失败，预警自动降级 `RULE_ONLY`，避免 AI 挂起时每条规则串行等 10/30 秒 |
| P2-8 | `POST /api/weather/products/refresh` 加 `@PreAuthorize("hasAuthority('weather:refresh')")` 并同步进前端 `auth.js`/`PermissionRegistry`；**补丁修正**：该权限码此前从未进 `sys_permission` 也未授任何角色，导致端点对所有人恒 403。已在 `data.sql` 补 `weather:refresh` 种子并授予 `maintenance`，`admin` 经 blanket 全量授权自动拥有（详见第九节 A1） |
| P2-9 | `WarningCreateService` 日志改打印 `finalLevel`，与落库等级一致 |
| P2-10 | 删除 `AuthController` 改密接口「兼容旧参数 request」的不实注释 |
| P2-11 | `register_devices.sql` 隐患点由 `ON CONFLICT DO NOTHING` 改为 `DO UPDATE SET ...`，改名称/坐标后重跑生效 |
| P2-12 | 模型编码归一化失败时，把原始型号写入 `result_json._modelName`，不再静默丢失溯源 |
| P2-13 | `debug-trigger` 加 `disasterguard.debug-endpoints` 开关，默认关闭；实测返回 `403 调试端点已关闭` |

---

## 三、本轮连带修复（修复过程中新发现）

1. **`rule.conditions/actions` 是 jsonb，实体按 varchar 绑定** → 编辑规则直接 500，`RuleInitializer` 启动镜像首次整批失败。加 `@JdbcTypeCode(SqlTypes.JSON)`；同步服务改为**每行 `REQUIRES_NEW` 独立事务**，避免一条失败把整批标为 aborted。
2. **`sandbox_e2e.py` 的 s3 默认步数不足**：触发点在剧本 170s，`100 步 × 1.5s = 150s` 永远跑不到。文档改为按 `steps × interval ≥ 触发点` 选取步数（复验用 130 步）。
3. **7 个既有集成测试硬编码旧 `rule` 语义**（`RuleEngineIntegrationTest` 5 条、`RuleMatchingVerificationTest` 2 条），P1-5 语义统一后全部失败。改为通过 `RuleTestRequest.conditions()` **自带条件树**，保留全部原有场景与断言，测试不再耦合业务种子数据。
4. **`sync-permission-registry.mjs` 生成模板漏了 3 个静态方法**，重新生成后 `RoleController` 编译失败；已补回生成器模板。

---

## 四、验证证据

| 层次 | 命令 / 动作 | 结果 |
|---|---|---|
| 后端单测 | `set -a && . ./.env && set +a && SERVER_PORT=8080 && cd backend && mvn test` | **120 passed / 0 failed**（新增 10 条：`WarningAutoResolveSchedulerTest` 6 + `WarningRuleSyncServiceTest` 4；另 `WarningRuleEvaluatorTest` 6 条为既有新增类，计入总数） |
| AI 契约 | `cd ai-service && .venv/Scripts/python.exe -m pytest tests/ -q` | **102 passed / 4 skipped / 4 warnings** |
| 前端 | `npx vitest run` / `npm run build` | **8/8 passed** / `✓ built in 7.23s` |
| 门禁 | `check-missing-imports.mjs` / `check-endpoint-parity.mjs` / `check-math-random.mjs` / `check-broken-imports.mjs` / `orphan_endpoint_scan.py` | 缺失 import 0 / 疑似孤儿 0 / Math.random PASS / 断链 0 / 孤儿结果已分级（7 确认 + 33 疑似误报） |
| 真实库 | `docker exec disasterguard-postgres psql ...` | `rule` 与 `warning_rule` 各 6 行且表达式一致；哨兵假指标 0 行 |
| 运行态 | 后端启动日志 | EMQX 带凭据订阅成功；规则对齐 6/6；自动解除实测 2 条；debug-trigger 默认 403 |

> ⚠ **AI 测试命令坑**：在 `ai-service/` 根目录直接 `pytest -q` 会误收集 `scripts/test_*.py`，因缺 `_bootstrap` 模块报 5 个 collection error。必须显式指定 `tests/` 目录，这不是本轮代码回归。

---

## 五、完整预警模拟检测（沙盘 → 平台）

链路：`沙盘剧本 / 串口 → 网关标定 → MQTT(EMQX, 已鉴权) → 后端 → device_metric → 规则引擎 → warning_event → AI 融合评分`

复现命令：

```bash
# 链路体检（必须 4/4 通过）
ai-service/.venv/Scripts/python.exe scripts/demo/sandbox_e2e.py --check

# S3 江洪：验证 R003 在移除哨兵指标后仍由真实物理量触发
ai-service/.venv/Scripts/python.exe scripts/demo/sandbox_e2e.py --scenario s3 --steps 130 --interval 1.5 --reset

# S1 主剧本：暴雨型滑坡全链
ai-service/.venv/Scripts/python.exe scripts/demo/sandbox_e2e.py --scenario s1 --steps 165 --interval 2.0 --reset
```

**S3 实测**（130 轮 / 1.5s）：水位 175.11m→176.45m、6h 雨量 95.2→112.67mm，双条件同时满足后产出
`R003 | HZ-SBOX-03 | 红色预警 | trigger_value=water_level=176.23 | 待确认`，退出码 0，本轮新增 1 条预警。

**S1 实测**：164 轮产出 R001 / R005 / R002 三条预警，退出码 0（明细与 AI 降级说明见第六节）。

---

## 六、S1 主剧本演练补记（完整预警模拟检测）

**执行**：`--scenario s1 --steps 165 --interval 2.0 --reset`，网关 164 轮 / 约 5.5 分钟，**exit=0，本轮新增 3 条预警**。

| 规则 | 隐患点 | 等级 | 触发值 | occur_count | 状态 |
|---|---|---|---|---|---|
| R001 | HZ-SBOX-01 | 黄色预警 | `rainfall_24h=55.31` | 5 | 待确认 |
| R005 | HZ-SBOX-01 | 黄色预警 | `displacement_rate=5.43` | 2 | 待确认 |
| R002 | HZ-SBOX-01 | 橙色预警 | `rainfall_24h=90.48` | 1 | 待确认 |

**真实库核验（4 项全过）**：
1. 6 类哨兵假指标 `COUNT(*)=0`（P1-3 / P2-3 生效，沙盘只上报真实物理量）；
2. `occur_count` 为 5/2/1 且 `auto_resolved=false`（P1-4 复现计数生效）；
3. `warning_rule w JOIN rule r ... WHERE r.dsl_text IS DISTINCT FROM w.expression` 返回 **0 行**（P1-5 两表语义完全一致）；
4. 启动阶段自动解除 2 条静默 30 分钟以上的历史预警（occurCount=5 / 28）。

**AI 融合的如实说明（重要，不要夸大）**：
- 演练时 **ai-service 未启动**（8001 无响应）。这条链恰好真实验证了本轮新增的 P2-7 熔断：日志 `AI 服务运输层连续失败 3 次，熔断 5 分钟（Connection refused）`，演练期间 3 条预警全部按设计降级 `source=RULE_ONLY`，**规则引擎没有因 AI 挂起而停摆**（若按旧逻辑，每条规则要串行等 10/30 秒超时）。
- 随后拉起 ai-service 并复位熔断，单独验证 AI 链路：直连 `POST :8001/api/ai/fusion-risk` → `risk_score=33.07, model=xgboost-fusion-v3, mock=false`；后端网关 `POST /api/ai/fusion-risk` → **HTTP 200**、`riskScore=19.47`、`degraded=false`。
- 再以正确 MQTT 凭据注入一条 `rainfall_24h=99.0`，规则引擎下一轮生成 `R001 黄色预警 source=AI_AUTO`，`ai_analysis_result` 近 3 分钟新增 2 行 —— **预警创建时的 AI 融合评分与审计写库均恢复正常**。

> 结论口径：主链（沙盘 → MQTT → 规则引擎 → 预警）在 AI 服务**在线与离线两种状态下都能出预警**；AI 融合在在线时为 `AI_AUTO`、离线时降级 `RULE_ONLY`。演练表格里的 `RULE_ONLY` 是环境原因，不是本轮代码回归。

---

## 七、遗留风险与部署提示（dev / prod 有别）

| 项 | 状态 | 说明 |
|---|---|---|
| EMQX 口令 | ⚠ **未闭环** | `.env` 仍是 `CHANGE_ME_emqx_secure_password_2024` 占位值；已具备认证能力但**口令未换**。上生产前必须 `openssl rand -base64 24` 生成，且同步改 `.env` 的 `EMQX_PASSWORD` / `EMQX_DASHBOARD_PASSWORD` 后重建容器 |
| 通知投递 | 既定决策，非缺陷 | 空白名单 fail-closed，回执 `BLOCKED`；演示链路上预警真实落库、通知不真实送达，不得对外说「已送达」 |
| AI 模型精度 | 不可对外宣称 | `eval_dataset=synthetic`；sklearn 1.5.1→1.5.2 有 `InconsistentVersionWarning` |
| 规则阈值持久性 | 既有行为 | 页面改的阈值在**重新执行 `data.sql`** 时会被种子 `DO UPDATE` 覆盖；正常重启不会（`spring.sql.init` 非 always） |
| `weather:refresh` 归属 | 一致性设计 | `maintenance` 显式持有（与端点注释「运维手动刷新」一致）；`admin` 经 `sys_role_permission` 全量 blanket 授权自动拥有**全部**权限（含 `weather:view` / `weather:refresh`），并非"无 weather:view"；其余未授予该权限的角色（如 `attendant`/`expert`）恒 403 |
| 工程化 | 未做 | 无 CI；`prod` compose 必须用 `-f docker-compose.yml -f docker-compose.prod.yml` |

**部署顺序（prod）**：换强口令 → `docker compose -f ... up -d emqx` 重建使引导文件生效 → 启动后端（确认日志「EMQX 已订阅主题」）→ `--check` 体检 → 演练。
**常见坑**：① 网关与后端 MQTT 凭据必须同源；② 同隐患点同指标只能一台设备上报；③ 上报间隔 ≥1s（唯一索引）；④ 连演必加 `--reset`，否则未关闭预警会让同规则跳过；⑤ 全场只能有一个 `--mode scenario` 网关进程。

---

## 八、改动文件概览

- 后端：新增 `WarningRuleSyncService` / `WarningAutoResolveScheduler`；改 `WarningCreateService` / `RuleEngineScheduler` / `RuleInitializer` / `RuleEngineService` / `RuleDto` / `Rule` / `WarningEvent` / `WarningRule` / 两个 Repository / `AiServiceClient` / `AiAnalysisService` / `WeatherController` / `WarningController` / `AuthController` / `PermissionRegistry` / `EventStateService` / `application.yml` / `schema.sql` / `data.sql`
- 前端：`aiApi.js` / `auth.js` / `RuleList.vue`
- 沙盘与脚本：`infra/emqx/init.sh` / 两个 compose / `hardware-sandbox/gateway/{config.yaml,sandbox_gateway.py,scenario_player.py}` / `register_devices.sql` / `scripts/demo/sandbox_e2e.py` / 3 个审计脚本 / `sync-permission-registry.mjs`
- 测试：新增 3 个测试类，适配 2 个既有集成测试类
- 迁移（A1 既有库补齐 `weather:refresh` 权限）：`scripts/migrations/V20261107_weather_refresh_permission.sql`

---

## 九、补充修复 A1 / A2（2026-11-07）

基于 R10 核对清单的第 1 轮复核，本轮只做两项定点修复，未顺手改其他。

### A1 · 修复 `weather:refresh` 恒 403（对应核对点 #12）

**问题**：`WeatherController.refresh()` 加了 `@PreAuthorize("hasAuthority('weather:refresh')")`，但 `data.sql` 从未把 `weather:refresh` 写进 `sys_permission`，也未授给任何角色 → 该端点对**所有人**（含 `maintenance`/`admin`）恒 403，P2-8 声称"已完成"并不成立。

**改动**：
1. `data.sql` `sys_permission` 补 `'weather:refresh'` 种子（OP 类型，route `/dashboard/weather`，sort 17；原 `history:*` 顺延到 18/19，因 `ON CONFLICT (code) DO NOTHING` 重跑安全）。
2. `data.sql` `maintenance` 角色授权块补 `((SELECT id FROM sys_role WHERE role_code='maintenance'),'weather:refresh')`。
3. **授权策略确认**：`admin` 的授权是 blanket 全量（`INSERT ... SELECT ... WHERE r.role_code='admin'`，`data.sql:247-251`），凡进入 `sys_permission` 的权限都会自动授予 `admin`。因此补种子后 `admin` **自动拥有** `weather:refresh`（以及 `weather:view`），无需单独授权。`maintenance` 走显式授权块，需单独补一行。

**既有库如何获得该权限（机制选择）**：
- 选 **(a) 独立幂等迁移脚本** `scripts/migrations/V20261107_weather_refresh_permission.sql`，**不**选 (b) 启动期 upsert。
- 理由：项目明确把一次性迁移放在 `spring.sql.init` 之外（`schema.sql:1493/1612` 注释、既有 `backend/migrate_rule_operators.sql` 约定）；且 `application-prod.yml` 写明 `schema.sql/data.sql` 属"首次部署建表…手工执行一次"，**非每次启动自动跑**。因此一个早于本次修复、已初始化的既有库**不会**因 `data.sql` 重跑而自动获得新种子（除非人工重跑 data.sql），存在"只改 data.sql 却宣称闭环"的盲区。独立迁移脚本幂等（`ON CONFLICT DO NOTHING`），可对任意既有库显式执行、可审计、可重复，符合项目约定；(b) 会把迁移逻辑藏进应用启动，违背该项目"迁移不进 boot"的既有约定。
- 脚本作用：`sys_permission` upsert 种子 + 授予 `maintenance` + 授予 `admin`（与 blanket 对齐）。
- 执行方式（脚本内注释已写明）：
  - 本地：`psql -h 127.0.0.1 -U <dbuser> -d <dbname> -f scripts/migrations/V20261107_weather_refresh_permission.sql`
  - 容器：`docker exec -i disasterguard-postgres psql -U disasterguard -d disasterguard -f /<mount>/scripts/migrations/V20261107_weather_refresh_permission.sql`
- 覆盖范围总结：**全新库**由 `data.sql` 播种（无需迁移）；**既有库**由本迁移脚本补齐。

**验证（SQL 级，已本地 PostgreSQL 实测；HTTP 端到端未做）**：
- 起本地 PostgreSQL，建库 → 跑 `schema.sql`（exit 0）→ 跑 `data.sql`（exit 0）。
- 重跑 `data.sql` 后的三查（全新库路径），原始输出：
  ```
  -- Q1: 权限种子应有 1 行
  SELECT code FROM sys_permission WHERE code='weather:refresh';
        code
  -----------------
   weather:refresh
  (1 row)

  -- Q2: maintenance 授权
  SELECT r.role_code, p.code FROM sys_role_permission rp
    JOIN sys_role r ON r.id=rp.role_id JOIN sys_permission p ON p.code=rp.perm_code
   WHERE p.code='weather:refresh' AND r.role_code='maintenance';
    role_code  |      code
  -------------+-----------------
   maintenance | weather:refresh
  (1 row)

  -- Q3: admin 授权（blanket）
  ... AND r.role_code='admin';
    role_code |      code
  -----------+-----------------
   admin      | weather:refresh
  (1 row)
  ```
- 模拟"既有库缺失该权限"：先 `DELETE` 种子（`sys_role_permission` 删 2 行、`sys_permission` 删 1 行，剩余 0）→ 跑迁移 → `INSERT 0 1`×3（权限 + maintenance + admin），回显权限 1 行、授权 `admin`/`maintenance` 共 2 行。再跑一次迁移 → 仍 1 行（幂等，无重复、无报错，exit 0）。
- HTTP 端到端（启动后端 + 用 `fieldops`/`duty` 调 `POST /api/weather/products/refresh` 验 200/403）**未执行**：本机 `docker ps` 无运行容器、本地无 Postgres 镜像缓存，已在本地 PG 上用等价 SQL 验证授权逻辑成立，待 infra 启动后补该步骤。

**状态**：代码已改；SQL 级验证通过；HTTP 端到端待 infra 启动后验证（未验证）。

### A2 · 修复自动解除误解除（对应核对点 #4）

**问题**：`WarningAutoResolveScheduler` 仅按 `last_seen_at` 静默超时解除。设备掉线 / 数据中断时，规则引擎收不到数据而不再命中，`last_seen_at` 停滞 → 30 分钟后被误判为"风险解除"，掩盖真实在险。

**方案选择**：采用「**离线则暂不解除**」（而非新增"数据中断待核查"状态）。理由：零 schema 变更、零前端改动、改动面最小；预警保持未关闭，下一周期（每分钟）重新评估——设备恢复且风险真消除时再解除，风险仍在则继续挂起，安全方向正确（多留一条开放预警比错误关闭更可接受）。解除日志打印 `设备离线/数据中断，暂不解除（掉线≠安全）`，演示可解释。多设备隐患点任一设备离线即跳过解除，属故意保守——宁可多留一条开放预警，不错误关闭。

**改动**：`WarningAutoResolveScheduler` 注入 `MonitoringDeviceRepository`，解除前对每个隐患点调用 `deviceDataInterruptedReason()`：若预警无 `hazard_code` 或隐患点未关联设备 → 按原逻辑解除；若关联设备任一 `online_status != '在线'` → 跳过解除并 `log.warn`。被跳过的预警不推送 WebSocket（避免演示出现"解除"假象）。

**测试**：`WarningAutoResolveSchedulerTest` 新增两条——
- `skipsResolveWhenDeviceOffline`：设备离线 + `last_seen_at` 超 30min → 解除数 0、状态仍"待确认"、不写 `closed_at`、不 save/不推送；
- `resolvesWhenDeviceOnline`：设备在线 + 超时 → 正常解除。
既有 4 条测试构造器补 `MonitoringDeviceRepository` 桩（默认 `findByHazardCode` 返回空 → 仍正常解除），保持原有断言。

**验证**：`mvn test -Dtest=WarningAutoResolveSchedulerTest` 全绿（6/6）。

