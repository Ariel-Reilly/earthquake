/**
 * 将后端 PermissionRegistry 的角色→权限映射与前端 auth.js 的 ROLE_PERMISSION_MAP 对齐。
 *
 * 背景：后端 PermissionRegistry 只注册了 4 个角色（admin / attendant / expert / maintenance），
 * 而 sys_role 表与前端实际有 7 个角色。permissionsOf() 对未注册角色返回空集，
 * 意味着 system_admin / emergency_commander / ai_engineer 在后端没有任何权限。
 * 此前因为 Controller 普遍没有方法级鉴权，这个缺陷不显形；一旦补上 @PreAuthorize，
 * 这三个角色会被完全挡在门外。
 *
 * 做法：以前端 ROLE_PERMISSION_MAP 为权威源（它是 7 角色完整定义），
 * 角色名转为下划线形式（与 sys_role.role_code 一致）后生成 Java 源文件。
 *
 * 用法：node scripts/fix/sync-permission-registry.mjs
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const SRC = path.join(ROOT, "frontend/src/stores/auth.js");
const DST = path.join(
  ROOT,
  "backend/src/main/java/cn/disasterguard/common/security/PermissionRegistry.java",
);

const s = fs.readFileSync(SRC, "utf8");
const i = s.indexOf("export const ROLE_PERMISSION_MAP");
const j = s.indexOf("\n}", i);
const seg = s.slice(i, j);

let cur = null;
let buf = [];
const roles = [];
for (const line of seg.split("\n")) {
  const m = line.match(/^ {2}'?([a-z-]+)'?:\s*\[/);
  if (m) {
    if (cur) roles.push([cur, buf]);
    cur = m[1];
    buf = [];
    continue;
  }
  if (/^ {2}\],?$/.test(line) && cur) {
    roles.push([cur, buf]);
    cur = null;
    continue;
  }
  if (cur) buf.push(line.trim());
}
if (cur) roles.push([cur, buf]);

const parsed = roles.map(([role, b]) => [
  role.replace(/-/g, "_"),
  [...b.join(" ").matchAll(/'([a-z:]+)'/g)].map((m) => m[1]),
]);

if (parsed.length !== 7) {
  console.error(`期望解析出 7 个角色，实际 ${parsed.length} 个，已中止`);
  process.exit(1);
}

// 每行放 4 个权限码，保持可读
const fmt = (perms) => {
  const lines = [];
  for (let k = 0; k < perms.length; k += 4) {
    lines.push("          " + perms.slice(k, k + 4).map((p) => `"${p}"`).join(", ") + ",");
  }
  // 去掉最后一项多余的逗号
  lines[lines.length - 1] = lines[lines.length - 1].replace(/,$/, "");
  return lines.join("\n");
};

const body = parsed
  .map(([role, perms]) => `      "${role}", Set.of(\n${fmt(perms)}\n      )`)
  .join(",\n");

const out = `package cn.disasterguard.common.security;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 角色 → 权限码映射。
 *
 * <p>与前端 auth.js 的 ROLE_PERMISSION_MAP 保持一致，且角色名使用 sys_role.role_code
 * 的下划线形式（system_admin / emergency_commander / ai_engineer）。
 *
 * <p>注意：未在此处注册的角色，permissionsOf() 会返回空权限集。
 * 因此新增角色时必须同步更新本表，否则该角色的用户将被所有 @PreAuthorize 拒绝。
 * 本文件由 scripts/fix/sync-permission-registry.mjs 从前端定义生成。
 */
public final class PermissionRegistry {

  private static final Map<String, Set<String>> ROLE_PERMISSIONS = Map.of(
${body}
  );

  private PermissionRegistry() {
  }

  public static Set<String> permissionsOf(String role) {
    if (role == null) {
      return Set.of();
    }
    return ROLE_PERMISSIONS.getOrDefault(role.toLowerCase(), ROLE_PERMISSIONS.getOrDefault(role, Set.of()));
  }

  /** 供统一返回使用。 */
  public static List<String> permissionsListOf(String role) {
    return List.copyOf(permissionsOf(role));
  }

  /** 全部已注册角色（供角色管理页 / 权限矩阵页展示）。 */
  public static Set<String> roles() {
    return ROLE_PERMISSIONS.keySet();
  }

  /** 角色 → 权限码快照（排序后），供 PermissionRegistryController 输出矩阵。 */
  public static Map<String, List<String>> snapshot() {
    Map<String, List<String>> result = new java.util.LinkedHashMap<>();
    ROLE_PERMISSIONS.keySet().stream()
        .sorted()
        .forEach(role -> result.put(role, ROLE_PERMISSIONS.get(role).stream().sorted().toList()));
    return result;
  }

  /** 所有角色权限码的并集（去重 + 排序）。 */
  public static List<String> allPermissions() {
    return ROLE_PERMISSIONS.values().stream()
        .flatMap(Set::stream)
        .distinct()
        .sorted()
        .toList();
  }
}
`;

fs.writeFileSync(DST, out, "utf8");
console.log("已同步 PermissionRegistry，角色数：" + parsed.length);
parsed.forEach(([r, p]) => console.log(`  ${r}: ${p.length} 项`));
