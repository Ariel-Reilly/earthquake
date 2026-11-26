# R11 前端审查 · 冗余代码与底层逻辑冲突

- **审查对象**：`frontend/`（Vue 3 + Vite）。范围 `frontend/src`（184 个 js/vue/css 文件、109,277 行，其中 115 个 `.vue`）+ `frontend/scripts`（10 文件/1,820 行）+ 前端配置。
- **审查方式**：只读。逐文件读码 + 全仓引用检索（含 `import()` 动态引用、模板 kebab-case、`<component :is>`、测试引用）+ Node 静态分析脚本（AST 不可用，用正则 + 人工逐条复核）。
- **未执行**：`npm run build` / `vitest`（按任务要求，CI 已在本 commit 验证）；未修改任何被 Git 跟踪文件。唯一写入为本文件。
- **基线**：HEAD `b81311f`；已读 `.workbuddy/memory/MEMORY.md`、`docs/reviews/CODE_REVIEW_R10.md`、`CODE_REVIEW_R10_FIX.md`，历史 P0/已修项不重报。
- **结论**：**无新增 P0；7 项 P1；14 项 P2**。核心风险集中在「三处权限/菜单源已互相漂移」「前端从不调用 logout」「通知抽屉·审批中心前后端契约断裂」「预警活动集合被两个轮询器互相覆盖」。

---

## 概要

| 维度 | 结果 |
|---|---|
| 未使用组件 | **0 个**（115 个 `.vue` 全部有静态或动态引用；懒加载路由、`<component :is>` 已排除误报） |
| 未使用 service 方法 | `dashboardApi` 中 `xxx: async` 形式的方法 226 个，其中 **42 个 0 引用**；`ruleEngineService` 7 个、`weatherService` 5 项（2 方法 + 3 常量）（详见冗余表） |
| 重复实现 | 时间格式化 33 处 / 15 个本地 `fmt*`；等级常量表 ≥8 份；指标表 6 份；灾种表 2 份冗余；角色中文名 3 份；request/fetch 封装 6 套 |
| 死路由/死菜单 | `routePermissionMap` 6 个 key 无对应路由；`ROLE_MENU_MAP` 12 条非菜单/字面量死条目；`moduleMeta` 5 条已迁移路径（另 2 条为真实子路由） |
| 样式覆盖链 | `styles.css` 9,743 行 / 1,179 个选择器 / **130 个重复选择器**；文件内已有「iOS 化后置覆写层」，外部再叠加 `visual-refresh.css`；`tokens.css` 与 `styles.css` 深色块 **45 个同名 token 双源** |
| 权限三源 | 前端 `ROLE_PERMISSION_MAP` 126/58/42/33/27/54/37 条；后端 `PermissionRegistry` 少 1 条（`inspection:gps-check`）；`data.sql` 权限表缺 20 条、多 9 条且用 `rule:*` 而非 `rules:*`，且**已无任何 Java 消费方** |

---

## P0

**本轮未发现新增 P0**（无「当前配置下必然阻断主链」的问题）。以下两项目前为 P1，但若换了配置/入口会升级为 P0，已在正文注明触发条件：

- P1-6（自动探测模式全站锁 mock，徽标仍显示「真实数据」）——生产 Dockerfile 有 `VITE_USE_BACKEND=true` fail-fast 护栏，故现状不触发；
- P1-3（`/warnings` 全量 与 `/warnings/preview` 活动集互相覆盖）——演示时预警点位的 10s/15s 抖动在真机上可能被当成「数据不稳定」。

---

## P1

### P1-1　角色菜单可点、路由权限码却缺失 → 点击菜单即 403（10 条确定性冲突）

**位置**

- `frontend/src/data/dashboardData.js:29-334`（菜单，41 项生效 + 1 行注释掉的 social-media）
- `frontend/src/stores/auth.js:80-283`（`ROLE_MENU_MAP`）、`:285-685`（`ROLE_PERMISSION_MAP`）
- `frontend/src/router/index.js:122-178`（`routePermissionMap`）、`:586-594`（守卫判定）
- `frontend/src/components/layout/UserProfileMenu.vue:131/141/150`（头像菜单硬链 profile / account-security / help，无权限判断）

**证据**（脚本交叉 `dashboardMenu × ROLE_MENU_MAP × routePermissionMap × ROLE_PERMISSION_MAP`，原文输出）

```
ai-engineer:        菜单 /dashboard/rule-engine      (路由 rule-engine)     需要 settings:view，该角色无
ai-engineer:        菜单 /dashboard/account-security (路由 account-security) 需要 settings:view，该角色无
ai-engineer:        菜单 /dashboard/help             (路由 help-center)     需要 settings:view，该角色无
ai-engineer:        菜单 /dashboard/profile          (路由 profile)         需要 settings:view，该角色无
emergency-commander:菜单 /dashboard/account-security (路由 account-security) 需要 settings:view，该角色无
emergency-commander:菜单 /dashboard/help             (路由 help-center)     需要 settings:view，该角色无
emergency-commander:菜单 /dashboard/profile          (路由 profile)         需要 settings:view，该角色无
attendant:          菜单 /dashboard/route-planning   (路由 route-planning)  需要 resource:dispatch，该角色无
expert:             菜单 /dashboard/route-planning   (路由 route-planning)  需要 resource:dispatch，该角色无
maintenance:        菜单 /dashboard/route-planning   (路由 route-planning)  需要 resource:dispatch，该角色无
```

`routePermissionMap` 现状：`:158 'rule-engine': 'settings:view'`、`:162 'route-planning': 'resource:dispatch'`、`:157 'account-security'` / `:164 profile` / `:173 'help-center'` 全部 `settings:view`。而 `ROLE_PERMISSION_MAP['ai-engineer']:646-684` 与 `['emergency-commander']:590-645` 均无 `settings:view`；`attendant/expert/maintenance` 均无 `resource:dispatch`（`resource:view` 不是同一码）。

**说明**：三张表各自维护、无单源、也无 CI 校验，所以「菜单可见」与「路由放行」可以长期不一致。`rule-engine`/`route-planning` 是侧栏真实可见项，点了直接跳 `/dashboard/error/403?reason=no-permission`；profile/account-security/help 虽 `hidden:true`，但头像菜单硬链，同样 403。

**影响**：AI 工程师与应急指挥两个角色在演示中「点菜单就被拒」；现场运维/值班员/专家三个角色「路径规划」入口不可用。属于对外可见的权限体验缺陷，且会让人误以为后端鉴权坏了。

**修复建议**

1. `rule-engine` → 改为 `rules:view`（该码已存在且 ai-engineer/expert 都有）；`profile`/`help-center`/`account-security` → `profile:view`（7 角色全有）或单独补 `settings:view`；`route-planning` → `route:view`（attendant/expert/maintenance 均有，且语义正确）。
2. 把「菜单路径 → 路由 name → 权限码」压成一张表（或让路由 `meta.permission` 自带码），`ROLE_MENU_MAP` 只保留路径集合，消除三源。
3. 加一条 CI 校验（现有 `check-endpoint-parity.mjs` 同类脚本可扩展）：对每个角色 × 每个菜单项断言 `routePermissionMap[name] ∈ ROLE_PERMISSION_MAP[role]`。

---

### P1-2　通知抽屉「审批中心」前后端契约三处不一致：列表恒空 + 通过/驳回必失败

**位置**

- `frontend/src/components/notification/tabs/ApprovalCenterTab.vue:30`（`approvals = ref({items: []})`）、`:42-59`（状态过滤/未读数按 `'pending'/'approved'/'rejected'` 比较）、`:74-87`（`listApprovals({status})`）、`:92`（`decideApproval?.(id, 'approved')`）、`:106`（`'rejected'`）
- `frontend/src/services/dashboardApi.js:1214-1222`（`decideApproval(id, payload) → JSON.stringify(payload)`）
- `backend/.../approval/ApprovalCenterController.java:108-157`（list：status 与 `warning.status` 中文比较；行内 `status` 直接回填中文状态）、`:169-189`（decide：`decision` 只认 `approve`/`reject`，`@RequestBody Map`）

**证据**

