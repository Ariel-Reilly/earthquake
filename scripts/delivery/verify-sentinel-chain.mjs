/**
 * B1-5 验收脚本：ai-service 时相对比端点链路实测（compose ×2 → diff → 图片）。
 *
 * 用法（先启动加载了新代码的 ai-service，例如 28081 验证实例）：
 *   SENTINEL_VERIFY_BASE=http://127.0.0.1:28081/api/ai node scripts/delivery/verify-sentinel-chain.mjs
 *
 * 校验点：
 *   - compose 返回的 result 明确带 dataSource（B1-4 诚实降级标记）；
 *   - diff 返回 diffUrl / changePercent / hotspots；
 *   - 合成图与热力图可通过 /sentinel_files/ 访问。
 * 该脚本只读调用 + 生成任务，不改数据库；输出可直接贴进验收日志。
 */
const BASE = (process.env.SENTINEL_VERIFY_BASE || 'http://127.0.0.1:28081/api/ai').replace(/\/$/, '')
const CDN_BASE = process.env.SENTINEL_VERIFY_CDN || BASE.replace(/\/api\/ai$/, '')
const BBOX = [103.30, 30.80, 103.80, 31.20]

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function call(path, options = {}) {
  const resp = await fetch(`${BASE}${path}`, {
    ...options,
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
  })
  const payload = await resp.json().catch(() => ({}))
  if (!resp.ok) {
    throw new Error(`${path} -> HTTP ${resp.status}: ${JSON.stringify(payload).slice(0, 300)}`)
  }
  return payload
}

async function waitTerminal(path, jobId, label, timeoutMs = 90000) {
  const started = Date.now()
  while (Date.now() - started < timeoutMs) {
    const job = await call(`${path}/${jobId}`)
    if (['succeeded', 'failed', 'cancelled'].includes(job.status)) {
      console.log(`  ${label}: ${job.status} / ${job.progress}% / ${job.message || ''}`)
      if (job.status !== 'succeeded') {
        throw new Error(`${label} 未成功：${job.message}`)
      }
      return job
    }
    await sleep(800)
  }
  throw new Error(`${label} 轮询超时`)
}

async function checkImage(url, label) {
  if (!url) {throw new Error(`${label} 缺少图片 URL`)}
  const absolute = url.startsWith('http') ? url : `${CDN_BASE}${url}`
  const resp = await fetch(absolute)
  const buf = Buffer.from(await resp.arrayBuffer())
  console.log(`  ${label}: HTTP ${resp.status}, ${buf.length} bytes, ${url.split('/').pop()}`)
  if (!resp.ok || buf.length === 0) {throw new Error(`${label} 图片不可达`)}
  return url.split('/').pop()
}

async function main() {
  console.log(`=== B1-5 ai-service 时相对比链路实测 (${BASE}) ===`)

  const before = await call('/sentinel/compose', {
    method: 'POST',
    body: JSON.stringify({ bbox: BBOX, start: '2025-09-01', end: '2025-09-30', max_cloud: 50, provider: 'mock', sar_overlay: false }),
  })
  const after = await call('/sentinel/compose', {
    method: 'POST',
    body: JSON.stringify({ bbox: BBOX, start: '2026-08-01', end: '2026-09-10', max_cloud: 50, provider: 'mock', sar_overlay: false }),
  })
  console.log(`  提交合成任务: before=${before.jobId.slice(0, 8)}… after=${after.jobId.slice(0, 8)}…`)

  const [b, a] = await Promise.all([
    waitTerminal('/sentinel/jobs', before.jobId, '灾前合成'),
    waitTerminal('/sentinel/jobs', after.jobId, '灾后合成'),
  ])
  console.log(`  数据来源标记: before=${b.result?.dataSource} / after=${a.result?.dataSource}`)
  if (!b.result?.dataSource || !a.result?.dataSource) {
    throw new Error('compose 结果缺少 dataSource（B1-4 诚实降级标记）')
  }
  const beforeFile = await checkImage(b.result.s2Url, '灾前影像')
  await checkImage(a.result.s2Url, '灾后影像')

  const diff = await call('/sentinel/diff', {
    method: 'POST',
    body: JSON.stringify({
      before_job_id: before.jobId,
      after_job_id: after.jobId,
      bbox: BBOX,
      threshold: 30,
      colormap: 'inferno',
    }),
  })
  const d = await waitTerminal('/sentinel/diff-jobs', diff.jobId, '差异计算')
  console.log(`  差异结果: changePercent=${d.result?.changePercent}% hotspots=${d.result?.hotspots?.length ?? 0}`)
  const diffFile = await checkImage(d.result.diffUrl, '差异热力图')

  console.log('=== PASS：compose/diff/图片链路可用，降级标记存在 ===')
  console.log(`  图片文件名（供后端网关代理验证）: ${beforeFile} / ${diffFile}`)
}

main().catch((e) => {
  console.error(`=== FAIL: ${e.message} ===`)
  process.exit(1)
})
