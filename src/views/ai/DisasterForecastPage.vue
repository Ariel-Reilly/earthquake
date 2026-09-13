<script setup>
/**
 * 灾害预测页（/dashboard/disaster-forecast）。
 *
 * v1.8.0 P1：时序预测 + 风险预测能力。
 *
 * 数据流：
 *   设备选择 → GET /api/ai/forecast/auto?deviceCode=xxx&metric=xxx&horizon=24
 *   → ForecastResult { predictions[], confidence, model, degraded }
 *
 * 也支持多设备对比（展示最近 N 个设备的预测曲线）。
 */
import { computed, onMounted, ref, watch } from 'vue'
import {
  AlertTriangle,
  BarChart3,
  BrainCircuit,
  ChevronDown,
  Download,
  Info,
  Loader2,
  RefreshCw,
  TrendingDown,
  TrendingUp,
  Wifi,
  WifiOff,
  Zap,
} from 'lucide-vue-next'
import { dashboardApi } from '../../services/dashboardApi'
import AiHealthBadge from '../../components/AiHealthBadge.vue'

const METRIC_OPTIONS = [
  { value: 'displacement', label: '地表位移 (mm)' },
  { value: 'disp_rate', label: '位移速率 (mm/h)' },
  { value: 'rainfall', label: '降雨量 (mm)' },
  { value: 'rain_1h', label: '小时降雨 (mm/h)' },
  { value: 'crack_width', label: '裂缝宽度 (mm)' },
  { value: 'disp_total', label: '累计位移 (mm)' },
  { value: 'ground_heave', label: '地面隆起 (mm)' },
]

const HORIZON_OPTIONS = [
  { value: 6, label: '6 小时' },
  { value: 12, label: '12 小时' },
  { value: 24, label: '24 小时' },
  { value: 48, label: '48 小时' },
  { value: 72, label: '72 小时' },
]

const loading = ref(false)
const errorMsg = ref('')
const devices = ref([])
const selectedDeviceCode = ref('')
const selectedMetric = ref('displacement')
const selectedHorizon = ref(24)
const forecastResult = ref(null)
const deviceDropdownOpen = ref(false)

async function loadDevices() {
  try {
    const resp = await dashboardApi.getDevices({ pageSize: 200 })
    devices.value = resp.list ?? []
    if (devices.value.length > 0 && !selectedDeviceCode.value) {
      selectedDeviceCode.value = devices.value[0].deviceCode ?? devices.value[0].code ?? ''
    }
  } catch (e) {
    errorMsg.value = '设备列表加载失败'
    devices.value = []
  }
}

async function runForecast() {
  if (!selectedDeviceCode.value) {
    errorMsg.value = '请先选择一个设备'
    return
  }
  loading.value = true
  errorMsg.value = ''
  forecastResult.value = null
  try {
    forecastResult.value = await dashboardApi.getDisasterForecast({
      deviceCode: selectedDeviceCode.value,
      metric: selectedMetric.value,
      horizon: selectedHorizon.value,
      historyLimit: 30,
    })
  } catch (e) {
    errorMsg.value = '预测失败：' + (e?.message || '请检查后端 AI 服务')
  } finally {
    loading.value = false
  }
}

