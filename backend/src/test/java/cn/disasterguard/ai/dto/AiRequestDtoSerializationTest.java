package cn.disasterguard.ai.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 锁定 Java → Python 出站 JSON 字段名（snake_case）。
 *
 * <p>历史 bug：{@code @JsonAlias} 仅影响反序列化，出站仍输出 Java 字段名（camelCase），
 * 导致 FastAPI Pydantic 收到 {@code hazardCode} 等无法解析 → 422 Unprocessable Entity。
 *
 * <p>本测试验证每个 Request DTO 的序列化结果符合 Python 端 Pydantic 字段定义。
 */
class AiRequestDtoSerializationTest {

  private final ObjectMapper mapper = new ObjectMapper();

  // ---------- DisasterAssessmentRequestDto ----------

  @Test
  void disasterAssessmentRequest_serializesToSnakeCase() throws Exception {
    var req = new DisasterAssessmentRequestDto(
        new DisasterAssessmentRequestDto.HazardMeta("HZ-001", "滑坡"),
        Map.of("rainfall_24h", 120.0)
    );
    String json = mapper.writeValueAsString(req);
    assertThat(json).contains("\"hazard\":");
    assertThat(json).contains("\"hazard_code\":\"HZ-001\"");
    assertThat(json).contains("\"hazard_type\":\"滑坡\"");
    assertThat(json).contains("\"factors\":");
    assertThat(json).doesNotContain("hazardCode");
    assertThat(json).doesNotContain("hazardType");
  }

  // ---------- DeviceHealthRequestDto ----------

  @Test
  void deviceHealthRequest_serializesToSnakeCase() throws Exception {
    var req = new DeviceHealthRequestDto(
        "DEV-001",
        Map.of("disp_rate", List.of(0.1, 0.2, 0.3)),
        "ONLINE",
        "2026-09-16T08:00:00Z"
    );
    String json = mapper.writeValueAsString(req);
    assertThat(json).contains("\"device_code\":\"DEV-001\"");
    assertThat(json).contains("\"online_status\":\"ONLINE\"");
    assertThat(json).contains("\"last_report_at\":\"2026-09-16T08:00:00Z\"");
    assertThat(json).contains("\"metrics\":");
    assertThat(json).doesNotContain("deviceCode");
    assertThat(json).doesNotContain("onlineStatus");
    assertThat(json).doesNotContain("lastReportAt");
  }

  // ---------- FeedbackRequestDto ----------

  @Test
  void feedbackRequest_serializesToSnakeCase() throws Exception {
    var req = new FeedbackRequestDto(
        "GS-001", "HIGH", 75.3, "2026-09-07T08:00:00Z",
        "true_positive", true, 0.9, "operator-zhang", "现场确认"
    );
    String json = mapper.writeValueAsString(req);
    assertThat(json).contains("\"hazard_code\":\"GS-001\"");
    assertThat(json).contains("\"predicted_level\":\"HIGH\"");
    assertThat(json).contains("\"predicted_score\":75.3");
    assertThat(json).contains("\"predicted_at\":\"2026-09-07T08:00:00Z\"");
    assertThat(json).contains("\"feedback_by\":\"operator-zhang\"");
    assertThat(json).doesNotContain("hazardCode");
    assertThat(json).doesNotContain("predictedLevel");
    assertThat(json).doesNotContain("feedbackBy");
  }

  /**
   * FeedbackRequestDto 是双向 DTO（前端→Java→Python）。
   * 前端发 camelCase，Python 要 snake_case。验证 Java 既能入站 camelCase，也能出站 snake_case。
   */
  @Test
  void feedbackRequest_acceptsCamelCaseIncomingAndSerializesSnakeCaseOutgoing() throws Exception {
    // 入站：前端 camelCase payload
    String camelPayload = """
        {
          "hazardCode": "GS-001",
          "predictedLevel": "HIGH",
          "predictedScore": 75.3,
          "predictedAt": "2026-09-07T08:00:00Z",
          "outcome": "true_positive",
          "occurred": true,
          "confidence": 0.9,
          "feedbackBy": "operator-zhang",
          "note": "现场确认"
        }
        """;
    FeedbackRequestDto req = mapper.readValue(camelPayload, FeedbackRequestDto.class);
    assertThat(req.hazardCode()).isEqualTo("GS-001");
    assertThat(req.predictedLevel()).isEqualTo("HIGH");
    assertThat(req.feedbackBy()).isEqualTo("operator-zhang");

    // 出站：Python snake_case
    String snakeJson = mapper.writeValueAsString(req);
    assertThat(snakeJson).contains("\"hazard_code\":\"GS-001\"");
    assertThat(snakeJson).contains("\"predicted_level\":\"HIGH\"");
    assertThat(snakeJson).contains("\"feedback_by\":\"operator-zhang\"");
  }

  // ---------- IndependentAssessmentRequestDto (入站专用，本测试仅覆盖反序列化) ----------

  /**
   * 该 DTO 仅入站（前端→Java），controller 手工构造 snake_case Map 调 Python，
   * 故**不**声明 @JsonProperty，仅保留 @JsonAlias。
   * 本测试锁定其行为不会被人误改。
   */
  @Test
  void independentRequest_acceptsCamelCaseIncoming() throws Exception {
    String camelPayload = """
        {
          "points": [
            {
              "lon": 107.21, "lat": 30.61,
              "regionCode": "100101",
              "rainfall24h": 120.5,
              "elevationM": 850.0,
              "soilGrade": 3
            }
          ],
          "minRiskLevel": "HIGH",
          "mockWeather": false
        }
        """;
    IndependentAssessmentRequestDto req =
        mapper.readValue(camelPayload, IndependentAssessmentRequestDto.class);
    assertThat(req.minRiskLevel()).isEqualTo("HIGH");
    assertThat(req.mockWeather()).isFalse();
    assertThat(req.points()).hasSize(1);
    assertThat(req.points().get(0).lon()).isEqualTo(107.21);
    assertThat(req.points().get(0).rainfall24h()).isEqualTo(120.5);
    assertThat(req.points().get(0).elevationM()).isEqualTo(850.0);
  }
}
