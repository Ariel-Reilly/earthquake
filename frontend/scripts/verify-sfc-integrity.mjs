#!/usr/bin/env node
/**
 * verify-sfc-integrity.mjs —— 全量 .vue 单文件组件编译校验
 *
 * 为什么需要它（这是本项目一个真实的门禁漏洞）：
 *   `npm run lint` 用的 eslint.config.js 只声明了 JS 的 files 段
 *   （`**` 加 `/` 加 `*.js` 这类写法），**没有任何 vue pattern** ——
 *   ESLint 会把全部 .vue 以 "File ignored because no matching
 *   configuration was supplied" 跳过，退出码 0。
 *   也就是说 lint 绿 ≠ .vue 没问题。
 *   而 `vite build` 只编译路由可达的组件，未挂载的组件即使语法损坏也发现不了。
 *
 *   本脚本用 @vue/compiler-sfc 对 src 下**每一个** .vue 做
 *   parse → compileScript → compileTemplate → compileStyle，
 *   覆盖全量、且不依赖应用入口。
 *
 * 用法：
 *   node scripts/verify-sfc-integrity.mjs
 *   node scripts/verify-sfc-integrity.mjs --json
 * 退出码：0 全部通过，1 有失败（可用于 CI 门禁）
 */

import { readFileSync, readdirSync } from 'node:fs'
import path from 'node:path'
import { parse, compileScript, compileTemplate, compileStyle } from '@vue/compiler-sfc'

const ROOT = process.cwd()
const AS_JSON = process.argv.includes('--json')

function listVue(dir, out = []) {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, e.name)
    if (e.isDirectory()) {
      if (e.name === 'node_modules' || e.name.startsWith('.')) continue
      listVue(full, out)
    } else if (e.name.endsWith('.vue')) out.push(full)
  }
  return out
}

const results = []
for (const file of listVue(path.join(ROOT, 'src'))) {
  const rel = path.relative(ROOT, file).replace(/\\/g, '/')
  const errors = []
  try {
    const source = readFileSync(file, 'utf8')
    const { descriptor, errors: parseErrors } = parse(source, { filename: file })
    for (const e of parseErrors ?? []) errors.push(`parse: ${e.message}`)

    const id = rel.replace(/[^a-z0-9]/gi, '_')
    if (descriptor.script || descriptor.scriptSetup) {
      try {
        compileScript(descriptor, { id })
      } catch (e) {
        errors.push(`script: ${e.message}`)
      }
    }
    if (descriptor.template) {
      const t = compileTemplate({
        source: descriptor.template.content,
        filename: file,
        id,
      })
      for (const e of t.errors ?? []) errors.push(`template: ${typeof e === 'string' ? e : e.message}`)
    }
    for (const [i, style] of (descriptor.styles ?? []).entries()) {
      try {
        const s = compileStyle({
          source: style.content,
          filename: file,
          id: `data-v-${id}`,
          scoped: style.scoped,
        })
        for (const e of s.errors ?? []) errors.push(`style[${i}]: ${e.message ?? e}`)
      } catch (e) {
        errors.push(`style[${i}]: ${e.message}`)
      }
    }
  } catch (e) {
    errors.push(`read: ${e.message}`)
  }
  results.push({ file: rel, errors })
}

const failed = results.filter((r) => r.errors.length)

if (AS_JSON) {
  console.log(JSON.stringify({ total: results.length, failed: failed.length, results: failed }, null, 2))
} else {
  console.log('═══ .vue 单文件组件编译校验 ═══')
  console.log(`  扫描 ${results.length} 个 .vue`)
  console.log(`  通过 ${results.length - failed.length}，失败 ${failed.length}`)
  for (const r of failed) {
    console.log(`\n  ✗ ${r.file}`)
    for (const e of r.errors.slice(0, 5)) console.log(`      ${e}`)
  }
  console.log(failed.length ? '\n❌ 存在无法编译的组件' : '\n✅ 全部组件解析、脚本、模板、样式均通过')
}

process.exit(failed.length ? 1 : 0)
