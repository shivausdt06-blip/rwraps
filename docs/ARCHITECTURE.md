# Architecture

## Purpose

Android Remote Lab is a consent-based remote-support and authorized security-research platform. An administrator authenticates to a backend, enrolls target devices through a short-lived pairing ceremony, and may start remote-support sessions only after the target has explicitly authorized the relevant capabilities.

The system is designed so that:

- The device owner can see and revoke access.
- Pairing codes are never long-lived authentication credentials.
- Live screen media is transported with WebRTC when possible.
- The backend stores control-plane state, not covert access to other apps’ private data.

## High-level topology

```
Admin app (Android)
        | HTTPS + WSS
        v
Backend / API  ---- PostgreSQL
        |  |  \
        |  |   +-- Staging storage (ephemeral; Admin holds payloads)
        |  +------ Signaling (WebSocket)
        |
        | HTTPS + WSS
        v
Target app (Android, enrolled + authorized)
        |
        +-- WebRTC media (peer-to-peer when possible)
        +-- TURN (when NAT traversal requires a relay)
```

The backend is the trust anchor for identity, enrollment, authorization, session lifecycle, audit, and backup **metadata**. Live media is WebRTC. Backup **payloads** in the lab demo are stored on the Admin device after an authenticated staged transfer. SDP and ICE candidates are signaled through the backend; RTP/RTCP should flow peer-to-peer or via a configured TURN server.

## Repository layout

| Path | Responsibility |
| --- | --- |
| `admin-app/` | Administrator Android console |
| `target-app/` | Consenting target Android client |
| `backend/` | Fastify HTTP API, WebSocket signaling, workers |
| `shared/` | Cross-package TypeScript contracts (HTTP + WS) |
| `infrastructure/` | Compose stack (PostgreSQL, API, optional MinIO later) |
| `docs/` | Architecture, API, schema, security, development |
| `scripts/` | Developer automation |

## Runtime services

### API process

Node.js 22, TypeScript (strict), Fastify 5.

Responsibilities:

- Admin registration/login/refresh/logout
- Pairing session create/claim/confirm/revoke
- Device registry, heartbeat, presence
- Remote session lifecycle
- Backup metadata and resumable upload coordination
- Audit event recording and query
- WebSocket authentication, signaling fan-out, and authorized interaction commands
- Configuration validation at boot

### PostgreSQL

System of record for admins, devices, enrollments, pairing, sessions, backups, and audit events. Prisma owns schema and migrations.

### Staging storage

Interface (`StorageProvider`) with a local filesystem implementation used as **ephemeral staging**. The Admin app is the durable payload store (`LOCAL_ADMIN`). A `cloud` wrapper exists for a future S3-compatible provider. Only user-authorized files selected in the target app are transferred.

### Signaling

WebSocket endpoint `/v1/ws`. Authenticated as either `admin` or `device`. Messages are versioned JSON envelopes. Signaling payloads are forwarded only to participants of the referenced remote session.

### TURN abstraction

`GET /v1/ice-servers` returns STUN servers always. When `TURN_URLS` is set, TURN credentials from the environment are included. In `NODE_ENV=development` with empty `TURN_URLS`, the API also embeds a local TURN listener on port 3478 (emulator `10.0.2.2` + detected LAN IP) so a physical Target can ICE with an Admin emulator. Production does not start that listener. TURN credentials are redacted from logs.

## Domain model

### Admin

Human operator. Authenticates with email + password (Argon2id). Owns devices enrolled through pairing sessions they created.

### Device

Enrolled Android target. Identified by UUID. Stores human-readable name, platform metadata, capability flags, last-seen, connection state, and hashed refresh credentials.

### PairingSession

Short-lived, single-use enrollment ticket. The plaintext pairing code is shown once to the admin (or encoded in a QR payload) and stored only as a peppered hash.

### Enrollment

