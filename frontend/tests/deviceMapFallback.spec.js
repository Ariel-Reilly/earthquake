import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchDeviceMap } from '../src/services/_architecture/domain/deviceMapService.js'
import { proxyPathFor } from '../src/services/_architecture/domain/driftMonitorService.js'

const REAL = {
  devices: [
    {
      deviceCode: 'DV-001',
      deviceName: '嘉陵江水位计-01',
      onlineStatus: '离线',
      longitude: 106.55,
      latitude: 29.56,
    },
  ],
  stats: { total: 1, online: 0, offline: 1, fault: 0, mobile: 0, fixed: 1, trajectoryDevices: 0 },
  generatedAt: '2026-11-14T00:00:00Z',
}

function stubFetch(impl) {
  vi.stubGlobal('fetch', vi.fn(impl))
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('设备地图：真实接口失败时不静默回退 mock（2026-11-14 审查 P0-2）', () => {
  it('接口 200 + code=0 时返回真实数据，且 demo=false', async () => {
    stubFetch(async () => ({ ok: true, json: async () => ({ code: 0, data: REAL }) }))
    const data = await fetchDeviceMap('http://test/api')
    expect(data.demo).toBe(false)
    expect(data.devices[0].deviceCode).toBe('DV-001')
    expect(data.stats.total).toBe(1)
  })

  it('HTTP 500 时抛错，绝不返回 12 台成都 mock 设备', async () => {
    stubFetch(async () => ({ ok: false, status: 500 }))
    await expect(fetchDeviceMap('http://test/api')).rejects.toThrow(/不回退演示数据/)
    await expect(fetchDeviceMap('http://test/api')).rejects.toThrow(/HTTP 500/)
  })

  it('响应 code!=0 时抛错', async () => {
    stubFetch(async () => ({
      ok: true,
      json: async () => ({ code: 500, message: '系统繁忙，请稍后重试' }),
    }))
    await expect(fetchDeviceMap('http://test/api')).rejects.toThrow(/系统繁忙/)
  })

  it('网络异常时抛错（不吞错造假）', async () => {
    stubFetch(async () => { throw new Error('ECONNREFUSED') })
    await expect(fetchDeviceMap('http://test/api')).rejects.toThrow(/ECONNREFUSED/)
  })

  it('默认不放行任何演示设备编码', async () => {
    stubFetch(async () => ({ ok: false, status: 500 }))
    const outcome = await fetchDeviceMap('http://test/api').catch((e) => e)
    expect(outcome).toBeInstanceOf(Error)
    expect(String(outcome.message)).not.toContain('GPS-001')
  })
})

describe('drift 后端回退路径（2026-11-14 审查 P0-5）', () => {
  it('path 已含 /drift 时不再重复拼接', () => {
    expect(proxyPathFor('/drift/detect')).toBe('/ai/drift/detect')
    expect(proxyPathFor('/drift/trend/model-1?days=30')).toBe('/ai/drift/trend/model-1?days=30')
  })

  it('拼出的路径不含 /drift/drift', () => {
    expect(proxyPathFor('/drift/detect')).not.toContain('/drift/drift')
  })
})
