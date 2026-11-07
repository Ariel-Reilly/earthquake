# 代码审查报告 R9（2026-11-06）

> 审查对象：HEAD `0fbc2fc` 之后**全部未提交改动**（R7 收尾 + 两轮修复 + 本轮）
> 审查方式：静态门禁 + 全量单测 + **真实后端 / 真实 Redis / 真实 Postgres** 实测 + 逐行复核
> 结论：**无 P0 阻断项**；R8 列出的 6 项遗留已全部处置（5 项修复、1 项按设计保留并落文档）；
> 本轮新发现 **1 处口径纠正**（Redis 残留的「假 0」）与 **2 项需知边界**。

---

## 一、审查范围与方法

| 项 | 值 |
|---|---|
| HEAD | `0fbc2fc`（R6 成果落库，本轮无新提交） |
| 工作区 | 40 项变更 = 30 个已跟踪文件修改 + 10 个新增文件 |
| diff 规模 | `30 files changed, 1297 insertions(+), 246 deletions(-)`（`--ignore-cr-at-eol` 口径） |
| 本轮定位 | 复核 R8 的 4 项修复是否引入回归；处置 R8 报告 §三 的遗留（P1-2 / P2-4~P2-8） |

审查动作为四段，缺一段就不算结论：

1. **门禁**：7 个脚本逐个跑并记退出码（不看「有没有输出」，只看 `EXIT`）。
2. **全量测试**：后端 `mvn clean compile` + `mvn test`、前端 `npm test` + `npm run build`、AI 侧 `pytest tests/`（**全量**，不是只跑契约文件）。
3. **运行时**：真实起后端（8080）+ 真实 Redis，实测 501 桩、HTTP method 限制、登出吊销、E2E 脚本；真实库只读 SQL 全部包在 `BEGIN/ROLLBACK` 里。
4. **逐行复核**：`git diff --ignore-cr-at-eol` 通读后端 → 前端 → ai-service → 脚本/文档，再对「正则/脚本给出的结论」手工复核一遍（历史教训：门禁全绿≠可用）。

---

## 二、本轮修复（6 项）

### P1-2 AI 全量 pytest 3 例失败 —— 已修（改实现 + 对齐测试口径）

**根因不是测试写错，而是实现漏写元数据。** `ai-service/scripts/training/train_fusion_v3.py` 训练时算出了
`mae` / `rmse` / 分档一致性，也算了 `feature_importance`，但**只 `print` 没落盘**；
meta 里只有 `best_score` / `best_iteration` / `device_weights` 等。测试却按 v2 分类口径断言
`model == "xgboost-fusion"` 和 `val_metrics.f1`——v3 是 `reg:squarederror` 回归模型，**根本没有 f1 这个量**。

处置（三条一起，缺一条都不成立）：

| # | 改动 | 说明 |
|---|---|---|
| 1 | `train_fusion_v3.py` 补落盘 | meta 新增 `feature_importance`（按 gain，缺特征补 0）、`feature_importance_type="gain"`、`val_metrics{mae, rmse, level_agreement, eval_dataset, eval_seed}` |
| 2 | 新增 `scripts/training/backfill_fusion_meta.py` | 不改权重、只重放同一份合成训练集（`seed=42`）重算指标写回 meta；**先 `--dry-run`**，重算 RMSE `0.958` 与 meta 已有 `best_score 0.957973` 完全吻合，证明重放链路可复现后才正式写 |
| 3 | `tests/test_xgboost_model.py` 对齐 v3 真实约定 | 模型标识用常量 `EXPECTED_MODEL_ID = "xgboost-fusion-v3"`；重要性断言 `len==7` 且键集合等于 `feature_names`；回归指标断言 `rmse<2.0` / `mae<1.5` / `level_agreement>=0.9`，并校验 `best_score ≈ rmse` |

回填后 meta 实测：

```json
{"mae": 0.7345, "rmse": 0.9579, "level_agreement": 0.9767, "eval_dataset": "synthetic", "eval_seed": 42}
```

`feature_importance` 7 项齐全，gain 最高为 `displacement_rate(1414.6)`、`rainfall_24h(1318.2)`。

**结果**：`3 failed / 99 passed / 4 skipped (EXIT=1)` → **`102 passed / 4 skipped (EXIT=0)`**。

