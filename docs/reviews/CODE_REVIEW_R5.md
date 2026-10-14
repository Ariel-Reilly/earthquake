# 代码审查报告（第五轮 · 聚焦未提交改动）

- **日期**：2026-09-22
- **审查范围**：工作区未提交改动（19 个修改文件 + 2 个新增），基线 `28abbb0`。
  这批改动是针对 R3 遗留问题的一批修复（AI 服务并发阻塞、路由未注册、prod 建表、
  规则引擎重入、TDengine healthcheck、start-dev 环境、CI 新门禁），本报告按
  "改动本身 + 改动引入的新问题" 双线审查。
- **门禁状态**：
  - `mvn clean compile` → **BUILD SUCCESS**（292 文件，42.8s）
  - check-missing-imports → 0；check-endpoint-parity → 0（306 后端 / 197 前端）
  - check-broken-imports → 0；前端 timer-leaks（新脚本）→ 0
  - 容器：postgres/redis/minio/emqx/tdengine 全部 healthy（R4 的 emqx 重启循环已消除）

---

## P0（阻断级，1 项）

### P0-1 `application-prod.yml` sql.init 改 `always`，会在**每次生产重启时清空全部数据**

```yaml
# application-prod.yml:22
mode: always
```

改动注释声称"两份脚本都已幂等，重复启动不会报错也不会产生重复数据"。前半句成立，
但**"不报错"不等于"无副作用"**，且该论断恰好漏掉了本仓库 schema.sql 最核心的坑（R4 已实测记录）：

- `schema.sql:97` `DROP VIEW IF EXISTS hazard_point;` → 先建临时物理表 `hazard_point`；
- `schema.sql:1352` `DROP TABLE IF EXISTS hazard_point CASCADE;` → **连同其引用的全部基础表级联删除**，再重建为视图；
- 临时表是 `CREATE TABLE IF NOT EXISTS` —— **升级场景（表已存在）下，:1352 删掉的是生产数据表本身**。

即 `sql.init.mode: always` 下，**prod 每次重启容器 = 全库清空重建**。R4 对"可重复执行"的验证
只在 dev 库做过，且当时结论就明确标注了 :97 与 :1352 的配对依赖——这两个 DROP 是让
"老库升级不报错"的机制，代价恰恰是删数据。`mode: never` 是对的（它防的正是这个），
方向应当反转：**改回 `never`，另寻建表路径**。

顺带核实了注释的另一处事实错误：`backend/Dockerfile` 打的是 Spring Boot fat jar
（`COPY backend/src ./src`，resources 在 jar 内，schema.sql **可用**），"Dockerfile 不含
schema.sql 导致 always 不生效"的推断不成立——如果它真的不生效，这个改动同样没有意义。

**修复建议**：`mode: always` 回退为 `never`。生产建表走"发布前手工/CI 执行 schema.sql（限首次部署）
+ 引入 Flyway"（TODO 已写，建议排期）。若确需应用启动期兜底，至少用
`spring.sql.init.continue-on-error` + **剥离两个 DROP** 的专用 init 脚本，且必须先在"有数据的库"上验证。

---

## P1（严重，3 项）

### P1-1 `check-timer-leaks.mjs` 用 `glob`（node:fs/promises）遍历，Node 22 LTS 不可用，CI 必红

```js
// frontend/scripts/audit/check-timer-leaks.mjs:16
import { glob } from 'node:fs/promises';
```

`fs.promises.glob` 是 **Node 22.14+（实验）→ Node 24 转正** 的 API。CI 的 node-version 若为
22.x 早期版本（LTS 22.13 及以下）直接 `SyntaxError: does not provide an export named 'glob'`。
本机 managed node 24.19 能跑通不代表 CI 能跑通。同文件 `walk()` 包装函数也因此多余——
直接 `for await (const f of glob(SRC_GLOB))` 或改用既有的递归 readdir 方案（参照
`scripts/fix/check-broken-imports.mjs`）即可，且能去掉相对路径对 CWD 的隐式依赖
（脚本用 `src/**` 相对 glob，依赖 CI `cd frontend`，可接受但建议显式锚定）。

### P1-2 `start-dev.ps1` 硬编码兜底口令与 compose 默认值不一致，且默认 profile 拿到容器网主机名

```powershell
# Invoke-Backend
$env:POSTGRES_PASSWORD = if ([string]::IsNullOrEmpty($env:POSTGRES_PASSWORD)) { "disasterguard123" } ...
```

