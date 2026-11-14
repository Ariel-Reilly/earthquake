// @vitest-environment jsdom
/**
 * PersonnelManagePage 页面级回归（`data.list ?? data` 迁移为 `normalizeList` 之后）。
 *
 * 该页只有一处取数（`loadPersonnel`），但形态比前两页更杂：
 *   - `dashboardApi.getPersonnel()` 对后端裸数组会包装成 `{ list }`；
 *   - 若后端走分页分支则返回 `PageResponse{records}`。
 *
 * 旧写法 `data.list ?? data` 在拿到 `{ records }` 时会把**对象**当数组传给 `.map()`，
 * 抛 TypeError → 被 catch 成「人员列表加载失败」，即一个真实的潜在缺陷。
 * 迁移到 `normalizeList` 后两种形态都正常，因此本用例重点锁这条差异。
 *
 * 注意：页面 `onMounted` 会 `setInterval(30s)` 自动刷新，用例必须 unmount 清理定时器，
 * 否则 vitest 会残留句柄。
 */
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({
  getPersonnel: vi.fn(),
}))

vi.mock('../services/dashboardApi', () => ({ dashboardApi: api }))

import PersonnelManagePage from '../views/system/PersonnelManagePage.vue'

const PERSON = {
  id: 'U-1',
  name: '张伟',
  lon: 106.53,
  lat: 29.56,
  status: '巡检中',
  lastSeen: new Date().toISOString(),
}

let wrapper = null

/**
 * 挂载页面并等待 onMounted 的取数落地。
 * @param {*} payload getPersonnel 返回值
 */
async function mountWith(payload) {
  api.getPersonnel.mockResolvedValue(payload)
  wrapper = mount(PersonnelManagePage)
  await flushPromises()
  return wrapper
}

/** 断言渲染出 1 名人员且无错误条。 */
function expectOnePerson(label) {
  const rows = wrapper.findAll('.pmp-person')
  expect(rows.length, `${label} 应渲染 1 名人员`).toBe(1)
  expect(wrapper.text()).toContain('张伟')
  expect(wrapper.find('.pmp-error').exists()).toBe(false)
  // 首张 KPI 卡是「值守人员」总数
  expect(wrapper.findAll('.pmp-kpi-value')[0].text()).toBe('1')
}

beforeEach(() => {
  vi.clearAllMocks()
})

afterEach(() => {
  // 清理 30s 自动刷新定时器
  wrapper?.unmount()
  wrapper = null
})

describe('PersonnelManagePage 人员列表形态', () => {
  it('{ list } 形态（getPersonnel 对裸数组的包装）渲染人员', async () => {
    await mountWith({ list: [PERSON] })
    expectOnePerson('list')
  })

  it('{ records } 形态（PageResponse）也能渲染——旧写法此处会抛错', async () => {
    await mountWith({ records: [PERSON], total: 1, page: 1, size: 10, pages: 1 })
    expectOnePerson('records')
  })

  it('裸数组形态渲染人员', async () => {
    await mountWith([PERSON])
    expectOnePerson('array')
  })
})

describe('PersonnelManagePage 空态与错误态', () => {
  it('payload 为 null 时显示空态、不报错', async () => {
    await mountWith(null)
    expect(wrapper.findAll('.pmp-person').length).toBe(0)
    expect(wrapper.find('.pmp-empty').exists()).toBe(true)
    expect(wrapper.find('.pmp-error').exists()).toBe(false)
    expect(wrapper.findAll('.pmp-kpi-value')[0].text()).toBe('0')
  })

  it('接口抛错时显示错误条并清空列表', async () => {
    api.getPersonnel.mockRejectedValue(new Error('boom'))
    wrapper = mount(PersonnelManagePage)
    await flushPromises()

    expect(wrapper.find('.pmp-error').exists()).toBe(true)
    expect(wrapper.text()).toContain('人员列表加载失败')
    expect(wrapper.text()).toContain('boom')
    expect(wrapper.findAll('.pmp-person').length).toBe(0)
  })
})
