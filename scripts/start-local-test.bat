@echo off
setlocal EnableExtensions EnableDelayedExpansion
rem Local development/demo launcher only. No secrets. Does not change Windows services or firewall.
rem This machine's SDK directory is spelled D:\Andriod\Sdk (not "Android").
rem Consent-based lab: this script never grants MediaProjection, Accessibility, or pairing.

set "CMD=%~1"
set "ARG2=%~2"
if "!CMD!"=="/?" set "CMD=help"
if "!CMD!"=="-h" set "CMD=help"

cd /d "%~dp0.." || (
  echo [ERROR] Could not change directory to the repository root from "%~dp0"
  exit /b 1
)
set "ROOT=%CD%"
set "STATE_DIR=%ROOT%\scripts\.local-test"
set "STATE_FILE=%STATE_DIR%\pids.txt"
set "LOG_DIR=%ROOT%\logs"
if not exist "%STATE_DIR%" mkdir "%STATE_DIR%" >nul 2>&1
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%" >nul 2>&1

set "AVD_NAME=Pixel_7"
set "HEALTH_URL=http://127.0.0.1:8080/health"
set "BACKEND_PORT=8080"
set "PG_PORT=5432"
set "SDK_FALLBACK=D:\Andriod\Sdk"
set "ADMIN_SERIAL=emulator-5554"
set "TARGET_SERIAL=6dd5235d"
set "ADMIN_PKG=lab.arl.admin"
set "TARGET_PKG=lab.arl.target"
set "ADMIN_APK=%ROOT%\admin-app\app\build\outputs\apk\debug\app-debug.apk"
set "TARGET_APK=%ROOT%\target-app\app\build\outputs\apk\debug\app-debug.apk"
set "LAN_DEFAULT=http://10.59.57.35:8080"
set "LAN_URL=%LAN_DEFAULT%"
set "JAVA_FALLBACK=C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"

set "OK_NODE=SKIP"
set "OK_NPM=SKIP"
set "OK_PG=SKIP"
set "OK_BACKEND=SKIP"
set "OK_HEALTH=SKIP"
set "OK_SDK=SKIP"
set "OK_ADB=SKIP"
set "OK_EMU=SKIP"
set "OK_ADMIN_DEV=SKIP"
set "OK_TARGET_DEV=SKIP"
set "OK_ADMIN_ADMIN_APK=SKIP"
set "OK_ADMIN_TARGET_APK=SKIP"
set "OK_TARGET_ADMIN_APK=SKIP"
set "OK_TARGET_TARGET_APK=SKIP"
set "OK_TURN_UDP=SKIP"
set "OK_TURN_TCP=SKIP"
set "OK_TURN_FW=SKIP"
set "LAN_HOST=10.59.57.35"
set "EMU_SERIAL="
set "SDK_ROOT="
set "ADB="
set "EMU="

if "!CMD!"=="" goto :usage
if /I "!CMD!"=="help" goto :usage

call :dispatch
exit /b %ERRORLEVEL%

:dispatch
if /I "!CMD!"=="demo" goto :do_demo
if /I "!CMD!"=="backend" goto :do_backend
if /I "!CMD!"=="emulator" goto :do_emulator
if /I "!CMD!"=="all" goto :do_all
if /I "!CMD!"=="install" goto :do_install
if /I "!CMD!"=="reinstall" goto :do_reinstall
if /I "!CMD!"=="launch" goto :do_launch
if /I "!CMD!"=="status" goto :do_status
if /I "!CMD!"=="logs" goto :do_logs
if /I "!CMD!"=="logs-clear" goto :do_logs_clear
if /I "!CMD!"=="stop" goto :do_stop
if /I "!CMD!"=="build-demo" goto :do_build_demo
if /I "!CMD!"=="e2e" goto :do_e2e
goto :usage

:usage
echo.
echo Usage: start-local-test.bat ^<command^>
echo.
echo   demo         Start/reuse backend, require Admin emulator + Target phone, clean reinstall, launch
echo   backend      Start/check only the Fastify API ^(npm run dev, HOST=0.0.0.0^)
echo   emulator     Start/check only the Pixel_7 AVD
echo   all          Start/check backend and Pixel_7 ^(does not install APKs^)
echo   install      Install both debug APKs (Admin + Target) onto both devices
echo   reinstall    Uninstall, install both APKs onto both devices, launch, print demo sequence
echo   launch       Launch Admin and Target only
echo   status       Backend, PostgreSQL, ADB, packages, process snapshot
echo   logs [target^|admin^|both]   Filtered logcat to console and logs\
echo   logs-clear   Clear logcat on both demo devices
echo   build-demo   assembleDebug Admin+Target, then reinstall
echo   e2e          Full physical-device E2E: build, reinstall, launch, manual test, collect logs
echo   stop         Stop processes this script started ^(not PostgreSQL, not Studio^)
echo.
echo Admin: %ADMIN_SERIAL%  %ADMIN_PKG%
echo Target: %TARGET_SERIAL%  %TARGET_PKG%
echo SDK: %SDK_FALLBACK%
echo Docs: docs\LOCAL_TESTING.md
echo.
echo This launcher never bypasses pairing, MediaProjection, or Accessibility consent.
exit /b 0

:do_demo
call :check_node || exit /b 1
call :check_npm || exit /b 1
call :check_postgres || exit /b 1
call :check_env_file || exit /b 1
call :check_node_modules || exit /b 1
call :ensure_backend || exit /b 1
call :ensure_turn_firewall
call :check_turn_ports
call :resolve_sdk || exit /b 1
call :check_adb || exit /b 1
call :check_emulator_bin
call :check_avd
call :ensure_emulator || exit /b 1
call :require_demo_devices || exit /b 1
call :require_apks || exit /b 1
call :detect_lan
call :reinstall_core 1 || exit /b 1
call :launch_apps || exit /b 1
call :print_demo_ready
exit /b 0

