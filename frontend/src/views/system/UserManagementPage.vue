<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  Check, Edit, Grid2x2, Key, Loader2, Mail, Pencil, Plus, Search, Trash2, UserPlus, Users, X,
} from 'lucide-vue-next'
import { dashboardApi } from '../../services/dashboardApi'
import { ROLE_OPTIONS, hasPermission, getAuthUser } from '../../stores/auth'
import ConfirmDialog from '../../components/common/ConfirmDialog.vue'
import { useEscapeClose } from '../../composables/useEscapeClose'

const emit = defineEmits(['notify'])
defineProps({ searchTerm: { type: String, default: '' }, userRole: { type: String, default: 'admin' } })

const operator = getAuthUser() ?? { name: '李晨曦', role: 'admin' }
const loading = ref(false)
const saving = ref(false)
const users = ref({ items: [], total: 0 })
const roles = ref([])
const filter = reactive({ keyword: '', role: '' })
const editDialog = ref(false)
const editingUser = ref(null)
const isNew = ref(false)
const activeTab = ref('users')
const resetDialog = ref(false)
const resettingUser = ref(null)
const resetPassword = ref('')
const resetSaving = ref(false)

const roleColor = {
  admin: '#c7553b',
  attendant: '#6f9e7f',
  expert: '#9b761e',
  maintenance: '#4a7a5e',
}

// ── 权限矩阵数据 ──
// 角色顺序与标签对齐后端 RoleController。code 为 sys_role.role_code（下划线形式）。
const ROLES_ORDER = [
  { value: 'admin', label: '系统管理员', color: '#c7553b' },
  { value: 'system_admin', label: '运维管理员', color: '#7c3aed' },
  { value: 'emergency_commander', label: '应急指挥员', color: '#dc2626' },
  { value: 'attendant', label: '值守人员', color: '#6f9e7f' },
  { value: 'expert', label: '专家', color: '#9b761e' },
  { value: 'ai_engineer', label: 'AI 工程师', color: '#0891b2' },
  { value: 'maintenance', label: '设备运维', color: '#4a7a5e' },
]

/**
 * 模块 → 该模块对应的权限码列表。
 *
 * 矩阵单元格的「有权限」判定 = 该角色权限集与本模块权限码存在交集。
 * 权限码权威定义在后端 PermissionRegistry，本映射仅负责"把权限码归到展示模块"。
 */
const MODULE_PERMISSIONS = {
  '综合态势大屏': ['overview:view'],
  '实时监测地图': ['map:view'],
  'AI 巡检任务': ['ai:task:view'],
  'AI 推理结果': ['ai:result:view'],
  'AI 综合研判': ['ai:insight:view'],
  '灾害事件列表': ['hazards:view'],
  '人工确认工作台': ['confirmation:view'],
  '统一事件中心': ['events:view'],
  '预警中心': ['warnings:view'],
  '应急事件': ['emergency:view'],
  '资源调度指挥': ['resource:view'],
  '社交媒体舆情': ['ai:insight:view'],
  '灾害预测': ['ai:forecast:view'],
  '灾损评估': ['ai:insight:view'],
  '现场巡检': ['inspection:view'],
  '设备管理': ['devices:view'],
  '数据源接入': ['datasource:view'],
  '统计分析': ['analytics:view'],
  '历史灾害库': ['history:view'],
  '用户与权限': ['users:view'],
  '日志审计': ['audit:view'],
  '系统监控': ['monitor:view'],
  '消息中心': ['messages:view'],
  '待办中心': ['todo:view'],
  '审批中心': ['approval:view'],
  'AI 模型管理': ['ai:model:view'],
  '通知模板': ['notice:view'],
  '通知渠道配置': ['notice:view'],
  '预案管理': ['plans:view'],
  '帮助中心': ['settings:view'],
  '备份恢复': ['backup:view'],
  '账号安全': ['profile:view'],
  '报表中心': ['reports:view'],
  '人员管理': ['personnel:view'],
  '路径规划': ['route:view'],
  '会签中心': ['sign:view'],
  '规则引擎': ['rules:view'],
}

/** 权限矩阵（来自后端 GET /api/roles/permission-matrix）。 */
const permissionMatrixData = ref(null)
const matrixLoading = ref(false)
const matrixError = ref('')

