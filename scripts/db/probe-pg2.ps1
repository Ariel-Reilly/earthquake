docker exec disasterguard-postgres cat /etc/passwd
Write-Host '---'
docker exec disasterguard-postgres id
Write-Host '---'
docker exec disasterguard-postgres cat /var/lib/postgresql/data/PG_VERSION
Write-Host '---'
Write-Host '=== Test local socket as default user ==='
docker exec disasterguard-postgres psql -d disasterguard -c "SELECT current_user, current_database(), version();" 2>&1
Write-Host '---'
Write-Host '=== Try to ALTER password ==='
docker exec disasterguard-postgres psql -d disasterguard -c "ALTER USER disasterguard WITH PASSWORD 'disasterguard123';" 2>&1
Write-Host '---'
Write-Host '=== Verify TCP auth now ==='
docker exec disasterguard-postgres bash -c "PGPASSWORD=disasterguard123 psql -h 127.0.0.1 -p 5432 -U disasterguard -d disasterguard -c 'SELECT 1;'" 2>&1
