import type { Admin, Backup, BackupFile, Device, Enrollment, PairingSession, RemoteSession } from "@prisma/client";
import type { CapabilityKey, CapabilityState, DeviceCapabilities } from "@arl/shared";

const CAPABILITY_KEYS: CapabilityKey[] = [
  "SCREEN_CAPTURE",
  "ACCESSIBILITY_CONTROL",
  "REMOTE_INTERACTION",
  "FILE_BACKUP",
  "BACKGROUND_SESSION"
];
const CAPABILITY_STATES = new Set<CapabilityState>(["AVAILABLE", "NOT_GRANTED", "RESTRICTED", "UNAVAILABLE"]);

export function serializeAdmin(admin: Admin) {
  return {
    id: admin.id,
    email: admin.email,
    displayName: admin.displayName,
    createdAt: admin.createdAt.toISOString(),
    updatedAt: admin.updatedAt.toISOString()
  };
}

export function defaultCapabilities(): DeviceCapabilities {
  return {
    screenCapture: false,
    fileBackup: false,
    remoteInput: false,
    accessibilityControl: false,
    backgroundSession: false,
    states: {}
  };
}

function asCapabilityState(value: unknown): CapabilityState | undefined {
  return typeof value === "string" && CAPABILITY_STATES.has(value as CapabilityState)
    ? (value as CapabilityState)
    : undefined;
}

export function asCapabilities(value: unknown): DeviceCapabilities {
  const base = defaultCapabilities();
  if (!value || typeof value !== "object") {
    return base;
  }
  const rec = value as Record<string, unknown>;
  const states: Partial<Record<CapabilityKey, CapabilityState>> = {};
  if (rec.states && typeof rec.states === "object") {
    const raw = rec.states as Record<string, unknown>;
    for (const key of CAPABILITY_KEYS) {
      const parsed = asCapabilityState(raw[key]);
      if (parsed) {
        states[key] = parsed;
      }
    }
  }
  return {
    screenCapture: rec.screenCapture === true,
    fileBackup: rec.fileBackup === true,
    remoteInput: rec.remoteInput === true,
    accessibilityControl: rec.accessibilityControl === true,
    backgroundSession: rec.backgroundSession === true,
    states
  };
}

export function serializeDevice(device: Device) {
  return {
    id: device.id,
    name: device.name,
    enrollmentState: device.enrollmentState,
    authorizationState: device.authorizationState,
    platform: device.platform,
    androidVersion: device.androidVersion,
    manufacturer: device.manufacturer,
    model: device.model,
    sdkInt: device.sdkInt,
    lastSeenAt: device.lastSeenAt?.toISOString() ?? null,
    connectionState: device.connectionState,
    capabilities: asCapabilities(device.capabilities),
    createdAt: device.createdAt.toISOString(),
    updatedAt: device.updatedAt.toISOString()
  };
}

export function serializePairingPublic(session: PairingSession) {
  return {
    id: session.id,
    status: session.status,
    expiresAt: session.expiresAt.toISOString(),
    claimedAt: session.claimedAt?.toISOString() ?? null,
    usedAt: session.usedAt?.toISOString() ?? null,
    revokedAt: session.revokedAt?.toISOString() ?? null,
    deviceId: session.deviceId,
    createdAt: session.createdAt.toISOString()
  };
}

export function serializeEnrollment(enrollment: Enrollment) {
  return {
    id: enrollment.id,
    deviceId: enrollment.deviceId,
    adminId: enrollment.adminId,
    status: enrollment.status,
    revokedAt: enrollment.revokedAt?.toISOString() ?? null,
    createdAt: enrollment.createdAt.toISOString(),
    updatedAt: enrollment.updatedAt.toISOString()
  };
}

export function serializeSession(session: RemoteSession) {
  return {
    id: session.id,
    deviceId: session.deviceId,
    adminId: session.adminId,
    status: session.status,
    mode: session.mode,
    startedAt: session.startedAt?.toISOString() ?? null,
    endedAt: session.endedAt?.toISOString() ?? null,
    timeoutAt: session.timeoutAt?.toISOString() ?? null,
    reconnectCount: session.reconnectCount,
    quality: session.quality,
    createdAt: session.createdAt.toISOString(),
    updatedAt: session.updatedAt.toISOString()
  };
}

export function serializeBackup(backup: Backup) {
  return {
    id: backup.id,
    deviceId: backup.deviceId,
    adminId: backup.adminId,
    status: backup.status,
    storageProvider: backup.storageProvider,
    payloadState: backup.payloadState,
    createdAt: backup.createdAt.toISOString(),
    updatedAt: backup.updatedAt.toISOString()
  };
}

export function serializeBackupFile(file: BackupFile) {
  return {
    id: file.id,
    backupId: file.backupId,
    filename: file.filename,
    sizeBytes: file.sizeBytes.toString(),
    mimeType: file.mimeType,
    checksumSha256: file.checksumSha256,
    uploadState: file.uploadState,
    payloadState: file.payloadState,
    storageKey: file.storageKey,
    bytesUploaded: file.bytesUploaded.toString(),
    createdAt: file.createdAt.toISOString(),
    updatedAt: file.updatedAt.toISOString()
  };
}
