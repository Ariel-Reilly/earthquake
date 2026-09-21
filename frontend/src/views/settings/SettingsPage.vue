<script setup>
import { computed, reactive, ref, watch } from 'vue'
import {
  Bell,
  Building2,
  Database,
  Globe,
  KeyRound,
  Laptop,
  Moon,
  RefreshCcw,
  Save,
  Server,
  ShieldCheck,
  Smartphone,
  Trash2,
  UserCircle,
} from 'lucide-vue-next'
import { getAuthUser, getRoleLabel } from '../../stores/auth'

const emit = defineEmits(['notify'])

const tabs = [
  { id: 'general', label: '基本设置', icon: Building2 },
  { id: 'preferences', label: '个人偏好', icon: UserCircle },
  { id: 'warning', label: '预警与通知', icon: Bell },
  { id: 'security', label: '账号与安全', icon: ShieldCheck },
  { id: 'integration', label: '数据与接口', icon: Server },
]
const activeTab = ref('general')

const user = computed(() => getAuthUser() ?? { name: '李晨曦', role: 'admin' })

const STORAGE_KEY = 'disasterguard_settings'

const defaults = {
  platformName: '地脉智巡',
  timezone: 'Asia/Shanghai',
  refreshInterval: 30,
  mapProvider: 'auto',
  warningChannels: { sms: true, email: true, station: true, phone: false },
  displacementThreshold: 10,
  rainfallThreshold: 50,
  mfaEnabled: false,
  autoSignOut: true,
  dataRetention: 180,
  backupEnabled: true,
  // 个人偏好 P2-2
  theme: 'auto',           // 'light' | 'dark' | 'auto'
  language: 'zh-CN',       // 'zh-CN' | 'en-US'
  landingPage: '/dashboard',
  compactMode: false,
  quietHoursEnabled: false,
  quietStart: '22:00',
  quietEnd: '08:00',
  notificationLevels: {
    red:    { sms: true,  email: true,  station: true,  phone: true  },
    orange: { sms: true,  email: true,  station: true,  phone: false },
    yellow: { sms: false, email: true,  station: true,  phone: false },
    blue:   { sms: false, email: false, station: true,  phone: false },
  },
}

const settings = reactive({ ...defaults })

function loadSettings() {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY)
    if (!raw) return
    const parsed = JSON.parse(raw)
    Object.assign(settings, defaults, parsed)
  } catch {
    // 忽略损坏的本地设置
  }
}

function saveSettings() {
  window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ ...settings }))
}

const isDirty = ref(false)
watch(settings, () => { isDirty.value = true }, { deep: true })

function persist() {
  saveSettings()
  applyPreferences()
  isDirty.value = false
  emit('notify', '设置已保存')
}

/**
 * P2-2：应用个人偏好到运行时。
 * - 主题：写入 <html data-theme> 让全局 CSS 切换
 * - 语言：写入 localStorage，由后续 i18n 模块读取（当前仅占位）
 * - 默认首页：写入 localStorage；登录跳转时由 auth.js 读取
 * - 紧凑模式：写入 <html data-density>，全局字号 / 间距 -1
 * - 免打扰时段：写入 localStorage，由通知服务读取做静默判断
 */
function applyPreferences() {
  const html = document.documentElement
  if (settings.theme === 'dark') html.dataset.theme = 'dark'
  else if (settings.theme === 'light') html.dataset.theme = 'light'
  else html.removeAttribute('data-theme')
  // 紧凑模式
  if (settings.compactMode) html.dataset.density = 'compact'
  else html.removeAttribute('data-density')
  window.localStorage.setItem('disasterguard_language', settings.language)
  // 默认首页
  window.localStorage.setItem('disasterguard_landing_page', settings.landingPage)
  // 免打扰
  window.localStorage.setItem(
    'disasterguard_quiet_hours',
    JSON.stringify({
      enabled: settings.quietHoursEnabled,
      start: settings.quietStart,
      end: settings.quietEnd,
    }),
  )
}

/**
 * 启动时回放一次偏好，使刷新后保留上次选择。
 * 但不要触发 isDirty（与 watch(settings) 解耦）。
 */
