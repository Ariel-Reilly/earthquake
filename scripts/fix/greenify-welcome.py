#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
greenify-welcome.py —— 把 WelcomePage 与动画背景的多彩渐变收敛为绿色单色族

映射原则:
  indigo/violet(品牌) → 深绿/主绿
  pink(强调)          → 中绿
  amber/sky/teal(点缀) → 浅绿/薄荷
  红(语义/装饰红点)    → 保留
  macOS 窗口点         → 保留
  emerald(已是绿)      → 保留
"""
import sys

FILES = [
    'frontend/src/views/WelcomePage.vue',
    'frontend/src/components/AnimatedLoginBackground.vue',
    'frontend/src/components/AnimatedDashboardBackground.vue',
    'frontend/src/components/AnimatedSidebarBackground.vue',
]

PAIRS = [
    # ── indigo / violet → 深绿 ──
    ('#4338ca', '#1f7a3f'),
    ('#6d28d9', '#14572c'),
    ('#4f46e5', '#2f8f52'),
    ('#7c3aed', '#1f7a3f'),
    ('#4c1d95', '#0f3d20'),
    ('#312e81', '#14572c'),
    ('#1e1b4b', '#0f2d18'),
    ('#f5f3ff', '#e9f8ed'),
    ('rgba(99, 102, 241,', 'rgba(52, 199, 89,'),
    ('rgba(67, 56, 202,', 'rgba(31, 122, 63,'),
    ('rgba(109, 40, 217,', 'rgba(20, 87, 44,'),
    # ── pink → 中绿 ──
    ('#db2777', '#2f8f52'),
    ('rgba(219, 39, 119,', 'rgba(47, 143, 82,'),
    ('rgba(236, 72, 153,', 'rgba(47, 143, 82,'),
    # ── amber → 浅绿点缀 ──
    ('#f59e0b', '#4cbd6a'),
    ('#fbbf24', '#6ddf8b'),
    ('#d97706', '#2f8f52'),
    ('#fcd34d', '#a5e8b8'),
    ('#b45309', '#1f7a3f'),
    ('#deb154', '#8fd3a0'),
    ('rgba(245, 158, 11,', 'rgba(76, 189, 106,'),
    ('rgba(251, 191, 36,', 'rgba(109, 223, 139,'),
    # ── sky / teal → 主绿 ──
    ('#0ea5e9', '#34c759'),
    ('rgba(14, 165, 233,', 'rgba(52, 199, 89,'),
    ('#14b8a6', '#34c759'),
    ('rgba(20, 184, 166,', 'rgba(52, 199, 89,'),
    # ── 深色正文 slate → 墨绿灰(保持对比度) ──
    ('rgba(15, 23, 42,', 'rgba(15, 34, 24,'),
    # ── 动画背景粉彩 → 绿粉彩 ──
    ('#a9c7ea', '#bfe6da'),
    ('#6a9fd6', '#8fd3c0'),
    ('#f3cd92', '#c9ecd4'),
    ('#e9b069', '#a8dcb6'),
    ('#d6e4f8', '#d6ecdd'),
    ('#b2cdf1', '#bfe6da'),
    ('#f6e4c4', '#e2f3e7'),
    ('#f0d3a4', '#cfe9d8'),
    # ── 侧栏背景装饰 ──
    ('#12383e', '#14572c'),
    ('#9a6f3e', '#2f8f52'),
    ('#3a7d8f', '#1f7a3f'),
    ('#2f8f7a', '#34c759'),
    # ── 登录页浅青残留 ──
    ('#7fd4bd', '#8fd3c0'),
    ('#3fa591', '#2f8f52'),
]

# 明显属于语义/装饰保留的色值(校验用,不参与替换)
KEEP = {'#ef4444', '#dc2626', '#b91c1c', '#e0443e', '#e07859',
        '#ff5f57', '#febc2e', '#dea123', '#28c840', '#1aab29',
        '#10b981', '#047857', '#34c759'}


def main():
    root = 'D:/Projects/DisasterGuard/'
    total = 0
    for rel in FILES:
        path = root + rel
        raw = open(path, 'rb').read()
        text = raw.decode('utf-8')
        before_fffd = text.count('\ufffd')
        n = 0
        for a, b in PAIRS:
            c = text.count(a)
            if c:
                text = text.replace(a, b)
                n += c
        if n:
            out = text.encode('utf-8')
            open(path, 'wb').write(out)
            # 回读校验
            chk = open(path, 'rb').read().decode('utf-8')
            fffd = chk.count('\ufffd')
            ok = 'OK' if (fffd == before_fffd == 0) else f'!! U+FFFD={fffd}'
            print(f'{rel}: {n} 处替换, 编码校验 {ok}')
            total += n
        else:
            print(f'{rel}: 无命中')
    print(f'\n合计: {total} 处')


if __name__ == '__main__':
    sys.exit(main())