1. **筛选域不一致**：前端默认 `filter.status='pending'` 并作为 `?status=pending` 下发；后端 `status.equals(w.getStatus())`，而 `warning_event.status` 取值是中文（`待确认/已确认/已发布/已关闭/已解除/已忽略`，见 `WarningController.stats()` 注释 `:88-92`）。→ 默认「待审」页签拿到 0 行；即使拿到行，`:45` 再按 `a.status === 'pending'` 客户端二次过滤，中文状态同样不匹配。
2. **决策动词不一致**：卡片传的是字符串 `'approved'`/`'rejected'`，`dashboardApi.decideApproval` 直接 `JSON.stringify('approved')` → 请求体是 JSON 标量 `"approved"`；后端形参是 `@RequestBody java.util.Map<String,Object>` → Jackson 反序列化失败（400）。即便改成对象，后端只认 `decision=approve|reject`（`ApprovalCenterController.java:179`），传 `approved` 也会走 reject 分支把状态写成「已忽略」。
3. **展示映射不一致**：`:61-72` 的 `statusLabel/statusColor` 键是 `pending/approved/rejected`，后端行 status 是中文，徽标永远走 fallback 显示原文。

**影响**：通知抽屉里的审批中心「待审」永远空白；若切到「全部」再点通过/驳回，请求 400，审批决策 100% 不可用。R10 只核了后端 `decide` 存在，没核前端调用形态。

**修复建议**：前端统一传 `{decision:'approve'|'reject', comment}`（`ApprovalCenterTab.vue:92`、`:106` 改为对象字面量）；列表状态筛选改由后端提供枚举映射（`PENDING/APPROVED/REJECTED` → 中文），或在 `dashboardApi.listApprovals` 内做一次双向映射；`statusLabel` 改吃后端枚举。

---

### P1-3　预警被两个轮询器共写同一 state：全量列表（含关闭）与「仅活动」列表互相覆盖

**位置**

- `frontend/src/services/_architecture/core/realtimeService.js:520-548`（`pollSnapshot`：`getWarnings({page:1,size:200})` → `replaceFromSnapshot` → emit `type:'poll'`）
- 同文件 `:554-563`（`pollWarningsPreview`：`getWarningsPreview()` → 同一个 `state.warnings = replaceFromSnapshot(...)` → emit `type:'warning-poll'`）
- 同文件 `:660-733`（两个定时器同时启动：polling `15s`/兜底 `60s`，warning `10s`/兜底 `40s`）
- `frontend/src/utils/alarmWarning.js:204-225`（`extractWarnings` 对 `poll` 与 `warning-poll` 一视同仁）
- 消费方：`frontend/src/views/OverviewPage.vue:1074-1088`（`payload.warnings` 直接赋值）、`frontend/src/views/screen/CommandScreenPage.vue:306-307`

**证据**（后端口径）

- `backend/.../warning/WarningController.java:69-80`：`GET /api/warnings` 的 `status` 为 `required=false`，**不传即返回全部状态**（含已关闭/已忽略/已解除）。
- `backend/.../warning/service/WarningService.java:102-110`：`preview()` 明确排除 `已关闭/已忽略/已解除`，**只返回活动预警**。

**说明**：`replaceFromSnapshot`（`realtimeService.js:297-303`）的语义是「incoming 为权威，不在其中的项视为已删除」，且只保留 `version>0` 的 WS 增量。轮询来源的数据 `version` 恒为 0（后端 DTO 无 `version` 字段），所以 10s 的 `preview` 会把 15s 全量里的已关闭预警整批删掉，15s 的全量再把它们加回来——同一个 `state.warnings` 在两个集合之间来回切换，两种事件类型都携带 `warnings` 字段。

**影响**：任何直接消费 `payload.warnings` 的页面（大屏预警播报、首页预警面板、Pinia `lastEvent` 订阅方）会出现「预警条数/点位在 10s 与 15s 之间跳动、已关闭预警反复进出」。若演示时正在播放大屏，观感就是数据抖动。

**修复建议**：二选一——① 只保留一个预警轮询器（推荐 `preview` 负责活动集，`pollSnapshot` 不再带 warnings）；② 拆成两个 state（`activeWarnings` / `allWarnings`）并让事件字段名不同，禁止同一字段被两个口径覆盖。若保留双通道，`preview` 必须改用 `mergeSnapshot`（只 upsert，不删项）。

---

### P1-4　前端从不调用 `/auth/logout`：后端 JWT 吊销（JwtBlacklist）链路在 UI 上不可达

**位置**

- `frontend/src/services/dashboardApi.js:160-165`（`logoutRequest()` 唯一实现：`POST /auth/logout`）
- `frontend/src/components/layout/UserProfileMenu.vue:66-70`（`handleLogout()`：只 `clearAuthSession()` + `router.push('/')`）
- `frontend/src/views/settings/ProfilePage.vue:57-67`（`logout()`：同样只清本地会话）

**证据**：全仓检索 `logoutRequest` = **1 处命中（定义处）**，无任何调用点；`/auth/logout` 仅由后端暴露。

**影响**：用户点「退出登录」/头像菜单退出后，本地 token 虽被清除，但服务端从未收到吊销请求，该 token 在 `JWT_EXPIRE`（默认 2h）内仍可被复用（本地已泄露/浏览器备份/其它标签页缓存场景）。R10 用 `curl` 实测过「登出→再访问 401」的**后端能力**，但 UI 从未触发它，对外不能说「登出即吊销」。

**修复建议**：两个退出入口改为 `await logoutRequest().catch(() => {})` 后再 `clearAuthSession()`；或把「登出」下沉到 auth store 的一个 `logout()` action 里，避免再次分叉。

---

### P1-5　「待确认」队列的灾种/等级筛选整组失效（`ref.value` 误用 + 中英文域错位 + mock 与真实库域不一致）

**位置**

- `frontend/src/views/hazard/HazardsListPage.vue:125`（`if (confirmationState.filterType.value)`）、`:126`（`i.type === confirmationState.filterType`）、`:112`（`operator` admin 兜底）、`:697-704`（下拉）、`:699`（`<option>滑坡</option>…` 5 项中文）
- `frontend/src/views/hazard/ConfirmationWorkbenchPage.vue:86-90`、`:391-398`（中文 5 项 + `高风险/关注/正常`）
- `frontend/src/services/mock/workflow.js:13-46`（mock 队列 `type:'滑坡'`、`level:'关注'`）

**证据（数据域）**

- `backend/src/main/resources/data.sql:672-682`：`unified_event.hazard_type` 存 **英文码**（`landslide/collapse/flood/debris_flow`）；`risk_level` 仅出现 `'高风险'`（7 次）与 `'中风险'`（3 次），**没有 `关注/正常`**。
- `ConfirmationWorkbenchPage.vue:86-90` 用 `dashboardApi.getConfirmationQueue` → `dashboardApi.js:688` 经 `normalizeQueueItem`（`dashboardApi.js:107-124`）得到 `type = raw.type ?? raw.hazardType`（英文码）、`level = raw.level ?? raw.riskLevel`（中文风险级）。
- `HazardsListPage.vue:125`：`confirmationState` 是 `reactive({filterType:''})`（`:82-101`），字符串属性上取 `.value` 恒为 `undefined` → **类型筛选分支永远不执行**；即使修好，下拉值是「滑坡」，而 `i.type` 是 `landslide`，仍然 0 命中。
- 等级下拉 `关注/正常` 在真实库不存在（只有 高风险/中风险），选中即空白列表。

**影响**：待确认队列的两个筛选控件是「摆设」：类型筛选完全无效；等级筛选里 2/3 选项必然筛出空列表；且 mock 态（中文域）与真实态（英文域）表现相反，容易在评审时被判「页面有 bug」或「接口没数据」。

**修复建议**：`HazardsListPage.vue:125` 去掉 `.value`；两个页面的 `<option>` 改为 `v-for="t in HAZARD_TYPES" :value="t.value"`（复用 `utils/hazardTypes.js`）；等级下拉改用真实风险级集合（建议后端下发字典或以常量表 `高风险/中风险/低风险` 为准）；`mock/workflow.js` 的 `type` 改为英文码与真实接口对齐（同时消除 mock/真实域不一致）。

---

### P1-6　运行模式是「导入期静态快照」：自动探测模式下整站 service 层锁死 mock，但界面徽标显示「真实数据」

**位置**

