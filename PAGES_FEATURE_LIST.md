# DisasterGuard 前端页面功能全景清单

**生成日期**：2026-10-24  
**前端版本**：v1.8.0  
**总计页面数**：65 个

---

## 1. 总览与态势监测 (dashboard/monitor)

### 1.1 综合态势大屏
| 页面名称 | 路由路径 | 权限要求 | 核心功能描述 | 关联的主要接口 |
|---------|---------|---------|-----------|--------------|
| OverviewPage | `/dashboard` | overview:view | **核心监测仪表板**<br>1. 地图可视化（2D/3D切换）：多底图（矢量/卫星）、多业务图层（灾害点/设备/预警/人员/轨迹）<br>2. 实时风险态势卡片：未闭环预警数、已确认预警数、消息通知送达率、联动部门数<br>3. 监测灾害点列表：按AI综合分降序排列，支持按风险等级筛选<br>4. 预警事件列表：分活跃/已解除两个Tab，支持查看状态、关闭原因、处置进度<br>5. 值守人员在线状态：显示在线/离线人员、所属队伍分布<br>6. 气象信息面板：实时天气、气象Overlay叠加（卫星云图/雷达/降水/降雪/干旱）<br>7. 搜索功能：支持经纬度/地址搜索定位<br>8. GPS定位功能：自动定位到用户位置，支持缓存与刷新<br>9. 多时相对比：基准时间段vs对比时间段的KPI对比（处置率/平均处置时长/设备在线率等）<br>10. Sentinel-1+2合成卫星：支持SAR+光学真彩合成叠加<br>11. 实时同步：WebSocket自动推送新发现的灾害、预警、设备变化 | `/api/dashboard/overview`、`/api/ai/comprehensive-analysis/all`、`/api/hazards/viewport`、`/api/devices/viewport`、`/api/warnings/viewport`、`/api/weather/realtime`、`/api/time-range-comparison` |
| RealTimeMapPage | `/dashboard/real-time-map` | map:view | **专项监测工作台**<br>1. 大尺寸交互地图：全屏渲染，多底图切换（矢量/卫星/地形）<br>2. 业务图层管理：灾害点、监测设备、预警事件、AI实时监测、气象Overlay、人员轨迹的显隐切换<br>3. 时间轴回放：24小时时间轴，支持播放/暂停、逐帧控制<br>4. 空间统计：当前视图内的灾害点数、设备数、预警数、AI监测数<br>5. 地图工具：缩放/拉框选择/距离测量/全屏/视图适配<br>6. 实时数据推送：WebSocket同步更新地图上的点位信息 | `/api/hazards/viewport`、`/api/devices/viewport`、`/api/warnings/viewport`、`/api/ai/monitor/hazards` |
| WeatherPage | `/dashboard/weather` | weather:view | **气象数据工作台**<br>1. 六类气象Overlay独立管理：卫星云图、全圆盘云图、雷达回波、24h降水、24h积雪、干旱监测<br>2. 每类Overlay支持：独立加载/移除、透明度调整、自动刷新<br>3. 大地图渲染：每个Overlay独占屏幕或并排对比<br>4. 数据源管理：支持后端代理与前端直连两种模式自动切换<br>5. 数据时间戳：显示每个Overlay的数据获取时间与更新频率 | `/api/weather/satellite`、`/api/weather/radar`、`/api/weather/precipitation24h`、`/api/weather/snow24h`、`/api/weather/drought`、`/api/weather/all-products` |
| ZoneDashboardPage | `/dashboard/zone/:kind?/:region?` | zone:view | **分区域态势看板**<br>1. 区域选择器：按行政区（省/市/县/乡）或灾害类型（滑坡/泥石流等）分类<br>2. 分区域KPI卡片：该区域的灾害点数、活跃预警数、设备在线率、应急响应时间<br>3. 区域内灾害点列表：按风险等级排序，支持快速跳转详情<br>4. 区域地图聚焦：地图自动聚焦到所选区域的包围盒 | `/api/dashboard/zone/:region`、`/api/hazards/by-region` |
| HistoryHazardsPage | `/dashboard/history-hazards` | history:view | **历史灾害库**<br>1. 已关闭灾害点的归档查询<br>2. 支持按关闭时间/关闭原因/灾害类型筛选<br>3. 展示灾害周期、影响范围、处置耗时等统计<br>4. 支持导出历史档案为Excel/PDF格式 | `/api/hazards/archived`、`/api/hazards/export` |
| SatelliteComparePage | `/dashboard/satellite-compare` | map:view | **多时相卫星影像对比**<br>1. 时间轴选择器：选择两个时间点的卫星影像<br>2. 并排对比/影像滑块对比两种模式<br>3. 差异识别：自动标注地表变化（植被变化/水体扩张/建筑变化）<br>4. 矢量点位叠加：同时显示灾害点、设备位置 | `/api/sentinel/history`、`/api/satellite/compare` |
| DeviceMapPage | `/dashboard/device-map` | devices:view | **设备分布地图**<br>1. 监测设备的地理位置分布可视化<br>2. 按设备类型/在线状态/信号强度/电量等条件过滤<br>3. 聚簇显示：高缩放级别时合并相近设备<br>4. 点击设备跳转设备详情页 | `/api/devices/by-location`、`/api/devices/stats` |

### 1.2 数据融合与分析
| 页面名称 | 路由路径 | 权限要求 | 核心功能描述 | 关联的主要接口 |
|---------|---------|---------|-----------|--------------|
| AnalyticsPage | `/dashboard/analytics` | analytics:view | **统计分析工作台**<br>1. 多维度数据透视表：灾害点数、预警数、设备数按时间/地域/类型聚合<br>2. 趋势图表：灾害点发现趋势、预警发布趋势、处置速度趋势（线图/柱图）<br>3. 同环比分析：按月/周对比历史数据<br>4. 人员绩效统计：各巡检员的巡检次数、响应速度、误报率排名<br>5. 支持自定义时间区间导出Excel/CSV | `/api/analytics/overview`、`/api/analytics/trends`、`/api/analytics/personnel-performance` |

---

## 2. 灾害点管理 (hazard)

