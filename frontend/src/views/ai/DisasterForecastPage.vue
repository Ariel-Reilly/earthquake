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
  Bot,
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
// 标题副标题展开态拆成两个独立信号：
//   titleHovered —— 鼠标悬停 / 键盘聚焦带来的"预览"，mouseleave 时清掉
//   titlePinned —— 点击（含 Enter / Space）触发的"固定"，多次点击翻转
// 综合是否展开由 computed titleExpanded 决定，避免 hover 已为 true 时再点
// 一下反而把状态翻成 false、看起来像"点击没展开"。
const titleHovered = ref(false)
const titlePinned = ref(false)
const titleExpanded = computed(() => titleHovered.value || titlePinned.value)

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

// 预测序列的全局统计量（O(n) 单次遍历），避免在模板行内多次 Math.max(...preds) 调用
// 触发 RangeError / 不必要的栈展开。
const predictionStats = computed(() => {
  const preds = forecastResult.value?.predictions
  if (!Array.isArray(preds) || preds.length === 0) {
    return null
  }
  let min = preds[0]
  let max = preds[0]
  for (let i = 1; i < preds.length; i++) {
    const v = preds[i]
    if (v < min) min = v
    if (v > max) max = v
  }
  const range = max - min || 1
  const paddedMin = min - range * 0.15
  const paddedMax = max + range * 0.15
  const paddedRange = paddedMax - paddedMin
  return { min, max, range, paddedMin, paddedMax, paddedRange, peak: max }
})

// 预测序列含置信区间的 SVG 路径
const chartPaths = computed(() => {
  const stats = predictionStats.value
  if (!stats) return null
  const preds = forecastResult.value.predictions
  const n = preds.length

  const W = 600
  const H = 200
  const PAD = { top: 16, right: 16, bottom: 32, left: 48 }
  const cw = W - PAD.left - PAD.right
  const ch = H - PAD.top - PAD.bottom

  const { paddedMin, paddedMax, paddedRange } = stats

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

  // R7：原「历史趋势」曲线是拿预测值前 1/4 重复画一遍，图例却标成「历史」——
  // 属于伪造观测数据。后端 ForecastResult 目前只下发 predictions[]，没有真实历史序列，
  // 因此撤下该曲线与图例；待后端补 history[] 后再恢复，绝不拿预测值冒充历史。
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
    W, H, PAD, cw, ch,
    linePath, bandPath,
    yTicks, yLabels, xLabels,
    bandHalf,
    conf,
  }
})

const riskLevel = computed(() => {
  if (!predictionStats.value) return null
  const maxPred = predictionStats.value.peak
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
  if (maxPred >= t.medium) return { level: 'MEDIUM', label: '中等风险', color: '#cbaa76', icon: '🟡' }
  return { level: 'LOW', label: '低风险', color: '#6f9e7f', icon: '🟢' }
})

// 表格行高亮阈值：使用峰值百分比，避免在 v-for 行内重复 Math.max(...predictions)
const riskHighThreshold = computed(() =>
  predictionStats.value ? predictionStats.value.peak * 0.9 : Infinity,
)
const riskMidThreshold = computed(() =>
  predictionStats.value ? predictionStats.value.peak * 0.75 : Infinity,
)

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

// 标题副标题"固定"态切换：用于点击 / Enter / Space 触发。
// 仅翻转 titlePinned，titleHovered 由 header 上的 mouseenter/leave、focusin/out 维护。
// 鼠标离开时如果用户没有点过（即没有固定），副标题会自动收起；
// 如果点过固定了，则离开后仍保持展开，再点一次才会收起。
function toggleTitle() {
  titlePinned.value = !titlePinned.value
}

onMounted(async () => {
  await loadDevices()
  if (selectedDeviceCode.value) await runForecast()
})
</script>

