# DisasterGuard 代码审查 R6 · 复审与修复

> 审查时间：2026-11-06 13:55–14:25（GMT+8）
> 审查范围：HEAD `4dc0fb1` 之上的**全部未提交改动**（两轮「整体补全优化」，42 个已跟踪文件 + 4 个新增文件）
> 审查方式：按 `dg-code-review` 规程 —— 门禁四件套、容器运行时检查、真实库只读验证、独立子代理交叉复审、逐条手工核证
> 结论：**无 P0**；发现并修复 11 项 P1/P2 缺陷；修复后全量单测 93/93 通过

---

## 1. 实测证据（本轮环境与基线）

| 检查项 | 命令 / 方法 | 结果 |
|---|---|---|
| 工作树 | `git status --short` | 42 个已修改 + 4 个新增，HEAD `4dc0fb1`，无提交 |
| 容器 | `docker ps -a` | postgres / redis / minio / emqx / tdengine **5 个 Up (healthy)**；本轮首次具备运行时验证条件 |
| 真实库 | `docker exec disasterguard-postgres psql ...`（`BEGIN`/`ROLLBACK` 只读） | `notification_receipt.status` / `notification_record.status` 均 `varchar(20)`，**无 CHECK 约束**，仅主键与外键；两表现有数据 0 行 → 新增 `BLOCKED` / `MOCK_SENT` / `PROBE_ONLY` 状态可安全写入 |
| EMQX 运行时 | `docker logs --tail 25 disasterguard-emqx` | 日志明确输出「认证初始化被跳过 —— 不要假定 MQTT 已鉴权」，**allow-all 仍存在**；监听器正常，无重启循环 |
| compose 语法 | `docker compose -f docker-compose.yml config --quiet` | exit 0 |
| compose 语法（生产） | 单 `-f docker-compose.prod.yml` | **失败**：`service "postgres" has neither an image nor a build context specified` |
| compose 语法（生产，正确用法） | `-f docker-compose.yml -f docker-compose.prod.yml config --quiet`（临时注入 `:?` 必需变量） | exit 0 |
| 全量单测（修复前基线） | `mvn test`（注入 `.env` + `SERVER_PORT=8080`） | **91 跑 / 3 failures**，均为 `ProductionConfigValidatorTest` |
| 全量单测（修复后） | 同上 | **93 跑 / 0 failures / 0 errors，BUILD SUCCESS** |

> 历史口径更正：R5 记录的 15 个失败中，12 个确实源于数据库未启动；剩下 3 个**不是环境变量缺失**，而是 `ProductionConfigValidatorTest` 直接 `new` validator、没有给 `datasourcePassword` 赋值，属测试自缺陷。本轮已修。

---

## 2. 本轮发现并已修复

### P0：无

### P1（6 项，均为本轮/上轮改动引入的功能性或契约性回归）

