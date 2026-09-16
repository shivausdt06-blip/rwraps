import { createHash, randomUUID } from "node:crypto";
import type { FastifyInstance } from "fastify";
import type { AppConfig } from "../config.js";
import type { StorageProvider } from "../storage/index.js";
import { prisma } from "../lib/prisma.js";
import { writeAudit } from "../lib/audit.js";
import { requestMeta } from "../lib/meta.js";
import { createBackupFileSchema, createBackupSchema, ingestBackupFileSchema } from "../lib/schemas.js";
import { serializeBackup, serializeBackupFile } from "../lib/serialize.js";
import { authenticate, requireAdmin, requireDevice, type Principal } from "../plugins/auth.js";
import { badRequest, conflict, forbidden, notFound } from "../errors.js";
import { sanitizeFilename } from "../storage/sanitize.js";
import { envelope } from "../presence/presence.js";
import { hub } from "../presence/hub.js";

const CHUNK_SIZE = 1_048_576;

async function loadBackupForPrincipal(id: string, principal: Principal) {
  const backup = await prisma.backup.findUnique({ where: { id }, include: { files: true } });
  if (!backup) {
    throw notFound("Backup");
  }
  if (principal.role === "admin" && backup.adminId !== principal.adminId) {
    throw forbidden("You do not own this backup.");
  }
  if (principal.role === "device" && backup.deviceId !== principal.deviceId) {
    throw forbidden("You are not the source device for this backup.");
  }
  return backup;
}

function notifyBackup(adminId: string, backupId: string, status: string) {
  hub.sendToAdmin(
    adminId,
    envelope("backup.updated", {
      backupId,
      status
    })
  );
}

async function refreshBackupStatus(backupId: string, destination: AppConfig["BACKUP_PAYLOAD_DESTINATION"]) {
  const files = await prisma.backupFile.findMany({ where: { backupId } });
  if (files.length === 0) {
    return prisma.backup.findUniqueOrThrow({ where: { id: backupId } });
  }
  const open = files.filter((file) => !["COMPLETE", "CANCELLED"].includes(file.uploadState));
  const local = files.filter((file) => file.payloadState === "LOCAL_ADMIN" || file.payloadState === "CLOUD");
  const failed = files.some((file) => file.uploadState === "FAILED" || file.payloadState === "FAILED");
  let status: "UPLOADING" | "STAGED" | "COMPLETE" | "FAILED" = "UPLOADING";
  let payloadState: "PENDING" | "STAGING" | "LOCAL_ADMIN" | "CLOUD" | "FAILED" = "STAGING";
  if (failed) {
    status = "FAILED";
    payloadState = "FAILED";
  } else if (open.length === 0 && local.length === files.length) {
    status = "COMPLETE";
    payloadState = destination === "CLOUD" ? "CLOUD" : "LOCAL_ADMIN";
  } else if (open.length === 0) {
    status = destination === "CLOUD" ? "COMPLETE" : "STAGED";
    payloadState = destination === "CLOUD" ? "CLOUD" : "STAGING";
  }
  return prisma.backup.update({
    where: { id: backupId },
    data: { status, payloadState }
  });
}

