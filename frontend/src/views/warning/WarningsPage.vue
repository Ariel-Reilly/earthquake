<script setup>
/**
 * 预警管理：列表按"AI 综合评分 + 等级 + 触发时间"三键排序。
 *   - 每条预警会显示一个 sparkles 小角标，标识它的综合风险分数
 *   - AI 综合分析模块跑出 source=AI_AUTO 的预警事件会被自动关联 AI 评分
 *   - v1.7.0+：「活跃 / 已解除归档」双 Tab；关闭操作走原因弹窗；归档项展示关闭原因与时间
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { AlertTriangle, Archive, ArchiveRestore, Bell, FileText, Layers3, MapPin, MessageSquareText, ShieldCheck, UsersRound, Send, CheckCircle2, XCircle, RotateCcw, ShieldAlert, Inbox } from 'lucide-vue-next'
import PermissionButton from '../../components/PermissionButton.vue'
import { dashboardApi } from '../../services/dashboardApi'

const emit = defineEmits(['notify'])
const props = defineProps({ userRole: { type: String, default: 'admin' } })
const router = useRouter()
const warnings = ref([])
const selectedWarningId = ref(null)
const showAll = ref(false)
const loading = ref(true)
const paging = ref({ page: 1, size: 10, total: 0, pages: 0 })

/** Tab 状态：'active' 当前活跃 / 'archived' 已解除归档 */
const statusTab = ref('active')

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
    <div v-if="loading" class="panel" style="padding: 24px;">预警数据加载中...</div>
    <template v-else>
      <div class="stats-grid">
        <div class="stat-card"><div class="stat-icon orange"><Bell :size="19" /></div><div class="stat-copy"><span>未闭环预警</span><strong>{{ activeWarnings.length }} <small>起</small></strong><em class="negative">需快速处置 <i>重点升级</i></em></div></div>
        <div class="stat-card"><div class="stat-icon blue"><ShieldCheck :size="19" /></div><div class="stat-copy"><span>已确认预警</span><strong>{{ warnings.filter((warning) => warning.status === '已确认').length }} <small>起</small></strong><em class="positive">闭环处理 <i>流程规范</i></em></div></div>
        <div class="stat-card"><div class="stat-icon teal"><MessageSquareText :size="19" /></div><div class="stat-copy"><span>消息通知</span><strong>100 <small>%</small></strong><em class="positive">全渠道触达 <i>短信/APP</i></em></div></div>
        <div class="stat-card"><div class="stat-icon purple"><UsersRound :size="19" /></div><div class="stat-copy"><span>联动部门</span><strong>4 <small>个</small></strong><em class="positive">协同响应 <i>专家/乡镇/运维</i></em></div></div>
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
              <PermissionButton v-if="statusTab === 'active'" permission="warnings:dispatch" :icon="Send" @click="async ({ allowed, reason }) => {
                if (!allowed) return emit('notify', reason)
                await dispatchWarning()
              }">派发</PermissionButton>
              <PermissionButton permission="warnings:export" :icon="FileText" @click="async ({ allowed, reason }) => {
                if (!allowed) return emit('notify', reason)
                const result = await dashboardApi.exportWarningReport()
                emit('notify', `已导出 ${result.fileName}`)
              }">导出</PermissionButton>
            </div>
          </div>
          <div v-if="currentList.length === 0" class="empty-state">
            <Inbox :size="32" />
            <p>{{ statusTab === 'archived' ? '暂无已归档的预警事件' : '当前没有活跃预警，态势平稳' }}</p>
          </div>
          <div v-else class="warning-list">
            <article v-for="warning in visibleWarnings" :key="warning.id" class="warning-item" :class="{ archived: isArchived(warning) }" @click="openDetail(warning)">
              <div class="warning-symbol" :class="warning.cssClass ?? warning.class ?? 'orange'">{{ isArchived(warning) ? '✓' : '!' }}</div>
              <div class="warning-body">
                <div class="warning-title">
                  <strong>{{ warning.title }}</strong>
                  <span :class="warning.cssClass ?? warning.class ?? 'orange'">{{ warning.level }}</span>
                  <span v-if="warning.aiScore != null" class="ai-priority-tag" :title="`AI 综合分 ${warning.aiScore.toFixed(1)}（${warning.aiLevel}）`">
                    <ShieldAlert :size="11" /> {{ Math.round(warning.aiScore) }}
                  </span>
                  <span v-if="isArchived(warning)" class="archived-tag">{{ warning.status }}</span>
                </div>
                <p>{{ warning.desc }}</p>
                <small>
                  <MapPin :size="12" />{{ warning.point }}<time>{{ warning.time }}</time>
                  <template v-if="isArchived(warning) && (warning.closeReason || warning.closedAt)">
                    <span class="archive-meta">
                      <ArchiveRestore :size="12" />
                      {{ warning.closeReason ?? '无原因记录' }}
                      <em v-if="warning.closedAt">· {{ warning.closedAt }}</em>
                    </span>
                  </template>
                </small>
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
          <div class="panel-header compact">
            <div>
              <h2>预警处置</h2>
              <p>当前预警的闭环状态</p>
            </div>
            <span class="health-label"><i></i>{{ selectedWarning?.status }}</span>
          </div>
          <div v-if="selectedWarning" class="system-list">
            <div><span><Bell :size="16" />预警等级</span><strong>{{ selectedWarning.level }}</strong></div>
            <div><span><Layers3 :size="16" />触发点位</span><strong>{{ selectedWarning.point }}</strong></div>
            <div><span><AlertTriangle :size="16" />触发时间</span><strong>{{ selectedWarning.time }}</strong></div>
            <div><span><FileText :size="16" />当前状态</span><strong>{{ selectedWarning.status }}</strong></div>
            <div v-if="selectedWarning.closeReason"><span><RotateCcw :size="16" />关闭原因</span><strong>{{ selectedWarning.closeReason }}</strong></div>
            <div v-if="getAiForHazard(selectedWarning.hazardCode)">
              <span><ShieldAlert :size="16" />AI 综合评分</span>
              <strong :class="`ai-tag-${getAiForHazard(selectedWarning.hazardCode)?.riskLevel?.toLowerCase()}`">
                {{ (getAiForHazard(selectedWarning.hazardCode)?.riskScore ?? 0).toFixed(1) }} 分 ·
                {{ translateLevel(getAiForHazard(selectedWarning.hazardCode)?.riskLevel) }}
              </strong>
            </div>
          </div>
          <div v-if="selectedWarning" class="task-summary">
            <div class="progress-ring"><span>{{ selectedWarning.level.includes('橙色') ? '86' : selectedWarning.level.includes('黄色') ? '71' : '54' }}<small>%</small></span></div>
            <div class="task-overview">
              <strong>{{ selectedWarning.title }}</strong>
              <span>{{ selectedWarning.desc }}</span>
              <div class="task-bars"><i style="width: 78%"></i></div>
              <p>{{ escalationHint }}</p>
            </div>
          </div>
          <div class="heading-actions" style="padding: 0 20px 18px; justify-content: flex-end;">
            <PermissionButton v-if="selectedWarning?.status === '待确认' || selectedWarning?.status === '已确认'" permission="warnings:confirm" :icon="CheckCircle2" @click="async ({ allowed, reason }) => {
              if (!allowed) return emit('notify', reason)
              await confirmWarning()
            }">确认</PermissionButton>
            <PermissionButton v-if="selectedWarning && !isArchived(selectedWarning)" permission="warnings:dispatch" :icon="Send" @click="async ({ allowed, reason }) => {
              if (!allowed) return emit('notify', reason)
              await dispatchWarning()
            }">派发</PermissionButton>
            <PermissionButton v-if="selectedWarning && !ARCHIVED_STATUSES.includes(selectedWarning.status)" permission="warnings:close" :icon="XCircle" @click="async ({ allowed, reason }) => {
              if (!allowed) return emit('notify', reason)
              await closeWarning()
            }">关闭</PermissionButton>
            <PermissionButton v-if="selectedWarning?.status === '待确认'" permission="warnings:close" :icon="RotateCcw" @click="async ({ allowed, reason }) => {
              if (!allowed) return emit('notify', reason)
              await dismissWarning()
            }">误报</PermissionButton>
            <PermissionButton v-if="selectedWarning?.status === '处理中' || selectedWarning?.status === '已派发'" permission="warnings:close" :icon="RotateCcw" @click="async ({ allowed, reason }) => {
              if (!allowed) return emit('notify', reason)
              await resolveWarning()
            }">自动解除</PermissionButton>
            <PermissionButton permission="warnings:export" :icon="FileText" @click="async ({ allowed, reason }) => {
              if (!allowed) return emit('notify', reason)
              const result = await dashboardApi.exportWarningReport()
              emit('notify', `已导出 ${result.fileName}`)
            }">导出</PermissionButton>
            <button class="primary-btn" @click="openDetail">查看详情页</button>
          </div>
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
    </template>
  </div>
</template>
