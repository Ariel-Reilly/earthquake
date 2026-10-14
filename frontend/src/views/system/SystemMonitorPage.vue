<script setup>
/**
 * 系统监控（独立页面 /dashboard/system-monitor）。
 *
 * 服务健康、API 调用、存储、CPU/GPU、实时告警 —— 30 秒自动刷新。
 */
import { computed, onBeforeUnmount, onMounted, ref } from "vue"
import {
  Radar,
  AlertTriangle,
  Bell,
  CheckCircle2,
  Cpu,
  Database as DatabaseIcon,
  HardDrive,
  Loader2,
  MemoryStick,
  RefreshCw,
  Server as ServerIcon,
  Wifi,
} from "lucide-vue-next"
import { dashboardApi } from "../../services/dashboardApi"

const props = defineProps({
  searchTerm: { type: String, default: "" },
  userRole: { type: String, default: "admin" },
})
const emit = defineEmits(["notify"])

const loading = ref(false)
const data = ref(null)
const alertFilter = ref("all")
let timer = null

async function load() {
  loading.value = true
  try {
    data.value = await dashboardApi.getSystemMonitor?.()
  } catch (e) {
    emit("notify", e.message ?? "加载监控数据失败")
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  load()
  timer = setInterval(load, 30000)
})
onBeforeUnmount(() => clearInterval(timer))

const apiSuccessRate = computed(() => {
  const a = data.value?.apiCalls24h
  return a?.successRate != null && a.available !== false ? `${a.successRate.toFixed(2)}%` : "—"
})

/** 告警：来自后端真实探测结果派生（服务 DOWN / CPU 内存超阈值）。 */
const alerts = computed(() => data.value?.alerts ?? [])

const filteredAlerts = computed(() => {
  let list = alerts.value
  if (alertFilter.value === "active") list = list.filter((a) => !a.resolved)
  if (alertFilter.value === "resolved") list = list.filter((a) => a.resolved)
  return list
})

/** 后端服务健康探测结果（真实 TCP 探测）。 */
const services = computed(() => data.value?.services ?? [])

/** API 24h 统计是否可用。 */
const apiCallsAvailable = computed(() => data.value?.apiCalls24h?.available !== false)

/** 存储用量是否可用。 */
const storageAvailable = computed(
  () => data.value?.storage?.available !== false && data.value?.storage?.pg != null,
)

/** 趋势数据是否可用（需时间序列采样）。 */
const trendsAvailable = computed(() => data.value?.trends?.available !== false)

/** 服务状态中文标签。 */
const STATE_LABEL = { UP: "正常", DOWN: "不可达", DISABLED: "未启用", UNKNOWN: "未知" }
const stateLabel = (state) => STATE_LABEL[state] ?? state ?? "未知"

const trendMax = (arr) => Math.max(...(arr ?? [1]), 1)

function trendPath(values) {
  if (!values?.length) return ""
  const max = trendMax(values)
  const stepX = 100 / (values.length - 1)
  return values.map((v, i) => `${i === 0 ? 'M' : 'L'} ${i * stepX},${40 - (v / max) * 36}`).join(" ")
}

const levelColor = {
  critical: "#c7553b",
  warning: "#c4a86e",
  info: "#5d95c1",
}
</script>

