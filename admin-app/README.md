# ARL Admin

Operator console matching the lab dashboard: target grid, live WebRTC view, connect/screen/control/backup, session log.

Talks to the existing backend. The target device must enroll and accept each remote-support session. Screen viewing uses WebRTC after the target grants MediaProjection. **CONTROL** issues AccessibilityService-backed interaction commands only when the target reports `REMOTE_INTERACTION: AVAILABLE`.

## Run

```powershell
cd D:\AndroidRemoteLab
npm run dev

cd D:\AndroidRemoteLab\admin-app
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:installDebug
# physical device:
.\gradlew.bat :app:installDebug "-Parl.debugApiBaseUrl=http://<lan-ip>:8080"
```

Sign in with a lab operator account (`POST /v1/auth/login` or register). **ENROLL** issues a pairing code for the Target app.

Release API (HTTPS, no secrets in the APK):

```powershell
.\gradlew.bat :app:assembleRelease "-Parl.releaseApiBaseUrl=https://<service>.onrender.com"
```

## Actions

| Button | Behavior |
| --- | --- |
| CONNECT | `POST /v1/sessions` for the selected online target |
| SCREEN | WebRTC offer (`OfferToReceiveVideo`) after the target authenticates |
| CONTROL | Touchpad + Back/Home/Recents/scroll/node/text when accessibility is enabled; reconnect / end session |
| BACKUP | Creates a record; pulls staged files onto this device; shows progress, checksum, quota |

`SERVER CONNECTED` is the authenticated WebSocket to `/v1/ws`. Presence updates come from `device.presence`.
