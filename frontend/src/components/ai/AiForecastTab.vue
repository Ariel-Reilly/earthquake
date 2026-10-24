<script setup>
/**
 * AI 趋势预测Tab页面
 * 
 * 功能：
 * - 显示灾害点关联的监测设备列表
 * - 选择设备后展示预测曲线（未来24/48/72小时）
 * - 异常兜底：无设备时显示友好空状态提示
 * - 允许用户手动切换设备
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { aiApi } from '../../services/aiApi'
import { dashboardApi } from '../../services/dashboardApi'
import { createDebouncedHazardSelector } from '../../utils/hazardEventBus'
import {
  AlertTriangle,
  Loader2,
  Plus,
  Radio,
  TrendingUp,
} from 'lucide-vue-next'

const props = defineProps({
  selectedHazardCode: { type: String, required: true },
})

const emit = defineEmits(['notify'])

// 设备列表
const devices = ref([])
const selectedDeviceId = ref(null)
const devicesLoading = ref(false)
const devicesError = ref('')

// 预测数据
const forecastData = ref(null)
const forecastLoading = ref(false)
const forecastError = ref('')

// 预测周期选择
const forecastHours = ref(24)
const forecastHourOptions = [
  { label: '24小时', value: 24 },
  { label: '48小时', value: 48 },
  { label: '72小时', value: 72 },
]

// 加载灾害点关联的设备
async function loadDevices() {
  if (!props.selectedHazardCode) {
    devices.value = []
    selectedDeviceId.value = null
    return
  }

  devicesLoading.value = true
  devicesError.value = ''
  try {
    const resp = await dashboardApi.getDevicesForHazard(props.selectedHazardCode)
    devices.value = resp?.list ?? resp ?? []
    
    // 自动选择第一个设备
    if (devices.value.length > 0) {
      selectedDeviceId.value = devices.value[0].deviceId ?? devices.value[0].id
      await loadForecast()
    } else {
      selectedDeviceId.value = null
      forecastData.value = null
    }
  } catch (err) {
    devicesError.value = err?.message || '设备加载失败'
    devices.value = []
    selectedDeviceId.value = null
  } finally {
    devicesLoading.value = false
  }
}

// 防抖watch，防止快速切换灾害点时的重复加载
const debouncedLoadDevices = createDebouncedHazardSelector(loadDevices, 300)

watch(() => props.selectedHazardCode, debouncedLoadDevices)

// 加载预测数据
async function loadForecast() {
  if (!selectedDeviceId.value) {
    forecastData.value = null
    return
  }

  forecastLoading.value = true
  forecastError.value = ''
  try {
    const resp = await aiApi.getForecast({
      deviceId: selectedDeviceId.value,
      hours: forecastHours.value,
    })
    forecastData.value = resp
  } catch (err) {
    forecastError.value = err?.message || '预测数据加载失败'
    forecastData.value = null
  } finally {
    forecastLoading.value = false
  }
}

// 设备选择变化
async function handleDeviceChange(deviceId) {
  selectedDeviceId.value = deviceId
  await loadForecast()
}

// 预测周期变化
async function handleForecastHoursChange(hours) {
  forecastHours.value = hours
  if (selectedDeviceId.value) {
    await loadForecast()
  }
}

// 计算属性
const hasDevices = computed(() => devices.value.length > 0)

const selectedDevice = computed(() =>
  devices.value.find(d => (d.deviceId ?? d.id) === selectedDeviceId.value)
)

const selectedDeviceName = computed(() =>
  selectedDevice.value?.deviceName ?? selectedDevice.value?.name ?? selectedDeviceId.value ?? '未选择'
)

// 预测数据处理
const forecastPoints = computed(() => {
  if (!forecastData.value?.points) return []
  return forecastData.value.points.map(p => ({
    timestamp: new Date(p.timestamp).toLocaleTimeString('zh-CN', {
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    }),
    value: p.value,
    confidence: p.confidence,
    unit: p.unit || 'mm',
  }))
})

const forecastConfidence = computed(() => {
  if (!forecastData.value) return null
  return (forecastData.value.confidence ?? 0.7)
})

const confidenceLevel = computed(() => {
  const conf = forecastConfidence.value
  if (conf >= 0.8) return { label: '高', color: 'green' }
  if (conf >= 0.6) return { label: '中', color: 'yellow' }
  return { label: '低', color: 'red' }
})

const forecastMetrics = computed(() => forecastData.value?.metrics ?? {})

const maxValue = computed(() => {
  const points = forecastPoints.value
  if (!points.length) return 0
  return Math.max(...points.map(p => p.value || 0))
})
</script>

<template>
  <div class="forecast-tab">
    <!-- 无设备空状态 -->
    <div v-if="!hasDevices && !devicesLoading" class="empty-state">
      <AlertTriangle size="32" />
      <h3>🔌 该灾害点暂未接入监测设备</h3>
      <p>无法进行趋势预测。您可以：</p>
      <ul>
        <li>切换其他灾害点查看预测趋势</li>
        <li>联系管理员为该灾害点新增监测设备</li>
      </ul>
      <button class="cta-btn" @click="$emit('navigate-to-devices')">
        <Plus size="16" /> 前往设备管理
      </button>
    </div>

    <!-- 加载中 -->
    <div v-else-if="devicesLoading" class="loading-state">
      <Loader2 class="spin-icon" />
      <p>正在加载监测设备...</p>
    </div>

    <!-- 加载失败 -->
    <div v-else-if="devicesError" class="error-state">
      <AlertTriangle />
      <p>{{ devicesError }}</p>
      <button class="retry-btn" @click="loadDevices">重试</button>
    </div>

    <!-- 主体内容 -->
    <div v-else class="forecast-content">
      <!-- 设备选择器 -->
      <div class="device-selector">
        <label>监测设备</label>
        <select v-model="selectedDeviceId" @change="handleDeviceChange(selectedDeviceId)">
          <option v-for="device in devices" :key="device.deviceId ?? device.id" :value="device.deviceId ?? device.id">
            {{ device.deviceName ?? device.name }} ({{ device.deviceType ?? '未知' }})
          </option>
        </select>
        <span class="device-status" :class="`status-${device.status ?? 'unknown'}`">
          <Radio size="12" />
          {{ device.onlineStatus ?? device.status ?? '未知' }}
        </span>
      </div>

      <!-- 预测周期选择 -->
      <div class="forecast-period">
        <label>预测周期</label>
        <div class="period-buttons">
          <button
            v-for="option in forecastHourOptions"
            :key="option.value"
            class="period-btn"
            :class="{ active: forecastHours === option.value }"
            @click="handleForecastHoursChange(option.value)"
          >
            {{ option.label }}
          </button>
        </div>
      </div>

      <!-- 预测数据加载中 -->
      <div v-if="forecastLoading" class="forecast-loading">
        <Loader2 class="spin-icon" />
        <p>正在生成预测...</p>
      </div>

      <!-- 预测失败 -->
      <div v-else-if="forecastError" class="forecast-error">
        <AlertTriangle />
        <p>{{ forecastError }}</p>
        <p class="hint">该设备暂无预测数据，请稍后重试或切换设备</p>
      </div>

      <!-- 预测曲线 -->
      <div v-else-if="forecastData && forecastPoints.length" class="forecast-chart">
        <!-- 置信度指示 -->
        <div class="confidence-indicator">
          <span>预测置信度：</span>
          <span class="confidence-badge" :class="`level-${confidenceLevel.color}`">
            {{ confidenceLevel.label }} ({{ (forecastConfidence * 100).toFixed(0) }}%)
          </span>
        </div>

        <!-- 预测曲线容器（简化，实际项目中可用ECharts） -->
        <div class="chart-placeholder">
          <div class="chart-title">
            <TrendingUp size="16" />
            预测趋势
          </div>
          <div class="chart-data">
            <div class="data-item">
              <span class="label">预测周期</span>
              <span class="value">{{ forecastHours }} 小时</span>
            </div>
            <div class="data-item">
              <span class="label">最大预测值</span>
              <span class="value">{{ maxValue.toFixed(1) }} {{ forecastPoints[0]?.unit }}</span>
            </div>
            <div class="data-item">
              <span class="label">数据点数</span>
              <span class="value">{{ forecastPoints.length }}</span>
            </div>
          </div>
          <div class="chart-hint">
            📊 实际项目中此处应显示ECharts交互式曲线图。当前示意预测数据已加载。
          </div>
        </div>

        <!-- 预测详细数据表 -->
        <div class="forecast-table">
          <h3>预测数据详情</h3>
          <table>
            <thead>
              <tr>
                <th>时间</th>
                <th>预测值</th>
                <th>置信度</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(point, idx) in forecastPoints.slice(0, 10)" :key="idx">
                <td>{{ point.timestamp }}</td>
                <td class="numeric">{{ point.value.toFixed(2) }} {{ point.unit }}</td>
                <td>
                  <span class="confidence-bar">
                    <span class="confidence-fill" :style="{ width: (point.confidence * 100) + '%' }" />
                  </span>
                </td>
              </tr>
            </tbody>
          </table>
          <div v-if="forecastPoints.length > 10" class="table-note">
            显示前10条数据，共{{ forecastPoints.length }}条
          </div>
        </div>

        <!-- 预测指标 -->
        <div v-if="forecastMetrics && Object.keys(forecastMetrics).length" class="forecast-metrics">
          <h3>预测指标</h3>
          <div class="metrics-grid">
            <div v-for="(value, key) in forecastMetrics" :key="key" class="metric-card">
              <div class="metric-label">{{ key }}</div>
              <div class="metric-value">{{ typeof value === 'number' ? value.toFixed(2) : value }}</div>
            </div>
          </div>
        </div>
      </div>

      <!-- 无预测数据 -->
      <div v-else class="no-forecast">
        <AlertTriangle />
        <p>该设备暂无预测数据</p>
        <p class="hint">请选择其他设备或稍后重试</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.forecast-tab {
  padding: 20px;
}

.empty-state,
.loading-state,
.error-state,
.no-forecast {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 60px 20px;
  color: var(--c-text-2);
  text-align: center;
  border: 1px dashed var(--c-separator);
  border-radius: var(--r-lg);
}

.empty-state h3 {
  margin: 0;
  font-size: 16px;
  color: var(--c-text);
}

.empty-state p {
  margin: 8px 0;
  font-size: 13px;
}

.empty-state ul {
  margin: 8px 0;
  padding-left: 20px;
  font-size: 12px;
  text-align: left;
}

.empty-state li {
  margin: 4px 0;
}

.cta-btn,
.retry-btn {
  margin-top: 12px;
  padding: 8px 14px;
  border: 0;
  background: var(--c-brand);
  color: var(--c-on-brand);
  border-radius: var(--r-sm);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.cta-btn:hover,
.retry-btn:hover {
  background: var(--c-brand-hover);
}

.spin-icon {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.forecast-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 设备选择器 */
.device-selector,
.forecast-period {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--c-border);
  border-radius: var(--r-md);
  background: var(--c-surface);
}

