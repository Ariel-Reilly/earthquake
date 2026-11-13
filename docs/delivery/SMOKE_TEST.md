# 交付冒烟测试清单（Smoke Test）

> 适用版本：DisasterGuard / 御灾智巡 v2.0（工作区快照 2026-11-12）
> 15 条用例覆盖角色权限、核心业务闭环、告警呈现、降级与安全回归。
> **执行前提**：按 `docs/delivery/DEPLOYMENT_GUIDE.md` 启动后端 8080、前端 5173、AI 8001、基础设施容器健康；`VITE_USE_BACKEND=true`。
> **状态列约定**：`未执行`＝本次沙箱无法启动服务，须现场执行；`部分覆盖`＝仅有静态证据。
> 行号为当前工作区快照，若并行修复改动会漂移。

| 编号 | 用例 | 状态 |
|---|---|---|
| SM-01 | 6 角色菜单与权限 | ✅ **真实浏览器通过**：值班员（attendant）可进预警中心；直达 `/dashboard/user-management` 被 403 拦截（`/dashboard/error/403`）；接口级 6 角色 200/403 矩阵此前已实测 |
| SM-02 | 综合态势首页 | ✅ **真实浏览器通过**：`.overview-page` 渲染、4 张统计卡、无加载失败/永久加载中；截图 `08-overview-page.png` |
| SM-03 | 实时地图点位 | ✅ **真实浏览器通过**：`.real-time-map-shell` + OpenLayers canvas 渲染、无"地图加载失败"；截图 `09-realtime-map.png` |
| SM-04 | 预警「确认 → 派发 → 解除」闭环 | ✅ **接口级通过**：待确认→已确认→处理中→已关闭；解除链路 200/已解除；页面按钮点击待人工复核 |
| SM-05 | 红色预警全屏警报 + 声音 | ✅ **真实浏览器（CDP）通过**：全屏 fixed/99999/rgba(255,0,0)、卡片 opacity=1、双按钮、确认关闭；无 autoplay 拦截提示（headless 无法听声，声音逻辑有 5 个单测覆盖） |
| SM-06 | 橙色预警弹窗 3 秒自动跳详情 | ✅ **真实浏览器通过**：`.warn-card` 出现 → 3 秒后跳 `/dashboard/warnings/{id}` |
| SM-07 | 在设备页停留时再触发红色预警 | ✅ **真实浏览器通过**：在 `/dashboard/devices` 停留时触发红色预警 → 全屏警报弹出 |
| SM-08 | AI 巡检任务列表/详情不 403 | ✅ **真实浏览器通过**：`/dashboard/ai-tasks` 正常渲染、无 403/无权/请求失败；接口级 6 角色矩阵此前已实测 |
| SM-09 | 审计日志筛选 | ✅ **真实浏览器通过**：列表 20 行、无空态；点击「登录登出」筛选后仍正常；截图 `10-audit-log.png` |
| SM-10 | 手机 768px 宽度布局 | ✅ **真实浏览器通过**：390px 下汉堡按钮可见、侧栏抽屉可打开、`scrollWidth=clientWidth` 无横向溢出 |
| SM-11 | 后端宕机提示与降级 | ✅ **真实浏览器通过**（CDP 阻断 `/api` 模拟宕机）：预警页显示"加载失败"且不渲染空态、首页显示错误+重试不永久加载；恢复网络后可正常加载；截图 `12/13/14` |
| SM-12 | WS 断线自动降级为轮询 | ✅ **真实浏览器通过**（CDP 只阻断 `/ws?*` 端点）：阻断后页面正常加载，创建新预警后经轮询把"活跃"计数刷新并显示新预警标题；截图 `16-ws-blocked-polling.png`（重连计时仍建议现场观察） |
| SM-13 | 报表导出 | ✅ **接口级通过**：`POST /api/warnings/export`、`/api/devices/export` 均 200 + xlsx（6.3KB / 5.2KB）；页面下载按钮未自动化 |
| SM-14 | 登出 | ✅ **真实浏览器通过**：用户菜单→退出登录→回到 `/` 且 `disasterguard_token` 清空；截图 `11-after-logout.png` |
| SM-15 | 会签冒名防护（安全回归，本轮新增） | ✅ 接口级已实测：管理员代签他人 signerId → HTTP 403；多会签人创建成功（2 行同 signId） |
| SM-16 | 系统设置落库（保存 → 刷新仍存在） | ✅ **真实浏览器通过**：设置页保存平台名 → 刷新页面仍从后端读回；后端 `system_setting` 表 + `GET/PUT /api/settings`；截图 `18-settings-persisted.png` |
| SM-17 | 预警详情页「确认 → 派发」闭环 | ✅ **真实浏览器通过**：详情页点「确认预警」→ 状态"已确认"；点「派发资源」→ 状态"处理中/已发布"；截图 `20-warning-confirm-dispatch.png` |
| SM-18 | 预警「AI 分析详情」 | ✅ **真实浏览器通过**：4 个区块（AI 研判结论 / 详细分析 / 事发前后指标 / 现场影像）、2 个真实指标序列、3 个影像位（2 张设备 MinIO 影像实际加载）；截图 `21-warning-analysis-detail.png` |

