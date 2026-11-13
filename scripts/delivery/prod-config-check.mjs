/**
 * 生产环境配置检查器（交付用，只读）。
 *
 * 用法：
 *   node scripts/delivery/prod-config-check.mjs [envFile] [--json]
 *
 * 默认检查仓库统一部署入口 .env（生产环境用 your-prod.env 覆盖）。
 * 退出码：0=全部通过；1=存在 error；仅 warn 时仍返回 0。
 *
 * 检查项与后端 ProductionConfigValidator 对齐（prod profile 启动时还会二次 fail-fast）：
 *  - JWT / AES / Postgres / Redis / MinIO 密钥是否为强随机值（非 CHANGE_ME、非开发默认）；
 *  - DEV_USERS_ENABLED=false、DEVICE_AUTH_STRICT=true、WS_REQUIRE_TICKET=true；
 *  - 前端域名、附件地址、MinIO 地址不能仍是 localhost / *；
 *  - AI 服务端口必须绑定 127.0.0.1（不直接暴露公网）；
 *  - 短信/邮件注册、EMQX 凭证等高风险开关不得误开。
 */
import fs from 'node:fs'
import path from 'node:path'
import { randomBytes } from 'node:crypto'

const DEV_DEFAULTS = new Set([
  'this_is_a_very_secure_secret_key_for_disaster_guard_2026_!@#',
  'a_very_secure_data_encryption_key_2026_!@#',
  'CHANGE_ME__dev_postgres_password_2026',
  'CHANGE_ME__dev_redis_password_2026',
  'CHANGE_ME__dev_minio_password_24chars_minimum_2026',
])

const WEAK_TOKENS = new Set([
  'password', 'password123', '123456', '12345678', '123456789',
  'qwerty', 'admin', 'admin123', 'changeme', 'change_me', 'letmein',
  'welcome', 'monkey', 'dragon', 'baseball', 'iloveyou', 'disasterguard',
])

const args = process.argv.slice(2)
const jsonMode = args.includes('--json')
const envFileArg = args.find((arg) => !arg.startsWith('--')) || '.env'
const envPath = path.resolve(envFileArg)

/** 解析 KEY=VALUE（忽略注释、导出前缀、引号）。 */
function parseEnv(text) {
  const map = new Map()
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim()
    if (!line || line.startsWith('#')) continue
    const match = line.match(/^(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/)
    if (!match) continue
    let value = match[2].trim()
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1)
    }
    map.set(match[1], value)
  }
  return map
}

function isPlaceholder(value) {
  const v = String(value ?? '').trim()
  return !v
    || DEV_DEFAULTS.has(v)
    || /^__GENERATE/i.test(v)
    || /^your[-_]/i.test(v)
    || /CHANGE_ME/i.test(v)
    || /^\*+$/.test(v)
    || /^<.*>$/.test(v)
    || WEAK_TOKENS.has(v.toLowerCase())
}

const checks = []
function add(id, level, ok, detail, hint = '') {
  checks.push({ id, level, ok: Boolean(ok), detail, hint })
}

if (!fs.existsSync(envPath)) {
  console.error(`环境文件不存在: ${envPath}`)
  process.exit(2)
}
const env = parseEnv(fs.readFileSync(envPath, 'utf8'))
const get = (key) => env.get(key) ?? ''

function strongSecret(key, minLength, id) {
  const value = get(key)
  add(id, 'error', !isPlaceholder(value) && value.length >= minLength,
    `${key} ${value ? `长度=${value.length}` : '未设置'}`,
    `生成：node -e "console.log(require('crypto').randomBytes(32).toString('base64'))"`)
}
function booleanCheck(key, expected, id, hint) {
  const raw = get(key)
  const ok = String(raw).trim().toLowerCase() === String(expected)
  add(id, 'error', ok, `${key}=${raw || '(未设置)'}，期望=${expected}`, hint)
}

strongSecret('JWT_SECRET', 32, 'E01_JWT_SECRET')
strongSecret('DATA_ENCRYPTION_KEY', 32, 'E02_DATA_ENCRYPTION_KEY')
strongSecret('POSTGRES_PASSWORD', 12, 'E03_POSTGRES_PASSWORD')
strongSecret('REDIS_PASSWORD', 12, 'E04_REDIS_PASSWORD')
strongSecret('MINIO_ROOT_PASSWORD', 16, 'E05_MINIO_SECRET')
add('E06_MINIO_ACCESS_KEY', 'error', !isPlaceholder(get('MINIO_ROOT_USER')) || !isPlaceholder(get('MINIO_ACCESS_KEY')),
  `MINIO_ROOT_USER=${get('MINIO_ROOT_USER') || '(未设置)'}`, '不要把默认 disasterguard 作为生产 AccessKey')

booleanCheck('DEV_USERS_ENABLED', 'false', 'E07_DEV_USERS', 'prod profile 会拒绝启动；生产禁止硬编码账号 fallback')
booleanCheck('DEVICE_AUTH_STRICT', 'true', 'E08_DEVICE_AUTH_STRICT', '设备上报必须带鉴权，否则可被匿名伪造数据')
booleanCheck('WS_REQUIRE_TICKET', 'true', 'E09_WS_TICKET', '避免 raw JWT 暴露在 WebSocket URL 中')

