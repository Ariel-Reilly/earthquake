<script setup>
/**
 * RegisterPage ── 注册申请页（/register，匿名可访问）
 *
 * 本系统**不提供自助注册**：这里提交的是一条「申请单」，落库后状态为
 * 「待审核」，账号真正创建发生在管理员审核通过时（后端 RegisterService.approve）。
 * 因此本页所有文案都用「申请 / 待审核」而不是「注册成功」。
 *
 * 与后端的对应关系：
 *   GET  /api/auth/captcha              → dashboardApi.getCaptcha()
 *   POST /api/auth/register/send-code   → dashboardApi.sendRegisterCode()
 *   POST /api/auth/register             → dashboardApi.registerRequest()
 *   GET  /api/auth/register/status      → dashboardApi.getRegisterStatus()
 *
 * 表单校验规则与后端 RegisterRequest 的注解逐条对齐（长度/正则），目的是
 * 少跑一次往返就能把错误定位到具体字段；真正的权威校验仍在后端。
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  AlertCircle,
  AlertTriangle,
  ArrowLeft,
  CheckCircle2,
  Clock,
  IdCard,
  Loader2,
  Lock,
  Mail,
  RefreshCw,
  Send,
  ShieldCheck,
  User,
} from 'lucide-vue-next'
import { dashboardApi } from '../services/dashboardApi'

const router = useRouter()

/**
 * 可申请的角色。
 *
 * 严格对齐后端 @Pattern(regexp = "^(attendant|maintenance|admin)$")：
 * 下拉里多列一项都只会被后端打回，不如不列。
 * 选 admin 时后端还会校验邀请码（InviteCodeService），故要额外显示该输入框。
 */
const ROLE_OPTIONS = [
  { value: 'attendant', label: '值班员', desc: '7×24 盯屏，AI 初筛的第一响应人' },
  { value: 'maintenance', label: '现场运维', desc: '设备安装、抢修与现场状态回报' },
  { value: 'admin', label: '系统管理员', desc: '平台最高权限，需管理员发放邀请码' },
]

/**
 * 可选区域。
 *
 * 后端只校验 regionCode 非空，并没有对应的行政区字典接口，这里先以内置
 * 列表兜底。接入字典接口后，把这里换成一次请求即可，字段名保持一致。
 */
const REGION_OPTIONS = [
  { value: 'chengdu', label: '成都市' },
  { value: 'mianyang', label: '绵阳市' },
  { value: 'deyang', label: '德阳市' },
  { value: 'yaan', label: '雅安市' },
  { value: 'aba', label: '阿坝州' },
  { value: 'longan', label: '龙安镇' },
  { value: 'shiqiao', label: '石桥镇' },
  { value: 'qinghe', label: '清河镇' },
]

/** 表单模型。字段名与后端 RegisterRequest 完全一致，避免二次映射出错。 */
const form = reactive({
  // 账号信息
  username: '',
  password: '',
  confirmPassword: '',
  // 身份信息
  realName: '',
  idCard: '',
  department: '',
  position: '',
  roleCode: '',
  regionCode: '',
  inviteCode: '',
  // 联系方式
  phone: '',
  phoneCode: '',
  email: '',
  emailCode: '',
  // 申请信息
  applyReason: '',
  // 合规与验证码
  agreed: false,
  captchaId: '',
  captchaCode: '',
})

/** 字段级错误：{ 字段名: 错误文案 }，有键即视为该字段未通过校验。 */
const errors = ref({})
/** 顶部提示条。匿名页面拿不到布局层的 notify，只能自带提示。 */
const banner = reactive({ type: '', text: '' })

const submitting = ref(false)
const captchaLoading = ref(false)
const captchaImage = ref('')
/** 提交成功后切到「待审核」视图，并记住用户名用于轮询状态。 */
const submittedUsername = ref('')
const statusView = ref(null)
const statusLoading = ref(false)

/** 两个通道各自的 60 秒倒计时。 */
const countdown = reactive({ phone: 0, email: 0 })
const sending = reactive({ phone: false, email: false })
let countdownTimer = null

const needsInviteCode = computed(() => form.roleCode === 'admin')

/** 提交成功后进入「等待审核」视图，而不是回登录页。 */
const isSubmitted = computed(() => Boolean(submittedUsername.value))

