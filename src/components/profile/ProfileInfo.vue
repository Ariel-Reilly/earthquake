<script setup>
/**
 * 个人中心-个人信息卡片。
 * 与 /api/profile 实时联动：读取/更新用户名不可改、姓名、手机号、邮箱、部门、头像。
 */
import { computed, reactive, ref, watch } from 'vue'
import { Save, Upload, UserCircle, Loader2 } from 'lucide-vue-next'
import { dashboardApi } from '../../services/dashboardApi'
import { getAuthUser } from '../../stores/auth'

const emit = defineEmits(['notify'])

const profile = reactive({
  id: '',
  username: '',
  name: '',
  phone: '',
  email: '',
  department: '',
  avatar: '',
  role: 'admin',
})
const loading = ref(false)
const saving = ref(false)
const dirty = ref(false)

function fillFromCache() {
  const u = getAuthUser() ?? {}
  Object.assign(profile, {
    id: u.id ?? 'u-admin',
    username: u.username ?? 'admin',
    name: u.name ?? '',
    phone: u.phone ?? '',
    email: u.email ?? '',
    department: u.department ?? '',
    avatar: u.avatar ?? '',
    role: u.role ?? 'admin',
  })
}

async function loadFromApi() {
  loading.value = true
  try {
    const data = await dashboardApi.getProfile()
    if (data) Object.assign(profile, data)
  } catch (e) {
    // 离线：使用本地缓存
    fillFromCache()
  } finally {
    loading.value = false
  }
}

watch(profile, () => { dirty.value = true }, { deep: true })

const phoneValid = computed(() => !profile.phone || /^1[3-9]\d{9}$/.test(profile.phone))
const emailValid = computed(() => !profile.email || /^[\w.+-]+@[\w-]+\.[\w.-]+$/.test(profile.email))
const canSave = computed(() => dirty.value && phoneValid.value && emailValid.value && !!profile.name)

async function save() {
  if (!canSave.value) {
    emit('notify', phoneValid.value && emailValid.value ? '没有需要保存的改动' : '手机号或邮箱格式不正确')
    return
  }
  saving.value = true
  try {
    const payload = {
      name: profile.name,
      phone: profile.phone,
      email: profile.email,
      department: profile.department,
      avatar: profile.avatar,
    }
    await dashboardApi.updateProfile(payload)
    dirty.value = false
    emit('notify', '个人信息已保存')
  } catch (e) {
    emit('notify', e?.message ?? '保存失败')
  } finally {
    saving.value = false
  }
}

function pickAvatar(ev) {
  const file = ev.target.files?.[0]
  if (!file) return
  if (file.size > 1024 * 200) {
    emit('notify', '头像大小请控制在 200KB 以内')
    return
  }
  const reader = new FileReader()
  reader.onload = () => {
    profile.avatar = reader.result
  }
  reader.readAsDataURL(file)
}

fillFromCache()
loadFromApi()
</script>

<template>
  <section class="profile-info">
    <header class="card-head">
      <div>
        <h3>个人信息</h3>
        <p>头像、姓名、手机号、邮箱、部门可修改；用户名与角色由系统分配。</p>
      </div>
      <span v-if="loading" class="status"><Loader2 class="spin" :size="14" /> 同步中</span>
    </header>

    <div class="avatar-row">
      <div class="avatar-preview">
        <img v-if="profile.avatar" :src="profile.avatar" alt="头像" />
        <UserCircle v-else :size="56" />
      </div>
      <label class="upload-btn">
        <Upload :size="14" /> 上传新头像
        <input type="file" accept="image/*" @change="pickAvatar" hidden />
      </label>
      <p class="hint">支持 JPG / PNG，200KB 以内，1:1 比例最佳。</p>
    </div>

    <div class="form-grid">
      <label class="field">
        <span>用户名</span>
        <input :value="profile.username" disabled />
      </label>
      <label class="field">
        <span>姓名 *</span>
        <input v-model="profile.name" placeholder="请填写真实姓名" />
      </label>
      <label class="field">
        <span>手机号</span>
        <input v-model="profile.phone" placeholder="如 13800138000" />
        <small v-if="!phoneValid" class="err">手机号格式不正确</small>
      </label>
      <label class="field">
        <span>邮箱</span>
        <input v-model="profile.email" placeholder="name@example.com" />
        <small v-if="!emailValid" class="err">邮箱格式不正确</small>
      </label>
      <label class="field">
        <span>部门</span>
        <input v-model="profile.department" placeholder="如 应急管理中心" />
      </label>
      <label class="field">
        <span>角色</span>
        <input :value="profile.role" disabled />
      </label>
    </div>

    <div class="actions">
      <button class="primary" :disabled="!canSave || saving" @click="save">
        <Loader2 v-if="saving" :size="14" class="spin" />
        <Save v-else :size="14" />
        保存修改
      </button>
    </div>
  </section>
</template>

<style scoped>
.profile-info {
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  border-bottom: 1px solid var(--dashboard-border, #eef2f7);
  padding-bottom: 14px;
}
.card-head h3 { margin: 0; font-size: 18px; }
.card-head p { margin: 4px 0 0; color: var(--dashboard-muted, #6b7280); font-size: 13px; }
.status { font-size: 12px; color: var(--dashboard-muted, #6b7280); display: inline-flex; align-items: center; gap: 4px; }
.spin { animation: rotate 1s linear infinite; }
@keyframes rotate { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
.avatar-row { display: flex; align-items: center; gap: 16px; }
.avatar-preview {
  width: 64px; height: 64px; border-radius: 50%;
  background: linear-gradient(135deg, #5b8ef9, #38bdf8);
  display: flex; align-items: center; justify-content: center;
  color: white; overflow: hidden;
}
.avatar-preview img { width: 100%; height: 100%; object-fit: cover; }
.upload-btn {
  display: inline-flex; align-items: center; gap: 6px;
  border: 1px dashed var(--dashboard-border, #cbd5e1);
  padding: 6px 14px; border-radius: 8px; cursor: pointer; font-size: 13px;
  color: var(--dashboard-text, #1e293b);
}
.upload-btn:hover { border-color: #5b8ef9; color: #5b8ef9; }
.hint { color: var(--dashboard-muted, #6b7280); font-size: 12px; margin: 0; }
.form-grid {
  display: grid; grid-template-columns: 1fr 1fr; gap: 14px 18px;
}
@media (max-width: 720px) { .form-grid { grid-template-columns: 1fr; } }
.field { display: flex; flex-direction: column; gap: 4px; font-size: 13px; }
.field span { color: var(--dashboard-muted, #4b5563); font-weight: 500; }
.field input {
  padding: 8px 12px; border: 1px solid var(--dashboard-border, #e2e8f0);
  border-radius: 8px; font-size: 14px;
  background: var(--dashboard-input-bg, #fff);
  color: var(--dashboard-text, #1e293b);
}
.field input:disabled { background: var(--dashboard-muted-bg, #f1f5f9); color: var(--dashboard-muted, #64748b); }
.field input:focus { outline: none; border-color: #5b8ef9; box-shadow: 0 0 0 3px rgba(91,142,249,0.15); }
.err { color: #ef4444; font-size: 12px; }
.actions { display: flex; justify-content: flex-end; }
.primary {
  background: linear-gradient(135deg, #5b8ef9, #3b82f6); color: white;
  border: none; padding: 8px 18px; border-radius: 8px;
  display: inline-flex; align-items: center; gap: 6px;
  cursor: pointer; font-size: 14px;
}
.primary:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
