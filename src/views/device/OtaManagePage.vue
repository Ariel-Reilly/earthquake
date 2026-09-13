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

const props = defineProps({
  searchTerm: { type: String, default: "" },
  userRole: { type: String, default: "admin" },
})
const emit = defineEmits(["notify"])

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
  pending: { label: "待执行", color: "#7a8d89" },
  running: { label: "升级中", color: "#4f8f99" },
  success: { label: "成功", color: "#6dbfa5" },
  failed: { label: "失败", color: "#e06060" },
  canceled: { label: "已取消", color: "#7a8d89" },
}

const statusTabs = [
  { value: "all", label: "全部" },
  { value: "pending", label: "待执行" },
  { value: "running", label: "升级中" },
  { value: "success", label: "成功" },
  { value: "failed", label: "失败" },
]

// 离线状态下的样例固件与设备
const sampleFirmware = [
  {
    id: "fw-001",
    version: "v2.4.1",
    deviceType: "rain",
    sizeKb: 4380,
    sha256: "9af23b6c01e7d4f5",
    uploadedAt: "2026-08-28 10:24",
    remark: "修复低功耗模式唤醒异常",
  },
  {
    id: "fw-002",
    version: "v1.7.3",
    deviceType: "crack",
    sizeKb: 2960,
    sha256: "2c6a18bd3340f8e1",
    uploadedAt: "2026-08-21 14:08",
    remark: "新增蓝牙就近升级通道",
  },
  {
    id: "fw-003",
    version: "v3.0.0-rc2",
    deviceType: "gnss",
    sizeKb: 8120,
    sha256: "44e0cdb9102a8d75",
    uploadedAt: "2026-08-15 09:15",
    remark: "重大协议升级，需配合回归测试",
  },
  {
    id: "fw-004",
    version: "v2.1.6",
    deviceType: "tilt",
    sizeKb: 1840,
    sha256: "b9d2f7e4c0ab8315",
    uploadedAt: "2026-08-05 16:42",
    remark: "提升抗电磁干扰能力",
  },
]

const sampleDevices = [
  { id: "RAIN-101", name: "龙安镇雨量站 A" },
  { id: "RAIN-102", name: "龙安镇雨量站 B" },
  { id: "RAIN-205", name: "石桥村雨量站" },
  { id: "CRACK-018", name: "北山滑坡裂缝计" },
  { id: "TILT-031", name: "塔山倾角仪" },
  { id: "GNSS-005", name: "GNSS 基站 5 号" },
]

const sampleTasks = [
  {
    id: "OTA-20260828-01",
    deviceId: "RAIN-101",
    deviceName: "龙安镇雨量站 A",
    firmwareId: "fw-001",
    firmwareVersion: "v2.4.1",
    progress: 64,
    status: "running",
    updatedAt: "2026-08-28 11:12",
  },
  {
    id: "OTA-20260827-02",
    deviceId: "RAIN-205",
    deviceName: "石桥村雨量站",
    firmwareId: "fw-001",
    firmwareVersion: "v2.4.1",
    progress: 100,
    status: "success",
    updatedAt: "2026-08-27 18:40",
  },
  {
    id: "OTA-20260826-03",
    deviceId: "TILT-031",
    deviceName: "塔山倾角仪",
    firmwareId: "fw-004",
    firmwareVersion: "v2.1.6",
    progress: 32,
    status: "failed",
    updatedAt: "2026-08-26 09:55",
    errorLog: "写入校验失败，已自动回滚至 v2.1.5",
  },
  {
    id: "OTA-20260825-04",
    deviceId: "GNSS-005",
    deviceName: "GNSS 基站 5 号",
    firmwareId: "fw-003",
    firmwareVersion: "v3.0.0-rc2",
    progress: 0,
    status: "pending",
    updatedAt: "2026-08-25 22:10",
  },
]

