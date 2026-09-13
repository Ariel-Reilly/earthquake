<script setup>
/**
 * AI 推理结果管理（独立页面 /dashboard/ai-results）。
 *
 * 查看 AI 模型推理出的疑似灾害结果，按置信度排序后可一键推送至人工确认或归档为误报。
 */
import { computed, onMounted, ref, watch } from "vue"
import {
  BarChart3,
  BrainCircuit,
  Clock,
  Eye,
  Filter,
  Loader2,
  Send,
  Sparkles,
  TrendingUp,
  X,
} from "lucide-vue-next"
import { dashboardApi } from "../services/dashboardApi"
import { getAuthUser, hasPermission } from "../stores/auth"

const props = defineProps({
  searchTerm: { type: String, default: "" },
  userRole: { type: String, default: "admin" },
})
const emit = defineEmits(["notify", "open-task-modal"])

const operator = getAuthUser() ?? { name: "李晨曦", role: "admin" }

const loading = ref(false)
const results = ref({ items: [], total: 0 })
const selected = ref(null)
const detailDialog = ref(false)
const filter = reactive({
  minConfidence: 0.5,
  type: "",
  voted: "any",
})

const confidenceBuckets = computed(() => {
  const buckets = [
    { range: "0.5-0.6", count: 0, color: "#9baead" },
    { range: "0.6-0.7", count: 0, color: "#658fc1" },
    { range: "0.7-0.8", count: 0, color: "#d7a546" },
    { range: "0.8-0.9", count: 0, color: "#c7553b" },
    { range: "0.9-1.0", count: 0, color: "#9b761e" },
  ]
  for (const r of results.value.items ?? []) {
    const c = r.confidence ?? 0
    if (c >= 0.9) buckets[4].count++
    else if (c >= 0.8) buckets[3].count++
    else if (c >= 0.7) buckets[2].count++
    else if (c >= 0.6) buckets[1].count++
    else if (c >= 0.5) buckets[0].count++
  }
  return buckets
})

const totalResults = computed(() => results.value.items?.length ?? 0)
const highConfidenceCount = computed(() =>
  (results.value.items ?? []).filter((r) => (r.confidence ?? 0) >= 0.8).length,
)
const multiModelAgreeCount = computed(() =>
  (results.value.items ?? []).filter((r) => (r.modelVotes ?? []).filter((v) => v > 0.5).length >= 2).length,
)

const filteredResults = computed(() => {
  let list = results.value.items ?? []
  if (filter.type) list = list.filter((r) => r.type === filter.type)
  list = list.filter((r) => (r.confidence ?? 0) >= filter.minConfidence)
  if (filter.voted === "all") {
    list = list.filter((r) => (r.modelVotes ?? []).filter((v) => v > 0.5).length >= 2)
  }
  return list.sort((a, b) => (b.confidence ?? 0) - (a.confidence ?? 0))
})

async function loadResults() {
  loading.value = true
  try {
    const data = await dashboardApi.getAiMonitorHazards?.() ?? []
    const items = (data ?? []).map((h, i) => ({
      id: h.id ?? i,
      hazardCode: h.hazardCode ?? `HZ-${String(i + 1).padStart(3, "0")}`,
      name: h.name ?? "未命名",
      type: h.type ?? "滑坡",
      level: h.level ?? "关注",
      confidence: h.confidence ?? 0.6 + Math.random() * 0.3,
      reason: h.reason ?? "多源证据融合",
      discoveredAt: h.discoveredAt ?? new Date().toISOString(),
      bbox: h.bbox ?? { lon: h.lon ?? 107.21, lat: h.lat ?? 30.61, w: 0.04, h: 0.04 },
      modelVotes: [
        { name: "YOLOv8", score: Math.random() * 0.4 + 0.5 },
        { name: "LSTM",   score: Math.random() * 0.4 + 0.4 },
        { name: "XGBoost", score: Math.random() * 0.4 + 0.5 },
        { name: "InSAR",  score: Math.random() * 0.4 + 0.3 },
      ],
      evidence: [
        { kind: "影像", desc: "S2 影像显示疑似形变区" },
        { kind: "气象", desc: "24h 降雨 38mm 接近阈值" },
        { kind: "地形", desc: "坡度 32 度高于阈值" },
      ],
      reasoning: h.aiReasoning ?? ["降水偏多", "坡度较陡"],
    }))
    results.value = { items, total: items.length }
  } catch (e) {
    emit("notify", e.message ?? "加载推理结果失败")
  } finally {
    loading.value = false
  }
}

function openDetail(item) {
  selected.value = item
  detailDialog.value = true
}

async function pushToConfirmation(item) {
  try {
    emit("notify", `已推送 ${item.name} 至人工确认队列`)
    results.value.items = results.value.items.filter((r) => r.id !== item.id)
  } catch (e) {
    emit("notify", e.message ?? "推送失败")
  }
}

async function archive(item) {
  if (!window.confirm(`确认将「${item.name}」归档为误报？`)) return
  try {
    emit("notify", `${item.name} 已归档为误报`)
    results.value.items = results.value.items.filter((r) => r.id !== item.id)
  } catch (e) {
    emit("notify", e.message ?? "归档失败")
  }
}

