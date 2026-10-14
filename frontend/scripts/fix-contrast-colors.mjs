#!/usr/bin/env node
/**
 * 补丁：把 styles.css 里剩余的「写死浅色底 + 写死深色字」收敛到令牌
 *
 * 为什么单列成脚本：这批值原本是「浅色页面上勉强够看」的手调色，
 * 但在深色主题下不会跟随翻转，于是形成浅底浅字（对比度 2.2~2.9:1）。
 * 它们都是成对出现的（前景+同色系浅底），恰好能一对一映射到语义令牌，
 * 映射后深浅两套主题都会自动取到合适的取值。
 *
 * 只运行一次；之后由 verify-token-integrity.mjs 与 audit-contrast.mjs 校验。
 */

import { readFileSync, writeFileSync } from 'node:fs'

const FILE = 'src/styles.css'
let src = readFileSync(FILE, 'utf8')

/* 顺序有意义：先长后短，避免 #f8f0ea 被 #f8f0e 之类的短前缀误伤（本项目无 4 位 hex，仍按长度降序稳妥处理） */
const MAP = [
  // 琥珀/黄色系
  ['#bda06a', 'var(--c-warning-ink)'],
  ['#fff4dc', 'var(--c-warning-bg)'],
  // 蓝色系（信息态）
  ['#8aa3bd', 'var(--sys-blue)'],
  ['#eaf2fb', 'var(--sys-blue-soft)'],
  ['#7f96b8', 'var(--sys-blue)'],
  ['#eef1f6', 'var(--sys-blue-soft)'],
  // 紫色系
  ['#9387b5', 'var(--sys-purple)'],
  ['#f2f0f7', 'var(--sys-purple-soft)'],
  // 橙色/警示系
  ['#c4836f', 'var(--c-warning-strong)'],
  ['#c08a6a', 'var(--c-warning-strong)'],
  ['#f8f0ea', 'var(--c-warning-bg)'],
  ['#feede7', 'var(--c-warning-bg)'],
  // 离线/次级灰绿（文字与底成对）
  ['#8b9a97', 'var(--c-text-3)'],
  ['#eef2f0', 'var(--c-morandi-clay)'],
]

let total = 0
for (const [from, to] of MAP) {
  const re = new RegExp(from.replace('#', '#'), 'gi')
  const n = (src.match(re) ?? []).length
  if (!n) continue
  src = src.replace(re, to)
  total += n
  console.log(`  ${from} → ${to}   ×${n}`)
}

writeFileSync(FILE, src, 'utf8')
console.log(`共替换 ${total} 处`)
