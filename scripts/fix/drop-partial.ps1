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
$env:PGPASSWORD = $env:POSTGRES_PASSWORD
if ([string]::IsNullOrEmpty($env:PGPASSWORD)) { $env:PGPASSWORD = 'disasterguard' }

$sql = @"
DROP TABLE IF EXISTS _x CASCADE;
DROP VIEW IF EXISTS hazard_point CASCADE;
DROP TABLE IF EXISTS hazard_point CASCADE;
"@
& 'E:\PostgreSQL\18\bin\psql.exe' -h 127.0.0.1 -p 15432 -U disasterguard -d disasterguard -c $sql -w
Write-Host ('exit: ' + $LASTEXITCODE)
