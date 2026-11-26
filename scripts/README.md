# scripts/

运维、审计、修复脚本。按用途归档到子目录：

| 子目录   | 用途                                       | 关键脚本                                                                                          |
|----------|--------------------------------------------|--------------------------------------------------------------------------------------------------|
| `db/`    | Postgres / schema / 数据回填              | `audit_db.py`, `backfill_unified_event_hazards.py`, `gen_data_sql.py`, `smoke_test_schema.py`     |
| `audit/` | 代码与数据的一致性审计                     | `orphan_endpoint_scan.py`, `empty_feats.py`, `audit-imports.ps1`                                  |
| `fix/`   | 已知的破坏性修复（一次性，但保留以备复用） | `oneclick-fix.mjs`, `fix-db-volume.ps1`, `reinit-postgres.ps1`                                    |
| `env/`   | 端口、进程、健康探针                       | `check-ports.ps1`, `check-procs.ps1`, `check-env.ps1`, `vite-check.ps1`, `wait-docker.ps1`        |

## db/ — Postgres 与数据相关

| 脚本                            | 说明                                                                                       |
|---------------------------------|--------------------------------------------------------------------------------------------|
| `audit_db.py`                   | 比对数据库实体与 `schema.sql`/`entity/*.java`，列出漂移。耗时较长，建议在 schema 变更后跑。 |
| `backfill_unified_event_hazards.py` | 把 `unified_event` 的 hazard 关系按当前规则回填。一次性数据修复。                      |
| `gen_data_sql.py`               | 把 `entity/` 与 `mock/seed.*` 导出成可执行的 `data.sql`。                                   |
| `smoke_test_schema.py`          | 链接 DB 跑一系列轻量查询，验证初始化后表结构/视图可用。                                     |
| `check_jsonb_sql.py`            | 列出所有 `jsonb` 类型的列与默认约束，排查 JSON 字段丢失。                                   |

## audit/ — 静态 / 动态审计

| 脚本                              | 说明                                                            |
|-----------------------------------|-----------------------------------------------------------------|
| `orphan_endpoint_scan.py`         | 列出后端 Controller 端点 vs 前端调用的差异（fuzzy 占位匹配）。  |
| `empty_feats.py`                  | 扫描所有 `.vue` 找完全没有后端调用的页面（识别"空功能"）。       |
| `audit-imports.ps1`               | 列出 `cn.disasterguard.*` import 数量与分布，用于排查循环依赖。 |
| `check-sql-bom.ps1`               | 检查 SQL/配置文件的 UTF-8 BOM。                                  |
| `strip-bom.ps1`                   | 移除 Java 源文件 BOM。                                          |
| `verify-bom.ps1`                  | 验证 BOM 已清除。                                                |
| `diagnose-schema-drift.ps1`       | 对照 `schema.sql` 期望列表，差分 `ai_comprehensive_score` 当前列。 |

## fix/ — 一次性修复

| 脚本                          | 说明                                                                                                              |
|-------------------------------|-------------------------------------------------------------------------------------------------------------------|
| `oneclick-fix.mjs`            | 把 `src/**/*.js` 中的 `==`/`!=` 升 `===`/`!==`（保留 `== null`/`!= null`），并清理意外 `!!=` 残留。一次性使用。   |
| `fix-db-volume.ps1`           | 重建 `disasterguard-postgres` 数据卷，重新跑 init 脚本。**会清空所有数据**。                                      |
| `reinit-postgres.ps1`         | 在容器不停的前提下重置角色/密码到 `disasterguard/disasterguard123`，不丢表。                                       |

## env/ — 环境与探针

| 脚本                | 说明                                                                       |
|---------------------|----------------------------------------------------------------------------|
| `check-env.ps1`     | 抽查关键环境变量是否设置（如 `JAVA_HOME`、`PATH` 中的 maven / node）。    |
| `check-ports.ps1`   | 查看 15432/16379/19000 等关键端口占用。                                    |
| `check-procs.ps1`   | docker / postgres / redis 进程 + 端口速查。                                |
| `health-check.ps1`  | 整体健康：拉一次 `/api/system/health`。                                     |
| `vite-check.ps1`    | 检查前端 dev server 启动是否成功（端口 + 首页 HTTP 200）。                 |
| `wait-docker.ps1`   | 在 `docker compose up` 之后等待 `postgres` 容器进入 healthy 状态。          |

## 已删除

整理过程清理掉的脚本，**不再使用**：

- `debug2.py` / `debug_dev.py` / `debug_paths.py` / `debug_users.py` / `dump_norm.py` / `find_dev.py` / `test2.py` / `test_find.py` — 临时调试一次性脚本
- `path_audit.py` / `path_audit2.py` / `gap_scan.py` / `empty_scan.py` — 中间迭代的审计脚本，已被 `audit/orphan_endpoint_scan.py` 取代
- `*.txt` / `*.log` — 临时输出文件
- `probe-pg4.sh` ~ `probe-pg10.sh` — postgres 鉴权事故的临时探针（已闭环）
- `probe-pg-default.ps1` / `list-tables2.ps1` — 与已有脚本重复
- `check-geosentry-refs.ps1` — `cn.geosentry → cn.disasterguard` 包名迁移已完成

