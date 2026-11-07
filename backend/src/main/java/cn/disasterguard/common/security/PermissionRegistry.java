package cn.disasterguard.common.security;

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
      "admin", Set.of(
          "overview:view", "overview:export", "map:view", "map:export",
          "map:edit", "zone:view", "ai:task:view", "ai:task:create",
          "ai:task:edit", "ai:task:delete", "ai:task:retry", "ai:task:terminate",
          "ai:result:view", "ai:result:confirm", "ai:result:transfer", "ai:result:review",
          "ai:insight:view", "ai:insight:refresh", "ai:forecast:view", "ai:model:view",
          "ai:model:manage", "ai:model:edit", "hazards:view", "hazards:create",
          "hazards:edit", "hazards:delete", "hazards:export", "confirmation:view",
          "confirmation:claim", "confirmation:confirm", "confirmation:reject", "confirmation:transfer",
          "inspection:view", "inspection:submit", "personnel:view", "personnel:report",
          "approval:view", "approval:sign", "approval:initiate", "approval:approve",
          "approval:reject", "events:view", "events:transition", "events:reopen",
          "warnings:view", "warnings:confirm", "warnings:dispatch", "warnings:close",
          "warnings:create", "warnings:export", "warnings:resend", "notice:view",
          "notice:edit", "emergency:view", "emergency:start", "emergency:dispatch",
          "emergency:close", "emergency:export", "resource:view", "resource:dispatch",
          "resource:assign", "resource:create", "resource:edit", "devices:view",
          "devices:create", "devices:edit", "devices:delete", "devices:export",
          "devices:control", "devices:ota", "datasource:view", "datasource:config",
          "datasource:create", "datasource:edit", "datasource:delete", "sync:view",
          "sync:manage", "analytics:view", "analytics:export", "users:view",
          "users:create", "users:edit", "users:delete", "roles:view",
          "roles:edit", "users:approve", "rules:view", "rules:edit",
          "rules:create", "rules:delete", "audit:view", "audit:export",
          "monitor:view", "settings:view", "settings:edit", "messages:view",
          "messages:send", "todo:view", "route:view", "route:plan",
          "route:approve", "route:export", "sign:view", "sign:create",
          "sign:confirm", "profile:view", "profile:edit", "data:catalog:view"
      ),
      "attendant", Set.of(
          "overview:view", "overview:export", "map:view", "map:edit",
          "zone:view", "ai:task:view", "ai:task:create", "ai:result:view",
          "ai:result:transfer", "ai:insight:view", "ai:insight:refresh", "ai:forecast:view",
          "ai:model:view", "hazards:view", "hazards:create", "hazards:edit",
          "confirmation:view", "confirmation:claim", "confirmation:confirm", "confirmation:reject",
          "confirmation:transfer", "inspection:view", "inspection:submit", "personnel:view",
          "personnel:report", "warnings:view", "warnings:confirm", "warnings:dispatch",
          "warnings:create", "warnings:export", "events:view", "events:transition",
          "notice:view", "notice:edit", "emergency:view", "emergency:dispatch",
          "devices:view", "weather:view", "analytics:view", "approval:view",
          "approval:initiate", "messages:view", "todo:view", "settings:view",
          "route:view", "route:export", "sign:view", "sign:create",
          "profile:view", "profile:edit"
      ),
      "expert", Set.of(
          "overview:view", "zone:view", "hazards:view", "hazards:create",
          "hazards:edit", "ai:task:view", "ai:insight:view", "ai:result:view",
          "ai:result:review", "ai:forecast:view", "ai:model:view", "confirmation:view",
          "confirmation:claim", "confirmation:confirm", "confirmation:reject", "confirmation:transfer",
          "inspection:view", "personnel:view", "approval:view", "approval:sign",
          "approval:approve", "approval:reject", "events:view", "warnings:view",
          "warnings:dispatch", "data:catalog:view", "emergency:view", "emergency:dispatch",
          "rules:view", "analytics:view", "messages:view", "todo:view",
          "settings:view", "route:view", "sign:view", "sign:create",
          "sign:confirm", "profile:view", "profile:edit"
      ),
      "maintenance", Set.of(
          "overview:view", "map:view", "zone:view", "hazards:view",
          "devices:view", "devices:create", "devices:edit", "devices:delete",
          "devices:export", "devices:control", "devices:ota", "datasource:view",
          "sync:view", "data:catalog:view", "weather:view", "weather:refresh",
          "analytics:view", "emergency:view", "emergency:dispatch", "resource:view",
          "route:view", "inspection:view", "inspection:submit", "personnel:view",
          "personnel:report", "monitor:view", "messages:view", "todo:view",
          "settings:view", "profile:view", "profile:edit"
      ),
      "system_admin", Set.of(
          "overview:view", "overview:export", "analytics:view", "analytics:export",
          "users:view", "users:create", "users:edit", "users:delete",
          "roles:view", "roles:edit", "audit:view", "audit:export",
          "monitor:view", "sync:view", "settings:view", "settings:edit",
          "messages:view", "messages:send", "todo:view", "profile:view",
          "profile:edit"
      ),
      "emergency_commander", Set.of(
          "overview:view", "overview:export", "map:view", "map:edit",
          "zone:view", "ai:task:view", "ai:result:view", "ai:forecast:view",
          "hazards:view", "hazards:create", "hazards:edit", "confirmation:view",
          "inspection:view", "personnel:view", "events:view", "events:transition",
          "events:reopen", "sign:view", "sign:create", "sign:confirm",
          "warnings:view", "warnings:dispatch", "warnings:close", "warnings:create",
          "warnings:export", "warnings:resend", "notice:view", "emergency:view",
          "emergency:start", "emergency:dispatch", "emergency:close", "resource:view",
          "resource:dispatch", "resource:assign", "resource:create", "resource:edit",
          "route:view", "route:plan", "route:approve", "route:export",
          "devices:view", "approval:view", "approval:approve", "approval:reject",
          "analytics:view", "analytics:export", "profile:view", "profile:edit"
      ),
      "ai_engineer", Set.of(
          "overview:view", "analytics:view", "ai:task:view", "ai:task:create",
          "ai:task:edit", "ai:task:delete", "ai:task:retry", "ai:task:terminate",
          "ai:result:view", "ai:result:transfer", "ai:model:view", "ai:model:manage",
          "ai:model:edit", "ai:insight:view", "ai:insight:refresh", "ai:forecast:view",
          "rules:view", "rules:edit", "rules:create", "rules:delete",
          "datasource:view", "datasource:config", "datasource:create", "datasource:edit",
          "datasource:delete", "sync:view", "sync:manage", "data:catalog:view",
          "monitor:view", "messages:view", "todo:view", "profile:view",
          "profile:edit"
      )
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