| # | 问题 | 位置 | 证据 | 修复 |
|---|---|---|---|---|
| P1-1 | **确认队列 confirm / reject 仍从请求体取 `operator`**，可伪造审批留痕；Javadoc 却宣称「四个写端点统一取 SecurityContext」 | `ConfirmationQueueController.java:361,407`（修复后 `:362,407`） | `String operator = payload.get("operator");` 两处残留，claim/transfer 已改 | 两处统一改 `currentOperator()`；`payload.get("operator")` **0 残留**（仅注释里保留历史说明） |
| P1-2 | **通知模板「测试」按钮必然失败**：后端要求 target 必填、且只认 `SUCCESS`；前端不传 target；6 个 mock Sender 永远返回 `MOCK_SENT` / `PROBE_ONLY`，前端按 `res.status` 写的三个分支全是死代码 | `NoticeTemplateController.java:215-218,261-264`；`NoticeTemplatePage.vue:184-196` | 后端 `if (!"SUCCESS".equals(...)) return ApiResponse.fail(502, ...)` 且丢弃 `data` | 后端 target 为空时取 `TestRecipientGuard.firstRecipient()`（保证默认目标一定在白名单内），并接受 `SUCCESS` / `MOCK_SENT` / `PROBE_ONLY` 为如实结果；前端补 `status` 为空分支，不再把演示模式误报成功 |
| P1-3 | **空 target 的「连通性探测」真实调用 Sender，SCREEN 会向所有在线 WebSocket 客户端广播**，却回报「未投递任何消息」；`ScreenSender` 的 mockReason 还写「仅记录日志」 | `NotificationService.java:119-142`；`ScreenSender.java:57-59`；`NotificationChannelsPage.vue:346` | `broadcastService.broadcastScreen(screenMessage)` 在 mock 分支内真实执行 | 探测路径**直接返回 `PROBE_ONLY`，不调用任何 Sender**；`ScreenSender` 改为如实说明「站内广播、收件人不限于 target」；`NotificationRouter` 对 `SCREEN` / `SOUND_LIGHT` 回执追加广播范围说明 |
| P1-4 | **演示回执仍写 `SENT` 并 `setSentAt`**：与 `testSend` 的 `MOCK_SENT` 口径自相矛盾，`toDto.stats.sent` 把打日志当成已送达；`retry` 只查 `FAILED`，历史 `BLOCKED` 无法补发 | `NotificationRouter.java:129,151,219`；`NotificationService.java:203`；`NotificationDispatcher.java:96` | `String status = result.success() ? "SENT" : "FAILED"` | 统一：真实 `SENT` / 演示 `MOCK_SENT`（不写 `sentAt`）/ 拦截 `BLOCKED` / 失败 `FAILED`；`stats` 增 `mockSent`、`blocked`；`record` 状态区分 `BLOCKED` / `MOCK_SENT` / `PARTIAL` / `SENDING`；`retry` 纳入 `BLOCKED`，补齐名单后可补发 |
| P1-5 | **默认配置下所有手机注册验证码被 40301 拦截**：`ALIYUN_SMS_ENABLED` 默认 `true`，白名单为空 → `realDelivery=true` → 注册功能不可用 | `application.yml:91`；`VerificationCodeService.java:127`（修复前） | `boolean realDelivery = phone ? smsProperties.isEnabled() : emailEnabled` | 抽出 `isRealSmsDelivery()`：`enabled` + `Client` bean 存在 + `access-key-id` 非 `CHANGE_ME`；只有**真的会离开本机**的短信才受白名单约束，邮箱 Sender 仍是演示实现不拦；`deliverSms` 同步用该判定走日志降级，不再拿占位凭证调 SDK |
| P1-6 | **生产部署命令错误**：`DEPLOYMENT.md:253` 写单 `-f docker-compose.prod.yml up -d`，而该文件是 override 文件（postgres/redis/minio 无 image） | `DEPLOYMENT.md:253`（修复后 `:256`） | `docker compose config --quiet` 实测报 `service "postgres" has neither an image nor a build context specified` | 改为 `docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d` 并加原因注释；双 `-f` 组合实测 exit 0 |

### P2（5 项）

| # | 问题 | 位置 | 修复 |
|---|---|---|---|
| P2-1 | `REGISTER_SMS_ENABLED` / `REGISTER_EMAIL_ENABLED` 是死配置：后端 `@Value` 读 `disasterguard.register.*`，但 `application.yml` 无对应键，`.env` 与 compose 传的值从未生效 | `application.yml`（新增 `:81-85`） | 补 `disasterguard.register.sms-enabled / email-enabled` 绑定；同步修正 `.env.example` 与 `DEPLOYMENT.md §6.2` 的变量口径 |
| P2-2 | `VerificationCodeService` 构造器参数级 `@Autowired(required=false)` 对 Spring 构造器注入无效，关闭 `ALIYUN_SMS_ENABLED=false` 时 `Client` bean 不存在 → 启动期 `NoSuchBeanDefinitionException` | `VerificationCodeService.java:92` | 改 `@Nullable Client`（`org.springframework.lang.Nullable`），`@SpringBootTest` 加载完整上下文实测通过 |
| P2-3 | `ProductionConfigValidatorTest.validatorWith()` 未给 `datasourcePassword` / `redisPassword` 赋值 → 3 个「应通过」用例恒定失败 | `ProductionConfigValidatorTest.java:44-50` | 显式赋合法测试值；修复后 8/8 通过 |
| P2-4 | 前端 `STATUS_LABEL` 未覆盖 `PENDING` / `READ` / `DELIVERED`，兜底 `demo` 把「已读 / 已送达」误标成「演示/未投递」；且误把 `SendResultDto.status` 混入 record 状态域 | `NotificationChannelsPage.vue:262` | 补齐完整状态域，未知状态改标「未知」并写明原值；`statusStyle` 增加 pending / read / unknown 配色；模板文案同步 |
| P2-5 | AI 侧两处「纸面功能」：① 自适应归一化诊断返回值被丢弃、注释仍宣称「参与评分」；② `anomaly.py` 一维补齐成 5 个共线特征后仍报 `mock=False` | `comprehensive_model.py:898,909-910,976`；`anomaly.py:38,113` | ① 诊断写入 `assess()` 返回体 `adaptive_normalization{enabled:false, applied:0, ...}` 并改掉旧注释；② 新增 `input_was_1d`（由输入形态自动判定、不可客户端伪造），路由据此置 `mock=True` + 说明文案，并校验二维输入必须为 5 维 |
| P2-6 | 菜单「社交媒体舆情」指向 `redirect: /dashboard/hazards`，**无页面实现**，点击跳到无关页面 | `router/index.js:353`；`dashboardData.js:41`；`stores/auth.js` | 菜单项与 `ROLE_MENU_MAP` 路径一并撤下并留注释说明恢复条件；避免保留假入口 |