- `frontend/src/services/dashboardApi.js:27`（`export const USE_BACKEND = getUseBackend()`）
- `frontend/src/services/_architecture/core/runtime.js:9-16`（文档明确：「不要直接 import { USE_BACKEND } 来判断分支」）、`:26-33`（`EXPLICIT_FLAG` 未设置时初始 `USE_BACKEND=false`，走 `'probing'`）、`:132-149`（`initBackendMode()` 探测成功后 `setRuntime('backend', true)`）
- `frontend/src/main.js:5`（静态 `import { initBackendMode } from './services/dashboardApi'` → 模块在 `bootstrap()` 之前求值）、`:43-44`（`await initBackendMode()` 在 mount 之前）
- `frontend/src/App.vue:14-30`（徽标读 `getRuntimeMode()`，动态值）

**证据**

1. Vite 的静态 import 在 `bootstrap()` 执行前完成模块求值；`dashboardApi.USE_BACKEND` 此时被固定为 `EXPLICIT_FLAG === 'true'`（未设置 env 时为 `false`）。
2. `initBackendMode()` 随后确实把运行时 `USE_BACKEND` 切成 `true`，但 `dashboardApi` 内部 **144 处** `if (USE_BACKEND)` / `if (!USE_BACKEND)` 分支比较的仍是那个冻结的 `const`（全文件 149 行引用 `USE_BACKEND`）；`fallback()`（`:133-138`）同理 → 所有方法走 mock 分支，返回 `mock.seed` 假数据。
3. 同一时刻 `getRuntimeMode()` 返回 `'backend'`，`App.vue` 左下角徽标渲染「真实数据」→ 「真实数据」徽标 + 假数据并存。
4. `frontend/.env.local` 的注释自证这是踩过的坑：「显式锁定后端模式：避免 initBackendMode() 探测时序竞态导致前端锁死在 mock。」——即历史上出现过，只用环境变量绕开、未修代码。
5. 设计中的动态复检根本没接线：`recheckBackendMode()` / `subscribeRuntime()` 全仓 **0 调用**（`runtime.js:71/155` 仅定义）——`runtime.js:12` 承诺的「路由切换时再探测」不存在。

**影响**：`.env.local` 被 `.gitignore` 忽略（`/.gitignore` Environment 段），全新克隆 `npm run dev`、或本地 `npm run build && vite preview`、或任何未注入 `VITE_USE_BACKEND` 的构建，都会进入「界面显示真实数据、实际全是 mock」的状态。生产 Docker 有 `frontend/Dockerfile:31-35` 的 fail-fast（`VITE_USE_BACKEND != true` 直接构建失败）兜底，compose/e2e 也都注入 `true`，所以**现状不触发**；但这是唯一的挡板。

**修复建议**：`dashboardApi.js` 内全部判断改为调用 `getUseBackend()`（或模块内 `const useBackend = () => getUseBackend()`），并订阅 `subscribeRuntime` 迁移已加载页面的数据源；`App.vue` 徽标改为同时反映 `getUseBackend()`，避免「真实数据」标签与数据源不一致；补一条单测：模拟 `initBackendMode()` 成功后将 `getUseBackend()` 置 true，断言 `dashboardApi.getHazards` 走 `request`。

---

### P1-7　权限三处源已漂移：生成器仍会丢连字符权限码；`data.sql` 权限表成死数据

**位置**

- `scripts/fix/sync-permission-registry.mjs:53`：`[...b.join(" ").matchAll(/'([a-z:]+)'/g)]` —— **字符类不含 `-`**，连字符权限码被静默丢弃
- `backend/.../common/security/PermissionRegistry.java:20-131`（生成产物）
- `frontend/src/stores/auth.js:18-54/80-283/285-685`
- `backend/src/main/resources/data.sql:102-231`（`sys_permission`）、`:250-254`（admin blanket）、`:257-486`（各角色授权）

**证据（逐角色条数，脚本比对原文）**

| 角色 | 前端 ROLE_PERMISSION_MAP | 后端 PermissionRegistry | data.sql sys_role_permission |
|---|---|---|---|
| admin | 126 | 125 | 115 |
| attendant | 58 | 57 | 48 |
| expert | 42 | 42 | 37 |
| maintenance | 33 | 32 | 29 |
| system-admin | 27 | 27 | 28 |
| emergency-commander | 54 | 54 | 52 |
| ai-engineer | 37 | 37 | 32 |

- 后端比前端**只少一条**：`inspection:gps-check`（恰好是唯一含 `-` 的权限码，被 `:53` 正则丢弃）。也就是说**现在再跑一次生成器仍会丢**，MEMORY §三 记录的坑没有真正闭环。
- `data.sql` 与前端并集：**缺 20 条**（`ai:result:confirm`、`ai:forecast:view`、`inspection:view/submit/gps-check`、`personnel:view/report`、`events:view/transition/reopen`、`users:approve`、`rules:*` 4 条、`tasks:*` 5 条），**多 9 条**（`device_map:view`、`history:view/export`、`warnings:publish`、`rule:view/create/edit/delete`、`monitor:alert`）。
- `data.sql:195-198` 用的是 **`rule:*`（单数）**，而前端 `auth.js` 与后端 `@PreAuthorize` 用的是 **`rules:*`（复数）**（后端 21 处 `rules:view/rules:edit`）。
- `sys_permission` / `sys_role_permission` 在全仓**没有任何 Java 消费方**：`grep -r "SysRolePermission|sys_role_permission" backend/src` 仅命中 `data.sql`/`schema.sql`；`RoleController` 的权限矩阵（`RoleController.java:89-97`）读的是 `PermissionRegistry`，前端 `UserManagementPage.vue:131` 读 `/api/roles/permission-matrix`。→ 这两张表是纯种子死数据，但 `data.sql:100` 注释写「与前端 routePermissionMap 完全对应」，`scripts/migrations/V20261107_weather_refresh_permission.sql` 还在往里写。

**影响**：任何以 `data.sql` 为依据的评审/运维判断都会错（尤其 `rule:*` vs `rules:*`、缺 `events:*`/`inspection:*`）；`inspection:gps-check` 目前是「前端三张表有、后端无、UI 不判、后端端点用 inspection:submit」的幽灵码，一旦有人按码加 `@PreAuthorize` 就会全员 403；生成器缺陷会继续制造同类漂移。

**修复建议**：① 生成器正则改 `/'([a-z][a-z0-9:_-]*)'/g` 并加「生成前后条数一致」断言，重生成 `PermissionRegistry`；② 决策 `sys_permission` 两张表：要么删除并下掉迁移脚本，要么让后端真正读它（当前无消费者）；③ `rule:*` 统一为 `rules:*`；④ 把「三处一致」做成 CI 校验脚本（复用现有 audit 脚本风格）。

---

## P2

### P2-1　`styles.css` 巨型覆盖链 + 双层 token 源（9,743 行 / 130 个重复选择器）

**位置**：`frontend/src/styles.css:26`（`:root`）、`:62-93`（深色令牌覆盖 45 个同名变量）、`:802/:2068/:8016`（`.page-content` 三处全局定义）、`:8001-8030`（文件内「SKELETON IOS-IFICATION … 本层不改动上方原有规则,只在文件末尾以同优先级后置覆写」）、`:8460-8495`（响应式再覆盖）；`frontend/src/styles/visual-refresh.css:1-172`（第三层覆写）；`frontend/design-system/disasterguard/tokens.css`（490 行/227 个变量）；`frontend/src/main.js:6-8`（加载顺序 `styles.css → tokens.css → visual-refresh.css`）。

**证据**

- 静态统计：`styles.css` 1,179 个顶层选择器，其中 **130 个选择器名重复定义**；`.page-content` 出现 10 次，其中 **3 处是全局（非媒体查询）且互相冲突**：`:802 padding: var(--sp-8) var(--sp-10) var(--sp-12)` / `:2068 position:relative` / `:8016 padding:32px 36px 56px`（硬编码 px，胜出后 token 版本变成死代码）。
- `tokens.css` 与 `styles.css` 深色块有 **45 个同名变量**（`--primary/--bg-page/--text-main/--c-brand-tint/--glass-*` 等），值完全不同（浅色/深色两套）。设计上靠 `:root[data-theme='dark']` 更高特异性取胜，但新增 token 时必须同时改两个文件，漏一个就是「深色下某 token 仍取浅色值」。
- 三层覆写（原规则 → 文件内 iOS 化层 → `visual-refresh.css`）叠加后，页面级样式调试必须同时看 4 个位置。

