<script setup>
/**
 * 全局分级警报弹窗（四级颜色：红 / 橙 / 黄 / 蓝）。
 *
 * 放置位置：MainLayout 顶层（一次挂载，覆盖所有 /dashboard、/admin 页面），
 * 通过 Teleport 到 body，保证 fixed 定位不受布局层 overflow / transform 影响。
 *
 * 分级策略（与后端 AI 研判分数区间一致）：
 *   - 红（critical，AI ≥80 / 红色预警）：满屏红色遮罩 + 深红卡片 + 闪烁 + 持续警报音，
 *     必须点击「确认收到 / 查看详情」才会停止声音并关闭；
 *   - 橙（warning，AI 60-80 / 橙色预警）、黄（caution，AI 35-60 / 黄色预警）、
 *     蓝（info，AI <35 / 蓝色预警）：普通弹窗提示（对应颜色、不响持续警报音），
 *     3 秒后自动跳转到该预警详情页；可点「查看详情」立即跳转或点「关闭」取消跳转。
 *
 * 触发链路：
 *   useWarningAlarm 监听 realtimeService 的实时事件 → 发现"新 ID + 待确认 + 红/橙/黄/蓝"
 *   → 按级别拆分入队 → 红色满屏响警报音，橙/黄/蓝启动 3 秒倒计时。
 *
 * 本组件不修改任何预警列表页 / 详情页的业务逻辑，只消费它们已有的数据与路由。
 */

import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { AlertTriangle, BellRing, Clock3, MapPin } from 'lucide-vue-next'
import { useAlarmSound } from '../../composables/useAlarmSound'
import { useWarningAlarm } from '../../composables/useWarningAlarm'
import {
  ALARM_SEVERITY,
  severityLabelOf,
} from '../../utils/alarmWarning'

/** 普通弹窗（橙/黄/蓝）停留时长：到期自动跳转详情页（需求指定 3 秒）。 */
const AUTO_JUMP_DELAY_MS = 3000

const router = useRouter()

/** 是否有弹窗（任意级别）可见 */
const visible = ref(false)
/** 严重级（红色）待处理队列 */
const criticalQueue = ref([])
/** 普通弹窗（橙/黄/蓝）待处理队列 */
const popupQueue = ref([])
/** 当前展示的严重级预警 */
const currentCritical = ref(null)
/** 倒计时进度条的动画重播 token（每次重新计时 +1） */
const autoJumpToken = ref(0)

/** @type {number|null} 普通弹窗自动跳转定时器 */
let autoJumpTimer = null

const {
  playing: audioPlaying,
  blocked: audioBlocked,
  unsupported: audioUnsupported,
  start: startAlarm,
  stop: stopAlarm,
  resume: resumeAlarm,
  unlock: unlockAlarm,
} = useAlarmSound()

/** 首次用户手势时预热音频，避免"未交互页面收到红色预警时警报音被浏览器拦截" */
function warmUpAudio() {
  unlockAlarm().catch(() => {})
  window.removeEventListener('pointerdown', warmUpAudio, true)
  window.removeEventListener('keydown', warmUpAudio, true)
}

onMounted(() => {
  window.addEventListener('pointerdown', warmUpAudio, { capture: true, once: true })
  window.addEventListener('keydown', warmUpAudio, { capture: true, once: true })
})

/** 「确认收到」按钮，满屏警报出现时自动聚焦，键盘用户可直接回车确认 */
const confirmButtonRef = ref(null)

/** 当前弹窗形态：'critical' 满屏红色 / 'popup' 普通弹窗 / null 无弹窗 */
const mode = computed(() => {
  if (!visible.value) {
    return null
  }
  return criticalQueue.value.length > 0 ? ALARM_SEVERITY.CRITICAL : 'popup'
})

/** 当前展示的预警：严重级看 currentCritical，普通弹窗看队列最新一条 */
const activeWarning = computed(() => {
  if (mode.value === ALARM_SEVERITY.CRITICAL) {
    return currentCritical.value
  }
  const queue = popupQueue.value
  return queue.length > 0 ? queue[queue.length - 1] : null
})

/** 普通弹窗当前颜色档位：warning（橙）/ caution（黄）/ info（蓝） */
const popupSeverity = computed(
  () => activeWarning.value?.severity || ALARM_SEVERITY.WARNING
)
/** 弹窗徽标文案（按档位而非原始等级，确保颜色与文案一致） */
const severityText = computed(() =>
  severityLabelOf(mode.value === ALARM_SEVERITY.CRITICAL ? ALARM_SEVERITY.CRITICAL : popupSeverity.value)
)