---

## 3. 验证为正确的部分（避免只报问题）

1. **白名单闸门本身严密**：`TestRecipientGuard` 空名单 / 空 target / null 一律 false，精确匹配去空白+小写、无域名通配、日志脱敏；名单外请求在调用 Sender 前返回，回执 `BLOCKED` 且 `sentAt=null`；`Dispatcher` 全拦时把 record 也标 `BLOCKED`。
2. **三条投递链路全覆盖**：`NotificationRouter#sendToChannel`、`NotificationService#testSend`、`VerificationCodeService#send`；全后端 grep 未发现第四条自动外发路径（无 `JavaMailSender` / `MqttTemplate` / `convertAndSend` 直发）。`SmsTestTool` 是脱离 Spring 的人工 `main()` 工具，非自动链路。
3. **登出吊销链路闭合**：filter 免过滤 → 自行 `Bearer` 验签 → `JwtService.revoke(jti, exp)` → 每次 `parseToken` 读 `JwtBlacklist.isRevoked`。
4. **Spring 构造无回归**：`VerificationCodeService` / `NotificationService` / `NotificationRouter` / `NoticeTemplateController` 签名变化后，`DisasterguardApplicationTests` 完整上下文加载通过（16.8s）。
5. **配置同源**：5 处 `NOTIFICATION_TEST_RECIPIENTS` 兜底值全为空且一致；`start-dev.sh` 能导出空值键；`application-docker.yml` 不覆盖 `disasterguard.notification`。
6. **ai-service 其它改动正确**：`model_info.py` 改同步 `def` 无遗留 `await`；`multimodal.py` 5 个演示端点全部带 `_demo_meta()`；`score_social_media` 去掉 `-1` 与上游 0-4 口径一致；`DamageAssessmentPage` 7 维键名与后端一致、图标均已 import。
7. **刻意返回 501 的诚实桩未被误改**；运行时 AI 端口已统一 8001；`WelcomePage` / `ModelManagePage` 的 `Math.random()` 伪造指标已移除；`check-timer-leaks.mjs` 自测 6/6。
8. **真实库约束核对通过**：新增状态字符串长度均 < 20，且无 CHECK 约束限制取值域。

---

## 4. 修复后验证结果（实测）

| 项 | 结果 |
|---|---|
| `mvn clean compile` | **BUILD SUCCESS**（329 源文件） |
| `mvn test`（全量，注入 `.env` + `SERVER_PORT=8080`） | **93 跑 / 0 failures / 0 errors / BUILD SUCCESS** |
| AI 契约测试 | `pytest tests/test_contract_fixes_20261106.py` **9 passed**（`E:\anaconda3\python.exe`） |
| Python 语法 | `anomaly.py` / `comprehensive_model.py` ast 通过 |
| 门禁 | missing-imports 0；endpoint-parity 0（后端 330 / 前端 220）；broken-imports 0；timer-leaks 0；frontend-endpoints 仅 2 个已登记缺口 |
| `npm run build` | **✓ built**（7.20s） |
| compose | dev 单文件 exit 0；prod 双 `-f` 组合 exit 0 |

新增/扩充测试：`TestRecipientGuardTest.java` **11 项**（含「占位凭证不拦截注册」「probe 不调用 Sender」「mock 回执不写 sentAt」「全拦记录状态」），`test_contract_fixes_20261106.py` **9 项**，`ProductionConfigValidatorTest` 8/8。

---

## 5. 遗留问题确认（本轮未修，需外部条件或架构决策）

