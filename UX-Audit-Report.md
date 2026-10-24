# DisasterGuard 前端 UX 审计报告

审计范围：`frontend/src/` 目录下所有 .vue 组件
审计日期：2026-10-24

---

## 一、加载状态可见性（Loading State Visibility）

**评级：PARTIAL（部分通过）**

### 优点

1. **主要列表页均有 loading 状态**：51 个 .vue 文件使用了 `v-if="loading"` 模式，覆盖了大部分核心页面。
   - `AdminApplicationsPage.vue:234` - 表格骨架屏加载
   - `UserManagementPage.vue:409` - 用户列表骨架屏
   - `DevicesPage.vue:179` - 设备列表 SkeletonTable 组件
   - `HazardDetailPage.vue:556` - 详情页加载提示

2. **有专门的骨架屏组件库**：
   - `components/common/SkeletonTable.vue` - 表格骨架屏
   - `components/common/SkeletonList.vue` - 卡片列表骨架屏
   - `components/common/SkeletonBar.vue` - 条形骨架屏基础组件

3. **按钮级 loading 反馈**：提交按钮普遍使用 `:disabled` + `Loader2 spin` 图标模式。
   - `WarningCreatePage.vue:353-354` - 提交发布按钮
   - `UserManagementPage.vue:209` - 保存按钮
   - `WelcomePage.vue:1499` - 登录按钮

### 问题

1. **全局 Toast 无 loading 态**：MainLayout 的全局 Toast 只展示最终结果（成功/失败），操作进行中用户只能依赖按钮上的小图标，反馈不够明显。
   - `MainLayout.vue:205-224` - toast 只有文本消息，没有 pending/loading 状态

2. **部分异步操作静默加载**：
   - `MainLayout.vue:129-165` - `refreshDynamicBadges()` 红点刷新失败时 `console.warn` 静默处理，用户无感知
   - `HazardDetailPage.vue:266-299` - `loadData()` 中只有最外层 loading，内部 AI 预测/综合研判的加载状态未在主视觉区域展示
   - `WarningCreatePage.vue:162-168` - bootstrap 加载灾害点详情，`catch {}` 空吞异常

3. **骨架屏覆盖率不均**：部分页面有骨架屏（AdminApplications、UserManagement、WarningDelivery），但也有不少页面仅用简单文字"加载中..."：
   - `HazardDetailPage.vue:556-558` - 仅显示"加载中..."文字
   - `ConfirmationWorkbenchPage` - 需确认是否有骨架屏

4. **初始加载与刷新无视觉区分**：首次加载和手动刷新使用相同的 loading 状态，用户无法区分是"正在初始化"还是"正在刷新数据"。

---

## 二、错误消息质量（Error Message Quality）

**评级：PARTIAL（部分通过）**

### 优点

1. **登录页错误处理完善**：
   - `WelcomePage.vue:239-248` - 客户端表单验证（账号/密码非空）
   - `WelcomePage.vue:285-286` - 服务端错误回显到 `loginError`
   - `WelcomePage.vue:1497` - 使用 `role="alert"` 保证可访问性
   - `WelcomePage.vue:251-253` - 大写锁定检测与提示

2. **注册页错误处理最完善**：
   - `RegisterPage.vue:170-204` - 逐字段验证，错误对象 `e` 包含每个字段的错误信息
   - 支持手机号格式、邮箱格式、长度限制等多种校验规则
   - 使用 banner 组件展示全局成功/错误消息

3. **Toast 统一出口**：
   - `MainLayout.vue:276` - `<router-view @notify="notify" />` 统一接收子页面的操作反馈
   - 58 个文件使用 `emit('notify', msg)` 模式，全站共 446 处调用

### 问题

1. **Toast 不区分成功/失败样式**：
   - `styles.css:1993` - `.toast` 只有一种样式（深绿色背景），成功和失败消息外观完全一致
   - `MainLayout.vue:334-338` - `.main-layout-toast` 同样只有一种颜色 `var(--c-brand-deeper, #245b5d)`
   - 用户无法从视觉上快速判断操作是成功还是失败

