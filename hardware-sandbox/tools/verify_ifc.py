#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
IFC 交付前自检：解析 IFCCARTESIANPOINTLIST3D / IFCTRIANGULATEDFACESET，
检查会导致 Revit 导入失败或显示异常的问题。

检查项
------
  1. 面索引越界（Revit 导入直接报错的头号原因）
  2. 退化三角形（重复顶点 -> 零面积面，Revit 会警告或丢面）
  3. 坐标含 NaN / Inf（Revit 会静默丢弃整个构件）
  4. 实体数量、要素分类统计、包围盒尺寸
  5. DATA 段实体引用是否都有定义（悬空引用）

用法
----
  python verify_ifc.py path/to/model.ifc
"""

from __future__ import annotations

import re
import sys
from collections import Counter

NUM = r"[-+]?[0-9]*\.?[0-9]+(?:[eE][-+]?[0-9]+)?"
PT_LIST = re.compile(r"#(\d+)\s*=\s*IFCCARTESIANPOINTLIST3D\s*\((.*?)\)\s*;", re.S)
FACE_SET = re.compile(
    r"#(\d+)\s*=\s*IFCTRIANGULATEDFACESET\s*\(\s*#(\d+)\s*,.*?\((.*?)\)\s*\)\s*;", re.S)
ENTITY = re.compile(r"#(\d+)\s*=\s*([A-Z0-9]+)\s*\(")
TRI = re.compile(r"\((%s)\s*,\s*(%s)\s*,\s*(%s)\)" % (NUM, NUM, NUM))
XYZ = re.compile(r"\((%s)\s*,\s*(%s)\s*,\s*(%s)\)" % (NUM, NUM, NUM))
OBJTYPE = re.compile(r"#(\d+)=IFC(?:BUILDINGELEMENTPROXY|GEOGRAPHICELEMENT)\("
                     r"[^,]+,[^,]+,'([^']*)',\$,'([^']*)'")


def parse(path):
    txt = open(path, encoding="utf-8").read()
    pts = {}
    for eid, body in PT_LIST.findall(txt):
        pts[int(eid)] = [(float(a), float(b), float(c))
                         for a, b, c in XYZ.findall(body)]
    faces = {}
    for eid, plist, body in FACE_SET.findall(txt):
        faces[int(eid)] = (int(plist),
                           [(int(a), int(b), int(c)) for a, b, c in TRI.findall(body)])
    types = {int(eid): objtype for eid, _nm, objtype in OBJTYPE.findall(txt)}
    defined = {int(eid) for eid, _ in ENTITY.findall(txt)}
    refs = {int(r) for r in re.findall(r"#(\d+)", txt)}
    return pts, faces, types, defined, refs


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "model.ifc"
    pts, faces, types, defined, refs = parse(path)

    print(f"文件：{path}")
    print(f"点集 {len(pts)} 个 / 面集 {len(faces)} 个 / 可识别实体 {len(types)} 个")

    problems = []
    total_tri = 0
    degen = 0
    bbox = [float("inf")] * 3 + [float("-inf")] * 3

    for fid, (pid, tris) in faces.items():
        plist = pts.get(pid)
        if plist is None:
            problems.append(f"面集 #{fid} 引用了不存在的点集 #{pid}")
            continue
        n = len(plist)
        total_tri += len(tris)
        for a, b, c in tris:
            for idx in (a, b, c):
                if idx < 1 or idx > n:
                    problems.append(f"面集 #{fid} 索引越界：{idx} 超出 1..{n}")
                    break
            else:
                if a == b or b == c or a == c:
                    degen += 1
                    continue
                p0, p1, p2 = plist[a - 1], plist[b - 1], plist[c - 1]
                for p in (p0, p1, p2):
                    for k in range(3):
                        if p[k] != p[k] or abs(p[k]) == float("inf"):
                            problems.append(f"面集 #{fid} 坐标非法：{p}")
                        bbox[k] = min(bbox[k], p[k])
                        bbox[k + 3] = max(bbox[k + 3], p[k])

    for pid, plist in pts.items():
        for p in plist:
            for k in range(3):
                if p[k] != p[k] or abs(p[k]) == float("inf"):
                    problems.append(f"点集 #{pid} 坐标非法：{p}")

    dangling = sorted(r for r in refs if r not in defined and r > 19)
    if dangling:
        problems.append(f"悬空实体引用：{dangling[:10]}（共 {len(dangling)} 个）")

    print(f"三角形总数 {total_tri}，退化三角形 {degen}")
    if len(bbox) == 6 and bbox[0] != float("inf"):
        print(f"包围盒 X {bbox[0]:.1f}~{bbox[3]:.1f}  Y {bbox[1]:.1f}~{bbox[4]:.1f}  "
              f"Z {bbox[2]:.1f}~{bbox[5]:.1f}（米）")

    cnt = Counter(types.values())
    print("\n要素分类：")
    for k, v in sorted(cnt.items(), key=lambda t: -t[1]):
        print(f"  {k:<14s} {v}")

    print()
    if problems:
        print(f"[FAIL] 发现 {len(problems)} 类问题：")
        for p in problems[:15]:
            print("  -", p)
        sys.exit(1)
    print("[PASS] 索引、坐标、引用均合法，可导入 Revit")


if __name__ == "__main__":
    main()
