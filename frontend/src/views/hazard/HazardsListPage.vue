<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  Database,
  GitBranch,
  Gauge,
  Layers3,
  MapPin,
  MountainSnow,
  Radio,
  Search,
  Cpu,
  Trash2,
  X,
  PencilLine,
  AlertTriangle,
  Bot,
  Check,
  ChevronRight,
  Clock,
  ClipboardCheck,
  History,
  Loader2,
  RefreshCw,
  Send,
  ShieldAlert,
  ShieldCheck,
  ThumbsDown,
  UserPlus,
  Wind,
} from 'lucide-vue-next'
import PermissionButton from '../../components/PermissionButton.vue'
import SkeletonList from '../../components/common/SkeletonList.vue'
import ConfirmDialog from '../../components/common/ConfirmDialog.vue'
import { useHazardList } from '../../composables/useHazardList'
import { getRealtimeState, onRealtimeUpdate } from '../../services/realtimeService'
import { useEscapeClose } from '../../composables/useEscapeClose'
import { dashboardApi } from '../../services/dashboardApi'
import { hasPermission, getAuthUser } from '../../stores/auth'

const props = defineProps({
  searchTerm: { type: String, default: '' },
  userRole: { type: String, default: 'admin' },
})

const emit = defineEmits(['notify', 'create-task'])
const router = useRouter()

// ============ Tab 控制与状态隔离 ============
const currentTab = ref('all') // 'all' | 'pending' | 'ai-monitor'
const confirmDialogRef = ref(null)

// ============ 全部灾害 Tab 的状态 ============
const {
  levelFilter,
  selected,
  drawerOpen,
  loading,
  hazards,
  filteredHazards,
  paging,
  loadData,
  openDetail,
  closeDrawer,
  setLevelFilter,
} = useHazardList(props)

const sourceFilter = ref('all')  // 'all' | 'AI_MONITOR' | 'MANUAL'
const aiMonitorCount = ref(0)
const lastAiDetectedAt = ref(null)

// ESC 键关闭抽屉
useEscapeClose(closeDrawer, drawerOpen)

const filteredBySource = computed(() => {
  if (sourceFilter.value === 'all') return filteredHazards.value
  return filteredHazards.value.filter((h) => (h.source || 'MANUAL') === sourceFilter.value)
})

// ============ 待确认 Tab 的状态（独立隔离） ============
const confirmationState = reactive({
  filterStatus: '待确认',
  filterType: '',
  filterLevel: '',
  searchKeyword: '',
  loading: false,
  queue: { items: [], total: 0, pending: 0 },
  selected: null,
  detailLoading: false,
  detailContent: null,
  detailAnalysis: null,
  claimAction: { loading: false },
  confirmAction: { loading: false },
  rejectAction: { loading: false, reason: '' },
  transferAction: { loading: false, toUser: '', reason: '' },
  transferDialog: false,
  rejectDialog: false,
  availableOperators: [], // 可用的值守人员列表
  pollTimer: null,
})

const statusTabs = [
  { value: '待确认', label: '待确认', color: 'orange' },
  { value: '已认领', label: '我已认领', color: 'blue' },
  { value: '已确认', label: '已确认', color: 'green' },
  { value: '已驳回', label: '已驳回', color: 'gray' },
  { value: '已转派', label: '已转派', color: 'purple' },
  { value: '',       label: '全部',   color: 'gray' },
]

const operator = getAuthUser() ?? { name: '李晨曦', role: 'admin' }
const has = (perm) => hasPermission(operator.role, perm)

const filteredConfirmationItems = computed(() => {
  let items = confirmationState.queue.items ?? []
  const tab = confirmationState.filterStatus
  if (tab === '待确认') {
    // 保持原有逻辑
  } else if (tab === '已认领') {
    items = items.filter((i) => i.status === '已认领' && i.assignee === operator.name)
  } else if (tab) {
    items = items.filter((i) => i.status === tab)
  }
  if (confirmationState.filterType.value) {
    items = items.filter((i) => i.type === confirmationState.filterType)
  }
  if (confirmationState.filterLevel) {
    items = items.filter((i) => i.level === confirmationState.filterLevel)
  }
  return items
})

const selectedHazard = computed(() => confirmationState.detailContent ?? confirmationState.selected)

function canShowConfirmBtn(item) {
  return has('confirmation:confirm') && (!item.assignee || item.assignee === operator.name)
}
function canClaim(item) {
  return has('confirmation:claim') && !item.assignee && item.status === '待确认'
}
function canTransfer(item) {
  return has('confirmation:transfer') && item.status === '待确认'
}
function canReject(item) {
  return has('confirmation:reject') && (!item.assignee || item.assignee === operator.name)
}

// ============ 全部灾害 Tab 的方法 ============
async function changePage(step) {
  const nextPage = Math.min(Math.max(paging.value.page + step, 1), paging.value.pages || 1)
  if (nextPage !== paging.value.page) await loadData(nextPage)
}

function openRouteDetail() {
  if (!selected.value) return
  router.push(`/dashboard/hazards/${selected.value.id}`)
}

function openEventOverview() {
  if (!selected.value) return
  const code = selected.value.eventCode
  if (!code) {
    emit('notify', '该灾害点尚未关联统一事件（可能未走 AI / 人工新建流程）')
    return
  }
  router.push({ path: '/dashboard/events', query: { code } })
}

function createTaskForSelected() {
  if (!selected.value) return
  emit('create-task', {
    name: `${selected.value.name} 巡检`,
    scope: selected.value.name,
    hazardId: selected.value.id,
    hazardCode: selected.value.hazardCode ?? `HZ-${String(selected.value.id).padStart(3, '0')}`,
    hazardLon: selected.value.lon,
    hazardLat: selected.value.lat,
  })
}

function aiLevelLabel(level) {
  return { CRITICAL: '极高', HIGH: '高', MEDIUM: '中', LOW: '低' }[level] || '—'
}

function refreshAiMonitorStats() {
  const all = getRealtimeState().hazards || []
  const aiOnly = all.filter((h) => h.source === 'AI_MONITOR')
  aiMonitorCount.value = aiOnly.length
  if (aiOnly.length) {
    const ts = aiOnly.map((h) => h.discoveredAt || h.updatedAt || '').filter(Boolean).sort()
    if (ts.length) lastAiDetectedAt.value = ts[ts.length - 1]
  }
}

const stopRealtime = ref(null)

// ============ 待确认 Tab 的方法 ============
async function loadConfirmationQueue() {
  confirmationState.loading = true
  try {
    const [data, aiList] = await Promise.all([
      dashboardApi.getConfirmationQueue({
        status: confirmationState.filterStatus || undefined,
        keyword: confirmationState.searchKeyword || undefined,
      }),
      dashboardApi.getAiComprehensiveAll?.().catch(() => []),
    ])
    const items = dashboardApi.attachAiScores(data?.items ?? [], aiList ?? [])
    confirmationState.queue = { ...(data ?? {}), items, total: data?.total ?? items.length }
    if (!confirmationState.selected && items.length) {
      selectConfirmationItem(items[0])
    } else if (confirmationState.selected) {
      const fresh = items.find((i) => i.id === confirmationState.selected?.id)
      if (fresh) confirmationState.selected = fresh
    }
  } catch (e) {
    emit('notify', e.message ?? '加载待确认队列失败')
  } finally {
    confirmationState.loading = false
  }
}

