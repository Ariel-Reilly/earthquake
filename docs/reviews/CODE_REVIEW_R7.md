# DisasterGuard 代码审查 R7 · 复审与修复

> 审查时间：2026-11-06 14:45–15:20（GMT+8）
> 审查范围：HEAD `0fbc2fc` 之上**本轮新产生的 14 个文件改动**；同时复核 `b802ba5` / `0fbc2fc` 两个提交（R6 成果落库 + 大规模行尾归一化）
> 审查方式：按 `dg-code-review` 规程 —— 门禁四件套、容器运行时检查、真实库只读验证、compose 语法校验、独立子代理交叉复审、逐条 `sed -n` 手工复核
> 结论：**无 P0**；发现并修复 **10 项 P1/P2 缺陷**；修复后全量单测 **99/99**、AI 契约测试 **11/11**、门禁与构建全绿

---

## 0. 与 R6 交接时的重要状态变化

R6 结束时工作树有 42 个未提交改动；本轮开始时 **`git status` 全空、HEAD 已前进到 `0fbc2fc`**。核证结论：

| 核证项 | 结果 |
|---|---|
| R6 五个交付物（白名单类、两个 Java 测试、AI 契约测试、R6 报告） | `git cat-file -e HEAD:<path>` **全部 IN HEAD**，成果已落库 |
| `4dc0fb1..HEAD` 提交 | 仅 2 个：`b802ba5`（265 文件 / 21166+/20095-，行尾归一化 + AI IDE 清理）、`0fbc2fc`（.gitignore 补充） |
| 删除文件 | 仅 `.cursor/rules/refactor.mdc`、`.cursorignore`、`.cursorrules` 三个 AI IDE 配置，**无误删业务文件** |
| `.env` / 密钥 | `git ls-files` 中无 `.env` / `*.pem` / `*.key`，**未泄露** |
| 行尾归一化安全性 | 253 个改动文件中 208 个是纯行尾、文本完全一致；`.gitattributes` 的 `*.sh text eol=lf` 完好、4 个 `.sh` 均为 LF；`frontend/src/styles.css` **不在改动列表**，BOM 与 U+FFFD 0（历史翻车点未复发） |
| 新 `.gitignore` 副作用 | 新增 `.workbuddy/` 忽略规则 → 项目记忆目录不再被 git 跟踪（文件仍在磁盘，属工程债，非数据丢失） |

> 因此本轮不是「继续审 R6 的未提交改动」，而是**从干净基线重新起一轮**：审 R6 落库后的代码有没有新回归，并回答用户新提的「四项预设能力是否可用」。

---

## 1. 实测证据

| 检查项 | 命令 / 方法 | 结果 |
|---|---|---|
| 工作树（审查前） | `git status --short` | 空；HEAD `0fbc2fc` |
| 容器运行时 | `docker ps -a` | postgres / redis / minio / emqx / tdengine **5 个 Up (healthy)**；另有 6 个其它项目的 Exited 容器（无关） |
| EMQX 运行时 | `docker logs --tail 12 disasterguard-emqx` | Enterprise 5.9.0 正常运行、dashboard :18083 started，**无重启循环**；allow-all 仍未开认证 |
| compose（dev） | `docker compose -f docker-compose.yml config --quiet` | exit 0 |
| compose（prod） | `-f docker-compose.yml -f docker-compose.prod.yml config --quiet`（临时注入 `:?` 必需变量） | exit 0（R6 修正的双 `-f` 命令有效） |
| 真实库 | `psql` + `BEGIN/ROLLBACK` 只读 | `notification_receipt.status` / `notification_record.status` 均 `varchar(20)`，`pg_constraint` 中 CHECK 约束 **0 条**；`SENT` / `FAILED` 等新增终态可安全写入 |
| 应用服务端口 | `curl` 探测 8080 / 8001 / 5173 | **全部无监听**；仅依赖容器在跑，端到端链路未真机触发 |
| 全量单测（修复前基线） | `mvn clean compile && mvn test`（注入 `.env` + `SERVER_PORT=8080`） | 93/93 通过，BUILD SUCCESS |
| 全量单测（修复后） | 同上 | **99 跑 / 0 failures / 0 errors，BUILD SUCCESS** |