:do_all
call :check_node || exit /b 1
call :check_npm || exit /b 1
call :check_postgres || exit /b 1
call :check_env_file || exit /b 1
call :check_node_modules || exit /b 1
call :ensure_backend || exit /b 1
call :resolve_sdk || exit /b 1
call :check_adb || exit /b 1
call :check_emulator_bin || exit /b 1
call :check_avd || exit /b 1
call :ensure_emulator || exit /b 1
call :print_summary
exit /b 0

:do_backend
call :check_node || exit /b 1
call :check_npm || exit /b 1
call :check_postgres || exit /b 1
call :check_env_file || exit /b 1
call :check_node_modules || exit /b 1
call :ensure_backend || exit /b 1
call :ensure_turn_firewall
call :check_turn_ports
call :detect_lan
call :print_summary
exit /b 0

:do_emulator
call :resolve_sdk || exit /b 1
call :check_adb || exit /b 1
call :check_emulator_bin || exit /b 1
call :check_avd || exit /b 1
call :ensure_emulator || exit /b 1
call :print_summary
exit /b 0

:do_install
call :resolve_sdk || exit /b 1
call :check_adb || exit /b 1
call :require_demo_devices || exit /b 1
call :require_apks || exit /b 1
echo Installing both applications onto %ADMIN_SERIAL% and %TARGET_SERIAL% ...
call :install_one "%ADMIN_SERIAL%" "%ADMIN_PKG%" "%ADMIN_APK%" || exit /b 1
call :install_one "%ADMIN_SERIAL%" "%TARGET_PKG%" "%TARGET_APK%" || exit /b 1
call :install_one "%TARGET_SERIAL%" "%ADMIN_PKG%" "%ADMIN_APK%" || exit /b 1
call :install_one "%TARGET_SERIAL%" "%TARGET_PKG%" "%TARGET_APK%" || exit /b 1
echo [OK] Both packages installed onto both devices. Apps were not launched. Backend was not started.
echo [WARN] If you later run pm clear / reinstall, Target must pair again.
exit /b 0

:do_reinstall
call :resolve_sdk || exit /b 1
call :check_adb || exit /b 1
call :require_demo_devices || exit /b 1
call :require_apks || exit /b 1
call :reinstall_core 1 || exit /b 1
call :launch_apps || exit /b 1
call :detect_lan
call :print_demo_ready
exit /b 0

:do_launch
call :resolve_sdk || exit /b 1
call :check_adb || exit /b 1
call :require_demo_devices || exit /b 1
call :launch_apps || exit /b 1
echo [OK] Launch requested for %ADMIN_PKG% and %TARGET_PKG%.
exit /b 0

:do_status
call :check_node
call :check_npm
call :check_postgres
call :health_once
if "!HEALTH_CODE!"=="200" (
  set "OK_BACKEND=OK"
  set "OK_HEALTH=OK"
) else (
  call :port_in_use && set "OK_BACKEND=FAIL" || set "OK_BACKEND=DOWN"
  set "OK_HEALTH=FAIL"
)
call :check_turn_ports
call :check_turn_fw_status
call :resolve_sdk
if defined ADB (
  call :check_adb
  call :device_state "%ADMIN_SERIAL%"
  if "!DEV_STATE!"=="device" (set "OK_ADMIN_DEV=OK") else (set "OK_ADMIN_DEV=!DEV_STATE!")
  call :device_state "%TARGET_SERIAL%"
  if "!DEV_STATE!"=="device" (set "OK_TARGET_DEV=OK") else (set "OK_TARGET_DEV=!DEV_STATE!")
  call :pkg_state "%ADMIN_SERIAL%" "%ADMIN_PKG%"
  set "OK_ADMIN_ADMIN_APK=!PKG_STATE!"
  call :pkg_state "%ADMIN_SERIAL%" "%TARGET_PKG%"
  set "OK_ADMIN_TARGET_APK=!PKG_STATE!"
  call :pkg_state "%TARGET_SERIAL%" "%ADMIN_PKG%"
  set "OK_TARGET_ADMIN_APK=!PKG_STATE!"
  call :pkg_state "%TARGET_SERIAL%" "%TARGET_PKG%"
  set "OK_TARGET_TARGET_APK=!PKG_STATE!"
)
call :detect_lan
call :print_status
exit /b 0

:do_logs
if "!ARG2!"=="" set "ARG2=both"
call :resolve_sdk || exit /b 1
call :check_adb || exit /b 1
if /I "!ARG2!"=="target" (
  call :dump_logs "%TARGET_SERIAL%" "%TARGET_PKG%" target || exit /b 1
  exit /b 0
)
if /I "!ARG2!"=="admin" (
  call :dump_logs "%ADMIN_SERIAL%" "%ADMIN_PKG%" admin || exit /b 1
  exit /b 0
)
if /I "!ARG2!"=="both" (
  call :dump_logs "%ADMIN_SERIAL%" "%ADMIN_PKG%" admin
  call :dump_logs "%TARGET_SERIAL%" "%TARGET_PKG%" target
  exit /b 0
)
echo [ERROR] logs expects target, admin, or both. Got "!ARG2!"
exit /b 1

