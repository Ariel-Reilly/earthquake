<script setup>
/**
 * 消息中心 Tab（抽屉版）
 * 
 * 精简版本：
 * - 移除完整页面的四大统计卡片
 * - 简化为：搜索 + 简单过滤 + 列表
 * - 列表项点击跳转到对应模块
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  Bell,
  Bot,
  Check,
  CheckCheck,
  Mail,
  MailOpen,
  Search,
  ShieldAlert,
  Loader2,
} from 'lucide-vue-next'
import { dashboardApi } from '../../../services/dashboardApi'

const props = defineProps({
  compact: { type: Boolean, default: false },
})

const emit = defineEmits(['notify', 'badge-refresh'])
const router = useRouter()

const loading = ref(false)
const messages = ref({ items: [], total: 0 })
const filter = ref({ type: '' })
const search = ref('')
let pollTimer = null

const typeFilters = [
  { value: '', label: '全部', icon: Mail },
  { value: 'warning', label: '预警', icon: ShieldAlert },
  { value: 'ai', label: 'AI', icon: Bot },
  { value: 'task', label: '任务', icon: Check },
  { value: 'system', label: '系统', icon: Bell },
]

const filteredItems = computed(() => {
  let list = messages.value.items ?? []
  if (filter.value.type) list = list.filter((m) => m.type === filter.value.type)
  if (search.value.trim()) {
    const k = search.value.trim().toLowerCase()
    list = list.filter((m) =>
      (m.title ?? '').toLowerCase().includes(k) ||
      (m.content ?? '').toLowerCase().includes(k)
    )
  }
  return list
})

const unreadCount = computed(() => 
  (messages.value.items ?? []).filter((m) => m.unread).length
)

const typeIcon = (type) => ({
  warning: ShieldAlert,
  ai: Bot,
  task: Check,
  system: Bell,
})[type] ?? Mail

async function loadMessages() {
  loading.value = true
  try {
    const data = await dashboardApi.getMessages?.({
      type: filter.value.type || undefined,
      keyword: search.value || undefined,
    })
    messages.value = data ?? { items: [], total: 0 }
  } catch (e) {
    emit('notify', e.message ?? '加载消息失败')
  } finally {
    loading.value = false
  }
}

async function markRead(id, e) {
  e?.stopPropagation()
  try {
    await dashboardApi.markMessageRead?.(id)
    const m = messages.value.items.find((x) => x.id === id)
    if (m) {
      m.unread = false
      m.read = true
    }
    emit('badge-refresh')
  } catch (e) {
    emit('notify', e.message ?? '标已读失败')
  }
}

async function markAll() {
  try {
    await dashboardApi.markAllMessagesRead?.()
    messages.value.items.forEach((m) => {
      m.unread = false
      m.read = true
    })
    emit('badge-refresh')
    emit('notify', '已全部标为已读')
  } catch (e) {
    emit('notify', e.message ?? '操作失败')
  }
}

function openMessage(msg) {
  if (msg.unread) markRead(msg.id, null)
  jumpToMessage(msg)
}

function jumpToMessage(msg) {
  if (msg.type === 'warning') {
    router.push('/dashboard/warnings')
  } else if (msg.type === 'ai') {
    router.push('/dashboard/confirmation-workbench')
  } else if (msg.type === 'task') {
    router.push('/dashboard/ai-tasks')
  } else if (msg.type === 'system') {
    router.push('/dashboard/system-monitor')
  }
}

watch(filter, loadMessages, { deep: true })
let searchTimer
watch(search, () => {
  window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(loadMessages, 300)
})

onMounted(async () => {
  await loadMessages()
  pollTimer = window.setInterval(loadMessages, 45000)
})

onBeforeUnmount(() => {
  if (pollTimer) window.clearInterval(pollTimer)
  window.clearTimeout(searchTimer)
})
</script>

<template>
  <div class="msg-tab">
    <!-- 顶部操作栏 -->
    <div class="msg-toolbar">
      <div class="search-box">
        <Search size="14" />
        <input
          v-model="search"
          type="text"
          placeholder="搜索消息..."
          class="search-input"
        />
      </div>
      <button v-if="unreadCount > 0" class="mark-all-btn" @click="markAll" :disabled="loading">
        <CheckCheck size="14" />
        全部已读
      </button>
    </div>

    <!-- 类型过滤 -->
    <div class="type-tabs">
      <button
        v-for="t in typeFilters"
        :key="t.value || 'all'"
        class="type-tab"
        :class="{ active: filter.type === t.value }"
        @click="filter.type = t.value"
      >
        <component :is="t.icon" size="13" />
        <span>{{ t.label }}</span>
      </button>
    </div>

    <!-- 加载中 -->
    <div v-if="loading" class="loading-state">
      <Loader2 class="spin-icon" size="20" />
      <p>加载中...</p>
    </div>

    <!-- 消息列表 -->
    <div v-else-if="filteredItems.length" class="msg-list">
      <button
        v-for="msg in filteredItems"
        :key="msg.id"
        class="msg-item"
        :class="{ unread: msg.unread }"
        @click="openMessage(msg)"
      >
        <div class="msg-icon">
          <component :is="typeIcon(msg.type)" size="16" />
        </div>
        <div class="msg-body">
          <div class="msg-title">{{ msg.title }}</div>
          <div class="msg-snippet">{{ msg.content?.substring(0, 40) || '—' }}</div>
          <div class="msg-meta">{{ new Date(msg.createdAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) }}</div>
        </div>
        <button
          v-if="msg.unread"
          class="mark-read-btn"
          :title="'标已读'"
          @click="markRead(msg.id, $event)"
        >
          <MailOpen size="14" />
        </button>
      </button>
    </div>

    <!-- 空状态 -->
    <div v-else class="empty-state">
      <Mail size="32" opacity="0.4" />
      <p>没有消息</p>
    </div>
  </div>
</template>

<style scoped>
.msg-tab {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--c-bg);
}

.msg-toolbar {
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

.mark-all-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 10px;
  border: 1px solid var(--c-border);
  border-radius: 6px;
  background: var(--c-surface);
  color: var(--c-text-3);
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
  white-space: nowrap;
  flex-shrink: 0;
}

.mark-all-btn:hover:not(:disabled) {
  background: var(--c-surface-2);
  color: var(--c-text);
}

.mark-all-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.type-tabs {
  display: flex;
  gap: 4px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--c-separator);
  flex-shrink: 0;
  overflow-x: auto;
}

.type-tab {
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

.type-tab:hover {
  background: var(--c-surface-2);
  color: var(--c-text);
}

.type-tab.active {
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

.msg-list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 0;
}

.msg-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px;
  border: 0;
  border-bottom: 1px solid var(--c-separator);
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition: background 0.2s;
}

.msg-item:hover {
  background: var(--c-surface-2);
}

.msg-item.unread {
  background: var(--c-brand-light);
}

.msg-item.unread:hover {
  background: var(--c-brand-mist);
}

.msg-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 6px;
  background: var(--c-surface);
  color: var(--c-text-2);
  flex-shrink: 0;
}

.msg-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.msg-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--c-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.msg-snippet {
  font-size: 11px;
  color: var(--c-text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.msg-meta {
  font-size: 10px;
  color: var(--c-text-3);
}

.mark-read-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: var(--c-brand);
  cursor: pointer;
  transition: all 0.2s;
  flex-shrink: 0;
}

.mark-read-btn:hover {
  background: var(--c-brand-light);
}
</style>
