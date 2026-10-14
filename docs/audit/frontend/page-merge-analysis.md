# DisasterGuard 页面功能重叠度与合并可行性分析报告

> 扫描范围：`frontend/src/views` 目录下 6 组共 28 个页面
> 分析维度：功能概述 / UI结构相似性 / 合并建议

---

## 一、AI 相关页面（6 个）

### 页面清单
| 页面 | 路径 | 核心功能 |
|------|------|----------|
| AiTaskManagePage | ai/AiTaskManagePage.vue | AI 巡检任务调度、执行状态跟踪、任务创建与管理 |
| AiResultsPage | ai/AiResultsPage.vue | AI 推理结果展示、置信度、模型投票、人工复核入口 |
| AiInsightPage | ai/AiInsightPage.vue | AI 综合研判、多维风险分析、灾害评估结论 |
| DisasterForecastPage | ai/DisasterForecastPage.vue | 灾害预测（LSTM 时序模型）、趋势曲线、预测区间 |
| DamageAssessmentPage | ai/DamageAssessmentPage.vue | 灾损评估（单点/全域）、经济损失估算、影响人口 |
| ModelManagePage | ai/ModelManagePage.vue | 模型版本管理、训练任务、漂移监控、模型评估 |

### 功能概述与业务链路
这 6 个页面构成一条完整的 **AI 生产链路**：

```
模型管理 → 巡检任务 → 推理结果 → 综合研判 → 灾害预测/灾损评估
```

- **上游**：`ModelManagePage` 管理模型资产（版本、训练、漂移）
- **中游**：`AiTaskManagePage` 调度任务 → `AiResultsPage` 产出推理结果
- **下游**：`AiInsightPage` 做综合研判，输出 `DisasterForecastPage`（预测）和 `DamageAssessmentPage`（评估）两类分析产物

### UI 结构相似性

| 页面 | 顶部 | 主体布局 | 交互模式 |
|------|------|----------|----------|
| AiTaskManagePage | 统计卡片 + 操作按钮 | 左侧筛选 + 右侧任务列表/表格 | 点击行 → 详情抽屉 |
| AiResultsPage | 统计卡片 + 置信度分布 | 左侧筛选 + 右侧结果卡片列表 | 点击卡片 → 详情抽屉 + 复核操作 |
| AiInsightPage | KPI 指标卡 + 风险等级 | 多面板布局（雷达图 + 趋势 + 建议） | 数据展示为主，少量操作 |
| DisasterForecastPage | 预测概览卡 | 主图（趋势曲线）+ 下方多指标面板 | 时间范围选择 + 区域切换 |
| DamageAssessmentPage | 灾损总览卡 | 左侧区域选择 + 右侧评估结果表格/图表 | 单点计算 / 全域计算切换 |
| ModelManagePage | 模型统计卡 | 左侧模型列表 + 右侧版本详情 + 训练任务 Tab | 点击模型 → 右侧详情抽屉/Tab |

**相似点**：
1. 均采用「顶部统计卡片 + 主体列表/详情 + 抽屉/弹窗操作」的经典后台布局
2. 均有筛选/过滤条（按状态、时间、类型等维度）
3. 详情查看均以右侧抽屉或模态弹窗形式呈现

**差异点**：
1. `AiInsightPage`、`DisasterForecastPage`、`DamageAssessmentPage` 以 **数据可视化（图表）** 为主，操作较少
2. `AiTaskManagePage`、`AiResultsPage`、`ModelManagePage` 以 **列表 + 操作** 为主，属于生产管理类

### 合并建议