| 页面名称 | 路由路径 | 权限要求 | 核心功能描述 | 关联的主要接口 |
|---------|---------|---------|-----------|--------------|
| HazardsListPage | `/dashboard/hazards` | hazards:view | **灾害点列表管理**<br>1. 灾害点列表表格：显示名称/类型/所属辖区/当前状态/风险等级<br>2. 多条件筛选：按风险等级（高风险/关注/正常）、来源（AI监测/人工新增）、关键词搜索<br>3. AI监测实时提示：显示AI自动发现的灾害点数与最新发现时间<br>4. 列表项快速操作：创建巡检任务、查看事件全貌、删除、导出<br>5. 分页显示：支持按 page/size 分页<br>6. 侧边栏详情面板：快速预览选中灾害点的关键信息（坐标/状态/AI评分）<br>7. 权限控制：仅维护员看不到"新增"操作 | `/api/hazards/list`、`/api/hazards/:id`、`/api/ai/comprehensive-analysis/all`、`/api/hazards/export` |
| HazardDetailPage | `/dashboard/hazards/:id` | hazards:view | **灾害点详细档案**<br>1. 完整灾害档案信息：基本属性/地理信息/监测设备/风险评估<br>2. 3D地形可视化：以该灾害点为中心的地形起伏（可选）<br>3. 关联设备面板：列出监测此灾害点的所有设备及其最新数据<br>4. 预警历史：与此灾害点相关的所有历史预警事件<br>5. 巡检记录：人员现场巡检的照片、视频、文字记录<br>6. AI评估报告：AI综合风险评分、灾害类型识别、演变趋势<br>7. 编辑档案：支持更新灾害点属性（限权限用户）<br>8. AI助手（可选）：右侧AI助手面板提供智能建议<br>9. 统一事件号关联：显示该灾害点的eventCode与事件状态 | `/api/hazards/:id`、`/api/devices/for-hazard/:id`、`/api/warnings/for-hazard`、`/api/inspections/for-hazard`、`/api/ai/assessment/:hazardCode` |
| ConfirmationWorkbenchPage | `/dashboard/confirmation-workbench` | confirmation:view | **人工确认工作台**<br>1. AI初检待确认列表：来自AI自动发现但未人工确认的灾害点<br>2. 待确认点的快速查看：地图定位、现场照片预览、AI评分展示<br>3. 批量确认操作：支持一键确认多个灾害点或拒绝<br>4. 拒绝理由收集：点击拒绝时收集专家意见反馈给AI模型<br>5. 确认后自动转入灾害列表 | `/api/hazards/ai-unconfirmed`、`/api/hazards/:id/confirm`、`/api/hazards/:id/reject` |
| TaskDetailPage | `/dashboard/ai-tasks/:id` | ai:task:view | **AI巡检任务详情**<br>1. 任务基本信息：任务名/触发方式/执行周期/运行状态<br>2. 任务执行历史：历次执行时间/成功失败/扫描覆盖范围<br>3. 任务结果预览：本次任务发现的灾害点数、新增点数、更新点数<br>4. 结果详情表格：列出任务发现的所有灾害点及其风险评分<br>5. 结果导出：支持导出任务成果为GeoJSON/Shapefile/Excel<br>6. 任务调整：支持临时修改周期/触发条件（限权限用户）<br>7. AI助手（可选）：提供任务优化建议 | `/api/ai/tasks/:id`、`/api/ai/tasks/:id/results`、`/api/ai/tasks/:id/hazards` |
| ConfirmationSignPage | `/dashboard/confirmation-sign` 或 `/dashboard/confirmation-sign/:id` | confirmation:view | **现场确认会签**<br>1. 灾害点现场勘查的电子会签记录<br>2. 支持添加到达时间、离开时间、现场照片、参与人员<br>3. 各相关部门依次签名确认<br>4. 支持生成会签报告PDF<br>5. 与统一事件流程集成：更新事件状态为"已确认" | `/api/confirmation-sign/new`、`/api/confirmation-sign/:id/add-signature` |
| FieldInspectionPage | `/dashboard/field-inspection` | inspection:view | **现场巡检管理**<br>1. 巡检任务下发：巡检员接收现场勘查任务<br>2. 巡检过程记录：支持拍照/录视频/语音备忘/地理打卡<br>3. 巡检报告生成：自动汇总现场数据生成勘查报告<br>4. GPS轨迹记录：记录巡检人员现场行动轨迹<br>5. 离线支持：网络不佳时本地缓存，恢复后自动同步<br>6. 现场风险评估：巡检过程中填写风险评分表单 | `/api/inspections/list`、`/api/inspections/:id/submit`、`/api/inspections/gps-track` |

---

## 3. 预警与应急处置 (warning/emergency)

