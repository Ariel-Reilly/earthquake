<script setup>
/**
 * AdminApplicationsPage ── 注册申请审核（/admin/applications）
 *
 * 对应后端 AdminApplicationController（全部标注 @PreAuthorize("hasRole('ADMIN')")）：
 *   GET  /api/admin/applications                → dashboardApi.getApplications()
 *   POST /api/admin/applications/{id}/approve   → dashboardApi.approveApplication()
 *   POST /api/admin/applications/{id}/reject    → dashboardApi.rejectApplication()
 *
 * 两点约定：
 *   1. 后端 status 直接存中文字符串（待审核 / 已通过 / 已拒绝），
 *      筛选与徽标都按中文比对，不要在前端再造一套英文枚举映射。
 *   2. 拒绝原因后端限长 255，这里同长度校验，避免用户填完才被拒。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { Check, Inbox, Loader2, RefreshCw, Search, ShieldCheck, X } from 'lucide-vue-next'
import { dashboardApi } from '../services/dashboardApi'
import { getAuthRole, hasPermission, ROLE_OPTIONS } from '../stores/auth'
import FormModal from '../components/FormModal.vue'

const emit = defineEmits(['notify'])
defineProps({
  searchTerm: { type: String, default: '' },
  userRole: { type: String, default: 'admin' },
})

/** 后端 SysUserApplication.STATUS_* 的三个取值。 */
const STATUS_PENDING = '待审核'
const STATUS_APPROVED = '已通过'
const STATUS_REJECTED = '已拒绝'

const STATUS_OPTIONS = [
  { value: '', label: '全部状态' },
  { value: STATUS_PENDING, label: STATUS_PENDING },
  { value: STATUS_APPROVED, label: STATUS_APPROVED },
  { value: STATUS_REJECTED, label: STATUS_REJECTED },
]

/** 状态徽标配色，pending 偏蓝、approved 偏绿、rejected 偏红。 */
const STATUS_TONE = {
  [STATUS_PENDING]: 'pending',
  [STATUS_APPROVED]: 'approved',
  [STATUS_REJECTED]: 'rejected',
}

const loading = ref(false)
const rows = ref([])
const page = reactive({ current: 1, size: 20, total: 0, pages: 0 })
const filter = reactive({ status: '', keyword: '' })

/** 正在提交审核的申请 ID，用于逐行禁用按钮，防重复点击。 */
const busyId = ref(null)
const rejectDialog = ref({ open: false, id: null, username: '', reason: '' })

/**
 * 是否具备审核权限。
 *
 * 路由层已用 users:approve 卡过一次，这里再挡一层：后端真正的判据是
 * hasRole('ADMIN')，前端权限集是独立的一份数据，两者万一不同步，
 * 多这一道判断可以避免渲染出点了必报 403 的按钮。
 */
const canReview = computed(() => hasPermission(getAuthRole(), 'users:approve'))

/* ── 页面内提示 ────────────────────────────────────────── */

/**
 * 提示信息。
 *
 * 统一 emit 给 MainLayout，由布局层的全局 toast 渲染（与 DashboardLayout
 * 的处理方式一致）。这里不再自己维护一份 toast —— 布局层已能接住事件，
 * 页面再画一条会导致同一次操作弹出两个提示。
 *
 * @param {string} message - 提示文案
 */
function notify(message) {
  emit('notify', message)
}

/* ── 数据 ──────────────────────────────────────────────── */

async function loadApplications() {
  loading.value = true
  try {
    const data = await dashboardApi.getApplications({
      status: filter.status || undefined,
      keyword: filter.keyword || undefined,
      page: page.current,
      size: page.size,
    })
    rows.value = data?.records ?? []
    page.total = data?.total ?? rows.value.length
    page.pages = data?.pages ?? 1

    // 翻页后若当前页被清空（例如筛选后总数变少），退到最后一页，
    // 否则用户会停在一个空白的「第 5 页，共 3 页」。
    if (!rows.value.length && page.current > 1 && page.current > page.pages) {
      page.current = page.pages
    }
  } catch (e) {
    rows.value = []
    notify(e?.message ?? '加载注册申请失败')
  } finally {
    loading.value = false
  }
}

