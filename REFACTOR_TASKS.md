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

### [x] P1-3 frontend services 薄层清理 → ❌ **已取消（2026-10-14）**

**只读验证结果**：

**薄层统计**：9 个薄层文件（50-68 B），转发到 `_architecture/domain/` 或 `_architecture/core/`

**引用面**：
| 类型 | 数量 |
|------|------|
| 通过薄层 import（`@/services/xxx`） | 33 处 |
| 直接 import `_architecture/` 的外部文件 | 6 个 |

**6 个直接引用文件的真相**：
| 文件 | 引用目标 | 有薄层？ |
|------|---------|---------|
| `ChannelMatrix.vue` | `_architecture/domain/notificationService.js` | ❌ |
| `DeliveryReceipt.vue` | 同上 | ❌ |
| `ServiceHealthBanner.vue` | `_architecture/core/healthService.js` | ❌ |
| `useOverviewSearch.js` | `_architecture/core/geocodeService.js` | ❌ |
| `DashboardLayout.vue` | `_architecture/core/healthService` | ❌ |
| `SocialMediaPage.vue` | `_architecture/domain/aiApi` | ✅ 有 |

**结论**：5 个文件引用的服务**从未建过薄层**，不是"绕过"；只有 1 个真正绕路。

**取消原因**：
1. 薄层在起作用（33 vs 6，薄层是主要入口）
2. `realtimeService.js` 有注释明示"刻意保留兼容性"
3. 删薄层后路径变长（`@/services/_architecture/domain/aiApi`），降低可读性
4. "反向整理"只有 1 个文件可改，收益 < 成本
5. `_architecture/` 重命名影响 17 个文件，属于 P2 范畴

**替代方案**：
- 新增 `frontend/src/services/README.md` 说明架构分层
- 未来新增服务时决定是否建薄层

**新增 P2 任务**：
- P2-11：评估是否为 `notificationService`、`healthService`、`geocodeService` 建薄层
- P2-12：`SocialMediaPage.vue` 的 `aiApi` 引用改为走薄层（微调）


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

---


### [x] P2-6 修 computer:// 坏链接 → ✅ **已完成（2026-10-15）**

**执行结果**：7 处 `computer://` 绝对路径改为同目录相对路径。

**修改文件**：`docs/audit/frontend/frontend-optimization-plan.md`

**替换映射**：
| 原链接 | 改为 |
|--------|------|
| `computer://.../frontend-comprehensive-audit-report.md` | `./comprehensive-audit-report.md` |
| `computer://.../docs/界面合并优化方案.md` | `./ui-merge-plan.md` |
| `computer://.../frontend/性能审查报告.md` | `./performance-audit-report.md` |
| `computer://.../frontend-architecture-review.md` | `./architecture-review.md` |
| `computer://.../frontend/UX_Audit_Report.md` | `./UX_Audit_Report.md` |

**验证**：`Select-String -Pattern "computer://"` 无残留。

---

### [x] P2-7 删死 fallback → ✅ **已完成（2026-10-15）**

**执行结果**：`ai-service/app/config.py` 删除 `_REPO_MODELS` 相关逻辑。

**删除内容**：
- 第 11 行注释（兼容旧版回退说明）
- 第 18 行注释尾（缺失时回退）
- `_REPO_MODELS` 定义
- `elif _REPO_MODELS.exists()` 分支

**理由**：P1-5 删除根目录 `models/` 后，fallback 永不触发，是死代码。

**验证**：`Select-String -Pattern "_REPO_MODELS"` 无残留。

---

### [x] P2-12 SocialMediaPage 引用改薄层 → ✅ **已完成（2026-10-15）**

**执行结果**：`SocialMediaPage.vue:37` 的 import 改为走薄层。

**修改**：

    // 前
    import { aiApi } from "../../services/_architecture/domain/aiApi";
    // 后
    import { aiApi } from "../../services/aiApi";

**验证**：`npm run build` 成功。

---


### [x] P2-8 Scheduler 归位 → ✅ **部分完成（2026-10-14）**

**执行结果**：2 个 Scheduler 移动到 `scheduler/` 子包。

| 文件 | 从 | 到 |
|------|----|----|
| `AiForecastScheduler.java` | `ai/service/` | `ai/scheduler/` |
| `DeviceMetricCleanupScheduler.java` | `device/service/` | `device/scheduler/` |

**引用更新**：
- `AiAnalysisController.java`（import 改为 `.ai.scheduler`）
- `AiForecastScheduler.java` 自身补 `import cn.disasterguard.ai.service.AiAnalysisService;`

**保留未移动的 3 个**：
- `auth/jwt/JwtBlacklistCleanupScheduler.java` —— 与 `JwtBlacklist` 语义邻近
- `warning/engine/AiWarningScheduler.java` —— engine 是功能单元
- `warning/engine/RuleEngineScheduler.java` —— 同上

**全项目 `@Scheduled` 分布**（15 处）：
- 已在 `scheduler/`：5 个
- 配置类：1 个
- Service 内嵌：2 个（非独立 scheduler）
- Repository 内嵌：1 个（P2-10 处理）
- 本次移动：2 个
- 保留：3 个

**教训**：移动 Java 类时，**同包引用会断**。移前先扫引用同包其他类，
移动后补 import。本次 `AiForecastScheduler` 遗漏补
`import ...AiAnalysisService;` 导致首次编译失败。