1. **口令不一致**：`docker-compose.yml:9` 的默认值是 `CHANGE_ME__dev_postgres_password_2026`。
   用户不设 `POSTGRES_PASSWORD` 时，postgres 容器用 CHANGE_ME 初始化，后端拿
   `disasterguard123` 去连 → 认证失败。R4 在 prod compose 专门修过"两套默认口令"这类问题，
   这里又引入了一组新默认值。兜底值必须与 compose 默认同源（或都走 .env 缺失即报错）。
2. **默认 profile 覆盖面不足**：`-SpringProfile` 允许 `default`/`dev`/`prod`，脚本却无条件设置
   `POSTGRES_HOST=127.0.0.1` 等**容器网端口映射**值。`docker`/`prod` profile 下这些环境变量会
   **压过 profile yml 里的容器网主机名**（环境变量优先级高于配置文件），等于"选了 docker
   profile 却被脚本拽回宿主机地址"。至少应把这段注入限制在 `default`/`dev`。

### P1-3 ai-service 降级注册让"端点 404"从启动失败降级为一行日志，超出必要容忍度

`main.py` 对 drift/multimodal 用"import 失败 → 跳过注册 + warning"的降级策略。意图可以理解
（防 scipy 缺失拖垮整个服务），但问题在于：

1. 依赖在 `requirements.txt:15` 已钉死（`scipy==1.14.1`），**正常部署路径上不存在"没装 scipy"
   的合法场景**；能触发降级的是坏环境（装错包、磁盘半满等），此时静默跳过是把配置错误
   变成难查的 404。建议至少升级为启动时 `logger.error` + 在 `/api/ai/health`（或 model_info）
   暴露"已注册模块列表"，让后端侧可探测降级。
2. `except Exception` 过宽，语法错误、`router` 变量拼错等**代码 bug** 也会走降级路径被掩盖。
   建议收窄到 `ImportError`。

（另注：该降级策略使"后端有 / 前端没调"式的 parity 审计对这两个模块天然失明，
属于已知审计盲区的扩大，接受与否需有意识决策。）

---

## P2（中等 / 轻微，4 项）

### P2-1 `hazardTypes.js` 的 `{return x}` 单行大括号为机器改写痕迹，非人工风格

6 处 `if (...) {return x}`（大括号与语句同行、无空格）不像项目任何既有人工风格，疑似
某次 lint --fix 或 AI 批量改动残留。行为无变化，但建议统一格式化（prettier），避免
diff 噪声进入 blame 历史。

### P2-2 TDengine healthcheck 对默认口令硬编码

`curl -sf http://localhost:6041/rest/login/root/taosdata`——若用户经环境变量改了 root 口令，
healthcheck 永远 unhealthy（R4 同类教训：**healthcheck 也是配置面**）。建议口令段引用
`${TAOS_PASSWORD:-taosdata}`（compose interpolation 支持），或改用容器内 `taos -s "show servers"`。
另：retries 20→10 使启动容忍从 ~200s 降到 ~100s，首次拉起+初始化若慢会误判，建议保留 20。

### P2-3 `detection.py` 超时后工作线程泄漏（有界、可接受，建议注释言明）

`asyncio.wait_for` 超时不会取消已提交给 `_DETECT_EXECUTOR` 的任务，30s 超时的推理线程会
继续占满 `max_workers=2` 的池直到自然结束——期间新请求全部排队。属已知取舍（R4 记录过
`wait_for` 语义），非阻塞缺陷，建议在 `DETECT_TIMEOUT_SECONDS` 注释处写明"超时不取消已提交
推理，极端情况下线程池满直至推理自然完成"。

### P2-4 `check-frontend-endpoints.mjs` 括号匹配器为手写解析器，缺测试

括号匹配修 300 字符窗口误报的思路正确（且已核对误报 14 条归零方向正确），但手写扫描器
对"对象字面量里再嵌模板串"、"箭头函数体中的 `)`"等边界的健壮性未验证。建议补 3~5 个
内联 fixture 断言（脚本内自测或 jest），否则下次语法变体又会静默误判——这正是 R3/R4
反复出现的"一次性脚本结论未复核"风险的脚本版。

---

## 验证为正确、值得肯定的部分

