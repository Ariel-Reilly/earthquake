package cn.disasterguard.system;

import cn.disasterguard.common.api.ApiResponse;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台级系统设置接口（设置页持久化）。
 *
 * <p>权限沿用设置模块既有权限码：
 * <ul>
 *   <li>{@code GET /api/settings} —— {@code settings:view}</li>
 *   <li>{@code PUT /api/settings} —— {@code settings:edit}</li>
 * </ul>
 * 保存为合并式 upsert，只接受白名单字段，避免任意配置注入。
 */
@RestController
@RequestMapping("/api/settings")
public class SystemSettingController {

  private static final Logger log = LoggerFactory.getLogger(SystemSettingController.class);

  private final SystemSettingService settingService;

  public SystemSettingController(SystemSettingService settingService) {
    this.settingService = settingService;
  }

  /**
   * 读取已保存的平台设置；无记录时返回空对象，由前端与默认值合并。
   *
   * @return 设置键值
   */
  @GetMapping
  @PreAuthorize("hasAuthority('settings:view')")
  public ApiResponse<Map<String, Object>> getSettings() {
    return ApiResponse.success(settingService.load());
  }

  /**
   * 保存平台设置（合并式，只覆盖提交的字段）。
   *
   * @param payload 设置字段（白名单外的字段会被忽略）
   * @return 合并后的完整设置
   */
  @PutMapping
  @PreAuthorize("hasAuthority('settings:edit')")
  public ApiResponse<Map<String, Object>> saveSettings(@RequestBody Map<String, Object> payload) {
    if (payload == null || payload.isEmpty()) {
      return ApiResponse.fail(400, "设置内容不能为空");
    }
    Map<String, Object> merged = settingService.save(payload, currentOperator());
    return ApiResponse.success("设置已保存", merged);
  }

  /** 从安全上下文取当前操作人（取不到时记 anonymous）。 */
  private String currentOperator() {
    try {
      var auth = SecurityContextHolder.getContext().getAuthentication();
      if (auth != null && auth.getName() != null && !auth.getName().isBlank()) {
        return auth.getName();
      }
    } catch (Exception e) {
      log.debug("无法从 SecurityContext 获取当前用户名: {}", e.getMessage());
    }
    return "anonymous";
  }
}
