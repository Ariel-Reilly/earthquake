// @vitest-environment jsdom
/**
 * OtaManagePage 页面级回归（本地 `toRecords` 迁移为 `normalizeList` 之后）。
 *
 * 该页一次并发取三类数据：固件包、升级任务、设备（`loadAll`），三者的历史返回形态不一，
 * 原本由本地 `toRecords()`（`array → records → items → list`）兜着，逻辑与 `normalizeList` 等价。
 * 迁移后调用点不变（仍叫 `toRecords`），因此用例按「三条数据源 × 不同形态」分别验证，
 * 而不是只测固件包这一条。
 *
 * 依赖隔离：
 *   - `stores/auth` 的 `getAuthUser/hasPermission` 内部走 pinia，这里整体 mock 掉；
 *   - `ConfirmDialog` 与本用例无关，stub。
 */
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({
  listFirmware: vi.fn(),
  listOtaTasks: vi.fn(),
  getDevices: vi.fn(),
}))

vi.mock('../services/dashboardApi', () => ({ dashboardApi: api }))
vi.mock('../stores/auth', () => ({
  getAuthUser: () => ({ id: 'U-1', name: '张伟', role: 'admin' }),
  hasPermission: () => true,
}))

import OtaManagePage from '../views/device/OtaManagePage.vue'

const FW = {
  id: 'FW-1',
  version: 'v1.9.0',
  deviceType: 'station',
  sizeKb: 2048,
  sha256: 'abcdef1234567890abcdef',
  uploadedAt: '11-14 10:00',
  remark: '测试包',
}
const TASK = {
  id: 'T-1',
  deviceName: '歇马站-01',
  deviceId: 'D-1',
  firmwareVersion: 'v1.9.0',
  status: 'running',
  updatedAt: '11-14 11:00',
}
const DEV = { id: 'D-1', deviceName: '歇马站-01', deviceType: 'station' }

let wrapper = null

/**
 * 挂载页面并等待 loadAll 的三个并发请求落地。
 * @param {{fw?: *, task?: *, dev?: *}} payload 三条数据源各自的返回形态
 */
async function mountWith({ fw, task, dev } = {}) {
  api.listFirmware.mockResolvedValue(fw ?? { records: [] })
  api.listOtaTasks.mockResolvedValue(task ?? { records: [] })
  api.getDevices.mockResolvedValue(dev ?? { records: [] })
  wrapper = mount(OtaManagePage, {
    global: { stubs: { ConfirmDialog: true } },
  })
  await flushPromises()
  // loadAll 的 finally 里有 waitForSkeleton(280ms)，需越过最短展示时间再断言
  await new Promise((r) => setTimeout(r, 320))
  await flushPromises()
  return wrapper
}

/** 切到「升级任务」Tab。 */
async function switchToTaskTab() {
  await wrapper.findAll('.tab-bar button')[1].trigger('click')
  await flushPromises()
}

beforeEach(() => {
  vi.clearAllMocks()
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

describe('OtaManagePage 三类数据源的返回形态', () => {
  it('固件=records、任务=items：两个 Tab 都能渲染（同时覆盖两种形态）', async () => {
    await mountWith({ fw: { records: [FW], total: 1 }, task: { items: [TASK] }, dev: [DEV] })

    // 默认固件 Tab
    expect(wrapper.findAll('.task-table tbody tr').length).toBe(1)
    expect(wrapper.text()).toContain('v1.9.0')

    await switchToTaskTab()
    expect(wrapper.findAll('.task-table tbody tr').length).toBe(1)
    expect(wrapper.text()).toContain('歇马站-01')
    expect(wrapper.text()).toContain('T-1')
  })

  it('固件=list、设备=裸数组：固件 Tab 仍渲染 1 行', async () => {
    await mountWith({ fw: { list: [FW] }, task: { records: [] }, dev: [DEV] })

    expect(wrapper.findAll('.task-table tbody tr').length).toBe(1)
    expect(wrapper.text()).toContain('v1.9.0')
    expect(wrapper.find('.empty-row').exists()).toBe(false)
  })

  it('固件为空 records 时显示空态文案', async () => {
    await mountWith({ fw: { records: [] }, task: { records: [] }, dev: [] })

    expect(wrapper.find('.task-table').exists()).toBe(false)
    expect(wrapper.text()).toContain('暂无固件包')
  })

  it('接口抛错时显示可重试错误态并 emit notify', async () => {
    api.listFirmware.mockResolvedValue({ records: [FW] })
    api.listOtaTasks.mockRejectedValue(new Error('boom'))
    api.getDevices.mockResolvedValue([DEV])
    wrapper = mount(OtaManagePage, { global: { stubs: { ConfirmDialog: true } } })
    await flushPromises()
    await new Promise((r) => setTimeout(r, 320))
    await flushPromises()

    // Promise.all 整体失败 → 三个列表清空，落到持久错误态（role=alert + 重试按钮）
    expect(wrapper.findAll('.task-table tbody tr').length).toBe(0)
    const errBox = wrapper.find('[role="alert"]')
    expect(errBox.exists()).toBe(true)
    expect(errBox.text()).toContain('boom')
    expect(errBox.find('button').text()).toContain('重试')
    expect(wrapper.emitted('notify')).toBeTruthy()
    expect(String(wrapper.emitted('notify')[0][0])).toContain('boom')
  })
})
