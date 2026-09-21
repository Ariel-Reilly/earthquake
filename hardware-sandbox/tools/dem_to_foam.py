#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
真实 DEM -> 泡沫分层切割图（重庆山地实体沙盘 · 地形加工工具）

用途
----
把一段真实高程数据（重庆渝中半岛等）按给定比尺换算，输出：
  1. 分层轮廓 SVG（每层一张，mm 单位，可直接打印描线 / 送激光切割 / 绘图仪）
  2. 层表 CSV（层号、阈值高程、板厚、累计高度、岩性、配色、轮廓面积）
  3. 叠加预览 HTML（浏览器打开即可检查地形是否合理）
  4. 可选地形 STL（3D 打印 / CNC 备选方案）

用法
----
  # 1) 无 DEM 时先用合成地形验证脚本（离线自检）
  python dem_to_foam.py --synthetic --out ./out

  # 2) 真实 GeoTIFF（需 pip install rasterio）
  python dem_to_foam.py --dem chongqing.tif --bbox 106.515 29.5425 106.545 29.56 --out ./out

  # 3) ASCII Grid（*.asc，纯 numpy 可解析，零额外依赖）
  python dem_to_foam.py --dem chongqing.asc --out ./out

  # 4) 在线地形瓦片快速预览（需 pip install pillow；分辨率有限，仅用于粗看）
  python dem_to_foam.py --fetch --bbox 106.515 29.5425 106.545 29.56 --out ./out

  # 5) 额外导出 STL
  python dem_to_foam.py --dem chongqing.asc --out ./out --stl

可选依赖：numpy(必需) / rasterio(读 GeoTIFF) / pillow(在线瓦片) / matplotlib(剖面图)

WARNING: 泡沫材料禁止激光切割（熔化 + 释放苯乙烯等有害气体）。
         本工具输出的图纸用于手工刀 / 热丝 / 线锯 / CNC 冷加工。
