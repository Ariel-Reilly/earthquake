<script setup>
/**
 * AI 灾害评估页面（/dashboard/ai-insight）。
 *
 * v1.3.0 起：
 *   - 数据基础是「当地地理状况 + 当地天气状况 + 可选设备位移」，AI 输出三件套：
 *     1) risk_level       — LOW/MEDIUM/HIGH/CRITICAL
 *     2) concern_label    — 中文「稳定/关注/预警/异常严重」
 *     3) disaster_type    — 滑坡/崩塌/泥石流/地裂缝/地面沉降/塌陷
 *   - 不强制依赖监测设备：无设备时 Python 自动按 geographic+precipitation 测算并标 device_available=false；
 *   - AI 独立测算入口：`aiApi.disasterAssessment()` 单 hazard 调用，立即返回完整评估。
 *
 * 数据流：前端 → GET /api/ai/disaster-assessment?hazardCode=xxx →
 *   Java 端 AiController.disasterAssessment()（聚合 hazard + 因子）→
 *   Python ai-service /api/ai/disaster-assessment/score → 返回三件套 + 三维度明细
 */
import { computed, onMounted, ref } from 'vue'
import {
  AlertTriangle,
  Brain,
  Calendar,
  Camera,
  CheckCircle2,
  ChevronRight,
  CloudRain,
  Cpu,
  Layers,
  Loader2,
  MapPin,
  MessageSquare,
  MountainSnow,
  Radio,
  RefreshCcw,
  RefreshCw,
  ShieldAlert,
  Target,
  TrendingUp,
  XCircle,
} from 'lucide-vue-next'
import { aiApi } from '../../services/aiApi'
import { dashboardApi } from '../../services/dashboardApi'
import { safeMax } from '../../utils/safeMinMax'
import AiHealthBadge from '../../components/AiHealthBadge.vue'
import {
  getInsight,
  refreshInsight,
  mockFusionResult,
  getRiskLevelInfo,
  MODAL_TYPES,
} from '../../services/multiModalService'

const props = defineProps({
  currentTime: { type: String, default: '' },
  userRole: { type: String, default: 'admin' },
})

const hazards = ref([])
const selectedCode = ref('')
const report = ref(null)
const loading = ref(false)
const errorMsg = ref('')
const batchLoading = ref(false)

// ==================== 多模态融合状态 ====================
const fusionResult = ref(null)
const fusionLoading = ref(false)
const fusionExpanded = ref(false)
const fusionAutoRefresh = ref(true)
let fusionTimer = null

async function loadFusionResult() {
  if (!selectedCode.value) return
  fusionLoading.value = true
  try {
    const result = await getInsight(selectedCode.value)
    fusionResult.value = result
  } catch (_e) {
    // 使用模拟数据
    fusionResult.value = mockFusionResult(selectedCode.value)
  } finally {
    fusionLoading.value = false
  }
}

async function handleRefreshFusion() {
  await loadFusionResult()
}

async function handlePublishWarning() {
  emit('notify', '正在创建预警...')
  // TODO: 调用预警创建接口
}

