/**
 * 为 BackupController 的端点补充 @PreAuthorize 方法级鉴权。
 *
 * 背景：SecurityConfig 仅配置 anyRequest().authenticated()（只校验"已登录"），
 * 而该 Controller 的 9 个端点全部没有方法级鉴权 —— 任意登录用户都能触发
 * 「恢复备份」/「执行备份策略」等高危操作。
 *
 * 权限码取自 PermissionRegistry：backup:view（读） / backup:manage（写）。
 *
 * 用法：node scripts/fix/add-controller-preauthorize.mjs
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const TARGET = path.join(
  ROOT,
  "backend/src/main/java/cn/disasterguard/backup/BackupController.java",
);

// 行号(1-based, 修改前) -> 权限码
const AUTH = {
  97: "backup:view", // GET  /records
  125: "backup:manage", // POST /records
  147: "backup:manage", // POST /records/{id}/restore
  171: "backup:manage", // DELETE /records/{id}
  180: "backup:view", // GET  /restores
  195: "backup:view", // GET  /strategies
  200: "backup:manage", // POST /strategies
  220: "backup:manage", // DELETE /strategies/{id}
  229: "backup:manage", // POST /strategies/{id}/run
};

if (!fs.existsSync(TARGET)) {
  console.error("未找到目标文件：" + TARGET);
  process.exit(1);
}

const lines = fs.readFileSync(TARGET, "utf8").split("\n");

// 从后往前插入，避免行号漂移
const sorted = Object.entries(AUTH)
  .map(([k, v]) => [Number(k), v])
  .sort((a, b) => b[0] - a[0]);

let inserted = 0;
for (const [lineNo, perm] of sorted) {
  const idx = lineNo - 1;
  const line = lines[idx];
  if (!/@(Get|Post|Put|Delete)Mapping/.test(line)) {
    console.error(`第 ${lineNo} 行不是映射注解，跳过：${line.trim()}`);
    process.exit(1);
  }
  // 已存在鉴权注解则不重复添加
  if ((lines[idx + 1] ?? "").includes("@PreAuthorize")) {
    continue;
  }
  const indent = (line.match(/^\s*/) ?? [""])[0];
  lines.splice(idx + 1, 0, `${indent}@PreAuthorize("hasAuthority('${perm}')")`);
  inserted += 1;
}

// 补 import
const text = lines.join("\n");
if (!text.includes("import org.springframework.security.access.prepost.PreAuthorize")) {
  const out = text.replace(
    /^import org\.springframework\.security\S*$/m,
    (m) => `import org.springframework.security.access.prepost.PreAuthorize;\n${m}`,
  );
  if (out === text) {
    // 没有既有 security import，插到最后一个 import 之后
    const arr = out.split("\n");
    let last = -1;
    arr.forEach((l, i) => {
      if (l.startsWith("import ")) last = i;
    });
    arr.splice(last + 1, 0, "import org.springframework.security.access.prepost.PreAuthorize;");
    fs.writeFileSync(TARGET, arr.join("\n"), "utf8");
  } else {
    fs.writeFileSync(TARGET, out, "utf8");
  }
} else {
  fs.writeFileSync(TARGET, text, "utf8");
}

console.log(`已为 BackupController 补充 ${inserted} 处 @PreAuthorize`);