:do_logs_clear
call :resolve_sdk || exit /b 1
call :check_adb || exit /b 1
"%ADB%" -s %ADMIN_SERIAL% logcat -c >nul 2>&1
if errorlevel 1 (echo [WARN] Could not clear Admin logcat on %ADMIN_SERIAL%) else (echo [OK] Cleared logcat on %ADMIN_SERIAL%)
"%ADB%" -s %TARGET_SERIAL% logcat -c >nul 2>&1
if errorlevel 1 (echo [WARN] Could not clear Target logcat on %TARGET_SERIAL%) else (echo [OK] Cleared logcat on %TARGET_SERIAL%)
exit /b 0

:do_stop
call :load_state
if defined STARTED_BACKEND_PID (
  echo Stopping backend process tree PID !STARTED_BACKEND_PID! ^(started by this script^)
  taskkill /T /PID !STARTED_BACKEND_PID! >nul 2>&1
  if errorlevel 1 (
    echo [WARN] Backend PID !STARTED_BACKEND_PID! was not running.
  ) else (
    echo [OK] Backend window/process tree stopped.
  )
) else (
  echo Backend was not started by this script; leaving port %BACKEND_PORT% alone.
)
if defined STARTED_EMULATOR_PID (
  echo Stopping emulator process tree PID !STARTED_EMULATOR_PID! ^(started by this script^)
  taskkill /T /PID !STARTED_EMULATOR_PID! >nul 2>&1
  if errorlevel 1 (
    echo [WARN] Emulator PID !STARTED_EMULATOR_PID! was not running.
  ) else (
    echo [OK] Emulator process tree stopped.
  )
) else (
  echo Emulator was not started by this script; not killing ADB devices.
)
if exist "%STATE_FILE%" del /f /q "%STATE_FILE%" >nul 2>&1
echo Done. PostgreSQL, Node you started yourself, and Android Studio are left running.
exit /b 0

:do_build_demo
call :check_node || exit /b 1
call :resolve_sdk || exit /b 1
call :build_apks || exit /b 1
call :do_reinstall
exit /b %ERRORLEVEL%

:require_demo_devices
call :device_state "%ADMIN_SERIAL%"
if not "!DEV_STATE!"=="device" (
  echo [ERROR] Admin emulator %ADMIN_SERIAL% is not ready ^(state=!DEV_STATE!^).
  echo        Start Pixel_7 first: "%ROOT%\scripts\start-local-test.bat" emulator
  echo        Then: "%ADB%" devices
  set "OK_ADMIN_DEV=FAIL"
  exit /b 1
)
set "OK_ADMIN_DEV=OK"
call :device_state "%TARGET_SERIAL%"
if not "!DEV_STATE!"=="device" (
  if /I "!DEV_STATE!"=="unauthorized" (
    echo [ERROR] Target device %TARGET_SERIAL% is UNAUTHORIZED.
    echo        Unlock the phone screen and accept the USB debugging RSA authorization prompt.
    echo        Then verify: "%ADB%" devices
  ) else if /I "!DEV_STATE!"=="offline" (
    echo [ERROR] Target device %TARGET_SERIAL% is OFFLINE.
    echo        Reconnect the USB cable or run: "%ADB%" reconnect
  ) else if defined E2E_MODE (
    echo ERROR: Physical Target device %TARGET_SERIAL% is not connected.
    echo.
    echo Connect the phone with USB debugging enabled and run:
    echo   .\scripts\start-local-test.bat e2e
  ) else (
    echo [ERROR] Target device %TARGET_SERIAL% is not connected ^(state=!DEV_STATE!^).
    echo        Enable USB debugging in Developer Options, connect via USB, and verify:
    echo        "%ADB%" devices
    echo        Expected line: %TARGET_SERIAL%    device
  )
  set "OK_TARGET_DEV=FAIL"
  exit /b 1
)
set "OK_TARGET_DEV=OK"
echo [OK] ADB devices: %ADMIN_SERIAL% device, %TARGET_SERIAL% device
exit /b 0

:device_state
set "DEV_STATE=missing"
if not defined ADB exit /b 1
for /f "tokens=1,2" %%A in ('"%ADB%" devices 2^>nul') do (
  if /I "%%A"=="%~1" set "DEV_STATE=%%B"
)
exit /b 0

:pkg_state
set "PKG_STATE=not-installed"
"%ADB%" -s %~1 shell pm path %~2 >nul 2>&1
if not errorlevel 1 set "PKG_STATE=installed"
exit /b 0

:require_apks
if not exist "%ADMIN_APK%" (
  echo [ERROR] Admin APK not found:
  echo        "%ADMIN_APK%"
  echo        Build first: cd admin-app ^& gradlew.bat assembleDebug
  echo        Or: start-local-test.bat build-demo
  exit /b 1
)
if not exist "%TARGET_APK%" (
  echo [ERROR] Target APK not found:
  echo        "%TARGET_APK%"
  echo        Build first: cd target-app ^& gradlew.bat assembleDebug
  echo        Or: start-local-test.bat build-demo
  exit /b 1
)
echo [OK] Debug APKs exist.
exit /b 0

:uninstall_one
echo Uninstalling %~2 from %~1 ...
"%ADB%" -s %~1 uninstall %~2 >nul 2>&1
"%ADB%" -s %~1 shell pm path %~2 >nul 2>&1
if not errorlevel 1 (
  echo [ERROR] Package %~2 is still installed on %~1 after uninstall.
  exit /b 1
)
echo [OK] %~2 not present on %~1
exit /b 0

:install_one
echo Installing %~2 onto %~1 ...
"%ADB%" -s %~1 install -r -t "%~3"
if errorlevel 1 (
  echo [WARN] install -r -t failed for %~2. Trying clean uninstall then install.
  call :uninstall_one %~1 %~2
  "%ADB%" -s %~1 install -t "%~3"
  if errorlevel 1 (
    echo [ERROR] Install failed for %~2 on %~1
    echo        APK: "%~3"
    exit /b 1
  )
)
"%ADB%" -s %~1 shell pm path %~2 2>nul | findstr /I "package:" >nul
if errorlevel 1 (
  echo [ERROR] pm path %~2 failed on %~1 after install.
  exit /b 1
)
echo [OK] %~2 installed on %~1
exit /b 0

