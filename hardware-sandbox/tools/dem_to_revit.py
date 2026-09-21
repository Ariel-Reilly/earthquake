#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
真实 DEM -> Revit 地形文件（重庆山地沙盘配套）

说明：.rvt 是 Autodesk 专有二进制格式，无法由脚本直接生成。
本工具输出 Revit **原生支持导入**的三种文件：

  1. terrain_points.csv  —— ★ 主路径。Revit「体量和场地」→「地形表面」
                            →「通过导入创建」→「指定点文件」，格式 X,Y,Z（米）
  2. terrain.ifc         —— IFC4（IfcTriangulatedFaceSet）。Revit 可直接打开，
                            再「另存为」即得到 .rvt
  3. terrain.obj         —— 通用三角网，供 Lumion / Blender / Twinmotion / 3ds Max 等

用法
----
  # 真实地形（1:1，无垂直夸张）—— 用于展示、分析、GIS 对齐
  python dem_to_revit.py --dem chongqing.asc --out ./revit_out

  # 沙盘实体地形（按 1:2421 缩放 + 垂直夸张 2.5）—— 用于设计沙盘本体
  python dem_to_revit.py --dem chongqing.asc --target sandbox --out ./revit_out

  # 无 DEM 时先用合成地形自检
  python dem_to_revit.py --synthetic --out ./revit_out

