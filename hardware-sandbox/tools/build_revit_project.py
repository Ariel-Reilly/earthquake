#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
混合沙盘 → Revit 项目级建模工具
====================================================================
把 examples/mixed_sandbox/realistic_render.html 实景渲染的全部要素
转成一份「Revit 原生项目级」IFC4 模型：

  空间结构: IfcProject → IfcSite → IfcBuilding → IfcBuildingStorey
  构件类别: 地形/水面(GeoGraphy) + 滑坡体/建筑/屋顶/道路/树木/岩石/
            底座/围框/分区标记/设备(传感器·LED屏·报警器) (BuildingProxy)
  属性表  : 每个构件挂 Pset_DisasterGuard（分区/材质/高程/尺寸等），
            在 Revit 里以“项目信息/共享参数”形式可见

输出（默认 examples/revit_project/）：
  model.ifc            项目级 IFC4（Revit 打开→另存为 .rvt 即成真实项目）
  model.obj            OBJ（Blender/Lumion 检查用）
  terrain_points.csv   Revit「地形表面→通过导入创建」点文件（米）
  features.csv         构件清单（类别/位置/尺寸）
  devices.csv          设备点位与 Revit 族建议
====================================================================
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

from build_revit_model import (IfcBuilder, _guid, build_terrain,
                               ear_clip, quad_faces, sample_z)
import build_mixed_sandbox as msb
import dem_to_foam as foam

W_MM, D_MM = msb.WIDTH_MM, msb.DEPTH_MM   # 1500 x 1000 mm


# ============================================================ 基础几何


def box(cx, cy, w, d, h, zb):
    """闭合长方体（含底面），返回 verts/faces（米制）。"""
    hw, hd = w / 2, d / 2
    zt = zb + h
    corners = [(cx - hw, cy - hd), (cx + hw, cy - hd),
               (cx + hw, cy + hd), (cx - hw, cy + hd)]
    verts = [(p[0], p[1], zb) for p in corners] + \
            [(p[0], p[1], zt) for p in corners]
    faces = []
    n = 4
    for k in range(n):
        a, b = k, (k + 1) % n
        faces += quad_faces(a, b, n + b, n + a)
    top = [(n + a, n + c, n + b) for a, b, c in ear_clip(corners)]
    bot = [(a, b, c) for a, b, c in ear_clip(corners[::-1])]
    return verts, faces + top + bot


def pyramid(cx, cy, r, h, zb):
    """四角锥（菱形底，对应渲染里 rotate 45° 的 4 段 ConeGeometry）。"""
    corners = [(cx + r, cy), (cx, cy + r), (cx - r, cy), (cx, cy - r)]
    verts = [(p[0], p[1], zb) for p in corners] + [(cx, cy, zb + h)]
    faces = []
    for k in range(4):
        a, b = k, (k + 1) % 4
        faces.append((a, b, 4))
    faces += [(a, b, c) for a, b, c in ear_clip(corners[::-1])]
    return verts, faces


def cylinder(cx, cy, r, h, zb, seg=12, r_top=None):
    """闭合圆柱/圆锥台（r_top=0 即锥）。"""
    rt = r if r_top is None else r_top
    zt = zb + h
    verts, faces = [], []
    for k in range(seg):
        a = 2 * math.pi * k / seg
        verts.append((cx + r * math.cos(a), cy + r * math.sin(a), zb))
    for k in range(seg):
        a = 2 * math.pi * k / seg
        verts.append((cx + rt * math.cos(a), cy + rt * math.sin(a), zt))
    cb, ct = len(verts), len(verts) + 1
    verts += [(cx, cy, zb), (cx, cy, zt)]
    for k in range(seg):
        a, b = k, (k + 1) % seg
        faces += quad_faces(a, b, seg + b, seg + a)
        if rt > 1e-9:
            faces.append((b, a, cb))
        faces.append((seg + a, seg + b, ct))
    return verts, faces


