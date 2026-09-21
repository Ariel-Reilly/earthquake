$srcRoot = 'D:\DisasterGuard\backend\src\main\java'
$srcTestRoot = 'D:\DisasterGuard\backend\src\test\java'
$imports = New-Object System.Collections.Generic.List[Object]

foreach ($root in @($srcRoot, $srcTestRoot)) {
  if (-not (Test-Path $root)) { continue }
  Get-ChildItem -Path $root -Recurse -Include '*.java','*.kt' -File | ForEach-Object {
    $file = $_
    $lines = Get-Content $file.FullName
    $regex = [regex]'^\s*import\s+(cn\.disasterguard\.[\w\.]+)\s*;'
    for ($i = 0; $i -lt $lines.Length; $i++) {
      $m = $regex.Match($lines[$i])
      if ($m.Success) {
        $imports.Add([pscustomobject]@{
          File = $file.FullName
          LineNo = $i + 1
          Import = $m.Groups[1].Value
        })
      }
    }
  }
}

$missing = @()
foreach ($imp in $imports) {
  $segments = $imp.Import -split '\.'
  # Try stripping last segment (treat as nested type); find a file that exists
  $found = $false
  for ($k = $segments.Length; $k -ge 2; $k--) {
    $candidate = ($segments[0..($k-1)] -join '.')
    $relPath = $candidate -replace '\.', '/'
    $fullJava = Join-Path $srcRoot ($relPath + '.java')
    $fullKt = Join-Path $srcRoot ($relPath + '.kt')
    if ((Test-Path $fullJava) -or (Test-Path $fullKt)) {
      $found = $true
      break
    }
  }
  if (-not $found) {
    $missing += $imp
  }
}

Write-Host ('total imports scanned: ' + $imports.Count)
Write-Host ('missing imports: ' + $missing.Count)
foreach ($m in $missing) {
  $idx = $m.File.IndexOf('backend\src')
  $rel = $m.File.Substring($idx)
  Write-Host ("MISSING  " + $rel + ':' + $m.LineNo + '  ' + $m.Import)
}