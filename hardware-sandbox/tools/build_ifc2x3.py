#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
混合沙盘 → IFC2x3（BRep 实心体）导出
====================================================================
Revit 2024 的「打开 IFC」对 IFC4 仅部分支持（三角网常丢失）。
本脚本用 IfcOpenShell 输出 IFC2x3 + IfcFacetedBrep 闭合实体，
这是 Revit 原生导入器完全支持的写法；构件统一为
IfcBuildingElementProxy（Revit 侧归入「常规模型」），并挂属性集。

用法：
  python tools/build_ifc2x3.py --out examples/revit_project
输出：
  examples/revit_project/model_ifc2x3.ifc
"""

from __future__ import annotations

import argparse
import os
import random
import sys

import numpy as np
import ifcopenshell

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from build_revit_model import build_terrain, sample_z, quad_faces  # noqa: E402
import build_mixed_sandbox as msb                          # noqa: E402
import dem_to_foam as foam                                 # noqa: E402
from build_revit_project import box, pyramid, cylinder, rock  # noqa: E402

W_MM, D_MM = msb.WIDTH_MM, msb.DEPTH_MM


def _fix_winding(verts, faces):
    """统一闭合网格的面朝向（相邻面反向 → 整体外法向）。

    IfcClosedShell 要求所有面法向一致朝外，否则 Revit 视为无效实体。
    """
    n = len(faces)
    if n == 0:
        return verts, faces
    fixed = [tuple(f) for f in faces]

    def dedges(fi):
        a, b, c = fixed[fi]
        return ((a, b), (b, c), (c, a))

    adj = {}
    for fi in range(n):
        a, b, c = fixed[fi]
        for e in ((a, b), (b, c), (c, a)):
            adj.setdefault(e, []).append(fi)
            adj.setdefault((e[1], e[0]), []).append(fi)

    visited = set()
    comps = []
    for start in range(n):
        if start in visited:
            continue
        visited.add(start)
        comp = [start]
        stack = [start]
        while stack:
            fi = stack.pop()
            for e in dedges(fi):
                for nb in adj.get(e, []):
                    if nb == fi or nb in visited:
                        continue
                    if e in dedges(nb):          # 同向共享边 → 需翻转邻面
                        fixed[nb] = tuple(reversed(fixed[nb]))
                    visited.add(nb)
                    comp.append(nb)
                    stack.append(nb)
        comps.append(comp)

    for comp in comps:
        vol = 0.0
        for fi in comp:
            a, b, c = fixed[fi]
            va, vb, vc = verts[a], verts[b], verts[c]
            vol += (va[0] * (vb[1] * vc[2] - vb[2] * vc[1])
                    + va[1] * (vb[2] * vc[0] - vb[0] * vc[2])
                    + va[2] * (vb[0] * vc[1] - vb[1] * vc[0]))
        if vol < 0:
            for fi in comp:
                fixed[fi] = tuple(reversed(fixed[fi]))
    return verts, fixed


class BrepBuilder:
    def __init__(self, name):
        self.m = ifcopenshell.file(schema="IFC2X3")
        m = self.m
        org = m.create_entity("IfcOrganization", Name="DisasterGuard")
        app = m.create_entity("IfcApplication", ApplicationDeveloper=org,
                              Version="1.0",
                              ApplicationFullName="DisasterGuard Sandbox",
                              ApplicationIdentifier="DGSBX")
        person = m.create_entity("IfcPerson", FamilyName="sandbox")
        pap = m.create_entity("IfcPersonAndOrganization",
                              ThePerson=person, TheOrganization=org)
        oh = m.create_entity("IfcOwnerHistory", OwningUser=pap,
                             OwningApplication=app,
                             ChangeAction="NOCHANGE", CreationDate=0)
        self.oh = oh
        ctx = m.create_entity("IfcGeometricRepresentationContext",
                              ContextType="Model",
                              CoordinateSpaceDimension=3,
                              Precision=1e-5,
                              WorldCoordinateSystem=m.create_entity(
                                  "IfcAxis2Placement3D"))
        unit = m.create_entity("IfcSIUnit", UnitType="LENGTHUNIT",
                               Name="METRE")
        ua = m.create_entity("IfcUnitAssignment", Units=[unit])
        self.project = m.create_entity(
            "IfcProject", GlobalId=self._guid(m), OwnerHistory=oh,
            Name=name, RepresentationContexts=[ctx], UnitsInContext=ua)
        self.ctx = ctx

        site_pl = m.create_entity("IfcLocalPlacement")
        self.site = m.create_entity(
            "IfcSite", GlobalId=self._guid(m), OwnerHistory=oh,
            Name="DisasterGuard Sandbox Site", ObjectPlacement=site_pl,
            CompositionType="ELEMENT")
        m.create_entity("IfcRelAggregates", GlobalId=self._guid(m),
                        OwnerHistory=oh, RelatingObject=self.project,
                        RelatedObjects=[self.site])

        bld_pl = m.create_entity("IfcLocalPlacement",
                                 PlacementRelTo=self.site.ObjectPlacement)
        self.building = m.create_entity(
            "IfcBuilding", GlobalId=self._guid(m), OwnerHistory=oh,
            Name="Sandbox", ObjectPlacement=bld_pl, CompositionType="ELEMENT")
        m.create_entity("IfcRelAggregates", GlobalId=self._guid(m),
                        OwnerHistory=oh, RelatingObject=self.site,
                        RelatedObjects=[self.building])

        st_pl = m.create_entity("IfcLocalPlacement",
                                PlacementRelTo=self.building.ObjectPlacement)
        self.storey = m.create_entity(
            "IfcBuildingStorey", GlobalId=self._guid(m), OwnerHistory=oh,
            Name="沙盘 Level 0", ObjectPlacement=st_pl,
            CompositionType="ELEMENT", Elevation=0.0)
        m.create_entity("IfcRelAggregates", GlobalId=self._guid(m),
                        OwnerHistory=oh, RelatingObject=self.building,
                        RelatedObjects=[self.storey])

        self._pmap = {}
        self._rel = None
        self._rels = []
        self.count = 0

    @staticmethod
    def _guid(m):
        return ifcopenshell.guid.new()

    def _point(self, x, y, z):
        key = (round(float(x), 4), round(float(y), 4), round(float(z), 4))
        p = self._pmap.get(key)
        if p is None:
            p = self.m.create_entity("IfcCartesianPoint",
                                     Coordinates=list(key))
            self._pmap[key] = p
        return p

    def add(self, verts, faces, name, pset=None, container="storey"):
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
        pds = m.create_entity("IfcProductDefinitionShape",
                              Representations=[rep])
        el = m.create_entity(
            "IfcBuildingElementProxy", GlobalId=self._guid(m),
            OwnerHistory=self.oh, Name=name,
            ObjectType="DisasterGuard", ObjectPlacement=pl,
            Representation=pds)
        self._rels.append(el)
        if pset:
            self._add_pset(el, pset)
        self.count += 1
        return el

    def _add_pset(self, product, props):
        """手工构造 IfcPropertySet（绕开 ifcopenshell.api 的 shapely 依赖）。"""
        m = self.m
        vals = []
        for k, v in props.items():
            if isinstance(v, (int, float)):
                sv = m.create_entity("IfcReal", float(v))
            else:
                sv = m.create_entity("IfcLabel", str(v))
            vals.append(m.create_entity(
                "IfcPropertySingleValue", str(k), None, sv, None))
        ps = m.create_entity("IfcPropertySet", self._guid(m), self.oh,
                             "Pset_DisasterGuard", None, vals)
        m.create_entity("IfcRelDefinesByProperties", self._guid(m), self.oh,
                        None, None, [product], ps)

    def write(self, path):
        if self._rels:
            self.m.create_entity("IfcRelContainedInSpatialStructure",
                                 GlobalId=self._guid(self.m),
                                 OwnerHistory=self.oh,
                                 RelatingStructure=self.storey,
                                 RelatedElements=list(self._rels))
            self._rels = []
        self.m.write(path)
        return self.count


# ------------------------------------------------------------ 场景重建


def build(out_dir, mgx=75, mgy=50, grid_mm=5.0, water_mm=12.0, smooth=1):
    os.makedirs(out_dir, exist_ok=True)
    nx = max(60, int(round(W_MM / grid_mm)))
    ny = max(40, int(round(D_MM / grid_mm)))
    z, xs, ys = msb.generate_terrain(nx, ny)
    if smooth > 0:
        z = foam.smooth(z, smooth)
    z_model = foam.resample(z, mgx, mgy)
    xs_m = np.linspace(0, W_MM / 1000.0, mgx)
    ys_m = np.linspace(0, D_MM / 1000.0, mgy)
    z_m = z_model / 1000.0

    bb = BrepBuilder("DisasterGuard 混合沙盘 C方案")

    def sm(x_m, y_m):
        return sample_z(z, x_m * 1000.0 / W_MM, y_m * 1000.0 / D_MM) / 1000.0

    # 1 地形
    tv, tf = build_terrain(z_m, xs_m, ys_m)
    bb.add(tv, tf, "地形 三区一体化",
           {"分区": "全区", "材质": "XPS泡沫+石膏+草粉"})
    print(f"[OK] 地形 {len(tv)}点/{len(tf)}面")

    # 2 水面
    bb.add(*box(0.85, 0.5, 0.50, 0.84, 0.004, water_mm / 1000.0)[:2],
          name="水面", pset={"分区": "B", "常态水位mm": water_mm,
                             "洪水位mm": 28.0, "控制": "5V水泵PWM"})

    # 3 水槽
    allv, allf = [], []
    def addp(v, f):
        off = len(allv)
        allv.extend(v)
        allf.extend([(a + off, b + off, c + off) for a, b, c in f])
    for (ox, oy, w, d) in [(0, -0.42, 0.50, 0.005), (0, 0.42, 0.50, 0.005),
                           (-0.25, 0, 0.005, 0.84), (0.25, 0, 0.005, 0.84)]:
        v, f = box(0.85 + ox, 0.5 + oy, w, d, 0.10, -0.005)
        addp(v, f)
    bb.add(allv, allf, "亚克力水槽 (B区)",
           {"分区": "B", "材质": "亚克力5mm"})

    # 4 滑坡体 + 岩块 + 滑轨
    lv, lf, lmeta = msb.landslide_geometry(z, xs, ys)
    lv_m = [(p[0] / 1000, p[1] / 1000, p[2] / 1000) for p in lv]
    bb.add(lv_m, lf, "可动滑坡体",
           {"分区": "A", "尺寸": "150x200x40mm", "驱动": "SG90+滑轨",
            "监测": "AS5311/裂缝计/MPU6050"})
    rng = random.Random(20260921)
    for k in range(5):
        s = 0.020 + rng.random() * 0.030
        rx = lmeta[0] / 1000 + (rng.random() - 0.5) * 0.14
        ry = lmeta[1] / 1000 + (rng.random() - 0.5) * 0.18
        v, f = rock(rx, ry, s / 2, lmeta[2] / 1000 + 0.04, rng)
        bb.add(v, f, f"崩塌岩块-{k + 1}", {"分区": "A"})
    bb.add(*box(0.30, 0.55, 0.16, 0.003, 0.001, lmeta[2] / 1000)[:2],
          name="滑坡体滑轨", pset={"分区": "A", "传感器": "AS5311"})

    # 5 建筑 + 屋顶
    for cx, cy, bw, bd, bh, btype, bname in msb.BUILDINGS:
        zb = sample_z(z, cx / W_MM, cy / D_MM) / 1000.0
        bb.add(*box(cx / 1000, cy / 1000, bw / 1000, bd / 1000,
                    bh / 1000, zb)[:2],
               name=bname,
               pset={"分区": "C",
                     "用途": ("公共建筑" if btype == "public" else "住宅"),
                     "尺寸": f"{bw}x{bd}x{bh}mm"})
        bb.add(*pyramid(cx / 1000, cy / 1000, max(bw, bd) * 0.75 / 1000,
                        bh * 0.45 / 1000, zb + bh / 1000)[:2],
               name=f"{bname} 屋顶", pset={"分区": "C", "类型": "四角锥坡屋顶"})

    # 6 道路（2mm 薄实体）
    for x1, y1, x2, y2, rw, rname in msb.ROADS:
        pts = msb._make_road_pts(x1, y1, x2, y2)
        rv_, rf_ = msb.road_ribbon(pts, rw / 1000.0,
                                   lambda a, b: sample_z(z, a / W_MM, b / D_MM))
        top = [(p[0] / 1000, p[1] / 1000, p[2] / 1000) for p in rv_]
        bot = [(x, y, zz - 0.002) for x, y, zz in top]
        v = top + bot
        n = len(top)                      # 顶/底各 n 个点（成对排列）
        f = list(rf_)                                              # 顶面
        f += [(c + n, b + n, a + n) for a, b, c in rf_]            # 底面
        for k in range(n // 2 - 1):
            l0, r0 = 2 * k, 2 * k + 1
            l1, r1 = 2 * k + 2, 2 * k + 3
            f += quad_faces(l0, l0 + n, l1 + n, l1)                # 左壁
            f += quad_faces(r0, r1, r1 + n, r0 + n)                # 右壁
        f += quad_faces(0, 1, 1 + n, 0 + n)                        # 起点封头
        f += quad_faces(n - 2, n - 1, n - 1 + n, n - 2 + n)        # 终点封头
        bb.add(v, f, rname, {"分区": "C", "路宽mm": float(rw),
                             "材质": "沥青"})

    # 7 树
    r2 = random.Random(777)
    for _ in range(30):
        x = r2.random() * 0.55
        zz = r2.random() * 1.0
        h = sm(x, zz)
        if not (0.02 < h < 0.18):
            continue
        sc = 0.7 + r2.random() * 0.5
        v, f = cylinder(x, zz, 0.004 * sc, 0.02 * sc, h)
        allv, allf = list(v), list(f)
        for i in range(3):
            rr = 0.025 * sc * (1 - i * 0.2)
            v, f = cylinder(x, zz, rr, 0.035 * sc,
                            h + (0.015 + i * 0.018) * sc - 0.0175 * sc,
                            seg=6, r_top=0.0005)
            off = len(allv)
            allv.extend(v)
            allf.extend([(a + off, b + off, c + off) for a, b, c in f])
        bb.add(allv, allf, "针叶树", {"分区": "A", "类型": "仿真针叶"})
    for _ in range(8):
        x = 0.6 + r2.random() * 0.45
        zz = r2.random() * 1.0
        h = sm(x, zz)
        if not (0.005 < h < 0.03):
            continue
        sc = 0.8 + r2.random() * 0.4
        v, f = cylinder(x, zz, 0.005 * sc, 0.025 * sc, h)
        allv, allf = list(v), list(f)
        v, f = cylinder(x, zz, 0.022 * sc, 0.030 * sc, h + 0.020 * sc, seg=8)
        off = len(allv)
        allv.extend(v)
        allf.extend([(a + off, b + off, c + off) for a, b, c in f])
        bb.add(allv, allf, "阔叶树", {"分区": "B", "类型": "仿真阔叶"})
    for _ in range(6):
        x = 1.15 + r2.random() * 0.30
        zz = r2.random() * 1.0
        if zz < 0.05 or zz > 0.95:
            continue
        h = sm(x, zz)
        sc = 0.6 + r2.random() * 0.3
        v, f = cylinder(x, zz, 0.0035 * sc, 0.015 * sc, h)
        allv, allf = list(v), list(f)
        v, f = cylinder(x, zz, 0.016 * sc, 0.028 * sc,
                        h + 0.007 * sc, seg=8)
        off = len(allv)
        allv.extend(v)
        allf.extend([(a + off, b + off, c + off) for a, b, c in f])
        bb.add(allv, allf, "城镇行道树", {"分区": "C", "类型": "行道树"})

    # 8 散石
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
        bb.add(v, f, f"散石-{nk + 1}", {"分区": "A"})
        nk += 1
    for _ in range(8):
        x = 0.55 + r3.random() * 0.5
        zz = r3.random() * 1.0
        h = sm(x, zz)
        if not (0.01 < h < 0.025):
            continue
        s = 0.006 + r3.random() * 0.012
        v, f = rock(x, zz, s, h, r3)
        bb.add(v, f, f"卵石-{nk + 1}", {"分区": "B"})
        nk += 1

    # 9 底座 + 围框
    bb.add(*box(0.75, 0.5, 1.54, 1.04, 0.02, -0.025)[:2],
          name="沙盘底板", pset={"材质": "高密度泡沫底板"})
    allv, allf = [], []
    for (fx, fy, fw, fd) in [(0.75, 1.02, 1.54, 0.02),
                             (0.75, -0.02, 1.54, 0.02),
                             (-0.02, 0.5, 0.02, 1.04),
                             (1.52, 0.5, 0.02, 1.04)]:
        v, f = box(fx, fy, fw, fd, 0.06, -0.025)
        off = len(allv)
        allv.extend(v)
        allf.extend([(a + off, b + off, c + off) for a, b, c in f])
    bb.add(allv, allf, "铝合金围框", {"材质": "铝合金型材", "高": "60mm"})

    # 10 分区标记柱
    for zc, nm, col, mx, mz in [("A", "A区标记柱", "8B6B47", 0.30, 0.05),
                                ("B", "B区标记柱", "1565C0", 0.85, 0.05),
                                ("C", "C区标记柱", "2E7D32", 1.30, 0.05)]:
        bb.add(*cylinder(mx, mz, 0.008, 0.06, sm(mx, mz))[:2],
               name=nm, pset={"分区": zc, "颜色": col})

    # 11 设备
    for code, name, dtype, zone, dx, dy, dz, desc in msb.DEVICES:
        x_m, y_m = dx / 1000, dy / 1000
        zb = sm(x_m, y_m)
        props = {"分区": zone, "编号": code, "说明": desc}
        if dtype == "led_matrix":
            v, f = cylinder(x_m, y_m, 0.004, 0.08, zb)
            allv, allf = list(v), list(f)
            v, f = box(x_m, y_m, 0.16, 0.02, 0.08, zb + 0.08)
            off = len(allv)
            allv.extend(v)
            allf.extend([(a + off, b + off, c + off) for a, b, c in f])
            props["规格"] = "32x16 P5"
            bb.add(allv, allf, name, props)
        elif dtype == "rain_gauge":
            v, f = cylinder(x_m, y_m, 0.08, 0.10, zb, seg=10)
            props["型号"] = "YFS201"
            bb.add(v, f, name, props)
        elif dtype in ("camera", "alarm"):
            v, f = cylinder(x_m, y_m, 0.012, 0.05, zb)
            allv, allf = list(v), list(f)
            v, f = box(x_m, y_m, 0.04, 0.03, 0.03, zb + 0.05)
            off = len(allv)
            allv.extend(v)
            allf.extend([(a + off, b + off, c + off) for a, b, c in f])
            bb.add(allv, allf, name, props)
        elif dtype == "pump":
            v, f = cylinder(x_m, y_m, 0.03, 0.04, zb - 0.02, seg=10)
            props["型号"] = "5V直流"
            bb.add(v, f, name, props)
        elif dtype == "servo":
            v, f = box(x_m, y_m, 0.04, 0.02, 0.04, zb)
            props["型号"] = "SG90"
            bb.add(v, f, name, props)
        else:
            v, f = box(x_m, y_m, 0.03, 0.02, 0.02, zb)
            bb.add(v, f, name, props)

    path = os.path.join(out_dir, "model_ifc2x3.ifc")
    n = bb.write(path)
    size_mb = os.path.getsize(path) / 1e6
    print(f"[OK] IFC2x3 -> {path}（{n} 构件 / {size_mb:.1f} MB）")

    # 自检：重新打开并统计
    chk = ifcopenshell.open(path)
    proxies = chk.by_type("IfcBuildingElementProxy")
    breps = chk.by_type("IfcFacetedBrep")
    shells = chk.by_type("IfcClosedShell")
    faces = sum(len(s.CfsFaces) for s in shells)
    print(f"[自检] schema={chk.schema} 构件={len(proxies)} "
          f"实体={len(breps)} 面={faces}")
    assert len(proxies) == n == len(breps)
    return path


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(
        HERE, "..", "examples", "revit_project"))
    ap.add_argument("--model-grid-x", type=int, default=75)
    ap.add_argument("--model-grid-y", type=int, default=50)
    a = ap.parse_args()
    build(os.path.abspath(a.out), a.model_grid_x, a.model_grid_y)
