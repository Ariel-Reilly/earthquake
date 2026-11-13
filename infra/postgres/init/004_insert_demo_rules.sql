-- =====================================================================
-- 004_insert_demo_rules.sql  ⚠ 历史脚本，已【不再】由 docker-entrypoint-initdb.d 自动执行
--
-- 原因（2026-11-13 空库部署演练实证）：本脚本 INSERT 的 unified_event / warning_rule
-- 均由后端启动时 schema.sql 创建，initdb 阶段表不存在，必然失败并让容器 Exited(3)。
-- 且与 data.sql 冲突：规则唯一真相源已是 data.sql 的 R001~R006（含 disaster_type、
-- 复合条件），灾害点已有 HZ-001~HZ-005；本脚本的 R_DEMO_* 是旧口径的冗余演示数据。
-- 本文件保留作历史参考；请勿再把整个 init/ 目录挂载到 initdb.d。
-- =====================================================================
-- 【P1 修复】插入演示规则（3 条关键规则）
-- 用于验证规则引擎正常工作

-- 确保灾害点存在（如果不存在）
INSERT INTO unified_event (
  kind, event_code, state, source,
  hazard_code, name, hazard_type, risk_level, region_code, owner, hazard_status,
  longitude, latitude, geom, threat_object, hazard_source, discovered_at, detection_confidence
) VALUES
('HAZARD','EVT-HZ-DEMO-001','DETECTED','AI_AUTO','HZ-DEMO-001','演示灾害点-滑坡','landslide','高风险','500109','演示','演示数据',106.4500,29.5600,ST_SetSRID(ST_MakePoint(106.4500,29.5600),4326),'演示区域','AI_MONITOR',NOW(),0.86)
ON CONFLICT DO NOTHING;

-- 插入演示规则（3 条）
-- 这些规则用于快速验证规则引擎的正常工作
INSERT INTO warning_rule (rule_code, rule_name, metric_code, expression, warning_level, enabled, remark) VALUES
-- 规则 1：降雨 > 50mm 触发黄色预警
('R_DEMO_001', '演示规则：降雨阈值黄色', 'rainfall_24h', 'rainfall_24h > 50', '黄色预警', TRUE, '演示用：降雨量超过50mm触发黄色预警'),

-- 规则 2：位移速率 > 5mm/day 触发橙色预警
('R_DEMO_002', '演示规则：位移速率橙色', 'displacement_rate', 'displacement_rate > 5', '橙色预警', TRUE, '演示用：位移速率超过5mm/day触发橙色预警'),

-- 规则 3：裂缝宽度 > 10mm 触发红色预警
('R_DEMO_003', '演示规则：裂缝宽度红色', 'crack_width', 'crack_width > 10', '红色预警', TRUE, '演示用：裂缝宽度超过10mm触发红色预警')
ON CONFLICT (rule_code) DO NOTHING;
