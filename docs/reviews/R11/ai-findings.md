# AI 服务（ai-service）冗余代码与底层逻辑冲突审查 — R11

- **审查对象**：`ai-service/`（FastAPI，Python），只读审查，未修改任何被 Git 跟踪的文件
- **审查时间**：2026-11-26
- **审查方式**：逐文件读码 + 全仓符号检索（py/java/js/vue/yml/md/sql）+ 基线文档对照（`MEMORY.md`、`CODE_REVIEW_R10.md`、`CODE_REVIEW_R10_FIX.md`、`KNOWN_LIMITATIONS.md`、`schema.sql`、`data.sql`）
- **未执行**：未启动服务、未跑 pytest（避免写 `models/feedback/feedback.jsonl`）、未跑训练；`git` 命令在只读沙箱被拒（命名管道访问被拒），因此没有用 `git status/ls-files` 做版本侧核验——本文所有结论均基于磁盘源码 + 交叉检索
- **范围实测**：`app/` 实测 39 个 `.py`、约 1.1 万行；另 `web.py`(2415)+`ai.py`(681)、`scripts/`约 30 文件 5 千行、`tests/` 约 1.1 千行，合计约 2.0 万行（与任务描述的 2.1 万行同量级，任务描述里的 "app/ 约 80 文件" 偏高）
- **结论**：**P0 = 0**（未发现崩溃/数据丢失/新增可利用安全问题）；**P1 = 7**（训练-推理模型契约断裂、重复路由、图像阈值断链、灾种值域混存、漂移假绿、自适应归一化双口径、直连 8001 与网关策略冲突）；**P2 = 11**（冗余与口径类）。P1 中有 3 条会**静默**产生错误结果（不报错、页面仍显示正常：P1-1 分数归零、P1-5 漂移假绿、P1-6 分数随调用历史漂移），是本轮最值得先修的。

> 口径说明：`MEMORY.md §五` 的「已修勿重报」清单逐条比对过，本文不重复审批鉴权/状态机/EMQX/`hazard_point` 默认值/`stopRealtimeSync` 等已关闭项；`KNOWN_LIMITATIONS.md` 已登记的 L-01（多模态/漂移演示数据 + 网关 501）、L-10（ai-service 无鉴权）在本文只作为**新角度**的证据引用，并标注「已知」。

---

## 概要

| 维度 | 结论 |
|---|---|
| 冗余代码 | 两类：①**遗留第二套服务/入口**（`web.py` + `ai.py` 约 3.1 千行，无 Dockerfile/compose/start-dev 引用）；②**同一能力多份实现**——两套七维度评分模型（`comprehensive_model.py` 1079 行 vs `disaster_assessment_model.py` 1381 行，评分函数成对重复）、6 个训练脚本写 2 个模型文件、5 处风险等级阈值、6 处归一化上限表、3 处 `TinyLSTM`、3 处「模型清单」实现、3 个 `RetrainRequest`。另有一批 0 引用函数/空包/未接入端点。 |
| 逻辑冲突 | 最重的 4 条：**训练脚本 objective/输出形状与推理契约不一致**（跑一次 `/model/retrain` 或误跑 v1/v2 脚本即把回归模型换成二分类器，推理端不做校验，表现是"AI 分数全 0/全 LOW"）、**`POST /api/ai/model/retrain` 被重复注册（后者不可达）**、**图像置信度阈值 AI 侧 0.35 / Java 侧 0.8 断链**、**灾种值域（英文 11 码 / 中文 6 类 / 中英混存）在 `hazard_point` 混存**。其余：`mock` 语义 4 种、模型缺失降级 5 种路径、自适应归一化"声明未接入但实际已接入"、漂移页无数据返回 NORMAL 假绿、鉴权/CORS/端口在"网关代理"与"前端直连"两种形态间自相矛盾。 |
| 高信噪比说明 | 每条都给了可复现的文件:行与检索证据；无法在只读环境证实的（如"跑一次 retrain 后模型分数归零"）标注为「按代码推导，未实跑」，并在「待确认项」列出验证方法。 |

---

## P0

**本轮未发现 P0。** 说明：`npm audit` 类依赖漏洞、仓库无 CI 属既有缺口，不属本服务代码缺陷；`ai-service` 无鉴权是 `KNOWN_LIMITATIONS.md:157`（L-10）已登记项，本文只在 P1-7 讨论它与"前端直连 / 网关代理"两种设计冲突带来的可运维后果，不重复登记安全缺口本身。

---

## P1（严重：静默错误结果 / 契约断裂 / 假绿）

### P1-1　训练脚本与推理契约不一致：4 份脚本写同一个 `xgboost_fusion.json`，其中 3 份是二分类，推理端按回归 `[0,100]` 解释且不校验

**位置（训练侧）**
- `ai-service/app/training/train_xgboost.py:252`（`objective: binary:logistic`，随即可被 `POST /api/ai/model/retrain` 调用）
- `ai-service/scripts/training/train_fusion_v1.py:102`、`train_fusion_v2.py:152`（均 `binary:logistic`）
- `ai-service/scripts/training/train_comprehensive_v1.py:322`（`binary:logistic`，写 `comprehensive_v2.json`）
- 正确的一组：`scripts/training/train_fusion_v3.py:120`（`reg:squarederror`，随 `:191` 写 v3.0.0 meta）、`train_comprehensive_v2.py:477`（回归）

**位置（推理侧）**
- `ai-service/app/models/xgboost_model.py:90-111`：`pred = model.predict(dmatrix)` 直接当 `[0,100]` 用（`_run_xgboost` 注释声明的就是 v3 回归）
- `ai-service/app/models/comprehensive_model.py:647-683`：同样按 0-100 直接使用
- 两处 `_load()`（`xgboost_model.py:54-68`、`comprehensive_model.py:608-622`）**只判断文件是否存在 + `load_model` 是否抛错**，完全不读 meta 里的 `objective/output_type/feature_names`，也没有对 `predict` 输出范围或分档分布做 sanity check

**证据**
- `models/xgboost_fusion.meta.json:2-4` 明确 `"objective": "reg:squarederror", "output_type": "regression"`；`train_xgboost.py` 训练出的 meta 是 `"version": "1.5.0"` + `binary:logistic` + `samples_train/pos_rate_train/scale_pos_weight`（`:294-314`），两套 meta 字段都不兼容（见 P2-10）。
- `/model/retrain` 有两条入口（见 P1-2），其中**真正生效的那条**（`feedback.py:152-171`）用 `subprocess` 调 `app/training/train_xgboost.py`；训练成功后**没有任何 reload 逻辑**（对比 `model_info.py:301-308` 有 reload），而进程内单例仍持有旧 booster，直到重启。
- `scripts/training/README.md:14-21` 已把 v1/v2 标成「已废弃（二分类/概率）」，但**脚本仍保留且默认输出路径就是线上模型文件**（`train_fusion_v2.py:172`、`train_comprehensive_v1.py:341`），任何人按 README 的 `python scripts/training/train_*.py` 顺手跑一次即覆盖生产权重。

**影响**
- 用二分类模型做回归推理：`binary:logistic` 输出落在 `[0,1]`，`risk_score` 被钳到 **0-1 分**，`_score_to_level()` 恒返回 `LOW`；`confidence` 公式（`0.5+0.005*score`）≈0.5。表现为"AI 融合评分全 0 / 全低风险"，**接口 200、`mock:false`、不报错**——与 `requirements.txt` 里已记录过的"xgboost 版本不符 → predict 恒 0.0 且 degraded=false"是同一类静默失效。
- 即使不动 `/model/retrain`，误跑一份 v1/v2 脚本同样中招；`docs`/README 把"重训"写成常规操作，风险是现实存在的。

**修复建议**
1. 推理端 `_load()` 读同名 `.meta.json`，校验 `objective` 含 `reg`、`feature_names` 与推理向量的顺序/数量完全一致、`output_type=="regression"`，不满足就 **拒绝加载并明确 `mock`/降级**（不要静默接受）；
2. 只保留"目标脚本 ↔ 目标文件"一对一：把 v1/v2 训练脚本移到 `scripts/archive/` 或给输出路径加 `--allow-overwrite` 保护（默认写到 `models/history/`，不覆盖线上文件）；
3. `/model/retrain` 统一走 v3 回归训练（或 `train_comprehensive_v2.py`），训练成功后按 `model_info.py:301-308` 的方式 reload；
4. 加一条启动自检：加载后跑一组固定样本，断言分数落在预期区间（例如专家样例 20 条），失败即告警。

### P1-2　`POST /api/ai/model/retrain` 被两个 router 重复注册，前者遮挡后者，两个实现的请求/响应契约还不同

**位置**
- `ai-service/app/routers/feedback.py:40`（`prefix="/api/ai"`）+ `:119`（`@router.post("/model/retrain")`）→ `/api/ai/model/retrain`
- `ai-service/app/routers/model_info.py:26`（`prefix="/api/ai/model"`）+ `:287`（`@router.post("/retrain")`）→ **同一个** `/api/ai/model/retrain`
- `ai-service/app/main.py:193-194`：`include_router(feedback.router)` 先于 `include_router(model_info.router)`

