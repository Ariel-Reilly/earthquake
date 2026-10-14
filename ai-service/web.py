#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DisasterGuard AI · 独立网页界面（standalone Web UI）。

这是 ai-service 模块的"对外门面 #2"——一个独立进程的 Web UI 服务器，
与 FastAPI API 服务（端口 8001）完全解耦：

  • 不依赖 FastAPI API 在跑（直接加载模型、调用推理）
  • 不依赖 npm/前端构建（vanilla HTML + JS，无 build step）
  • 单条命令启动：`python web.py`，默认 8080 端口

提供的网页：
  /              仪表盘（模型状态 + 服务健康 + 实时日志流）
  /detect        图像识别（拖拽上传，YOLO 推理，可视化 bbox）
  /forecast      时序预测（输入历史序列，LSTM 预测 + 置信带图）
  /comprehensive 综合风险分析（多因子滑块，仪表盘式得分）
  /anomaly       异常检测（多设备特征矩阵，结果可视化）
  /endpoints     API 端点速查（实时 curl 命令生成）

────────────────────────────────────────────────────────────
启动方式：

  python ai-service/web.py                       # 默认 127.0.0.1:8080
  python ai-service/web.py --host 0.0.0.0       # 监听所有网卡
  python ai-service/web.py --port 9090
  AI_WEB_PORT=8080 python ai-service/web.py

  或通过 CLI 启动器：
  python ai-service/ai.py web                   # 同上
  python ai-service/ai.py web --port 9090

────────────────────────────────────────────────────────────
环境变量：
  AI_WEB_HOST       默认 127.0.0.1
  AI_WEB_PORT       默认 8080
  AI_WEB_RELOAD     1 时启用 --reload（开发用）
  AI_SERVICE_URL    当 FastAPI 在跑时，Web UI 会探针它（仅展示，不强制）
"""
from __future__ import annotations

import argparse
import asyncio
import io
import json
import logging
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict

# Windows 终端默认 GBK，把 stdout 强制改成 UTF-8（不然菜单里的 emoji / 特殊字符无法打印）
if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

# ── 路径 bootstrap（支持任意 cwd 调用） ──────────────────────────────
_THIS = Path(__file__).resolve()
SCRIPTS_DIR = _THIS.parent / "scripts"
sys.path.insert(0, str(SCRIPTS_DIR))
from _bootstrap import setup_path  # noqa: E402

AI_ROOT: Path = setup_path()
MODELS_DIR: Path = AI_ROOT / "models"
LOGS_DIR: Path = AI_ROOT / "logs"
LOG_FILE: Path = LOGS_DIR / "ai-service.log"
LOGS_DIR.mkdir(parents=True, exist_ok=True)

# ── FastAPI / uvicorn（项目已有依赖） ───────────────────────────────
from fastapi import FastAPI, File, Form, HTTPException, Query, UploadFile  # noqa: E402
from fastapi.middleware.cors import CORSMiddleware  # noqa: E402
from fastapi.responses import HTMLResponse, JSONResponse, PlainTextResponse  # noqa: E402
import uvicorn  # noqa: E402
from fastapi import Request  # noqa: E402

# ── 日志 ──────────────────────────────────────────────────────────
logging.basicConfig(
    level=os.environ.get("AI_LOG_LEVEL", "INFO").upper(),
    format="%(asctime)s %(levelname)s [web-ui] %(message)s",
)
logger = logging.getLogger("ai-web")

WEB_HOST = os.environ.get("AI_WEB_HOST", "127.0.0.1")
WEB_PORT = int(os.environ.get("AI_WEB_PORT", "8080"))
SERVICE_URL = os.environ.get("AI_SERVICE_URL", "http://127.0.0.1:8001")

# ╔══════════════════════════════════════════════════════════════════════╗
# ║  HTML 模板                                                            ║
# ╚══════════════════════════════════════════════════════════════════════╝

PAGE_CSS = r"""
:root {
  --bg:#0b1020; --panel:#131a30; --panel2:#1b2340; --border:#243054;
  --fg:#e6edf3; --muted:#8b94b0;
  --accent:#4fc3f7; --accent2:#7c4dff;
  --ok:#66bb6a; --warn:#ffa726; --err:#ef5350; --crit:#ff5252;
  --low:#66bb6a; --med:#ffa726; --high:#ff7043; --critical:#ff1744;
}
* { box-sizing:border-box; }
body { margin:0; font:14px/1.55 -apple-system,"Segoe UI",Roboto,"PingFang SC",
       "Microsoft YaHei",sans-serif; background:var(--bg); color:var(--fg); }
