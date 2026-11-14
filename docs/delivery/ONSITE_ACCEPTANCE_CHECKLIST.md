# 现场人工验收记录表（A1 + SM-19）

> 生成日期：2026-11-13　｜　适用：交付演示 / 试运行签收现场
> 本表只列**本机自动化无法替代**的人工项。自动化预检结果见第 0 节，勿重复劳动。
> 口径依据：`DELIVERY_BACKLOG.md` D 节第 4 项、`SMOKE_TEST.md` SM-19、`KNOWN_LIMITATIONS.md`。
> **2026-11-13 A1 预检补强**：见 [A1_PREFLIGHT_REPORT.md](A1_PREFLIGHT_REPORT.md)（4 个脚本 / 34 项判定全通过）；
> 第 2、4、5、8 节已逐项标注哪些子项无需现场重做、哪些仍必须人工执行。

---

## 0. 自动化预检已完成（无需现场重做）

| 项目 | 结论 | 证据 / 复现命令 |
|---|---|---|
| 浏览器 → nginx(dist) → 新网关 → ai-service 端到端 | ✅ **15/15 PASS** | `node scripts/delivery/verify-sentinel-browser-e2e.mjs`；截图 `output/delivery-verify/sentinel-e2e-*.png` |
| Java 网关 API 全链路（含越权/参数负向） | ✅ **14/14 PASS** | `node scripts/delivery/verify-sentinel-e2e.mjs` |
| A1-2 WS 断线重连计时 | ✅ **6/6 PASS**，阻断 32s，重连 **2013 ms**，轮询兜底持续 | `node scripts/delivery/verify-realtime-reconnect.mjs`；`output/delivery-verify/realtime-reconnect.json` |
| A1-4 页面/按钮巡检预检 | 15 个路由 / **442** 个可点击按钮，无跳登录、无空白页、无 JS 异常 | `node scripts/delivery/verify-button-sweep.mjs`；`output/delivery-verify/button-sweep.json` |
| 2026-11-14 审查 P0/P1 修复回归 | ✅ 真实接口 **12/12** + 真实浏览器 **14/14**（7 页面）PASS：设备地图真实 16 台 / 无 mock、气象页不再 403、灾害预测不再 500、预警 KPI 显示「未接入」、灾害列表不自动弹窗 | `node scripts/delivery/verify-review-api-fixes.mjs`；`node scripts/delivery/verify-review-browser-fixes.mjs`；详见 [REVIEW_FIX_REPORT.md](REVIEW_FIX_REPORT.md) |
| 前端门禁 | eslint 0 error / stylelint 0 / vitest **77 passed**（8 个测试文件）/ SFC 98 / 令牌完整性 0 问题 / `vite build` 通过 | 含 B5-4 组件测试 12 例、审查修复回归 7 例 |
| 后端门禁 | `mvn -q -DskipTests package` 通过，fat jar 127 MB；`EventStateMachineTest` **11/11 PASS** | B5-1 状态机单测 |
| ai-service pytest | 10 个测试文件 **102 passed / 4 skipped**（6.48s） | `output/delivery-verify/pytest-20261113.log` |
| A1-3 移动端响应式（CDP 设备模拟） | ✅ **8/8 PASS**：4 视口 × 5 路由 = 20 组，无横向溢出 / 空白页 / 5xx / 异常；360×800 红色弹窗不溢出、按钮全可点 | `node scripts/delivery/verify-mobile-responsive.mjs`；9 张 `mobile-*.png` |
| A1-1 警报音频链路 | ✅ **10/10 PASS**：自动播放拦截提示、真实手势点击后振荡器 start +1、确认收到后 stop/close +1 | `node scripts/delivery/verify-alarm-audio.mjs` |
| A1-5 演示流程预演 | ✅ **5/5 PASS**：13 步页面与 3 个 Tab 全可达，纯页面加载累计 **15.7 s** | `EXPECT_AI_DISABLED=1 node scripts/delivery/verify-demo-walkthrough.mjs` |
| A1-4 有副作用按钮（隔离库实跑） | ✅ **11/11 PASS**：关闭原因必填校验与落库、导出 xlsx、≥2 人会签 + admin 代签、发布预警 4 步向导 | `EXPECT_AI_DISABLED=1 node scripts/delivery/verify-side-effect-buttons.mjs` |

