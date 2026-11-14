# 2026-11-14 复测遗留项修复报告

> 输入：`fc41abc` 之后的第二轮「真实接口 + 真实浏览器」复测结论。复测确认上一轮 P0 修复
> 全部生效（110 个 GET **0 个 500**、41 条路由 **0 个 403**、浏览器冒烟 37/37、vitest 77、eslint 0），
> 同时指出 4 个遗留项。本轮按「设备地图 fit → 演示数据刷新 → AI 研判缓存 → drift 懒加载/空态」处理。

## 一、复测确认已生效（无需再改）

| 项 | 复测结果 |
|---|---|
| `/api/device-map` 500 | ✅ 200，16 台真实设备，无 mock 设备 / 错误横幅 |
| 前端静默 mock | ✅ 默认抛错，演示模式需显式开关 + 横幅 |
| 灾害预测 500 | ✅ 200，24 步预测 / 峰值 / 置信度 / 降级标识 |
| 气象页 403 | ✅ admin 可进入 |
| drift 双 `/drift` | ✅ 已改 `/api/ai/drift/...`，501 识别为「能力未接入」 |
| 预警空 KPI / 灾害页弹窗 | ✅ 「未接入 + 原因」/ 首屏不再自动弹详情 |
| 全量接口 / 菜单 | ✅ 110 GET 0 个 500；41 路由 0 个 403 |

## 二、本轮修复

### 1. 设备地图视野 fit（复测 P1）

- **问题**：`DeviceMapPage.vue` 默认中心仍是成都 demo 坐标 `[104.065, 30.575]`，加载真实
  16 台重庆设备（约 `106.4 / 29.5`）后没有 `fit`，地图首屏看不到 marker。
- **修复**：默认中心改重庆 `[106.55, 29.56]`；新增 `fitToDevices()`，用投影后坐标算
  bounding extent 调 `mapView.fit(...)`（padding 70、maxZoom 14），无有效坐标时回退重庆中心；
  只自动执行一次，不覆盖用户后续手动缩放。为 CDP 回归增加 **仅 DEV** 的只读诊断句柄
  `window.__dgDeviceMap`，生产构建不含该分支。
- **实测**：marker 16 个、中心 `centerX≈11,860,000`（重庆，成都约 11,584,000）、zoom ≈11.03。

### 2. 演示数据刷新（复测 P1，已在开发演示库执行）

- **备份**：`output/delivery-verify/dev-db-backup-before-demo-refresh-20261114.sql`
  （3,500,997 B，SHA256 同目录校验通过），执行前 16 台全离线、预警/灾害点停在 2026-11-06。
- **执行**：`scripts/delivery/refresh-demo-data.sql`（幂等、只 UPDATE、不 INSERT/DELETE），
  在开发演示库跑通；新增 `verify-demo-data-refresh.mjs`（默认只读，`APPLY=1` 才刷新并验证）。
- **结果**：**6/16 台在线**、预警与灾害点最后时间 **2026-11-14**、近 24h 轨迹 40 条，
  `/api/device-map` 同步为 6 在线 / 10 离线；验证 **4/4 PASS**。
- ⚠ **重要发现（不是 bug）**：`DeviceOnlineChecker` 每 60s 把「在线且
  `last_report_at` 早于 now()-5min」的设备置离线（`application.yml:169-170`）。演示设备不会真的
  上报，所以刷新结果**约 5 分钟后自然衰减回 0 在线**。这是正确的心跳语义。
  → 脚本头部与现场表已写明：**每场演示开始前 5 分钟内重跑刷新脚本**（幂等，可反复执行），
  长时间彩排每 4 分钟重跑一次；生产/试运行环境不要这样保活。
- ⚠ 「已记录轨迹 0」也是真实统计：近 24h 轨迹属于 `DV-002`/`DV-004`（gnss / crack_meter），
  演示库没有「移动设备」类型，故不计数；未伪造移动设备。

