# 仓库盘点清单（INVENTORY）

> 基线：commit `47db7de` / tag `v1.8.11-delivery` / 2026-11-14
> 基线门禁（已在验收中确认）：vitest **101 passed** · `verify-retest-fixes` **57/57** · 浏览器冒烟 **37/37** · eslint **0** · stylelint **0**
> 性质：**只读盘点，不含代码改动**。数字为实测，与「项目整理方案」里的估算有出入处以本文为准（见 §10）。

---

## 1. 测量口径（可复现）

- 行数用 `wc -l`；**排除** `node_modules/`、`.venv/`、`dist/`、`target/`、`__pycache__/`。
- 前端统计 `frontend/src` 下 `.vue/.js/.css`；后端统计 `backend` 下 `.java`（**含测试源**）；AI 统计 `ai-service` 下 `.py`（排除虚拟环境）。
- 「大文件」阈值：前端 SFC/JS/CSS **≥ 1200 行**、后端 Java **≥ 400 行**、AI Python **≥ 1000 行**。
- **待办统计口径**：`TODO`/`FIXME` 用**大小写敏感** `\bTODO\b` / `\bFIXME\b`（避免 `todo:view`、`toDouble` 误报）；后端**含测试源**；`@Deprecated` **单独统计**、不计入 TODO。复跑以 `scripts/quality/check-comments.mjs` 为准。

复现命令示例：

```bash
find frontend/src backend ai-service -type f \( -name '*.vue' -o -name '*.js' -o -name '*.java' -o -name '*.css' -o -name '*.py' \) \
  -not -path '*/node_modules/*' -not -path '*/.venv/*' -not -path '*/dist/*' -not -path '*/target/*' -not -path '*/__pycache__/*' \
  -exec wc -l {} + | grep -v ' total$' | sort -rn | head -40
```

---

## 2. 规模实测

| 范围 | 文件数 | 行数 |
|---|---|---|
| 前端 `frontend/src`（vue/js/css） | **153** | **101,283** |
| 后端 `backend`（java，含测试） | **363** | **41,702** |
| AI `ai-service`（py，排除 venv） | **80** | **21,427** |
| 交付文档 `docs/`（md） | **61** | — |

---

## 3. 大文件清单（拆分的优先目标）

### 3.1 前端（≥ 1200 行）

| 文件 | 行数 | 备注 |
|---|---|---|
| `frontend/src/styles.css` | **9,420** | 全局样式单文件，拆分收益最大 |
| `views/WelcomePage.vue` | **5,933** | 营销页，可 section 拆、与业务解耦 |
| `views/OverviewPage.vue` | **3,203** | 总览，优先按 composable 拆 |
| `services/dashboardApi.js` | **2,407** | 需按域拆 API |
| `views/ai/ModelManagePage.vue` | **2,267** | 模型库/训练/漂移三 Tab |
| `views/hazard/HazardsListPage.vue` | **2,193** | 列表/筛选/详情/确认队列 |
| `views/screen/CommandScreenPage.vue` | **1,896** | 大屏 |
| `views/warning/WarningsPage.vue` | **1,712** | 列表/处置/统计 |
| `views/settings/AccountSecurityPage.vue` | **1,605** | |
| `views/warning/WarningDetailPage.vue` | **1,520** | 含处置时间线 |
| `views/monitor/DeviceMapPage.vue` | **1,464** | 本轮 marker 改造文件 |
| `views/data/DataCatalogPage.vue` | **1,460** | |
| `views/hazard/FieldInspectionPage.vue` | **1,443** | |
| `services/_architecture/core/mapService.js` | **1,405** | 地图聚类层 |
| `views/system/NotificationChannelsPage.vue` | **1,392** | |
| `views/ai/DamageAssessmentPage.vue` | **1,361** | |
| `components/WarningDeliveryTab.vue` | **1,305** | |
| `views/hazard/HazardDetailPage.vue` | **1,302** | |
| `views/device/OtaManagePage.vue` | **1,270** | |
| `views/hazard/ConfirmationWorkbenchPage.vue` | **1,261** | |
| `views/RegisterPage.vue` | **1,250** | |
| `components/ai/AiComprehensiveTab.vue` | **1,245** | |

