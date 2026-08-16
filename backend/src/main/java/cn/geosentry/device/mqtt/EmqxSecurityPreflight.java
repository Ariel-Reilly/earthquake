package cn.geosentry.device.mqtt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * EMQX 桥接鉴权预检。
 *
 * <p>当 {@code emqx.enabled=true} 且未配置 username/password 时，
 * 在启动阶段输出清晰的 WARN 日志，提醒操作者 MQTT broker 侧未启用认证，
 * 任何客户端都能伪造 deviceCode 发布数据。
 *
 * <p>默认不阻塞启动（fail-open），避免无 broker 环境无法调试；
 * 生产部署应显式配置 {@code EMQX_USERNAME} / {@code EMQX_PASSWORD} 或关闭 emqx.enabled。
 */
@Component
public class EmqxSecurityPreflight implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(EmqxSecurityPreflight.class);

  private final boolean enabled;
  private final String username;
  private final String password;

  public EmqxSecurityPreflight(
      @Value("${emqx.enabled:true}") boolean enabled,
      @Value("${emqx.username:}") String username,
      @Value("${emqx.password:}") String password) {
    this.enabled = enabled;
    this.username = username == null ? "" : username.trim();
    this.password = password == null ? "" : password.trim();
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!enabled) {
      return;
    }
    if (username.isEmpty() || password.isEmpty()) {
      log.warn("""
          ============================================================
          [安全警报] EMQX MQTT 桥接已启用但未配置鉴权凭据！
          任何网络可达的客户端都能向 topic 'geohazard/+/data' 发布伪造数据。
          修复方式（二选一）：
            1. 在 broker 侧启用认证，并通过环境变量传入：
               EMQX_USERNAME=geosentry  EMQX_PASSWORD=<strong-password>
            2. 临时关闭 MQTT 桥接：EMQX_ENABLED=false
          ============================================================""");
    } else {
      log.info("EMQX 桥接凭据已配置（user={}, password_len={})", username, password.length());
    }
  }
}
