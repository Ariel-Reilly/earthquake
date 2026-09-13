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
  -- v1.7.0：行政区域中心经纬度（AI 实时监测网格扫描锚点）
  --   必填（NOT NULL DEFAULT 105.0/35.0 是兼容迁移老 region 的回退值）
  center_lon NUMERIC(10, 6) NOT NULL DEFAULT 105.0,
  center_lat NUMERIC(10, 6) NOT NULL DEFAULT 35.0,
  -- 区域类型：PLAIN 平原（低风险默认跳过）/ HILL 丘陵 / MOUNTAIN 山地 / COASTAL 沿海 —— 决定扫描步长与权重
  geo_type VARCHAR(16) NOT NULL DEFAULT 'HILL',
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- v1.7.0：AI 实时监测定位需要的索引
CREATE INDEX IF NOT EXISTS idx_region_level ON region(region_level);
CREATE INDEX IF NOT EXISTS idx_region_parent ON region(parent_id);

-- v1.7.0: region 表加上 AI 实时监测锚点（中心经纬度 + geo_type）
ALTER TABLE region ADD COLUMN IF NOT EXISTS center_lon NUMERIC(10, 6) NOT NULL DEFAULT 105.0;
ALTER TABLE region ADD COLUMN IF NOT EXISTS center_lat NUMERIC(10, 6) NOT NULL DEFAULT 35.0;
ALTER TABLE region ADD COLUMN IF NOT EXISTS geo_type VARCHAR(16) NOT NULL DEFAULT 'HILL';
CREATE INDEX IF NOT EXISTS idx_region_level ON region(region_level);
CREATE INDEX IF NOT EXISTS idx_region_parent ON region(parent_id);
CREATE INDEX IF NOT EXISTS idx_region_monitorable ON region(region_level, geo_type) WHERE region_level IN ('village', 'town', 'district');

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
-- v1.6.0 起改为"AI 实时监测"模式：源端不再预置 hazard_point，
--   行由 AiHazardMonitorScheduler（每 90s）从 AI 评估中实时创建。
--   source = 'AI_MONITOR' | 'MANUAL' | 'DEMO'，discovered_at 是 AI 首次识别时间。
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
  source VARCHAR(32) NOT NULL DEFAULT 'AI_MONITOR',
  discovered_at TIMESTAMPTZ,
  detection_confidence DOUBLE PRECISION,
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
-- v1.6.0 hazard_code 可空（hazard 可能还没被 AI 监测到）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS inspection_record (
  id BIGSERIAL PRIMARY KEY,
  record_code VARCHAR(64) NOT NULL UNIQUE,
  task_code VARCHAR(64) NOT NULL REFERENCES inspection_task(task_code) ON DELETE CASCADE,
  hazard_code VARCHAR(64) REFERENCES hazard_point(hazard_code) ON DELETE SET NULL,
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
  hazard_code VARCHAR(64) REFERENCES hazard_point(hazard_code) ON DELETE SET NULL,
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
-- AI 综合分析结果落库（每 5 分钟刷一次，为预警/巡检/分析三个模块提供底数）
-- =====================================================================
-- 表结构：
--   * hazard_code + source 联合 UNIQUE：保证每个灾害点只有一条最新分数
--   * dimensions_json 存三维度明细（image/precipitation/geographic/device）
--   * reasoning_json + suggested_actions_json 存可解释 AI 输出
--   * analyzed_at 是逻辑"快照时间"；created_at 是物理写入时间

-- AI 设备健康评分：每台 monitoring_device 一条最新记录（UPSERT）
--   * health_score: 0-100, 越高越健康
--   * health_level: HEALTHY / ATTENTION / WARN / CRITICAL
--   * anomaly_score: 来自 ai-service /anomaly 端点（0-100 异常概率）
--   * forecast_risk: 来自 ai-service /forecast 的预测风险（0-100）
--   * metric_coverage: 参与评分的指标条数（缺失指标会降低置信度）
CREATE TABLE IF NOT EXISTS ai_device_health (
  id BIGSERIAL PRIMARY KEY,
  device_code VARCHAR(64) NOT NULL REFERENCES monitoring_device(device_code) ON DELETE CASCADE,
  health_level VARCHAR(32) NOT NULL,
  health_score NUMERIC(5,2) NOT NULL,
  anomaly_score NUMERIC(5,2),
  forecast_risk NUMERIC(5,2),
  metric_coverage INTEGER NOT NULL DEFAULT 0,
  last_anomaly_at TIMESTAMPTZ,
  dimensions_json TEXT,
  reasoning_json TEXT,
  suggested_actions_json TEXT,
  confidence DOUBLE PRECISION,
  model VARCHAR(64),
  degraded BOOLEAN NOT NULL DEFAULT FALSE,
  analyzed_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_ai_device_health_device UNIQUE (device_code)
);
CREATE INDEX IF NOT EXISTS idx_ai_device_health_level ON ai_device_health(health_level);
CREATE INDEX IF NOT EXISTS idx_ai_device_health_analyzed_at ON ai_device_health(analyzed_at DESC);

CREATE TABLE IF NOT EXISTS ai_comprehensive_score (
  id BIGSERIAL PRIMARY KEY,
  hazard_code VARCHAR(64) NOT NULL REFERENCES hazard_point(hazard_code) ON DELETE CASCADE,
  risk_level VARCHAR(32) NOT NULL,
  risk_score NUMERIC(5,2) NOT NULL,
  source VARCHAR(32) NOT NULL DEFAULT 'AI_AUTO',
  disaster_type VARCHAR(32),
  concern_label VARCHAR(32),
  disaster_type_source VARCHAR(32),
  disaster_type_confidence DOUBLE PRECISION,
  dimensions_json TEXT,
  reasoning_json TEXT,
  suggested_actions_json TEXT,
  confidence DOUBLE PRECISION,
  model VARCHAR(64),
  degraded BOOLEAN NOT NULL DEFAULT FALSE,
  device_available BOOLEAN,
  analyzed_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_ai_comprehensive_hazard
    ON ai_comprehensive_score(hazard_code);
CREATE INDEX IF NOT EXISTS idx_ai_score_level ON ai_comprehensive_score(risk_level);
CREATE INDEX IF NOT EXISTS idx_ai_score_analyzed_at ON ai_comprehensive_score(analyzed_at DESC);
CREATE INDEX IF NOT EXISTS idx_ai_score_disaster_type ON ai_comprehensive_score(disaster_type);
CREATE INDEX IF NOT EXISTS idx_ai_score_concern_label ON ai_comprehensive_score(concern_label);

-- 向后兼容：旧库可能没有这几列，幂等补齐（ai_comprehensive_score 升级到 v1.3.0）
ALTER TABLE ai_comprehensive_score ADD COLUMN IF NOT EXISTS disaster_type VARCHAR(32);
ALTER TABLE ai_comprehensive_score ADD COLUMN IF NOT EXISTS concern_label VARCHAR(32);
ALTER TABLE ai_comprehensive_score ADD COLUMN IF NOT EXISTS disaster_type_source VARCHAR(32);
ALTER TABLE ai_comprehensive_score ADD COLUMN IF NOT EXISTS disaster_type_confidence DOUBLE PRECISION;
ALTER TABLE ai_comprehensive_score ADD COLUMN IF NOT EXISTS device_available BOOLEAN;

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
ALTER TABLE warning_event ADD COLUMN IF NOT EXISTS resolve_scope VARCHAR(32);
ALTER TABLE warning_event ADD COLUMN IF NOT EXISTS resolve_area VARCHAR(64);
ALTER TABLE ai_analysis_result ALTER COLUMN result_json TYPE TEXT;
-- risk_score_log 补齐 source 列（AI 降级标记 / 融合来源）
ALTER TABLE risk_score_log ADD COLUMN IF NOT EXISTS source VARCHAR(32);
-- 种子附件含长 MIME 类型（如 application/vnd.openxmlformats-...docx），放宽列宽
ALTER TABLE file_attachment ALTER COLUMN file_type TYPE VARCHAR(128);

-- =====================================================================
-- v1.6.0：灾害数据全部由 AI 实时监测产出
--   * hazard_point.source 区分数据来源（AI_MONITOR / MANUAL / DEMO）
--   * discovered_at 是 AI 首次识别时间，用于 AI 监测列表排序
--   * detection_confidence 是 AI 给出 0-1 置信度（前端展示用）
--   * inspection_record / warning_event 的 hazard_code 已从 NOT NULL 改 NULL，
--     存量既有卷需要 DROP NOT NULL
-- =====================================================================
ALTER TABLE hazard_point ADD COLUMN IF NOT EXISTS source VARCHAR(32) NOT NULL DEFAULT 'AI_MONITOR';
ALTER TABLE hazard_point ADD COLUMN IF NOT EXISTS discovered_at TIMESTAMPTZ;
ALTER TABLE hazard_point ADD COLUMN IF NOT EXISTS detection_confidence DOUBLE PRECISION;
ALTER TABLE inspection_record ALTER COLUMN hazard_code DROP NOT NULL;
ALTER TABLE warning_event ALTER COLUMN hazard_code DROP NOT NULL;

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
-- v1.6.0：AI 监测实时灾害列表专用（按 source + discovered_at 倒序）
CREATE INDEX IF NOT EXISTS idx_hazard_source ON hazard_point(source, discovered_at DESC);
CREATE INDEX IF NOT EXISTS idx_hazard_discovered ON hazard_point(discovered_at DESC) WHERE source = 'AI_MONITOR';
CREATE INDEX IF NOT EXISTS idx_task_status_region ON inspection_task(status, region_code);
CREATE INDEX IF NOT EXISTS idx_task_hazard ON inspection_task(hazard_code);
CREATE INDEX IF NOT EXISTS idx_record_task_hazard ON inspection_record(task_code, hazard_code);
CREATE INDEX IF NOT EXISTS idx_device_status_region ON monitoring_device(online_status, region_code);
CREATE INDEX IF NOT EXISTS idx_warning_status_level ON warning_event(status, warning_level);
CREATE INDEX IF NOT EXISTS idx_warning_hazard ON warning_event(hazard_code);
CREATE INDEX IF NOT EXISTS idx_emergency_status ON emergency_event(status);
-- 预警→应急 幂等：同一预警最多一个未闭环应急事件（PostgreSQL 部分唯一索引）
-- 注意：status IS NULL 不受此索引约束，但 emergency_event.status 列有 NOT NULL 约束（L187），所以安全。
CREATE UNIQUE INDEX IF NOT EXISTS idx_emergency_warning_unique
    ON emergency_event(warning_code)
    WHERE status != '已闭环';
CREATE INDEX IF NOT EXISTS idx_attachment_biz ON file_attachment(business_type, business_code);
CREATE INDEX IF NOT EXISTS idx_ai_result_biz ON ai_analysis_result(business_type, business_code);
-- 设备数据去重 + 查询加速：唯一索引已覆盖 (device_code, metric_code, recorded_at) 查询路径，
-- 替代冗余普通索引（唯一索引自动具备等值查询优化）
CREATE UNIQUE INDEX IF NOT EXISTS idx_device_metric_unique
    ON device_metric(device_code, metric_code, recorded_at);
-- 规则引擎按 hazard 查询最新指标：覆盖 DeviceMetricRepository.findLatestByHazardCode* 和 sumByHazardRecentHours
CREATE INDEX IF NOT EXISTS idx_device_metric_hazard ON device_metric(hazard_code);
CREATE INDEX IF NOT EXISTS idx_device_metric_hazard_recorded ON device_metric(hazard_code, recorded_at DESC);

-- =====================================================================
-- v1.7.0 既有卷外键补丁 & 字段补丁
-- 说明：PostgreSQL 不支持 ADD CONSTRAINT IF NOT EXISTS，schema.sql 只建表；
--       既有卷上缺失的外键由应用启动时 DatabaseForeignKeyInitializer 幂等补齐。
-- =====================================================================

-- AI 承灾体评估 & 历史灾情字段（v1.7.0 HazardPoint 新增字段）
ALTER TABLE hazard_point ADD COLUMN IF NOT EXISTS exposure_buildings INTEGER NOT NULL DEFAULT 0;
ALTER TABLE hazard_point ADD COLUMN IF NOT EXISTS exposure_population BIGINT NOT NULL DEFAULT 0;
ALTER TABLE hazard_point ADD COLUMN IF NOT EXISTS land_use_type VARCHAR(32) NOT NULL DEFAULT 'unknown';
ALTER TABLE hazard_point ADD COLUMN IF NOT EXISTS historical_max_score DOUBLE PRECISION NOT NULL DEFAULT 0.0;
ALTER TABLE hazard_point ADD COLUMN IF NOT EXISTS historical_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE hazard_point ADD COLUMN IF NOT EXISTS last_inspection_device_type VARCHAR(64);

-- =====================================================================
-- v1.9.0 设备地图与轨迹回放 (P1 /device-map)
--   设备历史位置采样，用于"设备地图"和"轨迹回放"：
--     - device_code 关联 monitoring_device（不强制外键，便于新增设备立即有轨迹）
--     - longitude/latitude 为采样点经纬度
--     - geom 由应用层 GeometryFactory 生成（EPSG:4326），GiST 索引用于时空查询
--     - speed_kmh / heading_deg 可选，来源于 MQTT 上行 payload 或巡检打卡
--     - recorded_at 单调递增，回放时按它排序
--   索引：
--     - idx_trajectory_device_time：单设备轨迹查询（按时间范围）
--     - idx_trajectory_geom：地图视口范围查询
--     - idx_trajectory_recorded：最近轨迹清理 / 限流
-- =====================================================================
CREATE TABLE IF NOT EXISTS device_trajectory (
  id BIGSERIAL PRIMARY KEY,
  device_code VARCHAR(64) NOT NULL,
  longitude NUMERIC(10,6) NOT NULL,
  latitude NUMERIC(10,6) NOT NULL,
  geom geometry(Point, 4326),
  altitude_m NUMERIC(8,2),
  speed_kmh NUMERIC(6,2),
  heading_deg NUMERIC(5,2),
  battery_level INT,
  source VARCHAR(32) NOT NULL DEFAULT 'MQTT',
  recorded_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_trajectory_device_time
    ON device_trajectory(device_code, recorded_at DESC);
CREATE INDEX IF NOT EXISTS idx_trajectory_recorded
    ON device_trajectory(recorded_at DESC);
CREATE INDEX IF NOT EXISTS idx_trajectory_geom
    ON device_trajectory USING GIST (geom);

-- =====================================================================
-- v1.7.0 统一事件状态机（P3-4）
--   * unified_event 是 hazard_point / warning_event / emergency_event 三表的
--     "顶层事件视图"，一行对应一个完整的"监测 → 确认 → 预警 → 应急 → 关闭" 生命周期。
--   * state 列由 Spring StateMachine 维护（参见 EventStateMachineConfig.java），
--     任何状态变更都走 EventStateService.transition() 校验合法性。
--   * hazard_code / warning_code / emergency_code 三个反向引用允许为空
--     （并非所有事件都会触发到应急阶段）。
--   * close_reason / closed_by 仅在终态（CLOSED / REJECTED）下写入。
-- =====================================================================
CREATE TABLE IF NOT EXISTS unified_event (
  id BIGSERIAL PRIMARY KEY,
  event_code VARCHAR(64) NOT NULL UNIQUE,
  state VARCHAR(32) NOT NULL,
  hazard_code VARCHAR(64),
  warning_code VARCHAR(64),
  emergency_code VARCHAR(64),
  title VARCHAR(256),
  source VARCHAR(32),
  current_state_since TIMESTAMPTZ,
  last_transition_at TIMESTAMPTZ,
  last_trigger VARCHAR(32),
  close_reason VARCHAR(512),
  closed_by VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_unified_state ON unified_event(state);
CREATE INDEX IF NOT EXISTS idx_unified_hazard ON unified_event(hazard_code);
CREATE INDEX IF NOT EXISTS idx_unified_warning ON unified_event(warning_code);
CREATE INDEX IF NOT EXISTS idx_unified_emergency ON unified_event(emergency_code);
CREATE INDEX IF NOT EXISTS idx_unified_updated ON unified_event(updated_at DESC);

-- 三表反向引用 unified_event_id（可选列，老数据可保持 NULL，由后台补齐）
ALTER TABLE hazard_point ADD COLUMN IF NOT EXISTS unified_event_id BIGINT REFERENCES unified_event(id) ON DELETE SET NULL;
ALTER TABLE warning_event ADD COLUMN IF NOT EXISTS unified_event_id BIGINT REFERENCES unified_event(id) ON DELETE SET NULL;
ALTER TABLE emergency_event ADD COLUMN IF NOT EXISTS unified_event_id BIGINT REFERENCES unified_event(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_hazard_unified ON hazard_point(unified_event_id) WHERE unified_event_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_warning_unified ON warning_event(unified_event_id) WHERE unified_event_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_emergency_unified ON emergency_event(unified_event_id) WHERE unified_event_id IS NOT NULL;

-- =====================================================================
-- P0 阶段新增表
-- =====================================================================

-- P0-3: 个人中心扩展资料（与 sys_user 并行的可空副表）
CREATE TABLE IF NOT EXISTS user_profile (
  id BIGSERIAL PRIMARY KEY,
  username VARCHAR(64) NOT NULL UNIQUE,
  display_name VARCHAR(64),
  phone VARCHAR(32),
  email VARCHAR(128),
  department VARCHAR(128),
  avatar VARCHAR(512),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_user_profile_username ON user_profile(username);

-- P0-3: 用户通知偏好（独立表，channels / levels 存为 JSON 字符串）
CREATE TABLE IF NOT EXISTS user_preference (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL UNIQUE,
  channels TEXT,
  levels TEXT,
  language VARCHAR(10) DEFAULT 'zh-CN',
  theme VARCHAR(10) DEFAULT 'light',
  compact_mode BOOLEAN DEFAULT FALSE,
  quiet_hours_enabled BOOLEAN DEFAULT FALSE,
  quiet_start VARCHAR(8) DEFAULT '22:00',
  quiet_end VARCHAR(8) DEFAULT '08:00',
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_user_pref_user ON user_preference(user_id);

-- P0-4: 会签（每个会签实例下挂多个 signer 行）
CREATE TABLE IF NOT EXISTS event_sign (
  id BIGSERIAL PRIMARY KEY,
  sign_id VARCHAR(64) NOT NULL UNIQUE,
  event_id VARCHAR(64) NOT NULL,
  mode VARCHAR(20) NOT NULL DEFAULT 'PARALLEL',
  timeout_hours INT DEFAULT 24,
  timeout_action VARCHAR(20) DEFAULT 'ESCALATE',
  comment TEXT,
  initiator VARCHAR(64),
  status VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
  signer_id VARCHAR(64) NOT NULL,
  signer_name VARCHAR(64),
  specialty VARCHAR(64),
  order_index INT DEFAULT 0,
  signer_status VARCHAR(20) DEFAULT 'PENDING',
  opinion TEXT,
  signed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  completed_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_event_sign_event ON event_sign(event_id);
CREATE INDEX IF NOT EXISTS idx_event_sign_status ON event_sign(status);

-- P0-4: 事件转派历史
CREATE TABLE IF NOT EXISTS event_transfer (
  id BIGSERIAL PRIMARY KEY,
  event_id VARCHAR(64) NOT NULL,
  from_user VARCHAR(64),
  to_user VARCHAR(64) NOT NULL,
  reason TEXT,
  operator VARCHAR(64),
  transferred_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_event_transfer_event ON event_transfer(event_id);

-- =====================================================================
-- P1 阶段：任务 6 - 统一通知路由服务
-- =====================================================================

-- 通知发送记录
CREATE TABLE IF NOT EXISTS notification_record (
  id BIGSERIAL PRIMARY KEY,
  biz_type VARCHAR(50),
  biz_id VARCHAR(64),
  disaster_type VARCHAR(50),
  level VARCHAR(20),
  title VARCHAR(255),
  content TEXT,
  channels JSON,
  status VARCHAR(20),
  created_by VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_notification_record_biz ON notification_record(biz_type, biz_id);
CREATE INDEX IF NOT EXISTS idx_notification_record_status ON notification_record(status);
CREATE INDEX IF NOT EXISTS idx_notification_record_disaster ON notification_record(disaster_type, level);

-- 通知回执
CREATE TABLE IF NOT EXISTS notification_receipt (
  id BIGSERIAL PRIMARY KEY,
  record_id BIGINT NOT NULL REFERENCES notification_record(id) ON DELETE CASCADE,
  channel VARCHAR(20),
  target VARCHAR(255),
  status VARCHAR(20),
  error_msg TEXT,
  retry_count INT DEFAULT 0,
  sent_at TIMESTAMPTZ,
  delivered_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_notification_receipt_record ON notification_receipt(record_id);
CREATE INDEX IF NOT EXISTS idx_notification_receipt_status ON notification_receipt(status);
CREATE INDEX IF NOT EXISTS idx_notification_receipt_channel ON notification_receipt(channel, status);

-- 渠道配置
CREATE TABLE IF NOT EXISTS notification_channel_config (
  id BIGSERIAL PRIMARY KEY,
  disaster_type VARCHAR(50),
  level VARCHAR(20),
  channels JSON,
  template_mapping JSON,
  priority_channels JSON,
  enabled BOOLEAN DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_notification_config_disaster ON notification_channel_config(disaster_type, level);
CREATE INDEX IF NOT EXISTS idx_notification_config_enabled ON notification_channel_config(enabled);

-- =====================================================================
-- P1 阶段：任务 7 - 独立规则引擎
-- =====================================================================

-- 规则表
CREATE TABLE IF NOT EXISTS rule (
  id BIGSERIAL PRIMARY KEY,
  rule_code VARCHAR(64) NOT NULL UNIQUE,
  name VARCHAR(255) NOT NULL,
  disaster_type VARCHAR(50),
  level VARCHAR(20),
  priority INT DEFAULT 0,
  conditions JSON NOT NULL,
  actions JSON NOT NULL,
  dsl_text TEXT,
  version INT DEFAULT 1,
  enabled BOOLEAN DEFAULT TRUE,
  created_by VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_rule_disaster ON rule(disaster_type, level);
CREATE INDEX IF NOT EXISTS idx_rule_enabled ON rule(enabled);

-- 规则执行日志
CREATE TABLE IF NOT EXISTS rule_execution_log (
  id BIGSERIAL PRIMARY KEY,
  rule_id BIGINT REFERENCES rule(id) ON DELETE SET NULL,
  triggered BOOLEAN,
  input_data JSON,
  result JSON,
  executed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_rule_log_rule ON rule_execution_log(rule_id);
CREATE INDEX IF NOT EXISTS idx_rule_log_executed ON rule_execution_log(executed_at DESC);

-- 字段字典
CREATE TABLE IF NOT EXISTS field_dict (
  id BIGSERIAL PRIMARY KEY,
  field VARCHAR(64) NOT NULL,
  type VARCHAR(20),
  source VARCHAR(50),
  unit VARCHAR(20),
  description VARCHAR(255)
);
CREATE INDEX IF NOT EXISTS idx_field_dict_field ON field_dict(field);

-- =====================================================================
-- P1 阶段：任务 8 - AI 模型漂移监控
-- =====================================================================

-- 漂移监控记录
CREATE TABLE IF NOT EXISTS drift_monitor_record (
  id BIGSERIAL PRIMARY KEY,
  model_id VARCHAR(64),
  feature VARCHAR(64),
  psi DOUBLE PRECISION,
  ks_stat DOUBLE PRECISION,
  p_value DOUBLE PRECISION,
  drift_level VARCHAR(20),
  detected_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_drift_record_model ON drift_monitor_record(model_id);
CREATE INDEX IF NOT EXISTS idx_drift_record_detected ON drift_monitor_record(detected_at DESC);

-- 重训记录
CREATE TABLE IF NOT EXISTS retrain_record (
  id BIGSERIAL PRIMARY KEY,
  model_id VARCHAR(64),
  trigger_reason VARCHAR(255),
  old_version VARCHAR(20),
  new_version VARCHAR(20),
  status VARCHAR(20),
  metrics JSON,
  started_at TIMESTAMPTZ,
  finished_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_retrain_model ON retrain_record(model_id);

-- 基线数据
CREATE TABLE IF NOT EXISTS drift_baseline (
  id BIGSERIAL PRIMARY KEY,
  model_id VARCHAR(64),
  feature VARCHAR(64),
  baseline_data JSON,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_drift_baseline_model ON drift_baseline(model_id);

-- =====================================================================
-- P1 阶段：任务 9 - 多模态融合研判
-- =====================================================================

-- 多模态融合记录
CREATE TABLE IF NOT EXISTS multimodal_fusion_record (
  id BIGSERIAL PRIMARY KEY,
  event_id VARCHAR(64),
  fused_score DOUBLE PRECISION,
  level VARCHAR(20),
  confidence DOUBLE PRECISION,
  contributions JSON,
  explanation TEXT,
  modal_details JSON,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_multimodal_event ON multimodal_fusion_record(event_id);
CREATE INDEX IF NOT EXISTS idx_multimodal_created ON multimodal_fusion_record(created_at DESC);

-- 模态权重配置
CREATE TABLE IF NOT EXISTS modal_weight_config (
  id BIGSERIAL PRIMARY KEY,
  disaster_type VARCHAR(50),
  weights JSON,
  optimized_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_modal_weight_disaster ON modal_weight_config(disaster_type);
