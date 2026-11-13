<script setup>
/**
 * 时相对比（灾前 / 灾后）独立页面 — B1（2026-11-13，甲方决策「必做」）。
 *
 * 链路：选灾害点（或手填 AOI）→ 两期 Sentinel 合成任务 → 差异计算任务
 *      → 灾前图 / 灾后图 / 差异热力图 + 变化率 + Top 热点。
 *
 * 交付口径（B1-4 / E 节）：ai-service 拿不到真实卫星影像时会用 PIL 生成演示噪声图，
 * 本页在 result.dataSource !== 'real' 时只显示降级原因，**不提交差异任务、不展示任何假图**。
 * 时间统一按浏览器本地时区显示（A6-5 决策）。
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref } from "vue"
import {
  CalendarRange,
  ImageOff,
  Layers3,
  Loader2,
  Play,
  RefreshCw,
  Satellite,
  Square,
  TriangleAlert,
} from "lucide-vue-next"
import { dashboardApi } from "../../services/dashboardApi"
import {
  buildBbox,
  cancelComposeJob,
  describeJob,
  fetchComposeJob,
  fetchDiffJob,
  fetchImageObjectUrl,
  isRealImageJob,
  pollJob,
  submitCompose,
  submitDiff,
} from "../../services/satelliteCompareApi"

/** 按浏览器本地时区取 YYYY-MM-DD（A6-5：跟随浏览器时区，不固定 UTC+8）。 */
function localDate(daysAgo) {
  const d = new Date()
  d.setDate(d.getDate() - daysAgo)
  const p = (n) => String(n).padStart(2, "0")
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

const hazards = ref([])
const hazardsLoading = ref(false)
const selectedHazard = ref("")
const manualBbox = ref("")
const beforeWindow = reactive({ start: localDate(120), end: localDate(90) })
const afterWindow = reactive({ start: localDate(30), end: localDate(0) })
const maxCloud = ref(20)
const threshold = ref(30)
const colormap = ref("inferno")

const phase = ref("idle") // idle | composing | diffing | done | degraded | error
const statusText = ref("")
const errorMsg = ref("")
const degradeReason = ref("")
const beforeJob = ref(null)
const afterJob = ref(null)
const diffJob = ref(null)
const images = reactive({ before: "", after: "", diff: "" })
const jobIds = ref([])
let controller = null

const busy = computed(() => phase.value === "composing" || phase.value === "diffing")
const progress = computed(() => {
  const jobs = [beforeJob.value, afterJob.value, diffJob.value].filter(Boolean)
  if (!jobs.length) {return 0}
  return Math.round(jobs.reduce((sum, j) => sum + (Number(j.progress) || 0), 0) / jobs.length)
})
const stats = computed(() => diffJob.value?.result || null)
const hotspots = computed(() => (Array.isArray(stats.value?.hotspots) ? stats.value.hotspots : []))

/** 手动 bbox 优先；否则用选中灾害点的经纬度生成 ±0.02° AOI。 */
const activeBbox = computed(() => {
  const raw = manualBbox.value.trim()
  if (raw) {
    const parts = raw.split(/[,，\s]+/).map(Number)
    if (parts.length !== 4 || parts.some((n) => !Number.isFinite(n))) {return null}
    if (parts[0] >= parts[2] || parts[1] >= parts[3]) {return null}
    return parts
  }
  const point = hazards.value.find((h) => String(h.hazardCode ?? h.id) === selectedHazard.value)
  return point ? buildBbox(point) : null
})

const canRun = computed(() => !busy.value && Array.isArray(activeBbox.value)
  && beforeWindow.start && beforeWindow.end && afterWindow.start && afterWindow.end
  && beforeWindow.start <= beforeWindow.end && afterWindow.start <= afterWindow.end
  && beforeWindow.end <= afterWindow.start)

async function loadHazards() {
  hazardsLoading.value = true
  try {
    const data = await dashboardApi.getHazards({ page: 1, size: 200 })
    hazards.value = (data?.list ?? []).filter((h) =>
      Number.isFinite(Number(h.longitude ?? h.lon ?? h.lng))
      && Number.isFinite(Number(h.latitude ?? h.lat)))
    if (!selectedHazard.value && hazards.value.length) {
      selectedHazard.value = String(hazards.value[0].hazardCode ?? hazards.value[0].id)
    }
  } catch (e) {
    errorMsg.value = `灾害点列表加载失败：${e.message}`
  } finally {
    hazardsLoading.value = false
  }
}

function revokeImages() {
  for (const key of Object.keys(images)) {
    if (images[key]?.startsWith("blob:")) {URL.revokeObjectURL(images[key])}
    images[key] = ""
  }
}

function reset() {
  revokeImages()
  beforeJob.value = null
  afterJob.value = null
  diffJob.value = null
  errorMsg.value = ""
  degradeReason.value = ""
  statusText.value = ""
  jobIds.value = []
  phase.value = "idle"
}

async function cancel() {
  controller?.abort()
  for (const id of jobIds.value) {await cancelComposeJob(id)}
  reset()
  statusText.value = "已取消本次对比"
}

function composeParams(window) {
  return {
    bbox: activeBbox.value,
    start: window.start,
    end: window.end,
    max_cloud: Number(maxCloud.value),
    provider: "auto",
    sar_overlay: false,
    s2_true_color: true,
  }
}

async function runCompare() {
  if (!canRun.value) {return}
  reset()
  controller = new AbortController()
  const signal = controller.signal
  phase.value = "composing"
  statusText.value = "正在提交灾前 / 灾后两期影像合成任务…"
  try {
    const [b, a] = await Promise.all([
      submitCompose(composeParams(beforeWindow)),
      submitCompose(composeParams(afterWindow)),
    ])
    jobIds.value = [b.jobId, a.jobId]
    statusText.value = "正在等待两期影像合成…"
    const [before, after] = await Promise.all([
      pollJob(fetchComposeJob, b.jobId, (j) => { beforeJob.value = j }, { signal }),
      pollJob(fetchComposeJob, a.jobId, (j) => { afterJob.value = j }, { signal }),
    ])

    const failed = [before, after].find((j) => j.status !== "succeeded")
    if (failed) {
      throw new Error(`影像合成未完成：${failed.message || failed.status}`)
    }

    // B1-4：任一影像是演示降级数据 → 不提交 diff、不展示假图。
    if (!isRealImageJob(before) || !isRealImageJob(after)) {
      const mock = [before, after].find((j) => !isRealImageJob(j))
      degradeReason.value = `当前没有可用的真实卫星影像（数据源：${mock.result?.dataSource || "unknown"}` +
        `${mock.result?.sceneId ? `，场景 ${mock.result.sceneId}` : ""}）。` +
        "按交付口径不展示演示噪声图或由其计算出的假差异图。请按 docs/CDSE_SETUP.md 配置 CDSE / GEE 凭证，" +
        "或确认 ai-service 可访问公开 STAC 影像源后重试。"
      phase.value = "degraded"
      return
    }

    phase.value = "diffing"
    statusText.value = "正在计算两期影像差异…"
    const diff = await submitDiff({
      before_job_id: before.jobId,
      after_job_id: after.jobId,
      bbox: activeBbox.value,
      threshold: Number(threshold.value),
      colormap: colormap.value,
    })
    const result = await pollJob(fetchDiffJob, diff.jobId, (j) => { diffJob.value = j }, { signal })
    if (result.status !== "succeeded" || !result.result?.diffUrl) {
      throw new Error(`差异计算未完成：${result.message || result.status}`)
    }

    statusText.value = "正在加载影像…"
    const [beforeUrl, afterUrl, diffUrl] = await Promise.all([
      fetchImageObjectUrl(before.result.s2Url, signal),
      fetchImageObjectUrl(after.result.s2Url, signal),
      fetchImageObjectUrl(result.result.diffUrl, signal),
    ])
    images.before = beforeUrl
    images.after = afterUrl
    images.diff = diffUrl
    phase.value = "done"
    statusText.value = "对比完成"
  } catch (e) {
    if (e?.name === "AbortError") {return}
    revokeImages()
    phase.value = "error"
    errorMsg.value = e?.message || "时相对比失败（未知错误）"
    statusText.value = ""
  }
}

onMounted(loadHazards)
onBeforeUnmount(() => {
  controller?.abort()
  revokeImages()
})
</script>

<template>
  <div class="satellite-page">
    <header class="page-header">
      <div>
        <h2><Satellite :size="18" />时相对比（灾前 / 灾后）</h2>
        <p>基于 Sentinel-2 L2A 真彩影像，对同一 AOI 的两期影像做像素级差异计算与变化热点提取。</p>
      </div>
      <div class="actions">
        <button class="ghost-btn" :disabled="hazardsLoading || busy" @click="loadHazards">
          <RefreshCw :size="14" />刷新灾害点
        </button>
      </div>
    </header>

    <div class="notice">
      <TriangleAlert :size="14" />
      <span>真实影像需要 CDSE / GEE 凭证或可访问的公开 STAC 源；无真实影像时本页只提示降级原因，不生成或展示演示差异图。日期与任务时间按浏览器本地时区显示。</span>
    </div>

    <section class="panel form-panel">
      <div class="form-grid">
        <label class="field">
          <span>灾害点</span>
          <select v-model="selectedHazard" :disabled="!!manualBbox.trim() || busy">
            <option value="">请选择</option>
            <option v-for="h in hazards" :key="h.hazardCode ?? h.id" :value="String(h.hazardCode ?? h.id)">
              {{ h.name ?? h.hazardName ?? h.hazardCode ?? h.id }}
            </option>
          </select>
          <small>已加载 {{ hazards.length }} 个带坐标的灾害点</small>
        </label>
        <label class="field">
          <span>手动 AOI（可选）</span>
          <input v-model="manualBbox" type="text" placeholder="minLon,minLat,maxLon,maxLat" :disabled="busy" />
          <small>填写后覆盖灾害点坐标，例如 103.30,30.80,103.80,31.20</small>
        </label>
        <div class="field">
          <span><CalendarRange :size="13" />灾前期</span>
          <div class="date-row">
            <input v-model="beforeWindow.start" type="date" :disabled="busy" />
            <em>至</em>
            <input v-model="beforeWindow.end" type="date" :disabled="busy" />
          </div>
        </div>
        <div class="field">
          <span><CalendarRange :size="13" />灾后期</span>
          <div class="date-row">
            <input v-model="afterWindow.start" type="date" :disabled="busy" />
            <em>至</em>
            <input v-model="afterWindow.end" type="date" :disabled="busy" />
          </div>
        </div>
        <label class="field">
          <span>最大云量 {{ maxCloud }}%</span>
          <input v-model.number="maxCloud" type="range" min="0" max="100" step="5" :disabled="busy" />
        </label>
        <label class="field">
          <span>差异阈值 {{ threshold }}</span>
          <input v-model.number="threshold" type="range" min="0" max="120" step="5" :disabled="busy" />
        </label>
        <label class="field">
          <span>热力图配色</span>
          <select v-model="colormap" :disabled="busy">
            <option value="inferno">inferno</option>
            <option value="viridis">viridis</option>
            <option value="red">red</option>
            <option value="grayscale">grayscale</option>
          </select>
        </label>
        <div class="field field--actions">
          <button class="primary-btn" :disabled="!canRun" @click="runCompare">
            <Play :size="14" v-if="!busy" />
            <Loader2 :size="14" class="spin" v-else />
            {{ busy ? "处理中…" : "开始对比" }}
          </button>
          <button class="ghost-btn" v-if="busy" @click="cancel"><Square :size="13" />取消</button>
        </div>
      </div>
      <p class="bbox-line">当前 AOI：<code>{{ activeBbox ? activeBbox.join(", ") : "未确定（请选择带坐标的灾害点或手填 bbox）" }}</code></p>
    </section>

    <section v-if="busy || statusText" class="panel status-panel">
      <div class="status-head">
        <span><Layers3 :size="14" />{{ statusText || "处理中" }}</span>
        <strong>{{ progress }}%</strong>
      </div>
      <div class="progress-track"><div class="progress-fill" :style="{ width: progress + '%' }"></div></div>
      <div class="job-lines">
        <span v-if="beforeJob">灾前：{{ describeJob(beforeJob).label }}</span>
        <span v-if="afterJob">灾后：{{ describeJob(afterJob).label }}</span>
        <span v-if="diffJob">差异：{{ describeJob(diffJob).label }}</span>
      </div>
    </section>

    <section v-if="phase === 'degraded'" class="panel alert-panel alert-panel--warn">
      <ImageOff :size="20" />
      <div>
        <h3>未取得真实卫星影像</h3>
        <p>{{ degradeReason }}</p>
      </div>
    </section>

    <section v-if="phase === 'error'" class="panel alert-panel alert-panel--error">
      <TriangleAlert :size="20" />
      <div>
        <h3>时相对比未完成</h3>
        <p>{{ errorMsg }}</p>
        <button class="ghost-btn" @click="reset">返回重填</button>
      </div>
    </section>

    <section v-if="phase === 'done' && stats" class="result-grid">
      <div class="panel image-card" v-for="card in [
        { key: 'before', title: '灾前影像', note: beforeWindow.start + ' ~ ' + beforeWindow.end },
        { key: 'after', title: '灾后影像', note: afterWindow.start + ' ~ ' + afterWindow.end },
        { key: 'diff', title: '差异热力图', note: colormap + ' · 阈值 ' + threshold },
      ]" :key="card.key">
        <h3>{{ card.title }}</h3>
        <img :src="images[card.key]" :alt="card.title" />
        <small>{{ card.note }}</small>
      </div>
    </section>

    <section v-if="phase === 'done' && stats" class="panel summary-panel">
      <div class="summary-stats">
        <div><span>变化像素占比</span><strong>{{ stats.changePercent ?? "—" }}%</strong></div>
        <div><span>变化像素</span><strong>{{ stats.changePixels ?? "—" }}</strong></div>
        <div><span>影像尺寸</span><strong>{{ stats.imageSize ? stats.imageSize.width + '×' + stats.imageSize.height : '—' }}</strong></div>
        <div><span>数据源</span><strong>真实卫星影像</strong></div>
      </div>
      <h3>变化热点 Top {{ hotspots.length }}</h3>
      <ul v-if="hotspots.length" class="hotspot-list">
        <li v-for="(h, i) in hotspots" :key="i">
          <span class="hotspot-idx">#{{ i + 1 }}</span>
          <span>经度 {{ h.lon ?? "—" }} · 纬度 {{ h.lat ?? "—" }}</span>
          <span>强度 {{ Number(h.intensity ?? 0).toFixed(2) }}</span>
          <span>{{ h.pixel_count ?? "—" }} 像素</span>
        </li>
      </ul>
      <p v-else class="empty-row">本期影像未提取到超过阈值的差异热点。</p>
    </section>

    <section v-if="phase === 'idle' && !statusText" class="panel empty-panel">
      <Satellite :size="30" />
      <h3>尚未开始对比</h3>
      <p>选择灾害点、设定灾前 / 灾后时间窗口后点击「开始对比」。没有真实影像时会明确提示，不会给出假结果。</p>
    </section>
  </div>