2. **大量静默吞异常**：搜索到 41 处 `catch {}` 空代码块，错误完全不反馈给用户：
   - `WarningCreatePage.vue:168` - `catch {}` 灾害点加载失败无提示
   - `UserManagementPage.vue:181` - `catch {}` 角色加载失败无提示
   - `HazardDetailPage.vue:317` - AI 预测失败 `catch (_e) { aiForecast.value = null }` 无提示
   - `HazardDetailPage.vue:232` - AI 综合研判失败静默降级

3. **表单验证错误仅靠 Toast**：大部分业务表单的验证错误通过 `emit('notify', '请补全必填项')` 展示，而非内联到对应字段：
   - `WarningCreatePage.vue:191` - `return emit("notify", "请补全必填项")` 不告知具体哪个字段
   - `OtaManagePage.vue:262-263` - 验证失败仅 toast 提示，无字段级高亮
   - `DevicesPage.vue:127-128` - 同样只有 toast 提示

4. **错误文案不统一**：
   - 有的用"失败"，有的用"出错了"
   - 有的包含错误原因（`e.message`），有的只有固定文案

5. **网络错误无重试入口**：列表加载失败后大多只显示 Toast，页面停留在空白或旧数据状态，没有"点击重试"按钮。

---

## 三、破坏性操作确认（Destructive Operation Confirmation）

**评级：PARTIAL（部分通过）**

### 优点

1. **自定义 ConfirmDialog 组件设计良好**：
   - `components/common/ConfirmDialog.vue:41-48` - Promise 式 API，返回 `Promise<boolean>`
   - 支持 `isDanger` 危险操作红色按钮样式
   - 确认按钮有 loading 状态

2. **多数删除操作有确认对话框**：
   - `UserManagementPage.vue:252-259` - 删除用户确认（含 `isDanger: true`）
   - `OtaManagePage.vue:280-286` - 删除固件确认（含"不可恢复"警示）
   - `OtaManagePage.vue:344-348` - 取消升级任务确认
   - `AiTaskManagePage.vue:158-162` - 删除任务确认
   - `AiResultsPage.vue:120-124` - 归档（误报）确认
   - `HazardDetailPage.vue:122-128` - 确认灾害确认
   - `AiInsightPage.vue:121-125` - 发布预警确认

3. **确认文案具体明确**：不是通用的"确认删除吗"，而是包含操作对象名称。
   - 例：`确认删除用户 ${user.name}？该操作不可撤销。`

### 问题

1. **DevicesPage 删除设备无确认**（高危）：
   - `DevicesPage.vue:89-98` - `deleteDevice()` 函数直接调用 API 删除，没有任何确认对话框
   - `DevicesPage.vue:304-306` - 模板中删除按钮点击后直接执行 `deleteDevice()`
   - 这是已发现的最严重的 UX 安全漏洞

2. **部分"禁用/启用"操作无确认**：
   - `UserManagementPage.vue:276-287` - `toggleEnabled()` 直接切换状态，无确认
   - 虽然影响不如删除大，但也是状态变更操作

3. **批量操作确认情况不明**：未检查到批量删除的确认模式。

4. **确认对话框样式不统一**：部分页面使用 `ConfirmDialog` 组件，部分可能使用 Ant Design Vue 的 `a-modal` 原生确认。

---

## 四、表单错误恢复（Form Error Recovery）

**评级：PARTIAL（部分通过）**

### 优点

1. **登录表单提交失败保留输入值**：
   - `WelcomePage.vue:255-290` - 登录失败后表单值保留，只清空错误后可直接修改重提

2. **WarningCreatePage 向导式表单保留进度**：
   - 4 步向导（`WarningCreatePage.vue:56`），每步数据独立保存
   - 提交失败后用户仍在第 4 步，可修改后重新提交

3. **UserManagementPage 有本地 fallback 机制**：
   - `UserManagementPage.vue:220-238` - 后端失败时本地修改数据作为 fallback
   - 但这更多是离线模式设计，不是标准的"错误恢复"

