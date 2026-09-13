<script setup>
/**
 * AI 巡检任务管理（独立页面 /dashboard/ai-tasks）。
 *
 * 角色：AI 工程师 / 值班员
 * 编排 AI 自动巡查节奏 —— 周期调度 / 事件触发；启动后将持续 7x24h 无人值守。
 */
import { computed, onMounted, reactive, ref, watch } from "vue"
import { useRouter } from "vue-router"
import {
  CalendarDays,
  Clock,
  Edit,
  Loader2,
  Pause,
  Play,
  Plus,
  Radio,
  Sparkles,
  Trash2,
  Zap,
} from "lucide-vue-next"
import { dashboardApi } from "../../services/dashboardApi"
import { getAuthUser, hasPermission } from "../../stores/auth"

const props = defineProps({
  searchTerm: { type: String, default: "" },
  userRole: { type: String, default: "admin" },
})
const emit = defineEmits(["notify"])

const router = useRouter()
const operator = getAuthUser() ?? { name: "李晨曦", role: "admin" }

const loading = ref(false)
const saving = ref(false)
const tasks = ref({ list: [], total: 0 })
const filter = reactive({ keyword: "", trigger: "", status: "" })

const createDialog = ref(false)
const newTask = reactive({
  name: "",
  scope: "",
  cron: "0 * * * *",
  trigger: "cron",
  enabled: true,
  description: "",
  modelId: "yolov8",
})

const triggerOptions = [
  { id: "cron", label: "周期调度", icon: CalendarDays, color: "#2b6cb0",
    desc: "按 cron 表达式自动执行（如每小时、每天凌晨、每周一）" },
  { id: "event-earthquake", label: "地震触发", icon: Zap, color: "#c7553b",
    desc: "USGS / 中国地震台网发布 M≥3.0 地震后立即启动" },
  { id: "event-rain", label: "暴雨触发", icon: Sparkles, color: "#5db486",
    desc: "区域内 24h 累计降水 ≥ 50mm 后立即启动" },
  { id: "event-device", label: "设备告警", icon: Radio, color: "#9b761e",
    desc: "监测设备离线 / 数据异常时触发紧急巡查" },
]

const cronPresets = [
  { id: "0 * * * *", label: "每小时" },
  { id: "0 0 * * *", label: "每天 0 点" },
  { id: "0 */6 * * *", label: "每 6 小时" },
  { id: "0 8 * * 1", label: "每周一 8 点" },
]

const filteredTasks = computed(() => {
  let list = tasks.value.list ?? []
  if (filter.trigger) list = list.filter((t) => (t.trigger ?? "cron") === filter.trigger)
  if (filter.status) list = list.filter((t) => t.status === filter.status)
  if (filter.keyword.trim()) {
    const k = filter.keyword.trim().toLowerCase()
    list = list.filter(
      (t) => (t.name ?? "").toLowerCase().includes(k) ||
             (t.scope ?? "").toLowerCase().includes(k),
    )
  }
  return list
})