> 巡检中发现的两处后端 500 已用最新源码重启后端**实测 `code=0` 正式关闭**（见第 6 节），现场无需重做。
> 上述 A1 预检详见 [A1_PREFLIGHT_REPORT.md](A1_PREFLIGHT_REPORT.md)；**A1 各子项并未整体关闭**，
> 第 2/4/5/8 节列出的真人 / 真机 / 话术项仍须现场执行。
> **演示数据新鲜度**：若现场看到「0 在线设备 / 预警日期过期」，由演示负责人确认目标库并备份后执行
> `scripts/delivery/refresh-demo-data.sql`（仅 UPDATE、幂等；本轮只在隔离库验证，未在开发库执行）。
> 该脚本会改写业务时间戳与设备状态，**生产/试运行环境禁用**。

---

## 1. 演示前 30 秒自检（每次开场前）

| # | 检查项 | 预期 | ✅/❌ |
|---|---|---|---|
| 1.1 | 演示机时区与系统时间 | 本机时区正确；页面时间**跟随浏览器本地时区**（A6-5 决策），服务端日志/调度仍按 `TZ=Asia/Shanghai` | ☐ |
| 1.2 | 后端版本 | 已部署含 `SentinelCompareController` 的新 jar；`/api/ai/sentinel/compose` 未带 token 返回 403（不是 404） | ☐ |
| 1.3 | ai-service | `AI_SERVICE_BASE_URL` 指向已启动实例；健康检查 200 | ☐ |
| 1.4 | 真实影像凭证 | 若已配 CDSE / GEE / 公开 STAC，记录可用场景；未配置则本场按**降级话术**演示 | ☐ |
| 1.5 | 声音设备 | 外放可用、系统音量非静音；浏览器允许自动播放（或被拦截时点「开启警报声」） | ☐ |
| 1.6 | 网络 | WS 握手 101；nginx `/healthz` 200 | ☐ |

---

## 2. A1-1 红色警报真实听声（2.2 / 2.4 已预检）

| # | 步骤 | 预期现象 | 证据 |
|---|---|---|---|
| 2.1 | 在真实 Chrome/Edge 窗口触发红色预警 | 听到警报声 + 页面闪烁 | ☐ 录屏 **（必须现场）** |
| 2.2 | 点「确认收到」 | **彻底静音**，不再有尾音/循环 | ✅ 已预检（stop + close 均被调用）；☐ 现场录屏 |
| 2.3 | 若被浏览器自动播放策略拦截 | 出现「开启警报声」按钮，点击后立即出声 | ✅ 已预检（按钮出现 + 真实手势恢复链路）；**☐ 真人确认确实出声** |
| 2.4 | 查看控制台 | 无未捕获异常、无业务 ERROR | ✅ 已预检（0 异常 / 0 5xx / 0 控制台错误）；☐ 截图 |

> 自动化边界：headless **没有人耳**。`verify-alarm-audio.mjs` 已验证 2.2 的静音调用链、
> 2.3 的拦截提示与恢复路径、2.4 的控制台干净；**2.1 真实外放与 2.3 真人点击后确实出声仍必须现场确认**。

---

## 3. A1-2 WS 断线重连计时

自动化预检已完成（见第 0 节）；现场只需用真实网络条件再做一次：

| # | 步骤 | 预期 | 记录 |
|---|---|---|---|
| 3.1 | 在 DevTools Network 阻断 WS（或断网）≥30s | 「重连」提示出现；轮询兜底持续，页面不白屏 | 起始时间 ______ |
| 3.2 | 恢复网络 | 5s 定时窗口内重新握手 101；数据继续刷新 | 重连耗时 ______ ms |
| 3.3 | 检查轮询与 WS 是否重复插入数据 | 无重复预警、无状态回退 | ☐ |

