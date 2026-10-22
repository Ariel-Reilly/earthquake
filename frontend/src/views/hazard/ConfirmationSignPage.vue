<script setup>
/**
 * 会签与转派页面 /dashboard/confirmation-sign[/:id]。
 * 支持 3 个 Tab：待我确认 / 我发起的会签 / 抄送我的
 * 单事件详情侧栏：发起会签 / 转派
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  AlertTriangle,
  ArrowLeftRight,
  Check,
  Clock,
  FileSignature,
  Inbox,
  Loader2,
  Plus,
  Send,
  ShieldCheck,
  UserPlus,
  Users,
  X,
} from 'lucide-vue-next'
import SignForm from '../../components/confirmation/SignForm.vue'
import SignTimeline from '../../components/confirmation/SignTimeline.vue'
import { dashboardApi } from '../../services/dashboardApi'
// SIGN_MODE_OPTIONS 是纯 UI 枚举（并行/串行），非业务数据，保留静态定义。
import { SIGN_MODE_OPTIONS } from '../../services/mock/runtime'
import { getAuthUser, hasPermission } from '../../stores/auth'

const emit = defineEmits(['notify'])
defineProps({ searchTerm: { type: String, default: '' }, userRole: { type: String, default: 'admin' } })

const router = useRouter()
const route = useRoute()
const operator = computed(() => getAuthUser() ?? { name: 'system', role: 'admin', id: 'u-admin' })
const canSign = computed(() => hasPermission(operator.value.role, 'sign:create'))

const tab = ref('inbox')
const events = ref([])
const records = ref([])
const loading = ref(false)
const selectedRecord = ref(null)
const dialog = ref(false)
const transferDialog = ref(false)
const transferTarget = ref('')
const transferReason = ref('')
const opinionDraft = ref('')
const tabTimer = ref(null)

// ── 专家池：从真实用户系统按 roleCode=expert 拉取，不再使用硬编码假专家 ──
const expertPool = ref([])

async function loadExperts() {
  try {
    // 后端 GET /api/users?role=expert 返回 { items:[{id,realName,phone,roleCode,...}], total,... }
    const page = await dashboardApi.getUsers({ role: 'expert', page: 1, size: 200 })
    const items = page?.items ?? page?.records ?? []
    expertPool.value = items.map((u) => ({
      id: String(u.id ?? u.username),
      name: u.realName ?? u.username ?? '未命名',
      specialty: u.regionCode ? `辖区 ${u.regionCode}` : '地质灾害',
      org: u.roleCode === 'expert' ? '专家库' : (u.roleCode ?? ''),
    }))
  } catch (e) {
    expertPool.value = []
    emit('notify', `专家库加载失败：${e.message ?? e}`)
  }
}

const TABS = [
  { id: 'inbox',   label: '待我确认', icon: Inbox },
  { id: 'mine',    label: '我发起的会签', icon: FileSignature },
  { id: 'cc',      label: '抄送我的',   icon: Users },
]

const draftsEvents = computed(() => events.value)
const mySignRecords = computed(() => records.value.filter((r) => r.initiator === operator.value.name))
const inboxRecords = computed(() => records.value.filter((r) => r.signers?.some((s) => s.signerName === operator.value.name && s.status === 'PENDING')))

async function loadEvents() {
  try {
    const queue = await dashboardApi.getConfirmationQueue({})
    events.value = queue?.items ?? []
  } catch {
    events.value = []
  }
}

async function loadRecords() {
  loading.value = true
  try {
    const queue = await dashboardApi.getConfirmationQueue({})
    const ids = (queue?.items ?? []).map((it) => it.id ?? it.hazardCode)
    const all = []
    for (const id of ids) {
      try {
        const list = await dashboardApi.listSignRecords(id)
        if (Array.isArray(list)) all.push(...list)
      } catch (_) { /* 忽略无记录的event */ }
    }
    // 加载本地持久化
    try {
      const local = JSON.parse(window.localStorage.getItem('disasterguard_sign_records') ?? '[]')
      all.unshift(...local)
    } catch { /* ignore */ }
    records.value = all
  } finally {
    loading.value = false
  }
}

async function reloadAll() {
  await Promise.all([loadEvents(), loadRecords()])
}