/** 各角色权限集合（Set 加速查询）。 */
const rolePermissionSets = computed(() => {
  const matrix = permissionMatrixData.value?.matrix ?? {}
  return new Map(Object.entries(matrix).map(([role, perms]) => [role, new Set(perms ?? [])]))
})

/** 任一权限码命中即视为该角色对此模块"有权限"。 */
function roleHasModule(role, module) {
  const codes = MODULE_PERMISSIONS[module] ?? []
  const set = rolePermissionSets.value.get(role)
  if (!set || codes.length === 0) return false
  return codes.some((c) => set.has(c))
}

/** 矩阵行 = 模块 × 各角色是否有权限。 */
const matrixRows = computed(() => {
  if (!permissionMatrixData.value) return []
  return Object.keys(MODULE_PERMISSIONS).map((module) => {
    const perms = {}
    for (const col of ROLES_ORDER) {
      perms[col.value] = roleHasModule(col.value, module) ? '●' : '○'
    }
    return [module, perms]
  })
})

const matrixCols = computed(() => ROLES_ORDER)

async function loadPermissionMatrix() {
  matrixLoading.value = true
  matrixError.value = ''
  try {
    permissionMatrixData.value = await dashboardApi.getPermissionMatrix()
  } catch (e) {
    permissionMatrixData.value = null
    matrixError.value = e?.message ?? '权限矩阵加载失败'
  } finally {
    matrixLoading.value = false
  }
}

const cellStyle = (val) =>
  val === '●'
    ? { background: '#dcfce7', color: '#166534', textAlign: 'center' }
    : { background: '#f1f6f2', color: '#c8d8ce', textAlign: 'center' }

const filteredUsers = computed(() => {
  let list = users.value.items ?? []
  if (filter.role) list = list.filter((u) => u.role === filter.role)
  if (filter.keyword.trim()) {
    const k = filter.keyword.trim().toLowerCase()
    list = list.filter(
      (u) =>
        (u.username ?? '').toLowerCase().includes(k) ||
        (u.name ?? '').toLowerCase().includes(k) ||
        (u.org ?? '').toLowerCase().includes(k)
    )
  }
  return list
})

async function loadUsers() {
  loading.value = true
  try {
    const [u, r] = await Promise.all([
      dashboardApi.getUsers({ keyword: filter.keyword || undefined, role: filter.role || undefined }),
      dashboardApi.getRoles(),
    ])
    // 统一数据格式：兼容 records / items 两种返回
    const list = u?.records ?? u?.items ?? []
    users.value = { items: list, total: u?.total ?? list.length }
    roles.value = r ?? []
  } catch (e) {
    emit('notify', e.message ?? '加载用户失败')
  } finally {
    loading.value = false
  }
}

async function loadRoles() {
  try {
    roles.value = (await dashboardApi.getRoles()) ?? []
  } catch {}
}

function openCreate() {
  isNew.value = true
  editingUser.value = {
    id: null,
    username: '',
    name: '',
    role: 'attendant',
    org: '',
    enabled: true,
    email: '',
  }
  editDialog.value = true
}

function openEdit(user) {
  isNew.value = false
  editingUser.value = { ...user }
  editDialog.value = true
}

async function save() {
  if (!editingUser.value) return
  if (!editingUser.value.username?.trim() || !editingUser.value.name?.trim()) {
    return emit('notify', '账号和姓名不能为空')
  }
  saving.value = true
  try {
    if (isNew.value) {
      await dashboardApi.createUser(editingUser.value)
      emit('notify', '用户创建成功（已发送初始密码邮件）')
    } else {
      await dashboardApi.updateUser(editingUser.value.id, editingUser.value)
      emit('notify', '用户已更新')
    }
    editDialog.value = false
    await loadUsers()
  } catch (e) {
    emit('notify', `保存失败：${e?.message ?? e}`)
  } finally {
    saving.value = false
  }
}

const confirmDialogRef = ref(null)

// ESC 键关闭编辑弹窗
useEscapeClose(() => { editDialog.value = false }, editDialog)

// ESC 键关闭密码重置弹窗
useEscapeClose(() => { resetDialog.value = false }, resetDialog)

