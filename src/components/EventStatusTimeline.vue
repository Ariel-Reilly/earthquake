<script setup>
/**
 * 事件状态时间线 (EventStatusTimeline.vue)。
 *
 * v1.7.0 P1 统一事件中心：
 *   - 把 hazard / warning / emergency / inspection 四个业务实体的状态
 *     在同一张图上展示为「检测 → 确认 → 预警 → 处置 → 闭环」五步流水线；
 *   - 每一步显示：步骤名 / 状态 / 数量 / 关联跳转入口；
 *   - 高亮当前 hazard 处于哪个步骤，便于用户一眼定位阶段。
 *
 * 用法：
 *   <EventStatusTimeline :hazard-code="point.hazardCode" :hazard-id="point.id" />
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  AlertTriangle,
  ArrowRight,
  CheckCircle2,
  CircleDot,
  CircleSlash,
  Clock,
  GitBranch,
  Loader2,
  MapPin,
  ShieldCheck,
  Truck,
} from 'lucide-vue-next'
import { dashboardApi } from '../services/dashboardApi.js'

const props = defineProps({
  hazardId: { type: [String, Number], required: true },
  hazardCode: { type: String, default: '' },
})

const emit = defineEmits(['notify'])

const router = useRouter()
const loading = ref(true)
const related = ref({ hazard: null, warnings: [], emergencies: [], inspections: [] })

async function load() {
  loading.value = true
  try {
    related.value = await dashboardApi.getRelatedEvents(props.hazardId, props.hazardCode)
  } catch (error) {
    emit('notify', error instanceof Error ? error.message : '加载事件时间线失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)

// ─── 五步流水线定义 ─────────────────────────────
const STEPS = [
  { key: 'hazard',      label: '灾害事件', icon: MapPin,        color: '#2b6cb0' },
  { key: 'inspection',  label: '现场核查', icon: GitBranch,     color: '#7c3aed' },
  { key: 'warning',     label: '预警发布', icon: AlertTriangle, color: '#ea580c' },
  { key: 'emergency',   label: '应急处置', icon: Truck,         color: '#dc2626' },
  { key: 'closed',      label: '闭环归档', icon: ShieldCheck,   color: '#16a34a' },
]

const counts = computed(() => ({
  hazard: 1,
  inspection: related.value.inspections?.length ?? 0,
  warning: related.value.warnings?.length ?? 0,
  emergency: related.value.emergencies?.length ?? 0,
  closed: closedCount.value,
}))

/** 闭环数 = 已解除预警 + 已闭环应急 */
const closedCount = computed(() => {
  const dismissedWarnings = (related.value.warnings ?? []).filter((w) => {
    const s = String(w.status ?? '')
    return s.includes('解除') || s.includes('已解除') || s.includes('closed') || s.includes('dismissed')
  }).length
  const closedEmergencies = (related.value.emergencies ?? []).filter((e) => {
    const s = String(e.level ?? e.status ?? '')
    return s.includes('闭环')
  }).length
  return dismissedWarnings + closedEmergencies
})

const latestWarning = computed(() => related.value.warnings?.[0] ?? null)
const latestEmergency = computed(() => related.value.emergencies?.[0] ?? null)

/** 当前 hazard 处于哪个阶段(高亮逻辑) */
const currentStepIndex = computed(() => {
  const c = counts.value
  if (c.emergency > 0) {return 3}                  // 处置流程中
  if (c.warning > 0) {return 2}                    // 已发布预警
  if (c.inspection > 0) {return 1}                 // 现场核查中
  if (c.hazard > 0) {return 0}                    // 只有灾害点
  return 0
})

function isStepActive(idx) {
  return idx === currentStepIndex.value
}

function isStepCompleted(idx) {
  return idx < currentStepIndex.value
}

function goToWarnings() {
  if (latestWarning.value?.id) {
    router.push(`/dashboard/warnings/${latestWarning.value.id}`)
  } else {
    router.push('/dashboard/warnings')
  }
}

function goToEmergency() {
  if (latestEmergency.value?.id) {
    router.push(`/dashboard/emergency`)
  } else {
    router.push('/dashboard/emergency')
  }
}

function goToInspection() {
  router.push('/dashboard/ai-tasks')
}

