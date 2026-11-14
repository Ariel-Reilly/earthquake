// @vitest-environment jsdom
/**
 * ModelManagePage 页面级回归（两处透传端点迁移为 `normalizeList` 之后）。
 *
 * 两处取数：
 *   - `listModels({})` → 版本库表（原 `records or 裸数组`）
 *   - `getModelTrainingJobs()` → 训练任务表（原 `裸数组 or records`）
 * 两者在 API 层都是**原样透传**，页面此前各自只认其中两种形态；
 * 若后端换成 `{list}` 或 `{items}`，表会静默变空。本用例逐形态验证渲染。
 *
 * 依赖隔离：`getModelFamilyStats`（顶部 KPI）一并 mock；
 * `driftMonitorService` 的 5 个具名导出在漂移 Tab 才用到，这里给空实现。
 */
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({
  getModelFamilyStats: vi.fn(),
  listModels: vi.fn(),
  getModelTrainingJobs: vi.fn(),
}))

vi.mock('../services/dashboardApi', () => ({ dashboardApi: api }))
vi.mock('../stores/auth', () => ({
  getAuthUser: () => ({ id: 'U-1', name: '张伟', role: 'admin' }),
  getAuthRole: () => 'admin',
  hasPermission: () => true,
}))
vi.mock('../services/_architecture/domain/driftMonitorService', () => ({
  detectDrift: vi.fn(),
  getDriftTrend: vi.fn(),
  triggerRetrain: vi.fn(),
  getRetrainStatus: vi.fn(),
  getDriftLevelInfo: vi.fn(),
}))

import ModelManagePage from '../views/ai/ModelManagePage.vue'

const MODEL = {
  versionId: 'v1.9.0-rc1',
  family: 'landslide',
  trainSize: 12000,
  valF1: 0.9321,
  latencyMs: 85,
  status: 'online',
  onlineAt: '2026-11-14T10:00:00+08:00',
}

const JOB = {
  id: 'JOB-1',
  family: 'landslide',
  dataset: 'ds-2026-11',
  epochs: 50,
  progress: 40,
  status: 'running',
}

let wrapper = null

/**
 * 挂载页面并等待 onMounted 的三个并发请求落地。
 * @param {{models?: *, jobs?: *}} payload 两处透传端点各自的返回形态
 */
async function mountWith({ models, jobs } = {}) {
  api.getModelFamilyStats.mockResolvedValue({
    onlineCount: 2,
    todayInferences: 1200,
    avgLatency: 85,
    综合F1: 0.93,
  })
  api.listModels.mockResolvedValue(models ?? { records: [] })
  api.getModelTrainingJobs.mockResolvedValue(jobs ?? { records: [] })
  wrapper = mount(ModelManagePage)
  await flushPromises()
  return wrapper
}

/** 切到「训练任务」Tab（训练表在 Tab2 内，默认只渲染版本库）。 */
async function switchToTraining() {
  const btn = wrapper.findAll('button').find((b) => b.text().includes('训练任务'))
  expect(btn, '页面上应有「训练任务」Tab').toBeTruthy()
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

describe('ModelManagePage 版本库形态（listModels 透传）', () => {
  it('records 形态渲染版本行', async () => {
    await mountWith({ models: { records: [MODEL], total: 1 }, jobs: { records: [JOB] } })
    expect(wrapper.text()).toContain('v1.9.0-rc1')
  })

  it('版本行渲染「激活 / 下线」按钮（PermissionButton 曾被漏 import，渲染为空）', async () => {
    await mountWith({ models: { records: [MODEL] }, jobs: { records: [JOB] } })
    expect(wrapper.text()).toContain('激活')
    expect(wrapper.text()).toContain('下线')
    // 组件真实解析（未解析时 Vue 只打 warn、不产出节点）
    expect(wrapper.findComponent({ name: 'PermissionButton' }).exists()).toBe(true)
  })

  it('list 形态渲染版本行（旧写法此处会静默变空）', async () => {
    await mountWith({ models: { list: [MODEL] }, jobs: { records: [JOB] } })
    expect(wrapper.text()).toContain('v1.9.0-rc1')
  })

  it('裸数组形态渲染版本行', async () => {
    await mountWith({ models: [MODEL], jobs: { records: [JOB] } })
    expect(wrapper.text()).toContain('v1.9.0-rc1')
  })
})

describe('ModelManagePage 训练任务形态（getModelTrainingJobs 透传）', () => {
  it('裸数组形态渲染任务行', async () => {
    await mountWith({ models: { records: [MODEL] }, jobs: [JOB] })
    await switchToTraining()
    expect(wrapper.text()).toContain('JOB-1')
  })

  it('items 形态渲染任务行（旧写法此处会静默变空）', async () => {
    await mountWith({ models: { records: [MODEL] }, jobs: { items: [JOB] } })
    await switchToTraining()
    expect(wrapper.text()).toContain('JOB-1')
    expect(wrapper.text()).not.toContain('暂无训练任务')
  })

  it('空数据时显示任务空态', async () => {
    await mountWith({ models: { records: [] }, jobs: { records: [] } })
    await switchToTraining()
    expect(wrapper.text()).toContain('暂无训练任务')
  })
})
