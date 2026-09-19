$env:PGPASSWORD = 'disasterguard123'
& 'E:\PostgreSQL\18\bin\psql.exe' -h 127.0.0.1 -p 15432 -U disasterguard -d disasterguard -c "SELECT table_name, table_type FROM information_schema.tables WHERE table_schema='public' AND table_name='hazard_point';" -w
