import { z } from "zod";
import type { Device, RemoteSession } from "@prisma/client";
import { asCapabilities } from "../lib/serialize.js";

export const INTERACTION_MAX_TEXT = 500;
export const INTERACTION_MAX_VIEW_ID = 256;
export const INTERACTION_SKEW_MS = 30_000;
export const LIVE_SESSION_STATUSES = ["AUTHENTICATED", "ACTIVE", "RECONNECTING"] as const;

export const interactionOperations = [
  "TAP",
  "LONG_PRESS",
  "SWIPE",
  "SCROLL",
  "BACK",
  "HOME",
  "RECENTS",
  "NODE_CLICK",
  "NODE_FOCUS",
  "TEXT_ENTRY"
] as const;

export const interactionCommandSchema = z
  .object({
    commandId: z.string().uuid(),
    sessionId: z.string().uuid(),
    timestamp: z.number().int(),
    operation: z.enum(interactionOperations),
    capability: z.literal("REMOTE_INTERACTION"),
    params: z
      .object({
        nx: z.number().min(0).max(1).optional(),
        ny: z.number().min(0).max(1).optional(),
        nx2: z.number().min(0).max(1).optional(),
        ny2: z.number().min(0).max(1).optional(),
        durationMs: z.number().int().min(1).max(5_000).optional(),
        direction: z.enum(["UP", "DOWN", "LEFT", "RIGHT"]).optional(),
        viewId: z.string().min(1).max(INTERACTION_MAX_VIEW_ID).optional(),
        text: z.string().min(1).max(INTERACTION_MAX_TEXT).optional()
      })
      .optional()
  })
  .superRefine((value, ctx) => {
    const p = value.params ?? {};
    if (value.operation === "TAP" || value.operation === "LONG_PRESS") {
      if (p.nx === undefined || p.ny === undefined) {
        ctx.addIssue({ code: z.ZodIssueCode.custom, message: "TAP/LONG_PRESS require nx and ny." });
      }
    }
    if (value.operation === "SWIPE") {
      if (p.nx === undefined || p.ny === undefined || p.nx2 === undefined || p.ny2 === undefined) {
        ctx.addIssue({ code: z.ZodIssueCode.custom, message: "SWIPE requires nx, ny, nx2, ny2." });
      }
    }
    if (value.operation === "SCROLL" && p.direction === undefined) {
      ctx.addIssue({ code: z.ZodIssueCode.custom, message: "SCROLL requires direction." });
    }
    if ((value.operation === "NODE_CLICK" || value.operation === "NODE_FOCUS") && !p.viewId) {
      ctx.addIssue({ code: z.ZodIssueCode.custom, message: "Node operations require viewId." });
    }
    if (value.operation === "TEXT_ENTRY" && !p.text) {
      ctx.addIssue({ code: z.ZodIssueCode.custom, message: "TEXT_ENTRY requires text." });
    }
  });

export const interactionResultSchema = z.object({
  commandId: z.string().uuid(),
  sessionId: z.string().uuid(),
  ok: z.boolean(),
  code: z.string().max(64).optional(),
  message: z.string().max(400).optional(),
  latencyMs: z.number().int().min(0).max(60_000).optional()
});

export type InteractionCommand = z.infer<typeof interactionCommandSchema>;

export class ReplayCache {
  private readonly seen = new Map<string, number>();

  constructor(private readonly ttlMs = 120_000) {}

  remember(id: string, now: number): boolean {
    this.prune(now);
    if (this.seen.has(id)) {
      return false;
    }
    this.seen.set(id, now + this.ttlMs);
    return true;
  }

  has(id: string): boolean {
    return this.seen.has(id);
  }

  prune(now: number): void {
    for (const [id, exp] of this.seen) {
      if (exp <= now) {
        this.seen.delete(id);
      }
    }
  }

  clear(): void {
    this.seen.clear();
  }
}

