# 关键决策记录（ADR 精简版）

> 记录"为什么这么做"，不是"做了什么"。每条都有代码/文档出处。
> 供后人查证"这个设计是不是有意的"。

---

## 一、架构级决策

### ADR-1：前端不直连 ai-service

**决策**：所有 AI 调用走 backend 网关，前端不直接访问 `http://ai-service:8001`。

**理由**：

1. **统一鉴权**：backend 已集成 JWT + 权限，AI 服务保持简单
2. **降级可控**：AI 不可用时 backend 统一返回启发式结果，前端无感
3. **CORS 简单**：只需处理一个来源

**出处**：`frontend/src/services/_architecture/domain/aiApi.js` 头部注释

---

### ADR-2：WebSocket 用一次性 ticket 而非长期 JWT

**决策**：WebSocket 握手优先用 `?ticket=<xxx>`（60s TTL，一次性）。

**理由**：

- 反向代理 / 服务器日志会记录 URL query
- 长期 JWT 放 URL → 泄露即在有效期内可被利用
- 一次性 ticket 泄露也无法重放

**兼容**：仍支持 `?token=<JWT>` 给旧客户端，生产环境用 `WS_REQUIRE_TICKET=true` 强制禁用。

**出处**：`backend/.../realtime/WebSocketConfig.java` 顶部注释 +
`docs/DEVELOPER.md` 第 10 章

---

### ADR-3：AI 不可用时降级而非阻塞

**决策**：ai-service 故障时，backend 走启发式/空值，不阻塞主流程。

**理由**：

- AI 是增强功能，不是核心链路
- 预警、巡检、事件流转不能因模型服务挂掉而停
- 降级结果会打标（如 `AI_DEGRADED`、`mock: true`），前端可提示

**出处**：`AiServiceClient` 每个方法内 try/catch +
`docs/DEVELOPER.md` §18 AI 综合分析

---

### ADR-4：统一响应格式 `{ code, data, message }`

**决策**：backend 所有 API 返回 `{ code: 0, data: ..., message: "ok" }`。

**例外**：ai-service **不包装**，直接返回 Pydantic JSON（字段 snake_case）。
由 `AiServiceClient` 用 `@JsonAlias` 转换，再按 ApiResponse 包装返回前端。

**理由**：前端 httpClient 只需处理一种格式；AI 服务保持 FastAPI 原生风格。

**出处**：`docs/DEVELOPER.md` §6.2 + §7

---

## 二、开发约定

### ADR-5：CRLF 策略（含 EMQX 事故）

**事故**：`infra/emqx/init.sh` 曾以 CRLF 提交，容器内 bash 报
`syntax error near unexpected token `$'do\r'``，脚本在第 18 行中止，
EMQX broker 从未启动，容器无限重启。

**决策**：

- `*.sh` / `*.bash` → **强制 LF**（工作区与仓库内一致）
- `*.ps1` / `*.bat` / `*.cmd` → **强制 CRLF**（Windows 脚本）
- 核心代码（`.java` / `.js` / `.vue` / `.py` 等）→ **LF**
- 二进制文件 → 不转换

**不追溯存量**：P2-14 曾尝试 `git add --renormalize .`，会改 323 文件、
42396 行 → **取消**（`git blame` 全废，评审不可能）。
改为 P2-15 显式声明规则，新文件自动规范化。

**出处**：`.gitattributes` + `REFACTOR_TASKS.md` P2-14/P2-15

---

### ADR-6：JWT_SECRET 弱密钥黑名单

**决策**：启动时强制校验 `JWT_SECRET`：

- 长度 ≥ 32 字符
- 不在弱密钥黑名单（`change-me`、`default-secret` 等）
- 不满足 → 应用启动即失败（`IllegalStateException`）

**教训**：`DEVELOPER.md` 原先给的示例串**已被黑名单收录**，照抄会启动失败。
文档现已标注"不要照抄示例值"，改用 `openssl rand -base64 48` 现场生成。

**出处**：`docs/DEVELOPER.md` §4.3 + §11

---

### ADR-7：`version` 字段是序号，不是时间戳

**决策**：WebSocket 消息的 `version` 字段是递增序号，用于幂等 upsert，**不要按时间戳解析**。

**出处**：`docs/DEVELOPER.md` 第 10 章（有显式警告）

---

## 三、已知的坑（重要）

### ⚠️ 坑 1：`sql.init.mode: always` 会清空生产库

**问题**（CODE_REVIEW_R5 P0-1）：

- `schema.sql:97` `DROP VIEW IF EXISTS hazard_point` → 建临时表
- `schema.sql:1352` `DROP TABLE IF EXISTS hazard_point CASCADE` → **级联删全部基础表**
- 升级场景下：`:1352` 删的是**生产数据表本身**
- 结果：`mode: always` 下每次重启 = 全库清空重建

