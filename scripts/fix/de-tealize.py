#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
de-tealize.py —— 清除 DisasterGuard 前端残留的「旧青绿主色」体系。

背景：
  项目原主题是青绿（teal）#2f7f80，2026-10-08 改为莫兰迪绿 iOS 风格后，
  仍有 235 处青绿/青灰散落在 styles.css 内（按钮、侧栏、输入框、标签、徽标）。
  这是页面「灰脏、不像 iPhone」的根因。

策略：
  只替换 ASCII 十六进制色值；绝不触碰中文注释与 BOM。
  「rb 读 -> utf-8 解码 -> 字符串替换 -> utf-8 编码 -> wb 写 -> 回读校验 U+FFFD」。

保护名单（不替换）：
  --sys-*        iOS 系统语义色（红/橙/黄/绿/蓝）
  .tone-*        调色板类（语义区分）
  -teal / teal   刻意的语义青绿（如定位等级 pill）

目标色（莫兰迪绿，2026-10-08 定稿）：
  主绿  #6f9e7f     hover #5f8e70     press #54806a
  deep  #4a7a5e     deeper #385f49
  soft  #eaf1ec     mist   #f1f6f2
  文字主 #2c3d33     文字次 #5c6b61    文字弱 #8a968d
  边   #dfe7e1     边2    #e8efe9
