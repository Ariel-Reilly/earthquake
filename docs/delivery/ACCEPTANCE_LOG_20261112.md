# 运行时验收记录（2026-11-12）

> 环境：Windows 11 + Docker Desktop（PostgreSQL 15.8 / Redis / MinIO，EMQX 未启用）、
> 后端 Spring Boot 3.4.1（默认 profile，`AI_ENABLED=false` 以排除外部 AI 依赖）、
> 前端 Vite 6.4.3 dev server（`127.0.0.1:5173`，`/api`、`/ws` 代理到 `127.0.0.1:8080`）。
> 目的：把"代码/静态检查通过"升级为"真实服务端到端通过"，逐条对应 `SMOKE_TEST.md` 与交付修复项。

## 一、启动与基础健康

| 检查 | 结果 | 证据 |
|---|---|---|
| 后端启动 | ✅ 9.16s 启动，Tomcat 8080 | 启动日志 `Started DisasterguardApplication in 9.162 seconds` |
| `GET /api/system/health` | ✅ `code=0 data.status=UP`，`postgresql:OK` | HTTP 200 |
| 前端 dev server | ✅ `http://127.0.0.1:5173/` 200 | Vite ready in 429ms |
| Vite `/api` 代理 | ✅ `GET http://127.0.0.1:5173/api/system/health` 返回同上 | HTTP 200 |
| 新增/重写页面编译 | ✅ 重写后的 `WorkbenchPage.vue` 经 Vite 正常编译（模块 71KB） | HTTP 200 |

## 二、权限矩阵（本轮补齐 `tasks/backup/plans/reports` 后）

| 账号/角色 | 请求 | 结果 | 说明 |
|---|---|---|---|
| admin | `GET /api/tasks` | ✅ 200（原来 403） | `tasks:view` 修复生效 |
| duty / attendant | `GET /api/tasks` | ✅ 200 | 值守员可看任务 |
| aiengineer / ai_engineer | `GET /api/tasks` | ✅ 200 | AI 工程师可看任务 |
| sysadmin / system_admin | `GET /api/tasks` | ✅ 403（符合设计） | 系统管理员未被授予 tasks |
| sysadmin | `GET /api/backup/records` | ✅ 200 | `backup:view` 修复生效 |
| expert | `GET /api/plans` | ✅ 200 | `plans:view` 修复生效 |
| duty | `GET /api/audit/logs` | ✅ 403（符合设计） | 审计仅管理角色 |

> 注意：`/api/tasks` 返回 200 是"接口可访问"，列表内容是否为空取决于库内任务数据；本次库内有 5 条任务。

## 三、数据流与业务修复

| 检查 | 结果 | 证据 |
|---|---|---|
| 预警列表坐标（地图图层修复） | ✅ `GET /api/warnings` 返回 `lon/lat` 字段（示例 106.525 / 30.6） | JSON 响应 |
| 设备列表坐标 | ✅ `GET /api/devices` 返回 `lon=106.52525 lat=29.549063` | JSON 响应 |
| 预警"解除"状态机 | ✅ `POST /api/warnings/279/resolve` → `code=0`，状态 `已解除`（修复前必 409） | 后端日志 `Warning 279 resolved by admin` |
| 审计日志筛选契约 | （本轮改了后端 range/action + 前端分页/字段映射，未逐条构造测试数据，页面级待浏览器复核） | — |
| Analytics 巡检报告 | ✅ `synthetic=true, dataSource=SIMULATED` | `GET /api/analytics/inspection-report` |
| Analytics 时间对比 | ✅ `synthetic=true, dataSource=SIMULATED` | `GET /api/analytics/compare` |
| 待办汇总 | ✅ `GET /api/todo/summary` 200（pendingWarnings=2） | JSON 响应 |
| WS 一次性 ticket | ✅ `GET /api/auth/ws-ticket` 返回 32 位 ticket | JSON 响应 |

## 四、实时链路（本轮最重要的运行时发现）

**发现并修复的 P0**：`RealtimeBroadcastService` 用 `new ObjectMapper()` 序列化广播消息，
遇到 `WarningDto.triggeredAt`（`OffsetDateTime`）直接抛
`Java 8 date/time type not supported by default: ... jackson-datatype-jsr310`，
导致 **所有 WS 预警推送静默失败**（后端日志 `WebSocket 广播序列化失败 type=warning`），
前端只能靠轮询兜底。修复后改用 Spring 注入的 ObjectMapper + 显式 `JavaTimeModule`。