async function remove(user) {
  const ok = await confirmDialogRef.value?.show({
    title: '删除用户',
    message: `确认删除用户 ${user.name}？该操作不可撤销。`,
    confirmText: '删除',
    cancelText: '取消',
    isDanger: true
  })
  if (!ok) return
  try {
    await dashboardApi.deleteUser(user.id)
    emit('notify', '用户已删除')
    await loadUsers()
  } catch (e) {
    emit('notify', `删除失败：${e?.message ?? e}`)
  }
}

async function toggleEnabled(user) {
  const targetState = !user.enabled
  try {
    await dashboardApi.updateUser(user.id, { enabled: targetState })
    user.enabled = targetState
    emit('notify', `已${targetState ? '启用' : '禁用'} ${user.name}`)
  } catch (e) {
    emit('notify', `操作失败：${e?.message ?? e}`)
  }
}

function openResetPassword(user) {
  resettingUser.value = user
  resetPassword.value = ''
  resetDialog.value = true
}

async function doResetPassword() {
  if (!resettingUser.value) return
  if (!resetPassword.value.trim() || resetPassword.value.length < 6) {
    return emit('notify', '密码长度至少 6 位')
  }
  resetSaving.value = true
  try {
    await dashboardApi.resetUserPassword(resettingUser.value.id, resetPassword.value)
    emit('notify', `${resettingUser.value.name} 的密码已重置`)
    resetDialog.value = false
  } catch (e) {
    emit('notify', `重置密码失败：${e?.message ?? e}`)
  } finally {
    resetSaving.value = false
  }
}

function roleLabel(code) {
  return ROLE_OPTIONS.find((r) => r.value === code)?.label ?? code
}

watch(filter, loadUsers, { deep: true })

onMounted(async () => {
  await Promise.all([loadUsers(), loadRoles(), loadPermissionMatrix()])
})
</script>

