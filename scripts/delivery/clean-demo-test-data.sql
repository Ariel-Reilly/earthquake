-- 演示库测试数据清理（可回滚，仅用于隔离演示库）
--
-- 背景：自动化冒烟/验收在演示库留下了测试预警与通知，归档页会出现
--       「【测试】手动触发预警」「acceptance-test」「浏览器验收-确认派发」，
--       演示时看起来像真实业务数据错误。
--
-- 范围：
--   1) warning_event 中标题含「【测试】」或关闭原因为
--      acceptance-test / R10 审查注入测试清理 的记录；
--   2) notification_record 中由这些预警派生的浏览器验收通知，
--      以及标题/正文含「浏览器验收」或「【测试】」的通知。
--
-- 不做：
--   - 不删除真实历史归档；
--   - 不改业务时间；
--   - 不进入应用运行逻辑，只作为演示库运维脚本。
--
-- 用法（先备份，再执行）：
--   docker exec -i disasterguard-postgres pg_dump -U disasterguard -d disasterguard > backup.sql
--   Get-Content scripts/delivery/clean-demo-test-data.sql -Raw |
--     docker exec -i disasterguard-postgres psql -U disasterguard -d disasterguard -v ON_ERROR_STOP=1 -f -
--
-- 幂等：重复执行不会出错；第二次执行匹配 0 条。

BEGIN;

CREATE TEMP TABLE demo_test_warning_ids ON COMMIT DROP AS
SELECT id, warning_code
FROM warning_event
WHERE title LIKE '%【测试】%'
   OR close_reason IN ('acceptance-test', 'R10 审查注入测试清理');

-- 通知会按 biz_id / 标题 / 正文三路匹配，避免漏掉只带预警编号的派生通知。
-- 同时清掉没有对应预警的孤儿派发通知与乱码标题（历史导入残留）。
DELETE FROM notification_record
WHERE title LIKE '%浏览器验收%'
   OR content LIKE '%浏览器验收%'
   OR title LIKE '%【测试】%'
   OR title LIKE '%???%'
   OR content LIKE '%???%'
   OR biz_id IN (SELECT warning_code FROM demo_test_warning_ids)
   OR (
     title LIKE '【预警派发】%'
     AND NOT EXISTS (
       SELECT 1 FROM warning_event w WHERE w.warning_code = notification_record.biz_id
     )
   );

DELETE FROM warning_event
WHERE id IN (SELECT id FROM demo_test_warning_ids);

SELECT
  (SELECT count(*) FROM warning_event) AS warnings_after,
  (SELECT count(*) FROM notification_record) AS notifications_after;

COMMIT;
