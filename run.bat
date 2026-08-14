@REM ---------------------------------------------------------------------------
@REM Ticker launcher.
@REM
@REM Delegates to run.ps1 with the execution policy bypassed for this one call,
@REM so it works on a default Windows install without changing any machine
@REM setting. Paths are derived from this file's location rather than hard-coded,
@REM so the repository can live anywhere.
@REM ---------------------------------------------------------------------------
@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run.ps1" %*
if errorlevel 1 (
    echo.
    pause
)
exit /b %ERRORLEVEL%
