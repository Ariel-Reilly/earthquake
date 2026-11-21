<script setup>
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Cpu, History, Layers3, MapPin, MessageSquareText, PencilLine, Radio, Save, Stethoscope } from 'lucide-vue-next'
import PermissionButton from '../../components/PermissionButton.vue'
import DeviceDetailSkeleton from '../../components/common/DeviceDetailSkeleton.vue'
import { waitForSkeleton } from '../../utils/uiTiming'
import { dashboardApi } from '../../services/dashboardApi'
import { useAiContext } from '../../composables/useAiContext'

const route = useRoute()
const router = useRouter()
const device = ref(null)
const metrics = ref([])
const loading = ref(true)
const loadError = ref('')
const editMode = ref(false)

// 与 device_metric 真实编码对齐（与预警分析字典同口径）
const METRIC_META = {
  disp_rate: ['位移速率', 'mm/d'], disp_total: ['累计位移', 'mm'],
  displacement_rate: ['位移速率', 'mm/d'], displacement_total: ['累计位移', 'mm'],
  crack_width: ['裂缝宽度', 'mm'], crack_width_rate: ['裂缝扩展速率', 'mm/d'],
  rainfall_1h: ['1h 降雨', 'mm'], rain_1h: ['1h 降雨', 'mm'],
  rainfall_6h: ['6h 降雨', 'mm'], rainfall_24h: ['24h 降雨', 'mm'], rain_24h: ['24h 降雨', 'mm'],
  rainfall_7d: ['7d 降雨', 'mm'], rainfall_30d: ['30d 降雨', 'mm'],
  soil_moisture: ['土壤含水率', '%'], pore_pressure: ['孔隙水压', 'kPa'],
  flow_rate: ['水流速率', 'm³/s'], slope: ['坡度', '°'], elevation_m: ['海拔', 'm'],
  temperature: ['温度', '℃'], humidity: ['空气湿度', '%'], wind_speed: ['风速', 'm/s'],
  tilt: ['倾角', '°'], tilt_angle: ['倾角', '°'],
  water_level: ['水位', 'm'], ponding_depth: ['积水深度', 'cm'],
}
const metricMeta = (code) => {
  const m = METRIC_META[code]
  return m ? { label: m[0], unit: m[1] } : { label: code, unit: '' }
}
// 迷你趋势线：把数值序列归一化成 SVG polyline 点串
const sparkline = (series = []) => {
  const vals = series.filter((v) => typeof v === 'number' && Number.isFinite(v))
  if (vals.length < 2) return ''
  const min = Math.min(...vals)
  const max = Math.max(...vals)
  const span = max - min || 1
  return vals.map((v, i) => `${(i / (vals.length - 1)) * 100},${28 - ((v - min) / span) * 24}`).join(' ')
}
const form = ref({})
const aiHealth = ref(null)
const aiRefreshing = ref(false)

const { setAiContext, clearAiContext } = useAiContext()

const aiHealthLabel = computed(() => {
  const m = { HEALTHY: '健康', ATTENTION: '注意', WARN: '预警', CRITICAL: '严重' }
  return m[aiHealth.value?.healthLevel] || '—'
})