function startFusionAutoRefresh() {
  if (fusionTimer) clearInterval(fusionTimer)
  fusionTimer = setInterval(() => {
    if (fusionAutoRefresh.value && fusionExpanded.value) {
      loadFusionResult()
    }
  }, 5 * 60 * 1000) // 5分钟
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

const LEVEL_INFO = {
  LOW: { label: '低风险', color: 'teal' },
  MEDIUM: { label: '中等风险', color: 'blue' },
  HIGH: { label: '高风险', color: 'orange' },
  CRITICAL: { label: '极高风险', color: 'red' },
}

// 关注度（中文业务标签）→ 视觉色
const CONCERN_INFO = {
  '稳定': { color: 'teal', icon: '🟢' },
  '关注': { color: 'blue', icon: '🔵' },
  '预警': { color: 'orange', icon: '🟠' },
  '异常严重': { color: 'red', icon: '🔴' },
}

// 灾害类型徽标颜色（按类别区分视觉）
const DISASTER_TYPE_COLOR = {
  '滑坡': 'amber',
  '崩塌': 'rose',
  '泥石流': 'orange',
  '地裂缝': 'indigo',
  '地面沉降': 'teal',
  '塌陷': 'rose',
  '其他': 'gray',
}

const selectedHazard = computed(() =>
  hazards.value.find((h) => h.hazardCode === selectedCode.value) ?? hazards.value[0],
)

async function loadHazards() {
  try {
    const resp = await dashboardApi.getHazards({ pageSize: 200 })
    hazards.value = resp.list ?? []
    if (hazards.value.length > 0 && !selectedCode.value) {
      selectedCode.value = hazards.value[0].hazardCode
    }
  } catch (e) {
    errorMsg.value = '灾害点加载失败：' + (e?.message || '后端不可达')
    hazards.value = []
  }
}

async function loadReport() {
  if (!selectedCode.value) return
  loading.value = true
  errorMsg.value = ''
  try {
    // v1.3.0 改为调 disaster-assessment 端点（独立测算 + 灾害类型）
    report.value = await aiApi.disasterAssessment(selectedCode.value)
  } catch (err) {
    errorMsg.value = err?.message || 'AI 灾害评估失败，请稍后重试'
    report.value = null
  } finally {
    loading.value = false
  }
}

async function loadAllReports() {
  // 对所有灾害点串行触发一次"预热"，让每个点都拿到一份最新的独立测算
  batchLoading.value = true
  try {
    for (const h of hazards.value) {
      selectedCode.value = h.hazardCode
      await loadReport()
    }
  } finally {
    batchLoading.value = false
  }
}

function selectHazard(code) {
  selectedCode.value = code
  loadReport()
}

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

// v1.4.0：SHAP-like 特征贡献度
const featureContributions = computed(() => report.value?.feature_contributions ?? [])
const MAX_CONTRIB = computed(() => {
  const cs = featureContributions.value
  if (!cs.length) return 1
  // ⚠️ 不写 Math.max(...cs.map(...))：特征贡献列表长度无上限。
  // 用 safeMax，O(1) 栈深度。
  return safeMax(cs.map((c) => c.contribution))
})
function contribWidth(c) {
  return Math.max(2, (c / Math.max(MAX_CONTRIB.value, 1)) * 100)
}
function contribColor(c) {
  if (c >= 20) return '#dc2626'  // 红：高分
  if (c >= 10) return '#f59e0b'  // 橙：中分
  return '#10b981'  // 绿：低分
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
function featureLabel(name) {
  return FEATURE_LABELS[name] || name
}

// v1.4.0：AI 反馈状态
const feedbackSubmitted = ref(null)  // null=未反馈, true=实际发生, false=实际未发生
const feedbackSending = ref(false)
const feedbackError = ref('')

// v1.5.0：XGBoost 真实模型状态
const modelInfo = ref(null)
const reloading = ref(false)
async function loadModelInfo() {
  try {
    modelInfo.value = await aiApi.getModelInfo()
  } catch (_e) {
    // 静默失败：徽标区域降级为不显示
    modelInfo.value = null
  }
}
async function reloadAiModel() {
  reloading.value = true
  try {
    await aiApi.reloadModel()
    await loadModelInfo()
  } catch (_e) {
    // 失败忽略
  } finally {
    reloading.value = false
  }
}
function formatModelTime(iso) {
  if (!iso) return ''
  try {
    const d = new Date(iso)
    if (Number.isNaN(d.getTime())) return ''
    const yyyy = d.getFullYear()
    const mm = String(d.getMonth() + 1).padStart(2, '0')
    const dd = String(d.getDate()).padStart(2, '0')
    const hh = String(d.getHours()).padStart(2, '0')
    const mi = String(d.getMinutes()).padStart(2, '0')
    return `${yyyy}-${mm}-${dd} ${hh}:${mi}`
  } catch {
    return ''
  }
}
async function submitAiFeedback(outcome, occurred) {
  if (!report.value || !selectedCode.value) return
  feedbackSending.value = true
  feedbackError.value = ''
  try {
    await aiApi.submitFeedback({
      hazardCode: selectedCode.value,
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
  } catch (_) {
    return iso
  }
}

onMounted(async () => {
  await loadHazards()
  if (selectedCode.value) {
    loadReport()
    loadFusionResult()
  }
  loadModelInfo()
  startFusionAutoRefresh()
})
</script>

<template>
  <div class="ai-insight-page">
    <!-- 顶部：标题 + 批量刷新 -->
    <div class="page-head">
      <div class="page-head-info">
      </div>
      <div class="page-head-actions">
        <AiHealthBadge />
        <button class="refresh-btn" :disabled="batchLoading" @click="loadAllReports">
          <Loader2 v-if="batchLoading" :size="14" class="spin" />
          <RefreshCcw v-else :size="14" />
          {{ batchLoading ? '正在逐点分析…' : '一键分析全部灾害点' }}
        </button>
      </div>
    </div>

    <div class="ai-layout">
      <!-- 左：灾害点列表 -->
      <aside class="hazard-list-panel">
        <div class="panel-header">
          <div>
            <h2>灾害点清单</h2>
            <p>共 {{ hazards.length }} 个监测点</p>
          </div>
        </div>
        <div class="hazard-list">
          <button
            v-for="h in hazards"
            :key="h.id"
            class="hazard-row"
            :class="{ active: h.hazardCode === selectedCode }"
            @click="selectHazard(h.hazardCode)"
          >
            <div class="hazard-row-main">
              <strong>{{ h.name }}</strong>
              <span class="hazard-type">{{ h.type }} · {{ h.level }}</span>
              <small>
                <MapPin :size="11" />{{ h.owner }} ·
                <span class="hazard-status">{{ h.status }}</span>
              </small>
            </div>
            <ChevronRight :size="14" class="hazard-row-arrow" />
          </button>
        </div>
      </aside>

      <!-- 右：分析报告 -->
      <section class="report-panel">
        <div class="panel-header">
          <div>
            <h2>{{ selectedHazard?.name || '请选择灾害点' }}</h2>
            <p>
              编码 <code>{{ selectedCode }}</code>
              <span v-if="report?.analyzedAt"> · 分析时间 {{ fmtTime(report.analyzedAt) }}</span>
            </p>
          </div>
          <button class="refresh-btn small" :disabled="loading" @click="loadReport">
            <Loader2 v-if="loading" :size="13" class="spin" />
            <RefreshCcw v-else :size="13" />
            {{ loading ? '分析中…' : '重新分析' }}
          </button>
        </div>

        <div v-if="errorMsg" class="report-error">
          <XCircle :size="16" />{{ errorMsg }}
        </div>

        <div v-if="loading && !report" class="report-loading">
          <Loader2 :size="32" class="spin" />
          <span>正在聚合三维度因子并调用 AI 推理…</span>
        </div>

        <template v-else-if="report">
          <!-- 综合评分卡：左侧分数，右侧"灾害类型" + "关注度"双标签 + 模型 + 置信度 + 设备维度徽标 -->
          <div class="overall-card" :class="`level-${overallLevel.color}`">
            <div class="overall-card-score">
              <strong>{{ fmtNum(report.overall.riskScore, 1) }}</strong>
              <small>/ 100</small>
            </div>
            <div class="overall-card-meta">
              <!-- v1.3.0 三件套：风险等级 + 关注度 + 灾害类型 -->
              <div class="overall-tag-row">
                <span class="overall-level-badge">{{ overallLevel.label }}</span>
                <span class="concern-badge" :class="`level-${concernBadge.color}`">
                  <span class="concern-icon">{{ concernBadge.icon }}</span>
                  关注度：<strong>{{ report.overall.concernLabel || '未知' }}</strong>
                </span>
                <span class="disaster-type-badge" :class="`type-${disasterTypeBadge.color}`">
                  <Layers :size="12" />
                  {{ disasterTypeBadge.label }}
                  <em class="type-source">{{ disasterTypeBadge.source === 'declared' ? '已标注' : (disasterTypeBadge.source === 'inferred' ? 'AI 推断' : '兜底') }}</em>
                </span>
              </div>
              <p class="overall-model">
                模型 <code>{{ report.overall.model }}</code>
                <span v-if="report.degraded" class="mock-tag">降级</span>
                <!-- v1.5.0：真实模型/启发式徽标 -->
                <span v-if="modelInfo" class="model-badge" :class="modelInfo.model_loaded ? 'model-real' : 'model-heuristic'">
                  <Cpu :size="11" />
                  {{ modelInfo.model_loaded ? '真实模型' : '启发式' }}
                  <em v-if="modelInfo.version" class="model-version">v{{ modelInfo.version }}</em>
                </span>
                <span v-if="modelInfo?.trained_at" class="model-trained" :title="`训练时间: ${modelInfo.trained_at}`">
                  <Calendar :size="11" />
                  {{ formatModelTime(modelInfo.trained_at) }}
                </span>
                <button v-if="modelInfo?.model_loaded" class="model-reload-btn" @click="reloadAiModel" :disabled="reloading">
                  <RefreshCw :size="10" :class="{ spin: reloading }" />
                  {{ reloading ? '重载中' : '重载' }}
                </button>
                <span v-if="report.deviceAvailable === false" class="no-device-tag" title="本次评估完全依据地理 + 天气因子，未纳入设备指标">
                  <Radio :size="11" /> 无设备
                </span>
                <span v-else-if="report.deviceAvailable" class="device-ok-tag" title="本次评估同时纳入了设备位移 / 裂缝 / 地震等设备维度指标">
                  <Radio :size="11" /> 含设备
                </span>
              </p>
              <p class="overall-confidence">
                置信度 <strong>{{ fmtPct(report.overall.confidence) }}</strong>
                <span v-if="disasterTypeBadge.confidence != null" class="dt-confidence">
                  · 灾害类型置信 <strong>{{ fmtPct(disasterTypeBadge.confidence) }}</strong>
                </span>
              </p>
            </div>
            <div class="overall-card-icon">
              <ShieldAlert :size="36" />
            </div>
          </div>

          <!-- ===== 多模态融合面板 v2.0 ===== -->
          <div class="fusion-panel" :class="{ expanded: fusionExpanded }">
            <div class="fusion-header" @click="toggleFusionPanel">
              <div class="fusion-title">
                <Layers :size="16" />
                <span>多模态融合研判</span>
                <span v-if="fusionResult" class="fusion-score" :style="{ color: fusionResult.levelColor || '#e0a050' }">
                  {{ fusionResult.fusedScore || '—' }} / 100
                </span>
                <span v-if="fusionResult" class="fusion-level-badge" :class="`level-${getRiskLevelInfo(fusionResult.level)?.class || 'blue'}`">
                  {{ getRiskLevelInfo(fusionResult.level)?.label || fusionResult.level || '未知' }}
                </span>
              </div>
              <div class="fusion-actions">
                <button v-if="fusionExpanded" class="fusion-refresh-btn" @click.stop="handleRefreshFusion" :disabled="fusionLoading">
                  <Loader2 v-if="fusionLoading" :size="14" class="spin" />
                  <RefreshCw v-else :size="14" />
                </button>
                <span class="fusion-toggle">{{ fusionExpanded ? '收起' : '展开' }}</span>
              </div>
            </div>

            <div v-if="fusionExpanded && fusionResult" class="fusion-body">
              <!-- 贡献度图表 -->
              <div class="fusion-contributions">
                <h4>各模态贡献度</h4>
                <div class="contribution-bars">
                  <div
                    v-for="(contrib, modal) in fusionResult.contributions || {}"
                    :key="modal"
                    class="contribution-item"
                  >
                    <div class="contribution-label">
                      <Camera v-if="modal === 'satellite'" :size="14" />
                      <TrendingUp v-else-if="modal === 'forecast'" :size="14" />
                      <MessageSquare v-else-if="modal === 'social_media'" :size="14" />
                      <Radio v-else :size="14" />
                      <span>{{ MODAL_TYPES[modal?.toUpperCase()]?.label || modal }}</span>
                    </div>
                    <div class="contribution-bar-wrap">
                      <div
                        class="contribution-bar"
                        :style="{
                          width: (contrib * 100) + '%',
                          background: MODAL_TYPES[modal?.toUpperCase()]?.color || '#7a8d89'
                        }"
                      ></div>
                    </div>
                    <span class="contribution-value">{{ Math.round(contrib * 100) }}%</span>
                  </div>
                </div>
              </div>

              <!-- 融合解释 -->
              <div class="fusion-explanation">
                <h4>融合解释</h4>
                <p>{{ fusionResult.explanation || '暂无融合解释' }}</p>
              </div>

              <!-- 各模态详情 -->
              <div class="fusion-modal-details">
                <h4>模态详情</h4>
                <div class="modal-detail-grid">
                  <div
                    v-for="(detail, modal) in fusionResult.modalDetails || {}"
                    :key="modal"
                    class="modal-detail-card"
                    :style="{ borderColor: MODAL_TYPES[modal?.toUpperCase()]?.color || '#e2ebe8' }"
                  >
                    <div class="modal-detail-header">
                      <Camera v-if="modal === 'satellite'" :size="14" />
                      <TrendingUp v-else-if="modal === 'forecast'" :size="14" />
                      <MessageSquare v-else-if="modal === 'social_media'" :size="14" />
                      <Radio v-else :size="14" />
                      <strong>{{ MODAL_TYPES[modal?.toUpperCase()]?.label || modal }}</strong>
                      <span class="modal-score">{{ detail.score || '—' }}</span>
                    </div>
                    <p class="modal-detail-desc">{{ detail.desc || '暂无详情' }}</p>
                    <div class="modal-confidence">
                      <span>置信度</span>
                      <strong>{{ Math.round((detail.confidence || 0) * 100) }}%</strong>
                    </div>
                  </div>
                </div>
              </div>

              <!-- 操作按钮 -->
              <div class="fusion-ops">
                <button class="primary-btn" @click="handlePublishWarning">
                  <AlertTriangle :size="14" />
                  一键下发预警
                </button>
                <button class="ghost-btn" @click="() => {}">
                  转人工确认
                </button>
                <button class="ghost-btn" @click="() => {}">
                  查看详情
                </button>
              </div>
            </div>

            <div v-else-if="fusionLoading" class="fusion-loading">
              <Loader2 :size="20" class="spin" />
              <span>加载融合数据中...</span>
            </div>
          </div>

          <!-- 三维度明细：v1.3.0 起为「当地地理 + 当天降水 + 设备」三块 -->
          <div class="dimensions-grid">
            <!-- 当地地理情况 -->
            <article class="dim-card" :class="`level-${LEVEL_INFO[geographicDim.level]?.color || 'gray'}`">
              <header>
                <MountainSnow :size="18" />
                <div>
                  <h3>当地地理情况</h3>
                  <small>地形 · 土壤 · 植被 · 水文 · 地质</small>
                </div>
                <span class="dim-score">{{ fmtNum(geographicDim.score, 1) }}</span>
              </header>
              <p class="dim-details">{{ geographicDim.details }}</p>
              <div class="geo-grid">
                <div><span>海拔</span><strong>{{ fmtNum(geographicDim.elevation_m, 0) }} m</strong></div>
                <div><span>坡度</span><strong>{{ fmtNum(geographicDim.slope_deg, 1) }}°</strong></div>
                <div><span>土壤易蚀性</span><strong>{{ fmtNum(geographicDim.soil_grade, 1) }} / 5</strong></div>
                <div><span>植被覆盖</span><strong>{{ fmtPct(geographicDim.vegetation_cover) }}</strong></div>
              </div>
            </article>

            <!-- 当天降水 -->
            <article class="dim-card" :class="`level-${LEVEL_INFO[precipDim.level]?.color || 'gray'}`">
              <header>
                <CloudRain :size="18" />
                <div>
                  <h3>当天降水</h3>
                  <small>24h / 7d 累计降雨</small>
                </div>
                <span class="dim-score">{{ fmtNum(precipDim.score, 1) }}</span>
              </header>
              <p class="dim-details">{{ precipDim.details }}</p>
              <ul class="dim-meta">
                <li><span>24h</span><strong>{{ fmtNum(precipDim.rainfall_24h, 1) }} mm</strong></li>
                <li><span>7d</span><strong>{{ fmtNum(precipDim.rainfall_7d, 1) }} mm</strong></li>
                <li><span>趋势</span><strong>{{ precipDim.trend || '--' }}</strong></li>
                <li><span>距水体</span><strong>{{ fmtNum(geographicDim.distance_to_water_m, 0) }} m</strong></li>
              </ul>
            </article>

            <!-- 设备维度（可选；无设备时显示为降级提示） -->
            <article v-if="deviceDim && (deviceDim.available === false || deviceDim.available === true)" class="dim-card" :class="`level-${deviceDim.score >= 60 ? 'orange' : deviceDim.score >= 35 ? 'blue' : 'teal'}`">
              <header>
                <Target :size="18" />
                <div>
                  <h3>设备指标</h3>
                  <small>{{ deviceDim.available ? '位移 / 裂缝 / 地震指标' : '未接入监测设备' }}</small>
                </div>
                <span class="dim-score">{{ deviceDim.available ? fmtNum(deviceDim.score, 1) : '--' }}</span>
              </header>
              <p class="dim-details">
                <template v-if="deviceDim.available">
                  设备维度由加权融合（沿用 fusion-risk 因子）。置信度 {{ fmtPct(deviceDim.confidence) }}
                </template>
                <template v-else>
                  本次评估完全依据"当地地理状况 + 当天降水"，未纳入设备指标。建议尽快对接现场监测设备以提升研判精度。
                </template>
              </p>
            </article>
          </div>

          <!-- 灾害类型分类理由 -->
          <div v-if="disasterType && disasterType.reason" class="dt-reason-card">
            <header>
              <Layers :size="16" />
              <h3>灾害类型分类依据</h3>
              <span class="dt-label">{{ disasterTypeBadge.label }}</span>
            </header>
            <p>{{ disasterType.reason }}</p>
          </div>

          <!-- 推理 + 建议 + SHAP-like 特征贡献度 -->
          <div class="reasoning-grid">
            <article class="reasoning-card">
              <header>
                <AlertTriangle :size="16" />
                <h3>风险归因</h3>
              </header>
              <ul>
                <li v-for="(r, i) in reasoning" :key="`r-${i}`">{{ r }}</li>
              </ul>
            </article>

            <article class="action-card">
              <header>
                <CheckCircle2 :size="16" />
                <h3>建议措施</h3>
              </header>
              <ol>
                <li v-for="(a, i) in suggestedActions" :key="`a-${i}`">{{ a }}</li>
              </ol>
            </article>

            <!-- v1.4.0: 特征贡献度条形图（SHAP-like） -->
            <article class="contrib-card" v-if="featureContributions.length">
              <header>
                <ShieldAlert :size="16" />
                <h3>特征贡献度</h3>
                <small>SHAP-like · 影响总分最大的因子</small>
              </header>
              <ul class="contrib-list">
                <li v-for="c in featureContributions.slice(0, 8)" :key="c.feature">
                  <span class="contrib-name" :title="featureLabel(c.feature)">{{ featureLabel(c.feature) }}</span>
                  <div class="contrib-bar-wrap">
                    <div
                      class="contrib-bar"
                      :style="{ width: contribWidth(c.contribution) + '%', background: contribColor(c.contribution) }"
                    ></div>
                  </div>
                  <span class="contrib-value">+{{ c.contribution.toFixed(1) }}</span>
                </li>
              </ul>
              <!-- AI 反馈按钮（值班员上报实际事件） -->
              <footer class="contrib-footer">
                <button
                  class="feedback-btn"
                  :class="{ submitted: feedbackSubmitted }"
                  :disabled="feedbackSubmitted || feedbackSending"
                  @click="submitAiFeedback('true_positive', true)"
                >
                  {{ feedbackSubmitted ? '已反馈：实际发生' : '实际发生了' }}
                </button>
                <button
                  class="feedback-btn feedback-btn-neg"
                  :class="{ submitted: feedbackSubmitted === false }"
                  :disabled="feedbackSubmitted === false || feedbackSending"
                  @click="submitAiFeedback('false_positive', false)"
                >
                  {{ feedbackSubmitted === false ? '已反馈：未发生' : '实际未发生' }}
                </button>
                <span v-if="feedbackError" class="feedback-error">{{ feedbackError }}</span>
              </footer>
            </article>
          </div>
        </template>

        <div v-else class="report-empty">
          请选择左侧灾害点，或点击「重新分析」获取报告。
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.ai-insight-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
  height: 100%;
}

.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 20px;
  background: #ffffff;
  border: 1px solid #e2ebe8;
  border-radius: 12px;
  box-shadow: 0 1px 2px rgba(36, 73, 77, 0.04);
}
.page-head-info h1 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 4px;
  font-size: 17px;
  color: #24494d;
  letter-spacing: -0.01em;
}
.page-head-info .head-icon {
  color: #2f7f80;
}
.page-head-info p {
  margin: 0;
  color: #6f807d;
  font-size: 12px;
  line-height: 1.5;
}
.refresh-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  border: 1px solid #cfe3df;
  background: #f1f8f6;
  color: #246b6c;
  border-radius: 8px;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.15s ease;
}
.refresh-btn:hover {
  background: #e8f5f1;
}
.refresh-btn[disabled] {
  opacity: 0.55;
  cursor: wait;
}
.refresh-btn.small {
  padding: 6px 10px;
  font-size: 11px;
}
.spin {
  animation: ai-spin 0.9s linear infinite;
}
@keyframes ai-spin {
  to { transform: rotate(360deg); }
}

