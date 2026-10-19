<script setup>
/**
 * 账号安全（独立页面 /dashboard/account-security）。
 *
 * 当前账号安全总览：
 * - 顶部大卡片：当前账号基本信息（姓名 / 角色 / 上次登录时间 / 上次登录 IP / 设备）
 * - 主体四个 Tab：
 *   1. 双因子认证 (2FA)：开关 + 三种方式 (TOTP / 短信 / 邮箱) 多选 + 二维码占位 + 备用码 chips + 重新生成
 *   2. 登录历史：时间 / IP / 设备 / 地理位置 / 浏览器 / 状态 / 操作（踢出）
 *   3. 活跃会话：设备 / IP / 登录时间 / 最后活跃 / 操作（登出）
 *   4. 操作审计：时间 / 操作类型 / IP / 详情 / 状态
 *
 * 所有 API 调用均通过 dashboardApi 走真实后端；离线时统一 requireBackend 拒绝并落空态。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import ConfirmDialog from '../../components/common/ConfirmDialog.vue'
import {
  Eye,
  History,
  Key,
  KeyRound,
  LogOut,
  Mail,
  MonitorPlay,
  RefreshCw,
  Shield,
  ShieldCheck,
  Smartphone,
} from 'lucide-vue-next'
import { dashboardApi } from '../../services/dashboardApi'
import { getAuthUser, getRoleLabel, hasPermission } from '../../stores/auth'

const props = defineProps({
  searchTerm: { type: String, default: '' },
  userRole: { type: String, default: 'admin' },
})
const emit = defineEmits(['notify'])
const confirmDialogRef = ref(null)

const operator = computed(() => getAuthUser() ?? { name: '李晨曦', role: 'admin', username: 'lichenxi' })
const canView = computed(() => hasPermission(props.userRole || operator.value.role, 'settings:view'))

// 顶部基本信息
const profile = ref(null)
const profileLoading = ref(false)
const lastLoginText = computed(() => profile.value?.lastLoginAt || '—')
const lastIp = computed(() => profile.value?.lastIp || '—')
const lastDevice = computed(() => profile.value?.lastDevice || '—')
const accountName = computed(() => profile.value?.name || operator.value?.name || '—')
const accountRole = computed(() => getRoleLabel(profile.value?.role || operator.value?.role || 'admin'))

// Tab 1: 2FA
const twofa = reactive({
  enabled: false,
  methods: ['totp'],
  qrPayload: '',
  qrUri: '',
  backupCodes: [],
  generatedAt: '',
})
const twofaLoading = ref(false)
const twofaSaving = ref(false)
const twofaCode = ref('')

const METHOD_META = {
  totp:  { label: 'TOTP 验证器', icon: KeyRound,  desc: 'Google Authenticator / Microsoft Authenticator / 1Password' },
  sms:   { label: '短信验证码', icon: Smartphone, desc: '通过预留手机号接收一次性验证码' },
  email: { label: '邮箱验证码', icon: Mail,      desc: '通过工作邮箱接收一次性验证码' },
}

async function loadAccountSecurity() {
  profileLoading.value = true
  twofaLoading.value = true
  try {
    const data = await dashboardApi.getAccountSecurity()
    if (data) {
      profile.value = data.profile ?? null
      twofa.enabled = Boolean(data.twofa?.enabled)
      twofa.methods = Array.isArray(data.twofa?.methods) && data.twofa.methods.length
        ? [...data.twofa.methods]
        : ['totp']
      twofa.qrPayload = data.twofa?.qrPayload ?? ''
      twofa.qrUri = data.twofa?.otpauthUri ?? ''
      twofa.backupCodes = Array.isArray(data.twofa?.backupCodes) ? [...data.twofa.backupCodes] : []
      twofa.generatedAt = data.twofa?.backupCodesGeneratedAt ?? ''
    }
  } catch (e) {
    // 离线 / 后端不可达时统一落空态，避免阻塞其它 Tab
    profile.value = null
    twofa.enabled = false
    twofa.methods = []
    twofa.qrPayload = ''
    twofa.qrUri = ''
    twofa.backupCodes = []
    twofa.generatedAt = ''
    emit('notify', e?.message ?? '账号安全数据需要后端支持')
  } finally {
    profileLoading.value = false
    twofaLoading.value = false
  }
}

function toggleMethod(m) {
  const idx = twofa.methods.indexOf(m)
  if (idx >= 0) {
    if (twofa.methods.length === 1) {
      emit('notify', '至少保留一种验证方式')
      return
    }
    twofa.methods.splice(idx, 1)
  } else {
    twofa.methods.push(m)
  }
}

async function save2FA() {
  if (twofaSaving.value) return
  twofaSaving.value = true
  try {
    await dashboardApi.toggle2FA({ enabled: twofa.enabled, methods: [...twofa.methods] })
    emit('notify', twofa.enabled ? '双因子认证已启用' : '双因子认证已关闭')
    await loadAccountSecurity()
  } catch (e) {
    emit('notify', e?.message ?? '保存失败')
  } finally {
    twofaSaving.value = false
  }
}

async function regenerateCodes() {
  const ok = await confirmDialogRef.value?.show({
    title: '确认操作',
    message: '重新生成后将作废所有旧备用码，确认继续？',
    confirmText: '确认',
    cancelText: '取消',
    isDanger: true
  })
  if (!ok) return
  twofaLoading.value = true
  try {
    const res = await dashboardApi.regenerateBackupCodes()
    if (res?.backupCodes) {
      twofa.backupCodes = [...res.backupCodes]
      twofa.generatedAt = res.generatedAt ?? new Date().toISOString()
      emit('notify', '备用码已重新生成，请妥善保存')
    } else {
      emit('notify', '备用码生成失败')
    }
  } catch (e) {
    emit('notify', e?.message ?? '生成备用码失败')
  } finally {
    twofaLoading.value = false
  }
}

// Tab 2: 登录历史
const loginHistory = ref({ items: [], total: 0 })
const loginHistoryFilter = reactive({ status: '' })
const loginHistoryLoading = ref(false)

const STATUS_OPTIONS = [
  { value: '', label: '全部' },
  { value: 'SUCCESS', label: '成功' },
  { value: 'FAILED', label: '失败' },
]

async function loadLoginHistory() {
  loginHistoryLoading.value = true
  try {
    const data = await dashboardApi.listLoginHistory({
      status: loginHistoryFilter.status || undefined,
      size: 100,
    })
    loginHistory.value = data ?? { items: [], total: 0 }
  } catch (e) {
    loginHistory.value = { items: [], total: 0 }
    emit('notify', e?.message ?? '登录历史加载失败')
  } finally {
    loginHistoryLoading.value = false
  }
}

async function kickSessionFromHistory(id) {
  const ok = await confirmDialogRef.value?.show({
    title: '确认操作',
    message: '确定将该登录记录关联的会话踢出？',
    confirmText: '确认',
    cancelText: '取消',
    isDanger: true
  })
  if (!ok) return
  try {
    await dashboardApi.revokeSession(id)
    emit('notify', '已踢出该会话')
    await loadLoginHistory()
  } catch (e) {
    emit('notify', e?.message ?? '操作失败')
  }
}

// Tab 3: 活跃会话
const sessions = ref({ items: [], total: 0 })
const sessionsLoading = ref(false)

async function loadActiveSessions() {
  sessionsLoading.value = true
  try {
    const data = await dashboardApi.listActiveSessions()
    sessions.value = data ?? { items: [], total: 0 }
  } catch (e) {
    sessions.value = { items: [], total: 0 }
    emit('notify', e?.message ?? '活跃会话加载失败')
  } finally {
    sessionsLoading.value = false
  }
}

async function revokeOne(id) {
  const ok = await confirmDialogRef.value?.show({
    title: '确认操作',
    message: '确认登出该设备？',
    confirmText: '确认',
    cancelText: '取消',
    isDanger: true
  })
  if (!ok) return
  try {
    await dashboardApi.revokeSession(id)
    emit('notify', '已登出该设备')
    await loadActiveSessions()
  } catch (e) {
    emit('notify', e?.message ?? '操作失败')
  }
}

async function revokeAllOthers() {
  const others = (sessions.value.items ?? []).filter((s) => !s.current)
  if (!others.length) return emit('notify', '没有其它活跃会话')
  const ok = await confirmDialogRef.value?.show({
    title: '确认操作',
    message: `将登出 ${others.length} 个其它设备，确认？`,
    confirmText: '确认',
    cancelText: '取消',
    isDanger: true
  })
  if (!ok) return
  for (const s of others) {
    try { await dashboardApi.revokeSession(s.id) } catch {}
  }
  emit('notify', '其它设备会话已全部登出')
  await loadActiveSessions()
}

// Tab 4: 操作审计
const audit = ref({ items: [], total: 0 })
const auditFilter = reactive({ type: '' })
const auditLoading = ref(false)

const AUDIT_TYPE_OPTIONS = [
  { value: '', label: '全部' },
  { value: 'PASSWORD_CHANGE', label: '修改密码' },
  { value: 'TWOFA_ENABLE', label: '启用 2FA' },
  { value: 'TWOFA_DISABLE', label: '关闭 2FA' },
  { value: 'LOGIN', label: '登录' },
  { value: 'LOGOUT', label: '登出' },
  { value: 'PROFILE_UPDATE', label: '修改资料' },
  { value: 'BACKUP_CODES_REGENERATE', label: '重备码' },
]

const auditTypeColor = {
  PASSWORD_CHANGE: '#9b761e',
  TWOFA_ENABLE: '#4a7a5e',
  TWOFA_DISABLE: '#c7553b',
  LOGIN: '#6f9e7f',
  LOGOUT: '#76847a',
  PROFILE_UPDATE: '#5d95c1',
  BACKUP_CODES_REGENERATE: '#a07ec1',
}

// 活跃会话过滤
const sessionFilter = ref('')
const filteredSessions = computed(() => {
  const list = sessions.value.items ?? []
  if (sessionFilter.value === 'current') return list.filter((s) => s.current)
  if (sessionFilter.value === 'others') return list.filter((s) => !s.current)
  return list
})

async function loadAccountAudit() {
  auditLoading.value = true
  try {
    const data = await dashboardApi.listAccountAudit({
      type: auditFilter.type || undefined,
      size: 100,
    })
    audit.value = data ?? { items: [], total: 0 }
  } catch (e) {
    audit.value = { items: [], total: 0 }
    emit('notify', e?.message ?? '操作审计加载失败')
  } finally {
    auditLoading.value = false
  }
}

// Tab 切换
const activeTab = ref('twofa')
const TABS = [
  { id: 'twofa',     label: '双因子认证', icon: Shield },
  { id: 'history',   label: '登录历史',   icon: History },
  { id: 'sessions',  label: '活跃会话',   icon: MonitorPlay },
  { id: 'audit',     label: '操作审计',   icon: ShieldCheck },
]

function selectTab(id) {
  activeTab.value = id
  if (id === 'history' && !(loginHistory.value.items?.length || loginHistoryLoading.value)) loadLoginHistory()
  if (id === 'sessions' && !(sessions.value.items?.length || sessionsLoading.value)) loadActiveSessions()
  if (id === 'audit' && !(audit.value.items?.length || auditLoading.value)) loadAccountAudit()
}

const refreshingAll = ref(false)
async function refreshAll() {
  if (refreshingAll.value) return
  refreshingAll.value = true
  try {
    await loadAccountSecurity()
    const tasks = []
    if (activeTab.value === 'history') tasks.push(loadLoginHistory())
    if (activeTab.value === 'sessions') tasks.push(loadActiveSessions())
    if (activeTab.value === 'audit') tasks.push(loadAccountAudit())
    if (tasks.length) await Promise.all(tasks)
    emit('notify', '账号安全数据已刷新')
  } finally {
    refreshingAll.value = false
  }
}

// 显示辅助
function timeText(iso) {
  if (!iso) return '—'
  try {
    return new Date(iso).toLocaleString('zh-CN', { hour12: false })
  } catch {
    return iso
  }
}

function relativeText(iso) {
  if (!iso) return '—'
  const diff = Date.now() - new Date(iso).getTime()
  if (Number.isNaN(diff)) return '—'
  const m = Math.floor(diff / 60000)
  if (m < 1) return '刚刚'
  if (m < 60) return `${m} 分钟前`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h} 小时前`
  const d = Math.floor(h / 24)
  if (d < 30) return `${d} 天前`
  return timeText(iso)
}

function statusColor(s) {
  if (s === 'SUCCESS') return '#4a7a5e'
  if (s === 'FAILED') return '#c7553b'
  return '#76847a'
}

function statusLabel(s) {
  if (s === 'SUCCESS') return '成功'
  if (s === 'FAILED') return '失败'
  return s || '—'
}

function auditTypeLabel(t) {
  return AUDIT_TYPE_OPTIONS.find((o) => o.value === t)?.label || t || '—'
}

function auditStatusLabel(s) {
  if (s === 'SUCCESS' || s === 'OK') return '成功'
  if (s === 'FAILED' || s === 'FAIL') return '失败'
  return s || '—'
}

function auditStatusColor(s) {
  if (s === 'SUCCESS' || s === 'OK') return '#4a7a5e'
  if (s === 'FAILED' || s === 'FAIL') return '#c7553b'
  return '#76847a'
}

const backupCodesCopyable = computed(() => twofa.backupCodes.join('\n'))
async function copyBackupCodes() {
  if (!twofa.backupCodes.length) return
  try {
    await navigator.clipboard.writeText(backupCodesCopyable.value)
    emit('notify', '备用码已复制到剪贴板')
  } catch {
    emit('notify', '复制失败，请手动复制')
  }
}

const profileAvatar = computed(() => (accountName.value || '?').charAt(0).toUpperCase())

onMounted(() => {
  loadAccountSecurity()
})
</script>

<template>
  <div class="account-security-shell">
    <header class="page-header">
      <div>
      </div>
      <div class="actions">
        <button class="op-btn" :disabled="refreshingAll" @click="refreshAll">
          <RefreshCw :size="14" :class="refreshingAll ? 'spin' : ''" />刷新
        </button>
      </div>
    </header>

    <section v-if="!canView" class="panel-foot-tip">
      当前角色 <strong>{{ accountRole }}</strong> 无权访问此页面（缺少 <code>settings:view</code>）。
    </section>

    <template v-else>
      <!-- 顶部账号基本信息大卡片 -->
      <section class="profile-card">
        <div class="profile-left">
          <div class="avatar">{{ profileAvatar }}</div>
          <div class="profile-meta">
            <strong class="name">{{ accountName }}</strong>
            <span class="role-chip" :style="{ background: 'color-mix(in srgb, var(--c-brand) 13%, transparent)', color: 'var(--c-brand)' }">{{ accountRole }}</span>
            <span class="username"><code>@{{ operator?.username || profile?.username || '—' }}</code></span>
          </div>
        </div>
        <div class="profile-grid">
          <div class="info-cell">
            <small>上次登录时间</small>
            <strong>{{ timeText(profile?.lastLoginAt) }}</strong>
            <em>{{ relativeText(profile?.lastLoginAt) }}</em>
          </div>
          <div class="info-cell">
            <small>上次登录 IP</small>
            <strong><code>{{ lastIp }}</code></strong>
            <em>{{ profile?.lastLocation || '未知地区' }}</em>
          </div>
          <div class="info-cell">
            <small>登录设备</small>
            <strong>{{ lastDevice }}</strong>
            <em>{{ profile?.lastOs || '—' }} · {{ profile?.lastBrowser || '—' }}</em>
          </div>
          <div class="info-cell">
            <small>账号状态</small>
            <strong>
              <em class="state-chip" :class="profile?.enabled === false ? 'off' : 'on'">
                {{ profile?.enabled === false ? '已锁定' : '正常' }}
              </em>
            </strong>
            <em>2FA: {{ twofa.enabled ? '已启用' : '未启用' }}</em>
          </div>
        </div>
        <div v-if="profileLoading" class="profile-loading">正在加载账号信息…</div>
      </section>

      <!-- Tabs 导航 -->
      <nav class="settings-tabs" role="tablist">
        <button
          v-for="t in TABS"
          :key="t.id"
          :class="['settings-tab', { 'is-active': activeTab === t.id }]"
          role="tab"
          :aria-selected="activeTab === t.id"
          @click="selectTab(t.id)"
        >
          <component :is="t.icon" :size="14" />
          <span>{{ t.label }}</span>
        </button>
      </nav>

      <!-- Tab 1: 2FA -->
      <section v-if="activeTab === 'twofa'" class="settings-panel twofa-panel">
        <div class="twofa-grid">
          <article class="twofa-card">
            <header class="twofa-card-head">
              <ShieldCheck :size="16" color="#6f9e7f" />
              <strong>启用双因子认证</strong>
              <label class="switch">
                <input type="checkbox" v-model="twofa.enabled" />
                <span class="slider"></span>
              </label>
            </header>
            <p class="muted">
              启用后，登录除密码外还需要第二因素验证。建议同时启用 TOTP 与至少一种备用方式，
              并将备用恢复码保存在安全位置。
            </p>
            <div class="method-list">
              <label
                v-for="(meta, key) in METHOD_META"
                :key="key"
                class="method-chip"
                :class="{ active: twofa.methods.includes(key) }"
              >
                <input
                  type="checkbox"
                  :checked="twofa.methods.includes(key)"
                  @change="toggleMethod(key)"
                />
                <component :is="meta.icon" :size="14" />
                <span class="m-label">{{ meta.label }}</span>
                <small class="m-desc">{{ meta.desc }}</small>
              </label>
            </div>
            <div class="twofa-actions">
              <button class="dialog-btn primary" :disabled="twofaSaving" @click="save2FA">
                {{ twofaSaving ? '保存中…' : (twofa.enabled ? '保存并启用' : '保存设置') }}
              </button>
              <span v-if="twofaLoading" class="hint">正在同步服务器配置…</span>
            </div>
          </article>

          <article class="twofa-card">
            <header class="twofa-card-head">
              <Eye :size="16" color="#cbaa76" />
              <strong>扫码绑定 TOTP</strong>
            </header>
            <div class="qr-block">
              <div class="qr-frame">
                <!-- 二维码占位（后端返回 svg / base64 时替换为 :src 即可） -->
                <div class="qr-placeholder">
                  <KeyRound :size="42" color="#6f9e7f" />
                  <small>QR 区域</small>
                </div>
              </div>
              <div class="qr-info">
                <small>otpauth URI</small>
                <code class="qr-uri">{{ twofa.qrUri || 'otpauth://totp/Disasterguard:user@example.com?secret=...' }}</code>
                <small>密钥 / Secret</small>
                <code class="qr-secret">{{ twofa.qrPayload || 'JBSWY3DPEHPK3PXP' }}</code>
                <small>绑定步骤</small>
                <ol class="qr-steps">
                  <li>打开 Google / Microsoft Authenticator</li>
                  <li>扫描上方二维码或手动输入密钥</li>
                  <li>输入 App 一次性 6 位验证码以完成绑定</li>
                </ol>
                <label class="verify-input">
                  <small>验证码</small>
                  <input v-model="twofaCode" maxlength="6" placeholder="6 位数字" />
                </label>
              </div>
            </div>
          </article>

          <article class="twofa-card span-2">
            <header class="twofa-card-head">
              <Key :size="16" color="#d9c040" />
              <strong>备用恢复码</strong>
              <button class="text-btn" :disabled="twofaLoading" @click="regenerateCodes">
                <RefreshCw :size="12" :class="twofaLoading ? 'spin' : ''" />重新生成
              </button>
            </header>
            <p class="muted">
              每个备用码仅可使用一次，遗失手机或验证器时可作为最后手段登录。
              <em v-if="twofa.generatedAt">最近一次生成：{{ timeText(twofa.generatedAt) }}</em>
            </p>
            <ul v-if="twofa.backupCodes.length" class="backup-chips">
              <li v-for="(c, i) in twofa.backupCodes" :key="i">
                <code>{{ c }}</code>
              </li>
            </ul>
            <div v-else class="empty-state small">
              尚未生成备用码。点击右上角"重新生成"以创建一组一次性备用码。
            </div>
            <div v-if="twofa.backupCodes.length" class="backup-foot">
              <button class="text-btn" @click="copyBackupCodes">
                <Eye :size="12" />一键复制全部
              </button>
              <em class="warn-text">提示：请将备用码保存在离线的密码管理器中，本页刷新后不再展示完整列表。</em>
            </div>
          </article>
        </div>

        <aside class="security-hint">
          <strong><Shield :size="13" />安全建议</strong>
          <ul>
            <li>不要在常用设备之外启用"记住此设备"选项</li>
            <li>定期更换密码，并避免在公共网络中登录</li>
            <li>如发现异常登录，请立即在"活跃会话"中强制登出并修改密码</li>
          </ul>
        </aside>
      </section>

      <!-- Tab 2: 登录历史 -->
      <section v-else-if="activeTab === 'history'" class="settings-panel">
        <div class="filter-bar">
          <div class="status-tabs">
            <button
              v-for="o in STATUS_OPTIONS"
              :key="o.value || 'all'"
              :class="['pill', { active: loginHistoryFilter.status === o.value }]"
              @click="loginHistoryFilter.status = o.value; loadLoginHistory()"
            >{{ o.label }}</button>
          </div>
          <span class="hint">
            共 {{ loginHistory.total ?? loginHistory.items.length }} 条记录
          </span>
        </div>

        <div class="table-scroll">
          <table class="data-table">
            <thead>
              <tr>
                <th style="width: 170px">登录时间</th>
                <th style="width: 130px">IP</th>
                <th style="width: 180px">设备</th>
                <th style="width: 130px">地理位置</th>
                <th style="width: 160px">浏览器</th>
                <th style="width: 90px">状态</th>
                <th style="width: 110px">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="loginHistoryLoading">
                <td colspan="7" class="loading-state">加载登录历史…</td>
              </tr>
              <tr v-else-if="!loginHistory.items.length">
                <td colspan="7" class="empty-state">暂无登录历史记录</td>
              </tr>
              <tr v-for="row in loginHistory.items" :key="row.id ?? `${row.time}-${row.ip}`">
                <td><code>{{ timeText(row.time) }}</code></td>
                <td><code>{{ row.ip }}</code></td>
                <td>{{ row.device }}</td>
                <td>{{ row.location || '—' }}</td>
                <td>{{ row.browser }}</td>
                <td>
                  <em class="status-chip" :style="{ background: statusColor(row.status) + '22', color: statusColor(row.status) }">
                    {{ statusLabel(row.status) }}
                  </em>
                </td>
                <td>
                  <button
                    v-if="row.sessionId && row.status === 'SUCCESS'"
                    class="op-btn danger"
                    @click="kickSessionFromHistory(row.sessionId)"
                  >
                    <LogOut :size="12" />踢出
                  </button>
                  <span v-else class="muted">—</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- Tab 3: 活跃会话 -->
      <section v-else-if="activeTab === 'sessions'" class="settings-panel">
        <div class="filter-bar">
          <div class="status-tabs">
            <button
              v-for="o in [{v:'',l:'全部'}, {v:'current',l:'当前会话'}, {v:'others',l:'其它设备'}]"
              :key="o.v || 'all'"
              :class="['pill', { active: sessionFilter === o.v }]"
              @click="sessionFilter = o.v"
            >{{ o.l }}</button>
          </div>
          <div class="actions">
            <button class="op-btn danger" @click="revokeAllOthers">
              <LogOut :size="13" />一键登出其它
            </button>
          </div>
        </div>

        <div class="table-scroll">
          <table class="data-table">
            <thead>
              <tr>
                <th style="width: 220px">设备</th>
                <th style="width: 130px">IP</th>
                <th style="width: 170px">登录时间</th>
                <th style="width: 170px">最后活跃</th>
                <th>地点</th>
                <th style="width: 110px">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="sessionsLoading">
                <td colspan="6" class="loading-state">加载活跃会话…</td>
              </tr>
              <tr v-else-if="!filteredSessions.length">
                <td colspan="6" class="empty-state">当前无活跃会话</td>
              </tr>
              <tr
                v-for="s in filteredSessions"
                :key="s.id"
                :class="{ 'is-current': s.current }"
              >
                <td>
                  <strong>{{ s.device }}</strong>
                  <em v-if="s.current" class="current-tag">当前</em>
                  <small v-if="s.os">{{ s.os }} · {{ s.browser }}</small>
                </td>
                <td><code>{{ s.ip }}</code></td>
                <td><code>{{ timeText(s.loginAt) }}</code></td>
                <td><code>{{ timeText(s.lastActiveAt) }}</code><small>{{ relativeText(s.lastActiveAt) }}</small></td>
                <td>{{ s.location || '—' }}</td>
                <td>
                  <button
                    v-if="!s.current"
                    class="op-btn danger"
                    @click="revokeOne(s.id)"
                  >
                    <LogOut :size="12" />登出
                  </button>
                  <em v-else class="muted">本机</em>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- Tab 4: 操作审计 -->
      <section v-else-if="activeTab === 'audit'" class="settings-panel">
        <div class="filter-bar">
          <div class="status-tabs">
            <button
              v-for="o in AUDIT_TYPE_OPTIONS"
              :key="o.value || 'all'"
              :class="['pill', { active: auditFilter.type === o.value }]"
              @click="auditFilter.type = o.value; loadAccountAudit()"
            >{{ o.label }}</button>
          </div>
          <span class="hint">共 {{ audit.total ?? audit.items.length }} 条</span>
        </div>

        <div class="table-scroll">
          <table class="data-table">
            <thead>
              <tr>
                <th style="width: 170px">时间</th>
                <th style="width: 140px">操作类型</th>
                <th style="width: 130px">IP</th>
                <th>详情</th>
                <th style="width: 90px">状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="auditLoading">
                <td colspan="5" class="loading-state">加载审计记录…</td>
              </tr>
              <tr v-else-if="!audit.items.length">
                <td colspan="5" class="empty-state">暂无操作审计记录</td>
              </tr>
              <tr v-for="row in audit.items" :key="row.id ?? `${row.time}-${row.type}`">
                <td><code>{{ timeText(row.time) }}</code></td>
                <td>
                  <em
                    class="type-chip"
                    :style="{ background: (auditTypeColor[row.type] || 'var(--c-text-2)') + '22', color: auditTypeColor[row.type] || 'var(--c-text-2)' }"
                  >{{ auditTypeLabel(row.type) }}</em>
                </td>
                <td><code>{{ row.ip }}</code></td>
                <td>{{ row.detail || row.message || '—' }}</td>
                <td>
                  <em
                    class="status-chip"
                    :style="{ background: auditStatusColor(row.status) + '22', color: auditStatusColor(row.status) }"
                  >{{ auditStatusLabel(row.status) }}</em>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <aside class="security-hint">
          <strong><ShieldCheck :size="13" />审计来源</strong>
          <ul>
            <li>密码与 2FA 相关变更由 <code>AccountSecurityController</code> 写入</li>
            <li>登录 / 登出由 <code>JwtAuthenticationFilter</code> 写入 <code>login_log</code></li>
            <li>资料修改由 <code>UserController</code> 拦截 <code>@OperationLog</code></li>
          </ul>
        </aside>
      </section>
    </template>
  </div>
    <ConfirmDialog ref="confirmDialogRef" />
</template>

<style scoped>
.account-security-shell { display: flex; flex-direction: column; gap: 16px; }

/* Page header */
.page-header { background: var(--c-surface); border: 1px solid var(--c-brand-soft); border-radius: var(--r-md); padding: 14px 18px; display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; }
.page-header h2 { margin: 0 0 4px; display: flex; align-items: center; gap: 7px; color: var(--c-scrim); font-size: 16px; }
.page-header p { margin: 0; font-size: 12px; color: var(--c-text-2); line-height: 1.55; }
.page-header code { background: var(--c-brand-mist); padding: 1px 5px; border-radius: var(--r-xs); font-size: 11px; }
.actions { display: flex; gap: 8px; align-items: center; }
.op-btn { border: 1px solid var(--c-brand-tint); background: var(--c-surface); padding: 7px 12px; border-radius: var(--r-sm); font-size: 12px; cursor: pointer; display: inline-flex; align-items: center; gap: 5px; color: var(--c-ink-1); }
.op-btn:hover { background: var(--c-brand-mist); }
.op-btn.danger { color: var(--c-danger-strong); border-color: color-mix(in srgb, var(--c-danger-strong) 30%, transparent); }
.op-btn.danger:hover { background: var(--c-danger-bg); }
.op-btn:disabled { opacity: .55; cursor: not-allowed; }

