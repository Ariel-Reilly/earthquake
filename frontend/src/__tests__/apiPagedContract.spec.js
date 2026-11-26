/**
 * 列表方法返回形态契约（CONTRACT §1 / API_INVENTORY 批次 1）。
 *
 * 目的：锁定「所有列表方法统一返回 PageResponse」——{records,total,page,size,pages}。
 * 不论后端给的是 records（真分页）、items（Map 内嵌）还是裸数组，前端 API 层必须归一。
 *
 * 覆盖：批次 1 的 18 个方法（A 组零消费 11 + B 组已归一消费 7）。
 * 依赖隔离：mock httpClient.request 与 runtime.getUseBackend（模块加载期取常量）。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'

const http = vi.hoisted(() => ({ request: vi.fn() }))

vi.mock('../services/_architecture/core/httpClient.js', () => ({
  request: http.request,
  downloadFile: vi.fn(),
}))

vi.mock('../services/_architecture/core/runtime.js', () => ({
  getUseBackend: () => true,
  isBackendRuntime: () => true,
  getRuntimeMode: () => 'backend',
  initBackendMode: vi.fn(),
  requireBackend: vi.fn(),
  // dashboardApi 用订阅维持 USE_BACKEND 的活绑定；mock 里给个空实现即可
  subscribeRuntime: () => () => {},
  API_BASE: '/api',
}))

const { dashboardApi } = await import('../services/dashboardApi.js')

/** 批次 1 统一的方法清单（与 docs/api/API_INVENTORY.md §5 的 A/B 组一致）。 */
const METHODS = [
  // A 组：零消费点
  'getSyncHistory',
  'listMyConfirmations',
  'listMyApprovals',
  'listMyPendingWarnings',
  'listPlans',
  'listPlanLaunches',
  'listBackups',
  'listBackupStrategies',
  'listRestoreHistory',
  'listReportTemplates',
  'listReportHistory',
  // B 组：消费点已用 normalizeList
  'listMyOpenTasks',
  'listModels',
  'getModelTrainingJobs',
  'listNoticeTemplates',
  'listActiveSessions',
  'listDataAssets',
  'listCatalogUsage',
]

/** 三种后端形态。 */
const SHAPES = {
  'records（真分页）': { records: [{ id: 1 }, { id: 2 }], total: 2, page: 1, size: 20, pages: 1 },
  'items（Map 内嵌）': { items: [{ id: 3 }], total: 1 },
  裸数组: [{ id: 4 }],
}

describe('列表方法统一返回 PageResponse', () => {
  beforeEach(() => {
    http.request.mockReset()
  })

  it.each(METHODS)('%s 存在且为函数', (name) => {
    expect(typeof dashboardApi[name]).toBe('function')
  })

  for (const [label, payload] of Object.entries(SHAPES)) {
    it.each(METHODS)(
      `%s 在「${label}」形态下归一为 {records,total,page,size,pages}`,
      async (name) => {
        http.request.mockResolvedValue(structuredClone(payload))
        const res = await dashboardApi[name]()

        // 键集合固定：只允许这 5 个字段，不新增 list/items 别名
        expect(Object.keys(res).sort()).toEqual(['page', 'pages', 'records', 'size', 'total'])
        expect(Array.isArray(res.records)).toBe(true)
        expect(res.records.length).toBeGreaterThan(0)
        expect(typeof res.total).toBe('number')
        expect(typeof res.page).toBe('number')
        expect(typeof res.size).toBe('number')
        expect(typeof res.pages).toBe('number')
      }
    )
  }

  it('items 形态：列表被提升到 records，total 透传', async () => {
    http.request.mockResolvedValue({ items: [{ id: 3 }], total: 1 })
    const res = await dashboardApi.listModels({})
    expect(res.records).toEqual([{ id: 3 }])
    expect(res.total).toBe(1)
  })

  it('裸数组：total/size 按长度补齐，pages 为 1', async () => {
    http.request.mockResolvedValue([{ id: 4 }, { id: 5 }])
    const res = await dashboardApi.listActiveSessions()
    expect(res.records).toHaveLength(2)
    expect(res.total).toBe(2)
    expect(res.size).toBe(2)
    expect(res.pages).toBe(1)
  })

  it('真分页：total/size/pages 原样保留，不被推导覆盖', async () => {
    http.request.mockResolvedValue({ records: [{ id: 1 }], total: 50, page: 2, size: 10, pages: 5 })
    const res = await dashboardApi.listBackups({})
    expect(res.total).toBe(50)
    expect(res.page).toBe(2)
    expect(res.size).toBe(10)
    expect(res.pages).toBe(5)
  })

  it('空响应：不返回 undefined，得到空分页对象', async () => {
    http.request.mockResolvedValue(null)
    const res = await dashboardApi.listReportTemplates({})
    expect(res).toEqual({ records: [], total: 0, page: 1, size: 1, pages: 1 })
  })
})
