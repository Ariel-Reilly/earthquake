/**
 * 预警「AI 分析详情」Tab。
 *
 * 数据来源于后端一次聚合接口 GET /api/warnings/{id}/analysis-detail：
 *   - AI 研判结论：综合评分 / 维度 / 推理链 / 建议动作 / 灾害类型 / 模型与时间；
 *   - 事发前后关键指标：触发时刻 ±2h 的真实 device_metric 时序（缺失时回退最新快照并明确标注）；
 *   - 现场影像：灾害点 / 预警附件图片 + 关联设备最近上传的 MinIO 影像。
 *
 * 所有数据缺失时显示"暂无数据"，不用假数据占位。
 */
<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { Brain, Camera, Gauge, Loader2, RefreshCw, TriangleAlert } from 'lucide-vue-next'
import { dashboardApi } from '../../services/dashboardApi'

const props = defineProps({
  warningId: { type: [String, Number], required: true },
})

const loading = ref(true)
const error = ref('')
const data = ref(null)
const brokenImages = ref(new Set())
const IMAGE_FALLBACK = '影像地址已过期或对象不存在（可在灾害点详情重新上传）'

/** 指标编码 → 中文名 / 单位（与 device_metric 真实编码对齐）。 */
const METRIC_META = {
  disp_rate: { label: '位移速率', unit: 'mm/d' },
  displacement_rate: { label: '位移速率', unit: 'mm/d' },
  displacement_total: { label: '累计位移', unit: 'mm' },
  crack_width: { label: '裂缝宽度', unit: 'mm' },
  crack_width_rate: { label: '裂缝扩展速率', unit: 'mm/d' },
  rainfall_1h: { label: '1 小时降雨', unit: 'mm' },
  rainfall_6h: { label: '6 小时降雨', unit: 'mm' },
  rainfall_24h: { label: '24 小时降雨', unit: 'mm' },
  rainfall_7d: { label: '7 天累计降雨', unit: 'mm' },
  rainfall_30d: { label: '30 天累计降雨', unit: 'mm' },
  soil_moisture: { label: '土壤含水率', unit: '%' },
  pore_pressure: { label: '孔隙水压力', unit: 'kPa' },
  flow_rate: { label: '水流速率', unit: 'm³/s' },
  slope: { label: '坡度', unit: '°' },
  elevation_m: { label: '海拔', unit: 'm' },
  temperature: { label: '温度', unit: '℃' },
  tilt: { label: '倾角', unit: '°' },
}

const AI_BANDS = [
  { min: 80, label: '红色警报', color: '#c84a4a' },
  { min: 60, label: '橙色预警', color: '#d98324' },
  { min: 35, label: '黄色提示', color: '#c9a227' },
  { min: 0, label: '蓝色关注', color: '#3f6fa0' },
]

const ai = computed(() => data.value?.ai ?? null)
const metrics = computed(() => data.value?.metrics ?? { series: [], fallback: false })
const images = computed(() => data.value?.images ?? [])
const warning = computed(() => data.value?.warning ?? null)

const aiScore = computed(() => {
  const raw = ai.value?.riskScore ?? ai.value?.score
  const value = Number(raw)
  return Number.isFinite(value) ? value : null
})
const aiBand = computed(() => {
  const score = aiScore.value
  return AI_BANDS.find((band) => score !== null && score >= band.min) ?? null
})
const aiLevelLabel = computed(() => {
  const level = String(ai.value?.riskLevel ?? '').toUpperCase()
  const map = { CRITICAL: '极高风险', HIGH: '高风险', MEDIUM: '中风险', LOW: '低风险' }
  return ai.value?.levelLabel || map[level] || '—'
})
const concernLabel = computed(() => ai.value?.concernLabel || ai.value?.classicLevel || '—')
const disasterType = computed(() => {
  const raw = ai.value?.disasterType
  if (!raw) return '—'
  return typeof raw === 'object' ? raw.disasterType || raw.label || '—' : String(raw)
})
const analyzedAt = computed(() => formatTime(ai.value?.analyzedAt))
const reasoning = computed(() => (Array.isArray(ai.value?.reasoning) ? ai.value.reasoning : []))
const suggestedActions = computed(() =>
  Array.isArray(ai.value?.suggestedActions) ? ai.value.suggestedActions : [],
)
const dimensions = computed(() => {
  const dims = ai.value?.dimensions
  const entries = []
  if (!dims || typeof dims !== 'object') return entries
  const mapping = {
    image: '图像识别',
    precipitation: '气象降水',
    geographic: '地理环境',
    device: '设备监测',
    sensor: '地面传感',
    terrain: '地形地质',
    historical: '历史灾情',
    meteorology: '气象条件',
    social_media: '社媒舆情',
    remote_sensing: '遥感影像',
    supporting: '辅助数据',
  }
  for (const [key, value] of Object.entries(dims)) {
    if (!value || typeof value !== 'object') continue
    const entry = {
      key,
      label: mapping[key] || key,
      score: value.score ?? value.riskScore ?? value.value ?? null,
      weight: value.weight ?? null,
      note:
        value.note ||
        value.reason ||
        value.status ||
        (value.available === false ? '数据不可用（已降级）' : ''),
    }
    if (entry.score !== null || entry.note || entry.weight !== null) entries.push(entry)
  }
  return entries
})
const fallbackMetrics = computed(() => Boolean(metrics.value?.fallback))

