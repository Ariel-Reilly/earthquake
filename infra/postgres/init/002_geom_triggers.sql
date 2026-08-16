-- =====================================================================
-- 002_geom_triggers.sql
-- 为三张含 longitude/latitude + geom 列的表创建 geom 自动同步触发器
-- 适用表：hazard_point、monitoring_device、inspection_record
-- 触发场景：INSERT / UPDATE 时，若 longitude/latitude 被修改则自动重算 geom
-- 前置条件：已启用 postgis 扩展（001_create_extensions.sql）
-- =====================================================================

-- 触发器函数：根据经纬度同步 geom（EPSG:4326 WGS84）
CREATE OR REPLACE FUNCTION sync_geom_from_lonlat()
RETURNS TRIGGER AS $$
BEGIN
  -- 经度变化 或 纬度变化 时重算 geom
  IF NEW.longitude IS NOT NULL AND NEW.latitude IS NOT NULL
     AND (TG_OP = 'INSERT'
          OR OLD.longitude IS DISTINCT FROM NEW.longitude
          OR OLD.latitude IS DISTINCT FROM NEW.latitude) THEN
    NEW.geom := ST_SetSRID(ST_MakePoint(NEW.longitude, NEW.latitude), 4326);
  END IF;
  -- 经纬度置空时，同步置空 geom
  IF NEW.longitude IS NULL OR NEW.latitude IS NULL THEN
    NEW.geom := NULL;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- hazard_point 表 geom 同步触发器
DROP TRIGGER IF EXISTS trg_hazard_point_sync_geom ON hazard_point;
CREATE TRIGGER trg_hazard_point_sync_geom
BEFORE INSERT OR UPDATE OF longitude, latitude ON hazard_point
FOR EACH ROW EXECUTE FUNCTION sync_geom_from_lonlat();

-- monitoring_device 表 geom 同步触发器
DROP TRIGGER IF EXISTS trg_monitoring_device_sync_geom ON monitoring_device;
CREATE TRIGGER trg_monitoring_device_sync_geom
BEFORE INSERT OR UPDATE OF longitude, latitude ON monitoring_device
FOR EACH ROW EXECUTE FUNCTION sync_geom_from_lonlat();

-- inspection_record 表 geom 同步触发器（注意此表字段名为 sign_in_lon / sign_in_lat）
-- 需要单独的触发器函数处理不同字段名
CREATE OR REPLACE FUNCTION sync_inspection_record_geom()
RETURNS TRIGGER AS $$
BEGIN
  IF NEW.sign_in_lon IS NOT NULL AND NEW.sign_in_lat IS NOT NULL
     AND (TG_OP = 'INSERT'
          OR OLD.sign_in_lon IS DISTINCT FROM NEW.sign_in_lon
          OR OLD.sign_in_lat IS DISTINCT FROM NEW.sign_in_lat) THEN
    NEW.geom := ST_SetSRID(ST_MakePoint(NEW.sign_in_lon, NEW.sign_in_lat), 4326);
  END IF;
  IF NEW.sign_in_lon IS NULL OR NEW.sign_in_lat IS NULL THEN
    NEW.geom := NULL;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_inspection_record_sync_geom ON inspection_record;
CREATE TRIGGER trg_inspection_record_sync_geom
BEFORE INSERT OR UPDATE OF sign_in_lon, sign_in_lat ON inspection_record
FOR EACH ROW EXECUTE FUNCTION sync_inspection_record_geom();
