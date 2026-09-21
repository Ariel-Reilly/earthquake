# start-dev.ps1 — 地脉智巡一键开发启动（Windows PowerShell）
# ================================================================
#
# 用法：
#   .\start-dev.ps1                   默认：仅启动前端
#   .\start-dev.ps1 -All              后端 + 前端 + AI 服务（并行后台启动）
#   .\start-dev.ps1 -All -Wait        同上，但等全部就绪后报告
#   .\start-dev.ps1 -Backend          仅后端（前台）
#   .\start-dev.ps1 -Frontend         仅前端（前台）
#   .\start-dev.ps1 -Ai               仅 AI 微服务（前台）
#   .\start-dev.ps1 -Infra -Up        启动 Docker 基础设施
#   .\start-dev.ps1 -Infra -Down      停止 Docker 基础设施
#   .\start-dev.ps1 -Infra -Reset     销毁并重建基础设施（慎用）
#   .\start-dev.ps1 -Status           健康度速览
#   .\start-dev.ps1 -Doctor           诊断依赖问题
#   .\start-dev.ps1 -Stop             停止所有本地开发服务
#   .\start-dev.ps1 -Restart          重启所有本地开发服务
#
# 依赖：Docker Desktop / Node.js 18+ / JDK 17+ / Python 3.10+
#
# 前端默认端口：5173   后端：8080   AI 服务：8001

[CmdletBinding()]
param(
  [switch]$All,
  [switch]$Wait,               # -All -Wait: 等所有服务就绪后报告
  [switch]$Backend,
  [switch]$Frontend,
  [switch]$Ai,
  [switch]$Infra,
  [switch]$Up,
  [switch]$Down,
  [switch]$Reset,
  [switch]$Status,
  [switch]$Doctor,
  [switch]$Stop,
  [switch]$Restart,

  # ---- 后端参数 ----
  [ValidateSet("default", "dev", "docker", "prod")]
  [string]$SpringProfile = "default",
  [string]$JwtSecret = "",  # 空字符串表示从环境变量读取
  [switch]$SkipTest,           # 跳过单元测试（默认 true）
  [switch]$Jmx,                # 开启 JVM 调试端口 5005

  # ---- AI 服务参数 ----
  [switch]$AiNoReload          # 生产模式：不加 --reload 避免文件监听开销
)

$ErrorActionPreference = "Continue"
$PROJECT_ROOT = $PSScriptRoot

# ─── .env 加载（优先级：已存在的环境变量 > 项目根 .env > 本脚本默认值） ───
function Load-DotEnv {
  $envFile = Join-Path $PROJECT_ROOT ".env"
  if (-not (Test-Path $envFile)) { return }
  Get-Content $envFile | ForEach-Object {
    if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
      $key = $Matches[1].Trim()
      $val = $Matches[2].Trim().Trim('"', "'")
      if (-not [string]::IsNullOrEmpty($key) -and [string]::IsNullOrEmpty([System.Environment]::GetEnvironmentVariable($key))) {
        [System.Environment]::SetEnvironmentVariable($key, $val, "Process")
      }
    }
  }
}
Load-DotEnv

# ─── ANSI 颜色（兼容 Windows Terminal / PowerShell 7） ───
function Write-Step($msg) { Write-Host "`n▶ $msg" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "  [OK] $msg" -ForegroundColor Green }
function Write-Warn($msg) { Write-Host "  [!] $msg" -ForegroundColor Yellow }
function Write-Err($msg)  { Write-Host "  [X] $msg" -ForegroundColor Red }
function Write-Info($msg){ Write-Host "      $msg" -ForegroundColor DarkGray }