---

## 2. 本轮发现并已修复

### P0：无

### P1（4 项）

| # | 问题 | 位置 | 证据 | 修复 |
|---|---|---|---|---|
| P1-1 | **prod 下手机验证码「报成功但用户永远收不到」**：`ALIYUN_SMS_ENABLED` 默认 `true` 但 `access-key-id` 是 `CHANGE_ME`，`isChannelReady()` 只看开关 → 通过 → 写 Redis、返回成功 → `deliverSms()` 静默降级打日志。且降级日志**无条件打印验证码明文**（含 prod） | `VerificationCodeService.java:239`（修复后 `:244`） | `boolean enabled = phone ? smsProperties.isEnabled() : emailEnabled; return enabled \|\| !prodProfile;` | 短信就绪判定改为 `isRealSmsDelivery() \|\| !prodProfile`：prod 且网关未接入 → 明确 50301，不写 Redis、不假装成功；非 prod 保留日志降级。降级日志在 prod 不再输出验证码明文 |
| P1-2 | **空 target 探测虚假宣称「渠道配置已校验」**：R6 为规避 SCREEN 广播改成「不调用任何 Sender 直接返回」，但返回文案仍写「仅校验渠道配置」「渠道配置已校验」，实际连 Sender 是否存在都没查 | `NotificationService.java:119-123`；`NotificationChannelsPage.vue:368` | 探测分支直接 `return new SendResultDto(..., "渠道配置已校验；...")` | 探测现在**真实检查该渠道 Sender 是否已注册**（与真实发送路径同一匹配规则），未注册如实返回 `NO_SENDER`；文案改为「发送器已注册；未校验渠道配置，未校验外部网关连通性」；前端提示同步 |
| P1-3 | **派发记录状态误标**：全部渠道失败时 `successCount=0` → 标 `SENDING`，看板永远显示「发送中」不暴露故障；全部渠道真实成功也标 `PARTIAL` | `NotificationDispatcher.java:86`（修复后 `:88-102`） | `successCount > 0 ? "PARTIAL" : "SENDING"` | 明确终态：全成功 `SENT` / 全演示 `MOCK_SENT` / 全拦截 `BLOCKED` / 全失败 `FAILED` / 混合 `PARTIAL` / 无结果 `SENDING` |
| P1-4 | **预测页「历史趋势」是伪造观测数据**：`histPath` 拿预测值前 1/4 重画一遍，图例却标「历史」，且两条图例用同一颜色 | `DisasterForecastPage.vue:152-156, 392-393, 422-428` | `const history = preds.slice(0, histLen)` | 撤下伪历史曲线与图例（后端 `ForecastResult` 目前只下发 `predictions[]`，无真实 `history[]`），留注释说明恢复条件：等后端补真实历史，绝不拿预测值冒充观测 |

### P2（6 项）

