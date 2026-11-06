<script setup>
/**
 * 预警管理：列表按"AI 综合评分 + 等级 + 触发时间"三键排序。
 *   - 每条预警会显示一个 sparkles 小角标，标识它的综合风险分数
 *   - AI 综合分析模块跑出 source=AI_AUTO 的预警事件会被自动关联 AI 评分
 *   - v1.7.0+：「活跃 / 已解除归档」双 Tab；关闭操作走原因弹窗；归档项展示关闭原因与时间
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AlertTriangle, Archive, ArchiveRestore, ArrowRight, Bell, Check, Download, FileText, Layers3, MapPin, MessageSquareText, ShieldCheck, UsersRound, Send, CheckCircle2, RotateCcw, ShieldAlert, Inbox, Zap } from 'lucide-vue-next'
import PermissionButton from '../../components/PermissionButton.vue'
import SkeletonList from '../../components/common/SkeletonList.vue'
import WarningCreateModal from '../../components/WarningCreateModal.vue'
import { dashboardApi } from '../../services/dashboardApi'
import { useEscapeClose } from '../../composables/useEscapeClose'

const emit = defineEmits(['notify'])
const props = defineProps({ userRole: { type: String, default: 'admin' } })
const router = useRouter()
const route = useRoute()
const warnings = ref([])
const selectedWarningId = ref(null)
const showAll = ref(false)
const loading = ref(true)
const paging = ref({ page: 1, size: 10, total: 0, pages: 0 })

/** Tab 状态：'active' 当前活跃 / 'archived' 已解除归档 */
const statusTab = ref('active')

/** 预警创建弹窗显示状态 */
const showCreateModal = ref(false)
const modalRef = ref(null)

/** 关闭/解除原因弹窗 */
const closeDialog = ref({
  open: false,
  warningId: null,
  warningTitle: '',
  action: 'close', // 'close' | 'dismiss' | 'resolve'
  reason: '',
  error: '',
  saving: false,
})

const ARCHIVED_STATUSES = ['已关闭', '已忽略', '已解除']
const isArchived = (w) => w && ARCHIVED_STATUSES.includes(w.status)
const isActive = (w) => w && !isArchived(w)

/**
 * 活跃预警 = 仍需处置的（待确认 / 已确认 / 已派发 / 处理中 / 误报待复核等）。
 * 已解除归档 = 已关闭 / 已忽略 / 已解除 三类。
 */
const activeWarnings = computed(() => warnings.value.filter(isActive))
const archivedWarnings = computed(() => warnings.value.filter(isArchived))
const visibleWarnings = computed(() => {
  const pool = statusTab.value === 'archived' ? archivedWarnings.value : activeWarnings.value
  return showAll.value ? pool : pool.slice(0, 3)
})
const currentList = computed(() =>
  statusTab.value === 'archived' ? archivedWarnings.value : activeWarnings.value,
)
const selectedWarning = computed(
  () => warnings.value.find((item) => item.id === selectedWarningId.value) ?? activeWarnings.value[0] ?? archivedWarnings.value[0] ?? null,
)
const escalationHint = computed(() => {
  const level = selectedWarning.value?.level ?? ''
  if (/橙|红/.test(level)) return '橙色及以上：派发后自动启动应急响应，联动应急值守组'
  if (/黄/.test(level)) return '黄色：加密巡查，根据事态按需启动应急响应'
  if (/蓝/.test(level)) return '蓝色：常规处置，持续关注变化趋势'
  return '按预警等级采取对应处置策略'
})

/* ===================== 视觉语义工具 ===================== */

/**
 * 预警等级 → 语义色调。
 * 与详情页「处置时间轴」的圆点配色同源，保证两页看到同一条预警时颜色一致。
 * 注意：文字一律用 --color-foreground，色调只落在图形（图标/底色）上——
 * 莫兰迪色板在浅底上的文字对比度普遍低于 4.5:1，只适合做非文本图形。
 *
 * @param {string} level 预警等级文案，如「橙色预警」
 * @returns {'destructive'|'warning'|'caution'|'info'|'neutral'}
 */
function levelTone(level) {
  const s = String(level ?? '')
  if (s.includes('红')) return 'destructive'
  if (s.includes('橙')) return 'warning'
  if (s.includes('黄')) return 'caution'
  if (s.includes('蓝')) return 'info'
  return 'neutral'
}

/** 列表项色调：优先按等级文字判定，其次回落到后端下发的 cssClass */
function toneOf(warning) {
  const tone = levelTone(warning?.level)
  if (tone !== 'neutral') return tone
  const cls = String(warning?.cssClass ?? warning?.class ?? '')
  if (cls.includes('red')) return 'destructive'
  if (cls.includes('orange')) return 'warning'
  if (cls.includes('yellow')) return 'caution'
  if (cls.includes('blue')) return 'info'
  return 'neutral'
}

/** 当前选中预警关联的 AI 综合评分（右侧面板用） */
const aiInfo = computed(() => getAiForHazard(selectedWarning.value?.hazardCode) ?? null)

/* ===================== KPI 计算（无真实数据时显示 —） ===================== */

/** 消息通知送达率：优先取 warning 上的 notificationRate/notifyRate/deliveryRate 字段求平均，否则显示 — */
const notificationRateDisplay = computed(() => {
  const list = warnings.value
  if (!list || list.length === 0) return null
  const rates = list
    .map((w) => w.notificationRate ?? w.notifyRate ?? w.deliveryRate)
    .filter((v) => typeof v === 'number' && !Number.isNaN(v))
  if (rates.length === 0) return null
  const avg = rates.reduce((a, b) => a + b, 0) / rates.length
  return Math.round(avg)
})