**证据**
- FastAPI/Starlette 按 `app.router.routes` 注册顺序匹配，先注册者命中；因此**生效的是 `feedback.py:trigger_retrain`**，`model_info.py:model_retrain`（进程内训练 + 自动 reload + 返回 `status/samples/model_path`）整段成为不可达代码。
- 两版契约不同：`feedback.RetrainRequest` 是 `{min_samples, force}`（`feedback.py:61-63`），`model_info.RetrainRequest` 是 `{samples, val_ratio, force}`（`model_info.py:281-287`）；`ai-service/web.py:1522` 与 `dashboard.py:149` 仍在用旧的一种 payload `{"samples":1500,"val_ratio":0.2}`。Pydantic 默认忽略多余字段 → 旧 payload 会被悄悄解释成 `min_samples=20, force=false`，很可能直接返回 `skipped`，调用方拿不到任何"参数没生效"的错误。
- 返回体也不同：生效版返回 `{"code":0,"message":"queued"|"skipped"}`，被遮挡版返回 `{"status":"trained"|"skipped",...}`（`model_info.py:301-309`）。

**影响**
- 一个对外端点实际不可达（重复注册）；文档/面板里推荐的 payload 不生效且无报错；训练路径变成 `subprocess`（环境相关、无 reload、日志只进 stdout），与 `model_info` 版本"进程内训练 + reload"的预期相反。

**修复建议**
- 删除其中一份（建议保留 `model_info.py` 的进程内实现并把 `feedback.py` 的"从反馈库重训"改为同一 handler 的内部函数），或把路径区分开（如 `/model/retrain-from-feedback`）；
- 训练成功后统一 reload 并返回明确的 `status/model_path/metrics`；
- 加一条 `scripts/audit` 级检查：启动时扫描 `app.routes` 出现重复 `(method,path)` 直接 fail-fast（同时可防未来再次漂移）。

### P1-3　图像识别置信度阈值跨端断链：AI 侧 0.35 就返回灾种结论，Java 侧 0.8 才生成预警

**位置**
- `ai-service/app/models/yolo_model.py:81-82`：`YoloDetector(conf_threshold: float = 0.35)`；`:202` 低于阈值直接丢弃
- `ai-service/app/routers/detection.py:309-328`：只要 `mock=false && is_business_model` 且存在检出，就用最高分框给出 `disaster_type`（阈值只有一个：0.35）
  （顺带：`backend/.../ai/dto/DetectionResult.java` 本身没有 COCO 字样，写着"/detect 返回原始检测框（COCO 类别名）"的是 `AiImageResponse.java:8-10` 的对照注释，见 P2-9）
- `ai-service/app/routers/detection.py:165`：无检出时 `EMPTY_CONFIDENCE = 0.99`
- `backend/src/main/java/cn/disasterguard/device/listener/ImageAnalysisListener.java:35`：`CONFIDENCE_THRESHOLD = 0.8`；`:94-101` `confidence <= 0.8` 直接 `return`（不生成预警）；`:188-199` 再按 `0.85/0.90/0.95` 分四档等级

**证据**
- `ai-service/models/README.md:44-49` 自己给的实测指标是 crack P/R 0.6–0.9、test mAP50 0.327——**大量真实检出落在 0.35~0.8 区间**，这些框在 `/detect-image` 里是"有效灾种判定"（`disaster_type=landslide/collapse, message=ok`），到 Java 侧被 `<=0.8` 全部丢掉，日志只有一行 `AI 未确认险情`。
- 两侧阈值没有任何共享配置或注释交叉引用；`detection.py` 侧没有 `0.8` 字样，`ImageAnalysisListener` 侧没提 0.35。

**影响**
- 设备图片→AI 识别→预警这条链路（MEMORY §一 明确它是"真实主链"之一）**系统性漏报**：AI 说"识别到滑坡 0.65"、平台不生成预警，且两边日志都"正常"，排查时极易误判为模型没识别。
- 0.35~0.8 区间的"AI 已判灾种但平台不采信"没有任何审计字段（Java 只 log.info），无法量化到底漏了多少。

**修复建议**
- 阈值单一来源：AI 响应里显式返回 `decision_threshold`（或用统一配置下发给两侧），Java 侧不要另设第二个常量；若确实需要"AI 初筛 0.35 / 平台采信 0.8"，在 ai-service 响应里加 `accepted`/`min_accept_confidence` 字段并在 Java 落库记录被丢弃的次数与分值；
- 把 0.8 与 `riskLevelOf` 的 0.85/0.90/0.95 一起做成可配置项（配置项已有先例：`DETECT_TIMEOUT_SECONDS`），别写死在 listener；
- 修正两侧注释里的口径（`DetectionResult.java:8` 还说 `/detect` 返回 COCO 类别名，实际业务模型返回 crack/landslide）。

### P1-4　灾种值域三套且真实混存：AI 中文六类（含字典里没有的"塌陷"）/ 平台英文 11 码 / `warning_event.hazard_type` 声明英文码

**位置 / 五套定义**
1. AI 图像映射（英文 2 码）：`ai-service/app/routers/detection.py:159-167` `CLASS_TO_DISASTER={"crack":"collapse","landslide":"landslide"}` + `DISASTER_LABELS`
2. AI 评估模型（中文 6 类，多一个"塌陷"）：`ai-service/app/models/disaster_assessment_model.py:146-163` `SUPPORTED_DISASTER_TYPES=[滑坡,崩塌,泥石流,地裂缝,地面沉降,塌陷]`
3. 权威字典（英文 11 码，无"塌陷"）：`backend/src/main/resources/data.sql:17-30`
4. 后端归一化（11 码 + 2 别名，无"塌陷"→collapse）：`backend/.../warning/service/WarningService.java:369-384`
5. 前端权威源（11 码 + 别名 塌陷→collapse）：`frontend/src/utils/hazardTypes.js:19-49`

**冲突链（真实路径）**
- `ai-service/app/routers/disaster_assessment.py:275-280` 把模型的**中文** `disaster_type` 放进 `DetectionDto.hazard_type` → Java `AiHazardMonitorScheduler.java:155` 原样取用 → `HazardService.java:238-241` `point.setHazardType(disasterType)`，**不做任何归一化**直接落库。
- 而 `backend/src/main/resources/schema.sql:386` 的注释明确写「hazard_type 与 hazard_point.hazard_type 对齐（英文码 landslide / collapse / ...）」；`schema.sql:102` 该列是 `VARCHAR(64)`、无 FK/CHECK，因此不会报错——**中文值就这么混进按英文码设计的列**。同一张表里：人工预警（`WarningService.java:420`）存英文码，AI 监测点存中文。
- "塌陷" 只在 AI 的 6 类里：字典无此码 → 前端 `hazardTypes.js:45-49` 把它别名成 `collapse`；后端 `HAZARD_TYPE_CODES` 无"塌陷" → 若走 `POST /api/warnings` 会命中 `WarningService.java:461` 的 `log.warn("未知灾害类型标签…原样入库")`，把中文存进去。
- 反向："crack"（裂缝）被 AI 映射成 `collapse`（崩塌），而平台字典里裂缝对应的是 `ground_fissure`（地裂缝，`data.sql:26`）——一张裂缝照片会被创建成"崩塌"隐患点（`HazardService.java:240` 名称也会是「AI 监测-崩塌(...)」）。

**影响**
- 同一列两套值域：按类型筛选/统计/去重（`HazardService.java:230` 的 30 分钟去重按 disasterType 精确串）、前端 `hazardTypeLabel()`、预警向导 `WarningCreateModal.vue:134` 的 `normalizeHazardType(h.type)` 都会出现"中文/英文/未知码"三态；"塌陷"在库内无字典、在前端被折叠成"崩塌"。
- `AiServiceClient.java:233` 的 Javadoc 还在承诺 `disaster_type` 只有 6 类中文——三方契约没有任何一处是"唯一真相"。

**修复建议**
1. 以 `sys_dict.disaster_type` 的 11 个英文码为唯一值域，AI 侧 `SUPPORTED_DISASTER_TYPES`/`CLASS_TO_DISASTER`/`DISASTER_LABELS` 全部改成英文码（或 Java 落库前过 `WarningService.normalizeHazardType` 的同一张表）；
2. 处理"塌陷"：要么进字典（并同步前端/Java 映射/`hazardTypes.js`），要么 AI 侧删除并映射到 `collapse`；
3. 明确 `crack → ground_fissure`（地裂缝）而不是 `collapse`，这需要产品确认（见待确认项），但至少不要与字典语义冲突；
4. 加一个审计脚本：扫 `hazard_point.hazard_type` / `unified_event.hazard_type` 是否全部落在字典 code 集合内（现有 `check-endpoint-parity` 管不到值域）。

### P1-5　漂移检测在"无基线 / 空数据"时返回 `NORMAL`（假绿），另有 3 个漂移端点返回硬编码假数据且无 `mock` 标识

**位置**
- `ai-service/app/routers/drift.py:69-78`：`currentData` 为空 → `current_data={}` → `monitor.detect({})`
- `ai-service/app/models/drift_monitor.py:69-73`：无基线文件时 `baseline_data={}` 直接 return；`:196-252` 循环 `current_data.items()` 匹配不到基线特征 → `overall_level` 保持 `"NORMAL"`、`recommendation="各特征分布正常，模型运行稳定…"`（`:261-262`）
- 本机 `ai-service/models/` 下**没有 `baseline/` 目录**（`ls models` 实测只有 5 个权重 + history），即默认部署就是"无基线 → 永远 NORMAL"
- 前端真实入口：`frontend/src/services/_architecture/domain/driftMonitorService.js:73-78,163-168` → `frontend/src/views/ai/ModelManagePage.vue:354` 调 `detectDrift({modelId, currentData: []})`
- 假数据端点：`drift.py:114-125`（trend 用 `0.05+(i%5)*0.02+hash(i)%3*0.01` 拼趋势）、`:144-164`（feature 详情固定 `psi 0.18/SLIGHT/基线 mean 45.2`）、`:227-249`（retrain 状态 `RUNNING/45%/固定 5000 样本/假日志`）；`:206-224` 的 retrain 只生成 `jobId` 字符串，不训练

