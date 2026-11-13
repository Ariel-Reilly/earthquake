"""把 output/delivery-materials/DisasterGuard-演示材料包-v1.0 打成 UTF-8 zip。

原因：JDK `jar cfM` 在 Windows 用平台编码（GBK）写条目名且不置 UTF-8 标志位，
非 Windows 解压会显示乱码。Python zipfile 对非 ASCII 名称自动使用 UTF-8 + flag 0x800。

用法：python scripts/delivery/pack-demo-materials.py
产物：同名 .zip（ZIP_STORED，PPTX 本身已压缩，避免二次压缩浪费时间）
"""
import os
import zipfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
BASE = os.path.join(ROOT, "output", "delivery-materials")
SRC = os.path.join(BASE, "DisasterGuard-演示材料包-v1.0")
OUT = os.path.join(BASE, "DisasterGuard-演示材料包-v1.0.zip")

if not os.path.isdir(SRC):
    raise SystemExit(f"材料包目录不存在：{SRC}")
if os.path.exists(OUT):
    os.remove(OUT)

count = 0
with zipfile.ZipFile(OUT, "w", zipfile.ZIP_STORED, allowZip64=True) as zf:
    for dirpath, _dirnames, filenames in os.walk(SRC):
        for name in sorted(filenames):
            full = os.path.join(dirpath, name)
            arc = os.path.relpath(full, BASE).replace(os.sep, "/")
            zf.write(full, arc)
            count += 1

print(f"打包完成：{OUT}")
print(f"文件数={count}，大小={os.path.getsize(OUT):,} B")