| 合并方案 | 涉及页面 | 可行性 | 说明 |
|----------|----------|--------|------|
| **Tab 合并：AI 工作台** | AiTaskManagePage + AiResultsPage | ⭐⭐⭐⭐⭐ 强烈推荐 | 任务与结果是强上下游关系，用户常需在两者间切换。建议合并为「AI 巡检工作台」，左侧任务列表，右侧 Tab 切换「任务管理/推理结果」，或顶部 Tab 切换视图。 |
| **Tab 合并：AI 分析中心** | AiInsightPage + DisasterForecastPage + DamageAssessmentPage | ⭐⭐⭐⭐ 推荐 | 三者都是 AI 分析的输出产物，面向同一类用户（研判人员）。建议合并为「AI 综合研判」页面，顶部 Tab 切换「综合研判 / 灾害预测 / 灾损评估」，共用左侧区域选择器。 |
| **独立保留：模型管理** | ModelManagePage | ⭐⭐ 不建议合并 | 面向模型工程师/管理员，用户角色与其他页面差异较大，且功能复杂度高（版本、训练、漂移），独立页面更清晰。 |
| **抽屉/侧边栏：推理结果快捷查看** | AiResultsPage → AiTaskManagePage | ⭐⭐⭐ 可考虑 | 在任务详情抽屉中增加「推理结果」Tab，让用户从任务直接查看对应结果，无需跳转。但完整的结果列表仍保留独立页面。 |

---

## 二、事件/灾害相关（5 个）

### 页面清单
| 页面 | 路径 | 核心功能 |
|------|------|----------|
| HazardsListPage | hazard/HazardsListPage.vue | 灾害点列表、多维度筛选、实时监测状态 |
| ConfirmationWorkbenchPage | hazard/ConfirmationWorkbenchPage.vue | AI 推理确认工作台：认领、确认、驳回、转派 |
| EventCenterPage | system/EventCenterPage.vue | 统一事件中心、事件状态机可视化、全生命周期追踪 |
| ConfirmationSignPage | hazard/ConfirmationSignPage.vue | 会签与转派：待我签认 / 我发起的 / 抄送我的 |
| FieldInspectionPage | hazard/FieldInspectionPage.vue | 现场巡检：任务领取、GPS 围栏核验、记录提交 |

### 功能概述与业务链路
这 5 个页面围绕 **灾害事件的发现 → 确认 → 处置 → 归档** 全生命周期：

```
灾害点列表(HazardsList)
    ↓ 发现疑似
AI 确认工作台(ConfirmationWorkbench) ←→ 会签与转派(ConfirmationSign)
    ↓ 确认生效
统一事件中心(EventCenter)
    ↓ 派发现场任务
现场巡检(FieldInspection)
```

### UI 结构相似性

| 页面 | 布局模式 | 核心组件 |
|------|----------|----------|
| HazardsListPage | 顶部筛选 + 列表卡片（网格布局） | 灾害点卡片（状态色条 + 关键信息 + 操作按钮） |
| ConfirmationWorkbenchPage | 左中右三栏：待确认列表 + 详情 + 操作面板 | 确认/驳回/转派按钮组、AI 推理证据展示 |
| EventCenterPage | 顶部筛选 + 事件列表 + 状态机时间线 | 事件卡片 + 状态流转可视化（SVG 时间轴） |
| ConfirmationSignPage | 顶部三 Tab + 列表 | 待我签认 / 我发起的 / 抄送我的，每条带签认操作 |
| FieldInspectionPage | 任务列表 + 详情 + 巡检记录表单 | GPS 围栏校验、照片上传、表单提交 |

**相似点**：
1. 均以「列表 + 详情」为核心交互模式
2. 状态驱动的视觉设计（色条、徽章、状态标签）贯穿所有页面
3. `ConfirmationWorkbenchPage` 和 `ConfirmationSignPage` 都涉及「签认/确认」操作

**差异点**：
1. `EventCenterPage` 是全局视角（所有事件的统一视图），其他页面是角色视角
2. `FieldInspectionPage` 偏向移动端/现场场景，交互模式不同

### 合并建议

