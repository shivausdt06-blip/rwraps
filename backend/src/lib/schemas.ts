import { z } from "zod";

export const capabilityStateSchema = z.enum(["AVAILABLE", "NOT_GRANTED", "RESTRICTED", "UNAVAILABLE"]);

export const capabilityStatesSchema = z
  .object({
    SCREEN_CAPTURE: capabilityStateSchema.optional(),
    ACCESSIBILITY_CONTROL: capabilityStateSchema.optional(),
    REMOTE_INTERACTION: capabilityStateSchema.optional(),
    FILE_BACKUP: capabilityStateSchema.optional(),
    BACKGROUND_SESSION: capabilityStateSchema.optional()
  })
  .optional();

export const capabilitiesSchema = z.object({
  screenCapture: z.boolean().default(false),
  fileBackup: z.boolean().default(false),
  remoteInput: z.boolean().default(false),
  accessibilityControl: z.boolean().default(false),
  backgroundSession: z.boolean().default(false),
  states: capabilityStatesSchema
});

export const registerSchema = z.object({
  email: z.string().email(),
  password: z.string().min(12).max(200),
  displayName: z.string().min(1).max(80)
});

export const loginSchema = z.object({
  email: z.string().email(),
  password: z.string().min(1).max(200)
});

export const refreshSchema = z.object({
  refreshToken: z.string().min(16)
});

export const logoutSchema = z.object({
  refreshToken: z.string().min(16).optional()
});

export const deviceInfoSchema = z.object({
  name: z.string().min(1).max(80),
  platform: z.literal("android").default("android"),
  androidVersion: z.string().max(32).optional(),
  manufacturer: z.string().max(80).optional(),
  model: z.string().max(80).optional(),
  sdkInt: z.number().int().min(1).max(100).optional(),
  capabilities: capabilitiesSchema.default({
    screenCapture: false,
    fileBackup: false,
    remoteInput: false
  })
});

export const pairingClaimSchema = z.object({
  pairingCode: z.string().min(6).max(16),
  device: deviceInfoSchema
});

export const pairingConfirmSchema = z.object({
  claimToken: z.string().min(16)
});

export const patchDeviceSchema = z.object({
  name: z.string().min(1).max(80).optional()
});

export const heartbeatSchema = z.object({
  capabilities: capabilitiesSchema.optional(),
  androidVersion: z.string().max(32).optional()
});

export const sessionModeSchema = z.enum(["MONITOR", "MANAGED"]);

export const createSessionSchema = z.object({
  deviceId: z.string().uuid(),
  mode: sessionModeSchema.optional()
});

export const setSessionModeSchema = z.object({
  mode: sessionModeSchema
});

export const telemetrySchema = z.object({
  rttMs: z.number().min(0),
  packetLoss: z.number().min(0).max(1),
  bitrateKbps: z.number().min(0)
});

export const createBackupSchema = z.object({
  deviceId: z.string().uuid().optional()
});

export const createBackupFileSchema = z.object({
  filename: z.string().min(1).max(255),
  sizeBytes: z.number().int().positive().max(1024 * 1024 * 1024),
  mimeType: z.string().min(1).max(127),
  checksumSha256: z.string().regex(/^[a-f0-9]{64}$/i)
});

export const ingestBackupFileSchema = z.object({
  checksumSha256: z.string().regex(/^[a-f0-9]{64}$/i),
  bytesStored: z.number().int().nonnegative()
});

export const wsEnvelopeSchema = z.object({
  v: z.literal(1),
  id: z.string().max(80).optional(),
  type: z.string().min(1).max(64),
  payload: z.unknown().optional()
});
