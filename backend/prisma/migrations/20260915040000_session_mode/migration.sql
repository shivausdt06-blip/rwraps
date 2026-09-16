-- Add MONITOR/MANAGED session mode (view-only vs interactive remote support).
CREATE TYPE "RemoteSessionMode" AS ENUM ('MONITOR', 'MANAGED');

ALTER TABLE "RemoteSession" ADD COLUMN "mode" "RemoteSessionMode" NOT NULL DEFAULT 'MONITOR';
