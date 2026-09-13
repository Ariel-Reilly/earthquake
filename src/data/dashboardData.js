/**
 * 地脉智巡 v1.7.0 目标架构菜单配置。
 *
 * 设计原则：
 * - 每个菜单项带 group（导航分组）、order（组内排序）、icon（lucide 名称）、badge（静态徽标）、danger（红点告警样式）
 * - moduleMeta 提供 topbar 的 eyebrow（标题）和 desc（描述），key 为路由 path
 * - NAV_GROUPS 定义 5 大导航分组：监测与态势 / AI 与事件 / 预警与处置 / 数据与模型 / 系统与统计
 *
 * @author Cursor
 */

export const NAV_GROUPS = [
  { id: 'monitor',   label: '监测与态势', order: 1 },
  { id: 'ai',        label: 'AI 与事件',  order: 2 },
  { id: 'action',    label: '预警与处置', order: 3 },
  { id: 'data',      label: '数据与模型', order: 4 },
  { id: 'system',    label: '系统与统计', order: 5 },
]

export const dashboardMenu = [
  // ===X= 监测与态势 ===X=
  { path: '/dashboard',                  label: '综合态势大屏', icon: 'LayoutDashboard', group: 'monitor', order: 1 },
  { path: '/dashboard/real-time-map',    label: '实时监测地图', icon: 'Map',              group: 'monitor', order: 2 },
  { path: '/dashboard/satellite-compare', label: '时相对比', icon: 'Layers', group: 'monitor', order: 3, badge: 'NEW' },
  { path: '/dashboard/zone',            label: '区域专项', icon: 'Globe2',        group: 'monitor', order: 4 },

  // ===X= AI 与事件 ===X=
  { path: '/dashboard/ai-tasks',                  label: 'AI 巡检任务',          icon: 'ListChecks',       group: 'ai', order: 1 },
  { path: '/dashboard/ai-results',                label: 'AI 推理结果',          icon: 'BrainCircuit',     group: 'ai', order: 2 },
  { path: '/dashboard/hazards',                   label: '灾害事件列表',          icon: 'Mountain',         group: 'ai', order: 3, badge: 'NEW' },
  { path: '/dashboard/confirmation-workbench',    label: '人工确认工作台',        icon: 'ClipboardCheck',   group: 'ai', order: 4, danger: true },
  { path: '/dashboard/field-inspection',          label: '现场巡检',             icon: 'HardHat',          group: 'ai', order: 4.7, badge: 'P1' },
  { path: '/dashboard/personnel',                 label: '人员管理',             icon: 'UserCheck',        group: 'ai', order: 4.8, badge: 'P1' },
  { path: '/dashboard/ai-insight',                label: 'AI 综合研判',          icon: 'Sparkles',         group: 'ai', order: 5 },
  { path: '/dashboard/disaster-forecast',       label: '灾害预测',             icon: 'TrendingUp',       group: 'ai', order: 5.5, badge: 'P1' },
  { path: '/dashboard/damage-assessment',      label: '灾损评估',             icon: 'FileWarning',      group: 'ai', order: 5.8, badge: 'P1' },
  { path: '/dashboard/social-media',          label: '社交媒体舆情',          icon: 'MessageCircle',     group: 'ai', order: 5.9, badge: 'P1' },
  { path: '/dashboard/ai-models',                 label: 'AI 模型管理',          icon: 'BrainCircuit',     group: 'ai', order: 7 },
  { path: '/dashboard/reports',                   label: '报表中心',             icon: 'FileBarChart',     group: 'ai', order: 6 },
  { path: '/dashboard/data-catalog',              label: '数据资产目录',         icon: 'Database',         group: 'ai', order: 8 },

  // ===X= 预警与处置 ===X=
  { path: '/dashboard/warnings',          label: '预警中心',     icon: 'AlertTriangle',  group: 'action', order: 1 },
  { path: '/dashboard/notice-templates',  label: '通知模板',     icon: 'MessageSquare',  group: 'action', order: 4 },
  { path: '/dashboard/plans',             label: '预案管理',     icon: 'FileSignature',   group: 'action', order: 5 },
  { path: '/dashboard/emergency',         label: '应急事件',     icon: 'LifeBuoy',       group: 'action', order: 2 },
  { path: '/dashboard/resource-dispatch', label: '资源调度指挥', icon: 'Truck',          group: 'action', order: 3 },
  { path: '/dashboard/route-planning',     label: '路径规划',     icon: 'Route',          group: 'action', order: 2.5, badge: 'P0' },

  // ===X= 数据与模型 ===X=
  { path: '/dashboard/devices',           label: '设备管理',     icon: 'Radio',          group: 'data', order: 1 },
  { path: '/dashboard/devices/ota',       label: 'OTA 升级',     icon: 'Package',        group: 'data', order: 2 },
  { path: '/dashboard/device-map',        label: '设备地图',     icon: 'MapPin',         group: 'data', order: 3, badge: 'NEW' },
  { path: '/dashboard/data-sources',      label: '数据源接入',   icon: 'Database',       group: 'data', order: 4 },
  { path: '/dashboard/data-sync',         label: '数据同步监控', icon: 'Activity',       group: 'data', order: 5, badge: 'NEW', danger: true },
  { path: '/dashboard/weather',           label: '气象分析',     icon: 'CloudRain',      group: 'data', order: 6 },

  // ===X= 系统与底座 ===X=
  { path: '/dashboard/backup-recovery',   label: '备份恢复',     icon: 'HardDrive',      group: 'system', order: 10 },
  { path: '/dashboard/account-security',  label: '账号安全',     icon: 'ShieldCheck',    group: 'system', order: 11 },

  // ===X= 系统与统计 ===X=
  { path: '/dashboard/analytics',         label: '统计分析',     icon: 'BarChart3',      group: 'system', order: 1 },
  { path: '/dashboard/history-hazards',   label: '历史灾害库',   icon: 'History',        group: 'system', order: 2 },
  { path: '/dashboard/user-management',   label: '用户与权限',   icon: 'Users',          group: 'system', order: 3 },
  { path: '/dashboard/audit-log',         label: '日志审计',     icon: 'ScrollText',     group: 'system', order: 4 },
  { path: '/dashboard/system-monitor',    label: '系统监控',     icon: 'Activity',       group: 'system', order: 5 },
  { path: '/dashboard/message-center',    label: '消息中心',     icon: 'Inbox',          group: 'system', order: 6 },
  { path: '/dashboard/todo',              label: '待办中心',     icon: 'ListChecks',     group: 'system', order: 7, badge: 'NEW', danger: true },
  { path: '/dashboard/approvals',         label: '审批中心',     icon: 'ShieldCheck',    group: 'system',     order: 7.5 },
  { path: '/dashboard/events',          label: '统一事件中心',   icon: 'GitMerge',       group: 'ai',     order: 4.2, badge: 'NEW' },
  { path: '/dashboard/settings',          label: '系统设置',     icon: 'Settings',       group: 'system', order: 8 },
  { path: '/dashboard/profile',           label: '个人中心',     icon: 'UserCircle',     group: 'system', order: 9 },
  { path: '/dashboard/confirmation-sign', label: '会签与转派',   icon: 'Users',          group: 'ai',      order: 4.5, badge: 'P0' },
  { path: '/dashboard/help',               label: '帮助中心',     icon: 'HelpCircle',    group: 'system', order: 12 },
  { path: '/dashboard/notification-channels', label: '通知渠道配置', icon: 'BellRing',    group: 'system', order: 13 },
]

