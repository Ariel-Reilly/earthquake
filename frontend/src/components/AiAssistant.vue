<script setup>
/**
 * 地脉智巡助手 — 整个项目最显眼的"AI 入口"。
 *
 * 数据流：用户提问 → 后端 /api/ai/chat（关键词分发 demo）→ 返回 {intent, reply, attachment}
 *   - "分析 GS-001" / 灾害点名匹配 → intent=comprehensive → 渲染综合报告 + 建议
 *   - "哪些灾害点最危险" → intent=ranking → 渲染 TOP N 排行榜
 *   - "应急/紧急" → intent=emergency → 渲染派单入口
 *   - FAQ 兜底：原静态关键词库保留，作为 LLM 不可用时的最弱保底
 */
import { ref, computed, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { X, Send, Cpu, Stethoscope, AlertTriangle, MapPin, ChevronRight } from 'lucide-vue-next'
import BrandMascot from './BrandMascot.vue'
import { aiApi } from '../services/aiApi'

const router = useRouter()
const isOpen = ref(false)
const inputText = ref('')
const isTyping = ref(false)
const messagesRef = ref(null)
const inputRef = ref(null)

// 拖拽状态
const triggerRef = ref(null)
const isDragging = ref(false)
const dragMoved = ref(false)
const dragStart = { x: 0, y: 0, left: 0, top: 0 }
const dragPos = ref({ left: null, top: null })
const panelPos = ref({ left: 0, top: 0, panelW: 380, panelH: 520, originX: '100%', originY: '100%' })
const DRAG_THRESHOLD = 4

function computePanelPos() {
  const el = triggerRef.value
  if (!el) return
  const rect = el.getBoundingClientRect()
  const winW = window.innerWidth
  const winH = window.innerHeight
  const gap = 12
  const pad = 12
  const panelW = Math.min(380, winW - pad * 2)
  const panelH = Math.min(520, winH - pad * 2)

  const btnTop = rect.top
  const btnBottom = rect.bottom
  const btnLeft = rect.left
  const btnRight = rect.right

  const canAbove = btnTop - gap - panelH >= pad
  const canBelow = btnBottom + gap + panelH <= winH - pad

  let side, top
  if (canAbove) {
    side = 'top'
    top = btnTop - gap - panelH
  } else if (canBelow) {
    side = 'bottom'
    top = btnBottom + gap
  } else {
    const spaceAbove = btnTop - pad
    const spaceBelow = winH - pad - btnBottom
    if (spaceAbove >= spaceBelow) {
      side = 'top'
      top = Math.max(pad, btnTop - gap - panelH)
    } else {
      side = 'bottom'
      top = Math.min(winH - pad - panelH, btnBottom + gap)
    }
  }

  const rightAlignLeft = btnRight - panelW
  const leftAlignLeft = btnLeft
  let align, left
  if (rightAlignLeft >= pad) {
    align = 'right'
    left = rightAlignLeft
  } else if (leftAlignLeft + panelW <= winW - pad) {
    align = 'left'
    left = leftAlignLeft
  } else {
    align = 'right'
    left = Math.max(pad, Math.min(winW - pad - panelW, rightAlignLeft))
  }

  const originX = align === 'right' ? '100%' : '0%'
  const originY = side === 'top' ? '100%' : '0%'

  panelPos.value = { left, top, panelW, panelH, originX, originY }
}

const panelStyle = computed(() => ({
  left: panelPos.value.left + 'px',
  top: panelPos.value.top + 'px',
  width: panelPos.value.panelW + 'px',
  height: panelPos.value.panelH + 'px',
  transformOrigin: `${panelPos.value.originX} ${panelPos.value.originY}`,
}))

function onPointerDown(e) {
  const el = triggerRef.value
  if (!el) return
  const rect = el.getBoundingClientRect()
  dragStart.x = e.clientX
  dragStart.y = e.clientY
  dragStart.left = rect.left
  dragStart.top = rect.top
  dragMoved.value = false
  isDragging.value = true
  el.setPointerCapture(e.pointerId)
}

function onPointerMove(e) {
  if (!isDragging.value) return
  const dx = e.clientX - dragStart.x
  const dy = e.clientY - dragStart.y
  if (!dragMoved.value && Math.hypot(dx, dy) < DRAG_THRESHOLD) return
  dragMoved.value = true

  const btnSize = 64
  const padding = 12
  const winW = window.innerWidth
  const winH = window.innerHeight

  let newLeft = dragStart.left + dx
  let newTop = dragStart.top + dy
  newLeft = Math.max(padding, Math.min(winW - btnSize - padding, newLeft))
  newTop = Math.max(padding, Math.min(winH - btnSize - padding, newTop))

  dragPos.value = { left: newLeft, top: newTop }
  computePanelPos()
}

function onPointerUp(e) {
  if (!isDragging.value) return
  isDragging.value = false
  const el = triggerRef.value
  if (el) el.releasePointerCapture(e.pointerId)

  if (!dragMoved.value) {
    togglePanel()
  }
}

const messages = ref([
  {
    role: 'assistant',
    text: '你好！我是地脉智巡 AI 助手 🐱，正接在 AI 综合分析模块上。',
    quickReplies: ['分析 GS-001', '哪些灾害点最危险', '灾害类型是什么', '应急情况怎么办'],
  },
])

// 兜底关键词（AI 失联时仍可回答 FAQ）
const knowledgeBase = [
  { keywords: ['功能', '介绍', '有什么', '能做什么'], answer: '地脉智巡平台包含六大核心模块：\n1️⃣ AI 综合分析 — 图像+降水+地理+设备多维度智能研判\n2️⃣ 灾害点管理 — 滑坡、崩塌、泥石流隐患点全生命周期管理\n3️⃣ 设备监测 — IoT 传感器实时数据采集\n4️⃣ 智能预警 — 规则引擎 + AI 综合评分双驱动\n5️⃣ 应急处置 — 派单 + 联动部门响应\n6️⃣ 数据统计 — 多维度图表与数据导出', action: null },
  { keywords: ['预警', '警告', '告警', 'warning'], answer: '预警管理支持多级预警闭环处置。你可以在「预警管理」页面查看所有预警事件，进行确认、派发、关闭操作。', action: '/dashboard/warnings' },
  { keywords: ['设备', '传感器', '监测设备', 'device'], answer: '设备监测页面展示所有 IoT 设备的在线状态和实时数据，包括位移、雨量、倾斜等传感器数据。', action: '/dashboard/devices' },
  { keywords: ['灾害', '隐患', 'hazard', '滑坡'], answer: '灾害点管理页面统一管理所有地质灾害隐患点，支持空间可视化展示和全生命周期追踪。', action: '/dashboard' },
  { keywords: ['任务', '巡检', 'task'], answer: '巡检任务页面支持创建、分配和跟踪巡检任务，确保灾害点定期巡查。', action: '/dashboard/tasks' },
  { keywords: ['统计', '分析', '图表', 'analytics'], answer: '数据分析页面提供多维度统计图表，支持数据导出，为应急决策提供数据支撑。', action: '/dashboard/analytics' },
  { keywords: ['密码', '账号', '登录', 'admin', 'password'], answer: '演示环境默认账号：\n• 管理员 — admin / 123456\n• 值守员 — attendant / 123456\n• 维护员 — maintenance / 123456', action: null },
  { keywords: ['应急', 'emergency'], answer: '应急管理页面用于处置突发事件，支持事件派发和关闭操作。', action: '/dashboard/emergency' },
  { keywords: ['气象', '天气', 'weather'], answer: '气象监测页面接入了国家气象数据中心（风云四号卫星云图、雷达回波、24h 降水、积雪、干旱监测），可在地图上叠加显示。', action: '/dashboard/weather' },
  { keywords: ['AI', '综合分析', '综合', '智能'], answer: 'AI 综合分析在「/dashboard/ai-insight」页面，对每个灾害点从图像现况 / 当地降水量 / 当地地理情况 / 设备位移四个维度综合打分，输出 AI 报告、推理与建议。', action: '/dashboard/ai-insight' },
  { keywords: ['设置', 'settings'], answer: '设置页面可以查看系统配置和用户信息。', action: '/dashboard/settings' },
  { keywords: ['导出', 'export', '下载'], answer: '在概览、灾害点、预警等页面，点击右上角「导出」按钮即可导出 Excel 报表。', action: null },
  { keywords: ['帮助', 'help', '怎么用'], answer: '你可以点击下方快捷按钮快速了解系统功能，也可以直接输入问题。我支持：\n• 「分析 GS-XXX」 — 单灾害点 AI 综合分析\n• 「哪些灾害点最危险」 — AI 风险排行\n• 应急/紧急\n• 系统功能介绍\n• 预警/设备/灾害点查看\n• 默认账号密码', action: null },
]

function findFallbackAnswer(text) {
  const lower = text.toLowerCase().trim()
  for (const item of knowledgeBase) {
    if (item.keywords.some((kw) => lower.includes(kw.toLowerCase()))) {
      return { answer: item.answer, action: item.action }
    }
  }
  return {
    answer: '抱歉，我还没理解这个问题。你可以试试问我：\n• 分析 GS-001\n• 哪些灾害点最危险\n• 应急情况怎么办\n• 系统有哪些功能？',
    action: null,
  }
}

// 渲染附件：综合报告卡 / 排行榜卡
const LEVEL_INFO = {
  LOW: { label: '低风险', color: '#2f7f80' },
  MEDIUM: { label: '中等风险', color: '#4a8cc2' },
  HIGH: { label: '高风险', color: '#d18d3f' },
  CRITICAL: { label: '极高风险', color: '#c84a4a' },
}

function levelInfo(level) {
  return LEVEL_INFO[level] ?? { label: level || '未知', color: '#b6c2be' }
}

function fmtScore(v) {
  if (v == null || Number.isNaN(Number(v))) return '--'
  return Number(v).toFixed(1)
}

async function sendMessage(text) {
  const content = (text ?? inputText.value).trim()
  if (!content || isTyping.value) return

  messages.value.push({ role: 'user', text: content })
  inputText.value = ''
  scrollToBottom()

  isTyping.value = true
  try {
    const result = await aiApi.chat(content)
    const message = {
      role: 'assistant',
      text: result.reply || '暂无回复',
      attachment: result.attachment ?? null,
      intent: result.intent,
      quickReplies: null,
    }
    messages.value.push(message)

    // 兜底：若 intent=fallback 但 reply 不够友好，再加一条 FAQ 答案
    if (result.intent === 'fallback') {
      const fb = findFallbackAnswer(content)
      if (fb.action) {
        messages.value[messages.value.length - 1].action = fb.action
        messages.value[messages.value.length - 1].text = fb.answer
      }
    }
  } catch (e) {
    // 后端完全失联：退化到本地 FAQ
    const fb = findFallbackAnswer(content)
    messages.value.push({
      role: 'assistant',
      text: `⚠️ AI 综合分析模块暂不可用（${e?.message || '连接失败'}）。下面是基础问答：\n\n${fb.answer}`,
      action: fb.action,
      attachment: null,
      intent: 'fallback_local',
      quickReplies: null,
    })
  } finally {
    isTyping.value = false
    scrollToBottom()
  }
}

function useQuickReply(text) {
  sendMessage(text)
}

function navigate(path) {
  router.push(path)
  isOpen.value = false
}

function scrollToBottom() {
  nextTick(() => {
    if (messagesRef.value) {
      messagesRef.value.scrollTop = messagesRef.value.scrollHeight
    }
  })
}

function togglePanel() {
  if (!isOpen.value) {
    computePanelPos()
  }
  isOpen.value = !isOpen.value
  if (isOpen.value) {
    nextTick(() => {
      scrollToBottom()
      inputRef.value?.focus()
    })
  }
}

function handleKeydown(e) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    sendMessage()
  }
}

