/**
 * 警报音发声 / 停止 / 被浏览器拦截后恢复的逻辑测试。
 *
 * 用假的 AudioContext 验证：
 *   - start() 会创建振荡器并按 800 / 1000Hz 交替排程；
 *   - stop() 必须停止振荡器并关闭 AudioContext（"确认收到后声音彻底消失"）；
 *   - autoplay 拦截（suspended + resume 拒绝）时置 blocked，用户点击后 resume 可恢复；
 *   - 浏览器不支持 Web Audio 时置 unsupported 而不是抛错。
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAlarmSound } from '../src/composables/useAlarmSound.js'

/** 记录 AudioParam 上的排程调用 */
class FakeAudioParam {
  constructor() {
    this.frequencyCalls = []
  }

  setValueAtTime(value, time) {
    this.frequencyCalls.push({ method: 'set', value, time })
  }

  linearRampToValueAtTime(value, time) {
    this.frequencyCalls.push({ method: 'linear', value, time })
  }

  exponentialRampToValueAtTime(value, time) {
    this.frequencyCalls.push({ method: 'exp', value, time })
  }
}

/** 假振荡器 */
class FakeOscillator {
  constructor() {
    this.frequency = new FakeAudioParam()
    this.type = 'sine'
    this.started = false
    this.stopped = false
    this.destination = null
  }

  connect(node) {
    this.destination = node
  }

  disconnect() {
    this.destination = null
  }

  start() {
    this.started = true
  }

  stop() {
    this.stopped = true
  }
}

/** 假增益节点 */
class FakeGain {
  constructor() {
    this.gain = new FakeAudioParam()
    this.destination = null
  }

  connect(node) {
    this.destination = node
  }

  disconnect() {
    this.destination = null
  }
}

/**
 * 假 AudioContext。
 *
 * @param {{ initialState?: string, resumeRejects?: boolean }} options 初始状态
 */
function createFakeAudioContextClass({ initialState = 'running', resumeRejects = false } = {}) {
  return class FakeAudioContext {
    static instances = []

    constructor() {
      this.state = initialState
      this.currentTime = 0
      this.closed = false
      this.oscillators = []
      this.gains = []
      this.destination = {}
      FakeAudioContext.instances.push(this)
    }

    createOscillator() {
      const oscillator = new FakeOscillator()
      this.oscillators.push(oscillator)
      return oscillator
    }

    createGain() {
      const gain = new FakeGain()
      this.gains.push(gain)
      return gain
    }

    resume() {
      if (resumeRejects) {
        return Promise.reject(new Error('NotAllowedError'))
      }
      this.state = 'running'
      return Promise.resolve()
    }

    close() {
      this.closed = true
      this.state = 'closed'
      return Promise.resolve()
    }
  }
}

/** 安装一个带 AudioContext 的仿真 window。 */
function installFakeWindow(Ctor) {
  globalThis.window = {
    AudioContext: Ctor,
    setTimeout: globalThis.setTimeout,
    clearTimeout: globalThis.clearTimeout,
    setInterval: globalThis.setInterval,
    clearInterval: globalThis.clearInterval,
  }
}

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
  delete globalThis.window
})

describe('useAlarmSound', () => {
  it('start 会创建振荡器并按 800 / 1000Hz 交替排程，stop 彻底关闭声音', async () => {
    const FakeAudioContext = createFakeAudioContextClass()
    installFakeWindow(FakeAudioContext)
    const { playing, blocked, start, stop } = useAlarmSound()

    const started = await start()
    expect(started).toBe(true)
    expect(playing.value).toBe(true)
    expect(blocked.value).toBe(false)

    const context = FakeAudioContext.instances[0]
    const oscillator = context.oscillators[0]
    expect(oscillator.type).toBe('square')
    expect(oscillator.started).toBe(true)

    // 前四拍：800 / 1000Hz 交替
    const frequencies = oscillator.frequency.frequencyCalls
      .filter((call) => call.method === 'set')
      .map((call) => call.value)
    expect(frequencies.slice(0, 4)).toEqual([800, 1000, 800, 1000])

    stop()
    expect(playing.value).toBe(false)
    expect(oscillator.stopped).toBe(true)
    expect(context.closed).toBe(true)
  })

  it('audioContext 被 autoplay 拦截时标记 blocked，用户点击后 resume 能恢复', async () => {
    const FakeAudioContext = createFakeAudioContextClass({
      initialState: 'suspended',
      resumeRejects: true,
    })
    installFakeWindow(FakeAudioContext)
    const { playing, blocked, start, stop, resume } = useAlarmSound()

    const started = await start()
    expect(started).toBe(false)
    expect(playing.value).toBe(false)
    expect(blocked.value).toBe(true)

    // 用户点击后：允许播放（模拟浏览器在用户手势后放行）
    const context = FakeAudioContext.instances[0]
    context.state = 'running'
    const resumed = await resume()
    expect(resumed).toBe(true)
    expect(playing.value).toBe(true)
    expect(blocked.value).toBe(false)
    stop()
  })

  it('unlock 在用户手势中预热音频上下文，但不会开始发声', async () => {
    const FakeAudioContext = createFakeAudioContextClass({
      initialState: 'suspended',
      resumeRejects: true,
    })
    installFakeWindow(FakeAudioContext)
    const { playing, unlock, stop } = useAlarmSound()

    // 仍被拦截：预热返回 false，不产生振荡器
    expect(await unlock()).toBe(false)
    const context = FakeAudioContext.instances[0]
    expect(context.oscillators.length).toBe(0)

    // 用户手势后浏览器放行：unlock 成功，但依然不自动发声（只预热上下文）
    context.state = 'running'
    expect(await unlock()).toBe(true)
    expect(playing.value).toBe(false)
    expect(context.oscillators.length).toBe(0)
    stop()
  })

  it('浏览器不支持 Web Audio 时置 unsupported，不抛错', async () => {
    globalThis.window = { setTimeout: globalThis.setTimeout }
    const { unsupported, playing, start } = useAlarmSound()

    const started = await start()
    expect(started).toBe(false)
    expect(playing.value).toBe(false)
    expect(unsupported.value).toBe(true)
  })

  it('重复 start 不会叠加振荡器', async () => {
    const FakeAudioContext = createFakeAudioContextClass()
    installFakeWindow(FakeAudioContext)
    const { start, stop } = useAlarmSound()

    await start()
    await start()
    const context = FakeAudioContext.instances[0]
    expect(context.oscillators.length).toBe(1)
    stop()
  })

  it('resume 尚未完成时的并发 start 不会叠出第二组振荡器', async () => {
    const FakeAudioContext = createFakeAudioContextClass({ initialState: 'suspended' })
    installFakeWindow(FakeAudioContext)
    const { start, stop } = useAlarmSound()

    const first = start()
    const second = await start()
    expect(second).toBe(false)
    expect(await first).toBe(true)
    const context = FakeAudioContext.instances[0]
    expect(context.oscillators.length).toBe(1)
    stop()
  })
})