<template>
  <div class="system-monitor-shell">
    <header class="page-header">
      <div>
      </div>
      <div class="actions">
        <button class="op-btn" :disabled="loading" @click="load">
          <RefreshCw :size="14" :class="loading ? 'spin' : ''" />刷新
        </button>
      </div>
    </header>

    <div v-if="!data && loading" class="loading-row">
      <Loader2 :size="16" class="spin" />加载监控…
    </div>
    <template v-else-if="data">
      <section class="services-grid">
        <article
          v-for="svc in services"
          :key="svc.name"
          class="service-card"
          :class="svc.state.toLowerCase()"
        >
          <div class="svc-head">
            <ServerIcon :size="18" />
            <strong>{{ svc.name }}</strong>
            <span class="state-dot" :class="svc.state.toLowerCase()"></span>
          </div>
          <div class="svc-meta">
            <span>
              <small>状态</small>
              <em :class="'svc-state-' + svc.state.toLowerCase()">{{ stateLabel(svc.state) }}</em>
            </span>
            <span v-if="svc.latency >= 0"><small>延迟</small>{{ svc.latency }} ms</span>
            <span v-else><small>延迟</small>—</span>
          </div>
        </article>
      </section>

      <section class="kpi-grid">
        <div class="kpi-card">
          <Wifi :size="18" color="#6f9e7f" />
          <strong v-if="apiCallsAvailable">{{ data.apiCalls24h.total.toLocaleString() }}</strong>
          <strong v-else>—</strong>
          <span>API 调用 (24h)</span>
          <small v-if="apiCallsAvailable">成功 {{ apiSuccessRate }} · 失败 {{ data.apiCalls24h.failed }}</small>
          <small v-else>后端暂未提供接口调用统计</small>
        </div>
        <div class="kpi-card">
          <HardDrive :size="18" color="#9b761e" />
          <strong v-if="storageAvailable">{{ data.storage.pg.used }} / {{ data.storage.pg.total }} GB</strong>
          <strong v-else>—</strong>
          <span>PostgreSQL 存储</span>
          <div v-if="storageAvailable" class="progress">
            <div class="fill" :style="{ width: (data.storage.pg.used / data.storage.pg.total) * 100 + '%' }"></div>
          </div>
          <small v-else>后端暂未提供存储统计</small>
        </div>
        <div class="kpi-card">
          <DatabaseIcon :size="18" color="#4a7a5e" />
          <strong v-if="storageAvailable && data.storage.td">{{ data.storage.td.used }} / {{ data.storage.td.total }} GB</strong>
          <strong v-else>—</strong>
          <span>TDengine 存储</span>
          <div v-if="storageAvailable && data.storage.td" class="progress">
            <div class="fill" :style="{ width: (data.storage.td.used / data.storage.td.total) * 100 + '%', background: '#4a7a5e' }"></div>
          </div>
          <small v-else>后端暂未提供存储统计</small>
        </div>
        <div class="kpi-card">
          <DatabaseIcon :size="18" color="#6f9e7f" />
          <strong v-if="storageAvailable && data.storage.minio">{{ data.storage.minio.used }} / {{ data.storage.minio.total }} GB</strong>
          <strong v-else>—</strong>
          <span>MinIO 对象存储</span>
          <div v-if="storageAvailable && data.storage.minio" class="progress">
            <div class="fill" :style="{ width: (data.storage.minio.used / data.storage.minio.total) * 100 + '%', background: '#6f9e7f' }"></div>
          </div>
          <small v-else>后端暂未提供存储统计</small>
        </div>
      </section>

      <section class="trends-grid">
        <article class="trend-card">
          <header>
            <Cpu :size="16" />
            <strong>CPU 使用率</strong>
            <span>{{ data.cpuUsage >= 0 ? data.cpuUsage.toFixed(1) + '%' : '—' }}</span>
          </header>
          <svg v-if="trendsAvailable" viewBox="0 0 100 40" preserveAspectRatio="none" class="trend-svg">
            <path :d="trendPath(data.trends.cpu)" stroke="#6f9e7f" stroke-width="1.5" fill="none" />
          </svg>
          <div v-else class="trend-empty">
            <span class="trend-now">{{ data.cpuUsage >= 0 ? data.cpuUsage.toFixed(1) + '%' : '—' }}</span>
            <small>趋势需时间序列采样，后端暂未提供</small>
          </div>
        </article>
        <article class="trend-card">
          <header>
            <Cpu :size="16" />
            <strong>GPU 使用率</strong>
            <span>{{ trendsAvailable && data.trends.gpu.length ? data.trends.gpu.at(-1) + '%' : '—' }}</span>
          </header>
          <svg v-if="trendsAvailable && data.trends.gpu.length" viewBox="0 0 100 40" preserveAspectRatio="none" class="trend-svg">
            <path :d="trendPath(data.trends.gpu)" stroke="#9b761e" stroke-width="1.5" fill="none" />
          </svg>
          <div v-else class="trend-empty">
            <span class="trend-now">—</span>
            <small>GPU 指标需硬件采集，当前环境未提供</small>
          </div>
        </article>
        <article class="trend-card">
          <header>
            <MemoryStick :size="16" />
            <strong>内存使用率</strong>
            <span>{{ data.memoryUsage != null ? data.memoryUsage.toFixed(1) + '%' : '—' }}</span>
          </header>
          <svg v-if="trendsAvailable" viewBox="0 0 100 40" preserveAspectRatio="none" class="trend-svg">
            <path :d="trendPath(data.trends.mem)" stroke="#4a7a5e" stroke-width="1.5" fill="none" />
          </svg>
          <div v-else class="trend-empty">
            <span class="trend-now">{{ data.memoryUsage != null ? data.memoryUsage.toFixed(1) + '%' : '—' }}</span>
            <small>趋势需时间序列采样，后端暂未提供</small>
          </div>
        </article>
      </section>

      <section class="alerts-section">
        <header>
          <Bell :size="16" />
          <h3>实时告警</h3>
          <div class="alert-tabs">
            <button :class="{ active: alertFilter === 'all' }" @click="alertFilter = 'all'">全部 ({{ alerts.length }})</button>
            <button :class="{ active: alertFilter === 'active' }" @click="alertFilter = 'active'">未处理 ({{ alerts.filter((a) => !a.resolved).length }})</button>
            <button :class="{ active: alertFilter === 'resolved' }" @click="alertFilter = 'resolved'">已解决 ({{ alerts.filter((a) => a.resolved).length }})</button>
          </div>
        </header>
        <ul class="alert-list">
          <li v-for="a in filteredAlerts" :key="a.id" :class="['level-' + a.level, { resolved: a.resolved }]">
            <AlertTriangle :size="14" />
            <div class="alert-body">
              <strong>{{ a.title }}</strong>
              <small>{{ a.source }} · {{ a.time }}</small>
            </div>
            <span v-if="a.resolved" class="resolved-tag"><CheckCircle2 :size="12" />已解决</span>
          </li>
        </ul>
      </section>
    </template>
  </div>