**影响**：可维护性/回归风险；改 `.page-content`、`.panel`、`.stats-grid` 等高频类时容易改到「被后面覆盖掉」的那一份，实际不生效。

**建议**：把 `visual-refresh.css` 与文件内 iOS 化层合并为一层（`@layer` 显式分层）；深色令牌只保留 `styles.css` 一处并以 CSS 变量别名方式引用 `tokens.css`；单开任务把 `.page-content` 收敛为一份（含媒体查询内的差异）。

### P2-2　预警等级常量表 ≥8 份，且「红/橙同色」「红/黄被当蓝」两处判定错误

**位置**

- `frontend/src/utils/alarmWarning.js:42-47`（`AI_RISK_BANDS`：80/60/35 阈值 + 中文等级 + label）
- `frontend/src/services/_architecture/domain/multiModalService.js:152-157`（`RISK_LEVELS`：label/color/class 一套，颜色 `#e06060/#cbaa76/#d9c040/#9dc0aa`）
- `frontend/src/views/warning/WarningsPage.vue:93-112`（`levelTone` / `toneOf`，第三套映射）
- `frontend/src/views/settings/SettingsPage.vue:243-245`（`LEVEL_LABELS`）
- `frontend/src/components/profile/NotificationPreference.vue:21-24`（第四套 label+color）
- `frontend/src/components/warning/WarningAnalysisDetail.vue:57` 起（分数→中文等级阈值表）
- `frontend/src/views/warning/WarningDetailPage.vue:1156-1158`（硬编码 `<option>红色预警/橙色预警/黄色预警</option>`，**缺蓝色预警**，无法把等级改为蓝）
- `frontend/src/services/_architecture/core/realtimeService.js:160-161`（**逻辑错误**：`cssClass: item.cssClass ?? item.class ?? (String(item.level).includes('橙') ? 'orange' : 'blue')` → 红/黄/蓝全部落 `blue`）
- `frontend/src/services/_architecture/core/mapService.js:1396-1419`（**红与橙共用 `#cd927e`**，四级预警在地图上只有 3 色）

**证据**：`WarningService.cssClass()`（`backend/.../warning/service/WarningService.java:611-617`）是正确四色 red/orange/yellow/blue；前端地图样式把 `红||橙` 合并，WS 兜底把非橙一律判蓝。

**影响**：同一等级在不同组件里颜色/名称不同；WS 推送缺 `cssClass` 时红色预警被画成蓝色；地图上红/橙不可区分，与「蓝黄橙红四级」「灯带四色」演示口径冲突。

**建议**：新建 `frontend/src/utils/warningLevel.js`（`LEVELS = [{code:'red',label:'红色预警',tone,color,aiMin:80}, ...]`），`alarmWarning/multiModalService/WarningsPage/Settings/NotificationPreference/WarningAnalysisDetail/mapService` 全部改为引用；`realtimeService` 兜底改用 `level→code` 映射；`WarningDetailPage` 下拉改用常量表（补蓝色）。

### P2-3　时间格式化 33 处 + 15 个本地 `fmt*`，无统一工具（`warningTime.js` 只覆盖预警）

**位置/统计**（脚本正则统计，`src` 内）：`toLocaleString('zh-CN', {hour12:false})` **26 处**（分布 `SignTimeline.vue`、`EventDetailDrawer.vue`、`WarningAnalysisDetail.vue`、`WarningCreateModal.vue`、`WarningDeliveryTab.vue`、`WeatherOverlayLegend.vue`、`dashboardApi.js:408`、`AdminApplicationsPage.vue:120`、`ModelManagePage.vue:308`、`DeviceDetailPage.vue:196/259`、`DevicesPage.vue:246`、`ConfirmationSignPage.vue:272`、`FieldInspectionPage.vue:423/442/659`、`HazardDetailPage.vue:677`、`TaskDetailPage.vue:103`、`RealTimeMapPage.vue:541/543`、`WeatherPage.vue:213/247`、`RegisterPage.vue:372`、`AccountSecurityPage.vue:343`、`AuditLogPage.vue:123`）；`toLocaleString('zh-CN')` 总计 **33 处/27 文件**；`toLocaleTimeString` 11 处；`toLocaleDateString` 3 处；自定义 `padStart(2,'0')` 时间拼接 14 处；本地 `fmt/formatTime/formatDateTime/timeText` 定义 **15 个**。另有 `utils/warningTime.js`（预警）与 `weatherService.js:158-162`（气象）各自一套。

**说明**：格式化粒度也不一致（`slice(0,16)`、`{month:'short',day:'numeric'}`、`HH:mm`、`hour12` 有的传有的不传），同一时间在不同页面显示格式不同；时区处理只有 `warningTime.js` 显式做了本地时区决策，其余依赖浏览器默认。

**影响**：改时间显示口径要改 30+ 处；不同页面同一条记录时间格式不一致（演示时会被追问）。

**建议**：新增 `utils/datetime.js`（`formatDateTime/formatDate/formatTime/fromNow`，内部统一 `Intl` + 本地时区），`warningTime.js` 保留预警专用语义并委托它；逐步替换。

### P2-4　已撤下模块的 service/mock 残留：42 个 0 引用 `dashboardApi` 方法 + 38 个 0 引用 mock 导出；`knip` 装了但从未运行

**位置/证据**

- `frontend/src/services/dashboardApi.js`（2,469 行、226 个方法）：**42 个方法全仓 0 引用**——`createHazard/deleteHazard`、`exportOverviewReport/exportHazardReport/exportTaskReport/exportDeviceReport/exportEmergencyReport`、`uploadAttachment/getAttachments/refreshAttachmentUrl`、`getInspectionByHazard`、`getIntegrityReport`、`getSentinelCoverage`、`getNoticeTemplate/createNoticeTemplate/updateNoticeTemplate`、`getPlanDetail/upsertPlan/createPlan/updatePlan/deletePlan/launchPlan/simulatePlanLaunch`、`createBackup/verifyBackup/restoreBackup/deleteBackup/upsertBackupStrategy/deleteBackupStrategy/runBackupStrategy`、`createReportTemplate/deleteReportTemplate/runReportTemplate/updateReportSubscription/rerunReportHistory/cancelReportHistory`、`exportRouteGeoJson`、`previewWarningResolve/getWarningResolveScope`、`getUnifiedEventAvailableTriggers`、`getFusionRisk`、`getAiWelcomePrompts`。
- 对应页面已撤销：`frontend/src/views` 下无 Plan/Backup/Report 页面；路由 `/dashboard/plans|reports|backup-recovery` 全部是 redirect（`router/index.js:348-350`）。
- mock 死导出 **38 个**：`mock/operations.js` 14（`otaFirmwareListMock/otaTaskListMock/.../approvalListMock`）、`mock/seed.js` 15（`findHazardById/tasksOfHazard/.../SEED_ATTACHMENTS`）、`mock/empty.js` 5、`mock/runtime.js` 3、`mock/ai.js` 1（`aiDeviceHealthMock`）。
- `frontend/knip.json`（12 行）与 `package.json:46 devDependencies.knip` 存在，但 **package.json scripts 无 knip、CI 无 knip**（仓库内 grep `knip` 仅命中这两处）→ 装了未接线，正好是本类死代码的检测工具却没生效。

**建议**：`verifyBackup` 等刻意 501 的契约可保留注释说明，其余按模块批量删除；`mock/*` 同步删；`knip` 加 `"lint:dead": "knip"` 并在 CI 跑（排除 `src/services/mock/**` 已在配置里）。

### P2-5　6 套重复的 request/fetch 封装；死 `sentinelService` 仍直连 ai-service 8001（违背 A6-5 网关决策）

**位置**

- `frontend/src/services/_architecture/core/httpClient.js:45-90`（标准封装：JWT + 401/403 + ApiResponse 解包）
- `frontend/src/services/_architecture/domain/aiApi.js:15-41`（第二套 `fetchApi/request`，自行注入 token + 401）
- `frontend/src/services/satelliteCompareApi.js:23-43`（第三套，按 `SENTINEL_BASE` 拼）
- `frontend/src/services/_architecture/domain/driftMonitorService.js:26-63`（第四套，先直连 8001 再回落后端代理）
- `frontend/src/services/_architecture/domain/multiModalService.js:17-39`（第五套，同 drift 模式）
- `frontend/src/services/_architecture/domain/deviceMapService.js:217+`（第六套 `fetchWithAuth`，直接读 `localStorage['disasterguard_token']`）
- **死代码**：`frontend/src/services/_architecture/core/sentinelService.js`（285 行，`VITE_AI_SERVICE_BASE || 'http://localhost:8001/api/ai'` 直连）+ `frontend/src/services/sentinelService.js`（2 行 re-export shim）。`composeSentinelScene/pollSentinelJob` 全仓仅注释提及（`mapService.js:532`），无调用。