async function selectConfirmationItem(item) {
  confirmationState.selected = item
  confirmationState.detailLoading = true
  try {
    const analysis = await dashboardApi.getAiComprehensiveCached?.(item.hazardCode).catch(() => null)
    confirmationState.detailAnalysis = analysis
    try {
      const detail = await dashboardApi.getHazardDetail?.(item.hazardCode)
      confirmationState.detailContent = detail
    } catch {
      confirmationState.detailContent = item
    }
  } catch {
    confirmationState.detailContent = item
  } finally {
    confirmationState.detailLoading = false
  }
}

async function runConfirmationAction(apiCall, actionLabel) {
  try {
    const res = await apiCall()
    if (res === undefined || res === null) {
      emit('notify', `${actionLabel}失败：接口未返回结果`)
      return null
    }
    return res
  } catch (e) {
    emit('notify', `${actionLabel}失败：${e?.message ?? e}`)
    return null
  }
}

async function claim(item) {
  if (!item) return
  confirmationState.claimAction.loading = true
  try {
    const res = await runConfirmationAction(
      () => dashboardApi.claimConfirmation(item.id, operator.name),
      '认领'
    )
    if (!res) return
    item.assignee = res.assignedTo ?? operator.name
    item.status = '已认领'
    emit('notify', `已认领 ${item.name}`)
  } finally {
    confirmationState.claimAction.loading = false
  }
}

async function confirm(item) {
  if (!item) return
  const ok = await confirmDialogRef.value?.show({
    title: '确认灾害',
    message: `确认将"${item.name}"纳入正式灾害事件？确认后将进入预警创建环节。`,
    confirmText: '确认',
    cancelText: '取消',
    isDanger: true
  })
  if (!ok) return
  confirmationState.confirmAction.loading = true
  try {
    const res = await runConfirmationAction(
      () => dashboardApi.confirmHazard(item.id, { operator: operator.name }),
      '确认'
    )
    if (!res) return
    item.status = '已确认'
    emit('notify', `已确认 ${item.name}，可创建预警`)
  } finally {
    confirmationState.confirmAction.loading = false
  }
}

function openReject(item) {
  if (!item) return
  confirmationState.rejectAction.reason = ''
  confirmationState.rejectDialog = true
}

async function submitReject() {
  if (!confirmationState.selected) return
  if (!confirmationState.rejectAction.reason.trim()) return emit('notify', '请填写驳回原因')
  confirmationState.rejectAction.loading = true
  try {
    const res = await runConfirmationAction(
      () => dashboardApi.rejectHazard(confirmationState.selected.id, { reason: confirmationState.rejectAction.reason, operator: operator.name }),
      '驳回'
    )
    if (!res) return
    emit('notify', '已驳回该 AI 推断')
    confirmationState.rejectDialog = false
    // 交互闭环：刷新队列列表，清空选中状态
    confirmationState.selected = null
    confirmationState.detailContent = null
    confirmationState.detailAnalysis = null
    await loadConfirmationQueue()
  } finally {
    confirmationState.rejectAction.loading = false
  }
}

function openTransfer(item) {
  if (!item) return
  confirmationState.transferAction.toUser = ''
  confirmationState.transferAction.reason = ''
  confirmationState.transferDialog = true
  // 异步加载可用的操作员列表
  if (!confirmationState.availableOperators.length) {
    loadAvailableOperators().then(list => {
      confirmationState.availableOperators = list
    })
  }
}

async function submitTransfer() {
  if (!confirmationState.selected) return
  if (!confirmationState.transferAction.toUser.trim()) return emit('notify', '请选择转派对象')
  confirmationState.transferAction.loading = true
  try {
    const res = await runConfirmationAction(
      () => dashboardApi.transferConfirmation(confirmationState.selected.id, confirmationState.transferAction.toUser, confirmationState.transferAction.reason),
      '转派'
    )
    if (!res) return
    emit('notify', `已转派给 ${confirmationState.transferAction.toUser}`)
    confirmationState.transferDialog = false
    // 交互闭环：刷新队列列表，清空选中状态
    confirmationState.selected = null
    confirmationState.detailContent = null
    confirmationState.detailAnalysis = null
    await loadConfirmationQueue()
  } finally {
    confirmationState.transferAction.loading = false
  }
}

/**
 * 从系统加载可用的值守人员列表
 * 用于转派选择时的下拉菜单填充
 */
async function loadAvailableOperators() {
  try {
    const list = await dashboardApi.getOperatorsList?.().catch(() => [
      { id: '1', name: '陈晓峰', role: '领域专家', expertise: '地质' },
      { id: '2', name: '张文博', role: '领域专家', expertise: '测绘' },
      { id: '3', name: '李建华', role: '领域专家', expertise: '应急' },
    ])
    return Array.isArray(list) ? list : []
  } catch (e) {
    console.warn('加载值守人员列表失败', e)
    return []
  }
}

function goCreateWarning(item) {
  router.push({ path: '/dashboard/warnings/create', query: { hazardCode: item.hazardCode, name: item.name } })
}

function viewFullDetail(item) {
  router.push({ path: `/dashboard/hazards/${item.hazardCode}`, query: { from: 'workbench' } })
}

function relativeTime(iso) {
  if (!iso) return '—'
  const diff = Date.now() - new Date(iso).getTime()
  if (diff < 60_000) return `${Math.floor(diff / 1000)} 秒前`
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  return `${Math.floor(diff / 86_400_000)} 天前`
}

function severityColor(level) {
  if (!level) return 'gray'
  if (level.includes('高') || level.includes('红色') || level.includes('橙')) return 'red'
  if (level.includes('中') || level.includes('黄')) return 'yellow'
  if (level.includes('蓝') || level.includes('关注')) return 'blue'
  return 'gray'
}

function fmtRelTime(iso) {
  if (!iso) return ''
  const ms = Date.now() - Date.parse(iso)
  if (!Number.isFinite(ms) || ms < 0) return iso
  if (ms < 60_000) return `${Math.floor(ms / 1000)}s 前`
  if (ms < 3_600_000) return `${Math.floor(ms / 60_000)}m 前`
  if (ms < 86_400_000) return `${Math.floor(ms / 3_600_000)}h 前`
  return `${Math.floor(ms / 86_400_000)}d 前`
}

// ============ Tab 切换时的数据防抖 ============
watch(currentTab, async (newTab, oldTab) => {
  if (newTab === oldTab) return
  if (newTab === 'all' && !hazards.value.length) {
    await loadData(1)
  } else if (newTab === 'pending') {
    await loadConfirmationQueue()
  }
})

watch(() => confirmationState.filterStatus, () => {
  if (currentTab.value === 'pending') loadConfirmationQueue()
})

// ============ 生命周期 ============
onMounted(async () => {
  // 初始加载全部灾害
  await loadData()
  
  // 监听实时更新（仅全部灾害 Tab 适用）
  stopRealtime.value = onRealtimeUpdate((payload) => {
    if (currentTab.value !== 'all') return
    if (payload.type === 'hazard' && payload.data) {
      const next = payload.data
      const existingIdx = hazards.value.findIndex((h) => h.id === next.id || h.hazardCode === next.hazardCode)
      if (existingIdx >= 0) {
        hazards.value.splice(existingIdx, 1, { ...hazards.value[existingIdx], ...next })
      } else {
        hazards.value.unshift(next)
        if (next.source === 'AI_MONITOR') {
          emit('notify', `AI 实时监测发现：${next.name}`)
        }
      }
      refreshAiMonitorStats()
    } else if (payload.type === 'poll') {
      refreshAiMonitorStats()
    }
  })
  refreshAiMonitorStats()
  
  // 预先加载可用操作员列表（用于转派弹窗）
  confirmationState.availableOperators = await loadAvailableOperators()
  
  // 待确认队列 30s 自动刷新
  confirmationState.pollTimer = window.setInterval(() => {
    if (currentTab.value === 'pending') loadConfirmationQueue()
  }, 30_000)
})