</template>

<style scoped>
.system-monitor-shell { padding: 24px 28px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 18px; }
.page-header h2 { margin: 0; display: flex; gap: 8px; align-items: center; font-size: 18px; color: var(--c-text); }
.page-header p { margin: 4px 0 0; color: var(--c-text-2); font-size: 12px; }
.op-btn { display: flex; gap: 6px; align-items: center; padding: 7px 12px; border: 1px solid var(--c-separator); background: var(--c-surface); border-radius: var(--r-sm); color: var(--c-text); font-size: 12px; cursor: pointer; }
.loading-row { padding: 32px; text-align: center; color: var(--c-text-2); }

.services-grid { display: grid; grid-template-columns: repeat(6, 1fr); gap: 10px; margin-bottom: 18px; }
.service-card { background: var(--c-surface); border: 1px solid var(--c-brand-soft); border-radius: var(--r-md); padding: 12px; }
.service-card.down { border-color: var(--c-danger-soft); background: var(--c-danger-bg); }
.svc-head { display: flex; gap: 6px; align-items: center; margin-bottom: 8px; color: var(--c-text); }
.svc-head strong { flex: 1; font-size: 12px; }
.state-dot { width: 8px; height: 8px; border-radius: 50%; }
.state-dot.up { background: var(--c-brand); box-shadow: 0 0 4px rgba(93,180,134,.5); }
.state-dot.down { background: var(--c-danger-strong); }
.state-dot.disabled { background: var(--c-brand-tint); }
.service-card.disabled { border-color: var(--c-border); background: var(--c-border-2); }
.svc-state-up { color: var(--c-brand-deep); font-style: normal; font-weight: 600; }
.svc-state-down { color: var(--c-danger-strong); font-style: normal; font-weight: 600; }
.svc-state-disabled { color: var(--c-text-3); font-style: normal; }
.svc-meta { display: flex; flex-direction: column; gap: 4px; font-size: 11px; color: var(--c-brand-deep); }
.svc-meta span { display: flex; gap: 6px; align-items: center; }
.svc-meta small { width: 28px; color: var(--c-text-3); }
.svc-meta code { font-size: 10px; color: var(--c-text-2); }