<template>
  <div class="dfp-root">
    <!-- 标题区：主标题悬停/聚焦时展开副标题与说明。
         容器 header 接管 mouseenter/mouseleave + focusin/focusout，
         主标题按钮自身处理点击 / Enter / Space 切换并维护 aria-expanded。 -->
    <header
      class="dfp-header"
      @mouseenter="titleHovered = true"
      @mouseleave="titleHovered = false"
      @focusin="titleHovered = true"
      @focusout="titleHovered = false"
    >
      <button
        type="button"
        class="dfp-title-btn"
        :aria-expanded="titleExpanded ? 'true' : 'false'"
        aria-controls="dfp-title-panel"
        @click.stop="toggleTitle"
        @keydown.enter.prevent="toggleTitle"
        @keydown.space.prevent="toggleTitle"
      >
        <TrendingUp :size="18" class="dfp-title-icon" />
        <h1 class="dfp-title">灾害预测</h1>
        <ChevronDown :size="14" class="dfp-title-chevron" />
      </button>
      <div
        id="dfp-title-panel"
        class="dfp-title-expand"
        :class="{ open: titleExpanded }"
        role="region"
        :aria-hidden="titleExpanded ? 'false' : 'true'"
      >
        <div class="dfp-title-expand-inner">
          <p class="dfp-subtitle">
            基于 LSTM 时序模型对未来 {{ selectedHorizon }} 小时进行风险预测，
            支持位移、降雨、裂缝等多指标维度。
          </p>
        </div>
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
            <Bot :size="16" class="dfp-icon-brand" />
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
          <div class="dfp-kpi-value dfp-text-brand">
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
            {{ predictionStats ? predictionStats.peak.toFixed(2) : '—' }}
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
            <span class="dfp-legend-dot dfp-legend-dot--brand"></span> 预测
            <span class="dfp-legend-dot dfp-legend-dot--band"></span> 置信区间
          </span>
        </div>
        <div v-if="chartPaths" class="dfp-svg-wrap">
          <svg
            :viewBox="`0 0 ${chartPaths.W} ${chartPaths.H}`"
            preserveAspectRatio="none"
            class="dfp-svg"
          >
            <g class="dfp-grid">
              <line
                v-for="tick in chartPaths.yTicks"
                :key="tick.y"
                :x1="chartPaths.PAD.left"
                :x2="chartPaths.W - chartPaths.PAD.right"
                :y1="tick.y"
                :y2="tick.y"
                stroke="rgba(111,158,127,.12)"
                stroke-width="1"
              />
            </g>

            <path
              :d="chartPaths.bandPath"
              fill="rgba(47,143,82,.12)"
              stroke="none"
            />

            <path
              :d="chartPaths.linePath"
              fill="none"
              stroke="#6f9e7f"
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
              :cy="chartPaths.PAD.top + chartPaths.ch - ((v - predictionStats.paddedMin) / predictionStats.paddedRange) * chartPaths.ch"
              r="3"
              fill="#6f9e7f"
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
                :class="{ 'dfp-row-high': riskLevel?.level === 'HIGH' && v >= riskHighThreshold, 'dfp-row-mid': riskLevel?.level === 'MEDIUM' && v >= riskMidThreshold }"
              >
                <td class="dfp-step">+{{ i + 1 }}h</td>
                <td class="dfp-time">{{ new Date(Date.now() + (i + 1) * 3600000).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) }}</td>
                <td class="dfp-val">{{ v.toFixed(3) }}</td>
                <td>
                  <span
                    v-if="v >= riskHighThreshold"
                    class="dfp-risk-tag high"
                  >高</span>
                  <span
                    v-else-if="v >= riskMidThreshold"
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
      <TrendingUp :size="48" class="dfp-empty-icon" />
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

/* Header: 主标题 + 悬停/聚焦展开的副标题。
   - 节奏：200ms ease-in-out（克制、不戏剧化）
   - 悬停反馈：按钮 translateX + 图标 rotate + 箭头 chevron 翻转
   - 展开区域用 CSS grid 0fr ↔ 1fr 做高度过渡，避免 max-height 限制
   - @media (prefers-reduced-motion: reduce) 关闭所有过渡与变换 */
.dfp-header {
  display: flex;
  flex-direction: column;
  margin-bottom: 20px;
}
.dfp-title-btn {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  background: transparent;
  border: none;
  padding: 6px 10px;
  margin: 0 0 0 -10px; /* 让 hover 高亮背景从文字左侧自然起手 */
  border-radius: var(--r-sm);
  cursor: pointer;
  color: var(--c-text);
  font: inherit;
  align-self: flex-start;
  text-decoration: none;
  transition:
    transform 200ms ease-in-out,
    color 200ms ease-in-out,
    background-color 200ms ease-in-out;
}
.dfp-title-btn:hover {
  background: var(--c-separator);
  color: var(--c-brand);
  transform: translateX(4px);
}
.dfp-title-btn:focus-visible {
  outline: 2px solid var(--c-brand);
  outline-offset: 2px;
  background: var(--c-separator);
  color: var(--c-brand);
  transform: translateX(4px);
}
.dfp-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 18px;
  font-weight: 700;
  color: inherit;
  margin: 0;
}
.dfp-title-icon,
.dfp-title-chevron {
  transition: transform 200ms ease-in-out, color 200ms ease-in-out, opacity 200ms ease-in-out;
}
.dfp-title-btn:hover .dfp-title-icon,
.dfp-title-btn:focus-visible .dfp-title-icon {
  transform: rotate(-12deg);
  color: var(--c-brand);
}
.dfp-title-chevron {
  margin-left: 2px;
  opacity: 0.55;
}
.dfp-title-btn:hover .dfp-title-chevron,
.dfp-title-btn:focus-visible .dfp-title-chevron {
  transform: rotate(180deg);
  opacity: 0.9;
}