function bootstrapPreferences() {
  applyPreferences()
}

function resetToDefaults() {
  Object.assign(settings, defaults)
  saveSettings()
  isDirty.value = false
  emit('notify', '已恢复默认设置')
}

const timezoneOptions = [
  { value: 'Asia/Shanghai', label: '中国标准时间（UTC+8）' },
  { value: 'Asia/Urumqi', label: '乌鲁木齐时间（UTC+6）' },
  { value: 'Asia/Harbin', label: '哈尔滨时间（UTC+8）' },
]
const intervalOptions = [
  { value: 5, label: '5 秒' },
  { value: 15, label: '15 秒' },
  { value: 30, label: '30 秒' },
  { value: 60, label: '60 秒' },
]
const retentionOptions = [
  { value: 90, label: '90 天' },
  { value: 180, label: '180 天' },
  { value: 365, label: '365 天' },
  { value: 0, label: '永久保留' },
]
const channelOptions = [
  { id: 'sms', label: '短信通知', desc: '高风险预警实时短信提醒' },
  { id: 'email', label: '邮件通知', desc: '每日汇总与重要事件邮件' },
  { id: 'station', label: '站内通知', desc: '平台内弹窗与消息中心' },
  { id: 'phone', label: '电话外呼', desc: '红色预警自动电话提醒' },
]

/* ===X= 个人偏好 选项 P2-2 ===X= */
const themeOptions = [
  { value: 'light', label: '浅色主题', desc: '适合白天办公，文字与图标对比度高' },
  { value: 'dark', label: '深色主题', desc: '适合夜间值守与指挥大屏场景' },
  { value: 'auto', label: '跟随系统', desc: '根据操作系统暗色模式自动切换' },
]
const languageOptions = [
  { value: 'zh-CN', label: '简体中文', desc: '默认中文界面' },
  { value: 'en-US', label: 'English', desc: '英文界面（实验性，部分文案未翻译）' },
]
const landingOptions = [
  { value: '/dashboard', label: '综合态势大屏', desc: '总览所有灾害态势与关键指标' },
  { value: '/dashboard/todo', label: '待办中心', desc: '进入平台直接处理个人待办' },
  { value: '/dashboard/warnings', label: '预警中心', desc: '快速进入预警处置工作流' },
  { value: '/dashboard/confirmation-workbench', label: '人工确认工作台', desc: 'AI 巡检待确认事件集中处理' },
]
const LEVEL_TITLES = {
  red: '红色预警',
  orange: '橙色预警',
  yellow: '黄色预警',
  blue: '蓝色提醒',
}
const LEVEL_DESCS = {
  red: '最高级别，自动外呼 + 短信 + 站内',
  orange: '高级别，短信 + 邮件 + 站内',
  yellow: '中等级别，邮件 + 站内',
  blue: '关注级，仅站内通知',
}
function inQuietHours() {
  if (!settings.quietHoursEnabled) return false
  const now = new Date()
  const cur = now.getHours() * 60 + now.getMinutes()
  const [sh, sm] = String(settings.quietStart || '00:00').split(':').map(Number)
  const [eh, em] = String(settings.quietEnd || '00:00').split(':').map(Number)
  const startMin = sh * 60 + sm
  const endMin = eh * 60 + em
  if (startMin === endMin) return false
  if (startMin < endMin) return cur >= startMin && cur < endMin
  // 跨夜：22:00 → 08:00
  return cur >= startMin || cur < endMin
}
const quietHint = computed(() => {
  if (!settings.quietHoursEnabled) return '免打扰未开启'
  const now = new Date()
  const cur = now.getHours() * 60 + now.getMinutes()
  const [sh, sm] = String(settings.quietStart || '00:00').split(':').map(Number)
  const [eh, em] = String(settings.quietEnd || '00:00').split(':').map(Number)
  const startMin = sh * 60 + sm
  const endMin = eh * 60 + em
  const inQuiet = (startMin < endMin)
    ? cur >= startMin && cur < endMin
    : cur >= startMin || cur < endMin
  return inQuiet
    ? `当前处于免打扰时段（${settings.quietStart} - ${settings.quietEnd}），非紧急预警也会静音`
    : `免打扰 ${settings.quietStart} - ${settings.quietEnd}，当前不在该时段`
})

