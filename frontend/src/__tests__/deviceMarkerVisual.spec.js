/**
 * 设备地图 marker 视觉规范单测（node 环境，无需 jsdom / OpenLayers）。
 *
 * 锁定 2026-11-14 演示 polish 的可读性决策：
 *   描边对比色、选中态品牌橙、名称标签回退、离线与在线半径差异、白色光晕底圈。
 * 只测纯函数 deviceMarkerVisual（不触碰 OL 渲染），保证在 node 下可稳定运行。
 */
import { describe, expect, it } from 'vitest'
import { deviceMarkerVisual } from '../services/_architecture/domain/deviceMapService.js'

describe('deviceMarkerVisual', () => {
  it('离线固定设备：浅蓝灰填充 + 深色对比描边 + 名称标签', () => {
    const v = deviceMarkerVisual({
      onlineStatus: '离线',
      deviceName: '翻斗雨量计',
      deviceCode: 'RAIN-002',
      mobile: false,
    })
    expect(v.color).toBe('#94a3b8') // statusColor('离线')
    expect(v.radius).toBe(6) // 固定非选中
    expect(v.ringColor).toBe('#1f2937') // 普通态深色描边
    expect(v.ringWidth).toBe(1.6)
    expect(v.haloFill).toContain('255,255,255') // 白色光晕底圈
    expect(v.label).toBe('翻斗雨量计')
  })

  it('在线移动设备：绿色填充 + 半径 7', () => {
    const v = deviceMarkerVisual({
      onlineStatus: '在线',
      deviceName: '巡查无人机-01',
      mobile: true,
    })
    expect(v.color).toBe('#16a34a')
    expect(v.radius).toBe(7)
  })

  it('故障设备：红色填充', () => {
    expect(deviceMarkerVisual({ onlineStatus: '故障' }).color).toBe('#dc2626')
  })

  it('选中态：品牌橙描边 + 半径放大到 10', () => {
    const v = deviceMarkerVisual({ onlineStatus: '在线', mobile: false }, true)
    expect(v.radius).toBe(10)
    expect(v.ringColor).toBe('#f97316')
    expect(v.ringWidth).toBe(3)
  })

  it('无设备名时回退到设备编码作为标签', () => {
    expect(deviceMarkerVisual({ onlineStatus: '在线', deviceCode: 'GPS-009' }).label).toBe(
      'GPS-009'
    )
  })

  it('光晕半径始终大于主点半径，保证影像上"浮起来"', () => {
    const v = deviceMarkerVisual({ onlineStatus: '离线', mobile: false })
    expect(v.haloRadius).toBeGreaterThan(v.radius)
  })

  it('入参缺省不抛错，给出安全默认', () => {
    const v = deviceMarkerVisual()
    expect(v.color).toBe('#64748b')
    expect(v.label).toBe('')
    expect(v.haloRadius).toBeGreaterThan(v.radius)
  })
})