# ─── 端口工具 ───
function Test-PortListening([int]$Port) {
  $null -ne (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}

function Get-PortOwner([int]$Port) {
  $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
  if (-not $conn) { return $null }
  Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue
}

function Get-PortOwnerName([int]$Port) {
  $proc = Get-PortOwner $Port
  if (-not $proc) { return $null }
  "$($proc.ProcessName) (PID=$($proc.Id))"
}

function Free-Port([int]$Port, [string]$SvcName) {
  if (-not (Test-PortListening $Port)) { return $true }
  $owner = Get-PortOwnerName $Port
  Write-Warn "Port $Port 被 $owner 占用"
  # ⚠️ 确认提示：避免误杀无关进程
  $confirm = Read-Host "  强制释放端口 $Port（终止 $owner）？[y/N]"
  if ($confirm -ne 'y' -and $confirm -ne 'Y') {
    Write-Info "已取消，请手动处理端口 $Port 后重试"
    return $false
  }
  try {
    $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    Stop-Process -Id $conn.OwningProcess -Force -ErrorAction Stop
    Start-Sleep -Seconds 1
  } catch {
    Write-Err "终止进程失败：$_"
  }

  # 如果还有占用，先尝试 docker compose stop（常见：disasterguard-backend）
  $proc2 = Get-PortOwner $Port
  if ($proc2) {
    if ($proc2.ProcessName -match "docker|wslrelay|com.docker") {
      Write-Info "停止对应 Docker 容器..."
      docker compose stop backend frontend ai-service 2>&1 | Out-Null
      Start-Sleep -Seconds 2
    } else {
      try {
        Stop-Process -Id $proc2.Id -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 1
      } catch { }
    }
  }

  if (Test-PortListening $Port) {
    Write-Err "Port $Port 仍然被占用，请手动: Get-NetTCPConnection -LocalPort $Port | ForEach-Object { Stop-Process -Id `$_.OwningProcess -Force }"
    return $false
  }
  Write-Ok "Port $Port 已释放"
  return $true
}

function Stop-DockerContainerOnPort([int]$Port, [string]$ContainerName) {
  $proc = Get-PortOwner $Port
  if (-not $proc) { return }
  if ($proc.ProcessName -match "docker|wslrelay|com.docker") {
    Write-Info "停止 Docker 容器 $ContainerName..."
    docker compose stop $ContainerName 2>&1 | Out-Null
    Start-Sleep -Seconds 2
  }
}

# ─── Docker daemon 真正可用性检测（docker info 会报缓存数据，必须 docker ps 实测） ───
function Test-DockerDaemonUp {
  # 把错误吞进 try，1.5 秒超时
  try {
    $dockerOut = & docker ps 2>&1
    if ($LASTEXITCODE -eq 0 -and ($dockerOut | Out-String).Length -gt 0) {
      return $true
    }
  } catch { }
  return $false
}

# ─── 子进程是否还活着（轮询，单次检查） ───
function Test-ProcessAlive([int]$ProcessId) {
  if ($ProcessId -le 0) { return $false }
  $p = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
  return ($null -ne $p) -and ($p.HasExited -eq $false)
}

# ─── 等待一组端口就绪（带实时进度和进程存活检测） ───
# 返回：@{ Ports = @() ReadyPorts = @() DeadPids = @() Errors = @() }
function Wait-PortReadyDetailed([int[]]$Ports, [string[]]$Names, [int[]]$Pids, [string[]]$Logs, [int]$MaxSeconds = 120) {
  $result = @{ Ports = $Ports; Names = $Names; Pids = $Pids; Logs = $Logs; ReadyPorts = @(); DeadPids = @(); Errors = @(); TimedOut = $true }
  for ($i = 0; $i -lt $MaxSeconds; $i++) {
    $ready = 0
    $line = ""
    for ($j = 0; $j -lt $Ports.Count; $j++) {
      $port = $Ports[$j]; $name = $Names[$j]; $procId = $Pids[$j]; $log = $Logs[$j]
      if (Test-PortListening $port) {
        $ready++; $result.ReadyPorts += $port
        $line += "  [OK] $name :$port`n"
      } else {
        # 进程是否已死？
        if ($procId -gt 0 -and -not (Test-ProcessAlive $procId)) {
          $result.DeadPids += $procId
          # 读日志尾部，找错误信息
          $tail = ""
          if (Test-Path $log) {
            $errLines = Get-Content $log -Tail 15 -ErrorAction SilentlyContinue | Where-Object { $_ -match "error|Error|ERROR|failed|Failed|FAILED|exception|Exception" }
            if ($errLines) { $tail = " → " + ($errLines[-1] -replace '[\r\n]+', ' ') }
          }
          $line += "  [X] $name (PID=$procId 已退出)$tail`n"
        } else {
          $line += "  [..] $name :$port (PID=$procId 启动中...)`n"
        }
      }
    }
    Write-Host "`r      [$("$i".PadLeft(3))s] $ready/$($Ports.Count) 就绪  " -NoNewline -ForegroundColor DarkGray
    if ($ready -eq $Ports.Count) {
      Write-Host ""; $result.TimedOut = $false; break
    }
    if ($result.DeadPids.Count -gt 0) {
      Write-Host ""
      $result.DeadPids | Select-Object -Unique | ForEach-Object {
        $dp = $_
        $idx = $Pids.IndexOf($dp)
        $dn = if ($idx -ge 0) { $Names[$idx] } else { "PID=$dp" }
        $dl = if ($idx -ge 0) { $Logs[$idx] } else { "" }
        Write-Err "$dn (PID=$dp) 进程已退出"
        if ($dl -and (Test-Path $dl)) {
          $errLines = Get-Content $dl -Tail 20 -ErrorAction SilentlyContinue | Where-Object { $_ -match "error|Error|ERROR|failed|Failed|exception|Exception|cannot|Cannot" }
          if ($errLines) {
            $errLines | Select-Object -Last 5 | ForEach-Object { Write-Info $_ }
          } else {
            Write-Info "完整日志：$dl"
          }
        }
      }
      break
    }
    Start-Sleep -Seconds 1
  }
  if ($result.TimedOut) { Write-Host "" }
  return $result
}

# ─── 查找 log 文件路径 ───
function Get-ServiceLogPath([string]$Name) {
  $dir = Join-Path $env:TEMP "disasterguard-logs"
  if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
  Join-Path $dir "$Name-$PID.log"
}

# ─── 简单轮询等待（用于 Docker Infra，不需要 PID/日志检测） ───
function Wait-SimplePorts([int[]]$Ports, [int]$MaxSeconds = 60) {
  for ($i = 0; $i -lt $MaxSeconds; $i++) {
    $ready = ($Ports | Where-Object { Test-PortListening $_ }).Count
    Write-Host "`r      [$("$i".PadLeft(3))s] $ready/$($Ports.Count) 就绪  " -NoNewline -ForegroundColor DarkGray
    if ($ready -eq $Ports.Count) { Write-Host ""; return $true }
    Start-Sleep -Seconds 1
  }
  Write-Host ""
  return $false
}

# ─────────────────────────────────────────────
# A. 基础设施
# ─────────────────────────────────────────────
function Invoke-Infra {
  if ($Reset) {
    Write-Step "销毁并重建基础设施..."
    Push-Location $PROJECT_ROOT
    try {
      docker compose down -v 2>&1 | Out-Null
      docker compose up -d postgres redis minio emqx tdengine
    } finally { Pop-Location }
    $ok = Wait-SimplePorts @(15432, 16379, 19000, 11883, 16041) 60
    if ($ok) { Write-Ok "所有基础设施已就绪" }
    else { Write-Warn "部分端口未就绪，请检查 docker compose ps" }
    return
  }

  if ($Down) {
    Write-Step "停止基础设施..."
    Push-Location $PROJECT_ROOT
    try { docker compose down 2>&1 | Out-Null } finally { Pop-Location }
    Write-Ok "基础设施已停止"
    return
  }

  if ($Up) {
    Write-Step "启动基础设施..."
  } else {
    Write-Err "请指定 -Up / -Down / -Reset"
    return
  }

  Push-Location $PROJECT_ROOT
  try {
    docker compose up -d postgres redis minio emqx tdengine 2>&1 | Out-Null
  } finally { Pop-Location }

  $ok = Wait-SimplePorts @(15432, 16379, 19000, 11883, 16041) 60
  if ($ok) { Write-Ok "所有基础设施已就绪" }
  else { Write-Warn "部分端口未就绪，请检查 docker compose ps" }
}

# ─────────────────────────────────────────────
# B. 后端
# ─────────────────────────────────────────────
function Invoke-Backend {
  Write-Step "启动后端 (Spring Boot)"

  Stop-DockerContainerOnPort 8080 "backend"
  if (-not (Free-Port 8080 "Backend")) { return }

  # 检查基础设施
  $infra = @(
    @{ Port = 15432; Name = "Postgres" },
    @{ Port = 16379; Name = "Redis" },
    @{ Port = 19000; Name = "MinIO" }
  )
  $infraOk = $true
  foreach ($s in $infra) {
    if (Test-PortListening $s.Port) { Write-Ok "$($s.Name) $($s.Port)" }
    else { Write-Err "$($s.Name) $($s.Port) 未就绪"; $infraOk = $false }
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
  # REDIS_PASSWORD 从环境变量读取，Load-DotEnv 已从 .env 加载
  # 如果 .env 未设置则使用 .env 中的 CHANGE_ME 占位值（开发用）
  $env:REDIS_PASSWORD        = if ([string]::IsNullOrEmpty($env:REDIS_PASSWORD)) { "CHANGE_ME__dev_redis_password_2026" } else { $env:REDIS_PASSWORD }
  $env:MINIO_ENDPOINT        = "http://127.0.0.1:19000"
  # JWT_SECRET 从环境变量读取，不在脚本中硬编码默认值
  if (-not [string]::IsNullOrEmpty($JwtSecret)) {
    $env:JWT_SECRET = $JwtSecret
  } elseif ([string]::IsNullOrEmpty($env:JWT_SECRET)) {
    Write-Warn "JWT_SECRET 未设置，请通过环境变量或 .env 文件配置"
  }
  $env:DEV_USERS_ENABLED     = "true"
  $env:DEVICE_AUTH_STRICT    = "false"
  $env:TDENGINE_ENABLED      = "false"
  $env:EMQX_ENABLED          = "false"
  $env:AI_ENABLED            = "false"

  Write-Info "POSTGRES=15432  REDIS=16379  MINIO=19000  PROFILE=$SpringProfile"
  Write-Info "后端: http://localhost:8080/api"
  Write-Info "Swagger: http://localhost:8080/swagger-ui.html"

  # -Dspring-boot.run.arguments=--debug 会被 Spring Boot 当作启动参数
  $mvnOpts = @("spring-boot:run")
  if ($SkipTest -or (-not $PSBoundParameters.ContainsKey('SkipTest'))) {
    # Switch 默认为 false（$PSBoundParameters 检查更可靠）
  }
  $jvmArgs = @()
  if ($Jmx) {
    $jvmArgs = @("-Dspring-boot.run.jvmArguments=-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=5005")
    Write-Info "JVM 调试已开启：localhost:5005"
  } else {
    $jvmArgs = @()
  }

  Push-Location "$PROJECT_ROOT\backend"
  try {
    if ($jvmArgs.Count -gt 0) {
      mvn @mvnOpts @jvmArgs "-DskipTests"
    } else {
      mvn @mvnOpts "-DskipTests"
    }
  } finally { Pop-Location }
}

# ─────────────────────────────────────────────
# C. 前端
# ─────────────────────────────────────────────
function Invoke-Frontend {
  Write-Step "启动前端 (Vite)"

  Stop-DockerContainerOnPort 5173 "frontend"
  if (-not (Free-Port 5173 "Frontend")) { return }

  if (Test-PortListening 8080) { Write-Ok "后端 8080 可达" }
  else { Write-Warn "后端 8080 未运行（需单独启动或用 -All）" }

  $env:VITE_USE_BACKEND  = "true"
  $env:VITE_API_BASE_URL = "http://localhost:8080/api"
  $env:VITE_AI_SERVICE_BASE = "http://localhost:8001/api/ai"

  Write-Info "VITE_API_BASE_URL=http://localhost:8080/api"
  Write-Info "前端: http://localhost:5173/`n"

  Push-Location "$PROJECT_ROOT\frontend"
  try {
    if (-not (Test-Path "node_modules")) {
      Write-Info "首次运行，安装 npm 依赖..."
      npm install | Out-Null
    }
    # --strictPort：5173 被占时直接报错，不静默切换到其他端口
    npm run dev -- --port 5173 --strictPort
  } finally { Pop-Location }
}

# ─────────────────────────────────────────────
# D. AI 服务
# ─────────────────────────────────────────────
function Invoke-AiService {
  Write-Step "启动 AI 微服务 (FastAPI)"

  if (-not (Free-Port 8001 "AI Service")) { return }

  $vhome = Join-Path $PROJECT_ROOT "ai-service\.venv"
  $pyExe = Join-Path $vhome "Scripts\python.exe"
  $pipExe = Join-Path $vhome "Scripts\pip.exe"
  $uvicornExe = Join-Path $vhome "Scripts\uvicorn.exe"
  $reqFile = Join-Path $PROJECT_ROOT "ai-service\requirements.txt"

  # 创建虚拟环境（如不存在）
  if (-not (Test-Path $vhome)) {
    Write-Info "创建 Python 虚拟环境..."
    & python -m venv "$PROJECT_ROOT\ai-service\.venv" 2>&1 | Out-Null
  }

  # 安装依赖（只在文件更新后或首次时完整安装）
  $needsInstall = $false
  if (-not (Test-Path $pipExe)) {
    $needsInstall = $true
  } else {
    $vhash = if (Test-Path $reqFile) { (Get-FileHash $reqFile -Algorithm MD5).Hash } else { "" }
    $vhashFile = Join-Path $vhome ".req_hash"
    $ohash = if (Test-Path $vhashFile) { Get-Content $vhashFile -Raw } else { "" }
    if ($vhash -ne $ohash.Trim()) { $needsInstall = $true }
  }

  if ($needsInstall) {
    Write-Info "安装 AI 依赖（首次或 requirements.txt 有变化）..."
    & $pyExe -m pip install --upgrade pip 2>&1 | Out-Null
    & $pipExe install -q -r $reqFile 2>&1 | Out-Null
    if (Test-Path $reqFile) {
      $vhash = (Get-FileHash $reqFile -Algorithm MD5).Hash
      $vhash | Set-Content $vhashFile -NoNewline
    }
    Write-Ok "依赖安装完成"
  }

  # 启动 Uvicorn：用 `python -m uvicorn` 而不是直接调用 .venv\Scripts\uvicorn.exe
  # 原因：Windows 下某些版本的 *.exe 启动器会静默失败（无输出、无错误、退出码 0）
  $reloadArg = if ($AiNoReload) { "" } else { "--reload" }
  Write-Info "启动 Uvicorn on http://0.0.0.0:8001..."
  Write-Info "API 文档: http://localhost:8001/docs"
  & $pyExe -m uvicorn app.main:app --host 0.0.0.0 --port 8001 $reloadArg
}

# ─────────────────────────────────────────────
# E. 全栈并行启动（核心改进：预清理 + 真检测 + 实时监控）
# ─────────────────────────────────────────────
function Invoke-AllStack {
  Write-Step "一键全栈并行启动"
  $logDir = Join-Path $env:TEMP "disasterguard-logs"
  if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }
  Write-Info "日志目录: $logDir"

  # ─── 1. 预清理：杀干净目标端口上的残留进程 ───
  Write-Host ""
  Write-Info "[0/4] 预清理目标端口 8001/8080/5173 上的残留进程..."
  foreach ($p in @(8001, 8080, 5173)) {
    if (Test-PortListening $p) {
      Free-Port $p "Pre-cleanup" | Out-Null
    }
  }
  Write-Ok "端口预清理完成"

  # ─── 2. Docker daemon 真检测 ───
  Write-Info "[1/4] Docker daemon 检测..."
  $dockerUp = Test-DockerDaemonUp
  if ($dockerUp) {
    Write-Ok "Docker daemon 在线（docker ps 成功）"
    # 同时尝试启动 infra
    $infraLog = Join-Path $logDir "infra-$PID.log"
    Write-Info "启动基础设施后台任务..."
    $infraProc = Start-Process -FilePath "powershell.exe" `
      -ArgumentList "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
        "try { cd '$PROJECT_ROOT'; docker compose up -d postgres redis minio emqx tdengine 2>&1 | Out-File '$infraLog' -Append } catch { 'INFRA_FAIL: ' + `$_.Exception.Message | Out-File '$infraLog' -Append }" `
      -WindowStyle Hidden -PassThru
    Write-Info "Infra PID=$($infraProc.Id)  → $infraLog"
  } else {
    Write-Warn "Docker daemon 不可用（docker ps 失败）"
    Write-Info "  后端运行必须依赖 Postgres/Redis/MinIO，请检查："
    Write-Info "    1) Docker Desktop 是否已启动（系统托盘图标应为绿色）"
    Write-Info "    2) WSL2 / Hyper-V 后端是否正常"
    Write-Info "    3) 是否需要运行 'wsl --shutdown && Docker Desktop 重新打开'"
    $infraProc = $null
    $infraLog = "$logDir\infra-$PID.log"
    "Skipped: Docker daemon unavailable" | Out-File $infraLog
    # -All 模式下后端 / AI 必然连不上 Postgres (15432) / Redis (16379)，短路退出避免误导
    if ($All) {
      Write-Err "Docker daemon 不可用，无法启动 -All 全栈模式。"
      Write-Err "请先启动 Docker Desktop，然后重跑："
      Write-Info "    .\start-dev.ps1 -Infra -Up    # 启动 Postgres / Redis / MinIO / EMQX / TDengine"
      Write-Info "    .\start-dev.ps1 -All -Wait    # 再启动后端 / 前端 / AI"
      return
    }
  }

  # ─── 3. 启动后端 ───
  $beLog = Join-Path $logDir "backend-$PID.log"
  Write-Info "[2/4] 启动后端（Spring Boot）..."
  # 从环境变量读取 JWT_SECRET 和 REDIS_PASSWORD，不在脚本中硬编码
  $beJwtSecret = if ([string]::IsNullOrEmpty($env:JWT_SECRET)) { "''" } else { "'" + $env:JWT_SECRET + "'" }
  $beRedisPassword = if ([string]::IsNullOrEmpty($env:REDIS_PASSWORD)) { "'CHANGE_ME__dev_redis_password_2026'" } else { "'" + $env:REDIS_PASSWORD + "'" }
  $beScriptBlock = [ScriptBlock]::Create(@"
Set-Location '$PROJECT_ROOT\backend'
`$env:SPRING_PROFILES_ACTIVE='$SpringProfile'
`$env:POSTGRES_PORT='15432'
`$env:REDIS_PORT='16379'
`$env:REDIS_HOST='127.0.0.1'
`$env:REDIS_PASSWORD=$beRedisPassword
`$env:MINIO_ENDPOINT='http://127.0.0.1:19000'
`$env:JWT_SECRET=$beJwtSecret
`$env:DEV_USERS_ENABLED='true'
`$env:DEVICE_AUTH_STRICT='false'
`$env:TDENGINE_ENABLED='false'
`$env:EMQX_ENABLED='false'
`$env:AI_ENABLED='true'
mvn spring-boot:run -DskipTests 2>&1 | Out-File '$beLog' -Append
"@)
  $beProc = Start-Process -FilePath "powershell.exe" `
    -ArgumentList "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", $beScriptBlock `
    -WindowStyle Minimized -PassThru
  Write-Info "后端 PID=$($beProc.Id)  → $beLog"

  # ─── 4. 启动 AI 服务 ───
  $aiLog = Join-Path $logDir "ai-$PID.log"
  Write-Info "[3/4] 启动 AI 服务（FastAPI）..."
  $aiReload = if ($AiNoReload) { "" } else { "--reload" }
  $aiHomePath = "$PROJECT_ROOT\ai-service"
  # 极简版本：不嵌套 try/catch 防止伪错误；正常启动自然有 uvicorn 自身输出
  $aiScriptBlock = [ScriptBlock]::Create(@"
Set-Location '$aiHomePath'
if (-not (Test-Path '.venv\Scripts\python.exe')) {
  Write-Output 'Creating venv...' | Out-File '$aiLog' -Append
  python -m venv .venv 2>&1 | Out-Null
}
if (-not (Test-Path '.venv\Scripts\uvicorn.exe')) {
  Write-Output 'Installing requirements...' | Out-File '$aiLog' -Append
  .venv\Scripts\pip.exe install -q -r requirements.txt 2>&1 | Out-File '$aiLog' -Append
}
Write-Output ('=== AI Service starting at ' + (Get-Date -Format 'HH:mm:ss') + ' ===') | Out-File '$aiLog' -Append
# 使用 `python -m uvicorn`：.venv\Scripts\uvicorn.exe 在某些 Windows 环境下会静默退出
& .venv\Scripts\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8001 $aiReload 2>&1 | Out-File '$aiLog' -Append
"@)
  $aiProc = Start-Process -FilePath "powershell.exe" `
    -ArgumentList "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", $aiScriptBlock `
    -WindowStyle Minimized -PassThru
  Write-Info "AI PID=$($aiProc.Id)  → $aiLog"

  # ─── 5. 启动前端 ───
  $feLog = Join-Path $logDir "frontend-$PID.log"
  Write-Info "[4/4] 启动前端（Vite）..."
  $feScriptBlock = [ScriptBlock]::Create(@"
Set-Location '$PROJECT_ROOT\frontend'
if (-not (Test-Path 'node_modules')) { npm install 2>&1 | Out-Null }
`$env:VITE_USE_BACKEND='true'
`$env:VITE_API_BASE_URL='http://localhost:8080/api'
`$env:VITE_AI_SERVICE_BASE='http://localhost:8001/api/ai'
Write-Output ('=== Frontend starting at ' + (Get-Date -Format 'HH:mm:ss') + ' ===') | Out-File '$feLog' -Append
npm run dev -- --port 5173 --strictPort 2>&1 | Out-File '$feLog' -Append
"@)
  $feProc = Start-Process -FilePath "powershell.exe" `
    -ArgumentList "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", $feScriptBlock `
    -WindowStyle Minimized -PassThru
  Write-Info "Frontend PID=$($feProc.Id)  → $feLog"

  Write-Host ""
  Write-Host "============================================================" -ForegroundColor Cyan
  Write-Host "  4 个服务已在后台启动" -ForegroundColor Green
  Write-Host "    后端:  http://localhost:8080/api" -ForegroundColor White
  Write-Host "    前端:  http://localhost:5173/" -ForegroundColor White
  Write-Host "    AI:    http://localhost:8001/docs" -ForegroundColor White
  Write-Host "============================================================" -ForegroundColor Cyan

  # ─── 6. 等待就绪（带进程存活监控 + 错误提取） ───
  Write-Host ""
  $result = Wait-PortReadyDetailed `
    -Ports @(8080, 5173, 8001) `
    -Names @("Backend", "Frontend", "AI Service") `
    -Pids @($beProc.Id, $feProc.Id, $aiProc.Id) `
    -Logs @($beLog, $feLog, $aiLog) `
    -MaxSeconds 120

  if (-not $result.TimedOut -and $result.DeadPids.Count -eq 0) {
    Write-Host ""
    Write-Ok "✅ 全部就绪！"
    Write-Host "    前端:  http://localhost:5173/" -ForegroundColor Green
    Write-Host "    后端:  http://localhost:8080/api" -ForegroundColor Green
    Write-Host "    AI:    http://localhost:8001/docs" -ForegroundColor Green
  } else {
    Write-Host ""
    Write-Warn "有服务未就绪，请查看下方错误或日志："
    Write-Info "  Backend   → $beLog"
    Write-Info "  Frontend  → $feLog"
    Write-Info "  AI        → $aiLog"
    Write-Info "  Infra     → $infraLog"
  }
}

# ─────────────────────────────────────────────
# F. 停止所有服务
# ─────────────────────────────────────────────
function Invoke-Stop {
  Write-Step "停止所有开发服务..."

  # 先关后台进程（Java / node / python）
  $servers = @(
    @{ Name = "Java (Backend)";  Port = 8080 },
    @{ Name = "Node (Frontend)"; Port = 5173 },
    @{ Name = "Python (AI)";    Port = 8001 }
  )
  foreach ($s in $servers) {
    if (Test-PortListening $s.Port) {
      $owner = Get-PortOwnerName $s.Port
      Write-Info "停止 $owner..."
      $conn = Get-NetTCPConnection -LocalPort $s.Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
      Stop-Process -Id $conn.OwningProcess -Force -ErrorAction SilentlyContinue
    }
  }

  # 再关 Docker 容器
  Write-Info "停止 Docker 容器..."
  Push-Location $PROJECT_ROOT
  try {
    $containers = @("backend", "frontend", "ai-service", "postgres", "redis", "minio", "emqx", "tdengine")
    foreach ($c in $containers) {
      docker compose stop $c 2>&1 | Out-Null
    }
  } finally { Pop-Location }

  Write-Ok "所有服务已停止"
}

# ─────────────────────────────────────────────
# G. 重启所有服务
# ─────────────────────────────────────────────
function Invoke-Restart {
  Invoke-Stop
  Start-Sleep -Seconds 2
  if ($All -or $Backend -or $Frontend -or $Ai) {
    & $MyInvocation.MyCommand.Path -All
  } else {
    Invoke-AllStack
  }
}

# ─────────────────────────────────────────────
# H. 健康度速览
# ─────────────────────────────────────────────
function Invoke-Status {
  Write-Step "健康度速览"

  $entries = @(
    @{ Port = 15432; Name = "Postgres"        },
    @{ Port = 16379; Name = "Redis"           },
    @{ Port = 19000; Name = "MinIO API"       },
    @{ Port = 19001; Name = "MinIO Console"   },
    @{ Port = 11883; Name = "EMQX MQTT"       },
    @{ Port = 28083; Name = "EMQX Dashboard"  },
    @{ Port = 16041; Name = "TDengine HTTP"    },
    @{ Port = 5173;  Name = "Frontend (Vite)" },
    @{ Port = 8080;  Name = "Backend"         },
    @{ Port = 8001;  Name = "AI Service"      }
  )

  foreach ($e in $entries) {
    if (Test-PortListening $e.Port) {
      $owner = Get-PortOwnerName $e.Port
      Write-Ok ("{0,-22} :{1,-5}  {2}" -f $e.Name, $e.Port, $owner)
    } else {
      Write-Err ("{0,-22} :{1,-5}  DOWN" -f $e.Name, $e.Port)
    }
  }

  Write-Host ""
  Write-Info "Docker 状态："
  if (Test-DockerDaemonUp) {
    Write-Ok "Docker Desktop 在线"
    $running = docker ps --format "{{.Names}}" 2>$null | Where-Object { $_ -like "disasterguard-*" }
    if ($running) { Write-Info "容器: $($running -join ', ')" }
    else { Write-Warn "未发现 disasterguard-* 容器（正常，如用本地进程启动）" }
  } else {
    Write-Err "Docker Desktop 未运行"
  }

  Write-Host ""
  Write-Info "Git 状态："
  try {
    Push-Location $PROJECT_ROOT
    $branch = & git rev-parse --abbrev-ref HEAD 2>$null
    $ahead  = & git rev-list --count HEAD "@{u}" 2>$null
    $behind = & git rev-list --count "@{u}" HEAD 2>$null
    Pop-Location
    if ($branch) {
      Write-Ok "分支: $branch"
      if ($ahead  -gt 0) { Write-Warn "ahead  $ahead 个提交未推送" }
      if ($behind -gt 0) { Write-Warn "behind $behind 个远端提交未拉取" }
      if ($ahead -eq 0 -and $behind -eq 0) { Write-Ok "与 origin/$branch 同步" }
    }
  } catch { Write-Warn "Git 不可用" }
}

# ─────────────────────────────────────────────
# I. Doctor — 诊断依赖
# ─────────────────────────────────────────────
function Invoke-Doctor {
  Write-Step "Doctor — 诊断依赖"

  # Docker
  if (Test-DockerDaemonUp) { Write-Ok "Docker Desktop 可用" }
  else { Write-Err "Docker Desktop 未运行（Daemon 不可用）" }

  # Node.js
  try {
    $v = node --version 2>$null
    if ($v -match 'v(\d+)') {
      if ([int]$Matches[1] -ge 18) { Write-Ok "Node.js $v (>=18)" }
      else { Write-Err "Node.js $v (<18) — 需升级到 18+" }
    }
  } catch { Write-Err "Node.js 未安装" }

  # Java
  try {
    $jv = (java -version 2>&1 | Select-Object -First 1)
    if ($jv -match '"(\d+)\.') {
      if ([int]$Matches[1] -ge 17) { Write-Ok "$jv (>=17)" }
      else { Write-Err "$jv (<17) — 后端需 JDK 17+" }
    }
  } catch { Write-Err "Java 未安装" }

  # Python
  try {
    $pv = python --version 2>&1
    if ($pv -match 'Python (\d+)\.(\d+)') {
      $maj = [int]$Matches[1]; $min = [int]$Matches[2]
      if ($maj -ge 3 -and $min -ge 10) { Write-Ok "$pv (>=3.10)" }
      else { Write-Warn "$pv — AI 服务建议 3.10+" }
    }
  } catch { Write-Err "Python 未安装" }

  # Maven
  try {
    $mv = mvn --version 2>&1 | Select-Object -First 1
    Write-Ok "$mv"
  } catch { Write-Err "Maven 未安装" }

  # 端口冲突
  Write-Host ""
  Write-Info "关键端口："
  foreach ($p in @(5173, 8080, 8001, 15432, 16379, 19000)) {
    if (Test-PortListening $p) {
      $o = Get-PortOwnerName $p
      Write-Warn "  :$p  $o"
    } else {
      Write-Ok "  :$p  空闲"
    }
  }

  Write-Host ""
  Write-Info "参考文档：docs/DEVELOPER.md"
}

# ─── 主调度 ───
Write-Host ""
Write-Host "============================================================" -ForegroundColor Magenta
Write-Host "  DisasterGuard 地脉智巡 · 一键开发启动" -ForegroundColor Magenta
Write-Host "============================================================" -ForegroundColor Magenta

if ($Stop) {
  Invoke-Stop
} elseif ($Restart) {
  Invoke-Restart
} elseif ($All) {
  Invoke-AllStack
} elseif ($Infra) {
  Invoke-Infra
} elseif ($Backend) {
  Invoke-Backend
} elseif ($Ai) {
  Invoke-AiService
} elseif ($Status) {
  Invoke-Status
} elseif ($Doctor) {
  Invoke-Doctor
} else {
  # 默认启动前端
  Invoke-Frontend
}