/**
 * 待审核视图的图标配色。
 *
 * 后端 status 直接存中文字符串（SysUserApplication.STATUS_*），
 * 所以这里按中文比对，不要改成枚举。
 *
 * @returns {'pending'|'ok'|'warn'} 图标样式后缀
 */
const statusClass = computed(() => {
  const status = statusView.value?.status
  if (status === '已通过') return 'ok'
  if (status === '已拒绝') return 'warn'
  return 'pending'
})

/* ── 校验 ──────────────────────────────────────────────── */

/**
 * 逐字段校验，规则与后端 RegisterRequest 的注解保持一致。
 *
 * @returns {object} 字段名 → 错误文案；空对象代表通过
 */
function validate() {
  const e = {}

  const username = form.username.trim()
  if (!username) e.username = '用户名不能为空'
  else if (!/^[a-zA-Z0-9_]{4,32}$/.test(username)) {
    e.username = '用户名为 4-32 位字母、数字或下划线'
  }

  if (!form.password) e.password = '密码不能为空'
  else if (form.password.length < 8 || form.password.length > 64) {
    e.password = '密码长度为 8-64 位'
  }

  if (!form.confirmPassword) e.confirmPassword = '请再次输入密码'
  else if (form.confirmPassword !== form.password) {
    e.confirmPassword = '两次输入的密码不一致'
  }

  const realName = form.realName.trim()
  if (!realName) e.realName = '姓名不能为空'
  else if (realName.length < 2 || realName.length > 32) {
    e.realName = '姓名长度为 2-32 个字符'
  }

  const idCard = form.idCard.trim()
  if (!idCard) e.idCard = '身份证号不能为空'
  else if (!/^\d{17}[\dXx]$/.test(idCard)) e.idCard = '身份证号为 18 位（末位可为 X）'

  const department = form.department.trim()
  if (!department) e.department = '所属单位不能为空'
  else if (department.length > 128) e.department = '所属单位不能超过 128 个字符'

  if (form.position.trim().length > 64) e.position = '职务不能超过 64 个字符'

  if (!form.roleCode) e.roleCode = '请选择申请角色'
  if (!form.regionCode) e.regionCode = '请选择所属区域'

  // 只有申请 admin 才需要邀请码，其余角色不显示该输入框，也就无从校验。
  if (needsInviteCode.value && !form.inviteCode.trim()) {
    e.inviteCode = '申请系统管理员必须填写邀请码'
  }

  const phone = form.phone.trim()
  if (!phone) e.phone = '手机号不能为空'
  else if (!/^1[3-9]\d{9}$/.test(phone)) e.phone = '手机号格式不正确'
  if (!form.phoneCode.trim()) e.phoneCode = '请填写短信验证码'

  const email = form.email.trim()
  if (!email) e.email = '邮箱不能为空'
  else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) e.email = '邮箱格式不正确'
  if (!form.emailCode.trim()) e.emailCode = '请填写邮箱验证码'

  const reason = form.applyReason.trim()
  if (!reason) e.applyReason = '请填写申请理由'
  else if (reason.length < 10 || reason.length > 500) {
    e.applyReason = '申请理由为 10-500 个字符'
  }

  if (!form.agreed) e.agreed = '请先阅读并同意用户协议与隐私政策'

  if (!form.captchaCode.trim()) e.captchaCode = '请填写图形验证码'

  return e
}

/* ── 图形验证码 ────────────────────────────────────────── */

/**
 * 拉取一张新的图形验证码。
 *
 * captchaId 必须与图片成对使用，后端按 id 校验答案，所以刷新图片时
 * 必须把旧 id 一起丢掉，否则用户会拿着上一张的 id 提交。
 */
async function loadCaptcha() {
  captchaLoading.value = true
  try {
    const data = await dashboardApi.getCaptcha()
    captchaImage.value = data?.imageBase64 ?? ''
    form.captchaId = data?.captchaId ?? ''
    form.captchaCode = ''
  } catch (err) {
    banner.type = 'error'
    banner.text = err?.message ?? '图形验证码加载失败，请稍后重试'
  } finally {
    captchaLoading.value = false
  }
}

/* ── 短信 / 邮箱验证码 ─────────────────────────────────── */