| 检查 | 结果 | 证据 |
|---|---|---|
| 后端 WS 直连握手（带 ticket） | ✅ `state=Open` | `/ws?ticket=...` |
| Vite `/ws` 代理握手 | ✅ 原始 TCP 返回 `HTTP/1.1 101 Switching Protocols` + 正确 `Sec-WebSocket-Accept` | 通过 5173 端口握手 |
| 创建预警后 WS 推送 | ✅ 收到 `{"type":"warning","data":{"id":281,"level":"橙色预警","status":"待确认","lon":106.53875,"lat":29.553875,"triggeredAt":"..."}}` | 真实 broadcast |
| 修复前对照 | ❌ 同一路径超时未收到消息，后端日志 serialize 失败 | 运行日志 |

## 五、会签 P0 修复（多会签人 + 冒名）

| 检查 | 结果 | 证据 |
|---|---|---|
| 创建 2 人会签 | ✅ `sign-6b160d63` 创建成功，`event_sign` 同 signId 写入 **2 行**（修复前第二行 UNIQUE 冲突 500） | `POST /api/events/EVT-SBOX-04/sign` |
| 旧库唯一约束 | ✅ 启动补丁已删除：`SELECT ... contype='u'` 返回 0 行 | PostgreSQL 查询 |
| 普通索引 | ✅ `idx_event_sign_sign` 存在 | `pg_indexes` |
| 冒名确认拦截 | ✅ admin 用他人 `signerId` 调 confirm → **HTTP 403** | `PUT .../sign/{signId}/confirm` |

## 六、测试数据清理

- 删除验收创建的预警 `280/281`（避免演示库出现测试记录）。
- 删除测试会签行 `sign-6b160d63`（`DELETE 2`）。
- 预警 279 的状态变更（已解除）保留，作为"解除链路已验证"的痕迹。

## 七、未覆盖（需浏览器/外部依赖）

- 浏览器视觉：全屏红色警报、橙色弹窗 3 秒跳转、声音、闪烁动画、移动端抽屉、深色模式、弹层层级。
- 外部瓦片/气象（ESRI/NMC）与高德 Key：依赖现场网络。

> 结论：本轮"代码可编译 + 接口跑通 + 实时推送跑通 + 权限/会签/解除状态机跑通"已达成；
> 剩余主要是浏览器视觉与外部依赖类验收，见 `KNOWN_LIMITATIONS.md` 与 `SMOKE_TEST.md`。

---

# 补充验收：AI 链路 + 审计日志 + 可访问性（同日第二轮）

## 八、AI 链路（`AI_ENABLED=true`，FastAPI 8001）

| 检查 | 结果 | 证据 |
|---|---|---|
| AI 服务启动 | ✅ uvicorn 8001，模型预热完成（YOLO/LSTM/IsolationForest/Fusion/Comprehensive） | 启动日志 |
| 模型健康 | ✅ `/api/ai/model/health` → `status=UP`，5 个模型 `model_loaded=true`、`inference_ok=true` | yolo 业务模型 2 类、xgboost_fusion v3.0.0、comprehensive v2.1.0（36 特征） |
| 后端 AI 健康 | ✅ `GET /api/ai/health` → `code=0 status=UP`，返回模型路径/大小/训练时间/指标 | 后端代理响应 |
| AI 综合评分 | ✅ `GET /api/ai/comprehensive-analysis/all` → 9 条 | JSON count=9 |
| AI 设备健康 | ✅ `GET /api/ai/device-health/all` → 16 条 | JSON count=16 |
| 多模态边界 | ✅ `GET /api/ai/multimodal/fuse` → **HTTP 501**（设计如此，前端已改为错误态不伪造分数） | 实测 501 |

> 注：模型指标中的 `eval_dataset=synthetic` 来自模型训练集标注（历史训练产物），
> 但本次是**模型真实加载并推理**，不是接口层 mock；响应不再出现 `mock:true`。

## 九、审计日志筛选（真实数据）

| 检查 | 结果 |
|---|---|
| 全量查询 | `GET /api/audit/logs?page=1&size=5` → total=**1405** |
| 按类型 + 时间范围 | `...&action=LOGIN&range=7` → total=**68**，说明 `range/action` 过滤真实生效 |
| 返回字段 | `operator/moduleName/actionName/requestPath/resultStatus/costMs/ipAddress/createdAt`（前端 `getAuditLogs` 已做字段映射，页面不再整表空白） |

## 十、可访问性/对比度（本轮收尾）

- `node scripts/audit-contrast.mjs`：**确定问题从 5 处降到 2 处**。
- 已修复：天气图例 `.wx-icon-btn`、地图图层弹窗 `.layer-popup-close`、人员轨迹 tooltip 系列
  硬编码颜色 → 改用 `--c-surface-2 / --c-text-2` 等主题令牌（深色模式可读）。