const passwordForm = reactive({ current: '', next: '', confirm: '' })
const passwordHint = ref('')
const passwordOk = ref(false)
const passwordError = ref('')

function changePassword() {
  passwordError.value = ''
  passwordHint.value = ''
  passwordOk.value = false
  if (!passwordForm.current || !passwordForm.next || !passwordForm.confirm) {
    passwordError.value = '请完整填写旧密码与新密码'
    return
  }
  if (passwordForm.current !== '123456') {
    passwordError.value = '旧密码不正确'
    return
  }
  if (passwordForm.next.length < 6) {
    passwordError.value = '新密码长度至少 6 位'
    return
  }
  if (passwordForm.next !== passwordForm.confirm) {
    passwordError.value = '两次输入的新密码不一致'
    return
  }
  if (passwordForm.next === passwordForm.current) {
    passwordError.value = '新密码不能与旧密码相同'
    return
  }
  passwordOk.value = true
  passwordHint.value = '密码已更新，下次登录请使用新密码'
  passwordForm.current = ''
  passwordForm.next = ''
  passwordForm.confirm = ''
}

const sessions = ref([
  { id: 1, device: 'Windows · Chrome 126', location: '本地工作台', current: true, last: '当前会话', icon: Laptop },
  { id: 2, device: 'iPhone 15 · Safari', location: '重庆 · 移动网络', current: false, last: '2 小时前', icon: Smartphone },
  { id: 3, device: 'MacBook Pro · Edge', location: '重庆 · 企业网络', current: false, last: '昨天 18:42', icon: Laptop },
])

function revokeSession(id) {
  sessions.value = sessions.value.filter((item) => item.id !== id)
  emit('notify', '已下线该设备会话')
}

const serviceStatus = ref([
  { name: 'RESTful API 网关', detail: 'http://localhost:8080/api', state: '正常', color: 'teal' },
  { name: 'MQTT 数据接入', detail: 'mqtt://mqtt.geo-sentry.local:1883', state: '正常', color: 'teal' },
  { name: '时序数据库', detail: 'TDengine · 128 设备在线', state: '正常', color: 'teal' },
  { name: '消息服务', detail: '站内通知推送通道', state: '正常', color: 'teal' },
])

function runServiceCheck() {
  serviceStatus.value.forEach((item) => { item.state = '检测中' })
  emit('notify', '正在检测服务状态...')
  window.setTimeout(() => {
    serviceStatus.value = [
      { name: 'RESTful API 网关', detail: 'http://localhost:8080/api', state: '正常', color: 'teal' },
      { name: 'MQTT 数据接入', detail: 'mqtt://mqtt.geo-sentry.local:1883', state: '正常', color: 'teal' },
      { name: '时序数据库', detail: 'TDengine · 128 设备在线', state: '正常', color: 'teal' },
      { name: '消息服务', detail: '站内通知推送通道', state: '正常', color: 'teal' },
    ]
    emit('notify', '服务状态检测完成：全部正常')
  }, 900)
}

const passwordStrength = computed(() => {
  const value = passwordForm.next
  if (!value) return { level: 0, label: '' }
  let score = 0
  if (value.length >= 8) score += 1
  if (/[A-Z]/.test(value) && /[a-z]/.test(value)) score += 1
  if (/\d/.test(value) && /[^A-Za-z0-9]/.test(value)) score += 1
  return { level: score + 1, label: ['弱', '一般', '较强', '强'][score] ?? '强' }
})

loadSettings()
bootstrapPreferences()
</script>

