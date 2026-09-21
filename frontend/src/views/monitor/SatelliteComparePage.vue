<script setup>
/**
 * 时相对比卫星影像工作台（P0 新增）。
 *
 * 三种对比模式：
 *   1. 卷帘（swipe）：两张图叠放，中间竖线可拖动
 *   2. 闪烁（flicker）：左右自动切换（约 1.2s/次）
 *   3. 并排（side-by-side）：左右各一张
 *
 * 叠加层：
 *   - 差异热力图（PNG），可调 alpha
 *   - 热点矩形标记（Top 3）
 *
 * 工作流：
 *   提交 → 灾前合成 → 灾后合成 → 差异计算 → 结果渲染
 */

import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  Radar,
  AlertTriangle,
  ArrowLeftRight,
  Check,
  ChevronLeft,
  ChevronRight,
  CircleHelp,
  Columns2,
  Download,
  Eye,
  EyeOff,
  Flame,
  Gauge,
  Layers,
  Loader2,
  MapPin,
  Pause,
  Play,
  RefreshCw,
  Save,
  Target,
  Wand2,
  X,
  Zap,
} from 'lucide-vue-next'
import { dashboardApi } from '../../services/dashboardApi'
import {
  describeDiffJobStatus,
  hotspotToLonLat,
  resolveDiffTileUrl,
  runCompareWorkflow,
} from '../../services/satelliteCompareService'
import { describeJobStatus } from '../../services/sentinelService'

const emit = defineEmits(['notify'])
const route = useRoute()

// AOI 预设：常用灾区坐标
const AOI_PRESETS = [
  { id: 'jiuzhaigou', name: '九寨沟', bbox: [103.85, 33.10, 104.10, 33.35] },
  { id: 'wenchuan',   name: '汶川',   bbox: [103.30, 30.80, 103.80, 31.20] },
  { id: 'yushu',      name: '玉树',   bbox: [ 96.80, 32.80,  97.40, 33.20] },
  { id: 'ludian',     name: '鲁甸',   bbox: [103.50, 27.00, 103.85, 27.25] },
  { id: 'yaan',       name: '雅安',   bbox: [102.80, 29.90, 103.20, 30.25] },
]

const FORM_PRESETS = {
  '1m':  { days: 30 },
  '3m':  { days: 90 },
  '6m':  { days: 180 },
  '12m': { days: 365 },
  '24m': { days: 730 },
}

// 表单状态
const form = reactive({
  aoiPreset: 'wenchuan',
  bbox: [103.30, 30.80, 103.80, 31.20],
  beforePreset: '12m',
  afterPreset: '1m',
  beforeStart: '',
  beforeEnd: '',
  afterStart: '',
  afterEnd: '',
  threshold: 30,
  colormap: 'inferno',
  maxCloud: 15,
})

const compareMode = ref('swipe')  // swipe | flicker | sidebyside
const showHeatmap = ref(true)
const showHotspots = ref(true)
const heatmapOpacity = ref(0.65)
const swipePosition = ref(50)
const flickerSide = ref('before')  // before | after
const flickerPlaying = ref(true)
const flickerInterval = ref(null)

// 任务状态
const composeBefore = ref(null)
const composeAfter = ref(null)
const diffJob = ref(null)
const diffResult = ref(null)
const loading = ref(false)
const errorMessage = ref('')
const exportFormat = ref('png')

// 工具函数：日期范围
function dateOffsetDays(days) {
  const d = new Date()
  d.setDate(d.getDate() - days)
  return d.toISOString().slice(0, 10)
}

function getRange(presetKey, anchorEnd) {
  const cfg = FORM_PRESETS[presetKey]
  if (!cfg) return { start: '', end: '' }
  const end = anchorEnd || dateOffsetDays(0)
  const start = dateOffsetDays(cfg.days)
  return { start, end }
}

function applyPresetAOI(presetId) {
  const preset = AOI_PRESETS.find((p) => p.id === presetId)
  if (!preset) return
  form.aoiPreset = presetId
  form.bbox = [...preset.bbox]
}

function applyPresetBefore(key) {
  form.beforePreset = key
  if (key !== 'custom') {
    const { start, end } = getRange(key, form.afterStart || undefined)
    form.beforeStart = start
    form.beforeEnd = end
  }
}

function applyPresetAfter(key) {
  form.afterPreset = key
  if (key !== 'custom') {
    const { start, end } = getRange(key)
    form.afterStart = start
    form.afterEnd = end
  }
}

watch(() => form.afterStart, () => {
  if (form.beforePreset !== 'custom') applyPresetBefore(form.beforePreset)
})