function stopCountdown() {
  if (countdownTimer !== null) {
    window.clearInterval(countdownTimer)
    countdownTimer = null
  }
}

/**
 * 启动倒计时。两个通道共用一个定时器，倒计时都归零后才停，
 * 避免为每个通道各起一个 interval。
 *
 * @param {'phone'|'email'} channel - 通道
 * @param {number} seconds - 倒计时秒数
 */
function startCountdown(channel, seconds = 60) {
  countdown[channel] = seconds
  if (countdownTimer !== null) return
  countdownTimer = window.setInterval(() => {
    if (countdown.phone > 0) countdown.phone -= 1
    if (countdown.email > 0) countdown.email -= 1
    if (countdown.phone === 0 && countdown.email === 0) stopCountdown()
  }, 1000)
}

/**
 * 向后端请求下发验证码。
 *
 * @param {'phone'|'email'} channel - 通道
 */
async function sendCode(channel) {
  const target = channel === 'phone' ? form.phone.trim() : form.email.trim()

  // 先本地校验一次：号码没填对就发请求，浪费一次后端的限流额度。
  if (channel === 'phone' && !/^1[3-9]\d{9}$/.test(target)) {
    banner.type = 'error'
    banner.text = '请先填写正确的手机号'
    return
  }
  if (channel === 'email' && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(target)) {
    banner.type = 'error'
    banner.text = '请先填写正确的邮箱'
    return
  }

  sending[channel] = true
  try {
    await dashboardApi.sendRegisterCode(channel, target)
    banner.type = 'success'
    banner.text = `验证码已发送至${channel === 'phone' ? '手机' : '邮箱'}，请注意查收`
    startCountdown(channel)
  } catch (err) {
    // 网关未接入时后端返回 50301，这里直接透传它的提示，
    // 让用户知道是「功能未开放」而不是「自己填错了」。
    banner.type = 'error'
    banner.text = err?.message ?? '验证码发送失败'
  } finally {
    sending[channel] = false
  }
}

/* ── 提交 ──────────────────────────────────────────────── */

async function submit() {
  banner.type = ''
  banner.text = ''

  const found = validate()
  errors.value = found
  if (Object.keys(found).length > 0) {
    banner.type = 'error'
    banner.text = '请修正表单中标红的字段'
    return
  }

  submitting.value = true
  try {
    await dashboardApi.registerRequest({
      username: form.username.trim(),
      password: form.password,
      confirmPassword: form.confirmPassword,
      realName: form.realName.trim(),
      idCard: form.idCard.trim(),
      department: form.department.trim(),
      position: form.position.trim(),
      roleCode: form.roleCode,
      regionCode: form.regionCode,
      inviteCode: needsInviteCode.value ? form.inviteCode.trim() : '',
      phone: form.phone.trim(),
      email: form.email.trim(),
      applyReason: form.applyReason.trim(),
      agreed: form.agreed,
      captchaId: form.captchaId,
      captchaCode: form.captchaCode.trim(),
      phoneCode: form.phoneCode.trim(),
      emailCode: form.emailCode.trim(),
    })

    submittedUsername.value = form.username.trim()
    stopCountdown()
    // 直接拉一次状态，让「待审核」视图一进来就带真实数据，
    // 不必让用户再点一次刷新。
    await refreshStatus()
  } catch (err) {
    banner.type = 'error'
    banner.text = err?.message ?? '提交失败，请稍后重试'
    // 图形验证码是一次性的，失败后必定已失效，必须换一张，
    // 否则用户只能靠反复刷新页面重来。
    if (/验证码/.test(err?.message ?? '')) loadCaptcha()
  } finally {
    submitting.value = false
  }
}

/* ── 状态查询 ──────────────────────────────────────────── */

async function refreshStatus() {
  const username = submittedUsername.value
  if (!username) return
  statusLoading.value = true
  try {
    statusView.value = await dashboardApi.getRegisterStatus(username)
  } catch (err) {
    banner.type = 'error'
    banner.text = err?.message ?? '状态查询失败'
  } finally {
    statusLoading.value = false
  }
}

/** 「重新填写」：清掉提交标记，回到空表单并换一张验证码。 */
function restart() {
  submittedUsername.value = ''
  statusView.value = null
  form.captchaCode = ''
  loadCaptcha()
}

