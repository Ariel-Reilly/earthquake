<script setup>
/**
 * AI 综合研判Tab页面
 * 
 * 抽离自 AiInsightPage.vue，包含：
 * - 多模态融合评估
 * - 特征贡献度分析（SHAP-like）
 * - 发布预警功能
 * - AI反馈机制
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import ConfirmDialog from '../common/ConfirmDialog.vue'
import AiHealthBadge from '../AiHealthBadge.vue'
import { HazardSelector, createDebouncedHazardSelector } from '../../utils/hazardEventBus'
import { safeMax } from '../../utils/safeMinMax'
import { aiApi } from '../../services/aiApi'
import { dashboardApi } from '../../services/dashboardApi'
import { getAuthUser } from '../../stores/auth'
import {
  getInsight,
  mockFusionResult,
} from '../../services/_architecture/domain/multiModalService'
import {
  AlertTriangle,
  Brain,
  CheckCircle2,
  CloudRain,
  Cpu,
  Loader2,
  MapPin,
  RefreshCw,
  ShieldAlert,
  TrendingUp,
  XCircle,
} from 'lucide-vue-next'

const props = defineProps({
  selectedHazardCode: { type: String, required: true },
  selectedHazardInfo: { type: Object, default: null },
})

const emit = defineEmits(['publish-warning', 'notify'])
const confirmDialogRef = ref(null)

// 评估报告数据
const report = ref(null)
const loading = ref(false)
const errorMsg = ref('')

// 多模态融合状态
const fusionResult = ref(null)
const fusionLoading = ref(false)
const fusionExpanded = ref(false)
const fusionAutoRefresh = ref(true)
let fusionTimer = null

// AI反馈
const feedbackSubmitted = ref(null)
const feedbackSending = ref(false)
const feedbackError = ref('')
const modelInfo = ref(null)
const reloading = ref(false)

// 发布预警
const publishing = ref(false)

// 防抖选择器，防止快速切换时的重复加载
const hazardSelector = new HazardSelector()

// 灾害等级常量
const LEVEL_INFO = {
  LOW: { label: '低风险', color: 'teal' },
  MEDIUM: { label: '中等风险', color: 'blue' },
  HIGH: { label: '高风险', color: 'orange' },
  CRITICAL: { label: '极高风险', color: 'red' },
}

const CONCERN_INFO = {
  '稳定': { color: 'teal', icon: '🟢' },
  '关注': { color: 'blue', icon: '🔵' },
  '预警': { color: 'orange', icon: '🟠' },
  '异常严重': { color: 'red', icon: '🔴' },
}

const DISASTER_TYPE_COLOR = {
  '滑坡': 'amber',
  '崩塌': 'rose',
  '泥石流': 'orange',
  '地裂缝': 'indigo',
  '地面沉降': 'teal',
  '塌陷': 'rose',
  '其他': 'gray',
}

const FEATURE_LABELS = {
  rainfall_24h: '24h 降雨',
  rainfall_7d: '7d 累计降雨',
  slope_deg: '坡度',
  soil_grade: '土壤易蚀性',
  vegetation_cover: '植被覆盖',
  distance_to_water_m: '距水体',
  geology_grade: '地质脆弱性',
  elevation_m: '海拔',
  _derived_rain_saturation_index: '降雨饱和指数',
  _derived_rain_intensity_ratio: '骤雨强度',
  _derived_rain_duration_days: '降雨持续天数',
  _derived_foot_erosion_index: '坡脚侵蚀指数',
  _derived_geology_distance_score: '地质×距水耦合',
  _derived_disp_derivative: '位移加速度',
  disp_rate: '位移速率',
  disp_total: '累计位移',
  crack_width: '裂缝宽度',
  tilt: '倾角',
  disp_forecast_risk: '位移预测风险',
}

const RISK_TO_WARNING_LEVEL = {
  CRITICAL: 'red',
  HIGH: 'orange',
  MEDIUM: 'yellow',
  LOW: 'blue',
}

// 计算属性
const overallLevel = computed(() => {
  const lvl = report.value?.overall?.riskLevel
  return LEVEL_INFO[lvl] ?? { label: lvl || '未知', color: 'gray' }
})

const dimensions = computed(() => report.value?.dimensions ?? {})
const geographicDim = computed(() => dimensions.value.geographic ?? {})
const precipDim = computed(() => dimensions.value.precipitation ?? {})
const deviceDim = computed(() => dimensions.value.device ?? {})

const disasterType = computed(() => report.value?.disasterType ?? null)
const disasterTypeBadge = computed(() => {
  const dt = disasterType.value?.disasterType || '其他'
  const color = DISASTER_TYPE_COLOR[dt] || 'gray'
  return { label: dt, color, source: disasterType.value?.source, confidence: disasterType.value?.confidence }
})

const concernBadge = computed(() => {
  const label = report.value?.overall?.concernLabel || '未知'
  return CONCERN_INFO[label] ?? { color: 'gray', icon: '⚪' }
})

const reasoning = computed(() => report.value?.reasoning ?? [])
const suggestedActions = computed(() => report.value?.suggestedActions ?? [])

const featureContributions = computed(() => report.value?.feature_contributions ?? [])
const MAX_CONTRIB = computed(() => {
  const cs = featureContributions.value
  if (!cs.length) return 1
  return safeMax(cs.map((c) => c.contribution))
})

// 方法
function featureLabel(name) {
  return FEATURE_LABELS[name] || name
}

function contribWidth(c) {
  return Math.max(2, (c / Math.max(MAX_CONTRIB.value, 1)) * 100)
}

function contribColor(c) {
  if (c >= 20) return '#dc2626'
  if (c >= 10) return '#f59e0b'
  return '#10b981'
}

function fmtNum(value, digits = 1) {
  if (value == null || Number.isNaN(Number(value))) return '--'
  return Number(value).toFixed(digits)
}

function fmtPct(value) {
  if (value == null || Number.isNaN(Number(value))) return '--'
  return `${(Number(value) * 100).toFixed(0)}%`
}

function fmtTime(iso) {
  if (!iso) return '--'
  try {
    return new Date(iso).toLocaleString('zh-CN', {
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    })
  } catch {
    return '--'
  }
}

async function loadReport() {
  if (!props.selectedHazardCode) return
  loading.value = true
  errorMsg.value = ''
  try {
    report.value = await aiApi.disasterAssessment(props.selectedHazardCode)
  } catch (err) {
    errorMsg.value = err?.message || 'AI 灾害评估失败，请稍后重试'
    report.value = null
  } finally {
    loading.value = false
  }
}

// 防抖watch，防止重复加载
const debouncedLoad = createDebouncedHazardSelector(loadReport, 300)

watch(() => props.selectedHazardCode, debouncedLoad)

async function loadFusionResult() {
  if (!props.selectedHazardCode) return
  fusionLoading.value = true
  try {
    const result = await getInsight(props.selectedHazardCode)
    fusionResult.value = result
  } catch (_e) {
    fusionResult.value = mockFusionResult(props.selectedHazardCode)
  } finally {
    fusionLoading.value = false
  }
}

async function handleRefreshFusion() {
  await loadFusionResult()
}

function startFusionAutoRefresh() {
  if (fusionTimer) clearInterval(fusionTimer)
  fusionTimer = setInterval(() => {
    if (fusionAutoRefresh.value && fusionExpanded.value) {
      loadFusionResult()
    }
  }, 5 * 60 * 1000)
}

function stopFusionAutoRefresh() {
  if (fusionTimer) {
    clearInterval(fusionTimer)
    fusionTimer = null
  }
}

function toggleFusionPanel() {
  fusionExpanded.value = !fusionExpanded.value
  if (fusionExpanded.value) {
    loadFusionResult()
    startFusionAutoRefresh()
  } else {
    stopFusionAutoRefresh()
  }
}

async function loadModelInfo() {
  try {
    modelInfo.value = await aiApi.getModelInfo()
  } catch (_e) {
    modelInfo.value = null
  }
}

async function reloadAiModel() {
  reloading.value = true
  try {
    await aiApi.reloadModel()
    await loadModelInfo()
  } catch (_e) {
    // 忽略错误
  } finally {
    reloading.value = false
  }
}

async function handlePublishWarning() {
  const riskLevel = report.value?.overall?.riskLevel
  if (!props.selectedHazardCode || !props.selectedHazardInfo) {
    emit('notify', '请先选择灾害点并完成 AI 评估')
    return
  }
  if (!riskLevel) {
    emit('notify', '暂无 AI 评估结果，无法发布预警')
    return
  }

  const level = RISK_TO_WARNING_LEVEL[riskLevel] ?? 'blue'
  const ok = await confirmDialogRef.value?.show({
    title: '确认操作',
    message: `按 AI 评估结果（${riskLevel}）为「${props.selectedHazardInfo.name ?? props.selectedHazardCode}」发布预警？`,
    confirmText: '确认',
    cancelText: '取消',
    isDanger: true
  })
  if (!ok) return

  publishing.value = true
  try {
    const hazard = props.selectedHazardInfo
    const dto = await dashboardApi.createWarning({
      title: `${hazard.name ?? props.selectedHazardCode} ${riskLevel} 风险预警`,
      level,
      hazardCode: props.selectedHazardCode,
      hazardName: hazard.name ?? '',
      hazardType: hazard.hazardType ?? hazard.type ?? '',
      region: hazard.region ?? hazard.regionCode ?? '',
      lon: hazard.lon ?? hazard.longitude ?? null,
      lat: hazard.lat ?? hazard.latitude ?? null,
      content: report.value?.summary ?? report.value?.overall?.concernLabel ?? 'AI 综合评估触发的人工预警',
      operator: getAuthUser()?.name ?? 'system',
    })
    emit('notify', `预警已发布：${dto?.title ?? dto?.warningCode ?? '成功'}`)
    emit('publish-warning', dto)
  } catch (e) {
    emit('notify', `发布预警失败：${e.message ?? e}`)
  } finally {
    publishing.value = false
  }
}

async function submitAiFeedback(outcome, occurred) {
  if (!report.value || !props.selectedHazardCode) return
  feedbackSending.value = true
  feedbackError.value = ''
  try {
    await aiApi.submitFeedback({
      hazardCode: props.selectedHazardCode,
      predictedLevel: report.value.overall?.riskLevel || 'LOW',
      predictedScore: report.value.overall?.riskScore || 0,
      predictedAt: report.value.overall?.analyzedAt || new Date().toISOString(),
      outcome,
      occurred,
      confidence: 0.9,
      feedbackBy: 'frontend-user',
      note: '前端值班员反馈',
    })
    feedbackSubmitted.value = occurred
  } catch (err) {
    feedbackError.value = err?.message || '反馈失败'
  } finally {
    feedbackSending.value = false
  }
}

onMounted(() => {
  loadModelInfo()
})

onBeforeUnmount(() => {
  stopFusionAutoRefresh()
})
</script>

<template>
  <div class="comprehensive-tab">
    <!-- 加载状态 -->
    <div v-if="loading" class="loading-state">
      <Loader2 class="spin-icon" />
      <p>正在分析灾害风险...</p>
    </div>

    <!-- 错误状态 -->
    <div v-else-if="errorMsg" class="error-state">
      <AlertTriangle />
      <p>{{ errorMsg }}</p>
    </div>

    <!-- 主体内容 -->
    <div v-else-if="report" class="comprehensive-content">
      <!-- 综合风险卡片 -->
      <div class="overall-card" :class="`level-${overallLevel.color}`">
        <div class="card-header">
          <ShieldAlert />
          <div>
            <h3>综合风险评估</h3>
            <small>{{ fmtTime(report.overall?.analyzedAt) }}</small>
          </div>
          <div class="spacer" />
          <AiHealthBadge v-if="modelInfo" :model-info="modelInfo" />
        </div>
        <div class="card-body">
          <div class="risk-score">{{ fmtNum(report.overall?.riskScore, 1) }}</div>
          <div class="risk-info">
            <div class="risk-label">{{ overallLevel.label }}</div>
            <div class="concern-badge" :class="`level-${concernBadge.color}`">
              {{ concernBadge.icon }} {{ report.overall?.concernLabel }}
            </div>
            <div class="disaster-type" :class="`type-${disasterTypeBadge.color}`">
              {{ disasterTypeBadge.label }}
            </div>
          </div>
          <button
            v-if="!publishing"
            class="publish-btn"
            @click="handlePublishWarning"
          >
            <AlertTriangle size="16" /> 发布预警
          </button>
          <button v-else class="publish-btn disabled" disabled>
            <Loader2 size="16" class="spin-icon" /> 发布中...
          </button>
        </div>
      </div>

      <!-- 三维度评估 -->
      <div class="dimensions-grid">
        <!-- 地理因素 -->
        <div class="dim-card level-teal">
          <header>
            <MapPin size="16" />
            <div>
              <h3>地理因素</h3>
              <small>Geo Score</small>
            </div>
          </header>
          <div class="dim-score">{{ fmtNum(geographicDim.score, 1) }}</div>
          <ul class="dim-meta">
            <li>
              <span>坡度</span> <strong>{{ fmtNum(geographicDim.slope_deg, 1) }}°</strong>
            </li>
            <li>
              <span>海拔</span> <strong>{{ fmtNum(geographicDim.elevation_m, 0) }}m</strong>
            </li>
            <li>
              <span>土壤等级</span> <strong>{{ geographicDim.soil_grade }}</strong>
            </li>
            <li>
              <span>地质等级</span> <strong>{{ geographicDim.geology_grade }}</strong>
            </li>
          </ul>
        </div>

        <!-- 气象因素 -->
        <div class="dim-card level-blue">
          <header>
            <CloudRain size="16" />
            <div>
              <h3>气象因素</h3>
              <small>Precipitation Score</small>
            </div>
          </header>
          <div class="dim-score">{{ fmtNum(precipDim.score, 1) }}</div>
          <ul class="dim-meta">
            <li>
              <span>24h 降雨</span> <strong>{{ fmtNum(precipDim.rainfall_24h, 1) }}mm</strong>
            </li>
            <li>
              <span>7d 累计</span> <strong>{{ fmtNum(precipDim.rainfall_7d, 1) }}mm</strong>
            </li>
            <li>
              <span>强度比</span> <strong>{{ fmtNum(precipDim._derived_rain_intensity_ratio, 2) }}</strong>
            </li>
            <li>
              <span>持续天数</span> <strong>{{ fmtNum(precipDim._derived_rain_duration_days, 0) }}</strong>
            </li>
          </ul>
        </div>

        <!-- 设备告警 -->
        <div class="dim-card level-orange">
          <header>
            <Cpu size="16" />
            <div>
              <h3>设备告警</h3>
              <small>Device Alert Score</small>
            </div>
          </header>
          <div class="dim-score">{{ fmtNum(deviceDim.score, 1) }}</div>
          <ul class="dim-meta">
            <li>
              <span>位移速率</span> <strong>{{ fmtNum(deviceDim.disp_rate, 2) }}mm/d</strong>
            </li>
            <li>
              <span>累计位移</span> <strong>{{ fmtNum(deviceDim.disp_total, 1) }}mm</strong>
            </li>
            <li>
              <span>预测风险</span> <strong>{{ deviceDim.disp_forecast_risk }}</strong>
            </li>
            <li>
              <span>可用性</span> <strong>{{ deviceDim.device_available ? '有' : '无' }}</strong>
            </li>
          </ul>
        </div>
      </div>

      <!-- 特征贡献度 -->
      <div v-if="featureContributions.length" class="contrib-card">
        <header>
          <TrendingUp size="16" />
          <h3>特征贡献度 (SHAP-Like)</h3>
          <small>影响评估结果的主要因素排序</small>
        </header>
        <ul class="contrib-list">
          <li v-for="(contrib, idx) in featureContributions.slice(0, 10)" :key="idx">
            <div class="contrib-name">{{ featureLabel(contrib.feature) }}</div>
            <div class="contrib-bar-wrap">
              <div
                class="contrib-bar"
                :style="{ width: contribWidth(contrib.contribution) + '%', background: contribColor(contrib.contribution) }"
              />
            </div>
            <div class="contrib-value">{{ fmtNum(contrib.contribution, 1) }}</div>
          </li>
        </ul>
      </div>

      <!-- 多模态融合面板 -->
      <div class="fusion-panel" :class="{ expanded: fusionExpanded }">
        <div class="fusion-header" @click="toggleFusionPanel">
          <div class="fusion-title">
            <Brain size="16" />
            多模态融合分析
          </div>
          <div class="fusion-actions">
            <button
              class="fusion-refresh-btn"
              :disabled="fusionLoading"
              @click.stop="handleRefreshFusion"
            >
              <RefreshCw size="14" />
            </button>
            <span class="fusion-toggle">{{ fusionExpanded ? '收起' : '展开' }}</span>
          </div>
        </div>
        <div v-if="fusionExpanded" class="fusion-body">
          <div v-if="fusionLoading" class="fusion-loading">
            <Loader2 class="spin-icon" size="20" />
            加载中...
          </div>
          <template v-else-if="fusionResult">
            <div v-if="fusionResult.explanation" class="fusion-explanation">
              <h4>📖 分析说明</h4>
              <p>{{ fusionResult.explanation }}</p>
            </div>
          </template>
        </div>
      </div>

      <!-- AI反馈 -->
      <div class="feedback-section">
        <h3>AI准确性反馈</h3>
        <div class="feedback-buttons">
          <button
            class="feedback-btn"
            :disabled="feedbackSending"
            @click="submitAiFeedback('correct', true)"
          >
            <CheckCircle2 size="14" /> 准确
          </button>
          <button
            class="feedback-btn feedback-btn-neg"
            :disabled="feedbackSending"
            @click="submitAiFeedback('incorrect', false)"
          >
            <XCircle size="14" /> 不准确
          </button>
          <span v-if="feedbackError" class="feedback-error">{{ feedbackError }}</span>
        </div>
      </div>
    </div>

    <!-- 空状态 -->
    <div v-else class="empty-state">
      <AlertTriangle />
      <p>请选择灾害点查看综合评估</p>
    </div>

    <ConfirmDialog ref="confirmDialogRef" />
  </div>
</template>

<style scoped>
.comprehensive-tab {
  padding: 20px;
}

.loading-state,
.error-state,
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 60px 20px;
  color: var(--c-text-2);
  text-align: center;
}

.spin-icon {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.comprehensive-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.overall-card {
  padding: 16px;
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-lg);
  background: var(--c-surface);
  border-left: 4px solid;
}

.overall-card.level-teal { border-left-color: var(--c-brand); }
.overall-card.level-blue { border-left-color: var(--sys-blue); }
.overall-card.level-orange { border-left-color: var(--c-warning-strong); }
.overall-card.level-red { border-left-color: var(--c-danger-strong); }

.card-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.card-header svg {
  color: var(--c-brand);
  flex-shrink: 0;
}

.card-header h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--c-text);
}

.card-header small {
  font-size: 11px;
  color: var(--c-text-3);
}

.spacer {
  flex: 1;
}

.card-body {
  display: flex;
  align-items: center;
  gap: 16px;
}

.risk-score {
  font-family: 'JetBrains Mono', monospace;
  font-size: 28px;
  font-weight: 700;
  color: var(--c-text);
  flex-shrink: 0;
}

.risk-info {
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex: 1;
}

.risk-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--c-text);
}

.concern-badge,
.disaster-type {
  display: inline-block;
  padding: 4px 8px;
  border-radius: var(--r-sm);
  font-size: 11px;
  font-weight: 500;
  width: fit-content;
}

.concern-badge.level-teal { background: var(--c-brand-light); color: var(--c-brand-hover); }
.concern-badge.level-blue { background: var(--c-info-bg); color: var(--c-text-3); }
.concern-badge.level-orange { background: var(--c-warning-bg); color: var(--c-warning-ink); }
.concern-badge.level-red { background: var(--c-danger-bg); color: var(--c-danger-strong); }

.disaster-type.type-amber { background: #fef3c7; color: #92400e; }
.disaster-type.type-rose { background: #ffe4e6; color: #be123c; }
.disaster-type.type-orange { background: #fed7aa; color: #9a3412; }
.disaster-type.type-indigo { background: #e0e7ff; color: #312e81; }
.disaster-type.type-teal { background: var(--c-brand-light); color: var(--c-brand-hover); }
.disaster-type.type-gray { background: var(--c-separator); color: var(--c-text-3); }

.publish-btn {
  padding: 8px 14px;
  border: 0;
  background: var(--c-brand);
  color: var(--c-on-brand);
  border-radius: var(--r-sm);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 6px;
}

.publish-btn:hover { background: var(--c-brand-hover); }
.publish-btn.disabled { opacity: 0.6; cursor: not-allowed; }

/* 三维度卡片 */
.dimensions-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}

