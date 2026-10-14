/**
 * 认证状态 Pinia store + 兼容层。
 *
 * Step 4 引入 Pinia: 单例存储 token/user,所有 view/composable 通过 store 读取。
 * 兼容旧函数 API(getAuthToken / setAuthSession 等)继续可用,内部委托给 store,
 * 这样现有 50+ 调用点不需要修改。
 *
 * 角色 / 权限矩阵依旧以纯数据形式导出(ROLE_OPTIONS / ROLE_MENU_MAP /
 * ROLE_PERMISSION_MAP), 不放进 store 因为它们是静态配置而非运行时状态。
 */

import { defineStore } from 'pinia'

// 持久化 key 与角色定义
const TOKEN_KEY = 'disasterguard_token'
const USER_KEY = 'disasterguard_user'

export const ROLE_OPTIONS = [
  { value: 'admin',               label: '超管',       description: '平台最高权限，拥有全部模块访问权限，负责系统初始化和极端故障恢复' },
  { value: 'system-admin',        label: '系统管理员', description: '负责系统运维、日志审计和用户管理，管系统不管业务' },
  { value: 'emergency-commander', label: '应急指挥',   description: '灾害发生时的最高指挥官，负责态势研判、预警发布和资源调度' },
  { value: 'attendant',           label: '值班员',     description: '7×24 小时盯屏，AI 初筛的第一响应人，负责初审和草稿起草' },
  { value: 'expert',              label: '领域专家',   description: '提供专业背书，防止 AI 误报漏报，负责二次确认和会签复核' },
  { value: 'ai-engineer',         label: 'AI工程师',   description: '负责模型迭代、规则配置和数据管道，造模型的不管业务' },
  { value: 'maintenance',         label: '现场运维',   description: '负责设备安装、抢修和状态回报，一线设备保障' },
]

export const ROLE_HOME_MAP = {
  admin:               '/dashboard',
  'system-admin':      '/dashboard/audit-log',
  'emergency-commander': '/dashboard/emergency',
  attendant:           '/dashboard',
  expert:              '/dashboard/confirmation-workbench',
  'ai-engineer':      '/dashboard/ai-tasks',
  maintenance:         '/dashboard/devices',
}

/**
 * 角色名规范化：后端 sys_role.role_code 使用下划线（system_admin / emergency_commander /
 * ai_engineer），而本文件的角色映射表使用连字符（system-admin / ...）。
 * 两者不一致时映射会 miss，历史上被 `?? ROLE_MENU_MAP.admin` 兜底掩盖，
 * 导致这三个角色实际拿到的是管理员的全部菜单与权限（越权）。
 * 统一在此处做一次转换，使两侧键名对齐。
 */
export function normalizeRole(role) {
  if (!role) {return '';}
  return String(role).trim().toLowerCase().replace(/_/g, '-');
}

