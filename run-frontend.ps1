# run-frontend.ps1 — 本地开发启动前端（Windows PowerShell）
# 自动设置 Vite 连接本地后端（假设 run-backend.ps1 已启动）
# 用法：在项目根目录执行  .\run-frontend.ps1

Write-Host "=== GeoSentry Frontend Local Runner ===" -ForegroundColor Cyan

# ---- 0. 如果 Docker frontend 在跑，停掉（释放 5173 端口 + 避免 Nginx 502） ----
$conn5173 = Get-NetTCPConnection -LocalPort 5173 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($conn5173) {
  $procId = $conn5173.OwningProcess
  $proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
  if ($proc -and $proc.ProcessName -match "docker|wslrelay|com.docker") {
    Write-Host "  [!] Port 5173 is held by Docker ($($proc.ProcessName)). Stopping geosentry-frontend container..." -ForegroundColor Yellow
    docker compose stop frontend 2>&1 | Out-Null
    Start-Sleep -Seconds 3
    Write-Host "  [OK] Docker frontend stopped" -ForegroundColor Green
  } elseif ($proc) {
    Write-Host "  [!] Port 5173 is held by PID=$procId ($($proc.ProcessName)). Killing it..." -ForegroundColor Yellow
    Stop-Process -Id $procId -Force
    Start-Sleep -Seconds 1
  }
}

# ---- 1. 确保后端在跑 ----
$backendConn = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
if ($backendConn) {
  Write-Host "  [OK] Backend reachable on port 8080" -ForegroundColor Green
} else {
  Write-Host "  [!] Backend on port 8080 is NOT running. Run .\run-backend.ps1 first." -ForegroundColor Yellow
}

# ---- 2. 设置 Vite 环境变量 ----
$env:VITE_USE_BACKEND     = "true"
$env:VITE_API_BASE_URL    = "http://localhost:8080/api"

Write-Host "  [env] VITE_API_BASE_URL=http://localhost:8080/api" -ForegroundColor DarkGray
Write-Host "`n=== Starting Vite dev server... ===" -ForegroundColor Cyan
Write-Host "  URL: http://localhost:5173/`n" -ForegroundColor Gray

Push-Location $PSScriptRoot
try {
  npm run dev
}
finally {
  Pop-Location
}
