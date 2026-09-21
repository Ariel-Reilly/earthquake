#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
真实 DEM + 真实 OSM 地物 -> 全要素 Revit 模型（重庆渝中半岛山地沙盘）

要素（地形、水体、道路、城市建筑均来自真实数据）
----------------------------------------------------------------
  山体  TERRAIN    : DEM 三角网（真实高程）
  水体  WATER      : OSM 水体轮廓 ∩ 河床低于水位 -> 水面网格（嘉陵江）
  道路  ROAD-MTN   : 山路 / 梯道（footway / path / track / steps）
        ROAD-URBAN : 城市道路（primary … service）
  建筑  BLD-CITY   : OSM building footprint 挤出体块（真实轮廓 + 真实层数）
        BLD-PUBLIC : 医院 / 学校 / 车站等公共建筑
        BLD-RURAL  : 坡地散点民居（坡屋顶）。纯城区 OSM 无此类数据，
                     默认程序化补充，仅用于演示城乡差异，非真实建筑

比例约定（关键）
----------------------------------------------------------------
  * 垂直夸张 VE 只作用于**地形起伏**，不作用于建筑与道路的结构尺寸；
    建筑高度与路宽按**水平比尺**缩放，避免沙盘上出现 30 m 高的楼房。
  * terrain 模式：水平比尺 1:1，VE=1（真实地形，可与 GIS 对齐）
  * sandbox 模式：水平比尺 1:2421（沙盘 1.2×0.8 m），VE=2.5（视觉起伏）

关于 .rvt
----------------------------------------------------------------
.rvt 是 Autodesk 专有二进制格式，任何脚本都无法直接生成。本工具输出
IFC4 —— Revit 原生打开的交换格式，「打开 model.ifc」后「另存为」即得 .rvt。

输出
----------------------------------------------------------------
  model.ifc           ★ Revit 主交付物
  model.obj           按要素分组，供 Blender / Lumion / Twinmotion / 3ds Max
  terrain_points.csv  Revit「体量和场地」→ 地形表面 → 通过导入创建 → 指定点文件
  features.csv        要素清单（分类 / 名称 / 坐标 / 高度 / 面积）

用法
----------------------------------------------------------------
  python build_revit_model.py --fetch --out ../examples/revit_model
  python build_revit_model.py --fetch --target sandbox --out ../examples/revit_sandbox
  python build_revit_model.py --dem chongqing.asc --out ./out
  python build_revit_model.py --synthetic --out ./_selftest      # 离线自检