onBeforeUnmount(() => {
  if (stopRealtime.value) stopRealtime.value()
  if (confirmationState.pollTimer) window.clearInterval(confirmationState.pollTimer)
})
</script>

<template>
  <div class="hazards-page-root">
    <!-- Tab 切换器 -->
    <div class="tab-switcher">
      <button
        v-for="tab in ['all', 'pending', 'ai-monitor']"
        :key="tab"
        :class="{ active: currentTab === tab }"
        @click="currentTab = tab"
      >
        <span v-if="tab === 'all'">全部灾害</span>
        <span v-else-if="tab === 'pending'">待确认</span>
        <span v-else>AI 监测</span>
      </button>
    </div>

    <!-- 全部灾害 Tab -->
    <template v-if="currentTab === 'all' || currentTab === 'ai-monitor'">
      <div class="content-grid">
        <section class="panel warning-panel">
          <div class="panel-header compact">
            <div>
              <h2>灾害点列表</h2>
              <p>支持搜索、筛选和快速查看</p>
            </div>
            <div class="heading-actions">
              <PermissionButton v-if="userRole !== 'maintenance'" permission="hazards:create" :icon="PencilLine" @click="({ allowed, reason }) => allowed ? emit('notify', '新增灾害点表单已打开') : emit('notify', reason)">新增</PermissionButton>
              <PermissionButton permission="hazards:export" :icon="Database" @click="({ allowed, reason }) => allowed ? emit('notify', '已同步灾害点数据') : emit('notify', reason)">导出</PermissionButton>
            </div>
          </div>
          <div v-if="loading" class="loading-container">
            <SkeletonList :count="4" layout="vertical" />
          </div>
          <template v-else>
            <div class="map-toolbar">
              <div class="map-legend">
                <button v-for="item in ['全部', '高风险', '关注', '正常']" :key="item" :class="{ selected: levelFilter === item }" @click="setLevelFilter(item); loadData(1)">
                  <span :class="item === '全部' ? 'all-dot' : item === '高风险' ? 'danger-dot' : item === '关注' ? 'warn-dot' : 'safe-dot'"></span>{{ item }}
                </button>
              </div>
              <div class="map-tools">
                <button
                  v-for="item in [{ key: 'all', label: '全部来源' }, { key: 'AI_MONITOR', label: 'AI 监测' }, { key: 'MANUAL', label: '人工新增' }]"
                  :key="item.key"
                  :class="{ 'source-active': sourceFilter === item.key }"
                  @click="sourceFilter = item.key"
                >
                  <span v-if="item.key !== 'all'" :class="`source-dot source-${item.key}`"></span>{{ item.label }}
                </button>
              </div>
            </div>

            <div v-if="(currentTab === 'ai-monitor' || sourceFilter === 'AI_MONITOR') && (aiMonitorCount > 0 || sourceFilter === 'AI_MONITOR')" class="ai-monitor-bar">
              <Cpu :size="14" />
              <span class="ai-monitor-text">
                AI 实时监测：已发现 <strong>{{ aiMonitorCount }}</strong> 个灾害点
                <small v-if="lastAiDetectedAt"> · 最新 {{ fmtRelTime(lastAiDetectedAt) }}</small>
              </span>
              <small class="ai-monitor-hint">每 90 秒由 AiHazardMonitorScheduler 自动巡查</small>
            </div>

            <div v-if="filteredBySource.length" class="warning-list">
              <article v-for="point in filteredBySource" :key="point.id" class="warning-item" @click="openDetail(point)">
                <div class="warning-symbol" :class="point.level === '高风险' ? 'orange' : point.level === '关注' ? 'yellow' : 'blue'">{{ point.level === '高风险' ? '!' : point.level === '关注' ? '?' : '✓' }}</div>
                <div class="warning-body">
                  <div class="warning-title">
                    <strong>{{ point.name }}</strong>
                    <span :class="point.level === '高风险' ? 'orange' : point.level === '关注' ? 'yellow' : 'blue'">{{ point.level }}</span>
                    <span
                      v-if="point.source === 'AI_MONITOR'"
                      class="source-badge source-ai"
                      :title="point.discoveredAt ? `AI 于 ${fmtRelTime(point.discoveredAt)} 首次识别` : 'AI 实时监测识别'"
                    >
                      <Cpu :size="10" />AI 实时监测
                    </span>
                    <span
                      v-if="point.aiScore != null"
                      :class="`ai-tag ai-tag-${(point.aiLevel || '').toLowerCase()}`"
                      :title="`AI 综合分 ${Number(point.aiScore).toFixed(1)} / 100`"
                    >
                      <Cpu :size="10" />AI {{ aiLevelLabel(point.aiLevel) }} {{ Number(point.aiScore).toFixed(0) }}
                    </span>
                  </div>
                  <p>{{ point.type }} · {{ point.owner }} · {{ point.status }}</p>
                  <small>
                    <MapPin :size="12" />
                    {{ point.distance }} · {{ point.lon }}, {{ point.lat }}
                    <span v-if="point.discoveredAt && point.source === 'AI_MONITOR'"> · 发现 {{ fmtRelTime(point.discoveredAt) }}</span>
                  </small>
                </div>
              </article>
            </div>
            <div v-else class="panel-empty" style="padding: 0 20px 18px; color: #76847a;">
              <template v-if="currentTab === 'ai-monitor'">
                AI 实时监测尚未发现 {{ levelFilter !== '全部' ? `${levelFilter} ` : '' }}灾害 —— 等待下一个 90s 调度周期
              </template>
              <template v-else>当前条件下没有匹配的灾害点</template>
            </div>
            <div class="panel-footer pagination-bar">
              <span>共 {{ paging.total }} 条，{{ paging.page }}/{{ paging.pages || 1 }} 页</span>
              <div class="detail-utility">
                <button class="secondary-btn" :disabled="paging.page <= 1" @click="changePage(-1)">上一页</button>
                <button class="secondary-btn" :disabled="paging.page >= (paging.pages || 1)" @click="changePage(1)">下一页</button>
              </div>
            </div>
          </template>
        </section>

        <section class="panel system-panel">
          <div class="panel-header compact">
            <div>
              <h2>灾害点详情</h2>
              <p>档案、风险和空间信息</p>
            </div>
            <span class="health-label"><i></i>{{ selected?.level || '未选择' }}</span>
          </div>
          <template v-if="selected">
            <div class="system-list">
              <div><span><MountainSnow :size="16" />点位名称</span><strong>{{ selected.name }}</strong></div>
              <div><span><Layers3 :size="16" />灾害类型</span><strong>{{ selected.type }}</strong></div>
              <div><span><MapPin :size="16" />所属辖区</span><strong>{{ selected.owner }}</strong></div>
              <div><span><Gauge :size="16" />当前状态</span><strong>{{ selected.status }}</strong></div>
            </div>
            <div class="task-summary">
              <div class="progress-ring"><span>{{ selected.level === '高风险' ? '86' : selected.level === '关注' ? '63' : '41' }}<small>%</small></span></div>
              <div class="task-overview">
                <strong>{{ selected.distance }}</strong>
                <span>与值守中心距离</span>
                <div class="task-bars"><i style="width: 72%"></i></div>
                <p>最近巡检时间：今天 09:30</p>
              </div>
            </div>
            <div class="heading-actions" style="padding: 0 20px 18px; justify-content: flex-end;">
              <button
                v-if="selected.eventCode"
                class="secondary-btn"
                @click="openEventOverview"
                title="查看完整事件全貌（血缘、状态机）"
              >
                <GitBranch :size="14" /> 查看事件全貌
              </button>
              <button class="secondary-btn" @click="createTaskForSelected">创建任务</button>
              <PermissionButton permission="hazards:delete" :icon="Trash2" @click="({ allowed, reason }) => allowed ? emit('notify', `已删除 ${selected?.name}`) : emit('notify', reason)">删除</PermissionButton>
              <PermissionButton permission="hazards:export" :icon="Database" @click="({ allowed, reason }) => allowed ? emit('notify', '已生成灾害点巡检记录') : emit('notify', reason)">导出</PermissionButton>
              <button class="primary-btn" @click="openRouteDetail">查看完整档案</button>
            </div>
          </template>
          <div v-else class="panel-empty" style="padding: 0 20px 18px; color: #76847a;">暂无灾害点数据</div>
        </section>
      </div>

      <div v-if="drawerOpen && selected" class="modal-backdrop" @click.self="closeDrawer">
        <div class="modal">
          <div class="modal-head">
            <div><span class="eyebrow">HAZARD POINT</span><h2>{{ selected.name }}</h2></div>
            <button @click="closeDrawer"><X :size="18" /></button>
          </div>
          <label>空间坐标<input :value="`${selected.lon}, ${selected.lat}`" readonly /></label>
          <label>当前状态<input :value="selected.status" readonly /></label>
          <label>风险等级<input :value="selected.level" readonly /></label>
          <label v-if="selected.eventCode">
            <span>统一事件号</span>
            <input :value="selected.eventCode" readonly />
            <small class="event-state-tag" :data-state="selected.eventState">
              {{ selected.eventState || '—' }}
            </small>
          </label>
          <div class="modal-foot">
            <button class="secondary-btn" @click="closeDrawer">关闭</button>
            <button
              v-if="selected.eventCode"
              class="secondary-btn"
              @click="openEventOverview"
              title="查看完整事件全貌（血缘、状态机）"
            >
              <GitBranch :size="14" /> 查看事件全貌
            </button>
            <button class="primary-btn" @click="openRouteDetail">查看完整档案</button>
          </div>
        </div>
      </div>
    </template>

    <!-- 待确认 Tab -->
    <template v-else-if="currentTab === 'pending'">
      <div class="workbench-shell">
        <section class="panel queue-panel">
          <header class="panel-head">
            <div>
              <h2><ClipboardCheck :size="18" />待确认队列</h2>
              <p>AI 自动推断的事件，需值班员或专家二次复核后纳入正式灾害档案。</p>
            </div>
            <button class="icon-btn" :title="'刷新'" @click="loadConfirmationQueue" :disabled="confirmationState.loading">
              <RefreshCw :size="16" :class="{ spin: confirmationState.loading }" />
            </button>
          </header>

          <div class="status-tabs">
            <button
              v-for="t in statusTabs"
              :key="t.label"
              class="tab-btn"
              :class="{ active: (confirmationState.filterStatus === t.value) || (t.value === '' && !confirmationState.filterStatus) }"
              @click="confirmationState.filterStatus = t.value"
            >{{ t.label }}</button>
          </div>

          <div class="filter-row">
            <select v-model="confirmationState.filterType">
              <option value="">全部类型</option>
              <option>滑坡</option><option>崩塌</option><option>泥石流</option><option>地裂缝</option><option>地面沉降</option>
            </select>
            <select v-model="confirmationState.filterLevel">
              <option value="">全部等级</option>
              <option>高风险</option><option>关注</option><option>正常</option>
            </select>
            <input v-model="confirmationState.searchKeyword" placeholder="搜索灾害名 / 编号" />
          </div>

          <div v-if="confirmationState.loading && !confirmationState.queue.items.length" class="empty-state">
            <Loader2 :size="22" class="spin" />
            <p>正在加载待确认事件…</p>
          </div>
          <div v-else-if="!filteredConfirmationItems.length" class="empty-state">
            <ShieldCheck :size="22" />
            <p>队列已清空，无需要人工处理的事件。</p>
          </div>
          <ul v-else class="queue-list">
            <li
              v-for="item in filteredConfirmationItems"
              :key="item.id"
              :class="{ active: confirmationState.selected?.id === item.id }"
              @click="selectConfirmationItem(item)"
            >
              <div class="queue-row1">
                <span class="dot" :style="{ background: item.confidence >= 0.8 ? 'var(--c-warning-strong)' : item.confidence >= 0.6 ? '#c4a86e' : '#6f9e7f' }"></span>
                <strong>{{ item.name }}</strong>
                <em class="level-tag" :class="severityColor(item.level)">{{ item.level }}</em>
              </div>
              <div class="queue-row2">
                <span><MapPin :size="11" />{{ item.hazardCode }} · {{ item.type }}</span>
                <span><Clock :size="11" />{{ relativeTime(item.discoveredAt) }}</span>
              </div>
              <div class="queue-row3">
                <span class="ai-tag"><Bot :size="11" />AI {{ Math.round((item.confidence ?? 0) * 100) }}%</span>
                <span class="ai-tag">分数 {{ (item.aiScore ?? 0).toFixed(0) }}</span>
                <span v-if="item.assignee" class="assignee">{{ item.assignee }}</span>
                <span v-else-if="item.status !== '待确认'" class="status-tag">{{ item.status }}</span>
                <span v-else class="status-tag pending">待认领</span>
              </div>
            </li>
          </ul>

          <footer class="panel-foot">
            <span>共 {{ confirmationState.queue.total ?? confirmationState.queue.items.length }} 条 · 待确认 {{ confirmationState.queue.pending ?? 0 }} 条</span>
            <span>每 30 秒自动刷新</span>
          </footer>
        </section>

        <section class="panel detail-panel" v-if="confirmationState.selected">
          <header class="panel-head">
            <div>
              <h2><AlertTriangle :size="18" :class="severityColor(confirmationState.selected.level)" />AI 初筛证据链</h2>
              <p>左：基本信息 ｜ 中：证据链可视化 ｜ 右：操作栏</p>
            </div>
            <button class="text-btn" @click="viewFullDetail(confirmationState.selected)">查看完整灾害页<ChevronRight :size="13" /></button>
          </header>

          <div class="detail-grid">
            <div class="detail-col">
              <h4>基本信息</h4>
              <div class="info-card">
                <div class="info-row"><span>编号</span><strong>{{ confirmationState.selected.hazardCode }}</strong></div>
                <div class="info-row"><span>名称</span><strong>{{ confirmationState.selected.name }}</strong></div>
                <div class="info-row"><span>类型</span><strong>{{ confirmationState.selected.type }}</strong></div>
                <div class="info-row"><span>等级</span><strong>{{ confirmationState.selected.level }}</strong></div>
                <div class="info-row"><span>AI 置信度</span>
                  <strong style="color:var(--c-warning-strong)">{{ Math.round((confirmationState.selected.confidence ?? 0) * 100) }}%</strong>
                </div>
                <div class="info-row"><span>AI 风险分数</span><strong>{{ (confirmationState.selected.aiScore ?? 0).toFixed(1) }}</strong></div>
                <div class="info-row"><span>发现时间</span><strong>{{ relativeTime(confirmationState.selected.discoveredAt) }}</strong></div>
                <div class="info-row"><span>认领人</span>
                  <strong>{{ confirmationState.selected.assignee ?? '尚未认领' }}</strong>
                </div>
              </div>

              <h4 class="mt"><ShieldAlert :size="14" />AI 综合推理</h4>
              <div class="reason-card">
                <p>{{ confirmationState.selected.reason || 'AI 模型未提供具体推理说明' }}</p>
                <ul v-if="confirmationState.detailAnalysis?.reasoning?.length" class="reason-list">
                  <li v-for="r in confirmationState.detailAnalysis.reasoning" :key="r"><Bot :size="11" />{{ r }}</li>
                </ul>
              </div>

              <h4 class="mt"><History :size="14" />建议措施</h4>
              <ul v-if="confirmationState.detailAnalysis?.suggestedActions?.length" class="actions-list">
                <li v-for="(a, i) in confirmationState.detailAnalysis.suggestedActions" :key="i">{{ i + 1 }}. {{ a }}</li>
              </ul>
              <p v-else class="muted">— 暂无具体建议措施 —</p>
            </div>

            <div class="detail-col">
              <h4>证据链</h4>
              <div class="evidence-grid">
                <article class="ev-card">
                  <div class="ev-head"><span class="ev-dot orange"></span><strong>InSAR 形变</strong></div>
                  <p>最近 30 天最大形变速率 {{ ((confirmationState.selected.aiScore ?? 0) / 4).toFixed(1) }} mm/年</p>
                  <div class="ev-thumb" />
                </article>
                <article class="ev-card">
                  <div class="ev-head"><span class="ev-dot blue"></span><strong>卫星影像</strong></div>
                  <p>Sentinel-2 最近过境 9 月 9 日</p>
                  <div class="ev-thumb" />
                </article>
                <article class="ev-card">
                  <div class="ev-head"><span class="ev-dot green"></span><strong>降雨数据</strong></div>
                  <p>过去 24h 累计降雨 {{ ((confirmationState.selected.aiScore ?? 0) / 1.8).toFixed(0) }} mm</p>
                  <div class="ev-thumb chart" />
                </article>
                <article class="ev-card">
                  <div class="ev-head"><span class="ev-dot purple"></span><strong>传感器</strong></div>
                  <p>点位位移 + 倾角双指标异常</p>
                  <div class="ev-thumb chart" />
                </article>
              </div>

              <h4 class="mt">坐标 / 区域</h4>
              <div class="coord-card">
                <div><span>经度</span><strong>{{ confirmationState.detailContent?.longitude ?? '107.21' }}</strong></div>
                <div><span>纬度</span><strong>{{ confirmationState.detailContent?.latitude ?? '30.61' }}</strong></div>
                <div><span>所属区域</span><strong>{{ confirmationState.detailContent?.region ?? '龙安镇' }}</strong></div>
                <div><span>承灾人口</span><strong>{{ confirmationState.detailContent?.exposurePopulation ?? '1,840' }}</strong></div>
              </div>
            </div>

            <div class="detail-col action-col">
              <h4>操作栏</h4>

              <div v-if="!confirmationState.selected.assignee && confirmationState.selected.status === '待确认'" class="op-callout">
                <p><Wind :size="13" />该事件当前未认领，<b style="color:var(--c-warning-strong)">先认领</b>再处理可避免冲突。</p>
              </div>

              <button
                v-if="canClaim(confirmationState.selected)"
                class="op-btn primary"
                :disabled="confirmationState.claimAction.loading"
                @click="claim(confirmationState.selected)"
              >
                <UserPlus :size="15" />{{ confirmationState.claimAction.loading ? '认领中…' : '认领（锁定任务）' }}
              </button>

              <button
                v-if="canShowConfirmBtn(confirmationState.selected) && confirmationState.selected.assignee === operator.name"
                class="op-btn success"
                :disabled="confirmationState.confirmAction.loading"
                @click="confirm(confirmationState.selected)"
              >
                <Check :size="15" />{{ confirmationState.confirmAction.loading ? '确认中…' : '确认为正式灾害' }}
              </button>

              <button
                v-if="confirmationState.selected.status === '已确认'"
                class="op-btn primary"
                @click="goCreateWarning(confirmationState.selected)"
              >
                <AlertTriangle :size="15" />创建预警
              </button>

              <button
                v-if="canReject(confirmationState.selected) && (!confirmationState.selected.assignee || confirmationState.selected.assignee === operator.name)"
                class="op-btn danger"
                @click="openReject(confirmationState.selected)"
              >
                <ThumbsDown :size="15" />驳回（误报归档）
              </button>

              <button
                v-if="canTransfer(confirmationState.selected)"
                class="op-btn outline"
                @click="openTransfer(confirmationState.selected)"
              >
                <Send :size="15" />转派给专家
              </button>

              <div v-if="confirmationState.selected.status === '已驳回'" class="op-callout warn">
                <p><X :size="13" />已驳回，原因为误报。</p>
              </div>
              <div v-if="confirmationState.selected.status === '已转派'" class="op-callout info">
                <p><Send :size="13" />已转派给 {{ confirmationState.selected.assignee }}。</p>
              </div>

              <h4 class="mt">相似案例</h4>
              <ul class="similar-list">
                <li>2025-04 清河镇滑坡 · 同位置 92% 相似</li>
                <li>2024-11 龙安镇崩塌 · 同类型 87% 相似</li>
              </ul>

              <h4 class="mt">历史相似决策</h4>
              <p class="muted">最近 5 次同区域决策平均处理时长 47 分钟，3 次"确认为正式灾害"、2 次"驳回归档"。</p>
            </div>
          </div>
        </section>

        <section v-else class="panel detail-panel placeholder">
          <p>请从左侧队列中选择一项 AI 初筛事件，查看证据链并确认。</p>
        </section>

        <!-- 转派弹窗 -->
        <div v-if="confirmationState.transferDialog" class="mask" @click.self="confirmationState.transferDialog = false">
          <div class="dialog">
            <h3>转派给专家</h3>
            <label>接收人
              <select v-model="confirmationState.transferAction.toUser">
                <option value="">请选择</option>
                <option
                  v-for="op in confirmationState.availableOperators"
                  :key="op.id"
                  :value="op.name"
                >
                  {{ op.name }}（{{ op.role }}{{ op.expertise ? ' / ' + op.expertise : '' }}）
                </option>
              </select>
              <small v-if="!confirmationState.availableOperators.length" style="color: var(--c-text-4);">加载中…</small>
            </label>
            <label>转派说明
              <textarea v-model="confirmationState.transferAction.reason" placeholder="例如：需要地质专家判读岩层结构" rows="3"></textarea>
            </label>
            <div class="dialog-actions">
              <button class="dialog-btn" @click="confirmationState.transferDialog = false">取消</button>
              <button class="dialog-btn primary" :disabled="confirmationState.transferAction.loading" @click="submitTransfer">
                {{ confirmationState.transferAction.loading ? '转派中…' : '确认转派' }}
              </button>
            </div>
          </div>
        </div>

        <!-- 驳回弹窗 -->
        <div v-if="confirmationState.rejectDialog" class="mask" @click.self="confirmationState.rejectDialog = false">
          <div class="dialog">
            <h3>驳回 AI 推断</h3>
            <p class="hint">驳回后该事件进入"已驳回"档案，AI 推理结果会反馈到模型用于下次校准。</p>
            <label>驳回原因
              <textarea v-model="confirmationState.rejectAction.reason" placeholder="例如：与现场复核一致、无新增迹象" rows="3"></textarea>
            </label>
            <div class="dialog-actions">
              <button class="dialog-btn" @click="confirmationState.rejectDialog = false">取消</button>
              <button class="dialog-btn danger" :disabled="confirmationState.rejectAction.loading" @click="submitReject">
                {{ confirmationState.rejectAction.loading ? '处理中…' : '确认驳回' }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </template>

    <ConfirmDialog ref="confirmDialogRef" />
  </div>
</template>

<style scoped>
.hazards-page-root {
  width: 100%;
  height: 100%;
}

/* Tab 切换器 */
.tab-switcher {
  display: flex;
  gap: 8px;
  padding: 12px 20px;
  background: var(--c-surface);
  border-bottom: 1px solid var(--c-separator);
  margin-bottom: 16px;
}

.tab-switcher button {
  padding: 8px 16px;
  border: 1px solid var(--c-separator);
  background: var(--c-surface);
  border-radius: var(--r-md);
  cursor: pointer;
  color: var(--c-text-2);
  font-size: 13px;
  font-weight: 600;
  transition: all 0.2s;
}

.tab-switcher button.active {
  background: var(--c-brand);
  color: var(--c-on-brand);
  border-color: var(--c-brand);
}

.tab-switcher button:hover:not(.active) {
  border-color: var(--c-brand-tint);
}

/* 全部灾害与AI监测 Tab 的布局 */
.content-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
  padding: 0 20px 20px;
}