function handleClickOutside(e) {
  const panel = document.querySelector('.ai-assistant')
  if (panel && !panel.contains(e.target)) {
    isOpen.value = false
  }
}

function onResize() {
  if (isOpen.value) computePanelPos()
}

onMounted(() => {
  document.addEventListener('click', handleClickOutside)
  window.addEventListener('resize', onResize)
})

onBeforeUnmount(() => {
  document.removeEventListener('click', handleClickOutside)
  window.removeEventListener('resize', onResize)
})
</script>

<template>
  <div
    class="ai-assistant"
    :class="{ open: isOpen, dragged: dragPos.left !== null }"
    :style="dragPos.left !== null
      ? { left: dragPos.left + 'px', top: dragPos.top + 'px', right: 'auto', bottom: 'auto' }
      : {}"
  >
    <!-- 聊天面板 -->
    <transition name="panel-slide">
      <div v-show="isOpen" class="assistant-panel" :style="panelStyle">
        <div class="panel-header">
          <div class="panel-header-info">
            <div class="panel-avatar"><BrandMascot :size="36" /></div>
            <div>
              <strong>地脉智巡助手</strong>
              <span><span class="status-dot" /> AI 综合分析 · 在线</span>
            </div>
          </div>
          <button class="panel-close" @click="isOpen = false"><X :size="18" /></button>
        </div>

        <!-- 消息区域 -->
        <div ref="messagesRef" class="panel-messages">
          <template v-for="(msg, i) in messages" :key="i">
            <div class="msg-row" :class="msg.role">
              <div v-if="msg.role === 'assistant'" class="msg-avatar"><BrandMascot :size="28" /></div>
              <div class="msg-bubble" :class="msg.role">
                <!--
                  不要使用 v-html：msg.text 来源于后端 /api/ai/chat，理论上可被恶意 prompt 或
                  被入侵的后端注入 <script> 等 HTML/JS。{{ }} 插值默认转义，能阻断 XSS。
                  换行通过 CSS `white-space: pre-line` 保留。
                -->
                <span class="msg-text">{{ msg.text }}</span>

                <!-- 综合分析报告卡 -->
                <div
                  v-if="msg.attachment && msg.intent === 'comprehensive' && msg.attachment.riskScore != null"
                  class="ai-card ai-card-report"
                  :style="{ borderLeftColor: levelInfo(msg.attachment.riskLevel).color }"
                >
                  <div class="ai-card-head">
                    <div>
                      <small>AI 综合风险评分</small>
                      <strong :style="{ color: levelInfo(msg.attachment.riskLevel).color }">
                        {{ levelInfo(msg.attachment.riskLevel).label }} · {{ fmtScore(msg.attachment.riskScore) }}
                      </strong>
                    </div>
                    <span class="ai-card-tag">{{ msg.attachment.hazardCode }}</span>
                  </div>
                  <div class="ai-card-reasons">
                    <small>关键归因：</small>
                    <ul>
                      <li v-for="(r, idx) in (msg.attachment.reasoning || []).slice(0, 3)" :key="idx">{{ r }}</li>
                    </ul>
                  </div>
                  <button class="msg-action" @click="navigate('/dashboard/ai-insight')">
                    <Cpu :size="13" /> 查看完整 AI 报告
                  </button>
                </div>

                <!-- 排行榜卡 -->
                <div
                  v-else-if="msg.attachment && msg.intent === 'ranking' && msg.attachment.top"
                  class="ai-card ai-card-ranking"
                >
                  <div class="ai-card-ranklist">
                    <div v-for="row in msg.attachment.top" :key="row.rank" class="ai-rank-row">
                      <span class="ai-rank-no">#{{ row.rank }}</span>
                      <span class="ai-rank-code">{{ row.hazardCode }}</span>
                      <span
                        class="ai-rank-level"
                        :style="{ background: levelInfo(row.riskLevel).color + '22', color: levelInfo(row.riskLevel).color }"
                      >
                        {{ levelInfo(row.riskLevel).label }}
                      </span>
                      <strong class="ai-rank-score">{{ fmtScore(row.riskScore) }}</strong>
                    </div>
                  </div>
                  <button class="msg-action" @click="navigate('/dashboard/ai-insight')">
                    <Cpu :size="13" /> 前往 AI 综合分析
                  </button>
                </div>

                <!-- 应急入口卡 -->
                <div
                  v-else-if="msg.attachment && msg.intent === 'emergency'"
                  class="ai-card ai-card-emergency"
                >
                  <div class="ai-emergency-tag"><AlertTriangle :size="14" /> 应急联动</div>
                  <button class="msg-action" @click="navigate(msg.attachment.action || '/dashboard/emergency')">
                    进入应急处置页
                  </button>
                </div>

                <!-- AI 设备健康卡 -->
                <div
                  v-else-if="msg.attachment && msg.intent === 'device_health' && msg.attachment.items"
                  class="ai-card ai-card-device-health"
                >
                  <div class="ai-card-head">
                    <small><Stethoscope :size="13" />AI 设备健康</small>
                    <span class="ai-card-tag">共 {{ msg.attachment.total }} 台已评</span>
                  </div>
                  <ul class="ai-device-list">
                    <li v-for="row in msg.attachment.items" :key="row.deviceCode">
                      <span class="ai-device-code">{{ row.deviceCode }}</span>
                      <span :class="`ai-tag ai-tag-${(row.healthLevel || '').toLowerCase()}`">
                        {{ ({ HEALTHY: '健康', ATTENTION: '注意', WARN: '预警', CRITICAL: '严重' }[row.healthLevel] || '—') }}
                        {{ fmtScore(row.healthScore) }}
                      </span>
                      <small>异常 {{ fmtScore(row.anomalyScore) }}</small>
                    </li>
                  </ul>
                  <button class="msg-action" @click="navigate('/dashboard/devices')">
                    <Stethoscope :size="13" /> 前往设备管理
                  </button>
                </div>

                <button v-if="msg.action && msg.intent !== 'comprehensive' && msg.intent !== 'emergency'" class="msg-action" @click="navigate(msg.action)">
                  <Cpu :size="13" /> 前往查看
                </button>
                <div v-if="msg.quickReplies" class="msg-quick">
                  <button v-for="qr in msg.quickReplies" :key="qr" class="quick-btn" @click="useQuickReply(qr)">{{ qr }}</button>
                </div>
              </div>
            </div>
          </template>
          <!-- 打字指示器 -->
          <div v-if="isTyping" class="msg-row assistant">
            <div class="msg-avatar"><BrandMascot :size="28" /></div>
            <div class="msg-bubble assistant typing">
              <span class="typing-dot" />
              <span class="typing-dot" />
              <span class="typing-dot" />
            </div>
          </div>
        </div>

        <!-- 输入区域 -->
        <div class="panel-input">
          <input
            ref="inputRef"
            v-model="inputText"
            placeholder="试试：分析 GS-001 / 哪些灾害点最危险"
            @keydown="handleKeydown"
          />
          <button class="send-btn" :disabled="!inputText.trim() || isTyping" @click="sendMessage()">
            <Send :size="16" />
          </button>
        </div>
      </div>
    </transition>

    <!-- 悬浮按钮 -->
    <button
      ref="triggerRef"
      class="assistant-trigger"
      :class="{ dragging: isDragging }"
      @pointerdown.prevent="onPointerDown"
      @pointermove="onPointerMove"
      @pointerup="onPointerUp"
      @pointercancel="onPointerUp"
    >
      <div v-if="!isOpen" class="trigger-mascot"><BrandMascot :size="56" /></div>
      <div v-else class="trigger-close"><X :size="24" /></div>
      <span v-if="!isOpen" class="trigger-pulse" />
    </button>
  </div>