> **自动化执行**：`node scripts/delivery/browser-smoke.mjs`（需先以 `--remote-debugging-port=9222` 启动 Edge/Chrome，
> 且后端 8080、前端 5173 已运行）。脚本会自动登录、触发红色/橙色/黄色/蓝色预警、保存并回读系统设置、
> 执行确认→派发闭环、打开 AI 分析详情、截图到 `output/browser-verify/`、校验后自动删除测试预警。
> 当前一轮结果：**37/37 PASS**（截图 23 张，含四档颜色、API 宕机、WS 阻断轮询、角色越权、设置落库、确认→派发、分析详情场景）。

> **AI 研判 → 四级颜色（与后端一致）**：ai-service `fusion_engine.py` / `comprehensive_model.py` 的阈值
> `≥80 CRITICAL / 60-80 HIGH / 35-60 MEDIUM / <35 LOW`，后端 `AiWarningScheduler` 折算为
> 红 / 橙 / 黄 / 蓝色预警。前端 `alarmWarning.js` 按此映射警报档位，并以等级文字为权威、
> AI 原始等级与 cssClass 兜底：
> - **红色（AI≥80）**：满屏红色警报 + 声音 + 闪烁，必须手动「确认收到」；
> - **橙/黄/蓝（AI 60-80 / 35-60 / <35）**：对应颜色普通弹窗 + 3 秒自动跳详情（可点「关闭」取消）。
>
> 四档颜色已用真实浏览器验证：`03-orange-popup.png`（橙）、`03b-yellow-popup.png`（黄）、
> `03c-blue-popup.png`（蓝）、`01-red-alarm-devices-page.png`（红），单测覆盖 80/60/35 边界。

---

## SM-01 6 角色菜单与权限

**目的**：确认 6 个业务角色（系统管理员 / 应急指挥 / 值班员 / 领域专家 / AI 工程师 / 现场运维）+ 超管各自菜单与操作权限正确，直接输入 URL 越权时跳 403。

**步骤**
1. 用 `admin` 登录，记录侧边栏菜单项数量与首页路由。
2. 依次用 `system_admin`、`emergency_commander`、`attendant`、`expert`、`ai_engineer`、`maintenance` 账号登录（账号由部署人员提供）。
3. 每个角色对照 `frontend/src/stores/auth.js:50+` 的 `ROLE_MENU_MAP` 检查侧边栏。
4. 每个角色手工访问一个不属于自己的路由（如运维访问 `/dashboard/user-management`；系统管理员访问 `/dashboard/warnings`），观察跳转。
5. 每个角色进入权限范围内的页面，点一次写操作按钮（如值班员在预警页点「确认」、专家在会签页发起会签）。

**预期**
- 角色名带下划线（`system_admin`）与连字符（`system-admin`）均能正确映射，不回落到 admin 菜单 → `auth.js:45-48 normalizeRole`。
- 无路径权限 → 跳 `/dashboard/error/403`（`reason=no-path`）；有路径无权限码 → 跳 403（`reason=no-permission`，`frontend/src/router/index.js:547-589`；权限码映射 `:130-186`）。
- 按钮级权限由 `hasPermission` 控制，无权限时按钮不显示或被 `PermissionButton` 拦截提示。
- 后端接口层同样拒绝：`PermissionRegistry.permissionsOf(role)` 决定 `@PreAuthorize` 是否通过（`backend/.../common/security/PermissionRegistry.java`）。

**备注**：代码中实际定义了 7 个角色（含 `admin` 超管），任务描述的「6 角色」按 6 个业务角色交付；超管单独验收。

---

## SM-02 综合态势首页

**步骤**
1. 任意角色登录后进入 `/dashboard`。
2. 等待 5 秒观察 KPI 卡片、趋势图、预警摘要是否加载完成。
3. 打开浏览器 Network，确认请求 `/api/dashboard/overview` 等返回 200 而非 401/500。