> 自动化实测：阻断 32s、强制关闭 22 轮、期间 5 个 `/api` 响应、解除后 **2013 ms** 重连成功。

---

## 4. A1-3 真机移动端（4.2 / 4.3 / 4.5 已 CDP 预检）

| # | 检查项 | 预期 | 证据 |
|---|---|---|---|
| 4.1 | 真机浏览器访问前端 5173 / 生产域名 | 页面可打开、登录成功 | ☐ 手机截图 **（必须真机）** |
| 4.2 | 侧栏 | 抽屉展开/收起正常，不遮挡内容 | ✅ 4 视口 CDP 预检无溢出；☐ 真机截图 |
| 4.3 | 表格 | 横向滚动可用，表头不丢失 | ✅ 4 视口 CDP 预检有滚动容器、无横向溢出；☐ 真机截图 |
| 4.4 | 地图 | 手势缩放/拖动正常，无 CSP 或瓦片报错 | ☐ 手机截图 **（真机手势必须现场）** |
| 4.5 | 警报弹窗 | 布局不溢出、按钮可点 | ✅ 360×800 下遮罩与按钮全部在可点区域；☐ 真机截图 |

> 注意：生产 nginx CSP 已补 `'unsafe-eval' 'wasm-unsafe-eval'` 与 `worker-src 'self' blob:`（Cesium 需要）；真机请确认无 `EvalError` / `CompileError`。
> CDP 只是**设备模拟**，不能替代真机触摸手感与真实移动网络，4.1 / 4.4 仍须现场执行。

---

## 5. A1-4 页面按钮点检（5.1 / 5.3 / 5.4 / 5.5 已隔离库实跑）

上一轮 `verify-button-sweep.mjs` 只点「刷新/查询」等无副作用按钮；本轮 4 个子项已在
**隔离库**真实执行通过，现场只需留证并核对 5.2 的口径：

| # | 交互 | 预期 | 证据 |
|---|---|---|---|
| 5.1 | 关闭预警（填写原因） | 原因必填校验生效；关闭后状态/审计正确；不填原因不可提交 | ✅ 已实跑（空值提示「请输入关闭原因」、DB `已解除 + 原因`）；☐ 现场截图 |
| 5.2 | 通知送达详情 | 打开详情抽屉/页，回执状态与 `MOCK_SENT`/`BLOCKED` 口径一致，不谎称「已送达」 | ✅ Tab 可打开；**☐ 口径人工核对 + 截图** |
| 5.3 | AI 发布预警向导 | 逐步引导可用；演示数据有明确标识，不冒充真实模型结论 | ✅ 已实跑（4 步向导：基本信息→发布范围→渠道与内容→确认发布）；☐ 现场截图 |
| 5.4 | 会签发起 / 确认 | ≥2 人完整流程（B2-2）；admin 代签可用并写 warn 审计；非授权角色 403 | ✅ 已实跑：2 人会签 + expert/commander **各自本人确认 2/2 CONFIRMED**（`verify-multi-signer.mjs`）+ admin 代签 200/0；☐ 现场截图 |
| 5.5 | 导出下载 | 文件真实生成、可打开、内容与页面一致 | ✅ 已实跑（xlsx 6,808 B / ZIP 魔数 `504b`）；☐ 现场文件 |
| 5.6 | 通知渠道 / 通知模板两个页面 | 见第 6 节：重新部署新 jar 后应无 500 | ✅ 已关闭（实测 `code=0`），无需现场复核 |

> 演示口径提醒：**通知白名单是准入闸门，不是投递能力**；`MOCK_SENT` 是演示发送，不得说成真实送达。

---

## 6. 两条 500 的 HTTP 复核（✅ 2026-11-13 已关闭，无需现场重做）