- 剩余 2 处（`ForbiddenPage/ServerErrorPage .btn-ghost:hover`）经核对为**审计脚本误报**：
  脚本只扫描 `styles.css` 的 dark 块，而深色令牌在 `tokens.css` 的 `html[data-theme="dark"]`，
  无法解析 `--c-text → --color-foreground` 的跨文件引用；实际深色下为浅字 #E6EEE9 配深底 #232F28。
  需浏览器最终确认，已在 `KNOWN_LIMITATIONS.md` 备注工具限制。
- AI 助手关闭按钮补 `aria-label="关闭 AI 助手"`；布局组件（汉堡菜单、通知铃铛、抽屉关闭）此前已有 aria-label。

## 十一、开发体验修复（Vite dev server 稳定性）

- 现象：代码编辑触发原子写临时目录 `.foo.vue.<pid>.tmpdir`，Vite 文件监听报 `EBUSY` 直接退出（本轮复现两次）。
- 修复：`frontend/vite.config.js` 增加 `server.watch.ignored: ['**/.*.tmpdir/**', '**/.*.tmp', '**/.dsh-*']`，
  编辑代码时不再需要重启 dev server。

## 十二、本轮结束状态

- 服务运行：后端 8080（AI 已启用）、前端 5173、AI 8001、Postgres/Redis/MinIO 容器。
- 回归：eslint 0 error、vitest 39 passed、vite build 成功、SFC 96/96、令牌完整性通过。
- 仍待浏览器：SM-05/06/07（警报动效/声音/3s 跳转）、SM-10（移动端）、深色模式整体观感、弹层层级、审计日志页面展示。

---

# 补充验收：真实浏览器端到端（CDP 自动化，同日第三轮）

## 十三、执行方式与结果

- 工具：本机 Edge 157 headless + CDP（`scripts/delivery/browser-smoke.mjs`，无需 Playwright/Puppeteer）。
- 结果：**19 / 19 全部 PASS**，截图见 `output/browser-verify/`，机器可读汇总 `output/browser-verify/summary.json`。

| 用例 | 结果 | 关键证据 |
|---|---|---|
| 页面内登录（admin）并写入 localStorage | ✅ | role=admin |
| 设备页加载、无 403/白屏 | ✅ | `.main-layout` 渲染，正文无 403 |
| **SM-07 设备页触发红色预警 → 全屏警报** | ✅ | `.alarm-overlay` 出现；`position=fixed`、`inset=0`、`z-index=99999`、背景 `rgba(255,0,0,.95)` |
| 红色警报样式/按钮/音频 | ✅ | 卡片动画结束 `opacity=1`；按钮「查看详情 / 确认收到」；无"浏览器拦截"提示（音频已启动，headless 无法听声） |
| 确认收到后关闭 | ✅ | 点击后 `.alarm-overlay` 消失 |
| 红色警报「查看详情」 | ✅ | 跳转 `/dashboard/warnings/288` |
| **SM-06 橙色预警 → 普通弹窗 + 3 秒跳详情** | ✅ | `.warn-card` 出现；提示"3 秒后自动打开预警详情页"；3 秒后跳 `/dashboard/warnings/289` |
| **SM-10 移动端 390px** | ✅ | 汉堡按钮可见；点击后侧栏 `is-mobile-open` + 遮罩；`scrollWidth=390=clientWidth`，无横向溢出 |
| 深色模式令牌 | ✅ | `data-theme=dark` 后 body 背景 `rgb(21,28,24)`（深）、文字 `rgb(230,238,233)`（浅） |
| 测试数据清理 | ✅ | 自动删除本次创建的预警 287/288/289 |

## 十四、红橙分级修正（用户反馈）

用户明确要求"红色预警是红色、橙色是橙色，不要搞混"，本轮据此修正：

1. **视觉不再混色**：红色满屏警报原先沿用通用警示黄（图标/等级徽标/提示行为黄色系），现已改为纯红白体系
   （红遮罩、深红卡片、白图标、白底深红字"红色预警"徽标、白色按钮）；橙色弹窗保持橙/白体系。
2. **判定不再混级**：`alarmSeverityOf` 改为**等级文字优先、cssClass 仅兜底**，避免后端
   `level=橙色预警` 但 `cssClass=red` 时被误判成红色满屏；新增单测锁定
   "橙+red → warning""红+orange → critical"。
3. 证据：截图 `01-red-alarm-devices-page.png`（红）、`03-orange-popup.png`（橙）；单测 40 passed。

> 结论：三轮验收后，SM-05/06/07/10 与安全回归 SM-15 已由真实浏览器/接口自动化覆盖；
> 剩余未覆盖：SM-02/03/04（首页/地图/预警确认派发页面级）、SM-09（审计页展示）、
> SM-11/12（宕机与 WS 断线降级）、SM-13/14（导出/登出）。

---

# 补充验收：扩展浏览器冒烟（会话恢复后第四轮）

## 十五、扩展用例与结果

