// @vitest-environment jsdom
/**
 * B5-4（L-14）：全局分级警报弹窗组件测试（2026-11-13）。
 *
 * 覆盖清单要求的「GlobalAlarmModal 四档渲染」：红（critical 满屏）/
 * 橙（warning）/ 黄（caution）/ 蓝（info）三档普通弹窗，以及
 * 「确认收到」关闭、查看详情跳转两条交互路径。
 *
 * 外部依赖全部用 vi.mock 隔离：
 *   - vue-router：只保留 useRouter().push；
 *   - useAlarmSound：不触碰真实 Web Audio（jsdom 没有 AudioContext）；
 *   - useWarningAlarm：捕获组件传入的 onAlarm 回调，测试里直接投喂一批预警，
 *     等价于实时服务推送，无需启动 WebSocket。
 * Teleport 被 stub 成就地渲染，便于用 wrapper.find 断言。
 */
import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import GlobalAlarmModal from '../src/components/alarm/GlobalAlarmModal.vue'

const h = vi.hoisted(() => ({
  push: vi.fn(() => Promise.resolve()),
  alarm: { callback: null },
  sound: { start: vi.fn(), stop: vi.fn(), resume: vi.fn(), unlock: vi.fn() },
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: h.push }),
}))

vi.mock('../src/composables/useAlarmSound', async () => {
  const { ref } = await import('vue')
  return {
    useAlarmSound: () => ({
      playing: ref(false),
      blocked: ref(false),
      unsupported: ref(false),
      ...h.sound,
    }),
  }
})

vi.mock('../src/composables/useWarningAlarm', () => ({
  useWarningAlarm: ({ onAlarm } = {}) => {
    h.alarm.callback = onAlarm
    return { dispose: vi.fn() }
  },
}))

const TeleportStub = { template: '<div class="teleport-root"><slot /></div>' }

function mountModal() {
  return mount(GlobalAlarmModal, {
    global: { stubs: { Teleport: TeleportStub } },
  })
}

/** 模拟实时服务推送一批预警，等 Vue 完成一次 DOM 更新 */
async function pushAlarms(batch) {
  expect(typeof h.alarm.callback).toBe('function')
  h.alarm.callback(batch)
  await nextTick()
}

function warningOf(severity, id = 1) {
  return {
    id,
    severity,
    title: `测试${severity}预警`,
    level: '红色预警',
    pointName: '测试灾害点',
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  h.alarm.callback = null
  document.body.innerHTML = ''
})

describe('GlobalAlarmModal 四档渲染', () => {
  it('初始无警报：不渲染任何弹窗', () => {
    const wrapper = mountModal()
    expect(wrapper.find('.alarm-overlay').exists()).toBe(false)
    expect(wrapper.find('.warn-overlay').exists()).toBe(false)
    wrapper.unmount()
  })

  it('红色 critical：满屏 alertdialog + 红色警报徽标 + 确认收到', async () => {
    const wrapper = mountModal()
    await pushAlarms([warningOf('critical', 11)])

    expect(wrapper.find('.alarm-overlay').exists()).toBe(true)
    expect(wrapper.find('[role="alertdialog"]').attributes('aria-modal')).toBe('true')
    expect(wrapper.find('.alarm-level').text()).toBe('红色警报')
    expect(wrapper.text()).toContain('确认收到')
    expect(wrapper.find('.warn-overlay').exists()).toBe(false)
    wrapper.unmount()
  })

  it.each([
    ['warning', '橙色预警'],
    ['caution', '黄色提示'],
    ['info', '蓝色关注'],
  ])('%s 档普通弹窗：颜色类名与徽标文案正确', async (severity, label) => {
    const wrapper = mountModal()
    await pushAlarms([warningOf(severity, 12)])

    expect(wrapper.find('.alarm-overlay').exists()).toBe(false)
    const card = wrapper.find('.warn-card')
    expect(card.exists()).toBe(true)
    expect(card.classes()).toContain(`severity-${severity}`)
    expect(wrapper.find('.warn-level').text()).toBe(label)
    expect(wrapper.text()).toContain('3 秒后自动打开预警详情页')
    wrapper.unmount()
  })

  it('红色点「确认收到」：弹窗关闭且不再显示', async () => {
    const wrapper = mountModal()
    await pushAlarms([warningOf('critical', 13)])
    expect(wrapper.find('.alarm-overlay').exists()).toBe(true)

    await wrapper.findAll('.alarm-btn').find((b) => b.text() === '确认收到').trigger('click')
    await nextTick()
    expect(wrapper.find('.alarm-overlay').exists()).toBe(false)
    expect(wrapper.find('.warn-overlay').exists()).toBe(false)
    wrapper.unmount()
  })

  it('普通弹窗点「查看详情」：关闭弹窗并跳转预警详情', async () => {
    const wrapper = mountModal()
    await pushAlarms([warningOf('warning', 42)])
    expect(wrapper.find('.warn-overlay').exists()).toBe(true)

    await wrapper.findAll('.warn-btn').find((b) => b.text() === '查看详情').trigger('click')
    await nextTick()
    expect(h.push).toHaveBeenCalledWith('/dashboard/warnings/42')
    expect(wrapper.find('.warn-overlay').exists()).toBe(false)
    wrapper.unmount()
  })
})