export type AuthzFailure = { ok: false; code: string; message: string };
export type AuthzSuccess<T> = { ok: true; value: T };
export type AuthzResult<T> = AuthzFailure | AuthzSuccess<T>;

export function parseInteractionCommand(payload: unknown): AuthzResult<InteractionCommand> {
  const parsed = interactionCommandSchema.safeParse(payload);
  if (!parsed.success) {
    return { ok: false, code: "MALFORMED_COMMAND", message: "Invalid interaction command." };
  }
  return { ok: true, value: parsed.data };
}

export function remoteInteractionAvailable(device: Pick<Device, "capabilities">): boolean {
  const caps = asCapabilities(device.capabilities);
  const state = caps.states?.REMOTE_INTERACTION;
  if (state === "AVAILABLE") {
    return true;
  }
  if (state === "NOT_GRANTED" || state === "RESTRICTED" || state === "UNAVAILABLE") {
    return false;
  }
  return caps.remoteInput === true;
}

export function authorizeAdminCommand(input: {
  adminId: string;
  session: RemoteSession | null;
  device: Device | null;
  command: InteractionCommand;
  now: number;
  replay: ReplayCache;
}): AuthzResult<InteractionCommand> {
  const { adminId, session, device, command, now, replay } = input;
  if (!session) {
    return { ok: false, code: "NOT_FOUND", message: "Session not found." };
  }
  if (session.adminId !== adminId) {
    return { ok: false, code: "FORBIDDEN", message: "Not the authorized admin for this session." };
  }
  if (command.sessionId !== session.id) {
    return { ok: false, code: "FORBIDDEN", message: "Command session does not match." };
  }
  if (["TERMINATED", "REVOKED", "TIMED_OUT"].includes(session.status)) {
    return { ok: false, code: "SESSION_ENDED", message: "Session is not active." };
  }
  if (!LIVE_SESSION_STATUSES.includes(session.status as (typeof LIVE_SESSION_STATUSES)[number])) {
    return { ok: false, code: "SESSION_NOT_READY", message: "Session has not been authenticated." };
  }
  if (session.mode === "MONITOR") {
    return {
      ok: false,
      code: "MODE_VIEW_ONLY",
      message: "Remote interaction is not allowed in MONITOR mode."
    };
  }
  if (!device || device.id !== session.deviceId) {
    return { ok: false, code: "FORBIDDEN", message: "Device is not a session participant." };
  }
  // MANAGED sessions defer Accessibility enforcement to the Target at execution time.
  // Cached device capability snapshots can lag behind the live Accessibility service.
  if (session.mode !== "MANAGED" && !remoteInteractionAvailable(device)) {
    return {
      ok: false,
      code: "CAPABILITY_DISABLED",
      message: "REMOTE_INTERACTION is not available on this device."
    };
  }
  if (Math.abs(now - command.timestamp) > INTERACTION_SKEW_MS) {
    return { ok: false, code: "STALE_COMMAND", message: "Command timestamp is outside the allowed window." };
  }
  if (!replay.remember(command.commandId, now)) {
    return { ok: false, code: "REPLAYED_COMMAND", message: "Command ID has already been used." };
  }
  return { ok: true, value: command };
}

export function authorizeDeviceResult(input: {
  deviceId: string;
  session: RemoteSession | null;
  commandId: string;
}): AuthzResult<{ session: RemoteSession }> {
  const { deviceId, session } = input;
  if (!session) {
    return { ok: false, code: "NOT_FOUND", message: "Session not found." };
  }
  if (session.deviceId !== deviceId) {
    return { ok: false, code: "FORBIDDEN", message: "Not a session participant." };
  }
  if (["TERMINATED", "REVOKED", "TIMED_OUT"].includes(session.status)) {
    return { ok: false, code: "SESSION_ENDED", message: "Session is not active." };
  }
  return { ok: true, value: { session } };
}