a { color:var(--accent); text-decoration:none; }
a:hover { text-decoration:underline; }
header { padding:14px 22px; background:linear-gradient(135deg,#1a2253,#0f1631);
         border-bottom:1px solid var(--border); display:flex; align-items:center; gap:14px; position:sticky; top:0; z-index:10; }
header h1 { margin:0; font-size:17px; font-weight:600; }
header .pill { padding:3px 10px; border-radius:99px; font-size:11px;
               background:rgba(79,195,247,.15); color:var(--accent); border:1px solid rgba(79,195,247,.3); }
nav { display:flex; gap:4px; margin-left:auto; flex-wrap:wrap; }
nav a { padding:6px 12px; border-radius:6px; color:var(--muted); font-size:13px; }
nav a:hover { background:var(--panel); color:var(--fg); text-decoration:none; }
nav a.active { background:rgba(79,195,247,.18); color:var(--accent); }
main { padding:20px 24px 40px; max-width:1400px; margin:0 auto; }
h2 { margin:24px 0 12px; font-size:14px; color:var(--accent);
     text-transform:uppercase; letter-spacing:.04em; }
h2:first-child { margin-top:0; }
.card { background:var(--panel); border:1px solid var(--border); border-radius:10px;
        padding:16px 18px; margin-bottom:14px; }
.card-row { display:grid; grid-template-columns:repeat(auto-fit,minmax(220px,1fr)); gap:12px; }
.kv { display:flex; justify-content:space-between; padding:5px 0;
      border-bottom:1px dashed #2a3258; font-size:13px; }
.kv:last-child { border:0; }
.kv .k { color:var(--muted); }
.kv .v { font-family:ui-monospace,"SF Mono",Consolas,monospace; }
.row { display:flex; align-items:center; gap:8px; padding:6px 0;
       border-bottom:1px dashed #2a3258; }
.row:last-child { border:0; }
.dot { width:10px; height:10px; border-radius:50%; flex:0 0 auto; }
.dot.ok { background:var(--ok); box-shadow:0 0 6px rgba(102,187,106,.6); }
.dot.warn { background:var(--warn); }
.dot.err { background:var(--err); }
.name { flex:1; }
.size { color:var(--muted); font-family:ui-monospace,monospace; font-size:12px; }
button, .btn { background:var(--accent); color:#0b1020; border:0;
               border-radius:6px; padding:7px 14px; cursor:pointer;
               font-weight:600; font-size:13px; transition:filter .15s; }
button:hover, .btn:hover { filter:brightness(1.15); }
button:disabled { opacity:.5; cursor:not-allowed; }
.btn-ghost { background:transparent; color:var(--accent); border:1px solid var(--accent); }
.btn-danger { background:var(--err); color:#fff; }
input[type=text], input[type=number], input[type=file], select, textarea {
  background:#0c1228; color:var(--fg); border:1px solid var(--border);
  border-radius:6px; padding:7px 10px; font-size:13px; font-family:inherit;
  width:100%; }
input[type=range] { width:100%; }
label { display:block; color:var(--muted); font-size:12px; margin:8px 0 4px; }
table { width:100%; border-collapse:collapse; font-size:13px; }
th, td { padding:8px 10px; text-align:left; border-bottom:1px solid var(--border); }
th { color:var(--muted); font-weight:600; text-transform:uppercase; font-size:11px; letter-spacing:.04em; }
tr:hover td { background:rgba(79,195,247,.05); }
.status-up { color:var(--ok); font-weight:600; }
.status-down { color:var(--err); font-weight:600; }
.status-warn { color:var(--warn); font-weight:600; }
.level-LOW { color:var(--low); }
.level-MEDIUM, .level-MED { color:var(--med); }
.level-HIGH { color:var(--high); }
.level-CRITICAL { color:var(--crit); font-weight:700; }
.tag { display:inline-block; padding:2px 8px; border-radius:99px; font-size:11px;
       font-weight:600; text-transform:uppercase; letter-spacing:.04em; }
.tag.ok { background:rgba(102,187,106,.18); color:var(--ok); }
.tag.warn { background:rgba(255,167,38,.18); color:var(--warn); }
.tag.err { background:rgba(239,83,80,.18); color:var(--err); }
.toast { position:fixed; right:18px; top:18px; background:var(--panel2);
         border:1px solid var(--border); padding:10px 14px; border-radius:8px;
         font-size:13px; opacity:0; transition:opacity .2s; pointer-events:none; z-index:100; max-width:360px; }
.toast.show { opacity:1; }
.toast.err { border-color:var(--err); color:var(--err); }
.toast.ok { border-color:var(--ok); color:var(--ok); }
.drop-zone { border:2px dashed var(--border); border-radius:10px; padding:40px;
             text-align:center; color:var(--muted); transition:.2s; cursor:pointer; }
.drop-zone:hover, .drop-zone.dragover { border-color:var(--accent); color:var(--accent); background:rgba(79,195,247,.05); }
#detect-canvas { max-width:100%; background:#000; border-radius:8px; }
.gauge { position:relative; width:200px; height:200px; margin:0 auto; }
.gauge svg { transform:rotate(-90deg); }
.gauge .score { position:absolute; inset:0; display:flex; align-items:center;
                justify-content:center; font-size:42px; font-weight:700; }
#chart { width:100%; height:280px; background:#06091a; border-radius:8px;
         border:1px solid var(--border); }
.grid-2 { display:grid; grid-template-columns:1fr 1fr; gap:14px; }
@media (max-width:900px) { .grid-2 { grid-template-columns:1fr; } nav { margin-left:0; } }
pre.log { background:#06091a; padding:12px; border-radius:8px; overflow:auto;
          font-size:12px; max-height:380px; border:1px solid var(--border);
          font-family:ui-monospace,monospace; line-height:1.5; white-space:pre-wrap; }
pre.out { background:#06091a; padding:14px; border-radius:8px; overflow:auto;
          font-size:12px; max-height:520px; border:1px solid var(--border); }
.loading { display:inline-block; width:14px; height:14px; border:2px solid var(--accent);
           border-top-color:transparent; border-radius:50%; animation:spin .8s linear infinite; }
@keyframes spin { to { transform:rotate(360deg); } }
"""


def _layout(title: str, body: str, active: str = "") -> HTMLResponse:
    nav_items = [
        ("/", "dashboard"),
        ("/detect", "图像识别"),
        ("/forecast", "时序预测"),
        ("/comprehensive", "综合风险"),
        ("/anomaly", "异常检测"),
        ("/batch", "📤 批量上传"),
        ("/endpoints", "API 端点"),
    ]
    nav_html = "\n".join(
        f'<a href="{href}"{" class=\"active\"" if active == href else ""}>{label}</a>'
        for href, label in nav_items
    )
    html = f"""<!doctype html>
<html lang="zh-CN"><head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width,initial-scale=1" />
<title>{title} · DisasterGuard AI</title>
<style>{PAGE_CSS}</style>
</head><body>
<header>
  <h1>🌐 DisasterGuard AI · Web UI</h1>
  <span class="pill">standalone · port {WEB_PORT}</span>
  <nav>{nav_html}</nav>
</header>
<main>
{body}
</main>
<div class="toast" id="toast"></div>
<script>
function toast(msg, kind) {{
  const el = document.getElementById('toast');
  el.textContent = msg; el.className = 'toast show ' + (kind||'');
  setTimeout(() => el.className='toast', 2200);
}}
async function api(path, opts) {{
  const r = await fetch(path, opts||{{}});
  const t = await r.text();
  try {{ return {{ status:r.status, body:JSON.parse(t) }}; }}
  catch {{ return {{ status:r.status, body:t }}; }}
}}
function fmtSize(b) {{
  if (!b) return '';
  if (b > 1024*1024) return (b/1024/1024).toFixed(1)+' MB';
  if (b > 1024) return Math.round(b/1024)+' KB';
  return b+' B';
}}
function escapeHtml(s) {{
  return String(s==null?'':s).replace(/[&<>\"']/g, c => ({{'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}}[c]));
}}
function pretty(o) {{ return JSON.stringify(o, null, 2); }}
</script>
</body></html>"""
    return HTMLResponse(html)


# ──────────────────────────────── Dashboard ────────────────────────────

DASHBOARD_BODY = r"""
<div class="card-row">
  <div class="card">
    <h2>① 服务健康</h2>
    <div class="kv"><span class="k">Web UI</span><span class="v status-up" id="web-status">运行中</span></div>
    <div class="kv"><span class="k">FastAPI (8001)</span><span class="v" id="api-status">—</span></div>
    <div class="kv"><span class="k">模块</span><span class="v" id="api-modules">—</span></div>
    <div class="kv"><span class="k">查询时间</span><span class="v" id="query-time">—</span></div>
  </div>
  <div class="card">
    <h2>② 进程信息</h2>
    <div class="kv"><span class="k">ai-service 根</span><span class="v" id="ai-root">__AI_ROOT__</span></div>
    <div class="kv"><span class="k">Web UI PID</span><span class="v" id="web-pid">__WEB_PID__</span></div>
    <div class="kv"><span class="k">启动时间</span><span class="v" id="boot-time">__BOOT_TIME__</span></div>
    <div class="kv"><span class="k">Python</span><span class="v">__PY_VER__</span></div>
  </div>
</div>

<div class="card">
  <h2>③ 模型加载状态</h2>
  <div id="models">加载中…</div>
</div>

<div class="grid-2">
  <div class="card">
    <h2>④ 实时日志（尾部）</h2>
    <pre class="log" id="log-tail">加载中…</pre>
    <div style="margin-top:8px; display:flex; gap:8px;">
      <button class="btn-ghost" onclick="toggleLog()">⏯ 自动刷新</button>
      <button class="btn-ghost" onclick="loadLogs()">↻ 立即刷新</button>
    </div>
  </div>
  <div class="card">
    <h2>⑤ 端点汇总</h2>
    <table>
      <thead><tr><th>方法</th><th>路径</th><th>状态</th></tr></thead>
      <tbody id="endpoints">
        <tr><td colspan="3">检测中…</td></tr>
      </tbody>
    </table>
  </div>
</div>

<script>
let logTimer = null;

async function loadHealth() {{
  const h = await api('/_internal/health');
  if (h.status === 200) {{
    document.getElementById('api-status').innerHTML =
      h.body.api_up
        ? '<span class="status-up">UP</span>'
        : '<span class="status-down">DOWN</span>';
    document.getElementById('api-modules').textContent =
      Object.keys(h.body.unregistered_routers || {}).length === 0
        ? '全部已注册' : JSON.stringify(h.body.unregistered_routers);
  }} else {{
    document.getElementById('api-status').innerHTML = '<span class="status-down">未启动</span>';
    document.getElementById('api-modules').textContent = '—';
  }}
  document.getElementById('query-time').textContent = new Date().toLocaleTimeString();
}}

async function loadModels() {{
  const m = await api('/_internal/models');
  const div = document.getElementById('models');
  if (m.status !== 200) {{ div.innerHTML = '<div class="row"><span class="dot err"></span> '+m.status+'</div>'; return; }}
  const items = m.body.models || [];
  div.innerHTML = items.map(it => `
    <div class="row">
      <span class="dot ${{it.exists_on_disk?'ok':'err'}}"></span>
      <span class="name">${{escapeHtml(it.name)}}</span>
      <span class="size">${{fmtSize(it.size_bytes)}}${{it.trained_at?' · '+new Date(it.trained_at).toLocaleString():''}}</span>
    </div>`).join('');
}}

async function loadLogs() {{
  const r = await api('/_internal/logs?lines=40');
  document.getElementById('log-tail').textContent = r.body || '（无日志）';
}}

function toggleLog() {{
  if (logTimer) {{ clearInterval(logTimer); logTimer = null; return; }}
  logTimer = setInterval(loadLogs, 3000);
}}

async function loadEndpoints() {{
  const e = await api('/_internal/endpoints');
  const tb = document.getElementById('endpoints');
  tb.innerHTML = e.body.map(x => `
    <tr>
      <td><span class="tag ${{x.method==='GET'?'ok':'warn'}}">${{x.method}}</span></td>
      <td><code>${{escapeHtml(x.path)}}</code></td>
      <td>${{x.api_only?'仅 API':(x.web_only?'仅 Web':'双端')}}</td>
    </tr>`).join('');
}}

loadHealth(); loadModels(); loadLogs(); loadEndpoints();
setInterval(() => {{ loadHealth(); loadModels(); }}, 15000);
</script>
"""

# ──────────────────────────────── Detect ──────────────────────────────

DETECT_BODY = r"""
<div class="card">
  <h2>YOLO 图像识别</h2>
  <p style="color:var(--muted);margin-top:0">
    上传裂缝/落石/滑坡遥感或近景图像，YOLO 模型将返回检测到的目标类别、置信度、边界框。
    支持 JPEG/PNG/WebP，最大 8 MB。
  </p>
  <div class="drop-zone" id="drop">
    <div>📁 拖拽图片到这里，或点击选择</div>
    <div style="font-size:12px;margin-top:8px">建议分辨率 ≥ 640×640；模型在 COCO 80 类与业务 5 类之间自动判断</div>
    <input type="file" id="file" accept="image/*" style="display:none" />
  </div>
  <div style="margin-top:12px;display:flex;gap:8px;flex-wrap:wrap;">
    <label style="margin:0;display:flex;align-items:center;gap:6px;">
      <input type="checkbox" id="use-api" checked /> 优先调 FastAPI（8001）；失败时本地推理
    </label>
    <button id="run" disabled>▶ 开始识别</button>
    <button class="btn-ghost" id="clear">清空</button>
  </div>
</div>

<div class="card" id="result-card" style="display:none">
  <h2>识别结果</h2>
  <div class="grid-2">
    <div>
      <canvas id="detect-canvas"></canvas>
    </div>
    <div>
      <div class="kv"><span class="k">推理引擎</span><span class="v" id="engine">—</span></div>
      <div class="kv"><span class="k">模型变体</span><span class="v" id="variant">—</span></div>
      <div class="kv"><span class="k">业务模型</span><span class="v" id="biz">—</span></div>
      <div class="kv"><span class="k">推理耗时</span><span class="v" id="ms">—</span></div>
      <div class="kv"><span class="k">检测数</span><span class="v" id="count">—</span></div>
      <table id="det-table" style="margin-top:12px"></table>
    </div>
  </div>
  <pre class="out" id="raw" style="margin-top:12px"></pre>
</div>

<script>
const drop = document.getElementById('drop');
const fileInput = document.getElementById('file');
const runBtn = document.getElementById('run');
const clearBtn = document.getElementById('clear');
let selected = null;

drop.addEventListener('click', () => fileInput.click());
drop.addEventListener('dragover', e => {{ e.preventDefault(); drop.classList.add('dragover'); }});
drop.addEventListener('dragleave', () => drop.classList.remove('dragover'));
drop.addEventListener('drop', e => {{
  e.preventDefault(); drop.classList.remove('dragover');
  if (e.dataTransfer.files[0]) onFile(e.dataTransfer.files[0]);
}});
fileInput.addEventListener('change', () => {{ if (fileInput.files[0]) onFile(fileInput.files[0]); }});

function onFile(f) {{
  selected = f;
  runBtn.disabled = false;
  drop.innerHTML = `<div>✓ 已选择 <b>${{escapeHtml(f.name)}}</b> · ${{fmtSize(f.size)}}</div>
    <div style="font-size:12px;margin-top:8px">点击可重新选择</div>`;
}}
clearBtn.onclick = () => {{
  selected = null; fileInput.value = '';
  runBtn.disabled = true;
  document.getElementById('result-card').style.display = 'none';
  drop.innerHTML = '<div>📁 拖拽图片到这里，或点击选择</div><div style="font-size:12px;margin-top:8px">建议分辨率 ≥ 640×640</div>';
}};

runBtn.onclick = async () => {{
  if (!selected) return;
  runBtn.disabled = true; runBtn.innerHTML = '<span class="loading"></span> 识别中…';
  const fd = new FormData(); fd.append('file', selected);
  fd.append('use_api', document.getElementById('use-api').checked ? '1' : '0');
  const r = await fetch('/detect/run', {{ method:'POST', body: fd }});
  const j = await r.json();
  runBtn.disabled = false; runBtn.innerHTML = '▶ 开始识别';
  if (!r.ok) {{ toast('识别失败: '+(j.detail||r.status), 'err'); return; }}
  renderDetect(j);
}};

function renderDetect(j) {{
  document.getElementById('result-card').style.display = 'block';
  document.getElementById('engine').textContent = j.engine || '—';
  document.getElementById('variant').textContent = j.variant || '—';
  document.getElementById('biz').textContent = j.is_business_model ? '是' : '否（COCO 通用）';
  document.getElementById('ms').textContent = (j.inference_ms||0).toFixed(1)+' ms';
  document.getElementById('count').textContent = (j.detections||[]).length;
  const tb = document.getElementById('det-table');
  tb.innerHTML = '<thead><tr><th>#</th><th>类别</th><th>置信度</th><th>边界框 (x1,y1,x2,y2)</th></tr></thead><tbody>'
    + (j.detections||[]).map((d,i) =>
        `<tr><td>${{i+1}}</td><td>${{escapeHtml(d.class_name||d.label||'?')}}</td>
         <td>${{(d.confidence*100||d.score*100||0).toFixed(1)}}%</td>
         <td><code>${{JSON.stringify(d.bbox||d.box)}}</code></td></tr>`).join('')
    + '</tbody>';
  document.getElementById('raw').textContent = pretty(j);

  // 画 bbox
  const img = new Image();
  img.onload = () => {{
    const c = document.getElementById('detect-canvas');
    c.width = img.width; c.height = img.height;
    const ctx = c.getContext('2d');
    ctx.drawImage(img, 0, 0);
    const palette = ['#4fc3f7','#66bb6a','#ffa726','#ff7043','#ab47bc','#26c6da','#d4e157','#ec407a'];
    (j.detections||[]).forEach((d,i) => {{
      const b = d.bbox || d.box || [];
      if (b.length < 4) return;
      const color = palette[i % palette.length];
      ctx.strokeStyle = color; ctx.lineWidth = 3;
      ctx.strokeRect(b[0], b[1], b[2]-b[0], b[3]-b[1]);
      const label = (d.class_name||d.label||'?') + ' ' + ((d.confidence||d.score||0)*100).toFixed(0)+'%';
      ctx.font = 'bold 16px sans-serif';
      const w = ctx.measureText(label).width + 8;
      ctx.fillStyle = color;
      ctx.fillRect(b[0], b[1]-22, w, 22);
      ctx.fillStyle = '#000';
      ctx.fillText(label, b[0]+4, b[1]-6);
    }});
  }};
  img.src = j.image_data_url || URL.createObjectURL(selected);
}}
</script>
"""

# ──────────────────────────────── Forecast ─────────────────────────────

FORECAST_BODY = r"""
<div class="card">
  <h2>LSTM 位移预测</h2>
  <p style="color:var(--muted);margin-top:0">
    输入历史位移时间序列（mm），LSTM 模型预测未来 N 步趋势。
    序列长度建议 ≥ 8，自动归一化。
  </p>
  <div class="grid-2">
    <div>
      <label>历史序列（逗号或换行分隔，单位 mm）</label>
      <textarea id="history" rows="5">1.0, 1.5, 2.0, 2.8, 3.5, 4.5, 5.8, 7.5</textarea>
      <label>预测步数 (horizon)</label>
      <input type="number" id="horizon" value="5" min="1" max="20" />
      <label>设备 ID（可留空）</label>
      <input type="text" id="device" value="WEB_TEST" />
      <div style="margin-top:14px;display:flex;gap:8px;">
        <button id="run">▶ 预测</button>
        <button class="btn-ghost" onclick="loadSample('accelerating')">📈 加速趋势</button>
        <button class="btn-ghost" onclick="loadSample('stable')">📊 平稳</button>
        <button class="btn-ghost" onclick="loadSample('oscillating')">〰️ 振荡</button>
      </div>
    </div>
    <div>
      <canvas id="chart"></canvas>
    </div>
  </div>
</div>

<div class="card" id="result-card" style="display:none">
  <h2>预测结果</h2>
  <div class="grid-2">
    <div>
      <div class="kv"><span class="k">推理引擎</span><span class="v" id="engine">—</span></div>
      <div class="kv"><span class="k">预测值</span><span class="v" id="preds">—</span></div>
      <div class="kv"><span class="k">置信度</span><span class="v" id="conf">—</span></div>
      <div class="kv"><span class="k">推理耗时</span><span class="v" id="ms">—</span></div>
    </div>
    <div>
      <table id="pred-table"></table>
    </div>
  </div>
  <pre class="out" id="raw" style="margin-top:12px"></pre>
</div>

<script>
function loadSample(kind) {{
  if (kind === 'accelerating')
    document.getElementById('history').value = '1.0, 1.2, 1.5, 1.9, 2.5, 3.3, 4.4, 5.8, 7.6, 10.0';
  else if (kind === 'stable')
    document.getElementById('history').value = '5.0, 5.1, 4.9, 5.2, 5.0, 4.8, 5.1, 5.0, 5.1, 4.9';
  else
    document.getElementById('history').value = '3.0, 3.5, 3.2, 4.0, 3.7, 4.3, 4.0, 4.5, 4.2, 4.8';
}}

document.getElementById('run').onclick = async () => {{
  const hist = document.getElementById('history').value.split(/[,\n\s]+/).map(parseFloat).filter(x => !isNaN(x));
  const horizon = parseInt(document.getElementById('horizon').value, 10);
  const device = document.getElementById('device').value;
  if (hist.length < 3) {{ toast('序列太短（≥3）','err'); return; }}
  const btn = document.getElementById('run');
  btn.disabled = true; btn.innerHTML = '<span class="loading"></span> 预测中…';
  const r = await fetch('/forecast/run', {{
    method:'POST', headers:{{'Content-Type':'application/json'}},
    body: JSON.stringify({{ history: hist, horizon, device_code: device }})
  }});
  const j = await r.json();
  btn.disabled = false; btn.innerHTML = '▶ 预测';
  if (!r.ok) {{ toast('预测失败: '+(j.detail||r.status),'err'); return; }}
  renderForecast(hist, j);
}};

function renderForecast(hist, j) {{
  document.getElementById('result-card').style.display = 'block';
  document.getElementById('engine').textContent = j.engine || '—';
  const preds = j.predictions || [];
  document.getElementById('preds').textContent = preds.map(v => v.toFixed(2)).join(', ');
  document.getElementById('conf').textContent = ((j.confidence||0)*100).toFixed(0)+'%';
  document.getElementById('ms').textContent = (j.inference_ms||0).toFixed(1)+' ms';
  const tb = document.getElementById('pred-table');
  tb.innerHTML = '<thead><tr><th>步</th><th>预测值 (mm)</th><th>变化 (Δ)</th></tr></thead><tbody>'
    + preds.map((v,i) => `<tr><td>+${{i+1}}</td><td>${{v.toFixed(3)}}</td>
       <td>${{(i===0?(v-hist[hist.length-1]):(v-preds[i-1])).toFixed(3)}}</td></tr>`).join('')
    + '</tbody>';
  document.getElementById('raw').textContent = pretty(j);
  drawChart(hist, preds);
}}

function drawChart(hist, preds) {{
  const c = document.getElementById('chart');
  const ctx = c.getContext('2d');
  const W = c.width = c.clientWidth; const H = c.height = c.clientHeight;
  ctx.fillStyle = '#06091a'; ctx.fillRect(0,0,W,H);
  const all = hist.concat(preds);
  const min = Math.min(...all) * 0.95;
  const max = Math.max(...all) * 1.05;
  const pad = 40;
  const xStep = (W - 2*pad) / Math.max(1, all.length - 1);
  const yScale = v => H - pad - (v - min) / (max - min || 1) * (H - 2*pad);
  // 网格
  ctx.strokeStyle = '#1b2340'; ctx.lineWidth = 1;
  for (let i=0;i<5;i++) {{
    const y = pad + (H-2*pad) * i/4;
    ctx.beginPath(); ctx.moveTo(pad,y); ctx.lineTo(W-pad,y); ctx.stroke();
    ctx.fillStyle = '#8b94b0'; ctx.font = '10px monospace';
    ctx.fillText((max - (max-min)*i/4).toFixed(1), 4, y+3);
  }}
  // 历史
  ctx.strokeStyle = '#4fc3f7'; ctx.lineWidth = 2;
  ctx.beginPath();
  hist.forEach((v,i) => {{ const x = pad + i*xStep; const y = yScale(v);
    i===0?ctx.moveTo(x,y):ctx.lineTo(x,y); }});
  ctx.stroke();
  hist.forEach((v,i) => {{
    ctx.fillStyle = '#4fc3f7'; ctx.beginPath();
    ctx.arc(pad + i*xStep, yScale(v), 3, 0, Math.PI*2); ctx.fill();
  }});
  // 预测
  ctx.strokeStyle = '#ffa726'; ctx.setLineDash([4,3]);
  ctx.beginPath();
  const last = hist[hist.length-1];
  ctx.moveTo(pad + (hist.length-1)*xStep, yScale(last));
  preds.forEach((v,i) => {{
    ctx.lineTo(pad + (hist.length+i)*xStep, yScale(v));
  }});
  ctx.stroke(); ctx.setLineDash([]);
  preds.forEach((v,i) => {{
    ctx.fillStyle = '#ffa726'; ctx.beginPath();
    ctx.arc(pad + (hist.length+i)*xStep, yScale(v), 4, 0, Math.PI*2); ctx.fill();
  }});
  // 分隔线
  ctx.strokeStyle = '#66bb6a'; ctx.lineWidth = 1; ctx.setLineDash([2,2]);
  const sx = pad + (hist.length-1)*xStep;
  ctx.beginPath(); ctx.moveTo(sx, pad); ctx.lineTo(sx, H-pad); ctx.stroke();
  ctx.setLineDash([]);
  // legend
  ctx.fillStyle = '#4fc3f7'; ctx.fillRect(pad, H-pad+6, 12, 8);
  ctx.fillStyle = '#e6edf3'; ctx.fillText('history', pad+18, H-pad+14);
  ctx.fillStyle = '#ffa726'; ctx.fillRect(pad+80, H-pad+6, 12, 8);
  ctx.fillStyle = '#e6edf3'; ctx.fillText('forecast', pad+98, H-pad+14);
}}
</script>
"""

# ──────────────────────────────── Comprehensive ────────────────────────

COMPREHENSIVE_BODY = r"""
<div class="card">
  <h2>综合风险分析（XGBoost）</h2>
  <p style="color:var(--muted);margin-top:0">
    调节 36 维多源特征，查看 XGBoost 模型对综合风险评分的影响。包含气象/地形/地质/历史/承灾体/传感器 6 大维度。
  </p>
  <div class="grid-2">
    <div>
      <h2>特征输入</h2>
      <div class="grid-2">
        <div>
          <label>24h 累计降雨 (mm)</label>
          <input type="number" id="rainfall_24h" value="60" min="0" step="5" />
          <label>7d 累计降雨 (mm)</label>
          <input type="number" id="rainfall_7d" value="180" min="0" step="10" />
          <label>土壤湿度 (0-1)</label>
          <input type="number" id="soil_moisture" value="0.45" min="0" max="1" step="0.05" />
          <label>坡度 (°)</label>
          <input type="number" id="slope_deg" value="30" min="0" max="90" />
          <label>植被覆盖 (0-1)</label>
          <input type="number" id="vegetation_cover" value="0.3" min="0" max="1" step="0.05" />
          <label>土壤等级 (1-5)</label>
          <input type="number" id="soil_grade" value="3" min="1" max="5" />
        </div>
        <div>
          <label>位移速率 (mm/d)</label>
          <input type="number" id="displacement_rate" value="8" min="0" step="0.5" />
          <label>累计位移 (mm)</label>
          <input type="number" id="displacement_total" value="35" min="0" step="1" />
          <label>裂缝宽度 (mm)</label>
          <input type="number" id="crack_width" value="15" min="0" step="1" />
          <label>地震震级</label>
          <input type="number" id="earthquake_mag" value="3.5" min="0" max="9" step="0.1" />
          <label>历史灾害次数</label>
          <input type="number" id="historical_count" value="3" min="0" />
          <label>图像异常分 (0-100)</label>
          <input type="number" id="image_anomaly_score" value="40" min="0" max="100" />
        </div>
      </div>
      <div style="margin-top:14px">
        <label>灾害 ID（可留空）</label>
        <input type="text" id="hazard" value="WEB_TEST" />
      </div>
      <div style="margin-top:14px;display:flex;gap:8px;">
        <button id="run">▶ 评估</button>
        <button class="btn-ghost" onclick="loadPreset('critical')">⚠️ 临界场景</button>
        <button class="btn-ghost" onclick="loadPreset('safe')">✅ 安全场景</button>
        <button class="btn-ghost" onclick="loadPreset('medium')">⚡ 中等场景</button>
      </div>
    </div>
    <div>
      <h2>风险仪表盘</h2>
      <div class="gauge">
        <svg width="200" height="200">
          <circle cx="100" cy="100" r="80" fill="none" stroke="#243054" stroke-width="14" />
          <circle id="gauge-fg" cx="100" cy="100" r="80" fill="none" stroke="var(--ok)"
                  stroke-width="14" stroke-dasharray="502" stroke-dashoffset="502"
                  stroke-linecap="round" />
        </svg>
        <div class="score" id="score">—</div>
      </div>
      <div style="text-align:center;margin-top:10px">
        <span id="level-tag" class="tag ok">—</span>
        <span style="color:var(--muted);margin-left:8px" id="conf"></span>
      </div>
      <table id="dim-table" style="margin-top:14px"></table>
    </div>
  </div>
</div>

<div class="card" id="result-card" style="display:none">
  <h2>推理详情</h2>
  <pre class="out" id="raw"></pre>
</div>

<script>
function loadPreset(kind) {{
  const presets = {{
    critical:  {{rainfall_24h:120,rainfall_7d:340,soil_moisture:.65,slope_deg:42,
                  vegetation_cover:.15,soil_grade:5,displacement_rate:25,displacement_total:120,
                  crack_width:45,earthquake_mag:5.5,historical_count:6,image_anomaly_score:80}},
    safe:      {{rainfall_24h:5,rainfall_7d:25,soil_moisture:.20,slope_deg:12,
                  vegetation_cover:.7,soil_grade:2,displacement_rate:.5,displacement_total:3,
                  crack_width:2,earthquake_mag:0,historical_count:0,image_anomaly_score:8}},
    medium:    {{rainfall_24h:45,rainfall_7d:120,soil_moisture:.40,slope_deg:25,
                  vegetation_cover:.4,soil_grade:3,displacement_rate:5,displacement_total:18,
                  crack_width:10,earthquake_mag:2.5,historical_count:2,image_anomaly_score:35}},
  }};
  const p = presets[kind];
  for (const k in p) document.getElementById(k).value = p[k];
}}

document.getElementById('run').onclick = async () => {{
  const fields = ['rainfall_24h','rainfall_7d','soil_moisture','slope_deg','vegetation_cover',
                  'soil_grade','displacement_rate','displacement_total','crack_width',
                  'earthquake_mag','historical_count','image_anomaly_score'];
  const factors = {{}};
  for (const f of fields) factors[f] = parseFloat(document.getElementById(f).value);
  factors.hazard_code = document.getElementById('hazard').value;
  const btn = document.getElementById('run');
  btn.disabled = true; btn.innerHTML = '<span class="loading"></span> 评估中…';
  const r = await fetch('/comprehensive/run', {{
    method:'POST', headers:{{'Content-Type':'application/json'}}, body: JSON.stringify({{factors}})
  }});
  const j = await r.json();
  btn.disabled = false; btn.innerHTML = '▶ 评估';
  if (!r.ok) {{ toast('评估失败: '+(j.detail||r.status),'err'); return; }}
  renderComp(j);
}};

function renderComp(j) {{
  document.getElementById('result-card').style.display = 'block';
  document.getElementById('raw').textContent = pretty(j);
  const overall = j.overall || {{}};
  const score = overall.risk_score || 0;
  const level = (overall.level || 'LOW').toUpperCase();
  document.getElementById('score').textContent = score.toFixed(1);
  document.getElementById('conf').textContent = '置信度 ' + ((overall.confidence||0)*100).toFixed(0)+'%';
  const tag = document.getElementById('level-tag');
  tag.className = 'tag level-'+level;
  tag.textContent = level;
  // 仪表盘
  const fg = document.getElementById('gauge-fg');
  const offset = 502 - (502 * Math.min(score, 100) / 100);
  fg.setAttribute('stroke-dashoffset', offset);
  fg.style.stroke = level==='CRITICAL' ? 'var(--critical)' : level==='HIGH' ? 'var(--high)' :
                    level==='MEDIUM' ? 'var(--med)' : 'var(--ok)';
  // 维度表
  const dims = j.dimensions || {{}};
  const tb = document.getElementById('dim-table');
  tb.innerHTML = '<thead><tr><th>维度</th><th>得分</th><th>等级</th></tr></thead><tbody>'
    + Object.entries(dims).map(([k,v]) => {{
        const s = (typeof v === 'object' ? (v.score||0) : v) || 0;
        const l = (typeof v === 'object' ? (v.level||'') : '').toUpperCase();
        return `<tr><td>${{escapeHtml(k)}}</td><td>${{s.toFixed(1)}}</td>
                <td class="level-${{l||'LOW'}}">${{l||'—'}}</td></tr>`;
      }}).join('')
    + '</tbody>';
}}
</script>
"""

# ──────────────────────────────── Anomaly ──────────────────────────────

ANOMALY_BODY = r"""
<div class="card">
  <h2>异常检测（Isolation Forest）</h2>
  <p style="color:var(--muted);margin-top:0">
    每行是一个采样点，5 列：位移速率(mm/d) / 裂缝宽度(mm) / 倾斜角(°) / 降雨(mm/h) / 振动频率(Hz)。
    复制 Excel 数据粘贴到文本框即可。
  </p>
  <div class="grid-2">
    <div>
      <label>采样数据（每行 5 个数字，逗号或制表符分隔）</label>
      <textarea id="data" rows="10">1.0, 1.0, 0.5, 2.0, 1.0
1.2, 1.1, 0.6, 2.1, 1.1
25.0, 35.0, 8.0, 100.0, 30.0
1.1, 1.0, 0.5, 2.2, 1.0
1.0, 1.2, 0.4, 2.0, 1.0
22.0, 30.0, 7.0, 95.0, 28.0
1.0, 1.0, 0.5, 2.0, 1.0</textarea>
      <label>设备 ID</label>
      <input type="text" id="device" value="WEB_TEST" />
      <div style="margin-top:14px;display:flex;gap:8px;">
        <button id="run">▶ 检测</button>
        <button class="btn-ghost" onclick="loadNormal()">常规样本</button>
        <button class="btn-ghost" onclick="loadMixed()">混合异常</button>
      </div>
    </div>
    <div>
      <table id="anomaly-table">
        <thead><tr><th>#</th><th>异常</th><th>得分</th><th>原始数据</th></tr></thead>
        <tbody><tr><td colspan="4">点击检测查看结果</td></tr></tbody>
      </table>
    </div>
  </div>
</div>

<div class="card" id="raw-card" style="display:none">
  <h2>原始响应</h2>
  <pre class="out" id="raw"></pre>
</div>

<script>
function loadNormal() {{
  document.getElementById('data').value =
    '1.0, 1.0, 0.5, 2.0, 1.0\n1.2, 1.1, 0.6, 2.1, 1.1\n' +
    '1.1, 1.0, 0.5, 2.2, 1.0\n1.0, 1.2, 0.4, 2.0, 1.0\n' +
    '1.0, 1.0, 0.5, 2.0, 1.0\n1.2, 1.1, 0.5, 2.1, 1.1\n' +
    '1.0, 1.0, 0.5, 2.0, 1.0\n1.1, 1.0, 0.5, 2.2, 1.0';
}}
function loadMixed() {{
  document.getElementById('data').value =
    '1.0, 1.0, 0.5, 2.0, 1.0\n1.2, 1.1, 0.6, 2.1, 1.1\n' +
    '25.0, 35.0, 8.0, 100.0, 30.0\n' +  // 异常
    '1.1, 1.0, 0.5, 2.2, 1.0\n' +
    '22.0, 30.0, 7.0, 95.0, 28.0\n' +   // 异常
    '1.0, 1.0, 0.5, 2.0, 1.0';
}}

document.getElementById('run').onclick = async () => {{
  const lines = document.getElementById('data').value.split('\n').map(l => l.trim()).filter(Boolean);
  const data = lines.map(l => l.split(/[,\t\s]+/).map(parseFloat).filter(x => !isNaN(x)));
  if (data.some(r => r.length !== 5)) {{ toast('每行必须恰好 5 个数字','err'); return; }}
  const btn = document.getElementById('run');
  btn.disabled = true; btn.innerHTML = '<span class="loading"></span> 检测中…';
  const r = await fetch('/anomaly/run', {{
    method:'POST', headers:{{'Content-Type':'application/json'}},
    body: JSON.stringify({{ device_code: document.getElementById('device').value, data }})
  }});
  const j = await r.json();
  btn.disabled = false; btn.innerHTML = '▶ 检测';
  if (!r.ok) {{ toast('检测失败: '+(j.detail||r.status),'err'); return; }}
  renderAnomaly(data, j);
}};

function renderAnomaly(data, j) {{
  document.getElementById('raw-card').style.display = 'block';
  document.getElementById('raw').textContent = pretty(j);
  const scores = j.scores || [];
  const flags = j.anomaly_flags || scores.map(s => s < (j.threshold||0));
  const tb = document.getElementById('anomaly-table');
  tb.innerHTML = '<thead><tr><th>#</th><th>异常</th><th>得分</th><th>原始数据</th></tr></thead><tbody>'
    + data.map((row, i) => {{
        const isAnom = flags[i];
        return `<tr><td>${{i+1}}</td>
          <td>${{isAnom?'<span class="tag err">异常</span>':'<span class="tag ok">正常</span>'}}</td>
          <td>${{(scores[i]||0).toFixed(3)}}</td>
          <td><code>${{row.join(', ')}}</code></td></tr>`;
      }}).join('')
    + '</tbody>';
}}
</script>
"""

# ──────────────────────────────── Batch Upload ──────────────────────

BATCH_BODY = r"""
<div class="card">
  <h2>📤 批量上传 & 预测</h2>
  <p style="color:var(--muted);margin-top:0">
    上传 CSV / Excel / JSON 文件做批量推理。每个任务都有模板可下载，
    列名不严格匹配（按关键词模糊匹配），缺失列会立刻报错。
  </p>
  <div class="card-row">
    <div class="card" style="margin:0">
      <h2>① 选任务</h2>
      <div id="task-grid">加载中…</div>
    </div>
    <div class="card" style="margin:0">
      <h2>② 任务说明</h2>
      <div id="task-info" style="color:var(--muted)">点击左侧任务卡片查看说明</div>
    </div>
  </div>
</div>

<div class="card">
  <h2>③ 上传文件</h2>
  <div style="display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin-bottom:10px">
    <button class="btn-ghost" id="download-template">⬇️ 下载模板</button>
    <span id="format-hint" style="color:var(--muted);font-size:12px"></span>
  </div>
  <div class="drop-zone" id="drop">
    <div>📁 拖拽文件到这里，或点击选择</div>
    <div style="font-size:12px;margin-top:8px">支持 .csv / .tsv / .json / .xlsx / .txt，最大 16 MB</div>
    <input type="file" id="file" style="display:none" />
  </div>
  <div style="margin-top:12px;display:flex;gap:8px;flex-wrap:wrap;align-items:center">
    <div id="extra-options"></div>
    <button id="parse" disabled>📋 解析预览</button>
    <button id="run" disabled style="display:none">▶ 预测</button>
    <button class="btn-ghost" id="clear">清空</button>
  </div>
</div>

<div class="card" id="preview-card" style="display:none">
  <h2>④ 数据预览（前 20 行）</h2>
  <div style="max-height:280px;overflow:auto">
    <table id="preview-table"></table>
  </div>
  <div style="margin-top:8px;color:var(--muted);font-size:12px" id="preview-meta"></div>
</div>

<div class="card" id="result-card" style="display:none">
  <h2>⑤ 推理结果</h2>
  <div id="result-summary" class="card-row"></div>
  <div style="margin-top:14px;display:flex;gap:8px;flex-wrap:wrap;align-items:center">
    <button class="btn-ghost" id="download-result">⬇️ 下载结果 JSON</button>
    <button class="btn-ghost" id="download-result-csv">⬇️ 下载结果 CSV</button>
    <span style="color:var(--muted);font-size:12px" id="result-meta"></span>
  </div>
  <div id="result-body" style="margin-top:12px"></div>
</div>

<script>
let templates = {};
let currentTask = null;
let parsed = null;
let lastResult = null;
let extraOptions = {};

async function loadTemplates() {{
  const r = await api('/batch/templates');
  templates = Object.fromEntries((r.body.templates || []).map(t => [t.task, t]));
  const grid = document.getElementById('task-grid');
  grid.innerHTML = (r.body.templates || []).map(t => `
    <div class="task-card" data-task="${{t.task}}"
         style="padding:10px;border:1px solid var(--border);border-radius:8px;cursor:pointer;
                margin-bottom:6px;transition:all .15s"
         onmouseover="this.style.borderColor='var(--accent)'"
         onmouseout="if(!this.classList.contains('selected'))this.style.borderColor='var(--border)'">
      <div style="font-weight:600">${{escapeHtml(t.name)}}</div>
      <div style="color:var(--muted);font-size:11px">.${{t.format}} · ${{t.columns.length||'文本'}}列</div>
    </div>`).join('');
  grid.querySelectorAll('.task-card').forEach(card => {{
    card.onclick = () => selectTask(card.dataset.task);
  }});
  // 默认选 forecast
  selectTask('forecast');
}}

function selectTask(task) {{
  currentTask = task;
  // 重置 file input 属性
  fileInput.removeAttribute('multiple');
  fileInput.setAttribute('accept', currentTask === 'image-batch' ? 'image/*' : '.csv,.tsv,.tab,.json,.xlsx,.xls,.txt');
  selected = null; selectedMulti = null;
  document.getElementById('parse').disabled = true;
  document.getElementById('parse').style.display = '';
  document.getElementById('run').style.display = 'none';
  document.getElementById('run').disabled = true;
  document.getElementById('preview-card').style.display = 'none';
  document.getElementById('result-card').style.display = 'none';
  document.querySelectorAll('.task-card').forEach(c => {{
    if (c.dataset.task === task) {{
      c.classList.add('selected');
      c.style.borderColor = 'var(--accent)';
      c.style.background = 'rgba(79,195,247,.08)';
    }} else {{
      c.classList.remove('selected');
      c.style.borderColor = 'var(--border)';
      c.style.background = '';
    }}
  }});
  const t = templates[task];
  if (!t) return;
  document.getElementById('task-info').innerHTML = `
    <div style="color:var(--fg);font-weight:600;margin-bottom:6px">${{escapeHtml(t.name)}}</div>
    <div style="margin-bottom:8px">${{escapeHtml(t.description)}}</div>
    <div style="font-size:12px"><b>必需列：</b> <code>${{escapeHtml(t.columns.join(', '))||'(纯文本每行一条 / 多张图片)'}}</code></div>
    <div style="font-size:12px;margin-top:4px"><b>文件格式：</b> .${{t.format}}</div>`;
  document.getElementById('format-hint').textContent = `本任务：.${{t.format}} · 列名支持模糊匹配`;

  // 任务专属选项
  const ext = document.getElementById('extra-options');
  if (currentTask === 'image-batch') {{
    ext.innerHTML = '<span style="color:var(--muted);font-size:12px">支持同时拖拽多张图片</span>';
  }} else if (currentTask === 'forecast') {{
    ext.innerHTML = '<label style="display:inline">预测步数 <input type="number" id="opt-horizon" value="7" min="1" max="30" style="width:60px;display:inline-block;margin-left:6px"></label>';
    extraOptions.horizon = 7;
    document.getElementById('opt-horizon').oninput = e => extraOptions.horizon = parseInt(e.target.value, 10) || 7;
  }} else if (currentTask === 'comprehensive') {{
    ext.innerHTML = '<label style="display:inline">区域前缀 <input type="text" id="opt-prefix" value="BATCH" style="width:120px;display:inline-block;margin-left:6px"></label>';
    extraOptions.region_prefix = 'BATCH';
    document.getElementById('opt-prefix').oninput = e => extraOptions.region_prefix = e.target.value || 'BATCH';
  }} else {{
    ext.innerHTML = '';
  }}
}}

// 文件上传
const drop = document.getElementById('drop');
const fileInput = document.getElementById('file');
let selected = null;
let selectedMulti = null;  // image-batch 用
drop.onclick = () => fileInput.click();
drop.addEventListener('dragover', e => {{ e.preventDefault(); drop.classList.add('dragover'); }});
drop.addEventListener('dragleave', () => drop.classList.remove('dragover'));
drop.addEventListener('drop', e => {{
  e.preventDefault(); drop.classList.remove('dragover');
  const files = Array.from(e.dataTransfer.files || []);
  if (files.length === 0) return;
  if (currentTask === 'image-batch' && files.length > 0) {{
    onMultiFile(files);
  }} else {{
    onFile(files[0]);
  }}
}});
fileInput.addEventListener('change', () => {{
  const files = Array.from(fileInput.files || []);
  if (currentTask === 'image-batch' && files.length > 0) {{
    onMultiFile(files);
  }} else if (files.length > 0) {{
    onFile(files[0]);
  }}
}});

function onFile(f) {{
  selected = f;
  selectedMulti = null;
  document.getElementById('parse').disabled = false;
  document.getElementById('run').style.display = 'none';
  document.getElementById('preview-card').style.display = 'none';
  document.getElementById('result-card').style.display = 'none';
  drop.innerHTML = `<div>✓ <b>${{escapeHtml(f.name)}}</b> · ${{fmtSize(f.size)}}</div>
    <div style="font-size:12px;margin-top:8px">点击可重新选择</div>`;
}}

function onMultiFile(files) {{
  selected = null;
  selectedMulti = files;
  document.getElementById('parse').disabled = true;
  document.getElementById('parse').style.display = 'none';
  document.getElementById('run').style.display = '';
  document.getElementById('run').disabled = false;
  document.getElementById('preview-card').style.display = 'none';
  document.getElementById('result-card').style.display = 'none';
  const totalSize = files.reduce((a,f) => a + f.size, 0);
  drop.innerHTML = `<div>✓ 已选择 <b>${{files.length}}</b> 张图片 · ${{fmtSize(totalSize)}}</div>
    <div style="font-size:11px;margin-top:8px;color:var(--muted)">${{files.slice(0,3).map(f=>escapeHtml(f.name)).join(', ')}}${{files.length>3?' +'+(files.length-3)+' 个':''}}</div>`;
}}

document.getElementById('clear').onclick = () => {{
  selected = null; selectedMulti = null; fileInput.value = '';
  document.getElementById('parse').disabled = true;
  document.getElementById('parse').style.display = '';
  document.getElementById('run').style.display = 'none';
  document.getElementById('run').disabled = true;
  document.getElementById('preview-card').style.display = 'none';
  document.getElementById('result-card').style.display = 'none';
  drop.innerHTML = '<div>📁 拖拽文件到这里，或点击选择</div><div style="font-size:12px;margin-top:8px">支持 .csv / .tsv / .json / .xlsx / .txt，最大 16 MB</div>';
}};

document.getElementById('parse').onclick = async () => {{
  if (!selected) return;
  const fd = new FormData(); fd.append('file', selected);
  const btn = document.getElementById('parse');
  btn.disabled = true; btn.innerHTML = '<span class="loading"></span> 解析中…';
  const r = await fetch('/batch/parse', {{ method:'POST', body: fd }});
  const j = await r.json();
  btn.disabled = false; btn.innerHTML = '📋 解析预览';
  if (!r.ok) {{ toast('解析失败: '+(j.detail||r.status),'err'); return; }}
  parsed = j;
  // 渲染预览
  const tb = document.getElementById('preview-table');
  tb.innerHTML = '<thead><tr>' +
    ['#', ...(j.columns || []).map(c => `<th>${{escapeHtml(c)}}</th>`)].join('') + '</tr></thead><tbody>' +
    (j.preview_rows || []).map((row, i) => '<tr><td>'+(i+1)+'</td>'+
      (row||[]).map(c => `<td>${{escapeHtml(String(c))}}</td>`).join('')+'</tr>').join('') +
    '</tbody>';
  document.getElementById('preview-meta').textContent =
    `格式: ${{j.format}}${{j.delimiter?' (分隔符 "'+j.delimiter+'")':''}} · 总行数: ${{j.total}} · 显示前 ${{j.preview_rows.length}} 行`;
  document.getElementById('preview-card').style.display = 'block';
  document.getElementById('run').style.display = '';
  document.getElementById('run').disabled = false;
}};

document.getElementById('run').onclick = async () => {{
  if (!currentTask) return;
  const fd = new FormData();
  let endpoint = '/batch/' + currentTask;
  if (currentTask === 'image-batch') {{
    if (!selectedMulti || !selectedMulti.length) return;
    for (const f of selectedMulti) fd.append('files', f);
  }} else {{
    if (!selected) return;
    fd.append('file', selected);
    if (extraOptions.horizon !== undefined) fd.append('horizon', extraOptions.horizon);
    if (extraOptions.region_prefix !== undefined) fd.append('region_prefix', extraOptions.region_prefix);
  }}
  const btn = document.getElementById('run');
  btn.disabled = true; btn.innerHTML = '<span class="loading"></span> 预测中…';
  const r = await fetch(endpoint, {{ method:'POST', body: fd }});
  let j;
  try {{ j = await r.json(); }} catch {{ j = {{ detail: '响应不是 JSON' }}; }}
  btn.disabled = false; btn.innerHTML = '▶ 预测';
  if (!r.ok) {{ toast('失败: '+(j.detail||r.status),'err'); return; }}
  lastResult = j;
  renderResult(currentTask, j);
}};

document.getElementById('download-template').onclick = () => {{
  if (!currentTask) return;
  window.location.href = '/batch/template/' + currentTask;
}};

document.getElementById('download-result').onclick = () => {{
  if (!lastResult) return;
  const blob = new Blob([JSON.stringify(lastResult, null, 2)], {{ type: 'application/json' }});
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a'); a.href = url;
  a.download = (currentTask || 'result') + '_result.json';
  a.click(); URL.revokeObjectURL(url);
}};

document.getElementById('download-result-csv').onclick = () => {{
  if (!lastResult) return;
  const csv = resultToCsv(currentTask, lastResult);
  const blob = new Blob(['\ufeff'+csv], {{ type: 'text/csv;charset=utf-8' }});
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a'); a.href = url;
  a.download = (currentTask || 'result') + '_result.csv';
  a.click(); URL.revokeObjectURL(url);
}};

function resultToCsv(task, r) {{
  if (task === 'forecast') {{
    let lines = ['step,value'];
    (r.history || []).forEach((v,i) => lines.push('history_'+(i+1)+','+v));
    (r.predictions || []).forEach((v,i) => lines.push('forecast_'+(i+1)+','+v));
    return lines.join('\n');
  }}
  if (task === 'anomaly') {{
    let lines = ['row_index,displacement_rate,crack_width,tilt_angle,rainfall,vibration_freq,is_anomaly'];
    (r.per_row || []).forEach(p => lines.push([
      p.row_index, ...(p.values||[]), p.is_anomaly ? 1 : 0
    ].join(',')));
    return lines.join('\n');
  }}
  if (task === 'comprehensive') {{
    let lines = ['row_index,risk_score,level'];
    (r.per_row || []).forEach(p => lines.push([
      p.row_index, p.overall?.risk_score || 0, p.overall?.level || '?'
    ].join(',')));
    return lines.join('\n');
  }}
  if (task === 'fusion') {{
    let lines = ['device_code,risk_score,level'];
    (r.per_device || []).forEach(p => lines.push([
      '"'+p.device_code+'"', p.result?.risk_score || 0, p.result?.level || '?'
    ].join(',')));
    return lines.join('\n');
  }}
  if (task === 'social_media') {{
    let lines = ['row_index,text,is_disaster_related,risk_level'];
    (r.per_post || []).forEach(p => lines.push([
      p.row_index, '"'+(p.text||'').replace(/"/g,'""')+'"',
      p.result?.is_disaster_related ? 1 : 0, p.result?.risk_level || '?'
    ].join(',')));
    return lines.join('\n');
  }}
  if (task === 'image-batch') {{
    let lines = ['filename,n_detections,engine'];
    (r.results || []).forEach(p => lines.push([
      '"'+p.filename+'"', (p.detections||p.boxes||[]).length, p.engine||'?'
    ].join(',')));
    return lines.join('\n');
  }}
  return JSON.stringify(r);
}}

function renderResult(task, r) {{
  document.getElementById('result-card').style.display = 'block';
  const sum = document.getElementById('result-summary');
  const body = document.getElementById('result-body');
  document.getElementById('result-meta').textContent =
    `任务: ${{task}} · 引擎: ${{r.engine || '?'}} · 耗时: ${{r.inference_ms || 0}} ms`;
  if (task === 'forecast') {{
    sum.innerHTML = `
      <div class="card" style="margin:0"><div class="kv"><span class="k">输入点数</span><span class="v">${{r.n_input}}</span></div><div class="kv"><span class="k">预测步数</span><span class="v">${{r.horizon}}</span></div></div>
      <div class="card" style="margin:0"><div class="kv"><span class="k">模型</span><span class="v">${{r.model}}</span></div><div class="kv"><span class="k">置信度</span><span class="v">${{((r.confidence||0)*100).toFixed(0)}}%</span></div></div>
      <div class="card" style="margin:0"><div class="kv"><span class="k">预测趋势</span><span class="v">${{r.stats?.predicted_trend || 0}} mm/步</span></div><div class="kv"><span class="k">使用列</span><span class="v">${{escapeHtml(r.source_column || '-')}}</span></div></div>`;
    body.innerHTML = `
      <canvas id="forecast-canvas" width="800" height="280" style="width:100%;height:280px;background:#06091a;border-radius:8px;border:1px solid var(--border)"></canvas>
      <table style="margin-top:12px"><thead><tr><th>步</th><th>类型</th><th>数值</th></tr></thead><tbody>
        ${{(r.history||[]).map((v,i) => `<tr><td>${{i+1}}</td><td>历史</td><td>${{v.toFixed(3)}}</td></tr>`).join('')}}
        ${{(r.predictions||[]).map((v,i) => `<tr><td>${{i+1}}</td><td><span class="tag warn">预测</span></td><td>${{v.toFixed(3)}}</td></tr>`).join('')}}
      </tbody></table>`;
    setTimeout(() => drawForecastChart(r.history || [], r.predictions || []), 50);
  }} else if (task === 'anomaly') {{
    sum.innerHTML = `
      <div class="card" style="margin:0"><div class="kv"><span class="k">总行数</span><span class="v">${{r.n_total}}</span></div><div class="kv"><span class="k">有效行</span><span class="v">${{r.n_valid}}</span></div></div>
      <div class="card" style="margin:0"><div class="kv"><span class="k">异常数</span><span class="v status-down">${{r.n_anomaly}}</span></div><div class="kv"><span class="k">得分</span><span class="v">${{r.score}}</span></div></div>
      <div class="card" style="margin:0"><div class="kv"><span class="k">模型</span><span class="v">${{r.model}}</span></div><div class="kv"><span class="k">mock</span><span class="v">${{r.mock?'是':'否'}}</span></div></div>`;
    body.innerHTML = `
      <table><thead><tr><th>#</th><th>判定</th><th>数据</th></tr></thead><tbody>
        ${{(r.per_row||[]).map(p => `<tr><td>${{p.row_index+1}}</td>
          <td>${{p.is_anomaly?'<span class="tag err">异常</span>':'<span class="tag ok">正常</span>'}}</td>
          <td><code>${{(p.values||[]).join(', ')}}</code></td></tr>`).join('')}}
      </tbody></table>`;
  }} else if (task === 'comprehensive') {{
    sum.innerHTML = `
      <div class="card" style="margin:0"><div class="kv"><span class="k">总行数</span><span class="v">${{r.n_total}}</span></div><div class="kv"><span class="k">评估成功</span><span class="v">${{r.n_valid}}</span></div></div>
      <div class="card" style="margin:0"><div class="kv"><span class="k">高风险数</span><span class="v status-down">${{r.n_high_risk}}</span></div><div class="kv"><span class="k">失败行</span><span class="v">${{(r.row_errors||[]).length}}</span></div></div>
      <div class="card" style="margin:0"><div class="kv"><span class="k">模型</span><span class="v">${{escapeHtml((r.engine || '').replace('local:',''))}}</span></div></div>`;
    body.innerHTML = `
      <table><thead><tr><th>#</th><th>分数</th><th>等级</th><th>维度得分</th></tr></thead><tbody>
        ${{(r.per_row||[]).map(p => {{
            const o = p.overall || {{}};
            const dims = p.dimensions || {{}};
            return `<tr><td>${{p.row_index+1}}</td>
              <td><b>${{(o.risk_score||0).toFixed(1)}}</b></td>
              <td class="level-${{(o.level||'').toUpperCase()}}">${{o.level||'?'}}</td>
              <td style="font-size:11px;color:var(--muted)">${{Object.entries(dims).map(([k,v]) => k+':'+(typeof v==='object'?v.score:v).toFixed(0)).join(' / ')}}</td></tr>`;
        }}).join('')}}
      </tbody></table>`;
  }} else if (task === 'fusion') {{
    sum.innerHTML = `
      <div class="card" style="margin:0"><div class="kv"><span class="k">设备数</span><span class="v">${{r.n_devices}}</span></div></div>
      <div class="card" style="margin:0"><div class="kv"><span class="k">高风险设备</span><span class="v status-down">${{(r.per_device||[]).filter(p=>(p.result?.level||'').toUpperCase() in {{HIGH:true,CRITICAL:true}}).length}}</span></div></div>`;
    body.innerHTML = `
      <table><thead><tr><th>设备</th><th>分数</th><th>等级</th><th>传感器</th></tr></thead><tbody>
        ${{(r.per_device||[]).map(p => `<tr><td><code>${{escapeHtml(p.device_code)}}</code></td>
          <td><b>${{(p.result?.risk_score||0).toFixed(1)}}</b></td>
          <td class="level-${{(p.result?.level||'').toUpperCase()}}">${{p.result?.level||'?'}}</td>
          <td style="font-size:11px">${{Object.entries(p.sensor_data).map(([k,v])=>k+'='+(typeof v==='number'?v.toFixed(2):v)).join(' / ')}}</td></tr>`).join('')}}
      </tbody></table>`;
  }} else if (task === 'social_media') {{
    sum.innerHTML = `
      <div class="card" style="margin:0"><div class="kv"><span class="k">总文本行</span><span class="v">${{r.n_total}}</span></div><div class="kv"><span class="k">已分析</span><span class="v">${{r.n_analyzed}}</span></div></div>
      <div class="card" style="margin:0"><div class="kv"><span class="k">涉灾条数</span><span class="v status-down">${{r.n_disaster_related}}</span></div></div>`;
    body.innerHTML = `
      <table><thead><tr><th>#</th><th>内容</th><th>判定</th><th>等级</th></tr></thead><tbody>
        ${{(r.per_post||[]).map(p => `<tr><td>${{p.row_index+1}}</td>
          <td style="max-width:400px;word-break:break-all">${{escapeHtml(p.text)}}</td>
          <td>${{(p.result?.is_disaster_related||p.result?.disaster_type)?'<span class="tag warn">涉灾</span>':'<span class="tag ok">无关</span>'}}</td>
          <td class="level-${{(p.result?.risk_level||'').toUpperCase()}">${{p.result?.risk_level||'-'}}</td></tr>`).join('')}}
      </tbody></table>`;
  }} else if (task === 'image-batch') {{
    sum.innerHTML = `
      <div class="card" style="margin:0"><div class="kv"><span class="k">上传文件数</span><span class="v">${{r.n_files}}</span></div><div class="kv"><span class="k">成功识别</span><span class="v">${{r.n_success}}</span></div></div>
      <div class="card" style="margin:0"><div class="kv"><span class="k">总检测数</span><span class="v">${{r.n_total_detections}}</span></div></div>`;
    body.innerHTML = `
      <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:10px">
      ${{(r.results||[]).map(p => `<div class="card" style="margin:0">
          <div style="font-weight:600;margin-bottom:6px">${{escapeHtml(p.filename)}}</div>
          ${{p.error?`<div style="color:var(--err)">${{escapeHtml(p.error)}}</div>`:
            `<div class="kv"><span class="k">检测数</span><span class="v">${{(p.detections||p.boxes||[]).length}}</span></div>
             <div class="kv"><span class="k">引擎</span><span class="v">${{escapeHtml(p.engine||'?')}}</span></div>
             <div class="kv"><span class="k">变体</span><span class="v">${{escapeHtml(p.variant||'-')}}</span></div>`}}
        </div>`).join('')}}</div>`;
  }} else {{
    body.innerHTML = '<pre class="out">' + escapeHtml(JSON.stringify(r, null, 2)) + '</pre>';
  }}
}}

function drawForecastChart(hist, preds) {{
  const c = document.getElementById('forecast-canvas');
  if (!c) return;
  const ctx = c.getContext('2d');
  const W = c.width = c.clientWidth || 800;
  const H = c.height = 280;
  ctx.fillStyle = '#06091a'; ctx.fillRect(0,0,W,H);
  const all = hist.concat(preds);
  if (!all.length) return;
  const min = Math.min(...all) * 0.95;
  const max = Math.max(...all) * 1.05;
  const pad = 40;
  const xStep = (W - 2*pad) / Math.max(1, all.length - 1);
  const yScale = v => H - pad - (v - min) / (max - min || 1) * (H - 2*pad);
  ctx.strokeStyle = '#1b2340'; ctx.lineWidth = 1;
  for (let i=0;i<5;i++) {{
    const y = pad + (H-2*pad) * i/4;
    ctx.beginPath(); ctx.moveTo(pad,y); ctx.lineTo(W-pad,y); ctx.stroke();
    ctx.fillStyle = '#8b94b0'; ctx.font = '10px monospace';
    ctx.fillText((max - (max-min)*i/4).toFixed(1), 4, y+3);
  }}
  ctx.strokeStyle = '#4fc3f7'; ctx.lineWidth = 2;
  ctx.beginPath();
  hist.forEach((v,i) => {{ const x = pad + i*xStep; const y = yScale(v);
    i===0?ctx.moveTo(x,y):ctx.lineTo(x,y); }});
  ctx.stroke();
  ctx.strokeStyle = '#ffa726'; ctx.setLineDash([4,3]);
  ctx.beginPath();
  const last = hist[hist.length-1];
  ctx.moveTo(pad + (hist.length-1)*xStep, yScale(last));
  preds.forEach((v,i) => ctx.lineTo(pad + (hist.length+i)*xStep, yScale(v)));
  ctx.stroke(); ctx.setLineDash([]);
  hist.forEach((v,i) => {{ ctx.fillStyle = '#4fc3f7'; ctx.beginPath();
    ctx.arc(pad + i*xStep, yScale(v), 3, 0, Math.PI*2); ctx.fill(); }});
  preds.forEach((v,i) => {{ ctx.fillStyle = '#ffa726'; ctx.beginPath();
    ctx.arc(pad + (hist.length+i)*xStep, yScale(v), 4, 0, Math.PI*2); ctx.fill(); }});
  ctx.fillStyle = '#4fc3f7'; ctx.fillRect(pad, H-pad+6, 12, 8);
  ctx.fillStyle = '#e6edf3'; ctx.fillText('history ('+hist.length+')', pad+18, H-pad+14);
  ctx.fillStyle = '#ffa726'; ctx.fillRect(pad+150, H-pad+6, 12, 8);
  ctx.fillStyle = '#e6edf3'; ctx.fillText('forecast ('+preds.length+')', pad+168, H-pad+14);
}}

loadTemplates();
</script>
"""


# ──────────────────────────────── Endpoints ────────────────────────────

ENDPOINTS_BODY = r"""
<div class="card">
  <h2>API 端点速查 & 即时 curl</h2>
  <p style="color:var(--muted);margin-top:0">
    点击「复制」可复制完整 curl 命令到剪贴板。下方所有调用都基于 FastAPI 服务（<code id="api-base">http://127.0.0.1:8001</code>），
    如果 API 未运行，调用会失败。
  </p>
  <div id="endpoints">加载中…</div>
</div>

<script>
async function loadEndpoints() {{
  const r = await api('/_internal/endpoints');
  const base = document.getElementById('api-base');
  if (r.body && r.body.base) base.textContent = r.body.base;
  const eps = (r.body && Array.isArray(r.body)) ? r.body : (r.body && r.body.endpoints) || [];
  const html = eps.map(e => {{
    const curl = `curl -sS -X ${{e.method}} '${{(r.body&&r.body.base)||'http://127.0.0.1:8001'}}${{e.path}}'` +
                 (e.has_body ? ` -H 'Content-Type: application/json' -d '${{e.sample_body||"{}"}}'` : '');
    return `<div class="card" style="margin-bottom:8px">
      <div style="display:flex;align-items:center;gap:10px;">
        <span class="tag ${{e.method==='GET'?'ok':'warn'}}">${{e.method}}</span>
        <code style="flex:1">${{escapeHtml(e.path)}}</code>
        <button class="btn-ghost" onclick="navigator.clipboard.writeText(\`${{curl.replace(/`/g,'').replace(/\\/g,'\\\\')}}\`); toast('已复制','ok')">📋 复制</button>
        <button class="btn-ghost" onclick="tryEndpoint('${{e.method}}','${{e.path}}', ${{e.has_body?'true':'false'}})">▶ 试一下</button>
      </div>
      ${{e.description?`<div style="color:var(--muted);font-size:12px;margin-top:6px">${{escapeHtml(e.description)}}</div>`:''}}
    </div>`;
  }}).join('');
  document.getElementById('endpoints').innerHTML = html || '<div style="color:var(--muted)">无端点</div>';
}}
async function tryEndpoint(m, p, hasBody) {{
  const opts = {{ method:m }};
  if (hasBody) {{ opts.headers = {{'Content-Type':'application/json'}}; opts.body = '{{}}'; }}
  const r = await fetch('http://127.0.0.1:8001'+p, opts);
  toast(m+' '+p+' → '+r.status, r.ok?'ok':'err');
}}
loadEndpoints();
</script>
"""

# ╔══════════════════════════════════════════════════════════════════════╗
# ║  FastAPI 应用                                                         ║
# ╚══════════════════════════════════════════════════════════════════════╝

app = FastAPI(
    title="DisasterGuard AI · Standalone Web UI",
    description="独立的 Web 界面，无需启动 FastAPI 主服务即可使用（直接调用模型）。",
    version="3.0.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


# ──────────────────────────────── 页面路由 ────────────────────────────

@app.get("/", response_class=HTMLResponse, tags=["ui"])
async def page_dashboard() -> HTMLResponse:
    body = DASHBOARD_BODY.replace("__AI_ROOT__", str(AI_ROOT)) \
                          .replace("__WEB_PID__", str(os.getpid())) \
                          .replace("__BOOT_TIME__", datetime.now().strftime("%Y-%m-%d %H:%M:%S")) \
                          .replace("__PY_VER__", f"{sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}")
    return _layout("Dashboard", body, active="/")


@app.get("/detect", response_class=HTMLResponse, tags=["ui"])
async def page_detect() -> HTMLResponse:
    return _layout("图像识别", DETECT_BODY, active="/detect")


@app.get("/forecast", response_class=HTMLResponse, tags=["ui"])
async def page_forecast() -> HTMLResponse:
    return _layout("时序预测", FORECAST_BODY, active="/forecast")


@app.get("/comprehensive", response_class=HTMLResponse, tags=["ui"])
async def page_comprehensive() -> HTMLResponse:
    return _layout("综合风险", COMPREHENSIVE_BODY, active="/comprehensive")


@app.get("/anomaly", response_class=HTMLResponse, tags=["ui"])
async def page_anomaly() -> HTMLResponse:
    return _layout("异常检测", ANOMALY_BODY, active="/anomaly")


@app.get("/batch", response_class=HTMLResponse, tags=["ui"])
async def page_batch() -> HTMLResponse:
    return _layout("批量上传", BATCH_BODY, active="/batch")


@app.get("/endpoints", response_class=HTMLResponse, tags=["ui"])
async def page_endpoints() -> HTMLResponse:
    return _layout("API 端点", ENDPOINTS_BODY, active="/endpoints")


# ──────────────────────────────── 内部数据路由（前端用） ───────────────

@app.get("/_internal/health", tags=["internal"])
async def internal_health() -> Dict[str, Any]:
    """探测 FastAPI 服务状态（不要求存在）。"""
    import urllib.request
    import urllib.error
    try:
        req = urllib.request.Request(SERVICE_URL + "/api/ai/health", timeout=1.5)
        with urllib.request.urlopen(req) as r:
            data = json.loads(r.read())
            data["api_up"] = True
            return data
    except urllib.error.HTTPError as e:
        try:
            data = json.loads(e.read())
            data["api_up"] = e.code == 200
            return data
        except Exception:
            return {"status": "DOWN", "api_up": False, "http": e.code}
    except Exception:
        return {"status": "DOWN", "api_up": False}


@app.get("/_internal/models", tags=["internal"])
async def internal_models() -> Dict[str, Any]:
    """读取模型文件状态（直接读盘，不依赖 FastAPI）。"""
    import json as _json
    from app.models.xgboost_model import get_fusion_model
    from app.models.comprehensive_model import get_comprehensive_model
    from app.routers.model_info import (
        FUSION_META_PATH, COMPREHENSIVE_META_PATH,
        LSTM_META_PATH, IFOREST_META_PATH,
    )

    fusion = get_fusion_model()
    comprehensive = get_comprehensive_model()
    models_dir = fusion.model_path.parent

    def _read_meta(p: Path) -> dict:
        if not p.exists():
            return {}
        try:
            return _json.loads(p.read_text(encoding="utf-8"))
        except Exception:
            return {}

    models = []
    for fname, label, meta_p in [
        ("yolo_crack.onnx", "YOLO 裂缝/落石检测", None),
        ("lstm_displacement.pt", "LSTM 位移预测", LSTM_META_PATH),
        ("isolation_forest.joblib", "Isolation Forest 异常检测", IFOREST_META_PATH),
        ("xgboost_fusion.json", "XGBoost 融合预警", FUSION_META_PATH),
        ("comprehensive_v2.json", "综合风险分析", COMPREHENSIVE_META_PATH),
    ]:
        p = models_dir / fname
        info: Dict[str, Any] = {
            "name": label,
            "path": str(p),
            "exists_on_disk": p.exists(),
            "size_bytes": p.stat().st_size if p.exists() else 0,
        }
        if meta_p:
            meta = _read_meta(meta_p)
            if meta:
                info.update({
                    "trained_at": meta.get("trained_at"),
                    "version": meta.get("version"),
                    "feature_count": meta.get("n_features"),
                })
        models.append(info)

    return {
        "models": models,
        "fusion_loaded": fusion.is_loaded(),
        "comprehensive_loaded": comprehensive.is_loaded(),
    }


@app.get("/_internal/logs", tags=["internal"])
async def internal_logs(lines: int = Query(40, ge=1, le=500)) -> PlainTextResponse:
    if not LOG_FILE.exists():
        return PlainTextResponse("（日志文件不存在）")
    try:
        with LOG_FILE.open("r", encoding="utf-8", errors="replace") as f:
            data = f.readlines()
        return PlainTextResponse("".join(data[-lines:]))
    except Exception as e:
        return PlainTextResponse(f"读取失败: {e}")


@app.get("/_internal/endpoints", tags=["internal"])
async def internal_endpoints() -> Dict[str, Any]:
    """列出 FastAPI 服务端点（与 Web UI 自身的页面并列展示）。"""
    web_pages = [
        ("/", "GET", "Web UI · 仪表盘", False, ""),
        ("/detect", "GET", "Web UI · 图像识别", False, ""),
        ("/forecast", "GET", "Web UI · 时序预测", False, ""),
        ("/comprehensive", "GET", "Web UI · 综合风险", False, ""),
        ("/anomaly", "GET", "Web UI · 异常检测", False, ""),
    ]
    api_endpoints = [
        ("/api/ai/health", "GET", "健康检查", False, ""),
        ("/api/ai/model/info", "GET", "所有模型状态", False, ""),
        ("/api/ai/model/health", "GET", "模型推理可用性", False, ""),
        ("/api/ai/model/reload", "POST", "重新加载模型", False, ""),
        ("/api/ai/model/retrain", "POST", "重新训练 XGBoost", True, '{"samples":1500,"val_ratio":0.2}'),
        ("/api/ai/detect", "POST", "YOLO 图像检测", True, ""),
        ("/api/ai/fusion-risk", "POST", "设备/传感器融合预警", True, ""),
        ("/api/ai/anomaly", "POST", "异常检测", True, ""),
        ("/api/ai/forecast", "POST", "时序预测", True, ""),
        ("/api/ai/comprehensive", "POST", "综合风险分析", True, ""),
        ("/api/ai/disaster-assessment", "POST", "灾害类型评估", True, ""),
        ("/api/ai/social-media", "POST", "社交媒体分析", True, ""),
        ("/api/ai/device-health", "POST", "设备健康评分", True, ""),
        ("/api/ai/feedback", "POST", "反馈学习闭环", True, ""),
        ("/api/ai/sentinel/*", "GET", "Sentinel 合成图", False, ""),
        ("/api/ai/drift/*", "GET", "模型漂移检测", False, ""),
        ("/api/ai/multimodal/*", "GET", "多模态融合", False, ""),
    ]
    web_endpoints = [
        ("/", "GET", "Web UI · 仪表盘", False, ""),
        ("/detect", "GET", "Web UI · 图像识别", False, ""),
        ("/forecast", "GET", "Web UI · 时序预测", False, ""),
        ("/comprehensive", "GET", "Web UI · 综合风险", False, ""),
        ("/anomaly", "GET", "Web UI · 异常检测", False, ""),
        ("/batch", "GET", "Web UI · 批量上传", False, ""),
    ]
    batch_endpoints = [
        ("/batch/templates", "GET", "列出批量任务模板", False, ""),
        ("/batch/template/{task}", "GET", "下载任务模板（forecast/anomaly/comprehensive/fusion/social_media）", False, ""),
        ("/batch/parse", "POST", "解析上传文件并预览", True, ""),
        ("/batch/forecast", "POST", "上传 CSV → LSTM 时序预测", True, ""),
        ("/batch/anomaly", "POST", "上传 CSV → 异常检测", True, ""),
        ("/batch/comprehensive", "POST", "上传 CSV → 综合风险评估（支持批量）", True, ""),
        ("/batch/fusion", "POST", "上传 CSV → 多传感器融合", True, ""),
        ("/batch/images", "POST", "上传多张图片 → 批量识别（≤50）", True, ""),
        ("/batch/social-media", "POST", "上传 txt → 社交媒体分析", True, ""),
    ]
    rows = []
    for path, method, desc, has_body, sample in api_endpoints:
        rows.append({
            "path": path, "method": method, "description": desc,
            "has_body": has_body, "sample_body": sample,
            "api_only": True, "web_only": False,
        })
    for path, method, desc, has_body, sample in web_endpoints:
        rows.append({
            "path": path, "method": method, "description": desc,
            "has_body": has_body, "sample_body": sample,
            "api_only": False, "web_only": True,
        })
    for path, method, desc, has_body, sample in batch_endpoints:
        rows.append({
            "path": path, "method": method, "description": desc,
            "has_body": has_body, "sample_body": sample,
            "api_only": False, "web_only": True,
            "is_batch": True,
        })
    return {"base": SERVICE_URL, "endpoints": rows}


# ──────────────────────────────── 推理端点（直接调模型） ──────────────

@app.post("/detect/run", tags=["inference"])
async def infer_detect(
    file: UploadFile = File(...),
    use_api: str = Form("1"),
) -> JSONResponse:
    """图像识别：优先走 FastAPI（如果 8001 在跑），否则本地推理。"""
    img_bytes = await file.read()
    if len(img_bytes) > 8 * 1024 * 1024:
        raise HTTPException(413, "图片超过 8 MB")

    # 1) 优先 API
    if use_api == "1":
        try:
            import urllib.request
            import urllib.error
            import http.client
            # 用 http.client 避免 multipart 兼容性问题
            boundary = "----WebFormBoundary" + os.urandom(8).hex()
            body = (
                f"--{boundary}\r\n"
                f"Content-Disposition: form-data; name=\"file\"; filename=\"{file.filename}\"\r\n"
                f"Content-Type: {file.content_type or 'application/octet-stream'}\r\n\r\n"
            ).encode() + img_bytes + f"\r\n--{boundary}--\r\n".encode()
            host = SERVICE_URL.split("://", 1)[1].split(":", 1)[0]
            port = int(SERVICE_URL.rsplit(":", 1)[1].split("/", 1)[0])
            conn = http.client.HTTPConnection(host, port, timeout=10)
            conn.request("POST", "/api/ai/detect", body=body,
                         headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
            r = conn.getresponse()
            raw = r.read()
            if r.status == 200:
                j = json.loads(raw)
                j["engine"] = "FastAPI:" + SERVICE_URL
                return JSONResponse(j)
        except Exception:
            pass  # fallback 到本地

    # 2) 本地推理
    t0 = time.time()
    try:
        from app.models.yolo_model import get_detector
        detector = get_detector()
        result = detector.detect(img_bytes)
        result["engine"] = "local:" + (detector.model_variant or "?")
        result["inference_ms"] = (time.time() - t0) * 1000
        result["variant"] = detector.model_variant
        result["is_business_model"] = detector.is_business_model
        # 加 base64 给前端画 bbox
        import base64
        b64 = base64.b64encode(img_bytes).decode()
        result["image_data_url"] = f"data:{file.content_type or 'image/jpeg'};base64,{b64}"
        return JSONResponse(result)
    except Exception as e:
        logger.exception("detect failed")
        raise HTTPException(500, f"推理失败: {e}")


@app.post("/forecast/run", tags=["inference"])
async def infer_forecast(payload: Dict[str, Any]) -> JSONResponse:
    history = payload.get("history") or []
    horizon = int(payload.get("horizon", 5))
    if not isinstance(history, list) or len(history) < 3:
        raise HTTPException(400, "history 必须是非空数组，长度≥3")
    if not (1 <= horizon <= 20):
        raise HTTPException(400, "horizon 必须在 1-20")

    t0 = time.time()
    try:
        from app.models.lstm_model import get_forecaster
        fc = get_forecaster()
        result = fc.predict([float(x) for x in history], horizon=horizon)
        result["engine"] = "local:lstm" + (" (mock)" if result.get("mock") else "")
        result["inference_ms"] = (time.time() - t0) * 1000
        return JSONResponse(result)
    except Exception as e:
        logger.exception("forecast failed")
        raise HTTPException(500, f"推理失败: {e}")


@app.post("/comprehensive/run", tags=["inference"])
async def infer_comprehensive(payload: Dict[str, Any]) -> JSONResponse:
    factors = payload.get("factors") or {}
    hazard_code = factors.pop("hazard_code", None) or payload.get("hazard_code", "WEB_DEFAULT")
    if not isinstance(factors, dict) or not factors:
        raise HTTPException(400, "factors 必须是非空对象")

    t0 = time.time()
    try:
        from app.models.comprehensive_model import get_comprehensive_model
        cm = get_comprehensive_model()
        # factors 数字类型
        clean = {}
        for k, v in factors.items():
            try:
                clean[k] = float(v)
            except (TypeError, ValueError):
                pass
        result = cm.score(clean, region_code=hazard_code)
        result["engine"] = "local:xgboost" + (" (mock)" if result.get("mock") else "")
        result["inference_ms"] = (time.time() - t0) * 1000
        return JSONResponse(result)
    except Exception as e:
        logger.exception("comprehensive failed")
        raise HTTPException(500, f"推理失败: {e}")


@app.post("/anomaly/run", tags=["inference"])
async def infer_anomaly(payload: Dict[str, Any]) -> JSONResponse:
    data = payload.get("data") or []
    if not isinstance(data, list) or not data:
        raise HTTPException(400, "data 必须是非空数组")
    if any(not isinstance(row, list) or len(row) != 5 for row in data):
        raise HTTPException(400, "每行必须恰好 5 个特征")

    t0 = time.time()
    try:
        from app.models.isolation_forest import get_detector
        det = get_detector()
        result = det.detect([[float(x) for x in row] for row in data])
        result["engine"] = "local:isolation_forest" + (" (mock)" if result.get("mock") else "")
        result["inference_ms"] = (time.time() - t0) * 1000
        return JSONResponse(result)
    except Exception as e:
        logger.exception("anomaly failed")
        raise HTTPException(500, f"推理失败: {e}")


# ──────────────────────────── 批量上传工具 ─────────────────────────────

def _parse_table_upload(raw: bytes, filename: str) -> Dict[str, Any]:
    """
    解析上传的表格文件为 {columns, rows, total, format}。

    支持：
      .csv          → csv.DictReader（自动分隔符检测）
      .tsv .tab     → csv.DictReader(dialect='excel-tab')
      .json         → 期望 list[dict] 或 dict
      .xlsx .xls    → pandas.read_excel（如不可用则报错）
    """
    name = filename.lower()
    if name.endswith((".xlsx", ".xls")):
        try:
            import pandas as pd
            import io as _io
            df = pd.read_excel(_io.BytesIO(raw))
            return {
                "format": "xlsx",
                "columns": list(df.columns.astype(str)),
                "rows": df.fillna("").astype(object).values.tolist(),
                "total": len(df),
            }
        except ImportError:
            raise HTTPException(400, "解析 Excel 需要安装 pandas + openpyxl：`pip install pandas openpyxl`")

    if name.endswith(".json"):
        try:
            data = json.loads(raw.decode("utf-8"))
        except Exception as e:
            raise HTTPException(400, f"JSON 解析失败: {e}")
        if isinstance(data, dict) and "rows" in data:
            data = data["rows"]
        if not isinstance(data, list):
            raise HTTPException(400, "JSON 必须是数组或包含 rows 字段的对象")
        if not data:
            return {"format": "json", "columns": [], "rows": [], "total": 0}
        # 收集所有 key
        cols = []
        for row in data:
            if isinstance(row, dict):
                for k in row.keys():
                    if k not in cols:
                        cols.append(k)
        rows = [[row.get(c, "") if isinstance(row, dict) else "" for c in cols] for row in data]
        return {"format": "json", "columns": cols, "rows": rows, "total": len(rows)}

    # 默认按 CSV
    try:
        text = raw.decode("utf-8-sig")  # 自动剥离 BOM
    except UnicodeDecodeError:
        try:
            text = raw.decode("gbk")
        except Exception as e:
            raise HTTPException(400, f"文件编码无法识别（请用 UTF-8 或 GBK）: {e}")
    sample = text[:4096]
    try:
        import csv as _csv
        # 自动分隔符
        try:
            dialect = _csv.Sniffer().sniff(sample, delimiters=",\t;|")
            delimiter = dialect.delimiter
        except _csv.Error:
            delimiter = ","
        reader = _csv.reader(text.splitlines(), delimiter=delimiter)
        all_rows = list(reader)
        if not all_rows:
            return {"format": "csv", "columns": [], "rows": [], "total": 0, "delimiter": delimiter}
        header = all_rows[0]
        data_rows = all_rows[1:]
        return {
            "format": "csv",
            "columns": [str(c).strip() for c in header],
            "rows": data_rows,
            "total": len(data_rows),
            "delimiter": delimiter,
        }
    except Exception as e:
        raise HTTPException(400, f"CSV 解析失败: {e}")


def _pick_column(columns: list, candidates: list, prefer_idx: int = 0) -> int | None:
    """在列名列表中查找最匹配的列索引（不区分大小写、允许部分匹配）。"""
    lc = [str(c).strip().lower() for c in columns]
    # 精确匹配
    for cand in candidates:
        for i, name in enumerate(lc):
            if name == cand.lower():
                return i
    # 包含匹配
    for cand in candidates:
        for i, name in enumerate(lc):
            if cand.lower() in name:
                return i
    return None


def _to_float(s) -> float | None:
    if s is None or s == "":
        return None
    try:
        return float(str(s).strip())
    except (ValueError, TypeError):
        return None


# 任务模板（每个任务对应一份 CSV 模板）
TASK_TEMPLATES: Dict[str, Dict[str, Any]] = {
    "forecast": {
        "name": "LSTM 时序预测",
        "filename": "forecast_template.csv",
        "columns": ["displacement_mm"],
        "description": "单列历史位移序列，每行一个数值（mm）。模型会对全序列训练趋势并预测未来 N 步。",
        "sample_csv": "displacement_mm\n1.0\n1.2\n1.5\n1.9\n2.5\n3.3\n4.4\n5.8\n7.6\n10.0\n12.5\n15.5\n",
    },
    "anomaly": {
        "name": "异常检测",
        "filename": "anomaly_template.csv",
        "columns": ["displacement_rate", "crack_width", "tilt_angle", "rainfall", "vibration_freq"],
        "description": "5 列特征：位移速率、裂缝宽度、倾斜角、降雨量、振动频率。每行一个采样点。",
        "sample_csv": "displacement_rate,crack_width,tilt_angle,rainfall,vibration_freq\n1.0,1.0,0.5,2.0,1.0\n1.2,1.1,0.6,2.1,1.1\n1.1,1.0,0.5,2.2,1.0\n25.0,35.0,8.0,100.0,30.0\n1.0,1.2,0.4,2.0,1.0\n",
    },
    "comprehensive": {
        "name": "综合风险评分",
        "filename": "comprehensive_template.csv",
        "columns": [
            "rainfall_24h", "rainfall_7d", "soil_moisture", "slope_deg", "vegetation_cover",
            "soil_grade", "displacement_rate", "displacement_total", "crack_width",
            "earthquake_mag", "historical_count", "image_anomaly_score",
        ],
        "description": "12 维风险因子，每行一个评估对象。可以上传一行评估一个点，也可以上传多行批量评估。",
        "sample_csv": "rainfall_24h,rainfall_7d,soil_moisture,slope_deg,vegetation_cover,soil_grade,displacement_rate,displacement_total,crack_width,earthquake_mag,historical_count,image_anomaly_score\n60,180,0.45,30,0.3,3,8,35,15,3.5,3,40\n120,340,0.65,42,0.15,5,25,120,45,5.5,6,80\n",
    },
    "fusion": {
        "name": "多传感器融合预警",
        "filename": "fusion_template.csv",
        "columns": ["device_code", "metric_name", "value", "timestamp"],
        "description": "每行一条传感器读数：设备编码、指标名、数值、时间戳（ISO8601）。模型聚合多设备指标给出融合预警。",
        "sample_csv": "device_code,metric_name,value,timestamp\nSENSOR_A01,displacement_rate,5.2,2026-09-30T10:00:00\nSENSOR_A01,crack_width,12.5,2026-09-30T10:00:00\nSENSOR_A01,tilt_angle,1.8,2026-09-30T10:00:00\nSENSOR_B02,displacement_rate,8.7,2026-09-30T10:01:00\nSENSOR_B02,rainfall,45.0,2026-09-30T10:01:00\n",
    },
    "social_media": {
        "name": "社交媒体文本分析",
        "filename": "social_media_template.txt",
        "columns": [],
        "description": "纯文本文件：每行一条社交媒体帖子。模型会分析灾害相关性、情感倾向、紧急程度。",
        "sample_csv": "山脚下出现大面积裂缝，地面下沉明显\n刚刚发生了强烈的震动，窗户都在晃\n今天天气真好，适合爬山\n水库水位暴涨，有溃坝风险！\n",
    },
    "image-batch": {
        "name": "图像批量识别（多张）",
        "filename": "image_batch_README.txt",
        "columns": [],
        "description": "同时拖拽/选择多张图片（≤50），模型会逐张做 YOLO 检测。每张图片 ≤8 MB。",
        "sample_csv": "请直接拖拽多张 jpg/png 图片到上传框。",
    },
}


@app.get("/batch/templates", tags=["batch"])
async def batch_list_templates() -> Dict[str, Any]:
    """列出所有可用的 CSV 模板元信息。"""
    out = []
    for k, v in TASK_TEMPLATES.items():
        out.append({
            "task": k,
            "name": v["name"],
            "filename": v["filename"],
            "columns": v["columns"],
            "description": v["description"],
            "format": "image/*" if k == "image-batch" else ("txt" if k == "social_media" else "csv"),
            "is_image": k == "image-batch",
        })
    return {"templates": out}


@app.get("/batch/template/{task}", tags=["batch"])
async def batch_download_template(task: str):
    """下载指定任务的 CSV/文本模板。"""
    if task not in TASK_TEMPLATES:
        raise HTTPException(404, f"未知任务: {task}（可选: {list(TASK_TEMPLATES.keys())}）")
    tpl = TASK_TEMPLATES[task]
    media_type = "text/plain; charset=utf-8"
    if task == "image-batch":
        body = (
            "图像批量识别任务说明\n\n"
            "请直接在浏览器上传框里拖拽多张图片（jpg/png/webp），最多 50 张，\n"
            "每张不超过 8 MB。模型会逐张做 YOLO 裂缝/落石/灾害目标检测。\n\n"
            "无需下载模板。\n"
        ).encode("utf-8")
        filename = "image_batch_README.txt"
        media_type = "text/plain; charset=utf-8"
    elif task == "social_media":
        body = tpl["sample_csv"].encode("utf-8")
        filename = tpl["filename"]
    else:
        # BOM 让 Excel 自动识别 UTF-8
        body = ("\ufeff" + tpl["sample_csv"]).encode("utf-8")
        media_type = "text/csv; charset=utf-8"
        filename = tpl["filename"]
    from fastapi.responses import Response
    return Response(
        content=body,
        media_type=media_type,
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )


@app.post("/batch/parse", tags=["batch"])
async def batch_parse(file: UploadFile = File(...)) -> Dict[str, Any]:
    """解析上传的表格文件，仅返回表头和前 20 行预览（不执行推理）。"""
    raw = await file.read()
    if len(raw) > 16 * 1024 * 1024:
        raise HTTPException(413, "文件超过 16 MB")
    parsed = _parse_table_upload(raw, file.filename or "data.csv")
    parsed["preview_rows"] = parsed["rows"][:20]
    parsed["filename"] = file.filename
    return parsed


@app.post("/batch/forecast", tags=["batch"])
async def batch_forecast(
    file: UploadFile = File(...),
    horizon: int = Form(7),
    device_code: str = Form("BATCH"),
) -> Dict[str, Any]:
    """上传 CSV/Excel → LSTM 时序预测。返回原始序列、预测、统计。"""
    raw = await file.read()
    if len(raw) > 16 * 1024 * 1024:
        raise HTTPException(413, "文件超过 16 MB")
    parsed = _parse_table_upload(raw, file.filename or "forecast.csv")
    cols = parsed["columns"]
    rows = parsed["rows"]
    if not cols or not rows:
        raise HTTPException(400, "文件为空或无数据行")

    # 自动选列
    candidates = ["displacement_mm", "displacement", "value", "y", "位移", "amount"]
    idx = _pick_column(cols, candidates)
    if idx is None:
        raise HTTPException(400,
            f"找不到位移列，请用以下任一列名：{candidates}（实际列名: {cols}）")
    series: list = []
    errors: list = []
    for i, row in enumerate(rows):
        v = _to_float(row[idx] if idx < len(row) else None)
        if v is None:
            errors.append({"row": i, "raw": row, "reason": "无法转为数字"})
            continue
        series.append(v)
    if len(series) < 3:
        raise HTTPException(400, f"有效数据点不足 3（实际 {len(series)}，错误 {len(errors)}）")

    t0 = time.time()
    try:
        from app.models.lstm_model import get_forecaster
        fc = get_forecaster()
        result = fc.predict(series, horizon=horizon)
        result["engine"] = "local:lstm" + (" (mock)" if result.get("mock") else "")
    except Exception as e:
        raise HTTPException(500, f"推理失败: {e}")

    return {
        "task": "forecast",
        "device_code": device_code,
        "filename": file.filename,
        "source_column": cols[idx],
        "used_columns": cols,
        "n_input": len(series),
        "n_errors": len(errors),
        "history": series,
        "horizon": horizon,
        "predictions": result["predictions"],
        "confidence": result["confidence"],
        "model": result["model"],
        "mock": result["mock"],
        "engine": result["engine"],
        "inference_ms": round((time.time() - t0) * 1000, 2),
        "row_errors": errors[:20],
        "stats": {
            "input_min": round(min(series), 4),
            "input_max": round(max(series), 4),
            "input_mean": round(sum(series) / len(series), 4),
            "predicted_trend": round((result["predictions"][-1] - series[-1]) / max(1, horizon), 4),
        },
    }


@app.post("/batch/anomaly", tags=["batch"])
async def batch_anomaly(file: UploadFile = File(...)) -> Dict[str, Any]:
    """上传 CSV/Excel → 异常检测（每行一个 5 维样本）。"""
    raw = await file.read()
    if len(raw) > 16 * 1024 * 1024:
        raise HTTPException(413, "文件超过 16 MB")
    parsed = _parse_table_upload(raw, file.filename or "anomaly.csv")
    cols = parsed["columns"]
    rows = parsed["rows"]
    if not cols or not rows:
        raise HTTPException(400, "文件为空")

    # 尝试映射 5 个标准特征
    feature_candidates = [
        "displacement_rate", "crack_width", "tilt_angle", "rainfall", "vibration_freq",
        ["位移速率", "裂缝宽度", "倾斜角", "降雨", "振动频率"],
    ]
    # 英文 5 列
    feature_names = ["displacement_rate", "crack_width", "tilt_angle", "rainfall", "vibration_freq"]
    indices = []
    for cand in feature_names:
        i = _pick_column(cols, [cand])
        if i is None:
            break
        indices.append(i)
    if len(indices) != 5:
        # 中文列名
        cn_names = ["位移", "裂缝", "倾斜", "降雨", "振动"]
        indices2 = []
        for cand in cn_names:
            i = _pick_column(cols, [cand])
            if i is None:
                break
            indices2.append(i)
        if len(indices2) == 5:
            indices = indices2
    if len(indices) != 5:
        raise HTTPException(400,
            f"需要 5 列特征：{feature_names} 或包含关键词: 位移/裂缝/倾斜/降雨/振动（实际列: {cols}）")

    data: list = []
    errors: list = []
    for i, row in enumerate(rows):
        vals = [_to_float(row[j] if j < len(row) else None) for j in indices]
        if any(v is None for v in vals):
            errors.append({"row": i, "raw": row, "reason": "列缺失或非数字"})
            continue
        data.append(vals)
    if not data:
        raise HTTPException(400, "无有效数据行")

    t0 = time.time()
    try:
        from app.models.isolation_forest import get_detector
        det = get_detector()
        result = det.detect([list(map(float, row)) for row in data])
        result["engine"] = "local:isolation_forest" + (" (mock)" if result.get("mock") else "")
    except Exception as e:
        raise HTTPException(500, f"推理失败: {e}")

    # 逐行标签
    anomaly_set = set(result.get("anomaly_indices") or [])
    per_row = []
    for i, row in enumerate(data):
        per_row.append({
            "row_index": i,
            "values": row,
            "is_anomaly": i in anomaly_set,
        })

    return {
        "task": "anomaly",
        "filename": file.filename,
        "used_columns": [cols[i] for i in indices],
        "feature_names": feature_names,
        "n_total": len(rows),
        "n_valid": len(data),
        "n_anomaly": len(anomaly_set),
        "anomaly_indices": sorted(anomaly_set),
        "per_row": per_row,
        "score": result["score"],
        "model": result["model"],
        "mock": result["mock"],
        "engine": result["engine"],
        "inference_ms": round((time.time() - t0) * 1000, 2),
        "row_errors": errors[:20],
    }


@app.post("/batch/comprehensive", tags=["batch"])
async def batch_comprehensive(
    file: UploadFile = File(...),
    region_prefix: str = Form("BATCH"),
) -> Dict[str, Any]:
    """上传 CSV → 综合风险评估（多行批量）。"""
    raw = await file.read()
    if len(raw) > 16 * 1024 * 1024:
        raise HTTPException(413, "文件超过 16 MB")
    parsed = _parse_table_upload(raw, file.filename or "risk.csv")
    cols = parsed["columns"]
    rows = parsed["rows"]
    if not cols or not rows:
        raise HTTPException(400, "文件为空")

    factor_names = [
        "rainfall_24h", "rainfall_7d", "soil_moisture", "slope_deg", "vegetation_cover",
        "soil_grade", "displacement_rate", "displacement_total", "crack_width",
        "earthquake_mag", "historical_count", "image_anomaly_score",
    ]
    indices = []
    for name in factor_names:
        i = _pick_column(cols, [name])
        if i is None:
            raise HTTPException(400, f"找不到必需列: {name}（实际列名: {cols}）")
        indices.append(i)

    t0 = time.time()
    try:
        from app.models.comprehensive_model import get_comprehensive_model
        cm = get_comprehensive_model()
    except Exception as e:
        raise HTTPException(500, f"加载模型失败: {e}")

    per_row = []
    errors = []
    for i, row in enumerate(rows):
        factors = {}
        for name, j in zip(factor_names, indices):
            v = _to_float(row[j] if j < len(row) else None)
            if v is None:
                errors.append({"row": i, "raw": row, "reason": f"列 {name} 缺失或非数字"})
                break
            factors[name] = v
        if len(factors) != len(factor_names):
            continue
        try:
            r = cm.score(factors, region_code=f"{region_prefix}_{i}")
            per_row.append({
                "row_index": i,
                "factors": factors,
                "overall": r.get("overall", {}),
                "dimensions": r.get("dimensions", {}),
                "engine": r.get("engine", "?"),
                "mock": r.get("mock", False),
            })
        except Exception as e:
            errors.append({"row": i, "raw": row, "reason": f"推理失败: {e}"})

    if not per_row:
        raise HTTPException(400, f"全部 {len(rows)} 行均推理失败")

    n_anom = sum(1 for x in per_row if (x["overall"].get("level", "").upper() in ("HIGH", "CRITICAL")))
    return {
        "task": "comprehensive",
        "filename": file.filename,
        "used_columns": cols,
        "n_total": len(rows),
        "n_valid": len(per_row),
        "n_high_risk": n_anom,
        "per_row": per_row,
        "engine": "local:xgboost",
        "inference_ms": round((time.time() - t0) * 1000, 2),
        "row_errors": errors[:20],
    }


@app.post("/batch/fusion", tags=["batch"])
async def batch_fusion(file: UploadFile = File(...)) -> Dict[str, Any]:
    """上传 CSV → 多传感器融合预警（按 device_code + metric_name 自动分组）。"""
    raw = await file.read()
    if len(raw) > 16 * 1024 * 1024:
        raise HTTPException(413, "文件超过 16 MB")
    parsed = _parse_table_upload(raw, file.filename or "sensors.csv")
    cols = parsed["columns"]
    rows = parsed["rows"]
    if not cols or not rows:
        raise HTTPException(400, "文件为空")

    dev_idx = _pick_column(cols, ["device_code", "device", "device_id", "设备", "设备编码"])
    metric_idx = _pick_column(cols, ["metric_name", "metric", "feature", "name", "指标"])
    val_idx = _pick_column(cols, ["value", "val", "reading", "数值", "值"])
    if dev_idx is None or metric_idx is None or val_idx is None:
        raise HTTPException(400,
            f"需要 device_code / metric_name / value 三列（实际: {cols}）")

    # 按设备分组
    groups: Dict[str, list] = {}
    errors = []
    for i, row in enumerate(rows):
        dev = str(row[dev_idx]).strip() if dev_idx < len(row) else ""
        metric = str(row[metric_idx]).strip() if metric_idx < len(row) else ""
        val = _to_float(row[val_idx] if val_idx < len(row) else None)
        if not dev or not metric or val is None:
            errors.append({"row": i, "raw": row, "reason": "设备/指标/数值缺失"})
            continue
        groups.setdefault(dev, []).append({"metric": metric, "value": val})

    if not groups:
        raise HTTPException(400, "无有效设备读数")

    t0 = time.time()
    try:
        from app.routers.fusion import get_fusion_model
        fm = get_fusion_model()
    except Exception as e:
        raise HTTPException(500, f"融合模块加载失败: {e}")

    per_device = []
    for i, (dev, items) in enumerate(groups.items()):
        sensor_data = {it["metric"]: it["value"] for it in items}
        try:
            r = fm.score(sensor_data)
            per_device.append({
                "device_code": dev,
                "sensor_data": sensor_data,
                "result": {
                    "risk_score": r.get("risk_score", 0),
                    "risk_level": r.get("risk_level", "LOW"),
                    "confidence": r.get("confidence", 0),
                    "model": r.get("model", "fusion-xgboost"),
                    "mock": r.get("mock", False),
                },
            })
        except Exception as e:
            errors.append({"device": dev, "reason": f"融合失败: {e}"})

    if not per_device:
        raise HTTPException(500, "所有设备融合推理均失败")

    return {
        "task": "fusion",
        "filename": file.filename,
        "n_devices": len(per_device),
        "per_device": per_device,
        "engine": "local:fusion",
        "inference_ms": round((time.time() - t0) * 1000, 2),
        "row_errors": errors[:20],
    }


@app.post("/batch/images", tags=["batch"])
async def batch_images(request: Request) -> Dict[str, Any]:
    """批量图像识别（一次处理多个文件，字段名固定为 files）。"""
    form = await request.form()
    files = form.getlist("files")
    if not files:
        raise HTTPException(422, "未找到上传文件（字段名需为 files）")
    if len(files) > 50:
        raise HTTPException(413, f"单次最多 50 张图片（上传了 {len(files)}）")

    results = []
    for f in files:
        try:
            img = await f.read()
            if len(img) > 8 * 1024 * 1024:
                results.append({"filename": f.filename, "error": "超过 8 MB"})
                continue
            # 优先 API
            ok = False
            try:
                import http.client
                boundary = "----WebFormBoundary" + os.urandom(8).hex()
                body = (
                    f"--{boundary}\r\n"
                    f"Content-Disposition: form-data; name=\"file\"; filename=\"{f.filename}\"\r\n"
                    f"Content-Type: {f.content_type or 'application/octet-stream'}\r\n\r\n"
                ).encode() + img + f"\r\n--{boundary}--\r\n".encode()
                host = SERVICE_URL.split("://", 1)[1].split(":", 1)[0]
                port = int(SERVICE_URL.rsplit(":", 1)[1].split("/", 1)[0])
                conn = http.client.HTTPConnection(host, port, timeout=10)
                conn.request("POST", "/api/ai/detect", body=body,
                             headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
                r = conn.getresponse()
                if r.status == 200:
                    j = json.loads(r.read())
                    j["engine"] = "FastAPI:" + SERVICE_URL
                    j["filename"] = f.filename
                    results.append(j)
                    ok = True
            except Exception:
                pass
            if not ok:
                from app.models.yolo_model import get_detector
                det = get_detector()
                j = det.detect(img)
                j["engine"] = "local:yolo"
                j["filename"] = f.filename
                results.append(j)
        except Exception as e:
            results.append({"filename": f.filename, "error": str(e)})

    n_detections = sum(len(r.get("detections") or r.get("boxes") or []) for r in results if "error" not in r)
    return {
        "task": "image-batch",
        "n_files": len(files),
        "n_success": sum(1 for r in results if "error" not in r),
        "n_total_detections": n_detections,
        "results": results,
    }


@app.post("/batch/social-media", tags=["batch"])
async def batch_social_media(file: UploadFile = File(...)) -> Dict[str, Any]:
    """上传纯文本文件 → 社交媒体灾害分析（每行一条）。"""
    raw = await file.read()
    if len(raw) > 4 * 1024 * 1024:
        raise HTTPException(413, "文本文件超过 4 MB")
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError:
        text = raw.decode("gbk", errors="replace")

    posts = [l.strip() for l in text.splitlines() if l.strip()]
    if not posts:
        raise HTTPException(400, "文件中没有有效文本行")

    t0 = time.time()
    overall = {}
    errors: list = []
    try:
        from app.routers.social_media import (
            get_analyzer, _extract_keywords, _analyze_alert_level,
            _analyze_sentiment,
        )
        analyzer = get_analyzer()
    except Exception as e:
        raise HTTPException(500, f"社交媒体分析模块加载失败: {e}")

    per_post = []
    text_items = [{"content": p} for p in posts[:500]]
    try:
        bulk = analyzer.analyze(text_items, bbox=None)
        overall = bulk.get("overall", {})
    except Exception as e:
        errors.append({"phase": "bulk", "reason": str(e)})

    # 逐条分析（直接复用底层函数，避免黑盒）
    n_disaster = 0
    for i, p in enumerate(posts[:500]):
        try:
            keywords = _extract_keywords(p)
            sentiment = _analyze_sentiment(p)
            alert_level = _analyze_alert_level(keywords, sentiment)
            is_disaster = len(keywords) > 0
            risk_level = _alert_level_to_word(alert_level)
            if is_disaster:
                n_disaster += 1
            per_post.append({
                "row_index": i,
                "text": p[:200],
                "result": {
                    "is_disaster_related": is_disaster,
                    "alert_level": alert_level,
                    "risk_level": risk_level,
                    "sentiment_score": round(sentiment, 3),
                    "matched_keywords": [k.get("keyword") if isinstance(k, dict) else k for k in keywords][:10],
                },
            })
        except Exception as e:
            per_post.append({"row_index": i, "text": p[:200], "error": str(e)})

    return {
        "task": "social_media",
        "filename": file.filename,
        "n_total": len(posts),
        "n_analyzed": len(per_post),
        "n_disaster_related": n_disaster,
        "overall": overall,
        "per_post": per_post,
        "engine": "local:social_media",
        "inference_ms": round((time.time() - t0) * 1000, 2),
        "row_errors": errors[:20],
    }


def _alert_level_to_word(level: int) -> str:
    return {0: "NORMAL", 1: "LOW", 2: "MEDIUM", 3: "HIGH", 4: "CRITICAL"}.get(int(level or 0), "NORMAL")


# ──────────────────────────────── 启动 ────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="DisasterGuard AI · 独立 Web UI")
    parser.add_argument("--host", default=WEB_HOST)
    parser.add_argument("--port", type=int, default=WEB_PORT)
    parser.add_argument("--reload", action="store_true",
                        help="开发模式自动重载（修改代码后无需手动重启）")
    parser.add_argument("--no-browser", action="store_true",
                        help="启动后不自动打开浏览器")
    args = parser.parse_args()

    url = f"http://{args.host}:{args.port}"
    print(f"\n🌐 DisasterGuard AI Web UI")
    print(f"   监听:   {url}")
    print(f"   ai 根:  {AI_ROOT}")
    print(f"   PID:    {os.getpid()}")
    print(f"   日志:   {LOG_FILE}")
    print(f"\n   打开浏览器访问: {url}\n")
    print(f"   按 Ctrl+C 退出\n")

    if not args.no_browser:
        # 延迟打开浏览器，等服务先起来
        def _open():
            import threading, webbrowser, time
            time.sleep(1.5)
            try:
                webbrowser.open(url)
            except Exception:
                pass
        threading.Thread(target=_open, daemon=True).start()

    uvicorn.run(
        "web:app",
        host=args.host,
        port=args.port,
        reload=args.reload,
        log_level=os.environ.get("AI_LOG_LEVEL", "info").lower(),
    )


if __name__ == "__main__":
    main()