**影响**：401/403 处理、超时、错误文案四套不一致（`aiApi` 只在 401 跳登录、403 不区分；`driftMonitorService` 失败时先试 8001 再回落，生产浏览器根本连不到 8001 只会多一次失败）；死 `sentinelService` 若被误用会绕过 Java 网关与 `satellite:view`，与 MEMORY §八 A6-5「禁止前端直连 8001」直接冲突。

**建议**：统一走 `httpClient.request`（改造成支持 basePath 与自定义 401 行为）；删除死 `sentinelService` + shim；`driftMonitorService`/`multiModalService` 去掉「先直连 8001」分支，直接走后端代理（其注释已说明 501 为已知缺口，用显式错误提示即可）。

### P2-6　角色判断逻辑重复 + 17 处 fail-open admin 兜底 + 9 处本地 `has()`

**位置/证据**

- `getAuthUser() ?? { name:'李晨曦'/'system', role:'admin' }` **17 处**：`HazardsListPage.vue:112`、`ConfirmationWorkbenchPage.vue:33`、`ConfirmationSignPage.vue:38`、`RoutePlanningPage.vue:26`、`ResourceDispatchPage.vue:35`、`NotificationChannelsPage.vue:57`、`AuditLogPage.vue:10`、`NoticeTemplatePage.vue:40`、`UserManagementPage.vue:14`、`HelpCenterPage.vue:36`、`OtaManagePage.vue:38`、`DataSourcesPage.vue:33`、`AiTaskManagePage.vue:37`、`AccountSecurityPage.vue:40`、`ProfilePage.vue:40`、`SettingsPage.vue:36`、`WarningCreateModal.vue:30`。
- 本地 `const has = (perm) => hasPermission(operator.role, perm)` **9 处**（同上多数文件）+ `components/PermissionButton.vue` 这一「正规封装」只被 2 处使用（`HazardsListPage.vue:517-518`）；`WarningCreateModal.vue:197/353`、`NotificationChannelsPage.vue:58` 等仍各自手写。
- `HazardsListPage.vue:517` 同时用 `v-if="userRole !== 'maintenance'"` 与 `permission="hazards:create"` 双重判定（maintenance 本就没有该权限，角色判断是多余的真值来源）。
- `EventDetailDrawer.vue:119` 直接 `JSON.parse(localStorage.getItem("disasterguard_user"))` 读取身份，绕过 auth store。
- 路由守卫只看 `requiresAuth` 与路径，不校验「菜单项是否隐藏」。

**影响**：权限判断有 3 种写法（store getter / 本地 has / PermissionButton）；`?? {role:'admin'}` 在用户态丢失（localStorage 被清一半、解析失败）时**默认放开按钮**（fail-open），与 `auth.js:757-768` 刻意「绝不回落 admin」的修复方向相反；`EventDetailDrawer` 直读 localStorage 会在 mock/真实会话切换时读到旧用户。

**建议**：统一用 `useAuthStore()`（`role/permissions/hasPermission`）+ `PermissionButton`；`operator` 缺省改为 `null` 并禁用操作而不是造一个 admin；删除 17 处兜底与 9 个 `has()`；`EventDetailDrawer` 改 `getAuthUser()`。

### P2-7　`PermissionButton` 的「拒绝时给原因」分支不可达

**位置**：`frontend/src/components/PermissionButton.vue:23-30`（`handleClick` 里 `if (!allowed) { preventDefault(); emit('click',{allowed:false,reason}) }`）、`:36`（`:disabled="!allowed"`）；调用方 `HazardsListPage.vue:517-518` 依赖 `{allowed,reason}` 弹提示。

**证据**：`disabled` 的原生行为是**不派发 click 事件**，所以无权限时 `handleClick` 永不执行，`allowed:false` 分支是死代码；调用方写的「无权限时提示原因」永远不会出现，用户只看到灰色按钮无说明（`title` 仅鼠标悬停可见）。

**影响**：权限受限时缺少可解释反馈；组件内 `pointer-events: auto` 与 `disabled` 的组合也自相矛盾。

**建议**：去掉 `:disabled`，改用 `aria-disabled` + `handleClick` 阻止动作（保留事件），或把 `disabledReason` 提升为显式提示条。

### P2-8　主题「跟随系统」空转：清掉钩子后没有任何 `prefers-color-scheme` 实现

**位置**：`frontend/src/composables/useTheme.js:45-64`（`applyTheme('system')` = 移除 `theme-dark/theme-light` 与 `data-theme`，靠 `prefers-color-scheme`）、`:119-121`（`isDark()` 在 system 下按媒体查询返回 true）；`frontend/src/styles.css:56-57`（注释明写「不挂 prefers-color-scheme」）；`frontend/src/views/settings/SettingsPage.vue:227` 起（`themeOptions` 含「跟随系统」）；`frontend/src/components/profile/ThemeLanguage.vue:40`。

**证据**：全仓 CSS `grep prefers-color-scheme` = **1 处，且是注释**（`styles.css:56`）。因此选择「跟随系统」后：系统深色时页面仍是浅色，但 `isDark()` 返回 true（任何依赖它的样式/文案会与视觉不一致）。

**建议**：要么给 `:root[data-theme]` 之外补 `@media (prefers-color-scheme: dark)`（与 `styles.css:62-93` 的深色块复用同一组变量），要么把「跟随系统」从设置项移除或按 `isDark()` 主动写入 `data-theme`。

### P2-9　命令大屏与移动指挥页的等级 class/键域与真实中文等级不匹配（预警等级显示失效）

**位置**

- `frontend/src/views/screen/CommandScreenPage.vue:524`（`lvl-${h.level}`）、`:551/:635/:656`（`lvl-${w.level ?? w.riskLevel}`）、CSS `:1175-1250`（选择器只有 `.lvl-red/.lvl-红色/.lvl-关注` 等）、`:209-216`（`levelColor` 用「红/橙/黄」子串判断，正确）；数据集：`WarningDto.level` = `红色预警`、`HazardDto.level` = `高风险/中风险`。
- `frontend/src/views/mobile/MobileCommandPage.vue:79`（`LEVEL_LABEL = {red,orange,yellow,blue}`）、`:82-88`（`WARNING_STATUS_LABEL = {published,draft,resolved,...}`）、`:102-113`（`levelColor`/`statusBadge` 同键域）、`:198-211`（`level: w.level`、`levelLabel: LEVEL_LABEL[w.level] ?? '未知'`）。

**证据**：静态可确定——`class="lvl-红色预警"` 不匹配任何选择器（`.lvl-红色` 是完整类名，不做前缀匹配）；`lvl-高风险` 同理（CSS 只定义了 `.dot-关注/.lvl-关注`）。移动页 `LEVEL_LABEL['红色预警']` = undefined → 徽标文案「未知」、`levelColor('红色预警')` 走默认灰 `#718096`。

**影响**：大屏预警列表/卡片等级徽标无背景色与等级色；移动指挥页所有真实预警等级显示「未知」+ 灰色左边框（`/mobile` 是公开路由，评审可直接访问）。

**建议**：两处都改用 P2-2 的统一等级常量，按 `level` 文本归一化到 `red/orange/yellow/blue` 后再拼 class；或后端 DTO 直接下发 `cssClass`（已有）并让这两页只用 `cssClass`。

### P2-10　分页归一化仍有双实现与残余猜测（与 `api/client` 归一化冲突）

**位置/证据**