.device-selector label,
.forecast-period label {
  font-size: 13px;
  font-weight: 600;
  color: var(--c-text);
  flex-shrink: 0;
}

.device-selector select {
  flex: 1;
  padding: 6px 8px;
  border: 1px solid var(--c-border);
  border-radius: var(--r-sm);
  background: var(--c-surface);
  color: var(--c-text);
  font-size: 12px;
}

.device-status {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: var(--c-text-3);
  flex-shrink: 0;
}

.device-status.status-online {
  color: var(--c-success-strong);
}

.device-status.status-offline {
  color: var(--c-danger-strong);
}

/* 预测周期选择 */
.forecast-period {
  flex-direction: column;
  align-items: flex-start;
}

.period-buttons {
  display: flex;
  gap: 8px;
}

.period-btn {
  padding: 6px 12px;
  border: 1px solid var(--c-border);
  background: var(--c-surface);
  border-radius: var(--r-sm);
  font-size: 12px;
  cursor: pointer;
  color: var(--c-text);
  transition: all 0.2s;
}

.period-btn:hover {
  background: var(--c-surface-2);
}

.period-btn.active {
  background: var(--c-brand);
  color: var(--c-on-brand);
  border-color: var(--c-brand);
}

/* 预测加载状态 */
.forecast-loading,
.forecast-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 40px;
  border: 1px dashed var(--c-separator);
  border-radius: var(--r-lg);
  color: var(--c-text-2);
  text-align: center;
}

