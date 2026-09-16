import type { FastifyInstance } from "fastify";
import type { AppConfig } from "../config.js";
import { prisma } from "../lib/prisma.js";
import { authenticate, requireAdmin } from "../plugins/auth.js";

export async function auditRoutes(app: FastifyInstance, config: AppConfig): Promise<void> {
  app.get("/v1/audit-events", async (request) => {
    const principal = await authenticate(request, config);
    requireAdmin(principal);
    const query = request.query as {
      action?: string;
      resourceType?: string;
      resourceId?: string;
      cursor?: string;
      limit?: string;
    };
    const limit = Math.min(Math.max(Number(query.limit ?? 50), 1), 200);
    const ownedDeviceIds = (
      await prisma.device.findMany({
        where: { ownerAdminId: principal.adminId },
        select: { id: true }
      })
    ).map((d) => d.id);

    const events = await prisma.auditEvent.findMany({
      where: {
        AND: [
          {
            OR: [
              { actorAdminId: principal.adminId },
              { actorDeviceId: { in: ownedDeviceIds } },
              { resourceType: "device", resourceId: { in: ownedDeviceIds } },
              { resourceType: "admin", resourceId: principal.adminId }
            ]
          },
          query.action ? { action: query.action } : {},
          query.resourceType ? { resourceType: query.resourceType } : {},
          query.resourceId ? { resourceId: query.resourceId } : {},
          query.cursor ? { createdAt: { lt: new Date(query.cursor) } } : {}
        ]
      },
      orderBy: { createdAt: "desc" },
      take: limit
    });

    return {
      events: events.map((event) => ({
        id: event.id,
        actorType: event.actorType,
        actorAdminId: event.actorAdminId,
        actorDeviceId: event.actorDeviceId,
        action: event.action,
        resourceType: event.resourceType,
        resourceId: event.resourceId,
        ipAddress: event.ipAddress,
        createdAt: event.createdAt.toISOString(),
        metadata: event.metadata
      })),
      nextCursor: events.length === limit ? events[events.length - 1]?.createdAt.toISOString() : null
    };
  });
}