.ai-layout {
  display: grid;
  grid-template-columns: 280px 1fr;
  gap: 16px;
  flex: 1;
  min-height: 0;
}

.hazard-list-panel,
.report-panel {
  background: #ffffff;
  border: 1px solid #e2ebe8;
  border-radius: 12px;
  box-shadow: 0 1px 2px rgba(36, 73, 77, 0.04);
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.hazard-list-panel {
  overflow: hidden;
}

.report-panel {
  overflow: hidden;
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 18px;
  border-bottom: 1px solid #f0f5f3;
  flex-shrink: 0;
}
.panel-header h2 {
  margin: 0;
  font-size: 14px;
  color: #24494d;
}
.panel-header p {
  margin: 4px 0 0;
  font-size: 11px;
  color: #99a7a1;
}
.panel-header code {
  background: #f4f5f3;
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 10px;
  color: #4a5e5b;
  font-family: 'JetBrains Mono', monospace;
}

.hazard-list {
  overflow-y: auto;
  padding: 8px;
  flex: 1;
  min-height: 0;
}
.hazard-row {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 10px 12px;
  background: transparent;
  border: 1px solid transparent;
  border-radius: 8px;
  text-align: left;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease;
  margin-bottom: 4px;
}
.hazard-row:hover {
  background: #f6faf9;
}
.hazard-row.active {
  background: #e8f5f1;
  border-color: #b6d8d2;
}
.hazard-row-main {
  display: flex;
  flex-direction: column;
  gap: 3px;
  flex: 1;
  min-width: 0;
}
.hazard-row-main strong {
  font-size: 12px;
  color: #24494d;
}
.hazard-type {
  font-size: 10px;
  color: #6f807d;
}
.hazard-row-main small {
  display: flex;
  align-items: center;
  gap: 3px;
  font-size: 10px;
  color: #99a7a1;
}
.hazard-status {
  color: #2f7f80;
  font-weight: 500;
}
.hazard-row-arrow {
  color: #b6c2be;
  flex-shrink: 0;
}
.hazard-row.active .hazard-row-arrow {
  color: #2f7f80;
}

.report-panel {
  overflow-y: auto;
}

.report-error {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 16px 18px;
  padding: 10px 14px;
  background: #fbecec;
  color: #9c3d3d;
  border: 1px solid #ecc4c4;
  border-radius: 8px;
  font-size: 12px;
}
.report-loading,
.report-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 60px 20px;
  color: #99a7a1;
  font-size: 13px;
}

