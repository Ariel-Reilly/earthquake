const TOKEN_KEY = 'geosentry_token'
const USER_KEY = 'geosentry_user'

// ===X===X===X===X===X===X===X===X===X===X===X===X===X===X===X
// 角色定义（目标架构 v1.7.0）
// ===X===X===X===X===X===X===X===X===X===X===X===X===X===X===X
export const ROLE_OPTIONS = [
  { value: 'admin',      label: '管理员',   description: '拥有全部模块访问权限' },
  { value: 'attendant',  label: '值班员',   description: '负责态势、巡检和预警处置' },
  { value: 'expert',     label: '领域专家',  description: '负责灾害评估和会签复核' },
  { value: 'maintenance',label: '运维员',   description: '负责设备和基础设施运维' },
]

export const ROLE_HOME_MAP = {
  admin:      '/dashboard',
  attendant:  '/dashboard',
  expert:     '/dashboard/confirmation-workbench',
  maintenance:'/dashboard/devices',
}

// ===X===X===X===X===X===X===X===X===X===X===X===X===X===X===X
// 完整菜单权限（按角色）
// ===X===X===X===X===X===X===X===X===X===X===X===X===X===X===X
export const ROLE_MENU_MAP = {
  admin: [
    '/dashboard',
    '/dashboard/real-time-map',
    '/dashboard/ai-tasks',
    '/dashboard/ai-results',
    '/dashboard/ai-insight',
    '/dashboard/hazards',
    '/dashboard/confirmation-workbench',
    '/dashboard/warnings',
    '/dashboard/emergency',
    '/dashboard/resource-dispatch',
    '/dashboard/devices',
    '/dashboard/data-sources',
    '/dashboard/weather',
    '/dashboard/analytics',
    '/dashboard/history-hazards',
    '/dashboard/user-management',
    '/dashboard/audit-log',
    '/dashboard/system-monitor',
    '/dashboard/message-center',
    '/dashboard/todo',
    '/dashboard/approvals',
    '/dashboard/ai-models',
    '/dashboard/reports',
    '/dashboard/data-catalog',
    '/dashboard/notice-templates',
    '/dashboard/plans',
    '/dashboard/devices/ota',
    '/dashboard/backup-recovery',
    '/dashboard/account-security',
    '/dashboard/settings',
    '/dashboard/profile',
    '/dashboard/route-planning',
    '/dashboard/confirmation-sign',
  ],
  attendant: [
    '/dashboard',
    '/dashboard/real-time-map',
    '/dashboard/ai-tasks',
    '/dashboard/ai-insight',
    '/dashboard/reports',
    '/dashboard/hazards',
    '/dashboard/confirmation-workbench',
    '/dashboard/warnings',
    '/dashboard/notice-templates',
    '/dashboard/plans',
    '/dashboard/emergency',
    '/dashboard/devices',
    '/dashboard/weather',
    '/dashboard/analytics',
    '/dashboard/account-security',
    '/dashboard/message-center',
    '/dashboard/todo',
    '/dashboard/approvals',
    '/dashboard/settings',
    '/dashboard/profile',
    '/dashboard/route-planning',
    '/dashboard/confirmation-sign',
  ],
  expert: [
    '/dashboard',
    '/dashboard/hazards',
    '/dashboard/confirmation-workbench',
    '/dashboard/confirmation-sign',
    '/dashboard/emergency',
    '/dashboard/route-planning',
    '/dashboard/ai-insight',
    '/dashboard/ai-models',
    '/dashboard/data-catalog',
    '/dashboard/plans',
    '/dashboard/account-security',
    '/dashboard/message-center',
    '/dashboard/todo',
    '/dashboard/approvals',
    '/dashboard/settings',
    '/dashboard/profile',
  ],
  maintenance: [
    '/dashboard',
    '/dashboard/real-time-map',
    '/dashboard/devices',
    '/dashboard/devices/ota',
    '/dashboard/data-sources',
    '/dashboard/data-catalog',
    '/dashboard/weather',
    '/dashboard/analytics',
    '/dashboard/system-monitor',
    '/dashboard/account-security',
    '/dashboard/backup-recovery',
    '/dashboard/message-center',
    '/dashboard/todo',
    '/dashboard/settings',
    '/dashboard/profile',
  ],
}