- `frontend/src/services/dashboardApi.js:48-50`：`normalizePaged` 只是 `normalizePage` 的 4 行别名（纯转发，无附加语义）。
- 同文件 **6 处** `Array.isArray(data) ? data : ...`（`:194/202/301/350/416` 与 `:2277`）——`normalizePage` 已处理裸数组，这些分支是重复判断。
- 同文件手写 `{items}` 拼装：`getMessages:1077-1092`、`getAuditLogs:1020-1053`、`listUnifiedEvents:2274+`（三处各自 `items ?? records`），而其余 100+ 方法用 `normalizePaged`。
- 页面级链式猜测仍在：`MobileCommandPage.vue:197`（`records ?? list ?? items`）、`:216`、`RoutePlanningPage.vue:101`（`records ?? items`）、`ConfirmationSignPage.vue:76`、`UserManagementPage.vue:168`（`records ?? items`）、`ResourceDispatchPage.vue:90`（`list ?? records`）、`AdminApplicationsPage.vue:90`（只认 `records`）。
- AI 分数注水两套且语义不同：`frontend/src/composables/useHazardList.js:38-58`（`h.aiScore ?? ai?.riskScore`，保留后端值）vs `dashboardApi.attachAiScores:612-630`（`aiScore: ai?.riskScore ?? null`，AI 列表缺失时**把已有 aiScore 覆盖为 null**）。后者被 `HazardsListPage.vue:209`、`ConfirmationWorkbenchPage.vue:122` 使用。

**影响**：同一种「后端多形态」在不同入口表现不同（某些页面裸数组可用、某些只认 items）；`attachAiScores` 与 `useHazardList` 的取值优先级相反，可能导致队列页 AI 分比列表页少（空白/0），而列表页正常。

**建议**：`normalizePaged` 直接删除（全量改 `normalizePage`）；`Array.isArray` 分支删除；三处手写 `{items}` 迁到统一归一化 + 视图映射；页面级 `records ?? items ?? list` 链全部改 `normalizeList`；`attachAiScores` 改为 `r.aiScore ?? ai?.riskScore ?? null` 与 `useHazardList` 对齐。

### P2-11　设备轨迹接口失败仍静默回退 mock 轨迹，与同文件「绝不静默造假」策略矛盾

**位置**：`frontend/src/services/_architecture/domain/deviceMapService.js:5-11`（头注释：默认失败即抛错、绝不静默造假）、`:77-103`（`fetchDeviceMap`：只有 `VITE_DEVICE_MAP_DEMO_FALLBACK==='1'` 才回退，且带 `demo:true`）、`:113-134`（**`fetchDeviceTrajectory` 失败无条件 `return { ...points:generateMockTrajectory(deviceCode), mock:true }`**，不受开关控制）；`frontend/src/views/monitor/DeviceMapPage.vue:683`（只渲染一个小 `MOCK` 标签）、`:402`（demoMode 只取 `fetchDeviceMap` 的 `data.demo`）。

**证据**：开关 `VITE_DEVICE_MAP_DEMO_FALLBACK` 只出现在 `deviceMapService.js:28/94` 与 `DeviceMapPage.vue:581` 文案；`.env.example`/`.env.local`/两个 compose 均**未声明**该变量（grep 结果），运维无法从配置样例发现它。

**影响**：设备列表严格、轨迹宽松，同一页面两种真假策略；接口 500 时轨迹照常画出假路线，只有角落一个 MOCK 小标；演示中容易把假轨迹当真数据讲。

**建议**：轨迹回退同样受 `ALLOW_DEMO_FALLBACK` 控制，默认 `throw`（页面显示「轨迹不可用」）；把开关补进 `frontend/.env.example` 与 compose（默认不设=关闭）。

### P2-12　角色中文名 3 份表且 `admin` / `system-admin` 命名冲突

**位置**：`frontend/src/stores/auth.js:18-54`（`admin='超管'`、`system-admin='系统管理员'`）；`frontend/src/views/system/UserManagementPage.vue:38-46`（`admin='系统管理员'`、`system_admin='运维管理员'`、`attendant='值守人员'`…）；`frontend/src/views/RegisterPage.vue:46-50`（`admin='系统管理员'`）；`frontend/src/services/mock/system.js:72-84`（mock 又一套）。

**证据**：同一角色在两个表里叫两个名字（admin：超管 vs 系统管理员）；「系统管理员」同时被 `admin` 与 `system-admin` 使用，用户无法区分。

**影响**：注册页、用户管理页、帮助中心、头像菜单对同一角色显示不同名称；评审/审计时角色口径无法对齐。

**建议**：以 `auth.js` 的 `ROLE_OPTIONS` 为唯一源（并统一 7 个中文名），其余页面 import；`getRoleLabel()` 已有，推广使用。

### P2-13　死路由 / 死菜单条目清理清单

**位置/证据**

- `ROLE_MENU_MAP` 中的非菜单/死条目（12 条）：`/dashboard/warnings/create`（admin/attendant/emergency-commander，路由只是 redirect 回列表，见 `TopToolbar.vue:136-137` 注释）、`/dashboard/warnings/:id/delivery`（admin/emergency-commander，**字面量 `:id` 永远不会被真实路径命中**，真实路径已被 `/dashboard/warnings` 前缀放行）、`/dashboard/reports`（ai-engineer，且已在 `LEGACY_REDIRECT_PATHS:707-713` 对全体放行，`router:348` 只 redirect 到 analytics）、`/dashboard/message-center|todo|approvals`（5 个角色，路由已改为「重定向到首页 + 打开通知抽屉」，`router:367-378`，菜单项早已从 `dashboardData.js` 移除）。
- `routePermissionMap`（`router/index.js:122-178`）中 **6 个 key 无对应路由 name**：`warning-create`、`message-center`、`todo-center`、`approval-center`、`warning-delivery`、`warning-resolve`。
- `moduleMeta`（`dashboardData.js:340-389`）与生效菜单的差集 7 条：其中 **5 条是已迁移/壳路由**（`/dashboard/warnings/create`、`/dashboard/message-center`、`/dashboard/todo`、`/dashboard/approvals`、`/dashboard/warnings/:id/resolve`），另 2 条 `/dashboard/events/:code`、`/dashboard/confirmation-sign/:id` 是真实子路由应保留。
- `auth.js:699` `FRAMEWORK_PATHS` 含 `/dashboard/error/404`，但 `router` 只注册了 `error/403`、`error/500`（404 走 catch-all `NotFoundPage`）。
- 路由 `satellite-compare`（`router:345`）没有 `routePermissionMap` 条目：`satellite:view` 权限码在 B1 决策里被专门引入，但前端路由层未用它判定（当前只靠 `ROLE_MENU_MAP` 路径限制；`data-manage`、`workbench` 同样无权限码）。

**影响**：权限/路由/菜单三处对照困难，后续维护者会按死条目做无效改动；`satellite:view` 的前端权限码形同虚设。

**建议**：一次性清理 `ROLE_MENU_MAP` 死路径、`routePermissionMap` 无主 key、`moduleMeta` 已迁移路径；给 `satellite-compare` 补 `routePermissionMap['satellite-compare']='satellite:view'`；`/dashboard/error/404` 从 `FRAMEWORK_PATHS` 删除或补路由。

### P2-14　`WarningDetailPage` 的等级下拉硬编码 3 档，缺少「蓝色预警」

**位置**：`frontend/src/views/warning/WarningDetailPage.vue:1152-1159`。

**证据**：四档等级里只有 `红色预警/橙色预警/黄色预警` 三个 `<option>`（无 value 绑定，提交中文文本）；后端 `WarningService.update` 直接写 `warning_level`，蓝色预警无法从该下拉选出。同时该下拉与 `WarningCreateModal` 的 4 档、`utils/alarmWarning.AI_RISK_BANDS` 4 档不一致。

**建议**：改用统一等级常量表 `v-for`，带 `value` 绑定。

---

## 冗余代码清单（表格）

