@echo off
REM ============================================================
REM  Full build script (Windows)
REM  Points JAVA_HOME to JDK17, then invokes the Maven Wrapper.
REM  This project contains jakarta / springdoc modules
REM  (source/target=17) which require JDK17 to compile.
REM  Core modules remain source=8 (JDK17 is backward compatible).
REM
REM  Usage:
REM    build.cmd                   = clean verify (full build)
REM    build.cmd clean package
REM    build.cmd -pl magic-api test
REM ============================================================

setlocal

if not defined JDK17_HOME set "JDK17_HOME=D:\Environment\Java\jdk-17.0.20.1+1"

if not exist "%JDK17_HOME%\bin\java.exe" (
    echo [ERROR] JDK17 not found at: %JDK17_HOME%
    echo         Install JDK17 or set env var JDK17_HOME to the correct path.
    exit /b 1
)

set "JAVA_HOME=%JDK17_HOME%"
echo [INFO] Using JAVA_HOME=%JAVA_HOME%

if "%~1"=="" (
    call "%~dp0mvnw.cmd" -B --no-transfer-progress clean verify
) else (
    call "%~dp0mvnw.cmd" -B --no-transfer-progress %*
)

exit /b %ERRORLEVEL%
