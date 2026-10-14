<script setup>
import { computed, onMounted, ref } from 'vue'
import { ShieldCheck, UsersRound, MapPin, MessageSquareText, FileText, Clock3, ListChecks, XCircle, Send, ShieldAlert, Bot, X, Loader2 } from 'lucide-vue-next'
import PermissionButton from '../../components/PermissionButton.vue'
import { dashboardApi } from '../../services/dashboardApi'

const emit = defineEmits(['notify'])
const incidents = ref([])
const loading = ref(true)
const aiScoreMap = ref(new Map())

// 选中的事件 ID（用于高亮 + 派发/查看工单）
const selectedIncidentId = ref(null)
// 详情抽屉
const drawerVisible = ref(false)
// 派发按钮 loading
const dispatchLoading = ref(false)
// 动态 KPI
const personnelCount = ref(null)
const reportCount = ref(null)

const activeCount = computed(() => incidents.value.length)
const aiCriticalCount = computed(() => {
  return Array.from(aiScoreMap.value.values()).filter((s) => s.riskLevel === 'CRITICAL' || s.riskLevel === 'HIGH').length
})

const selectedIncident = computed(() => {
  if (!selectedIncidentId.value) return null
  return incidents.value.find((i) => i.id === selectedIncidentId.value) ?? null
})

// 应急步骤定义（顺序：转移安置 → 专家会商 → 现场管控 → 消息推送）
const STEP_DEFS = [
  { key: 'relocate', label: '转移安置', icon: MapPin, keywords: ['转移', '安置', 'relocate'] },
  { key: 'expert',   label: '专家会商', icon: UsersRound, keywords: ['专家', '会商', '研判', 'expert'] },
  { key: 'control',  label: '现场管控', icon: ShieldCheck, keywords: ['管控', '现场', '抢险', 'control'] },
  { key: 'notify',   label: '消息推送', icon: MessageSquareText, keywords: ['推送', '通知', '消息', 'notify', 'push'] },
]

// 根据选中事件的 step 字段，计算各步骤状态
const emergencySteps = computed(() => {
  const stepText = selectedIncident.value?.step ?? ''
  if (!stepText) {
    return STEP_DEFS.map((s) => ({ ...s, status: 'pending', labelText: '待启动' }))
  }
  // 遍历步骤，根据关键词判断哪些已完成/进行中
  // 规则：step 字段包含某个关键词，则该步骤及之前的步骤都已完成，当前步骤为"进行中"
  let currentIdx = -1
  for (let i = 0; i < STEP_DEFS.length; i++) {
    const def = STEP_DEFS[i]
    const matched = def.keywords.some((kw) => stepText.includes(kw))
    if (matched) {
      currentIdx = i
    }
  }
  return STEP_DEFS.map((def, idx) => {
    let status = 'pending'
    let labelText = '待启动'
    if (idx < currentIdx) {
      status = 'done'
      labelText = '已完成'
    } else if (idx === currentIdx) {
      status = 'active'
      labelText = '进行中'
    }
    return { ...def, status, labelText }
  })
})

// 整体进度百分比
const progressPercent = computed(() => {
  const steps = emergencySteps.value
  const doneCount = steps.filter((s) => s.status === 'done').length
  const activeCount = steps.filter((s) => s.status === 'active').length
  // 已完成步骤算 100%，进行中步骤算 50%
  const total = steps.length
  if (total === 0) return 0
  const progress = (doneCount + activeCount * 0.5) / total
  return Math.round(progress * 100)
})

function incidentLevelClass(incident) {
  const level = incident?.level ?? ''
  if (/橙|红/.test(level)) return 'orange'
  if (/黄/.test(level)) return 'yellow'
  if (/蓝/.test(level)) return 'blue'
  return 'teal'
}
function aiLevelLabel(level) {
  return { CRITICAL: '极高', HIGH: '高', MEDIUM: '中', LOW: '低' }[level] || '—'
}
function aiOf(hazardCode) {
  if (!hazardCode) return null
  return aiScoreMap.value.get(hazardCode) ?? null
}

