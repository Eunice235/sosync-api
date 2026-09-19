@echo off
rem Double-click to deploy SOSync to your Oracle server.
rem
rem -ExecutionPolicy Bypass applies to this one run of the script only. It is there because
rem Windows blocks .ps1 files by default, and it changes no system setting.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy.ps1" %*
echo.
pause