:reinstall_core
echo Reinstalling both applications (%ADMIN_PKG% and %TARGET_PKG%) onto both devices...
call :uninstall_one %ADMIN_SERIAL% %ADMIN_PKG% || exit /b 1
call :uninstall_one %ADMIN_SERIAL% %TARGET_PKG% || exit /b 1
call :uninstall_one %TARGET_SERIAL% %ADMIN_PKG% || exit /b 1
call :uninstall_one %TARGET_SERIAL% %TARGET_PKG% || exit /b 1
call :install_one %ADMIN_SERIAL% %ADMIN_PKG% "%ADMIN_APK%" || exit /b 1
call :install_one %ADMIN_SERIAL% %TARGET_PKG% "%TARGET_APK%" || exit /b 1
call :install_one %TARGET_SERIAL% %ADMIN_PKG% "%ADMIN_APK%" || exit /b 1
call :install_one %TARGET_SERIAL% %TARGET_PKG% "%TARGET_APK%" || exit /b 1
if "%~1"=="1" (
  echo Clearing app data for a deterministic demo ^(pairing tokens are wiped^)...
  "%ADB%" -s %ADMIN_SERIAL% shell pm clear %ADMIN_PKG% >nul 2>&1
  "%ADB%" -s %ADMIN_SERIAL% shell pm clear %TARGET_PKG% >nul 2>&1
  "%ADB%" -s %TARGET_SERIAL% shell pm clear %ADMIN_PKG% >nul 2>&1
  "%ADB%" -s %TARGET_SERIAL% shell pm clear %TARGET_PKG% >nul 2>&1
  echo [WARN] pm clear resets local app state. Pair/enroll Target again before CONNECT.
)
exit /b 0

:launch_apps
call :launch_one %ADMIN_SERIAL% %ADMIN_PKG% || exit /b 1
call :launch_one %TARGET_SERIAL% %TARGET_PKG% || exit /b 1
echo Waiting for activities...
ping -n 4 127.0.0.1 >nul
exit /b 0

:launch_one
echo Launching %~2 on %~1 ...
"%ADB%" -s %~1 shell monkey -p %~2 -c android.intent.category.LAUNCHER 1 >nul 2>&1
if errorlevel 1 (
  "%ADB%" -s %~1 shell monkey -p %~2 1 >nul 2>&1
)
if errorlevel 1 (
  echo [ERROR] Could not launch %~2 on %~1
  exit /b 1
)
echo [OK] Launch sent for %~2
exit /b 0

:dump_logs
call :device_state "%~1"
if not "!DEV_STATE!"=="device" (
  echo [ERROR] Cannot capture logs: %~1 is !DEV_STATE!
  exit /b 1
)
set "STAMP="
for /f %%T in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set "STAMP=%%T"
set "OUT=%LOG_DIR%\%~3-!STAMP!.txt"
echo ----- %~3  %~1  %~2 -----
"%ADB%" -s %~1 logcat -d -v time ARL-WebRTC:I AndroidRuntime:E FATAL:E %~2:I *:S > "%OUT%" 2>nul
type "%OUT%"
echo.
echo [OK] Saved filtered logcat to "%OUT%"
echo      Filters: ARL-WebRTC, AndroidRuntime, %~2  ^(no SDP/ICE secrets are added by this script^)
exit /b 0

:detect_lan
set "LAN_HOST=10.59.57.35"
set "LAN_URL=%LAN_DEFAULT%"
for /f "delims=" %%I in ('powershell -NoProfile -Command "$ips = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object { $_.IPAddress -match '^(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[0-1])\.)' -and $_.IPAddress -ne '10.0.2.2' }; if ($ips) { ($ips | Select-Object -First 1).IPAddress }"') do (
  if not "%%I"=="" (
    set "LAN_HOST=%%I"
    set "LAN_URL=http://%%I:%BACKEND_PORT%"
  )
)
exit /b 0

:print_demo_ready
if "!OK_PG!"=="SKIP" call :check_postgres
if "!OK_HEALTH!"=="SKIP" (
  call :health_once
  if "!HEALTH_CODE!"=="200" (set "OK_HEALTH=OK") else (set "OK_HEALTH=DOWN")
)
call :check_turn_ports
call :check_turn_fw_status
echo.
echo ========================================
echo  AndroidRemoteLab LOCAL DEMO READY
echo ========================================
echo.
echo Backend:
echo   HTTP :%BACKEND_PORT%       !OK_HEALTH!
echo   PostgreSQL       !OK_PG!
if "!OK_TURN_FW!"=="OK" (
  echo   TURN UDP :3478   !OK_TURN_UDP!
  echo   TURN TCP :3478   !OK_TURN_TCP!
) else (
  echo   TURN UDP :3478   !OK_TURN_UDP! ^(Firewall: !OK_TURN_FW!^)
  echo   TURN TCP :3478   !OK_TURN_TCP! ^(Firewall: !OK_TURN_FW!^)
)
echo.
echo Devices:
echo   Admin            %ADMIN_SERIAL%    !OK_ADMIN_DEV!
echo   Target           %TARGET_SERIAL%         !OK_TARGET_DEV!
echo.
echo Applications:
echo   %ADMIN_SERIAL%   %ADMIN_PKG% ^(RUNNING^) + %TARGET_PKG% ^(INSTALLED^)
echo   %TARGET_SERIAL%  %TARGET_PKG% ^(RUNNING^) + %ADMIN_PKG% ^(INSTALLED^)
echo.
echo LAN:
echo   Host             !LAN_HOST!
echo   Target API       !LAN_URL!
echo.
echo Next:
echo   1. Open Admin
echo   2. CONNECT to Target
echo   3. ACCEPT on Target
echo   4. Grant MediaProjection
echo   5. Enable Accessibility if required
echo   6. Live view starts automatically in MONITOR mode
echo   7. Optionally tap MANAGED MODE, interact on screen, then X to return to Monitor
echo.
echo Note: Screen streaming requires explicit user consent on Target.
echo       The launcher only verifies infrastructure/device readiness.
echo.
exit /b 0