const pendingCount = computed(() =>
  mode.value === ALARM_SEVERITY.CRITICAL
    ? criticalQueue.value.length
    : popupQueue.value.length
)
const levelText = computed(() => activeWarning.value?.level || '')
const titleText = computed(
  () => activeWarning.value?.title || activeWarning.value?.name || '发现新的紧急预警'
)
const descText = computed(
  () =>
    activeWarning.value?.desc ||
    activeWarning.value?.message ||
    '收到一条待确认的高等级预警，请立即核实并处置。'
)
const pointText = computed(() => activeWarning.value?.point || '')
const timeText = computed(() => {
  const raw = activeWarning.value?.time || activeWarning.value?.updatedAt || ''
  return raw ? String(raw) : ''
})
const bizNoText = computed(() => activeWarning.value?.bizNo || '')

/** 按当前级别同步弹窗可见性、警报音与自动跳转定时器。 */
function refreshPopupState() {
  const hasCritical = criticalQueue.value.length > 0
  const hasPopup = popupQueue.value.length > 0
  visible.value = hasCritical || hasPopup

  if (hasCritical) {
    // 满屏警报期间不跑自动跳转，避免"还没确认就被带走"
    stopAutoJumpTimer()
    startAlarm()
    nextTick(() => {
      confirmButtonRef.value?.focus?.()
    })
    return
  }

  stopAlarm()
  if (hasPopup) {
    startAutoJumpTimer()
  } else {
    stopAutoJumpTimer()
  }
}

/**
 * 收到一批新的待确认预警：按级别入队后刷新弹窗状态。
 * 红色进满屏队列，橙/黄/蓝进普通弹窗队列（各自保留 severity 决定颜色）。
 *
 * @param {Array<object>} batch 新一轮需要警报的预警（已带 severity）
 */
function handleAlarmBatch(batch) {
  if (!Array.isArray(batch) || batch.length === 0) {
    return
  }
  const critical = batch.filter((item) => item.severity === ALARM_SEVERITY.CRITICAL)
  const popup = batch.filter(
    (item) => item.severity && item.severity !== ALARM_SEVERITY.CRITICAL
  )
  if (critical.length === 0 && popup.length === 0) {
    return
  }
  if (critical.length > 0) {
    criticalQueue.value = [...criticalQueue.value, ...critical]
    currentCritical.value = critical[critical.length - 1]
  }
  if (popup.length > 0) {
    popupQueue.value = [...popupQueue.value, ...popup]
  }
  refreshPopupState()
}

/** 关闭严重级警报（仅清空红色队列，普通弹窗队列若还有会接着弹）。 */
function dismissCritical() {
  criticalQueue.value = []
  currentCritical.value = null
  refreshPopupState()
}

/** 关闭普通弹窗（同时取消 3 秒自动跳转）。 */
function dismissPopup() {
  popupQueue.value = []
  refreshPopupState()
}

/** 点击「确认收到 / 关闭」：按当前级别收尾，不做任何业务写操作。 */
function acknowledge() {
  if (mode.value === ALARM_SEVERITY.CRITICAL) {
    dismissCritical()
    return
  }
  dismissPopup()
}

/**
 * 点击「查看详情」：立即关闭弹窗并跳转到该预警详情页。
 */
function viewDetail() {
  const item = activeWarning.value
  const id = item?.id
  if (mode.value === ALARM_SEVERITY.CRITICAL) {
    dismissCritical()
  } else {
    dismissPopup()
  }
  navigateToWarning(id)
}

/**
 * 跳转到预警详情页（唯一的路由副作用）。
 *
 * @param {number|string|undefined} id 预警 ID
 */
function navigateToWarning(id) {
  if (id === undefined || id === null || id === '') {
    return
  }
  router.push(`/dashboard/warnings/${id}`).catch((err) => {
    console.warn('[GlobalAlarmModal] 跳转预警详情失败:', err?.message ?? err)
  })
}

/** 启动橙色弹窗的 3 秒自动跳转计时（重复触发会重置计时与进度条）。 */
function startAutoJumpTimer() {
  stopAutoJumpTimer()
  autoJumpToken.value += 1
  autoJumpTimer = window.setTimeout(() => {
    autoJumpTimer = null
    jumpToLatestWarning()
  }, AUTO_JUMP_DELAY_MS)
}

