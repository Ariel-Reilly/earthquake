<script setup>
/**
 * OTA 与边缘计算（独立页面 /dashboard/devices/ota）。
 *
 * 角色：运维员 / 管理员
 * 固件包库 + 批量升级任务编排，支持断网续传 / 失败回滚。
 */
import { computed, onMounted, reactive, ref } from "vue"
import {
  ClipboardList,
  Download,
  FileSignature,
  Filter,
  GitBranch,
  Loader2,
  Package,
  PlayCircle,
  Plus,
  RefreshCcw,
  Trash2,
  Upload,
  UserCheck,
  X,
} from "lucide-vue-next"
import { dashboardApi } from "../../services/dashboardApi"
import { getAuthUser, hasPermission } from "../../stores/auth"
import ConfirmDialog from "../../components/common/ConfirmDialog.vue"

const props = defineProps({
  searchTerm: { type: String, default: "" },
  userRole: { type: String, default: "admin" },
})
const emit = defineEmits(["notify"])
const confirmDialogRef = ref(null)

const operator = getAuthUser() ?? { name: "李晨曦", role: "admin" }
const has = (perm) => hasPermission(operator.role, perm)

const loading = ref(false)
const activeTab = ref("firmware")
const firmwareList = ref([])
const taskList = ref([])
const uploadDialog = ref(false)
const newTaskDialog = ref(false)
const detailDrawer = ref(false)
const detailTask = ref(null)

const filter = reactive({
  status: "all",
})

const uploadForm = reactive({
  fileName: "",
  fileSize: 0,
  version: "",
  deviceType: "rain",
  remark: "",
  dragging: false,
})

// 保留 <input type=file> / 拖拽得到的真实 File 对象，用于 FormData 上传。
let selectedFile = null

const newTaskForm = reactive({
  firmwareId: "",
  deviceIds: [],
  resumeOnReconnect: true,
  rollbackOnFailure: true,
})

const deviceTypeOptions = [
  { value: "rain", label: "雨量计" },
  { value: "crack", label: "裂缝计" },
  { value: "tilt", label: "倾角仪" },
  { value: "gnss", label: "GNSS 监测站" },
  { value: "soil", label: "含水率传感器" },
  { value: "video", label: "视频监控" },
]

const statusMeta = {
  pending: { label: "待执行", color: "#76847a" },
  running: { label: "升级中", color: "#6f9e7f" },
  success: { label: "成功", color: "#87b096" },
  failed: { label: "失败", color: "#e06060" },
  canceled: { label: "已取消", color: "#76847a" },
}

const statusTabs = [
  { value: "all", label: "全部" },
  { value: "pending", label: "待执行" },
  { value: "running", label: "升级中" },
  { value: "success", label: "成功" },
  { value: "failed", label: "失败" },
]

// 固件 / 升级任务 / 设备全部来自真实 API，不再内置离线样例数据。
const devices = ref([])
const deviceOptions = computed(() =>
  devices.value.map((d) => ({
    id: d.id ?? d.deviceCode,
    name: d.name ?? d.deviceCode ?? String(d.id),
  }))
)

const kpis = computed(() => {
  const total = firmwareList.value.length
  const running = taskList.value.filter((t) => t.status === "running").length
  const failed = taskList.value.filter((t) => t.status === "failed").length
  const lastSuccess = taskList.value
    .filter((t) => t.status === "success")
    .sort((a, b) => (a.updatedAt < b.updatedAt ? 1 : -1))[0]
  return [
    { label: "固件包数", value: total, color: "#6f9e7f", icon: Package },
    { label: "升级中设备", value: running, color: "#cbaa76", icon: RefreshCcw },
    { label: "失败回滚", value: failed, color: "#e06060", icon: Trash2 },
    {
      label: "最近一次成功",
      value: lastSuccess?.updatedAt ? String(lastSuccess.updatedAt).slice(5) : "—",
      color: "#87b096",
      icon: UserCheck,
    },
  ]
})

