@echo off
echo === Port 8001 owner (netstat) ===
netstat -ano | findstr :8001
echo.
echo === Trying to kill PIDs ===
for /f "tokens=5" %%p in ('netstat -ano ^| findstr :8001') do (
  echo Killing PID %%p
  taskkill /F /PID %%p 2>&1
)
echo.
echo === Port 8001 after kill ===
timeout /t 2 /nobreak >nul
netstat -ano | findstr :8001
if errorlevel 1 echo [OK] Port 8001 free
