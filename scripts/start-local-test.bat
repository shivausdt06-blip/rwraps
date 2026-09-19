@echo off
setlocal EnableExtensions EnableDelayedExpansion
rem AndroidRemoteLab - Test & Deploy Launcher
rem Backend is hosted live on Render: https://andriod-remote-lab.onrender.com

set "CMD=%~1"
set "ARG2=%~2"
if "!CMD!"=="/?" set "CMD=help"
if "!CMD!"=="-h" set "CMD=help"

cd /d "%~dp0.." || (
  echo [ERROR] Could not change directory to repository root.
  exit /b 1
)
set "ROOT=%CD%"
set "LOG_DIR=%ROOT%\logs"
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%" >nul 2>&1

set "BACKEND_URL=https://andriod-remote-lab.onrender.com"
set "HEALTH_URL=%BACKEND_URL%/health"

set "ADMIN_PKG=lab.arl.admin"
set "TARGET_PKG=lab.arl.target"
set "ADMIN_APK=%ROOT%\admin-app\app\build\outputs\apk\debug\app-debug.apk"
set "TARGET_APK=%ROOT%\target-app\app\build\outputs\apk\debug\app-debug.apk"

set "SDK_FALLBACK=D:\Andriod\Sdk"
set "JAVA_FALLBACK=C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
if exist "%JAVA_FALLBACK%\bin\java.exe" set "JAVA_HOME=%JAVA_FALLBACK%"

if not defined ADMIN_SERIAL set "ADMIN_SERIAL=emulator-5554"
if not defined TARGET_SERIAL set "TARGET_SERIAL=6dd5235d"

if "!CMD!"=="" goto :do_e2e
if /I "!CMD!"=="e2e" goto :do_e2e
if /I "!CMD!"=="demo" goto :do_e2e
if /I "!CMD!"=="build" goto :do_build
if /I "!CMD!"=="build-demo" goto :do_e2e
if /I "!CMD!"=="install" goto :do_install
if /I "!CMD!"=="reinstall" goto :do_reinstall
if /I "!CMD!"=="launch" goto :do_launch
if /I "!CMD!"=="backend" goto :do_backend
if /I "!CMD!"=="status" goto :do_status
if /I "!CMD!"=="logs" goto :do_logs
if /I "!CMD!"=="logs-clear" goto :do_logs_clear
if /I "!CMD!"=="stop" goto :do_stop
goto :usage

:usage
echo.
echo ========================================================
echo  AndroidRemoteLab Launcher (Render Backend)
echo ========================================================
echo.
echo Usage: start-local-test.bat [command]
echo.
echo Commands:
echo   (none) / e2e   Verify Render backend, build, install on devices, launch
echo   install        Install existing APKs onto all connected devices
echo   reinstall      Reinstall APKs and clear app state for clean setup
echo   build          Build both APKs (assembleDebug)
echo   launch         Launch Admin and Target on connected devices
echo   backend        Check Render backend health
echo   status         Show backend, device, and package status
echo   logs           Capture filtered logcat to logs\
echo   logs-clear     Clear logcat on connected devices
echo   help           Show this help message
echo.
echo Backend: %BACKEND_URL%
echo.
exit /b 0

:do_e2e
echo.
echo ========================================
echo  AndroidRemoteLab - Deploy & Test
echo ========================================
echo.
call :resolve_sdk || exit /b 1
call :check_adb || exit /b 1
call :ensure_backend
call :build_apks || exit /b 1
call :install_all || exit /b 1
call :launch_apps || exit /b 1
echo.
echo ========================================
echo  APPLICATIONS READY
echo ========================================
echo Backend:  %BACKEND_URL% (LIVE)
echo Target:   PerkDevil (%TARGET_PKG%)
echo Admin:    ARL Admin (%ADMIN_PKG%)
echo.
exit /b 0

:do_build
call :build_apks || exit /b 1
exit /b 0

:do_install
call :resolve_sdk || exit /b 1
call :check_adb || exit /b 1
call :install_all || exit /b 1
exit /b 0