.forecast-error {
  border-color: var(--c-danger-soft);
}

.forecast-error p {
  margin: 0;
  font-size: 13px;
}

.forecast-error .hint {
  font-size: 11px;
  color: var(--c-text-3);
}

/* 置信度指示 */
.confidence-indicator {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  background: var(--c-surface-2);
  border-radius: var(--r-md);
  font-size: 12px;
}

.confidence-badge {
  padding: 3px 8px;
  border-radius: var(--r-sm);
  font-weight: 600;
  font-size: 11px;
}

.confidence-badge.level-green {
  background: var(--c-success-bg);
  color: var(--c-success-strong);
}

.confidence-badge.level-yellow {
  background: var(--c-warning-bg);
  color: var(--c-warning-ink);
}

.confidence-badge.level-red {
  background: var(--c-danger-bg);
  color: var(--c-danger-strong);
}

/* 图表占位 */
.chart-placeholder {
  padding: 20px;
  border: 1px dashed var(--c-separator);
  border-radius: var(--r-lg);
  background: var(--c-surface);
}

.chart-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  font-weight: 600;
  color: var(--c-text);
  font-size: 13px;
}

.chart-data {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
  margin-bottom: 12px;
}

.data-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.data-item .label {
  font-size: 11px;
  color: var(--c-text-3);
}

