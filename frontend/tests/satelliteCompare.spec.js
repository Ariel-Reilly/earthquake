/**
 * B1 时相对比纯函数单测（2026-11-13）。
 *
 * 重点锁定两件事：
 *   ① AOI bbox 只从真实经纬度生成，脏数据不产生假坐标；
 *   ② 诚实降级判定（B1-4）——演示噪声图 / 未经网关重写的图一律不得进入差异展示。
 */
import { describe, expect, it, vi } from 'vitest'
import {
  buildBbox,
  describeJob,
  isRealImageJob,
  normalizeJob,
  pollJob,
  TERMINAL_STATUSES,
} from '../src/services/satelliteCompareUtils.js'

describe('buildBbox', () => {
  it('按灾害点经纬度生成 ±0.02° AOI', () => {
    expect(buildBbox({ longitude: 103.3, latitude: 30.8 })).toEqual([
      103.28, 30.78, 103.32, 30.82,
    ])
  })

  it('兼容 lon/lat 别名并支持自定义边距', () => {
    expect(buildBbox({ lon: 107, lat: 30 }, 0.1)).toEqual([106.9, 29.9, 107.1, 30.1])
  })

  it('无坐标 / 非法坐标 / 越界一律返回 null', () => {
    expect(buildBbox({ name: '无坐标点' })).toBeNull()
    expect(buildBbox({ longitude: 'abc', latitude: 30 })).toBeNull()
    expect(buildBbox({ longitude: 200, latitude: 30 })).toBeNull()
    expect(buildBbox({ longitude: 103, latitude: 30 }, 0)).toBeNull()
    expect(buildBbox(null)).toBeNull()
  })
})

describe('isRealImageJob（B1-4 诚实降级）', () => {
  const realJob = {
    status: 'succeeded',
    result: {
      dataSource: 'real',
      s2Url: '/api/ai/sentinel/image/s2_truecolor_abc.png',
      sarUrl: null,
    },
  }

  it('真实卫星影像 + 网关代理地址才放行', () => {
    expect(isRealImageJob(realJob)).toBe(true)
  })

  it('演示噪声图（mock）一律拒绝，即使 URL 已重写', () => {
    expect(isRealImageJob({ result: { ...realJob.result, dataSource: 'mock' } })).toBe(false)
  })

  it('上游原始 URL / file:// / 空结果都不放行', () => {
    expect(isRealImageJob({
      result: { ...realJob.result, s2Url: 'file:///tmp/s2_truecolor_abc.png' },
    })).toBe(false)
    expect(isRealImageJob({
      result: { ...realJob.result, s2Url: 'http://localhost:8001/sentinel_files/a.png' },
    })).toBe(false)
    expect(isRealImageJob({ result: null })).toBe(false)
    expect(isRealImageJob(null)).toBe(false)
  })
})

describe('normalizeJob / describeJob', () => {
  it('补齐缺省字段并保留上游结果', () => {
    expect(normalizeJob({ jobId: 'j1' }, 'fallback')).toEqual({
      jobId: 'j1', status: 'unknown', progress: 0, message: '', result: null,
    })
    expect(normalizeJob(null, 'fallback').jobId).toBe('fallback')
  })

  it('进度裁剪到 0..100，失败态带出上游 message', () => {
    expect(describeJob({ status: 'running', progress: 143 }).percent).toBe(100)
    expect(describeJob({ status: 'running', progress: 42 }).label).toBe('处理中 42%')
    expect(describeJob({ status: 'failed', message: '无可用场景' }).label).toBe('无可用场景')
    expect(describeJob({ status: 'succeeded' }).percent).toBe(100)
    expect(TERMINAL_STATUSES).toContain('succeeded')
  })
})

describe('pollJob', () => {
  it('第一次就是终态时只调用一次', async () => {
    const fetcher = vi.fn(async () => ({ jobId: 'j', status: 'succeeded', progress: 100 }))
    const job = await pollJob(fetcher, 'j', null, { intervalMs: 1 })
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(job.status).toBe('succeeded')
  })

  it('按序轮询到终态并逐次回调进度', async () => {
    const states = [
      { status: 'queued', progress: 0 },
      { status: 'running', progress: 50 },
      { status: 'succeeded', progress: 100 },
    ]
    let i = 0
    const fetcher = vi.fn(async () => states[i++])
    const seen = []
    const job = await pollJob(fetcher, 'j', (j) => seen.push(j.status), { intervalMs: 1 })
    expect(fetcher).toHaveBeenCalledTimes(3)
    expect(seen).toEqual(['queued', 'running', 'succeeded'])
    expect(job.progress).toBe(100)
  })

  it('AbortSignal 取消时立即停止并抛 AbortError', async () => {
    const ctrl = new AbortController()
    const fetcher = vi.fn(async () => {
      ctrl.abort()
      return { status: 'running', progress: 10 }
    })
    await expect(pollJob(fetcher, 'j', null, {
      signal: ctrl.signal, intervalMs: 1, timeoutMs: 200,
    })).rejects.toThrow(/取消/)
    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  it('超时抛可读中文错误', async () => {
    const fetcher = vi.fn(async () => ({ status: 'running', progress: 10 }))
    await expect(pollJob(fetcher, 'j', null, { intervalMs: 1, timeoutMs: 5 }))
      .rejects.toThrow(/超时/)
  })
})