def rock(cx, cy, s, zb, rng):
    """不规则块石（十二面体级近似：随机扰动八面体）。"""
    base = [(s, 0, 0), (-s, 0, 0), (0, s, 0), (0, -s, 0),
            (0, 0, s), (0, 0, -s)]
    verts = []
    for (vx, vy, vz) in base:
        j = s * 0.35
        verts.append((cx + vx + rng.uniform(-j, j),
                      cy + vy + rng.uniform(-j, j),
                      zb + s * 0.4 + vz + rng.uniform(-j, j)))
    faces = [(0, 2, 4), (2, 1, 4), (1, 3, 4), (3, 0, 4),
             (2, 0, 5), (1, 2, 5), (3, 1, 5), (0, 3, 5)]
    return verts, faces


# ============================================================ 属性表


def attach_psets(ifc: IfcBuilder, items):
    """给构件挂 IFCPROPERTYSET（Revit 导入后显示为项目信息参数）。"""
    for eid, props in items:
        if eid is None:
            continue
        pid_list = []
        for key, val in props.items():
            pid = ifc._id()
            if isinstance(val, (int, float)):
                ifc.body.append(
                    f"#{pid}=IFCPROPERTYSINGLEVALUE('{key}',$,"
                    f"IFCLENGTHMEASURE({float(val):.5f}),$);")
            else:
                ifc.body.append(
                    f"#{pid}=IFCPROPERTYSINGLEVALUE('{key}',$,"
                    f"IFCLABEL('{val}'),$);")
            pid_list.append(f"#{pid}")
        psid = ifc._id()
        ifc.body.append(
            f"#{psid}=IFCPROPERTYSET('{_guid(ifc.rng)}',#5,"
            f"'Pset_DisasterGuard',$,({','.join(pid_list)}));")
        rid = ifc._id()
        ifc.body.append(
            f"#{rid}=IFCRELDEFINESBYPROPERTIES('{_guid(ifc.rng)}',#5,$,$,"
            f"(#{eid}),#{psid});")


# ============================================================ 主建模


