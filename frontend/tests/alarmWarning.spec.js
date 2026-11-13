/**
 * 全局分级警报判定逻辑单测。
 *
 * 分级口径（与后端 AI 研判分数区间一致，见 ai-service fusion_engine.py）：
 *   ≥80 红（critical，满屏警报） · 60-80 橙（warning） · 35-60 黄（caution） · <35 蓝（info）
 * 橙/黄/蓝都是普通弹窗 + 3 秒跳详情；等级文字优先，AI 原始等级 / cssClass 兜底。
 */

import { describe, expect, it } from 'vitest'
import {
  AI_RISK_BANDS,
  ALARM_SEVERITY,
  alarmSeverityOf,
  extractWarnings,
  isAlarmWorthy,
  normalizeWarningId,
  partitionAlarmWarnings,
  pickNewAlarmWarnings,
  severityFromAiScore,
  severityLabelOf,
} from '../src/utils/alarmWarning.js'

/**
 * 构造一条预警样本。
 *
 * @param {object} patch 覆盖字段
 * @returns {object} 预警对象
 */
function warning(patch = {}) {
  return {
    id: 1,
    level: '橙色预警',
    cssClass: 'orange',
    status: '待确认',
    ...patch,
  }
}

describe('AI 分数区间常量', () => {
  it('与后端 fusion_engine 阈值一致：80 / 60 / 35', () => {
    expect(AI_RISK_BANDS.map((band) => band.min)).toEqual([80, 60, 35, 0])
    expect(AI_RISK_BANDS.map((band) => band.severity)).toEqual([
      ALARM_SEVERITY.CRITICAL,
      ALARM_SEVERITY.WARNING,
      ALARM_SEVERITY.CAUTION,
      ALARM_SEVERITY.INFO,
    ])
  })
})

describe('severityFromAiScore', () => {
  it('按分数落档：≥80 红、60-80 橙、35-60 黄、<35 蓝', () => {
    expect(severityFromAiScore(85)).toBe(ALARM_SEVERITY.CRITICAL)
    expect(severityFromAiScore(80)).toBe(ALARM_SEVERITY.CRITICAL)
    expect(severityFromAiScore(79.9)).toBe(ALARM_SEVERITY.WARNING)
    expect(severityFromAiScore(60)).toBe(ALARM_SEVERITY.WARNING)
    expect(severityFromAiScore(59.9)).toBe(ALARM_SEVERITY.CAUTION)
    expect(severityFromAiScore(35)).toBe(ALARM_SEVERITY.CAUTION)
    expect(severityFromAiScore(34.9)).toBe(ALARM_SEVERITY.INFO)
    expect(severityFromAiScore(0)).toBe(ALARM_SEVERITY.INFO)
  })

  it('无效分数返回 null', () => {
    expect(severityFromAiScore(undefined)).toBeNull()
    expect(severityFromAiScore(null)).toBeNull()
    expect(severityFromAiScore('abc')).toBeNull()
  })
})

describe('isAlarmWorthy', () => {
  it('待确认的红 / 橙 / 黄 / 蓝预警都触发分级警报', () => {
    expect(isAlarmWorthy(warning({ id: 1, level: '红色预警', cssClass: 'red' }))).toBe(true)
    expect(isAlarmWorthy(warning({ id: 2, level: '橙色预警', cssClass: 'orange' }))).toBe(true)
    expect(isAlarmWorthy(warning({ id: 3, level: '黄色预警', cssClass: 'yellow' }))).toBe(true)
    expect(isAlarmWorthy(warning({ id: 4, level: '蓝色提醒', cssClass: 'blue' }))).toBe(true)
  })

  it('非待确认状态不触发', () => {
    expect(isAlarmWorthy(warning({ id: 7, status: '已确认' }))).toBe(false)
    expect(isAlarmWorthy(warning({ id: 8, status: '已关闭' }))).toBe(false)
  })

  it('空值 / 非对象输入安全返回 false', () => {
    expect(isAlarmWorthy(null)).toBe(false)
    expect(isAlarmWorthy(undefined)).toBe(false)
    expect(isAlarmWorthy('not-an-object')).toBe(false)
  })
})

