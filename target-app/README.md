# Target Android client

Consent-first remote-support client. The device owner enrolls an administrator with a short-lived pairing code, grants MediaProjection at session time, may enable AccessibilityService for remote interaction, can upload user-selected files, and can revoke access.

The backend in this repository is the source of truth for HTTP, WebSocket signaling, pairing, sessions, and backups.

## Requirements

- JDK 17+
- Android SDK 35 (`platforms;android-35`, `build-tools;35.0.0`, `platform-tools`)
- Gradle 8.11.1 (wrapper included)
- Android device or emulator **API 26+** (API 34+ recommended for `mediaProjection` FGS type)
- Running lab API (`npm run dev` in the repo root). Emulator host URL is `http://10.0.2.2:8080`.

## Configure API base URL

Default debug URL: `http://10.0.2.2:8080` (Android emulator → host loopback).

Physical device on the same LAN:

```powershell
cd D:\AndroidRemoteLab\target-app
.\gradlew.bat :app:installDebug -Parl.debugApiBaseUrl=http://192.168.1.10:8080
```

Release builds require HTTPS:

```powershell
.\gradlew.bat :app:assembleRelease -Parl.releaseApiBaseUrl=https://lab.example.com
```

Never put JWT secrets in the app. Device tokens are issued by the backend after explicit confirm and stored in EncryptedSharedPreferences.

## Build / test

```powershell
cd D:\AndroidRemoteLab\target-app
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.*"   # installed OpenJDK 17
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
```

Install on a connected device/emulator:

```powershell
.\gradlew.bat :app:installDebug
```

## Enrollment flow

1. Admin creates a pairing session (`POST /v1/pairing-sessions`) and shows the code / `arl://pair?code=…` QR payload.
2. Target enters or opens that payload.
3. Target **claims** (`POST /v1/pairing/claim`) and shows an explicit confirmation screen.
4. Target **confirms** (`POST /v1/pairing/confirm`) → persistent device tokens.
5. App reconnects later with `POST /v1/devices/me/refresh` / stored access token. The pairing code is not reused.

Deep link: `arl://pair?code=K7M2Q9XA&session=<uuid>`

## Sessions and WebRTC

1. Admin creates a `RemoteSession`.
2. Target receives `session.updated` over `/v1/ws`.
3. User taps **Accept** → `POST /v1/sessions/:id/authenticate`.
4. Target starts a visible foreground service, then the **system MediaProjection** consent activity.
5. On grant: `ScreenCapturerAndroid` + `PeerConnection`, then `POST /v1/sessions/:id/activate`.
6. SDP/ICE are exchanged as `signaling.offer|answer|ice` (never via REST).
7. User can **End remote session** or **Revoke enrollment**. Notifications are not suppressed.

## Accessibility (remote interaction)

The app **never** enables AccessibilityService itself. The owner opens Android Accessibility Settings from **ACCESSIBILITY CONTROL**, enables **ARL Target**, returns, and verifies. Heartbeat then reports `REMOTE_INTERACTION: AVAILABLE`. Disable the service in Settings to revoke. See [docs/INTERACTION.md](../docs/INTERACTION.md).

Authorized backups use the system document picker. Chunks go to the API as staging; the Admin device stores verified payloads. See [docs/BACKUP_STORAGE.md](../docs/BACKUP_STORAGE.md).

## Permissions

| Permission | Why |
| --- | --- |
| `INTERNET` / `ACCESS_NETWORK_STATE` | API + WebSocket |
| `POST_NOTIFICATIONS` | Session / availability notification (API 33+) |
| `FOREGROUND_SERVICE` + `MEDIA_PROJECTION` / `DATA_SYNC` | Visible FGS during capture / enrollment |
| `CAMERA` | Optional QR scan (not required; code entry always works) |

## Architecture

`presentation/` ViewModels + Compose (replaceable UI)  
`domain/` models and operation states  
`data`/`auth/` encrypted credentials  
`network/` Retrofit + OkHttp WebSocket  
`pairing/` `device/` `session/` `media/` `backup/` `telemetry/` `service/` `interaction/`

## Tests

- Unit: pairing parser, session state machine, SHA-256, chunk planner, WS URL mapping, capability detection, command validation
- Networking: MockWebServer claim/confirm/session/backup/error mapping
- Instrumentation: package identity (`androidTest`, needs a device)

## Android limitations

- MediaProjection **cannot** start without the system consent dialog. Denial is handled; video is not sent.
- API 34+ requires starting the `mediaProjection` FGS **before** using the projection token.
- `dataSync` FGS on API 14/15 may be time-limited by the OS; reopen the app to restore presence.
- Emulators often lack hardware H.264 encoders; software encoding is enabled in WebRTC factory.
- Cleartext HTTP is for **debug/lab** only (`10.0.2.2`, localhost). Release expects TLS.

## Known non-goals

No stealth persistence, notification hiding, APK masking, hidden accessibility, or access to other apps’ private storage. Backups use the Storage Access Framework document picker only.
