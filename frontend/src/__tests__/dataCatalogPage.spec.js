// @vitest-environment jsdom
/**
 * DataCatalogPage 页面级回归（三处 `records / 裸数组` 分支迁移为 `normalizeList` 之后）。
 *
 * 该页有三处独立取数：资源列表（`listDataAssets`）、使用排行（`listCatalogUsage`，
 * 分别用于 Tab2 与趋势抽屉）。三处原写法都是
 * `Array.isArray(data?.records) ? data.records : Array.isArray(data) ? data : []`，
 * 现统一委托 `normalizeList`，本用例逐处断言渲染结果，防止「函数改对了、页面没接上」。
 *
 * 后端契约核对：
 *   - `GET /api/catalog/assets` → `PageResponse{records,...}`（CatalogController:199）
 *   - `GET /api/catalog/usage`  → 同上形态
 * 用例同时覆盖裸数组形态（历史容错路径），确保迁移前后等价。
 */
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({
  getCatalogSummary: vi.fn(),
  listDataAssets: vi.fn(),
  listCatalogUsage: vi.fn(),
}))

vi.mock('../services/dashboardApi', () => ({ dashboardApi: api }))

import DataCatalogPage from '../views/data/DataCatalogPage.vue'

const ASSET = {
  id: 'A-1',
  name: '监测点位移小时表',
  type: '表',
  category: '监测',
  source: 'TDengine',
  description: '位移监测',
  tags: ['位移'],
  fieldCount: 12,
}

const USAGE = {
  id: 'A-1',
  name: '监测点位移小时表',
  caller: 'ai-service',
  type: '表',
  category: '监测',
  source: 'TDengine',
  trend7d: [1, 2, 3],
}

/**
 * 挂载页面并等待 onMounted 的三个请求落地。
 * @param {*} assetsPayload listDataAssets 返回值
 * @param {*} usagePayload listCatalogUsage 返回值
 */
async function mountWith(assetsPayload, usagePayload = { records: [USAGE] }) {
  api.getCatalogSummary.mockResolvedValue({
    total: 1,
    tables: 1,
    fields: 12,
    lastUpdated: '11-14 19:00',
  })
  api.listDataAssets.mockResolvedValue(assetsPayload)
  api.listCatalogUsage.mockResolvedValue(usagePayload)
  const wrapper = mount(DataCatalogPage)
  await flushPromises()
  return wrapper
}

/** 资源列表 Tab 是否渲染出 1 行且带资源名。 */
function expectOneAssetRow(wrapper, label) {
  const rows = wrapper.findAll('.catalog-table tbody tr')
  expect(rows.length, `${label} 应渲染 1 行`).toBe(1)
  expect(wrapper.text()).toContain('监测点位移小时表')
  expect(wrapper.find('.empty-row').exists()).toBe(false)
  // Tab1 计数来自 filteredAssets.length
  expect(wrapper.find('.tab-btn .count').text()).toBe('1')
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('DataCatalogPage 资源列表分页形态', () => {
  it('records 形态（PageResponse，真实契约）渲染列表', async () => {
    expectOneAssetRow(
      await mountWith({ records: [ASSET], total: 1, page: 1, size: 10, pages: 1 }),
      'records'
    )
  })

  it('裸数组形态渲染列表', async () => {
    expectOneAssetRow(await mountWith([ASSET]), 'array')
  })

  it('items 形态也兼容（归一层超集）', async () => {
    expectOneAssetRow(await mountWith({ items: [ASSET], total: 1 }), 'items')
  })

  it('空 records 显示空态而非报错', async () => {
    const wrapper = await mountWith({ records: [], total: 0, page: 1, size: 10, pages: 0 })
    expect(wrapper.find('.catalog-table').exists()).toBe(false)
    expect(wrapper.text()).toContain('暂无符合条件的资源')
  })
})

describe('DataCatalogPage 使用排行与容错', () => {
  it('切到使用排行 Tab 渲染 usage（同一归一层）', async () => {
    const wrapper = await mountWith({ records: [ASSET] }, { records: [USAGE], total: 1 })
    await wrapper.findAll('.tab-btn')[1].trigger('click')
    await flushPromises()

    const rows = wrapper.findAll('.catalog-table tbody tr')
    expect(rows.length).toBe(1)
    expect(wrapper.text()).toContain('ai-service')
    // Tab2 计数
    expect(wrapper.findAll('.tab-btn .count')[1].text()).toBe('1')
  })

  it('点击「查看趋势」二次拉取 usage，趋势抽屉按新数据渲染（openTrend 路径）', async () => {
    api.getCatalogSummary.mockResolvedValue({ total: 1, tables: 1, fields: 12, lastUpdated: '—' })
    api.listDataAssets.mockResolvedValue({ records: [ASSET] })
    // 两次调用返回不同长度：列表 3 条 trend，openTrend 二次拉取 5 条；
    // 抽屉柱子数只可能来自第二次，才能真正证明 openTrend 的取数分支被执行。
    api.listCatalogUsage.mockReset()
    api.listCatalogUsage
      .mockResolvedValueOnce({ records: [{ ...USAGE, trend7d: [1, 2, 3] }] })
      .mockResolvedValueOnce({
        records: [{ id: 'A-1', name: USAGE.name, trend7d: [5, 6, 7, 8, 9] }],
      })

    const wrapper = mount(DataCatalogPage)
    await flushPromises()

    await wrapper.findAll('.tab-btn')[1].trigger('click')
    await flushPromises()

    const trendBtn = wrapper.findAll('button').find((b) => b.text().includes('查看趋势'))
    expect(trendBtn, '使用排行行内应有「查看趋势」按钮').toBeTruthy()
    await trendBtn.trigger('click')
    await flushPromises()

    expect(api.listCatalogUsage).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.trend-drawer').exists()).toBe(true)
    expect(wrapper.findAll('.trend-drawer .spark-bar').length).toBe(5)
    expect(wrapper.text()).not.toContain('无趋势数据')
  })

  it('接口抛错时资源列表降级为空态、页面不崩', async () => {
    api.getCatalogSummary.mockResolvedValue(null)
    api.listDataAssets.mockRejectedValue(new Error('boom'))
    api.listCatalogUsage.mockResolvedValue(null)
    const wrapper = mount(DataCatalogPage)
    await flushPromises()

    expect(wrapper.find('.catalog-table').exists()).toBe(false)
    expect(wrapper.text()).toContain('暂无符合条件的资源')
  })
})
