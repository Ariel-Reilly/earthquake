# DisasterGuard 代码审查 R8

- 审查时间：2026-11-06 17:02–17:40（GMT+8）
- 审查范围：HEAD `0fbc2fc` 之后的**全部未提交改动**——26 个已跟踪文件 + 5 个新增文件/目录
  （覆盖 R7 遗留、遗留清单 4 项修复、收尾 4 项，共 1161+/225- 行）
- 方法：门禁四件套 + 新增门禁 → 容器/日志/真实库运行时检查 → 启动后端做 HTTP 实测 → 逐行复核 diff → 最小修复 → 全量回归
- 结论：**无 P0 阻断项**。发现并修复 1 项本轮引入的 P1 回归 + 3 项口径/验证缺口；
  新发现 1 项此前被掩盖的 P1（AI 全量测试 3 例失败，存量）与 6 项 P2，均列入遗留并给出处置建议。

> 口径说明：本文所有 `PASS/FAIL` 均为本机实测命令输出；标注「推断」的是代码级判断，标注「未做」的是本轮没有执行的验证。
> 自动化后台任务通知均为系统事件，未当作用户确认。

---

## 一、验证为正确的部分（实测）

| 项目 | 证据 |
|---|---|
| 后端编译 | `mvn clean compile` **BUILD SUCCESS**，330 源文件 |
| 后端全量单测 | `mvn test` **104/104**（R8 补 5 个 `JwtBlacklistTest` 后），含 Spring 上下文加载 |
| 前端 | `npm test` **8/8**；`npm run build` ✓ 5.65s |
| AI 契约测试 | `test_contract_fixes_20261106.py` **11/11** |
| 静态门禁 | 6 个脚本 + `--self-test` 13/13 **全绿**（详见 §五） |
| 容器 | postgres / redis / minio / emqx / tdengine 均 `Up 3 hours (healthy)`；日志无异常、无重启循环 |
| 真实库（只读，BEGIN/ROLLBACK 包裹） | `notification_receipt.status` / `notification_record.status` 均 `varchar(20)`；`%status%` CHECK 约束 8 条但均不在通知表；`unified_event=5` / `warning_event=10` / `warning_rule=6` |
| PowerShell 脚本 | `Parser::ParseFile` **0 语法错误**，`NonAsciiBytes=0`（PS 5.1 兼容） |
| 默认值同源 | `REDIS_PASSWORD` 的 dev 默认值在 `docker-compose.yml:39/46/170`、`application.yml:42`、`verify-jwt-blacklist.ps1:47` **四处完全一致** |
| compose dev | `docker compose -f docker-compose.yml config --quiet` **exit 0** |

### HTTP 运行时实测（后端真实启动，8080）

| 请求 | 结果 | 判定 |
|---|---|---|
| `GET /api/ai/drift` / `/multimodal`（admin token） | **501** `{"status":"NOT_IMPLEMENTED","deprecated":true,...}` | ✓ 符合 501 桩设计 |
| `GET /api/ai/drift/detect`、`POST /api/ai/drift/detect`、`GET /api/ai/multimodal/insight/1` | 均 **501** | ✓ 通配兜底生效 |
| `GET /api/ai/health` / `/model/info`（admin token） | **200**（未被新 Controller 遮蔽）；`/ai/health` 报 `DEGRADED`，因 8001 未监听 | ✓ 映射无歧义 |
| 上述两条无 token | **403**（`anyRequest().authenticated()`） | 见 P2-4，行为与设计目标有偏差 |
| `POST /api/auth/logout`（无 token） | **200** `{"code":0,"message":"退出成功"}` 且不写 Redis | ✓ 安全空操作 |
| `verify-jwt-blacklist.ps1` 重跑 | **10/10 PASS, EXIT=0**；key `jwt:blacklist:{jti}`、value `1`、TTL `86399s` ≈ JWT 剩余 `86399s`，旧 token `200→401`，新 token 200 | ✓ 上一轮 logout 403 修复未回归 |

---

## 二、本轮发现并已修复

### P1-1（本轮引入的回归）收紧 `/dashboard` 前缀后，6 个历史重定向别名变成 403

- **位置**：`frontend/src/stores/auth.js:306` `hasPathAccess()`；`frontend/src/router/index.js:350-356`；守卫 `router/index.js:572-577`
- **证据**：R7 收尾把 `canAccessPath` 改为「精确 or `/` 边界」并禁止 `/dashboard` 根节点展开，修好了越权，但路由表里 6 个
  `{ path: "xxx", redirect: "/dashboard/yyy" }` 别名**从来不在任何角色菜单里**。独立复核脚本列出 54 个 dashboard 子路由，
  其中 `history-hazards` / `warning-config` / `backup-recovery` / `plans` **不在菜单并集**，`satellite-compare` / `reports`
  只在个别角色菜单里（其余角色同样被拦）。守卫在 `!canAccessPath` 且路径以 `/dashboard/` 开头时直接跳 403，
  于是旧书签/旧链接不再 redirect 而是误报「无权限」。
