$root = 'D:\DisasterGuard\backend\src'
$files = Get-ChildItem -Path $root -Recurse -Include '*.java','*.kt' -File
$results = @()
foreach ($file in $files) {
  $content = Get-Content $file.FullName -Raw
  # Capture pattern: comma (with optional newline + spaces) followed by closing paren/bracket
  $ms = [regex]::Matches($content, ',\s*\r?\n\s*[\)\]]\s*;')
  foreach ($m in $ms) {
    $before = $content.Substring(0, $m.Index)
    $line = ($before -split '\r?\n').Count
    $snippet = ($before.Split("`n")[-1].TrimEnd() + $m.Value).Trim()
    $idx = $file.FullName.IndexOf('backend\src')
    $rel = $file.FullName.Substring($idx)
    $results += [pscustomobject]@{
      File = $rel
      Line = $line
      Text = $snippet
    }
  }
}
Write-Host ('multi-line trailing comma before ): ' + $results.Count)
foreach ($r in $results) {
  Write-Host ($r.File + ':' + $r.Line + '  ' + $r.Text)
}