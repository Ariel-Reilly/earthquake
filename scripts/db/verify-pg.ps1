$ready = $false
for ($i = 1; $i -le 30; $i++) {
  $s = docker inspect --format='{{.State.Health.Status}}' disasterguard-postgres 2>$null
  if ($s -eq 'healthy') { $ready = $true; break }
  Write-Host ("waiting {0}s status={1}" -f $i, $s)
  Start-Sleep -Seconds 2
}
if ($ready) { Write-Host '[OK] healthy' } else { Write-Host '[FAIL] not healthy' }

Write-Host ''
Write-Host '=== Test auth ==='
docker exec disasterguard-postgres bash -c "PGPASSWORD=disasterguard123 psql -h 127.0.0.1 -U disasterguard -d disasterguard -c 'SELECT current_user, current_database();'" 2>&1

Write-Host ''
Write-Host '=== List roles ==='
docker exec disasterguard-postgres bash -c "PGPASSWORD=disasterguard123 psql -h 127.0.0.1 -U disasterguard -d disasterguard -c '\\du'" 2>&1
