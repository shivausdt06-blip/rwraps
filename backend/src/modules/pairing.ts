import type { FastifyInstance } from "fastify";
import type { Prisma } from "@prisma/client";
import type { AppConfig } from "../config.js";
import { prisma } from "../lib/prisma.js";
import { writeAudit } from "../lib/audit.js";
import { generateNonce, generatePairingCode, hashPairingCode } from "../lib/crypto.js";
import { signClaimToken, verifyAccessToken } from "../lib/jwt.js";
import { requestMeta } from "../lib/meta.js";
import { authenticate, requireAdmin } from "../plugins/auth.js";
import { pairingClaimSchema, pairingConfirmSchema } from "../lib/schemas.js";
import { serializeDevice, serializeEnrollment, serializePairingPublic } from "../lib/serialize.js";
import { issueDeviceTokens } from "../lib/tokens.js";
import { AppError, badRequest, conflict, forbidden, notFound, unauthorized } from "../errors.js";
import { advertisedPairingApiBase, buildPairingQrPayload } from "../lib/pairing-uri.js";

export async function pairingRoutes(app: FastifyInstance, config: AppConfig): Promise<void> {
  app.post("/v1/pairing-sessions", async (request, reply) => {
    const principal = await authenticate(request, config);
    requireAdmin(principal);

    const owned = await prisma.device.count({
      where: { ownerAdminId: principal.adminId, enrollmentState: "ACTIVE" }
    });
    if (owned >= config.MAX_DEVICES_PER_ADMIN) {
      throw forbidden(`Device limit of ${config.MAX_DEVICES_PER_ADMIN} reached.`);
    }

    let session = null;
    let pairingCode = "";
    for (let attempt = 0; attempt < 8; attempt += 1) {
      pairingCode = generatePairingCode();
      const codeHash = hashPairingCode(pairingCode, config.PAIRING_PEPPER);
      const nonce = generateNonce();
      const expiresAt = new Date(Date.now() + config.PAIRING_TTL_SECONDS * 1000);
      try {
        session = await prisma.pairingSession.create({
          data: {
            adminId: principal.adminId,
            codeHash,
            nonce,
            expiresAt
          }
        });
        break;
      } catch {
        session = null;
      }
    }
    if (!session) {
      throw new AppError(500, "PAIRING_ISSUE_FAILED", "Could not issue a pairing session.", false);
    }

    await writeAudit({
      actorType: "ADMIN",
      actorAdminId: principal.adminId,
      action: "pairing.create",
      resourceType: "pairing_session",
      resourceId: session.id,
      ...requestMeta(request)
    });

    return reply.code(201).send({
      pairingSession: {
        id: session.id,
        expiresAt: session.expiresAt.toISOString(),
        pairingCode,
        qrPayload: buildPairingQrPayload(
          pairingCode,
          session.id,
          advertisedPairingApiBase(config)
        )
      }
    });
  });

  app.get("/v1/pairing-sessions", async (request) => {
    const principal = await authenticate(request, config);
    requireAdmin(principal);
    const sessions = await prisma.pairingSession.findMany({
      where: { adminId: principal.adminId },
      orderBy: { createdAt: "desc" },
      take: 100
    });
    return { pairingSessions: sessions.map(serializePairingPublic) };
  });

  app.post("/v1/pairing-sessions/:id/revoke", async (request) => {
    const principal = await authenticate(request, config);
    requireAdmin(principal);
    const { id } = request.params as { id: string };
    const existing = await prisma.pairingSession.findFirst({
      where: { id, adminId: principal.adminId }
    });
    if (!existing) {
      throw notFound("Pairing session");
    }
    if (existing.status === "COMPLETED") {
      throw conflict("PAIRING_COMPLETED", "A completed pairing session cannot be revoked.");
    }
    const updated = await prisma.pairingSession.update({
      where: { id: existing.id },
      data: { status: "REVOKED", revokedAt: new Date() }
    });
    await writeAudit({
      actorType: "ADMIN",
      actorAdminId: principal.adminId,
      action: "pairing.revoke",
      resourceType: "pairing_session",
      resourceId: updated.id,
      ...requestMeta(request)
    });
    return { pairingSession: serializePairingPublic(updated) };
  });

  app.post(
    "/v1/pairing/claim",
    {
      config: {
        rateLimit: {
          max: 20,
          timeWindow: "1 minute"
        }
      }
    },
    async (request) => {
      const body = pairingClaimSchema.parse(request.body);
      const codeHash = hashPairingCode(body.pairingCode, config.PAIRING_PEPPER);
      const session = await prisma.pairingSession.findUnique({ where: { codeHash } });
      if (!session) {
        throw unauthorized("Invalid pairing code.");
      }
      if (session.status === "EXPIRED" || session.expiresAt < new Date()) {
        if (session.status !== "EXPIRED") {
          await prisma.pairingSession.update({
            where: { id: session.id },
            data: { status: "EXPIRED" }
          });
        }
        throw new AppError(410, "PAIRING_EXPIRED", "This pairing session is no longer valid.");
      }
      if (session.status === "REVOKED") {
        throw new AppError(410, "PAIRING_REVOKED", "This pairing session was revoked.");
      }
      if (session.status === "COMPLETED" || session.status === "CLAIMED") {
        throw conflict("PAIRING_REPLAY", "This pairing code has already been used.");
      }

      const claimed = await prisma.pairingSession.updateMany({
        where: { id: session.id, status: "PENDING" },
        data: {
          status: "CLAIMED",
          claimedAt: new Date(),
          pendingDeviceInfo: body.device as Prisma.InputJsonValue
        }
      });
      if (claimed.count !== 1) {
        throw conflict("PAIRING_REPLAY", "This pairing code has already been used.");
      }

      const claimToken = await signClaimToken(config, {
        pairingSessionId: session.id,
        nonce: session.nonce,
        expiresAt: session.expiresAt
      });

      await writeAudit({
        actorType: "DEVICE",
        action: "pairing.claim",
        resourceType: "pairing_session",
        resourceId: session.id,
        ...requestMeta(request)
      });

      return {
        pairingSessionId: session.id,
        claimToken,
        expiresAt: session.expiresAt.toISOString()
      };
    }
  );

  app.post("/v1/pairing/confirm", async (request, reply) => {
    const body = pairingConfirmSchema.parse(request.body);
    let pairingSessionId: string;
    let nonce: string;
    try {
      const claims = await verifyAccessToken(config, body.claimToken);
      if (claims.typ !== "claim" || claims.role !== "pairing" || !claims.sub || !claims.nonce) {
        throw unauthorized("Invalid claim token.");
      }
      pairingSessionId = claims.sub;
      nonce = claims.nonce;
    } catch (err) {
      if (err instanceof AppError) throw err;
      throw unauthorized("Invalid claim token.");
    }

    const result = await prisma.$transaction(async (tx) => {
      const session = await tx.pairingSession.findUnique({ where: { id: pairingSessionId } });
      if (!session || session.nonce !== nonce) {
        throw unauthorized("Invalid claim token.");
      }
      if (session.status === "EXPIRED" || session.expiresAt < new Date()) {
        throw new AppError(410, "PAIRING_EXPIRED", "This pairing session is no longer valid.");
      }
      if (session.status !== "CLAIMED") {
        throw conflict("PAIRING_NOT_CLAIMED", "Pairing must be claimed and confirmed exactly once.");
      }
      const info = session.pendingDeviceInfo;
      if (!info || typeof info !== "object") {
        throw badRequest("PAIRING_STATE", "Claimed pairing session is missing device metadata.");
      }
      const deviceInfo = info as {
        name: string;
        platform?: string;
        androidVersion?: string;
        manufacturer?: string;
        model?: string;
        sdkInt?: number;
        capabilities?: Prisma.InputJsonValue;
      };

      const owned = await tx.device.count({
        where: { ownerAdminId: session.adminId, enrollmentState: "ACTIVE" }
      });
      if (owned >= config.MAX_DEVICES_PER_ADMIN) {
        throw forbidden(`Device limit of ${config.MAX_DEVICES_PER_ADMIN} reached.`);
      }

      const advanced = await tx.pairingSession.updateMany({
        where: { id: session.id, status: "CLAIMED" },
        data: { status: "COMPLETED", usedAt: new Date() }
      });
      if (advanced.count !== 1) {
        throw conflict("PAIRING_REPLAY", "This pairing session has already been confirmed.");
      }

      const device = await tx.device.create({
        data: {
          ownerAdminId: session.adminId,
          name: deviceInfo.name,
          enrollmentState: "ACTIVE",
          authorizationState: "GRANTED",
          platform: deviceInfo.platform ?? "android",
          androidVersion: deviceInfo.androidVersion,
          manufacturer: deviceInfo.manufacturer,
          model: deviceInfo.model,
          sdkInt: deviceInfo.sdkInt,
          capabilities: (deviceInfo.capabilities ?? {}) as Prisma.InputJsonValue,
          lastSeenAt: new Date(),
          connectionState: "ONLINE"
        }
      });

      await tx.pairingSession.update({
        where: { id: session.id },
        data: { deviceId: device.id }
      });

      const enrollment = await tx.enrollment.create({
        data: {
          deviceId: device.id,
          adminId: session.adminId,
          pairingSessionId: session.id,
          status: "ACTIVE"
        }
      });

      return { device, enrollment };
    });

    const tokens = await issueDeviceTokens(config, result.device.id, result.device.tokenVersion);
    await writeAudit({
      actorType: "DEVICE",
      actorDeviceId: result.device.id,
      action: "pairing.confirm",
      resourceType: "device",
      resourceId: result.device.id,
      ...requestMeta(request)
    });

    return reply.code(201).send({
      device: serializeDevice(result.device),
      tokens,
      enrollment: serializeEnrollment(result.enrollment)
    });
  });
}