| 页面名称 | 路由路径 | 权限要求 | 核心功能描述 | 关联的主要接口 |
|---------|---------|---------|-----------|--------------|
| WarningsPage | `/dashboard/warnings` | warnings:view | **预警中心**<br>1. 预警列表双Tab：活跃预警（待处置）和已解除归档预警<br>2. 预警卡片内容：预警等级/标题/描述/触发位置/触发时间/AI综合分<br>3. 预警排序：按AI综合分降序，空值落底<br>4. 批量操作：确认/派发/关闭/标记误报/自动解除<br>5. 关闭原因管理：预设原因模板与自由输入<br>6. KPI展示：未闭环预警数、已确认预警数、消息送达率、联动部门数<br>7. 处置进度可视化：进度条显示从触发→派发→处理→关闭全流程<br>8. 预警历史搜索：支持按时间段/等级/状态查询历史预警<br>9. 导出功能：支持生成预警处置报告 | `/api/warnings/list`、`/api/warnings/:id`、`/api/warnings/:id/confirm`、`/api/warnings/:id/dispatch`、`/api/warnings/:id/close`、`/api/warnings/export` |
| WarningDetailPage | `/dashboard/warnings/:id` | warnings:view | **预警详情页**<br>1. 完整预警信息：基本属性/触发条件/关联灾害点/关联设备<br>2. 预警演变时间轴：触发→确认→派发→处理各阶段的时间戳与操作人<br>3. 关联灾害点地图：显示预警涉及的所有灾害点的地理分布<br>4. 触发因子详解：风雨情/设备告警/AI推理等原因的具体数据<br>5. 处置流程记录：每个环节的操作人/操作时间/操作说明<br>6. 相关部门列表：显示已派发的部门及接收确认状态<br>7. AI诊断建议：AI根据现状给出的下一步建议<br>8. 预警关闭操作：支持直接关闭或跳转关闭弹窗<br>9. AI助手（可选）：提供处置决策支持 | `/api/warnings/:id`、`/api/warnings/:id/timeline`、`/api/warnings/:id/related-hazards`、`/api/ai/advisory` |
| WarningCreatePage | `/dashboard/warnings/create` | warnings:create | **手工预警发布**<br>1. 预警表单：预警等级/标题/描述/涉及灾害点/触发原因<br>2. 地图选点：支持在地图上点击选择预警涉及的灾害点<br>3. 派发对象选择：支持勾选多个部门或人员<br>4. 提前通知设置：选择通知时间和通知渠道（短信/APP/钉钉等）<br>5. 表单验证：必填字段检查、逻辑校验<br>6. 草稿保存：支持保存为草稿稍后发布<br>7. 发布确认：二次确认后提交<br>8. 发布成功提示：显示预警编号与派发对象列表 | `/api/warnings/create`、`/api/hazards/list`、`/api/users/for-dispatch` |
| WarningConfigPage | `/dashboard/warning-config` | warnings:view | **预警规则配置**<br>1. 预警规则列表：显示当前生效的所有预警触发规则<br>2. 规则详情查看：触发条件/处置流程/通知渠道/关联灾害点<br>3. 规则编辑：支持修改规则参数（阈值/触发周期等）<br>4. 规则启用禁用：快速开关规则生效状态<br>5. 规则测试：输入测试数据验证规则逻辑<br>6. 规则版本管理：显示规则修改历史与回滚<br>7. 权限控制：仅管理员可编辑规则 | `/api/warning-rules/list`、`/api/warning-rules/:id`、`/api/warning-rules/:id/update` |
| EmergencyPage | `/dashboard/emergency` | emergency:view | **应急响应指挥**<br>1. 应急事件列表：当前活跃应急事件的列表<br>2. 事件级别指示：按应急级别（I/II/III/IV）分类显示<br>3. 应急启动：支持一键启动应急响应流程（涉及多个预警/灾害时）<br>4. 应急队伍管理：显示已派出的应急队伍及其位置/状态<br>5. 物资调动：列出应急物资的需求与实际调配情况<br>6. 应急指令下发：支持向相关部门下达应急指令<br>7. 实时沟通：与应急队伍的实时通讯（接入IM系统）<br>8. 应急关闭：满足条件时支持关闭应急状态<br>9. 应急总结：生成应急事件的总结报告 | `/api/emergency/active-events`、`/api/emergency/start`、`/api/emergency/dispatch-team` |
| ResourceDispatchPage | `/dashboard/resource-dispatch` | resource:view | **资源调度管理**<br>1. 应急资源清单：各类应急物资、车辆、人员的库存统计<br>2. 资源需求表单：灾害点或应急事件提出资源需求<br>3. 资源调配过程：从提出需求→批准→派车→送达的全过程跟踪<br>4. 资源路由规划：自动计算从库房到目的地的最优路线<br>5. 实时跟踪：调度车辆的GPS定位与ETA预计<br>6. 多部门协调：支持跨部门资源借用与交接<br>7. 资源使用记录：记录使用时间、使用部门、消耗量<br>8. 库存预警：库存低于阈值时自动告警 | `/api/resources/inventory`、`/api/resources/demand/create`、`/api/resources/dispatch`、`/api/resources/dispatch/:id/track` |
| RoutePlanningPage | `/dashboard/route-planning` | resource:view | **路线规划**<br>1. 出车计划表：应急资源调度的车辆出车计划<br>2. 路线优化算法：支持指定目标点集自动规划最优访问顺序<br>3. 路线地图展示：在地图上绘制规划的路线与各目标点<br>4. 实时轨迹更新：与GPS设备同步，实时显示车辆当前位置<br>5. ETA计算：基于交通数据估计各站点达到时间<br>6. 路线调整：支持动态添加/移除目标点重新规划<br>7. 历史轨迹回放：支持查看已完成出车的历史路线 | `/api/routes/plan`、`/api/routes/optimize`、`/api/routes/:id/track` |
| WarningDeliveryPage | `/dashboard/warnings/:id/delivery` | warnings:view | **预警派发确认**<br>1. 派发对象详情：显示预警派发给的各部门/人员<br>2. 送达状态：各收信人的收到/已读/已确认状态<br>3. 发送渠道：显示通过短信/APP/钉钉等多渠道的发送结果<br>4. 重新派发：针对未收到的对象重新发送<br>5. 反馈收集：收集各部门的处置反馈<br>6. 送达率统计：展示总体送达率/已读率/确认率<br>7. 派发日志：详细的派发操作日志 | `/api/warnings/:id/delivery-status`、`/api/warnings/:id/resend` |
| WarningResolvePage | `/dashboard/warnings/:id/resolve` | warnings:view | **预警解除审批**<br>1. 解除申请表单：提出解除预警的理由与支持数据<br>2. 审批流程：显示多层级审批（现场确认→市级复核→省级审批）<br>3. 数据支持：显示支撑解除的风险评分、监测数据、专家意见<br>4. 工作联动停止：一旦审批通过则停止应急联动<br>5. 解除记录存档：记录完整的解除流程与参与人 | `/api/warnings/:id/resolve-apply`、`/api/warnings/:id/resolve-approve` |

---

## 4. AI智能分析 (ai)

