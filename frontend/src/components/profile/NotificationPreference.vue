<script setup>
/**
 * 个人中心-通知偏好。
 * 通道：短信 / 邮件 / APP / 站内信 / 电话
 * 等级：红 / 橙 / 黄 / 蓝
 * 与 /api/profile/notification-preference 联动。
 */
import { computed, reactive, ref, watch } from 'vue'
import { Bell, Loader2, Save } from 'lucide-vue-next'
import { dashboardApi } from '../../services/dashboardApi'

const emit = defineEmits(['notify'])

const CHANNELS = [
  { value: 'sms',   label: '短信',     icon: '📱' },
  { value: 'email', label: '邮件',     icon: '✉️' },
  { value: 'app',   label: 'APP 推送', icon: '🔔' },
  { value: 'inApp', label: '站内信',   icon: '💬' },
  { value: 'phone', label: '电话外呼', icon: '☎️' },
]
const LEVELS = [
  { value: 'red',    label: '红色预警', color: '#dc2626' },
  { value: 'orange', label: '橙色预警', color: '#ea580c' },
  { value: 'yellow', label: '黄色预警', color: '#ca8a04' },
  { value: 'blue',   label: '蓝色提醒', color: '#4a7a5e' },
]

const pref = reactive({
  channels: { sms: true, email: false, app: true, inApp: true, phone: false },
  levels: {
    red:    ['sms', 'app', 'inApp', 'phone'],
    orange: ['sms', 'app', 'inApp'],
    yellow: ['app', 'inApp'],
    blue:   ['inApp'],
  },
})

const loading = ref(false)
const saving = ref(false)
const dirty = ref(false)

watch(pref, () => { dirty.value = true }, { deep: true })

async function load() {
  loading.value = true
  try {
    const data = await dashboardApi.getUserPreference('u-admin')
    if (data && data.channels && data.levels) {
      Object.assign(pref.channels, data.channels)
      Object.keys(pref.levels).forEach((lvl) => {
        pref.levels[lvl] = Array.isArray(data.levels[lvl]) ? data.levels[lvl] : []
      })
    }
  } finally {
    loading.value = false
  }
}

const summary = computed(() => {
  const enabled = CHANNELS.filter((c) => pref.channels[c.value]).map((c) => c.label).join(' / ') || '无'
  return `已开启通道：${enabled}`
})

function toggleChannel(ch) {
  pref.channels[ch] = !pref.channels[ch]
}

function toggleLevelChannel(level, ch) {
  const idx = pref.levels[level].indexOf(ch)
  if (idx >= 0) pref.levels[level].splice(idx, 1)
  else pref.levels[level].push(ch)
}

async function save() {
  saving.value = true
  try {
    await dashboardApi.updateUserPreference({
      channels: pref.channels,
      levels: pref.levels,
    })
    dirty.value = false
    emit('notify', '通知偏好已保存')
  } catch (e) {
    emit('notify', e?.message ?? '保存失败')
  } finally {
    saving.value = false
  }
}

load()
</script>

<template>
  <section class="pref">
    <header class="head">
      <div>
        <h3>通知偏好</h3>
        <p>{{ summary }}；按等级选择通道，未勾选代表该等级下不通过该通道送达。</p>
      </div>
      <button class="primary" :disabled="!dirty || saving" @click="save">
        <Loader2 v-if="saving" class="spin" :size="14" />
        <Save v-else :size="14" /> 保存
      </button>
    </header>

    <div class="block">
      <h4><Bell :size="14" /> 通道总开关</h4>
      <p class="desc">勾选表示愿意接收；关闭将完全屏蔽该通道。</p>
      <div class="chips">
        <button
          v-for="ch in CHANNELS"
          :key="ch.value"
          class="chip"
          :class="{ active: pref.channels[ch.value] }"
          @click="toggleChannel(ch.value)"
        >
          <span class="icon">{{ ch.icon }}</span>{{ ch.label }}
        </button>
      </div>
    </div>

    <div class="block">
      <h4>预警等级渠道矩阵</h4>
      <p class="desc">按红 / 橙 / 黄 / 蓝分别配置接收通道。</p>
      <div class="matrix">
        <div v-for="lv in LEVELS" :key="lv.value" class="level-row" :style="{ '--level-color': lv.color }">
          <div class="level-name">{{ lv.label }}</div>
          <div class="level-channels">
            <button
              v-for="ch in CHANNELS"
              :key="ch.value"
              class="chip sm"
              :class="{ active: pref.levels[lv.value].includes(ch.value) }"
              :disabled="!pref.channels[ch.value]"
              @click="toggleLevelChannel(lv.value, ch.value)"
            >{{ ch.label }}</button>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.pref { display: flex; flex-direction: column; gap: 18px; }
.head { display: flex; justify-content: space-between; align-items: flex-start; border-bottom: 1px solid var(--dashboard-border, var(--c-brand-mist)); padding-bottom: 14px; }
.head h3 { margin: 0; font-size: 18px; }
.head p { margin: 4px 0 0; color: var(--dashboard-muted, var(--c-text-2)); font-size: 13px; }
.primary {
  background: linear-gradient(135deg, var(--c-brand-tint), var(--c-brand-hover)); color: white;
  border: none; padding: 6px 14px; border-radius: var(--r-sm);
  display: inline-flex; align-items: center; gap: 6px;
  cursor: pointer; font-size: 13px;
}
.primary:disabled { opacity: 0.5; cursor: not-allowed; }
.spin { animation: rotate 1s linear infinite; }
@keyframes rotate { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
.block { display: flex; flex-direction: column; gap: 8px; }
.block h4 { display: inline-flex; align-items: center; gap: 4px; margin: 0; font-size: 14px; color: var(--dashboard-text, var(--c-scrim)); }
.desc { margin: 0; font-size: 12px; color: var(--dashboard-muted, var(--c-text-2)); }
.chips { display: flex; flex-wrap: wrap; gap: 8px; }
.chip {
  display: inline-flex; align-items: center; gap: 4px;
  background: var(--dashboard-muted-bg, var(--c-brand-mist));
  border: 1px solid transparent;
  border-radius: var(--r-pill); padding: 5px 12px; cursor: pointer;
  font-size: 13px; color: var(--dashboard-text, var(--c-ink-1));
}
.chip:hover { border-color: var(--c-brand-tint); }
.chip.active { background: linear-gradient(135deg, var(--sys-blue-soft), var(--sys-blue-soft)); border-color: var(--c-brand-tint); color: var(--c-brand-deep); }
.chip.sm { padding: 3px 10px; font-size: 12px; }
.chip:disabled { opacity: 0.4; cursor: not-allowed; }
.matrix { display: flex; flex-direction: column; gap: 10px; }
.level-row {
  display: grid; grid-template-columns: 96px 1fr; align-items: center;
  border: 1px solid var(--dashboard-border, var(--c-brand-mist));
  border-left: 3px solid var(--level-color, var(--c-brand-tint));
  border-radius: var(--r-sm); padding: 10px 14px;
}
.level-name { font-size: 13px; font-weight: 600; color: var(--level-color, var(--c-scrim)); }
.level-channels { display: flex; flex-wrap: wrap; gap: 6px; }
</style>