.panel-foot-tip { background: var(--c-warning-bg); border: 1px solid color-mix(in srgb, var(--c-warning) 40%, transparent); color: var(--c-warning-ink); padding: 14px 18px; border-radius: var(--r-md); font-size: 13px; }
.panel-foot-tip code { background: var(--c-surface); padding: 1px 6px; border-radius: var(--r-xs); }

/* Profile big card */
.profile-card { background: var(--c-surface); border: 1px solid var(--c-brand-soft); border-radius: var(--r-lg); padding: 18px 22px; display: grid; grid-template-columns: 240px 1fr; gap: 24px; box-shadow: 0 4px 14px rgba(0, 0, 0, .04); }
.profile-left { display: flex; gap: 14px; align-items: center; }
.avatar { width: 56px; height: 56px; border-radius: var(--r-lg); background: linear-gradient(135deg, var(--c-brand) 0%, var(--c-brand-hover) 100%); color: var(--c-on-brand); display: grid; place-items: center; font-size: 22px; font-weight: 700; box-shadow: 0 4px 14px rgba(79,143,153,.28); }
.profile-meta { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.profile-meta .name { color: var(--c-scrim); font-size: 17px; }
.profile-meta .username code { background: var(--c-brand-mist); padding: 1px 6px; border-radius: var(--r-xs); font-size: 11px; color: var(--c-text-2); }
.role-chip { display: inline-block; font-size: 10px; padding: 2px 8px; border-radius: var(--r-pill); font-weight: 700; letter-spacing: .03em; width: fit-content; }
.profile-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 18px; align-content: center; }
.info-cell { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.info-cell small { color: var(--c-text-4); font-size: 11px; }
.info-cell strong { color: var(--c-scrim); font-size: 14px; }
.info-cell strong code { background: var(--c-brand-mist); padding: 1px 6px; border-radius: var(--r-xs); font-size: 12px; }
.info-cell em { font-style: normal; color: var(--c-text-2); font-size: 11px; }
.state-chip { display: inline-block; font-size: 11px; padding: 2px 8px; border-radius: var(--r-pill); font-weight: 600; letter-spacing: .03em; }
.state-chip.on  { background: rgba(109,191,165,.18); color: var(--c-brand-deep); }
.state-chip.off { background: var(--c-danger-bg); color: var(--c-danger-strong); }
.profile-loading { grid-column: 1 / -1; font-size: 11px; color: var(--c-text-4); text-align: right; }

/* Tabs (沿用工程 .settings-tabs) */
.settings-tabs { display: flex; gap: 8px; flex-wrap: wrap; }
.settings-tab { display: inline-flex; align-items: center; gap: 6px; padding: 8px 14px; background: var(--c-surface); border: 1px solid var(--c-brand-tint); border-radius: var(--r-sm); color: var(--c-ink-1); font-size: 13px; cursor: pointer; transition: background .2s, color .2s, border-color .2s; }
.settings-tab:hover { background: var(--c-brand-mist); }
.settings-tab.is-active { background: var(--c-scrim); color: var(--c-on-brand); border-color: var(--c-scrim); box-shadow: 0 4px 10px rgba(31,62,62,.18); }

/* Settings panel */
.settings-panel { background: var(--c-surface); border: 1px solid var(--c-brand-soft); border-radius: var(--r-lg); padding: 16px 18px 18px; }

/* Tab 1: 2FA */
.twofa-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.twofa-card { background: var(--c-surface-2); border: 1px solid var(--c-separator); border-radius: var(--r-md); padding: 14px 16px; display: flex; flex-direction: column; gap: 12px; }
.twofa-card.span-2 { grid-column: span 2; }
.twofa-card-head { display: flex; align-items: center; gap: 8px; color: var(--c-scrim); font-size: 14px; padding-bottom: 6px; border-bottom: 1px dashed var(--c-separator); }
.twofa-card-head strong { flex: 1; }
.muted { color: var(--c-text-2); font-size: 12px; line-height: 1.55; margin: 0; }

.switch { position: relative; display: inline-block; width: 38px; height: 22px; }
.switch input { opacity: 0; width: 0; height: 0; }
.switch .slider { position: absolute; cursor: pointer; inset: 0; background: var(--c-brand-tint); border-radius: var(--r-pill); transition: .25s; }
.switch .slider::before { position: absolute; content: ""; height: 16px; width: 16px; left: 3px; top: 3px; background: var(--c-surface); border-radius: 50%; transition: .25s; box-shadow: 0 1px 3px rgba(0,0,0,.2); }
.switch input:checked + .slider { background: var(--c-brand); }
.switch input:checked + .slider::before { transform: translateX(16px); }

.method-list { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 8px; }
.method-chip { display: flex; flex-direction: column; gap: 3px; padding: 10px 12px; border: 1px solid var(--c-brand-tint); border-radius: var(--r-sm); cursor: pointer; background: var(--c-surface); transition: .2s; }
.method-chip:hover { border-color: var(--c-brand); }
.method-chip.active { border-color: var(--c-brand); background: rgba(79,143,153,.07); }
.method-chip input { display: none; }
.method-chip .m-label { display: flex; align-items: center; gap: 6px; color: var(--c-scrim); font-size: 13px; font-weight: 600; }
.method-chip .m-desc { color: var(--c-text-2); font-size: 11px; line-height: 1.4; }

.twofa-actions { display: flex; gap: 10px; align-items: center; }
.dialog-btn { border: 1px solid var(--c-brand-tint); background: var(--c-surface); padding: 7px 14px; border-radius: var(--r-sm); font-size: 13px; cursor: pointer; color: var(--c-ink-1); }
.dialog-btn.primary { background: var(--c-brand); color: var(--c-on-brand); border-color: var(--c-brand); }
.dialog-btn.primary:hover { background: var(--sys-blue-deep); }
.dialog-btn:disabled { opacity: .55; cursor: not-allowed; }
.text-btn { border: 0; background: transparent; color: var(--c-brand); cursor: pointer; font-size: 12px; display: inline-flex; align-items: center; gap: 4px; padding: 4px 6px; }
.text-btn:hover { background: rgba(79,143,153,.08); border-radius: var(--r-sm); }
.text-btn:disabled { opacity: .5; cursor: not-allowed; }
.hint { font-size: 11px; color: var(--c-text-4); }

/* QR block */
.qr-block { display: grid; grid-template-columns: 140px 1fr; gap: 16px; align-items: flex-start; }
.qr-frame { width: 140px; height: 140px; padding: 8px; background: var(--c-surface); border: 1px solid var(--c-separator); border-radius: var(--r-md); display: grid; place-items: center; }
.qr-placeholder { width: 100%; height: 100%; display: grid; place-items: center; gap: 6px; background: repeating-linear-gradient(45deg, var(--c-brand-mist), var(--c-brand-mist) 6px, var(--c-separator) 6px, var(--c-separator) 12px); border: 1px dashed var(--c-text-4); border-radius: var(--r-sm); color: var(--c-text-2); }
.qr-placeholder small { font-size: 10px; letter-spacing: .08em; }
.qr-info { display: flex; flex-direction: column; gap: 4px; }
.qr-info small { color: var(--c-text-4); font-size: 10px; margin-top: 4px; }
.qr-info code { font-size: 11px; padding: 4px 8px; background: var(--c-brand-mist); border-radius: var(--r-xs); word-break: break-all; color: var(--c-scrim); }
.qr-info code.qr-secret { font-weight: 700; letter-spacing: .04em; }
.qr-steps { margin: 4px 0 0; padding-left: 20px; font-size: 11px; color: var(--c-ink-1); }
.qr-steps li { margin-bottom: 2px; }
.verify-input { display: flex; flex-direction: column; gap: 3px; margin-top: 6px; }
.verify-input input { border: 1px solid var(--c-brand-tint); border-radius: var(--r-sm); padding: 6px 10px; font-size: 13px; outline: none; width: 140px; letter-spacing: .12em; font-family: 'JetBrains Mono', monospace; background: var(--c-surface); }

/* Backup chips */
.backup-chips { list-style: none; padding: 0; margin: 0; display: grid; grid-template-columns: repeat(4, 1fr); gap: 6px; }
.backup-chips li { background: var(--c-surface); border: 1px dashed var(--c-brand-tint); border-radius: var(--r-sm); padding: 8px 6px; text-align: center; }
.backup-chips code { font-family: 'JetBrains Mono', monospace; font-size: 12px; letter-spacing: .05em; color: var(--c-scrim); }
.backup-foot { display: flex; gap: 10px; align-items: center; margin-top: 8px; flex-wrap: wrap; }
.warn-text { font-size: 11px; color: var(--c-danger-strong); font-style: normal; }
.empty-state.small { padding: 18px; background: var(--c-surface); border: 1px dashed var(--c-brand-tint); border-radius: var(--r-sm); color: var(--c-text-2); font-size: 12px; }

/* Security hint */
.security-hint { margin-top: 16px; padding: 12px 16px; background: var(--c-brand-mist); border: 1px dashed var(--c-brand-tint); border-radius: var(--r-md); font-size: 12px; color: var(--c-ink-1); }
.security-hint strong { display: flex; align-items: center; gap: 5px; color: var(--c-scrim); font-size: 13px; margin-bottom: 6px; }
.security-hint ul { padding-left: 18px; margin: 0; }
.security-hint li { margin-bottom: 3px; }
.security-hint code { background: var(--c-surface); padding: 1px 5px; border-radius: var(--r-xs); font-size: 11px; }

/* Filter bar / status tabs */
.filter-bar { display: flex; gap: 12px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }
.status-tabs { display: flex; gap: 5px; flex-wrap: wrap; }
.pill { border: 1px solid transparent; background: var(--c-brand-mist); padding: 5px 12px; border-radius: var(--r-pill); font-size: 12px; cursor: pointer; color: var(--c-ink-1); transition: .2s; }
.pill:hover { background: var(--c-separator); }
.pill.active { background: var(--c-scrim); color: var(--c-on-brand); }

/* Data tables */
.table-scroll { overflow-x: auto; }
.data-table { width: 100%; min-width: 720px; border-collapse: collapse; font-size: 12px; }
.data-table th, .data-table td { text-align: left; padding: 9px 14px; border-bottom: 1px solid var(--c-brand-mist); white-space: nowrap; }
.data-table th { color: var(--c-text-2); font-weight: 600; background: var(--c-surface-2); font-size: 11px; letter-spacing: .04em; text-transform: uppercase; }
.data-table tr:hover td { background: var(--c-surface-2); }
.data-table tr.is-current td { background: rgba(79,143,153,.05); }
.data-table td small { display: block; color: var(--c-text-4); font-size: 11px; margin-top: 2px; }
.loading-state, .empty-state { text-align: center; color: var(--c-text-4); padding: 30px 0; }
.status-chip { display: inline-block; font-size: 10px; padding: 2px 8px; border-radius: var(--r-pill); font-weight: 700; letter-spacing: .04em; }
.type-chip { display: inline-block; font-size: 10px; padding: 2px 8px; border-radius: var(--r-xs); font-weight: 700; letter-spacing: .04em; }
.current-tag { display: inline-block; margin-left: 8px; font-size: 10px; padding: 1px 6px; border-radius: var(--r-xs); background: rgba(79,143,153,.18); color: var(--c-brand); font-style: normal; }

/* Spin */
.spin { animation: spin 1.2s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

/* Responsive */
@media (max-width: 1180px) {
  .profile-card { grid-template-columns: 1fr; gap: 18px; }
  .profile-grid { grid-template-columns: repeat(2, 1fr); }
  .twofa-grid { grid-template-columns: 1fr; }
  .twofa-card.span-2 { grid-column: auto; }
  .qr-block { grid-template-columns: 1fr; }
  .qr-frame { margin: 0 auto; }
}
@media (max-width: 820px) {
  .profile-grid { grid-template-columns: 1fr 1fr; }
  .method-list { grid-template-columns: 1fr; }
  .backup-chips { grid-template-columns: repeat(2, 1fr); }
  .settings-tab { flex: 1; justify-content: center; padding: 8px 6px; font-size: 12px; }
}
@media (max-width: 520px) {
  .profile-grid { grid-template-columns: 1fr; }
  .profile-left { flex-direction: column; align-items: flex-start; }
}
</style>
