import type { FastifyInstance } from "fastify";
import type { AppConfig } from "../config.js";
import { prisma } from "../lib/prisma.js";
import { writeAudit } from "../lib/audit.js";
import { hashOpaqueToken, hashPassword, verifyPassword } from "../lib/crypto.js";
import { requestMeta } from "../lib/meta.js";
import { serializeAdmin } from "../lib/serialize.js";
import { issueAdminTokens } from "../lib/tokens.js";
import { loginSchema, logoutSchema, refreshSchema, registerSchema } from "../lib/schemas.js";
import { AppError, forbidden, unauthorized } from "../errors.js";
import { authenticate, requireAdmin } from "../plugins/auth.js";

export async function bootstrapAdmin(config: AppConfig): Promise<void> {
  if (!config.ADMIN_BOOTSTRAP_EMAIL || !config.ADMIN_BOOTSTRAP_PASSWORD) {
    return;
  }
  const existing = await prisma.admin.findUnique({ where: { email: config.ADMIN_BOOTSTRAP_EMAIL } });
  if (existing) {
    return;
  }
  const passwordHash = await hashPassword(config.ADMIN_BOOTSTRAP_PASSWORD);
  const admin = await prisma.admin.create({
    data: {
      email: config.ADMIN_BOOTSTRAP_EMAIL,
      passwordHash,
      displayName: "Lab Administrator"
    }
  });
  await writeAudit({
    actorType: "SYSTEM",
    action: "admin.bootstrap",
    resourceType: "admin",
    resourceId: admin.id
  });
}

export async function authRoutes(app: FastifyInstance, config: AppConfig): Promise<void> {
  app.post("/v1/auth/register", async (request, reply) => {
    if (!config.allowAdminRegistration) {
      throw forbidden("Admin registration is disabled.");
    }
    const body = registerSchema.parse(request.body);
    const duplicate = await prisma.admin.findUnique({ where: { email: body.email.toLowerCase() } });
    if (duplicate) {
      throw new AppError(409, "EMAIL_TAKEN", "An administrator with this email already exists.");
    }
    const admin = await prisma.admin.create({
      data: {
        email: body.email.toLowerCase(),
        passwordHash: await hashPassword(body.password),
        displayName: body.displayName
      }
    });
    const tokens = await issueAdminTokens(config, admin.id, admin.tokenVersion);
    await writeAudit({
      actorType: "ADMIN",
      actorAdminId: admin.id,
      action: "admin.register",
      resourceType: "admin",
      resourceId: admin.id,
      ...requestMeta(request)
    });
    return reply.code(201).send({ admin: serializeAdmin(admin), tokens });
  });

  app.post("/v1/auth/login", async (request) => {
    const body = loginSchema.parse(request.body);
    const admin = await prisma.admin.findUnique({ where: { email: body.email.toLowerCase() } });
    if (!admin || !(await verifyPassword(admin.passwordHash, body.password))) {
      throw unauthorized("Invalid email or password.");
    }
    const tokens = await issueAdminTokens(config, admin.id, admin.tokenVersion);
    await writeAudit({
      actorType: "ADMIN",
      actorAdminId: admin.id,
      action: "admin.login",
      resourceType: "admin",
      resourceId: admin.id,
      ...requestMeta(request)
    });
    return { admin: serializeAdmin(admin), tokens };
  });

  app.post("/v1/auth/refresh", async (request) => {
    const body = refreshSchema.parse(request.body);
    const tokenHash = hashOpaqueToken(body.refreshToken, config.JWT_REFRESH_SECRET);
    const stored = await prisma.refreshToken.findUnique({ where: { tokenHash }, include: { admin: true } });
    if (!stored || stored.revokedAt || stored.expiresAt < new Date()) {
      throw unauthorized("Refresh token is invalid.");
    }
    await prisma.refreshToken.update({
      where: { id: stored.id },
      data: { revokedAt: new Date() }
    });
    const tokens = await issueAdminTokens(config, stored.adminId, stored.admin.tokenVersion);
    return { tokens };
  });

  app.post("/v1/auth/logout", async (request, reply) => {
    const principal = await authenticate(request, config);
    requireAdmin(principal);
    const body = logoutSchema.parse(request.body ?? {});
    if (body.refreshToken) {
      const tokenHash = hashOpaqueToken(body.refreshToken, config.JWT_REFRESH_SECRET);
      await prisma.refreshToken.updateMany({
        where: { adminId: principal.adminId, tokenHash, revokedAt: null },
        data: { revokedAt: new Date() }
      });
    } else {
      await prisma.refreshToken.updateMany({
        where: { adminId: principal.adminId, revokedAt: null },
        data: { revokedAt: new Date() }
      });
      await prisma.admin.update({
        where: { id: principal.adminId },
        data: { tokenVersion: { increment: 1 } }
      });
    }
    await writeAudit({
      actorType: "ADMIN",
      actorAdminId: principal.adminId,
      action: "admin.logout",
      resourceType: "admin",
      resourceId: principal.adminId,
      ...requestMeta(request)
    });
    return reply.code(204).send();
  });

  app.get("/v1/auth/me", async (request) => {
    const principal = await authenticate(request, config);
    requireAdmin(principal);
    const admin = await prisma.admin.findUniqueOrThrow({ where: { id: principal.adminId } });
    return { admin: serializeAdmin(admin) };
  });
}