/** 清除自动跳转计时。 */
function stopAutoJumpTimer() {
  if (autoJumpTimer !== null) {
    window.clearTimeout(autoJumpTimer)
    autoJumpTimer = null
  }
}

/** 倒计时到期：关闭普通弹窗并跳转到队列中最新的预警详情页。 */
function jumpToLatestWarning() {
  const queue = popupQueue.value
  const item = queue.length > 0 ? queue[queue.length - 1] : null
  const id = item?.id
  popupQueue.value = []
  refreshPopupState()
  navigateToWarning(id)
}

/**
 * 点击满屏警报任意区域 / 「开启警报声」按钮：借用户手势恢复播放。
 * 不再只依赖 audioBlocked 标记——部分浏览器会静默 suspended，只要当前没在响就重试。
 * 按钮点击会 stop 传播，因此不会和"关闭"动作互相抢跑。
 */
async function handleCriticalOverlayClick() {
  if (mode.value !== ALARM_SEVERITY.CRITICAL || audioPlaying.value) {
    return
  }
  await resumeAlarm()
  if (mode.value !== ALARM_SEVERITY.CRITICAL) {
    // 恢复期间用户已经点了按钮关闭：立即收掉刚创建的声音
    stopAlarm()
  }
}

/** 显式开启警报声（红色警报被 autoplay 拦截时使用）。 */
async function enableAlarmSound() {
  if (audioPlaying.value) return
  await unlockAlarm()
  await resumeAlarm()
}

useWarningAlarm({ onAlarm: handleAlarmBatch })

onBeforeUnmount(() => {
  stopAutoJumpTimer()
  stopAlarm()
  window.removeEventListener('pointerdown', warmUpAudio, true)
  window.removeEventListener('keydown', warmUpAudio, true)
})
</script>

<template>
  <Teleport to="body">
    <!-- ── 严重级：满屏红色警报（持续声音 + 闪烁，必须手动关闭） ── -->
    <div
      v-if="mode === 'critical'"
      class="alarm-overlay"
      role="alertdialog"
      aria-modal="true"
      aria-labelledby="global-alarm-title"
      @click="handleCriticalOverlayClick"
    >
      <div class="alarm-card">
        <div class="alarm-icon" aria-hidden="true">
          <AlertTriangle :size="44" :stroke-width="2.2" />
        </div>

        <div class="alarm-badge-row">
          <span class="alarm-level">{{ severityText }}</span>
          <span class="alarm-count">您有 {{ pendingCount }} 条未处理警报</span>
        </div>

        <h2 id="global-alarm-title" class="alarm-title">{{ titleText }}</h2>
        <p class="alarm-desc">{{ descText }}</p>

        <ul v-if="levelText || pointText || timeText || bizNoText" class="alarm-meta">
          <li v-if="levelText" class="alarm-meta-item">
            <span>预警等级 {{ levelText }}</span>
          </li>
          <li v-if="pointText" class="alarm-meta-item">
            <MapPin :size="14" aria-hidden="true" />
            <span>{{ pointText }}</span>
          </li>
          <li v-if="timeText" class="alarm-meta-item">
            <Clock3 :size="14" aria-hidden="true" />
            <span>{{ timeText }}</span>
          </li>
          <li v-if="bizNoText" class="alarm-meta-item">
            <span>编号 {{ bizNoText }}</span>
          </li>
        </ul>

        <p v-if="audioBlocked && !audioPlaying" class="alarm-hint">
          <span>浏览器限制了自动播放，请点击下方按钮开启警报声</span>
          <button type="button" class="alarm-sound-enable" @click.stop="enableAlarmSound">
            开启警报声
          </button>
        </p>
        <p v-else-if="audioUnsupported" class="alarm-hint">
          当前浏览器不支持 Web Audio，警报音不可用
        </p>

        <div class="alarm-actions">
          <button type="button" class="alarm-btn alarm-btn-ghost" @click.stop="viewDetail">
            查看详情
          </button>
          <button
            ref="confirmButtonRef"
            type="button"
            class="alarm-btn alarm-btn-primary"
            @click.stop="acknowledge"
          >
            确认收到
          </button>
        </div>
      </div>
    </div>

    <!-- ── 普通弹窗：橙 / 黄 / 蓝三档颜色，3 秒后自动跳转详情 ── -->
    <div
      v-else-if="mode === 'popup'"
      class="warn-overlay"
      role="alertdialog"
      aria-modal="true"
      aria-labelledby="global-warning-title"
    >
      <div class="warn-card" :class="['severity-' + popupSeverity]">
        <div class="warn-head">
          <span class="warn-icon" aria-hidden="true">
            <BellRing :size="18" :stroke-width="2.4" />
          </span>
          <span class="warn-level">{{ severityText }}</span>
          <span class="warn-count">您有 {{ pendingCount }} 条未处理警报</span>
        </div>

        <h3 id="global-warning-title" class="warn-title">{{ titleText }}</h3>
        <p class="warn-desc">{{ descText }}</p>

        <ul v-if="levelText || pointText || timeText || bizNoText" class="warn-meta">
          <li v-if="levelText" class="warn-meta-item">
            <span>预警等级 {{ levelText }}</span>
          </li>
          <li v-if="pointText" class="warn-meta-item">
            <MapPin :size="13" aria-hidden="true" />
            <span>{{ pointText }}</span>
          </li>
          <li v-if="timeText" class="warn-meta-item">
            <Clock3 :size="13" aria-hidden="true" />
            <span>{{ timeText }}</span>
          </li>
          <li v-if="bizNoText" class="warn-meta-item">
            <span>编号 {{ bizNoText }}</span>
          </li>
        </ul>

        <p class="warn-hint">3 秒后自动打开预警详情页</p>
        <div class="warn-progress" aria-hidden="true">
          <span :key="autoJumpToken" class="warn-progress-bar"></span>
        </div>

        <div class="warn-actions">
          <button type="button" class="warn-btn warn-btn-ghost" @click.stop="acknowledge">
            关闭
          </button>
          <button type="button" class="warn-btn warn-btn-primary" @click.stop="viewDetail">
            查看详情
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
/* ══════════════════════════════════════════════════════════
   严重级（红色）：满屏遮罩 + 深红卡片 + 闪烁
   遮罩色等价于需求指定的 rgba(255, 0, 0, 0.85)
   ══════════════════════════════════════════════════════════ */
