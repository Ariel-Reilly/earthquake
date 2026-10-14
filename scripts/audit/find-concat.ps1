$lines = Get-Content 'D:\Projects\DisasterGuard\backend\src\main\resources\schema.sql'
for ($i = 0; $i -lt $lines.Count; $i++) {
  $line = $lines[$i]
  if ($line -match "'[^']*'\s+'") {
    Write-Host ('L' + ($i + 1) + ': ' + $line)
  }
}
