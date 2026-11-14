// @vitest-environment jsdom
/**
 * NoticeTemplatePage 页面级回归（`data?.records ?? data?.items` 迁移为 `normalizeList` 之后）。
 *
 * 背景：`dashboardApi.listNoticeTemplates` 是**未归一的透传**（直接 `return request(...)`），
 * 后端当前返回 `PageResponse{records}`，所以旧写法「能跑」；但一旦后端改成分页以外的形态
 * （`{list}` 或裸数组），表格会**静默变空**且不报错。迁移后四种形态都兼容。
 *
 * 依赖隔离：`stores/auth` 内部走 pinia（`getAuthUser/hasPermission`）→ 整体 mock；
 * `ConfirmDialog` 与用例无关 → stub。
 */
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({
  listNoticeTemplates: vi.fn(),
}))

vi.mock('../services/dashboardApi', () => ({ dashboardApi: api }))
vi.mock('../stores/auth', () => ({
  getAuthUser: () => ({ id: 'U-1', name: '张伟', role: 'admin' }),
  hasPermission: () => true,
}))

import NoticeTemplatePage from '../views/system/NoticeTemplatePage.vue'

const TPL = {
  id: 'T-1',
  name: '橙色预警短信模板',
  hazardType: '滑坡',
  level: '橙色',
  enabled: true,
  channels: { sms: '【地脉智巡】橙色预警，请注意防范。' },
}

let wrapper = null

/**
 * 挂载页面并等待 onMounted 的列表请求落地。
 * @param {*} payload listNoticeTemplates 返回值
 */
async function mountWith(payload) {
  api.listNoticeTemplates.mockResolvedValue(payload)
  wrapper = mount(NoticeTemplatePage, { global: { stubs: { ConfirmDialog: true } } })
  await flushPromises()
  return wrapper
}

/** 断言渲染出 1 张模板卡且不是错误/空态。 */
function expectOneCard(label) {
  expect(wrapper.findAll('.tpl-card').length, `${label} 应渲染 1 张模板卡`).toBe(1)
  expect(wrapper.text()).toContain('橙色预警短信模板')
  expect(wrapper.find('.empty-row').exists()).toBe(false)
  expect(wrapper.find('.load-error-hint').exists()).toBe(false)
}

beforeEach(() => {
  vi.clearAllMocks()
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

describe('NoticeTemplatePage 列表形态', () => {
  it('records 形态（后端当前契约）渲染模板卡', async () => {
    expectOneCard(
      await mountWith({ records: [TPL], total: 1, page: 1, size: 20, pages: 1 }),
      'records'
    )
  })

  it('items 形态渲染模板卡', async () => {
    expectOneCard(await mountWith({ items: [TPL], total: 1 }), 'items')
  })

  it('list 形态渲染模板卡（旧写法此处会静默变空）', async () => {
    expectOneCard(await mountWith({ list: [TPL] }), 'list')
  })

  it('裸数组形态渲染模板卡（旧写法此处会静默变空）', async () => {
    expectOneCard(await mountWith([TPL]), 'array')
  })
})

describe('NoticeTemplatePage 空态与错误态', () => {
  it('空 records 显示空态文案', async () => {
    await mountWith({ records: [] })
    expect(wrapper.find('.card-grid').exists()).toBe(false)
    expect(wrapper.text()).toContain('没有匹配的模板')
  })

  it('接口抛错时显示错误提示并 emit notify', async () => {
    api.listNoticeTemplates.mockRejectedValue(new Error('boom'))
    wrapper = mount(NoticeTemplatePage, { global: { stubs: { ConfirmDialog: true } } })
    await flushPromises()

    expect(wrapper.find('.load-error-hint').exists()).toBe(true)
    expect(wrapper.text()).toContain('boom')
    expect(wrapper.emitted('notify')).toBeTruthy()
    expect(wrapper.findAll('.tpl-card').length).toBe(0)
  })
})