### 3. AI 综合研判首屏（复测 P1）

- **根因实测**：`GET /api/ai/disaster-assessment?hazardCode=HZ-SBOX-04` **27.6 秒**
  （后端调 AI 实时推理），复测观测到 35 秒才出完整内容。
- **修复**：新增 `aiApi.disasterAssessmentCached()` 读已有的
  `/api/ai/comprehensive-analysis/cached`（落库评分，**不触发推理**）。
  `AiComprehensiveTab.loadReport()` 改为两阶段：
  1. 立即渲染落库评分，横幅明确标注「落库评分 · 时间（非实时推理）」、
     后台刷新中、评分较旧（>24h）提示；
  2. 后台异步调实时 `disaster-assessment`，成功后替换并改为「实时研判结果」；
     失败时保留落库评分并提示刷新失败，绝不冒充实时结果。
  切换灾害点时用 code 比对丢弃过期响应。
- **实测**：首屏可解释内容 **~850 ms**（原 27.6s），横幅正确显示来源与「评分较旧」。

### 4. drift 懒加载与 AI 模型页空态（复测 P2）

- 移除模块顶层无条件 `loadDriftMonitor()`，改为点开「漂移监控」Tab 才 `ensureDriftLoad()`；
  **首屏 501 请求 0 个**，点开 Tab 后才出现 2 个显式 501 并显示「能力未接入」。
- 模型版本库空态改为「暂无模型版本数据（演示库尚未初始化模型版本表），非接口故障」；
  KPI 全 0 时新增「模型统计表为空：以上 0 是真实统计值」说明。
- 未补模型种子数据：数据库确实为空，补种子等于造演示模型，按诚实边界保持空态。

### 5. 正式登记为二期 / 外部边界（复测 P2、P3，本轮不改代码）

- 预警「消息通知送达率 / 联动部门数」仍为「未接入」：接入需后端从
  `notification_record` 与预警-部门关联聚合，属 `KNOWN_LIMITATIONS` L-21 二期能力；
  本轮不做半真半假的统计。
- 总览页约 320 条 `ERR_BLOCKED_BY_ORB`（外部地图/图片资源）、NSMC/NMC 天气源超时或 404、
  设备地图右侧未选设备空白提示，均属外部依赖/演示 polish，登记 L-20，不代理外链。
- 浅色/深色对比度 7 页 0 issues、1440 下无横向溢出，UI 设计体系保持不动。

## 三、回归与门禁

| 脚本 | 结果 |
|---|---|
| `verify-demo-data-refresh.mjs`（`APPLY=1`） | **4/4 PASS**：6/16 在线、接口一致、日期今天、近 24h 轨迹 40 |
| `verify-retest-fixes.mjs`（dev 5173 + CDP） | **17/17 PASS**：地图 fit / AI 首屏 / drift 懒加载 / 空态 / 无意外 5xx / 无异常 |

证据：`output/delivery-verify/demo-data-refresh.json`、`retest-fixes.json`；
截图 `output/retest-fixes/*.png`。

门禁：eslint 0 / stylelint 全量 0 / vitest **77 passed**（8 文件）/ SFC 98 / 令牌 0 /
`vite build` 5.71s；后端全量 `mvn test` **149 / 0 failures / 0 errors / 4 skipped**、
`mvn package` 成功。本轮**未改产品后端 Java 代码**。

## 四、仍需现场 / 二期

- 每场演示前 5 分钟内重跑 `refresh-demo-data.sql`（心跳窗口）；
- A1 纯人工项（人耳听声、真机、彩排计时、真实影像凭证）、A4 上线日注入、A6-3/A6-4；
- B6-1 巡检报告 ground truth、B7-1 通知/待办口径、状态机 409 契约、Vite 升级、
  预警通知统计真实聚合（L-21）、drift/multimodal 同源代理、P2 二期能力。