<template>
  <div class="users-shell">
    <header class="page-header">
      <div>
      </div>
      <button v-if="hasPermission(operator.role, 'users:create')" class="primary-btn" @click="openCreate">
        <UserPlus :size="14" />新建用户
      </button>
    </header>

    <section class="info-grid">
      <article class="info-card">
        <h4>角色矩阵</h4>
        <table>
          <thead>
            <tr><th>角色</th><th>说明</th><th>用户数</th></tr>
          </thead>
          <tbody>
            <tr v-for="r in roles" :key="r.code">
              <td>
                <em class="role-chip" :style="{ background: roleColor[r.code] + '22', color: roleColor[r.code] }">
                  {{ r.label }}
                </em>
              </td>
              <td>{{ r.description }}</td>
              <td>{{ r.userCount }}</td>
            </tr>
          </tbody>
        </table>
      </article>

      <article class="info-card">
        <h4>权限粒度说明</h4>
        <ul class="explain-list">
          <li><strong>菜单权限</strong>：是否能在侧边栏看到某个模块</li>
          <li><strong>操作权限</strong>：能否在该模块执行新增/编辑/删除</li>
          <li><strong>数据权限</strong>：仅查看本部门数据 / 全局数据</li>
        </ul>
        <p class="hint">
          <Mail :size="12" /> 权限策略基于后端 <code>ROLE_PERMISSION_MAP</code> 矩阵，
          任何修改都需要以源代码方式提交（v1.7.0 暂不支持 UI 拖拽配置）
        </p>
      </article>
    </section>

    <section class="tab-bar">
      <button
        class="tab-btn"
        :class="{ active: activeTab === 'users' }"
        @click="activeTab = 'users'"
      >
        <Users :size="14" /> 用户列表
      </button>
      <button
        class="tab-btn"
        :class="{ active: activeTab === 'matrix' }"
        @click="activeTab = 'matrix'"
        v-if="hasPermission(operator.role, 'users:view')"
      >
        <Grid2x2 :size="14" /> 权限矩阵
      </button>
    </section>

    <!-- 用户列表 -->
    <section v-if="activeTab === 'users'" class="users-panel">
      <header class="filter-bar">
        <div class="search-input">
          <Search :size="14" />
          <input v-model="filter.keyword" placeholder="搜索账号 / 姓名 / 部门" />
        </div>
        <select v-model="filter.role">
          <option value="">全部角色</option>
          <option v-for="r in ROLE_OPTIONS" :key="r.value" :value="r.value">{{ r.label }}</option>
        </select>
      </header>

      <table class="users-table">
        <thead>
          <tr>
            <th>账号</th><th>姓名</th><th>角色</th><th>部门</th><th>最后登录</th><th>状态</th><th>操作</th>
          </tr>
        </thead>
        <tbody>
          <template v-if="loading">
            <tr v-for="i in 5" :key="'skeleton-' + i">
              <td v-for="j in 7" :key="j">
                <div class="skeleton-bar"></div>
              </td>
            </tr>
          </template>
          <tr v-else-if="!filteredUsers.length">
            <td colspan="7" class="empty-state">暂无用户</td>
          </tr>
          <tr v-for="u in filteredUsers" :key="u.id">
            <td><code>{{ u.username }}</code></td>
            <td><strong>{{ u.name }}</strong></td>
            <td>
              <em class="role-chip" :style="{ background: roleColor[u.role] + '22', color: roleColor[u.role] }">
                {{ roleLabel(u.role) }}
              </em>
            </td>
            <td>{{ u.org }}</td>
            <td>{{ u.lastLogin }}</td>
            <td>
              <em class="state-chip" :class="u.enabled ? 'on' : 'off'">
                {{ u.enabled ? '已启用' : '已禁用' }}
              </em>
            </td>
            <td class="op-col">
              <button class="op-btn" :title="'编辑'" @click="openEdit(u)" v-if="hasPermission(operator.role, 'users:edit')">
                <Pencil :size="13" />
              </button>
              <button class="op-btn" :title="'重置密码'" @click="openResetPassword(u)" v-if="hasPermission(operator.role, 'users:edit')">
                <Key :size="13" />
              </button>
              <button class="op-btn" :title="u.enabled ? '禁用' : '启用'" @click="toggleEnabled(u)">
                <Check v-if="!u.enabled" :size="13" />
                <X v-else :size="13" />
              </button>
              <button class="op-btn danger" :title="'删除'" @click="remove(u)" v-if="hasPermission(operator.role, 'users:delete')">
                <Trash2 :size="13" />
              </button>
            </td>
          </tr>
        </tbody>
      </table>

      <footer class="panel-foot">
        <span>共 {{ users.total ?? filteredUsers.length }} 名用户</span>
        <span>角色权限改动审计：自动留痕</span>
      </footer>
    </section>

    <!-- 权限矩阵 -->
    <section v-if="activeTab === 'matrix'" class="matrix-panel">
      <div class="matrix-header">
        <div class="matrix-title">
          <Grid2x2 :size="15" />
          <span>角色 × 模块权限矩阵</span>
          <span class="matrix-legend">
            <span class="legend-dot has" />● 有权限
            <span class="legend-dot no" />○ 无权限
          </span>
        </div>
        <p class="matrix-desc">
          矩阵由后端权限注册表实时生成（<code>GET /api/roles/permission-matrix</code>，权威源 <code>PermissionRegistry</code>）。
          权限码的增删改请通过 <code>scripts/fix/sync-permission-registry.mjs</code> 同步前后端。
        </p>
      </div>

      <div v-if="matrixLoading" class="matrix-state">正在加载权限矩阵…</div>
      <div v-else-if="matrixError" class="matrix-state matrix-error">
        <span>权限矩阵加载失败：{{ matrixError }}</span>
        <button class="retry-btn" @click="loadPermissionMatrix">重试</button>
      </div>

      <div v-else class="matrix-wrap">
        <table class="matrix-table">
          <thead>
            <tr>
              <th class="col-module">模块</th>
              <th
                v-for="col in matrixCols"
                :key="col.value"
                class="col-role"
                :title="col.label"
              >
                <span
                  class="role-dot"
                  :style="{ background: col.color }"
                />
                <span class="role-name">{{ col.label }}</span>
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="([module, perms]) in matrixRows"
              :key="module"
            >
              <td class="cell-module">{{ module }}</td>
              <td
                v-for="col in matrixCols"
                :key="col.value"
                :style="cellStyle(perms[col.value])"
              >{{ perms[col.value] }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <!-- 编辑弹窗 -->
    <div v-if="editDialog" class="mask" @click.self="editDialog = false">
      <div class="dialog">
        <h3>{{ isNew ? '新建用户' : '编辑用户' }}</h3>
        <div class="form-grid">
          <label>账号
            <input v-model="editingUser.username" :disabled="!isNew" placeholder="登录账号" />
          </label>
          <label>姓名
            <input v-model="editingUser.name" placeholder="真实姓名" />
          </label>
          <label>邮箱
            <input v-model="editingUser.email" placeholder="接收系统通知（选填）" />
          </label>
          <label>部门
            <input v-model="editingUser.org" placeholder="所属单位" />
          </label>
          <label>角色
            <select v-model="editingUser.role">
              <option v-for="r in ROLE_OPTIONS" :key="r.value" :value="r.value">{{ r.label }} - {{ r.description }}</option>
            </select>
          </label>
          <label>状态
            <select v-model.number="editingUser.enabled" :true-value="true" :false-value="false">
              <option :value="true">启用</option>
              <option :value="false">禁用</option>
            </select>
          </label>
        </div>
        <div v-if="isNew" class="callout">
          新建后系统会发送含初始密码的邮件给 {{ editingUser.email || editingUser.name }}，
          首次登录后需强制修改密码。
        </div>
        <div class="dialog-actions">
          <button class="dialog-btn" @click="editDialog = false">取消</button>
          <button class="dialog-btn primary" :disabled="saving" @click="save">
            {{ saving ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 重置密码弹窗 -->
    <div v-if="resetDialog" class="mask" @click.self="resetDialog = false">
      <div class="dialog" style="width: 420px;">
        <h3>重置密码</h3>
        <p class="um-hint" style="font-size: 13px; margin: 0 0 14px;">
          为 <strong>{{ resettingUser?.name }}</strong>（{{ resettingUser?.username }}）设置新密码。
        </p>
        <label class="um-field-label" style="display: block; font-size: 12px;">
          新密码
          <input
            v-model="resetPassword"
            type="password"
            placeholder="请输入新密码（至少 6 位）"
            style="width: 100%; border: 1px solid var(--c-brand-tint); border-radius: 8px; padding: 7px 10px; font-size: 13px; outline: none; margin-top: 4px; box-sizing: border-box;"
          />
        </label>
        <div class="callout" style="margin-top: 14px;">
          重置后系统会发送通知邮件给用户，提醒其首次登录后修改密码。
        </div>
        <div class="dialog-actions">
          <button class="dialog-btn" @click="resetDialog = false">取消</button>
          <button class="dialog-btn primary" :disabled="resetSaving" @click="doResetPassword">
            {{ resetSaving ? '重置中…' : '确认重置' }}
          </button>
        </div>
      </div>
    </div>
    <ConfirmDialog ref="confirmDialogRef" />
  </div>
</template>

<style scoped>
.skeleton-bar {
  height: 16px;
  background: linear-gradient(90deg, rgba(248, 250, 252, 0.05) 25%, rgba(248, 250, 252, 0.1) 50%, rgba(248, 250, 252, 0.05) 75%);
  background-size: 200% 100%;
  animation: skeleton-loading 1.5s ease-in-out infinite;
  border-radius: 4px;
  max-width: 80%;
}

@keyframes skeleton-loading {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}

.users-shell { display: flex; flex-direction: column; gap: 18px; }

.page-header {
  background: var(--c-surface); border: 1px solid var(--c-brand-soft); border-radius: var(--r-md);
  padding: 14px 18px; display: flex; justify-content: space-between; align-items: flex-start;
}
.page-header h2 { margin: 0 0 4px; display: flex; align-items: center; gap: 7px; color: var(--c-scrim); font-size: 16px; }
.page-header p { margin: 0; font-size: 12px; color: var(--c-text-2); }
.primary-btn {
  border: 0; background: var(--c-brand); color: var(--c-on-brand); padding: 8px 14px;
  border-radius: var(--r-sm); font-size: 13px; cursor: pointer;
  display: inline-flex; align-items: center; gap: 6px;
}
.primary-btn:hover { background: var(--sys-blue-deep); }

.info-grid {
  display: grid; grid-template-columns: 1.4fr 1fr; gap: 16px;
}
.info-card {
  background: var(--c-surface); border: 1px solid var(--c-brand-soft); border-radius: var(--r-md); padding: 14px 18px;
}
.info-card h4 {
  margin: 0 0 10px; font-size: 13px; color: var(--c-ink-1); display: flex; align-items: center; gap: 6px;
}
.info-card table {
  width: 100%; border-collapse: collapse; font-size: 12px;
}
.info-card th, .info-card td {
  text-align: left; padding: 7px 8px; border-bottom: 1px dashed var(--c-border-2);
}
.info-card th { color: var(--c-text-2); font-weight: 600; }
.role-chip {
  display: inline-block; font-size: 10px; padding: 2px 7px; border-radius: var(--r-pill);
  font-weight: 700; letter-spacing: .03em;
}
.state-chip {
  display: inline-block; font-size: 10px; padding: 2px 7px; border-radius: var(--r-pill);
}
.state-chip.on  { background: var(--c-success-bg); color: var(--c-brand-deep); }
.state-chip.off { background: var(--c-brand-mist); color: var(--c-text-2); }
.explain-list { padding-left: 18px; margin: 4px 0 8px; color: var(--c-ink-1); font-size: 12px; }
.explain-list li { margin-bottom: 3px; }
.hint { font-size: 11px; color: var(--c-text-4); display: flex; align-items: center; gap: 4px; }
.hint code { background: var(--c-brand-mist); padding: 1px 5px; border-radius: var(--r-xs); }

.users-panel { background: var(--c-surface); border: 1px solid var(--c-brand-soft); border-radius: var(--r-md); }
.filter-bar {
  display: flex; gap: 10px; padding: 12px 18px; border-bottom: 1px solid var(--c-brand-soft);
}
.search-input {
  display: flex; align-items: center; gap: 4px; padding: 0 9px;
  border: 1px solid var(--c-brand-tint); border-radius: var(--r-sm); background: var(--c-surface-2); flex: 1; max-width: 320px;
}
.search-input input { border: 0; background: transparent; outline: none; padding: 6px 0; font-size: 12px; width: 100%; }
.filter-bar select {
  border: 1px solid var(--c-brand-tint); border-radius: var(--r-sm); padding: 6px 9px;
  font-size: 12px; outline: none; background: var(--c-surface);
}

.users-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.users-table th, .users-table td {
  text-align: left; padding: 9px 18px; border-bottom: 1px solid var(--c-brand-mist);
}
.users-table th { color: var(--c-text-2); font-weight: 600; background: var(--c-surface-2); font-size: 12px; }
.users-table tr:hover td { background: var(--c-surface-2); }
.loading-state, .empty-state { text-align: center; color: var(--c-text-4); padding: 30px 0; }

.op-col { white-space: nowrap; }
.op-btn {
  border: 1px solid transparent; background: transparent; padding: 5px;
  border-radius: var(--r-sm); cursor: pointer; color: var(--c-text-2);
}
.op-btn:hover { background: var(--c-brand-mist); }
.op-btn.danger { color: var(--c-danger-strong); }
.op-btn.danger:hover { background: var(--c-danger-bg); }

.panel-foot {
  display: flex; justify-content: space-between; padding: 11px 18px;
  border-top: 1px solid var(--c-brand-mist); font-size: 11px; color: var(--c-text-4);
}

.mask { position: fixed; inset: 0; background: var(--c-overlay); display: flex; align-items: center; justify-content: center; z-index: 99; }
.dialog { background: var(--c-surface); padding: 22px 26px; border-radius: var(--r-lg); width: 540px; max-width: 95vw; box-shadow: 0 10px 40px rgba(0,0,0,.18); }
.dialog h3 { margin: 0 0 14px; color: var(--c-scrim); }
.form-grid {
  display: grid; grid-template-columns: 1fr 1fr; gap: 12px 14px;
}
.form-grid label { display: block; color: var(--c-ink-1); font-size: 12px; }
.form-grid label input, .form-grid label select {
  width: 100%; border: 1px solid var(--c-brand-tint); border-radius: var(--r-sm); padding: 7px 10px;
  font-size: 13px; outline: none; margin-top: 4px; box-sizing: border-box;
}
.form-grid label input:disabled { background: var(--c-brand-mist); color: var(--c-text-4); }
.callout {
  font-size: 12px; color: var(--c-text-2); padding: 8px 10px; border-radius: var(--r-sm);
  background: var(--c-warning-bg); border: 1px solid var(--c-warning-bg); margin-top: 14px;
}
.dialog-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 14px; }
.dialog-btn {
  border: 1px solid var(--c-brand-tint); background: var(--c-surface); padding: 8px 14px; border-radius: var(--r-sm);
  font-size: 13px; cursor: pointer; color: var(--c-ink-1);
}
.dialog-btn.primary { background: var(--c-brand); color: var(--c-on-brand); border-color: var(--c-brand); }
.dialog-btn:disabled { opacity: .6; cursor: not-allowed; }

.spin { animation: spin 1.2s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

/* ── 标签切换 ── */
.tab-bar {
  display: flex;
  gap: 0;
  background: var(--c-surface);
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-md);
  padding: 0 12px;
  width: fit-content;
}

.tab-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 16px;
  background: none;
  border: none;
  border-bottom: 2px solid transparent;
  font-size: 13px;
  font-weight: 500;
  color: var(--c-text-2);
  cursor: pointer;
  transition: all 0.15s;
}

.tab-btn:hover { color: var(--c-brand); }
.tab-btn.active {
  color: var(--c-brand);
  font-weight: 700;
  border-bottom-color: var(--c-brand);
}

/* ── 权限矩阵 ── */
.matrix-panel {
  background: var(--c-surface);
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-md);
  overflow: hidden;
}