**必须说清的边界**：`eval_dataset=synthetic`，这组 MAE/RMSE 是**合成评估集**上的数字，
只能用来防「越改越差」，**不能对外当成真实灾害样本的精度**。要真实指标得用 `datasets/` 的真实标注重训重评。

### P2-4 501 桩未限定 HTTP method —— 已修

`AiDeprecatedGapController` 的兜底方法由 `@RequestMapping({"/drift/**","/multimodal/**"})`
改为 `method = {RequestMethod.GET, RequestMethod.POST}`。实测：

| 请求 | 修复前 | 修复后 |
|---|---|---|
| `GET /api/ai/drift`、`POST /api/ai/drift` | 501 | **501** |
| `PUT /api/ai/drift`、`DELETE /api/ai/multimodal` | 501（被一并吞掉） | **405** |

保留项：**未认证调用仍是 403 而不是 501**。这是刻意的安全边界（不给已弃用端点开匿名访问），
已写进类 javadoc 第 27-28 行，避免下次有人把它当 bug 改成 `permitAll`。

### P2-5 `Math.random()` 门禁未挂自动化入口 —— 已修

`frontend/package.json`：

```json
"check:random": "node ../scripts/audit/check-math-random.mjs",
"pretest":      "npm run check:random",
"check:all":    "npm run check:random && npm run check:tokens && npm run check:sfc && npm run check:design"
```

实测 `npm test` 会先跑门禁（0 违规）再跑 8 个用例，**违规时测试阶段直接失败**，不再依赖人记得手动跑。

### P2-6 端点门禁把「有映射」当「已实现」—— 已修

`check-frontend-endpoints.mjs` 新增 `deprecatedStubs`：文件里同时出现 `NOT_IMPLEMENTED` 与 `@Deprecated`
即标记为桩，命中时单独输出：

```
○ 命中已弃用 501 桩（映射存在但未实现，共 2）：
    GET /api/ai/drift        后端返回 501 NOT_IMPLEMENTED   <- ...
    这些路径「通过」只代表有映射兜底，不代表功能已实现；请勿据此判定缺口已修复。
```

门禁仍然 `EXIT=0`（桩是有意为之，不该阻断 CI），但**不再静默地把桩算成功能已补齐**。

### P2-7 E2E 脚本断言不精确 + 跨轮残留 —— 已修

- 断言由「存在任意 `jwt:blacklist:*`」改为 `$keys -contains $expectedKey` 的**精确比对**，
  PASS 文案直接打出 `exactKey=... scanned=3`。
- 收尾 `DEL` 本次写入的 key，输出 `[CLEANUP] DEL ... -> deleted=1`；需要留现场时设 `KEEP_REDIS_KEY=1`。
- 实测重跑 **10/10 PASS，EXIT=0**：本次 `scanned=3` 里有 2 个是更早轮次的残留，
  精确断言不受干扰——这正好验证了本次修的就是这个坑。

### P2-8 `DEPLOYMENT.md` 与 compose 强制变量口径矛盾 —— 已修

`DATA_ENCRYPTION_KEY` / `MINIO_ENDPOINT` 两行由「默认值 xxx」改为「**prod 必填、无默认值**；
dev 有兜底」。与 `docker-compose.prod.yml` 的 `${VAR:?}` 语义一致，不再出现「文档说有默认、compose 直接拒绝解析」。

---

## 三、复核：判定为正确的部分

逐条复核 R8 的修复，确认没有引入回归：

| 复核项 | 结论 | 依据 |
|---|---|---|
| `LEGACY_REDIRECT_PATHS` 是否扩权 | **不扩权** | 只放行 6 个 redirect 别名；redirect 落地后目标页仍要再过一次守卫 |
| `matchesMenuPath` 边界匹配 | **正确** | 精确或 `${p}/` 边界；根节点 `/dashboard` 只精确匹配 |
| `JwtBlacklistTest` 5 例有效性 | **有效** | 覆盖 TTL 捕获、`StringRedisTemplate` 未注入、读写抛异常、本地镜像优先 |
| `JwtBlacklist` 写入语义 | **正确** | key `jwt:blacklist:{jti}`、value `"1"`、TTL=剩余有效期；E2E 实测 TTL `86399s` vs 剩余 `86400s` |
| `logout` 加 `permitAll` 是否放宽过度 | **未过度** | 仅 `/api/auth/logout`；无 token/无效 token 时控制器空操作返回成功，仅合法 JWT 能吊销自身 |
| 501 桩是否遮蔽既有端点 | **未遮蔽** | `GET /api/ai/health` 仍 200（`{"code":0,"message":"degraded"}`） |
| 通知状态域与库约束冲突 | **无冲突** | 见下节口径纠正 |