| 合并方案 | 涉及页面 | 可行性 | 说明 |
|----------|----------|--------|------|
| **Tab 合并：确认工作台** | ConfirmationWorkbenchPage + ConfirmationSignPage | ⭐⭐⭐⭐⭐ 强烈推荐 | 两者都是「确认/签认」工作流，用户高度重叠。建议合并为「人工确认工作台」，顶部 Tab 切换「待确认 / 会签转派」，共用左侧列表和右侧详情面板。签认操作可复用同一组件。 |
| **抽屉嵌入：事件详情** | EventCenterPage → HazardsListPage | ⭐⭐⭐ 可考虑 | 在灾害点详情抽屉中增加「事件生命周期」Tab，展示该灾害点对应的事件流转。但 EventCenter 作为全局事件视图仍需保留独立页面（事件维度与灾害点维度不同）。 |
| **独立保留：现场巡检** | FieldInspectionPage | ⭐⭐ 不建议合并 | 面向现场巡检人员，使用场景、设备（移动端）、交互方式都不同，独立页面更合理。可通过消息/待办跳转关联。 |
| **关联优化：跳转链路** | 全部 5 页 | ⭐⭐⭐⭐ 推荐 | 增加跨页面的上下文跳转：从灾害点 → 确认工作台 → 事件中心 → 现场巡检，形成闭环导航，而非物理合并。 |

---

## 三、预警/处置相关（4 个）

### 页面清单
| 页面 | 路径 | 核心功能 |
|------|------|----------|
| WarningsPage | warning/WarningsPage.vue | 预警中心：预警列表、状态 Tab（活跃/归档）、预警详情与处置流程 |
| EmergencyPage | warning/EmergencyPage.vue | 应急事件：事件列表、处置步骤、AI 综合分关联 |
| ResourceDispatchPage | warning/ResourceDispatchPage.vue | 资源调度指挥：资源列表、调度指令、路径规划嵌入 |
| RoutePlanningPage | warning/RoutePlanningPage.vue | 路径规划：起终点、避障参数、多方案对比、SVG 地图 |

### 功能概述与业务链路

```
预警中心(Warnings) → 应急事件(Emergency) → 资源调度(ResourceDispatch) → 路径规划(RoutePlanning)
```

- `WarningsPage` 是预警的入口和全生命周期管理（发布→处置→解除）
- `EmergencyPage` 是升级后的应急事件，关联 AI 综合研判分
- `ResourceDispatchPage` 是应急处置中的资源调配操作
- `RoutePlanningPage` 是资源调度中的一个子功能（路径计算）

### UI 结构相似性

| 页面 | 布局模式 | 地图依赖 |
|------|----------|----------|
| WarningsPage | 左侧 Tab（活跃/归档）+ 右侧预警卡片列表 + 详情抽屉 | 无地图 |
| EmergencyPage | 顶部统计 + 事件卡片网格 + 详情侧栏 | 无地图 |
| ResourceDispatchPage | 左侧资源列表 + 右侧地图 + 底部调度面板 | 内嵌 OpenLayers 地图 |
| RoutePlanningPage | 顶部表单 + 左侧 SVG 地图 + 右侧方案对比卡片 | SVG 自绘地图（轻量） |

**相似点**：
1. 均涉及「列表 + 详情」结构
2. `ResourceDispatchPage` 和 `RoutePlanningPage` 都涉及地图和路径规划
3. `WarningsPage` 和 `EmergencyPage` 都有状态驱动的卡片列表

**差异点**：
1. `ResourceDispatchPage` 用 OpenLayers 真实地图，`RoutePlanningPage` 用 SVG 简化地图
2. `WarningsPage`/`EmergencyPage` 是信息管理类，后两者是工具操作类

### 合并建议

| 合并方案 | 涉及页面 | 可行性 | 说明 |
|----------|----------|--------|------|
| **子页面嵌入：路径规划** | RoutePlanningPage → ResourceDispatchPage | ⭐⭐⭐⭐⭐ 强烈推荐 | 路径规划是资源调度的核心子功能，当前 `ResourceDispatchPage` 已有 `routeTabActive` 状态和路径规划相关代码。建议将 `RoutePlanningPage` 的完整功能（多方案对比、避障设置、导出）作为 `ResourceDispatchPage` 的一个 Tab 或展开面板，无需独立成页。 |
| **Tab 合并：应急指挥中心** | WarningsPage + EmergencyPage + ResourceDispatchPage | ⭐⭐⭐ 可考虑 | 三者是预警→应急→调度的递进关系。可合并为「应急指挥中心」，左侧导航 Tab 切换「预警管理 / 应急事件 / 资源调度」，共用顶部态势概览。但需注意三个页面信息量都较大，合并后单页可能过重。 |
| **独立保留：预警中心** | WarningsPage | ⭐⭐⭐⭐ 建议保留独立 | 预警是高频使用模块，独立入口更直观，且功能完整（发布、处置、归档、统计），合并可能降低可用性。 |
| **抽屉嵌入：应急详情** | EmergencyPage → WarningsPage | ⭐⭐ 不推荐 | 预警和应急是不同层级的业务对象，虽有关联但语义不同，强制合并会造成概念混淆。 |

