# CODE_REVIEW_R11 · 冗余代码与底层逻辑冲突专项审查

> 审查对象：`backend/` + `frontend/` + `ai-service/`（全仓，不含 `.venv` / `node_modules` / `datasets` 图像）
> 基线：`b81311f`（`release/delivery`，工作区干净）
> 方式：三线并行只读审查（后端 / 前端 / AI）+ Lead 跨端交叉审计；每端均先读 `MEMORY.md` 与 R10/R10_FIX，历史已修项不重报
> 结论：**P0 = 1 · P1 = 23 · P2 = 49**；冗余清单 60+ 条、逻辑冲突 50+ 条；另有「验证为正确」31 项、待确认 25 项

---

## 0. 方法与基线

| 项 | 说明 |
|---|---|
| 代码规模（实测） | backend 375 文件 / 4.2 万行；frontend 184 文件 / 10.9 万行（115 个 `.vue`）；ai-service app 39 文件 ≈ 1.1 万行 + 入口 3.1k + scripts 5k |
| 门禁基线 | 本 commit 的 CI 已全绿：backend `mvn clean compile`、frontend lint + build、静态审计（parity / missing-imports / broken-imports / timer-leaks / comments）、AI Python 语法。**未在本机重跑**（Windows 沙箱 + Docker 未运行） |
| 未覆盖 | Docker 容器运行时（`docker ps/logs` 全部不可做，EMQX 类崩溃无法复现）；真实库校验（未连库，schema 结论基于 `schema.sql` 文本 + 代码推导）；`mvn test` / `vitest` / `pytest` 实跑 |
| 判定原则 | 「无用」必须全仓引用检索（含 test、动态 import、模板 kebab-case、`<component :is>`）后仍 0 命中；跨端冲突必须给出两侧代码位置；无法实跑验证的结论进「待确认」 |
| 详细证据 | 三份分端报告（含逐条位置/摘录/修复建议）见附表；本文件为汇总与去重后的版本 |

---

## 1. P0（1 条，已由 Lead 独立复核）

### P0-1　「AI 综合评分 → 预警」链路在干净部署上恒定失败，且被当成「并发去重」静默吞掉

**证据链（我逐环复核过）**
1. `warning/engine/AiWarningScheduler.java:37` 硬编码 `RULE_CODE_AI = "AI_COMPREHENSIVE"`，`:114` 写入 `warning_event.rule_code`；
2. `schema.sql:197`：`rule_code VARCHAR(64) REFERENCES warning_rule(rule_code) ON DELETE SET NULL` —— 外键；
3. `data.sql:623-631`：`warning_rule` 只播种 `R001`–`R006`，**没有 `AI_COMPREHENSIVE`**（全仓检索：该串只出现在调度器自身）；
4. `config/DatabaseForeignKeyInitializer.java:41-43` 启动期幂等补 `fk_warning_rule`，老库也会被补上；
5. `AiWarningScheduler.java:124-131`：`catch (DataIntegrityViolationException)` → `log.debug("AI 预警并发去重…")` → `return false`。

**结论**：任何由 `data.sql` 初始化的库上，这条 INSERT **每轮必抛外键违约**，被降级为 debug 日志 + 静默跳过 → `source='AI_AUTO'` 的 AI 综合预警 0 产出、监控无告警。`docs/DEVELOPER.md:729` 仍对外描述该链路"自动创建 warning_event"，属**文档与运行态相反**。

**修复建议（二选一）**
- `data.sql` 增加一条系统伪规则（`rule_code='AI_COMPREHENSIVE'`, `metric_code='ai_score'`, `expression='ai_score >= 60'`, `enabled=false`），把 rule 表当来源字典；或
- 去掉该硬外键（改普通列 + 应用层校验），与 `warning_rule` 只停用不硬删的设计一致；
- 同时把 catch 拆开：只有唯一键冲突才算并发去重，外键/非空必须 `log.error` 并计入失败数。

> ⚠ 与 P0-1 **并列且必须一起修**：`data.sql:712-733` 的 `ai_comprehensive_score.risk_level` 种子写 `orange/yellow/red`，而代码全按 `LOW/MEDIUM/HIGH/CRITICAL` 查（`AiWarningScheduler:63-64`、`AiRiskEnricher:35-56`）→ 即使修好外键，AI 联动仍选不中种子行；前端 AI 标签会显示英文 `orange`。两条是独立断点。

