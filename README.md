# Android Remote Support & Security Research Lab

Consent-based remote-support and authorized security-research platform for Android devices. The target application must explicitly enroll and authorize an administrator. Live media uses WebRTC with backend signaling; the API never pretends to bypass Android’s security model.

This repository is a monorepo. Backend, Target client, and Admin console are implemented.

## Components

| Path | Role |
| --- | --- |
| `admin-app/` | Administrator Android console (device grid, live view, session log) |
| `target-app/` | Consenting target Android client (Milestone 2) |
| `backend/` | Fastify API, WebSocket signaling, presence, backups |
| `shared/` | Shared TypeScript contracts |
| `infrastructure/` | Docker Compose and container definitions |
| `docs/` | Architecture, API, database, security, WebSocket, interaction, backup storage, deployment |
| `scripts/` | Local developer helpers |
| `render.yaml` | Render native Node blueprint |

## Safety boundary

This system is for **authorized** support and research only. It does not implement covert RATs, stealth persistence, credential theft, permission bypasses, hidden surveillance, or access to other applications’ private storage.

See [docs/SECURITY.md](docs/SECURITY.md) and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Quick start (backend)

```powershell
copy .env.example .env
docker compose -f infrastructure/docker-compose.yml up -d postgres
npm install
npm run db:generate
npm run db:migrate
npm run dev
```

Health check: `GET http://localhost:8080/health`

Local E2E (backend + Pixel_7 emulator, one command):

```bat
D:\AndroidRemoteLab\scripts\start-local-test.bat
```

Details: [docs/LOCAL_TESTING.md](docs/LOCAL_TESTING.md). Full local commands, tests, and troubleshooting: [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

Production (Render + Supabase, no product-behavior changes): [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) and [docs/SECURITY.md](docs/SECURITY.md).

```powershell
npm run db:migrate:deploy
npm start
```

Android debug API default is `http://10.0.2.2:8080`. Release APKs must be built with `-Parl.releaseApiBaseUrl=https://<your-api>` and contain no JWT or TURN secrets.