.alarm-overlay {
  position: fixed;
  inset: 0;
  z-index: 99999;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgb(255 0 0 / 85%);
  font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Microsoft YaHei', sans-serif;
  backdrop-filter: blur(2px);
  animation:
    alarm-overlay-in 0.18s ease-out both,
    alarm-overlay-flash 1.4s ease-in-out 0.18s infinite;
}

.alarm-card {
  position: relative;
  width: min(560px, 100%);
  max-height: calc(100vh - 48px);
  padding: 30px 32px 26px;
  overflow-y: auto;
  color: #fff;
  text-align: center;
  background: linear-gradient(160deg, #b31212 0%, #870808 55%, #560202 100%);
  border: 2px solid rgb(255 92 92 / 75%);
  border-radius: 20px;
  box-shadow:
    0 24px 60px rgb(0 0 0 / 45%),
    inset 0 0 0 1px rgb(255 255 255 / 12%);
  animation: alarm-card-in 0.34s cubic-bezier(0.2, 0.9, 0.3, 1.25) both;
}

.alarm-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 72px;
  height: 72px;
  margin-bottom: 14px;

  /* 红色预警整卡使用纯红色系：图标/徽标不再复用橙色警示黄，避免红橙混色 */
  color: #fff;
  background: rgb(0 0 0 / 24%);
  border: 1px solid rgb(255 255 255 / 55%);
  border-radius: 50%;
  animation: alarm-icon-blink 1s ease-in-out infinite;
}

.alarm-badge-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  justify-content: center;
}

.alarm-level {
  display: inline-flex;
  align-items: center;
  padding: 4px 14px;
  font-size: 13px;
  font-weight: 800;
  letter-spacing: 0.04em;

  /* 白底深红字：红色预警的等级徽标 */
  color: #8a0a0a;
  background: #fff;
  border-radius: 999px;
}

.alarm-count {
  display: inline-flex;
  align-items: center;
  padding: 4px 12px;
  font-size: 12px;
  font-weight: 600;
  color: #ffdede;
  background: rgb(0 0 0 / 28%);
  border-radius: 999px;
}

.alarm-title {
  margin: 14px 0 0;
  font-size: 24px;
  font-weight: 800;
  line-height: 1.35;
  color: #fff;
  overflow-wrap: anywhere;
}

.alarm-desc {
  margin: 10px 0 0;
  font-size: 14px;
  line-height: 1.65;
  color: rgb(255 255 255 / 88%);
  overflow-wrap: anywhere;
}