可选 --crs epsg:4545 需要 pyproj（CGCS2000 / 3度带 / 中央子午线108E）。
不装 pyproj 时用局部平面坐标（原点=西南角），2.9 km 范围内误差 < 1 m，足够建模。
"""

from __future__ import annotations

import argparse
import math
import os
import random
import re
import string
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

try:
    import dem_to_foam as foam          # 复用 DEM 载入 / 重采样 / 平滑
except ImportError:
    sys.exit("[ERR] 需要同目录下的 dem_to_foam.py")

DEFAULT_BBOX = (106.5150, 29.5425, 106.5450, 29.5600)


# ---------------------------------------------------------------- IFC 辅助

def ifc_guid() -> str:
    chars = string.digits + string.ascii_uppercase + string.ascii_lowercase + "_$"
    return "".join(random.choice(chars) for _ in range(22))


def ifc_real(v: float) -> str:
    s = f"{v:.4f}"
    if "." not in s:
        s += "."
    return s


def write_ifc(path, xs, ys, z, name="GL-Sandbox Terrain", closed=True):
    """
    输出 IFC4：IfcGeographicElement + IfcTriangulatedFaceSet。
    closed=True 时补裙边与底面形成闭合壳，Revit 导入成功率更高。
    """
    ny, nx = z.shape
    pts = []
    for i in range(ny):
        for j in range(nx):
            pts.append((xs[j], ys[i], float(z[i, j])))

    def vid(i, j):
        return i * nx + j + 1          # IFC 索引 1-based

    faces = []
    for i in range(ny - 1):
        for j in range(nx - 1):
            a, b, c, d = vid(i, j), vid(i, j + 1), vid(i + 1, j + 1), vid(i + 1, j)
            faces.append((a, b, c))
            faces.append((a, c, d))

    if closed:
        base = float(z.min()) - max(1.0, float(z.max() - z.min()) * 0.1)
        # 底面（反向，保证法向朝外）
        b0 = len(pts) + 1
        b1 = len(pts) + 2
        b2 = len(pts) + 3
        b3 = len(pts) + 4
        pts += [(xs[0], ys[0], base), (xs[-1], ys[0], base),
                (xs[-1], ys[-1], base), (xs[0], ys[-1], base)]
        faces.append((b3, b2, b1))
        faces.append((b3, b1, b0))
        # 四边裙边
        for j in range(nx - 1):
            faces.append((vid(0, j), b0, b1))
            faces.append((vid(0, j), b1, vid(0, j + 1)))
            faces.append((vid(ny - 1, j), vid(ny - 1, j + 1), b2))
            faces.append((vid(ny - 1, j), b2, b3))
        for i in range(ny - 1):
            faces.append((vid(i, 0), vid(i + 1, 0), b1))
            faces.append((vid(i, 0), b1, b0))
            faces.append((vid(i, nx - 1), b2, vid(i + 1, nx - 1)))
            faces.append((vid(i, nx - 1), b3, b2))

    L = []
    L.append("ISO-10303-21;")
    L.append("HEADER;")
    L.append("FILE_DESCRIPTION(('ViewDefinition [DesignTransferView]'),'2;1');")
    L.append(f"FILE_NAME('{os.path.basename(path)}','2026-01-01T00:00:00',"
             "('DisasterGuard'),(''),'dem_to_revit.py','GL-Sandbox','');")
    L.append("FILE_SCHEMA(('IFC4'));")
    L.append("ENDSEC;")
    L.append("DATA;")

    # --- 上下文与单位
    L.append("#1=IFCORGANIZATION($,'DisasterGuard',$,$,$);")
    L.append("#2=IFCAPPLICATION(#1,'1.0','dem_to_revit','GL-Sandbox');")
    L.append("#3=IFCPERSON($,'sandbox','operator',$,$,$,$,$);")
    L.append("#4=IFCPERSONANDORGANIZATION(#3,#1,$);")
    L.append("#5=IFCOWNERHISTORY(#4,#2,$,.NOCHANGE.,$,$,$,0);")
    L.append("#6=IFCCARTESIANPOINT((0.,0.,0.));")
    L.append("#7=IFCDIRECTION((0.,0.,1.));")
    L.append("#8=IFCDIRECTION((1.,0.,0.));")
    L.append("#9=IFCAXIS2PLACEMENT3D(#6,#7,#8);")
    L.append("#10=IFCGEOMETRICREPRESENTATIONCONTEXT($,'Model',3,1.E-05,#9,#7);")
    L.append("#11=IFCSIUNIT(*,.LENGTHUNIT.,$,.METRE.);")
    L.append("#12=IFCUNITASSIGNMENT((#11));")
    L.append(f"#13=IFCPROJECT('{ifc_guid()}',#5,'{name}',$,$,$,$,(#10),#12);")
    L.append(f"#14=IFCSITE('{ifc_guid()}',#5,'Site',$,$,#9,$,$,.ELEMENT.,$,$,$,$,$);")
    L.append(f"#15=IFCRELAGGREGATES('{ifc_guid()}',#5,$,$,#13,(#14));")

    # --- 几何
    coord = ",".join(f"({ifc_real(x)},{ifc_real(y)},{ifc_real(zz)})" for x, y, zz in pts)
    L.append(f"#20=IFCCARTESIANPOINTLIST3D(({coord}));")
    faces_s = ",".join(f"({a},{b},{c})" for a, b, c in faces)
    L.append(f"#21=IFCTRIANGULATEDFACESET(#20,$,{'.T.' if closed else '.F.'},$,({faces_s}));")
    L.append("#22=IFCSHAPEREPRESENTATION(#10,'Body','Tessellation',(#21));")
    L.append("#23=IFCPRODUCTDEFINITIONSHAPE($,$,(#22));")
    L.append(f"#24=IFCGEOGRAPHICELEMENT('{ifc_guid()}',#5,'{name}',$,$,#9,#23,$,.TERRAIN.);")
    L.append(f"#25=IFCRELCONTAINEDINSPATIALSTRUCTURE('{ifc_guid()}',#5,$,$,(#24),#14);")

    L.append("ENDSEC;")
    L.append("END-ISO-10303-21;")

    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(L))
    return len(pts), len(faces)


def write_obj(path, xs, ys, z):
    ny, nx = z.shape
    with open(path, "w", encoding="utf-8") as f:
        f.write("# GL-Sandbox terrain\n")
        for i in range(ny):
            for j in range(nx):
                f.write(f"v {xs[j]:.4f} {float(z[i, j]):.4f} {ys[i]:.4f}\n")
        for i in range(ny - 1):
            for j in range(nx - 1):
                a = i * nx + j + 1
                b = a + 1
                c = (i + 1) * nx + j + 2
                d = c - 1
                f.write(f"f {a} {b} {c}\nf {a} {c} {d}\n")


# ---------------------------------------------------------------- 布点解析

HZ_RE = re.compile(
    r"\('HAZARD',.*?'(HZ-[A-Z0-9\-]+)','([^']*)','([^']*)'.*?(106\.\d+),(29\.\d+)", re.S)

DEV_RE = re.compile(
    r"\('(SB-[A-Z0-9\-]+|WS-[A-Z0-9\-]+)','([^']*)','([^']*)','(HZ-[A-Z0-9\-]+)'"
    r".*?(106\.\d+),(29\.\d+)", re.S)


def parse_registry(sql_path):
    """从 register_devices.sql 提取隐患点与设备的经纬度（避免坐标二次维护）"""
    txt = open(sql_path, encoding="utf-8").read()
    hazards = [(m.group(1), m.group(2), m.group(3), float(m.group(4)), float(m.group(5)))
               for m in HZ_RE.finditer(txt)]
    devices = [(m.group(1), m.group(2), m.group(3), m.group(4),
                float(m.group(5)), float(m.group(6)))
               for m in DEV_RE.finditer(txt)]
    return hazards, devices


def sample_z(z, u, v):
    """在归一化网格坐标 (u,v)∈[0,1]² 上双线性插值高程"""
    ny, nx = z.shape
    fi = min(max(v, 0.0), 1.0) * (ny - 1)
    fj = min(max(u, 0.0), 1.0) * (nx - 1)
    i0 = int(min(fi, ny - 2))
    j0 = int(min(fj, nx - 2))
    ti, tj = fi - i0, fj - j0
    return float(z[i0, j0] * (1 - ti) * (1 - tj) + z[i0, j0 + 1] * (1 - ti) * tj
                 + z[i0 + 1, j0] * ti * (1 - tj) + z[i0 + 1, j0 + 1] * ti * tj)


def write_points_csv(path, header, rows):
    with open(path, "w", encoding="utf-8") as f:
        f.write(header + "\n")
        for r in rows:
            f.write(",".join(r) + "\n")
    return len(rows)


# ---------------------------------------------------------------- 主流程

def main():
    ap = argparse.ArgumentParser(description="真实 DEM -> Revit 地形文件")
    src = ap.add_mutually_exclusive_group()
    src.add_argument("--dem", help="本地 DEM（*.tif 需 rasterio / *.asc 零依赖）")
    src.add_argument("--fetch", action="store_true", help="在线地形瓦片（需 pillow）")
    src.add_argument("--synthetic", action="store_true", help="合成地形自检")
    ap.add_argument("--bbox", nargs=4, type=float, metavar=("LON1", "LAT1", "LON2", "LAT2"))
    ap.add_argument("--out", default="./revit_out")
    ap.add_argument("--target", choices=["terrain", "sandbox"], default="terrain",
                    help="terrain=真实 1:1 地形；sandbox=按沙盘比尺缩放后的实体地形")
    ap.add_argument("--grid-x", type=int, default=120, help="输出网格列数（点数≈grid-x*grid-y）")
    ap.add_argument("--grid-y", type=int, default=80)
    ap.add_argument("--ve", type=float, default=None, help="垂直夸张，默认 terrain=1 / sandbox=2.5")
    ap.add_argument("--smooth", type=int, default=1)
    ap.add_argument("--zoom", type=int, default=15,
                    help="在线瓦片层级（--fetch），默认 15（约 4.2 m/px）")
    ap.add_argument("--crs", default="local", help="local 或 epsg:4545（后者需 pyproj）")
    ap.add_argument("--no-ifc", action="store_true")
    ap.add_argument("--no-obj", action="store_true")
    ap.add_argument("--sensors", nargs="?", const="auto", default=None,
                    help="额外输出传感器布点 CSV（默认读取 gateway/config.yaml）")
    args = ap.parse_args()

    if not (args.dem or args.fetch or args.synthetic):
        ap.error("需指定 --dem / --fetch / --synthetic 之一")

    bbox = tuple(args.bbox) if args.bbox else None

    if args.synthetic:
        z, bbox = foam.load_synthetic()
    elif args.dem:
        if args.dem.lower().endswith((".tif", ".tiff")):
            z, bbox = foam.load_tif(args.dem, bbox)
        elif args.dem.lower().endswith(".asc"):
            z, bbox = foam.load_asc(args.dem)
        else:
            sys.exit("[ERR] 仅支持 *.tif / *.asc")
    else:
        z, bbox = foam.load_tiles(bbox or DEFAULT_BBOX, args.zoom)

    if args.smooth > 0:
        z = foam.smooth(z, args.smooth)

    zmin, zmax = float(np.min(z)), float(np.max(z))
    print(f"[INFO] 高程 {zmin:.1f} ~ {zmax:.1f} m（高差 {zmax - zmin:.1f} m）")

    lon1, lat1, lon2, lat2 = bbox
    span_x = abs(lon2 - lon1) * 111320.0 * math.cos(math.radians((lat1 + lat2) / 2))
    span_y = abs(lat2 - lat1) * 110540.0

    # --- 水平坐标（米）
    if args.crs.lower().startswith("epsg"):
        try:
            from pyproj import Transformer
        except ImportError:
            sys.exit("[ERR] --crs epsg:... 需要 pyproj： pip install pyproj")
        tf = Transformer.from_crs("EPSG:4326", args.crs.upper(), always_xy=True)
        x0, y0 = tf.transform(lon1, lat1)
        x1, y1 = tf.transform(lon2, lat2)
        # 平移到以西南角为原点，避免大坐标值导致 Revit 精度问题
        xs = np.linspace(0.0, x1 - x0, args.grid_x)
        ys = np.linspace(0.0, y1 - y0, args.grid_y)
        print(f"[INFO] CRS {args.crs.upper()} 范围 {x1 - x0:.0f} x {y1 - y0:.0f} m"
              f"（已平移至原点，原始东经/北纬 {x0:.0f}/{y0:.0f}）")
    else:
        xs = np.linspace(0.0, span_x, args.grid_x)
        ys = np.linspace(0.0, span_y, args.grid_y)
        print(f"[INFO] 局部平面坐标（原点=西南角 {lon1},{lat1}）"
              f"范围 {span_x:.0f} x {span_y:.0f} m")

    z = foam.resample(z, args.grid_x, args.grid_y)

    # --- 垂直
    ve = args.ve if args.ve is not None else (1.0 if args.target == "terrain" else 2.5)
    if args.target == "sandbox":
        scale = 1200.0 * 0.001 / span_x          # 沙盘 1200mm 对应的真实跨度
        xs = xs * scale
        ys = ys * scale
        print(f"[INFO] 沙盘模式：水平比尺 1:{1/scale:.0f}，垂直夸张 VE={ve}")
    z_out = zmin + (z - zmin) * ve

    os.makedirs(args.out, exist_ok=True)

    # --- 1. Revit 点文件（主路径）
    csv_path = os.path.join(args.out, "terrain_points.csv")
    with open(csv_path, "w", encoding="utf-8") as f:
        for i in range(args.grid_y):
            for j in range(args.grid_x):
                f.write(f"{xs[j]:.3f},{ys[i]:.3f},{float(z_out[i, j]):.3f}\n")
    print(f"[OK] Revit 点文件 -> {csv_path}  "
          f"（{args.grid_x * args.grid_y} 点，X,Y,Z / 米）")

    # --- 2. IFC
    if not args.no_ifc:
        ifc_path = os.path.join(args.out, "terrain.ifc")
        npts, nfaces = write_ifc(ifc_path, xs, ys, z_out,
                                 name="GL-Sandbox Terrain" if args.target == "terrain"
                                 else "GL-Sandbox Model")
        print(f"[OK] IFC4 -> {ifc_path}  （{npts} 点 / {nfaces} 面）")

    # --- 3. OBJ
    if not args.no_obj:
        obj_path = os.path.join(args.out, "terrain.obj")
        write_obj(obj_path, xs, ys, z_out)
        print(f"[OK] OBJ -> {obj_path}")

    # --- 4. 隐患点 / 设备布点（坐标直接从 register_devices.sql 解析，避免二次维护）
    if args.sensors:
        sql_path = args.sensors if args.sensors != "auto" else \
            os.path.join(HERE, "register_devices.sql")
        if not os.path.exists(sql_path):
            print(f"[WARN] 未找到 {sql_path}，跳过布点")
        else:
            hazards, devices = parse_registry(sql_path)
            dlon = (lon2 - lon1) or 1e-9
            dlat = (lat2 - lat1) or 1e-9

            def xy_of(lon, lat):
                u = (lon - lon1) / dlon
                v = (lat - lat1) / dlat
                return u * float(xs[-1]), v * float(ys[-1]), u, v

            hz_rows = []
            for code, name, htype, lon, lat in hazards:
                X, Y, u, v = xy_of(lon, lat)
                hz_rows.append([code, name, htype,
                                f"{X:.3f}", f"{Y:.3f}", f"{sample_z(z_out, u, v):.3f}"])
            n1 = write_points_csv(os.path.join(args.out, "hazard_points.csv"),
                                  "hazard_code,name,hazard_type,X,Y,Z_m", hz_rows)

            dv_rows = []
            for code, name, dtype, hcode, lon, lat in devices:
                X, Y, u, v = xy_of(lon, lat)
                dv_rows.append([code, name, dtype, hcode,
                                f"{X:.3f}", f"{Y:.3f}", f"{sample_z(z_out, u, v):.3f}"])
            n2 = write_points_csv(os.path.join(args.out, "device_points.csv"),
                                  "device_code,name,device_type,hazard_code,X,Y,Z_m", dv_rows)
            print(f"[OK] 布点 CSV -> hazard_points.csv（{n1}）/ device_points.csv（{n2}）"
                  f"，坐标系与 terrain_points.csv 一致")

    print("\n[Revit 导入步骤]")
    print("  1) 新建项目 →「体量和场地」选项卡 →「地形表面」→「通过导入创建」→「指定点文件」")
    print(f"  2) 选择 {os.path.join(args.out, 'terrain_points.csv')}，单位选「米」")
    print("  3) 或用「打开」→ 文件类型 IFC → 选中 terrain.ifc → 另存为 .rvt")
    if args.target == "sandbox":
        print("  4) 当前为沙盘实体模式，尺寸单位为米（沙盘 1.2 x 0.8），可直接布置泡沫层与构件")
    else:
        print("  4) 当前为真实地形模式（1:1），单位为米；如需与 GIS 对齐请加 --crs epsg:4545")


if __name__ == "__main__":
    main()
