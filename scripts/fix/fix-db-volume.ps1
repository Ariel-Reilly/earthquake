# fix-db-volume.ps1 — 重建 Postgres 数据卷解决 schema 漂移问题
# 适用：disasterguard-postgres 容器里 ai_comprehensive_score 等表缺少 schema.sql 中新增的列
#       （典型表现：spring.sql.init 报 column "disaster_type" does not exist）
# 用法：在项目根目录以 PowerShell 运行  .\scripts\fix-db-volume.ps1
# ⚠️ 会删除 postgres_data 卷，所有本地 Postgres 数据将丢失。
#    其他容器（redis/minio/emqx/tdengine）的卷不受影响。

$ErrorActionPreference = 'Stop'

Write-Host '=== Rebuild disasterguard-postgres data volume ===' -ForegroundColor Cyan

# 1. 确认 Docker 在跑
try {
  docker info 2>&1 | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'docker not responding' }
} catch {
  Write-Host '[X] Docker 引擎不可用，请先启动 Docker Desktop 后重试。' -ForegroundColor Red
  exit 1
}

# 2. 只停掉 postgres 容器（不影响 redis/minio/emqx/tdengine）
Write-Host '[1/4] Stopping disasterguard-postgres container...' -ForegroundColor Yellow
docker compose stop postgres 2>&1 | Out-Null

# 3. 删除容器 + 数据卷
Write-Host '[2/4] Removing disasterguard-postgres container and volume...' -ForegroundColor Yellow
docker compose rm -f postgres 2>&1 | Out-Null
$volNames = docker volume ls --format '{{.Name}}' 2>$null | Select-String -Pattern 'postgres_data$'
if ($volNames) {
  foreach ($v in $volNames) {
    $vname = $v.ToString().Trim()
    docker volume rm $vname 2>&1 | Out-Null
    Write-Host "      removed volume $vname" -ForegroundColor DarkGray
  }
} else {
  Write-Host '      (no postgres_data volume found, skipping)' -ForegroundColor DarkGray
}

# 4. 重新启动基础设施
Write-Host '[3/4] Starting postgres (will re-run docker-entrypoint-initdb.d + spring.sql.init)...' -ForegroundColor Yellow
docker compose up -d postgres 2>&1 | Out-Null

# 5. 等待就绪
Write-Host '[4/4] Waiting for postgres to be healthy...' -ForegroundColor Yellow
$ready = $false
for ($i=1; $i -le 30; $i++) {
  $state = (docker inspect --format '{{.State.Health.Status}}' disasterguard-postgres 2>$null)
  if ($state -eq 'healthy') { $ready = $true; break }
  Start-Sleep -Seconds 2
}
if ($ready) {
  Write-Host '  [OK] disasterguard-postgres is healthy.' -ForegroundColor Green
} else {
  Write-Host '  [!] disasterguard-postgres 未进入 healthy，超时。继续后续步骤时请留意日志：docker compose logs postgres' -ForegroundColor Yellow
}

Write-Host ''
Write-Host '下一步：' -ForegroundColor Cyan
Write-Host '  .\start-dev.ps1 -Backend' -ForegroundColor Green
