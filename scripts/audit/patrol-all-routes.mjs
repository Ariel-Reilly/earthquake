/**
 * 48+ 路由全量巡检（横向导航改造后回归）
 * 真实登录 → 逐路由访问 → pageerror / console.error / 错误边界检测
 * 用法: node scripts/audit/patrol-all-routes.mjs
 */
import { chromium } from 'file:///C:/Users/27118/node_modules/playwright-core/index.mjs'
import { readFileSync } from 'node:fs'

const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
const src = readFileSync('D:/Projects/DisasterGuard/frontend/src/router/index.js', 'utf-8')
const paths = [...src.matchAll(/path:\s*['"`]([^'"`]+)['"`]/g)]
  .map((m) => m[1])
  .filter((p) => !p.includes(':') && p.startsWith('/'))
console.log(`路由总数(无参): ${paths.length}`)

const browser = await chromium.launch({ executablePath: EDGE, headless: true })
const page = await browser.newPage({ viewport: { width: 1500, height: 950 } })

const errs = []
page.on('pageerror', (e) => errs.push(`[pageerror] ${page.url()} :: ${e.message}`))
page.on('console', (m) => {
  if (m.type() === 'error' && !m.text().includes('Failed to load resource')) {
    errs.push(`[console] ${page.url()} :: ${m.text().slice(0, 160)}`)
  }
})

// 登录一次
await page.goto('http://localhost:5173/', { waitUntil: 'networkidle' })
await page.waitForTimeout(1200)
await page.locator('.nav-cta, .btn-primary', { hasText: /进入系统|登录/ }).first().click().catch(() => {})
await page.waitForTimeout(900)
await page.locator('.login-modal input:not([type="password"])').first().fill('admin').catch(() => {})
await page.locator('.login-modal input[type="password"]').first().fill('123456').catch(() => {})
await page.locator('.login-modal button[type="submit"]').first().click().catch(() => {})
await page.waitForTimeout(4000)
console.log('登录后:', page.url())

const badBoundary = []
let ok = 0
for (const p of paths) {
  errs.length = 0
  try {
    await page.goto(`http://localhost:5173${p}`, { waitUntil: 'domcontentloaded', timeout: 15000 })
    await page.waitForTimeout(1100)
  } catch (e) {
    errs.push(`[goto] ${e.message.slice(0, 100)}`)
  }
  const boundaryText = await page
    .evaluate(() => document.body.innerText.slice(0, 400))
    .catch(() => '')
  const hasBoundary = /页面出错了|出错了|出現錯誤|Something went wrong|错误边界/.test(boundaryText)
  if (hasBoundary) badBoundary.push(p)
  const real = errs.filter((e) => !e.includes('404') && !e.includes('401') && !e.includes('403'))
  if (real.length || hasBoundary) {
    console.log(`✗ ${p}`)
    for (const e of real) console.log('   ', e)
    if (hasBoundary) console.log('    错误边界触发')
  } else {
    ok++
  }
}
console.log(`\n=== 结果: ${ok}/${paths.length} 正常 ===`)
if (badBoundary.length) console.log('错误边界页面:', badBoundary.join(', '))
const errSample = errs.slice(0, 10)
if (errSample.length) console.log('末轮错误样本:\n' + errSample.join('\n'))
await browser.close()
