package cn.geosentry.config;

import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 既有数据卷外键补丁。
 *
 * <p>schema.sql 受 spring.sql.init（ScriptUtils 按分号切分、不识别 PL/pgSQL $$ 块）
 * 限制，只负责建表/索引；CREATE TABLE 中的外键仅对全新库生效。
 * 本组件在每次启动时以 JDBC 单条语句方式补齐缺失的外键约束，幂等安全。
 */
@Component
public class DatabaseForeignKeyInitializer implements ApplicationRunner {

  private static final Map<String, String> CONSTRAINTS = Map.ofEntries(
      Map.entry("fk_task_hazard", "ALTER TABLE inspection_task ADD CONSTRAINT fk_task_hazard FOREIGN KEY (hazard_code) REFERENCES hazard_point(hazard_code) ON DELETE SET NULL"),
      Map.entry("fk_inspection_record_task", "ALTER TABLE inspection_record ADD CONSTRAINT fk_inspection_record_task FOREIGN KEY (task_code) REFERENCES inspection_task(task_code) ON DELETE CASCADE"),
      Map.entry("fk_inspection_record_hazard", "ALTER TABLE inspection_record ADD CONSTRAINT fk_inspection_record_hazard FOREIGN KEY (hazard_code) REFERENCES hazard_point(hazard_code) ON DELETE CASCADE"),
      Map.entry("fk_device_hazard", "ALTER TABLE monitoring_device ADD CONSTRAINT fk_device_hazard FOREIGN KEY (hazard_code) REFERENCES hazard_point(hazard_code) ON DELETE SET NULL"),
      Map.entry("fk_warning_rule", "ALTER TABLE warning_event ADD CONSTRAINT fk_warning_rule FOREIGN KEY (rule_code) REFERENCES warning_rule(rule_code) ON DELETE SET NULL"),
      Map.entry("fk_warning_hazard", "ALTER TABLE warning_event ADD CONSTRAINT fk_warning_hazard FOREIGN KEY (hazard_code) REFERENCES hazard_point(hazard_code) ON DELETE CASCADE"),
      Map.entry("fk_emergency_warning", "ALTER TABLE emergency_event ADD CONSTRAINT fk_emergency_warning FOREIGN KEY (warning_code) REFERENCES warning_event(warning_code) ON DELETE SET NULL"),
      Map.entry("fk_risk_score_hazard", "ALTER TABLE risk_score_log ADD CONSTRAINT fk_risk_score_hazard FOREIGN KEY (hazard_code) REFERENCES hazard_point(hazard_code) ON DELETE CASCADE")
  );

  private final JdbcTemplate jdbcTemplate;

  public DatabaseForeignKeyInitializer(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void run(ApplicationArguments args) {
    CONSTRAINTS.forEach((name, ddl) -> {
      Integer count = jdbcTemplate.queryForObject(
          "SELECT count(*) FROM pg_constraint WHERE conname = ?", Integer.class, name);
      if (count == null || count == 0) {
        jdbcTemplate.execute(ddl);
      }
    });
  }
}