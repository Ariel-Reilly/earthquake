<script setup>
/**
 * AI 推理结果管理（独立页面 /dashboard/ai-results）。
 *
 * 查看 AI 模型推理出的疑似灾害结果，按置信度排序后可一键推送至人工确认或归档为误报。
 */
import { computed, onMounted, reactive, ref, watch } from "vue"
import { BarChart3, Bot, Clock, Cpu, Eye, Filter, Loader2, Send, TrendingUp, X } from "lucide-vue-next"
import { dashboardApi } from "../../services/dashboardApi"
import AiHealthBadge from "../../components/AiHealthBadge.vue"

const props = defineProps({
  searchTerm: { type: String, default: "" },
  userRole: { type: String, default: "admin" },
})
const emit = defineEmits(["notify", "open-task-modal"])

const loading = ref(false)
const submittingId = ref(null)
const results = ref({ items: [], total: 0 })
const selected = ref(null)
const detailDialog = ref(false)
const dismissDialog = ref(false)
const dismissTarget = ref(null)
const dismissReason = ref("")
const filter = reactive({
  minConfidence: 0.5,
  type: "",
})

const confidenceBuckets = computed(() => {
  const buckets = [
    { range: "0.5-0.6", count: 0, color: "#9aab9f" },
    { range: "0.6-0.7", count: 0, color: "#658fc1" },
    { range: "0.7-0.8", count: 0, color: "#c4a86e" },
    { range: "0.8-0.9", count: 0, color: "#c7553b" },
    { range: "0.9-1.0", count: 0, color: "#9b761e" },
  ]
  for (const r of results.value.items ?? []) {
    const c = r.confidence
    if (typeof c !== "number") continue
    if (c >= 0.9) buckets[4].count++
    else if (c >= 0.8) buckets[3].count++
    else if (c >= 0.7) buckets[2].count++
    else if (c >= 0.6) buckets[1].count++
    else if (c >= 0.5) buckets[0].count++
  }
  return buckets
})

const totalResults = computed(() => results.value.items?.length ?? 0)
/** 已评估 = 后端给了 detection_confidence；null 表示该点从未被模型打过置信度，不能编一个。 */
const evaluatedCount = computed(() =>
  (results.value.items ?? []).filter((r) => typeof r.confidence === "number").length,
)
const highConfidenceCount = computed(() =>
  (results.value.items ?? []).filter((r) => typeof r.confidence === "number" && r.confidence >= 0.8).length,
)
const highScoreCount = computed(() =>
  (results.value.items ?? []).filter((r) => typeof r.aiScore === "number" && r.aiScore >= 60).length,
)

const filteredResults = computed(() => {
  let list = results.value.items ?? []
  if (filter.type) list = list.filter((r) => r.type === filter.type)
  list = list.filter((r) => typeof r.confidence === "number" && r.confidence >= filter.minConfidence)
  return list.sort((a, b) => (b.confidence ?? 0) - (a.confidence ?? 0))
})

/** 灾害点类型选项来自真实返回结果，不再写死灾种清单。 */
const typeOptions = computed(() => {
  const set = new Set()
  for (const r of results.value.items ?? []) {
    if (r.type) set.add(r.type)
  }
  return [...set]
})

/**
 * 后端 /hazards/ai-monitor 返回 HazardDto，真实可用字段是：
 * detectionConfidence（模型置信度）、aiScore（综合风险分）、aiLevelLabel、
 * aiDisasterType、aiAnalyzedAt、aiDeviceAvailable、eventCode / eventState。
 * 缺失字段一律保留 null，由 UI 显示"—"，不再用 Math.random 或写死文案填充。
 */
function normalizeResult(item, index) {
  return {
    id: item.id ?? index,
    hazardCode: item.hazardCode ?? "",
    name: item.name ?? "未命名",
    type: item.aiDisasterType ?? item.type ?? "",
    level: item.aiLevelLabel ?? item.level ?? "",
    confidence: typeof item.detectionConfidence === "number" ? item.detectionConfidence : null,
    aiScore: typeof item.aiScore === "number" ? item.aiScore : null,
    analyzedAt: item.aiAnalyzedAt ?? null,
    deviceAvailable: item.aiDeviceAvailable,
    source: item.source ?? "",
    discoveredAt: item.discoveredAt ?? null,
    regionCode: item.regionCode ?? "",
    eventCode: item.eventCode ?? "",
    eventState: item.eventState ?? "",
  }
}