---

## 四、系统/工作台相关（3 个）

### 页面清单
| 页面 | 路径 | 核心功能 |
|------|------|----------|
| MessageCenterPage | system/MessageCenterPage.vue | 消息中心：类型筛选（预警/AI推送/任务/系统）、未读统计、标已读 |
| TodoCenterPage | system/TodoCenterPage.vue | 待办中心：4 类汇总卡 + 4 Tab（待确认/待审批/待发布预警/待跟踪） |
| ApprovalCenterPage | system/ApprovalCenterPage.vue | 审批中心：左侧筛选 + KPI + 饼图趋势 + 审批表格 + 右侧详情抽屉 |

### 功能概述
三者都是 **个人工作台** 的组成部分，面向当前登录用户的"我需要处理的事"：

- **消息中心**：被动接收的通知（所有类型消息的汇集）
- **待办中心**：主动需要处理的任务（确认、审批、发布、跟踪）
- **审批中心**：专门的行政审批流程（预警发布、预案启动、应急派单、设备控制）

### UI 结构相似性

| 页面 | 顶部 | 筛选 | 列表形式 | 详情 |
|------|------|------|----------|------|
| MessageCenterPage | 4 张统计卡 | 类型 Tab + 紧急度下拉 + 搜索 | 卡片列表（左图标 + 中内容 + 右操作） | 点击跳转至对应模块 |
| TodoCenterPage | 4 张汇总卡（可点击切换 Tab） | 每个 Tab 独立筛选（紧急度/时间/关键词） | 卡片网格（2 列），每个 Tab 卡片样式不同 | 点击跳转详情页 |
| ApprovalCenterPage | 左侧筛选栏 + 4 张 KPI + 饼图趋势图 | 左侧固定筛选（类别/紧急度/时间/状态） | 表格列表（7 列） | 右侧抽屉详情 + 审批决策弹窗 |

**相似点**：
1. 均有顶部统计/概览卡片
2. 均按类型/状态做筛选或 Tab 切换
3. 都是「我的 X」的个人视角

**差异点**：
1. `ApprovalCenterPage` 复杂度最高（左侧筛选 + 图表 + 表格 + 抽屉），是典型的工作台式布局
2. `TodoCenterPage` 是聚合视图，汇集了来自 4 个不同业务域的待办
3. `MessageCenterPage` 最轻量，以通知浏览为主

### 合并建议

| 合并方案 | 涉及页面 | 可行性 | 说明 |
|----------|----------|--------|------|
| **超级合并：个人工作台** | MessageCenterPage + TodoCenterPage + ApprovalCenterPage | ⭐⭐⭐⭐ 推荐 | 三者都是"我的工作台"的不同维度。建议合并为一个「个人工作台」页面，采用左侧 Tab 导航（消息 / 待办 / 审批），共用顶部用户信息栏。审批中心因其复杂度较高，作为主内容区的完整子页面呈现。 |
| **部分合并：待办 + 审批** | TodoCenterPage + ApprovalCenterPage | ⭐⭐⭐⭐⭐ 强烈推荐 | `TodoCenterPage` 的「待我审批」Tab 就是 `ApprovalCenterPage` 的简化入口。建议将审批中心作为待办中心的「审批」Tab 的完整视图，点击「查看全部」展开完整审批工作台（筛选 + 图表 + 表格 + 抽屉）。 |
| **抽屉嵌入：消息中心** | MessageCenterPage → 全局顶部栏 | ⭐⭐⭐ 可考虑 | 消息中心通常作为全局功能（类似钉钉/飞书的消息铃铛），可考虑将其从独立页面降级为顶部导航栏的下拉抽屉，仅保留「查看全部」跳转到完整列表页。 |

