# Remote interaction (AccessibilityService)

The Target app can expose **consented** remote interaction after the device owner explicitly enables `lab.arl.target.interaction.RemoteInteractionService` in Android Settings → Accessibility. The service **does not** start or enable itself.

## Why this service exists

During an authenticated remote-support session, the lab administrator may need taps, swipes, and system Back/Home/Recents on the target. Android only allows those gestures through a user-enabled `AccessibilityService` with `canPerformGestures`. Window-content retrieval is used only to click/focus a named accessibility node or to set text on a **non-password** focused editable node via `ACTION_SET_TEXT`.

This is not HID injection, not a global input driver, and not a bypass of the lock screen.

## Authorization flow (Target)

1. **ACCESSIBILITY CONTROL** explains the capability.
2. **Open Android Accessibility Settings** (`Settings.ACTION_ACCESSIBILITY_SETTINGS`).
3. The owner finds **ARL Target** and enables it.
4. Return to the app. **Verify service availability** (also runs on resume).
5. Heartbeat reports `ACCESSIBILITY_CONTROL` / `REMOTE_INTERACTION` to the backend.
6. Disable the service in the same Settings screen to revoke the capability.

## Capability states

Each of `SCREEN_CAPTURE`, `ACCESSIBILITY_CONTROL`, `REMOTE_INTERACTION`, `FILE_BACKUP`, `BACKGROUND_SESSION` is one of:

| State | Meaning |
| --- | --- |
| `AVAILABLE` | Android APIs are present and currently usable |
| `NOT_GRANTED` | Platform supports it; the owner has not granted it |
| `RESTRICTED` | Granted or present but Android/OEM currently limits it (e.g. service listed but not bound; API 35 data-sync FGS time limits) |
| `UNAVAILABLE` | Not supported on this API level |

`remoteInput` remains a boolean convenience flag and is `true` only when `REMOTE_INTERACTION` is `AVAILABLE`.

## Supported operations (when `REMOTE_INTERACTION` is AVAILABLE)

| Operation | API |
| --- | --- |
| TAP / LONG_PRESS / SWIPE / SCROLL | `AccessibilityService.dispatchGesture` (API 24+) |
| BACK / HOME / RECENTS | `performGlobalAction` |
| NODE_CLICK / NODE_FOCUS | `AccessibilityNodeInfo.performAction` on `viewId` or content description |
| TEXT_ENTRY | `ACTION_SET_TEXT` on the focused editable node |

Coordinates are normalized `0..1` relative to the current display.

## What Android prevents (not implemented)

- Hidden or silent AccessibilityService activation
- Filter key events / PIN, password, or credential capture (`canRequestFilterKeyEvents` is false; password nodes are rejected)
- Security-dialog or permission-dialog bypasses
- Notification suppression
- Covert persistence
- Injecting input without an authenticated live session

OEM overlays, lock screen, work profile, and some system UI simply ignore gestures. Those outcomes are reported as execution failures, not faked success.

## Supported Android versions

Target `minSdk` 26, `compileSdk` 35.

| Area | Notes |
| --- | --- |
| Gestures | API 24+ (`dispatchGesture`); always available at minSdk 26 when the service is connected |
| Global actions | Back/Home/Recents on all supported API levels |
| ACTION_SET_TEXT | API 21+ |
| FGS / MediaProjection | Unchanged from screen capture; API 34+ `mediaProjection` FGS type; API 35 `dataSync` time limits → `BACKGROUND_SESSION=RESTRICTED` |

## Security model

1. Admin WebSocket must be authenticated.
2. Backend checks the admin owns the `RemoteSession`, the session is `AUTHENTICATED`/`ACTIVE`/`RECONNECTING`, the device reported remote interaction available, command schema, timestamp skew (30s), and command-ID replay cache.
3. Target repeats session + capability + schema + replay checks, then executes only through the bound AccessibilityService.
4. Audit events: `interaction.command`, `interaction.command.rejected`, `interaction.result`.

Knowing a device ID is not sufficient. `CREATED` sessions (before target consent) are rejected.

## Command protocol

WebSocket type `interaction.command` (admin → server → device):

```json
{
  "commandId": "uuid",
  "sessionId": "uuid",
  "timestamp": 1710000000000,
  "operation": "TAP",
  "capability": "REMOTE_INTERACTION",
  "params": { "nx": 0.5, "ny": 0.4 }
}
```

Result type `interaction.result` (device → server → admin): `{ commandId, sessionId, ok, code?, message?, latencyMs? }`.

Capability push: HTTP heartbeat plus `device.capabilities` and `device.presence` to the owning admin.

## Testing procedure

Automated:

```powershell
cd D:\AndroidRemoteLab\backend
npm test

cd D:\AndroidRemoteLab\target-app
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

cd D:\AndroidRemoteLab\admin-app
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Manual:

1. Enroll Target, start a session, grant MediaProjection, confirm SCREEN still works.
2. On Target, open Accessibility Settings, enable **ARL Target**, return, tap verify. Admin CONTROL should show **CONTROL AVAILABLE**.
3. With a live session, tap the Admin touchpad and Back/Home; confirm the Target performs the gesture and CONTROL shows OK + latency from `interaction.result`.
4. Disable the accessibility service; Admin should show **CONTROL REQUIRES ACCESSIBILITY PERMISSION** after the next heartbeat/presence.
5. End the session and confirm further commands are rejected.
