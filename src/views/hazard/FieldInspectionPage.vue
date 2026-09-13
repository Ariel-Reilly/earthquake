<script setup>
/**
 * 现场巡检页（/dashboard/field-inspection）。
 *
 * v1.9.0 P1：现场巡检与移动端联调。
 *
 * 数据流：
 *   - 待巡检任务列表   GET /api/tasks?status=PENDING
 *   - 单任务巡检记录   GET /api/inspection/task/{taskCode}
 *   - GPS 围栏校验     POST /api/inspection/gps-check
 *   - 提交巡检记录     POST /api/inspection/submit
 *   - 轨迹回放         GET /api/device-map/trajectory/{deviceCode}
 *   - 示例 payload     GET /api/inspection/example
 */
import { computed, onMounted, ref, watch } from 'vue'
import {
  AlertTriangle,
  Camera,
  CheckCircle2,
  ChevronRight,
  ClipboardCheck,
  Clock,
  Download,
  FileText,
  Gauge,
  HardHat,
  Info,
  Loader2,
  MapPin,
  Navigation,
  PencilLine,
  Play,
  Plus,
  RefreshCw,
  Ruler,
  Send,
  Smartphone,
  Trash2,
  X,
  XCircle,
  Zap,
} from 'lucide-vue-next'
import { dashboardApi } from '../../services/dashboardApi'

// ============ Tab ============
const activeTab = ref('tasks') // tasks | fence | record | example

// ============ Tasks ============
const loading = ref(false)
const errorMsg = ref('')
const tasks = ref([])
const selectedTaskCode = ref('')

async function loadTasks() {
  loading.value = true
  try {
    const resp = await dashboardApi.getTasks({ pageSize: 100 })
    tasks.value = resp.list ?? []
    if (tasks.value.length > 0 && !selectedTaskCode.value) {
      selectedTaskCode.value = tasks.value[0].taskCode ?? tasks.value[0].code
    }
  } catch (e) {
    errorMsg.value = '任务加载失败：' + (e?.message || '后端不可达')
    tasks.value = []
  } finally {
    loading.value = false
  }
}

// ============ GPS Fence Demo ============
const fenceResult = ref(null)
const fenceLoading = ref(false)
const fenceForm = ref({
  taskCode: '',
  hazardCode: '',
  lon: '',
  lat: '',
  allowedRadius: 100,
  inspector: '',
})
const fenceDemoHazardCenter = ref({ lon: 118.5421, lat: 30.1284 })

function fillFenceFromTask() {
  if (!selectedTaskCode.value) return
  const t = tasks.value.find((tk) => (tk.taskCode ?? tk.code) === selectedTaskCode.value)
  if (!t) return
  fenceForm.value.taskCode = t.taskCode ?? t.code ?? ''
  fenceForm.value.hazardCode = t.hazardCode ?? t.relatedHazardCode ?? 'HZ-LA-001'
  fenceForm.value.allowedRadius = t.radiusMeters ?? 100
  // 默认在灾害点中心做一次偏移模拟"签到位置"
  const offset = (Math.random() - 0.5) * 0.005
  fenceForm.value.lon = (fenceDemoHazardCenter.value.lon + offset).toFixed(6)
  fenceForm.value.lat = (fenceDemoHazardCenter.value.lat + offset * 0.8).toFixed(6)
}

async function runGpsCheck() {
  if (!fenceForm.value.taskCode || !fenceForm.value.hazardCode) {
    errorMsg.value = '请先填写任务号和灾害点编号'
    return
  }
  fenceLoading.value = true
  errorMsg.value = ''
  try {
    fenceResult.value = await dashboardApi.gpsCheck({
      taskCode: fenceForm.value.taskCode,
      hazardCode: fenceForm.value.hazardCode,
      lon: parseFloat(fenceForm.value.lon),
      lat: parseFloat(fenceForm.value.lat),
      allowedRadius: parseFloat(fenceForm.value.allowedRadius),
      inspector: fenceForm.value.inspector || currentUser.value,
    })
  } catch (e) {
    errorMsg.value = 'GPS 围栏校验失败：' + (e?.message ?? '')
    fenceResult.value = null
  } finally {
    fenceLoading.value = false
  }
}

// ============ Submit Inspection ============
const records = ref([])
const recordsLoading = ref(false)
const submitResult = ref(null)
const submitForm = ref({
  taskCode: '',
  hazardCode: '',
  lon: '',
  lat: '',
  allowedRadius: 200,
  inspector: '',
  abnormalFlag: false,
  note: '',
  photoIds: '',
  displacement_mm: '',
  crack_width_mm: '',
  rainfall_mm: '',
})

async function loadRecords(taskCode) {
  if (!taskCode) {
    records.value = []
    return
  }
  recordsLoading.value = true
  try {
    const data = await dashboardApi.getInspectionByTask(taskCode)
    records.value = Array.isArray(data) ? data : []
  } catch (e) {
    records.value = []
  } finally {
    recordsLoading.value = false
  }
}