.dim-card {
  padding: 12px;
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-md);
  background: var(--c-surface);
  border-left: 3px solid;
}

.dim-card header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.dim-card header svg {
  color: var(--c-brand);
  flex-shrink: 0;
}

.dim-card h3 {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--c-text);
}

.dim-card small {
  font-size: 10px;
  color: var(--c-text-3);
}

.dim-score {
  font-family: 'JetBrains Mono', monospace;
  font-size: 18px;
  font-weight: 700;
  color: var(--c-text);
  margin-bottom: 8px;
}

.dim-meta {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  grid-template-columns: 1fr;
  gap: 4px;
  font-size: 11px;
}

.dim-meta li {
  display: flex;
  justify-content: space-between;
  color: var(--c-text-2);
}

.dim-meta strong {
  color: var(--c-text);
  font-weight: 600;
}

/* 特征贡献度卡片 */
.contrib-card {
  padding: 14px;
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-lg);
  background: var(--c-surface);
}

.contrib-card header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.contrib-card header svg {
  color: var(--c-brand);
}

.contrib-card h3 {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--c-text);
}

.contrib-card small {
  margin-left: auto;
  font-size: 11px;
  color: var(--c-text-3);
}

.contrib-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.contrib-list li {
  display: grid;
  grid-template-columns: 120px 1fr 50px;
  align-items: center;
  gap: 10px;
}