---

## 四、新发现与口径纠正

### 4.1 【口径纠正】「Redis 0 残留」是假 0 —— 已修文档并复查

上一轮我用

```bash
docker exec disasterguard-redis sh -c 'REDISCLI_AUTH=$REDIS_PASSWORD redis-cli --scan ... | wc -l'
```

得到 `0`，据此说「无残留」。**这个 0 是假的**：容器内没有导出 `REDIS_PASSWORD`，
`REDISCLI_AUTH` 展开为空 → `AUTH failed: WRONGPASS` 走 stderr 被吞掉 → `wc -l` 统计空输入得 0。

改用宿主机 `.env` 注入口令后，真实情况是 **2 个更早轮次的残留 key**：

| key | TTL |
|---|---|
| `jwt:blacklist:0c190f0a-...` | `80166 s`（≈22.3 h） |
| `jwt:blacklist:943d3f20-...` | `77823 s`（≈21.6 h） |

两条都有 TTL，会自然过期（R8 之前脚本没有收尾清理）。当前脚本已自带 `DEL`，新跑不再堆积。
**该坑已写入 `docs/DEVELOPER.md §29.5`**：只要输出里出现 `AUTH failed` / `NOAUTH`，这一行计数就不可信。

### 4.2 【口径纠正】「通知表无 CHECK 约束」的表述不完整

R8 记「CHECK 约束 0」。本轮复核：库里 `conname LIKE '%status%'` 的 CHECK **有 8 条**，
但全部挂在别的表（`ck_ai_model_status` / `ck_approval_status` / `ck_confirm_status` / `ck_ds_status` /
`ck_plan_status` / `ck_resource_status` / `ck_sync_task_status` / `ck_todo_status`）。

结论对通知链路仍然安全：`notification_record.status` 与 `notification_receipt.status` 都是
`varchar(20)` 且**无 CHECK**，`MOCK_SENT`(9) / `BLOCKED`(7) / `PARTIAL`(7) / `SENDING`(7) 都不会触发约束。
但「0 约束」这个说法容易被误读成「库里没有 CHECK」，后续引用请用「通知两表无 CHECK」。

### 4.3 【需知边界】AI 指标基于合成评估集

见 P1-2 的边界说明。另：`ai-service` 本轮**未启动**（8001 无监听），
`/api/ai/health` 返回 `degraded`，**直连 ai-service 的 drift / multimodal 真实链路本轮未验证**。

---

## 五、仍未修 / 未做

### 5.1 需决策（非本轮可闭环）

| 项 | 说明 |
|---|---|
| P2-4 残留：匿名调用返 403 | 刻意保留（不给已弃用端点开匿名）；若要匿名也能看到 501，需改 `permitAll`，属安全边界变更 |
| `check-math-random.mjs` 未进 CI | 已挂 `npm test` 前置，但仓库**没有 CI 配置**（`.github/workflows` 不存在），真正拦合并还需建流水线 |
| `xgboost_fusion.meta.json` 已回填但模型未重训 | 回填用的是同一份合成数据 `seed=42` 重放，指标可信；但**权重本身仍是旧的**，没有重新训练 |

### 5.2 历史遗留（本轮确认未变化，沿用 R7/R8 §5）

EMQX 5.9 MQTT allow-all；6 个 Sender 未接真实网关（`MOCK_SENT` 语义正确）；CAS CC-BY-NC-4.0 数据禁商用（按用户「不商用」决策保留）；
AI 四项能力未自动串联；通知白名单 fail-closed 使外部投递固定 `BLOCKED`；多模态 5 端点仍是演示数据；
自适应归一化未接入评分路径（`applied=0`）；`confidence>0.8` 阈值；前端仍是页面级 0 单测（只有 `auth.spec.js` 8 例）；
端到端联调（设备上报→预警→AI→方案→通知）未跑。

### 5.3 本轮未做