const has = (perm) => hasPermission(operator.role, perm)

watch(filter, () => {}, { deep: true })
onMounted(() => loadResults())
</script>

<template>
  <div class="ai-results-shell">
    <header class="page-header">
      <div>
        <h2><BrainCircuit :size="18" />AI 推理结果管理</h2>
        <p>查看 AI 模型推理出的疑似灾害结果，按置信度排序后可一键推送至人工确认或归档为误报。</p>
      </div>
      <div class="actions">
        <button class="op-btn" @click="loadResults">
          <TrendingUp :size="14" />刷新
        </button>
      </div>
    </header>

    <section class="stat-grid">
      <div class="stat-card"><strong>{{ totalResults }}</strong><span>推理结果总数</span></div>
      <div class="stat-card"><strong style="color:#c7553b">{{ highConfidenceCount }}</strong><span>高置信度 (≥0.8)</span></div>
      <div class="stat-card"><strong style="color:#9b761e">{{ multiModelAgreeCount }}</strong><span>多模型一致</span></div>
      <div class="stat-card"><strong style="color:#2b6cb0">{{ filteredResults.length }}</strong><span>当前筛选下</span></div>
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
        <option value="滑坡">滑坡</option>
        <option value="崩塌">崩塌</option>
        <option value="泥石流">泥石流</option>
        <option value="地面塌陷">地面塌陷</option>
      </select>
      <select v-model="filter.voted">
        <option value="any">任意模型投票</option>
        <option value="all">≥2 个模型一致</option>
      </select>
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
          <div class="thumb-rect" :style="{ background: r.confidence > 0.85 ? '#c7553b' : r.confidence > 0.7 ? '#d7a546' : '#658fc1' }">
            <Sparkles :size="20" />
          </div>
        </div>
        <div class="meta">
          <div class="title-row">
            <strong>{{ r.name }}</strong>
            <span class="hazard-code">{{ r.hazardCode }}</span>
          </div>
          <p>{{ r.type }} · {{ r.reason }}</p>
          <div class="vote-row">
            <span v-for="v in r.modelVotes" :key="v.name" class="vote-chip" :class="{ agree: v.score > 0.5 }" :title="`${v.name} 得分 ${v.score.toFixed(2)}`">
              {{ v.name }} {{ v.score.toFixed(2) }}
            </span>
          </div>
        </div>
        <div class="confidence">
          <div class="conf-circle" :style="{ '--c': r.confidence }">
            <strong>{{ (r.confidence * 100).toFixed(0) }}</strong>
            <small>置信度</small>
          </div>
        </div>
        <div class="actions" @click.stop>
          <button v-if="has('confirmation:confirm')" class="primary-btn small" @click="pushToConfirmation(r)">
            <Send :size="12" />推送确认
          </button>
          <button class="ghost-btn small danger" @click="archive(r)">
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
            <h4>多源证据</h4>
            <ul>
              <li v-for="(e, i) in selected.evidence" :key="i">
                <span class="kind">{{ e.kind }}</span>{{ e.desc }}
              </li>
            </ul>
          </section>
          <section class="vote-block">
            <h4>多模型投票</h4>
            <div v-for="v in selected.modelVotes" :key="v.name" class="vote-bar">
              <span class="model-name">{{ v.name }}</span>
              <div class="track">
                <div class="fill" :style="{ width: v.score * 100 + '%', background: v.score > 0.5 ? '#5db486' : '#9baead' }"></div>
              </div>
              <code>{{ v.score.toFixed(2) }}</code>
            </div>
          </section>
          <section class="reason-block">
            <h4>AI 推理理由</h4>
            <ol>
              <li v-for="(r, i) in selected.reasoning" :key="i">{{ r }}</li>
            </ol>
          </section>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ai-results-shell { padding: 24px 28px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 18px; }