onMounted(() => {
  applyPresetBefore(form.beforePreset)
  applyPresetAfter(form.afterPreset)
})

// 提交工作流
async function submitCompare() {
  if (loading.value) return
  errorMessage.value = ''
  composeBefore.value = null
  composeAfter.value = null
  diffJob.value = null
  diffResult.value = null
  loading.value = true

  try {
    const result = await runCompareWorkflow(
      {
        bbox: form.bbox,
        beforeWindow: { start: form.beforeStart, end: form.beforeEnd },
        afterWindow: { start: form.afterStart, end: form.afterEnd },
        threshold: form.threshold,
        colormap: form.colormap,
        maxCloud: form.maxCloud,
      },
      {
        onComposeProgress: (stage, job) => {
          if (stage === 'before') composeBefore.value = job
          else composeAfter.value = job
        },
        onDiffProgress: (job) => {
          diffJob.value = job
        },
      },
    )
    diffResult.value = result.diff
    diffJob.value = { jobId: result.diffJobId, status: 'succeeded', progress: 100 }
    emit?.('notify', '时相对比完成')
  } catch (e) {
    errorMessage.value = e?.message || String(e)
  } finally {
    loading.value = false
  }
}

// 闪烁模式自动播放
function startFlicker() {
  stopFlicker()
  flickerInterval.value = setInterval(() => {
    flickerSide.value = flickerSide.value === 'before' ? 'after' : 'before'
  }, 1200)
}

function stopFlicker() {
  if (flickerInterval.value) {
    clearInterval(flickerInterval.value)
    flickerInterval.value = null
  }
}

watch(compareMode, (mode) => {
  if (mode === 'flicker') {
    if (flickerPlaying.value) startFlicker()
  } else {
    stopFlicker()
  }
})

watch(flickerPlaying, (playing) => {
  if (compareMode.value === 'flicker') {
    if (playing) startFlicker()
    else stopFlicker()
  }
})

onBeforeUnmount(() => stopFlicker())

// 计算属性：进度 / 状态文案 / 图像 URL
const beforeStatus = computed(() => describeJobStatus(composeBefore.value))
const afterStatus = computed(() => describeJobStatus(composeAfter.value))
const diffStatus = computed(() => describeDiffJobStatus(diffJob.value))

const beforeUrl = computed(() => resolveDiffTileUrl(composeBefore.value?.result?.s2Url))
const afterUrl = computed(() => resolveDiffTileUrl(composeAfter.value?.result?.s2Url))
const diffUrl = computed(() => resolveDiffTileUrl(diffResult.value?.diffUrl))

const changePercent = computed(() => diffResult.value?.changePercent ?? 0)
const changePixels = computed(() => diffResult.value?.changePixels ?? 0)
const totalPixels = computed(() => diffResult.value?.totalPixels ?? 0)
const imageSize = computed(() => diffResult.value?.imageSize || { width: 0, height: 0 })
const hotspots = computed(() => diffResult.value?.hotspots ?? [])

const hotspotsWithGeo = computed(() => {
  return hotspots.value.map((h) => ({
    ...h,
    geo: hotspotToLonLat(h, form.bbox),
  }))
})

const bboxValid = computed(() => {
  const [a, b, c, d] = form.bbox
  return Number.isFinite(a) && Number.isFinite(b) && Number.isFinite(c) && Number.isFinite(d)
    && a < c && b < d
})

const canSubmit = computed(() => !loading.value && bboxValid.value
  && form.beforeStart && form.beforeEnd && form.afterStart && form.afterEnd
  && form.beforeEnd < form.afterStart)

// 画布交互：卷帘位置拖动
const canvasRef = ref(null)
const dragging = ref(false)

function pointerToPercent(evt) {
  const el = canvasRef.value
  if (!el) return 50
  const rect = el.getBoundingClientRect()
  const x = (evt.clientX || (evt.touches && evt.touches[0]?.clientX) || 0) - rect.left
  return Math.max(0, Math.min(100, (x / rect.width) * 100))
}

function onPointerDown(evt) {
  if (compareMode.value !== 'swipe') return
  dragging.value = true
  swipePosition.value = pointerToPercent(evt)
}

function onPointerMove(evt) {
  if (!dragging.value) return
  swipePosition.value = pointerToPercent(evt)
}

function onPointerUp() {
  dragging.value = false
}