/** 联动部门数：优先取 linkedDepartments/depts 数组去重计数，其次用 deptCount/departmentCount 最大值，否则显示 — */
const deptCountDisplay = computed(() => {
  const list = warnings.value
  if (!list || list.length === 0) return null
  const allDepts = new Set()
  let hasArrayData = false
  list.forEach((w) => {
    const arr = w.linkedDepartments ?? w.depts
    if (Array.isArray(arr)) {
      hasArrayData = true
      arr.forEach((d) => allDepts.add(typeof d === 'object' ? d.id ?? d.name : d))
    }
  })
  if (hasArrayData) return allDepts.size
  const counts = list
    .map((w) => w.deptCount ?? w.departmentCount)
    .filter((v) => typeof v === 'number' && !Number.isNaN(v))
  if (counts.length > 0) return Math.max(...counts)
  return null
})

/**
 * 处置进度：
 * 1. 优先使用 warning 上的 progress / disposalProgress / disposeProgress 字段
 * 2. 没有则根据 status 估算
 * 3. 都没有返回 null，模板显示 —
 */
const disposalProgress = computed(() => {
  const w = selectedWarning.value
  if (!w) return null
  const direct = w.progress ?? w.disposalProgress ?? w.disposeProgress
  if (typeof direct === 'number' && !Number.isNaN(direct)) {
    return Math.min(100, Math.max(0, Math.round(direct)))
  }
  const status = w.status ?? ''
  if (status === '待确认' || status.includes('发布') || status.includes('新建')) return 10
  if (status === '已确认') return 30
  if (status === '已派发') return 40
  if (status === '处理中' || status.includes('处置')) return 60
  if (status === '已解除' || status === '已关闭' || status === '已忽略') return 100
  return null
})

/**
 * 顶部四张统计卡片（数据驱动）。
 * 模板里只保留一段 v-for，条件判断集中在这里，避免四段硬编码各写一遍。
 */
const statCards = computed(() => {
  const confirmedCount = warnings.value.filter((w) => w.status === '已确认').length
  const notifyRate = notificationRateDisplay.value
  const deptCount = deptCountDisplay.value
  return [
    {
      key: 'active',
      label: '未闭环预警',
      icon: Bell,
      value: activeWarnings.value.length,
      unit: '起',
      note: '需快速处置',
    },
    {
      key: 'confirmed',
      label: '已确认预警',
      icon: ShieldCheck,
      value: confirmedCount,
      unit: '起',
      note: '闭环处理中',
    },
    {
      key: 'notify',
      label: '消息通知',
      icon: MessageSquareText,
      value: notifyRate,
      unit: notifyRate != null ? '%' : '',
      note: '全渠道触达',
    },
    {
      key: 'dept',
      label: '联动部门',
      icon: UsersRound,
      value: deptCount,
      unit: deptCount != null ? '个' : '',
      note: '协同响应',
    },
  ]
})

/** 导出预警报告（列表头部与处置面板共用同一入口） */
async function exportReport() {
  try {
    const result = await dashboardApi.exportWarningReport()
    emit('notify', `已导出 ${result.fileName}`)
  } catch (error) {
    emit('notify', error instanceof Error ? error.message : '导出失败')
  }
}

/** Tab 切换时若选中已不在新列表内，自动落到当前首条 */
function switchTab(tab) {
  if (statusTab.value === tab) return
  statusTab.value = tab
  showAll.value = false
  const nextPool = tab === 'archived' ? archivedWarnings.value : activeWarnings.value
  if (!nextPool.find((w) => w.id === selectedWarningId.value)) {
    selectedWarningId.value = nextPool[0]?.id ?? null
  }
}

/** AI 综合分 Map（hazardCode → {riskLevel, riskScore}）。 */
const aiScoreMap = ref(new Map())

function getAiForHazard(hazardCode) {
  if (!hazardCode) return null
  return aiScoreMap.value.get(hazardCode) ?? null
}

async function loadData(page = 1) {
  loading.value = true
  try {
    const data = await dashboardApi.getWarnings({ page, size: paging.value.size })
    // 拉 AI 综合分
    let aiList = []
    try {
      aiList = await dashboardApi.getAiComprehensiveAll()
    } catch (_) {
      aiList = []
    }
    const aiMap = new Map()
    aiList.forEach((s) => aiMap.set(s.hazardCode, s))
    aiScoreMap.value = aiMap

    // 给每条预警补 aiScore / aiLevel
    const enriched = (data.list ?? []).map((w) => {
      const ai = aiMap.get(w.hazardCode)
      return {
        ...w,
        aiScore: ai?.riskScore ?? null,
        aiLevel: ai?.riskLevel ?? null,
      }
    })
    // 排序：AI 综合分高的优先；空则按 level 字符串
    enriched.sort((a, b) => {
      const sa = a.aiScore ?? -1
      const sb = b.aiScore ?? -1
      if (sb !== sa) return sb - sa
      return levelWeight(b.level) - levelWeight(a.level)
    })

    warnings.value = enriched
    paging.value = { page: data.page ?? page, size: data.size ?? paging.value.size, total: data.total ?? enriched.length, pages: data.pages ?? 1 }
    selectedWarningId.value = warnings.value[0]?.id ?? null
  } finally {
    loading.value = false
  }
}

function levelWeight(level) {
  if (!level) return 0
  if (level.includes('红色')) return 4
  if (level.includes('橙')) return 3
  if (level.includes('黄')) return 2
  if (level.includes('蓝')) return 1
  return 0
}

async function changePage(step) {
  const nextPage = Math.min(Math.max(paging.value.page + step, 1), paging.value.pages || 1)
  if (nextPage !== paging.value.page) await loadData(nextPage)
}

function openDetail(warning) {
  const target = warning ?? selectedWarning.value
  if (target) router.push(`/dashboard/warnings/${target.id}`)
}

async function confirmWarning() {
  if (!selectedWarning.value) return
  try {
    const result = await dashboardApi.confirmWarning(selectedWarning.value.id)
    warnings.value = warnings.value.map((item) => (String(item.id) === String(result.id) ? result : item))
    emit('notify', `已确认 ${selectedWarning.value.title}`)
  } catch (error) {
    emit('notify', error instanceof Error ? error.message : '确认失败')
  }
}

