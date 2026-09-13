<script setup>
/**
 * 应急预案管理（独立页面 /dashboard/plans）。
 *
 * 角色：管理员 / 领域专家
 * 预案库维护 + 启动记录追踪；支持启动模拟以演练响应链路。
 */
import { computed, onMounted, reactive, ref } from "vue"
import {
  ClipboardList,
  Copy,
  Edit,
  FileText,
  GitBranch,
  Loader2,
  Paperclip,
  PlayCircle,
  Plus,
  RefreshCw,
  Trash2,
  UserCheck,
  X,
} from "lucide-vue-next"
import { dashboardApi } from "../services/dashboardApi"
import { getAuthUser, hasPermission } from "../stores/auth"

const props = defineProps({
  searchTerm: { type: String, default: "" },
  userRole: { type: String, default: "admin" },
})
const emit = defineEmits(["notify"])

const operator = getAuthUser() ?? { name: "李晨曦", role: "admin" }
const has = (perm) => hasPermission(operator.role, perm)

const loading = ref(false)
const activeTab = ref("library")
const plans = ref([])
const launches = ref([])
const editDrawer = ref(false)
const editing = ref(null)
const confirmDialog = ref(false)
const pendingLaunch = ref(null)

const samplePlans = [
  {
    id: "plan-001",
    name: "滑坡橙色预警 · 城镇撤离预案",
    hazardType: "滑坡",
    level: "orange",
    version: "v3.2",
    owner: "李晨曦",
    updatedAt: "2026-08-30 16:12",
    enabled: true,
    steps: [
      { name: "监测告警触发", owner: "系统", sla: "即时" },
      { name: "现场复核", owner: "李晨曦", sla: "15 分钟" },
      { name: "启动疏散广播", owner: "王皓", sla: "5 分钟" },
      { name: "人员清点", owner: "社区网格员", sla: "30 分钟" },
      { name: "应急安置", owner: "应急办", sla: "60 分钟" },
    ],
    attachments: ["应急疏散图.pdf", "安置点清单.xlsx", "通讯录 v2.csv"],
  },
  {
    id: "plan-002",
    name: "崩塌红色预警 · 山区抢险预案",
    hazardType: "崩塌",
    level: "red",
    version: "v2.4",
    owner: "王皓",
    updatedAt: "2026-08-22 09:35",
    enabled: true,
    steps: [
      { name: "险情研判", owner: "专家组", sla: "10 分钟" },
      { name: "启动交通管制", owner: "公安交警", sla: "5 分钟" },
      { name: "组织群众撤离", owner: "镇政府", sla: "20 分钟" },
      { name: "救援队伍集结", owner: "消防救援", sla: "30 分钟" },
    ],
    attachments: ["交通管制方案.pdf"],
  },
  {
    id: "plan-003",
    name: "泥石流黄色预警 · 沟谷巡查预案",
    hazardType: "泥石流",
    level: "yellow",
    version: "v1.7",
    owner: "张敏",
    updatedAt: "2026-08-10 11:08",
    enabled: false,
    steps: [
      { name: "加密降雨监测", owner: "气象岗", sla: "即时" },
      { name: "沟谷巡查", owner: "巡查员", sla: "30 分钟" },
      { name: "劝离游客", owner: "景区办", sla: "20 分钟" },
    ],
    attachments: [],
  },
]

const sampleLaunches = [
  {
    id: "launch-001",
    planId: "plan-001",
    planName: "滑坡橙色预警 · 城镇撤离预案",
    triggeredAt: "2026-08-28 10:24",
    currentStep: 2,
    totalSteps: 5,
    progress: 40,
    status: "running",
  },
  {
    id: "launch-002",
    planId: "plan-002",
    planName: "崩塌红色预警 · 山区抢险预案",
    triggeredAt: "2026-08-15 03:11",
    currentStep: 4,
    totalSteps: 4,
    progress: 100,
    status: "success",
  },
  {
    id: "launch-003",
    planId: "plan-003",
    planName: "泥石流黄色预警 · 沟谷巡查预案",
    triggeredAt: "2026-07-20 22:48",
    currentStep: 2,
    totalSteps: 3,
    progress: 66,
    status: "failed",
  },
]