:do_e2e
set "E2E_MODE=1"
set "E2E_DIR=%LOG_DIR%\e2e"
if not exist "%E2E_DIR%" mkdir "%E2E_DIR%" >nul 2>&1
call :check_node || exit /b 1
call :check_npm || exit /b 1
call :check_postgres || exit /b 1
call :check_env_file || exit /b 1
call :check_node_modules || exit /b 1
call :resolve_sdk || exit /b 1
call :check_adb || exit /b 1
call :check_emulator_bin || exit /b 1
call :check_avd || exit /b 1
call :ensure_emulator || exit /b 1
call :require_demo_devices || exit /b 1
call :ensure_backend || exit /b 1
call :ensure_turn_firewall
call :check_turn_ports
call :detect_lan
echo.
echo ========================================
echo  E2E PREP — AndroidRemoteLab
echo ========================================
echo [OK] Node/npm, PostgreSQL, backend, TURN checks
echo [OK] Admin %ADMIN_SERIAL%, Target %TARGET_SERIAL%
echo [OK] Target API will use !LAN_URL! ^(override with TARGET_API_URL env^)
echo.
call :build_apks || exit /b 1
call :reinstall_core 1 || exit /b 1
call :restore_target_api || exit /b 1
call :stop_app_processes
call :do_logs_clear
call :launch_apps || exit /b 1
powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%\scripts\e2e-run.ps1" -Root "%ROOT%" -AdminSerial "%ADMIN_SERIAL%" -TargetSerial "%TARGET_SERIAL%" -AdminPkg "%ADMIN_PKG%" -TargetPkg "%TARGET_PKG%" -Adb "%ADB%"
exit /b 0

:build_apks
if exist "%JAVA_FALLBACK%\bin\java.exe" set "JAVA_HOME=%JAVA_FALLBACK%"
set "ANDROID_HOME=%SDK_ROOT%"
set "ANDROID_SDK_ROOT=%SDK_ROOT%"
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
  echo [ERROR] Admin APK missing after build: "%ADMIN_APK%"
  exit /b 1
)
if not exist "%TARGET_APK%" (
  echo [ERROR] Target APK missing after build: "%TARGET_APK%"
  exit /b 1
)
echo [OK] Debug APKs built.
exit /b 0

:restore_target_api
set "API_URL=!LAN_URL!"
if defined TARGET_API_URL set "API_URL=!TARGET_API_URL!"
echo Restoring Target debug API override: !API_URL!
if not exist "%STATE_DIR%" mkdir "%STATE_DIR%" >nul 2>&1
powershell -NoProfile -Command "$url='!API_URL!'.Trim().TrimEnd('/'); $xml='<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?>'+[Environment]::NewLine+'<map>'+[Environment]::NewLine+'    <string name=\"api_base\">'+$url+'</string>'+[Environment]::NewLine+'</map>'+[Environment]::NewLine; Set-Content -Path '%STATE_DIR%\arl_debug_api.xml' -Value $xml -Encoding UTF8"
"%ADB%" -s %TARGET_SERIAL% push "%STATE_DIR%\arl_debug_api.xml" /data/local/tmp/arl_debug_api.xml >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Could not push debug API prefs to Target.
  exit /b 1
)
"%ADB%" -s %TARGET_SERIAL% shell run-as %TARGET_PKG% cp /data/local/tmp/arl_debug_api.xml shared_prefs/arl_debug_api.xml >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Could not install debug API prefs via run-as %TARGET_PKG%.
  echo        Pair with COPY LINK containing api= if this device blocks run-as.
  exit /b 1
)
echo [OK] Target debug API override set to !API_URL!
exit /b 0

:stop_app_processes
echo Stopping stale app processes on both devices ...
"%ADB%" -s %ADMIN_SERIAL% shell am force-stop %ADMIN_PKG% >nul 2>&1
"%ADB%" -s %ADMIN_SERIAL% shell am force-stop %TARGET_PKG% >nul 2>&1
"%ADB%" -s %TARGET_SERIAL% shell am force-stop %ADMIN_PKG% >nul 2>&1
"%ADB%" -s %TARGET_SERIAL% shell am force-stop %TARGET_PKG% >nul 2>&1
echo [OK] force-stop sent for %ADMIN_PKG% and %TARGET_PKG% on both devices
exit /b 0