export const ROLE_MENU_MAP = {
  admin: [
    '/dashboard', '/dashboard/real-time-map', '/dashboard/satellite-compare', '/dashboard/device-map',
    '/dashboard/ai-tasks', '/dashboard/ai-results', '/dashboard/ai-insight', '/dashboard/disaster-forecast', '/dashboard/damage-assessment', '/dashboard/ai-models',
    '/dashboard/hazards', '/dashboard/confirmation-workbench', '/dashboard/confirmation-sign',
    '/dashboard/field-inspection', '/dashboard/personnel',
    '/dashboard/events',
    '/dashboard/warnings', '/dashboard/warnings/create', '/dashboard/warnings/:id/delivery',
    '/dashboard/emergency', '/dashboard/resource-dispatch', '/dashboard/route-planning',
    '/dashboard/devices', '/dashboard/devices/ota',
    '/dashboard/data-sources', '/dashboard/data-sync', '/dashboard/weather',
    '/dashboard/rule-engine',
    '/dashboard/analytics', '/dashboard/history-hazards',
    '/dashboard/user-management', '/dashboard/audit-log', '/dashboard/system-monitor',
    '/dashboard/message-center', '/dashboard/todo', '/dashboard/approvals',
    '/dashboard/notice-templates', '/dashboard/plans', '/dashboard/reports',
    '/dashboard/data-catalog', '/dashboard/backup-recovery',
    '/dashboard/account-security', '/dashboard/settings', '/dashboard/profile',
    '/dashboard/zone', '/dashboard/workbench', '/dashboard/help',
    '/dashboard/social-media', '/dashboard/warning-config', '/dashboard/data-manage',
    '/dashboard/notification-channels',
  ],
  attendant: [
    '/dashboard', '/dashboard/real-time-map', '/dashboard/satellite-compare',
    '/dashboard/ai-tasks', '/dashboard/ai-insight', '/dashboard/ai-results',
    '/dashboard/hazards', '/dashboard/confirmation-workbench', '/dashboard/confirmation-sign',
    '/dashboard/field-inspection', '/dashboard/personnel',
    '/dashboard/events',
    '/dashboard/warnings', '/dashboard/warnings/create',
    '/dashboard/emergency', '/dashboard/devices', '/dashboard/device-map',
    '/dashboard/notice-templates', '/dashboard/plans', '/dashboard/reports',
    '/dashboard/weather', '/dashboard/analytics',
    '/dashboard/approvals', '/dashboard/account-security',
    '/dashboard/message-center', '/dashboard/todo', '/dashboard/settings', '/dashboard/profile',
    '/dashboard/route-planning', '/dashboard/workbench', '/dashboard/help',
    '/dashboard/social-media', '/dashboard/warning-config', '/dashboard/notification-channels',
  ],
  expert: [
    '/dashboard',
    '/dashboard/hazards', '/dashboard/confirmation-workbench', '/dashboard/confirmation-sign',
    '/dashboard/field-inspection', '/dashboard/personnel',
    '/dashboard/events',
    '/dashboard/ai-tasks', '/dashboard/ai-insight', '/dashboard/disaster-forecast', '/dashboard/damage-assessment', '/dashboard/ai-models', '/dashboard/ai-results',
    '/dashboard/warnings',
    '/dashboard/emergency', '/dashboard/route-planning',
    '/dashboard/plans', '/dashboard/data-catalog',
    '/dashboard/approvals', '/dashboard/rule-engine',
    '/dashboard/reports', '/dashboard/analytics',
    '/dashboard/account-security', '/dashboard/message-center', '/dashboard/todo',
    '/dashboard/settings', '/dashboard/profile', '/dashboard/workbench', '/dashboard/help',
  ],
  maintenance: [
    '/dashboard', '/dashboard/real-time-map',
    '/dashboard/hazards',
    '/dashboard/devices', '/dashboard/devices/ota', '/dashboard/device-map',
    '/dashboard/data-sources', '/dashboard/weather',
    '/dashboard/analytics',
    '/dashboard/emergency', '/dashboard/resource-dispatch', '/dashboard/route-planning',
    '/dashboard/system-monitor', '/dashboard/backup-recovery',
    '/dashboard/account-security', '/dashboard/message-center', '/dashboard/todo',
    '/dashboard/settings', '/dashboard/profile', '/dashboard/workbench', '/dashboard/help',
  ],
  'system-admin': [
    '/dashboard', '/dashboard/analytics', '/dashboard/history-hazards',
    '/dashboard/reports', '/dashboard/user-management', '/dashboard/audit-log',
    '/dashboard/system-monitor', '/dashboard/data-sync', '/dashboard/account-security',
    '/dashboard/settings', '/dashboard/profile', '/dashboard/message-center',
    '/dashboard/todo', '/dashboard/backup-recovery', '/dashboard/workbench', '/dashboard/help',
  ],
  'emergency-commander': [
    '/dashboard', '/dashboard/real-time-map', '/dashboard/satellite-compare',
    '/dashboard/ai-tasks', '/dashboard/ai-results',
    '/dashboard/disaster-forecast', '/dashboard/damage-assessment',
    '/dashboard/hazards', '/dashboard/confirmation-workbench', '/dashboard/confirmation-sign',
    '/dashboard/field-inspection', '/dashboard/personnel',
    '/dashboard/events',
    '/dashboard/warnings', '/dashboard/warnings/create', '/dashboard/warnings/:id/delivery',
    '/dashboard/emergency', '/dashboard/resource-dispatch', '/dashboard/route-planning',
    '/dashboard/devices', '/dashboard/device-map',
    '/dashboard/notice-templates', '/dashboard/plans',
    '/dashboard/approvals', '/dashboard/reports', '/dashboard/analytics',
    '/dashboard/account-security', '/dashboard/profile', '/dashboard/workbench', '/dashboard/help',
    '/dashboard/social-media', '/dashboard/warning-config', '/dashboard/notification-channels',
  ],
  'ai-engineer': [
    '/dashboard', '/dashboard/ai-tasks', '/dashboard/ai-results',
    '/dashboard/ai-models', '/dashboard/ai-insight', '/dashboard/disaster-forecast', '/dashboard/damage-assessment',
    '/dashboard/rule-engine',
    '/dashboard/data-sources', '/dashboard/data-sync', '/dashboard/data-catalog',
    '/dashboard/analytics', '/dashboard/reports',
    '/dashboard/system-monitor',
    '/dashboard/account-security', '/dashboard/message-center', '/dashboard/todo',
    '/dashboard/profile', '/dashboard/workbench', '/dashboard/help', '/dashboard/data-manage',
  ],
}