const hazardOptions = ["滑坡", "崩塌", "泥石流", "地面沉降", "地震"]
const levelOptions = [
  { value: "red", label: "红色" },
  { value: "orange", label: "橙色" },
  { value: "yellow", label: "黄色" },
  { value: "blue", label: "蓝色" },
]
const levelColor = {
  red: "#e06060",
  orange: "#e0a050",
  yellow: "#d9c040",
  blue: "#4f8f99",
}

const launchStatusMeta = {
  running: { label: "进行中", color: "#4f8f99" },
  success: { label: "已结束", color: "#6dbfa5" },
  failed: { label: "异常", color: "#e06060" },
}

const kpis = computed(() => {
  const total = plans.value.length
  const enabled = plans.value.filter((p) => p.enabled).length
  const launchesSorted = [...launches.value].sort((a, b) => (a.triggeredAt < b.triggeredAt ? 1 : -1))
  const lastTrigger = launchesSorted[0]?.triggeredAt?.slice(5) ?? "—"
  const launchCount = launches.value.length
  return [
    { label: "预案总数", value: total, color: "#4f8f99", icon: ClipboardList },
    { label: "已启用", value: enabled, color: "#6dbfa5", icon: UserCheck },
    { label: "最近一次启动", value: lastTrigger, color: "#e0a050", icon: PlayCircle },
    { label: "启动次数", value: launchCount, color: "#d9c040", icon: GitBranch },
  ]
})

async function load() {
  loading.value = true
  try {
    const [p, l] = await Promise.all([
      dashboardApi.listPlans().catch(() => ({ records: samplePlans, total: samplePlans.length })),
      dashboardApi.listPlanLaunches().catch(() => ({ records: sampleLaunches, total: sampleLaunches.length })),
    ])
    plans.value = p?.records ?? []
    launches.value = l?.records ?? []
  } catch (e) {
    emit("notify", e.message ?? "加载预案失败")
  } finally {
    loading.value = false
  }
}

function levelLabel(lv) {
  return levelOptions.find((o) => o.value === lv)?.label ?? lv
}
function levelColorOf(lv) {
  return levelColor[lv] ?? "#7a8d89"
}

function openCreate() {
  editing.value = {
    id: null,
    name: "",
    hazardType: "滑坡",
    level: "orange",
    version: "v1.0",
    owner: operator.name,
    steps: [{ name: "监测告警触发", owner: "系统", sla: "即时" }],
    attachments: [],
    enabled: true,
    updatedAt: new Date().toISOString().slice(0, 16).replace("T", " "),
  }
  editDrawer.value = true
}

function openEdit(plan) {
  editing.value = JSON.parse(JSON.stringify(plan))
  editDrawer.value = true
}

function duplicate(plan) {
  editing.value = {
    ...JSON.parse(JSON.stringify(plan)),
    id: null,
    name: `${plan.name} · 副本`,
    version: `${plan.version}-copy`,
    enabled: false,
    updatedAt: new Date().toISOString().slice(0, 16).replace("T", " "),
  }
  editDrawer.value = true
  emit("notify", "已复制预案，可编辑后保存为新预案")
}

async function remove(plan) {
  if (!window.confirm(`确认删除预案「${plan.name}」？`)) return
  try {
    await dashboardApi.deletePlan(plan.id)
    emit("notify", "预案已删除")
    await load()
  } catch (e) {
    emit("notify", e.message ?? "删除失败")
  }
}

function addStep() {
  if (!editing.value) return
  editing.value.steps.push({ name: "", owner: "", sla: "" })
}
function removeStep(idx) {
  if (!editing.value) return
  editing.value.steps.splice(idx, 1)
}
function addAttachment() {
  if (!editing.value) return
  const name = window.prompt("附件名称（含扩展名）")
  if (name && name.trim()) editing.value.attachments.push(name.trim())
}