---

## 2. P1（23 条，按主题归并）

### 2.1 权限与鉴权（跨端同源，三线各自独立发现 → 强证据）

| # | 问题 | 关键证据 |
|---|---|---|
| P1-A1 | **三源权限漂移**：前端 `ROLE_PERMISSION_MAP`（126 码）/ 后端 `PermissionRegistry`（125 码）/ `data.sql sys_permission`（115 码）互不一致；`data.sql` 缺 20 条、多 9 条，`rule:*`（单数）与后端 `rules:*` 拼写漂移 | `auth.js:285-685`；`PermissionRegistry.java`；`data.sql:102-244` |
| P1-A2 | **幽灵权限码 `inspection:gps-check`**：前端 3 处使用，后端 registry 与 `data.sql` 均无；根因是生成器正则 `sync-permission-registry.mjs:53` 的 `[a-z:]+` 不含 `-` | `sync-permission-registry.mjs:53`；`InspectionController.java:30-34`（实际校验 `inspection:submit`） |
| P1-A3 | **AI 写操作全部用读权限码**：`AiController` 37 个端点统一 `hasAuthority('ai:insight:view')`，含删除模型、上线、启停训练；`ai:model:manage/edit` 三处声明、后端 0 引用 | `AiController.java:88…1043`；`data.sql:130-131`；`PermissionRegistry.java:26,122-123` |
| P1-A4 | **`hasRole('ADMIN')` 硬编码 7 处**：`system_admin`（下划线 role_code、无 `ROLE_ADMIN`）因此永久无权改/删预警 | `WarningController.java:151,228`；`AiAnalysisController.java:41,49` 等 |
| P1-A5 | **菜单可点但路由 403（10 条确定冲突）**：ai-engineer / emergency-commander 缺 `settings:view`，attendant / expert / maintenance 缺 `resource:dispatch` | `router/index.js:157-173` ↔ `auth.js:590-684` ↔ `dashboardData.js:29-334` |

### 2.2 状态与生命周期

| # | 问题 | 关键证据 |
|---|---|---|
| P1-B1 | 新建预警把 `unified_event.state` **无条件重置为 `DETECTED`**，已闭环事件被打回待确认；`dismiss` / `update` 又完全不推状态机 | `EventStateService.java:119-135`；`WarningCreateService.java:218-228`；`WarningService.java:112-125,256-266` |
| P1-B2 | AI 预警与自动解除**互斥循环**：AI 预警不写 `lastSeenAt` → 30 分钟被自动解除、10 分钟后重建 | `AiWarningScheduler.java:111-121`；`WarningEventRepository.java:51-55`；`WarningAutoResolveScheduler.java:42,74` |
| P1-B3 | 预警双轮询互相覆盖：`/warnings`（全量含关闭，15s）与 `/warnings/preview`（仅活动，10s）都整表替换 `state.warnings` → 大屏点位 10s↔15s 抖动 | `realtimeService.js:520-563`；`WarningService.java:102-110` |
| P1-B4 | 应急「终态」判据漂移：`TodoCenter` 用 `contains("关闭")||contains("归档")`，而真实终态是 `已闭环` → 已闭环应急被计为进行中 | `TodoCenterController.java:61-67`；`EmergencyService.java:32` |

### 2.3 通知与送达

| # | 问题 | 关键证据 |
|---|---|---|
| P1-C1 | **「已读」覆盖「投递状态」**：`notification_record.status` 一列两义，点已读写 `READ` 覆盖 `SENT/MOCK_SENT/BLOCKED/FAILED`，导致按状态查询/补发/统计全部失真 | `MessageController.java:126-162`；`NotificationDispatcher.java:88-101,119-124`；`MessageStatsController.java:54` |
| P1-C2 | **送达页数据源分裂**：`warning_delivery_record` 全仓无任何 INSERT；真实回执在 `notification_record/notification_receipt`，两套状态词表互不相交 → 预警送达页恒 0 | `WarningDeliveryController.java:72-203`；`NotificationRouter.java:233-246` |

### 2.4 数据、种子与配置