/** 状态徽标。未知状态统一按 pending 处理，避免出现空样式。 */
function statusTone(status) {
  return STATUS_TONE[status] ?? 'pending'
}

/** 角色码 → 中文名，与 UserManagementPage 共用同一份 ROLE_OPTIONS。 */
function roleLabel(code) {
  return ROLE_OPTIONS.find((r) => r.value === code)?.label ?? code ?? '—'
}

function timeText(iso) {
  if (!iso) return '—'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString('zh-CN', { hour12: false })
}

function goPage(next) {
  if (next < 1 || (page.pages && next > page.pages)) return
  page.current = next
  loadApplications()
}

/* ── 审核操作 ──────────────────────────────────────────── */

async function approve(row) {
  busyId.value = row.id
  try {
    await dashboardApi.approveApplication(row.id)
    notify(`已通过 ${row.username} 的申请，账号已创建`)
    await loadApplications()
  } catch (e) {
    notify(e?.message ?? '审核通过失败')
  } finally {
    busyId.value = null
  }
}

function openReject(row) {
  rejectDialog.value = { open: true, id: row.id, username: row.username, reason: '' }
}

function closeReject() {
  rejectDialog.value = { open: false, id: null, username: '', reason: '' }
}

async function submitReject() {
  const { id, reason } = rejectDialog.value
  // 后端 RejectRequest 对 reason 是 @NotBlank + 最长 255，先在前端拦住空值
  if (!reason.trim()) {
    notify('请填写拒绝原因')
    return
  }
  busyId.value = id
  try {
    await dashboardApi.rejectApplication(id, reason.trim())
    notify(`已拒绝 ${rejectDialog.value.username} 的申请`)
    closeReject()
    await loadApplications()
  } catch (e) {
    notify(e?.message ?? '审核拒绝失败')
  } finally {
    busyId.value = null
  }
}

/* ── 筛选联动 ──────────────────────────────────────────── */

// 筛选条件变化后回到第 1 页，否则停留在第 5 页时改关键字会查不到东西。
watch(
  () => [filter.status, filter.keyword],
  () => {
    page.current = 1
    loadApplications()
  }
)

onMounted(loadApplications)
</script>