async function save() {
  if (!editing.value) return
  if (!editing.value.name.trim()) return emit("notify", "请填写预案名称")
  if (!editing.value.steps.length) return emit("notify", "请至少添加一个启动步骤")
  try {
    await dashboardApi.upsertPlan({
      id: editing.value.id,
      name: editing.value.name.trim(),
      hazardType: editing.value.hazardType,
      level: editing.value.level,
      version: editing.value.version,
      owner: editing.value.owner,
      steps: editing.value.steps,
      attachments: editing.value.attachments,
      enabled: editing.value.enabled,
    })
    emit("notify", editing.value.id ? "预案已更新" : "预案已创建")
    editDrawer.value = false
    await load()
  } catch (e) {
    emit("notify", e.message ?? "保存失败")
  }
}

function askLaunch(plan) {
  pendingLaunch.value = plan
  confirmDialog.value = true
}

async function confirmLaunch() {
  const plan = pendingLaunch.value
  if (!plan) return
  try {
    await dashboardApi.simulatePlanLaunch(plan.id)
    emit("notify", `已模拟启动预案「${plan.name}」，推进至第 1 步`)
    confirmDialog.value = false
    pendingLaunch.value = null
    await load()
  } catch (e) {
    emit("notify", e.message ?? "启动模拟失败")
  }
}

onMounted(load)
</script>