4. **RegisterPage 验证码自动刷新**：
   - `RegisterPage.vue:218-221` - 加载新验证码时清空旧的 captchaCode
   - 避免用户用过期验证码提交

### 问题

1. **提交失败后无字段级错误高亮**：
   - 绝大多数表单验证失败只弹 Toast，用户不知道具体哪个字段错了
   - 没有类似 `:class="{ error: hasError }"` 的字段错误态样式

2. **部分 catch 中直接关闭弹窗**：
   - `UserManagementPage.vue:238` - 后端失败的 fallback 分支也会关闭弹窗 (`editDialog.value = false`)
   - 用户无法在弹窗中修正后重新提交

3. **表单重置行为不一致**：
   - 成功后是否重置表单：有的重置（新建用户），有的不重置
   - 失败后是否保留数据：大多保留（好），但部分操作后状态混乱

4. **WarningCreatePage 提交失败无明确恢复指引**：
   - `WarningCreatePage.vue:216-217` - 失败后只 emit notify，用户仍在第 4 步确认页
   - 没有提示用户回到哪一步修改

5. **HazardDetailPage 编辑模式保存失败无反馈**：
   - `HazardDetailPage.vue:341-347` - `saveDetail()` 没有 try/catch，失败后可能静默

---

## 五、空状态处理（Empty State Handling）

**评级：PARTIAL（部分通过）**

### 优点

1. **核心列表页有空状态**：
   - `AdminApplicationsPage.vue:240-244` - 带图标和文案的空状态（Inbox 图标 + "没有符合条件的注册申请"）
   - `MobileCommandPage.vue:413-415` - 带 CheckCircle2 图标的空状态
   - `DataSyncPage.vue:242-244` - 带 Database 图标的空状态

2. **AccountSecurityPage 空状态设计细致**：
   - `AccountSecurityPage.vue:569` - 备用码空状态含操作指引
   - `AccountSecurityPage.vue:625` - 登录历史空状态
   - `AccountSecurityPage.vue:689` - 活跃会话空状态
   - `AccountSecurityPage.vue:751` - 审计记录空状态

3. **表格空状态较普遍**：搜索到约 20+ 处空状态实现。

### 问题

1. **空状态实现方式不统一**：
   - 有的用 `.empty-state` 类名
   - 有的用 `.table-empty`
   - 有的用 `.ds-empty` / `.cs-empty` 等页面专属类名
   - 没有统一的 EmptyState 组件

2. **空状态质量参差不齐**：
   - 好的空状态：带图标 + 描述 + 操作按钮（引导用户下一步）
   - 差的空状态：只有"暂无数据"四个字（如 `ModelManagePage.vue:528`）

3. **空状态与加载状态切换可能闪烁**：
   - 部分页面 `v-if="loading"` ... `v-else-if="!items.length"` ... `v-else` 顺序正确
   - 但也有页面先渲染空状态再替换为数据，造成"暂无数据"闪烁

4. **筛选结果为空与初始空状态无区分**：筛选后无结果和初始无数据使用相同文案，用户可能困惑是"系统里没数据"还是"筛选条件不对"。

5. **详情页数据为空时处理不足**：
   - `HazardDetailPage.vue:559` - `v-else-if="point"` 之外没有 `v-else` 的空/错误状态
   - 如果 API 返回 null，页面可能白屏或部分渲染

---

## 六、响应式设计（Responsive Design）

**评级：PARTIAL（部分通过）**

### 优点

1. **布局层有响应式设计**：
   - `MainLayout.vue:340-354` - 1024px 侧栏缩窄，768px 侧栏隐藏
   - `DashboardLayout.vue:1567-1582` - 多断点响应式（1024px / 880px / 768px）
   - `ContentArea.vue:370-384` - 内容区域响应式

2. **WelcomePage 响应式较完善**：
   - `WelcomePage.vue:4269` - 980px 断点（移动端导航切换）
   - `WelcomePage.vue:4286` - 767px 断点
   - `WelcomePage.vue:4426` - 560px 小屏优化
   - `WelcomePage.vue:445` - JS 端也有对应 `DESKTOP_QUERY` 判断，保持一致

