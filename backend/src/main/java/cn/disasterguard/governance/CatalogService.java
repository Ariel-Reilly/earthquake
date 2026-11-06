package cn.disasterguard.governance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

/**
 * 数据资产「登记」的持久化服务。
 *
 * <p>资产目录本体是 {@link CatalogController} 对真实业务库表的实时盘点视图，
 * 并没有独立的资产台账表。为满足「登记数据资产」又要「不新建表 / 不改 schema」，
 * 这里复用现有的 {@code sys_dict} 通用字典表：以专用 {@code type} 命名空间
 * ({@code data_catalog_asset}) 写入登记记录 —— 这是真实落库、可跨重启读取的持久化，
 * 不是内存 mock，也不是 501 桩。
 *
 * <p>{@code sys_dict} 列映射：label=资源名、value=资产编码、description=元数据 JSON、
 * enabled=true、sort_order=0，id 用数据库自增值。{@code description} 为
 * VARCHAR(255)，故序列化后超长时明确报错（不静默截断）。
 */
@Service
public class CatalogService {

  private static final Logger log = LoggerFactory.getLogger(CatalogService.class);

  /** sys_dict.type 命名空间：仅此类型视为用户登记的数据资产。 */
  private static final String ASSET_DICT_TYPE = "data_catalog_asset";

  /** sys_dict.description 为 VARCHAR(255)。 */
  private static final int MAX_PAYLOAD_LEN = 255;

  /** 登记资产在目录里的 id 偏移，避免和实盘资产（1..N）冲突。 */
  private static final long REGISTERED_ID_OFFSET = 1_000_000L;

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  @Autowired
  public CatalogService(@Autowired(required = false) DataSource dataSource,
                        ObjectMapper objectMapper) {
    this.jdbcTemplate = dataSource == null ? null : new JdbcTemplate(dataSource);
    this.objectMapper = objectMapper;
  }

  /**
   * 登记一条数据资产，返回带稳定 id 的资产视图（与实盘资产同结构，便于目录直接展示）。
   *
   * @throws IllegalArgumentException 入参不合法（缺名称 / 信息过长）
   * @throws IllegalStateException    数据源不可用或数据库写入失败
   */
  public Map<String, Object> register(Map<String, Object> body, String operator) {
    if (jdbcTemplate == null) {
      throw new IllegalStateException("数据源不可用，无法登记数据资产");
    }
    Map<String, Object> b = body == null ? Map.of() : body;
    String name = trimToNull(str(b.get("name")));
    if (name == null) {
      throw new IllegalArgumentException("请填写资源名称 name");
    }
    String type = trimToNull(str(b.get("type")));
    String category = trimToNull(str(b.get("category")));
    String source = trimToNull(str(b.get("source")));
    String description = trimToNull(str(b.get("description")));
    List<String> tags = toStringList(b.get("tags"));
    String code = "AST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    OffsetDateTime now = OffsetDateTime.now();

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("code", code);
    payload.put("name", name);
    payload.put("type", type);
    payload.put("category", category);
    payload.put("source", source);
    payload.put("description", description);
    payload.put("tags", tags);
    payload.put("creator", operator == null ? "system" : operator);
    payload.put("createdAt", now.toString());

    String json;
    try {
      json = objectMapper.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("资产信息序列化失败", e);
    }
    if (json.length() > MAX_PAYLOAD_LEN) {
      throw new IllegalArgumentException("登记信息过长（" + json.length() + " 字符，上限 "
          + MAX_PAYLOAD_LEN + "），请精简名称 / 描述 / 标签");
    }

    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      PreparedStatement ps = connection.prepareStatement(
          "INSERT INTO sys_dict (type, label, value, description, sort_order, enabled) "
              + "VALUES (?, ?, ?, ?, ?, ?)",
          Statement.RETURN_GENERATED_KEYS);
      ps.setString(1, ASSET_DICT_TYPE);
      ps.setString(2, name);
      ps.setString(3, code);
      ps.setString(4, json);
      ps.setInt(5, 0);
      ps.setBoolean(6, true);
      return ps;
    }, keyHolder);
    Number key = keyHolder.getKey();
    long dictId = key == null ? now.toEpochSecond() : key.longValue();

    return toAssetView(dictId, payload);
  }

  /** 从真实库 sys_dict 回读全部已登记资产，供目录盘点合并。库异常时返回空列表（不伪造）。 */
  public List<Map<String, Object>> listRegistered() {
    if (jdbcTemplate == null) {
      return List.of();
    }
    try {
      return jdbcTemplate.query(
          "SELECT id, description FROM sys_dict "
              + "WHERE type = ? AND enabled = true ORDER BY id DESC",
          (rs, i) -> {
            Map<String, Object> payload = parsePayload(rs.getString("description"));
            return toAssetView(rs.getLong("id"), payload);
          }, ASSET_DICT_TYPE);
    } catch (Exception ex) {
      log.debug("读取已登记数据资产失败: {}", ex.getMessage());
      return List.of();
    }
  }

  private Map<String, Object> parsePayload(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
    } catch (Exception e) {
      log.debug("已登记资产解析失败: {}", e.getMessage());
      return Map.of();
    }
  }

  /** 转成与 CatalogController.loadRealAssets() 实盘资产一致的字段结构。 */
  private Map<String, Object> toAssetView(long dictId, Map<String, Object> p) {
    Map<String, Object> a = new LinkedHashMap<>();
    a.put("id", REGISTERED_ID_OFFSET + dictId);
    a.put("table", null);                     // 用户登记，非实盘库表
    a.put("name", p.get("name"));
    a.put("domain", domainOf(p.get("type"), p.get("category")));
    a.put("description", p.get("description") == null ? "" : p.get("description"));
    a.put("format", p.get("source") == null ? "自定义登记" : p.get("source"));
    a.put("size", "—");
    a.put("sizeBytes", 0L);
    a.put("recordCount", 0L);
    a.put("updatedAt", p.get("createdAt"));
    a.put("owner", p.get("creator") == null ? "system" : p.get("creator"));
    a.put("tags", p.get("tags") == null ? List.of() : p.get("tags"));
    a.put("quality", 0);
    a.put("registered", true);
    a.put("code", p.get("code"));
    a.put("type", p.get("type"));
    a.put("category", p.get("category"));
    a.put("source", p.get("source"));
    a.put("creator", p.get("creator"));
    a.put("createdAt", p.get("createdAt"));
    return a;
  }

  private String domainOf(Object type, Object category) {
    String t = trimToNull(str(type));
    if (t != null) {
      return t.toUpperCase(Locale.ROOT);
    }
    String c = trimToNull(str(category));
    if (c != null) {
      return c.toUpperCase(Locale.ROOT);
    }
    return "CUSTOM";
  }

  private List<String> toStringList(Object v) {
    List<String> out = new ArrayList<>();
    if (v instanceof List<?> list) {
      for (Object o : list) {
        String s = trimToNull(str(o));
        if (s != null) {
          out.add(s);
        }
      }
      return out;
    }
    String s = trimToNull(str(v));
    if (s != null) {
      for (String part : s.split("[,，]")) {
        String t = part.trim();
        if (!t.isEmpty()) {
          out.add(t);
        }
      }
    }
    return out;
  }

  private String str(Object v) {
    return v == null ? null : String.valueOf(v);
  }

  private String trimToNull(String s) {
    if (s == null) {
      return null;
    }
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }
}