| # | 问题 | 位置 | 修复 |
|---|---|---|---|
| P2-1 | 邮件分支零闸门：EmailSender 目前是纯日志 Mock，但将来接入 SMTP 时白名单不会自动生效 | `VerificationCodeService.java:135,226,354` | 新增 `isRealEmailDelivery()`（当前恒 false，注释写明接入契约），`realDelivery` 判定收敛到一处；`deliverEmail` 在 Sender 返回**非 mock** 结果而能力未声明时 fail-closed 抛错，避免「网关已接、开关没开」被静默绕过 |
| P2-2 | 前端把 `SENDING` / `RETRYING` 归到 `demo` 类，显示成「演示/未投递」 | `NotificationChannelsPage.vue:262-290` | 独立 `sending` 类别 + 文案「发送中，结果待确认」+ 配色；`statusNote` 同步 |
| P2-3 | `BatchAnomalyItem` 未做一维归一化，批量异常接口传一维仍 422（R6 只修了单条） | `ai-service/app/routers/anomaly.py:145` | 抽出 `_flag_one_dimensional_input` / `_normalize_data_shape` / `_check_data_shape` 三个模块级函数，单条与批量共用；批量同样 `mock=True` + 说明文案，二维仍强制 5 维 |
| P2-4 | 菜单撤下后 `router/index.js` 仍留 `social-media → hazards` 假入口，直接访问会跳无关页面 | `router/index.js:353`；`dashboardData.js:41` | 删除该 redirect，注释与恢复条件同步更新 |
| P2-5 | `docs/DEVELOPER.md` 8 处 AI 端口仍写 8000 | `DEVELOPER.md:119,182,507,1015,1595,2217,2238,3103`；`AiServiceClient.java:35`；`sentinel.py:1085,1163` | 逐条精确替换为 8001。**刻意保留**两处 `8000` 毫秒配置（`AI_HAZARD_MONITOR_INITIAL_DELAY_MS`）与 `:7` 的历史说明「端口由 8000 改为 8001」—— 全局替换会毁掉配置 |
| P2-6 | 文档口径未跟上代码 | `DEPLOYMENT.md:309-312` | 探测语义改为「只确认 Sender 已注册」；补 prod 无网关 50301、邮件 fail-closed、派发记录六种终态三行说明 |

### 未能核证项（如实标注）

- 独立复审提示 `.env.example` 某行可能含非占位口令。核证该片段的命令触发敏感内容审批且超时，**本轮未重试、未推断、未绕过**，该文件本轮也未改动。请用户自行检查并确认是否需要改为占位值。

---

## 3. 验证为正确的部分（避免只报问题）

1. **白名单闸门仍严密**：`TestRecipientGuard` 空名单 / 空 target / null 一律 false，精确匹配、无域名通配、日志脱敏；名单外在调用 Sender 前返回，回执 `BLOCKED` 且 `sentAt=null`。三条投递链路覆盖不变。
2. **模板测试按钮可用且诚实**：target 为空时取 `firstRecipient()`，白名单为空时明确返回 400；`SUCCESS` / `MOCK_SENT` / `PROBE_ONLY` 都作为如实结果返回，不再一律 502。
3. **真实库约束安全**：`status varchar(20)`、CHECK 约束 0 条，新增终态字符串均可写入；`createReceipt` 只在 `SENT` 时写 `sentAt`。
4. **`retry` 纳入 `BLOCKED` 有效**：补齐名单后可补发，名单外会在 `sendToChannel` 再次被拦。
5. **AI 侧其它改动正确**：`anomaly.py` 一维 `input_was_1d` 由输入形态自动判定、不可客户端伪造；`comprehensive_model.py` 的 `adaptive_normalization` 在所有返回路径都是 `enabled:false, applied:0`，前端未误称「已启用」；`multimodal.py` 5 个演示端点全带 mock 标记；`model_info.py` 改同步 `def` 无遗留 `await`。
6. **`b802ba5` 大清理未破坏内容**：见 §0，特别是 `frontend/src/styles.css` 未被触碰、`.sh` 保持 LF、`.env` 未提交。
7. **刻意返回 501 的诚实桩未被误改**；运行时 AI 端口已统一 8001；`WelcomePage` / `ModelManagePage` 的 `Math.random()` 伪造指标已移除；`check-timer-leaks.mjs` 自测 6/6。

---

## 4. 附：用户新问「四项预设能力是否可用」的核证结论

本轮一并做了只读链路核证，**不是本轮修复范围**（需架构决策 / 真实数据 / 算法实现），结论如下：