function timeText(iso) {
  if (!iso) return '—'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString('zh-CN', { hour12: false })
}

onMounted(loadCaptcha)
onBeforeUnmount(stopCountdown)
</script>

<template>
  <div class="register-page">
    <div class="register-shell">
      <!-- 顶部：品牌 + 返回登录 -->
      <header class="register-top">
        <button type="button" class="back-btn" @click="router.push('/')">
          <ArrowLeft :size="15" />返回登录
        </button>
        <div class="brand">
          <ShieldCheck :size="18" />
          <span>御灾智巡 · DisasterGuard</span>
        </div>
      </header>

      <!-- 提示条 -->
      <p v-if="banner.text" class="banner" :class="banner.type">
        <AlertCircle v-if="banner.type === 'error'" :size="14" />
        <CheckCircle2 v-else :size="14" />
        {{ banner.text }}
      </p>

      <!-- ══ 视图一：提交申请 ══ -->
      <form v-if="!isSubmitted" novalidate class="register-form" @submit.prevent="submit">
        <div class="form-intro">
          <h1>申请账号</h1>
          <p>
            本系统不开放自助注册。提交后申请进入「待审核」，
            由系统管理员审核通过后账号才会创建。
          </p>
        </div>

        <!-- 1. 账号信息 -->
        <fieldset class="form-section">
          <legend>账号信息</legend>

          <div class="grid-2">
            <div class="form-group">
              <label class="form-label" for="reg-username">用户名 <i>*</i></label>
              <input
                id="reg-username"
                v-model="form.username"
                class="form-input"
                :class="{ invalid: errors.username }"
                autocomplete="username"
                placeholder="4-32 位字母、数字或下划线"
              />
              <small v-if="errors.username" class="field-error">{{ errors.username }}</small>
            </div>

            <div class="form-group">
              <label class="form-label" for="reg-department">所属单位 <i>*</i></label>
              <input
                id="reg-department"
                v-model="form.department"
                class="form-input"
                :class="{ invalid: errors.department }"
                placeholder="例如：成都市应急管理局"
              />
              <small v-if="errors.department" class="field-error">{{ errors.department }}</small>
            </div>
          </div>

          <div class="grid-2">
            <div class="form-group">
              <label class="form-label" for="reg-password">密码 <i>*</i></label>
              <input
                id="reg-password"
                v-model="form.password"
                class="form-input"
                :class="{ invalid: errors.password }"
                type="password"
                autocomplete="new-password"
                placeholder="8-64 位"
              />
              <small v-if="errors.password" class="field-error">{{ errors.password }}</small>
            </div>

            <div class="form-group">
              <label class="form-label" for="reg-confirm">确认密码 <i>*</i></label>
              <input
                id="reg-confirm"
                v-model="form.confirmPassword"
                class="form-input"
                :class="{ invalid: errors.confirmPassword }"
                type="password"
                autocomplete="new-password"
                placeholder="再次输入密码"
              />
              <small v-if="errors.confirmPassword" class="field-error">
                {{ errors.confirmPassword }}
              </small>
            </div>
          </div>
        </fieldset>

        <!-- 2. 身份信息 -->
        <fieldset class="form-section">
          <legend>身份信息</legend>

          <div class="grid-2">
            <div class="form-group">
              <label class="form-label" for="reg-realname">真实姓名 <i>*</i></label>
              <input
                id="reg-realname"
                v-model="form.realName"
                class="form-input"
                :class="{ invalid: errors.realName }"
                placeholder="与证件一致"
              />
              <small v-if="errors.realName" class="field-error">{{ errors.realName }}</small>
            </div>

            <div class="form-group">
              <label class="form-label" for="reg-idcard">身份证号 <i>*</i></label>
              <input
                id="reg-idcard"
                v-model="form.idCard"
                class="form-input"
                :class="{ invalid: errors.idCard }"
                maxlength="18"
                placeholder="18 位，末位可为 X"
              />
              <small v-if="errors.idCard" class="field-error">{{ errors.idCard }}</small>
            </div>
          </div>

          <div class="grid-2">
            <div class="form-group">
              <label class="form-label" for="reg-position">职务</label>
              <input
                id="reg-position"
                v-model="form.position"
                class="form-input"
                :class="{ invalid: errors.position }"
                placeholder="选填，最长 64 字"
              />
              <small v-if="errors.position" class="field-error">{{ errors.position }}</small>
            </div>

            <div class="form-group">
              <label class="form-label" for="reg-role">申请角色 <i>*</i></label>
              <select
                id="reg-role"
                v-model="form.roleCode"
                class="form-input"
                :class="{ invalid: errors.roleCode }"
              >
                <option value="">请选择角色</option>
                <option v-for="r in ROLE_OPTIONS" :key="r.value" :value="r.value">
                  {{ r.label }}
                </option>
              </select>
              <small v-if="errors.roleCode" class="field-error">{{ errors.roleCode }}</small>
              <small v-else-if="form.roleCode" class="field-hint">
                {{ ROLE_OPTIONS.find((r) => r.value === form.roleCode)?.desc }}
              </small>
            </div>
          </div>

          <div class="grid-2">
            <div class="form-group">
              <label class="form-label" for="reg-region">所属区域 <i>*</i></label>
              <select
                id="reg-region"
                v-model="form.regionCode"
                class="form-input"
                :class="{ invalid: errors.regionCode }"
              >
                <option value="">请选择区域</option>
                <option v-for="r in REGION_OPTIONS" :key="r.value" :value="r.value">
                  {{ r.label }}
                </option>
              </select>
              <small v-if="errors.regionCode" class="field-error">{{ errors.regionCode }}</small>
            </div>

            <!-- 仅申请 admin 时出现：后端 RegisterService 会校验邀请码 -->
            <div v-if="needsInviteCode" class="form-group">
              <label class="form-label" for="reg-invite">管理员邀请码 <i>*</i></label>
              <input
                id="reg-invite"
                v-model="form.inviteCode"
                class="form-input"
                :class="{ invalid: errors.inviteCode }"
                placeholder="向系统管理员索取"
              />
              <small v-if="errors.inviteCode" class="field-error">{{ errors.inviteCode }}</small>
            </div>
          </div>
        </fieldset>

        <!-- 3. 联系方式 -->
        <fieldset class="form-section">
          <legend>联系方式</legend>

          <div class="grid-2">
            <div class="form-group">
              <label class="form-label" for="reg-phone">手机号 <i>*</i></label>
              <div class="input-row">
                <input
                  id="reg-phone"
                  v-model="form.phone"
                  class="form-input"
                  :class="{ invalid: errors.phone }"
                  maxlength="11"
                  inputmode="numeric"
                  placeholder="11 位手机号"
                />
                <button
                  type="button"
                  class="code-btn"
                  :disabled="sending.phone || countdown.phone > 0"
                  @click="sendCode('phone')"
                >
                  <Loader2 v-if="sending.phone" :size="13" class="spin" />
                  <Send v-else :size="13" />
                  {{ countdown.phone > 0 ? `${countdown.phone}s` : '发送验证码' }}
                </button>
              </div>
              <small v-if="errors.phone" class="field-error">{{ errors.phone }}</small>
            </div>

            <div class="form-group">
              <label class="form-label" for="reg-phone-code">短信验证码 <i>*</i></label>
              <input
                id="reg-phone-code"
                v-model="form.phoneCode"
                class="form-input"
                :class="{ invalid: errors.phoneCode }"
                inputmode="numeric"
                placeholder="收到的短信验证码"
              />
              <small v-if="errors.phoneCode" class="field-error">{{ errors.phoneCode }}</small>
            </div>
          </div>

          <div class="grid-2">
            <div class="form-group">
              <label class="form-label" for="reg-email">邮箱 <i>*</i></label>
              <div class="input-row">
                <input
                  id="reg-email"
                  v-model="form.email"
                  class="form-input"
                  :class="{ invalid: errors.email }"
                  type="email"
                  placeholder="name@example.com"
                />
                <button
                  type="button"
                  class="code-btn"
                  :disabled="sending.email || countdown.email > 0"
                  @click="sendCode('email')"
                >
                  <Loader2 v-if="sending.email" :size="13" class="spin" />
                  <Mail v-else :size="13" />
                  {{ countdown.email > 0 ? `${countdown.email}s` : '发送验证码' }}
                </button>
              </div>
              <small v-if="errors.email" class="field-error">{{ errors.email }}</small>
            </div>

            <div class="form-group">
              <label class="form-label" for="reg-email-code">邮箱验证码 <i>*</i></label>
              <input
                id="reg-email-code"
                v-model="form.emailCode"
                class="form-input"
                :class="{ invalid: errors.emailCode }"
                placeholder="收到的邮件验证码"
              />
              <small v-if="errors.emailCode" class="field-error">{{ errors.emailCode }}</small>
            </div>
          </div>
        </fieldset>

        <!-- 4. 申请信息 -->
        <fieldset class="form-section">
          <legend>申请信息</legend>
          <div class="form-group">
            <label class="form-label" for="reg-reason">申请理由 <i>*</i></label>
            <textarea
              id="reg-reason"
              v-model="form.applyReason"
              class="form-input"
              :class="{ invalid: errors.applyReason }"
              rows="4"
              maxlength="500"
              placeholder="请说明所属单位、承担职责与使用场景（10-500 字）"
            ></textarea>
            <small class="counter">{{ form.applyReason.trim().length }} / 500</small>
            <small v-if="errors.applyReason" class="field-error">{{ errors.applyReason }}</small>
          </div>
        </fieldset>

        <!-- 5. 合规与验证码 -->
        <fieldset class="form-section">
          <legend>合规与验证码</legend>

          <label class="agree-row" :class="{ invalid: errors.agreed }">
            <input v-model="form.agreed" type="checkbox" />
            <span>
              我已阅读并同意《用户协议》与《隐私政策》，并确认所填信息真实有效
            </span>
          </label>
          <small v-if="errors.agreed" class="field-error agree-error">{{ errors.agreed }}</small>

          <div class="captcha-row">
            <div class="form-group captcha-field">
              <label class="form-label" for="reg-captcha">图形验证码 <i>*</i></label>
              <input
                id="reg-captcha"
                v-model="form.captchaCode"
                class="form-input"
                :class="{ invalid: errors.captchaCode }"
                maxlength="8"
                placeholder="输入右侧字符"
              />
              <small v-if="errors.captchaCode" class="field-error">{{ errors.captchaCode }}</small>
            </div>

            <button
              type="button"
              class="captcha-img"
              title="点击刷新"
              :disabled="captchaLoading"
              @click="loadCaptcha"
            >
              <img v-if="captchaImage" :src="captchaImage" alt="图形验证码" />
              <span v-else-if="captchaLoading">加载中</span>
              <span v-else>重新加载</span>
              <RefreshCw :size="12" class="refresh-mark" />
            </button>
          </div>
        </fieldset>

        <button type="submit" class="submit-btn" :disabled="submitting">
          <Loader2 v-if="submitting" :size="15" class="spin" />
          <User v-else :size="15" />
          {{ submitting ? '提交中...' : '提交注册申请' }}
        </button>

        <p class="form-foot">
          已有账号？
          <RouterLink to="/">返回登录</RouterLink>
        </p>
      </form>

      <!-- ══ 视图二：待审核 ══ -->
      <section v-else class="status-panel">
        <div class="status-icon" :class="statusClass">
          <CheckCircle2 v-if="statusClass === 'ok'" :size="30" />
          <AlertTriangle v-else-if="statusClass === 'warn'" :size="30" />
          <Clock v-else :size="30" />
        </div>

        <h1>申请已提交</h1>
        <p class="status-sub">
          账号 <strong>{{ submittedUsername }}</strong> 的申请已进入审核流程。
          管理员审核通过后即可用该用户名登录。
        </p>

        <dl class="status-detail">
          <div>
            <dt>当前状态</dt>
            <dd>{{ statusView?.status ?? '待审核' }}</dd>
          </div>
          <div>
            <dt>提交时间</dt>
            <dd>{{ timeText(statusView?.createdAt) }}</dd>
          </div>
        </dl>

        <p v-if="statusView?.status === '已拒绝'" class="reject-reason">
          <strong>拒绝原因：</strong>{{ statusView.rejectReason }}
        </p>

        <div class="status-actions">
          <button type="button" class="primary-btn" :disabled="statusLoading" @click="refreshStatus">
            <Loader2 v-if="statusLoading" :size="14" class="spin" />
            <RefreshCw v-else :size="14" />
            {{ statusLoading ? '查询中...' : '刷新状态' }}
          </button>
          <button type="button" class="ghost-btn" @click="restart">重新填写</button>
          <button type="button" class="ghost-btn" @click="router.push('/')">返回登录</button>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.register-page {
  min-height: 100vh;
  padding: 32px 20px 64px;
  background: var(--c-surface-3);
  color: var(--c-text-2);
  font-family: -apple-system, BlinkMacSystemFont, 'SF Pro Text', 'PingFang SC', 'Helvetica Neue', sans-serif;
}