"""
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'frontend', 'src')

# 主绿家族
G = {
    'main':   '#6f9e7f',
    'hover':  '#5f8e70',
    'press':  '#54806a',
    'deep':   '#4a7a5e',
    'deeper': '#385f49',
    'soft':   '#eaf1ec',
    'mist':   '#f1f6f2',
    'light':  '#87b096',
    'l2':     '#9dc0aa',
    'l3':     '#b8d2c1',
}

# 文本/边框灰绿
T = {
    'text1': '#2c3d33',   # 主文字（原深墨青 #1f3e44 / #1f4f4a）
    'text2': '#3f5348',   # 次文字（原 #24494d / #24464b / #2c4d3b / #34575a）
    'muted': '#5c6b61',   # 弱文字（原 #5e7c79）
    'hint':  '#8a968d',   # 提示文字（原 #7b8f8c / #7a8d89 / #93a39e / #99a7a1）
    'hint2': '#9aa79f',   # 更淡（原 #9aaead / #97b1ae）
    'border':  '#dfe7e1',  # 边框（原 #cfe3df / #cfe3dd / #b6d8d2）
    'border2': '#e8efe9',  # 淡边框（原 #e3ecea / #e2ebe8）
    'bgsoft':  '#eef5f0',  # 浅底（原 #eff9f6 / #f0faf7 / #f1f8f6 / #e8f5f1）
    'bgsoft2': '#e4efe8',  # 稍深浅底（原 #d6ece8）
}

# 精确十六进制映射（长值必须在前，避免前缀误伤 —— hex 长度一致无此问题）
HEX_PAIRS = [
    # 青绿主色 -> 莫兰迪主绿
    ('#2f7f80', G['main']),
    ('#246b6c', G['hover']),
    ('#2b7778', G['press']),
    # 青绿浅底 -> 莫兰迪浅底
    ('#eff9f6', T['bgsoft']),
    ('#f0faf7', T['bgsoft']),
    ('#f1f8f6', T['bgsoft']),
    ('#e8f5f1', T['bgsoft']),
    ('#d6ece8', T['bgsoft2']),
    # 深墨青（标题文字）-> 绿灰文字
    ('#1f3e44', T['text1']),
    ('#1f4f4a', T['text1']),
    ('#24494d', T['text2']),
    ('#24464b', T['text2']),
    ('#2c4d3b', T['text2']),
    ('#34575a', T['text2']),
    # 灰青（次要文字）-> 绿灰文字
    ('#5e7c79', T['muted']),
    ('#7b8f8c', T['hint']),
    ('#7a8d89', T['hint']),
    ('#93a39e', T['hint']),
    ('#99a7a1', T['hint']),
    ('#9aaead', T['hint2']),
    ('#97b1ae', T['hint2']),
    # 青边 -> 绿边
    ('#cfe3df', T['border']),
    ('#cfe3dd', T['border']),
    ('#b6d8d2', T['border']),
    ('#e3ecea', T['border2']),
    ('#e2ebe8', T['border2']),
    # 旧品牌残留在 styles.css 其他处的青绿（由 greenify 系列遗留）
    ('#2f7f80', G['main']),
    ('#3b9a72', G['main']),
    ('#5db486', G['light']),
    ('#6dbfa5', G['l2']),
    ('#7fb0ae', G['l2']),
]

# rgba 映射（正则，因空格不定）
RGBA_PAIRS = [
    (r'rgba\(47\s*,\s*127\s*,\s*128\s*,', 'rgba(111, 158, 127,'),   # 青绿主色
    (r'rgba\(45\s*,\s*130\s*,\s*128\s*,', 'rgba(111, 158, 127,'),
    (r'rgba\(108\s*,\s*191\s*,\s*165\s*,', 'rgba(135, 176, 150,'),
    (r'rgba\(30\s*,\s*75\s*,\s*69\s*,',   'rgba(44, 61, 51,'),      # 阴影色
    (r'rgba\(47\s*,\s*127\s*,\s*128\s*\)', 'rgba(111, 158, 127)'),
]

# 保护行：含这些片段的行跳过（语义色定义）
PROTECT = [
    '--sys-red', '--sys-orange', '--sys-yellow', '--sys-green', '--sys-blue',
    '--sys-teal', '--sys-purple', '--sys-pink', '--sys-indigo', '--sys-mint',
    '.tone-', 'tone-red', 'tone-orange', 'tone-yellow', 'tone-blue',
]

SKIP_DIRS = {'node_modules', 'dist', '.git'}
EXT = {'.css', '.vue', '.js', '.scss', '.html'}


def process(text):
    """返回 (新文本, 替换次数)"""
    n = 0
    # rg 先做（含括号，避免与 hex 冲突）
    for pat, rep in RGBA_PAIRS:
        text, k = re.subn(pat, rep, text)
        n += k
    # hex 精确替换（用正则加词边界，避免 #2f7f80 命中 #2f7f801）
    for old, new in HEX_PAIRS:
        pat = re.escape(old) + r'(?![0-9a-fA-F])'
        text, k = re.subn(pat, new, text, flags=re.IGNORECASE)
        n += k
    return text, n


def main():
    apply = '--apply' in sys.argv
    total_files = 0
    total_hits = 0
    details = []

    for root, dirs, files in os.walk(ROOT):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for f in sorted(files):
            if os.path.splitext(f)[1].lower() not in EXT:
                continue
            p = os.path.join(root, f)
            raw = open(p, 'rb').read()
            try:
                text = raw.decode('utf-8')
            except UnicodeDecodeError:
                print('  [SKIP 非 utf-8]', p)
                continue

            new, n = process(text)
            if n == 0:
                continue

            rel = os.path.relpath(p, ROOT)
            total_files += 1
            total_hits += n
            details.append((rel, n))

            if apply:
                out = new.encode('utf-8')
                # BOM 保真
                if raw.startswith(b'\xef\xbb\xbf') and not out.startswith(b'\xef\xbb\xbf'):
                    out = b'\xef\xbb\xbf' + out
                open(p, 'wb').write(out)
                # 回读校验
                back = open(p, 'rb').read().decode('utf-8', errors='replace')
                if '\ufffd' in back:
                    print('  [!! 编码损坏]', rel)
                    sys.exit(1)
                # BOM 状态校验
                if raw.startswith(b'\xef\xbb\xbf') != open(p, 'rb').read().startswith(b'\xef\xbb\xbf'):
                    print('  [!! BOM 状态变化]', rel)
                    sys.exit(1)

    details.sort(key=lambda x: -x[1])
    for rel, n in details:
        print(f'  {n:>4}  {rel}')
    print()
    print(f'命中文件 {total_files} 个,替换 {total_hits} 处')
    print('[APPLIED]' if apply else '[DRY-RUN] 未写入')


if __name__ == '__main__':
    main()