| 项 | 原因 | 证据 |
|---|---|---|
| EMQX allow-all 匿名放行，`CHANGE_ME_*` 凭据未填 | 需真实凭据 + 同步 `hardware-sandbox/gateway/config.yaml` 网关配置 | `docker logs` 实测「认证初始化被跳过」；`infra/emqx/init.sh:100-104` |
| 6 个 Sender 真接 SMTP / 短信 / APP 网关 | 需密钥与服务商账号 | 回执均已标 `MOCK_SENT`，不伪装送达 |
| CAS Landslide Dataset 为 CC-BY-NC-4.0 | **用户已决策不商用**，保留数据与现有 `yolo_crack.onnx`；商用前必须换源重训且不得分发权重 | `DEPLOYMENT.md §6.1`、数据卡顶部、`models/README.md` 顶部 |
| 自适应归一化仍未接入评分路径 | 需把 `max_val` 改成每次推理的局部参数传入 `score_*` / `safe_norm`（改全局会并发串扰） | 返回体已如实标 `enabled:false, applied:0` |
| 多模态融合 5 端点仍是演示数据 | 已标 `mock/dataSource=demo`，不伪装真实；真接需数据源 | `ai-service/app/routers/multimodal.py` |
| `JwtBlacklist` 为单机内存 | 多副本部署下登出只在单实例生效，需换 Redis；prod 副本数静态无法判定 | `auth/jwt/JwtBlacklist.java:19-20` |
| `ConfirmationQueueController` 把 `closedBy` 当 `assignee` | 既有数据模型语义错用，改动涉及状态机与历史数据 | `:333,462` |
| 2 个 KNOWN_GAPS 端点（`/api/ai/drift`、`/api/ai/multimodal`） | 后端目前只有演示数据，补转发反而模糊真实度 | `check-frontend-endpoints.mjs` |
| `docs/DEVELOPER.md` 多处仍写 AI 端口 8000 | 既有文档残留，运行时已统一 8001 | `DEVELOPER.md` 约 8 处 |
| `Math.random()` 残留 | `deviceMapService.js`（速度/航向）、`FieldInspectionPage.vue`（GPS 偏移）、`CommandScreenPage.vue:628`（Vue `:key`）均非本轮引入 | 全局 grep |
| `BatchAnomalyItem` 未做一维归一化 | 批量异常接口传一维仍会 422；无前端调用 | `ai-service/app/routers/anomaly.py` |
| 前端单测仍为 0 | `package.json` 无 test script | `frontend/package.json` |
| 全量端到端（登录→预警→通知→确认）未跑 | 后端 / AI 服务 / 前端 dev server 均未启动（端口 8080/8001/5173 无监听） | 端口探测 |
| `/dashboard/social-media` 无页面实现 | 本轮已撤下菜单入口；恢复需先新建页面与路由 | `dashboardData.js:41` 注释 |

---

## 6. 提交拆分建议

当前改动全部未提交，建议拆成 6 个提交（不要一个巨型 commit）：

1. `fix(notification): 投递白名单闸门 + 回执状态口径统一` — `TestRecipientGuard`、Router/Service/Dispatcher、6 Sender、ScreenSender、统计与重试
2. `fix(auth): 确认队列操作人取 SecurityContext + 验证码真实网关判定 + @Nullable` — ConfirmationQueueController、VerificationCodeService、`application.yml` register 绑定
3. `fix(ai): 一维异常输入如实标 mock + 自适应归一化诊断对外暴露` — anomaly.py、comprehensive_model.py
4. `fix(frontend): 通知状态映射 / 模板测试提示 / 撤下无页面菜单` — 3 个 vue + dashboardData.js + stores/auth.js
5. `test: R6 契约断言 + 修复 ProductionConfigValidatorTest 自缺陷` — 3 个测试文件
6. `docs: 生产 compose 双 -f 启动命令 + 不商用与投递边界口径` — DEPLOYMENT.md、数据卡、models README、.env.example、compose 环境变量

提交前注意：`.gitattributes` 规定 `eol=lf`，部分 Java/Python 文件 HEAD blob 仍是 CRLF，任何编辑都会显示整文件 diff；看真实改动量用 `git diff --ignore-cr-at-eol --stat`（当前 **42 files / +747 / -228**），`git add` 时 git 会自动归一化，不要误判成内容被改坏。

---

## 7. 可信度分层

- **实测**：编译 / 全量单测 / AI pytest / 5 个门禁 / 前端构建 / `docker ps` / `docker logs` / `docker compose config` / 真实库 schema 与约束查询 / 端口探测，以及报告内所有 `文件:行号`。
- **推断**：运行时后果（模板测试按钮行为、通知统计口径、验证码 40301）由契约与调用链得出，未在启动的服务上真机触发。
- **未做**：后端 / AI / 前端服务未启动，完整端到端链路、prod 实际发布、登录→预警→通知→确认全流程、多副本下的登出行为均未验证。
