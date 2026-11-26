package cn.disasterguard.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * R11 P1-D1 回归护栏：data.sql 在 dev/docker 每次启动都会重跑
 * （{@code spring.sql.init.mode=always}），因此每条种子都必须真正幂等。
 *
 * <p>两件必须成立的事：
 * <ol>
 *   <li>规则种子只负责首次建行，不得覆盖运维在规则管理页改过的阈值 ——
 *       否则重启后配置会静默变回出厂值；</li>
 *   <li>id 为 BIGSERIAL 的演示表没有可命中的唯一键，
 *       {@code ON CONFLICT DO NOTHING} 永远不会触发，必须改用
 *       {@code INSERT ... SELECT ... WHERE NOT EXISTS}。</li>
 * </ol>
 */
@DisplayName("data.sql — 种子幂等契约")
class SeedIdempotencyContractTest {

  private static final String DATA_SQL = "/data.sql";

  /** id 为 BIGSERIAL、且此前用无效 ON CONFLICT 播种的演示表。 */
  private static final List<String> DEMO_TABLES = List.of(
      "sys_dict",
      "device_metric",
      "device_trajectory",
      "risk_score_log",
      "operation_log",
      "user_message",
      "user_todo"
  );

  @Test
  @DisplayName("warning_rule 不得在重启时覆盖运维改过的阈值")
  void warningRuleSeedDoesNotOverwriteOperatorEdits() throws Exception {
    String block = stripLineComments(statementStartingWith(readDataSql(), "INSERT INTO warning_rule"));
    assertFalse(block.isBlank(), "未找到 warning_rule 种子段");
    assertTrue(block.contains("ON CONFLICT (rule_code) DO NOTHING"),
        "warning_rule 种子必须是 DO NOTHING，DO UPDATE 会把页面上改过的阈值改回出厂值");
    assertFalse(block.contains("DO UPDATE"),
        "warning_rule 种子仍含 DO UPDATE，重启会覆盖规则配置");
  }

  @Test
  @DisplayName("BIGSERIAL 演示表用 WHERE NOT EXISTS 保证不累积")
  void demoTablesGuardAgainstDuplicateRows() throws Exception {
    String sql = readDataSql();
    for (String table : DEMO_TABLES) {
      String block = statementStartingWith(sql, "INSERT INTO " + table);
      assertFalse(block.isBlank(), "未找到 " + table + " 种子段");
      assertTrue(block.contains("WHERE NOT EXISTS (SELECT 1 FROM " + table),
          table + " 的种子没有 WHERE NOT EXISTS 保护；id 是 BIGSERIAL，"
              + "ON CONFLICT DO NOTHING 命中不了唯一键，每次重启都会重复插入");
    }
  }

  /** 去掉整行注释，避免把说明文字里的 "DO UPDATE" 当成真实语法。 */
  private static String stripLineComments(String sql) {
    return sql.replaceAll("(?m)^\\s*--.*$", "");
  }

  /** 取以 marker 开头的第一条 SQL 语句（到第一个分号为止）。 */
  private static String statementStartingWith(String text, String marker) {
    int start = text.indexOf(marker);
    if (start < 0) return "";
    int end = text.indexOf(';', start);
    return end < 0 ? text.substring(start) : text.substring(start, end + 1);
  }

  private static String readDataSql() throws Exception {
    try (InputStream in = SeedIdempotencyContractTest.class.getResourceAsStream(DATA_SQL)) {
      assertNotNull(in, "classpath 缺少 " + DATA_SQL);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