// ===X===X===X===X===X===X===X===X===X===X===X===X===X===X===X
// 细粒度权限矩阵（目标架构 45 界面全覆盖）
// ===X===X===X===X===X===X===X===X===X===X===X===X===X===X===X
export const ROLE_PERMISSION_MAP = {
  admin: [
    // 感知层
    'overview:view', 'overview:export',
    'map:view', 'map:export',
    // 认知层
    'ai:task:view', 'ai:task:create', 'ai:task:edit', 'ai:task:delete',
    'ai:result:view', 'ai:result:confirm',
    'ai:insight:view', 'ai:insight:refresh',
    'ai:model:view', 'ai:model:manage',
    // 决策层
    'hazards:view', 'hazards:create', 'hazards:edit', 'hazards:delete', 'hazards:export',
    'confirmation:view', 'confirmation:claim', 'confirmation:confirm', 'confirmation:reject', 'confirmation:transfer',
    'approval:view', 'approval:sign',
    // 行动层
    'warnings:view', 'warnings:confirm', 'warnings:dispatch', 'warnings:close', 'warnings:create', 'warnings:export',
    'notice:view', 'notice:edit',
    'plans:view', 'plans:edit', 'plans:launch',
    'emergency:view', 'emergency:dispatch', 'emergency:close', 'emergency:export',
    'resource:view', 'resource:dispatch',    // 神经层
    'devices:view', 'devices:create', 'devices:edit', 'devices:delete', 'devices:export', 'devices:control', 'devices:ota',
    'datasource:view', 'datasource:config',
    // 复盘层
    'analytics:view', 'analytics:export',
    'history:view', 'history:export',
    // 底座层
    'users:view', 'users:create', 'users:edit', 'users:delete',
    'roles:view', 'roles:edit',
    'rules:view', 'rules:edit',
    'audit:view', 'audit:export',
    'monitor:view',
    'backup:view', 'backup:manage',
    'settings:view', 'settings:edit',
    // 新增
    'reports:view', 'reports:create', 'reports:delete',
    'data:catalog:view',
    // 用户层
    'messages:view', 'messages:send',
    'todo:view',
    // P0 新增
    'route:view', 'route:export',
    'sign:view', 'sign:create', 'sign:confirm',
    'profile:view', 'profile:edit',
  ],
  attendant: [
    'overview:view', 'overview:export',
    'map:view',
    'ai:task:view', 'ai:task:create',
    'ai:insight:view', 'ai:insight:refresh',
    'hazards:view', 'hazards:create', 'hazards:edit',
    'confirmation:view', 'confirmation:claim', 'confirmation:confirm', 'confirmation:reject', 'confirmation:transfer',
    'warnings:view', 'warnings:confirm', 'warnings:dispatch', 'warnings:close', 'warnings:export',
    'notice:view', 'notice:edit',
    'plans:view', 'plans:edit', 'plans:launch',
    'emergency:view', 'emergency:dispatch',
    'devices:view',
    'weather:view',
    'analytics:view',
    'messages:view',
    'todo:view',
    'reports:view',
    'settings:view',
    'route:view', 'route:export',
    'sign:view', 'sign:create', 'sign:confirm',
    'profile:view', 'profile:edit',
  ],
  expert: [
    'overview:view',
    'hazards:view', 'hazards:create',
    'confirmation:view', 'confirmation:claim', 'confirmation:confirm', 'confirmation:reject', 'confirmation:transfer',
    'approval:view', 'approval:sign',
    'ai:insight:view',
    'ai:model:view',
    'plans:view',
    'data:catalog:view',
    'emergency:view', 'emergency:dispatch',
    'messages:view',
    'todo:view',
    'settings:view',
    'route:view',
    'sign:view', 'sign:create', 'sign:confirm',
    'profile:view', 'profile:edit',
  ],
  maintenance: [
    'overview:view',
    'map:view',
    'devices:view', 'devices:create', 'devices:edit', 'devices:delete', 'devices:export', 'devices:control',
    'devices:ota',
    'datasource:view', 'datasource:config',
    'data:catalog:view',
    'weather:view',
    'analytics:view',
    'monitor:view',
    'backup:view',
    'messages:view',
    'todo:view',
    'settings:view',
    'profile:view', 'profile:edit',
  ],
}

function safeParse(raw) {
  if (!raw) {return null}
  try {
    return JSON.parse(raw)
  } catch {
    return null
  }
}

export function getAuthToken() {
  return window.localStorage.getItem(TOKEN_KEY)
}

export function getAuthHeaders() {
  const token = getAuthToken()
  return token ? { Authorization: `Bearer ${token}` } : {}
}

export function getAuthUser() {
  return safeParse(window.localStorage.getItem(USER_KEY))
}

export function getAuthRole() {
  return getAuthUser()?.role ?? 'admin'
}

export function getRoleLabel(role) {
  return ROLE_OPTIONS.find((item) => item.value === role)?.label ?? '管理员'
}

export function getHomePathByRole(role) {
  return ROLE_HOME_MAP[role] ?? '/dashboard'
}

export function getAllowedMenuPaths(role) {
  return ROLE_MENU_MAP[role] ?? ROLE_MENU_MAP.admin
}

export function canAccessPath(role, path) {
  const allowedPaths = getAllowedMenuPaths(role)
  return allowedPaths.some(
    (allowed) => path === allowed || path.startsWith(`${allowed}/`),
  )
}

export function hasPermission(role, permission) {
  return (ROLE_PERMISSION_MAP[role] ?? ROLE_PERMISSION_MAP.admin).includes(permission)
}

export function setAuthSession(session) {
  window.localStorage.setItem(TOKEN_KEY, session.token)
  window.localStorage.setItem(USER_KEY, JSON.stringify(session.user))
}

export function clearAuthSession() {
  window.localStorage.removeItem(TOKEN_KEY)
  window.localStorage.removeItem(USER_KEY)
}

/**
 * 轻量级 JWT 过期检查：base64 解码 payload 段，对比 exp 与当前时间。
 * 不依赖第三方 JWT 库（避免引入运行时开销），仅做 exp 字段提取。
 *
 * @returns true 表示 token 已过期或格式异常（视为过期，触发重新登录）
 */
export function isTokenExpired() {
  const token = getAuthToken()
  if (!token) {return true}
  const parts = token.split('.')
  if (parts.length !== 3) {return true}
  try {
    // base64url → base64 兼容（JWT 用 url-safe base64）
    const base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const payload = JSON.parse(atob(base64))
    if (!payload.exp) {return false}  // 无 exp 字段视为永不过期（兼容老 token）
    return payload.exp * 1000 < Date.now()
  } catch {
    return true  // 格式异常视为过期，强制重新登录
  }
}

/** 模块级 401 重定向锁：防止并发请求同时触发多次 redirectToLogin。 */
let _redirecting = false
export function redirectToLogin() {
  if (_redirecting) {return}
  _redirecting = true
  clearAuthSession()
  window.location.replace('/login')
  // 重置锁（理论上已跳转，但以防万一页面未刷新）
  setTimeout(() => { _redirecting = false }, 2000)
}
