<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { Download, Filter, Loader2, ScrollText, Search, ShieldAlert } from 'lucide-vue-next'
import { dashboardApi } from '../../services/dashboardApi'
import { hasPermission, getAuthUser } from '../../stores/auth'

const emit = defineEmits(['notify'])
defineProps({ searchTerm: { type: String, default: '' }, userRole: { type: String, default: 'admin' } })

const operator = getAuthUser() ?? { name: '李晨曦', role: 'admin' }
const loading = ref(false)
const logs = ref({ items: [], total: 0 })
const filter = ref({ keyword: '', action: '', dateRange: '7' })

const actionGroups = [
  { value: '', label: '全部操作', count: 0 },
  { value: 'WARNING', label: '预警相关', count: 0 },
  { value: 'HAZARD', label: '灾害点', count: 0 },
  { value: 'TASK', label: '巡检任务', count: 0 },
  { value: 'DEVICE', label: '设备', count: 0 },
  { value: 'CONFIRMATION', label: 'AI 确认', count: 0 },
  { value: 'USER', label: '用户', count: 0 },
  { value: 'LOGIN', label: '登录登出', count: 0 },
]

const actionColor = {
  WARNING_CREATE: '#c7553b', WARNING_CONFIRM: '#c7553b', WARNING_DISPATCH: '#c7553b', WARNING_CLOSE: '#9b761e',
  HAZARD_CREATE: '#4a7a5e', HAZARD_EDIT: '#4a7a5e', HAZARD_DELETE: '#c7553b',
  TASK_CREATE: '#6f9e7f', TASK_STATUS: '#6f9e7f',
  DEVICE_EDIT: '#5d95c1', DEVICE_DELETE: '#c7553b',
  CONFIRMATION_CONFIRM: '#9b761e', CONFIRMATION_REJECT: '#76847a', CONFIRMATION_TRANSFER: '#a07ec1',
  USER_CREATE: '#a07ec1', USER_DELETE: '#c7553b', USER_EDIT: '#a07ec1',
  LOGIN: '#4a7a5e', LOGOUT: '#9aab9f',
}

const filteredItems = computed(() => {
  let list = logs.value.items ?? []
  if (filter.value.action) {
    list = list.filter((l) => (l.action ?? '').startsWith(filter.value.action))
  }
  if (filter.value.keyword.trim()) {
    const k = filter.value.keyword.trim().toLowerCase()
    list = list.filter(
      (l) =>
        (l.operator ?? '').toLowerCase().includes(k) ||
        (l.detail ?? '').toLowerCase().includes(k) ||
        (l.target ?? '').toLowerCase().includes(k)
    )
  }
  return list
})

async function loadLogs() {
  loading.value = true
  try {
    const data = await dashboardApi.getAuditLogs({
      range: filter.value.dateRange || undefined,
      action: filter.value.action || undefined,
      keyword: filter.value.keyword || undefined,
    })
    logs.value = data ?? { items: [], total: 0 }
  } catch (e) {
    emit('notify', e.message ?? '加载日志失败')
  } finally {
    loading.value = false
  }
}