**证据 / 影响**
- `psi=0` 的早退还有 `drift_monitor.py:105-106,167-168`：样本 <10 也返回 0 → 判 NORMAL。
- 页面层面 `ModelManagePage.vue:361-368` 只在**请求失败**时才提示"能力未接入"，请求"成功但空数据"会走到正常渲染分支——于是**"未检测"被渲染成"模型无漂移、运行稳定"**，与项目反复强调的"不造假绿"原则直接冲突（MEMORY §八/A6 系列就是为这类假绿写的）。
- 3 个假数据端点没有 `mock:true`（对比 `multimodal.py:121-133` 至少有 `_demo_meta()`），前端无法标注"演示"；`KNOWN_LIMITATIONS.md` L-01（`:69-75`）已把多模态/漂移整体登记为"演示能力"，但**没有登记"漂移接口在空数据时返回 NORMAL"这一假绿行为**，也没要求给漂移假数据补 `mock` 标识——本条的新增部分是后者。

**修复建议**
- `DriftMonitor` 区分三种状态：`NO_BASELINE` / `INSUFFICIENT_SAMPLES` / `NORMAL`，无基线或样本不足时 `overall_status="UNKNOWN"` 且 `recommendation="无基线数据，无法判定"`，不要返回 NORMAL；
- `/drift/detect` 在 `currentData` 为空时返回 400（或 `status=NO_DATA`），别让它"顺利通过"；
- 假数据 3 端点要么实现、要么删除、要么统一加 `_demo_meta()` 级 `mock:true + dataSource:"demo"` 并在页面打「演示」标签；
- 前端 `ModelManagePage` 对 `UNKNOWN/NO_BASELINE` 单独渲染（现有文案已经为 501 写过，复用即可）。

### P1-6　自适应归一化"双口径"：`disaster_assessment_model` 已实际接入（同输入跨时间漂移 + 跨区域窗口污染），`comprehensive_model` 声明 `applied=0` 且该声明字段被 response_model 丢弃

**位置**
- 已接入方：`ai-service/app/models/disaster_assessment_model.py:296-304`（`_dynamic_norm`）、`:318-323`（geographic 6 因子）、`:357-358`（降雨）、`:432-434`（气象扩展）、`:477-482`（地形扩展）、`:614-619`（特征贡献度）；写入窗口在 `:1236-1247`（每次 `score()` 都 `fit_one`）
- 声明未接入方：`ai-service/app/models/comprehensive_model.py:898-910`（拟合窗口）、`:990-1022`（`_maybe_use_adaptive_norms` 注释明写 `applied=0`）、`:974-981`（返回 `adaptive_normalization:{enabled:false, applied:0, note:"评分仍使用静态 NORM_MAX"}`）
- 声明被丢弃：`ai-service/app/routers/comprehensive.py:48-56` 的 `ComprehensiveResponse` 没有 `adaptive_normalization`/`derived_features`/`derived_contributions` 字段，`:76-84` 构造响应时也没带 → FastAPI 按 `response_model` 过滤，这些字段**永远不会出现在 HTTP 响应里**

**证据（为什么会漂移）**
- `adaptive_normalizer.py:107-130`：窗口 ≥10 个样本后返回 `clamp(P95*1.5, fallback*0.5, fallback*3)`；`score()` 每次都会把 `rainfall_24h / rainfall_7d / slope_deg / vegetation_cover`（`:1244-1247`）推进窗口。于是同一个输入，**第 1 次调用和第 11 次调用可以拿到不同的 `max_val`**（例如 slope 本地 P95=20° → 上限 30，风险项直接翻倍），风险分数随"调用历史"漂移；Java 侧 `AiHazardMonitorScheduler` 每 90s 调一次 `/detect-batch`，分数会自行爬升/回落而与真实数据无关。
- `/detect-batch` 的窗口 key 还是污染的：`disaster_assessment.py:261-264` 传的 `hazard_dict` 只有 `hazard_code=f"MON-{idx:03d}"`、**没有 `region_code`** → 模型 `:1232` 回落到 `hazard_code` → 全国所有 region 的第 idx 个网格点共用 `("slope_deg","MON-000")` 一个窗口，跨区域、跨轮次互相影响。
- 另一份实现（`comprehensive_model`）用同一组窗口但只做只读诊断；同一个 `get_adaptive_normalizer()` 单例被两条端点共同写入（`/comprehensive` 也会 `fit_one`，`:900-907`），互相污染对方窗口。

**影响**
- 同输入不可复现：同一隐患点、同一数据，昨天/今天两台端点返回不同 risk_score；`/comprehensive` 与 `/disaster-assessment` 对同一因子给出不同分（还有 P2-4 的权重差），前端"AI 研判"与"落库评分"对不上时无法归因。
- 对外声明"applied=0"本身也是假口径：一份模型真在用、另一份把"未用"的说明写进一个**HTTP 层看不到的字段**，等于谁都无法从响应判断到底用没用。

**修复建议**
- 二选一：① 彻底关掉 `disaster_assessment_model` 的 `_dynamic_norm`（恢复静态上限，和 comprehensive 一致），并把 `adaptive_normalization` 诊断字段真正暴露到响应里；② 真正启用：把 `max_val` 作为**每次推理的局部参数**传入（代码注释 `:997-1001` 已写明正确做法），禁止进程级全局改写，并给 `(factor, region)` 用真正的 region_code（`/detect-batch` 必须带 `region_code`，禁止用 `MON-000` 这类批次内序号当区域）。
- 两条评分路径合并为一份（见 P2-4），避免"一处接入、一处声明未接入"。
- 加回归测试：同输入连续调用 20 次分数必须恒定；`/detect-batch` 同一 region 的窗口只受该 region 影响。

### P1-7　"网关代理"与"前端直连 8001"两套形态共存：drift/multimodal 绕网关无鉴权直连，而 sentinel 明确禁止直连；CORS/端口默认值只为其中一种形态配置

**位置 / 证据**
- 网关派（正确形态）：`backend/.../ai/SentinelCompareController.java:32-35` 类注释写明"浏览器直连 8001 存在三个交付问题：生产端口不可达、ai-service 无 JWT 校验（见 KNOWN_LIMITATIONS C5）、合成图 URL 不可读"，因此全部收回网关；`frontend/src/services/satelliteCompareApi.js:1-8` 同样注释"不直连 ai-service 8001"。
- 直连派：`frontend/src/services/_architecture/domain/driftMonitorService.js:12-16`、`multiModalService.js:11-15` 都把 `http://localhost:8001/api/ai` 作为首选 base，**失败才**回落后端 501；`frontend/src/services/_architecture/domain/aiApi.js:368-372`（feedback/stats）、`_architecture/core/sentinelService.js:32-34` 又是另外两份同样的 base 解析（共 4 份，环境变量名还 2/3 个不一致）。
- 服务端无任何鉴权：`ai-service/app/main.py` 全文无 `Depends`/`Security`/中间件；`/api/ai/drift/**`、`/api/ai/multimodal/**`、`/sentinel_files` 静态挂载（`main.py:222`）均可匿名访问；`KNOWN_LIMITATIONS.md:157-160`（L-10）已登记。
- 生产默认值与直连形态冲突：`docker-compose.prod.yml:258-260` `AI_CORS_ORIGINS` 默认只允许 `http://localhost:5173`；`:277-279` 端口映射为 `127.0.0.1:8001`（仅回环）。也就是说 prod 下前端从用户浏览器直连 8001 **必然 CORS 失败/端口不可达**，直连派只在开发机成立；而开发机又恰好"无鉴权 + 8001 对 0.0.0.0 暴露"（`start-dev.ps1:493`、`docker-compose.yml:230-231`）。
- `AiDeprecatedGapController.java:24,43` 的 501 提示文案还写着"前端应直连 ai-service（默认 http://localhost:8001/api/ai）"——把与 sentinel 相反的策略固化进了对客文案。

**影响**
- 同仓两套相反的网络/鉴权假设：sentinel 已按"生产不可直连"改造完，drift/multimodal 仍按"直连可用"设计 → 生产上这两页能力必挂（或落到 501），开发上任何能访问开发机 8001 的进程都可无鉴权调用推理任务（含 `/sentinel/compose` 触发外部下载、`/model/retrain`）。
- 4 份 base URL 解析 + 3 个环境变量名（`VITE_AI_SERVICE_BASE`/`_BASE_URL`/`_URL`）意味着"给某页配好了、另一页还是默认 localhost"的配置漂移风险（P2-11 表内列全量）。

**修复建议**
1. 统一为"浏览器只走 Java 网关"：drift/multimodal 也补 `SentinelCompareController` 式代理（或直接下线这两个演示模块），删掉前端 4 份直连 base 解析，收敛到 `dashboardApi` 一个入口；
2. 若保留直连做开发便利，用 `VITE_AI_SERVICE_BASE` 单一变量 + 仅在 `import.meta.env.DEV` 下生效，并在 prod 构建里把直连路径裁掉；
3. `AiDeprecatedGapController` 文案改为"该能力已停产，请走网关/二期"，不要教用户直连；
4. 长期：给 ai-service 加服务间凭证（L-10 建议），或至少把 8001 只绑回环 + nginx 同源代理（现有 `infra/nginx/frontend.conf` 已具备条件）。

