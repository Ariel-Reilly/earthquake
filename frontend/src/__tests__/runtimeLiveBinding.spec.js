/**
 * 运行模式的活绑定契约（R11 F4）。
 *
 * 背景：dashboardApi 曾以 `export const USE_BACKEND = getUseBackend()` 导出，
 * 该值在模块首次求值时被冻结。auto 模式下启动探测失败会先降级 mock，
 * 后端恢复后 runtime 切回 backend，但所有 `import { USE_BACKEND }` 的分支
 * 仍读到 false —— 界面徽标显示"真实数据"，实际全走 mock。
 *
 * 这里锁死：runtime 模式变化后，导入方读到的 USE_BACKEND 必须跟着变。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

function healthResponse(ok) {
  return {
    ok,
    status: ok ? 200 : 503,
    json: async () => ({ code: 0, data: { status: ok ? 'UP' : 'DOWN' } }),
  }
}

describe('runtime 模式切换对 USE_BACKEND 的可见性', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.stubEnv('VITE_USE_BACKEND', '')
    // node 环境下 runtime 的复检定时器需要 window.setInterval
    vi.stubGlobal('window', {
      setInterval: () => 0,
      clearInterval: () => {},
    })
  })

  afterEach(() => {
    vi.unstubAllEnvs()
    vi.unstubAllGlobals()
  })

  it('mock 降级 → 后端恢复后，导入方读到的值同步更新', async () => {
    const fetchMock = vi.fn().mockResolvedValue(healthResponse(false))
    vi.stubGlobal('fetch', fetchMock)

    const runtime = await import('../services/_architecture/core/runtime.js')
    const api = await import('../services/dashboardApi.js')

    await runtime.initBackendMode()
    expect(runtime.getUseBackend()).toBe(false)
    expect(api.USE_BACKEND).toBe(false)

    // 后端恢复后可探活
    fetchMock.mockResolvedValue(healthResponse(true))
    await runtime.recheckBackendMode()
    // 订阅通知走 queueMicrotask
    await Promise.resolve()

    expect(runtime.getUseBackend()).toBe(true)
    expect(api.USE_BACKEND).toBe(true)
  })
})
