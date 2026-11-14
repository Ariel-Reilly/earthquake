# 代码注释与契约规范（CODE_STYLE）

> 适用版本：`v1.8.11-delivery`（基线 commit `47db7de`）
> 最后更新：2026-11-14
> 性质：**约定 + 可执行验收**。新增/修改代码按本文执行；存量代码在「阶段 1 低风险清理」按模块逐步对齐，不要求一次性全改。

---

## 0. 为什么单独定这份规范

当前仓库注释密度并不低（后端约 14%、前端约 15%），真正的问题是**注释质量与一致性**：

- 大量注释在「讲历史修复过程」，容易与现状脱节（例如某轮复测的临时判断）；
- 公共方法缺少「权限 / 事务 / 副作用 / 降级行为」说明；
- `TODO`、mock、501、演示数据散落各处，没有统一标记词，导致「这是真数据还是演示数据」需要读代码才知道。

本规范的目标不是「多写注释」，而是让注释**解释约束与取舍**、让「未实现/降级/演示」一眼可辨。

**总原则：先冻结功能、再小步重构；一个 PR 只做一个主题；每步可回滚、可验收。**

---

## 1. 注释原则

1. 注释解释 **为什么 / 约束 / 取舍**，不复述代码；
2. 历史修复过程放 `docs/`（ADR 或验收日志），代码里最多留一句摘要 + 链接；
3. 公共 API 必须有文档注释；内部复杂分支才需要行内注释；
4. 统一：**中文说明 + 英文标识符**；文件 UTF-8；禁止乱码、禁止注释掉的旧代码（删除，交给 git 历史）；
5. 注释与实现必须一致——宁可少写，不可写过期的内容。

---

## 2. 文件头模板

### 2.1 Java

```java
/**
 * 模块：预警生命周期
 * 职责：确认 / 派发 / 关闭 / 解除 状态流转与审计
 * 关键不变量：
 *   - 终态不可再次流转（返回 409）
 *   - 每次流转写审计日志
 * 关联：docs/architecture/ADR-003-warning-state.md
 */
```

### 2.2 Vue SFC

```vue
<!--
  页面：总览
  职责：活跃预警 + KPI + 地图概览的只读聚合视图
  数据口径：预警时间一律经 formatWarningTime 本地化，禁止直接渲染 API 的 time 快照
  关联：docs/api/CONTRACT.md（时间/分页）
-->
```

### 2.3 Python

```python
"""
Module: drift monitor
Responsibility: 计算在线特征漂移并返回结构化告警
Invariants:
  - 无基线时返回空结构，不臆造漂移分
References: docs/architecture/README.md（ADR 索引；AI 降级 ADR 待建）
"""
```

> 纯 DTO / 纯常量 / 纯类型声明可豁免文件头。

---

## 3. 公共方法必须写清的四件事

对 `public` / 导出函数，文档注释至少覆盖：

1. **做什么 + 权限要求**（需要哪个 `hasAuthority`）；
2. **事务 / 幂等 / 副作用**（写库、发通知、WS 广播、外部调用）；
3. **参数 / 返回 / 异常**（尤其 4xx/409/501 的语义）；
4. **演示 / 降级行为**（如「AI 不可用返回 fallback，标注 generatedBy=rule」）。

### 3.1 前端 JSDoc 模板

```js
/**
 * 按浏览器时区格式化预警时间（A6-5）。
 * 只读展示层函数，不做任何网络/副作用。
 * @param {{triggeredAt?: string, time?: string}} warning
 * @param {string} [timeZone] 仅供测试/SSR 显式指定，缺省用浏览器时区
 * @returns {string} "MM-dd HH:mm"；无有效值时回退 time 或 '—'
 */
export function formatWarningTime(warning, timeZone) {}
```

---

## 4. 统一标记词（全项目一致）

在关键决策点使用固定前缀，便于 grep 与 review：

| 标记 | 用途 | 示例 |
|---|---|---|
| `// CONTRACT:` | 接口/字段契约约定 | `// CONTRACT: 分页统一 records/total/page/size/pages` |
| `// SECURITY:` | 权限 / 代签 / CORS 等安全决策 | `// SECURITY: 已弃用端点仍走 ai:insight:view，匿名调用得 403` |
| `// FALLBACK:` | 降级 / mock 数据来源 | `// FALLBACK: ai-service 不可达时用落库评分，标注非实时` |
| `// DEMO:` | 演示 / seed 数据 | `// DEMO: 巡检报告为按查询参数生成的演示数据，非真实统计` |
| `// TODO(owner, date, issue):` | 有主待办 | `// TODO(lizhe, 2026-11-20, #123): 补 RelatedWarning.id` |

**规则：**

- 无主 TODO（缺 owner/date/issue 任一）不允许进主干；
- `@Deprecated` 必须写**替代方案**与**计划移除版本**；
- 「未实现 / 未接入」的能力必须同时：后端返回明确语义（501 或 `ApiResponse.fail(501,...)`）+ 前端显示「能力未接入」，不得用 0 或假数据冒充。

---

## 5. 契约红线（与注释同等重要）

注释之外，以下契约必须在 `docs/api/CONTRACT.md` 固定，并由契约测试兜底：