.matrix-header {
  padding: 14px 18px;
  border-bottom: 1px solid var(--c-brand-soft);
}

.matrix-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 700;
  color: var(--c-scrim);
  margin-bottom: 6px;
}

.matrix-legend {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: var(--c-text-2);
  font-weight: 400;
}

.legend-dot {
  display: inline-block;
  width: 16px;
  height: 16px;
  border-radius: var(--r-xs);
  margin-right: 4px;
}

.legend-dot.has { background: var(--c-success-bg); color: var(--c-success-strong); text-align: center; font-size: 10px; }
.legend-dot.no { background: var(--c-brand-mist); color: var(--c-brand-tint); text-align: center; font-size: 10px; }

.matrix-desc {
  font-size: 12px;
  color: var(--c-text-4);
  margin: 0;
}

.matrix-desc code {
  background: var(--c-brand-mist);
  padding: 1px 5px;
  border-radius: 3px;
  font-family: monospace;
}

.matrix-state {
  padding: 28px 16px;
  text-align: center;
  font-size: 13px;
  color: var(--c-text-3);
}

.matrix-error {
  color: var(--c-danger-strong);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
}

.retry-btn {
  padding: 4px 12px;
  border: 1px solid var(--c-brand-tint);
  border-radius: var(--r-xs);
  background: var(--c-surface);
  color: var(--c-ink-1);
  font-size: 12px;
  cursor: pointer;
}