const kpis = computed(() => {
  const total = firmwareList.value.length
  const running = taskList.value.filter((t) => t.status === "running").length
  const failed = taskList.value.filter((t) => t.status === "failed").length
  const lastSuccess = taskList.value
    .filter((t) => t.status === "success")
    .sort((a, b) => (a.updatedAt < b.updatedAt ? 1 : -1))[0]
  return [
    { label: "固件包数", value: total, color: "#4f8f99", icon: Package },
    { label: "升级中设备", value: running, color: "#e0a050", icon: RefreshCcw },
    { label: "失败回滚", value: failed, color: "#e06060", icon: Trash2 },
    {
      label: "最近一次成功",
      value: lastSuccess ? lastSuccess.updatedAt.slice(5) : "—",
      color: "#6dbfa5",
      icon: UserCheck,
    },
  ]
})

const filteredTasks = computed(() => {
  if (filter.status === "all") return taskList.value
  return taskList.value.filter((t) => t.status === filter.status)
})

async function loadAll() {
  loading.value = true
  try {
    const [fw, tk] = await Promise.all([
      dashboardApi.listFirmware().catch(() => ({ records: sampleFirmware, total: sampleFirmware.length })),
      dashboardApi.listOtaTasks({ status: filter.status === "all" ? undefined : filter.status }).catch(
        () => ({ records: sampleTasks, total: sampleTasks.length }),
      ),
    ])
    firmwareList.value = fw?.records ?? []
    taskList.value = tk?.records ?? []
  } catch (e) {
    emit("notify", e.message ?? "加载 OTA 数据失败")
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
  uploadForm.fileName = file.name
  uploadForm.fileSize = Math.round(file.size / 1024)
  if (!uploadForm.version) {
    // 根据文件名猜测版本号 vX.Y.Z
    const m = file.name.match(/v?(\d+\.\d+\.\d+)/i)
    if (m) uploadForm.version = `v${m[1]}`
  }
}

async function submitUpload() {
  if (!uploadForm.fileName) return emit("notify", "请选择固件文件")
  if (!uploadForm.version.trim()) return emit("notify", "请填写版本号")
  try {
    await dashboardApi.uploadFirmware({
      file: { name: uploadForm.fileName, size: uploadForm.fileSize },
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
  if (!window.confirm(`确认删除固件 ${fw.version}？该操作不可恢复。`)) return
  try {
    await dashboardApi.deleteFirmware(fw.id)
    emit("notify", `固件 ${fw.version} 已删除`)
    await loadAll()
  } catch (e) {
    emit("notify", e.message ?? "删除失败")
  }
}

function downloadFirmware(fw) {
  emit("notify", `已生成 ${fw.version} 下载链接（演示）`)
}

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
  if (!window.confirm(`取消任务 ${task.id}？该设备的升级将立即终止。`)) return
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
  } catch {
    /* 离线时使用已有信息 */
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
  return statusMeta[s]?.color ?? "#7a8d89"
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
        <h2><Package :size="18" />OTA 与边缘计算</h2>
        <p>固件包库、批量升级任务编排 —— 支持断网续传与失败回滚。</p>
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
                <span class="pill" style="background:#e7f0ed;color:#2a8154">
                  {{ deviceTypeLabel(fw.deviceType) }}
                </span>
              </td>
              <td><code>{{ formatSize(fw.sizeKb) }}</code></td>
              <td><code class="hash">{{ fw.sha256.slice(0, 16) }}…</code></td>
              <td>{{ fw.uploadedAt }}</td>
              <td class="muted">{{ fw.remark || "—" }}</td>
              <td class="ops">
                <button class="ghost-btn" @click="downloadFirmware(fw)" title="下载">
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
        <Filter :size="14" color="#56716e" />
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
          <Upload :size="22" color="#4f8f99" />
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
          <span class="picker-label">目标设备（{{ newTaskForm.deviceIds.length }} / {{ sampleDevices.length }}）</span>
          <ul>
            <li v-for="d in sampleDevices" :key="d.id">
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
  </div>
</template>

<style scoped>
.ota-shell { padding: 24px 28px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 18px; gap: 12px; flex-wrap: wrap; }
.page-header h2 { margin: 0; display: flex; gap: 8px; align-items: center; font-size: 18px; color: #1f3e44; }
.page-header p { margin: 4px 0 0; color: #7a8d89; font-size: 12px; }
.actions { display: flex; gap: 8px; flex-wrap: wrap; }

.primary-btn { display: inline-flex; gap: 6px; align-items: center; padding: 8px 14px; border: 0; background: #2f7f80; color: #fff; border-radius: 8px; font-size: 13px; cursor: pointer; }
.primary-btn.mini { padding: 6px 12px; font-size: 12px; }
.ghost-btn { display: inline-flex; gap: 4px; align-items: center; padding: 6px 10px; border: 1px solid #d9e6e1; background: #fff; border-radius: 6px; color: #37555a; font-size: 12px; cursor: pointer; }
.ghost-btn:hover { background: #f4f7f5; }
.ghost-btn.icon { padding: 6px; }
.ghost-btn.danger { color: #c7553b; border-color: rgba(199,85,59,.3); }

.kpi-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-bottom: 18px; }
.kpi-card { background: #fff; border: 1px solid #e9eee9; border-left: 4px solid; border-radius: 10px; padding: 14px 16px; display: flex; flex-direction: column; gap: 4px; }
.kpi-card strong { font-size: 22px; color: #1f3e44; }
.kpi-card span { color: #56716e; font-size: 12px; }

.tab-bar { display: flex; gap: 4px; background: #f4f7f5; padding: 4px; border-radius: 8px; margin-bottom: 14px; width: max-content; }
.tab-bar button { display: inline-flex; gap: 6px; align-items: center; padding: 8px 16px; border: 0; background: transparent; border-radius: 6px; cursor: pointer; font-size: 13px; color: #56716e; }
.tab-bar button.active { background: #fff; color: #1f3e44; box-shadow: 0 1px 4px rgba(0,0,0,.06); }

.panel-card { background: #fff; border: 1px solid #e9eee9; border-radius: 10px; padding: 16px; }
.filter-row { display: flex; gap: 6px; align-items: center; margin-bottom: 14px; flex-wrap: wrap; }
.filter-row button { padding: 5px 12px; border: 1px solid transparent; background: transparent; border-radius: 6px; cursor: pointer; font-size: 12px; color: #56716e; }
.filter-row button.active { background: #e7f0ed; color: #2a8154; border-color: #cfe1d9; }

.table-wrap { overflow-x: auto; }
.task-table { width: 100%; border-collapse: collapse; min-width: 720px; }
.task-table th, .task-table td { padding: 12px 14px; text-align: left; font-size: 13px; border-bottom: 1px solid #eef3f1; }
.task-table th { background: #fafdfc; color: #56716e; font-weight: 600; }
.task-table tbody tr { cursor: pointer; transition: background .12s; }
.task-table tbody tr:hover { background: #f4f7f5; }
.task-table td strong { display: block; color: #1f3e44; }
.task-table td small { display: block; color: #7a8d89; font-size: 11px; margin-top: 2px; }
.task-table code { background: #f4f7f5; padding: 2px 6px; border-radius: 4px; font-size: 12px; }
.task-table .hash { color: #2b6cb0; }
.ops-col { white-space: nowrap; }
.ops .ghost-btn { margin-right: 4px; }
.muted { color: #7a8d89; }
.pill { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 11px; font-weight: 600; }

.progress { height: 6px; background: #f4f7f5; border-radius: 3px; overflow: hidden; margin-bottom: 4px; }
.progress .fill { height: 100%; background: #5fbd9a; border-radius: 3px; transition: width .3s; }
.dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 6px; vertical-align: middle; }

.loading-row, .empty-row { padding: 28px; text-align: center; color: #7a8d89; font-size: 13px; }

.modal-mask { position: fixed; inset: 0; background: rgba(15,32,38,.45); display: grid; place-items: center; z-index: 100; padding: 20px; }
.modal-card { width: min(480px, 100%); max-height: 90vh; overflow-y: auto; background: #fff; border-radius: 14px; padding: 24px; }
.modal-card.wide { width: min(560px, 100%); }
.modal-card h3 { margin: 0 0 16px; color: #1f3e44; font-size: 16px; }
.modal-card label { display: block; margin-bottom: 14px; color: #56716e; font-size: 12px; font-weight: 600; }
.modal-card input, .modal-card textarea, .modal-card select { display: block; width: 100%; margin-top: 6px; padding: 8px 10px; border: 1px solid #d9e6e1; border-radius: 8px; background: #fafdfc; font-size: 13px; }

.dropzone { padding: 28px 16px; border: 2px dashed #d9e6e1; border-radius: 10px; background: #fafdfc; text-align: center; margin-bottom: 14px; }
.dropzone.dragging { border-color: #4f8f99; background: #eff9f6; }
.dropzone p { margin: 8px 0; color: #56716e; font-size: 13px; }
.dropzone strong { color: #1f3e44; }

.device-picker { margin-bottom: 14px; }
.picker-label { display: block; margin-bottom: 8px; color: #56716e; font-size: 12px; font-weight: 600; }
.device-picker ul { list-style: none; padding: 0; margin: 0; border: 1px solid #e9eee9; border-radius: 8px; max-height: 200px; overflow-y: auto; }
.device-picker li { border-bottom: 1px solid #eef3f1; }
.device-picker li:last-child { border-bottom: 0; }
.check-row { display: flex; gap: 10px; align-items: center; padding: 10px 12px; margin: 0; font-weight: 500; cursor: pointer; }
.check-row small { color: #7a8d89; margin-left: auto; }

.switch-row { display: flex; flex-direction: column; gap: 10px; margin-bottom: 16px; }
.switch { display: flex; gap: 8px; align-items: center; font-size: 13px; color: #37555a; font-weight: 500; cursor: pointer; }
.switch input { width: 14px; height: 14px; accent-color: #2f7f80; }

.modal-actions { display: flex; gap: 10px; justify-content: flex-end; margin-top: 4px; }

.drawer-mask { position: fixed; inset: 0; background: rgba(15,32,38,.35); display: flex; justify-content: flex-end; z-index: 110; }
.drawer { width: min(440px, 92vw); background: #fff; height: 100%; display: flex; flex-direction: column; box-shadow: -8px 0 24px rgba(0,0,0,.08); }
.drawer > header { display: flex; align-items: center; justify-content: space-between; padding: 18px 20px; border-bottom: 1px solid #eef3f1; }
.drawer > header h3 { margin: 0; font-size: 15px; color: #1f3e44; }
.drawer-body { padding: 20px; overflow-y: auto; }

.detail-meta { display: grid; grid-template-columns: 1fr; gap: 8px; padding: 14px; border: 1px solid #e9eee9; border-radius: 8px; background: #fafdfc; margin-bottom: 18px; }
.detail-meta div { display: flex; gap: 10px; align-items: center; font-size: 12px; color: #56716e; }
.detail-meta span { width: 80px; color: #7a8d89; }
.detail-meta strong { color: #1f3e44; font-weight: 600; }
.detail-meta code { background: #fff; padding: 2px 6px; border-radius: 4px; font-size: 11px; }

.step-section h4, .error-section h4 { margin: 0 0 12px; font-size: 13px; color: #1f3e44; }
.step-list { list-style: none; padding: 0; margin: 0; }
.step-list li { display: flex; gap: 12px; align-items: center; padding: 12px; border-left: 2px solid #e9eee9; margin-bottom: 6px; }
.step-list li.done { border-left-color: #6dbfa5; }
.step-list li.done .step-index { background: #6dbfa5; color: #fff; }
.step-index { width: 24px; height: 24px; border-radius: 50%; background: #f4f7f5; color: #56716e; display: grid; place-items: center; font-size: 12px; font-weight: 600; }
.step-body { flex: 1; }
.step-body strong { display: block; color: #1f3e44; font-size: 13px; }
.step-body small { color: #7a8d89; font-size: 11px; }
.step-tag { font-size: 11px; color: #2a8154; background: rgba(109,191,165,.16); padding: 2px 8px; border-radius: 4px; }

.error-section { margin-top: 18px; }
.error-section pre { background: #faf0ee; color: #c7553b; padding: 12px; border-radius: 8px; font-size: 12px; white-space: pre-wrap; word-break: break-all; margin: 0; }

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
