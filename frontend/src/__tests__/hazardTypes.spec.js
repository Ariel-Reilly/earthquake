/**
 * 灾种归一化契约（R11 E4）。
 *
 * AI 侧返回中文灾种，库表/字典/前端用英文码。三处映射（后端 HazardTypeCodes、
 * data.sql sys_dict、本文件）必须同口径，尤其「塌陷」：曾在前端被映射成 collapse，
 * 而别名表与后端把它归到 subsidence，导致同一份 AI 结果在不同页面显示不同灾种。
 */
import { describe, expect, it } from 'vitest'

import { HAZARD_TYPES, hazardTypeLabel, normalizeHazardType } from '../utils/hazardTypes'

const CODES = HAZARD_TYPES.map((o) => o.value)

describe('normalizeHazardType', () => {
  it('中文标签全部能映射到字典英文码', () => {
    for (const { value, label } of HAZARD_TYPES) {
      expect(normalizeHazardType(label)).toBe(value)
    }
  })

  it('已是英文码时原样返回（幂等）', () => {
    for (const code of CODES) {
      expect(normalizeHazardType(code)).toBe(code)
    }
  })

  it('「塌陷」归到 subsidence，与后端 HazardTypeCodes / WarningService 一致', () => {
    expect(normalizeHazardType('塌陷')).toBe('subsidence')
    expect(normalizeHazardType('地面塌陷')).toBe('subsidence')
  })

  it('无法判定的兜底词归为空，不伪造类型', () => {
    for (const raw of ['其他', '待定', '未知', '未识别', 'unknown', 'other']) {
      expect(normalizeHazardType(raw)).toBe('')
      expect(normalizeHazardType(raw, 'landslide')).toBe('landslide')
    }
    expect(normalizeHazardType(null)).toBe('')
    expect(normalizeHazardType('  ')).toBe('')
  })
})

describe('hazardTypeLabel', () => {
  it('英文码给中文标签，未知码原样返回', () => {
    expect(hazardTypeLabel('subsidence')).toBe('地面沉降')
    expect(hazardTypeLabel('sinkhole')).toBe('sinkhole')
    expect(hazardTypeLabel(null)).toBe('—')
  })
})
