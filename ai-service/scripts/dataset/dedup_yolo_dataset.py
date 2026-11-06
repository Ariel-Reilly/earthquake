#!/usr/bin/env python3
"""按内容哈希去除 YOLO 数据集里的重复图像，防止跨 split 数据泄漏。

保留优先级：train > val > test（同内容保留优先级最高的那一份）。
删除范围：图像文件 + 对应标签文件。默认只报告不删除，加 --apply 才真正删除。

用法：
    python dedup_yolo_dataset.py --data ../../datasets/disaster-v1            # 预演
    python dedup_yolo_dataset.py --data ../../datasets/disaster-v1 --apply    # 执行
"""
from __future__ import annotations

import argparse
import hashlib
import json
from collections import defaultdict
from pathlib import Path

SPLITS_PRIORITY = ("train", "val", "test")
IMG_EXTS = {".jpg", ".jpeg", ".png", ".bmp", ".tif", ".tiff", ".webp"}


def md5(p: Path, chunk: int = 1 << 20) -> str:
    h = hashlib.md5()
    with p.open("rb") as f:
        for b in iter(lambda: f.read(chunk), b""):
            h.update(b)
    return h.hexdigest()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True)
    ap.add_argument("--apply", action="store_true", help="真正删除重复文件")
    args = ap.parse_args()

    root = Path(args.data).resolve()
    groups: dict[str, list[tuple[int, str, Path]]] = defaultdict(list)
    for rank, split in enumerate(SPLITS_PRIORITY):
        img_dir = root / "images" / split
        if not img_dir.exists():
            continue
        for p in img_dir.iterdir():
            if p.suffix.lower() in IMG_EXTS:
                groups[md5(p)].append((rank, split, p))

    removed: list[dict] = []
    for h, items in groups.items():
        if len(items) < 2:
            continue
        items.sort(key=lambda x: x[0])
        keep = items[0]
        for _rank, split, p in items[1:]:
            lbl = root / "labels" / split / f"{p.stem}.txt"
            removed.append({
                "kept": f"{keep[1]}/{keep[2].name}",
                "removed": f"{split}/{p.name}",
                "label_removed": lbl.exists(),
            })
            if args.apply:
                p.unlink(missing_ok=True)
                lbl.unlink(missing_ok=True)

    cross = sum(1 for r in removed if r["kept"].split("/")[0] != r["removed"].split("/")[0])
    out = {
        "dataset_root": root.as_posix(),
        "mode": "apply" if args.apply else "dry-run",
        "duplicate_files_removed": len(removed),
        "cross_split_leaks_removed": cross,
        "details": removed,
    }
    (root / ("dedup_applied.json" if args.apply else "dedup_plan.json")).write_text(
        json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps({k: out[k] for k in
                      ("mode", "duplicate_files_removed", "cross_split_leaks_removed")},
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
