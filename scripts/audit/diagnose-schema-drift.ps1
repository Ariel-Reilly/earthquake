# diagnose-schema-drift.ps1
# 检查 disasterguard-postgres 容器里 ai_comprehensive_score 表的列，对照 schema.sql 期望的列
# 用法：.\scripts\diagnose-schema-drift.ps1

$ErrorActionPreference = 'Stop'

$expected = @(
  'id','hazard_code','risk_level','risk_score','source',
  'disaster_type','concern_label','disaster_type_source',
  'disaster_type_confidence','dimensions_json','reasoning_json',
  'suggested_actions_json','confidence','model','degraded',
  'device_available','analyzed_at','created_at','updated_at'
)

$raw = docker exec disasterguard-postgres psql -U disasterguard -d disasterguard -t -A -F '|' -c "\d ai_comprehensive_score" 2>&1
if ($LASTEXITCODE -ne 0) {
  Write-Host "[X] docker exec 失败：$raw" -ForegroundColor Red
  Write-Host '    请确认 Docker Desktop 已启动，且 disasterguard-postgres 容器在跑。' -ForegroundColor Red
  exit 1
}

$Script:actual = @()
foreach ($line in ($raw -split "`r?`n")) {
  if ($line -match '^\s*(\w+)\s*\|') { $Script:actual += $matches[1] }
}

if (-not $Script:actual -or $Script:actual.Count -lt 3) {
  Write-Host '[X] 未取到列信息，容器可能没就绪或表不存在。' -ForegroundColor Red
  Write-Host "    docker 输出: $raw" -ForegroundColor DarkGray
  exit 1
}

Write-Host '=== ai_comprehensive_score 列检查 ===' -ForegroundColor Cyan
Write-Host ('当前列数: {0}    schema.sql 期望列数: {1}' -f $Script:actual.Count, $expected.Count)

$missing = $expected | Where-Object { $_ -notin $Script:actual }
if ($missing.Count -gt 0) {
  Write-Host ''
  Write-Host '[!] 缺失列：' -ForegroundColor Yellow
  $missing | ForEach-Object { Write-Host "    - $_" -ForegroundColor Yellow }
  Write-Host ''
  Write-Host '这正是 spring.sql.init 报错的根本原因。' -ForegroundColor Yellow
  Write-Host '建议运行: .\scripts\fix-db-volume.ps1 重建 Postgres 数据卷' -ForegroundColor Green
  exit 2
} else {
  Write-Host ''
  Write-Host '[OK] 所有期望列都存在。' -ForegroundColor Green
  exit 0
}