const filteredTasks = computed(() => {
  if (filter.status === "all") return taskList.value
  return taskList.value.filter((t) => t.status === filter.status)
})

function toRecords(data) {
  if (Array.isArray(data)) return data
  const v = data?.records ?? data?.items ?? data?.list
  return Array.isArray(v) ? v : []
}

async function loadAll() {
  loading.value = true
  try {
    const [fw, tk, dev] = await Promise.all([
      dashboardApi.listFirmware(),
      dashboardApi.listOtaTasks({ status: filter.status === "all" ? undefined : filter.status }),
      dashboardApi.getDevices({ size: 200 }),
    ])
    firmwareList.value = toRecords(fw)
    taskList.value = toRecords(tk)
    devices.value = toRecords(dev)
  } catch (e) {
    firmwareList.value = []
    taskList.value = []
    devices.value = []
    emit("notify", `加载 OTA 数据失败：${e?.message ?? e}`)
  } finally {
    loading.value = false
  }
}

function openUpload() {
  uploadForm.fileName = ""
  uploadForm.fileSize = 0
  uploadForm.version = ""
  uploadForm.deviceType = "rain"
  uploadForm.remark = ""
  uploadForm.dragging = false
  selectedFile = null
  uploadDialog.value = true
}

function handleDrop(e) {
  e.preventDefault()
  uploadForm.dragging = false
  const file = e.dataTransfer?.files?.[0]
  if (file) applyFile(file)
}

function handleFilePick(e) {
  const file = e.target.files?.[0]
  if (file) applyFile(file)
}

function applyFile(file) {
  selectedFile = file
  uploadForm.fileName = file.name
  uploadForm.fileSize = Math.round(file.size / 1024)
  if (!uploadForm.version) {
    // 根据文件名猜测版本号 vX.Y.Z
    const m = file.name.match(/v?(\d+\.\d+\.\d+)/i)
    if (m) uploadForm.version = `v${m[1]}`
  }
}

async function submitUpload() {
  if (!selectedFile) return emit("notify", "请选择固件文件")
  if (!uploadForm.version.trim()) return emit("notify", "请填写版本号")
  try {
    await dashboardApi.uploadFirmware({
      file: selectedFile,
      version: uploadForm.version.trim(),
      deviceType: uploadForm.deviceType,
      remark: uploadForm.remark.trim(),
    })
    emit("notify", `已上传固件 ${uploadForm.version}，待校验后入库存档`)
    uploadDialog.value = false
    await loadAll()
  } catch (e) {
    emit("notify", e.message ?? "上传失败")
  }
}

async function removeFirmware(fw) {
  const ok = await confirmDialogRef.value?.show({
    title: '删除固件',
    message: `确认删除固件 ${fw.version}？该操作不可恢复。`,
    confirmText: '删除',
    cancelText: '取消',
    isDanger: true
  })
  if (!ok) return
  try {
    await dashboardApi.deleteFirmware(fw.id)
    emit("notify", `固件 ${fw.version} 已删除`)
    await loadAll()
  } catch (e) {
    emit("notify", e.message ?? "删除失败")
  }
}

// 后端 OtaController 仅提供固件列表 / 上传 / 删除，没有固件下载端点，
// dashboardApi 也没有对应下载方法，因此下载按钮禁用并在 UI 明确说明，
// 不再用"已生成下载链接（演示）"伪造成功。
function pushFromFirmware(fw) {
  newTaskForm.firmwareId = fw.id
  newTaskForm.deviceIds = []
  openNewTask()
}

function openNewTask() {
  if (!newTaskForm.firmwareId && firmwareList.value.length) {
    newTaskForm.firmwareId = firmwareList.value[0].id
  }
  if (!newTaskForm.deviceIds.length) newTaskForm.deviceIds = []
  newTaskDialog.value = true
}

function toggleDevice(id) {
  const idx = newTaskForm.deviceIds.indexOf(id)
  if (idx === -1) newTaskForm.deviceIds.push(id)
  else newTaskForm.deviceIds.splice(idx, 1)
}