### 3.2 后端（≥ 400 行）

| 文件 | 行数 | 拆分建议 |
|---|---|---|
| `ai/AiController.java` | **1,016** | 模型 / 分析 / 预测 / 训练 拆分 |
| `warning/service/WarningService.java` | **723** | 查询 / 生命周期 / 通知 |
| `ai/service/ExternalDataService.java` | **721** | 每源一个 Client + 统一降级 |
| `ruleengine/RuleEngineService.java` | **630** | 规则匹配 / 评估 |
| `ai/service/AiComprehensiveAnalysisSupport.java` | **569** | 支撑层 |
| `analytics/AnalyticsController.java` | **565** | Controller + QueryService（SQL 外移） |
| `ai/AiServiceClient.java` | **538** | 外部 AI 客户端 |
| `weather/service/WeatherService.java` | **512** | 外部源 |
| `confirmation/ConfirmationQueueController.java` | **505** | |
| `hazard/service/HazardService.java` | **487** | |
| `report/ReportController.java` | **467** | |
| `ai/service/AiInsightService.java` | **454** | |
| `notification/NoticeTemplateController.java` | **447** | |
| `governance/CatalogController.java` | **408** | |
| `config/ProductionConfigValidator.java` | **404** | |

### 3.3 AI（≥ 1000 行）

| 文件 | 行数 |
|---|---|
| `ai-service/web.py` | **2,414** |
| `ai-service/app/models/disaster_assessment_model.py` | **1,380** |
| `ai-service/app/routers/sentinel.py` | **1,351** |

---

## 4. 待办与弃用实测（**远低于方案估算**）

> 口径：大小写敏感 `\bTODO\b` / `\bFIXME\b`；后端含测试源；前端仅 `frontend/src`；AI 仅 `ai-service/app`（排除 `.venv`）；`@Deprecated` 单独统计、不计入 TODO。

| 标记 | 实测 | 说明 |
|---|---|---|
| `TODO` | 后端 **6** · 前端 **0** · AI **9** · 合计 **15** | 后端 6 已补占位 owner（§4.3）；AI 9 见 §4.1 allowlist |
| `裸标记`（无 owner/date/issue） | **0** | 门禁默认已转绿（2026-11-14） |
| `FIXME` | **0** | — |
| `@Deprecated` | **6** | 主要在 `AiDeprecatedGapController`（类 + 3 方法） |

### 4.1 AI 服务 9 处 TODO（待实现点，非「裸清理」）

| 文件 | 行 | 内容摘要 | 关联边界 |
|---|---|---|---|
| `ai-service/app/routers/drift.py` | 112 | 从数据库加载历史漂移记录 | L-01 |
| `ai-service/app/routers/drift.py` | 142 | 返回特征详细分布 | L-01 |
| `ai-service/app/routers/drift.py` | 216 | 实际触发重训任务 | L-01 |
| `ai-service/app/routers/drift.py` | 235 | 从任务队列获取状态 | L-01 |
| `ai-service/app/routers/multimodal.py` | 140 | 按 `event_id` 从融合结果库加载真实记录（接入后删 `_demo_meta()`） | L-01 |
| `ai-service/app/routers/multimodal.py` | 184 | 贡献度详情（当前演示数据） | L-01 |
| `ai-service/app/routers/multimodal.py` | 220 | 权重配置（当前演示数据） | L-01 |
| `ai-service/app/routers/sentinel.py` | 799 | 按真实 PNG 像素尺寸换算 | L-04 |
| `ai-service/app/routers/sentinel.py` | 1177 | 真实场景改用连通域分析 + 凸包 | L-04 |

> 结论：AI 这 9 处**不应按「裸 TODO 清理」处理**，而是待实现点，归入 `docs/delivery/KNOWN_LIMITATIONS.md` 的 **L-01**（drift/multimodal）与 **L-04**（卫星影像）。**后端 6 处已于 2026-11-14 补占位 owner（见 §4.3），当前裸标记 0。**

