# Deployment

This document is the production runbook for Android Remote Lab. Product behavior (pairing, session authorization, MediaProjection, WebRTC signaling, Accessibility, backup transfer) is unchanged. The API is a Fastify process; preferred hosting is **Render native Node**. Docker is optional for local development or self-hosted Node.

Never commit `.env`, keystores, or live secrets.

## Architecture (production)

- **API:** Fastify on Render (`PORT` from the platform, `HOST=0.0.0.0`, `trustProxy` for HTTPS).
- **Database:** Supabase PostgreSQL. Prisma `migrate deploy` only — never `migrate reset` in production.
- **Signaling:** WebSocket on the same HTTP server (`/v1/ws`). Clients convert `https://` → `wss://`.
- **Media:** WebRTC peer-to-peer. ICE servers come from `STUN_URLS` / optional `TURN_*`. The API does not relay media.
- **Backups:** `BACKUP_PAYLOAD_DESTINATION=LOCAL_ADMIN` (demo default). The API stages bytes in `STORAGE_DIR`; durable payloads live on the Admin device after ingest.

## Environment variables

| Variable | Required | Production notes |
| --- | --- | --- |
| `NODE_ENV` | yes | `production` |
| `HOST` | no | Default `0.0.0.0` |
| `PORT` | yes on Render | Render injects `PORT`; do not hardcode |
| `LOG_LEVEL` | no | Default `info`. Production JSON logs redact Authorization, cookies, passwords, tokens, pairing codes, TURN credentials |
| `CORS_ORIGIN` | yes in production | Comma-separated **https** origins. `*` and localhost are rejected. Native Android clients usually send no `Origin` and are still accepted |
| `PUBLIC_BASE_URL` | yes in production | `https://` public API URL (Render hostname) |
| `PUBLIC_WS_URL` | no | `wss://` if set; otherwise derived from `PUBLIC_BASE_URL` |
| `DATABASE_URL` | yes | Supabase Postgres URL with `sslmode=require`. Not localhost / not `lab_dev_only` |
| `DIRECT_URL` | no | Documented alias for operators; the Prisma schema uses `DATABASE_URL` |
| `JWT_ACCESS_SECRET` | yes | ≥32 chars, not a placeholder |
| `JWT_REFRESH_SECRET` | yes | ≥32 chars, independent of access secret |
| `PAIRING_PEPPER` | yes | ≥32 chars |
| `ACCESS_TOKEN_TTL_SECONDS` | no | Default `900` |
| `REFRESH_TOKEN_TTL_SECONDS` | no | Default `604800` |
| `DEVICE_ACCESS_TOKEN_TTL_SECONDS` | no | Default `900` |
| `DEVICE_REFRESH_TOKEN_TTL_SECONDS` | no | Default `2592000` |
| `PAIRING_TTL_SECONDS` | no | Default `600` |
| `SESSION_TIMEOUT_SECONDS` | no | Default `3600` |
| `PRESENCE_OFFLINE_AFTER_SECONDS` | no | Default `45` |
| `MAX_DEVICES_PER_ADMIN` | no | Default `50` |
| `ALLOW_ADMIN_REGISTRATION` | no | **Defaults to false in production** if unset |
| `ADMIN_BOOTSTRAP_EMAIL` | no | Omit after the first admin exists |
| `ADMIN_BOOTSTRAP_PASSWORD` | no | Local demo only |
| `STORAGE_PROVIDER` | no | Default `local` (staging filesystem) |
| `STORAGE_DIR` | no | Default `./data/backups` (ephemeral on Render unless you attach a disk) |
| `BACKUP_PAYLOAD_DESTINATION` | no | Default `LOCAL_ADMIN` |
| `STUN_URLS` | no | Comma-separated STUN URIs |
| `TURN_URLS` | no | Optional. If set, username and credential are required |
| `TURN_USERNAME` | if TURN | Secret |
| `TURN_CREDENTIAL` | if TURN | Secret |

The process **exits on invalid production configuration**. `.env` files are **not** loaded when `NODE_ENV=production`; set variables in the host.

## Local development

```powershell
cd D:\AndroidRemoteLab
copy .env.example .env
# Replace JWT_ACCESS_SECRET, JWT_REFRESH_SECRET, and PAIRING_PEPPER with unique 32+ character values.
docker compose -f infrastructure/docker-compose.yml up -d postgres
npm install
npm run db:generate
npm run db:migrate
npm run dev
```

- Health: `GET http://localhost:8080/health`
- Ready: `GET http://localhost:8080/ready`

Optional full stack in Docker (development `NODE_ENV`, not Render):

```powershell
docker compose -f infrastructure/docker-compose.yml --profile full up --build
```

See [DEVELOPMENT.md](DEVELOPMENT.md).

## Supabase setup

1. Create a project. Note the **database password**.
2. Project Settings → Database → connection strings.
3. Prefer the **session pooler** URI on port `5432` (or the direct `db.<project>.supabase.co:5432` URI) so Prisma migrations and runtime queries both work.
4. Append `?sslmode=require` if it is not already present.
5. If you must use the **transaction** pooler (`6543`), add `pgbouncer=true` to the runtime URL and run `prisma migrate deploy` against the **direct** session URL in a one-off job. Do not point migrations at transaction mode PgBouncer.
6. Allow the Render egress IPs in Supabase network restrictions if you enabled them.
7. Copy the URI into Render `DATABASE_URL`. Never commit it.

