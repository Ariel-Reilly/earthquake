package cn.disasterguard.auth.register.service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 敏感数据（身份证号）对称加解密组件：AES-256-GCM。
 *
 * <p>密钥来源：配置项 {@code disasterguard.security.data-encryption-key}
 * （对应环境变量 {@code DATA_ENCRYPTION_KEY}）。
 * <ul>
 *   <li>值为 Base64 且解码后 ≥ 32 字节 → 直接作为原始密钥使用；</li>
 *   <li>其余情况 → 取 SHA-256 摘要的前 32 字节作为密钥（便于运维用任意长字符串配置）。</li>
 * </ul>
 *
 * <p>密文格式：{@code Base64(iv(12B) || ciphertext || tag(16B))}。
 * GCM 每次加密使用随机 IV，因此相同明文两次加密结果不同，可防止密文比对攻击。
 *
 * <p>dev 环境未配置密钥时生成随机密钥并 WARN 提示 —— 重启后旧密文将无法解密；
 * prod 环境由 {@code ProductionConfigValidator} 在启动阶段 fail-fast 拦截。
 */
@Component
public class SensitiveDataCipher {

  private static final Logger log = LoggerFactory.getLogger(SensitiveDataCipher.class);

  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final int IV_LENGTH = 12;
  private static final int TAG_LENGTH_BITS = 128;
  private static final int KEY_LENGTH_BYTES = 32;

  private final SecretKeySpec secretKey;
  private final SecureRandom random = new SecureRandom();

  public SensitiveDataCipher(
      @Value("${disasterguard.security.data-encryption-key:}") String configuredKey) {
    this.secretKey = buildKey(configuredKey);
  }

  private SecretKeySpec buildKey(String configuredKey) {
    if (configuredKey == null || configuredKey.isBlank()) {
      byte[] randomKey = new byte[KEY_LENGTH_BYTES];
      new SecureRandom().nextBytes(randomKey);
      log.warn("未配置 disasterguard.security.data-encryption-key，已生成随机密钥；"
          + "重启后此前加密的数据将无法解密，请尽快在环境变量 DATA_ENCRYPTION_KEY 中固定密钥");
      return new SecretKeySpec(randomKey, "AES");
    }
    String trimmed = configuredKey.trim();
    byte[] decoded = tryDecodeBase64(trimmed);
    if (decoded != null && decoded.length >= KEY_LENGTH_BYTES) {
      return new SecretKeySpec(decoded, "AES");
    }
    // 非 Base64 或 Base64 解码后不足 32 字节：统一用 SHA-256 派生，保证 256 位密钥强度
    try {
      byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
          .digest(trimmed.getBytes(StandardCharsets.UTF_8));
      return new SecretKeySpec(digest, "AES");
    } catch (Exception ex) {
      throw new IllegalStateException("SHA-256 不可用，无法派生数据加密密钥", ex);
    }
  }

  private byte[] tryDecodeBase64(String value) {
    try {
      return Base64.getDecoder().decode(value);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  /**
   * 加密明文。
   *
   * @param plain 明文
   * @return Base64(iv || ciphertext || tag)
   */
  public String encrypt(String plain) {
    if (plain == null) {
      return null;
    }
    try {
      byte[] iv = new byte[IV_LENGTH];
      random.nextBytes(iv);
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
      byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
      byte[] combined = new byte[iv.length + encrypted.length];
      System.arraycopy(iv, 0, combined, 0, iv.length);
      System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
      return Base64.getEncoder().encodeToString(combined);
    } catch (Exception ex) {
      throw new IllegalStateException("敏感数据加密失败", ex);
    }
  }

  /**
   * 解密密文。
   *
   * @param cipherText Base64(iv || ciphertext || tag)
   * @return 明文
   */
  public String decrypt(String cipherText) {
    if (cipherText == null) {
      return null;
    }
    try {
      byte[] combined = Base64.getDecoder().decode(cipherText);
      if (combined.length <= IV_LENGTH) {
        throw new IllegalStateException("密文长度非法");
      }
      byte[] iv = new byte[IV_LENGTH];
      System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
      byte[] decrypted = cipher.doFinal(combined, IV_LENGTH, combined.length - IV_LENGTH);
      return new String(decrypted, StandardCharsets.UTF_8);
    } catch (Exception ex) {
      throw new IllegalStateException("敏感数据解密失败", ex);
    }
  }

  /**
   * 生成脱敏串：保留前 3 位与后 4 位，中间以 * 填充。
   *
   * @param value 原始身份证号
   * @return 形如 110***********1234 的脱敏串
   */
  public static String mask(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    String trimmed = value.trim();
    if (trimmed.length() <= 7) {
      return "*".repeat(trimmed.length());
    }
    int head = 3;
    int tail = 4;
    return trimmed.substring(0, head)
        + "*".repeat(trimmed.length() - head - tail)
        + trimmed.substring(trimmed.length() - tail);
  }
}
