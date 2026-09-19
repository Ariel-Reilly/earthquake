<script setup>
/**
 * 灾损评估页（/dashboard/damage-assessment）。
 *
 * v1.8.0 P1：灾害损失评估能力。
 *
 * 两种模式：
 * 1. 单点评估：选择已有灾害点，调用 GET /api/ai/disaster-assessment?hazardCode=xxx
 *    → DisasterAssessmentResponseDto（总体评分 + 六大灾种 + 七维度 + 推理链 + 建议动作）
 * 2. 全国独立测算：输入坐标 + 可选天气/地理因子，
 *    POST /api/ai/disaster-assessment/independent
 *    → IndependentAssessmentResponseDto（多点评分）
 */
import { computed, onMounted, ref, reactive } from 'vue'
import {
  Radar,
  AlertTriangle,
  ArrowRight,
  BarChart3,
  Bot,
  CheckCircle2,
  CloudRain,
  FileText,
  Globe,
  Image,
  Loader2,
  MapPin,
  MountainSnow,
  Newspaper,
  Radio,
  RefreshCw,
  Snowflake,
  Target,
  Thermometer,
  TreePine,
  Waves,
  XCircle,
} from 'lucide-vue-next'
import { dashboardApi } from '../../services/dashboardApi'
import AiHealthBadge from '../../components/AiHealthBadge.vue'

// ── Tab ──────────────────────────────────────────────
const activeTab = ref('single') // 'single' | 'nationwide'

// ── 单点评估状态 ──────────────────────────────────────
const loading = ref(false)
const errorMsg = ref('')
const hazards = ref([])
const selectedHazardCode = ref('')
const assessmentResult = ref(null)
const statusDict = ref(null)

async function loadHazards() {
  try {
    const resp = await dashboardApi.getHazards({ pageSize: 200 })
    hazards.value = resp.list ?? []
    if (hazards.value.length > 0 && !selectedHazardCode.value) {
      selectedHazardCode.value = hazards.value[0].hazardCode ?? hazards.value[0].code ?? ''
    }
  } catch (e) {
    hazards.value = []
  }
}

async function loadStatusDict() {
  try {
    statusDict.value = await dashboardApi.getDisasterAssessmentStatus()
  } catch (_) {
    statusDict.value = null
  }
}

async function runSingleAssessment() {
  if (!selectedHazardCode.value) { errorMsg.value = '请选择一个灾害点'; return }
  loading.value = true
  errorMsg.value = ''
  assessmentResult.value = null
  try {
    assessmentResult.value = await dashboardApi.getDisasterAssessment(selectedHazardCode.value)
  } catch (e) {
    errorMsg.value = '评估失败：' + (e?.message || '后端 AI 服务不可达')
  } finally {
    loading.value = false
  }
}

// ── 全国独立测算状态 ──────────────────────────────────
const nationwideLoading = ref(false)
const nationwideError = ref('')
const nationwideResult = ref(null)
const assessmentPoints = ref([])
const nationwideForm = reactive({
  lon: '',
  lat: '',
  regionName: '',
  rainfall24h: '',
  rainfall7d: '',
  temperature: '',
  elevationM: '',
  slopeDeg: '',
  soilGrade: '',
  vegetationCover: '',
  mockWeather: true,
})

function addPoint() {
  const lon = parseFloat(nationwideForm.lon)
  const lat = parseFloat(nationwideForm.lat)
  if (isNaN(lon) || isNaN(lat)) { nationwideError.value = '请输入有效的经纬度'; return }
  if (lon < 73 || lon > 135 || lat < 18 || lat > 54) { nationwideError.value = '经度范围 73-135°，纬度范围 18-54°'; return }
  assessmentPoints.value.push({
    lon, lat,
    regionName: nationwideForm.regionName,
    rainfall24h: nationwideForm.rainfall24h ? parseFloat(nationwideForm.rainfall24h) : undefined,
    rainfall7d: nationwideForm.rainfall7d ? parseFloat(nationwideForm.rainfall7d) : undefined,
    temperature: nationwideForm.temperature ? parseFloat(nationwideForm.temperature) : undefined,
    elevationM: nationwideForm.elevationM ? parseFloat(nationwideForm.elevationM) : undefined,
    slopeDeg: nationwideForm.slopeDeg ? parseFloat(nationwideForm.slopeDeg) : undefined,
    soilGrade: nationwideForm.soilGrade ? parseFloat(nationwideForm.soilGrade) : undefined,
    vegetationCover: nationwideForm.vegetationCover ? parseFloat(nationwideForm.vegetationCover) : undefined,
  })
  nationwideForm.lon = ''
  nationwideForm.lat = ''
  nationwideForm.regionName = ''
  nationwideError.value = ''
}

