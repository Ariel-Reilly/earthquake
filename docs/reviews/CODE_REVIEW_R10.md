# DisasterGuard 代码审查报告 R10（全项目深度复审）

- **审查时间**：2026-11-06 20:30–21:10
- **审查范围**：全项目 + 本轮未提交改动（45 文件 / +1622 −282）+ 沙盘端到端链路运行态
- **审查方式**：git diff 逐行 + 门禁四件套 + 容器运行态 + 真实库只读核验 + HTTP/MQTT 实测复现
- **基线**：HEAD `0fbc2fc` + 工作区改动（沙盘链路 / AI 融合 / 随机数门禁 / JwtBlacklist）
- **结论**：**6 项 P1、13 项 P2，无新增 P0**。本轮沙盘与 AI 融合修复本身验证通过，但暴露出三类结构性问题：
  ①**设备鉴权只保护了 HTTP，主数据通道 MQTT 完全裸奔**（已端到端复现造出真实红色外预警）；
  ②**预警写入的事务边界失效**（自调用绕过 @Transactional，与类注释承诺相反）；
  ③**"规则管理页"和"真正生效的规则"是两张表**，页面改阈值对预警零影响。

---

## 一、P1（严重，影响可用性 / 数据正确性 / 安全边界）

### P1-1　MQTT 主数据通道零鉴权，设备密钥形同虚设（已复现造出预警）

**位置**
- `docker-compose.prod.yml:52-62`（已声明的"已知安全缺口"）
- `backend/.../common/security/DeviceAuthFilter.java:102-110`（只覆盖 `/api/devices/**`）
- `backend/.../device/mqtt/MqttDataSubscriber.java:39`（订阅 `disasterguard/+/data`，无任何凭据校验）

**证据（实测）**
```bash
docker exec disasterguard-emqx emqx ctl conf show authentication   # authentication = []
docker exec disasterguard-emqx emqx ctl conf show authorization    # no_match = allow
# 无用户名/密码发布到真实沙盘设备主题
publish("disasterguard/SB-RAIN-01/data", '{"metrics":{"rainfall_24h":999.0}}')
```
结果（后端已订阅、规则引擎 5s 一轮）：

| 环节 | 实测结果 |
|---|---|
| device_metric | `SB-RAIN-01 / rainfall_24h / 999 / 20:40:00` |
| warning_event | `WG-44D4E7DF / R001 / HZ-SBOX-01 / rainfall_24h=999.0 / AI_AUTO / 待确认` |

即**一个无任何凭据的客户端可以让平台生成一条真实预警**。前端给出的红色/橙色预警、RiskScore 曲线、通知派发全部会被这条伪造数据驱动。

**影响**：`X-Device-Key` 这套设备身份体系只保护了 HTTP 补录通道（实测：正确密钥 200、错误密钥 401），
而**生产的主数据源是 MQTT**，两边强度不对等，等于没有设备鉴权。对外不能宣称"设备已鉴权/数据可信"。

**修复建议**
1. EMQX 侧补 authn + ACL：`username=deviceCode`、`acl` 限定只能 publish `disasterguard/${username}/data`；
2. 后端 `MqttDataSubscriber` 增加一层校验：payload 的 `deviceCode`（topic 段）必须与 `monitoring_device.api_key` 派生签名一致，
   或要求 payload 带 `sig=HMAC(api_key, ts+body)`，无签名直接丢弃并计数告警；
3. 在补齐前，`prod` 必须保持端口只绑回环（现已如此），并在 `DEPLOYMENT.md` 明确"当前数据不可信"。

---

### P1-2　`doPersist` 自调用导致 `@Transactional` 失效，预警与评分日志非原子

**位置**：`backend/.../warning/engine/WarningCreateService.java:139`（调用）、`:154`（注解）