const formattedWindow = computed(() => {
  const m = metrics.value
  if (!m?.from || !m?.to) return ''
  return `${formatTime(m.from)} 至 ${formatTime(m.to)}`
})

/** 指标中文名与单位。 */
function metricMeta(code) {
  return METRIC_META[code] ?? { label: code, unit: '' }
}

/** 触发时刻前后数值与变化率。 */
function seriesDelta(series) {
  const before = series?.before?.v
  const after = series?.after?.v
  if (typeof before !== 'number' || typeof after !== 'number') return null
  const diff = after - before
  const ratio = before === 0 ? null : (diff / Math.abs(before)) * 100
  return { before, after, diff, ratio }
}

/** 把样本序列映射成 SVG polyline 的 points 字符串。 */
function sparkPoints(series) {
  const samples = Array.isArray(series?.samples) ? series.samples : []
  if (samples.length === 0) return ''
  const values = samples.map((point) => Number(point.v)).filter((value) => Number.isFinite(value))
  if (values.length === 0) return ''
  let min = Math.min(...values)
  let max = Math.max(...values)
  if (min === max) {
    min -= 0.5
    max += 0.5
  }
  const span = max - min
  return samples
    .map((point, index) => {
      const x = samples.length === 1 ? 50 : (index / (samples.length - 1)) * 100
      const y = 34 - ((Number(point.v) - min) / span) * 30
      return `${x.toFixed(2)},${y.toFixed(2)}`
    })
    .join(' ')
}

/** 触发时刻在 SVG 中的横坐标（不在窗口内时不画）。 */
function triggerX(series) {
  const samples = Array.isArray(series?.samples) ? series.samples : []
  const focus = Number(metrics.value?.focusAt ? Date.parse(metrics.value.focusAt) : NaN)
  if (!samples.length || !Number.isFinite(focus)) return null
  const times = samples.map((point) => Number(point.t))
  const min = Math.min(...times)
  const max = Math.max(...times)
  if (focus < min || focus > max || min === max) return null
  return ((focus - min) / (max - min)) * 100
}

function formatValue(value) {
  const number = Number(value)
  if (!Number.isFinite(number)) return '—'
  if (Math.abs(number) >= 100) return number.toFixed(0)
  if (Math.abs(number) >= 1) return number.toFixed(2)
  return number.toFixed(3)
}

function formatRatio(ratio) {
  if (ratio === null || ratio === undefined) return ''
  const sign = ratio >= 0 ? '+' : ''
  return `${sign}${ratio.toFixed(1)}%`
}

function formatTime(raw) {
  if (!raw) return '—'
  const date = new Date(raw)
  if (Number.isNaN(date.getTime())) return String(raw)
  return date.toLocaleString('zh-CN', { hour12: false })
}