function removePoint(i) { assessmentPoints.value.splice(i, 1) }

async function runNationwideAssessment() {
  if (assessmentPoints.value.length === 0) { nationwideError.value = '请先添加至少一个评估点'; return }
  nationwideLoading.value = true
  nationwideError.value = ''
  nationwideResult.value = null
  try {
    nationwideResult.value = await dashboardApi.runIndependentAssessment({
      points: assessmentPoints.value,
      minRiskLevel: 'MEDIUM',
      mockWeather: nationwideForm.mockWeather,
    })
  } catch (e) {
    nationwideError.value = '测算失败：' + (e?.message || '后端 AI 服务不可达')
  } finally {
    nationwideLoading.value = false
  }
}

// ── 辅助 ──────────────────────────────────────────────
const RISK_COLOR = {
  LOW: '#4f8f99',
  MEDIUM: '#e0a050',
  HIGH: '#d88561',
  CRITICAL: '#e06060',
}
const RISK_LABEL = {
  LOW: '低风险 · 稳定',
  MEDIUM: '中等风险 · 关注',
  HIGH: '高风险 · 预警',
  CRITICAL: '极高风险 · 异常严重',
}
const CONCERN_COLOR = {
  '稳定': '#276749',
  '关注': '#2b6cb0',
  '预警': '#c05621',
  '异常严重': '#c53030',
}
const DISASTER_TYPE_COLOR = {
  '滑坡': '#d69e2e', '崩塌': '#e53e3e', '泥石流': '#dd6b20',
  '地裂缝': '#5a67d8', '地面沉降': '#319795', '塌陷': '#d53f8c',
  '待定': '#718096',
}

const RISK_LEVEL = computed(() => {
  const r = assessmentResult.value?.overall?.riskLevel
  return r ?? null
})

const CONCERN = computed(() => assessmentResult.value?.overall?.concernLabel ?? '—')
const DISASTER_TYPE = computed(() => assessmentResult.value?.disasterType?.disasterType ?? '—')
const DT_COLOR = computed(() => DISASTER_TYPE_COLOR[DISASTER_TYPE.value] ?? '#718096')

// 七维度雷达图 SVG
const RADAR_W = 320
const RADAR_H = 280
const RADAR_CX = RADAR_W / 2
const RADAR_CY = RADAR_H / 2
const RADAR_R = 100
const DIMENSIONS = [
  { key: 'geographic',     label: '地理',           icon: Mountain,     weight: 0.30 },
  { key: 'precipitation',  label: '降水',           icon: CloudRain,    weight: 0.25 },
  { key: 'sensor',         label: '传感器',         icon: Radio,        weight: 0.20 },
  { key: 'historical',      label: '历史',           icon: Newspaper,    weight: 0.15 },
  { key: 'image',          label: '影像',           icon: Image,        weight: 0.10 },
]
const DIM_SCORE = computed(() => {
  const dims = assessmentResult.value?.dimensions ?? {}
  return DIMENSIONS.map((d) => {
    const entry = dims[d.key] ?? {}
    return { ...d, score: entry.score ?? 0 }
  })
})
const RADAR_POLYGON = computed(() => {
  const max = 100
  const pts = DIM_SCORE.value.map((d, i) => {
    const angle = (Math.PI * 2 * i) / DIMENSIONS.length - Math.PI / 2
    const r = (d.score / max) * RADAR_R
    return `${RADAR_CX + r * Math.cos(angle)},${RADAR_CY + r * Math.sin(angle)}`
  })
  return `M ${pts.join(' L ')} Z`
})
const RADAR_AXES = computed(() =>
  DIMENSIONS.map((_, i) => {
    const angle = (Math.PI * 2 * i) / DIMENSIONS.length - Math.PI / 2
    return {
      x2: RADAR_CX + RADAR_R * Math.cos(angle),
      y2: RADAR_CY + RADAR_R * Math.sin(angle),
    }
  })
)
const RADAR_GRID = computed(() => {
  const levels = [0.25, 0.5, 0.75, 1.0]
  return levels.map((frac) => {
    const r = frac * RADAR_R
    const pts = DIMENSIONS.map((_, i) => {
      const angle = (Math.PI * 2 * i) / DIMENSIONS.length - Math.PI / 2
      return `${RADAR_CX + r * Math.cos(angle)},${RADAR_CY + r * Math.sin(angle)}`
    })
    return `M ${pts.join(' L ')} Z`
  })
})
const RADAR_LABELS = computed(() =>
  DIMENSIONS.map((d, i) => {
    const angle = (Math.PI * 2 * i) / DIMENSIONS.length - Math.PI / 2
    const r = RADAR_R + 22
    return {
      x: RADAR_CX + r * Math.cos(angle),
      y: RADAR_CY + r * Math.sin(angle),
      label: d.label,
      weight: d.weight,
    }
  })
)

