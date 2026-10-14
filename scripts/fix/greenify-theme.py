#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
greenify-theme.py —— 把前端硬编码的 iOS 蓝 / 残留青灰绿收敛为绿色品牌 token

设计原则:
  1. 只替换「品牌/中性装饰」用途的色值,不动语义色(红/橙/黄/灾种/预警等级)。
  2. 蓝色系(#007aff 家族)一律映射到主绿 token 或字面主绿。
  3. 青灰绿残留(#7a8d89 家族)按明度映射到新绿色体系(深绿/中绿/薄荷浅底)。

用法:
  python greenify-theme.py --dry-run     # 只统计不写入
  python greenify-theme.py --apply       # 实际写入
"""

import argparse
import os
import re
import sys
from collections import Counter

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', 'frontend', 'src'))
EXTS = ('.vue', '.css')
SKIP_DIRS = {'node_modules', 'dist', '.git'}
# styles.css 是设计令牌的定义处,绝不能被脚本改写(否则会破坏 token 体系)
SKIP_FILES = {'styles.css'}

# ── 蓝色系 → 主绿 ──
BLUE_MAP = {
    # 实色
    '#007aff': '#34c759',
    '#007AFF': '#34C759',
    '#0a84ff': '#2fb350',
    '#0A84FF': '#2FB350',
    '#409cff': '#4cd964',
    '#5ac8fa': '#6ddf8b',
    '#2563eb': '#1f7a3f',
    '#1d4ed8': '#14572c',
    '#3b82f6': '#4cbd6a',
    '#5b8ef9': '#63c77f',
    '#2b6cb0': '#2f8f52',
    '#1d6b9a': '#27805a',
    # rgba 形式
    'rgba(0, 122, 255,': 'rgba(52, 199, 89,',
    'rgba(0,122,255,': 'rgba(52,199,89,',
    'rgba(10, 132, 255,': 'rgba(47, 179, 80,',
    'rgba(10,132,255,': 'rgba(47,179,80,',
    'rgba(43, 108, 176,': 'rgba(47, 143, 82,',
    'rgba(43,108,176,': 'rgba(47,143,82,',
    'rgba(59, 130, 246,': 'rgba(76, 189, 106,',
    'rgba(59,130,246,': 'rgba(76,189,106,',
    'rgba(91, 142, 249,': 'rgba(99, 199, 127,',
    'rgba(91,142,249,': 'rgba(99,199,127,',
}

# ── 青灰绿残留 → 新绿色体系 ──
# deep  = 深绿(标题/深色块/主要文字强调)
# brand = 中绿(图标/次强调)
# soft  = 薄荷浅底(选中底/浅色块)
# bg    = 页面底
GREEN_MAP = {
    # 中性灰绿(偏灰,多用于次要文字/边框)
    '#7a8d89': '#6f8587',
    '#94a3b8': '#8aa3a0',
    '#9baead': '#8fa8a4',
    '#64748b': '#6b7f7c',
    '#6b7280': '#6f8587',
    '#72908f': '#6f8587',
    '#4a6367': '#3d5a55',
    '#5a6a68': '#4f6663',
    '#334155': '#3a4f4a',
    '#2d3748': '#33443f',
    '#374151': '#3a4f4a',
    '#475569': '#40564f',
    '#cbd5e1': '#c3d6cc',
    '#1e293b': '#1f3b3e',
    '#1f2d3d': '#20343a',
    '#4a6760': '#3f5a52',
    '#5b7770': '#4d6862',
    '#8aa3a0': '#7f9a96',
    '#36585b': '#2f5550',
    '#35575b': '#2f5550',
    '#2c3e3e': '#2a3f3c',
    # 青绿强调色 → 主绿体系
    '#4f8f99': '#2f8f52',
    '#56716e': '#2a8154',
    '#6dbfa5': '#4cbd6a',
    '#7d9b99': '#6f8587',
    '#1f6b6c': '#1f7a3f',
    '#0f766e': '#1f7a3f',
    '#31bad1': '#34c759',
    # 浅底
    '#e9eee9': '#e9f8ed',
    '#e9efe9': '#e9f8ed',
    '#fafdfc': '#f7fbf8',
    '#f4f7f5': '#f2f8f4',
    '#eef6f3': '#e9f8ed',
    '#eef2f7': '#eef7f1',
    '#f1f5f9': '#eef7f1',
    '#f8fafc': '#f5faf7',
    '#e2ebe8': '#e2f1e7',
    '#d8e6e3': '#d6ebdd',
    '#e2e8f0': '#dcebe1',
    # 深绿
    '#1e4b45': '#14572c',
    '#1f3b3e': '#1f3b3e',
    '#2a8154': '#2a8154',
}

# 保持不变(语义色/灾种色/中性,防止误伤)
KEEP = {
    '#e06060', '#c7553b', '#e0a050', '#d9c040', '#9b761e', '#9c3d3d',
    '#ef4444', '#f59e0b', '#dc2626', '#22c55e', '#db2777', '#4338ca',
    '#1c1c1e', '#ffffff', '#fff', '#000', '#000000',
}


def collect_files():
    out = []
    for base, dirs, files in os.walk(ROOT):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for f in files:
            if f.endswith(EXTS) and f not in SKIP_FILES:
                out.append(os.path.join(base, f))
    return out


def build_regex(mapping):
    """按 key 长度降序构造正则,避免短 key 抢先匹配长 key。"""
    keys = sorted(mapping.keys(), key=len, reverse=True)
    parts = []
    for k in keys:
        if k.startswith('rgba('):
            parts.append(re.escape(k))
        else:
            parts.append(re.escape(k) + r'(?![0-9a-fA-F])')
    return re.compile('|'.join(parts))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--apply', action='store_true')
    ap.add_argument('--dry-run', action='store_true')
    args = ap.parse_args()
    apply = args.apply

    combined = {}
    combined.update(BLUE_MAP)
    combined.update(GREEN_MAP)

    pat = build_regex(combined)
    # 大小写不敏感匹配: hex 色值可能大写
    pat_ci = re.compile(pat.pattern, re.IGNORECASE)

    files = collect_files()
    print(f'扫描目录: {ROOT}')
    print(f'候选文件: {len(files)} 个')

    hits = Counter()
    changed_files = []
    total = 0

    for path in files:
        with open(path, 'r', encoding='utf-8', errors='surrogateescape') as fh:
            src = fh.read()
        orig = src
        local = Counter()

        def repl(m):
            raw = m.group(0)
            low = raw.lower()
            if low in KEEP:
                return raw
            # 精确命中(小写键)
            if low in combined:
                return combined[low]
            return raw

        new = pat_ci.sub(repl, src)

        # 再做一次纯小写精确替换,处理大小写混排
        for k, v in combined.items():
            if k in new:
                n = new.count(k)
                if n:
                    local[k] += n
                    new = new.replace(k, v)

        if new != orig:
            n = sum(local.values())
            # 统计:用原文重新数一遍,保证准确
            cnt = 0
            for k in combined:
                cnt += len(re.findall(re.escape(k), orig, re.IGNORECASE))
            hits[k] = hits.get(k, 0)
            total += cnt
            changed_files.append((os.path.relpath(path, ROOT), cnt))
            if apply:
                with open(path, 'w', encoding='utf-8', errors='surrogateescape', newline='') as fh:
                    fh.write(new)

    # 按原文件重新精确统计替换明细
    detail = Counter()
    for path in files:
        with open(path, 'r', encoding='utf-8', errors='surrogateescape') as fh:
            src = fh.read()
        for k in combined:
            c = len(re.findall(re.escape(k), src, re.IGNORECASE))
            if c:
                detail[k] += c

    print()
    print('── 替换明细 ──')
    for k, c in detail.most_common():
        print(f'  {k:24s} -> {combined[k]:24s}  x{c}')
    print()
    print(f'命中文件: {len(changed_files)} 个')
    print(f'替换总数: {sum(detail.values())} 处')
    print()
    print('── 改动文件 ──')
    for rel, c in sorted(changed_files, key=lambda x: -x[1]):
        print(f'  {c:5d}  {rel}')

    if not apply:
        print()
        print('[DRY-RUN] 未写入任何文件。加 --apply 生效。')
    else:
        print()
        print('[APPLIED] 已写入。')


if __name__ == '__main__':
    sys.exit(main())
