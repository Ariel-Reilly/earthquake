/**
 * 静态检查：源文件里用到了注解/类型，但缺少对应 import。
 *
 * 背景：批量脚本（add-missing-controller-auth.mjs 等）给 40+ 个 Controller 插入
 *      @PreAuthorize 时只写注解、不写 import，有两个文件因此编译不过。
 *      更麻烦的是 Maven 增量编译只重编改动文件，一次 `BUILD SUCCESS` 并不代表
 *      整个模块能编译——必须 touch 全部源文件再编一次才算数。
 *
 * 用法：node scripts/audit/check-missing-imports.mjs
 * 退出码：0 = 无缺失；1 = 发现缺失
 */
import fs from 'node:fs';
import path from 'node:path';

const ROOT = 'backend/src/main/java';

// 简化类型 → 需要的 import（按需扩展）
const REQUIRED = [
  { fqn: 'org.springframework.security.access.prepost.PreAuthorize', simple: 'PreAuthorize' },
  { fqn: 'org.springframework.security.access.prepost.PostAuthorize', simple: 'PostAuthorize' },
  { fqn: 'org.springframework.web.bind.annotation.GetMapping', simple: 'GetMapping' },
  { fqn: 'org.springframework.web.bind.annotation.PostMapping', simple: 'PostMapping' },
  { fqn: 'org.springframework.web.bind.annotation.PutMapping', simple: 'PutMapping' },
  { fqn: 'org.springframework.web.bind.annotation.DeleteMapping', simple: 'DeleteMapping' },
  { fqn: 'org.springframework.web.bind.annotation.PatchMapping', simple: 'PatchMapping' },
  { fqn: 'org.springframework.transaction.annotation.Transactional', simple: 'Transactional' },
];

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.endsWith('.java')) out.push(p);
  }
  return out;
}

const files = walk(ROOT);
const problems = [];

for (const file of files) {
  const src = fs.readFileSync(file, 'utf8');
  // 去掉块注释与行注释，避免 JSDoc 里提到注解名造成误报
  const code = src
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\/\/.*$/gm, '');
  for (const { fqn, simple } of REQUIRED) {
    const used = new RegExp(`@${simple}\\b`).test(code);
    if (!used) continue;
    // 精确导入 或 所在包的通配导入，均视为已导入
    const pkg = fqn.slice(0, fqn.lastIndexOf('.'));
    const exact = new RegExp(`^\\s*import\\s+${fqn.replace(/\./g, '\\.')}\\s*;`, 'm').test(src);
    const wildcard = new RegExp(`^\\s*import\\s+${pkg.replace(/\./g, '\\.')}\\.\\*\\s*;`, 'm').test(src);
    if (!exact && !wildcard) problems.push(`${file}: 使用了 @${simple} 但缺少 import ${fqn};`);
  }
}

console.log(`扫描 Java 文件: ${files.length}`);
console.log(`缺失 import: ${problems.length}`);
for (const p of problems) console.log('  ! ' + p);

process.exit(problems.length ? 1 : 0);