| # | 问题 | 关键证据 |
|---|---|---|
| P1-D1 | dev/docker **每次重启重跑 `schema.sql` + `data.sql`**：规则阈值被种子覆盖、时序样本每次重启累积（`NOW()` + 含 `recorded_at` 的唯一索引 → 冲突永不触发）；与 R10_FIX §7「正常重启不会」直接冲突 | `application.yml:33-37`；`data.sql:623-631,871-885`；`schema.sql:464-465` |
| P1-D2 | 配置默认值不同源（Lead 交叉审计）：`ALIYUN_SMS_ENABLED` 模板 `false` vs yml 默认 `true`；`JWT_EXPIRE` 模板 `PT24H` vs yml `PT2H`；`.env.example` 缺 37 个 yml/compose 需要的键；5 个 `*_PORT` 键无消费者 | `.env.example`；`application.yml:98,109`；`docker-compose*.yml` |
| P1-D3 | AI 等级 `risk_level` 值域相反（见 P0 后注） | `data.sql:712-733` vs `AiComprehensiveScore.java:30-32` |

### 2.5 AI 链路

| # | 问题 | 关键证据（已抽检） |
|---|---|---|
| P1-E1 | **训练-推理契约断裂**：4 份脚本写同一个 `xgboost_fusion.json`，其中 3 份是二分类；推理按回归 `[0,100]` 解释、只 load 不校验 objective → 跑一次 retrain 即静默全 LOW；LSTM/IForest 架构同样不符 | `train_fusion_v1.py:102` 等；`xgboost_model.py:90-111` |
| P1-E2 | **重复路由**：`POST /api/ai/model/retrain` 在 `feedback.py:119` 与 `model_info.py:287` 各注册一次，先注册者遮挡后者，两版 schema 还不同 | `main.py:193-194` |
| P1-E3 | **图像阈值断链**：AI 侧 `conf_threshold=0.35` 就返回灾种；Java 侧 `CONFIDENCE_THRESHOLD=0.8` 才建预警 → 0.35–0.8 的真实检出系统性漏报（已抽检两处常量） | `yolo_model.py:82`；`ImageAnalysisListener.java:35,96` |
| P1-E4 | 灾种值域三套：AI 中文六类（含字典没有的「塌陷」）经 `HazardService:238-241` 原样落库，而 `schema.sql:386` 声明该列为英文码；`crack→collapse` 与字典 `ground_fissure` 语义冲突 | `disaster_assessment_model.py:146-163`；`HazardService.java:238-241` |
| P1-E5 | 漂移检测**假绿**：无基线/空数据返回 `NORMAL`，前端显示"模型运行稳定"；另有 3 个漂移端点返回硬编码假数据且无 `mock` 标识 | `drift.py:69-78`；`drift_monitor.py:196-262`；`ModelManagePage.vue:354` |
| P1-E6 | 自适应归一化双口径：`disaster_assessment_model` 已实际接入（同输入跨时间漂移 + `/detect-batch` 用 `MON-000` 污染跨区域窗口），`comprehensive_model` 却声明 `applied=0` 且该字段被 response_model 丢弃 | `disaster_assessment_model.py:296-304`；`disaster_assessment.py:261-264`；`comprehensive_model.py:974-981` |
| P1-E7 | **直连 / 网关两套形态并存**：sentinel 明确禁止浏览器直连 8001，drift/multimodal 仍首选直连；生产 CORS/端口只按网关形态配置 → 生产两页必挂、开发机 8001 无鉴权暴露 | `SentinelCompareController.java:32-35`；`driftMonitorService.js:12-16`；`docker-compose.prod.yml:258-280` |

### 2.6 前端契约与入口

