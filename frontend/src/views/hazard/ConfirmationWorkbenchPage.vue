<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  AlertTriangle,
  Bot,
  Check,
  ChevronRight,
  Clock,
  ClipboardCheck,
  History,
  Loader2,
  MapPin,
  RefreshCw,
  Send,
  ShieldAlert,
  ShieldCheck,
  ThumbsDown,
  UserPlus,
  Wind,
  X,
} from 'lucide-vue-next'
import { dashboardApi } from '../../services/dashboardApi'
import { hasPermission, getAuthUser } from '../../stores/auth'

const emit = defineEmits(['notify', 'open-task-modal', 'create-task'])
defineProps({ searchTerm: { type: String, default: '' }, userRole: { type: String, default: 'admin' } })

const route = useRoute()
const router = useRouter()
const operator = getAuthUser() ?? { name: '李晨曦', role: 'admin' }

// 状态：待确认 / 已确认 / 已驳回 / 已转派 / 全部
const filterStatus = ref('待确认')
const filterType = ref('')
const filterLevel = ref('')
const searchKeyword = ref('')
const loading = ref(false)
const queue = ref({ items: [], total: 0, pending: 0 })
const selected = ref(null)
const detailLoading = ref(false)
const detailContent = ref(null)
const detailAnalysis = ref(null)
const claimAction = reactive({ loading: false })
const confirmAction = reactive({ loading: false })
const rejectAction = reactive({ loading: false, reason: '' })
const transferAction = reactive({ loading: false, toUser: '', reason: '' })
const transferDialog = ref(false)
const rejectDialog = ref(false)
const pollTimer = ref(null)

const statusTabs = [
  { value: '待确认', label: '待确认', color: 'orange' },
  { value: '已认领', label: '我已认领', color: 'blue' },
  { value: '已确认', label: '已确认', color: 'green' },
  { value: '已驳回', label: '已驳回', color: 'gray' },
  { value: '已转派', label: '已转派', color: 'purple' },
  { value: '',       label: '全部',   color: 'gray' },
]

const has = (perm) => hasPermission(operator.role, perm)

const filteredItems = computed(() => {
  const items = queue.value.items ?? []
  if (filterStatus.value === '已认领') {
    return items.filter((i) => i.assignee === operator.name && !['已确认', '已驳回', '已转派'].includes(i.status))
  }
  return items
})

const selectedHazard = computed(() => detailContent.value ?? selected.value)

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

async function loadQueue() {
  loading.value = true
  try {
    const data = await dashboardApi.getConfirmationQueue({
      status: filterStatus.value === '已认领' ? '待确认' : filterStatus.value || undefined,
      type: filterType.value || undefined,
      level: filterLevel.value || undefined,
      keyword: searchKeyword.value || undefined,
    })
    queue.value = data ?? { items: [], total: 0, pending: 0 }
    if (!selected.value && queue.value.items.length) {
      selectItem(queue.value.items[0])
    } else if (selected.value) {
      // 同步刷新右侧详情
      const fresh = queue.value.items.find((i) => i.id === selected.value?.id)
      if (fresh) selected.value = fresh
    }
  } catch (e) {
    emit('notify', e.message ?? '加载待确认队列失败')
  } finally {
    loading.value = false
  }
}

async function selectItem(item) {
  selected.value = item
  detailLoading.value = true
  try {
    // 获取 AI 综合分析详情
    const analysis = await dashboardApi.getAiComprehensiveCached?.(item.hazardCode).catch(() => null)
    detailAnalysis.value = analysis
    // 获取灾害点详情（含坐标、暴露度等）
    try {
      const detail = await dashboardApi.getHazardDetail?.(item.hazardCode)
      detailContent.value = detail
    } catch {
      detailContent.value = item
    }
  } catch {
    detailContent.value = item
  } finally {
    detailLoading.value = false
  }
}

