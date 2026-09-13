<script setup>
/**
 * 系统监控（独立页面 /dashboard/system-monitor）。
 *
 * 服务健康、API 调用、存储、CPU/GPU、实时告警 —— 30 秒自动刷新。
 */
import { computed, onBeforeUnmount, onMounted, ref } from "vue"
import {
  Activity,
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
  return a ? `${a.successRate.toFixed(2)}%` : "—"
})

const alerts = computed(() => [
  { id: 1, level: "critical", title: "AI Service GPU 利用率持续 > 90%", time: "5 分钟前", resolved: false, source: "AI Service" },
  { id: 2, level: "warning",  title: "TDengine 连接延迟偏高（> 200ms）", time: "12 分钟前", resolved: false, source: "Database" },
  { id: 3, level: "warning",  title: "设备 RAIN-052 离线", time: "21 分钟前", resolved: false, source: "Device" },
  { id: 4, level: "info",     title: "OSM 数据源连接异常，自动降级到 mock", time: "32 分钟前", resolved: true, source: "Data Source" },
  { id: 5, level: "info",     title: "后端自动备份完成", time: "2 小时前", resolved: true, source: "Backup" },
])

const filteredAlerts = computed(() => {
  let list = alerts.value
  if (alertFilter.value === "active") list = list.filter((a) => !a.resolved)
  if (alertFilter.value === "resolved") list = list.filter((a) => a.resolved)
  return list
})

const trendMax = (arr) => Math.max(...(arr ?? [1]), 1)

function trendPath(values) {
  if (!values?.length) return ""
  const max = trendMax(values)
  const stepX = 100 / (values.length - 1)
  return values.map((v, i) => `${i === 0 ? 'M' : 'L'} ${i * stepX},${40 - (v / max) * 36}`).join(" ")
}

const levelColor = {
  critical: "#c7553b",
  warning: "#d7a546",
  info: "#5d95c1",
}
</script>