<template>
  <div class="settings-page">
    <div class="settings-tabs">
      <button
        v-for="tab in tabs"
        :key="tab.id"
        class="settings-tab"
        :class="{ active: activeTab === tab.id }"
        @click="activeTab = tab.id"
      >
        <component :is="tab.icon" :size="16" />
        {{ tab.label }}
      </button>
    </div>

    <!-- 基本设置 -->
    <Transition name="tab" mode="out-in" :duration="220">
      <section v-if="activeTab === 'general'" class="panel settings-panel">
      <div class="panel-header">
        <span class="health-label"><i></i>{{ settings.platformName }}</span>
      </div>

      <div class="settings-body">
        <label class="settings-row">
          <span class="settings-label"><strong>平台名称</strong><small>显示在侧边栏与登录页的品牌名称</small></span>
          <input v-model="settings.platformName" class="settings-input" maxlength="20" />
        </label>

        <label class="settings-row">
          <span class="settings-label"><strong>系统时区</strong><small>预警时间与巡检记录统一使用该时区</small></span>
          <select v-model="settings.timezone" class="settings-input">
            <option v-for="item in timezoneOptions" :key="item.value" :value="item.value">{{ item.label }}</option>
          </select>
        </label>

        <label class="settings-row">
          <span class="settings-label"><strong>数据刷新间隔</strong><small>态势总览与设备状态自动刷新的频率</small></span>
          <select v-model="settings.refreshInterval" class="settings-input">
            <option v-for="item in intervalOptions" :key="item.value" :value="item.value">{{ item.label }}</option>
          </select>
        </label>

        <label class="settings-row">
          <span class="settings-label"><strong>地图底图</strong><small>选择地图展示风格，自动模式根据区域切换</small></span>
          <select v-model="settings.mapProvider" class="settings-input">
            <option value="auto">自动（推荐）</option>
            <option value="dark">深色地形</option>
            <option value="light">浅色地形</option>
            <option value="satellite">卫星影像</option>
          </select>
        </label>
      </div>

      <div class="settings-foot">
        <button class="secondary-btn" @click="resetToDefaults"><RefreshCcw :size="14" />恢复默认</button>
        <button class="primary-btn" :disabled="!isDirty" @click="persist"><Save :size="14" />保存设置</button>
      </div>
    </section>

    <!-- 个人偏好 -->
    <section v-else-if="activeTab === 'preferences'" class="panel settings-panel">
      <div class="panel-header">
        <span class="health-label"><i></i>{{ themeOptions.find((t) => t.value === settings.theme)?.label ?? '自动' }}</span>
      </div>

      <div class="settings-body">
        <div class="settings-subhead"><strong>外观</strong><small>主题与界面密度</small></div>

        <div class="theme-grid">
          <button
            v-for="item in themeOptions"
            :key="item.value"
            type="button"
            class="theme-card"
            :class="{ active: settings.theme === item.value }"
            @click="settings.theme = item.value; applyPreferences()"
          >
            <component
              :is="item.value === 'dark' ? Moon : (item.value === 'auto' ? Laptop : Globe)"
              :size="22"
            />
            <strong>{{ item.label }}</strong>
            <small>{{ item.desc }}</small>
          </button>
        </div>

        <label class="settings-row switch-row">
          <span class="settings-label"><strong>紧凑模式</strong><small>字号与间距缩小 10%，适合小屏与多窗口场景</small></span>
          <span class="settings-toggle" :class="{ on: settings.compactMode }">
            <input v-model="settings.compactMode" type="checkbox" @change="applyPreferences()" />
            <i></i>
          </span>
        </label>

        <div class="settings-subhead"><strong>语言与首页</strong><small>登录后默认进入的页面</small></div>

        <label class="settings-row">
          <span class="settings-label"><strong>界面语言</strong><small>切换后立即生效</small></span>
          <select v-model="settings.language" class="settings-input" @change="applyPreferences()">
            <option v-for="item in languageOptions" :key="item.value" :value="item.value">{{ item.label }} · {{ item.desc }}</option>
          </select>
        </label>

        <label class="settings-row">
          <span class="settings-label"><strong>默认登录首页</strong><small>登录后跳转的第一个页面</small></span>
          <select v-model="settings.landingPage" class="settings-input" @change="applyPreferences()">
            <option v-for="item in landingOptions" :key="item.value" :value="item.value">{{ item.label }} · {{ item.desc }}</option>
          </select>
        </label>

        <div class="settings-subhead"><strong>免打扰</strong><small>在指定时段内不推送非紧急预警</small></div>

        <label class="settings-row switch-row">
          <span class="settings-label"><strong>开启免打扰</strong><small>{{ quietHint }}</small></span>
          <span class="settings-toggle" :class="{ on: settings.quietHoursEnabled }">
            <input v-model="settings.quietHoursEnabled" type="checkbox" @change="applyPreferences()" />
            <i></i>
          </span>
        </label>

        <div class="quiet-range">
          <label class="settings-row">
            <span class="settings-label"><strong>起始时间</strong><small>免打扰开始</small></span>
            <input v-model="settings.quietStart" type="time" class="settings-input narrow" :disabled="!settings.quietHoursEnabled" @change="applyPreferences()" />
          </label>
          <span class="quiet-arrow">→</span>
          <label class="settings-row">
            <span class="settings-label"><strong>结束时间</strong><small>免打扰结束（可跨夜）</small></span>
            <input v-model="settings.quietEnd" type="time" class="settings-input narrow" :disabled="!settings.quietHoursEnabled" @change="applyPreferences()" />
          </label>
        </div>

        <div class="settings-subhead"><strong>分级通知</strong><small>不同预警等级走不同渠道组合</small></div>

        <div v-for="(channels, level) in settings.notificationLevels" :key="level" class="level-row" :class="`level-${level}`">
          <div class="level-meta">
            <strong>{{ LEVEL_TITLES[level] }}</strong>
            <small>{{ LEVEL_DESCS[level] }}</small>
          </div>
          <div class="level-channels">
            <label v-for="ch in channelOptions" :key="ch.id" class="channel-chip" :class="{ on: channels[ch.id] }">
              <input v-model="channels[ch.id]" type="checkbox" />
              <span>{{ ch.label.replace('通知', '').replace('外呼', '') }}</span>
            </label>
          </div>
        </div>
      </div>

      <div class="settings-foot">
        <button class="secondary-btn" @click="resetToDefaults"><RefreshCcw :size="14" />恢复默认</button>
        <button class="primary-btn" :disabled="!isDirty" @click="persist"><Save :size="14" />保存设置</button>
      </div>
    </section>

    <!-- 预警与通知 -->
    <section v-else-if="activeTab === 'warning'" class="panel settings-panel">
      <div class="panel-header">
        <span class="health-label"><i></i>通知渠道 {{ Object.values(settings.warningChannels).filter(Boolean).length }}/4</span>
      </div>

      <div class="settings-body">
        <div class="settings-subhead"><strong>预警阈值</strong><small>达到阈值自动生成对应级别预警</small></div>

        <label class="settings-row">
          <span class="settings-label"><strong>位移速率阈值</strong><small>单点位移速率超过该值触发黄色预警（mm/日）</small></span>
          <input v-model.number="settings.displacementThreshold" type="number" min="1" max="100" class="settings-input narrow" />
        </label>

        <label class="settings-row">
          <span class="settings-label"><strong>小时雨量阈值</strong><small>1 小时降雨量超过该值触发蓝色提醒（mm）</small></span>
          <input v-model.number="settings.rainfallThreshold" type="number" min="5" max="200" class="settings-input narrow" />
        </label>

        <div class="settings-subhead"><strong>通知渠道</strong><small>开启后将通过所选渠道推送预警信息</small></div>

        <label v-for="item in channelOptions" :key="item.id" class="settings-row switch-row">
          <span class="settings-label"><strong>{{ item.label }}</strong><small>{{ item.desc }}</small></span>
          <span class="settings-toggle" :class="{ on: settings.warningChannels[item.id] }">
            <input v-model="settings.warningChannels[item.id]" type="checkbox" />
            <i></i>
          </span>
        </label>
      </div>

      <div class="settings-foot">
        <button class="secondary-btn" @click="resetToDefaults"><RefreshCcw :size="14" />恢复默认</button>
        <button class="primary-btn" :disabled="!isDirty" @click="persist"><Save :size="14" />保存设置</button>
      </div>
    </section>

    <!-- 账号与安全 -->
    <section v-else-if="activeTab === 'security'" class="panel settings-panel">
      <div class="panel-header">
        <span class="health-label"><i></i>{{ getRoleLabel(user.role) }}</span>
      </div>

      <div class="settings-body">
        <div class="settings-subhead"><strong>修改密码</strong><small>演示环境旧密码为 123456</small></div>

        <label class="settings-row">
          <span class="settings-label"><strong>当前密码</strong><small>用于验证身份</small></span>
          <input v-model="passwordForm.current" type="password" autocomplete="current-password" class="settings-input" placeholder="输入当前密码" />
        </label>

        <label class="settings-row">
          <span class="settings-label"><strong>新密码</strong><small>至少 6 位，建议包含字母与数字</small></span>
          <span class="settings-password-wrap">
            <input v-model="passwordForm.next" type="password" autocomplete="new-password" class="settings-input" placeholder="输入新密码" />
            <em v-if="passwordStrength.level" class="strength" :class="`s${passwordStrength.level}`">{{ passwordStrength.label }}</em>
          </span>
        </label>

        <label class="settings-row">
          <span class="settings-label"><strong>确认新密码</strong><small>再次输入以确认无误</small></span>
          <input v-model="passwordForm.confirm" type="password" autocomplete="new-password" class="settings-input" placeholder="再次输入新密码" />
        </label>

        <p v-if="passwordError" class="settings-error">{{ passwordError }}</p>
        <p v-if="passwordOk" class="settings-success">{{ passwordHint }}</p>

        <div class="settings-foot inline">
          <button class="primary-btn" @click="changePassword"><KeyRound :size="14" />更新密码</button>
        </div>

        <div class="settings-subhead"><strong>登录设备</strong><small>管理已登录本账号的设备会话</small></div>

        <div v-for="item in sessions" :key="item.id" class="settings-row device-row">
          <span class="settings-label device-label">
            <component :is="item.icon" :size="18" />
            <span><strong>{{ item.device }}</strong><small>{{ item.location }} · {{ item.last }}</small></span>
          </span>
          <span class="device-actions">
            <em v-if="item.current" class="device-current">当前设备</em>
            <button v-else class="text-btn danger-text" @click="revokeSession(item.id)"><Trash2 :size="13" />下线</button>
          </span>
        </div>
      </div>
    </section>

    <!-- 数据与接口 -->
    <section v-else-if="activeTab === 'integration'" class="panel settings-panel">
      <div class="panel-header">
        <div class="heading-actions">
          <button class="secondary-btn" @click="runServiceCheck"><RefreshCcw :size="14" />检测服务</button>
        </div>
      </div>

      <div class="settings-body">
        <div class="settings-subhead"><strong>服务状态</strong><small>实时查看平台各服务连接情况</small></div>

        <div v-for="item in serviceStatus" :key="item.name" class="settings-row device-row">
          <span class="settings-label device-label">
            <Database :size="18" />
            <span><strong>{{ item.name }}</strong><small>{{ item.detail }}</small></span>
          </span>
          <em class="service-state" :class="item.color === 'teal' ? 'ok' : 'checking'">{{ item.state }}</em>
        </div>

        <div class="settings-subhead"><strong>数据保留策略</strong><small>历史监测与巡检数据在平台中的保留周期</small></div>

        <label class="settings-row">
          <span class="settings-label"><strong>数据保留周期</strong><small>到期数据将自动归档到冷存储</small></span>
          <select v-model="settings.dataRetention" class="settings-input">
            <option v-for="item in retentionOptions" :key="item.value" :value="item.value">{{ item.label }}</option>
          </select>
        </label>

        <label class="settings-row switch-row">
          <span class="settings-label"><strong>自动备份</strong><small>每日凌晨自动备份核心数据库</small></span>
          <span class="settings-toggle" :class="{ on: settings.backupEnabled }">
            <input v-model="settings.backupEnabled" type="checkbox" />
            <i></i>
          </span>
        </label>
      </div>

      <div class="settings-foot">
        <button class="secondary-btn" @click="resetToDefaults"><RefreshCcw :size="14" />恢复默认</button>
        <button class="primary-btn" :disabled="!isDirty" @click="persist"><Save :size="14" />保存设置</button>
      </div>
    </section>
    </Transition>
  </div>
</template>