| # | 问题 | 关键证据 |
|---|---|---|
| P1-F1 | **审批中心契约三处断裂**：传字符串 `'approved'` → `JSON.stringify` 成标量 → 后端 `@RequestBody Map` 400；状态筛选用英文字面量，后端按中文 `warning.status` 比较 → 默认「待审」恒空 | `ApprovalCenterTab.vue:92,106`；`dashboardApi.js:1214`；`ApprovalCenterController.java:169` |
| P1-F2 | **待确认队列筛选整组失效**：对 reactive 字符串取 `.value` 恒 `undefined`；中文灾种/等级下拉与库里英文码不匹配 | `HazardsListPage.vue:125,699`；`ConfirmationWorkbenchPage.vue:393`；`data.sql:672-682` |
| P1-F3 | **退出登录从不调 `/auth/logout`**：`logoutRequest` 全仓 0 调用，JWT 吊销链路 UI 不可达（已抽检：只定义未使用） | `dashboardApi.js:160-165`；`UserProfileMenu.vue:66-70` |
| P1-F4 | **运行模式是导入期静态快照**：`USE_BACKEND` 在 import 时冻结，未注入 env 时 144 处分支恒走 mock，而徽标显示「真实数据」（生产 Dockerfile 有 fail-fast 兜底，故未升 P0） | `dashboardApi.js:27`；`main.js:5-11`；`App.vue:14-30` |
| P1-F5 | 预警列表/详情「等级」映射 ≥8 份且不一致（红橙同色、红黄兜底成蓝；大屏与移动端 class 与真实中文等级不匹配） | `mapService.js:1399`；`realtimeService.js:161`；`MobileCommandPage.vue:79-113` |

---

## 3. 跨端交叉审计（Lead 独立结论）

| # | 结论 | 证据 | 级别 |
|---|---|---|---|
| L1 | **灾种三方一致（验证正确）**：前端 11 项 / 后端 `HAZARD_TYPE_CODES` 13 项（11 + 2 历史变体）/ `sys_dict.disaster_type` 11 项，值域一致 | 三处逐项比对 | ✅ |
| L2 | **MinIO 双命名已正确桥接（验证正确）**：`${MINIO_ACCESS_KEY:${MINIO_ROOT_USER:…}}` 嵌套别名 + compose 映射，非冲突 | `application.yml:189-190`；`docker-compose.yml:183-184` | ✅ |
| L3 | **预警状态无枚举，中文裸字面量散落**：`待确认/已确认/处理中/已解除/已忽略/已关闭` 在 ≥11 个后端文件、37 处裸写；终态集合 `List.of("已关闭","已忽略","已解除")` 在 7 个文件各写一份 | `WarningController:102`、`WarningService:106`、`AiWarningScheduler:72,158` 等 | P2（与后端 P2-16 互证） |
| L4 | **后端返回形态与契约不符**：`ApiResponse<Map>` 端点 **≥160 个**；`"items"` 出现在 15 个 Controller，`records` 0 个；与前端三形态兼容（R10 §7.1）叠加后持续放大 | 全 Controller 扫描 | P1 契约债（既有条目，非新发现） |
| L5 | 配置层 37 个键未进模板 / 5 个死端口键（见 P1-D2） | `.env.example` 89 键 vs yml+compose 105 键 | P2 |

---

## 4. 冗余代码汇总（按类型，去重后）

| 类型 | 代表项 | 规模 |
|---|---|---|
| 死方法 | 后端 13 个 0 调用公开方法 + 3 片死方法（~130 行）；前端 `dashboardApi` 42 个 0 引用方法、`mock` 38 个 0 引用导出 | ~50 处 |
| 死类 / 死字段 / 死配置 | 后端 `AppProperties` 整类无人注入、`SecurityConfigProd.devUsersEnabled`、`rule_snapshot` 永不写入、`WarningDto` 18 参构造器；前端 `sentinelService` 285 行直连 8001 且 0 引用 | 8 处 + 285 行 |
| 死 Repository | 34 个 Spring Data 派生方法全仓 0 调用 | 34 |
| 重复实现 | 6 套 request/fetch 封装；时间格式化 33 处（15 个本地 `fmt*`）；等级映射 6–8 份；指标表 6 份；角色中文名 3 份；两份 `SecurityFilterChain`（~60 行几乎逐行相同）；两份七维度评分 | ≥10 组 |
| 重复常量 | 预警终态 7 份、灾种映射 3 份、权限矩阵 3 份、`NORM_MAX` 6 份、训练脚本阈值 5 处 | ≥8 组 |
| 重复 DDL | `schema.sql` 14 张表定义两次（两个 109 行近同整块，`:1474-1582` / `:1593-1701`）；同列可能双外键（内联 FK 自动命名 vs 补丁器 `fk_warning_rule`） | 14 表 / 1 类 |
| 遗留第二套实现 | ai-service `web.py`（2415 行）+ `ai.py`（681 行）第二套入口，无启动脚本引用、默认 8080 与后端撞端口；`scripts/quick_api_check.py` 端口 8765 | 2 文件 / 3k 行 |
| 样式冗余 | `styles.css` 9,743 行 / 130 个重复选择器 / 45 个同名 token 双源 | 前端 |

