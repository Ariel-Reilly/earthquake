<script setup>
import { computed, onMounted, onBeforeUnmount, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowRight, LogIn, Mountain, User, Lock, ChevronDown, Eye, EyeOff } from 'lucide-vue-next'
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
const showMobileNav = ref(false)
const scrollY = ref(0)

const form = reactive({ username: 'admin', password: '123456', role: 'admin' })
const REMEMBER_KEY = 'geosentry_remembered_username'

const selectedRole = computed(() => ROLE_OPTIONS.find((item) => item.value === form.role) ?? ROLE_OPTIONS[0])
const navScrolled = computed(() => scrollY.value > 24)

const goHome = () => {
  showMobileNav.value = false
  router.push('/')
}

function onScroll() {
  scrollY.value = window.scrollY
}

onMounted(() => {
  window.addEventListener('scroll', onScroll, { passive: true })
  const fromQuery =
    typeof route.query.role === "string" ? route.query.role : "";
  if (fromQuery && ROLE_OPTIONS.some((r) => r.value === fromQuery)) {
    useDemoRole(fromQuery);
  }
  try {
    const saved = window.localStorage.getItem(REMEMBER_KEY)
    if (saved && !fromQuery) form.username = saved
  } catch {
    // ignore storage read failures
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('scroll', onScroll)
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

    <!-- ====== 顶部导航 ====== -->
    <header class="topnav" :class="{ scrolled: navScrolled }">
      <div class="topnav-inner">
        <button class="brand" @click="goHome">
          <span class="brand-mark"><Mountain :size="20" /></span>
          <span class="brand-copy">
            <strong>地脉智巡</strong>
            <small>GeoSentry</small>
          </span>
        </button>

        <nav class="nav-links" :class="{ open: showMobileNav }">
          <a href="/#features" @click="showMobileNav = false">核心能力</a>
          <a href="/#workflow" @click="showMobileNav = false">工作流程</a>
          <a href="/#architecture" @click="showMobileNav = false">系统架构</a>
          <a href="/#roles" @click="showMobileNav = false">角色方案</a>
          <a href="/#faq" @click="showMobileNav = false">常见问题</a>
        </nav>

        <div class="topnav-actions">
          <button class="nav-cta" @click="goHome">
            返回首页 <ArrowRight :size="14" />
          </button>
          <button
            class="nav-burger"
            :class="{ open: showMobileNav }"
            @click="showMobileNav = !showMobileNav"
          >
            <span />
            <span />
            <span />
          </button>
        </div>
      </div>
    </header>

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

<style scoped>
.login-page {
  grid-template-columns: 1fr;
  place-items: center;
}

/* ====== 顶部导航 ====== */
.topnav {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: 50;
  transition: background 0.3s, border-color 0.3s, backdrop-filter 0.3s;
  border-bottom: 1px solid transparent;
}
.topnav.scrolled {
  background: rgba(251, 252, 251, 0.8);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  border-bottom-color: rgba(229, 236, 233, 0.8);
}
.topnav-inner {
  max-width: 1240px;
  margin: 0 auto;
  padding: 14px 24px;
  display: flex;
  align-items: center;
  gap: 24px;
}
.brand {
  display: inline-flex;
  align-items: center;
  gap: 11px;
  padding: 0;
  border: 0;
  background: transparent;
  cursor: pointer;
  color: inherit;
}
.brand-mark {
  width: 38px;
  height: 38px;
  display: grid;
  place-items: center;
  color: #0f4e47;
  background: linear-gradient(135deg, #d8f4dd, #b7e6cb);
  border-radius: 11px;
  box-shadow: 0 6px 18px rgba(73, 131, 118, 0.18);
}
.brand-copy {
  text-align: left;
  line-height: 1;
}
.brand-copy strong {
  display: block;
  font-size: 16px;
  color: #24494d;
  letter-spacing: 0.02em;
}
.brand-copy small {
  display: block;
  margin-top: 4px;
  color: #829793;
  font-size: 10px;
  letter-spacing: 0.28em;
  text-transform: uppercase;
}
.nav-links {
  display: flex;
  gap: 4px;
  margin-left: 16px;
}
.nav-links a {
  padding: 8px 13px;
  color: #5f7b78;
  border-radius: 8px;
  font-size: 14px;
  transition: color 0.2s, background 0.2s;
  text-decoration: none;
}
.nav-links a:hover {
  color: #2f7f80;
  background: rgba(47, 127, 128, 0.08);
}
.topnav-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 10px;
}
.nav-cta {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 9px 16px;
  color: #fff;
  border: 1px solid #2b7778;
  border-radius: 8px;
  background: linear-gradient(135deg, #3fae97, #246f70);
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
  transition: transform 0.2s, box-shadow 0.2s, background 0.2s;
  box-shadow: 0 4px 14px rgba(47, 127, 128, 0.22);
}
.nav-cta:hover {
  transform: translateY(-1px);
  background: linear-gradient(135deg, #47b8a0, #277061);
  box-shadow: 0 8px 20px rgba(47, 127, 128, 0.3);
}
.nav-burger {
  display: none;
  width: 40px;
  height: 40px;
  border: 1px solid rgba(47, 127, 128, 0.25);
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.7);
  cursor: pointer;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 5px;
}
.nav-burger span {
  width: 18px;
  height: 2px;
  background: #5f7f7b;
  border-radius: 2px;
  transition: transform 0.25s, opacity 0.25s;
}
.nav-burger.open span:nth-child(1) { transform: translateY(7px) rotate(45deg); }
.nav-burger.open span:nth-child(2) { opacity: 0; }
.nav-burger.open span:nth-child(3) { transform: translateY(-7px) rotate(-45deg); }

.login-card {
  margin: auto;
}

@media (max-width: 820px) {
  .login-page {
    place-items: center;
  }
  .login-card {
    margin: auto;
  }
  .nav-links {
    position: fixed;
    top: 64px;
    left: 12px;
    right: 12px;
    flex-direction: column;
    padding: 10px;
    border: 1px solid #e3ebe7;
    border-radius: 12px;
    background: #fff;
    box-shadow: 0 20px 50px rgba(25, 65, 64, 0.14);
    display: none;
  }
  .nav-links.open {
    display: flex;
  }
  .nav-burger {
    display: inline-flex;
  }
}

@media (max-width: 560px) {
  .nav-links {
    display: none;
  }
  .nav-links.open {
    display: flex;
  }
  .nav-burger {
    display: inline-flex;
  }
}
</style>
