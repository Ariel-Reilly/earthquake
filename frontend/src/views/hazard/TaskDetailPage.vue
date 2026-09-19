<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  AlertOctagon, ArrowLeft, CalendarDays, Cpu, Download, FileText,
  Gauge, GitBranch, History, Loader2, MemoryStick, Paperclip,
  PauseCircle, PencilLine, PlayCircle, RefreshCcw, Save, Square,
  Terminal, UsersRound,
} from 'lucide-vue-next'
import PermissionButton from '../../components/PermissionButton.vue'
import { dashboardApi } from '../../services/dashboardApi'

const emit = defineEmits(['notify'])
const route = useRoute()
const router = useRouter()

// === 基础信息 & 编辑（保留原有交互） ===
const task = ref(null)
const loading = ref(true)
const editMode = ref(false)
const form = ref({})

// === 运行时指标 ===
const runtime = ref(null)
const runtimeLoading = ref(false)
const runtimeError = ref('')

// === 实时日志 ===
const logs = ref([])
const logScrollerRef = ref(null)
const logPaused = ref(false)
const logLevelFilter = ref('ALL')
const logLoading = ref(false)
const logError = ref('')
const logCursor = ref(null)
const LOG_MAX = 200

// === 控制动作状态 ===
const retrying = ref(false)
const terminating = ref(false)
const showTerminateConfirm = ref(false)
const autoRetried = ref(false)

let runtimeTimer = null
let logTimer = null

// === 计算属性 ===
const STATUS_LABEL = { RUNNING: '运行中', SUCCESS: '已完成', FAILED: '失败', PENDING: '排队中', PAUSED: '已暂停', TERMINATED: '已终止', TIMEOUT: '超时' }

const visibleLogs = computed(() => logLevelFilter.value === 'ALL'
  ? logs.value
  : logs.value.filter((l) => (l.level || 'INFO') === logLevelFilter.value))

const runtimeStatusLabel = computed(() => runtime.value ? (STATUS_LABEL[runtime.value.status] || runtime.value.status) : '—')

const runtimeStatusClass = computed(() => runtime.value ? `status-${(runtime.value.status || '').toLowerCase()}` : '')

const cpu = computed(() => clamp(Number(runtime.value?.cpu ?? 0), 0, 100))
const gpu = computed(() => clamp(Number(runtime.value?.gpu ?? 0), 0, 100))
const memMB = computed(() => Math.max(0, Number(runtime.value?.memMB ?? 0)))
const memMaxMB = computed(() => Math.max(2048, Math.round(memMB.value * 1.4)))
const memPercent = computed(() => Math.min(100, (memMB.value / memMaxMB.value) * 100))
const cpuBarColor = computed(() => colorForUsage(cpu.value))
const gpuBarColor = computed(() => colorForUsage(gpu.value))

const elapsedLabel = computed(() => {
  if (!runtime.value) return '00:00'
  let seconds = 0
  if (runtime.value.status === 'RUNNING' && runtime.value.startedAt) {
    seconds = Math.floor((Date.now() - new Date(runtime.value.startedAt).getTime()) / 1000)
  } else {
    seconds = Math.max(0, Number(runtime.value.duration ?? 0))
  }
  return formatDuration(seconds)
})

const elapsedPercent = computed(() => {
  if (!runtime.value) return 0
  if (['SUCCESS', 'FAILED', 'TERMINATED', 'TIMEOUT'].includes(runtime.value.status)) return 100
  const seconds = Math.max(0, Number(runtime.value.duration ?? 0))
  return Math.min(100, (seconds / 600) * 100)
})

const startedAtLabel = computed(() => formatDate(runtime.value?.startedAt))
const finishedAtLabel = computed(() => formatDate(runtime.value?.finishedAt))
const canTerminate = computed(() => runtime.value?.status === 'RUNNING')
const logMetaLabel = computed(() => `${logPaused.value ? '已暂停' : '轮询中'} · ${logs.value.length} / ${LOG_MAX}`)

