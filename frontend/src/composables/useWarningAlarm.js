/**
 * 全局紧急警报的实时数据监听。
 *
 * 职责：
 *   1. 订阅 realtimeService 写入 Pinia 的实时事件（WebSocket 推送 + 轮询兜底），
 *      从中筛出"首次出现 + 待确认 + 红/橙/黄/蓝"的预警并回调给弹窗组件
 *      （红=满屏警报；橙/黄/蓝=对应颜色弹窗 + 3 秒跳详情）；
 *   2. 保证实时流在任意路由下都保持运行 —— realtimeService 是模块级单例，
 *      但现有页面（OverviewPage / RealTimeMapPage）会在卸载时 stopRealtimeSync()，
 *      这会导致用户切到设备页等页面后再也收不到预警推送。本 composable 在
 *      实时流空闲时补启一次，不改动任何现有页面的启停逻辑；
 *   3. 首屏基线：第一个列表快照里已经存在的预警只登记 ID、不弹窗，
 *      避免登录瞬间被历史待确认预警刷屏。
 *
 * 为什么监听 Pinia store 而不是 onRealtimeUpdate：
 *   stopRealtimeSync() 默认会 listeners.clear()，页面卸载会顺手清掉所有订阅；
 *   而 emit() 无论何时都会 setEvent 写 store，watch lastEvent 更稳。
 */

import { nextTick, onBeforeUnmount, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useRealtimeStore } from '../stores/realtime'
import { startRealtimeSync, stopRealtimeSync } from '../services/realtimeService'
import { extractWarnings, pickNewAlarmWarnings } from '../utils/alarmWarning'

/** 已见 ID 的保留上限，避免长时间挂机内存无限增长。 */
const KNOWN_ID_LIMIT = 1000

/** 实时流存活巡检间隔：只兜底"意外被停掉"的场景。 */
const ENSURE_SYNC_INTERVAL_MS = 15_000

/**
 * 启动全局预警警报监听。
 *
 * @param {{ onAlarm?: (warnings: Array<object>) => void }} options 回调配置
 * @returns {{ dispose: () => void }} 手动释放（组件卸载时自动调用，一般无需手动）
 */
export function useWarningAlarm({ onAlarm } = {}) {
  const router = useRouter()
  const realtimeStore = useRealtimeStore()

  /** 已经见过的预警 ID（含未触发弹窗的蓝/黄与已确认项） */
  const knownIds = new Set()
  /** 是否已经用首个列表快照完成基线登记 */
  let initialized = false
  /** 组件是否已卸载（异步回调里防止继续操作） */
  let disposed = false
  /** @type {number|null} */
  let ensureTimer = null
  /** @type {(() => void)|null} */
  let removeAfterEach = null
  /** @type {(() => void)|null} */
  let stopWatch = null

  /** 裁剪已见 ID 集合（Set 保持插入顺序，删最早的即可）。 */
  function trimKnownIds() {
    while (knownIds.size > KNOWN_ID_LIMIT) {
      const oldest = knownIds.values().next().value
      if (oldest === undefined) {
        break
      }
      knownIds.delete(oldest)
    }
  }

  /** 实时流处于 idle 时补启一次，保证换页后仍能收到预警推送。 */
  function ensureRealtimeRunning() {
    if (disposed || realtimeStore.status !== 'idle') {
      return
    }
    try {
      startRealtimeSync()
    } catch (err) {
      console.warn('[useWarningAlarm] 启动实时预警流失败:', err?.message ?? err)
    }
  }

  /**
   * 处理一次实时事件。
   *
   * @param {object} payload realtimeService.emit() 的 payload
   */
  function handlePayload(payload) {
    if (disposed || !payload || typeof payload !== 'object') {
      return
    }
    const warnings = extractWarnings(payload)
    // 列表快照（含空数组）：首个快照只做基线登记，历史待确认预警不再弹窗
    if (Array.isArray(payload.warnings) && !initialized) {
      pickNewAlarmWarnings(knownIds, warnings)
      trimKnownIds()
      initialized = true
      return
    }
    if (warnings.length === 0) {
      return
    }
    const fresh = pickNewAlarmWarnings(knownIds, warnings)
    trimKnownIds()
    if (Array.isArray(payload.warnings)) {
      initialized = true
    }
    if (fresh.length === 0) {
      return
    }
    onAlarm?.(fresh)
  }

  onMounted(() => {
    ensureRealtimeRunning()

    // 路由切换后，旧页面可能在 unmount 时停掉实时流；等 DOM 更新完再补启
    removeAfterEach = router.afterEach(() => {
      nextTick(() => {
        ensureRealtimeRunning()
      })
    })

    ensureTimer = window.setInterval(() => {
      ensureRealtimeRunning()
    }, ENSURE_SYNC_INTERVAL_MS)

    // lastEvent 每次 emit 都是新对象，浅层 watch 即可拿到每一条实时事件
    stopWatch = watch(() => realtimeStore.lastEvent, handlePayload, { flush: 'post' })
  })

  /**
   * 释放监听与定时器，并停止实时流（调用方通常是退出主布局/登出场景）。
   */
  function dispose() {
    if (disposed) {
      return
    }
    disposed = true
    if (stopWatch) {
      stopWatch()
      stopWatch = null
    }
    if (ensureTimer !== null) {
      window.clearInterval(ensureTimer)
      ensureTimer = null
    }
    if (removeAfterEach) {
      removeAfterEach()
      removeAfterEach = null
    }
    try {
      stopRealtimeSync()
    } catch (err) {
      console.warn('[useWarningAlarm] 停止实时预警流失败:', err?.message ?? err)
    }
  }

  onBeforeUnmount(dispose)

  return { dispose }
}
