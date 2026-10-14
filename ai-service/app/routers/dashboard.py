"""
AI Service Dashboard —— 浏览器可视化入口。

访问 http://127.0.0.1:8001/ 即可看到模块的实时状态、模型加载情况、
一键测试入口和 API 端点文档链接。

依赖: from zero 前端构建工具，纯 HTML + 原生 fetch()。
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict

from fastapi import APIRouter, Request
from fastapi.responses import HTMLResponse, JSONResponse

router = APIRouter(tags=["dashboard"])


# ───────────────────────────── HTML 模板 ─────────────────────────────

INDEX_HTML = r"""<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8" />
<title>DisasterGuard AI Service · Dashboard</title>
<meta name="viewport" content="width=device-width,initial-scale=1" />
<style>
  :root {
    --bg: #0b1020; --panel: #131a30; --panel2: #1b2340;
    --fg: #e6edf3; --muted: #8b94b0;
    --accent: #4fc3f7; --ok: #66bb6a; --warn: #ffa726; --err: #ef5350;
    --border: #243054;
  }
  * { box-sizing: border-box; }
  body { margin: 0; font: 14px/1.5 -apple-system, "Segoe UI", Roboto, "PingFang SC",
         "Microsoft YaHei", sans-serif; background: var(--bg); color: var(--fg); }
  header { padding: 18px 24px; background: linear-gradient(135deg,#1a2253,#0f1631);
           border-bottom: 1px solid var(--border); display: flex; align-items: center; gap: 12px; }
  header h1 { margin: 0; font-size: 18px; font-weight: 600; }
  header .pill { padding: 3px 10px; border-radius: 99px; font-size: 12px;
                 background: rgba(79,195,247,.15); color: var(--accent); }
  main { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; padding: 16px 24px 32px; }
  @media (max-width: 900px) { main { grid-template-columns: 1fr; } }
  .card { background: var(--panel); border: 1px solid var(--border); border-radius: 10px;
          padding: 14px 16px; }
  .card h2 { margin: 0 0 12px; font-size: 14px; color: var(--accent);
             text-transform: uppercase; letter-spacing: .04em; }
  .grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 8px 16px; font-size: 13px; }
  .kv { display: flex; justify-content: space-between; padding: 4px 0; border-bottom: 1px dashed #2a3258; }
  .kv:last-child { border: 0; }
  .kv .k { color: var(--muted); }
  .kv .v { font-family: ui-monospace, "SF Mono", Consolas, monospace; }
  .status-up { color: var(--ok); font-weight: 600; }
  .status-down { color: var(--err); font-weight: 600; }
  .status-warn { color: var(--warn); font-weight: 600; }
  .row { display: flex; align-items: center; gap: 8px; padding: 6px 0;
         border-bottom: 1px dashed #2a3258; }
  .row:last-child { border: 0; }
  .dot { width: 10px; height: 10px; border-radius: 50%; flex: 0 0 auto; }
  .dot.ok { background: var(--ok); box-shadow: 0 0 6px rgba(102,187,106,.6); }
  .dot.warn { background: var(--warn); }
  .dot.err { background: var(--err); }
  .name { flex: 1 1 auto; }
  .size { color: var(--muted); font-family: ui-monospace, monospace; font-size: 12px; }
  button { background: var(--accent); color: #0b1020; border: 0; border-radius: 6px;
           padding: 6px 12px; cursor: pointer; font-weight: 600; font-size: 13px; }
  button:hover { filter: brightness(1.1); }
  button.ghost { background: transparent; color: var(--accent); border: 1px solid var(--accent); }
  pre { background: #06091a; padding: 12px; border-radius: 8px; overflow: auto;
        font-size: 12px; max-height: 280px; border: 1px solid var(--border); }
  a { color: var(--accent); text-decoration: none; }
  a:hover { text-decoration: underline; }
  .endpoint-grid { display: grid; grid-template-columns: 80px 1fr auto; gap: 4px 12px;
                   align-items: center; font-size: 12px; font-family: ui-monospace, monospace; }
  .endpoint-grid .m { padding: 2px 6px; border-radius: 4px; text-align: center; font-weight: 700; }
  .endpoint-grid .m.get { background: rgba(102,187,106,.18); color: var(--ok); }
  .endpoint-grid .m.post { background: rgba(79,195,247,.18); color: var(--accent); }
  .endpoint-grid .m.put { background: rgba(255,167,38,.18); color: var(--warn); }
  .endpoint-grid .m.del { background: rgba(239,83,80,.18); color: var(--err); }
  .endpoint-grid .p { color: var(--fg); }
  .endpoint-grid a.t { font-size: 11px; color: var(--muted); }
  .actions { display: flex; gap: 8px; flex-wrap: wrap; }
  footer { color: var(--muted); font-size: 12px; padding: 16px 24px 24px; text-align: center; }
  .toast { position: fixed; right: 16px; top: 16px; background: var(--panel2);
           border: 1px solid var(--border); padding: 8px 12px; border-radius: 8px;
           font-size: 13px; opacity: 0; transition: opacity .2s; pointer-events: none; }
  .toast.show { opacity: 1; }
  .toast.err { border-color: var(--err); color: var(--err); }
  .toast.ok { border-color: var(--ok); color: var(--ok); }
</style>
</head>
<body>
<header>
  <h1>DisasterGuard AI Service</h1>
  <span class="pill">v3.0.0 · standalone</span>
  <span style="flex:1"></span>
  <button class="ghost" onclick="refreshAll()">↻ 刷新</button>
</header>

<main>
  <section class="card">
    <h2>① 服务健康</h2>
    <div class="kv"><span class="k">服务地址</span><span class="v" id="kv-url">—</span></div>
    <div class="kv"><span class="k">健康状态</span><span class="v" id="kv-health">—</span></div>
    <div class="kv"><span class="k">Service</span><span class="v" id="kv-service">—</span></div>
    <div class="kv"><span class="k">降级路由</span><span class="v" id="kv-degraded">—</span></div>
    <div class="kv"><span class="k">查询时间</span><span class="v" id="kv-queried">—</span></div>
    <h2 style="margin-top:18px">② 模型加载</h2>
    <div id="models"></div>
  </section>

  <section class="card">
    <h2>③ 一键测试</h2>
    <p style="color:var(--muted); margin:0 0 12px;">
      点击按钮直接调 AI 端点查看响应（无需 curl 或 Postman）。
    </p>
    <div class="actions">
      <button onclick="testEndpoint('GET','/api/ai/health')">GET /health</button>
      <button onclick="testEndpoint('GET','/api/ai/model/health')">GET /model/health</button>
      <button onclick="testEndpoint('POST','/api/ai/fusion-risk', fusionSample)">POST /fusion-risk</button>
      <button onclick="testEndpoint('POST','/api/ai/anomaly', anomalySample)">POST /anomaly</button>
      <button onclick="testEndpoint('POST','/api/ai/forecast', forecastSample)">POST /forecast</button>
      <button onclick="testEndpoint('POST','/api/ai/comprehensive', comprehensiveSample)">POST /comprehensive</button>
      <button onclick="testEndpoint('GET','/api/ai/model/info')">GET /model/info</button>
    </div>
    <pre id="test-out">（点击上方按钮查看响应）</pre>
  </section>

  <section class="card" style="grid-column: 1 / -1;">
    <h2>④ API 端点速查</h2>
    <div class="endpoint-grid">
      <span class="m get">GET</span><span class="p">/</span><a class="t" target="_blank" href="/api/ai/health">health →</a>
      <span class="m get">GET</span><span class="p">/api/ai/health</span><a class="t" target="_blank" href="/api/ai/health">→</a>
      <span class="m get">GET</span><span class="p">/api/ai/model/info</span><a class="t" target="_blank" href="/api/ai/model/info">→</a>
      <span class="m get">GET</span><span class="p">/api/ai/model/health</span><a class="t" target="_blank" href="/api/ai/model/health">→</a>
      <span class="m post">POST</span><span class="p">/api/ai/detect</span><span class="t">图像识别</span>
      <span class="m post">POST</span><span class="p">/api/ai/fusion-risk</span><span class="t">设备/传感器融合</span>
      <span class="m post">POST</span><span class="p">/api/ai/anomaly</span><span class="t">异常检测</span>
      <span class="m post">POST</span><span class="p">/api/ai/forecast</span><span class="t">时序预测</span>
      <span class="m post">POST</span><span class="p">/api/ai/comprehensive</span><span class="t">综合风险</span>
      <span class="m post">POST</span><span class="p">/api/ai/disaster-assessment</span><span class="t">灾害类型评估</span>
      <span class="m post">POST</span><span class="p">/api/ai/social-media</span><span class="t">社交媒体分析</span>
      <span class="m post">POST</span><span class="p">/api/ai/device-health</span><span class="t">设备健康评分</span>
      <span class="m get">GET</span><span class="p">/api/ai/drift/*</span><span class="t">模型漂移检测</span>
      <span class="m get">GET</span><span class="p">/api/ai/multimodal/*</span><span class="t">多模态融合</span>
      <span class="m post">POST</span><span class="p">/api/ai/model/reload</span><span class="t">重新加载模型</span>
      <span class="m post">POST</span><span class="p">/api/ai/model/retrain</span><span class="t">重新训练模型</span>
      <span class="m get">GET</span><span class="p">/docs</span><a class="t" target="_blank" href="/docs">Swagger UI →</a>
      <span class="m get">GET</span><span class="p">/redoc</span><a class="t" target="_blank" href="/redoc">ReDoc →</a>
    </div>
  </section>
</main>

<footer>
  本页是 ai-service 模块内置 dashboard，无第三方依赖 ·
  数据来自 <code>/api/ai/health</code> + <code>/api/ai/model/info</code>
</footer>

<div class="toast" id="toast"></div>

<script>
const BASE = window.location.origin;
document.getElementById('kv-url').textContent = BASE;

const fusionSample = {
  hazard_code: 'TEST_DASH',
  factors: { displacement_rate: 12, rainfall_24h: 150, crack_width: 25, earthquake_mag: 5.0 }
};
const anomalySample = {
  device_code: 'TEST_DASH',
  data: [
    [1.0, 1.0, 0.5, 2.0, 1.0],
    [1.2, 1.1, 0.6, 2.1, 1.1],
    [25.0, 35.0, 8.0, 100.0, 30.0],
    [1.1, 1.0, 0.5, 2.2, 1.0],
  ]
};
const forecastSample = {
  device_code: 'TEST_DASH',
  history: [1.0, 1.5, 2.0, 2.8, 3.5, 4.5, 5.8, 7.5],
  horizon: 5,
};
const comprehensiveSample = {
  hazard_code: 'TEST_LANDSLIDE',
  factors: {
    rainfall_24h: 95, rainfall_7d: 240, soil_moisture: 0.50,
    slope_deg: 35, vegetation_cover: 0.2, soil_grade: 4,
    displacement_rate: 12, crack_width: 22,
    image_anomaly_score: 65, historical_count: 4,
  }
};

function toast(msg, kind) {
  const el = document.getElementById('toast');
  el.textContent = msg;
  el.className = 'toast show ' + (kind || '');
  setTimeout(() => el.className = 'toast', 1800);
}

async function fetchJSON(path) {
  const r = await fetch(BASE + path);
  const t = await r.text();
  try { return { status: r.status, body: JSON.parse(t) }; }
  catch { return { status: r.status, body: t }; }
}

function fmtSize(b) {
  if (!b) return '';
  if (b > 1024*1024) return (b/1024/1024).toFixed(1) + ' MB';
  if (b > 1024) return Math.round(b/1024) + ' KB';
  return b + ' B';
}

function renderModels(body) {
  const div = document.getElementById('models');
  div.innerHTML = '';
  const items = Array.isArray(body.models) ? body.models : [];
  if (!items.length) {
    div.innerHTML = '<div class="row">（无模型数据）</div>';
    return;
  }
  for (const m of items) {
    const onDisk = m.exists_on_disk;
    const cls = onDisk ? 'ok' : 'err';
    const row = document.createElement('div');
    row.className = 'row';
    row.innerHTML = `
      <span class="dot ${cls}"></span>
      <span class="name">${m.name || '?'}</span>
      <span class="size">${fmtSize(m.size_bytes)}${m.trained_at ? ' · ' + new Date(m.trained_at).toLocaleString() : ''}</span>
    `;
    div.appendChild(row);
  }
}

async function refreshAll() {
  try {
    const h = await fetchJSON('/api/ai/health');
    if (h.status === 200) {
      const b = h.body;
      const deg = b.unregistered_routers || {};
      document.getElementById('kv-health').innerHTML =
        '<span class="status-up">' + (b.status || '?') + '</span>';
      document.getElementById('kv-service').textContent = b.service || '?';
      document.getElementById('kv-degraded').textContent =
        Object.keys(deg).length ? JSON.stringify(deg) : '（无降级）';
      document.getElementById('kv-queried').textContent = new Date().toLocaleTimeString();
    } else {
      document.getElementById('kv-health').innerHTML =
        '<span class="status-down">HTTP ' + h.status + '</span>';
    }
  } catch (e) {
    document.getElementById('kv-health').innerHTML =
      '<span class="status-down">不可达</span>';
  }

  try {
    const m = await fetchJSON('/api/ai/model/info');
    if (m.status === 200) renderModels(m.body);
    else document.getElementById('models').innerHTML =
      '<div class="row"><span class="dot warn"></span> /model/info HTTP ' + m.status + '</div>';
  } catch (e) {
    document.getElementById('models').innerHTML =
      '<div class="row"><span class="dot err"></span> ' + e.message + '</div>';
  }
}

async function testEndpoint(method, path, body) {
  const out = document.getElementById('test-out');
  out.textContent = '请求 ' + method + ' ' + path + ' …';
  try {
    const opts = { method, headers: {} };
    if (body) {
      opts.headers['Content-Type'] = 'application/json';
      opts.body = JSON.stringify(body);
    }
    const r = await fetch(BASE + path, opts);
    const t = await r.text();
    let pretty = t;
    try { pretty = JSON.stringify(JSON.parse(t), null, 2); } catch {}
    out.textContent = '← HTTP ' + r.status + '\n' + pretty;
    toast(method + ' ' + path + '  HTTP ' + r.status, r.ok ? 'ok' : 'err');
  } catch (e) {
    out.textContent = '异常: ' + e.message;
    toast('异常: ' + e.message, 'err');
  }
}

refreshAll();
setInterval(refreshAll, 15000);
</script>
</body>
</html>
"""


# ───────────────────────────── 路由 ─────────────────────────────

@router.get("/", response_class=HTMLResponse, tags=["dashboard"])
async def index() -> HTMLResponse:
    """Dashboard 首页：模块状态 + 模型列表 + 一键测试。"""
    return HTMLResponse(content=INDEX_HTML, status_code=200)


@router.get("/api/ai/dashboard/summary", tags=["dashboard"])
async def dashboard_summary() -> JSONResponse:
    """
    一站式 summary：合并 health + model/info 的关键字段，避免前端两次往返。
    直接走模块内部调用，零 HTTP 开销。
    """
    from datetime import datetime, timezone

    # health 走真正的路由函数（避免重复声明降级状态）
    from app.main import health as _health_fn
    health = _health_fn()

    summary: Dict[str, Any] = {
        "queried_at": datetime.now(timezone.utc).isoformat(),
        "health": health,
    }

    # 直接读取模型元数据 + 内存加载状态，避免 HTTP 自调
    try:
        from app.models.xgboost_model import get_fusion_model
        from app.models.comprehensive_model import get_comprehensive_model
        from app.routers.model_info import (
            FUSION_META_PATH, COMPREHENSIVE_META_PATH,
            LSTM_META_PATH, IFOREST_META_PATH,
        )
        import json as _json
        from pathlib import Path as _Path
        from app.config import MODELS_DIR

        fusion = get_fusion_model()
        comprehensive = get_comprehensive_model()

        def _read_meta(p: _Path) -> dict:
            if not p.exists():
                return {}
            try:
                return _json.loads(p.read_text(encoding="utf-8"))
            except Exception:  # noqa: BLE001
                return {}

        models_dir = MODELS_DIR
        all_models = []
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
            all_models.append(info)

        summary["model_info"] = {
            "models": all_models,
            "fusion_model": {
                "model_loaded": fusion.is_loaded(),
                "model_path": str(fusion.model_path),
            },
            "comprehensive_model": {
                "model_loaded": comprehensive.is_loaded(),
                "model_path": str(comprehensive.model_path),
            },
        }
    except Exception as e:  # noqa: BLE001
        summary["model_info"] = {"error": str(e)}

    return JSONResponse(summary)