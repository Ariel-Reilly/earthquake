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

// ===X===X===X===X===X===X===X===X===X===X===X===X===X===X===X
// 持久化 key 与角色定义
// ===X===X===X===X===X===X===X===X===X===X===X===X===X===X===X
const TOKEN_KEY = 'geosentry_token'
const USER_KEY = 'geosentry_user'

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

export const ROLE_MENU_MAP = {
  admin: [
    '/dashboard', '/dashboard/real-time-map', '/dashboard/ai-tasks', '/dashboard/ai-results',
    '/dashboard/ai-insight', '/dashboard/hazards', '/dashboard/confirmation-workbench',
    '/dashboard/warnings', '/dashboard/emergency', '/dashboard/resource-dispatch',
    '/dashboard/devices', '/dashboard/data-sources', '/dashboard/weather',
    '/dashboard/analytics', '/dashboard/history-hazards', '/dashboard/user-management',
    '/dashboard/audit-log', '/dashboard/system-monitor', '/dashboard/message-center',
    '/dashboard/todo', '/dashboard/approvals', '/dashboard/ai-models', '/dashboard/reports',
    '/dashboard/data-catalog', '/dashboard/notice-templates', '/dashboard/plans',
    '/dashboard/devices/ota', '/dashboard/backup-recovery', '/dashboard/account-security',
    '/dashboard/settings', '/dashboard/profile', '/dashboard/route-planning',
    '/dashboard/confirmation-sign',
  ],
  attendant: [
    '/dashboard', '/dashboard/real-time-map', '/dashboard/ai-tasks', '/dashboard/ai-insight',
    '/dashboard/reports', '/dashboard/hazards', '/dashboard/confirmation-workbench',
    '/dashboard/warnings', '/dashboard/notice-templates', '/dashboard/plans',
    '/dashboard/emergency', '/dashboard/devices', '/dashboard/weather',
    '/dashboard/analytics', '/dashboard/account-security', '/dashboard/message-center',
    '/dashboard/todo', '/dashboard/approvals', '/dashboard/settings', '/dashboard/profile',
    '/dashboard/route-planning', '/dashboard/confirmation-sign',
  ],
  expert: [
    '/dashboard', '/dashboard/hazards', '/dashboard/confirmation-workbench',
    '/dashboard/confirmation-sign', '/dashboard/emergency', '/dashboard/route-planning',
    '/dashboard/ai-insight', '/dashboard/ai-models', '/dashboard/data-catalog',
    '/dashboard/plans', '/dashboard/account-security', '/dashboard/message-center',
    '/dashboard/todo', '/dashboard/approvals', '/dashboard/settings', '/dashboard/profile',
  ],
  maintenance: [
    '/dashboard', '/dashboard/real-time-map', '/dashboard/devices', '/dashboard/devices/ota',
    '/dashboard/data-sources', '/dashboard/data-catalog', '/dashboard/weather',
    '/dashboard/analytics', '/dashboard/system-monitor', '/dashboard/account-security',
    '/dashboard/backup-recovery', '/dashboard/message-center', '/dashboard/todo',
    '/dashboard/settings', '/dashboard/profile',
  ],
}

