/**
 * 挖空基线起点自检（赛前入库；现场 3′45″–4′25″ 只跑一次，输出投屏）
 *
 * 目的：在 55 分钟开始之前，用一条命令证明"四个写端点现在点了就失败"。
 *      四个 [FAIL] 就是四处方法体挖空点，评委看到的是真实的失败，不是预设。
 *
 * 判据（现场口径）：
 *   3′45″  4 个 [FAIL] / 0 个 [PASS]   ← 第一次，等四个 FAIL 全出现再开口
 *   12′00″ 4 个 [FAIL] / 0 个 [PASS]   ← 契约冻结时重跑一次，证明还没开始补
 *
 * 本脚本**只在方法体还空着的时候跑**（3′45″ 与 12′00″ 两次）。
 * 17′40″ 之后的"端点通了"用 A-04 单端点打一次、用页面点一次证明，不用本脚本——
 * 因为方法体一恢复，本脚本的四个 POST 会真的推进状态、真的落库，属于破坏性操作。
 *
 * 与 warning-loop-e2e.mjs 的分工：
 *   本脚本只验"端点通不通"，不做闭环断言；
 *   e2e 负责六组闭环断言与三表核对，现场新建。两者不互相替代。
 *
 * 用法：
 *   D 机（跨机）：BASE=http://192.168.10.11:8080/api node scripts/audit/precheck.mjs
 *   单机降级：    node scripts/audit/precheck.mjs
 *   换账号：      PC_USER=admin PC_PASS=admin123 ...
 *
 * 退出码：0 = 四个全 FAIL（起点正确）；1 = 出现 PASS（挖空未生效）；2 = 前置失败（登录/取行/连不上）
 */
const BASE = (process.env.BASE ?? 'http://localhost:8080/api').replace(/\/$/, '');
const USER = process.env.PC_USER ?? 'admin';
const PASS = process.env.PC_PASS ?? 'admin123';

const ENDPOINTS = [
  { key: 'confirm', method: 'POST', path: '/warnings/{id}/confirm', body: null },
  { key: 'dispatch', method: 'POST', path: '/warnings/{id}/dispatch', body: null },
  { key: 'close', method: 'POST', path: '/warnings/{id}/close', body: { reason: 'precheck' } },
  {
    key: 'resolve',
    method: 'POST',
    path: '/warnings/{id}/resolve',
    body: { reason: 'precheck', scope: 'all', scopeArea: '' },
  },
];

function line(s) {
  process.stdout.write(s + '\n');
}

async function call(method, path, { token, body } = {}) {
  const headers = { Accept: 'application/json' };
  if (token) headers.Authorization = 'Bearer ' + token;
  if (body !== undefined && body !== null) headers['Content-Type'] = 'application/json';
  const res = await fetch(BASE + path, {
    method,
    headers,
    body: body === undefined || body === null ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  return { status: res.status, text };
}

async function main() {
  line('== 御灾智巡　挖空基线起点自检 ==');
  line('BASE = ' + BASE);

  // 1. 登录
  let token;
  try {
    const r = await call('POST', '/auth/login', { body: { username: USER, password: PASS } });
    if (r.status !== 200) {
      line('[BLOCKED] 登录失败 ' + r.status + ' ' + r.text.slice(0, 160));
      return 2;
    }
    const j = JSON.parse(r.text);
    token = j?.data?.token;
    if (!token) {
      line('[BLOCKED] 登录响应里没有 token：' + r.text.slice(0, 160));
      return 2;
    }
    line('[OK] 登录成功 user=' + (j?.data?.user?.username ?? USER));
  } catch (e) {
    line('[BLOCKED] 连不上后端：' + (e?.message ?? e));
    line('        先确认 A-07 已起、BASE 地址正确（跨机是 192.168.10.11:8080）');
    return 2;
  }

  // 2. 取一条可操作的预警 id（优先"待确认"）
  let id;
  try {
    const r = await call('GET', '/warnings?page=1&size=50', { token });
    if (r.status !== 200) {
      line('[BLOCKED] 取预警列表失败 ' + r.status + ' ' + r.text.slice(0, 160));
      return 2;
    }
    const j = JSON.parse(r.text);
    const rows = j?.data?.records ?? [];
    if (rows.length === 0) {
      line('[BLOCKED] 预警表为空：先由沙盘触发一条，或跑 C-06 场景');
      return 2;
    }
    const pending = rows.find((w) => String(w.status ?? '').includes('待确认')) ?? rows[0];
    id = pending.id;
    line('[OK] 目标预警 id=' + id + ' status=' + (pending.status ?? '-'));
  } catch (e) {
    line('[BLOCKED] 解析预警列表失败：' + (e?.message ?? e));
    return 2;
  }

  // 3. 四个端点逐个打
  line('');
  const rows = [];
  for (const ep of ENDPOINTS) {
    const path = ep.path.replace('{id}', String(id));
    let r;
    try {
      r = await call(ep.method, path, { token, body: ep.body });
    } catch (e) {
      rows.push({ key: ep.key, state: 'BLOCKED', note: '请求异常 ' + (e?.message ?? e) });
      line('[BLOCKED] ' + ep.key + ' ' + (e?.message ?? e));
      continue;
    }
    const todo = /CONTEST_TODO/.test(r.text);
    const hollow = /UnsupportedOperationException/.test(r.text);
    if (r.status >= 500 && (todo || hollow)) {
      rows.push({ key: ep.key, state: 'FAIL', note: r.status + ' CONTEST_TODO' });
      line('[FAIL] ' + ep.key + ' ' + r.status + ' CONTEST_TODO');
    } else if (r.status === 200) {
      rows.push({ key: ep.key, state: 'PASS', note: '200' });
      line('[PASS] ' + ep.key + ' 200');
    } else {
      rows.push({ key: ep.key, state: 'BLOCKED', note: r.status + ' ' + r.text.slice(0, 120) });
      line('[BLOCKED] ' + ep.key + ' ' + r.status + ' ' + r.text.slice(0, 120));
    }
  }

  // 4. 汇总
  const nFail = rows.filter((x) => x.state === 'FAIL').length;
  const nPass = rows.filter((x) => x.state === 'PASS').length;
  const nBlocked = rows.filter((x) => x.state === 'BLOCKED').length;
  line('');
  line('------ 汇总 ------');
  line('[FAIL] ' + nFail + '  [PASS] ' + nPass + '  [BLOCKED] ' + nBlocked);
  line('起点期望：4 FAIL / 0 PASS');

  if (nBlocked > 0) {
    line('判定：前置异常（不是挖空问题），按 BLOCKED 行逐条排查，不带病上场。');
    return 2;
  }
  if (nFail === 4 && nPass === 0) {
    line('判定：起点正确——四处方法体确认为空，可以开始。');
    return 0;
  }
  if (nPass > 0) {
    line('判定：有端点返回 200，挖空未生效。');
    line('      现场处置：立即停，核对四处方法体是否残留 CONTEST_TODO（不许口头补一句"应该是好的"）。');
    return 1;
  }
  return 1;
}

main().then(
  (code) => process.exit(code),
  (e) => {
    line('[BLOCKED] 未捕获异常：' + (e?.stack ?? e));
    process.exit(2);
  }
);
