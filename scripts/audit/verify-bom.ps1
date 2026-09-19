$paths = @(
  'D:\earthquake\backend\src\main\java\cn\disasterguard',
  'D:\earthquake\backend\src\test\java\cn\disasterguard'
)
$bomCount = 0
$total = 0
foreach ($dir in $paths) {
  if (-not (Test-Path $dir)) { continue }
  Get-ChildItem -Path $dir -Recurse -Filter *.java | ForEach-Object {
    $script:total++
    $bytes = [System.IO.File]::ReadAllBytes($_.FullName)
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
      $script:bomCount++
      Write-Host ('STILL HAS BOM: ' + $_.FullName)
    }
  }
}
Write-Host ''
Write-Host ('Total files scanned: ' + $total)
Write-Host ('Files still containing BOM: ' + $bomCount)
