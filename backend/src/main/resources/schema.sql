-- =====================================================================
-- DisasterGuard PostgreSQL + PostGIS 建表脚本（幂等：每次启动可安全重跑）
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
  email VARCHAR(128),
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
-- v2.1.0 起改为"投影"模式（Task #1 P1）：
--   * hazard_point 是从 unified_event WHERE kind='HAZARD' 投影出的视图，
--     由底部 v2.1.0 迁移块创建/替换。
--   * 本块先建一个临时物理表，让下方引用 hazard_point 的 CREATE TABLE /ALTER
--     块顺利通过；该物理表在底部 v2.1.0 迁移块被 DROP CASCADE 并替换为视图。
--   * 升级场景下，若 hazard_point 已是视图（v2.1.0 已生效），先 DROP VIEW 再
--     CREATE TABLE IF NOT EXISTS 即可让本块保持幂等。
--   * 注意：ScriptUtils 按 ; 切分时无法解析 PL/pgSQL 的 $$ ... $$ 嵌套，
--     故本块不使用 DO $E$ ... $F$ ... $E$ 包装，改用纯 DDL。
-- ---------------------------------------------------------------------
DROP VIEW IF EXISTS hazard_point;
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
  -- MinIO 预签名 URL 含 AK/签名/时间戳，实测约 500 字符，255 会让附件上传在落库时失败。
  file_url VARCHAR(1024) NOT NULL,
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
-- ---------------------------------------------------------------------
-- (v2.1.0: 原 ALTER TABLE hazard_point ADD COLUMN geom 已迁到 unified_event，
--  由底部 v2.1.0 迁移块统一执行)
-- ---------------------------------------------------------------------
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
-- 人工上报预警（source='MANUAL'）补充字段：
-- hazard_type 与 hazard_point.hazard_type 对齐（英文码 landslide / collapse / ...），
-- manual_payload 承接向导页的扩展字段（区域/经纬度/影响半径/渠道/正文/有效期/上报人）。
ALTER TABLE warning_event ADD COLUMN IF NOT EXISTS hazard_type VARCHAR(64);
ALTER TABLE warning_event ADD COLUMN IF NOT EXISTS manual_payload TEXT;
-- 幂等：result_json 在上一次运行可能已被迁移到 JSONB 并创建 GIN 索引，
-- 直接 ALTER TYPE TEXT 会被 GIN 拒绝。先 drop GIN，下方 B 段会把列变 JSONB
-- 并在末尾的 CREATE INDEX IF NOT EXISTS 重建 GIN。
DROP INDEX IF EXISTS idx_ai_result_biz_gin;
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
-- ---------------------------------------------------------------------
-- (v2.1.0: hazard_point.source / discovered_at / detection_confidence
--  已迁到 unified_event.hazard_source / discovered_at / detection_confidence,
--  由底部 v2.1.0 迁移块统一执行)
-- ---------------------------------------------------------------------
ALTER TABLE inspection_record ALTER COLUMN hazard_code DROP NOT NULL;
ALTER TABLE warning_event ALTER COLUMN hazard_code DROP NOT NULL;

-- =====================================================================
-- 索引
-- =====================================================================
-- 空间（GiST）
-- ---------------------------------------------------------------------
-- (v2.1.0: idx_hazard_geom 已迁为 unified_event 上的 partial GiST 索引
--  idx_unified_hazard_geom WHERE kind='HAZARD'，由底部 v2.1.0 迁移块创建)
-- ---------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_record_geom ON inspection_record USING GIST (geom);
CREATE INDEX IF NOT EXISTS idx_device_geom ON monitoring_device USING GIST (geom);

-- 常规查询
-- ---------------------------------------------------------------------
-- (v2.1.0: idx_hazard_region_level / idx_hazard_type 已迁为 unified_event
--  上的 partial 索引，由底部 v2.1.0 迁移块创建)
-- ---------------------------------------------------------------------
-- (v2.1.0: idx_hazard_source / idx_hazard_discovered 已迁为 unified_event
--  上的 partial 索引 idx_unified_hazard_source / idx_unified_hazard_discovered，
--  由底部 v2.1.0 迁移块创建)
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

-- AI 承灾体评估 & 历史灾情字段（v2.1.0 已迁到 unified_event，
-- 由底部 v2.1.0 迁移块统一执行）
-- 原 hazard_point 字段名: exposure_buildings, exposure_population, land_use_type,
--   historical_max_score, historical_count, last_inspection_device_type

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

-- (v2.1.0: 原 hazard_point.unified_event_id 反向引用列已废弃；
--  hazard_point 现在是 unified_event WHERE kind='HAZARD' 的视图，
--  1:1 关系天然成立，无需单独的反向 FK 列)
ALTER TABLE warning_event ADD COLUMN IF NOT EXISTS unified_event_id BIGINT REFERENCES unified_event(id) ON DELETE SET NULL;
ALTER TABLE emergency_event ADD COLUMN IF NOT EXISTS unified_event_id BIGINT REFERENCES unified_event(id) ON DELETE SET NULL;
-- (v2.1.0: idx_hazard_unified 已废弃——视图本身已经天然暴露 unified_event.id)
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