</template>

<style scoped>
.satellite-page { padding: 4px 2px 24px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; margin-bottom: 14px; }
.page-header h2 { margin: 0; display: flex; gap: 8px; align-items: center; font-size: 18px; color: var(--c-text); }
.page-header p { margin: 4px 0 0; color: var(--c-text-2); font-size: 12px; }
.actions { display: flex; gap: 8px; }
.notice { display: flex; gap: 8px; align-items: flex-start; padding: 10px 14px; margin-bottom: 14px; border: 1px solid var(--c-brand-soft); border-radius: var(--r-md); background: var(--c-surface); color: var(--c-text-2); font-size: 12px; line-height: 1.6; }
.notice svg { flex: none; margin-top: 2px; color: var(--c-warning-ink); }
.panel { background: var(--c-surface); border: 1px solid var(--c-brand-soft); border-radius: var(--r-md); padding: 16px 18px; margin-bottom: 14px; }
.form-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(230px, 1fr)); gap: 14px 18px; }
.field { display: flex; flex-direction: column; gap: 6px; font-size: 12px; color: var(--c-text-2); }
.field > span { display: flex; gap: 5px; align-items: center; color: var(--c-brand-deep); font-weight: 600; }
.field small { color: var(--c-text-2); font-size: 11px; }
.field input[type="text"], .field input[type="date"], .field select { padding: 7px 10px; border: 1px solid var(--c-separator); border-radius: var(--r-sm); background: var(--c-bg); color: var(--c-text); font-size: 12px; }
.field input[type="range"] { width: 100%; accent-color: var(--c-brand); }
.date-row { display: flex; gap: 6px; align-items: center; }
.date-row input { flex: 1; min-width: 0; }
.date-row em { font-style: normal; color: var(--c-text-2); }
.field--actions { flex-direction: row; align-items: flex-end; gap: 8px; }
.bbox-line { margin: 14px 0 0; font-size: 11px; color: var(--c-text-2); }
.bbox-line code { padding: 2px 6px; background: var(--c-bg); border-radius: var(--r-xs); }
.primary-btn { display: flex; gap: 6px; align-items: center; padding: 8px 14px; border: 0; background: var(--c-brand); color: var(--c-on-brand); border-radius: var(--r-sm); cursor: pointer; font-size: 13px; }
.primary-btn:disabled { opacity: .5; cursor: not-allowed; }
.ghost-btn { display: flex; gap: 5px; align-items: center; padding: 7px 10px; border: 1px solid var(--c-separator); background: var(--c-surface); border-radius: var(--r-sm); color: var(--c-text); cursor: pointer; font-size: 12px; }
.ghost-btn:disabled { opacity: .5; cursor: not-allowed; }
.spin { animation: rotate 1s linear infinite; }
@keyframes rotate { to { transform: rotate(360deg); } }
.status-head { display: flex; justify-content: space-between; align-items: center; font-size: 12px; color: var(--c-text-2); }
.status-head span { display: flex; gap: 6px; align-items: center; }
.status-head strong { color: var(--c-brand-deep); }
.progress-track { height: 6px; margin: 10px 0; background: var(--c-bg); border-radius: 99px; overflow: hidden; }
.progress-fill { height: 100%; background: var(--c-brand); transition: width .3s; }
.job-lines { display: flex; flex-wrap: wrap; gap: 14px; font-size: 11px; color: var(--c-text-2); }
.alert-panel { display: flex; gap: 12px; align-items: flex-start; }
.alert-panel svg { flex: none; }
.alert-panel h3 { margin: 0 0 6px; font-size: 14px; color: var(--c-text); }
.alert-panel p { margin: 0; font-size: 12px; line-height: 1.7; color: var(--c-text-2); }
.alert-panel--warn { border-color: var(--c-danger-bg); }
.alert-panel--warn svg { color: var(--c-warning-ink); }
.alert-panel--error svg { color: var(--c-danger-strong); }
.alert-panel .ghost-btn { margin-top: 10px; }
.result-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 14px; margin-bottom: 14px; }
.image-card { margin: 0; }
.image-card h3 { margin: 0 0 10px; font-size: 13px; color: var(--c-brand-deep); }
.image-card img { display: block; width: 100%; aspect-ratio: 1 / 1; object-fit: cover; border-radius: var(--r-sm); background: var(--c-bg); }
.image-card small { display: block; margin-top: 8px; color: var(--c-text-2); font-size: 11px; }
.summary-stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 12px; margin-bottom: 16px; }
.summary-stats div { padding: 12px; background: var(--c-bg); border-radius: var(--r-sm); display: flex; flex-direction: column; gap: 4px; }
.summary-stats span { color: var(--c-text-2); font-size: 11px; }
.summary-stats strong { color: var(--c-text); font-size: 18px; }
.summary-panel h3 { margin: 0 0 10px; font-size: 13px; color: var(--c-brand-deep); }
.hotspot-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
.hotspot-list li { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; padding: 9px 12px; background: var(--c-bg); border-radius: var(--r-sm); font-size: 12px; color: var(--c-text-2); }
.hotspot-idx { font-weight: 700; color: var(--c-brand-deep); }
.empty-row { padding: 18px; text-align: center; color: var(--c-text-2); font-size: 12px; }
.empty-panel { text-align: center; color: var(--c-text-2); }
.empty-panel h3 { margin: 10px 0 6px; font-size: 14px; color: var(--c-text); }
.empty-panel p { margin: 0 auto; max-width: 520px; font-size: 12px; line-height: 1.7; }
</style>
