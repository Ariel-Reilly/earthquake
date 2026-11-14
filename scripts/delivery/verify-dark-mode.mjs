/**
 * B3 深色模式审计：真实浏览器逐页检查文字对比度与"深色模式下的浅底块"。
 *
 * 前置：前端 5173、后端 8080、Edge CDP 9222。
 * 用法：node scripts/delivery/verify-dark-mode.mjs
 * 输出：output/delivery-verify/dark-mode-audit.json + dark-*.png
 */
import fs from 'node:fs'
import path from 'node:path'

const CDP = 'http://127.0.0.1:9222'
const APP = 'http://127.0.0.1:5173'
const OUT = path.resolve('output/delivery-verify')
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const ROUTES = [
  ['/dashboard', 'overview'],
  ['/dashboard/warnings', 'warnings'],
  ['/dashboard/devices', 'devices'],
  ['/dashboard/real-time-map', 'map'],
  ['/dashboard/audit-log', 'audit'],
  ['/dashboard/ai-tasks', 'ai-tasks'],
  ['/dashboard/settings', 'settings'],
]

const target = await (await fetch(`${CDP}/json/new?about:blank`, { method: 'PUT' })).json()
const ws = new WebSocket(target.webSocketDebuggerUrl)
await new Promise((res, rej) => { ws.addEventListener('open', res, { once: true }); ws.addEventListener('error', rej, { once: true }) })
let id = 0
const pending = new Map()
ws.addEventListener('message', (ev) => {
  const m = JSON.parse(ev.data)
  if (m.id && pending.has(m.id)) { const p = pending.get(m.id); pending.delete(m.id); m.error ? p.reject(new Error(m.error.message)) : p.resolve(m.result) }
})
const send = (method, params = {}) => new Promise((resolve, reject) => {
  const i = ++id; pending.set(i, { resolve, reject })
  ws.send(JSON.stringify({ id: i, method, params }))
  setTimeout(() => { if (pending.has(i)) { pending.delete(i); reject(new Error('timeout ' + method)) } }, 30000)
})
const evalJs = async (expression) => {
  const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.text + ' ' + (r.exceptionDetails.exception?.description || ''))
  return r.result?.value
}

const AUDIT_JS = `(() => {
  const parse = (s) => {
    const m = String(s || '').match(/rgba?\\(([^)]+)\\)/);
    if (!m) return null;
    const p = m[1].split(/[,\\s\\/]+/).filter(Boolean).map(Number);
    return { r: p[0], g: p[1], b: p[2], a: p.length > 3 ? p[3] : 1 };
  };
  const lum = (c) => {
    const f = (v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
    return 0.2126 * f(c.r) + 0.7152 * f(c.g) + 0.0722 * f(c.b);
  };
  const ratio = (a, b) => { const l1 = lum(a), l2 = lum(b); return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05); };
  const bodyBg = parse(getComputedStyle(document.body).backgroundColor) || { r: 255, g: 255, b: 255, a: 1 };
  const effBg = (el) => {
    const layers = [];
    for (let n = el; n; n = n.parentElement) {
      const c = parse(getComputedStyle(n).backgroundColor);
      if (c && c.a > 0) layers.push(c);
    }
    let out = { ...bodyBg, a: 1 };
    if (bodyBg.a < 1) out = { r: 255, g: 255, b: 255, a: 1 };
    for (let i = layers.length - 1; i >= 0; i--) {
      const c = layers[i];
      out = { r: out.r * (1 - c.a) + c.r * c.a, g: out.g * (1 - c.a) + c.g * c.a, b: out.b * (1 - c.a) + c.b * c.a, a: 1 };
    }
    return out;
  };
  const visible = (el) => {
    const cs = getComputedStyle(el);
    if (cs.display === 'none' || cs.visibility === 'hidden' || Number(cs.opacity) < 0.35) return false;
    const r = el.getBoundingClientRect();
    return r.width > 1 && r.height > 1 && r.bottom > 0 && r.right > 0 && r.top < innerHeight && r.left < innerWidth;
  };
  const pathOf = (el) => {
    const bits = [];
    for (let n = el; n && n.nodeType === 1 && bits.length < 4; n = n.parentElement) {
      bits.unshift(n.tagName.toLowerCase() + (n.className && typeof n.className === 'string' ? '.' + n.className.trim().split(/\\s+/).slice(0, 2).join('.') : ''));
    }
    return bits.join(' > ');
  };
  const contrast = [];
  const light = [];
  const seen = new Set();
  for (const el of document.querySelectorAll('body *')) {
    if (!visible(el)) continue;
    const cs = getComputedStyle(el);
    const text = [...el.childNodes].filter((n) => n.nodeType === 3).map((n) => n.textContent.trim()).join(' ').trim();
    if (text) {
      const fg = parse(cs.color);
      if (fg) {
        const bg = effBg(el);
        const size = parseFloat(cs.fontSize) || 14;
        const bold = Number(cs.fontWeight) >= 600;
        const large = size >= 24 || (size >= 18.66 && bold);
        const r = ratio(fg, bg);
        if (r < (large ? 3 : 4.5)) {
          const key = cs.color + '|' + pathOf(el) + '|' + text.slice(0, 20);
          if (!seen.has(key)) {
            seen.add(key);
            contrast.push({ path: pathOf(el), text: text.slice(0, 60), color: cs.color, bg: 'rgb(' + Math.round(bg.r) + ' ' + Math.round(bg.g) + ' ' + Math.round(bg.b) + ')', ratio: Math.round(r * 100) / 100, size });
          }
        }
      }
    }
    const bg = parse(cs.backgroundColor);
    if (bg && bg.a > 0.8 && lum(bg) > 0.72) {
      const rect = el.getBoundingClientRect();
      const area = rect.width * rect.height;
      if (area > innerWidth * innerHeight * 0.04) {
        const key = pathOf(el);
        if (!seen.has('L' + key)) {
          seen.add('L' + key);
          light.push({ path: key, bg: cs.backgroundColor, area: Math.round(area) });
        }
      }
    }
  }
  return {
    theme: document.documentElement.getAttribute('data-theme'),
    className: document.documentElement.className,
    bodyBg: getComputedStyle(document.body).backgroundColor,
    contrast: contrast.sort((a, b) => a.ratio - b.ratio).slice(0, 30),
    light: light.slice(0, 20),
  };
})()`

