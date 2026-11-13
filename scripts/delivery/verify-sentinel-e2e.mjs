/**
 * SM-19 / DELIVERY_BACKLOG §31.4 端到端缺口验收：
 * 「带 JWT 的客户端 → Java 网关 SentinelCompareController → ai-service → 同源图片代理」。
 *
 * 前置：
 *   1) 已部署本轮 jar 的后端（默认 http://127.0.0.1:28083/api，可用 E2E_API_BASE 覆盖）；
 *   2) ai-service 已启动，且后端 AI_SERVICE_BASE_URL 指向它。
 * 用法：node scripts/delivery/verify-sentinel-e2e.mjs
 *
 * 说明：只读调用 + 生成临时合成任务，不改业务数据；无真实卫星凭证时 dataSource=mock，
 *       本脚本仍验证全链路可达，但**不代表真实影像能力已具备**（见 KNOWN_LIMITATIONS L-05）。
 * 输出可直接贴入 ACCEPTANCE_LOG。
 */
const API = (process.env.E2E_API_BASE || 'http://127.0.0.1:28083/api').replace(/\/$/, '')
const ORIGIN = API.replace(/\/api$/, '')
const PASSWORD = process.env.E2E_PASSWORD || 'admin123'
const BBOX = [103.30, 30.80, 103.80, 31.20]
const PNG_MAGIC = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const checks = []
function check(name, ok, detail = '') {
  checks.push({ name, ok, detail })
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`)
}

async function http(url, opts = {}) {
  const { token, method = 'GET', body } = opts
  const headers = {}
  if (token) headers.Authorization = `Bearer ${token}`
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const resp = await fetch(url, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const buf = Buffer.from(await resp.arrayBuffer())
  let json = null
  try { json = JSON.parse(buf.toString('utf8')) } catch { /* 二进制图片 */ }
  return { status: resp.status, json, buf, type: resp.headers.get('content-type') || '' }
}

const api = (path, opts) => http(`${API}${path}`, opts)
const image = (path, opts) => http(`${ORIGIN}${path}`, opts)

async function login(username) {
  const r = await api('/auth/login', {
    method: 'POST',
    body: { username, password: PASSWORD },
  })
  if (r.status !== 200 || !r.json?.data?.token) {
    throw new Error(`登录失败 ${username}: HTTP ${r.status} ${JSON.stringify(r.json).slice(0, 160)}`)
  }
  return r.json.data.token
}

async function waitJob(path, jobId, label, token, timeoutMs = 150000) {
  const started = Date.now()
  while (Date.now() - started < timeoutMs) {
    const r = await api(`${path}/${jobId}`, { token })
    if (r.status !== 200) {
      throw new Error(`${label} 查询失败：HTTP ${r.status} ${JSON.stringify(r.json).slice(0, 160)}`)
    }
    const job = r.json
    if (['succeeded', 'failed', 'cancelled'].includes(job.status)) {
      if (job.status !== 'succeeded') {
        throw new Error(`${label} 未成功：${job.status} / ${job.message || ''}`)
      }
      return job
    }
    await sleep(700)
  }
  throw new Error(`${label} 轮询超时`)
}

const composeBody = (start, end) => ({
  bbox: BBOX,
  start,
  end,
  max_cloud: 50,
  provider: 'mock',
  sar_overlay: false,
})

async function main() {
  console.log(`=== 时相对比 Java 网关端到端验收 (${API}) ===`)

  // ── ① 鉴权与参数负向用例 ────────────────────────────────────────
  const admin = await login('admin')
  check('admin 登录并取得 JWT', admin.length > 100, `${admin.length} 字符`)

  const noToken = await api('/ai/sentinel/compose', { method: 'POST', body: composeBody('2025-09-01', '2025-09-30') })
  check('未带 JWT 被拒绝', noToken.status === 403, `HTTP ${noToken.status}`)

  const fieldops = await login('fieldops')
  const forbidden = await api('/ai/sentinel/compose', {
    token: fieldops,
    method: 'POST',
    body: composeBody('2025-09-01', '2025-09-30'),
  })
  check('maintenance（无 satellite:view）被拒绝', forbidden.status === 403, `HTTP ${forbidden.status}`)

  const duty = await login('duty')
  const attendantJob = await api('/ai/sentinel/compose', {
    token: duty,
    method: 'POST',
    body: composeBody('2025-09-01', '2025-09-30'),
  })
  check('attendant（持有 satellite:view）可提交', attendantJob.status === 200 && !!attendantJob.json?.jobId,
    `HTTP ${attendantJob.status}`)
  if (attendantJob.json?.jobId) {
    await api(`/ai/sentinel/jobs/${attendantJob.json.jobId}/cancel`, { token: duty, method: 'POST', body: {} })
  }

  const badBbox = await api('/ai/sentinel/compose', {
    token: admin,
    method: 'POST',
    body: { ...composeBody('2025-09-01', '2025-09-30'), bbox: [1, 2, 'x'] },
  })
  check('非法 bbox 返回 400 + 可读中文', badBbox.status === 400 && badBbox.json?.code === 'INVALID_BBOX',
    `HTTP ${badBbox.status} / ${badBbox.json?.message || ''}`)

  const traversal = await image('/api/ai/sentinel/image/..%2F..%2F..%2Fetc%2Fpasswd', { token: admin })
  check('图片路径穿越被拒绝（不返回 200）', traversal.status !== 200, `HTTP ${traversal.status}`)

  // ── ② 正向链路：compose ×2 → 图片代理 → diff → 热力图 ────────────
  const before = await api('/ai/sentinel/compose', {
    token: admin,
    method: 'POST',
    body: composeBody('2025-09-01', '2025-09-30'),
  })
  const after = await api('/ai/sentinel/compose', {
    token: admin,
    method: 'POST',
    body: composeBody('2026-08-01', '2026-09-10'),
  })
  check('灾前/灾后 compose 均受理', before.status === 200 && after.status === 200 && !!before.json?.jobId && !!after.json?.jobId,
    `${before.status}/${after.status}`)

  const [b, a] = await Promise.all([
    waitJob('/ai/sentinel/jobs', before.json.jobId, '灾前合成', admin),
    waitJob('/ai/sentinel/jobs', after.json.jobId, '灾后合成', admin),
  ])
  check('compose 结果带 dataSource 诚实标记',
    ['real', 'mock'].includes(b.result?.dataSource) && ['real', 'mock'].includes(a.result?.dataSource),
    `${b.result?.dataSource} / ${a.result?.dataSource}`)
  check('图片 URL 已重写为同源网关地址',
    typeof b.result?.s2Url === 'string' && b.result.s2Url.startsWith('/api/ai/sentinel/image/'),
    b.result?.s2Url)

  const beforeImg = await image(b.result.s2Url, { token: admin })
  check('灾前影像经网关代理可取（PNG）',
    beforeImg.status === 200 && beforeImg.buf.length > 1000 && beforeImg.buf.subarray(0, 8).equals(PNG_MAGIC),
    `HTTP ${beforeImg.status}, ${beforeImg.buf.length} B, ${beforeImg.type}`)

  const diff = await api('/ai/sentinel/diff', {
    token: admin,
    method: 'POST',
    body: {
      before_job_id: before.json.jobId,
      after_job_id: after.json.jobId,
      bbox: BBOX,
      threshold: 30,
      colormap: 'inferno',
    },
  })
  check('diff 任务受理', diff.status === 200 && !!diff.json?.jobId, `HTTP ${diff.status}`)
  const d = await waitJob('/ai/sentinel/diff-jobs', diff.json.jobId, '差异计算', admin)
  check('diff 结果含 changePercent / hotspots',
    typeof d.result?.changePercent === 'number' && Array.isArray(d.result?.hotspots),
    `changePercent=${d.result?.changePercent}%, hotspots=${d.result?.hotspots?.length ?? 0}`)

  const diffImg = await image(d.result.diffUrl, { token: admin })
  check('差异热力图经网关代理可取（PNG）',
    diffImg.status === 200 && diffImg.buf.length > 1000 && diffImg.buf.subarray(0, 8).equals(PNG_MAGIC),
    `HTTP ${diffImg.status}, ${diffImg.buf.length} B`)

  const raw = JSON.stringify([b, a, d])
  check('响应未泄漏上游 8001/28081 或 file:// 地址',
    !/:8001|:28081|file:\/\//i.test(raw))

  // ── ③ 汇总 ──────────────────────────────────────────────────────
  const failed = checks.filter((c) => !c.ok)
  console.log(`=== ${failed.length ? 'FAIL' : 'PASS'}：${checks.length - failed.length}/${checks.length} 项通过 ===`)
  console.log(`  数据来源：dataSource=${b.result.dataSource}（无真实影像凭证时为 mock，页面按 B1-4 只提示降级）`)
  if (failed.length) process.exit(1)
}

main().catch((e) => {
  console.error(`=== FAIL: ${e.message} ===`)
  process.exit(1)
})
