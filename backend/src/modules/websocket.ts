import { z } from "zod";
import type { FastifyInstance } from "fastify";
import type { AppConfig } from "../config.js";
import { prisma } from "../lib/prisma.js";
import { verifyAccessToken } from "../lib/jwt.js";
import { wsEnvelopeSchema } from "../lib/schemas.js";
import { serializeSession } from "../lib/serialize.js";
import { hub } from "../presence/hub.js";
import { envelope, markDeviceOnline, markDeviceOffline } from "../presence/presence.js";
import { unauthorized } from "../errors.js";
import { writeAudit } from "../lib/audit.js";
import {
  ReplayCache,
  authorizeAdminCommand,
  authorizeDeviceResult,
  interactionResultSchema,
  parseInteractionCommand
} from "../interaction/protocol.js";
import { extendSessionIdleDeadline, nextSessionTimeoutAt } from "../lib/session-lifecycle.js";

const interactionReplay = new ReplayCache();

const authPayload = z.object({
  role: z.enum(["admin", "device"]),
  accessToken: z.string().min(16)
});

const signalingPayload = z.object({
  sessionId: z.string().uuid(),
  sdp: z.string().min(1).max(65_536).optional(),
  candidate: z.unknown().optional()
});

const sessionPayload = z.object({
  sessionId: z.string().uuid(),
  rttMs: z.number().min(0).optional(),
  packetLoss: z.number().min(0).max(1).optional(),
  bitrateKbps: z.number().min(0).optional()
});

const screenControlPayload = z.object({
  sessionId: z.string().uuid(),
  enabled: z.boolean()
});

type WsPrincipal =
  | { role: "admin"; adminId: string }
  | { role: "device"; deviceId: string };

function send(socket: { send: (data: string) => void }, type: string, payload: unknown, id?: string) {
  socket.send(JSON.stringify(envelope(type, payload, id)));
}