| # | 冗余类型 | 位置（文件:行） | 证据 | 建议 |
|---|---|---|---|---|
| R1 | 未使用组件 | 全仓 115 个 `.vue` | 逐个 `import`/模板 kebab/`<component :is>`/路由懒加载检索：0 个无引用 | 无需处理（本条为负结果） |
| R2 | 死模块（285 行 + shim） | `src/services/_architecture/core/sentinelService.js:1-285`、`src/services/sentinelService.js:1-2` | `composeSentinelScene/pollSentinelJob` 全仓无调用（仅 `mapService.js:532` 注释）；shim 无 importer | 删除；能力已由 `satelliteCompareApi.js` 走 Java 网关替代 |
| R3 | 0 引用 service 方法（42） | `src/services/dashboardApi.js` 全文件 | 见 P2-4 列表；逐方法 `\bname\b` 全仓计数=1（仅定义行） | 按模块删除，`verifyBackup` 等刻意 501 契约保留注释 |
| R4 | 0 引用 service 方法/常量（小计 12 = 7 + 5） | `ruleEngineService.js`：`getRule/getRuleLogs/getAllRuleLogs/getFieldDicts/getOperators/reloadRules/rollbackRule`；`weatherService.js`：`getProductLatest/isWeatherBackendEnabled/EXTENT_CHINA_FY4A/EXTENT_FY4A_DISK/EXTENT_CHINA_NMC` | 全仓 0 外部引用（`RuleList/RuleEditor/RuleTestPanel` 只用 `getRules/toggleRule/deleteRule/testRule/createRule/updateRule`） | 删除未用方法/常量 |
| R5 | 0 引用 mock 导出（38） | `mock/operations.js` 14、`mock/seed.js` 15、`mock/empty.js` 5、`mock/runtime.js` 3、`mock/ai.js` 1 | 见 P2-4；`knip.json` 已把 `src/services/mock/**` 排除，所以 knip 也查不到 | 删除或纳入 knip 检查 |
| R6 | 重复 request 封装（6 套） | `httpClient.js:45`、`aiApi.js:15-41`、`satelliteCompareApi.js:23-43`、`driftMonitorService.js:26-63`、`multiModalService.js:17-39`、`deviceMapService.js:217+` | 各自实现 JWT 注入 / 401 / 错误文案，语义不一致 | 收敛到 `httpClient`（加 `basePath` 参数） |
| R7 | 重复时间格式化 | 26 处 `toLocaleString('zh-CN',{hour12:false})` + 15 个本地 `fmt*`（清单见 P2-3） | 同一格式复制 26 次；`warningTime.js` 仅覆盖预警 | 新建 `utils/datetime.js` 统一 |
| R8 | 重复等级常量表（≥8 份） | `alarmWarning.js:42-47`、`multiModalService.js:152-157`、`WarningsPage.vue:93-112`、`SettingsPage.vue:243-245`、`NotificationPreference.vue:21-24`、`WarningAnalysisDetail.vue:57`、`WarningDetailPage.vue:1156-1158`、`mapService.js:1396-1419`、`realtimeService.js:160-161` | 见 P2-2；红/橙同色、红/黄→蓝 | 单源 `utils/warningLevel.js` |
| R9 | 重复指标常量表（6 份） | `WarningAnalysisDetail.vue:30-51`、`RuleEditor.vue:80-89`、`RuleTestPanel.vue:31-40`、`DisasterForecastPage.vue:33-39`、`DeviceDetailPage.vue:22-31`（且 `rainfall_24h`/`rain_24h`、`tilt`/`tilt_angle` 双别名）、`AiComprehensiveTab.vue:145` | 同一组 `metric_code → label/unit` 6 处维护 | 抽 `data/metrics.js`（label/unit/source） |
| R10 | 重复灾种表（2 处硬编码） | `HazardsListPage.vue:699`、`ConfirmationWorkbenchPage.vue:393`、`services/mock/ai.js:219/268/291`、`DamageAssessmentPage.vue:151` | 已有唯一源 `utils/hazardTypes.js`，这 4 处未接入（其中 2 处是 P1-5 的失效筛选） | 统一 `HAZARD_TYPES` |
| R11 | 重复角色中文名表（3+1 份） | `auth.js:18-54`、`UserManagementPage.vue:38-46`、`RegisterPage.vue:46-50`、`mock/system.js:72-84` | admin 中文名冲突（超管 vs 系统管理员） | 单源 `ROLE_OPTIONS` |
| R12 | 重复权限判定写法 | 9 处本地 `has()` + `PermissionButton` + 17 处 `?? {role:'admin'}` | 见 P2-6 | 统一 store getter / PermissionButton |
| R13 | 重复分页归一化 | `dashboardApi.js:48-50` 别名、6 处 `Array.isArray`、3 处手写 `{items}`、7 处页面链式猜测；`realtimeService.js:531/535/539/556/576` 的 `list ?? records` 冗余（上游已归一化） | 见 P2-10 | 全量 `normalizePage/normalizeList`，删别名与冗余分支 |
| R14 | 重复 CSS 选择器/token | `styles.css` 130 个重复选择器、`.page-content` 3 处全局定义（`:802/:2068/:8016`）、45 个 token 与 `tokens.css` 双源、`visual-refresh.css` 第三层覆写 | 见 P2-1 | 分层合并、删被覆盖的旧定义 |
| R15 | 重复 AI 分数补充实现 | `useHazardList.js:38-58` vs `dashboardApi.js:612-630` | 优先级相反（后者会把 aiScore 覆盖成 null） | 合并到 `attachAiScores` 并保留后端值 |
| R16 | 已撤下模块残留 | 42 个方法（R3）+ 4 个 mock 文件族 + 3 条 redirect 路由（`router:348-350`） | 页面已不存在（`views` 下无 Plan/Backup/Report） | 随模块删除 |
| R17 | 装了未用的工具 | `frontend/knip.json`、`frontend/package.json:46` | `knip` 无 script、无 CI 调用（仓库 grep 仅 2 处） | 加 `"lint:dead": "knip"` 并接 CI |
| R18 | 冗余组件包装 | `components/common/*Skeleton.vue` 共 20 个骨架组件 | 均有引用（无冗余），但 20 个文件重复同一套 shimmer 样式；`SkeletonBar/List/Table` 已是泛化件的页面仍各写一份 | 后续可合并为参数化骨架（非本轮缺陷） |

---

## 逻辑冲突清单（表格）

| # | 冲突主题 | 位置 | 两侧口径 | 影响 | 分级 |
|---|---|---|---|---|---|
| C1 | 菜单 vs 路由权限码 | `dashboardData.js` ↔ `auth.js` ↔ `router/index.js:122-178` | 菜单可见但 `routePermissionMap` 要求的码该角色没有（10 条） | 点菜单 403 | P1-1 |
| C2 | 审批状态/决策域 | `ApprovalCenterTab.vue:30-106` ↔ `ApprovalCenterController.java:108-189` | 前端 `pending/approved/rejected` + 字符串 body ↔ 后端中文状态 + `Map{decision: approve 或 reject}` | 列表空、按钮 400 | P1-2 |
| C3 | 预警集合口径 | `realtimeService.js:520-563` ↔ `WarningController.java:69-80` / `WarningService.java:102-110` | `/warnings` 全量 ↔ `/warnings/preview` 仅活动；两者同写 `state.warnings` | 点位/条数 10s↔15s 抖动 | P1-3 |
| C4 | 会话吊销 | `UserProfileMenu.vue:66-70`、`ProfilePage.vue:57-67` ↔ `dashboardApi.js:160-165`、`/auth/logout` | 前端只清本地 ↔ 后端提供吊销且从未被调用 | token 退出后仍有效 | P1-4 |
| C5 | 灾种/等级域 | `HazardsListPage.vue:125/699`、`ConfirmationWorkbenchPage.vue:86-90/393` ↔ `data.sql:672-682`、`mock/workflow.js:13-46` | 前端中文选项/`.value` 取值 ↔ 真实库英文码 + 仅 高/中风险 ↔ mock 中文 | 筛选整组失效 | P1-5 |
| C6 | 运行模式 | `dashboardApi.js:27` ↔ `runtime.js:26-149`、`App.vue:28-30` | 导入期静态 `USE_BACKEND` ↔ 运行时动态模式 | 自动探测模式全站 mock 而徽标「真实数据」 | P1-6 |
| C7 | 权限三源 | `auth.js:285-685` ↔ `PermissionRegistry.java` ↔ `data.sql:102-486` | 126 vs 125（缺 `inspection:gps-check`）vs 115（缺 20/多 9、`rule:*` vs `rules:*`）；DB 表无消费者 | 权限矩阵与文档/DB 不一致；生成器继续丢码 | P1-7 |
| C8 | 等级颜色/文案 | `mapService.js:1396-1419`、`realtimeService.js:160-161`、`CommandScreenPage.vue:524-656`、`MobileCommandPage.vue:79-113` ↔ `WarningService.cssClass()` 四色、`WarningDto.level='红色预警'` | 红橙同色；红/黄兜底成蓝；`lvl-红色预警` 无选择器；Mobile 以 red/blue 与英文 status 为键 | 等级色/文案错误 | P2-2 / P2-9 |
| C9 | 主题系统 | `useTheme.js:45-64/119-121` ↔ `styles.css:56` 注释 + 全仓无 `prefers-color-scheme` | 「跟随系统」清钩子但 CSS 不响应系统 | 永远浅色、`isDark()` 说谎 | P2-8 |
| C10 | demo 兜底策略 | `deviceMapService.js:77-103` ↔ `:113-134` | 设备列表严格不回退 ↔ 轨迹失败静默造 mock | 同页两种真假策略 | P2-11 |
| C11 | 分页/注入语义 | `api/client.js` ↔ `dashboardApi.js:48-50/194/202/…/2274` ↔ 页面链式取值 ↔ `useHazardList.js:38-58` vs `attachAiScores:612-630` | 归一化有 2 层 + 手写 3 处；AI 分覆盖优先级相反 | 页面行为不一致 | P2-10 |
| C12 | AI 分数等级域（待确认真实库） | `data.sql:712-716`（`red/orange/yellow`）↔ `AiInsightService.java:326`（`CRITICAL/HIGH/…`）↔ `AnalyticsPage.vue:229-238`（只认大写） | 种子域 vs 运行态域不同 | 新库首屏排名徽标全「低」/同色 | 待确认 |
| C13 | 死路由/死菜单 | `auth.js:80-283`、`router/index.js:122-178/343-378`、`dashboardData.js:340-389` | 三处保留已迁移/永不命中的路径 | 维护误导、`satellite:view` 未用于路由 | P2-13 |