export const ROLE_PERMISSION_MAP = {
  admin: [
    'overview:view','overview:export','map:view','map:export','map:edit','zone:view',
    'ai:task:view','ai:task:create','ai:task:edit','ai:task:delete','ai:task:retry','ai:task:terminate',
    'ai:result:view','ai:result:confirm','ai:result:transfer','ai:result:review','ai:insight:view','ai:insight:refresh',
    'ai:forecast:view',
    'ai:model:view','ai:model:manage','ai:model:edit',
    'hazards:view','hazards:create','hazards:edit','hazards:delete','hazards:export',
    'confirmation:view','confirmation:claim','confirmation:confirm','confirmation:reject','confirmation:transfer',
    'inspection:view','inspection:submit','inspection:gps-check',
    'personnel:view','personnel:report',
    'approval:view','approval:sign','approval:initiate','approval:approve','approval:reject',
    'events:view','events:transition','events:reopen',
    'warnings:view','warnings:confirm','warnings:dispatch','warnings:close','warnings:create','warnings:export','warnings:resend',
    'notice:view','notice:edit','plans:view','plans:edit','plans:launch',
    'emergency:view','emergency:start','emergency:dispatch','emergency:close','emergency:export',
    'resource:view','resource:dispatch','resource:assign','resource:create','resource:edit',
    'devices:view','devices:create','devices:edit','devices:delete','devices:export','devices:control','devices:ota',
    'datasource:view','datasource:config','datasource:create','datasource:edit','datasource:delete',
    'sync:view','sync:manage',
    'analytics:view','analytics:export','history:view','history:export',
    'users:view','users:create','users:edit','users:delete','roles:view','roles:edit',
    'rules:view','rules:edit','rules:create','rules:delete','audit:view','audit:export','monitor:view',
    'backup:view','backup:manage','settings:view','settings:edit',
    'reports:view','reports:create','reports:delete','data:catalog:view',
    'messages:view','messages:send','todo:view',
    'route:view','route:plan','route:approve','route:export','sign:view','sign:create','sign:confirm',
    'profile:view','profile:edit',
  ],
  attendant: [
    'overview:view','overview:export','map:view','map:edit','zone:view',
    'ai:task:view','ai:task:create',
    'ai:result:view','ai:result:transfer',
    'ai:insight:view','ai:insight:refresh',
    'ai:forecast:view',
    'ai:model:view',
    'hazards:view','hazards:create','hazards:edit',
    'confirmation:view','confirmation:claim','confirmation:confirm','confirmation:reject','confirmation:transfer',
    'inspection:view','inspection:submit','inspection:gps-check',
    'personnel:view','personnel:report',
    'warnings:view','warnings:confirm','warnings:dispatch','warnings:create','warnings:export',
    'events:view','events:transition',
    'notice:view','notice:edit','plans:view','plans:edit','plans:launch',
    'emergency:view','emergency:dispatch','devices:view','weather:view','analytics:view',
    'reports:view','approval:view','approval:initiate',
    'messages:view','todo:view','settings:view',
    'route:view','route:export','sign:view','sign:create',
    'profile:view','profile:edit',
  ],
  expert: [
    'overview:view','zone:view',
    'hazards:view','hazards:create','hazards:edit',
    'ai:task:view','ai:insight:view',
    'ai:result:view','ai:result:review',
    'ai:forecast:view',
    'ai:model:view',
    'confirmation:view','confirmation:claim','confirmation:confirm','confirmation:reject','confirmation:transfer',
    'inspection:view',
    'personnel:view',
    'approval:view','approval:sign','approval:approve','approval:reject',
    'events:view',
    'warnings:view','warnings:dispatch',
    'plans:view','data:catalog:view','emergency:view','emergency:dispatch',
    'rules:view',
    'reports:view','analytics:view',
    'messages:view','todo:view','settings:view',
    'route:view','sign:view','sign:create','sign:confirm',
    'profile:view','profile:edit',
  ],
  maintenance: [
    'overview:view','map:view','zone:view',
    'hazards:view',
    'devices:view','devices:create','devices:edit','devices:delete','devices:export','devices:control','devices:ota',
    'datasource:view','sync:view','data:catalog:view','weather:view','analytics:view',
    'emergency:view','emergency:dispatch',
    'resource:view','route:view',
    'inspection:view','inspection:submit','inspection:gps-check',
    'personnel:view','personnel:report',
    'monitor:view','backup:view','messages:view','todo:view','settings:view',
    'profile:view','profile:edit',
  ],
  'system-admin': [
    'overview:view','overview:export',
    'analytics:view','analytics:export','history:view','history:export',
    'reports:view','reports:create','reports:delete',
    'users:view','users:create','users:edit','users:delete','roles:view','roles:edit',
    'audit:view','audit:export',
    'monitor:view',
    'backup:view','backup:manage',
    'sync:view',
    'settings:view','settings:edit',
    'messages:view','messages:send','todo:view',
    'profile:view','profile:edit',
  ],
  'emergency-commander': [
    'overview:view','overview:export','map:view','map:edit','zone:view',
    'ai:task:view',
    'ai:result:view',
    'ai:forecast:view',
    'hazards:view','hazards:create','hazards:edit',
    'confirmation:view',
    'inspection:view',
    'personnel:view',
    'events:view','events:transition','events:reopen',
    'sign:view','sign:create','sign:confirm',
    'warnings:view','warnings:dispatch','warnings:close','warnings:create','warnings:export','warnings:resend',
    'notice:view','plans:view','plans:edit','plans:launch',
    'emergency:view','emergency:start','emergency:dispatch','emergency:close',
    'resource:view','resource:dispatch','resource:assign','resource:create','resource:edit',
    'route:view','route:plan','route:approve','route:export',
    'devices:view',
    'approval:view','approval:approve','approval:reject',
    'reports:view','reports:create','analytics:view','analytics:export',
    'profile:view','profile:edit',
  ],
  'ai-engineer': [
    'overview:view','analytics:view',
    'ai:task:view','ai:task:create','ai:task:edit','ai:task:delete','ai:task:retry','ai:task:terminate',
    'ai:result:view','ai:result:transfer',
    'ai:model:view','ai:model:manage','ai:model:edit',
    'ai:insight:view','ai:insight:refresh',
    'ai:forecast:view',
    'rules:view','rules:edit','rules:create','rules:delete',
    'datasource:view','datasource:config','datasource:create','datasource:edit','datasource:delete',
    'sync:view','sync:manage',
    'data:catalog:view',
    'reports:view','monitor:view',
    'messages:view','todo:view',
    'profile:view','profile:edit',
  ],
}