.data-item .value {
  font-size: 14px;
  font-weight: 600;
  color: var(--c-text);
  font-family: 'JetBrains Mono', monospace;
}

.chart-hint {
  font-size: 11px;
  color: var(--c-text-3);
  padding: 10px;
  background: var(--c-surface-2);
  border-radius: var(--r-sm);
}

/* 预测数据表 */
.forecast-table {
  margin-top: 12px;
}

.forecast-table h3 {
  margin: 0 0 10px;
  font-size: 13px;
  font-weight: 600;
  color: var(--c-text);
}

.forecast-table table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}

.forecast-table thead {
  background: var(--c-surface-2);
}

.forecast-table th {
  padding: 8px;
  text-align: left;
  color: var(--c-text);
  font-weight: 600;
  border-bottom: 1px solid var(--c-border);
}

.forecast-table td {
  padding: 8px;
  border-bottom: 1px solid var(--c-border);
  color: var(--c-text-2);
}

.forecast-table td.numeric {
  text-align: right;
  font-family: 'JetBrains Mono', monospace;
  color: var(--c-text);
}

.confidence-bar {
  display: inline-block;
  width: 100%;
  height: 6px;
  background: var(--c-surface-2);
  border-radius: var(--r-xs);
  overflow: hidden;
}

.confidence-fill {
  display: block;
  height: 100%;
  background: var(--c-brand);
  transition: width 0.3s;
}

.table-note {
  margin-top: 8px;
  font-size: 11px;
  color: var(--c-text-3);
  text-align: center;
}

/* 预测指标 */
.forecast-metrics {
  margin-top: 12px;
}

.forecast-metrics h3 {
  margin: 0 0 10px;
  font-size: 13px;
  font-weight: 600;
  color: var(--c-text);
}

.metrics-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 10px;
}

.metric-card {
  padding: 10px;
  border: 1px solid var(--c-border);
  border-radius: var(--r-md);
  background: var(--c-surface);
}

.metric-label {
  font-size: 11px;
  color: var(--c-text-3);
  margin-bottom: 4px;
}

.metric-value {
  font-size: 14px;
  font-weight: 600;
  color: var(--c-text);
  font-family: 'JetBrains Mono', monospace;
}

@media (max-width: 768px) {
  .chart-data {
    grid-template-columns: 1fr;
  }

  .metrics-grid {
    grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
  }
}
</style>