export async function backupRoutes(
  app: FastifyInstance,
  config: AppConfig,
  storage: StorageProvider
): Promise<void> {
  const destination = config.BACKUP_PAYLOAD_DESTINATION;

  app.post("/v1/backups", async (request, reply) => {
    const principal = await authenticate(request, config);
    const body = createBackupSchema.parse(request.body ?? {});
    let deviceId: string;
    let adminId: string;
    if (principal.role === "admin") {
      requireAdmin(principal);
      if (!body.deviceId) {
        throw badRequest("DEVICE_REQUIRED", "deviceId is required.");
      }
      const device = await prisma.device.findFirst({
        where: { id: body.deviceId, ownerAdminId: principal.adminId }
      });
      if (!device) {
        throw notFound("Device");
      }
      deviceId = device.id;
      adminId = principal.adminId;
    } else {
      requireDevice(principal);
      const device = await prisma.device.findUniqueOrThrow({ where: { id: principal.deviceId } });
      deviceId = device.id;
      adminId = device.ownerAdminId;
    }

    const backup = await prisma.backup.create({
      data: {
        deviceId,
        adminId,
        status: "CREATED",
        storageProvider: destination,
        payloadState: "PENDING"
      }
    });
    await writeAudit({
      actorType: principal.role === "admin" ? "ADMIN" : "DEVICE",
      actorAdminId: principal.role === "admin" ? principal.adminId : adminId,
      actorDeviceId: principal.role === "device" ? principal.deviceId : undefined,
      action: "backup.create",
      resourceType: "backup",
      resourceId: backup.id,
      metadata: { deviceId, storageProvider: destination },
      ...requestMeta(request)
    });
    notifyBackup(adminId, backup.id, backup.status);
    return reply.code(201).send({ backup: serializeBackup(backup) });
  });

  app.get("/v1/backups", async (request) => {
    const principal = await authenticate(request, config);
    requireAdmin(principal);
    const query = request.query as { deviceId?: string };
    const backups = await prisma.backup.findMany({
      where: {
        adminId: principal.adminId,
        deviceId: query.deviceId,
        status: { not: "DELETED" }
      },
      include: { files: true },
      orderBy: { createdAt: "desc" },
      take: 200
    });
    return {
      backups: backups.map((backup) => ({
        ...serializeBackup(backup),
        files: backup.files.map(serializeBackupFile)
      }))
    };
  });

  app.get("/v1/backups/:id", async (request) => {
    const principal = await authenticate(request, config);
    const { id } = request.params as { id: string };
    const backup = await loadBackupForPrincipal(id, principal);
    return {
      backup: serializeBackup(backup),
      files: backup.files.map(serializeBackupFile)
    };
  });

  app.post("/v1/backups/:id/files", async (request, reply) => {
    const principal = await authenticate(request, config);
    requireDevice(principal);
    const { id } = request.params as { id: string };
    const backup = await loadBackupForPrincipal(id, principal);
    const device = await prisma.device.findUniqueOrThrow({ where: { id: principal.deviceId } });
    if (device.enrollmentState !== "ACTIVE" || device.authorizationState !== "GRANTED") {
      throw forbidden("This device is not authorized to upload backups.");
    }
    if (["CANCELLED", "DELETED", "COMPLETE", "FAILED"].includes(backup.status)) {
      throw conflict("BACKUP_STATE", "Files cannot be added to this backup.");
    }
    const body = createBackupFileSchema.parse(request.body);
    const fileId = randomUUID();
    const storageKey = `${backup.id}/${fileId}`;
    const file = await prisma.backupFile.create({
      data: {
        id: fileId,
        backupId: backup.id,
        filename: sanitizeFilename(body.filename),
        sizeBytes: BigInt(body.sizeBytes),
        mimeType: body.mimeType,
        checksumSha256: body.checksumSha256.toLowerCase(),
        storageKey,
        uploadState: "PENDING",
        payloadState: "PENDING"
      }
    });
    await prisma.backup.update({
      where: { id: backup.id },
      data: { status: "UPLOADING", payloadState: "STAGING" }
    });
    notifyBackup(backup.adminId, backup.id, "UPLOADING");
    return reply.code(201).send({
      file: serializeBackupFile(file),
      upload: { chunkSize: CHUNK_SIZE }
    });
  });

  app.put("/v1/backups/:id/files/:fileId/chunk", async (request) => {
    const principal = await authenticate(request, config);
    requireDevice(principal);
    const { id, fileId } = request.params as { id: string; fileId: string };
    const backup = await loadBackupForPrincipal(id, principal);
    if (["CANCELLED", "DELETED", "FAILED"].includes(backup.status)) {
      throw conflict("BACKUP_STATE", "This backup is not accepting chunks.");
    }
    const file = backup.files.find((item) => item.id === fileId);
    if (!file) {
      throw notFound("Backup file");
    }
    if (file.uploadState === "COMPLETE" || file.uploadState === "CANCELLED") {
      throw conflict("UPLOAD_STATE", "This file is not accepting chunks.");
    }
    const offsetRaw = (request.query as { offset?: string }).offset;
    const offset = Number(offsetRaw ?? "0");
    if (!Number.isInteger(offset) || offset < 0) {
      throw badRequest("INVALID_OFFSET", "offset must be a non-negative integer.");
    }
    if (BigInt(offset) !== file.bytesUploaded) {
      throw conflict("RESUME_OFFSET", `Next expected offset is ${file.bytesUploaded.toString()}.`);
    }
    const chunk = request.body;
    if (!Buffer.isBuffer(chunk) || chunk.length === 0) {
      throw badRequest("EMPTY_CHUNK", "Request body must be a non-empty octet-stream.");
    }
    if (chunk.length > CHUNK_SIZE * 2) {
      throw badRequest("CHUNK_TOO_LARGE", "Chunk exceeds the allowed size.");
    }
    const next = file.bytesUploaded + BigInt(chunk.length);
    if (next > file.sizeBytes) {
      throw badRequest("SIZE_EXCEEDED", "Uploaded bytes exceed declared file size.");
    }
    await storage.putChunk(file.storageKey, offset, chunk);
    const updated = await prisma.backupFile.update({
      where: { id: file.id },
      data: {
        bytesUploaded: next,
        uploadState: "IN_PROGRESS",
        payloadState: "STAGING"
      }
    });
    return { file: serializeBackupFile(updated) };
  });

  app.post("/v1/backups/:id/files/:fileId/complete", async (request) => {
    const principal = await authenticate(request, config);
    requireDevice(principal);
    const { id, fileId } = request.params as { id: string; fileId: string };
    const backup = await loadBackupForPrincipal(id, principal);
    const file = backup.files.find((item) => item.id === fileId);
    if (!file) {
      throw notFound("Backup file");
    }
    if (file.uploadState === "COMPLETE") {
      return { file: serializeBackupFile(file) };
    }
    if (file.bytesUploaded !== file.sizeBytes) {
      throw conflict(
        "INCOMPLETE_UPLOAD",
        `Uploaded ${file.bytesUploaded.toString()} of ${file.sizeBytes.toString()} bytes.`
      );
    }
    const stored = await storage.readObject(file.storageKey);
    const digest = createHash("sha256").update(stored).digest("hex");
    if (digest !== file.checksumSha256) {
      await prisma.backupFile.update({
        where: { id: file.id },
        data: { uploadState: "FAILED", payloadState: "FAILED" }
      });
      await refreshBackupStatus(backup.id, destination);
      throw badRequest("CHECKSUM_MISMATCH", "Stored bytes do not match the declared SHA-256 checksum.");
    }
    const payloadState = destination === "CLOUD" ? "CLOUD" : "STAGING";
    const updated = await prisma.backupFile.update({
      where: { id: file.id },
      data: { uploadState: "COMPLETE", payloadState }
    });
    const header = await refreshBackupStatus(backup.id, destination);
    await writeAudit({
      actorType: "DEVICE",
      actorDeviceId: principal.deviceId,
      action: "backup.file.complete",
      resourceType: "backup_file",
      resourceId: updated.id,
      metadata: { backupId: backup.id, payloadState },
      ...requestMeta(request)
    });
    notifyBackup(backup.adminId, backup.id, header.status);
    return { file: serializeBackupFile(updated), backup: serializeBackup(header) };
  });

  app.get("/v1/backups/:id/files/:fileId/chunk", async (request, reply) => {
    const principal = await authenticate(request, config);
    requireAdmin(principal);
    const { id, fileId } = request.params as { id: string; fileId: string };
    const backup = await loadBackupForPrincipal(id, principal);
    if (["CANCELLED", "DELETED"].includes(backup.status)) {
      throw conflict("BACKUP_STATE", "This backup is not readable.");
    }
    const file = backup.files.find((item) => item.id === fileId);
    if (!file) {
      throw notFound("Backup file");
    }
    if (file.uploadState !== "COMPLETE") {
      throw conflict("UPLOAD_STATE", "File is not ready to download.");
    }
    if (file.payloadState === "LOCAL_ADMIN" && destination === "LOCAL_ADMIN") {
      throw conflict("PAYLOAD_MOVED", "Payload already resides on the admin device; staging was removed.");
    }
    const offset = Number((request.query as { offset?: string }).offset ?? "0");
    const length = Number((request.query as { length?: string }).length ?? String(CHUNK_SIZE));
    if (!Number.isInteger(offset) || offset < 0) {
      throw badRequest("INVALID_OFFSET", "offset must be a non-negative integer.");
    }
    if (!Number.isInteger(length) || length <= 0 || length > CHUNK_SIZE * 2) {
      throw badRequest("CHUNK_TOO_LARGE", "length is invalid.");
    }
    if (BigInt(offset) >= file.sizeBytes) {
      throw badRequest("INVALID_OFFSET", "offset is past the end of the file.");
    }
    const data = await storage.readRange(file.storageKey, offset, length);
    return reply
      .header("content-type", "application/octet-stream")
      .header("x-file-checksum", file.checksumSha256)
      .send(data);
  });

  app.post("/v1/backups/:id/files/:fileId/ingest", async (request) => {
    const principal = await authenticate(request, config);
    requireAdmin(principal);
    const { id, fileId } = request.params as { id: string; fileId: string };
    const backup = await loadBackupForPrincipal(id, principal);
    const file = backup.files.find((item) => item.id === fileId);
    if (!file) {
      throw notFound("Backup file");
    }
    const body = ingestBackupFileSchema.parse(request.body ?? {});
    if (file.payloadState === "LOCAL_ADMIN" || (destination === "CLOUD" && file.payloadState === "CLOUD")) {
      return { file: serializeBackupFile(file), backup: serializeBackup(backup) };
    }
    if (file.uploadState !== "COMPLETE") {
      throw conflict("UPLOAD_STATE", "File has not finished staging.");
    }
    if (body.checksumSha256.toLowerCase() !== file.checksumSha256) {
      throw badRequest("CHECKSUM_MISMATCH", "Admin checksum does not match the staged file.");
    }
    if (BigInt(body.bytesStored) !== file.sizeBytes) {
      throw badRequest("SIZE_MISMATCH", "Stored size does not match the declared file size.");
    }
    const payloadState = destination === "CLOUD" ? "CLOUD" : "LOCAL_ADMIN";
    if (destination === "LOCAL_ADMIN") {
      await storage.deleteObject(file.storageKey);
    }
    const updated = await prisma.backupFile.update({
      where: { id: file.id },
      data: { payloadState }
    });
    const header = await refreshBackupStatus(backup.id, destination);
    await writeAudit({
      actorType: "ADMIN",
      actorAdminId: principal.adminId,
      action: "backup.file.ingest",
      resourceType: "backup_file",
      resourceId: updated.id,
      metadata: { backupId: backup.id, payloadState },
      ...requestMeta(request)
    });
    notifyBackup(backup.adminId, backup.id, header.status);
    return { file: serializeBackupFile(updated), backup: serializeBackup(header) };
  });

  app.post("/v1/backups/:id/cancel", async (request) => {
    const principal = await authenticate(request, config);
    const { id } = request.params as { id: string };
    const backup = await loadBackupForPrincipal(id, principal);
    if (backup.status === "DELETED") {
      throw conflict("BACKUP_STATE", "Backup is already deleted.");
    }
    for (const file of backup.files) {
      if (file.payloadState === "STAGING" || file.payloadState === "PENDING") {
        await storage.deleteObject(file.storageKey);
      }
    }
    await prisma.backup.update({
      where: { id: backup.id },
      data: { status: "CANCELLED", payloadState: backup.payloadState }
    });
    await prisma.backupFile.updateMany({
      where: { backupId: backup.id, uploadState: { in: ["PENDING", "IN_PROGRESS"] } },
      data: { uploadState: "CANCELLED", payloadState: "FAILED" }
    });
    await writeAudit({
      actorType: principal.role === "admin" ? "ADMIN" : "DEVICE",
      actorAdminId: principal.role === "admin" ? principal.adminId : undefined,
      actorDeviceId: principal.role === "device" ? principal.deviceId : undefined,
      action: "backup.cancel",
      resourceType: "backup",
      resourceId: backup.id,
      ...requestMeta(request)
    });
    notifyBackup(backup.adminId, backup.id, "CANCELLED");
    const updated = await prisma.backup.findUniqueOrThrow({ where: { id: backup.id } });
    return { backup: serializeBackup(updated) };
  });

  app.delete("/v1/backups/:id", async (request, reply) => {
    const principal = await authenticate(request, config);
    requireAdmin(principal);
    const { id } = request.params as { id: string };
    const backup = await loadBackupForPrincipal(id, principal);
    for (const file of backup.files) {
      await storage.deleteObject(file.storageKey);
    }
    await prisma.backup.update({
      where: { id: backup.id },
      data: { status: "DELETED", payloadState: backup.payloadState }
    });
    await writeAudit({
      actorType: "ADMIN",
      actorAdminId: principal.adminId,
      action: "backup.delete",
      resourceType: "backup",
      resourceId: backup.id,
      ...requestMeta(request)
    });
    notifyBackup(backup.adminId, backup.id, "DELETED");
    return reply.code(204).send();
  });
}