// === 工具方法 ===
function clamp(n, lo, hi) { return Number.isNaN(n) ? lo : Math.min(hi, Math.max(lo, n)) }

function colorForUsage(value) {
  if (value >= 85) return '#e06060'
  if (value >= 65) return '#e0a050'
  if (value >= 40) return '#d9c040'
  return '#6dbfa5'
}

function formatDate(iso) {
  if (!iso) return '—'
  try { return new Date(iso).toLocaleString('zh-CN', { hour12: false }) }
  catch (_) { return '—' }
}

function formatDuration(seconds) {
  seconds = Math.max(0, Math.floor(seconds))
  const hh = Math.floor(seconds / 3600), mm = Math.floor((seconds % 3600) / 60), ss = seconds % 60
  const pad = (n) => String(n).padStart(2, '0')
  return hh > 0 ? `${pad(hh)}:${pad(mm)}:${pad(ss)}` : `${pad(mm)}:${pad(ss)}`
}

// === 数据加载 ===
async function loadBasic() {
  loading.value = true
  try {
    const data = await dashboardApi.getTaskDetail(route.params.id)
    task.value = data.item
    form.value = {
      name: data.item?.name ?? '',
      type: data.item?.type ?? '',
      owner: data.item?.owner ?? '',
      due: data.item?.due ?? '',
      status: data.item?.status ?? '待处理',
      progress: data.item?.progress ?? 0,
      cron: data.item?.cron ?? 'every 5m',
      trigger: data.item?.trigger ?? '周期',
    }
  } catch (err) {
    emit('notify', err instanceof Error ? err.message : '任务详情加载失败')
    task.value = null
  } finally {
    loading.value = false
  }
}

async function loadRuntime() {
  if (!task.value) return
  runtimeLoading.value = true
  runtimeError.value = ''
  try {
    runtime.value = await dashboardApi.getTaskRuntime(task.value.id)
  } catch (err) {
    runtimeError.value = err instanceof Error ? err.message : '运行时指标获取失败'
  } finally {
    runtimeLoading.value = false
  }
}

async function loadLogs(since) {
  if (!task.value || logPaused.value) return
  logLoading.value = true
  logError.value = ''
  try {
    const params = since ? { since } : {}
    const data = await dashboardApi.getTaskExecutionLog(task.value.id, params)
    const entries = Array.isArray(data?.entries) ? data.entries : []
    if (entries.length === 0) {
      logCursor.value = data?.cursor || logCursor.value || new Date().toISOString()
      return
    }
    logs.value = [...logs.value, ...entries].slice(-LOG_MAX)
    logCursor.value = data?.cursor || new Date().toISOString()
    await nextTick(() => {
      if (logScrollerRef.value && !logPaused.value) {
        logScrollerRef.value.scrollTop = logScrollerRef.value.scrollHeight
      }
    })
  } catch (err) {
    logError.value = err instanceof Error ? err.message : '日志拉取失败'
  } finally {
    logLoading.value = false
  }
}

async function refreshLogsNow() {
  logCursor.value = null
  logs.value = []
  await loadLogs()
}

async function refreshDetail() {
  await Promise.all([loadBasic(), loadRuntime(), refreshLogsNow()])
}

// === 轮询控制 ===
function startPolling() {
  stopPolling()
  runtimeTimer = window.setInterval(() => { if (!document.hidden) loadRuntime() }, 5000)
  logTimer = window.setInterval(() => {
    if (!document.hidden && !logPaused.value) loadLogs(logCursor.value)
  }, 5000)
}

function stopPolling() {
  if (runtimeTimer) { clearInterval(runtimeTimer); runtimeTimer = null }
  if (logTimer) { clearInterval(logTimer); logTimer = null }
}

onBeforeUnmount(stopPolling)