| 能力 | 判定 | 真实部分 | 主要差距 |
|---|---|---|---|
| ① AI 分析巡检 | **部分真实** | 巡检任务走真实库 + GPS 围栏；`yolo_crack.onnx` 是真实训练模型（clean v2，test mAP50=0.327） | 巡检提交**不调 YOLO**（只存表单 JSON + `photoIds` 字符串）；AI 识别挂在「设备图片上传」链路，结果写 `warning_event` 不回写巡检记录，且 `confidence>0.8` 门控与 0.327 精度常冲突 |
| ② 监测警报 | **主链真实** | MQTT/HTTP → `device_metric` → 每 30s 规则引擎 → AND 表达式求值 → `warning_event`/`risk_score_log` 落库 → 前端查真实库 | 通知只在人工 `dispatch` 时触发；空白名单 → 全部 `BLOCKED`，配了名单也只到 `MOCK_SENT` |
| ③ 智能分析最优方案 | **演示占位** | 资源点、避障多边形来自真实库；Redis 缓存真 | `PathPlanningService` 是线性插值 + 正弦抖动，`obstacles` 只参与 `size()` 计数；「推荐」硬编码 `fastest`；资源调度只有 `filter` 无排序/最优化；预案写死 5 条。**全仓无 A\*/Dijkstra/规划求解器** |
| ④ 预测 | **部分真实** | `lstm_displacement.pt` 真实 torch 前向，`degraded`/`mock` 标记链路完整，历史取自 `device_metric` | 权重由 `synthesize_sample()` 合成数据训练；`AI_SCHEDULER_ENABLED` 默认 `false` 不自动跑；本轮已撤掉伪造的「历史趋势」曲线 |

**六处断点**：巡检↔AI 识别不相交 → 规则预警的「AI」只是合成数据训练的 `xgboost_fusion` 做等级升降 → 预警创建后状态固定「待确认」，无自动生成方案 → 方案与通知无因果关系 → 通知被空白名单钉在 `BLOCKED`、放行后仍是 `MOCK_SENT` → 预测独立且默认不调度。

**当前运行时**：8080 / 8001 / 5173 全部无监听；前端单独起且未设 `VITE_USE_BACKEND=true` 时会**自动降级到 mock 数据**，界面数字不是真实链路结果。启动用 `./start-dev.sh all`（`./start-dev.sh status` 检查）。即便全起来，③④ 的差距与断点仍在。

**总体结论**：现在**不能**按预设跑通 ①→②→③→④ 的完整闭环。

---

## 5. 修复后验证结果（实测）

| 项 | 结果 |
|---|---|
| `mvn clean compile` | **BUILD SUCCESS**（329 源文件） |
| `mvn test`（全量，注入 `.env` + `SERVER_PORT=8080`） | **99 跑 / 0 failures / 0 errors / BUILD SUCCESS** |
| AI 契约测试 | `pytest tests/test_contract_fixes_20261106.py` **11 passed** |
| Python 语法 | `anomaly.py` / `sentinel.py` ast 通过 |
| 门禁 | missing-imports 0；endpoint-parity 0（后端 330 / 前端 220）；broken-imports 0；timer-leaks 0；frontend-endpoints 仅 2 个已登记缺口 |
| `npm run build` | **✓ built in 8.10s** |
| compose | dev 单文件 / prod 双 `-f` 组合 `config --quiet` 均 exit 0 |
| 真实库 | `status varchar(20)`、CHECK 约束 0 条，新增终态可写 |

新增断言 6 项（Java）+ 2 项（Python），锁住：探测不虚报网关校验、未知渠道报 `NO_SENDER`、prod 无网关明确失败且不写 Redis、邮件真实投递未声明时 fail-closed、全失败标 `FAILED`、全成功标 `SENT`、批量一维归一化并标 mock。

---

## 6. 遗留问题确认（本轮未修，需外部条件或架构决策）

