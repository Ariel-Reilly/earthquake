-- =====================================================================
-- GeoSentry PostgreSQL + PostGIS 建表脚本（幂等：每次启动可安全重跑）
-- 说明：
--   * spring.sql.init 使用 ScriptUtils 按分号切分，无法解析 PL/pgSQL
--     "$$...$$" 块，因此本文件只包含纯 DDL（表/索引/外键）。
--   * 空间列 geom 由应用层维护（参照方案 5.3 GeometryFactory），
--     种子数据在 data.sql 中用 ST_SetSRID/ST_MakePoint 直接写入。
--   * 统一坐标系统：EPSG:4326（WGS84），空间列 geometry(Point, 4326)
-- =====================================================================
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE IF NOT EXISTS sys_role (
  id BIGSERIAL PRIMARY KEY,
  role_code VARCHAR(64) NOT NULL UNIQUE,
  role_name VARCHAR(64) NOT NULL,
  description VARCHAR(255),
  menu_permissions TEXT,
  button_permissions TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS region (
  id BIGSERIAL PRIMARY KEY,
  parent_id BIGINT,
  region_code VARCHAR(64) NOT NULL UNIQUE,
  region_name VARCHAR(128) NOT NULL,
  region_level VARCHAR(32) NOT NULL,
  path TEXT,
  sort_order INT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS sys_user (
  id BIGSERIAL PRIMARY KEY,
  username VARCHAR(64) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  real_name VARCHAR(64) NOT NULL,
  phone VARCHAR(32),
  role_code VARCHAR(64) NOT NULL,
  region_code VARCHAR(64),
  status VARCHAR(32) NOT NULL DEFAULT '启用',
  last_login_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS sys_menu (
  id BIGSERIAL PRIMARY KEY,
  parent_id BIGINT,
  menu_code VARCHAR(64) NOT NULL UNIQUE,
  menu_name VARCHAR(64) NOT NULL,
  menu_type VARCHAR(32) NOT NULL,
  path VARCHAR(255),
  component VARCHAR(255),
  icon VARCHAR(64),
  permission_code VARCHAR(128),
  sort_order INT NOT NULL DEFAULT 0,
  visible BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ---------------------------------------------------------------------
-- 灾害点（geom 空间点，由应用层从 longitude/latitude 生成）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS hazard_point (
  id BIGSERIAL PRIMARY KEY,
  hazard_code VARCHAR(64) NOT NULL UNIQUE,
  name VARCHAR(128) NOT NULL,
  hazard_type VARCHAR(64) NOT NULL,
  risk_level VARCHAR(32) NOT NULL,
  region_code VARCHAR(64),
  owner VARCHAR(128),
  status VARCHAR(32) NOT NULL,
  longitude NUMERIC(10,6) NOT NULL,
  latitude NUMERIC(10,6) NOT NULL,
  geom geometry(Point, 4326) NOT NULL,
  threat_object TEXT,
  last_inspection_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS inspection_task (
  id BIGSERIAL PRIMARY KEY,
  task_code VARCHAR(64) NOT NULL UNIQUE,
  name VARCHAR(128) NOT NULL,
  task_type VARCHAR(64) NOT NULL,
  executor VARCHAR(64),
  hazard_code VARCHAR(64) REFERENCES hazard_point(hazard_code) ON DELETE SET NULL,
  region_code VARCHAR(64),
  status VARCHAR(32) NOT NULL,
  progress INT NOT NULL DEFAULT 0,
  start_time TIMESTAMPTZ,
  due_time TIMESTAMPTZ,
  created_by VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ---------------------------------------------------------------------
-- 巡检记录（geom 由应用层从 sign_in_lon/sign_in_lat 生成）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS inspection_record (
  id BIGSERIAL PRIMARY KEY,
  record_code VARCHAR(64) NOT NULL UNIQUE,
  task_code VARCHAR(64) NOT NULL REFERENCES inspection_task(task_code) ON DELETE CASCADE,
  hazard_code VARCHAR(64) NOT NULL REFERENCES hazard_point(hazard_code) ON DELETE CASCADE,
  inspector VARCHAR(64) NOT NULL,
  inspect_at TIMESTAMPTZ NOT NULL,
  sign_in_lon NUMERIC(10,6),
  sign_in_lat NUMERIC(10,6),
  geom geometry(Point, 4326),
  distance_m NUMERIC(10,2),
  abnormal_flag BOOLEAN NOT NULL DEFAULT FALSE,
  inspection_result TEXT,
  note TEXT,
  photo_ids TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ---------------------------------------------------------------------
-- 监测设备（longitude/latitude 可选，geom 由应用层生成）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS monitoring_device (
  id BIGSERIAL PRIMARY KEY,
  device_code VARCHAR(64) NOT NULL UNIQUE,
  device_name VARCHAR(128) NOT NULL,
  device_type VARCHAR(64) NOT NULL,
  hazard_code VARCHAR(64) REFERENCES hazard_point(hazard_code) ON DELETE SET NULL,
  region_code VARCHAR(64),
  online_status VARCHAR(32) NOT NULL,
  battery_level INT,
  signal_level VARCHAR(32),
  last_report_at TIMESTAMPTZ,
  install_location VARCHAR(255),
  longitude NUMERIC(10,6),
  latitude NUMERIC(10,6),
  api_key VARCHAR(128),
  geom geometry(Point, 4326),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS warning_rule (
  id BIGSERIAL PRIMARY KEY,
  rule_code VARCHAR(64) NOT NULL UNIQUE,
  rule_name VARCHAR(128) NOT NULL,
  metric_code VARCHAR(64) NOT NULL,
  expression TEXT NOT NULL,
  warning_level VARCHAR(32) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  remark TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS warning_event (
  id BIGSERIAL PRIMARY KEY,
  warning_code VARCHAR(64) NOT NULL UNIQUE,
  rule_code VARCHAR(64) REFERENCES warning_rule(rule_code) ON DELETE SET NULL,
  hazard_code VARCHAR(64) NOT NULL REFERENCES hazard_point(hazard_code) ON DELETE CASCADE,
  title VARCHAR(128) NOT NULL,
  warning_level VARCHAR(32) NOT NULL,
  trigger_value VARCHAR(64),
  status VARCHAR(32) NOT NULL,
  triggered_at TIMESTAMPTZ NOT NULL,
  confirmed_at TIMESTAMPTZ,
  dispatched_at TIMESTAMPTZ,
  closed_at TIMESTAMPTZ,
  close_reason VARCHAR(255),
  closed_by VARCHAR(64),
  rule_snapshot TEXT,
  source VARCHAR(32),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS emergency_event (
  id BIGSERIAL PRIMARY KEY,
  event_code VARCHAR(64) NOT NULL UNIQUE,
  warning_code VARCHAR(64) REFERENCES warning_event(warning_code) ON DELETE SET NULL,
  title VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL,
  commander VARCHAR(64),
  step_name VARCHAR(64),
  result_summary TEXT,
  started_at TIMESTAMPTZ,
  closed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS file_attachment (
  id BIGSERIAL PRIMARY KEY,
  business_type VARCHAR(64) NOT NULL,
  business_code VARCHAR(64) NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  file_size BIGINT,
  file_type VARCHAR(128),
  storage_path VARCHAR(255) NOT NULL,
  file_url VARCHAR(255) NOT NULL,
  uploader VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS risk_score_log (
  id BIGSERIAL PRIMARY KEY,
  hazard_code VARCHAR(64) NOT NULL REFERENCES hazard_point(hazard_code) ON DELETE CASCADE,
  score NUMERIC(5,2) NOT NULL,
  risk_level VARCHAR(32) NOT NULL,
  source VARCHAR(32),
  factors JSONB,
  calculated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS operation_log (
  id BIGSERIAL PRIMARY KEY,
  operator VARCHAR(64) NOT NULL,
  module_name VARCHAR(64) NOT NULL,
  action_name VARCHAR(128) NOT NULL,
  request_method VARCHAR(16),
  request_path VARCHAR(255),
  request_body TEXT,
  result_status VARCHAR(32),
  cost_ms BIGINT,
  ip_address VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS ai_analysis_result (
  id BIGSERIAL PRIMARY KEY,
  business_type VARCHAR(30) NOT NULL,
  business_code VARCHAR(32) NOT NULL,
  model_name VARCHAR(50),
  result_json TEXT NOT NULL,
  confidence DOUBLE PRECISION,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 设备上报的指标样本（位移/雨量/裂缝宽度/倾角等），供预警规则引擎消费
CREATE TABLE IF NOT EXISTS device_metric (
  id BIGSERIAL PRIMARY KEY,
  device_code VARCHAR(64) NOT NULL,
  hazard_code VARCHAR(64),
  metric_code VARCHAR(32) NOT NULL,
  value DOUBLE PRECISION NOT NULL,
  recorded_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  raw_payload TEXT
);

-- =====================================================================
-- 兼容既有卷升级：为新空间列补充 ADD COLUMN IF NOT EXISTS（幂等）
-- 注意：geometry 列不能带 NOT NULL 通过 ALTER 追加（存量行会失败），
-- 因此升级路径下 geom 保持可空，应用层保证写入。
-- =====================================================================
ALTER TABLE hazard_point ADD COLUMN IF NOT EXISTS geom geometry(Point, 4326);
ALTER TABLE inspection_record ADD COLUMN IF NOT EXISTS geom geometry(Point, 4326);
ALTER TABLE monitoring_device ADD COLUMN IF NOT EXISTS longitude NUMERIC(10,6);
ALTER TABLE monitoring_device ADD COLUMN IF NOT EXISTS latitude NUMERIC(10,6);
ALTER TABLE monitoring_device ADD COLUMN IF NOT EXISTS api_key VARCHAR(128);
ALTER TABLE monitoring_device ADD COLUMN IF NOT EXISTS geom geometry(Point, 4326);
ALTER TABLE inspection_task ADD COLUMN IF NOT EXISTS hazard_code VARCHAR(64);
ALTER TABLE emergency_event ADD COLUMN IF NOT EXISTS hazard_code VARCHAR(64);
ALTER TABLE device_metric ALTER COLUMN raw_payload TYPE TEXT;
ALTER TABLE warning_event ALTER COLUMN rule_snapshot TYPE TEXT;
ALTER TABLE warning_event ADD COLUMN IF NOT EXISTS close_reason VARCHAR(255);
ALTER TABLE warning_event ADD COLUMN IF NOT EXISTS closed_by VARCHAR(64);
ALTER TABLE warning_event ADD COLUMN IF NOT EXISTS source VARCHAR(32);
ALTER TABLE ai_analysis_result ALTER COLUMN result_json TYPE TEXT;
-- risk_score_log 补齐 source 列（AI 降级标记 / 融合来源）
ALTER TABLE risk_score_log ADD COLUMN IF NOT EXISTS source VARCHAR(32);
-- 种子附件含长 MIME 类型（如 application/vnd.openxmlformats-...docx），放宽列宽
ALTER TABLE file_attachment ALTER COLUMN file_type TYPE VARCHAR(128);

-- =====================================================================
-- 索引
-- =====================================================================
-- 空间（GiST）
CREATE INDEX IF NOT EXISTS idx_hazard_geom ON hazard_point USING GIST (geom);
CREATE INDEX IF NOT EXISTS idx_record_geom ON inspection_record USING GIST (geom);
CREATE INDEX IF NOT EXISTS idx_device_geom ON monitoring_device USING GIST (geom);

-- 常规查询
CREATE INDEX IF NOT EXISTS idx_hazard_region_level ON hazard_point(region_code, risk_level);
CREATE INDEX IF NOT EXISTS idx_hazard_type ON hazard_point(hazard_type);
CREATE INDEX IF NOT EXISTS idx_task_status_region ON inspection_task(status, region_code);
CREATE INDEX IF NOT EXISTS idx_task_hazard ON inspection_task(hazard_code);
CREATE INDEX IF NOT EXISTS idx_record_task_hazard ON inspection_record(task_code, hazard_code);
CREATE INDEX IF NOT EXISTS idx_device_status_region ON monitoring_device(online_status, region_code);
CREATE INDEX IF NOT EXISTS idx_warning_status_level ON warning_event(status, warning_level);
CREATE INDEX IF NOT EXISTS idx_warning_hazard ON warning_event(hazard_code);
CREATE INDEX IF NOT EXISTS idx_emergency_status ON emergency_event(status);
-- 预警→应急 幂等：同一预警最多一个未闭环应急事件（PostgreSQL 部分唯一索引）
CREATE UNIQUE INDEX IF NOT EXISTS idx_emergency_warning_unique
    ON emergency_event(warning_code)
    WHERE status != '已闭环';
CREATE INDEX IF NOT EXISTS idx_attachment_biz ON file_attachment(business_type, business_code);
CREATE INDEX IF NOT EXISTS idx_ai_result_biz ON ai_analysis_result(business_type, business_code);
-- 设备数据去重 + 查询加速：唯一索引已覆盖 (device_code, metric_code, recorded_at) 查询路径，
-- 替代冗余普通索引（唯一索引自动具备等值查询优化）
CREATE UNIQUE INDEX IF NOT EXISTS idx_device_metric_unique
    ON device_metric(device_code, metric_code, recorded_at);

-- =====================================================================
-- 既有卷外键补丁
-- 说明：PostgreSQL 不支持 ADD CONSTRAINT IF NOT EXISTS，schema.sql 只建表；
--       既有卷上缺失的外键由应用启动时 DatabaseForeignKeyInitializer 幂等补齐。
-- =====================================================================