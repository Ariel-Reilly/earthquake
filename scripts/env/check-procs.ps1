$pattern = 'docker|com.docker|postgres|redis|vpnkit|com.docker.backend'
Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.ProcessName -match $pattern } | Select-Object Id, ProcessName | Format-Table -AutoSize
Write-Host '---'
$ports = 15432, 16379, 19000, 11883, 18083, 16030, 16041, 2375, 2376
Get-NetTCPConnection -ErrorAction SilentlyContinue | Where-Object { $ports -contains $_.LocalPort } | Format-Table LocalAddress, LocalPort, State, OwningProcess -AutoSize
