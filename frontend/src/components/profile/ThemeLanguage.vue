<script setup>
/**
 * 个人中心-主题与语言。
 * 主题：浅色 / 深色 / 跟随系统
 * 语言：简体中文 / English（实验性）
 * 与 /api/profile/theme-language 联动；同时立即应用主题到 <html data-theme>。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { Languages, Moon, Save, Sun, Monitor } from 'lucide-vue-next'
import { dashboardApi } from '../../services/dashboardApi'

const emit = defineEmits(['notify'])

const THEMES = [
  { value: 'light', label: '浅色', icon: Sun, desc: '白天办公，眼睛不易疲劳' },
  { value: 'dark',  label: '深色', icon: Moon, desc: '夜间值守 / 指挥大屏' },
  { value: 'auto',  label: '跟随系统', icon: Monitor, desc: '依据系统暗色模式自动切换' },
]
const LANGS = [
  { value: 'zh-CN', label: '简体中文', desc: '默认中文界面' },
  { value: 'en-US', label: 'English', desc: '实验性，部分文案未翻译' },
]
const DENSITY = [
  { value: 'comfortable', label: '标准', desc: '默认间距' },
  { value: 'compact',     label: '紧凑', desc: '信息密度更高' },
]

const choice = reactive({ theme: 'auto', language: 'zh-CN', density: 'comfortable' })
const saving = ref(false)
const dirty = ref(false)

watch(choice, () => { dirty.value = true }, { deep: true })

function applyTheme() {
  const html = document.documentElement
  if (choice.theme === 'dark') html.dataset.theme = 'dark'
  else if (choice.theme === 'light') html.dataset.theme = 'light'
  else html.removeAttribute('data-theme')
}

function applyDensity() {
  const html = document.documentElement
  if (choice.density === 'compact') html.dataset.density = 'compact'
  else html.removeAttribute('data-density')
}

function applyLanguage() {
  window.localStorage.setItem('disasterguard_language', choice.language)
}

async function load() {
  try {
    const data = await dashboardApi.getUserPreference('u-admin')
    if (data) {
      if (data.theme) choice.theme = data.theme
      if (data.language) choice.language = data.language
      if (data.compactMode != null) choice.density = data.compactMode ? 'compact' : 'comfortable'
    }
  } finally {
    applyTheme()
    applyDensity()
    applyLanguage()
  }
}

async function save() {
  saving.value = true
  try {
    await dashboardApi.updateThemeLanguage({
      theme: choice.theme,
      language: choice.language,
      compactMode: choice.density === 'compact',
    })
    applyTheme()
    applyDensity()
    applyLanguage()
    dirty.value = false
    emit('notify', '主题 / 语言已生效')
  } catch (e) {
    emit('notify', e?.message ?? '保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(load)
const langDesc = computed(() => LANGS.find((l) => l.value === choice.language)?.desc ?? '')
</script>

<template>
  <section class="theme-lang">
    <header class="head">
      <div>
        <h3><Languages :size="18" /> 主题与语言</h3>
        <p>主题立即生效；语言切换后界面文案会相应变化。</p>
      </div>
      <button class="primary" :disabled="!dirty || saving" @click="save">
        <Save :size="14" /> 保存
      </button>
    </header>

    <div class="block">
      <h4>主题</h4>
      <div class="cards">
        <button
          v-for="t in THEMES"
          :key="t.value"
          class="card"
          :class="{ active: choice.theme === t.value }"
          @click="choice.theme = t.value"
        >
          <component :is="t.icon" :size="22" />
          <div>
            <strong>{{ t.label }}</strong>
            <small>{{ t.desc }}</small>
          </div>
        </button>
      </div>
    </div>

    <div class="block">
      <h4>语言</h4>
      <div class="cards">
        <button
          v-for="l in LANGS"
          :key="l.value"
          class="card wide"
          :class="{ active: choice.language === l.value }"
          @click="choice.language = l.value"
        >
          <strong>{{ l.label }}</strong>
          <small>{{ l.desc }}</small>
        </button>
      </div>
      <p class="hint">当前语言：{{ choice.language }}（{{ langDesc }}）</p>
    </div>

    <div class="block">
      <h4>界面密度</h4>
      <div class="cards">
        <button
          v-for="d in DENSITY"
          :key="d.value"
          class="card wide"
          :class="{ active: choice.density === d.value }"
          @click="choice.density = d.value"
        >
          <strong>{{ d.label }}</strong>
          <small>{{ d.desc }}</small>
        </button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.theme-lang { display: flex; flex-direction: column; gap: 18px; }
.head { display: flex; justify-content: space-between; align-items: flex-start; border-bottom: 1px solid var(--dashboard-border, #eef2f7); padding-bottom: 14px; }
.head h3 { display: inline-flex; align-items: center; gap: 6px; margin: 0; font-size: 18px; }
.head p { margin: 4px 0 0; color: var(--dashboard-muted, #6b7280); font-size: 13px; }
.primary {
  background: linear-gradient(135deg, #5b8ef9, #3b82f6); color: white;
  border: none; padding: 6px 14px; border-radius: 8px;
  display: inline-flex; align-items: center; gap: 6px;
  cursor: pointer; font-size: 13px;
}
.primary:disabled { opacity: 0.5; cursor: not-allowed; }
.block { display: flex; flex-direction: column; gap: 10px; }
.block h4 { margin: 0; font-size: 14px; }
.cards { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
.cards .wide { grid-column: span 1; }
@media (max-width: 720px) { .cards { grid-template-columns: 1fr 1fr; } }
.card {
  display: flex; gap: 10px; align-items: center;
  background: var(--dashboard-muted-bg, #f8fafc);
  border: 1px solid var(--dashboard-border, #e2e8f0);
  border-radius: 10px; padding: 12px 14px; cursor: pointer;
  text-align: left;
  color: var(--dashboard-text, #1e293b);
}
.card.active { border-color: #5b8ef9; background: linear-gradient(135deg, #eff6ff, #e0e7ff); }
.card strong { display: block; font-size: 14px; }
.card small { display: block; font-size: 12px; color: var(--dashboard-muted, #64748b); }
.hint { font-size: 12px; color: var(--dashboard-muted, #6b7280); margin: 0; }
</style>
