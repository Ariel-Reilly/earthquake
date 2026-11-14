/**
 * 分页归一化单测（纯函数，node 环境，无需 jsdom）。
 *
 * 锁定 records / items / list / 数组 / 空值 的契约（docs/api/CONTRACT.md §1），
 * 保证后续把 dashboardApi.normalizePaged 迁移到 api/client 时行为不回退。
 */
import { describe, expect, it } from 'vitest'
import { normalizeList, normalizePage } from '../api/client.js'

describe('normalizePage', () => {
  it('records 形态：原样取列表与分页元信息', () => {
    const p = normalizePage({ records: [{ id: 1 }, { id: 2 }], total: 8, page: 2, size: 2, pages: 4 })
    expect(p.records).toHaveLength(2)
    expect(p.total).toBe(8)
    expect(p.page).toBe(2)
    expect(p.size).toBe(2)
    expect(p.pages).toBe(4)
  })

  it('items 形态：映射到 records', () => {
    const p = normalizePage({ items: [{ id: 1 }], total: 1 })
    expect(p.records).toEqual([{ id: 1 }])
    expect(p.total).toBe(1)
  })

  it('list 形态：映射到 records（现有 normalizePaged 不支持的形态）', () => {
    const p = normalizePage({ list: [{ id: 9 }], total: 3, page: 1, size: 1, pages: 3 })
    expect(p.records).toEqual([{ id: 9 }])
    expect(p.pages).toBe(3)
  })

  it('裸数组：视为完整列表', () => {
    const p = normalizePage([{ id: 1 }, { id: 2 }, { id: 3 }])
    expect(p.records).toHaveLength(3)
    expect(p.total).toBe(3)
    expect(p.page).toBe(1)
    expect(p.pages).toBe(1)
  })

  it('null / 非对象：回退 fallback', () => {
    const p = normalizePage(null, [{ id: 'f' }])
    expect(p.records).toEqual([{ id: 'f' }])
    expect(p.total).toBe(1)
  })

  it('对象但无列表字段：回退 fallback', () => {
    const p = normalizePage({ total: 5 }, [])
    expect(p.records).toEqual([])
    expect(p.total).toBe(0)
  })

  it('缺省分页元信息：给安全默认（total=长度, page=1, size=长度, pages=1）', () => {
    const p = normalizePage({ records: [{ id: 1 }, { id: 2 }] })
    expect(p.total).toBe(2)
    expect(p.page).toBe(1)
    expect(p.size).toBe(2)
    expect(p.pages).toBe(1)
  })

  it('records 优先于 items / list', () => {
    const p = normalizePage({ records: [1], items: [2], list: [3] })
    expect(p.records).toEqual([1])
  })

  it('total 为 0 时保留 0（不被默认值覆盖）', () => {
    const p = normalizePage({ records: [], total: 0, page: 1, size: 10, pages: 0 })
    expect(p.total).toBe(0)
    expect(p.pages).toBe(0)
  })
})

describe('normalizeList', () => {
  it('records / items / list / 数组 都能取到列表', () => {
    expect(normalizeList({ records: [1] })).toEqual([1])
    expect(normalizeList({ items: [2] })).toEqual([2])
    expect(normalizeList({ list: [3] })).toEqual([3])
    expect(normalizeList([4])).toEqual([4])
  })

  it('空值回退 fallback', () => {
    expect(normalizeList(null, ['x'])).toEqual(['x'])
    expect(normalizeList({}, ['y'])).toEqual(['y'])
  })
})
