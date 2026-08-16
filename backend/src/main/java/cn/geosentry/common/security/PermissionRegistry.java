package cn.geosentry.common.security;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 角色 → 权限码映射，与前端 auth.js 的 ROLE_PERMISSION_MAP 保持一致。 */
public final class PermissionRegistry {

  private static final Map<String, Set<String>> ROLE_PERMISSIONS = Map.of(
      "admin", Set.of(
          "overview:view", "overview:export",
          "hazards:view", "hazards:create", "hazards:edit", "hazards:delete", "hazards:export",
          "tasks:view", "tasks:create", "tasks:edit", "tasks:delete", "tasks:export",
          "devices:view", "devices:create", "devices:edit", "devices:delete", "devices:export",
          "warnings:view", "warnings:confirm", "warnings:dispatch", "warnings:close", "warnings:export",
          "emergency:view", "emergency:dispatch", "emergency:close", "emergency:export",
          "analytics:view", "analytics:export", "settings:view"
      ),
      "attendant", Set.of(
          "overview:view", "overview:export",
          "hazards:view", "hazards:create", "hazards:edit", "hazards:export",
          "tasks:view", "tasks:create", "tasks:edit", "tasks:export",
          "warnings:view", "warnings:confirm", "warnings:dispatch", "warnings:close", "warnings:export",
          "emergency:view", "emergency:dispatch", "emergency:close",
          "analytics:view", "analytics:export", "settings:view"
      ),
      "maintenance", Set.of(
          "overview:view", "overview:export",
          "hazards:view", "hazards:export",
          "devices:view", "devices:create", "devices:edit", "devices:delete", "devices:export",
          "analytics:view", "analytics:export", "settings:view"
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