| 页面名称 | 路由路径 | 权限要求 | 核心功能描述 | 关联的主要接口 |
|---------|---------|---------|-----------|--------------|
| AiTaskManagePage | `/dashboard/ai-tasks` | ai:task:view | **AI巡检任务管理**<br>1. 任务创建表单：支持三种触发方式（周期调度Cron/地震触发/暴雨触发/设备告警）<br>2. 预置触发模板：常见的调度周期（每小时/每天/每6小时/每周一）<br>3. 任务列表表格：显示任务名/触发方式/执行周期/当前状态/最近执行时间/负责人<br>4. 批量操作：启用/暂停/立即执行/编辑/删除<br>5. 任务状态指示：运行中/已暂停/已停止/执行失败各色彩显示<br>6. 任务筛选：按触发方式/状态/关键词搜索<br>7. 任务详情查看：点击任务跳转到该任务的执行历史与成果汇总<br>8. 权限控制：仅有ai:task:create/edit/delete权限的用户可修改 | `/api/ai/tasks/list`、`/api/ai/tasks/create`、`/api/ai/tasks/:id/update`、`/api/ai/tasks/:id/delete`、`/api/ai/tasks/:id/status/update` |
| AiResultsPage | `/dashboard/ai-results` | ai:result:view | **AI推理结果查询**<br>1. 推理任务结果列表：按任务/时间段查询AI最近的推理成果<br>2. 结果类型筛选：灾害检测/风险评估/预测成果等分类显示<br>3. 结果详情预览：包含推理时间/模型版本/输入数据/输出结果<br>4. 结果精度统计：正确率/误报率/漏报率等指标<br>5. 对比分析：支持选择两个时间段的推理结果进行对比<br>6. 批量反馈：对推理结果进行正确/错误标记，用于模型改进<br>7. 结果导出：支持导出为JSON/CSV格式<br>8. 结果溯源：显示该结果依赖的输入数据与模型版本 | `/api/ai/results/list`、`/api/ai/results/:id`、`/api/ai/results/feedback` |
| AiInsightPage | `/dashboard/ai-insight` | ai:insight:view | **AI综合研判**<br>1. 灾害点选择：下拉列表或地图点击选择待评估灾害点<br>2. 多模态融合：综合地形/降雨/设备数据/卫星影像给出风险评估<br>3. AI评估报告：包含风险等级(CRITICAL/HIGH/MEDIUM/LOW)、灾害类型识别、演变趋势曲线<br>4. 三维度明细：地理因素评分/气象因素评分/设备告警评分三个子维度<br>5. 发布预警按钮：基于AI评估结果一键发布人工预警<br>6. 评估历史：显示该灾害点的历次评估结果对比<br>7. 评估刷新：支持重新触发评估（等待后端ai-service新推理）<br>8. 多模态融合可视化：地图显示支撑评估的各类信息源位置 | `/api/ai/disaster-assessment`、`/api/ai/comprehensive-analysis`、`/api/warnings/create`、`/api/ai/insight/refresh` |
| ModelManagePage | `/dashboard/ai-models` | ai:model:view | **AI模型管理**<br>1. 已部署模型列表：YOLOv8（目标检测）/LSTM（形变预测）/XGBoost（风险评分）/融合模型等<br>2. 模型版本管理：显示每个模型的版本号/发布时间/精度指标/部署状态<br>3. 模型性能监控：实时调用量/平均延迟/错误率/QPS<br>4. 模型灰度发布：支持新版本灰度上线（先10%→50%→100%）<br>5. 模型回滚：异常情况下快速回滚到上个版本<br>6. 模型参数调整：支持调整模型推理参数（置信度阈值等）<br>7. 模型黑白名单：可以禁用某些模型或指定场景专用模型<br>8. 模型输入输出Trace：记录每次调用的输入数据与输出结果用于审计 | `/api/ai/models/list`、`/api/ai/models/:id/version`、`/api/ai/models/:id/deploy`、`/api/ai/models/:id/rollback` |
| RuleEnginePage | `/dashboard/rule-engine` | settings:view | **预警规则与阈值配置**<br>1. 规则管理：支持新增/修改/删除预警触发规则<br>2. 可视化规则编辑：图形化界面配置规则条件（IF-THEN结构）<br>3. 条件组合：支持AND/OR逻辑的复杂条件组合<br>4. 参数配置：每条规则的关键参数（降雨阈值/位移阈值等）<br>5. 规则优先级：支持设置规则触发优先级（高优先级率先评估）<br>6. 规则测试平台：输入测试数据验证规则逻辑<br>7. 规则版本控制：保存规则修改历史，支持版本回滚<br>8. 规则说明文档：每条规则配置相应的说明与操作指南 | `/api/rules/list`、`/api/rules/create`、`/api/rules/:id/update`、`/api/rules/:id/test` |
| DisasterForecastPage | `/dashboard/disaster-forecast` | ai:forecast:view | **灾害预测**<br>1. 设备选择：下拉列表选择监测设备或灾害点<br>2. 预测指标选择：地表位移/位移速率/降雨量/裂缝宽度/累计位移等<br>3. 预测周期选择：6/12/24/48/72小时等多个时间尺度<br>4. 预测曲线展示：显示未来N小时的预测趋势曲线<br>5. 置信区间：显示预测的不确定性带状区间<br>6. 预测精度指标：给出该预测的置信度评分<br>7. 多设备对比：支持同时显示多个设备的预测曲线进行对比<br>8. 预测成果导出：支持导出预测数据与曲线图 | `/api/ai/forecast/auto`、`/api/ai/forecast/compare` |
| DamageAssessmentPage | `/dashboard/damage-assessment` | ai:forecast:view | **灾损评估**<br>1. 灾害事件选择：选择待评估的灾害事件<br>2. 灾损类型：建筑物损毁/农作物受损/道路中断/人员伤亡等多类评估<br>3. 卫星影像对比：事前/事后卫星影像的并排对比<br>4. AI自动评估：基于遥感影像的自动灾损面积计算<br>5. 人工调整：支持专家手动调整AI自动计算的灾损数据<br>6. 灾损等级判定：根据灾损面积与程度判定为特别重大/重大/较大/一般<br>7. 灾损报告生成：自动汇总形成灾损评估报告<br>8. 补偿对接：灾损报告对接政府补偿系统 | `/api/ai/damage-assessment`、`/api/sentinel/before-after` |
| SocialMediaPage | `/dashboard/social-media` | ai:insight:view | **社交媒体舆情监测**<br>1. 舆情监测：实时监测微博/微信/抖音/贴吧等社交平台的相关信息<br>2. 关键词配置：支持自定义监测关键词（灾害点名称/行政区名称）<br>3. 舆情聚合：自动将相同事件的多条信息聚合显示<br>4. 情感分析：对舆情内容进行情感分类（正面/中立/负面）<br>5. 传播热度指数：显示话题热度趋势曲线<br>6. 舆情预警：负面舆情达到阈值时自动告警<br>7. 舆情管理：支持标记为已处理或忽略<br>8. 舆情追踪：支持导出舆情列表与传播分析报告 | `/api/ai/social-media/monitor`、`/api/ai/social-media/sentiment`、`/api/ai/social-media/trends` |

---

## 5. 设备与数据治理 (device/data)

### 5.1 设备管理
| 页面名称 | 路由路径 | 权限要求 | 核心功能描述 | 关联的主要接口 |
|---------|---------|---------|-----------|--------------|
| DevicesPage | `/dashboard/devices` | devices:view | **监测设备列表**<br>1. 设备列表表格：设备编码/名称/类型/位置/在线状态/电量/信号强度/最后更新时间<br>2. 在线状态统计：在线设备数/离线设备数/离线时长预警<br>3. AI健康度评分：AI标注各设备的健康等级（HEALTHY/ATTENTION/WARN/CRITICAL）<br>4. 设备筛选：按类型/在线状态/健康度/区域等条件<br>5. 快速操作：设备详情/诊断/删除/OTA升级<br>6. 新增设备表单：支持单个新增或批量导入Excel<br>7. 排序：按AI健康度降序排列，CRITICAL优先<br>8. 分页显示<br>9. 权限控制：维护员看不到删除选项 | `/api/devices/list`、`/api/devices/create`、`/api/devices/delete`、`/api/ai/device-health/all` |
| DeviceDetailPage | `/dashboard/devices/:id` | devices:view | **设备详细信息**<br>1. 设备属性：编码/名称/类型/制造商/序列号/IP地址等<br>2. 实时数据面板：当前采集的各传感器数据实时显示<br>3. 数据时间序列图：显示最近24小时的采集数据曲线<br>4. 告警历史：该设备产生过的所有告警记录<br>5. 故障诊断：支持一键诊断设备状态，返回诊断结果与建议<br>6. 关联灾害点：显示该设备正在监测的灾害点列表<br>7. 维保记录：设备维护保养的历史记录<br>8. OTA升级管理：支持查看固件版本、预览升级包、确认升级<br>9. 远程控制：支持重启/重置等远程操作（限维护员）<br>10. 编辑设备属性：修改设备名称/安装位置等 | `/api/devices/:id`、`/api/devices/:id/data`、`/api/devices/:id/diagnose`、`/api/devices/:id/alarms`、`/api/devices/:id/upgrade` |
| OtaManagePage | `/dashboard/devices/ota` | devices:ota | **设备固件升级管理**<br>1. 固件版本列表：已发布和待发布的固件版本<br>2. 升级包详情：版本号/发布时间/更新说明/文件大小/升级耗时<br>3. 升级计划配置：选择待升级设备、升级时间窗口、回滚策略<br>4. 灰度升级：支持分批次升级（先10%→50%→100%）<br>5. 升级进度监控：实时显示各设备的升级状态<br>6. 升级失败处理：自动回滚或手动干预选项<br>7. 升级成功率统计：显示升级成功/失败/跳过的设备数<br>8. 升级日志：详细的升级操作日志可导出 | `/api/ota/versions`、`/api/ota/plan/create`、`/api/ota/plan/:id/execute`、`/api/ota/upgrade/:id/status` |

