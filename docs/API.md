# API specification

Base URL (local): `http://localhost:8080`

JSON request and response bodies. Authentication uses `Authorization: Bearer <access_token>` unless noted.

Error shape:

```json
{
  "error": {
    "code": "PAIRING_EXPIRED",
    "message": "This pairing session is no longer valid."
  }
}
```

## Health

### `GET /health`

Liveness. No dependencies.

`200`

```json
{ "status": "ok", "service": "android-remote-lab-api", "time": "2026-09-12T19:00:00.000Z" }
```

### `GET /ready`

Readiness. Checks configuration + database.

`200` `{ "status": "ready" }`  
`503` `{ "status": "not_ready", "reason": "database" }`

## Admin authentication

### `POST /v1/auth/register`

Enabled only when `ALLOW_ADMIN_REGISTRATION=true`.

Body: `{ "email": string, "password": string, "displayName": string }`  
`201` `{ "admin": Admin, "tokens": TokenPair }`

### `POST /v1/auth/login`

Body: `{ "email": string, "password": string }`  
`200` `{ "admin": Admin, "tokens": TokenPair }`

### `POST /v1/auth/refresh`

Body: `{ "refreshToken": string }`  
`200` `{ "tokens": TokenPair }`

### `POST /v1/auth/logout`

Auth: admin. Body may include `{ "refreshToken": string }` to revoke one token; otherwise all refresh tokens are revoked and `tokenVersion` is incremented.

`204`

### `GET /v1/auth/me`

Auth: admin. `200` `{ "admin": Admin }`

`Admin` = `{ id, email, displayName, createdAt, updatedAt }`  
`TokenPair` = `{ accessToken, refreshToken, expiresIn }`

## Pairing

### `POST /v1/pairing-sessions`

Auth: admin.

`201`

```json
{
  "pairingSession": {
    "id": "uuid",
    "expiresAt": "iso",
    "pairingCode": "K7M2Q9XA",
    "qrPayload": "arl://pair?code=K7M2Q9XA&session=uuid&api=http%3A%2F%2F10.0.0.5%3A8080"
  }
}
```

`pairingCode` is returned **once**. It is not stored in plaintext.

`qrPayload` is `arl://pair?code=&session=` plus optional `api=` (URL-encoded). `api` is the address a **Target** should call. In local development this is typically the host LAN HTTP URL (not `10.0.2.2`). Production uses `PUBLIC_BASE_URL` (https). Release Target APKs ignore `api=` and keep their compiled HTTPS endpoint. Debug Targets accept `api=` only for localhost / emulator loopback / RFC1918 IPv4. Pairing expiration, claim, and confirm are unchanged. Manual 8-character code entry still works (uses the Target’s compiled API base).

### `GET /v1/pairing-sessions`

Auth: admin. Lists the caller’s sessions (no codes).

### `POST /v1/pairing-sessions/:id/revoke`

Auth: admin (owner). `200` `{ "pairingSession": PairingSessionPublic }`

### `POST /v1/pairing/claim`

Unauthenticated. Rate limited.

Body:

```json
{
  "pairingCode": "K7M2Q9XA",
  "device": {
    "name": "Pixel Lab-01",
    "platform": "android",
    "androidVersion": "14",
    "manufacturer": "Google",
    "model": "Pixel 8",
    "sdkInt": 34,
    "capabilities": {
      "screenCapture": true,
      "fileBackup": true,
      "remoteInput": false,
      "accessibilityControl": false,
      "backgroundSession": true,
      "states": {
        "SCREEN_CAPTURE": "AVAILABLE",
        "ACCESSIBILITY_CONTROL": "NOT_GRANTED",
        "REMOTE_INTERACTION": "NOT_GRANTED",
        "FILE_BACKUP": "AVAILABLE",
        "BACKGROUND_SESSION": "AVAILABLE"
      }
    }
  }
}
```

`200` `{ "pairingSessionId": "uuid", "claimToken": string, "expiresAt": "iso" }`

### `POST /v1/pairing/confirm`

Body: `{ "claimToken": string }`  
The target must have displayed an explicit confirmation UI before calling this.

`201`

```json
{
  "device": Device,
  "tokens": TokenPair,
  "enrollment": { "id": "uuid", "status": "ACTIVE" }
}
```

## Devices

### `GET /v1/devices`

Auth: admin. Returns devices owned by the caller (designed for ≥10 enrolled devices).

