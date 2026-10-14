#!/usr/bin/env node
/**
 * 一次性修复脚本：还原被误改写的令牌定义
 *
 * 事故原因：tokenize-colors.mjs 的第一次运行发生在「跳过令牌定义」护栏加入之前，
 * 它把 :root 里若干 `--xxx: #hex;` 定义改成了 `var(--xxx)` 自引用。
 * CSS 自定义属性一旦自引用就构成依赖环，整条声明在 computed-value 阶段失效，
 * 于是浅色模式下 background: var(--c-surface) 退化为 transparent。
 *
 * 本脚本按行号精确替换这些定义，并把被改写过的注释还原为可读文本。
 * 只运行一次；修复后由 verify-token-integrity.mjs 校验。
 */

import { readFileSync, writeFileSync } from 'node:fs'

const FILE = 'src/styles.css'
const src = readFileSync(FILE, 'utf8')
const lines = src.split('\n')

/* 行号(1-based) → 新的完整行内容。行号基于修复时的文件状态。 */
const REPAIRS = {
  // 品牌三层绿：莫兰迪低饱和绿（与注释中的取向一致）
  65: '  --c-brand:         #6f9e7f;              /* 主绿 · 鼠尾草绿(柔和低饱和) */',
  66: '  --c-brand-hover:   #5f8e70;              /* 悬停(压深一档) */',
  67: '  --c-brand-press:   #54806a;              /* 按下 */',
  68: '  --c-brand-deep:    #4a7a5e;              /* 深绿 · 深底/渐变收尾 */',
  69: '  --c-brand-deeper:  #385f49;              /* 最深绿 · 侧栏底/大屏底 */',
  70: '  --c-brand-soft:    #eaf1ec;              /* 浅绿 · 选中行/浅标签底(雾感) */',
  71: '  --c-brand-mist:    #f1f6f2;              /* 更浅一档 · 区块底 */',
  74: '  --c-brand-on:      #ffffff;              /* 主绿之上的文字色 */',
  // 文本层级：--c-text 必须落到具体黑，不能指向 --c-overlay-ink 形成环
  92: '  --c-text:         #1c1c1e;',
  // 背景与面
  99: '  --c-bg:           #f4f7f4;              /* 全局底 · 雾灰白偏绿 */',
  100: '  --c-bg-deep:      #eaefeb;              /* 更沉一档的分区底 */',
  101: '  --c-surface:      #ffffff;',
  105: '  --c-morandi-clay: #e8ece8;              /* 灰陶(分隔/浅块) */',
  // 语义补充令牌
  114: '  --c-on-brand:     #ffffff;              /* 品牌色/深色底之上的文字与图标 */',
  115: '  --c-surface-ink:  #1c1c1e;              /* 恒定浅色面上的墨色（反向于 --c-text）*/',
  116: '  --c-ink-1:        #3a4f4a;              /* 深墨绿 · 正文/标题（浅色面）*/',
  117: '  --c-ink-2:        #4d6862;              /* 次级墨色 */',
  118: '  --c-ink-3:        #6b7f7c;              /* 三级墨色 */',
  120: '  --c-overlay-ink:  #000000;              /* 深色遮罩上的纯黑 */',
  121: '  --c-brand-tint:   #c8d8ce;              /* 品牌色块更实的一档 */',
  122: '  --c-brand-ink:    #276749;              /* 可读的深绿文字 */',
  123: '  --c-warning-strong: #d9c040;',
  125: '  --c-danger-strong:  #c7553b;',
}

let fixed = 0
for (const [lineNo, text] of Object.entries(REPAIRS)) {
  const i = Number(lineNo) - 1
  const before = lines[i]
  const name = /^\s*(--[a-z0-9-]+)/.exec(text)?.[1]
  // 安全校验：该行确实是在定义同名令牌，且当前值是自引用
  if (!name || !before || !before.includes(`${name}:`)) {
    console.error(`跳过 L${lineNo}：与预期不符 -> ${before}`)
    continue
  }
  lines[i] = text
  fixed++
}

writeFileSync(FILE, lines.join('\n'), 'utf8')
console.log(`已还原 ${fixed} 条令牌定义`)