function isDeviceSelected(id) {
  return newTaskForm.deviceIds.includes(id)
}

async function submitNewTask() {
  if (!newTaskForm.firmwareId) return emit("notify", "请选择固件版本")
  if (!newTaskForm.deviceIds.length) return emit("notify", "请至少选择一个目标设备")
  try {
    await dashboardApi.createOtaTask({
      firmwareId: newTaskForm.firmwareId,
      deviceIds: [...newTaskForm.deviceIds],
      resumeOnReconnect: newTaskForm.resumeOnReconnect,
      rollbackOnFailure: newTaskForm.rollbackOnFailure,
    })
    emit("notify", `已下发 ${newTaskForm.deviceIds.length} 台设备的升级任务`)
    newTaskDialog.value = false
    await loadAll()
  } catch (e) {
    emit("notify", e.message ?? "创建升级任务失败")
  }
}

async function cancelTask(task) {
  const ok = await confirmDialogRef.value?.show({
    title: '取消升级任务',
    message: `取消任务 ${task.id}？该设备的升级将立即终止。`,
    confirmText: '确认',
    cancelText: '取消',
    isDanger: true
  })
  if (!ok) return
  try {
    await dashboardApi.cancelOtaTask(task.id)
    emit("notify", `任务 ${task.id} 已取消`)
    await loadAll()
  } catch (e) {
    emit("notify", e.message ?? "取消失败")
  }
}

async function retryTask(task) {
  try {
    await dashboardApi.retryOtaTask(task.id)
    emit("notify", `已重新触发任务 ${task.id}`)
    await loadAll()
  } catch (e) {
    emit("notify", e.message ?? "重试失败")
  }
}

async function openDetail(task) {
  detailTask.value = task
  detailDrawer.value = true
  try {
    const detail = await dashboardApi.getOtaTaskDetail(task.id)
    if (detail) detailTask.value = { ...task, ...detail }
  } catch (e) {
    emit("notify", `加载任务详情失败：${e?.message ?? e}`)
  }
}

const detailSteps = computed(() => {
  const t = detailTask.value
  if (!t) return []
  const progress = t.progress ?? 0
  const failed = t.status === "failed"
  return [
    { id: "download", label: "下载固件", at: t.updatedAt, done: progress >= 20 || failed },
    { id: "verify", label: "校验签名", at: progress >= 20 ? t.updatedAt : null, done: progress >= 40 || failed },
    { id: "flash", label: "写入 Flash", at: progress >= 40 ? t.updatedAt : null, done: progress >= 70 || failed },
    { id: "reboot", label: "设备重启", at: progress >= 70 ? t.updatedAt : null, done: progress >= 90 || failed },
    { id: "online", label: "重新上线", at: progress >= 100 ? t.updatedAt : null, done: t.status === "success" },
  ]
})

function deviceTypeLabel(t) {
  return deviceTypeOptions.find((o) => o.value === t)?.label ?? t
}

function statusColor(s) {
  return statusMeta[s]?.color ?? "#76847a"
}

function statusLabel(s) {
  return statusMeta[s]?.label ?? s
}

function formatSize(kb) {
  if (kb >= 1024) return `${(kb / 1024).toFixed(2)} MB`
  return `${kb} KB`
}

onMounted(loadAll)
</script>

