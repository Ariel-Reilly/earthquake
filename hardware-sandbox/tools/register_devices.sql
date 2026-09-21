-- ============================================================
-- GL-Sandbox 沙盘设备 / 隐患点注册（幂等，可重复执行）
--
-- 执行方式（任选其一）：
--   psql -h localhost -p 15432 -U postgres -d disasterguard -f register_devices.sql
--   或：python tools/register_devices.py
--
-- 坐标说明：由沙盘布点 (mm) 按 bbox 换算得到
--   bbox = 106.5150,29.5425 ~ 106.5450,29.5600   （约 2.91 km x 1.93 km）
--   沙盘 = 1200 x 800 mm   （水平 1:2421）
--   lon = 106.5150 + x/1200 * 0.0300
--   lat = 29.5425  + y/800  * 0.0175
--
-- 前置：monitoring_device.hazard_code 有外键指向 hazard_point(hazard_code)，
--       hazard_point 是 unified_event WHERE kind='HAZARD' 的视图，
--       所以必须先插隐患点，再插设备。
-- ============================================================

-- ------------------------------------------------------------
-- 1. 隐患点（沙盘 4 类灾种）
--    B 区 李子坝岸坡 / C 区 佛图关危岩 / A 区 嘉陵江河谷 / D 区 滨江低洼
-- ------------------------------------------------------------
INSERT INTO unified_event (
  kind, event_code, state, source,
  hazard_code, name, hazard_type, risk_level, region_code, owner, hazard_status,
  longitude, latitude, geom, threat_object, hazard_source, discovered_at, detection_confidence
) VALUES
('HAZARD','EVT-SBOX-01','DETECTED','DEVICE','HZ-SBOX-01','李子坝岸坡滑坡隐患点','landslide','中风险','500103','渝中区应急局','沙盘监测中',
 106.525,29.552344,ST_SetSRID(ST_MakePoint(106.525,29.552344),4326),'李子坝轻轨站及沿岸建筑群','DEVICE',NOW(),0.80),
('HAZARD','EVT-SBOX-02','DETECTED','DEVICE','HZ-SBOX-02','佛图关危岩崩塌隐患','collapse','中风险','500103','渝中区应急局','沙盘监测中',
 106.5225,29.546437,ST_SetSRID(ST_MakePoint(106.5225,29.546437),4326),'佛图关步道及崖下道路','DEVICE',NOW(),0.78),
('HAZARD','EVT-SBOX-03','DETECTED','DEVICE','HZ-SBOX-03','嘉陵江洪涝风险段','flood','高风险','500103','渝中区水务局','沙盘监测中',
 106.53,29.558031,ST_SetSRID(ST_MakePoint(106.53,29.558031),4326),'滨江路低洼段及码头','DEVICE',NOW(),0.90),
('HAZARD','EVT-SBOX-04','DETECTED','DEVICE','HZ-SBOX-04','滨江路低洼内涝点','waterlogging','中风险','500103','渝中区水务局','沙盘监测中',
 106.53875,29.553875,ST_SetSRID(ST_MakePoint(106.53875,29.553875),4326),'滨江路下穿道','DEVICE',NOW(),0.72)
ON CONFLICT DO NOTHING;

-- ------------------------------------------------------------
-- 2. 监测设备（8 台）
--    ★ hazard_code 必须非空：RuleEngineScheduler 会丢弃 hazard 为空的指标
-- ------------------------------------------------------------
INSERT INTO monitoring_device (
  device_code, device_name, device_type, hazard_code, region_code,
  online_status, battery_level, signal_level, last_report_at,
  install_location, longitude, latitude, api_key, geom
) VALUES
('SB-RAIN-01','李子坝雨量站','rain_gauge','HZ-SBOX-01','500103','离线',100,'强',NULL,'B区坡顶承雨口',
 106.525,29.550156,'sandbox-sb-rain-01',ST_SetSRID(ST_MakePoint(106.525,29.550156),4326)),
('SB-SOIL-01','坡体土壤含水站','soil_moisture','HZ-SBOX-01','500103','离线',100,'强',NULL,'B区坡体中部',
 106.5255,29.552563,'sandbox-sb-soil-01',ST_SetSRID(ST_MakePoint(106.5255,29.552563),4326)),
('SB-GNSS-01','坡体位移站','gnss','HZ-SBOX-01','500103','离线',100,'强',NULL,'B区坡体前缘',
 106.526,29.554969,'sandbox-sb-gnss-01',ST_SetSRID(ST_MakePoint(106.526,29.554969),4326)),
('SB-INCL-01','佛图关倾角站','inclinometer','HZ-SBOX-02','500103','离线',100,'强',NULL,'C区危岩体',
 106.5225,29.546437,'sandbox-sb-incl-01',ST_SetSRID(ST_MakePoint(106.5225,29.546437),4326)),
('SB-CRACK-01','佛图关裂缝计','crack_meter','HZ-SBOX-02','500103','离线',100,'强',NULL,'C区崖顶裂缝',
 106.52325,29.544906,'sandbox-sb-crack-01',ST_SetSRID(ST_MakePoint(106.52325,29.544906),4326)),
('WS-RIVER-01','嘉陵江水位站','water_level','HZ-SBOX-03','500103','离线',100,'强',NULL,'A区河道',
 106.53,29.558031,'sandbox-ws-river-01',ST_SetSRID(ST_MakePoint(106.53,29.558031),4326)),
('SB-FLOOD-01','滨江低洼积水站','water_level','HZ-SBOX-04','500103','离线',100,'强',NULL,'D区低洼路网',
 106.53875,29.553875,'sandbox-sb-flood-01',ST_SetSRID(ST_MakePoint(106.53875,29.553875),4326)),
('SB-CAM-01','沙盘坡体摄像头','camera','HZ-SBOX-01','500103','离线',100,'强',NULL,'B区正面',
 106.52525,29.549063,'sandbox-sb-cam-01',ST_SetSRID(ST_MakePoint(106.52525,29.549063),4326))
ON CONFLICT (device_code) DO UPDATE SET
  device_name = EXCLUDED.device_name,
  hazard_code = EXCLUDED.hazard_code,
  install_location = EXCLUDED.install_location,
  longitude = EXCLUDED.longitude,
  latitude = EXCLUDED.latitude,
  geom = EXCLUDED.geom,
  updated_at = NOW();

-- ------------------------------------------------------------
-- 3. 演示前清理：归档上一轮未关闭的沙盘预警
--    ★ 必须做：RuleEngineScheduler 对 (ruleCode, hazardCode) 已存在未关闭预警时
--      不会再创建，导致二次演示"哑火"
-- ------------------------------------------------------------
UPDATE warning_event
   SET status = '已解除',
       close_reason = '沙盘演示重置',
       closed_at = NOW(),
       updated_at = NOW()
 WHERE hazard_code LIKE 'HZ-SBOX-%'
   AND status NOT IN ('已关闭','已忽略','已解除');

-- ------------------------------------------------------------
-- 4. 注册结果自查（取消注释执行）
-- ------------------------------------------------------------
-- SELECT device_code, device_type, hazard_code, online_status
--   FROM monitoring_device
--  WHERE device_code LIKE 'SB-%' OR device_code LIKE 'WS-%'
--  ORDER BY device_code;
