package cn.disasterguard.ai.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 锁定 {@link ForecastResult} 的 Jackson 序列化。
 *
 * <p>2026-11-14 审查 P0-3：该类原先只有 record 风格访问器 {@code deviceCode()}，
 * Jackson 默认只识别 {@code getXxx()} / {@code isXxx()}，序列化时抛
 * {@code No serializer found for class ForecastResult} →
 * {@code GET /api/ai/forecast/auto} 与 {@code POST /api/ai/forecast} 均 500。
 */
@DisplayName("ForecastResult — Jackson 序列化 / 反序列化")
class ForecastResultSerializationTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  @DisplayName("序列化输出 camelCase 全字段（不再抛 No serializer found）")
  void serializesAllFields() {
    ForecastResult result = new ForecastResult(
        "DV-001", "displacement", 7, List.of(10.0, 10.2, 10.5), 0.87, "lstm", true);

    JsonNode node = mapper.valueToTree(result);
    assertThat(node.path("deviceCode").asText()).isEqualTo("DV-001");
    assertThat(node.path("metric").asText()).isEqualTo("displacement");
    assertThat(node.path("horizon").asInt()).isEqualTo(7);
    assertThat(node.path("predictions").size()).isEqualTo(3);
    assertThat(node.path("confidence").asDouble()).isEqualTo(0.87);
    assertThat(node.path("model").asText()).isEqualTo("lstm");
    assertThat(node.path("degraded").asBoolean()).isTrue();
    // record 风格访问器名不应作为额外字段泄漏到 JSON
    assertThat(node.has("device_code")).isFalse();
  }

  @Test
  @DisplayName("ai-service 的 snake_case 别名仍可反序列化")
  void deserializesSnakeCaseAliases() throws Exception {
    String json = """
        {"device_code":"DV-001","metric":"displacement","horizon":7,
         "predictions":[1.0,2.0],"confidence":0.9,"model":"lstm","mock":true}
        """;
    ForecastResult result = mapper.readValue(json, ForecastResult.class);
    assertThat(result.deviceCode()).isEqualTo("DV-001");
    assertThat(result.predictions()).containsExactly(1.0, 2.0);
    assertThat(result.degraded()).isTrue();
  }
}
