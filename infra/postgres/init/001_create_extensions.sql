-- DisasterGuard PostGIS 扩展初始化（首次创建卷时由 docker-entrypoint-initdb.d 执行）
-- 建表与种子数据由后端 spring.sql.init（schema.sql / data.sql）统一管理，
-- 此处仅负责 PostGIS / UUID 扩展的引导，避免两处维护漂移。
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";