@echo off
REM ============================================================
REM  magic-api server - start script (Windows)
REM
REM  Usage:
REM    start.cmd          start on the port configured below
REM    start.cmd 8080     start on port 8080
REM
REM  The service runs in the background (javaw). Logs: logs\magic-api.log
REM  Stop it with stop.cmd.
REM ============================================================
setlocal EnableExtensions EnableDelayedExpansion

REM ---------------- settings (edit here) ----------------
REM JDK17 used ONLY by this service (the system JDK8 is not used)
set "JDK17_HOME=C:\Program Files\Java\jdk-17"
REM HTTP port
set "SERVER_PORT=9999"
REM JVM options
set "JAVA_OPTS=-Xms512m -Xmx1024m"
REM Seconds to wait for the port to open before giving up
set "START_TIMEOUT=120"

REM ---- SkyWalking APM (optional) ----
REM Set SW_ENABLE=1 to attach the SkyWalking Java Agent at startup.
REM Leave SW_ENABLE empty (default) to start WITHOUT SkyWalking.
REM The agent jar is NOT bundled; download it from the SkyWalking release
REM package. By default we look for skywalking-agent\skywalking-agent.jar
REM next to this script; override with env var SW_AGENT (full path).
REM Service name shown in the SkyWalking UI (keep it unique and stable)
if not defined SW_SERVICE_NAME set "SW_SERVICE_NAME=magic-api-server"
REM SkyWalking OAP backend (gRPC), must be reachable from this host
if not defined SW_BACKEND set "SW_BACKEND=127.0.0.1:11800"
REM -------------------------------------------------------

if not "%~1"=="" set "SERVER_PORT=%~1"

echo %SERVER_PORT%| findstr /R "^[0-9][0-9]*$" >nul
if errorlevel 1 (
    echo [ERROR] Invalid port: %SERVER_PORT%
    exit /b 1
)
if %SERVER_PORT% LSS 1 goto bad_port
if %SERVER_PORT% GTR 65535 goto bad_port

set "APP_HOME=%~dp0"
set "APP_HOME=%APP_HOME:~0,-1%"
cd /d "%APP_HOME%"

set "APP_JAR=%APP_HOME%\magic-api-server.jar"
set "PID_FILE=%APP_HOME%\magic-api.pid"
set "JAVAW=%JDK17_HOME%\bin\javaw.exe"

REM ---- build SkyWalking agent options (only when SW_ENABLE=1) ----
set "SW_OPTS="
if not defined SW_AGENT set "SW_AGENT=%APP_HOME%\skywalking-agent\skywalking-agent.jar"
if "%SW_ENABLE%"=="1" (
    if exist "!SW_AGENT!" (
        set SW_OPTS=-javaagent:"!SW_AGENT!" -Dskywalking.agent.service_name=!SW_SERVICE_NAME! -Dskywalking.collector.backend_service=!SW_BACKEND!
        echo [INFO] SkyWalking: ENABLED
        echo [INFO]   agent  : !SW_AGENT!
        echo [INFO]   service: !SW_SERVICE_NAME!
        echo [INFO]   backend: !SW_BACKEND!
    ) else (
        echo [WARN] SW_ENABLE=1 but agent jar not found: !SW_AGENT!
        echo [WARN]        Starting WITHOUT SkyWalking.
    )
)

if not exist "%JAVAW%" (
    echo [ERROR] JDK17 not found: %JDK17_HOME%
    echo         Edit JDK17_HOME at the top of %~nx0
    exit /b 1
)
if not exist "%APP_JAR%" (
    echo [ERROR] Jar not found: %APP_JAR%
    exit /b 1
)

REM ---- already running? ----
if exist "%PID_FILE%" (
    set /p OLD_PID=<"%PID_FILE%"
    call :is_alive !OLD_PID!
    if not errorlevel 1 (
        echo [ERROR] magic-api is already running, PID=!OLD_PID!. Run stop.cmd first.
        exit /b 1
    )
    del /q "%PID_FILE%" >nul 2>&1
)

REM ---- port free? ----
call :is_listening
if not errorlevel 1 (
    echo [ERROR] Port %SERVER_PORT% is already in use:
    netstat -ano | findstr /C:":%SERVER_PORT% " | findstr LISTENING
    exit /b 1
)

if not exist "%APP_HOME%\logs" mkdir "%APP_HOME%\logs"

echo [INFO] JDK      : %JDK17_HOME%
echo [INFO] Port     : %SERVER_PORT%
echo [INFO] Home     : %APP_HOME%
echo [INFO] Starting...

start "magic-api" /b "%JAVAW%" %JAVA_OPTS% %SW_OPTS% -Dfile.encoding=UTF-8 -jar "%APP_JAR%" --server.port=%SERVER_PORT% >>"%APP_HOME%\logs\console.log" 2>&1

REM ---- wait until the port is listening ----
set /a WAITED=0
:wait_loop
ping -n 3 127.0.0.1 >nul
set /a WAITED+=2

call :is_listening
if not errorlevel 1 goto started

if exist "%PID_FILE%" (
    set /p NEW_PID=<"%PID_FILE%"
    call :is_alive !NEW_PID!
    if errorlevel 1 goto failed
) else (
    REM PID file is written a few seconds after JVM start; if it never appears the JVM exited
    if !WAITED! GEQ 30 goto failed
)

if !WAITED! GEQ %START_TIMEOUT% (
    echo [WARN] Port %SERVER_PORT% not open after %START_TIMEOUT%s. Still starting or stuck, check logs\magic-api.log
    exit /b 1
)
goto wait_loop

:started
set /p NEW_PID=<"%PID_FILE%"
echo [INFO] Started in about %WAITED%s, PID=%NEW_PID%
echo [INFO] Editor   : http://localhost:%SERVER_PORT%/magic/web
echo [INFO] Logs     : %APP_HOME%\logs\magic-api.log
exit /b 0

:failed
echo [ERROR] magic-api failed to start. Check:
echo         %APP_HOME%\logs\console.log
echo         %APP_HOME%\logs\magic-api.log
exit /b 1

:bad_port
echo [ERROR] Port must be 1-65535: %SERVER_PORT%
exit /b 1

REM errorlevel 0 = a java/javaw process with this PID exists
:is_alive
tasklist /FI "PID eq %~1" /NH 2>nul | findstr /I "java.exe javaw.exe" >nul
exit /b %errorlevel%

REM errorlevel 0 = something listens on SERVER_PORT
:is_listening
netstat -ano | findstr /C:":%SERVER_PORT% " | findstr LISTENING >nul
exit /b %errorlevel%
