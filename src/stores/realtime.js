/**
 * 实时通道状态 Pinia store。
 *
 * 集中持有 WebSocket / 轮询 / 通知三类实时数据的状态,
 * 由 realtimeService 写入, view 通过 store 或兼容函数 getRealtimeState() 读取。
 *
 * 状态语义:
 *   status: 'idle' | 'connecting' | 'ws' | 'polling'
 *     - idle       : 未启动
 *     - connecting : 启动中(WS 握手/首次轮询未完成)
 *     - ws         : 已建立 WebSocket
 *     - polling    : WS 失败降级为轮询
 *
 *   lastEvent: 最近一次收到的 realtime event (任意类型)
 *   pendingChanges: 待前端展示的变更数(0 在 UI 隐藏红点)
 *
 * listeners Set 仍在 realtimeService 内(JS 原生 Set 无法响应式,
 * 需要响应式的话用 ref + shallowReactive 包装)。
 */

import { defineStore } from 'pinia'

export const useRealtimeStore = defineStore('realtime', {
  state: () => ({
    status: 'idle',
    lastEvent: null,
    pendingChanges: 0,
    /** 后端探活结果: true 表示后端可达(用于 banner 提示) */
    backendReachable: true,
  }),
  getters: {
    isRunning: (state) => state.status !== 'idle',
    isUsingWebSocket: (state) => state.status === 'ws',
    isPolling: (state) => state.status === 'polling',
  },
  actions: {
    setStatus(status) {
      this.status = status
    },
    setEvent(event) {
      this.lastEvent = event
      this.pendingChanges += 1
    },
    clearPending() {
      this.pendingChanges = 0
    },
    incrementPending(n = 1) {
      this.pendingChanges += n
    },
    setBackendReachable(flag) {
      this.backendReachable = Boolean(flag)
    },
    reset() {
      this.status = 'idle'
      this.lastEvent = null
      this.pendingChanges = 0
    },
  },
})

/**
 * 兼容层: 旧 realtimeService.getRealtimeState() 返回的对象保持一致。
 * 新代码直接 useRealtimeStore().
 */
export function getRealtimeStateSnapshot() {
  const s = useRealtimeStore()
  return {
    status: s.status,
    isRunning: s.isRunning,
    pendingChanges: s.pendingChanges,
    lastEvent: s.lastEvent,
  }
}
