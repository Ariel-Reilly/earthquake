# ScriptUtils 把 schema.sql 切成多语句执行；之前那次失败在第 15 句，
# 但前面 1-14 句已经落库 (sys_role / region / sys_user / sys_menu / spatial_ref_sys)，
# 第 15 句之后都未执行。这次重跑 schema.sql 应该能完成。
# 先看一下当前 schema 内容，方便后续对比。
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

Write-Host '--- tables in public ---'
& 'E:\PostgreSQL\18\bin\psql.exe' -h 127.0.0.1 -p 15432 -U disasterguard -d disasterguard -c "\dt public.*" -w 2>&1
Write-Host '--- views in public ---'
& 'E:\PostgreSQL\18\bin\psql.exe' -h 127.0.0.1 -p 15432 -U disasterguard -d disasterguard -c "SELECT table_name, table_type FROM information_schema.tables WHERE table_schema='public' ORDER BY table_type, table_name;" -w 2>&1
