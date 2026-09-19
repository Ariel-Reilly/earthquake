$env:PGPASSWORD = 'disasterguard123'
$content = Get-Content -Raw (Join-Path $PSScriptRoot '..\..\backend\src\main\resources\schema.sql')

# 提取 schema.sql 第 15 句（hazard_point DO $E$ 块）到临时目录，psql 跑完即删
$tmp = Join-Path $env:TEMP 'disasterguard-probe-stmt15.sql'
$start = $content.IndexOf('DO $E$')
$rest = $content.Substring($start)
# find the matching END$E$;
$endMarker = 'END$E$;'
$end = $rest.IndexOf($endMarker) + $endMarker.Length
$block = $rest.Substring(0, $end)
Set-Content -Path $tmp -Value $block -Encoding UTF8
Write-Host ('Length: ' + $block.Length)
Write-Host '---'
& 'E:\PostgreSQL\18\bin\psql.exe' -h 127.0.0.1 -p 15432 -U disasterguard -d disasterguard -f $tmp -w 2>&1 | Select-Object -First 30
# 清理临时文件
Remove-Item -Path $tmp -ErrorAction SilentlyContinue