async function dispatchWarning() {
  if (!selectedWarning.value) return
  try {
    const result = await dashboardApi.dispatchWarning(selectedWarning.value.id)
    warnings.value = warnings.value.map((item) => (String(item.id) === String(result.id) ? result : item))
    // 后端 WarningDto 无 escalate 字段，按等级字符串判断应急联动
    const escalated = /橙|红/.test(String(result.level ?? selectedWarning.value.level ?? ''))
    if (escalated) {
      emit('notify', `已派发 ${selectedWarning.value.title}，并按等级自动启动应急响应`)
    } else {
      emit('notify', `已派发 ${selectedWarning.value.title}，按等级采取加密巡查`)
    }
  } catch (error) {
    emit('notify', error instanceof Error ? error.message : '派发失败')
  }
}

/* ===================== 关闭 / 解除 / 忽略 三件套 =====================
 * P1 链路补齐：所有"结束当前预警生命周期"的操作均走原因弹窗，
 * 不再硬编码 '处置完成'。弹窗收集 reason 后再调用 API。
 * 后端会在关闭时一并记录原因到 close_reason 字段。
 */
const REASON_PRESETS = {
  close: [
    '现场处置完成，险情解除',
    '风险已降至关注级以下',
    '监测数据持续平稳，建议降级',
    '已完成应急闭环，事件终止',
    '其他（请手动输入原因）',
  ],
  dismiss: [
    '确认为误报，数据源抖动',
    '确认为误报，阈值误触发',
    '确认为误报，人工复核未发现异常',
    '其他（请手动输入原因）',
  ],
  resolve: [
    '监测数据恢复正常，自动解除',
    '降雨停止，致灾条件消失',
    '设备恢复在线，告警自动收敛',
    '其他（请手动输入原因）',
  ],
}

const ACTION_LABEL = {
  close: '关闭',
  dismiss: '标记为误报',
  resolve: '自动解除',
}
const ACTION_DESC = {
  close: '关闭此预警后，事件流转结束，请填写关闭原因。',
  dismiss: '将此预警标记为误报，归入历史档案，请说明判定依据。',
  resolve: '系统按既定规则自动解除预警，请记录解除依据。',
}

function openCloseDialog(warning, action) {
  if (!warning) return
  closeDialog.value = {
    open: true,
    warningId: warning.id,
    warningTitle: warning.title,
    action,
    reason: '',
    error: '',
    saving: false,
  }
}

function applyPreset(reason) {
  if (reason === '其他（请手动输入原因）') {
    closeDialog.value.reason = ''
  } else {
    closeDialog.value.reason = reason
  }
  closeDialog.value.error = ''
}

async function confirmCloseDialog() {
  const dialog = closeDialog.value
  const trimmed = dialog.reason.trim()
  if (!trimmed) {
    dialog.error = '请输入解除/关闭原因'
    return
  }
  dialog.saving = true
  dialog.error = ''
  try {
    let result
    if (dialog.action === 'close') {
      result = await dashboardApi.closeWarning(dialog.warningId, { reason: trimmed })
    } else if (dialog.action === 'dismiss') {
      result = await dashboardApi.dismissWarning(dialog.warningId)
      // dismissReason 由前端拼装写入结果回显
      if (result && typeof result === 'object') {
        result.closeReason = trimmed
        result.status = '已忽略'
      }
    } else {
      result = await dashboardApi.resolveWarning(dialog.warningId)
      if (result && typeof result === 'object') {
        result.closeReason = trimmed
        result.status = result.status ?? '已解除'
      }
    }
    if (result && typeof result === 'object') {
      warnings.value = warnings.value.map((item) =>
        String(item.id) === String(result.id) ? { ...item, ...result } : item,
      )
    }
    emit('notify', `已${ACTION_LABEL[dialog.action]}：${dialog.warningTitle}`)
    closeDialog.value.open = false
    // 切到归档 Tab 方便用户立刻看到刚操作的那条
    if (statusTab.value === 'active' && activeWarnings.value.length === 0) {
      switchTab('archived')
    } else if (statusTab.value === 'active') {
      selectedWarningId.value = activeWarnings.value[0]?.id ?? null
    }
  } catch (error) {
    dialog.error = error instanceof Error ? error.message : `${ACTION_LABEL[dialog.action]}失败`
  } finally {
    dialog.saving = false
  }
}

function cancelCloseDialog() {
  if (closeDialog.value.saving) return
  closeDialog.value.open = false
  closeDialog.value.error = ''
}

// ESC 键关闭弹窗
useEscapeClose(cancelCloseDialog, () => closeDialog.value.open)

// ===================== 预警创建弹窗控制 =====================
/**
 * 打开预警创建弹窗
 */
function openCreateModal() {
  showCreateModal.value = true
}

/**
 * 关闭预警创建弹窗
 */
function closeCreateModal() {
  showCreateModal.value = false
  if (modalRef.value?.reset) {
    modalRef.value.reset()
  }
}

/**
 * 弹窗创建成功后的回调
 * 1. 关闭弹窗
 * 2. 刷新预警列表
 * 3. 显示成功提示
 */
async function handleCreateSuccess(warning) {
  closeCreateModal()
  emit('notify', `预警创建成功（编号 ${warning.id}），即将进入审批流程`)
  // 重新加载预警列表
  await loadData(1)
}

/**
 * 顶栏「新建预警」通过 ?create=1 唤起本页弹窗。
 *
 * 打开后立即用 replace 摘掉该参数：否则用户手动关掉弹窗后，
 * 再次点顶栏会 push 到完全相同的 URL，vue-router 判定为重复导航而不再触发 watch，
 * 弹窗就再也弹不出来。
 *
 * @returns {void}
 */
function openCreateFromQuery() {
  if (String(route.query.create) !== '1') return
  openCreateModal()
  const query = { ...route.query }
  delete query.create
  router.replace({ path: route.path, query }).catch(() => {})
}

