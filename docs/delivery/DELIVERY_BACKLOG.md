# 交付剩余任务清单（Backlog）

> 基线：2026-11-13 · 已完成主交付线（构建/测试/37 项浏览器冒烟/交付文档）。
> 本清单只列"还没做完或需要现场/甲方确认"的事项，按优先级排列。
> 状态：⬜ 待办 · 🔄 进行中 · ✅ 已完成 · ⚠️ 待甲方/现场确认

## 图例

- **P0**：不做完不能算"交付完成"（演示/上线/签收前必须关闭）
- **P1**：建议在试运行前完成（影响体验/可信度/可维护性）
- **P2**：二期能力或甲方未确认需求（当前为显式 501/演示标识/产品边界）
- 负责人建议：前端 / 后端 / AI / 测试 / 运维 / 项目经理

---

## A. P0 交付前必做

### A1 现场人工验收（自动化覆盖不到的部分）

| ID | 任务 | 验收标准 | 证据 | 预估 | 负责人 |
|---|---|---|---|---|---|
| A1-1 | 红色警报真实听声 | 在 Chrome/Edge 真实窗口触发红色预警：听到警报声 + 闪烁；点"确认收到"后**彻底静音**；被自动播放拦截时点"开启警报声"能出声 | 现场录屏 + 控制台无错误 | 0.2d | 测试/前端 |
| A1-2 | WS 断线重连计时 | 阻断 WS/断网 ≥30s 后恢复：轮询兜底持续、重连提示出现、恢复后数据继续更新；记录重连耗时 | 录屏 + 网络面板截图 | 0.2d | 前端 |
| A1-3 | 真机移动端 | 真实手机访问 5173：侧栏抽屉、表格横向、地图手势、警报弹窗布局正常 | 手机截图 | 0.2d | 测试 |
| A1-4 | 页面按钮全量点检 | 关闭预警（填原因）、通知送达详情、AI 发布预警向导、会签发起/确认、导出下载 | 勾选清单 + 截图 | 0.5d | 测试 |
| A1-5 | 演示全流程彩排 | 按 `DEMO_SCRIPT.md` 完整走 2 遍，计时 ≤20 分钟，无阻断问题 | 彩排记录 | 0.2d | 项目经理/演示人 |

### A2 源代码整理与提交（✅ 本轮已关闭，2026-11-13）

| ID | 任务 | 验收标准 | 本轮结果 / 说明 | 预估 |
|---|---|---|---|---|
| A2-1 ✅ | 更新 `.gitignore` | `output/`、`generated-videos/`、CDP profile、`dist/`、临时脚本/压缩包不进入交付提交 | 已排除上述目录及根目录 PPTX/演示材料；未跟踪项 **110 → 88**，`git check-ignore -v` 逐项验证 | 0.2d |
| A2-2 ✅ | 决定 PPTX/演示材料归属 | 明确 15+ 个 `.pptx` 与"御灾智巡/"目录是纳入仓库还是单独交付包 | **决策已定（2026-11-13）：单独材料包**。19 个 PPTX（325 MB）+ 演示流程稿 + 5 份交付文档（含操作卡）打成 `DisasterGuard-演示材料包-v1.0.zip`（MANIFEST + SHA256，UTF-8 条目名），**不入库**；`御灾智巡/` 仅含工具状态文件，不纳入；现场放映版本由项目经理在包内 32 页系列中指定 | 0.2d |
| A2-3 ✅ | 建交付分支并提交 | `release/delivery` 分支：业务代码、文档、脚本、迁移、CI 全部提交；工作区干净 | 已建分支提交，`git status` 无意外残留 | 0.3d |
| A2-4 ✅ | 打标签与交付包 | tag `v1.8.0-delivery`；生成源码包（不含 output/临时物）+ 校验和 | 已打 tag 并生成源码包 + SHA256（见 §F） | 0.2d |
| A2-5 ✅ | 提交内容复核 | 66 个修改文件 + 新增告警/分析详情/设置/任务文档；3 个删除的死代码确认无引用 | 3 个删除组件无代码引用，`SMOKE_TEST.md` 过期行号已修正；eslint 0 / vitest 44 / SFC 97 / 令牌 / build / mvn package 全通过 | 0.3d |

### A3 干净机器部署演练（✅ 本轮已关闭，隔离环境实跑）

| ID | 任务 | 验收标准 | 预估 |
|---|---|---|---|
| A3-1 ✅ | 空库初始化路径 | 新目录/新数据卷：compose up → 后端启动自动建表 → 登录/首页/预警正常 | 0.3d |
| A3-2 ✅ | 历史库补丁路径 | 用旧 schema 数据卷启动：`system_setting` 建表、`event_sign` 唯一约束删除均幂等成功 | 0.2d |
| A3-3 ✅ | 生产构建部署 | `mvn package` + `npm run build` + nginx 反代（`/api`、`/ws`）全链路可用 | 0.3d |
| A3-4 ✅ | 部署手册复核 | 按 `DEPLOYMENT_GUIDE.md` 逐步执行无遗漏，修正过期截图/端口 | 0.2d |

> 实跑证据与 **4 个全新部署 P0 阻断的发现/修复**见 `DEPLOYMENT_GUIDE.md` §9、
> `ACCEPTANCE_LOG_20261112.md` §28、`KNOWN_LIMITATIONS.md` 〇节。
> 演练用独立编排 `scripts/delivery/compose.delivery-verify.yml`（独立端口/命名卷），未触碰开发库。