async function loadData() {
  loading.value = true
  try {
    const data = await dashboardApi.getEmergency()
    incidents.value = data.incidents
    // 从返回数据中提取动态 KPI，如果没有则保持 null（模板显示 "—"）
    personnelCount.value = data.personnelCount ?? null
    reportCount.value = data.reportCount ?? null
    // 如果有事件且没有选中项，默认选中第一个
    if (incidents.value.length && !selectedIncidentId.value) {
      selectedIncidentId.value = incidents.value[0].id
    }
    // 拉 AI 综合分（每个事件可能关联不同 hazard）
    const codes = Array.from(new Set(incidents.value.map((i) => i.hazardCode).filter(Boolean)))
    if (codes.length) {
      try {
        const all = await dashboardApi.getAiComprehensiveAll()
        const map = new Map()
        all.forEach((s) => map.set(s.hazardCode, s))
        aiScoreMap.value = map
      } catch (_) { /* AI 不可用 */ }
    }
  } finally {
    loading.value = false
  }
}

function selectIncident(incident) {
  selectedIncidentId.value = incident.id
}

function openDetailDrawer() {
  if (!selectedIncident.value) {
    emit('notify', '请先选择一个处置事件')
    return
  }
  drawerVisible.value = true
}

function closeDrawer() {
  drawerVisible.value = false
}

async function closeIncident(incident) {
  try {
    await dashboardApi.closeIncident(incident.id)
    incidents.value = incidents.value.filter((item) => item.id !== incident.id)
    // 如果关闭的是当前选中的，清除选中
    if (selectedIncidentId.value === incident.id) {
      selectedIncidentId.value = incidents.value.length ? incidents.value[0].id : null
    }
    emit('notify', `已关闭事件：${incident.title}`)
  } catch (error) {
    emit('notify', error instanceof Error ? error.message : '关闭失败')
  }
}

async function dispatchIncident() {
  if (!selectedIncident.value) {
    emit('notify', '请先选择一个处置事件')
    return
  }
  dispatchLoading.value = true
  try {
    const result = await dashboardApi.dispatchIncident(selectedIncident.value.id)
    // 成功后更新事件的 step（如果后端返回了更新后的 step）
    if (result && result.stepName) {
      const inc = incidents.value.find((i) => i.id === selectedIncident.value.id)
      if (inc) {
        inc.step = result.stepName
      }
    }
    emit('notify', `已派发：${selectedIncident.value.title}`)
  } catch (error) {
    emit('notify', error instanceof Error ? error.message : '派发失败')
  } finally {
    dispatchLoading.value = false
  }
}

// 格式化 KPI 显示
function formatCount(val) {
  if (val === null || val === undefined || val === '') return '—'
  return val
}

onMounted(loadData)
</script>