.contrib-name {
  font-size: 12px;
  color: var(--c-text);
  text-align: right;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.contrib-bar-wrap {
  height: 8px;
  background: var(--c-surface-2);
  border-radius: var(--r-xs);
  overflow: hidden;
}

.contrib-bar {
  height: 100%;
  transition: width 0.3s;
}

.contrib-value {
  font-family: 'JetBrains Mono', monospace;
  font-size: 11px;
  font-weight: 600;
  color: var(--c-text);
  text-align: right;
}

/* 多模态融合面板 */
.fusion-panel {
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-lg);
  background: var(--c-surface);
  overflow: hidden;
}

.fusion-header {
  padding: 12px 14px;
  cursor: pointer;
  background: linear-gradient(135deg, var(--c-brand-mist) 0%, var(--c-surface) 60%);
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.fusion-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  font-size: 13px;
  color: var(--c-text);
}

.fusion-title svg {
  color: var(--c-brand);
}

.fusion-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.fusion-refresh-btn {
  padding: 4px;
  border: 1px solid var(--c-separator);
  background: var(--c-surface);
  border-radius: var(--r-xs);
  cursor: pointer;
  color: var(--c-brand-deep);
  display: flex;
  align-items: center;
  justify-content: center;
}

.fusion-refresh-btn:hover {
  background: var(--c-bg);
}