// === 保存编辑 ===
async function saveDetail() {
  if (!task.value) return
  try {
    const result = await dashboardApi.updateTask(task.value.id, {
      ...form.value,
      progress: Number(form.value.progress),
      cron: form.value.cron,
      trigger: form.value.trigger,
    })
    task.value = { ...task.value, ...result, cron: form.value.cron, trigger: form.value.trigger }
    emit('notify', '任务信息已保存')
    editMode.value = false
  } catch (err) {
    emit('notify', err instanceof Error ? err.message : '保存失败')
  }
}

// === 重试 / 终止 ===
async function performRetry(silent = false) {
  if (!task.value) return
  retrying.value = true
  try {
    await dashboardApi.retryTask(task.value.id)
    if (!silent) emit('notify', `已重新触发任务：${task.value.name}`)
    await Promise.all([loadRuntime(), refreshLogsNow()])
  } catch (err) {
    emit('notify', err instanceof Error ? err.message : '重试失败，请稍后再试')
  } finally {
    retrying.value = false
  }
}

async function performTerminate() {
  if (!task.value) return
  showTerminateConfirm.value = false
  terminating.value = true
  try {
    await dashboardApi.terminateTask(task.value.id)
    emit('notify', `已手动终止任务：${task.value.name}`)
    await loadRuntime()
  } catch (err) {
    emit('notify', err instanceof Error ? err.message : '终止失败')
  } finally {
    terminating.value = false
  }
}