<template>
  <div>
    <div v-if="loading" class="panel" style="padding: 24px;">应急数据加载中...</div>
    <template v-else>
      <div class="stats-grid">
        <div class="stat-card"><div class="stat-icon orange"><ShieldCheck :size="19" /></div><div class="stat-copy"><span>处置中的事件</span><strong>{{ activeCount }} <small>起</small></strong><em class="positive">联动响应 <i>已启动</i></em></div></div>
        <div class="stat-card"><div class="stat-icon teal"><Bot :size="19" /></div><div class="stat-copy"><span>AI 标高/极高</span><strong>{{ aiCriticalCount }} <small>起</small></strong><em :class="aiCriticalCount ? 'negative' : 'positive'">{{ aiCriticalCount ? '需立即处置' : '运行良好' }} <i>综合风险</i></em></div></div>
        <div class="stat-card"><div class="stat-icon blue"><UsersRound :size="19" /></div><div class="stat-copy"><span>应急力量</span><strong>{{ formatCount(personnelCount) }} <small v-if="personnelCount !== null">人</small></strong><em class="positive">在线值守 <i>随时出动</i></em></div></div>
        <div class="stat-card"><div class="stat-icon purple"><FileText :size="19" /></div><div class="stat-copy"><span>复盘报告</span><strong>{{ formatCount(reportCount) }} <small v-if="reportCount !== null">份</small></strong><em class="positive">持续沉淀 <i>经验库</i></em></div></div>
      </div>

      <!-- AI 应急研判：每个事件挂上 AI 评分 -->
      <div v-if="incidents.length" class="ai-hazard-card" style="margin: 0 0 16px;">
        <div class="ai-card-head">
          <div>
            <small><Bot :size="13" />AI 应急研判</small>
            <strong>已对所有处置事件关联 AI 综合评分 · 5 分钟自动刷新</strong>
          </div>
        </div>
        <ul class="ai-hazard-reasons" style="border-top: none; padding-top: 4px;">
          <li v-for="(inc, i) in incidents" :key="`ai-${inc.id ?? i}`">
            <strong>{{ inc.title }}</strong>
            <template v-if="inc.hazardCode && aiOf(inc.hazardCode)">
              · 关联 {{ inc.hazardCode }}
              <span :class="`ai-tag ai-tag-${(aiOf(inc.hazardCode).riskLevel || '').toLowerCase()}`" style="margin-left:4px">
                AI {{ aiLevelLabel(aiOf(inc.hazardCode).riskLevel) }} {{ Number(aiOf(inc.hazardCode).riskScore || 0).toFixed(0) }}
              </span>
            </template>
            <template v-else-if="inc.hazardCode">
              · 关联 {{ inc.hazardCode }} · AI 暂未评分
            </template>
            <template v-else>
              · 无关联灾害点
            </template>
          </li>
        </ul>
      </div>

      <div class="content-grid">
        <section class="panel warning-panel">
          <div class="panel-header compact">
            <div>
              <h2>处置工单</h2>
              <p>转移、研判、抢险和复盘的流程清单</p>
            </div>
            <button class="text-btn" @click="openDetailDrawer"><ListChecks :size="15" />查看工单</button>
          </div>
          <div class="warning-list">
            <article
              v-for="incident in incidents"
              :key="incident.id"
              class="warning-item"
              :class="{ selected: selectedIncidentId === incident.id }"
              @click="selectIncident(incident)"
            >
              <div class="warning-symbol" :class="incidentLevelClass(incident)">{{ incidentLevelClass(incident) === 'orange' ? '!' : 'i' }}</div>
              <div class="warning-body">
                <div class="warning-title"><strong>{{ incident.title }}</strong><span :class="incidentLevelClass(incident)">{{ incident.level ?? '应急响应' }}</span></div>
                <p>{{ incident.owner }}<template v-if="incident.warningId"> · 关联预警 #{{ incident.warningId }}</template></p>
                <small><Clock3 :size="12" />{{ incident.time }} · {{ incident.step }}</small>
              </div>
            </article>
            <p v-if="!incidents.length" class="people-empty">当前无处置中的应急事件</p>
          </div>
        </section>

        <section class="panel system-panel">
          <div class="panel-header compact">
            <div>
              <h2>应急步骤</h2>
              <p>可一键推动转移和警戒</p>
            </div>
            <span class="health-label"><i></i>流程联动</span>
          </div>
          <div class="system-list">
            <div v-for="step in emergencySteps" :key="step.key" :class="`step-${step.status}`">
              <span><component :is="step.icon" :size="16" />{{ step.label }}</span>
              <strong>{{ step.labelText }}</strong>
            </div>
          </div>
          <div class="task-summary">
            <div class="progress-ring" :style="{ background: `conic-gradient(#6f9e7f 0 ${progressPercent}%, #e8f2ee ${progressPercent}% 100%)` }">
              <span>{{ progressPercent }}<small>%</small></span>
            </div>
            <div class="task-overview">
              <strong>应急响应</strong>
              <span>{{ selectedIncident ? selectedIncident.step : '暂无选中事件' }}</span>
              <div class="task-bars"><i :style="{ width: progressPercent + '%' }"></i></div>
              <p>{{ selectedIncident ? '可继续补充现场图片和处置结果' : '请先选择一个处置事件查看进度' }}</p>
            </div>
          </div>
          <div class="heading-actions" style="padding: 0 20px 18px; justify-content: flex-end;">
            <div class="action-btn-wrapper" :class="{ 'is-disabled': !selectedIncident || dispatchLoading }">
              <PermissionButton
                permission="emergency:dispatch"
                :icon="Send"
                @click="({ allowed, reason }) => allowed ? dispatchIncident() : emit('notify', reason)"
              >
                <Loader2 v-if="dispatchLoading" :size="14" class="spin" />
                {{ dispatchLoading ? '派发中' : '派发' }}
              </PermissionButton>
            </div>
            <div class="action-btn-wrapper" :class="{ 'is-disabled': !selectedIncident }">
              <PermissionButton
                permission="emergency:close"
                :icon="XCircle"
                @click="({ allowed, reason }) => allowed ? closeIncident(selectedIncident) : emit('notify', reason)"
              >关闭</PermissionButton>
            </div>
          </div>
        </section>
      </div>
    </template>

    <!-- 详情抽屉 -->
    <transition name="drawer">
      <div v-if="drawerVisible && selectedIncident" class="incident-drawer-backdrop" @click.self="closeDrawer">
        <aside class="incident-drawer" role="complementary">
          <header class="id-header">
            <div class="id-header-meta">
              <span class="eyebrow">EMERGENCY INCIDENT</span>
              <h2>
                <ShieldAlert :size="22" />
                {{ selectedIncident.title }}
              </h2>
              <div class="id-event-code">
                <code>#{{ selectedIncident.id }}</code>
                <span class="level-pill" :class="incidentLevelClass(selectedIncident)">
                  {{ selectedIncident.level ?? '应急响应' }}
                </span>
              </div>
            </div>
            <button class="id-close" @click="closeDrawer" aria-label="关闭"><X :size="18" /></button>
          </header>

          <div class="id-body">
            <section class="id-section">
              <h4 class="id-section-title">
                <Clock3 :size="14" /> 基本信息
              </h4>
              <div class="id-meta-grid">
                <div class="id-meta-cell">
                  <span class="k">事件标题</span>
                  <strong class="v">{{ selectedIncident.title }}</strong>
                </div>
                <div class="id-meta-cell">
                  <span class="k">事件等级</span>
                  <strong class="v" :class="incidentLevelClass(selectedIncident)">{{ selectedIncident.level ?? '—' }}</strong>
                </div>
                <div class="id-meta-cell">
                  <span class="k">指挥官</span>
                  <strong class="v">{{ selectedIncident.owner ?? '—' }}</strong>
                </div>
                <div class="id-meta-cell">
                  <span class="k">启动时间</span>
                  <strong class="v">{{ selectedIncident.time || '—' }}</strong>
                </div>
                <div class="id-meta-cell">
                  <span class="k">当前阶段</span>
                  <strong class="v">{{ selectedIncident.step ?? '—' }}</strong>
                </div>
                <div class="id-meta-cell">
                  <span class="k">关联预警</span>
                  <strong class="v code">{{ selectedIncident.warningId ? `#${selectedIncident.warningId}` : '—' }}</strong>
                </div>
              </div>
            </section>

            <section class="id-section">
              <h4 class="id-section-title">
                <ListChecks :size="14" /> 处置进度
              </h4>
              <div class="id-steps">
                <div v-for="(step, idx) in emergencySteps" :key="step.key" class="id-step-row" :class="`step-${step.status}`">
                  <div class="id-step-icon">
                    <component :is="step.icon" :size="16" />
                  </div>
                  <div class="id-step-info">
                    <strong>{{ step.label }}</strong>
                    <span>{{ step.labelText }}</span>
                  </div>
                  <div v-if="idx < emergencySteps.length - 1" class="id-step-line"></div>
                </div>
              </div>
            </section>

            <section v-if="selectedIncident.resultSummary" class="id-section">
              <h4 class="id-section-title">
                <FileText :size="14" /> 处置结果摘要
              </h4>
              <p class="id-summary">{{ selectedIncident.resultSummary }}</p>
            </section>

            <section v-if="selectedIncident.hazardCode" class="id-section">
              <h4 class="id-section-title">
                <Bot :size="14" /> AI 综合风险
              </h4>
              <template v-if="aiOf(selectedIncident.hazardCode)">
                <div class="id-ai-score">
                  <div class="score-num" :class="`ai-tag-${(aiOf(selectedIncident.hazardCode).riskLevel || '').toLowerCase()}`">
                    {{ Number(aiOf(selectedIncident.hazardCode).riskScore || 0).toFixed(0) }}
                  </div>
                  <div class="score-info">
                    <strong>风险等级：{{ aiLevelLabel(aiOf(selectedIncident.hazardCode).riskLevel) }}</strong>
                    <span>关联灾害点：{{ selectedIncident.hazardCode }}</span>
                  </div>
                </div>
              </template>
              <div v-else class="id-empty">AI 暂未评分</div>
            </section>
          </div>

          <footer class="id-footer">
            <div class="id-footer-title">操作</div>
            <div class="id-actions">
              <button
                class="id-action-btn tone-red"
                :disabled="dispatchLoading"
                @click="dispatchIncident"
              >
                <Loader2 v-if="dispatchLoading" :size="14" class="spin" />
                <Send v-else :size="14" />
                派发处置
              </button>
              <button class="id-action-btn tone-gray" @click="closeDrawer">
                <XCircle :size="14" />
                关闭
              </button>
            </div>
          </footer>
        </aside>
      </div>
    </transition>
  </div>
