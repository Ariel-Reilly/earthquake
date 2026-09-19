$lines = Get-Content 'D:\earthquake\backend\src\main\resources\schema.sql'
for ($i = 0; $i -lt $lines.Count; $i++) {
  $line = $lines[$i]
  if ($line -match "''") {
    Write-Host ('L' + ($i + 1) + ': ' + $line)
  }
}