<template>
  <div class="app-shell">
    <header class="page-header">
      <div>
        <h2><ShieldCheck :size="16" />注册申请审核</h2>
        <p>审核通过后由系统创建账号；拒绝原因会回显给申请人。</p>
      </div>
      <div class="actions">
        <button class="op-btn" :disabled="loading" @click="loadApplications">
          <RefreshCw :size="14" :class="{ spin: loading }" />刷新
        </button>
      </div>
    </header>

    <section class="filter-bar">
      <label class="select-wrap">
        <span>状态</span>
        <select v-model="filter.status">
          <option v-for="s in STATUS_OPTIONS" :key="s.value || 'all'" :value="s.value">
            {{ s.label }}
          </option>
        </select>
      </label>

      <div class="search-input">
        <Search :size="14" />
        <input v-model="filter.keyword" placeholder="搜索用户名 / 姓名 / 手机号" />
      </div>

      <span class="result-count">共 {{ page.total }} 条申请</span>
    </section>

    <section class="table-panel">
      <table class="data-table">
        <thead>
          <tr>
            <th style="width: 130px">用户名</th>
            <th style="width: 90px">姓名</th>
            <th style="width: 125px">手机号</th>
            <th style="width: 190px">邮箱</th>
            <th style="width: 110px">申请角色</th>
            <th style="width: 125px">提交 IP</th>
            <th style="width: 150px">提交时间</th>
            <th style="width: 90px">状态</th>
            <th style="width: 150px">操作</th>
          </tr>
        </thead>
        <tbody>
          <template v-if="loading">
            <tr v-for="i in 6" :key="'skeleton-' + i">
              <td v-for="j in 9" :key="j"><div class="skeleton-bar"></div></td>
            </tr>
          </template>

          <tr v-else-if="!rows.length">
            <td colspan="9" class="empty-state">
              <Inbox :size="20" />
              <span>没有符合条件的注册申请</span>
            </td>
          </tr>

          <tr v-for="row in rows" v-else :key="row.id">
            <td><code>{{ row.username }}</code></td>
            <td>{{ row.realName ?? '—' }}</td>
            <td><code>{{ row.phone ?? '—' }}</code></td>
            <td class="cell-email">{{ row.email ?? '—' }}</td>
            <td>{{ roleLabel(row.roleCode) }}</td>
            <td><code>{{ row.registerIp ?? '—' }}</code></td>
            <td><code>{{ timeText(row.createdAt) }}</code></td>
            <td>
              <span class="status-chip" :class="statusTone(row.status)">
                {{ row.status ?? STATUS_PENDING }}
              </span>
            </td>
            <td>
              <!-- 只有待审核的行才给操作按钮：后端对已处理的申请再点会返回 40008 -->
              <div v-if="row.status === STATUS_PENDING" class="row-actions">
                <button
                  class="mini-btn approve"
                  :disabled="busyId === row.id || !canReview"
                  :title="canReview ? '审核通过' : '缺少 users:approve 权限'"
                  @click="approve(row)"
                >
                  <Loader2 v-if="busyId === row.id" :size="12" class="spin" />
                  <Check v-else :size="12" />通过
                </button>
                <button
                  class="mini-btn reject"
                  :disabled="busyId === row.id || !canReview"
                  :title="canReview ? '审核拒绝' : '缺少 users:approve 权限'"
                  @click="openReject(row)"
                >
                  <X :size="12" />拒绝
                </button>
              </div>
              <span v-else class="cell-sub">
                {{ row.reviewer ? `由 ${row.reviewer} 处理` : '已处理' }}
              </span>
            </td>
          </tr>
        </tbody>
      </table>

      <footer v-if="page.pages > 1" class="panel-foot">
        <span>第 {{ page.current }} / {{ page.pages }} 页 · 共 {{ page.total }} 条</span>
        <div class="pager">
          <button class="op-btn" :disabled="page.current <= 1" @click="goPage(page.current - 1)">
            上一页
          </button>
          <button
            class="op-btn"
            :disabled="page.current >= page.pages"
            @click="goPage(page.current + 1)"
          >
            下一页
          </button>
        </div>
      </footer>
    </section>

    <!-- 拒绝原因 -->
    <FormModal
      :open="rejectDialog.open"
      eyebrow="REJECT APPLICATION"
      title="填写拒绝原因"
      :subtitle="`该原因会回显给申请人 ${rejectDialog.username}`"
      confirm-text="确认拒绝"
      cancel-text="返回"
      destructive
      :loading="busyId === rejectDialog.id"
      @close="closeReject"
      @confirm="submitReject"
    >
      <label class="form-label" for="reject-reason">拒绝原因 <i>*</i></label>
      <textarea
        id="reject-reason"
        v-model="rejectDialog.reason"
        class="form-input"
        rows="4"
        maxlength="255"
        placeholder="例如：所属单位信息无法核实，请补充材料后重新申请"
      ></textarea>
      <p class="modal-counter">{{ rejectDialog.reason.length }} / 255</p>
    </FormModal>
  </div>
</template>

<style scoped>
.app-shell { display: flex; flex-direction: column; gap: 14px; }

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  padding: 14px 18px;
  background: var(--c-surface);
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-md);
}
.page-header h2 {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0 0 4px;
  color: var(--c-scrim);
  font-size: 16px;
}
.page-header p { margin: 0; color: var(--c-text-3); font-size: 12px; }
.actions { display: flex; gap: 8px; }

.op-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 7px 12px;
  border: 1px solid var(--c-brand-tint);
  border-radius: var(--r-sm);
  background: var(--c-surface);
  color: var(--c-ink-1);
  font-size: 12px;
  cursor: pointer;
  transition: background 0.18s, opacity 0.18s;
}
.op-btn:hover:not(:disabled) { background: var(--c-brand-mist); }
.op-btn:disabled { opacity: 0.55; cursor: not-allowed; }