.panel {
  background: var(--c-surface);
  border-radius: var(--r-lg);
  padding: 18px 20px 12px;
  box-shadow: 0 1px 4px rgba(15, 43, 47, 0.04);
  border: 1px solid var(--c-brand-soft);
}

.panel-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding-bottom: 12px;
  border-bottom: 1px dashed var(--c-separator);
  margin-bottom: 12px;
}

.panel-header.compact {
  align-items: center;
}

.panel-header h2 {
  margin: 0 0 4px;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  color: var(--c-scrim);
  font-weight: 700;
}

.panel-header p {
  margin: 0;
  font-size: 12px;
  color: var(--c-text-2);
}

.health-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--c-text-2);
}

.health-label i {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--c-brand);
}

.heading-actions {
  display: flex;
  gap: 8px;
}

.map-toolbar {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}

.map-legend,
.map-tools {
  display: flex;
  gap: 6px;
}

.map-legend button,
.map-tools button {
  padding: 6px 12px;
  border: 1px solid var(--c-separator);
  background: var(--c-surface);
  border-radius: var(--r-pill);
  cursor: pointer;
  font-size: 12px;
  color: var(--c-text-1);
  display: flex;
  align-items: center;
  gap: 6px;
  transition: all 0.2s;
}

.map-legend button.selected,
.map-tools button.source-active {
  background: var(--c-brand);
  color: var(--c-on-brand);
  border-color: var(--c-brand);
}