function scrollToLinked() {
  const el = document.getElementById('task-linked-anchor')
  if (el && typeof el.scrollIntoView === 'function') el.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

// === 导出日志 ===
function exportLogs() {
  if (!logs.value.length) return emit('notify', '暂无日志可导出')
  const lines = logs.value.map((l) => `[${l.ts || '—'}] ${l.level || 'INFO'} ${l.message || ''}`)
  const blob = new Blob([lines.join('\n')], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `task-${task.value?.id ?? 'unknown'}-${Date.now()}.log`
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
  emit('notify', `日志已导出（${logs.value.length} 条）`)
}

// === 自动重试入口：?retry=1 ===
async function maybeAutoRetry() {
  if (route.query.retry !== '1' || autoRetried.value || !task.value) return
  autoRetried.value = true
  emit('notify', '已根据链接参数自动重试任务')
  await performRetry(true)
}

onMounted(async () => {
  await loadBasic()
  await Promise.all([loadRuntime(), refreshLogsNow()])
  startPolling()
  await maybeAutoRetry()
})
</script>

<template>
  <div>
    <div class="content-grid task-detail-grid">
      <!-- 左列：基本信息 + 运行时 + 实时日志 -->
      <div class="detail-main-col">
        <!-- 1) 基本信息 + 编辑 -->
        <section class="panel system-panel">
          <div class="panel-header compact">
            <div>
              <h2>巡检任务详情</h2>
              <p>任务信息、编辑与执行监控</p>
            </div>
            <div class="heading-actions">
              <button class="text-btn" @click="router.back()"><ArrowLeft :size="15" />返回</button>
              <button class="text-btn" @click="refreshDetail"><History :size="15" />刷新</button>
              <button class="primary-btn" @click="editMode = !editMode">
                <PencilLine :size="15" />{{ editMode ? '取消编辑' : '编辑详情' }}
              </button>
            </div>
          </div>
          <div v-if="loading" class="loading-row">加载中...</div>
          <template v-else-if="task">
            <div class="system-list">
              <div>
                <span><FileText :size="16" />任务名称</span>
                <strong v-if="!editMode">{{ task.name }}</strong>
                <input v-else v-model="form.name" />
              </div>
              <div>
                <span><UsersRound :size="16" />负责人</span>
                <strong v-if="!editMode">{{ task.owner }}</strong>
                <input v-else v-model="form.owner" />
              </div>
              <div>
                <span><CalendarDays :size="16" />任务类型</span>
                <strong v-if="!editMode">{{ task.type }}</strong>
                <input v-else v-model="form.type" />
              </div>
              <div>
                <span><Gauge :size="16" />完成进度</span>
                <strong v-if="!editMode">{{ task.progress }}%</strong>
                <input v-else v-model="form.progress" type="number" min="0" max="100" />
              </div>
              <div>
                <span><Terminal :size="16" />调度 cron</span>
                <strong v-if="!editMode">{{ form.cron }}</strong>
                <input v-else v-model="form.cron" placeholder="例如 every 5m" />
              </div>
              <div>
                <span><PlayCircle :size="16" />触发条件</span>
                <strong v-if="!editMode">{{ form.trigger }}</strong>
                <select v-else v-model="form.trigger">
                  <option value="手动">手动</option>
                  <option value="周期">周期</option>
                  <option value="紧急触发">紧急触发</option>
                </select>
              </div>
            </div>
            <div class="task-summary">
              <div class="progress-ring"><span>{{ task.progress }}<small>%</small></span></div>
              <div class="task-overview">
                <strong v-if="!editMode">截止 {{ task.due }}</strong>
                <input v-else v-model="form.due" />
                <span>任务状态：{{ task.status }}</span>
                <div class="task-bars"><i :style="{ width: `${task.progress}%` }"></i></div>
                <p>完成后请上传图片与位置信息</p>
              </div>
            </div>
            <div class="heading-actions" style="padding: 0 20px 18px; justify-content: flex-end;">
              <PermissionButton permission="ai:task:edit" :icon="Save" @click="async ({ allowed }) => {
                if (!allowed) return
                await saveDetail()
              }">保存</PermissionButton>
            </div>
          </template>
          <div v-else class="loading-row">暂无任务数据</div>
        </section>

        <!-- 2) 运行时指标 -->
        <section class="panel system-panel">
          <div class="panel-header compact">
            <div>
              <h2>运行时指标</h2>
              <p>CPU / GPU / 内存占用与执行耗时，5 秒自动刷新</p>
            </div>
            <div class="heading-actions">
              <button class="text-btn" :disabled="runtimeLoading" @click="loadRuntime">
                <Loader2 v-if="runtimeLoading" :size="14" class="spin" />
                <RefreshCcw v-else :size="14" />
                立即刷新
              </button>
            </div>
          </div>
          <div v-if="!task" class="loading-row">暂无任务数据</div>
          <template v-else>
            <div class="runtime-grid">
              <div class="runtime-card">
                <small><Cpu :size="14" />CPU 占用</small>
                <strong>{{ cpu.toFixed(1) }}<i>%</i></strong>
                <div class="bar-track"><i :style="{ width: cpu + '%', background: cpuBarColor }"></i></div>
                <span class="runtime-foot">阈值 85%</span>
              </div>
              <div class="runtime-card">
                <small><Cpu :size="14" />GPU 占用</small>
                <strong>{{ gpu.toFixed(1) }}<i>%</i></strong>
                <div class="bar-track"><i :style="{ width: gpu + '%', background: gpuBarColor }"></i></div>
                <span class="runtime-foot">推理节点 GPU:0</span>
              </div>
              <div class="runtime-card">
                <small><MemoryStick :size="14" />内存占用</small>
                <strong>{{ memMB }}<i>MB</i></strong>
                <div class="bar-track"><i :style="{ width: memPercent + '%', background: '#4f8f99' }"></i></div>
                <span class="runtime-foot">软上限 {{ memMaxMB }} MB</span>
              </div>
              <div class="runtime-card">
                <small><History :size="14" />已运行时长</small>
                <strong>{{ elapsedLabel }}</strong>
                <div class="bar-track">
                  <i :style="{ width: elapsedPercent + '%', background: runtimeStatusClass === 'status-failed' ? '#e06060' : '#6dbfa5' }"></i>
                </div>
                <span class="runtime-foot">吞吐 {{ runtime?.throughput ?? '—' }} 张/分</span>
              </div>
            </div>
            <div class="runtime-summary">
              <div class="runtime-status">
                <span class="status-dot" :class="runtimeStatusClass"></span>
                <strong>任务状态：{{ runtimeStatusLabel }}</strong>
                <small v-if="runtime?.status === 'RUNNING'">实时监控中</small>
                <small v-else-if="runtime?.status === 'PENDING'">等待调度</small>
                <small v-else-if="runtime?.status === 'FAILED'">请检查输入或重试</small>
              </div>
              <div class="runtime-retries">
                <small><RefreshCcw :size="13" />重试次数</small>
                <strong>{{ runtime?.retries ?? 0 }}</strong>
              </div>
              <div class="runtime-times">
                <div><span>开始时间</span><strong>{{ startedAtLabel }}</strong></div>
                <div><span>结束时间</span><strong>{{ finishedAtLabel }}</strong></div>
              </div>
              <div v-if="runtimeError" class="runtime-error">{{ runtimeError }}</div>
            </div>
          </template>
        </section>

        <!-- 3) 实时日志 -->
        <section class="panel system-panel">
          <div class="panel-header compact">
            <div>
              <h2>实时执行日志</h2>
              <p>滚动查看最新 200 条，5 秒自动拉取增量</p>
            </div>
            <div class="heading-actions">
              <span class="log-meta">
                <span class="status-dot" :class="logPaused ? 'status-paused' : 'status-running'"></span>
                {{ logMetaLabel }}
              </span>
              <select v-model="logLevelFilter" class="log-filter">
                <option value="ALL">全部级别</option>
                <option value="INFO">INFO</option>
                <option value="WARN">WARN</option>
                <option value="ERR">ERR</option>
                <option value="DEBUG">DEBUG</option>
              </select>
            </div>
          </div>
          <div v-if="!task" class="loading-row">暂无任务数据</div>
          <template v-else>
            <div ref="logScrollerRef" class="log-scroller">
              <div v-if="!visibleLogs.length" class="log-empty">
                暂无日志记录，请稍候或点击「立即刷新」
              </div>
              <div
                v-for="(line, idx) in visibleLogs"
                :key="`${line.ts}-${idx}`"
                class="log-line"
                :class="`log-${(line.level || 'INFO').toLowerCase()}`"
              >
                <span class="log-time">[{{ line.ts || '—' }}]</span>
                <span class="log-level">{{ line.level || 'INFO' }}</span>
                <span class="log-msg">{{ line.message }}</span>
              </div>
            </div>
            <div class="log-toolbar">
              <button class="text-btn" @click="logPaused = !logPaused">
                <component :is="logPaused ? PlayCircle : PauseCircle" :size="15" />
                {{ logPaused ? '继续' : '暂停' }}
              </button>
              <button class="text-btn" :disabled="logLoading" @click="refreshLogsNow">
                <Loader2 v-if="logLoading" :size="14" class="spin" />
                <RefreshCcw v-else :size="14" />
                立即刷新
              </button>
              <button class="text-btn" @click="exportLogs">
                <Download :size="14" />导出 .log 文件
              </button>
              <span v-if="logError" class="log-error">{{ logError }}</span>
            </div>
          </template>
        </section>
      </div>

      <!-- 右列：执行控制 + 关联/附件 -->
      <div class="detail-side-col">
        <!-- 4) 执行控制 -->
        <section class="panel warning-panel">
          <div class="panel-header compact">
            <div>
              <h2>执行控制</h2>
              <p>失败重试、手动终止与关联跳转</p>
            </div>
          </div>
          <div v-if="!task" class="loading-row">暂无任务数据</div>
          <template v-else>
            <div class="control-list">
              <div class="control-card control-card-yellow">
                <div class="control-info">
                  <strong><RefreshCcw :size="14" />立即重试</strong>
                  <span>适用于失败 / 超时 / 中断后再次执行</span>
                </div>
                <PermissionButton
                  permission="ai:task:retry"
                  :icon="RefreshCcw"
                  :disabled="retrying"
                  @click="async ({ allowed, reason }) => {
                    if (!allowed) return emit('notify', reason)
                    await performRetry()
                  }"
                >{{ retrying ? '重试中...' : '立即重试' }}</PermissionButton>
              </div>
              <div class="control-card control-card-red">
                <div class="control-info">
                  <strong><Square :size="14" />手动终止</strong>
                  <span>仅在任务运行中可用，终止后需要重新触发</span>
                </div>
                <PermissionButton
                  permission="ai:task:terminate"
                  :icon="Square"
                  :disabled="!canTerminate || terminating"
                  @click="({ allowed, reason }) => {
                    if (!allowed) return emit('notify', reason)
                    if (!canTerminate) return emit('notify', '当前任务不在运行中，无法终止')
                    showTerminateConfirm = true
                  }"
                >{{ terminating ? '终止中...' : '手动终止' }}</PermissionButton>
              </div>
              <div class="control-card control-card-blue">
                <div class="control-info">
                  <strong><GitBranch :size="14" />查看关联</strong>
                  <span>跳转至任务绑定的灾害点 / 预警 / 设备</span>
                </div>
                <button class="text-btn" @click="scrollToLinked">
                  <GitBranch :size="14" />查看关联
                </button>
              </div>
            </div>

            <div v-if="showTerminateConfirm" class="confirm-mask" @click.self="showTerminateConfirm = false">
              <div class="confirm-card">
                <div class="confirm-head">
                  <AlertOctagon :size="18" />
                  <strong>确认手动终止任务</strong>
                </div>
                <p>终止后任务将立刻停止运行，已完成的部分会保留。是否继续？</p>
                <div class="confirm-foot">
                  <button class="secondary-btn" @click="showTerminateConfirm = false">取消</button>
                  <button class="danger-btn" :disabled="terminating" @click="performTerminate">
                    {{ terminating ? '终止中...' : '确认终止' }}
                  </button>
                </div>
              </div>
            </div>
          </template>
        </section>

        <!-- 5) 任务过程记录 / 附件 / 关联联动（保留原有结构） -->
        <section class="panel warning-panel">
          <div class="panel-header compact">
            <div>
              <h2>任务过程记录</h2>
              <p>执行轨迹、附件与关联联动</p>
            </div>
          </div>
          <div v-if="task" class="warning-list">
            <article v-for="record in task.history" :key="`${record.time}-${record.title}`" class="warning-item">
              <div class="warning-symbol blue">R</div>
              <div class="warning-body">
                <div class="warning-title"><strong>{{ record.title }}</strong><span class="blue">{{ record.time }}</span></div>
                <p>{{ record.desc }}</p>
              </div>
            </article>
          </div>
          <div v-if="task" class="chart-card">
            <div class="mini-chart-header">
              <strong><Paperclip :size="16" />附件</strong>
              <span>{{ (task.attachments?.length ?? 0) }} 个文件</span>
            </div>
            <div class="pie-list">
              <div v-for="file in task.attachments" :key="file.name" class="pie-item">
                <span>{{ file.name }}</span>
                <div class="pie-track"><i style="width: 100%; background: #4f8f99"></i></div>
                <strong>{{ file.size }}</strong>
              </div>
            </div>
          </div>
          <div id="task-linked-anchor" v-if="task" class="chart-card">
            <div class="mini-chart-header">
              <strong><GitBranch :size="16" />关联联动</strong>
              <span>点击跳转 · 灾害点 / 预警 / 设备</span>
            </div>
            <div v-if="task.linked?.length" class="pie-list">
              <RouterLink v-for="item in task.linked" :key="`${item.type}-${item.label}`" :to="item.path" class="pie-item link-item">
                <span class="link-type">{{ item.type }}</span>
                <span class="link-label">{{ item.label }}</span>
                <div class="pie-track"><i style="width: 100%; background: #6dbfa5"></i></div>
                <strong class="link-go">查看 ›</strong>
              </RouterLink>
            </div>
            <p v-else class="people-empty" style="padding: 12px;">暂无关联记录</p>
          </div>
        </section>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 顶层布局：把 content-grid 重新定义为左主 + 右副 */