function formatTime(t) {
  if (!t) return '—'
  try {
    const d = new Date(t)
    if (Number.isNaN(d.getTime())) {return '—'}
    return d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false })
  } catch { return '—' }
}
</script>

<template>
  <div class="event-timeline">
    <div class="timeline-header">
      <h3>
        <GitBranch :size="15" />
        统一事件时间线
        <small>事件中枢 · 一页看完检测 → 预警 → 处置 → 闭环</small>
      </h3>
      <button class="refresh-btn" :disabled="loading" @click="load">
        <Loader2 :size="13" :class="{ spinning: loading }" />
        刷新
      </button>
    </div>

    <div v-if="loading && !related.hazard" class="timeline-loading">
      <Loader2 :size="20" class="spinning" />
      <span>关联事件加载中…</span>
    </div>

    <div v-else class="timeline-body">
      <!-- 横向五步流水线 -->
      <ol class="timeline-steps">
        <li
          v-for="(step, idx) in STEPS"
          :key="step.key"
          class="step"
          :class="{
            active: isStepActive(idx),
            completed: isStepCompleted(idx),
          }"
        >
          <div class="step-icon" :style="{ background: isStepActive(idx) ? step.color : (isStepCompleted(idx) ? '#e2e8f0' : '#f1f5f9'), color: isStepActive(idx) ? '#fff' : (isStepCompleted(idx) ? '#16a34a' : '#94a3b8') }">
            <CheckCircle2 v-if="isStepCompleted(idx)" :size="14" />
            <CircleDot v-else-if="isStepActive(idx)" :size="14" />
            <CircleSlash v-else :size="14" />
          </div>
          <div class="step-content">
            <div class="step-label">{{ step.label }}</div>
            <div class="step-meta">
              <span class="step-count">{{ counts[step.key] }}</span>
              <small v-if="step.key === 'warning' && latestWarning">
                {{ latestWarning.title ?? `预警 #${latestWarning.id}` }}
              </small>
              <small v-else-if="step.key === 'emergency' && latestEmergency">
                {{ latestEmergency.title ?? `应急 #${latestEmergency.id}` }}
              </small>
              <small v-else>—</small>
            </div>
          </div>
          <ArrowRight v-if="idx < STEPS.length - 1" :size="14" class="step-arrow" />
        </li>
      </ol>

      <!-- 关联事件摘要列表 -->
      <div class="timeline-relations">
        <div class="relation-card">
          <div class="rel-head">
            <Clock :size="13" />
            <span>关联预警</span>
            <em>{{ related.warnings.length }}</em>
          </div>
          <ul v-if="related.warnings.length" class="rel-list">
            <li v-for="w in related.warnings.slice(0, 3)" :key="w.id" @click="goToWarnings">
              <span class="rel-title">{{ w.title ?? w.name ?? `预警 #${w.id}` }}</span>
              <small class="rel-time">{{ formatTime(w.time ?? w.updatedAt ?? w.createdAt) }}</small>
              <span class="rel-status">{{ w.status }}</span>
            </li>
          </ul>
          <p v-else class="rel-empty">该灾害点暂未发布预警</p>
          <button v-if="related.warnings.length" class="link-btn" @click="goToWarnings">
            查看全部预警 →
          </button>
        </div>

        <div class="relation-card">
          <div class="rel-head">
            <Clock :size="13" />
            <span>关联应急</span>
            <em>{{ related.emergencies.length }}</em>
          </div>
          <ul v-if="related.emergencies.length" class="rel-list">
            <li v-for="e in related.emergencies.slice(0, 3)" :key="e.id" @click="goToEmergency">
              <span class="rel-title">{{ e.title ?? `应急 #${e.id}` }}</span>
              <small class="rel-time">{{ formatTime(e.time ?? e.startedAt) }}</small>
              <span class="rel-status">{{ e.level ?? e.status ?? '处置中' }}</span>
            </li>
          </ul>
          <p v-else class="rel-empty">该灾害点未触发应急响应</p>
          <button v-if="related.emergencies.length" class="link-btn" @click="goToEmergency">
            查看应急详情 →
          </button>
        </div>

        <div class="relation-card">
          <div class="rel-head">
            <Clock :size="13" />
            <span>现场核查任务</span>
            <em>{{ related.inspections.length }}</em>
          </div>
          <ul v-if="related.inspections.length" class="rel-list">
            <li v-for="t in related.inspections.slice(0, 3)" :key="t.id" @click="goToInspection">
              <span class="rel-title">{{ t.name ?? t.title ?? `任务 #${t.id}` }}</span>
              <small class="rel-time">{{ formatTime(t.dueDate ?? t.dueAt ?? t.updatedAt) }}</small>
              <span class="rel-status">{{ t.status ?? '待执行' }}</span>
            </li>
          </ul>
          <p v-else class="rel-empty">暂无现场核查任务</p>
          <button v-if="related.inspections.length" class="link-btn" @click="goToInspection">
            创建核查任务 →
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.event-timeline {
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  padding: 16px 18px;
  margin-bottom: 16px;
}

