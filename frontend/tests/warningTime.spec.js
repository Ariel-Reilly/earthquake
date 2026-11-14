// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { formatTimelineTime, formatWarningTime } from '../src/utils/warningTime'

/**
 * 第四轮复测 P1：预警时间必须跟随浏览器本地时区，而不是后端 UTC 快照。
 * 后端 WarningDto.time = "11-14 06:15"（UTC 格式化），triggeredAt = 06:15:29Z（绝对时刻）。
 */
const ISO = '2026-11-14T06:15:29.338574Z'
const WARNING = { triggeredAt: ISO, time: '11-14 06:15' }

describe('formatWarningTime — A6-5 浏览器本地时区语义', () => {
  it('同一 UTC 时刻在 Asia/Shanghai 显示为 14:15（比 UTC 快 8 小时）', () => {
    expect(formatWarningTime(WARNING, 'Asia/Shanghai')).toBe('11-14 14:15')
  })

  it('同一 UTC 时刻在 UTC 显示为 06:15', () => {
    expect(formatWarningTime(WARNING, 'UTC')).toBe('11-14 06:15')
  })

  it('跨午夜：UTC 20:30 在上海是次日 04:30（不再显示成前一天）', () => {
    const w = { triggeredAt: '2026-11-14T20:30:00Z', time: '11-14 20:30' }
    expect(formatWarningTime(w, 'Asia/Shanghai')).toBe('11-15 04:30')
    expect(formatWarningTime(w, 'UTC')).toBe('11-14 20:30')
  })

  it('不同时区产生不同结果（证明确实跟随时区，而非硬编码 UTC）', () => {
    const sh = formatWarningTime(WARNING, 'Asia/Shanghai')
    const utc = formatWarningTime(WARNING, 'UTC')
    const ny = formatWarningTime(WARNING, 'America/New_York')
    expect(sh).not.toBe(utc)
    expect(ny).toBe('11-14 01:15')
  })

  it('不传 timeZone 时按运行环境本地时区（默认行为不变）', () => {
    const expected = new Intl.DateTimeFormat('en-US', {
      hourCycle: 'h23', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
    }).formatToParts(new Date(ISO))
    const get = (t) => expected.find((p) => p.type === t)?.value ?? ''
    expect(formatWarningTime(WARNING)).toBe(`${get('month')}-${get('day')} ${get('hour')}:${get('minute')}`)
  })

  it('缺 triggeredAt 时回退后端 time 快照（兼容旧数据/mock），不造新值', () => {
    expect(formatWarningTime({ time: '11-14 06:15' })).toBe('11-14 06:15')
  })

  it('triggeredAt 无效时回退 time，而不是抛异常或输出 Invalid Date', () => {
    expect(formatWarningTime({ triggeredAt: 'not-a-date', time: '11-14 06:15' })).toBe('11-14 06:15')
  })

  it('空对象 / null 返回占位符', () => {
    expect(formatWarningTime(null)).toBe('—')
    expect(formatWarningTime({})).toBe('—')
  })

  it('跨年时刻（12-31 → 01-01）按本地时区正确翻年', () => {
    const w = { triggeredAt: '2026-12-31T16:30:00Z', time: '12-31 16:30' }
    expect(formatWarningTime(w, 'Asia/Shanghai')).toBe('01-01 00:30')
  })

  it('只有 time 快照、没有 triggeredAt 时按原值展示（兼容旧数据，不造新值）', () => {
    expect(formatWarningTime({ time: '11-14 06:15' })).toBe('11-14 06:15')
  })
})

describe('formatTimelineTime — 详情页时间线节点', () => {
  const ISO = '2026-11-12T13:09:17.321049Z'

  it('后端 OffsetDateTime ISO 原文按浏览器本地时区格式化，不再直出 UTC', () => {
    expect(formatTimelineTime(ISO, '已关闭', 'Asia/Shanghai')).toBe('11-12 21:09')
    expect(formatTimelineTime(ISO, '已关闭', 'UTC')).toBe('11-12 13:09')
  })

  it('带 offset 的 ISO 同样按绝对时刻换算', () => {
    expect(formatTimelineTime('2026-11-12T21:09:17+08:00', '—', 'UTC')).toBe('11-12 13:09')
  })

  it('空值回退到状态文案（已确认 / 已派发 / 已关闭）', () => {
    expect(formatTimelineTime(null, '已确认')).toBe('已确认')
    expect(formatTimelineTime(undefined, '已派发')).toBe('已派发')
    expect(formatTimelineTime('', '已关闭')).toBe('已关闭')
  })

  it('已是 "MM-dd HH:mm" 的展示串或状态文案原样保留', () => {
    expect(formatTimelineTime('11-14 14:43')).toBe('11-14 14:43')
    expect(formatTimelineTime('已确认')).toBe('已确认')
  })

  it('预警对象走 formatWarningTime（triggeredAt 优先）', () => {
    const w = { triggeredAt: '2026-11-14T06:43:15Z', time: '11-14 06:43' }
    expect(formatTimelineTime(w, '—', 'Asia/Shanghai')).toBe('11-14 14:43')
  })

  it('历史记录无 triggeredAt 但有 time 快照时原样保留', () => {
    expect(formatTimelineTime({ time: '11-14 14:43' })).toBe('11-14 14:43')
  })

  it('无效 ISO 不会变成占位符而是保留原文，避免吞掉信息', () => {
    expect(formatTimelineTime('2026-99-99T99:99:99Z')).toBe('2026-99-99T99:99:99Z')
  })
})