export const ROLE_PERMISSION_MAP = {
  admin: [
    'overview:view','overview:export','map:view','map:export',
    'ai:task:view','ai:task:create','ai:task:edit','ai:task:delete',
    'ai:result:view','ai:result:confirm','ai:insight:view','ai:insight:refresh',
    'ai:model:view','ai:model:manage',
    'hazards:view','hazards:create','hazards:edit','hazards:delete','hazards:export',
    'confirmation:view','confirmation:claim','confirmation:confirm','confirmation:reject','confirmation:transfer',
    'approval:view','approval:sign',
    'warnings:view','warnings:confirm','warnings:dispatch','warnings:close','warnings:create','warnings:export',
    'notice:view','notice:edit','plans:view','plans:edit','plans:launch',
    'emergency:view','emergency:dispatch','emergency:close','emergency:export',
    'resource:view','resource:dispatch',
    'devices:view','devices:create','devices:edit','devices:delete','devices:export','devices:control','devices:ota',
    'datasource:view','datasource:config',
    'analytics:view','analytics:export','history:view','history:export',
    'users:view','users:create','users:edit','users:delete','roles:view','roles:edit',
    'rules:view','rules:edit','audit:view','audit:export','monitor:view',
    'backup:view','backup:manage','settings:view','settings:edit',
    'reports:view','reports:create','reports:delete','data:catalog:view',
    'messages:view','messages:send','todo:view',
    'route:view','route:export','sign:view','sign:create','sign:confirm',
    'profile:view','profile:edit',
  ],
  attendant: [
    'overview:view','overview:export','map:view',
    'ai:task:view','ai:task:create','ai:insight:view','ai:insight:refresh',
    'hazards:view','hazards:create','hazards:edit',
    'confirmation:view','confirmation:claim','confirmation:confirm','confirmation:reject','confirmation:transfer',
    'warnings:view','warnings:confirm','warnings:dispatch','warnings:close','warnings:export',
    'notice:view','notice:edit','plans:view','plans:edit','plans:launch',
    'emergency:view','emergency:dispatch','devices:view','weather:view','analytics:view',
    'messages:view','todo:view','reports:view','settings:view',
    'route:view','route:export','sign:view','sign:create','sign:confirm',
    'profile:view','profile:edit',
  ],
  expert: [
    'overview:view','hazards:view','hazards:create',
    'confirmation:view','confirmation:claim','confirmation:confirm','confirmation:reject','confirmation:transfer',
    'approval:view','approval:sign','ai:insight:view','ai:model:view',
    'plans:view','data:catalog:view','emergency:view','emergency:dispatch',
    'messages:view','todo:view','settings:view','route:view',
    'sign:view','sign:create','sign:confirm','profile:view','profile:edit',
  ],
  maintenance: [
    'overview:view','map:view',
    'devices:view','devices:create','devices:edit','devices:delete','devices:export','devices:control','devices:ota',
    'datasource:view','datasource:config','data:catalog:view','weather:view','analytics:view',
    'monitor:view','backup:view','messages:view','todo:view','settings:view',
    'profile:view','profile:edit',
  ],
}

// ===X===X===X===X===X===X===X===X===X===X===X===X===X===X===X
// Pinia store — 唯一状态源
// ===X===X===X===X===X===X===X===X===X===X===X===X===X===X===X

function safeParse(raw) {
  if (!raw) return null
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
    role: (state) => state.user?.role ?? 'admin',
    homePath() { return ROLE_HOME_MAP[this.role] ?? '/dashboard' },
    allowedPaths() { return ROLE_MENU_MAP[this.role] ?? ROLE_MENU_MAP.admin },
    permissions() { return ROLE_PERMISSION_MAP[this.role] ?? ROLE_PERMISSION_MAP.admin },
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

// ===X===X===X===X===X===X===X===X===X===X===X===X===X===X===X
// 兼容层: 旧函数 API → store (Step 4 引入,后续 Step 8 移除)
// ===X===X===X===X===X===X===X===X===X===X===X===X===X===X===X

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
  if (role && role !== useAuthStore().role) {
    return (ROLE_PERMISSION_MAP[role] ?? ROLE_PERMISSION_MAP.admin).includes(permission)
  }
  return useAuthStore().hasPermission(permission)
}
export function canAccessPath(role, path) {
  if (role && role !== useAuthStore().role) {
    const allowed = ROLE_MENU_MAP[role] ?? ROLE_MENU_MAP.admin
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
  if (!token) return true
  const parts = token.split('.')
  if (parts.length !== 3) return true
  try {
    const base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const payload = JSON.parse(atob(base64))
    if (!payload.exp) return false
    return payload.exp * 1000 < Date.now()
  } catch {
    return true
  }
}

/** 模块级 401 重定向锁:防止并发请求同时触发多次 redirectToLogin。 */
let _redirecting = false
export function redirectToLogin() {
  if (_redirecting) return
  _redirecting = true
  clearAuthSession()
  window.location.replace('/login')
  setTimeout(() => { _redirecting = false }, 2000)
}