"""

from __future__ import annotations

import argparse
import csv
import math
import os
import sys
import urllib.request

import numpy as np

# ---------------------------------------------------------------- 默认参数

# 重庆渝中半岛：李子坝 — 佛图关 — 嘉陵江段
DEFAULT_BBOX = (106.5150, 29.5425, 106.5450, 29.5600)

# 沙盘几何
DEFAULT_WIDTH_MM = 1200.0   # 沙盘东西向（对应经度）
DEFAULT_DEPTH_MM = 800.0    # 沙盘南北向（对应纬度）
DEFAULT_LAYER_MM = 20.0     # 泡沫板厚度（XPS 常见 20/25/30/50mm；越薄层数越多越细腻）
DEFAULT_GRID_MM = 2.0       # 高程网格分辨率（mm/格），越小越精细越慢
DEFAULT_VE = 2.5            # 垂直夸张系数（地形沙盘常规 2~5）
DEFAULT_DEM_NOTE = "渝中半岛李子坝—佛图关—嘉陵江段"

# 重庆红层（侏罗系沙溪庙组）砂泥岩互层配色，自底向上循环
STRATA = [
    ("泥岩夹粉砂岩", "#7E4433"),
    ("砂岩", "#B5836A"),
    ("泥岩", "#8A4F3C"),
    ("砂岩厚层", "#C09176"),
]
TOPSOIL = ("第四系覆盖层", "#9C7A4E")  # 最顶层


# ---------------------------------------------------------------- DEM 载入

def load_synthetic(nx=160, ny=107):
    """合成地形：用于脚本离线自检（河谷 + 台地 + 山脊，模拟重庆两江山地）。"""
    x = np.linspace(0.0, 1.0, nx)[None, :]
    y = np.linspace(0.0, 1.0, ny)[:, None]
    # 嘉陵江河谷：沿 y 小侧自东向西下切
    valley = np.exp(-((y - 0.12) ** 2) / (2 * 0.10 ** 2)) * 140.0
    # 李子坝台地
    terrace = np.exp(-((x - 0.45) ** 2 + (y - 0.45) ** 2) / (2 * 0.22 ** 2)) * 90.0
    # 佛图关山脊
    ridge = np.exp(-((x - 0.78) ** 2 + (y - 0.72) ** 2) / (2 * 0.20 ** 2)) * 180.0
    # 次级冲沟 + 微起伏
    gully = np.sin(x * 9.0) * np.cos(y * 7.0) * 8.0
    z = 160.0 + valley * 0.0 + terrace + ridge + gully - valley * 0.55
    return z.astype(np.float64), DEFAULT_BBOX


def load_asc(path):
    """解析 ESRI ASCII Grid（*.asc）。零额外依赖。"""
    with open(path, "r", encoding="utf-8", errors="ignore") as f:
        head = {}
        for _ in range(6):
            line = f.readline()
            if not line:
                break
            parts = line.split()
            if len(parts) >= 2:
                head[parts[0].lower()] = float(parts[1])
        z = np.loadtxt(f, dtype=np.float64)
    z = np.flipud(z)  # ASC 自北向南存，翻转为自南向北
    ncols = int(head.get("ncols", z.shape[1]))
    nrows = int(head.get("nrows", z.shape[0]))
    z = z[:nrows, :ncols]
    z = np.where(z == head.get("nodata_value", -9999.0), np.nan, z)
    if np.isnan(z).any():
        z = _fill_nan(z)
    cell = head.get("cellsize", 30.0)
    xll = head.get("xllcorner", 0.0)
    yll = head.get("yllcorner", 0.0)
    bbox = (xll, yll, xll + cell * ncols, yll + cell * nrows)
    return z, bbox


def load_tif(path, bbox):
    """读取 GeoTIFF 并按 bbox 裁剪（需要 rasterio）。"""
    try:
        import rasterio
    except ImportError:
        sys.exit("[ERR] 读取 GeoTIFF 需要 rasterio：pip install rasterio\n"
                 "      或将 DEM 转成 ASCII Grid（*.asc）/ npy 后重试。")
    with rasterio.open(path) as ds:
        if bbox is None:
            z = ds.read(1).astype(np.float64)
            bbox = (ds.bounds.left, ds.bounds.bottom, ds.bounds.right, ds.bounds.top)
        else:
            from rasterio.windows import from_bounds
            win = from_bounds(*bbox, transform=ds.transform)
            z = ds.read(1, window=win).astype(np.float64)
        nodata = ds.nodata
    if nodata is not None:
        z = np.where(z == nodata, np.nan, z)
        z = _fill_nan(z)
    return z, bbox


def sanitize(z, valid_min=-200.0, valid_max=9000.0, passes=6):
    """
    剔除 terrarium 瓦片的 NoData / 坏像素。

    实测该数据源会随机混入 -32768 ~ -1000 的荒谬值（约 0.1%）。若不处理，
    高程区间会被拉成 4000 m 量级，等高线间距、泡沫分层、Revit 点文件全部失效。
    处理：4 邻域有效值均值迭代填充，孤立坏点退化为全局中位数。
    """
    bad = ~((z >= valid_min) & (z <= valid_max)) | np.isnan(z)
    n = int(bad.sum())
    if n == 0:
        return z
    z = z.copy()
    print(f"[INFO] 清洗异常高程像素 {n} 个（{100.0 * n / z.size:.3f}%）")
    for _ in range(passes):
        if not bad.any():
            break
        zp = np.pad(np.where(bad, 0.0, z), 1, mode="edge")
        okp = np.pad((~bad).astype(float), 1, mode="constant", constant_values=0.0)
        s = zp[:-2, 1:-1] + zp[2:, 1:-1] + zp[1:-1, :-2] + zp[1:-1, 2:]
        c = okp[:-2, 1:-1] + okp[2:, 1:-1] + okp[1:-1, :-2] + okp[1:-1, 2:]
        m = bad & (c > 0)
        z[m] = s[m] / c[m]
        bad = bad & (c == 0)
    if bad.any():
        good = z[~bad]
        z[bad] = float(np.median(good)) if good.size else 0.0
    return z


def load_tiles(bbox, zoom=14):
    """在线地形瓦片（terrarium 编码）。下载后按 bbox 精确裁剪并清洗坏像素。"""
    try:
        from PIL import Image
    except ImportError:
        sys.exit("[ERR] 在线瓦片需要 pillow：pip install pillow")

    lon1, lat1, lon2, lat2 = bbox

    def deg2tile(lon, lat, z):
        n = 2 ** z
        x = int((lon + 180.0) / 360.0 * n)
        lat_r = math.radians(lat)
        y = int((1.0 - math.log(math.tan(lat_r) + 1 / math.cos(lat_r)) / math.pi) / 2.0 * n)
        return x, y

    x1, y1 = deg2tile(lon1, lat2, zoom)
    x2, y2 = deg2tile(lon2, lat1, zoom)
    nx_t, ny_t = x2 - x1 + 1, y2 - y1 + 1
    print(f"[INFO] 下载地形瓦片 zoom={zoom} {nx_t}x{ny_t} 张 ...")

    rows = []
    for ty in range(y1, y2 + 1):
        row = []
        for tx in range(x1, x2 + 1):
            url = f"https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{zoom}/{tx}/{ty}.png"
            try:
                with urllib.request.urlopen(url, timeout=20) as r:
                    from io import BytesIO
                    img = Image.open(BytesIO(r.read())).convert("RGB")
                a = np.asarray(img).astype(np.float64)
                # terrarium: h = R*256 + G + B/256 - 32768
                row.append(a[:, :, 0] * 256.0 + a[:, :, 1] + a[:, :, 2] / 256.0 - 32768.0)
            except Exception as e:
                print(f"[WARN] 瓦片 {zoom}/{tx}/{ty} 获取失败: {e}")
                row.append(np.zeros((256, 256)))
        rows.append(np.hstack(row))
    z = np.vstack(rows)

    # --- 按 bbox 精确裁剪：瓦片覆盖范围通常大于 bbox，
    #     不裁会让整幅地形相对经纬度偏移（实测 768px≈3.2km vs bbox 2.9km，错位约 10%）
    def deg2pix(lon, lat, zz):
        n = 2.0 ** zz
        px = (lon + 180.0) / 360.0 * n
        lat_r = math.radians(lat)
        py = (1.0 - math.log(math.tan(lat_r) + 1 / math.cos(lat_r)) / math.pi) / 2.0 * n
        return px, py

    px1, py1 = deg2pix(lon1, lat2, zoom)      # 西北角
    px2, py2 = deg2pix(lon2, lat1, zoom)      # 东南角
    ox, oy = x1 * 256.0, y1 * 256.0
    j0, j1 = max(int(round(px1 - ox)), 0), min(int(round(px2 - ox)), z.shape[1])
    i0, i1 = max(int(round(py1 - oy)), 0), min(int(round(py2 - oy)), z.shape[0])
    if j1 > j0 and i1 > i0:
        z = z[i0:i1, j0:j1]
    print(f"[INFO] 裁剪到 bbox：{z.shape[1]}x{z.shape[0]} 像素")

    z = sanitize(z)
    return z, bbox


def _fill_nan(z):
    """NaN 用有效值的最小值填充（避免轮廓出现空洞，且不改变地形趋势）。"""
    if not np.isnan(z).any():
        return z
    fill = np.nanmin(z)
    return np.where(np.isnan(z), fill, z)


# ---------------------------------------------------------------- 网格重采样

def resample(z, out_nx, out_ny):
    """双线性重采样到目标网格（仅 numpy）。"""
    ny, nx = z.shape
    xs = np.linspace(0, nx - 1, out_nx)
    ys = np.linspace(0, ny - 1, out_ny)
    x0 = np.floor(xs).astype(int)
    x1 = np.minimum(x0 + 1, nx - 1)
    fx = xs - x0
    tmp = np.empty((ny, out_nx))
    for i in range(ny):
        row = z[i]
        tmp[i] = row[x0] * (1 - fx) + row[x1] * fx
    y0 = np.floor(ys).astype(int)
    y1 = np.minimum(y0 + 1, ny - 1)
    fy = (ys - y0)[:, None]
    return tmp[y0, :] * (1 - fy) + tmp[y1, :] * fy


def smooth(z, passes=1):
    """3x3 均值平滑，抑制 DEM 噪声导致的锯齿轮廓。"""
    out = z.astype(np.float64).copy()
    for _ in range(passes):
        p = np.pad(out, 1, mode="edge")
        out = (p[:-2, :-2] + p[:-2, 1:-1] + p[:-2, 2:] +
               p[1:-1, :-2] + p[1:-1, 1:-1] + p[1:-1, 2:] +
               p[2:, :-2] + p[2:, 1:-1] + p[2:, 2:]) / 9.0
    return out


# ---------------------------------------------------------------- Marching Squares

def _interp(v0, v1, t):
    d = v1 - v0
    if abs(d) < 1e-12:
        return 0.5
    return float(np.clip((t - v0) / d, 0.0, 1.0))


# case 表：bit0=左上(TL) bit1=右上(TR) bit2=右下(BR) bit3=左下(BL)
# 每条边记作 (i, j, 0=水平边 / 1=垂直边)，用整数做 key 可完全避免浮点匹配误差：
#   水平边 (i,j) = 格点(i,j)--(i,j+1) 之间   → 同时是 cell(i,j) 的 top 与 cell(i-1,j) 的 bottom
#   垂直边 (i,j) = 格点(i,j)--(i+1,j) 之间   → 同时是 cell(i,j) 的 left 与 cell(i,j-1) 的 right
_MS_TABLE = {
    1:  ("L", "T"), 2: ("T", "R"), 3: ("L", "R"), 4: ("R", "B"),
    5:  ("L", "T", "R", "B"), 6: ("T", "B"), 7: ("L", "B"),
    8:  ("B", "L"), 9: ("B", "T"), 10: ("T", "R", "B", "L"),
    11: ("B", "R"), 12: ("R", "L"), 13: ("R", "T"), 14: ("T", "L"),
}


def contour_polygons(z, xs, ys, threshold):
    """
    Marching Squares：提取 z >= threshold 的区域边界（闭合成环）。
    返回多边形列表，每个多边形是 [(x_mm, y_mm), ...]。
    """
    ny, nx = z.shape
    if ny < 2 or nx < 2:
        return []

    # 向量化求 case，只对混合 cell 做精确处理（避免 O(n^2) 纯 Python 循环）
    a, b, c, d = z[:-1, :-1], z[:-1, 1:], z[1:, 1:], z[1:, :-1]
    case = ((a >= threshold).astype(np.uint8)
            | ((b >= threshold).astype(np.uint8) << 1)
            | ((c >= threshold).astype(np.uint8) << 2)
            | ((d >= threshold).astype(np.uint8) << 3))
    mixed = np.argwhere((case != 0) & (case != 15))
    if mixed.size == 0:
        return []

    dx = xs[1] - xs[0]
    dy = ys[1] - ys[0]
    segs = []
    for i_raw, j_raw in mixed:
        i, j = int(i_raw), int(j_raw)
        v00, v01 = z[i, j], z[i, j + 1]
        v11, v10 = z[i + 1, j + 1], z[i + 1, j]
        # key: (行, 列, 0=水平 / 1=垂直)；pt: (x, y)
        top = ((i, j, 0), (xs[j] + dx * _interp(v00, v01, threshold), ys[i]))
        bottom = ((i + 1, j, 0), (xs[j] + dx * _interp(v10, v11, threshold), ys[i + 1]))
        left = ((i, j, 1), (xs[j], ys[i] + dy * _interp(v00, v10, threshold)))
        right = ((i, j + 1, 1), (xs[j + 1], ys[i] + dy * _interp(v01, v11, threshold)))
        node = {"T": top, "B": bottom, "L": left, "R": right}

        seq = _MS_TABLE[int(case[i, j])]
        for k in range(0, len(seq), 2):
            ka, pa = node[seq[k]]
            kb, pb = node[seq[k + 1]]
            if ka != kb:
                segs.append((ka, kb, pa, pb))

    return _link_segments(segs)


def _link_segments(segs):
    """把零散线段连接成折线/闭合环。同一条无向边只建一个 entry，供两端共享标记。"""
    if not segs:
        return []

    edges = {}
    adj = {}
    for ka, kb, pa, pb in segs:
        k = (ka, kb) if ka <= kb else (kb, ka)
        if k in edges:
            continue
        e = [k[0], k[1], (pa, pb) if k == (ka, kb) else (pb, pa), False]
        edges[k] = e
        adj.setdefault(k[0], []).append(e)
        adj.setdefault(k[1], []).append(e)

    polys = []
    for start in list(adj.keys()):
        for e in adj[start]:
            if e[3]:
                continue
            e[3] = True
            cur = e[1] if e[0] == start else e[0]
            pts = [e[2][0], e[2][1]] if e[0] == start else [e[2][1], e[2][0]]
            while True:
                nxt = None
                for e2 in adj.get(cur, []):
                    if not e2[3]:
                        nxt = e2
                        break
                if nxt is None:
                    break
                nxt[3] = True
                end = nxt[1] if nxt[0] == cur else nxt[0]
                pts.append(nxt[2][1] if nxt[0] == cur else nxt[2][0])
                cur = end
                if cur == start:
                    break
            if len(pts) >= 3:
                polys.append(pts)
    return polys


def polygon_area(poly):
    """鞋带公式，mm^2。"""
    s = 0.0
    for i in range(len(poly)):
        x1, y1 = poly[i]
        x2, y2 = poly[(i + 1) % len(poly)]
        s += x1 * y2 - x2 * y1
    return abs(s) / 2.0


# ---------------------------------------------------------------- 输出

def _one_path(polys, width_mm, depth_mm, border_in):
    """
    把所有轮廓合成单个 <path> 的 d（多个 M..Z 子路径）。

    必须合成到一个 path：fill-rule="evenodd" 只在**同一 path 的多个子路径之间**
    才会相互挖空；拆成多个 <path> 时各自独立填充，孔洞不会被挖掉。

    border_in=True 表示实体一直延伸到图幅边缘，此时 marching squares 不会产生外环，
    需先铺一整幅矩形作为外边界，内部环才会被 evenodd 挖空。
    """
    subs = []
    if border_in:
        subs.append(f"M 0,0 L {width_mm:.2f},0 L {width_mm:.2f},{depth_mm:.2f} "
                    f"L 0,{depth_mm:.2f} Z")
    for poly in polys:
        subs.append("M " + " L ".join(f"{x:.2f},{y:.2f}" for x, y in poly) + " Z")
    return " ".join(subs)


def write_layer_svg(path, polys, width_mm, depth_mm, color, name, elev,
                    thickness_mm, full_block=False, border_in=True):
    d = _one_path(polys, width_mm, depth_mm, border_in)
    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width_mm:.1f}mm" '
        f'height="{depth_mm:.1f}mm" viewBox="0 0 {width_mm:.1f} {depth_mm:.1f}">',
        f'<title>{name} layer elev={elev:.1f}m</title>',
        '<rect width="100%" height="100%" fill="#ffffff"/>',
        f'<path d="{d}" fill="{color}" fill-rule="evenodd" '
        'stroke="#333333" stroke-width="0.4"/>',
    ]
    # 边框 + 标注
    parts.append(f'<rect x="0" y="0" width="{width_mm:.1f}" height="{depth_mm:.1f}" '
                 'fill="none" stroke="#000" stroke-width="0.8"/>')
    tag = "  [整块板，无需切割轮廓]" if full_block else ""
    parts.append(f'<text x="8" y="18" font-size="12" fill="#000">'
                 f'L{name}  z>={elev:.1f}m  t={thickness_mm:.1f}mm{tag}</text>')
    parts.append("</svg>")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(parts))


def write_preview_html(path, layers, width_mm, depth_mm, meta):
    """所有层叠加预览（半透明），浏览器直接打开检查地形是否合理。"""
    w = min(1200, int(width_mm))
    h = int(w * depth_mm / width_mm)
    body = [
        "<!doctype html><html lang='zh-CN'><head><meta charset='utf-8'>",
        "<title>沙盘泡沫分层预览</title>",
        "<style>body{font-family:system-ui,'Microsoft YaHei',sans-serif;margin:20px;background:#fafafa}"
        "svg{background:#fff;border:1px solid #ccc;max-width:100%}"
        "table{border-collapse:collapse;margin-top:16px;font-size:13px}"
        "td,th{border:1px solid #ddd;padding:4px 10px}</style></head><body>",
        f"<h2>沙盘泡沫分层预览</h2>",
        f"<p>{meta}</p>",
        f"<svg viewBox='0 0 {width_mm} {depth_mm}' width='{w}' height='{h}'>",
        "<rect width='100%' height='100%' fill='#fff'/>",
    ]
    for lay in layers:
        d = _one_path(lay["polys"], width_mm, depth_mm, lay.get("border", True))
        body.append(f"<path d='{d}' fill='{lay['color']}' fill-opacity='0.55' "
                    "fill-rule='evenodd' stroke='#333' stroke-width='0.3'/>")
    body.append("</svg>")
    body.append("<table><tr><th>层</th><th>阈值高程(m)</th><th>板厚(mm)</th>"
                "<th>累计(mm)</th><th>岩性</th><th>轮廓面积(cm²)</th><th>整块板</th></tr>")
    for lay in layers:
        body.append(f"<tr><td>L{lay['idx']:02d}</td><td>{lay['elev']:.1f}</td>"
                    f"<td>{lay['thickness']:.1f}</td><td>{lay['stack']:.1f}</td>"
                    f"<td style='background:{lay['color']}'>{lay['name']}</td>"
                    f"<td>{lay['area']/100:.1f}</td>"
                    f"<td>{'是' if lay.get('full') else '否'}</td></tr>")
    body.append("</table></body></html>")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(body))


def write_stl(path, z_mm, width_mm, depth_mm):
    """高度场 -> 二进制 STL（CNC / 3D 打印备选）。"""
    ny, nx = z_mm.shape
    xs = np.linspace(0, width_mm, nx)
    ys = np.linspace(0, depth_mm, ny)
    tris = []

    def add(p1, p2, p3):
        tris.append((p1, p2, p3))

    for i in range(ny - 1):
        for j in range(nx - 1):
            x0, x1 = xs[j], xs[j + 1]
            y0, y1 = ys[i], ys[i + 1]
            a = (x0, y0, z_mm[i, j])
            b = (x1, y0, z_mm[i, j + 1])
            c = (x1, y1, z_mm[i + 1, j + 1])
            d = (x0, y1, z_mm[i + 1, j])
            add(a, b, c)
            add(a, c, d)
    # 侧面裙边 + 底面
    base = 0.0
    for j in range(nx - 1):
        add((xs[j], 0, z_mm[0, j]), (xs[j + 1], 0, z_mm[0, j + 1]), (xs[j + 1], 0, base))
        add((xs[j], 0, z_mm[0, j]), (xs[j + 1], 0, base), (xs[j], 0, base))
        add((xs[j], depth_mm, z_mm[-1, j]), (xs[j], depth_mm, base), (xs[j + 1], depth_mm, base))
        add((xs[j], depth_mm, z_mm[-1, j]), (xs[j + 1], depth_mm, base), (xs[j + 1], depth_mm, z_mm[-1, j + 1]))
    for i in range(ny - 1):
        add((0, ys[i], z_mm[i, 0]), (0, ys[i], base), (0, ys[i + 1], base))
        add((0, ys[i], z_mm[i, 0]), (0, ys[i + 1], base), (0, ys[i + 1], z_mm[i + 1, 0]))
        add((width_mm, ys[i], z_mm[i, -1]), (width_mm, ys[i + 1], z_mm[i + 1, -1]), (width_mm, ys[i + 1], base))
        add((width_mm, ys[i], z_mm[i, -1]), (width_mm, ys[i + 1], base), (width_mm, ys[i], base))
    add((0, 0, base), (width_mm, 0, base), (width_mm, depth_mm, base))
    add((0, 0, base), (width_mm, depth_mm, base), (0, depth_mm, base))

    import struct
    with open(path, "wb") as f:
        f.write(b"\0" * 80)
        f.write(struct.pack("<I", len(tris)))
        for p1, p2, p3 in tris:
            n = np.cross(np.subtract(p2, p1), np.subtract(p3, p1))
            ln = np.linalg.norm(n)
            n = n / ln if ln > 0 else np.array([0.0, 0.0, 1.0])
            f.write(struct.pack("<3f", *n))
            for p in (p1, p2, p3):
                f.write(struct.pack("<3f", *p))
            f.write(struct.pack("<H", 0))


# ---------------------------------------------------------------- 主流程

def main():
    ap = argparse.ArgumentParser(description="真实 DEM -> 泡沫分层切割图")
    src = ap.add_mutually_exclusive_group()
    src.add_argument("--dem", help="本地 DEM：*.tif（需 rasterio）或 *.asc（零依赖）")
    src.add_argument("--fetch", action="store_true", help="在线地形瓦片（需 pillow，分辨率有限）")
    src.add_argument("--synthetic", action="store_true", help="合成地形，用于离线自检")
    ap.add_argument("--bbox", nargs=4, type=float, metavar=("LON1", "LAT1", "LON2", "LAT2"),
                    help=f"范围，默认重庆渝中半岛 {DEFAULT_BBOX}")
    ap.add_argument("--out", default="./sandbox_foam_out", help="输出目录")
    ap.add_argument("--width-mm", type=float, default=DEFAULT_WIDTH_MM)
    ap.add_argument("--depth-mm", type=float, default=DEFAULT_DEPTH_MM)
    ap.add_argument("--layer-mm", type=float, default=DEFAULT_LAYER_MM, help="泡沫板厚度")
    ap.add_argument("--grid-mm", type=float, default=DEFAULT_GRID_MM, help="网格分辨率 mm/格")
    ap.add_argument("--ve", type=float, default=DEFAULT_VE, help="垂直夸张系数")
    ap.add_argument("--smooth", type=int, default=1, help="平滑次数，0 关闭")
    ap.add_argument("--zoom", type=int, default=14, help="--fetch 时的瓦片 zoom")
    ap.add_argument("--stl", action="store_true", help="额外导出 STL")
    ap.add_argument("--stl-max", type=int, default=420, help="STL 网格上限，避免文件过大")
    args = ap.parse_args()

    if not (args.dem or args.fetch or args.synthetic):
        ap.error("需指定 --dem / --fetch / --synthetic 之一")

    bbox = tuple(args.bbox) if args.bbox else None

    # ---- 载入 DEM
    if args.synthetic:
        z, bbox = load_synthetic()
        print("[INFO] 使用合成地形（自检模式）")
    elif args.dem:
        if args.dem.lower().endswith((".tif", ".tiff")):
            z, bbox = load_tif(args.dem, bbox)
        elif args.dem.lower().endswith(".asc"):
            z, bbox = load_asc(args.dem)
        else:
            sys.exit("[ERR] 仅支持 *.tif / *.asc")
        print(f"[INFO] DEM 载入 {z.shape[1]}x{z.shape[0]}")
    else:
        if bbox is None:
            bbox = DEFAULT_BBOX
        z, bbox = load_tiles(bbox, args.zoom)
        print(f"[INFO] 瓦片拼合 {z.shape[1]}x{z.shape[0]}")

    if args.smooth > 0:
        z = smooth(z, args.smooth)
        print(f"[INFO] 平滑 {args.smooth} 次")

    # ---- 网格：按沙盘尺寸 + grid 分辨率
    nx = max(32, int(round(args.width_mm / args.grid_mm)))
    ny = max(32, int(round(args.depth_mm / args.grid_mm)))
    z = resample(z, nx, ny)
    xs = np.linspace(0.0, args.width_mm, nx)
    ys = np.linspace(0.0, args.depth_mm, ny)

    zmin, zmax = float(np.min(z)), float(np.max(z))
    print(f"[INFO] 高程范围 {zmin:.1f} ~ {zmax:.1f} m（高差 {zmax - zmin:.1f} m）")

    # ---- 比尺换算
    lon1, lat1, lon2, lat2 = bbox
    span_x_m = abs(lon2 - lon1) * 111320.0 * math.cos(math.radians((lat1 + lat2) / 2))
    span_y_m = abs(lat2 - lat1) * 110540.0
    h_scale = args.width_mm * 0.001 / span_x_m          # 水平比尺分母的倒数
    v_scale = h_scale * args.ve                          # 垂直（含夸张）
    relief_mm = (zmax - zmin) * v_scale * 1000.0
    print(f"[INFO] 真实范围 {span_x_m:.0f} x {span_y_m:.0f} m")
    print(f"[INFO] 水平比尺 1:{1/h_scale:.0f}   垂直比尺 1:{1/v_scale:.0f}（VE={args.ve}）")
    print(f"[INFO] 沙盘起伏 {relief_mm:.1f} mm")

    # 高程 -> 沙盘高度（mm，自基底起算）
    z_mm = (z - zmin) * v_scale * 1000.0

    # ---- 分层
    # 分层逻辑：第 i 层板的形状 = "地形高度 >= i×板厚" 的区域，
    # 因此等高距就等于板厚，不需要按 relief 均分（均分会让顶层退化成一个点）。
    n_layers = max(2, int(math.ceil(relief_mm / args.layer_mm)))
    step_mm = float(args.layer_mm)
    step_m = step_mm / (v_scale * 1000.0)
    top_margin = n_layers * args.layer_mm - relief_mm  # 顶部富余，需打磨或加垫片
    print(f"[INFO] 共 {n_layers} 层 × {args.layer_mm:.1f}mm，等高距 {step_m:.1f} m")
    print(f"[INFO] 堆叠总高 {n_layers * args.layer_mm:.1f}mm，"
          f"顶部富余 {top_margin:.1f}mm（打磨/垫片吸收）")

    os.makedirs(args.out, exist_ok=True)
    layer_dir = os.path.join(args.out, "layers")
    os.makedirs(layer_dir, exist_ok=True)

    cap_mm = relief_mm * 0.999  # 数值兜底：避免某层阈值顶到最高点导致零面积
    layers = []
    for i in range(n_layers):
        thr_mm = min(i * step_mm, cap_mm)
        in_mask = z_mm >= thr_mm
        frac = float(in_mask.mean())
        thr_elev = zmin + thr_mm / (v_scale * 1000.0)
        polys = contour_polygons(z_mm, xs, ys, thr_mm)
        # 实体是否延伸到图幅边缘：是则需要一整幅矩形做外边界，孔洞才会被 evenodd 挖空
        border_in = bool(in_mask[0, :].any() or in_mask[-1, :].any()
                         or in_mask[:, 0].any() or in_mask[:, -1].any())

        full_block = False
        if frac > 0.999:
            W, D = args.width_mm, args.depth_mm
            polys = [[(0.0, 0.0), (W, 0.0), (W, D), (0.0, D)]]
            border_in = True
            full_block = True

        if i == n_layers - 1:
            name, color = TOPSOIL
        else:
            name, color = STRATA[i % len(STRATA)]
        area = frac * args.width_mm * args.depth_mm  # mm²，按网格统计，方向无关
        svg_path = os.path.join(layer_dir, f"L{i+1:02d}_z{thr_elev:.0f}m.svg")
        write_layer_svg(svg_path, polys, args.width_mm, args.depth_mm,
                        color, f"{i+1:02d}", thr_elev, args.layer_mm,
                        full_block, border_in)
        layers.append(dict(idx=i + 1, elev=thr_elev, thickness=args.layer_mm,
                           stack=(i + 1) * args.layer_mm, name=name, color=color,
                           area=area, polys=polys, n_poly=len(polys),
                           full=full_block, border=border_in))

    # ---- CSV
    csv_path = os.path.join(args.out, "layer_table.csv")
    with open(csv_path, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(["层号", "阈值高程(m)", "板厚(mm)", "累计高度(mm)",
                    "岩性", "配色", "轮廓数", "轮廓面积(cm2)", "整块板"])
        for l in layers:
            w.writerow([f"L{l['idx']:02d}", f"{l['elev']:.1f}", f"{l['thickness']:.1f}",
                        f"{l['stack']:.1f}", l["name"], l["color"],
                        l["n_poly"], f"{l['area']/100:.1f}", "是" if l["full"] else "否"])
    print(f"[OK] 层表 -> {csv_path}")

    # ---- 预览
    meta = (f"范围 {bbox}｜水平 1:{1/h_scale:.0f}｜垂直 1:{1/v_scale:.0f}（VE {args.ve}）｜"
            f"{n_layers} 层 × {args.layer_mm:.0f}mm｜起伏 {relief_mm:.0f}mm")
    prev = os.path.join(args.out, "preview.html")
    write_preview_html(prev, layers, args.width_mm, args.depth_mm, meta)
    print(f"[OK] 预览 -> {prev}")

    # ---- STL（可选）
    if args.stl:
        gx = min(nx, args.stl_max)
        gy = min(ny, args.stl_max)
        zs = resample(z_mm, gx, gy)
        stl = os.path.join(args.out, "terrain.stl")
        write_stl(stl, zs, args.width_mm, args.depth_mm)
        print(f"[OK] STL -> {stl}")

    # ---- 耗材估算
    total_area = sum(l["area"] for l in layers) / 100.0  # cm2
    print(f"[INFO] 泡沫板合计面积约 {total_area:.0f} cm² "
          f"（≈ {total_area/10000:.2f} m²，建议按 1.3 倍备料）")
    print(f"[OK] 全部完成，输出目录：{os.path.abspath(args.out)}")


if __name__ == "__main__":
    main()
