<#
.SYNOPSIS
  End-to-end verification of the Redis-backed JwtBlacklist.

.DESCRIPTION
  Chain of proof (all steps run against a live backend + Redis container):
    1. GET  /api/system/health            -> backend is reachable
    2. POST /api/auth/login               -> obtain a fresh JWT (read jti / exp)
    3. GET  <protected path>              -> valid token is accepted (HTTP 200)
    4. POST /api/auth/logout              -> revoke that JWT
    5. redis-cli SCAN / GET / TTL         -> key jwt:blacklist:{jti} exists,
                                            value is "1", TTL == remaining JWT lifetime
    6. GET  <protected path> (old token)  -> HTTP 401
    7. POST /api/auth/login               -> a brand new token is still accepted
                                            (revocation is scoped to jti, not global)

  Exit codes: 0 = all checks passed, 1 = at least one check failed,
              2 = blocked (backend / Redis / login unreachable).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts/audit/verify-jwt-blacklist.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts/audit/verify-jwt-blacklist.ps1 `
    -BaseUrl http://127.0.0.1:8080/api -RedisPassword $env:REDIS_PASSWORD
#>
[CmdletBinding()]
param(
  [string]$BaseUrl = 'http://localhost:8080/api',
  [string]$Username = 'admin',
  [string]$Password = 'admin123',
  [string]$RedisContainer = 'disasterguard-redis',
  [string]$RedisPassword = $env:REDIS_PASSWORD,
  # Any authenticated read-only endpoint is fine; /api/warnings is used by precheck.mjs too.
  [string]$ProtectedPath = '/warnings?page=1&size=1',
  [int]$TtlToleranceSeconds = 10
)

$ErrorActionPreference = 'Stop'
# Keep the output machine-readable (Invoke-WebRequest otherwise emits progress bars).
$ProgressPreference = 'SilentlyContinue'
# Do not let an inherited HTTP_PROXY / system proxy swallow loopback calls.
[System.Net.WebRequest]::DefaultWebProxy = $null

if ([string]::IsNullOrWhiteSpace($RedisPassword)) {
  # Must match spring.data.redis.password in application.yml and the compose default.
  $RedisPassword = 'CHANGE_ME__dev_redis_password_2026'
}
$BaseUrl = $BaseUrl.TrimEnd('/')

$checks = New-Object System.Collections.ArrayList

function Add-Check {
  param([string]$Name, [bool]$Ok, [string]$Detail)
  [void]$checks.Add([pscustomobject]@{ Name = $Name; Ok = $Ok; Detail = $Detail })
  $tag = 'FAIL'
  if ($Ok) { $tag = 'PASS' }
  Write-Output ('[{0}] {1} -- {2}' -f $tag, $Name, $Detail)
}

function Get-HttpStatus {
  param([string]$Method, [string]$Url, [hashtable]$Headers)
  try {
    $r = Invoke-WebRequest -Method $Method -Uri $Url -Headers $Headers `
      -TimeoutSec 20 -UseBasicParsing
    return [int]$r.StatusCode
  } catch {
    $ex = $_.Exception
    $code = $null
    if ($null -ne $ex.Response) { $code = [int]$ex.Response.StatusCode }
    elseif ($null -ne $ex.StatusCode) { $code = [int]$ex.StatusCode }
    if ($null -ne $code) { return $code }
    throw
  }
}

function Get-JwtPayload {
  param([string]$Jwt)
  $parts = $Jwt.Split('.')
  if ($parts.Length -ne 3) { throw 'JWT does not have 3 segments' }
  $seg = $parts[1].Replace('-', '+').Replace('_', '/')
  $mod = $seg.Length % 4
  if ($mod -eq 2) { $seg += '==' }
  elseif ($mod -eq 3) { $seg += '=' }
  elseif ($mod -eq 1) { throw 'Invalid base64url payload' }
  $json = [System.Text.Encoding]::UTF8.GetString(
    [System.Convert]::FromBase64String($seg))
  return ($json | ConvertFrom-Json)
}

