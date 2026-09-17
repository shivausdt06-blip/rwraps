import type { Device } from "@prisma/client";
import { prisma } from "../lib/prisma.js";
import { serializeDevice } from "../lib/serialize.js";
import { invalidateSessionAuthCache } from "../lib/session-cache.js";
import { hub } from "./hub.js";

export function envelope(type: string, payload: unknown, id?: string) {
  return { v: 1 as const, id, type, payload };
}

export async function markDeviceOnline(device: Device): Promise<Device> {
  const updated = await prisma.device.update({
    where: { id: device.id },
    data: {
      lastSeenAt: new Date(),
      connectionState: "ONLINE"
    }
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
  return updated;
}

export async function markDeviceOffline(deviceId: string): Promise<void> {
  try {
    const existing = await prisma.device.findUnique({ where: { id: deviceId } });
    if (!existing || existing.connectionState === "OFFLINE") {
      return;
    }
    const updated = await prisma.device.update({
      where: { id: deviceId },
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
  } catch {
    // Presence updates must not fail socket teardown (e.g. test shutdown).
  }
}

export function notifySession(adminId: string, deviceId: string, session: unknown): void {
  const message = envelope("session.updated", { session });
  hub.sendToAdmin(adminId, message);
  hub.sendToDevice(deviceId, message);
  const s = session as { id?: string } | undefined;
  if (s?.id) {
    invalidateSessionAuthCache(s.id);
  }
}
