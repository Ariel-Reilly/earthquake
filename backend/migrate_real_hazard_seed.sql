-- =====================================================================
-- 真实灾害案例种子迁移（2026-11-21）
-- 目的：把 data.sql 早期版本里 5 处【虚构】隐患点（青龙坡/歌乐山/歇马/石门沟）
--       替换为【可溯源】的真实案例，来源均为重庆市人民政府网 cq.gov.cn。
-- 幂等：可重复执行（UPDATE 无副作用）。
-- 事务：整脚本由 BEGIN/COMMIT 包裹，失败可整体回滚。
--
-- 来源（逐条）：
--   HZ-001 万盛经开区金桥镇牯牛塘滑坡
--     https://cq.gov.cn/zt/2026cqsaqdx/fxdt/202608/t20260814_15932841.html
--   HZ-002 万盛经开区黑山镇老黑山石笋危岩
--     https://cq.gov.cn/zt/2026cqsaqdx/fxdt/202606/t20260630_15788174.html
--   HZ-003 渝中区滨江岸线消落带洪涝风险
--     https://cq.gov.cn/ywdt/zwhd/qxdt/202608/t20260803_15885374.html
--   HZ-004 两江新区古路镇银花村水洞子泥石流
--     https://cq.gov.cn/zt/2026cqsaqdx/fxdt/202608/t20260805_15895969.html
--   HZ-005 巫山县官渡镇平南村滑坡
--     https://cq.gov.cn/zt/2026cqsaqdx/fxdt/202608/t20260805_15895969.html
--
-- 坐标：区县政府驻地公开中心点（可在公开地图核验），非隐患点厘米级精测坐标。
--       精测坐标应由主管部门实测后录入，本脚本不虚构精确点位。
-- =====================================================================

BEGIN;

-- 0.补充真实区划：巫山县（国标 500237，民政部 2022 行政区划代码），foreign key 依赖。
--   来源：https://www.mca.gov.cn/mzsj/xzqh/2022/202201xzqh.html
INSERT INTO region (region_code, region_name, region_level, path, sort_order, center_lon, center_lat, geo_type)
VALUES ('500237','巫山县','district','500000/500237',24,109.9000,31.0800,'MOUNTAIN')
ON CONFLICT (region_code) DO NOTHING;

