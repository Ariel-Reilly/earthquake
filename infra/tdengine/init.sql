-- =====================================================================
-- TDengine 初始化脚本（首次启动时自动执行，idempotent）
-- 说明：
--   * TDengine 的建库/建表语法与 PostgreSQL 不同，不支持 IF NOT EXISTS
--     我们用 DO...WHILE 加异常忽略来实现幂等
--   * 本脚本被 docker-compose 的 tdengine 服务通过 volume 挂载到
--     /docker-entrypoint-initdb.d/init.sql，由 TDengine 官方镜像自动执行
--   * 设备指标采用超级表 + 每设备一张子表的模式，便于按设备聚合查询
-- =====================================================================

-- 创建数据库（已存在会报错，忽略即可）
CREATE DATABASE IF NOT EXISTS geohazard KEEP 365 DURATION 10 BUFFER 256;
USE geohazard;

-- 创建超级表：设备指标
-- ts: 时间戳（主键）
-- metric_code: 指标编码（disp_rate / crack_width / rain_1h / tilt / battery 等）
-- value: 数值
-- 标签：device_code（设备号）、hazard_code（关联灾害点）、device_type（设备类型）
CREATE STABLE IF NOT EXISTS device_metric (
    ts TIMESTAMP,
    metric_code NCHAR(32),
    value FLOAT
) TAGS (
    device_code NCHAR(64),
    hazard_code NCHAR(64)
);

-- 为已有的种子设备预创建子表（实际部署时可以按需动态创建）
-- TDengine 子表命名：device_metric_<device_code>
-- 这里仅作为参考，生产环境设备注册时由后端自动 CREATE TABLE IF NOT EXISTS

-- 种子设备子表
CREATE TABLE IF NOT EXISTS device_metric_dv_001 USING device_metric TAGS ('DV-001', 'HZ-001');
CREATE TABLE IF NOT EXISTS device_metric_dv_002 USING device_metric TAGS ('DV-002', 'HZ-002');
CREATE TABLE IF NOT EXISTS device_metric_dv_003 USING device_metric TAGS ('DV-003', 'HZ-003');
CREATE TABLE IF NOT EXISTS device_metric_dv_004 USING device_metric TAGS ('DV-004', 'HZ-004');