| 端点 | 原故障 | 代码修复 | 复核结果 |
|---|---|---|---|
| `GET /api/notice-templates` | 500：`column t.updatedat does not exist`。`PageRequest` 传驼峰 `Sort("updatedAt")` 被 Spring Data 原样追加到原生 SQL | `NoticeTemplateController` 去掉 Sort，排序交给 SQL 内 `ORDER BY t.updated_at DESC` | ✅ 用最新源码重启后端后实测 `?page=1&size=5` → `code=0`，返回模板列表 |
| `GET /api/notification/records` | 500：`function lower(bytea) does not exist`。JPQL `CONCAT('%', :keyword, '%')` 在空关键词时把 null 绑成 bytea | `MessageController` / `NotificationService` 空关键词改传 `""`（产生 `%%` 模式） | ✅ `keyword=` 与 `keyword=%20` 两种空关键词均 `code=0`，返回通知记录 |

> **复核证据（2026-11-13 15:0x，8080 开发后端）**：登录取 JWT 后逐条请求，三个响应首部均为
> `{"code":0,"message":"success",...}`；证据同步记录于 `ACCEPTANCE_LOG_20261112.md` §33.3、
> `DELIVERY_BACKLOG.md` §K，提交 `b7825be`。
> 这两项**已从现场待办中移除**；现场无需再对这两个页面做 500 回归，仅按 §5 做常规按钮点检即可。

---

## 7. SM-19 时相对比真实影像

| # | 步骤 | 预期 | 证据 |
|---|---|---|---|
| 7.1 | 确认 CDSE / GEE 凭证或公开 STAC 可达 | 能查到所选 AOI、所选时间窗的 S2 L2A 场景 | ☐ 配置记录 |
| 7.2 | 选择灾害点 + 灾前/灾后时间窗，点「开始对比」 | 两期 compose 成功且 `dataSource=real` | ☐ 截图 |
| 7.3 | 查看三栏影像与差异热力图 | 真实缩略图经 `/api/ai/sentinel/image/**` 同源代理加载；无 `file://`、无 8001 直连 | ☐ 截图 |
| 7.4 | 无凭证场景（本轮实测） | 页面显示「未取得真实卫星影像」+ 具体原因（日期窗/AOI）+ 配置指引，**不提交 diff、不展示任何假图** | ✅ 已自动化验证 |
| 7.5 | 越权 / 取消路径 | maintenance 403；admin/attendant/commander 200；取消后不残留任务 | ✅ 越权已自动化验证；取消待现场 |

> 本轮实测降级原因示例：`在 [2026-10-14–2026-11-13] 范围内未找到 S2 L2A 有效场景（AOI=[106.41, 29.55, 106.45, 29.59]）；已按 dataSource=mock 降级`。

---

## 8. A1-5 演示全流程彩排（页面预演已通过）

> 自动化预演：`verify-demo-walkthrough.mjs` 按 `DEMO_SCRIPT.md` 走完 13 步 **5/5 PASS**，
> 纯页面加载累计 **15.7 s**。它**不含讲解、停顿与话术**，因此不能用来宣称下表计时已达标，
> 现场仍须完整走 2 遍并人工计时。

| # | 项目 | 标准 | 记录 |
|---|---|---|---|
| 8.1 | 按 `DEMO_SCRIPT.md` 完整走第 1 遍 | 无阻断问题 | 用时 ______ |
| 8.2 | 完整走第 2 遍 | 无阻断问题 | 用时 ______ |
| 8.3 | 总时长 | ≤20 分钟 | ______ |
| 8.4 | 兜底话术 | 501 / 演示数据 / 模拟结果均按口径说明，不夸大能力 | ☐ |

---

## 9. 签字

| 角色 | 姓名 | 日期 | 结论 |
|---|---|---|---|
| 测试 | | | ☐ 通过 ☐ 带遗留项通过 |
| 项目经理 | | | ☐ 通过 ☐ 带遗留项通过 |
| 甲方代表 | | | ☐ 通过 ☐ 带遗留项通过 |

遗留项与责任人：____________________________________________
