/**
 * 地脉智巡 v1.7.0 目标架构菜单配置。
 *
 * 设计原则：
 * - 每个菜单项带 group（导航分组）、order（组内排序）、icon（lucide 名称）、badge（静态徽标）、danger（红点告警样式）
 * - moduleMeta 提供 topbar 的 eyebrow（标题），key 为路由 path
 * - NAV_GROUPS 定义 5 大导航分组：监测与态势 / AI 与事件 / 预警与处置 / 数据与模型 / 系统与统计
 *
 * @author Cursor
 */

export const NAV_GROUPS = [
  { id: 'monitor',   label: '监测与态势', order: 1, icon: 'Radar' },
  { id: 'ai',        label: 'AI 与事件',  order: 2, icon: 'Cpu' },
  { id: 'action',    label: '预警与处置', order: 3, icon: 'Siren' },
  { id: 'data',      label: '数据与模型', order: 4, icon: 'Database' },
  { id: 'system',    label: '系统与统计', order: 5, icon: 'Settings' },
]

export const dashboardMenu = [
  // ===X= 监测与态势 ===X=
  { path: '/dashboard',                  label: '综合态势大屏', icon: 'LayoutDashboard', group: 'monitor', order: 1 },
  { path: '/dashboard/real-time-map',    label: '实时监测地图', icon: 'Map',              group: 'monitor', order: 2 },
  { path: '/dashboard/satellite-compare', label: '时相对比', icon: 'Camera', group: 'monitor', order: 3, badge: 'NEW' },
  { path: '/dashboard/zone',            label: '区域专项', icon: 'Landmark', group: 'monitor', order: 4 },

  // ===X= AI 与事件 ===X=
  { path: '/dashboard/ai-tasks',                  label: 'AI 巡检任务',          icon: 'Bot',            group: 'ai', order: 1 },
  { path: '/dashboard/ai-results',                label: 'AI 推理结果',          icon: 'Cpu',            group: 'ai', order: 2 },
  { path: '/dashboard/hazards',                   label: '灾害事件列表',          icon: 'MountainSnow',   group: 'ai', order: 3, badge: 'NEW' },
  { path: '/dashboard/confirmation-workbench',    label: '人工确认工作台',        icon: 'ClipboardCheck',   group: 'ai', order: 4, danger: true },
  { path: '/dashboard/field-inspection',          label: '现场巡检',             icon: 'HardHat',          group: 'ai', order: 4.7, badge: 'P1' },
  { path: '/dashboard/personnel',                 label: '人员管理',             icon: 'UserCheck',        group: 'ai', order: 4.8, badge: 'P1' },
  { path: '/dashboard/ai-insight',                label: 'AI 综合研判',          icon: 'ShieldAlert',    group: 'ai', order: 5 },
  { path: '/dashboard/disaster-forecast',       label: '灾害预测',             icon: 'Flame',         group: 'ai', order: 5.5, badge: 'P1' },
  { path: '/dashboard/damage-assessment',      label: '灾损评估',             icon: 'FileWarning',      group: 'ai', order: 5.8, badge: 'P1' },
  { path: '/dashboard/social-media',          label: '社交媒体舆情',          icon: 'MessageCircle',     group: 'ai', order: 5.9, badge: 'P1' },
  { path: '/dashboard/ai-models',                 label: 'AI 模型管理',          icon: 'Bot',            group: 'ai', order: 7 },
  { path: '/dashboard/reports',                   label: '报表中心',             icon: 'FileBarChart',     group: 'ai', order: 6 },
  { path: '/dashboard/data-catalog',              label: '数据资产目录',         icon: 'Database',         group: 'ai', order: 8 },

  // ===X= 预警与处置 ===X=
  { path: '/dashboard/warnings',          label: '预警中心',     icon: 'Siren',          group: 'action', order: 1 },
  { path: '/dashboard/notice-templates',  label: '通知模板',     icon: 'BellRing',       group: 'action', order: 4 },
  { path: '/dashboard/plans',             label: '预案管理',     icon: 'ScrollText',     group: 'action', order: 5 },
  { path: '/dashboard/emergency',         label: '应急事件',     icon: 'Flame',         group: 'action', order: 2 },
  { path: '/dashboard/resource-dispatch', label: '资源调度指挥', icon: 'Boxes',         group: 'action', order: 3 },
  { path: '/dashboard/route-planning',     label: '路径规划',     icon: 'Route',          group: 'action', order: 2.5, badge: 'P0' },

  // ===X= 数据与模型 ===X=
  { path: '/dashboard/devices',           label: '设备管理',     icon: 'Radio',        group: 'data', order: 1 },
  { path: '/dashboard/devices/ota',       label: 'OTA 升级',     icon: 'Package',        group: 'data', order: 2 },
  { path: '/dashboard/device-map',        label: '设备地图',     icon: 'MapPin',         group: 'data', order: 3, badge: 'NEW' },
  { path: '/dashboard/data-sources',      label: '数据源接入',   icon: 'Database',       group: 'data', order: 4 },
  { path: '/dashboard/data-sync',         label: '数据同步监控', icon: 'Radar',          group: 'data', order: 5, badge: 'NEW', danger: true },
  { path: '/dashboard/weather',           label: '气象分析',     icon: 'CloudRain',      group: 'data', order: 6 },

  // ===X= 系统与底座 ===X=
  { path: '/dashboard/backup-recovery',   label: '备份恢复',     icon: 'HardDrive',      group: 'system', order: 10 },
  { path: '/dashboard/account-security',  label: '账号安全',     icon: 'ShieldCheck',    group: 'system', order: 11 },

  // ===X= 系统与统计 ===X=
  { path: '/dashboard/analytics',         label: '统计分析',     icon: 'BarChart3',      group: 'system', order: 1 },
  { path: '/dashboard/history-hazards',   label: '历史灾害库',   icon: 'History',        group: 'system', order: 2 },
  { path: '/dashboard/user-management',   label: '用户与权限',   icon: 'Users',          group: 'system', order: 3 },
  { path: '/dashboard/audit-log',         label: '日志审计',     icon: 'ScrollText',     group: 'system', order: 4 },
  { path: '/dashboard/system-monitor',    label: '系统监控',     icon: 'Radar',         group: 'system', order: 5 },
  { path: '/dashboard/message-center',    label: '消息中心',     icon: 'Inbox',          group: 'system', order: 6 },
  { path: '/dashboard/todo',              label: '待办中心',     icon: 'ListChecks',     group: 'system', order: 7, badge: 'NEW', danger: true },
  { path: '/dashboard/approvals',         label: '审批中心',     icon: 'ShieldCheck',    group: 'system',     order: 7.5 },
  { path: '/dashboard/events',          label: '统一事件中心',   icon: 'GitBranch',       group: 'ai',     order: 4.2, badge: 'NEW' },
  { path: '/dashboard/settings',          label: '系统设置',     icon: 'Settings',       group: 'system', order: 8 },
  { path: '/dashboard/profile',           label: '个人中心',     icon: 'UserCircle',     group: 'system', order: 9 },
  { path: '/dashboard/confirmation-sign', label: '会签与转派',   icon: 'Users',          group: 'ai',      order: 4.5, badge: 'P0' },
  { path: '/dashboard/help',               label: '帮助中心',     icon: 'HelpCircle',    group: 'system', order: 12 },
  { path: '/dashboard/notification-channels', label: '通知渠道配置', icon: 'BellRing',    group: 'system', order: 13 },
]

