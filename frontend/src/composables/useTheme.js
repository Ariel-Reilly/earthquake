import { ref, readonly } from 'vue'

/**
 * 主题切换 (iOS 18 Light / Dark)  ★ 2026-10-09 收敛为唯一主题写入方
 *
 * 背景（修复的真实缺陷）：
 *   此前全站有三套互不相认的主题机制 ——
 *     · 本文件写 <html class="theme-dark">      → CSS 认（但没人调用）
 *     · SettingsPage / ThemeLanguage 写 data-theme 属性 → CSS 不认 ⇒ 点了没反应
 *     · AiAssistant / AppFeedback 自带 prefers-color-scheme → 只认系统
 *   结果「设置 → 深色主题」是空转的。现在统一由本模块写入，
 *   同时写 class 与 data-theme 两种钩子，CSS 两种选择器都支持。
 *
 * 用法:
 *   const { theme, setTheme, toggle } = useTheme()
 *   setTheme('dark') / setTheme('light') / setTheme('system') / toggle()
 */
const STORAGE_KEY = 'disasterguard_theme'
/** 旧设置页把主题存在这个大对象里，读取时需兼容，否则用户上次的选择会丢 */
const LEGACY_SETTINGS_KEY = 'disasterguard_settings'

function readStored() {
  try {
    const v = window.localStorage.getItem(STORAGE_KEY)
    if (v === 'light' || v === 'dark' || v === 'system') {
      return v
    }
    // 兼容：从旧的 settings 大对象里迁移主题，并同步到新键
    const raw = window.localStorage.getItem(LEGACY_SETTINGS_KEY)
    if (raw) {
      const parsed = JSON.parse(raw)
      const t = parsed?.theme
      if (t === 'light' || t === 'dark' || t === 'auto') {
        const normalized = t === 'auto' ? 'system' : t
        window.localStorage.setItem(STORAGE_KEY, normalized)
        return normalized
      }
    }
  } catch (_) { /* 忽略隐私模式 */ }
  return 'system'
}

/**
 * 把主题写到 <html>：
 *   · class="theme-dark|theme-light" —— 旧选择器与既有业务页依赖
 *   · data-theme="dark|light"       —— 设置页/个人中心的既有写法
 *   · 跟随系统时两者都清掉，交给 prefers-color-scheme
 */
function applyTheme(mode) {
  const root = document.documentElement
  root.classList.remove('theme-dark', 'theme-light')
  root.removeAttribute('data-theme')
  if (mode === 'dark') {
    root.classList.add('theme-dark')
    root.setAttribute('data-theme', 'dark')
  } else if (mode === 'light') {
    root.classList.add('theme-light')
    root.setAttribute('data-theme', 'light')
  }
  // 配色方案提示：让浏览器原生控件（滚动条/表单/日期选择器）跟随主题
  root.style.colorScheme = mode === 'system' ? 'light dark' : mode
}

const theme = ref(readStored())
let initialized = false
const listeners = new Set()

function broadcast(value) {
  for (const cb of listeners) {
    try { cb(value) } catch (_) { /* swallow */ }
  }
}

function setTheme(mode) {
  const next = mode === 'auto' ? 'system' : mode
  theme.value = next
  try { window.localStorage.setItem(STORAGE_KEY, next) } catch (_) { /* */ }
  applyTheme(next)
  broadcast(next)
}

function toggle() {
  // 在 light/dark 之间往返,系统跟随时根据当前实际态切换
  const actual = document.documentElement.classList.contains('theme-dark') ? 'dark' : 'light'
  setTheme(actual === 'dark' ? 'light' : 'dark')
}

export function useTheme() {
  if (!initialized) {
    applyTheme(theme.value)
    // 跨标签页同步
    const onStorage = (e) => {
      if (e.key === STORAGE_KEY) {
        const v = e.newValue
        if (v === 'light' || v === 'dark' || v === 'system') {
          theme.value = v
          applyTheme(v)
        }
      }
    }
    window.addEventListener('storage', onStorage)
    initialized = true
  }

  return {
    theme: readonly(theme),
    setTheme,
    toggle,
    isDark: () =>
      theme.value === 'dark' ||
      (theme.value === 'system' &&
        window.matchMedia?.('(prefers-color-scheme: dark)').matches),
    onChange: (cb) => {
      listeners.add(cb)
      return () => listeners.delete(cb)
    },
  }
}

// 顶层模块: 启动时立即应用一次,避免首屏闪白
applyTheme(readStored())
