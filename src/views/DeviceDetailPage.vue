<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Database, Gauge, GitBranch, History, Layers3, MapPin, MessageSquareText, PencilLine, Save, Radio, Paperclip, Sparkles, Stethoscope } from 'lucide-vue-next'
import PermissionButton from '../components/PermissionButton.vue'
import { dashboardApi } from '../services/dashboardApi'

const route = useRoute()
const router = useRouter()
const device = ref(null)
const loading = ref(true)
const editMode = ref(false)
const form = ref({})
const aiHealth = ref(null)
const aiRefreshing = ref(false)

const aiHealthLabel = computed(() => {
  const m = { HEALTHY: '健康', ATTENTION: '注意', WARN: '预警', CRITICAL: '严重' }
  return m[aiHealth.value?.healthLevel] || '—'
})

async function loadData() {
  loading.value = true
  const data = await dashboardApi.getDeviceDetail(route.params.id)
  device.value = data.item
  form.value = {
    name: data.item.name,
    type: data.item.type,
    point: data.item.point,
    state: data.item.state,
    battery: data.item.battery,
    signal: data.item.signal,
  }
  loading.value = false
  loadAiHealth()
}

async function loadAiHealth() {
  if (!device.value) return
  try {
    const code = device.value.code ?? device.value.deviceCode
    if (!code) return
    aiHealth.value = await dashboardApi.getAiDeviceHealth(code)
  } catch (_) {
    aiHealth.value = null
  }
}

async function refreshAi() {
  aiRefreshing.value = true
  try {
    const code = device.value?.code ?? device.value?.deviceCode
    if (!code) return
    const result = await fetch('/api/ai/device-health/refresh-one?deviceCode=' + encodeURIComponent(code), {
      method: 'POST',
      headers: { Authorization: 'Bearer ' + (localStorage.getItem('geosentry_token') || '') }
    })
    const json = await result.json()
    if (json?.code === 0) {
      aiHealth.value = json.data
    }
  } catch (_) {
    // silent
  } finally {
    aiRefreshing.value = false
  }
}

async function saveDetail() {
  if (!device.value) return
  const result = await dashboardApi.updateDevice(device.value.id, { ...form.value, battery: Number(form.value.battery) })
  // 后端 PUT /devices/{id} 返回 DeviceDto（无 item 包装），直接合并
  device.value = { ...device.value, ...result }
  editMode.value = false
}

async function refreshDetail() {
  await loadData()
}

onMounted(loadData)
</script>