async function loadResults() {
  loading.value = true
  try {
    const data = await dashboardApi.getAiMonitorHazards?.() ?? []
    const items = (data ?? []).map(normalizeResult)
    results.value = { items, total: items.length }
  } catch (e) {
    results.value = { items: [], total: 0 }
    emit("notify", e.message ?? "加载推理结果失败")
  } finally {
    loading.value = false
  }
}

function openDetail(item) {
  selected.value = item
  detailDialog.value = true
}

/** 提交人工确认：真实调用后端，成功与否以后端返回为准。 */
async function pushToConfirmation(item) {
  if (!item.hazardCode) {
    emit("notify", "该记录缺少灾害点编号，无法提交确认")
    return
  }
  submittingId.value = item.id
  try {
    const res = await dashboardApi.submitHazardConfirmation(item.hazardCode)
    emit("notify", res?.message ?? `${item.hazardCode} 已提交人工确认`)
    results.value.items = results.value.items.filter((r) => r.id !== item.id)
  } catch (e) {
    emit("notify", `提交失败：${e?.message ?? e}`)
  } finally {
    submittingId.value = null
  }
}

function openDismiss(item) {
  dismissTarget.value = item
  dismissReason.value = ""
  dismissDialog.value = true
}

/** 归档误报：原因必填，后端执行 DISMISS → CLOSED。 */
async function confirmDismiss() {
  const reason = dismissReason.value.trim()
  if (!reason) {
    emit("notify", "请填写误报原因")
    return
  }
  if (!dismissTarget.value?.hazardCode) {
    emit("notify", "该记录缺少灾害点编号，无法归档")
    return
  }
  submittingId.value = dismissTarget.value.id
  try {
    const res = await dashboardApi.dismissHazard(dismissTarget.value.hazardCode, reason)
    emit("notify", `${dismissTarget.value.name} 已归档为误报（${res?.state ?? "CLOSED"}）`)
    results.value.items = results.value.items.filter((r) => r.id !== dismissTarget.value.id)
    dismissDialog.value = false
  } catch (e) {
    emit("notify", `归档失败：${e?.message ?? e}`)
  } finally {
    submittingId.value = null
  }
}

watch(filter, () => {}, { deep: true })
onMounted(() => loadResults())
</script>