function Invoke-RedisCli {
  param([string[]]$Arguments)
  $prefix = @('exec')
  if (-not [string]::IsNullOrWhiteSpace($RedisPassword)) {
    $prefix += @('-e', "REDISCLI_AUTH=$RedisPassword")
  }
  $prefix += @($RedisContainer, 'redis-cli', '--raw')
  $all = $prefix + $Arguments
  $raw = & docker @all 2>$null
  if ($LASTEXITCODE -ne 0) {
    throw "redis-cli failed (exit $LASTEXITCODE): $raw"
  }
  return @($raw | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ -ne '' })
}

Write-Output '== JwtBlacklist Redis E2E verification =='
Write-Output ('Base URL        : {0}' -f $BaseUrl)
Write-Output ('Redis container : {0}' -f $RedisContainer)
Write-Output ('Protected path  : {0}' -f $ProtectedPath)
Write-Output ('UTC start       : {0}' -f [DateTime]::UtcNow.ToString('o'))
Write-Output ''

# --- Step 0: backend reachable -------------------------------------------------
try {
  $health = Get-HttpStatus -Method 'GET' -Url "$BaseUrl/system/health" -Headers @{}
  Add-Check 'backend reachable' ($health -eq 200) "GET /system/health -> HTTP $health"
  if ($health -ne 200) { Write-Output '[BLOCKED] backend health is not 200'; exit 2 }
} catch {
  Write-Output ('[BLOCKED] backend unreachable: {0}' -f $_.Exception.Message)
  exit 2
}

# --- Step 1: login -------------------------------------------------------------
$loginBody = @{ username = $Username; password = $Password } | ConvertTo-Json -Compress
try {
  $login = Invoke-RestMethod -Method 'POST' -Uri "$BaseUrl/auth/login" `
    -ContentType 'application/json' -Body $loginBody -TimeoutSec 20
} catch {
  Write-Output ('[BLOCKED] login failed: {0}' -f $_.Exception.Message)
  exit 2
}
$token = [string]$login.data.token
if ([string]::IsNullOrWhiteSpace($token)) {
  Write-Output '[BLOCKED] login response contains no token'
  exit 2
}
Add-Check 'login returns JWT' $true "user=$Username, tokenLength=$($token.Length)"

$payload = Get-JwtPayload -Jwt $token
$jti = [string]$payload.jti
$exp = [long]$payload.exp
if ([string]::IsNullOrWhiteSpace($jti)) {
  # Legacy tokens without jti fall back to jwt:blacklist:sub:<sub>:iat:<epoch-ms>.
  $expectedKey = 'jwt:blacklist:sub:' + [string]$payload.sub + ':iat:' + ([long]$payload.iat * 1000)
} else {
  $expectedKey = 'jwt:blacklist:' + $jti
}
Add-Check 'JWT carries jti and exp' ($exp -gt 0) "jti=$jti exp=$exp expectedKey=$expectedKey"

$authHeaders = @{ Authorization = "Bearer $token" }

# --- Step 2: token is valid before logout --------------------------------------
try {
  $before = Get-HttpStatus -Method 'GET' -Url ($BaseUrl + $ProtectedPath) -Headers $authHeaders
  Add-Check 'valid token accepted before logout' ($before -eq 200) `
    "GET $ProtectedPath -> HTTP $before"
} catch {
  Write-Output ('[BLOCKED] protected endpoint failed before logout: {0}' -f $_.Exception.Message)
  exit 2
}