// Pinia store — 唯一状态源

function safeParse(raw) {
  if (!raw) {return null}
  try { return JSON.parse(raw) } catch { return null }
}

function loadInitialState() {
  return {
    token: window.localStorage.getItem(TOKEN_KEY) || null,
    user: safeParse(window.localStorage.getItem(USER_KEY)) || null,
  }
}

export const useAuthStore = defineStore('auth', {
  state: () => loadInitialState(),
  getters: {
    isLoggedIn: (state) => Boolean(state.token && state.user),
    // 角色缺失或无法识别时一律视为"无角色"，绝不回落到 admin。
    // 原实现 `?? 'admin'` 会让任意未知角色直接获得管理员全部菜单与权限。
    role: (state) => normalizeRole(state.user?.role),
    homePath() { return ROLE_HOME_MAP[this.role] ?? '/dashboard' },
    allowedPaths() { return ROLE_MENU_MAP[this.role] ?? [] },
    permissions() { return ROLE_PERMISSION_MAP[this.role] ?? [] },
  },
  actions: {
    setSession(session) {
      this.token = session.token
      this.user = session.user
      window.localStorage.setItem(TOKEN_KEY, session.token)
      window.localStorage.setItem(USER_KEY, JSON.stringify(session.user))
    },
    updateUser(user) {
      this.user = user
      window.localStorage.setItem(USER_KEY, JSON.stringify(user))
    },
    clearSession() {
      this.token = null
      this.user = null
      window.localStorage.removeItem(TOKEN_KEY)
      window.localStorage.removeItem(USER_KEY)
    },
    hasPermission(permission) {
      return this.permissions.includes(permission)
    },
    canAccessPath(path) {
      return this.allowedPaths.some((p) => path === p || path.startsWith(`${p}/`))
    },
  },
})

