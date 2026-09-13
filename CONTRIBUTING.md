# 贡献指南 · CONTRIBUTING

> 欢迎参与地脉智巡项目！本文档说明代码规范、提交规范与 PR 流程。

---

## 1. 开发环境

| 工具 | 版本 | 用途 |
|---|---|---|
| Node.js | ≥ 18.0 | 前端 |
| npm | ≥ 9.0 | 前端包管理 |
| JDK | 17 | 后端 |
| Maven | ≥ 3.8 | 后端构建 |
| Python | 3.10+ | AI 服务 |
| Docker Desktop | 最新 | 基础设施 |
| PowerShell | 7+（推荐） | Windows 开发 |
| Git | ≥ 2.30 | 版本控制 |
| VS Code | 最新 | 推荐 IDE（含前端 + Java 扩展） |

---

## 2. 本地启动

```bash
# 1. 克隆仓库
git clone <repo-url>
cd earthquake

# 2. 启动基础设施
.\start-dev.ps1 -Infra -Up          # Windows
./start-dev.sh infra up             # Linux/Mac

# 3. 启动开发服务（3 个终端）
.\start-dev.ps1 -Backend
.\start-dev.ps1 -Frontend
.\start-dev.ps1 -Ai
```

详细排错见 [docs/DEVELOPER.md](docs/DEVELOPER.md) §14。

---

## 3. 分支策略

```
main                ── 生产稳定
  ├─ feature/*      ── 新功能（从 develop 切出）
  ├─ fix/*          ── Bug 修复
  ├─ refactor/*     ── 重构（无功能变化）
  └─ docs/*         ── 纯文档变更
```

- 所有 PR 目标分支：`main`
- Feature 分支命名：`feature/<模块>-<功能>`，如 `feature/hazard-batch-confirm`
- Fix 分支命名：`fix/<模块>-<问题>`，如 `fix/warning-status-transition`

---

## 4. 提交规范（Conventional Commits）

```
<type>(<scope>): <subject>

<body>

<footer>
```

### 4.1 Type 类型

| Type | 用途 |
|---|---|
| `feat` | 新功能 |
| `fix` | Bug 修复 |
| `refactor` | 重构（无功能变化） |
| `perf` | 性能优化 |
| `style` | 代码风格（无逻辑变化） |
| `test` | 测试 |
| `docs` | 文档 |
| `chore` | 构建/工具链/CI |
| `revert` | 回滚 |

### 4.2 Scope 范围

常用：`frontend` / `backend` / `ai-service` / `infra` / `docs` / `scripts` / `router` / `auth` / `hazard` / `warning` / `ai` / `notification` / ...

### 4.3 Subject 主题

- 中文/英文均可，保持简短（≤ 50 字符）
- 不加句末标点
- 动词开头："新增" / "修复" / "优化" / "重构"

### 4.4 示例

```bash
git commit -m "feat(hazard): 新增灾害批量确认功能"
git commit -m "fix(warning): 修复预警状态流转越权问题"
git commit -m "refactor(auth): 抽取 JWT 解析到独立中间件"
git commit -m "docs: 更新 DEVELOPER.md §14 故障排查"
git commit -m "chore: 升级 vue-router 至 4.4"
```

---

## 5. 代码风格

### 5.1 前端（Vue 3 + Vite）

