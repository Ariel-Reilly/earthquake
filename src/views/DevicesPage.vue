<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { AlertTriangle, Database, Gauge, Layers3, MapPin, MessageSquareText, Radio, Sparkles, Stethoscope, Video, PencilLine, Trash2, Wrench } from 'lucide-vue-next'
import PermissionButton from '../components/PermissionButton.vue'
import { dashboardApi } from '../services/dashboardApi'

const emit = defineEmits(['notify'])
const router = useRouter()
const devices = ref([])
const selectedDeviceId = ref(null)
const loading = ref(true)
const paging = ref({ page: 1, size: 10, total: 0, totalPages: 0, pages: 0 })
const aiHealthMap = ref(new Map())

const selectedDevice = computed(() => devices.value.find((item) => item.id === selectedDeviceId.value) ?? devices.value[0] ?? null)
const onlineCount = computed(() => devices.value.filter((item) => item.state === '在线').length)
const offlineCount = computed(() => devices.value.filter((item) => item.state === '离线').length)
/** AI 标 CRITICAL 的设备数 —— 需立即处置 */
const aiCriticalCount = computed(() => devices.value.filter((d) => d.aiHealthLevel === 'CRITICAL').length)
/** AI 标 WARN/ATTENTION 的设备数 —— 关注 */
const aiAttentionCount = computed(() => devices.value.filter((d) => d.aiHealthLevel === 'WARN' || d.aiHealthLevel === 'ATTENTION').length)

/** 等级 → 中文 + 颜色 */
function aiHealthLabel(level) {
  return { HEALTHY: '健康', ATTENTION: '注意', WARN: '预警', CRITICAL: '严重' }[level] || '—'
}
function aiHealthClass(level) {
  return { HEALTHY: 'teal', ATTENTION: 'blue', WARN: 'yellow', CRITICAL: 'orange' }[level] || 'blue'
}

async function loadData(page = 1) {
  loading.value = true
  try {
    // 1) 拉 AI 健康评分（先拉，避免和设备数据时序错位）
    let aiList = []
    try {
      aiList = await dashboardApi.getAiDeviceHealthAll()
    } catch (_) {
      aiList = []
    }
    const aiMap = new Map()
    aiList.forEach((s) => aiMap.set(s.deviceCode, s))
    aiHealthMap.value = aiMap

    // 2) 拉设备列表
    const data = await dashboardApi.getDevices({ page, size: paging.value.size })
    let list = data.list ?? []
    // 3) 合并 AI 评分
    list = dashboardApi.attachAiDeviceHealth(list, aiList)
    // 4) 排序：AI CRITICAL > WARN > ATTENTION > HEALTHY，再按 online
    const levelOrder = { CRITICAL: 0, WARN: 1, ATTENTION: 2, HEALTHY: 3, undefined: 4 }
    list.sort((a, b) => {
      const la = levelOrder[a.aiHealthLevel] ?? 4
      const lb = levelOrder[b.aiHealthLevel] ?? 4
      if (la !== lb) return la - lb
      return (a.aiHealthScore ?? 100) - (b.aiHealthScore ?? 100)
    })
    devices.value = list
    paging.value = {
      page: data.page ?? page,
      size: data.size ?? paging.value.size,
      total: data.total ?? list.length,
      pages: data.pages ?? 1,
    }
    selectedDeviceId.value = list[0]?.id ?? null
  } finally {
    loading.value = false
  }
}

async function changePage(step) {
  const nextPage = Math.min(Math.max(paging.value.page + step, 1), paging.value.pages || 1)
  if (nextPage !== paging.value.page) await loadData(nextPage)
}

function openDetail(device) {
  const target = device ?? selectedDevice.value
  if (target) router.push(`/dashboard/devices/${target.id}`)
}

async function deleteDevice() {
  if (!selectedDevice.value) return
  try {
    await dashboardApi.deleteDevice(selectedDevice.value.id)
    devices.value = devices.value.filter((item) => item.id !== selectedDevice.value.id)
    selectedDeviceId.value = devices.value[0]?.id ?? null
    emit('notify', `已删除 ${selectedDevice.value.name}`)
  } catch (error) {
    emit('notify', error instanceof Error ? error.message : '删除失败')
  }
}

async function diagnoseDevice() {
  if (!selectedDevice.value) return
  try {
    const result = await dashboardApi.diagnoseDevice(selectedDevice.value.id)
    devices.value = devices.value.map((item) => (String(item.id) === String(result.id) ? result : item))
    emit('notify', `已完成 ${selectedDevice.value.name} 诊断`)
  } catch (error) {
    emit('notify', error instanceof Error ? error.message : '诊断失败')
  }
}

onMounted(loadData)
</script>

