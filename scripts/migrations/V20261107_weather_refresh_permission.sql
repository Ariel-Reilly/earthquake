-- =====================================================================
-- 迁移：补充 weather:refresh 权限（A1 修复配套；非 Flyway，手工执行）
-- ---------------------------------------------------------------------
-- 背景：
--   data.sql 中的 weather:refresh 种子仅在「首次部署建表」或「手工重跑
--   data.sql」时生效。依据 application-prod.yml 注释，schema.sql / data.sql
--   属于一次性手工执行（非 spring.sql.init 每次启动自动跑），因此：
--     * 全新库 —— 由 data.sql 自动播种，无需本脚本；
--     * 既已初始化、且早于本次修复的库 —— 不会自动获得该权限，导致
--       POST /api/weather/products/refresh 对所有角色恒 403。
--   本脚本用于给既有库补齐权限，幂等、可重复执行，无副作用。
--
-- 作用：
--   1) 向 sys_permission 补 weather:refresh（已存在则更新属性，ON CONFLICT）；
--   2) 授予 maintenance 角色（设计意图：现场运维手动刷新气象产品）；
--   3) 授予 admin 角色（与 data.sql 的 blanket 全量授权保持一致）。
--
-- 执行方式（任选其一，执行前建议先备份 sys_permission / sys_role_permission）：
--   本地：
--     psql -h 127.0.0.1 -U <dbuser> -d <dbname> \
--       -f scripts/migrations/V20261107_weather_refresh_permission.sql
--   容器（disasterguard-postgres 容器内已挂载项目根）：
--     docker exec -i disasterguard-postgres psql -U disasterguard -d disasterguard \
--       -f /<mount>/scripts/migrations/V20261107_weather_refresh_permission.sql
-- =====================================================================

BEGIN;

-- 1) 权限种子（幂等 upsert，与 data.sql:113 一致）
INSERT INTO sys_permission (code, name, type, route_path, sort_order)
VALUES ('weather:refresh', '刷新气象产品', 'OP', '/dashboard/weather', 17)
ON CONFLICT (code) DO UPDATE SET
  name       = EXCLUDED.name,
  type       = EXCLUDED.type,
  route_path = EXCLUDED.route_path,
  sort_order = EXCLUDED.sort_order;

-- 2) 授予 maintenance（设计意图：现场运维手动刷新）
INSERT INTO sys_role_permission (role_id, perm_code)
SELECT id, 'weather:refresh'
FROM sys_role WHERE role_code = 'maintenance'
ON CONFLICT (role_id, perm_code) DO NOTHING;

-- 3) 授予 admin（与 data.sql:247-251 的 blanket 全量授权对齐）
INSERT INTO sys_role_permission (role_id, perm_code)
SELECT id, 'weather:refresh'
FROM sys_role WHERE role_code = 'admin'
ON CONFLICT (role_id, perm_code) DO NOTHING;

-- 校验（执行后直接回显，便于确认）
SELECT 'permission' AS check_item, code, name, type, route_path, sort_order
FROM sys_permission WHERE code = 'weather:refresh';

SELECT 'grants' AS check_item, r.role_code, p.code
FROM sys_role_permission rp
JOIN sys_role r ON r.id = rp.role_id
JOIN sys_permission p ON p.code = rp.perm_code
WHERE p.code = 'weather:refresh'
ORDER BY r.role_code;

COMMIT;
