#!/usr/bin/env python3
"""把公开**人工标注**数据集转换为 YOLO 检测格式（COCO / 掩码 两类源）。

设计原则（很重要，别改成"看起来能跑"）：
  1. 本脚本**不生成任何图像，也不生成任何标注**。它只做确定性格式转换：
     - COCO 的 bbox → YOLO 归一化 cx/cy/w/h（像素级线性映射，无插值）
     - 人工掩码的连通域 → 外接矩形 bbox（可复现的几何运算）
     标注内容 100% 来自原始数据集的人工标注，不含随机数、不含模型预测。
  2. 任何被丢弃的标注都必须计数并写进 stats，不允许静默过滤。
  3. 划分沿用原始数据集的 train/valid/test，不重新洗牌，保证可与论文结果对齐。

用法：
    python build_yolo_dataset.py --config configs/disaster_v1.json --out ../../datasets/disaster-v1
"""
from __future__ import annotations

import argparse
import json
import shutil
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

# 像素面积小于该值的框视为无效标注（标注抖动/误点的常见形态）
MIN_BOX_AREA_PX = 4.0
# 归一化后宽或高小于该值的框，YOLO 训练无意义
MIN_NORM_SIDE = 1e-4


@dataclass
class Source:
    """一路数据源。

    kind="coco"：一个 COCO json + 一个图片目录。
    kind="mask"：一个图片目录 + 一个掩码目录（掩码非零即前景），
                 由连通域导出实例级 bbox。
    """

    name: str
    split: str                     # train / val / test
    kind: str = "coco"
    class_map: dict[str, str] = field(default_factory=dict)
    license: str = "unknown"
    homepage: str = ""
    prefix: str = ""               # 输出文件名前缀，避免不同源重名

    # kind=coco
    images_dir: Path | None = None
    coco_json: Path | None = None

    # kind=mask
    masks_dir: Path | None = None
    mask_target: str = ""          # 掩码源的唯一目标类别
    min_component_area: int = 20   # 连通域最小面积（像素），小于此视为噪点
    mask_name_template: str = "{stem}.png"  # 掩码文件名模板，{stem} 为影像名
    strip_prefix: str = ""         # 影像 stem 需剥掉的前缀（掩码命名未带该前缀时用）
    # 语义分割掩码转检测框时，同一条滑坡常被切成若干碎片连通域。
    # 先用闭运算（kernel 边长，0=不处理）把邻近碎片粘回一体，再取外接矩形，
    # 否则一张 512×512 图会产出几十个 16px 的框，检测任务退化为小目标碎片学习。
    mask_close_kernel: int = 0
    # 旋转裁切产生的纯色填充（黑/白三角）会污染训练；开启后裁到有效内容区域。
    # 仅当填充面积 >= trim_min_ratio 时才裁，避免为几个像素反复裁剪损失边缘。
    trim_fill_border: bool = False
    trim_min_ratio: float = 0.02
    # 裁到最大内接矩形后，剩余面积占比低于该值就整张丢弃（有效内容太少）
    min_keep_ratio: float = 0.25
    # 上游未提供可靠划分（或划分存在泄漏）时，用确定性重划分：
    # {"train": 0.8, "val": 0.1, "test": 0.1}，按文件名排序切分，可复现
    auto_split: dict[str, float] | None = None


@dataclass
class BuildStats:
    per_source: list[dict[str, Any]] = field(default_factory=list)
    dropped: Counter = field(default_factory=Counter)
    class_instances: Counter = field(default_factory=Counter)
    images_per_split: Counter = field(default_factory=Counter)
    missing_images: list[str] = field(default_factory=list)
    clipped_boxes: int = 0
    # 原始类别 → 目标类别的映射计数，用于审计类别合并是否合理
    class_mapping: Counter = field(default_factory=Counter)
    empty_label_images: Counter = field(default_factory=Counter)


