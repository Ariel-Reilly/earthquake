# 2026-10-19 审计发现清单

## 概览

| 问题类型 | 数量 | 严重度 | 状态 |
|---------|------|--------|------|
| 孤儿接口（后端声明但前端未用） | 5 | 中 | 待处理 |
| HTTP 方法不匹配 | 2 | 中 | 待处理 |
| 已知缺口（基线登记） | 2 | 低 | 已登记 |

**汇总**：7 条问题，其中 5 条需要修复，2 条是历史已知。

---

## 一、孤儿接口扫描结果（前端调用不存在 / 方法不匹配）

### 疑似孤儿接口（5 个）

**说明**：这些接口在前端代码中被引用（`dashboardApi.js` 等），但后端 @RestController 中找不到对应实现。

| HTTP 方法 | API 路径 | 前端引用位置 | 优先级 | 建议 |
|----------|---------|-----------|-------|------|
| POST | `/api/emergency/{id}/dispatch` | `dashboardApi.js` | P1 | 检查后端是否遗漏实现，或改为注释掉前端调用 |
| POST | `/api/notice-templates/{id}/test` | `dashboardApi.js` | P1 | 同上 |
| POST | `/api/plans/{id}/launch` | `dashboardApi.js` | P1 | 同上 |
| POST | `/api/users/{id}/reset-password` | `dashboardApi.js` | P1 | 同上 |
| PUT | `/api/datasources/{id}` | `dashboardApi.js` | P1 | 同上 |

### HTTP 方法不匹配（2 个）

**说明**：前端用 POST，后端只有 GET；需协调。

| API 路径 | 前端用 | 后端有 | 前端引用位置 | 建议 |
|---------|------|--------|-----------|------|
| `/api/catalog/assets` | POST | GET | `dashboardApi.js` | 确认是否需要 POST，或改为 GET |
| `/api/datasources` | POST | GET | `dashboardApi.js` | 同上 |

---

## 二、已知缺口（基线登记，不阻断 CI）

| HTTP 方法 | API 路径 | 引用页面 | 说明 | 记录日期 |
|----------|---------|--------|------|---------|
| GET | `/api/ai/drift` | ModelManagePage | 漂移监控接口，后端未实现 | 2026-09-21 |
| GET | `/api/ai/multimodal` | AiInsightPage | 多模态分析接口，后端未实现 | 2026-09-21 |

**处理流程**：这两个接口修复后，应从 `scripts/audit/orphan_endpoint_scan.py` 的 `KNOWN_GAPS` 中移除。

---

## 三、后续行动清单

### 清单 A：修复孤儿接口（需确认优先级）

- [ ] **P3-2a** 检查 `EmergencyController` 是否缺 `POST /emergency/{id}/dispatch` 端点
- [ ] **P3-2b** 检查 `NotificationTemplateController` 是否缺 `POST /notice-templates/{id}/test` 端点
- [ ] **P3-2c** 检查 `PlanController` 是否缺 `POST /plans/{id}/launch` 端点
- [ ] **P3-2d** 检查 `UserController` 是否缺 `POST /users/{id}/reset-password` 端点
- [ ] **P3-2e** 检查 `DataSourceController` 是否缺 `PUT /datasources/{id}` 端点

### 清单 B：修复方法不匹配

- [ ] **P3-3a** 修复 `/api/catalog/assets`（POST vs GET）
- [ ] **P3-3b** 修复 `/api/datasources`（POST vs GET）

### 清单 C：扫描其他死代码

- [ ] **P3-4** 运行 `.\scripts\audit\run-audit-suite.ps1` 找 TODO/FIXME/console.log
- [ ] **P3-5** 运行 `npm run lint` 检查前端未使用变量
- [ ] **P3-6** 运行 `.\scripts\audit\run-route-patrol.ps1` 检查路由错误（需启动 dev server）

---

## 四、执行规范

按 `REFACTOR_TASKS.md` 规范执行每条修复：

1. **收到任务前输出执行计划**：文件清单 + 修改方式 + 影响面 + 验证命令
2. **每个修复单独 PR/commit**
3. **修改后全局 grep 旧路径**（确认零残留）
4. **验证**：`npm run build` + `mvn compile`

---

## 附录：扫描工具说明

| 脚本 | 用途 | 需要服务 |
|------|------|---------|
| `check-endpoint-parity.mjs` | 前端 ↔ 后端 API 对账（已跑） | ❌ |
| `patrol-all-routes.mjs` | 路由真实访问巡检 | ✅ 需 dev server |
| `check-frontend-imports.mjs` | import 完整性 | ❌ |
| `orphan_endpoint_scan.py` | 孤儿接口扫描（备选） | ❌ |
| `empty_feats.py` | 空功能检测 | ❌ |

**快速运行所有无服务依赖的脚本**：
```powershell
.\scripts\audit\run-audit-suite.ps1
```
