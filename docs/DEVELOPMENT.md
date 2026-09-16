# Development

## Prerequisites

- Node.js 22+
- npm 10+
- PostgreSQL 16+ (Docker Desktop **or** a local PostgreSQL service)

## First-time setup

```powershell
cd D:\AndroidRemoteLab
copy .env.example .env
```

Start PostgreSQL, then create the lab role and database if they do not exist:

```sql
CREATE ROLE lab LOGIN PASSWORD 'lab_dev_only';
CREATE DATABASE android_remote_lab OWNER lab;
```

Docker Compose (optional, if Docker is installed):

```powershell
docker compose -f infrastructure/docker-compose.yml up -d postgres
```

Then:

```powershell
npm install
npm run db:generate
npm run db:migrate
```

Edit `.env` so JWT and pairing secrets are unique 32+ character values. The bootstrap admin is created on API start from `ADMIN_BOOTSTRAP_EMAIL` / `ADMIN_BOOTSTRAP_PASSWORD` if that email does not exist.

## Run the API

```powershell
npm run dev
```

- Health: http://localhost:8080/health
- Ready: http://localhost:8080/ready

## Local test launcher (Windows)

One command for the local backend + Pixel_7 emulator (SDK `D:\Andriod\Sdk`):

```bat
D:\AndroidRemoteLab\scripts\start-local-test.bat
```

See [LOCAL_TESTING.md](LOCAL_TESTING.md).

## Tests

```powershell
npm run typecheck
npm run lint
npm run test
```

Integration tests expect PostgreSQL at `DATABASE_URL`. Docker Compose provides this locally. Tests use a dedicated schema cleanup strategy (truncate) and never mock successful auth.

## Migrations

```powershell
npm run db:migrate
```

Prisma schema: `backend/prisma/schema.prisma`.

## Version strategy

| Component | Version policy |
| --- | --- |
| Node.js | 22.x LTS |
| TypeScript | 5.9.x, `strict` |
| Fastify | 5.x |
| Prisma | 6.x |
| Zod | 3.x |
| PostgreSQL | 16.x |

Workspace packages (`backend`, `shared`) share one lockfile at the repository root.

## Environment

See `.env.example`. The process **refuses to start** if required variables are missing or secrets are too short.

## Android apps

`admin-app/` and `target-app/` are implemented. Debug API default: `http://10.0.2.2:8080`. Release API: `-Parl.releaseApiBaseUrl=https://...` (see [DEPLOYMENT.md](DEPLOYMENT.md)).

## Production notes

- Follow [DEPLOYMENT.md](DEPLOYMENT.md) (Render + Supabase).
- Set `ALLOW_ADMIN_REGISTRATION=false` (already the production default if unset)
- Use TLS at the edge; `PUBLIC_BASE_URL` must be https
- Keep `BACKUP_PAYLOAD_DESTINATION=LOCAL_ADMIN` unless you intentionally switch to cloud staging retention
- Provision TURN via env vars; do not hardcode providers in APKs
- Rotate JWT secrets independently of pairing pepper
- Never run `prisma migrate reset` against production
