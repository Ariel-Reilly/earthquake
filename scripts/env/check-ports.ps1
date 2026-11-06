$ports = 15432, 16379, 19000, 11883, 18083, 16030, 16041
Get-NetTCPConnection -ErrorAction SilentlyContinue | Where-Object { $ports -contains $_.LocalPort } | Format-Table LocalAddress, LocalPort, State, OwningProcess -AutoSize