.register-shell {
  width: min(820px, 100%);
  margin: 0 auto;
}

.register-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 18px;
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: var(--c-brand);
  font-size: 14px;
  font-weight: 600;
}

.back-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 7px 12px;
  border: 1px solid var(--c-brand-tint);
  border-radius: var(--r-sm);
  background: var(--c-surface);
  color: var(--c-text-2);
  font-size: 12px;
  cursor: pointer;
  transition: background 0.18s, color 0.18s;
}
.back-btn:hover { background: var(--c-brand-mist); color: var(--c-brand); }

.banner {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0 0 14px;
  padding: 10px 14px;
  border-radius: var(--r-md);
  font-size: 12px;
}
.banner.error {
  background: rgba(199, 85, 59, 0.1);
  color: #a83f28;
  border: 1px solid rgba(199, 85, 59, 0.3);
}
.banner.success {
  background: rgba(111, 158, 127, 0.12);
  color: var(--c-brand-hover);
  border: 1px solid rgba(111, 158, 127, 0.32);
}

/* ── 表单 ── */
.register-form,
.status-panel {
  background: var(--c-surface);
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-lg);
  padding: 26px 28px;
}

.form-intro { margin-bottom: 20px; }
.form-intro h1 {
  margin: 0 0 6px;
  color: var(--c-scrim);
  font-size: 20px;
  font-weight: 600;
}
.form-intro p {
  margin: 0;
  max-width: 620px;
  color: var(--c-text-3);
  font-size: 12px;
  line-height: 1.6;
}