### 2026-11-26 清理（40 个，事实依据：全仓零引用 + 对应问题已闭环）

- **主题/配色迁移一次性脚本**（2026-10，配色迁移已完成）：
  `fix/de-tealize.py`、`fix/greenify-styles.py`、`fix/greenify-theme.py`、`fix/greenify-welcome.py`、
  `fix/morandi-downgrade.py`、`fix/probe-theme.cjs`、`migration/ios-color-migration.ps1`、
  `migration/ios-color-migration-phase2.ps1`、`frontend/scripts/fix-contrast-colors.mjs`
- **Postgres 事故临时探针**（2026-09~10，鉴权/建表事故已闭环）：
  `db/probe-pg.ps1`、`db/probe-pg2.ps1`、`db/probe-pg3.ps1`、`db/probe-pg-state.ps1`、
  `db/probe-pg-tables.ps1`、`db/probe-stmt15.ps1`、`db/verify-pg.ps1`、`db/list-tables.ps1`
- **一次性 schema/数据修复**：`fix/cleanup-hazard.ps1`、`fix/cleanup-hazard2.ps1`、
  `fix/drop-partial.ps1`、`fix/drop-partial2.ps1`
- **一次性代码批量修复**：`fix/add-controller-preauthorize.mjs`、`fix/add-missing-controller-auth.mjs`、
  `fix/fix-dashboardapi-encoding.mjs`、`fix/batch-replace-confirm.mjs`、`audit/svc_imports.py`
- **一次性静态语法检查**（针对已修复问题，后续由 CI 与 ESLint 覆盖）：
  `audit/check-remaining-imports.ps1`、`audit/check-trailing-comma.ps1`、
  `audit/check-trailing-comma-multi.ps1`、`audit/find-concat.ps1`、`audit/find-doublequote.ps1`、
  `audit/check-data-state.ps1`、`audit/check-hazard-point.ps1`
- **早期前端探针**（2026-10，端点一致性已由 CI 的 `check-frontend-endpoints.mjs` 覆盖）：
  `audit/check-frontend-imports.mjs`、`audit/patrol-all-routes.mjs`、`audit/probe-hnav-responsive.mjs`、
  `audit/probe-hnav-tabs.mjs`、`audit/shot-hnav.mjs`、`frontend/scripts/check-layout.mjs`
- **冗余环境探针**：`env/check-procs2.ps1`（已被 `check-procs.ps1` 覆盖）

> 保留判据：凡被 CI（`.github/workflows/ci.yml`）、`frontend/package.json`、源码或现行文档引用的脚本一律保留，
> 例如 `audit/check-broken-imports.mjs`、`audit/check-missing-imports.mjs`、`audit/check-endpoint-parity.mjs`、
> `audit/check-frontend-endpoints.mjs`、`audit/mvn.sh`、`fix/sync-permission-registry.mjs`、`fix/free-8001.bat`。

## 索引更新

`audit/` 新增的前端探针类脚本（2026-10）：

| 脚本 | 说明 |
|---|---|
| `check-frontend-endpoints.mjs` | 校验前端服务调用与后端 Controller 的端点一致性。 |
| `check-endpoint-parity.mjs` | 同步比对多端点版本（前端 / 后端 / API 网关）。 |
| `check-missing-imports.mjs` | 列出 ESLint 跳过但可能缺失的依赖。 |

`fix/` 新增的一次性修复（2026-09 ~ 2026-11）：

| 脚本 | 说明 |
|---|---|
| `check-broken-imports.mjs` | 列出已删除模块仍被引用的位置。 |
| `sync-permission-registry.mjs` | 同步扫描结果到 `PermissionRegistry.java` 的常量定义。 |

`fix/free-8001.bat` 在 Windows 下释放 AI 服务 8001 端口（`taskkill` 包装）。

## 与 frontend/scripts/ 的分工

`scripts/audit/`、`scripts/fix/` 关注**后端 / 全栈**；前端**样式 token / SFC 完整性**等专用脚本在 `frontend/scripts/`：

| 路径 | 关注点 |
|---|---|
| `frontend/scripts/tokenize-colors.mjs` | 把硬编码颜色批量替换为 token 引用。 |
| `frontend/scripts/tokenize-radii.mjs` | 圆角 / 间距 token 化。 |
| `frontend/scripts/verify-token-integrity.mjs` | 检查 token 引用是否存在循环 / 死链。 |
| `frontend/scripts/repair-token-cycles.mjs` / `fix-token-cycles.mjs` | 修复 token 循环引用。 |
| `frontend/scripts/audit-contrast.mjs` | WCAG 对比度审计。 |
| `frontend/scripts/check-design-consistency.mjs` | 多页面组件风格一致性扫描。 |
| `frontend/scripts/verify-sfc-integrity.mjs` | 校验 SFC `<template>/<script>/<style>` 完整性。 |
| `frontend/scripts/audit/check-timer-leaks.mjs` | 扫描 `setInterval` / `setTimeout` 未清理。 |

`frontend/scripts/_shots/`、`frontend/scripts/audit/_shots/` 截图临时目录已加入 `.gitignore`，本仓库不再追踪。
