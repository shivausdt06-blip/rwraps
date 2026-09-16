# Database

PostgreSQL is the system of record. Prisma owns the schema and migrations (`backend/prisma`).

## Enums

| Enum | Values |
| --- | --- |
| `EnrollmentState` | `PENDING`, `ACTIVE`, `REVOKED` |
| `AuthorizationState` | `NONE`, `GRANTED`, `REVOKED` |
| `ConnectionState` | `OFFLINE`, `ONLINE` |
| `PairingStatus` | `PENDING`, `CLAIMED`, `COMPLETED`, `EXPIRED`, `REVOKED` |
| `EnrollmentStatus` | `ACTIVE`, `REVOKED` |
| `RemoteSessionStatus` | `CREATED`, `AUTHENTICATED`, `ACTIVE`, `RECONNECTING`, `TERMINATED`, `REVOKED`, `TIMED_OUT` |
| `BackupStatus` | `CREATED`, `UPLOADING`, `COMPLETE`, `CANCELLED`, `FAILED`, `DELETED` |
| `UploadState` | `PENDING`, `IN_PROGRESS`, `COMPLETE`, `CANCELLED`, `FAILED` |
| `ActorType` | `ADMIN`, `DEVICE`, `SYSTEM` |

## Tables

### Admin

Operators of the lab. `email` unique. `passwordHash` is Argon2id. `tokenVersion` invalidates access JWTs on global logout.

### RefreshToken (admin)

Hashed opaque refresh tokens, expiry, optional `revokedAt`. Unique `tokenHash`. Indexed on `adminId`, `expiresAt`.

### Device

Enrolled target. Owned by `ownerAdminId`. Capabilities stored as JSON:

```json
{
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
```

`credentialHash` reserved for optional device secret; v1 uses `DeviceRefreshToken`. Indexed on owner, connection state, last seen.

### DeviceRefreshToken

Same pattern as admin refresh tokens.

### PairingSession

`codeHash` unique (peppered SHA-256 of code). `nonce` unique (replay protection). Status machine as above. `pendingDeviceInfo` holds claim metadata until confirm. Plaintext codes are never persisted.

### Enrollment

Binding of device ↔ admin, optionally linked 1:1 to a pairing session. Unique `pairingSessionId`. Revocation is a status + timestamp, not a silent delete.

### RemoteSession

Admin + device, status, start/end/timeout, reconnect count, optional quality JSON. Indexed on `(deviceId, status)` and `(adminId, createdAt)`.

### Backup / BackupFile

Backup header: `deviceId`, `adminId`, `status` (includes `STAGED`), `storageProvider` (`LOCAL_ADMIN` \| `CLOUD`), `payloadState`. Files: unique `storageKey` (opaque `{backupId}/{fileId}`), `checksumSha256`, `bytesUploaded`, MIME, size, `payloadState`. Durable bytes are not kept in PostgreSQL.

### AuditEvent

Append-only. Actor admin (FK) or device id, action, resource type/id, IP, user agent, metadata JSON. Indexed for time and resource lookup.

## Constraints of note

- Pairing code hashes are unique: the same code cannot be issued twice while a hash collides (codes are 8 chars from a 32-symbol alphabet; uniqueness is enforced at insert with retry).
- Device names are not globally unique; identity is UUID.
- Foreign keys use `Restrict` from device to admin so audit/history cannot orphan via casual admin delete (admin delete is not exposed in v1).

## Migrations

```powershell
npm run db:generate
npm run db:migrate
```

Developers should not edit the database out of band. All changes go through Prisma migrations.

Production (Supabase): use `npm run db:migrate:deploy` / `prisma migrate deploy` only. Prefer a session-mode or direct Postgres URI. Do not `migrate reset`. Details: [DEPLOYMENT.md](DEPLOYMENT.md).