</template>

<style scoped>
.ai-assistant {
  position: fixed;
  bottom: 20px;
  right: 20px;
  z-index: 10000;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 12px;
}

/* ====== 聊天面板 ====== */
.assistant-panel {
  position: fixed;
  z-index: 10001;
  display: flex;
  flex-direction: column;
  border: 1px solid rgba(47, 127, 128, 0.15);
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.97);
  backdrop-filter: blur(10px);
  box-shadow: 0 20px 60px rgba(15, 78, 71, 0.18);
  overflow: hidden;
  will-change: transform, opacity;
}

/* 面板头部 */
.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  border-bottom: 1px solid rgba(220, 235, 230, 0.8);
  background: linear-gradient(135deg, rgba(47, 127, 128, 0.06), rgba(58, 141, 107, 0.04));
}

.panel-header-info {
  display: flex;
  align-items: center;
  gap: 10px;
}

.panel-avatar { flex-shrink: 0; }

.panel-header-info strong {
  display: block;
  font-size: 15px;
  color: #18363f;
}

.panel-header-info span {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: #7e9591;
}

.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #3a8d6b;
  animation: status-pulse 2s ease-in-out infinite;
}

@keyframes status-pulse {
  0%, 100% { opacity: 1; box-shadow: 0 0 0 0 rgba(58, 141, 107, 0.4); }
  50% { opacity: 0.7; box-shadow: 0 0 0 4px rgba(58, 141, 107, 0); }
}

