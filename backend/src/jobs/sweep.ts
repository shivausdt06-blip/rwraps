import type { FastifyBaseLogger } from "fastify";
import type { AppConfig } from "../config.js";
import { prisma } from "../lib/prisma.js";
import { writeAudit } from "../lib/audit.js";
import { serializeDevice, serializeSession } from "../lib/serialize.js";
import { envelope, notifySession } from "../presence/presence.js";
import { hub } from "../presence/hub.js";

export async function sweepExpiredPairing(): Promise<number> {
  const result = await prisma.pairingSession.updateMany({
    where: {
      status: { in: ["PENDING", "CLAIMED"] },
      expiresAt: { lt: new Date() }
    },
    data: { status: "EXPIRED" }
  });
  return result.count;
}

export async function sweepPresence(config: AppConfig): Promise<number> {
  const cutoff = new Date(Date.now() - config.PRESENCE_OFFLINE_AFTER_SECONDS * 1000);
  const stale = await prisma.device.findMany({
    where: {
      connectionState: "ONLINE",
      OR: [{ lastSeenAt: null }, { lastSeenAt: { lt: cutoff } }]
    }
  });
  for (const device of stale) {
    const updated = await prisma.device.update({
      where: { id: device.id },
      data: { connectionState: "OFFLINE" }
    });
    hub.sendToAdmin(
      updated.ownerAdminId,
      envelope("device.presence", {
        deviceId: updated.id,
        connectionState: updated.connectionState,
        lastSeenAt: updated.lastSeenAt?.toISOString() ?? null,
        device: serializeDevice(updated)
      })
    );
  }
  return stale.length;
}

export async function sweepSessionTimeouts(): Promise<number> {
  const now = new Date();
  const expired = await prisma.remoteSession.findMany({
    where: {
      status: { in: ["CREATED", "AUTHENTICATED", "ACTIVE", "RECONNECTING"] },
      timeoutAt: { lt: now }
    }
  });
  for (const session of expired) {
    const updated = await prisma.remoteSession.update({
      where: { id: session.id },
      data: { status: "TIMED_OUT", endedAt: now }
    });
    await writeAudit({
      actorType: "SYSTEM",
      action: "session.timeout",
      resourceType: "remote_session",
      resourceId: session.id
    });
    notifySession(updated.adminId, updated.deviceId, serializeSession(updated));
  }
  return expired.length;
}

export function startSweepers(config: AppConfig, logger: FastifyBaseLogger): () => void {
  const timer = setInterval(() => {
    void (async () => {
      try {
        await sweepExpiredPairing();
        await sweepPresence(config);
        await sweepSessionTimeouts();
      } catch (err) {
        logger.error({ err }, "background sweeper failed");
      }
    })();
  }, 15_000);
  timer.unref();
  return () => clearInterval(timer);
}
