/**
 * 用户偏好 Pinia store + 兼容层。
 *
 * 范围:主题、语言、通知通道矩阵、免打扰时段、首页、紧凑模式。
 * 持久化:window.localStorage,key = `disasterguard_pref_<userId>`。
 * 兼容旧 `dashboardApi.getUserPreference / updateUserPreference` 调用方。
 *
 * 数据模型参考 `src/services/mock/runtime.js` 的 `DEFAULT_USER_PREFERENCE`,
 * 字段完全一致,后续若字段变动应同步两边。
 */

import { defineStore } from 'pinia'
import { dashboardApi } from '../services/dashboardApi.js'

const PREF_KEY_PREFIX = 'disasterguard_pref_'

function safeParse(raw) {
  if (!raw) {return null}
  try { return JSON.parse(raw) } catch { return null }
}

function loadInitialState(userId = 'u-admin') {
  return {
    userId,
    channels: { sms: true, email: false, app: true, inApp: true, phone: false },
    levels: {
      red:    ['sms', 'app', 'inApp', 'phone'],
      orange: ['sms', 'app', 'inApp'],
      yellow: ['app', 'inApp'],
      blue:   ['inApp'],
    },
    language: 'zh-CN',
    theme: 'light',
    landingPage: '/dashboard',
    compactMode: false,
    quietHoursEnabled: false,
    quietStart: '22:00',
    quietEnd: '08:00',
  }
}

function persist(userId, snapshot) {
  if (typeof window === 'undefined') {return}
  window.localStorage.setItem(PREF_KEY_PREFIX + userId, JSON.stringify(snapshot))
}

export const useUserPreferenceStore = defineStore('userPreference', {
  state: () => loadInitialState(),
  getters: {
    isCompact: (state) => Boolean(state.compactMode),
    isDarkTheme: (state) => state.theme === 'dark',
    enabledChannels: (state) =>
      Object.entries(state.channels).filter(([, on]) => on).map(([k]) => k),
    channelsForLevel: (state) => (level) => state.levels[level] ?? [],
  },
  actions: {
    setUserId(userId) {
      this.userId = userId || 'u-admin'
    },
    setChannels(channels) {
      this.channels = { ...this.channels, ...channels }
      persist(this.userId, this.$state)
    },
    setLevelChannels(level, list) {
      this.levels[level] = Array.isArray(list) ? [...list] : []
      persist(this.userId, this.$state)
    },
    toggleChannel(name) {
      if (!(name in this.channels)) {return}
      this.channels[name] = !this.channels[name]
      persist(this.userId, this.$state)
    },
    toggleLevelChannel(level, channel) {
      const list = this.levels[level] ?? []
      const idx = list.indexOf(channel)
      if (idx >= 0) {list.splice(idx, 1)}
      else {list.push(channel)}
      this.levels[level] = [...list]
      persist(this.userId, this.$state)
    },
    setTheme(theme) {
      this.theme = theme
      persist(this.userId, this.$state)
    },
    setLanguage(language) {
      this.language = language
      persist(this.userId, this.$state)
    },
    setLandingPage(path) {
      this.landingPage = path
      persist(this.userId, this.$state)
    },
    setCompactMode(flag) {
      this.compactMode = Boolean(flag)
      persist(this.userId, this.$state)
    },
    setQuietHours({ enabled, start, end }) {
      if (typeof enabled === 'boolean') {this.quietHoursEnabled = enabled}
      if (start) {this.quietStart = start}
      if (end) {this.quietEnd = end}
      persist(this.userId, this.$state)
    },
    /**
     * 从远端拉取并合并到本地;远端失败时回退到 localStorage,再回退到默认。
     */
    async load() {
      try {
        const data = await dashboardApi.getUserPreference(this.userId)
        if (data && typeof data === 'object') {
          this.channels = { ...this.channels, ...(data.channels ?? {}) }
          if (data.levels) {
            Object.keys(data.levels).forEach((lvl) => {
              if (Array.isArray(data.levels[lvl])) {this.levels[lvl] = [...data.levels[lvl]]}
            })
          }
          if (data.language) {this.language = data.language}
          if (data.theme) {this.theme = data.theme}
          if (data.landingPage) {this.landingPage = data.landingPage}
          if (typeof data.compactMode === 'boolean') {this.compactMode = data.compactMode}
          if (typeof data.quietHoursEnabled === 'boolean') {this.quietHoursEnabled = data.quietHoursEnabled}
          if (data.quietStart) {this.quietStart = data.quietStart}
          if (data.quietEnd) {this.quietEnd = data.quietEnd}
          persist(this.userId, this.$state)
          return this.$state
        }
      } catch {
        // 网络失败 → 回退到 localStorage
      }
      // 尝试从 localStorage 恢复
      const raw = typeof window !== 'undefined'
        ? window.localStorage.getItem(PREF_KEY_PREFIX + this.userId)
        : null
      const cached = safeParse(raw)
      if (cached && typeof cached === 'object') {
        Object.assign(this.channels, cached.channels ?? {})
        if (cached.levels) {
          Object.keys(cached.levels).forEach((lvl) => {
            if (Array.isArray(cached.levels[lvl])) {this.levels[lvl] = [...cached.levels[lvl]]}
          })
        }
        if (cached.language) {this.language = cached.language}
        if (cached.theme) {this.theme = cached.theme}
        if (cached.landingPage) {this.landingPage = cached.landingPage}
        if (typeof cached.compactMode === 'boolean') {this.compactMode = cached.compactMode}
        if (typeof cached.quietHoursEnabled === 'boolean') {this.quietHoursEnabled = cached.quietHoursEnabled}
        if (cached.quietStart) {this.quietStart = cached.quietStart}
        if (cached.quietEnd) {this.quietEnd = cached.quietEnd}
      }
      return this.$state
    },
    async save() {
      const snapshot = { ...this.$state }
      try {
        await dashboardApi.updateUserPreference({
          channels: this.channels,
          levels: this.levels,
          language: this.language,
          theme: this.theme,
          landingPage: this.landingPage,
          compactMode: this.compactMode,
          quietHoursEnabled: this.quietHoursEnabled,
          quietStart: this.quietStart,
          quietEnd: this.quietEnd,
        })
      } catch {
        // 后端失败时仅本地持久化,下次 load 会同步
      }
      persist(this.userId, snapshot)
      return snapshot
    },
    reset() {
      const fresh = loadInitialState(this.userId)
      this.$patch(fresh)
      persist(this.userId, fresh)
    },
  },
})

// 兼容层: 旧函数 API → store

/**
 * @deprecated 直接使用 useUserPreferenceStore().xxx
 */
export function getUserPreferenceSnapshot() {
  const s = useUserPreferenceStore()
  return {
    channels: { ...s.channels },
    levels: JSON.parse(JSON.stringify(s.levels)),
    language: s.language,
    theme: s.theme,
    landingPage: s.landingPage,
    compactMode: s.compactMode,
    quietHoursEnabled: s.quietHoursEnabled,
    quietStart: s.quietStart,
    quietEnd: s.quietEnd,
  }
}

export function setThemeSync(theme) { useUserPreferenceStore().setTheme(theme) }
export function setLanguageSync(language) { useUserPreferenceStore().setLanguage(language) }