### 5.2 数据治理
| 页面名称 | 路由路径 | 权限要求 | 核心功能描述 | 关联的主要接口 |
|---------|---------|---------|-----------|--------------|
| DataSourcesPage | `/dashboard/data-sources` | datasource:view | **数据源管理**<br>1. 数据源列表：已接入的气象/地震/遥感/社交等各类数据源<br>2. 数据源详情：接入协议/更新频率/数据覆盖范围/订阅状态<br>3. 新增数据源表单：支持配置第三方数据源接入参数<br>4. 数据源启用禁用：快速开关数据源的启用状态<br>5. 数据同步管理：显示最近一次同步时间/同步结果/数据条数<br>6. 数据质量评分：显示该数据源的数据完整性/及时性评分<br>7. 异常告警：数据源异常断开/更新延迟时自动告警<br>8. 成本管理：部分收费数据源显示当月消费额与配额 | `/api/data-sources/list`、`/api/data-sources/create`、`/api/data-sources/:id/sync` |
| DataSyncPage | `/dashboard/data-sync` | sync:view | **数据同步任务管理**<br>1. 同步任务列表：定期同步的各类数据源任务<br>2. 任务调度配置：周期/延迟策略/同步对象等<br>3. 任务执行历史：最近50次执行记录，显示时间/状态/行数/耗时<br>4. 同步失败处理：失败原因分析与重试选项<br>5. 同步日志查看：详细的同步操作日志<br>6. 手动同步触发：支持按需手动触发某个数据源的同步<br>7. 同步速率限制：防止同步任务对系统造成压力<br>8. 增量同步配置：支持全量/增量同步策略选择 | `/api/sync-tasks/list`、`/api/sync-tasks/:id/execute`、`/api/sync-tasks/:id/history` |
| DataCatalogPage | `/dashboard/data-catalog` | data:catalog:view | **数据资产目录**<br>1. 数据资产分类：按业务模块（灾害/预警/设备/气象）分类浏览<br>2. 数据集详情：数据集名/字段/数据类型/更新频率/存储位置/访问权限<br>3. 数据质量评分：数据完整性/准确性/一致性/及时性评分<br>4. 数据查询示例：支持在线预览数据集样本记录<br>5. 数据血缘追踪：显示数据的来源→转换→使用全链路<br>6. 数据使用统计：该数据集被哪些应用/报表引用<br>7. 元数据管理：支持编辑数据集描述与业务含义<br>8. 权限管理：显示谁有权访问该数据集<br>9. 导出功能：支持导出数据集定义为数据字典 | `/api/data-catalog/list`、`/api/data-catalog/:id/sample` |
| DataManagePage | `/dashboard/data-manage` | data:manage:view | **数据管理与治理**<br>1. 数据质量监控：各数据源的实时质量指标<br>2. 异常数据处理：显示检测到的异常数据并提供处理选项<br>3. 重复数据清洗：自动识别与清理重复数据<br>4. 缺失值处理：支持多种缺失值填充策略（删除/均值/插值等）<br>5. 数据验证规则：支持自定义数据验证规则<br>6. 批量修正：支持批量修正已识别的错误数据<br>7. 数据备份管理：显示数据备份计划与备份历史<br>8. 数据归档策略：配置历史数据的归档时间与存储位置<br>9. 操作日志：所有数据修改操作的完整日志<br>10. GDPR合规：支持数据脱敏与遗忘权处理 | `/api/data-manage/quality`、`/api/data-manage/anomalies`、`/api/data-manage/repair` |

---

## 6. 系统管理 (system)