function markBroken(index) {
  const next = new Set(brokenImages.value)
  next.add(index)
  brokenImages.value = next
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    data.value = await dashboardApi.getWarningAnalysisDetail(props.warningId)
  } catch (e) {
    data.value = null
    error.value = e?.message ?? '分析详情加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(() => props.warningId, load)
</script>

<template>
  <div class="analysis-detail">
    <div v-if="loading" class="state-card">
      <Loader2 :size="18" class="spin" />
      <span>正在加载 AI 分析详情…</span>
    </div>

    <div v-else-if="error" class="state-card error">
      <TriangleAlert :size="18" />
      <span>{{ error }}</span>
      <button type="button" class="retry-btn" @click="load">
        <RefreshCw :size="14" />重试
      </button>
    </div>

    <template v-else>
      <!-- ── 1. AI 研判结论 ── -->
      <section class="analysis-card">
        <header class="card-head">
          <h3><Brain :size="17" />AI 研判结论</h3>
          <div class="head-tags">
            <span v-if="ai?.degraded" class="tag warn">降级/兜底</span>
            <span v-if="ai?.model" class="tag">{{ ai.model }}</span>
            <span v-if="analyzedAt !== '—'" class="tag">分析于 {{ analyzedAt }}</span>
          </div>
        </header>

        <div v-if="!ai" class="empty-inline">
          暂无 AI 研判结果：该灾害点尚未生成综合分析（可等待下一次 5 分钟落库，或在 AI 洞察页立即重算）。
        </div>

        <div v-else class="conclusion-body">
          <div
            class="score-ring"
            :style="{ borderColor: aiBand?.color ?? '#8a9a92', color: aiBand?.color ?? '#4a5a52' }"
          >
            <strong>{{ aiScore === null ? '—' : formatValue(aiScore) }}</strong>
            <small>/ 100</small>
            <em>{{ aiBand?.label ?? aiLevelLabel }}</em>
          </div>

          <div class="conclusion-main">
            <div class="conclusion-line">
              <strong>研判等级：</strong>
              <span :style="{ color: aiBand?.color ?? 'inherit' }">{{ aiLevelLabel }}</span>
              <span class="dot">·</span>
              <span>关注度：{{ concernLabel }}</span>
              <span class="dot">·</span>
              <span>灾害类型：{{ disasterType }}</span>
              <span v-if="ai.confidence != null" class="dot">·</span>
              <span v-if="ai.confidence != null">
                置信度 {{ (Number(ai.confidence) * 100).toFixed(0) }}%
              </span>
            </div>
            <p class="conclusion-note">
              该预警由 AI 综合研判产出，分数区间与预警颜色一致：≥80 红、60–80 橙、35–60 黄、&lt;35 蓝。
              预警触发值：<strong>{{ warning?.triggerValue || '—' }}</strong>
              ，触发时间：<strong>{{ formatTime(warning?.triggeredAt) }}</strong>。
            </p>
            <div v-if="dimensions.length" class="dimension-grid">
              <div v-for="dim in dimensions" :key="dim.key" class="dimension-item">
                <span class="dimension-label">{{ dim.label }}</span>
                <strong>{{ dim.score === null ? '—' : formatValue(dim.score) }}</strong>
                <small v-if="dim.weight != null">权重 {{ (Number(dim.weight) * 100).toFixed(0) }}%</small>
                <small v-if="dim.note">{{ dim.note }}</small>
              </div>
            </div>
          </div>
        </div>
      </section>

      <!-- ── 2. AI 详细分析：推理链 + 建议动作 ── -->
      <section v-if="reasoning.length || suggestedActions.length" class="analysis-card">
        <header class="card-head">
          <h3>AI 详细分析</h3>
          <span class="sub">数据来源：综合研判模型 reasoning / suggestedActions</span>
        </header>
        <div class="analysis-columns">
          <div class="analysis-block">
            <h4>研判依据</h4>
            <ol v-if="reasoning.length" class="reason-list">
              <li v-for="(item, index) in reasoning" :key="'r-' + index">{{ item }}</li>
            </ol>
            <p v-else class="empty-inline">暂无推理链明细。</p>
          </div>
          <div class="analysis-block">
            <h4>建议处置动作</h4>
            <ol v-if="suggestedActions.length" class="reason-list">
              <li v-for="(item, index) in suggestedActions" :key="'a-' + index">{{ item }}</li>
            </ol>
            <p v-else class="empty-inline">暂无建议动作。</p>
          </div>
        </div>
      </section>

      <!-- ── 3. 事发前后关键指标 ── -->
      <section class="analysis-card">
        <header class="card-head">
          <h3><Gauge :size="17" />事发前后关键指标</h3>
          <span v-if="formattedWindow" class="sub">观察窗口 {{ formattedWindow }}（触发时刻 ±2h）</span>
        </header>
        <div v-if="fallbackMetrics" class="fallback-note">
          触发时刻 ±2 小时内无真实监测样本，以下为该灾害点各指标的最新快照（仅作参考，不构成事前/事后对比）。
        </div>
        <div v-if="!metrics.series?.length" class="empty-inline">
          该灾害点暂无关联设备指标数据。
        </div>
        <div v-else class="metric-list">
          <article v-for="series in metrics.series" :key="series.metricCode" class="metric-item">
            <div class="metric-head">
              <strong>{{ metricMeta(series.metricCode).label }}</strong>
              <span class="metric-code">{{ series.metricCode }} · {{ series.deviceCount }} 台设备</span>
            </div>
            <svg class="spark" viewBox="0 0 100 36" preserveAspectRatio="none" aria-hidden="true">
              <line x1="0" y1="34" x2="100" y2="34" stroke="#e3e9e5" stroke-width="1" />
              <polyline
                v-if="sparkPoints(series)"
                :points="sparkPoints(series)"
                fill="none"
                stroke="#3f6fa0"
                stroke-width="1.6"
                vector-effect="non-scaling-stroke"
              />
              <line
                v-if="triggerX(series) !== null"
                :x1="triggerX(series)"
                y1="2"
                :x2="triggerX(series)"
                y2="34"
                stroke="#c84a4a"
                stroke-dasharray="2 2"
                stroke-width="1.2"
                vector-effect="non-scaling-stroke"
              />
            </svg>
            <div class="metric-stats">
              <span>
                触发前
                <strong>{{ series.before ? formatValue(series.before.v) : '—' }}</strong>
                {{ metricMeta(series.metricCode).unit }}
              </span>
              <span>
                触发后
                <strong>{{ series.after ? formatValue(series.after.v) : '—' }}</strong>
                {{ series.after ? metricMeta(series.metricCode).unit : '（暂无后续样本）' }}
              </span>
              <span v-if="seriesDelta(series)" :class="{ up: seriesDelta(series).diff > 0, down: seriesDelta(series).diff < 0 }">
                变化 {{ formatRatio(seriesDelta(series).ratio) }}
              </span>
              <span class="muted">
                最小 {{ formatValue(series.min) }} / 平均 {{ formatValue(series.avg) }} / 最大
                {{ formatValue(series.max) }}
              </span>
            </div>
          </article>
        </div>
      </section>

      <!-- ── 4. 现场影像 ── -->
      <section class="analysis-card">
        <header class="card-head">
          <h3><Camera :size="17" />现场影像 / 附件</h3>
          <span class="sub">来源：灾害点/预警附件图片、关联设备最近上传影像</span>
        </header>
        <div v-if="!images.length" class="empty-inline">
          暂无现场影像。可在「灾害点详情」上传现场照片，AI 图像识别后会出现在这里。
        </div>
        <div v-else class="image-grid">
          <figure v-for="(image, index) in images" :key="image.url || index" class="image-item">
            <img
              v-if="!brokenImages.has(index) && image.url"
              :src="image.url"
              :alt="image.name || '现场影像'"
              loading="lazy"
              @error="markBroken(index)"
            />
            <div v-else class="image-fallback">
              <Camera :size="22" />
              <span>{{ IMAGE_FALLBACK }}</span>
            </div>
            <figcaption>
              <span class="image-name">{{ image.name || '未命名影像' }}</span>
              <span class="image-meta">
                {{ image.source === 'device' ? '设备影像' : image.source === 'warning' ? '预警附件' : '灾害点附件' }}
                <template v-if="image.capturedAt"> · {{ formatTime(image.capturedAt) }}</template>
              </span>
            </figcaption>
          </figure>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.analysis-detail {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.state-card {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 18px;
  font-size: 13px;
  color: var(--c-text-2);
  background: var(--c-surface);
  border: 1px solid var(--c-separator);
  border-radius: var(--r-md);
}

.state-card.error {
  color: #a83a2f;
  background: #fdf1ef;
  border-color: #f3c9c3;
}

.retry-btn {
  margin-left: auto;
  padding: 5px 12px;
  font-size: 12px;
  cursor: pointer;
  background: #fff;
  border: 1px solid currentcolor;
  border-radius: 999px;
}

.analysis-card {
  padding: 18px 20px;
  background: var(--c-surface);
  border: 1px solid var(--c-separator);
  border-radius: var(--r-md);
  box-shadow: var(--shadow-xs);
}

.card-head {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 14px;
  align-items: center;
  margin-bottom: 14px;
}

.card-head h3 {
  display: inline-flex;
  gap: 7px;
  align-items: center;
  margin: 0;
  font-size: 15px;
  font-weight: 800;
  color: var(--c-text);
}

.card-head .sub {
  font-size: 12px;
  color: var(--c-text-3);
}

.head-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-left: auto;
}

.tag {
  padding: 2px 9px;
  font-size: 11px;
  color: var(--c-text-2);
  background: var(--c-bg);
  border: 1px solid var(--c-separator);
  border-radius: 999px;
}

.tag.warn {
  color: #8a4a05;
  background: #fdf1df;
  border-color: #f0d5ae;
}

.conclusion-body {
  display: flex;
  gap: 22px;
  align-items: flex-start;
}

.score-ring {
  display: flex;
  flex-direction: column;
  flex: 0 0 108px;
  align-items: center;
  justify-content: center;
  width: 108px;
  height: 108px;
  border: 5px solid currentcolor;
  border-radius: 50%;
}

.score-ring strong {
  font-size: 26px;
  font-weight: 900;
  line-height: 1;
}

.score-ring small {
  margin-top: 2px;
  font-size: 11px;
  opacity: 0.75;
}

.score-ring em {
  margin-top: 4px;
  font-size: 12px;
  font-style: normal;
  font-weight: 700;
}

.conclusion-main {
  flex: 1;
  min-width: 0;
}

.conclusion-line {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 6px;
  align-items: center;
  font-size: 13px;
  color: var(--c-text);
}

.conclusion-line .dot {
  color: var(--c-text-3);
}

.conclusion-note {
  margin: 10px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--c-text-2);
}

