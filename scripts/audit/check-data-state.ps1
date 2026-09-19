$env:PGPASSWORD = 'disasterguard123'
Write-Host '--- hazard_point ---'
& 'E:\PostgreSQL\18\bin\psql.exe' -h 127.0.0.1 -p 15432 -U disasterguard -d disasterguard -c "SELECT table_name, table_type FROM information_schema.tables WHERE table_schema='public' AND table_name='hazard_point';" -w
Write-Host '--- unified_event count ---'
& 'E:\PostgreSQL\18\bin\psql.exe' -h 127.0.0.1 -p 15432 -U disasterguard -d disasterguard -c "SELECT COUNT(*) FROM unified_event;" -w
Write-Host '--- monitoring_device count ---'
& 'E:\PostgreSQL\18\bin\psql.exe' -h 127.0.0.1 -p 15432 -U disasterguard -d disasterguard -c "SELECT COUNT(*) FROM monitoring_device;" -w
Write-Host '--- gis sample ---'
& 'E:\PostgreSQL\18\bin\psql.exe' -h 127.0.0.1 -p 15432 -U disasterguard -d disasterguard -c "SELECT hazard_code, name FROM unified_event WHERE kind='HAZARD' ORDER BY hazard_code;" -w