### `GET /v1/devices/:id`

Auth: admin owner.

### `PATCH /v1/devices/:id`

Auth: admin owner. Body: `{ "name"?: string }`

### `POST /v1/devices/:id/revoke`

Auth: admin owner. Revokes enrollment, device refresh tokens, and active sessions.

### `GET /v1/devices/me`

Auth: device.

### `POST /v1/devices/me/heartbeat`

Auth: device.

Body (optional): `{ "capabilities"?: Capabilities, "androidVersion"?: string }`  
`200` `{ "device": DevicePublic, "serverTime": "iso" }`

### `POST /v1/devices/me/refresh`

Body: `{ "refreshToken": string }`  
`200` `{ "tokens": TokenPair }`

### `POST /v1/devices/me/revoke`

Auth: device. Target-initiated revocation of its own enrollment.

## Remote sessions

### `POST /v1/sessions`

Auth: admin.

Body: `{ "deviceId": "uuid" }`  
`201` `{ "session": RemoteSession }`

### `GET /v1/sessions`

Auth: admin. Query: `deviceId`, `status`.

### `GET /v1/sessions/:id`

Auth: admin owner or enrolled device participant.

### `POST /v1/sessions/:id/authenticate`

Auth: device. Target accepts the session.  
`200` `{ "session": RemoteSession }`

### `POST /v1/sessions/:id/activate`

Auth: admin or device. Marks `AUTHENTICATED` → `ACTIVE` once media is negotiating.

### `POST /v1/sessions/:id/reconnect`

Auth: admin or device. Increments reconnect count; status `RECONNECTING` then back to `ACTIVE` on next activate.

### `POST /v1/sessions/:id/terminate`

Auth: admin or device. Graceful end.

### `POST /v1/sessions/:id/revoke`

Auth: admin. Forced end.

### `POST /v1/sessions/:id/telemetry`

Auth: admin or device.

Body: `{ "rttMs": number, "packetLoss": number, "bitrateKbps": number }`

### `GET /v1/ice-servers`

Auth: admin or device.

`200` `{ "iceServers": [ { "urls": ["stun:..."], "username"?: string, "credential"?: string } ] }`

## Backups

### `POST /v1/backups`

Auth: admin or device. Body: `{ "deviceId": "uuid" }` (admin). Device uses itself.

### `GET /v1/backups`

Auth: admin. Query: `deviceId`.

### `GET /v1/backups/:id`

Auth: admin owner or the device.

### `POST /v1/backups/:id/files`

Auth: device.

Body: `{ "filename": string, "sizeBytes": number, "mimeType": string, "checksumSha256": string }`  
`201` `{ "file": BackupFile, "upload": { "chunkSize": 1048576 } }`

### `PUT /v1/backups/:id/files/:fileId/chunk`

Auth: device. Query: `offset` (bytes). Body: raw octet-stream.

### `POST /v1/backups/:id/files/:fileId/complete`

Auth: device. Verifies staged SHA-256. Idempotent if already complete. Backup becomes `STAGED` when all files are staged (`LOCAL_ADMIN` destination).

### `GET /v1/backups/:id/files/:fileId/chunk`

Auth: owning admin. Query: `offset`, `length`. Body: octet-stream of staged bytes. Rejected after ingest has moved the payload to the Admin device.

### `POST /v1/backups/:id/files/:fileId/ingest`

Auth: owning admin. Body: `{ "checksumSha256": hex, "bytesStored": number }`. Marks payload `LOCAL_ADMIN`, deletes staging, idempotent. Backup `COMPLETE` when every file is ingested.

### `POST /v1/backups/:id/cancel`

Auth: admin or device. Cancels open uploads and deletes incomplete staging.

### `DELETE /v1/backups/:id`

Auth: admin. Deletes metadata and remaining staging objects. The Admin app also removes the local backup directory.

`Backup` includes `storageProvider` (`LOCAL_ADMIN` \| `CLOUD`) and `payloadState`. `GET /v1/backups` includes nested `files`. Full transfer notes: [BACKUP_STORAGE.md](BACKUP_STORAGE.md).

## Audit

### `GET /v1/audit-events`

Auth: admin. Query: `action`, `resourceType`, `resourceId`, `cursor`, `limit`.

Returns only events related to the caller (as actor or as owner of the resource).

## WebSocket

`GET /v1/ws` (upgrade). See [WEBSOCKET.md](WEBSOCKET.md).