/**
 * topbar eyebrow：每页的标题。仅保留标题文本，
 * 不再在顶部展示功能描述文案（避免每个界面重复文字说明）。
 */
export const moduleMeta = {
  '/dashboard':                        { title: '综合态势大屏' },
  '/dashboard/real-time-map':          { title: '实时监测地图' },
  '/dashboard/satellite-compare':      { title: '时相对比卫星影像' },
  '/dashboard/ai-tasks':               { title: 'AI 巡检任务' },
  '/dashboard/ai-results':             { title: 'AI 推理结果' },
  '/dashboard/ai-insight':             { title: 'AI 综合研判' },
  '/dashboard/hazards':                { title: '灾害事件列表' },
  '/dashboard/confirmation-workbench': { title: '人工确认工作台' },
  '/dashboard/warnings':               { title: '预警中心' },
  '/dashboard/warnings/create':        { title: '新建预警' },
  '/dashboard/emergency':              { title: '应急事件' },
  '/dashboard/resource-dispatch':      { title: '资源调度指挥' },
  '/dashboard/devices':                { title: '设备管理' },
  '/dashboard/data-sources':           { title: '数据源接入' },
  '/dashboard/data-sync':              { title: '数据同步监控' },
  '/dashboard/weather':                { title: '气象分析' },
  '/dashboard/analytics':              { title: '统计分析' },
  '/dashboard/history-hazards':        { title: '历史灾害库' },
  '/dashboard/user-management':        { title: '用户与权限' },
  '/dashboard/audit-log':              { title: '日志审计' },
  '/dashboard/system-monitor':         { title: '系统监控' },
  '/dashboard/message-center':         { title: '消息中心' },
  '/dashboard/todo':                   { title: '待办中心' },
  '/dashboard/approvals':              { title: '审批中心' },
  '/dashboard/events':                 { title: '统一事件中心' },
  '/dashboard/events/:code':           { title: '事件详情' },
  '/dashboard/ai-models':              { title: 'AI 模型管理' },
  '/dashboard/notice-templates':       { title: '通知模板管理' },
  '/dashboard/plans':                  { title: '预案管理' },
  '/dashboard/devices/ota':            { title: 'OTA 与边缘升级' },
  '/dashboard/device-map':             { title: '设备地图与轨迹回放' },
  '/dashboard/reports':                { title: '报表中心' },
  '/dashboard/data-catalog':           { title: '数据资产目录' },
  '/dashboard/backup-recovery':        { title: '备份与恢复' },
  '/dashboard/account-security':       { title: '账号安全' },
  '/dashboard/zone':                   { title: '区域专项大屏' },
  '/dashboard/settings':               { title: '系统设置' },
  '/dashboard/route-planning':         { title: '灾区路径规划' },
  '/dashboard/warnings/:id/resolve':   { title: '解除预警' },
  '/dashboard/profile':                { title: '个人中心' },
  '/dashboard/confirmation-sign':      { title: '会签与转派' },
  '/dashboard/confirmation-sign/:id':  { title: '事件会签详情' },
  '/dashboard/social-media':           { title: '社交媒体舆情监测' },
  '/dashboard/help':                   { title: '帮助中心' },
  '/dashboard/notification-channels':  { title: '通知渠道配置' },
}

/**
 * 左侧菜单分组顺序 + 折叠默认行为
 */
export const SIDEBAR_DEFAULTS = {
  collapsed: false,
  persistKey: 'disasterguard_sidebar_collapsed',
}

/**
 * 顶栏动态红点轮询间隔（毫秒）
 */
export const BADGE_REFRESH_INTERVAL_MS = 60_000
