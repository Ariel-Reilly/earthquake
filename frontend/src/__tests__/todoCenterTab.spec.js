// @vitest-environment jsdom
/**
 * TodoCenterTab 组件级回归（`listMyOpenTasks` 的 4 形态分支迁移为 `normalizeList` 之后）。
 *
 * 背景：`dashboardApi.listMyOpenTasks` 是**未归一的透传**（`return request("/todo/tasks")`），
 * 旧写法 `Array.isArray(data) ? data : (data?.list ?? data?.items ?? data?.records ?? [])`
 * 把四种形态都猜了一遍；迁移后统一由归一层判断，本用例逐形态验证渲染结果。
 *
 * 依赖隔离：`vue-router` 的 `useRouter().push` 只在点开待办时用到，这里 mock 掉。
 *
 * 注：fixture 常量故意命名为 `TODO`——它同时也是注释门禁的「误报回归样本」：
 * `check-comments.mjs` 只在注释/docstring 内匹配 TODO，代码标识符不应被计入
 * （selftest 的 F/G 场景与此对应）。改名会让这条真实样本消失，请勿重命名。
 */
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({
  listMyOpenTasks: vi.fn(),
}))

const push = vi.hoisted(() => vi.fn())

vi.mock('../services/dashboardApi', () => ({ dashboardApi: api }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

import TodoCenterTab from '../components/notification/tabs/TodoCenterTab.vue'

const TODO = {
  id: 'TD-1',
  title: '确认歇马滑坡橙色预警',
  description: '张伟',
  priority: 'high',
  status: '待处理',
  dueDate: '2026-11-14T18:00:00+08:00',
}

let wrapper = null

/**
 * 挂载组件并等待 onMounted 的取数落地。
 * @param {*} payload listMyOpenTasks 返回值
 */
async function mountWith(payload) {
  api.listMyOpenTasks.mockResolvedValue(payload)
  wrapper = mount(TodoCenterTab)
  await flushPromises()
  return wrapper
}

/** 断言渲染出 1 条待办。 */
function expectOneTodo(label) {
  const items = wrapper.findAll('.todo-item')
  expect(items.length, `${label} 应渲染 1 条待办`).toBe(1)
  expect(wrapper.text()).toContain('确认歇马滑坡橙色预警')
  expect(wrapper.find('.empty-state').exists()).toBe(false)
}

beforeEach(() => {
  vi.clearAllMocks()
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

describe('TodoCenterTab 待办形态', () => {
  it('records 形态渲染待办', async () => {
    expectOneTodo(await mountWith({ records: [TODO], total: 1 }), 'records')
  })

  it('items 形态渲染待办', async () => {
    expectOneTodo(await mountWith({ items: [TODO], total: 1 }), 'items')
  })

  it('list 形态渲染待办', async () => {
    expectOneTodo(await mountWith({ list: [TODO] }), 'list')
  })

  it('裸数组形态渲染待办', async () => {
    expectOneTodo(await mountWith([TODO]), 'array')
  })
})

describe('TodoCenterTab 空态与错误态', () => {
  it('payload 为 null 时显示空态', async () => {
    await mountWith(null)
    expect(wrapper.findAll('.todo-item').length).toBe(0)
    expect(wrapper.find('.empty-state').exists()).toBe(true)
  })

  it('接口抛错时显示空态并 emit notify', async () => {
    api.listMyOpenTasks.mockRejectedValue(new Error('boom'))
    wrapper = mount(TodoCenterTab)
    await flushPromises()

    expect(wrapper.find('.empty-state').exists()).toBe(true)
    expect(wrapper.emitted('notify')).toBeTruthy()
    expect(String(wrapper.emitted('notify')[0][0])).toContain('boom')
  })
})
