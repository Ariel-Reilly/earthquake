/**
 * 紧急警报音（Web Audio API 代码合成，零音频文件）。
 *
 * 为什么不用 <audio autoplay>：
 *   现代浏览器禁止无用户手势的自动播放，而警报必须"收到预警立即响"。
 *   用 AudioContext 现场合成 800Hz / 1000Hz 交替的方波蜂鸣，
 *   首次被拦时可以把 AudioContext 的 Promise 拒绝转成"待用户点击恢复"的状态，
 *   用户任意点击后 resume() 即可续播，无需额外资源文件。
 *
 * 使用方式：
 *   const { playing, blocked, unsupported, start, stop, resume } = useAlarmSound()
 *   start()  启动 / 续播（内部处理 autoplay 拦截）
 *   stop()   彻底停止（oscillator.stop() + audioContext.close()）
 *
 * 注意：一个浏览器页面同时活跃的 AudioContext 数量有限，因此每次停止都会
 * close() 并置空，下一轮警报重新创建，不会累积。
 */

import { ref } from 'vue'

/** 低 / 高交替音高（Hz）。 */
const LOW_FREQUENCY_HZ = 800
const HIGH_FREQUENCY_HZ = 1000

/** 单声"哔"的节拍：0.4s 一拍，其中 0.32s 发声、0.08s 静音。 */
const BEEP_STEP_SECONDS = 0.4
const BEEP_DURATION_SECONDS = 0.32
const BEEP_FADE_SECONDS = 0.02
const BEEP_RELEASE_SECONDS = 0.06

/** 音量：足够刺耳但不至于破音。 */
const BEEP_GAIN = 0.22

/** 预排程窗口：提前 1.6s 把后续节拍写进 AudioParam，避免 JS 卡顿导致断续。 */
const SCHEDULE_AHEAD_SECONDS = 1.6
const SCHEDULER_INTERVAL_MS = 400
/** 每批预排 4 拍（1.6s）。 */
const BATCH_BEEP_COUNT = 4

/** resume() 等待上限：超过仍未 running 视为被 autoplay 策略拦截。 */
const RESUME_TIMEOUT_MS = 300

/**
 * 取浏览器 AudioContext 构造器。
 * 通过 window 访问而不是裸 AudioContext：既兼容 webkit 前缀，
 * 也避免 ESLint `no-undef`（AudioContext 不在项目 globals 白名单里）。
 *
 * @returns {Function|null} 构造器，不支持时返回 null
 */
function getAudioContextCtor() {
  if (typeof window === 'undefined') {
    return null
  }
  return window.AudioContext || window.webkitAudioContext || null
}

/**
 * 等待指定毫秒（用于给 resume() 加超时，避免被拦时永远挂起）。
 *
 * @param {number} ms 毫秒
 * @returns {Promise<void>}
 */
function wait(ms) {
  return new Promise((resolve) => {
    window.setTimeout(resolve, ms)
  })
}

/**
 * 警报音 composable。
 *
 * @returns {{
 *   playing: import('vue').Ref<boolean>,
 *   blocked: import('vue').Ref<boolean>,
 *   unsupported: import('vue').Ref<boolean>,
 *   start: () => Promise<boolean>,
 *   stop: () => void,
 *   resume: () => Promise<boolean>,
 * }}
 */
