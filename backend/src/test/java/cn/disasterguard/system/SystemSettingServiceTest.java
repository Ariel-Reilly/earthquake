package cn.disasterguard.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 平台设置落库服务单测。
 *
 * <p>重点锁定三个失败模式：
 * <ul>
 *   <li>历史 JSON 损坏时不能 500，而是返回空设置让前端用默认值；</li>
 *   <li>只允许白名单字段落库（防止任意请求体注入配置）；</li>
 *   <li>保存是「合并式 upsert」，不能把已有设置整条覆盖丢失。</li>
 * </ul>
 */
class SystemSettingServiceTest {

  private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
  private final SystemSettingService service =
      new SystemSettingService(jdbcTemplate, new ObjectMapper());

  @Test
  void load_parsesStoredJson() {
    when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq("platform")))
        .thenReturn(List.of("{\"platformName\":\"地脉智巡\",\"refreshInterval\":15}"));

    Map<String, Object> settings = service.load();

    assertThat(settings).containsEntry("platformName", "地脉智巡");
    assertThat(settings).containsEntry("refreshInterval", 15);
  }

  @Test
  void load_corruptedJson_returnsEmptyInsteadOfThrowing() {
    when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq("platform")))
        .thenReturn(List.of("{not-json"));

    assertThat(service.load()).isEmpty();
  }

  @Test
  void save_mergesExistingAndDropsUnknownFields() {
    when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq("platform")))
        .thenReturn(List.of("{\"platformName\":\"旧名称\",\"dataRetention\":180,\"evil\":\"drop\"}"));

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("platformName", "新名称");
    payload.put("refreshInterval", 15);
    payload.put("evil", "ignored");

    Map<String, Object> merged = service.save(payload, "admin");

    assertThat(merged).containsEntry("platformName", "新名称");
    assertThat(merged).containsEntry("refreshInterval", 15);
    // 已有字段保留（合并而非覆盖）
    assertThat(merged).containsEntry("dataRetention", 180);
    // 非白名单字段必须被丢弃
    assertThat(merged).doesNotContainKey("evil");

    verify(jdbcTemplate).update(
        contains("INSERT INTO system_setting"), eq("platform"), anyString(), eq("admin"));
  }
}
