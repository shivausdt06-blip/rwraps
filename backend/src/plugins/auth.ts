import type { FastifyRequest } from "fastify";
import { prisma } from "../lib/prisma.js";
import { unauthorized } from "../errors.js";
import { verifyAccessToken } from "../lib/jwt.js";
import type { AppConfig } from "../config.js";

export type Principal =
  | { role: "admin"; adminId: string; tokenVersion: number }
  | { role: "device"; deviceId: string; tokenVersion: number };

declare module "fastify" {
  interface FastifyRequest {
    principal?: Principal;
  }
}

export async function authenticate(request: FastifyRequest, config: AppConfig): Promise<Principal> {
  const header = request.headers.authorization;
  if (!header?.startsWith("Bearer ")) {
    throw unauthorized();
  }
  const token = header.slice("Bearer ".length);
  try {
    const claims = await verifyAccessToken(config, token);
    if (claims.typ !== "access" || !claims.sub) {
      throw unauthorized("Invalid access token.");
    }
    const version = typeof claims.ver === "number" ? claims.ver : 0;
    if (claims.role === "admin") {
      const admin = await prisma.admin.findUnique({ where: { id: claims.sub } });
      if (!admin || admin.tokenVersion !== version) {
        throw unauthorized("Access token has been revoked.");
      }
      const principal: Principal = { role: "admin", adminId: admin.id, tokenVersion: admin.tokenVersion };
      request.principal = principal;
      return principal;
    }
    if (claims.role === "device") {
      const device = await prisma.device.findUnique({ where: { id: claims.sub } });
      if (!device || device.tokenVersion !== version || device.enrollmentState === "REVOKED") {
        throw unauthorized("Access token has been revoked.");
      }
      const principal: Principal = {
        role: "device",
        deviceId: device.id,
        tokenVersion: device.tokenVersion
      };
      request.principal = principal;
      return principal;
    }
    throw unauthorized();
  } catch (err) {
    if (err instanceof Error && "statusCode" in err) {
      throw err;
    }
    throw unauthorized("Invalid access token.");
  }
}

export function requireAdmin(principal: Principal | undefined): asserts principal is Extract<Principal, { role: "admin" }> {
  if (!principal || principal.role !== "admin") {
    throw unauthorized("Admin authentication is required.");
  }
}

export function requireDevice(principal: Principal | undefined): asserts principal is Extract<Principal, { role: "device" }> {
  if (!principal || principal.role !== "device") {
    throw unauthorized("Device authentication is required.");
  }
}