<template>
  <div class="ota-shell">
    <header class="page-header">
      <div>
      </div>
      <div class="actions">
        <button v-if="has('devices:ota')" class="ghost-btn" @click="loadAll">
          <RefreshCcw :size="14" />刷新
        </button>
        <button v-if="has('devices:ota')" class="primary-btn" @click="openUpload">
          <Upload :size="14" />上传固件
        </button>
        <button v-if="has('devices:ota')" class="primary-btn" @click="openNewTask">
          <Plus :size="14" />新建批量升级
        </button>
      </div>
    </header>

    <section class="kpi-grid">
      <article
        v-for="kpi in kpis"
        :key="kpi.label"
        class="kpi-card"
        :style="{ borderColor: kpi.color + '55' }"
      >
        <component :is="kpi.icon" :size="18" :style="{ color: kpi.color }" />
        <strong>{{ kpi.value }}</strong>
        <span>{{ kpi.label }}</span>
      </article>
    </section>

    <div class="tab-bar">
      <button
        v-for="t in ['firmware', 'task']"
        :key="t"
        :class="{ active: activeTab === t }"
        @click="activeTab = t"
      >
        <component :is="t === 'firmware' ? FileSignature : ClipboardList" :size="14" />
        {{ t === "firmware" ? "固件包库" : "升级任务" }}
      </button>
    </div>

    <section v-if="activeTab === 'firmware'" class="panel-card">
      <div class="firmware-hint">固件下载：后端暂未提供固件下载接口，下载按钮已禁用。</div>
      <div v-if="loading" class="loading-row">
        <Loader2 :size="16" class="spin" />加载固件包…
      </div>
      <div v-else-if="firmwareList.length === 0" class="empty-row">
        暂无固件包 —— 点击右上角「上传固件」开始
      </div>
      <div v-else class="table-wrap">
        <table class="task-table">
          <thead>
            <tr>
              <th>版本号</th>
              <th>适配设备类型</th>
              <th>大小</th>
              <th>SHA256</th>
              <th>上传时间</th>
              <th>备注</th>
              <th class="ops-col">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="fw in firmwareList" :key="fw.id">
              <td><strong>{{ fw.version }}</strong></td>
              <td>
                <span class="pill" style="background:var(--c-border-2);color:#4a7a5e">
                  {{ deviceTypeLabel(fw.deviceType) }}
                </span>
              </td>
              <td><code>{{ formatSize(fw.sizeKb) }}</code></td>
              <td><code class="hash">{{ (fw.sha256 || '').slice(0, 16) || '—' }}…</code></td>
              <td>{{ fw.uploadedAt }}</td>
              <td class="muted">{{ fw.remark || "—" }}</td>
              <td class="ops">
                <button class="ghost-btn" disabled title="后端暂未提供固件下载接口，暂不可用">
                  <Download :size="13" />
                </button>
                <button v-if="has('devices:ota')" class="ghost-btn" @click="pushFromFirmware(fw)" title="推升级任务">
                  <PlayCircle :size="13" />
                </button>
                <button v-if="has('devices:ota')" class="ghost-btn danger" @click="removeFirmware(fw)" title="删除">
                  <Trash2 :size="13" />
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section v-else class="panel-card">
      <div class="filter-row">
        <Filter :size="14" color="#4a7a5e" />
        <button
          v-for="tab in statusTabs"
          :key="tab.value"
          :class="{ active: filter.status === tab.value }"
          @click="filter.status = tab.value"
        >
          {{ tab.label }}
        </button>
      </div>

      <div v-if="loading" class="loading-row">
        <Loader2 :size="16" class="spin" />加载升级任务…
      </div>
      <div v-else-if="filteredTasks.length === 0" class="empty-row">
        当前筛选下没有升级任务
      </div>
      <div v-else class="table-wrap">
        <table class="task-table">
          <thead>
            <tr>
              <th>任务 ID</th>
              <th>目标设备</th>
              <th>固件版本</th>
              <th>进度</th>
              <th>状态</th>
              <th>更新时间</th>
              <th class="ops-col">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in filteredTasks" :key="t.id" @click="openDetail(t)">
              <td><code>{{ t.id }}</code></td>
              <td>
                <strong>{{ t.deviceName }}</strong>
                <small>{{ t.deviceId }}</small>
              </td>
              <td>{{ t.firmwareVersion }}</td>
              <td>
                <div class="progress">
                  <div class="fill" :style="{ width: t.progress + '%', background: statusColor(t.status) }"></div>
                </div>
                <small>{{ t.progress }}%</small>
              </td>
              <td>
                <span class="dot" :style="{ background: statusColor(t.status) }"></span>
                {{ statusLabel(t.status) }}
              </td>
              <td>{{ t.updatedAt }}</td>
              <td class="ops" @click.stop>
                <button
                  v-if="t.status === 'running' || t.status === 'pending'"
                  class="ghost-btn"
                  @click="cancelTask(t)"
                  title="取消"
                >
                  <X :size="13" />
                </button>
                <button v-if="t.status === 'failed'" class="ghost-btn" @click="retryTask(t)" title="重试">
                  <RefreshCcw :size="13" />
                </button>
                <button class="ghost-btn" @click="openDetail(t)" title="详情">
                  <GitBranch :size="13" />
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <!-- 上传固件弹窗 -->
    <div v-if="uploadDialog" class="modal-mask" @click.self="uploadDialog = false">
      <div class="modal-card">
        <h3>上传固件包</h3>
        <div
          class="dropzone"
          :class="{ dragging: uploadForm.dragging }"
          @dragover.prevent="uploadForm.dragging = true"
          @dragleave="uploadForm.dragging = false"
          @drop="handleDrop"
        >
          <Upload :size="22" color="#6f9e7f" />
          <p v-if="!uploadForm.fileName">将固件文件拖拽到此处，或点击下方按钮选择文件</p>
          <p v-else>
            已选择：<strong>{{ uploadForm.fileName }}</strong>
            <small>({{ formatSize(uploadForm.fileSize) }})</small>
          </p>
          <input id="fw-file" type="file" accept=".bin,.img,.fw,.zip" hidden @change="handleFilePick" />
          <label for="fw-file" class="primary-btn mini">选择文件</label>
        </div>
        <label>版本号<input v-model="uploadForm.version" placeholder="例如 v2.4.2" /></label>
        <label>适配设备类型
          <select v-model="uploadForm.deviceType">
            <option v-for="o in deviceTypeOptions" :key="o.value" :value="o.value">{{ o.label }}</option>
          </select>
        </label>
        <label>备注<textarea v-model="uploadForm.remark" rows="2" placeholder="本次升级要点说明（可选）" /></label>
        <div class="modal-actions">
          <button class="ghost-btn" @click="uploadDialog = false">取消</button>
          <button class="primary-btn" @click="submitUpload">上传并入库</button>
        </div>
      </div>
    </div>

    <!-- 新建批量升级弹窗 -->
    <div v-if="newTaskDialog" class="modal-mask" @click.self="newTaskDialog = false">
      <div class="modal-card wide">
        <h3>新建批量升级任务</h3>
        <label>固件版本
          <select v-model="newTaskForm.firmwareId">
            <option value="" disabled>请选择</option>
            <option v-for="fw in firmwareList" :key="fw.id" :value="fw.id">
              {{ fw.version }}（{{ deviceTypeLabel(fw.deviceType) }}）
            </option>
          </select>
        </label>
        <div class="device-picker">
          <span class="picker-label">目标设备（{{ newTaskForm.deviceIds.length }} / {{ deviceOptions.length }}）</span>
          <div v-if="!deviceOptions.length" class="picker-empty">暂无可选设备（请确认设备数据是否已加载）</div>
          <ul v-else>
            <li v-for="d in deviceOptions" :key="d.id">
              <label class="check-row">
                <input type="checkbox" :checked="isDeviceSelected(d.id)" @change="toggleDevice(d.id)" />
                <strong>{{ d.name }}</strong>
                <small>{{ d.id }}</small>
              </label>
            </li>
          </ul>
        </div>
        <div class="switch-row">
          <label class="switch">
            <input type="checkbox" v-model="newTaskForm.resumeOnReconnect" />
            <span>断网续传（设备离线时缓存包，恢复后自动续传）</span>
          </label>
          <label class="switch">
            <input type="checkbox" v-model="newTaskForm.rollbackOnFailure" />
            <span>失败回滚（校验/写入失败时自动回滚到原版本）</span>
          </label>
        </div>
        <div class="modal-actions">
          <button class="ghost-btn" @click="newTaskDialog = false">取消</button>
          <button class="primary-btn" @click="submitNewTask">下发升级</button>
        </div>
      </div>
    </div>

    <!-- 任务详情抽屉 -->
    <div v-if="detailDrawer" class="drawer-mask" @click.self="detailDrawer = false">
      <aside class="drawer">
        <header>
          <h3>升级任务详情</h3>
          <button class="ghost-btn icon" @click="detailDrawer = false"><X :size="14" /></button>
        </header>
        <div v-if="detailTask" class="drawer-body">
          <div class="detail-meta">
            <div><span>任务 ID</span><code>{{ detailTask.id }}</code></div>
            <div><span>目标设备</span><strong>{{ detailTask.deviceName }}（{{ detailTask.deviceId }}）</strong></div>
            <div><span>固件版本</span><strong>{{ detailTask.firmwareVersion }}</strong></div>
            <div>
              <span>状态</span>
              <span class="dot" :style="{ background: statusColor(detailTask.status) }"></span>
              {{ statusLabel(detailTask.status) }}
            </div>
          </div>

          <section class="step-section">
            <h4>执行步骤</h4>
            <ol class="step-list">
              <li v-for="(s, i) in detailSteps" :key="s.id" :class="{ done: s.done }">
                <span class="step-index">{{ i + 1 }}</span>
                <div class="step-body">
                  <strong>{{ s.label }}</strong>
                  <small>{{ s.at || "等待执行" }}</small>
                </div>
                <span v-if="s.done" class="step-tag">已完成</span>
              </li>
            </ol>
          </section>

          <section v-if="detailTask.errorLog" class="error-section">
            <h4>错误日志</h4>
            <pre>{{ detailTask.errorLog }}</pre>
          </section>
        </div>
      </aside>
    </div>
    <ConfirmDialog ref="confirmDialogRef" />
  </div>
