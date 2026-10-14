<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  Bell,
  Bot,
  Check,
  CheckCheck,
  Filter,
  Inbox,
  Mail,
  MailOpen,
  Search,
  ShieldAlert,
} from 'lucide-vue-next'
import { dashboardApi } from '../../services/dashboardApi'

const emit = defineEmits(['notify'])
defineProps({ searchTerm: { type: String, default: '' }, userRole: { type: String, default: 'admin' } })

const router = useRouter()
const loading = ref(false)
const messages = ref({ items: [], total: 0 })
const filter = ref({ type: '', severity: '' })
const search = ref('')
const pollTimer = ref(null)

const typeFilters = [
  { value: '',           label: '全部', icon: Inbox },
  { value: 'warning',    label: '预警', icon: ShieldAlert },
  { value: 'ai',         label: 'AI 推送', icon: Bot },
  { value: 'task',       label: '任务', icon: Check },
  { value: 'system',     label: '系统', icon: Bell },
]

const filteredItems = computed(() => {
  let list = messages.value.items ?? []
  if (filter.value.type) list = list.filter((m) => m.type === filter.value.type)
  if (filter.value.severity) list = list.filter((m) => m.severity === filter.value.severity)
  if (search.value.trim()) {
    const k = search.value.trim().toLowerCase()
    list = list.filter((m) =>
      (m.title ?? '').toLowerCase().includes(k) ||
      (m.content ?? '').toLowerCase().includes(k)
    )
  }
  return list
})

const stats = computed(() => {
  const list = messages.value.items ?? []
  return {
    total: messages.value.total ?? list.length,
    unread: list.filter((m) => m.unread).length,
    high: list.filter((m) => m.severity === 'high').length,
    warning: list.filter((m) => m.type === 'warning').length,
  }
})

async function loadMessages() {
  loading.value = true
  try {
    const data = await dashboardApi.getMessages({
      type: filter.value.type || undefined,
      severity: filter.value.severity || undefined,
      keyword: search.value || undefined,
    })
    messages.value = data ?? { items: [], total: 0 }
  } catch (e) {
    emit('notify', e.message ?? '加载消息失败')
  } finally {
    loading.value = false
  }
}

async function markRead(id) {
  try {
    await dashboardApi.markMessageRead?.(id)
    const m = messages.value.items.find((x) => x.id === id)
    if (m) {
      m.unread = false
      m.read = true
    }
    // 通知布局层立即重算红点，不等 60s 轮询（否则用户体感是"点了红点还在"）
    emit('badge-refresh')
  } catch (e) {
    emit('notify', e.message ?? '标已读失败')
  }
}

async function markAll() {
  try {
    const res = await dashboardApi.markAllMessagesRead?.()
    messages.value.items.forEach((m) => {
      m.unread = false
      m.read = true
    })
    emit('badge-refresh')
    const updated = res?.updated
    emit('notify', typeof updated === 'number' ? `已标记 ${updated} 条为已读` : '已全部标为已读')
  } catch (e) {
    emit('notify', e.message ?? '操作失败')
  }
}

/**
 * 点击消息卡片视同"查看"：先就地标已读并刷新红点，再跳转。
 * 否则用户点进详情页、红点却还挂着，是"点击查看红点不消失"的直接来源。
 */
async function openMessage(msg) {
  if (msg.unread) await markRead(msg.id)
  jumpToMessage(msg)
}

function severityClass(s) {
  return ({ high: 'red', medium: 'yellow', low: 'gray' })[s] ?? 'gray'
}

function typeIcon(type) {
  const map = {
    warning: ShieldAlert,
    ai: Bot,
    task: Check,
    system: Bell,
  }
  return map[type] ?? Bell
}

function jumpToMessage(msg) {
  // 点击消息跳转至对应详情
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
  pollTimer.value = window.setInterval(loadMessages, 45_000)
})

onBeforeUnmount(() => {
  if (pollTimer.value) window.clearInterval(pollTimer.value)
  window.clearTimeout(searchTimer)
})
</script>

