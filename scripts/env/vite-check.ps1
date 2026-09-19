try {
    $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 'http://127.0.0.1:5174/'
    Write-Host ('VITE STATUS: ' + $r.StatusCode)
    Write-Host ('VITE LEN: ' + $r.Content.Length)
} catch {
    Write-Host ('VITE ERROR: ' + $_.Exception.Message)
}
try {
    $r2 = Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 'http://127.0.0.1:5174/'
    $status = $r2.StatusCode
    $len = $r2.Content.Length
    Write-Host ('OK status=' + $status + ' len=' + $len)
} catch {
    Write-Host ('ERR: ' + $_.Exception.Message)
}