<template>
  <div class="ai-results-shell">
    <header class="page-header">
      <div>
      </div>
      <div class="actions">
        <AiHealthBadge />
        <button class="op-btn" @click="loadResults">
          <TrendingUp :size="14" />刷新
        </button>
      </div>
    </header>

    <section class="stat-grid">
      <div class="stat-card"><strong>{{ totalResults }}</strong><span>推理结果总数</span></div>
      <div class="stat-card"><strong class="stat-num--brand">{{ evaluatedCount }}</strong><span>已评估（有置信度）</span></div>
      <div class="stat-card"><strong class="stat-num--danger">{{ highConfidenceCount }}</strong><span>高置信度 (≥0.8)</span></div>
      <div class="stat-card"><strong class="stat-num--warn">{{ highScoreCount }}</strong><span>AI 综合分 ≥60</span></div>
    </section>

    <section class="confidence-chart">
      <h3><BarChart3 :size="14" />置信度分布</h3>
      <div class="bars">
        <div v-for="b in confidenceBuckets" :key="b.range" class="bar-col">
          <div class="bar" :style="{ height: Math.max(8, b.count * 16) + 'px', background: b.color }">
            <span class="bar-count">{{ b.count }}</span>
          </div>
          <span class="bar-label">{{ b.range }}</span>
        </div>
      </div>
    </section>

    <section class="filter-bar">
      <Filter :size="14" />
      <span>最小置信度</span>
      <input type="range" min="0.5" max="1" step="0.05" v-model.number="filter.minConfidence" />
      <code>{{ filter.minConfidence.toFixed(2) }}</code>
      <select v-model="filter.type">
        <option value="">全部灾种</option>
        <option v-for="t in typeOptions" :key="t" :value="t">{{ t }}</option>
      </select>
      <span class="filter-hint">仅显示模型已给出置信度的记录</span>
    </section>

    <div v-if="loading" class="loading-row">
      <Loader2 :size="16" class="spin" />加载 AI 推理结果…
    </div>
    <div v-else-if="filteredResults.length === 0" class="empty-row">
      当前筛选下没有推理结果
    </div>
    <ul v-else class="result-list">
      <li v-for="r in filteredResults" :key="r.id" @click="openDetail(r)">
        <div class="thumb">
          <div class="thumb-rect" :style="{ background: r.confidence >= 0.85 ? '#c7553b' : r.confidence >= 0.7 ? '#c4a86e' : '#658fc1' }">
            <Cpu :size="20" />
          </div>
        </div>
        <div class="meta">
          <div class="title-row">
            <strong>{{ r.name }}</strong>
            <span class="hazard-code">{{ r.hazardCode }}</span>
          </div>
          <p>
            {{ r.type || "未分类" }}
            <template v-if="r.regionCode"> · {{ r.regionCode }}</template>
            <template v-if="r.aiScore !== null"> · AI 综合分 {{ r.aiScore.toFixed(0) }}</template>
          </p>
          <div class="tag-row">
            <span v-if="r.level" class="tag">{{ r.level }}</span>
            <span v-if="r.eventState" class="tag">{{ r.eventState }}</span>
            <span v-if="r.deviceAvailable === false" class="tag tag--muted">无设备数据</span>
            <span v-if="r.analyzedAt" class="tag tag--muted">分析于 {{ r.analyzedAt.slice(0, 16).replace("T", " ") }}</span>
          </div>
        </div>
        <div class="confidence">
          <div v-if="r.confidence !== null" class="conf-circle" :style="{ '--c': r.confidence }">
            <strong>{{ (r.confidence * 100).toFixed(0) }}</strong>
            <small>置信度</small>
          </div>
          <div v-else class="conf-circle conf-circle--empty">
            <strong>—</strong>
            <small>未评估</small>
          </div>
        </div>
        <div class="actions" @click.stop>
          <button
            class="primary-btn small"
            :disabled="submittingId === r.id"
            @click="pushToConfirmation(r)"
          >
            <Send :size="12" />{{ submittingId === r.id ? "提交中…" : "提交人工确认" }}
          </button>
          <button class="ghost-btn small danger" :disabled="submittingId === r.id" @click="openDismiss(r)">
            <X :size="12" />误报
          </button>
        </div>
      </li>
    </ul>

    <div v-if="detailDialog && selected" class="modal-mask" @click.self="detailDialog = false">
      <div class="modal-card large">
        <header>
          <h3><Eye :size="16" />{{ selected.name }} 推理详情</h3>
          <button class="icon-btn" @click="detailDialog = false"><X :size="14" /></button>
        </header>
        <div class="detail-grid">
          <section class="evidence-block">
            <h4>灾害点信息</h4>
            <ul>
              <li><span class="kind">编号</span>{{ selected.hazardCode || "—" }}</li>
              <li><span class="kind">灾种</span>{{ selected.type || "未分类" }}</li>
              <li><span class="kind">区域</span>{{ selected.regionCode || "—" }}</li>
              <li><span class="kind">来源</span>{{ selected.source || "—" }}</li>
              <li><span class="kind">发现时间</span>{{ selected.discoveredAt ? selected.discoveredAt.slice(0, 19).replace("T", " ") : "—" }}</li>
            </ul>
          </section>
          <section class="vote-block">
            <h4>模型评估结果</h4>
            <div class="metric-row">
              <span class="metric-name">检测置信度</span>
              <div class="track">
                <div
                  class="fill"
                  :style="{ width: (selected.confidence ?? 0) * 100 + '%', background: selected.confidence >= 0.8 ? '#6f9e7f' : '#9aab9f' }"
                ></div>
              </div>
              <code>{{ selected.confidence !== null ? selected.confidence.toFixed(2) : "未评估" }}</code>
            </div>
            <div class="metric-row">
              <span class="metric-name">AI 综合分</span>
              <div class="track">
                <div class="fill" :style="{ width: (selected.aiScore ?? 0) + '%', background: '#658fc1' }"></div>
              </div>
              <code>{{ selected.aiScore !== null ? selected.aiScore.toFixed(0) : "—" }}</code>
            </div>
            <p class="metric-meta">
              风险等级：{{ selected.level || "—" }} · 分析时间：{{ selected.analyzedAt ? selected.analyzedAt.slice(0, 19).replace("T", " ") : "—" }}
            </p>
            <p v-if="selected.deviceAvailable === false" class="metric-warn">
              该灾害点暂无在线监测设备，本次评估仅使用遥感与气象数据。
            </p>
          </section>
          <section class="reason-block">
            <h4>事件关联</h4>
            <p class="link-line">
              统一事件：<code>{{ selected.eventCode || "尚未生成" }}</code>
            </p>
            <p class="link-line">
              当前状态：<code>{{ selected.eventState || "—" }}</code>
            </p>
            <p class="hint-text">
              提交人工确认后，该灾害点会进入确认工作台，由值班员认领、复核，再决定发布预警或归档。
            </p>
          </section>
        </div>
      </div>
    </div>

    <div v-if="dismissDialog && dismissTarget" class="modal-mask" @click.self="dismissDialog = false">
      <div class="modal-card">
        <header>
          <h3><X :size="16" />归档为误报</h3>
          <button class="icon-btn" @click="dismissDialog = false"><X :size="14" /></button>
        </header>
        <p class="dismiss-tip">
          将「{{ dismissTarget.name }}」归档为误报后，对应统一事件会流转到 CLOSED 并记录原因。
          已发布预警或已启动应急的事件不允许按误报关闭。
        </p>
        <label class="dismiss-field">
          <span>误报原因（必填）</span>
          <textarea v-model="dismissReason" rows="3" placeholder="例如：现场核实为施工扬尘，非滑坡形变"></textarea>
        </label>
        <div class="dismiss-actions">
          <button class="ghost-btn" @click="dismissDialog = false">取消</button>
          <button class="primary-btn danger" :disabled="submittingId === dismissTarget.id" @click="confirmDismiss">
            {{ submittingId === dismissTarget.id ? "提交中…" : "确认归档" }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ai-results-shell { padding: 24px 28px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 18px; }
.page-header h2 { margin: 0; display: flex; gap: 8px; align-items: center; font-size: 18px; color: var(--c-text); }
.page-header p { margin: 4px 0 0; color: var(--c-text-2); font-size: 12px; }

.stat-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-bottom: 18px; }
.stat-card { padding: 16px; background: var(--c-surface); border: 1px solid var(--c-brand-soft); border-radius: var(--r-md); display: flex; flex-direction: column; gap: 4px; }
.stat-card strong { font-size: 26px; color: var(--c-text); }
.stat-card span { color: var(--c-text-2); font-size: 12px; }

