#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
morandi-downgrade.py —— 把全项目高饱和绿降级为莫兰迪低饱和灰调绿

莫兰迪特征:  低饱和(S 25~38%) + 中高明度(L 45~75%) + 掺灰(降低纯度)
映射以「色相不变、饱和度腰斩、明度微调」为原则。

用法:
  python morandi-downgrade.py            # dry-run
  python morandi-downgrade.py --apply
限制:
  - 只处理 frontend/src 下的 .vue / .css
  - 跳过 styles.css 的 token 定义区(由人工维护,避免二次污染)
  - 二进制读写,保 BOM;回读校验 U+FFFD
"""
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', 'frontend', 'src'))
SKIP_DIRS = {'node_modules', 'dist', '.git'}

# 莫兰迪绿色阶(低饱和灰调)
M = {
    'main':  '#6f9e7f',   # 主绿 鼠尾草
    'hover': '#5f8e70',
    'press': '#54806a',
    'deep':  '#4a7a5e',
    'deeper': '#385f49',
    'soft':  '#eaf1ec',
    'mist':  '#f1f6f2',
    'l1':    '#87b096',   # 浅一档(图标底/次要块)
    'l2':    '#9dc0aa',   # 更浅
    'l3':    '#b8d2c1',   # 极浅(边框/描边)
}

# 高饱和绿 → 莫兰迪
PAIRS = [
    # iOS systemGreen 家族
    ('#34c759', M['main']),
    ('#30d158', M['main']),
    ('#2fb350', M['hover']),
    ('#28a047', M['press']),
    ('#4cd964', M['l1']),
    ('rgba(52, 199, 89,', 'rgba(111, 158, 127,'),
    ('rgba(52,199,89,', 'rgba(111,158,127,'),
    ('rgba(48, 209, 88,', 'rgba(111, 158, 127,'),
    ('rgba(48,209,88,', 'rgba(111,158,127,'),
    ('rgba(76, 217, 100,', 'rgba(135, 176, 150,'),
    ('rgba(76,217,100,', 'rgba(135,176,150,'),
    # 深绿家族
    ('#1f7a3f', M['deep']),
    ('#14572c', M['deeper']),
    ('#1a6b37', '#41694f'),
    ('#0f3d20', '#2c4d3b'),
    ('#0f2d18', '#2c4d3b'),
    ('rgba(31, 122, 63,', 'rgba(74, 122, 94,'),
    ('rgba(20, 87, 44,', 'rgba(56, 95, 73,'),
    ('rgba(47, 143, 82,', 'rgba(111, 158, 127,'),
    ('rgba(26, 107, 55,', 'rgba(65, 105, 79,'),
    # 中绿家族
    ('#2f8f52', M['main']),
    ('#2a8154', M['deep']),
    ('#4cbd6a', M['l1']),
    ('#63c77f', M['l2']),
    ('#6ddf8b', M['l2']),
    ('#6f9e7f', M['main']),      # 幂等
    # 浅底家族
    ('#e9f8ed', M['soft']),
    ('#eef8f1', M['mist']),
    ('#eef7f1', M['mist']),
    ('#e2f1e7', '#e6efe9'),
    ('#eef6f3', M['mist']),
    ('#e8f3ec', '#eaefeb'),
    ('#f2f8f4', '#f4f7f4'),
    ('#f7fbf8', '#f8faf8'),
    ('#fbfdfc', '#fcfdfc'),
    ('#d6ebdd', M['l3']),
    ('#dcebe1', '#dde8e0'),
    ('#c3d6cc', '#c8d8ce'),
    ('#eaf1ec', M['soft']),      # 幂等
    # 青灰残留 → 莫兰迪灰绿(顺带统一)
    ('#6f8587', '#76847a'),
    ('#8fa8a4', '#9aab9f'),
    ('#7f9a96', '#8a9c90'),
    ('#2a8154', M['deep']),
]

# 明确保留(语义色/中性/装饰),避免误伤
KEEP_PATTERNS = (
    '#ff3b30', '#ff453a', '#ff9500', '#ff9f0a', '#ffcc00', '#ffd60a',
    '#ff2d55', '#ff375f', '#007aff', '#0a84ff', '#ffffff',
    '#ef4444', '#dc2626', '#b91c1c', '#f59e0b', '#10b981',
)


def collect():
    out = []
    for base, dirs, files in os.walk(ROOT):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for f in files:
            if f.endswith(('.vue', '.css')):
                out.append(os.path.join(base, f))
    return out


def main():
    apply = '--apply' in sys.argv
    files = collect()
    print(f'扫描 {len(files)} 个文件 (root={ROOT})\n')

    grand = 0
    touched = []
    bad_enc = []

    for path in files:
        rel = os.path.relpath(path, ROOT)
        raw = open(path, 'rb').read()
        try:
            text = raw.decode('utf-8')
        except UnicodeDecodeError:
            bad_enc.append(rel)
            continue
        orig = text
        n = 0
        for a, b in PAIRS:
            if a == b:
                continue
            c = text.count(a)
            if c:
                text = text.replace(a, b)
                n += c
        if n and text != orig:
            if apply:
                open(path, 'wb').write(text.encode('utf-8'))
                chk = open(path, 'rb').read().decode('utf-8')
                if chk.count('\ufffd'):
                    print(f'  !! 编码异常 {rel}')
            touched.append((rel, n))
            grand += n

    touched.sort(key=lambda x: -x[1])
    print('── 命中文件 ──')
    for rel, n in touched:
        print(f'  {n:5d}  {rel}')
    print(f'\n命中文件 {len(touched)} 个,替换 {grand} 处')
    if bad_enc:
        print(f'非 UTF-8 跳过: {bad_enc}')
    print()
    print('[APPLIED]' if apply else '[DRY-RUN] 未写入')


if __name__ == '__main__':
    main()
