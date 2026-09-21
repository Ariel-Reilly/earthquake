# Load .env for credential override
$envFile = Join-Path $PSScriptRoot '..\..\.env'
if (Test-Path $envFile) {
  Get-Content $envFile | ForEach-Object {
    if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
      $key = $Matches[1].Trim()
      $val = $Matches[2].Trim().Trim('"', "'")
      if (-not [string]::IsNullOrEmpty($key) -and [string]::IsNullOrEmpty([System.Environment]::GetEnvironmentVariable($key))) {
        [System.Environment]::SetEnvironmentVariable($key, $val, 'Process')
      }
    }
  }
}
$pgPassword = $env:POSTGRES_PASSWORD
if ([string]::IsNullOrEmpty($pgPassword)) { $pgPassword = 'disasterguard' }

docker exec disasterguard-postgres cat /etc/passwd
Write-Host '---'
docker exec disasterguard-postgres id
Write-Host '---'
docker exec disasterguard-postgres cat /var/lib/postgresql/data/PG_VERSION
Write-Host '---'
Write-Host '=== Test local socket as default user ==='
docker exec disasterguard-postgres psql -d disasterguard -c "SELECT current_user, current_database(), version();" 2>&1
Write-Host '---'
Write-Host ('=== Try to ALTER password (using .env password or default: {0}) ===' -f $pgPassword)
docker exec disasterguard-postgres psql -d disasterguard -c "ALTER USER disasterguard WITH PASSWORD '$pgPassword';" 2>&1
Write-Host '---'
Write-Host '=== Verify TCP auth now ==='
docker exec disasterguard-postgres bash -c "PGPASSWORD=$pgPassword psql -h 127.0.0.1 -p 5432 -U disasterguard -d disasterguard -c 'SELECT 1;'" 2>&1