/**
 * topbar eyebrow：每页的标题 + 描述
 */
export const moduleMeta = {
  '/dashboard': {
    title: '综合态势大屏',
    desc: '全国/省级灾害态势、预警、AI 巡检覆盖、核心指标总览。暗色主题、自动轮播、按角色切换视角。',
  },
  '/dashboard/real-time-map': {
    title: '实时监测地图',
    desc: '卫星 / 地形 / 行政区划底图切换；气象雷达、传感器、灾害点多图层叠加；时间轴回放与空间测量。',
  },
  '/dashboard/satellite-compare': {
    title: '时相对比卫星影像',
    desc: '灾前 / 灾后 Sentinel-1 + Sentinel-2 三种对比模式（卷帘 / 闪烁 / 并排）+ 像素级差异热力图 + Top 3 变化热点提取，支持导出 PNG。',
  },
  '/dashboard/ai-tasks': {
    title: 'AI 巡检任务',
    desc: '创建周期任务（每小时/每天）或触发式任务（地震/暴雨），任务队列、启停与执行监控。',
  },
  '/dashboard/ai-results': {
    title: 'AI 推理结果',
    desc: '置信度分布、多模型投票、异常检测，自动生成"疑似事件"推送到人工确认队列。',
  },
  '/dashboard/ai-insight': {
    title: 'AI 综合研判',
    desc: '每个灾害点的多维度 AI 融合评分、可解释性推理、建议措施，研判数据每 5 分钟自动刷新。',
  },
  '/dashboard/hazards': {
    title: '灾害事件列表',
    desc: '已纳入档案的灾害点：按时间 / 灾种 / 等级 / 状态筛选，支持批量确认 / 驳回 / 合并。',
  },
  '/dashboard/confirmation-workbench': {
    title: '人工确认工作台',
    desc: 'AI 初筛 → 人工二次确认闭环。认领、转派、确认、驳回、操作全流程留痕。',
  },
  '/dashboard/warnings': {
    title: '预警中心',
    desc: '待发布 / 已发布 / 已解除预警统一管理；批量解除、状态机推进、模板套用。',
  },
  '/dashboard/warnings/create': {
    title: '新建预警',
    desc: '从已确认灾害事件创建预警：等级、发布范围、模板、渠道一键配置。',
  },
  '/dashboard/emergency': {
    title: '应急事件',
    desc: '与预警联动（派发橙/红预警时自动生成），处置流程：待处置 → 处置中 → 已闭环。',
  },
  '/dashboard/resource-dispatch': {
    title: '资源调度指挥',
    desc: '基于事件推荐资源（队伍/物资/场所）、任务指派、协同通讯、路径规划。',
  },
  '/dashboard/devices': {
    title: '设备管理',
    desc: '设备注册 / 认证 / 分组 / 状态监控；批量导入、远程控制、固件 OTA。',
  },
  '/dashboard/data-sources': {
    title: '数据源接入',
    desc: '遥感卫星 / 气象 API / 水文数据接入管理：连接测试、认证配置、状态监控。',
  },
  '/dashboard/data-sync': {
    title: '数据同步监控',
    desc: '同步任务运行状态 / 历史 / 数据完整性 / 异常值告警，分钟级延迟可观测。',
  },
  '/dashboard/weather': {
    title: '气象分析',
    desc: 'NSMC 国家气象卫星云图、雷达回波、地面观测，与 AI 模型联动预测风险。',
  },
  '/dashboard/analytics': {
    title: '统计分析',
    desc: '灾害频次 / 趋势 / 空间分布；模型准确率 / 误报率；确认效率 / 响应时间；可视化图表。',
  },
  '/dashboard/history-hazards': {
    title: '历史灾害库',
    desc: '历史灾害事件档案库：时间 / 地点 / 灾种 / 等级检索、地图展示、详情查看、导出。',
  },
  '/dashboard/user-management': {
    title: '用户与权限',
    desc: '用户列表、角色分配、菜单 / 操作 / 数据权限，审计留痕不可篡改。',
  },
  '/dashboard/audit-log': {
    title: '日志审计',
    desc: '所有写操作自动落库，操作员 / 动作 / 对象 / 详情 / IP 完整记录。',
  },
  '/dashboard/system-monitor': {
    title: '系统监控',
    desc: '服务状态、API 调用量、存储使用、CPU/GPU 趋势、实时告警。',
  },
  '/dashboard/message-center': {
    title: '消息中心',
    desc: '站内消息 / 系统通知 / 预警通知；已读未读、批量操作、快速跳转。',
  },
  '/dashboard/todo': {
    title: '待办中心',
    desc: '汇总待确认 / 待审批 / 待发布 / 待跟踪任务，一键跳转对应工作台。',
  },
  '/dashboard/approvals': {
    title: '审批中心',
    desc: '预警发布 / 应急派单 / 预案启动 / 设备控制审批流，支持会签、超时升级、审计追溯。',
  },
  '/dashboard/events': {
    title: '统一事件中心',
    desc: '7 状态 × 12 转移的状态机视图，跨 hazard / warning / emergency 三表的统一跟踪。AI 自动流转 → 人工确认 → 预警发布 → 应急处置 → 关闭归档，全链路可追溯。',
  },
  '/dashboard/events/:code': {
    title: '事件详情',
    desc: '单个事件的元信息、关联实体（hazard/warning/emergency）、血缘图与状态流转操作面板。',
  },
  '/dashboard/ai-models': {
    title: 'AI 模型管理',
    desc: '模型版本库 / 训练任务 / 推理指标 / 特征重要性 / 在线模型切换与重载。',
  },
  '/dashboard/notice-templates': {
    title: '通知模板管理',
    desc: '按 (灾种,等级,渠道) 维护模板，{{var}} 占位符 + 多渠道预览 + 启用开关。',
  },
  '/dashboard/plans': {
    title: '预案管理',
    desc: '应急预案数字化：启动流程 / 责任人 / 附件 / 启动模拟与历史。',
  },
  '/dashboard/devices/ota': {
    title: 'OTA 与边缘升级',
    desc: '固件包库 / 批量升级 / 进度监控 / 失败回滚 / 边缘 AI 模型下发。',
  },
  '/dashboard/device-map': {
    title: '设备地图与轨迹回放',
    desc: '把设备按当前位置标注在地图上，无人机 / 车辆 / 手持终端等移动设备可展示最近 24h 轨迹，支持播放、暂停、加速、重置。',
  },
  '/dashboard/reports': {
    title: '报表中心',
    desc: '报表模板库 / 字段组合 / 订阅与调度 / 历史重跑与下载。',
  },
  '/dashboard/data-catalog': {
    title: '数据资产目录',
    desc: '资源元数据 / 字段字典 / 上下游血缘 / 使用排行 / 权限申请。',
  },
  '/dashboard/backup-recovery': {
    title: '备份与恢复',
    desc: '全量 / 差异 / 增量备份 / 跨环境恢复 / 校验与日志。',
  },
  '/dashboard/account-security': {
    title: '账号安全',
    desc: '双因子认证 2FA / 登录历史 / 活跃会话 / 个人操作审计。',
  },
  '/dashboard/zone': {
    title: '区域专项大屏',
    desc: '按灾种 / 区域下钻的专项态势：风险趋势、Top 5 风险点、关联预警与应急事件。',
  },
  '/dashboard/settings': {
    title: '系统设置',
    desc: '个人信息、密码修改、通知偏好、语言、主题。',
  },
  // ===X===X===X===X===X===X===X===X===X===X===X===X===X===X===X
  // P0 阶段新增描述
  // ===X===X===X===X===X===X===X===X===X===X===X===X===X===X===X
  '/dashboard/route-planning': {
    title: '灾区路径规划',
    desc: '基于灾害影响区域的避障导航：多方案对比、风险等级、GeoJSON 导出与现场分享。',
  },
  '/dashboard/warnings/:id/resolve': {
    title: '解除预警',
    desc: '解除已发布预警、选择范围、生成解除通知并自动归档。',
  },
  '/dashboard/profile': {
    title: '个人中心',
    desc: '个人信息 / 密码 / 通知偏好 / 主题语言，一站式管理。',
  },
  '/dashboard/confirmation-sign': {
    title: '会签与转派',
    desc: '多人会签（并行 / 串行 / 超时升级）、转派、意见汇总。',
  },
  '/dashboard/confirmation-sign/:id': {
    title: '事件会签详情',
    desc: '查看单个事件会签时间线、转派历史、确认或驳回。',
  },
  // ===X= v1.7+ 缺口补全 ===X=
  '/dashboard/social-media': {
    title: '社交媒体舆情监测',
    desc: '关键词监控（灾种 + 地区 + 时间）；舆情热力图 + 情感分析；疑似灾害线索提取；一键转为"疑似事件"进入人工确认队列。',
  },
  '/dashboard/help': {
    title: '帮助中心',
    desc: '操作手册（按角色分）、FAQ、API 文档（Swagger）、联系管理员 / 提交反馈。',
  },
  '/dashboard/notification-channels': {
    title: '通知渠道配置',
    desc: '短信 / APP 推送 / 邮件 / LED 屏 / Webhook 的配置与测试；渠道优先级 + 失败降级策略。',
  },
  '/dashboard/zone': {
    title: '区域专项大屏',
    desc: '按灾种 / 区域下钻的专项态势：风险趋势、Top 5 风险点、关联预警与应急事件。',
  },
}

/**
 * 左侧菜单分组顺序 + 折叠默认行为
 */
export const SIDEBAR_DEFAULTS = {
  collapsed: false,
  persistKey: 'geosentry_sidebar_collapsed',
}

/**
 * 顶栏动态红点轮询间隔（毫秒）
 */
export const BADGE_REFRESH_INTERVAL_MS = 60_000
