const TOKEN_KEY = 'geosentry_token'
const USER_KEY = 'geosentry_user'

export const ROLE_OPTIONS = [
  { value: 'admin', label: '管理员', description: '拥有全部模块访问权限' },
  { value: 'attendant', label: '值班员', description: '负责态势、巡检和预警处置' },
  { value: 'maintenance', label: '运维员', description: '负责设备和基础设施运维' },
]

export const ROLE_HOME_MAP = {
  admin: '/',
  attendant: '/',
  maintenance: '/devices',
}

export const ROLE_MENU_MAP = {
  admin: ['/', '/hazards', '/tasks', '/devices', '/warnings', '/emergency', '/analytics', '/settings'],
  attendant: ['/', '/hazards', '/tasks', '/warnings', '/emergency', '/analytics', '/settings'],
  maintenance: ['/', '/hazards', '/devices', '/analytics', '/settings'],
}

export const ROLE_PERMISSION_MAP = {
  admin: [
    'overview:view', 'overview:export',
    'hazards:view', 'hazards:create', 'hazards:edit', 'hazards:delete', 'hazards:export',
    'tasks:view', 'tasks:create', 'tasks:edit', 'tasks:delete', 'tasks:export',
    'devices:view', 'devices:create', 'devices:edit', 'devices:delete', 'devices:export',
    'warnings:view', 'warnings:confirm', 'warnings:dispatch', 'warnings:close', 'warnings:export',
    'emergency:view', 'emergency:dispatch', 'emergency:close', 'emergency:export',
    'analytics:view', 'analytics:export',
    'settings:view',
  ],
  attendant: [
    'overview:view', 'overview:export',
    'hazards:view', 'hazards:create', 'hazards:edit', 'hazards:export',
    'tasks:view', 'tasks:create', 'tasks:edit', 'tasks:export',
    'warnings:view', 'warnings:confirm', 'warnings:dispatch', 'warnings:close', 'warnings:export',
    'emergency:view', 'emergency:dispatch', 'emergency:close',
    'analytics:view', 'analytics:export',
    'settings:view',
  ],
  maintenance: [
    'overview:view', 'overview:export',
    'hazards:view', 'hazards:export',
    'devices:view', 'devices:create', 'devices:edit', 'devices:delete', 'devices:export',
    'analytics:view', 'analytics:export',
    'settings:view',
  ],
}

function safeParse(raw) {
  if (!raw) return null
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
  return ROLE_HOME_MAP[role] ?? '/'
}

export function getAllowedMenuPaths(role) {
  return ROLE_MENU_MAP[role] ?? ROLE_MENU_MAP.admin
}

export function canAccessPath(role, path) {
  const allowedPaths = getAllowedMenuPaths(role)
  return allowedPaths.some((allowed) => (allowed === '/' ? path === '/' : path === allowed || path.startsWith(`${allowed}/`)))
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