---

## 五、数据/设置相关（6 个）

### 页面清单
| 页面 | 路径 | 核心功能 |
|------|------|----------|
| DataSourcesPage | data/DataSourcesPage.vue | 数据源接入：启停、测试连接、认证配置 |
| DataSyncPage | data/DataSyncPage.vue | 数据同步监控：同步任务状态、延迟/失败监控、详情抽屉 |
| DataCatalogPage | data/DataCatalogPage.vue | 数据资产目录：KPI + 资源列表/使用排行双 Tab + 血缘 SVG |
| SettingsPage | settings/SettingsPage.vue | 系统设置：5 Tab（基本/偏好/预警通知/安全/数据接口） |
| AccountSecurityPage | settings/AccountSecurityPage.vue | 账号安全：4 Tab（2FA/登录历史/活跃会话/操作审计） |
| ProfilePage | settings/ProfilePage.vue | 个人中心：4 Tab（个人信息/密码修改/通知偏好/主题语言） |

### 功能概述
6 个页面分为两大块：

**数据管理块（3 个）**：数据接入 → 同步监控 → 资产目录，是数据中台的完整链路。

**设置/个人块（3 个）**：系统设置、账号安全、个人中心，三者存在功能重叠。

### UI 结构相似性

**数据管理三页：**
| 页面 | 顶部 | 主体 | 详情 |
|------|------|------|------|
| DataSourcesPage | 4 统计卡 + 分类 Tab + 搜索 | 表格列表（5 列） | 模态弹窗（认证配置） |
| DataSyncPage | 6 统计卡 + 搜索 + 状态筛选 | 表格列表（8 列） | 右侧抽屉（基本信息 + 告警记录） |
| DataCatalogPage | 4 KPI 卡 + 筛选条 + 双 Tab | 表格列表（Tab1 资源/Tab2 使用排行） | 右侧抽屉（标签/字段/血缘/元数据） |

**设置三页：**
| 页面 | 布局 | Tab 数量 |
|------|------|----------|
| SettingsPage | 左侧 Tab 导航 + 右侧设置表单 | 5 个（基本/偏好/预警/安全/数据接口） |
| AccountSecurityPage | 顶部账号信息卡 + Tab 导航 + Tab 内容 | 4 个（2FA/登录历史/活跃会话/操作审计） |
| ProfilePage | 顶部渐变头 + Tab 导航 + 子组件内容 | 4 个（信息/密码/通知/主题） |

**关键发现：功能重叠严重**
1. `SettingsPage` 的「个人偏好」Tab 与 `ProfilePage` 的「主题与语言」Tab **完全重叠**（主题、语言、默认首页、免打扰、分级通知）
2. `SettingsPage` 的「账号与安全」Tab（修改密码 + 登录设备）与 `AccountSecurityPage` **部分重叠**
3. `SettingsPage` 的「预警与通知」Tab 与 `ProfilePage` 的「通知偏好」Tab **高度重叠**
4. `SettingsPage` 的「数据与接口」Tab（服务状态 + 数据保留 + 备份）与数据管理三页 **概念上有重叠**

### 合并建议

| 合并方案 | 涉及页面 | 可行性 | 说明 |
|----------|----------|--------|------|
| **必须合并：个人设置** | SettingsPage(部分Tab) + ProfilePage + AccountSecurityPage | ⭐⭐⭐⭐⭐ 强烈推荐 | 三者功能高度重叠，用户困惑度高。建议统一为「个人中心」，结构如下：<br>• 顶部：个人信息卡片<br>• Tab1 基本资料：个人信息编辑<br>• Tab2 安全设置：密码修改 + 2FA + 登录历史 + 活跃会话 + 操作审计（即 AccountSecurityPage 全部内容）<br>• Tab3 偏好设置：主题/语言/默认首页/免打扰/通知分级（来自 Settings 的个人偏好 + 预警通知 + Profile 的主题语言）<br>• Tab4 退出登录<br>**当前 SettingsPage 中的「账号与安全」「个人偏好」「预警与通知」Tab 全部移入个人中心。** |
| **拆分重组：系统设置** | SettingsPage(剩余Tab) + 数据管理三页 | ⭐⭐⭐⭐ 推荐 | 将 `SettingsPage` 拆分为两部分：<br>• **个人设置**（见上条，并入个人中心）<br>• **系统设置**：保留「基本设置」和「数据与接口」Tab，面向管理员<br>数据管理三页（数据源/同步/目录）作为「数据管理」独立模块，与系统设置平级，不要放在设置页面内。 |
| **Tab 合并：数据管理工作台** | DataSourcesPage + DataSyncPage + DataCatalogPage | ⭐⭐⭐⭐ 推荐 | 三者是数据管理的完整链路，合并为「数据管理工作台」，顶部 Tab 切换「数据源 / 同步监控 / 资产目录」，共用全局筛选和统计概览。 |