// 导出 PNG
async function exportComparison() {
  if (!canvasRef.value || !beforeUrl.value || !afterUrl.value) {
    errorMessage.value = '没有可导出的对比图，请先运行对比'
    return
  }
  try {
    const canvas = document.createElement('canvas')
    const width = 1024
    const height = 512
    canvas.width = width
    canvas.height = height
    const ctx = canvas.getContext('2d')

    const [imgA, imgB] = await Promise.all([
      loadImageToCanvas(beforeUrl.value),
      loadImageToCanvas(afterUrl.value),
    ])

    // 左侧画灾前
    ctx.drawImage(imgA, 0, 0, width / 2, height)
    // 右侧画灾后
    ctx.drawImage(imgB, width / 2, 0, width / 2, height)
    // 中间分隔线
    ctx.fillStyle = '#ffffff'
    ctx.fillRect(width / 2 - 1, 0, 2, height)
    ctx.font = 'bold 16px sans-serif'
    ctx.fillStyle = '#000000'
    ctx.fillText('灾前', 20, 30)
    ctx.fillText('灾后', width / 2 + 20, 30)

    canvas.toBlob((blob) => {
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `satellite-compare-${Date.now()}.${exportFormat.value}`
      a.click()
      URL.revokeObjectURL(url)
    }, exportFormat.value === 'png' ? 'image/png' : 'image/jpeg', 0.92)
    emit?.('notify', '对比图已导出')
  } catch (e) {
    errorMessage.value = '导出失败：' + (e?.message || String(e))
  }
}

function loadImageToCanvas(url) {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.crossOrigin = 'anonymous'
    img.onload = () => resolve(img)
    img.onerror = () => reject(new Error(`图片加载失败：${url}`))
    img.src = url
  })
}

// 模式切换
function setCompareMode(mode) {
  compareMode.value = mode
}

function resetAll() {
  composeBefore.value = null
  composeAfter.value = null
  diffJob.value = null
  diffResult.value = null
  errorMessage.value = ''
}
</script>