### 4.2 门禁脚本的豁免项与移除条件（方案 B）

`scripts/quality/check-comments.mjs` 对上述 **AI 9 处**做**显式 allowlist**（按 `文件 + 行内片段` 精确匹配，**非整文件豁免**），运行输出：

```text
命中 15 处；已带 owner 6 处；allowlist 豁免 9 处；裸标记 0 处
```

- **豁免范围**：仅 `drift.py`(4) / `multimodal.py`(3) / `sentinel.py`(2) 的这 9 行；这三个文件里**新增**的 TODO 仍会被判为裸标记。
- **为何不选方案 A**：其中 4 处是 docstring/prose（`multimodal.py:184/220`、`sentinel.py:799/1177`），强改 `TODO(owner,date,issue)` 会破坏可读性；且后端 6 处若也要求「严格模式通过」需臆造团队/工单，风险更大。
- **自检**：`--selfcheck` 逐条核对 allowlist 是否仍命中 1 行，失效/重复命中即报错并点名条目（已验证：人为改坏一条 → `8/9 命中` 并精确报出 `sentinel.py` 失效项）。**CI 使用 `--selfcheck`（严格：裸标记 + allowlist 自检）；过渡期才用 `--warn --selfcheck`。** 并配套 `check-comments.selftest.mjs` 回归（13/13：文件缺失 / 片段重复 / 干净 fixture / 清零后严格模式 / 真实仓库 / 过期与未来日期）。
- **移除条件**：阶段 2 为这 9 处补 `TODO(ai-team, 日期, #L-01/#L-04)` 后，**删除对应 allowlist 条目**；届时全部 TODO 均为「已带 owner」，裸标记维持 0。

### 4.3 后端 6 处占位 owner（阶段 1 首项，2026-11-14）

以下 6 处已从裸 TODO 改为 `TODO(backend-team, 2026-12-31, #DELIVERY-C9)`（锚定 `DELIVERY_BACKLOG C9`），**仅注释、零逻辑改动**；`backend-team / 2026-12-31` 为**占位值**（团队级、非具体个人），工单已锚定 C9，待正式负责人确认后仅替换 owner。

| 文件 | 行 | 内容 |
|---|---|---|
| `notification/channel/AppSender.java` | 35 | 集成真实推送服务（极光/个推/FCM） |
| `notification/channel/EmailSender.java` | 38 | 集成真实邮件服务（JavaMail/阿里云） |
| `notification/channel/SmsSender.java` | 39 | 集成真实短信网关（阿里云/腾讯云） |
| `notification/channel/SoundLightSender.java` | 38 | 集成真实声光报警器 API |
| `ruleengine/RuleEngineService.java` | 401 | 实现版本回滚逻辑 |
| `warning/service/WarningService.java` | 326 | 三镇硬编码兜底 → 注入 RegionRepository |

- 一键定位：`grep -rn "TODO(backend-team, 2026-12-31, #DELIVERY-C9)" backend/src`
- 替换条件：拿到真实负责人后仅替换 `backend-team`（保持 `#DELIVERY-C9` 指向台账 C9）；替换后重跑 `check-comments.mjs` 与 `selftest.mjs`。

> **边界（重要）：注释门禁绿 ≠ 功能已实现。** 这 6 项仍是真实能力缺口：4 个 Sender 仍 mock 发送（返回成功但未真实送达）、`RuleEngineService.rollback` 仍抛 `UnsupportedOperationException`、`WarningService.resolveScope` 仍为三镇硬编码。**功能完成率应看 `KNOWN_LIMITATIONS` / `DELIVERY_BACKLOG C9`，不能以「TODO 清零」判定功能完成。**

> ⚠ 方案里写的「TODO/FIXME/@Deprecated 211 处 / 52 文件」**不成立**（属宽匹配误报，如 `todo:view`、`toDouble`）。实测 `TODO` **15** + `@Deprecated` **6** = **21**。**任务 1「清裸 TODO」工作量很小（后端 6 处）；AI 9 处按 §4.2 豁免登记。**

---

## 5. 未实现 / 未接入 / 演示能力表（真实来源）

