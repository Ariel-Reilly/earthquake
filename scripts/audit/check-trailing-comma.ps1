$root = 'D:\DisasterGuard\backend\src'
$results = @()
Get-ChildItem -Path $root -Recurse -Include '*.java','*.kt' -File | ForEach-Object {
  $file = $_
  $lines = Get-Content $file.FullName
  for ($i = 0; $i -lt $lines.Length; $i++) {
    $line = $lines[$i]
    # Match patterns like: <something> ), or ), or ]  alone (closing paren/bracket with trailing comma immediately before)
    if ($line -match ',\s*\)\s*;?\s*$') {
      # Filter common false positives: enum-style comma+paren, Javadoc, etc.
      if ($line -match '\)\s*\*' -or $line -match '^\s*\*') { continue }
      $results += [pscustomobject]@{
        File = $file.FullName
        Line = $i + 1
        Text = $line.TrimEnd()
      }
    }
  }
}

Write-Host ('hits: ' + $results.Count)
foreach ($r in $results) {
  $idx = $r.File.IndexOf('backend\src')
  $rel = $r.File.Substring($idx)
  Write-Host ($rel + ':' + $r.Line + '  ' + $r.Text)
}