**证据**
```java
public void createWarning(...) {          // 无 @Transactional
    ...
    doPersist(...);                       // ← 同类自调用，未走代理
}
@Transactional
public String doPersist(...) { ... }
```
- 全仓无 `@EnableAspectJAutoProxy(exposeProxy=true)` / AspectJ 织入（`grep` 结果为空）→ 只能靠代理；
- 对比：`ImageAnalysisListener:126`、`WarningController:234` 通过注入的 Bean 调用，是走代理的；
- 该类注释第 24 行明确写「通过 Spring 代理生效 @Transactional」、第 70 行写「保证主记录与风险日志原子性」——**与实际行为相反**。

**影响**：规则引擎这条主链路上，`warning_event` 与 `risk_score_log` 各自独立提交。riskLog 写失败会留下孤儿预警；
统一事件、WebSocket 推送也在同一方法内，失败点不同会产生"前端已收到、库里没有"的不一致。

**修复建议**：把 `doPersist` 抽到独立 `WarningPersistService`（或注入 `@Lazy WarningCreateService self` 调用 `self.doPersist(...)`），
与 `DeviceDataService` 已有的 `self` 写法保持一致。

---

### P1-3　R003 红色洪水预警在真实设备下永不触发（阈值挂在"只有沙盘才上报"的指标上）

**位置**：`backend/src/main/resources/data.sql:646`、`hardware-sandbox/gateway/config.yaml:73`

**证据**
```sql
('R003', ..., 'water_level', 'water_level > warning_level AND rainfall_6h > 100', ...)
```
- `warning_level` 不是设备物理量，而是沙盘网关 `config.yaml:73 warning_level_m: 175.0` 转成的一条"指标"（`sandbox_gateway.py:214`）；
- 真实库当前 `SELECT ... WHERE metric_code='warning_level'` → **0 行**；
- `hardware-sandbox/firmware/` 中无 `water_level` / `warning_level` 上报实现；
- `hardware-sandbox/tools/selftest.py:39` 与 `scenario_player.py:50` 都依赖这一约定。

**影响**：`evaluateSimple` 遇到 `metrics.get("warning_level") == null` 直接 `return Optional.empty()`（`WarningRuleEvaluator.java:80-84`），
所以**换成真实水位计、或沙盘网关不注入该键时，R003 静默失效**——红色最高等级预警永远不会出现，且不会报错。
更根本的问题是：**警戒水位属于业务参数，却被藏在网关配置文件里**，平台侧（库 / 页面 / 接口）无法查看、无法修改。

**修复建议**：把警戒水位落成设备属性（`monitoring_device` 增加 `threshold_json`）或规则参数表，表达式改为
`water_level > 175.0 AND rainfall_6h > 100` 并把 175.0 做成可配置项；沙盘侧不再伪造 `warning_level` 指标。

---

### P1-4　预警没有自动解除机制 → 同一 (规则, 隐患点) 触发一次后永久哑火

**位置**：`backend/.../warning/engine/RuleEngineScheduler.java:145`

**证据**
```java
if (openWarningKeys.contains(rule.getRuleCode() + "|" + hazardCode)) continue;  // 只判"是否未关闭"
```
真实库现状：

| status | count |
|---|---|
| 已解除 | 25 |
| 待确认 | **8** |
| 处理中 / 已确认 / 待发布 / 已发布 | 各 1 |

最老的待确认：`WG-BE4516B7 / R004 / HZ-004 / 2026-10-25`（已挂 12 天）。全仓无任何"指标回归正常→自动解除"的调度器
（`grep 自动关闭|autoClose` 仅命中 AI 预警去重与本次读码处）。

**影响**：一条预警只要没人手工点掉，该规则对该点**终身只报一次**。对"持续性监测"平台来说是功能性缺陷：
第二场暴雨来临时平台不会再次报警，而这正是最需要它工作的时刻。对演示同样是隐患——上一轮遗留的待确认预警会让下一轮"哑火"（本轮已踩过一次）。