### [x] P2-9 Properties 归位 → ✅ **已完成（2026-10-15）**

**执行结果**：2 个 Properties 移动到 `config/`。

| 文件 | 从 | 到 |
|------|----|----|
| `AiProperties.java` | `ai/` | `config/` |
| `JwtProperties.java` | `auth/jwt/` | `config/` |

**引用更新**：
- `PropertiesConfig.java`（删除 2 个旧 import，同包后不需要）
- `AiServiceClient.java`（补 `import cn.disasterguard.config.AiProperties;`）
- `JwtService.java`（补 `import cn.disasterguard.config.JwtProperties;`）

**归位理由**：`config/` 已有 4 个 Properties 类（`AppProperties`、
`ExternalDataProperties`、`MinioProperties`、`WeatherProperties`），
本次将剩余 2 个归位，实现 Properties 全统一。

**教训**：批量替换 import 时，**插入新行要匹配原文件的 EOL**。
PowerShell 硬编码 `\r\n` 在 LF 文件里造成混合行尾，本次已修正。
未来脚本应检测 EOL：`$eol = if ($raw.Contains("`r`n")) { "`r`n" } else { "`n" }`。

### [x] P2-10 WsTicketRepository 命名修正 → ✅ **已完成（2026-10-15）**

**执行结果**：`WsTicketRepository` → `WsTicketStore`

**理由**：该类不是 Spring Data Repository（无 `@Repository`、无接口继承），
而是内存票据存储。原命名误导。`@Scheduled` 内嵌合理（资源生命周期管理），
**不抽取 Scheduler**（抽了要暴露 public 方法，弱化封装）。

**改动范围**：
- `WsTicketRepository.java` → `WsTicketStore.java`（类名同步）
- 3 个引用者改 import：`AuthController`、`JwtHandshakeInterceptor`、`WebSocketConfig`

**未做**：抽取独立 Scheduler —— 参考 P0-2/P2-8 经验，此改动收益低、成本高。

---

### [x] P2-11 评估为 5 个无薄层服务建薄层 → ❌ **已取消（2026-10-15）**

**只读验证结果**：

| 服务 | 引用数 | 引用形式 |
|------|-------|---------|
| `notificationService` | 4（3+1） | 动态 `await import()` |
| `healthService` | 2（1+1） | 静态 import |
| `geocodeService` | 1 | 静态 import |

**总计 7 处引用**，对比现有薄层 33 处引用，差距显著。

**取消原因**：
1. 引用面太小，建薄层收益微弱
2. `_architecture/` 深路径是**有意设计**——`_architecture` 前缀表示"内部实现"
3. `notificationService` 使用动态 import，薄层路径长度无差别
4. 与 P1-3 决策一致（薄层只在多引用时才有价值）

**结论**：现有 6 处深路径引用**合理保留**。未来如 `notificationService`
被更多组件引用，可重新评估建薄层。

### [x] P2-13 dashboard.py 路径 bug 修复 → ✅ **已完成（2026-10-15）**

**背景**：`ai-service/app/routers/dashboard.py:346` 的 `models_dir` 计算有 bug：

```python
# 修复前
models_dir = fusion.model_path.parent.parent if fusion.model_path else _Path("./models")


### [x] P2-14 CRLF 规范化 → ❌ **已取消（2026-10-15）**

**只读验证结果**：
- 297 个文件索引中是 CRLF
- `git add --renormalize .` 会改动 **323 个文件**
- **42396 行增删**

**取消原因**：

1. **`git blame` 全废** —— 未来追任何代码都指向本次，而非原始提交
2. **Code review 不可能** —— 323 个文件的 diff 无法审核
3. **协作者冲突地狱** —— 所有已拉取的分支合并时全部冲突
4. **连二进制文件都被误伤** —— 8 个 `.ifc` 被改动
5. **问题已被 `.gitattributes` 缓解** —— `* text=auto` 管新文件，存量重写收益低
6. 与 P0-1/P0-2/P1-3/P2-11 同一模式

**副产物**：验证过程中发现 19 个 `scripts/*.ps1` 工作区/索引行尾不一致，
已通过 `git checkout -- scripts/` 恢复（无 commit 必要）。

### [x] P2-15 `.gitattributes` 补全核心代码规则 → ✅ **已完成（2026-10-15）**

**执行结果**：为 12 种核心代码扩展名设置 `text eol=lf`。

**追加规则**：

    *.java   text eol=lf
    *.js     text eol=lf
    *.mjs    text eol=lf
    *.cjs    text eol=lf
    *.vue    text eol=lf
    *.py     text eol=lf
    *.ts     text eol=lf
    *.json   text eol=lf
    *.yml    text eol=lf
    *.yaml   text eol=lf
    *.md     text eol=lf
    *.sql    text eol=lf

**效果**：
- 未来新增文件自动以 LF 入库
- 存量文件**不重写**（避免 P2-14 的 323 文件 diff）
- 与 `.gitattributes` 现有规则（`*.sh text eol=lf`、`*.ps1 text eol=crlf`）配合

**验证**：`git diff .gitattributes` 仅显示追加，无其他改动。

## 执行记录

| 日期 | 任务 | 提交 | 结果 |
|------|------|------|------|
|      |      |      |      |

- [ ] P2-16 在 README 顶部加 ONBOARDING 入口指引（README 已 renormalize 为 LF，可随时做）