function fillSubmitFromTask() {
  if (!selectedTaskCode.value) return
  const t = tasks.value.find((tk) => (tk.taskCode ?? tk.code) === selectedTaskCode.value)
  if (!t) return
  submitForm.value.taskCode = t.taskCode ?? t.code ?? ''
  submitForm.value.hazardCode = t.hazardCode ?? t.relatedHazardCode ?? 'HZ-LA-001'
  submitForm.value.allowedRadius = t.radiusMeters ?? 200
  const offset = (Math.random() - 0.5) * 0.005
  submitForm.value.lon = (fenceDemoHazardCenter.value.lon + offset).toFixed(6)
  submitForm.value.lat = (fenceDemoHazardCenter.value.lat + offset * 0.8).toFixed(6)
  submitForm.value.inspector = currentUser.value
}

async function submitInspection() {
  if (!submitForm.value.taskCode || !submitForm.value.hazardCode) {
    errorMsg.value = '请先选择关联的任务和灾害点'
    return
  }
  const formData = {}
  if (submitForm.value.displacement_mm !== '')
    formData.displacement_mm = parseFloat(submitForm.value.displacement_mm)
  if (submitForm.value.crack_width_mm !== '')
    formData.crack_width_mm = parseFloat(submitForm.value.crack_width_mm)
  if (submitForm.value.rainfall_mm !== '')
    formData.rainfall_mm = parseFloat(submitForm.value.rainfall_mm)

  submitResult.value = null
  try {
    submitResult.value = await dashboardApi.submitInspection({
      taskCode: submitForm.value.taskCode,
      hazardCode: submitForm.value.hazardCode,
      lon: parseFloat(submitForm.value.lon),
      lat: parseFloat(submitForm.value.lat),
      allowedRadius: parseFloat(submitForm.value.allowedRadius),
      inspector: submitForm.value.inspector || currentUser.value,
      abnormalFlag: submitForm.value.abnormalFlag,
      note: submitForm.value.note,
      photoIds: submitForm.value.photoIds
        ? submitForm.value.photoIds.split(',').map((s) => s.trim()).filter(Boolean)
        : [],
      formData,
    })
    // 提交后刷新记录列表
    await loadRecords(submitForm.value.taskCode)
  } catch (e) {
    errorMsg.value = '巡检记录提交失败：' + (e?.message ?? '')
  }
}

// ============ Example Payload ============
const examplePayload = ref(null)
async function loadExample() {
  try {
    examplePayload.value = await dashboardApi.getInspectionExample()
  } catch (e) {
    examplePayload.value = null
  }
}

// ============ Trajectory Replay ============
const trajectoryLoading = ref(false)
const trajectoryData = ref(null)
async function loadTrajectory(deviceCode) {
  if (!deviceCode) return
  trajectoryLoading.value = true
  try {
    const data = await dashboardApi.getDeviceTrack(deviceCode, {
      from: new Date(Date.now() - 24 * 3600 * 1000).toISOString(),
      to: new Date().toISOString(),
    })
    trajectoryData.value = data
  } catch (e) {
    trajectoryData.value = []
  } finally {
    trajectoryLoading.value = false
  }
}

const currentUser = ref('admin')

// ============ Stats ============
const stats = computed(() => {
  const total = tasks.value.length
  const pending = tasks.value.filter((t) => (t.status ?? 'PENDING') === 'PENDING' || (t.status ?? '').includes('待')).length
  const inProgress = tasks.value.filter((t) => (t.status ?? '').includes('进行') || (t.status ?? '') === 'IN_PROGRESS').length
  const done = tasks.value.filter((t) => (t.status ?? '').includes('完成') || (t.status ?? '') === 'DONE').length
  const abnormal = records.value.filter((r) => r.abnormalFlag).length
  return { total, pending, inProgress, done, abnormal }
})

const TRAJECTORY_SVG = computed(() => {
  if (!trajectoryData.value?.length) return null
  const pts = trajectoryData.value
  const lons = pts.map((p) => p.lon)
  const lats = pts.map((p) => p.lat)
  const minLon = Math.min(...lons)
  const maxLon = Math.max(...lons)
  const minLat = Math.min(...lats)
  const maxLat = Math.max(...lats)
  const rangeLon = maxLon - minLon || 0.001
  const rangeLat = maxLat - minLat || 0.001
  const pad = 0.15
  const paddedLon = rangeLon * pad
  const paddedLat = rangeLat * pad
  const W = 600
  const H = 240
  const PAD = { top: 14, right: 14, bottom: 22, left: 36 }
  const cw = W - PAD.left - PAD.right
  const ch = H - PAD.top - PAD.bottom
  const toX = (lon) => PAD.left + ((lon - minLon + paddedLon) / (rangeLon + 2 * paddedLon)) * cw
  const toY = (lat) => PAD.top + ch - ((lat - minLat + paddedLat) / (rangeLat + 2 * paddedLat)) * ch

  const path = `M ${pts.map((p) => `${toX(p.lon)},${toY(p.lat)}`).join(' L ')}`
  const start = { x: toX(pts[0].lon), y: toY(pts[0].lat) }
  const end = { x: toX(pts[pts.length - 1].lon), y: toY(pts[pts.length - 1].lat) }
  return { W, H, PAD, path, start, end, points: pts.map((p) => ({ ...p, x: toX(p.lon), y: toY(p.lat) })) }
})

watch(selectedTaskCode, (code) => {
  if (code && activeTab.value === 'record') loadRecords(code)
})

