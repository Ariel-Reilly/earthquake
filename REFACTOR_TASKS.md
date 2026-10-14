# 重构任务清单

> 基线分支：refactor/2026-10-14
> 规则：@.cursor/rules/refactor.mdc
> 计划：@docs/refactor-plan.md
> 执行原则：一次一个任务 → 先输出计划 → 等确认 → 执行 → 自检 → 停下

---

## P0 - 立即执行

### [x] P0-1 删除 ai-service 重复测试文件 → ❌ **已取消（2026-10-14）**

**验证结果**：两文件**非重复**，用途互补。

| 文件 | 框架 | 需要服务 | 用途 | 大小 |
|------|------|---------|------|------|
| `tests/test_api_endpoints.py` | pytest + TestClient | 否 | CI 单元测试（17 类） | 225 行 |
| `scripts/test_api_endpoints.py` | urllib | 是 | 现场诊断，被 `ai.py api` 调用 | 80 行 |

**结论**：两者均保留。原"重复"判断错误。

---

### [x] P0-2 ai-service/scripts/ 测试文件归位 → ❌ **已取消（2026-10-14）**

**验证结果**：5 个 `test_*.py` **全部** `from _bootstrap import setup_path`。
移动到 `scripts/testing/` 会导致 import 失效，必须在每个文件补 sys.path 修正，
**代价大于收益**。

**替代方案**：保持物理位置不变，已新建 `ai-service/scripts/README.md`，
在其中说明 5 个文件的用途和与 `tests/` 的区别。

**教训**：文件"看起来可以整理" ≠ "值得整理"。移动前必须验证
运行依赖（`_bootstrap` / `sys.path` / 相对路径），移动成本高于组织收益时
应保留现状。

## P1 - 本周内完成

### [x] P1-1 ai-service 训练脚本合并 → ✅ **已完成（2026-10-14）**

**执行结果**：**8 个训练脚本**移动到 `ai-service/scripts/training/`。

**移动清单**：
| 原路径 | 新路径 |
|--------|--------|
| `scripts/train_comprehensive_xgboost.py` | `scripts/training/train_comprehensive_v1.py` |
| `scripts/train_comprehensive_xgboost_v2.py` | `scripts/training/train_comprehensive_v2.py` |
| `scripts/train_xgboost_fusion.py` | `scripts/training/train_fusion_v1.py` |
| `scripts/train_xgboost_fusion_v2.py` | `scripts/training/train_fusion_v2.py` |
| `scripts/train_xgboost_fusion_v3.py` | `scripts/training/train_fusion_v3.py` |
| `scripts/train_crack_yolo.py` | `scripts/training/train_crack_yolo.py` |
| `scripts/train_isolation_forest.py` | `scripts/training/train_isolation_forest.py` |
| `scripts/train_lstm_displacement.py` | `scripts/training/train_lstm_displacement.py` |

**附带修复**（5 处 default 路径 bug）：
- `train_comprehensive_v1.py`（`parent.parent` → `parent.parent.parent`）
- `train_isolation_forest.py`（同上）
- `train_lstm_displacement.py`（同上）
- `train_fusion_v1.py`（同上）
- `train_crack_yolo.py`（`--models-dir`，同上）

**路径 bug 说明**：原 `parent.parent` 在 `scripts/` 根时正确指向 `ai-service/models/`，
移动后会自动指向 `scripts/models/`。修正为 3 层 parent 后，
无论脚本位于 `scripts/` 还是 `scripts/training/` 都能正确输出到 `ai-service/models/`。

**引用更新**：
- `ai-service/README.md:217,220`
- `ai-service/app/models/comprehensive_model.py:648`（注释）
- `ai-service/app/models/xgboost_model.py:93`（注释）
- `ai-service/models/README.md`（8 处 + CRLF 修复）
- `ai-service/scripts/README.md`（分类表格）

**新增文档**：
- `ai-service/scripts/training/README.md`（含 8 个脚本版本一览表）

**遗留问题**（转 P1-5）：
- 根目录 `models/` 有历史堆积（v2/v3 路径 bug 产物），需单独清理

**补充说明**：`ai-service/app/training/` 目录（含 `train_xgboost.py` + `synthetic_data.py`）
经 P1-5 验证是**应用内训练模块**，被 `/api/ai/model/retrain` API 调用，
依赖 `app.models.*`，与独立脚本性质不同。**不移动**。
原分析中"训练代码不应在业务代码目录"的判断针对的是 `scripts/` 下的独立脚本，
已由本任务处理。

### [x] P1-2 backend DTO 归位 → ✅ **已完成（2026-10-14）**

**执行结果**：2 个子任务完成，3 个子任务跳过（已规范）。

**P1-2a：auth DTO 移动** ✅（commit aba5d25）
6 个 DTO 移动到 `auth/dto/`：
- `LoginRequest.java`
- `LoginResponse.java`
- `ChangePasswordRequest.java`
- `ChangePasswordResponse.java`
- `UserProfileDto.java`
- `NotificationPreferenceDto.java`

引用更新：
- `AuthController.java`（+4 imports）
- `UserProfileController.java`（+2 imports）
- `UserProfileService.java`（同包引用转跨包）

**P1-2b：dashboard DTO 移动** ✅（commit cc61d61）
- `dashboard/dao/ZoneDashboardDto.java` → `dashboard/dto/ZoneDashboardDto.java`
- `dao/` 目录已删除（空）
- `ZoneDashboardController.java`（5 个 import 更新）
- `ZoneDashboardService.java`（5 个 import 更新）

