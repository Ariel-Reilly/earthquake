/**
 * 验证「全部功能」态下分组 Tab 文字是否丢失
 */
import { chromium } from 'file:///C:/Users/27118/node_modules/playwright-core/index.mjs'

const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
const browser = await chromium.launch({ executablePath: EDGE, headless: true })
const page = await browser.newPage({ viewport: { width: 1500, height: 950 } })

await page.goto('http://localhost:5173/', { waitUntil: 'networkidle' })
await page.waitForTimeout(1200)
await page.locator('.nav-cta, .btn-primary', { hasText: /进入系统|登录/ }).first().click().catch(() => {})
await page.waitForTimeout(900)
await page.locator('.login-modal input:not([type="password"])').first().fill('admin').catch(() => {})
await page.locator('.login-modal input[type="password"]').first().fill('123456').catch(() => {})
await page.locator('.login-modal button[type="submit"]').first().click().catch(() => {})
await page.waitForTimeout(4000)

// 先点「全部功能」
await page.locator('.hnav-group-all').click()
await page.waitForTimeout(600)

const probe = await page.evaluate(() => {
  return [...document.querySelectorAll('.hnav-group')].map((g) => {
    const span = g.querySelector('span:not(.hnav-group-count)')
    const cs = span ? getComputedStyle(span) : null
    return {
      text: g.textContent.trim().replace(/\s+/g, ' '),
      groupW: Math.round(g.getBoundingClientRect().width),
      spanDisplay: cs?.display ?? 'n/a',
      spanW: span ? Math.round(span.getBoundingClientRect().width) : -1,
      spanColor: cs?.color ?? 'n/a',
      fontSize: cs?.fontSize ?? 'n/a',
    }
  })
})
console.log(JSON.stringify(probe, null, 2))

await browser.close()