:print_e2e_manual
echo.
echo ========================================
echo  MANUAL E2E — consent required
echo ========================================
echo.
echo Target ^(%TARGET_SERIAL%^) API: !LAN_URL!
echo Admin ^(%ADMIN_SERIAL%^) uses http://10.0.2.2:%BACKEND_PORT%
echo.
echo Perform on devices:
echo   1. Target: pair/enroll if pm clear wiped state ^(use ENROLL + COPY LINK^)
echo   2. Target: SET UP DEVICE for screen sharing + Accessibility if needed
echo   3. Admin: sign in, select Target, CONNECT
echo   4. Target: ACCEPT session, grant MediaProjection
echo   5. Admin: verify MONITOR mode — live screen, view-only
echo   6. Admin: MANAGED MODE — tap/drag on mirrored screen
echo   7. Admin: press X top-right — returns to Monitor, video stays up
echo   8. Admin: MANAGED MODE again — interaction re-enabled
echo   9. Admin: END SESSION — WebRTC closes, Target returns ONLINE
echo.
echo WebRTC stack is unchanged. Do not press legacy SCREEN/CONTROL buttons.
echo.
exit /b 0

:print_status
echo.
echo --- status ---
echo [!OK_NODE!] Node.js
echo [!OK_NPM!] npm
echo [!OK_PG!] PostgreSQL :%PG_PORT%
echo [!OK_BACKEND!] Backend
echo [!OK_HEALTH!] GET %HEALTH_URL%  code=!HEALTH_CODE!
echo [!OK_TURN_UDP!] TURN UDP :3478
echo [!OK_TURN_TCP!] TURN TCP :3478
echo [!OK_TURN_FW!] TURN Firewall (Profile Any)
echo [!OK_SDK!] SDK
echo [!OK_ADB!] ADB
echo [!OK_ADMIN_DEV!] Admin device %ADMIN_SERIAL%
echo [!OK_TARGET_DEV!] Target device %TARGET_SERIAL%
echo Packages on %ADMIN_SERIAL%:
echo   [!OK_ADMIN_ADMIN_APK!] Admin package %ADMIN_PKG%
echo   [!OK_ADMIN_TARGET_APK!] Target package %TARGET_PKG%
echo Packages on %TARGET_SERIAL%:
echo   [!OK_TARGET_ADMIN_APK!] Admin package %ADMIN_PKG%
echo   [!OK_TARGET_TARGET_APK!] Target package %TARGET_PKG%
if defined ADB (
  echo.
  echo ADB devices:
  "%ADB%" devices
  echo.
  if /I "!OK_ADMIN_DEV!"=="OK" (
    for /f "delims=" %%P in ('"%ADB%" -s %ADMIN_SERIAL% shell pidof %ADMIN_PKG% 2^>nul') do echo Admin pidof %ADMIN_PKG%: %%P
  )
  if /I "!OK_TARGET_DEV!"=="OK" (
    for /f "delims=" %%P in ('"%ADB%" -s %TARGET_SERIAL% shell pidof %TARGET_PKG% 2^>nul') do echo Target pidof %TARGET_PKG%: %%P
  )
)
echo LAN: !LAN_URL!
echo.
exit /b 0

:check_node
where node >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Node.js is not on PATH. Install Node.js 22.x and reopen the terminal.
  echo        Check: where node
  set "OK_NODE=FAIL"
  exit /b 1
)
set "OK_NODE=OK"
exit /b 0

:check_npm
where npm >nul 2>&1
if errorlevel 1 (
  echo [ERROR] npm is not on PATH. It normally ships with Node.js 22.
  echo        Check: where npm
  set "OK_NPM=FAIL"
  exit /b 1
)
set "OK_NPM=OK"
exit /b 0

:check_postgres
powershell -NoProfile -Command "try { $c = New-Object System.Net.Sockets.TcpClient; $c.ReceiveTimeout=2000; $c.SendTimeout=2000; $c.Connect('127.0.0.1',%PG_PORT%); $c.Close(); exit 0 } catch { exit 1 }" >nul 2>&1
if errorlevel 1 (
  echo [ERROR] PostgreSQL is not accepting TCP connections on 127.0.0.1:%PG_PORT%.
  echo        Start the local PostgreSQL Windows service, or:
  echo        docker compose -f "%ROOT%\infrastructure\docker-compose.yml" up -d postgres
  echo        DATABASE_URL stays in .env ^(not in this script^).
  set "OK_PG=FAIL"
  exit /b 1
)
set "OK_PG=OK"
exit /b 0

:check_env_file
if not exist "%ROOT%\.env" (
  echo [ERROR] Missing "%ROOT%\.env". Copy .env.example and set local secrets:
  echo        copy "%ROOT%\.env.example" "%ROOT%\.env"
  echo        Do not commit .env. This launcher never writes credentials.
  exit /b 1
)
exit /b 0

:check_node_modules
if not exist "%ROOT%\node_modules" (
  echo [ERROR] Dependencies are not installed. From the repo root run: npm install
  exit /b 1
)
exit /b 0

:port_in_use
netstat -ano | findstr ":%BACKEND_PORT%" | findstr /I "LISTENING" >nul 2>&1
exit /b %ERRORLEVEL%

:health_once
set "HEALTH_CODE="
for /f "delims=" %%C in ('curl.exe -s -o NUL -w "%%{http_code}" --connect-timeout 3 --max-time 5 "%HEALTH_URL%" 2^>nul') do set "HEALTH_CODE=%%C"
if not defined HEALTH_CODE set "HEALTH_CODE=000"
exit /b 0

:wait_health
set /a "_tries=0"
:wait_health_loop
call :health_once
if "!HEALTH_CODE!"=="200" (
  set "OK_HEALTH=OK"
  set "OK_BACKEND=OK"
  exit /b 0
)
set /a "_tries+=1"
if !_tries! GEQ 30 (
  echo [ERROR] Backend health did not return HTTP 200 at %HEALTH_URL% within ~60 seconds ^(last code !HEALTH_CODE!^).
  echo        Check the "ARL Backend" window for Fastify/Prisma errors.
  set "OK_HEALTH=FAIL"
  exit /b 1
)
ping -n 3 127.0.0.1 >nul
goto :wait_health_loop

