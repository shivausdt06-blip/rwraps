# Local testing

This is a **local development** runbook. It does not change pairing, session authorization, MediaProjection, Accessibility, backup transfer, or audit behavior. The Target app remains consent-based: the device owner enrolls an admin and grants session permissions.

Do not put passwords, JWT secrets, or database credentials in the launcher. Those stay in `.env` (gitignored).

The launcher never grants MediaProjection, Accessibility, or pairing. Android system consent UI must appear when those features are used.

## Prerequisites

- Windows (CMD or PowerShell)
- Node.js 22.x and npm on `PATH`
- PostgreSQL listening on `127.0.0.1:5432` (local service or Docker Compose)
- Repository `.env` copied from `.env.example` with unique 32+ character secrets
- `npm install` already run at the repo root
- Android SDK on this machine: **`D:\Andriod\Sdk`** (spelling is intentional; do not assume `D:\Android\Sdk`). Override with `ANDROID_SDK_ROOT` or `ANDROID_HOME` if those point at a working SDK.
- ADB: `D:\Andriod\Sdk\platform-tools\adb.exe`
- `D:\Andriod\Sdk\emulator\emulator.exe` (needed only for `emulator` / `all`)
- AVD named **`Pixel_7`** (Android 15 / API 35 / x86_64) for the Admin emulator
- Debug APKs built **before** `demo` / `install` / `reinstall` (the demo command does **not** run Gradle)

## Device IDs and packages

| Role | ADB serial | Package |
| --- | --- | --- |
| Admin (emulator) | `emulator-5554` | `lab.arl.admin` |
| Target (physical) | `6dd5235d` | `lab.arl.target` |

APKs:

- Admin: `D:\AndroidRemoteLab\admin-app\app\build\outputs\apk\debug\app-debug.apk`
- Target: `D:\AndroidRemoteLab\target-app\app\build\outputs\apk\debug\app-debug.apk`

If a serial is unauthorized or offline, the script stops. It does not pick a “first available” device.

## Backend addresses

| Who | URL |
| --- | --- |
| Health / browser on the PC | `http://localhost:8080/health` |
| Fastify bind | `0.0.0.0:8080` |
| Physical Target on LAN | `http://10.59.57.35:8080` (or the NIC the launcher detects) |
| Admin **inside** the emulator | `http://10.0.2.2:8080` |

`10.0.2.2` is the emulator alias for the host. A USB Target cannot use `10.0.2.2`. Use Admin **COPY LINK** so the pairing URI includes `api=` pointing at the laptop LAN address.

## One-command demo

Build APKs in Cursor/Gradle when code changes, then:

```bat
.\scripts\demo-reset.bat
```

Or using `start-local-test.bat`:

```bat
.\scripts\start-local-test.bat demo
```

From the repo root (CMD or PowerShell). Equivalent:

```bat
D:\AndroidRemoteLab\scripts\demo-reset.bat
```

`demo` does **not** assemble Gradle. Order:

1. Check node, npm, ADB (`D:\Andriod\Sdk`), PostgreSQL `:5432`, backend health, both ADB devices.
2. Start backend with existing `npm run dev` **only if** `GET /health` is not already HTTP 200. No duplicate API.
3. Require `emulator-5554` and `6dd5235d` in `device` state.
4. Uninstall then install both debug APKs; verify `pm path`.
5. `pm clear` both packages so the demo is deterministic. **Pair/enroll Target again.** Backend/PostgreSQL enrollment history is not wiped.
6. Launch both apps (`monkey` + LAUNCHER category).
7. Print **DEMO READY** and the operator sequence.

## Supported commands

With no argument, the script prints this list and exits 0.