.panel-close {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border: 0;
  border-radius: 10px;
  background: rgba(47, 127, 128, 0.08);
  color: #4a6661;
  cursor: pointer;
  transition: background 0.2s, color 0.2s;
}

.panel-close:hover { background: rgba(47, 127, 128, 0.15); color: #0f4e47; }

/* 消息区域 */
.panel-messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  scrollbar-width: thin;
  scrollbar-color: rgba(47, 127, 128, 0.15) transparent;
}

.panel-messages::-webkit-scrollbar { width: 4px; }
.panel-messages::-webkit-scrollbar-thumb { background: rgba(47, 127, 128, 0.15); border-radius: 2px; }

.msg-row {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}

.msg-row.user { flex-direction: row-reverse; }

.msg-avatar { flex-shrink: 0; }

.msg-bubble {
  max-width: 86%;
  padding: 10px 14px;
  border-radius: 16px;
  font-size: 14px;
  line-height: 1.6;
}

.msg-bubble.assistant {
  background: rgba(47, 127, 128, 0.08);
  color: #18363f;
  border-top-left-radius: 4px;
}

.msg-bubble.user {
  background: linear-gradient(135deg, #2f7f80, #0f4e47);
  color: #fff;
  border-top-right-radius: 4px;
}

.msg-text { display: block; white-space: pre-line; word-break: break-word; }

.msg-action {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-top: 8px;
  padding: 6px 14px;
  border: 1px solid rgba(47, 127, 128, 0.3);
  border-radius: 10px;
  background: rgba(47, 127, 128, 0.06);
  color: #2f7f80;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
}

.msg-action:hover { background: rgba(47, 127, 128, 0.12); border-color: #2f7f80; }

.msg-quick {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}

.quick-btn {
  padding: 5px 12px;
  border: 1px solid rgba(47, 127, 128, 0.2);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.8);
  color: #2f7f80;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
}

.quick-btn:hover { background: rgba(47, 127, 128, 0.08); border-color: #2f7f80; }

/* ====== AI 报告卡（综合分析） ====== */
.ai-card {
  display: block;
  margin-top: 8px;
  padding: 10px 12px;
  background: rgba(255, 255, 255, 0.95);
  border: 1px solid rgba(47, 127, 128, 0.18);
  border-left-width: 3px;
  border-radius: 10px;
  max-width: 100%;
}

.ai-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.ai-card-head small {
  display: block;
  font-size: 10px;
  color: #7e9591;
  margin-bottom: 2px;
}

.ai-card-head strong {
  font-size: 14px;
  font-weight: 600;
}

.ai-card-tag {
  padding: 2px 7px;
  background: rgba(47, 127, 128, 0.12);
  color: #246b6c;
  border-radius: 5px;
  font-size: 11px;
  font-weight: 500;
  font-family: 'JetBrains Mono', monospace;
}

.ai-card-reasons small {
  display: block;
  font-size: 11px;
  color: #7e9591;
  margin: 6px 0 4px;
}

.ai-card-reasons ul {
  margin: 0;
  padding-left: 16px;
  list-style: disc;
  font-size: 12px;
  color: #4a5e5b;
  line-height: 1.55;
}

.ai-card-reasons li {
  margin-bottom: 2px;
}

/* ====== 排行榜 ====== */
.ai-card-ranklist {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 4px 0 8px;
}

.ai-rank-row {
  display: grid;
  grid-template-columns: 32px 80px 1fr 60px;
  align-items: center;
  gap: 6px;
  padding: 6px 0;
  border-bottom: 1px solid rgba(220, 235, 230, 0.6);
  font-size: 12px;
}

.ai-rank-row:last-child { border-bottom: 0; }

.ai-rank-no {
  font-family: 'JetBrains Mono', monospace;
  color: #99a7a1;
  font-weight: 500;
}

.ai-rank-code {
  font-family: 'JetBrains Mono', monospace;
  color: #24494d;
  font-weight: 500;
}

.ai-rank-level {
  padding: 2px 8px;
  border-radius: 5px;
  font-size: 11px;
  font-weight: 500;
  text-align: center;
}

.ai-rank-score {
  text-align: right;
  font-family: 'JetBrains Mono', monospace;
  color: #24494d;
  font-weight: 700;
}

/* ====== 应急入口卡 ====== */
.ai-card-emergency {
  border-left-color: #d18d3f !important;
}

.ai-emergency-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 9px;
  background: rgba(209, 141, 63, 0.15);
  color: #8a6d3b;
  border-radius: 5px;
  font-size: 11px;
  font-weight: 500;
}

/* 打字指示器 */
.msg-bubble.typing {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 14px 16px;
}

.typing-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: rgba(47, 127, 128, 0.4);
  animation: typing-bounce 1.4s ease-in-out infinite;
}