const frontendOrigin = get('FRONTEND_ORIGIN')
add('E10_FRONTEND_ORIGIN', 'error',
  frontendOrigin && frontendOrigin !== '*' && !frontendOrigin.includes('localhost'),
  `FRONTEND_ORIGIN=${frontendOrigin || '(未设置)'}`, '生产必须填可信前端域名，如 https://disasterguard.example.com')
if (frontendOrigin && !frontendOrigin.startsWith('https://')) {
  add('E10b_HTTPS', 'warn', false, `FRONTEND_ORIGIN 不是 https：${frontendOrigin}`, '建议统一 HTTPS')
}

for (const [key, id] of [['FILE_BASE_URL', 'E11_FILE_BASE_URL'], ['MINIO_ENDPOINT', 'E12_MINIO_ENDPOINT']]) {
  const value = get(key)
  add(id, 'error', value && !value.includes('localhost') && !value.includes('127.0.0.1') && !value.includes('://minio:'),
    `${key}=${value || '(未设置)'}`, '必须是浏览器可达的外部地址（presigned URL 会用它生成）')
}

const aiPort = get('AI_HOST_PORT')
add('E13_AI_BIND', 'error', /^127\.0\.0\.1:/.test(aiPort),
  `AI_HOST_PORT=${aiPort || '(未设置)'}`, 'AI 服务不对外，绑定 127.0.0.1（或用网络策略限制 8001）')

const backendPort = get('BACKEND_HOST_PORT')
const frontendPort = get('FRONTEND_HOST_PORT')
if (backendPort || frontendPort) {
  const bound = [backendPort, frontendPort].every((value) => !value || /^127\.0\.0\.1:/.test(value))
  add('E14_PORT_BIND', 'error', bound, `BACKEND_HOST_PORT=${backendPort || '(默认)'}, FRONTEND_HOST_PORT=${frontendPort || '(默认)'}`, '后端/前端容器端口只绑定本机，公网统一走 nginx 80/443')
}

const emqxEnabled = String(get('EMQX_ENABLED')).toLowerCase() === 'true'
if (emqxEnabled) {
  strongSecret('EMQX_PASSWORD', 12, 'E15_EMQX_PASSWORD')
  add('E15b_EMQX_USER', 'error', !isPlaceholder(get('EMQX_USERNAME')), `EMQX_USERNAME=${get('EMQX_USERNAME') || '(未设置)'}`)
} else {
  add('E15_EMQX', 'warn', true, 'EMQX_ENABLED 未开启（按需启用）')
}

for (const key of ['REGISTER_SMS_ENABLED', 'REGISTER_EMAIL_ENABLED']) {
  add(`E16_${key}`, 'error', String(get(key)).toLowerCase() !== 'true', `${key}=${get(key) || '(未设置)'}`, '短信/邮件网关未接入时开启会让注册接口"假装发送成功"')
}
for (const key of ['ALIYUN_SMS_ENABLED']) {
  add(`E16b_${key}`, 'warn', String(get(key)).toLowerCase() !== 'true', `${key}=${get(key) || '(未设置)'}`, '无真实短信凭证时保持关闭')
}

add('E17_DEBUG_ENDPOINTS', 'error',
  String(get('DEBUG_ENDPOINTS') || '').toLowerCase() !== 'true' && String(get('DISASTERGUARD_DEBUG_ENDPOINTS') || '').toLowerCase() !== 'true',
  '调试端点必须关闭', '生产禁用 /api/warnings/debug-trigger 等')

add('E18_AI_ENABLED', 'warn', String(get('AI_ENABLED') || 'true').toLowerCase() === 'true',
  `AI_ENABLED=${get('AI_ENABLED') || '(默认 true)'}`, '关闭时页面应显示降级提示')

const apiBase = get('VITE_API_BASE_URL')
if (apiBase) {
  add('E19_API_URL', 'warn', apiBase.startsWith('https://'), `VITE_API_BASE_URL=${apiBase}`, '生产建议 https')
}

const failed = checks.filter((item) => item.level === 'error' && !item.ok)
const warned = checks.filter((item) => item.level === 'warn' && !item.ok)

if (jsonMode) {
  console.log(JSON.stringify({ envFile: envPath, passed: failed.length === 0, failed, warned, checks }, null, 2))
} else {
  console.log(`生产配置检查: ${envPath}`)
  for (const item of checks) {
    const tag = item.ok ? 'PASS' : (item.level === 'error' ? 'FAIL' : 'WARN')
    console.log(`[${tag}] ${item.id}: ${item.detail}${!item.ok && item.hint ? `  → ${item.hint}` : ''}`)
  }
  console.log(`\n结果: error=${failed.length} warn=${warned.length} total=${checks.length}`)
  if (failed.length > 0) {
    console.log('修复后再执行本检查；后端 prod profile 启动时 ProductionConfigValidator 会再次 fail-fast。')
  }
}

process.exit(failed.length > 0 ? 1 : 0)