function selectEvent(eventItem) {
  router.replace({ path: `/dashboard/confirmation-sign/${eventItem.id ?? eventItem.hazardCode}` })
}

function selectRecord(record) {
  selectedRecord.value = record
}

watch(
  () => route.params.id,
  async (id) => {
    if (!id) return
    try {
      const list = await dashboardApi.listSignRecords(id)
      if (Array.isArray(list) && list.length) {
        selectedRecord.value = list[0]
        tab.value = 'mine'
      }
    } catch { /* ignore */ }
  },
  { immediate: true },
)

function openNewSign() {
  selectedRecord.value = null
  dialog.value = true
}

async function submitSign(payload) {
  const eventId = selectedRecord.value?.eventId ?? route.params.id ?? events.value[0]?.id ?? `E${Date.now()}`
  const rec = {
    eventId,
    ...payload,
    initiator: operator.value.name,
  }
  try {
    const saved = await dashboardApi.createSign(rec)
    emit('notify', '已发起会签并通知会签人')
    dialog.value = false
    selectedRecord.value = saved
    // 显式塞回本地缓存
    try {
      const local = JSON.parse(window.localStorage.getItem('disasterguard_sign_records') ?? '[]')
      local.unshift(saved)
      window.localStorage.setItem('disasterguard_sign_records', JSON.stringify(local))
    } catch { /* ignore */ }
    await reloadAll()
  } catch (e) {
    emit('notify', e?.message ?? '发起会签失败')
  }
}

async function handleConfirm(signerId, opinion) {
  if (!selectedRecord.value) return
  // ⚠️ 严格使用 selectedRecord.eventId，不允许 route.params.id 兜底——
  // 路由 :id 可能是 signId（嵌套路由变化时），fallback 会把 signId 误用作 eventId。
  const eventId = selectedRecord.value.eventId
  if (!eventId) {
    emit('notify', '会签记录缺少 eventId，无法确认。请刷新页面后重试。')
    return
  }
  try {
    const r = await dashboardApi.confirmSign({
      eventId,
      signId: selectedRecord.value.id,
      signerId,
      opinion,
    })
    selectedRecord.value = r
    emit('notify', '已确认')
    await reloadAll()
  } catch (e) {
    emit('notify', e?.message ?? '确认失败')
  }
}

async function handleReject(signerId, opinion) {
  if (!selectedRecord.value) return
  const eventId = selectedRecord.value.eventId
  if (!eventId) {
    emit('notify', '会签记录缺少 eventId，无法驳回。请刷新页面后重试。')
    return
  }
  try {
    const r = await dashboardApi.rejectSign({
      eventId,
      signId: selectedRecord.value.id,
      signerId,
      opinion,
    })
    selectedRecord.value = r
    emit('notify', '已驳回，会签终止')
    await reloadAll()
  } catch (e) {
    emit('notify', e?.message ?? '驳回失败')
  }
}

function openTransfer() {
  transferTarget.value = ''
  transferReason.value = ''
  transferDialog.value = true
}

async function submitTransfer() {
  if (!transferTarget.value.trim() || !transferReason.value.trim()) {
    emit('notify', '请填写接收人和原因')
    return
  }
  try {
    await dashboardApi.transferEvent({
      eventId: route.params.id ?? selectedRecord.value?.eventId,
      toUser: transferTarget.value,
      reason: transferReason.value,
    })
    transferDialog.value = false
    emit('notify', '已转派')
    await reloadAll()
  } catch (e) {
    emit('notify', e?.message ?? '转派失败')
  }
}

const fmt = (iso) => iso ? new Date(iso).toLocaleString('zh-CN', { hour12: false }) : ''

onMounted(async () => {
  await Promise.all([reloadAll(), loadExperts()])
  tabTimer.value = window.setInterval(reloadAll, 15_000)
})
onBeforeUnmount(() => { if (tabTimer.value) window.clearInterval(tabTimer.value) })
</script>

