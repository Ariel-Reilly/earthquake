package cn.disasterguard.system;

import cn.disasterguard.common.api.ApiResponse;
import cn.disasterguard.common.security.PermissionRegistry;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 角色管理（v1.7.0+ 静态角色列表）。
 *
 * <p>前端 dashboardApi.js 依赖：{@code GET /api/roles}.
 *
 * <p>当前无独立角色表，返回预定义静态角色列表。
 * 后续接入 RBAC 表后替换为数据库查询。
 */
@RestController
@RequestMapping("/api/roles")
public class RoleController {

  private static final List<Map<String, Object>> ROLES = Arrays.asList(
      Map.of(
          "id", 1,
          "name", "系统管理员",
          "code", "admin",
          "description", "系统管理员，拥有全部权限"
      ),
      Map.of(
          "id", 2,
          "name", "运维管理员",
          "code", "system_admin",
          "description", "账号、审计、监控、备份与系统设置"
      ),
      Map.of(
          "id", 3,
          "name", "应急指挥员",
          "code", "emergency_commander",
          "description", "应急指挥、预警发布、资源调度与路径审批"
      ),
      Map.of(
          "id", 4,
          "name", "值守人员",
          "code", "attendant",
          "description", "日常值班、隐患上报与确认"
      ),
      Map.of(
          "id", 5,
          "name", "专家",
          "code", "expert",
          "description", "会签评审、研判复核"
      ),
      Map.of(
          "id", 6,
          "name", "AI 工程师",
          "code", "ai_engineer",
          "description", "模型、规则与数据源管理"
      ),
      Map.of(
          "id", 7,
          "name", "设备运维",
          "code", "maintenance",
          "description", "设备巡检、控制与 OTA 升级"
      )
  );

  @GetMapping
  @PreAuthorize("hasAuthority('roles:view')")
  public ApiResponse<List<Map<String, Object>>> list() {
    return ApiResponse.success(ROLES);
  }

  /**
   * 权限矩阵（角色 → 权限码），数据源为后端 {@link PermissionRegistry}。
   *
   * <p>GET /api/roles/permission-matrix。返回：
   * <pre>
   * { "roles": ["admin", ...], "permissions": ["ai:model:edit", ...],
   *   "matrix": { "admin": ["ai:model:edit", ...], ... } }
   * </pre>
   *
   * <p>注意：权限码的权威定义在后端 PermissionRegistry，前端
   * {@code ROLE_PERMISSION_MAP} 是同步副本。前端权限矩阵页应优先消费本端点，
   * 避免手写表格与真实权限漂移。
   */
  @GetMapping("/permission-matrix")
  @PreAuthorize("hasAuthority('roles:view')")
  public ApiResponse<Map<String, Object>> permissionMatrix() {
    return ApiResponse.success(Map.of(
        "roles", PermissionRegistry.roles().stream().sorted().toList(),
        "permissions", PermissionRegistry.allPermissions(),
        "matrix", PermissionRegistry.snapshot()
    ));
  }
}