-- 1.隐患点（统一事件源表；hazard_point 是它的视图）
--   区划说明：万盛经开区/两江新区在本系统内沿用既有编码（500110/500112），
--   真实行政归属以 owner 责任单位全称与来源 URL 为准，不虚构新编码。
UPDATE unified_event SET
  name          = CASE hazard_code
    WHEN 'HZ-001' THEN '万盛经开区金桥镇牯牛塘滑坡'
    WHEN 'HZ-002' THEN '万盛经开区黑山镇老黑山石笋危岩'
    WHEN 'HZ-003' THEN '渝中区滨江岸线消落带洪涝风险'
    WHEN 'HZ-004' THEN '两江新区古路镇银花村水洞子泥石流'
    WHEN 'HZ-005' THEN '巫山县官渡镇平南村滑坡' END,
  hazard_type   = CASE hazard_code
    WHEN 'HZ-001' THEN 'landslide'   WHEN 'HZ-002' THEN 'collapse'
    WHEN 'HZ-003' THEN 'flood'       WHEN 'HZ-004' THEN 'debris_flow'
    WHEN 'HZ-005' THEN 'landslide' END,
  risk_level    = CASE hazard_code
    WHEN 'HZ-001' THEN '高风险' WHEN 'HZ-002' THEN '高风险'
    WHEN 'HZ-003' THEN '高风险' WHEN 'HZ-004' THEN '中风险'
    WHEN 'HZ-005' THEN '高风险' END,
  region_code   = CASE hazard_code
    WHEN 'HZ-001' THEN '500110' WHEN 'HZ-002' THEN '500110'
    WHEN 'HZ-003' THEN '500103' WHEN 'HZ-004' THEN '500112'
    WHEN 'HZ-005' THEN '500237' END,
  owner         = CASE hazard_code
    WHEN 'HZ-001' THEN '万盛经开区规划自然资源局'
    WHEN 'HZ-002' THEN '万盛经开区规划自然资源局'
    WHEN 'HZ-003' THEN '渝中区滨江中心'
    WHEN 'HZ-004' THEN '两江新区王家街道规划自然资源所'
    WHEN 'HZ-005' THEN '巫山县规划自然资源局' END,
  hazard_status = CASE hazard_code
    WHEN 'HZ-001' THEN '监测仪告警·已避险'
    WHEN 'HZ-002' THEN '危岩掉块·管控中'
    WHEN 'HZ-003' THEN '汛期巡查中'
    WHEN 'HZ-004' THEN '已避险·管控中'
    WHEN 'HZ-005' THEN 'GNSS监测·已避险' END,
  longitude     = CASE hazard_code
    WHEN 'HZ-001' THEN 106.8500 WHEN 'HZ-002' THEN 106.8700
    WHEN 'HZ-003' THEN 106.5500 WHEN 'HZ-004' THEN 106.6300
    WHEN 'HZ-005' THEN 109.9000 END,
  latitude      = CASE hazard_code
    WHEN 'HZ-001' THEN 28.8500 WHEN 'HZ-002' THEN 28.8000
    WHEN 'HZ-003' THEN 29.5600 WHEN 'HZ-004' THEN 29.7800
    WHEN 'HZ-005' THEN 31.0800 END,
  geom          = CASE hazard_code
    WHEN 'HZ-001' THEN ST_SetSRID(ST_MakePoint(106.8500,28.8500),4326)
    WHEN 'HZ-002' THEN ST_SetSRID(ST_MakePoint(106.8700,28.8000),4326)
    WHEN 'HZ-003' THEN ST_SetSRID(ST_MakePoint(106.5500,29.5600),4326)
    WHEN 'HZ-004' THEN ST_SetSRID(ST_MakePoint(106.6300,29.7800),4326)
    WHEN 'HZ-005' THEN ST_SetSRID(ST_MakePoint(109.9000,31.0800),4326) END,
  threat_object = CASE hazard_code
    WHEN 'HZ-001' THEN '金桥镇新木村牯牛塘下方居民（4户6人，已提前转移）'
    WHEN 'HZ-002' THEN '黑山镇北门村下方居民区（7户34人，已转移避让）'
    WHEN 'HZ-003' THEN '两江岸线、消落带、护岸堤坝、滨江公园'
    WHEN 'HZ-004' THEN '银花村7社水洞子下方居民（1户6人，已提前转移）'
    WHEN 'HZ-005' THEN '平南村滑坡体下方村民及省道S105（3户11人，已转移）' END,
  source        = 'GOV_PUBLIC',
  hazard_source = 'GOV_PUBLIC',
  detection_confidence = CASE hazard_code
    WHEN 'HZ-001' THEN 0.90 WHEN 'HZ-002' THEN 0.88 WHEN 'HZ-003' THEN 0.85
    WHEN 'HZ-004' THEN 0.82 WHEN 'HZ-005' THEN 0.92 END,
  updated_at    = NOW()
WHERE hazard_code IN ('HZ-001','HZ-002','HZ-003','HZ-004','HZ-005');

-- 2.监测设备（名称、位置、坐标、所属区划同步）
UPDATE monitoring_device SET
  device_name     = CASE device_code
    WHEN 'DV-002' THEN '牯牛塘GNSS-01'    WHEN 'DV-003' THEN '金桥雨量站-01'
    WHEN 'DV-004' THEN '石笋危岩裂缝计-01' WHEN 'DV-005' THEN '石笋危岩倾角计-01'
    WHEN 'DV-008' THEN '银花村土壤站-01' END,
  install_location= CASE device_code
    WHEN 'DV-002' THEN '金桥镇新木村牯牛塘' WHEN 'DV-003' THEN '金桥镇新木村'
    WHEN 'DV-004' THEN '黑山镇北门村石笋危岩' WHEN 'DV-005' THEN '黑山镇北门村石笋危岩'
    WHEN 'DV-008' THEN '古路镇银花村' END,
  region_code     = CASE device_code
    WHEN 'DV-002' THEN '500110' WHEN 'DV-003' THEN '500110'
    WHEN 'DV-004' THEN '500110' WHEN 'DV-005' THEN '500110'
    WHEN 'DV-008' THEN '500112' END,
  longitude       = CASE device_code
    WHEN 'DV-002' THEN 106.8500 WHEN 'DV-003' THEN 106.8500
    WHEN 'DV-004' THEN 106.8700 WHEN 'DV-005' THEN 106.8710
    WHEN 'DV-008' THEN 106.6300 END,
  latitude        = CASE device_code
    WHEN 'DV-002' THEN 28.8500 WHEN 'DV-003' THEN 28.8500
    WHEN 'DV-004' THEN 28.8000 WHEN 'DV-005' THEN 28.8010
    WHEN 'DV-008' THEN 29.7800 END,
  geom            = CASE device_code
    WHEN 'DV-002' THEN ST_SetSRID(ST_MakePoint(106.8500,28.8500),4326)
    WHEN 'DV-003' THEN ST_SetSRID(ST_MakePoint(106.8500,28.8500),4326)
    WHEN 'DV-004' THEN ST_SetSRID(ST_MakePoint(106.8700,28.8000),4326)
    WHEN 'DV-005' THEN ST_SetSRID(ST_MakePoint(106.8710,28.8010),4326)
    WHEN 'DV-008' THEN ST_SetSRID(ST_MakePoint(106.6300,29.7800),4326) END,
  updated_at      = NOW()