---

## P2（中等 / 轻微：口径不一致、死代码、文档失真）

### P2-1　`mock` 语义多端点不一致：同一字段承担 4 种含义，还有端点用 `degraded` 而非 `mock`

| 端点（位置） | 字段 | 该字段实际含义 |
|---|---|---|
| `detection.py:108-115`（`/detect`） | `mock` | **模型未加载**，且返回的是空 detections（不是启发式结果），`message` 仍是 `"ok"` |
| `detection.py:312-320`（`/detect-image`） | `mock` | 模型未加载 → `disaster_type=none, confidence=0`，并给了说明性 message |
| `anomaly.py:124-137` | `mock` | 输入是 1 维 → 被补齐成共线 5 维（**Java 唯一调用路径必然如此**，见 `AiController.java:707` 的 `List<Double>`） |
| `forecast.py:55-58` / `fusion.py:45` / `comprehensive.py:83` | `mock` | 启发式回退（移动平均 / 加权融合 / 维度加权） |
| `device_health.py:58,95` | **`degraded`** | 与上面同义的降级，但字段名不同（Java DTO 用 `@JsonAlias("mock")` 兜） |
| `social_media.py:344` | `mock` | **无真实数据源**（关键词启发式本身是"实现"，不是降级）→ 恒 `true` |
| `multimodal.py:121-133` | `mock` + `dataSource:"demo"` | **固定写死的演示数值**（87.5 分等） |
| `drift.py:114-249` | 无 | 硬编码假数据**没有** mock 标识 |

**影响**：MEMORY §三 的契约是"`mock:true` = 启发式回退"，但消费方无法区分"模型没加载/结果为空"（`/detect`）、"没有数据源"（social）、"编的演示值"（multimodal）和"启发式算的"（fusion）。前端标注"演示数据"只能按端点硬编码。
**建议**：统一为 `mock`（bool）+ `degradation_kind`（`model_missing|no_data_source|demo_values|heuristic`）+ `data_source`；`/detect` 的 `message` 在 mock 时不能是 `"ok"`；`device_health` 的 `degraded` 要么改名 `mock` 要么在文档里声明二者同义。

### P2-2　模型缺失/加载失败的降级路径 5 种，且与 Java 侧 503 口径混用

- ai-service 内：YOLO 缺失→空 detections+mock（`yolo_model.py:254-272`）；XGBoost 缺失→加权融合（`xgboost_model.py:113-136`）；LSTM 缺失→移动平均（`lstm_model.py:128-147`，且**含 `random.uniform(-0.1,0.1)` 抖动**，`:140`，同一输入两次结果不同）；IForest 缺失→3σ（`isolation_forest.py:109-139`）；comprehensive 缺失→维度加权（`comprehensive_model.py:627-645`）。全部 HTTP 200。
- Java 侧：`AI_ENABLED=false` 或 ai-service 不可达 → 多数 `/api/ai/**` 直接 `ApiResponse.fail(503, …)`（`AiController.java:608,627,640` 等），而 `fusion-risk` 的规则链路会降级 `RULE_ONLY`（`AiServiceClient.java:39-41` 注释）。
- 于是"AI 不可用"的对外语义在同一个平台里既有 503、又有 200+mock、又有 200+空结果、又有 200+假数据（drift/multimodal）。
**建议**：定义一份"降级字典"并让 `/api/ai/health` 暴露每模型的 `loaded/degraded` 汇总（`model/health` 已有雏形，但见 P2-5 的线程问题且未被任何调用方使用）；Java 侧区分"服务不可达(503)"与"服务可用但模型降级(200+mock)"两种语义，前端分别展示。

### P2-3　演示/合成数据残留（部分已知）：多模态 5 端点、漂移 3 端点、`detect-regions` 硬编码坐标、`/independent` 默认季节性模拟雨量（Java/Python 两份同一公式）

| 位置 | 内容 | 状态 |
|---|---|---|
| `multimodal.py:136-245`（insight/contributions/refresh/weights GET/PUT） | 固定 87.5 分、假贡献度、假权重；`_demo_meta()` 标 `mock+dataSource=demo` | **已知未修**（`KNOWN_LIMITATIONS.md` L-01，`:69-75`） |
| `drift.py:114-249` | 假趋势/假特征详情/假训练任务与假日志 | 未登记（见 P1-5） |
| `disaster_assessment.py:342-355,358-402` | `_DEFAULT_REGION_CENTERS` 12 个硬编码演示坐标（"仅 demo 用"）；`detect-regions` 端点保留 | Java 侧唯一调用 `AiServiceClient.detectHazardsForRegions` 已 `@Deprecated`（`AiServiceClient.java:490-499`），**全仓无调用点** → 端点+坐标表是死演示数据 |
| `disaster_assessment.py:577-588` | `/independent` 在 `mock_weather=true`（Java 默认 true，`AiController.java:429-434`）时用 `sin(day_of_year)` 生成假 24h/7d 雨量参与评分 | 前端有复选框（`DamageAssessmentPage.vue:85,500`）但默认勾选 |
| `AiHazardMonitorScheduler.java:273-285` | **同一公式、同一系数**（25/80、80/220、22℃、0.65）在 Java 侧再写一遍，且被真实调度器每 90s 用于"当前天气快照" → AI 监测点（`hazard_point`）的来源天气是季节性合成值 | 两语言重复实现，且不在 MEMORY 的已知清单里 |

**影响**：`/independent` 的"mock 天气"至少回传 `mock_weather_used` 且前端有勾选，属可接受；但 Java 调度器那份是**默认真实链路**，`hazard_point` 会被合成雨量驱动，对外不能称"AI 实时监测基于真实气象"。两份公式任何一侧改了系数，另一侧不会跟。
**建议**：Java 侧 `currentWeatherSnapshot()` 接真实气象服务或显式标注 `source=synthetic` 并落库；Python 侧若无干系可删除；`detect-regions`+`_DEFAULT_REGION_CENTERS` 删除（Java 已自行算网格，`AiHazardMonitorScheduler.java:242-267` 自己生成了 3×3 网格，和 Python 的 detect-regions 是**重复实现**）。

### P2-4　同一套七维度评分有两份实现，同一因子两端分数不同；`comprehensive` 气象维度未 clamp 且权重与另一份不一致

- 重复函数（成对存在，逻辑近似但**权重/归一化源不同**）：
  - 阈值表：`comprehensive_model.py:178-190` vs `disaster_assessment_model.py:131-136`
  - 维度权重：`comprehensive_model.py:168-176` (`DIMENSION_WEIGHTS`) vs `disaster_assessment_model.py:181-189` (`BASE_WEIGHTS_V2`)，数值相同但两份定义
  - 遥感/历史/社媒/辅助评分：`comprehensive_model.py:203-235/433-478/481-528/531-566` vs `disaster_assessment_model.py:391-420/514-538/540-564/566-585`（几乎逐行相同）
  - 气象评分：`comprehensive_model.py:262-267` `score=(rain*0.7 + soil_risk + level_risk)*100`（soil/water **不加权**，理论最大 130；只有传了未声明因子 `water_level_warning` 才会突破；`level_risk` 依赖该键，`data.sql`/Java 均不传 → 实际 100 封顶但公式错误）vs `disaster_assessment_model.py:436-438` `(rain*0.7 + soil*0.15 + water*0.15)*100`（规范加权，最大 100）
  - 地形评分：`comprehensive_model.py:311-387`（静态上限）vs `disaster_assessment_model.py:464-512`（`_dynamic_norm`，见 P1-6）
- `comprehensive_model.py:964` 分支 `enriched_factors.get("_derived_")` 永远为 `None`（`compute_derived_features` 只写 `_derived_xxx` 单键），是死分支。
- 传感器维度永远启发式：`comprehensive_model.py:390-396` 直接调 `fusion._run_weighted(...)`，**即使 XGBoost 已加载也不使用模型**；而 `/fusion-risk` 用模型 → 同一 7 因子两端口径不同，且 `/comprehensive` 的 `overall.mock=false` 会让人误以为全部维度都是模型输出。

**建议**：合并为一份评分实现（共享 `LEVEL_THRESHOLDS`/`NORM_MAX`/维度权重/评分函数），`comprehensive` 的 sensor 维度在有 `model_loaded` 时用模型并单列 `dimensions.sensor.mock`；修 P2-4 的气象公式并 clamp 到 100。

### P2-5　同步/异步与线程池政策不一致：`detection` 明确把 CPU 推理放线程池，`model_info /health` 与 `sentinel` 在 `async def` 里同步跑推理/图像处理