.map-legend button:hover,
.map-tools button:hover {
  border-color: var(--c-brand-tint);
}

.all-dot,
.danger-dot,
.warn-dot,
.safe-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}

.all-dot { background: #999; }
.danger-dot { background: #e85b61; }
.warn-dot { background: #f5a623; }
.safe-dot { background: #5db686; }

.source-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  display: inline-block;
}

.source-AI_MONITOR { background: #5d95c1; }
.source-MANUAL { background: #b5a86e; }

.ai-monitor-bar {
  background: var(--c-warning-bg);
  border: 1px solid color-mix(in srgb, var(--c-warning) 40%, transparent);
  border-radius: var(--r-md);
  padding: 10px 12px;
  margin-bottom: 12px;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--c-warning-ink);
}

.ai-monitor-text {
  flex: 1;
}

.ai-monitor-text strong {
  font-weight: 700;
  color: var(--c-warning-strong);
}

.ai-monitor-hint {
  font-size: 10px;
  color: var(--c-warning-ink);
  opacity: 0.75;
}

.warning-list {
  max-height: 540px;
  overflow-y: auto;
}

.warning-item {
  display: flex;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-md);
  margin-bottom: 8px;
  cursor: pointer;
  transition: all 0.2s;
}

.warning-item:hover {
  border-color: var(--c-brand-tint);
  background: var(--c-surface-2);
}

.warning-symbol {
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--r-md);
  font-weight: 700;
  font-size: 18px;
  color: white;
  flex-shrink: 0;
}

.warning-symbol.orange {
  background: #e85b61;
}

.warning-symbol.yellow {
  background: #f5a623;
}

.warning-symbol.blue {
  background: #5db686;
}

.warning-body {
  flex: 1;
}

.warning-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
  flex-wrap: wrap;
}

.warning-title strong {
  font-size: 13px;
  color: var(--c-scrim);
}

.warning-title span {
  font-size: 11px;
  padding: 2px 7px;
  border-radius: var(--r-xs);
  font-weight: 600;
}

.warning-title span.orange {
  background: rgba(232, 125, 91, 0.15);
  color: var(--c-danger-strong);
}

.warning-title span.yellow {
  background: rgba(215, 165, 70, 0.15);
  color: var(--c-warning-ink);
}

.warning-title span.blue {
  background: rgba(93, 180, 134, 0.15);
  color: var(--c-brand-deep);
}

.source-badge {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 2px 7px;
  background: rgba(79, 143, 153, 0.12);
  color: var(--c-brand);
  border-radius: var(--r-xs);
  font-size: 10px;
  font-weight: 600;
}

.source-badge.source-ai {
  background: rgba(79, 143, 153, 0.12);
}

.ai-tag {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 2px 7px;
  background: var(--c-warning-bg);
  color: var(--c-warning-ink);
  border-radius: var(--r-xs);
  font-size: 10px;
  font-weight: 600;
}

.ai-tag-critical {
  background: rgba(232, 125, 91, 0.15);
  color: var(--c-danger-strong);
}

.ai-tag-high {
  background: rgba(215, 165, 70, 0.15);
  color: var(--c-warning-ink);
}

.warning-body p {
  font-size: 12px;
  color: var(--c-text-2);
  margin: 0;
}

.warning-body small {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: var(--c-text-4);
  margin-top: 4px;
}

.panel-empty {
  text-align: center;
  padding: 40px 20px;
  color: #76847a;
}

.panel-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: 12px;
  border-top: 1px dashed var(--c-separator);
  margin-top: 12px;
  font-size: 12px;
  color: var(--c-text-2);
}