.alarm-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
  align-items: center;
  justify-content: center;
  padding: 0;
  margin: 16px 0 0;
  font-size: 13px;
  color: rgb(255 255 255 / 82%);
  list-style: none;
}

.alarm-meta-item {
  display: inline-flex;
  gap: 6px;
  align-items: center;
}

.alarm-hint {
  padding: 8px 12px;
  margin: 16px 0 0;
  font-size: 12px;
  line-height: 1.5;

  /* 红色警报的提示行同样保持红白体系，不混入橙色 */
  color: #ffe3e3;
  background: rgb(0 0 0 / 30%);
  border: 1px dashed rgb(255 227 227 / 55%);
  border-radius: 10px;
  animation: alarm-hint-flash 1.4s ease-in-out infinite;
}

/* 显式开启警报声按钮（红色警报被 autoplay 拦截时显示） */
.alarm-sound-enable {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 5px 12px;
  margin-left: 10px;
  font-family: inherit;
  font-size: 12px;
  font-weight: 800;
  color: #8a0a0a;
  cursor: pointer;
  background: #fff;
  border: 1px solid #fff;
  border-radius: 999px;
}

.alarm-sound-enable:hover {
  background: #ffe3e3;
}

.alarm-sound-enable:focus-visible {
  outline: 3px solid rgb(255 255 255 / 90%);
  outline-offset: 2px;
}

.alarm-actions {
  display: flex;
  gap: 12px;
  margin-top: 24px;
}

.alarm-btn {
  flex: 1;
  min-height: 46px;
  font-family: inherit;
  font-size: 15px;
  font-weight: 700;
  cursor: pointer;
  border-radius: 12px;
  transition:
    transform 0.15s ease,
    box-shadow 0.15s ease,
    filter 0.15s ease;
}

.alarm-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 8px 20px rgb(0 0 0 / 28%);
}

.alarm-btn:active {
  transform: translateY(0);
}

.alarm-btn:focus-visible {
  outline: 3px solid rgb(255 255 255 / 90%);
  outline-offset: 2px;
}

.alarm-btn-primary {
  color: #8a0a0a;
  background: #fff;
  border: 1px solid #fff;
}

.alarm-btn-ghost {
  color: #fff;
  background: rgb(0 0 0 / 18%);
  border: 1px solid rgb(255 255 255 / 65%);
}

/* ══════════════════════════════════════════════════════════
   普通弹窗（橙 / 黄 / 蓝）：按 AI 研判档位切换主题色 + 3 秒进度条后跳转
   ══════════════════════════════════════════════════════════ */
.warn-overlay {
  position: fixed;
  inset: 0;
  z-index: 99999;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgb(15 23 20 / 45%);
  font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Microsoft YaHei', sans-serif;
  backdrop-filter: blur(2px);
  animation: alarm-overlay-in 0.18s ease-out both;
}

.warn-card {
  /* 默认橙色档位；黄 / 蓝由 severity-* 覆盖这组变量 */
  --warn-accent: #d98324;
  --warn-accent-strong: #a85a06;
  --warn-tint: #fdf1df;
  --warn-tint-ink: #8a4a05;
  --warn-hint-ink: #b5620a;
  --warn-progress-track: #f3e7d6;

  width: min(430px, 100%);
  padding: 22px 24px 20px;
  color: #2c3b34;
  background: #fff;
  border-top: 4px solid var(--warn-accent);
  border-radius: 16px;
  box-shadow: 0 18px 46px rgb(0 0 0 / 30%);
  animation: alarm-card-in 0.28s cubic-bezier(0.2, 0.9, 0.3, 1.25) both;
}

/* 黄色档（AI 35-60 / 黄色预警） */
.warn-card.severity-caution {
  --warn-accent: #c9a227;
  --warn-accent-strong: #8a6d00;
  --warn-tint: #fbf4d9;
  --warn-tint-ink: #6b5300;
  --warn-hint-ink: #7a5f00;
  --warn-progress-track: #f6edd2;
}

/* 蓝色档（AI <35 / 蓝色预警） */
.warn-card.severity-info {
  --warn-accent: #4a7fa5;
  --warn-accent-strong: #2f5f80;
  --warn-tint: #e6f0f7;
  --warn-tint-ink: #2f5f80;
  --warn-hint-ink: #2f5f80;
  --warn-progress-track: #e3edf4;
}

.warn-head {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.warn-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  color: var(--warn-tint-ink);
  background: var(--warn-tint);
  border-radius: 10px;
}