.form-section {
  margin: 0 0 18px;
  padding: 16px 18px 4px;
  border: 1px solid var(--c-brand-mist);
  border-radius: var(--r-md);
  background: var(--c-surface-2);
}
.form-section legend {
  padding: 0 7px;
  color: var(--c-text-2);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
}

.grid-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
  margin-bottom: 14px;
}

.form-label i { color: var(--c-danger, #c7553b); font-style: normal; }
.form-group { display: flex; flex-direction: column; gap: 6px; }

.form-input.invalid {
  border-color: rgba(199, 85, 59, 0.6);
  box-shadow: 0 0 0 3px rgba(199, 85, 59, 0.08);
}

textarea.form-input { resize: vertical; min-height: 84px; }

.field-error { color: #b34a30; font-size: 11px; }
.field-hint { color: var(--c-text-4); font-size: 11px; }
.counter {
  align-self: flex-end;
  color: var(--c-text-4);
  font-size: 10px;
}

.input-row {
  display: flex;
  gap: 8px;
  align-items: center;
}
.input-row .form-input { flex: 1; min-width: 0; }

.code-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  padding: 0 11px;
  height: 38px;
  border: 1px solid var(--c-brand-tint);
  border-radius: var(--r-sm);
  background: var(--c-surface);
  color: var(--c-brand);
  font-size: 11px;
  white-space: nowrap;
  cursor: pointer;
  transition: background 0.18s, opacity 0.18s;
}
.code-btn:hover:not(:disabled) { background: var(--c-brand-mist); }
.code-btn:disabled { opacity: 0.55; cursor: not-allowed; }

/* 协议勾选 */
.agree-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 14px;
  color: var(--c-text-2);
  font-size: 12px;
  line-height: 1.55;
  cursor: pointer;
}
.agree-row input { margin-top: 2px; flex-shrink: 0; }
.agree-row.invalid { color: #b34a30; }
.agree-error { display: block; margin: -10px 0 14px; }

/* 图形验证码 */
.captcha-row {
  display: flex;
  align-items: flex-end;
  gap: 12px;
}
.captcha-field { flex: 1; }
.captcha-img {
  position: relative;
  display: grid;
  place-items: center;
  width: 132px;
  height: 38px;
  margin-bottom: 14px;
  overflow: hidden;
  border: 1px solid var(--c-brand-tint);
  border-radius: var(--r-sm);
  background: var(--c-surface);
  color: var(--c-text-4);
  font-size: 11px;
  cursor: pointer;
}
.captcha-img:disabled { opacity: 0.6; cursor: progress; }
.captcha-img img { width: 100%; height: 100%; object-fit: cover; display: block; }
.refresh-mark {
  position: absolute;
  right: 4px;
  bottom: 3px;
  color: var(--c-text-4);
  opacity: 0.65;
}

.submit-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  width: 100%;
  padding: 12px;
  border: 0;
  border-radius: var(--r-md);
  background: var(--c-brand);
  color: var(--c-on-brand);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: opacity 0.18s, transform 0.12s;
}
.submit-btn:hover:not(:disabled) { opacity: 0.92; }
.submit-btn:active:not(:disabled) { transform: scale(0.995); }
.submit-btn:disabled { opacity: 0.6; cursor: not-allowed; }

.form-foot {
  margin: 14px 0 0;
  text-align: center;
  color: var(--c-text-3);
  font-size: 12px;
}
.form-foot a { color: var(--c-brand); text-decoration: none; }
.form-foot a:hover { text-decoration: underline; }

/* ── 待审核视图 ── */
.status-panel { text-align: center; padding: 44px 32px; }
.status-icon {
  display: grid;
  place-items: center;
  width: 62px;
  height: 62px;
  margin: 0 auto 16px;
  border-radius: 50%;
}
.status-icon.pending {
  background: rgba(74, 140, 194, 0.12);
  color: #3d7ca8;
}
.status-icon.ok {
  background: rgba(111, 158, 127, 0.14);
  color: var(--c-brand-hover);
}
.status-icon.warn {
  background: rgba(199, 85, 59, 0.12);
  color: #b34a30;
}

.status-panel h1 {
  margin: 0 0 8px;
  color: var(--c-scrim);
  font-size: 20px;
  font-weight: 600;
}
.status-sub {
  margin: 0 auto 22px;
  max-width: 460px;
  color: var(--c-text-3);
  font-size: 13px;
  line-height: 1.65;
}
.status-sub strong { color: var(--c-ink-1); }

.status-detail {
  display: flex;
  justify-content: center;
  gap: 36px;
  margin: 0 0 20px;
  padding: 16px 0;
  border-top: 1px solid var(--c-brand-mist);
  border-bottom: 1px solid var(--c-brand-mist);
}
.status-detail div { text-align: center; }
.status-detail dt {
  color: var(--c-text-4);
  font-size: 11px;
  margin-bottom: 5px;
}
.status-detail dd {
  margin: 0;
  color: var(--c-ink-1);
  font-size: 14px;
  font-weight: 600;
}

.reject-reason {
  margin: 0 auto 20px;
  padding: 10px 14px;
  max-width: 460px;
  border-radius: var(--r-sm);
  background: rgba(199, 85, 59, 0.08);
  border: 1px solid rgba(199, 85, 59, 0.22);
  color: #8f3a24;
  font-size: 12px;
  line-height: 1.6;
  text-align: left;
}

.status-actions {
  display: flex;
  justify-content: center;
  gap: 10px;
  flex-wrap: wrap;
}
.primary-btn,
.ghost-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 9px 18px;
  border-radius: var(--r-pill);
  font-size: 12px;
  cursor: pointer;
  transition: opacity 0.18s, background 0.18s;
}
.primary-btn {
  border: 0;
  background: var(--c-brand);
  color: var(--c-on-brand);
  font-weight: 600;
}
.primary-btn:hover:not(:disabled) { opacity: 0.92; }
.primary-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.ghost-btn {
  border: 1px solid var(--c-brand-tint);
  background: transparent;
  color: var(--c-text-2);
}
.ghost-btn:hover { background: var(--c-brand-mist); }

.spin { animation: register-spin 0.9s linear infinite; }
@keyframes register-spin { to { transform: rotate(360deg); } }

@media (max-width: 640px) {
  .register-form, .status-panel { padding: 20px 16px; }
  .grid-2 { grid-template-columns: 1fr; }
  .status-detail { gap: 20px; }
}
</style>