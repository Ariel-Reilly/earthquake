$path = "D:\earthquake\backend\src\main\resources\data.sql"
$content = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
$lines = $content -split "`r?`n"
$startIdx = -1; $endIdx = -1
for($i=0;$i -lt $lines.Count;$i++){
  if($lines[$i] -match '^-- ===== operation_log') { $startIdx = $i }
  if($lines[$i] -match '\) AS v\(user_id') { $endIdx = $i; break }
}
Write-Host "startIdx=$startIdx endIdx=$endIdx"
if($startIdx -lt 0 -or $endIdx -lt 0){ exit 1 }

$valueLines = @()
for($i=$startIdx+2; $i -le $endIdx-1; $i++){
  $ln = $lines[$i].Trim()
  if($ln -match '^\(.*\)$'){
    $ln = $ln.Substring(1, $ln.Length-2)
    $parts = @(); $cur = ''; $inQuote = $false
    foreach($ch in $ln.ToCharArray()){
      if($ch -eq "'"){ $inQuote = -not $inQuote; $cur += $ch; continue }
      if($ch -eq ',' -and -not $inQuote){ $parts += $cur.Trim(); $cur = ''; continue }
      $cur += $ch
    }
    if($cur.Length -gt 0){ $parts += $cur.Trim() }
    if($parts.Count -ne 8){
      Write-Host "PARSE FAIL line $($i+1): parts=$($parts.Count)  :: $ln"
      exit 1
    }
    $user_id, $username, $action, $bizType, $bizCode, $detail, $ip, $createdAt = $parts

    $operator = "'" + $username.Trim("'") + "'"
    $module = "'" + $bizType.Trim("'") + "'"
    $actionName = "'" + $action.Trim("'") + "'"
    switch ($action.Trim("'")) {
      'CREATE'  { $method = "'POST'" }
      'UPDATE'  { $method = "'PUT'" }
      'CLOSE'   { $method = "'POST'" }
      'REFRESH' { $method = "'POST'" }
      'EXPORT'  { $method = "'GET'" }
      'CONFIRM' { $method = "'POST'" }
      'DISPATCH'{ $method = "'POST'" }
      'DELETE'  { $method = "'DELETE'" }
      default   { $method = "'POST'" }
    }
    $code = $bizCode.Trim("'")
    if($bizCode -eq 'NULL'){
      $pathStr = "/api/" + $bizType.Trim("'")
    } else {
      $pathStr = "/api/" + $bizType.Trim("'") + "/" + $code
    }
    $body = $detail
    $status = "'SUCCESS'"
    $cost = Get-Random -Minimum 35 -Maximum 280
    $valueLines += "  ($operator,$module,$actionName,$method,'$pathStr',$body,$status,$cost,$ip,$createdAt)"
  }
}

$newBlock = @(
  '-- ===== operation_log (30 条近 7 天审计) ====='
  'INSERT INTO operation_log (operator, module_name, action_name, request_method, request_path, request_body, result_status, cost_ms, ip_address, created_at) VALUES'
) + $valueLines + 'ON CONFLICT DO NOTHING;'

$newLines = @()
for($i=0;$i -lt $startIdx;$i++){ $newLines += $lines[$i] }
$newLines += $newBlock
for($i=$endIdx+1;$i -lt $lines.Count;$i++){ $newLines += $lines[$i] }
$newContent = $newLines -join "`r`n"
[System.IO.File]::WriteAllText($path, $newContent, (New-Object System.Text.UTF8Encoding $false))
Write-Host "Done. New value rows: $($valueLines.Count)"
Write-Host "New file size: $($newContent.Length)"