<template>
  <div>
    <div class="content-grid">
      <section class="panel system-panel">
        <div class="panel-header compact">
          <div>
            <h2>监测设备详情</h2>
            <p>接入信息、编辑与运行概况</p>
          </div>
          <div class="heading-actions">
            <button class="text-btn" @click="router.back()"><ArrowLeft :size="15" />返回</button>
            <button class="text-btn" @click="refreshDetail"><History :size="15" />刷新</button>
            <button class="primary-btn" @click="editMode = !editMode"><PencilLine :size="15" />{{ editMode ? '取消编辑' : '编辑详情' }}</button>
          </div>
        </div>
        <div v-if="loading" style="padding: 0 20px 18px; color: #7a8d89;">加载中...</div>
        <template v-else-if="device">
          <div class="system-list">
            <div><span><Radio :size="16" />设备编号</span><strong v-if="!editMode">{{ device.name }}</strong><input v-else v-model="form.name" /></div>
            <div><span><Layers3 :size="16" />设备类型</span><strong v-if="!editMode">{{ device.type }}</strong><input v-else v-model="form.type" /></div>
            <div><span><MapPin :size="16" />绑定点位</span><strong v-if="!editMode">{{ device.point }}</strong><input v-else v-model="form.point" /></div>
            <div><span><MessageSquareText :size="16" />最近上报</span><strong>{{ device.last }}</strong></div>
          </div>
          <div class="task-summary">
            <div class="progress-ring"><span>{{ device.battery }}<small>%</small></span></div>
            <div class="task-overview">
              <strong v-if="!editMode">{{ device.state }}</strong>
              <select v-else v-model="form.state">
                <option value="在线">在线</option>
                <option value="离线">离线</option>
              </select>
              <span>信号：{{ device.signal }}</span>
              <div class="task-bars"><i :style="{ width: `${device.battery}%` }"></i></div>
              <p>支持平台自动巡检和离线告警</p>
            </div>
          </div>

          <!-- AI 设备健康研判（详情页） -->
          <div class="ai-health-card" :class="`ai-card ai-card-${(aiHealth?.healthLevel || 'unknown').toLowerCase()}`">
            <div class="ai-card-head">
              <div>
                <small><Sparkles :size="12" />AI 设备健康研判</small>
                <strong v-if="aiHealth" :class="`ai-tag ai-tag-${(aiHealth.healthLevel || '').toLowerCase()}`">
                  {{ aiHealthLabel }} · {{ Number(aiHealth.healthScore || 0).toFixed(1) }} / 100
                </strong>
                <strong v-else style="color:#7a8d89">AI 暂未评分（5 分钟内自动更新）</strong>
              </div>
              <span v-if="aiHealth?.analyzedAt" class="ai-card-time">{{ new Date(aiHealth.analyzedAt).toLocaleString('zh-CN', { hour12: false }) }}</span>
            </div>
            <div v-if="aiHealth" class="ai-health-meters">
              <div class="ai-meter">
                <small>异常分</small>
                <div class="ai-bar-track"><i :style="{ width: (aiHealth.anomalyScore ?? 0) + '%', background: (aiHealth.anomalyScore ?? 0) > 50 ? '#d57252' : (aiHealth.anomalyScore ?? 0) > 20 ? '#c69132' : '#4c8b87' }"></i></div>
                <strong>{{ Number(aiHealth.anomalyScore ?? 0).toFixed(1) }}</strong>
              </div>
              <div class="ai-meter">
                <small>预测风险</small>
                <div class="ai-bar-track"><i :style="{ width: (aiHealth.forecastRisk ?? 0) + '%', background: (aiHealth.forecastRisk ?? 0) > 50 ? '#d57252' : (aiHealth.forecastRisk ?? 0) > 20 ? '#c69132' : '#4c8b87' }"></i></div>
                <strong>{{ Number(aiHealth.forecastRisk ?? 0).toFixed(1) }}</strong>
              </div>
              <div class="ai-meter">
                <small>指标覆盖</small>
                <div class="ai-bar-track"><i :style="{ width: ((aiHealth.metricCoverage ?? 0) / 4 * 100) + '%', background: '#4c8b87' }"></i></div>
                <strong>{{ aiHealth.metricCoverage ?? 0 }} / 4</strong>
              </div>
            </div>
            <ul v-if="aiHealth?.reasoning?.length" class="ai-health-reasons">
              <li v-for="(r, i) in aiHealth.reasoning.slice(0, 3)" :key="i">{{ r }}</li>
            </ul>
            <div v-if="aiHealth?.suggestedActions?.length" class="ai-health-actions">
              <small>建议：</small>
              <span v-for="(a, i) in aiHealth.suggestedActions.slice(0, 3)" :key="i" class="ai-action-pill">{{ a }}</span>
            </div>
            <div class="heading-actions" style="padding: 8px 0 0; justify-content: flex-end; gap: 6px;">
              <span v-if="aiHealth?.model" style="font-size: 10px; color: #99a7a1;">模型：{{ aiHealth.model }} · 置信度 {{ ((aiHealth.confidence ?? 0) * 100).toFixed(0) }}%</span>
              <PermissionButton permission="ai:insight:refresh" :icon="Stethoscope" @click="async ({ allowed, reason }) => {
                if (!allowed) return
                await refreshAi()
              }">立即重算</PermissionButton>
            </div>
          </div>
          <div class="heading-actions" style="padding: 0 20px 18px; justify-content: flex-end;">
            <PermissionButton permission="devices:edit" :icon="Save" @click="async ({ allowed }) => {
              if (!allowed) return
              await saveDetail()
            }">保存</PermissionButton>
          </div>
        </template>
      </section>

      <section class="panel warning-panel">
        <div class="panel-header compact">
          <div>
            <h2>数据上报曲线</h2>
            <p>最近一段时间的监测上报趋势</p>
          </div>
        </div>
        <div v-if="device" class="warning-list">
          <article v-for="record in device.history" :key="`${record.time}-${record.title}`" class="warning-item">
            <div class="warning-symbol blue">D</div>
            <div class="warning-body">
              <div class="warning-title"><strong>{{ record.title }}</strong><span class="blue">{{ record.time }}</span></div>
              <p>{{ record.desc }}</p>
            </div>
          </article>
        </div>
        <div v-if="device" class="chart-card">
          <div class="mini-chart-header">
            <strong><Paperclip :size="16" />附件</strong>
            <span>设备说明与校准记录</span>
          </div>
          <div class="pie-list">
            <div v-for="file in device.attachments" :key="file.name" class="pie-item">
              <span>{{ file.name }}</span>
              <div class="pie-track"><i style="width: 100%; background: #4f8f99"></i></div>
              <strong>{{ file.size }}</strong>
            </div>
          </div>
        </div>
        <div v-if="device" class="chart-card">
          <div class="mini-chart-header">
            <strong><GitBranch :size="16" />关联联动</strong>
            <span>点击跳转 · 灾害点 / 预警 / 任务</span>
          </div>
          <div v-if="device.linked?.length" class="pie-list">
            <RouterLink v-for="item in device.linked" :key="`${item.type}-${item.label}`" :to="item.path" class="pie-item link-item">
              <span class="link-type">{{ item.type }}</span>
              <span class="link-label">{{ item.label }}</span>
              <div class="pie-track"><i style="width: 100%; background: #6dbfa5"></i></div>
              <strong class="link-go">查看 ›</strong>
            </RouterLink>
          </div>
          <p v-else class="people-empty" style="padding: 12px;">暂无关联记录</p>
        </div>
      </section>
    </div>
  </div>
</template>
