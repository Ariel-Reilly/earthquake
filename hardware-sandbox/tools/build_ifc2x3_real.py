#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
混合沙盘 → 高真实感 IFC2x3 建模
====================================================================
在 build_ifc2x3.py（Revit 可正常打开的 BRep 版）基础上做真实化升级：
  · 建筑：基座 + 主体 + 楼层腰线 + 规则窗阵 + 入户门 + 檐口/女儿墙
          + 屋顶设备，住宅四角坡屋顶带出檐，公共建筑平屋顶带女儿墙
  · 道路：沥青路面 + 双侧路缘石 + 中央虚线标线（沿线形贴地形）
  · 植被：针叶树 4 层锥冠、阔叶树双球冠、行道树带树池
  · 设备：LED 屏带边框、摄像机带枪机镜头、雨量筒带漏斗、警报器带喇叭
  · 材质：全部构件挂 IfcSurfaceStyle RGB 颜色，Revit 导入后带材质色
输出：
  examples/revit_project/model_real.ifc
用法：
  python tools/build_ifc2x3_real.py --out examples/revit_project
"""

from __future__ import annotations

import argparse
import math
import os
import random
import sys

import numpy as np
import ifcopenshell

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from build_revit_model import build_terrain, sample_z, quad_faces  # noqa: E402
import build_mixed_sandbox as msb                                   # noqa: E402
import dem_to_foam as foam                                          # noqa: E402
from build_revit_project import box, pyramid, cylinder, rock        # noqa: E402
from build_ifc2x3 import BrepBuilder, _fix_winding                  # noqa: E402

W_MM, D_MM = msb.WIDTH_MM, msb.DEPTH_MM

# ------------------------------------------------------------ 色板
C_GROUND = (0.42, 0.52, 0.28)     # 草皮
C_CLAY = (0.62, 0.45, 0.28)       # 滑坡裸露面
C_WATER = (0.05, 0.42, 0.78)      # 水体
C_ACRYL = (0.80, 0.90, 0.97)      # 亚克力
C_ASPHALT = (0.28, 0.28, 0.32)    # 沥青
C_CURB = (0.66, 0.65, 0.62)       # 路缘石
C_MARK = (0.98, 0.86, 0.10)       # 标线
C_TRUNK = (0.45, 0.30, 0.16)      # 树干
C_LEAF_D = (0.13, 0.48, 0.22)     # 深针叶
C_LEAF_L = (0.24, 0.62, 0.28)     # 阔叶
C_ROCK = (0.55, 0.53, 0.50)       # 岩石
C_BASE = (0.20, 0.20, 0.24)       # 底板
C_ALU = (0.78, 0.80, 0.84)        # 铝框
C_GLASS = (0.38, 0.62, 0.88)      # 玻璃
C_DOOR = (0.45, 0.27, 0.14)       # 门
C_PLINTH = (0.55, 0.52, 0.48)     # 基座
C_BAND = (0.68, 0.64, 0.58)       # 腰线
C_ROOF_R = (0.80, 0.24, 0.18)     # 红瓦
C_ROOF_G = (0.45, 0.47, 0.50)     # 公共屋顶
C_DEV = (0.90, 0.90, 0.93)        # 设备壳
C_DEV_D = (0.18, 0.18, 0.22)      # 设备深色
C_LED = (0.04, 0.04, 0.06)        # LED 屏面

MAT_NAMES = {
    C_GROUND: "草皮", C_CLAY: "滑坡黏土", C_WATER: "水体",
    C_ACRYL: "亚克力", C_ASPHALT: "沥青路面", C_CURB: "混凝土路缘",
    C_MARK: "道路标线", C_TRUNK: "树干", C_LEAF_D: "针叶",
    C_LEAF_L: "阔叶", C_ROCK: "岩石", C_BASE: "泡沫底板",
    C_ALU: "铝合金", C_GLASS: "玻璃窗", C_DOOR: "木门",
    C_PLINTH: "石材基座", C_BAND: "混凝土腰线", C_ROOF_R: "红色陶瓦",
    C_ROOF_G: "公共屋面", C_DEV: "设备外壳", C_DEV_D: "设备深色",
    C_LED: "LED屏面",
}


class RealBuilder(BrepBuilder):
    """在 BrepBuilder 基础上支持 IfcSurfaceStyle 颜色 + IfcMaterial 关联。"""

    def __init__(self, name):
        super().__init__(name)
        self._styles = {}
        self._materials = {}

    def _style(self, rgb, transparency=0.0):
        key = tuple(round(v, 3) for v in rgb) + (round(transparency, 2),)
        s = self._styles.get(key)
        if s is None:
            m = self.m
            col = m.create_entity("IfcColourRgb", Name="RGB",
                                  Red=key[0], Green=key[1], Blue=key[2])
            # IFC2x3 中 ReflectanceMethod 为必填，缺失会导致 Revit 丢弃样式
            rend = m.create_entity("IfcSurfaceStyleRendering",
                                   SurfaceColour=col,
                                   Transparency=transparency,
                                   ReflectanceMethod="NOTDEFINED")
            tag = "M%02X%02X%02X" % tuple(
                int(round(k * 255)) for k in key[:3])
            s = m.create_entity("IfcSurfaceStyle",
                                Name=tag + ("T" if transparency else ""),
                                Side="BOTH", Styles=[rend])
            self._styles[key] = s
        return s

    def _material(self, rgb):
        key = tuple(round(v, 3) for v in rgb)
        mt = self._materials.get(key)
        if mt is None:
            tag = "%02X%02X%02X" % tuple(
                int(round(k * 255)) for k in key)
            name = f"{MAT_NAMES.get(key[:3], '材质')}_{tag}"
            mt = self.m.create_entity("IfcMaterial", Name=name)
            self._materials[key] = mt
        return mt

    def _associate_material(self, el, rgb):
        m = self.m
        mt = self._material(rgb)
        m.create_entity("IfcRelAssociatesMaterial", self._guid(m),
                        self.oh, Name=None, Description=None,
                        RelatedObjects=[el], RelatingMaterial=mt)

    def add(self, verts, faces, name, pset=None, container="storey",
            color=None, transparency=0.0):
        if not verts or not faces:
            return None
        m = self.m
        verts, faces = _fix_winding(verts, faces)
        rel_obj = self.site if container == "site" else self.storey
        pl = m.create_entity("IfcLocalPlacement",
                             PlacementRelTo=rel_obj.ObjectPlacement)
        face_ents = []
        for a, b, c in faces:
            pa, pb, pc = (self._point(*verts[a]),
                          self._point(*verts[b]),
                          self._point(*verts[c]))
            loop = m.create_entity("IfcPolyLoop", Polygon=[pa, pb, pc])
            bound = m.create_entity("IfcFaceOuterBound",
                                    Bound=loop, Orientation=True)
            face_ents.append(m.create_entity("IfcFace", Bounds=[bound]))
        shell = m.create_entity("IfcClosedShell", CfsFaces=face_ents)
        brep = m.create_entity("IfcFacetedBrep", Outer=shell)
        rep = m.create_entity("IfcShapeRepresentation",
                              RepresentationIdentifier="Body",
                              RepresentationType="BRep",
                              Items=[brep])
        if color is not None:
            st = self._style(color, transparency)
            m.create_entity("IfcStyledItem", Item=rep, Styles=[st],
                            Name=None)
        pds = m.create_entity("IfcProductDefinitionShape",
                              Representations=[rep])
        el = m.create_entity(
            "IfcBuildingElementProxy", GlobalId=self._guid(m),
            OwnerHistory=self.oh, Name=name,
            ObjectType="DisasterGuard", ObjectPlacement=pl,
            Representation=pds)
        self._rels.append(el)
        if color is not None:
            self._associate_material(el, color)
        if pset:
            self._add_pset(el, pset)
        self.count += 1
        return el


def prism(p1, p2, z1, z2, width, thick, rise):
    """沿 2D 线段 p1→p2（米）、两端地面高 z1/z2 的贴地薄体。

    width=横向宽，thick=自地面下沉，rise=高出地面。返回 verts/faces。
    """
    dx, dy = p2[0] - p1[0], p2[1] - p1[1]
    ln = math.hypot(dx, dy)
    if ln < 1e-9:
        return [], []
    ux, uy = -dy / ln * width / 2, dx / ln * width / 2
    base = [(p1[0] + ux, p1[1] + uy), (p1[0] - ux, p1[1] - uy),
            (p2[0] - ux, p2[1] - uy), (p2[0] + ux, p2[1] + uy)]
    zb = [z1 - thick, z1 - thick, z2 - thick, z2 - thick]
    zt = [z1 + rise, z1 + rise, z2 + rise, z2 + rise]
    verts = [(base[k][0], base[k][1], zb[k]) for k in range(4)] + \
            [(base[k][0], base[k][1], zt[k]) for k in range(4)]
    f = []
    for k in range(4):
        a, b = k, (k + 1) % 4
        f += quad_faces(a, b, 4 + b, 4 + a)          # 四个侧壁
    f += [(4, 6, 5), (4, 7, 6)]                      # 顶（两三角）
    f += [(0, 2, 1), (0, 3, 2)]                      # 底（反向）
    return verts, f


def merge(*vf_list):
    """合并多个 (verts, faces) 为单一网格。"""
    av, af = [], []
    for v, f in vf_list:
        off = len(av)
        av.extend(v)
        af.extend([(a + off, b + off, c + off) for a, b, c in f])
    return av, af


def add_building(bb, X, Y, W_, D_, H_, btype, bname, zb, sm):
    """真实化单体：基座/主体/腰线/窗阵/门/檐口/屋顶。"""
    public = btype == "public"
    wall = (0.82, 0.83, 0.86) if public else (0.88, 0.81, 0.70)
    plinth_h = max(0.004, H_ * 0.10)
    # 基座
    bb.add(*box(X, Y, W_ + 0.006, D_ + 0.006, plinth_h, zb)[:2],
           name=f"{bname} 基座", color=C_PLINTH,
           pset={"分区": "C", "构件": "石材基座"})
    # 主体
    bb.add(*box(X, Y, W_, D_, H_ - plinth_h, zb + plinth_h)[:2],
           name=bname, color=wall,
           pset={"分区": "C",
                 "用途": ("公共建筑" if public else "住宅"),
                 "尺寸": f"{W_ * 1000:.0f}x{D_ * 1000:.0f}x{H_ * 1000:.0f}mm"})
    # 楼层划分（沙盘 20mm 层高）
    nf = max(2, int(round((H_ - plinth_h) / 0.020)))
    fh = (H_ - plinth_h) / nf
    for i in range(1, nf):
        bb.add(*box(X, Y, W_ + 0.003, D_ + 0.003, 0.0015,
                    zb + plinth_h + i * fh - 0.0007)[:2],
               name=f"{bname} 腰线-{i}", color=C_BAND,
               pset={"分区": "C", "构件": f"第{i}层楼板带"})
    # 窗阵：前后沿 X 均布，左右沿 Y 均布；首层正中留门
    win_w, win_h = 0.008, min(0.010, fh * 0.55)
    def win_row(axis, fixed_y_or_x, sign, span, floors, tag):
        out = []
        nc = max(1, int(round(span / 0.016)))
        for i in range(floors):
            zc = zb + plinth_h + (i + 0.5) * fh
            for k in range(nc):
                if i == 0 and tag == "S" and abs(k - (nc - 1) / 2) < 0.6:
                    continue                     # 首层门位
                t = span * (k + 0.5) / nc - span / 2
                if axis == "X":
                    out.append(box(X + t, Y + sign * (D_ / 2 + 0.0012),
                                   win_w, 0.0025, win_h,
                                   zc - win_h / 2))
                else:
                    out.append(box(X + sign * (W_ / 2 + 0.0012), Y + t,
                                   0.0025, win_w, win_h,
                                   zc - win_h / 2))
        return out
    wins = (win_row("X", None, -1, W_, nf, "S") +
            win_row("X", None, 1, W_, nf, "N") +
            win_row("Y", None, -1, D_, nf, "W") +
            win_row("Y", None, 1, D_, nf, "E"))
    for gi, (v, f) in enumerate(wins):
        bb.add(v, f, f"{bname} 窗-{gi + 1}", color=C_GLASS,
               pset={"分区": "C", "构件": "外窗", "类型": "塑钢推拉窗"})
    # 入户门 + 雨棚（南侧）
    door_w, door_h = 0.014, min(0.018, fh * 0.9)
    bb.add(*box(X, Y - D_ / 2 - 0.0012, door_w, 0.003, door_h,
                zb + plinth_h)[:2],
           name=f"{bname} 入户门", color=C_DOOR,
           pset={"分区": "C", "构件": "入户门"})
    bb.add(*box(X, Y - D_ / 2 - 0.004, door_w + 0.006, 0.008, 0.0015,
                zb + plinth_h + door_h)[:2],
           name=f"{bname} 门雨棚", color=C_BAND,
           pset={"分区": "C", "构件": "入口雨棚"})
    zt = zb + H_
    if public:
        # 平屋顶：女儿墙四片 + 屋面 + 屋顶设备
        bb.add(*box(X, Y, W_ + 0.004, D_ + 0.004, 0.0015, zt)[:2],
               name=f"{bname} 屋面", color=C_ROOF_G,
               pset={"分区": "C", "构件": "平屋面"})
        pp = []
        for (px, py, pw, pd) in [(X, Y - D_ / 2 - 0.001, W_ + 0.004, 0.002),
                                 (X, Y + D_ / 2 + 0.001, W_ + 0.004, 0.002),
                                 (X - W_ / 2 - 0.001, Y, 0.002, D_),
                                 (X + W_ / 2 + 0.001, Y, 0.002, D_)]:
            pp.append(box(px, py, pw, pd, 0.006, zt))
        av, af = merge(*pp)
        bb.add(av, af, f"{bname} 女儿墙", color=wall,
               pset={"分区": "C", "构件": "女儿墙", "高": "6mm"})
        bb.add(*box(X + W_ * 0.22, Y + D_ * 0.2, 0.012, 0.010, 0.006,
                    zt + 0.0015)[:2],
               name=f"{bname} 屋顶设备", color=C_DEV_D,
               pset={"分区": "C", "构件": "屋顶风机"})
    else:
        # 坡屋顶：檐口板 + 四坡锥
        bb.add(*box(X, Y, W_ + 0.010, D_ + 0.010, 0.002, zt)[:2],
               name=f"{bname} 檐口", color=C_BAND,
               pset={"分区": "C", "构件": "檐口板"})
        rh = H_ * 0.45
        rr = max(W_, D_) * 0.75 + 0.004
        bb.add(*pyramid(X, Y, rr, rh, zt + 0.002)[:2],
               name=f"{bname} 屋顶", color=C_ROOF_R,
               pset={"分区": "C", "构件": "四角坡屋面", "瓦": "红色陶瓦"})
        bb.add(*cylinder(X, Y, 0.0025, 0.010, zt + 0.002 + rh)[:2],
               name=f"{bname} 烟囱", color=C_PLINTH,
               pset={"分区": "C", "构件": "烟囱"})


def add_tree_conifer(bb, x, y, h, sc, rng):
    """针叶树：树干 + 4 层锥冠。"""
    parts = [cylinder(x, y, 0.004 * sc, 0.022 * sc, h, seg=8)]
    for i in range(4):
        rr = 0.026 * sc * (1 - i * 0.17)
        parts.append(cylinder(x, y, rr, 0.034 * sc,
                              h + (0.014 + i * 0.017) * sc - 0.017 * sc,
                              seg=7, r_top=0.0005))
    v, f = merge(*parts)
    bb.add(v, f, "针叶树", color=C_LEAF_D,
           pset={"分区": "A", "类型": "仿真针叶",
                 "冠径": f"{52 * sc:.0f}mm"})


def add_tree_broadleaf(bb, x, y, h, sc, rng):
    """阔叶树：树干 + 双球冠。"""
    parts = [cylinder(x, y, 0.005 * sc, 0.026 * sc, h, seg=8)]
    parts.append(cylinder(x, y, 0.022 * sc, 0.026 * sc,
                          h + 0.020 * sc, seg=10))
    parts.append(cylinder(x + 0.010 * sc, y - 0.006 * sc, 0.015 * sc,
                          0.018 * sc, h + 0.032 * sc, seg=8))
    v, f = merge(*parts)
    bb.add(v, f, "阔叶树", color=C_LEAF_L,
           pset={"分区": "B", "类型": "仿真阔叶"})


def add_street_tree(bb, x, y, h, sc):
    """行道树：树池 + 支撑架 + 冠。"""
    parts = [box(x, y, 0.030, 0.030, 0.004, h - 0.002)]      # 树池边框
    parts.append(cylinder(x, y, 0.013, 0.002, h + 0.002))    # 种植土面
    parts.append(cylinder(x, y, 0.0035 * sc, 0.018 * sc, h + 0.002,
                          seg=6))                            # 主干
    parts.append(cylinder(x, y, 0.017 * sc, 0.026 * sc,
                          h + 0.012 * sc, seg=9))            # 圆冠
    for k in range(3):                                       # 三脚支撑
        a = 2 * math.pi * k / 3 + 0.5
        p1 = (x + 0.014 * math.cos(a), y + 0.014 * math.sin(a))
        parts.append(cylinder(p1[0], p1[1], 0.0012, 0.020,
                              h + 0.002, seg=4))
    v, f = merge(*parts)
    bb.add(v, f, "城镇行道树", color=C_LEAF_L,
           pset={"分区": "C", "类型": "行道树", "构件": "树池+支撑"})


def add_road(bb, x1, y1, x2, y2, rw_mm, rname, sm):
    """沥青路面 + 双侧路缘 + 中央虚线。"""
    pts = msb._make_road_pts(x1, y1, x2, y2)
    rv_, rf_ = msb.road_ribbon(
        pts, rw_mm / 1000.0,
        lambda a, b: sample_z(msb_z, a / W_MM, b / D_MM))
    top = [(p[0], p[1], p[2] / 1000.0) for p in rv_]
    bot = [(x, y, zz - 0.002) for x, y, zz in top]
    n = len(top)
    f = list(rf_)
    f += [(c + n, b + n, a + n) for a, b, c in rf_]
    for k in range(n // 2 - 1):
        l0, r0 = 2 * k, 2 * k + 1
        l1, r1 = 2 * k + 2, 2 * k + 3
        f += quad_faces(l0, l0 + n, l1 + n, l1)
        f += quad_faces(r0, r1, r1 + n, r0 + n)
    f += quad_faces(0, 1, 1 + n, 0 + n)
    f += quad_faces(n - 2, n - 1, n - 1 + n, n - 2 + n)
    bb.add(top + bot, f, rname, color=C_ASPHALT,
           pset={"分区": "C", "路宽mm": float(rw_mm), "材质": "沥青"})
    # 路缘石：沿点序两侧小段
    half = rw_mm / 2000.0 + 0.0012
    for side in (-1, 1):
        curb = []
        for k in range(n // 2 - 1):
            l0, r0 = 2 * k, 2 * k + 1
            p1, p2 = top[l0 if side < 0 else r0], \
                top[l1 if side < 0 else r1]
            dx, dy = p2[0] - p1[0], p2[1] - p1[1]
            ln = math.hypot(dx, dy)
            if ln < 1e-6:
                continue
            ux, uy = -dy / ln * side, dx / ln * side
            q1 = (p1[0] + ux * half, p1[1] + uy * half)
            q2 = (p2[0] + ux * half, p2[1] + uy * half)
            curb.append(prism(q1, q2, p1[2], p2[2], 0.0025, 0.002, 0.0018))
        cv = [v for v, _ in curb if v]
        if cv:
            vv, ff = merge(*curb)
            bb.add(vv, ff, f"{rname} 路缘({'左' if side < 0 else '右'})",
                   color=C_CURB, pset={"分区": "C", "构件": "路缘石",
                                       "高": "1.8mm"})
    # 中央虚线：每 2 段取 1 段放标线块
    for k in range(0, n // 2 - 1, 2):
        l0, r0 = 2 * k, 2 * k + 1
        p1 = ((top[l0][0] + top[r0][0]) / 2, (top[l0][1] + top[r0][1]) / 2)
        z1 = (top[l0][2] + top[r0][2]) / 2
        l1, r1 = 2 * k + 2, 2 * k + 3
        if l1 >= n or r1 >= n:
            break
        p2 = ((top[l1][0] + top[r1][0]) / 2, (top[l1][1] + top[r1][1]) / 2)
        z2 = (top[l1][2] + top[r1][2]) / 2
        v, f = prism(p1, p2, z1, z2, 0.0015, 0.0, 0.0023)
        if v:
            bb.add(v, f, f"{rname} 标线", color=C_MARK,
                   pset={"分区": "C", "构件": "中央虚线"})


def add_devices_real(bb, sm):
    """真实化设备模型。"""
    for code, name, dtype, zone, dx, dy, dz, desc in msb.DEVICES:
        x_m, y_m = dx / 1000, dy / 1000
        zb = sm(x_m, y_m)
        props = {"分区": zone, "编号": code, "说明": desc}
        if dtype == "led_matrix":
            parts = [cylinder(x_m, y_m, 0.004, 0.085, zb, seg=10)]
            parts.append(box(x_m, y_m, 0.166, 0.026, 0.086, zb + 0.085))
            av, af = merge(*parts)
            bb.add(av, af, name, color=C_DEV_D, pset=props)
            bb.add(*box(x_m, y_m, 0.156, 0.028, 0.076, zb + 0.090)[:2],
                   name=f"{name} 屏面", color=C_LED, pset=props)
        elif dtype == "rain_gauge":
            parts = [cylinder(x_m, y_m, 0.060, 0.018, zb, seg=12),
                     cylinder(x_m, y_m, 0.030, 0.070, zb + 0.018, seg=12),
                     cylinder(x_m, y_m, 0.026, 0.010, zb + 0.088,
                              seg=12, r_top=0.012)]   # 漏斗承水器
            av, af = merge(*parts)
            props["型号"] = "YFS201"
            bb.add(av, af, name, color=C_DEV, pset=props)
        elif dtype == "camera":
            parts = [cylinder(x_m, y_m, 0.004, 0.060, zb, seg=8)]
            parts.append(box(x_m, y_m, 0.030, 0.022, 0.018, zb + 0.060))
            parts.append(cylinder(x_m + 0.017, y_m, 0.007, 0.012,
                                  zb + 0.069, seg=10))     # 枪机镜头
            av, af = merge(*parts)
            bb.add(av, af, name, color=C_DEV, pset=props)
            bb.add(*cylinder(x_m + 0.024, y_m, 0.0055, 0.011,
                             zb + 0.0695, seg=10)[:2],
                   name=f"{name} 镜头", color=C_DEV_D, pset=props)
        elif dtype == "alarm":
            parts = [cylinder(x_m, y_m, 0.004, 0.055, zb, seg=8)]
            parts.append(cylinder(x_m, y_m, 0.012, 0.020, zb + 0.055,
                                  seg=10, r_top=0.020))    # 喇叭口
            av, af = merge(*parts)
            bb.add(av, af, name, color=C_DEV, pset=props)
        elif dtype == "pump":
            parts = [cylinder(x_m, y_m, 0.030, 0.040, zb - 0.020, seg=12),
                     cylinder(x_m + 0.020, y_m, 0.006, 0.030,
                              zb + 0.020, seg=8)]          # 出水管
            av, af = merge(*parts)
            props["型号"] = "5V直流"
            bb.add(av, af, name, color=C_DEV_D, pset=props)
        elif dtype == "servo":
            parts = [box(x_m, y_m, 0.040, 0.020, 0.040, zb),
                     cylinder(x_m + 0.022, y_m, 0.003, 0.008,
                              zb + 0.030, seg=6)]          # 舵机输出轴
            av, af = merge(*parts)
            props["型号"] = "SG90"
            bb.add(av, af, name, color=C_DEV_D, pset=props)
        elif dtype == "led":
            parts = [cylinder(x_m, y_m, 0.0015, 0.012, zb, seg=6)]
            parts.append(cylinder(x_m, y_m, 0.0035, 0.004,
                                  zb + 0.012, seg=8))
            av, af = merge(*parts)
            bb.add(av, af, name, color=(0.9, 0.2, 0.15), pset=props)
        else:
            bb.add(*box(x_m, y_m, 0.030, 0.020, 0.020, zb)[:2],
                   name=name, color=C_DEV, pset=props)


msb_z = None  # 供道路采样（main 中赋值）


def build(out_dir, mgx=90, mgy=60, grid_mm=4.0, water_mm=12.0, smooth=2):
    global msb_z
    os.makedirs(out_dir, exist_ok=True)
    nx = max(60, int(round(W_MM / grid_mm)))
    ny = max(40, int(round(D_MM / grid_mm)))
    z, xs, ys = msb.generate_terrain(nx, ny)
    if smooth > 0:
        z = foam.smooth(z, smooth)
    msb_z = z
    z_model = foam.resample(z, mgx, mgy)
    xs_m = np.linspace(0, W_MM / 1000.0, mgx)
    ys_m = np.linspace(0, D_MM / 1000.0, mgy)
    z_m = z_model / 1000.0

    bb = RealBuilder("DisasterGuard 混合沙盘 C方案（真实化）")

    def sm(x_m, y_m):
        return sample_z(z, x_m * 1000.0 / W_MM,
                        y_m * 1000.0 / D_MM) / 1000.0

    # 1 地形（更密网格）
    tv, tf = build_terrain(z_m, xs_m, ys_m)
    bb.add(tv, tf, "地形 三区一体化", color=C_GROUND,
           pset={"分区": "全区", "材质": "XPS泡沫+石膏+草粉",
                 "网格": f"{nx}x{ny}"})

    # 2 水体（半透明）+ 亚克力水槽（透明）
    bb.add(*box(0.85, 0.5, 0.50, 0.84, 0.004,
                water_mm / 1000.0 - 0.004)[:2],
          name="水面", color=C_WATER, transparency=0.35,
          pset={"分区": "B", "常态水位mm": water_mm,
                "洪水位mm": 28.0, "控制": "5V水泵PWM"})
    tank = []
    for (ox, oy, w, d) in [(0, -0.42, 0.50, 0.005), (0, 0.42, 0.50, 0.005),
                           (-0.25, 0, 0.005, 0.84), (0.25, 0, 0.005, 0.84)]:
        tank.append(box(0.85 + ox, 0.5 + oy, w, d, 0.10, -0.005))
    v, f = merge(*tank)
    bb.add(v, f, "亚克力水槽 (B区)", color=C_ACRYL, transparency=0.6,
           pset={"分区": "B", "材质": "亚克力5mm"})
    # 池底
    bb.add(*box(0.85, 0.5, 0.50, 0.84, 0.003, -0.005)[:2],
           name="水槽池底", color=(0.35, 0.38, 0.40),
           pset={"分区": "B", "材质": "磨砂亚克力"})

    # 3 滑坡体 + 岩块 + 滑轨
    lv, lf, lmeta = msb.landslide_geometry(z, xs, ys)
    lv_m = [(p[0] / 1000, p[1] / 1000, p[2] / 1000) for p in lv]
    bb.add(lv_m, lf, "可动滑坡体", color=C_CLAY,
           pset={"分区": "A", "尺寸": "150x200x40mm",
                 "驱动": "SG90+滑轨",
                 "监测": "AS5311/裂缝计/MPU6050"})
    rng = random.Random(20260921)
    for k in range(7):
        s = 0.020 + rng.random() * 0.030
        rx = lmeta[0] / 1000 + (rng.random() - 0.5) * 0.14
        ry = lmeta[1] / 1000 + (rng.random() - 0.5) * 0.18
        v, f = rock(rx, ry, s / 2, lmeta[2] / 1000 + 0.04, rng)
        bb.add(v, f, f"崩塌岩块-{k + 1}", color=C_ROCK, pset={"分区": "A"})
    bb.add(*box(0.30, 0.55, 0.16, 0.003, 0.001,
                lmeta[2] / 1000)[:2],
           name="滑坡体滑轨", color=C_ALU,
           pset={"分区": "A", "传感器": "AS5311"})

    # 4 建筑（真实化）
    for cx, cy, bw, bd, bh, btype, bname in msb.BUILDINGS:
        zb = sample_z(z, cx / W_MM, cy / D_MM) / 1000.0
        add_building(bb, cx / 1000, cy / 1000, bw / 1000, bd / 1000,
                     bh / 1000, btype, bname, zb, sm)

    # 5 道路（真实化）
    for x1, y1, x2, y2, rw, rname in msb.ROADS:
        add_road(bb, x1, y1, x2, y2, rw, rname, sm)

    # 6 树（真实化）
    r2 = random.Random(777)
    for _ in range(30):
        x = r2.random() * 0.55
        zz = r2.random() * 1.0
        h = sm(x, zz)
        if not (0.02 < h < 0.18):
            continue
        add_tree_conifer(bb, x, zz, h, 0.7 + r2.random() * 0.5, r2)
    for _ in range(8):
        x = 0.6 + r2.random() * 0.45
        zz = r2.random() * 1.0
        h = sm(x, zz)
        if not (0.005 < h < 0.03):
            continue
        add_tree_broadleaf(bb, x, zz, h, 0.8 + r2.random() * 0.4, r2)
    for _ in range(6):
        x = 1.15 + r2.random() * 0.30
        zz = r2.random() * 1.0
        if zz < 0.05 or zz > 0.95:
            continue
        add_street_tree(bb, x, zz, sm(x, zz), 0.6 + r2.random() * 0.3)

    # 7 散石
    r3 = random.Random(4242)
    nk = 0
    for _ in range(20):
        x = r3.random() * 0.55
        zz = r3.random() * 1.0
        h = sm(x, zz)
        if not (0.04 < h < 0.16):
            continue
        s = 0.008 + r3.random() * 0.02
        v, f = rock(x, zz, s, h, r3)
        bb.add(v, f, f"散石-{nk + 1}", color=C_ROCK, pset={"分区": "A"})
        nk += 1
    for _ in range(8):
        x = 0.55 + r3.random() * 0.5
        zz = r3.random() * 1.0
        h = sm(x, zz)
        if not (0.01 < h < 0.025):
            continue
        s = 0.006 + r3.random() * 0.012
        v, f = rock(x, zz, s, h, r3)
        bb.add(v, f, f"卵石-{nk + 1}", color=(0.52, 0.50, 0.47),
               pset={"分区": "B"})
        nk += 1

    # 8 底座 + 围框
    bb.add(*box(0.75, 0.5, 1.54, 1.04, 0.02, -0.025)[:2],
           name="沙盘底板", color=C_BASE, pset={"材质": "高密度泡沫底板"})
    frame = []
    for (fx, fy, fw, fd) in [(0.75, 1.02, 1.54, 0.02),
                             (0.75, -0.02, 1.54, 0.02),
                             (-0.02, 0.5, 0.02, 1.04),
                             (1.52, 0.5, 0.02, 1.04)]:
        frame.append(box(fx, fy, fw, fd, 0.06, -0.025))
    v, f = merge(*frame)
    bb.add(v, f, "铝合金围框", color=C_ALU,
           pset={"材质": "铝合金型材", "高": "60mm"})

    # 9 分区标记柱
    for zc, nm, col, mx, mz in [("A", "A区标记柱", (0.55, 0.42, 0.28),
                                 0.30, 0.05),
                                ("B", "B区标记柱", (0.08, 0.40, 0.75),
                                 0.85, 0.05),
                                ("C", "C区标记柱", (0.18, 0.49, 0.20),
                                 1.30, 0.05)]:
        parts = [cylinder(mx, mz, 0.008, 0.055, sm(mx, mz), seg=12)]
        parts.append(cylinder(mx, mz, 0.013, 0.006,
                              sm(mx, mz) + 0.055, seg=12))
        v, f = merge(*parts)
        bb.add(v, f, nm, color=col, pset={"分区": zc, "构件": "分区标识"})

    # 10 设备（真实化）
    add_devices_real(bb, sm)

    path = os.path.join(out_dir, "沙盘_彩色真实模型.ifc")
    n = bb.write(path)
    size_mb = os.path.getsize(path) / 1e6
    print(f"[OK] 真实化 IFC2x3 -> {path}（{n} 构件 / {size_mb:.1f} MB）")

    chk = ifcopenshell.open(path)
    proxies = chk.by_type("IfcBuildingElementProxy")
    breps = chk.by_type("IfcFacetedBrep")
    shells = chk.by_type("IfcClosedShell")
    faces = sum(len(s.CfsFaces) for s in shells)
    styles = chk.by_type("IfcSurfaceStyle")
    print(f"[自检] 构件={len(proxies)} 实体={len(breps)} 面={faces} "
          f"材质={len(styles)}")
    assert len(proxies) == n == len(breps)
    return path


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(
        HERE, "..", "examples", "revit_project"))
    a = ap.parse_args()
    build(os.path.abspath(a.out))
