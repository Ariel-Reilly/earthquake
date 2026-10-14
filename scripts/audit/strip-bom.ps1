$paths = @(
  'D:\Projects\DisasterGuard\backend\src\main\java\cn\disasterguard',
  'D:\Projects\DisasterGuard\backend\src\test\java\cn\disasterguard'
)
$totalFixed = 0
foreach ($dir in $paths) {
  if (-not (Test-Path $dir)) { continue }
  Get-ChildItem -Path $dir -Recurse -Filter *.java | ForEach-Object {
    $path = $_.FullName
    $bytes = [System.IO.File]::ReadAllBytes($path)
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
      $newBytes = $bytes[3..($bytes.Length - 1)]
      [System.IO.File]::WriteAllBytes($path, $newBytes)
      Write-Host ('FIXED: ' + $path)
      $script:totalFixed++
    }
  }
}
Write-Host ''
Write-Host ('Total files fixed: ' + $totalFixed)