watch(() => route.query.create, openCreateFromQuery, { immediate: true })

/* ===================== 兼容旧调用入口（内部走弹窗） ===================== */

async function closeWarning() {
  if (!selectedWarning.value) return
  openCloseDialog(selectedWarning.value, 'close')
}

async function dismissWarning() {
  if (!selectedWarning.value) return
  openCloseDialog(selectedWarning.value, 'dismiss')
}

async function resolveWarning() {
  if (!selectedWarning.value) return
  openCloseDialog(selectedWarning.value, 'resolve')
}

function translateLevel(level) {
  if (!level) return '未知'
  if (level === 'CRITICAL') return '极高风险'
  if (level === 'HIGH') return '高风险'
  if (level === 'MEDIUM') return '中等风险'
  if (level === 'LOW') return '低风险'
  return level
}

onMounted(loadData)
</script>

<template>
  <div>
    <div v-if="loading" class="panel loading-container">
      <SkeletonList :count="3" layout="vertical" />
    </div>
    <template v-else>
      <div class="stats-grid">
        <article v-for="card in statCards" :key="card.key" class="stat-card">
          <span class="stat-icon">
            <component :is="card.icon" :size="15" />
          </span>
          <div class="stat-copy">
            <span class="stat-label">{{ card.label }}</span>
            <strong :class="{ 'is-empty': card.value === 0 || card.value == null }">
              {{ card.value == null ? '—' : card.value }}<small v-if="card.value != null">{{ card.unit }}</small>
            </strong>
          </div>
          <em class="stat-note">{{ card.note }}</em>
        </article>
      </div>

      <div class="content-grid">
        <section class="panel warning-panel">
          <div class="panel-header compact">
            <div>
              <h2>预警列表</h2>
              <p>{{ statusTab === 'archived' ? '历史解除与归档，关闭原因可追溯' : '确认、升级和关闭一体化处理' }}</p>
            </div>
            <div class="status-tabs">
              <button
                type="button"
                class="status-tab"
                :class="{ active: statusTab === 'active' }"
                @click="switchTab('active')"
              >
                <Bell :size="14" />
                活跃
                <em>{{ activeWarnings.length }}</em>
              </button>
              <button
                type="button"
                class="status-tab"
                :class="{ active: statusTab === 'archived' }"
                @click="switchTab('archived')"
              >
                <Archive :size="14" />
                已解除归档
                <em>{{ archivedWarnings.length }}</em>
              </button>
            </div>
            <div class="heading-actions">
              <PermissionButton
                class="action-secondary"
                permission="warnings:create"
                :icon="Send"
                @click="({ allowed, reason }) => allowed ? openCreateModal() : emit('notify', reason)"
              >发布预警</PermissionButton>
            </div>
          </div>
          <div v-if="currentList.length === 0" class="empty-state">
            <Inbox :size="32" />
            <p>{{ statusTab === 'archived' ? '暂无已归档的预警事件' : '当前没有活跃预警，态势平稳' }}</p>
          </div>
          <div v-else class="warning-list">
            <article
              v-for="warning in visibleWarnings"
              :key="warning.id"
              class="warning-item"
              :class="{ 'is-archived': isArchived(warning), 'is-selected': warning.id === selectedWarningId }"
              @click="openDetail(warning)"
            >
              <span class="warning-symbol" :class="`tone-${toneOf(warning)}`">
                <Check v-if="isArchived(warning)" :size="14" />
                <AlertTriangle v-else :size="14" />
              </span>
              <div class="warning-body">
                <div class="warning-title">
                  <strong>{{ warning.title }}</strong>
                  <span class="level-badge" :class="`tone-${toneOf(warning)}`">
                    <i class="level-dot"></i>{{ warning.level }}
                  </span>
                  <span
                    v-if="warning.aiScore != null"
                    class="ai-priority-tag"
                    :title="`AI 综合分 ${warning.aiScore.toFixed(1)}（${warning.aiLevel}）`"
                  >
                    <ShieldAlert :size="11" /> {{ Math.round(warning.aiScore) }}
                  </span>
                  <span v-if="isArchived(warning)" class="archived-tag">{{ warning.status }}</span>
                </div>
                <div class="warning-meta">
                  <span class="warning-desc">{{ warning.desc }}</span>
                  <span class="warning-point"><MapPin :size="12" />{{ warning.point }}</span>
                  <time class="warning-time">{{ warning.time }}</time>
                </div>
                <p
                  v-if="isArchived(warning) && (warning.closeReason || warning.closedAt)"
                  class="archive-meta"
                >
                  <ArchiveRestore :size="12" />
                  {{ warning.closeReason ?? '无原因记录' }}
                  <em v-if="warning.closedAt">· {{ warning.closedAt }}</em>
                </p>
              </div>
            </article>
          </div>
          <div class="panel-footer pagination-bar">
            <span>共 {{ paging.total }} 条，{{ paging.page }}/{{ paging.pages || 1 }} 页</span>
            <div class="detail-utility">
              <button class="secondary-btn" :disabled="paging.page <= 1" @click="changePage(-1)">上一页</button>
              <button class="secondary-btn" :disabled="paging.page >= (paging.pages || 1)" @click="changePage(1)">下一页</button>
            </div>
          </div>
          <div class="detail-utility" style="padding: 0 20px 18px;">
            <button class="secondary-btn" @click="showAll = !showAll">{{ showAll ? '收起列表' : '查看全部' }}</button>
          </div>
        </section>

        <section class="panel system-panel">
          <header class="panel-header compact system-head">
            <div class="system-head-title">
              <h2>预警处置</h2>
              <p>当前预警的闭环状态</p>
            </div>
            <div class="system-head-tools">
              <span class="health-label"><i></i>{{ selectedWarning?.status ?? '—' }}</span>
              <span class="icon-btn-slot">
                <PermissionButton
                  permission="warnings:export"
                  :icon="Download"
                  aria-label="导出预警报告"
                  @click="async ({ allowed, reason }) => {
                    if (!allowed) return emit('notify', reason)
                    await exportReport()
                  }"
                />
              </span>
            </div>
          </header>

          <div v-if="selectedWarning" class="system-body">
            <!-- 当前预警概要：标题 + 处置进度条 + 分级策略 -->
            <div class="dispatch-overview">
              <strong class="dispatch-title">{{ selectedWarning.title }}</strong>
              <p class="dispatch-desc">{{ selectedWarning.desc }}</p>
              <div class="dispatch-progress">
                <div class="dispatch-progress-track">
                  <i :style="{ width: (disposalProgress ?? 0) + '%' }"></i>
                </div>
                <span class="dispatch-progress-text">
                  {{ disposalProgress != null ? `处置进度 ${disposalProgress}%` : '处置进度待计算' }}
                </span>
              </div>
              <p class="dispatch-hint"><Zap :size="13" />{{ escalationHint }}</p>
            </div>

            <!-- 基础信息：两栏紧凑网格 -->
            <div class="system-grid">
              <div class="system-item">
                <span class="system-item-label"><Bell :size="13" />预警等级</span>
                <strong class="system-item-value" :class="`tone-${toneOf(selectedWarning)}`">
                  <i class="level-dot"></i>{{ selectedWarning.level }}
                </strong>
              </div>
              <div class="system-item">
                <span class="system-item-label"><Layers3 :size="13" />触发点位</span>
                <strong class="system-item-value">{{ selectedWarning.point }}</strong>
              </div>
              <div class="system-item">
                <span class="system-item-label"><AlertTriangle :size="13" />触发时间</span>
                <strong class="system-item-value is-time">{{ selectedWarning.time }}</strong>
              </div>
              <div class="system-item">
                <span class="system-item-label"><FileText :size="13" />当前状态</span>
                <strong class="system-item-value">{{ selectedWarning.status }}</strong>
              </div>
              <div v-if="aiInfo" class="system-item">
                <span class="system-item-label"><ShieldAlert :size="13" />AI 综合评分</span>
                <strong class="system-item-value">
                  {{ (aiInfo.riskScore ?? 0).toFixed(1) }} 分 · {{ translateLevel(aiInfo.riskLevel) }}
                </strong>
              </div>
              <div v-if="selectedWarning.closeReason" class="system-item is-wide">
                <span class="system-item-label"><RotateCcw :size="13" />关闭原因</span>
                <strong class="system-item-value">{{ selectedWarning.closeReason }}</strong>
              </div>
            </div>
          </div>

          <div v-else class="system-empty">
            <Inbox :size="22" />
            <p>当前没有可处置的预警</p>
          </div>

          <!-- 操作区：主按钮（查看详情）+ 次要按钮（派发/确认）+ 危险 Ghost（关闭/解除） -->
          <footer class="system-actions">
            <PermissionButton
              v-if="selectedWarning && (selectedWarning.status === '待确认' || selectedWarning.status === '已确认')"
              class="action-secondary"
              permission="warnings:confirm"
              :icon="CheckCircle2"
              @click="async ({ allowed, reason }) => {
                if (!allowed) return emit('notify', reason)
                await confirmWarning()
              }"
            >确认</PermissionButton>

            <PermissionButton
              v-if="selectedWarning && !isArchived(selectedWarning)"
              class="action-secondary"
              permission="warnings:dispatch"
              :icon="Send"
              @click="async ({ allowed, reason }) => {
                if (!allowed) return emit('notify', reason)
                await dispatchWarning()
              }"
            >派发</PermissionButton>

            <PermissionButton
              v-if="selectedWarning && selectedWarning.status === '待确认'"
              class="action-danger"
              permission="warnings:close"
              @click="async ({ allowed, reason }) => {
                if (!allowed) return emit('notify', reason)
                await dismissWarning()
              }"
            >标记误报</PermissionButton>

            <PermissionButton
              v-if="selectedWarning && (selectedWarning.status === '处理中' || selectedWarning.status === '已派发')"
              class="action-danger"
              permission="warnings:close"
              @click="async ({ allowed, reason }) => {
                if (!allowed) return emit('notify', reason)
                await resolveWarning()
              }"
            >自动解除</PermissionButton>

            <PermissionButton
              v-if="selectedWarning && !ARCHIVED_STATUSES.includes(selectedWarning.status)"
              class="action-danger"
              permission="warnings:close"
              @click="async ({ allowed, reason }) => {
                if (!allowed) return emit('notify', reason)
                await closeWarning()
              }"
            >关闭</PermissionButton>

            <button class="action-primary" type="button" @click="openDetail">
              查看详情<ArrowRight :size="14" />
            </button>
          </footer>
        </section>
      </div>

      <!-- ============== 解除归档 / 关闭原因弹窗 ============== -->
      <div v-if="closeDialog.open" class="close-dialog-mask" @click.self="cancelCloseDialog">
        <div class="close-dialog" role="dialog" aria-modal="true">
          <div class="close-dialog-head">
            <strong>{{ ACTION_LABEL[closeDialog.action] }}预警</strong>
            <button class="close-dialog-x" @click="cancelCloseDialog" aria-label="关闭">×</button>
          </div>
          <div class="close-dialog-body">
            <p class="close-dialog-target">
              <AlertTriangle :size="14" />
              <span>{{ closeDialog.warningTitle }}</span>
            </p>
            <p class="close-dialog-hint">{{ ACTION_DESC[closeDialog.action] }}</p>

            <div class="close-dialog-presets">
              <button
                v-for="preset in REASON_PRESETS[closeDialog.action]"
                :key="preset"
                type="button"
                class="preset-chip"
                :class="{ active: closeDialog.reason === preset }"
                @click="applyPreset(preset)"
              >
                {{ preset }}
              </button>
            </div>

            <label class="close-dialog-field">
              <span>解除/关闭原因<em>*</em></span>
              <textarea
                v-model="closeDialog.reason"
                rows="3"
                :placeholder="closeDialog.action === 'close' ? '现场处置完成，险情解除' : closeDialog.action === 'dismiss' ? '确认为误报，数据源抖动' : '监测数据恢复正常，自动解除'"
              ></textarea>
            </label>

            <p v-if="closeDialog.error" class="close-dialog-error">{{ closeDialog.error }}</p>
          </div>
          <div class="close-dialog-foot">
            <button class="secondary-btn" :disabled="closeDialog.saving" @click="cancelCloseDialog">取消</button>
            <button class="primary-btn" :disabled="closeDialog.saving" @click="confirmCloseDialog">
              {{ closeDialog.saving ? '提交中...' : `确认${ACTION_LABEL[closeDialog.action]}` }}
            </button>
          </div>
        </div>
      </div>

      <!-- ============== 预警创建弹窗 ============== -->
      <WarningCreateModal
        v-if="showCreateModal"
        ref="modalRef"
        @success="handleCreateSuccess"
        @close="closeCreateModal"
      />
    </template>
  </div>
