#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
OSM 真实地物 + 沙盘地形 -> 泡沫沙盘**零件加工图**

配套 dem_to_foam.py：那份只解决"山体"（分层切割图），
这份解决地形之上的四类要素：水体、山路/城市道路、城市/公共建筑、乡村民居。

★ 坐标系与 dem_to_foam.py 的 layers/*.svg **完全一致**
  （x = 东向 0~1200 mm，y = 北向 0~800 mm，未翻转）。
  因此图纸可打印在透明胶片上，直接覆盖到地形分层图上定位。

输出
----------------------------------------------------------------
  A_buildings_map.svg   建筑平面位置图（真实 footprint + 编号），1:1 叠地形图用
  A2_building_parts.svg 建筑**立面下料排版图**（每栋正面矩形，带编号/尺寸/层数）
  B_roads.svg           道路与梯道贴片裁剪图（山路、梯道单独配色）
  C_water.svg           水体（河槽）开挖轮廓，标注水位
  D_assembly.svg        总装定位图：网格 + 分区 + 设备点位 + 建筑 + 道路 + 水体
  parts_list.csv        零件清单（编号 / 类型 / 尺寸 mm / 数量 / 材料 / 配色）

用法
----------------------------------------------------------------
  cd hardware-sandbox/tools
  python osm_to_foam_parts.py --out ../examples/foam_parts

  # 指定输入（默认自动找上一步的产物）
  python osm_to_foam_parts.py \
      --osm-cache ../examples/revit_model/_osm_cache.json \
      --grid-csv  ../examples/revit_sandbox/terrain_points.csv \
      --rural-csv ../examples/revit_sandbox/features.csv \
      --out ../examples/foam_parts
"""

from __future__ import annotations

import argparse
import csv
import math
import os
import sys
import xml.sax.saxutils as xu

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import dem_to_foam as foam            # noqa: E402
import build_revit_model as brm       # noqa: E402

DEFAULT_BBOX = (106.5150, 29.5425, 106.5450, 29.5600)
BASE_ELEV = 96.4          # 本区域 DEM 最低高程（米），用于把真实水位换算到沙盘高度
WATER_LEVEL = 168.0       # 真实水位（米）

# 配色（与 HARDWARE_SANDBOX.md §3.6 / §3.9 一致）
C_CITY = "#8A8F98"        # 城市建筑
C_PUBLIC = "#C9A227"      # 公共建筑
C_RURAL = "#B5836A"       # 乡村民居
C_ROAD_URBAN = "#4A4A4A"
C_ROAD_MTN = "#9C7A4E"
C_WATER = "#5B9BD5"


# ---------------------------------------------------------------- SVG 基础

def svg_begin(w_mm, h_mm, title):
    return [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{w_mm:.1f}mm" '
        f'height="{h_mm:.1f}mm" viewBox="0 0 {w_mm:.1f} {h_mm:.1f}">',
        f"<title>{xu.escape(title)}</title>",
        f'<rect width="100%" height="100%" fill="#ffffff"/>',
    ]


def svg_end(parts):
    parts.append("</svg>")
    return "\n".join(parts)


def grid_layer(w_mm, h_mm, step=100.0):
    out = []
    x = step
    while x < w_mm:
        out.append(f'<line x1="{x:.1f}" y1="0" x2="{x:.1f}" y2="{h_mm:.1f}" '
                   'stroke="#DDDDDD" stroke-width="0.2"/>')
        x += step
    y = step
    while y < h_mm:
        out.append(f'<line x1="0" y1="{y:.1f}" x2="{w_mm:.1f}" y2="{y:.1f}" '
                   'stroke="#DDDDDD" stroke-width="0.2"/>')
        y += step
    out.append(f'<rect x="0" y="0" width="{w_mm:.1f}" height="{h_mm:.1f}" '
               'fill="none" stroke="#000" stroke-width="0.8"/>')
    return out


def poly_d(pts):
    return "M " + " L ".join(f"{x:.2f},{y:.2f}" for x, y in pts) + " Z"


# ---------------------------------------------------------------- 数据载入

def load_grid_csv(path, width_mm, depth_mm):
    """读 terrain_points.csv（沙盘版，单位米）-> mm 网格。"""
    d = np.loadtxt(path, delimiter=",")
    xs = np.unique(np.round(d[:, 0], 6))
    ys = np.unique(np.round(d[:, 1], 6))
    nx, ny = len(xs), len(ys)
    z = np.zeros((ny, nx))
    idx_x = {round(v, 6): i for i, v in enumerate(xs)}
    idx_y = {round(v, 6): i for i, v in enumerate(ys)}
    for X, Y, Z in d:
        z[idx_y[round(Y, 6)], idx_x[round(X, 6)]] = Z
    scale_x = width_mm / max(xs.max(), 1e-9)
    scale_y = depth_mm / max(ys.max(), 1e-9)
    # X/Y: 模型米 -> mm；Z 已含水平比尺与垂直夸张，只需 m -> mm（不可再乘 scale_x）
    return xs * scale_x, ys * scale_y, z * 1000.0


def load_rural(path, width_mm, depth_mm):
    """从 revit 版 features.csv 读乡村民居位置（沙盘模型坐标 m -> mm）。"""
    out = []
    if not path or not os.path.exists(path):
        return out
    with open(path, encoding="utf-8") as f:
        for row in csv.DictReader(f):
            if row.get("class") != "BLD-RURAL":
                continue
            try:
                x = float(row["X_m"]) * 1000.0
                y = float(row["Y_m"]) * 1000.0
                h = float(row["height_m"] or 0)
            except (TypeError, ValueError):
                continue
            out.append({"x": x, "y": y, "h_mm": h * (width_mm / 2905.0),
                        "name": row.get("name", "")})
    return out


# ---------------------------------------------------------------- 各图纸

def write_building_map(path, items, rural, width_mm, depth_mm):
    p = svg_begin(width_mm, depth_mm, "建筑平面位置图 1:1")
    p += grid_layer(width_mm, depth_mm)
    for it in items:
        p.append(f'<path d="{poly_d(it["poly"])}" fill="{it["color"]}" '
                 f'fill-opacity="0.85" stroke="#333" stroke-width="0.2"/>')
        p.append(f'<text x="{it["cx"]:.1f}" y="{it["cy"]:.1f}" font-size="2.4" '
                 f'text-anchor="middle" fill="#fff">{it["no"]}</text>')
    for r in rural:
        p.append(f'<rect x="{r["x"] - 1.6:.2f}" y="{r["y"] - 1.6:.2f}" width="3.2" '
                 f'height="3.2" fill="{C_RURAL}" stroke="#333" stroke-width="0.2"/>')
    p.append(f'<text x="8" y="14" font-size="6" fill="#000">'
             f'A 建筑平面位置图｜1:1｜透明胶片打印后直接覆盖地形层定位｜'
             f'共 {len(items) + len(rural)} 处</text>')
    p.append(f'<text x="8" y="24" font-size="5" fill="#666">'
             f'灰=城市建筑 黄=公共建筑 棕=乡村民居｜数字为零件编号（见 A2 排版图）</text>')
    open(path, "w", encoding="utf-8").write(svg_end(p))


def write_building_parts(path, items, cols=16, cell_w=62.0, cell_h=46.0):
    """立面下料排版图：每格一个建筑的正面矩形（宽 × 高，1:1，可直接照切）。"""
    rows = math.ceil(max(len(items), 1) / cols)
    w_mm = cols * cell_w + 20
    h_mm = rows * cell_h + 30
    p = svg_begin(w_mm, h_mm, "建筑立面下料排版图 1:1")
    p.append(f'<text x="8" y="14" font-size="6" fill="#000">'
             f'A2 建筑立面下料排版图｜1:1｜每格为建筑正面（宽 × 高）｜'
             f'共 {len(items)} 栋</text>')
    p.append('<text x="8" y="24" font-size="5" fill="#666">'
             '按格内轮廓切割泡沫块，深度（进深）见 parts_list.csv 的 depth_mm</text>')
    for k, it in enumerate(items):
        c, r = k % cols, k // cols
        ox, oy = 10 + c * cell_w, 30 + r * cell_h
        p.append(f'<rect x="{ox:.1f}" y="{oy:.1f}" width="{cell_w - 4:.1f}" '
                 f'height="{cell_h - 4:.1f}" fill="none" stroke="#CCCCCC" stroke-width="0.3"/>')
        bw = min(it["w_mm"], cell_w - 10)
        bh = min(it["h_mm"], cell_h - 16)
        bx = ox + 5
        by = oy + cell_h - 10 - bh
        p.append(f'<rect x="{bx:.2f}" y="{by:.2f}" width="{bw:.2f}" height="{bh:.2f}" '
                 f'fill="{it["color"]}" fill-opacity="0.35" stroke="#000" stroke-width="0.4"/>')
        p.append(f'<text x="{ox + 4:.1f}" y="{oy + 8:.1f}" font-size="4.5" fill="#000">'
                 f'{it["no"]} {xu.escape(it["label"])}</text>')
        p.append(f'<text x="{ox + 4:.1f}" y="{oy + 13:.1f}" font-size="3.6" fill="#666">'
                 f'{it["w_mm"]:.1f}×{it["d_mm"]:.1f}×{it["h_mm"]:.1f}mm</text>')
    open(path, "w", encoding="utf-8").write(svg_end(p))


def write_roads(path, roads, width_mm, depth_mm):
    p = svg_begin(width_mm, depth_mm, "道路与梯道贴片裁剪图 1:1")
    p += grid_layer(width_mm, depth_mm)
    for rd in roads:
        color = C_ROAD_MTN if rd["kind"] == "ROAD-MTN" else C_ROAD_URBAN
        p.append(f'<path d="{poly_d(rd["poly"])}" fill="{color}" fill-opacity="0.75" '
                 'stroke="#222" stroke-width="0.15"/>')
    for rd in roads:
        if rd.get("name") and not rd["name"].startswith(("footway", "steps", "path",
                                                         "service", "residential")):
            p.append(f'<text x="{rd["mid"][0]:.1f}" y="{rd["mid"][1]:.1f}" '
                     f'font-size="3.2" text-anchor="middle" fill="#111">'
                     f'{xu.escape(rd["name"])}</text>')
    n_mtn = sum(1 for r in roads if r["kind"] == "ROAD-MTN")
    p.append(f'<text x="8" y="14" font-size="6" fill="#000">'
             f'B 道路与梯道贴片裁剪图｜1:1｜共 {len(roads)} 条'
             f'（山路/梯道 {n_mtn}，城市道路 {len(roads) - n_mtn}）</text>')
    p.append('<text x="8" y="24" font-size="5" fill="#666">'
             '深灰=城市道路 棕=山路/梯道｜窄路已按 --road-amp 放大以便观看</text>')
    open(path, "w", encoding="utf-8").write(svg_end(p))


def write_water(path, polys, width_mm, depth_mm, level_mm):
    p = svg_begin(width_mm, depth_mm, "水体开挖轮廓 1:1")
    p += grid_layer(width_mm, depth_mm)
    for poly in polys:
        p.append(f'<path d="{poly_d(poly)}" fill="{C_WATER}" fill-opacity="0.5" '
                 'stroke="#1F5C8B" stroke-width="0.5"/>')
    p.append(f'<text x="8" y="14" font-size="6" fill="#000">'
             f'C 水体开挖轮廓｜1:1｜水位 {level_mm:.1f} mm（自沙盘底面起算）</text>')
    p.append('<text x="8" y="24" font-size="5" fill="#666">'
             '★ 河床挖至此轮廓后，水面须比周边泡沫低 3~5 mm 形成岸线，否则水会溢出盘外</text>')
    open(path, "w", encoding="utf-8").write(svg_end(p))


def write_assembly(path, items, rural, roads, water_polys, devices,
                   width_mm, depth_mm):
    p = svg_begin(width_mm, depth_mm, "沙盘总装定位图 1:1")
    p += grid_layer(width_mm, depth_mm)
    for poly in water_polys:
        p.append(f'<path d="{poly_d(poly)}" fill="{C_WATER}" fill-opacity="0.35" '
                 'stroke="#1F5C8B" stroke-width="0.4"/>')
    for rd in roads:
        color = C_ROAD_MTN if rd["kind"] == "ROAD-MTN" else C_ROAD_URBAN
        p.append(f'<path d="{poly_d(rd["poly"])}" fill="{color}" fill-opacity="0.5" '
                 'stroke="none"/>')
    for it in items:
        p.append(f'<rect x="{it["x0"]:.1f}" y="{it["y0"]:.1f}" '
                 f'width="{max(it["w_mm"], 1.0):.1f}" height="{max(it["d_mm"], 1.0):.1f}" '
                 f'fill="{it["color"]}" stroke="none"/>')
    for r in rural:
        p.append(f'<circle cx="{r["x"]:.1f}" cy="{r["y"]:.1f}" r="1.6" '
                 f'fill="{C_RURAL}" stroke="#333" stroke-width="0.2"/>')
    for d in devices:
        p.append(f'<circle cx="{d["x"]:.1f}" cy="{d["y"]:.1f}" r="3.0" '
                 f'fill="none" stroke="#C00000" stroke-width="0.5"/>')
        p.append(f'<text x="{d["x"] + 4:.1f}" y="{d["y"] + 1.5:.1f}" font-size="3.0" '
                 f'fill="#C00000">{xu.escape(d["code"])}</text>')
    p.append(f'<text x="8" y="14" font-size="6" fill="#000">'
             f'D 沙盘总装定位图｜1:1｜{width_mm:.0f} × {depth_mm:.0f} mm</text>')
    p.append('<text x="8" y="24" font-size="5" fill="#666">'
             '蓝=水体 棕线=山路/梯道 灰块=建筑 红圈=监测设备（需预埋走线孔）</text>')
    open(path, "w", encoding="utf-8").write(svg_end(p))


# ---------------------------------------------------------------- 主流程

def main():
    ap = argparse.ArgumentParser(description="OSM 地物 -> 泡沫沙盘零件加工图")
    ap.add_argument("--osm-cache", default="../examples/revit_model/_osm_cache.json")
    ap.add_argument("--grid-csv", default="../examples/revit_sandbox/terrain_points.csv",
                    help="沙盘地形点文件（用于水体轮廓）")
    ap.add_argument("--rural-csv", default="../examples/revit_sandbox/features.csv",
                    help="revit 版要素清单（用于乡村民居位置）")
    ap.add_argument("--out", default="../examples/foam_parts")
    ap.add_argument("--bbox", nargs=4, type=float, metavar=("LON1", "LAT1", "LON2", "LAT2"))
    ap.add_argument("--width-mm", type=float, default=1200.0)
    ap.add_argument("--depth-mm", type=float, default=800.0)
    ap.add_argument("--base-elev", type=float, default=BASE_ELEV,
                    help="DEM 最低高程（米），用于换算沙盘水位高度")
    ap.add_argument("--water-level", type=float, default=WATER_LEVEL, help="真实水位（米）")
    ap.add_argument("--ve", type=float, default=2.5, help="垂直夸张（须与地形图一致）")
    ap.add_argument("--road-amp", type=float, default=2.0,
                    help="道路宽度放大倍数（1:2421 下支路仅 3 mm，需放大才看得见）")
    ap.add_argument("--building-scale", type=float, default=1.0)
    ap.add_argument("--min-part-mm", type=float, default=2.5,
                    help="小于此尺寸的零件不加工（切不出来）")
    ap.add_argument("--min-water-mm2", type=float, default=50.0,
                    help="小于此面积的水体碎片环丢弃（避免图纸上出现无意义的碎点）")
    ap.add_argument("--max-buildings", type=int, default=300)
    args = ap.parse_args()

    bbox = tuple(args.bbox) if args.bbox else DEFAULT_BBOX
    lon1, lat1, lon2, lat2 = bbox
    W, D = args.width_mm, args.depth_mm

    def to_mm(lon, lat):
        return ((lon - lon1) / (lon2 - lon1) * W,
                (lat - lat1) / (lat2 - lat1) * D)

    hs_mm = W / 2905.0        # 真实米 -> 沙盘 mm（水平比尺）
    os.makedirs(args.out, exist_ok=True)

    osm = {}
    if os.path.exists(args.osm_cache):
        import json
        osm = json.load(open(args.osm_cache, encoding="utf-8"))
        print(f"[INFO] OSM 缓存 {os.path.basename(args.osm_cache)}："
              f"建筑 {len(osm.get('buildings', []))} / "
              f"道路 {len(osm.get('roads', []))}")
    else:
        print(f"[WARN] 未找到 {args.osm_cache}，建筑与道路将为空")

    # --- 建筑
    items = []
    rng = brm.random.Random(20260920)
    m = max(W, D) * 0.01
    cand = []
    for el in osm.get("buildings", []):
        tags = el.get("tags", {})
        poly = brm.way_xy(el, to_mm)
        if len(poly) < 3:
            continue
        if not all(brm._inside(p, W, D, m) for p in poly):
            continue
        xs = [p[0] for p in poly]
        ys = [p[1] for p in poly]
        w_mm, d_mm = max(xs) - min(xs), max(ys) - min(ys)
        if max(w_mm, d_mm) < args.min_part_mm:
            continue
        area_mm = abs(brm.poly_area(poly))
        cand.append((area_mm, el, tags, poly, w_mm, d_mm,
                     min(xs), min(ys), sum(xs) / len(xs), sum(ys) / len(ys)))
    cand.sort(key=lambda t: -t[0])
    for k, (area_mm, el, tags, poly, w_mm, d_mm, x0, y0, cx, cy) in enumerate(
            cand[:args.max_buildings], start=1):
        bt = tags.get("building", "yes")
        h_real = brm.building_height(tags, area_mm / max(hs_mm * hs_mm, 1e-9), rng)
        h_mm = h_real * hs_mm * args.building_scale
        if bt in brm.PUBLIC_TYPES:
            color, cls = C_PUBLIC, "公共建筑"
        else:
            color, cls = C_CITY, "城市建筑"
        label = tags.get("name") or bt
        items.append({"no": k, "poly": poly, "color": color, "cls": cls,
                      "label": label[:14], "w_mm": w_mm, "d_mm": d_mm, "h_mm": h_mm,
                      "x0": x0, "y0": y0, "cx": cx, "cy": cy,
                      "levels": tags.get("building:levels", ""),
                      "h_real": h_real, "osm": bt})
    print(f"[OK] 建筑 {len(items)} 栋（最小边 ≥ {args.min_part_mm} mm）")

    rural = load_rural(args.rural_csv, W, D)
    print(f"[OK] 乡村民居 {len(rural)} 座（位置与 Revit 模型一致）")

    # --- 道路
    roads = []
    for el in osm.get("roads", []):
        tags = el.get("tags", {})
        hw = tags.get("highway", "residential")
        for pts in brm.split_inside(brm.way_xy(el, to_mm), W, D, m):
            if len(pts) < 2:
                continue
            w = brm.ROAD_WIDTH.get(hw, 6.0) * 0.5 * hs_mm * args.road_amp
            left, right = [], []
            for k in range(len(pts)):
                if k == 0:
                    dx, dy = pts[1][0] - pts[0][0], pts[1][1] - pts[0][1]
                elif k == len(pts) - 1:
                    dx, dy = pts[-1][0] - pts[-2][0], pts[-1][1] - pts[-2][1]
                else:
                    dx, dy = pts[k + 1][0] - pts[k - 1][0], pts[k + 1][1] - pts[k - 1][1]
                L = math.hypot(dx, dy) or 1.0
                nx_, ny_ = -dy / L, dx / L
                left.append((pts[k][0] + nx_ * w, pts[k][1] + ny_ * w))
                right.append((pts[k][0] - nx_ * w, pts[k][1] - ny_ * w))
            roads.append({"kind": "ROAD-MTN" if hw in brm.MOUNTAIN_ROADS else "ROAD-URBAN",
                          "hw": hw, "name": tags.get("name") or f"{hw}-{el.get('id')}",
                          "poly": left + right[::-1], "mid": pts[len(pts) // 2],
                          "len_mm": sum(math.hypot(pts[i + 1][0] - pts[i][0],
                                                   pts[i + 1][1] - pts[i][1])
                                        for i in range(len(pts) - 1)),
                          "w_mm": w * 2})
    n_mtn = sum(1 for r in roads if r["kind"] == "ROAD-MTN")
    print(f"[OK] 道路 {len(roads)} 条（山路/梯道 {n_mtn}，宽度放大 ×{args.road_amp}）")

    # --- 水体
    water_polys = []
    level_mm = 0.0
    if os.path.exists(args.grid_csv):
        xs_mm, ys_mm, z_mm = load_grid_csv(args.grid_csv, W, D)
        level_mm = (args.water_level - args.base_elev) * hs_mm * args.ve
        raw = foam.contour_polygons(level_mm - z_mm, xs_mm, ys_mm, 0.0)
        water_polys = [p for p in raw if abs(brm.poly_area(p)) >= args.min_water_mm2]
        kept = sum(abs(brm.poly_area(p)) for p in water_polys)
        print(f"[OK] 水体轮廓 {len(water_polys)} 环（原始 {len(raw)}，"
              f"已滤掉 <{args.min_water_mm2} mm² 的碎片）"
              f"｜水面 {kept / 100:.0f} cm² 占盘面 {100 * kept / (W * D):.1f}%"
              f"｜水位 {args.water_level} m -> 沙盘 {level_mm:.1f} mm")
    else:
        print(f"[WARN] 未找到 {args.grid_csv}，跳过水体轮廓")

    # --- 设备点位（坐标直接从 register_devices.sql 解析，避免二次维护）
    devices = []
    sql = os.path.join(HERE, "register_devices.sql")
    if os.path.exists(sql):
        import re
        txt = open(sql, encoding="utf-8").read()
        for code, lon, lat in re.findall(
                r"\('(SB-[A-Z0-9\-]+|WS-[A-Z0-9\-]+)','[^']*','[^']*','[^']*'"
                r".*?(106\.\d+),(29\.\d+)", txt, re.S):
            x, y = to_mm(float(lon), float(lat))
            devices.append({"code": code, "x": x, "y": y})
    print(f"[OK] 设备点位 {len(devices)} 个（需在泡沫上预埋走线孔）")

    # --- 出图
    a = os.path.join(args.out, "A_buildings_map.svg")
    write_building_map(a, items, rural, W, D)
    a2 = os.path.join(args.out, "A2_building_parts.svg")
    write_building_parts(a2, items)
    b = os.path.join(args.out, "B_roads.svg")
    write_roads(b, roads, W, D)
    c = os.path.join(args.out, "C_water.svg")
    write_water(c, water_polys, W, D, level_mm)
    dd = os.path.join(args.out, "D_assembly.svg")
    write_assembly(dd, items, rural, roads, water_polys, devices, W, D)
    print(f"[OK] 图纸 -> {args.out}（A / A2 / B / C / D）")

    # --- 清单
    cl = os.path.join(args.out, "parts_list.csv")
    with open(cl, "w", encoding="utf-8", newline="") as f:
        wr = csv.writer(f)
        wr.writerow(["no", "class", "name", "osm_kind", "w_mm", "depth_mm", "h_mm",
                     "levels", "h_real_m", "x_mm", "y_mm", "material", "color"])
        for it in items:
            wr.writerow([it["no"], it["cls"], it["label"], it["osm"],
                         round(it["w_mm"], 1), round(it["d_mm"], 1), round(it["h_mm"], 1),
                         it["levels"], round(it["h_real"], 1), round(it["x0"], 1),
                         round(it["y0"], 1), "EPS/XPS 块", it["color"]])
        for k, r in enumerate(rural, start=1):
            wr.writerow([f"R{k}", "乡村民居", r["name"], "rural(程序化)",
                         6.0 * hs_mm * args.building_scale, 5.0 * hs_mm * args.building_scale,
                         round(r["h_mm"], 1), "1-2", "", round(r["x"], 1), round(r["y"], 1),
                         "XPS 块 + 坡屋顶", C_RURAL])
        for k, rd in enumerate(roads, start=1):
            wr.writerow([f"RD{k}", rd["kind"], rd["name"], rd["hw"], rd["w_mm"],
                         round(rd["len_mm"], 1), 0.3, "", "",
                         round(rd["mid"][0], 1), round(rd["mid"][1], 1),
                         "0.5mm 卡纸/PVC" if rd["kind"] == "ROAD-URBAN" else "3mm XPS 薄条",
                         C_ROAD_URBAN if rd["kind"] == "ROAD-URBAN" else C_ROAD_MTN])
        wr.writerow(["W1", "水体", "河槽开挖", "DEM 河谷", "", "", round(level_mm, 1),
                     "", "", "", "", "泡沫开挖 + 蓝色 PVC 片 + 水性防水", C_WATER])
    print(f"[OK] 零件清单 -> {cl}"
          f"（建筑 {len(items)} + 乡村 {len(rural)} + 道路 {len(roads)} + 水体 1）")

    print("\n[加工顺序建议]")
    print("  1) 先按 dem_to_foam 的 layers/*.svg 堆出山体（16 层 × 20 mm）")
    print("  2) 用 C_water.svg 开挖河槽，做防水并铺蓝色片材（水面低于岸 3~5 mm）")
    print("  3) 用 B_roads.svg 裁贴道路与梯道（梯道用 3mm 薄条逐级错位粘贴）")
    print("  4) 按 A2_building_parts.svg 切建筑块，用 A_buildings_map.svg 定位")
    print("  5) 全程只用白乳胶 / UHU por，禁用 502 与硝基漆（会溶解泡沫）")


if __name__ == "__main__":
    main()