.task-detail-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.65fr) minmax(310px, 0.85fr);
  gap: 15px;
  margin-top: 15px;
}
.detail-main-col,
.detail-side-col { display: grid; gap: 15px; min-width: 0; align-content: start; }
.loading-row { padding: 0 20px 18px; color: #7a8d89; font-size: 11px; }

/* 运行时指标 */
.runtime-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  padding: 4px 20px 14px;
}
.runtime-card {
  display: grid;
  gap: 6px;
  padding: 14px;
  border: 1px solid #e3ebe7;
  border-radius: 12px;
  background: linear-gradient(180deg, #fff, #f8fbfa);
}
.runtime-card small {
  display: inline-flex; align-items: center; gap: 5px;
  color: #7a8d89; font-size: 10px;
}
.runtime-card strong {
  display: block; color: #275055; font-size: 22px; font-weight: 700;
}
.runtime-card strong i {
  margin-left: 3px; color: #a4b3b0; font-style: normal; font-size: 11px; font-weight: 500;
}
.bar-track { height: 5px; overflow: hidden; border-radius: 3px; background: #e7f0ed; }
.bar-track i { display: block; height: 100%; border-radius: inherit; transition: width .4s ease; }
.runtime-foot { color: #97aead !important; font-size: 9px !important; }
.runtime-summary {
  display: grid; grid-template-columns: 1fr 1fr; gap: 12px; padding: 0 20px 18px;
}
.runtime-status, .runtime-retries {
  display: flex; align-items: center; gap: 10px;
  padding: 12px 14px; border: 1px solid #e3ebe7; border-radius: 11px; background: #fbfdfc;
}
.runtime-status strong { color: #244b50; font-size: 13px; }
.runtime-status small { color: #7a8d89; font-size: 10px; }
.runtime-retries small {
  display: inline-flex; align-items: center; gap: 5px; color: #7a8d89; font-size: 10px;
}
.runtime-retries strong { margin-left: auto; color: #e0a050; font-size: 20px; font-weight: 700; }
.runtime-times {
  grid-column: span 2; display: grid; grid-template-columns: 1fr 1fr; gap: 10px;
}
.runtime-times div {
  display: flex; align-items: center; justify-content: space-between;
  padding: 8px 12px; border: 1px dashed #e3ebe7; border-radius: 10px; background: #fbfdfc;
}
.runtime-times span { color: #7a8d89; font-size: 10px; }
.runtime-times strong { color: #36585b; font-size: 11px; font-weight: 500; }
.runtime-error {
  grid-column: span 2; padding: 8px 12px;
  color: #e06060; border: 1px solid #f6d6d3; border-radius: 9px; background: #fdeeed; font-size: 10px;
}

.status-dot {
  display: inline-block; width: 8px; height: 8px; border-radius: 50%; background: #97aead; flex: 0 0 auto;
}
.status-dot.status-running { background: #6dbfa5; animation: pulse 1.4s ease-in-out infinite; }
.status-dot.status-success { background: #6dbfa5; }
.status-dot.status-failed { background: #e06060; }
.status-dot.status-pending { background: #d9c040; }
.status-dot.status-paused { background: #7a8d89; }
.status-dot.status-terminated { background: #e0a050; }
.status-dot.status-timeout { background: #e06060; }
@keyframes pulse {
  0%, 100% { opacity: 1; box-shadow: 0 0 0 0 rgba(109, 191, 165, .55); }
  50% { opacity: .55; box-shadow: 0 0 0 6px rgba(109, 191, 165, 0); }
}

/* 实时日志 */
.log-meta {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 4px 9px; color: #4f8f99; border: 1px solid #dbe6e2; border-radius: 999px; background: #f4f8f7; font-size: 10px;
}
.log-filter {
  padding: 5px 8px; font-size: 10px;
  border: 1px solid #dbe6e2; border-radius: 8px; background: #fbfdfc; color: #36585b; outline: none;
}
.log-scroller {
  height: 260px; margin: 0 19px; padding: 12px 14px;
  overflow-y: auto; border: 1px solid #e3ebe7; border-radius: 11px; background: #0e1e22;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 11px; line-height: 1.6;
}
.log-scroller::-webkit-scrollbar { width: 6px; }
.log-scroller::-webkit-scrollbar-track { background: transparent; }
.log-scroller::-webkit-scrollbar-thumb { background: rgba(255,255,255,.18); border-radius: 3px; }
.log-empty { color: #6c8084; font-size: 11px; text-align: center; padding: 24px 0; }
.log-line {
  display: grid; grid-template-columns: 100px 60px 1fr; gap: 10px;
  padding: 1px 0; white-space: pre-wrap; word-break: break-all;
}
.log-time { color: #7a9094; }
.log-level { text-align: center; font-weight: 700; letter-spacing: .04em; }
.log-msg { color: #cfd8d8; }
.log-line.log-info .log-level { color: #4f8f99; }
.log-line.log-info .log-msg { color: #cfd8d8; }
.log-line.log-warn .log-level { color: #e0a050; }
.log-line.log-warn .log-msg { color: #f0d8b3; }
.log-line.log-err .log-level { color: #e06060; }
.log-line.log-err .log-msg { color: #f1b4ad; }
.log-line.log-debug .log-level { color: #7a8d89; }
.log-line.log-debug .log-msg { color: #97aead; }
.log-toolbar {
  display: flex; align-items: center; gap: 12px;
  padding: 10px 19px 14px; border-top: 1px solid #edf2f0;
}
.log-error { margin-left: auto; color: #e06060; font-size: 10px; }

/* 执行控制 */
.control-list { display: grid; gap: 10px; padding: 6px 19px 16px; }
.control-card {
  display: flex; align-items: center; gap: 12px;
  padding: 12px 14px; border: 1px solid #e3ebe7; border-radius: 11px; background: #fbfdfc;
}
.control-card-yellow { border-left: 3px solid #d9c040; }
.control-card-red { border-left: 3px solid #e06060; }
.control-card-blue { border-left: 3px solid #4f8f99; }
.control-info { flex: 1; min-width: 0; }
.control-info strong {
  display: flex; align-items: center; gap: 5px; color: #244b50; font-size: 12px;
}
.control-info span {
  display: block; margin-top: 3px; color: #8aa3a0; font-size: 10px;
}
.danger-btn {
  display: inline-flex; align-items: center; justify-content: center; gap: 6px;
  padding: 9px 14px; color: #fff; border: 1px solid #c14f4f; border-radius: 8px;
  background: #e06060; font-size: 12px; font-weight: 600; cursor: pointer; transition: background .2s ease;
}
.danger-btn:disabled { opacity: .55; cursor: not-allowed; }
.danger-btn:hover:not(:disabled) { background: #c14f4f; }
.confirm-mask {
  position: fixed; inset: 0; z-index: 50;
  display: grid; place-items: center; background: rgba(8, 38, 42, .35); backdrop-filter: blur(2px);
}
.confirm-card {
  width: min(420px, 92vw); padding: 18px;
  border: 1px solid #e3ebe7; border-radius: 12px; background: #fff;
  box-shadow: 0 18px 50px rgba(20, 60, 56, .22);
}
.confirm-head {
  display: flex; align-items: center; gap: 8px; color: #e06060; font-size: 14px; font-weight: 700;
}
.confirm-card p { margin: 10px 0 16px; color: #4d6c6e; font-size: 12px; line-height: 1.7; }
.confirm-foot { display: flex; justify-content: flex-end; gap: 10px; }
.spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

/* 响应式：移动端折成单列 */
@media (max-width: 980px) {
  .task-detail-grid { grid-template-columns: 1fr; }
  .runtime-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .runtime-summary { grid-template-columns: 1fr; }
  .runtime-times { grid-column: span 1; grid-template-columns: 1fr; }
  .log-scroller { height: 220px; }
}
@media (max-width: 640px) {
  .runtime-grid { grid-template-columns: 1fr; }
  .log-scroller { height: 200px; }
  .log-line { grid-template-columns: 82px 50px 1fr; gap: 6px; }
  .control-card { flex-wrap: wrap; }
}
</style>