#!/usr/bin/env python3
"""在同一测试集上横向比较多个权重，输出可比的 per-class 指标。

为什么单独写：训练脚本内的 val 只能评估单一模型，而"补了高分辨率数据后精度是否真的提升"
必须**固定测试集**比较才有意义。本脚本把 N 个权重跑在同一个 split 上，输出一张表。

用法：
    python eval_compare.py --data ../../datasets/disaster-v2 --split test \
        --weights runs/detect/v1-n60/weights/best.pt runs/detect/v2-n60/weights/best.pt \
        --labels v1-data v2-data --out runs/compare_v1_v2.json

输出：
    - stdout：对齐的表格（每类 precision / recall / mAP50 / mAP50-95）
    - JSON：完整结果，供文档与报告引用

注意：脚本不修改、不重训任何权重；数字全部来自 ultralytics 的 val 输出。
     若某个权重与数据集类别数不一致，会直接报错而不是给出误导性的 0。
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from ultralytics import YOLO


def read_names(data_root: Path) -> list[str]:
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
        if in_names and ":" in s:
            k, v = s.split(":", 1)
            if k.strip().isdigit():
                names[int(k.strip())] = v.strip().strip("'\"")
    return [names[i] for i in sorted(names)]


def evaluate(weights: Path, args, class_names: list[str]) -> dict:
    model = YOLO(str(weights))
    n_model_cls = len(model.names) if hasattr(model, "names") else -1
    if n_model_cls != len(class_names):
        raise SystemExit(
            f"{weights.name} 的类别数 {n_model_cls} 与数据集 {len(class_names)} 不一致，"
            "比较结果无意义，已中止。"
        )

    metrics = model.val(
        data=str(args.data_yaml),
        split=args.split,
        imgsz=args.imgsz,
        batch=args.batch,
        device=args.device,
        workers=args.workers,
        project=str(args.project),
        name=f"cmp_{weights.parent.parent.name}",
        exist_ok=True,
        verbose=False,
    )

    per_class: dict[str, dict[str, float]] = {}
    idx = list(metrics.box.ap_class_index)
    overall = {
        "precision": round(float(metrics.box.mp), 4),
        "recall": round(float(metrics.box.mr), 4),
        "mAP50": round(float(metrics.box.map50), 4),
        "mAP50-95": round(float(metrics.box.map), 4),
    }
    for pos, ci in enumerate(idx):
        cname = class_names[ci] if ci < len(class_names) else str(ci)
        per_class[cname] = {
            "precision": round(float(metrics.box.p[pos]), 4),
            "recall": round(float(metrics.box.r[pos]), 4),
            "mAP50": round(float(metrics.box.ap50[pos]), 4),
            "mAP50-95": round(float(metrics.box.ap[pos]), 4),
        }
    return {"weights": str(weights), "overall": overall, "per_class": per_class}


def main() -> None:
    ap = argparse.ArgumentParser(description="固定测试集横向比较多个 YOLO 权重")
    ap.add_argument("--data", required=True)
    ap.add_argument("--split", default="test", choices=("train", "val", "test"))
    ap.add_argument("--weights", nargs="+", required=True)
    ap.add_argument("--labels", nargs="*", default=None, help="与 weights 对应的展示名")
    ap.add_argument("--imgsz", type=int, default=640)
    ap.add_argument("--batch", type=int, default=16)
    ap.add_argument("--device", default="cpu")
    ap.add_argument("--workers", type=int, default=0)
    ap.add_argument("--project", default="runs/eval")
    ap.add_argument("--out", default="runs/eval/compare.json")
    a = ap.parse_args()

    data_root = Path(a.data)
    a.data_yaml = data_root / "dataset.yaml"
    if not a.data_yaml.exists():
        raise SystemExit(f"缺少 {a.data_yaml}，请先构建数据集")

    class_names = read_names(data_root)
    labels = a.labels or [Path(w).parent.parent.name for w in a.weights]
    if len(labels) != len(a.weights):
        raise SystemExit("--labels 数量必须与 --weights 一致")

    results = []
    for w, lab in zip(a.weights, labels):
        wp = Path(w)
        if not wp.exists():
            raise SystemExit(f"权重不存在：{wp}")
        print(f"[eval] {lab} <- {wp}", flush=True)
        r = evaluate(wp, a, class_names)
        r["label"] = lab
        results.append(r)

    # 表格输出
    print(f"\n=== {a.split} 集比较（{data_root.name}, imgsz={a.imgsz}）===")
    header = f"{'模型':<14}{'类别':<12}{'P':>8}{'R':>8}{'mAP50':>9}{'mAP50-95':>10}"
    print(header)
    print("-" * len(header))
    for r in results:
        o = r["overall"]
        print(f"{r['label']:<14}{'ALL':<12}{o['precision']:>8.3f}{o['recall']:>8.3f}"
              f"{o['mAP50']:>9.3f}{o['mAP50-95']:>10.3f}")
        for cname, m in r["per_class"].items():
            print(f"{'':<14}{cname:<12}{m['precision']:>8.3f}{m['recall']:>8.3f}"
                  f"{m['mAP50']:>9.3f}{m['mAP50-95']:>10.3f}")
        print("-" * len(header))

    out = Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "dataset": data_root.name,
        "split": a.split,
        "imgsz": a.imgsz,
        "class_names": class_names,
        "results": results,
    }
    out.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[eval] 结果已写入 {out}")

    # 明确打印增量，避免看表时误读
    if len(results) == 2:
        a0, b0 = results[0], results[1]
        for cname in class_names:
            if cname in a0["per_class"] and cname in b0["per_class"]:
                x = a0["per_class"][cname]["mAP50"]
                y = b0["per_class"][cname]["mAP50"]
                delta = y - x
                rel = (delta / x * 100) if x > 0 else float("inf")
                print(f"[delta] {cname} mAP50: {x:.3f} -> {y:.3f}  "
                      f"({'+' if delta >= 0 else ''}{delta:.3f}, "
                      f"{'inf' if rel == float('inf') else f'{rel:+.1f}%'})")


if __name__ == "__main__":
    sys.exit(main())