:ensure_backend
call :health_once
if "!HEALTH_CODE!"=="200" (
  echo [OK] Backend already healthy at %HEALTH_URL%
  set "OK_BACKEND=OK"
  set "OK_HEALTH=OK"
  exit /b 0
)
call :port_in_use
if not errorlevel 1 (
  echo [ERROR] Port %BACKEND_PORT% is in use but %HEALTH_URL% is not HTTP 200 ^(code !HEALTH_CODE!^).
  echo        Identify: netstat -ano ^| findstr :%BACKEND_PORT%
  echo        Do not start a duplicate API.
  set "OK_BACKEND=FAIL"
  set "OK_HEALTH=FAIL"
  exit /b 1
)
echo Starting backend with existing command: npm run dev
echo HOST=0.0.0.0 is set in the new window so emulator uses http://10.0.2.2:%BACKEND_PORT%
echo and a physical Target can use the LAN URL.
call :load_state
set "STARTED_BACKEND_PID="
for /f %%I in ('powershell -NoProfile -Command "$p = Start-Process -FilePath cmd.exe -WorkingDirectory '%ROOT%' -ArgumentList '/k','%ROOT%\scripts\_run-dev-window.cmd' -PassThru; $p.Id"') do set "STARTED_BACKEND_PID=%%I"
if not defined STARTED_BACKEND_PID (
  echo [ERROR] Could not start npm run dev in a new window.
  exit /b 1
)
call :save_state
echo Backend launcher PID !STARTED_BACKEND_PID! ^(window titled ARL Backend^)
call :wait_health
call :ensure_turn_firewall
exit /b %ERRORLEVEL%

:ensure_turn_firewall
set "OK_TURN_FW=UNKNOWN"
powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%\scripts\open-turn-firewall.ps1" -CheckOnly >nul 2>&1
if not errorlevel 1 (
  echo [OK] TURN firewall rules present, enabled, and cover active network profiles.
  set "OK_TURN_FW=OK"
  exit /b 0
)
echo Checking/updating TURN firewall rules (requires Administrator)...
powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%\scripts\open-turn-firewall.ps1" -ElevateIfNeeded
if not errorlevel 1 (
  echo [OK] TURN firewall rules configured.
  set "OK_TURN_FW=OK"
) else (
  echo [WARN] Administrator privileges are required to configure local TURN firewall rules.
  echo        Active network profile excludes existing rules or rules are missing.
  echo        Physical Target may ICE-fail without typ=relay candidates.
  echo        To fix, open PowerShell as Administrator and run:
  echo        powershell -ExecutionPolicy Bypass -File "%ROOT%\scripts\open-turn-firewall.ps1"
  set "OK_TURN_FW=WARN"
)
exit /b 0

:check_turn_fw_status
powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%\scripts\open-turn-firewall.ps1" -CheckOnly >nul 2>&1
if not errorlevel 1 (
  set "OK_TURN_FW=OK"
) else (
  set "OK_TURN_FW=WARN"
)
exit /b 0

:check_turn_ports
set "OK_TURN_TCP=DOWN"
set "OK_TURN_UDP=DOWN"
netstat -ano | findstr ":3478" | findstr /I "LISTENING" >nul 2>&1
if not errorlevel 1 set "OK_TURN_TCP=OK"
netstat -ano | findstr ":3478" | findstr /I "UDP" >nul 2>&1
if not errorlevel 1 set "OK_TURN_UDP=OK"
if "!OK_TURN_TCP!"=="OK" if "!OK_TURN_UDP!"=="OK" (
  echo [OK] Local TURN listening on UDP :3478 and TCP :3478
) else (
  echo [WARN] TURN listening check: UDP :3478 is !OK_TURN_UDP!, TCP :3478 is !OK_TURN_TCP!
)
exit /b 0

:resolve_sdk
set "SDK_ROOT="
if defined ANDROID_SDK_ROOT if exist "%ANDROID_SDK_ROOT%\platform-tools\adb.exe" set "SDK_ROOT=%ANDROID_SDK_ROOT%"
if not defined SDK_ROOT if defined ANDROID_HOME if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "SDK_ROOT=%ANDROID_HOME%"
if not defined SDK_ROOT if exist "%SDK_FALLBACK%\platform-tools\adb.exe" set "SDK_ROOT=%SDK_FALLBACK%"
if not defined SDK_ROOT (
  echo [ERROR] Android SDK not found. Checked ANDROID_SDK_ROOT, ANDROID_HOME, and %SDK_FALLBACK%
  echo        Expected adb at %SDK_FALLBACK%\platform-tools\adb.exe
  echo        Note the folder spelling Andriod on this machine.
  set "OK_SDK=FAIL"
  exit /b 1
)
set "OK_SDK=OK"
set "ADB=%SDK_ROOT%\platform-tools\adb.exe"
set "EMU=%SDK_ROOT%\emulator\emulator.exe"
exit /b 0

:check_adb
if not defined ADB (
  echo [ERROR] adb.exe path is not set.
  set "OK_ADB=FAIL"
  exit /b 1
)
if not exist "%ADB%" (
  echo [ERROR] adb.exe not found at "%ADB%"
  set "OK_ADB=FAIL"
  exit /b 1
)
"%ADB%" version >nul 2>&1
if errorlevel 1 (
  echo [ERROR] adb.exe exists but failed to run: "%ADB%" version
  set "OK_ADB=FAIL"
  exit /b 1
)
set "OK_ADB=OK"
exit /b 0

:check_emulator_bin
if not exist "%EMU%" (
  echo [ERROR] emulator.exe not found at "%EMU%"
  set "OK_EMU=FAIL"
  exit /b 1
)
exit /b 0