- **修复**：`stores/auth.js` 新增 `LEGACY_REDIRECT_PATHS`（6 条），`hasPathAccess` 对别名放行。
  **不扩大权限**——redirect 落地后的目标页面会再过一次同一守卫判断。
  `auth.spec.js` 新增 1 个测试（2 角色 × 6 别名 = 12 断言 + 1 条越权仍被拦），`npm test` 8/8。
- **推断**：这是静态分析 + 单测结论，**未做**浏览器真实点击旧链接的验证。

### P2-1 两处注释仍宣称 `canAccessPath` 走前缀匹配（口径过时）

- `TopToolbar.vue:128`、`router/index.js:517`。修复后已改成「按 `/` 边界匹配、`/dashboard` 根节点不展开」，
  并注明 R8 起旧理由不再适用。**纯注释，无逻辑改动**。

### P2-2 `reportId` 注释把「未实现的去重」写成既成事实

- **位置**：`FieldInspectionPage.vue:146`、`:207`。原注释称客户端流水号「只用于前端留痕与去重」，
  但该 ID **不随 `/api/inspection/submit` 请求体发送**，后端无从去重，前端也没有去重逻辑。
- **修复**：如实改为「仅用于前端留痕与签到坐标派生，不参与服务端幂等/去重；后端编号以 `recordCode` 为准」，
  并写明「同一份填报（含失败重试）沿用同一 ID，成功后清空」。未改请求契约、未改行为。

### P2-3 Redis 降级路径此前只有代码级推断，没有可复现断言

- 新增 `backend/src/test/java/cn/disasterguard/auth/jwt/JwtBlacklistTest.java`（5 个测试）：
  `deriveKey` 的 jti / sub+iat 两种格式；Redis 正常路径的 key/value=1/TTL≈剩余有效期（ArgumentCaptor）；
  Redis 未注入时纯内存降级、已过期 token 不写入；Redis 读写抛异常时降级不向外抛且**本地镜像优先**（`verify(redis, never()).hasKey(...)`）；
  `cleanup()` 幂等且不误删未过期条目。
- `mvn test` 由 99/99 变为 **104/104**；`docs/DEVELOPER.md §29.3` 表格已同步口径。
- 注意：这仍是**单元测试级**验证，**未做**停掉 Redis 容器的真实断网演练。

---

## 三、遗留问题（本轮发现，未修）

### P1-2 AI 全量 pytest 有 3 例失败，此前「11/11 全绿」口径掩盖了它

- **实测**：`E:/anaconda3/python.exe -m pytest ai-service/tests/ -q`
  → **3 failed, 99 passed, 4 skipped in 12.91s**（`EXIT=1`）。
  单独跑 R7 用的契约文件 `test_contract_fixes_20261106.py` 才是 `11 passed`。
- **失败明细**（`ai-service/tests/test_xgboost_model.py`）：
  1. `test_high_factors_yields_critical`：`assert 'xgboost-fusion-v3' == 'xgboost-fusion'`
  2. `test_low_factors_yields_low`：同上
  3. `test_meta_json_exists`：`assert 'feature_importance' in meta` 失败，meta 只有
     `best_iteration / best_score / device_weights / feature_names ...`
- **归因**：测试文件与模型实现**均未被本轮改动**（`git diff` 为空），是 HEAD 就有的**存量漂移**；
  与 R7 报告「AI 契约 pytest 11/11」不矛盾——那是只跑一个文件的口径，但不足以代表全量。
- **建议**：二选一并写进发布门禁——① 把测试期望对齐真实实现（`model` 用 `xgboost-fusion-v3`，
  去掉或改写 `feature_importance` 断言）；② 若模型确实应输出 `feature_importance`，则补实现而非删断言。
  同时把「全量 pytest」而非「契约文件」列为发布前置条件。**本轮未修**：属 AI 服务存量实现/测试口径决策，超出本次审查的改动范围。

### P2-4 501 桩对未登录调用方返回 403 而不是 501

- 实测 `GET /api/ai/drift`（无 token）= 403。原因是 `@PreAuthorize` + `anyRequest().authenticated()`。
  与类注释「让调用方一眼看出未实现且已弃用」的目标有偏差（未登录时看不出 501）。