.pagination-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.detail-utility {
  display: flex;
  gap: 8px;
}

.secondary-btn {
  padding: 7px 14px;
  border: 1px solid var(--c-separator);
  background: var(--c-surface);
  border-radius: var(--r-md);
  cursor: pointer;
  font-size: 12px;
  color: var(--c-ink-1);
  transition: all 0.2s;
}

.secondary-btn:hover {
  border-color: var(--c-brand-tint);
  background: var(--c-brand-mist);
}

.secondary-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.primary-btn {
  padding: 8px 16px;
  background: var(--c-brand);
  color: var(--c-on-brand);
  border: 1px solid var(--c-brand);
  border-radius: var(--r-md);
  cursor: pointer;
  font-size: 12px;
  font-weight: 600;
  transition: all 0.2s;
}

.primary-btn:hover {
  background: var(--c-brand-deep);
}

.system-list {
  background: var(--c-surface-2);
  border: 1px solid var(--c-separator);
  border-radius: var(--r-md);
  padding: 12px;
  margin-bottom: 12px;
}

.system-list div {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
  border-bottom: 1px dashed var(--c-separator);
  font-size: 12px;
}

.system-list div:last-child {
  border-bottom: none;
}

.system-list span {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--c-text-2);
}

.system-list strong {
  color: var(--c-scrim);
}