"""

from __future__ import annotations

import argparse
import json
import math
import os
import random
import sys
import time
import urllib.parse
import urllib.request

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

try:
    import dem_to_foam as foam
except ImportError:
    sys.exit("[ERR] 需要同目录下的 dem_to_foam.py")

DEFAULT_BBOX = (106.5150, 29.5425, 106.5450, 29.5600)
OVERPASS = ["https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter"]
UA = "DisasterGuard/1.0 (hardware sandbox)"

# ---------------------------------------------------------------- 参数表

ROAD_WIDTH = {                       # 真实路宽（米，含人行道）
    "motorway": 32.0, "motorway_link": 16.0, "trunk": 28.0, "trunk_link": 14.0,
    "primary": 24.0, "primary_link": 12.0, "secondary": 18.0, "secondary_link": 10.0,
    "tertiary": 13.0, "residential": 8.0, "unclassified": 7.0,
    "living_street": 6.0, "service": 5.0, "pedestrian": 5.0,
    "footway": 2.5, "path": 2.0, "track": 3.0, "steps": 2.0,
}
MOUNTAIN_ROADS = {"footway", "path", "track", "steps"}      # 山路 / 梯道
ROAD_LIFT = 0.12                                            # 路面结构厚度（米）
FOUNDATION = 1.5                                            # 基础埋深（米）

RURAL_TYPES = {"house", "detached", "bungalow", "cabin", "hut", "farm",
               "farm_auxiliary", "barn", "shed", "ruins", "rural"}
PUBLIC_TYPES = {"hospital", "school", "university", "college", "train_station",
                "station", "church", "cathedral", "temple", "mosque", "government",
                "civic", "stadium", "sports_hall", "kindergarten"}
FLOOR_H = 3.0                                               # 缺省层高（米）


# ---------------------------------------------------------------- IFC 写出

_GUID_CHARS = ("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz_$")


def _guid(rng: random.Random) -> str:
    return "".join(rng.choice(_GUID_CHARS) for _ in range(22))


def _r(v: float) -> str:
    s = f"{v:.4f}"
    return s if "." in s else s + "."


def _esc(s: str) -> str:
    return str(s).replace("'", "''").replace("\\", "\\\\")


class IfcBuilder:
    """累积三角网构件，输出 IFC4（并按要素分组同步输出 OBJ）。"""

    def __init__(self, name="GL-Sandbox Model", rng=None):
        self.rng = rng or random.Random(20260920)
        self.name = name
        self.body = []
        self.obj_groups = {}    # type -> [(verts, faces, name)]
        self.nid = 100
        self.storey_items = []  # 建筑 / 道路 / 水体
        self.site_items = []    # 地形 / 水面
        self.count = 0

    def _id(self):
        i = self.nid
        self.nid += 1
        return i

    def add_mesh(self, verts, faces, obj_type, name, container="storey",
                 cls="IFCBUILDINGELEMENTPROXY", predefined="$"):
        if not verts or not faces:
            return None
        self.obj_groups.setdefault(obj_type, []).append((verts, faces, name))

        pid = self._id()
        coord = ",".join(f"({_r(v[0])},{_r(v[1])},{_r(v[2])})" for v in verts)
        self.body.append(f"#{pid}=IFCCARTESIANPOINTLIST3D(({coord}));")
        fid = self._id()
        fs = ",".join(f"({a + 1},{b + 1},{c + 1})" for a, b, c in faces)
        self.body.append(f"#{fid}=IFCTRIANGULATEDFACESET(#{pid},$,.T.,$,({fs}));")
        rid = self._id()
        self.body.append(f"#{rid}=IFCSHAPEREPRESENTATION(#10,'Body','Tessellation',(#{fid}));")
        did = self._id()
        self.body.append(f"#{did}=IFCPRODUCTDEFINITIONSHAPE($,$,(#{rid}));")
        eid = self._id()
        g = _guid(self.rng)
        nm, ot = _esc(name), _esc(obj_type)
        if cls == "IFCGEOGRAPHICELEMENT":
            self.body.append(f"#{eid}=IFCGEOGRAPHICELEMENT('{g}',#5,'{nm}',$,'{ot}',"
                             f"#9,#{did},$,{predefined});")
        else:
            self.body.append(f"#{eid}=IFCBUILDINGELEMENTPROXY('{g}',#5,'{nm}',$,'{ot}',"
                             f"#9,#{did},$,{predefined});")
        (self.site_items if container == "site" else self.storey_items).append(eid)
        self.count += 1
        return eid

    def write_ifc(self, path):
        L = ["ISO-10303-21;", "HEADER;",
             "FILE_DESCRIPTION(('ViewDefinition [DesignTransferView]'),'2;1');",
             f"FILE_NAME('{os.path.basename(path)}','2026-01-01T00:00:00',"
             "('DisasterGuard'),(''),'build_revit_model.py','GL-Sandbox','');",
             "FILE_SCHEMA(('IFC4'));", "ENDSEC;", "DATA;",
             "#1=IFCORGANIZATION($,'DisasterGuard',$,$,$);",
             "#2=IFCAPPLICATION(#1,'1.0','build_revit_model','GL-Sandbox');",
             "#3=IFCPERSON($,'sandbox','operator',$,$,$,$,$);",
             "#4=IFCPERSONANDORGANIZATION(#3,#1,$);",
             "#5=IFCOWNERHISTORY(#4,#2,$,.NOCHANGE.,$,$,$,0);",
             "#6=IFCCARTESIANPOINT((0.,0.,0.));",
             "#7=IFCDIRECTION((0.,0.,1.));",
             "#8=IFCDIRECTION((1.,0.,0.));",
             "#9=IFCAXIS2PLACEMENT3D(#6,#7,#8);",
             "#10=IFCGEOMETRICREPRESENTATIONCONTEXT($,'Model',3,1.E-05,#9,#7);",
             "#11=IFCSIUNIT(*,.LENGTHUNIT.,$,.METRE.);",
             "#12=IFCUNITASSIGNMENT((#11));",
             f"#13=IFCPROJECT('{_guid(self.rng)}',#5,'{_esc(self.name)}',$,$,$,$,(#10),#12);",
             f"#14=IFCSITE('{_guid(self.rng)}',#5,'Site',$,$,#9,$,$,.ELEMENT.,$,$,$,$,$);",
             f"#15=IFCRELAGGREGATES('{_guid(self.rng)}',#5,$,$,#13,(#14));",
             f"#16=IFCBUILDING('{_guid(self.rng)}',#5,'Sandbox',$,$,#9,$,$,.ELEMENT.,$,$,$);",
             f"#17=IFCRELAGGREGATES('{_guid(self.rng)}',#5,$,$,#14,(#16));",
             f"#18=IFCBUILDINGSTOREY('{_guid(self.rng)}',#5,'Level 0',$,$,#9,$,$,"
             f".ELEMENT.,0.);",
             f"#19=IFCRELAGGREGATES('{_guid(self.rng)}',#5,$,$,#16,(#18));"]
        L += self.body
        if self.storey_items:
            ids = ",".join(f"#{i}" for i in self.storey_items)
            L.append(f"#{self.nid}=IFCRELCONTAINEDINSPATIALSTRUCTURE("
                     f"'{_guid(self.rng)}',#5,$,$,({ids}),#18);")
            self.nid += 1
        if self.site_items:
            ids = ",".join(f"#{i}" for i in self.site_items)
            L.append(f"#{self.nid}=IFCRELCONTAINEDINSPATIALSTRUCTURE("
                     f"'{_guid(self.rng)}',#5,$,$,({ids}),#14);")
            self.nid += 1
        L += ["ENDSEC;", "END-ISO-10303-21;"]
        with open(path, "w", encoding="utf-8") as f:
            f.write("\n".join(L))
        return self.count

    def write_obj(self, path):
        """按要素类型分组输出 OBJ（Y 轴向上，供 Blender / Lumion 等）。"""
        n_v_total = 0
        with open(path, "w", encoding="utf-8") as f:
            f.write("# GL-Sandbox 渝中半岛全要素模型\n")
            f.write("# 单位：米；Y 轴向上\n")
            for gtype in ("TERRAIN", "WATER", "ROAD-URBAN", "ROAD-MTN",
                          "BLD-CITY", "BLD-PUBLIC", "BLD-RURAL"):
                items = self.obj_groups.get(gtype)
                if not items:
                    continue
                f.write(f"\ng {gtype}\n")
                for verts, faces, _nm in items:
                    base = n_v_total
                    for x, y, z in verts:
                        f.write(f"v {x:.4f} {z:.4f} {y:.4f}\n")
                    n_v_total += len(verts)
                    for a, b, c in faces:
                        f.write(f"f {base + a + 1} {base + b + 1} {base + c + 1}\n")
        return n_v_total


# ---------------------------------------------------------------- 几何工具

def poly_area(p):
    s = 0.0
    for i in range(len(p)):
        x1, y1 = p[i]
        x2, y2 = p[(i + 1) % len(p)]
        s += x1 * y2 - x2 * y1
    return s * 0.5


def point_in_poly(x, y, poly):
    inside = False
    n = len(poly)
    for i in range(n):
        x1, y1 = poly[i]
        x2, y2 = poly[(i + 1) % n]
        if (y1 > y) != (y2 > y):
            xi = x1 + (y - y1) / ((y2 - y1) or 1e-12) * (x2 - x1)
            if x < xi:
                inside = not inside
    return inside


def point_in_tri(px, py, a, b, c):
    def s(u, v):
        return (v[0] - u[0]) * (py - u[1]) - (v[1] - u[1]) * (px - u[0])
    d1, d2, d3 = s(a, b), s(b, c), s(c, a)
    neg = (d1 < -1e-12) or (d2 < -1e-12) or (d3 < -1e-12)
    pos = (d1 > 1e-12) or (d2 > 1e-12) or (d3 > 1e-12)
    return not (neg and pos)


def ear_clip(poly):
    """简单多边形三角化，返回顶点索引三元组。退化情况回退为扇形三角化。"""
    n = len(poly)
    if n < 3:
        return []
    if poly_area(poly) < 0:
        poly = poly[::-1]
    idx = list(range(n))
    tris = []
    guard = 0
    while len(idx) > 3 and guard < 6 * n:
        guard += 1
        cut = False
        for k in range(len(idx)):
            a = idx[(k - 1) % len(idx)]
            b = idx[k]
            c = idx[(k + 1) % len(idx)]
            A, B, C = poly[a], poly[b], poly[c]
            cross = (B[0] - A[0]) * (C[1] - A[1]) - (B[1] - A[1]) * (C[0] - A[0])
            if cross <= 1e-12:
                continue
            ok = True
            for m in idx:
                if m in (a, b, c):
                    continue
                if point_in_tri(poly[m][0], poly[m][1], A, B, C):
                    ok = False
                    break
            if ok:
                tris.append((a, b, c))
                idx.pop(k)
                cut = True
                break
        if not cut:
            break
    if len(idx) >= 3:
        for k in range(1, len(idx) - 1):        # 回退：扇形三角化
            tris.append((idx[0], idx[k], idx[k + 1]))
    return tris


def sample_z(z, u, v):
    """在归一化网格 (u,v)∈[0,1]² 上双线性插值高程。"""
    ny, nx = z.shape
    fi = min(max(v, 0.0), 1.0) * (ny - 1)
    fj = min(max(u, 0.0), 1.0) * (nx - 1)
    i0 = int(min(fi, ny - 2))
    j0 = int(min(fj, nx - 2))
    ti, tj = fi - i0, fj - j0
    return float(z[i0, j0] * (1 - ti) * (1 - tj) + z[i0, j0 + 1] * (1 - ti) * tj
                 + z[i0 + 1, j0] * ti * (1 - tj) + z[i0 + 1, j0 + 1] * ti * tj)


def quad_faces(a, b, c, d):
    return [(a, b, c), (a, c, d)]


# ---------------------------------------------------------------- OSM 获取

def _query(ql: str, tries=3):
    data = urllib.parse.urlencode({"data": ql}).encode()
    last = "unknown"
    for t in range(tries):
        for host in OVERPASS:
            try:
                req = urllib.request.Request(host, data=data, headers={"User-Agent": UA})
                with urllib.request.urlopen(req, timeout=150) as r:
                    body = r.read().decode("utf-8")
                if body.strip().startswith("{"):
                    return json.loads(body)
                last = f"{host} 返回非 JSON"
            except Exception as e:
                last = f"{host}: {e}"
            time.sleep(1.5)
        print(f"[WARN] Overpass 第 {t + 1} 轮失败（{last}），重试 ...")
        time.sleep(5)
    raise RuntimeError(f"Overpass 不可用：{last}")


def fetch_osm(bbox, cache_path, force=False):
    lat1, lon1, lat2, lon2 = bbox[1], bbox[0], bbox[3], bbox[2]
    bb = f"{lat1},{lon1},{lat2},{lon2}"
    if os.path.exists(cache_path) and not force:
        print(f"[INFO] 复用 OSM 缓存 {os.path.basename(cache_path)}")
        return json.load(open(cache_path, encoding="utf-8"))

    jobs = {
        "buildings": f'[out:json][timeout:150];way["building"]({bb});out geom tags;',
        "roads": f'[out:json][timeout:150];way["highway"]({bb});out geom tags;',
        # 面状水体：可用于栅格化
        "waters": ('[out:json][timeout:150];('
                   f'way["natural"="water"]({bb});'
                   f'way["waterway"="riverbank"]({bb});) ;out geom tags;'),
        # 河道中心线：仅用于标注河流名称，不能当多边形栅格化
        "rivers": f'[out:json][timeout:150];way["waterway"="river"]({bb});out tags;',
    }
    out = {}
    for key, ql in jobs.items():
        print(f"[INFO] 查询 OSM {key} ...")
        try:
            out[key] = _query(ql).get("elements", [])
        except Exception as e:
            print(f"[WARN] {key} 获取失败：{e}")
            out[key] = []
        print(f"       {key}: {len(out[key])} 条")
        time.sleep(2)
    json.dump(out, open(cache_path, "w", encoding="utf-8"), ensure_ascii=False)
    print(f"[INFO] OSM 已缓存 -> {cache_path}")
    return out


def way_xy(el, to_xy):
    g = el.get("geometry") or []
    pts = [to_xy(p["lon"], p["lat"]) for p in g if "lon" in p and "lat" in p]
    if len(pts) > 1 and abs(pts[0][0] - pts[-1][0]) < 1e-9 \
            and abs(pts[0][1] - pts[-1][1]) < 1e-9:
        pts = pts[:-1]
    return pts


# ---------------------------------------------------------------- 要素生成

def build_terrain(z_out, xs, ys):
    """地形 TIN + 裙边 + 底座（闭合壳，Revit 导入更稳）。"""
    ny, nx = z_out.shape
    verts = [(float(xs[j]), float(ys[i]), float(z_out[i, j]))
             for i in range(ny) for j in range(nx)]

    def vid(i, j):
        return i * nx + j

    faces = []
    for i in range(ny - 1):
        for j in range(nx - 1):
            a, b, c, d = vid(i, j), vid(i, j + 1), vid(i + 1, j + 1), vid(i + 1, j)
            faces += [(a, b, c), (a, c, d)]

    base = float(z_out.min()) - max(2e-3, float(z_out.max() - z_out.min()) * 0.08)
    b0, b1, b2, b3 = len(verts), len(verts) + 1, len(verts) + 2, len(verts) + 3
    verts += [(xs[0], ys[0], base), (xs[-1], ys[0], base),
              (xs[-1], ys[-1], base), (xs[0], ys[-1], base)]
    faces += [(b3, b2, b1), (b3, b1, b0)]
    for j in range(nx - 1):
        faces += [(vid(0, j), b0, b1), (vid(0, j), b1, vid(0, j + 1))]
        faces += [(vid(ny - 1, j), vid(ny - 1, j + 1), b2), (vid(ny - 1, j), b2, b3)]
    for i in range(ny - 1):
        faces += [(vid(i, 0), vid(i + 1, 0), b1), (vid(i, 0), b1, b0)]
        faces += [(vid(i, nx - 1), b2, vid(i + 1, nx - 1)), (vid(i, nx - 1), b3, b2)]
    return verts, faces


def keep_components(mask, min_cells):
    """保留面积不小于 min_cells 的 4-邻域连通域，滤掉零散水洼。"""
    from collections import deque
    ny, nx = mask.shape
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
            if len(comp) >= min_cells:
                for a, b in comp:
                    out[a, b] = True
    return out


def build_water(area_polys, z_real, xs, ys, level_real, level_out, min_ratio=0.002):
    """
    水面 = 真实河床低于水位的区域（DEM 判据），叠加 OSM 面状水体（natural=water /
    waterway=riverbank），再滤掉零散水洼。

    ★ 不要用 waterway=river 生成水面：它是河道**中心线**（LineString），
      当多边形栅格化只会得到一条细线。OSM 中心线仅用于标注河流名称。

    判据用真实高程，写入的高程用 VE 变换后的水位，两者不要混用。
    """
    ny, nx = z_real.shape
    dx = (xs[-1] - xs[0]) / max(nx - 1, 1)
    dy = (ys[-1] - ys[0]) / max(ny - 1, 1)
    area_mask = np.zeros((ny, nx), dtype=bool)
    for poly in area_polys or []:
        px = [p[0] for p in poly]
        py = [p[1] for p in poly]
        j0 = int(max(0, math.floor((min(px) - xs[0]) / dx)))
        j1 = int(min(nx - 1, math.ceil((max(px) - xs[0]) / dx)))
        i0 = int(max(0, math.floor((min(py) - ys[0]) / dy)))
        i1 = int(min(ny - 1, math.ceil((max(py) - ys[0]) / dy)))
        for i in range(i0, i1 + 1):
            for j in range(j0, j1 + 1):
                if not area_mask[i, j] and point_in_poly(xs[j], ys[i], poly):
                    area_mask[i, j] = True

    mask = (z_real <= level_real) | (area_mask & (z_real <= level_real + 8.0))
    mask = keep_components(mask, max(4, int(mask.size * min_ratio)))
    if not mask.any():
        return [], [], None

    verts, faces, index = [], [], {}
    for i in range(ny):
        for j in range(nx):
            if mask[i, j]:
                index[(i, j)] = len(verts)
                verts.append((float(xs[j]), float(ys[i]), level_out))
    for (i, j), a in list(index.items()):
        k1 = index.get((i, j + 1))
        k2 = index.get((i + 1, j + 1))
        k3 = index.get((i + 1, j))
        if k1 is not None and k2 is not None and k3 is not None:
            faces += [(a, k1, k2), (a, k2, k3)]
    return verts, faces, mask


def _inside(p, span_x, span_y, m):
    return -m <= p[0] <= span_x + m and -m <= p[1] <= span_y + m


def split_inside(pts, span_x, span_y, m):
    """
    Overpass 返回的是**完整** way（只要与 bbox 相交就整条返回），
    顶点常常远在场地之外。这里把折线拆成落在场地内的连续段。
    """
    segs, cur = [], []
    for p in pts:
        if _inside(p, span_x, span_y, m):
            cur.append(p)
        elif len(cur) >= 2:
            segs.append(cur)
            cur = []
        else:
            cur = []
    if len(cur) >= 2:
        segs.append(cur)
    return segs


def build_roads(elements, to_xy, z_out, span_x, span_y, hs):
    """中心线 -> 带状路面。hs = 水平比尺系数（真实米 -> 模型米）。"""
    out = []
    m = max(span_x, span_y) * 0.01
    for el in elements:
        tags = el.get("tags", {})
        hw = tags.get("highway", "residential")
        for pts in split_inside(way_xy(el, to_xy), span_x, span_y, m):
            if len(pts) < 2:
                continue
            out.append(_road_ribbon(pts, hw, tags, el, z_out, span_x, span_y, hs))
    return [r for r in out if r]


def _road_ribbon(pts, hw, tags, el, z_out, span_x, span_y, hs):
    """单段中心线 -> 带状面。"""
    w = ROAD_WIDTH.get(hw, 6.0) * 0.5 * hs
    lift = ROAD_LIFT * hs
    verts, faces = [], []
    normals = []
    for k in range(len(pts)):
        if k == 0:
            d = (pts[1][0] - pts[0][0], pts[1][1] - pts[0][1])
        elif k == len(pts) - 1:
            d = (pts[-1][0] - pts[-2][0], pts[-1][1] - pts[-2][1])
        else:
            d = (pts[k + 1][0] - pts[k - 1][0], pts[k + 1][1] - pts[k - 1][1])
        L = math.hypot(*d) or 1.0
        normals.append((-d[1] / L, d[0] / L))
    total = 0.0
    for k, (x, y) in enumerate(pts):
        nx_, ny_ = normals[k]
        zz = sample_z(z_out, x / span_x, y / span_y) + lift
        verts.append((x + nx_ * w, y + ny_ * w, zz))
        verts.append((x - nx_ * w, y - ny_ * w, zz))
        if k:
            total += math.hypot(x - pts[k - 1][0], y - pts[k - 1][1])
    for k in range(len(pts) - 1):
        faces += quad_faces(2 * k, 2 * k + 1, 2 * k + 3, 2 * k + 2)
    kind = "ROAD-MTN" if hw in MOUNTAIN_ROADS else "ROAD-URBAN"
    nm = tags.get("name") or f"{hw}-{el.get('id')}"
    return (kind, nm, verts, faces, total, hw)


def building_height(tags, area, rng):
    for key in ("height", "building:height"):
        v = tags.get(key)
        if v:
            try:
                return max(3.0, float(str(v).replace("m", "").strip()))
            except ValueError:
                pass
    lv = tags.get("building:levels") or tags.get("building:levels:aboveground")
    if lv:
        try:
            return max(3.0, float(lv) * FLOOR_H)
        except ValueError:
            pass
    bt = tags.get("building", "yes")
    if bt in RURAL_TYPES:
        return rng.uniform(4.0, 7.0)
    if bt in PUBLIC_TYPES:
        return rng.uniform(12.0, 33.0)
    base = 9.0 + min(55.0, math.sqrt(max(area, 1.0)) * 0.9)
    return base * rng.uniform(0.75, 1.25)


def build_buildings(elements, to_xy, z_out, span_x, span_y, hs, bs, rng, max_n):
    """OSM footprint -> 挤出体块。返回 [(分类, 名称, verts, faces, meta)]"""
    items = []
    m = max(span_x, span_y) * 0.01
    for el in elements:
        tags = el.get("tags", {})
        poly = way_xy(el, to_xy)
        if len(poly) < 3:
            continue
        if not all(_inside(p, span_x, span_y, m) for p in poly):
            continue                   # Overpass 会返回跨界要素，超出场地的整栋丢弃
        area = abs(poly_area(poly))
        if area < 12.0 * hs * hs:      # 过滤碎面（随比尺缩放）
            continue
        items.append((area, el, tags, poly))
    items.sort(key=lambda t: -t[0])
    items = items[:max_n]

    out = []
    for area, el, tags, poly in items:
        if poly_area(poly) < 0:                # 统一为 CCW，保证侧墙法向朝外
            poly = poly[::-1]
        bt = tags.get("building", "yes")
        zs = [sample_z(z_out, min(max(p[0] / span_x, 0), 1), min(max(p[1] / span_y, 0), 1))
              for p in poly]
        zbase = min(zs)
        h_real = building_height(tags, area / max(hs * hs, 1e-12), rng)
        h = h_real * hs * bs
        if bt in RURAL_TYPES:
            cls = "BLD-RURAL"
        elif bt in PUBLIC_TYPES:
            cls = "BLD-PUBLIC"
        else:
            cls = "BLD-CITY"
        zb = zbase - FOUNDATION * hs * bs
        zt = zb + h
        verts = [(p[0], p[1], zb) for p in poly] + [(p[0], p[1], zt) for p in poly]
        n = len(poly)
        faces = []
        for k in range(n):                       # 侧墙
            a, b = k, (k + 1) % n
            faces += quad_faces(a, b, n + b, n + a)
        for a, b, c in ear_clip(poly):           # 屋面
            faces.append((n + a, n + b, n + c))
        nm = tags.get("name") or f"{bt}-{el.get('id')}"
        cx = sum(p[0] for p in poly) / n
        cy = sum(p[1] for p in poly) / n
        out.append((cls, nm, verts, faces,
                    {"id": el.get("id"), "area_m2": round(area / max(hs * hs, 1e-12), 1),
                     "height_m": round(h_real, 1), "levels": tags.get("building:levels", ""),
                     "z_base_m": round(zbase, 1), "kind": bt, "cx": cx, "cy": cy}))
    return out


def rural_house(cx, cy, zb, w, d, wall, ridge, rng):
    """坡屋顶民居：4 面墙 + 2 坡屋面 + 2 山墙。"""
    ang = rng.uniform(0, math.pi)
    ca, sa = math.cos(ang), math.sin(ang)

    def loc(u, v):
        return (cx + u * ca - v * sa, cy + u * sa + v * ca)

    b = [loc(-w / 2, -d / 2), loc(w / 2, -d / 2), loc(w / 2, d / 2), loc(-w / 2, d / 2)]
    verts = [(x, y, zb) for x, y in b]
    verts += [(x, y, zb + wall) for x, y in b]
    r0, r1 = loc(0.0, -d / 2), loc(0.0, d / 2)
    verts += [(r0[0], r0[1], zb + wall + ridge), (r1[0], r1[1], zb + wall + ridge)]
    faces = []
    for k in range(4):
        a, bb = k, (k + 1) % 4
        faces += quad_faces(a, bb, 4 + bb, 4 + a)
    # 绕向：屋脊 8(v=-d/2 端) / 9(v=+d/2 端)，两个坡面分别位于 u 的负侧与正侧
    faces += quad_faces(4, 8, 9, 7)          # 坡面（u 负侧，法向 -u +z）
    faces += quad_faces(5, 6, 9, 8)          # 坡面（u 正侧，法向 +u +z）
    faces += [(4, 5, 8), (7, 9, 6)]          # 山墙（v 两端）
    return verts, faces


def place_rural(z_out, z_real, xs, ys, mask_water, occupied, n, hs, bs, rng,
                span_x_real, span_y_real):
    """
    在坡地（非水体 / 非建成区 / 非陡崖）程序化补布乡村民居。

    ★ 坡度必须用**真实**水平距离计算：z_real 是真实米，若用沙盘模型的
      网格间距（0.01 m 量级）去除，坡度会被放大到几万度，候选点全部落空。
    """
    ny, nx = z_out.shape
    dx = span_x_real / max(nx - 1, 1)
    dy = span_y_real / max(ny - 1, 1)
    gy, gx = np.gradient(z_real)
    slope = np.degrees(np.arctan(np.hypot(gx / dx, gy / dy)))
    ztop = float(np.percentile(z_out, 82))
    cand = []
    for i in range(1, ny - 1):
        for j in range(1, nx - 1):
            if mask_water is not None and mask_water[i, j]:
                continue
            if not (5.0 <= slope[i, j] <= 28.0):
                continue
            if z_out[i, j] > ztop:
                continue
            p = (float(xs[j]), float(ys[i]))
            r2 = (30.0 * hs) ** 2
            if any((p[0] - o[0]) ** 2 + (p[1] - o[1]) ** 2 < r2 for o in occupied):
                continue
            cand.append((i, j, p))
    if not cand:
        return []
    rng.shuffle(cand)
    out = []
    for i, j, p in cand[:n]:
        zb = float(z_out[i, j])
        w = rng.uniform(6.0, 10.0) * hs * bs
        d = rng.uniform(5.0, 8.0) * hs * bs
        wall = rng.uniform(3.0, 4.2) * hs * bs
        ridge = rng.uniform(1.6, 2.6) * hs * bs
        verts, faces = rural_house(p[0], p[1], zb, w, d, wall, ridge, rng)
        out.append(("BLD-RURAL", f"坡地民居-{len(out) + 1:02d}", verts, faces,
                    {"id": "", "area_m2": round(w * d / max(hs * hs, 1e-12), 1),
                     "height_m": round((wall + ridge) / max(hs * bs, 1e-12), 1),
                     "levels": "1-2", "z_base_m": round(zb, 1),
                     "kind": "rural(程序化)", "cx": p[0], "cy": p[1]}))
        occupied.append(p)
    return out


# ---------------------------------------------------------------- 主流程

def main():
    ap = argparse.ArgumentParser(description="真实 DEM + OSM -> 全要素 Revit 模型")
    src = ap.add_mutually_exclusive_group()
    src.add_argument("--dem", help="本地 DEM（*.tif 需 rasterio / *.asc 零依赖）")
    src.add_argument("--fetch", action="store_true", help="在线地形瓦片（需 pillow）")
    src.add_argument("--synthetic", action="store_true", help="合成地形自检（离线）")
    ap.add_argument("--bbox", nargs=4, type=float, metavar=("LON1", "LAT1", "LON2", "LAT2"))
    ap.add_argument("--out", default="./revit_model")
    ap.add_argument("--target", choices=["terrain", "sandbox"], default="terrain",
                    help="terrain=真实 1:1；sandbox=沙盘实体（1.2x0.8m + 垂直夸张）")
    ap.add_argument("--grid-x", type=int, default=120)
    ap.add_argument("--grid-y", type=int, default=80)
    ap.add_argument("--zoom", type=int, default=15, help="瓦片层级（--fetch）")
    ap.add_argument("--ve", type=float, default=None, help="垂直夸张，默认 terrain=1 / sandbox=2.5")
    ap.add_argument("--smooth", type=int, default=1)
    ap.add_argument("--water-level", type=float, default=168.0, help="水面高程（真实米）")
    ap.add_argument("--max-buildings", type=int, default=1200)
    ap.add_argument("--rural", type=int, default=40, help="程序化乡村民居数量，0=关闭")
    ap.add_argument("--building-scale", type=float, default=1.0,
                    help="建筑高度附加放大倍数（沙盘视觉需要时可设 1.5~2）")
    ap.add_argument("--no-water", action="store_true")
    ap.add_argument("--no-roads", action="store_true")
    ap.add_argument("--no-buildings", action="store_true")
    ap.add_argument("--osm-cache", default=None, help="OSM 缓存 JSON 路径")
    ap.add_argument("--refresh-osm", action="store_true", help="忽略缓存重新抓取")
    ap.add_argument("--seed", type=int, default=20260920)
    args = ap.parse_args()

    if not (args.dem or args.fetch or args.synthetic):
        ap.error("需指定 --dem / --fetch / --synthetic 之一")

    bbox = tuple(args.bbox) if args.bbox else None
    if args.synthetic:
        z_raw, bbox = foam.load_synthetic()
    elif args.dem:
        if args.dem.lower().endswith((".tif", ".tiff")):
            z_raw, bbox = foam.load_tif(args.dem, bbox)
        elif args.dem.lower().endswith(".asc"):
            z_raw, bbox = foam.load_asc(args.dem)
        else:
            sys.exit("[ERR] 仅支持 *.tif / *.asc")
    else:
        z_raw, bbox = foam.load_tiles(bbox or DEFAULT_BBOX, args.zoom)

    if args.smooth > 0:
        z_raw = foam.smooth(z_raw, args.smooth)

    lon1, lat1, lon2, lat2 = bbox
    span_x = abs(lon2 - lon1) * 111320.0 * math.cos(math.radians((lat1 + lat2) / 2))
    span_y = abs(lat2 - lat1) * 110540.0
    zmin = float(np.min(z_raw))
    zmax = float(np.max(z_raw))
    print(f"[INFO] DEM 高程 {zmin:.1f} ~ {zmax:.1f} m（高差 {zmax - zmin:.1f} m）")
    if zmin < 0:
        print(f"[WARN] 高程含负值（{zmin:.1f} m），请检查 DEM 是否已清洗")

    ve = args.ve if args.ve is not None else (1.0 if args.target == "terrain" else 2.5)
    if args.target == "sandbox":
        scale = 1.2 / span_x
        span_x_m, span_y_m = span_x * scale, span_y * scale
        hs = scale                       # 真实米 -> 沙盘米
        print(f"[INFO] 沙盘模式：水平 1:{1 / hs:.0f}，VE={ve}，"
              f"尺寸 {span_x_m:.2f} x {span_y_m:.2f} m")
    else:
        span_x_m, span_y_m = span_x, span_y
        hs = 1.0
        print(f"[INFO] 真实地形模式：{span_x_m:.0f} x {span_y_m:.0f} m，VE={ve}")

    xs = np.linspace(0.0, span_x_m, args.grid_x)
    ys = np.linspace(0.0, span_y_m, args.grid_y)
    z_real = foam.resample(z_raw, args.grid_x, args.grid_y)
    if args.target == "sandbox":
        # 沙盘：从 0 起算的实体起伏 = 相对高差 × 水平比尺 × 垂直夸张
        z_out = (z_real - zmin) * hs * ve
        level_out = (args.water_level - zmin) * hs * ve
        print(f"[INFO] 沙盘起伏 {z_out.max():.3f} m（最高点），"
              f"建议泡沫层数按 {z_out.max() * 1000 / 20:.0f} 层 × 20 mm 估算")
    else:
        z_out = zmin + (z_real - zmin) * ve             # 真实高程（VE 默认 1）
        level_out = args.water_level

    def to_xy(lon, lat):
        return ((lon - lon1) / (lon2 - lon1) * span_x_m,
                (lat - lat1) / (lat2 - lat1) * span_y_m)

    os.makedirs(args.out, exist_ok=True)
    rng = random.Random(args.seed)
    ifc = IfcBuilder("GL-Sandbox 渝中半岛沙盘", rng=rng)
    features = []

    # --- 山体
    tv, tf = build_terrain(z_out, xs, ys)
    ifc.add_mesh(tv, tf, "TERRAIN", "TERRAIN 渝中半岛地形",
                 container="site", cls="IFCGEOGRAPHICELEMENT", predefined=".TERRAIN.")
    print(f"[OK] 山体：{len(tv)} 点 / {len(tf)} 面")

    # --- OSM
    cache = args.osm_cache or os.path.join(args.out, "_osm_cache.json")
    if args.synthetic:
        osm = {"buildings": [], "roads": [], "waters": []}
        print("[INFO] 合成地形自检：跳过 OSM")
    else:
        try:
            osm = fetch_osm(bbox, cache, force=args.refresh_osm)
        except Exception as e:
            print(f"[WARN] OSM 不可用（{e}），仅输出地形")
            osm = {"buildings": [], "roads": [], "waters": []}

    # --- 水体
    mask_water = None
    if not args.no_water:
        polys = [p for p in (way_xy(e, to_xy) for e in osm.get("waters", [])) if len(p) >= 3]
        wv, wf, mask_water = build_water(polys, z_real, xs, ys, args.water_level, level_out)
        rivers = sorted({(e.get("tags", {}).get("name") or "未命名河流")
                         for e in osm.get("rivers", [])})
        if rivers:
            print(f"[INFO] 河道中心线（OSM，仅标注）：{'、'.join(rivers)}")
        if wv:
            ifc.add_mesh(wv, wf, "WATER", f"WATER 水面 z={args.water_level:.1f}m",
                         container="site", cls="IFCGEOGRAPHICELEMENT",
                         predefined=".USERDEFINED.")
            print(f"[OK] 水体：{len(wv)} 点 / {len(wf)} 面"
                  f"（水位 {args.water_level:.1f} m，河谷来自 DEM，"
                  f"OSM 面状水体 {len(polys)} 个）")
        else:
            print(f"[WARN] 未生成水面：水位 {args.water_level} m 低于河床，"
                  f"可调高 --water-level")

    # --- 道路
    if not args.no_roads:
        roads = build_roads(osm.get("roads", []), to_xy, z_out, span_x_m, span_y_m, hs)
        for kind, nm, v, f, length, hw in roads:
            ifc.add_mesh(v, f, kind, f"{kind} {nm}")
            features.append([kind, nm, hw, round(v[0][0], 2), round(v[0][1], 2),
                             round(v[0][2], 2), "", round(length / max(hs, 1e-12), 1), ""])
        nm_mtn = sum(1 for k, *_ in roads if k == "ROAD-MTN")
        print(f"[OK] 道路：{len(roads)} 条（山路/梯道 {nm_mtn} 条，"
              f"城市道路 {len(roads) - nm_mtn} 条）")

    # --- 建筑（城市 / 公共 / 乡村）
    occupied = []
    if not args.no_buildings:
        blds = build_buildings(osm.get("buildings", []), to_xy, z_out, span_x_m, span_y_m,
                               hs, args.building_scale, rng, args.max_buildings)
        for cls, nm, v, f, meta in blds:
            ifc.add_mesh(v, f, cls, f"{cls} {nm}")
            occupied.append((meta["cx"], meta["cy"]))
            features.append([cls, nm, meta["kind"], round(meta["cx"], 2), round(meta["cy"], 2),
                             meta["z_base_m"], meta["height_m"], meta["area_m2"],
                             meta["levels"]])
        n_city = sum(1 for c, *_ in blds if c == "BLD-CITY")
        n_pub = sum(1 for c, *_ in blds if c == "BLD-PUBLIC")
        print(f"[OK] 建筑：{len(blds)} 栋（城市 {n_city} / 公共 {n_pub}）"
              f"—— OSM 真实 footprint")

        if args.rural > 0:
            rur = place_rural(z_out, z_real, xs, ys, mask_water, occupied,
                              args.rural, hs, args.building_scale, rng,
                              span_x, span_y)
            for cls, nm, v, f, meta in rur:
                ifc.add_mesh(v, f, cls, f"{cls} {nm}")
                features.append([cls, nm, "rural(程序化)", round(meta["cx"], 2),
                                 round(meta["cy"], 2), meta["z_base_m"], meta["height_m"],
                                 meta["area_m2"], meta["levels"]])
            print(f"[OK] 乡村民居：{len(rur)} 座（坡地程序化布点，非真实建筑）")

    # --- IFC
    ifc_path = os.path.join(args.out, "model.ifc")
    n = ifc.write_ifc(ifc_path)
    print(f"[OK] IFC4 -> {ifc_path}（{n} 个构件 / {os.path.getsize(ifc_path) // 1024} KB）")

    # --- OBJ
    obj_path = os.path.join(args.out, "model.obj")
    nv = ifc.write_obj(obj_path)
    print(f"[OK] OBJ -> {obj_path}（{nv} 顶点 / {os.path.getsize(obj_path) // 1024} KB）")

    # --- Revit 地形点文件
    csv_path = os.path.join(args.out, "terrain_points.csv")
    with open(csv_path, "w", encoding="utf-8") as f:
        for i in range(args.grid_y):
            for j in range(args.grid_x):
                f.write(f"{xs[j]:.3f},{ys[i]:.3f},{float(z_out[i, j]):.3f}\n")
    print(f"[OK] Revit 地形点文件 -> {csv_path}（{args.grid_x * args.grid_y} 点）")

    # --- 要素清单
    fea_path = os.path.join(args.out, "features.csv")
    with open(fea_path, "w", encoding="utf-8") as f:
        f.write("class,name,osm_kind,X_m,Y_m,Z_base_m,height_m,footprint_m2,levels\n")
        for row in features:
            f.write(",".join(str(x) for x in row) + "\n")
    print(f"[OK] 要素清单 -> {fea_path}（{len(features)} 条）")

    print("\n[Revit 导入]")
    print("  1) Revit →「打开」→ 文件类型 IFC → 选 model.ifc →「另存为」即得 .rvt")
    print("  2) 或「体量和场地」→「地形表面」→「通过导入创建」→「指定点文件」")
    print(f"     → 选 {os.path.basename(csv_path)}，单位「米」")
    print("  3) 构件按 ObjectType 分类：TERRAIN / WATER / ROAD-MTN / ROAD-URBAN /")
    print("     BLD-CITY / BLD-PUBLIC / BLD-RURAL，Revit 中按此筛选并替换为真实族")


if __name__ == "__main__":
    main()
