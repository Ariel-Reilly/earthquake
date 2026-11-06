#!/usr/bin/env python3
"""多连接分段下载器（仅用于公开数据集获取）。

为什么需要它：Zenodo 对单连接限速约 10~30 KB/s，而它支持 HTTP Range（206）。
把文件切成 N 段并行拉取可以把总吞吐提升一个量级。

工程约定（每一条都是踩过坑补的）：
  1. **每段写独立的 .part-N 临时文件，最后顺序合并**。绝不让多个线程对同一个
     文件句柄 seek+write —— Windows 上多句柄同文件写入会产生静默的段错位，
     表现为 zip 能列目录但解压报 bad CRC。这是本脚本第一版真实翻车的原因。
  2. 合并后校验大小 == content-length，并做 CRC/格式自检（zip 由调用方负责）。
  3. 分段失败最多重试 6 次，仍失败则整体报错退出，不假装成功。
  4. 支持断点续传：已有完整大小的目标文件直接跳过；.part 文件按段复用。
"""
from __future__ import annotations

import argparse
import os
import shutil
import sys
import threading
import time
from pathlib import Path
from urllib.parse import unquote

import requests

CHUNK = 1 << 16
MAX_ATTEMPTS = 6


def probe(url: str, session: requests.Session) -> tuple[int, bool]:
    """拿文件大小并判断是否支持 Range。

    Zenodo 在拥塞时段连 HEAD 都会超时，这里必须重试；探测失败不等于文件不可下，
    直接判失败会让整批数据集下载无故中断。
    """
    last: Exception | None = None
    for attempt in range(4):
        try:
            r = session.head(url, allow_redirects=True, timeout=60)
            r.raise_for_status()
            size = int(r.headers.get("content-length", "0"))
            declared = r.headers.get("accept-ranges", "").lower() == "bytes"
            if not declared:
                t = session.get(url, headers={"Range": "bytes=0-1"}, timeout=60, stream=True)
                declared = t.status_code == 206
                t.close()
            return size, declared
        except Exception as exc:  # noqa: BLE001
            last = exc
            time.sleep(2 * (attempt + 1))
    raise SystemExit(f"探测文件信息失败（已重试 4 次）：{last}")


class Segment(threading.Thread):
    def __init__(self, url: str, part: Path, seg_start: int, seg_end: int, idx: int, report: dict):
        super().__init__(daemon=True)
        self.url, self.part = url, part
        self.seg_start, self.seg_end = seg_start, seg_end
        self.idx, self.report = idx, report

    def run(self) -> None:
        want = self.seg_end - self.seg_start + 1
        got = self.part.stat().st_size if self.part.exists() else 0
        if got > want:  # 残片比目标段大，说明上次写坏了，重来
            got = 0
            self.part.unlink(missing_ok=True)

        attempt = 0
        with open(self.part, "ab") as f:
            while got < want and attempt < MAX_ATTEMPTS:
                attempt += 1
                headers = {"Range": f"bytes={self.seg_start + got}-{self.seg_end}"}
                try:
                    with requests.get(self.url, headers=headers, timeout=120, stream=True) as r:
                        if r.status_code not in (200, 206):
                            raise RuntimeError(f"HTTP {r.status_code}")
                        for chunk in r.iter_content(CHUNK):
                            if not chunk:
                                continue
                            f.write(chunk)
                            got += len(chunk)
                            self.report["done"] += len(chunk)
                            if got >= want:
                                break
                except Exception as exc:  # noqa: BLE001 - 网络异常统一重试
                    f.flush()
                    if attempt >= MAX_ATTEMPTS:
                        self.report["errors"].append(f"seg{self.idx}: {exc}")
                        return
                    time.sleep(1.5 * attempt)

        if got != want:
            self.report["errors"].append(f"seg{self.idx}: 仅 {got}/{want} 字节")


def download(url: str, out: Path, workers: int = 16, force: bool = False) -> None:
    session = requests.Session()
    session.headers["User-Agent"] = "DisasterGuard-dataset-fetch/1.0"
    size, supports = probe(url, session)
    if size <= 0:
        raise SystemExit(f"无法获取文件大小：{url}")

    if out.exists() and out.stat().st_size == size and not force:
        print(f"[skip] 已完整存在：{out.name} ({size / 1e6:.1f} MB)", flush=True)
        return

    print(f"[info] {out.name}  {size / 1e6:.1f} MB  range={supports} workers={workers}", flush=True)
    if not supports:
        print("[warn] 目标不支持 Range，退化为单连接下载", flush=True)
        tmp = out.with_suffix(out.suffix + ".single")
        with session.get(url, timeout=600, stream=True) as r, open(tmp, "wb") as f:
            for chunk in r.iter_content(CHUNK):
                f.write(chunk)
        if tmp.stat().st_size != size:
            raise SystemExit("单连接下载后大小不符")
        shutil.move(tmp, out)
        return

    parts_dir = out.with_suffix(out.suffix + ".parts")
    parts_dir.mkdir(parents=True, exist_ok=True)

    per = size // workers
    jobs: list[tuple[int, int, Path]] = []
    for i in range(workers):
        seg_start = i * per
        seg_end = size - 1 if i == workers - 1 else seg_start + per - 1
        jobs.append((seg_start, seg_end, parts_dir / f"p{i:03d}"))

    report = {"done": 0, "errors": []}
    threads = [Segment(url, p, s, e, i, report) for i, (s, e, p) in enumerate(jobs)]
    for t in threads:
        t.start()

    t0 = time.time()
    last = 0
    while any(t.is_alive() for t in threads):
        time.sleep(3)
        cur = report["done"]
        alive = any(t.is_alive() for t in threads)
        sys.stdout.write(
            f"\r  {cur / size * 100:5.1f}%  {cur / 1e6:7.1f}/{size / 1e6:.1f} MB"
            f"  {(cur - last) / 3 / 1024:6.1f} KB/s{' ' if alive else ''}"
        )
        sys.stdout.flush()
        last = cur
    for t in threads:
        t.join()
    print(flush=True)

    if report["errors"]:
        raise SystemExit("分段下载有失败段：" + "; ".join(report["errors"][:3]))

    # 顺序合并分片（合并时校验每片大小，防止残片混入）
    tmp_out = out.with_suffix(out.suffix + ".merge")
    written = 0
    with open(tmp_out, "wb") as dst:
        for (seg_start, seg_end, part) in jobs:
            want = seg_end - seg_start + 1
            have = part.stat().st_size if part.exists() else 0
            if have != want:
                raise SystemExit(f"分片 {part.name} 大小不符：{have} != {want}")
            with open(part, "rb") as src:
                shutil.copyfileobj(src, dst, CHUNK)
            written += want
    if written != size:
        raise SystemExit(f"合并后大小不符：{written} != {size}")
    shutil.move(tmp_out, out)
    shutil.rmtree(parts_dir, ignore_errors=True)
    print(f"[done] {out.name}  {size / 1e6:.1f} MB  {time.time() - t0:.0f}s", flush=True)


def main() -> None:
    ap = argparse.ArgumentParser(description="多连接分段下载公开数据集文件")
    ap.add_argument("url")
    ap.add_argument("-o", "--out", required=True)
    ap.add_argument("-w", "--workers", type=int, default=16)
    ap.add_argument("--force", action="store_true", help="即使目标文件大小已匹配也重新下载")
    a = ap.parse_args()
    out = Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    print(f"[target] {unquote(a.url)}", flush=True)
    download(a.url, out, a.workers, a.force)


if __name__ == "__main__":
    main()
