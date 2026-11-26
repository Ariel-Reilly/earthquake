/**
 * 漂移状态展示契约（R11 E5）。
 *
 * 后端返回的是 SLIGHT_DRIFT / SIGNIFICANT_DRIFT，而展示层历史上只认
 * SLIGHT / SIGNIFICANT，getDriftLevelInfo 对前者一律回落到"未知"，
 * 趋势线配色也因为字符串不等而恒为绿色。这里锁死归一规则。
 */
import { describe, expect, it } from 'vitest'

import {
  DRIFT_LEVELS,
  getDriftLevelInfo,
  normalizeDriftLevel,
} from '../services/_architecture/domain/driftMonitorService'

describe('normalizeDriftLevel', () => {
  it('把后端整体状态映射到展示级别', () => {
    expect(normalizeDriftLevel('SIGNIFICANT_DRIFT')).toBe('SIGNIFICANT')
    expect(normalizeDriftLevel('SLIGHT_DRIFT')).toBe('SLIGHT')
    expect(normalizeDriftLevel('INSUFFICIENT_DATA')).toBe('INSUFFICIENT')
    expect(normalizeDriftLevel('NO_BASELINE')).toBe('NO_BASELINE')
    expect(normalizeDriftLevel('normal')).toBe('NORMAL')
  })

  it('未知输入回落到 UNKNOWN', () => {
    expect(getDriftLevelInfo(undefined)).toBe(DRIFT_LEVELS.UNKNOWN)
    expect(getDriftLevelInfo('WHATEVER')).toBe(DRIFT_LEVELS.UNKNOWN)
  })
})

describe('getDriftLevelInfo', () => {
  it('显著漂移不再被显示为正常', () => {
    const info = getDriftLevelInfo('SIGNIFICANT_DRIFT')
    expect(info.label).toBe('显著漂移')
    expect(info.class).toBe('red')
  })

  it('无基线 / 样本不足都是灰色未知态，不是绿色正常态', () => {
    for (const status of ['NO_BASELINE', 'INSUFFICIENT_DATA']) {
      const info = getDriftLevelInfo(status)
      expect(info).not.toBe(DRIFT_LEVELS.NORMAL)
      expect(info.class).toBe('gray')
    }
  })
})