- 正面样板：`detection.py:32-37`（`ThreadPoolExecutor(max_workers=2)`）、`:88-97`（`wait_for` + 504，且注释 R5 P2-3 已说明"超时不能取消已提交任务"的取舍）
- 反例 1：`model_info.py:191-278` `async def model_health()` 里串行跑 YOLO 推理（`det.detect(dummy)`）、LSTM、IForest、XGBoost、comprehensive **全部同步** → 单次健康检查可冻结事件循环；同一文件 `:290-295` 的 `model_retrain` 注释恰好写着"若改成 async def 却直接调用训练，整个服务的健康检查与其它请求都会被冻住"——**文件内部自相矛盾**。
- 反例 2：`sentinel.py:258-349`（`async def _run_compose`）内部直接调 `PIL/numpy`（`_generate_mock_*:634-667,705-729`、`_compose_*`、`_render_heatmap:1092-1153`、`_pixel_diff_magnitude:1082-1089`、`_load_image_to_array:974-1043`），`_run_diff:876-971` 同样；`resp.read()`（`:498,1030`）也无分块。大图（文档里 512×512 mock 也是 262k 像素）会把事件循环占住，另加 `uvicorn --limit-concurrency 32`（`Dockerfile:40`）的下限语义。
- 单例/热加载不一致：`model_info.py:152-183` 的 `/model/reload` 只重置模块级 `_future/_forecaster/_detector`，但 `device_health_model.py:92-95` 的 `DeviceHealthScorer` 在构造时**缓存**了 `self._anomaly`/`self._forecaster`，且 `:349-354` 是进程级单例 → reload LSTM/IForest 后设备健康评分仍用旧实例，与端点文案"训练后立即热加载"不符。

**建议**：`model_health`、sentinel 的 PIL/numpy 全部走 `run_in_executor`（或改成同步 `def` 由 Starlette 调度到 anyio 线程，与 `fusion.py:35-36` 的注释一致）；`/model/reload` 同步重建 `DeviceHealthScorer`（或让 scorer 每次 `get_*` 动态取单例）。

### P2-6　后台任务/缓存无清理、`cancel` 不真正取消

- `sentinel.py:150-156`：`_jobs`/`_diff_jobs` 是模块级 dict，`_init_job/_init_diff_job` 只增不删，`expiresAt` 仅写在结果里（`:754`），无 TTL/清理线程；`_jobs_lock` 只在 async 更新函数用，`_update_job`（`:183-189`）无锁但被 `async def cancel_job` 调（单线程事件循环下目前安全，属隐式假设）。
- `sentinel.py:1262-1281` `cancel_job` 只把 status 改成 `CANCELLED`，**没有取消 `BackgroundTasks` 里的 `_run_compose`**；`_run_compose` 结束仍会 `_update_job_async(status=SUCCEEDED)`（`:340`）覆盖取消状态 → 前端"取消"按钮不生效（`satelliteCompareApi.js:55-63` 调用）。
- `drift_monitor.py:360-367` `_monitors` 以请求里的任意 `model_id` 为 key 缓存，永不过期（可被匿名调用放大内存，叠加 P1-7）。
- `adaptive_normalizer._data` 以 `(factor, region)` 为 key 且 region 来自请求（`_indep_{idx}`/`MON-000`/hazard_code），长期运行单调增长（每 key 200 个 float，量级不大但无上限）。

**建议**：任务表加 TTL（如 1h）+ 定期清理；cancel 用 `asyncio.Task` 持有句柄并 `task.cancel()`（`BackgroundTasks` 拿不到句柄，需要改 `asyncio.create_task` 或自建任务表）；`_monitors` 加 LRU/上限。

### P2-7　遗留第二套服务/入口 + 死代码 + 未使用端点

| 类别 | 位置 | 证据 |
|---|---|---|
| 遗留 standalone Web 服务（约 3.1 千行） | `ai-service/web.py`（2415 行，自建第二个 `FastAPI` app：`:1361`，`/detect/run`、`/forecast/run`、`/batch/*` 等） + `ai-service/ai.py`（681 行 CLI，`web` 子命令拉起 web.py：`ai.py:487-504`） | 全仓检索：Dockerfile 只 `COPY app`（`Dockerfile:15`）、compose 只挂 `./ai-service:/app`、`start-dev.ps1:493`/`start-dev.sh:330` 均直接 `uvicorn app.main:app`，**无任何启动脚本引用 web.py/ai.py**；`docs/项目结构梳理报告.md:40` 也记录"实测无 Dockerfile/compose/启动脚本引用后两者" |
| web.py 内部重复实现 | `web.py:1440-1495`（`_internal/models` 又实现一遍模型清单） | 与 `model_info.py:61-119`、`dashboard.py:324-383` 三份同功能代码 |
| 空包 | `ai-service/app/schemas/__init__.py`（0 行）、`common.py`（0 行） | README:17 还宣称"schemas Pydantic schemas"，实际无任何 import（全仓 grep `app.schemas` 0 命中） |
| 包 `__init__` 自引用 | `app/training/__init__.py:2` `from app.training import train_xgboost` | 包初始化期自导入，靠 Python 的 "from package import submodule" 兜底才能工作；无调用方需要该属性 |
| 0 引用函数 | `yolo_model.py:55-75` `infer_num_classes`/`detect_business_model`（只有彼此调用；`_load` 里又内联了一份同样的 shape 推断 `:106-113`）；`xgboost_model.py:149-154` `device_weighted_score`；`feature_engineering.py:49-55` `DEFAULT_DERIVED`、`:225-248` `get_derived_feature_descriptions`（`get_derived_feature_names` 仅测试用）；`drift_monitor.py:325-356` `get_trend`；`feedback_store.py:144-147` `list_by_hazard`（仅测试）；`http_client.py:161-176` `gather_silent`；`sentinel.py:61-74` 4 个 `_copernicus_*/_gee_*` 读取函数 | 全仓 grep，均为定义处以外 0 命中（测试除外） |
| 死导入 | `stac_clients.py:25` `DEFAULT_TIMEOUT`；`multimodal.py:11` `FusionEngine`；`drift.py:8,11` `HTTPException`/`DriftMonitor`；`lstm_model.py:13,15` `math`/`random` 用法局部；`comprehensive_model.py:137` `math` 有用但 `List/Optional` 组合冗余 | 逐处核对引用 |
| 未使用端点（Java/frontend/scripts 均无调用） | `POST /api/ai/anomaly/batch`（`anomaly.py:174`）、`POST /api/ai/forecast/batch`（`forecast.py:90`）、`GET /api/ai/model/health`（`model_info.py:191`）、`GET /api/ai/feedback/list`（`feedback.py:93`）、`GET /api/ai/dashboard/summary`（`dashboard.py:307`，HTML 自己都用 `/health`+`/model/info`）、`POST /api/ai/disaster-assessment/detect-regions`（`disaster_assessment.py:358`） | 全仓 grep 端点串：仅定义/文档；`web.py:1519-1526` 与 `dashboard.py:133-152` 只把它们列在"端点速查"里 |
| 三份模型清单实现 | `model_info.py:61-119`、`dashboard.py:324-383`（`all_models` 循环逐字段重复）、`web.py:1440-1495` | 同字段名/同 meta 读取逻辑三处维护 |
| 六类数据源大表（约 120 行 doc）写三份 | `comprehensive_model.py:1-132`、`disaster_assessment_model.py:1-120`、`main.py:121-158`（FastAPI `description` 又把六类数据源抄一遍） | 同一表格三份，改一处必漏两处 |
| `_RISK_ORDER` 两份 | `disaster_assessment.py:237` 与 `:591` | 完全相同的 dict |
| `RetrainRequest` 三份 | `model_info.py:281-287`、`feedback.py:61-63`、`drift.py:36-40` | 同名不同字段（见 P1-2） |
| `TinyLSTM` 三份 | `lstm_model.py:55-63`、`scripts/training/train_lstm_displacement.py:109-117`、`scripts/download_models.py:263-271` | 后两份 `output_size` 不一致（1 vs 7，见 P1-1） |
| 重复测试 | `scripts/test_api_endpoints.py`（手写 HTTP 探针，端点路径已过时：`:12` `/api/ai/model-info`、`:13` `/api/ai/fusion` 均 404）vs `tests/test_api_endpoints.py`；`scripts/quick_api_check.py` 的 `BASE_URL="http://127.0.0.1:8765"`（`:6`，与 8001 不符，按 README 跑必然全部失败） | 文件级对照 |
| 端口冲突 | `web.py:89`/`ai.py:475` 遗留 Web UI 默认 **8080**，与后端 Spring Boot 默认 8080（`start-dev`/`docs/DEVELOPER.md`）撞车 | 两进程同时按默认端口启动必有一方失败 |

**建议**：`web.py`/`ai.py` 与 `scripts/` 下与运行期无关的旧工具整体归档（或移到 `tools/legacy/` 并在 README 标注）；删 0 引用函数/空包/死导入；未使用端点按"保留但标注 experimental"或删除；三份模型清单收敛到 `model_info`。

### P2-8　契约测试/冒烟脚本失真：`tests/test_api_endpoints.py` 用 `except Exception` 把断言失败吞成 `skip`，全部跳过也能"全绿"

- `ai-service/tests/test_api_endpoints.py` 里的路径**大多已不存在**：`/api/ai/fusion`（`:149,172`，实际 `/fusion-risk`）、`/api/ai/comprehensive-analysis`（`:198`，实际 `/comprehensive` 或 Java 端点）、`/api/ai/disaster-assessment`（`:222`，实际 `/disaster-assessment/score`）；payload 也过时（`image_url` 发给 multipart 的 `/detect`、`values`/`horizon_days` 发给 `/anomaly`/`/forecast`）。
- 每个用例都包在 `try: ... except Exception: pytest.skip(...)` 里（`:65-74,88-95,108-116,148-156,197-204,221-228`）——**404 导致的 `assert` 失败是 `AssertionError`，也属 `Exception`，会被吞成 skip**。于是这组"API 端点测试"在任何路由漂移下都不会失败，只会被静默跳过，给人"契约测试存在"的错觉。
- 叠加 `scripts/test_api_endpoints.py`/`quick_api_check.py` 的过时路径与错端口（见 P2-7），三套冒烟脚本没有一套能真实发现路由漂移（本项目实际漂移的例子：`/api/ai/fusion`→`/fusion-risk`、`/model/retrain` 重复、`/dashboard/summary` 死端点）。
**建议**：删掉 `try/except: skip`，改为"用 `app.routes` 快照 + 显式请求断言 200/字段"；在 `scripts/audit` 增加"文档/脚本里出现的 `/api/ai/*` 路径必须存在于 `app.routes`"的门禁（类似 `check-endpoint-parity`，方向反过来）。