.filter-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  background: var(--c-surface);
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-md);
}
.select-wrap {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--c-text-2);
  font-size: 12px;
}
.select-wrap select {
  padding: 7px 9px;
  border: 1px solid var(--c-brand-tint);
  border-radius: var(--r-sm);
  background: var(--c-surface);
  color: var(--c-ink-1);
  font-size: 12px;
  outline: none;
}

.search-input {
  display: flex;
  align-items: center;
  gap: 4px;
  width: 260px;
  padding: 0 9px;
  border: 1px solid var(--c-brand-tint);
  border-radius: var(--r-sm);
  background: var(--c-surface-2);
}
.search-input input {
  width: 100%;
  padding: 7px 0;
  border: 0;
  background: transparent;
  outline: none;
  font-size: 12px;
}

.result-count { margin-left: auto; color: var(--c-text-4); font-size: 11px; }

.table-panel {
  overflow: hidden;
  background: var(--c-surface);
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-md);
}
.data-table { width: 100%; font-size: 12px; }
.data-table td.cell-email {
  max-width: 190px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.empty-state {
  padding: 34px 0;
  text-align: center;
  color: var(--c-text-4);
}
.empty-state :deep(svg) { display: block; margin: 0 auto 8px; opacity: 0.6; }

.status-chip {
  display: inline-block;
  padding: 3px 9px;
  border-radius: var(--r-sm);
  font-size: 11px;
  font-weight: 500;
  white-space: nowrap;
}
.status-chip.pending {
  background: rgba(74, 140, 194, 0.12);
  color: #3d7ca8;
  border: 1px solid rgba(74, 140, 194, 0.3);
}
.status-chip.approved {
  background: rgba(111, 158, 127, 0.12);
  color: var(--c-brand-hover);
  border: 1px solid rgba(111, 158, 127, 0.32);
}
.status-chip.rejected {
  background: rgba(199, 85, 59, 0.1);
  color: #b34a30;
  border: 1px solid rgba(199, 85, 59, 0.28);
}

.row-actions { display: flex; gap: 6px; }
.mini-btn {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 4px 9px;
  border-radius: var(--r-sm);
  font-size: 11px;
  cursor: pointer;
  transition: background 0.18s, opacity 0.18s;
}
.mini-btn:disabled { opacity: 0.55; cursor: not-allowed; }
.mini-btn.approve {
  border: 1px solid rgba(111, 158, 127, 0.4);
  background: rgba(111, 158, 127, 0.1);
  color: var(--c-brand-hover);
}
.mini-btn.approve:hover:not(:disabled) { background: rgba(111, 158, 127, 0.2); }
.mini-btn.reject {
  border: 1px solid rgba(199, 85, 59, 0.36);
  background: rgba(199, 85, 59, 0.08);
  color: #b34a30;
}
.mini-btn.reject:hover:not(:disabled) { background: rgba(199, 85, 59, 0.16); }

.cell-sub { color: var(--c-text-4); font-size: 10px; }

.panel-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  border-top: 1px solid var(--c-brand-mist);
  color: var(--c-text-4);
  font-size: 11px;
}
.pager { display: flex; gap: 8px; }

/* 拒绝弹窗 */
.form-label i { color: var(--c-danger, #c7553b); font-style: normal; }
textarea.form-input { resize: vertical; min-height: 88px; }
.modal-counter {
  margin: 6px 0 4px;
  text-align: right;
  color: var(--c-text-4);
  font-size: 10px;
}

.skeleton-bar {
  height: 14px;
  max-width: 78%;
  border-radius: 4px;
  background: linear-gradient(90deg, rgba(248, 250, 252, 0.05) 25%, rgba(248, 250, 252, 0.1) 50%, rgba(248, 250, 252, 0.05) 75%);
  background-size: 200% 100%;
  animation: skeleton-loading 1.5s ease-in-out infinite;
}
@keyframes skeleton-loading {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}

.spin { animation: app-spin 0.9s linear infinite; }
@keyframes app-spin { to { transform: rotate(360deg); } }

@media (max-width: 900px) {
  .filter-bar { flex-wrap: wrap; }
  .search-input { width: 100%; }
  .result-count { margin-left: 0; }
}
</style>