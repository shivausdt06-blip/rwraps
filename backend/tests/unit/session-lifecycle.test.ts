import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { FastifyInstance } from "fastify";
import { createTestApp, enrollDevice, registerAdmin, resetDatabase } from "../helpers.js";
import { config } from "../helpers.js";
import { prisma } from "../../src/lib/prisma.js";
import { extendSessionIdleDeadline, nextSessionTimeoutAt } from "../../src/lib/session-lifecycle.js";
import { sweepSessionTimeouts } from "../../src/jobs/sweep.js";

let app: FastifyInstance;

beforeAll(async () => {
  app = await createTestApp();
});

beforeEach(async () => {
  await resetDatabase();
});

afterAll(async () => {
  await app.close();
});

describe("session idle deadline", () => {
  it("computes timeout from configured inactivity window", () => {
    const base = Date.parse("2026-01-01T00:00:00.000Z");
    const deadline = nextSessionTimeoutAt(config, base);
    expect(deadline.getTime()).toBe(base + config.SESSION_TIMEOUT_SECONDS * 1000);
  });

  it("extends timeoutAt on activity so healthy sessions are not swept", async () => {
    const registered = await registerAdmin(app);
    const enrolled = await enrollDevice(app, registered.tokens.accessToken, "Phone");
    const created = await app.inject({
      method: "POST",
      url: "/v1/sessions",
      headers: { authorization: `Bearer ${registered.tokens.accessToken}` },
      payload: { deviceId: enrolled.device.id, mode: "MONITOR" }
    });
    const sessionId = created.json().session.id as string;

    const past = new Date(Date.now() - 60_000);
    await prisma.remoteSession.update({
      where: { id: sessionId },
      data: { timeoutAt: past, status: "ACTIVE" }
    });

    await extendSessionIdleDeadline(config, sessionId);
    const swept = await sweepSessionTimeouts();
    expect(swept).toBe(0);

    const row = await prisma.remoteSession.findUnique({ where: { id: sessionId } });
    expect(row?.status).toBe("ACTIVE");
    expect(row?.timeoutAt!.getTime()).toBeGreaterThan(Date.now());
  });

  it("sweeps only genuinely expired non-terminal sessions", async () => {
    const registered = await registerAdmin(app);
    const enrolled = await enrollDevice(app, registered.tokens.accessToken, "Phone");
    const created = await app.inject({
      method: "POST",
      url: "/v1/sessions",
      headers: { authorization: `Bearer ${registered.tokens.accessToken}` },
      payload: { deviceId: enrolled.device.id, mode: "MONITOR" }
    });
    const sessionId = created.json().session.id as string;

    await prisma.remoteSession.update({
      where: { id: sessionId },
      data: { timeoutAt: new Date(Date.now() - 1000), status: "ACTIVE" }
    });

    const swept = await sweepSessionTimeouts();
    expect(swept).toBe(1);

    const row = await prisma.remoteSession.findUnique({ where: { id: sessionId } });
    expect(row?.status).toBe("TIMED_OUT");
  });
});