.fusion-refresh-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.fusion-toggle {
  font-size: 11px;
  color: var(--c-text-3);
}

.fusion-body {
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.fusion-loading {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 20px;
  justify-content: center;
  color: var(--c-text-2);
  font-size: 13px;
}

.fusion-explanation {
  padding: 10px 12px;
  background: var(--c-surface-2);
  border-radius: var(--r-sm);
  border: 1px solid var(--c-brand-soft);
}

.fusion-explanation h4 {
  margin: 0 0 8px;
  font-size: 12px;
  font-weight: 600;
  color: var(--c-text);
}

.fusion-explanation p {
  margin: 0;
  font-size: 12px;
  color: var(--c-text-2);
  line-height: 1.5;
}

/* AI反馈部分 */
.feedback-section {
  padding: 12px;
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-md);
  background: var(--c-surface);
}

.feedback-section h3 {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--c-text);
}

.feedback-buttons {
  display: flex;
  gap: 8px;
  align-items: center;
}

.feedback-btn {
  padding: 6px 12px;
  border: 1px solid var(--c-border);
  background: var(--c-surface);
  border-radius: var(--r-sm);
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 4px;
  color: var(--c-text);
  transition: all 0.2s;
}

.feedback-btn:hover:not(:disabled) {
  background: var(--c-surface-2);
}

.feedback-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.feedback-btn.submitted {
  background: var(--c-success-bg);
  color: var(--c-success-strong);
  border-color: var(--c-success);
}

.feedback-btn-neg.submitted {
  background: var(--c-warning-bg);
  color: var(--c-warning-ink);
  border-color: var(--c-warning);
}

.feedback-error {
  font-size: 11px;
  color: var(--c-danger-strong);
  margin-left: auto;
}

@media (max-width: 1024px) {
  .dimensions-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 768px) {
  .dimensions-grid {
    grid-template-columns: 1fr;
  }
}
</style>