环境重建（Docker + Postgres/Redis/MinIO + 后端 8080（AI 启用）+ 前端 5173 + AI 8001）后，浏览器冒烟脚本新增
首页、实时地图、审计日志、API 宕机模拟、恢复、登出 6 类用例，最终 **28 / 28 PASS**（15 张截图）。

| 新增用例 | 结果 | 证据 |
|---|---|---|
| SM-02 综合态势首页 | ✅ | `.overview-page` 渲染、4 张统计卡、无 `overview-load-error`、无"正在加载"卡死 |
| SM-03 实时地图 | ✅ | `.real-time-map-shell` + OL canvas 渲染、无"地图加载失败"、非空态 |
| SM-09 审计日志 | ✅ | 列表 20 行、无空态；点击「登录登出」筛选后仍有数据 |
| **SM-11 后端不可达（CDP 阻断 `/api`）** | ✅ | 预警页显示"预警列表加载失败"，且**不渲染空态/不出现"当前没有活跃预警"**；首页显示"态势数据加载失败 + 重新加载"，不永久 loading；解除阻断后页面恢复正常 |
| SM-14 登出 | ✅ | 用户菜单→退出登录→回到 `/`，`disasterguard_token` 已清空 |
| SM-04 预警生命周期（接口级） | ✅ | 待确认 → 确认=已确认 → 派发=处理中 → 关闭=已关闭（均 200）；测试数据已删除 |
| SM-13 导出（接口级） | ✅ | `POST /api/warnings/export` 200 + xlsx 6345B；`/api/devices/export` 200 + xlsx 5179B |

截图：`08-overview-page.png`、`09-realtime-map.png`、`10-audit-log.png`、`11-after-logout.png`、
`12-api-down-warnings.png`、`13-api-down-overview.png`、`14-recovered.png`。

## 十六、本轮遗留

- SM-12（WS 断线降级计时/重连提示）仍为部分覆盖：需要真实断开 WS 后观察轮询节奏，建议演示前人工做一次。
- SM-01/SM-08 的"菜单/页面级"仍需人工快速点检（接口权限矩阵已自动化通过）。
- 页面内音频是否真实发声无法在 headless 中验证；已通过"无 autoplay 拦截提示 + 声音单测"间接覆盖，建议现场外放确认。

---

# 补充验收：SM-01/08/12 与无障碍收口（第五轮）

## 十七、本轮新增用例（32/32 PASS）

| 用例 | 结果 | 证据 |
|---|---|---|
| SM-08 AI 巡检任务页 | ✅ | `/dashboard/ai-tasks` 正常渲染，无 403/无权/请求失败 |
| **SM-12 WS 阻断 → 轮询降级** | ✅ | CDP 只阻断 `/ws?*` 端点后页面正常加载；新建蓝级预警后「活跃」计数由 4 增长、点开"查看全部"可见新预警标题 → 证明轮询链路在工作 |
| SM-01 角色越权 | ✅ | 值班员（attendant）可进预警中心；直达 `/dashboard/user-management` 被重定向到 `/dashboard/error/403` |

> 排障记录（避免误判为产品缺陷）：
> 1) 最初用宽泛阻断 `*ws*` 会误伤 `src/utils/wsUrl.js` 模块导致白屏 → 改为精确阻断 `*://*/ws?*`；
> 2) SM-12 初次断言只查可见文本，而预警列表默认折叠前 3 条、新预警按 AI 分排序在尾部 → 改为断言「活跃」计数增长 + 点开查看全部后查标题。
> 两处均为测试脚本问题，不是应用缺陷。

## 十八、无障碍收口（本轮修复）

用真实浏览器 DOM 扫描关键页面（首页/预警/设备/AI 任务/审计/实时地图等）的
"无文字且无 aria-label/title"按钮，共发现 6 处并全部修复：

| 位置 | 按钮 | 修复 |
|---|---|---|
| `OverviewPage.vue` | 刷新实时天气（纯图标） | 补 `aria-label` + `title` |
| `OverviewPage.vue` | 点位详情关闭按钮 | 补 `aria-label` + `title` |
| `OverviewPage.vue` | 无 handler 的"更多操作"死按钮 | 直接删除（避免无效可点击） |
| `RealTimeMapPage.vue` | 关闭图层面板 | 补 `aria-label` + `title` |
| `RealTimeMapPage.vue` | 放大 / 缩小地图 | 补 `aria-label` + `title` |

回归：eslint 0 error、vitest 40 passed、vite build 成功、浏览器冒烟 **32/32 PASS**。

## 十九、剩余

- 声音实际外放、SM-04 页面按钮点击、SM-12 重连计时：需人工/演示现场确认。
- P1 未做：系统设置落库、时相对比页面、业务页深色硬编码残余、stylelint 基线（约 1800 处旧写法）。
- 交付前建议：按 `DELIVERY_CHECKLIST.md` 做一次 30 分钟部署演练 + 录屏。

