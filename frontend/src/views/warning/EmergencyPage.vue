<script setup>
import { computed, onMounted, ref } from 'vue'
import { ShieldCheck, UsersRound, MapPin, MessageSquareText, FileText, Clock3, ListChecks, XCircle, Send, ShieldAlert, Bot } from 'lucide-vue-next'
import PermissionButton from '../../components/PermissionButton.vue'
import { dashboardApi } from '../../services/dashboardApi'

const emit = defineEmits(['notify'])
const incidents = ref([])
const loading = ref(true)
const aiScoreMap = ref(new Map())

const activeCount = computed(() => incidents.value.length)
const aiCriticalCount = computed(() => {
  return Array.from(aiScoreMap.value.values()).filter((s) => s.riskLevel === 'CRITICAL' || s.riskLevel === 'HIGH').length
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

async function closeIncident(incident) {
  try {
    await dashboardApi.closeIncident(incident.id)
    incidents.value = incidents.value.filter((item) => item.id !== incident.id)
    emit('notify', `已关闭事件：${incident.title}`)
  } catch (error) {
    emit('notify', error instanceof Error ? error.message : '关闭失败')
  }
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
        <div class="stat-card"><div class="stat-icon blue"><UsersRound :size="19" /></div><div class="stat-copy"><span>应急力量</span><strong>11 <small>人</small></strong><em class="positive">在线值守 <i>随时出动</i></em></div></div>
        <div class="stat-card"><div class="stat-icon purple"><FileText :size="19" /></div><div class="stat-copy"><span>复盘报告</span><strong>12 <small>份</small></strong><em class="positive">持续沉淀 <i>经验库</i></em></div></div>
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
            <button class="text-btn" @click="emit('notify', '已打开应急工单')"><ListChecks :size="15" />查看工单</button>
          </div>
          <div class="warning-list">
            <article v-for="incident in incidents" :key="incident.id" class="warning-item" @click="emit('notify', `已派发处置：${incident.title}`)">
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
            <div><span><MapPin :size="16" />转移安置</span><strong>已准备</strong></div>
            <div><span><UsersRound :size="16" />专家会商</span><strong>进行中</strong></div>
            <div><span><ShieldCheck :size="16" />现场管控</span><strong>已启动</strong></div>
            <div><span><MessageSquareText :size="16" />消息推送</span><strong>已发送</strong></div>
          </div>
          <div class="task-summary">
            <div class="progress-ring"><span>78<small>%</small></span></div>
            <div class="task-overview">
              <strong>应急响应</strong>
              <span>建议继续关注雨情与位移变化</span>
              <div class="task-bars"><i style="width: 78%"></i></div>
              <p>可继续补充现场图片和处置结果</p>
            </div>
          </div>
          <div class="heading-actions" style="padding: 0 20px 18px; justify-content: flex-end;">
            <PermissionButton permission="emergency:dispatch" :icon="Send" @click="({ allowed, reason }) => allowed ? emit('notify', '已推送转移通知') : emit('notify', reason)">派发</PermissionButton>
            <PermissionButton permission="emergency:close" :icon="XCircle" @click="({ allowed, reason }) => allowed ? closeIncident(incidents[0]) : emit('notify', reason)">关闭</PermissionButton>
          </div>
        </section>
      </div>
    </template>
  </div>
</template>
