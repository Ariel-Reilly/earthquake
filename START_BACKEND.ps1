# ========================================
# DisasterGuard 后端快速启动脚本
# ========================================
# 用途：编译并启动 Spring Boot 后端服务（端口 8080）
# 前置条件：
#   - Java 17+
#   - Maven 3.6+
#   - Docker 服务（postgres/redis/minio）已运行
# ========================================

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

Write-Host "🚀 启动 DisasterGuard 后端服务..." -ForegroundColor Cyan

# 1. 检查依赖服务
Write-Host "`n[1/4] 检查 Docker 依赖服务..." -ForegroundColor Yellow
$containers = docker ps --format "{{.Names}}" 2>$null
$required = @("disasterguard-postgres", "disasterguard-redis", "disasterguard-minio")
$missing = $required | Where-Object { $containers -notcontains $_ }

if ($missing.Count -gt 0) {
    Write-Host "❌ 缺失依赖服务：$($missing -join ', ')" -ForegroundColor Red
    Write-Host "请先运行：docker-compose -f docker-compose.dev.yml up -d" -ForegroundColor Yellow
    exit 1
}
Write-Host "✅ 依赖服务正常运行" -ForegroundColor Green

# 2. 进入后端目录
Write-Host "`n[2/4] 进入后端目录..." -ForegroundColor Yellow
Set-Location "$PSScriptRoot\backend"

# 3. 编译项目（跳过测试加速启动）
Write-Host "`n[3/4] 编译项目（跳过测试）..." -ForegroundColor Yellow
mvn clean package -DskipTests
if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ 编译失败，请检查代码" -ForegroundColor Red
    exit 1
}
Write-Host "✅ 编译完成" -ForegroundColor Green

# 4. 启动服务
Write-Host "`n[4/4] 启动 Spring Boot..." -ForegroundColor Yellow
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Cyan
Write-Host "🌐 后端地址：http://localhost:8080" -ForegroundColor Green
Write-Host "📚 API 文档：http://localhost:8080/swagger-ui.html" -ForegroundColor Green
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Cyan
Write-Host ""

java -jar target/disasterguard-backend-0.0.1-SNAPSHOT.jar
