-- 演示数据新鲜度刷新（2026-11-14 审查 P1-8）
--
-- ⚠ 仅用于**演示库 / 隔离演练库**。本脚本会改写设备在线状态与业务时间戳，
--    禁止在任何生产、试运行或甲方真实数据环境执行。
-- ⚠ 执行前请由演示负责人确认；建议先在隔离库备份：
--    docker exec <pg> pg_dump -U disasterguard -d disasterguard > backup.sql
--
-- 背景：开发库里 16 台设备全部「离线」，预警 / 灾害点最后时间停在 2026-11-06，
--       导致总览页显示「0 在线」、24h 时间对比为 0，评审会误以为功能没接数据。
--       这不是代码 bug，而是演示数据过期；脚本把这些**演示记录**刷新为当前时间。
--
-- 幂等性：只 UPDATE 现有记录，不 INSERT、不 DELETE；重复执行只会把时间再刷一遍，
--         不会累积数据。设备在线数固定为 6 台（按 device_code 排序取前 6）。
--
-- 用法（隔离库 / 演示库示例）：
--   docker exec -i <pg容器> psql -U disasterguard -d disasterguard \
--     -v ON_ERROR_STOP=1 -f - < scripts/delivery/refresh-demo-data.sql
--
-- ⚠ 有效期只有约 5 分钟：后端 `DeviceOnlineChecker` 每 60s 扫描一次，把
--    「在线且 last_report_at 早于 now()-5min」的设备自动置为离线（application.yml:169-170）。
--    演示设备不会真的上报，因此脚本执行约 5 分钟后 6 台「在线」会被心跳检查打回离线，
--    这是**正确的心跳语义**，不是脚本失效。
--    → 请在每场演示开始前 5 分钟内重新执行本脚本（幂等，可反复跑）；
--      长时间彩排可每 4 分钟重跑一次。生产/试运行环境不要这样保活。

BEGIN;

-- 1) 设备：全部刷新最近上报时间；前 6 台标记为在线，其余保持离线。
WITH ranked AS (
  SELECT id, row_number() OVER (ORDER BY device_code) AS rn
  FROM monitoring_device
)
UPDATE monitoring_device d
SET online_status  = CASE WHEN r.rn <= 6 THEN '在线' ELSE '离线' END,
    last_report_at = now(),
    updated_at     = now()
FROM ranked r
WHERE d.id = r.id;

-- 2) 未闭环预警：把时间平移到今天（保留 1 小时间隔），便于演示「实时预警」。
WITH recent AS (
  SELECT id, row_number() OVER (ORDER BY created_at DESC) AS rn
  FROM warning_event
  WHERE status NOT IN ('已解除', '已关闭')
)
UPDATE warning_event w
SET created_at   = now() - (interval '1 hour' * r.rn),
    triggered_at = COALESCE(w.triggered_at, now() - (interval '1 hour' * r.rn)),
    last_seen_at = now(),
    updated_at   = now()
FROM recent r
WHERE w.id = r.id AND r.rn <= 3;

-- 3) 灾害点（hazard_point 是 unified_event 的视图，必须更新基表）。
WITH recent AS (
  SELECT id, row_number() OVER (ORDER BY discovered_at DESC NULLS LAST) AS rn
  FROM unified_event
  WHERE kind::text = 'HAZARD'
)
UPDATE unified_event u
SET discovered_at      = now() - (interval '1 hour' * r.rn),
    last_inspection_at = now() - (interval '30 minute' * r.rn)
FROM recent r
WHERE u.id = r.id AND r.rn <= 3;

COMMIT;

-- 4) 自检：期望「在线设备 6 / 未闭环预警时间=今天 / 灾害点最近发现=今天」。
SELECT
  (SELECT count(*) FILTER (WHERE online_status = '在线') FROM monitoring_device) AS online_devices,
  (SELECT count(*) FROM monitoring_device)                                      AS total_devices,
  (SELECT max(created_at)::date FROM warning_event)                             AS latest_warning_day,
  (SELECT max(discovered_at)::date FROM unified_event WHERE kind::text = 'HAZARD') AS latest_hazard_day,
  current_date                                                                  AS today;