---

## 六、地图相关（4 个）

### 页面清单
| 页面 | 路径 | 核心功能 |
|------|------|----------|
| RealTimeMapPage | monitor/RealTimeMapPage.vue | 实时监测地图：多底图、多图层、时间轴回放、空间工具、设备轨迹回放 Tab |
| DeviceMapPage | monitor/DeviceMapPage.vue | 设备地图：三栏布局（设备列表/地图/详情+轨迹回放） |
| SatelliteComparePage | monitor/SatelliteComparePage.vue | 时相对比：卷帘/闪烁/并排三种模式、差异热力图、热点标记 |
| WeatherPage | monitor/WeatherPage.vue | 气象分析：主地图叠加 + 6 个气象产品卡片 + 透明度控制 |

### 功能概述
4 个页面都以 **地图** 为核心交互载体，但面向不同的业务场景：

- **实时监测地图**：全局态势总览，所有图层叠加的"一张图"
- **设备地图**：专注设备管理和轨迹回放
- **时相对比**：卫星影像专业分析工具
- **气象分析**：气象数据专项工作台

### UI 结构相似性

| 页面 | 地图引擎 | 布局结构 | 工具栏位置 |
|------|----------|----------|------------|
| RealTimeMapPage | OpenLayers | 左侧图层面板 + 中央地图 + 底部时间轴 | 顶部（底图切换）+ 左侧（图层/工具） |
| DeviceMapPage | OpenLayers（直接引用） | 三栏：左设备列表 + 中地图 + 右详情/轨迹 | 顶部（刷新/回填）+ 底部（统计卡） |
| SatelliteComparePage | 无（Canvas 图片叠加） | 左主画布 + 右侧栏（统计/热点/建议） | 顶部（参数表单）+ 画布上方（模式切换） |
| WeatherPage | OpenLayers | 左主地图 + 右侧 6 产品卡片网格 | 顶部（刷新）+ 地图右侧（图例/透明度） |

**相似点**：
1. 3 个页面使用 OpenLayers 地图引擎（RealTimeMap / DeviceMap / Weather）
2. 都有图层/叠加层控制的概念
3. `RealTimeMapPage` 和 `DeviceMapPage` 都包含「设备轨迹回放」功能（功能重叠！）

**关键发现：轨迹回放功能重复**
- `RealTimeMapPage` 有一个 `设备轨迹回放` Tab，内含设备选择、时间范围、播放控制、进度条、GeoJSON 导出
- `DeviceMapPage` 有完整的三栏设备地图，右侧面板包含轨迹回放（时间范围、播放、速度、当前点信息）
- 两者功能高度重叠，但实现方式不同（前者用 `createOpenLayersController`，后者直接用 `ol` 懒加载）

### 合并建议