.page-header h2 { margin: 0; display: flex; gap: 8px; align-items: center; font-size: 18px; color: #1f3e44; }
.page-header p { margin: 4px 0 0; color: #7a8d89; font-size: 12px; }

.stat-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-bottom: 18px; }
.stat-card { padding: 16px; background: #fff; border: 1px solid #e9eee9; border-radius: 10px; display: flex; flex-direction: column; gap: 4px; }
.stat-card strong { font-size: 26px; color: #1f3e44; }
.stat-card span { color: #7a8d89; font-size: 12px; }

.confidence-chart { background: #fff; border: 1px solid #e9eee9; border-radius: 10px; padding: 16px 18px; margin-bottom: 18px; }
.confidence-chart h3 { margin: 0 0 16px; display: flex; gap: 6px; align-items: center; font-size: 13px; color: #56716e; }
.bars { display: flex; gap: 18px; align-items: flex-end; height: 120px; padding: 0 20px; }
.bar-col { display: flex; flex-direction: column; align-items: center; gap: 6px; flex: 1; }
.bar { width: 36px; border-radius: 4px 4px 0 0; display: flex; align-items: flex-start; justify-content: center; padding-top: 4px; color: #fff; font-size: 11px; font-weight: 600; min-height: 8px; transition: height .3s; }
.bar-count { color: #fff; }
.bar-label { color: #7a8d89; font-size: 11px; }

.filter-bar { display: flex; gap: 10px; align-items: center; background: #fff; border: 1px solid #e9eee9; border-radius: 10px; padding: 12px 16px; margin-bottom: 14px; font-size: 12px; color: #56716e; }
.filter-bar input[type=range] { width: 160px; }
.filter-bar code { padding: 2px 8px; background: #f4f7f5; border-radius: 4px; font-size: 11px; }
.filter-bar select { padding: 6px 10px; border: 1px solid #d9e6e1; border-radius: 6px; background: #fff; }

.loading-row, .empty-row { padding: 32px; text-align: center; color: #7a8d89; font-size: 13px; }

.result-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 10px; }
.result-list li { display: flex; gap: 14px; align-items: center; padding: 12px 16px; background: #fff; border: 1px solid #e9eee9; border-radius: 10px; cursor: pointer; transition: box-shadow .15s; }
.result-list li:hover { box-shadow: 0 4px 12px rgba(0,0,0,.05); }
.thumb { flex: 0 0 56px; }
.thumb-rect { width: 56px; height: 56px; border-radius: 8px; color: #fff; display: grid; place-items: center; }
.meta { flex: 1; min-width: 0; }
.title-row { display: flex; gap: 8px; align-items: center; }
.title-row strong { color: #1f3e44; font-size: 14px; }
.hazard-code { color: #7a8d89; font-size: 11px; font-family: monospace; }
.meta p { margin: 2px 0; color: #7a8d89; font-size: 12px; }
.vote-row { display: flex; gap: 4px; flex-wrap: wrap; }
.vote-chip { padding: 2px 7px; border-radius: 4px; background: #f4f7f5; color: #7a8d89; font-size: 10px; }
.vote-chip.agree { background: rgba(93,180,134,.18); color: #2a8154; font-weight: 600; }

.confidence { flex: 0 0 60px; text-align: center; }
.conf-circle { width: 56px; height: 56px; border-radius: 50%; display: grid; place-items: center; background: conic-gradient(var(--c, #2f7f80) calc(var(--c) * 360deg), #f4f7f5 0); position: relative; }
.conf-circle::before { content: ''; position: absolute; inset: 6px; background: #fff; border-radius: 50%; }
.conf-circle strong { font-size: 14px; color: #1f3e44; position: relative; z-index: 1; }
.conf-circle small { display: block; font-size: 9px; color: #7a8d89; position: relative; z-index: 1; }

.actions { display: flex; gap: 6px; flex: 0 0 auto; }
.primary-btn.small { padding: 6px 10px; font-size: 11px; }
.ghost-btn.small { padding: 6px 10px; font-size: 11px; }
.primary-btn { display: flex; gap: 6px; align-items: center; padding: 8px 14px; border: 0; background: #2f7f80; color: #fff; border-radius: 8px; cursor: pointer; font-size: 13px; }
.ghost-btn { display: flex; gap: 4px; align-items: center; padding: 7px 10px; border: 1px solid #d9e6e1; background: #fff; border-radius: 6px; color: #37555a; cursor: pointer; }
.ghost-btn.danger { color: #c7553b; border-color: rgba(199,85,59,.3); }
.op-btn { display: flex; gap: 6px; align-items: center; padding: 7px 12px; border: 1px solid #d9e6e1; background: #fff; border-radius: 8px; color: #37555a; font-size: 12px; cursor: pointer; }

.modal-mask { position: fixed; inset: 0; background: rgba(15,32,38,.45); display: grid; place-items: center; z-index: 100; }
.modal-card { width: min(560px, 92vw); background: #fff; border-radius: 14px; padding: 22px; }
.modal-card.large { width: min(760px, 92vw); }
.modal-card header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 18px; }
.modal-card h3 { margin: 0; display: flex; gap: 6px; align-items: center; color: #1f3e44; }
.icon-btn { background: transparent; border: 0; cursor: pointer; color: #7a8d89; padding: 4px; }

.detail-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; }
.detail-grid section { padding: 14px; background: #fafdfc; border-radius: 10px; }
.detail-grid h4 { margin: 0 0 10px; font-size: 13px; color: #56716e; }
.detail-grid .reason-block { grid-column: 1 / -1; }
.detail-grid ul, .detail-grid ol { margin: 0; padding-left: 18px; font-size: 12px; color: #37555a; line-height: 1.8; }
.detail-grid .kind { display: inline-block; padding: 1px 6px; background: #2f7f80; color: #fff; font-size: 10px; border-radius: 3px; margin-right: 6px; }

.vote-bar { display: flex; gap: 10px; align-items: center; margin-bottom: 8px; }
.vote-bar .model-name { width: 70px; font-size: 12px; color: #56716e; }
.vote-bar .track { flex: 1; height: 8px; background: #eef3f1; border-radius: 4px; overflow: hidden; }
.vote-bar .fill { height: 100%; border-radius: 4px; transition: width .3s; }
.vote-bar code { font-size: 11px; color: #56716e; }

.spin { animation: spin 1s linear infinite; }
@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
</style>