.kpi-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-bottom: 18px; }
.kpi-card { background: var(--c-surface); border: 1px solid var(--c-brand-soft); border-radius: var(--r-md); padding: 14px 16px; display: flex; flex-direction: column; gap: 4px; }
.kpi-card strong { font-size: 18px; color: var(--c-text); }
.kpi-card span { color: var(--c-brand-deep); font-size: 12px; }
.kpi-card small { color: var(--c-text-3); font-size: 11px; }
.progress { height: 6px; background: var(--c-bg); border-radius: 3px; margin-top: 8px; overflow: hidden; }
.progress .fill { height: 100%; background: var(--c-warning-ink); border-radius: 3px; transition: width .3s; }

.trends-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; margin-bottom: 18px; }
.trend-card { background: var(--c-surface); border: 1px solid var(--c-brand-soft); border-radius: var(--r-md); padding: 14px 16px; }
.trend-card header { display: flex; gap: 6px; align-items: center; margin-bottom: 10px; }
.trend-card strong { flex: 1; font-size: 13px; color: var(--c-text); }
.trend-card span { color: var(--c-brand-deep); font-size: 12px; font-weight: 600; }
.trend-svg { width: 100%; height: 50px; background: var(--c-surface-2); border-radius: var(--r-sm); }

.trend-empty {
  height: 50px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  background: var(--c-surface-2);
  border: 1px dashed var(--c-brand-soft);
  border-radius: var(--r-sm);
}
.trend-empty .trend-now { font-size: 14px; font-weight: 600; color: var(--c-brand); }
.trend-empty small { font-size: 10px; color: var(--c-text-3); }

.alerts-section { background: var(--c-surface); border: 1px solid var(--c-brand-soft); border-radius: var(--r-md); padding: 14px 16px; }
.alerts-section > header { display: flex; gap: 10px; align-items: center; margin-bottom: 14px; }
.alerts-section h3 { margin: 0; display: flex; gap: 6px; align-items: center; font-size: 14px; color: var(--c-text); }
.alert-tabs { margin-left: auto; display: flex; gap: 4px; background: var(--c-bg); padding: 3px; border-radius: var(--r-sm); }
.alert-tabs button { padding: 5px 10px; border: 0; background: transparent; border-radius: var(--r-sm); cursor: pointer; font-size: 11px; color: var(--c-brand-deep); }
.alert-tabs button.active { background: var(--c-surface); color: var(--c-text); box-shadow: 0 1px 3px rgba(0,0,0,.06); }

.alert-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
.alert-list li { display: flex; gap: 10px; align-items: center; padding: 10px 12px; border-radius: var(--r-sm); background: var(--c-surface-2); }
.alert-list li.level-critical { border-left: 3px solid var(--c-danger-strong); }
.alert-list li.level-warning { border-left: 3px solid var(--c-warning-bg); }
.alert-list li.level-info { border-left: 3px solid var(--c-info); }
.alert-list li.resolved { opacity: .6; }
.alert-list li.level-critical :deep(svg) { color: var(--c-danger-strong); }
.alert-list li.level-warning :deep(svg) { color: var(--c-warning-bg); }
.alert-list li.level-info :deep(svg) { color: var(--c-info); }
.alert-body { flex: 1; }
.alert-body strong { display: block; font-size: 13px; color: var(--c-text); }
.alert-body small { color: var(--c-text-2); font-size: 11px; }
.resolved-tag { display: inline-flex; gap: 4px; align-items: center; padding: 2px 8px; background: var(--c-success-bg); color: var(--c-brand-deep); border-radius: var(--r-xs); font-size: 11px; }

.spin { animation: spin 1s linear infinite; }
@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
</style>