---

# 补充验收：AI 研判四档颜色告警（第六轮）

## 二十、需求与实现

用户明确：预警颜色由 **AI 研判数值区间**决定 —— 红色对应一个阶段、橙色另一个区间、黄色另一个区间、蓝色另一个区间。
核对项目既有实现，阈值来源一致，无需另造：

| AI 分数区间（ai-service） | AI 等级 | 后端折算预警等级（AiWarningScheduler） | 前端警报表现 |
|---|---|---|---|
| ≥ 80 | CRITICAL | 红色预警 | **满屏红色警报** + 持续声音 + 闪烁，必须「确认收到」 |
| 60 – 80 | HIGH | 橙色预警 | 橙色弹窗 + 3 秒自动跳详情（可「关闭」取消） |
| 35 – 60 | MEDIUM | 黄色预警 | 黄色弹窗 + 3 秒自动跳详情 |
| < 35 | LOW | 蓝色预警 | 蓝色弹窗 + 3 秒自动跳详情 |

实现要点：
1. `utils/alarmWarning.js` 增加 `AI_RISK_BANDS`（80/60/35）与 `severityFromAiScore`；
   警报判定按 **等级文字（后端按 AI 分数折算的权威值）优先 → aiLevel → cssClass 兜底**，
   四档严格分开，避免数据不一致时红橙黄蓝混判。
2. `GlobalAlarmModal.vue`：红色仍为满屏；橙/黄/蓝为同一弹窗组件按 `severity-*` 切换整卡主题色
   （顶边、图标、徽标、提示、进度条、主按钮全部随档位），仍保留 3 秒跳转与「关闭」。
3. 单测：80/60/35 边界、四档映射、等级优先、aiLevel/cssClass 兜底、cssClass 空值兜底；
   浏览器冒烟新增黄/蓝弹窗用例。

## 二十一、验证结果

- 单测：**43 passed**（4 个测试文件）；eslint 0 error；vite build 成功。
- 真实浏览器：**34/34 PASS**，四档颜色截图与计算色一致：
  - 红 `01-red-alarm-devices-page.png`（满屏 rgba(255,0,0) + 双按钮）
  - 橙 `03-orange-popup.png`（badge「橙色预警」）
  - 黄 `03b-yellow-popup.png`（badge「黄色提示」，顶边 rgb(201,162,39)，按钮深金 #8a6d00）
  - 蓝 `03c-blue-popup.png`（badge「蓝色关注」，顶边 rgb(74,127,165)，按钮深蓝 #2f5f80）

## 二十二、排障记录（Vite 样式缓存，非产品缺陷）

验收中发现黄色/蓝色弹窗的**按钮与提示仍是橙色**：页面运行时 CSS 规则显示旧硬编码 `#a85a06`，
而磁盘与 `fetch` 到的最新 CSS 是 `var(--warn-accent-strong)`。根因是 Vite dev server 的样式子模块缓存
（带 `?t=` 的模块 URL 返回旧内容），重启 dev server 后计算色变为 `rgb(47,95,128)`（蓝）。
为避免此类缓存污染验收，`browser-smoke.mjs` 已加入 `Network.setCacheDisabled(true)`；
生产构建（`vite build`）不含该问题。

## 二十三、剩余

- 黄色/蓝色弹窗属于"非红色也提醒"的四档需求；若现场希望降低打扰，可在 `useWarningAlarm` 收窄档位。
- 其余遗留同十九节：人工听声、页面按钮点击、P1 设置落库/时相对比/深色残余/stylelint。

---

# 补充验收：设置落库、警报声兜底、AI 分析详情（第七轮）

## 二十四、系统设置真正落库（P1 已修复）

- 后端新增 `system_setting` 表（启动幂等补建，旧数据卷也生效）+ `GET/PUT /api/settings`
  （权限沿用 `settings:view` / `settings:edit`，只接受白名单字段）。
- 前端设置页：打开时从后端回填、保存时后端 upsert + localStorage 离线兜底；服务器失败时明确提示
  "已暂存到本地"，不再让用户误以为已持久化。
- 验证：接口 PUT→GET 持久化通过；真实浏览器用例 **SM-16**"保存 → 刷新 → 仍存在"通过
  （截图 `18-settings-persisted.png`）；测试后平台名已恢复默认。

## 二十五、红色警报声可靠性增强

用户反馈"红色预警时界面应出现警报声"，本轮加强兜底：
1. `useAlarmSound.unlock()`：首次 `pointerdown/keydown` 预热 `AudioContext`，避免用户登录后未点击页面时
   收到红色预警被浏览器自动播放策略静默拦截；
