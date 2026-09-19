Get-ChildItem -Path 'D:\earthquake\backend\src' -Recurse -Include '*.java','*.kt' -File |
  Select-String -Pattern 'cn\.disasterguard\.' |
  ForEach-Object {
    $p = $_.Path
    $idx = $p.IndexOf('cn\disasterguard')
    $rel = $p.Substring($idx)
    Write-Host ($rel + ':' + $_.LineNumber + ' ' + $_.Line.Trim())
  }
Write-Host '--- done ---'