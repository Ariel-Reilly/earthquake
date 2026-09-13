# start-dev.ps1 — 地脉智巡一键全栈启动（Windows PowerShell）
#
# 用法：
#   .\start-dev.ps1                  # 默认：只启动前端（后端需另开窗口）
#   .\start-dev.ps1 -All             # 后端 + 前端 + AI 服务（推荐在 PowerShell 7+）
#   .\start-dev.ps1 -Backend         # 仅启动后端
#   .\start-dev.ps1 -Frontend        # 仅启动前端
#   .\start-dev.ps1 -Ai              # 仅启动 AI 微服务
#   .\start-dev.ps1 -Infra -Up       # 启动 Docker 基础设施
#   .\start-dev.ps1 -Infra -Down     # 停止 Docker 基础设施
#   .\start-dev.ps1 -Infra -Reset    # 销毁并重建基础设施（慎用）
#
# 依赖：Docker Desktop / Node.js 18+ / JDK 17+ / Python 3.10+
#
# 前端默认端口：5173
# 后端默认端口：8080
# AI 服务默认端口：8001

[CmdletBinding(DefaultParameterSetName = "Frontend")]
param(
  [Parameter(ParameterSetName = "All")]
  [switch]$All,

  [Parameter(ParameterSetName = "Backend")]
  [switch]$Backend,

  [Parameter(ParameterSetName = "Frontend")]
  [switch]$Frontend,

  [Parameter(ParameterSetName = "Ai")]
  [switch]$Ai,

  [Parameter(ParameterSetName = "Infra")]
  [switch]$Infra,

  [Parameter(ParameterSetName = "Infra")]
  [switch]$Up,

  [Parameter(ParameterSetName = "Infra")]
  [switch]$Down,

  [Parameter(ParameterSetName = "Infra")]
  [switch]$Reset,

  [string]$SpringProfile = "default",
  [string]$JwtSecret = "geosentry-dev-secret-key-at-least-32-chars-long-xyz",
  [switch]$SkipTest,
  [switch]$Debug
)

$ErrorActionPreference = "Continue"
$PROJECT_ROOT = $PSScriptRoot

# ─── ANSI 颜色（兼容 Windows Terminal / PowerShell 7，旧版会 fallback） ───
function Write-Step($msg)  { Write-Host "`n▶ $msg" -ForegroundColor Cyan }
function Write-Ok($msg)    { Write-Host "  [OK] $msg" -ForegroundColor Green }
function Write-Warn($msg)  { Write-Host "  [!] $msg" -ForegroundColor Yellow }
function Write-Err($msg)   { Write-Host "  [X] $msg" -ForegroundColor Red }
function Write-Info($msg)  { Write-Host "      $msg" -ForegroundColor DarkGray }

# ─── 端口检测 ───
function Test-PortListening([int]$Port) {
  $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
  return $null -ne $conn
}

function Get-PortOwner([int]$Port) {
  $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
  if (-not $conn) { return $null }
  $proc = Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue
  return $proc
}

# ─── 强制释放端口（必要时杀进程） ───
function Ensure-PortFree([int]$Port, [string]$Name) {
  if (-not (Test-PortListening $Port)) { return }
  $proc = Get-PortOwner $Port
  if ($proc) {
    Write-Warn "Port $Port held by $($proc.ProcessName) (PID=$($proc.Id)). Killing..."
    try {
      Stop-Process -Id $proc.Id -Force -ErrorAction Stop
      Start-Sleep -Seconds 1
      Write-Ok "Port $Port released"
    } catch {
      Write-Err "Cannot kill PID=$($proc.Id): $_"
    }
  }
}

function Ensure-DockerStopped([int]$Port, [string]$ContainerName) {
  $proc = Get-PortOwner $Port
  if ($proc -and $proc.ProcessName -match "docker|wslrelay|com.docker") {
    Write-Warn "Port $Port held by Docker. Stopping container $ContainerName..."
    docker compose stop $ContainerName 2>&1 | Out-Null
    Start-Sleep -Seconds 3
  }
}

# ─────────────────────────────────────────────
# A. 基础设施（Docker compose）
# ─────────────────────────────────────────────
function Invoke-Infrastructure {
  if ($Reset) {
    Write-Step "销毁并重建基础设施..."
    Push-Location $PROJECT_ROOT
    try {
      docker compose down -v
      docker compose up -d postgres redis minio emqx tdengine
    } finally {
      Pop-Location
    }
  } elseif ($Down) {
    Write-Step "停止基础设施..."
    Push-Location $PROJECT_ROOT
    try { docker compose down } finally { Pop-Location }
    return
  } elseif ($Up) {
    Write-Step "启动基础设施..."
  } else {
    Write-Err "请指定 -Up / -Down / -Reset"
    return
  }

  Push-Location $PROJECT_ROOT
  try {
    docker compose up -d postgres redis minio emqx tdengine
  } finally {
    Pop-Location
  }

  # 等待端口就绪
  $infraPorts = @(15432, 16379, 19000, 1883, 16041)
  $infraNames = @("Postgres", "Redis", "MinIO", "EMQX", "TDengine")
  Write-Info "等待基础设施端口就绪..."
  for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 2
    $ready = ($infraPorts | ForEach-Object { Test-PortListening $_ } | Where-Object { $_ -eq $true }).Count
    Write-Host "`r      就绪 $ready/$($infraPorts.Count)" -NoNewline -ForegroundColor DarkGray
    if ($ready -eq $infraPorts.Count) {
      Write-Host ""
      Write-Ok "所有基础设施已就绪"
      return
    }
  }
  Write-Host ""
  Write-Warn "部分端口未就绪，请稍后检查 docker compose ps"
}