.overall-card {
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 20px;
  margin: 16px 18px 0;
  padding: 20px 24px;
  border-radius: 12px;
  border: 1px solid;
  background: #ffffff;
}
.overall-card.level-teal { border-color: #b6d8d2; background: linear-gradient(135deg, #e8f5f1 0%, #ffffff 80%); }
.overall-card.level-blue { border-color: #c2d6e8; background: linear-gradient(135deg, #ebf3fa 0%, #ffffff 80%); }
.overall-card.level-orange { border-color: #ecd6b6; background: linear-gradient(135deg, #fbf1e3 0%, #ffffff 80%); }
.overall-card.level-red { border-color: #ecc4c4; background: linear-gradient(135deg, #fbecec 0%, #ffffff 80%); }
.overall-card.level-gray { border-color: #e2ebe8; background: #f6faf9; }

.overall-card-score {
  display: flex;
  align-items: baseline;
  gap: 4px;
  font-family: 'JetBrains Mono', monospace;
}
.overall-card-score strong {
  font-size: 42px;
  font-weight: 700;
  color: #24494d;
  letter-spacing: -0.02em;
}
.overall-card-score small {
  font-size: 14px;
  color: #99a7a1;
}

.overall-card-meta {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.overall-level-badge {
  display: inline-block;
  padding: 3px 10px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 600;
  width: fit-content;
}
.level-teal .overall-level-badge { background: #d6ece8; color: #246b6c; }
.level-blue .overall-level-badge { background: #d6e4f1; color: #2b5e8c; }
.level-orange .overall-level-badge { background: #f4e0c2; color: #8a6d3b; }
.level-red .overall-level-badge { background: #f4d2d2; color: #9c3d3d; }
.level-gray .overall-level-badge { background: #e6ebe9; color: #6f807d; }

.overall-model,
.overall-confidence {
  margin: 0;
  font-size: 11px;
  color: #6f807d;
}
.overall-model code {
  background: rgba(36, 73, 77, 0.06);
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 10px;
  color: #4a5e5b;
  font-family: 'JetBrains Mono', monospace;
}
.overall-confidence strong {
  color: #24494d;
  font-weight: 600;
}
.mock-tag {
  margin-left: 6px;
  padding: 1px 6px;
  background: #fbf4e3;
  color: #8a6d3b;
  border-radius: 4px;
  font-size: 9px;
  font-weight: 500;
}

.overall-card-icon {
  color: #2f7f80;
  opacity: 0.6;
}

.dimensions-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
  margin: 16px 18px 0;
}
.dim-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px 16px;
  border: 1px solid #e2ebe8;
  border-radius: 10px;
  background: #ffffff;
}
.dim-card.wide {
  grid-column: span 2;
}
.dim-card.level-teal { border-left: 3px solid #2f7f80; }
.dim-card.level-blue { border-left: 3px solid #4a8cc2; }
.dim-card.level-orange { border-left: 3px solid #d18d3f; }
.dim-card.level-red { border-left: 3px solid #c84a4a; }
.dim-card.level-gray { border-left: 3px solid #b6c2be; }

.dim-card header {
  display: flex;
  align-items: center;
  gap: 10px;
}
.dim-card header > svg {
  color: #2f7f80;
  flex-shrink: 0;
}
.dim-card header h3 {
  margin: 0;
  font-size: 13px;
  color: #24494d;
  font-weight: 600;
}
.dim-card header small {
  display: block;
  font-size: 10px;
  color: #99a7a1;
  margin-top: 2px;
}
.dim-card header > div {
  flex: 1;
}
.dim-score {
  font-family: 'JetBrains Mono', monospace;
  font-size: 18px;
  font-weight: 700;
  color: #24494d;
}

.dim-details {
  margin: 0;
  font-size: 12px;
  color: #4a5e5b;
  line-height: 1.55;
}

.dim-meta {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px 16px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.dim-meta li {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: #6f807d;
}
.dim-meta li strong {
  color: #24494d;
  font-weight: 600;
}

.geo-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px 16px;
  padding: 8px 0 0;
  border-top: 1px solid #f0f5f3;
  margin-top: 4px;
}
.geo-grid > div {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 11px;
}
.geo-grid span {
  color: #99a7a1;
}
.geo-grid strong {
  color: #24494d;
  font-weight: 600;
  font-size: 12px;
}

.reasoning-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin: 16px 18px 20px;
}
.reasoning-card,
.action-card {
  padding: 14px 16px;
  border: 1px solid #e2ebe8;
  border-radius: 10px;
  background: #ffffff;
}
.reasoning-card {
  border-left: 3px solid #d18d3f;
  background: linear-gradient(135deg, #fbf4e3 0%, #ffffff 60%);
}
.action-card {
  border-left: 3px solid #2f7f80;
  background: linear-gradient(135deg, #e8f5f1 0%, #ffffff 60%);
}
.reasoning-card header,
.action-card header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.reasoning-card header { color: #8a6d3b; }
.action-card header { color: #246b6c; }
.reasoning-card header h3,
.action-card header h3 {
  margin: 0;
  font-size: 12px;
  font-weight: 600;
}
.reasoning-card ul,
.action-card ol {
  margin: 0;
  padding-left: 20px;
  font-size: 12px;
  color: #4a5e5b;
  line-height: 1.65;
}
.reasoning-card li,
.action-card li {
  margin-bottom: 4px;
}

@media (max-width: 1180px) {
  .ai-layout {
    grid-template-columns: 240px 1fr;
  }
  .dimensions-grid {
    grid-template-columns: 1fr;
  }
  .dim-card.wide {
    grid-column: span 1;
  }
  .reasoning-grid {
    grid-template-columns: 1fr;
  }
  .geo-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 820px) {
  .ai-layout {
    grid-template-columns: 1fr;
  }
  .hazard-list-panel {
    max-height: 220px;
  }
}

/* v1.4.0: SHAP-like 特征贡献度条形图 + 反馈按钮 */
.contrib-card {
  background: #ffffff;
  border-radius: 12px;
  padding: 18px 22px;
  border: 1px solid #e5e7eb;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.04);
}
.contrib-card header {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #1f2937;
  margin-bottom: 12px;
}
.contrib-card header h3 {
  font-size: 14px;
  font-weight: 600;
  margin: 0;
}
.contrib-card header small {
  margin-left: auto;
  color: #6b7280;
  font-size: 11px;
}
.contrib-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.contrib-list li {
  display: grid;
  grid-template-columns: 130px 1fr 50px;
  align-items: center;
  gap: 10px;
  font-size: 12px;
}
.contrib-name {
  color: #374151;
  font-weight: 500;
  text-align: right;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.contrib-bar-wrap {
  height: 8px;
  background: #f3f4f6;
  border-radius: 4px;
  overflow: hidden;
}
.contrib-bar {
  height: 100%;
  border-radius: 4px;
  transition: width 0.3s ease;
}
.contrib-value {
  font-family: 'JetBrains Mono', 'SF Mono', monospace;
  font-size: 11px;
  font-weight: 600;
  color: #1f2937;
  text-align: right;
}
.contrib-footer {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid #f3f4f6;
}
.feedback-btn {
  padding: 6px 14px;
  border-radius: 6px;
  border: 1px solid #d1d5db;
  background: #ffffff;
  color: #374151;
  cursor: pointer;
  font-size: 12px;
  font-weight: 500;
  transition: all 0.2s;
}
.feedback-btn:hover:not(:disabled) {
  background: #f9fafb;
  border-color: #9ca3af;
}
.feedback-btn:disabled {
  cursor: not-allowed;
  opacity: 0.7;
}
.feedback-btn.submitted {
  background: #d1fae5;
  color: #065f46;
  border-color: #10b981;
}
.feedback-btn-neg.submitted {
  background: #fef3c7;
  color: #92400e;
  border-color: #f59e0b;
}
.feedback-error {
  color: #dc2626;
  font-size: 11px;
  margin-left: auto;
}
@media (max-width: 700px) {
  .contrib-list li {
    grid-template-columns: 90px 1fr 40px;
    font-size: 11px;
  }
}

/* ===== 多模态融合面板样式 ===== */
.fusion-panel {
  margin: 16px 18px 0;
  background: #fff;
  border: 1px solid #e2ebe8;
  border-radius: 12px;
  overflow: hidden;
  transition: all 0.2s;
}

.fusion-panel.expanded {
  border-color: #b6d8d2;
}

.fusion-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 18px;
  cursor: pointer;
  background: linear-gradient(135deg, #e8f5f1 0%, #fff 60%);
  border-bottom: 1px solid transparent;
  transition: all 0.2s;
}

.fusion-panel.expanded .fusion-header {
  border-bottom-color: #e2ebe8;
}

.fusion-title {
  display: flex;
  align-items: center;
  gap: 10px;
  color: #24494d;
  font-weight: 600;
  font-size: 14px;
}

.fusion-title svg {
  color: #2f7f80;
}

.fusion-score {
  font-family: 'JetBrains Mono', monospace;
  font-size: 18px;
  font-weight: 700;
}

.fusion-level-badge {
  padding: 3px 10px;
  border-radius: 12px;
  font-size: 11px;
  font-weight: 600;
}

.fusion-level-badge.level-red { background: #f4d2d2; color: #9c3d3d; }
.fusion-level-badge.level-orange { background: #f4e0c2; color: #8a6d3b; }
.fusion-level-badge.level-yellow { background: #fef9c3; color: #854d0e; }
.fusion-level-badge.level-blue { background: #d6e4f1; color: #2b5e8c; }
.fusion-level-badge.level-teal { background: #d6ece8; color: #246b6c; }

.fusion-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.fusion-refresh-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: 1px solid #d9e6e1;
  background: #fff;
  border-radius: 6px;
  color: #56716e;
  cursor: pointer;
}

.fusion-refresh-btn:hover {
  background: #f4f7f5;
  color: #2f7f80;
}

.fusion-refresh-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.fusion-toggle {
  font-size: 12px;
  color: #6f807d;
}

.fusion-body {
  padding: 16px 18px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.fusion-contributions h4,
.fusion-explanation h4,
.fusion-modal-details h4 {
  margin: 0 0 10px;
  font-size: 13px;
  color: #37555a;
  font-weight: 600;
}

.contribution-bars {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.contribution-item {
  display: flex;
  align-items: center;
  gap: 12px;
}

.contribution-label {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 0 0 120px;
  font-size: 12px;
  color: #36585b;
}

.contribution-label svg {
  color: #7a8d89;
}

.contribution-bar-wrap {
  flex: 1;
  height: 10px;
  background: #f0f5f4;
  border-radius: 5px;
  overflow: hidden;
}

.contribution-bar {
  height: 100%;
  border-radius: 5px;
  transition: width 0.3s;
}

.contribution-value {
  flex: 0 0 45px;
  font-size: 12px;
  font-weight: 600;
  color: #36585b;
  text-align: right;
}

.fusion-explanation {
  background: #fafdfc;
  padding: 14px 16px;
  border-radius: 8px;
  border: 1px solid #e2ebe8;
}

.fusion-explanation p {
  margin: 0;
  color: #36585b;
  font-size: 13px;
  line-height: 1.6;
}

.modal-detail-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
}

.modal-detail-card {
  padding: 12px 14px;
  border: 1px solid;
  border-radius: 8px;
  background: #fff;
}

.modal-detail-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.modal-detail-header svg {
  color: #7a8d89;
}

.modal-detail-header strong {
  flex: 1;
  font-size: 13px;
  color: #24494d;
}

.modal-score {
  font-family: 'JetBrains Mono', monospace;
  font-size: 16px;
  font-weight: 700;
  color: #2f7f80;
}

.modal-detail-desc {
  margin: 0 0 8px;
  font-size: 12px;
  color: #56716e;
  line-height: 1.5;
}

.modal-confidence {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: 8px;
  border-top: 1px dashed #e2ebe8;
  font-size: 11px;
}

.modal-confidence span {
  color: #99a7a1;
}

.modal-confidence strong {
  color: #24494d;
  font-weight: 600;
}

.fusion-ops {
  display: flex;
  gap: 10px;
  padding-top: 12px;
  border-top: 1px solid #e2ebe8;
}

.primary-btn {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  padding: 8px 14px;
  border: 0;
  background: #2f7f80;
  color: #fff;
  border-radius: 8px;
  font-size: 13px;
  cursor: pointer;
}

.primary-btn:hover {
  background: #246b6c;
}

.ghost-btn {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  padding: 6px 10px;
  border: 1px solid #d9e6e1;
  background: #fff;
  border-radius: 6px;
  color: #37555a;
  font-size: 12px;
  cursor: pointer;
}

.ghost-btn:hover {
  background: #f4f7f5;
}

.fusion-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 30px;
  color: #7a8d89;
  font-size: 13px;
}

@media (max-width: 900px) {
  .modal-detail-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .contribution-item {
    flex-wrap: wrap;
  }
  .contribution-label {
    flex: 0 0 100%;
  }
  .contribution-bar-wrap {
    flex: 0 0 100%;
  }
  .fusion-ops {
    flex-wrap: wrap;
  }
}
</style>