**修复建议**：加"复现计数 + 静默超时"策略：每轮命中但已有未关闭预警时，`occur_count++`、更新 `last_seen_at`；
连续 N 分钟（可配）不再命中则自动转「已解除（自动）」，并在前端标注"自动解除"。

---

### P1-5　"规则管理"页面改的是 `rule` 表，实际预警读 `warning_rule`——改阈值对预警零影响

**位置**
- 前端：`frontend/src/services/_architecture/domain/ruleEngineService.js:19-44`（`/api/rules` CRUD）
- 后端写：`backend/.../ruleengine/RuleEngineService.java:191/213/251`（写 `rule` 表）
- 后端读（真正生效）：`backend/.../warning/engine/RuleEngineScheduler.java:87`（读 `warning_rule`）

**证据**
- `ruleengine/RuleEngineService#execute(...)` **无任何调用点**（全仓 grep 为空）→ `rule` 表被编译进缓存后从不参与判定；
- `warning_rule` 没有 CRUD 控制器，只有 `DashboardController` 只读统计 + `RuleInitializer` 兜底种子；
- 两张表同编号不同语义：`rule.R004 = 设备离线`，`warning_rule.R004 = tilt_angle > 5`。

**影响**：用户在「规则管理」页把阈值从 50 改成 30、或把规则停用，**预警行为完全不变**。
这属于"页面有交互、后端有落库、但业务闭环断了"的典型问题，对外讲解时不能说"规则可在线配置、即时生效"。

**修复建议**：二选一——① 让 `/api/rules` 的 CRUD 直接读写 `warning_rule` 并同步字段（metric_code/expression/warning_level）；
② 保留 `rule` 作为"规则模板库"，增加"发布到预警引擎"的动作与状态位，并在页面明确标注当前版本是否已生效。

---

### P1-6　`.env` 仍为 `EMQX_ENABLED=false`，yml 改默认值不生效；体检第 4 项恒真 → 演示静默失败

**位置**：`.env:47`、`backend/src/main/resources/application.yml:190`、`scripts/demo/sandbox_e2e.py:168-174`

**证据**
```bash
$ grep EMQX_ENABLED .env
47:EMQX_ENABLED=false
# 按文档 set -a && . ./.env 启动后：
INFO c.d.device.mqtt.MqttDataSubscriber : EMQX 桥接已禁用（emqx.enabled=false），跳过 MQTT 订阅
```
即本轮把 yml 默认值改成 `true`（`application.yml:190`）**在实际启动路径上完全没有生效**——
文档里的启动命令就是 `set -a && . ../.env`，环境变量优先级高于 yml。

同时 `sandbox_e2e.py --check` 的第 4 项：

```python
def check_emqx(cfg, env) -> bool:
    print("...")          # 只打印提示
    return True           # 恒真，不做任何校验
```
它**永远返回 True**，所以体检会打印「链路体检通过」，但实际一条数据都进不来。这正是本轮已踩过的"哑火"最可能的现场复现路径。

**修复建议**
1. `.env` 与 `application.yml` 默认值同源（改 yml 就同步 `.env` / `.env.example`，并在 `.env.example` 显式列出该键）；
2. `check_emqx` 改为真实探测：查 EMQX `/api/v5/clients` 或订阅一次自检主题，或读后端 `/api/system/health` 里的 mqtt 状态字段；
3. `--check` 失败时明确输出"数据进不来"，而不是"通过"。

---

## 二、P2（中等 / 轻微）

