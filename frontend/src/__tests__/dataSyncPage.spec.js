// @vitest-environment jsdom
/**
 * DataSyncPage 页面级回归（`toList` 迁移为 `normalizeList` 之后）。
 *
 * 为什么要组件级测试：`normalizeList` 的纯函数单测只能证明「函数返回数组」，
 * 不能证明「页面真的把三种分页形态渲染成了列表」。本用例挂载真实 SFC，
 * mock 掉 dashboardApi，断言 DOM 结果，覆盖迁移前的等价行为：
 *   - records / items / list / 裸数组 四种形态都能出表格行；
 *   - null（无列表字段）走空态，不误报错误；
 *   - 抛错走错误态并 emit notify。
 */
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({
  getSyncTasks: vi.fn(),
  getSyncStats: vi.fn(),
  getSyncAnomalies: vi.fn(),
  triggerSyncTask: vi.fn(),
  pauseSyncTask: vi.fn(),
}))

vi.mock('../services/dashboardApi.js', () => ({ dashboardApi: api }))

import DataSyncPage from '../views/data/DataSyncPage.vue'

const TASK = {
  taskId: 'T-1',
  taskName: '订单库→数仓',
  status: 'RUNNING',
  recordsSynced: 1200,
  source: 'mysql-order',
  target: 'dwh',
  delaySeconds: 30,
  lastSyncAt: '2026-11-14T03:00:00Z',
}

/**
 * 挂载页面并等待 onMounted 的 Promise.all 落地。
 * @param {*} tasksPayload getSyncTasks 的返回值（四种分页形态之一）
 * @returns {Promise<import('@vue/test-utils').VueWrapper>}
 */
async function mountWith(tasksPayload) {
  api.getSyncTasks.mockResolvedValue(tasksPayload)
  api.getSyncStats.mockResolvedValue({ totalTasks: 1 })
  api.getSyncAnomalies.mockResolvedValue({ records: [] })
  const wrapper = mount(DataSyncPage)
  await flushPromises()
  return wrapper
}

/** 断言：渲染出一行任务，且不是错误态/空态。 */
function expectOneRow(wrapper, label) {
  const rows = wrapper.findAll('.ds-table tbody tr')
  expect(rows.length, `${label} 应渲染 1 行`).toBe(1)
  expect(wrapper.text()).toContain('订单库→数仓')
  expect(wrapper.text()).not.toContain('同步状态加载失败')
  expect(wrapper.find('.ds-empty').exists()).toBe(false)
  // 统计卡「同步链路」总数取自任务数（stats.totalTasks=1 与列表长度一致）
  expect(wrapper.find('.ds-stat-card .stat-num').text()).toBe('1')
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('DataSyncPage 分页形态渲染', () => {
  it('records 形态（PageResponse）渲染列表', async () => {
    expectOneRow(
      await mountWith({ records: [TASK], total: 1, page: 1, size: 10, pages: 1 }),
      'records'
    )
  })

  it('items 形态渲染列表', async () => {
    expectOneRow(await mountWith({ items: [TASK], total: 1 }), 'items')
  })

  it('list 形态渲染列表', async () => {
    expectOneRow(await mountWith({ list: [TASK], total: 1 }), 'list')
  })

  it('裸数组形态渲染列表', async () => {
    expectOneRow(await mountWith([TASK]), 'array')
  })
})

describe('DataSyncPage 异常与空态', () => {
  it('payload 为 null 时走空态而不是错误态', async () => {
    const wrapper = await mountWith(null)
    expect(wrapper.find('.ds-table').exists()).toBe(false)
    expect(wrapper.text()).toContain('暂无同步任务')
    expect(wrapper.text()).not.toContain('同步状态加载失败')
  })

  it('接口抛错时显示错误态并 emit notify', async () => {
    api.getSyncTasks.mockRejectedValue(new Error('boom'))
    api.getSyncStats.mockResolvedValue(null)
    api.getSyncAnomalies.mockResolvedValue(null)
    const wrapper = mount(DataSyncPage)
    await flushPromises()

    expect(wrapper.text()).toContain('同步状态加载失败')
    expect(wrapper.text()).toContain('boom')
    expect(wrapper.emitted('notify')).toBeTruthy()
    expect(wrapper.emitted('notify')[0][0]).toBe('boom')
  })
})