<template>
  <div class="cs-page">
    <header class="head">
      <div>
      </div>
      <button v-if="canSign" class="primary" @click="openNewSign">
        <Plus :size="14" /> 发起会签
      </button>
    </header>

    <nav class="tabs">
      <button v-for="t in TABS" :key="t.id" :class="{ active: tab === t.id }" @click="tab = t.id">
        <component :is="t.icon" :size="13" /> {{ t.label }}
        <span v-if="t.id === 'inbox' && inboxRecords.length" class="badge">{{ inboxRecords.length }}</span>
        <span v-else-if="t.id === 'mine' && mySignRecords.length" class="badge">{{ mySignRecords.length }}</span>
      </button>
    </nav>

    <div class="grid">
      <!-- 左侧：事件 / 会签列表 -->
      <section class="panel list">
        <h3>队列</h3>
        <div v-if="loading" class="loading"><Loader2 :size="14" class="spin" /> 加载中…</div>
        <div v-else>
          <template v-if="tab === 'inbox'">
            <p class="hint">从 AI 巡检的疑似事件中挑选发起的会签；下面是当前"待二次确认"队列。</p>
            <ul>
              <li
                v-for="ev in draftsEvents"
                :key="ev.id ?? ev.hazardCode"
                class="item"
                :class="{ active: selectedRecord?.eventId === (ev.id ?? ev.hazardCode) }"
                @click="selectEvent(ev)"
              >
                <div class="ev-head">
                  <strong>{{ ev.name ?? ev.hazardCode }}</strong>
                  <span class="level" :data-level="ev.level">{{ ev.level }}</span>
                </div>
                <p class="meta">
                  <AlertTriangle :size="12" /> AI 置信度 {{ Math.round((ev.confidence ?? ev.aiScore ?? 0.85) * 100) }}% ·
                  <span>{{ ev.location ?? fmt(ev.discoveredAt) }}</span>
                </p>
              </li>
              <li v-if="!draftsEvents.length" class="empty">暂无待二次确认事件</li>
            </ul>
          </template>

          <template v-if="tab === 'mine'">
            <ul>
              <li
                v-for="r in mySignRecords"
                :key="r.id"
                class="item"
                :class="{ active: selectedRecord?.id === r.id }"
                @click="selectRecord(r)"
              >
                <div class="ev-head">
                  <strong>{{ r.eventId }}</strong>
                  <span class="state">{{ r.status }}</span>
                </div>
                <p class="meta">
                  <Clock :size="12" /> 发起于 {{ fmt(r.createdAt) }} ·
                  <span>{{ r.mode === 'SEQUENTIAL' ? '串行' : '并行' }} · {{ r.signers?.length ?? 0 }} 人</span>
                </p>
              </li>
              <li v-if="!mySignRecords.length" class="empty">你还没有发起过会签</li>
            </ul>
          </template>

          <template v-if="tab === 'cc'">
            <p class="hint">被抄送的会签（其他人发起的、你需要在期限内确认）。</p>
            <ul>
              <li v-for="r in records.filter((x) => x.cc?.includes(operator.name))" :key="r.id" class="item" :class="{ active: selectedRecord?.id === r.id }" @click="selectRecord(r)">
                <div class="ev-head">
                  <strong>{{ r.eventId }}</strong>
                  <span class="state">{{ r.status }}</span>
                </div>
                <p class="meta">{{ r.comment }}</p>
              </li>
              <li v-if="!records.filter((x) => x.cc?.includes(operator.name)).length" class="empty">暂无抄送记录</li>
            </ul>
          </template>
        </div>
      </section>

      <!-- 右侧：会签详情 -->
      <section class="panel detail">
        <h3>会签详情</h3>
        <div v-if="!selectedRecord" class="placeholder">
          选择左侧事件发起会签，或点击已有会签查看时间线。
        </div>
        <div v-else class="detail-body">
          <header class="d-head">
            <div>
              <h4>{{ selectedRecord.eventId }}</h4>
              <p>{{ selectedRecord.comment }}</p>
            </div>
            <span class="state">{{ selectedRecord.status }}</span>
          </header>
          <div class="d-actions">
            <button class="ghost" @click="openTransfer"><UserPlus :size="14" /> 转派</button>
          </div>
          <SignTimeline :sign="selectedRecord" />
          <div v-if="selectedRecord.status === 'RUNNING' && selectedRecord.signers?.some((s) => s.signerName === operator.name && s.status === 'PENDING')" class="sign-actions">
            <label>确认意见（可填）</label>
            <textarea v-model="opinionDraft" rows="2" placeholder="请留下你的确认意见" />
            <div class="btns">
              <button class="primary small" @click="handleConfirm(operator.name, opinionDraft); opinionDraft = ''">
                <Check :size="14" /> 我已确认
              </button>
              <button class="danger small" @click="handleReject(operator.name, opinionDraft); opinionDraft = ''">
                <X :size="14" /> 我要驳回
              </button>
            </div>
          </div>
        </div>
      </section>
    </div>

    <!-- 发起会签弹窗 -->
    <div v-if="dialog" class="modal-mask" @click.self="dialog = false">
      <div class="modal">
        <header><h3><Users :size="16" /> 发起会签</h3><button class="close" @click="dialog = false"><X :size="16" /></button></header>
        <SignForm
          :experts="expertPool"
          :mode-options="SIGN_MODE_OPTIONS"
          :record="selectedRecord"
          @submit="submitSign"
          @cancel="dialog = false"
        />
      </div>
    </div>

    <!-- 转派弹窗 -->
    <div v-if="transferDialog" class="modal-mask" @click.self="transferDialog = false">
      <div class="modal">
        <header><h3><ArrowLeftRight :size="16" /> 转派事件</h3><button class="close" @click="transferDialog = false"><X :size="16" /></button></header>
        <form @submit.prevent="submitTransfer" class="transfer-form">
          <label>接收人</label>
          <input v-model="transferTarget" placeholder="如 张三 / 现场指挥组" />
          <label>原因</label>
          <textarea v-model="transferReason" rows="3" placeholder="请说明转派原因" />
          <div class="actions">
            <button type="button" class="ghost" @click="transferDialog = false">取消</button>
            <button type="submit" class="primary"><Send :size="14" /> 确认转派</button>
          </div>
        </form>
      </div>
    </div>
  </div>