<template>
  <div class="satellite-compare-page">
    <!-- 顶部标题 -->
    <header class="cmp-header">
      <div class="cmp-header-main">
        <div class="cmp-title">
          <Layers :size="22" />
        </div>
        <div class="cmp-actions">
          <button
            class="cmp-btn cmp-btn-primary"
            :disabled="!canSubmit"
            @click="submitCompare"
          >
            <RefreshCw v-if="loading" :size="16" class="spin" />
            <Wand2 v-else :size="16" />
            <span>{{ loading ? '运行中…' : '运行对比' }}</span>
          </button>
          <button class="cmp-btn" :disabled="!diffResult" @click="exportComparison">
            <Download :size="16" />
            <span>导出对比图</span>
          </button>
          <button class="cmp-btn cmp-btn-ghost" :disabled="loading" @click="resetAll">
            <X :size="16" />
            <span>清空</span>
          </button>
        </div>
      </div>

      <!-- 工具栏：表单 -->
      <div class="cmp-toolbar">
        <div class="cmp-toolbar-group">
          <label class="cmp-label">AOI 区域</label>
          <div class="cmp-row">
            <select v-model="form.aoiPreset" class="cmp-select" @change="applyPresetAOI(form.aoiPreset)">
              <option v-for="p in AOI_PRESETS" :key="p.id" :value="p.id">{{ p.name }}</option>
            </select>
            <span class="cmp-bbox-text">{{ form.bbox.map(v => v.toFixed(2)).join(' , ') }}</span>
          </div>
        </div>

        <div class="cmp-toolbar-group">
          <label class="cmp-label">灾前时段</label>
          <div class="cmp-row">
            <select v-model="form.beforePreset" class="cmp-select" @change="applyPresetBefore(form.beforePreset)">
              <option value="1m">近 1 个月</option>
              <option value="3m">近 3 个月</option>
              <option value="6m">近 6 个月</option>
              <option value="12m">近 12 个月</option>
              <option value="24m">近 24 个月</option>
              <option value="custom">自定义</option>
            </select>
            <input v-model="form.beforeStart" type="date" class="cmp-input" :disabled="form.beforePreset !== 'custom'" />
            <span class="cmp-input-sep">至</span>
            <input v-model="form.beforeEnd" type="date" class="cmp-input" :disabled="form.beforePreset !== 'custom'" />
          </div>
        </div>

        <div class="cmp-toolbar-group">
          <label class="cmp-label">灾后时段</label>
          <div class="cmp-row">
            <select v-model="form.afterPreset" class="cmp-select" @change="applyPresetAfter(form.afterPreset)">
              <option value="1m">近 1 个月</option>
              <option value="3m">近 3 个月</option>
              <option value="6m">近 6 个月</option>
              <option value="12m">近 12 个月</option>
              <option value="24m">近 24 个月</option>
              <option value="custom">自定义</option>
            </select>
            <input v-model="form.afterStart" type="date" class="cmp-input" :disabled="form.afterPreset !== 'custom'" />
            <span class="cmp-input-sep">至</span>
            <input v-model="form.afterEnd" type="date" class="cmp-input" :disabled="form.afterPreset !== 'custom'" />
          </div>
        </div>

        <div class="cmp-toolbar-group">
          <label class="cmp-label">差异参数</label>
          <div class="cmp-row">
            <label class="cmp-inline">
              <span>阈值</span>
              <input v-model.number="form.threshold" type="range" min="5" max="120" step="1" />
              <strong>{{ form.threshold }}</strong>
            </label>
            <select v-model="form.colormap" class="cmp-select">
              <option value="inferno">inferno</option>
              <option value="viridis">viridis</option>
              <option value="red">red</option>
              <option value="grayscale">grayscale</option>
            </select>
            <label class="cmp-inline">
              <span>最大云量</span>
              <input v-model.number="form.maxCloud" type="number" min="0" max="100" />
              <strong>%</strong>
            </label>
          </div>
        </div>
      </div>
    </header>

    <section class="cmp-status-bar">
      <div class="cmp-status-card">
        <span class="cmp-status-label">灾前合成</span>
        <span :class="['cmp-status-tag', `tone-${beforeStatus.tone}`]">{{ beforeStatus.label }}</span>
        <div class="cmp-progress"><div class="cmp-progress-bar" :style="{ width: beforeStatus.percent + '%' }"></div></div>
      </div>
      <div class="cmp-status-card">
        <span class="cmp-status-label">灾后合成</span>
        <span :class="['cmp-status-tag', `tone-${afterStatus.tone}`]">{{ afterStatus.label }}</span>
        <div class="cmp-progress"><div class="cmp-progress-bar" :style="{ width: afterStatus.percent + '%' }"></div></div>
      </div>
      <div class="cmp-status-card">
        <span class="cmp-status-label">差异计算</span>
        <span :class="['cmp-status-tag', `tone-${diffStatus.tone}`]">{{ diffStatus.label }}</span>
        <div class="cmp-progress"><div class="cmp-progress-bar" :style="{ width: diffStatus.percent + '%' }"></div></div>
      </div>
    </section>

    <!-- 错误提示 -->
    <div v-if="errorMessage" class="cmp-error">
      <AlertTriangle :size="16" />
      <span>{{ errorMessage }}</span>
      <button class="cmp-btn cmp-btn-ghost cmp-btn-sm" @click="errorMessage = ''">
        <X :size="14" />
      </button>
    </div>

    <!-- 主体：画布 + 侧栏 -->
    <div class="cmp-main">
      <section class="cmp-canvas-section">
        <!-- 模式切换 -->
        <div class="cmp-mode-tabs">
          <button
            v-for="m in [
              { id: 'swipe',     label: '卷帘', icon: ArrowLeftRight },
              { id: 'flicker',   label: '闪烁', icon: Radar },
              { id: 'sidebyside', label: '并排', icon: Columns2 },
            ]"
            :key="m.id"
            :class="['cmp-mode-tab', { active: compareMode === m.id }]"
            @click="setCompareMode(m.id)"
          >
            <component :is="m.icon" :size="14" />
            <span>{{ m.label }}</span>
          </button>

          <div class="cmp-canvas-tools">
            <button
              class="cmp-btn cmp-btn-sm cmp-btn-ghost"
              :disabled="!diffResult"
              @click="showHeatmap = !showHeatmap"
              :title="showHeatmap ? '隐藏差异层' : '显示差异层'"
            >
              <component :is="showHeatmap ? Eye : EyeOff" :size="14" />
              <span>差异层</span>
            </button>
            <button
              class="cmp-btn cmp-btn-sm cmp-btn-ghost"
              :disabled="!diffResult"
              @click="showHotspots = !showHotspots"
            >
              <Target :size="14" />
              <span>热点</span>
            </button>
            <label v-if="diffResult" class="cmp-inline">
              <span>差异透明度</span>
              <input v-model.number="heatmapOpacity" type="range" min="0" max="1" step="0.05" />
              <strong>{{ Math.round(heatmapOpacity * 100) }}%</strong>
            </label>
            <button
              v-if="compareMode === 'flicker'"
              class="cmp-btn cmp-btn-sm"
              @click="flickerPlaying = !flickerPlaying"
            >
              <component :is="flickerPlaying ? Pause : Play" :size="14" />
              <span>{{ flickerPlaying ? '暂停' : '播放' }}</span>
            </button>
          </div>
        </div>

        <!-- 卷帘模式 -->
        <div
          v-show="compareMode === 'swipe'"
          ref="canvasRef"
          class="cmp-canvas cmp-canvas-swipe"
          :class="{ ready: beforeUrl && afterUrl }"
          @pointerdown="onPointerDown"
          @pointermove="onPointerMove"
          @pointerup="onPointerUp"
          @pointercancel="onPointerUp"
        >
          <template v-if="!beforeUrl || !afterUrl">
            <div class="cmp-canvas-empty">
              <Layers :size="48" />
              <p>请填写灾前 / 灾后时段并点击「运行对比」生成卫星图像</p>
            </div>
          </template>
          <template v-else>
            <img v-if="beforeUrl" :src="beforeUrl" class="cmp-layer cmp-layer-before" alt="灾前" />
            <img v-if="afterUrl" :src="afterUrl" class="cmp-layer cmp-layer-after" alt="灾后" />
            <div class="cmp-swipe-divider" :style="{ left: swipePosition + '%' }">
              <div class="cmp-swipe-handle">
                <ChevronLeft :size="14" />
                <ChevronRight :size="14" />
              </div>
            </div>
            <span class="cmp-tag cmp-tag-before">灾前</span>
            <span class="cmp-tag cmp-tag-after">灾后</span>
          </template>
        </div>

        <!-- ===== 闪烁模式 ===== -->
        <div v-show="compareMode === 'flicker'" class="cmp-canvas cmp-canvas-flicker">
          <template v-if="!beforeUrl || !afterUrl">
            <div class="cmp-canvas-empty">
              <Radar :size="48" />
              <p>请先生成卫星图像</p>
            </div>
          </template>
          <template v-else>
            <transition name="cmp-fade" mode="out-in">
              <img
                :key="flickerSide"
                :src="flickerSide === 'before' ? beforeUrl : afterUrl"
                class="cmp-layer-flicker"
                :alt="flickerSide === 'before' ? '灾前' : '灾后'"
              />
            </transition>
            <span class="cmp-tag cmp-tag-flicker">
              {{ flickerSide === 'before' ? '灾前' : '灾后' }}
            </span>
            <div class="cmp-flicker-buttons">
              <button class="cmp-btn cmp-btn-sm" @click="flickerSide = 'before'">灾前</button>
              <button class="cmp-btn cmp-btn-sm" @click="flickerSide = 'after'">灾后</button>
            </div>
          </template>
        </div>

        <!-- ===== 并排模式 ===== -->
        <div v-show="compareMode === 'sidebyside'" class="cmp-canvas cmp-canvas-side">
          <template v-if="!beforeUrl || !afterUrl">
            <div class="cmp-canvas-empty">
              <Columns2 :size="48" />
              <p>请先生成卫星图像</p>
            </div>
          </template>
          <template v-else>
            <figure class="cmp-side-figure">
              <img :src="beforeUrl" alt="灾前" />
              <figcaption>
                <span class="cmp-tag cmp-tag-before">灾前</span>
                <span>{{ form.beforeStart }} ~ {{ form.beforeEnd }}</span>
              </figcaption>
            </figure>
            <figure class="cmp-side-figure">
              <img :src="afterUrl" alt="灾后" />
              <figcaption>
                <span class="cmp-tag cmp-tag-after">灾后</span>
                <span>{{ form.afterStart }} ~ {{ form.afterEnd }}</span>
              </figcaption>
            </figure>
          </template>
        </div>

        <!-- ===== 差异热力图叠加 ===== -->
        <div
          v-if="diffUrl && showHeatmap"
          class="cmp-heatmap-overlay"
          :class="[`mode-${compareMode}`]"
        >
          <img :src="diffUrl" alt="差异热力图" :style="{ opacity: heatmapOpacity }" />
        </div>

        <!-- ===== 热点标记 ===== -->
        <div
          v-if="showHotspots && hotspotsWithGeo.length"
          class="cmp-hotspots-overlay"
          :class="[`mode-${compareMode}`]"
        >
          <div
            v-for="(h, idx) in hotspotsWithGeo"
            :key="`h-${idx}`"
            class="cmp-hotspot"
            :style="hotspotStyle(h, compareMode)"
            :title="`热点 #${idx + 1} 强度 ${(h.intensity * 100).toFixed(0)}%`"
          >
            <span class="cmp-hotspot-label">{{ idx + 1 }}</span>
          </div>
        </div>
      </section>

      <!-- ===== 右侧栏：统计 + 热点列表 ===== -->
      <aside class="cmp-side-panel">
        <article class="cmp-card">
          <header class="cmp-card-header">
            <Gauge :size="16" />
            <h3>差异统计</h3>
          </header>
          <div class="cmp-card-body">
            <div class="cmp-stat-row">
              <span class="cmp-stat-label">变化像素</span>
              <strong>{{ changePixels.toLocaleString() }} / {{ totalPixels.toLocaleString() }}</strong>
            </div>
            <div class="cmp-stat-row">
              <span class="cmp-stat-label">变化率</span>
              <strong :class="{ danger: changePercent > 5 }">{{ changePercent.toFixed(2) }}%</strong>
            </div>
            <div class="cmp-stat-row">
              <span class="cmp-stat-label">差异阈值</span>
              <strong>{{ form.threshold }}</strong>
            </div>
            <div class="cmp-stat-row">
              <span class="cmp-stat-label">配色</span>
              <strong>{{ form.colormap }}</strong>
            </div>
            <div class="cmp-stat-row">
              <span class="cmp-stat-label">图像尺寸</span>
              <strong>{{ imageSize.width }} × {{ imageSize.height }}</strong>
            </div>
          </div>
        </article>

        <article class="cmp-card">
          <header class="cmp-card-header">
            <Flame :size="16" />
            <h3>Top 差异热点</h3>
            <span class="cmp-badge">{{ hotspots.length }}</span>
          </header>
          <ul v-if="hotspots.length" class="cmp-hotspot-list">
            <li v-for="(h, idx) in hotspotsWithGeo" :key="`hl-${idx}`" class="cmp-hotspot-item">
              <div class="cmp-hotspot-rank">#{{ idx + 1 }}</div>
              <div class="cmp-hotspot-info">
                <div class="cmp-hotspot-coord" v-if="h.geo">
                  <MapPin :size="12" />
                  <code>{{ h.geo.lon.toFixed(4) }}, {{ h.geo.lat.toFixed(4) }}</code>
                </div>
                <div class="cmp-hotspot-meta">
                  强度 <strong>{{ (h.intensity * 100).toFixed(0) }}%</strong>
                  · 像素 <strong>{{ h.pixel_count }}</strong>
                </div>
              </div>
              <div class="cmp-hotspot-bar">
                <div class="cmp-hotspot-bar-fill" :style="{ width: (h.intensity * 100) + '%' }"></div>
              </div>
            </li>
          </ul>
          <div v-else class="cmp-empty">
            <CircleHelp :size="20" />
            <p>暂无差异热点。运行对比后会显示变化最显著的 Top 3 区域。</p>
          </div>
        </article>

        <article class="cmp-card">
          <header class="cmp-card-header">
            <Wand2 :size="16" />
            <h3>解读建议</h3>
          </header>
          <ul class="cmp-tips">
            <li><Zap :size="12" /> 变化率 > 5% 通常意味着地表发生显著变化（滑坡、洪水、新增建筑）。</li>
            <li><Target :size="12" /> Top 3 热点已映射回 AOI 经纬度，可直接转去实时地图查看。</li>
            <li><MapPin :size="12" /> 卷帘模式适合细节比对；闪烁模式适合快速定位变化；并排适合汇报。</li>
            <li><Download :size="12" /> 导出后 PNG 可直接附在应急简报中。</li>
          </ul>
        </article>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.satellite-compare-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 18px;
  background: #f5f7fb;
  min-height: 100%;
}