.confidence-chart { background: var(--c-surface); border: 1px solid var(--c-brand-soft); border-radius: var(--r-md); padding: 16px 18px; margin-bottom: 18px; }
.confidence-chart h3 { margin: 0 0 16px; display: flex; gap: 6px; align-items: center; font-size: 13px; color: var(--c-brand-deep); }
.bars { display: flex; gap: 18px; align-items: flex-end; height: 120px; padding: 0 20px; }
.bar-col { display: flex; flex-direction: column; align-items: center; gap: 6px; flex: 1; }
.bar { width: 36px; border-radius: var(--r-xs) 4px 0 0; display: flex; align-items: flex-start; justify-content: center; padding-top: 4px; color: var(--c-on-brand); font-size: 11px; font-weight: 600; min-height: 8px; transition: height .3s; }
.bar-count { color: var(--c-on-brand); }
.bar-label { color: var(--c-text-2); font-size: 11px; }

.filter-bar { display: flex; gap: 10px; align-items: center; background: var(--c-surface); border: 1px solid var(--c-brand-soft); border-radius: var(--r-md); padding: 12px 16px; margin-bottom: 14px; font-size: 12px; color: var(--c-brand-deep); }
.filter-bar input[type=range] { width: 160px; }
.filter-bar code { padding: 2px 8px; background: var(--c-bg); border-radius: var(--r-xs); font-size: 11px; }
.filter-bar select { padding: 6px 10px; border: 1px solid var(--c-separator); border-radius: var(--r-sm); background: var(--c-surface); }

