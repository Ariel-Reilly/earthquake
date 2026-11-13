/**
 * B4-3 第二批：只格式化 Vue SFC 的 <style> 块与独立 .css 文件。
 *
 * 背景：`stylelint --fix` 无法修复 declaration-block-single-line-max-declarations
 * （单行多个声明，约 2,320 处），但 Prettier 的 CSS 格式化天然把每个声明放一行。
 * 本脚本不格式化模板/脚本，只对样式内容调用 prettier.format(parser='css')，
 * 避免全文件格式化带来的无关 diff 风险。
 *
 * 用法：node scripts/delivery/format-style-blocks.mjs [--write]
 *   默认 dry-run，只统计；--write 才写回文件。
 */
import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'

const require = createRequire(path.resolve(process.cwd(), 'frontend/package.json'))
const prettier = require('prettier')

const WRITE = process.argv.includes('--write')
const SRC = path.resolve('frontend/src')
const exts = new Set(['.vue', '.css'])

function walk(dir) {
  const out = []
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) out.push(...walk(full))
    else if (exts.has(path.extname(entry.name))) out.push(full)
  }
  return out
}

async function formatBlock(css) {
  const formatted = await prettier.format(css, {
    parser: 'css',
    printWidth: 120,
    tabWidth: 2,
  })
  // 保留原块的换行包裹，避免 <style> 与内容贴在一起
  const trimmed = formatted.replace(/\s+$/, '')
  return `\n${trimmed}\n`
}

async function main() {
  const files = walk(SRC)
  let changedFiles = 0
  let failed = []
  for (const file of files) {
    const original = fs.readFileSync(file, 'utf8')
    let next = original
    if (file.endsWith('.vue')) {
      const matches = [...original.matchAll(/(<style\b[^>]*>)([\s\S]*?)(<\/style>)/g)]
      if (matches.length === 0) continue
      const parts = []
      let cursor = 0
      for (const match of matches) {
        const [full, open, css, close] = match
        parts.push(original.slice(cursor, match.index))
        parts.push(open)
        try {
          parts.push(css.trim() ? await formatBlock(css) : css)
        } catch (err) {
          failed.push({ file, error: String(err?.message || err) })
          parts.push(css)
        }
        parts.push(close)
        cursor = match.index + full.length
      }
      parts.push(original.slice(cursor))
      next = parts.join('')
    } else {
      try {
        next = await formatBlock(original)
      } catch (err) {
        failed.push({ file, error: String(err?.message || err) })
        next = original
      }
    }
    if (next !== original) {
      changedFiles += 1
      if (WRITE) fs.writeFileSync(file, next, 'utf8')
    }
  }
  console.log(JSON.stringify({ write: WRITE, scanned: files.length, changedFiles, failed }, null, 2))
  if (failed.length > 0) process.exitCode = 1
}

main().catch((err) => {
  console.error(err)
  process.exit(1)
})