onMounted(async () => {
  await Promise.all([loadHazards(), loadStatusDict()])
})
</script>

<template>
  <div class="dap-root">
    <!-- Header -->
    <header class="dap-header">
      <AiHealthBadge />
    </header>

    <!-- Tabs -->
    <div class="dap-tabs">
      <button class="dap-tab" :class="{ active: activeTab === 'single' }" @click="activeTab = 'single'">
        <Target :size="15" /> 单点评估
      </button>
      <button class="dap-tab" :class="{ active: activeTab === 'nationwide' }" @click="activeTab = 'nationwide'">
        <Globe :size="15" /> 全国独立测算
      </button>
    </div>

    <!-- ══ SINGLE ASSESSMENT ══ -->
    <div v-if="activeTab === 'single'" class="dap-section">
      <div class="dap-control-bar">
        <div class="dap-control-group">
          <label class="dap-label">灾害点</label>
          <select v-model="selectedHazardCode" class="dap-select">
            <option value="">— 选择灾害点 —</option>
            <option v-for="h in hazards" :key="h.id" :value="h.hazardCode ?? h.code">
              {{ h.name }} ({{ h.hazardCode ?? h.code }})
            </option>
          </select>
        </div>
        <button class="dap-run-btn" :disabled="loading || !selectedHazardCode" @click="runSingleAssessment">
          <Loader2 v-if="loading" :size="15" class="spin" />
          <Bot v-else :size="15" />
          {{ loading ? '评估中…' : '开始评估' }}
        </button>
        <button class="dap-refresh-btn" :disabled="loading" @click="runSingleAssessment">
          <RefreshCw :size="14" :class="{ spin: loading }" />
        </button>
      </div>

      <div v-if="errorMsg" class="dap-error">
        <AlertTriangle :size="14" /> {{ errorMsg }}
      </div>

      <!-- Results -->
      <div v-if="assessmentResult" class="dap-results">
        <!-- 核心风险卡片 -->
        <div class="dap-risk-hero" :style="{ borderColor: RISK_COLOR[RISK_LEVEL] }">
          <div class="dap-risk-level" :style="{ color: RISK_COLOR[RISK_LEVEL] }">
            {{ RISK_LEVEL ? RISK_LABEL[RISK_LEVEL] : '—' }}
          </div>
          <div class="dap-risk-score">
            <span class="dap-score-num" :style="{ color: RISK_COLOR[RISK_LEVEL] }">
              {{ assessmentResult.overall?.riskScore?.toFixed(1) ?? '—' }}
            </span>
            <span class="dap-score-max">/ 100</span>
          </div>
          <div class="dap-risk-meta">
            <span
              class="dap-concern-badge"
              :style="{ background: CONCERN_COLOR[CONCERN] + '22', color: CONCERN_COLOR[CONCERN] }"
            >
              {{ CONCERN }}
            </span>
            <span
              class="dap-type-badge"
              :style="{ background: DT_COLOR + '22', color: DT_COLOR }"
            >
              {{ DISASTER_TYPE }}
            </span>
            <span v-if="assessmentResult.deviceAvailable === false" class="dap-no-device-badge">
              <AlertTriangle :size="11" /> 无设备数据
            </span>
          </div>
          <div class="dap-model-info">
            <Bot :size="12" />
            模型：{{ assessmentResult.model ?? '—' }}
            <span v-if="assessmentResult.mock" class="dap-mock-tag">降级</span>
          </div>
        </div>

        <div class="dap-results-grid">
          <!-- 雷达图 -->
          <div class="dap-card">
            <div class="dap-card-header">
              <strong><Radar :size="14" /> 七维度风险分布</strong>
              <span class="dap-card-sub">各因子加权贡献</span>
            </div>
            <div class="dap-radar-wrap">
              <svg :viewBox="`0 0 ${RADAR_W} ${RADAR_H}`" class="dap-radar-svg">
                <!-- 网格 -->
                <path v-for="(p, i) in RADAR_GRID" :key="i" :d="p" fill="none" stroke="rgba(47,127,128,.15)" stroke-width="1" />
                <!-- 轴线 -->
                <line
                  v-for="(axis, i) in RADAR_AXES"
                  :key="'a'+i"
                  :x1="RADAR_CX" :y1="RADAR_CY"
                  :x2="axis.x2" :y2="axis.y2"
                  stroke="rgba(47,127,128,.2)" stroke-width="1"
                />
                <!-- 数据多边形 -->
                <path :d="RADAR_POLYGON" fill="rgba(43,108,176,.2)" stroke="#2b6cb0" stroke-width="2" />
                <!-- 顶点圆点 -->
                <circle
                  v-for="(d, i) in DIM_SCORE"
                  :key="'c'+i"
                  :cx="(() => { const a=(Math.PI*2*i)/DIMENSIONS.length-Math.PI/2; const r=(d.score/100)*RADAR_R; return RADAR_CX+r*Math.cos(a); })()"
                  :cy="(() => { const a=(Math.PI*2*i)/DIMENSIONS.length-Math.PI/2; const r=(d.score/100)*RADAR_R; return RADAR_CY+r*Math.sin(a); })()"
                  r="5" fill="#2b6cb0" stroke="#fff" stroke-width="2"
                />
                <!-- 标签 -->
                <text
                  v-for="(l, i) in RADAR_LABELS"
                  :key="'l'+i"
                  :x="l.x"
                  :y="l.y"
                  text-anchor="middle"
                  dominant-baseline="middle"
                  class="dap-radar-label"
                >
                  <tspan font-size="11" fill="#56716e" font-weight="600">{{ l.label }}</tspan>
                  <tspan x="0" dy="13" font-size="10" fill="#9baead">{{ (DIM_SCORE[i]?.score ?? 0).toFixed(0) }}</tspan>
                </text>
              </svg>
            </div>
            <!-- 维度得分条 -->
            <div class="dap-dim-bars">
              <div v-for="d in DIM_SCORE" :key="d.key" class="dap-dim-bar-row">
                <span class="dap-dim-name">{{ d.label }}</span>
                <div class="dap-dim-bar-track">
                  <div
                    class="dap-dim-bar-fill"
                    :style="{ width: `${d.score}%`, background: `hsl(${200 - d.score * 1.2}, 55%, 45%)` }"
                  ></div>
                </div>
                <span class="dap-dim-score">{{ d.score.toFixed(1) }}</span>
              </div>
            </div>
          </div>

          <!-- 推理链 + 建议 -->
          <div class="dap-card">
            <div class="dap-card-header">
              <strong><Bot :size="14" /> AI 推理链</strong>
            </div>
            <div class="dap-reasoning">
              <div v-if="!assessmentResult.reasoning?.length" class="dap-empty-sub">暂无推理信息</div>
              <div v-for="(r, i) in assessmentResult.reasoning" :key="i" class="dap-reason-item">
                <span class="dap-reason-num">{{ i + 1 }}</span>
                <span class="dap-reason-text">{{ r }}</span>
              </div>
            </div>
            <div class="dap-divider" />
            <div class="dap-card-header" style="margin-top:12px">
              <strong><CheckCircle2 :size="14" /> 处置建议</strong>
            </div>
            <div class="dap-actions">
              <div v-if="!assessmentResult.suggestedActions?.length" class="dap-empty-sub">暂无建议</div>
              <div v-for="(a, i) in assessmentResult.suggestedActions" :key="i" class="dap-action-item">
                <ArrowRight :size="13" style="color:#4f8f99;flex-shrink:0" />
                <span>{{ a }}</span>
              </div>
            </div>
          </div>
        </div>

        <!-- 灾种分类详情 -->
        <div v-if="assessmentResult.disasterType" class="dap-card">
          <div class="dap-card-header">
            <strong><MountainSnow :size="14" /> 灾害类型判定</strong>
            <span class="dap-card-sub">AI 推断依据</span>
          </div>
          <div class="dap-dt-grid">
            <div class="dap-dt-item">
              <span class="dap-dt-label">判定类型</span>
              <span class="dap-dt-val" :style="{ color: DT_COLOR }">{{ assessmentResult.disasterType.disasterType }}</span>
            </div>
            <div class="dap-dt-item">
              <span class="dap-dt-label">数据来源</span>
              <span class="dap-dt-val">
                <span class="dap-source-tag">{{ assessmentResult.disasterType.source }}</span>
              </span>
            </div>
            <div class="dap-dt-item">
              <span class="dap-dt-label">置信度</span>
              <span class="dap-dt-val" style="color:#2b6cb0">
                {{ ((assessmentResult.disasterType.confidence ?? 0) * 100).toFixed(0) }}%
              </span>
            </div>
            <div class="dap-dt-item" style="grid-column:1/-1">
              <span class="dap-dt-label">判定依据</span>
              <span class="dap-dt-val">{{ assessmentResult.disasterType.reason ?? '—' }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Empty -->
      <div v-else-if="!loading" class="dap-empty">
        <BarChart3 :size="48" style="color:#c8d5d5" />
        <p>选择一个灾害点并点击「开始评估」</p>
      </div>
    </div>

    <!-- ══ NATIONWIDE ASSESSMENT ══ -->
    <div v-if="activeTab === 'nationwide'" class="dap-section">
      <div class="dap-nationwide-layout">
        <!-- 左：表单 -->
        <div class="dap-card dap-nationwide-form">
          <div class="dap-card-header">
            <strong><MapPin :size="14" /> 添加评估点</strong>
          </div>

          <div class="dap-form-grid">
            <div class="dap-form-row">
              <label class="dap-form-label">经度 *</label>
              <input v-model="nationwideForm.lon" type="number" step="0.001" min="73" max="135"
                class="dap-input" placeholder="例：104.065" />
            </div>
            <div class="dap-form-row">
              <label class="dap-form-label">纬度 *</label>
              <input v-model="nationwideForm.lat" type="number" step="0.001" min="18" max="54"
                class="dap-input" placeholder="例：30.572" />
            </div>
            <div class="dap-form-row">
              <label class="dap-form-label">地名</label>
              <input v-model="nationwideForm.regionName" type="text"
                class="dap-input" placeholder="例：四川省甘孜州" />
            </div>
          </div>

          <div class="dap-form-section-title">可选天气/地理因子</div>
          <div class="dap-form-grid">
            <div class="dap-form-row">
              <label class="dap-form-label"><CloudRain :size="12" /> 24h 降雨(mm)</label>
              <input v-model="nationwideForm.rainfall24h" type="number" step="0.1"
                class="dap-input" placeholder="0" />
            </div>
            <div class="dap-form-row">
              <label class="dap-form-label"><CloudRain :size="12" /> 7d 降雨(mm)</label>
              <input v-model="nationwideForm.rainfall7d" type="number" step="0.1"
                class="dap-input" placeholder="0" />
            </div>
            <div class="dap-form-row">
              <label class="dap-form-label"><Thermometer :size="12" /> 气温(℃)</label>
              <input v-model="nationwideForm.temperature" type="number" step="0.1"
                class="dap-input" placeholder="25" />
            </div>
            <div class="dap-form-row">
              <label class="dap-form-label"><MountainSnow :size="12" /> 海拔(m)</label>
              <input v-model="nationwideForm.elevationM" type="number" step="1"
                class="dap-input" placeholder="1200" />
            </div>
            <div class="dap-form-row">
              <label class="dap-form-label"><Waves :size="12" /> 坡度(°)</label>
              <input v-model="nationwideForm.slopeDeg" type="number" step="1"
                class="dap-input" placeholder="30" />
            </div>
            <div class="dap-form-row">
              <label class="dap-form-label"><TreePine :size="12" /> 植被覆盖度</label>
              <input v-model="nationwideForm.vegetationCover" type="number" step="0.01" min="0" max="1"
                class="dap-input" placeholder="0.5" />
            </div>
          </div>

          <label class="dap-checkbox-row">
            <input v-model="nationwideForm.mockWeather" type="checkbox" />
            <span>缺失因子使用模拟值填充（推荐开启，保证 AI 总能输出结论）</span>
          </label>

          <button class="dap-add-point-btn" @click="addPoint">
            <MapPin :size="14" /> 添加此点到列表
          </button>

          <!-- 已添加点列表 -->
          <div v-if="assessmentPoints.length" class="dap-points-list">
            <div class="dap-points-title">待评估点 ({{ assessmentPoints.length }})</div>
            <div v-for="(pt, i) in assessmentPoints" :key="i" class="dap-point-item">
              <span class="dap-point-coord">{{ pt.lon.toFixed(3) }}, {{ pt.lat.toFixed(3) }}</span>
              <span v-if="pt.regionName" class="dap-point-name">{{ pt.regionName }}</span>
              <button class="dap-point-remove" @click="removePoint(i)"><XCircle :size="14" /></button>
            </div>
          </div>

          <button class="dap-run-btn" :disabled="nationwideLoading || assessmentPoints.length === 0"
            @click="runNationwideAssessment">
            <Loader2 v-if="nationwideLoading" :size="15" class="spin" />
            <Globe v-else :size="15" />
            {{ nationwideLoading ? '测算中…' : '开始全国测算' }}
          </button>

          <div v-if="nationwideError" class="dap-error">
            <AlertTriangle :size="14" /> {{ nationwideError }}
          </div>
        </div>

        <!-- 右：结果 -->
        <div v-if="nationwideResult" class="dap-nationwide-results">
          <div class="dap-nationwide-kpi">
            <div class="dap-nkpi">
              <span class="dap-nkpi-val">{{ nationwideResult.totalPoints }}</span>
              <span class="dap-nkpi-label">评估点</span>
            </div>
            <div class="dap-nkpi">
              <span class="dap-nkpi-val" style="color:#d88561">{{ nationwideResult.detectedCount }}</span>
              <span class="dap-nkpi-label">触发预警点</span>
            </div>
            <div class="dap-nkpi">
              <span class="dap-nkpi-val" style="color:#2b6cb0">{{ nationwideResult.executionMs }}ms</span>
              <span class="dap-nkpi-label">推理耗时</span>
            </div>
          </div>

          <div class="dap-nationwide-table">
            <div class="dap-card-header">
              <strong><Globe :size="14" /> 评估结果</strong>
            </div>
            <div class="dap-nt-wrap">
              <table class="dap-table">
                <thead>
                  <tr>
                    <th>坐标</th>
                    <th>地区</th>
                    <th>风险等级</th>
                    <th>评分</th>
                    <th>灾种</th>
                    <th>置信度</th>
                    <th>建议</th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="(r, i) in nationwideResult.results"
                    :key="i"
                    :class="{
                      'dap-row-critical': r.riskLevel === 'CRITICAL',
                      'dap-row-high': r.riskLevel === 'HIGH',
                      'dap-row-medium': r.riskLevel === 'MEDIUM',
                    }"
                  >
                    <td class="dap-coord">{{ r.lon.toFixed(3) }}, {{ r.lat.toFixed(3) }}</td>
                    <td>{{ r.regionName || '—' }}</td>
                    <td>
                      <span
                        class="dap-risk-tag"
                        :style="{ background: (RISK_COLOR[r.riskLevel] ?? '#999') + '22', color: RISK_COLOR[r.riskLevel] ?? '#999' }"
                      >
                        {{ RISK_LABEL[r.riskLevel] ?? r.riskLevel }}
                      </span>
                    </td>
                    <td class="dap-score-cell">{{ r.riskScore.toFixed(1) }}</td>
                    <td :style="{ color: DISASTER_TYPE_COLOR[r.disasterType] ?? '#999' }">
                      {{ r.disasterType }}
                    </td>
                    <td class="dap-conf">{{ (r.confidence * 100).toFixed(0) }}%</td>
                    <td class="dap-suggestion">{{ r.suggestedActions?.[0] ?? '—' }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>

        <!-- Nationwide Empty -->
        <div v-else-if="!nationwideLoading" class="dap-nationwide-empty">
          <Globe :size="48" style="color:#c8d5d5" />
          <p>在左侧输入坐标添加评估点，点击「开始全国测算」</p>
          <p class="dap-empty-sub">不依赖已知灾害点档案，基于坐标 + 可选因子快速评估全国任意位置</p>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dap-root { padding: 20px 24px; max-width: 1300px; }

/* Header */
.dap-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 20px; gap: 12px; }
.dap-title { display: flex; align-items: center; gap: 8px; font-size: 18px; font-weight: 700; color: #24494d; margin: 0 0 4px; }
.dap-subtitle { font-size: 13px; color: #7a8d89; margin: 0; }

/* Tabs */
.dap-tabs { display: flex; gap: 8px; margin-bottom: 20px; border-bottom: 2px solid #e9eee9; }
.dap-tab { display: flex; align-items: center; gap: 6px; padding: 8px 16px; border: none; background: none; cursor: pointer; font-size: 13px; color: #7a8d89; border-bottom: 2px solid transparent; margin-bottom: -2px; border-radius: 6px 6px 0 0; }
.dap-tab:hover { color: #2c3e3e; background: #f0f8f6; }
.dap-tab.active { color: #2b6cb0; border-bottom-color: #2b6cb0; font-weight: 600; }

/* Control bar */
.dap-control-bar { display: flex; gap: 12px; align-items: flex-end; background: #fff; border: 1px solid #e9eee9; border-radius: 12px; padding: 16px; margin-bottom: 16px; }
.dap-control-group { display: flex; flex-direction: column; gap: 4px; }
.dap-label { font-size: 11px; color: #7a8d89; font-weight: 600; text-transform: uppercase; }
.dap-select { padding: 7px 12px; border: 1px solid #d0dcd8; border-radius: 8px; background: #f8fbfb; font-size: 13px; min-width: 280px; color: #2c3e3e; }
.dap-select:focus { outline: none; border-color: #4f8f99; }
.dap-run-btn { display: flex; align-items: center; gap: 6px; padding: 8px 20px; background: #2b6cb0; color: #fff; border: none; border-radius: 8px; font-size: 13px; font-weight: 600; cursor: pointer; }
.dap-run-btn:hover:not(:disabled) { background: #1a4a8a; }
.dap-run-btn:disabled { opacity: .6; cursor: not-allowed; }
.dap-refresh-btn { padding: 8px 10px; background: #f0f8f6; border: 1px solid #d0dcd8; border-radius: 8px; cursor: pointer; color: #56716e; }
.dap-refresh-btn:hover { border-color: #4f8f99; color: #4f8f99; }
.dap-error { display: flex; align-items: center; gap: 8px; padding: 10px 14px; background: #fff5f5; border: 1px solid #feb2b2; border-radius: 8px; color: #c53030; font-size: 13px; margin-bottom: 16px; }
.spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

/* Risk Hero */
.dap-risk-hero { background: #fff; border: 2px solid #ccc; border-radius: 16px; padding: 24px; margin-bottom: 16px; }
.dap-risk-level { font-size: 15px; font-weight: 700; margin-bottom: 6px; }
.dap-risk-score { margin-bottom: 12px; }
.dap-score-num { font-size: 56px; font-weight: 800; line-height: 1; }
.dap-score-max { font-size: 18px; color: #9baead; margin-left: 4px; }
.dap-risk-meta { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin-bottom: 10px; }
.dap-concern-badge, .dap-type-badge, .dap-no-device-badge { display: inline-flex; align-items: center; gap: 4px; padding: 4px 10px; border-radius: 20px; font-size: 12px; font-weight: 600; }
.dap-no-device-badge { background: #fffaf0; color: #c05621; }
.dap-model-info { font-size: 12px; color: #7a8d89; display: flex; align-items: center; gap: 4px; }
.dap-mock-tag { background: #fffaf0; color: #c05621; padding: 1px 6px; border-radius: 8px; font-size: 10px; font-weight: 700; }

/* Results Grid */
.dap-results { display: flex; flex-direction: column; gap: 16px; }
.dap-results-grid { display: grid; grid-template-columns: 380px 1fr; gap: 16px; }
@media (max-width: 960px) { .dap-results-grid { grid-template-columns: 1fr; } }
.dap-card { background: #fff; border: 1px solid #e9eee9; border-radius: 12px; padding: 16px; }
.dap-card-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.dap-card-header strong { font-size: 13px; color: #24494d; display: flex; align-items: center; gap: 6px; }
.dap-card-sub { font-size: 11px; color: #7a8d89; }

/* Radar */
.dap-radar-wrap { display: flex; justify-content: center; margin-bottom: 16px; }
.dap-radar-svg { width: 280px; height: 250px; }
.dap-radar-label text { font-family: -apple-system, sans-serif; }

/* Dimension bars */
.dap-dim-bars { display: flex; flex-direction: column; gap: 8px; }
.dap-dim-bar-row { display: flex; align-items: center; gap: 8px; }
.dap-dim-name { font-size: 12px; color: #56716e; width: 52px; flex-shrink: 0; }
.dap-dim-bar-track { flex: 1; height: 6px; background: #f0f5f3; border-radius: 3px; overflow: hidden; }
.dap-dim-bar-fill { height: 100%; border-radius: 3px; transition: width .4s; }
.dap-dim-score { font-size: 12px; color: #56716e; width: 36px; text-align: right; font-family: monospace; }

/* Reasoning */
.dap-reasoning { display: flex; flex-direction: column; gap: 8px; }
.dap-reason-item { display: flex; gap: 10px; align-items: flex-start; }
.dap-reason-num { min-width: 20px; height: 20px; background: #e8f4f2; color: #4f8f99; border-radius: 50%; font-size: 11px; font-weight: 700; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.dap-reason-text { font-size: 13px; color: #2c3e3e; line-height: 1.5; }
.dap-divider { height: 1px; background: #e9eee9; margin: 12px 0; }
.dap-actions { display: flex; flex-direction: column; gap: 8px; }
.dap-action-item { display: flex; gap: 8px; align-items: flex-start; font-size: 13px; color: #2c3e3e; line-height: 1.5; }
.dap-empty-sub { font-size: 12px; color: #9baead; font-style: italic; }

/* Disaster Type Grid */
.dap-dt-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.dap-dt-item { display: flex; flex-direction: column; gap: 4px; }
.dap-dt-label { font-size: 11px; color: #7a8d89; font-weight: 600; text-transform: uppercase; }
.dap-dt-val { font-size: 14px; color: #24494d; font-weight: 600; }
.dap-source-tag { display: inline-block; padding: 2px 8px; background: #e8f4f2; color: #4f8f99; border-radius: 12px; font-size: 12px; font-weight: 600; }

/* Nationwide */
.dap-nationwide-layout { display: grid; grid-template-columns: 360px 1fr; gap: 16px; }
@media (max-width: 900px) { .dap-nationwide-layout { grid-template-columns: 1fr; } }
.dap-nationwide-form { display: flex; flex-direction: column; gap: 12px; }
.dap-form-grid { display: flex; flex-direction: column; gap: 8px; }
.dap-form-row { display: flex; flex-direction: column; gap: 3px; }
.dap-form-label { font-size: 11px; color: #7a8d89; font-weight: 600; display: flex; align-items: center; gap: 4px; }
.dap-input { padding: 7px 10px; border: 1px solid #d0dcd8; border-radius: 7px; font-size: 13px; background: #f8fbfb; color: #2c3e3e; }
.dap-input:focus { outline: none; border-color: #4f8f99; background: #fff; }
.dap-form-section-title { font-size: 12px; color: #7a8d89; font-weight: 600; margin-top: 4px; padding-top: 8px; border-top: 1px solid #e9eee9; }
.dap-checkbox-row { display: flex; align-items: flex-start; gap: 8px; font-size: 12px; color: #56716e; cursor: pointer; }
.dap-checkbox-row input { margin-top: 2px; }
.dap-add-point-btn { display: flex; align-items: center; justify-content: center; gap: 6px; padding: 8px; background: #f0f8f6; border: 1px solid #4f8f99; border-radius: 8px; color: #4f8f99; font-size: 13px; cursor: pointer; }
.dap-add-point-btn:hover { background: #e8f4f2; }
.dap-points-list { background: #f8fbfb; border-radius: 8px; padding: 10px; display: flex; flex-direction: column; gap: 6px; max-height: 200px; overflow-y: auto; }
.dap-points-title { font-size: 12px; color: #7a8d89; font-weight: 600; margin-bottom: 4px; }
.dap-point-item { display: flex; align-items: center; gap: 8px; background: #fff; border: 1px solid #e9eee9; border-radius: 6px; padding: 6px 10px; }
.dap-point-coord { font-size: 12px; color: #2c3e3e; font-family: monospace; flex: 1; }
.dap-point-name { font-size: 11px; color: #7a8d89; }
.dap-point-remove { border: none; background: none; cursor: pointer; color: #e06060; padding: 0; display: flex; }
.dap-nationwide-results { display: flex; flex-direction: column; gap: 12px; }
.dap-nationwide-kpi { display: flex; gap: 12px; }
.dap-nkpi { flex: 1; background: #fff; border: 1px solid #e9eee9; border-radius: 10px; padding: 12px 16px; text-align: center; }
.dap-nkpi-val { display: block; font-size: 24px; font-weight: 800; color: #24494d; }
.dap-nkpi-label { font-size: 11px; color: #7a8d89; }
.dap-nationwide-table { background: #fff; border: 1px solid #e9eee9; border-radius: 12px; overflow: hidden; }
.dap-nt-wrap { overflow-x: auto; max-height: 400px; overflow-y: auto; }
.dap-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.dap-table th { padding: 8px 10px; text-align: left; background: #f8fbfb; color: #56716e; font-weight: 600; border-bottom: 1px solid #e9eee9; white-space: nowrap; position: sticky; top: 0; }
.dap-table td { padding: 7px 10px; border-bottom: 1px solid #f0f5f3; color: #2c3e3e; }
.dap-coord { font-family: monospace; font-size: 11px; color: #7a8d89; }
.dap-score-cell { font-family: monospace; font-weight: 700; color: #2b6cb0; }
.dap-conf { font-family: monospace; color: #7a8d89; }
.dap-suggestion { font-size: 11px; color: #56716e; max-width: 140px; }
.dap-risk-tag { display: inline-block; padding: 2px 8px; border-radius: 12px; font-size: 11px; font-weight: 600; white-space: nowrap; }
.dap-row-critical { background: #fff5f5; }
.dap-row-high { background: #fffaf0; }
.dap-row-medium { background: #f7fafc; }

/* Empty */
.dap-empty { display: flex; flex-direction: column; align-items: center; padding: 80px 20px; gap: 12px; color: #7a8d89; text-align: center; }
.dap-empty p { margin: 0; font-size: 15px; color: #56716e; }
.dap-empty-sub { font-size: 13px; color: #9baead; }
.dap-nationwide-empty { display: flex; flex-direction: column; align-items: center; padding: 80px 20px; gap: 12px; text-align: center; }
.dap-nationwide-empty p { margin: 0; font-size: 14px; color: #56716e; }

@media (max-width: 720px) {
  .dap-root { padding: 12px; }
  .dap-control-bar { flex-wrap: wrap; }
  .dap-dt-grid { grid-template-columns: 1fr 1fr; }
}
</style>