| 合并方案 | 涉及页面 | 可行性 | 说明 |
|----------|----------|--------|------|
| **必须合并：设备轨迹回放** | RealTimeMapPage(轨迹Tab) + DeviceMapPage | ⭐⭐⭐⭐⭐ 强烈推荐 | 功能严重重复。建议保留 `DeviceMapPage` 作为专业的设备地图与轨迹分析页面，删除 `RealTimeMapPage` 中的「设备轨迹回放」Tab，改为从实时地图点击设备后跳转到设备地图页面并自动选中该设备。 |
| **图层嵌入：气象叠加** | WeatherPage → RealTimeMapPage | ⭐⭐⭐⭐ 推荐 | `RealTimeMapPage` 已有 `weather` 图层开关和 `showWeatherOverlay` 方法，但功能较弱。建议将 `WeatherPage` 的气象产品选择能力（6 类产品、透明度、图例）作为「气象」图层面板嵌入到实时监测地图中，点击气象图层后展开详细产品选择。`WeatherPage` 作为独立的「气象数据中心」仍可保留（面向气象专业人员），但普通用户通过实时地图的气象图层即可满足需求。 |
| **独立保留：时相对比** | SatelliteComparePage | ⭐⭐ 不建议合并 | 是专业的卫星影像分析工具，交互模式独特（卷帘/闪烁/并排），用户群体是遥感分析人员，与实时监测地图的用户差异大。 |
| **统一地图底座** | 全部 4 页 | ⭐⭐⭐⭐ 推荐 | 目前 3 个 OpenLayers 页面实现方式不统一（有的用 `createOpenLayersController` 封装，有的直接 `import('ol')` 懒加载）。建议统一地图服务层，抽出通用的图层管理、底图切换、工具条组件，让各业务页面只关注自己的业务图层。这不是页面合并，而是架构优化。 |

---

## 总结合并优先级

### 第一梯队（强烈推荐，功能严重重叠）

| 优先级 | 合并方案 | 收益 |
|--------|----------|------|
| P0 | 个人设置三合一（Settings + Profile + AccountSecurity） | 消除 3 处功能重叠，用户不再困惑"改密码/改主题该去哪" |
| P0 | 设备轨迹回放合并（RealTimeMap 轨迹 Tab → DeviceMapPage） | 消除重复开发，统一轨迹体验 |
| P0 | 确认工作台合并（ConfirmationWorkbench + ConfirmationSign） | 合并同类项，减少页面跳转 |

### 第二梯队（推荐，业务上下游强关联）

| 优先级 | 合并方案 | 收益 |
|--------|----------|------|
| P1 | AI 工作台（AiTaskManage + AiResults） | 任务→结果的流畅工作流 |
| P1 | AI 分析中心（AiInsight + DisasterForecast + DamageAssessment） | 研判人员一站式分析 |
| P1 | 路径规划并入资源调度（RoutePlanning → ResourceDispatch） | 工具嵌入业务场景 |
| P1 | 待办 + 审批合并（TodoCenter + ApprovalCenter） | 审批是待办的子集，深化视图 |
| P1 | 数据管理工作台（DataSources + DataSync + DataCatalog） | 数据链路一站式管理 |

### 第三梯队（可考虑，优化体验）

| 优先级 | 合并方案 | 收益 |
|--------|----------|------|
| P2 | 个人工作台三合一（消息 + 待办 + 审批） | 统一个人入口 |
| P2 | 气象图层嵌入实时地图（Weather → RealTimeMap） | 普通用户无需跳转 |
| P2 | 应急指挥中心（Warnings + Emergency + ResourceDispatch） | 预警→应急→调度连贯 |

### 不建议合并

| 页面 | 原因 |
|------|------|
| ModelManagePage | 面向模型工程师，用户角色差异大 |
| FieldInspectionPage | 面向现场人员，移动端场景 |
| SatelliteComparePage | 专业遥感工具，交互模式独特 |
| EventCenterPage（独立保留） | 全局事件视图，维度不同 |

---

## 架构层建议

1. **统一地图组件库**：3 个 OpenLayers 页面实现方式不一致，建议封装统一的地图控制器和通用 UI 组件（图层面板、底图切换、工具条、图例）。

2. **统一列表/详情模式**：多数页面采用「列表 + 抽屉详情」模式，建议抽出通用的 `ListDetailLayout` 布局组件，减少重复代码。

3. **统一统计卡片组件**：几乎每个页面顶部都有统计卡片，样式各异，建议标准化。

4. **Tab 组件标准化**：有 Tab 切换的页面超过 10 个，但 Tab 样式、交互各不相同（有的在顶部、有的在左侧、有的胶囊、有的下划线），建议统一设计语言。