function exportCsv() {
  if (!hasPermission(operator.role, 'audit:export')) return emit('notify', '无导出权限')
  const rows = [
    ['时间', '操作员', '动作', '对象', '详情', 'IP'].join(','),
    ...filteredItems.value.map((l) =>
      [l.time, l.operator, l.action, l.target, '"' + (l.detail ?? '').replace(/"/g, '""') + '"', l.ip].join(',')
    ),
  ].join('\n')
  const blob = new Blob(['\uFEFF' + rows], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `audit-${new Date().toISOString().replace(/[:.]/g, '-')}.csv`
  a.click()
  URL.revokeObjectURL(url)
  emit('notify', '审计日志已导出')
}

function actionBadge(action) {
  return actionColor[action] ?? '#76847a'
}

function timeText(iso) {
  if (!iso) return ''
  return new Date(iso).toLocaleString('zh-CN', { hour12: false })
}

watch(filter, loadLogs, { deep: true })

onMounted(() => loadLogs())
</script>

<template>
  <div class="audit-shell">
    <header class="page-header">
      <div>
      </div>
      <div class="actions">
        <select v-model="filter.dateRange">
          <option value="24h">最近 24 小时</option>
          <option value="7">最近 7 天</option>
          <option value="30">最近 30 天</option>
          <option value="">全部</option>
        </select>
        <button class="op-btn" @click="exportCsv">
          <Download :size="14" />导出 CSV
        </button>
      </div>
    </header>

    <section class="action-bar">
      <div class="action-pills">
        <button
          v-for="a in actionGroups"
          :key="a.value || 'all'"
          class="pill"
          :class="{ active: filter.action === a.value }"
          @click="filter.action = a.value"
        >{{ a.label }}</button>
      </div>
      <div class="search-input">
        <Search :size="14" />
        <input v-model="filter.keyword" placeholder="搜索操作员 / 对象 / 详情" />
      </div>
    </section>

    <section class="log-panel">
      <table class="log-table">
        <thead>
          <tr>
            <th style="width: 170px">时间</th>
            <th style="width: 100px">操作员</th>
            <th style="width: 130px">动作</th>
            <th style="width: 130px">对象</th>
            <th>详情</th>
            <th style="width: 130px">IP</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="loading">
            <td colspan="6" class="loading-state">
              <Loader2 :size="16" class="spin" />加载日志中…
            </td>
          </tr>
          <tr v-else-if="!filteredItems.length">
            <td colspan="6" class="empty-state">该时间范围无操作日志</td>
          </tr>
          <tr v-for="log in filteredItems" :key="log.id">
            <td><code>{{ timeText(log.time) }}</code></td>
            <td>
              <span class="avatar">
                {{ (log.operator ?? '?').charAt(0).toUpperCase() }}
              </span>
              <span>{{ log.operator }}</span>
            </td>
            <td>
              <em class="action-chip" :style="{ background: actionBadge(log.action) + '22', color: actionBadge(log.action) }">
                {{ log.action }}
              </em>
            </td>
            <td><code class="target">{{ log.target }}</code></td>
            <td>{{ log.detail }}</td>
            <td><code>{{ log.ip }}</code></td>
          </tr>
        </tbody>
      </table>

      <footer class="panel-foot">
        <span>当前结果 {{ filteredItems.length }} 条 · 总计 {{ logs.total ?? filteredItems.length }} 条</span>
        <span>涉及敏感操作（删除 / 关闭 / 转派）会标记为红色徽标</span>
      </footer>
    </section>

    <section class="audit-rules">
      <h4><ShieldAlert :size="14" />审计规则说明</h4>
      <ul>
        <li>所有 Controller 方法标注 <code>@OperationLog</code> 后，写操作会自动被 AOP 拦截并落库</li>
        <li>登录 / 登出 / 权限相关变更会被记录至 <code>login_log</code> 子表</li>
        <li>敏感操作（角色变更、设备删除、灾害点关闭）会触发实时通知至审计负责人</li>
        <li>超过保留期的日志（默认 365 天）由 <code>OperationLogArchiver</code> 凌晨迁移至冷存储</li>
      </ul>
    </section>
  </div>
</template>

<style scoped>
.audit-shell { display: flex; flex-direction: column; gap: 16px; }

.page-header {
  background: var(--c-surface); border: 1px solid var(--c-brand-soft); border-radius: var(--r-md);
  padding: 14px 18px; display: flex; justify-content: space-between; align-items: flex-start; gap: 12px;
}
.page-header h2 { margin: 0 0 4px; display: flex; align-items: center; gap: 7px; color: var(--c-scrim); font-size: 16px; }
.page-header p { margin: 0; font-size: 12px; color: var(--c-text-2); line-height: 1.55; }
.page-header code { background: var(--c-brand-mist); padding: 1px 5px; border-radius: var(--r-xs); font-size: 11px; }
.actions { display: flex; gap: 8px; }
.actions select {
  border: 1px solid var(--c-brand-tint); border-radius: var(--r-sm); padding: 7px 9px;
  font-size: 12px; outline: none; background: var(--c-surface);
}
.op-btn {
  border: 1px solid var(--c-brand-tint); background: var(--c-surface); padding: 7px 12px;
  border-radius: var(--r-sm); font-size: 12px; cursor: pointer;
  display: inline-flex; align-items: center; gap: 5px; color: var(--c-ink-1);
}
.op-btn:hover { background: var(--c-brand-mist); }

.action-bar {
  background: var(--c-surface); border: 1px solid var(--c-brand-soft); border-radius: var(--r-md);
  padding: 10px 14px; display: flex; gap: 12px; align-items: center;
}
.action-pills { display: flex; gap: 5px; flex-wrap: wrap; flex: 1; }
.pill {
  border: 1px solid transparent; background: transparent; padding: 5px 10px;
  border-radius: var(--r-sm); font-size: 12px; cursor: pointer; color: var(--c-ink-1);
}
.pill:hover { background: var(--c-surface-2); }
.pill.active { background: var(--c-scrim); color: var(--c-on-brand); }
.search-input {
  display: flex; align-items: center; gap: 4px; padding: 0 9px;
  border: 1px solid var(--c-brand-tint); border-radius: var(--r-sm); background: var(--c-surface-2);
  flex-shrink: 0; width: 240px;
}
.search-input input { border: 0; background: transparent; outline: none; padding: 6px 0; font-size: 12px; width: 100%; }

.log-panel { background: var(--c-surface); border: 1px solid var(--c-brand-soft); border-radius: var(--r-md); overflow: hidden; }
.log-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.log-table th, .log-table td {
  text-align: left; padding: 9px 14px; border-bottom: 1px solid var(--c-brand-mist);
}
.log-table th { color: var(--c-text-2); font-weight: 600; background: var(--c-surface-2); font-size: 11px; letter-spacing: .04em; text-transform: uppercase; }
.log-table tr:hover td { background: var(--c-surface-2); }
.loading-state, .empty-state { text-align: center; color: var(--c-text-4); padding: 30px 0; }
.avatar {
  width: 22px; height: 22px; border-radius: var(--r-sm);
  background: var(--c-brand); color: var(--c-on-brand); display: inline-grid; place-items: center;
  font-size: 11px; font-weight: 700; margin-right: 6px; vertical-align: -4px;
}
.action-chip {
  font-size: 10px; padding: 2px 7px; border-radius: var(--r-xs); font-weight: 700;
  letter-spacing: .03em; font-family: 'JetBrains Mono', monospace;
}
code.target { background: var(--c-surface-2); padding: 1px 6px; border-radius: var(--r-xs); }
.panel-foot { display: flex; justify-content: space-between; padding: 10px 14px; border-top: 1px solid var(--c-brand-mist); font-size: 11px; color: var(--c-text-4); }

.audit-rules {
  background: var(--c-surface); border: 1px dashed var(--c-separator); border-radius: var(--r-md); padding: 14px 18px;
}
.audit-rules h4 { margin: 0 0 8px; font-size: 13px; color: var(--c-ink-1); display: flex; align-items: center; gap: 6px; }
.audit-rules ul { padding-left: 20px; margin: 0; font-size: 12px; color: var(--c-ink-1); }
.audit-rules li { margin-bottom: 4px; }
.audit-rules code { background: var(--c-brand-mist); padding: 1px 5px; border-radius: var(--r-xs); font-size: 11px; }

.spin { animation: spin 1.2s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