</template>

<style scoped>
/* ═══════════════════════════════════════════════════════════
   预警中心列表页 —— 与详情页同一套 UI 语言
   tokens 来源：frontend/design-system/disasterguard/tokens.css
   规则：小字一律 --color-foreground / --color-muted-foreground；
        莫兰迪色调只落在图形（图标 / 底色 / 描边）上，避免浅底文字对比度不足。
   ═══════════════════════════════════════════════════════════ */

.loading-container {
  padding: var(--space-lg);
}

/* ═══════════════════════════════════════════════════════════
   1. 顶部统计卡片（压扁版）
   ═══════════════════════════════════════════════════════════ */
.stats-grid {
  gap: var(--space-md);
}

.stat-card {
  min-height: 0;
  padding: var(--space-md) var(--space-lg);
  display: flex;
  align-items: center;
  gap: var(--space-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-card);
  box-shadow: var(--shadow-xs);
  transition: box-shadow var(--duration-fast) var(--ease-out),
    border-color var(--duration-fast) var(--ease-out);
}

.stat-card:hover {
  transform: none;
  top: 0;
  border-color: var(--color-border-hover);
  box-shadow: var(--shadow-sm);
}

/* 小图标：浅色底圈，颜色统一走 primary-soft */
.stat-icon {
  width: 28px;
  height: 28px;
  flex: 0 0 auto;
  display: grid;
  place-items: center;
  border-radius: var(--radius-full);
  background: var(--color-primary-soft);
  color: var(--color-primary);
}

