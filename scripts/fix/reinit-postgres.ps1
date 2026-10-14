$ErrorActionPreference = 'Stop'

# ⚠️ 确认提示：本脚本删除 Postgres 数据卷，数据不可恢复
$confirm = Read-Host "⚠️  警告：本脚本将删除 Postgres 数据卷（数据不可恢复）！确认执行？[y/N]"
if ($confirm -ne 'y' -and $confirm -ne 'Y') {
  Write-Host '已取消。'
  exit 0
}

# dry-run 模式（设置 DRY_RUN=1 跳过实际删除操作）
if ($env:DRY_RUN -eq '1') {
  Write-Host '[DRY-RUN] 以下操作将被跳过：'
  Write-Host '  docker compose stop postgres'
  Write-Host '  docker compose rm -f postgres'
  Write-Host '  docker volume rm disasterguard_postgres_data'
  exit 0
}

Write-Host '=== 1. Stop and remove postgres container + volume ==='
docker compose stop postgres 2>&1 | Out-Null
docker compose rm -f postgres 2>&1 | Out-Null
docker volume rm disasterguard_postgres_data 2>&1 | Out-Null

Write-Host ''
Write-Host '=== 2. Bring postgres back up (triggers initdb with compose defaults) ==='
Set-Location 'D:\Projects\DisasterGuard'
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
# Load .env for credential override (supports DRY_RUN)
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

Write-Host "=== 4. Verify auth works from container (password from .env or default) ==="
docker exec disasterguard-postgres bash -c "PGPASSWORD=$pgPassword psql -h 127.0.0.1 -U disasterguard -d disasterguard -c 'SELECT current_user, current_database();'" 2>&1

Write-Host ''
Write-Host '=== 5. Verify auth works from host (mimicking Spring Boot) ==='
docker exec disasterguard-postgres bash -c "PGPASSWORD=$pgPassword psql -h 127.0.0.1 -p 5432 -U disasterguard -d disasterguard -c 'SELECT 1;'" 2>&1
