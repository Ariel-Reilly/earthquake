-- =====================================================================
-- operation_log 归档策略
-- 将超过 90 天的审计日志迁移到 operation_log_archive 表
-- 建议由 pg_cron 或外部调度器（cron / Airflow / 自建定时）每日凌晨执行
-- 独立脚本：不依赖 spring.sql.init，手动执行或由 cron 触发
-- =====================================================================

-- 1) 确保归档表存在（结构与 operation_log 完全一致，无索引减少写入开销）
CREATE TABLE IF NOT EXISTS operation_log_archive (
  id             BIGSERIAL PRIMARY KEY,
  operator       VARCHAR(64) NOT NULL,
  module_name    VARCHAR(64) NOT NULL,
  action_name    VARCHAR(128) NOT NULL,
  request_method VARCHAR(16),
  request_path   VARCHAR(255),
  request_body   TEXT,
  result_status  VARCHAR(32),
  cost_ms        BIGINT,
  ip_address     VARCHAR(64),
  created_at     TIMESTAMPTZ NOT NULL
);

-- 2) 一次性归档命令（可直接执行或由 pg_cron 调度）
--    参数：RETENTION_DAYS（默认 90 天）
--    典型用法：SET LOCAL disasterguard.retention_days = 90; 然后调用 archive_operation_logs()
CREATE OR REPLACE FUNCTION archive_operation_logs(retention_days INT DEFAULT 90)
RETURNS TABLE(migrated INT, deleted INT) AS $$
DECLARE
  cutoff TIMESTAMPTZ;
  rows_migrated INT := 0;
  rows_deleted  INT := 0;
BEGIN
  cutoff := NOW() - (retention_days || ' days')::INTERVAL;

  -- 迁移到归档表
  INSERT INTO operation_log_archive
  SELECT * FROM operation_log
  WHERE created_at < cutoff
  ON CONFLICT DO NOTHING;

  GET DIAGNOSTICS rows_migrated = ROW_COUNT;

  -- 删除已归档的源数据
  DELETE FROM operation_log WHERE created_at < cutoff;
  GET DIAGNOSTICS rows_deleted = ROW_COUNT;

  RETURN QUERY SELECT rows_migrated, rows_deleted;
END;
$$ LANGUAGE plpgsql;

-- 3) 手动执行归档示例（保留 90 天）
-- SELECT * FROM archive_operation_logs(90);

-- 4) 与 pg_cron 集成示例（需先 CREATE EXTENSION pg_cron;）
-- SELECT cron.schedule('archive-operation-log', '0 3 * * *',
--   $$ SELECT archive_operation_logs(90); $$);
-- SELECT cron.enable_schedule('archive-operation-log');

-- 5) 定期 vacuum（归档 + 删除后执行）
-- VACUUM ANALYZE operation_log;