// 兼容层: 旧函数 API → store (Step 4 引入,后续 Step 8 移除)

/**
 * @deprecated 直接使用 useAuthStore().xxx。新代码不要再调用这些函数。
 * 保留它们是因为 router / 5+ view / httpClient 还在用,逐步迁移。
 */
export function getAuthToken() { return useAuthStore().token }
export function getAuthUser() { return useAuthStore().user }
export function getAuthRole() { return useAuthStore().role }
export function getAuthHeaders() {
  const token = useAuthStore().token
  return token ? { Authorization: `Bearer ${token}` } : {}
}
export function setAuthSession(session) { useAuthStore().setSession(session) }
export function clearAuthSession() { useAuthStore().clearSession() }
export function getHomePathByRole(_role) { return useAuthStore().homePath }
export function getAllowedMenuPaths(_role) { return useAuthStore().allowedPaths }
export function getRoleLabel(role) {
  return ROLE_OPTIONS.find((item) => item.value === role)?.label ?? '管理员'
}
export function hasPermission(role, permission) {
  // 注: 旧 API 把 role 作为参数传入, store 用 this.role。
  // 这里做一次映射: 如果 role 显式传入且和 store.role 不同,按传入计算 (兼容 mock 时多角色切换)。
  const normalized = normalizeRole(role)
  if (normalized && normalized !== useAuthStore().role) {
    return (ROLE_PERMISSION_MAP[normalized] ?? []).includes(permission)
  }
  return useAuthStore().hasPermission(permission)
}
export function canAccessPath(role, path) {
  const normalized = normalizeRole(role)
  if (normalized && normalized !== useAuthStore().role) {
    const allowed = ROLE_MENU_MAP[normalized] ?? []
    return allowed.some((p) => path === p || path.startsWith(`${p}/`))
  }
  return useAuthStore().canAccessPath(path)
}

/**
 * 轻量级 JWT 过期检查:base64 解码 payload 段,对比 exp 与当前时间。
 * 不依赖第三方 JWT 库(避免引入运行时开销),仅做 exp 字段提取。
 *
 * @returns true 表示 token 已过期或格式异常(视为过期,触发重新登录)
 */
export function isTokenExpired() {
  const token = getAuthToken()
  if (!token) {return true}
  const parts = token.split('.')
  if (parts.length !== 3) {return true}
  try {
    const base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const payload = JSON.parse(atob(base64))
    if (!payload.exp) {return false}
    return payload.exp * 1000 < Date.now()
  } catch {
    return true
  }
}

/** 模块级 401 重定向锁:防止并发请求同时触发多次 redirectToLogin。 */
let _redirecting = false
export function redirectToLogin() {
  if (_redirecting) {return}
  _redirecting = true
  clearAuthSession()
  window.location.replace('/login')
  setTimeout(() => { _redirecting = false }, 2000)
}
