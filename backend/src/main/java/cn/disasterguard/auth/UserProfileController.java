package cn.disasterguard.auth;

import cn.disasterguard.auth.service.UserProfileService;
import cn.disasterguard.common.api.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * P0-3：个人中心接口。
 */
@RestController
@RequestMapping("/api/profile")
public class UserProfileController {

    private final UserProfileService userProfileService;

    public UserProfileController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    /** 当前用户资料。 */
    @GetMapping
    @PreAuthorize("hasAuthority('settings:view')")
    public ApiResponse<UserProfileDto> get(Authentication auth) {
        return ApiResponse.success(userProfileService.getProfile(auth.getName()));
    }

    /** 更新资料。 */
    @PutMapping
    @PreAuthorize("hasAuthority('settings:edit')")
    public ApiResponse<UserProfileDto> update(Authentication auth,
                                              @RequestBody Map<String, Object> payload) {
        return ApiResponse.success(userProfileService.updateProfile(auth.getName(), payload));
    }

    /** 修改密码。 */
    @PutMapping("/password")
    @PreAuthorize("hasAuthority('settings:edit')")
    public ApiResponse<Map<String, Object>> changePassword(Authentication auth,
                                                          @RequestBody Map<String, String> payload) {
        userProfileService.changePassword(auth.getName(), payload.get("current"), payload.get("next"));
        return ApiResponse.success("密码修改成功，请下次登录使用新密码",
            Map.of("changed", true, "username", auth.getName()));
    }

    /** 获取通知偏好。 */
    @GetMapping("/notification-preference")
    @PreAuthorize("hasAuthority('settings:view')")
    public ApiResponse<NotificationPreferenceDto> getPref(Authentication auth) {
        return ApiResponse.success(userProfileService.getPreference(auth.getName()));
    }

    /** 更新通知偏好。 */
    @PutMapping("/notification-preference")
    @PreAuthorize("hasAuthority('settings:edit')")
    public ApiResponse<NotificationPreferenceDto> updatePref(Authentication auth,
                                                             @RequestBody NotificationPreferenceDto dto) {
        return ApiResponse.success(userProfileService.updatePreference(auth.getName(), dto));
    }

    /** 更新主题与语言（同步更新本地偏好）。 */
    @PutMapping("/theme-language")
    @PreAuthorize("hasAuthority('settings:edit')")
    public ApiResponse<NotificationPreferenceDto> updateTheme(Authentication auth,
                                                             @RequestBody Map<String, Object> payload) {
        NotificationPreferenceDto cur = userProfileService.getPreference(auth.getName());
        NotificationPreferenceDto next = new NotificationPreferenceDto(
            cur.channels(),
            cur.levels(),
            asString(payload.get("language"), cur.language()),
            asString(payload.get("theme"), cur.theme()),
            payload.get("compactMode") == null ? cur.compactMode() : Boolean.valueOf(payload.get("compactMode").toString()),
            cur.quietHoursEnabled(), cur.quietStart(), cur.quietEnd()
        );
        return ApiResponse.success(userProfileService.updatePreference(auth.getName(), next));
    }

    private String asString(Object v, String fallback) {
        return v == null ? fallback : String.valueOf(v);
    }
}