<template>
  <div class="system-monitor-shell">
    <header class="page-header">
      <div>
        <h2><Activity :size="18" />系统监控</h2>
        <p>服务健康、API 调用、存储、CPU/GPU、实时告警 —— 30 秒自动刷新。</p>
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
        <article v-for="svc in data.services" :key="svc.name" class="service-card" :class="svc.state.toLowerCase()">
          <div class="svc-head">
            <ServerIcon :size="18" />
            <strong>{{ svc.name }}</strong>
            <span class="state-dot" :class="svc.state.toLowerCase()"></span>
          </div>
          <div class="svc-meta">
            <span><small>延迟</small>{{ svc.latency }} ms</span>
            <span><small>版本</small><code>{{ svc.version }}</code></span>
          </div>
        </article>
      </section>

      <section class="kpi-grid">
        <div class="kpi-card">
          <Wifi :size="18" color="#2b6cb0" />
          <strong>{{ data.apiCalls24h.total.toLocaleString() }}</strong>
          <span>API 调用 (24h)</span>
          <small>成功 {{ apiSuccessRate }} · 失败 {{ data.apiCalls24h.failed }}</small>
        </div>
        <div class="kpi-card">
          <HardDrive :size="18" color="#9b761e" />
          <strong>{{ data.storage.pg.used }} / {{ data.storage.pg.total }} GB</strong>
          <span>PostgreSQL 存储</span>
          <div class="progress"><div class="fill" :style="{ width: (data.storage.pg.used / data.storage.pg.total) * 100 + '%' }"></div></div>
        </div>
        <div class="kpi-card">
          <DatabaseIcon :size="18" color="#2a8154" />
          <strong>{{ data.storage.td.used }} / {{ data.storage.td.total }} GB</strong>
          <span>TDengine 存储</span>
          <div class="progress"><div class="fill" :style="{ width: (data.storage.td.used / data.storage.td.total) * 100 + '%', background: '#2a8154' }"></div></div>
        </div>
        <div class="kpi-card">
          <DatabaseIcon :size="18" color="#5db486" />
          <strong>{{ data.storage.minio.used }} / {{ data.storage.minio.total }} GB</strong>
          <span>MinIO 对象存储</span>
          <div class="progress"><div class="fill" :style="{ width: (data.storage.minio.used / data.storage.minio.total) * 100 + '%', background: '#5db486' }"></div></div>
        </div>
      </section>

      <section class="trends-grid">
        <article class="trend-card">
          <header>
            <Cpu :size="16" />
            <strong>CPU 使用率</strong>
            <span>{{ data.trends.cpu.at(-1) }}%</span>
          </header>
          <svg viewBox="0 0 100 40" preserveAspectRatio="none" class="trend-svg">
            <path :d="trendPath(data.trends.cpu)" stroke="#2f7f80" stroke-width="1.5" fill="none" />
          </svg>
        </article>
        <article class="trend-card">
          <header>
            <Cpu :size="16" />
            <strong>GPU 使用率</strong>
            <span>{{ data.trends.gpu.at(-1) }}%</span>
          </header>
          <svg viewBox="0 0 100 40" preserveAspectRatio="none" class="trend-svg">
            <path :d="trendPath(data.trends.gpu)" stroke="#9b761e" stroke-width="1.5" fill="none" />
          </svg>
        </article>
        <article class="trend-card">
          <header>
            <MemoryStick :size="16" />
            <strong>内存使用率</strong>
            <span>{{ data.trends.mem.at(-1) }}%</span>
          </header>
          <svg viewBox="0 0 100 40" preserveAspectRatio="none" class="trend-svg">
            <path :d="trendPath(data.trends.mem)" stroke="#2a8154" stroke-width="1.5" fill="none" />
          </svg>
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
.page-header h2 { margin: 0; display: flex; gap: 8px; align-items: center; font-size: 18px; color: #1f3e44; }
.page-header p { margin: 4px 0 0; color: #7a8d89; font-size: 12px; }
.op-btn { display: flex; gap: 6px; align-items: center; padding: 7px 12px; border: 1px solid #d9e6e1; background: #fff; border-radius: 8px; color: #37555a; font-size: 12px; cursor: pointer; }
.loading-row { padding: 32px; text-align: center; color: #7a8d89; }

.services-grid { display: grid; grid-template-columns: repeat(6, 1fr); gap: 10px; margin-bottom: 18px; }
.service-card { background: #fff; border: 1px solid #e9eee9; border-radius: 10px; padding: 12px; }
.service-card.down { border-color: rgba(199,85,59,.4); background: rgba(199,85,59,.04); }
.svc-head { display: flex; gap: 6px; align-items: center; margin-bottom: 8px; color: #1f3e44; }
.svc-head strong { flex: 1; font-size: 12px; }
.state-dot { width: 8px; height: 8px; border-radius: 50%; }
.state-dot.up { background: #5db486; box-shadow: 0 0 4px rgba(93,180,134,.5); }
.state-dot.down { background: #c7553b; }
.svc-meta { display: flex; flex-direction: column; gap: 4px; font-size: 11px; color: #56716e; }
.svc-meta span { display: flex; gap: 6px; align-items: center; }
.svc-meta small { width: 28px; color: #9aacab; }
.svc-meta code { font-size: 10px; color: #7a8d89; }

.kpi-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-bottom: 18px; }
.kpi-card { background: #fff; border: 1px solid #e9eee9; border-radius: 10px; padding: 14px 16px; display: flex; flex-direction: column; gap: 4px; }
.kpi-card strong { font-size: 18px; color: #1f3e44; }
.kpi-card span { color: #56716e; font-size: 12px; }
.kpi-card small { color: #9aacab; font-size: 11px; }
.progress { height: 6px; background: #f4f7f5; border-radius: 3px; margin-top: 8px; overflow: hidden; }
.progress .fill { height: 100%; background: #9b761e; border-radius: 3px; transition: width .3s; }

.trends-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; margin-bottom: 18px; }
.trend-card { background: #fff; border: 1px solid #e9eee9; border-radius: 10px; padding: 14px 16px; }
.trend-card header { display: flex; gap: 6px; align-items: center; margin-bottom: 10px; }
.trend-card strong { flex: 1; font-size: 13px; color: #1f3e44; }
.trend-card span { color: #56716e; font-size: 12px; font-weight: 600; }
.trend-svg { width: 100%; height: 50px; background: #fafdfc; border-radius: 6px; }

.alerts-section { background: #fff; border: 1px solid #e9eee9; border-radius: 10px; padding: 14px 16px; }
.alerts-section > header { display: flex; gap: 10px; align-items: center; margin-bottom: 14px; }
.alerts-section h3 { margin: 0; display: flex; gap: 6px; align-items: center; font-size: 14px; color: #1f3e44; }
.alert-tabs { margin-left: auto; display: flex; gap: 4px; background: #f4f7f5; padding: 3px; border-radius: 8px; }
.alert-tabs button { padding: 5px 10px; border: 0; background: transparent; border-radius: 5px; cursor: pointer; font-size: 11px; color: #56716e; }
.alert-tabs button.active { background: #fff; color: #1f3e44; box-shadow: 0 1px 3px rgba(0,0,0,.06); }

.alert-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
.alert-list li { display: flex; gap: 10px; align-items: center; padding: 10px 12px; border-radius: 8px; background: #fafdfc; }
.alert-list li.level-critical { border-left: 3px solid #c7553b; }
.alert-list li.level-warning { border-left: 3px solid #d7a546; }
.alert-list li.level-info { border-left: 3px solid #5d95c1; }
.alert-list li.resolved { opacity: .6; }
.alert-list li.level-critical :deep(svg) { color: #c7553b; }
.alert-list li.level-warning :deep(svg) { color: #d7a546; }
.alert-list li.level-info :deep(svg) { color: #5d95c1; }
.alert-body { flex: 1; }
.alert-body strong { display: block; font-size: 13px; color: #1f3e44; }
.alert-body small { color: #7a8d89; font-size: 11px; }
.resolved-tag { display: inline-flex; gap: 4px; align-items: center; padding: 2px 8px; background: rgba(93,180,134,.12); color: #2a8154; border-radius: 4px; font-size: 11px; }

.spin { animation: spin 1s linear infinite; }
@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
</style>
