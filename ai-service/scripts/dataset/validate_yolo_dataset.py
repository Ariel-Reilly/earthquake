#!/usr/bin/env python3
"""YOLO 数据集完整性与一致性校验。

检查项（任何一项失败都会以非零退出码结束，便于接 CI）：
  1. images/{split} 与 labels/{split} 一一配对，不允许孤儿图像/孤儿标签
  2. 每个标签行必须是 5 列：class cx cy w h，且坐标在 [0,1] 内
  3. class id 必须在 dataset.yaml 的 names 范围内
  4. 图像可被真实解码，且宽高 > 0
  5. 跨 split 内容重复（同一张图同时进 train 和 val/test → 数据泄漏）
  6. 输出类别分布、框面积分布、空标签比例

用法：
    python validate_yolo_dataset.py --data ../../datasets/disaster-v1
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

SPLITS = ("train", "val", "test")
IMG_EXTS = {".jpg", ".jpeg", ".png", ".bmp", ".tif", ".tiff", ".webp"}


def read_names(data_root: Path) -> list[str]:
    """从 dataset.yaml 里读出 names 列表（避免引入 yaml 依赖的简易解析）。"""
    yaml_path = data_root / "dataset.yaml"
    names: dict[int, str] = {}
    in_names = False
    for line in yaml_path.read_text(encoding="utf-8").splitlines():
        s = line.rstrip()
        if not s or s.lstrip().startswith("#"):
            continue
        if s.startswith("names:"):
            in_names = True
            continue
        if in_names:
            if not s.startswith((" ", "\t")):
                break
            k, _, v = s.strip().partition(":")
            if k.strip().isdigit():
                names[int(k)] = v.strip()
    return [names[i] for i in sorted(names)]


def file_md5(p: Path, chunk: int = 1 << 20) -> str:
    h = hashlib.md5()
    with p.open("rb") as f:
        while True:
            b = f.read(chunk)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True)
    ap.add_argument("--samples", default="", help="抽样可视化输出目录（可选）")
    args = ap.parse_args()

    root = Path(args.data).resolve()
    names = read_names(root)
    errors: list[str] = []
    warnings: list[str] = []

    cls_counter: Counter = Counter()
    split_stats: dict[str, dict] = {}
    hashes: dict[str, list[str]] = defaultdict(list)
    box_areas: list[float] = []
    box_sides: list[float] = []

    for split in SPLITS:
        img_dir = root / "images" / split
        lbl_dir = root / "labels" / split
        if not img_dir.exists():
            errors.append(f"缺少目录 {img_dir}")
            continue
        images = {p.stem: p for p in img_dir.iterdir() if p.suffix.lower() in IMG_EXTS}
        labels = {p.stem: p for p in lbl_dir.glob("*.txt")} if lbl_dir.exists() else {}

        orphan_img = sorted(set(images) - set(labels))
        orphan_lbl = sorted(set(labels) - set(images))
        if orphan_img:
            errors.append(f"[{split}] {len(orphan_img)} 张图缺标签，例：{orphan_img[:3]}")
        if orphan_lbl:
            errors.append(f"[{split}] {len(orphan_lbl)} 个标签缺图，例：{orphan_lbl[:3]}")

        empty = 0
        inst = 0
        bad_lines = 0
        bad_class = 0
        bad_coord = 0
        undecodable = 0
        sizes: Counter = Counter()

        for stem, img_path in images.items():
            try:
                from PIL import Image
                with Image.open(img_path) as im:
                    w, h = im.size
                    im.load()
                if w <= 0 or h <= 0:
                    undecodable += 1
                    continue
            except Exception:
                undecodable += 1
                continue
            hashes[file_md5(img_path)].append(f"{split}/{img_path.name}")

            lbl = labels.get(stem)
            if lbl is None:
                continue
            rows = [r for r in lbl.read_text(encoding="utf-8").splitlines() if r.strip()]
            if not rows:
                empty += 1
                continue
            for r in rows:
                parts = r.split()
                if len(parts) != 5:
                    bad_lines += 1
                    continue
                try:
                    cid = int(float(parts[0]))
                    cx, cy, bw, bh = (float(v) for v in parts[1:])
                except ValueError:
                    bad_lines += 1
                    continue
                if not (0 <= cid < len(names)):
                    bad_class += 1
                    continue
                if not all(0.0 <= v <= 1.0 for v in (cx, cy, bw, bh)):
                    bad_coord += 1
                    continue
                if bw <= 0 or bh <= 0:
                    bad_coord += 1
                    continue
                cls_counter[names[cid]] += 1
                inst += 1
                box_areas.append(bw * bh)
                box_sides.append(min(bw, bh))

        split_stats[split] = {
            "images": len(images), "labels": len(labels),
            "instances": inst, "empty_label_images": empty,
            "orphan_images": len(orphan_img), "orphan_labels": len(orphan_lbl),
            "undecodable_images": undecodable,
            "bad_lines": bad_lines, "bad_class_id": bad_class, "bad_coordinates": bad_coord,
        }
        if undecodable:
            errors.append(f"[{split}] {undecodable} 张图无法解码")
        if bad_lines:
            errors.append(f"[{split}] {bad_lines} 行标签格式错误（非 5 列/非数值）")
        if bad_class:
            errors.append(f"[{split}] {bad_class} 个 class id 越界")
        if bad_coord:
            errors.append(f"[{split}] {bad_coord} 个坐标越界或宽高非正")

    # 跨 split 泄漏
    for h, where in hashes.items():
        splits_involved = {w.split("/")[0] for w in where}
        if len(splits_involved) > 1:
            warnings.append(f"内容重复且跨 split：{sorted(where)}")
    dup_within = sum(1 for where in hashes.values() if len(where) > 1)

    total_inst = sum(cls_counter.values())
    report = {
        "dataset_root": root.as_posix(),
        "class_names": names,
        "total_images": sum(s["images"] for s in split_stats.values()),
        "total_instances": total_inst,
        "class_distribution": dict(cls_counter),
        "split_stats": split_stats,
        "duplicate_content_groups": dup_within,
        "cross_split_leaks": sum(1 for w in hashes.values() if len({x.split("/")[0] for x in w}) > 1),
        "box_area_norm_p50": round(sorted(box_areas)[len(box_areas) // 2], 6) if box_areas else None,
        "box_min_side_norm_p50": round(sorted(box_sides)[len(box_sides) // 2], 6) if box_sides else None,
        "tiny_boxes_lt_0_01_side": sum(1 for s in box_sides if s < 0.01),
        "errors": errors,
        "warnings": warnings[:20],
        "passed": not errors,
    }
    (root / "validation.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    print(json.dumps({k: report[k] for k in (
        "total_images", "total_instances", "class_distribution",
        "cross_split_leaks", "tiny_boxes_lt_0_01_side", "passed")},
        ensure_ascii=False, indent=2))
    for e in errors:
        print("  ERROR:", e, file=sys.stderr)
    for w in warnings[:5]:
        print("  WARN:", w, file=sys.stderr)

    if args.samples:
        render_samples(root, names, Path(args.samples))

    sys.exit(0 if not errors else 1)


def render_samples(root: Path, names: list[str], out_dir: Path, per_class: int = 4) -> None:
    """把样本按标签画框导出，用于人工目检标注是否正确。"""
    import cv2

    out_dir.mkdir(parents=True, exist_ok=True)
    picked: Counter = Counter()
    colors = [(60, 120, 220), (70, 160, 90), (200, 120, 60), (150, 80, 200), (40, 180, 200)]

    for split in ("train", "val", "test"):
        for lbl in sorted((root / "labels" / split).glob("*.txt")):
            rows = [r.split() for r in lbl.read_text(encoding="utf-8").splitlines() if r.strip()]
            if not rows:
                continue
            cids = {int(float(r[0])) for r in rows if len(r) == 5}
            if not cids:
                continue
            cid = sorted(cids)[0]
            cname = names[cid] if cid < len(names) else str(cid)
            if picked[cname] >= per_class:
                continue
            img_path = None
            for ext in IMG_EXTS:
                cand = root / "images" / split / f"{lbl.stem}{ext}"
                if cand.exists():
                    img_path = cand
                    break
            if img_path is None:
                continue
            img = cv2.imread(str(img_path), cv2.IMREAD_COLOR)
            if img is None:
                continue
            h, w = img.shape[:2]
            for r in rows:
                if len(r) != 5:
                    continue
                c = int(float(r[0]))
                cx, cy, bw, bh = (float(v) for v in r[1:])
                x1, y1 = int((cx - bw / 2) * w), int((cy - bh / 2) * h)
                x2, y2 = int((cx + bw / 2) * w), int((cy + bh / 2) * h)
                cv2.rectangle(img, (x1, y1), (x2, y2), colors[c % len(colors)], 2)
                cv2.putText(img, names[c] if c < len(names) else str(c), (x1, max(12, y1 - 4)),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.45, colors[c % len(colors)], 1, cv2.LINE_AA)
            picked[cname] += 1
            cv2.imwrite(str(out_dir / f"{cname}_{split}_{picked[cname]}.jpg"), img)

    print(f"抽样可视化已输出到 {out_dir}", file=sys.stderr)


if __name__ == "__main__":
    main()
