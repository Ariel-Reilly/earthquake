-- =====================================================================
-- 灾种值域归一化迁移（R11 E4，2026-11-26）
--
-- 问题：AI 侧返回中文灾种（滑坡/崩塌/泥石流/地裂缝/地面沉降/塌陷，另有「其他」「待定」），
--       而库表、sys_dict.disaster_type、前端 hazardTypes.js 均为英文码 11 类。
--       HazardService#createFromAiDetection 曾把 AI 返回值原样写库，于是库里出现
--       中文 hazard_type —— 前端类型筛选/标签/统计全部失配。
--
-- 本脚本把**存量**数据刷成字典英文码；**新增**数据由 HazardTypeCodes 在写入前归一化。
-- 映射口径与 HazardTypeCodes / data.sql / 前端字典三处一致：
--   滑坡→landslide  崩塌→collapse   泥石流→debris_flow  地裂缝→ground_fissure
--   地面沉降→subsidence  塌陷→subsidence（与既有「地面塌陷→subsidence」先例一致）
--   地面塌陷→subsidence  城市内涝→waterlogging  其他/待定/未知→NULL（未分类，不伪造类型）
--
-- 目标表：unified_event（hazard_point 是从该表 kind='HAZARD' 投影出的可写视图，
--         直接更新基表避开视图可写性的边界情况）；warning_event.hazard_type 同步清理。
-- 幂等：条件命中才更新，重复执行无副作用。
-- 事务：整脚本 BEGIN/COMMIT，失败可整体回滚。
--
-- 用法：psql -h <host> -U <user> -d disasterguard -f migrate_hazard_type_codes.sql
-- 说明：位于 backend/ 根目录的迁移脚本，不并入 schema.sql（spring.sql.init 不自动执行）。
-- =====================================================================

BEGIN;

-- 1) 备份将被改写的行为快照。IF NOT EXISTS ⇒ 重复执行时保留首次快照，不会用改后的值覆盖。
CREATE TABLE IF NOT EXISTS migrate_backup_hazard_type_20261126 AS
SELECT 'unified_event' AS src_table, id, hazard_type
  FROM unified_event
 WHERE hazard_type IS NOT NULL
   AND hazard_type !~ '^[a-z_]+$';

-- 2) unified_event（隐患点 + 事件）
UPDATE unified_event
   SET hazard_type = CASE hazard_type
                       WHEN '滑坡' THEN 'landslide'
                       WHEN '崩塌' THEN 'collapse'
                       WHEN '泥石流' THEN 'debris_flow'
                       WHEN '泥石流灾害' THEN 'debris_flow'
                       WHEN '地裂缝' THEN 'ground_fissure'
                       WHEN '地面裂缝' THEN 'ground_fissure'
                       WHEN '地裂' THEN 'ground_fissure'
                       WHEN '地面沉降' THEN 'subsidence'
                       WHEN '地面塌陷' THEN 'subsidence'
                       WHEN '塌陷' THEN 'subsidence'
                       WHEN '地震' THEN 'earthquake'
                       WHEN '洪水' THEN 'flood'
                       WHEN '山洪' THEN 'flood'
                       WHEN '火灾' THEN 'fire'
                       WHEN '台风' THEN 'typhoon'
                       WHEN '干旱' THEN 'drought'
                       WHEN '内涝' THEN 'waterlogging'
                       WHEN '城市内涝' THEN 'waterlogging'
                       ELSE NULL
                     END,
       updated_at = NOW()
 WHERE hazard_type IN ('滑坡', '崩塌', '泥石流', '泥石流灾害', '地裂缝', '地面裂缝', '地裂',
                       '地面沉降', '地面塌陷', '塌陷', '地震', '洪水', '山洪', '火灾',
                       '台风', '干旱', '内涝', '城市内涝',
                       '其他', '待定', '未知', '无', '未识别', 'unknown', 'other');

-- 3) warning_event.hazard_type 同步（该列可空，同样可能残留中文）
UPDATE warning_event
   SET hazard_type = CASE hazard_type
                       WHEN '滑坡' THEN 'landslide'
                       WHEN '崩塌' THEN 'collapse'
                       WHEN '泥石流' THEN 'debris_flow'
                       WHEN '泥石流灾害' THEN 'debris_flow'
                       WHEN '地裂缝' THEN 'ground_fissure'
                       WHEN '地面裂缝' THEN 'ground_fissure'
                       WHEN '地裂' THEN 'ground_fissure'
                       WHEN '地面沉降' THEN 'subsidence'
                       WHEN '地面塌陷' THEN 'subsidence'
                       WHEN '塌陷' THEN 'subsidence'
                       WHEN '地震' THEN 'earthquake'
                       WHEN '洪水' THEN 'flood'
                       WHEN '山洪' THEN 'flood'
                       WHEN '火灾' THEN 'fire'
                       WHEN '台风' THEN 'typhoon'
                       WHEN '干旱' THEN 'drought'
                       WHEN '内涝' THEN 'waterlogging'
                       WHEN '城市内涝' THEN 'waterlogging'
                       ELSE NULL
                     END
 WHERE hazard_type IN ('滑坡', '崩塌', '泥石流', '泥石流灾害', '地裂缝', '地面裂缝', '地裂',
                       '地面沉降', '地面塌陷', '塌陷', '地震', '洪水', '山洪', '火灾',
                       '台风', '干旱', '内涝', '城市内涝',
                       '其他', '待定', '未知', '无', '未识别', 'unknown', 'other');

-- 4) 自检：以下两条应为 0 行。非 0 说明出现了脚本未覆盖的新写法，需补映射后重跑。
-- SELECT DISTINCT hazard_type FROM unified_event WHERE hazard_type !~ '^[a-z_]+$';
-- SELECT DISTINCT hazard_type FROM warning_event WHERE hazard_type !~ '^[a-z_]+$';

COMMIT;