/* 可展开区域：用 grid-template-rows 0fr ↔ 1fr 做高度过渡，避开 max-height 估算 */
.dfp-title-expand {
  display: grid;
  grid-template-rows: 0fr;
  opacity: 0;
  margin-top: 0;
  transition:
    grid-template-rows 200ms ease-in-out,
    opacity 200ms ease-in-out,
    margin-top 200ms ease-in-out;
}
.dfp-title-expand-inner {
  overflow: hidden;
  min-height: 0;
}
.dfp-title-expand.open {
  grid-template-rows: 1fr;
  opacity: 1;
  margin-top: 4px;
}
.dfp-subtitle {
  font-size: 13px;
  color: var(--c-text-2);
  margin: 0;
  padding: 2px 0 0;
  line-height: 1.55;
}

@media (prefers-reduced-motion: reduce) {
  .dfp-title-btn,
  .dfp-title-icon,
  .dfp-title-chevron,
  .dfp-title-expand {
    transition: none;
  }
  .dfp-title-btn:hover,
  .dfp-title-btn:focus-visible,
  .dfp-title-btn:hover .dfp-title-icon,
  .dfp-title-btn:focus-visible .dfp-title-icon,
  .dfp-title-btn:hover .dfp-title-chevron,
  .dfp-title-btn:focus-visible .dfp-title-chevron {
    transform: none;
  }
}

/* Controls */
.dfp-controls {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: flex-end;
  background: var(--c-surface);
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-lg);
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
  color: var(--c-text-2);
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: .5px;
}
.dfp-select,
.dfp-select-btn {
  padding: 7px 12px;
  border: 1px solid var(--c-brand-tint);
  border-radius: var(--r-sm);
  background: var(--c-brand-mist);
  font-size: 13px;
  color: var(--c-ink-1);
  cursor: pointer;
  min-width: 140px;
  text-align: left;
}
.dfp-select:focus { outline: none; border-color: var(--c-brand); }
.dfp-select-wrap { position: relative; }
.dfp-select-btn {
  display: flex; justify-content: space-between; align-items: center;
  background: var(--c-surface);
}
.dfp-select-wrap.open .dfp-select-btn,
.dfp-select-btn:hover { border-color: var(--c-brand); }
.dfp-select-dropdown {
  position: absolute;
  top: calc(100% + 4px);
  left: 0;
  right: 0;
  background: var(--c-surface);
  border: 1px solid var(--c-brand-tint);
  border-radius: var(--r-sm);
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
  border-bottom: 1px solid var(--c-brand-mist);
}
.dfp-select-option:hover { background: var(--c-brand-mist); }
.dfp-select-option.active { background: var(--c-brand-mist); }
.dfp-device-name { font-size: 13px; color: var(--c-ink-1); font-weight: 500; }
.dfp-device-code { font-size: 11px; color: var(--c-text-2); }
.dfp-select-empty { padding: 12px; font-size: 12px; color: var(--c-text-2); text-align: center; }
.dfp-horizon-btns { display: flex; gap: 4px; flex-wrap: wrap; }
.dfp-h-btn {
  padding: 6px 10px;
  border: 1px solid var(--c-brand-tint);
  border-radius: var(--r-sm);
  background: var(--c-surface);
  font-size: 12px;
  color: var(--c-ink-1);
  cursor: pointer;
  white-space: nowrap;
}
.dfp-h-btn:hover { border-color: var(--c-brand); }
.dfp-h-btn.active { background: var(--c-brand); color: var(--c-on-brand); border-color: var(--c-brand); }
.dfp-run-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 20px;
  background: var(--c-brand);
  color: var(--c-on-brand);
  border: none;
  border-radius: var(--r-sm);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
  margin-left: auto;
}
.dfp-run-btn:hover:not(:disabled) { background: var(--sys-blue-deep); }
.dfp-run-btn:disabled { opacity: .6; cursor: not-allowed; }
.spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