---

## 5. 验证为正确的部分（避免只报问题）

- 灾种 11 项三方一致；UI 判定的 39 个权限码在前端角色表与后端 registry 中完全一致（差异只在 `inspection:gps-check`）
- 预警时间展示 8 文件 / 15 处全部走 `utils/warningTime.js`，未发现直接渲染 `WarningDto.time`
- XGBoost / Comprehensive 推理特征顺序与 `meta.json` 完全一致；v3/v2 回归口径自洽
- YOLO 对 COCO 80 类模型不报业务灾种；SSRF 白名单、`mock:true` 诚实降级（sentinel `dataSource=real/mock`）
- 主链（设备上报 → 规则引擎 → 预警落库 → 前端）代码层面自洽；规则外键、启动补丁器行为一致
- 前端 0 个未使用组件（动态 import 陷阱已排除）；MinIO 双命名正确桥接；`coordinate`/`query` 工具单源

---

## 6. 待确认项（需环境或产品口径，未下结论）

1. `data.sql` 的 `sys_permission` / `sys_role_permission` 是否已可整体废弃（后端权限矩阵读 `PermissionRegistry`，两表已无 Java 消费方）——若废弃，P1-A1 的一半可删除而非修复
2. `ApprovalCenter`（审批中心）是否仍在交付范围（前端入口存在但契约断裂）
3. `crack → collapse` 的灾种语义是否符合产品口径（AI 两分类 vs 字典 11 类）
4. `ai_comprehensive_score.risk_level` 是否允许历史小写颜色词（迁移 vs 清洗）
5. docker 未运行：EMQX 类崩溃、容器 healthcheck、真实库 `fk_warning_rule` 实际状态未验证
6. 前端 `USE_BACKEND` 静态快照是否接受"仅生产 fail-fast"的现状
7. `styles.css` 拆分与 `visual-refresh.css` 覆写层是否列入本迭代

---

## 7. 修复优先级与提交拆分建议

| 顺序 | 内容 | 理由 |
|---|---|---|
| ① 单独 PR | **P0-1 + P1-D3**（AI→预警外键 + 等级词表） | 同一链路、必须一起验；改动小、收益最大（恢复一条宣称存在的链路） |
| ② 权限 PR | P1-A1~A5（含改生成器正则 + 删死码 + `hasRole` 换权限码） | 安全相关；三处同步必须一次到位，并补 `AiControllerPermissionTest` |
| ③ 状态机 PR | P1-B1~B4（`EventStateService` 不重置、`dismiss/update` 推状态机、AI 预警写入 `lastSeenAt`） | 数据一致性；需 DB 迁移/回填评估 |
| ④ 通知 PR | P1-C1~C2（拆 `read_at` 列、送达页改读回执表） | 涉及表结构变更，需前后端同改 |
| ⑤ 契约 PR | P1-F1~F4 + L4（审批中心、筛选、logout、返回形态归一） | 前端为主，可分批 |
| ⑥ 清理 PR | 死代码/重复 DDL/重复常量/`web.py` 遗留入口 | 纯删除，低风险，最后做 |
| ⑦ 配置 PR | P1-D2（模板补 37 键、删 5 死键、默认值同源） | 与部署文档一起改 |

> 不建议把 P0/P1 混在一个大 PR：本仓历史（R4/R10）显示契约类改动一混就难以定位回归。

---

## 8. 角色可见性专项核对（2026-11-26 追加）

**方法**：解析 `ROLE_MENU_MAP`（角色 → 菜单路径）+ `routePermissionMap`（路由名 → 权限码）+ 路由表（含嵌套 children、重定向、可选参数），对 7 个角色 × 全部菜单路径逐一判定「路径是否存在 / 是否有必需权限」。

### 8.1 逐角色总览

| 角色（role_code） | 菜单项 | 可正常进入 | 点击 403 |
|---|---|---|---|
| admin（admin） | 47 | 40 | **0** |
| system_admin（sysadmin） | 13 | 11 | **0** |
| attendant（duty） | 30 | 25 | **1** |
| expert（expert） | 27 | 23 | **1** |
| maintenance（fieldops） | 20 | 17 | **1** |
| emergency_commander（commander） | 29 | 23 | **3** |
| ai_engineer（aiengineer） | 21 | 14 | **4** |