### P2-9　文档/注释与实际代码漂移（会误导后续维护与对外讲解）

| 位置 | 说法 | 实际 |
|---|---|---|
| `ai-service/README.md:196-211` | "12 个真实灾害场景测试 **100% 准确率**" + 一张分数表 | 源头是 `scripts/test_disaster_scenarios.py:33` 起的"期望最低/最高等级"**自检脚本**（用同一模型 + 宽区间），不是准确率评测；MEMORY §一/§五 明确要求不得宣称真实精度，`models/README.md:94` 也承认其余模型训练样本是合成 |
| `ai-service/README.md:106-112` | 路径策略"MODELS_DIR → ai-service/models → `<repo>/models`（兼容旧版缺失回退）" | `app/config.py:17-28` 只有 env 与 self 两级，**没有 repo/models 回退** |
| `ai-service/README.md:137-141` | "Docker 模式 CMD: python scripts/run_service.py …" | `Dockerfile:40` 是 `uvicorn app.main:app --limit-concurrency 32`，且镜像不 COPY `scripts/` |
| `ai-service/models/README.md:14-15,96-104` | LSTM/IF 训练用 `scripts/training/train_lstm_displacement.py`/`train_isolation_forest.py`；XGBoost 用 `train_fusion_v3.py --data ./any.csv` | 前两个脚本产出的模型与推理不兼容（P1-1）；`train_fusion_v3.py` **没有 `--data` 参数**（`:226-230` 只有 `--n-samples/--seed/--output-dir`） |
| `scripts/training/README.md:43-44` | "v1 → v2：二分类改回归，33→36 特征"；`:49` "config.py 兼容 models/（项目根）" | `train_fusion_v2.py:152` 仍是 `binary:logistic`（v2→v3 才改回归）；config.py 无该回退 |
| `detection.py:69` | `/detect` "自动降级为启发式 mock（返回随机 0-2 个检测框）" | `yolo_model.py:254-272` 已改为**返回空列表**不造框 |
| `AiImageResponse.java:8-10`（不是 `DetectionResult.java`，后者无此描述） | "/detect 返回原始检测框（COCO 类别名）" | 业务模型加载时返回 crack/landslide；且 `/detect` 与 `/detect-image` 的差异不在"COCO vs 业务"而在"文件上传 vs URL 下载" |
| `AiController.java:688-691` | `enrichDimensions` 给 `dimensions.image` 注入默认 `image_source` | Python `/comprehensive` 返回的 7 维度是 `remote_sensing/meteorology/terrain/sensor/historical/social_media/supporting`（`comprehensive_model.py:915-924`），**没有 `image` 键** → 该兜底永不生效，Java 注释里的"四维度 image/precipitation/geographic/device"（`ComprehensiveAnalysisResult.java:17`）也是旧 schema |
| `dashboard.py:133-152` | 端点速查列 `/api/ai/disaster-assessment`、`/api/ai/social-media`、`/api/ai/device-health` | 实际分别是 `/disaster-assessment/score`、`/social-media/analyze`、`/device-health/score`（点击即 404/405） |
| `ai-service/.env.example:30-34` + `sentinel.py:232-243` | 支持 `provider="gee"`，需 `GEE_SERVICE_ACCOUNT_JSON/GEE_PROJECT` | 全仓无任何 earth-engine 实现（仅 `sentinel.py:67-71` 两个**无人调用**的读取函数）；`provider=="gee"` 在 `:266,269` 的分支里既不跳过 CDSE 也不走 mock，等于**静默退化成 auto**；同理"`copernicus` 强制 CDSE、凭证缺失则报错"（`:239-241`）未实现——`search_with_fallback` 在未配置凭证时静默跳过 CDSE（`stac_clients.py:239-244`） |
| `sentinel.py:59-60` | 注释"必须用函数读取环境变量，不能顶层捕获" | `sentinel.py:95-102` 的 `_ALLOWED_HOSTS` 就是模块顶层读取，自相矛盾（SSRF 白名单因此无法在 import 后再改） |

**建议**：以上逐条订正；README 的"100% 准确率"改成"12 场景自检（非准确率评测）"，并引用 `models/README.md` 的 test mAP50=0.327；删除 GEE 相关配置项与函数、或真实现后再宣称。

### P2-10　类型/契约层面的死分支：`factors: Dict[str, float]` 却按字符串元数据分支；meta 字段/历史文件读取对不上

- **字符串元数据永远收不到**：`AiServiceClient.java:279-302` `filterNumericFactors()` 在 Java 侧把所有字符串因子剥掉（`image_source`/`land_use_type`/`geology_label`），而 `DisasterAssessmentRequest.factors: Dict[str, float]`（`disaster_assessment.py:54`）本来就只收数值 → Python 里基于字符串的分支全部死掉：
  - `disaster_assessment_model.py:407-411,475`（`image_source` → `source_label`；`geology_label` 只用于展示）
  - `:359-360`（`geology_label in ("碳酸盐岩","可溶性岩")` → "岩溶塌陷风险" flag 永不出现）
  - `:520-526`（`land_use_type` → land_risk；永远 `unknown` 的 0.2 默认）
  - `comprehensive_model.py:209-232` 的 `image_source/source_label` 同理
  同时 `/detect-batch` 的 `_factors_from_point` 又主动把这些字符串写成 `0.0`（`disaster_assessment.py:182,223`），把"缺失"伪造成"有值"，并让 `_detect_data_availability`（`:894-933`）把维度判为"有真实数据"——与直接调 `/disaster-assessment/score` 的权重口径不一致（后者不会被预填地形/天气默认值，见 `:186-216`）。
- **`/model/info` 的 meta 字段/历史文件对不上**：
  - `model_info.py:104-112` 读 `samples_train/pos_rate_train/scale_pos_weight/training_seconds` —— 这些字段只有 `app/training/train_xgboost.py:294-314` 写；线上 `models/xgboost_fusion.meta.json` 是 v3（`n_train/n_val/...`，`:5-8`）→ 这几项**永远 null**（直到有人跑 retrain，而 retrain 会破坏模型，见 P1-1）。
  - `model_info.py:312-327` `_list_history_files()` glob `xgboost_fusion-*.json`，而磁盘 `models/history/` 里是 `xgboost_fusion.backup.*.json`（`.` 不是 `-`；`train_fusion_v3.py:140-144` 写的也是 `.backup.` 到 models 根目录）→ `history_files` **永远 `[]`**。
- **`social_alert_level` 的 `-1` 归一化**已在 R10 前修过（`comprehensive_model.py:486-488` 注释），此处不重复；`disaster_assessment_model.py:542-545` 也已是 0-4 口径（验证正确）。
**建议**：① 要么把 `image_source/land_use_type/geology_label` 从 Pydantic 模型中删掉（承认不传），要么把 factors 改成 `Dict[str, Any]` 并在 Java 侧只过滤"确实不是数值且模型没声明支持"的键；`_factors_from_point` 不要用 0.0 冒充缺失；② `_list_history_files` 改成 `glob("*.json")` 并按前缀匹配两种命名；`xgboost_meta` 字段按 meta 实际版本分支读取（或统一 v3 meta schema）。

### P2-11　前端 4 份 AI base URL 解析（同一语义 2/3 个环境变量名），与"禁止直连"策略冲突的配置面

- 4 份重复实现：`_architecture/domain/driftMonitorService.js:12-16`、`_architecture/domain/multiModalService.js:11-15`、`_architecture/domain/aiApi.js:368-372`、`_architecture/core/sentinelService.js:32-34`；变量名分别支持 3/3/2/2 个（`VITE_AI_SERVICE_BASE`、`VITE_AI_SERVICE_BASE_URL`、`VITE_AI_SERVICE_URL`），默认值都是 `http://localhost:8001/api/ai`；`:368` 的 aiApi 只在 feedback stats 里用。
- 与 P1-7 的"浏览器不直连"决策冲突：sentinel 已改为 `satelliteCompareApi.js:20-21` 走 `VITE_API_BASE_URL` + 网关，上述 4 份仍是直连，且 fallback 行为各不相同（drift/multimodal 在失败时回落后端 501 并转语义化错误，aiApi/sentinelService 直接抛）。
**建议**：收敛到一处 `getAiServiceBase()`（仅 DEV 使用），其余改走网关；若保留直连，统一变量名为 `VITE_AI_SERVICE_BASE` 并在文档里只写这一个。

---

## 冗余代码清单（表格）

> 判定"未使用"前均做了全仓检索（`*.py/*.java/*.js/*.vue/*.yml/*.md/*.sql`）；测试中出现的引用已单独注明。

