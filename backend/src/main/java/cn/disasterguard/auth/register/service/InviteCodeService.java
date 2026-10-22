package cn.disasterguard.auth.register.service;

import cn.disasterguard.common.exception.AppException;
import java.util.Collections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * 邀请码服务：申请 admin 角色时必须提供有效邀请码。
 *
 * <p>存储约定：Redis key {@code invite:{code}}，值为该邀请码<b>剩余可用次数</b>（正整数）。
 * 运维发放邀请码的方式（无需改代码）：
 * <pre>
 *   redis-cli SET invite:XXXX 1              # 可用 1 次
 *   redis-cli SET invite:XXXX 5 EX 86400     # 可用 5 次，24 小时后过期
 * </pre>
 *
 * <p>消费使用 Lua 脚本保证「判断 + 递减 + 归零删除」的原子性，
 * 避免并发申请把同一个邀请码用掉多次。
 */
@Service
public class InviteCodeService {

  private static final Logger log = LoggerFactory.getLogger(InviteCodeService.class);

  private static final String KEY_PREFIX = "invite:";

  /**
   * 原子消费：剩余次数 &gt; 0 时减 1，减到 0 则删除 key。
   * 返回 1 表示消费成功，0 表示无可用次数。
   */
  private static final RedisScript<Long> CONSUME_SCRIPT = new DefaultRedisScript<>(
      "local v = redis.call('GET', KEYS[1]); "
          + "if not v then return 0 end; "
          + "local n = tonumber(v); "
          + "if not n or n <= 0 then return 0 end; "
          + "if n == 1 then redis.call('DEL', KEYS[1]) else redis.call('DECR', KEYS[1]) end; "
          + "return 1",
      Long.class);

  private final StringRedisTemplate redis;

  public InviteCodeService(StringRedisTemplate redis) {
    this.redis = redis;
  }

  /**
   * 消费一次邀请码。
   *
   * @param code 邀请码
   * @return 消费成功返回 true
   */
  public boolean consume(String code) {
    if (code == null || code.isBlank()) {
      return false;
    }
    try {
      Long result = redis.execute(CONSUME_SCRIPT, Collections.singletonList(KEY_PREFIX + code.trim()));
      return result != null && result > 0;
    } catch (DataAccessException ex) {
      log.error("邀请码校验访问 Redis 失败: {}", ex.getMessage());
      throw new AppException(50302, "注册服务暂不可用，请稍后重试");
    }
  }
}
