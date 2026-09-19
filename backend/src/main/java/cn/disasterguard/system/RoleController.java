package cn.disasterguard.system;

import cn.disasterguard.common.api.ApiResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
          "name", "管理员",
          "code", "ADMIN",
          "description", "系统管理员，拥有全部权限"
      ),
      Map.of(
          "id", 2,
          "name", "操作员",
          "code", "OPERATOR",
          "description", "日常运营操作人员"
      ),
      Map.of(
          "id", 3,
          "name", "查看者",
          "code", "VIEWER",
          "description", "只读查看权限"
      ),
      Map.of(
          "id", 4,
          "name", "调度员",
          "code", "DISPATCHER",
          "description", "应急调度与任务分发"
      )
  );

  /** 返回预定义角色列表。 */
  @GetMapping
  public ApiResponse<List<Map<String, Object>>> list() {
    return ApiResponse.success(ROLES);
  }
}