async function loadTasks() {
  loading.value = true
  try {
    const data = await dashboardApi.getTasks({ page: 1, size: 50 })
    tasks.value = data ?? { list: [], total: 0 }
  } catch (e) {
    emit("notify", e.message ?? "加载任务失败")
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(newTask, {
    name: "",
    scope: "全部灾害点",
    cron: "0 * * * *",
    trigger: "cron",
    enabled: true,
    description: "",
    modelId: "yolov8",
  })
  createDialog.value = true
}

async function save() {
  if (!newTask.name.trim()) return emit("notify", "请输入任务名称")
  saving.value = true
  try {
    await dashboardApi.createTask({
      name: newTask.name,
      scope: newTask.scope,
      cron: newTask.cron,
      trigger: newTask.trigger,
      enabled: newTask.enabled,
      description: newTask.description,
      modelId: newTask.modelId,
      owner: operator.name,
    })
    emit("notify", "任务创建成功，下次调度周期将自动执行")
    createDialog.value = false
    await loadTasks()
  } catch (e) {
    emit("notify", e.message ?? "创建失败")
  } finally {
    saving.value = false
  }
}

async function toggleStatus(task) {
  const next = task.status === "运行中" ? "已暂停" : "运行中"
  try {
    await dashboardApi.updateTaskStatus(task.id, next)
    task.status = next
    emit("notify", `任务已${next}`)
  } catch (e) {
    emit("notify", e.message ?? "操作失败")
  }
}

async function runNow(task) {
  try {
    await dashboardApi.updateTaskStatus(task.id, "运行中")
    task.status = "运行中"
    emit("notify", `已触发立即执行：${task.name}`)
  } catch (e) {
    emit("notify", e.message ?? "触发失败")
  }
}

async function remove(task) {
  if (!window.confirm(`确认删除任务「${task.name}」？`)) return
  try {
    await dashboardApi.deleteTask(task.id)
    emit("notify", "任务已删除")
    await loadTasks()
  } catch (e) {
    emit("notify", e.message ?? "删除失败")
  }
}

function openDetail(task) {
  router.push(`/dashboard/ai-tasks/${task.id}`)
}

function triggerLabel(t) {
  const item = triggerOptions.find((o) => o.id === (t.trigger ?? "cron"))
  return item ? item.label : "周期调度"
}

function triggerColor(t) {
  const item = triggerOptions.find((o) => o.id === (t.trigger ?? "cron"))
  return item ? item.color : "#2b6cb0"
}

function statusColor(s) {
  return ({
    "运行中": "#5db486",
    "已暂停": "#9baead",
    "已停止": "#7a8d89",
    "执行失败": "#c7553b",
  })[s] ?? "#56716e"
}

const has = (perm) => hasPermission(operator.role, perm)

watch(filter, () => {}, { deep: true })

onMounted(() => loadTasks())
</script>

<template>
  <div class="ai-tasks-shell">
    <header class="page-header">
      <div>
        <h2><CalendarDays :size="18" />AI 巡检任务管理</h2>
        <p>编排 AI 自动巡查节奏 —— 周期调度 / 事件触发；启动后将持续 7x24h 无人值守。</p>
      </div>
      <div class="actions">
        <button v-if="has('ai:task:create')" class="primary-btn" @click="openCreate">
          <Plus :size="14" />新建任务
        </button>
      </div>
    </header>

    <section class="trigger-cards">
      <article
        v-for="opt in triggerOptions"
        :key="opt.id"
        class="trigger-card"
        :style="{ borderColor: opt.color }"
        @click="newTask.trigger = opt.id; openCreate()"
      >
        <component :is="opt.icon" :size="20" :style="{ color: opt.color }" />
        <strong>{{ opt.label }}</strong>
        <p>{{ opt.desc }}</p>
      </article>
    </section>

    <section class="filter-bar">
      <div class="filter-row">
        <input v-model="filter.keyword" placeholder="搜索任务名 / 范围" class="filter-input" />
        <select v-model="filter.trigger">
          <option value="">全部触发方式</option>
          <option v-for="opt in triggerOptions" :key="opt.id" :value="opt.id">{{ opt.label }}</option>
        </select>
        <select v-model="filter.status">
          <option value="">全部状态</option>
          <option value="运行中">运行中</option>
          <option value="已暂停">已暂停</option>
          <option value="执行失败">执行失败</option>
        </select>
      </div>
    </section>

    <div v-if="loading" class="loading-row">
      <Loader2 :size="16" class="spin" />加载任务列表…
    </div>
    <div v-else-if="filteredTasks.length === 0" class="empty-row">
      当前条件下没有任务 —— 点击右上角「新建任务」或上方触发卡片开始
    </div>
    <table v-else class="task-table">
      <thead>
        <tr>
          <th>任务名</th>
          <th>触发方式</th>
          <th>执行周期</th>
          <th>状态</th>
          <th>最近执行</th>
          <th>负责人</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="t in filteredTasks" :key="t.id" @click="openDetail(t)">
          <td>
            <strong>{{ t.name }}</strong>
            <small v-if="t.description">{{ t.description }}</small>
          </td>
          <td>
            <span class="tag" :style="{ background: triggerColor(t) + '22', color: triggerColor(t) }">
              {{ triggerLabel(t) }}
            </span>
          </td>
          <td><code>{{ t.cron ?? "—" }}</code></td>
          <td>
            <span class="dot" :style="{ background: statusColor(t.status) }"></span>
            {{ t.status }}
          </td>
          <td>
            <span v-if="t.lastRunAt">{{ t.lastRunAt }}</span>
            <span v-else style="color:#9baead">—</span>
          </td>
          <td>{{ t.owner ?? "—" }}</td>
          <td class="ops" @click.stop>
            <button v-if="has('ai:task:edit')" class="ghost-btn" @click="runNow(t)" title="立即执行">
              <Play :size="13" />
            </button>
            <button v-if="has('ai:task:edit')" class="ghost-btn" @click="toggleStatus(t)" :title="t.status === '运行中' ? '暂停' : '启动'">
              <Pause v-if="t.status === '运行中'" :size="13" />
              <Play v-else :size="13" />
            </button>
            <button v-if="has('ai:task:edit')" class="ghost-btn" @click="openDetail(t)" title="查看详情">
              <Edit :size="13" />
            </button>
            <button v-if="has('ai:task:delete')" class="ghost-btn danger" @click="remove(t)" title="删除">
              <Trash2 :size="13" />
            </button>
          </td>
        </tr>
      </tbody>
    </table>

    <div v-if="createDialog" class="modal-mask" @click.self="createDialog = false">
      <div class="modal-card">
        <h3>新建 AI 巡检任务</h3>
        <label>任务名称<input v-model="newTask.name" placeholder="如：雨后重点灾害点巡查" /></label>
        <label>巡查范围<input v-model="newTask.scope" placeholder="如：龙安镇高风险灾害点" /></label>
        <label>触发方式
          <select v-model="newTask.trigger">
            <option v-for="opt in triggerOptions" :key="opt.id" :value="opt.id">{{ opt.label }}</option>
          </select>
        </label>
        <div v-if="newTask.trigger === 'cron'" class="cron-row">
          <label>执行周期（Cron）<input v-model="newTask.cron" /></label>
          <div class="cron-presets">
            <button v-for="p in cronPresets" :key="p.id" class="preset-btn" @click="newTask.cron = p.id">
              {{ p.label }}
            </button>
          </div>
        </div>
        <label>使用模型
          <select v-model="newTask.modelId">
            <option value="yolov8">YOLOv8（目标检测）</option>
            <option value="lstm">LSTM（形变预测）</option>
            <option value="xgboost">XGBoost（风险评分）</option>
            <option value="ensemble">模型融合（综合研判）</option>
          </select>
        </label>
        <label>备注<textarea v-model="newTask.description" rows="2" placeholder="可选"/></label>
        <label class="checkbox">
          <input type="checkbox" v-model="newTask.enabled" />创建后立即启用
        </label>
        <div class="modal-actions">
          <button class="ghost-btn" @click="createDialog = false">取消</button>
          <button class="primary-btn" :disabled="saving" @click="save">
            <Loader2 v-if="saving" :size="14" class="spin" />创建
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ai-tasks-shell { padding: 24px 28px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 18px; }
.page-header h2 { margin: 0; display: flex; gap: 8px; align-items: center; font-size: 18px; color: #1f3e44; }
.page-header p { margin: 4px 0 0; color: #7a8d89; font-size: 12px; }
.primary-btn { display: flex; gap: 6px; align-items: center; padding: 8px 14px; border: 0; background: #2f7f80; color: #fff; border-radius: 8px; cursor: pointer; font-size: 13px; }
.primary-btn:disabled { opacity: .5; }
.ghost-btn { display: inline-flex; gap: 4px; align-items: center; padding: 6px 10px; border: 1px solid #d9e6e1; background: #fff; border-radius: 6px; color: #37555a; font-size: 12px; cursor: pointer; }
.ghost-btn:hover { background: #f4f7f5; }
.ghost-btn.danger { color: #c7553b; border-color: rgba(199,85,59,.3); }

.trigger-cards { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-bottom: 22px; }
.trigger-card { padding: 18px; border-left: 4px solid; border-top: 1px solid #e9eee9; border-right: 1px solid #e9eee9; border-bottom: 1px solid #e9eee9; border-radius: 10px; background: #fff; cursor: pointer; transition: transform .15s, box-shadow .15s; }
.trigger-card:hover { transform: translateY(-2px); box-shadow: 0 6px 18px rgba(0,0,0,.06); }
.trigger-card strong { display: block; margin: 8px 0 4px; color: #1f3e44; font-size: 14px; }
.trigger-card p { margin: 0; color: #7a8d89; font-size: 12px; line-height: 1.6; }

.filter-bar { background: #fff; border: 1px solid #e9eee9; border-radius: 10px; padding: 14px; margin-bottom: 16px; }
.filter-row { display: flex; gap: 12px; }
.filter-input { flex: 1; padding: 8px 12px; border: 1px solid #d9e6e1; border-radius: 8px; background: #fafdfc; }
.filter-bar select { padding: 8px 12px; border: 1px solid #d9e6e1; border-radius: 8px; background: #fff; }

.loading-row, .empty-row { padding: 32px; text-align: center; color: #7a8d89; font-size: 13px; }

.task-table { width: 100%; border-collapse: collapse; background: #fff; border: 1px solid #e9eee9; border-radius: 10px; overflow: hidden; }
.task-table th, .task-table td { padding: 12px 14px; text-align: left; font-size: 13px; border-bottom: 1px solid #eef3f1; }
.task-table th { background: #fafdfc; color: #56716e; font-weight: 600; }
.task-table tbody tr { cursor: pointer; transition: background .12s; }
.task-table tbody tr:hover { background: #f4f7f5; }
.task-table td strong { display: block; color: #1f3e44; }
.task-table td small { display: block; color: #7a8d89; font-size: 11px; margin-top: 2px; }
.task-table code { background: #f4f7f5; padding: 2px 6px; border-radius: 4px; font-size: 12px; }
.tag { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 11px; font-weight: 600; }
.dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 4px; vertical-align: middle; }
.ops { white-space: nowrap; }
.ops .ghost-btn { margin-right: 4px; }

.modal-mask { position: fixed; inset: 0; background: rgba(15,32,38,.45); display: grid; place-items: center; z-index: 100; }
.modal-card { width: min(480px, 92vw); background: #fff; border-radius: 14px; padding: 24px; }
.modal-card h3 { margin: 0 0 16px; color: #1f3e44; }
.modal-card label { display: block; margin-bottom: 14px; color: #56716e; font-size: 12px; font-weight: 600; }
.modal-card label.checkbox { font-weight: 500; }
.modal-card input, .modal-card textarea, .modal-card select { display: block; width: 100%; margin-top: 6px; padding: 8px 10px; border: 1px solid #d9e6e1; border-radius: 8px; background: #fafdfc; }
.cron-row label { width: 100%; }
.cron-presets { display: flex; gap: 6px; margin-top: 6px; flex-wrap: wrap; }
.preset-btn { padding: 4px 10px; border: 1px dashed #d9e6e1; background: #fafdfc; border-radius: 6px; font-size: 11px; cursor: pointer; color: #56716e; }
.modal-actions { display: flex; gap: 10px; justify-content: flex-end; margin-top: 18px; }

.spin { animation: spin 1s linear infinite; }
@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
</style>
