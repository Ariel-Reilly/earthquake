<script setup>
/**
 * 待办中心 Tab（抽屉版）
 * 
 * 精简版本：
 * - 移除多个 Tab 分组
 * - 简化为：搜索 + 优先级过滤 + 列表
 * - 支持标记完成、接受/拒绝操作
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  AlertTriangle,
  CheckCircle2,
  Loader2,
  Search,
  Clock4,
  Inbox,
  AlertCircle,
} from 'lucide-vue-next'
import { dashboardApi } from '../../../services/dashboardApi'

const props = defineProps({
  compact: { type: Boolean, default: false },
})

const emit = defineEmits(['notify', 'badge-refresh'])
const router = useRouter()

const loading = ref(false)
const todos = ref({ items: [], total: 0 })
const filter = ref({ priority: '' })
const search = ref('')
let pollTimer = null

const priorityFilters = [
  { value: '', label: '全部', icon: Inbox },
  { value: 'high', label: '高', icon: AlertTriangle },
  { value: 'medium', label: '中', icon: AlertCircle },
  { value: 'low', label: '低', icon: Clock4 },
]

const filteredItems = computed(() => {
  let list = todos.value.items ?? []
  if (filter.value.priority) {
    list = list.filter((t) => t.priority === filter.value.priority)
  }
  if (search.value.trim()) {
    const k = search.value.trim().toLowerCase()
    list = list.filter((t) =>
      (t.title ?? '').toLowerCase().includes(k) ||
      (t.description ?? '').toLowerCase().includes(k)
    )
  }
  return list
})

const priorityColor = (priority) => ({
  high: 'var(--c-danger)',
  medium: 'var(--c-warning)',
  low: 'var(--c-text-3)',
})[priority] ?? 'var(--c-text-3)'

const priorityLabel = (priority) => ({
  high: '高',
  medium: '中',
  low: '低',
})[priority] ?? (priority || '未标注')

function normalizeTodo(raw) {
  return {
    id: raw?.taskCode ?? raw?.id,
    title: raw?.title ?? raw?.name ?? '未命名待办',
    description: raw?.description ?? raw?.assignee ?? raw?.point ?? '',
    priority: raw?.priority ?? '',
    status: raw?.status ?? '',
    dueDate: raw?.dueDate ?? raw?.dueAt ?? null,
    overdue: Boolean(raw?.overdue),
  }
}

async function loadTodos() {
  loading.value = true
  try {
    const data = await dashboardApi.listMyOpenTasks()
    const raw = Array.isArray(data) ? data : (data?.list ?? data?.items ?? data?.records ?? [])
    const items = (Array.isArray(raw) ? raw : []).map(normalizeTodo)
    todos.value = { items, total: items.length }
  } catch (e) {
    todos.value = { items: [], total: 0 }
    emit('notify', e?.message ?? '加载待办失败')
  } finally {
    loading.value = false
  }
}

// 后端 /api/todo/* 目前只提供 GET 查询端点（summary / confirmations /
// approvals / warnings / tasks），没有"接受 / 完成 / 拒绝待办"的写接口。
// 因此这里不保留"点了弹成功、刷新即复原"的假按钮，统一引导到待办详情页处理。

function openTodoDetail(todo) {
  router.push(`/dashboard/todo?id=${todo.id}`)
}

watch(filter, loadTodos, { deep: true })
let searchTimer
watch(search, () => {
  window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(loadTodos, 300)
})

onMounted(async () => {
  await loadTodos()
  pollTimer = window.setInterval(loadTodos, 45000)
})

onBeforeUnmount(() => {
  if (pollTimer) window.clearInterval(pollTimer)
  window.clearTimeout(searchTimer)
})
</script>

<template>
  <div class="todo-tab">
    <!-- 顶部操作栏 -->
    <div class="todo-toolbar">
      <div class="search-box">
        <Search size="14" />
        <input
          v-model="search"
          type="text"
          placeholder="搜索待办..."
          class="search-input"
        />
      </div>
    </div>

    <!-- 优先级过滤 -->
    <div class="priority-tabs">
      <button
        v-for="p in priorityFilters"
        :key="p.value || 'all'"
        class="priority-tab"
        :class="{ active: filter.priority === p.value }"
        @click="filter.priority = p.value"
      >
        <component :is="p.icon" size="13" />
        <span>{{ p.label }}</span>
      </button>
    </div>

    <div class="unsupported-hint">
      后端暂不支持在待办中心直接接受 / 完成 / 拒绝，请点击条目进入待办详情处理。
    </div>

    <!-- 加载中 -->
    <div v-if="loading" class="loading-state">
      <Loader2 class="spin-icon" size="20" />
      <p>加载中...</p>
    </div>

    <!-- 待办列表 -->
    <div v-else-if="filteredItems.length" class="todo-list">
      <button
        v-for="todo in filteredItems"
        :key="todo.id"
        class="todo-item"
        :class="{ pending: todo.status === 'pending', overdue: todo.overdue }"
        @click="openTodoDetail(todo)"
      >
        <div class="todo-header">
          <div class="todo-title">{{ todo.title }}</div>
          <div
            class="todo-priority"
            :style="{ color: priorityColor(todo.priority) }"
          >
            {{ priorityLabel(todo.priority) }}
          </div>
        </div>
        <div class="todo-meta">
          <span v-if="todo.dueDate" class="due-date">
            {{ new Date(todo.dueDate).toLocaleDateString('zh-CN', {
              month: 'short',
              day: 'numeric',
            }) }}
          </span>
          <span v-if="todo.status" class="status-badge">{{ todo.status }}</span>
        </div>
      </button>
    </div>

    <!-- 空状态 -->
    <div v-else class="empty-state">
      <CheckCircle2 size="32" opacity="0.4" />
      <p>没有待办任务</p>
    </div>
  </div>
</template>

<style scoped>
.todo-tab {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--c-bg);
}

.todo-toolbar {
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

.priority-tabs {
  display: flex;
  gap: 4px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--c-separator);
  flex-shrink: 0;
  overflow-x: auto;
}

.priority-tab {
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

.priority-tab:hover {
  background: var(--c-surface-2);
  color: var(--c-text);
}

.priority-tab.active {
  border-color: var(--c-brand);
  background: var(--c-brand-light);
  color: var(--c-brand);
}

.unsupported-hint {
  padding: 8px 12px;
  border-bottom: 1px solid var(--c-separator);
  color: var(--c-text-3);
  font-size: 11px;
  line-height: 1.5;
  flex-shrink: 0;
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

.todo-list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 0;
}

.todo-item {
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
}

.todo-item:hover {
  background: var(--c-surface-2);
}

.todo-item.pending {
  background: var(--c-brand-light);
}

.todo-item.pending:hover {
  background: var(--c-brand-mist);
}

.todo-item.overdue {
  background: var(--c-danger-light);
}

.todo-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 8px;
}

.todo-title {
  flex: 1;
  font-size: 12px;
  font-weight: 600;
  color: var(--c-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.todo-priority {
  font-size: 11px;
  font-weight: 600;
  flex-shrink: 0;
  white-space: nowrap;
}

.todo-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 10px;
  color: var(--c-text-3);
}

.due-date {
  padding: 2px 6px;
  border-radius: 3px;
  background: var(--c-surface);
}

.status-badge {
  padding: 2px 6px;
  border-radius: 3px;
  background: var(--c-warning-light);
  color: var(--c-warning);
  font-weight: 500;
}

.status-badge.in {
  background: var(--c-success-light);
  color: var(--c-success);
}

.todo-actions {
  display: flex;
  gap: 6px;
  margin-top: 4px;
}

.action-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 4px 8px;
  border: 1px solid var(--c-border);
  border-radius: 4px;
  background: var(--c-surface);
  color: var(--c-text-2);
  font-size: 10px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
  flex: 1;
  min-height: 24px;
}

.action-btn:hover {
  background: var(--c-surface-2);
  color: var(--c-text);
}

.action-btn.accept:hover {
  border-color: var(--c-success);
  color: var(--c-success);
}

.action-btn.reject:hover {
  border-color: var(--c-danger);
  color: var(--c-danger);
}

.action-btn.complete {
  background: var(--c-success-light);
  border-color: var(--c-success);
  color: var(--c-success);
}

.action-btn.complete:hover {
  background: var(--c-success-mist);
}
</style>