</template>

<style scoped>
.cs-page { padding: 22px 28px; display: flex; flex-direction: column; gap: 16px; height: 100%; overflow: hidden; }
.head { display: flex; justify-content: space-between; align-items: flex-end; border-bottom: 1px solid var(--dashboard-border, var(--c-brand-mist)); padding-bottom: 14px; }
.head h2 { display: inline-flex; align-items: center; gap: 8px; margin: 0; font-size: 20px; }
.head p { margin: 4px 0 0; color: var(--dashboard-muted, var(--c-text-2)); font-size: 13px; }
.primary {
  background: linear-gradient(135deg, var(--c-brand-tint), var(--c-brand-hover)); color: white;
  border: none; padding: 8px 16px; border-radius: var(--r-sm);
  display: inline-flex; align-items: center; gap: 6px; cursor: pointer; font-size: 14px;
}
.primary.small { padding: 6px 12px; font-size: 12px; }
.danger { background: linear-gradient(135deg, var(--c-danger), var(--c-danger)); color: white; border: none; padding: 6px 12px; border-radius: var(--r-sm); font-size: 12px; display: inline-flex; align-items: center; gap: 4px; cursor: pointer; }
.ghost { background: transparent; color: var(--dashboard-muted, var(--c-ink-1)); border: 1px solid var(--dashboard-border, var(--c-brand-tint)); padding: 6px 12px; border-radius: var(--r-sm); font-size: 12px; display: inline-flex; align-items: center; gap: 4px; cursor: pointer; }
.tabs { display: flex; gap: 6px; border-bottom: 1px solid var(--dashboard-border, var(--c-brand-mist)); }
.tabs button {
  display: inline-flex; align-items: center; gap: 4px;
  background: transparent; border: none;
  padding: 8px 16px; cursor: pointer;
  color: var(--dashboard-muted, var(--c-text-3));
  border-bottom: 2px solid transparent; font-size: 14px;
}
.tabs button.active { color: var(--c-brand-deep); border-color: var(--c-brand-deep); font-weight: 600; }
.tabs .badge { background: var(--c-danger); color: white; padding: 0 6px; border-radius: var(--r-pill); font-size: 11px; margin-left: 4px; }
.grid { display: grid; grid-template-columns: 1.1fr 1.4fr; gap: 18px; min-height: 0; flex: 1; }
@media (max-width: 980px) { .grid { grid-template-columns: 1fr; } }
.panel {
  background: var(--dashboard-card-bg, var(--c-surface));
  border: 1px solid var(--dashboard-border, var(--c-brand-mist)); border-radius: var(--r-lg);
  padding: 16px 18px; display: flex; flex-direction: column; gap: 10px;
  box-shadow: 0 4px 12px rgba(0,0,0,0.04); overflow: auto;
}
.panel h3 { margin: 0; font-size: 16px; border-bottom: 1px solid var(--dashboard-border, var(--c-brand-mist)); padding-bottom: 6px; }
.loading, .empty, .placeholder { color: var(--dashboard-muted, var(--c-text-3)); font-size: 13px; padding: 12px; text-align: center; }
.hint { font-size: 12px; color: var(--dashboard-muted, var(--c-text-2)); margin: 0 0 6px; }
ul { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 6px; }
.item {
  border: 1px solid var(--dashboard-border, var(--c-brand-mist)); border-radius: var(--r-md);
  padding: 10px 12px; cursor: pointer; background: var(--dashboard-card-bg, var(--c-surface));
}
.item:hover { border-color: var(--c-brand-tint); }
.item.active { border-color: var(--c-brand-deep); background: linear-gradient(135deg, var(--sys-blue-soft), var(--sys-blue-soft)); }
.ev-head { display: flex; justify-content: space-between; align-items: center; }
.level { font-size: 11px; padding: 1px 8px; border-radius: var(--r-pill); background: var(--c-danger-bg); color: var(--c-danger-strong); }
.level[data-level="黄色"] { background: var(--c-warning-bg); color: var(--c-warning-ink); }
.level[data-level="橙色"] { background: var(--c-warning-bg); color: var(--c-warning-ink); }
.state {
  font-size: 11px; padding: 1px 8px; border-radius: var(--r-pill);
  background: var(--dashboard-muted-bg, var(--c-brand-mist)); color: var(--dashboard-muted, var(--c-ink-1));
}
.meta { margin: 4px 0 0; font-size: 12px; color: var(--dashboard-muted, var(--c-text-3)); display: flex; align-items: center; gap: 4px; flex-wrap: wrap; }
.detail-body { display: flex; flex-direction: column; gap: 12px; }
.d-head { display: flex; justify-content: space-between; align-items: center; }
.d-head h4 { margin: 0; font-size: 18px; }
.d-head p { margin: 4px 0 0; color: var(--dashboard-muted, var(--c-text-2)); font-size: 13px; }
.d-actions { display: flex; gap: 6px; }
.sign-actions { display: flex; flex-direction: column; gap: 6px; border-top: 1px dashed var(--dashboard-border, var(--c-brand-tint)); padding-top: 10px; }
.sign-actions label { font-size: 12px; color: var(--dashboard-muted, var(--c-text-2)); }
.sign-actions textarea {
  border: 1px solid var(--dashboard-border, var(--c-brand-soft)); border-radius: var(--r-sm);
  padding: 8px 10px; font-size: 13px; font-family: inherit;
}
.sign-actions .btns { display: flex; gap: 6px; }