.stat-copy {
  min-width: 0;
}

.stat-copy .stat-label {
  display: block;
  color: var(--color-muted-foreground);
  font-size: var(--text-sm);
  font-weight: var(--font-medium);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.stat-copy strong {
  display: flex;
  align-items: baseline;
  gap: 2px;
  margin-top: 2px;
  color: var(--color-foreground);
  font-size: var(--text-3xl);
  font-weight: var(--font-bold);
  letter-spacing: -0.03em;
  line-height: 1.1;
  font-variant-numeric: tabular-nums;
}

/* 数值单位：跟着字号缩小，不抢主数字 */
.stat-copy strong small {
  margin-left: 0;
  color: var(--color-tertiary);
  font-size: var(--text-xs);
  font-weight: var(--font-medium);
}

/* 0 或「—」时降级为弱化色，避免大号数字抢戏 */
.stat-copy strong.is-empty {
  color: var(--color-muted-foreground);
}

.stat-note {
  margin-left: auto;
  padding-left: var(--space-sm);
  color: var(--color-tertiary);
  font-size: var(--text-xs);
  font-style: normal;
  white-space: nowrap;
}

/* ═══════════════════════════════════════════════════════════
   2. 按钮体系：主 / 次 / 危险 Ghost / 图标
   ═══════════════════════════════════════════════════════════ */

/* 主按钮：查看详情 */
.action-primary {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-height: 36px;
  padding: 0 16px;
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  background: var(--color-primary);
  color: var(--color-on-primary);
  font-family: inherit;
  font-size: var(--text-sm);
  font-weight: var(--font-semibold);
  white-space: nowrap;
  cursor: pointer;
  transition: background-color var(--duration-fast) var(--ease-out),
    box-shadow var(--duration-fast) var(--ease-out),
    transform var(--duration-fast) var(--ease-out);
}

.action-primary:hover {
  background: var(--color-primary-hover);
  box-shadow: var(--shadow-md);
}

.action-primary:active {
  background: var(--color-primary-active);
  transform: scale(0.98);
}

.action-primary:focus-visible {
  outline: none;
  box-shadow: var(--shadow-focus);
}

/* 次要按钮：派发 / 确认 / 发布预警 */
.action-secondary {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-height: 36px;
  padding: 0 14px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface-1);
  color: var(--color-foreground);
  font-family: inherit;
  font-size: var(--text-sm);
  font-weight: var(--font-medium);
  white-space: nowrap;
  cursor: pointer;
  transition: background-color var(--duration-fast) var(--ease-out),
    border-color var(--duration-fast) var(--ease-out),
    color var(--duration-fast) var(--ease-out);
}

.action-secondary:hover {
  background: var(--color-primary-soft);
  border-color: var(--color-border-hover);
  color: var(--color-primary);
}

.action-secondary:active {
  transform: scale(0.98);
}

.action-secondary:focus-visible {
  outline: none;
  box-shadow: var(--shadow-focus);
}

/* 危险 Ghost：纯文本，悬停才变红 */
.action-danger {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 36px;
  padding: 0 10px;
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--color-muted-foreground);
  font-family: inherit;
  font-size: var(--text-sm);
  font-weight: var(--font-medium);
  white-space: nowrap;
  cursor: pointer;
  transition: color var(--duration-fast) var(--ease-out),
    background-color var(--duration-fast) var(--ease-out);
}

