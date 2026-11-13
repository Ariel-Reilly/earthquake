/**
 * WebSocket 地址规范化单测。
 *
 * 背景：生产 compose 注入 VITE_WS_URL=/ws（相对路径），浏览器 new WebSocket(相对路径)
 * 会抛 SyntaxError，导致 WS 永远连不上、实时能力被静默降级；本测试锁定规范化行为。
 */

import { describe, expect, it } from 'vitest'
import { appendWsQuery, resolveWebSocketUrl } from '../src/utils/wsUrl.js'

const ORIGIN = 'http://127.0.0.1:5173'

describe('resolveWebSocketUrl', () => {
  it('相对路径按 origin 补全', () => {
    expect(resolveWebSocketUrl('/ws', ORIGIN)).toBe('ws://127.0.0.1:5173/ws')
  })

  it('http(s) 绝对地址转为 ws(s)', () => {
    expect(resolveWebSocketUrl('http://localhost:8080/ws', ORIGIN)).toBe('ws://localhost:8080/ws')
    expect(resolveWebSocketUrl('https://example.com/ws', ORIGIN)).toBe('wss://example.com/ws')
  })

  it('已是 ws/wss 的地址保持不变', () => {
    expect(resolveWebSocketUrl('ws://localhost:8080/ws', ORIGIN)).toBe('ws://localhost:8080/ws')
    expect(resolveWebSocketUrl('wss://example.com/ws', ORIGIN)).toBe('wss://example.com/ws')
  })

  it('空值 / 非法协议返回空串（调用方据此降级为轮询）', () => {
    expect(resolveWebSocketUrl('', ORIGIN)).toBe('')
    expect(resolveWebSocketUrl('   ', ORIGIN)).toBe('')
    expect(resolveWebSocketUrl(null, ORIGIN)).toBe('')
    expect(resolveWebSocketUrl('ftp://example.com/ws', ORIGIN)).toBe('')
  })
})

describe('appendWsQuery', () => {
  it('追加 ticket 参数', () => {
    const url = appendWsQuery('ws://127.0.0.1:8080/ws', { ticket: 'abc 123' })
    expect(url).toContain('ticket=abc+123')
    expect(url.startsWith('ws://127.0.0.1:8080/ws?')).toBe(true)
  })

  it('保留已有 query 并覆盖同名参数', () => {
    const url = appendWsQuery('ws://example.com/ws?x=1', { ticket: 't' })
    expect(url).toContain('x=1')
    expect(url).toContain('ticket=t')
  })

  it('空地址 / 空参数安全返回', () => {
    expect(appendWsQuery('', { ticket: 't' })).toBe('')
    expect(appendWsQuery('ws://example.com/ws', null)).toBe('ws://example.com/ws')
  })
})