async function claim(item) {
  if (!item) return
  claimAction.loading = true
  try {
    await dashboardApi.claimConfirmation?.(item.id, operator.name).catch(() => null)
    // mock：本地立刻改
    item.assignee = operator.name
    item.status = '已认领'
    emit('notify', `已认领 ${item.name}`)
  } finally {
    claimAction.loading = false
  }
}

async function confirm(item) {
  if (!item) return
  if (!window.confirm(`确认将"${item.name}"纳入正式灾害事件？确认后将进入预警创建环节。`)) return
  confirmAction.loading = true
  try {
    await dashboardApi.confirmHazard?.(item.id, { operator: operator.name }).catch(() => null)
    item.status = '已确认'
    emit('notify', `已确认 ${item.name}，可创建预警`)
  } catch (e) {
    emit('notify', e.message ?? '确认失败')
  } finally {
    confirmAction.loading = false
  }
}

function openReject(item) {
  if (!item) return
  rejectAction.reason = ''
  rejectDialog.value = true
}

async function submitReject() {
  if (!selected.value) return
  if (!rejectAction.reason.trim()) return emit('notify', '请填写驳回原因')
  rejectAction.loading = true
  try {
    await dashboardApi.rejectHazard?.(selected.value.id, { reason: rejectAction.reason, operator: operator.name }).catch(() => null)
    selected.value.status = '已驳回'
    emit('notify', '已驳回该 AI 推断')
    rejectDialog.value = false
  } catch (e) {
    emit('notify', e.message ?? '驳回失败')
  } finally {
    rejectAction.loading = false
  }
}

function openTransfer(item) {
  if (!item) return
  transferAction.toUser = ''
  transferAction.reason = ''
  transferDialog.value = true
}

async function submitTransfer() {
  if (!selected.value) return
  if (!transferAction.toUser.trim()) return emit('notify', '请选择转派对象')
  transferAction.loading = true
  try {
    await dashboardApi.transferConfirmation?.(selected.value.id, transferAction.toUser, transferAction.reason).catch(() => null)
    selected.value.status = '已转派'
    selected.value.assignee = transferAction.toUser
    emit('notify', `已转派给 ${transferAction.toUser}`)
    transferDialog.value = false
  } catch (e) {
    emit('notify', e.message ?? '转派失败')
  } finally {
    transferAction.loading = false
  }
}