Prisma never resets production databases. There is no `migrate reset` in the Render start path.

## Prisma migrations

Development (may prompt; creates migrations):

```powershell
npm run db:migrate
```

Production / Render (applies existing migrations only):

```powershell
npx prisma migrate deploy --schema backend/prisma/schema.prisma
```

or:

```powershell
npm run db:migrate:deploy
```

Requires `DATABASE_URL`. Confirm with `GET /ready` after deploy.

## Render setup

Native Node is simpler than Docker on Render. `render.yaml` at the repository root is a blueprint; you can also create a Web Service manually.

1. New Web Service → this repo → **Node**.
2. Root directory: repository root (workspaces).
3. Build command:

```text
npm ci && npx prisma generate --schema backend/prisma/schema.prisma && npm run build
```

4. Pre-deploy / release command (migrations only):

```text
npx prisma migrate deploy --schema backend/prisma/schema.prisma
```

5. Start command:

```text
npm start
```

(`npm start` runs `node dist/index.js` in `backend/`.)

6. Health check path: `/health` (does not require the database).
7. Set environment variables from the table above. Generate secrets with a CSPRNG; do not reuse `.env.example` placeholders.
8. `PUBLIC_BASE_URL=https://<service>.onrender.com`
9. `PUBLIC_WS_URL=wss://<service>.onrender.com` (optional; derived if omitted)
10. `CORS_ORIGIN` = https origins of any browser consoles you actually host. Android APKs do not need to be listed.

Render terminates TLS. Fastify uses `trustProxy: true` so HTTPS and WebSockets work behind the proxy. Listen on `process.env.PORT`.

WebSockets: enable (default on Render web services). Sticky sessions are not required for signaling (each socket authenticates).

Persistent disk is optional. Staging files in `STORAGE_DIR` are ephemeral by design when destination is `LOCAL_ADMIN`.

## CORS

Production CORS is an allow-list of https origins. Wildcard CORS is **not** enabled. If a future browser-only client on a dynamic origin made an allow-list impossible, that exception would need an explicit security review; it is not the current configuration.

## Backup configuration

- `BACKUP_PAYLOAD_DESTINATION=LOCAL_ADMIN` remains the demo default.
- `STORAGE_DIR` is backend **staging**, not the durable store.
- After Admin ingest, payloads are on the Admin device (`filesDir/backups/...`). See [BACKUP_STORAGE.md](BACKUP_STORAGE.md).

## WebRTC / TURN

ICE servers are built from environment variables in `GET` session ICE configuration. Do not bake TURN passwords into APKs. If `TURN_URLS` is empty, clients use STUN only.

## Android API configuration

Debug (emulator → host loopback):

```powershell
cd admin-app
.\gradlew assembleDebug -Parl.debugApiBaseUrl=http://10.0.2.2:8080

cd ..\target-app
.\gradlew assembleDebug -Parl.debugApiBaseUrl=http://10.0.2.2:8080
```

Release (HTTPS API; no JWT/TURN secrets in the APK):

```powershell
cd admin-app
.\gradlew assembleRelease -Parl.releaseApiBaseUrl=https://<service>.onrender.com

cd ..\target-app
.\gradlew assembleRelease -Parl.releaseApiBaseUrl=https://<service>.onrender.com
```

You can also set `arl.debugApiBaseUrl` / `arl.releaseApiBaseUrl` in each app’s `gradle.properties` (URLs only). Release builds disable cleartext HTTP (`usesCleartextTraffic=false`). Debug allows HTTP to `10.0.2.2` / localhost via network security config.

Physical devices on a LAN need a reachable HTTPS URL or an extra debug domain in network security config — do not ship global cleartext in release.

## Health verification

```text
GET https://<service>.onrender.com/health   → { "status": "ok", ... }
GET https://<service>.onrender.com/ready    → { "status": "ready" } or 503
```

Then: admin login, pairing, WebSocket connect (`wss://<host>/v1/ws`).

## Rollback procedure

1. In Render, **Rollback** to the previous successful deploy (previous `dist/` and Node process).
2. Database: Prisma migrations are forward-only in this repo. If a new migration is unsafe, do **not** `migrate reset`. Restore a Supabase backup/PITR to a point before the migration, or apply a new forward migration that restores compatibility.
3. Android: sideload the previous APKs. Clients must keep using an API URL that still serves the same signaling protocol.
4. Confirm `/health`, `/ready`, then a pairing smoke test.

## Docker (optional)

```powershell
docker compose -f infrastructure/docker-compose.yml up -d postgres
```

Image: `infrastructure/backend.Dockerfile` (Node 22 Alpine, `PORT` from env). Compose `api` profile is **development** (`NODE_ENV=development`) so production validators do not reject local URLs.

## What not to do

- `prisma migrate reset` against Supabase
- Wildcard production CORS
- Committing `.env`, APKs with embedded production JWT/TURN secrets
- Pointing release APKs at `http://localhost` or `http://10.0.2.2`
