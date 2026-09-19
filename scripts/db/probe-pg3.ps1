Write-Host '=== Connect via socket as postgres OS user ==='
docker exec disasterguard-postgres su - postgres -c "psql -d disasterguard -c \"SELECT current_user, current_database();\"" 2>&1
Write-Host '---'
Write-Host '=== List roles ==='
docker exec disasterguard-postgres su - postgres -c "psql -d disasterguard -c \"SELECT usename, usesuper FROM pg_user ORDER BY usename;\"" 2>&1
Write-Host '---'
Write-Host '=== List databases ==='
docker exec disasterguard-postgres su - postgres -c "psql -l" 2>&1
Write-Host '---'
Write-Host '=== Inspect data dir ==='
docker exec disasterguard-postgres ls -la /var/lib/postgresql/data 2>&1
Write-Host '---'
Write-Host '=== Read PG_VERSION & postgresql.conf snippet ==='
docker exec disasterguard-postgres bash -c "cat /var/lib/postgresql/data/PG_VERSION; head -50 /var/lib/postgresql/data/postgresql.conf 2>/dev/null"