</template>

<style scoped>
.ota-shell { padding: 24px 28px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 18px; gap: 12px; flex-wrap: wrap; }
.page-header h2 { margin: 0; display: flex; gap: 8px; align-items: center; font-size: 18px; color: var(--c-text); }
.page-header p { margin: 4px 0 0; color: var(--c-text-2); font-size: 12px; }
.actions { display: flex; gap: 8px; flex-wrap: wrap; }

.primary-btn { display: inline-flex; gap: 6px; align-items: center; padding: 8px 14px; border: 0; background: var(--c-brand); color: var(--c-on-brand); border-radius: var(--r-sm); font-size: 13px; cursor: pointer; }
.primary-btn.mini { padding: 6px 12px; font-size: 12px; }
.ghost-btn { display: inline-flex; gap: 4px; align-items: center; padding: 6px 10px; border: 1px solid var(--c-separator); background: var(--c-surface); border-radius: var(--r-sm); color: var(--c-text); font-size: 12px; cursor: pointer; }
.ghost-btn:hover { background: var(--c-bg); }
.ghost-btn.icon { padding: 6px; }
.ghost-btn.danger { color: var(--c-danger-strong); border-color: color-mix(in srgb, var(--c-danger-strong) 30%, transparent); }

.kpi-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-bottom: 18px; }
.kpi-card { background: var(--c-surface); border: 1px solid var(--c-brand-soft); border-left: 4px solid; border-radius: var(--r-md); padding: 14px 16px; display: flex; flex-direction: column; gap: 4px; }
.kpi-card strong { font-size: 22px; color: var(--c-text); }
.kpi-card span { color: var(--c-brand-deep); font-size: 12px; }