.loading-row, .empty-row { padding: 32px; text-align: center; color: var(--c-text-2); font-size: 13px; }

.result-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 10px; }
.result-list li { display: flex; gap: 14px; align-items: center; padding: 12px 16px; background: var(--c-surface); border: 1px solid var(--c-brand-soft); border-radius: var(--r-md); cursor: pointer; transition: box-shadow .15s; }
.result-list li:hover { box-shadow: 0 4px 12px rgba(0,0,0,.05); }
.thumb { flex: 0 0 56px; }
.thumb-rect { width: 56px; height: 56px; border-radius: var(--r-sm); color: var(--c-on-brand); display: grid; place-items: center; }
.meta { flex: 1; min-width: 0; }
.title-row { display: flex; gap: 8px; align-items: center; }
.title-row strong { color: var(--c-text); font-size: 14px; }
.hazard-code { color: var(--c-text-2); font-size: 11px; font-family: monospace; }
.meta p { margin: 2px 0; color: var(--c-text-2); font-size: 12px; }
.tag-row { display: flex; gap: 4px; flex-wrap: wrap; margin-top: 4px; }
.tag { padding: 2px 7px; border-radius: var(--r-xs); background: var(--c-brand-soft); color: var(--c-brand-deep); font-size: 10px; font-weight: 600; }
.tag--muted { background: var(--c-bg); color: var(--c-text-2); font-weight: 400; }
/* 统计数字强调色（原为模板内联 style，改为 class 以支持暗色主题）*/
.stat-num--danger { color: var(--c-danger-strong); }
.stat-num--warn { color: var(--c-warning-ink); }
.stat-num--brand { color: var(--c-brand); }

.confidence { flex: 0 0 60px; text-align: center; }
.conf-circle { width: 56px; height: 56px; border-radius: 50%; display: grid; place-items: center; background: conic-gradient(var(--c, var(--c-brand)) calc(var(--c) * 360deg), var(--c-bg) 0); position: relative; }
.conf-circle::before { content: ''; position: absolute; inset: 6px; background: var(--c-surface); border-radius: 50%; }
.conf-circle strong { font-size: 14px; color: var(--c-text); position: relative; z-index: 1; }
.conf-circle small { display: block; font-size: 9px; color: var(--c-text-2); position: relative; z-index: 1; }

.actions { display: flex; gap: 6px; flex: 0 0 auto; }
.primary-btn.small { padding: 6px 10px; font-size: 11px; }
.ghost-btn.small { padding: 6px 10px; font-size: 11px; }
.primary-btn { display: flex; gap: 6px; align-items: center; padding: 8px 14px; border: 0; background: var(--c-brand); color: var(--c-on-brand); border-radius: var(--r-sm); cursor: pointer; font-size: 13px; }
.ghost-btn { display: flex; gap: 4px; align-items: center; padding: 7px 10px; border: 1px solid var(--c-separator); background: var(--c-surface); border-radius: var(--r-sm); color: var(--c-text); cursor: pointer; }
.ghost-btn.danger { color: var(--c-danger-strong); border-color: var(--c-danger-bg); }
.op-btn { display: flex; gap: 6px; align-items: center; padding: 7px 12px; border: 1px solid var(--c-separator); background: var(--c-surface); border-radius: var(--r-sm); color: var(--c-text); font-size: 12px; cursor: pointer; }

.modal-mask { position: fixed; inset: 0; background: var(--c-overlay); display: grid; place-items: center; z-index: 100; }
.modal-card { width: min(560px, 92vw); background: var(--c-surface); border-radius: var(--r-lg); padding: 22px; }
.modal-card.large { width: min(760px, 92vw); }
.modal-card header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 18px; }
.modal-card h3 { margin: 0; display: flex; gap: 6px; align-items: center; color: var(--c-text); }
.icon-btn { background: transparent; border: 0; cursor: pointer; color: var(--c-text-2); padding: 4px; }

