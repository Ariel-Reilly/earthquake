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

const unreadCount = computed(() =>
  (todos.value.items ?? []).filter((t) => t.status === 'pending').length
)

const priorityColor = (priority) => ({
  high: 'var(--c-danger)',
  medium: 'var(--c-warning)',
  low: 'var(--c-text-3)',
})[priority] ?? 'var(--c-text-3)'

const priorityLabel = (priority) => ({
  high: '高',
  medium: '中',
  low: '低',
})[priority] ?? priority

async function loadTodos() {
  loading.value = true
  try {
    const data = await dashboardApi.listMyTodos?.({
      priority: filter.value.priority || undefined,
      keyword: search.value || undefined,
    })
    todos.value = data ?? { items: [], total: 0 }
  } catch (e) {
    emit('notify', e.message ?? '加载待办失败')
  } finally {
    loading.value = false
  }
}

async function completeTodo(id, e) {
  e?.stopPropagation()
  try {
    await dashboardApi.completeTodo?.(id)
    const t = todos.value.items.find((x) => x.id === id)
    if (t) t.status = 'completed'
    emit('badge-refresh')
    emit('notify', '已完成')
    await loadTodos()
  } catch (e) {
    emit('notify', e.message ?? '操作失败')
  }
}

async function acceptTodo(id, e) {
  e?.stopPropagation()
  try {
    await dashboardApi.acceptTodo?.(id)
    const t = todos.value.items.find((x) => x.id === id)
    if (t) t.status = 'in_progress'
    emit('badge-refresh')
    emit('notify', '已接受')
    await loadTodos()
  } catch (e) {
    emit('notify', e.message ?? '操作失败')
  }
}

async function rejectTodo(id, e) {
  e?.stopPropagation()
  try {
    await dashboardApi.rejectTodo?.(id)
    const t = todos.value.items.find((x) => x.id === id)
    if (t) todos.value.items = todos.value.items.filter((x) => x.id !== id)
    emit('badge-refresh')
    emit('notify', '已拒绝')
    await loadTodos()
  } catch (e) {
    emit('notify', e.message ?? '操作失败')
  }
}

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
          <span v-if="todo.status === 'pending'" class="status-badge">未接受</span>
          <span v-if="todo.status === 'in_progress'" class="status-badge in">进行中</span>
        </div>
        <div v-if="todo.status === 'pending'" class="todo-actions">
          <button class="action-btn accept" @click="acceptTodo(todo.id, $event)">
            接受
          </button>
          <button class="action-btn reject" @click="rejectTodo(todo.id, $event)">
            拒绝
          </button>
        </div>
        <div v-else-if="todo.status === 'in_progress'" class="todo-actions">
          <button class="action-btn complete" @click="completeTodo(todo.id, $event)">
            <CheckCircle2 size="13" />
            完成
          </button>
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
