# 御灾智巡 · 项目文档

> DisasterGuard 开发者文档索引
> 最后更新：2026-09-16

本目录是面向开发者的核心文档集合，按"了解系统 → 开发 → 运维 → 接口"的阅读路径组织。

---

## 📚 文档清单

| 文档 | 用途 | 何时读 |
|------|------|--------|
| [ARCHITECTURE.md](./ARCHITECTURE.md) | 系统分层、数据流、模块依赖、关键时序图 | **必读** — 第一次接触项目时 |
| [DEVELOPER.md](./DEVELOPER.md) | 开发环境、技术栈、API、数据库、权限、部署、二次开发 | **必读** — 准备动手改代码前 |
| [FRONTEND_API.md](./FRONTEND_API.md) | 前端功能 ↔ 后端 REST 接口对照表 | 写前端或调试接口时 |
| [UNIFIED_EVENT_STATEMACHINE.md](./UNIFIED_EVENT_STATEMACHINE.md) | 统一事件中心 7 状态 × 12 转移的详细规则 | 改 hazard/warning/emergency 业务时 |
| [CDSE_SETUP.md](./CDSE_SETUP.md) | Copernicus Data Space OAuth 接入 + STAC fallback 链路 | 第一次部署卫星合成功能时 |

---

## 🗂️ 阅读顺序建议

### 🆕 新人入职
1. [ARCHITECTURE.md](./ARCHITECTURE.md) — 30 分钟建立全局视图
2. [DEVELOPER.md](./DEVELOPER.md) §1–§6（简介、技术栈、目录、环境、前后端开发指南）

### 🔧 改前端代码
1. [DEVELOPER.md](./DEVELOPER.md) §5（前端开发指南）
2. [FRONTEND_API.md](./FRONTEND_API.md) — 找对应功能的接口路径

### 🔧 改后端代码
1. [DEVELOPER.md](./DEVELOPER.md) §6（后端开发指南）+ §7（API 参考）+ §8（数据库设计）
2. [UNIFIED_EVENT_STATEMACHINE.md](./UNIFIED_EVENT_STATEMACHINE.md) — 若涉及 hazard/warning/emergency 业务

### 🚀 部署 / 运维
1. [DEVELOPER.md](./DEVELOPER.md) §11–§12（环境变量、Docker 部署）
2. [CDSE_SETUP.md](./CDSE_SETUP.md) — 若启用卫星合成功能

### 🤖 接 AI 模型
1. [DEVELOPER.md](./DEVELOPER.md) §16–§26（卫星、AI 引擎、多源数据接入）
2. [CDSE_SETUP.md](./CDSE_SETUP.md) — Sentinel 影像接入

---

## 🧭 文档维护原则

1. **单一可信源**：业务逻辑以代码为准，文档是"导览图"；任何代码改动必须同步更新相关章节
2. **保持精简**：超过 300 行的专题文档拆分子章节；超过 5KB 的截图改用文字描述
3. **删除过期**：版本变更后，旧版本特性章节要么合并到新章节，要么删除（不要保留"v1.5.0 旧接口兼容说明"等历史包袱）
4. **日期标注**：每个文档顶部保留"最后更新：YYYY-MM-DD"，随每次改动更新

---

## 🗑️ 已清理的历史文档

| 文档 | 处理方式 | 原因 |
|------|---------|------|
| `API_GAP_SUMMARY.md` | **删除** | 由 `scripts/audit/orphan_endpoint_scan.py` 自动生成，重跑脚本即可恢复 |
| `archive/STRUCTURE_OPTIMIZATION_PLAN.md` | **删除** | 重构方案已完成（Step 1–Step 10 全部落地），无需保留草案 |

如果需要重新生成接口对齐快照：

```bash
python scripts/audit/orphan_endpoint_scan.py > scripts/audit/orphan-now.txt
```