export async function websocketRoutes(app: FastifyInstance, config: AppConfig): Promise<void> {
  app.get("/v1/ws", { websocket: true }, (socket) => {
    let principal: WsPrincipal | undefined;
    const ref = {
      send: (payload: unknown) => {
        if (socket.readyState === socket.OPEN) {
          socket.send(JSON.stringify(payload));
        }
      }
    };

    const authTimer = setTimeout(() => {
      if (!principal) {
        send(socket, "error", { code: "AUTH_TIMEOUT", message: "WebSocket authentication timed out." });
        socket.close();
      }
    }, 10_000);

    socket.on("close", () => {
      clearTimeout(authTimer);
      if (principal?.role === "admin") {
        hub.removeAdmin(principal.adminId, ref);
      }
      if (principal?.role === "device") {
        hub.removeDevice(principal.deviceId, ref);
        void markDeviceOffline(principal.deviceId);
      }
    });

    socket.on("message", (raw) => {
      void (async () => {
        const text = Buffer.isBuffer(raw) ? raw.toString("utf8") : String(raw);
        if (Buffer.byteLength(text, "utf8") > 65_536) {
          send(socket, "error", { code: "MESSAGE_TOO_LARGE", message: "WebSocket message exceeds 64 KiB." });
          return;
        }
        let parsed: unknown;
        try {
          parsed = JSON.parse(text);
        } catch {
          send(socket, "error", { code: "INVALID_JSON", message: "Message must be JSON." });
          return;
        }
        const envelopeResult = wsEnvelopeSchema.safeParse(parsed);
        if (!envelopeResult.success) {
          send(socket, "error", { code: "INVALID_ENVELOPE", message: "Invalid WebSocket envelope." });
          return;
        }
        const msg = envelopeResult.data;
        const replyId = msg.id;

        if (!principal) {
          if (msg.type !== "auth") {
            send(socket, "error", { code: "UNAUTHENTICATED", message: "Authenticate first." }, replyId);
            return;
          }
          const body = authPayload.safeParse(msg.payload);
          if (!body.success) {
            send(socket, "error", { code: "INVALID_AUTH", message: "Invalid auth payload." }, replyId);
            return;
          }
          try {
            const claims = await verifyAccessToken(config, body.data.accessToken);
            if (claims.typ !== "access" || claims.role !== body.data.role || !claims.sub) {
              throw unauthorized();
            }
            const version = typeof claims.ver === "number" ? claims.ver : 0;
            if (claims.role === "admin") {
              const admin = await prisma.admin.findUnique({ where: { id: claims.sub } });
              if (!admin || admin.tokenVersion !== version) {
                throw unauthorized();
              }
              principal = { role: "admin", adminId: admin.id };
              hub.addAdmin(admin.id, ref);
            } else {
              const device = await prisma.device.findUnique({ where: { id: claims.sub } });
              if (!device || device.tokenVersion !== version || device.enrollmentState === "REVOKED") {
                throw unauthorized();
              }
              principal = { role: "device", deviceId: device.id };
              hub.setDevice(device.id, ref);
              await markDeviceOnline(device);
            }
            clearTimeout(authTimer);
            send(socket, "auth.ok", { principal: { role: principal.role, id: claims.sub } }, replyId);
          } catch {
            send(socket, "error", { code: "UNAUTHORIZED", message: "WebSocket authentication failed." }, replyId);
            socket.close();
          }
          return;
        }

        if (msg.type === "heartbeat") {
          if (principal.role === "device") {
            const device = await prisma.device.findUnique({ where: { id: principal.deviceId } });
            if (device) {
              await markDeviceOnline(device);
            }
            const sessions = await prisma.remoteSession.findMany({
              where: {
                deviceId: principal.deviceId,
                status: { in: ["CREATED", "AUTHENTICATED", "ACTIVE", "RECONNECTING"] }
              },
              select: { id: true }
            });
            for (const active of sessions) {
              await extendSessionIdleDeadline(config, active.id);
            }
          }
          if (principal.role === "admin") {
            const sessions = await prisma.remoteSession.findMany({
              where: {
                adminId: principal.adminId,
                status: { in: ["CREATED", "AUTHENTICATED", "ACTIVE", "RECONNECTING"] }
              },
              select: { id: true }
            });
            for (const active of sessions) {
              await extendSessionIdleDeadline(config, active.id);
            }
          }
          send(socket, "heartbeat.ack", { serverTime: new Date().toISOString() }, replyId);
          return;
        }

        if (
          msg.type === "signaling.offer" ||
          msg.type === "signaling.answer" ||
          msg.type === "signaling.ice"
        ) {
          const payload = signalingPayload.safeParse(msg.payload);
          if (!payload.success) {
            send(socket, "error", { code: "INVALID_SIGNALING", message: "Invalid signaling payload." }, replyId);
            return;
          }
          const session = await prisma.remoteSession.findUnique({
            where: { id: payload.data.sessionId }
          });
          if (!session) {
            send(socket, "error", { code: "NOT_FOUND", message: "Session not found." }, replyId);
            return;
          }
          const isAdmin = principal.role === "admin" && principal.adminId === session.adminId;
          const isDevice = principal.role === "device" && principal.deviceId === session.deviceId;
          if (!isAdmin && !isDevice) {
            send(socket, "error", { code: "FORBIDDEN", message: "Not a session participant." }, replyId);
            return;
          }
          if (["TERMINATED", "REVOKED", "TIMED_OUT"].includes(session.status)) {
            send(socket, "error", { code: "SESSION_ENDED", message: "Session is not active." }, replyId);
            return;
          }
          await extendSessionIdleDeadline(config, session.id);
          const forward = envelope(msg.type, payload.data, replyId);
          const from = principal.role;
          const to = from === "admin" ? "device" : "admin";
          const delivered =
            from === "admin"
              ? hub.sendToDevice(session.deviceId, forward)
              : hub.sendToAdmin(session.adminId, forward);
          app.log.info({
            msg: "signaling.forward",
            type: msg.type,
            sessionId: session.id,
            from,
            to,
            delivered
          });
          if (!delivered) {
            send(
              socket,
              "error",
              { code: "PEER_OFFLINE", message: "Signaling peer is not connected to the control channel." },
              replyId
            );
          }
          return;
        }

        if (msg.type === "session.telemetry") {
          const payload = sessionPayload.safeParse(msg.payload);
          if (!payload.success || payload.data.rttMs === undefined) {
            send(socket, "error", { code: "INVALID_TELEMETRY", message: "Invalid telemetry payload." }, replyId);
            return;
          }
          const session = await prisma.remoteSession.findUnique({
            where: { id: payload.data.sessionId }
          });
          if (!session) {
            send(socket, "error", { code: "NOT_FOUND", message: "Session not found." }, replyId);
            return;
          }
          const isAdmin = principal.role === "admin" && principal.adminId === session.adminId;
          const isDevice = principal.role === "device" && principal.deviceId === session.deviceId;
          if (!isAdmin && !isDevice) {
            send(socket, "error", { code: "FORBIDDEN", message: "Not a session participant." }, replyId);
            return;
          }
          await prisma.remoteSession.update({
            where: { id: session.id },
            data: {
              quality: {
                rttMs: payload.data.rttMs,
                packetLoss: payload.data.packetLoss ?? 0,
                bitrateKbps: payload.data.bitrateKbps ?? 0
              }
            }
          });
          await extendSessionIdleDeadline(config, session.id);
          send(socket, "heartbeat.ack", { serverTime: new Date().toISOString() }, replyId);
          return;
        }

        if (msg.type === "screen.control") {
          if (principal.role !== "admin") {
            send(socket, "error", { code: "FORBIDDEN", message: "Only the session admin may control screen sharing." }, replyId);
            return;
          }
          const payload = screenControlPayload.safeParse(msg.payload);
          if (!payload.success) {
            send(socket, "error", { code: "INVALID_SCREEN_CONTROL", message: "Invalid screen control payload." }, replyId);
            return;
          }
          const session = await prisma.remoteSession.findUnique({
            where: { id: payload.data.sessionId }
          });
          if (!session) {
            send(socket, "error", { code: "NOT_FOUND", message: "Session not found." }, replyId);
            return;
          }
          if (principal.adminId !== session.adminId) {
            send(socket, "error", { code: "FORBIDDEN", message: "Not a session participant." }, replyId);
            return;
          }
          if (["TERMINATED", "REVOKED", "TIMED_OUT"].includes(session.status)) {
            send(socket, "error", { code: "SESSION_ENDED", message: "Session is not active." }, replyId);
            return;
          }
          await extendSessionIdleDeadline(config, session.id);
          const forwarded = envelope("screen.control", payload.data, replyId);
          const delivered = hub.sendToDevice(session.deviceId, forwarded);
          if (!delivered) {
            send(socket, "error", { code: "DEVICE_OFFLINE", message: "Target is not connected to the control channel." }, replyId);
          }
          return;
        }

        if (msg.type === "interaction.command") {
          if (principal.role !== "admin") {
            send(socket, "error", { code: "FORBIDDEN", message: "Only the session admin may issue interaction commands." }, replyId);
            return;
          }
          const parsed = parseInteractionCommand(msg.payload);
          if (!parsed.ok) {
            send(socket, "error", { code: parsed.code, message: parsed.message }, replyId);
            return;
          }
          const session = await prisma.remoteSession.findUnique({ where: { id: parsed.value.sessionId } });
          const device = session
            ? await prisma.device.findUnique({ where: { id: session.deviceId } })
            : null;
          const authorized = authorizeAdminCommand({
            adminId: principal.adminId,
            session,
            device,
            command: parsed.value,
            now: Date.now(),
            replay: interactionReplay
          });
          if (!authorized.ok) {
            send(socket, "error", { code: authorized.code, message: authorized.message }, replyId);
            await writeAudit({
              actorType: "ADMIN",
              actorAdminId: principal.adminId,
              action: "interaction.command.rejected",
              resourceType: "remote_session",
              resourceId: parsed.value.sessionId,
              metadata: {
                commandId: parsed.value.commandId,
                operation: parsed.value.operation,
                code: authorized.code
              }
            });
            return;
          }
          await extendSessionIdleDeadline(config, session!.id);
          app.log.info({
            msg: "interaction.received",
            sessionId: session!.id,
            commandId: authorized.value.commandId,
            operation: authorized.value.operation,
            mode: session!.mode
          });
          app.log.info({
            msg: "interaction.authorized",
            sessionId: session!.id,
            commandId: authorized.value.commandId
          });
          const forwarded = envelope("interaction.command", authorized.value, replyId);
          const delivered = hub.sendToDevice(session!.deviceId, forwarded);
          app.log.info({
            msg: delivered ? "interaction.forwarded" : "interaction.forward_failed",
            sessionId: session!.id,
            commandId: authorized.value.commandId,
            delivered
          });
          await writeAudit({
            actorType: "ADMIN",
            actorAdminId: principal.adminId,
            actorDeviceId: session!.deviceId,
            action: "interaction.command",
            resourceType: "remote_session",
            resourceId: session!.id,
            metadata: {
              commandId: authorized.value.commandId,
              operation: authorized.value.operation,
              delivered
            }
          });
          if (!delivered) {
            send(socket, "error", { code: "DEVICE_OFFLINE", message: "Target is not connected to the control channel." }, replyId);
          }
          return;
        }

        if (msg.type === "interaction.result") {
          if (principal.role !== "device") {
            send(socket, "error", { code: "FORBIDDEN", message: "Only the target device may report interaction results." }, replyId);
            return;
          }
          const body = interactionResultSchema.safeParse(msg.payload);
          if (!body.success) {
            send(socket, "error", { code: "MALFORMED_COMMAND", message: "Invalid interaction result." }, replyId);
            return;
          }
          const session = await prisma.remoteSession.findUnique({ where: { id: body.data.sessionId } });
          const authorized = authorizeDeviceResult({
            deviceId: principal.deviceId,
            session,
            commandId: body.data.commandId
          });
          if (!authorized.ok) {
            send(socket, "error", { code: authorized.code, message: authorized.message }, replyId);
            return;
          }
          await extendSessionIdleDeadline(config, authorized.value.session.id);
          hub.sendToAdmin(authorized.value.session.adminId, envelope("interaction.result", body.data, replyId));
          await writeAudit({
            actorType: "DEVICE",
            actorDeviceId: principal.deviceId,
            actorAdminId: authorized.value.session.adminId,
            action: "interaction.result",
            resourceType: "remote_session",
            resourceId: authorized.value.session.id,
            metadata: {
              commandId: body.data.commandId,
              ok: body.data.ok,
              code: body.data.code ?? null
            }
          });
          return;
        }

        if (msg.type === "session.reconnect") {
          const payload = sessionPayload.safeParse(msg.payload);
          if (!payload.success) {
            send(socket, "error", { code: "INVALID_SESSION", message: "sessionId is required." }, replyId);
            return;
          }
          const session = await prisma.remoteSession.findUnique({
            where: { id: payload.data.sessionId }
          });
          if (!session) {
            send(socket, "error", { code: "NOT_FOUND", message: "Session not found." }, replyId);
            return;
          }
          const isAdmin = principal.role === "admin" && principal.adminId === session.adminId;
          const isDevice = principal.role === "device" && principal.deviceId === session.deviceId;
          if (!isAdmin && !isDevice) {
            send(socket, "error", { code: "FORBIDDEN", message: "Not a session participant." }, replyId);
            return;
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
          const message = envelope("session.updated", { session: serializeSession(updated) }, replyId);
          hub.sendToAdmin(updated.adminId, message);
          hub.sendToDevice(updated.deviceId, message);
          return;
        }

        send(socket, "error", { code: "UNKNOWN_TYPE", message: `Unknown type ${msg.type}.` }, replyId);
      })();
    });
  });
}
