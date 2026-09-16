import type { FastifyInstance } from "fastify";
import type { Prisma } from "@prisma/client";
import type { AppConfig } from "../config.js";
import { prisma } from "../lib/prisma.js";
import { writeAudit } from "../lib/audit.js";
import { hashOpaqueToken } from "../lib/crypto.js";
import { requestMeta } from "../lib/meta.js";
import { heartbeatSchema, patchDeviceSchema, refreshSchema } from "../lib/schemas.js";
import { serializeDevice } from "../lib/serialize.js";
import { issueDeviceTokens } from "../lib/tokens.js";
import { authenticate, requireAdmin, requireDevice } from "../plugins/auth.js";
import { forbidden, notFound, unauthorized } from "../errors.js";
import { envelope, markDeviceOnline } from "../presence/presence.js";
import { hub } from "../presence/hub.js";

async function revokeDeviceAccess(deviceId: string, actor: "ADMIN" | "DEVICE", adminId?: string) {
  const now = new Date();
  await prisma.$transaction([
    prisma.device.update({
      where: { id: deviceId },
      data: {
        enrollmentState: "REVOKED",
        authorizationState: "REVOKED",
        tokenVersion: { increment: 1 },
        connectionState: "OFFLINE"
      }
    }),
    prisma.enrollment.updateMany({
      where: { deviceId, status: "ACTIVE" },
      data: { status: "REVOKED", revokedAt: now }
    }),
    prisma.deviceRefreshToken.updateMany({
      where: { deviceId, revokedAt: null },
      data: { revokedAt: now }
    }),
    prisma.remoteSession.updateMany({
      where: {
        deviceId,
        status: { in: ["CREATED", "AUTHENTICATED", "ACTIVE", "RECONNECTING"] }
      },
      data: { status: "REVOKED", endedAt: now }
    })
  ]);
  await writeAudit({
    actorType: actor,
    actorAdminId: adminId,
    actorDeviceId: actor === "DEVICE" ? deviceId : undefined,
    action: "device.revoke",
    resourceType: "device",
    resourceId: deviceId
  });
}

export async function deviceRoutes(app: FastifyInstance, config: AppConfig): Promise<void> {
  app.get("/v1/devices/me", async (request) => {
    const principal = await authenticate(request, config);
    requireDevice(principal);
    const device = await prisma.device.findUniqueOrThrow({ where: { id: principal.deviceId } });
    return { device: serializeDevice(device) };
  });

  app.post("/v1/devices/me/heartbeat", async (request) => {
    const principal = await authenticate(request, config);
    requireDevice(principal);
    const body = heartbeatSchema.parse(request.body ?? {});
    const current = await prisma.device.findUniqueOrThrow({ where: { id: principal.deviceId } });
    if (current.enrollmentState !== "ACTIVE" || current.authorizationState !== "GRANTED") {
      throw forbidden("This device is not authorized for remote support.");
    }
    const data: Prisma.DeviceUpdateInput = {};
    if (body.capabilities) {
      data.capabilities = body.capabilities;
    }
    if (body.androidVersion) {
      data.androidVersion = body.androidVersion;
    }
    const base =
      Object.keys(data).length > 0
        ? await prisma.device.update({ where: { id: current.id }, data })
        : current;
    const device = await markDeviceOnline(base);
    if (body.capabilities) {
      hub.sendToAdmin(
        device.ownerAdminId,
        envelope("device.capabilities", {
          deviceId: device.id,
          capabilities: serializeDevice(device).capabilities
        })
      );
    }
    return { device: serializeDevice(device), serverTime: new Date().toISOString() };
  });

  app.post("/v1/devices/me/refresh", async (request) => {
    const body = refreshSchema.parse(request.body);
    const tokenHash = hashOpaqueToken(body.refreshToken, config.JWT_REFRESH_SECRET);
    const stored = await prisma.deviceRefreshToken.findUnique({
      where: { tokenHash },
      include: { device: true }
    });
    if (!stored || stored.revokedAt || stored.expiresAt < new Date()) {
      throw unauthorized("Refresh token is invalid.");
    }
    if (stored.device.enrollmentState === "REVOKED") {
      throw unauthorized("Device enrollment has been revoked.");
    }
    await prisma.deviceRefreshToken.update({
      where: { id: stored.id },
      data: { revokedAt: new Date() }
    });
    const tokens = await issueDeviceTokens(config, stored.deviceId, stored.device.tokenVersion);
    return { tokens };
  });

  app.post("/v1/devices/me/revoke", async (request, reply) => {
    const principal = await authenticate(request, config);
    requireDevice(principal);
    await revokeDeviceAccess(principal.deviceId, "DEVICE");
    await writeAudit({
      actorType: "DEVICE",
      actorDeviceId: principal.deviceId,
      action: "enrollment.revoke",
      resourceType: "device",
      resourceId: principal.deviceId,
      ...requestMeta(request)
    });
    return reply.code(204).send();
  });

  app.get("/v1/devices", async (request) => {
    const principal = await authenticate(request, config);
    requireAdmin(principal);
    const devices = await prisma.device.findMany({
      where: { ownerAdminId: principal.adminId },
      orderBy: { createdAt: "desc" }
    });
    return { devices: devices.map(serializeDevice) };
  });

  app.get("/v1/devices/:id", async (request) => {
    const principal = await authenticate(request, config);
    requireAdmin(principal);
    const { id } = request.params as { id: string };
    const device = await prisma.device.findFirst({
      where: { id, ownerAdminId: principal.adminId }
    });
    if (!device) {
      throw notFound("Device");
    }
    return { device: serializeDevice(device) };
  });

  app.patch("/v1/devices/:id", async (request) => {
    const principal = await authenticate(request, config);
    requireAdmin(principal);
    const { id } = request.params as { id: string };
    const body = patchDeviceSchema.parse(request.body);
    const existing = await prisma.device.findFirst({
      where: { id, ownerAdminId: principal.adminId }
    });
    if (!existing) {
      throw notFound("Device");
    }
    const device = await prisma.device.update({
      where: { id },
      data: { name: body.name ?? existing.name }
    });
    return { device: serializeDevice(device) };
  });

  app.post("/v1/devices/:id/revoke", async (request, reply) => {
    const principal = await authenticate(request, config);
    requireAdmin(principal);
    const { id } = request.params as { id: string };
    const existing = await prisma.device.findFirst({
      where: { id, ownerAdminId: principal.adminId }
    });
    if (!existing) {
      throw notFound("Device");
    }
    await revokeDeviceAccess(id, "ADMIN", principal.adminId);
    await writeAudit({
      actorType: "ADMIN",
      actorAdminId: principal.adminId,
      action: "enrollment.revoke",
      resourceType: "device",
      resourceId: id,
      ...requestMeta(request)
    });
    return reply.code(204).send();
  });
}