.timeline-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}
.timeline-header h3 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: #1e293b;
}
.timeline-header h3 small {
  font-weight: 400;
  font-size: 12px;
  color: #94a3b8;
  margin-left: 6px;
}
.refresh-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border-radius: 6px;
  border: 1px solid #e2e8f0;
  background: #fff;
  color: #64748b;
  font-size: 12px;
  cursor: pointer;
}
.refresh-btn:hover { border-color: #3b82f6; color: #3b82f6; }
.refresh-btn:disabled { opacity: 0.5; cursor: not-allowed; }

.timeline-loading {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 28px;
  color: #94a3b8;
  font-size: 13px;
  justify-content: center;
}

/* 五步流水线 */
.timeline-steps {
  display: flex;
  align-items: stretch;
  list-style: none;
  margin: 0 0 18px;
  padding: 0;
  background: linear-gradient(180deg, #f8fafc 0%, #f1f5f9 100%);
  border-radius: 10px;
  border: 1px solid #e2e8f0;
  overflow: hidden;
}
.step {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  position: relative;
  min-width: 0;
}
.step.active { background: rgba(59,130,246,0.04); }
.step.completed { opacity: 0.85; }
.step-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  flex-shrink: 0;
  transition: all 0.2s;
}
.step.active .step-icon { box-shadow: 0 0 0 4px rgba(59,130,246,0.15); }
.step-content { min-width: 0; flex: 1; }
.step-label { font-size: 12px; color: #64748b; font-weight: 500; }
.step.active .step-label { color: #1e293b; font-weight: 700; }
.step-meta { display: flex; align-items: baseline; gap: 6px; margin-top: 2px; }
.step-count {
  font-size: 18px;
  font-weight: 700;
  color: #1e293b;
  line-height: 1;
  font-variant-numeric: tabular-nums;
}
.step.active .step-count { color: #2b6cb0; }
.step-meta small { font-size: 11px; color: #94a3b8; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.step-arrow { color: #cbd5e1; flex-shrink: 0; }

/* 关联事件 */
.timeline-relations {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}
.relation-card {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 10px 12px;
}
.rel-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 600;
  color: #475569;
  margin-bottom: 6px;
}
.rel-head em {
  margin-left: auto;
  font-style: normal;
  background: #e2e8f0;
  color: #475569;
  padding: 1px 7px;
  border-radius: 10px;
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}
.rel-list { list-style: none; margin: 0; padding: 0; }
.rel-list li {
  display: grid;
  grid-template-columns: 1fr auto auto;
  gap: 6px;
  align-items: center;
  font-size: 12px;
  padding: 4px 0;
  cursor: pointer;
  border-bottom: 1px dashed #e2e8f0;
}
.rel-list li:hover .rel-title { color: #3b82f6; }
.rel-list li:last-child { border-bottom: none; }
.rel-title { color: #334155; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.rel-time { color: #94a3b8; font-size: 11px; white-space: nowrap; }
.rel-status {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 4px;
  background: #e2e8f0;
  color: #475569;
  white-space: nowrap;
}
.rel-empty { font-size: 12px; color: #cbd5e1; text-align: center; margin: 6px 0; }
.link-btn {
  background: transparent;
  border: none;
  color: #3b82f6;
  font-size: 12px;
  cursor: pointer;
  padding: 0;
  margin-top: 4px;
}
.link-btn:hover { text-decoration: underline; }

.spinning { animation: spin 1s linear infinite; }
@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }

@media (max-width: 1100px) {
  .timeline-relations { grid-template-columns: 1fr; }
  .timeline-steps { flex-wrap: wrap; }
  .step { min-width: 50%; }
}
</style>
