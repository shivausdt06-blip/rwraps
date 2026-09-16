import { createHash, randomUUID } from "node:crypto";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { FastifyInstance } from "fastify";
import {
  authHeader,
  createTestApp,
  enrollDevice,
  registerAdmin,
  resetDatabase
} from "../helpers.js";

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

describe("pairing qrPayload", () => {
  it("includes code, session, and Target API when a LAN or public base is known", async () => {
    const registered = await registerAdmin(app);
    const created = await app.inject({
      method: "POST",
      url: "/v1/pairing-sessions",
      headers: authHeader(registered.tokens.accessToken)
    });
    expect(created.statusCode).toBe(201);
    const pairing = created.json().pairingSession as {
      id: string;
      pairingCode: string;
      qrPayload: string;
    };
    expect(pairing.qrPayload.startsWith("arl://pair?")).toBe(true);
    const query = new URLSearchParams(pairing.qrPayload.slice("arl://pair?".length));
    expect(query.get("code")).toBe(pairing.pairingCode);
    expect(query.get("session")).toBe(pairing.id);
    const api = query.get("api");
    if (api) {
      expect(api.startsWith("http://") || api.startsWith("https://")).toBe(true);
      expect(api.includes("10.0.2.2")).toBe(false);
    }
  });
});

describe("health", () => {
  it("returns liveness without auth", async () => {
    const res = await app.inject({ method: "GET", url: "/health" });
    expect(res.statusCode).toBe(200);
    expect(res.json()).toMatchObject({ status: "ok", service: "android-remote-lab-api" });
  });

  it("returns readiness when the database is up", async () => {
    const res = await app.inject({ method: "GET", url: "/ready" });
    expect(res.statusCode).toBe(200);
    expect(res.json()).toEqual({ status: "ready" });
  });
});