| 能力 | 位置 | 表现 | 是否冒充真实 |
|---|---|---|---|
| AI 漂移代理 `/api/ai/drift*` | `ai/AiDeprecatedGapController` | **HTTP 501** + `deprecated:true`（匿名得 403） | 否（明确弃用） |
| AI 多模态代理 `/api/ai/multimodal*` | 同上 | **HTTP 501** + `deprecated:true` | 否 |
| AI 训练任务（创建/状态/日志） | `ai/AiController`（约 842/854/866 行） | `ApiResponse.fail(501, ...)` | 否（说明无调度器） |
| 备份完整性校验 | `backup/BackupController:235` | 返回「尚未实现，记录为内存模拟」 | 否（明说） |
| 资产访问申请 | `governance/CatalogController:402` | 明确「尚未实现」 | 否 |
| 数据订阅推送 | `governance/CatalogController:385` | 「已登记，未接推送网关」 | 否 |
| 巡检报告（Analytics） | `analytics/AnalyticsController:118` | note=「演示数据」 | 否（标注演示） |
| 短信/邮件验证码网关 | `auth/register/.../VerificationCodeService`、`ProductionConfigValidator` | prod 下直接失败；非 prod 日志降级 | 否（刻意防「假装发送成功」） |
| APP 推送 | `notification/channel/AppSender:42` | `SendResult.mockSuccess(...)` | ⚠ 返回成功但未真实发送，**需确保 UI 不宣称已送达** |

> 建议：以上统一登记到 `docs/delivery/KNOWN_LIMITATIONS`（或一张总表），与前端「能力未接入」文案一一对应。**P2 项 `RelatedWarning.id` 见 §7.3。**

---

## 6. Mock 层现状

- 独立 mock 包：`frontend/src/services/mock/`（**9 个文件**：`ai / analytics / empty / index / operations / runtime / seed / system / workflow`）。
- 回退入口：`services/_architecture/core/httpClient.js`、`runtime.js`、`errors.js` 内做失败降级。
- 生产代码中引用 `mock/synthetic` 的 `.js/.vue` 文件 **40 个**（已排除 `__tests__`）；显式 `demo:true / isDemo / DEMO_MODE` 标记仅 **6 处**。
- 判定：属**显式 mock 基础设施**（seed/empty/runtime），非「静默造假」。但仍建议：所有回退结果必须带 `demo`/来源标识，并由页面横幅可见。

---

## 7. 契约不一致清单（任务 3 的真实范围）

### 7.1 分页三形态并存

| 形态 | 前端引用数 |
|---|---|
| `records` | **121** |
| `.items` | **83** |
| `.list` | **77** |

- `common/api/PageResponse{records,total,page,size,pages}` **已存在**（Javadoc 已注明历史上两个 record 已合一），并已在 admin application 分页使用。
- **任务 3 不是「新建 PageResponse」，而是「前端在 `api/client` 归一化一次 + 校验尚未遵循的端点 + 逐步消除页面层 `items/list` 兼容分支」。**
- **归一化落点已建**：`frontend/src/api/client.js`（`normalizePage / normalizeList`，纯函数 + 单测 **14** 例）；`dashboardApi.normalizePaged` **已改为委托 `normalizePage`**（已去重、行为等价）；页面层 `items/list` 兼容分支待阶段 1 小步清理。
- **已迁移页面/组件（9）**：`DataSyncPage.toList`；`DataCatalogPage` 三处（`loadAssets` / `loadUsage` / `openTrend`）；`PersonnelManagePage.loadPersonnel`；`WarningDeliveryTab.loadChannel`（5 渠道共用）；`OtaManagePage.toRecords`（三源共用）；`NoticeTemplatePage.load`；`TodoCenterTab.loadTodos`；`ModelManagePage`（`listModels` + `getModelTrainingJobs`）；`SettingsPage.loadSessions`。
  - 其中 **4 个是「API 原样透传 + 页面猜形态」**（NoticeTemplate / TodoCenter / ModelManage ×2 / Settings sessions），这类才是真实风险点。
