// @vitest-environment jsdom
/**
 * SettingsPage 页面级回归（`listActiveSessions` 的 4 形态分支迁移为 `normalizeList` 之后）。
 *
 * 后端 `GET /api/account/sessions` 返回的是 `ApiResponse<List<...>>`（裸数组），
 * 而 `dashboardApi.listActiveSessions` 是原样透传。旧写法
 * `Array.isArray(data) ? data : (data?.items ?? data?.records ?? data?.sessions ?? [])`
 * 里 `items/records/sessions` 三个分支实际不可达（后端不返回这些键），
 * 迁移后统一交给归一层，既去掉死分支，也让 `{list}` 这类形态不再是盲区。
 *
 * 依赖隔离：
 *   - `stores/auth` 内部走 pinia → 整体 mock；
 *   - `useTheme` 只操作 document/localStorage，jsdom 可直跑；
 *   - `getSystemSettings` / `getSystemMonitor` 在 onMounted 一并被调用，给最小返回。
 */
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({
  getSystemSettings: vi.fn(),
  getSystemMonitor: vi.fn(),
  listActiveSessions: vi.fn(),
  revokeSession: vi.fn(),
}))

vi.mock('../services/dashboardApi', () => ({ dashboardApi: api }))
vi.mock('../stores/auth', () => ({
  getAuthUser: () => ({ id: 'U-1', name: '张伟', role: 'admin' }),
  getRoleLabel: (role) => (role === 'admin' ? '管理员' : role),
}))

import SettingsPage from '../views/settings/SettingsPage.vue'

const SESSION = {
  id: 'S-1',
  device: 'Chrome · Windows',
  location: '10.0.0.8',
  current: true,
  lastActiveAt: '2026-11-14T19:00:00+08:00',
}
const SESSION2 = {
  id: 'S-2',
  device: 'iPhone · iOS 18',
  location: '10.0.0.9',
  current: false,
  lastActiveAt: '2026-11-14T18:00:00+08:00',
}

let wrapper = null

/**
 * 挂载页面并等待 onMounted 的三个请求落地。
 * @param {*} sessionsPayload listActiveSessions 返回值
 */
async function mountWith(sessionsPayload) {
  api.getSystemSettings.mockResolvedValue({})
  api.getSystemMonitor.mockResolvedValue({ services: [] })
  api.listActiveSessions.mockResolvedValue(sessionsPayload)
  wrapper = mount(SettingsPage)
  await flushPromises()
  return wrapper
}

/** 切到「账号与安全」Tab（登录设备列表在该 Tab 内）。 */
async function switchToSecurity() {
  const btn = wrapper.findAll('.settings-tab').find((b) => b.text().includes('账号与安全'))
  expect(btn, '页面上应有「账号与安全」Tab').toBeTruthy()
  await btn.trigger('click')
  await flushPromises()
}

beforeEach(() => {
  vi.clearAllMocks()
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

describe('SettingsPage 活跃会话形态', () => {
  it('裸数组形态（后端真实契约）渲染会话行', async () => {
    await mountWith([SESSION, SESSION2])
    await switchToSecurity()

    const rows = wrapper.findAll('.device-row')
    expect(rows.length).toBe(2)
    expect(wrapper.text()).toContain('Chrome · Windows')
    expect(wrapper.find('.settings-error').exists()).toBe(false)
  })

  it('records 形态渲染会话行', async () => {
    await mountWith({ records: [SESSION] })
    await switchToSecurity()
    expect(wrapper.findAll('.device-row').length).toBe(1)
  })

  it('items 形态渲染会话行', async () => {
    await mountWith({ items: [SESSION] })
    await switchToSecurity()
    expect(wrapper.findAll('.device-row').length).toBe(1)
  })

  it('list 形态渲染会话行（旧写法此处会静默为空）', async () => {
    await mountWith({ list: [SESSION] })
    await switchToSecurity()
    expect(wrapper.findAll('.device-row').length).toBe(1)
    expect(wrapper.text()).toContain('Chrome · Windows')
  })

  it('payload 为 null 时显示空态而非报错', async () => {
    await mountWith(null)
    await switchToSecurity()
    expect(wrapper.findAll('.device-row').length).toBe(0)
    expect(wrapper.text()).toContain('暂无活跃会话')
    expect(wrapper.find('.settings-error').exists()).toBe(false)
  })

  it('接口抛错时显示错误文案', async () => {
    api.getSystemSettings.mockResolvedValue({})
    api.getSystemMonitor.mockResolvedValue({ services: [] })
    api.listActiveSessions.mockRejectedValue(new Error('boom'))
    wrapper = mount(SettingsPage)
    await flushPromises()
    await switchToSecurity()

    expect(wrapper.find('.settings-error').exists()).toBe(true)
    expect(wrapper.text()).toContain('boom')
  })
})