<template>
  <div class="msg-shell">
    <header class="msg-header">
      <div class="msg-stats">
        <article class="stat-card">
          <strong>{{ stats.total }}</strong>
          <span>累计消息</span>
        </article>
        <article class="stat-card unread">
          <strong>{{ stats.unread }}</strong>
          <span>未读</span>
        </article>
        <article class="stat-card high">
          <strong>{{ stats.high }}</strong>
          <span>高紧急</span>
        </article>
        <article class="stat-card warning">
          <strong>{{ stats.warning }}</strong>
          <span>预警相关</span>
        </article>
      </div>
      <div class="msg-actions">
        <button class="op-btn" @click="markAll" :disabled="!stats.unread">
          <CheckCheck :size="14" />全部已读
        </button>
      </div>
    </header>

    <section class="msg-toolbar">
      <div class="type-tabs">
        <button
          v-for="t in typeFilters"
          :key="t.value || 'all'"
          class="type-tab"
          :class="{ active: filter.type === t.value }"
          @click="filter.type = t.value"
        >
          <component :is="t.icon" :size="14" />
          <span>{{ t.label }}</span>
        </button>
      </div>
      <div class="right-tools">
        <select v-model="filter.severity">
          <option value="">全部紧急度</option>
          <option value="high">高</option>
          <option value="medium">中</option>
          <option value="low">低</option>
        </select>
        <div class="search-input">
          <Search :size="14" />
          <input v-model="search" placeholder="搜索标题或正文…" />
        </div>
      </div>
    </section>

    <section class="msg-list" v-if="filteredItems.length">
      <article
        v-for="msg in filteredItems"
        :key="msg.id"
        class="msg-card"
        :class="{ unread: msg.unread }"
      >
        <div class="msg-icon" :class="severityClass(msg.severity)">
          <component :is="typeIcon(msg.type)" :size="18" />
        </div>
        <div class="msg-body" @click="openMessage(msg)">
          <div class="msg-head">
            <strong>{{ msg.title }}</strong>
            <em class="severity" :class="severityClass(msg.severity)">{{
              ({ high: '高紧急', medium: '中等', low: '一般' })[msg.severity]
            }}</em>
            <span class="msg-time">{{ msg.time }}</span>
          </div>
          <p class="msg-content">{{ msg.content }}</p>
          <div class="msg-meta">
            <span class="badge" :class="msg.type">{{
              ({ warning: '预警', ai: 'AI推送', task: '任务', system: '系统' })[msg.type]
            }}</span>
          </div>
        </div>
        <div class="msg-actions">
          <button v-if="msg.unread" class="op-btn small" @click.stop="markRead(msg.id)">
            <Mail :size="13" />标已读
          </button>
          <span v-else class="read-tag"><MailOpen :size="12" />已读</span>
        </div>
      </article>
    </section>

    <div v-else-if="loading" class="empty-state">正在加载消息…</div>
    <div v-else class="empty-state">
      <Inbox :size="22" />
      <p>暂无消息，去其他模块处理任务时将有新提醒。</p>
    </div>
  </div>
</template>

<style scoped>
.msg-shell { display: flex; flex-direction: column; gap: 16px; }

.msg-header {
  display: flex; justify-content: space-between; align-items: stretch; gap: 18px;
}
.msg-stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; flex: 1; }
.stat-card {
  background: var(--c-surface); border: 1px solid var(--c-brand-soft); border-radius: var(--r-md); padding: 14px 16px;
  display: flex; flex-direction: column; gap: 4px;
}
.stat-card strong { font-size: 22px; color: var(--c-scrim); font-weight: 700; }
.stat-card span { font-size: 12px; color: var(--c-text-2); }
.stat-card.unread strong { color: var(--c-danger-strong); }
.stat-card.high strong { color: var(--c-warning-ink); }
.stat-card.warning strong { color: var(--sys-blue-deep); }
.msg-actions { display: flex; gap: 8px; align-items: flex-start; }
.op-btn {
  border: 1px solid var(--c-brand-tint); background: var(--c-surface); padding: 8px 12px; border-radius: var(--r-sm);
  font-size: 12px; cursor: pointer; display: inline-flex; align-items: center; gap: 6px; color: var(--c-ink-1);
}
.op-btn:hover { background: var(--c-brand-mist); }
.op-btn:disabled { opacity: .5; cursor: not-allowed; }
.op-btn.small { padding: 6px 10px; font-size: 11px; }