**预期**
- 页面左下角运行模式角标为「后端连接中/正常」，不出现「演示模式」（`frontend/src/App.vue:14-44`）。
- 数据非零且与后端 `/api/system/health` 的 UP 状态一致；加载失败时显示明确错误而非空白/「无预警」（`frontend/src/views/OverviewPage.vue:1579`）。
- 控制台无未捕获异常（ErrorBoundary 会兜底）。

---

## SM-03 实时地图点位

**步骤**
1. 进入 `/dashboard/real-time-map`。
2. 检查灾害点、设备、预警三类点位是否渲染，缩放/切换图层是否可用。
3. 无高德 Key 的部署环境，确认底图自动降级到天地图/ESRI，不出现大面积灰格。

**预期**
- 点位来自后端真实数据（`VITE_USE_BACKEND=true`）；`mapService` 的瓦片兜底链路生效（`frontend/src/services/_architecture/core/mapService.js:160-200,441`）。
- 地图不因单一瓦片 404 整页崩溃；无 Key 时控制台有明确降级信息。
- 点位弹窗跳转灾害点/预警详情正常。

---

## SM-04 预警「确认 → 派发 → 解除」闭环

**步骤**
1. 值班员进入 `/dashboard/warnings`，选择一条「待确认」预警。
2. 点「确认」（需要 `warnings:confirm`）。
3. 点「派发」（需要 `warnings:dispatch`），观察橙色及以上是否提示联动应急响应。
4. 预警进入「处理中/已派发」后，点「自动解除」并填写解除原因，提交。
5. 切换到「已解除归档」Tab，确认该预警已归档且解除原因/时间可查。
6. 到 `/dashboard/audit-log` 确认对应 WARNING_CONFIRM / WARNING_DISPATCH / WARNING_CLOSE 记录存在。

**预期**
- 状态机按「待确认 → 已确认 → 已派发 → 已解除/已关闭」推进（`frontend/src/views/warning/WarningsPage.vue:161-165`）。
- 解除必须填写原因；取消不落库（`frontend/src/views/warning/WarningsPage.vue:334-420`，原因弹窗 `:850-890`）；已删除的 `WarningResolveTab/Form/Preview.vue` 不再引用。
- 后端真实写入并产生审计日志；刷新页面状态不丢失。
- 派发红/橙预警时后端按等级联动应急响应（`WarningsPage.vue:317-330`）。

---

## SM-05 红色预警全屏警报 + 声音

**步骤**
1. 用管理员登录并停留在任意 `/dashboard` 页面。
2. 通过后端/规则引擎创建一条**红色（严重级）待确认**预警（可 `POST /api/warnings` 或设备上报触发）。
3. 观察页面弹窗形态与声音。
4. 点击「确认收到」；再重复一次并点击「查看详情」。

**预期**
- 满屏红色遮罩 + 深红卡片闪烁，浏览器发出持续警报音（800Hz/1000Hz 交替方波，`frontend/src/composables/useAlarmSound.js:6-7`）。
- 必须点击才停止声音并关闭（`frontend/src/components/alarm/GlobalAlarmModal.vue:9-12,104-111`）。
- 若浏览器拦截自动播放，页面出现「点击恢复声音」提示，点击后可恢复（`GlobalAlarmModal.vue:223-236`）。
- 「查看详情」跳转 `/dashboard/warnings/{id}`；弹窗在任意 dashboard 页面都能出现（`MainLayout` 顶层挂载：`frontend/src/layouts/MainLayout.vue:26,325`）。

---

## SM-06 橙色预警弹窗 3 秒自动跳详情

**步骤**
1. 保持登录，触发一条**橙色（警示级）待确认**预警。
2. 观察弹窗与倒计时。
3. 不点击，等待 3 秒；重复一次并在 3 秒内点「关闭」。

**预期**
- 弹橙色普通弹窗、不响持续警报音（`GlobalAlarmModal.vue:11-12`）。
- 3 秒后自动跳转到该预警详情页（`AUTO_JUMP_DELAY_MS = 3000`，`GlobalAlarmModal.vue:30,195-221`）。
- 点「关闭」取消跳转；多条橙色预警同时到达时按队列最新一条处理。

---

## SM-07 在设备页停留时再触发红色预警

**步骤**
1. 登录后进入 `/dashboard/devices`（设备页），停留不切换。
2. 从另一终端/后端触发一条红色待确认预警。
3. 观察是否仍能收到全屏警报；点击确认后继续操作设备页功能。

