@echo off
REM ============================================================
REM  magic-api server - stop script (Windows)
REM  Stops the process recorded in magic-api.pid
REM ============================================================
setlocal EnableExtensions EnableDelayedExpansion

set "APP_HOME=%~dp0"
set "APP_HOME=%APP_HOME:~0,-1%"
set "PID_FILE=%APP_HOME%\magic-api.pid"

if not exist "%PID_FILE%" (
    echo [INFO] magic-api is not running ^(no magic-api.pid^)
    exit /b 0
)

set /p PID=<"%PID_FILE%"

call :is_alive %PID%
if errorlevel 1 (
    echo [INFO] magic-api is not running ^(stale PID %PID%^), removing magic-api.pid
    del /q "%PID_FILE%" >nul 2>&1
    exit /b 0
)

echo [INFO] Stopping magic-api, PID=%PID% ...
taskkill /PID %PID% /F >nul
if errorlevel 1 (
    echo [ERROR] taskkill failed. Run this script as Administrator or the user that started the service.
    exit /b 1
)

set /a WAITED=0
:wait_loop
call :is_alive %PID%
if errorlevel 1 goto stopped
set /a WAITED+=1
if !WAITED! GEQ 30 (
    echo [ERROR] Process %PID% still alive after 30s
    exit /b 1
)
ping -n 2 127.0.0.1 >nul
goto wait_loop

:stopped
del /q "%PID_FILE%" >nul 2>&1
echo [INFO] Stopped
exit /b 0

REM errorlevel 0 = a java/javaw process with this PID exists
:is_alive
tasklist /FI "PID eq %~1" /NH 2>nul | findstr /I "java.exe javaw.exe" >nul
exit /b %errorlevel%
