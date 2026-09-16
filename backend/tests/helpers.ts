import { randomUUID } from "node:crypto";
import type { FastifyInstance } from "fastify";
import { loadConfig } from "../src/config.js";
import { buildApp } from "../src/app.js";
import { prisma } from "../src/lib/prisma.js";

export const config = loadConfig();

export async function createTestApp(): Promise<FastifyInstance> {
  return buildApp(config);
}

export async function resetDatabase(): Promise<void> {
  await prisma.$executeRawUnsafe(`
    TRUNCATE TABLE
      "AuditEvent",
      "BackupFile",
      "Backup",
      "RemoteSession",
      "Enrollment",
      "PairingSession",
      "DeviceRefreshToken",
      "RefreshToken",
      "Device",
      "Admin"
    RESTART IDENTITY CASCADE
  `);
}

export async function registerAdmin(
  app: FastifyInstance,
  email = `admin-${randomUUID()}@lab.test`
) {
  const response = await app.inject({
    method: "POST",
    url: "/v1/auth/register",
    payload: {
      email,
      password: "ChangeMe_LabOnly_12",
      displayName: "Lab Admin"
    }
  });
  if (response.statusCode !== 201) {
    throw new Error(`register failed: ${response.statusCode} ${response.body}`);
  }
  return response.json() as {
    admin: { id: string; email: string };
    tokens: { accessToken: string; refreshToken: string; expiresIn: number };
  };
}

export async function enrollDevice(app: FastifyInstance, accessToken: string, name: string) {
  const created = await app.inject({
    method: "POST",
    url: "/v1/pairing-sessions",
    headers: { authorization: `Bearer ${accessToken}` }
  });
  if (created.statusCode !== 201) {
    throw new Error(`pairing create failed: ${created.statusCode} ${created.body}`);
  }
  const pairing = created.json() as {
    pairingSession: { id: string; pairingCode: string };
  };

  const claimed = await app.inject({
    method: "POST",
    url: "/v1/pairing/claim",
    payload: {
      pairingCode: pairing.pairingSession.pairingCode,
      device: {
        name,
        platform: "android",
        androidVersion: "14",
        manufacturer: "Google",
        model: "Pixel 8",
        sdkInt: 34,
        capabilities: { screenCapture: true, fileBackup: true, remoteInput: false }
      }
    }
  });
  if (claimed.statusCode !== 200) {
    throw new Error(`claim failed: ${claimed.statusCode} ${claimed.body}`);
  }
  const claim = claimed.json() as { claimToken: string };

  const confirmed = await app.inject({
    method: "POST",
    url: "/v1/pairing/confirm",
    payload: { claimToken: claim.claimToken }
  });
  if (confirmed.statusCode !== 201) {
    throw new Error(`confirm failed: ${confirmed.statusCode} ${confirmed.body}`);
  }
  return confirmed.json() as {
    device: { id: string; name: string };
    tokens: { accessToken: string; refreshToken: string };
    enrollment: { id: string; status: string };
  };
}

export function authHeader(token: string) {
  return { authorization: `Bearer ${token}` };
}
