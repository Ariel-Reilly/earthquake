$env:PGPASSWORD = 'disasterguard123'
Write-Host '--- postgis extension status ---'
& 'E:\PostgreSQL\18\bin\psql.exe' -h 127.0.0.1 -p 15432 -U disasterguard -d disasterguard -c "SELECT extname, extversion FROM pg_extension WHERE extname IN ('postgis','uuid-ossp');" -w
Write-Host '--- database list ---'
& 'E:\PostgreSQL\18\bin\psql.exe' -h 127.0.0.1 -p 15432 -U disasterguard -d disasterguard -c "\l" -w
Write-Host '--- schema list in disasterguard ---'
& 'E:\PostgreSQL\18\bin\psql.exe' -h 127.0.0.1 -p 15432 -U disasterguard -d disasterguard -c "\dn" -w
Write-Host '--- existing tables ---'
& 'E:\PostgreSQL\18\bin\psql.exe' -h 127.0.0.1 -p 15432 -U disasterguard -d disasterguard -c "\dt public.*" -w
Write-Host '--- error attempt: create table with geometry ---'
& 'E:\PostgreSQL\18\bin\psql.exe' -h 127.0.0.1 -p 15432 -U disasterguard -d disasterguard -c "CREATE TABLE _x (id serial, g geometry(Point,4326));" -w 2>&1
