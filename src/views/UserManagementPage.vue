<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import {
  Check, Edit, Loader2, Mail, Pencil, Plus, Search, Trash2, UserPlus, Users, X,
} from 'lucide-vue-next'
import { dashboardApi } from '../services/dashboardApi'
import { ROLE_OPTIONS, hasPermission, getAuthUser } from '../stores/auth'

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

const roleColor = {
  admin: '#c7553b',
  attendant: '#2b6cb0',
  expert: '#9b761e',
  maintenance: '#2a8154',
}

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
    users.value = u ?? { items: [], total: 0 }
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
      await dashboardApi.createUser?.(editingUser.value)
      emit('notify', '用户创建成功（已发送初始密码邮件）')
    } else {
      await dashboardApi.updateUser?.(editingUser.value.id, editingUser.value)
      emit('notify', '用户已更新')
    }
    editDialog.value = false
    await loadUsers()
  } catch (e) {
    emit('notify', e.message ?? '保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(user) {
  if (!window.confirm(`确认删除用户 ${user.name}？该操作不可撤销。`)) return
  try {
    await dashboardApi.deleteUser?.(user.id)
    emit('notify', '用户已删除')
    await loadUsers()
  } catch (e) {
    emit('notify', e.message ?? '删除失败')
  }
}

async function toggleEnabled(user) {
  try {
    await dashboardApi.updateUser?.(user.id, { enabled: !user.enabled })
    user.enabled = !user.enabled
    emit('notify', `已${user.enabled ? '启用' : '禁用'} ${user.name}`)
  } catch (e) {
    emit('notify', e.message ?? '操作失败')
  }
}

function roleLabel(code) {
  return ROLE_OPTIONS.find((r) => r.value === code)?.label ?? code
}

watch(filter, loadUsers, { deep: true })

onMounted(async () => {
  await Promise.all([loadUsers(), loadRoles()])
})
</script>

<template>
  <div class="users-shell">
    <header class="page-header">
      <div>
        <h2><Users :size="18" />用户与权限</h2>
        <p>管理用户、分配角色、调整权限范围。所有写操作均会留痕到审计日志。</p>
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

    <section class="users-panel">
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
          <tr v-if="loading">
            <td colspan="7" class="loading-state">
              <Loader2 :size="16" class="spin" />加载用户中…
            </td>
          </tr>
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
  </div>
</template>

<style scoped>
.users-shell { display: flex; flex-direction: column; gap: 18px; }

.page-header {
  background: #fff; border: 1px solid #e9efe9; border-radius: 11px;
  padding: 14px 18px; display: flex; justify-content: space-between; align-items: flex-start;
}
.page-header h2 { margin: 0 0 4px; display: flex; align-items: center; gap: 7px; color: #1f3b3e; font-size: 16px; }
.page-header p { margin: 0; font-size: 12px; color: #6f8587; }
.primary-btn {
  border: 0; background: #2b6cb0; color: #fff; padding: 8px 14px;
  border-radius: 8px; font-size: 13px; cursor: pointer;
  display: inline-flex; align-items: center; gap: 6px;
}
.primary-btn:hover { background: #1e568f; }

.info-grid {
  display: grid; grid-template-columns: 1.4fr 1fr; gap: 16px;
}
.info-card {
  background: #fff; border: 1px solid #e9efe9; border-radius: 11px; padding: 14px 18px;
}
.info-card h4 {
  margin: 0 0 10px; font-size: 13px; color: #35575b; display: flex; align-items: center; gap: 6px;
}
.info-card table {
  width: 100%; border-collapse: collapse; font-size: 12px;
}
.info-card th, .info-card td {
  text-align: left; padding: 7px 8px; border-bottom: 1px dashed #eff4f2;
}
.info-card th { color: #6f8587; font-weight: 600; }
.role-chip {
  display: inline-block; font-size: 10px; padding: 2px 7px; border-radius: 999px;
  font-weight: 700; letter-spacing: .03em;
}
.state-chip {
  display: inline-block; font-size: 10px; padding: 2px 7px; border-radius: 999px;
}
.state-chip.on  { background: rgba(93,180,134,0.15); color: #2a8154; }
.state-chip.off { background: #f1f5f4; color: #6f8587; }
.explain-list { padding-left: 18px; margin: 4px 0 8px; color: #4a6367; font-size: 12px; }
.explain-list li { margin-bottom: 3px; }
.hint { font-size: 11px; color: #9baead; display: flex; align-items: center; gap: 4px; }
.hint code { background: #f4f7f6; padding: 1px 5px; border-radius: 4px; }

.users-panel { background: #fff; border: 1px solid #e9efe9; border-radius: 11px; }
.filter-bar {
  display: flex; gap: 10px; padding: 12px 18px; border-bottom: 1px solid #e9efe9;
}
.search-input {
  display: flex; align-items: center; gap: 4px; padding: 0 9px;
  border: 1px solid #d8e6e3; border-radius: 8px; background: #fafdfc; flex: 1; max-width: 320px;
}
.search-input input { border: 0; background: transparent; outline: none; padding: 6px 0; font-size: 12px; width: 100%; }
.filter-bar select {
  border: 1px solid #d8e6e3; border-radius: 8px; padding: 6px 9px;
  font-size: 12px; outline: none; background: #fff;
}

.users-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.users-table th, .users-table td {
  text-align: left; padding: 9px 18px; border-bottom: 1px solid #f1f5f4;
}
.users-table th { color: #6f8587; font-weight: 600; background: #fafdfc; font-size: 12px; }
.users-table tr:hover td { background: #fbfefd; }
.loading-state, .empty-state { text-align: center; color: #9baead; padding: 30px 0; }

.op-col { white-space: nowrap; }
.op-btn {
  border: 1px solid transparent; background: transparent; padding: 5px;
  border-radius: 6px; cursor: pointer; color: #72908f;
}
.op-btn:hover { background: #eef5f3; }
.op-btn.danger { color: #c7553b; }
.op-btn.danger:hover { background: #fdece8; }

.panel-foot {
  display: flex; justify-content: space-between; padding: 11px 18px;
  border-top: 1px solid #f1f5f4; font-size: 11px; color: #9baead;
}

.mask { position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: center; justify-content: center; z-index: 99; }
.dialog { background: #fff; padding: 22px 26px; border-radius: 12px; width: 540px; max-width: 95vw; box-shadow: 0 10px 40px rgba(0,0,0,.18); }
.dialog h3 { margin: 0 0 14px; color: #1f3b3e; }
.form-grid {
  display: grid; grid-template-columns: 1fr 1fr; gap: 12px 14px;
}
.form-grid label { display: block; color: #35575b; font-size: 12px; }
.form-grid label input, .form-grid label select {
  width: 100%; border: 1px solid #d8e6e3; border-radius: 8px; padding: 7px 10px;
  font-size: 13px; outline: none; margin-top: 4px; box-sizing: border-box;
}
.form-grid label input:disabled { background: #f1f5f4; color: #9baead; }
.callout {
  font-size: 12px; color: #6f8587; padding: 8px 10px; border-radius: 6px;
  background: #fff8ec; border: 1px solid #f4e3c1; margin-top: 14px;
}
.dialog-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 14px; }
.dialog-btn {
  border: 1px solid #d8e6e3; background: #fff; padding: 8px 14px; border-radius: 8px;
  font-size: 13px; cursor: pointer; color: #35575b;
}
.dialog-btn.primary { background: #2b6cb0; color: #fff; border-color: #2b6cb0; }
.dialog-btn:disabled { opacity: .6; cursor: not-allowed; }

.spin { animation: spin 1.2s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

@media (max-width: 980px) {
  .info-grid { grid-template-columns: 1fr; }
  .form-grid { grid-template-columns: 1fr; }
}
</style>