3. **覆盖面广**：搜索到 100+ 处 @media 查询，覆盖约 50+ 页面/组件

4. **支持 prefers-reduced-motion**：多处使用 `@media (prefers-reduced-motion: reduce)` 尊重用户动效偏好
   - `WelcomePage.vue:4320`、`AnimatedLoginBackground.vue:254`、`BrandMascot.vue:52` 等

### 问题

1. **响应式断点不统一**：
   - 有的用 980px（WelcomePage）
   - 有的用 1024px（MainLayout、DashboardLayout）
   - 有的用 768px（MainLayout）
   - 有的用 720px（DataManagePage、FieldInspectionPage）
   - 有的用 900px（AiResultsPage、AuditLogPage）
   - 缺乏统一的设计系统断点规范

2. **768px 以下侧栏完全消失但无汉堡菜单**：
   - `MainLayout.vue:347-349` - 768px 以下 `.main-layout-sidebar { display: none }`
   - 但没有提供移动端导航菜单入口，用户在小屏上无法访问二级菜单

3. **表单页响应式较弱**：
   - `WarningCreatePage.vue` - 未检测到移动端适配（只有桌面端的 `.row-2` 两列布局）
   - 部分内页只有简单的 grid 列数调整，没有考虑触控友好性

4. **表格类页面移动端体验差**：大量表格页面在小屏上会横向滚动，没有适配卡片式布局。

---

## 七、导航/返回按钮行为（Navigation/Back Button Behavior）

**评级：PASS（通过）**

### 优点

1. **详情页统一使用 router.back()**：
   - `HazardDetailPage.vue:538` - 返回按钮
   - `WarningDetailPage.vue:127` - 返回按钮
   - `DeviceDetailPage.vue:114` - 返回按钮
   - `TaskDetailPage.vue:298` - 返回按钮

2. **WarningCreatePage 有返回按钮**：
   - `WarningCreatePage.vue:237` - 向导页左上角返回按钮
   - 提交成功后提供"返回预警列表"和"查看详情"两个选择

3. **ForbiddenPage 智能返回**：
   - `ForbiddenPage.vue:54-57` - 有历史记录则 back，否则跳首页
   - 避免了从外部链接直接进入 403 页面时返回按钮不可用的问题

4. **MainLayout 路由保护完善**：
   - `MainLayout.vue:173-178` - 相同路径不重复 push，避免 NavigationDuplicated 警告
   - `MainLayout.vue:87-103` - 未匹配大类时保留上次位置，不闪烁跳回第一个

### 问题

1. **表单页返回未做数据丢失确认**：
   - `WarningCreatePage.vue:237` - 直接 `router.back()`，如果用户填了一半数据会直接丢失
   - 没有"表单有未保存内容，确定离开吗？"的确认提示

2. **提交成功后返回列表位置丢失**：返回列表页后通常重新加载第一页，不保留用户之前的筛选和页码。

3. **部分详情页返回按钮与浏览器后退行为重复**：页面内返回按钮和浏览器返回功能完全一样，属于冗余但无负面影响。

---

## 八、术语一致性（Terminology Consistency）

**评级：PARTIAL（部分通过）**

### 问题

1. **"灾害点" vs "隐患点" vs "点位"混用**：
   - `HazardDetailPage.vue:562` - "点位名称"
   - `WelcomePage.vue:97` - "灾害监测点"
   - `WarningCreatePage.vue:275-276` - "灾害点名称"
   - `HazardDetailPage.vue:534` - "灾害点详情"
   - 同一概念有多种表述

2. **"预警" vs "告警" vs "警报"**：
   - 大部分用"预警"（正确）
   - 但也有"警报"（`WarningCreatePage.vue` 中的声光报警器相关）
   - 建议明确术语体系

3. **"用户" vs "账号" vs "人员"**：
   - `UserManagementPage.vue` - 用户管理
   - `PersonnelManagePage.vue` - 人员管理
   - 注册申请用"账号"
   - 需要明确三者关系和使用场景

