Get-Content 'D:\earthquake\backend\src\main\resources\schema.sql' |
  Where-Object { $_ -notmatch '^\s*--' -and $_ -notmatch '^\s*$' } |
  ForEach-Object { $_.Trim() } |
  Where-Object { $_ -match ';' } |
  ForEach-Object -Begin { $i = 1 } -Process {
    $line = $_
    Write-Host ('#{0:00}: {1}' -f $i, $line.Substring(0, [Math]::Min(80, $line.Length)))
    $i++
  }
