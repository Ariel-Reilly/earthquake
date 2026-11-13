package cn.disasterguard.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.disasterguard.auth.repository.SysUserRepository;
import cn.disasterguard.common.exception.AppException;
import cn.disasterguard.common.security.PermissionRegistry;
import cn.disasterguard.event.repository.EventSignRepository;
import cn.disasterguard.event.repository.EventTransferRepository;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * A6-1 / B2-3：管理员代签权限落地回归（2026-11-13 决策「允许管理员代签」）。
 *
 * <p>验证两件事：
 * <ul>
 *   <li>只有 admin 拿到 {@code sign:proxy}，其余角色仍默认拒绝；</li>
 *   <li>{@code SignService.assertCanActOn} 对持有该权限的管理员放行他人 signerId，
 *       对无权限角色仍抛 403（冒名防护没有被代签开关削弱）。</li>
 * </ul>
 */
class SignProxyPermissionTest {

  private static final String OTHER_SIGNER_ID = "5"; // 专家用户 sys_user.id

  @Test
  void onlyAdminHasProxyPermission() {
    assertThat(PermissionRegistry.permissionsOf("admin")).contains("sign:proxy");
    for (String role : List.of("attendant", "expert", "maintenance", "system_admin",
        "emergency_commander", "ai_engineer")) {
      assertThat(PermissionRegistry.permissionsOf(role)).doesNotContain("sign:proxy");
    }
    // B1：时相对比页面权限同步进了运行时注册表
    assertThat(PermissionRegistry.permissionsOf("admin")).contains("satellite:view");
    assertThat(PermissionRegistry.permissionsOf("attendant")).contains("satellite:view");
    assertThat(PermissionRegistry.permissionsOf("emergency_commander")).contains("satellite:view");
    assertThat(PermissionRegistry.permissionsOf("expert")).doesNotContain("satellite:view");
  }

  @Test
  void adminCanProxySignButOrdinaryRoleCannot() throws Exception {
    SysUserRepository users = mock(SysUserRepository.class);
    when(users.findByUsername(anyString())).thenReturn(Optional.empty());
    SignService service = new SignService(
        mock(EventSignRepository.class), mock(EventTransferRepository.class), users);
    Method check = SignService.class.getDeclaredMethod(
        "assertCanActOn", String.class, String.class, Authentication.class);
    check.setAccessible(true);

    Authentication admin = authFor("admin");
    Authentication expert = authFor("expert");

    // admin 持有 sign:proxy → 代签他人不抛异常（SignService 会写 warn 审计日志）
    assertThatCode(() -> invoke(check, service, "sign-proxy-1", OTHER_SIGNER_ID, admin))
        .doesNotThrowAnyException();

    // 无该权限的角色仍被 403 拦下，冒名防护没有被削弱
    assertThatThrownBy(() -> invoke(check, service, "sign-proxy-2", OTHER_SIGNER_ID, expert))
        .isInstanceOf(InvocationTargetException.class)
        .hasCauseInstanceOf(AppException.class)
        .hasStackTraceContaining("无权操作他人会签");
  }

  private static Authentication authFor(String role) {
    var authorities = PermissionRegistry.permissionsOf(role).stream()
        .map(SimpleGrantedAuthority::new)
        .map(a -> (org.springframework.security.core.GrantedAuthority) a)
        .toList();
    return new UsernamePasswordAuthenticationToken(role, "n/a", authorities);
  }

  private static void invoke(Method method, Object target, String signId, String signerId,
      Authentication auth) throws Exception {
    method.invoke(target, signId, signerId, auth);
  }
}