def polygon_to_bbox(seg: Any) -> list[float] | None:
    """COCO segmentation → 外接矩形 [x, y, w, h]（像素）。"""
    if not isinstance(seg, list) or not seg:
        return None
    xs: list[float] = []
    ys: list[float] = []
    for poly in seg:
        if not isinstance(poly, list) or len(poly) < 6:
            continue
        xs.extend(poly[0::2])
        ys.extend(poly[1::2])
    if not xs or not ys:
        return None
    x0, x1 = min(xs), max(xs)
    y0, y1 = min(ys), max(ys)
    if x1 <= x0 or y1 <= y0:
        return None
    return [x0, y0, x1 - x0, y1 - y0]


def rle_to_bbox(rle: dict) -> list[float] | None:
    """COCO RLE 掩码 → 外接矩形。不支持压缩 RLE（返回 None 并计数）。"""
    try:
        counts = rle["counts"]
        h, w = rle["size"]
        if isinstance(counts, str):
            return None
        mask = []
        val = 0
        for c in counts:
            mask.extend([val] * int(c))
            val ^= 1
        if len(mask) < h * w:
            mask.extend([0] * (h * w - len(mask)))
        idx = [i for i, v in enumerate(mask[: h * w]) if v]
        if not idx:
            return None
        xs = [i % w for i in idx]
        ys = [i // w for i in idx]
        return [float(min(xs)), float(min(ys)), float(max(xs) + 1 - min(xs)), float(max(ys) + 1 - min(ys))]
    except Exception:
        return None


def ann_to_pixel_bbox(ann: dict) -> tuple[list[float] | None, str]:
    """取标注的像素 bbox，优先用原始 bbox，缺失则从掩码导出。"""
    bbox = ann.get("bbox")
    if isinstance(bbox, (list, tuple)) and len(bbox) == 4:
        x, y, w, h = (float(v) for v in bbox)
        if w > 0 and h > 0:
            return [x, y, w, h], "coco_bbox"
    seg = ann.get("segmentation")
    if isinstance(seg, list):
        b = polygon_to_bbox(seg)
        if b:
            return b, "polygon_extent"
    elif isinstance(seg, dict):
        b = rle_to_bbox(seg)
        if b:
            return b, "rle_extent"
    return None, "none"


def file_md5(p: Path, chunk: int = 1 << 20) -> str:
    """文件内容哈希，用于识别重复影像（不做任何图像处理）。"""
    import hashlib

    h = hashlib.md5()
    with p.open("rb") as f:
        for b in iter(lambda: f.read(chunk), b""):
            h.update(b)
    return h.hexdigest()


def clip_box(b: list[float], w: int, h: int) -> tuple[list[float], bool]:
    x, y, bw, bh = b
    x0, y0 = max(0.0, x), max(0.0, y)
    x1, y1 = min(float(w), x + bw), min(float(h), y + bh)
    clipped = (x0 != x) or (y0 != y) or (x1 != x + bw) or (y1 != y + bh)
    return [x0, y0, max(0.0, x1 - x0), max(0.0, y1 - y0)], clipped


def to_yolo_line(px_bbox: list[float], width: int, height: int) -> str | None:
    """像素框 → YOLO 归一化行。超出图像的部分先裁剪。"""
    cb, _ = clip_box(px_bbox, width, height)
    if cb[2] * cb[3] < MIN_BOX_AREA_PX:
        return None
    cx = (cb[0] + cb[2] / 2.0) / width
    cy = (cb[1] + cb[3] / 2.0) / height
    nw = cb[2] / width
    nh = cb[3] / height
    if nw < MIN_NORM_SIDE or nh < MIN_NORM_SIDE:
        return None
    if not (0.0 <= cx <= 1.0 and 0.0 <= cy <= 1.0):
        return None
    return f"{cx:.6f} {cy:.6f} {nw:.6f} {nh:.6f}"


# ---------------------------------------------------------------- mask 源


def components_to_boxes(
    mask_path: Path, min_area: int, close_kernel: int = 0
) -> tuple[list[list[float]], int, int, int]:
    """二值掩码文件 → 连通域外接矩形列表（保留文件入口，内部转数组处理）。"""
    import cv2

    raw = cv2.imread(str(mask_path), cv2.IMREAD_UNCHANGED)
    if raw is None:
        return [], 0, 0, 0
    if raw.ndim == 3:
        raw = raw[:, :, 0]
    return components_to_boxes_arr(raw, min_area, close_kernel)


def components_to_boxes_arr(
    raw, min_area: int, close_kernel: int = 0
) -> tuple[list[list[float]], int, int, int]:
    """二值掩码数组 → 连通域外接矩形列表。

    返回 (boxes, 闭运算前组件数, 闭运算后组件数, 被面积阈值丢弃的组件数)。
    close_kernel > 0 时先做形态学闭运算，把属于同一滑坡的碎片掩码粘连成一个实例；
    这一步只做几何合并，不新增也不虚构任何标注区域。
    """
    import cv2
    import numpy as np

    binm = (raw > 0).astype(np.uint8)
    if binm.sum() == 0:
        return [], 0, 0, 0

    n_before, _l0, _s0, _c0 = cv2.connectedComponentsWithStats(binm, connectivity=8)
    n_before -= 1

    if close_kernel > 1:
        kernel = np.ones((close_kernel, close_kernel), np.uint8)
        binm = cv2.morphologyEx(binm, cv2.MORPH_CLOSE, kernel)

    n, _lab, stats, _cent = cv2.connectedComponentsWithStats(binm, connectivity=8)
    n_after = n - 1
    boxes: list[list[float]] = []
    dropped = 0
    for x, y, w, h, area in stats[1:]:
        if int(area) < min_area:
            dropped += 1
            continue
        boxes.append([float(x), float(y), float(w), float(h)])
    return boxes, n_before, n_after, dropped


def fill_border_mask(arr) -> tuple[tuple[int, int, int, int] | None, "object"]:
    """识别旋转裁切留下的纯色填充。

    返回 (有效内容外接矩形, 填充掩码)。

    判定规则（不是简单看四角，踩过坑）：纯黑（各通道 ≤2）或纯白（各通道 ≥253）的
    连通区域，同时满足「面积 ≥ 全图 0.5%」且「外接矩形触碰图像边界」，才算填充。
    只从四角 floodFill 的做法在旋转角度不巧时四角正好落在真实地物上，会漏掉整片白三角。
    真实地物里的大片白色（河床、屋顶）极少是纯 255 且贴着边界成片，因此误判概率低。
    """
    import cv2
    import numpy as np

    h, w = arr.shape[:2]
    total = h * w
    invalid = np.zeros((h, w), np.uint8)
    min_area = max(64, int(total * 0.005))

    black = (arr.max(axis=2) <= 2).astype(np.uint8)
    white = (arr.min(axis=2) >= 253).astype(np.uint8)
    for m in (black, white):
        if m.sum() == 0:
            continue
        n, lab, st, _ct = cv2.connectedComponentsWithStats(m, connectivity=4)
        for i in range(1, n):
            x, y, bw, bh, area = st[i]
            if area < min_area:
                continue
            touches = x <= 0 or y <= 0 or (x + bw) >= w or (y + bh) >= h
            if touches:
                invalid[lab == i] = 1

    valid = (invalid == 0)
    if not valid.any():
        return None, invalid
    ys, xs = np.where(valid)
    return (int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1), invalid


def largest_inscribed_rect(valid) -> tuple[int, int, int, int]:
    """在二值有效区内找面积最大的**轴对齐**矩形，返回 (x0, y0, x1, y1)。

    为什么不用 inpaint：CAS 的旋转切片填充可占 46% 面积，TELEA 修复会把整片白三角
    涂成条状涂抹痕（实测肉眼可见），等于给模型喂了一个新的伪特征，比纯白更糟。
    裁到最大内接矩形不生成任何像素、不改动标签语义，只是少看一点边缘内容。

    算法：按行累加柱状高度，再用单调栈求每根柱子的最大左右扩展（O(h·w)）。
    """
    import numpy as np

    h, w = valid.shape[:2]
    heights = np.zeros(w, dtype=np.int32)
    best_area = 0
    best = (0, 0, w, h)
    for y in range(h):
        heights = np.where(valid[y], heights + 1, 0)

        left = np.zeros(w, dtype=np.int32)
        stack: list[int] = []
        for x in range(w):
            while stack and heights[stack[-1]] >= heights[x]:
                stack.pop()
            left[x] = stack[-1] + 1 if stack else 0
            stack.append(x)

        right = np.zeros(w, dtype=np.int32)
        stack = []
        for x in range(w - 1, -1, -1):
            while stack and heights[stack[-1]] >= heights[x]:
                stack.pop()
            right[x] = stack[-1] - 1 if stack else w - 1
            stack.append(x)

        for x in range(w):
            hh = int(heights[x])
            if hh == 0:
                continue
            area = hh * int(right[x] - left[x] + 1)
            if area > best_area:
                best_area = area
                best = (int(left[x]), y + 1 - hh, int(right[x]) + 1, y + 1)
    return best


def fill_border_bbox(arr) -> tuple[int, int, int, int] | None:
    """兼容旧调用：只要有效区域 bbox。"""
    bbox, _mask = fill_border_mask(arr)
    return bbox


IMG_EXTS = {".jpg", ".jpeg", ".png", ".bmp", ".tif", ".tiff", ".webp"}
SPLIT_PRIORITY = {"train": 0, "val": 1, "test": 2}


def dedup_cross_split(root: Path, stats: BuildStats, class_names: list[str]) -> int:
    """构建收尾的全局去重：同一内容若出现在多个 split，保留优先级最高的那份。

    这是最后一道保险（数据源自带的划分也可能有泄漏）。被删除的实例数会从
    stats 里同步扣减，保证 stats.json 与磁盘真实一致。
    """
    groups: dict[str, list[tuple[int, str, Path]]] = defaultdict(list)
    for split in ("train", "val", "test"):
        d = root / "images" / split
        if not d.exists():
            continue
        for p in d.iterdir():
            if p.suffix.lower() in IMG_EXTS:
                groups[file_md5(p)].append((SPLIT_PRIORITY[split], split, p))

    removed = 0
    for items in groups.values():
        if len(items) < 2:
            continue
        items.sort(key=lambda x: x[0])
        for _pri, split, p in items[1:]:
            lbl = root / "labels" / split / f"{p.stem}.txt"
            if lbl.exists():
                for row in lbl.read_text(encoding="utf-8").splitlines():
                    parts = row.split()
                    if len(parts) == 5:
                        try:
                            cid = int(float(parts[0]))
                        except ValueError:
                            continue
                        if 0 <= cid < len(class_names):
                            stats.class_instances[class_names[cid]] -= 1
                lbl.unlink(missing_ok=True)
            p.unlink(missing_ok=True)
            stats.images_per_split[split] -= 1
            removed += 1
    return removed


def build_mask_source(src: Source, out_root: Path, stats: BuildStats, cls_index: dict[str, int]) -> dict:
    if src.mask_target not in cls_index:
        raise SystemExit(f"mask 源 {src.name} 的目标类别 {src.mask_target} 不在 class_names 中")
    cls_id = cls_index[src.mask_target]

    exts = {".tif", ".tiff", ".png", ".jpg", ".jpeg", ".bmp"}
    images = sorted(p for p in src.images_dir.iterdir() if p.suffix.lower() in exts)

    # 掩码索引按小写文件名建表：上游数据集的影像与掩码扩展名大小写常不一致
    # （CAS 数据集影像为 .tif、掩码为 .TIF），逐扩展名试错的写法在大小写敏感的文件系统上会漏配。
    mask_index: dict[str, Path] = {}
    if src.masks_dir and src.masks_dir.exists():
        for p in src.masks_dir.iterdir():
            if p.is_file():
                mask_index[p.name.lower()] = p

    # 源内按内容去重：同一张切片在一个数据集里重复出现会把验证指标抬高
    seen: dict[str, Path] = {}
    unique: list[Path] = []
    duplicates_inside = 0
    for p in images:
        h = file_md5(p)
        if h in seen:
            duplicates_inside += 1
            continue
        seen[h] = p
        unique.append(p)

    # 划分：auto_split 用确定性重划分，否则沿用源给的 split
    assignment: dict[Path, str] = {}
    if src.auto_split:
        n = len(unique)
        n_train = int(round(n * src.auto_split.get("train", 0.8)))
        n_val = int(round(n * src.auto_split.get("val", 0.1)))
        for i, p in enumerate(unique):
            if i < n_train:
                assignment[p] = "train"
            elif i < n_train + n_val:
                assignment[p] = "val"
            else:
                assignment[p] = "test"
    else:
        assignment = {p: src.split for p in unique}

    kept_img = kept_inst = empty = 0
    merged_components = 0
    trimmed_images = 0
    trimmed_px: list[float] = []
    dropped_local: Counter = Counter()
    per_split_out: Counter = Counter()

    for img_path in unique:
        stem = img_path.stem
        if src.strip_prefix and stem.startswith(src.strip_prefix):
            stem = stem[len(src.strip_prefix):]
        want_name = src.mask_name_template.format(stem=stem).lower()
        mask_path = mask_index.get(want_name)
        if mask_path is None:
            for alt in (".png", ".tif", ".tiff", ".jpg", ".jpeg", ".bmp"):
                mask_path = mask_index.get(f"{stem}{alt}".lower())
                if mask_path is not None:
                    break
        if mask_path is None:
            stats.missing_images.append(f"{src.name}:mask_for_{img_path.name}")
            continue

        target_split = assignment[img_path]
        img_dir_out = out_root / "images" / target_split
        lbl_dir_out = out_root / "labels" / target_split

        try:
            import cv2
            import numpy as np
            from PIL import Image

            with Image.open(img_path) as im:
                rgb = np.array(im.convert("RGB"))
            raw_mask = cv2.imread(str(mask_path), cv2.IMREAD_UNCHANGED)
        except Exception:
            dropped_local["unreadable_image"] += 1
            continue
        if raw_mask is None:
            dropped_local["unreadable_mask"] += 1
            continue
        if raw_mask.ndim == 3:
            raw_mask = raw_mask[:, :, 0]
        if raw_mask.shape[:2] != rgb.shape[:2]:
            dropped_local["image_mask_size_mismatch"] += 1
            continue

        # 旋转裁切留下的纯色填充：不修补，直接裁到最大内接矩形。
        #   < trim_min_ratio      → 填充太少，原图保留
        #   ≥ trim_min_ratio      → 裁掉填充，影像与掩码同步裁（标签在裁剪后的坐标系里算）
        #   剩余面积 < min_keep_ratio → 整张丢弃（有效内容已经不够看了）
        if src.trim_fill_border:
            _bbox, invalid = fill_border_mask(rgb)
            total_px = float(rgb.shape[0] * rgb.shape[1])
            fill_ratio = float(invalid.sum()) / total_px
            if fill_ratio >= src.trim_min_ratio:
                x0, y0, x1, y1 = largest_inscribed_rect(invalid == 0)
                keep_ratio = ((x1 - x0) * (y1 - y0)) / total_px
                if keep_ratio < src.min_keep_ratio:
                    dropped_local["fill_crop_too_small"] += 1
                    continue
                rgb = rgb[y0:y1, x0:x1]
                raw_mask = raw_mask[y0:y1, x0:x1]
                trimmed_images += 1
                trimmed_px.append(fill_ratio)

        width, height = rgb.shape[1], rgb.shape[0]
        Image.fromarray(rgb).save(
            img_dir_out / f"{src.prefix}{stem}.png", format="PNG", optimize=True
        )

        boxes, n_before, n_after, n_dropped = components_to_boxes_arr(
            raw_mask, src.min_component_area, src.mask_close_kernel
        )
        stats.dropped["mask_component_below_min_area"] += n_dropped
        merged_components += max(0, n_before - n_after)

        lines: list[str] = []
        for b in boxes:
            line = to_yolo_line(b, width, height)
            if line is None:
                dropped_local["box_invalid"] += 1
                continue
            lines.append(f"{cls_id} {line}")
            kept_inst += 1

        if not lines:
            empty += 1
        (lbl_dir_out / f"{src.prefix}{stem}.txt").write_text(
            "\n".join(lines) + ("\n" if lines else ""), encoding="utf-8"
        )
        kept_img += 1
        per_split_out[target_split] += 1
        stats.images_per_split[target_split] += 1
        stats.class_instances[src.mask_target] += len(lines)
        stats.class_mapping[f"mask:{src.name} -> {src.mask_target}"] += len(lines)
        if not lines:
            stats.empty_label_images[target_split] += 1

    stats.dropped.update(dropped_local)
    return {
        "source": src.name, "kind": "mask",
        "split": dict(per_split_out) if src.auto_split else src.split,
        "auto_split": src.auto_split,
        "duplicates_dropped_in_source": duplicates_inside,
        "license": src.license, "homepage": src.homepage,
        "images_in": len(images), "images_kept": kept_img,
        "annotations_kept": kept_inst, "empty_label_images": empty,
        "annotation_origin": {"mask_component_extent": kept_inst},
        "mask_close_kernel": src.mask_close_kernel,
        "fragments_merged_by_close": merged_components,
        "trim_fill_border": src.trim_fill_border,
        "images_fill_cropped": trimmed_images,
        "fill_ratio_median_when_cropped": (
            round(float(sorted(trimmed_px)[len(trimmed_px) // 2]), 4) if trimmed_px else 0.0
        ),
        "min_keep_ratio": src.min_keep_ratio,
        "dropped_fill_crop_too_small": dropped_local.get("fill_crop_too_small", 0),
        "min_component_area": src.min_component_area,
        "dropped": dict(dropped_local),
    }


def build_coco_source(src: Source, out_root: Path, stats: BuildStats, cls_index: dict[str, int]) -> dict:
    data = json.loads(src.coco_json.read_text(encoding="utf-8"))
    images = {im["id"]: im for im in data.get("images", [])}
    cats = {c["id"]: c["name"] for c in data.get("categories", [])}
    by_image: dict[int, list[dict]] = defaultdict(list)
    for ann in data.get("annotations", []):
        by_image[ann["image_id"]].append(ann)

    img_dir_out = out_root / "images" / src.split
    lbl_dir_out = out_root / "labels" / src.split
    kept_img = kept_inst = empty = 0
    ann_sources: Counter = Counter()
    raw_classes: Counter = Counter()
    dropped_local: Counter = Counter()

    for img_id, im in images.items():
        file_name = im["file_name"]
        src_path = src.images_dir / file_name
        if not src_path.exists():
            stats.missing_images.append(f"{src.name}:{file_name}")
            continue
        width, height = int(im["width"]), int(im["height"])
        if width <= 0 or height <= 0:
            dropped_local["bad_image_size"] += 1
            continue

        lines: list[str] = []
        for ann in by_image.get(img_id, []):
            raw_name = cats.get(ann["category_id"], f"id_{ann['category_id']}")
            target = src.class_map.get(raw_name)
            if target is None:
                dropped_local[f"unmapped_class:{raw_name}"] += 1
                continue
            if target not in cls_index:
                dropped_local[f"unknown_target:{target}"] += 1
                continue

            px_bbox, how = ann_to_pixel_bbox(ann)
            if px_bbox is None:
                dropped_local["no_bbox"] += 1
                continue
            if px_bbox[2] * px_bbox[3] < MIN_BOX_AREA_PX:
                dropped_local["too_small_px"] += 1
                continue

            _, clipped = clip_box(px_bbox, width, height)
            if clipped:
                stats.clipped_boxes += 1
            line = to_yolo_line(px_bbox, width, height)
            if line is None:
                dropped_local["box_invalid_after_clip"] += 1
                continue

            lines.append(f"{cls_index[target]} {line}")
            ann_sources[how] += 1
            raw_classes[f"{raw_name} -> {target}"] += 1
            stats.class_mapping[f"{raw_name} -> {target}"] += 1
            stats.class_instances[target] += 1
            kept_inst += 1

        stem = Path(file_name).stem
        shutil.copy2(src_path, img_dir_out / f"{src.prefix}{stem}{src_path.suffix.lower()}")
        if not lines:
            empty += 1
        (lbl_dir_out / f"{src.prefix}{stem}.txt").write_text(
            "\n".join(lines) + ("\n" if lines else ""), encoding="utf-8"
        )
        kept_img += 1
        stats.images_per_split[src.split] += 1
        if not lines:
            stats.empty_label_images[src.split] += 1

    stats.dropped.update(dropped_local)
    return {
        "source": src.name, "kind": "coco", "split": src.split,
        "license": src.license, "homepage": src.homepage,
        "images_in": len(images), "images_kept": kept_img,
        "annotations_kept": kept_inst, "empty_label_images": empty,
        "annotation_origin": dict(ann_sources),
        "raw_class_to_target": dict(raw_classes),
        "dropped": dict(dropped_local),
    }


def build(
    sources: list[Source], out_root: Path, class_names: list[str], clean: bool = True
) -> BuildStats:
    stats = BuildStats()
    cls_index = {n: i for i, n in enumerate(class_names)}

    # 幂等：先清掉上一次构建遗留的产物。否则换数据源重跑会把旧图留在 val/test 里，
    # 造成"新数据集里混着已弃用源"的静默污染（stats 还看不出来）。
    # clean=False 时只覆盖不删除，用于"源未变、只想重跑一遍"的场景（大批量删除会被
    # 安全策略拦截）；此时收尾会做文件数一致性自检，防止残留文件被忽略。
    removed = 0
    for split in ("train", "val", "test"):
        for sub in ("images", "labels"):
            d = out_root / sub / split
            d.mkdir(parents=True, exist_ok=True)
            if clean:
                for p in d.iterdir():
                    if p.is_file():
                        p.unlink()
                        removed += 1
    if removed:
        print(f"[build] 清理上次构建残留文件 {removed} 个")
    elif not clean:
        print("[build] --no-clean：保留既有文件，仅覆盖写入")

    for src in sources:
        if src.kind == "mask":
            info = build_mask_source(src, out_root, stats, cls_index)
        elif src.kind == "coco":
            info = build_coco_source(src, out_root, stats, cls_index)
        else:
            raise SystemExit(f"未知 source.kind: {src.kind}")
        stats.per_source.append(info)
    return stats


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", required=True, help="数据源配置 JSON")
    ap.add_argument("--out", required=True, help="输出数据集根目录")
    ap.add_argument(
        "--no-clean",
        action="store_true",
        help="不删除输出目录里的既有文件（源未变时重跑用）；收尾会做文件数一致性自检",
    )
    args = ap.parse_args()

    cfg = json.loads(Path(args.config).read_text(encoding="utf-8"))
    out_root = Path(args.out).resolve()
    out_root.mkdir(parents=True, exist_ok=True)
    base = Path(args.config).resolve().parent

    sources: list[Source] = []
    for s in cfg["sources"]:
        kind = s.get("kind", "coco")
        kw: dict[str, Any] = dict(
            name=s["name"], split=s["split"], kind=kind,
            class_map=s.get("class_map", {}),
            license=s.get("license", "unknown"),
            homepage=s.get("homepage", ""),
            prefix=s.get("prefix", ""),
        )
        if kind == "coco":
            kw["images_dir"] = (base / s["images_dir"]).resolve()
            kw["coco_json"] = (base / s["coco_json"]).resolve()
        else:
            kw["images_dir"] = (base / s["images_dir"]).resolve()
            kw["masks_dir"] = (base / s["masks_dir"]).resolve()
            kw["mask_target"] = s["mask_target"]
            kw["min_component_area"] = int(s.get("min_component_area", 20))
            kw["mask_name_template"] = s.get("mask_name_template", "{stem}.png")
            kw["strip_prefix"] = s.get("strip_prefix", "")
            kw["auto_split"] = s.get("auto_split")
            kw["mask_close_kernel"] = int(s.get("mask_close_kernel", 0))
            kw["trim_fill_border"] = bool(s.get("trim_fill_border", False))
            kw["trim_min_ratio"] = float(s.get("trim_min_ratio", 0.02))
            kw["min_keep_ratio"] = float(s.get("min_keep_ratio", 0.25))
        sources.append(Source(**kw))

    stats = build(sources, out_root, cfg["class_names"], clean=not args.no_clean)
    cross_removed = dedup_cross_split(out_root, stats, cfg["class_names"])

    # 收尾自检：目录里真实文件数必须与统计一致，否则说明有上次构建的残留混在里面。
    real_images = sum(1 for d in ("train", "val", "test")
                      for p in (out_root / "images" / d).iterdir() if p.is_file())
    real_labels = sum(1 for d in ("train", "val", "test")
                      for p in (out_root / "labels" / d).iterdir() if p.is_file())
    expected = sum(stats.images_per_split.values())
    if real_images != expected or real_labels != expected:
        raise SystemExit(
            f"构建产物与统计不一致：images={real_images} labels={real_labels} 期望={expected}。"
            " 说明输出目录有历史残留，请清理后重跑（或去掉 --no-clean）。"
        )

    total_images = sum(stats.images_per_split.values())
    report = {
        "dataset_name": cfg.get("dataset_name", "disaster"),
        "built_at": datetime.now(timezone.utc).isoformat(),
        "class_names": cfg["class_names"],
        "total_images": total_images,
        "images_per_split": dict(stats.images_per_split),
        "class_instances": dict(stats.class_instances),
        "class_mapping": dict(stats.class_mapping),
        "empty_label_images": dict(stats.empty_label_images),
        "clipped_boxes": stats.clipped_boxes,
        "cross_split_dedup_removed": cross_removed,
        "missing_images": len(stats.missing_images),
        "dropped_total": dict(stats.dropped),
        "per_source": stats.per_source,
    }
    (out_root / "stats.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    yaml = [
        f"# 由 build_yolo_dataset.py 生成于 {report['built_at']}",
        "# 标注全部来自公开数据集的人工标注，经确定性格式转换；详见 DATASET_CARD.md",
        f"path: {out_root.as_posix()}",
        "train: images/train",
        "val: images/val",
        "test: images/test",
        f"nc: {len(cfg['class_names'])}",
        "names:",
    ] + [f"  {i}: {n}" for i, n in enumerate(cfg["class_names"])]
    (out_root / "dataset.yaml").write_text("\n".join(yaml) + "\n", encoding="utf-8")

    print(json.dumps({k: report[k] for k in (
        "total_images", "images_per_split", "class_instances",
        "empty_label_images", "clipped_boxes", "cross_split_dedup_removed", "dropped_total")},
        ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