| Command | Effect |
| --- | --- |
| `demo` | Backend reuse/start, device checks, clean reinstall, `pm clear`, launch, demo sequence |
| `backend` | Start/check Fastify only (`HOST=0.0.0.0`) |
| `emulator` | Start/check Pixel_7 AVD only |
| `all` | Backend + Pixel_7 (does not install APKs) |
| `install` | Install APKs if they exist; no backend start; no launch |
| `reinstall` | Uninstall, install, `pm clear`, launch, print demo sequence |
| `launch` | Launch Admin and Target only |
| `status` | Backend, PostgreSQL TCP, ADB, packages, pid/resumed activity if available |
| `logs [target\|admin\|both]` | Filtered logcat to console and `logs\` |
| `logs-clear` | `logcat -c` on both demo devices |
| `build-demo` | `assembleDebug` Admin then Target; fail visibly; then `reinstall` |
| `e2e` | Build both APKs, clean reinstall, restore Target LAN API override, launch, pause for manual consent/E2E, collect `logs\e2e\` artifacts |
| `stop` | Stop **only** PIDs recorded in `scripts\.local-test\` (not Studio, not arbitrary Node) |

## One-command physical-device E2E

Standard workflow when the physical Target (`6dd5235d`) is connected:

```bat
.\scripts\start-local-test.bat e2e
```

The `e2e` command automates infrastructure and build steps:

1. Checks Node/npm, ADB, Admin emulator, physical Target, PostgreSQL, backend, TURN
2. Builds Admin and Target debug APKs
3. Uninstalls/reinstalls both apps and `pm clear` (same as `reinstall`)
4. Restores the Target debug API override to the detected LAN URL (override with `TARGET_API_URL` env, e.g. `http://192.168.1.18:8080`)
5. Clears logcat, stops stale app processes, launches both apps
6. Prints the manual consent/session checklist and pauses
7. After you press ENTER, saves artifacts under `logs\e2e\`:
   - `admin.log`, `target.log`, `backend.log`, `admin-final.png`
8. Prints an evidence-based summary (PASS only when log signals support it; otherwise `MANUAL VERIFICATION REQUIRED`)

If the physical Target is disconnected, the script exits with:

```
ERROR: Physical Target device 6dd5235d is not connected.
```

Connect USB debugging and rerun `.\scripts\start-local-test.bat e2e`.

The script never bypasses MediaProjection, Accessibility, pairing, or session consent.

## Normal demo sequence (after `demo` or `e2e`)

Product flow:

```
ENROLLED → ONLINE → CONNECT → MONITOR (auto) → MANAGED (optional) → ✕ → MONITOR → END SESSION → ONLINE
```

1. Target: open/confirm Target app  
2. Target: enroll/pair if required (`pm clear` wiped local tokens)  
3. Target: **SET UP DEVICE** — grant screen sharing (MediaProjection) and Accessibility when prompted  
4. Admin: select Target  
5. Admin: **CONNECT** — Monitor mode starts automatically; live view begins after Target accepts and grants capture  
6. Target: **ACCEPT** session and grant Android **screen-capture** (MediaProjection) if prompted  
7. Admin: verify **MONITOR** — live Target screen, view-only (touch on mirror does nothing)  
8. Admin: **MANAGED MODE** — interact directly on the mirrored screen (tap/drag)  
9. Admin: press **✕** (top right) — returns to Monitor; video and session stay active  
10. Admin: **MANAGED MODE** again — interaction re-enabled  
11. Admin: **END SESSION** — WebRTC closes; Target returns ONLINE  

Screen capture requires explicit Android user consent. Accessibility must be enabled explicitly for managed interaction. This lab does not suppress those system dialogs.

## Logs

Filtered tags: `ARL-WebRTC`, `AndroidRuntime`, `FATAL EXCEPTION` (via `AndroidRuntime`/`FATAL`), and the app package. Unrelated OEM spam is silenced (`*:S`).

```bat
.\scripts\start-local-test.bat logs target
.\scripts\start-local-test.bat logs admin
.\scripts\start-local-test.bat logs both
.\scripts\start-local-test.bat logs-clear
```

Dumps are saved under `D:\AndroidRemoteLab\logs\` with timestamped names. The script does not write tokens, passwords, SDP, or ICE credentials.

## How to stop

```bat
.\scripts\start-local-test.bat stop
```

Kills the backend window and emulator **only if this script started them**.

Manual:

- Close the **ARL Backend** window
- Emulator: close the window, or `"D:\Andriod\Sdk\platform-tools\adb.exe" -s emulator-5554 emu kill`
- PostgreSQL: leave running unless you intend to stop it

## WebRTC / live view (emulator Admin + physical Target)

Control uses the WebSocket, not ICE. Screen mirroring uses WebRTC UDP.

The Admin emulator lives on `10.0.2.x`. The physical Target lives on the LAN. Host ICE candidates cannot connect those two networks, so **STUN-only sessions ICE-fail** (~15s → `ACTIVE • WebRTC FAILED`) even when `onTrack` fires and control works.

In local `npm run dev`, if `TURN_URLS` is empty the API starts an **embedded TURN** on `0.0.0.0:3478` and `GET /v1/ice-servers` advertises:

- `turn:<LAN-IP>:3478` for the phone
- `turn:10.0.2.2:3478` for the emulator

Windows Firewall must allow **UDP 3478**, **TCP 3478**, and **UDP 49152–49200** (TURN relay) from the phone. This launcher does not change the firewall.

Do not treat `WebRTC FAILED` as a UI bug. It is the PeerConnection state. After ICE fails, end the session and reconnect only after TURN is listening.

Blank LIVE DEVICE VIEW after ICE connects is a renderer problem. Until ICE is `CONNECTED`/`COMPLETED` and `first_frame_received` appears, do not treat it as a Compose overlay bug.

If Admin shows **WebRTC CLOSED** after **CONNECTING**, check `ARL-WebRTC` for the event immediately before close:

```
ice_state FAILED
pc_failed_reset
close_requested reason=ice_failed
closed_callback
```

That means ICE timed out (~16s), not a renderer bug. Admin often has `typ=relay`; the physical Target must also log `relay_candidate_found`. If Target only sends `typ=host` / `typ=srflx`, open the firewall (Administrator PowerShell):

```powershell
powershell -ExecutionPolicy Bypass -File D:\AndroidRemoteLab\scripts\open-turn-firewall.ps1
```

`start-local-test.bat backend` / `demo` warns when firewall rules are missing.

## Physical Target pairing (LAN)

Admin on Pixel_7 still talks to `http://10.0.2.2:8080`. On ENROLL, the backend puts a Target-facing `api=` on `arl://pair?code=&session=&api=`. For local Node it advertises a private IPv4 (often `http://10.59.57.35:8080`). COPY LINK copies that URI. Typing only the 8-character code still uses the APK’s compiled base (`10.0.2.2` on emulator Target).