/* Error */
.dfp-error {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  background: var(--c-danger-bg);
  border: 1px solid var(--c-danger-soft);
  border-radius: var(--r-sm);
  color: var(--c-danger-strong);
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
  background: var(--c-surface);
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-md);
  padding: 14px 16px;
  border-left: 3px solid var(--c-brand);
}
.dfp-kpi-label { font-size: 11px; color: var(--c-text-2); text-transform: uppercase; font-weight: 600; margin-bottom: 4px; }
.dfp-kpi-value {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 16px;
  font-weight: 700;
  color: var(--c-text);
}
.dfp-kpi-sub { font-size: 11px; color: var(--c-text-2); margin-top: 2px; }
.dfp-kpi-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border-radius: var(--r-xl);
  font-size: 11px;
  margin-top: 6px;
}
.dfp-kpi-badge.ok { background: var(--c-brand-mist); color: var(--c-brand-ink); }
.dfp-kpi-badge.warn { background: var(--c-warning-bg); color: var(--c-warning-ink); }

/* Chart */
.dfp-chart-card {
  background: var(--c-surface);
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-lg);
  padding: 16px;
  margin-bottom: 16px;
}
.dfp-chart-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.dfp-chart-header strong { font-size: 13px; color: var(--c-text); display: flex; align-items: center; gap: 6px; }
.dfp-chart-meta { display: flex; align-items: center; gap: 12px; font-size: 12px; color: var(--c-text-2); }
.dfp-legend-dot { display: inline-block; width: 10px; height: 10px; border-radius: 50%; margin-right: 4px; }
.dfp-svg-wrap { width: 100%; height: 220px; }
.dfp-svg { width: 100%; height: 100%; }
.dfp-axis-text { font-size: 10px; fill: var(--c-text-2); font-family: monospace; }
.dfp-chart-empty { height: 200px; display: flex; align-items: center; justify-content: center; color: var(--c-text-2); font-size: 13px; }

/* Table */
.dfp-table-card {
  background: var(--c-surface);
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-lg);
  overflow: hidden;
}
.dfp-table-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--c-brand-soft);
}
.dfp-table-header strong { font-size: 13px; color: var(--c-text); }
.dfp-table-meta { font-size: 12px; color: var(--c-text-2); }
.dfp-table-wrap { overflow-x: auto; max-height: 320px; overflow-y: auto; }
.dfp-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.dfp-table th { padding: 8px 12px; text-align: left; background: var(--c-brand-mist); color: var(--c-brand-deep); font-weight: 600; border-bottom: 1px solid var(--c-brand-soft); white-space: nowrap; position: sticky; top: 0; }
.dfp-table td { padding: 7px 12px; border-bottom: 1px solid var(--c-brand-mist); color: var(--c-ink-1); }
.dfp-step { color: var(--c-text-2); font-family: monospace; font-size: 11px; }
.dfp-time { font-family: monospace; font-size: 11px; }
.dfp-val { font-family: monospace; color: var(--c-brand); font-weight: 600; }
.dfp-row-high { background: var(--c-danger-bg); }
.dfp-row-mid { background: var(--c-warning-bg); }
.dfp-risk-tag {
  display: inline-block;
  padding: 2px 8px;
  border-radius: var(--r-lg);
  font-size: 11px;
  font-weight: 600;
}
.dfp-risk-tag.high { background: var(--c-danger-bg); color: var(--c-danger-strong); }
.dfp-risk-tag.mid { background: var(--c-warning-bg); color: var(--c-warning-ink); }
/* 图表图例/图标语义色（原为模板内联 style，改为 class 以支持暗色主题）*/
.dfp-icon-brand { color: var(--c-brand); }
.dfp-text-brand { color: var(--c-brand); }
.dfp-legend-dot--brand { background: var(--c-brand); }
.dfp-legend-dot--band { background: var(--c-brand-light); }
.dfp-empty-icon { color: var(--c-text-4); }
.dfp-risk-tag.low { background: var(--sys-mint-soft); color: var(--c-brand-ink); }

/* Empty */
.dfp-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 80px 20px;
  gap: 12px;
  color: var(--c-text-2);
  text-align: center;
}
.dfp-empty p { margin: 0; font-size: 15px; color: var(--c-brand-deep); }
.dfp-empty-sub { font-size: 13px; color: var(--c-text-4); }

@media (max-width: 720px) {
  .dfp-root { padding: 12px; }
  .dfp-controls { flex-direction: column; }
  .dfp-run-btn { margin-left: 0; width: 100%; justify-content: center; }
  .dfp-kpi-row { grid-template-columns: repeat(2, 1fr); }
}
</style>