describe('alarmSeverityOf', () => {
  it('红 / 橙 / 黄 / 蓝分别映射四个级别', () => {
    expect(alarmSeverityOf(warning({ id: 1, level: '红色预警', cssClass: 'red' }))).toBe(
      ALARM_SEVERITY.CRITICAL
    )
    expect(alarmSeverityOf(warning({ id: 2, level: '橙色预警', cssClass: 'orange' }))).toBe(
      ALARM_SEVERITY.WARNING
    )
    expect(alarmSeverityOf(warning({ id: 3, level: '黄色预警', cssClass: 'yellow' }))).toBe(
      ALARM_SEVERITY.CAUTION
    )
    expect(alarmSeverityOf(warning({ id: 4, level: '蓝色提醒', cssClass: 'blue' }))).toBe(
      ALARM_SEVERITY.INFO
    )
  })

  it('等级文字优先：cssClass 数据不一致时也不混色', () => {
    // 橙色预警 + 错误的 red cssClass：仍必须走橙色弹窗，不能变成红色满屏
    expect(alarmSeverityOf(warning({ id: 11, level: '橙色预警', cssClass: 'red' }))).toBe(
      ALARM_SEVERITY.WARNING
    )
    expect(alarmSeverityOf(warning({ id: 12, level: '黄色预警', cssClass: 'red' }))).toBe(
      ALARM_SEVERITY.CAUTION
    )
    expect(alarmSeverityOf(warning({ id: 13, level: '蓝色提醒', cssClass: 'orange' }))).toBe(
      ALARM_SEVERITY.INFO
    )
    // 红色预警 + 错误的 orange cssClass：必须走红色满屏
    expect(alarmSeverityOf(warning({ id: 14, level: '红色预警', cssClass: 'orange' }))).toBe(
      ALARM_SEVERITY.CRITICAL
    )
  })

  it('等级文字缺失时，AI 原始等级兜底（CRITICAL/HIGH/MEDIUM/LOW）', () => {
    expect(alarmSeverityOf(warning({ id: 21, level: '', aiLevel: 'CRITICAL' }))).toBe(
      ALARM_SEVERITY.CRITICAL
    )
    expect(alarmSeverityOf(warning({ id: 22, level: '', aiLevel: 'HIGH' }))).toBe(
      ALARM_SEVERITY.WARNING
    )
    expect(alarmSeverityOf(warning({ id: 23, level: '', aiLevel: 'MEDIUM' }))).toBe(
      ALARM_SEVERITY.CAUTION
    )
    expect(alarmSeverityOf(warning({ id: 24, level: '', aiLevel: 'LOW' }))).toBe(
      ALARM_SEVERITY.INFO
    )
  })

  it('等级与 AI 等级都缺失时按 cssClass 兜底', () => {
    expect(alarmSeverityOf(warning({ id: 31, level: '', cssClass: 'red' }))).toBe(
      ALARM_SEVERITY.CRITICAL
    )
    expect(alarmSeverityOf(warning({ id: 32, level: '', cssClass: '', class: 'yellow' }))).toBe(
      ALARM_SEVERITY.CAUTION
    )
    expect(alarmSeverityOf(warning({ id: 33, level: '', cssClass: 'blue' }))).toBe(
      ALARM_SEVERITY.INFO
    )
  })

  it('非待确认 / 无颜色信息返回 null', () => {
    expect(alarmSeverityOf(warning({ id: 41, status: '已确认' }))).toBeNull()
    expect(alarmSeverityOf(warning({ id: 42, level: '', cssClass: '' }))).toBeNull()
    expect(alarmSeverityOf(null)).toBeNull()
  })
})

describe('severityLabelOf', () => {
  it('四个级别都有中文标签', () => {
    expect(severityLabelOf(ALARM_SEVERITY.CRITICAL)).toBe('红色警报')
    expect(severityLabelOf(ALARM_SEVERITY.WARNING)).toBe('橙色预警')
    expect(severityLabelOf(ALARM_SEVERITY.CAUTION)).toBe('黄色提示')
    expect(severityLabelOf(ALARM_SEVERITY.INFO)).toBe('蓝色关注')
  })
})

