@REM ---------------------------------------------------------------------------
@REM Apache Maven Wrapper (Windows)
@REM
@REM Runs Maven without requiring Maven to be installed. The first invocation
@REM downloads the distribution pinned in .mvn\wrapper\maven-wrapper.properties
@REM into %USERPROFILE%\.m2\wrapper\dists and verifies its SHA-512; later runs
@REM reuse it and never touch the network.
@REM
@REM Usage:  mvnw.cmd clean compile
@REM         mvnw.cmd exec:java
@REM ---------------------------------------------------------------------------
@echo off
setlocal

set "MVNW_INSTALLER=%~dp0.mvn\wrapper\install-maven.ps1"

if not exist "%MVNW_INSTALLER%" (
    echo [mvnw] ERROR: missing "%MVNW_INSTALLER%" 1>&2
    exit /b 1
)

set "MVNW_HOME="
for /f "usebackq delims=" %%i in (`powershell -NoProfile -ExecutionPolicy Bypass -File "%MVNW_INSTALLER%"`) do set "MVNW_HOME=%%i"

if not defined MVNW_HOME (
    echo [mvnw] ERROR: could not provision Apache Maven. See the messages above. 1>&2
    exit /b 1
)

if not exist "%MVNW_HOME%\bin\mvn.cmd" (
    echo [mvnw] ERROR: "%MVNW_HOME%\bin\mvn.cmd" does not exist. 1>&2
    exit /b 1
)

call "%MVNW_HOME%\bin\mvn.cmd" %*
exit /b %ERRORLEVEL%