| 页面名称 | 路由路径 | 权限要求 | 核心功能描述 | 关联的主要接口 |
|---------|---------|---------|-----------|--------------|
| UserManagementPage | `/dashboard/user-management` | users:view | **用户与权限管理**<br>1. 用户列表：显示用户名/邮箱/角色/部门/创建时间/最后登录时间<br>2. 用户搜索与筛选：按用户名/邮箱/角色/部门查询<br>3. 用户编辑表单：修改用户信息、重置密码、禁用启用<br>4. 批量导入：支持从Excel批量导入新用户<br>5. 密码重置：管理员可以重置用户密码并通知用户<br>6. 权限矩阵：7个角色×30+个模块的权限可视化矩阵<br>7. 角色权限配置：支持修改角色拥有的权限码列表<br>8. 用户角色分配：支持给用户分配单个或多个角色<br>9. 操作日志：记录所有用户管理操作 | `/api/users/list`、`/api/users/create`、`/api/users/:id/update`、`/api/roles/permission-matrix` |
| AuditLogPage | `/dashboard/audit-log` | audit:view | **系统审计日志**<br>1. 审计日志查询：显示所有用户操作的完整日志<br>2. 操作类型筛选：创建/修改/删除/查询/导出/登录等各类操作<br>3. 操作人过滤：按用户名查询该用户的所有操作<br>4. 时间范围查询：支持自定义时间段查询<br>5. 操作详情：显示操作前后的数据变更（Data Before/After）<br>6. IP地址与位置：显示操作发生的IP与地理位置<br>7. 日志导出：支持导出审计日志为Excel/CSV<br>8. 日志保留政策：显示日志保留期限与归档策略<br>9. 日志完整性校验：支持验证日志是否被篡改（完整性证明）<br>10. 敏感操作告警：删除/权限变更等敏感操作实时通知管理员 | `/api/audit-log/list`、`/api/audit-log/export` |
| SystemMonitorPage | `/dashboard/system-monitor` | monitor:view | **系统监控**<br>1. 服务健康检查：显示后端各微服务的健康状态（UP/DOWN/WARN）<br>2. API调用统计：24小时API调用量/成功率/平均响应时间<br>3. 数据库连接池：显示数据库连接数/活跃连接/等待队列长度<br>4. 消息队列监控：Kafka/RabbitMQ的队列长度/消费延迟<br>5. 缓存命中率：Redis缓存的命中率/淘汰策略<br>6. 存储容量：PostgreSQL/MongoDB的存储占用与增长趋势<br>7. CPU/内存使用率：实时显示主机资源使用情况<br>8. GPU监控：AI推理GPU的使用率/温度/功率（如有）<br>9. 告警面板：系统告警列表与处理状态<br>10. 30秒自动刷新：监控数据每30秒自动更新 | `/api/system/monitor`、`/api/system/services/health` |
| MessageCenterPage | `/dashboard/message-center` | messages:view | **消息中心**<br>1. 消息列表：系统通知/工作提醒/告警消息的统一收件箱<br>2. 消息分类：预警/任务/审批/告警等多种消息类型<br>3. 消息搜索与筛选：按类型/发件人/时间等条件查询<br>4. 消息详情：展示完整的消息内容与关联资源链接<br>5. 消息标记：支持标记为已读/重要/忽略<br>6. 消息导出：支持导出消息为PDF/Excel<br>7. 批量操作：批量标记已读、批量删除等<br>8. 通知渠道配置：支持配置APP/短信/邮箱/钉钉等接收渠道<br>9. 消息订阅管理：选择要订阅的消息类型与频率<br>10. 已读状态同步：跨设备消息状态同步 | `/api/messages/list`、`/api/messages/:id/read`、`/api/messages/channels/config` |
| TodoCenterPage | `/dashboard/todo` | todo:view | **待办中心**<br>1. 个人待办任务列表：我需要完成的任务/表单/审批<br>2. 任务状态分类：进行中/已完成/逾期/已取消<br>3. 任务优先级：高/中/低三个优先级视觉区分<br>4. 截止时间提醒：将截止的任务标红显示<br>5. 任务详情：任务标题/描述/截止日期/任务来源<br>6. 任务接受/拒绝：支持接受新分配的任务或拒绝<br>7. 任务评论：任务处理过程中的沟通记录<br>8. 任务完成报告：标记完成时需填写完成说明<br>9. 任务委托：支持将任务委托给其他人<br>10. 数据统计：今日/本周/本月的任务完成进度 | `/api/todos/list`、`/api/todos/:id/accept`、`/api/todos/:id/complete` |
| ApprovalCenterPage | `/dashboard/approvals` | approval:view | **审批中心**<br>1. 待我审批的列表：显示所有需要我批准的工单<br>2. 工单类型：用户注册/数据导出/系统配置修改/应急启动等<br>3. 工单详情：申请人/申请时间/申请原因/涉及资源/当前状态<br>4. 在线审批：支持直接在页面上审批（通过/拒绝）并输入审批意见<br>5. 批量审批：支持批量通过多个工单<br>6. 审批流程：显示当前工单所在的审批环节（1/3、2/3等）<br>7. 抄送人：显示工单已抄送给哪些人<br>8. 回退功能：已审批的工单支持申请人回退修改后重新提交<br>9. 审批历史：显示该工单的完整审批链路与各环节耗时<br>10. 代理审批：支持设置审批权限代理 | `/api/approvals/pending`、`/api/approvals/:id/approve` |
| EventCenterPage | `/dashboard/events` 或 `/dashboard/events/:code` | events:view | **统一事件中心**<br>1. 事件列表：所有灾害/预警/应急事件的统一管理<br>2. 事件分类展示：按事件类型（灾害确认/预警发布/应急启动）分类<br>3. 事件详情展示：事件编号/标题/状态/关联灾害点/关联预警/处理部门<br>4. 事件血缘关系：显示事件的来源灾害点→触发预警→启动应急的完整链路<br>5. 事件状态机：DETECTED→CONFIRMING→CONFIRMED→WARNING_ISSUED→EMERGENCY_ACTIVE→CLOSED/REJECTED<br>6. 事件时间轴：事件生命周期内的所有重要时间节点<br>7. 事件关联资源：列出与该事件相关的所有灾害点/预警/资源调度单<br>8. 事件搜索：按事件号/标题/类型/地点等条件查询<br>9. 事件导出：支持导出事件档案为PDF/Word<br>10. 事件标签管理：支持为事件添加自定义标签分类 | `/api/events/list`、`/api/events/:code`、`/api/events/lineage` |
| NoticeTemplatePage | `/dashboard/notice-templates` | notice:view | **通知模板管理**<br>1. 模板列表：预定义的通知/告警/提醒文本模板<br>2. 模板编辑：支持修改模板文本与占位符<br>3. 变量列表：显示模板中可用的变量（如灾害点名/风险等级/联系人等）<br>4. 模板预览：输入模板变量后预览最终的通知文本<br>5. 多语言支持：模板支持中文/英文等多语言<br>6. 模板分类：按通知类型（预警/任务/审批）分类存储<br>7. 模板版本管理：支持查看模板修改历史与版本回滚<br>8. 模板使用统计：显示该模板被使用的次数与相关通知<br>9. 批量导入：支持从Excel批量导入模板<br>10. 权限控制：仅管理员可修改模板 | `/api/notice-templates/list`、`/api/notice-templates/create` |
| NotificationChannelsPage | `/dashboard/notification-channels` | notice:view | **通知渠道配置**<br>1. 已配置渠道列表：短信/APP推送/钉钉/企业微信/邮箱/电话/传真等<br>2. 渠道连接状态：显示各渠道的连接状态与最后测试时间<br>3. 渠道参数配置：访问密钥/速率限制/签名方式等参数编辑<br>4. 发送成功率：显示各渠道的24小时发送成功率<br>5. 成本管理：部分收费渠道（短信/电话）显示消费额与余额<br>6. 渠道测试：支持发送测试消息验证渠道可用性<br>7. 渠道优先级：配置多个渠道时的发送优先级<br>8. 发送日志：显示通过各渠道的发送记录与失败原因<br>9. 告警规则：配置在渠道故障时如何处理<br>10. 操作日志：渠道配置修改的完整操作记录 | `/api/notification-channels/list`、`/api/notification-channels/:id/test` |
| PlanManagePage | `/dashboard/plans` | plans:view | **应急预案管理**<br>1. 预案列表：各类应急预案（洪涝/滑坡/泥石流等）的管理<br>2. 预案版本管理：支持多版本预案并标记当前生效版本<br>3. 预案详情：预案编号/预案级别/发布机关/有效期等基本信息<br>4. 预案内容查看：支持在线查看预案全文（可能是PDF嵌入）<br>5. 预案下载：支持下载预案为PDF格式离线查阅<br>6. 预案更新：支持上传新版本预案<br>7. 预案启用禁用：快速开关预案的生效状态<br>8. 相关流程：显示该预案触发时的应急响应流程<br>9. 关键数据字段：预案涉及的关键资源/部门/联系人汇总<br>10. 预案演练管理：记录基于该预案的应急演练历史 | `/api/plans/list`、`/api/plans/:id`、`/api/plans/:id/download` |
| ReportCenterPage | `/dashboard/reports` | reports:view | **报表中心**<br>1. 预定义报表列表：日报/周报/月报/年报等定期报表<br>2. 报表下载：支持导出报表为PDF/Excel/Word格式<br>3. 报表自定义：支持选择关键指标生成定制报表<br>4. 报表计划：配置定期自动生成报表并发送给相关人员<br>5. 报表分享：支持生成报表分享链接给第三方<br>6. 报表审批：部分报表需要管理员审批后才能发布<br>7. 报表归档：已发布报表的版本管理与查阅<br>8. 报表数据源：显示报表引用的数据集<br>9. 报表权限：控制谁有权查看各类报表<br>10. 报表性能：大数据报表的生成进度与超时处理 | `/api/reports/list`、`/api/reports/:id/generate`、`/api/reports/:id/download` |
| BackupRecoveryPage | `/dashboard/backup-recovery` | backup:view | **备份与恢复管理**<br>1. 备份策略配置：设置每日备份时间与备份周期<br>2. 备份列表：显示所有备份文件的时间戳/大小/状态/备份类型<br>3. 手动备份触发：支持立即手动触发全库备份<br>4. 备份验证：显示备份文件的完整性校验结果<br>5. 备份去重：支持增量备份减少存储空间<br>6. 备份压缩：支持配置备份文件的压缩算法与比率<br>7. 备份到云端：支持配置备份文件上传到云存储（阿里云/AWS等）<br>8. 恢复预演：支持预演恢复过程而不实际执行<br>9. 恢复操作：支持从指定备份点进行全量恢复或指定库恢复<br>10. 恢复进度：显示恢复过程的实时进度<br>11. 恢复验证：恢复完成后自动验证数据完整性 | `/api/backup/list`、`/api/backup/execute`、`/api/backup/:id/recover` |
| PersonnelManagePage | `/dashboard/personnel` | personnel:view | **人员管理**<br>1. 值守人员列表：在线/离线值守员的信息<br>2. 人员编制管理：支持新增/编辑/删除人员<br>3. 人员队伍分组：按应急队伍/岗位/地点分组管理<br>4. 在线状态实时显示：GPS定位显示人员当前位置<br>5. 人员轨迹回放：支持查看人员的历史活动轨迹<br>6. 人员能力标签：标注人员的专业能力（驾驶/抢险/医疗等）<br>7. 人员联系方式：电话/邮箱/紧急联系人管理<br>8. 值班表管理：配置人员的值班日历与轮班制度<br>9. 人员绩效：统计个人的任务完成数/响应速度等绩效指标<br>10. 人员导出：支持导出人员名单与联系方式 | `/api/personnel/list`、`/api/personnel/:id/location` |
| WorkbenchPage | `/dashboard/workbench` | system:view | **我的工作台（个人首页）**<br>1. 个性化仪表板：根据角色与权限定制展示的信息<br>2. 快捷菜单：常用功能的快速访问<br>3. 我的待办：个人待办任务的摘要展示<br>4. 我的告警：与我相关的告警提醒<br>5. 最近访问：最近浏览的灾害点/预警/设备等资源<br>6. 工作统计：个人的周/月工作统计<br>7. 消息未读提醒：未读消息数与快速导航<br>8. 日程日历：工作日程与事务提醒<br>9. 快捷操作按钮：新增灾害/发布预警/启动应急等常用操作<br>10. 小工具：计算器/备忘录/便签等辅助工具（可选） | `/api/workbench/summary`、`/api/todos/my-list` |
| HelpCenterPage | `/dashboard/help` | settings:view | **帮助中心**<br>1. 在线文档库：用户手册/操作指南/常见问题等文档<br>2. 文档搜索：全文搜索帮助文档<br>3. 文档分类：按功能模块/用户角色/常见问题等分类浏览<br>4. 视频教程：操作演示视频的链接（可能指向YouTube/优酷等）<br>5. 反馈渠道：支持用户提交使用问题/改进建议<br>6. 反馈管理：管理员可查看并回复用户反馈<br>7. FAQ热榜：显示最热门的常见问题<br>8. 知识库排行：用户访问最多的帮助文档<br>9. 版本更新说明：最近版本的功能更新说明<br>10. 技术支持：显示技术支持团队的联系方式 | `/api/help/docs`、`/api/help/feedback/create` |
| AdminApplicationsPage | `/admin/applications` | users:approve | **新用户注册申请审核**<br>1. 注册申请列表：待审核的新用户注册申请<br>2. 申请者信息：姓名/邮箱/单位/申请理由等<br>3. 申请详情：显示申请者填写的所有信息与上传的身份证明<br>4. 审核操作：通过/拒绝申请并输入审核意见<br>5. 审核记录：显示审核人/审核时间/审核结果<br>6. 批量操作：支持批量审核多个申请<br>7. 回复申请者：支持给申请者发送审核结果通知<br>8. 申请统计：显示待审核/已通过/已拒绝申请的数量统计<br>9. 申请导出：支持导出申请列表<br>10. 自定义审核流程：支持配置多层级审核流程 | `/api/applications/pending`、`/api/applications/:id/approve` |