.warn-level {
  padding: 3px 10px;
  font-size: 12px;
  font-weight: 800;
  color: var(--warn-tint-ink);
  background: var(--warn-tint);
  border-radius: 999px;
}

.warn-count {
  padding: 3px 10px;
  font-size: 12px;
  font-weight: 600;
  color: #5f6b64;
  background: #f2f5f3;
  border-radius: 999px;
}

.warn-title {
  margin: 12px 0 0;
  font-size: 18px;
  font-weight: 800;
  line-height: 1.4;
  color: #22302a;
  overflow-wrap: anywhere;
}

.warn-desc {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.65;
  color: #5a6a62;
  overflow-wrap: anywhere;
}

.warn-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 14px;
  align-items: center;
  padding: 0;
  margin: 12px 0 0;
  font-size: 12px;
  color: #71807a;
  list-style: none;
}

.warn-meta-item {
  display: inline-flex;
  gap: 5px;
  align-items: center;
}

.warn-hint {
  margin: 14px 0 0;
  font-size: 12px;
  font-weight: 600;
  color: var(--warn-hint-ink);
}

.warn-progress {
  height: 4px;
  margin-top: 8px;
  overflow: hidden;
  background: var(--warn-progress-track);
  border-radius: 999px;
}

.warn-progress-bar {
  display: block;
  height: 100%;
  background: var(--warn-accent);
  border-radius: 999px;
  transform-origin: left center;
  animation: warn-progress-countdown 3s linear forwards;
}

.warn-actions {
  display: flex;
  gap: 10px;
  margin-top: 18px;
}

.warn-btn {
  flex: 1;
  min-height: 40px;
  font-family: inherit;
  font-size: 14px;
  font-weight: 700;
  cursor: pointer;
  border-radius: 10px;
  transition:
    transform 0.15s ease,
    box-shadow 0.15s ease;
}

.warn-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 6px 16px rgb(0 0 0 / 16%);
}

.warn-btn:active {
  transform: translateY(0);
}

.warn-btn:focus-visible {
  outline: 3px solid color-mix(in srgb, var(--warn-accent) 55%, transparent);
  outline-offset: 2px;
}

.warn-btn-primary {
  color: #fff;
  background: var(--warn-accent-strong);
  border: 1px solid var(--warn-accent-strong);
}

.warn-btn-ghost {
  color: #4a5a52;
  background: #fff;
  border: 1px solid #cfdad4;
}

/* ══════════════════════════════════════════════════════════
   纯 CSS 动画（不依赖任何动画库）
   ══════════════════════════════════════════════════════════ */
@keyframes alarm-overlay-in {
  from {
    opacity: 0;
  }

  to {
    opacity: 1;
  }
}

@keyframes alarm-overlay-flash {
  0%,
  100% {
    background-color: rgb(255 0 0 / 85%);
  }

  50% {
    background-color: rgb(255 0 0 / 95%);
  }
}

@keyframes alarm-card-in {
  from {
    opacity: 0;
    transform: scale(0.9);
  }

  to {
    opacity: 1;
    transform: scale(1);
  }
}

@keyframes alarm-icon-blink {
  0%,
  100% {
    transform: scale(1);
    box-shadow: 0 0 0 0 rgb(255 255 255 / 55%);
  }

  50% {
    transform: scale(1.1);
    box-shadow: 0 0 24px 6px rgb(255 120 120 / 45%);
  }
}

@keyframes alarm-hint-flash {
  0%,
  100% {
    opacity: 1;
  }

  50% {
    opacity: 0.55;
  }
}

@keyframes warn-progress-countdown {
  from {
    transform: scaleX(1);
  }

  to {
    transform: scaleX(0);
  }
}

/* ── 窄屏适配 ── */
@media (width <= 640px) {
  .alarm-overlay,
  .warn-overlay {
    padding: 16px;
  }

  .alarm-card {
    padding: 24px 20px 20px;
  }

  .alarm-title {
    font-size: 20px;
  }

  .alarm-actions,
  .warn-actions {
    flex-direction: column-reverse;
  }

  .warn-card {
    padding: 18px 18px 16px;
  }
}

/* ── 尊重"减少动效"偏好：关闭闪烁与缩放，避免诱发不适 ── */
@media (prefers-reduced-motion: reduce) {
  .alarm-overlay,
  .alarm-card,
  .alarm-icon,
  .alarm-hint,
  .warn-overlay,
  .warn-card,
  .warn-progress-bar {
    animation: none;
  }
}
</style>