**P1-2c/d/e：跳过** ✅
- `notification/dto/`：已规范（7 个 DTO 已在 dto/ 子包）
- `ai/dto/`、`ai/scheduler/`：已规范
- `event/controller/dto/`：已规范

**原计划中未执行的项**（单开 P1-2-续任务）：
- scheduler 统一归位（`AiForecastScheduler` 已在 `ai/scheduler/`，`JwtBlacklistCleanupScheduler` 在 `auth/jwt/`）
- Properties 统一到 `config/`（`AiProperties`、`JwtProperties`）
- `WsTicketRepository` 的 `@Scheduled` 抽取

**执行方式**：本轮使用**手动 PowerShell 脚本**（非 Cursor Agent）。
因 Cursor Agent 在 P1-2 第一次执行时偏离指令（移错文件 + 报告失实），
已 `git restore` 完全回滚后改用手动脚本。脚本化流程更可控，
将作为后续同类任务的默认方式。

### [ ] P1-3 frontend services 薄层 re-export 清理

目标：
1. 删除 services/ 根下薄层 re-export 文件
2. 调用方改为引用 services/_architecture/domain/xxx.js
3. 只做这一步，_architecture/ 重命名单独任务

警告：必须单独 Cursor 会话，先 git checkout -b refactor/p1-3-frontend-services

### [x] P1-4 docs 归档整理 → ✅ **已完成（2026-10-14）**

**执行结果**：11 个文件移动，4 处引用同步更新。

**移动清单**：
| 原路径 | 新路径 |
|--------|--------|
| `docs/前端全面优化方案.md` | `docs/audit/frontend/frontend-optimization-plan.md` |
| `docs/前端功能真实性核查报告_20261003.md` | `docs/audit/frontend/feature-reality-check-20261003.md` |
| `docs/界面合并优化方案.md` | `docs/audit/frontend/ui-merge-plan.md` |
| `docs/页面合并可行性分析报告.md` | `docs/audit/frontend/page-merge-analysis.md` |
| `docs/audit/frontend/性能审查报告.md` | `docs/audit/frontend/performance-audit-report.md` |
| `docs/CODE_REVIEW.md` | `docs/reviews/CODE_REVIEW.md` |
| `docs/CODE_REVIEW_R2.md` | `docs/reviews/CODE_REVIEW_R2.md` |
| `docs/CODE_REVIEW_R3.md` | `docs/reviews/CODE_REVIEW_R3.md` |
| `docs/CODE_REVIEW_R4.md` | `docs/reviews/CODE_REVIEW_R4.md` |
| `docs/CODE_REVIEW_R5.md` | `docs/reviews/CODE_REVIEW_R5.md` |
| `docs/AI_MODULE_IMPROVEMENT_20260929.md` | `docs/guides/AI_MODULE_IMPROVEMENT_20260929.md` |

**引用更新**：
- `backend/src/main/resources/application-prod.yml:30`
- `ai-service/README.md:251-252`
- `CHANGELOG.md:55`

**遗留问题**（转 P2-6）：
- `docs/audit/frontend/frontend-optimization-plan.md` 第 99/544 行有 6 处
  `computer://` 绝对路径链接，历史遗留，本次未修
---

### [x] P1-5 清理根目录 models/ 堆积 → ✅ **已完成（2026-10-14）**

**执行结果**：
- **P1-5a**：删除 6 个 `*.backup.*.json` 文件（v2/v3 路径 bug 的历史堆积）
- **P1-5c**：删除整个根目录 `models/`（与 `ai-service/models/` 内容 100% 一致）

**验证过程**：
1. 9 个共享文件 md5 全部一致
2. `config.py` 的 fallback（`_REPO_MODELS`）在正常部署下**永不触发**
3. `docker-compose` / `Dockerfile` / CI 均不引用根目录 `models/`
4. 无任何代码引用根目录 `models/`

**注**：原报告称 `app/training/train_xgboost.py:48` 硬编码引用根目录 `models/`，
**经查证为误判**。该文件 `REPO_ROOT = SCRIPT_DIR.parent.parent` 指向 `ai-service/`，
实际引用 `ai-service/models/`，正确无误。

**遗留问题**（转 P2-7）：
- `ai-service/app/config.py` 中 `_REPO_MODELS` 的 fallback 分支已无实际用途，
  可清理（本次不动，避免改动代码逻辑）

## P2 - 迭代优化（暂缓）

- [ ] P2-1 frontend 重组散落组件
- [ ] P2-2 backend 统一 scheduler 组织
- [ ] P2-3 frontend 拆分 system 视图
- [ ] P2-4 补测试覆盖率
- [ ] P2-5 清理 CSS rgba 注释噪音
- [ ] P2-6 修复 `frontend-optimization-plan.md` 中 6 处 `computer://` 坏链接
- [ ] P2-7 清理 `ai-service/app/config.py` 中无用的 `_REPO_MODELS` fallback 分支
- [ ] P2-8 scheduler 归位（`JwtBlacklistCleanupScheduler` 从 `auth/jwt/` 移到 `auth/scheduler/`）
- [ ] P2-9 Properties 归位（`AiProperties`、`JwtProperties` 移到 `config/`）
- [ ] P2-10 `WsTicketRepository` 的 `@Scheduled` 抽取
---

## 执行记录

| 日期 | 任务 | 提交 | 结果 |
|------|------|------|------|
|      |      |      |      |