- **顺带修复的真实缺陷（2）**：
  1. `PersonnelManagePage`：`data.list ?? data ?? []` 在 `{records}` 形态把对象当数组 `.map`、在 `null` 下 `data.list` 直接 TypeError，被 catch 成「人员列表加载失败」。
  2. `ModelManagePage`：模板 4 处 `<PermissionButton>`（行内「激活/下线」+ 抽屉「切换为当前激活模型」）**从未 import**，Vue 仅打 warn、渲染为空 —— 按钮在生产环境完全不可见。已补 import，并在组件测试中加「真实解析」断言防回归。
- **页面迁移的验收基线**：迁移必须带页面级回归，不接受“纯函数绿 + 页面未验证”；**一页多处取数须逐路径覆盖**。
  - `dataSyncPage.spec.js`（6 例）：records/items/list/裸数组/null/抛错；变异验证：`toList` 返回 `[]` → 4 例失败。
  - `dataCatalogPage.spec.js`（7 例）：`loadAssets`（records/裸数组/items/空）+ `loadUsage`（Tab）+ `openTrend`（二次拉取趋势）+ 抛错降级。
    变异验证分两步：整体换 `() => []` → 4 例失败；**只坏 `openTrend` 一处 → 仅 1 例失败**，证明第三处取数被独立锁定。
  - `personnelManagePage.spec.js`（5 例）：`{list}` / `{records}` / 裸数组 / null 空态 / 抛错。
    变异验证（还原旧写法 `data.list ?? data ?? []`）→ **2 例失败**（`{records}` 与 `null`），顺带暴露并修掉一个潜在缺陷。
  - `warningDeliveryTab.spec.js`（5 例）：5 渠道共用一处取数，按**渠道**逐形态覆盖：
    sms=`records`、app=`list`、email=裸数组、空记录空态、单渠道失败降级。
    变异验证（只保留 `records` 分支）→ **2 例失败**（`list` / 裸数组），逐形态锁定成立。
    注：`PermissionButton` 依赖 pinia auth store，用例内 stub 掉；`RouterLink` 同样 stub。
  - `otaManagePage.spec.js`（4 例）：三源分别投喂 `records`/`items`/`list`/裸数组/空 + 整页失败降级。
    变异验证（只保留 `records` 分支）→ **2 例失败**（`items` 任务行 / `list` 固件行）。
  - `noticeTemplatePage.spec.js`（6 例）：`records`/`items`/`list`/裸数组/空态/抛错。
    变异验证（还原旧写法 `records ?? items`）→ **2 例失败**（`list` / 裸数组会静默空表）。
- **扫描口径纠偏（重要）**：按 `records|items|list` 关键字统计文件会**误把内部状态读取算成形态分支**。
  例：`AiResultsPage.vue` 有 6 处 `results.value.items ?? []`，但 `dashboardApi.getAiMonitorHazards` 已归一再返回数组，
  那些是内部 ref 读取，不是待迁移点。**同样，`AdminApplicationsPage:90` 的 `data?.records ?? []` 也不算风险**——
  `getApplications` 返回 `normalizePaged(...)`，`records` 恒为数组。
  正确口径：只扫「API 未归一（直接 `return request`）且页面再猜形态」的组合，或本地 helper。
- **API 层现状**：`dashboardApi` 列表方法三种返回风格并存——
  ① `normalizePaged(...)`→`{records,...}`；② 包装成 `{list,...}`（如 `getHazards`/`getPersonnel`）；③ 原样透传（如 `listNoticeTemplates`/`listActiveSessions`）。
  ③ 类是页面猜形态的根源，**统一为 ① 才是根治**（改动面大，需单独一轮 + 契约测试，暂不动）。

### 7.2 时间快照

- API 返回 ISO `triggeredAt`（绝对时刻）；前端 `formatWarningTime` 本地化（已覆盖 5 页面，57/57 断言）。
- `WarningDto.time` 为 UTC 兼容快照字段 → 标记待弃用；外部消费者以 `triggeredAt` 为准。

### 7.3 ID 语义（P2）

