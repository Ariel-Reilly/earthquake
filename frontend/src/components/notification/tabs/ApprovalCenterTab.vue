<script setup>
/**
 * 审批中心 Tab（抽屉版）
 * 
 * 精简版本：
 * - 移除左侧筛选栏和统计图表
 * - 简化为：搜索 + 简单过滤 + 列表
 * - 列表项点击跳转到 /dashboard/approvals
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  Check,
  Loader2,
  Search,
  ShieldAlert,
  ShieldCheck,
  X,
} from 'lucide-vue-next'
import { dashboardApi } from '../../../services/dashboardApi'

const props = defineProps({
  compact: { type: Boolean, default: false },
})

const emit = defineEmits(['notify', 'badge-refresh'])
const router = useRouter()

const loading = ref(false)
const approvals = ref({ items: [], total: 0 })
const filter = ref({ status: 'pending' })
const search = ref('')
let pollTimer = null

const statusFilters = [
  { value: 'pending', label: '待审', icon: ShieldAlert },
  { value: 'approved', label: '已通过', icon: Check },
  { value: 'rejected', label: '已驳回', icon: X },
  { value: 'all', label: '全部', icon: ShieldCheck },
]

const filteredItems = computed(() => {
  let list = approvals.value.items ?? []
  if (filter.value.status !== 'all') {
    list = list.filter((a) => a.status === filter.value.status)
  }
  if (search.value.trim()) {
    const k = search.value.trim().toLowerCase()
    list = list.filter((a) =>
      (a.title ?? '').toLowerCase().includes(k) ||
      (a.applicant ?? '').toLowerCase().includes(k)
    )
  }
  return list
})

const unreadCount = computed(() =>
  (approvals.value.items ?? []).filter((a) => a.status === 'pending').length
)

const statusLabel = (status) => ({
  pending: '待审批',
  approved: '已通过',
  rejected: '已驳回',
})[status] ?? status

const statusColor = (status) => ({
  pending: 'var(--c-danger)',
  approved: 'var(--c-success)',
  rejected: 'var(--c-warning)',
})[status] ?? 'var(--c-text-3)'

async function loadApprovals() {
  loading.value = true
  try {
    const data = await dashboardApi.listApprovals?.({
      status: filter.value.status !== 'all' ? filter.value.status : undefined,
      keyword: search.value || undefined,
    })
    approvals.value = data ?? { items: [], total: 0 }
  } catch (e) {
    emit('notify', e.message ?? '加载审批失败')
  } finally {
    loading.value = false
  }
}

async function approve(id, e) {
  e?.stopPropagation()
  try {
    await dashboardApi.decideApproval?.(id, 'approved')
    const a = approvals.value.items.find((x) => x.id === id)
    if (a) a.status = 'approved'
    emit('badge-refresh')
    emit('notify', '已通过')
    await loadApprovals()
  } catch (e) {
    emit('notify', e.message ?? '操作失败')
  }
}

async function reject(id, e) {
  e?.stopPropagation()
  try {
    await dashboardApi.decideApproval?.(id, 'rejected')
    const a = approvals.value.items.find((x) => x.id === id)
    if (a) a.status = 'rejected'
    emit('badge-refresh')
    emit('notify', '已驳回')
    await loadApprovals()
  } catch (e) {
    emit('notify', e.message ?? '操作失败')
  }
}

function openApprovalDetail(approval) {
  router.push(`/dashboard/approvals?id=${approval.id}`)
}

watch(filter, loadApprovals, { deep: true })
let searchTimer
watch(search, () => {
  window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(loadApprovals, 300)
})

onMounted(async () => {
  await loadApprovals()
  pollTimer = window.setInterval(loadApprovals, 45000)
})

onBeforeUnmount(() => {
  if (pollTimer) window.clearInterval(pollTimer)
  window.clearTimeout(searchTimer)
})
</script>

<template>
  <div class="approval-tab">
    <!-- 顶部操作栏 -->
    <div class="approval-toolbar">
      <div class="search-box">
        <Search size="14" />
        <input
          v-model="search"
          type="text"
          placeholder="搜索审批..."
          class="search-input"
        />
      </div>
    </div>

    <!-- 状态过滤 -->
    <div class="status-tabs">
      <button
        v-for="s in statusFilters"
        :key="s.value"
        class="status-tab"
        :class="{ active: filter.status === s.value }"
        @click="filter.status = s.value"
      >
        <component :is="s.icon" size="13" />
        <span>{{ s.label }}</span>
      </button>
    </div>

    <!-- 加载中 -->
    <div v-if="loading" class="loading-state">
      <Loader2 class="spin-icon" size="20" />
      <p>加载中...</p>
    </div>

    <!-- 审批列表 -->
    <div v-else-if="filteredItems.length" class="approval-list">
      <button
        v-for="approval in filteredItems"
        :key="approval.id"
        class="approval-item"
        :class="{ pending: approval.status === 'pending' }"
        @click="openApprovalDetail(approval)"
      >
        <div class="approval-header">
          <div class="approval-title">{{ approval.title }}</div>
          <div class="approval-status" :style="{ color: statusColor(approval.status) }">
            {{ statusLabel(approval.status) }}
          </div>
        </div>
        <div class="approval-meta">
          <span>{{ approval.applicant || '—' }}</span>
          <span class="dot">•</span>
          <span>{{ new Date(approval.createdAt).toLocaleDateString('zh-CN') }}</span>
        </div>
        <div v-if="approval.status === 'pending'" class="approval-actions">
          <button class="action-btn approve" @click="approve(approval.id, $event)">
            <Check size="13" />
          </button>
          <button class="action-btn reject" @click="reject(approval.id, $event)">
            <X size="13" />
          </button>
        </div>
      </button>
    </div>

    <!-- 空状态 -->
    <div v-else class="empty-state">
      <ShieldCheck size="32" opacity="0.4" />
      <p>没有审批任务</p>
    </div>
  </div>
</template>

<style scoped>
.approval-tab {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--c-bg);
}

.approval-toolbar {
  display: flex;
  gap: 8px;
  padding: 12px;
  border-bottom: 1px solid var(--c-separator);
  flex-shrink: 0;
  align-items: center;
}

.search-box {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border: 1px solid var(--c-border);
  border-radius: 6px;
  background: var(--c-surface);
  color: var(--c-text-3);
}

.search-input {
  flex: 1;
  border: 0;
  background: transparent;
  color: var(--c-text);
  font-size: 12px;
  outline: 0;
}

.search-input::placeholder {
  color: var(--c-text-3);
}

.status-tabs {
  display: flex;
  gap: 4px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--c-separator);
  flex-shrink: 0;
  overflow-x: auto;
}

.status-tab {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 10px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  color: var(--c-text-3);
  font-size: 11px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
  white-space: nowrap;
  flex-shrink: 0;
}

.status-tab:hover {
  background: var(--c-surface-2);
  color: var(--c-text);
}

.status-tab.active {
  border-color: var(--c-brand);
  background: var(--c-brand-light);
  color: var(--c-brand);
}

.loading-state,
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 40px 20px;
  color: var(--c-text-3);
  text-align: center;
  flex: 1;
}

.loading-state p {
  font-size: 12px;
}

.empty-state p {
  font-size: 13px;
}

.spin-icon {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.approval-list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 0;
}

.approval-item {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
  border: 0;
  border-bottom: 1px solid var(--c-separator);
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition: background 0.2s;
  position: relative;
}

.approval-item:hover {
  background: var(--c-surface-2);
}

.approval-item.pending {
  background: var(--c-brand-light);
}

.approval-item.pending:hover {
  background: var(--c-brand-mist);
}

.approval-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 8px;
}

.approval-title {
  flex: 1;
  font-size: 12px;
  font-weight: 600;
  color: var(--c-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.approval-status {
  font-size: 11px;
  font-weight: 600;
  flex-shrink: 0;
  white-space: nowrap;
}

.approval-meta {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 10px;
  color: var(--c-text-3);
}

.dot {
  margin: 0 2px;
}

.approval-actions {
  display: flex;
  gap: 6px;
  margin-top: 4px;
}

.action-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: var(--c-text-3);
  cursor: pointer;
  transition: all 0.2s;
  flex-shrink: 0;
}

.action-btn:hover {
  background: var(--c-surface-2);
}

.action-btn.approve:hover {
  color: var(--c-success);
  background: var(--c-success-light);
}

.action-btn.reject:hover {
  color: var(--c-danger);
  background: var(--c-danger-light);
}
</style>