.retry-btn:hover {
  border-color: var(--c-text-3);
  background: var(--c-brand-mist);
}

.matrix-wrap {
  overflow-x: auto;
  padding: 0;
}

.matrix-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
  white-space: nowrap;
}

.matrix-table th, .matrix-table td {
  padding: 8px 12px;
  border-bottom: 1px solid var(--c-brand-mist);
  border-right: 1px solid var(--c-brand-mist);
}

.matrix-table thead th {
  background: var(--c-surface-2);
  font-weight: 700;
  color: var(--c-text-2);
  font-size: 11px;
  text-align: center;
  position: sticky;
  top: 0;
  z-index: 1;
}

.col-module { min-width: 120px; text-align: left !important; }
.col-role { min-width: 90px; }

.role-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 4px;
  vertical-align: middle;
}

.role-name { vertical-align: middle; }

.cell-module {
  font-weight: 600;
  color: var(--c-ink-1);
  background: var(--c-surface-2);
  position: sticky;
  left: 0;
  z-index: 1;
}

.matrix-table tbody tr:hover td { background: var(--c-surface-2); }

@media (max-width: 980px) {
  .info-grid { grid-template-columns: 1fr; }
  .form-grid { grid-template-columns: 1fr; }
}
/* 设计令牌收尾：模板静态色内联样式 → 类（2026-10-09） */
.um-hint { color: var(--c-text-2); }
.um-field-label { color: var(--c-ink-1); }
</style>