.msg-toolbar {
  display: flex; justify-content: space-between; align-items: center; gap: 12px;
  background: var(--c-surface); border-radius: var(--r-md); padding: 10px 12px;
  border: 1px solid var(--c-brand-soft);
}
.type-tabs { display: flex; gap: 4px; flex-wrap: wrap; }
.type-tab {
  border: 1px solid transparent; background: transparent; padding: 6px 11px;
  border-radius: var(--r-sm); font-size: 12px; cursor: pointer; color: var(--c-ink-1);
  display: inline-flex; align-items: center; gap: 4px;
}
.type-tab:hover { background: var(--c-surface-2); }
.type-tab.active { background: var(--c-scrim); color: var(--c-on-brand); }

.right-tools { display: flex; gap: 8px; }
.right-tools select {
  border: 1px solid var(--c-brand-tint); border-radius: var(--r-sm); padding: 6px 9px;
  font-size: 12px; outline: none; background: var(--c-surface);
}
.search-input {
  display: flex; align-items: center; gap: 4px; padding: 0 9px;
  border: 1px solid var(--c-brand-tint); border-radius: var(--r-sm); background: var(--c-surface-2);
}
.search-input input { border: 0; background: transparent; outline: none; padding: 6px 0; font-size: 12px; width: 180px; }

.msg-list { display: flex; flex-direction: column; gap: 8px; }
.msg-card {
  display: grid; grid-template-columns: 40px 1fr auto;
  background: var(--c-surface); border: 1px solid var(--c-brand-soft); border-radius: var(--r-md);
  padding: 12px 14px; gap: 12px; cursor: pointer; transition: border-color .15s;
}
.msg-card:hover { border-color: var(--c-brand-tint); }
.msg-card.unread { background: var(--c-surface-2); border-left: 3px solid var(--c-brand); }

.msg-icon {
  width: 36px; height: 36px; border-radius: var(--r-md);
  display: grid; place-items: center;
}
.msg-icon.red    { background: var(--c-danger-bg); color: var(--c-danger-strong); }
.msg-icon.yellow { background: var(--c-warning-bg); color: var(--c-warning-ink); }
.msg-icon.gray   { background: var(--c-brand-mist); color: var(--c-text-2); }

.msg-body { min-width: 0; }
.msg-head {
  display: flex; align-items: center; gap: 8px; margin-bottom: 4px;
}
.msg-head strong { color: var(--c-scrim); font-size: 14px; flex: 1; min-width: 0; }
.severity { font-size: 10px; padding: 2px 6px; border-radius: var(--r-pill); font-weight: 700; }
.severity.red    { background: var(--c-danger-bg); color: var(--c-danger-strong); }
.severity.yellow { background: var(--c-warning-bg); color: var(--c-warning-ink); }
.severity.gray   { background: var(--c-brand-mist); color: var(--c-text-2); }
.msg-time { font-size: 11px; color: var(--c-text-4); white-space: nowrap; }
.msg-content { margin: 0; color: var(--c-ink-1); font-size: 12px; line-height: 1.55; }
.msg-meta { margin-top: 6px; }
.badge {
  font-size: 10px; padding: 2px 6px; border-radius: var(--r-xs);
  background: var(--c-brand-mist); color: var(--c-ink-1);
}
.badge.warning { background: var(--c-danger-bg); color: var(--c-danger-strong); }
.badge.ai      { background: var(--c-warning-bg); color: var(--c-warning-ink); }
.badge.task    { background: var(--c-brand-mist); color: var(--c-brand-deep); }
.badge.system  { background: var(--c-surface-2); color: var(--sys-blue-deep); }

.read-tag {
  display: inline-flex; align-items: center; gap: 3px; font-size: 11px;
  color: var(--c-text-4);
}

.empty-state {
  background: var(--c-surface); border: 1px dashed var(--c-brand-tint); border-radius: var(--r-md);
  text-align: center; padding: 50px 20px; color: var(--c-text-4);
}
.empty-state p { margin: 8px 0 0; font-size: 13px; }

@media (max-width: 900px) {
  .msg-stats { grid-template-columns: repeat(2, 1fr); }
  .msg-header { flex-direction: column; }
  .msg-card { grid-template-columns: 32px 1fr; }
  .msg-card .msg-actions { grid-column: 2; justify-self: start; }
}
</style>
