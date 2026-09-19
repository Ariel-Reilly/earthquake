$ErrorActionPreference = 'Stop'

Write-Host '=== 1. Probe Spring defaults ==='
Write-Host ''
Write-Host 'application.yml default password: disasterguard123'
Write-Host 'docker-compose default password:   disasterguard123'
Write-Host ''

Write-Host '=== 2. Try logging into Postgres with the default password ==='
try {
  $env:PGPASSWORD = 'disasterguard123'
  $result = & psql -h 127.0.0.1 -p 15432 -U disasterguard -d disasterguard -c 'SELECT current_user, version();' 2>&1
  if ($LASTEXITCODE -eq 0) {
    Write-Host '[OK] psql auth works with disasterguard123'
    $result | ForEach-Object { Write-Host $_ }
  } else {
    Write-Host '[FAIL] psql auth FAILED with disasterguard123'
    $result | ForEach-Object { Write-Host $_ }
  }
} catch {
  Write-Host "[FAIL] psql not available or error: $($_.Exception.Message)"
}

Write-Host ''
Write-Host '=== 3. Inspect users and their password hashes ==='
try {
  $env:PGPASSWORD = 'disasterguard123'
  $rows = & psql -h 127.0.0.1 -p 15432 -U disasterguard -d postgres -A -t -c "SELECT usename, passwd IS NULL AS no_pw FROM pg_shadow ORDER BY usename;" 2>&1
  $rows | ForEach-Object { Write-Host $_ }
} catch {
  Write-Host "[FAIL] $_"
}

Write-Host ''
Write-Host '=== 4. Check pg_hba.conf auth method ==='
try {
  $env:PGPASSWORD = 'disasterguard123'
  & psql -h 127.0.0.1 -p 15432 -U disasterguard -d postgres -A -t -c "SHOW hba_file;" 2>&1 | ForEach-Object { Write-Host $_ }
} catch {}

Write-Host ''
Write-Host '=== 5. Check if a stray .env file overrides the password ==='
Get-ChildItem -Path 'D:\earthquake' -Filter .env -Force -ErrorAction SilentlyContinue | ForEach-Object {
  Write-Host ('FOUND .env at ' + $_.FullName)
  Get-Content $_.FullName | Where-Object { $_ -match 'POSTGRES' } | ForEach-Object { Write-Host ('  ' + $_) }
}