<template>
  <div class="plan-shell">
    <header class="page-header">
      <div>
        <h2><ClipboardList :size="18" />应急预案管理</h2>
        <p>维护灾害响应流程、责任人、SLA 与关联附件，支持演练启动以验证链路。</p>
      </div>
      <div class="actions">
        <button class="ghost-btn" @click="load"><RefreshCw :size="14" />刷新</button>
        <button v-if="has('plans:edit')" class="primary-btn" @click="openCreate">
          <Plus :size="14" />新建预案
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
        v-for="t in ['library', 'launches']"
        :key="t"
        :class="{ active: activeTab === t }"
        @click="activeTab = t"
      >
        <component :is="t === 'library' ? FileText : GitBranch" :size="14" />
        {{ t === "library" ? "预案库" : "启动记录" }}
      </button>
    </div>

    <section v-if="activeTab === 'library'" class="panel-card">
      <div v-if="loading" class="loading-row">
        <Loader2 :size="16" class="spin" />加载预案…
      </div>
      <div v-else-if="plans.length === 0" class="empty-row">尚无预案 —— 点击「新建预案」开始</div>
      <div v-else class="table-wrap">
        <table class="task-table">
          <thead>
            <tr>
              <th>名称</th>
              <th>灾种</th>
              <th>等级</th>
              <th>版本</th>
              <th>责任人</th>
              <th>最近修改</th>
              <th>状态</th>
              <th class="ops-col">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="p in plans" :key="p.id" @click="openEdit(p)">
              <td><strong>{{ p.name }}</strong></td>
              <td>{{ p.hazardType }}</td>
              <td>
                <span class="level-pill" :style="{ background: levelColorOf(p.level) + '22', color: levelColorOf(p.level) }">
                  {{ levelLabel(p.level) }}
                </span>
              </td>
              <td><code>{{ p.version }}</code></td>
              <td>{{ p.owner }}</td>
              <td>{{ p.updatedAt }}</td>
              <td>
                <span class="status-dot" :style="{ background: p.enabled ? '#6dbfa5' : '#7a8d89' }"></span>
                {{ p.enabled ? "已启用" : "已停用" }}
              </td>
              <td class="ops" @click.stop>
                <button v-if="has('plans:edit')" class="ghost-btn" @click="openEdit(p)" title="编辑">
                  <Edit :size="13" />
                </button>
                <button v-if="has('plans:edit')" class="ghost-btn" @click="duplicate(p)" title="复制">
                  <Copy :size="13" />
                </button>
                <button v-if="has('plans:launch')" class="ghost-btn primary" @click="askLaunch(p)" title="启动模拟">
                  <PlayCircle :size="13" />
                </button>
                <button v-if="has('plans:edit')" class="ghost-btn danger" @click="remove(p)" title="删除">
                  <Trash2 :size="13" />
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section v-else class="panel-card">
      <div v-if="launches.length === 0" class="empty-row">暂无启动记录</div>
      <div v-else class="table-wrap">
        <table class="task-table">
          <thead>
            <tr>
              <th>预案名</th>
              <th>触发时间</th>
              <th>当前步骤</th>
              <th>进度</th>
              <th>状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="lc in launches" :key="lc.id">
              <td><strong>{{ lc.planName }}</strong></td>
              <td>{{ lc.triggeredAt }}</td>
              <td>第 {{ lc.currentStep }} / {{ lc.totalSteps }} 步</td>
              <td>
                <div class="progress">
                  <div
                    class="fill"
                    :style="{ width: lc.progress + '%', background: launchStatusMeta[lc.status]?.color ?? '#5fbd9a' }"
                  ></div>
                </div>
                <small>{{ lc.progress }}%</small>
              </td>
              <td>
                <span class="status-dot" :style="{ background: launchStatusMeta[lc.status]?.color ?? '#7a8d89' }"></span>
                {{ launchStatusMeta[lc.status]?.label ?? lc.status }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <!-- 编辑抽屉 -->
    <div v-if="editDrawer" class="drawer-mask" @click.self="editDrawer = false">
      <aside class="drawer">
        <header>
          <h3>{{ editing?.id ? "编辑预案" : "新建预案" }}</h3>
          <button class="ghost-btn icon" @click="editDrawer = false"><X :size="14" /></button>
        </header>
        <div v-if="editing" class="drawer-body">
          <div class="form-grid">
            <label class="span-2">预案名称<input v-model="editing.name" placeholder="如：滑坡橙色预警 · 城镇撤离预案" /></label>
            <label>灾种
              <select v-model="editing.hazardType">
                <option v-for="t in hazardOptions" :key="t" :value="t">{{ t }}</option>
              </select>
            </label>
            <label>等级
              <select v-model="editing.level">
                <option v-for="o in levelOptions" :key="o.value" :value="o.value">{{ o.label }}</option>
              </select>
            </label>
            <label>版本<input v-model="editing.version" placeholder="v1.0" /></label>
            <label>责任人<input v-model="editing.owner" placeholder="负责人姓名" /></label>
          </div>

          <section class="step-section">
            <header>
              <h4>启动流程</h4>
              <button v-if="has('plans:edit')" class="ghost-btn" @click="addStep">
                <Plus :size="12" />添加步骤
              </button>
            </header>
            <ol class="step-list">
              <li v-for="(s, i) in editing.steps" :key="i">
                <span class="step-index">{{ i + 1 }}</span>
                <input v-model="s.name" placeholder="步骤名称" class="step-name" />
                <input v-model="s.owner" placeholder="负责人" class="step-owner" />
                <input v-model="s.sla" placeholder="SLA（如 30 分钟）" class="step-sla" />
                <button v-if="has('plans:edit')" class="ghost-btn icon danger" @click="removeStep(i)">
                  <Trash2 :size="12" />
                </button>
              </li>
            </ol>
          </section>

          <section class="attach-section">
            <header>
              <h4>关联附件</h4>
              <button v-if="has('plans:edit')" class="ghost-btn" @click="addAttachment">
                <Plus :size="12" />添加附件
              </button>
            </header>
            <div v-if="!editing.attachments.length" class="empty-card">尚无附件</div>
            <ul v-else class="attach-list">
              <li v-for="(a, i) in editing.attachments" :key="i">
                <Paperclip :size="13" color="#56716e" />
                <span>{{ a }}</span>
                <button v-if="has('plans:edit')" class="ghost-btn icon" @click="editing.attachments.splice(i, 1)">
                  <X :size="12" />
                </button>
              </li>
            </ul>
          </section>

          <label class="switch-row">
            <input type="checkbox" v-model="editing.enabled" />
            <span>启用此预案</span>
          </label>

          <div class="drawer-actions">
            <button class="ghost-btn" @click="editDrawer = false">取消</button>
            <button class="primary-btn" @click="save">保存</button>
          </div>
        </div>
      </aside>
    </div>

    <!-- 启动模拟确认 -->
    <div v-if="confirmDialog" class="modal-mask" @click.self="confirmDialog = false">
      <div class="modal-card">
        <h3>启动模拟</h3>
        <p class="confirm-text">
          将为预案「<strong>{{ pendingLaunch?.name }}</strong>」启动一次模拟演练，
          自动推进到第 1 步。该操作仅用于测试链路，不会真实下发任务。
        </p>
        <div class="modal-actions">
          <button class="ghost-btn" @click="confirmDialog = false">取消</button>
          <button class="primary-btn" @click="confirmLaunch">确认启动</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.plan-shell { padding: 24px 28px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 18px; gap: 12px; flex-wrap: wrap; }
.page-header h2 { margin: 0; display: flex; gap: 8px; align-items: center; font-size: 18px; color: #1f3e44; }
.page-header p { margin: 4px 0 0; color: #7a8d89; font-size: 12px; }
.actions { display: flex; gap: 8px; }

.primary-btn { display: inline-flex; gap: 6px; align-items: center; padding: 8px 14px; border: 0; background: #2f7f80; color: #fff; border-radius: 8px; font-size: 13px; cursor: pointer; }
.ghost-btn { display: inline-flex; gap: 4px; align-items: center; padding: 6px 10px; border: 1px solid #d9e6e1; background: #fff; border-radius: 6px; color: #37555a; font-size: 12px; cursor: pointer; }
.ghost-btn:hover { background: #f4f7f5; }
.ghost-btn.icon { padding: 6px; }
.ghost-btn.danger { color: #c7553b; border-color: rgba(199,85,59,.3); }
.ghost-btn.primary { color: #2f7f80; border-color: #cfe1d9; }

.kpi-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-bottom: 18px; }
.kpi-card { background: #fff; border: 1px solid #e9eee9; border-left: 4px solid; border-radius: 10px; padding: 14px 16px; display: flex; flex-direction: column; gap: 4px; }
.kpi-card strong { font-size: 22px; color: #1f3e44; }
.kpi-card span { color: #56716e; font-size: 12px; }

.tab-bar { display: flex; gap: 4px; background: #f4f7f5; padding: 4px; border-radius: 8px; margin-bottom: 14px; width: max-content; }
.tab-bar button { display: inline-flex; gap: 6px; align-items: center; padding: 8px 16px; border: 0; background: transparent; border-radius: 6px; cursor: pointer; font-size: 13px; color: #56716e; }
.tab-bar button.active { background: #fff; color: #1f3e44; box-shadow: 0 1px 4px rgba(0,0,0,.06); }

.panel-card { background: #fff; border: 1px solid #e9eee9; border-radius: 10px; padding: 16px; }
.table-wrap { overflow-x: auto; }
.task-table { width: 100%; border-collapse: collapse; min-width: 720px; }
.task-table th, .task-table td { padding: 12px 14px; text-align: left; font-size: 13px; border-bottom: 1px solid #eef3f1; }
.task-table th { background: #fafdfc; color: #56716e; font-weight: 600; }
.task-table tbody tr { cursor: pointer; transition: background .12s; }
.task-table tbody tr:hover { background: #f4f7f5; }
.task-table td strong { color: #1f3e44; }
.task-table code { background: #f4f7f5; padding: 2px 6px; border-radius: 4px; font-size: 12px; }
.ops-col { white-space: nowrap; }
.ops .ghost-btn { margin-right: 4px; }

.level-pill { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 11px; font-weight: 600; }
.status-dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 6px; vertical-align: middle; }

.progress { height: 6px; background: #f4f7f5; border-radius: 3px; overflow: hidden; margin-bottom: 4px; }
.progress .fill { height: 100%; background: #5fbd9a; border-radius: 3px; transition: width .3s; }

.loading-row, .empty-row { padding: 32px; text-align: center; color: #7a8d89; font-size: 13px; }

.drawer-mask { position: fixed; inset: 0; background: rgba(15,32,38,.35); display: flex; justify-content: flex-end; z-index: 110; }
.drawer { width: min(560px, 100%); background: #fff; height: 100%; display: flex; flex-direction: column; box-shadow: -8px 0 24px rgba(0,0,0,.08); }
.drawer > header { display: flex; align-items: center; justify-content: space-between; padding: 18px 22px; border-bottom: 1px solid #eef3f1; }
.drawer > header h3 { margin: 0; font-size: 15px; color: #1f3e44; }
.drawer-body { padding: 22px; overflow-y: auto; flex: 1; }

.form-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 14px; margin-bottom: 18px; }
.form-grid .span-2 { grid-column: 1 / -1; }
.form-grid label { display: flex; flex-direction: column; gap: 6px; color: #56716e; font-size: 12px; font-weight: 600; }
.form-grid input, .form-grid select { padding: 8px 10px; border: 1px solid #d9e6e1; border-radius: 8px; background: #fafdfc; font-size: 13px; }

.step-section, .attach-section { margin-bottom: 18px; }
.step-section > header, .attach-section > header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.step-section h4, .attach-section h4 { margin: 0; font-size: 13px; color: #1f3e44; }

.step-list { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 8px; }
.step-list li { display: grid; grid-template-columns: 28px 1.6fr 1fr 1fr 28px; gap: 8px; align-items: center; padding: 8px; background: #fafdfc; border: 1px solid #e9eee9; border-radius: 8px; }
.step-index { width: 24px; height: 24px; border-radius: 50%; background: #2f7f80; color: #fff; display: grid; place-items: center; font-size: 11px; font-weight: 600; }
.step-list input { padding: 6px 8px; border: 1px solid #d9e6e1; border-radius: 6px; font-size: 12px; background: #fff; }

.empty-card { padding: 16px; border: 1px dashed #d9e6e1; border-radius: 8px; text-align: center; color: #7a8d89; font-size: 12px; }
.attach-list { list-style: none; padding: 0; margin: 0; display: grid; grid-template-columns: repeat(2, 1fr); gap: 8px; }
.attach-list li { display: flex; gap: 6px; align-items: center; padding: 8px 10px; border: 1px solid #e9eee9; border-radius: 8px; background: #fff; font-size: 12px; color: #1f3e44; }
.attach-list span { flex: 1; }

.switch-row { display: flex; gap: 8px; align-items: center; margin-bottom: 18px; font-size: 13px; color: #37555a; font-weight: 500; }
.switch-row input { width: 14px; height: 14px; accent-color: #2f7f80; }

.drawer-actions { display: flex; gap: 10px; justify-content: flex-end; padding-top: 16px; border-top: 1px solid #eef3f1; }

.modal-mask { position: fixed; inset: 0; background: rgba(15,32,38,.45); display: grid; place-items: center; z-index: 120; padding: 20px; }
.modal-card { width: min(420px, 100%); background: #fff; border-radius: 14px; padding: 22px; }
.modal-card h3 { margin: 0 0 12px; color: #1f3e44; font-size: 15px; }
.confirm-text { color: #56716e; font-size: 13px; line-height: 1.7; margin: 0 0 18px; }
.modal-actions { display: flex; gap: 10px; justify-content: flex-end; }

.spin { animation: spin 1s linear infinite; }
@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }

@media (max-width: 880px) {
  .kpi-grid { grid-template-columns: repeat(2, 1fr); }
  .form-grid { grid-template-columns: 1fr; }
  .step-list li { grid-template-columns: 28px 1fr 1fr 28px; }
  .step-sla { grid-column: 1 / -1; }
}
@media (max-width: 540px) {
  .plan-shell { padding: 16px; }
  .actions { width: 100%; }
  .actions button { flex: 1; justify-content: center; }
}
</style>