:do_reinstall
call :resolve_sdk || exit /b 1
call :check_adb || exit /b 1
call :reinstall_all || exit /b 1
call :launch_apps || exit /b 1
exit /b 0

:do_launch
call :resolve_sdk || exit /b 1
call :check_adb || exit /b 1
call :launch_apps || exit /b 1
exit /b 0

:do_backend
call :ensure_backend
exit /b 0

:do_status
echo.
echo --- Status Check ---
call :ensure_backend
call :resolve_sdk
if defined ADB (
  echo.
  echo Connected ADB Devices:
  "%ADB%" devices
  echo.
  for /f "skip=1 tokens=1,2" %%A in ('"%ADB%" devices 2^>nul') do (
    if "%%B"=="device" (
      echo --- Device %%A ---
      "%ADB%" -s %%A shell pm path %TARGET_PKG% < nul 2>nul ^| findstr /I "package:" >nul
      if not errorlevel 1 (echo   Target: %TARGET_PKG% INSTALLED) else (echo   Target: %TARGET_PKG% NOT INSTALLED)
      "%ADB%" -s %%A shell pm path %ADMIN_PKG% < nul 2>nul ^| findstr /I "package:" >nul
      if not errorlevel 1 (echo   Admin:  %ADMIN_PKG% INSTALLED) else (echo   Admin:  %ADMIN_PKG% NOT INSTALLED)
    )
  )
)
echo.
exit /b 0

:do_logs
call :resolve_sdk || exit /b 1
call :check_adb || exit /b 1
set "STAMP="
for /f %%T in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set "STAMP=%%T"
for /f "skip=1 tokens=1,2" %%A in ('"%ADB%" devices 2^>nul') do (
  if "%%B"=="device" (
    set "OUT=%LOG_DIR%\logcat-%%A-!STAMP!.txt"
    echo Saving logcat for %%A to !OUT! ...
    "%ADB%" -s %%A logcat -d -v time ARL-WebRTC:I AndroidRuntime:E FATAL:E %TARGET_PKG%:I %ADMIN_PKG%:I *:S > "!OUT!" 2>nul
  )
)
echo [OK] Logs saved to %LOG_DIR%
exit /b 0

:do_logs_clear
call :resolve_sdk || exit /b 1
call :check_adb || exit /b 1
for /f "skip=1 tokens=1,2" %%A in ('"%ADB%" devices 2^>nul') do (
  if "%%B"=="device" (
    "%ADB%" -s %%A logcat -c >nul 2>&1
    echo [OK] Cleared logcat on %%A
  )
)
exit /b 0

:do_stop
echo Nothing to stop - backend is hosted live on Render (%BACKEND_URL%).
exit /b 0

:ensure_backend
echo Checking Render backend: %HEALTH_URL% ...
set /a "_tries=0"
:health_loop
powershell -NoProfile -Command "try { $r = [System.Net.WebRequest]::Create('%HEALTH_URL%').GetResponse(); if ([int]$r.StatusCode -eq 200) { $r.Close(); exit 0 } else { $r.Close(); exit 1 } } catch { exit 1 }" >nul 2>&1
if not errorlevel 1 (
  echo [OK] Backend is live at %BACKEND_URL%
  exit /b 0
)
set /a "_tries+=1"
if !_tries! GEQ 20 (
  echo [WARN] Backend did not respond within ~60s. Proceeding with deployment...
  exit /b 0
)
echo [INFO] Waking up Render backend... (attempt !_tries!/20)
ping -n 4 127.0.0.1 >nul
goto :health_loop

:build_apks
echo.
echo Building Admin assembleDebug ...
pushd "%ROOT%\admin-app" || exit /b 1
call gradlew.bat assembleDebug --no-daemon
set "_rc=!ERRORLEVEL!"
popd
if not "!_rc!"=="0" (
  echo [ERROR] Admin assembleDebug failed.
  exit /b 1
)

