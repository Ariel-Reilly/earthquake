// @vitest-environment jsdom
/**
 * B5-4（L-14）：预警「AI 分析详情」组件测试（2026-11-13）。
 *
 * 用 @vue/test-utils + jsdom 挂载真实 SFC，mock 掉唯一的外部数据依赖
 * `dashboardApi.getWarningAnalysisDetail`，覆盖清单要求的三态：
 *   - 加载态：请求未回来时显示「正在加载」而不是空白；
 *   - 异常态：接口抛错时显示可读错误 + 可重试，不白屏；
 *   - 空态：AI / 指标 / 影像全缺失时显示「暂无…」，**不拿演示数据占位**；
 * 另覆盖正常态与「重试后可恢复」，防止错误态成为死路。
 *
 * 只给本文件启用 jsdom（文件级 @vitest-environment 注释），
 * 其余 57 个纯逻辑用例仍在默认 node 环境运行，互不影响。
 */
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { dashboardApi } from '../src/services/dashboardApi'
import WarningAnalysisDetail from '../src/components/warning/WarningAnalysisDetail.vue'

vi.mock('../src/services/dashboardApi', () => ({
  dashboardApi: { getWarningAnalysisDetail: vi.fn() },
}))

const EMPTY = Object.freeze({
  ai: null,
  metrics: { series: [], fallback: true },
  images: [],
  warning: null,
})

const mountDetail = () =>
  mount(WarningAnalysisDetail, {
    props: { warningId: 'W-TEST-1' },
  })

beforeEach(() => {
  vi.clearAllMocks()
})

describe('WarningAnalysisDetail 三态', () => {
  it('加载态：请求未返回时显示加载提示，不空白', () => {
    dashboardApi.getWarningAnalysisDetail.mockReturnValue(new Promise(() => {}))
    const wrapper = mountDetail()
    expect(wrapper.find('.state-card').exists()).toBe(true)
    expect(wrapper.text()).toContain('正在加载 AI 分析详情')
    expect(wrapper.find('.state-card.error').exists()).toBe(false)
    wrapper.unmount()
  })

  it('异常态：接口报错显示错误文案与重试按钮，不白屏', async () => {
    dashboardApi.getWarningAnalysisDetail.mockRejectedValue(new Error('分析接口 502'))
    const wrapper = mountDetail()
    await flushPromises()
    const card = wrapper.find('.state-card.error')
    expect(card.exists()).toBe(true)
    expect(card.text()).toContain('分析接口 502')
    expect(wrapper.find('.retry-btn').exists()).toBe(true)
    wrapper.unmount()
  })

  it('空态：AI / 指标 / 影像全缺失时只显示暂无，不展示假数据', async () => {
    dashboardApi.getWarningAnalysisDetail.mockResolvedValue(EMPTY)
    const wrapper = mountDetail()
    await flushPromises()
    const text = wrapper.text()
    expect(wrapper.find('.state-card').exists()).toBe(false)
    expect(text).toContain('暂无 AI 研判结果')
    expect(text).toContain('该灾害点暂无关联设备指标数据')
    expect(text).toContain('暂无现场影像')
    // 空态不应伪造分数 / 模型名
    expect(wrapper.find('.score-ring').exists()).toBe(false)
    expect(text).not.toContain('fusion-v3')
    wrapper.unmount()
  })

  it('正常态：有 AI 结论时渲染评分与模型标签', async () => {
    dashboardApi.getWarningAnalysisDetail.mockResolvedValue({
      ai: { riskScore: 82, model: 'fusion-v3', dimensions: [], reasoning: [], suggestions: [] },
      metrics: { series: [], fallback: false },
      images: [],
      warning: { title: '测试预警' },
    })
    const wrapper = mountDetail()
    await flushPromises()
    expect(wrapper.find('.state-card').exists()).toBe(false)
    expect(wrapper.text()).toContain('fusion-v3')
    expect(wrapper.find('.score-ring').exists()).toBe(true)
    expect(wrapper.find('.score-ring').text()).toContain('82')
    wrapper.unmount()
  })

  it('异常态点重试：API 恢复后错误消失并进入空态', async () => {
    dashboardApi.getWarningAnalysisDetail
      .mockRejectedValueOnce(new Error('网络抖动'))
      .mockResolvedValueOnce(EMPTY)
    const wrapper = mountDetail()
    await flushPromises()
    expect(wrapper.find('.state-card.error').exists()).toBe(true)

    await wrapper.find('.retry-btn').trigger('click')
    await flushPromises()
    expect(wrapper.find('.state-card.error').exists()).toBe(false)
    expect(wrapper.text()).toContain('暂无 AI 研判结果')
    expect(dashboardApi.getWarningAnalysisDetail).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })
})
