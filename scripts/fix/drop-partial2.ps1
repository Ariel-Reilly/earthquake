$env:PGPASSWORD = 'disasterguard123'
& 'E:\PostgreSQL\18\bin\psql.exe' -h 127.0.0.1 -p 15432 -U disasterguard -d disasterguard -c "DROP TABLE IF EXISTS _x;" -w
& 'E:\PostgreSQL\18\bin\psql.exe' -h 127.0.0.1 -p 15432 -U disasterguard -d disasterguard -c "DROP TABLE IF EXISTS hazard_point CASCADE;" -w
Write-Host '--- now ---'
& 'E:\PostgreSQL\18\bin\psql.exe' -h 127.0.0.1 -p 15432 -U disasterguard -d disasterguard -c "\dt public.*" -w