**正解**：`application-prod.yml` 保持 `mode: never`。
生产建表走"发布前手工/CI 执行 schema.sql（限首次部署）+ 引入 Flyway"。

**出处**：`docs/reviews/CODE_REVIEW_R5.md` P0-1

---

### ⚠️ 坑 2：`schema.sql` 不能用 PL/pgSQL `$$` 块

**原因**：`spring.sql.init` 基于 ScriptUtils 按 `;` 切分，不识别 `$$` 块。

**约定**：`schema.sql` 只放**纯 DDL**。PL/pgSQL 函数放 `infra/postgres/init/` 下。

**出处**：`docs/DEVELOPER.md` §8

---

### ⚠️ 坑 3：前端 WS 降级必须保持轮询兜底

**决策**：WebSocket 连接成功后**暂停轮询**，断开/失败时**恢复轮询**。

**理由**：即使 WS 长期不可用，实时数据也不中断。

**实现**：`pausePolling` / `resumePolling` 联动 WS 状态。

**出处**：`docs/DEVELOPER.md` 第 10 章

---

### ⚠️ 坑 4：两种"规则求值器"不要混淆

| 类 | 用途 |
|----|------|
| `WarningRuleEvaluator` | 预警引擎的字符串表达式求值 |
| `ruleengine.RuleEvaluator` | 规则引擎的 AST 求值器（`@Component` Bean） |

**出处**：`docs/DEVELOPER.md` §6 提醒

---

### ⚠️ 坑 5：Node `fs.promises.glob` 只在新版本可用

**问题**（CODE_REVIEW_R5 P1-1）：

- `frontend/scripts/audit/check-timer-leaks.mjs` 用了 `node:fs/promises` 的 `glob`
- 该 API 需要 Node 22.14+（实验）→ Node 24（转正）
- CI 若是 Node 22 早期版本 → `SyntaxError: does not provide an export named 'glob'`

**修复方向**：改用递归 `readdir` 方案（参考 `scripts/fix/check-broken-imports.mjs`）

**出处**：`docs/reviews/CODE_REVIEW_R5.md` P1-1

---

## 四、2026-10 目录重构决策

### 执行总结

| 阶段 | 完成 | 取消 |
|------|------|------|
| P0 | 0 | 2 |
| P1 | 4 | 1 |
| P2 | 8 | 2 |

### 5 个取消任务的决策依据

| 任务 | 取消理由 |
|------|---------|
| **P0-1** | `tests/test_api_endpoints.py` 与 `scripts/test_api_endpoints.py` 用途不同（pytest vs 手动 HTTP），非重复 |
| **P0-2** | 5 个 test 脚本全部依赖 `_bootstrap.py`，移动需逐个补 sys.path |
| **P1-3** | 薄层引用 33 处 vs 深路径 6 处，薄层有效 |
| **P2-11** | 无薄层服务引用面仅 7 处，收益 < 成本 |
| **P2-14** | `git add --renormalize .` 会改 323 文件 42396 行 |

### 关键教训

1. **"看起来该整理" ≠ "值得整理"** —— 5 个取消都验证了这点
2. **只读验证是前提** —— 每个任务先扫引用面，再决定做不做
3. **Cursor Agent 不适合精细重构** —— P1-2 时它擅自移动了计划外文件，改用手动 PowerShell 脚本
4. **移动 Java 类时同包引用会断** —— 必须补 import（P2-8/P2-9 都踩过）
5. **`mvn -q` 失败会静默** —— 编译检查要查 `$LASTEXITCODE`

**完整记录**：`REFACTOR_TASKS.md`

---

## 五、待办与已知问题

### 技术债

| 项 | 说明 | 出处 |
|----|------|------|
| 引入 Flyway | 替代 `spring.sql.init`，根治清库风险 | CODE_REVIEW_R5 P0-1 |
| 修 `check-timer-leaks.mjs` | 兼容 Node 22 早期 | CODE_REVIEW_R5 P1-1 |
| 补测试覆盖率 | 301 生产文件仅 6 测试 | REFACTOR_TASKS P2-4 |
| 清理 CSS 注释噪音 | `/* = rgba */` 批量替换的残留 | REFACTOR_TASKS P2-5 |
| 评估 scheduler 归位 | `JwtBlacklistCleanupScheduler` 等 | REFACTOR_TASKS P2-8 |

### 待办任务

见 `REFACTOR_TASKS.md` 的 P2 段落。

---

## 六、如何新增决策

**建议格式**：

```markdown
### ADR-N：一句话标题

**决策**：做什么

**理由**：为什么（1-3 条）

**出处**：`文件:行号` 或 `文档 §N`
```

**原则**：

- 只记录**非显然**的决策（"为什么这样而不那样"）
- 必有**出处**（代码/文档/评审报告）
- 简洁，200 字内

---

**最后更新**：2026-10-15
**维护者**：见 `git log docs/ONBOARDING/DECISIONS.md`
