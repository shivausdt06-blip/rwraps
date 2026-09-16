import type { ActorType, Prisma } from "@prisma/client";
import { prisma } from "./prisma.js";

export async function writeAudit(input: {
  actorType: ActorType;
  actorAdminId?: string;
  actorDeviceId?: string;
  action: string;
  resourceType: string;
  resourceId?: string;
  ipAddress?: string | null;
  userAgent?: string | null;
  metadata?: Prisma.InputJsonValue;
}): Promise<void> {
  await prisma.auditEvent.create({
    data: {
      actorType: input.actorType,
      actorAdminId: input.actorAdminId,
      actorDeviceId: input.actorDeviceId,
      action: input.action,
      resourceType: input.resourceType,
      resourceId: input.resourceId,
      ipAddress: input.ipAddress ?? undefined,
      userAgent: input.userAgent ?? undefined,
      metadata: input.metadata
    }
  });
}