2. 红色警报界面：被拦截时显示**「开启警报声」按钮**（原提示不可点），点击遮罩任意位置也会尝试续播；
3. 单测新增 `unlock`（预热成功但不发声）用例，vitest **44 passed**；
4. 严格 autoplay 策略的 headless 验证：该参数下 headless 未真正拦截（路径=audio-allowed），
   拦截分支由单测覆盖；真实浏览器（Chrome/Edge）首次点击后即可出声。

## 二十六、预警「AI 分析详情」新增（用户需求）

新增预警详情第 2 个 Tab「AI 分析详情」，一屏回答"为什么报警、当时周围什么情况"：

| 区块 | 内容与数据源 |
|---|---|
| AI 研判结论 | 综合评分/等级/关注度/灾害类型/置信度/模型/分析时间/维度得分；`ai_comprehensive_score` 落库快照 |
| AI 详细分析 | `reasoning` 研判依据 + `suggestedActions` 建议动作 |
| 事发前后关键指标 | 触发时刻 **±2h** 的真实 `device_metric` 时序（同指标多设备聚合）、触发前/后值、变化率、min/avg/max；无样本时回退最新快照并**明确标注** |
| 现场影像 | 灾害点/预警附件图片 + 关联设备最近上传 MinIO 影像（预签名 URL）；加载失败显示占位说明，不伪造图片 |

- 后端：`GET /api/warnings/{id}/analysis-detail`（`WarningAnalysisService`），
  `DeviceMetricRepository.findByHazardCodeAndRecordedAtBetween`、`DeviceImageService.listRecent`。
- 前端：`components/warning/WarningAnalysisDetail.vue`，预警详情新增 Tab。
- 浏览器验证：HZ-001 测试预警 → 4 个区块全部渲染、2 个指标序列、3 个影像位（2 张设备影像实际加载、
  1 张历史附件地址失效显示占位）；截图 `21-warning-analysis-detail.png`（自动化用例 SM-18）。
- 页面级预警闭环（确认→派发）已纳入自动化 SM-17，截图 `20-warning-confirm-dispatch.png`。
- 已知边界见 `KNOWN_LIMITATIONS.md` L-07b：影像需要真实上传，种子附件地址可能失效。

## 二十七、回归

- eslint 0 error；vitest **44 passed**；vite build 成功；后端编译通过；浏览器冒烟 **37/37 PASS**（截图 23 张）。
- 浏览器脚本已重写为无缓存（`Network.setCacheDisabled(true)`）、自动清理测试数据的 37 项套件；
  覆盖四档告警、确认→派发、AI 分析详情、设置落库、API 宕机、WS 轮询降级、角色 403、登出。
- 测试数据已清理（分析详情验收预警 378/379 已删除，平台名恢复 `地脉智巡`）。

## 二十八、A2 源码整理 / A3 干净机器部署演练（2026-11-13）

### A2-1 交付提交排除项

- `.gitignore` 新增排除：`/output/`（含 CDP profile，639M）、`/generated-videos/`、根目录 `*.pptx`、
  `/御灾智巡/`、演示流程 md、交付源码包/压缩包/校验和。
- 效果：`git status --porcelain` 项数 **110 → 88**，剩余全部为应提交的业务代码、交付文档、
  迁移脚本与 CI 配置；`git check-ignore -v` 逐项验证生效。

### A2-2 演示材料归属（⚠ 待项目经理确认）

- 盘点：根目录 **19 个 `.pptx`（合计 325 MB）**、`御灾智巡/`（4 文件 / 1.4 MB）、
  `御灾智巡_现场演示流程_操作融合稿.md`（63 KB）。
- **当前保守处置**：不纳入源码仓库与交付提交，按「单独演示材料包」口径管理，已在 `.gitignore` 排除。
  若项目经理决定纳入仓库，去掉对应 ignore 规则即可，完全可逆。

### A2-5 提交内容复核

- 3 个删除组件 `WarningResolveTab.vue` / `WarningResolveForm.vue` / `WarningResolvePreview.vue`：
  全仓搜索**无代码引用**；仅 `SMOKE_TEST.md` 有一处过期行号引用，已改为当前
  `WarningsPage.vue:334-420`（原因弹窗 `:850-890`）。其余同名命中为 `WarningResolvePage`（不同文件）
  或 `output/` 下的 CDP 缓存（已忽略）。
- 门禁实跑：`npx eslint src/` 0 error、vitest **44 passed**（4 文件）、SFC **97/97** 通过、
  设计令牌 99 文件 / 295 定义无未定义引用与依赖环、`npm run build` ✓ built in 5.16s、
  `mvn -DskipTests package` 通过（jar 122 MB）。
