/**
 * 前端相对路径 import 全量解析检查
 *
 * 背景：项目做过一次 "services/* -> services/_architecture/{core,domain}/*" 的目录重构，
 *      部分文件的相对路径没有跟着改，形成运行时才暴露的断链 import。
 *      由于这些 import 多为 `await import(...)` 动态导入，构建期不会被发现。
 *
 * 用法：cd frontend && node ../../scripts/fix/check-broken-imports.mjs
 * 退出码：0 = 无断链；1 = 发现断链
 */
import fs from 'node:fs';
import path from 'node:path';

const ROOT = 'src';
const SKIP_DIRS = ['node_modules', '.git', 'dist', '.vite'];
const EXT = ['.js', '.vue', '.mjs', '.json', '.css', '.scss'];

function walk(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (SKIP_DIRS.includes(entry.name)) continue;
      walk(p, out);
    } else if (/\.(js|vue|mjs)$/.test(entry.name)) {
      out.push(p);
    }
  }
  return out;
}

const files = walk(ROOT);
// 覆盖三种形式：静态 import/export ... from、动态 import()、require()
const RE = /(?:^|\s)(?:import|export)\s+(?:[\s\S]*?\sfrom\s+)?["']([^"']+)["']|import\(\s*["']([^"']+)["']\s*\)|require\(\s*["']([^"']+)["']\s*\)/g;

const broken = [];
const aliasHits = new Set();

function resolves(base) {
  if (fs.existsSync(base) && fs.statSync(base).isFile()) return true;
  return EXT.some((e) => fs.existsSync(base + e))
    || EXT.some((e) => fs.existsSync(path.join(base, 'index' + e)));
}

for (const file of files) {
  // 去掉注释再扫描：JSDoc 里常出现 `import * as mock from './mock'` 这类示例路径，
  // 它们不是真实依赖，包含进来会产生无法消除的误报。
  const src = fs.readFileSync(file, 'utf8')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\/\/.*$/gm, '');
  RE.lastIndex = 0;
  let m;
  while ((m = RE.exec(src))) {
    const spec = m[1] || m[2] || m[3];
    if (!spec) continue;
    if (spec.startsWith('@/')) { aliasHits.add(spec); continue; } // 走 vite alias，另行校验
    if (!spec.startsWith('.')) continue;                          // 裸包名交给打包器
    const base = path.resolve(path.dirname(file), spec);
    if (!resolves(base)) broken.push(`${file} -> ${spec}`);
  }
}

console.log(`扫描文件数: ${files.length}`);
console.log(`断链 import: ${broken.length}`);
for (const b of broken) console.log('  ! ' + b);
console.log(`@/ 别名引用: ${aliasHits.size} 种（需确认 vite alias 已配置）`);

process.exit(broken.length ? 1 : 0);
