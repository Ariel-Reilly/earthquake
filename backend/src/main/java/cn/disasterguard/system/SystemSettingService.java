package cn.disasterguard.system;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 平台级系统设置持久化。
 *
 * <p>背景：设置页此前只写 {@code localStorage}，换浏览器/清缓存后
 * 平台名称、时区、刷新间隔、预警阈值、通知渠道等配置会全部丢失，试运行无法交付。
 * 这里用一张极小的 {@code system_setting} 表按 key-value 落库：
 * 单条 {@code platform} 记录存整个设置 JSON，读取时与默认值在前端合并，
 * 便于后续增加字段而不改表结构。
 *
 * <p>只接受白名单字段，避免把任意请求体塞进配置。
 */
@Service
public class SystemSettingService {

  private static final Logger log = LoggerFactory.getLogger(SystemSettingService.class);

  /** 平台设置在表中的固定 key。 */
  private static final String PLATFORM_KEY = "platform";

  /** 允许落库的设置字段（与前端 SettingsPage defaults 对齐）。 */
  private static final List<String> ALLOWED_KEYS = List.of(
      "platformName", "timezone", "refreshInterval", "mapProvider",
      "warningChannels", "displacementThreshold", "rainfallThreshold",
      "mfaEnabled", "autoSignOut", "dataRetention", "backupEnabled",
      "theme", "language", "landingPage", "compactMode",
      "quietHoursEnabled", "quietStart", "quietEnd", "notificationLevels");

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public SystemSettingService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  /**
   * 读取平台设置（无记录时返回空 Map，由前端与默认值合并）。
   *
   * @return 已保存的设置键值
   */
  public Map<String, Object> load() {
    List<String> values = jdbcTemplate.queryForList(
        "SELECT setting_value FROM system_setting WHERE setting_key = ?",
        String.class, PLATFORM_KEY);
    if (values.isEmpty() || values.get(0) == null || values.get(0).isBlank()) {
      return new LinkedHashMap<>();
    }
    try {
      Map<String, Object> parsed = objectMapper.readValue(
          values.get(0), new TypeReference<LinkedHashMap<String, Object>>() {});
      // 防御：历史记录里可能残留旧版本/手工写入的非白名单字段，
      // 读取时统一过滤，避免 save() 合并后把脏字段再次写回并返回给前端。
      Map<String, Object> filtered = new LinkedHashMap<>();
      for (String key : ALLOWED_KEYS) {
        if (parsed.containsKey(key)) {
          filtered.put(key, parsed.get(key));
        }
      }
      return filtered;
    } catch (Exception e) {
      log.warn("系统设置 JSON 解析失败，忽略历史记录: {}", e.getMessage());
      return new LinkedHashMap<>();
    }
  }

  /**
   * 保存平台设置：与已有记录合并后整条 upsert，返回合并后的完整设置。
   *
   * @param payload  本次提交的设置（只取白名单字段）
   * @param operator 当前操作人（写入 updated_by）
   * @return 合并后的设置
   */
  public Map<String, Object> save(Map<String, Object> payload, String operator) {
    Map<String, Object> merged = new LinkedHashMap<>(load());
    if (payload != null) {
      for (String key : ALLOWED_KEYS) {
        if (payload.containsKey(key)) {
          merged.put(key, payload.get(key));
        }
      }
    }
    String json;
    try {
      json = objectMapper.writeValueAsString(merged);
    } catch (Exception e) {
      throw new IllegalArgumentException("系统设置序列化失败: " + e.getMessage(), e);
    }
    jdbcTemplate.update(
        "INSERT INTO system_setting(setting_key, setting_value, updated_at, updated_by) "
            + "VALUES (?, ?, CURRENT_TIMESTAMP, ?) "
            + "ON CONFLICT (setting_key) DO UPDATE SET "
            + "setting_value = EXCLUDED.setting_value, "
            + "updated_at = CURRENT_TIMESTAMP, "
            + "updated_by = EXCLUDED.updated_by",
        PLATFORM_KEY, json, operator);
    log.info("系统设置已保存，操作人={}，字段数={}", operator, merged.size());
    return merged;
  }
}