.detail-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; }
.detail-grid section { padding: 14px; background: var(--c-surface-2); border-radius: var(--r-md); }
.detail-grid h4 { margin: 0 0 10px; font-size: 13px; color: var(--c-brand-deep); }
.detail-grid .reason-block { grid-column: 1 / -1; }
.detail-grid ul, .detail-grid ol { margin: 0; padding-left: 18px; font-size: 12px; color: var(--c-text); line-height: 1.8; }
.detail-grid .kind { display: inline-block; padding: 1px 6px; background: var(--c-brand); color: var(--c-on-brand); font-size: 10px; border-radius: 3px; margin-right: 6px; }

.vote-bar { display: flex; gap: 10px; align-items: center; margin-bottom: 8px; }
.vote-bar .model-name { width: 70px; font-size: 12px; color: var(--c-brand-deep); }
.vote-bar .track { flex: 1; height: 8px; background: var(--c-border-2); border-radius: var(--r-xs); overflow: hidden; }
.vote-bar .fill { height: 100%; border-radius: var(--r-xs); transition: width .3s; }
.vote-bar code { font-size: 11px; color: var(--c-brand-deep); }

.conf-circle--empty { background: var(--c-bg); }
.conf-circle--empty strong { color: var(--c-text-2); }

.metric-row { display: flex; gap: 10px; align-items: center; margin-bottom: 10px; }
.metric-name { width: 80px; font-size: 12px; color: var(--c-brand-deep); }
.metric-row .track { flex: 1; height: 8px; background: var(--c-border-2); border-radius: var(--r-xs); overflow: hidden; }
.metric-row .fill { height: 100%; border-radius: var(--r-xs); transition: width .3s; }
.metric-row code { font-size: 11px; color: var(--c-brand-deep); min-width: 48px; text-align: right; }
.metric-meta { margin: 10px 0 0; font-size: 11px; color: var(--c-text-2); line-height: 1.7; }
.metric-warn { margin: 6px 0 0; font-size: 11px; color: var(--c-warning-ink); line-height: 1.7; }
.link-line { margin: 0 0 8px; font-size: 12px; color: var(--c-text); }
.link-line code { padding: 2px 6px; background: var(--c-bg); border-radius: var(--r-xs); font-size: 11px; }
.hint-text { margin: 8px 0 0; font-size: 11px; color: var(--c-text-2); line-height: 1.7; }

.filter-hint { margin-left: auto; color: var(--c-text-2); font-size: 11px; }

.dismiss-tip { margin: 0 0 14px; font-size: 12px; color: var(--c-text-2); line-height: 1.7; }
.dismiss-field { display: flex; flex-direction: column; gap: 6px; font-size: 12px; color: var(--c-brand-deep); }
.dismiss-field textarea { padding: 8px 10px; border: 1px solid var(--c-separator); border-radius: var(--r-sm); font-family: inherit; font-size: 12px; color: var(--c-text); background: var(--c-surface); resize: vertical; }
.dismiss-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px; }
.primary-btn.danger { background: var(--c-danger-strong); }
.primary-btn:disabled, .ghost-btn:disabled { opacity: .55; cursor: not-allowed; }

.spin { animation: spin 1s linear infinite; }
@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }

@media (max-width: 1280px) {
  .stat-grid { grid-template-columns: repeat(2, 1fr); }
}
@media (max-width: 768px) {
  .ai-results-shell { padding: 16px; }
  .page-header { flex-direction: column; align-items: flex-start; gap: 12px; }
  .stat-grid { grid-template-columns: 1fr; gap: 10px; }
  .detail-grid { grid-template-columns: 1fr; gap: 12px; }
  .bars { gap: 10px; padding: 0 8px; }
  .bar { width: 24px; }
  .filter-bar { flex-wrap: wrap; gap: 8px; }
  .filter-bar input[type=range] { width: 100%; }
  .result-list li { flex-wrap: wrap; }
  .confidence { flex: 1 0 100%; display: flex; justify-content: flex-start; gap: 8px; align-items: center; }
  .actions { width: 100%; }
}
</style>
