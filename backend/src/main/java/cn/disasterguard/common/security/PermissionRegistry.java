package cn.disasterguard.common.security;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 角色 → 权限码映射，与前端 auth.js 的 ROLE_PERMISSION_MAP 保持一致。 */
public final class PermissionRegistry {

  private static final Map<String, Set<String>> ROLE_PERMISSIONS = Map.of(
      "admin", Set.of(
          "overview:view", "overview:export",
          "map:view", "map:export",
          "ai:task:view", "ai:task:create", "ai:task:edit", "ai:task:delete",
          "ai:result:view", "ai:result:confirm",
          "ai:insight:view", "ai:insight:refresh",
          "ai:model:view", "ai:model:manage",
          "hazards:view", "hazards:create", "hazards:edit", "hazards:delete", "hazards:export",
          "confirmation:view", "confirmation:claim", "confirmation:confirm", "confirmation:reject", "confirmation:transfer",
          "approval:view", "approval:sign",
          "events:view", "events:transition",
          "tasks:view", "tasks:create", "tasks:edit", "tasks:delete", "tasks:export",
          "warnings:view", "warnings:confirm", "warnings:dispatch", "warnings:close", "warnings:create", "warnings:export",
          "notice:view", "notice:edit",
          "plans:view", "plans:edit", "plans:launch",
          "emergency:view", "emergency:dispatch", "emergency:close", "emergency:export",
          "resource:view", "resource:dispatch",
          "devices:view", "devices:create", "devices:edit", "devices:delete", "devices:export", "devices:control", "devices:ota",
          "datasource:view", "datasource:config",
          "analytics:view", "analytics:export",
          "history:view", "history:export",
          "users:view", "users:create", "users:edit", "users:delete",
          "roles:view", "roles:edit",
          "rules:view", "rules:edit",
          "audit:view", "audit:export",
          "monitor:view",
          "backup:view", "backup:manage",
          "messages:view", "messages:send",
          "todo:view",
          "reports:view", "reports:create", "reports:delete",
          "data:catalog:view",
          "settings:view", "settings:edit"
      ),
      "attendant", Set.of(
          "overview:view", "overview:export",
          "map:view",
          "ai:task:view", "ai:task:create",
          "ai:insight:view", "ai:insight:refresh",
          "hazards:view", "hazards:create", "hazards:edit",
          "confirmation:view", "confirmation:claim", "confirmation:confirm", "confirmation:reject", "confirmation:transfer",
          "tasks:view", "tasks:create",
          "warnings:view", "warnings:confirm", "warnings:dispatch", "warnings:close", "warnings:export",
          "notice:view", "notice:edit",
          "plans:view", "plans:edit", "plans:launch",
          "emergency:view", "emergency:dispatch",
          "devices:view",
          "weather:view",
          "analytics:view",
          "messages:view",
          "todo:view",
          "settings:view"
      ),
      "expert", Set.of(
          "overview:view",
          "hazards:view", "hazards:create",
          "confirmation:view", "confirmation:claim", "confirmation:confirm", "confirmation:reject", "confirmation:transfer",
          "approval:view", "approval:sign",
          "events:view", "events:transition",
          "ai:insight:view",
          "ai:model:view",
          "plans:view",
          "data:catalog:view",
          "emergency:view", "emergency:dispatch",
          "messages:view",
          "todo:view",
          "settings:view"
      ),
      "maintenance", Set.of(
          "overview:view", "overview:export",
          "hazards:view", "hazards:export",
          "devices:view", "devices:create", "devices:edit", "devices:delete", "devices:export", "devices:control", "devices:ota",
          "datasource:view", "datasource:config",
          "data:catalog:view",
          "weather:view",
          "analytics:view",
          "monitor:view",
          "backup:view",
          "messages:view",
          "todo:view",
          "settings:view"
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
}
