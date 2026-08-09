@echo off
chcp 65001 >nul
title Stop DN Photo Sync Server
taskkill /IM "DN-Photo-Server-NEW.exe" /T /F >nul 2>&1
taskkill /IM "DN-Photo-Server-V2.exe" /T /F >nul 2>&1
taskkill /IM "DN-Photo-Server-V3.exe" /T /F >nul 2>&1
taskkill /IM "DN-Photo-Server-V4.exe" /T /F >nul 2>&1
taskkill /IM "DN-Photo-Server-V5.exe" /T /F >nul 2>&1
taskkill /IM "DN-Photo-Server.exe" /T /F >nul 2>&1
echo.
echo  DN Photo Sync Server has been stopped.
echo.
timeout /t 2 /nobreak >nul