describe('partitionAlarmWarnings', () => {
  it('红色进满屏组，橙/黄/蓝进普通弹窗组，并附带 severity', () => {
    const result = partitionAlarmWarnings([
      warning({ id: 1, level: '红色预警', cssClass: 'red' }),
      warning({ id: 2, level: '橙色预警', cssClass: 'orange' }),
      warning({ id: 3, level: '黄色预警', cssClass: 'yellow' }),
      warning({ id: 4, level: '蓝色提醒', cssClass: 'blue' }),
      warning({ id: 5, level: '红色预警', cssClass: 'red' }),
    ])
    expect(result.critical.map((item) => item.id)).toEqual([1, 5])
    expect(result.popup.map((item) => item.id)).toEqual([2, 3, 4])
    expect(result.popup.map((item) => item.severity)).toEqual([
      ALARM_SEVERITY.WARNING,
      ALARM_SEVERITY.CAUTION,
      ALARM_SEVERITY.INFO,
    ])
  })

  it('空列表 / 非数组安全返回两组空数组', () => {
    expect(partitionAlarmWarnings([])).toEqual({ critical: [], popup: [] })
    expect(partitionAlarmWarnings(null)).toEqual({ critical: [], popup: [] })
  })
})

describe('pickNewAlarmWarnings', () => {
  it('只有首次出现的 ID 会进入警报列表，同 ID 不重复', () => {
    const knownIds = new Set()
    const first = pickNewAlarmWarnings(knownIds, [
      warning({ id: 1 }),
      warning({ id: 2, level: '蓝色提醒', cssClass: 'blue' }),
    ])
    // 蓝色也属于四级警报（普通弹窗）
    expect(first.map((item) => item.id)).toEqual([1, 2])
    expect(first.map((item) => item.severity)).toEqual([
      ALARM_SEVERITY.WARNING,
      ALARM_SEVERITY.INFO,
    ])

    const second = pickNewAlarmWarnings(knownIds, [warning({ id: 1 })])
    expect(second).toEqual([])

    const third = pickNewAlarmWarnings(knownIds, [
      warning({ id: 3, level: '红色预警', cssClass: 'red' }),
    ])
    expect(third.map((item) => item.id)).toEqual([3])
    expect(third[0].severity).toBe(ALARM_SEVERITY.CRITICAL)
  })

  it('缺少 id 的项被跳过；无颜色信息的项不触发但仍登记 ID', () => {
    const knownIds = new Set()
    expect(pickNewAlarmWarnings(knownIds, [{ level: '红色预警', status: '待确认' }])).toEqual([])
    expect(pickNewAlarmWarnings(knownIds, [warning({ id: 9, level: '', cssClass: '' })])).toEqual([])
    expect(knownIds.has('9')).toBe(true)
  })

  it('数字 ID 与字符串 ID 视为同一条预警', () => {
    const knownIds = new Set()
    pickNewAlarmWarnings(knownIds, [warning({ id: 10 })])
    expect(pickNewAlarmWarnings(knownIds, [warning({ id: '10' })])).toEqual([])
  })

  it('空列表 / 非数组输入安全返回空数组', () => {
    expect(pickNewAlarmWarnings(new Set(), [])).toEqual([])
    expect(pickNewAlarmWarnings(new Set(), null)).toEqual([])
    expect(pickNewAlarmWarnings(null, [warning()])).toEqual([])
  })
})

describe('extractWarnings', () => {
  it('列表快照 payload 取 warnings 数组', () => {
    const list = [warning({ id: 1 })]
    expect(extractWarnings({ type: 'warning-poll', warnings: list })).toBe(list)
    expect(extractWarnings({ type: 'poll', warnings: [] })).toEqual([])
  })

  it('WebSocket 单条 payload 取 data', () => {
    const item = warning({ id: 2 })
    expect(extractWarnings({ type: 'warning', data: item })).toEqual([item])
  })

  it('无关事件返回空数组', () => {
    expect(extractWarnings({ type: 'socket-open' })).toEqual([])
    expect(extractWarnings(null)).toEqual([])
    expect(extractWarnings('oops')).toEqual([])
  })
})

describe('normalizeWarningId', () => {
  it('数字 / 字符串统一按字符串处理', () => {
    expect(normalizeWarningId({ id: 12 })).toBe('12')
    expect(normalizeWarningId({ id: '12' })).toBe('12')
    expect(normalizeWarningId({ warningId: 'W-1' })).toBe('W-1')
  })

  it('缺失 ID 返回空串', () => {
    expect(normalizeWarningId({})).toBe('')
    expect(normalizeWarningId(null)).toBe('')
    expect(normalizeWarningId({ id: '' })).toBe('')
  })
})
