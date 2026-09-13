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
#   .\start-dev.ps1 -Status          # 健康度速览（端口 / Docker / Git）
#   .\start-dev.ps1 -Doctor          # 诊断常见问题（端口占用 / Docker / 依赖）
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

  [Parameter(ParameterSetName = "Status")]
  [switch]$Status,

  [Parameter(ParameterSetName = "Doctor")]
  [switch]$Doctor,

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

# ─────────────────────────────────────────────
# F. 健康度速览（端口 / Docker / Git）
# ─────────────────────────────────────────────
function Invoke-Status {
  Write-Step "健康度速览"

  $checks = @(
    @{ Port = 15432; Name = "Postgres"  },
    @{ Port = 16379; Name = "Redis"     },
    @{ Port = 19000; Name = "MinIO API" },
    @{ Port = 19001; Name = "MinIO UI"  },
    @{ Port = 11883; Name = "EMQX MQTT" },
    @{ Port = 28083; Name = "EMQX UI"   },
    @{ Port = 16030; Name = "TDengine TCP" },
    @{ Port = 16041; Name = "TDengine HTTP" },
    @{ Port = 5173; Name = "Frontend (Vite)" },
    @{ Port = 8080; Name = "Backend (Spring Boot)" },
    @{ Port = 8001; Name = "AI Service (FastAPI)" }
  )

  foreach ($c in $checks) {
    if (Test-PortListening $c.Port) {
      $proc = Get-PortOwner $c.Port
      $who = if ($proc) { " ($($proc.ProcessName))" } else { "" }
      Write-Ok ("{0,-22} port {1,-5} OK{2}" -f $c.Name, $c.Port, $who)
    } else {
      Write-Err ("{0,-22} port {1,-5} DOWN" -f $c.Name, $c.Port)
    }
  }

  # Docker 状态
  Write-Host ""
  Write-Info "Docker 状态："
  try {
    $dockerInfo = docker info 2>&1 | Out-String
    if ($LASTEXITCODE -eq 0) {
      Write-Ok "Docker Desktop 在线"
      $containers = docker ps --format "{{.Names}}" 2>$null
      $geosentry = $containers | Where-Object { $_ -like "geosentry-*" -or $_ -like "*geosentry*" }
      if ($geosentry) {
        Write-Info "geosentry 容器：$($geosentry -join ', ')"
      } else {
        Write-Warn "未发现 geosentry-* 容器"
      }
    } else {
      Write-Err "Docker Desktop 未运行"
    }
  } catch {
    Write-Err "Docker 命令不可用：$_"
  }

  # Git 状态
  Write-Host ""
  Write-Info "Git 状态："
  Push-Location $PROJECT_ROOT
  try {
    $branch = git rev-parse --abbrev-ref HEAD 2>$null
    $ahead  = git rev-list --count @{u}..HEAD 2>$null
    $behind = git rev-list --count HEAD..@{u} 2>$null
    if ($branch) {
      Write-Ok "当前分支：$branch"
      if ($ahead -gt 0)  { Write-Warn "ahead  $ahead 个提交未推送" }
      if ($behind -gt 0) { Write-Warn "behind $behind 个远端提交未拉取" }
      if ($ahead -eq 0 -and $behind -eq 0) { Write-Ok "与 origin/$branch 同步" }
    } else {
      Write-Warn "Git 仓库未初始化或异常"
    }
  } finally {
    Pop-Location
  }
}

# ─────────────────────────────────────────────
# G. Doctor — 诊断常见问题
# ─────────────────────────────────────────────
function Invoke-Doctor {
  Write-Step "Doctor — 诊断常见问题"

  # 1. Docker
  try {
    docker info 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) {
      Write-Ok "Docker Desktop 可用"
    } else {
      Write-Err "Docker Desktop 未启动 —— 后端 / AI 服务无法对外提供依赖"
    }
  } catch {
    Write-Err "Docker 命令未找到 —— 请先安装 Docker Desktop 并加入 PATH"
  }

  # 2. Node.js
  try {
    $nodeVer = node --version 2>$null
    if ($nodeVer) {
      $major = [int]($nodeVer -replace 'v(\d+)\..*', '$1')
      if ($major -ge 18) {
        Write-Ok "Node.js $nodeVer (>=18)"
      } else {
        Write-Err "Node.js $nodeVer (<18) —— 请升级到 18+"
      }
    } else {
      Write-Err "Node.js 未安装"
    }
  } catch {
    Write-Err "Node.js 未安装"
  }

  # 3. Java
  try {
    $javaVer = java -version 2>&1 | Select-Object -First 1
    if ($javaVer -match '"(\d+)\.') {
      $major = [int]$Matches[1]
      if ($major -ge 17) {
        Write-Ok "Java $javaVer (>=17)"
      } else {
        Write-Err "Java $javaVer (<17) —— 后端需要 JDK 17+"
      }
    } else {
      Write-Warn "无法解析 Java 版本：$javaVer"
    }
  } catch {
    Write-Err "Java 未安装"
  }

  # 4. Python
  try {
    $pyVer = python --version 2>&1
    if ($pyVer -match 'Python (\d+)\.(\d+)') {
      $major = [int]$Matches[1]; $minor = [int]$Matches[2]
      if ($major -ge 3 -and $minor -ge 10) {
        Write-Ok "$pyVer (>=3.10)"
      } else {
        Write-Warn "$pyVer —— AI 服务建议 3.10+"
      }
    } else {
      Write-Warn "Python 版本解析失败：$pyVer"
    }
  } catch {
    Write-Err "Python 未安装"
  }

  # 5. Maven
  try {
    $mvnVer = mvn --version 2>&1 | Select-Object -First 1
    Write-Ok "$mvnVer"
  } catch {
    Write-Err "Maven 未安装 —— 后端构建需要"
  }

  # 6. 端口冲突快速检查
  Write-Host ""
  Write-Info "关键端口占用："
  $critical = @(5173, 8080, 8001, 15432, 16379, 19000)
  foreach ($p in $critical) {
    if (Test-PortListening $p) {
      $proc = Get-PortOwner $p
      Write-Warn "port $p → $($proc.ProcessName) (PID=$($proc.Id))"
    } else {
      Write-Ok "port $p 空闲"
    }
  }

  Write-Host ""
  Write-Info "如需修复常见问题，请参考 docs/DEVELOPER.md §15 与 §28"
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
} elseif ($Status) {
  Invoke-Status
} elseif ($Doctor) {
  Invoke-Doctor
} else {
  # 默认启动前端
  Invoke-Frontend
}