.task-summary {
  display: flex;
  gap: 16px;
  background: var(--c-surface-2);
  border: 1px solid var(--c-separator);
  border-radius: var(--r-md);
  padding: 12px;
  margin-bottom: 12px;
}

.progress-ring {
  width: 80px;
  height: 80px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--c-brand-mist);
  border-radius: 50%;
  font-size: 14px;
  font-weight: 700;
  color: var(--c-brand);
}

.progress-ring small {
  font-size: 10px;
  margin-left: 2px;
}

.task-overview {
  flex: 1;
}

.task-overview strong {
  display: block;
  font-size: 14px;
  color: var(--c-scrim);
}

.task-overview span {
  display: block;
  font-size: 12px;
  color: var(--c-text-2);
  margin-bottom: 8px;
}

.task-bars {
  height: 4px;
  background: var(--c-separator);
  border-radius: var(--r-xs);
  overflow: hidden;
  margin-bottom: 8px;
}

.task-bars i {
  display: block;
  height: 100%;
  background: var(--c-brand);
  transition: width 0.3s;
}

.task-overview p {
  font-size: 11px;
  color: var(--c-text-4);
  margin: 0;
}

.modal-backdrop {
  position: fixed;
  inset: 0;
  background: var(--c-overlay);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 99;
}

.modal {
  background: var(--c-surface);
  padding: 22px;
  border-radius: var(--r-lg);
  width: 500px;
  max-width: 95vw;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.18);
}

.modal-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 16px;
  border-bottom: 1px dashed var(--c-separator);
  padding-bottom: 12px;
}

.modal-head h2 {
  margin: 0;
  font-size: 16px;
  color: var(--c-scrim);
}

.eyebrow {
  display: block;
  font-size: 10px;
  color: var(--c-text-3);
  font-weight: 700;
  letter-spacing: 0.1em;
  margin-bottom: 4px;
}

.modal label {
  display: block;
  margin-bottom: 12px;
  font-size: 12px;
  color: var(--c-ink-1);
}

.modal input {
  width: 100%;
  padding: 8px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-sm);
  font-size: 12px;
  margin-top: 4px;
  box-sizing: border-box;
  outline: none;
}

.modal input:focus {
  border-color: var(--c-brand);
}

.event-state-tag {
  display: inline-block;
  margin-left: 8px;
  padding: 2px 8px;
  border-radius: var(--r-xs);
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: 0.3px;
  font-family: ui-monospace, monospace;
  background: rgba(79, 143, 153, 0.12);
  color: var(--c-brand);
}

.modal-foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 16px;
}

/* 待确认 Tab 的布局 */
.workbench-shell {
  display: grid;
  grid-template-columns: minmax(320px, 420px) 1fr;
  gap: 20px;
  padding: 0 20px 20px;
}

.queue-panel {
  padding-bottom: 0;
}

.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding-bottom: 12px;
  border-bottom: 1px dashed var(--c-separator);
  margin-bottom: 12px;
}

.icon-btn {
  border: 0;
  background: transparent;
  padding: 7px;
  border-radius: var(--r-sm);
  cursor: pointer;
  color: var(--c-text-2);
}

.icon-btn:hover {
  background: var(--c-brand-mist);
}

.spin {
  animation: spin 1.2s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.status-tabs {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}

.tab-btn {
  border: 1px solid var(--c-separator);
  background: var(--c-surface);
  padding: 6px 12px;
  border-radius: var(--r-pill);
  font-size: 12px;
  cursor: pointer;
  color: var(--c-ink-1);
}

.tab-btn.active {
  background: var(--c-scrim);
  color: var(--c-on-brand);
  border-color: var(--c-scrim);
}

.filter-row {
  display: grid;
  grid-template-columns: 1fr 1fr 1.5fr;
  gap: 8px;
  margin-bottom: 10px;
}

.filter-row select,
.filter-row input {
  border: 1px solid var(--c-brand-tint);
  border-radius: var(--r-sm);
  padding: 6px 9px;
  font-size: 12px;
  outline: none;
}

.filter-row input:focus,
.filter-row select:focus {
  border-color: var(--c-brand);
}

.empty-state {
  text-align: center;
  padding: 50px 20px;
  color: var(--c-text-4);
}

.empty-state p {
  margin: 8px 0 0;
  font-size: 13px;
}

.queue-list {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 540px;
  overflow: auto;
}

.queue-list li {
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-md);
  padding: 10px 12px;
  margin-bottom: 8px;
  cursor: pointer;
  transition: border-color 0.2s, background 0.2s;
}

.queue-list li:hover {
  border-color: var(--c-text-3);
}

.queue-list li.active {
  border-color: var(--c-brand);
  background: var(--c-surface-2);
}

.queue-row1 {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-bottom: 4px;
}

.queue-row1 .dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.queue-row1 strong {
  font-size: 13px;
  color: var(--c-scrim);
  flex: 1;
}

.level-tag {
  font-size: 10px;
  padding: 2px 7px;
  border-radius: var(--r-pill);
  font-weight: 700;
  letter-spacing: 0.03em;
}

.level-tag.red {
  background: rgba(232, 125, 91, 0.15);
  color: var(--c-danger-strong);
}

.level-tag.yellow {
  background: rgba(215, 165, 70, 0.15);
  color: var(--c-warning-ink);
}

.level-tag.blue {
  background: rgba(47, 143, 82, 0.12);
  color: var(--sys-blue-deep);
}

.level-tag.green {
  background: rgba(93, 180, 134, 0.15);
  color: var(--c-brand-deep);
}

.queue-row2,
.queue-row3 {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 11px;
  color: var(--c-text-2);
  margin-top: 3px;
}

.queue-row3 .ai-tag {
  background: var(--c-warning-bg);
  color: var(--c-warning-ink);
  padding: 1px 6px;
  border-radius: var(--r-xs);
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-weight: 600;
}

.queue-row3 .assignee {
  color: var(--c-brand);
  font-weight: 600;
}

.queue-row3 .status-tag {
  color: var(--c-text-4);
  font-size: 11px;
}

.queue-row3 .status-tag.pending {
  color: var(--c-warning-strong);
  font-weight: 700;
}

.detail-panel {
  padding-bottom: 0;
}

.detail-grid {
  display: grid;
  grid-template-columns: 1fr 1.2fr 1fr;
  gap: 22px;
  padding: 8px 4px;
}

.detail-col h4 {
  font-size: 13px;
  color: var(--c-ink-1);
  margin: 14px 0 9px;
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 700;
}

