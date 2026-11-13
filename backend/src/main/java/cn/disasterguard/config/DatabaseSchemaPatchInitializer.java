package cn.disasterguard.config;

import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 既有数据卷的结构补丁（幂等）。
 *
 * <p>背景：{@code event_sign} 的语义是「一次会签会话 × 一个会签人 = 一行」，
 * 同一 {@code sign_id} 会有多行；但历史 schema 给 {@code sign_id} 误加了 UNIQUE 约束，
 * 多人会签在插入第二个会签人时直接触发唯一约束冲突（接口 500）。
 *
 * <p>{@code schema.sql} 已改为普通索引，但已存在的数据库不会重跑 {@code CREATE TABLE}，
 * 因此这里在每次启动时幂等地：删除 sign_id 上的唯一约束 + 补建普通索引。
 * 对全新库是空操作（没有该约束）；对旧库是一次性修复。
 */
@Component
public class DatabaseSchemaPatchInitializer implements ApplicationRunner {

  private final JdbcTemplate jdbcTemplate;

  public DatabaseSchemaPatchInitializer(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void run(ApplicationArguments args) {
    // 平台设置落库表（旧数据卷不会重跑 schema.sql，这里幂等补建）
    ensureSystemSettingTable();
    if (!tableExists("event_sign")) {
      return;
    }
    dropSignIdUniqueConstraint();
  }

  /**
   * 幂等创建 {@code system_setting} 表：设置页从 localStorage 迁到后端持久化，
   * 既有数据卷也需要这张表，否则第一个 GET /api/settings 会报表不存在。
   */
  private void ensureSystemSettingTable() {
    jdbcTemplate.execute(
        "CREATE TABLE IF NOT EXISTS system_setting ("
            + "setting_key VARCHAR(64) PRIMARY KEY, "
            + "setting_value TEXT, "
            + "updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), "
            + "updated_by VARCHAR(64))");
  }

  /**
   * 判断表是否存在：{@code to_regclass} 在表不存在时返回 null，不会抛异常。
   *
   * @param table 表名
   * @return 是否存在
   */
  private boolean tableExists(String table) {
    Boolean exists = jdbcTemplate.queryForObject(
        "SELECT to_regclass(?) IS NOT NULL", Boolean.class, table);
    return Boolean.TRUE.equals(exists);
  }

  /** 删除 event_sign.sign_id 上的唯一约束并补建普通索引（可重复执行）。 */
  private void dropSignIdUniqueConstraint() {
    List<String> constraintNames = jdbcTemplate.queryForList(
        "SELECT con.conname FROM pg_constraint con "
            + "JOIN pg_class rel ON rel.oid = con.conrelid "
            + "JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace "
            + "JOIN pg_attribute att ON att.attrelid = rel.oid AND att.attnum = ANY (con.conkey) "
            + "WHERE rel.relname = 'event_sign' AND nsp.nspname = current_schema() "
            + "AND con.contype = 'u' AND att.attname = 'sign_id'",
        String.class);
    for (String constraintName : constraintNames) {
      jdbcTemplate.execute("ALTER TABLE event_sign DROP CONSTRAINT " + constraintName);
    }
    jdbcTemplate.execute(
        "CREATE INDEX IF NOT EXISTS idx_event_sign_sign ON event_sign(sign_id)");
  }
}
