-- 2026-11-12 交付修复：event_sign.sign_id 唯一约束误加
--
-- 背景：event_sign 的语义是「一次会签会话 × 一个会签人 = 一行」，
-- 同一 sign_id 必须能有多行；历史 schema 误给 sign_id 加了 UNIQUE，
-- 导致多人会签插入第二个会签人时触发唯一约束冲突（接口 500）。
--
-- 全新部署无需执行本脚本（schema.sql 已改为普通索引 + 新增 idx_event_sign_sign）。
-- 仅当升级已有数据库、且不重启后端时手工执行一次。
-- 后端 DatabaseSchemaPatchInitializer 启动时也会幂等执行同样的修复。

ALTER TABLE event_sign DROP CONSTRAINT IF EXISTS event_sign_sign_id_key;
CREATE INDEX IF NOT EXISTS idx_event_sign_sign ON event_sign(sign_id);
