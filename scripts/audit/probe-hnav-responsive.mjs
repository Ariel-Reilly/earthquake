/**
 * 响应式断点实测：1024 / 880 / 390 三档
 */
import { chromium } from 'file:///C:/Users/27118/node_modules/playwright-core/index.mjs'

const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
const OUT = 'D:/Projects/DisasterGuard/scripts/audit/_shots'
const browser = await chromium.launch({ executablePath: EDGE, headless: true })

async function login(page) {
  await page.goto('http://localhost:5173/', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1200)
  await page.locator('.nav-cta, .btn-primary', { hasText: /进入系统|登录/ }).first().click().catch(() => {})
  await page.waitForTimeout(900)
  await page.locator('.login-modal input:not([type="password"])').first().fill('admin').catch(() => {})
  await page.locator('.login-modal input[type="password"]').first().fill('123456').catch(() => {})
  await page.locator('.login-modal button[type="submit"]').first().click().catch(() => {})
  await page.waitForTimeout(4000)
}

for (const [name, w, h] of [['tablet-1024', 1024, 800], ['narrow-880', 880, 800], ['mobile-390', 390, 844]]) {
  const page = await browser.newPage({ viewport: { width: w, height: h } })
  await login(page)
  const probe = await page.evaluate(() => {
    const q = (s) => document.querySelector(s)
    const vis = (s) => {
      const el = q(s)
      if (!el) return 'absent'
      const cs = getComputedStyle(el)
      return cs.display === 'none' ? 'hidden' : 'visible'
    }
    return {
      hnav: vis('.hnav'),
      topbarSub: vis('.topbar-sub'),
      sidebar: vis('.sidebar'),
      tabbar: vis('.ios-tabbar'),
      mobileMenuBtn: vis('.mobile-menu-btn'),
      groupSpanVisible: (() => {
        const s = q('.hnav-group span:not(.hnav-group-count)')
        return s ? getComputedStyle(s).display : 'n/a'
      })(),
    }
  })
  console.log(`── ${name} (${w}px) ──`, JSON.stringify(probe))

  // 移动端: 测抽屉开合
  if (w <= 768) {
    await page.locator('.mobile-menu-btn').click().catch(() => {})
    await page.waitForTimeout(700)
    const open = await page.evaluate(() => {
      const sb = document.querySelector('.sidebar')
      return { cls: sb?.className, transform: getComputedStyle(sb).transform }
    })
    console.log('  抽屉打开:', JSON.stringify(open))
    await page.screenshot({ path: `${OUT}/resp-${name}-drawer.png` })
    await page.locator('.sidebar-overlay').click({ force: true }).catch(() => {})
    await page.waitForTimeout(700)
    await page.screenshot({ path: `${OUT}/resp-${name}-closed.png` })
  } else {
    await page.screenshot({ path: `${OUT}/resp-${name}.png` })
  }
  await page.close()
}

await browser.close()
console.log('done')