| 改动 | 核实结果 |
|---|---|
| ai-service 8 处 `async def` → `def`（R3 P1-15） | ✅ 均为纯同步推理体，无内部 await；detection 保留 async+线程池+超时是正确设计 |
| drift/multimodal 前缀改 `/api/ai/drift`、`/api/ai/multimodal` 并注册 | ✅ 与全仓 13 个路由前缀风格一致；drift `/detect` 与 detection `/detect` 不冲突 |
| `RuleEngineScheduler` AtomicBoolean 重入保护 | ✅ compareAndSet + finally 复位 + 顶层 catch 保留，正确 |
| `main.py` logging.basicConfig | ✅ 读取 AI_LOG_LEVEL，格式含时间戳，位置在 dotenv 之前（仅日志，无碍） |
| requirements.txt 补 pytest/httpx | ✅ tests/ 确实需要 |
| TDengine healthcheck 换 REST | ✅ 容器内实测：curl 存在，6041 可达，登录返回 `{"code":0,...}` |
| prod compose MINIO_ENDPOINT | ✅ `:?` fail-fast + 完整警示注释，覆盖 R4 P0-4 |
| ci.yml 新增 timer-leaks 步骤 | ✅ 路径正确（脚本位于 frontend/scripts/audit/），但见 P1-1 |
| timer-leaks 脚本本身的判定逻辑 | ✅ 本机全绿；注释行过滤、.value 归一化、行序比较均合理（除 glob 兼容性） |

## 遗留问题确认（非本轮改动引入，仍开放）

- R3-P0-6：`docker-compose.prod.yml` 仍缺 emqx / ai-service 服务定义（需定架构）。
- R4 P1-3：`ConfirmationQueueController` 写端点仍用只读码 `confirmation:view`。
- AI 服务 uvicorn 仍无 workers/limit-concurrency 配置（本轮 def 化只解决事件循环阻塞，
  并发容量问题仍在）。
- 测试覆盖：Java 测试类 6 个、前端 0，P2 未动。

## 结论

门禁全绿、编译通过、运行时容器健康；但 **P0-1（prod 每次重启清库）必须回退后才能提交
这批改动**。建议提交拆分：ai-service 并发修复 + 路由注册 + requirements + RuleEngine +
compose healthcheck + prod MINIO_ENDPOINT 一组（安全）；application-prod.yml 单独重做。

---

## 附：修复实施记录（2026-09-22 同日，报告定稿后执行）

| 项 | 处置 | 验证 |
|---|---|---|
| P0-1 | `application-prod.yml` 回退 `mode: never`，注释写明 CASCADE 清库机理与建表替代路径 | yml 随编译资源打包，复跑 clean compile 不必要；注释引用 R4 实测 |
| P1-1 | `check-timer-leaks.mjs` 弃用 `fs/promises.glob`，改为 `readdirSync` 递归 + 脚本位置锚定（不再依赖 CWD） | **managed node 22.22 实跑通过**（原脚本在该版本会 SyntaxError） |
| P1-2 | `start-dev.ps1`：兜底口令对齐 compose 默认值 `CHANGE_ME__dev_postgres_password_2026`；127.0.0.1 类注入限定在 default/dev profile | PowerShell Parser::ParseFile 0 错误 |
| P1-3 | `main.py` 降级仅捕获 `ImportError`，日志升为 ERROR；`/api/ai/health` 新增 `unregistered_routers` 字段供后端探测降级 | `ast.parse` 语法通过；模块级状态字典在 health 定义前初始化 |
| P2-1 | `hazardTypes.js` 单行 `if (...) {return x}` 统一为多行格式，移除机器改写痕迹 | node 语法校验通过 |
| P2-2 | TDengine healthcheck 口令段改为 `${TDENGINE_PASSWORD:-taosdata}` 插值（与 backend 同源变量）；retries 恢复 20 | `docker compose config` 通过 |
| P2-3 | `detection.py` 在 `asyncio.wait_for` 上方加完整注释，阐明"超时不取消已提交推理、线程池满直至推理自然完成"的语义与运维缓解 | 注释引用 max_workers=2 队列堆积机制 |
| P2-4 | `check-frontend-endpoints.mjs` 新增 `--self-test` 模式 + `scripts/audit/_bracket_matcher_fixtures.mjs`（7 条 fixture 覆盖嵌套括号/模板串/注释/箭头函数/多行对象字面量） | **7/7 fixture 通过**；交叉验证算法变体一致 |

复验：三项审计全绿（0/0/0）、timer-leaks（node22）0、compose config OK、ps1 解析 OK、
main.py 语法 OK、bracket matcher self-test 7/7、AI 服务 pytest 91/4（4 个跳过为预期）。
**R5 报告涉及的全部 8 项 P0/P1/P2 现已全部处理完毕，可安全提交。**