| # | 位置 | 问题 | 证据 | 建议 |
|---|---|---|---|---|
| P2-1 | `scripts/demo/sandbox_e2e.py:265` | 网关崩溃也返回 0，自动化无法判定失败 | `return 0` 无条件；`proc.poll()` 立即非空时 `tick=0` 仍返回 0 | 按 `proc.returncode` 与"是否产生预警"决定退出码 |
| P2-2 | 同上 `:276` | `summarize` 查的是全库最近 10 条，非本轮 | `WHERE hazard_code LIKE 'HZ-SBOX-%' ORDER BY id DESC LIMIT 10` | 用开跑时的 `last_id` 做下界 |
| P2-3 | `hardware-sandbox/gateway/config.yaml:94-108`、`sandbox_gateway.py:225-230` | 仍在注入哨兵假指标 `rainfall_24h_disp/slope=1.0`，而它们对应的后端门控**已被删除** | 短跑 3 步后库里即出现 `rainfall_24h_disp=1.0`、`rainfall_24h_slope=1.0` 各 3 行；注释仍写 `if (!metrics.containsKey(rule.getMetricCode())) continue;` | 删除 `sentinels` 机制与相关注释（后端已按表达式求值，不再需要） |
| P2-4 | `frontend/src/services/_architecture/domain/aiApi.js:15-25` | 自带的 fetch 封装无 401 处理，token 过期只抛 `AI API 401: Unauthorized`，不跳登录 | 与 `core/httpClient.js:71` 的处理不一致 | 复用 `httpClient.request` 或补 `redirectToLogin()` |
| P2-5 | `scripts/audit/check-endpoint-parity.mjs:22,139` + `scripts/audit/orphan_endpoint_scan.py` | 两套对账脚本覆盖都不完整：parity 只解析 `request(`（`aiApi.js` 的 15 处 `fetch()` 全部漏掉）；orphan scan 不认模板拼接，报告 40 个"未被引用" | 确认误报示例：`/api/ai/chat`（`aiApi.js:218`）、`/api/sentinel/coverage`（`dashboardApi.js:1045`）；40 个里 34 个末段字符串在前端出现 | 统一解析器；orphan scan 至少支持 `${...}` 拼接与 `fetch(` |
| P2-6 | `data.sql` `rule` 表 vs `warning_rule` | 同编号两套语义（R004：设备离线 / 倾角>5），运维极易误配 | 见 P1-5 | 与 P1-5 一并处理，保留一套编号体系 |
| P2-7 | `WarningCreateService.java:87-136` | AI 融合同步调用在规则引擎线程内，`ai.timeout-ms` 默认 30s；AI 挂时每条规则串行等待，`running` 守卫会丢弃整轮 | `AiServiceClient` connect 10s / read 30s | 加熔断（连续 N 次失败后短路 5 分钟）或改异步 + 结果回填 |
| P2-8 | `WeatherController.java:75` | `POST /api/weather/products/refresh` 无权限码，任意登录用户可触发外部回源 | 仅靠 `authenticated()` | 加 `@PreAuthorize("hasAuthority('weather:refresh')")` |
| P2-9 | `WarningCreateService.java:205` | 日志打印 `level` 而非 `finalLevel`，AI 升级后日志与落库等级不一致 | `log.info("... finalLevel={}", level, source)` | 改为 `finalLevel` |
| P2-10 | `AuthController.java:141` | 注释称"兼容旧参数命名 request"，实际无兼容分支 | `ChangePasswordRequest req = requestBody;` | 删注释或补 `@JsonAlias` |
| P2-11 | `hardware-sandbox/tools/register_devices.sql:36` | 隐患点用 `ON CONFLICT DO NOTHING`，改名称/坐标后重跑不生效（设备表用的是 `DO UPDATE`） | 同文件 `:63` 设备表为 `DO UPDATE` | 隐患点也改 `DO UPDATE SET ...` |
| P2-12 | `AiAnalysisService.java:60-75` | 模型编码归一化失败时静默写 `NULL`，模型溯源丢失 | 外键列可空，只打 WARN | 至少把原始型号写进 `result_json` 顶层字段并在页面展示（现仅存在于原始 JSON） |
| P2-13 | `WarningController.java:219-247` | `debug-trigger` 硬编码 `R004/HZ-004/红色预警`，注释称"生产建议关闭"但无开关 | 有 `warnings:create` 即可调用 | 加 `@ConditionalOnProperty(disasterguard.debug-endpoints)` |

