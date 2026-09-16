import { describe, expect, it } from "vitest";
import type { Device, RemoteSession } from "@prisma/client";
import { asCapabilities } from "../../src/lib/serialize.js";
import {
  ReplayCache,
  authorizeAdminCommand,
  authorizeDeviceResult,
  parseInteractionCommand,
  remoteInteractionAvailable
} from "../../src/interaction/protocol.js";

function command(overrides: Record<string, unknown> = {}) {
  return {
    commandId: "11111111-1111-4111-8111-111111111111",
    sessionId: "22222222-2222-4222-8222-222222222222",
    timestamp: 1_000_000,
    operation: "BACK",
    capability: "REMOTE_INTERACTION",
    ...overrides
  };
}

function session(overrides: Partial<RemoteSession> = {}): RemoteSession {
  return {
    id: "22222222-2222-4222-8222-222222222222",
    deviceId: "33333333-3333-4333-8333-333333333333",
    adminId: "44444444-4444-4444-8444-444444444444",
    status: "ACTIVE",
    startedAt: new Date(),
    endedAt: null,
    timeoutAt: new Date(),
    reconnectCount: 0,
    mode: "MANAGED",
    quality: null,
    createdAt: new Date(),
    updatedAt: new Date(),
    ...overrides
  } as RemoteSession;
}

function device(overrides: Partial<Device> = {}): Device {
  return {
    id: "33333333-3333-4333-8333-333333333333",
    capabilities: {
      screenCapture: true,
      fileBackup: true,
      remoteInput: true,
      states: { REMOTE_INTERACTION: "AVAILABLE" }
    },
    ...overrides
  } as Device;
}

describe("capability serialization", () => {
  it("does not mark remote interaction available from booleans alone when states say otherwise", () => {
    const caps = asCapabilities({
      screenCapture: true,
      fileBackup: true,
      remoteInput: true,
      states: { REMOTE_INTERACTION: "NOT_GRANTED" }
    });
    expect(caps.remoteInput).toBe(true);
    expect(caps.states?.REMOTE_INTERACTION).toBe("NOT_GRANTED");
    expect(remoteInteractionAvailable({ capabilities: caps })).toBe(false);
  });

  it("treats missing states as the boolean remoteInput flag", () => {
    expect(remoteInteractionAvailable({ capabilities: { remoteInput: true } })).toBe(true);
    expect(remoteInteractionAvailable({ capabilities: { remoteInput: false } })).toBe(false);
  });
});

describe("interaction command validation", () => {
  it("rejects malformed commands", () => {
    expect(parseInteractionCommand({}).ok).toBe(false);
    expect(parseInteractionCommand(command({ operation: "EXPLODE" })).ok).toBe(false);
    expect(parseInteractionCommand(command({ operation: "TAP" })).ok).toBe(false);
    expect(parseInteractionCommand(command({ operation: "TEXT_ENTRY", params: { text: "x".repeat(501) } })).ok).toBe(
      false
    );
  });

  it("accepts a valid TAP", () => {
    const parsed = parseInteractionCommand(command({ operation: "TAP", params: { nx: 0.5, ny: 0.5 } }));
    expect(parsed.ok).toBe(true);
  });
});

describe("interaction authorization", () => {
  const replay = new ReplayCache();
  const now = 1_000_000;

  it("rejects another admin", () => {
    const result = authorizeAdminCommand({
      adminId: "55555555-5555-4555-8555-555555555555",
      session: session(),
      device: device(),
      command: command(),
      now,
      replay: new ReplayCache()
    });
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.code).toBe("FORBIDDEN");
  });

  it("rejects terminated sessions", () => {
    const result = authorizeAdminCommand({
      adminId: session().adminId,
      session: session({ status: "TERMINATED" }),
      device: device(),
      command: command(),
      now,
      replay: new ReplayCache()
    });
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.code).toBe("SESSION_ENDED");
  });

  it("rejects CREATED sessions before target consent", () => {
    const result = authorizeAdminCommand({
      adminId: session().adminId,
      session: session({ status: "CREATED" }),
      device: device(),
      command: command(),
      now,
      replay: new ReplayCache()
    });
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.code).toBe("SESSION_NOT_READY");
  });

  it("rejects interaction in MONITOR mode", () => {
    const result = authorizeAdminCommand({
      adminId: session().adminId,
      session: session({ mode: "MONITOR" }),
      device: device(),
      command: command(),
      now,
      replay: new ReplayCache()
    });
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.code).toBe("MODE_VIEW_ONLY");
  });

  it("allows MANAGED interaction when capability cache is stale (Target enforces Accessibility)", () => {
    const result = authorizeAdminCommand({
      adminId: session().adminId,
      session: session(),
      device: device({
        capabilities: { remoteInput: false, states: { REMOTE_INTERACTION: "NOT_GRANTED" } }
      }),
      command: command(),
      now,
      replay: new ReplayCache()
    });
    expect(result.ok).toBe(true);
  });

  it("rejects stale and replayed commands", () => {
    const cache = new ReplayCache();
    const first = authorizeAdminCommand({
      adminId: session().adminId,
      session: session(),
      device: device(),
      command: command(),
      now,
      replay: cache
    });
    expect(first.ok).toBe(true);
    const replayed = authorizeAdminCommand({
      adminId: session().adminId,
      session: session(),
      device: device(),
      command: command(),
      now: now + 10,
      replay: cache
    });
    expect(replayed.ok).toBe(false);
    if (!replayed.ok) expect(replayed.code).toBe("REPLAYED_COMMAND");
    const stale = authorizeAdminCommand({
      adminId: session().adminId,
      session: session(),
      device: device(),
      command: command({ commandId: "66666666-6666-4666-8666-666666666666", timestamp: now - 60_000 }),
      now,
      replay
    });
    expect(stale.ok).toBe(false);
    if (!stale.ok) expect(stale.code).toBe("STALE_COMMAND");
  });

  it("rejects device results from a different device", () => {
    const result = authorizeDeviceResult({
      deviceId: "99999999-9999-4999-8999-999999999999",
      session: session(),
      commandId: "11111111-1111-4111-8111-111111111111"
    });
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.code).toBe("FORBIDDEN");
  });
});