.action-danger:hover {
  color: var(--color-destructive-strong);
  background: var(--color-destructive-bg);
}

.action-danger:focus-visible {
  outline: none;
  box-shadow: var(--shadow-focus);
}

/* 权限被拒时的降级态（PermissionButton 会加 .is-denied） */
.action-secondary.is-denied,
.action-danger.is-denied {
  background: var(--color-surface-1);
  border-color: var(--color-border);
  color: var(--color-muted-foreground);
}

/* 列表头部的「发布预警」沿用次要按钮外观 */
.heading-actions .action-secondary {
  min-height: 32px;
  padding: 0 12px;
}

/* ═══════════════════════════════════════════════════════════
   3. 左侧预警列表
   ═══════════════════════════════════════════════════════════ */
.warning-list {
  padding: 0 var(--space-lg) var(--space-sm);
}

.warning-item {
  display: flex;
  gap: var(--space-md);

  /* 左侧留 3px 给选中态边框，padding 相应减去 3px 保证文字对齐不跳动 */
  padding: var(--space-md) var(--space-sm) var(--space-md) calc(var(--space-md) - 3px);
  border-bottom: 1px solid var(--color-border);
  border-left: 3px solid transparent;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: background-color var(--duration-fast) var(--ease-out),
    border-color var(--duration-fast) var(--ease-out),
    box-shadow var(--duration-fast) var(--ease-out);
}

.warning-item:last-child {
  border-bottom: 0;
}

.warning-item:hover {
  background: var(--color-surface-1);
  border-bottom-color: var(--color-border-hover);
  box-shadow: var(--shadow-sm);
}

/* 选中态：右侧面板正在展示的那一条 */
.warning-item.is-selected {
  border-left-color: var(--color-primary);
  background: var(--color-primary-mist);
}

.warning-item.is-selected:hover {
  background: var(--color-primary-soft);
}

/* 归档项整体弱化，但文字仍保持可读 */
.warning-item.is-archived {
  opacity: 0.82;
}

.warning-item.is-archived .warning-symbol {
  color: var(--color-success-strong);
  background: var(--color-success-bg);
}

/* 等级图标：色调只落在这里（图形，可接受 3:1） */
.warning-symbol {
  width: 28px;
  height: 28px;
  flex: 0 0 auto;
  display: grid;
  place-items: center;
  border-radius: var(--radius-full);
}

.warning-symbol.tone-destructive {
  color: var(--color-destructive-strong);
  background: var(--color-destructive-bg);
}

.warning-symbol.tone-warning {
  color: var(--color-warning-ink);
  background: var(--color-warning-bg);
}

.warning-symbol.tone-caution {
  color: var(--color-warning-ink);
  background: var(--color-warning-bg);
}

.warning-symbol.tone-info {
  color: var(--color-accent);
  background: var(--color-accent-soft);
}

.warning-symbol.tone-neutral {
  color: var(--color-muted-foreground);
  background: var(--color-surface-2);
}

.warning-body {
  min-width: 0;
  flex: 1;
}

.warning-title {
  display: flex;
  align-items: center;
  justify-content: flex-start;
  gap: var(--space-sm);
  min-width: 0;
}

.warning-title strong {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  color: var(--color-foreground);
  font-size: var(--text-base);
  font-weight: var(--font-semibold);
  white-space: nowrap;
  text-overflow: ellipsis;
}

/* 等级徽章：文字用前景色，色调由色点承担 */
.level-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  flex: 0 0 auto;
  padding: 1px 8px;
  border-radius: var(--radius-full);
  font-size: var(--text-xs);
  font-weight: var(--font-medium);
  line-height: 1.7;
  white-space: nowrap;
}

.level-dot {
  width: 6px;
  height: 6px;
  flex: 0 0 auto;
  border-radius: var(--radius-full);
  background: var(--color-muted-foreground);
}

.level-badge.tone-destructive {
  color: var(--color-foreground);
  background: var(--color-destructive-bg);
}

.level-badge.tone-destructive .level-dot {
  background: var(--color-destructive-strong);
}

.level-badge.tone-warning {
  color: var(--color-foreground);
  background: var(--color-warning-bg);
}

.level-badge.tone-warning .level-dot {
  background: var(--color-warning-ink);
}

.level-badge.tone-caution {
  color: var(--color-foreground);
  background: var(--color-warning-bg);
}

.level-badge.tone-caution .level-dot {
  background: var(--color-warning-ink);
}

.level-badge.tone-info {
  color: var(--color-foreground);
  background: var(--color-accent-soft);
}

.level-badge.tone-info .level-dot {
  background: var(--color-accent);
}

.level-badge.tone-neutral {
  color: var(--color-foreground);
  background: var(--color-surface-2);
}

/* AI 综合分：小标签，图标承担强调色 */
.ai-priority-tag {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  flex: 0 0 auto;
  padding: 1px 8px;
  border-radius: var(--radius-full);
  background: var(--color-surface-2);
  color: var(--color-foreground);
  font-size: var(--text-xs);
  font-weight: var(--font-semibold);
  font-variant-numeric: tabular-nums;
}

.ai-priority-tag svg {
  color: var(--color-primary);
}

.archived-tag {
  flex: 0 0 auto;
  padding: 1px 8px;
  border-radius: var(--radius-full);
  background: var(--color-surface-2);
  color: var(--color-foreground);
  font-size: var(--text-xs);
  font-weight: var(--font-medium);
}

/* 次级信息行：描述 + 地点 + 时间戳 */
.warning-meta {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  min-width: 0;
  margin-top: var(--space-xs);
  color: var(--color-muted-foreground);
  font-size: var(--text-sm);
}