Persistent authorization binding between an admin and a device, created only after the target confirms. Revocation ends remote access without deleting historical audit rows.

### RemoteSession

Time-bounded support/research session. States: `CREATED` → `AUTHENTICATED` → `ACTIVE`, with `RECONNECTING`, `TERMINATED`, `REVOKED`, `TIMED_OUT`.

### Backup / BackupFile

Metadata for authorized file transfers. Bytes are staged on the API only until the Admin device ingests and verifies them (see [BACKUP_STORAGE.md](BACKUP_STORAGE.md)). The database stores filename, size, MIME type, SHA-256, upload/payload state, and an opaque staging key.

### AuditEvent

Append-oriented record of security-relevant actions.

## Pairing ceremony

1. Authenticated admin `POST /v1/pairing-sessions`.
2. Backend generates an 8-character code, a unique nonce, and expiry (default 10 minutes).
3. Target submits the code to `POST /v1/pairing/claim` with device metadata. The session becomes `CLAIMED`.
4. Target user explicitly confirms on-device; client calls `POST /v1/pairing/confirm`.
5. Backend creates `Device` + `Enrollment`, marks pairing `COMPLETED`, issues device access + refresh tokens.
6. Replay of the code fails. Expired or revoked sessions never mint credentials.

Reconnection of an already enrolled device uses device refresh tokens, not the pairing code.

## Presence

Devices send HTTP heartbeats and WebSocket heartbeats. `lastSeenAt` is persisted. A sweeper marks the device `OFFLINE` when `now - lastSeenAt` exceeds `PRESENCE_OFFLINE_AFTER_SECONDS`. Connected admins receive `device.presence` events.

v1 presence is **in-process**. Multiple API replicas require a shared presence bus (Redis) before horizontal scale-out.

## Session + media

1. Admin creates a `RemoteSession` for a device they own that is enrolled and authorized.
2. Target authenticates the session (explicit accept).
3. Either party may publish WebRTC offer/answer/ICE over the WebSocket.
4. Session timeout, admin terminate, device revoke, or enrollment revocation ends the session and is audited.
5. Quality telemetry (RTT, loss, bitrate) may be attached to the session record.

The target application is responsible for using `MediaProjection` and showing a persistent, non-deceptive session indicator. The backend refuses to treat a device as authorized unless enrollment and authorization flags are set.

## Backup transfer

See [BACKUP_STORAGE.md](BACKUP_STORAGE.md). Summary:

1. Admin or device opens a backup record bound to one Target.
2. Device initializes each authorized file (`filename`, `size`, `mimeType`, `checksumSha256`).
3. Chunks are uploaded with offsets (`bytesUploaded` tracked) into ephemeral staging.
4. Complete verifies SHA-256 of staged bytes. Backup becomes `STAGED`.
5. Admin downloads chunks into app-private `target-<id>/backup-<id>/`, verifies SHA-256, then `ingest`.
6. Staging objects are deleted. Cancel and delete are first-class and audited.

The API never requests other applications’ private directories. At least ten enrolled Targets are supported; backup directories are isolated per device ID.

## Trust and authn/z

| Principal | Credential | Scope |
| --- | --- | --- |
| Admin | Access JWT (short) + hashed refresh token | Admin APIs and WS |
| Device | Access JWT (short) + hashed refresh token | Device APIs and WS |
| Pairing | Single-use code + nonce | Claim/confirm only |

Every protected route checks role, token version (logout/revoke), and resource ownership.

## Scaling notes (later)

- Shared presence and pub/sub for WebSocket fan-out
- Optional S3-compatible **cloud** payload provider
- Dedicated TURN
- Optional media SFU only if recording/relay is an explicit, consented feature
- Read replicas for audit queries

## Out of scope (explicitly rejected)

Covert installation, notification suppression, credential theft, access to other apps’ private storage, permission bypasses, stealth persistence, privilege escalation, hidden camera/microphone, and attribution evasion.