-- =====================================================================
-- v2.0.0：7角色权限体系 + 完整演示数据集
-- 依赖：sys_role(id)、region(id)、sys_user(id) 已在 schema.sql 前部定义。
-- 新增表与既有表并列，均使用 IF NOT EXISTS 保证幂等。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 8.1 系统字典（灾种/等级/状态/渠道/设备类型/资源类型）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_dict (
  id BIGSERIAL PRIMARY KEY,
  type VARCHAR(64) NOT NULL,
  label VARCHAR(128) NOT NULL,
  value VARCHAR(128) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  description VARCHAR(255),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_dict_type ON sys_dict(type);

-- ---------------------------------------------------------------------
-- 8.2 权限表（与前端 routePermissionMap / ROLE_PERMISSION_MAP 一一对应）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_permission (
  id BIGSERIAL PRIMARY KEY,
  code VARCHAR(64) NOT NULL UNIQUE,
  name VARCHAR(128) NOT NULL,
  type VARCHAR(16) NOT NULL DEFAULT 'OP',
  -- ROUTE=路由级权限（菜单可见性）, OP=操作级权限（按钮/接口）
  route_path VARCHAR(255),
  parent_code VARCHAR(64),
  sort_order INT NOT NULL DEFAULT 0,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_perm_code ON sys_permission(code);
CREATE INDEX IF NOT EXISTS idx_perm_type ON sys_permission(type);

-- ---------------------------------------------------------------------
-- 8.3 角色-权限关联表
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_role_permission (
  id BIGSERIAL PRIMARY KEY,
  role_id BIGINT NOT NULL REFERENCES sys_role(id) ON DELETE CASCADE,
  perm_code VARCHAR(64) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE(role_id, perm_code)
);
CREATE INDEX IF NOT EXISTS idx_rp_role ON sys_role_permission(role_id);

-- ---------------------------------------------------------------------
-- 8.4 AI 模型元数据表
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_model (
  id BIGSERIAL PRIMARY KEY,
  model_code VARCHAR(64) NOT NULL UNIQUE,
  model_name VARCHAR(128) NOT NULL,
  version VARCHAR(32) NOT NULL,
  model_type VARCHAR(32) NOT NULL,
  -- xgboost / lstm / yolo / isolation_forest / comprehensive / device_health
  status VARCHAR(16) NOT NULL DEFAULT 'OFFLINE',
  -- ONLINE / OFFLINE / TRAINING / DEPLOYING / ROLLBACK
  metrics JSONB,
  -- {"accuracy":0.92,"f1":0.89,"auc":0.95} 等训练指标
  config JSONB,
  -- 推理参数：阈值、窗口大小、特征列等
  description TEXT,
  deployed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_ai_model_type ON ai_model(model_type);
CREATE INDEX IF NOT EXISTS idx_ai_model_status ON ai_model(status);

-- ---------------------------------------------------------------------
-- 8.5 数据源配置表
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS data_source (
  id BIGSERIAL PRIMARY KEY,
  ds_code VARCHAR(64) NOT NULL UNIQUE,
  ds_name VARCHAR(128) NOT NULL,
  ds_type VARCHAR(32) NOT NULL,
  -- satellite / weather / mqtt / terrain / custom
  endpoint VARCHAR(512),
  auth_type VARCHAR(32),
  -- NONE / BASIC / API_KEY / OAuth2 / TOKEN
  auth_config JSONB,
  status VARCHAR(16) NOT NULL DEFAULT 'DISCONNECTED',
  -- CONNECTED / DISCONNECTED / ERROR / SYNCING
  last_sync_at TIMESTAMPTZ,
  sync_interval INT DEFAULT 3600,
  -- 秒，REALTIME 类型忽略
  created_by VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_ds_type ON data_source(ds_type);
CREATE INDEX IF NOT EXISTS idx_ds_status ON data_source(status);

-- ---------------------------------------------------------------------
-- 8.6 数据同步任务表
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS data_sync_task (
  id BIGSERIAL PRIMARY KEY,
  task_code VARCHAR(64) NOT NULL UNIQUE,
  task_name VARCHAR(128) NOT NULL,
  data_source_id BIGINT REFERENCES data_source(id) ON DELETE SET NULL,
  sync_type VARCHAR(16) NOT NULL DEFAULT 'PERIODIC',
  -- REALTIME / PERIODIC / MANUAL
  cron_expr VARCHAR(64),
  status VARCHAR(16) NOT NULL DEFAULT 'IDLE',
  -- RUNNING / IDLE / PAUSED / FAILED / DISABLED
  last_run_at TIMESTAMPTZ,
  last_status VARCHAR(16),
  next_run_at TIMESTAMPTZ,
  records_synced BIGINT DEFAULT 0,
  error_message TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_sync_task_status ON data_sync_task(status);
CREATE INDEX IF NOT EXISTS idx_sync_task_source ON data_sync_task(data_source_id);

-- ---------------------------------------------------------------------
-- 8.7 应急资源表
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS resource (
  id BIGSERIAL PRIMARY KEY,
  res_code VARCHAR(64) NOT NULL UNIQUE,
  res_name VARCHAR(128) NOT NULL,
  res_type VARCHAR(32) NOT NULL,
  -- rescue_team / supplies / shelter / equipment / vehicle
  region_code VARCHAR(64),
  longitude NUMERIC(10,6),
  latitude NUMERIC(10,6),
  geom geometry(Point, 4326),
  status VARCHAR(32) NOT NULL DEFAULT 'AVAILABLE',
  -- AVAILABLE / DEPLOYED / UNAVAILABLE / MAINTENANCE
  capacity VARCHAR(64),
  contact_person VARCHAR(64),
  contact_phone VARCHAR(32),
  description TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_resource_type ON resource(res_type);
CREATE INDEX IF NOT EXISTS idx_resource_status ON resource(status);
CREATE INDEX IF NOT EXISTS idx_resource_geom ON resource USING GIST (geom);

-- ---------------------------------------------------------------------
-- 8.8 应急预案表
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS plan (
  id BIGSERIAL PRIMARY KEY,
  plan_code VARCHAR(64) NOT NULL UNIQUE,
  plan_name VARCHAR(128) NOT NULL,
  disaster_type VARCHAR(32),
  level VARCHAR(16),
  content TEXT,
  owner_dept VARCHAR(128),
  contact_person VARCHAR(64),
  contact_phone VARCHAR(32),
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  -- DRAFT / ACTIVE / SUSPENDED / ARCHIVED
  version INT NOT NULL DEFAULT 1,
  created_by VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_plan_disaster ON plan(disaster_type);
CREATE INDEX IF NOT EXISTS idx_plan_status ON plan(status);

-- ---------------------------------------------------------------------
-- 8.9 通知模板表
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS notice_template (
  id BIGSERIAL PRIMARY KEY,
  template_code VARCHAR(64) NOT NULL UNIQUE,
  template_name VARCHAR(128) NOT NULL,
  disaster_type VARCHAR(32),
  level VARCHAR(16),
  channel VARCHAR(32),
  -- sms / app / email / led / webhook / sound_light
  title_template VARCHAR(256),
  content_template TEXT,
  variables JSONB,
  -- {"region":"string","level":"string","action":"string"}
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_by VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_template_disaster ON notice_template(disaster_type);
CREATE INDEX IF NOT EXISTS idx_template_channel ON notice_template(channel);
CREATE INDEX IF NOT EXISTS idx_template_enabled ON notice_template(enabled);

-- 8.9.1 多渠道正文：原表设计为一个模板绑定单个渠道（channel + content_template），
--       而通知模板管理页（NoticeTemplatePage.vue）以「渠道 → 正文」映射编辑多渠
--       道内容。这里追加 JSONB 列承载该映射，形如 {"sms":"...","email":"..."}。
--       旧行（如种子数据 NT-001..NT-005）channel_contents 为 NULL，由
--       NoticeTemplateController 回退为 {channel: content_template}，无需数据迁移。
ALTER TABLE notice_template ADD COLUMN IF NOT EXISTS channel_contents JSONB;

-- ---------------------------------------------------------------------
-- 8.10 会签/确认工作台任务（独立表，便于多用户并发认领）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS confirmation_task (
  id BIGSERIAL PRIMARY KEY,
  task_code VARCHAR(64) NOT NULL UNIQUE,
  hazard_code VARCHAR(64),
  source VARCHAR(32) NOT NULL DEFAULT 'AI_RESULT',
  -- AI_RESULT / MANUAL / RULE_ENGINE
  ai_result_id BIGINT,
  risk_level VARCHAR(16),
  confidence DOUBLE PRECISION,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING_CLAIM',
  -- PENDING_CLAIM / PENDING_FIRST / PENDING_SECOND / CONFIRMED / REJECTED / TRANSFERRED
  priority VARCHAR(8) DEFAULT 'MEDIUM',
  -- HIGH / MEDIUM / LOW
  claimed_by VARCHAR(64),
  claimed_at TIMESTAMPTZ,
  first_confirmed_by VARCHAR(64),
  first_confirmed_at TIMESTAMPTZ,
  second_confirmed_by VARCHAR(64),
  second_confirmed_at TIMESTAMPTZ,
  rejected_by VARCHAR(64),
  rejected_at TIMESTAMPTZ,
  reject_reason TEXT,
  transferred_to VARCHAR(64),
  transferred_at TIMESTAMPTZ,
  transfer_reason TEXT,
  note TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_confirm_task_status ON confirmation_task(status);
CREATE INDEX IF NOT EXISTS idx_confirm_task_claimed ON confirmation_task(claimed_by) WHERE claimed_by IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_confirm_task_priority ON confirmation_task(priority);

-- ---------------------------------------------------------------------
-- 8.11 审批记录表
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS approval (
  id BIGSERIAL PRIMARY KEY,
  approval_code VARCHAR(64) NOT NULL UNIQUE,
  biz_type VARCHAR(32) NOT NULL,
  -- WARNING_PUBLISH / WARNING_DISPATCH / ROUTE_PLAN / RESOURCE_ASSIGN
  biz_id VARCHAR(64) NOT NULL,
  title VARCHAR(256) NOT NULL,
  applicant_id BIGINT NOT NULL REFERENCES sys_user(id),
  approver_id BIGINT REFERENCES sys_user(id),
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  -- PENDING / APPROVED / REJECTED / CANCELLED
  opinion TEXT,
  approved_at TIMESTAMPTZ,
  result_summary TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_approval_biz ON approval(biz_type, biz_id);
CREATE INDEX IF NOT EXISTS idx_approval_status ON approval(status);
CREATE INDEX IF NOT EXISTS idx_approval_applicant ON approval(applicant_id);

-- ---------------------------------------------------------------------
-- 8.12 用户消息/待办（独立表，支持实时推送）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_message (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES sys_user(id) ON DELETE CASCADE,
  msg_type VARCHAR(32) NOT NULL,
  -- WARNING / CONFIRM / APPROVAL / SYSTEM / EMERGENCY
  title VARCHAR(256) NOT NULL,
  content TEXT,
  biz_type VARCHAR(32),
  biz_id VARCHAR(64),
  priority VARCHAR(8) DEFAULT 'NORMAL',
  -- HIGH / NORMAL / LOW
  read_status BOOLEAN NOT NULL DEFAULT FALSE,
  read_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_msg_user_status ON user_message(user_id, read_status);
CREATE INDEX IF NOT EXISTS idx_msg_created ON user_message(created_at DESC);

CREATE TABLE IF NOT EXISTS user_todo (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES sys_user(id) ON DELETE CASCADE,
  todo_type VARCHAR(32) NOT NULL,
  -- CONFIRMATION / WARNING_DRAFT / APPROVAL / EMERGENCY_UPDATE
  biz_id VARCHAR(64),
  title VARCHAR(256) NOT NULL,
  description TEXT,
  priority VARCHAR(8) DEFAULT 'MEDIUM',
  deadline TIMESTAMPTZ,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  -- PENDING / COMPLETED / CANCELLED / OVERDUE
  completed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_todo_user_status ON user_todo(user_id, status);
CREATE INDEX IF NOT EXISTS idx_todo_deadline ON user_todo(deadline) WHERE deadline IS NOT NULL;

-- =====================================================================
-- v2.0.1 设计审计补丁
-- 1. 补齐缺失索引（13 个热路径字段）
-- 2. JSON → JSONB（20 列，便于 GIN 索引和高效查询）
-- 3. 关键 logical FK 转为形式化 FK
-- 4. 状态字段加 CHECK 约束
-- =====================================================================

-- ---------------------------------------------------------------------
-- A. 缺失索引
-- ---------------------------------------------------------------------
-- monitoring_device
CREATE INDEX IF NOT EXISTS idx_device_hazard_code ON monitoring_device(hazard_code) WHERE hazard_code IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_device_type ON monitoring_device(device_type);
-- warning_event
CREATE INDEX IF NOT EXISTS idx_warning_rule_code ON warning_event(rule_code) WHERE rule_code IS NOT NULL;
-- confirmation_task
CREATE INDEX IF NOT EXISTS idx_confirm_task_hazard ON confirmation_task(hazard_code) WHERE hazard_code IS NOT NULL;
-- approval（反向：待我审批的）
CREATE INDEX IF NOT EXISTS idx_approval_approver_status ON approval(approver_id, status) WHERE approver_id IS NOT NULL;
-- resource
CREATE INDEX IF NOT EXISTS idx_resource_region ON resource(region_code) WHERE region_code IS NOT NULL;
-- sys_role_permission 反向查找（哪些角色有 X 权限）
CREATE INDEX IF NOT EXISTS idx_rp_perm_code ON sys_role_permission(perm_code);
-- event_sign 按会签人查询
CREATE INDEX IF NOT EXISTS idx_event_sign_signer ON event_sign(signer_id) WHERE signer_id IS NOT NULL;
-- risk_score_log 时序+灾害查询
CREATE INDEX IF NOT EXISTS idx_risk_score_hazard ON risk_score_log(hazard_code, calculated_at DESC);
CREATE INDEX IF NOT EXISTS idx_risk_score_calculated ON risk_score_log(calculated_at DESC);
-- operation_log 审计
CREATE INDEX IF NOT EXISTS idx_op_log_operator ON operation_log(operator, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_op_log_created ON operation_log(created_at DESC);

-- ---------------------------------------------------------------------
-- B. JSON → JSONB（幂等：先 ALTER，再补 GIN 索引）
-- 注意：PostgreSQL ALTER TABLE ... ALTER COLUMN ... TYPE JSONB USING ... 自动转换。
-- JSONB 支持 GIN 索引、jsonb_path_ops、操作符（@>, ? 等），JSON 不支持。
-- ---------------------------------------------------------------------
ALTER TABLE ai_analysis_result ALTER COLUMN result_json TYPE JSONB USING result_json::JSONB;
ALTER TABLE ai_device_health ALTER COLUMN dimensions_json TYPE JSONB USING dimensions_json::JSONB;
ALTER TABLE ai_device_health ALTER COLUMN reasoning_json TYPE JSONB USING reasoning_json::JSONB;
ALTER TABLE ai_device_health ALTER COLUMN suggested_actions_json TYPE JSONB USING suggested_actions_json::JSONB;
ALTER TABLE ai_comprehensive_score ALTER COLUMN dimensions_json TYPE JSONB USING dimensions_json::JSONB;
ALTER TABLE ai_comprehensive_score ALTER COLUMN reasoning_json TYPE JSONB USING reasoning_json::JSONB;
ALTER TABLE ai_comprehensive_score ALTER COLUMN suggested_actions_json TYPE JSONB USING suggested_actions_json::JSONB;
ALTER TABLE notification_record ALTER COLUMN channels TYPE JSONB USING channels::JSONB;
ALTER TABLE notification_channel_config ALTER COLUMN channels TYPE JSONB USING channels::JSONB;
ALTER TABLE notification_channel_config ALTER COLUMN template_mapping TYPE JSONB USING template_mapping::JSONB;
ALTER TABLE notification_channel_config ALTER COLUMN priority_channels TYPE JSONB USING priority_channels::JSONB;
ALTER TABLE rule ALTER COLUMN conditions TYPE JSONB USING conditions::JSONB;
ALTER TABLE rule ALTER COLUMN actions TYPE JSONB USING actions::JSONB;
ALTER TABLE rule_execution_log ALTER COLUMN input_data TYPE JSONB USING input_data::JSONB;
ALTER TABLE rule_execution_log ALTER COLUMN result TYPE JSONB USING result::JSONB;
ALTER TABLE retrain_record ALTER COLUMN metrics TYPE JSONB USING metrics::JSONB;
ALTER TABLE drift_baseline ALTER COLUMN baseline_data TYPE JSONB USING baseline_data::JSONB;
ALTER TABLE multimodal_fusion_record ALTER COLUMN contributions TYPE JSONB USING contributions::JSONB;
ALTER TABLE multimodal_fusion_record ALTER COLUMN modal_details TYPE JSONB USING modal_details::JSONB;
ALTER TABLE modal_weight_config ALTER COLUMN weights TYPE JSONB USING weights::JSONB;
ALTER TABLE risk_score_log ALTER COLUMN factors TYPE JSONB USING factors::JSONB;
-- file_attachment.business_type 是枚举字符串（hazard / task / warning / DEVICE_IMAGE ...），
-- 不是 JSON 结构，此前被误并入本段 JSON→JSONB 迁移，导致 FileAttachment 实体
-- （String 绑定）插入时报 "column is of type jsonb but expression is of type character varying"，
-- 附件上传整体不可用。这里幂等地修回 VARCHAR：
--   * ::text 在 jsonb 与 varchar 两种现状下都是合法 cast，因此本语句可反复执行；
--   * jsonb 标量转文本会残留双引号（"hazard"），紧随的 UPDATE 统一剥除。
ALTER TABLE file_attachment ALTER COLUMN business_type TYPE VARCHAR(64) USING business_type::text;
UPDATE file_attachment SET business_type = trim(both '"' from business_type)
 WHERE business_type LIKE '"%"';
-- 同上：file_url 存的是 MinIO 预签名地址（约 500 字符），255 会在插入时直接报
-- "value too long for type character varying(255)"，附件上传因此同样不可用。
-- 放宽到 1024 是幂等的（已是 1024 时重复执行无副作用）。
ALTER TABLE file_attachment ALTER COLUMN file_url TYPE VARCHAR(1024);
ALTER TABLE drift_monitor_record ALTER COLUMN feature TYPE JSONB USING to_jsonb(feature);

-- GIN 索引：让 JSONB 列的高频查询 (@>, ?, ?&, ?|) 走索引
CREATE INDEX IF NOT EXISTS idx_rule_conditions_gin ON rule USING GIN (conditions);
CREATE INDEX IF NOT EXISTS idx_rule_actions_gin ON rule USING GIN (actions);
CREATE INDEX IF NOT EXISTS idx_ai_result_biz_gin ON ai_analysis_result USING GIN (result_json);
CREATE INDEX IF NOT EXISTS idx_multimodal_contrib_gin ON multimodal_fusion_record USING GIN (contributions);
CREATE INDEX IF NOT EXISTS idx_risk_score_factors_gin ON risk_score_log USING GIN (factors);

-- ---------------------------------------------------------------------
-- C. 状态字段 CHECK 约束（确保只接受合法枚举值）
-- 注意：ADD CONSTRAINT 不支持 IF NOT EXISTS，改用 DROP+ADD 实现幂等，
--       避免使用 ScriptUtils 无法解析的 PL/pgSQL DO 块。
-- ---------------------------------------------------------------------
ALTER TABLE ai_model DROP CONSTRAINT IF EXISTS ck_ai_model_status;
ALTER TABLE ai_model ADD CONSTRAINT ck_ai_model_status
  CHECK (status IN ('ONLINE','OFFLINE','TRAINING','DEPLOYING','ROLLBACK'));

ALTER TABLE data_source DROP CONSTRAINT IF EXISTS ck_ds_status;
ALTER TABLE data_source ADD CONSTRAINT ck_ds_status
  CHECK (status IN ('CONNECTED','DISCONNECTED','ERROR','SYNCING'));

ALTER TABLE data_sync_task DROP CONSTRAINT IF EXISTS ck_sync_task_status;
ALTER TABLE data_sync_task ADD CONSTRAINT ck_sync_task_status
  CHECK (status IN ('RUNNING','IDLE','PAUSED','FAILED','DISABLED'));

ALTER TABLE resource DROP CONSTRAINT IF EXISTS ck_resource_status;
ALTER TABLE resource ADD CONSTRAINT ck_resource_status
  CHECK (status IN ('AVAILABLE','DEPLOYED','UNAVAILABLE','MAINTENANCE','OPEN','FULL','CLOSED'));

ALTER TABLE plan DROP CONSTRAINT IF EXISTS ck_plan_status;
ALTER TABLE plan ADD CONSTRAINT ck_plan_status
  CHECK (status IN ('DRAFT','ACTIVE','SUSPENDED','ARCHIVED'));

ALTER TABLE confirmation_task DROP CONSTRAINT IF EXISTS ck_confirm_status;
ALTER TABLE confirmation_task ADD CONSTRAINT ck_confirm_status
  CHECK (status IN ('PENDING_CLAIM','PENDING_FIRST','PENDING_SECOND','CONFIRMED','REJECTED','TRANSFERRED'));

ALTER TABLE confirmation_task DROP CONSTRAINT IF EXISTS ck_confirm_priority;
ALTER TABLE confirmation_task ADD CONSTRAINT ck_confirm_priority
  CHECK (priority IN ('HIGH','MEDIUM','LOW'));

ALTER TABLE approval DROP CONSTRAINT IF EXISTS ck_approval_status;
ALTER TABLE approval ADD CONSTRAINT ck_approval_status
  CHECK (status IN ('PENDING','APPROVED','REJECTED','CANCELLED'));

ALTER TABLE user_todo DROP CONSTRAINT IF EXISTS ck_todo_status;
ALTER TABLE user_todo ADD CONSTRAINT ck_todo_status
  CHECK (status IN ('PENDING','COMPLETED','CANCELLED','OVERDUE'));

ALTER TABLE user_todo DROP CONSTRAINT IF EXISTS ck_todo_priority;
ALTER TABLE user_todo ADD CONSTRAINT ck_todo_priority
  CHECK (priority IN ('HIGH','MEDIUM','LOW'));
-- ---------------------------------------------------------------------
-- D. 关键 logical FK 转形式化 FK（应用层一致性的最后兜底）
-- 注意：sys_user.role_code 在既有卷可能引用旧 3 角色，新 7 角色已包含旧的子集，
--       所以加 FK 不会破坏既有数据。ScriptUtils 无法解析 PL/pgSQL DO 块，
--       故改用纯 DDL：DROP IF EXISTS + ADD 即可幂等。
-- ---------------------------------------------------------------------
-- sys_user.role_code -> sys_role.role_code
UPDATE sys_user SET role_code = 'admin'
  WHERE role_code NOT IN (SELECT role_code FROM sys_role);
ALTER TABLE sys_user DROP CONSTRAINT IF EXISTS fk_sys_user_role;
ALTER TABLE sys_user ADD CONSTRAINT fk_sys_user_role
  FOREIGN KEY (role_code) REFERENCES sys_role(role_code) ON UPDATE CASCADE ON DELETE RESTRICT;

-- monitoring_device.region_code -> region.region_code
UPDATE monitoring_device SET region_code = NULL
  WHERE region_code IS NOT NULL AND region_code NOT IN (SELECT region_code FROM region);
ALTER TABLE monitoring_device DROP CONSTRAINT IF EXISTS fk_device_region;
ALTER TABLE monitoring_device ADD CONSTRAINT fk_device_region
  FOREIGN KEY (region_code) REFERENCES region(region_code) ON UPDATE CASCADE ON DELETE SET NULL;

-- monitoring_device.hazard_code 已有 REFERENCES，不重复
-- (v2.1.0: hazard_point 现在是从 unified_event 投影的视图，不再需要
--  fk_hazard_region 单独的外键约束；unified_event.region_code 上的约束由
--  底部 v2.1.0 迁移块的 partial index 维护)

-- inspection_task.region_code -> region.region_code
UPDATE inspection_task SET region_code = NULL
  WHERE region_code IS NOT NULL AND region_code NOT IN (SELECT region_code FROM region);
ALTER TABLE inspection_task DROP CONSTRAINT IF EXISTS fk_task_region;
ALTER TABLE inspection_task ADD CONSTRAINT fk_task_region
  FOREIGN KEY (region_code) REFERENCES region(region_code) ON UPDATE CASCADE ON DELETE SET NULL;

-- resource.region_code -> region.region_code
UPDATE resource SET region_code = NULL
  WHERE region_code IS NOT NULL AND region_code NOT IN (SELECT region_code FROM region);
ALTER TABLE resource DROP CONSTRAINT IF EXISTS fk_resource_region;
ALTER TABLE resource ADD CONSTRAINT fk_resource_region
  FOREIGN KEY (region_code) REFERENCES region(region_code) ON UPDATE CASCADE ON DELETE SET NULL;

-- ai_analysis_result.model_name -> ai_model.model_code
UPDATE ai_analysis_result SET model_name = NULL
  WHERE model_name IS NOT NULL AND model_name NOT IN (SELECT model_code FROM ai_model);
ALTER TABLE ai_analysis_result DROP CONSTRAINT IF EXISTS fk_ai_result_model;
ALTER TABLE ai_analysis_result ADD CONSTRAINT fk_ai_result_model
  FOREIGN KEY (model_name) REFERENCES ai_model(model_code) ON UPDATE CASCADE ON DELETE SET NULL;

-- rule.created_by -> sys_user.username (username is unique but not PK)
UPDATE rule SET created_by = NULL
  WHERE created_by IS NOT NULL AND created_by NOT IN (SELECT username FROM sys_user);
ALTER TABLE rule DROP CONSTRAINT IF EXISTS fk_rule_created_by;
ALTER TABLE rule ADD CONSTRAINT fk_rule_created_by
  FOREIGN KEY (created_by) REFERENCES sys_user(username) ON UPDATE CASCADE ON DELETE SET NULL;

-- plan.created_by -> sys_user.username
UPDATE plan SET created_by = NULL
  WHERE created_by IS NOT NULL AND created_by NOT IN (SELECT username FROM sys_user);
ALTER TABLE plan DROP CONSTRAINT IF EXISTS fk_plan_created_by;
ALTER TABLE plan ADD CONSTRAINT fk_plan_created_by
  FOREIGN KEY (created_by) REFERENCES sys_user(username) ON UPDATE CASCADE ON DELETE SET NULL;

-- notice_template.created_by -> sys_user.username
UPDATE notice_template SET created_by = NULL
  WHERE created_by IS NOT NULL AND created_by NOT IN (SELECT username FROM sys_user);
ALTER TABLE notice_template DROP CONSTRAINT IF EXISTS fk_notice_created_by;
ALTER TABLE notice_template ADD CONSTRAINT fk_notice_created_by
  FOREIGN KEY (created_by) REFERENCES sys_user(username) ON UPDATE CASCADE ON DELETE SET NULL;

-- =====================================================================
-- v2.1.0 数据层统一 (Task #1 P1)：events 升格为 source of truth，
--   hazard_point 改为从 unified_event 投影的可写视图。
--
-- 升级步骤（务必按顺序）：
--   1) 应用启动前：先执行 scripts/backfill_unified_event_hazards.py，
--      把既有 hazard_point 数据搬运到 unified_event（kind='HAZARD'）。
--   2) 应用启动：本 schema.sql 的迁移块自动完成：
--        - 给 unified_event 补齐 `kind` 判别列 + 全部 hazard 领域列
--        - 用 partial 索引替代原 hazard_point 上的索引
--        - DROP hazard_point（物理表）→ CREATE VIEW hazard_point（自动可写视图）
--        - 把 6 张子表的外键从 hazard_point 改挂到 unified_event(hazard_code)
--
-- 升级后 hazard_point 是视图，原 hazard_point INSERT/UPDATE/DELETE 仍可工作
-- （PostgreSQL 自动可写视图会把 DML 重写到 unified_event，kind 默认 'HAZARD'）。
-- Phase 2 再清理双写路径与 JPA 实体。
-- =====================================================================

-- ---- 1. `kind` 判别列 + state 默认值 + CHECK 约束 ----
ALTER TABLE unified_event ADD COLUMN IF NOT EXISTS kind VARCHAR(16);
-- 已有行的回填：warning_code / emergency_code 任一非空 → 对应类型；其余全部归 HAZARD
UPDATE unified_event SET kind = 'WARNING'
  WHERE warning_code IS NOT NULL AND kind IS NULL;
UPDATE unified_event SET kind = 'EMERGENCY'
  WHERE emergency_code IS NOT NULL AND warning_code IS NULL AND kind IS NULL;
UPDATE unified_event SET kind = 'HAZARD'
  WHERE kind IS NULL;
ALTER TABLE unified_event ALTER COLUMN kind SET NOT NULL;
ALTER TABLE unified_event ALTER COLUMN kind SET DEFAULT 'HAZARD';
-- state 列允许视图写入时不显式提供（避免 data.sql / 视图重写时 NOT NULL 失败）
ALTER TABLE unified_event ALTER COLUMN state SET DEFAULT 'DETECTED';
-- R4 P0-5 修复：event_code 是 NOT NULL UNIQUE 且原先**没有默认值**，但
-- hazard_point 视图并不投影这一列 —— 于是"往视图插隐患点"必然以
-- `null value in column "event_code" violates not-null constraint` 失败，
-- 人工新增隐患点与 AI 落库两条写路径全部 500（已在本机库实测）。
-- 这里给出与 EventStateService#generateEventCode() **同格式**的默认值
-- （UFE-yyyyMMdd-XXXX，后 4 位十六进制取自随机 UUID），
-- 让视图重写后的 INSERT 能自动获得事件号，无需把该列暴露给历史消费者
-- （视图注释明确要求不暴露 event_code）。
-- 幂等：ALTER COLUMN ... SET DEFAULT 可重复执行。
ALTER TABLE unified_event ALTER COLUMN event_code
  SET DEFAULT ('UFE-' || to_char(NOW(), 'YYYYMMDD') || '-' ||
               upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 4)));
-- unified_event 的 CHECK 约束：ScriptUtils 无法解析 PL/pgSQL DO 块，改用 DROP+ADD 幂等
ALTER TABLE unified_event DROP CONSTRAINT IF EXISTS ck_unified_kind;
ALTER TABLE unified_event ADD CONSTRAINT ck_unified_kind
  CHECK (kind IN ('HAZARD','WARNING','EMERGENCY'));

ALTER TABLE unified_event DROP CONSTRAINT IF EXISTS ck_unified_state;
ALTER TABLE unified_event ADD CONSTRAINT ck_unified_state
  CHECK (state IN ('DETECTED','CONFIRMING','CONFIRMED','WARNING_ISSUED','EMERGENCY_ACTIVE','CLOSED','REJECTED'));

-- ---- 2. hazard 领域列（NULL 允许；非 HAZARD 行不需这些列）----
ALTER TABLE unified_event ADD COLUMN IF NOT EXISTS name VARCHAR(128);
ALTER TABLE unified_event ADD COLUMN IF NOT EXISTS hazard_type VARCHAR(64);
ALTER TABLE unified_event ADD COLUMN IF NOT EXISTS risk_level VARCHAR(32);
ALTER TABLE unified_event ADD COLUMN IF NOT EXISTS region_code VARCHAR(64);
ALTER TABLE unified_event ADD COLUMN IF NOT EXISTS owner VARCHAR(128);
ALTER TABLE unified_event ADD COLUMN IF NOT EXISTS hazard_status VARCHAR(32);
ALTER TABLE unified_event ADD COLUMN IF NOT EXISTS longitude NUMERIC(10,6);
ALTER TABLE unified_event ADD COLUMN IF NOT EXISTS latitude NUMERIC(10,6);
ALTER TABLE unified_event ADD COLUMN IF NOT EXISTS geom geometry(Point, 4326);
ALTER TABLE unified_event ADD COLUMN IF NOT EXISTS threat_object TEXT;
ALTER TABLE unified_event ADD COLUMN IF NOT EXISTS last_inspection_at TIMESTAMPTZ;
ALTER TABLE unified_event ADD COLUMN IF NOT EXISTS discovered_at TIMESTAMPTZ;
ALTER TABLE unified_event ADD COLUMN IF NOT EXISTS detection_confidence DOUBLE PRECISION;
ALTER TABLE unified_event ADD COLUMN IF NOT EXISTS last_inspection_device_type VARCHAR(64);
ALTER TABLE unified_event ADD COLUMN IF NOT EXISTS historical_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE unified_event ADD COLUMN IF NOT EXISTS historical_max_score DOUBLE PRECISION NOT NULL DEFAULT 0;
ALTER TABLE unified_event ADD COLUMN IF NOT EXISTS exposure_population BIGINT NOT NULL DEFAULT 0;
ALTER TABLE unified_event ADD COLUMN IF NOT EXISTS exposure_buildings INTEGER NOT NULL DEFAULT 0;
ALTER TABLE unified_event ADD COLUMN IF NOT EXISTS land_use_type VARCHAR(32) NOT NULL DEFAULT 'unknown';
ALTER TABLE unified_event ADD COLUMN IF NOT EXISTS hazard_source VARCHAR(32);

-- ---- 3. unified_event 上的 hazard partial 索引（替代原 hazard_point 上的索引）----
CREATE INDEX IF NOT EXISTS idx_unified_hazard_geom
    ON unified_event USING GIST (geom) WHERE kind = 'HAZARD';
CREATE INDEX IF NOT EXISTS idx_unified_hazard_region_level
    ON unified_event(region_code, risk_level) WHERE kind = 'HAZARD';
CREATE INDEX IF NOT EXISTS idx_unified_hazard_type
    ON unified_event(hazard_type) WHERE kind = 'HAZARD';
CREATE INDEX IF NOT EXISTS idx_unified_hazard_source
    ON unified_event(hazard_source, discovered_at DESC) WHERE kind = 'HAZARD';
CREATE INDEX IF NOT EXISTS idx_unified_hazard_discovered
    ON unified_event(discovered_at DESC) WHERE kind = 'HAZARD' AND hazard_source = 'AI_MONITOR';
-- hazard_key 暴露列：仅 HAZARD 行带值，WARNING/EMERGENCY 行带 NULL
-- (PostgreSQL 不允许外键引用 partial unique index；这里改成普通 UNIQUE 索引，
--  多 NULL 在 UNIQUE 下互不相等，所以 WARNING/EMERGENCY 行的 NULL 不影响唯一性。)
ALTER TABLE unified_event ADD COLUMN IF NOT EXISTS hazard_key VARCHAR(64)
  GENERATED ALWAYS AS (CASE WHEN kind = 'HAZARD' THEN hazard_code ELSE NULL END) STORED;
CREATE UNIQUE INDEX IF NOT EXISTS uq_unified_hazard_key
    ON unified_event(hazard_key);

-- ---- 4. 物理 hazard_point → 自动可写视图 ----
DROP TABLE IF EXISTS hazard_point CASCADE;
CREATE OR REPLACE VIEW hazard_point AS
SELECT
  id,
  hazard_code,
  name,
  hazard_type,
  risk_level,
  region_code,
  owner,
  hazard_status AS status,
  longitude,
  latitude,
  geom,
  threat_object,
  last_inspection_at,
  hazard_source   AS source,
  discovered_at,
  detection_confidence,
  last_inspection_device_type,
  historical_count,
  historical_max_score,
  exposure_population,
  exposure_buildings,
  land_use_type
FROM unified_event
WHERE kind = 'HAZARD';

COMMENT ON VIEW hazard_point IS
  'v2.1.0 Task#1 P1: 自动可写视图，投影自 unified_event WHERE kind=HAZARD。所有 INSERT/UPDATE/DELETE 会被 PostgreSQL 重写到 unified_event 表。视图外的 unified_event 列（state、event_code、source 等）可通过 unified_event 实体直接读写，不暴露给历史 hazard_point 消费者。';

-- ---- 5. 重新指向 unified_event(hazard_code) 的外键 ----
-- 原 6 张子表（inspection_task / inspection_record / monitoring_device /
-- warning_event / risk_score_log / ai_comprehensive_score）的 hazard_code
-- 外键在 DROP TABLE CASCADE 时已被卸下；这里统一挂回 unified_event。
-- ScriptUtils 无法解析 PL/pgSQL DO $$ ... $$ 块，改用 DROP+ADD 实现幂等。
ALTER TABLE inspection_task DROP CONSTRAINT IF EXISTS fk_inspection_task_hazard;
ALTER TABLE inspection_task ADD CONSTRAINT fk_inspection_task_hazard
  FOREIGN KEY (hazard_code) REFERENCES unified_event(hazard_key) ON DELETE SET NULL
  DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE inspection_record DROP CONSTRAINT IF EXISTS fk_inspection_record_hazard;
ALTER TABLE inspection_record ADD CONSTRAINT fk_inspection_record_hazard
  FOREIGN KEY (hazard_code) REFERENCES unified_event(hazard_key) ON DELETE SET NULL
  DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE monitoring_device DROP CONSTRAINT IF EXISTS fk_monitoring_device_hazard;
ALTER TABLE monitoring_device ADD CONSTRAINT fk_monitoring_device_hazard
  FOREIGN KEY (hazard_code) REFERENCES unified_event(hazard_key) ON DELETE SET NULL
  DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE warning_event DROP CONSTRAINT IF EXISTS fk_warning_event_hazard;
ALTER TABLE warning_event ADD CONSTRAINT fk_warning_event_hazard
  FOREIGN KEY (hazard_code) REFERENCES unified_event(hazard_key) ON DELETE SET NULL
  DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE risk_score_log DROP CONSTRAINT IF EXISTS fk_risk_score_log_hazard;
ALTER TABLE risk_score_log ADD CONSTRAINT fk_risk_score_log_hazard
  FOREIGN KEY (hazard_code) REFERENCES unified_event(hazard_key) ON DELETE CASCADE
  DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE ai_comprehensive_score DROP CONSTRAINT IF EXISTS fk_ai_comprehensive_score_hazard;
ALTER TABLE ai_comprehensive_score ADD CONSTRAINT fk_ai_comprehensive_score_hazard
  FOREIGN KEY (hazard_code) REFERENCES unified_event(hazard_key) ON DELETE CASCADE
  DEFERRABLE INITIALLY DEFERRED;


-- =====================================================================
-- M6 落库：原内存 mock（ConcurrentHashMap）业务数据表
-- payload 统一 JSONB 承载原 Map<String,Object> 结构，响应字段不变
-- =====================================================================
-- =====================================================================
-- M6 技术债改造：BackupController 内存 mock 落库（backup 模块）
-- 结构统一为 {id, payload JSONB, created_at}，业务字段全部在 payload 内。
-- id 由 Controller 侧计数器手动分配，非自增主键。
-- =====================================================================

CREATE TABLE IF NOT EXISTS backup_record (
  id BIGINT PRIMARY KEY,
  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS restore_record (
  id BIGINT PRIMARY KEY,
  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS backup_strategy (
  id BIGINT PRIMARY KEY,
  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- =====================================================================
-- M6 技术债：Controller 内存 mock（ConcurrentHashMap）→ JPA + JSONB 落库
-- 涉及：OtaController（firmware / ota_task）、SyncController（sync_task_record）
-- 实体结构统一为 {id VARCHAR 主键, payload JSONB, created_at TIMESTAMPTZ}
-- 说明：backend/ 根目录迁移脚本，不并入 schema.sql（spring.sql.init 不自动执行）
-- =====================================================================

CREATE TABLE IF NOT EXISTS firmware (
  id VARCHAR(64) PRIMARY KEY,
  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS ota_task (
  id VARCHAR(64) PRIMARY KEY,
  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS sync_task_record (
  id VARCHAR(64) PRIMARY KEY,
  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- =====================================================================
-- M6 技术债改造 DDL：报告模板 / 报告历史 / 应急预案 JSONB 落库
-- 幂等：可安全重跑。id 由应用层计数器分配，故不使用 BIGSERIAL。
-- =====================================================================

CREATE TABLE IF NOT EXISTS report_template (
  id BIGINT PRIMARY KEY,
  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS report_history (
  id BIGINT PRIMARY KEY,
  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS emergency_plan (
  id BIGINT PRIMARY KEY,
  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 报告订阅（前端 ReportCenterPage 调 updateReportSubscription 创建/更新订阅）
CREATE TABLE IF NOT EXISTS report_subscription (
  id BIGINT PRIMARY KEY,
  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- =====================================================================
-- M6 技术债改造：Controller 内存 Map 落库（JSONB）
-- 涉及：AccountSecurityController、WarningDeliveryController
-- 说明：{id, payload JSONB, created_at} 通用结构；payload 存原 Map 的
--       value（业务字段整体），时间值均为 ISO-8601 字符串。
-- =====================================================================

-- 会话（原 sessions: Map<token字符串, Map>），主键为会话 token 字符串
CREATE TABLE IF NOT EXISTS login_session (
  id VARCHAR(128) PRIMARY KEY,
  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 登录历史（原 loginHistory: Map<自增id, Map>）
CREATE TABLE IF NOT EXISTS login_history_entry (
  id BIGINT PRIMARY KEY,
  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 账户审计日志（原 auditLog: Map<自增id, Map>）
CREATE TABLE IF NOT EXISTS account_audit_entry (
  id BIGINT PRIMARY KEY,
  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 预警送达记录（原 deliveryRecords: Map<预警id, List<Map>> 拆为行表，
-- record_id 即预警 id，另有自增记录 id 主键）
CREATE TABLE IF NOT EXISTS warning_delivery_record (
  id BIGINT PRIMARY KEY,
  record_id BIGINT NOT NULL,
  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_wdr_record_id ON warning_delivery_record(record_id);

-- =====================================================================
-- M6 落库：原内存 mock（ConcurrentHashMap）业务数据表
-- payload 统一 JSONB 承载原 Map<String,Object> 结构，响应字段不变
-- =====================================================================
-- =====================================================================
-- M6 技术债改造：BackupController 内存 mock 落库（backup 模块）
-- 结构统一为 {id, payload JSONB, created_at}，业务字段全部在 payload 内。
-- id 由 Controller 侧计数器手动分配，非自增主键。
-- =====================================================================

CREATE TABLE IF NOT EXISTS backup_record (
  id BIGINT PRIMARY KEY,
  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS restore_record (
  id BIGINT PRIMARY KEY,
  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS backup_strategy (
  id BIGINT PRIMARY KEY,
  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- =====================================================================
-- M6 技术债：Controller 内存 mock（ConcurrentHashMap）→ JPA + JSONB 落库
-- 涉及：OtaController（firmware / ota_task）、SyncController（sync_task_record）
-- 实体结构统一为 {id VARCHAR 主键, payload JSONB, created_at TIMESTAMPTZ}
-- 说明：backend/ 根目录迁移脚本，不并入 schema.sql（spring.sql.init 不自动执行）
-- =====================================================================

CREATE TABLE IF NOT EXISTS firmware (
  id VARCHAR(64) PRIMARY KEY,
  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS ota_task (
  id VARCHAR(64) PRIMARY KEY,
  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS sync_task_record (
  id VARCHAR(64) PRIMARY KEY,
  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- =====================================================================
-- M6 技术债改造 DDL：报告模板 / 报告历史 / 应急预案 JSONB 落库
-- 幂等：可安全重跑。id 由应用层计数器分配，故不使用 BIGSERIAL。
-- =====================================================================

CREATE TABLE IF NOT EXISTS report_template (
  id BIGINT PRIMARY KEY,
  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS report_history (
  id BIGINT PRIMARY KEY,
  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS emergency_plan (
  id BIGINT PRIMARY KEY,
  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 报告订阅（前端 ReportCenterPage 调 updateReportSubscription 创建/更新订阅）
CREATE TABLE IF NOT EXISTS report_subscription (
  id BIGINT PRIMARY KEY,
  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- =====================================================================
-- M6 技术债改造：Controller 内存 Map 落库（JSONB）
-- 涉及：AccountSecurityController、WarningDeliveryController
-- 说明：{id, payload JSONB, created_at} 通用结构；payload 存原 Map 的
--       value（业务字段整体），时间值均为 ISO-8601 字符串。
-- =====================================================================

-- 会话（原 sessions: Map<token字符串, Map>），主键为会话 token 字符串
CREATE TABLE IF NOT EXISTS login_session (
  id VARCHAR(128) PRIMARY KEY,
  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 登录历史（原 loginHistory: Map<自增id, Map>）
CREATE TABLE IF NOT EXISTS login_history_entry (
  id BIGINT PRIMARY KEY,
  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 账户审计日志（原 auditLog: Map<自增id, Map>）
CREATE TABLE IF NOT EXISTS account_audit_entry (
  id BIGINT PRIMARY KEY,
  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 预警送达记录（原 deliveryRecords: Map<预警id, List<Map>> 拆为行表，
-- record_id 即预警 id，另有自增记录 id 主键）
CREATE TABLE IF NOT EXISTS warning_delivery_record (
  id BIGINT PRIMARY KEY,
  record_id BIGINT NOT NULL,
  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_wdr_record_id ON warning_delivery_record(record_id);
-- =====================================================================
-- 注册申请单：用户提交申请 -> 待审核 -> 管理员通过后创建 sys_user
-- 约定：
--   - password_hash / id_card_enc 为敏感字段，禁止出现在任何响应 DTO 中
--   - id_card_enc 为 AES-256-GCM 密文（Base64(iv||ciphertext||tag)）
--   - status 取值：待审核 / 已通过 / 已拒绝
-- =====================================================================
CREATE TABLE IF NOT EXISTS sys_user_application (
  id BIGSERIAL PRIMARY KEY,
  username VARCHAR(64) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  real_name VARCHAR(64) NOT NULL,
  phone VARCHAR(32) NOT NULL,
  email VARCHAR(128) NOT NULL,
  -- AES-256-GCM 密文（Base64(iv || ciphertext || tag)）
  id_card_enc VARCHAR(512) NOT NULL,
  -- 脱敏串，如 110***********1234
  id_card_mask VARCHAR(32) NOT NULL,
  department VARCHAR(128) NOT NULL,
  position VARCHAR(64),
  role_code VARCHAR(64) NOT NULL,
  region_code VARCHAR(64) NOT NULL,
  apply_reason TEXT NOT NULL,
  invite_code VARCHAR(64),
  status VARCHAR(32) NOT NULL DEFAULT '待审核',
  reject_reason VARCHAR(255),
  register_ip VARCHAR(64) NOT NULL,
  user_agent VARCHAR(512),
  phone_verified BOOLEAN NOT NULL DEFAULT FALSE,
  email_verified BOOLEAN NOT NULL DEFAULT FALSE,
  reviewer VARCHAR(64),
  reviewed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_application_status ON sys_user_application(status);
CREATE INDEX IF NOT EXISTS idx_application_username ON sys_user_application(username);
CREATE INDEX IF NOT EXISTS idx_application_phone ON sys_user_application(phone);
CREATE INDEX IF NOT EXISTS idx_application_ip ON sys_user_application(register_ip);

-- 同一 username 只允许存在一条「待审核」申请（部分唯一索引）
CREATE UNIQUE INDEX IF NOT EXISTS idx_application_pending_username
  ON sys_user_application(username) WHERE status = '待审核';
