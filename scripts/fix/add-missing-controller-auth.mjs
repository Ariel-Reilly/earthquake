/**
 * 为缺少方法级鉴权的 Controller 端点批量补充 @PreAuthorize。
 *
 * 背景：SecurityConfig 只配置了 anyRequest().authenticated()（仅校验"已登录"），
 * 45 个 Controller 中大量端点没有任何角色/权限校验，任意登录用户可访问全部业务接口。
 *
 * 策略：
 *   - GET    -> <domain>:view
 *   - POST   -> <domain>:edit / :create（取 PermissionRegistry 中实际存在的那个）
 *   - PUT/PATCH -> <domain>:edit
 *   - DELETE -> <domain>:delete（不存在则回退到 :edit / :view）
 *   已存在 @PreAuthorize 的方法一律跳过，保留人工设定的细粒度权限（如 warnings:confirm）。
 *
 * 安全护栏：只写入 PermissionRegistry 中**真实存在**的权限码。写入不存在的权限码
 * 会导致所有角色都被拒绝（没有任何角色被授予该权限），反而造成全员功能失效。
 *
 * 用法：node scripts/fix/add-missing-controller-auth.mjs
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const CTRL_DIR = path.join(ROOT, "backend/src/main/java/cn/disasterguard");
const REGISTRY = path.join(CTRL_DIR, "common/security/PermissionRegistry.java");

// 从 PermissionRegistry 提取所有真实存在的权限码
const regSrc = fs.readFileSync(REGISTRY, "utf8");
const KNOWN = new Set([...regSrc.matchAll(/"([a-z:]+:[a-z]+)"/g)].map((m) => m[1]));

// Controller -> 权限域（用于拼接 :view / :edit / :delete）
const DOMAIN = {
  AiController: "ai:insight",
  AnalyticsController: "analytics",
  ApprovalCenterController: "approval",
  AccountSecurityController: "settings",
  UserProfileController: "settings",
  ConfirmationQueueController: "confirmation",
  SignController: "confirmation",
  DashboardController: "overview",
  ZoneDashboardController: "overview",
  DeviceController: "devices",
  DeviceTrackController: "devices",
  OtaController: "devices",
  PlanController: "plans",
  ResourceController: "resource",
  PathPlanningController: "resource",
  PersonnelController: "resource",
  EventStateController: "events",
  CatalogController: "data:catalog",
  DataSourceController: "datasource",
  SyncController: "datasource",
  HazardController: "hazards",
  SystemMonitorController: "monitor",
  MessageController: "messages",
  MessageStatsController: "messages",
  NotificationController: "notice",
  ReportController: "reports",
  RuleEngineController: "rules",
  TodoCenterController: "todo",
  TaskController: "tasks",
  WarningController: "warnings",
  WarningDeliveryController: "warnings",
  EmergencyController: "emergency",
  AttachmentController: "hazards",
  InspectionController: "inspection",
  InspectionReportController: "inspection",
};

// 这些 Controller 的端点由 SecurityConfig 显式 permitAll，或属认证流程，不在此处加注解
const SKIP = new Set([
  "AuthController",
  "HealthController",
  "SentinelController",
  "WeatherController",
]);

function permFor(domain, httpMethod) {
  const cands =
    httpMethod === "GET"
      ? [`${domain}:view`]
      : httpMethod === "DELETE"
        ? [`${domain}:delete`, `${domain}:edit`, `${domain}:submit`, `${domain}:view`]
        : [`${domain}:edit`, `${domain}:create`, `${domain}:submit`, `${domain}:view`];
  return cands.find((c) => KNOWN.has(c)) ?? null;
}

function collectControllers(dir) {
  const out = [];
  (function walk(d) {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      const p = path.join(d, e.name);
      if (e.isDirectory()) walk(p);
      else if (e.name.endsWith("Controller.java")) out.push(p);
    }
  })(dir);
  return out;
}

let totalInserted = 0;
let totalSkipped = 0;
const unresolved = [];

for (const file of collectControllers(CTRL_DIR)) {
  const name = path.basename(file, ".java");
  if (SKIP.has(name)) {
    continue;
  }
  const domain = DOMAIN[name];
  if (!domain) {
    unresolved.push(`${name}: 未配置权限域映射`);
    continue;
  }

  const lines = fs.readFileSync(file, "utf8").split("\n");
  // 记录待插入位置：{ idx, perm }（idx = mapping 注解所在行）
  const inserts = [];
  for (let i = 0; i < lines.length; i++) {
    const m = lines[i].match(/^\s*@(Get|Post|Put|Patch|Delete)Mapping\b/);
    if (!m) continue;
    const httpMethod = m[1].toUpperCase() === "PATCH" ? "PUT" : m[1].toUpperCase();
    // 检查紧邻的上下几行是否已有鉴权注解
    const near = [lines[i - 1], lines[i + 1], lines[i + 2]].filter(Boolean).join("\n");
    if (near.includes("@PreAuthorize")) {
      totalSkipped += 1;
      continue;
    }
    const perm = permFor(domain, httpMethod);
    if (!perm) {
      unresolved.push(`${name}:${i + 1} ${httpMethod} 无可用权限码 (domain=${domain})`);
      continue;
    }
    inserts.push({ idx: i, perm });
  }
  if (!inserts.length) continue;

  // 从后往前插入，避免行号漂移
  inserts.sort((a, b) => b.idx - a.idx);
  for (const { idx, perm } of inserts) {
    const indent = (lines[idx].match(/^\s*/) ?? [""])[0];
    lines.splice(idx + 1, 0, `${indent}@PreAuthorize("hasAuthority('${perm}')")`);
    totalInserted += 1;
  }

  let text = lines.join("\n");
  if (!text.includes("prepost.PreAuthorize")) {
    const arr = text.split("\n");
    let last = -1;
    arr.forEach((l, i) => {
      if (l.startsWith("import ")) last = i;
    });
    arr.splice(last + 1, 0, "import org.springframework.security.access.prepost.PreAuthorize;");
    text = arr.join("\n");
  }
  fs.writeFileSync(file, text, "utf8");
}

console.log(`新增 @PreAuthorize：${totalInserted} 处`);
console.log(`已有鉴权跳过：${totalSkipped} 处`);
if (unresolved.length) {
  console.log("\n未能处理（需人工补充权限定义或映射）：");
  unresolved.forEach((u) => console.log("  " + u));
}