- `ZoneDashboardDto.RelatedWarning` 字段：`code, title, level, status, region, triggeredAt` —— **无数字 `id`**。
- 因此区域专项「关联预警」列表**不可点击跳转** `/dashboard/warnings/{id}`。
- 修复需后端补 `id` 字段（数据契约变更），**演示后做**；短期前端保持不可点击 + 文案说明。

---

## 8. 文档现状

- `docs/` 共 **61** 篇 md，已有 `README.md`（3.9KB）与 `INDEX.md`（11.5KB）**双导航并存**——真实待收敛项：保留唯一入口，另一个转跳或归档。
- 已有目录：`archive/`、`audit/`、`delivery/`、`demo/`、`guides/`、`reviews/`、`ONBOARDING/`。
- 建议（阶段 0 后续）：`docs/README.md` 作为唯一导航；历史验收日志进 `delivery/archive/` 并标「历史快照」。

---

## 9. 风险登记

| 风险 | 说明 | 处置 |
|---|---|---|
| 演示前动契约代码 | `PageResponse`/时间/ID 归一化会触及 57/57、37/37 全绿链路 | **演示前不改**，先出审计（本文）+ 契约测试 |
| `styles.css` 9,420 行大搬迁 | 样式层极易回归 | 逐页面迁移 + 截图对比，禁一次性搬 |
| 数据库初始化补丁 | `schema.sql`/`data.sql`/`DatabaseSchemaPatchInitializer` 幂等补丁属高风险债 | 演示后引入 Flyway 再动 |
| 历史文档丢失 | 验收证据依赖 `docs/delivery/` | 迁移进 `archive/`，不删除 |
| prettier `format:check` 存量未过 | 全仓 **148** 文件不符合 prettier（`semi:false / singleQuote / printWidth:100`）；CI `ci.yml` 有 `format:check` 步骤，合并到 `main` 会失败（`release/delivery` 为本地分支未触发 CI） | **只格式化本次触碰的文件**；不做全仓一次性格式化 PR（diff 噪声会掩盖真实改动），随结构拆分按模块逐步格式化；CI 侧先 `continue-on-error` 或暂缓该步骤 |

---

## 10. 与「项目整理方案」估算的差异（**重要，防误工**）

| 项 | 方案估算 | 实测 | 结论 |
|---|---|---|---|
| 后端规模 | 337 文件 / 33,233 行 | **363 / 41,702** | 方案偏小（含测试/或旧快照） |
| 前端规模 | 155 / 88,901 | **153 / 101,283** | 方案偏小 |
| AI 规模 | 79 / 13,952 | **80 / 21,427** | 方案偏小（可能未含部分模块） |
| TODO/FIXME/@Deprecated | 211 处 / 52 文件（宽匹配误报） | **21**（TODO 15 + @Deprecated 6） | **方案高估**；但 AI 9 处需按 §4.2 登记豁免 |
| `PageResponse` | 「统一为…」（暗示新建） | **已存在且已统一** | 任务改为「推广/归一化」 |
| `AiController` | 871 行 | **1,016 行** | 已增长，拆分优先级更高 |
| 501 面 | 多处 | **HTTP 501 仅 2 端点**，其余为 `ApiResponse.fail(501)`/文案 | 治理面小、集中 |

> 以上差异说明：**方案的方向正确，但规模/基线数字已过期**。后续排期请以本文实测为准，避免按错误体量评估工时。

---

## 11. 建议排期（配合演示窗口）

- **演示前（现在）**：仅完成阶段 0（基线冻结 + 本文 + `CODE_STYLE.md`）+ 契约审计。**不动业务代码。**
- **演示后 阶段 1（低风险）**：注释规范落地（存量逐模块）、文档双导航收敛、前端 `api/client` 分页归一化（先加归一化层，不改端点）。
- **阶段 2（结构拆分）**：`dashboardApi` 分域 → `styles.css` → Overview/Welcome/ModelManage → 后端 AiController/WarningService；每 PR 一个主题 + 单测 + 截图 + 57/57。
- **阶段 3（数据/安全）**：Flyway 迁移、覆盖率门槛、契约测试、ai-service 鉴权、依赖审计。