.warning-desc {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.warning-point {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  flex: 0 0 auto;
  color: var(--color-muted-foreground);
  white-space: nowrap;
}

.warning-time {
  flex: 0 0 auto;
  margin-left: auto;
  padding-left: var(--space-sm);
  color: var(--color-muted-foreground);
  font-size: var(--text-xs);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

/* 归档原因：第三行弱化说明 */
.warning-body .archive-meta {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-top: var(--space-xs);
  padding-left: 0;
  border-left: 0;
  color: var(--color-muted-foreground);
  font-size: var(--text-xs);
}

.warning-body .archive-meta em {
  font-style: normal;
  color: var(--color-tertiary);
}

/* ═══════════════════════════════════════════════════════════
   4. 右侧「预警处置」面板
   ═══════════════════════════════════════════════════════════ */
.system-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-sm);
}

.system-head-tools {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  flex: 0 0 auto;
}

.health-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--color-muted-foreground);
  font-size: var(--text-xs);
  white-space: nowrap;
}

/* 导出：纯图标按钮 */
.icon-btn-slot {
  display: inline-flex;
}

.icon-btn-slot :deep(.ios-btn) {
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  padding: 0;
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--color-muted-foreground);
  cursor: pointer;
  transition: background-color var(--duration-fast) var(--ease-out),
    border-color var(--duration-fast) var(--ease-out),
    color var(--duration-fast) var(--ease-out);
}

.icon-btn-slot :deep(.ios-btn:hover) {
  background: var(--color-surface-1);
  border-color: var(--color-border);
  color: var(--color-primary);
}

.icon-btn-slot :deep(.ios-btn:focus-visible) {
  outline: none;
  box-shadow: var(--shadow-focus);
}

.system-body {
  padding: 0 var(--space-lg) var(--space-md);
}

/* 当前预警概要 */
.dispatch-overview {
  padding: var(--space-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-surface-1);
}

.dispatch-title {
  display: block;
  color: var(--color-foreground);
  font-size: var(--text-lg);
  font-weight: var(--font-semibold);
  letter-spacing: -0.01em;
  line-height: 1.35;
}

.dispatch-desc {
  margin: var(--space-xs) 0 0;
  color: var(--color-muted-foreground);
  font-size: var(--text-sm);
  line-height: 1.5;
}

/* 处置进度条：替换原来的巨大圆环 */
.dispatch-progress {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  margin-top: var(--space-md);
}

.dispatch-progress-track {
  flex: 1;
  height: 4px;
  overflow: hidden;
  border-radius: var(--radius-full);
  background: color-mix(in srgb, var(--color-primary) 16%, transparent);
}

.dispatch-progress-track i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--color-primary);
  transition: width var(--duration-slow) var(--ease-out);
}

.dispatch-progress-text {
  flex: 0 0 auto;
  color: var(--color-primary);
  font-size: var(--text-xs);
  font-weight: var(--font-semibold);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

/* 分级策略提示：与详情页的应急标签同一套配色 */
.dispatch-hint {
  display: flex;
  align-items: flex-start;
  gap: 5px;
  margin: var(--space-sm) 0 0;
  padding: 6px 8px;
  border-radius: var(--radius-sm);
  background: var(--color-warning-bg);
  color: var(--color-foreground);
  font-size: var(--text-xs);
  line-height: 1.5;
}

.dispatch-hint svg {
  flex: 0 0 auto;
  margin-top: 1px;
  color: var(--color-warning-strong);
}

/* 基础信息：两栏紧凑网格
   右栏在双栏布局下最窄只有 280px，固定两栏会把「触发点位 / 触发时间 /
   AI 综合评分」压成省略号；用容器查询按面板自身宽度降为一栏，
   而单栏布局时面板很宽，仍保持两栏不拉伸 */
.system-panel {
  container-type: inline-size;
  container-name: system-panel;
}

.system-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-sm);
  margin-top: var(--space-md);
}

@container system-panel (width <= 360px) {
  .system-grid {
    grid-template-columns: 1fr;
  }
}

.system-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  padding: var(--space-sm) 10px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-card);
}

.system-item.is-wide {
  grid-column: 1 / -1;
}

.system-item-label {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--color-muted-foreground);
  font-size: var(--text-xs);
}

.system-item-label svg {
  flex: 0 0 auto;
  color: var(--color-tertiary);
}

.system-item-value {
  overflow: hidden;
  color: var(--color-foreground);
  font-size: var(--text-sm);
  font-weight: var(--font-semibold);
  white-space: nowrap;
  text-overflow: ellipsis;
}

.system-item-value.is-time {
  font-variant-numeric: tabular-nums;
}

/* 等级文字用前景色保证可读，色调交给前面的色点（同列表项的等级徽章） */
.system-item-value .level-dot {
  display: inline-block;
  vertical-align: middle;
  margin-right: 5px;
}

.system-item-value.tone-destructive .level-dot { background: var(--color-destructive-strong); }
.system-item-value.tone-warning .level-dot { background: var(--color-warning-ink); }
.system-item-value.tone-caution .level-dot { background: var(--color-warning-ink); }
.system-item-value.tone-info .level-dot { background: var(--color-accent); }
.system-item-value.tone-neutral .level-dot { background: var(--color-muted-foreground); }

.system-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-2xl) var(--space-lg);
  color: var(--color-tertiary);
}

.system-empty p {
  margin: 0;
  font-size: var(--text-sm);
}

/* 操作区 */
.system-actions {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  flex-wrap: wrap;
  padding: var(--space-md) var(--space-lg);
  border-top: 1px solid var(--color-border);
  background: var(--color-surface-1);
}

.system-actions .action-primary {
  margin-left: auto;
}

/* ═══════════════════════════════════════════════════════════
   5. 响应式
   ═══════════════════════════════════════════════════════════ */
@media (width <= 1180px) {
  .stat-note {
    display: none;
  }
}

@media (width <= 760px) {
  .stats-grid {
    grid-template-columns: 1fr 1fr;
  }

  .system-grid {
    grid-template-columns: 1fr;
  }

  .warning-meta {
    flex-wrap: wrap;
  }

  .warning-time {
    margin-left: 0;
    padding-left: 0;
  }
}
</style>
