@echo off
setlocal EnableExtensions EnableDelayedExpansion
rem AndroidRemoteLab Master Demo Reset & Launcher
rem Automates complete local demo environment preparation from scratch.
rem Usage:
rem   .\scripts\demo-reset.bat            Full clean install + backend readiness + launch
rem   .\scripts\demo-reset.bat build      Build both APKs + full demo setup
rem   .\scripts\demo-reset.bat backend    Only start/reuse backend and verify health
rem   .\scripts\demo-reset.bat install    Install/reinstall both APKs onto both devices
rem   .\scripts\demo-reset.bat launch     Only launch both already-installed apps
rem   .\scripts\demo-reset.bat status     Show backend/device/app/TURN status
rem   .\scripts\demo-reset.bat stop       Stop only processes started by the launcher

cd /d "%~dp0.." || (
  echo [ERROR] Could not change directory to repository root from "%~dp0"
  exit /b 1
)
set "ROOT=%CD%"
set "CMD=%~1"
if "!CMD!"=="" set "CMD=demo"
if "!CMD!"=="/?" goto :usage
if "!CMD!"=="-h" goto :usage
if "!CMD!"=="help" goto :usage

if /I "!CMD!"=="demo" goto :run_demo
if /I "!CMD!"=="build" goto :run_build
if /I "!CMD!"=="backend" goto :run_backend
if /I "!CMD!"=="install" goto :run_install
if /I "!CMD!"=="launch" goto :run_launch
if /I "!CMD!"=="status" goto :run_status
if /I "!CMD!"=="stop" goto :run_stop
if /I "!CMD!"=="reinstall" goto :run_reinstall
goto :usage

:run_demo
call "%ROOT%\scripts\start-local-test.bat" demo
exit /b %ERRORLEVEL%

:run_build
call "%ROOT%\scripts\start-local-test.bat" build-demo
exit /b %ERRORLEVEL%

:run_backend
call "%ROOT%\scripts\start-local-test.bat" backend
exit /b %ERRORLEVEL%

:run_install
call "%ROOT%\scripts\start-local-test.bat" reinstall
exit /b %ERRORLEVEL%

:run_launch
call "%ROOT%\scripts\start-local-test.bat" launch
exit /b %ERRORLEVEL%

:run_status
call "%ROOT%\scripts\start-local-test.bat" status
exit /b %ERRORLEVEL%

:run_stop
call "%ROOT%\scripts\start-local-test.bat" stop
exit /b %ERRORLEVEL%

:run_reinstall
call "%ROOT%\scripts\start-local-test.bat" reinstall
exit /b %ERRORLEVEL%

:usage
echo.
echo ========================================================
echo  AndroidRemoteLab - Master Demo Reset ^& Launcher
echo ========================================================
echo.
echo Usage: demo-reset.bat [command]
echo.
echo Commands:
echo   (none)    Full clean install + backend readiness + launch
echo   build     Build both APKs (assembleDebug) + full demo setup
echo   backend   Start/reuse backend only, verify HTTP and TURN ports
echo   install   Clean reinstall both APKs onto both devices and clear app state
echo   launch    Launch both already-installed apps
echo   status    Show backend, PostgreSQL, TURN, devices, and app status
echo   stop      Stop only processes started by the launcher
echo   help      Show this help message
echo.
echo Devices:
echo   Admin:    emulator-5554 (Pixel_7)
echo   Target:   6dd5235d (OnePlus physical device)
echo   Apps:     Both lab.arl.admin and lab.arl.target installed on both devices
echo.
echo Documentation: docs\LOCAL_TESTING.md
echo.
exit /b 0
