$ErrorActionPreference = 'Stop'

Write-Host '=== 1. Stop and remove postgres container + volume ==='
docker compose stop postgres 2>&1 | Out-Null
docker compose rm -f postgres 2>&1 | Out-Null
docker volume rm disasterguard_postgres_data 2>&1 | Out-Null

Write-Host ''
Write-Host '=== 2. Bring postgres back up (triggers initdb with compose defaults) ==='
Set-Location 'D:\earthquake'
docker compose up -d postgres 2>&1 | Out-Null

Write-Host ''
Write-Host '=== 3. Wait for postgres to become healthy ==='
$ready = $false
for ($i = 1; $i -le 30; $i++) {
  $status = docker inspect --format='{{.State.Health.Status}}' disasterguard-postgres 2>$null
  if ($status -eq 'healthy') { $ready = $true; break }
  Write-Host ("  waiting... ({0}s) status={1}" -f $i, $status)
  Start-Sleep -Seconds 2
}
if (-not $ready) {
  Write-Host '[FAIL] Postgres did not become healthy in 60s'
  exit 1
}
Write-Host '[OK] Postgres healthy'

Write-Host ''
Write-Host '=== 4. Verify auth works from container ==='
docker exec disasterguard-postgres bash -c "PGPASSWORD=disasterguard123 psql -h 127.0.0.1 -U disasterguard -d disasterguard -c 'SELECT current_user, current_database();'" 2>&1

Write-Host ''
Write-Host '=== 5. Verify auth works from host (mimicking Spring Boot) ==='
docker exec disasterguard-postgres bash -c "PGPASSWORD=disasterguard123 psql -h 127.0.0.1 -p 5432 -U disasterguard -d disasterguard -c 'SELECT 1;'" 2>&1
