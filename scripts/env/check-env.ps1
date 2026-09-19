$names = @(
  'POSTGRES_USER','POSTGRES_PASSWORD','POSTGRES_DB','POSTGRES_HOST','POSTGRES_PORT',
  'REDIS_HOST','REDIS_PORT','REDIS_PASSWORD',
  'JWT_SECRET','SPRING_PROFILES_ACTIVE',
  'MINIO_ENDPOINT','MINIO_ACCESS_KEY','MINIO_SECRET_KEY',
  'EMQX_ENABLED','TDENGINE_ENABLED','AI_ENABLED'
)
foreach ($n in $names) {
  $v = [Environment]::GetEnvironmentVariable($n, 'Process')
  if ($null -ne $v -and $v -ne '') {
    if ($n -match 'SECRET|PASSWORD|TOKEN|JWT') {
      Write-Host ($n + '=*** (' + $v.Length + ' chars)')
    } else {
      Write-Host ($n + '=' + $v)
    }
  }
}