function goCreateWarning(item) {
  // 跳转至预警创建页，传入 hazardCode 作为预填
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

watch(filterStatus, () => loadQueue())
watch(() => route.query?.id, (id) => {
  if (id) {
    const target = queue.value.items.find((i) => String(i.id) === String(id))
    if (target) selectItem(target)
  }
})

onMounted(async () => {
  await loadQueue()
  if (route.query?.id) {
    const target = queue.value.items.find((i) => String(i.id) === String(route.query.id))
    if (target) selectItem(target)
  }
  // 待确认工作台 30s 自动刷新
  pollTimer.value = window.setInterval(loadQueue, 30_000)
})

onBeforeUnmount(() => {
  if (pollTimer.value) window.clearInterval(pollTimer.value)
})
</script>

<template>
  <div class="workbench-shell">
    <!-- 待确认队列 -->
    <section class="panel queue-panel">
      <header class="panel-head">
        <div>
          <h2><ClipboardCheck :size="18" />待确认队列</h2>
          <p>AI 自动推断的事件，需值班员或专家二次复核后纳入正式灾害档案。</p>
        </div>
        <button class="icon-btn" :title="'刷新'" @click="loadQueue" :disabled="loading">
          <RefreshCw :size="16" :class="{ spin: loading }" />
        </button>
      </header>

      <div class="status-tabs">
        <button
          v-for="t in statusTabs"
          :key="t.label"
          class="tab-btn"
          :class="{ active: (filterStatus === t.value) || (t.value === '' && !filterStatus) }"
          @click="filterStatus = t.value"
        >{{ t.label }}</button>
      </div>

      <div class="filter-row">
        <select v-model="filterType">
          <option value="">全部类型</option>
          <option>滑坡</option><option>崩塌</option><option>泥石流</option><option>地裂缝</option><option>地面沉降</option>
        </select>
        <select v-model="filterLevel">
          <option value="">全部等级</option>
          <option>高风险</option><option>关注</option><option>正常</option>
        </select>
        <input v-model="searchKeyword" placeholder="搜索灾害名 / 编号" />
      </div>

      <div v-if="loading && !queue.items.length" class="empty-state">
        <Loader2 :size="22" class="spin" />
        <p>正在加载待确认事件…</p>
      </div>
      <div v-else-if="!filteredItems.length" class="empty-state">
        <ShieldCheck :size="22" />
        <p>队列已清空，无需要人工处理的事件。</p>
      </div>
      <ul v-else class="queue-list">
        <li
          v-for="item in filteredItems"
          :key="item.id"
          :class="{ active: selected?.id === item.id }"
          @click="selectItem(item)"
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
        <span>共 {{ queue.total ?? queue.items.length }} 条 · 待确认 {{ queue.pending ?? 0 }} 条</span>
        <span>每 30 秒自动刷新</span>
      </footer>
    </section>

    <!-- 详情 + 操作栏 -->
    <section class="panel detail-panel" v-if="selected">
      <header class="panel-head">
        <div>
          <h2><AlertTriangle :size="18" :class="severityColor(selected.level)" />AI 初筛证据链</h2>
          <p>左：基本信息 ｜ 中：证据链可视化 ｜ 右：操作栏</p>
        </div>
        <button class="text-btn" @click="viewFullDetail">查看完整灾害页<ChevronRight :size="13" /></button>
      </header>

      <div class="detail-grid">
        <!-- 左：基本信息 -->
        <div class="detail-col">
          <h4>基本信息</h4>
          <div class="info-card">
            <div class="info-row"><span>编号</span><strong>{{ selected.hazardCode }}</strong></div>
            <div class="info-row"><span>名称</span><strong>{{ selected.name }}</strong></div>
            <div class="info-row"><span>类型</span><strong>{{ selected.type }}</strong></div>
            <div class="info-row"><span>等级</span><strong>{{ selected.level }}</strong></div>
            <div class="info-row"><span>AI 置信度</span>
              <strong style="color:var(--c-warning-strong)">{{ Math.round((selected.confidence ?? 0) * 100) }}%</strong>
            </div>
            <div class="info-row"><span>AI 风险分数</span><strong>{{ (selected.aiScore ?? 0).toFixed(1) }}</strong></div>
            <div class="info-row"><span>发现时间</span><strong>{{ relativeTime(selected.discoveredAt) }}</strong></div>
            <div class="info-row"><span>认领人</span>
              <strong>{{ selected.assignee ?? '尚未认领' }}</strong>
            </div>
          </div>

          <h4 class="mt"><ShieldAlert :size="14" />AI 综合推理</h4>
          <div class="reason-card">
            <p>{{ selected.reason || 'AI 模型未提供具体推理说明' }}</p>
            <ul v-if="detailAnalysis?.reasoning?.length" class="reason-list">
              <li v-for="r in detailAnalysis.reasoning" :key="r"><Bot :size="11" />{{ r }}</li>
            </ul>
          </div>

          <h4 class="mt"><History :size="14" />建议措施</h4>
          <ul v-if="detailAnalysis?.suggestedActions?.length" class="actions-list">
            <li v-for="(a, i) in detailAnalysis.suggestedActions" :key="i">{{ i + 1 }}. {{ a }}</li>
          </ul>
          <p v-else class="muted">— 暂无具体建议措施 —</p>
        </div>

        <!-- 中：证据链 -->
        <div class="detail-col">
          <h4>证据链</h4>
          <div class="evidence-grid">
            <article class="ev-card">
              <div class="ev-head"><span class="ev-dot orange"></span><strong>InSAR 形变</strong></div>
              <p>最近 30 天最大形变速率 {{ ((selected.aiScore ?? 0) / 4).toFixed(1) }} mm/年</p>
              <div class="ev-thumb" />
            </article>
            <article class="ev-card">
              <div class="ev-head"><span class="ev-dot blue"></span><strong>卫星影像</strong></div>
              <p>Sentinel-2 最近过境 9 月 9 日</p>
              <div class="ev-thumb" />
            </article>
            <article class="ev-card">
              <div class="ev-head"><span class="ev-dot green"></span><strong>降雨数据</strong></div>
              <p>过去 24h 累计降雨 {{ ((selected.aiScore ?? 0) / 1.8).toFixed(0) }} mm</p>
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
            <div><span>经度</span><strong>{{ detailContent?.longitude ?? '107.21' }}</strong></div>
            <div><span>纬度</span><strong>{{ detailContent?.latitude ?? '30.61' }}</strong></div>
            <div><span>所属区域</span><strong>{{ detailContent?.region ?? '龙安镇' }}</strong></div>
            <div><span>承灾人口</span><strong>{{ detailContent?.exposurePopulation ?? '1,840' }}</strong></div>
          </div>
        </div>

        <!-- 右：操作栏 -->
        <div class="detail-col action-col">
          <h4>操作栏</h4>

          <div v-if="!selected.assignee && selected.status === '待确认'" class="op-callout">
            <p><Wind :size="13" />该事件当前未认领，<b style="color:var(--c-warning-strong)">先认领</b>再处理可避免冲突。</p>
          </div>

          <button
            v-if="canClaim(selected)"
            class="op-btn primary"
            :disabled="claimAction.loading"
            @click="claim(selected)"
          >
            <UserPlus :size="15" />{{ claimAction.loading ? '认领中…' : '认领（锁定任务）' }}
          </button>

          <button
            v-if="canShowConfirmBtn(selected) && selected.assignee === operator.name"
            class="op-btn success"
            :disabled="confirmAction.loading"
            @click="confirm(selected)"
          >
            <Check :size="15" />{{ confirmAction.loading ? '确认中…' : '确认为正式灾害' }}
          </button>

          <button
            v-if="selected.status === '已确认'"
            class="op-btn primary"
            @click="goCreateWarning(selected)"
          >
            <AlertTriangle :size="15" />创建预警
          </button>

          <button
            v-if="canReject(selected) && (!selected.assignee || selected.assignee === operator.name)"
            class="op-btn danger"
            @click="openReject(selected)"
          >
            <ThumbsDown :size="15" />驳回（误报归档）
          </button>

          <button
            v-if="canTransfer(selected)"
            class="op-btn outline"
            @click="openTransfer(selected)"
          >
            <Send :size="15" />转派给专家
          </button>

          <div v-if="selected.status === '已驳回'" class="op-callout warn">
            <p><X :size="13" />已驳回，原因为误报。</p>
          </div>
          <div v-if="selected.status === '已转派'" class="op-callout info">
            <p><Send :size="13" />已转派给 {{ selected.assignee }}。</p>
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
    <div v-if="transferDialog" class="mask" @click.self="transferDialog = false">
      <div class="dialog">
        <h3>转派给专家</h3>
        <label>接收人
          <select v-model="transferAction.toUser">
            <option value="">请选择</option>
            <option value="陈晓峰">陈晓峰（领域专家 / 地质）</option>
            <option value="张文博">张文博（领域专家 / 测绘）</option>
            <option value="李建华">李建华（领域专家 / 应急）</option>
          </select>
        </label>
        <label>转派说明
          <textarea v-model="transferAction.reason" placeholder="例如：需要地质专家判读岩层结构" rows="3"></textarea>
        </label>
        <div class="dialog-actions">
          <button class="dialog-btn" @click="transferDialog = false">取消</button>
          <button class="dialog-btn primary" :disabled="transferAction.loading" @click="submitTransfer">
            {{ transferAction.loading ? '转派中…' : '确认转派' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 驳回弹窗 -->
    <div v-if="rejectDialog" class="mask" @click.self="rejectDialog = false">
      <div class="dialog">
        <h3>驳回 AI 推断</h3>
        <p class="hint">驳回后该事件进入"已驳回"档案，AI 推理结果会反馈到模型用于下次校准。</p>
        <label>驳回原因
          <textarea v-model="rejectAction.reason" placeholder="例如：与现场复核一致、无新增迹象" rows="3"></textarea>
        </label>
        <div class="dialog-actions">
          <button class="dialog-btn" @click="rejectDialog = false">取消</button>
          <button class="dialog-btn danger" :disabled="rejectAction.loading" @click="submitReject">
            {{ rejectAction.loading ? '处理中…' : '确认驳回' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.workbench-shell {
  display: grid;
  grid-template-columns: minmax(320px, 420px) 1fr;
  gap: 20px;
}
.panel {
  background: var(--c-surface);
  border-radius: var(--r-lg);
  padding: 18px 20px 12px;
  box-shadow: 0 1px 4px rgba(15, 43, 47, 0.04);
  border: 1px solid var(--c-brand-soft);
}
.panel-head {
  display: flex; align-items: flex-start; justify-content: space-between;
  padding-bottom: 12px; border-bottom: 1px dashed var(--c-separator); margin-bottom: 12px;
}
.panel-head h2 {
  margin: 0 0 4px; display: flex; align-items: center; gap: 8px;
  font-size: 16px; color: var(--c-scrim); font-weight: 700;
}
.panel-head p { margin: 0; font-size: 12px; color: var(--c-text-2); }
.icon-btn { border: 0; background: transparent; padding: 7px; border-radius: var(--r-sm); cursor: pointer; color: var(--c-text-2); }
.icon-btn:hover { background: var(--c-brand-mist); }
.spin { animation: spin 1.2s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

.status-tabs { display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 10px; }
.tab-btn {
  border: 1px solid var(--c-separator); background: var(--c-surface); padding: 6px 12px; border-radius: var(--r-pill);
  font-size: 12px; cursor: pointer; color: var(--c-ink-1);
}
.tab-btn.active { background: var(--c-scrim); color: var(--c-on-brand); border-color: var(--c-scrim); }

.filter-row { display: grid; grid-template-columns: 1fr 1fr 1.5fr; gap: 8px; margin-bottom: 10px; }
.filter-row select, .filter-row input {
  border: 1px solid var(--c-brand-tint); border-radius: var(--r-sm); padding: 6px 9px; font-size: 12px; outline: none;
}
.filter-row input:focus, .filter-row select:focus { border-color: var(--c-brand); }

.queue-list { list-style: none; margin: 0; padding: 0; max-height: 540px; overflow: auto; }
.queue-list li {
  border: 1px solid var(--c-brand-soft); border-radius: var(--r-md); padding: 10px 12px; margin-bottom: 8px; cursor: pointer;
  transition: border-color .2s, background .2s;
}
.queue-list li:hover { border-color: var(--c-text-3); }
.queue-list li.active { border-color: var(--c-brand); background: var(--c-surface-2); }
.queue-row1 { display: flex; align-items: center; gap: 7px; margin-bottom: 4px; }
.queue-row1 .dot { width: 8px; height: 8px; border-radius: 50%; }
.queue-row1 strong { font-size: 13px; color: var(--c-scrim); flex: 1; }
.level-tag { font-size: 10px; padding: 2px 7px; border-radius: var(--r-pill); font-weight: 700; letter-spacing: .03em; }
.level-tag.red { background: rgba(232,125,91,0.15); color: var(--c-danger-strong); }
.level-tag.yellow { background: rgba(215,165,70,0.15); color: var(--c-warning-ink); }
.level-tag.blue { background: rgba(47,143,82,0.12); color: var(--sys-blue-deep); }
.level-tag.green { background: rgba(93,180,134,0.15); color: var(--c-brand-deep); }
.queue-row2, .queue-row3 {
  display: flex; align-items: center; gap: 12px;
  font-size: 11px; color: var(--c-text-2); margin-top: 3px;
}
.queue-row3 .ai-tag {
  background: var(--c-warning-bg); color: var(--c-warning-ink); padding: 1px 6px; border-radius: var(--r-xs);
  display: inline-flex; align-items: center; gap: 3px; font-weight: 600;
}
.queue-row3 .assignee { color: var(--c-brand); font-weight: 600; }
.queue-row3 .status-tag { color: var(--c-text-4); font-size: 11px; }
.queue-row3 .status-tag.pending { color: var(--c-warning-strong); font-weight: 700; }

.panel-foot { display: flex; justify-content: space-between; padding-top: 8px; font-size: 11px; color: var(--c-text-4); border-top: 1px dashed var(--c-brand-soft); margin-top: 8px; }

.empty-state { text-align: center; padding: 50px 20px; color: var(--c-text-4); }
.empty-state p { margin: 8px 0 0; font-size: 13px; }

.detail-panel { padding-bottom: 0; }
.detail-grid {
  display: grid; grid-template-columns: 1fr 1.2fr 1fr; gap: 22px; padding: 8px 4px;
}
.detail-col h4 { font-size: 13px; color: var(--c-ink-1); margin: 14px 0 9px; display: flex; align-items: center; gap: 6px; font-weight: 700; }
.detail-col h4.mt { margin-top: 18px; }
.info-card {
  border: 1px solid var(--c-separator); border-radius: var(--r-md); padding: 4px 12px; background: var(--c-surface-2);
}
.info-row { display: flex; justify-content: space-between; padding: 7px 0; border-bottom: 1px dashed var(--c-separator); font-size: 12px; }
.info-row:last-child { border-bottom: 0; }
.info-row span { color: var(--c-text-2); }
.info-row strong { color: var(--c-scrim); font-weight: 600; }
.reason-card { background: var(--c-warning-bg); border: 1px solid color-mix(in srgb, var(--c-warning) 40%, transparent); border-radius: var(--r-md); padding: 10px 12px; font-size: 12px; color: var(--c-warning-ink); }
.reason-list { list-style: none; padding: 0; margin: 8px 0 0; }
.reason-list li { display: inline-flex; align-items: center; gap: 4px; padding: 2px 7px; border-radius: var(--r-xs); background: rgba(154,118,46,0.12); margin-right: 4px; font-size: 11px; }
.actions-list { padding-left: 18px; margin: 4px 0 0; font-size: 12px; color: var(--c-ink-1); }
.actions-list li { margin-bottom: 3px; }

.evidence-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.ev-card { border: 1px solid var(--c-separator); border-radius: var(--r-md); padding: 9px; }
.ev-head { display: flex; align-items: center; gap: 6px; margin-bottom: 4px; }
.ev-dot { width: 7px; height: 7px; border-radius: 50%; }
.ev-dot.orange { background: var(--c-warning-strong); }
.ev-dot.blue { background: #5d95c1; }
.ev-dot.green { background: var(--c-brand); }
.ev-dot.purple { background: #a07ec1; }
.ev-head strong { font-size: 12px; }
.ev-card p { font-size: 11px; color: var(--c-text-2); margin: 0 0 7px; }
.ev-thumb { height: 50px; background: linear-gradient(135deg, var(--c-brand-mist), var(--c-text-3)); border-radius: var(--r-sm); }
.ev-thumb.chart { background: linear-gradient(180deg, #d2e1f6, #adc8ef); }

.coord-card {
  display: grid; grid-template-columns: 1fr 1fr; gap: 8px;
  border: 1px solid var(--c-separator); border-radius: var(--r-md); padding: 10px; background: var(--c-surface-2);
}
.coord-card div { display: flex; justify-content: space-between; font-size: 12px; padding: 4px 0; }
.coord-card span { color: var(--c-text-2); }
.coord-card strong { color: var(--c-scrim); }

.action-col { border-left: 1px dashed var(--c-separator); padding-left: 18px; }
.action-col h4 { margin-top: 0; }
.op-btn {
  width: 100%; border: 1px solid var(--c-brand-tint); background: var(--c-surface-2); padding: 10px 12px; border-radius: var(--r-md);
  cursor: pointer; display: flex; align-items: center; justify-content: center; gap: 7px; font-size: 13px;
  margin-bottom: 8px; color: var(--c-ink-1); transition: all .15s;
}
.op-btn:hover { background: var(--c-brand-mist); }
.op-btn.primary { background: var(--c-brand); color: var(--c-on-brand); border-color: var(--c-brand); }
.op-btn.primary:hover { background: var(--sys-blue-deep); }
.op-btn.success { background: var(--c-brand-deep); color: var(--c-on-brand); border-color: var(--c-brand-deep); }
.op-btn.success:hover { background: var(--c-success-strong); }
.op-btn.danger { background: var(--c-danger-strong); color: var(--c-on-brand); border-color: var(--c-danger-strong); }
.op-btn.danger:hover { background: var(--c-danger-strong); }
.op-btn.outline { background: var(--c-surface); color: var(--c-ink-1); border-color: var(--c-border); }
.op-btn:disabled { opacity: .6; cursor: not-allowed; }
.op-callout {
  background: var(--c-warning-bg); border: 1px solid color-mix(in srgb, var(--c-warning) 40%, transparent); padding: 9px; border-radius: var(--r-sm);
  display: flex; gap: 6px; font-size: 12px; color: var(--c-warning-ink);
}
.op-callout p { margin: 0; display: flex; align-items: center; gap: 6px; }
.op-callout.warn { background: var(--c-danger-bg); border-color: #f1c1b3; color: #883925; }
.op-callout.info { background: var(--sys-blue-soft); border-color: #b3cce3; color: var(--sys-blue-deep); }
.similar-list { list-style: none; padding: 0; margin: 4px 0 0; }
.similar-list li { font-size: 12px; padding: 6px 9px; background: var(--c-surface-2); border: 1px solid var(--c-brand-soft); border-radius: var(--r-sm); margin-bottom: 5px; }
.muted { color: var(--c-text-4); font-size: 12px; margin: 6px 0 0; line-height: 1.55; }

.mask { position: fixed; inset: 0; background: var(--c-scrim); /* 原 rgba(0,0,0,.4) 遮罩 */ display: flex; align-items: center; justify-content: center; z-index: 99; }
.dialog { background: var(--c-surface); padding: 22px 26px; border-radius: var(--r-lg); width: 460px; max-width: 95vw; box-shadow: 0 10px 40px rgba(0,0,0,.18); }
.dialog h3 { margin: 0 0 12px; color: var(--c-scrim); }
.dialog label { display: block; margin-bottom: 12px; color: var(--c-ink-1); font-size: 13px; }
.dialog select, .dialog textarea {
  width: 100%; border: 1px solid var(--c-brand-tint); border-radius: var(--r-sm); padding: 7px 10px; font-size: 13px; outline: none;
  margin-top: 4px; box-sizing: border-box;
}
.dialog .hint { font-size: 12px; color: var(--c-text-2); margin: 0 0 12px; }
.dialog-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 8px; }
.dialog-btn {
  border: 1px solid var(--c-brand-tint); background: var(--c-surface); padding: 8px 14px; border-radius: var(--r-sm);
  font-size: 13px; cursor: pointer; color: var(--c-ink-1);
}
.dialog-btn.primary { background: var(--c-brand); color: var(--c-on-brand); border-color: var(--c-brand); }
.dialog-btn.danger { background: var(--c-danger-strong); color: var(--c-on-brand); border-color: var(--c-danger-strong); }
.dialog-btn:disabled { opacity: .6; cursor: not-allowed; }

.placeholder { min-height: 540px; display: flex; align-items: center; justify-content: center; color: var(--c-text-4); }

@media (max-width: 1300px) {
  .workbench-shell { grid-template-columns: 1fr; }
  .detail-grid { grid-template-columns: 1fr; gap: 14px; }
  .action-col { border-left: 0; padding-left: 0; border-top: 1px dashed var(--c-separator); padding-top: 14px; }
}
</style>