async function main() {
  fs.mkdirSync(OUT, { recursive: true })
  await send('Page.enable'); await send('Runtime.enable')
  await send('Page.navigate', { url: APP }); await sleep(3500)
  await evalJs(`(async () => {
    const res = await fetch('/api/auth/login', {method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({username:'admin',password:'admin123'})});
    const body = await res.json();
    if (body.code === 0) { localStorage.setItem('disasterguard_token', body.data.token); localStorage.setItem('disasterguard_user', JSON.stringify(body.data.user)); }
    localStorage.setItem('disasterguard_theme', 'dark');
    document.documentElement.classList.add('theme-dark');
    document.documentElement.setAttribute('data-theme', 'dark');
    return body.code;
  })()`)
  const results = []
  for (const [route, name] of ROUTES) {
    await send('Page.navigate', { url: APP + route })
    await sleep(3500)
    // 与 browser-smoke 一致：直接给 <html> 打主题钩子（真实用户从设置页切换时由 useTheme 写入）
    await evalJs(`(() => { document.documentElement.classList.add('theme-dark'); document.documentElement.setAttribute('data-theme', 'dark'); return true })()`)
    await sleep(600)
    let audit
    try { audit = await evalJs(AUDIT_JS) } catch (err) { audit = { error: String(err.message || err) } }
    const shot = await send('Page.captureScreenshot', { format: 'png' })
    fs.writeFileSync(path.join(OUT, `dark-${name}.png`), Buffer.from(shot.data, 'base64'))
    results.push({ route, name, ...audit })
    console.log(`${name}: theme=${audit.theme} contrast=${audit.contrast?.length ?? '-'} light=${audit.light?.length ?? '-'}${audit.error ? ' error=' + audit.error : ''}`)
  }
  fs.writeFileSync(path.join(OUT, 'dark-mode-audit.json'), JSON.stringify(results, null, 2))
  const total = results.reduce((n, r) => n + (r.contrast?.length || 0) + (r.light?.length || 0), 0)
  console.log(`TOTAL_ISSUES=${total} -> ${path.join(OUT, 'dark-mode-audit.json')}`)
  process.exit(0)
}

main().catch((err) => { console.error(err); process.exit(1) })
