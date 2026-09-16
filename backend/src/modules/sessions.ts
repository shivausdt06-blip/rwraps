import type { FastifyInstance } from "fastify";
import type { RemoteSession, RemoteSessionStatus } from "@prisma/client";
import type { AppConfig } from "../config.js";
import { prisma } from "../lib/prisma.js";
import { writeAudit } from "../lib/audit.js";
import { requestMeta } from "../lib/meta.js";
import { createSessionSchema, setSessionModeSchema, telemetrySchema } from "../lib/schemas.js";
import { serializeSession } from "../lib/serialize.js";
import { authenticate, requireAdmin, requireDevice, type Principal } from "../plugins/auth.js";
import { badRequest, conflict, forbidden, notFound } from "../errors.js";
import { notifySession } from "../presence/presence.js";
import { buildIceServersPayload, type IceServerClientType } from "../lib/ice-servers.js";
import { extendSessionIdleDeadline, nextSessionTimeoutAt } from "../lib/session-lifecycle.js";

const TERMINAL: RemoteSessionStatus[] = ["TERMINATED", "REVOKED", "TIMED_OUT"];

async function loadParticipantSession(id: string, principal: Principal): Promise<RemoteSession> {
  const session = await prisma.remoteSession.findUnique({ where: { id } });
  if (!session) {
    throw notFound("Remote session");
  }
  if (principal.role === "admin" && session.adminId !== principal.adminId) {
    throw forbidden("You do not own this session.");
  }
  if (principal.role === "device" && session.deviceId !== principal.deviceId) {
    throw forbidden("You are not a participant of this session.");
  }
  return session;
}