---

## 三、验证为正确的部分（避免只报问题）

| 项 | 验证方式 | 结果 |
|---|---|---|
| 后端编译 | `mvn.sh -q clean compile` | SUCCESS |
| 后端测试 | `mvn.sh test` | **110/110**（含新增 `WarningRuleEvaluatorTest` 6 条） |
| 前端单测 | `npm test` / `npx vitest run` | **8/8** |
| 前端构建 | `npm run build` | ✓ 6.59s（先 `mv dist dist.prev`） |
| 静态门禁 | missing-imports / endpoint-parity / math-random / timer-leaks | 全部 EXIT=0，`Math.random` 违规 0 |
| 设备 HTTP 鉴权 | 正确密钥 200；错误密钥 401；JWT+`devices:edit` 200；匿名 403 | 与 R10 改动预期一致，`/data` 双身份通道已打通 |
| 登出吊销 | 登录→`/api/users` 200→logout 200→再访问 **401**；Redis 存在 `jwt:blacklist:{jti}` | JwtBlacklist Redis 化真实有效 |
| 弃用端点 | `GET /api/ai/drift` → 501 + `deprecated:true`；匿名 403；`PUT /api/ai/drift/x` → 405 | 与方法级权限、动词边界一致 |
| 复合规则触发值 | R001 落库 `rainfall_24h=999.0`（注入复现） | 取值语义修复生效，不再错位 |
| 指标新鲜度窗口 | `findLatestByDeviceCodesSince` + `metric-stale-minutes:60` | 生效（重置后旧读数不再参与） |
| AI 融合 | 注入触发的预警 `source=AI_AUTO` | 评分链路非 RULE_ONLY，xgboost 3.4.1 与模型文件版本一致 |
| 沙盘→平台链路 | 网关 3 步短跑 → 12 类指标入库 | 回归正常 |
| AI 服务 | `/api/ai/health` 200 | 运行中 |

---

## 四、遗留问题确认（历史未修，不在本轮新增）

1. 6 个 Sender 均未接真实网关（`TODO` 仍在 `EmailSender/SmsSender/WebhookSender/...`），通知白名单仍为 fail-closed；
2. AI 指标 `eval_dataset=synthetic`，不能对外宣称真实精度；
3. `JwtBlacklist` 在 Redis 故障时 fail-open（跨实例吊销不可见，仅本实例内存镜像生效）——注释已声明；
4. EMQX allow-all 属已知缺口（P1-1 是其后果，本轮补充了端到端证据）；
5. 仓库无 CI，门禁只挂在 `npm test` 前置；
6. `scikit-learn` 1.5.1→1.5.2 patch 差仍会报 `InconsistentVersionWarning`（预测值正确）。

---

## 五、提交拆分建议

当前 45 文件混在一起，建议按"能独立回滚"的原则拆 6 个提交：

1. `fix(auth): logout permitAll + DeviceAuthFilter 回落 JWT + /data 双身份鉴权 + 403 映射`
2. `feat(redis): JwtBlacklist Redis 化（含降级与清理调度）`
3. `fix(warning): 复合规则触发值取首条款 + 指标新鲜度窗口 + 规则阈值校正(R003/R004)`
4. `fix(ai): jsonb 映射 + 模型编码归一化 + 审计失败不降级 + xgboost 3.4.1`
5. `tooling(demo): 沙盘 E2E 演练脚本 + 注册/重置 SQL 顺序修正 + 文档`
6. `chore(frontend): 确定性随机替换 + vitest 冒烟 + 路由/菜单注释订正`

> 注意：P1-2（事务自调用）、P1-3（R003 阈值）、P1-5（规则表分裂）、P1-6（EMQX 默认值不同源）本轮**只出报告未改代码**，
> 需要确认后再动，避免把审查变成未审的改动。