.tab-bar { display: flex; gap: 4px; background: var(--c-bg); padding: 4px; border-radius: var(--r-sm); margin-bottom: 14px; width: max-content; }
.tab-bar button { display: inline-flex; gap: 6px; align-items: center; padding: 8px 16px; border: 0; background: transparent; border-radius: var(--r-sm); cursor: pointer; font-size: 13px; color: var(--c-brand-deep); }
.tab-bar button.active { background: var(--c-surface); color: var(--c-text); box-shadow: 0 1px 4px rgba(0,0,0,.06); }

.panel-card { background: var(--c-surface); border: 1px solid var(--c-brand-soft); border-radius: var(--r-md); padding: 16px; }
.filter-row { display: flex; gap: 6px; align-items: center; margin-bottom: 14px; flex-wrap: wrap; }
.filter-row button { padding: 5px 12px; border: 1px solid transparent; background: transparent; border-radius: var(--r-sm); cursor: pointer; font-size: 12px; color: var(--c-brand-deep); }
.filter-row button.active { background: var(--c-border-2); color: var(--c-brand-deep); border-color: var(--c-separator); }

.table-wrap { overflow-x: auto; }
.task-table { width: 100%; border-collapse: collapse; min-width: 720px; }
.task-table th, .task-table td { padding: 12px 14px; text-align: left; font-size: 13px; border-bottom: 1px solid var(--c-border-2); }
.task-table th { background: var(--c-surface-2); color: var(--c-brand-deep); font-weight: 600; }
.task-table tbody tr { cursor: pointer; transition: background .12s; }
.task-table tbody tr:hover { background: var(--c-bg); }
.task-table td strong { display: block; color: var(--c-text); }
.task-table td small { display: block; color: var(--c-text-2); font-size: 11px; margin-top: 2px; }
.task-table code { background: var(--c-bg); padding: 2px 6px; border-radius: var(--r-xs); font-size: 12px; }
.task-table .hash { color: var(--c-brand); }
.ops-col { white-space: nowrap; }
.ops .ghost-btn { margin-right: 4px; }
.muted { color: var(--c-text-2); }
.pill { display: inline-block; padding: 2px 8px; border-radius: var(--r-xs); font-size: 11px; font-weight: 600; }