| # | 冗余项 | 位置 | 证据 | 处理建议 |
|---|---|---|---|---|
| R1 | 遗留 standalone Web UI + CLI（约 3.1k 行） | `ai-service/web.py`、`ai-service/ai.py` | Dockerfile/compose/start-dev 均直接 `uvicorn app.main:app`；全仓仅 docs 引用；`docs/项目结构梳理报告.md:40` 记录"无引用" | 归档/删除，或移入 `tools/legacy/` 并在 README 标非交付物 |
| R2 | 两套七维度评分模型（评分函数成对重复） | `app/models/comprehensive_model.py`（1079 行） vs `app/models/disaster_assessment_model.py`（1381 行） | 见 P2-4 函数对照行号 | 抽公共 `scoring.py`，两模型只保留维度编排差异 |
| R3 | 风险等级阈值 80/60/35 五处 | `comprehensive_model.py:178-183`、`disaster_assessment_model.py:131-136`、`xgboost_model.py:139-146`、`fusion_engine.py:62-67`、`device_health_model.py:44-51`（40 档） | 逐处硬编码 | 收敛到一个 `risk_levels.py` 常量 + 函数 |
| R4 | 归一化上限表 6 份 | `comprehensive_model.py:147-159`、`disaster_assessment_model.py:167-178`、`xgboost_model.py:34-42`、`adaptive_normalizer.py:32-46`、`scripts/training/train_fusion_v3.py:26-34`、`train_comprehensive_v2.py:70-...` | 数值完全相同（150/400/800/60/5000/5…） | 由 `app/config` 或 `models/constants.py` 单一提供；训练脚本 import 同一份 |
| R5 | 维度权重表两份 | `comprehensive_model.py:168-176` vs `disaster_assessment_model.py:181-189` | 数值相同 | 合并 |
| R6 | 灾种/类别表 4 份（服务内） | `yolo_model.py:24-45`、`detection.py:159-167`、`disaster_assessment_model.py:146-163`、`social_media.py:54-86`（关键词含灾种） | 值域不一致见 P1-4 | 以 `sys_dict.disaster_type` 生成一份共享常量 |
| R7 | `TinyLSTM` 3 份 | `lstm_model.py:55-63`、`scripts/training/train_lstm_displacement.py:109-117`、`scripts/download_models.py:263-271` | `output_size` 1 vs 7 | 只保留 `download_models.py` 版并让训练脚本 import `lstm_model` 的定义 |
| R8 | 训练脚本 6 个写 2 个线上模型文件 | `scripts/training/train_fusion_v1/v2/v3.py`、`train_comprehensive_v1/v2.py`、`app/training/train_xgboost.py` | 目标文件名见 P1-1 | 归档废弃版本，输出默认写 `models/history/` |
| R9 | `RetrainRequest` 3 份 + retrain 端点 3 个 | `model_info.py:281-287`、`feedback.py:61-63`、`drift.py:36-40,206-249` | 同名不同字段；`/model/retrain` 重复注册；`/drift/retrain` 只回假 jobId | 合并为 1 个真实训练入口 |
| R10 | 模型清单实现 3 份 | `model_info.py:61-119`、`dashboard.py:324-383`、`web.py:1440-1495` | 同字段循环三处 | dashboard 直接调 `model_info` 函数 |
| R11 | 0 引用函数/常量 | `yolo_model.py:55-75`、`xgboost_model.py:149-154`、`feature_engineering.py:49-55,225-248`、`drift_monitor.py:325-356`、`feedback_store.py:144-147`、`http_client.py:161-176`、`sentinel.py:61-74` | grep 定义外 0 命中（测试除外） | 删除 |
| R12 | 死导入 | `stac_clients.py:25`、`multimodal.py:11`、`drift.py:8,11` | 逐文件核引用 | 删除 |
| R13 | 空 Python 包 | `app/schemas/__init__.py`、`common.py`（均 0 行） | 全仓无 `app.schemas` 引用 | 删除（README 同步） |
| R14 | 未使用端点 6 个 | `anomaly.py:174`、`forecast.py:90`、`model_info.py:191`、`feedback.py:93`、`dashboard.py:307`、`disaster_assessment.py:358` | Java/前端/脚本均无调用（grep 端点串） | 删除或标 `experimental` |
| R15 | 重复测试/冒烟脚本 | `scripts/test_api_endpoints.py`、`scripts/quick_api_check.py`、`scripts/test_models.py`、`scripts/test_yolo.py`、`scripts/test_comprehensive_v2.py` vs `tests/*` | 手写 HTTP 探针 vs pytest 套件；路径/端口过时 | 删 scripts 侧，保留 pytest 并修断言 |
| R16 | 重复 doc（六类数据源大表） | `comprehensive_model.py:1-132`、`disaster_assessment_model.py:1-120`、`main.py:121-158` | 同表三份 | 收敛到 `docs/` 一处，代码内链引用 |
| R17 | `_RISK_ORDER` 两份 | `disaster_assessment.py:237,591` | 完全一致 | 合并 |
| R18 | 前端 AI base URL 4 份 | 见 P2-11 | 3 种变量名 | 收敛一处 |
| R19 | Java/Python 季节性模拟天气公式两份 | `AiHazardMonitorScheduler.java:273-285` vs `disaster_assessment.py:577-588` | 系数逐项相同 | 删除 Python 侧或抽配置；Java 接真实气象 |
| R20 | 演示/固定数据端点 | `multimodal.py:136-245`（5 个）、`drift.py:114-249`（3 个）、`disaster_assessment.py:342-402`（默认坐标） | 见 P2-3；L-01 已登记多模态/漂移为"演示能力" | 实现或删除，至少统一 `mock:true` |

---

## 逻辑冲突清单（表格）

| # | 冲突 | 两侧位置 | 证据 | 影响 | 分级 |
|---|---|---|---|---|---|
| C1 | 训练 objective/输出形状 vs 推理回归契约 | `train_xgboost.py:252`、`train_fusion_v1/v2.py:102/152`、`train_comprehensive_v1.py:322` ⇄ `xgboost_model.py:90-111`、`comprehensive_model.py:647-683` | 二分类输出 [0,1] 被当 0-100；推理不校验 meta objective | 分数全 0/全 LOW，`mock:false` 不报错 | P1-1 |
| C2 | 同一路由重复注册 | `feedback.py:119` vs `model_info.py:287`；`main.py:193-194` | 同 method+path；先注册者生效 | 后者不可达；两版请求/响应 schema 不同 | P1-2 |
| C3 | 训练架构 vs 推理架构（LSTM/IForest） | `train_lstm_displacement.py:119,145-147` ⇄ `lstm_model.py:65-66,38`；`train_isolation_forest.py:37` ⇄ `isolation_forest.py:30-33` | 1 输出 vs 7 输出；1 特征 vs 5 特征；norm 写 `.meta.json` 而推理读 `.norm.json` | `load_state_dict` 尺寸不符/ predict 特征数不符 → 静默落回启发式 | P1-1 |
| C4 | 图像置信度阈值 | `yolo_model.py:82`(0.35) ⇄ `ImageAnalysisListener.java:35,94-101`(0.8)、`:188-199`(0.85/0.90/0.95) | 两侧无共享常量 | 0.35–0.8 检出被判"未确认"，系统漏报 | P1-3 |
| C5 | 灾种值域 | `detection.py:159-167`、`disaster_assessment_model.py:146-163`、`data.sql:17-30`、`WarningService.java:369-384`、`hazardTypes.js:19-49`、`schema.sql:386`、`HazardService.java:238-241` | 英文 11 码 / 中文 6 类 / 中文混存；"塌陷"缺码；crack→collapse 而字典有 ground_fissure | 同列混存中英；去重/筛选/向导语义错 | P1-4 |
| C6 | `mock` 语义 4 种 + 字段名 `mock`/`degraded` | 见 P2-1 表 | 同一字段 4 含义 | 消费方无法区分"缺失/无源/演示/启发式" | P2-1 |
| C7 | 模型缺失降级路径 200 vs Java 503 | ai-service 各 `_load` 降级（全部 200） ⇄ `AiController.java:608,627,640` 等 503 | 同平台两套"AI 不可用"语义 | 前端/运维无法统一判断 | P2-2 |
| C8 | 自适应归一化"未接入"声明 vs 实际接入 | `comprehensive_model.py:974-981` ⇄ `disaster_assessment_model.py:296-304,318-323,432-434,1244-1247` | 后者真用 `_dynamic_norm`；`/detect-batch` 用 `MON-000` 当 region | 同输入跨时间/跨区域分数漂移；声明字段还被 response_model 丢弃 | P1-6 |
| C9 | 两份评分实现同因子不同权重 | `comprehensive_model.py:262-267` ⇄ `disaster_assessment_model.py:436-438` | 气象维度 0.7+soil+water vs 0.7+0.15soil+0.15water；前者未 clamp | 两端点分数不可比 | P2-4 |
| C10 | 模型热加载只覆盖部分单例 | `model_info.py:152-183` ⇄ `device_health_model.py:92-95,349-354` | scorer 缓存 `_anomaly/_forecaster` | reload 后设备健康仍用旧模型 | P2-5 |
| C11 | 同步 CPU 推理在 async 路由 | `model_info.py:191-278`、`sentinel.py:258-349,876-971,974-1153` ⇄ `detection.py:32-37` 的线程池政策 | 同文件 `model_info.py:290-295` 自己写明不能这么干 | 单请求冻结事件循环/健康检查超时 | P2-5 |
| C12 | cancel 语义 | `sentinel.py:1262-1281` vs `:340` | cancel 只改状态，后台任务继续并覆盖为 SUCCEEDED | 前端"取消"无效 | P2-6 |
| C13 | 字符串元数据被剥掉 vs 代码按字符串分支 | `AiServiceClient.java:279-302`、`disaster_assessment.py:54` ⇄ `disaster_assessment_model.py:359-360,407-411,520-526` | `factors: Dict[str,float]` + Java 过滤 | geology_label/land_use/image_source 分支永远不生效 | P2-10 |
| C14 | 端口/网关形态 | `SentinelCompareController.java:32-35`（禁直连）⇄ `driftMonitorService.js:12-16`/`multiModalService.js:11-15`（首选直连）+ `docker-compose.prod.yml:258-260,277-279` + `start-dev.ps1:493` | 同仓两套相反假设 | 生产两页必挂；开发机无鉴权暴露 | P1-7 |
| C15 | `detect-batch` 预填默认值 vs 直接 `/score` 不预填 | `disaster_assessment.py:186-216`（预填地形/天气默认值）⇄ `:550-588`（independent 不预填） | 同一 `model.score()` 两条入口的 `dim_has_data` 不同 → active dims/权重不同 | `/detect-batch` 与 `/score` 同输入不同分；v1 稀疏回退在 batch 永不触发 | P2-4 |
| C16 | `history_files`/`xgboost_meta` 读取字段 | `model_info.py:104-112,312-327` ⇄ `models/xgboost_fusion.meta.json:2-56`、`train_fusion_v3.py:140-144` | glob 前缀不符；meta schema 两套 | 端点字段恒 null/空 | P2-10 |