- 构建踩坑：旧 `dist/` 触发本机文件删除保护导致 Vite 无法清空目录；改名保留为
  `frontend/dist.bak-delivery-20261113`（回滚用）后构建到全新空目录成功，非产品缺陷。

### A3 干净机器部署演练（隔离环境，不触碰开发库）

编排 `scripts/delivery/compose.delivery-verify.yml`：project `dg-delivery-verify`，
端口 25432 / 26379 / 29000+29001 / 25173，后端 28080 / 28081 / 28082，独立命名卷。
日志：`output/delivery-verify/backend-{fresh,patch-1,patch-2}.log`。

| 阶段 | 结果 |
|---|---|
| 空卷 `up -d --wait` | 三容器 healthy；initdb `complete; ready for start up` |
| 后端空库首启 | 17.9s 启动并自动建表/种子；`/api/system/health` UP + PostgreSQL OK；admin 登录 200；`/api/warnings`、`/api/devices`、`/api/rules`、`/api/settings`、`/api/hazards`、`/api/dashboard/overview` 全 200 |
| 规则引擎 | `rule` 表 6 条、`actions` 数组结构、启动日志 0 ERROR；`WarningCreateService` 按真实指标自动生成 `WG-*` 预警 |
| A3-2 历史库补丁 | 人为加回 UNIQUE + 删 `system_setting` 后重启：唯一约束 0 / 表存在 / `idx_event_sign_sign` 存在 / 0 ERROR；再启动一次状态不变 |
| A3-3 nginx 反代 | `/` 200、`/healthz` 200、`/api/system/health` 200、`/ws?ticket=` **101 Switching Protocols** |
| A3-4 手册复核 | `DEPLOYMENT_GUIDE.md` §9、`DEPLOYMENT.md`、`DELIVERY_CHECKLIST.md`、`README.md`、`SMOKE_TEST.md` 已同步 |

**本轮发现并修复的 4 个全新部署 P0 阻断**（详见 `DEPLOYMENT_GUIDE.md` §9.2 / `KNOWN_LIMITATIONS.md` 〇节）：

1. `initdb.d` 脚本依赖应用侧才建的表 → postgres `Exited(3)`：改为只挂载 001/003。
2. `data.sql` 应急响应引用已停用预警 `WG-001` → 外键失败、后端启动中止：改为子查询降级 NULL。
3. 规则镜像 `created_by='SYSTEM'` 无对应 `sys_user` → 6 条规则写不进：改 NULL。
4. `rule.actions` 写成对象 → 6 条反序列化失败：改数组。

**WS 验证注记**：本机 `HTTP_PROXY` 使 curl 经代理时剥掉 `Upgrade` 头，裸 curl 握手 400、后端报
`invalid Upgrade header: null`；加 `--noproxy '*'` 后拿到 101。属验证环境问题，非 nginx/后端缺陷。

### 仍未关闭（按 backlog）

A1 现场人工验收、A4 生产安全配置、A5 备份恢复演练、A6 甲方/安全决策项；
B/C 类 P1/P2 项未纳入本轮。**演示可用 ≠ 试运行上线条件已满足**。

---

## 二十九、A4 生产安全加固 / A5 备份恢复演练（2026-11-13）

### A4 生产安全（代码/工具侧完成，环境项待上线日）

1. **配置检查器** `scripts/delivery/prod-config-check.mjs`（只读，21 项）：
   - 对开发 `.env`：按预期报 **13 error**（DEV_USERS/DEVICE_AUTH/WS/域名/密钥等），退出码 1；
   - 对模拟强密钥 env：**21/21 PASS**，退出码 0。
2. **生产 env 模板** `docs/delivery/env.production.example`：JWT/AES/DB/Redis/MinIO/EMQX 全部
   `__GENERATE__` 占位；配合 `docs/delivery/PRODUCTION_HARDENING.md` 的生成与执行步骤。
3. **prod 启动 fail-fast 实测**：`SPRING_PROFILES_ACTIVE=prod` + 密钥合格但不安全开关
   （DEV_USERS=true / DEVICE_AUTH_STRICT=false / WS_REQUIRE_TICKET=false / localhost 域名）
   → `IllegalStateException: prod 配置校验失败（4 项）`，进程退出 1
   （日志 `output/delivery-verify/prod-validator-fail2.log`）。
   另一次试验中 MinIO 空凭证在更早阶段报 `AccessKey and SecretKey must not be empty`，同样拒绝启动。
4. **端口收敛**：
   - `.env.example`：`AI_HOST_PORT=127.0.0.1:8001`（原裸端口会暴露到 0.0.0.0）；
   - `docker-compose.prod.yml`：后端默认 `${BACKEND_HOST_PORT:-127.0.0.1:8081}`，公网仅 frontend nginx；
   - AI/DB/Redis/MinIO 均不直接暴露公网（部署时用网络策略再兜底）。