---

## 验证为正确的部分

1. **无未使用组件/死导入**：115 个 `.vue` 全部被静态 import、路由懒加载或模板引用覆盖；被怀疑的 `ruleEngineService` 实际由 `RuleList.vue:70/103/115`、`RuleEditor.vue:170/190`、`RuleTestPanel.vue:50` 以 `await import()` 动态引用（动态引用已计入检索，非误报）。
2. **预警时间权威路径统一**：`utils/warningTime.js` 的 `formatWarningTime/formatTimelineTime` 被 **8 个文件/15 个调用点**使用（`GlobalAlarmModal.vue:119`、`WarningsPage.vue:696/704/779`、`WarningDetailPage.vue:98/109/120/136/146/1172`、`EventStatusTimeline.vue:201`、`WarningDeliveryTab.vue:173`、`ZoneDashboardPage.vue:577`、`OverviewPage.vue:1947`、`MobileCommandPage.vue:460`）；全仓未发现把 `WarningDto.time`（UTC 快照）直接渲染为绝对时间的页面——`MobileCommandPage.vue:209`、`WarningDeliveryTab.vue:175` 的 `time` 只作「旧数据/mock 无 triggeredAt」的兼容快照，符合该文件注释。
3. **等级展示字段用 `cssClass` 而非 `class`**：`realtimeService.js:160`、`OverviewPage.vue:1938/1942`、`ZoneDashboardPage.vue:570/574`、`WarningsPage.vue:106`、`alarmWarning.js:156` 全部 `cssClass ?? class` 兼容；后端 `WarningDto` 确有 `cssClass` 字段（`WarningDto.java:17`）且 `WarningService.cssClass()` 是正确四色。
4. **`hazardTypes.js` 唯一源已部分落地**：`NoticeTemplatePage.vue:29/77`、`WarningCreateModal.vue:23/84` 直接用 `HAZARD_TYPES`；未接入的仅 2 个筛选下拉（已报 P1-5）+ mock 表。
5. **权限码一致性（UI 判定面）**：UI 中实际判定的 39 个权限码**全部**存在于前端角色表与后端 `PermissionRegistry`（脚本交叉结果为空）——只有 `inspection:gps-check` 属「表里有、UI 不判、后端也无」，已并入 P1-7。
6. **`normalizeRole` 与「绝不回落 admin」保持有效**：`auth.js:73-78/757-768` 的修复未被回退；`hasPathAccess` 的 `/dashboard` 精确匹配 + `LEGACY_REDIRECT_PATHS` 白名单（`:698-731`）仍是收紧状态。
7. **设备地图演示兜底符合 2026-11-14 决策**：`deviceMapService.js:28/94-102` 默认关闭、只有开关=1 才 mock 且带 `demo:true`；`DeviceMapPage.vue:578-583` 有「演示数据」横幅、`:402` 正确读取 `data.demo`——主设备列表没有静默假数据（轨迹是例外，见 P2-11）。
8. **AI 融合不再静默造假评分**：`AiComprehensiveTab.vue:322-328` 失败时显式置空 + 错误文案，注释与代码一致（`mockFusionResult()` 已仅剩定义，见 R4/R5 死导出）。
9. **分页归一化主体已归一到 `api/client.js`**：`normalizePage/normalizeList` 已被 `ModelManagePage`、`NoticeTemplatePage`、`DataSyncPage`、`DataCatalogPage`、`PersonnelManagePage`、`SettingsPage`、`OtaManagePage`、`WarningDeliveryTab`、`TodoCenterTab` 等 12+ 处采用，`frontend/src/__tests__` 下另有 13 个 spec（含页面级回归）；残余分支见 P2-10。
10. **坐标转换单源**：`utils/coordTransform.js` 是唯一 WGS84↔GCJ02 实现，被 `mapService.js:20`、`geocodeService.js:21`、`DeviceMapPage.vue:18` 共用，未发现第二套。
11. **查询串单源**：`utils/query.js` 的 `withQuery` 被全部 domain service 使用，未发现手写 `?a=&b=` 拼串（仅 `weatherApi/geocodeService/aiApi` 对第三方 URL 用 `URLSearchParams`，属合理场景）。
12. **路由守卫未发现可直接越权**：`/admin/applications` 虽不走 `/dashboard/` 分支的路径 403，但仍被 `routePermissionMap['admin-applications']='users:approve'` 挡住（非 admin 无该码）；`/screen`、`/mobile`、`/register` 为显式 `public` 路由。

---

## 待确认项

1. **P1-6 是否会在交付路径触发**：`.env.local`（gitignored）与 `.env.example`、两个 compose、`frontend/Dockerfile` 均强制 `VITE_USE_BACKEND=true`，因此完整交付路径不触发；但「全新克隆后 `npm run dev`」「本地 `npm run build` + `vite preview`」会触发。请确认官方运行/验收流程是否 100% 注入该变量。
2. **`ai_comprehensive_score.risk_level` 真实库现值**：`data.sql:712-716` 种子写 `red/orange/yellow`，而运行态写入是大写 `CRITICAL/HIGH/...`（`AiInsightService.java:326`）；`AnalyticsPage.vue:229-238` 只认大写。需查真实库现有行 + 5 分钟落库周期是否会把种子覆盖，才能定级（暂列 P2/待确认）。
3. **P2-9 建议浏览器复核**：`lvl-红色预警` 无匹配选择器是 CSS 精确类名匹配的确定结论，但大屏/移动页是否还有别的内联样式兜底（`levelColor` 在 CommandScreen 正确、Mobile 错误）需要截图确认最终观感。
4. **审批中心是否仍在交付范围**：菜单项早已撤下（`dashboardData.js:245-269` 注释「待办/审批/消息已迁移至全局通知抽屉」），但抽屉 Tab 仍在。若产品已决定下线，可直接按「冗余」删除而不是修契约（P1-2 降为 P2）。
5. **`sys_permission`/`sys_role_permission` 是否还有运维依赖**：Java 侧无消费者，但 `scripts/migrations/V20261107_weather_refresh_permission.sql` 仍向该表写数据；需确认是否还有外部 SQL/报表/验收脚本读这两张表，再决定「删表数据」还是「让后端改读表」。
6. **投递回执状态映射重复**：`NotificationChannelsPage.vue:263-295`、`NoticeTemplatePage.vue:189-191`、`WarningDeliveryTab.vue:328` 各自维护 `SENT/MOCK_SENT/BLOCKED/FAILED/PARTIAL` 语义映射（未逐条比对差异），是否需要统一到一处待产品口径确认。
7. **`WarningCreateModal` 与 `/dashboard/warnings/create`**：路由仍是 redirect 壳（`router/index.js:293-295`），`ROLE_MENU_MAP` 仍留给 3 个角色；若确认「新建」只走列表页 `?create=1`（`TopToolbar.vue:136` 注释方向），可连同死条目一起删。