/* 弹窗 */
.modal-mask {
  position: fixed; inset: 0; background: var(--c-overlay); z-index: 999;
  display: flex; align-items: center; justify-content: center;
}
.modal {
  width: min(640px, 92vw); max-height: 90vh; overflow: auto;
  background: var(--dashboard-card-bg, var(--c-surface)); border-radius: var(--r-lg);
  box-shadow: 0 24px 48px rgba(0,0,0,0.18); padding: 18px 22px;
}
.modal header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.modal header h3 { margin: 0; font-size: 17px; display: inline-flex; align-items: center; gap: 6px; }
.close { background: none; border: none; cursor: pointer; color: var(--dashboard-muted, var(--c-text-3)); }
.transfer-form { display: flex; flex-direction: column; gap: 8px; }
.transfer-form label { font-size: 13px; color: var(--dashboard-muted, var(--c-ink-1)); }
.transfer-form input, .transfer-form textarea {
  border: 1px solid var(--dashboard-border, var(--c-brand-soft)); border-radius: var(--r-sm);
  padding: 8px 10px; font-size: 13px; font-family: inherit;
}
.transfer-form .actions { display: flex; justify-content: flex-end; gap: 6px; margin-top: 8px; }
.spin { animation: rotate 1s linear infinite; }
@keyframes rotate { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
</style>
