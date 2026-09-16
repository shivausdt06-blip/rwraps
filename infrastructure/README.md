# Infrastructure

| File | Purpose |
| --- | --- |
| `docker-compose.yml` | Local PostgreSQL (and optional API via `--profile full`) |
| `backend.Dockerfile` | Optional container image for self-hosted Node |
| `../render.yaml` | Render native Node blueprint (preferred production path) |

Render does not require Docker. Use native Node with `npm ci`, `prisma generate`, `npm run build`, `prisma migrate deploy`, and `npm start`.

Compose credentials (`lab` / `lab_dev_only`) are for local development only.
