<script setup>
/**
 * 个人中心-修改密码。
 * 与 /api/profile/password 联动：旧密码 / 新密码 / 确认密码，强度校验。
 * 修改成功后会提示"下次登录请使用新密码"，并清空表单。
 */
import { computed, reactive, ref } from 'vue'
import { Eye, EyeOff, KeyRound, Loader2, ShieldCheck } from 'lucide-vue-next'
import { dashboardApi } from '../../services/dashboardApi'

const emit = defineEmits(['notify'])

const form = reactive({
  current: '',
  next: '',
  confirm: '',
})

const show = reactive({ current: false, next: false, confirm: false })
const saving = ref(false)
const error = ref('')
const success = ref('')

const strength = computed(() => {
  const v = form.next
  if (!v) return { level: 0, label: '' }
  let score = 0
  if (v.length >= 8) score += 1
  if (/[A-Z]/.test(v) && /[a-z]/.test(v)) score += 1
  if (/\d/.test(v) && /[^A-Za-z0-9]/.test(v)) score += 1
  return {
    level: Math.min(score + 1, 4),
    label: ['弱', '一般', '较强', '强'][score] ?? '强',
  }
})

const same = computed(() => !!form.next && form.next === form.confirm)
const longEnough = computed(() => form.next.length >= 8)

async function submit() {
  error.value = ''
  success.value = ''
  if (!form.current) { error.value = '请填写旧密码'; return }
  if (!longEnough.value) { error.value = '新密码至少 8 位'; return }
  if (!same.value) { error.value = '两次输入的新密码不一致'; return }
  if (form.next === form.current) { error.value = '新密码不能与旧密码相同'; return }
  saving.value = true
  try {
    await dashboardApi.changeMyPassword({ current: form.current, next: form.next })
    success.value = '密码已更新，下次登录请使用新密码'
    emit('notify', '密码修改成功')
    form.current = ''
    form.next = ''
    form.confirm = ''
  } catch (e) {
    error.value = e?.message ?? '密码修改失败'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <section class="pwd">
    <header class="head">
      <div>
        <h3>密码修改</h3>
        <p>为了账号安全，建议每 90 天更换一次密码；新密码需 8 位以上且包含数字与字母。</p>
      </div>
      <ShieldCheck :size="22" class="shield" />
    </header>

    <div class="row">
      <label>旧密码</label>
      <div class="input-with-icon">
        <input :type="show.current ? 'text' : 'password'" v-model="form.current" autocomplete="current-password" />
        <button class="icon" type="button" @click="show.current = !show.current">
          <Eye v-if="show.current" :size="16" />
          <EyeOff v-else :size="16" />
        </button>
      </div>
    </div>
    <div class="row">
      <label>新密码</label>
      <div class="input-with-icon">
        <input :type="show.next ? 'text' : 'password'" v-model="form.next" autocomplete="new-password" />
        <button class="icon" type="button" @click="show.next = !show.next">
          <Eye v-if="show.next" :size="16" />
          <EyeOff v-else :size="16" />
        </button>
      </div>
      <div class="strength">
        <div class="bar"><span :style="{ width: (strength.level / 4 * 100) + '%' }" :data-level="strength.level"></span></div>
        <small>{{ strength.label || '请输入新密码' }}</small>
      </div>
    </div>
    <div class="row">
      <label>确认新密码</label>
      <div class="input-with-icon">
        <input :type="show.confirm ? 'text' : 'password'" v-model="form.confirm" autocomplete="new-password" />
        <button class="icon" type="button" @click="show.confirm = !show.confirm">
          <Eye v-if="show.confirm" :size="16" />
          <EyeOff v-else :size="16" />
        </button>
      </div>
      <small v-if="form.confirm && !same" class="err">两次输入不一致</small>
      <small v-else-if="same" class="ok">两次输入一致</small>
    </div>

    <div v-if="error" class="msg err">{{ error }}</div>
    <div v-if="success" class="msg ok">{{ success }}</div>

    <div class="actions">
      <button class="primary" :disabled="saving" @click="submit">
        <Loader2 v-if="saving" class="spin" :size="14" />
        <KeyRound v-else :size="14" />
        更新密码
      </button>
    </div>
  </section>
</template>

<style scoped>
.pwd { display: flex; flex-direction: column; gap: 14px; max-width: 520px; }
.head { display: flex; justify-content: space-between; align-items: flex-start; border-bottom: 1px solid var(--dashboard-border, var(--c-brand-mist)); padding-bottom: 14px; }
.head h3 { margin: 0; font-size: 18px; }
.head p { margin: 4px 0 0; color: var(--dashboard-muted, var(--c-text-2)); font-size: 13px; max-width: 420px; }
.shield { color: var(--c-brand-tint); }
.row { display: flex; flex-direction: column; gap: 6px; }
.row label { font-size: 13px; color: var(--dashboard-muted, var(--c-text-2)); font-weight: 500; }
.input-with-icon { position: relative; display: flex; align-items: center; }
.input-with-icon input {
  flex: 1; padding: 8px 38px 8px 12px; border: 1px solid var(--dashboard-border, var(--c-brand-soft));
  border-radius: var(--r-sm); font-size: 14px; background: var(--dashboard-input-bg, var(--c-surface));
}
.input-with-icon input:focus { outline: none; border-color: var(--c-brand-tint); box-shadow: 0 0 0 3px rgba(99,199,127,0.15); }
.icon { position: absolute; right: 8px; background: none; border: none; cursor: pointer; color: var(--dashboard-muted, var(--c-text-3)); padding: 4px; }
.icon:hover { color: var(--dashboard-text, var(--c-scrim)); }
.strength { display: flex; align-items: center; gap: 10px; }
.bar { flex: 1; height: 4px; background: var(--dashboard-muted-bg, var(--c-separator)); border-radius: var(--r-xs); overflow: hidden; }
.bar span { display: block; height: 100%; transition: width 0.3s; }
.bar span[data-level="1"] { background: var(--c-danger); }
.bar span[data-level="2"] { background: var(--c-warning); }
.bar span[data-level="3"] { background: var(--c-success); }
.bar span[data-level="4"] { background: var(--c-success); }
small { font-size: 12px; color: var(--dashboard-muted, var(--c-text-3)); }
.err { color: var(--c-danger); }
.ok { color: var(--c-success); }
.msg { padding: 8px 12px; border-radius: var(--r-sm); font-size: 13px; }
.msg.err { background: var(--c-danger-bg); color: var(--c-danger-strong); }
.msg.ok { background: var(--c-brand-mist); color: var(--c-success-strong); }
.actions { display: flex; justify-content: flex-end; }
.primary {
  background: linear-gradient(135deg, var(--c-brand-tint), var(--c-brand-hover)); color: white;
  border: none; padding: 8px 18px; border-radius: var(--r-sm);
  display: inline-flex; align-items: center; gap: 6px;
  cursor: pointer; font-size: 14px;
}
.primary:disabled { opacity: 0.5; cursor: not-allowed; }
.spin { animation: rotate 1s linear infinite; }
@keyframes rotate { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
</style>