.dimension-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 8px;
  margin-top: 12px;
}

.dimension-item {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 9px 11px;
  background: var(--c-bg);
  border: 1px solid var(--c-separator);
  border-radius: var(--r-sm);
}

.dimension-label {
  font-size: 11px;
  color: var(--c-text-3);
}

.dimension-item strong {
  font-size: 16px;
}

.dimension-item small {
  font-size: 11px;
  color: var(--c-text-3);
}

.analysis-columns {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}

.analysis-block h4 {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 800;
}

.reason-list {
  padding-left: 18px;
  margin: 0;
  font-size: 12px;
  line-height: 1.8;
  color: var(--c-text-2);
}

.empty-inline {
  padding: 10px 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--c-text-3);
}

.fallback-note {
  padding: 9px 12px;
  margin-bottom: 12px;
  font-size: 12px;
  line-height: 1.6;
  color: #8a5a0a;
  background: #fdf6e8;
  border: 1px solid #eed9b0;
  border-radius: var(--r-sm);
}

.metric-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 12px;
}

.metric-item {
  padding: 12px;
  background: var(--c-bg);
  border: 1px solid var(--c-separator);
  border-radius: var(--r-sm);
}

.metric-head {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 8px;
  align-items: baseline;
  justify-content: space-between;
}