### A4 生产安全配置（代码/工具侧 ✅ 本轮完成；真实环境注入待上线日）

| ID | 任务 | 验收标准 | 状态/证据 |
|---|---|---|---|
| A4-1 | 改初始密码 | admin/演示账号密码全部更换；禁用/删除不需要的账号 | ⚠️ 上线日执行（代码侧 `ProductionConfigValidator` 会拦截仍是 `admin123` 的种子密码） |
| A4-2 ✅ | 密钥与凭证 | JWT/AES/MinIO/DB 强随机且只走环境变量 | 新增 `scripts/delivery/prod-config-check.mjs`（21 项）、`docs/delivery/env.production.example`；合格模拟 env **21/21 PASS**，开发 env 按预期报 13 error |
| A4-3 ✅ | 网络隔离 | AI 8001 仅内网；DB/Redis/MinIO 不暴露公网 | `.env.example` AI 绑定改 `127.0.0.1:8001`；`docker-compose.prod.yml` 后端默认 `127.0.0.1:8081`；公网仅 frontend nginx |
| A4-4 | HTTPS / CORS / CSP | 域名证书、CSP 白名单与实际域名一致 | ⚠️ 上线日执行（需甲方域名/证书）；检查器会拒绝 localhost/* |
| A4-5 | 审计与日志留存 | 审计/登录/应用日志保留周期确认 | ⚠️ 上线日按等保要求配置 |

> 验证证据：`PRODUCTION_HARDENING.md`；prod profile 实战 fail-fast：
> `IllegalStateException: prod 配置校验失败（4 项）`，进程退出 1（日志 `output/delivery-verify/prod-validator-fail2.log`）。

### A5 备份与恢复演练（✅ 本轮已关闭，真实备份/恢复）

| ID | 任务 | 验收标准 | 状态/证据 |
|---|---|---|---|
| A5-1 ✅ | Postgres 备份/恢复 | `pg_dump` → 空库恢复 → 预警/设备/审计/设置数据完整 | dump 434,253 B（SHA256 `60839886…9434`）；恢复库 8 张关键表行数逐一相等（预警 46/设备 16/审计 1764/规则 6/设置 1/灾害点 9/AI 分数 9/指标 5899） |
| A5-2 ✅ | MinIO 数据备份 | 附件/影像对象恢复后可用 | 5 个对象；宿主 tar 包 119,110 B（SHA256 `E548ED84…1B38`）；解包后对象数 5=5 |
| A5-3 ✅ | 恢复后应用验证 | 恢复环境核心接口可用 | 恢复库启动独立后端（18080）：健康、admin 登录、预警/设备/设置/规则/审计/首页 **全部 code=0**；验证后已清理临时库 |

> 详细命令与结果见 `ACCEPTANCE_LOG_20261112.md` §二十九。

### A6 甲方/安全决策项（阻塞部分范围）

| ID | 决策 | 影响 | 结论（2026-11-13） |
|---|---|---|---|
| A6-1 ✅ | 是否授予 `sign:proxy`（管理员代签） | 不授予则管理员替专家确认会 403 | **允许管理员代签**：权限码 93 仅授予 admin，其余 6 角色仍 403；每次代签写 warn 审计日志；`SignProxyPermissionTest` 覆盖。回收只需从权限矩阵移除该码 |
| A6-2 ✅ | 时相对比页面是否必做 | 决定 B1 是否进入本次交付 | **必做**：B1 已完整实现（页面/网关/菜单/权限/降级口径），证据见 §I |
| A6-3 ⚠️ | 演示数据是否清空/加水印 | 影响上线数据真实性口径 | 仍待甲方：试运行前清空业务数据，保留字典 |
| A6-4 ⚠️ | 地图合规：高德/天地图 Key/资质 | 不配置则降级境外 ESRI 源，有合规风险 | 仍待甲方：上线前采购/配置国内底图 |
| A6-5 ✅ | 时间显示是否固定北京时间 | 当前跟随浏览器时区 | **跟随浏览器本地时区**，不做代码改动；已写入 L-15 与操作卡；现场演示前确认机器时区 |

---

## B. P1 建议在试运行前完成

### B1 时相对比（灾前/灾后）页面 — ✅ 本轮已关闭（A6-2 决策「必做」）

| ID | 任务 | 验收标准 | 本轮结果 |
|---|---|---|---|
| B1-1 ✅ | 后端/aiApi 封装 sentinel compose/diff | 前端可调 `/api/ai/sentinel/compose`、`/diff`，错误码可读 | 新增 Java 网关 `SentinelCompareController`（compose/jobs/cancel/diff/diff-jobs/image）+ 前端 `satelliteCompareApi.js`；非法 bbox 400、上游不可达 502 中文提示；不再直连 8001 |
| B1-2 ✅ | 新增 `SatelliteComparePage.vue` | 选择灾害点 + 两期影像时间 → 生成对比图/差异热点 | 已实现：灾害点/手填 AOI、两期时间窗、进度轮询、灾前/灾后/热力图三栏、变化率与 Top 热点、取消 |
| B1-3 ✅ | 路由与菜单 | `/dashboard/satellite-compare` 指向新页面，权限正确 | redirect 改为真实页面；菜单与 moduleMeta 恢复、Satellite 图标注册；`satellite:view` 仅 admin/attendant/emergency_commander；旧别名从全局放行表移除（新增权限回归单测） |
| B1-4 ✅ | 演示数据与降级 | 无影像/无凭证时明确提示，不显示假差异图 | ai-service compose 结果新增 `dataSource`；`mock` 时页面**不提交 diff、不展示任何假图**，只给配置指引 |
| B1-5 ✅ | 浏览器验收用例 | 页面加载、选择/请求、空态、截图；纳入 smoke 或人工清单 | AI 端点实跑脚本 PASS、网关 4 用例、前端 12 用例、**真实浏览器 10/10 PASS**（`verify-satellite-page.mjs`，含截图）；SMOKE_TEST 新增 **SM-19**（剩余人工清单：真实影像/越权/取消） |

### B2 会签流程收口 — L-11/L-12

| ID | 任务 | 验收标准 | 状态 |
|---|---|---|---|
| B2-1 ✅ | 统一 `signerId` 语义 | 前端发起与确认统一传 `sys_user.id`（或 username），后端兼容期结束 | 已改为从会签记录中取当前用户的真实 `signerId`（`currentSignerId()`），不再传 username；后端兼容保留 |
| B2-2 | 多人会签真机验证 | PostgreSQL 实测 ≥2 人会签完整流程，状态/审计正确 | ⬜ 待现场 |
| B2-3 ✅ | 代签决策落地 | 若开启 `sign:proxy`：改 PermissionRegistry + 审计 warn 日志验证 | **已按 A6-1 开启**：权限码写入 data.sql + 前端映射 + 重新生成注册表（仅 admin）；`SignProxyPermissionTest` 反射执行真实校验逻辑，admin 放行 / expert 403；warn 日志格式见 SM-15 |

### B3 深色模式硬编码清理

| ID | 任务 | 验收标准 | 预估 |
|---|---|---|---|
| B3-1 | 扫描业务页硬编码颜色 | 输出文件/行清单（`#fff`、`rgba()` 等非 token） | 0.3d |
| B3-2 | 替换为设计令牌 | 关键页面（预警/设备/地图/审计/AI）深色下对比度达标 | 0.7d |
| B3-3 | 回归截图 | 深色模式关键页截图 + 无不可读文本 | 0.2d |

### B4 stylelint 基线（依赖已存在但未入门禁）

| ID | 任务 | 验收标准 | 状态 |
|---|---|---|---|
| B4-1 ✅ | 跑 `npm run lint:css` 并统计 | 得到基线数量/规则分布；输出报告 | 实测 **5,264 条**，Top：单行多声明 2273 / 规则空行 1139 / 颜色写法 1360 等（`STYLELINT_BASELINE.md`） |
| B4-2 ✅ | 配置 `.stylelintignore` + 分级规则 | 先 error 关键规则、warning 存量；CI 输出报告不阻断 | CI 新增 advisory 步骤 + artifact；存量规则暂不阻断 |
| B4-3 | 分批修复存量 | 至少 token 颜色、重复选择器、未知单位清零；再转阻断 | 🔄 第一批已做：`--fix` 5,312→**2,440**（86 文件，回归全绿）；剩余 2,320 单行多声明 + 115 语义项 |

### B5 测试覆盖补强 — L-14

| ID | 任务 | 验收标准 | 状态 |
|---|---|---|---|
| B5-1 ✅ | 后端单测：预警状态机 | 确认/派发/关闭/解除、非法状态拒绝、幂等 | ✅ `EventStateMachineTest` **11 例**：真实 Spring StateMachine 工厂 + Mockito 仓储，覆盖全链路/驳回/误报解除/REOPEN/非法跳转/重复幂等/审计日志；实测发现并修复 3 个缺陷。**口径**：非法流转在 service 层返回 `false` 且不落库，Controller 仍是 HTTP 200 + `ok=false`；原验收标准写的 409 属 API 契约调整，本轮未改，如实留档 |
| B5-2 ✅ | 后端单测：设置落库/权限 | 白名单字段、越权 403、upsert 幂等 | 新增 `SystemSettingServiceTest`（3 用例）：损坏 JSON 降级、白名单过滤、合并 upsert；并修复历史脏字段被合并写回的问题 |
| B5-3 ✅ | 后端单测：分析详情聚合 | 无指标/无影像/无 AI 时降级输出 | 新增 `WarningAnalysisServiceTest`：全依赖无数据时 ai=null、metrics.fallback=true、images=[]，不 500 |
| B5-4 ✅ | 前端组件测试 | GlobalAlarmModal 四档渲染、WarningAnalysisDetail 空态/异常 | ✅ 引入 `@vue/test-utils` + `jsdom`（devDeps）；2 个 SFC 测试 **12 例**（红/橙/黄/蓝四档 + 加载/异常/空/正常/重试），文件级 jsdom 环境不影响其余 node 用例 |
| B5-5 ✅ | ai-service pytest 实跑 | 10 个 pytest 文件在本地可执行并纳入结果 | ✅ 实跑 **102 passed / 4 skipped**（6.48s，2 条第三方弃用警告）；日志 `output/delivery-verify/pytest-20261113.log` |

### B6 Analytics 真实统计 — L-17

| ID | 任务 | 验收标准 | 预估 |
|---|---|---|---|
| B6-1 | 巡检报告真实统计 | 从巡检任务/事件表聚合，返回 `synthetic:false` | 0.7d |
| B6-2 | 时间对比真实数据 | 按日期范围聚合事件/预警，非确定性模拟 | 0.7d |
| B6-3 | 前端标识切换 | 数据真实后移除"演示数据"角标；保留 fallback 标识 | 0.2d |

### B7 其他 P1 小项

| ID | 任务 | 说明 | 预估 |
|---|---|---|---|
| B7-1 | 通知/待办详情整合 | 工作台只做汇总入口，详情在抽屉；确认是否可接受（L-06） | 0.3d |
| B7-2 ✅ | 时区显示统一 | 如甲方要求固定北京时间，统一 `formatTime` 工具（L-15） | **A6-5 决策＝跟随浏览器时区，条件不成立，关闭**；无需统一 `formatTime`，口径写入 L-15 / 操作卡 |
| B7-3 ✅ | 操作手册/培训材料 | 面向甲方值班员的一页纸操作卡（登录/预警处置/查看分析详情） | 已产出 `docs/delivery/OPERATOR_CARD.md`（含代签、时相对比、时区、演示数据口径），并纳入单独演示材料包 |

---

## C. P2 二期能力 / 明确边界（当前 501 或演示标识，非遗漏）

| ID | 能力 | 现状/边界 | 参考 |
|---|---|---|---|
| C1 | AI 模型训练任务表/调度器 | 训练相关端点 501；需建表+调度+界面 | L-02 |
| C2 | 多模态融合/模型漂移网关转发 | 后端 501，ai-service 侧为 demo/mock | L-01 |
| C3 | 图像识别/卫星外部数据源接入 | 依赖 CDSE/STAC/影像源凭证与网络；file:// 前端不可达 | L-04 |
| C4 | 备份校验、数据资产访问申请 | 显式 501，页面有提示 | L-08 |
| C5 | ai-service 鉴权/服务间凭证 | 当前无 JWT，需网关或服务凭证 | L-10 |
| C6 | 演示数据替换/全局水印 | data.sql 幂等种子不会自动清业务数据 | L-09 |
| C7 | 地图时间轴历史回放 | 需历史快照表/时序查询接口（3–5 人日） | L-18 |
| C8 | 地图/外部服务合规与商业授权 | 高德/天地图 Key、测绘资质 | L-16 |

---

## D. 下一轮建议执行顺序（按投入产出）

1. ~~**A2 代码提交 + A3 部署演练**~~ ✅ 已完成（见 §F）。
2. ~~**A4/A5 生产安全与备份恢复**~~ ✅ 已完成代码/工具与真实备份恢复演练（见 §G）；仅剩上线日真实注入。
3. ~~**4 项甲方决策 + B1 时相对比**~~ ✅ **2026-11-13 本轮完成**（A2-2 / A6-1 / A6-2 / A6-5 / B1 / B2-3 / B7-2 / B7-3，见 §I）。
4. **A1 现场人工验收 + SM-19 真实浏览器清单**（0.5 天）：✅ **自动化预检已大幅压缩**（端到端 14/14+15/15、WS 重连 6/6、15 路由/442 按钮巡检；本轮新增 4 个脚本 / **34 项判定全通过**：移动端 8/8、音频链路 10/10、演示预演 5/5、有副作用按钮 11/11，见 §M）；剩余**纯人工项**：真实听声（人耳）、真机触摸/网络、送达口径人工核对、完整彩排 2 遍计时、真实影像凭证 — 按 `ONSITE_ACCEPTANCE_CHECKLIST.md` + [A1_PREFLIGHT_REPORT.md](A1_PREFLIGHT_REPORT.md) 执行 — **当前建议下一步**。
5. **A6-3 / A6-4 + A4 上线日注入**（0.5 天）：演示数据水印/清空、国内底图 Key 与资质、域名证书、改初始密码、强密钥、审计留存。
6. ~~**B5 测试覆盖补强**~~ ✅ **2026-11-13 本轮关闭**（B5-1 / B5-4 / B5-5，见 §L）。
   剩余 **B3/B4-3/B6/B7-1**：进入试运行后按优先级持续迭代；**C 类**二期立项，不在本次交付承诺内。

## E. 验收口径提醒

- 任何"剩余任务"关闭时，必须同步更新：
  `DELIVERY_CHECKLIST.md`（结论）、`SMOKE_TEST.md`（若新增用例）、`KNOWN_LIMITATIONS.md`（移除已修复项）、`ACCEPTANCE_LOG`（证据）。
- 不允许把 501/演示数据说成已完成能力；演示时按 `DEMO_SCRIPT.md` 的兜底话术说明。

---

## F. 本轮执行记录（2026-11-13）

- **范围**：按 D 节第 1 项，关闭 **A2**（A2-2 当时保留 ⚠️ 待项目经理确认，**后续已决策关闭，见 §I / §J**）与 **A3**。
- **交付物**：`release/delivery` 分支 + tag `v1.8.0-delivery`；源码包与 SHA256 校验和
  生成于 `output/delivery-verify/`（临时产物，不入库）。
- **代码修复**（干净机器部署演练实证的 4 个 P0）：
  | 文件 | 修复 |
  |---|---|
  | `docker-compose.yml`、`scripts/delivery/compose.delivery-verify.yml` | 空卷 initdb 只挂载 001/003，避免 postgres `Exited(3)` |
  | `infra/postgres/init/002_*.sql`、`004_*.sql` | 加醒目说明：不再由 initdb 自动执行 |
  | `backend/src/main/resources/data.sql` | 应急响应 `warning_code` 改子查询，空库不再违反外键 |
  | `backend/.../ruleengine/WarningRuleSyncService.java` | `created_by` 置 NULL、`actions` 改数组，6 条规则可入库且可解析 |
  | `.gitignore` | 排除交付临时物与演示材料 |
- **新增可复现编排**：`scripts/delivery/compose.delivery-verify.yml`（隔离端口/命名卷 + nginx profile）。
- **文档同步**：`DELIVERY_CHECKLIST.md`、`SMOKE_TEST.md`、`KNOWN_LIMITATIONS.md`、
  `ACCEPTANCE_LOG_20261112.md`、`DEPLOYMENT_GUIDE.md`（新增 §9）、`DEPLOYMENT.md`、
  `docs/delivery/README.md`、本文件。
- **边界**：A1 / A4 / A5 / A6 与 B / C 类均未执行；演示可用 ≠ 试运行上线条件满足。

---

## G. 本轮执行记录（2026-11-13，A4/A5）

- **A5 已关闭**（真实备份/恢复演练）：
  - Postgres：`pg_dump` 434,253 B（SHA256 `60839886…9434`）→ 临时库恢复 → 8 张关键表行数逐一相等
    （预警 46 / 设备 16 / 审计 1764 / 规则 6 / 设置 1 / 灾害点 9 / AI 分数 9 / 指标 5899）；
  - MinIO：5 个对象，宿主 tar 119,110 B（SHA256 `E548ED84…1B38`），解包对象数 5=5；
  - 应用级：恢复库上启动独立后端（18080），健康/登录/预警/设备/设置/规则/审计/首页全部 code=0，验证后清理临时库。
- **A4 代码/工具侧完成**：
  - 新增 `scripts/delivery/prod-config-check.mjs`（21 项生产配置检查，只读）；
  - 新增 `docs/delivery/env.production.example` 与 `docs/delivery/PRODUCTION_HARDENING.md`；
  - 安全默认收敛：`.env.example` AI 改 `127.0.0.1:8001`，`docker-compose.prod.yml` 后端默认 `127.0.0.1:8081`；
  - 验证：合格模拟 env **21/21 PASS**；开发 env 按预期 13 error；prod profile 不安全开关实测
    `IllegalStateException: prod 配置校验失败（4 项）`、进程退出 1；生产 compose 组合解析退出码 0。
- **A4 仍待上线日**：真实域名/证书、强密钥注入、防火墙、改初始密码、审计留存（见 `PRODUCTION_HARDENING.md` §五）。
- **剩余 P0**：A1 现场人工验收（声音/重连/真机/按钮/彩排）、A6 决策项、A2-2 演示材料归属、A4 上线日项；
  P1/P2 保持不变。

---

## H. 本轮执行记录（2026-11-13，B2/B4/B5 部分）

- **B2-1 ✅ 会签 signerId 语义统一**：
  - `ConfirmationSignPage.vue` 新增 `currentSignerId()`，从 `selectedRecord.signers` 中按当前用户名取
    **真实 signerId**（即发起时写入的 `sys_user.id`），确认/驳回不再传 `operator.name`；
  - 同时补"找不到会签身份"的提示，后端兼容逻辑保留（历史记录不受影响）。
- **B4-1/B4-2 ✅ Stylelint 基线可见化**：
  - 实测存量 **5,264 条**：单行多声明 2,273、规则空行 1,139、颜色写法（alpha+notation）1,360、
    重复选择器 72、冗余前缀 39 等；
  - CI 新增 `Stylelint (advisory baseline)`（不阻断）+ 上传 `stylelint-report` artifact；
  - 治理计划见 `STYLELINT_BASELINE.md`（建议先 `--fix` 低风险批次，再手工处理单行多声明）。
- **B5-2/B5-3 ✅ 后端单测补强**：
  - `SystemSettingServiceTest`（3 用例）：损坏 JSON 降级、白名单过滤、合并式 upsert；
  - 测试发现并修复一个真实缺陷：历史设置中非白名单字段会被合并写回 → `load()` 现按白名单过滤；
  - `WarningAnalysisServiceTest`：全依赖无数据时 `ai=null / metrics.fallback=true / images=[]`，接口不 500；
  - 运行方式：`cd backend; mvn -q "-Dtest=SystemSettingServiceTest,WarningAnalysisServiceTest" test`（通过）。
- **未关闭**：B2-2 多人会签真机、B2-3 代签决策、B4-3 存量分批修复、B5-1/B5-4/B5-5 其余测试。
- **下一步建议**：B4-3 低风险自动修复批次（单独分支 + 视觉回归），或 B5-1 预警状态机单测。

---

## I. 本轮执行记录（2026-11-13，4 项决策 + B1）

- **输入口径**：跟随浏览器时区（A6-5）/ 单独材料包（A2-2）/ 允许管理员代签（A6-1）/ 时相对比必做（A6-2）。
- **关闭项**：A2-2、A6-1、A6-2、A6-5、B1-1~B1-5、B2-3、B7-2（条件不成立关闭）、B7-3。
- **新增/修改**：
  - 权限：`data.sql` 权限码 93 `sign:proxy`；`auth.js` 补 `sign:proxy`（admin）与 `satellite:view`（3 角色）；
    重新运行 `scripts/fix/sync-permission-registry.mjs` 生成后端注册表（7 角色）。
  - 时相对比：`SentinelCompareController.java`（网关 6 端点）、`SatelliteComparePage.vue`、
    `satelliteCompareApi.js` + `satelliteCompareUtils.js`、路由/菜单/图标、
    `ai-service/app/routers/sentinel.py` 的 `dataSource` 诚实降级标记。
  - 工具：`scripts/delivery/verify-sentinel-chain.mjs`、`pack-demo-materials.py`、
    `docs/delivery/OPERATOR_CARD.md`、`SMOKE_TEST.md` SM-19。
- **验收证据**：前端 eslint 0 / vitest **57 passed** / SFC 98 / 令牌 0 / build 9.03s；
  后端新增 6 用例全过（`SignProxyPermissionTest` 2 / `SentinelCompareControllerTest` 4，后者真实调用 28081 ai-service）；
  AI 端点脚本实测 compose×2 → diff → 3 张图片全 200（`changePercent=89.41%`、3 热点、`dataSource=mock`）；
  **真实浏览器页面 10/10 PASS**（`verify-satellite-page.mjs`，截图在 `output/delivery-verify/`）；
  材料包 26 文件（19 PPTX，约 324 MB），SHA256 以随包 `.sha256` 文件为准（`sha256sum -c` 通过，UTF-8 条目名复核）。
  详见 `ACCEPTANCE_LOG_20261112.md` §三十一、`DEPLOYMENT_GUIDE.md` §10、`KNOWN_LIMITATIONS.md` 〇节。
- **既有环境缺口**：全量 `mvn test` 中 12 个 `@SpringBootTest` 上下文用例因无测试库凭据失败（与本轮无关），
  交付门禁沿用 `mvn -DskipTests package` + 指定单测；详见验收日志 §31.4。
- **诚实边界**：
  - ~~未启动完整后端实例做浏览器端到端联调~~ **已补做并关闭**（同日后续轮次）：隔离 nginx(dist) →
    已部署新网关的后端 → ai-service 真跑，浏览器 15/15 + 网关 14/14 PASS（见 §K / `ACCEPTANCE_LOG` §三十三）。
    真实影像凭证仍待现场，SM-19 现场清单保留。
  - 真实卫星影像仍需 CDSE/GEE 凭证或公开 STAC 源；无凭证时页面只提示降级，不展示假差异图。
  - A1 现场项、A4 上线日注入、A6-3 / A6-4、B3/B4-3/B5/B6/B7-1 与 C 类保持不变。
- **下一步**：见 D 节第 4、5 项（A1 现场验收 + SM-19；A6-3/A6-4 + A4 上线日注入）。

---

## J. 交付快照收尾（2026-11-13，本轮提交后）

- **提交 / 标签**：`release/delivery` 分支 `0b109e1`（本轮 26 文件）+ 本节所在文档提交；
  **新增** annotated tag `v1.8.1-delivery` 指向该文档提交。`v1.8.0-delivery` 保留为前一轮快照，不强制移动。
- **源码包**：`output/delivery-verify/DisasterGuard-v1.8.1-delivery.zip` + `.sha256`
  （`git archive --prefix` 从 tag 生成，不含 `.git/`、`output/`、PPTX、`dist/`、`target/`）。
- **演示材料包复核**：`sha256sum -c` OK；26 条目 / 19 PPTX / UTF-8 标志齐全；
  包内 5 份文档快照已与 `docs/delivery/` 重新同步，`MANIFEST.md` 改指最新源码包，已重新打包并重算 SHA256。
- **文档一致性修订**：验收日志重复「三十」→「三十一」（含 30.x 编号与交叉引用）；
  `DELIVERY_CHECKLIST.md` §八「时相对比仍为重定向」→「待真实影像凭证」；
  `DEPLOYMENT_GUIDE.md` §6「代签未授予任何角色」→「仅 admin 持有」；§F / §28 的 A2-2 历史记录加注后续决策指针。
- **下一步不变**：D 节第 4、5 项（A1 现场验收 + SM-19；A6-3 / A6-4 + A4 上线日注入）。
  证据详见 `ACCEPTANCE_LOG_20261112.md` §三十二。

---

## K. 本轮执行记录（2026-11-13，D-4 验收闭环）

- **范围**：按本轮选定「D 节第 4 项」推进——关闭 §31.4 端到端缺口、自动化 A1 可自动部分、
  产出 `ONSITE_ACCEPTANCE_CHECKLIST.md`；并修复实测发现的 4 个真实缺陷。
- **验证环境（隔离，未触碰开发库）**：`compose.delivery-verify.yml` 拉起 25432/26379/29000，
  从开发库 `pg_dump` 恢复到隔离库；复用已在运行的 28083（含新网关 jar）与 28081 ai-service，
  nginx(dist) 25173 反代。全程未读取 `.env`、未用真实凭据启动新后端实例。
- **端到端联调（§31.4 缺口关闭）**：
  | 脚本 | 结果 | 覆盖 |
  |---|---|---|
  | `scripts/delivery/verify-sentinel-e2e.mjs` | **14/14 PASS** | 登录/JWT、越权 403、非法 bbox 400、路径穿越 400、compose×2、图片代理 PNG、diff、热力图、URL 重写 |
  | `scripts/delivery/verify-sentinel-browser-e2e.mjs` | **15/15 PASS** | nginx→网关→AI 真实浏览器；空态/表单/时区/降级文案、点击后降级、无假图、浏览器带 JWT 取 blob、无异常 |
- **实测发现并修复的 4 个缺陷**：
  | 缺陷 | 根因 | 修复 |
  |---|---|---|
  | 页面点「开始对比」进「时相对比未完成」错误态 | ai-service `provider=auto` 找不到 S2 场景时**硬抛 RuntimeError**，任务判 failed，B1-4 诚实降级走不到 | `sentinel.py` 降级返回 `dataSource=mock` + `fallbackReason`；前端展示具体原因（日期窗/AOI） |
  | nginx 下 Cesium 报 EvalError / CompileError | CSP `script-src` 缺 `'unsafe-eval' 'wasm-unsafe-eval'`，WASM 与 worker 被拦 | `infra/nginx/frontend.conf` 补两项 + `worker-src 'self' blob:`，注明上线加固取舍 |
  | `GET /api/notice-templates` 500 | 原生 SQL 已 ORDER BY，`PageRequest` 又传驼峰 `Sort("updatedAt")`，被追加成 `t.updatedAt` | `NoticeTemplateController` 去掉 Sort |
  | `GET /api/notification/records` 500 | JPQL `CONCAT('%', :keyword, '%')` 空关键词把 null 绑成 bytea → `lower(bytea)` | `MessageController` / `NotificationService` 空关键词改传 `""` |
- **A1 自动化预检**：
  - `verify-realtime-reconnect.mjs`：强制关闭 WS 32s（22 轮），期间轮询兜底 5 个 `/api` 响应持续，
    解除后 **2013 ms** 重连成功，**6/6 PASS**；
  - `verify-button-sweep.mjs`：15 个路由 / 442 个可点击按钮，无跳登录、无空白页、无 JS 异常；
    发现的 2 个 500 已定位并修复（见上表）。
- **门禁复核**：eslint 0 / vitest 57 / SFC 98 / 令牌 0 / `vite build` 9.48s；
  后端 `mvn -q -DskipTests package` 通过（fat jar 127,440,951 B，含新 class）。
- **诚实边界**：
  - 两条 500 的代码修复已编译 + SQL 直连校验；**2026-11-13 用最新源码重启后端复核**：
    `/api/notice-templates?page=1&size=5`、`/api/notification/records?...&keyword=` / `keyword=%20`
    均返回 `code=0`，两项已正式关闭。
  - 真实卫星影像仍依赖 CDSE/GEE/公开 STAC；本轮实测为 `dataSource=mock` 的**降级路径**，
    真实三栏影像、取消任务、听声、真机、彩排仍在 `ONSITE_ACCEPTANCE_CHECKLIST.md`。
  - 全量 `mvn test` 的 12 个 `@SpringBootTest` 环境凭据缺口保持不变（§31.4）。
- **下一步**：D 节第 4 项剩余纯人工项 → 第 5 项（A6-3/A6-4 + A4 上线日注入）。

---

## L. 本轮执行记录（2026-11-13，B5 测试覆盖补强）

- **范围**：D 节第 4 项剩余纯人工项、第 5 项甲方/上线日注入均无法在本机执行，
  故按第 6 项推进可自主完成的 **B5-1 / B5-4 / B5-5**，并同步修正文档一致性。
- **起点核对**：并行工作流提交 `b7825be` 关闭了两条 500 的 HTTP 复核项；本机复测 `8080`
  的 `/api/notice-templates`、`/api/notification/records?keyword=`、`keyword=%20`
  均返回 `code=0`，声明属实。但 `ONSITE_ACCEPTANCE_CHECKLIST.md` §6 仍写「待重新部署后复核」，
  已改为「已关闭、无需现场重做」，并把三条 `code=0` 实测证据写进表格。
- **B5-1 状态机单测（11 例）**：新建 `EventStateMachineTest`，用
  `AnnotationConfigApplicationContext(EventStateMachineConfig.class)` 拿真实 `StateMachineFactory`、
  Mockito 打桩仓储，规避 12 个 `@SpringBootTest` 的 DB 凭据缺口。补测过程发现并修复 3 个**真实缺陷**：
  1. `EventStateService` 误用 `resetStateMachineReactively(...)`（返回冷 `Mono` 却从未订阅），
     状态恢复不执行、每次都从 `DETECTED` 出发 → 中间态流转被误判、终态甚至可被 `CONFIRM` 错误"复活"；
     改用同步 `resetStateMachine(...)`。
  2. `EventStateMachineConfig` 把 `CLOSED` / `REJECTED` 声明为 `.end()` 终止态，机器一进入就终止，
     配置的 `REOPEN` 出口永远不被接受（R3 P1-17 当时只补了转换、没去掉 `.end()`）→ 改为普通状态，
     业务终态仍由 `EventState.isTerminal()` 判定。
  3. 审计日志在 `setState(newState)` 之后才取旧态，打印成 `CLOSED -> CLOSED` → 先存 `oldState`，
     并加日志断言用例。
  修复前 10 例 5 失败 → 修复后 **11/11 PASS**。**口径**：service 层非法流转返回 `false` 且不落库；
  `EventStateController` 仍是 HTTP 200 + `ok=false`，原验收标准写的 409 属 API 契约调整，
  为避免破坏前端调用本轮未改，如实留档。
- **B5-4 前端组件测试（12 例）**：新增 devDeps `@vue/test-utils@^2.5.1`、`jsdom@^26.1.0`；
  `tests/globalAlarmModal.spec.js` 7 例（四档渲染 + 确认收到 + 查看详情跳转，mock router / Web Audio / 实时回调）、
  `tests/warningAnalysisDetail.spec.js` 5 例（加载 / 异常 / 空 / 正常 / 重试，只 mock 聚合 API）。
  文件级 `// @vitest-environment jsdom` 只作用于这两个文件，其余 5 个测试文件仍跑 node 环境。
- **B5-5 ai-service pytest**：用装了 `requirements.txt` 的 Anaconda Python 实跑 10 个测试文件，
  **102 passed / 4 skipped / 2 warnings**（6.48s），无需外部服务；日志 `output/delivery-verify/pytest-20261113.log`。
- **门禁复核**：eslint 0 / vitest **69 passed**（7 文件）/ SFC 98 / 令牌 0 / `vite build` 5.24s；
  后端 `mvn -q -DskipTests package` 通过（fat jar 127,440,921 B，含新 class）。
  `npm audit` 17 项，其中 `--omit=dev` 的 5 项为**既有** Vite / launch-editor 公告，
  与本次新增依赖无关，详见 `KNOWN_LIMITATIONS.md` L-14。
- **交付快照**：tag `v1.8.3-delivery`；源码包
  `output/delivery-verify/DisasterGuard-v1.8.3-delivery.zip` + `.sha256`；
  演示材料包因 6 份文档快照变更已重新打包并重算 SHA256。
- **下一步**：D 节第 4 项剩余纯人工项 → 第 5 项（A6-3/A6-4 + A4 上线日注入）；
  B3/B4-3/B6/B7-1 留待试运行期迭代。

---

## M. 本轮执行记录（2026-11-13，A1 剩余项再压缩）

- **范围**：用户选定「D 节第 4 项 · A1 剩余项再压缩」。起点核对：清单与 `4d0f670` 逐字一致、
  工作区干净、无新提交；全仓 / `output/` / D 盘副本均无本轮新增改动，
  只有常规开发服务（8080 / 5173 / 8001）与三个开发容器在跑。
- **隔离验证环境**：`compose.delivery-verify.yml` 拉起 25432 / 26379 / 29000，
  开发库 `pg_dump` 恢复到隔离库（7 用户 / 46 预警 / 9 统一事件 / 1795 审计）；
  启动含最新 jar 的后端 28083（`AI_ENABLED=false`）、ai-service 28081、
  nginx(dist) 25173（沿用含 Origin 改写与 Cesium CSP 的演练 conf）、Edge headless CDP 9222。
  全程只用编排文件内标注「仅本机隔离演练」的口令，未读取 `.env`。
- **新增 4 个可复现脚本 / 34 项判定全通过**：
  | 脚本 | 覆盖 | 结果 |
  |---|---|---|
  | `verify-mobile-responsive.mjs` | A1-3 4.2/4.3/4.5 | **8/8**：4 视口 × 5 路由 = 20 组无横向溢出 / 空白页 / 5xx / 异常 / 控制台错误；360×800 红色弹窗不溢出、按钮全可点；9 张截图 |
  | `verify-alarm-audio.mjs` | A1-1 2.2/2.3/2.4 | **10/10**：拦截提示出现、真实输入事件点击后振荡器 start +1、确认收到后 stop/close +1、弹窗关闭、0 异常 |
  | `verify-demo-walkthrough.mjs` | A1-5 页面预演 | **5/5**：13 步 + 3 个 Tab 全可达，纯页面加载 15.7s |
  | `verify-side-effect-buttons.mjs` | A1-4 5.1/5.3/5.4/5.5 | **11/11**：关闭原因必填校验 + DB 落库、导出 xlsx 6,808 B / `PK`、2 人会签 + admin 代签 200/0 + 状态落库、发布预警 4 步向导、送达 Tab |
- **文档**：新增 `A1_PREFLIGHT_REPORT.md`；`ONSITE_ACCEPTANCE_CHECKLIST.md` 第 0/2/4/5/8 节
  逐项标注哪些子项已预检、哪些仍必须现场；`ACCEPTANCE_LOG` §三十五记录证据与边界。
- **脚本稳定性加固**：首版移动端脚本固定等待 1.5s，在隔离库数据增多后误判空白页、
  且易错过 WS 推送窗口；改为轮询等内容渲染完成 + WS 连接稳定后创建预警 + 最多 3 次重试创建，
  复跑稳定 8/8。彩排 / 副作用脚本加 `EXPECT_AI_DISABLED=1` 显式开关：只把 AI 关闭导致的
  `/api/ai/**` 503 单独归类，真实环境仍严格按 5xx 判失败。
- **诚实边界**：
  - 演练后端 `AI_ENABLED=false`：预警详情 1 个 `503 /api/ai/fusion-risk/auto` 属预期降级，
    页面按 `DEMO_SCRIPT.md` 显示「加载失败 + 重试」而非假数据；AI 洞察页停在
    「正在分析 / 后端连接中」加载态、未展示伪造评分，现场启动 AI 后须确认完整渲染。
  - headless **没有人耳**、CDP **不是真机**：2.1 真实外放、2.3 真人点击出声、
    4.1/4.4 真机触摸与网络仍必须现场；5.2 送达口径、8.1~8.4 彩排计时与话术同样不可自动化。
  - **A1 各子项并未整体关闭**；A4 上线日注入、A6-3/A6-4、B3/B4-3/B6/B7-1 与 C 类保持原状态。
- **交付快照**：tag `v1.8.4-delivery`；源码包
  `output/delivery-verify/DisasterGuard-v1.8.4-delivery.zip` + `.sha256`；
  演示材料包纳入 `A1_PREFLIGHT_REPORT.md` 并刷新 6 份文档快照后重新打包、重算 SHA256。
- **下一步不变**：D 节第 4 项剩余纯人工项 → 第 5 项（A6-3/A6-4 + A4 上线日注入）。