- 未停 Redis 容器做断网降级演练（`JwtBlacklistTest` 单测已覆盖异常分支，但非真实断网）。
- 未起 ai-service，未验证 `drift` / `multimodal` 直连 8001 的真实响应。
- 未做浏览器级点击验证（旧链接 redirect 是否真的落到目标页）。
- `docker ps -a` 里仍有 7 个 2 个月前的 `geosentry-*` / `thingspanel-*` Exited 容器，与本仓库无关，未清理。

---

## 六、实测验证矩阵（2026-11-06 本次运行）

| 验证项 | 命令 / 方式 | 结果 |
|---|---|---|
| 后端编译 | `mvn clean compile` | **BUILD SUCCESS**（330 源文件） |
| 后端单测 | `mvn test`（surefire 20 个报告汇总） | **104 / 104，Failures 0，Errors 0** |
| 前端单测 | `npm test`（含 `pretest` 门禁） | **8 / 8**，1 个测试文件 |
| 前端构建 | `npm run build` | **✓ built in 5.10s** |
| AI 全量测试 | `pytest tests/ -q` | **102 passed, 4 skipped, EXIT=0** |
| 门禁 7 项 | `check-missing-imports` / `check-endpoint-parity` / `check-frontend-endpoints` / `check-math-random` / `check-timer-leaks` / `check-broken-imports` / `orphan_endpoint_scan.py` | **全部 EXIT=0** |
| 501 桩 | 真实后端 + admin token | 5 条 GET/POST 路径 **501**，响应体含 `deprecated:true` |
| method 限定 | `PUT /api/ai/drift`、`DELETE /api/ai/multimodal` | **405** |
| 匿名边界 | `GET /api/ai/drift` 无 token | **403**（刻意） |
| 既有端点 | `GET /api/ai/health` | **200**，未被遮蔽 |
| JwtBlacklist E2E | `verify-jwt-blacklist.ps1` 真实后端运行 | **10/10 PASS，EXIT=0**；TTL `86399s`；旧 token `200→401`；`CLEANUP deleted=1` |
| Redis 键空间 | 宿主机 `.env` 口令注入 | 2 个历史残留 key（TTL 22.3h / 21.6h，自然过期） |
| 真实库只读 | `BEGIN ... ROLLBACK` | 通知两表 `status varchar(20)`；8 条 `%status%` CHECK 全在其他表；`unified_event=5 / warning_event=10 / warning_rule=6` |
| 容器 | `docker ps` | 5 个依赖容器全部 `Up (healthy)` |
| 临时文件 | `git status` 过滤 | **0 处**（无 `_r8_` / `_r9_` / `__pycache__` / `dist.prev`） |

后端验证完已停止（8080 无监听），5 个容器保持运行。

---

## 七、提交拆分建议

工作区累积了 4 轮改动（1161→1297 行），建议按「一个提交一个可回滚单元」拆，**不要一把梭**：

1. `fix(security): logout 吊销链路 + JwtBlacklist Redis 化 + 降级单测`
   `JwtBlacklist.java`、`JwtBlacklistCleanupScheduler.java`、`SecurityConfig*.java`、`JwtBlacklistTest.java`、`verify-jwt-blacklist.ps1`、`DEVELOPER.md §29`
2. `feat(ai): 补齐 v3 融合模型元数据并回填 + 测试对齐真实口径`
   `train_fusion_v3.py`、`backfill_fusion_meta.py`、`xgboost_fusion.meta.json`、`test_xgboost_model.py`
3. `feat(api): 两个 KNOWN_GAPS 路径改为显式 501 deprecated`
   `AiDeprecatedGapController.java`、`check-frontend-endpoints.mjs`、`orphan_endpoint_scan.py`
4. `fix(frontend): 权限边界匹配 + 清除确定性随机 + 冒烟测试`
   `stores/auth.js`、`auth.spec.js`、`deviceMapService.js`、`FieldInspectionPage.vue`、`CommandScreenPage.vue`、`package.json`、`package-lock.json`，以及 `TopToolbar.vue` / `router/index.js` 的注释同步
5. `chore(audit): Math.random 门禁 + 文档口径修正`
   `check-math-random.mjs`、`DEPLOYMENT.md`、`DEVELOPER.md §28.7/§29.5`、`docs/reviews/*`

每拆一个都跑一遍对应验证（1 → `mvn test` + E2E 脚本；2 → `pytest`；3 → 端点门禁；4 → `npm test` + `npm run build`；5 → 全门禁），
保证任意单个提交都能独立通过门禁，便于 bisect。
