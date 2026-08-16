<script setup>
import { computed, onMounted, ref } from 'vue'
import { ShieldCheck, UsersRound, MapPin, MessageSquareText, FileText, Clock3, ListChecks, XCircle, Send } from 'lucide-vue-next'
import PermissionButton from '../components/PermissionButton.vue'
import { dashboardApi } from '../services/dashboardApi'

const emit = defineEmits(['notify'])
const incidents = ref([])
const loading = ref(true)

const activeCount = computed(() => incidents.value.length)

function incidentLevelClass(incident) {
  const level = incident?.level ?? ''
  if (/橙|红/.test(level)) return 'orange'
  if (/黄/.test(level)) return 'yellow'
  if (/蓝/.test(level)) return 'blue'
  return 'teal'
}

async function loadData() {
  loading.value = true
  const data = await dashboardApi.getEmergency()
  incidents.value = data.incidents
  loading.value = false
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
        <div class="stat-card"><div class="stat-icon blue"><UsersRound :size="19" /></div><div class="stat-copy"><span>应急力量</span><strong>11 <small>人</small></strong><em class="positive">在线值守 <i>随时出动</i></em></div></div>
        <div class="stat-card"><div class="stat-icon teal"><MapPin :size="19" /></div><div class="stat-copy"><span>避险安置点</span><strong>3 <small>处</small></strong><em class="positive">覆盖周边 <i>联动安置</i></em></div></div>
        <div class="stat-card"><div class="stat-icon purple"><FileText :size="19" /></div><div class="stat-copy"><span>复盘报告</span><strong>12 <small>份</small></strong><em class="positive">持续沉淀 <i>经验库</i></em></div></div>
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
