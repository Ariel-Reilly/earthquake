#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
greenify-styles.py —— 收敛 styles.css 中遗留的蓝色品牌色为绿色

与 greenify-theme.py 的区别:
  styles.css 是设计令牌的**定义处**,必须保护:
    - `--sys-blue*` 是 iOS 系统色调色板,保留原值(供 info 类语义使用)
    - `.tone-blue` 图标调色板保留原值(语义命名,内容随命名)
  其余出现蓝色品牌色的规则(按钮/聚焦/选中/链接/进度)全部改为绿色 token。

用法:
  python greenify-styles.py --dry-run
  python greenify-styles.py --apply
"""

import argparse
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
TARGET = os.path.abspath(os.path.join(HERE, '..', '..', 'frontend', 'src', 'styles.css'))

# 保护行:包含这些片段的行不改写
PROTECT = (
    '--sys-blue',
    'tone-blue',
    '--c-brand',
    '--brand-grad',
    '--brand-ring',
)

# 蓝色 → 绿色映射(含 rgba)
MAP = {
    '#007aff': '#34c759',
    '#0a84ff': '#2fb350',
    '#5ac8fa': '#6ddf8b',
    '#2563eb': '#1f7a3f',
    '#1d4ed8': '#14572c',
    '#3b82f6': '#4cbd6a',
    '#2b6cb0': '#2f8f52',
    '#1d6b9a': '#27805a',
    '#af52de': '#1f7a3f',
    'rgba(0, 122, 255,': 'rgba(52, 199, 89,',
    'rgba(0,122,255,': 'rgba(52,199,89,',
    'rgba(10, 132, 255,': 'rgba(47, 179, 80,',
    'rgba(10,132,255,': 'rgba(47,179,80,',
    'rgba(43, 108, 176,': 'rgba(47, 143, 82,',
    'rgba(43,108,176,': 'rgba(47,143,82,',
    'rgba(91, 180, 134,': 'rgba(76, 189, 106,',
    'rgba(91,180,134,': 'rgba(76,189,106,',
    'rgba(45, 130, 128,': 'rgba(47, 143, 82,',
    'rgba(45,130,128,': 'rgba(47,143,82,',
}

# 额外的青灰绿残留
GREEN_EXTRA = {
    '#6dbfa5': '#4cbd6a',
    '#2b6cb0': '#2f8f52',
    '#7a8d89': '#6f8587',
    '#4f8f99': '#2f8f52',
    '#4e8882': '#3f7a5c',
    '#365e61': '#2f5550',
    '#96a7a5': '#8fa8a4',
    '#9daead': '#8fa8a4',
    '#bcddd4': '#bfe3cb',
    '#f0faf7': '#eef8f1',
    '#e8f5f1': '#e9f8ed',
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--apply', action='store_true')
    args = ap.parse_args()

    combined = dict(MAP)
    combined.update(GREEN_EXTRA)

    with open(TARGET, 'r', encoding='utf-8') as fh:
        lines = fh.readlines()

    total = 0
    changed_lines = 0
    detail = {}

    for i, line in enumerate(lines):
        if any(p in line for p in PROTECT):
            continue
        new = line
        for k, v in combined.items():
            if k in new:
                detail[k] = detail.get(k, 0) + new.count(k)
                total += new.count(k)
                new = new.replace(k, v)
        if new != line:
            lines[i] = new
            changed_lines += 1

    print(f'目标文件: {TARGET}')
    print()
    print('── 替换明细 ──')
    for k, c in sorted(detail.items(), key=lambda x: -x[1]):
        print(f'  {k:22s} -> {combined[k]:22s}  x{c}')
    print()
    print(f'改动行数: {changed_lines}')
    print(f'替换总数: {total}')

    if args.apply:
        with open(TARGET, 'w', encoding='utf-8', newline='') as fh:
            fh.writelines(lines)
        print()
        print('[APPLIED]')
    else:
        print()
        print('[DRY-RUN] 未写入')


if __name__ == '__main__':
    sys.exit(main())