### 8.2 「菜单在、点击 403」（10 处，功能性失踪）

| 角色 | 失踪页面 | 路由所需权限 | 根因 |
|---|---|---|---|
| attendant / expert / maintenance | `/dashboard/route-planning` | `resource:dispatch` | 菜单对所有角色开放，但路由绑定"派发"写权限 |
| emergency-commander | `/dashboard/account-security`、`/dashboard/profile`、`/dashboard/help` | `settings:view` | 个人中心/帮助/账号安全被统一绑 `settings:view`，该角色只有 `profile:view` |
| ai-engineer | `/dashboard/rule-engine`、`/dashboard/account-security`、`/dashboard/profile`、`/dashboard/help` | `settings:view` | 同上；**规则引擎还绑错了权限码**——AI 工程师持 `rules:view/rules:edit`，路由却要 `settings:view` |

> 守卫逻辑：`frontend/src/router/index.js:586-593` —— 路径在菜单内（`canAccessPath` 通过）但 `hasPermission` 失败时跳 `/dashboard/error/403`。

### 8.3 「有权限、但菜单没有入口」（12 处，能力隐形；`/mobile` 除外）

| 角色 | 无菜单入口但可访问的页面 |
|---|---|
| attendant | ai-models、rule-engine、disaster-forecast、damage-assessment、zone-dashboard |
| maintenance | data-sync、data-catalog、rule-engine、field-inspection、personnel-manage、zone-dashboard |
| system-admin | rule-engine |
| admin / expert / emergency-commander / ai-engineer | zone-dashboard |
| 全部角色 | `/mobile`（独立移动端入口，设计如此） |

### 8.4 已排除的误报（避免误判为"页面失踪"）

- `/dashboard/message-center`、`/dashboard/todo`、`/dashboard/approvals` → 重定向到首页并打开通知抽屉（`router/index.js:367-378`）；
- `/dashboard/warnings/create` → 重定向预警列表（创建走 `WarningCreateModal`）；
- `/dashboard/warnings/:id/delivery` → 重定向预警详情 `?tab=delivery`；
- `/dashboard/zone` → 匹配 `zone/:kind?/:region?` 可选参数路由；
- `ADMIN` → 脚本把注释中 `hasRole('ADMIN')` 的字符串误识别为菜单项，**假阳性**。

### 8.5 账号与角色键对照（README 表未标 role_code）

| README 用户名 | sys_user.role_code | 前端角色键（normalizeRole 后） |
|---|---|---|
| admin | `admin` | admin |
| sysadmin | `system_admin` | system-admin |
| commander | `emergency_commander` | emergency-commander |
| duty | `attendant` | attendant |
| expert | `expert` | expert |
| aiengineer | `ai_engineer` | ai-engineer |
| fieldops | `maintenance` | maintenance |

### 8.6 修复建议（最小改动）

1. **改绑权限码**：`profile` → `profile:view`（7 角色都有）；`help-center` 独立（公开或 `help:view`）；`account-security` 独立为 `account:security`；`rule-engine` → `rules:view`（admin/expert/ai-engineer 已有，正好匹配职责）。
2. **路径规划**：从 attendant/expert/maintenance 的 `ROLE_MENU_MAP` 移除（菜单与权限同源），不建议补 `resource:dispatch`（写权限过宽）。
3. **补菜单入口**：maintenance 的现场巡检/人员/数据目录/数据同步、attendant 的 AI 预测两页，需产品确认是刻意隐藏还是漏配。
4. **加回归测试**：7 角色 × 菜单交集断言「菜单路径必须命中路由 且 角色必须持有该路由权限码」，可防 8.2 类问题复发。

---

## 附表：分端详细报告

| 报告 | 内容 |
|---|---|
| `docs/reviews/R11/backend-findings.md` | 后端 424 行：P0×1 / P1×9 / P2×24，冗余清单 27 条、冲突清单 22 条、验证正确 14 条、待确认 8 条 |
| `docs/reviews/R11/frontend-findings.md` | 前端 455 行：P1×7 / P2×14，含 0 未用组件的排除过程与防误报说明 |
| `docs/reviews/R11/ai-findings.md` | AI 服务 415 行：P1×7 / P2×11，含训练-推理契约矩阵与实跑验证方法 |