async function loadData() {
  loading.value = true
  loadError.value = ''
  try {
    const data = await dashboardApi.getDeviceDetail(route.params.id)
    const item = data?.item
    if (!item) throw new Error('设备详情数据为空')
    device.value = item
    metrics.value = Array.isArray(data.metrics) ? data.metrics : []
    form.value = {
      name: item.name,
      type: item.type,
      point: item.point,
      state: item.state,
      battery: item.battery,
      signal: item.signal,
    }

    // 注入 AI 上下文
    setAiContext({
      contextType: 'device',
      contextId: item.deviceCode || route.params.id,
      title: item.name || `设备 ${item.deviceCode}`,
      metadata: {
        type: item.type,
        state: item.state,
        battery: item.battery,
      },
    })
  } catch (err) {
    loadError.value = err instanceof Error ? err.message : '设备详情加载失败'
    device.value = null
  } finally {
    await waitForSkeleton()
    loading.value = false
  }
  if (device.value) loadAiHealth()
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
      headers: { Authorization: 'Bearer ' + (localStorage.getItem('disasterguard_token') || '') }
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

onBeforeUnmount(() => {
  clearAiContext()
})
</script>

<template>
  <div>
    <DeviceDetailSkeleton v-if="loading && !device" />
    <div v-else-if="loadError" class="detail-load-error" role="alert">
      <p>{{ loadError }}</p>
      <button class="text-btn" @click="loadData"><History :size="15" />重试加载</button>
    </div>
    <div v-else class="content-grid">
      <section class="panel system-panel">
        <div class="panel-header compact">
          <div>
            <h2>监测设备详情</h2>
          </div>
          <div class="heading-actions">
            <button class="text-btn" @click="router.back()"><ArrowLeft :size="15" />返回</button>
            <button class="text-btn" @click="refreshDetail"><History :size="15" />刷新</button>
            <button class="primary-btn" @click="editMode = !editMode"><PencilLine :size="15" />{{ editMode ? '取消编辑' : '编辑详情' }}</button>
          </div>
        </div>
        <div v-if="loading" style="padding: 0 20px 18px; color: #76847a;">加载中...</div>
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
            </div>
          </div>

          <!-- AI 设备健康研判（详情页） -->
          <div class="ai-health-card" :class="`ai-card ai-card-${(aiHealth?.healthLevel || 'unknown').toLowerCase()}`">
            <div class="ai-card-head">
              <div>
                <small><Cpu :size="12" />AI 设备健康研判</small>
                <strong v-if="aiHealth" :class="`ai-tag ai-tag-${(aiHealth.healthLevel || '').toLowerCase()}`">
                  {{ aiHealthLabel }} · {{ Number(aiHealth.healthScore || 0).toFixed(1) }} / 100
                </strong>
                <strong v-else style="color:#76847a">AI 暂未评分（5 分钟内自动更新）</strong>
              </div>
              <span v-if="aiHealth?.analyzedAt" class="ai-card-time">{{ new Date(aiHealth.analyzedAt).toLocaleString('zh-CN', { hour12: false }) }}</span>
            </div>
            <div v-if="aiHealth" class="ai-health-meters">
              <div class="ai-meter">
                <small>异常分</small>
                <div class="ai-bar-track"><i :style="{ width: (aiHealth.anomalyScore ?? 0) + '%', background: (aiHealth.anomalyScore ?? 0) > 50 ? '#c4836f' : (aiHealth.anomalyScore ?? 0) > 20 ? '#bda06a' : '#4c8b87' }"></i></div>
                <strong>{{ Number(aiHealth.anomalyScore ?? 0).toFixed(1) }}</strong>
              </div>
              <div class="ai-meter">
                <small>预测风险</small>
                <div class="ai-bar-track"><i :style="{ width: (aiHealth.forecastRisk ?? 0) + '%', background: (aiHealth.forecastRisk ?? 0) > 50 ? '#c4836f' : (aiHealth.forecastRisk ?? 0) > 20 ? '#bda06a' : '#4c8b87' }"></i></div>
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
              <span v-if="aiHealth?.model" style="font-size: 10px; color: rgb(60 60 67 / 52%);">模型：{{ aiHealth.model }} · 置信度 {{ ((aiHealth.confidence ?? 0) * 100).toFixed(0) }}%</span>
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
            <h2>实时指标</h2>
          </div>
          <span v-if="metrics.length" class="metric-count">{{ metrics.length }} 项</span>
        </div>
        <div v-if="metrics.length" class="metric-grid">
          <article v-for="m in metrics" :key="m.metricCode" class="metric-cell">
            <div class="metric-cell-head">
              <span class="metric-name">{{ metricMeta(m.metricCode).label }}</span>
              <span class="metric-code">{{ m.metricCode }}</span>
            </div>
            <div class="metric-value">
              <strong>{{ Number(m.value).toFixed(2) }}</strong><small>{{ metricMeta(m.metricCode).unit }}</small>
            </div>
            <svg v-if="sparkline(m.series)" class="metric-spark" viewBox="0 0 100 32" preserveAspectRatio="none">
              <polyline :points="sparkline(m.series)" fill="none" stroke="currentColor" stroke-width="1.6" vector-effect="non-scaling-stroke" />
            </svg>
            <div v-else class="metric-spark-empty"></div>
            <time v-if="m.recordedAt">{{ new Date(m.recordedAt).toLocaleString('zh-CN', { hour12: false }) }}</time>
          </article>
        </div>
        <div v-else class="metric-empty">
          <Radio :size="20" />
          <span>设备尚无指标上报</span>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.detail-load-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  min-height: 220px;
  padding: 24px;
  color: var(--c-text-2);
}

.detail-load-error p {
  margin: 0;
  font-size: 13px;
}

.metric-count {
  font-size: 12px;
  color: var(--c-text-2);
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 12px;
  padding: 16px 20px 20px;
}

.metric-cell {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
  padding: 14px;
  border: 1px solid var(--c-border, rgb(0 0 0 / 8%));
  border-radius: 12px;
  background: var(--c-surface-2, rgb(0 0 0 / 2%));
}

.metric-cell-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
}

.metric-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--c-text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metric-code {
  font-size: 10px;
  color: var(--c-text-2);
  font-family: ui-monospace, monospace;
  opacity: 0.7;
  flex-shrink: 0;
}

.metric-value {
  display: flex;
  align-items: baseline;
  gap: 4px;
}

.metric-value strong {
  font-size: 22px;
  font-weight: 650;
  color: var(--c-text-1);
  line-height: 1.1;
}

.metric-value small {
  font-size: 11px;
  color: var(--c-text-2);
}

.metric-spark {
  width: 100%;
  height: 32px;
  color: var(--c-accent, #4c8b87);
  opacity: 0.85;
}

.metric-spark-empty {
  height: 32px;
}

.metric-cell time {
  font-size: 10px;
  color: var(--c-text-2);
}

.metric-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  min-height: 140px;
  color: var(--c-text-2);
  font-size: 13px;
}
</style>