.progress { height: 6px; background: var(--c-bg); border-radius: 3px; overflow: hidden; margin-bottom: 4px; }
.progress .fill { height: 100%; background: var(--c-brand); border-radius: 3px; transition: width .3s; }
.dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 6px; vertical-align: middle; }

.firmware-hint { margin-bottom: 12px; padding: 8px 12px; border: 1px solid var(--c-separator); border-radius: var(--r-sm); background: var(--c-surface-2); color: var(--c-text-2); font-size: 12px; }
.loading-row, .empty-row { padding: 28px; text-align: center; color: var(--c-text-2); font-size: 13px; }

.modal-mask { position: fixed; inset: 0; background: var(--c-overlay); display: grid; place-items: center; z-index: 100; padding: 20px; }
.modal-card { width: min(480px, 100%); max-height: 90vh; overflow-y: auto; background: var(--c-surface); border-radius: var(--r-lg); padding: 24px; }
.modal-card.wide { width: min(560px, 100%); }
.modal-card h3 { margin: 0 0 16px; color: var(--c-text); font-size: 16px; }
.modal-card label { display: block; margin-bottom: 14px; color: var(--c-brand-deep); font-size: 12px; font-weight: 600; }
.modal-card input, .modal-card textarea, .modal-card select { display: block; width: 100%; margin-top: 6px; padding: 8px 10px; border: 1px solid var(--c-separator); border-radius: var(--r-sm); background: var(--c-surface-2); font-size: 13px; }

.dropzone { padding: 28px 16px; border: 2px dashed var(--c-separator); border-radius: var(--r-md); background: var(--c-surface-2); text-align: center; margin-bottom: 14px; }
.dropzone.dragging { border-color: var(--c-brand); background: color-mix(in srgb, var(--c-brand) 8%, transparent); }
.dropzone p { margin: 8px 0; color: var(--c-brand-deep); font-size: 13px; }
.dropzone strong { color: var(--c-text); }