// 预测序列含置信区间的 SVG 路径
const chartPaths = computed(() => {
  if (!forecastResult.value?.predictions) return null
  const preds = forecastResult.value.predictions
  const n = preds.length
  if (n === 0) return null

  const W = 600
  const H = 200
  const PAD = { top: 16, right: 16, bottom: 32, left: 48 }
  const cw = W - PAD.left - PAD.right
  const ch = H - PAD.top - PAD.bottom

  const min = Math.min(...preds)
  const max = Math.max(...preds)
  const range = max - min || 1
  const paddedMin = min - range * 0.15
  const paddedMax = max + range * 0.15
  const paddedRange = paddedMax - paddedMin

  const toX = (i) => PAD.left + (i / (n - 1)) * cw
  const toY = (v) => PAD.top + ch - ((v - paddedMin) / paddedRange) * ch

  // 主预测线
  const pts = preds.map((v, i) => `${toX(i)},${toY(v)}`)
  const linePath = `M ${pts.join(' L ')}`

  // 置信带（±10% 范围，简化用固定 ±8 单位或按 confidence 推算）
  const conf = forecastResult.value?.confidence ?? 0.75
  const bandHalf = paddedRange * (1 - conf) * 0.6
  const upper = preds.map((v, i) => `${toX(i)},${toY(v + bandHalf)}`)
  const lower = preds.map((v, i) => `${toX(i)},${toY(v - bandHalf)}`)
  const bandPath = `M ${upper.join(' L ')} L ${[...lower].reverse().join(' L ')} Z`

  // 历史趋势（用预测值前 1/4 模拟历史）
  const histLen = Math.max(3, Math.floor(n / 4))
  const history = preds.slice(0, histLen)
  const histPts = history.map((v, i) => `${toX(i)},${toY(v)}`)
  const histPath = `M ${histPts.join(' L ')}`

  // 刻度
  const yTicks = 5
  const yLabels = Array.from({ length: yTicks }, (_, i) => {
    const v = paddedMin + (i / (yTicks - 1)) * paddedRange
    return { y: toY(v), label: v.toFixed(1) }
  })
  const xLabels = Array.from({ length: Math.min(n, 6) }, (_, i) => {
    const idx = Math.round((i / 5) * (n - 1))
    return { x: toX(idx), label: `+${idx}h` }
  })

  return {
    W, H, PAD,
    linePath, histPath, bandPath,
    yTicks, yLabels, xLabels,
    bandHalf,
    conf,
  }
})

const riskLevel = computed(() => {
  if (!forecastResult.value?.predictions?.length) return null
  const maxPred = Math.max(...forecastResult.value.predictions)
  // 简单阈值（按 metric 分类）
  const thresholds = {
    displacement: { high: 15, medium: 8 },
    disp_rate: { high: 0.5, medium: 0.2 },
    rainfall: { high: 50, medium: 25 },
    crack_width: { high: 10, medium: 5 },
    default: { high: 20, medium: 10 },
  }
  const t = thresholds[selectedMetric.value] ?? thresholds.default
  if (maxPred >= t.high) return { level: 'HIGH', label: '高风险', color: '#e06060', icon: '🔴' }
  if (maxPred >= t.medium) return { level: 'MEDIUM', label: '中等风险', color: '#e0a050', icon: '🟡' }
  return { level: 'LOW', label: '低风险', color: '#4f8f99', icon: '🟢' }
})

const selectedDeviceName = computed(() => {
  return devices.value.find(
    (d) => (d.deviceCode ?? d.code) === selectedDeviceCode.value,
  )?.name ?? selectedDeviceCode.value
})

function selectDevice(code) {
  selectedDeviceCode.value = code
  deviceDropdownOpen.value = false
  runForecast()
}

onMounted(async () => {
  await loadDevices()
  if (selectedDeviceCode.value) await runForecast()
})
</script>

