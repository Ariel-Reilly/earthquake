$env:PGPASSWORD = 'disasterguard123'
# 当前 hazard_point 是 BASE TABLE（上次运行停在 COMMENT 之前的中间态），
# 不属于任何一方的"最终状态"。把它连同 cascade 依赖一起清掉，让
# schema.sql 从语句 15 开始重新走：先建临时物理表 → 后被 v2.1.0 块 DROP
# CASCADE + CREATE VIEW 替换为视图。
& 'E:\PostgreSQL\18\bin\psql.exe' -h 127.0.0.1 -p 15432 -U disasterguard -d disasterguard -c "DROP TABLE IF EXISTS hazard_point CASCADE;" -w
Write-Host '--- now ---'
& 'E:\PostgreSQL\18\bin\psql.exe' -h 127.0.0.1 -p 15432 -U disasterguard -d disasterguard -c "SELECT table_name, table_type FROM information_schema.tables WHERE table_schema='public' AND table_name='hazard_point';" -w