1. **分页**：统一 `PageResponse{records,total,page,size,pages}`（**该类型已存在**，见 `common/api/PageResponse.java`）；前端只在 `api/client` 归一化一次，禁止页面层各写一套 `records/items/list` 兼容分支。
2. **时间**：API 返回 ISO `triggeredAt`（绝对时刻）；前端统一 `formatWarningTime` 本地化；`WarningDto.time` 为兼容快照字段，**标记为待弃用**，外部消费者以 `triggeredAt` 为准。
3. **ID**：数据库数字 `id` vs 业务 `code` 严格区分；禁止用 `code` 拼 `/warnings/{id}`。`RelatedWarning` 目前无数字 `id`，跳转受限属已知 P2。
4. **Mock / 演示**：禁止静默 mock；fallback 必须带 `demo`/来源标识 + 页面横幅；能力缺口登记到 `docs/delivery/KNOWN_LIMITATIONS`（或统一总表）。
5. **权限单一来源**：`auth.js` 权限表 → `sync-permission-registry.mjs` → 后端注册表；CI 校验 `required - registry == 0`。

---

## 6. 注释验收（可执行）

新增 / 修改代码进入 review 前，至少满足：

- [ ] 新增/修改的 `public` class / method：Javadoc / JSDoc 覆盖率 **100%**；
- [ ] 每个新增文件有文件头（职责 + 关键约束），纯 DTO/常量可豁免；
- [ ] 全仓**无未登记裸 TODO**（缺 owner/date/issue）、**无注释掉的旧代码**、**无过期日期叙述**；其中 AI 9 处为 L-01/L-04 已知边界，已在 `INVENTORY §4.2` 显式 allowlist 豁免，阶段 2 补 owner 后移除；后端 6 处已补占位 owner（`INVENTORY §4.3`），待真实负责人替换；
- [ ] 所有 `@Deprecated` 带替代方案与移除计划；
- [ ] 所有「未实现/未接入/演示」点使用第 4 节标记词，且前后端表现一致（不冒充真实）；
- [ ] Code review 抽查 20 个文件，注释与实现**一致率 100%**。

### 6.1 静态检查（跨平台，Node 脚本）

裸 TODO / FIXME 用项目内 Node 脚本检查（**不依赖 grep，Windows 可用**）：

```bash
# 阶段 1 过渡：先告警不阻断
node scripts/quality/check-comments.mjs --warn

# 阻断模式（裸标记 > 0 即非零退出，供 CI 使用）
node scripts/quality/check-comments.mjs
```

脚本规则：**只在注释 / docstring 文本内**匹配（代码标识符与字符串常量不算，例：测试 fixture `const TODO = {...}` 不计入）；大小写敏感 `\bTODO\b` / `\bFIXME\b`；允许 `TODO(owner, 2026-11-20, #123)`；输出区分「已带 owner / allowlist 豁免 / 裸标记」三档；AI 9 处按 `INVENTORY §4.2` 豁免；默认裸标记 > 0 即失败。
覆盖范围：`.java/.js/.vue` 的 `//` 与块注释（含 javadoc `*` 续行）；`.py` 的 `#` 与 `""" / '''` docstring（含跨行）。已知取舍：不做完整词法分析，字符串里出现 `//`、`#`、`"""` 时可能「多报」，不会漏报真实注释。
**引用不算标记**：注释里用反引号包裹的关键字（如 `` `TODO` ``，解释规则时常见）视为「提及」而非待办，不计入。

> **为什么不用「标记必须出现在行首」做区分**：实测该规则会把 `sentinel.py:799/1177` 两处 docstring 正文里的待实现点判为「提及」，
> 导致 AI allowlist 从 9 降到 7、L-04 台账与统计口径都要改。这属于**语义决策**（是否把「散文里提到的待实现点」也算待办），
> 需要单独确认，不在扫描器里单方面收紧。当前采用「反引号排除」这条零歧义的规则来挡住误报。

同一脚本还检查**注释掉的旧代码**（`.js/.vue/.java` 的 `// 关键字`），默认只报告，`--strict-legacy` 才阻断：

```bash
node scripts/quality/check-comments.mjs --strict-legacy
```

**过期 TODO 提醒**：带 owner 的 TODO 若 `date < 今天` 会报告；`--strict-stale` 可阻断（CI 默认只提醒，不阻断，避免日期到点误红）。

```bash
node scripts/quality/check-comments.mjs --strict-stale
```

**allowlist 自检**（防止 AI TODO 文案微调后「豁免悄悄失效」）：每条 allowlist 必须命中 1 行，失效/重复命中即报错并点名条目。

```bash
# 过渡期：仅校验 allowlist 完整性（忽略裸标记阻断）
node scripts/quality/check-comments.mjs --warn --selfcheck

# CI 实际使用（严格：裸标记 + allowlist 自检）
node scripts/quality/check-comments.mjs --selfcheck
```

自检本身也有回归（覆盖 文件缺失 / 片段重复 / 干净 fixture（含清零后严格模式）/ 真实仓库 / 过期 TODO 5 类，13 个断言）：

```bash
node scripts/quality/check-comments.selftest.mjs
```

仅 `@Deprecated` 的替代方案说明仍建议人工抽查：

```bash
grep -rn '@Deprecated' backend/src --include='*.java'
```

> 本阶段只制定规范，**不批量改存量**。存量按「阶段 1 低风险清理」逐模块对齐，每模块单独 PR + 单测 + 截图。

---

## 7. 与「项目整理方案」的对应关系

| 方案条目 | 本文对应 |
|---|---|
| 方向 4.1 原理 | §1 |
| 4.2 文件头模板 | §2 |
| 4.3 公共方法 Javadoc | §3 |
| 4.4 前端 JSDoc | §3.1 |
| 4.5 统一标记词 | §4 |
| 4.6 注释验收 | §6 |
| 方向 1 契约 | §5（红线）+ `docs/api/CONTRACT.md`（待建） |