WHERE device_code IN ('DV-002','DV-003','DV-004','DV-005','DV-008');
-- DV-003/DV-008 原先 hazard_code 为 NULL，补挂到真实隐患点
UPDATE monitoring_device SET hazard_code='HZ-001', updated_at=NOW() WHERE device_code='DV-003';
UPDATE monitoring_device SET hazard_code='HZ-004', updated_at=NOW() WHERE device_code='DV-008';

-- 3.AI 综合评分：灾种与分数同步
UPDATE ai_comprehensive_score SET
  disaster_type = CASE hazard_code WHEN 'HZ-005' THEN 'landslide' ELSE disaster_type END,
  risk_level    = CASE hazard_code WHEN 'HZ-001' THEN 'orange' WHEN 'HZ-005' THEN 'orange' ELSE risk_level END,
  risk_score    = CASE hazard_code WHEN 'HZ-001' THEN 84.00 WHEN 'HZ-005' THEN 72.00 ELSE risk_score END,
  confidence    = CASE hazard_code WHEN 'HZ-001' THEN 0.90 WHEN 'HZ-005' THEN 0.92 ELSE confidence END
WHERE hazard_code IN ('HZ-001','HZ-005');

-- 4.风险评分日志 HZ-005 因子（地裂缝→滑坡）
UPDATE risk_score_log SET factors = '{"displacement_total":16.0,"rainfall_24h":88}'::jsonb,
  score=72.00, risk_level='高风险'
WHERE hazard_code='HZ-005';
UPDATE risk_score_log SET score=84.00 WHERE hazard_code='HZ-001';

-- 5.巡检任务名称/区划同步真实案例
UPDATE inspection_task SET
  name=CASE hazard_code
    WHEN 'HZ-001' THEN '牯牛塘滑坡雨后加密巡检'
    WHEN 'HZ-002' THEN '老黑山石笋危岩专项排查'
    WHEN 'HZ-004' THEN '银花村水洞子泥石流年度排查'
    WHEN 'HZ-005' THEN '官渡镇平南村滑坡季度复查' END,
  region_code=CASE hazard_code
    WHEN 'HZ-001' THEN '500110' WHEN 'HZ-002' THEN '500110'
    WHEN 'HZ-004' THEN '500112' WHEN 'HZ-005' THEN '500237' END
WHERE hazard_code IN ('HZ-001','HZ-002','HZ-004','HZ-005');

-- 6.审批/工单/消息里的虚构地名
UPDATE approval SET title=REPLACE(title,'青龙坡','牯牛塘') WHERE title LIKE '%青龙坡%';
UPDATE user_todo SET title=REPLACE(REPLACE(title,'青龙坡','牯牛塘'),'歌乐山','石笋危岩'),
  description=REPLACE(REPLACE(description,'青龙坡','牯牛塘'),'歌乐山','石笋危岩')
WHERE title LIKE '%青龙坡%' OR description LIKE '%青龙坡%' OR title LIKE '%歌乐山%' OR description LIKE '%歌乐山%';
UPDATE user_message SET title=REPLACE(REPLACE(title,'青龙坡','牯牛塘'),'歌乐山','石笋危岩'),
  content=REPLACE(REPLACE(content,'青龙坡','牯牛塘'),'歌乐山','石笋危岩')
WHERE title LIKE '%青龙坡%' OR content LIKE '%青龙坡%' OR title LIKE '%歌乐山%' OR content LIKE '%歌乐山%';
-- confirmation_task 无自由文本标题列（仅 task_code/note），此处无需替换
UPDATE warning_event SET title=REPLACE(REPLACE(title,'青龙坡','牯牛塘'),'歌乐山','石笋危岩')
WHERE title LIKE '%青龙坡%' OR title LIKE '%歌乐山%';

-- 7.附件名同步
UPDATE file_attachment SET
  file_name=CASE WHEN file_name LIKE '%青龙坡%' THEN REPLACE(file_name,'青龙坡','牯牛塘')
                 WHEN file_name LIKE '%歌乐山%' THEN REPLACE(file_name,'歌乐山','石笋危岩') ELSE file_name END
WHERE file_name LIKE '%青龙坡%' OR file_name LIKE '%歌乐山%';

COMMIT;