export function useAlarmSound() {
  /** 当前是否正在出声 */
  const playing = ref(false)
  /** 是否被浏览器 autoplay 策略拦截（需要用户点击后恢复） */
  const blocked = ref(false)
  /** 浏览器完全不支持 Web Audio */
  const unsupported = ref(false)

  /** @type {AudioContext|null} */
  let audioContext = null
  /** @type {OscillatorNode|null} */
  let oscillatorNode = null
  /** @type {GainNode|null} */
  let gainNode = null
  /** @type {number|null} */
  let schedulerTimer = null
  /** 下一批节拍的绝对开始时间（AudioContext 时间轴，秒） */
  let nextBatchStart = 0
  /** 全局节拍序号，用于 800 / 1000Hz 交替 */
  let beatIndex = 0
  /** start() 是否正在等待 resume()，防止并发调用重复创建振荡器 */
  let starting = false

  /**
   * 把一批（4 拍）蜂鸣写进 AudioParam 时间轴。
   *
   * @param {number} startAt 绝对开始时间（秒）
   */
  function scheduleBatch(startAt) {
    if (!audioContext || !oscillatorNode || !gainNode) {
      return
    }
    let cursor = startAt
    for (let i = 0; i < BATCH_BEEP_COUNT; i += 1) {
      const frequency = beatIndex % 2 === 0 ? LOW_FREQUENCY_HZ : HIGH_FREQUENCY_HZ
      beatIndex += 1
      const beepEnd = cursor + BEEP_DURATION_SECONDS
      const releaseStart = beepEnd - BEEP_RELEASE_SECONDS
      oscillatorNode.frequency.setValueAtTime(frequency, cursor)
      gainNode.gain.setValueAtTime(0.0001, cursor)
      gainNode.gain.linearRampToValueAtTime(BEEP_GAIN, cursor + BEEP_FADE_SECONDS)
      gainNode.gain.setValueAtTime(BEEP_GAIN, releaseStart)
      gainNode.gain.exponentialRampToValueAtTime(0.0001, beepEnd)
      cursor += BEEP_STEP_SECONDS
    }
    nextBatchStart = cursor
  }

  /** 定时补排下一批节拍，保持声音持续、无缝隙。 */
  function pumpScheduler() {
    if (!audioContext) {
      return
    }
    const lookaheadEnd = audioContext.currentTime + SCHEDULE_AHEAD_SECONDS
    if (nextBatchStart > lookaheadEnd) {
      return
    }
    scheduleBatch(Math.max(nextBatchStart, audioContext.currentTime + 0.02))
  }

  /**
   * 启动警报音。已在上一次 `stop()` 后调用时会在调用方的用户手势下恢复。
   *
   * @returns {Promise<boolean>} 是否成功出声
   */
  async function start() {
    if (playing.value) {
      return true
    }
    if (starting) {
      // 正在等待 resume()：直接复用进行中的启动，避免叠出第二组振荡器
      return false
    }
    starting = true
    try {
      const Ctor = getAudioContextCtor()
      if (!Ctor) {
        unsupported.value = true
        return false
      }
      try {
        if (!audioContext || audioContext.state === 'closed') {
          audioContext = new Ctor()
        }
        if (audioContext.state !== 'running') {
          const resumeTask = audioContext.resume()
          // 被 autoplay 策略拦截时 resume 可能长期 pending，加超时兜底
          await Promise.race([resumeTask, wait(RESUME_TIMEOUT_MS)])
        }
      } catch (err) {
        // 典型：NotAllowedError —— 没有用户手势，先标记被拦截，等用户点击
        console.warn('[useAlarmSound] 浏览器拦截了警报音播放:', err?.message ?? err)
      }
      if (!audioContext || audioContext.state !== 'running') {
        blocked.value = true
        return false
      }

      blocked.value = false
      oscillatorNode = audioContext.createOscillator()
      gainNode = audioContext.createGain()
      oscillatorNode.type = 'square'
      gainNode.gain.setValueAtTime(0.0001, audioContext.currentTime)
      oscillatorNode.connect(gainNode)
      gainNode.connect(audioContext.destination)
      oscillatorNode.start()

      beatIndex = 0
      nextBatchStart = audioContext.currentTime + 0.02
      scheduleBatch(nextBatchStart)
      schedulerTimer = window.setInterval(pumpScheduler, SCHEDULER_INTERVAL_MS)
      playing.value = true
      return true
    } finally {
      starting = false
    }
  }

  /**
   * 彻底停止警报音：停振荡器、断开节点、关闭 AudioContext。
   * 关闭逻辑必须彻底，否则用户点"确认收到"后仍可能有余音。
   */
  function stop() {
    if (schedulerTimer !== null) {
      window.clearInterval(schedulerTimer)
      schedulerTimer = null
    }
    if (oscillatorNode) {
      try {
        oscillatorNode.stop()
      } catch {
        // 已停止 / 未启动时忽略
      }
      try {
        oscillatorNode.disconnect()
      } catch {
        // 忽略重复断开
      }
      oscillatorNode = null
    }
    if (gainNode) {
      try {
        gainNode.disconnect()
      } catch {
        // 忽略重复断开
      }
      gainNode = null
    }
    if (audioContext) {
      const context = audioContext
      audioContext = null
      try {
        context.close().catch(() => {})
      } catch {
        // close() 同步抛错时忽略
      }
    }
    playing.value = false
    blocked.value = false
  }

  /**
   * 用户点击弹窗后恢复播放（autoplay 兜底链路）。
   *
   * @returns {Promise<boolean>} 是否成功出声
   */
  async function resume() {
    blocked.value = false
    return start()
  }

  /**
   * 在用户手势中预热音频上下文（不发声）。
   *
   * 浏览器自动播放策略要求"页面发生过用户交互"才允许 Web Audio 出声。
   * 登录后若一直没点击页面就收到红色预警，警报音会被拦截；因此在
   * 首次 pointerdown / keydown 时调用本方法，创建并 resume 一次上下文，
   * 之后的红色预警即可直接出声。
   *
   * @returns {Promise<boolean>} 是否已可出声
   */
  async function unlock() {
    const Ctor = getAudioContextCtor()
    if (!Ctor) {
      unsupported.value = true
      return false
    }
    try {
      if (!audioContext || audioContext.state === 'closed') {
        audioContext = new Ctor()
      }
      if (audioContext.state !== 'running') {
        await Promise.race([audioContext.resume(), wait(RESUME_TIMEOUT_MS)])
      }
      return audioContext.state === 'running'
    } catch (err) {
      console.warn('[useAlarmSound] 音频预热失败:', err?.message ?? err)
      return false
    }
  }

  return { playing, blocked, unsupported, start, stop, resume, unlock }
}
