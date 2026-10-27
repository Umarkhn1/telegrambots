@echo off
rem Запуск двойным щелчком: обходит запрет на .ps1 только для этого скрипта.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-local.ps1"
pause
