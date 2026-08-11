@echo off
chcp 65001 >nul
title DN Photo Sync Server
cd /d "%~dp0"

powershell -NoProfile -Command "try { Invoke-RestMethod 'http://127.0.0.1:8080/health' -TimeoutSec 1 | Out-Null; exit 0 } catch { exit 1 }"
if errorlevel 1 start "" "%~dp0DN-Photo-Server-V6.exe"

:monitor
cls
echo ==========================================================
echo  DN Photo Sync Server
echo ==========================================================
echo.
powershell -NoProfile -Command "try { $h=Invoke-RestMethod 'http://127.0.0.1:8080/health' -TimeoutSec 2; if($h.status -eq 'ok'){ exit 0 }; exit 1 } catch { exit 1 }"
if errorlevel 1 goto stopped

echo  STATUS : RUNNING
echo  PORT   : 8080
echo.
echo  Address for the DN Android app:
powershell -NoProfile -Command "$ips=Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue ^| Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' }; $ips ^| ForEach-Object { '    http://' + $_.IPAddress + ':8080' }"
echo.
echo  Keep this window open to view server status.
echo  Double-click STOP-DN-SERVER.cmd to stop the server.
echo ==========================================================
timeout /t 3 /nobreak >nul
goto monitor

:stopped
echo.
echo  STATUS : STOPPED
echo  The server could not be started or has been stopped.
echo.
pause