- 另外 `AiDeprecatedGapController.java:66` 的 `@RequestMapping({"/drift/**","/multimodal/**"})`
  **未限定 HTTP method**，会接管这两组路径下的所有动词（实测 POST 也返回 501）。
  设计上可接受，但会遮蔽未来在此路径下的真实实现。
- 建议：文档写明「需带有效 token 才能看到 501」；若要让匿名也能看到 501，需把这两组路径加入 `permitAll`
  ——这是安全边界变更，本轮**不做**。

### P2-5 新 `Math.random()` 门禁是手动脚本，未挂任何自动化入口

- `check-math-random.mjs` 未出现在 `package.json` scripts、CI 配置或 `precheck.mjs` 中；
  不显式执行就不会拦截新增违规。已在 `docs/DEVELOPER.md §28.7` 加 ⚠ 警示。
- 建议：挂到 `npm test`（或统一 `scripts/audit/run-all`）后再从文档移除警示。

### P2-6 端点门禁把「有映射」当成「已实现」，501 桩会被判为缺口已解决

- `check-frontend-endpoints.mjs` 的 `KNOWN_GAPS` 已清空（✓ 正确），但它只判断后端是否存在映射；
  现在 `AiDeprecatedGapController` 提供了 501 映射，两条路径就恒为「通过」，不会再被提示为未实现。
  `check-endpoint-parity.mjs:151-154` 的 `IGNORE` 仍忽略 `GET /api/ai/drift{}` / `GET /api/ai/multimodal{}`
  （解析器约定，保留合理），parity 因此也不覆盖这两条。
- 建议：门禁增加「响应体含 `deprecated:true` / 控制器标 `@Deprecated`」的提示级输出，避免 501 桩被当成功能已补齐。

### P2-7 E2E 脚本「命中 key」断言不精确

- 重跑输出 `matchedKeys=2`（上轮 E2E 留下的 key 尚未到 TTL），但断言文案是
  `[PASS] jwt:blacklist:* contains revoked key -- expectedKey=...`。
  即该步只验证了「存在任意 `jwt:blacklist:*`」，未精确比对本次 `jti`。
- 缓解：后续 `GET expectedKey` + TTL 两步仍会兜底（本次确实拿到了正确 key），所以**不会误判通过**。
  建议把该步改成 `-contains $expectedKey` 的精确断言，并在脚本结束时删除本次写入的 key（避免跨轮残留）。

### P2-8 生产 compose 校验因本机 `.env` 缺变量失败（环境漂移，非代码缺陷）

- 实测 `docker compose -f docker-compose.yml -f docker-compose.prod.yml config --quiet` → `EXIT=1`：
  `required variable DATA_ENCRYPTION_KEY is missing a value`。本机 `.env` 无该键，`.env.example` 有。
- 这是 `docker-compose.prod.yml:144` 的 `:?` 强制变量**按设计工作**，`DEPLOYMENT.md:226/229` 也写明「compose 拒绝解析」；
  但 `DEPLOYMENT.md:63/90` 又给这两个变量列了「默认值」，与 `:?` 语义口径矛盾。
- 建议：把 `:63/90` 改成「prod 必填、无默认值」；部署前按 `.env.example` 补齐 `DATA_ENCRYPTION_KEY`。
  本轮**未改**（配置/文档口径调整需与部署约定一并确认）。
- 说明：R7 报告曾记「dev/prod 双 `-f` exit 0」，本轮在**未注入额外环境变量**的原生 shell 下得到 exit 1，
  两轮环境不同，不构成代码回归。

### P2-9 历史遗留（本轮确认仍在，未修）

沿用 R7 §5 与项目记忆，逐条确认未变化：EMQX 5.9 MQTT allow-all；6 个 Sender 未接真实网关；
CAS CC-BY-NC-4.0 数据禁商用（按用户决策保留）；AI 四项能力未自动串联、通知白名单 fail-closed 钉在 BLOCKED；
多模态 5 端点仍是演示数据；自适应归一化未接入评分路径（`applied=0`）；前端组件/页面仍 0 单测（本轮只有 `auth.spec.js` 8 例）；
`confidence>0.8` 阈值；端到端联调未跑。
另：`docker ps -a` 里有 7 个 `geosentry-*` / `thingspanel-*` 的 Exited 旧容器（2 个月前，与本仓库无关），
仅磁盘/列表噪音，未清理。

---

## 四、未能核证项 / 环境限制

1. **未起 ai-service**：8001 无监听，`GET /api/ai/health` 实测返回 `DEGRADED`。
   因此 drift / multimodal 的**真实直连链路**、多模态 5 端点、模型推理均未做运行时验证。