**预期**
- 切到设备页后实时流不断：`useWarningAlarm` 在实时流 idle 时会补启一次并每 15s 巡检（`frontend/src/composables/useWarningAlarm.js:7-10,31-32,68-78`）。
- 全屏红色警报在设备页同样出现（全局弹窗不依赖具体页面）。
- 确认后回到设备页操作无副作用；首屏已存在的历史预警只登记 ID、不重复弹（`useWarningAlarm.js:11-12`）。

---

## SM-08 AI 巡检任务列表/详情不 403

**步骤**
1. 依次用 `admin`、`ai_engineer`、`attendant`、`maintenance` 登录，访问 `/dashboard/ai-tasks`。
2. 检查列表、详情、创建/编辑/删除按钮的可见性与可用性。
3. 打开 Network，确认 `GET /api/tasks` 返回 200。

**预期**
- `GET /api/tasks` 不再 403：`TaskController` 要求 `tasks:view`，权限表已按角色补齐 `tasks:view/create/edit/delete/export`（`backend/.../common/security/PermissionRegistry.java:16-24,33,62,78`）。
- 只读角色（maintenance / emergency_commander）看不到创建/删除按钮；`ai_engineer` 可增删改。
- 前端路由权限为 `ai:task:view`（`frontend/src/router/index.js:143`），按钮权限为 `ai:task:create`（`frontend/src/views/ai/AiTaskManagePage.vue:213`）。

**备注**：该 403 属并行修复项，若权限表被重新生成脚本覆盖，需同步前端 `auth.js` 的 `tasks:*` 定义（见 `PermissionRegistry.java:23-24` 注释）。

---

## SM-09 审计日志筛选

**步骤**
1. 以 `admin`（或 `system_admin`）进入 `/dashboard/audit-log`。
2. 分别按「操作类型（预警/灾害点/设备/登录…）」「时间范围（24h/7d/30d）」「关键字（操作人/详情/对象）」筛选。
3. 翻到第 2 页，再改筛选条件，确认分页不越界。

**预期**
- 筛选请求带 `page/size/range/action/keyword` 到后端并按服务端真实字段过滤（`frontend/src/views/system/AuditLogPage.vue:13,69-79`）；不再出现「总数有、列表空」。
- 分页越界时自动退回最后一页（`AuditLogPage.vue:80+`）。
- 无 `audit:view` 的角色直接访问被路由守卫拦到 403。

---

## SM-10 手机 768px 宽度布局

**步骤**
1. Chrome DevTools 设备模式，宽度设为 **768px**（以及 375px、640px 辅助抽查）。
2. 依次打开 `/dashboard`、`/dashboard/warnings`、`/dashboard/devices`、`/mobile`。
3. 检查侧边栏是否收起为抽屉、表格是否横向滚动不溢出、弹窗是否可关闭。

**预期**
- 全局 768px 断点生效：侧栏隐藏为抽屉（`frontend/src/styles.css:764-775,2217-2228`；布局断点 `frontend/src/layouts/MainLayout.vue:384`）。
- 无横向滚动条撑破视口；顶部导航可操作。
- 移动端指挥页 `/mobile` 路由可访问（`frontend/src/router/index.js:204-205`，组件 `frontend/src/views/mobile/MobileCommandPage.vue`）。

---

## SM-11 后端宕机提示与降级

**步骤**
1. 前端正常运行，登录后停掉后端进程（`.\start-dev.ps1 -Stop` 或仅结束 8080）。
2. 刷新页面 / 切换几个页面，观察提示与角标。
3. 重启后端，等待恢复后再次操作。

**预期**
- 前端运行模式识别为 `mock`，左下角角标显示「演示模式」（`frontend/src/App.vue:14-44`），不伪装成实时数据。
- 请求失败页面显示「无法连接到后端服务…」（`frontend/src/views/ServerErrorPage.vue:85,115`）；写操作提示需启动后端（`frontend/src/services/_architecture/core/runtime.js:7,173`）。
- 恢复后刷新可回到后端模式，无需重装。

---

## SM-12 WS 断线自动降级为轮询

**步骤**
1. 正常登录后打开 DevTools Network → WS，确认 `/ws` 已 101 建立。
2. 在 Network 面板对 WS 断开连接（或临时停后端再拉起、或在 nginx 层封锁 `/ws`）。
3. 观察列表/地图数据是否仍更新；等待约 15–30 秒。

