/**
 * 横向导航栏实测：真实登录 → 逐分组点击 → 截图 + DOM 探针
 * 用法: node scripts/audit/shot-hnav.mjs
 */
import { chromium } from 'file:///C:/Users/27118/node_modules/playwright-core/index.mjs'
import fs from 'node:fs'

const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
const OUT = 'D:/Projects/DisasterGuard/scripts/audit/_shots'
fs.mkdirSync(OUT, { recursive: true })

const browser = await chromium.launch({ executablePath: EDGE, headless: true })
const page = await browser.newPage({ viewport: { width: 1500, height: 950 } })

const errs = []
page.on('pageerror', (e) => errs.push('pageerror: ' + e.message))
page.on('console', (m) => { if (m.type() === 'error') errs.push('console: ' + m.text()) })

await page.goto('http://localhost:5173/', { waitUntil: 'networkidle' })
await page.waitForTimeout(1200)

// WelcomePage: 先点「进入系统」打开登录弹窗
await page.locator('.nav-cta, .btn-primary', { hasText: /进入系统|登录/ }).first().click().catch(async () => {
  await page.locator('button:has-text("进入系统")').first().click().catch(() => {})
})
await page.waitForTimeout(900)

// 填写弹窗表单
const userSel = '.login-modal input[type="text"], .login-modal input:not([type="password"])'
await page.locator(userSel).first().fill('admin').catch(() => {})
await page.locator('.login-modal input[type="password"]').first().fill('123456').catch(() => {})
await page.locator('.login-modal form button[type="submit"], .login-modal button[type="submit"]').first().click().catch(() => {})
await page.waitForTimeout(4000)

const url = page.url()
console.log('登录后 URL:', url)

// 探针：横向导航结构
const probe = await page.evaluate(() => {
  const q = (s) => document.querySelector(s)
  const qa = (s) => [...document.querySelectorAll(s)]
  const groups = qa('.hnav-group')
  const items = qa('.hnav-item')
  const sidebar = q('.sidebar')
  const sbStyle = sidebar ? getComputedStyle(sidebar) : null
  const hnav = q('.hnav')
  const sub = q('.hnav-sub')
  const main = q('.main-content')
  return {
    hasHnav: !!hnav,
    hnavHeight: hnav?.getBoundingClientRect().height ?? 0,
    hnavBg: hnav ? getComputedStyle(hnav).backgroundImage : '',
    groupCount: groups.length,
    groupLabels: groups.map((g) => g.textContent.trim().replace(/\s+/g, ' ')),
    activeGroup: qa('.hnav-group.active').map((g) => g.textContent.trim()),
    subVisible: sub ? sub.getBoundingClientRect().height : 0,
    subTitle: q('.hnav-sub-title')?.textContent.trim() ?? '',
    itemCount: items.length,
    itemLabels: items.map((i) => i.textContent.trim().replace(/\s+/g, ' ')),
    sidebarDisplay: sbStyle?.display ?? 'n/a',
    mainMarginLeft: main ? getComputedStyle(main).marginLeft : 'n/a',
    mainWidth: main?.getBoundingClientRect().width ?? 0,
  }
})
console.log('\n=== 横向导航探针 ===')
console.log(JSON.stringify(probe, null, 2))

await page.screenshot({ path: `${OUT}/hnav-01-initial.png` })

// 逐分组点击
const groupCount = probe.groupCount
for (let i = 0; i < groupCount; i++) {
  await page.locator('.hnav-group').nth(i).click()
  await page.waitForTimeout(450)
  const info = await page.evaluate(() => ({
    title: document.querySelector('.hnav-sub-title')?.textContent.trim() ?? '',
    count: document.querySelectorAll('.hnav-item').length,
  }))
  console.log(`分组[${i}] → ${info.title} (${info.count} 项)`)
  await page.screenshot({ path: `${OUT}/hnav-02-group-${i}.png` })
}

// 回到「全部功能」
const allIdx = groupCount - 1
await page.locator('.hnav-group').nth(allIdx).click()
await page.waitForTimeout(500)
const allInfo = await page.evaluate(() => {
  const wrap = document.querySelector('.hnav-items')
  return {
    title: document.querySelector('.hnav-sub-title')?.textContent.trim() ?? '',
    count: document.querySelectorAll('.hnav-item').length,
    scrollWidth: wrap?.scrollWidth ?? 0,
    clientWidth: wrap?.clientWidth ?? 0,
  }
})
console.log('\n全部功能视图:', JSON.stringify(allInfo))
await page.screenshot({ path: `${OUT}/hnav-03-all.png` })

// 点击一个二级项验证路由跳转
await page.locator('.hnav-item', { hasText: '统计分析' }).first().click().catch(() => {})
await page.waitForTimeout(1800)
console.log('跳转后 URL:', page.url())
await page.screenshot({ path: `${OUT}/hnav-04-after-nav.png` })

console.log('\n=== 错误 ===')
console.log(errs.length ? errs.join('\n') : '(无)')

await browser.close()