# --- Step 3: logout revokes the token ------------------------------------------
try {
  $logout = Invoke-RestMethod -Method 'POST' -Uri "$BaseUrl/auth/logout" `
    -Headers $authHeaders -TimeoutSec 20
  Add-Check 'logout endpoint succeeded' $true ("response code=" + [string]$logout.code)
} catch {
  Write-Output ('[BLOCKED] logout failed: {0}' -f $_.Exception.Message)
  exit 2
}

# --- Step 4: Redis key / value / TTL -------------------------------------------
try {
  $keys = Invoke-RedisCli -Arguments @('--scan', '--pattern', 'jwt:blacklist:*')
  $value = (Invoke-RedisCli -Arguments @('GET', $expectedKey)) -join ''
  $ttlRaw = (Invoke-RedisCli -Arguments @('TTL', $expectedKey)) -join ''
} catch {
  Write-Output ('[BLOCKED] Redis check failed: {0}' -f $_.Exception.Message)
  exit 2
}

$keyHit = @($keys) -contains $expectedKey
Add-Check 'Redis holds the exact revoked key' $keyHit `
  "exactKey=$expectedKey scanned=$(@($keys).Count)"
Add-Check 'Redis value is "1"' ($value -eq '1') "GET $expectedKey -> '$value'"

$ttl = 0
[void][int]::TryParse($ttlRaw, [ref]$ttl)
$nowSec = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$remaining = [int]($exp - $nowSec)
$ttlOk = ($ttl -gt 0) -and
         ($ttl -le ($remaining + 2)) -and
         ($ttl -ge ($remaining - $TtlToleranceSeconds))
Add-Check 'TTL equals remaining JWT lifetime' $ttlOk `
  "TTL=${ttl}s expectedRemaining=${remaining}s exp=$exp tolerance=+/-${TtlToleranceSeconds}s"

# --- Step 5: revoked token must be rejected ------------------------------------
try {
  $after = Get-HttpStatus -Method 'GET' -Url ($BaseUrl + $ProtectedPath) -Headers $authHeaders
  Add-Check 'revoked token rejected with 401' ($after -eq 401) `
    "GET $ProtectedPath -> HTTP $after"
} catch {
  Write-Output ('[BLOCKED] protected endpoint failed after logout: {0}' -f $_.Exception.Message)
  exit 2
}

# --- Step 6: a fresh token is still valid (per-jti revocation) ----------------
try {
  $login2 = Invoke-RestMethod -Method 'POST' -Uri "$BaseUrl/auth/login" `
    -ContentType 'application/json' -Body $loginBody -TimeoutSec 20
  $token2 = [string]$login2.data.token
  $fresh = Get-HttpStatus -Method 'GET' -Url ($BaseUrl + $ProtectedPath) `
    -Headers @{ Authorization = "Bearer $token2" }
  Add-Check 'fresh login still works (jti-scoped)' ($fresh -eq 200) `
    "new tokenLength=$($token2.Length) HTTP $fresh"
} catch {
  Write-Output ('[BLOCKED] fresh login check failed: {0}' -f $_.Exception.Message)
  exit 2
}

# --- Summary -------------------------------------------------------------------
$passed = @($checks | Where-Object { $_.Ok }).Count
$failed = @($checks | Where-Object { -not $_.Ok }).Count
Write-Output ''
Write-Output '------ Summary ------'
Write-Output "PASS=$passed FAIL=$failed"
Write-Output ("jti={0} key={1} ttl={2}s" -f $jti, $expectedKey, $ttl)
Write-Output ("UTC end: {0}" -f [DateTime]::UtcNow.ToString('o'))

# --- Cleanup: delete the revocation entry written by this run -------------------
# The key belongs to this login's jti only, so deleting it cannot affect other
# sessions. Without cleanup the key survives until TTL expiry and the next run
# sees a leftover key from the previous run (R8 P2-7).
# Set KEEP_REDIS_KEY=1 to keep the key for on-site troubleshooting.
if ($env:KEEP_REDIS_KEY -eq '1') {
  Write-Output ("[CLEANUP] skipped (KEEP_REDIS_KEY=1); key={0}" -f $expectedKey)
} else {
  try {
    $delResult = (Invoke-RedisCli -Arguments @('DEL', $expectedKey)) -join ''
    Write-Output ("[CLEANUP] DEL {0} -> deleted={1}" -f $expectedKey, $delResult)
  } catch {
    Write-Output ('[WARN] cleanup failed: {0}' -f $_.Exception.Message)
  }
}

if ($failed -gt 0) {
  Write-Output 'Verdict: FAILED - JwtBlacklist Redis chain is not fully proven.'
  exit 1
}
Write-Output 'Verdict: PASSED - login -> revoke -> Redis key/TTL -> 401 verified end to end.'
exit 0