<template>
  <div>
    <div v-if="loading" class="panel" style="padding: 24px;">设备数据加载中...</div>
    <template v-else>
      <div class="stats-grid">
        <div class="stat-card"><div class="stat-icon teal"><Radio :size="19" /></div><div class="stat-copy"><span>在线设备</span><strong>{{ onlineCount }} <small>台</small></strong><em class="positive">高可用 <i>MQTT接入</i></em></div></div>
        <div class="stat-card"><div class="stat-icon orange"><AlertTriangle :size="19" /></div><div class="stat-copy"><span>离线设备</span><strong>{{ offlineCount }} <small>台</small></strong><em class="negative">需运维 <i>立即处理</i></em></div></div>
        <div class="stat-card"><div class="stat-icon red" v-if="aiCriticalCount" :style="{ background: 'linear-gradient(135deg, #c75d5d, #e08c8c)' }"><AlertTriangle :size="19" /></div><div class="stat-icon orange" v-else><Sparkles :size="19" /></div><div class="stat-copy"><span>AI 标严重</span><strong>{{ aiCriticalCount }} <small>台</small></strong><em :class="aiCriticalCount ? 'negative' : 'positive'">{{ aiCriticalCount ? '需立即处置' : '运行良好' }} <i>5 分钟刷新</i></em></div></div>
        <div class="stat-card"><div class="stat-icon yellow"><Stethoscope :size="19" /></div><div class="stat-copy"><span>AI 关注</span><strong>{{ aiAttentionCount }} <small>台</small></strong><em class="positive">建议加密观测 <i>设备健康分</i></em></div></div>
      </div>

      <div class="content-grid">
        <section class="panel warning-panel">
          <div class="panel-header compact">
            <div>
              <h2>设备列表（按 AI 健康分）</h2>
              <p>异常在前 · 在线/电量/AI 健康一目了然</p>
            </div>
            <div class="heading-actions">
              <PermissionButton permission="ai:insight:refresh" :icon="Sparkles" @click="async ({ allowed, reason }) => {
                if (!allowed) return emit('notify', reason)
                emit('notify', '正在重新评估所有设备 AI 健康...')
                try {
                  const r = await dashboardApi.refreshAiDeviceHealth()
                  emit('notify', `AI 健康已重算：${r.analyzedCount} 台更新 / ${r.failedCount} 失败 / ${r.elapsedMs ?? 0}ms`)
                  await loadData(paging.value.page)
                } catch (e) {
                  emit('notify', e instanceof Error ? e.message : '重算失败')
                }
              }">AI 重算</PermissionButton>
              <PermissionButton permission="devices:create" :icon="PencilLine" @click="({ allowed, reason }) => allowed ? emit('notify', '设备新增表单已打开') : emit('notify', reason)">新增</PermissionButton>
            </div>
          </div>
          <div class="warning-list">
            <article v-for="device in devices" :key="device.id" class="warning-item" @click="openDetail">
              <div class="warning-symbol" :class="device.state === '在线' ? 'blue' : 'orange'">{{ device.state === '在线' ? '✓' : '!' }}</div>
              <div class="warning-body">
                <div class="warning-title">
                  <strong>{{ device.name }}</strong>
                  <span :class="device.state === '在线' ? 'blue' : 'orange'">{{ device.state }}</span>
                  <span
                    v-if="device.aiHealthLevel"
                    :class="`ai-tag ai-tag-${(device.aiHealthLevel || '').toLowerCase()}`"
                    :title="`AI 健康分 ${(device.aiHealthScore ?? 0).toFixed(1)} / 100`"
                  >
                    <Sparkles :size="10" />AI {{ aiHealthLabel(device.aiHealthLevel) }} {{ device.aiHealthScore != null ? Number(device.aiHealthScore).toFixed(0) : '' }}
                  </span>
                </div>
                <p>{{ device.type }} · {{ device.point }} · 信号 {{ device.signal }}</p>
                <small><Gauge :size="12" />电量 {{ device.battery }}% · {{ device.last }}</small>
              </div>
            </article>
          </div>
          <div class="panel-footer pagination-bar">
            <span>共 {{ paging.total }} 条，{{ paging.page }}/{{ paging.pages || 1 }} 页</span>
            <div class="detail-utility">
              <button class="secondary-btn" :disabled="paging.page <= 1" @click="changePage(-1)">上一页</button>
              <button class="secondary-btn" :disabled="paging.page >= (paging.pages || 1)" @click="changePage(1)">下一页</button>
            </div>
          </div>
        </section>

        <section class="panel system-panel">
          <div class="panel-header compact">
            <div>
              <h2>设备详情</h2>
              <p>单台设备的运行概况和接入情况</p>
            </div>
            <span class="health-label"><i></i>{{ selectedDevice?.state }}</span>
          </div>
          <div v-if="selectedDevice" class="system-list">
            <div><span><Radio :size="16" />设备编号</span><strong>{{ selectedDevice.name }}</strong></div>
            <div><span><Layers3 :size="16" />设备类型</span><strong>{{ selectedDevice.type }}</strong></div>
            <div><span><MapPin :size="16" />绑定点位</span><strong>{{ selectedDevice.point }}</strong></div>
            <div><span><MessageSquareText :size="16" />最近上报</span><strong>{{ selectedDevice.last }}</strong></div>
          </div>

          <!-- AI 设备健康研判 -->
          <div v-if="selectedDevice" class="ai-health-card" :class="`ai-card ai-card-${(selectedDevice.aiHealthLevel || 'unknown').toLowerCase()}`">
            <div class="ai-card-head">
              <div>
                <small><Sparkles :size="12" />AI 设备健康研判</small>
                <strong v-if="selectedDevice.aiHealthScore != null" :class="`ai-tag ai-tag-${(selectedDevice.aiHealthLevel || '').toLowerCase()}`">
                  {{ aiHealthLabel(selectedDevice.aiHealthLevel) }} · {{ Number(selectedDevice.aiHealthScore).toFixed(1) }} / 100
                </strong>
                <strong v-else style="color:#7a8d89">AI 暂未评分</strong>
              </div>
              <span v-if="selectedDevice.aiAnalyzedAt" class="ai-card-time">{{ new Date(selectedDevice.aiAnalyzedAt).toLocaleString('zh-CN', { hour12: false }) }}</span>
            </div>
            <div v-if="selectedDevice.aiAnomalyScore != null" class="ai-health-meters">
              <div class="ai-meter">
                <small>异常分</small>
                <div class="ai-bar-track"><i :style="{ width: selectedDevice.aiAnomalyScore + '%', background: selectedDevice.aiAnomalyScore > 50 ? '#d57252' : selectedDevice.aiAnomalyScore > 20 ? '#c69132' : '#4c8b87' }"></i></div>
                <strong>{{ Number(selectedDevice.aiAnomalyScore).toFixed(1) }}</strong>
              </div>
              <div class="ai-meter">
                <small>预测风险</small>
                <div class="ai-bar-track"><i :style="{ width: selectedDevice.aiForecastRisk + '%', background: selectedDevice.aiForecastRisk > 50 ? '#d57252' : selectedDevice.aiForecastRisk > 20 ? '#c69132' : '#4c8b87' }"></i></div>
                <strong>{{ Number(selectedDevice.aiForecastRisk).toFixed(1) }}</strong>
              </div>
            </div>
            <ul v-if="selectedDevice.aiReasoning?.length" class="ai-health-reasons">
              <li v-for="(r, i) in selectedDevice.aiReasoning.slice(0, 3)" :key="i">{{ r }}</li>
            </ul>
            <p v-else-if="selectedDevice.aiHealthScore == null" class="ai-health-hint">等待 AI 评分（5 分钟自动刷新）</p>
            <div v-if="selectedDevice.aiSuggestedActions?.length" class="ai-health-actions">
              <small>建议：</small>
              <span v-for="(a, i) in selectedDevice.aiSuggestedActions.slice(0, 2)" :key="i" class="ai-action-pill">{{ a }}</span>
            </div>
          </div>

          <div v-if="selectedDevice" class="task-summary">
            <div class="progress-ring"><span>{{ selectedDevice.battery }}<small>%</small></span></div>
            <div class="task-overview">
              <strong>电量</strong>
              <span>信号：{{ selectedDevice.signal }}</span>
              <div class="task-bars"><i :style="{ width: `${selectedDevice.battery}%` }"></i></div>
              <p>支持 EMQX 接入与 WebSocket 推送</p>
            </div>
          </div>
          <div class="heading-actions" style="padding: 0 20px 18px; justify-content: flex-end;">
            <PermissionButton permission="devices:edit" :icon="Wrench" @click="async ({ allowed, reason }) => {
              if (!allowed) return emit('notify', reason)
              await diagnoseDevice()
            }">诊断</PermissionButton>
            <PermissionButton permission="devices:delete" :icon="Trash2" @click="async ({ allowed, reason }) => {
              if (!allowed) return emit('notify', reason)
              await deleteDevice()
            }">删除</PermissionButton>
            <PermissionButton permission="ai:insight:refresh" :icon="Stethoscope" @click="async ({ allowed, reason }) => {
              if (!allowed) return emit('notify', reason)
              try {
                await dashboardApi.refreshAiDeviceHealth()
                emit('notify', '已请求 AI 重算，稍候刷新')
                setTimeout(() => loadData(paging.value.page), 3000)
              } catch (e) { emit('notify', e instanceof Error ? e.message : '重算失败') }
            }">AI 重算</PermissionButton>
            <button class="primary-btn" @click="openDetail">查看详情页</button>
          </div>
        </section>
      </div>
    </template>
  </div>
</template>