**预期**
- 连接断开后状态切到 `polling`，页面数据由轮询继续刷新（`frontend/src/services/_architecture/core/realtimeService.js:470,481,629-631`）。
- 轮询间隔默认 15s（`VITE_REALTIME_POLL_INTERVAL`），预警 10s、人员 30s；重连成功后恢复实时推送（`realtimeService.js:687-727,770-810`）。
- 控制台有明确「仅依赖轮询」告警，不产生未捕获异常。

**排查**：若 WS 一直 101 失败 → 检查 nginx `/ws` 的 Upgrade/Connection 头（`infra/nginx/frontend.conf:62-70`）与后端 `/ws` permitAll（`SecurityConfig.java:55`）。

---

## SM-13 报表导出

**步骤**
1. `admin` 登录，在综合态势页点右上「导出」。
2. 到灾害点、巡检任务、设备、预警页面各执行一次导出（需对应 `*:export` 权限）。
3. 检查下载的 `.xlsx` 可打开且列与页面一致。

**预期**
- 浏览器下载文件名为「综合态势报表.xlsx / 灾害统计报表.xlsx / 巡检任务报表.xlsx / 设备台账报表.xlsx / 预警统计报表.xlsx」等（`frontend/src/services/dashboardApi.js:455-470`）。
- 走 `downloadFile` 二进制下载链路，非 JSON 解析（`frontend/src/services/_architecture/core/httpClient.js:100-140`）。
- 无 `*:export` 权限角色按钮不可见；后端未启动时提示「导出需要后端服务」而不是下载空文件。

---

## SM-14 登出

**步骤**
1. 任意角色登录，进入任意 dashboard 页面。
2. 点右上角用户菜单 →「退出登录」。
3. 尝试用浏览器后退键回到 dashboard；再刷新页面。

**预期**
- 清除 token/user 并跳回首页 `/`（`frontend/src/stores/auth.js:389,438-440`；菜单入口 `frontend/src/components/layout/UserProfileMenu.vue:13,68,160-167`）。
- 后退/刷新后因无 token 被路由守卫重定向到登录（`frontend/src/router/index.js:565-570`），不出现旧数据闪回。
- 实时同步停止，不再继续轮询鉴权接口（`realtimeService.js:830-843` 的注释与解绑逻辑）。

---

## SM-15 会签冒名防护（安全回归，本轮新增）

**目的**：验证 `PUT /api/events/{eventId}/sign/{signId}/confirm|reject` 不再信任请求体 `signerId`。

**步骤**
1. 用 `expert` A 登录，在 `/dashboard/confirmation-sign` 发起会签并选择另一名专家 B（需 `sign:create`）。
2. 用 B 的账号查询该会签 `signId`（`GET /api/events/{eventId}/sign`）。
3. 用 A 的 token 调用确认接口，请求体传 **B 的 signerId**：
   ```bash
   curl -X PUT "http://localhost:8080/api/events/{eventId}/sign/{signId}/confirm" \
     -H "Authorization: Bearer <A_TOKEN>" -H "Content-Type: application/json" \
     -d '{"signerId":"<B 的 signerId>","opinion":"冒名测试"}'
   ```
4. 用 B 的 token 正常确认自己的会签。
5. 校验数据库中该会签行的 `signer_status`（应为 B 的 CONFIRMED，而不是 A 冒名写入）。
6. 用 admin token 重复步骤 3（admin 当前未授予 `sign:proxy`）。

**预期**
- 步骤 3 返回 **HTTP 403**，错误信息「无权操作他人会签：signerId 与当前登录用户不匹配」（`backend/.../event/service/SignService.java:158-174`、`SignController.java:56-69,75-86`）。
- 步骤 4 返回 200；兼容前端「确认时传 username、记录里存 sys_user.id」的历史差异（`SignService.java:176-200`）。
- 步骤 6 返回 403：管理员代签需显式权限码 `sign:proxy`，当前 `PermissionRegistry` 未授予任何角色，代签默认禁用（`SignService.java:153-157,167-173`）。
- 审计/日志出现 `无权操作他人会签` 的 403 业务异常记录（`GlobalExceptionHandler.handleAppException`）。

**兼容性风险**：若生产环境存在「管理员代签」实际业务，需要权限表 owner 在 `PermissionRegistry` 中为对应角色增加 `sign:proxy` 后再验收；本次未修改该文件（跨工作流 scope）。

---

## 执行记录模板

| 用例 | 执行人 | 日期 | 环境（compose/dev） | 结果（通过/失败） | 证据（截图/日志/请求 ID） | 缺陷单 |
|---|---|---|---|---|---|---|
| SM-01 |  |  |  |  |  |  |
| … |  |  |  |  |  |  |
