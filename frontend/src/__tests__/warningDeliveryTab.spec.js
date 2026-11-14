// @vitest-environment jsdom
/**
 * WarningDeliveryTab 页面级回归（`loadChannel` 的 records/list/裸数组三级链迁移为 `normalizeList`）。
 *
 * 该组件按 5 个渠道（sms/app/email/led/webhook）分别请求 `getDeliveryRecords`，
 * 原写法是 `records → list → 裸数组 → []` 的嵌套三元，现统一委托 `normalizeList`。
 * 用例逐渠道投喂不同形态，确保三条历史分支都仍能渲染，而不是只测默认 tab。
 *
 * 依赖隔离：`RouterLink` 用 stub 就地渲染；`PermissionButton` 走 stores/auth 的纯函数，
 * jsdom 下可直接渲染，无需 pinia。
 */
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({
  getWarningDetail: vi.fn(),
  getDeliverySummary: vi.fn(),
  getDeliveryRecords: vi.fn(),
  redeliver: vi.fn(),
}))

vi.mock('../services/dashboardApi', () => ({ dashboardApi: api }))

import WarningDeliveryTab from '../components/WarningDeliveryTab.vue'

const DETAIL = { item: { id: 'W-1', title: '歇马滑坡橙色预警', level: '橙色', point: '歇马' } }
const SUMMARY = { shouldDeliver: 3, delivered: 2, read: 1, confirmed: 1, channels: [] }

const SMS_ROWS = [
  { id: 'D-1', phone: '13800000001', sentAt: '11-14 14:43', status: '已送达' },
  { id: 'D-2', phone: '13800000002', sentAt: '11-14 14:44', status: '已送达' },
]
const APP_ROWS = [{ id: 'D-3', user: '张伟', status: '已点击', clicked: '是' }]
const EMAIL_ROWS = [{ id: 'D-4', email: 'ops@example.com', status: '已送达' }]

let wrapper = null

/**
 * 按渠道投喂不同返回形态；未指定的渠道返回空数组（触发空态）。
 * @param {Record<string, unknown>} byChannel 渠道 → 返回值
 */
function mockChannels(byChannel) {
  api.getDeliveryRecords.mockImplementation((id, opts) => {
    const v = byChannel[opts?.channel]
    if (v instanceof Error) {
      return Promise.reject(v)
    }
    return Promise.resolve(v ?? [])
  })
}

/**
 * 挂载组件并等待 detail / summary / 5 个渠道全部落地。
 * @param {Record<string, unknown>} byChannel 渠道 → 返回值
 */
async function mountWith(byChannel) {
  api.getWarningDetail.mockResolvedValue(DETAIL)
  api.getDeliverySummary.mockResolvedValue(SUMMARY)
  mockChannels(byChannel)
  wrapper = mount(WarningDeliveryTab, {
    props: { warningId: 'W-1' },
    global: {
      stubs: {
        RouterLink: { template: '<a><slot /></a>' },
        // PermissionButton 内部读 pinia 的 auth store（getAuthRole），与本用例无关；
        // stub 掉避免为权限门禁在 jsdom 里额外装 pinia。
        PermissionButton: true,
      },
    },
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

describe('WarningDeliveryTab 渠道记录形态', () => {
  it('records 形态（默认 sms 渠道）渲染 2 行并带手机号', async () => {
    await mountWith({ sms: { records: SMS_ROWS, total: 2 } })

    const rows = wrapper.findAll('.data-table tbody tr')
    expect(rows.length).toBe(2)
    expect(wrapper.text()).toContain('13800000001')
    // 分段控制器上的渠道计数取自 records.length
    expect(wrapper.findAll('.segment-count')[0].text()).toBe('2')
  })

  it('list 形态（切到 app 渠道）渲染 1 行', async () => {
    await mountWith({ sms: { records: SMS_ROWS }, app: { list: APP_ROWS } })

    await wrapper.findAll('.segment-item')[1].trigger('click')
    await flushPromises()

    const rows = wrapper.findAll('.data-table tbody tr')
    expect(rows.length).toBe(1)
    expect(wrapper.text()).toContain('张伟')
    expect(wrapper.findAll('.segment-count')[1].text()).toBe('1')
  })

  it('裸数组形态（切到 email 渠道）渲染 1 行', async () => {
    await mountWith({ sms: { records: SMS_ROWS }, email: EMAIL_ROWS })

    await wrapper.findAll('.segment-item')[2].trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.data-table tbody tr').length).toBe(1)
    expect(wrapper.text()).toContain('ops@example.com')
  })

  it('空记录显示该渠道空态文案', async () => {
    await mountWith({ sms: { records: [] } })

    expect(wrapper.find('.data-table').exists()).toBe(false)
    expect(wrapper.text()).toContain('暂无短信发送记录')
  })

  it('单渠道请求失败时降级为空态、不影响其他渠道', async () => {
    await mountWith({ sms: new Error('boom'), app: { records: APP_ROWS } })

    // 默认 sms 失败 → 空态
    expect(wrapper.text()).toContain('暂无短信发送记录')
    // 其他渠道仍正常取到数据
    await wrapper.findAll('.segment-item')[1].trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.data-table tbody tr').length).toBe(1)
  })
})
