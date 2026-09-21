$ErrorActionPreference = 'Stop'

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

Write-Host '=== 1. Probe Spring defaults ==='
Write-Host ''
Write-Host ('Postgres password from .env (or default): {0}' -f $pgPassword)

Write-Host '=== 2. Try logging into Postgres with the password from .env ==='
try {
  $env:PGPASSWORD = $pgPassword
  $result = & psql -h 127.0.0.1 -p 15432 -U disasterguard -d disasterguard -c 'SELECT current_user, version();' 2>&1
  if ($LASTEXITCODE -eq 0) {
    Write-Host '[OK] psql auth works'
    $result | ForEach-Object { Write-Host $_ }
  } else {
    Write-Host '[FAIL] psql auth FAILED'
    $result | ForEach-Object { Write-Host $_ }
  }
} catch {
  Write-Host "[FAIL] psql not available or error: $($_.Exception.Message)"
}

Write-Host ''
Write-Host '=== 3. Inspect users and their password hashes ==='
try {
  $env:PGPASSWORD = $pgPassword
  $rows = & psql -h 127.0.0.1 -p 15432 -U disasterguard -d postgres -A -t -c "SELECT usename, passwd IS NULL AS no_pw FROM pg_shadow ORDER BY usename;" 2>&1
  $rows | ForEach-Object { Write-Host $_ }
} catch {
  Write-Host "[FAIL] $_"
}

Write-Host ''
Write-Host '=== 4. Check pg_hba.conf auth method ==='
try {
  $env:PGPASSWORD = $pgPassword
  & psql -h 127.0.0.1 -p 15432 -U disasterguard -d postgres -A -t -c "SHOW hba_file;" 2>&1 | ForEach-Object { Write-Host $_ }
} catch {}

Write-Host ''
Write-Host '=== 5. Check if a stray .env file overrides the password ==='
Get-ChildItem -Path 'D:\DisasterGuard' -Filter .env -Force -ErrorAction SilentlyContinue | ForEach-Object {
  Write-Host ('FOUND .env at ' + $_.FullName)
  Get-Content $_.FullName | Where-Object { $_ -match 'POSTGRES' } | ForEach-Object { Write-Host ('  ' + $_) }
}