2. **未做真实断网降级**：没有停 `disasterguard-redis` 容器来演练 WARN 降级；已用新增单测覆盖等价分支。
3. **未做浏览器级路由守卫验证**：P1-1 的修复依据是静态路由覆盖分析 + 单测，没有真机点旧链接。
4. **prod compose 未完整校验**：见 P2-8，缺 `DATA_ENCRYPTION_KEY` 属环境缺值，未临时伪造变量绕过。
5. 未执行 `git commit`；工作区改动仍全部未提交。

---

## 五、最终验证矩阵（R8 修复后重跑）

| 命令 | 结果 |
|---|---|
| `cd backend && bash ../scripts/audit/mvn.sh clean compile` | **BUILD SUCCESS**（330 源文件） |
| `mvn test`（注入 `.env`，`SERVER_PORT=8080`） | **104 / 104**，BUILD SUCCESS |
| `cd frontend && npm test` | **8 / 8** |
| `npm run build` | ✓ built in 5.65s |
| AI 契约 `pytest tests/test_contract_fixes_20261106.py -q` | **11 passed** |
| AI 全量 `pytest tests/ -q` | **99 passed / 3 failed / 4 skipped**（见 P1-2） |
| `check-missing-imports` / `check-endpoint-parity` / `check-frontend-endpoints` / `check-math-random` | 4 × EXIT=0 |
| `check-timer-leaks`（在 `frontend/`） / `check-broken-imports` | 2 × EXIT=0 |
| `check-math-random --self-test` | 13 cases / 0 failed |
| `orphan_endpoint_scan.py` | EXIT=0（后端 289 / 前端 228 / 报告 40 个未引用端点，信息输出） |
| `verify-jwt-blacklist.ps1` | **10/10 PASS，EXIT=0** |
| `Parser::ParseFile` | 0 syntax errors / 0 non-ASCII bytes |
| 临时文件残留（探针 / `__pycache__` / `dist.prev` / `_r8_*`） | **0 处** |

> 环境坑（复现请照做）：本机有 `HTTP_PROXY`，curl 必须 `--noproxy '*'`，PS 脚本内需清 `DefaultWebProxy`；
> `check-timer-leaks.mjs` 实际位于 **`frontend/scripts/audit/`**，从仓库根跑会 MODULE_NOT_FOUND；
> AI 的 pytest 在 `E:\anaconda3\python.exe`（项目 `.venv` 与 managed python 均**无 pytest**）。

---

## 六、本轮改动文件

**R8 新增/修改**：
- 修改 `frontend/src/stores/auth.js`（LEGACY_REDIRECT_PATHS）
- 修改 `frontend/src/__tests__/auth.spec.js`（新增回归测试）
- 修改 `frontend/src/router/index.js`、`frontend/src/components/layout/TopToolbar.vue`（注释口径）
- 修改 `frontend/src/views/hazard/FieldInspectionPage.vue`（注释口径）
- 新增 `backend/src/test/java/cn/disasterguard/auth/jwt/JwtBlacklistTest.java`
- 修改 `docs/DEVELOPER.md`（§28.7 门禁警示、§29.3 测试数口径）
- 新增 `docs/reviews/CODE_REVIEW_R8.md`（本报告）

工作区其余 26 个已跟踪改动 + 4 个新增文件均为 R7 / 前两轮遗留，本轮**未触碰**。

---

## 七、提交拆分建议

建议把当前 31 个未提交改动按「可独立回滚」拆成 5 个提交，顺序如下：

1. **安全/鉴权**：`JwtBlacklist.java`、`JwtBlacklistCleanupScheduler.java`、`SecurityConfig.java`、
   `SecurityConfigProd.java`、`JwtBlacklistTest.java`、`verify-jwt-blacklist.ps1`、`DEVELOPER.md §29`
2. **端点与门禁基线**：`AiDeprecatedGapController.java`、`check-frontend-endpoints.mjs`、
   `check-endpoint-parity.mjs`（备注）、`orphan_endpoint_scan.py`
3. **前端确定性随机**：`deviceMapService.js`、`FieldInspectionPage.vue`、`CommandScreenPage.vue`、
   `check-math-random.mjs`、`DEVELOPER.md §28.7`
4. **权限与前端测试**：`stores/auth.js`、`auth.spec.js`、`router/index.js`、`TopToolbar.vue`、
   `package.json`、`package-lock.json`
5. **文档与审查报告**：`DEPLOYMENT.md`、`docs/reviews/CODE_REVIEW_R6/R7/R8.md`、其余展示层修正

提交前请先处置 P1-2（AI 全量 pytest 3 例失败），否则「全量测试全绿」的表述仍不成立。