- **缩进**：2 空格
- **引号**：JS 字符串单引号 `'`、模板字符串反引号 `` ` ``
- **分号**：可选（项目使用标准风格，带分号）
- **命名**：
  - 组件文件：`PascalCase.vue`
  - 工具/服务：`camelCase.js`
  - 常量：`UPPER_SNAKE_CASE`
- **Composition API**：统一使用 `<script setup>`
- **Props**：`defineProps({ ... })`，类型用 TypeScript-like 语法
- **状态**：跨组件共享用 Pinia，本地状态用 `ref/reactive`
- **样式**：`<style scoped>`，CSS 变量优先

提交前必跑：

```bash
npm run lint
```

### 5.2 后端（Java 17 + Spring Boot 3）

- **缩进**：4 空格
- **命名**：
  - 类/接口：`PascalCase`
  - 方法/字段：`camelCase`
  - 常量：`UPPER_SNAKE_CASE`
- **包结构**：`cn.geosentry.<domain>.<layer>`，如 `cn.geosentry.hazard.controller`
- **Controller**：薄层（参数解析 + 调 service + 返回 DTO）
- **Service**：业务逻辑 + 事务边界
- **Repository**：纯 JPA 接口
- **统一异常**：使用 `GlobalExceptionHandler`，禁止 Controller 内 try-catch
- **审计字段**：`@CreatedDate` / `@LastModifiedDate` 由 JPA Auditing 自动填充

### 5.3 AI 服务（Python 3.10+）

- **缩进**：4 空格
- **类型注解**：所有公开函数必加
- **命名**：`snake_case`
- **Pydantic**：所有请求/响应 schema 用 BaseModel
- **路由**：仅参数解析 + 调 service + 返回，禁止业务逻辑

### 5.4 SQL

- 表名：`snake_case`，单数（如 `hazard_event`，不是 `hazards`）
- 主键：`id BIGSERIAL PRIMARY KEY`
- 时间字段：`created_at TIMESTAMPTZ DEFAULT now()` / `updated_at TIMESTAMPTZ`
- 软删除：`deleted_at TIMESTAMPTZ`（NULL = 未删除）
- 索引：高频查询字段必加索引，外键必加索引
- 空间字段：PostGIS `geometry(Point, 4326)` + GIST 索引

---

## 6. PR 流程

### 6.1 创建 PR 前

- [ ] 在本地跑通 `npm run lint` / `mvn -DskipTests verify` / `pytest`
- [ ] 至少 1 名团队成员 Review 通过
- [ ] CI 全部通过（lint + build + test）
- [ ] 已更新相关文档（DEVELOPER.md / README.md / CHANGELOG.md）
- [ ] 已关联对应 Issue（若有）
- [ ] Commit message 符合 Conventional Commits

### 6.2 PR 标题

```
[<scope>] <类型>: <简要描述>

例:
[backend] feat(hazard): 新增灾害批量确认功能
[frontend] fix(warning): 修复预警状态流转越权问题
[docs] docs: 更新 DEVELOPER.md §14
```

### 6.3 PR 模板

```markdown
## 变更类型
- [ ] 新功能
- [ ] Bug 修复
- [ ] 重构
- [ ] 文档
- [ ] 性能优化
- [ ] 其他

## 变更说明
<!-- 详细描述本次改动 -->

## 相关 Issue
<!-- 关联 #123 -->

## 测试
<!-- 如何测试 -->

## 截图/录屏
<!-- UI 改动附图 -->

## Checklist
- [ ] 已通过本地测试
- [ ] 已更新文档
- [ ] 已同步至 CHANGELOG.md
```

---

## 7. Code Review 要点

### 7.1 业务
- 是否符合统一事件中心的状态机约束？
- 是否考虑了降级场景（AI 不可用、MQTT 断开）？
- 是否影响了其他模块的数据一致性？

### 7.2 安全
- 是否做了权限校验（路径 + 操作）？
- 是否注入了用户输入到 SQL / HTML / JS？
- 敏感字段（密码、密钥）是否脱敏？

### 7.3 性能
- 是否走了索引？
- N+1 查询是否需要 `@EntityGraph`？
- 长事务是否拆分？

### 7.4 可维护性
- 命名是否自解释？
- 是否重复了已有代码（应抽取）？
- 是否有足够注释解释"为什么"？

---

## 8. 版本发布流程

1. 合并所有 feature → `release/vX.Y.Z` 分支
2. 在 `CHANGELOG.md` 整理该版本变更
3. 跑通完整测试套件
4. Tag：`git tag -a vX.Y.Z -m "release notes"`
5. 推送 tag：`git push origin vX.Y.Z`
6. 部署到生产（CI/CD 自动触发）

---

## 9. 问题反馈

- **Bug** → GitHub Issues
- **安全问题** → 邮件 `security@geosentry.cn`（**不要**开 Issue）
- **功能建议** → GitHub Discussions

---

## 10. 行为准则

- 尊重所有贡献者
- 接受建设性批评
- 关注对社区最有利的事
- 与其他贡献者协作

违反行为准则的 PR/Issue 将被关闭。
