package cn.disasterguard.warning.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * R11 P0-1 / P1-D3 回归护栏。
 *
 * <p>锁定两件在干净部署上必须成立的事，任何一件破了 AI 综合预警链路都会静默归零：
 * <ol>
 *   <li>{@code warning_rule} 必须存在 {@code AI_COMPREHENSIVE} 行 ——
 *       {@code warning_event.rule_code} 有外键指向它，缺失时每轮 INSERT 必违约；</li>
 *   <li>{@code ai_comprehensive_score} 种子的 {@code risk_level} 必须用
 *       LOW/MEDIUM/HIGH/CRITICAL，不能用颜色词 —— 代码按英文等级查询。</li>
 * </ol>
 */
@DisplayName("data.sql — AI 预警链路种子契约")
class AiWarningSeedContractTest {

  private static final String DATA_SQL = "/data.sql";

  @Test
  @DisplayName("warning_rule 存在 AI_COMPREHENSIVE 外键目标行")
  void aiRuleCodeHasForeignKeyTarget() throws Exception {
    assertTrue(readDataSql().contains("'AI_COMPREHENSIVE'"),
        "data.sql 未播种 AI_COMPREHENSIVE 伪规则，AiWarningScheduler 会因外键违约 0 产出");
  }

  @Test
  @DisplayName("ai_comprehensive_score 种子使用英文 AI 等级")
  void aiScoreSeedUsesEnglishRiskLevels() throws Exception {
    String block = section(readDataSql(),
        "INSERT INTO ai_comprehensive_score",
        "R11 P1-D3 老库清洗");
    assertFalse(block.isBlank(), "未找到 ai_comprehensive_score 种子段");
    for (String color : List.of("'red'", "'orange'", "'yellow'", "'blue'")) {
      assertFalse(block.contains(color),
          "ai_comprehensive_score 种子仍含颜色词 " + color + "，代码按英文等级查询会选不中");
    }
  }

  private static String section(String text, String from, String to) {
    int start = text.indexOf(from);
    if (start < 0) return "";
    int end = text.indexOf(to, start);
    return end < 0 ? text.substring(start) : text.substring(start, end);
  }

  private static String readDataSql() throws Exception {
    try (InputStream in = AiWarningSeedContractTest.class.getResourceAsStream(DATA_SQL)) {
      assertNotNull(in, "classpath 缺少 " + DATA_SQL);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