:check_avd
"%EMU%" -list-avds 2>nul | findstr /I /C:"%AVD_NAME%" >nul
if errorlevel 1 (
  echo [ERROR] AVD %AVD_NAME% was not listed by "%EMU%" -list-avds
  set "OK_EMU=FAIL"
  exit /b 1
)
exit /b 0

:avd_lock_exists
if exist "%USERPROFILE%\.android\avd\%AVD_NAME%.avd\hardware-qemu.ini.lock" exit /b 0
exit /b 1

:find_pixel_serial
set "EMU_SERIAL="
if not defined ADB exit /b 1
for /f "tokens=1,2" %%A in ('"%ADB%" devices 2^>nul') do (
  if "%%B"=="device" (
    set "_ser=%%A"
    set "_name="
    for /f "delims=" %%N in ('"%ADB%" -s !_ser! shell getprop qemu.avd.name 2^>nul') do set "_name=%%N"
    if not defined _name for /f "delims=" %%N in ('"%ADB%" -s !_ser! shell getprop ro.kernel.qemu.avd_name 2^>nul') do set "_name=%%N"
    if not defined _name for /f "delims=" %%N in ('"%ADB%" -s !_ser! emu avd name 2^>nul') do set "_name=%%N"
    if /I "!_name!"=="%AVD_NAME%" set "EMU_SERIAL=!_ser!"
    if not defined EMU_SERIAL if /I "!_ser!"=="%ADMIN_SERIAL%" set "EMU_SERIAL=!_ser!"
  )
)
exit /b 0

:ensure_emulator
call :find_pixel_serial
if defined EMU_SERIAL (
  echo [OK] Emulator already present as !EMU_SERIAL!
  set "OK_EMU=OK"
  exit /b 0
)
call :avd_lock_exists
if not errorlevel 1 (
  echo Pixel_7 lock file exists; waiting for ADB instead of starting a second instance.
  call :wait_emulator
  exit /b %ERRORLEVEL%
)
echo Starting AVD %AVD_NAME% using "%EMU%"
call :load_state
set "STARTED_EMULATOR_PID="
for /f %%I in ('powershell -NoProfile -Command "$p = Start-Process -FilePath '%EMU%' -ArgumentList '-avd','%AVD_NAME%' -PassThru; $p.Id"') do set "STARTED_EMULATOR_PID=%%I"
if not defined STARTED_EMULATOR_PID (
  echo [ERROR] Could not launch emulator.exe
  set "OK_EMU=FAIL"
  exit /b 1
)
call :save_state
echo Emulator launcher PID !STARTED_EMULATOR_PID!
call :wait_emulator
exit /b %ERRORLEVEL%

:wait_emulator
set /a "_tries=0"
echo Waiting for ADB device ^(up to ~3 minutes^)...
:wait_emu_loop
"%ADB%" start-server >nul 2>&1
call :find_pixel_serial
if defined EMU_SERIAL (
  "%ADB%" -s !EMU_SERIAL! wait-for-device >nul 2>&1
  "%ADB%" -s !EMU_SERIAL! shell getprop sys.boot_completed 2>nul | findstr /R "1" >nul
  if not errorlevel 1 (
    echo [OK] ADB reports !EMU_SERIAL!    device
    set "OK_EMU=OK"
    exit /b 0
  )
)
set /a "_tries+=1"
if !_tries! GEQ 90 (
  echo [ERROR] Emulator did not become ready.
  echo        ADB: "%ADB%" devices
  set "OK_EMU=FAIL"
  exit /b 1
)
ping -n 3 127.0.0.1 >nul
goto :wait_emu_loop

:load_state
set "STARTED_BACKEND_PID="
set "STARTED_EMULATOR_PID="
if exist "%STATE_FILE%" (
  for /f "usebackq tokens=1,2 delims==" %%A in ("%STATE_FILE%") do (
    if /I "%%A"=="STARTED_BACKEND_PID" set "STARTED_BACKEND_PID=%%B"
    if /I "%%A"=="STARTED_EMULATOR_PID" set "STARTED_EMULATOR_PID=%%B"
  )
)
exit /b 0

:save_state
(
  echo STARTED_BACKEND_PID=!STARTED_BACKEND_PID!
  echo STARTED_EMULATOR_PID=!STARTED_EMULATOR_PID!
) > "%STATE_FILE%"
exit /b 0

:print_summary
if not defined EMU_SERIAL set "EMU_SERIAL=(not running)"
echo.
echo ==========================================
echo ANDROID REMOTE LAB - LOCAL TEST ENVIRONMENT
echo ==========================================
echo.
echo [!OK_NODE!] Node.js
echo [!OK_NPM!] npm
echo [!OK_PG!] PostgreSQL
echo [!OK_BACKEND!] Backend :%BACKEND_PORT%
echo [!OK_HEALTH!] Backend health
echo [!OK_SDK!] Android SDK
echo [!OK_ADB!] ADB
echo [!OK_EMU!] Pixel_7 emulator
echo.
echo Admin emulator:
echo !EMU_SERIAL!
echo.
echo Backend:
echo http://localhost:%BACKEND_PORT%
echo LAN: !LAN_URL!
echo Emulator backend: http://10.0.2.2:%BACKEND_PORT%
echo.
echo Target access stays consent-based ^(pairing, session, MediaProjection, Accessibility^).
echo Commands: "%ROOT%\scripts\start-local-test.bat" demo ^| backend ^| install ^| reinstall ^| launch ^| status ^| logs ^| stop
echo Docs: "%ROOT%\docs\LOCAL_TESTING.md"
echo.
exit /b 0
