package cn.disasterguard.common.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 锁定 {@link PermissionRegistry} 的角色→权限码映射。
 *
 * <p>这是后端运行期 authority 的唯一来源（{@code DbUserDetailsService} 直接读它，
 * 不读 data.sql）。{@code ConfirmationQueueController} 的 claim/transfer/confirm/reject
 * 四个写端点已改为要求对应的写权限码，本测试保证这些码确实被业务角色持有，
 * 否则改注解会导致这些端点全部 403。
 */
@DisplayName("PermissionRegistry — 角色→权限码映射")
class PermissionRegistryTest {

  private static final Set<String> CONFIRMATION_WRITE =
      Set.of("confirmation:claim", "confirmation:confirm", "confirmation:reject", "confirmation:transfer");

  @Test
  @DisplayName("admin 配齐确认工作台四个写权限码")
  void admin_has_confirmation_write_codes() {
    Set<String> perms = new HashSet<>(PermissionRegistry.permissionsOf("admin"));
    Set<String> missing = new HashSet<>(CONFIRMATION_WRITE);
    missing.removeAll(perms);
    assertTrue(missing.isEmpty(), "admin 缺少确认写权限码: " + missing);
  }

  @Test
  @DisplayName("attendant 配齐确认工作台四个写权限码")
  void attendant_has_confirmation_write_codes() {
    assertTrue(PermissionRegistry.permissionsOf("attendant").containsAll(CONFIRMATION_WRITE));
  }

  @Test
  @DisplayName("expert 配齐确认工作台四个写权限码")
  void expert_has_confirmation_write_codes() {
    assertTrue(PermissionRegistry.permissionsOf("expert").containsAll(CONFIRMATION_WRITE));
  }

  @Test
  @DisplayName("四个确认写权限码至少被一个角色持有（写端点不会全体 403）")
  void write_codes_granted_to_some_role() {
    boolean grantedToSomeone = PermissionRegistry.roles().stream()
        .anyMatch(r -> PermissionRegistry.permissionsOf(r).containsAll(CONFIRMATION_WRITE));
    assertTrue(grantedToSomeone, "确认写权限码必须被至少一个角色持有");
  }

  @Test
  @DisplayName("未知角色 / null 返回空权限集，不会意外放行")
  void unknown_role_and_null_are_empty() {
    assertTrue(PermissionRegistry.permissionsOf("ghost").isEmpty());
    assertTrue(PermissionRegistry.permissionsOf(null).isEmpty());
  }
}
