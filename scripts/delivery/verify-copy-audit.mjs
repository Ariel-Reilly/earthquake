// 文案裁剪批次视觉核验 v2：先注入真实 token，再逐页导航截图
import { writeFileSync, mkdirSync } from 'node:fs';

const CDP = process.env.DG_CDP || 'http://127.0.0.1:9223';
const APP = process.env.DG_APP || 'http://127.0.0.1:5174';
const login = await (
  await fetch('http://127.0.0.1:8080/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'admin123' }),
  })
).json();
const TOKEN = login.data.token;
const PAGES = [
  ['', 'overview'],
  ['/dashboard/warnings', 'warnings'],
  ['/dashboard/emergency', 'emergency'],
  ['/dashboard/analytics', 'analytics'],
  ['/dashboard/hazards', 'hazards'],
  ['/dashboard/devices', 'devices'],
];
const OUT = 'output/video-review';
mkdirSync(OUT, { recursive: true });

const targets = await (await fetch(`${CDP}/json/list`)).json();
const tab = targets.find(t => t.type === 'page');
const ws = new WebSocket(tab.webSocketDebuggerUrl);
await new Promise((res, rej) => {
  ws.addEventListener('open', res, { once: true });
  ws.addEventListener('error', rej, { once: true });
});
let id = 0;
const pending = new Map();
ws.addEventListener('message', e => {
  const msg = JSON.parse(e.data);
  if (msg.id && pending.has(msg.id)) {
    pending.get(msg.id)(msg.result ?? msg.error);
    pending.delete(msg.id);
  }
});
const send = (m, p = {}) =>
  new Promise(res => {
    const mid = ++id;
    pending.set(mid, res);
    ws.send(JSON.stringify({ id: mid, method: m, params: p }));
  });
const sleep = ms => new Promise(r => setTimeout(r, ms));

await send('Page.enable');
await send('Runtime.enable');
// 先落地一次，写入 token，再进入业务页
await send('Page.navigate', { url: APP });
await sleep(600);
await send('Runtime.evaluate', {
  expression: `localStorage.setItem('disasterguard_token','${TOKEN}');localStorage.setItem('disasterguard_user',JSON.stringify({id:'u-admin',username:'admin',name:'管理员',role:'admin'}));`,
});

for (const [w, h] of [
  [1440, 900],
  [1024, 800],
  [375, 812],
]) {
  await send('Emulation.setDeviceMetricsOverride', { width: w, height: h, deviceScaleFactor: 1, mobile: w < 500 });
  for (const [path, name] of PAGES) {
    await send('Page.navigate', { url: `${APP}${path || '/dashboard'}` });
    await sleep(w === 1440 ? 4200 : 3000);
    const audit = await send('Runtime.evaluate', {
      expression: `(() => {
        const h2s = [...document.querySelectorAll('h2')];
        const pairs = h2s.map(h => {
          const sib = h.nextElementSibling;
          return { t: h.textContent.trim().slice(0,14), sub: sib && sib.tagName === 'P' ? sib.textContent.trim().slice(0,36) : null };
        }).filter(p => p.sub);
        return JSON.stringify({ h2: h2s.length, subs: pairs, overflow: document.documentElement.scrollWidth > innerWidth + 1 });
      })()`,
      returnByValue: true,
    });
    const d = JSON.parse(audit.result.value);
    console.log(`[${w}] ${name.padEnd(10)} h2=${String(d.h2).padEnd(3)} 残留副标题=${d.subs.length} 溢出=${d.overflow}`);
    if (d.subs.length) console.log('     ', d.subs.map(s => `${s.t}→${s.sub}`).join(' | '));
    if (w === 1440) {
      const shot = await send('Page.captureScreenshot', { format: 'png' });
      writeFileSync(`${OUT}/copy-${name}.png`, Buffer.from(shot.data, 'base64'));
    }
  }
}
ws.close();