If the advertised IP is the wrong NIC, set `PUBLIC_BASE_URL=http://10.59.57.35:8080` in `.env` (not committed).

Screen capture requires the Target user to accept the Android MediaProjection dialog. Remote control requires the Target user to enable the Accessibility service in Android Settings. The apps do not grant either silently.

## Troubleshooting

| Symptom | What to check |
| --- | --- |
| PostgreSQL fail | Service running? `docker compose -f infrastructure\docker-compose.yml up -d postgres` |
| Missing `.env` | `copy .env.example .env` then set secrets (not in git) |
| `npm run dev` crash | Read the ARL Backend window: Prisma, secrets length, `DATABASE_URL` |
| Port 8080 unexpected process | `netstat -ano \| findstr :8080` — stop that PID; do not start a second API |
| Health timeout | Wait for compile (`tsx watch`); confirm `HOST` is `0.0.0.0` |
| Emulator cannot hit API | Use `http://10.0.2.2:8080` in debug APKs, not `localhost` |
| Phone cannot hit API | Pairing `api=` / `PUBLIC_BASE_URL`; laptop firewall; Fastify on `0.0.0.0` |
| AVD missing | Android Studio Device Manager → AVD name exactly `Pixel_7` |
| Wrong SDK path | Must be `D:\Andriod\Sdk`, not `D:\Android\Sdk` |
| ADB empty / unauthorized | `"D:\Andriod\Sdk\platform-tools\adb.exe" devices` ; accept RSA prompt |
| Missing APK | `gradlew assembleDebug` in each app, or `build-demo` |
| Blank LIVE DEVICE VIEW / WebRTC FAILED | ICE cannot path emulator↔phone without TURN. Confirm ARL Backend log “embedded local TURN listening”. Allow UDP 3478. Check `ARL-WebRTC` `ice_state` / `typ=relay` |
| Control without video | Accessibility + session OK; video still needs capture, authenticated session, and ICE CONNECTED |
| Managed touch ignored | Session must be in MANAGED mode; Monitor mode rejects interaction (`MODE_VIEW_ONLY`) |
| Duplicate emulator | Script reuses lock/`adb`; do not start a second Pixel_7 from Studio at the same time |

## Related

- [DEVELOPMENT.md](DEVELOPMENT.md) — first-time DB and `npm run dev`
- [DEPLOYMENT.md](DEPLOYMENT.md) — Render / release APKs
- [SECURITY.md](SECURITY.md) — consent and safety boundary