export async function sessionRoutes(app: FastifyInstance, config: AppConfig): Promise<void> {
  app.post("/v1/sessions", async (request, reply) => {
    const principal = await authenticate(request, config);
    requireAdmin(principal);
    const body = createSessionSchema.parse(request.body);
    const device = await prisma.device.findFirst({
      where: { id: body.deviceId, ownerAdminId: principal.adminId }
    });
    if (!device) {
      throw notFound("Device");
    }
    if (device.enrollmentState !== "ACTIVE" || device.authorizationState !== "GRANTED") {
      throw forbidden("Device is not enrolled and authorized for remote support.");
    }

    const timeoutAt = nextSessionTimeoutAt(config);
    const session = await prisma.remoteSession.create({
      data: {
        deviceId: device.id,
        adminId: principal.adminId,
        status: "CREATED",
        mode: body.mode ?? "MONITOR",
        timeoutAt
      }
    });
    await writeAudit({
      actorType: "ADMIN",
      actorAdminId: principal.adminId,
      action: "session.create",
      resourceType: "remote_session",
      resourceId: session.id,
      ...requestMeta(request)
    });
    const dto = serializeSession(session);
    notifySession(session.adminId, session.deviceId, dto);
    return reply.code(201).send({ session: dto });
  });

  app.get("/v1/sessions", async (request) => {
    const principal = await authenticate(request, config);
    requireAdmin(principal);
    const query = request.query as { deviceId?: string; status?: string };
    const sessions = await prisma.remoteSession.findMany({
      where: {
        adminId: principal.adminId,
        deviceId: query.deviceId,
        status: query.status as RemoteSessionStatus | undefined
      },
      orderBy: { createdAt: "desc" },
      take: 200
    });
    return { sessions: sessions.map(serializeSession) };
  });

  app.get("/v1/sessions/:id", async (request) => {
    const principal = await authenticate(request, config);
    const { id } = request.params as { id: string };
    const session = await loadParticipantSession(id, principal);
    return { session: serializeSession(session) };
  });

  app.post("/v1/sessions/:id/authenticate", async (request) => {
    const principal = await authenticate(request, config);
    requireDevice(principal);
    const { id } = request.params as { id: string };
    const session = await loadParticipantSession(id, principal);
    if (session.status !== "CREATED" && session.status !== "RECONNECTING") {
      throw conflict("SESSION_STATE", "Session cannot be authenticated in its current state.");
    }
    const updated = await prisma.remoteSession.update({
      where: { id: session.id },
      data: {
        status: "AUTHENTICATED",
        startedAt: session.startedAt ?? new Date(),
        timeoutAt: nextSessionTimeoutAt(config)
      }
    });
    await extendSessionIdleDeadline(config, updated.id);
    await writeAudit({
      actorType: "DEVICE",
      actorDeviceId: principal.deviceId,
      action: "session.authenticate",
      resourceType: "remote_session",
      resourceId: updated.id,
      ...requestMeta(request)
    });
    const dto = serializeSession(updated);
    notifySession(updated.adminId, updated.deviceId, dto);
    return { session: dto };
  });

  app.post("/v1/sessions/:id/activate", async (request) => {
    const principal = await authenticate(request, config);
    const { id } = request.params as { id: string };
    const session = await loadParticipantSession(id, principal);
    if (session.status !== "AUTHENTICATED" && session.status !== "RECONNECTING") {
      throw conflict("SESSION_STATE", "Session must be authenticated before it can be activated.");
    }
    const updated = await prisma.remoteSession.update({
      where: { id: session.id },
      data: {
        status: "ACTIVE",
        startedAt: session.startedAt ?? new Date(),
        timeoutAt: nextSessionTimeoutAt(config)
      }
    });
    await extendSessionIdleDeadline(config, updated.id);
    await writeAudit({
      actorType: principal.role === "admin" ? "ADMIN" : "DEVICE",
      actorAdminId: principal.role === "admin" ? principal.adminId : undefined,
      actorDeviceId: principal.role === "device" ? principal.deviceId : undefined,
      action: "session.activate",
      resourceType: "remote_session",
      resourceId: updated.id,
      ...requestMeta(request)
    });
    const dto = serializeSession(updated);
    notifySession(updated.adminId, updated.deviceId, dto);
    return { session: dto };
  });

  app.post("/v1/sessions/:id/mode", async (request) => {
    const principal = await authenticate(request, config);
    requireAdmin(principal);
    const { id } = request.params as { id: string };
    const session = await loadParticipantSession(id, principal);
    if (TERMINAL.includes(session.status)) {
      throw conflict("SESSION_STATE", "Cannot change mode on an ended session.");
    }
    const body = setSessionModeSchema.parse(request.body);
    const updated = await prisma.remoteSession.update({
      where: { id: session.id },
      data: { mode: body.mode, timeoutAt: nextSessionTimeoutAt(config) }
    });
    await extendSessionIdleDeadline(config, updated.id);
    await writeAudit({
      actorType: "ADMIN",
      actorAdminId: principal.adminId,
      action: body.mode === "MANAGED" ? "session.mode.managed" : "session.mode.monitor",
      resourceType: "remote_session",
      resourceId: updated.id,
      ...requestMeta(request)
    });
    const dto = serializeSession(updated);
    notifySession(updated.adminId, updated.deviceId, dto);
    return { session: dto };
  });

  app.post("/v1/sessions/:id/reconnect", async (request) => {
    const principal = await authenticate(request, config);
    const { id } = request.params as { id: string };
    const session = await loadParticipantSession(id, principal);
    if (TERMINAL.includes(session.status)) {
      throw conflict("SESSION_STATE", "A terminated session cannot reconnect.");
    }
    const updated = await prisma.remoteSession.update({
      where: { id: session.id },
      data: {
        status: "RECONNECTING",
        reconnectCount: { increment: 1 },
        timeoutAt: nextSessionTimeoutAt(config)
      }
    });
    await extendSessionIdleDeadline(config, updated.id);
    await writeAudit({
      actorType: principal.role === "admin" ? "ADMIN" : "DEVICE",
      actorAdminId: principal.role === "admin" ? principal.adminId : undefined,
      actorDeviceId: principal.role === "device" ? principal.deviceId : undefined,
      action: "session.reconnect",
      resourceType: "remote_session",
      resourceId: updated.id,
      ...requestMeta(request)
    });
    const dto = serializeSession(updated);
    notifySession(updated.adminId, updated.deviceId, dto);
    return { session: dto };
  });

  app.post("/v1/sessions/:id/terminate", async (request) => {
    const principal = await authenticate(request, config);
    const { id } = request.params as { id: string };
    const session = await loadParticipantSession(id, principal);
    if (TERMINAL.includes(session.status)) {
      throw conflict("SESSION_STATE", "Session is already ended.");
    }
    const updated = await prisma.remoteSession.update({
      where: { id: session.id },
      data: { status: "TERMINATED", endedAt: new Date() }
    });
    await writeAudit({
      actorType: principal.role === "admin" ? "ADMIN" : "DEVICE",
      actorAdminId: principal.role === "admin" ? principal.adminId : undefined,
      actorDeviceId: principal.role === "device" ? principal.deviceId : undefined,
      action: "session.terminate",
      resourceType: "remote_session",
      resourceId: updated.id,
      ...requestMeta(request)
    });
    const dto = serializeSession(updated);
    notifySession(updated.adminId, updated.deviceId, dto);
    return { session: dto };
  });

  app.post("/v1/sessions/:id/revoke", async (request) => {
    const principal = await authenticate(request, config);
    requireAdmin(principal);
    const { id } = request.params as { id: string };
    const session = await loadParticipantSession(id, principal);
    if (TERMINAL.includes(session.status)) {
      throw conflict("SESSION_STATE", "Session is already ended.");
    }
    const updated = await prisma.remoteSession.update({
      where: { id: session.id },
      data: { status: "REVOKED", endedAt: new Date() }
    });
    await writeAudit({
      actorType: "ADMIN",
      actorAdminId: principal.adminId,
      action: "session.revoke",
      resourceType: "remote_session",
      resourceId: updated.id,
      ...requestMeta(request)
    });
    const dto = serializeSession(updated);
    notifySession(updated.adminId, updated.deviceId, dto);
    return { session: dto };
  });

  app.post("/v1/sessions/:id/telemetry", async (request) => {
    const principal = await authenticate(request, config);
    const { id } = request.params as { id: string };
    const session = await loadParticipantSession(id, principal);
    if (TERMINAL.includes(session.status)) {
      throw badRequest("SESSION_ENDED", "Telemetry cannot be attached to an ended session.");
    }
    const body = telemetrySchema.parse(request.body);
    const updated = await prisma.remoteSession.update({
      where: { id: session.id },
      data: { quality: body, timeoutAt: nextSessionTimeoutAt(config) }
    });
    await extendSessionIdleDeadline(config, updated.id);
    return { session: serializeSession(updated) };
  });

  app.get("/v1/ice-servers", async (request) => {
    const principal = await authenticate(request, config);
    const query = request.query as { client?: string; emulator?: string } | undefined;
    let clientType: IceServerClientType = "all";
    if (query?.client === "emulator" || query?.emulator === "true") {
      clientType = "emulator";
    } else if (query?.client === "device" || principal.role === "device") {
      clientType = "device";
    } else if (query?.client === "all") {
      clientType = "all";
    } else if (principal.role === "admin") {
      clientType = "emulator";
    }
    return { iceServers: buildIceServersPayload(config, undefined, clientType) };
  });
}
