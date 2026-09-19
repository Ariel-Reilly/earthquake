$ok = $false
for ($i = 0; $i -lt 60; $i++) {
  try {
    $out = & docker version 2>&1 | Out-String
    if ($out -match 'Server:') {
      $ok = $true
      break
    }
  } catch {}
  Start-Sleep -Seconds 2
}
Write-Host ('docker up: ' + $ok)
if ($ok) { docker version }
