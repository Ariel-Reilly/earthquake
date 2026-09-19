try {
    $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 'http://127.0.0.1:8080/api/health'
    Write-Host ('STATUS: ' + $r.StatusCode)
    Write-Host ('BODY: ' + $r.Content)
} catch {
    Write-Host ('ERROR: ' + $_.Exception.Message)
}
try {
    $r2 = Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 'http://127.0.0.1:8080/actuator/health'
    Write-Host ('ACTUATOR STATUS: ' + $r2.StatusCode)
    Write-Host ('ACTUATOR BODY: ' + $r2.Content)
} catch {
    Write-Host ('ACTUATOR ERROR: ' + $_.Exception.Message)
}