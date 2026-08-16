INSERT INTO sys_role (role_code, role_name, description, menu_permissions, button_permissions)
VALUES
('admin', '管理员', '拥有全部模块访问权限', '*', '*'),
('attendant', '值班员', '负责态势、巡检和预警处置', 'overview,hazards,tasks,warnings,emergency,analytics', 'overview:export,hazards:create,hazards:edit,tasks:create,tasks:edit,warnings:confirm,warnings:dispatch,warnings:close,emergency:dispatch,emergency:close,analytics:export'),
('maintenance', '运维员', '负责设备和基础设施运维', 'overview,hazards,devices,analytics', 'overview:export,hazards:export,devices:create,devices:edit,devices:delete,devices:export,analytics:export')
ON CONFLICT (role_code) DO NOTHING;

INSERT INTO region (region_code, region_name, region_level, path, sort_order)
VALUES
('100000', '龙川县', 'city', '100000', 1),
('100100', '龙安镇', 'town', '100000/100100', 2),
('100200', '石桥镇', 'town', '100000/100200', 3),
('100300', '清河镇', 'town', '100000/100300', 4)
ON CONFLICT (region_code) DO NOTHING;

INSERT INTO sys_user (username, password_hash, real_name, phone, role_code, region_code, status)
VALUES
('admin', '$2b$10$e7J.wokpqTMslzHzJq/ateJNEhEnLh/pgz0gXB1HEv1mp6qDaI5pq', '李晨曦', '13800000000', 'admin', '100000', '启用'),
('attendant', '$2b$10$e7J.wokpqTMslzHzJq/ateJNEhEnLh/pgz0gXB1HEv1mp6qDaI5pq', '周小雨', '13800000001', 'attendant', '100100', '启用'),
('maintenance', '$2b$10$e7J.wokpqTMslzHzJq/ateJNEhEnLh/pgz0gXB1HEv1mp6qDaI5pq', '陈晓峰', '13800000002', 'maintenance', '100000', '启用')
ON CONFLICT (username) DO NOTHING;

INSERT INTO sys_menu (menu_code, menu_name, menu_type, path, component, icon, permission_code, sort_order, visible)
VALUES
('overview', '态势总览', 'MENU', '/', 'OverviewPage', 'LayoutDashboard', 'overview:view', 1, TRUE),
('hazards', '灾害点管理', 'MENU', '/hazards', 'HazardsListPage', 'Mountain', 'hazards:view', 2, TRUE),
('tasks', '巡检任务', 'MENU', '/tasks', 'TasksPage', 'ListChecks', 'tasks:view', 3, TRUE),
('devices', '监测设备', 'MENU', '/devices', 'DevicesPage', 'Radio', 'devices:view', 4, TRUE),
('warnings', '预警中心', 'MENU', '/warnings', 'WarningsPage', 'Bell', 'warnings:view', 5, TRUE),
('emergency', '应急处置', 'MENU', '/emergency', 'EmergencyPage', 'ShieldCheck', 'emergency:view', 6, TRUE),
('analytics', '统计分析', 'MENU', '/analytics', 'AnalyticsPage', 'Activity', 'analytics:view', 7, TRUE)
ON CONFLICT (menu_code) DO NOTHING;

INSERT INTO hazard_point (hazard_code, name, hazard_type, risk_level, region_code, owner, status, longitude, latitude, geom, threat_object)
VALUES
('HZ-001', '青龙坡滑坡隐患点', '滑坡', '高风险', '100100', '龙安镇', '位移 +12mm', 107.210000, 30.610000, ST_SetSRID(ST_MakePoint(107.210000, 30.610000), 4326), '居民区'),
('HZ-002', '大岭村崩塌点', '崩塌', '关注', '100200', '石桥镇', '裂缝 18mm', 107.340000, 30.480000, ST_SetSRID(ST_MakePoint(107.340000, 30.480000), 4326), '学校'),
('HZ-003', '白石沟泥石流', '泥石流', '正常', '100300', '清河镇', '雨量 8.2mm', 107.550000, 30.420000, ST_SetSRID(ST_MakePoint(107.550000, 30.420000), 4326), '道路'),
('HZ-004', '云台山地裂缝', '地裂缝', '关注', '100300', '清河镇', '倾角 2.1°', 107.610000, 30.670000, ST_SetSRID(ST_MakePoint(107.610000, 30.670000), 4326), '通信基站'),
('HZ-005', '石门沟滑坡点', '滑坡', '正常', '100100', '龙安镇', '位移稳定', 107.140000, 30.560000, ST_SetSRID(ST_MakePoint(107.140000, 30.560000), 4326), '农田')
ON CONFLICT (hazard_code) DO NOTHING;

INSERT INTO inspection_task (task_code, name, task_type, executor, region_code, status, progress, start_time, due_time, created_by)
VALUES
('TK-001', '雨后重点灾害点复查', '雨后复查', '王建国', '100100', '执行中', 92, NOW(), NOW() + INTERVAL '8 hour', 'admin'),
('TK-002', '青龙坡高风险点加密巡检', '应急巡检', '周小雨', '100100', '待处理', 68, NOW(), NOW() + INTERVAL '6 hour', 'admin'),
('TK-003', '云台山地裂缝专项排查', '专项排查', '陈晓峰', '100300', '已完成', 100, NOW() - INTERVAL '1 day', NOW() - INTERVAL '2 hour', 'admin')
ON CONFLICT (task_code) DO NOTHING;