.metric-head strong {
  font-size: 13px;
}

.metric-code {
  font-size: 11px;
  color: var(--c-text-3);
}

.spark {
  width: 100%;
  height: 54px;
  margin: 8px 0 6px;
}

.metric-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  font-size: 11px;
  color: var(--c-text-2);
}

.metric-stats strong {
  font-size: 12px;
}

.metric-stats .up {
  color: #c84a4a;
  font-weight: 700;
}

.metric-stats .down {
  color: #2d8280;
  font-weight: 700;
}

.muted {
  color: var(--c-text-3);
}

.image-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 12px;
}

.image-item {
  margin: 0;
  overflow: hidden;
  background: var(--c-bg);
  border: 1px solid var(--c-separator);
  border-radius: var(--r-sm);
}

.image-item img {
  display: block;
  width: 100%;
  height: 150px;
  object-fit: cover;
  background: #eef2ef;
}

.image-fallback {
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: center;
  justify-content: center;
  height: 150px;
  padding: 12px;
  font-size: 11px;
  line-height: 1.5;
  color: var(--c-text-3);
  text-align: center;
  background: #f4f7f5;
}

.image-item figcaption {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px;
}

.image-name {
  overflow: hidden;
  font-size: 12px;
  font-weight: 700;
  color: var(--c-text);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.image-meta {
  font-size: 11px;
  color: var(--c-text-3);
}

.spin {
  animation: spin 0.9s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 900px) {
  .conclusion-body {
    flex-direction: column;
  }

  .analysis-columns {
    grid-template-columns: 1fr;
  }
}
</style>