onMounted(async () => {
  await loadTasks()
  await loadExample()
})
</script>

<template>
  <div class="fip-root">
    <!-- Header -->
    <header class="fip-header">
      <div>
        <h1 class="fip-title">
          <HardHat :size="20" style="color:#4f8f99" />
          现场巡检
        </h1>
        <p class="fip-subtitle">移动端 GPS 围栏、巡检上报、轨迹回放 — 应急时外业人员"边走边拍"在线闭环</p>
      </div>
      <button class="fip-refresh-btn" @click="loadTasks">
        <RefreshCw :size="14" :class="{ spin: loading }" />
        刷新任务
      </button>
    </header>

    <!-- Error -->
    <div v-if="errorMsg" class="fip-error">
      <AlertTriangle :size="14" />
      {{ errorMsg }}
    </div>

    <!-- KPI -->
    <div class="fip-kpis">
      <div class="fip-kpi">
        <div class="fip-kpi-label">总任务数</div>
        <div class="fip-kpi-value">{{ stats.total }}</div>
      </div>
      <div class="fip-kpi kpi-pending">
        <div class="fip-kpi-label"><Clock :size="11" /> 待执行</div>
        <div class="fip-kpi-value">{{ stats.pending }}</div>
      </div>
      <div class="fip-kpi kpi-active">
        <div class="fip-kpi-label"><Play :size="11" /> 进行中</div>
        <div class="fip-kpi-value">{{ stats.inProgress }}</div>
      </div>
      <div class="fip-kpi kpi-done">
        <div class="fip-kpi-label"><CheckCircle2 :size="11" /> 已完成</div>
        <div class="fip-kpi-value">{{ stats.done }}</div>
      </div>
      <div class="fip-kpi kpi-abnormal">
        <div class="fip-kpi-label"><AlertTriangle :size="11" /> 异常签到</div>
        <div class="fip-kpi-value">{{ stats.abnormal }}</div>
      </div>
    </div>

    <!-- Tabs -->
    <div class="fip-tabs">
      <button class="fip-tab" :class="{ active: activeTab === 'tasks' }" @click="activeTab = 'tasks'">
        <ClipboardCheck :size="15" /> 巡检任务
      </button>
      <button class="fip-tab" :class="{ active: activeTab === 'fence' }" @click="activeTab = 'fence'">
        <Gauge :size="15" /> GPS 围栏
      </button>
      <button class="fip-tab" :class="{ active: activeTab === 'record' }" @click="activeTab = 'record'">
        <PencilLine :size="15" /> 上报记录
      </button>
      <button class="fip-tab" :class="{ active: activeTab === 'trajectory' }" @click="activeTab = 'trajectory'">
        <Navigation :size="15" /> 轨迹回放
      </button>
      <button class="fip-tab" :class="{ active: activeTab === 'example' }" @click="activeTab = 'example'">
        <FileText :size="15" /> 接口示例
      </button>
    </div>

    <!-- ═══ TASKS ═══ -->
    <div v-if="activeTab === 'tasks'" class="fip-section">
      <div v-if="loading" class="fip-loading"><Loader2 :size="20" class="spin" /> 任务加载中…</div>
      <div v-else-if="tasks.length === 0" class="fip-empty">
        <ClipboardCheck :size="40" style="color:#c8d5d5" />
        <p>暂无巡检任务</p>
      </div>
      <div v-else class="fip-tasks-grid">
        <article
          v-for="t in tasks"
          :key="t.taskCode ?? t.code"
          class="fip-task-card"
          :class="{ active: selectedTaskCode === (t.taskCode ?? t.code) }"
          @click="selectedTaskCode = t.taskCode ?? t.code"
        >
          <div class="fip-task-head">
            <div class="fip-task-code">{{ t.taskCode ?? t.code }}</div>
            <span
              class="fip-status-tag"
              :class="{
                pending: t.status === 'PENDING' || (t.status ?? '').includes('待'),
                progress: (t.status ?? '').includes('进行') || t.status === 'IN_PROGRESS',
                done: (t.status ?? '').includes('完成') || t.status === 'DONE',
              }"
            >{{ t.status ?? '待执行' }}</span>
          </div>
          <div class="fip-task-name">{{ t.name ?? '巡检任务' }}</div>
          <div class="fip-task-meta">
            <span><MapPin :size="11" /> {{ t.hazardCode ?? t.relatedHazardCode ?? '—' }}</span>
            <span><HardHat :size="11" /> {{ t.executor ?? t.inspector ?? '未分配' }}</span>
          </div>
          <div class="fip-task-time">
            <Clock :size="11" />
            {{ t.scheduledAt ? new Date(t.scheduledAt).toLocaleString('zh-CN', { hour12: false }).slice(0, 16) : t.dueDate ?? '未排期' }}
          </div>
          <button class="fip-task-go" @click.stop="() => { activeTab = 'fence'; fillFenceFromTask() }">
            <Gauge :size="12" /> 去定位
          </button>
        </article>
      </div>
    </div>

    <!-- ═══ GPS FENCE ═══ -->
    <div v-if="activeTab === 'fence'" class="fip-section">
      <div class="fip-fence-layout">
        <form class="fip-card fip-fence-form" @submit.prevent="runGpsCheck">
          <div class="fip-card-header">
            <strong><Smartphone :size="14" /> 移动端 GPS 围栏校验</strong>
            <button class="fip-fill-btn" type="button" @click="fillFenceFromTask" :disabled="!selectedTaskCode">
              <ChevronRight :size="12" /> 从当前任务填充
            </button>
          </div>

          <div class="fip-form-grid">
            <div class="fip-form-row">
              <label class="fip-form-label">任务编号 *</label>
              <input v-model="fenceForm.taskCode" type="text" class="fip-input" placeholder="例：TK-001" />
            </div>
            <div class="fip-form-row">
              <label class="fip-form-label">灾害点编号 *</label>
              <input v-model="fenceForm.hazardCode" type="text" class="fip-input" placeholder="例：HZ-LA-001" />
            </div>
            <div class="fip-form-row">
              <label class="fip-form-label">签到经度 *</label>
              <input v-model="fenceForm.lon" type="number" step="0.000001" class="fip-input" placeholder="118.5421" />
            </div>
            <div class="fip-form-row">
              <label class="fip-form-label">签到纬度 *</label>
              <input v-model="fenceForm.lat" type="number" step="0.000001" class="fip-input" placeholder="30.1284" />
            </div>
            <div class="fip-form-row">
              <label class="fip-form-label">允许半径（米）</label>
              <input v-model="fenceForm.allowedRadius" type="number" step="10" class="fip-input" placeholder="100" />
            </div>
            <div class="fip-form-row">
              <label class="fip-form-label">巡检员</label>
              <input v-model="fenceForm.inspector" type="text" class="fip-input" :placeholder="currentUser" />
            </div>
          </div>

          <button class="fip-run-btn" type="submit" :disabled="fenceLoading">
            <Loader2 v-if="fenceLoading" :size="15" class="spin" />
            <Gauge v-else :size="15" />
            {{ fenceLoading ? '校验中…' : '开始 GPS 围栏校验' }}
          </button>
        </form>

        <div class="fip-card fip-fence-result">
          <div class="fip-card-header">
            <strong><Ruler :size="14" /> 校验结果</strong>
          </div>
          <div v-if="fenceResult" class="fip-fence-content">
            <div
              class="fip-fence-banner"
              :class="{ ok: fenceResult.withinFence, err: !fenceResult.withinFence }"
            >
              <CheckCircle2 v-if="fenceResult.withinFence" :size="32" />
              <XCircle v-else :size="32" />
              <div>
                <div class="fip-fence-status">{{ fenceResult.fenceStatus ?? (fenceResult.withinFence ? '已进入围栏' : '超出围栏') }}</div>
                <div class="fip-fence-distance">实际距离 <b>{{ Number(fenceResult.distanceM).toFixed(1) }}</b> 米 / 允许 {{ fenceResult.allowedRadius }} 米</div>
              </div>
            </div>

            <div class="fip-kv-list">
              <div class="fip-kv"><span>任务编号</span><b>{{ fenceResult.taskCode }}</b></div>
              <div class="fip-kv"><span>灾害点</span><b>{{ fenceResult.hazardCode }}</b></div>
              <div class="fip-kv"><span>签到经度</span><b>{{ fenceResult.lon }}</b></div>
              <div class="fip-kv"><span>签到纬度</span><b>{{ fenceResult.lat }}</b></div>
              <div class="fip-kv"><span>校验时间</span><b>{{ fenceResult.checkedAt ? new Date(fenceResult.checkedAt).toLocaleTimeString('zh-CN') : '—' }}</b></div>
            </div>
          </div>
          <div v-else class="fip-empty">
            <Ruler :size="36" style="color:#c8d5d5" />
            <p>填写参数后点击「开始 GPS 围栏校验」</p>
          </div>
        </div>
      </div>
    </div>

    <!-- ═══ SUBMIT RECORD ═══ -->
    <div v-if="activeTab === 'record'" class="fip-section">
      <div class="fip-record-layout">
        <form class="fip-card fip-submit-form" @submit.prevent="submitInspection">
          <div class="fip-card-header">
            <strong><Send :size="14" /> 提交巡检记录</strong>
            <button class="fip-fill-btn" type="button" @click="fillSubmitFromTask" :disabled="!selectedTaskCode">
              <ChevronRight :size="12" /> 从当前任务填充
            </button>
          </div>

          <div class="fip-form-grid">
            <div class="fip-form-row">
              <label class="fip-form-label">任务编号 *</label>
              <input v-model="submitForm.taskCode" type="text" class="fip-input" placeholder="TK-001" />
            </div>
            <div class="fip-form-row">
              <label class="fip-form-label">灾害点 *</label>
              <input v-model="submitForm.hazardCode" type="text" class="fip-input" placeholder="HZ-LA-001" />
            </div>
            <div class="fip-form-row">
              <label class="fip-form-label">签到经度 *</label>
              <input v-model="submitForm.lon" type="number" step="0.000001" class="fip-input" />
            </div>
            <div class="fip-form-row">
              <label class="fip-form-label">签到纬度 *</label>
              <input v-model="submitForm.lat" type="number" step="0.000001" class="fip-input" />
            </div>
            <div class="fip-form-row">
              <label class="fip-form-label">允许半径</label>
              <input v-model="submitForm.allowedRadius" type="number" step="10" class="fip-input" />
            </div>
            <div class="fip-form-row">
              <label class="fip-form-label">巡检员</label>
              <input v-model="submitForm.inspector" type="text" class="fip-input" />
            </div>
          </div>

          <div class="fip-form-section-title">现场观测数据</div>
          <div class="fip-form-grid">
            <div class="fip-form-row">
              <label class="fip-form-label">地表位移 (mm)</label>
              <input v-model="submitForm.displacement_mm" type="number" step="0.1" class="fip-input" placeholder="0.0" />
            </div>
            <div class="fip-form-row">
              <label class="fip-form-label">裂缝宽度 (mm)</label>
              <input v-model="submitForm.crack_width_mm" type="number" step="0.1" class="fip-input" placeholder="0.0" />
            </div>
            <div class="fip-form-row">
              <label class="fip-form-label">累计降雨 (mm)</label>
              <input v-model="submitForm.rainfall_mm" type="number" step="0.1" class="fip-input" placeholder="0.0" />
            </div>
            <div class="fip-form-row fip-form-full">
              <label class="fip-form-label">照片编号（逗号分隔）</label>
              <input v-model="submitForm.photoIds" type="text" class="fip-input" placeholder="photo-001, photo-002" />
            </div>
            <div class="fip-form-row fip-form-full">
              <label class="fip-form-label">备注</label>
              <textarea v-model="submitForm.note" class="fip-input" rows="2" placeholder="现场情况描述..." />
            </div>
            <label class="fip-checkbox-row">
              <input v-model="submitForm.abnormalFlag" type="checkbox" />
              <span>标记为异常签到（地表裂缝、冒水、塌陷等）</span>
            </label>
          </div>

          <button class="fip-run-btn" type="submit">
            <Send :size="14" />
            提交巡检记录
          </button>

          <div v-if="submitResult" class="fip-submit-result">
            <CheckCircle2 :size="14" />
            记录已上报 — 编号 <code>{{ submitResult.recordCode }}</code>
            <span v-if="submitResult.abnormalFlag" class="fip-flag-tag">异常</span>
          </div>
        </form>

        <div class="fip-card fip-records">
          <div class="fip-card-header">
            <strong>
              <FileText :size="14" /> 该任务的巡检记录
              <span v-if="records.length" class="fip-tag-count">{{ records.length }}</span>
            </strong>
            <button class="fip-fill-btn" type="button" :disabled="!selectedTaskCode" @click="loadRecords(selectedTaskCode)">
              <RefreshCw :size="12" /> 刷新
            </button>
          </div>

          <div v-if="recordsLoading" class="fip-loading">
            <Loader2 :size="18" class="spin" />
          </div>
          <div v-else-if="!selectedTaskCode" class="fip-empty">
            <Info :size="36" style="color:#c8d5d5" />
            <p>先选择一个巡检任务</p>
          </div>
          <div v-else-if="records.length === 0" class="fip-empty">
            <FileText :size="36" style="color:#c8d5d5" />
            <p>该任务暂无巡检记录</p>
          </div>
          <div v-else class="fip-records-list">
            <article
              v-for="r in records"
              :key="r.recordCode ?? r.id"
              class="fip-record"
              :class="{ abnormal: r.abnormalFlag }"
            >
              <div class="fip-record-head">
                <span class="fip-record-code">{{ r.recordCode }}</span>
                <span
                  class="fip-status-tag"
                  :class="r.abnormalFlag ? 'abnormal' : 'done'"
                >
                  {{ r.abnormalFlag ? '异常' : '正常' }}
                </span>
              </div>
              <div class="fip-record-grid">
                <div><span>巡检员</span><b>{{ r.inspector ?? '—' }}</b></div>
                <div><span>距离灾害点</span><b>{{ r.distanceM ? `${Number(r.distanceM).toFixed(1)} m` : '—' }}</b></div>
                <div><span>签到经度</span><b>{{ r.signInLon ?? '—' }}</b></div>
                <div><span>签到纬度</span><b>{{ r.signInLat ?? '—' }}</b></div>
                <div class="fip-record-full"><span>上报时间</span><b>{{ r.inspectAt ? new Date(r.inspectAt).toLocaleString('zh-CN', { hour12: false }) : '—' }}</b></div>
                <div v-if="r.note" class="fip-record-full"><span>备注</span><b>{{ r.note }}</b></div>
                <div v-if="r.formData && Object.keys(r.formData).length" class="fip-record-full">
                  <span>观测数据</span>
                  <b class="fip-formdata">
                    <span v-for="(v, k) in r.formData" :key="k">{{ k }}: {{ v }}</span>
                  </b>
                </div>
              </div>
            </article>
          </div>
        </div>
      </div>
    </div>

    <!-- ═══ TRAJECTORY ═══ -->
    <div v-if="activeTab === 'trajectory'" class="fip-section">
      <div class="fip-card fip-trajectory">
        <div class="fip-card-header">
          <strong><Navigation :size="14" /> 设备轨迹回放</strong>
          <div class="fip-trajectory-form">
            <label>设备编号</label>
            <input
              type="text"
              :value="selectedTaskCode"
              @input="selectedTaskCode = $event.target.value"
              class="fip-input"
              placeholder="DEV-001"
              style="width:200px"
            />
            <button class="fip-fill-btn" @click="loadTrajectory(selectedTaskCode)">
              <Play :size="12" /> 加载 24h 轨迹
            </button>
          </div>
        </div>

        <div v-if="trajectoryLoading" class="fip-loading"><Loader2 :size="20" class="spin" /></div>
        <div v-else-if="!TRAJECTORY_SVG" class="fip-empty">
          <Navigation :size="40" style="color:#c8d5d5" />
          <p>输入设备编号并点击「加载 24h 轨迹」</p>
          <p class="fip-empty-sub">基于 device-map/trajectory 接口返回最近 24 小时位置点</p>
        </div>
        <div v-else class="fip-trajectory-content">
          <div class="fip-trajectory-stats">
            <span><b>{{ TRAJECTORY_SVG.points.length }}</b> 个点</span>
            <span>起点：<b>{{ TRAJECTORY_SVG.points[0].lon.toFixed(4) }}, {{ TRAJECTORY_SVG.points[0].lat.toFixed(4) }}</b></span>
            <span>终点：<b>{{ TRAJECTORY_SVG.points[TRAJECTORY_SVG.points.length - 1].lon.toFixed(4) }}, {{ TRAJECTORY_SVG.points[TRAJECTORY_SVG.points.length - 1].lat.toFixed(4) }}</b></span>
            <span v-if="TRAJECTORY_SVG.points[0].speed">速度：<b>{{ TRAJECTORY_SVG.points[0].speed.toFixed(1) }} m/s</b></span>
          </div>
          <svg :viewBox="`0 0 ${TRAJECTORY_SVG.W} ${TRAJECTORY_SVG.H}`" class="fip-trajectory-svg">
            <!-- 网格 -->
            <line
              v-for="i in 4"
              :key="i"
              :x1="TRAJECTORY_SVG.PAD.left"
              :x2="TRAJECTORY_SVG.W - TRAJECTORY_SVG.PAD.right"
              :y1="TRAJECTORY_SVG.PAD.top + ((TRAJECTORY_SVG.H - TRAJECTORY_SVG.PAD.top - TRAJECTORY_SVG.PAD.bottom) / 4) * (i - 1)"
              :y2="TRAJECTORY_SVG.PAD.top + ((TRAJECTORY_SVG.H - TRAJECTORY_SVG.PAD.top - TRAJECTORY_SVG.PAD.bottom) / 4) * (i - 1)"
              stroke="rgba(47,127,128,.1)" stroke-width="1"
            />
            <!-- 轨迹线 -->
            <path :d="TRAJECTORY_SVG.path" fill="none" stroke="#2b6cb0" stroke-width="2" stroke-linejoin="round" />
            <!-- 起点 -->
            <circle :cx="TRAJECTORY_SVG.start.x" :cy="TRAJECTORY_SVG.start.y" r="6" fill="#4f8f99" />
            <text :x="TRAJECTORY_SVG.start.x" :y="TRAJECTORY_SVG.start.y - 10" text-anchor="middle" class="fip-traj-label">起点</text>
            <!-- 终点 -->
            <circle :cx="TRAJECTORY_SVG.end.x" :cy="TRAJECTORY_SVG.end.y" r="6" fill="#e06060" />
            <text :x="TRAJECTORY_SVG.end.x" :y="TRAJECTORY_SVG.end.y + 18" text-anchor="middle" class="fip-traj-label">终点</text>
            <!-- 数据点 -->
            <circle
              v-for="(p, i) in TRAJECTORY_SVG.points"
              :key="i"
              :cx="p.x"
              :cy="p.y"
              r="3"
              fill="#2b6cb0"
              opacity="0.5"
            />
          </svg>
        </div>
      </div>
    </div>

    <!-- ═══ EXAMPLE ═══ -->
    <div v-if="activeTab === 'example'" class="fip-section">
      <div class="fip-card fip-example">
        <div class="fip-card-header">
          <strong><Info :size="14" /> 移动端上报接口示例</strong>
          <a class="fip-fill-btn" href="/api/inspection/example" target="_blank">
            <Download :size="12" /> 原始 JSON
          </a>
        </div>
        <p class="fip-example-desc">
          <Info :size="13" />
          此为移动端联调参考的请求/响应样例。提交 URL：<code>{{ examplePayload?.submitUrl }}</code>，方法：<b>{{ examplePayload?.method }}</b>
        </p>
        <pre v-if="examplePayload" class="fip-code">{{ JSON.stringify(examplePayload, null, 2) }}</pre>
        <div v-else class="fip-empty"><Loader2 :size="24" class="spin" /></div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.fip-root { padding: 20px 24px; max-width: 1300px; }