---

## 7. 用户设置 (settings)

| 页面名称 | 路由路径 | 权限要求 | 核心功能描述 | 关联的主要接口 |
|---------|---------|---------|-----------|--------------|
| SettingsPage | `/dashboard/settings` | settings:view | **系统设置**<br>1. 全局参数配置：AI推理阈值/通知频率/数据保留期等<br>2. 地域配置：监管范围的地理坐标/行政区划<br>3. 业务参数：灾害分类/预警等级/应急级别等<br>4. 界面主题：暗黑/浅色/自动切换<br>5. 语言选择：中文/English等多语言支持<br>6. 时区设置：显示与修改当前时区<br>7. 隐私政策：显示平台的隐私政策与用户协议<br>8. 关于本站：显示系统版本/发布时间/技术栈等 | `/api/settings/global`、`/api/settings/save` |
| ProfilePage | `/dashboard/profile` | settings:view | **个人资料**<br>1. 个人基本信息：姓名/邮箱/电话/部门/职位等<br>2. 头像上传：支持修改个人头像<br>3. 签名编辑：个人签名与简介编辑<br>4. 工作信息：工作单位/部门/职位/技能等<br>5. 紧急联系人：绑定紧急联系人信息<br>6. 账号绑定：第三方账号绑定（钉钉/企业微信等）<br>7. 隐私设置：配置个人信息的可见性<br>8. 账号注销：支持注销账号（需二次确认）<br>9. 数据导出：支持导出个人所有数据为压缩包<br>10. 修改确认：保存修改时需要输入密码确认 | `/api/users/profile`、`/api/users/profile/update`、`/api/users/avatar/upload` |
| AccountSecurityPage | `/dashboard/account-security` | settings:view | **账号安全**<br>1. 登录密码修改：修改账号登录密码<br>2. 登录历史：显示最近的登录记录（时间/IP/地点/设备）<br>3. 异常登录告警：检测到异常登录时自动告警<br>4. 双因素认证（2FA）：支持绑定手机/邮箱实现二次验证<br>5. 登出其他设备：支持强制登出其他设备上的登录会话<br>6. IP白名单：配置允许登录的IP地址范围<br>7. 设备管理：显示已授权的登录设备与管理会话<br>8. 操作审计：账号相关的敏感操作记录（密码修改/权限变更）<br>9. 风险评分：根据行为特征给账号安全评分<br>10. 安全建议：根据评分给出安全加固建议 | `/api/users/security/password/change`、`/api/users/security/login-history` |