</template>

<style scoped>
/* 按钮禁用包装器 */
.action-btn-wrapper { display: inline-block; }
.action-btn-wrapper.is-disabled {
  opacity: 0.5;
  cursor: not-allowed;
  pointer-events: none;
}

/* 选中高亮样式 */
.warning-item.selected {
  background: rgba(111, 158, 127, 0.06);
  margin: 0 -8px;
  padding-left: 8px;
  padding-right: 8px;
  border-radius: var(--r-sm);
  border-left: 3px solid var(--c-brand);
}

/* 应急步骤状态样式 */
.system-list .step-done strong { color: var(--c-brand-deep); }
.system-list .step-active strong { color: var(--c-warning); }
.system-list .step-pending strong { color: var(--c-text-3); }

/* 详情抽屉 */
.incident-drawer-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.35);
  backdrop-filter: blur(2px);
  z-index: 200;
  display: flex;
  justify-content: flex-end;
}
.incident-drawer {
  width: 520px;
  max-width: 92vw;
  height: 100vh;
  background: var(--c-surface);
  display: flex;
  flex-direction: column;
  box-shadow: -16px 0 40px rgba(46, 67, 93, 0.18);
}
.id-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  padding: 20px 22px 18px;
  color: var(--c-on-brand);
  background: linear-gradient(135deg, var(--c-danger) 0%, var(--c-danger-strong) 100%);
}
.id-header-meta { flex: 1; min-width: 0; }
.id-header .eyebrow {
  font-size: 10.5px;
  letter-spacing: 1.5px;
  opacity: 0.85;
  font-weight: 600;
}
.id-header h2 {
  margin: 4px 0 8px;
  font-size: 18px;
  font-weight: 700;
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--c-on-brand);
  line-height: 1.3;
}
.id-event-code {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.id-event-code code {
  background: rgba(255, 255, 255, 0.18);
  padding: 3px 8px;
  border-radius: var(--r-xs);
  font-size: 11px;
  font-family: ui-monospace, monospace;
  letter-spacing: 0.5px;
}
.level-pill {
  padding: 3px 8px;
  border-radius: var(--r-pill);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.5px;
  background: rgba(255, 255, 255, 0.2);
  color: var(--c-on-brand);
}
.level-pill.orange { background: rgba(255, 200, 100, 0.35); }
.level-pill.yellow { background: rgba(255, 220, 100, 0.35); }
.level-pill.blue { background: rgba(100, 180, 255, 0.35); }

.id-close {
  background: rgba(255, 255, 255, 0.15);
  border: none;
  width: 32px;
  height: 32px;
  border-radius: var(--r-sm);
  color: var(--c-on-brand);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.id-close:hover { background: rgba(255, 255, 255, 0.28); }

.id-body {
  flex: 1;
  overflow-y: auto;
  padding: 18px 22px;
}
.id-section {
  margin-bottom: 22px;
  padding-bottom: 16px;
  border-bottom: 1px dashed rgba(125, 145, 165, 0.25);
}
.id-section:last-child { border-bottom: none; padding-bottom: 0; }
.id-section-title {
  font-size: 12px;
  font-weight: 700;
  color: var(--c-ink-1);
  margin: 0 0 10px;
  display: flex;
  align-items: center;
  gap: 6px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
.id-meta-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 10px;
}
.id-meta-cell {
  background: rgba(247, 250, 253, 0.7);
  border: 1px solid rgba(125, 145, 165, 0.18);
  border-radius: var(--r-sm);
  padding: 8px 10px;
}
.id-meta-cell .k {
  display: block;
  font-size: 10.5px;
  color: var(--c-text-2);
  margin-bottom: 2px;
}
.id-meta-cell .v {
  font-size: 13px;
  color: var(--c-ink-1);
}
.id-meta-cell .v.code {
  font-family: ui-monospace, monospace;
  font-size: 12px;
  color: var(--c-brand);
}
.id-meta-cell .v.orange { color: var(--c-warning-strong); }
.id-meta-cell .v.yellow { color: var(--c-warning-ink); }
.id-meta-cell .v.blue { color: var(--c-text-3); }

/* 步骤时间线 */
.id-steps {
  display: flex;
  flex-direction: column;
  gap: 0;
  position: relative;
}
.id-step-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 10px 0;
  position: relative;
}
.id-step-icon {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  background: rgba(60,60,67,.08);
  color: var(--c-text-3);
  z-index: 1;
}
.id-step-row.step-done .id-step-icon {
  background: var(--c-brand-mist);
  color: var(--c-brand-deep);
}
.id-step-row.step-active .id-step-icon {
  background: var(--c-warning-bg);
  color: var(--c-warning);
}
.id-step-info {
  flex: 1;
  padding-top: 4px;
}
.id-step-info strong {
  display: block;
  font-size: 13px;
  color: var(--c-ink-1);
  font-weight: 600;
}
.id-step-info span {
  display: block;
  font-size: 11px;
  color: var(--c-text-3);
  margin-top: 2px;
}
.id-step-row.step-done .id-step-info span { color: var(--c-brand-deep); }
.id-step-row.step-active .id-step-info span { color: var(--c-warning); }

.id-step-line {
  position: absolute;
  left: 15px;
  top: 42px;
  bottom: -10px;
  width: 2px;
  background: rgba(60,60,67,.08);
}
.id-step-row.step-done .id-step-line { background: var(--c-brand-hover); }

.id-summary {
  font-size: 13px;
  color: var(--c-ink-1);
  line-height: 1.6;
  background: rgba(247, 250, 253, 0.7);
  padding: 10px 12px;
  border-radius: var(--r-sm);
  border-left: 3px solid var(--c-brand);
  margin: 0;
}

.id-ai-score {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 10px;
  background: rgba(247, 250, 253, 0.7);
  border-radius: var(--r-sm);
}
.score-num {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: 20px;
  font-weight: 700;
  color: var(--c-on-brand);
  background: var(--c-text-3);
}
.score-num.ai-tag-critical { background: var(--c-danger); }
.score-num.ai-tag-high { background: var(--c-warning); }
.score-num.ai-tag-medium { background: var(--sys-blue); }
.score-num.ai-tag-low { background: var(--c-brand); }
.score-info strong {
  display: block;
  font-size: 14px;
  color: var(--c-ink-1);
}
.score-info span {
  display: block;
  font-size: 11px;
  color: var(--c-text-2);
  margin-top: 2px;
}
.id-empty {
  padding: 14px;
  text-align: center;
  color: var(--c-text-3);
  font-size: 12px;
  background: rgba(247, 250, 253, 0.5);
  border-radius: var(--r-sm);
}

.id-footer {
  border-top: 1px solid rgba(125, 145, 165, 0.18);
  padding: 14px 22px 16px;
  background: rgba(247, 250, 253, 0.6);
}
.id-footer-title {
  font-size: 11px;
  color: var(--c-text-2);
  margin-bottom: 8px;
  font-weight: 600;
}
.id-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.id-action-btn {
  border: 1px solid;
  padding: 8px 16px;
  border-radius: var(--r-sm);
  cursor: pointer;
  font-size: 12px;
  font-weight: 600;
  transition: all 0.15s;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.id-action-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.id-action-btn.tone-red { background: rgba(224, 96, 96, 0.08); border-color: var(--c-danger); color: var(--c-danger-strong); }
.id-action-btn.tone-red:hover:not(:disabled) { background: var(--c-danger); color: var(--c-on-brand); }
.id-action-btn.tone-gray { background: var(--c-brand-mist); border-color: var(--c-border); color: var(--c-ink-2); }
.id-action-btn.tone-gray:hover:not(:disabled) { background: var(--c-border); color: var(--c-ink-1); }

.spin { animation: spin 1s linear infinite; }
@keyframes spin { from { transform: rotate(0); } to { transform: rotate(360deg); } }

.drawer-enter-active, .drawer-leave-active {
  transition: opacity 0.18s ease;
}
.drawer-enter-active .incident-drawer, .drawer-leave-active .incident-drawer {
  transition: transform 0.24s cubic-bezier(0.22, 0.61, 0.36, 1);
}
.drawer-enter-from, .drawer-leave-to { opacity: 0; }
.drawer-enter-from .incident-drawer, .drawer-leave-to .incident-drawer { transform: translateX(100%); }
</style>