/* ===== 顶部 ===== */
.cmp-header {
  background: #ffffff;
  border-radius: 12px;
  padding: 16px 18px;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.05);
}
.cmp-header-main {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.cmp-title {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #1f2d3d;
}
.cmp-title h1 { font-size: 20px; font-weight: 600; margin: 0; }
.cmp-subtitle { color: #64748b; font-size: 13px; margin-left: 8px; }
.cmp-actions { display: flex; gap: 8px; flex-wrap: wrap; }

.cmp-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 14px;
  border-radius: 8px;
  border: 1px solid #d8e0ec;
  background: #ffffff;
  color: #1f2d3d;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.15s;
}
.cmp-btn:hover:not(:disabled) {
  border-color: #2563eb;
  color: #2563eb;
}
.cmp-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.cmp-btn-primary {
  background: linear-gradient(135deg, #2563eb, #1d4ed8);
  color: #ffffff;
  border-color: transparent;
}
.cmp-btn-primary:hover:not(:disabled) {
  color: #ffffff;
  box-shadow: 0 2px 8px rgba(37, 99, 235, 0.35);
}
.cmp-btn-ghost { background: transparent; }
.cmp-btn-sm { padding: 4px 10px; font-size: 12px; }

/* ===== 工具栏 ===== */
.cmp-toolbar {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 14px;
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid #e8eef6;
}
.cmp-toolbar-group { display: flex; flex-direction: column; gap: 6px; }
.cmp-label {
  font-size: 12px;
  color: #64748b;
  font-weight: 600;
}
.cmp-row { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.cmp-select, .cmp-input {
  padding: 5px 8px;
  border: 1px solid #d8e0ec;
  border-radius: 6px;
  font-size: 12px;
  background: #ffffff;
}
.cmp-input-sep { color: #94a3b8; font-size: 11px; }
.cmp-bbox-text {
  font-family: monospace;
  font-size: 11px;
  color: #64748b;
  background: #f1f5f9;
  padding: 3px 6px;
  border-radius: 4px;
}
.cmp-inline {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #64748b;
}
.cmp-inline input[type="range"] { width: 80px; }
.cmp-inline input[type="number"] { width: 50px; }

/* ===== 状态条 ===== */
.cmp-status-bar {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 10px;
}
.cmp-status-card {
  background: #ffffff;
  padding: 10px 14px;
  border-radius: 10px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.05);
}
.cmp-status-label {
  font-size: 11px;
  color: #94a3b8;
  font-weight: 600;
}
.cmp-status-tag {
  font-size: 12px;
  font-weight: 500;
  padding: 2px 8px;
  border-radius: 4px;
  display: inline-block;
  width: fit-content;
}
.cmp-status-tag.tone-idle    { background: #f1f5f9; color: #64748b; }
.cmp-status-tag.tone-pending { background: #fef3c7; color: #92400e; }
.cmp-status-tag.tone-running { background: #dbeafe; color: #1e40af; }
.cmp-status-tag.tone-success { background: #d1fae5; color: #065f46; }
.cmp-status-tag.tone-error   { background: #fee2e2; color: #991b1b; }

.cmp-progress {
  height: 4px;
  background: #f1f5f9;
  border-radius: 2px;
  overflow: hidden;
}
.cmp-progress-bar {
  height: 100%;
  background: linear-gradient(90deg, #2563eb, #60a5fa);
  transition: width 0.3s;
}

/* ===== 错误提示 ===== */
.cmp-error {
  background: #fef2f2;
  border: 1px solid #fca5a5;
  color: #991b1b;
  padding: 10px 14px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
}

/* ===== 主体 ===== */
.cmp-main {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 14px;
  flex: 1;
  min-height: 0;
}
@media (max-width: 1100px) {
  .cmp-main { grid-template-columns: 1fr; }
}

/* ===== 画布区 ===== */
.cmp-canvas-section {
  background: #ffffff;
  border-radius: 12px;
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  position: relative;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.05);
}
.cmp-mode-tabs {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.cmp-mode-tab {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 5px 12px;
  border-radius: 6px;
  border: 1px solid #d8e0ec;
  background: #ffffff;
  font-size: 12px;
  cursor: pointer;
}
.cmp-mode-tab.active {
  background: #2563eb;
  color: #ffffff;
  border-color: transparent;
}
.cmp-canvas-tools {
  margin-left: auto;
  display: flex;
  gap: 6px;
  align-items: center;
  flex-wrap: wrap;
}

/* ===== 画布 ===== */
.cmp-canvas {
  position: relative;
  aspect-ratio: 16 / 9;
  background: #0f172a;
  border-radius: 8px;
  overflow: hidden;
  user-select: none;
  display: flex;
  align-items: center;
  justify-content: center;
}
.cmp-canvas-empty {
  color: #94a3b8;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  font-size: 13px;
}
.cmp-canvas-swipe .cmp-layer {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.cmp-canvas-swipe .cmp-layer-after {
  clip-path: inset(0 0 0 var(--cmp-after-clip, 50%));
}
.cmp-swipe-divider {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 2px;
  background: #ffffff;
  box-shadow: 0 0 8px rgba(0,0,0,0.4);
  pointer-events: none;
}
.cmp-swipe-handle {
  position: absolute;
  top: 50%;
  transform: translate(-50%, -50%);
  background: #ffffff;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #1f2d3d;
  box-shadow: 0 2px 6px rgba(0,0,0,0.3);
}
.cmp-tag {
  position: absolute;
  top: 12px;
  padding: 3px 10px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
  color: #ffffff;
  z-index: 5;
}
.cmp-tag-before { background: #2563eb; left: 12px; }
.cmp-tag-after { background: #dc2626; right: 12px; }
.cmp-tag-flicker {
  background: rgba(0,0,0,0.6);
  top: 12px;
  left: 12px;
}

.cmp-canvas-flicker {
  display: flex;
  align-items: center;
  justify-content: center;
}
.cmp-layer-flicker {
  width: 100%;
  height: 100%;
  object-fit: cover;
  position: absolute;
  inset: 0;
}
.cmp-flicker-buttons {
  position: absolute;
  bottom: 12px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  gap: 6px;
  z-index: 5;
}
.cmp-fade-enter-active, .cmp-fade-leave-active { transition: opacity 0.3s; }
.cmp-fade-enter-from, .cmp-fade-leave-to { opacity: 0; }

.cmp-canvas-side {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px;
  background: #0f172a;
  aspect-ratio: 16 / 7;
}
.cmp-side-figure {
  margin: 0;
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
}
.cmp-side-figure img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.cmp-side-figure figcaption {
  position: absolute;
  bottom: 6px;
  left: 6px;
  right: 6px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: rgba(0,0,0,0.55);
  color: #ffffff;
  padding: 3px 8px;
  border-radius: 4px;
  font-size: 11px;
}

/* ===== 差异叠加 ===== */
.cmp-heatmap-overlay {
  position: absolute;
  inset: 14px;
  pointer-events: none;
  z-index: 3;
}
.cmp-heatmap-overlay.mode-side { inset: 14px; }
.cmp-heatmap-overlay img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  mix-blend-mode: screen;
}

.cmp-hotspots-overlay {
  position: absolute;
  inset: 14px;
  pointer-events: none;
  z-index: 4;
}
.cmp-hotspots-overlay.mode-side { inset: 14px; }
.cmp-hotspot {
  position: absolute;
  border: 2px solid #facc15;
  background: rgba(250, 204, 21, 0.18);
  border-radius: 3px;
}
.cmp-hotspot-label {
  position: absolute;
  top: -10px;
  left: -10px;
  width: 20px;
  height: 20px;
  background: #facc15;
  color: #1f2d3d;
  border-radius: 50%;
  font-size: 11px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* ===== 侧栏 ===== */
.cmp-side-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.cmp-card {
  background: #ffffff;
  border-radius: 12px;
  padding: 14px;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.05);
}
.cmp-card-header {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 10px;
  color: #1f2d3d;
}
.cmp-card-header h3 { font-size: 14px; font-weight: 600; margin: 0; }
.cmp-badge {
  margin-left: auto;
  background: #2563eb;
  color: #ffffff;
  font-size: 11px;
  padding: 2px 6px;
  border-radius: 10px;
}
.cmp-stat-row {
  display: flex;
  justify-content: space-between;
  padding: 4px 0;
  font-size: 12px;
  color: #64748b;
}
.cmp-stat-row strong { color: #1f2d3d; }
.cmp-stat-row strong.danger { color: #dc2626; }

.cmp-hotspot-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.cmp-hotspot-item {
  display: grid;
  grid-template-columns: 28px 1fr;
  gap: 8px;
  padding: 8px;
  background: #f8fafc;
  border-radius: 6px;
}
.cmp-hotspot-rank {
  background: #2563eb;
  color: #ffffff;
  border-radius: 50%;
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 11px;
}
.cmp-hotspot-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.cmp-hotspot-coord {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: #64748b;
}
.cmp-hotspot-coord code {
  background: #e0e7ff;
  color: #1e40af;
  padding: 1px 4px;
  border-radius: 3px;
  font-size: 11px;
}
.cmp-hotspot-meta {
  font-size: 11px;
  color: #94a3b8;
}
.cmp-hotspot-bar {
  grid-column: 1 / -1;
  height: 3px;
  background: #e2e8f0;
  border-radius: 1px;
  margin-top: 2px;
  overflow: hidden;
}
.cmp-hotspot-bar-fill {
  height: 100%;
  background: linear-gradient(90deg, #facc15, #f97316);
}
.cmp-empty {
  color: #94a3b8;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 14px;
  text-align: center;
  font-size: 12px;
}
.cmp-tips {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.cmp-tips li {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  font-size: 12px;
  color: #475569;
  line-height: 1.5;
}

/* ===== Spin ===== */
.spin { animation: cmp-spin 1s linear infinite; }
@keyframes cmp-spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
</style>
