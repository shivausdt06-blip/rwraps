# Security

This platform is an **authorized** remote-support and security-research system. The target device owner must enroll the admin and can revoke access. The following are out of scope and must not be implemented:

- Covert malware / RAT behavior
- Deceptive installation that conceals purpose from the device owner
- Notification suppression
- Credential, token, or password theft from other apps
- Unauthorized access to other applications’ private storage
- Security or permission bypasses
- Stealth persistence
- Privilege escalation
- Hidden surveillance
- Unauthorized camera or microphone access
- Mechanisms intended to evade attribution or security monitoring

Android clients must use documented platform APIs (`MediaProjection` for screen capture, user-enabled `AccessibilityService` for consented remote interaction, user-facing file pickers for backups, foreground services with a visible session indicator). Accessibility is never enabled by the app itself. Interaction commands are session-scoped and audited.

## Authentication

- Admin passwords: Argon2id. Minimum length 12.
- Access tokens: signed JWTs, short TTL (default 15 minutes), include `ver` matching `tokenVersion`.
- Refresh tokens: opaque, stored as SHA-256 hashes, rotatable, revocable.
- Pairing codes: 8-character Crockford-like alphabet, TTL default 10 minutes, hashed with a server pepper, single use.
- Device credentials are issued only after explicit `pairing/confirm`.

## Authorization

Every protected handler loads the principal from the JWT, then checks:

1. Role (`admin` vs `device`)
2. Token version / revocation
3. Resource ownership (device.ownerAdminId, session participants, backup.deviceId)

Devices cannot list other devices. Admins cannot act on devices they do not own.

## Transport

The architecture is TLS-ready. Local development may use HTTP. Production must terminate TLS (Render or another reverse proxy), set `PUBLIC_BASE_URL` to an `https://` URL, and set `CORS_ORIGIN` to an explicit comma-separated https allow-list. Wildcard CORS (`*`) is rejected in production. WebSockets use `wss://` (same host as the API). Native Android clients typically send no `Origin` header; those requests are still accepted after JWT checks.

Control-plane traffic is HTTPS/WSS with application device UUIDs. Live media is WebRTC (TURN optional, environment-driven). This is not an anonymity proxy: IPs remain visible to TLS/TURN operators. MAC addresses are not collected. See [BACKUP_STORAGE.md](BACKUP_STORAGE.md) for what each layer can observe.

## Secrets

All secrets come from environment variables, validated at process start. Production rejects placeholder strings (`replace-with-…`, `lab_dev_only`, etc.), localhost database URLs, and `.env` file loading (`NODE_ENV=production` uses the host environment only). No credentials are hardcoded in source. `.env` is gitignored. `.env.example` contains placeholders only. Android release APKs must not embed JWT, pairing pepper, or TURN credentials — only a public API base URL.

`ALLOW_ADMIN_REGISTRATION` defaults to **false** in production when unset. Bootstrap admin env vars are for local demo; omit them after the first operator exists.

## Abuse controls

- Global and route-specific rate limits (`@fastify/rate-limit`)
- Zod validation on all bodies, params, and queries
- Pairing claim is especially tightly limited
- Audit log for auth, pairing, enrollment, session, backup, revocation, and remote-interaction commands

## WebRTC

The backend forwards signaling only between the admin and device bound to a live `RemoteSession`. ICE servers are returned from `STUN_URLS` / optional `TURN_URLS` environment configuration. TURN credentials, if configured, are treated as secrets and are redacted from logs.

## Data handling

Backups store only files the user authorized in the target app. Checksums and sizes are recorded. Durable payloads live on the Admin device; the API deletes staging after verified ingest. Deletion removes remaining staging objects and marks metadata deleted. File contents are not logged.

## Logging

Structured logs (pino) must not include passwords, pairing codes, raw tokens, Authorization headers, cookies, TURN credentials, or backup file contents. Production uses a redact list. HTTP clients in the Android apps use BASIC logging only in debug and redact `Authorization`. Audit metadata should avoid secrets. See [DEPLOYMENT.md](DEPLOYMENT.md) for host configuration.

## Threat notes

| Threat | Mitigation |
| --- | --- |
| Pairing code guessing | Short TTL, rate limit, hashed storage, single use |
| Stolen refresh token | Hash at rest, rotation, revocation, tokenVersion |
| Session hijack via signaling | Session membership checks before forwarding |
| Confused deputy (device A signals session B) | Session.deviceId must match authenticated device |
| Secret leak in git | env validation, gitignore, no default production secrets |

Release Android builds disable cleartext HTTP. Debug builds may use HTTP to the emulator host (`10.0.2.2`) only.
