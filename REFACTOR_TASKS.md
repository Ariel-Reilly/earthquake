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

### [ ] P1-1 ai-service 训练脚本合并到 scripts/training/

现状（ai-service/scripts/ 根下）：
- train_comprehensive_xgboost.py
- train_comprehensive_xgboost_v2.py
- train_xgboost_fusion_v2.py
- train_xgboost_fusion_v3.py

目标结构：
ai-service/scripts/training/
├── __init__.py
├── README.md
├── train_comprehensive_v1.py
├── train_comprehensive_v2.py
├── train_fusion_v2.py
└── train_fusion_v3.py （当前生产）

执行前必做：
1. 用 git log 确认版本先后
2. 搜索谁引用这些脚本
3. 输出引用点清单，等确认

### [ ] P1-2 backend 散落 DTO 移动到 dto 子包

目标：
- auth/*.java 中 DTO → auth/dto/
- notification/*.java 中 DTO → notification/dto/
- dashboard/dao/ZoneDashboardDto.java → dashboard/dto/

每文件必须：
1. 改 package 声明
2. 改所有 import
3. 检查 MyBatis / Jackson 扫描配置

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

## P2 - 迭代优化（暂缓）

- [ ] P2-1 frontend 重组散落组件
- [ ] P2-2 backend 统一 scheduler 组织
- [ ] P2-3 frontend 拆分 system 视图
- [ ] P2-4 补测试覆盖率
- [ ] P2-5 清理 CSS rgba 注释噪音
- [ ] P2-6 修复 `frontend-optimization-plan.md` 中 6 处 `computer://` 坏链接
---

## 执行记录

| 日期 | 任务 | 提交 | 结果 |
|------|------|------|------|
|      |      |      |      |
