<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ShieldCheck, Mountain, AlertTriangle, LogIn, User, Lock, ChevronDown, Eye, EyeOff } from 'lucide-vue-next'
import { loginRequest } from '../services/dashboardApi'
import { ROLE_OPTIONS, getHomePathByRole, setAuthSession } from '../stores/auth'
import AnimatedLoginBackground from '../components/AnimatedLoginBackground.vue'

const router = useRouter()
const route = useRoute()
const loading = ref(false)
const error = ref('')
const hint = ref('')
const showPassword = ref(false)
const remember = ref(true)
const capsLockOn = ref(false)

const form = reactive({ username: 'admin', password: '123456', role: 'admin' })
const REMEMBER_KEY = 'geosentry_remembered_username'

const selectedRole = computed(() => ROLE_OPTIONS.find((item) => item.value === form.role) ?? ROLE_OPTIONS[0])

onMounted(() => {
  try {
    const saved = window.localStorage.getItem(REMEMBER_KEY)
    if (saved) form.username = saved
  } catch {
    // ignore storage read failures
  }
})

function safeRedirectPath(fallback) {
  const raw = typeof route.query.redirect === 'string' ? route.query.redirect : ''
  if (raw.startsWith('/') && !raw.startsWith('//') && !raw.includes('\\')) return raw
  return fallback
}

function validate() {
  if (!form.username.trim()) {
    error.value = '请输入账号'
    return false
  }
  if (!form.password) {
    error.value = '请输入密码'
    return false
  }
  return true
}

function onCapsLock(event) {
  capsLockOn.value = event.getModifierState?.('CapsLock') === true
}

async function submit() {
  if (loading.value) return
  error.value = ''
  hint.value = ''
  if (!validate()) return

  loading.value = true
  try {
    const session = await loginRequest({
      username: form.username.trim(),
      password: form.password,
      role: form.role,
    })
    setAuthSession(session)

    if (remember.value) {
      window.localStorage.setItem(REMEMBER_KEY, form.username.trim())
    } else {
      window.localStorage.removeItem(REMEMBER_KEY)
    }

    const redirect = safeRedirectPath(getHomePathByRole(session.user.role))
    router.replace(redirect)
  } catch (err) {
    error.value = err instanceof Error ? err.message : '登录失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function useDemoRole(role) {
  form.role = role
  form.username = 'admin'
  form.password = '123456'
  error.value = ''
  hint.value = `已填入「${ROLE_OPTIONS.find((item) => item.value === role)?.label}」演示账号，点击「进入系统」即可`
}

function forgotPassword() {
  hint.value = '演示环境默认密码为 123456，正式环境请联系管理员重置密码'
}
</script>

<template>
  <div class="login-page">
    <AnimatedLoginBackground />

    <section class="login-hero">
      <div class="login-brand">
        <div class="brand-mark"><Mountain :size="24" /></div>
        <div>
          <strong>地脉智巡</strong>
          <span>Geohazard Inspection Dashboard</span>
        </div>
      </div>
      <h1>地质灾害点智能巡检与预警管理平台</h1>
      <p>统一管理灾害点、巡检任务、监测设备和预警事件，为应急响应提供一体化支撑。</p>
      <ul>
        <li><ShieldCheck :size="16" />权限分级与安全访问</li>
        <li><AlertTriangle :size="16" />多级预警闭环处置</li>
        <li><LogIn :size="16" />支持单点登录扩展</li>
      </ul>
    </section>

    <section class="login-card">
      <div class="login-head">
        <div>
          <span class="eyebrow">SIGN IN</span>
          <h2>登录系统</h2>
          <p class="login-subtitle">使用您的账号登录地脉智巡平台</p>
        </div>
      </div>

      <form novalidate @submit.prevent="submit">
        <label>
          角色
          <div class="login-input">
            <ChevronDown :size="16" />
            <select v-model="form.role" aria-label="选择登录角色">
              <option v-for="role in ROLE_OPTIONS" :key="role.value" :value="role.value">
                {{ role.label }} · {{ role.description }}
              </option>
            </select>
          </div>
        </label>

        <label>
          账号
          <div class="login-input">
            <User :size="16" />
            <input v-model="form.username" autocomplete="username" placeholder="请输入账号" autofocus />
          </div>
        </label>

        <label>
          密码
          <div class="login-input">
            <Lock :size="16" />
            <input
              v-model="form.password"
              :type="showPassword ? 'text' : 'password'"
              autocomplete="current-password"
              placeholder="请输入密码"
              @keyup="onCapsLock"
            />
            <button type="button" :aria-label="showPassword ? '隐藏密码' : '显示密码'" @click="showPassword = !showPassword">
              <EyeOff v-if="showPassword" :size="15" />
              <Eye v-else :size="15" />
            </button>
          </div>
        </label>

        <p v-if="capsLockOn" class="login-caps">大写锁定已开启，注意密码大小写</p>

        <div class="login-options">
          <label class="login-remember">
            <input v-model="remember" type="checkbox" />
            <span>记住账号</span>
          </label>
          <button type="button" class="login-forgot" @click="forgotPassword">忘记密码？</button>
        </div>

        <p v-if="error" class="login-error" role="alert">{{ error }}</p>
        <p v-if="hint && !error" class="login-hint">{{ hint }}</p>

        <button class="primary-btn login-btn" type="submit" :disabled="loading">
          <LogIn :size="16" />{{ loading ? '登录中...' : '进入系统' }}
        </button>
      </form>

      <div class="demo-accounts">
        <span>演示账号 · 点击快速填充</span>
        <div class="demo-grid">
          <button
            v-for="role in ROLE_OPTIONS"
            :key="role.value"
            type="button"
            class="demo-role-btn"
            :class="{ active: form.role === role.value }"
            @click="useDemoRole(role.value)"
          >
            {{ role.label }}
            <small>{{ role.value }}</small>
          </button>
        </div>
      </div>

      <div class="login-footer">
        <span>默认账号：admin</span>
        <span>默认密码：123456</span>
      </div>
    </section>
  </div>
</template>
