$env:PGPASSWORD = 'disasterguard123'
$sql = @"
DROP TABLE IF EXISTS _x CASCADE;
DROP VIEW IF EXISTS hazard_point CASCADE;
DROP TABLE IF EXISTS hazard_point CASCADE;
"@
& 'E:\PostgreSQL\18\bin\psql.exe' -h 127.0.0.1 -p 15432 -U disasterguard -d disasterguard -c $sql -w
Write-Host ('exit: ' + $LASTEXITCODE)