describe("admin auth, pairing, devices, sessions, backups", () => {
  it("registers, pairs, lists devices, runs a session, and stores a backup file", async () => {
    const registered = await registerAdmin(app);
    const me = await app.inject({
      method: "GET",
      url: "/v1/auth/me",
      headers: authHeader(registered.tokens.accessToken)
    });
    expect(me.statusCode).toBe(200);
    expect(me.json().admin.email).toBe(registered.admin.email);

    const enrolled: Array<{ device: { id: string }; tokens: { accessToken: string } }> = [];
    for (let i = 0; i < 10; i += 1) {
      enrolled.push(await enrollDevice(app, registered.tokens.accessToken, `Lab Phone ${i + 1}`));
    }

    const list = await app.inject({
      method: "GET",
      url: "/v1/devices",
      headers: authHeader(registered.tokens.accessToken)
    });
    expect(list.statusCode).toBe(200);
    expect(list.json().devices).toHaveLength(10);

    const replay = await app.inject({
      method: "POST",
      url: "/v1/pairing/claim",
      payload: {
        pairingCode: "AAAAAAAA",
        device: { name: "X", platform: "android" }
      }
    });
    expect(replay.statusCode).toBe(401);

    const createdSession = await app.inject({
      method: "POST",
      url: "/v1/sessions",
      headers: authHeader(registered.tokens.accessToken),
      payload: { deviceId: enrolled[0]!.device.id }
    });
    expect(createdSession.statusCode).toBe(201);
    const sessionId = createdSession.json().session.id as string;

    const authenticated = await app.inject({
      method: "POST",
      url: `/v1/sessions/${sessionId}/authenticate`,
      headers: authHeader(enrolled[0]!.tokens.accessToken)
    });
    expect(authenticated.statusCode).toBe(200);
    expect(authenticated.json().session.status).toBe("AUTHENTICATED");

    const activated = await app.inject({
      method: "POST",
      url: `/v1/sessions/${sessionId}/activate`,
      headers: authHeader(registered.tokens.accessToken)
    });
    expect(activated.json().session.status).toBe("ACTIVE");

    const ice = await app.inject({
      method: "GET",
      url: "/v1/ice-servers",
      headers: authHeader(registered.tokens.accessToken)
    });
    expect(ice.statusCode).toBe(200);
    expect(ice.json().iceServers[0].urls[0]).toMatch(/^stun:/);

    const heartbeat = await app.inject({
      method: "POST",
      url: "/v1/devices/me/heartbeat",
      headers: authHeader(enrolled[0]!.tokens.accessToken),
      payload: {}
    });
    expect(heartbeat.statusCode).toBe(200);
    expect(heartbeat.json().device.connectionState).toBe("ONLINE");

    const backup = await app.inject({
      method: "POST",
      url: "/v1/backups",
      headers: authHeader(registered.tokens.accessToken),
      payload: { deviceId: enrolled[0]!.device.id }
    });
    expect(backup.statusCode).toBe(201);
    const backupId = backup.json().backup.id as string;
    const payload = Buffer.from("authorized-file-bytes");
    const checksum = createHash("sha256").update(payload).digest("hex");

    const fileRes = await app.inject({
      method: "POST",
      url: `/v1/backups/${backupId}/files`,
      headers: authHeader(enrolled[0]!.tokens.accessToken),
      payload: {
        filename: "notes.txt",
        sizeBytes: payload.length,
        mimeType: "text/plain",
        checksumSha256: checksum
      }
    });
    expect(fileRes.statusCode).toBe(201);
    const fileId = fileRes.json().file.id as string;

    const chunk = await app.inject({
      method: "PUT",
      url: `/v1/backups/${backupId}/files/${fileId}/chunk?offset=0`,
      headers: {
        ...authHeader(enrolled[0]!.tokens.accessToken),
        "content-type": "application/octet-stream"
      },
      payload: payload
    });
    expect(chunk.statusCode).toBe(200);

    const complete = await app.inject({
      method: "POST",
      url: `/v1/backups/${backupId}/files/${fileId}/complete`,
      headers: authHeader(enrolled[0]!.tokens.accessToken)
    });
    expect(complete.statusCode).toBe(200);
    expect(complete.json().file.uploadState).toBe("COMPLETE");
    expect(complete.json().backup.status).toBe("STAGED");

    const download = await app.inject({
      method: "GET",
      url: `/v1/backups/${backupId}/files/${fileId}/chunk?offset=0&length=32`,
      headers: authHeader(registered.tokens.accessToken)
    });
    expect(download.statusCode).toBe(200);
    expect(Buffer.from(download.rawPayload).toString()).toBe("authorized-file-bytes");

    const otherAdmin = await registerAdmin(app);
    const foreign = await app.inject({
      method: "GET",
      url: `/v1/backups/${backupId}/files/${fileId}/chunk?offset=0&length=32`,
      headers: authHeader(otherAdmin.tokens.accessToken)
    });
    expect(foreign.statusCode).toBe(403);

    const badIngest = await app.inject({
      method: "POST",
      url: `/v1/backups/${backupId}/files/${fileId}/ingest`,
      headers: authHeader(registered.tokens.accessToken),
      payload: { checksumSha256: "ab".repeat(32), bytesStored: payload.length }
    });
    expect(badIngest.statusCode).toBe(400);

    const ingest = await app.inject({
      method: "POST",
      url: `/v1/backups/${backupId}/files/${fileId}/ingest`,
      headers: authHeader(registered.tokens.accessToken),
      payload: { checksumSha256: checksum, bytesStored: payload.length }
    });
    expect(ingest.statusCode).toBe(200);
    expect(ingest.json().file.payloadState).toBe("LOCAL_ADMIN");
    expect(ingest.json().backup.status).toBe("COMPLETE");

    const ingestAgain = await app.inject({
      method: "POST",
      url: `/v1/backups/${backupId}/files/${fileId}/ingest`,
      headers: authHeader(registered.tokens.accessToken),
      payload: { checksumSha256: checksum, bytesStored: payload.length }
    });
    expect(ingestAgain.statusCode).toBe(200);

    const missing = await app.inject({
      method: "POST",
      url: `/v1/backups/${randomUUID()}/files/${fileId}/ingest`,
      headers: authHeader(registered.tokens.accessToken),
      payload: { checksumSha256: checksum, bytesStored: payload.length }
    });
    expect(missing.statusCode).toBe(404);

    const audit = await app.inject({
      method: "GET",
      url: "/v1/audit-events",
      headers: authHeader(registered.tokens.accessToken)
    });
    expect(audit.statusCode).toBe(200);
    expect(audit.json().events.length).toBeGreaterThan(0);

    const terminated = await app.inject({
      method: "POST",
      url: `/v1/sessions/${sessionId}/terminate`,
      headers: authHeader(registered.tokens.accessToken)
    });
    expect(terminated.json().session.status).toBe("TERMINATED");
  });

  it("rejects pairing replay after confirm", async () => {
    const admin = await registerAdmin(app);
    const created = await app.inject({
      method: "POST",
      url: "/v1/pairing-sessions",
      headers: authHeader(admin.tokens.accessToken)
    });
    const code = created.json().pairingSession.pairingCode as string;
    const first = await app.inject({
      method: "POST",
      url: "/v1/pairing/claim",
      payload: { pairingCode: code, device: { name: "One", platform: "android" } }
    });
    expect(first.statusCode).toBe(200);
    const second = await app.inject({
      method: "POST",
      url: "/v1/pairing/claim",
      payload: { pairingCode: code, device: { name: "Two", platform: "android" } }
    });
    expect(second.statusCode).toBe(409);
  });
});