echo Building Target assembleDebug ...
pushd "%ROOT%\target-app" || exit /b 1
call gradlew.bat assembleDebug --no-daemon
set "_rc=!ERRORLEVEL!"
popd
if not "!_rc!"=="0" (
  echo [ERROR] Target assembleDebug failed.
  exit /b 1
)

if not exist "%ADMIN_APK%" (
  echo [ERROR] Admin APK missing: "%ADMIN_APK%"
  exit /b 1
)
if not exist "%TARGET_APK%" (
  echo [ERROR] Target APK missing: "%TARGET_APK%"
  exit /b 1
)
echo [OK] Both debug APKs built successfully.
exit /b 0

:install_all
echo.
echo Installing latest applications on connected devices...
set /a "_dev_count=0"
for /f "skip=1 tokens=1,2" %%A in ('"%ADB%" devices 2^>nul') do (
  if "%%B"=="device" (
    set /a "_dev_count+=1"
    echo.
    echo --- Device: %%A ---
    echo Installing Admin: %ADMIN_PKG% ...
    "%ADB%" -s %%A install -r -t "%ADMIN_APK%"
    echo Installing Target: %TARGET_PKG% ...
    "%ADB%" -s %%A install -r -t "%TARGET_APK%"
  )
)
if !_dev_count! EQU 0 (
  echo [ERROR] No active ADB devices connected.
  echo        Connect a phone via USB or start an emulator, then try again.
  exit /b 1
)
echo.
echo [OK] Applications installed on !_dev_count! device(s).
exit /b 0

:reinstall_all
echo.
echo Clean reinstalling applications on connected devices...
set /a "_dev_count=0"
for /f "skip=1 tokens=1,2" %%A in ('"%ADB%" devices 2^>nul') do (
  if "%%B"=="device" (
    set /a "_dev_count+=1"
    echo.
    echo --- Device: %%A ---
    "%ADB%" -s %%A uninstall %ADMIN_PKG% >nul 2>&1
    "%ADB%" -s %%A uninstall %TARGET_PKG% >nul 2>&1
    echo Installing Admin: %ADMIN_PKG% ...
    "%ADB%" -s %%A install -r -t "%ADMIN_APK%"
    echo Installing Target: %TARGET_PKG% ...
    "%ADB%" -s %%A install -r -t "%TARGET_APK%"
  )
)
if !_dev_count! EQU 0 (
  echo [ERROR] No active ADB devices connected.
  exit /b 1
)
echo.
echo [OK] Clean reinstalled on !_dev_count! device(s).
exit /b 0

:launch_apps
echo.
echo Launching applications...
for /f "skip=1 tokens=1,2" %%A in ('"%ADB%" devices 2^>nul') do (
  if "%%B"=="device" (
    echo Launching on %%A ...
    "%ADB%" -s %%A shell monkey -p %TARGET_PKG% -c android.intent.category.LAUNCHER 1 < nul >nul 2>&1
    "%ADB%" -s %%A shell monkey -p %ADMIN_PKG% -c android.intent.category.LAUNCHER 1 < nul >nul 2>&1
  )
)
echo [OK] Launch sent.
exit /b 0

:resolve_sdk
set "SDK_ROOT="
if defined ANDROID_SDK_ROOT if exist "%ANDROID_SDK_ROOT%\platform-tools\adb.exe" set "SDK_ROOT=%ANDROID_SDK_ROOT%"
if not defined SDK_ROOT if defined ANDROID_HOME if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "SDK_ROOT=%ANDROID_HOME%"
if not defined SDK_ROOT if exist "%SDK_FALLBACK%\platform-tools\adb.exe" set "SDK_ROOT=%SDK_FALLBACK%"
if not defined SDK_ROOT (
  echo [ERROR] Android SDK not found. Expected adb at %SDK_FALLBACK%\platform-tools\adb.exe
  exit /b 1
)
set "ADB=%SDK_ROOT%\platform-tools\adb.exe"
exit /b 0

:check_adb
"%ADB%" start-server >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Could not start ADB server at "%ADB%"
  exit /b 1
)
exit /b 0