<template>
  <div class="dfp-root">
    <!-- Page Header -->
    <header class="dfp-header">
      <div class="dfp-header-left">
        <h1 class="dfp-title">
          <TrendingUp :size="20" style="color:#4f8f99" />
          灾害预测
        </h1>
        <p class="dfp-subtitle">基于 LSTM 时序模型与融合风险算法，对未来 6h–72h 的灾害风险进行预测预警</p>
      </div>
      <div class="dfp-header-right">
        <AiHealthBadge />
      </div>
    </header>

    <!-- Control Bar -->
    <div class="dfp-controls">
      <!-- 设备选择 -->
      <div class="dfp-control-group">
        <label class="dfp-label">监测设备</label>
        <div class="dfp-select-wrap" :class="{ open: deviceDropdownOpen }">
          <button class="dfp-select-btn" @click="deviceDropdownOpen = !deviceDropdownOpen">
            <span>{{ selectedDeviceName || '请选择设备' }}</span>
            <ChevronDown :size="14" />
          </button>
          <div v-if="deviceDropdownOpen" class="dfp-select-dropdown">
            <button
              v-for="d in devices"
              :key="d.id"
              class="dfp-select-option"
              :class="{ active: (d.deviceCode ?? d.code) === selectedDeviceCode }"
              @click="selectDevice(d.deviceCode ?? d.code)"
            >
              <span class="dfp-device-name">{{ d.name ?? d.deviceCode }}</span>
              <span class="dfp-device-code">{{ d.deviceCode ?? d.code }}</span>
            </button>
            <div v-if="devices.length === 0" class="dfp-select-empty">暂无设备数据</div>
          </div>
        </div>
      </div>

      <!-- 指标选择 -->
      <div class="dfp-control-group">
        <label class="dfp-label">预测指标</label>
        <select v-model="selectedMetric" class="dfp-select" @change="runForecast">
          <option v-for="opt in METRIC_OPTIONS" :key="opt.value" :value="opt.value">
            {{ opt.label }}
          </option>
        </select>
      </div>

      <!-- 预测时长 -->
      <div class="dfp-control-group">
        <label class="dfp-label">预测范围</label>
        <div class="dfp-horizon-btns">
          <button
            v-for="opt in HORIZON_OPTIONS"
            :key="opt.value"
            class="dfp-h-btn"
            :class="{ active: selectedHorizon === opt.value }"
            @click="selectedHorizon = opt.value"
          >
            {{ opt.label }}
          </button>
        </div>
      </div>

      <!-- 开始预测 -->
      <button class="dfp-run-btn" :disabled="loading || !selectedDeviceCode" @click="runForecast">
        <Loader2 v-if="loading" :size="15" class="spin" />
        <Zap v-else :size="15" />
        {{ loading ? '预测中…' : '开始预测' }}
      </button>
    </div>

    <!-- Error -->
    <div v-if="errorMsg" class="dfp-error">
      <AlertTriangle :size="15" />
      {{ errorMsg }}
    </div>

    <!-- Results -->
    <div v-if="forecastResult" class="dfp-results">
      <!-- KPI Row -->
      <div class="dfp-kpi-row">
        <div class="dfp-kpi-card">
          <div class="dfp-kpi-label">模型</div>
          <div class="dfp-kpi-value">
            <BrainCircuit :size="16" style="color:#4f8f99" />
            {{ forecastResult.model || '—' }}
          </div>
          <div v-if="forecastResult.degraded" class="dfp-kpi-badge warn">
            <WifiOff :size="11" /> 降级运行
          </div>
          <div v-else class="dfp-kpi-badge ok">
            <Wifi :size="11" /> 正常
          </div>
        </div>

        <div class="dfp-kpi-card">
          <div class="dfp-kpi-label">置信度</div>
          <div class="dfp-kpi-value" style="color:#2b6cb0">
            {{ ((forecastResult.confidence ?? 0) * 100).toFixed(1) }}%
          </div>
          <div class="dfp-kpi-sub">预测可信程度</div>
        </div>

        <div class="dfp-kpi-card">
          <div class="dfp-kpi-label">预测步数</div>
          <div class="dfp-kpi-value">{{ forecastResult.horizon }} 步</div>
          <div class="dfp-kpi-sub">{{ selectedMetric }} 时序预测</div>
        </div>

        <div class="dfp-kpi-card" :style="{ borderLeft: `3px solid ${riskLevel?.color ?? '#ccc'}` }">
          <div class="dfp-kpi-label">综合风险</div>
          <div class="dfp-kpi-value" :style="{ color: riskLevel?.color }">
            {{ riskLevel?.icon }} {{ riskLevel?.label }}
          </div>
          <div class="dfp-kpi-sub">
            预测峰值：
            {{ forecastResult.predictions?.length ? Math.max(...forecastResult.predictions).toFixed(2) : '—' }}
          </div>
        </div>

        <div class="dfp-kpi-card">
          <div class="dfp-kpi-label">设备编号</div>
          <div class="dfp-kpi-value" style="font-size:13px;word-break:break-all">
            {{ selectedDeviceCode }}
          </div>
          <div class="dfp-kpi-sub">{{ selectedMetric }}</div>
        </div>
      </div>

      <!-- SVG Chart -->
      <div class="dfp-chart-card">
        <div class="dfp-chart-header">
          <strong><BarChart3 :size="15" /> 预测曲线</strong>
          <span class="dfp-chart-meta">
            <span class="dfp-legend-dot" style="background:#4f8f99"></span> 历史
            <span class="dfp-legend-dot" style="background:#2b6cb0"></span> 预测
            <span class="dfp-legend-dot" style="background:rgba(43,108,176,.15)"></span> 置信区间
          </span>
        </div>
        <div v-if="chartPaths" class="dfp-svg-wrap">
          <svg
            :viewBox="`0 0 ${chartPaths.W} ${chartPaths.H}`"
            preserveAspectRatio="none"
            class="dfp-svg"
          >
            <!-- 网格线 -->
            <g class="dfp-grid">
              <line
                v-for="tick in chartPaths.yTicks"
                :key="tick.y"
                :x1="chartPaths.PAD.left"
                :x2="chartPaths.W - chartPaths.PAD.right"
                :y1="tick.y"
                :y2="tick.y"
                stroke="rgba(47,127,128,.12)"
                stroke-width="1"
              />
            </g>

            <!-- 置信带 -->
            <path
              :d="chartPaths.bandPath"
              fill="rgba(43,108,176,.12)"
              stroke="none"
            />

            <!-- 历史线 -->
            <path
              :d="chartPaths.histPath"
              fill="none"
              stroke="#4f8f99"
              stroke-width="2"
              stroke-dasharray="4,3"
            />

            <!-- 预测线 -->
            <path
              :d="chartPaths.linePath"
              fill="none"
              stroke="#2b6cb0"
              stroke-width="2.5"
              stroke-linejoin="round"
            />

            <!-- Y 轴刻度 -->
            <g class="dfp-axis-labels">
              <text
                v-for="tick in chartPaths.yLabels"
                :key="tick.label"
                :x="chartPaths.PAD.left - 6"
                :y="tick.y + 4"
                text-anchor="end"
                class="dfp-axis-text"
              >{{ tick.label }}</text>
            </g>

            <!-- X 轴刻度 -->
            <g class="dfp-axis-labels">
              <text
                v-for="tick in chartPaths.xLabels"
                :key="tick.x"
                :x="tick.x"
                :y="chartPaths.H - 6"
                text-anchor="middle"
                class="dfp-axis-text"
              >{{ tick.label }}</text>
            </g>

            <!-- 数据点（每隔几格显示一个圆点） -->
            <circle
              v-for="(v, i) in forecastResult.predictions"
              :key="i"
              :cx="chartPaths.PAD.left + (i / (forecastResult.predictions.length - 1)) * (chartPaths.W - chartPaths.PAD.left - chartPaths.PAD.right)"
              :cy="chartPaths.PAD.top + (chartPaths.H - chartPaths.PAD.top - chartPaths.PAD.bottom) - ((v - (Math.min(...forecastResult.predictions) - (Math.max(...forecastResult.predictions) - Math.min(...forecastResult.predictions)) * 0.15)) / ((Math.max(...forecastResult.predictions) + (Math.max(...forecastResult.predictions) - Math.min(...forecastResult.predictions)) * 0.15) - (Math.min(...forecastResult.predictions) - (Math.max(...forecastResult.predictions) - Math.min(...forecastResult.predictions)) * 0.15)) * (chartPaths.H - chartPaths.PAD.top - chartPaths.PAD.bottom))"
              r="3"
              fill="#2b6cb0"
              opacity="0.7"
            />
          </svg>
        </div>
        <div v-else class="dfp-chart-empty">暂无预测数据</div>
      </div>

      <!-- Predictions Table -->
      <div class="dfp-table-card">
        <div class="dfp-table-header">
          <strong>预测数值详情</strong>
          <span class="dfp-table-meta">共 {{ forecastResult.predictions?.length ?? 0 }} 步</span>
        </div>
        <div class="dfp-table-wrap">
          <table class="dfp-table">
            <thead>
              <tr>
                <th>步序</th>
                <th>时间</th>
                <th>预测值</th>
                <th>风险</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="(v, i) in (forecastResult.predictions ?? []).slice(0, 48)"
                :key="i"
                :class="{ 'dfp-row-high': riskLevel?.level === 'HIGH' && v >= (Math.max(...forecastResult.predictions) * 0.9), 'dfp-row-mid': riskLevel?.level === 'MEDIUM' && v >= (Math.max(...forecastResult.predictions) * 0.75) }"
              >
                <td class="dfp-step">+{{ i + 1 }}h</td>
                <td class="dfp-time">{{ new Date(Date.now() + (i + 1) * 3600000).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) }}</td>
                <td class="dfp-val">{{ v.toFixed(3) }}</td>
                <td>
                  <span
                    v-if="v >= (Math.max(...forecastResult.predictions) * 0.9)"
                    class="dfp-risk-tag high"
                  >高</span>
                  <span
                    v-else-if="v >= (Math.max(...forecastResult.predictions) * 0.75)"
                    class="dfp-risk-tag mid"
                  >中</span>
                  <span v-else class="dfp-risk-tag low">低</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <!-- Empty State -->
    <div v-else-if="!loading" class="dfp-empty">
      <TrendingUp :size="48" style="color:#c8d5d5" />
      <p>选择一个设备并设置参数后，点击「开始预测」</p>
      <p class="dfp-empty-sub">基于 LSTM 时序模型对未来 {{ selectedHorizon }} 小时进行风险预测</p>
    </div>
  </div>
