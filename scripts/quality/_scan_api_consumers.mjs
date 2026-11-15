/**
 * 临时扫描器：统计 dashboardApi 各方法在前端源码中的消费点数量，
 * 并标注每个消费点的读取方式（.records / .items / .list / 纯数组）。
 * 用法：node scripts/quality/_scan_api_consumers.mjs
 */
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

function walk(dir, out = []) {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    if (e.name === '__tests__' || e.name === 'node_modules') continue
    const p = join(dir, e.name)
    if (e.isDirectory()) walk(p, out)
    else if (/\.(vue|js)$/.test(e.name)) out.push(p)
  }
  return out
}

const files = walk('frontend/src').filter((f) => !f.endsWith('dashboardApi.js'))
const src = files.map((f) => ({ file: f.replace(/\\/g, '/'), text: readFileSync(f, 'utf8') }))

const targets = process.argv.slice(2)
const methods =
  targets.length > 0
    ? targets
    : [
        'getHazards',
        'getHazardsByViewport',
        'getAiMonitorHazards',
        'getConfirmationQueue',
        'listUnifiedEvents',
        'listMyConfirmations',
        'listMyApprovals',
        'listMyPendingWarnings',
        'listMyOpenTasks',
        'listApprovals',
        'getSyncTasks',
        'getSyncHistory',
        'listModels',
        'getModelTrainingJobs',
        'listFirmware',
        'listOtaTasks',
        'listNoticeTemplates',
        'listPlans',
        'listPlanLaunches',
        'listBackups',
        'listBackupStrategies',
        'listRestoreHistory',
        'listLoginHistory',
        'listActiveSessions',
        'listAccountAudit',
        'listReportTemplates',
        'listReportHistory',
        'listDataAssets',
        'listCatalogUsage',
        'listSignRecords',
        'getAiDeviceHealthAll',
        'getAiComprehensiveAll',
        'getAuditLogs',
        'getWarningDeliveryRecords',
      ]

console.log('method\tconsumers\treaders')
for (const m of methods) {
  // 兼容可选调用 dashboardApi.foo?.(...) 与普通调用 foo(...)
  const re = new RegExp(`dashboardApi\\.${m}\\b\\s*\\??\\.?\\s*\\(`, 'g')
  const hits = []
  for (const s of src) {
    let match
    let count = 0
    while ((match = re.exec(s.text)) !== null) count++
    if (count > 0) hits.push({ file: s.file, count })
  }
  // 读取方式：找赋值语句后 6 行内的 .records/.items/.list/Array.isArray
  const readers = new Set()
  for (const s of src) {
    const idx = s.text.search(new RegExp(`dashboardApi\\.${m}\\b\\s*\\??\\.?\\s*\\(`))
    if (idx < 0) continue
    const win = s.text.slice(idx, idx + 700)
    if (/\.records\b/.test(win)) readers.add('records')
    if (/\.items\b/.test(win)) readers.add('items')
    if (/\.list\b/.test(win)) readers.add('list')
    if (/Array\.isArray\(/.test(win)) readers.add('isArray')
    if (/normalizeList|normalizePage/.test(win)) readers.add('normalize*')
  }
  const total = hits.reduce((a, b) => a + b.count, 0)
  console.log(`${m}\t${total}\t${[...readers].join('+') || '直接使用'}`)
  for (const h of hits) console.log(`  - ${h.file} ×${h.count}`)
}