.device-picker { margin-bottom: 14px; }
.picker-label { display: block; margin-bottom: 8px; color: var(--c-brand-deep); font-size: 12px; font-weight: 600; }
.picker-empty { padding: 16px; text-align: center; color: var(--c-text-2); font-size: 12px; border: 1px dashed var(--c-separator); border-radius: var(--r-sm); }
.device-picker ul { list-style: none; padding: 0; margin: 0; border: 1px solid var(--c-brand-soft); border-radius: var(--r-sm); max-height: 200px; overflow-y: auto; }
.device-picker li { border-bottom: 1px solid var(--c-border-2); }
.device-picker li:last-child { border-bottom: 0; }
.check-row { display: flex; gap: 10px; align-items: center; padding: 10px 12px; margin: 0; font-weight: 500; cursor: pointer; }
.check-row small { color: var(--c-text-2); margin-left: auto; }

.switch-row { display: flex; flex-direction: column; gap: 10px; margin-bottom: 16px; }
.switch { display: flex; gap: 8px; align-items: center; font-size: 13px; color: var(--c-text); font-weight: 500; cursor: pointer; }
.switch input { width: 14px; height: 14px; accent-color: var(--c-brand); }

.modal-actions { display: flex; gap: 10px; justify-content: flex-end; margin-top: 4px; }

.drawer-mask { position: fixed; inset: 0; background: var(--c-overlay); display: flex; justify-content: flex-end; z-index: 110; }
.drawer { width: min(440px, 92vw); background: var(--c-surface); height: 100%; display: flex; flex-direction: column; box-shadow: -8px 0 24px rgba(0,0,0,.08); }
.drawer > header { display: flex; align-items: center; justify-content: space-between; padding: 18px 20px; border-bottom: 1px solid var(--c-border-2); }
.drawer > header h3 { margin: 0; font-size: 15px; color: var(--c-text); }
.drawer-body { padding: 20px; overflow-y: auto; }

.detail-meta { display: grid; grid-template-columns: 1fr; gap: 8px; padding: 14px; border: 1px solid var(--c-brand-soft); border-radius: var(--r-sm); background: var(--c-surface-2); margin-bottom: 18px; }
.detail-meta div { display: flex; gap: 10px; align-items: center; font-size: 12px; color: var(--c-brand-deep); }
.detail-meta span { width: 80px; color: var(--c-text-2); }
.detail-meta strong { color: var(--c-text); font-weight: 600; }
.detail-meta code { background: var(--c-surface); padding: 2px 6px; border-radius: var(--r-xs); font-size: 11px; }

.step-section h4, .error-section h4 { margin: 0 0 12px; font-size: 13px; color: var(--c-text); }
.step-list { list-style: none; padding: 0; margin: 0; }
.step-list li { display: flex; gap: 12px; align-items: center; padding: 12px; border-left: 2px solid var(--c-brand-soft); margin-bottom: 6px; }
.step-list li.done { border-left-color: var(--c-brand-hover); }
.step-list li.done .step-index { background: var(--c-brand-hover); color: var(--c-on-brand); }
.step-index { width: 24px; height: 24px; border-radius: 50%; background: var(--c-bg); color: var(--c-brand-deep); display: grid; place-items: center; font-size: 12px; font-weight: 600; }
.step-body { flex: 1; }
.step-body strong { display: block; color: var(--c-text); font-size: 13px; }
.step-body small { color: var(--c-text-2); font-size: 11px; }
.step-tag { font-size: 11px; color: var(--c-brand-deep); background: rgba(109,191,165,.16); padding: 2px 8px; border-radius: var(--r-xs); }

.error-section { margin-top: 18px; }
.error-section pre { background: var(--c-danger-bg); color: var(--c-danger-strong); padding: 12px; border-radius: var(--r-sm); font-size: 12px; white-space: pre-wrap; word-break: break-all; margin: 0; }

.spin { animation: spin 1s linear infinite; }
@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }

@media (max-width: 880px) {
  .kpi-grid { grid-template-columns: repeat(2, 1fr); }
  .page-header h2 { font-size: 16px; }
}
@media (max-width: 540px) {
  .ota-shell { padding: 16px; }
  .kpi-grid { gap: 10px; }
  .actions { width: 100%; }
  .actions button { flex: 1; justify-content: center; }
}
</style>
