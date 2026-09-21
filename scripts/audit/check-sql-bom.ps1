$files = @(
  'D:\DisasterGuard\backend\src\main\resources\schema.sql',
  'D:\DisasterGuard\backend\src\main\resources\data.sql'
)
foreach ($f in $files) {
  $bytes = [System.IO.File]::ReadAllBytes($f)
  $bom = ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF)
  Write-Host ($f + ' BOM=' + $bom + ' bytes=' + $bytes.Length)
}
