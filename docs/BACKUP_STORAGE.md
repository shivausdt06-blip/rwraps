# Backup storage

Authorized file backups keep **metadata in PostgreSQL** and the **payload on the Admin device** for the lab demo. The backend is a session coordinator and ephemeral staging relay, not a permanent object store. An S3/cloud provider remains optional for later; it is not required to run the local architecture.

## Architecture

```
Target (SAF picker)
    HTTPS chunk upload (device JWT)
        v
Backend staging (STORAGE_DIR)
    metadata in PostgreSQL
    payload is temporary
        v
Admin GET chunks (admin JWT)
        v
Admin app-private storage
    filesDir/backups/target-<deviceId>/backup-<backupId>/
```

- The Target never writes paths onto the Admin filesystem.
- The Admin never trusts filenames from the Target without sanitizing them.
- Device IDs and backup IDs in local paths come from server records, not client-supplied path strings.

## Metadata vs payload

| Layer | What it stores |
| --- | --- |
| PostgreSQL `Backup` / `BackupFile` | owner admin, source device, filename, size, MIME, SHA-256, upload/payload state, opaque staging key |
| Backend `STORAGE_DIR` | staged bytes until Admin ingest (LOCAL_ADMIN) or retained if `BACKUP_PAYLOAD_DESTINATION=CLOUD` |
| Admin `filesDir/backups` | verified payloads, `meta.json`, `.tmp/*.part` |

`storageProvider`: `LOCAL_ADMIN` (default) or `CLOUD`.  
`payloadState`: `PENDING` → `STAGING` → `LOCAL_ADMIN` (or `CLOUD` / `FAILED`).

Backup `status`: `CREATED` → `UPLOADING` → `STAGED` (waiting for Admin) → `COMPLETE`. Cancel/delete/fail remain first-class.

## Transfer protocol

1. Admin or Target `POST /v1/backups` (Admin must pass `deviceId` they own).
2. Target `POST /v1/backups/:id/files` with sanitized filename, size, MIME, SHA-256.
3. Target `PUT .../chunk?offset=` sequential octet-stream. Wrong offset → `RESUME_OFFSET`. Oversized → `CHUNK_TOO_LARGE`.
4. Target `POST .../complete` hashes staged bytes. Mismatch → `FAILED`. Success → file `COMPLETE` / payload `STAGING`; backup `STAGED` when all files are staged. Completion is **idempotent**.
5. `backup.updated` is sent on the Admin WebSocket.
6. Admin `GET .../chunk?offset=&length=` downloads staged bytes.
7. Admin writes `.part` files, resumes from local length, hashes, renames atomically, then `POST .../ingest` `{ checksumSha256, bytesStored }`.
8. Ingest matching checksum deletes staging (LOCAL_ADMIN). Duplicate ingest is **idempotent**.
9. When every file is `LOCAL_ADMIN`, backup `COMPLETE`.

Unauthorized: foreign Admin, foreign device, unknown IDs, cancelled/deleted backups, incomplete staging.

## Local layout

```
filesDir/backups/
  target-<uuid>/
    backup-<uuid>/
      meta.json
      .tmp/<fileId>.part
      <sanitized-filename>
```

- Path traversal (`..`, absolute paths) is rejected.
- Colliding names become `stem-<fileId8>.ext`. Completed files are never overwritten.
- Cancel deletes `.tmp`. Delete removes the backup directory and server metadata.

## Quotas and recovery

The Admin store enforces a 2 GiB default quota and checks usable space before each chunk. Insufficient space throws a visible error and leaves completed backups intact. Interrupted transfers resume from `.part` length. Corrupt SHA-256 deletes the partial file and marks checksum `FAILED`.

## Security

- Device upload requires active enrollment + `GRANTED` authorization.
- Admin download/ingest requires backup ownership.
- Staging keys are `{backupId}/{fileId}`, never user paths.
- Logs must not include file bytes. HTTP logging stays at BASIC (no bodies).
- No backup secrets in source.

## Future cloud provider

`STORAGE_PROVIDER=cloud` wraps the same interface (today still a local directory adapter). Set `BACKUP_PAYLOAD_DESTINATION=CLOUD` to retain staging objects after complete instead of requiring Admin ingest. A later S3 adapter can replace `CloudStorageProvider` without changing HTTP routes.

## Cancellation and recovery

| Event | Backend | Admin |
| --- | --- | --- |
| Cancel | status CANCELLED, delete incomplete staging | delete `.tmp` |
| Delete | status DELETED, delete staging | delete backup directory |
| Ingest | delete staging, payload LOCAL_ADMIN | keep verified file |

## Network visibility (not a hiding proxy)

This platform does not add an anonymity overlay.

| Layer | Visible to that layer |
| --- | --- |
| HTTPS/WSS control plane | TLS endpoints, JWT identity, API routes, IP at the TLS terminator |
| WebRTC media | Peer IPs unless TURN is used; SDP/ICE via the authenticated WS |
| TURN (optional) | Relay addresses when configured; credentials are secrets |
| Application IDs | Device UUID, backup UUID — **not** MAC addresses |

The backend does not collect MAC addresses or extra hardware identifiers. Relayed control-plane traffic avoids a direct Target↔Admin TCP backup connection; it does not hide operators from their own TLS endpoint or from TURN.