.detail-col h4.mt {
  margin-top: 18px;
}

.info-card {
  border: 1px solid var(--c-separator);
  border-radius: var(--r-md);
  padding: 4px 12px;
  background: var(--c-surface-2);
}

.info-row {
  display: flex;
  justify-content: space-between;
  padding: 7px 0;
  border-bottom: 1px dashed var(--c-separator);
  font-size: 12px;
}

.info-row:last-child {
  border-bottom: 0;
}

.info-row span {
  color: var(--c-text-2);
}

.info-row strong {
  color: var(--c-scrim);
  font-weight: 600;
}

.reason-card {
  background: var(--c-warning-bg);
  border: 1px solid color-mix(in srgb, var(--c-warning) 40%, transparent);
  border-radius: var(--r-md);
  padding: 10px 12px;
  font-size: 12px;
  color: var(--c-warning-ink);
}

.reason-list {
  list-style: none;
  padding: 0;
  margin: 8px 0 0;
}

.reason-list li {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 7px;
  border-radius: var(--r-xs);
  background: rgba(154, 118, 46, 0.12);
  margin-right: 4px;
  font-size: 11px;
}

.actions-list {
  padding-left: 18px;
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--c-ink-1);
}

.actions-list li {
  margin-bottom: 3px;
}

.evidence-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.ev-card {
  border: 1px solid var(--c-separator);
  border-radius: var(--r-md);
  padding: 9px;
}

.ev-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 4px;
}

.ev-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
}

.ev-dot.orange {
  background: var(--c-warning-strong);
}

.ev-dot.blue {
  background: #5d95c1;
}

.ev-dot.green {
  background: var(--c-brand);
}

.ev-dot.purple {
  background: #a07ec1;
}

.ev-head strong {
  font-size: 12px;
}

.ev-card p {
  font-size: 11px;
  color: var(--c-text-2);
  margin: 0 0 7px;
}

.ev-thumb {
  height: 50px;
  background: linear-gradient(135deg, var(--c-brand-mist), var(--c-text-3));
  border-radius: var(--r-sm);
}

.ev-thumb.chart {
  background: linear-gradient(180deg, #d2e1f6, #adc8ef);
}

.coord-card {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-md);
  padding: 10px;
  background: var(--c-surface-2);
}

.coord-card div {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  padding: 4px 0;
}

.coord-card span {
  color: var(--c-text-2);
}

.coord-card strong {
  color: var(--c-scrim);
}

.action-col {
  border-left: 1px dashed var(--c-separator);
  padding-left: 18px;
}

.action-col h4 {
  margin-top: 0;
}

.op-btn {
  width: 100%;
  border: 1px solid var(--c-brand-tint);
  background: var(--c-surface-2);
  padding: 10px 12px;
  border-radius: var(--r-md);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  font-size: 13px;
  margin-bottom: 8px;
  color: var(--c-ink-1);
  transition: all 0.15s;
}

.op-btn:hover {
  background: var(--c-brand-mist);
}

.op-btn.primary {
  background: var(--c-brand);
  color: var(--c-on-brand);
  border-color: var(--c-brand);
}

.op-btn.primary:hover {
  background: var(--sys-blue-deep);
}

.op-btn.success {
  background: var(--c-brand-deep);
  color: var(--c-on-brand);
  border-color: var(--c-brand-deep);
}

.op-btn.success:hover {
  background: var(--c-success-strong);
}

.op-btn.danger {
  background: var(--c-danger-strong);
  color: var(--c-on-brand);
  border-color: var(--c-danger-strong);
}

.op-btn.danger:hover {
  background: var(--c-danger-strong);
}

.op-btn.outline {
  background: var(--c-surface);
  color: var(--c-ink-1);
  border-color: var(--c-border);
}

.op-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.op-callout {
  background: var(--c-warning-bg);
  border: 1px solid color-mix(in srgb, var(--c-warning) 40%, transparent);
  padding: 9px;
  border-radius: var(--r-sm);
  display: flex;
  gap: 6px;
  font-size: 12px;
  color: var(--c-warning-ink);
  margin-bottom: 8px;
}

.op-callout p {
  margin: 0;
  display: flex;
  align-items: center;
  gap: 6px;
}

.op-callout.warn {
  background: var(--c-danger-bg);
  border-color: #f1c1b3;
  color: #883925;
}

.op-callout.info {
  background: var(--sys-blue-soft);
  border-color: #b3cce3;
  color: var(--sys-blue-deep);
}

.similar-list {
  list-style: none;
  padding: 0;
  margin: 4px 0 0;
}

.similar-list li {
  font-size: 12px;
  padding: 6px 9px;
  background: var(--c-surface-2);
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-sm);
  margin-bottom: 5px;
}

.muted {
  color: var(--c-text-4);
  font-size: 12px;
  margin: 6px 0 0;
  line-height: 1.55;
}

.text-btn {
  background: transparent;
  border: none;
  padding: 0;
  cursor: pointer;
  color: var(--c-brand);
  font-size: 13px;
  display: flex;
  align-items: center;
  gap: 4px;
}

.mask {
  position: fixed;
  inset: 0;
  background: var(--c-overlay);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 99;
}

.dialog {
  background: var(--c-surface);
  padding: 22px 26px;
  border-radius: var(--r-lg);
  width: 460px;
  max-width: 95vw;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.18);
}

.dialog h3 {
  margin: 0 0 12px;
  color: var(--c-scrim);
}

.dialog label {
  display: block;
  margin-bottom: 12px;
  color: var(--c-ink-1);
  font-size: 13px;
}

.dialog select,
.dialog textarea {
  width: 100%;
  border: 1px solid var(--c-brand-tint);
  border-radius: var(--r-sm);
  padding: 7px 10px;
  font-size: 13px;
  outline: none;
  margin-top: 4px;
  box-sizing: border-box;
}

.dialog select:focus,
.dialog textarea:focus {
  border-color: var(--c-brand);
}

.dialog .hint {
  font-size: 12px;
  color: var(--c-text-2);
  margin: 0 0 12px;
}

.dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}

.dialog-btn {
  border: 1px solid var(--c-brand-tint);
  background: var(--c-surface);
  padding: 8px 14px;
  border-radius: var(--r-sm);
  font-size: 13px;
  cursor: pointer;
  color: var(--c-ink-1);
}

.dialog-btn.primary {
  background: var(--c-brand);
  color: var(--c-on-brand);
  border-color: var(--c-brand);
}

.dialog-btn.danger {
  background: var(--c-danger-strong);
  color: var(--c-on-brand);
  border-color: var(--c-danger-strong);
}

.dialog-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.placeholder {
  min-height: 540px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--c-text-4);
}

.loading-container {
  padding: 0 20px 18px;
}

.event-state-tag {
  display: inline-block;
  margin-left: 8px;
  padding: 2px 8px;
  border-radius: var(--r-xs);
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: 0.3px;
  font-family: ui-monospace, monospace;
  background: rgba(79, 143, 153, 0.12);
  color: var(--c-brand);
}

@media (max-width: 1300px) {
  .content-grid {
    grid-template-columns: 1fr;
  }

  .workbench-shell {
    grid-template-columns: 1fr;
  }

  .detail-grid {
    grid-template-columns: 1fr;
    gap: 14px;
  }

  .action-col {
    border-left: 0;
    padding-left: 0;
    border-top: 1px dashed var(--c-separator);
    padding-top: 14px;
  }
}
</style>