| 项 | 原因 | 证据 |
|---|---|---|
| EMQX allow-all 匿名放行，`CHANGE_ME_*` 凭据未填 | 需真实凭据 + 同步沙盘网关配置 | `docker logs` 实测；`infra/emqx/init.sh:100-104` |
| 6 个 Sender 真接 SMTP / 短信 / APP 网关 | 需密钥与服务商账号 | 回执均标 `MOCK_SENT`，不伪装送达 |
| CAS 数据 CC-BY-NC-4.0 | **用户已决策不商用**，保留数据与现有权重；商用前须换源重训且不得分发权重 | `DEPLOYMENT.md §6.1` |
| 四项能力未自动串联（§4 六处断点） | 需产品/架构决策：巡检接 YOLO、预警自动生成方案、路径规划换真算法、预测接真实历史与调度 | `InspectionService.java:88-92`、`PathPlanningService.java:20-33`、`WarningCreateService.java:159` |
| YOLO `confidence>0.8` 门控与 test mAP50=0.327 冲突 | 阈值属业务决策，不擅自改 | `ImageAnalysisListener.java:94-96` |
| 自适应归一化未接评分路径 | 需把 `max_val` 改成每次推理的局部参数（改全局会并发串扰） | 返回体已如实标 `enabled:false, applied:0` |
| 多模态融合 5 端点仍是演示数据 | 已标 `mock/dataSource=demo`，真接需数据源 | `ai-service/app/routers/multimodal.py` |
| `JwtBlacklist` 单机内存 | 多副本下登出只在单实例生效，需换 Redis | `auth/jwt/JwtBlacklist.java:19-20` |
| `ConfirmationQueueController` 把 `closedBy` 当 `assignee` | 既有数据模型语义错用，改动涉及状态机与历史数据 | `:333,462` |
| 2 个 KNOWN_GAPS 端点（`/api/ai/drift`、`/api/ai/multimodal`） | 后端目前只有演示数据，补转发会模糊真实度 | `check-frontend-endpoints.mjs` |
| `Math.random()` 残留 | `deviceMapService.js`、`FieldInspectionPage.vue`、`CommandScreenPage.vue` 均非本轮引入 | 全局 grep |
| 前端单测仍为 0 | `package.json` 无 test script | `frontend/package.json` |
| 全量端到端（登录→预警→通知→确认）未跑 | 后端 / AI / 前端服务均未启动 | 端口探测 |
| `.workbuddy/` 被新增 gitignore 规则忽略 | 项目记忆目录不再被版本控制，文件仍在磁盘 | `.gitignore` |
| `.env.example` 疑似非占位口令 | 本轮敏感内容审批超时，未核证（见 §2 末） | — |

---

## 7. 提交拆分建议

本轮 14 个文件 / +350 / -111（忽略行尾），建议拆成 4 个提交：

1. `fix(notification): 探测如实报告 + 派发记录六种终态` — `NotificationService.java`、`NotificationDispatcher.java`、`NotificationChannelsPage.vue`、`DEPLOYMENT.md`
2. `fix(auth): prod 无短信网关明确失败 + 邮件投递能力声明` — `VerificationCodeService.java`、`TestRecipientGuardTest.java`
3. `fix(ai): 批量异常一维归一化 + 撤下伪造历史曲线 + 端口口径统一` — `anomaly.py`、`sentinel.py`、`DisasterForecastPage.vue`、`AiServiceClient.java`、`DEVELOPER.md`、`test_contract_fixes_20261106.py`
4. `fix(frontend): 移除无页面社媒假入口` — `router/index.js`、`dashboardData.js`

> 提交前注意：`.gitattributes` 规定 `eol=lf`，部分文件工作副本仍是 CRLF，编辑后 `git diff` 会显示整文件差异；看真实改动量用 `git diff --ignore-cr-at-eol --shortstat`，`git add` 时 git 会自动归一化，不要误判成内容被改坏。

---

## 8. 可信度分层

- **实测**：编译 / 全量单测 / AI pytest / 5 个门禁 / 前端构建 / `docker ps` / `docker logs` / `docker compose config` / 真实库 schema 与约束查询 / 端口探测 / `git cat-file` / 行尾与 BOM 核证，以及报告内所有 `文件:行号`。
- **推断**：运行时后果（prod 50301、探测返回 `NO_SENDER`、页面显示「发送中」）由契约与调用链得出，未在启动的服务上真机触发。
- **未做**：后端 / AI / 前端服务未启动，完整端到端链路、prod 实际发布、多副本登出行为均未验证；`.env.example` 某片段因审批超时未核证。