4. **"设备" vs "终端" vs "监测点"**：
   - `DevicesPage.vue` - 设备管理
   - `WelcomePage.vue:69` - "monitors: 128" 监测点
   - KPI 里"设备"和"监测点"是两个数字，但页面中有时混用

5. **"角色"术语不一致**：
   - `ROLE_OPTIONS` vs `ROLES_ORDER` 两套角色定义
   - `UserManagementPage.vue:38-46` 的 `ROLES_ORDER` 有 7 种角色
   - `auth.js` 的 `ROLE_OPTIONS` 可能不同

6. **Toast 提示文案风格不统一**：
   - 有的开头有"已"："已删除用户"、"已更新"
   - 有的是陈述："用户创建成功"
   - 有的带括号说明："（本地缓存，后端连接后自动同步）"

---

## 关键问题优先级汇总

### P0（必须修复）

| # | 问题 | 位置 | 影响 |
|---|------|------|------|
| 1 | 设备删除无确认对话框 | `DevicesPage.vue:89-98` | 误删设备不可逆 |

### P1（强烈建议修复）

| # | 问题 | 位置 | 影响 |
|---|------|------|------|
| 1 | Toast 不区分成功/失败样式 | `styles.css:1993`, `MainLayout.vue:334` | 用户无法快速识别操作结果 |
| 2 | 表单验证错误无字段级高亮 | 大部分表单页 | 用户不知道具体哪个字段错了 |
| 3 | 768px 以下侧栏消失无替代导航 | `MainLayout.vue:347-349` | 移动端用户无法访问二级菜单 |
| 4 | 表单页返回无未保存提示 | `WarningCreatePage.vue:237` 等 | 用户数据意外丢失 |

### P2（建议优化）

| # | 问题 | 位置 | 影响 |
|---|------|------|------|
| 1 | 空状态组件不统一 | 各页面散落实现 | 维护成本高，体验不一致 |
| 2 | 响应式断点不统一 | 各页面 @media 断点各异 | 跨页面体验割裂 |
| 3 | 部分 catch 静默吞异常 | 41 处空 catch | 出错时用户无感知 |
| 4 | 术语不一致 | 多处 | 用户认知负担 |
| 5 | 全局 Toast 无 loading 状态 | `MainLayout.vue:205-224` | 操作反馈链不完整 |

### P3（锦上添花）

| # | 问题 | 位置 | 影响 |
|---|------|------|------|
| 1 | 骨架屏覆盖率不均 | 部分页面有，部分没有 | 加载体验不一致 |
| 2 | 筛选空状态 vs 初始空状态无区分 | 各列表页 | 用户可能困惑 |
| 3 | 提交成功后列表页位置丢失 | 各详情/创建页 | 操作连续性差 |
| 4 | prefers-reduced-motion 支持范围可扩大 | 部分动效未适配 | 无障碍可进一步提升 |

---

## 总体评分

| 维度 | 评级 | 说明 |
|------|------|------|
| 加载状态可见性 | **PARTIAL** | 列表页较好，详情页和全局反馈不足 |
| 错误消息质量 | **PARTIAL** | 登录/注册页完善，业务表单依赖 Toast 且无样式区分 |
| 破坏性操作确认 | **PARTIAL** | 大部分有确认，但 DevicesPage 删除无确认是严重漏洞 |
| 表单错误恢复 | **PARTIAL** | 数据保留尚可，但字段级错误反馈缺失 |
| 空状态处理 | **PARTIAL** | 有实现但不统一，质量参差不齐 |
| 响应式设计 | **PARTIAL** | 布局层尚可，内页表格和表单移动端体验差 |
| 导航/返回按钮行为 | **PASS** | 实现一致，行为可预期 |
| 术语一致性 | **PARTIAL** | 核心概念存在多种表述 |

**整体评估**：DisasterGuard 前端在基础 UX 模式上有较好的框架意识（统一 Toast、ConfirmDialog 组件、骨架屏组件），但在执行细节上存在较多不一致和遗漏。最突出的问题是**设备删除无确认**、**Toast 无成功/失败区分**、**表单无字段级错误提示**。建议优先修复 P0/P1 级问题。