def build_all(out_dir, mgx=150, mgy=100, water_mm=msb.WATER_LEVEL_MM,
              smooth=1, grid_mm=5.0):
    os.makedirs(out_dir, exist_ok=True)

    # ---- 地形（与 build_mixed_sandbox 同一生成器，保证几何一致） ----
    nx = max(60, int(round(W_MM / grid_mm)))
    ny = max(40, int(round(D_MM / grid_mm)))
    z, xs, ys = msb.generate_terrain(nx, ny)
    if smooth > 0:
        z = foam.smooth(z, smooth)

    z_model = foam.resample(z, mgx, mgy)
    xs_m = np.linspace(0, W_MM / 1000.0, mgx)
    ys_m = np.linspace(0, D_MM / 1000.0, mgy)
    z_m = z_model / 1000.0

    rng = random.Random(20260921)
    ifc = IfcBuilder("DisasterGuard 混合沙盘 — Revit 项目", rng=rng)
    psets = []
    features = []

    def sampler_m(x_m, y_m):
        return sample_z(z, x_m * 1000.0 / W_MM, y_m * 1000.0 / D_MM) / 1000.0

    # 1) 地形 TIN -----------------------------------------------------
    tv, tf = build_terrain(z_m, xs_m, ys_m)
    eid = ifc.add_mesh(tv, tf, "TERRAIN", "地形 三区一体化 (A滑坡/B河谷/C城镇)",
                       container="site", cls="IFCGEOGRAPHICELEMENT",
                       predefined=".TERRAIN.")
    psets.append((eid, {"分区": "全区", "材质": "XPS泡沫+石膏+草粉",
                        "最大起伏mm": float(z.max())}))

    # 2) B区水面 ------------------------------------------------------
    wv, wf = box(0.85, 0.5, 0.50, 0.84, 0.004, water_mm / 1000.0)
    eid = ifc.add_mesh(wv, wf, "WATER", "水面 (常态高程)",
                       container="site", cls="IFCGEOGRAPHICELEMENT",
                       predefined=".USERDEFINED.")
    psets.append((eid, {"分区": "B", "常态水位mm": water_mm,
                        "洪水位mm": 28.0, "控制": "5V水泵 PWM"}))
    features.append(["WATER", "水面", "pond", 0.85, 0.5,
                     round(water_mm / 1000, 3), 0.004, 0.42, ""])

    # 3) B区亚克力水槽壁（5mm 板，内空 500x840） -----------------------
    tank_parts = []
    thk, th = 0.005, 0.10
    tx, ty = 0.85, 0.5
    for (ox, oy, w, d) in [(0, -0.42, 0.50, thk), (0, 0.42, 0.50, thk),
                           (-0.25, 0, thk, 0.84), (0.25, 0, thk, 0.84)]:
        v, f = box(tx + ox, ty + oy, w, d, th, -0.005)
        tank_parts.append((v, f))
    allv, allf = [], []
    for v, f in tank_parts:
        off = len(allv)
        allv += v
        allf += [(a + off, b + off, c + off) for a, b, c in f]
    eid = ifc.add_mesh(allv, allf, "TANK", "亚克力水槽 (B区)",
                       cls="IFCBUILDINGELEMENTPROXY")
    psets.append((eid, {"分区": "B", "材质": "亚克力5mm",
                        "净尺寸": "500x840x100mm"}))
    features.append(["TANK", "亚克力水槽", "basin", 0.85, 0.5,
                     -0.005, 0.10, 0.42, ""])

    # 4) 可动滑坡体 + 崩塌岩块 + 滑轨 ----------------------------------
    lv, lf, lmeta = msb.landslide_geometry(z, xs, ys)
    lv_m = [(p[0] / 1000, p[1] / 1000, p[2] / 1000) for p in lv]
    eid = ifc.add_mesh(lv_m, lf, "LANDSLIDE", "可动滑坡体 (SG90舵机驱动)",
                       container="storey")
    psets.append((eid, {"分区": "A", "尺寸": "150x200x40mm",
                        "驱动": "SG90+滑轨", "监测": "AS5311位移计/裂缝计/MPU6050",
                        "滑移方向": "+X 150mm"}))
    features.append(["LANDSLIDE", "可动滑坡体", "movable_block",
                     round(lmeta[0] / 1000, 3), round(lmeta[1] / 1000, 3),
                     round(lmeta[2] / 1000, 3), round(lmeta[5] / 1000, 3),
                     round(lmeta[3] * lmeta[4] / 1e6, 4), ""])

    for k in range(5):                      # 滑坡体上 5 块崩塌岩
        s = 0.020 + rng.random() * 0.030
        rx = lmeta[0] / 1000 + (rng.random() - 0.5) * 0.14
        ry = lmeta[1] / 1000 + (rng.random() - 0.5) * 0.18
        v, f = rock(rx, ry, s / 2, lmeta[2] / 1000 + 0.04, rng)
        ifc.add_mesh(v, f, "DEBRIS", f"崩塌岩块-{k + 1}")
    rv, rf = box(0.30, 0.55, 0.16, 0.003, 0.001,
                 lmeta[2] / 1000)          # 滑轨
    eid = ifc.add_mesh(rv, rf, "RAIL", "滑坡体滑轨 (AS5311 基座)")
    psets.append((eid, {"分区": "A", "传感器": "AS5311 磁性位移计"}))

    # 5) C区 8 栋建筑 + 四角锥屋顶 ------------------------------------
    for cx, cy, bw, bd, bh, btype, bname in msb.BUILDINGS:
        zb = sample_z(z, cx / W_MM, cy / D_MM) / 1000.0
        v, f = box(cx / 1000, cy / 1000, bw / 1000, bd / 1000, bh / 1000, zb)
        cls = "BLD-PUBLIC" if btype == "public" else "BLD-CITY"
        eid = ifc.add_mesh(v, f, cls, f"{bname}", container="storey")
        psets.append((eid, {"分区": "C", "用途": ("公共建筑" if btype == "public"
                                                  else "住宅"),
                            "底标高m": round(zb, 3),
                            "尺寸": f"{bw}x{bd}x{bh}mm"}))
        features.append([cls, bname, btype, round(cx / 1000, 3),
                         round(cy / 1000, 3), round(zb, 3),
                         round(bh / 1000, 3), round(bw * bd / 1e6, 4), ""])
        r = max(bw, bd) * 0.75 / 1000
        v, f = pyramid(cx / 1000, cy / 1000, r, bh * 0.45 / 1000,
                       zb + bh / 1000)
        eid = ifc.add_mesh(v, f, "ROOF", f"{bname} 屋顶", container="storey")
        psets.append((eid, {"分区": "C", "类型": "四角锥坡屋顶",
                            "瓦色": ("#B8483A" if btype == "public"
                                     else "#5A3A2A")}))

    # 6) C区道路 6 条 --------------------------------------------------
    for x1, y1, x2, y2, rw, rname in msb.ROADS:
        pts = msb._make_road_pts(x1, y1, x2, y2)
        rv_, rf_ = msb.road_ribbon(pts, rw / 1000.0,
                                   lambda a, b: sample_z(z, a / W_MM, b / D_MM))
        rv_m = [(p[0] / 1000, p[1] / 1000, p[2] / 1000) for p in rv_]
        eid = ifc.add_mesh(rv_m, rf_, "ROAD-URBAN", rname, container="storey")
        psets.append((eid, {"分区": "C", "路宽mm": float(rw),
                            "材质": "沥青贴纸"}))
        features.append(["ROAD-URBAN", rname, "urban_road",
                         round(x1 / 1000, 3), round(y1 / 1000, 3),
                         "", "", "", ""])

    # 7) 植被：针叶松(30采样)/阔叶(8)/城镇(6)，与渲染同逻辑同种子 ------
    def add_pine(x, zz, r_):
        zb = sampler_m(x, zz)
        sc = 0.7 + r_.random() * 0.5
        allv, allf = [], []
        v, f = cylinder(x, zz, 0.004 * sc, 0.02 * sc, zb)
        off = 0; allv += v; allf += f
        for i in range(3):
            rr = 0.025 * sc * (1 - i * 0.2)
            v, f = cone_at(x, zz, rr, 0.035 * sc,
                           zb + (0.015 + i * 0.018) * sc - 0.0175 * sc)
            off = len(allv)
            allv += v
            allf += [(a + off, b + off, c + off) for a, b, c in f]
        return allv, allf

    def cone_at(x, zz, r, h, zb):
        return cylinder(x, zz, r, h, zb, seg=6, r_top=0.0005)

    veg = []
    r2 = random.Random(777)
    for _ in range(30):
        x = r2.random() * 0.55
        zz = r2.random() * 1.0
        h = sampler_m(x, zz)
        if 0.02 < h < 0.18:
            v, f = add_pine(x, zz, r2)
            eid = ifc.add_mesh(v, f, "VEG-PINE", "针叶树", container="storey")
            psets.append((eid, {"分区": "A", "类型": "仿真针叶"}))
    for _ in range(8):
        x = 0.6 + r2.random() * 0.45
        zz = r2.random() * 1.0
        h = sampler_m(x, zz)
        if 0.005 < h < 0.03:
            zb = h
            sc = 0.8 + r2.random() * 0.4
            v, f = cylinder(x, zz, 0.005 * sc, 0.025 * sc, zb)
            ifc.add_mesh(v, f, "VEG-TREE", "阔叶树(树干)", container="storey")
            v, f = cylinder(x, zz, 0.022 * sc, 0.030 * sc,
                            zb + 0.020 * sc, seg=8)
            eid = ifc.add_mesh(v, f, "VEG-TREE", "阔叶树(树冠)",
                               container="storey")
            psets.append((eid, {"分区": "B", "类型": "仿真阔叶"}))
    for _ in range(6):
        x = 1.15 + r2.random() * 0.30
        zz = r2.random() * 1.0
        if zz < 0.05 or zz > 0.95:
            continue
        zb = sampler_m(x, zz)
        sc = 0.6 + r2.random() * 0.3
        v, f = cylinder(x, zz, 0.0035 * sc, 0.015 * sc, zb)
        allv, allf = list(v), list(f)
        v, f = cylinder(x, zz, 0.016 * sc, 0.028 * sc,
                        zb + 0.007 * sc, seg=8)
        off = len(allv)
        allv += v
        allf += [(a + off, b + off, c + off) for a, b, c in f]
        eid = ifc.add_mesh(allv, allf, "VEG-URBAN", "城镇行道树",
                           container="storey")
        psets.append((eid, {"分区": "C", "类型": "行道树"}))

    # 8) 散石（A区 20 采样 + 河岸 8 采样） -----------------------------
    r3 = random.Random(4242)
    n_rock = 0
    for _ in range(20):
        x = r3.random() * 0.55
        zz = r3.random() * 1.0
        h = sampler_m(x, zz)
        if not (0.04 < h < 0.16):
            continue
        s = 0.008 + r3.random() * 0.02
        v, f = rock(x, zz, s, h, r3)
        ifc.add_mesh(v, f, "ROCK", f"坡面散石-{n_rock + 1}", container="storey")
        n_rock += 1
    for _ in range(8):
        x = 0.55 + r3.random() * 0.5
        zz = r3.random() * 1.0
        h = sampler_m(x, zz)
        if not (0.01 < h < 0.025):
            continue
        s = 0.006 + r3.random() * 0.012
        v, f = rock(x, zz, s, h, r3)
        ifc.add_mesh(v, f, "ROCK", f"河岸卵石-{n_rock + 1}", container="storey")
        n_rock += 1

    # 9) 底座 + 铝合金围框 ---------------------------------------------
    v, f = box(0.75, 0.5, 1.54, 1.04, 0.02, -0.025)
    eid = ifc.add_mesh(v, f, "BASE", "沙盘底板", container="storey")
    psets.append((eid, {"材质": "高密度泡沫底板", "尺寸": "1540x1040x20mm"}))
    frame = [
        (0.75, 1.02, 1.54, 0.02),   # 前
        (0.75, -0.02, 1.54, 0.02),  # 后
        (-0.02, 0.5, 0.02, 1.04),   # 左
        (1.52, 0.5, 0.02, 1.04),    # 右
    ]
    allv, allf = [], []
    for (fx, fy, fw, fd) in frame:
        v, f = box(fx, fy, fw, fd, 0.06, -0.025)
        off = len(allv)
        allv += v
        allf += [(a + off, b + off, c + off) for a, b, c in f]
    eid = ifc.add_mesh(allv, allf, "FRAME", "铝合金围框", container="storey")
    psets.append((eid, {"材质": "铝合金型材", "高": "60mm"}))
    features.append(["FRAME", "沙盘底座+围框", "structure", 0.75, 0.5,
                     -0.025, 0.065, 1.54 * 1.04, ""])

    # 10) 分区标记柱 A/B/C ---------------------------------------------
    marks = [("A", "A区标记柱", 0x8B6B47, 0.30, 0.05),
             ("B", "B区标记柱", 0x1565C0, 0.85, 0.05),
             ("C", "C区标记柱", 0x2E7D32, 1.30, 0.05)]
    for zc, nm, col, mx, mz in marks:
        zb = sampler_m(mx, mz)
        v, f = cylinder(mx, mz, 0.008, 0.06, zb)
        eid = ifc.add_mesh(v, f, "MARKER", nm, container="storey")
        psets.append((eid, {"分区": zc,
                            "颜色": f"{col:06X}", "作用": "区域标识"}))

    # 11) 设备（传感器/执行器）→ Revit MEP/家具族候选 ------------------
    dev_rows = []
    for code, name, dtype, zone, dx, dy, dz, desc in msb.DEVICES:
        x_m, y_m = dx / 1000, dy / 1000
        zb = sampler_m(x_m, y_m)
        if dtype == "led_matrix":            # 32x16 P5 屏 = 160x80mm
            v, f = cylinder(x_m, y_m, 0.004, 0.08, zb)
            allv, allf = list(v), list(f)
            v, f = box(x_m, y_m, 0.16, 0.02, 0.08, zb + 0.08)
            off = len(allv)
            allv += v
            allf += [(a + off, b + off, c + off) for a, b, c in f]
            eid = ifc.add_mesh(allv, allf, "DEVICE-LED", name,
                               container="storey")
            psets.append((eid, {"分区": zone, "编号": code,
                                "规格": "32x16 P5", "说明": desc}))
        elif dtype == "rain_gauge":          # 翻斗式雨量计
            v, f = cylinder(x_m, y_m, 0.08, 0.10, zb, seg=10)
            eid = ifc.add_mesh(v, f, "DEVICE", name, container="storey")
            psets.append((eid, {"分区": zone, "编号": code,
                                "型号": "YFS201", "说明": desc}))
        elif dtype in ("camera", "alarm"):
            v, f = cylinder(x_m, y_m, 0.012, 0.05, zb)
            allv, allf = list(v), list(f)
            v, f = box(x_m, y_m, 0.04, 0.03, 0.03, zb + 0.05)
            off = len(allv)
            allv += v
            allf += [(a + off, b + off, c + off) for a, b, c in f]
            eid = ifc.add_mesh(allv, allf, "DEVICE", name, container="storey")
            psets.append((eid, {"分区": zone, "编号": code, "说明": desc}))
        elif dtype == "pump":
            v, f = cylinder(x_m, y_m, 0.03, 0.04, zb - 0.02, seg=10)
            eid = ifc.add_mesh(v, f, "DEVICE", name, container="storey")
            psets.append((eid, {"分区": zone, "编号": code,
                                "型号": "5V直流", "说明": desc}))
        elif dtype == "servo":
            v, f = box(x_m, y_m, 0.04, 0.02, 0.04, zb)
            eid = ifc.add_mesh(v, f, "DEVICE", name, container="storey")
            psets.append((eid, {"分区": zone, "编号": code,
                                "型号": "SG90", "说明": desc}))
        else:                                # 其余小型传感器统一小盒
            v, f = box(x_m, y_m, 0.03, 0.02, 0.02, zb)
            eid = ifc.add_mesh(v, f, "DEVICE", name, container="storey")
            psets.append((eid, {"分区": zone, "编号": code, "说明": desc}))
        dev_rows.append([code, name, dtype, zone, dx, dy,
                         round(zb * 1000, 1), desc])

    # ---- 属性表落盘 + 写出 ----
    attach_psets(ifc, psets)

    ifc_path = os.path.join(out_dir, "model.ifc")
    n = ifc.write_ifc(ifc_path)
    print(f"[OK] 项目级 IFC4 -> {ifc_path}（{n} 构件 / "
          f"{os.path.getsize(ifc_path) // 1024} KB / {len(psets)} 条属性表）")

    obj_path = os.path.join(out_dir, "model.obj")
    ifc.write_obj(obj_path)
    print(f"[OK] OBJ -> {obj_path}")

    csv_path = os.path.join(out_dir, "terrain_points.csv")
    with open(csv_path, "w", encoding="utf-8") as fp:
        for i in range(mgy):
            for j in range(mgx):
                fp.write(f"{xs_m[j]:.4f},{ys_m[i]:.4f},"
                         f"{float(z_model[i, j]) / 1000.0:.4f}\n")
    print(f"[OK] Revit 地形点 -> {csv_path}（{mgx * mgy} 点，单位米）")

    fea_path = os.path.join(out_dir, "features.csv")
    with open(fea_path, "w", encoding="utf-8") as fp:
        fp.write("class,name,kind,X_m,Y_m,Z_base_m,height_m,footprint_m2,levels\n")
        for row in features:
            fp.write(",".join(str(x) for x in row) + "\n")
    print(f"[OK] 构件清单 -> {fea_path}")

    dev_path = os.path.join(out_dir, "devices.csv")
    with open(dev_path, "w", newline="", encoding="utf-8-sig") as fp:
        w = csv.writer(fp)
        w.writerow(["编号", "名称", "类型", "分区", "X_mm", "Y_mm",
                    "地面高程_mm", "Revit建议族类别", "说明"])
        fam = {"led_matrix": "电气/显示设备", "alarm": "电气/警报装置",
               "camera": "安防/摄像头", "pump": "水/机械装置",
               "servo": "通用/机电设备", "rain_gauge": "通用/气象设备",
               "valve": "管道/阀门部件"}
        for r in dev_rows:
            w.writerow(r[:7] + [fam.get(r[2], "设备/传感器"), r[7]])
    print(f"[OK] 设备点位 -> {dev_path}（{len(dev_rows)} 台）")

    return ifc_path, n


def main():
    ap = argparse.ArgumentParser(description="混合沙盘 Revit 项目级建模")
    ap.add_argument("--out", default=os.path.join(
        HERE, "..", "examples", "revit_project"))
    ap.add_argument("--model-grid-x", type=int, default=150)
    ap.add_argument("--model-grid-y", type=int, default=100)
    ap.add_argument("--grid-mm", type=float, default=5.0)
    ap.add_argument("--smooth", type=int, default=1)
    ap.add_argument("--water-level", type=float, default=msb.WATER_LEVEL_MM)
    args = ap.parse_args()
    build_all(os.path.abspath(args.out), args.model_grid_x, args.model_grid_y,
              args.water_level, args.smooth, args.grid_mm)
    print("\n[Revit 导入] File → Open → IFC → model.ifc → 另存为 .rvt 即成项目")


if __name__ == "__main__":
    main()