# ─────────────────────────────────────────────
# B. 后端（Spring Boot + Maven）
# ─────────────────────────────────────────────
function Invoke-Backend {
  Write-Step "启动后端 (Spring Boot)"

  Ensure-DockerStopped 8080 "backend"
  Ensure-PortFree 8080 "Backend"

  # 检查基础设施
  $infraPorts = @(15432, 16379, 19000)
  $infraNames = @("Postgres", "Redis", "MinIO")
  $infraOk = $true
  for ($i = 0; $i -lt $infraPorts.Count; $i++) {
    if (Test-PortListening $infraPorts[$i]) {
      Write-Ok "$($infraNames[$i]) on $($infraPorts[$i])"
    } else {
      Write-Err "$($infraNames[$i]) on $($infraPorts[$i]) NOT listening"
      Write-Info "启动：docker compose up -d $($infraNames[$i].ToLower())"
      $infraOk = $false
    }
  }
  if (-not $infraOk) {
    Write-Err "基础设施未就绪。运行: .\start-dev.ps1 -Infra -Up"
    return
  }

  # 环境变量
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

  Write-Info "POSTGRES_PORT=15432 REDIS_PORT=16379 MINIO=19000 PROFILE=$SpringProfile"

  Push-Location "$PROJECT_ROOT\backend"
  try {
    $mvnArgs = @("spring-boot:run", "-DskipTests=$SkipTest")
    if ($Debug) { $mvnArgs += "-Dspring-boot.run.jvmArguments=-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=5005" }
    mvn @mvnArgs
  } finally {
    Pop-Location
  }
}

# ─────────────────────────────────────────────
# C. 前端（Vite）
# ─────────────────────────────────────────────
function Invoke-Frontend {
  Write-Step "启动前端 (Vite dev server)"

  Ensure-DockerStopped 5173 "frontend"
  Ensure-PortFree 5173 "Frontend"

  if (Test-PortListening 8080) {
    Write-Ok "Backend reachable on 8080"
  } else {
    Write-Warn "Backend on 8080 NOT running. Run: .\start-dev.ps1 -Backend"
  }

  $env:VITE_USE_BACKEND  = "true"
  $env:VITE_API_BASE_URL = "http://localhost:8080/api"

  Push-Location $PROJECT_ROOT
  try {
    if (-not (Test-Path "node_modules")) {
      Write-Info "首次运行，安装 npm 依赖..."
      npm install
    }
    npm run dev
  } finally {
    Pop-Location
  }
}

# ─────────────────────────────────────────────
# D. AI 服务（FastAPI / Uvicorn）
# ─────────────────────────────────────────────
function Invoke-Ai {
  Write-Step "启动 AI 微服务 (FastAPI)"

  Ensure-PortFree 8001 "AI Service"

  Push-Location "$PROJECT_ROOT\ai-service"
  try {
    if (-not (Test-Path ".venv")) {
      Write-Info "首次运行，创建虚拟环境..."
      python -m venv .venv
    }
    & ".\.venv\Scripts\Activate.ps1"
    Write-Info "安装依赖..."
    pip install -q -r requirements.txt 2>&1 | Out-Null
    Write-Info "启动 Uvicorn on 8001..."
    uvicorn app.main:app --host 0.0.0.0 --port 8001 --reload
  } finally {
    Pop-Location
  }
}

# ─────────────────────────────────────────────
# E. 全栈模式（按顺序后台启动）
# ─────────────────────────────────────────────
function Invoke-All {
  Write-Step "一键全栈启动"

  # 1. 基础设施
  if (-not (Test-PortListening 15432)) {
    Invoke-Infrastructure
  } else {
    Write-Ok "基础设施已在运行"
  }

  # 2. 提示用户开新窗口
  Write-Host ""
  Write-Host "============================================================" -ForegroundColor Cyan
  Write-Host "  全栈模式需要 3 个终端，分别执行：" -ForegroundColor Yellow
  Write-Host ""
  Write-Host "    Terminal 1 (后端):    .\start-dev.ps1 -Backend" -ForegroundColor White
  Write-Host "    Terminal 2 (AI):      .\start-dev.ps1 -Ai" -ForegroundColor White
  Write-Host "    Terminal 3 (前端):    .\start-dev.ps1 -Frontend" -ForegroundColor White
  Write-Host ""
  Write-Host "  后续可访问：" -ForegroundColor Yellow
  Write-Host "    前端:    http://localhost:5173" -ForegroundColor White
  Write-Host "    后端:    http://localhost:8080/swagger-ui.html" -ForegroundColor White
  Write-Host "    AI 服务: http://localhost:8001/docs" -ForegroundColor White
  Write-Host "============================================================" -ForegroundColor Cyan
  Write-Host ""

  # 默认先启动前端
  Invoke-Frontend
}

# ─── 主调度 ───
Write-Host "============================================================" -ForegroundColor Magenta
Write-Host "  GeoSentry 地脉智巡 · 一键开发启动" -ForegroundColor Magenta
Write-Host "============================================================" -ForegroundColor Magenta

if ($All) {
  Invoke-All
} elseif ($Infra) {
  Invoke-Infrastructure
} elseif ($Backend) {
  Invoke-Backend
} elseif ($Ai) {
  Invoke-Ai
} else {
  # 默认启动前端
  Invoke-Frontend
}