/* Header */
.fip-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 20px; gap: 12px; }
.fip-title { display: flex; align-items: center; gap: 8px; font-size: 18px; font-weight: 700; color: #24494d; margin: 0 0 4px; }
.fip-subtitle { font-size: 13px; color: #7a8d89; margin: 0; }
.fip-refresh-btn { display: flex; align-items: center; gap: 6px; padding: 7px 14px; background: #f0f8f6; border: 1px solid #d0dcd8; border-radius: 8px; font-size: 13px; color: #56716e; cursor: pointer; }
.fip-refresh-btn:hover { border-color: #4f8f99; color: #4f8f99; }
.fip-error { display: flex; align-items: center; gap: 8px; padding: 10px 14px; background: #fff5f5; border: 1px solid #feb2b2; border-radius: 8px; color: #c53030; font-size: 13px; margin-bottom: 16px; }

/* KPIs */
.fip-kpis { display: grid; grid-template-columns: repeat(5, 1fr); gap: 10px; margin-bottom: 16px; }
.fip-kpi { background: #fff; border: 1px solid #e9eee9; border-left: 3px solid #4f8f99; border-radius: 10px; padding: 12px 16px; }
.fip-kpi-label { font-size: 11px; color: #7a8d89; text-transform: uppercase; font-weight: 600; margin-bottom: 4px; display: flex; align-items: center; gap: 4px; }
.fip-kpi-value { font-size: 24px; font-weight: 800; color: #24494d; line-height: 1.1; }
.kpi-pending { border-left-color: #e0a050; }
.kpi-active { border-left-color: #2b6cb0; }
.kpi-done { border-left-color: #38a169; }
.kpi-abnormal { border-left-color: #e06060; }

/* Tabs */
.fip-tabs { display: flex; gap: 8px; margin-bottom: 16px; border-bottom: 2px solid #e9eee9; }
.fip-tab { display: flex; align-items: center; gap: 6px; padding: 8px 16px; border: none; background: none; cursor: pointer; font-size: 13px; color: #7a8d89; border-bottom: 2px solid transparent; margin-bottom: -2px; }
.fip-tab:hover { color: #2c3e3e; background: #f0f8f6; }
.fip-tab.active { color: #2b6cb0; border-bottom-color: #2b6cb0; font-weight: 600; }

/* Loading/Empty */
.fip-loading { display: flex; align-items: center; justify-content: center; gap: 8px; padding: 60px; color: #7a8d89; font-size: 13px; }
.fip-empty { display: flex; flex-direction: column; align-items: center; gap: 12px; padding: 60px 20px; color: #7a8d89; text-align: center; }
.fip-empty p { margin: 0; font-size: 14px; color: #56716e; }
.fip-empty-sub { font-size: 12px; color: #9baead; }
.spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

/* Tasks grid */
.fip-tasks-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px; }
.fip-task-card { background: #fff; border: 1px solid #e9eee9; border-radius: 12px; padding: 14px; cursor: pointer; transition: all .15s; }
.fip-task-card:hover { border-color: #4f8f99; box-shadow: 0 4px 12px rgba(0,0,0,.05); }
.fip-task-card.active { border-color: #2b6cb0; background: #f7faff; }
.fip-task-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; }
.fip-task-code { font-family: monospace; font-size: 11px; color: #7a8d89; }
.fip-status-tag { display: inline-block; padding: 2px 8px; border-radius: 12px; font-size: 11px; font-weight: 600; background: #e9eee9; color: #56716e; }
.fip-status-tag.pending { background: #fffaf0; color: #c05621; }
.fip-status-tag.progress { background: #e8f4f2; color: #2b6cb0; }
.fip-status-tag.done { background: #e6fffa; color: #276749; }
.fip-status-tag.abnormal { background: #fed7d7; color: #c53030; }
.fip-task-name { font-size: 14px; font-weight: 600; color: #24494d; margin-bottom: 8px; }
.fip-task-meta { display: flex; gap: 12px; font-size: 11px; color: #7a8d89; margin-bottom: 4px; }
.fip-task-meta span { display: flex; align-items: center; gap: 3px; }
.fip-task-time { font-size: 11px; color: #56716e; display: flex; align-items: center; gap: 4px; margin-bottom: 10px; }
.fip-task-go { display: flex; align-items: center; gap: 4px; padding: 4px 10px; background: #f0f8f6; border: 1px solid #d0dcd8; border-radius: 6px; font-size: 11px; cursor: pointer; color: #4f8f99; }
.fip-task-go:hover { background: #e8f4f2; }

/* Cards */
.fip-card { background: #fff; border: 1px solid #e9eee9; border-radius: 12px; padding: 16px; }
.fip-card-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; gap: 8px; flex-wrap: wrap; }
.fip-card-header strong { font-size: 13px; color: #24494d; display: flex; align-items: center; gap: 6px; }
.fip-tag-count { background: #2b6cb0; color: #fff; padding: 0 8px; border-radius: 12px; font-size: 11px; font-weight: 700; }

/* Forms */
.fip-form-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px 12px; }
.fip-form-row { display: flex; flex-direction: column; gap: 3px; }
.fip-form-full { grid-column: 1 / -1; }
.fip-form-label { font-size: 11px; color: #7a8d89; font-weight: 600; }
.fip-input { padding: 7px 10px; border: 1px solid #d0dcd8; border-radius: 7px; font-size: 13px; background: #f8fbfb; color: #2c3e3e; font-family: inherit; resize: vertical; }
.fip-input:focus { outline: none; border-color: #4f8f99; background: #fff; }
.fip-form-section-title { font-size: 12px; color: #7a8d89; font-weight: 600; margin: 12px 0 4px; padding-top: 8px; border-top: 1px solid #e9eee9; }
.fip-checkbox-row { display: flex; align-items: flex-start; gap: 8px; font-size: 12px; color: #56716e; cursor: pointer; grid-column: 1 / -1; margin-top: 4px; }
.fip-checkbox-row input { margin-top: 2px; }
.fip-fill-btn { display: inline-flex; align-items: center; gap: 4px; padding: 4px 10px; background: #f0f8f6; border: 1px solid #4f8f99; border-radius: 6px; font-size: 11px; cursor: pointer; color: #4f8f99; }
.fip-fill-btn:hover:not(:disabled) { background: #e8f4f2; }
.fip-fill-btn:disabled { opacity: .5; cursor: not-allowed; }
.fip-run-btn { display: flex; align-items: center; justify-content: center; gap: 6px; padding: 10px; background: #2b6cb0; color: #fff; border: none; border-radius: 8px; font-size: 13px; font-weight: 600; cursor: pointer; margin-top: 12px; }
.fip-run-btn:hover:not(:disabled) { background: #1a4a8a; }
.fip-run-btn:disabled { opacity: .6; cursor: not-allowed; }
.fip-submit-result { display: flex; align-items: center; gap: 8px; padding: 10px; background: #e6fffa; border-radius: 8px; font-size: 12px; color: #276749; margin-top: 10px; }
.fip-submit-result code { font-family: monospace; background: #fff; padding: 2px 6px; border-radius: 4px; color: #24494d; }
.fip-flag-tag { background: #fed7d7; color: #c53030; padding: 1px 6px; border-radius: 8px; font-size: 11px; font-weight: 700; }

/* Fence layout */
.fip-fence-layout { display: grid; grid-template-columns: 380px 1fr; gap: 16px; }
.fip-fence-banner { display: flex; align-items: center; gap: 14px; padding: 16px; border-radius: 10px; }
.fip-fence-banner.ok { background: #e6fffa; color: #276749; }
.fip-fence-banner.err { background: #fff5f5; color: #c53030; }
.fip-fence-status { font-size: 16px; font-weight: 700; margin-bottom: 4px; }
.fip-fence-distance { font-size: 12px; }
.fip-fence-distance b { font-size: 18px; font-weight: 800; }
.fip-kv-list { margin-top: 16px; }
.fip-kv { display: flex; justify-content: space-between; padding: 8px 0; border-bottom: 1px solid #f0f5f3; font-size: 12px; }
.fip-kv span { color: #7a8d89; }
.fip-kv b { color: #24494d; font-family: monospace; font-weight: 600; }

/* Record layout */
.fip-record-layout { display: grid; grid-template-columns: 380px 1fr; gap: 16px; }
.fip-records-list { display: flex; flex-direction: column; gap: 10px; max-height: 600px; overflow-y: auto; }
.fip-record { background: #f8fbfb; border: 1px solid #e9eee9; border-radius: 10px; padding: 12px; }
.fip-record.abnormal { border-left: 3px solid #e06060; background: #fff8f7; }
.fip-record-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.fip-record-code { font-family: monospace; font-size: 12px; color: #24494d; font-weight: 600; }
.fip-record-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 4px 12px; font-size: 12px; }
.fip-record-grid > div { display: flex; gap: 6px; }
.fip-record-grid span { color: #7a8d89; min-width: 60px; }
.fip-record-grid b { color: #24494d; font-family: monospace; font-weight: 600; font-size: 11px; }
.fip-record-full { grid-column: 1 / -1; }
.fip-formdata { display: flex; flex-wrap: wrap; gap: 6px; }
.fip-formdata span { background: #e8f4f2; color: #4f8f99; padding: 2px 8px; border-radius: 10px; font-size: 11px; font-family: monospace; min-width: 0; }

/* Trajectory */
.fip-trajectory-form { display: flex; gap: 8px; align-items: center; }
.fip-trajectory-form label { font-size: 12px; color: #7a8d89; }
.fip-trajectory-content { margin-top: 12px; }
.fip-trajectory-stats { display: flex; gap: 16px; font-size: 12px; color: #56716e; padding: 8px 0; flex-wrap: wrap; }
.fip-trajectory-stats span b { color: #24494d; font-family: monospace; }
.fip-trajectory-svg { width: 100%; height: 280px; background: #f8fbfb; border-radius: 8px; }
.fip-traj-label { font-size: 11px; fill: #24494d; font-weight: 600; }

/* Example */
.fip-example-desc { display: flex; align-items: center; gap: 6px; font-size: 12px; color: #56716e; margin-bottom: 12px; }
.fip-example-desc code { background: #e8f4f2; color: #4f8f99; padding: 2px 6px; border-radius: 4px; }
.fip-code { background: #1a2a2a; color: #c8e6e0; padding: 16px; border-radius: 8px; font-size: 12px; font-family: 'Cascadia Code', monospace; line-height: 1.5; overflow-x: auto; max-height: 480px; overflow-y: auto; }

@media (max-width: 1024px) {
  .fip-fence-layout, .fip-record-layout { grid-template-columns: 1fr; }
}
@media (max-width: 720px) {
  .fip-root { padding: 12px; }
  .fip-kpis { grid-template-columns: repeat(2, 1fr); }
  .fip-form-grid { grid-template-columns: 1fr; }
  .fip-record-grid { grid-template-columns: 1fr; }
}
</style>