INSERT INTO monitoring_device (device_code, device_name, device_type, hazard_code, region_code, online_status, battery_level, signal_level, last_report_at, install_location, longitude, latitude, api_key, geom)
VALUES
('DV-001', 'GNSS-001', 'GNSS位移', 'HZ-001', '100100', '在线', 92, '强', NOW() - INTERVAL '2 minute', '青龙坡', 107.210000, 30.610000, 'devkey-gnss-001-secret', ST_SetSRID(ST_MakePoint(107.210000, 30.610000), 4326)),
('DV-002', 'CRK-014', '裂缝计', 'HZ-002', '100200', '在线', 88, '强', NOW() - INTERVAL '1 minute', '大岭村', 107.340000, 30.480000, 'devkey-crk-014-secret', ST_SetSRID(ST_MakePoint(107.340000, 30.480000), 4326)),
('DV-003', 'RAIN-052', '雨量计', 'HZ-003', '100300', '离线', 34, '弱', NOW() - INTERVAL '18 minute', '白石沟', 107.550000, 30.420000, 'devkey-rain-052-secret', ST_SetSRID(ST_MakePoint(107.550000, 30.420000), 4326)),
('DV-004', 'INC-223', '倾角计', 'HZ-004', '100300', '在线', 76, '中', NOW(), '云台山', 107.610000, 30.670000, 'devkey-inc-223-secret', ST_SetSRID(ST_MakePoint(107.610000, 30.670000), 4326))
ON CONFLICT (device_code) DO NOTHING;

INSERT INTO warning_rule (rule_code, rule_name, metric_code, expression, warning_level, enabled, remark)
VALUES
('WR-001', '位移速率预警', 'disp_rate', 'disp_rate > 5', '橙色预警', TRUE, '位移速率超过阈值'),
('WR-002', '裂缝宽度预警', 'crack_width', 'crack_width > 10', '黄色预警', TRUE, '裂缝扩展异常'),
('WR-003', '雨量阈值提醒', 'rain_1h', 'rain_1h > 50', '蓝色提醒', TRUE, '小时雨量过高')
ON CONFLICT (rule_code) DO NOTHING;

INSERT INTO warning_event (warning_code, rule_code, hazard_code, title, warning_level, trigger_value, status, triggered_at, rule_snapshot)
VALUES
('WG-001', 'WR-001', 'HZ-001', '青龙坡滑坡隐患点', '橙色预警', 'disp_rate=6.2', '待确认', NOW() - INTERVAL '20 minute', '{"metric":"disp_rate","threshold":5}'),
('WG-002', 'WR-002', 'HZ-002', '大岭村崩塌点', '黄色预警', 'crack_width=18', '处理中', NOW() - INTERVAL '35 minute', '{"metric":"crack_width","threshold":10}'),
('WG-003', 'WR-003', 'HZ-004', '云台山地裂缝', '蓝色提醒', 'rain_1h=58', '待确认', NOW() - INTERVAL '55 minute', '{"metric":"rain_1h","threshold":50}')
ON CONFLICT (warning_code) DO NOTHING;

INSERT INTO emergency_event (event_code, warning_code, title, status, commander, step_name, result_summary, started_at)
VALUES
('EM-001', 'WG-001', '青龙坡橙色预警', '专家复核中', '自然资源局值班组', '会商', '正在研判滑坡演化趋势', NOW() - INTERVAL '10 minute')
ON CONFLICT (event_code) DO NOTHING;

INSERT INTO file_attachment (business_type, business_code, file_name, file_size, file_type, storage_path, file_url, uploader)
SELECT * FROM (VALUES
  ('hazard', 'HZ-001', '现场照片.jpg', 1240000, 'image/jpeg', 'hazard/HZ-001/现场照片.jpg', 'http://localhost:9000/geosentry/hazard/HZ-001/现场照片.jpg', 'admin'),
  ('task', 'TK-001', '巡检轨迹.kml', 86000, 'application/vnd.google-earth.kml+xml', 'task/TK-001/巡检轨迹.kml', 'http://localhost:9000/geosentry/task/TK-001/巡检轨迹.kml', 'admin'),
  ('warning', 'WG-001', '处置记录.docx', 420000, 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', 'warning/WG-001/处置记录.docx', 'http://localhost:9000/geosentry/warning/WG-001/处置记录.docx', 'admin')
) AS v(business_type, business_code, file_name, file_size, file_type, storage_path, file_url, uploader)
WHERE NOT EXISTS (SELECT 1 FROM file_attachment);

INSERT INTO risk_score_log (hazard_code, score, risk_level, factors)
SELECT * FROM (VALUES
  ('HZ-001', 86.50, '高风险', '{"disp_rate":6.2,"rain_1h":58}'::jsonb),
  ('HZ-002', 63.20, '关注', '{"crack_width":18,"rain_1h":40}'::jsonb),
  ('HZ-004', 61.00, '关注', '{"rain_1h":58,"tilt":2.1}'::jsonb)
) AS v(hazard_code, score, risk_level, factors)
WHERE NOT EXISTS (SELECT 1 FROM risk_score_log);
