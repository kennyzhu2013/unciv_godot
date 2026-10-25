@echo off
rem Unciv Godot release launcher. Starts the local kernel gateway, then the frontend.
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-unciv.ps1" %*
endlocal
