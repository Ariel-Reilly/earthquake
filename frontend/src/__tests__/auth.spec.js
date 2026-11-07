/**
 * 认证 store 冒烟测试：JWT token 解析（exp 判定）与角色判断逻辑。
 *
 * 不引入 jsdom：用 vi.stubGlobal 提供最小 window.localStorage，
 * atob/btoa 由 Node 22 原生提供，因此只需 vitest + 项目已有的 pinia。
 * 覆盖 auth.js 里最容易回归的三处历史 bug：
 *   1) 后端下划线角色码与前端连字符映射不一致导致越权；
 *   2) 未知角色 `?? admin` 兜底导致拿到管理员全部菜单；
 *   3) canAccessPath 前缀匹配（含全角色共有的 /dashboard 根节点）导致越权放行。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import {
  normalizeRole,
  ROLE_MENU_MAP,
  ROLE_PERMISSION_MAP,
  getRoleLabel,
  hasPermission,
  canAccessPath,
  isTokenExpired,
  setAuthSession,
  clearAuthSession,
  getAuthHeaders,
} from '../stores/auth.js'

const storage = new Map()
vi.stubGlobal('window', {
  localStorage: {
    getItem: (key) => (storage.has(key) ? storage.get(key) : null),
    setItem: (key, value) => storage.set(key, String(value)),
    removeItem: (key) => storage.delete(key),
  },
  location: { replace: vi.fn() },
})

/** 构造一个只用于 payload 解析的 JWT（签名段不校验，故用占位串）。 */
function makeToken(payload) {
  const header = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' }))
  const body = btoa(JSON.stringify(payload))
  return `${header}.${body}.signature`
}

function login(payload = { sub: 'admin' }) {
  const token = makeToken(payload)
  setAuthSession({ token, user: { role: 'admin' } })
  return token
}

beforeEach(() => {
  storage.clear()
  setActivePinia(createPinia())
})

describe('token 解析', () => {
  it('无 token / 格式异常一律视为过期', () => {
    expect(isTokenExpired()).toBe(true)
    setAuthSession({ token: 'not-a-jwt', user: { role: 'admin' } })
    expect(isTokenExpired()).toBe(true)
    setAuthSession({ token: 'a.b', user: { role: 'admin' } })
    expect(isTokenExpired()).toBe(true)
  })

  it('按 payload.exp 判定有效期：未过期 false、已过期 true', () => {
    const nowSec = Math.floor(Date.now() / 1000)
    login({ sub: 'admin', exp: nowSec + 3600 })
    expect(isTokenExpired()).toBe(false)
    login({ sub: 'admin', exp: nowSec - 10 })
    expect(isTokenExpired()).toBe(true)
  })

  it('payload 无 exp 时不误判过期，且 Authorization 头随登录/登出变化', () => {
    const token = login({ sub: 'admin' })
    expect(isTokenExpired()).toBe(false)
    expect(getAuthHeaders().Authorization).toBe(`Bearer ${token}`)
    clearAuthSession()
    expect(getAuthHeaders()).toEqual({})
  })
})

describe('角色判断', () => {
  it('下划线角色码规范化为连字符，并统一大小写与空白', () => {
    expect(normalizeRole('system_admin')).toBe('system-admin')
    expect(normalizeRole('EMERGENCY_COMMANDER')).toBe('emergency-commander')
    expect(normalizeRole('  ai_engineer ')).toBe('ai-engineer')
    expect(normalizeRole(null)).toBe('')
  })

  it('未知角色不回落到 admin，菜单与权限均为空', () => {
    const role = normalizeRole('ghost_role')
    expect(ROLE_MENU_MAP[role] ?? []).toEqual([])
    expect(ROLE_PERMISSION_MAP[role] ?? []).toEqual([])
    expect(hasPermission('ghost_role', 'users:create')).toBe(false)
    expect(canAccessPath('ghost_role', '/dashboard/user-management')).toBe(false)
  })

  it('按角色给出正确的名称、权限与菜单路径（/dashboard/* 按角色区分）', () => {
    expect(getRoleLabel('admin')).toBe('超管')
    expect(hasPermission('system_admin', 'users:create')).toBe(true)
    expect(hasPermission('maintenance', 'users:create')).toBe(false)
    // 修复前：所有角色菜单都含 /dashboard，前缀展开会让任意 /dashboard/*
    // 对任何角色都返回 true；修复后只有真正列在菜单里的路径才放行。
    expect(canAccessPath('admin', '/dashboard/user-management')).toBe(true)
    expect(canAccessPath('maintenance', '/dashboard/user-management')).toBe(false)
    expect(canAccessPath('admin', '/admin/applications')).toBe(true)
    expect(canAccessPath('maintenance', '/admin/applications')).toBe(false)
  })

  it('路径按 / 边界匹配：兄弟路径不误命中，子路径仍可达，403 页不死锁', () => {
    expect(canAccessPath('admin', '/dashboard')).toBe(true)
    expect(canAccessPath('admin', '/dashboard-backup')).toBe(false)
    expect(canAccessPath('admin', '/dashboardX/user-management')).toBe(false)
    // 明确列在菜单里的项，其子路径（详情/子页）仍按边界放行
    expect(canAccessPath('admin', '/dashboard/warnings/W001/delivery')).toBe(true)
    expect(canAccessPath('maintenance', '/dashboard/devices/ota')).toBe(true)
    expect(canAccessPath('maintenance', '/dashboard/devices-ota')).toBe(false)
    // 框架级错误页对所有已登录角色可达，否则 403 重定向会自我拦截
    expect(canAccessPath('maintenance', '/dashboard/error/403')).toBe(true)
  })

  it('历史重定向别名对所有角色可达（R8 回归），但不放大目标页面权限', () => {
    // router/index.js 里 6 个 /dashboard/<alias> -> 真实页面的 redirect 不属于任何角色菜单。
    // 收紧 /dashboard 根节点后，若不显式放行，旧链接会 403 而不是 redirect。
    const aliases = [
      '/dashboard/history-hazards',
      '/dashboard/satellite-compare',
      '/dashboard/warning-config',
      '/dashboard/reports',
      '/dashboard/backup-recovery',
      '/dashboard/plans',
    ]
    aliases.forEach((p) => {
      expect(canAccessPath('maintenance', p)).toBe(true)
      expect(canAccessPath('system_admin', p)).toBe(true)
    })
    // 别名只是 redirect 入口；redirect 落地后的目标页仍按各自菜单判断
    expect(canAccessPath('maintenance', '/dashboard/user-management')).toBe(false)
  })
})