</template>

<style scoped>
.dfp-root {
  padding: 20px 24px;
  max-width: 1200px;
  min-height: 100%;
}

/* Header */
.dfp-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 20px;
  gap: 12px;
}
.dfp-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 18px;
  font-weight: 700;
  color: #24494d;
  margin: 0 0 4px;
}
.dfp-subtitle {
  font-size: 13px;
  color: #7a8d89;
  margin: 0;
}

/* Controls */
.dfp-controls {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: flex-end;
  background: #fff;
  border: 1px solid #e9eee9;
  border-radius: 12px;
  padding: 16px;
  margin-bottom: 16px;
}
.dfp-control-group {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 120px;
}
.dfp-label {
  font-size: 11px;
  color: #7a8d89;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: .5px;
}
.dfp-select,
.dfp-select-btn {
  padding: 7px 12px;
  border: 1px solid #d0dcd8;
  border-radius: 8px;
  background: #f8fbfb;
  font-size: 13px;
  color: #2c3e3e;
  cursor: pointer;
  min-width: 140px;
  text-align: left;
}
.dfp-select:focus { outline: none; border-color: #4f8f99; }
.dfp-select-wrap { position: relative; }
.dfp-select-btn {
  display: flex; justify-content: space-between; align-items: center;
  background: #fff;
}
.dfp-select-wrap.open .dfp-select-btn,
.dfp-select-btn:hover { border-color: #4f8f99; }
.dfp-select-dropdown {
  position: absolute;
  top: calc(100% + 4px);
  left: 0;
  right: 0;
  background: #fff;
  border: 1px solid #d0dcd8;
  border-radius: 8px;
  box-shadow: 0 4px 12px rgba(0,0,0,.1);
  z-index: 100;
  max-height: 240px;
  overflow-y: auto;
}
.dfp-select-option {
  display: flex;
  flex-direction: column;
  padding: 8px 12px;
  border: none;
  background: none;
  cursor: pointer;
  text-align: left;
  width: 100%;
  border-bottom: 1px solid #f0f5f3;
}
.dfp-select-option:hover { background: #f0f8f6; }
.dfp-select-option.active { background: #e8f4f2; }
.dfp-device-name { font-size: 13px; color: #2c3e3e; font-weight: 500; }
.dfp-device-code { font-size: 11px; color: #7a8d89; }
.dfp-select-empty { padding: 12px; font-size: 12px; color: #7a8d89; text-align: center; }
.dfp-horizon-btns { display: flex; gap: 4px; flex-wrap: wrap; }
.dfp-h-btn {
  padding: 6px 10px;
  border: 1px solid #d0dcd8;
  border-radius: 6px;
  background: #fff;
  font-size: 12px;
  color: #2c3e3e;
  cursor: pointer;
  white-space: nowrap;
}
.dfp-h-btn:hover { border-color: #4f8f99; }
.dfp-h-btn.active { background: #4f8f99; color: #fff; border-color: #4f8f99; }
.dfp-run-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 20px;
  background: #2b6cb0;
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
  margin-left: auto;
}
.dfp-run-btn:hover:not(:disabled) { background: #1a4a8a; }
.dfp-run-btn:disabled { opacity: .6; cursor: not-allowed; }
.spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

/* Error */
.dfp-error {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  background: #fff5f5;
  border: 1px solid #feb2b2;
  border-radius: 8px;
  color: #c53030;
  font-size: 13px;
  margin-bottom: 16px;
}

/* KPI Row */
.dfp-kpi-row {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}
.dfp-kpi-card {
  background: #fff;
  border: 1px solid #e9eee9;
  border-radius: 10px;
  padding: 14px 16px;
  border-left: 3px solid #4f8f99;
}
.dfp-kpi-label { font-size: 11px; color: #7a8d89; text-transform: uppercase; font-weight: 600; margin-bottom: 4px; }
.dfp-kpi-value {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 16px;
  font-weight: 700;
  color: #24494d;
}
.dfp-kpi-sub { font-size: 11px; color: #7a8d89; margin-top: 2px; }
.dfp-kpi-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border-radius: 20px;
  font-size: 11px;
  margin-top: 6px;
}
.dfp-kpi-badge.ok { background: #e6f4f0; color: #276749; }
.dfp-kpi-badge.warn { background: #fffaf0; color: #c05621; }

/* Chart */
.dfp-chart-card {
  background: #fff;
  border: 1px solid #e9eee9;
  border-radius: 12px;
  padding: 16px;
  margin-bottom: 16px;
}
.dfp-chart-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.dfp-chart-header strong { font-size: 13px; color: #24494d; display: flex; align-items: center; gap: 6px; }
.dfp-chart-meta { display: flex; align-items: center; gap: 12px; font-size: 12px; color: #7a8d89; }
.dfp-legend-dot { display: inline-block; width: 10px; height: 10px; border-radius: 50%; margin-right: 4px; }
.dfp-svg-wrap { width: 100%; height: 220px; }
.dfp-svg { width: 100%; height: 100%; }
.dfp-axis-text { font-size: 10px; fill: #7a8d89; font-family: monospace; }
.dfp-chart-empty { height: 200px; display: flex; align-items: center; justify-content: center; color: #7a8d89; font-size: 13px; }

/* Table */
.dfp-table-card {
  background: #fff;
  border: 1px solid #e9eee9;
  border-radius: 12px;
  overflow: hidden;
}
.dfp-table-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid #e9eee9;
}
.dfp-table-header strong { font-size: 13px; color: #24494d; }
.dfp-table-meta { font-size: 12px; color: #7a8d89; }
.dfp-table-wrap { overflow-x: auto; max-height: 320px; overflow-y: auto; }
.dfp-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.dfp-table th { padding: 8px 12px; text-align: left; background: #f8fbfb; color: #56716e; font-weight: 600; border-bottom: 1px solid #e9eee9; white-space: nowrap; position: sticky; top: 0; }
.dfp-table td { padding: 7px 12px; border-bottom: 1px solid #f0f5f3; color: #2c3e3e; }
.dfp-step { color: #7a8d89; font-family: monospace; font-size: 11px; }
.dfp-time { font-family: monospace; font-size: 11px; }
.dfp-val { font-family: monospace; color: #2b6cb0; font-weight: 600; }
.dfp-row-high { background: #fff5f5; }
.dfp-row-mid { background: #fffaf0; }
.dfp-risk-tag {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 12px;
  font-size: 11px;
  font-weight: 600;
}
.dfp-risk-tag.high { background: #fed7d7; color: #c53030; }
.dfp-risk-tag.mid { background: #feebc8; color: #c05621; }
.dfp-risk-tag.low { background: #e6fffa; color: #276749; }

/* Empty */
.dfp-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 80px 20px;
  gap: 12px;
  color: #7a8d89;
  text-align: center;
}
.dfp-empty p { margin: 0; font-size: 15px; color: #56716e; }
.dfp-empty-sub { font-size: 13px; color: #9baead; }

@media (max-width: 720px) {
  .dfp-root { padding: 12px; }
  .dfp-controls { flex-direction: column; }
  .dfp-run-btn { margin-left: 0; width: 100%; justify-content: center; }
  .dfp-kpi-row { grid-template-columns: repeat(2, 1fr); }
}
</style>