---

## 8. 公开页面与错误页

| 页面名称 | 路由路径 | 权限要求 | 核心功能描述 | 关联的主要接口 |
|---------|---------|---------|-----------|--------------|
| WelcomePage | `/` 或 `/welcome` | public | **平台欢迎页/登录页**<br>1. 品牌展示：Logo、品牌Slogan、产品概述<br>2. 功能亮点展示：多行业支持/智能预警/应急指挥等核心功能<br>3. 成功案例：平台服务过的地区/应急事件统计<br>4. 客户反馈：已使用客户的用户体验评价<br>5. 登录表单：用户名密码登录、第三方登录（钉钉/企业微信等）<br>6. 注册链接：新用户注册入口<br>7. 忘记密码链接：密码重置流程入口<br>8. 试用申请：非注册用户可申请试用账号<br>9. 常见问题FAQ：快速回答用户常见问题<br>10. 视差滚动动画：网站首屏的视差滚动效果 | `/api/auth/login`、`/api/auth/social-login` |
| RegisterPage | `/register` | public | **用户注册申请**<br>1. 注册表单：邮箱/用户名/密码/确认密码/单位/部门等字段<br>2. 表单验证：邮箱格式/密码强度/字段必填等客户端验证<br>3. 验证码：邮箱/短信验证码验证身份<br>4. 用户协议勾选：注册前需勾选用户协议与隐私政策<br>5. 注册提交：提交后显示"审核中"页面<br>6. 已有账号链接：提供登录页面链接方便已注册用户<br>7. 错误提示：注册失败时显示具体错误原因<br>8. 成功提示：注册成功后显示引导信息<br>9. 企业/个人选择：区分企业用户与个人用户的注册流程<br>10. 邀请码兑换：支持邀请码方式快速注册 | `/api/auth/register` |
| CommandScreenPage | `/screen` | public | **大屏指挥系统**<br>1. 全屏地图：监测点的地理分布与实时状态<br>2. 关键KPI大卡：灾害点数/活跃预警数/应急队伍数等大字显示<br>3. 排行榜：各地区的风险排名/应急响应速度排名等<br>4. 时间戳与更新频率：显示大屏数据的最后更新时间<br>5. 横屏/竖屏自适应：支持横竖屏显示<br>6. 自动刷新：数据每30秒自动刷新<br>7. 音视频告警：关键事件到达时播放告警声音/闪屏提醒<br>8. 滚动播报：底部滚动播报最新的灾害/预警事件<br>9. 指挥官模式：仅显示关键信息，不显示细节数据<br>10. 无操作自动暂停：无操作一段时间后自动进入锁屏模式 | `/api/dashboard/overview`（查询） |
| MobileCommandPage | `/mobile` | public | **移动端指挥应用**<br>1. 响应式设计：适配各种手机屏幕尺寸<br>2. 触摸优化：按钮/表单为触摸优化的尺寸<br>3. 离线模式：支持基本功能的离线使用<br>4. 推送通知：支持App推送通知（预警/任务等）<br>5. 相机集成：支持调用手机摄像头拍照/录视频<br>6. GPS定位：获取用户当前位置信息<br>7. 轻量级列表：减少数据加载，快速响应<br>8. 简化表单：针对移动输入优化的表单设计<br>9. 底部导航：常用功能的快速导航栏<br>10. 语音输入（可选）：支持语音指令与语音备忘 | `/api/dashboard/overview`（查询） |
| ForbiddenPage | `/dashboard/error/403` | public | **403禁止访问页**<br>1. 错误提示："403 Forbidden - 您没有权限访问此页面"<br>2. 拒绝原因：显示具体的拒绝理由（无路径权限/缺少权限码等）<br>3. 联系管理员链接：提供联系管理员申请权限的方式<br>4. 返回首页按钮：快速返回系统首页<br>5. 申请权限入口：支持点击向管理员发送权限申请 | 无API调用 |
| ServerErrorPage | `/dashboard/error/500` | public | **500服务器错误页**<br>1. 错误提示："500 Internal Server Error - 服务器遇到问题"<br>2. 错误追踪ID：显示本次错误的唯一追踪ID便于反馈<br>3. 重新加载按钮：支持刷新页面重试<br>4. 返回首页按钮：快速返回系统首页<br>5. 联系技术支持：提供技术支持团队的联系方式<br>6. 已知问题说明：如果是已知问题显示预计修复时间 | 无API调用 |
| NotFoundPage | 通配符路由 `/:pathMatch(.*)*` | public | **404页面未找到**<br>1. 错误提示："404 Page Not Found - 您访问的页面不存在"<br>2. 搜索建议：建议用户尝试搜索相关内容<br>3. 返回首页按钮：快速返回系统首页<br>4. 网站导航链接：显示主要功能模块的快速链接 | 无API调用 |

---

## ⚠️ 特别标注

### 已废弃/未挂载的页面
基于对路由配置 (`frontend/src/router/index.js`) 的扫描，所有 65 个 `.vue` 文件都已在路由中正确挂载。**无废弃页面**。

### 纯占位/未实现页面
- 所有已列出的页面都有完整的业务功能实现或明确的占位架构
- 部分页面（如部分管理类页面）的后端接口可能仍在建设中，前端已预留调用栈

### 权限要求说明
- 权限码遵循 `/api/roles/permission-matrix` 后端定义
- 7个角色：`admin`/`system_admin`/`emergency_commander`/`attendant`/`expert`/`ai_engineer`/`maintenance`
- 30+ 个权限码对应各模块功能

### 性能特性说明
- **实时同步**：OverviewPage/RealTimeMapPage/HazardsListPage 接入 WebSocket 实时推送
- **地图优化**：ResizeObserver 监听容器尺寸、OpenLayers 虚拟化渲染、聚簇管理
- **缓存策略**：GPS定位24h本地缓存、气象Overlay预热缓存、AI综合分5分钟落库
- **分页管理**：列表页统一支持 page/size 分页参数
- **AI集成**：灾害点/预警/设备均关联AI评分与风险标签

### 下一步改进方向
1. **性能**：大数据列表虚拟滚动实现
2. **功能**：完整的数据大屏配置化系统
3. **UX**：批量操作快捷键支持、表格列配置持久化
4. **安全**：字段级权限控制、数据脱敏规则细化

---

**文档维护**：请定期与代码保持同步，特别是新路由/新页面添加时需更新此清单。
