#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
混合沙盘地形建模工具（C方案）

基于「地脉智巡」混合沙盘演示方案，生成三区一体化地形模型：
  A区 山区/滑坡区 (0.6m x 1.0m) — 高地，坡度25-35，可动滑坡体
  B区 河谷/洪水区 (0.5m x 1.0m) — 低洼，亚克力水槽，水位可控
  C区 城镇/避难区 (0.4m x 1.0m) — 平地，仿真建筑群，LED预警屏

输出：
  model.ifc            IFC4 三维模型（Revit 可直接打开）
  model.obj            OBJ 三维模型（Blender/Lumion/Twinmotion）
  terrain_points.csv   Revit 地形点文件
  features.csv         要素清单
  device_points.csv    传感器/执行器部署点位
  layer_table.csv      泡沫分层规格表
  parts_list.csv       加工零件清单
  zone_layout.svg      区域布局图（施工参考）
  preview.html         交互式预览页面
  foam_layers/         泡沫分层切割图（每层一张 SVG）
  landslide_body.svg   滑坡体零件图
"""

from __future__ import annotations

import argparse
import csv
import math
import os
import random
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

try:
    import dem_to_foam as foam
except ImportError:
    sys.exit("[ERR] 需要同目录下的 dem_to_foam.py")

try:
    from build_revit_model import IfcBuilder, ear_clip, quad_faces
except ImportError:
    sys.exit("[ERR] 需要同目录下的 build_revit_model.py")

# ============================================================ 常量

WIDTH_MM = 1500.0
DEPTH_MM = 1000.0
A_END = 600.0       # A-B 分界
B_END = 1100.0      # B-C 分界
FOAM_MM = 20.0      # 泡沫板厚度
WATER_LEVEL_MM = 12.0  # 默认水面高程

ZONE_LABELS = {"A": "山区/滑坡区", "B": "河谷/洪水区", "C": "城镇/避难区"}
ZONE_COLORS = {"A": "#8B6B47", "B": "#4A90C4", "C": "#7CB342"}

STRATA = [
    ("泥岩夹粉砂岩", "#7E4433"),
    ("砂岩", "#B5836A"),
    ("泥岩", "#8A4F3C"),
    ("砂岩厚层", "#C09176"),
]
TOPSOIL = ("第四系覆盖层", "#9C7A4E")

# C区建筑布局（center_x, center_y, w_mm, d_mm, h_mm, type, name）
BUILDINGS = [
    (1200, 200, 50, 70, 35, "residential", "居民楼-1"),
    (1300, 200, 50, 70, 40, "residential", "居民楼-2"),
    (1400, 200, 50, 70, 35, "residential", "居民楼-3"),
    (1250, 500, 90, 110, 55, "public", "社区中心"),
    (1400, 500, 50, 70, 45, "residential", "居民楼-4"),
    (1200, 800, 50, 70, 35, "residential", "居民楼-5"),
    (1300, 800, 50, 70, 40, "residential", "居民楼-6"),
    (1400, 800, 50, 70, 35, "residential", "居民楼-7"),
]

# C区道路（x1, y1, x2, y2, width_mm, name）
ROADS = [
    (1150, 0, 1150, 1000, 10, "道路-1"),
    (1350, 0, 1350, 1000, 10, "道路-2"),
    (1450, 0, 1450, 1000, 8, "道路-3"),
    (1100, 350, 1500, 350, 10, "横街-1"),
    (1100, 650, 1500, 650, 10, "横街-2"),
    (1050, 500, 1150, 500, 12, "疏散通道"),
]

# 传感器/执行器部署（code, name, type, zone, x_mm, y_mm, z_mm, desc）
DEVICES = [
    ("SB-CRACK-01", "裂缝计",     "crack_meter",    "A", 250, 480, 0, "滑坡体后缘"),
    ("SB-DISP-01",  "位移计",     "displacement",   "A", 320, 550, 0, "滑坡体滑轨"),
    ("SB-INCL-01",  "倾角计",     "inclinometer",    "A", 300, 540, 0, "滑坡体"),
    ("SB-SOIL-01",  "土壤湿度",   "soil_moisture",  "A", 200, 620, 0, "坡体"),
    ("SB-RAIN-01",  "雨量计",     "rain_gauge",     "A", 400, 180, 0, "A区上方"),
    ("SB-TILT-01",  "六轴加速度", "accelerometer",  "A", 300, 400, 0, "A区"),
    ("SB-DHT-01",   "温湿度",     "temp_humidity",  "A", 350, 220, 0, "A区气象站"),
    ("SB-CAM-01",   "摄像头A",   "camera",         "A", 420, 120, 0, "A区俯瞰"),
    ("WS-WATER-01", "水位计",     "water_level",    "B", 850, 500, 0, "B区水槽"),
    ("SB-CAM-02",   "摄像头B",   "camera",         "B", 850, 50,  0, "B区俯瞰"),
    ("SB-VIB-01",   "振动传感器", "vibration",      "C", 1300, 600, 0, "C区"),
    ("SB-LED-01",   "LED预警屏",  "led_matrix",     "C", 1350, 500, 0, "C区"),
    ("SB-ALARM-01", "声光报警器", "alarm",          "C", 1300, 700, 0, "C区"),
    ("SB-SERVO-01", "舵机",       "servo",          "A", 300, 550, 0, "滑坡体驱动"),
    ("SB-PUMP-01",  "微型水泵",   "pump",           "B", 850, 880, 0, "水位控制"),
    ("SB-VALVE-01", "电磁阀",     "valve",          "A", 400, 180, 0, "降雨控制"),
]


# ============================================================ 地形生成

def generate_terrain(nx=300, ny=200, width_mm=WIDTH_MM, depth_mm=DEPTH_MM):
    """生成三区一体化地形高程网格（mm 坐标系）。"""
    x = np.linspace(0, width_mm, nx)
    y = np.linspace(0, depth_mm, ny)
    X, Y = np.meshgrid(x, y)

    # 区域权重（tanh 过渡，过渡带约 ±40mm）
    w_a = 0.5 * (1.0 - np.tanh((X - A_END) / 40.0))
    w_c = 0.5 * (1.0 + np.tanh((X - B_END) / 40.0))
    w_b = 1.0 - w_a - w_c

    # ---- A区：山地 ----
    peak = 250.0 * np.exp(-((X - 350.0)**2 / (2 * 200.0**2) +
                            (Y - 500.0)**2 / (2 * 280.0**2)))
    spur = 70.0 * np.exp(-((X - 150.0)**2 / (2 * 70.0**2) +
                           (Y - 750.0)**2 / (2 * 120.0**2)))
    gully = -35.0 * np.exp(-((Y - 550.0)**2 / (2 * 35.0**2))) * \
            np.exp(-((X - 300.0)**2 / (2 * 100.0**2))) * (X < 620.0)
    ridge = 12.0 * np.sin(np.arctan2(Y - 500.0, (X - 350.0) + 1e-6) * 3.0) * \
            np.exp(-((X - 350.0)**2 + (Y - 500.0)**2) / (2 * 250.0**2))
    z_a = peak + spur + gully + ridge

    # ---- B区：河谷 ----
    river_x = 850.0
    # 河谷基底低洼，河道中心最低（0mm），两岸逐渐升高
    valley_floor = 5.0
    channel = -5.0 * np.exp(-((X - river_x)**2 / (2 * 40.0**2)))
    banks = 30.0 * (1.0 - np.exp(-((X - river_x)**2 / (2 * 200.0**2))))
    z_b = valley_floor + channel + banks

    # ---- C区：城镇平地 ----
    z_c = np.full_like(X, 30.0)
    z_c += 2.0 * np.sin(X * 0.02) * np.cos(Y * 0.03)

    # 合成
    z = w_a * z_a + w_b * z_b + w_c * z_c
    z = np.clip(z, 0.0, None)
    return z, x, y


# ============================================================ 特征要素

def landslide_geometry(z, xs, ys):
    """滑坡体几何：150x200mm 可动块，位于 A区坡面。"""
    cx, cy = 300.0, 550.0
    w, d, h = 150.0, 200.0, 40.0
    # 在地形上采样四个角的高程
    from build_revit_model import sample_z
    z0 = sample_z(z, cx / WIDTH_MM, cy / DEPTH_MM)

    # 简化为贴合地形的块体
    half_w, half_d = w / 2, d / 2
    corners = [
        (cx - half_w, cy - half_d),
        (cx + half_w, cy - half_d),
        (cx + half_w, cy + half_d),
        (cx - half_w, cy + half_d),
    ]
    zs = [sample_z(z, p[0] / WIDTH_MM, p[1] / DEPTH_MM) for p in corners]
    z_base = min(zs)
    verts = [(p[0], p[1], z_base) for p in corners] + \
            [(p[0], p[1], z_base + h) for p in corners]
    faces = []
    n = 4
    for k in range(n):
        a, b = k, (k + 1) % n
        faces += quad_faces(a, b, n + b, n + a)
    for a, b, c in ear_clip(corners):
        faces.append((n + a, n + b, n + c))
    # 底面
    for a, b, c in ear_clip(corners[::-1]):
        faces.append((a, b, c))
    return verts, faces, (cx, cy, z_base, w, d, h)


def building_geometry(cx, cy, w, d, h, z_terrain):
    """生成一栋建筑的挤出体块。"""
    half_w, half_d = w / 2, d / 2
    corners = [
        (cx - half_w, cy - half_d),
        (cx + half_w, cy - half_d),
        (cx + half_w, cy + half_d),
        (cx - half_w, cy + half_d),
    ]
    zb = z_terrain
    zt = zb + h
    verts = [(p[0], p[1], zb) for p in corners] + \
            [(p[0], p[1], zt) for p in corners]
    faces = []
    n = 4
    for k in range(n):
        a, b = k, (k + 1) % n
        faces += quad_faces(a, b, n + b, n + a)
    for a, b, c in ear_clip(corners):
        faces.append((n + a, n + b, n + c))
    return verts, faces


def road_ribbon(pts, width_mm, z_terrain_fn):
    """中心线 -> 带状路面。"""
    w = width_mm * 0.5
    verts, faces = [], []
    normals = []
    for k in range(len(pts)):
        if k == 0:
            dx = pts[1][0] - pts[0][0]
            dy = pts[1][1] - pts[0][1]
        elif k == len(pts) - 1:
            dx = pts[-1][0] - pts[-2][0]
            dy = pts[-1][1] - pts[-2][1]
        else:
            dx = pts[k + 1][0] - pts[k - 1][0]
            dy = pts[k + 1][1] - pts[k - 1][1]
        L = math.hypot(dx, dy) or 1.0
        normals.append((-dy / L, dx / L))
    for k, (x, y) in enumerate(pts):
        nx_, ny_ = normals[k]
        zz = z_terrain_fn(x, y) + 0.5  # 路面略高于地面
        verts.append((x + nx_ * w, y + ny_ * w, zz))
        verts.append((x - nx_ * w, y - ny_ * w, zz))
    for k in range(len(pts) - 1):
        faces += quad_faces(2 * k, 2 * k + 1, 2 * k + 3, 2 * k + 2)
    return verts, faces


def sample_terrain_fn(z, xs, ys):
    """返回 (x_mm, y_mm) -> z_mm 的采样函数。"""
    ny, nx = z.shape
    def fn(x, y):
        u = max(0.0, min(1.0, x / WIDTH_MM))
        v = max(0.0, min(1.0, y / DEPTH_MM))
        return foam._interp_terrain(z, u, v) if hasattr(foam, '_interp_terrain') else \
            float(z[int(v * (ny - 1)), int(u * (nx - 1))])
    return fn


# ============================================================ SVG 输出

def _mm(v):
    return f"{v:.2f}"


def write_zone_layout(path, z, xs, ys, devices, buildings, roads):
    """区域布局图（俯视，施工参考）。"""
    W, D = WIDTH_MM, DEPTH_MM
    scale = 0.6  # SVG 缩放
    sw, sh = W * scale, D * scale

    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{sw:.0f}mm" height="{sh:.0f}mm" '
        f'viewBox="0 0 {W} {D}">',
        f'<title>混合沙盘区域布局图</title>',
        '<rect width="100%" height="100%" fill="#fafafa"/>',
        # 网格背景
        f'<defs><pattern id="grid" width="50" height="50" patternUnits="userSpaceOnUse">'
        f'<path d="M 50 0 L 0 0 0 50" fill="none" stroke="#e0e0e0" stroke-width="0.3"/>'
        f'</pattern></defs>',
        f'<rect width="{W}" height="{D}" fill="url(#grid)"/>',
    ]

    # 区域底色
    parts.append(f'<rect x="0" y="0" width="{A_END}" height="{D}" '
                 f'fill="{ZONE_COLORS["A"]}" fill-opacity="0.15"/>')
    parts.append(f'<rect x="{A_END}" y="0" width="{B_END - A_END}" height="{D}" '
                 f'fill="{ZONE_COLORS["B"]}" fill-opacity="0.15"/>')
    parts.append(f'<rect x="{B_END}" y="0" width="{W - B_END}" height="{D}" '
                 f'fill="{ZONE_COLORS["C"]}" fill-opacity="0.15"/>')

    # 等高线（稀疏，仅参考）
    zmin, zmax = float(z.min()), float(z.max())
    n_contours = 8
    for i in range(1, n_contours + 1):
        thr = zmin + (zmax - zmin) * i / (n_contours + 1)
        polys = foam.contour_polygons(z, xs, ys, thr)
        for poly in polys:
            d = "M " + " L ".join(f"{x:.1f},{y:.1f}" for x, y in poly) + " Z"
            parts.append(f'<path d="{d}" fill="none" stroke="#999" '
                         f'stroke-width="0.3" stroke-opacity="0.4"/>')

    # 区域分界线
    parts.append(f'<line x1="{A_END}" y1="0" x2="{A_END}" y2="{D}" '
                 f'stroke="#666" stroke-width="1.5" stroke-dasharray="10,5"/>')
    parts.append(f'<line x1="{B_END}" y1="0" x2="{B_END}" y2="{D}" '
                 f'stroke="#666" stroke-width="1.5" stroke-dasharray="10,5"/>')

    # 区域标签
    parts.append(f'<text x="{A_END/2}" y="30" font-size="24" fill="#333" '
                 f'text-anchor="middle" font-weight="bold">A区 山区/滑坡区</text>')
    parts.append(f'<text x="{A_END/2}" y="55" font-size="12" fill="#666" '
                 f'text-anchor="middle">600mm x 1000mm | 坡度25-35 | 滑坡体</text>')
    parts.append(f'<text x="{(A_END+B_END)/2}" y="30" font-size="24" fill="#333" '
                 f'text-anchor="middle" font-weight="bold">B区 河谷/洪水区</text>')
    parts.append(f'<text x="{(A_END+B_END)/2}" y="55" font-size="12" fill="#666" '
                 f'text-anchor="middle">500mm x 1000mm | 亚克力水槽 | 水位可控</text>')
    parts.append(f'<text x="{(B_END+W)/2}" y="30" font-size="24" fill="#333" '
                 f'text-anchor="middle" font-weight="bold">C区 城镇/避难区</text>')
    parts.append(f'<text x="{(B_END+W)/2}" y="55" font-size="12" fill="#666" '
                 f'text-anchor="middle">400mm x 1000mm | 仿真建筑群 | LED预警屏</text>')

    # 滑坡体
    parts.append(f'<rect x="225" y="450" width="150" height="200" '
                 f'fill="#D4624A" fill-opacity="0.3" stroke="#C0392B" stroke-width="1.2" '
                 f'stroke-dasharray="6,3"/>')
    parts.append(f'<text x="300" y="560" font-size="14" fill="#C0392B" '
                 f'text-anchor="middle">滑坡体</text>')
    parts.append(f'<text x="300" y="576" font-size="9" fill="#C0392B" '
                 f'text-anchor="middle">150x200mm 可动</text>')

    # 水槽轮廓
    parts.append(f'<rect x="780" y="80" width="140" height="840" '
                 f'fill="{ZONE_COLORS["B"]}" fill-opacity="0.25" '
                 f'stroke="#2980B9" stroke-width="1.2"/>')
    parts.append(f'<text x="850" y="500" font-size="14" fill="#2980B9" '
                 f'text-anchor="middle" transform="rotate(-90 850 500)">'
                 f'亚克力水槽</text>')

    # 建筑物
    for cx, cy, w, d, h, btype, name in buildings:
        color = "#E67E22" if btype == "public" else "#2C3E50"
        parts.append(f'<rect x="{cx-w/2:.1f}" y="{cy-d/2:.1f}" '
                     f'width="{w}" height="{d}" fill="{color}" '
                     f'fill-opacity="0.2" stroke="{color}" stroke-width="0.8"/>')
        parts.append(f'<text x="{cx}" y="{cy+4}" font-size="8" fill="{color}" '
                     f'text-anchor="middle">{name}</text>')

    # 道路
    for x1, y1, x2, y2, rw, name in roads:
        parts.append(f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" '
                     f'stroke="#555" stroke-width="{rw}" stroke-opacity="0.3"/>')

    # 传感器/执行器
    device_colors = {
        "crack_meter": "#E74C3C", "displacement": "#8E44AD", "inclinometer": "#9B59B6",
        "soil_moisture": "#27AE60", "rain_gauge": "#3498DB", "accelerometer": "#F39C12",
        "temp_humidity": "#1ABC9C", "camera": "#34495E", "water_level": "#2980B9",
        "vibration": "#E67E22", "led_matrix": "#2ECC71", "alarm": "#E74C3C",
        "servo": "#D35400", "pump": "#16A085", "valve": "#F1C40F",
    }
    for code, name, dtype, zone, dx, dy, dz, desc in devices:
        c = device_colors.get(dtype, "#333")
        parts.append(f'<circle cx="{dx}" cy="{dy}" r="8" fill="{c}" '
                     f'stroke="#fff" stroke-width="1.5"/>')
        parts.append(f'<text x="{dx+12}" y="{dy+4}" font-size="8" fill="#333">'
                     f'{name}</text>')

    # 边框 + 尺寸标注
    parts.append(f'<rect x="0" y="0" width="{W}" height="{D}" '
                 f'fill="none" stroke="#000" stroke-width="1.5"/>')
    parts.append(f'<text x="8" y="{D-8}" font-size="11" fill="#666">'
                 f'沙盘 1500mm x 1000mm x 150mm | 比例 1:1</text>')
    parts.append(f'<text x="{W-8}" y="{D-8}" font-size="11" fill="#666" '
                 f'text-anchor="end">DisasterGuard 混合沙盘 C方案</text>')

    parts.append('</svg>')
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(parts))


def write_landslide_svg(path, w=150, d=200, h=40):
    """滑坡体零件图。"""
    cx, cy = 30 + w / 2, 30 + d / 2
    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{w+60}mm" height="{d+60}mm" '
        f'viewBox="0 0 {w+60} {d+60}">',
        f'<title>滑坡体零件图</title>',
        f'<rect width="100%" height="100%" fill="#fff"/>',
        f'<rect x="30" y="30" width="{w}" height="{d}" fill="#D4624A" '
        f'fill-opacity="0.2" stroke="#C0392B" stroke-width="1.5"/>',
        f'<text x="{cx}" y="22" font-size="14" fill="#333" text-anchor="middle">'
        f'滑坡体 俯视图</text>',
        f'<text x="{cx}" y="{30+d+20}" font-size="10" fill="#666" text-anchor="middle">'
        f'{w}mm x {d}mm x {h}mm (高)</text>',
        f'<line x1="30" y1="{cy}" x2="{30+w}" y2="{cy}" '
        f'stroke="#666" stroke-width="0.8" stroke-dasharray="5,3"/>',
        f'<text x="{cx}" y="{cy+5}" font-size="8" fill="#666" '
        f'text-anchor="middle">滑轨方向</text>',
        f'<circle cx="{cx}" cy="{30+d-20}" r="10" fill="#D35400" '
        f'fill-opacity="0.3" stroke="#D35400" stroke-width="1"/>',
        f'<text x="{cx}" y="{30+d-17}" font-size="7" fill="#D35400" '
        f'text-anchor="middle">SG90舵机</text>',
        '</svg>',
    ]
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(parts))


# ============================================================ HTML 预览

def write_preview_html(path, layers, devices, buildings, meta):
    W, D = WIDTH_MM, DEPTH_MM
    body = [
        "<!doctype html><html lang='zh-CN'><head><meta charset='utf-8'>",
        "<title>混合沙盘地形预览</title>",
        "<style>",
        "body{font-family:system-ui,'Microsoft YaHei',sans-serif;margin:20px;background:#f5f5f5}",
        "h2{color:#333} p{color:#666;font-size:13px}",
        "svg{background:#fff;border:1px solid #ccc;max-width:100%}",
        ".tabs{display:flex;gap:8px;margin:16px 0}",
        ".tab{padding:6px 16px;border:1px solid #ccc;border-radius:6px;cursor:pointer;"
        "background:#fff;font-size:13px}",
        ".tab.active{background:#2C3E50;color:#fff;border-color:#2C3E50}",
        ".panel{display:none}.panel.active{display:block}",
        "table{border-collapse:collapse;margin-top:12px;font-size:12px}",
        "td,th{border:1px solid #ddd;padding:4px 10px}",
        "th{background:#2C3E50;color:#fff}",
        ".device-dot{cursor:pointer}",
        ".legend{display:flex;gap:16px;margin:12px 0;font-size:12px}",
        ".legend-item{display:flex;align-items:center;gap:6px}",
        ".legend-dot{width:12px;height:12px;border-radius:50%}",
        "</style></head><body>",
        f"<h2>DisasterGuard 混合沙盘地形预览</h2>",
        f"<p>{meta}</p>",
        # Tab 切换
        "<div class='tabs'>",
        "<div class='tab active' onclick=\"showTab('terrain')\">地形俯视</div>",
        "<div class='tab' onclick=\"showTab('layers')\">泡沫分层</div>",
        "<div class='tab' onclick=\"showTab('zones')\">区域布局</div>",
        "<div class='tab' onclick=\"showTab('devices')\">设备部署</div>",
        "</div>",
    ]

    # --- 地形俯视 panel ---
    body.append("<div class='panel active' id='terrain'>")
    body.append(f"<svg viewBox='0 0 {W} {D}' width='{min(1000, int(W))}' "
                f"height='{int(min(1000, W) * D / W)}'>")
    body.append(f"<rect width='{W}' height='{D}' fill='#f0f0f0'/>")
    for lay in layers:
        d = foam._one_path(lay["polys"], W, D, lay.get("border", True))
        body.append(f"<path d='{d}' fill='{lay['color']}' fill-opacity='0.45' "
                    "fill-rule='evenodd' stroke='#555' stroke-width='0.3'/>")
    body.append("</svg>")
    body.append("</div>")

    # --- 泡沫分层 panel ---
    body.append("<div class='panel' id='layers'>")
    body.append(f"<svg viewBox='0 0 {W} {D}' width='{min(1000, int(W))}' "
                f"height='{int(min(1000, W) * D / W)}'>")
    body.append(f"<rect width='{W}' height='{D}' fill='#fff'/>")
    for lay in layers:
        d = foam._one_path(lay["polys"], W, D, lay.get("border", True))
        body.append(f"<path d='{d}' fill='{lay['color']}' fill-opacity='0.3' "
                    "fill-rule='evenodd' stroke='#333' stroke-width='0.4'/>")
    body.append("</svg>")
    body.append("<table><tr><th>层</th><th>阈值(mm)</th><th>板厚(mm)</th>"
                "<th>累计(mm)</th><th>岩性</th><th>面积(cm2)</th></tr>")
    for lay in layers:
        body.append(f"<tr><td>L{lay['idx']:02d}</td><td>{lay['thr']:.1f}</td>"
                    f"<td>{FOAM_MM:.1f}</td><td>{lay['stack']:.1f}</td>"
                    f"<td style='background:{lay['color']}'>{lay['name']}</td>"
                    f"<td>{lay['area']/100:.1f}</td></tr>")
    body.append("</table></div>")

    # --- 区域布局 panel ---
    body.append("<div class='panel' id='zones'>")
    body.append(f"<svg viewBox='0 0 {W} {D}' width='{min(1000, int(W))}' "
                f"height='{int(min(1000, W) * D / W)}'>")
    body.append(f"<rect width='{W}' height='{D}' fill='#fafafa'/>")
    body.append(f"<rect x='0' y='0' width='{A_END}' height='{D}' "
                f"fill='{ZONE_COLORS['A']}' fill-opacity='0.15'/>")
    body.append(f"<rect x='{A_END}' y='0' width='{B_END-A_END}' height='{D}' "
                f"fill='{ZONE_COLORS['B']}' fill-opacity='0.15'/>")
    body.append(f"<rect x='{B_END}' y='0' width='{W-B_END}' height='{D}' "
                f"fill='{ZONE_COLORS['C']}' fill-opacity='0.15'/>")
    body.append(f"<line x1='{A_END}' y1='0' x2='{A_END}' y2='{D}' "
                f"stroke='#666' stroke-width='1.5' stroke-dasharray='10,5'/>")
    body.append(f"<line x1='{B_END}' y1='0' x2='{B_END}' y2='{D}' "
                f"stroke='#666' stroke-width='1.5' stroke-dasharray='10,5'/>")
    body.append(f"<text x='{A_END/2}' y='40' font-size='28' fill='#333' "
                f"text-anchor='middle' font-weight='bold'>A区</text>")
    body.append(f"<text x='{(A_END+B_END)/2}' y='40' font-size='28' fill='#333' "
                f"text-anchor='middle' font-weight='bold'>B区</text>")
    body.append(f"<text x='{(B_END+W)/2}' y='40' font-size='28' fill='#333' "
                f"text-anchor='middle' font-weight='bold'>C区</text>")
    # 滑坡体
    body.append(f"<rect x='225' y='450' width='150' height='200' "
                f"fill='#D4624A' fill-opacity='0.3' stroke='#C0392B' "
                f"stroke-width='1.2' stroke-dasharray='6,3'/>")
    body.append(f"<text x='300' y='560' font-size='16' fill='#C0392B' "
                f"text-anchor='middle'>滑坡体</text>")
    # 建筑物
    for cx, cy, w, d, h, btype, name in buildings:
        color = "#E67E22" if btype == "public" else "#2C3E50"
        body.append(f"<rect x='{cx-w/2:.1f}' y='{cy-d/2:.1f}' "
                    f"width='{w}' height='{d}' fill='{color}' "
                    f"fill-opacity='0.3' stroke='{color}' stroke-width='0.8'/>")
    # 道路
    for x1, y1, x2, y2, rw, name in ROADS:
        body.append(f"<line x1='{x1}' y1='{y1}' x2='{x2}' y2='{y2}' "
                    f"stroke='#555' stroke-width='{rw}' stroke-opacity='0.3'/>")
    body.append(f"<rect x='0' y='0' width='{W}' height='{D}' "
                f"fill='none' stroke='#000' stroke-width='1.5'/>")
    body.append("</svg>")
    body.append("</div>")

    # --- 设备部署 panel ---
    body.append("<div class='panel' id='devices'>")
    body.append(f"<svg viewBox='0 0 {W} {D}' width='{min(1000, int(W))}' "
                f"height='{int(min(1000, W) * D / W)}'>")
    body.append(f"<rect width='{W}' height='{D}' fill='#fafafa'/>")
    body.append(f"<rect x='0' y='0' width='{A_END}' height='{D}' "
                f"fill='{ZONE_COLORS['A']}' fill-opacity='0.08'/>")
    body.append(f"<rect x='{A_END}' y='0' width='{B_END-A_END}' height='{D}' "
                f"fill='{ZONE_COLORS['B']}' fill-opacity='0.08'/>")
    body.append(f"<rect x='{B_END}' y='0' width='{W-B_END}' height='{D}' "
                f"fill='{ZONE_COLORS['C']}' fill-opacity='0.08'/>")
    dev_colors = {
        "crack_meter": "#E74C3C", "displacement": "#8E44AD", "inclinometer": "#9B59B6",
        "soil_moisture": "#27AE60", "rain_gauge": "#3498DB", "accelerometer": "#F39C12",
        "temp_humidity": "#1ABC9C", "camera": "#34495E", "water_level": "#2980B9",
        "vibration": "#E67E22", "led_matrix": "#2ECC71", "alarm": "#E74C3C",
        "servo": "#D35400", "pump": "#16A085", "valve": "#F1C40F",
    }
    for code, name, dtype, zone, dx, dy, dz, desc in devices:
        c = dev_colors.get(dtype, "#333")
        body.append(f"<circle cx='{dx}' cy='{dy}' r='10' fill='{c}' "
                    f"stroke='#fff' stroke-width='2' class='device-dot'>"
                    f"<title>{name} ({code}) — {desc}</title></circle>")
        body.append(f"<text x='{dx+14}' y='{dy+5}' font-size='10' fill='#333'>{name}</text>")
    body.append(f"<rect x='0' y='0' width='{W}' height='{D}' "
                f"fill='none' stroke='#000' stroke-width='1.5'/>")
    body.append("</svg>")

    # 图例
    body.append("<div class='legend'>")
    seen = set()
    for _, name, dtype, _, _, _, _, _ in devices:
        c = dev_colors.get(dtype, "#333")
        if dtype not in seen:
            seen.add(dtype)
            body.append(f"<div class='legend-item'><div class='legend-dot' "
                        f"style='background:{c}'></div>{name}</div>")
    body.append("</div>")

    # 设备表
    body.append("<table><tr><th>编号</th><th>名称</th><th>类型</th>"
                "<th>区域</th><th>X(mm)</th><th>Y(mm)</th><th>说明</th></tr>")
    for code, name, dtype, zone, dx, dy, dz, desc in devices:
        body.append(f"<tr><td>{code}</td><td>{name}</td><td>{dtype}</td>"
                    f"<td>{zone}</td><td>{dx}</td><td>{dy}</td><td>{desc}</td></tr>")
    body.append("</table></div>")

    body.append("""
    <script>
    function showTab(id){
        document.querySelectorAll('.tab').forEach(t=>t.classList.remove('active'));
        document.querySelectorAll('.panel').forEach(p=>p.classList.remove('active'));
        event.target.classList.add('active');
        document.getElementById(id).classList.add('active');
    }
    </script>
    </body></html>
    """)
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(body))


# ============================================================ CSV 输出

def write_device_csv(path, devices):
    with open(path, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(["编号", "名称", "类型", "区域", "X_mm", "Y_mm", "Z_mm",
                    "对应ESP32节点", "MQTT主题", "说明"])
        node_map = {"A": "Node-A", "B": "Node-A", "C": "Node-C"}
        for code, name, dtype, zone, dx, dy, dz, desc in devices:
            node = node_map.get(zone, "Node-A")
            topic = f"disasterguard/{code}/data"
            w.writerow([code, name, dtype, zone, dx, dy, dz, node, topic, desc])


def write_parts_csv(path):
    parts = [
        ("P01", "沙盘底座", "铝合金框架", "1500x1000x150", 1, ""),
        ("P02", "A区泡沫板", "XPS泡沫板 20mm", "600x1000x20", 13, "分层堆叠"),
        ("P03", "B区泡沫板", "XPS泡沫板 20mm", "500x1000x20", 3, "分层堆叠"),
        ("P04", "C区泡沫板", "XPS泡沫板 20mm", "400x1000x20", 2, "分层堆叠"),
        ("P05", "滑坡体", "XPS泡沫块", "150x200x40", 1, "可动,底部滑轨"),
        ("P06", "亚克力水槽", "亚克力板 5mm", "500x1000x100", 1, "B区,定制"),
        ("P07", "亚克力围边", "亚克力板 5mm", "1500x1000x150", 1, "四周围边"),
        ("P08", "仿真建筑", "ABS/树脂", "多种尺寸", 8, "C区"),
        ("P09", "LED点阵屏", "32x16 P5", "80x40mm", 1, "C区"),
        ("P10", "声光报警器", "12V", "标准", 1, "C区"),
        ("P11", "SG90舵机", "SG90", "标准", 2, "A区滑坡体+B区闸门"),
        ("P12", "微型水泵", "5V直流", "标准", 1, "B区水位控制"),
        ("P13", "电磁阀", "12V", "标准", 1, "A区降雨控制"),
        ("P14", "微型喷淋头", "标准", "标准", 2, "A区降雨模拟"),
        ("P15", "仿真植被", "草粉/树模型", "—", 1, "批,表面覆盖"),
        ("P16", "石膏", "粉饰石膏", "—", 5, "kg,表面塑造"),
        ("P17", "道路贴纸", "比例模型道路", "—", 1, "C区路面"),
    ]
    with open(path, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(["编号", "名称", "材料/规格", "尺寸(mm)", "数量", "备注"])
        for p in parts:
            w.writerow(p)


# ============================================================ 主流程

def main():
    ap = argparse.ArgumentParser(description="混合沙盘地形建模工具（C方案）")
    ap.add_argument("--out", default="../examples/mixed_sandbox",
                    help="输出目录")
    ap.add_argument("--grid-mm", type=float, default=5.0,
                    help="地形网格分辨率 mm/格（越小越精细越慢）")
    ap.add_argument("--foam-mm", type=float, default=FOAM_MM,
                    help="泡沫板厚度 mm")
    ap.add_argument("--model-grid-x", type=int, default=150,
                    help="3D 模型 X 方向网格数")
    ap.add_argument("--model-grid-y", type=int, default=100,
                    help="3D 模型 Y 方向网格数")
    ap.add_argument("--water-level", type=float, default=WATER_LEVEL_MM,
                    help="水面高程 mm")
    ap.add_argument("--smooth", type=int, default=1, help="平滑次数")
    ap.add_argument("--stl", action="store_true", help="额外导出 STL")
    args = ap.parse_args()

    out_dir = args.out
    os.makedirs(out_dir, exist_ok=True)
    layer_dir = os.path.join(out_dir, "foam_layers")
    os.makedirs(layer_dir, exist_ok=True)

    # ---- 1. 生成地形 ----
    nx = max(60, int(round(WIDTH_MM / args.grid_mm)))
    ny = max(40, int(round(DEPTH_MM / args.grid_mm)))
    z, xs, ys = generate_terrain(nx, ny)
    print(f"[INFO] 地形网格 {nx}x{ny}（{args.grid_mm} mm/格）")

    if args.smooth > 0:
        z = foam.smooth(z, args.smooth)
        print(f"[INFO] 平滑 {args.smooth} 次")

    zmin, zmax = float(z.min()), float(z.max())
    relief = zmax - zmin
    print(f"[INFO] 高程范围 {zmin:.1f} ~ {zmax:.1f} mm（起伏 {relief:.1f} mm）")

    # ---- 2. 泡沫分层 ----
    n_layers = max(2, int(math.ceil(relief / args.foam_mm)))
    print(f"[INFO] 泡沫分层 {n_layers} 层 x {args.foam_mm:.0f} mm")

    cap = relief * 0.999
    layers = []
    for i in range(n_layers):
        thr = min(i * args.foam_mm, cap)
        in_mask = z >= thr
        frac = float(in_mask.mean())
        polys = foam.contour_polygons(z, xs, ys, thr)
        border_in = bool(in_mask[0, :].any() or in_mask[-1, :].any()
                         or in_mask[:, 0].any() or in_mask[:, -1].any())

        full_block = False
        if frac > 0.999:
            polys = [[(0.0, 0.0), (WIDTH_MM, 0.0), (WIDTH_MM, DEPTH_MM), (0.0, DEPTH_MM)]]
            border_in = True
            full_block = True

        if i == n_layers - 1:
            name, color = TOPSOIL
        else:
            name, color = STRATA[i % len(STRATA)]

        area = frac * WIDTH_MM * DEPTH_MM
        svg_path = os.path.join(layer_dir, f"L{i+1:02d}_z{thr:.0f}mm.svg")
        foam.write_layer_svg(svg_path, polys, WIDTH_MM, DEPTH_MM,
                             color, f"{i+1:02d}", thr, args.foam_mm,
                             full_block, border_in)
        layers.append(dict(idx=i + 1, thr=thr, thickness=args.foam_mm,
                           stack=(i + 1) * args.foam_mm, name=name, color=color,
                           area=area, polys=polys, n_poly=len(polys),
                           full=full_block, border=border_in))

    # 层表 CSV
    csv_path = os.path.join(out_dir, "layer_table.csv")
    with open(csv_path, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(["层号", "阈值(mm)", "板厚(mm)", "累计(mm)", "岩性",
                    "配色", "轮廓数", "面积(cm2)", "整块板"])
        for l in layers:
            w.writerow([f"L{l['idx']:02d}", f"{l['thr']:.1f}", f"{l['thickness']:.1f}",
                        f"{l['stack']:.1f}", l["name"], l["color"],
                        l["n_poly"], f"{l['area']/100:.1f}",
                        "是" if l["full"] else "否"])
    print(f"[OK] 层表 -> {csv_path}")

    # ---- 3. 3D 模型 ----
    # 重采样到模型网格
    mgx, mgy = args.model_grid_x, args.model_grid_y
    z_model = foam.resample(z, mgx, mgy)
    xs_m = np.linspace(0, WIDTH_MM / 1000.0, mgx)  # mm -> m
    ys_m = np.linspace(0, DEPTH_MM / 1000.0, mgy)
    z_model_m = z_model / 1000.0  # mm -> m

    rng = random.Random(20260921)
    ifc = IfcBuilder("DisasterGuard 混合沙盘 C方案", rng=rng)
    features = []

    # 地形 TIN
    tv, tf = _build_terrain_mesh(z_model_m, xs_m, ys_m)
    ifc.add_mesh(tv, tf, "TERRAIN", "TERRAIN 三区地形",
                 container="site", cls="IFCGEOGRAPHICELEMENT",
                 predefined=".TERRAIN.")
    print(f"[OK] 地形 TIN：{len(tv)} 点 / {len(tf)} 面")

    # 水面
    water_z = args.water_level / 1000.0
    wv, wf, _ = _build_water_mesh(z_model_m, xs_m, ys_m, water_z, river_x=0.85)
    if wv:
        ifc.add_mesh(wv, wf, "WATER", f"WATER 水面 z={args.water_level:.0f}mm",
                     container="site", cls="IFCGEOGRAPHICELEMENT",
                     predefined=".USERDEFINED.")
        print(f"[OK] 水面：{len(wv)} 点 / {len(wf)} 面")

    # 滑坡体
    z_terrain_fn = _make_sampler(z, xs, ys)
    lv, lf, lmeta = landslide_geometry(z, xs, ys)
    # 转换为米
    lv_m = [(p[0] / 1000.0, p[1] / 1000.0, p[2] / 1000.0) for p in lv]
    ifc.add_mesh(lv_m, lf, "LANDSLIDE", "LANDSLIDE 可动滑坡体",
                 container="storey")
    print(f"[OK] 滑坡体：{len(lv_m)} 点 / {len(lf)} 面 @ ({lmeta[0]:.0f},{lmeta[1]:.0f})mm")
    features.append(["LANDSLIDE", "可动滑坡体", "movable_block",
                     round(lmeta[0] / 1000, 3), round(lmeta[1] / 1000, 3),
                     round(lmeta[2] / 1000, 3), round(lmeta[5] / 1000, 3),
                     round(lmeta[3] * lmeta[4] / 1e6, 4), ""])

    # 建筑物
    for cx, cy, bw, bd, bh, btype, bname in BUILDINGS:
        zt = z_terrain_fn(cx, cy)
        bv, bf = building_geometry(cx / 1000.0, cy / 1000.0,
                                   bw / 1000.0, bd / 1000.0,
                                   bh / 1000.0, zt / 1000.0)
        cls = "BLD-PUBLIC" if btype == "public" else "BLD-CITY"
        ifc.add_mesh(bv, bf, cls, f"{cls} {bname}")
        features.append([cls, bname, btype, round(cx / 1000, 3),
                         round(cy / 1000, 3), round(zt / 1000, 3),
                         round(bh / 1000, 3), round(bw * bd / 1e6, 4), ""])
    print(f"[OK] 建筑：{len(BUILDINGS)} 栋（C区城镇）")

    # 道路
    for x1, y1, x2, y2, rw, rname in ROADS:
        pts = _make_road_pts(x1, y1, x2, y2)
        rv, rf = road_ribbon(pts, rw / 1000.0, z_terrain_fn)
        rv_m = [(p[0] / 1000.0, p[1] / 1000.0, p[2] / 1000.0) for p in rv]
        ifc.add_mesh(rv_m, rf, "ROAD-URBAN", f"ROAD-URBAN {rname}")
        features.append(["ROAD-URBAN", rname, "urban_road",
                         round(x1 / 1000, 3), round(y1 / 1000, 3),
                         "", "", "", ""])
    print(f"[OK] 道路：{len(ROADS)} 条")

    # IFC
    ifc_path = os.path.join(out_dir, "model.ifc")
    n = ifc.write_ifc(ifc_path)
    print(f"[OK] IFC4 -> {ifc_path}（{n} 构件 / "
          f"{os.path.getsize(ifc_path) // 1024} KB）")

    # OBJ
    obj_path = os.path.join(out_dir, "model.obj")
    ifc.write_obj(obj_path)
    print(f"[OK] OBJ -> {obj_path}")

    # Revit 地形点文件
    csv_path = os.path.join(out_dir, "terrain_points.csv")
    with open(csv_path, "w", encoding="utf-8") as f:
        for i in range(mgy):
            for j in range(mgx):
                f.write(f"{xs_m[j]:.4f},{ys_m[i]:.4f},"
                        f"{float(z_model[i, j]) / 1000.0:.4f}\n")
    print(f"[OK] Revit 地形点文件 -> {csv_path}（{mgx * mgy} 点）")

    # 要素清单
    fea_path = os.path.join(out_dir, "features.csv")
    with open(fea_path, "w", encoding="utf-8") as f:
        f.write("class,name,kind,X_m,Y_m,Z_base_m,height_m,footprint_m2,levels\n")
        for row in features:
            f.write(",".join(str(x) for x in row) + "\n")
    print(f"[OK] 要素清单 -> {fea_path}（{len(features)} 条）")

    # ---- 4. 区域布局图 ----
    layout_path = os.path.join(out_dir, "zone_layout.svg")
    write_zone_layout(layout_path, z, xs, ys, DEVICES, BUILDINGS, ROADS)
    print(f"[OK] 区域布局图 -> {layout_path}")

    # 滑坡体零件图
    ls_path = os.path.join(out_dir, "landslide_body.svg")
    write_landslide_svg(ls_path)
    print(f"[OK] 滑坡体零件图 -> {ls_path}")

    # ---- 5. CSV 输出 ----
    dev_path = os.path.join(out_dir, "device_points.csv")
    write_device_csv(dev_path, DEVICES)
    print(f"[OK] 设备部署点位 -> {dev_path}")

    parts_path = os.path.join(out_dir, "parts_list.csv")
    write_parts_csv(parts_path)
    print(f"[OK] 加工零件清单 -> {parts_path}")

    # ---- 6. 预览 HTML ----
    meta = (f"沙盘 {WIDTH_MM:.0f}x{DEPTH_MM:.0f}x150 mm | "
            f"A区 {ZONE_LABELS['A']} | B区 {ZONE_LABELS['B']} | "
            f"C区 {ZONE_LABELS['C']} | "
            f"{n_layers} 层 x {args.foam_mm:.0f}mm | "
            f"起伏 {relief:.0f}mm | 水位 {args.water_level:.0f}mm")
    prev_path = os.path.join(out_dir, "preview.html")
    write_preview_html(prev_path, layers, DEVICES, BUILDINGS, meta)
    print(f"[OK] 预览页面 -> {prev_path}")

    # ---- 7. 可选 STL ----
    if args.stl:
        stl_path = os.path.join(out_dir, "terrain.stl")
        gx = min(nx, 420)
        gy = min(ny, 280)
        zs = foam.resample(z, gx, gy)
        foam.write_stl(stl_path, zs, WIDTH_MM, DEPTH_MM)
        print(f"[OK] STL -> {stl_path}")

    # ---- 耗材估算 ----
    total_area = sum(l["area"] for l in layers) / 100.0
    print(f"\n[INFO] 泡沫板合计面积约 {total_area:.0f} cm2"
          f"（约 {total_area / 10000:.2f} m2，建议按 1.3 倍备料）")
    print(f"[OK] 全部完成，输出目录：{os.path.abspath(out_dir)}")

    print("\n[Revit 导入]")
    print("  1) Revit -> 打开 -> 文件类型 IFC -> 选 model.ifc -> 另存为 .rvt")
    print("  2) 或 体量和场地 -> 地形表面 -> 通过导入创建 -> 指定点文件")
    print(f"     -> 选 terrain_points.csv，单位 米")
    print("  3) 构件分类：TERRAIN / WATER / LANDSLIDE /")
    print("     BLD-CITY / BLD-PUBLIC / ROAD-URBAN")


# ============================================================ 辅助

def _build_terrain_mesh(z, xs, ys):
    """地形 TIN + 裙边 + 底座。"""
    from build_revit_model import build_terrain
    return build_terrain(z, xs, ys)


def _build_water_mesh(z, xs, ys, level_m, river_x=0.85, tolerance=0.03):
    """B区水面网格。"""
    ny, nx = z.shape
    mask = np.zeros((ny, nx), dtype=bool)
    for i in range(ny):
        for j in range(nx):
            if abs(xs[j] - river_x) < 0.12 and z[i, j] <= level_m + 0.01:
                mask[i, j] = True
    # 也包含所有低于水位的格子
    mask |= (z <= level_m)
    # 只保留 B区范围
    for j in range(nx):
        if xs[j] < 0.55 or xs[j] > 1.20:
            mask[:, j] = False

    from collections import deque
    seen = np.zeros_like(mask)
    out = np.zeros_like(mask)
    for i0 in range(ny):
        for j0 in range(nx):
            if not mask[i0, j0] or seen[i0, j0]:
                continue
            q = deque([(i0, j0)])
            seen[i0, j0] = True
            comp = []
            while q:
                a, b = q.popleft()
                comp.append((a, b))
                for da, db in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    na, nb = a + da, b + db
                    if 0 <= na < ny and 0 <= nb < nx and mask[na, nb] and not seen[na, nb]:
                        seen[na, nb] = True
                        q.append((na, nb))
            if len(comp) >= 8:
                for a, b in comp:
                    out[a, b] = True

    if not out.any():
        return [], [], None

    verts, faces, index = [], [], {}
    for i in range(ny):
        for j in range(nx):
            if out[i, j]:
                index[(i, j)] = len(verts)
                verts.append((float(xs[j]), float(ys[i]), level_m))
    for (i, j), a in list(index.items()):
        k1 = index.get((i, j + 1))
        k2 = index.get((i + 1, j + 1))
        k3 = index.get((i + 1, j))
        if k1 is not None and k2 is not None and k3 is not None:
            faces += quad_faces(a, k1, k2, k3)
    return verts, faces, out


def _make_sampler(z, xs, ys):
    """返回 (x_mm, y_mm) -> z_mm 的采样函数。"""
    from build_revit_model import sample_z
    ny, nx = z.shape
    span_x = xs[-1] - xs[0]
    span_y = ys[-1] - ys[0]
    def fn(x, y):
        u = max(0.0, min(1.0, x / span_x))
        v = max(0.0, min(1.0, y / span_y))
        return sample_z(z, u, v)
    return fn


def _make_road_pts(x1, y1, x2, y2, step=20.0):
    """从两点生成道路中心线点列。"""
    length = math.hypot(x2 - x1, y2 - y1)
    n = max(2, int(length / step))
    return [((x1 + (x2 - x1) * t / n), (y1 + (y2 - y1) * t / n)) for t in range(n + 1)]


if __name__ == "__main__":
    main()