5. **compose 校验**：`docker compose --env-file docs/delivery/env.production.example
   -f docker-compose.yml -f docker-compose.prod.yml config --quiet` 退出码 0。
6. 仍待上线日：真实域名/证书、强密钥注入、防火墙、改初始密码、审计留存（`PRODUCTION_HARDENING.md` §五）。

### A5 备份与恢复演练（已关闭）

| 项 | 结果 |
|---|---|
| Postgres dump | `dg_20261113.dump` **434,253 B**，SHA256 `6083988687BB726588D2B2DAB38FDAF6A42D4BDF96F680D9A13675ED7C8C9434` |
| 恢复 | 新建 `dg_restore_verify` → `pg_restore` 退出码 0 |
| 数据完整性 | 8 张关键表行数逐一相等：预警 46、设备 16、审计 1764、规则 6、设置 1、灾害点 9、AI 综合分 9、指标 5899；平台名读回 `地脉智巡` |
| MinIO | 5 个对象；宿主 tar 包 **119,110 B**，SHA256 `E548ED8464B536CD2A05CF82EC3C66138278C11485C9D8E99A7AB4EEC4C51B38`；解包后对象数 5=5 |
| 恢复后应用验证 | 恢复库上启动独立后端（18080）：`/api/system/health`、admin 登录、`/warnings`、`/devices`、`/settings`、`/rules`、`/audit/logs`、`/dashboard/overview` **全部 code=0** |
| 清理 | 临时库、容器临时文件、恢复实例均已删除；备份产物留在 `output/backup/`（已 gitignore） |

> 演练同时验证了"恢复后应用可启动、真实数据可读"，不是仅比对文件大小。
> 建议上线后按日执行 `pg_dump` + MinIO 同步，并每季度重复一次本演练。

### 本轮后剩余（P0）

- A1 现场人工验收（声音/WS 重连/真机/页面按钮/演示彩排）
- A4 环境项（域名证书/密钥注入/防火墙/改密/审计留存）
- A6 甲方/安全决策项（sign:proxy、时相对比范围、演示数据水印、地图合规、时间显示）
- A2-2 演示材料归属确认
- B/C 类 P1/P2 不变

---

## 三十、B2 会签语义 / B4 Stylelint 基线 / B5 测试补强（2026-11-13）

### B2-1 会签 signerId 语义统一

- 前端 `ConfirmationSignPage.vue` 新增 `currentSignerId()`：从 `selectedRecord.signers` 中按当前用户名
  取**真实 signerId**（发起时写入的 `sys_user.id`），确认/驳回不再把 `operator.name`（username）传给后端；
- 增加"未找到当前用户会签身份"的显式提示；后端对历史两种写法的兼容逻辑保留，旧记录不受影响；
- 回归：`npx eslint src/` 0 error、vitest 44 passed、`npm run build` 成功。
- 剩余：B2-2 多人会签 PostgreSQL 真机验证（需现场/API 全流程）、B2-3 代签决策。

### B4 Stylelint 基线

- 实测 `npx stylelint "src/**/*.{css,scss,vue}"`：**5,264 条**；
  Top：`declaration-block-single-line-max-declarations` 2,273、`rule-empty-line-before` 1,139、
  `alpha-value-notation` 680、`color-function-notation` 680、`media-feature-range-notation` 98、
  `no-duplicate-selectors` 72、`property-no-vendor-prefix` 39。
- CI 新增 `Stylelint (advisory baseline)`（不阻断）+ `stylelint-report` artifact；
- 分批治理计划见 `STYLELINT_BASELINE.md`（建议 `--fix` 低风险批次 → 手工单行多声明 → 转阻断）。

### B5 后端单测补强

- 新增 `SystemSettingServiceTest`（3 用例）：损坏 JSON 降级、白名单过滤、合并式 upsert；
  **测试发现真实缺陷**：历史设置中的非白名单字段会被合并写回 → `SystemSettingService.load()`
  现统一按白名单过滤（修复后再测通过）。
- 新增 `WarningAnalysisServiceTest`：AI/指标/影像全部缺失时返回
  `ai=null / metrics.fallback=true / images=[]`，接口不 500。
- 运行：`cd backend; mvn -q "-Dtest=SystemSettingServiceTest,WarningAnalysisServiceTest" test` → 通过。
- 剩余：B5-1 预警状态机单测、B5-4 前端组件测试（需评估是否引入 @vue/test-utils）、B5-5
  ai-service 10 个 pytest 实跑。

### 回归

- 前端：eslint 0 / vitest 44 / build 成功；后端：`mvn compile` 通过、新增单测通过；
- 设置接口运行时复验：GET/PUT/回读 code=0，18 个白名单字段返回正常。