---

## 验证为正确的部分（避免只报问题）

| 项 | 证据 | 结论 |
|---|---|---|
| XGBoost 训练/推理特征**顺序与数量**一致 | 推理 `xgboost_model.py:23-31,98-100` 的 `list(DEFAULT_WEIGHTS.keys())` = `models/xgboost_fusion.meta.json:10-18` = `train_fusion_v3.py:21-24`；归一化上限也与 meta `norm_max`（`:19-27`）完全一致 | ✅ 一致（v3 路径） |
| Comprehensive 36 特征顺序一致 | `comprehensive_model.py:655-677` vs `models/comprehensive_v2.meta.json:9-46`，逐项核对 36/36 相同 | ✅ |
| v3/v2 训练目标与推理输出口径一致 | `train_fusion_v3.py:120`、`train_comprehensive_v2.py:477` 均 `reg:squarederror`；推理不再 `*100`（`xgboost_model.py:95-104` 注释与代码一致） | ✅（问题只在"别的脚本也能写同一文件"） |
| YOLO 业务模型/COCO-80 判定与"不把 COCO 框当灾种" | `yolo_model.py:95-132`（按输出维度判定 `is_business_model`）；`detection.py:309-328`：mock 或非业务模型 → `disaster_type=none, confidence=0`，并给中文说明 | ✅ 防"对着车报泥石流"的防线在 |
| 模型缺失时 YOLO 不再伪造检测框 | `yolo_model.py:254-272` 明确返回空 detections + `mock:true` + warning（对比历史版本随机 0-2 框） | ✅（但 warning 未进响应，见 P2-1） |
| 图像/差异两个下载入口的 SSRF 防护 | `detection.py:143-219`（主机白名单、禁 file://、禁重定向、分块限 10MB）；`sentinel.py:105-137,974-1043`（file:// 仅可信分支且必须落在 SENTINEL_STORAGE_DIR 内、`is_relative_to` 校验） | ✅ |
| Sentinel 真实/演示数据诚实标注 | `sentinel.py:733-762` `dataSource=real/mock` + `fallbackReason`；`SentinelCompareController.java:213-239` 递归改写图片 URL；前端 `satelliteComparePage` 只在 `dataSource==='real'` 时展示差异图（`satelliteCompareUtils.js:22-30`） | ✅ |
| 自适应归一化器本身的并发安全 | `adaptive_normalizer.py:87-130` `threading.Lock` + 双检单例；`feedback_store` 同样有锁 + JSONL 追加（`:83-136`）；`HttpClientHolder` 单例 + lifespan 关闭（`http_client.py:54-85`、`main.py:52-71`） | ✅（问题在"是否该接入、接入到哪"） |
| 8001 端口在服务侧自洽 | `Dockerfile:28-40`、`docker-compose.yml:220-231`、`docker-compose.prod.yml:249-279`、`start-dev.ps1:493`、`start-dev.sh:330`、`app/main.py:184`、`scripts/run_service.py:51` 全部 8001；ai-service 源码内无 8000 残留 | ✅（8000 只在 `docs/DEVELOPER.md` 的历史描述，MEMORY 已记录） |
| 前后端 snake_case ↔ camelCase 契约 | `AiImageResponse.java:23-31`（`disasterType`/`disaster_type` 双别名）、`DetectionResult.java:12-18`、`FusionRiskResult.java`、`AnomalyResult.java` 的 `@JsonAlias` 与 Python 模型字段逐项对照 | ✅ |
| 5 个模型降级都"能返回"而非崩 500 | 各 `_load` 异常均捕获并降级（`yolo_model.py:129-132`、`xgboost_model.py:65-68`、`lstm_model.py:71-74`、`isolation_forest.py:55-58`、`comprehensive_model.py:619-622`） | ✅（语义可区分性另见 P2-2） |
| 前台线程池取舍有注释兜底 | `detection.py:79-97` 明确写出 `wait_for` 无法取消已提交任务，并给运维缓解建议 | ✅ 文档化取舍 |
| 单例 reload 覆盖 YOLO/融合/综合/LSTM/IF 五个模块 | `model_info.py:122-188` 逐模型重置 | ✅（未覆盖 device_health 缓存，见 C10） |

**无法在本环境验证（不是"已验证"）**：未启动服务、未跑 pytest、未跑训练、未跑 `git`，因此"线上模型当前是哪份脚本产的""/model/retrain 实跑后分数是否归零""重复路由实际命中哪一个"属**代码推导**结论；建议按「待确认项」的 5 分钟验证法确认后再动手。

---

## 待确认项

1. **重复路由的实际命中**（P1-2）：只读环境未启服务。验证法：`uvicorn app.main:app --port 18001` 后 `GET /openapi.json` 看 `/api/ai/model/retrain` 的 `requestBody` schema 是 `{min_samples,force}`（生效版）还是 `{samples,val_ratio,force}`（被遮挡版），或发一个空 body POST 看返回 `{code,message}` 还是 `{status,...}`。
2. **`/model/retrain` 实跑影响**（P1-1）：建议在隔离副本上跑（复制 `ai-service/models` 到临时目录、`MODELS_DIR` 指过去），确认二分类模型被加载后 `/fusion-risk` 的 `risk_score` 是否落到 0-1；本轮因"禁止修改仓库/不跑训练"未做。
3. **`crack` 的灾种语义**（P1-4）：`crack→collapse` 是历史选择（`detection.py:160` 注释写"裂缝/剥落→崩塌类灾种的前兆"）。究竟应映射 `ground_fissure`（地裂缝）还是 `collapse`（崩塌），需要产品/领域口径确认；"塌陷"是否要进 `sys_dict` 同样需要决策。
4. **`hazard_point.hazard_type` 值域是否有外部约束**：`schema.sql:102` 只是 `VARCHAR(64)`、无 FK/CHECK；`schema.sql:386` 注释声明英文码。是否还有数据库迁移/字典校验在运行库上生效，本环境未连库确认（按 MEMORY 要求未连真实库）。
5. **`ai-service/scripts/` 的定位**：`scripts/` 是否作为"交付给运维的工具集"保留？若是，则 `test_*`/`quick_api_check` 的路径与端口错误必须修；若纯开发遗留，建议整体归档（P2-7）。
6. **前端是否还有页面依赖直连 8001**（P1-7/P2-11）：已确认 drift/multimodal/feedback-stats/sentinelService 4 处；是否有移动端/未在 `frontend/src` 的调用方（如 `frontend/tests/`、`scripts/delivery/` 下的 mjs 验证脚本）需另查——本报告只覆盖 `frontend/src`。
7. **Java 侧 `AiController` 其余 AI 端点对 `mock` 的处理**（P2-2）：`/models/*`、`/chat`、`/welcome-prompts` 等走本地库/模板，未逐个核对是否有伪装为 AI 的演示数据（超出 ai-service 范围，留给后端 review）。
8. **`SENTINEL_STORAGE_DIR` 相对路径基准**：`.env.example:26` 写 `outputs/sentinel`（相对），`sentinel.py:76-83` 相对 cwd 解析，`app/config.py:38-44` 也用 env；若从仓库根而非 `ai-service/` 启动，两个模块可能落到不同目录。取决于启动脚本是否 `cd ai-service`（`start-dev.ps1` 中确有切换，但是否有其他启动方式未知）。
9. **`provider=gee` / `copernicus 强制` 是否要保留为配置面**（P2-9）：如果二期要接 GEE，就把 `_gee_*` 实现补上；否则从 `.env.example` 与 `Literal` 里删除，避免"配置了但没生效"。
10. **`anomaly/batch`、`forecast/batch` 是否给外部/第三方用**（P2-7）：仓内 0 引用，但可能写在对外 API 文档里；删除前需确认无外部消费者。

---

*报告结束。本轮只写入本文件（`output/review-R11/ai-findings.md`，`output/` 已被 .gitignore 忽略），未修改任何被 Git 跟踪的文件。*