.typing-dot:nth-child(2) { animation-delay: 0.2s; }
.typing-dot:nth-child(3) { animation-delay: 0.4s; }

@keyframes typing-bounce {
  0%, 60%, 100% { transform: translateY(0); opacity: 0.4; }
  30% { transform: translateY(-6px); opacity: 1; }
}

/* 输入区域 */
.panel-input {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  border-top: 1px solid rgba(220, 235, 230, 0.8);
  background: rgba(255, 255, 255, 0.6);
}

.panel-input input {
  flex: 1;
  padding: 10px 14px;
  border: 1px solid #d9e6e1;
  border-radius: 12px;
  font-size: 14px;
  outline: none;
  background: #fff;
  color: #18363f;
  transition: border-color 0.2s, box-shadow 0.2s;
}

.panel-input input:focus {
  border-color: #2f7f80;
  box-shadow: 0 0 0 3px rgba(47, 127, 128, 0.1);
}

.send-btn {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  flex-shrink: 0;
  border: 0;
  border-radius: 12px;
  background: linear-gradient(135deg, #2f7f80, #0f4e47);
  color: #fff;
  cursor: pointer;
  transition: transform 0.2s, opacity 0.2s;
}

.send-btn:hover:not(:disabled) { transform: scale(1.05); }
.send-btn:disabled { opacity: 0.4; cursor: not; }

/* ====== 悬浮按钮 ====== */
.assistant-trigger {
  position: relative;
  display: grid;
  place-items: center;
  width: 64px;
  height: 64px;
  border: 0;
  border-radius: 50%;
  background: linear-gradient(135deg, rgba(47, 127, 128, 0.1), rgba(58, 141, 107, 0.08));
  cursor: grab;
  transition: transform 0.2s, box-shadow 0.2s;
  box-shadow: 0 8px 24px rgba(15, 78, 71, 0.15);
  backdrop-filter: blur(10px);
  touch-action: none;
  user-select: none;
}

.assistant-trigger:hover { transform: scale(1.08); box-shadow: 0 12px 32px rgba(15, 78, 71, 0.2); }
.assistant-trigger.dragging { cursor: grabbing; transform: scale(0.95); transition: none; }

.trigger-mascot { animation: mascot-float 4s ease-in-out infinite; }

.trigger-close {
  display: grid;
  place-items: center;
  color: #0f4e47;
}

.trigger-pulse {
  position: absolute;
  inset: 0;
  border-radius: 50%;
  border: 2px solid rgba(47, 127, 128, 0.3);
  animation: trigger-pulse 2.5s ease-out infinite;
}

@keyframes trigger-pulse {
  0% { transform: scale(1); opacity: 0.6; }
  100% { transform: scale(1.4); opacity: 0; }
}

@keyframes mascot-float {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-4px); }
}

/* ====== 面板动画 ====== */
.panel-slide-enter-active {
  transition: opacity 0.26s cubic-bezier(0.16, 1, 0.3, 1), transform 0.26s cubic-bezier(0.16, 1, 0.3, 1);
}
.panel-slide-leave-active {
  transition: opacity 0.18s cubic-bezier(0.4, 0, 1, 1), transform 0.18s cubic-bezier(0.4, 0, 1, 1);
}
.panel-slide-enter-from { opacity: 0; transform: scale(0.92); }
.panel-slide-leave-to { opacity: 0; transform: scale(0.94); }

/* ====== 响应式 ====== */
@media (max-width: 768px) {
  .assistant-trigger { width: 56px; height: 56px; }
}

@media (prefers-reduced-motion: reduce) {
  .trigger-pulse, .trigger-mascot, .status-dot, .typing-dot { animation: none; }
}
</style>
