# run-backend.ps1 — 本地开发启动后端（Windows PowerShell）
# 自动设置 Docker 非标准端口映射环境变量，避免每次手动输入
# 前置条件：docker compose up -d postgres redis minio emqx tdengine 已运行
# 用法：在项目根目录执行  .\run-backend.ps1

param(
  [Alias("Profile")]
  [string]$SpringProfile = "default",   # Spring profile: default | docker | dev | prod
  [string]$JwtSecret = "geosentry-dev-secret-key-at-least-32-chars-long-xyz",
  [switch]$SkipTest,
  [switch]$Debug
)

Write-Host "=== GeoSentry Backend Local Runner ===" -ForegroundColor Cyan

# ---- 辅助函数：检测端口是否在监听 ----
function Test-PortListening([int]$Port) {
  $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
  return $null -ne $conn
}

# ---- 0. 检查 Docker 后端容器，如果在跑就停掉（释放 8080 端口） ----
if (Test-PortListening 8080) {
  $conn8080 = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
  $procId = $conn8080.OwningProcess
  $proc = Get-Process -Id $procId -ErrorAction SilentlyContinue

  if ($proc -and $proc.ProcessName -match "docker|wslrelay|com.docker") {
    Write-Host "  [!] Port 8080 is held by Docker ($($proc.ProcessName)). Stopping geosentry-backend container..." -ForegroundColor Yellow
    docker compose stop backend 2>&1 | Out-Null
    Start-Sleep -Seconds 3

    if (Test-PortListening 8080) {
      Write-Host "  [!] Port 8080 still in use after docker stop. Checking for local Java process..." -ForegroundColor Yellow
      $javaConn = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
      $javaProc = Get-Process -Id $javaConn.OwningProcess -ErrorAction SilentlyContinue
      if ($javaProc -and $javaProc.ProcessName -eq "java") {
        Write-Host "  [!] Killing stale Java PID=$($javaProc.Id)" -ForegroundColor Yellow
        Stop-Process -Id $javaProc.Id -Force
        Start-Sleep -Seconds 1
      }
    }
    if (-not (Test-PortListening 8080)) {
      Write-Host "  [OK] Port 8080 is now free" -ForegroundColor Green
    } else {
      Write-Host "  [!] Port 8080 is STILL in use. Please manually stop the process." -ForegroundColor Red
      exit 1
    }
  } elseif ($proc -and $proc.ProcessName -eq "java") {
    Write-Host "  [!] Port 8080 is held by local Java PID=$procId. Killing it..." -ForegroundColor Yellow
    Stop-Process -Id $procId -Force
    Start-Sleep -Seconds 1
  } else {
    Write-Host "  [!] Port 8080 is held by PID=$procId ($($proc.ProcessName)). Cannot auto-resolve." -ForegroundColor Red
    exit 1
  }
}

# ---- 1. 检查 Docker 基础设施端口 ----
# 用端口检测替代 docker compose ps，更可靠（不依赖 docker 命令在 PATH 中）
$infraPorts = @{
  15432 = "Postgres"
  16379 = "Redis"
  19000 = "MinIO"
}
$infraOk = $true
foreach ($p in $infraPorts.Keys) {
  if (Test-PortListening $p) {
    Write-Host "  [OK] $($infraPorts[$p]) on port $p" -ForegroundColor Green
  } else {
    Write-Host "  [X] $($infraPorts[$p]) on port $p is NOT listening" -ForegroundColor Red
    Write-Host "      Start it: docker compose up -d $($infraPorts[$p].ToLower())" -ForegroundColor Gray
    $infraOk = $false
  }
}

if (-not $infraOk) {
  Write-Host "  [!] Infrastructure not ready, aborting." -ForegroundColor Red
  Write-Host "      Run: docker compose up -d postgres redis minio emqx tdengine" -ForegroundColor Gray
  exit 1
}

# ---- 2. 设置环境变量 ----
$env:SPRING_PROFILES_ACTIVE = $SpringProfile
$env:POSTGRES_PORT         = "15432"
$env:REDIS_PORT            = "16379"
$env:REDIS_HOST            = "127.0.0.1"
$env:REDIS_PASSWORD        = "redis123"
$env:MINIO_ENDPOINT        = "http://127.0.0.1:19000"
$env:JWT_SECRET            = $JwtSecret
$env:DEV_USERS_ENABLED     = "true"
$env:DEVICE_AUTH_STRICT    = "false"
$env:TDENGINE_ENABLED      = "false"
$env:EMQX_ENABLED          = "false"
$env:AI_ENABLED            = "false"

Write-Host "  [env] POSTGRES_PORT=15432  REDIS_PORT=16379  MINIO=19000" -ForegroundColor DarkGray
Write-Host "  [env] JWT_SECRET=$($JwtSecret.Substring(0,8))...  PROFILE=$SpringProfile" -ForegroundColor DarkGray

# ---- 3. 启动 ----
$mvnArgs = @("-DskipTests")
if ($Debug) { $mvnArgs += "-Dspring-boot.run.arguments=--debug" }

Write-Host "`n=== Starting Spring Boot... ===" -ForegroundColor Cyan
Write-Host "  URL: http://localhost:8080/api" -ForegroundColor Gray
Write-Host "  Swagger: http://localhost:8080/swagger-ui.html" -ForegroundColor Gray
Write-Host ""

Push-Location "$PSScriptRoot\backend"
try {
  mvn @mvnArgs spring-boot:run
}
finally {
  Pop-Location
}
