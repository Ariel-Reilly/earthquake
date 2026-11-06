$procs = Get-Process -ErrorAction SilentlyContinue
Write-Host ('Total processes: ' + $procs.Count)
$matches = $procs | Where-Object { $_.ProcessName -match 'docker|postgres|redis|emqx|minio|taos' } | Select-Object Id, ProcessName
Write-Host ('Matches: ' + ($matches | Out-String))
Write-Host '---Wsl---'
wsl --list --verbose 2>&1
