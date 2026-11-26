/**
 * 预警等级映射契约（R11 F5）。
 *
 * 库表存中文等级（红色预警…），推送对象带英文 cssClass（red…），历史实现各页各写一份判定，
 * 导致「红色预警」在实时推送里被兜底成 blue、地图上红橙同色、移动端等级恒为「未知」。
 * 这里锁死唯一口径，并要求四种等级的色值两两不同。
 */
import { describe, expect, it } from 'vitest'

import {
  WARNING_LEVEL_COLORS,
  WARNING_LEVEL_MAP_COLORS,
  WARNING_LEVEL_TONES,
  normalizeWarningLevel,
  warningLevelColor,
  warningLevelLabel,
  warningLevelRank,
} from '../utils/warningLevel'

describe('normalizeWarningLevel', () => {
  it('识别后端中文等级文案', () => {
    expect(normalizeWarningLevel('红色预警')).toBe('red')
    expect(normalizeWarningLevel('橙色预警')).toBe('orange')
    expect(normalizeWarningLevel('黄色预警')).toBe('yellow')
    expect(normalizeWarningLevel('蓝色预警')).toBe('blue')
  })

  it('识别中文简写与英文 key（含后端 cssClass）', () => {
    expect(normalizeWarningLevel('红')).toBe('red')
    expect(normalizeWarningLevel('橙')).toBe('orange')
    expect(normalizeWarningLevel('RED')).toBe('red')
    expect(normalizeWarningLevel('orange')).toBe('orange')
    expect(normalizeWarningLevel('redAlert')).toBe('red')
    expect(normalizeWarningLevel('yellow_warning')).toBe('yellow')
  })

  it('空值与未知等级回落 blue，与后端 WarningService#cssClass 一致', () => {
    expect(normalizeWarningLevel(null)).toBe('blue')
    expect(normalizeWarningLevel('')).toBe('blue')
    expect(normalizeWarningLevel(undefined)).toBe('blue')
    expect(normalizeWarningLevel('待研判')).toBe('blue')
  })
})

describe('warningLevelLabel', () => {
  it('默认给出完整中文文案', () => {
    expect(warningLevelLabel('red')).toBe('红色预警')
    expect(warningLevelLabel('橙色')).toBe('橙色预警')
    expect(warningLevelLabel(null)).toBe('蓝色预警')
  })

  it('short 只给颜色词', () => {
    expect(warningLevelLabel('红色预警', { short: true })).toBe('红色')
    expect(warningLevelLabel('黄色预警', { short: true })).toBe('黄色')
  })
})

describe('warningLevelRank', () => {
  it('红 > 橙 > 黄 > 蓝', () => {
    expect(warningLevelRank('红色预警')).toBe(4)
    expect(warningLevelRank('橙色预警')).toBe(3)
    expect(warningLevelRank('黄色预警')).toBe(2)
    expect(warningLevelRank('蓝色预警')).toBe(1)
  })

  it('未知等级权重为 0，不会冒充蓝色参与排序', () => {
    expect(warningLevelRank('待研判')).toBe(0)
    expect(warningLevelRank('')).toBe(0)
    expect(warningLevelRank(null)).toBe(0)
  })
})

describe('色板', () => {
  it('四级色值两两不同（修复红橙同色）', () => {
    for (const table of [WARNING_LEVEL_COLORS, WARNING_LEVEL_MAP_COLORS]) {
      const values = WARNING_LEVEL_TONES.map((tone) => table[tone])
      expect(new Set(values).size).toBe(4)
    }
  })

  it('按 tone 取色，且 map 色板与 ui 色板分离', () => {
    expect(warningLevelColor('红色预警')).toBe(WARNING_LEVEL_COLORS.red)
    expect(warningLevelColor('red', 'map')).toBe(WARNING_LEVEL_MAP_COLORS.red)
    expect(warningLevelColor('待研判')).toBe(WARNING_LEVEL_COLORS.blue)
  })
})
