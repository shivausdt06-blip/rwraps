import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { WebSocket } from "ws";
import type { FastifyInstance } from "fastify";
import {
  createTestApp,
  enrollDevice,
  registerAdmin,
  resetDatabase
} from "../helpers.js";

let app: FastifyInstance;
let baseUrl: string;

beforeAll(async () => {
  app = await createTestApp();
  await app.listen({ host: "127.0.0.1", port: 0 });
  const address = app.server.address();
  if (!address || typeof address === "string") {
    throw new Error("failed to bind test server");
  }
  baseUrl = `ws://127.0.0.1:${address.port}/v1/ws`;
});

beforeEach(async () => {
  await resetDatabase();
});

afterAll(async () => {
  await app.close();
});

function onceMessage(socket: WebSocket): Promise<Record<string, unknown>> {
  return new Promise((resolve, reject) => {
    socket.once("message", (data) => {
      resolve(JSON.parse(String(data)) as Record<string, unknown>);
    });
    socket.once("error", reject);
  });
}

function waitForType(socket: WebSocket, type: string, timeoutMs = 5_000): Promise<Record<string, unknown>> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      socket.off("message", onMessage);
      reject(new Error(`timed out waiting for ${type}`));
    }, timeoutMs);
    function onMessage(data: WebSocket.RawData) {
      const parsed = JSON.parse(String(data)) as Record<string, unknown>;
      if (parsed.type === type) {
        clearTimeout(timer);
        socket.off("message", onMessage);
        resolve(parsed);
      }
    }
    socket.on("message", onMessage);
  });
}

describe("websocket signaling", () => {
  it("authenticates an admin and a device then forwards an offer", async () => {
    const admin = await registerAdmin(app);
    const device = await enrollDevice(app, admin.tokens.accessToken, "WS Device");
    const sessionRes = await app.inject({
      method: "POST",
      url: "/v1/sessions",
      headers: { authorization: `Bearer ${admin.tokens.accessToken}` },
      payload: { deviceId: device.device.id }
    });
    const sessionId = sessionRes.json().session.id as string;

    const adminWs = new WebSocket(baseUrl);
    await new Promise((resolve) => adminWs.once("open", resolve));
    adminWs.send(
      JSON.stringify({
        v: 1,
        id: "a1",
        type: "auth",
        payload: { role: "admin", accessToken: admin.tokens.accessToken }
      })
    );
    const adminAuth = await onceMessage(adminWs);
    expect(adminAuth.type).toBe("auth.ok");

    const deviceWs = new WebSocket(baseUrl);
    await new Promise((resolve) => deviceWs.once("open", resolve));
    deviceWs.send(
      JSON.stringify({
        v: 1,
        id: "d1",
        type: "auth",
        payload: { role: "device", accessToken: device.tokens.accessToken }
      })
    );
    const deviceAuth = await onceMessage(deviceWs);
    expect(deviceAuth.type).toBe("auth.ok");

    const unauth = await app.inject({ method: "GET", url: "/v1/ws" });
    expect(unauth.statusCode).not.toBe(401);

    const iceNoToken = await app.inject({ method: "GET", url: "/v1/ice-servers" });
    expect(iceNoToken.statusCode).toBe(401);

    const iceDevice = await app.inject({
      method: "GET",
      url: "/v1/ice-servers",
      headers: { authorization: `Bearer ${device.tokens.accessToken}` }
    });
    expect(iceDevice.statusCode).toBe(200);

    const offerWait = onceMessage(deviceWs);
    adminWs.send(
      JSON.stringify({
        v: 1,
        type: "signaling.offer",
        payload: { sessionId, sdp: "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\n" }
      })
    );
    const forwarded = await offerWait;
    expect(forwarded.type).toBe("signaling.offer");

    const adminClosed = new Promise<void>((resolve) => adminWs.once("close", () => resolve()));
    const deviceClosed = new Promise<void>((resolve) => deviceWs.once("close", () => resolve()));
    adminWs.close();
    deviceWs.close();
    await Promise.all([adminClosed, deviceClosed]);
  });

  it("rejects a device access token presented as an admin websocket principal", async () => {
    const admin = await registerAdmin(app);
    const device = await enrollDevice(app, admin.tokens.accessToken, "Role Mismatch Device");
    const socket = new WebSocket(baseUrl);
    await new Promise((resolve) => socket.once("open", resolve));
    const closed = new Promise<void>((resolve) => socket.once("close", () => resolve()));
    socket.send(
      JSON.stringify({
        v: 1,
        id: "bad-role",
        type: "auth",
        payload: { role: "admin", accessToken: device.tokens.accessToken }
      })
    );
    const err = await onceMessage(socket);
    expect(err.type).toBe("error");
    expect((err.payload as { code: string }).code).toBe("UNAUTHORIZED");
    await closed;
  });

  it("authorizes interaction commands only for live sessions with remote interaction", async () => {
    const admin = await registerAdmin(app);
    const device = await enrollDevice(app, admin.tokens.accessToken, "Control Device");
    const sessionRes = await app.inject({
      method: "POST",
      url: "/v1/sessions",
      headers: { authorization: `Bearer ${admin.tokens.accessToken}` },
      payload: { deviceId: device.device.id }
    });
    const sessionId = sessionRes.json().session.id as string;

    const adminWs = new WebSocket(baseUrl);
    await new Promise((resolve) => adminWs.once("open", resolve));
    adminWs.send(
      JSON.stringify({
        v: 1,
        type: "auth",
        payload: { role: "admin", accessToken: admin.tokens.accessToken }
      })
    );
    expect((await onceMessage(adminWs)).type).toBe("auth.ok");

    const deviceWs = new WebSocket(baseUrl);
    await new Promise((resolve) => deviceWs.once("open", resolve));
    deviceWs.send(
      JSON.stringify({
        v: 1,
        type: "auth",
        payload: { role: "device", accessToken: device.tokens.accessToken }
      })
    );
    expect((await onceMessage(deviceWs)).type).toBe("auth.ok");

    const cmd = {
      commandId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
      sessionId,
      timestamp: Date.now(),
      operation: "HOME",
      capability: "REMOTE_INTERACTION"
    };
    adminWs.send(JSON.stringify({ v: 1, id: "c1", type: "interaction.command", payload: cmd }));
    const notReady = await waitForType(adminWs, "error");
    expect((notReady.payload as { code: string }).code).toBe("SESSION_NOT_READY");

    await app.inject({
      method: "POST",
      url: `/v1/sessions/${sessionId}/authenticate`,
      headers: { authorization: `Bearer ${device.tokens.accessToken}` }
    });
    await app.inject({
      method: "POST",
      url: `/v1/sessions/${sessionId}/activate`,
      headers: { authorization: `Bearer ${admin.tokens.accessToken}` }
    });

    adminWs.send(JSON.stringify({ v: 1, id: "c2", type: "interaction.command", payload: { ...cmd, commandId: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb" } }));
    const monitorOnly = await waitForType(adminWs, "error");
    expect((monitorOnly.payload as { code: string }).code).toBe("MODE_VIEW_ONLY");

    await app.inject({
      method: "POST",
      url: `/v1/sessions/${sessionId}/mode`,
      headers: { authorization: `Bearer ${admin.tokens.accessToken}` },
      payload: { mode: "MANAGED" }
    });

    const managedForward = waitForType(deviceWs, "interaction.command");
    adminWs.send(JSON.stringify({ v: 1, id: "c2b", type: "interaction.command", payload: { ...cmd, commandId: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbd" } }));
    const managedCmd = await managedForward;
    expect(managedCmd.type).toBe("interaction.command");

    await app.inject({
      method: "POST",
      url: "/v1/devices/me/heartbeat",
      headers: { authorization: `Bearer ${device.tokens.accessToken}` },
      payload: {
        capabilities: {
          screenCapture: true,
          fileBackup: true,
          remoteInput: true,
          accessibilityControl: true,
          states: { REMOTE_INTERACTION: "AVAILABLE", ACCESSIBILITY_CONTROL: "AVAILABLE" }
        }
      }
    });

    const forwarded = waitForType(deviceWs, "interaction.command");
    adminWs.send(
      JSON.stringify({
        v: 1,
        id: "c3",
        type: "interaction.command",
        payload: { ...cmd, commandId: "cccccccc-cccc-4ccc-8ccc-cccccccccccc" }
      })
    );
    const toDevice = await forwarded;
    expect(toDevice.type).toBe("interaction.command");

    adminWs.send(
      JSON.stringify({
        v: 1,
        id: "c4",
        type: "interaction.command",
        payload: { ...cmd, commandId: "cccccccc-cccc-4ccc-8ccc-cccccccccccc" }
      })
    );
    const replay = await waitForType(adminWs, "error");
    expect((replay.payload as { code: string }).code).toBe("REPLAYED_COMMAND");

    adminWs.send(JSON.stringify({ v: 1, type: "interaction.command", payload: { not: "a command" } }));
    const malformed = await waitForType(adminWs, "error");
    expect((malformed.payload as { code: string }).code).toBe("MALFORMED_COMMAND");

    await app.inject({
      method: "POST",
      url: `/v1/sessions/${sessionId}/terminate`,
      headers: { authorization: `Bearer ${admin.tokens.accessToken}` }
    });
    adminWs.send(
      JSON.stringify({
        v: 1,
        type: "interaction.command",
        payload: { ...cmd, commandId: "dddddddd-dddd-4ddd-8ddd-dddddddddddd" }
      })
    );
    const ended = await waitForType(adminWs, "error");
    expect((ended.payload as { code: string }).code).toBe("SESSION_ENDED");

    adminWs.close();
    deviceWs.close();
  });

  async function authedPair() {
    const admin = await registerAdmin(app);
    const device = await enrollDevice(app, admin.tokens.accessToken, "Signal Device");
    const sessionRes = await app.inject({
      method: "POST",
      url: "/v1/sessions",
      headers: { authorization: `Bearer ${admin.tokens.accessToken}` },
      payload: { deviceId: device.device.id }
    });
    const sessionId = sessionRes.json().session.id as string;
    const adminWs = new WebSocket(baseUrl);
    await new Promise((resolve) => adminWs.once("open", resolve));
    adminWs.send(
      JSON.stringify({
        v: 1,
        type: "auth",
        payload: { role: "admin", accessToken: admin.tokens.accessToken }
      })
    );
    expect((await onceMessage(adminWs)).type).toBe("auth.ok");
    const deviceWs = new WebSocket(baseUrl);
    await new Promise((resolve) => deviceWs.once("open", resolve));
    deviceWs.send(
      JSON.stringify({
        v: 1,
        type: "auth",
        payload: { role: "device", accessToken: device.tokens.accessToken }
      })
    );
    expect((await onceMessage(deviceWs)).type).toBe("auth.ok");
    return { admin, device, sessionId, adminWs, deviceWs };
  }

  it("forwards signaling.answer from device to admin", async () => {
    const { sessionId, adminWs, deviceWs } = await authedPair();
    const wait = onceMessage(adminWs);
    deviceWs.send(
      JSON.stringify({
        v: 1,
        type: "signaling.answer",
        payload: { sessionId, sdp: "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n" }
      })
    );
    const forwarded = await wait;
    expect(forwarded.type).toBe("signaling.answer");
    expect((forwarded.payload as { sessionId: string }).sessionId).toBe(sessionId);
    expect((forwarded.payload as { sdp: string }).sdp).toContain("m=video");
    adminWs.close();
    deviceWs.close();
  });

  it("forwards signaling.ice in both directions with the same session id", async () => {
    const { sessionId, adminWs, deviceWs } = await authedPair();
    const ice = {
      sessionId,
      candidate: { sdpMid: "0", sdpMLineIndex: 0, candidate: "candidate:1 1 UDP 1 127.0.0.1 9 typ host" }
    };
    const toDevice = onceMessage(deviceWs);
    adminWs.send(JSON.stringify({ v: 1, type: "signaling.ice", payload: ice }));
    const adminIce = await toDevice;
    expect(adminIce.type).toBe("signaling.ice");
    expect((adminIce.payload as { sessionId: string }).sessionId).toBe(sessionId);

    const toAdmin = onceMessage(adminWs);
    deviceWs.send(JSON.stringify({ v: 1, type: "signaling.ice", payload: ice }));
    const deviceIce = await toAdmin;
    expect(deviceIce.type).toBe("signaling.ice");
    expect((deviceIce.payload as { sessionId: string }).sessionId).toBe(sessionId);
    adminWs.close();
    deviceWs.close();
  });

  it("rejects signaling for a session the principal does not own", async () => {
    const first = await authedPair();
    const other = await registerAdmin(app);
    const otherDevice = await enrollDevice(app, other.tokens.accessToken, "Other Device");
    const otherSession = await app.inject({
      method: "POST",
      url: "/v1/sessions",
      headers: { authorization: `Bearer ${other.tokens.accessToken}` },
      payload: { deviceId: otherDevice.device.id }
    });
    const otherSessionId = otherSession.json().session.id as string;
    first.adminWs.send(
      JSON.stringify({
        v: 1,
        type: "signaling.offer",
        payload: { sessionId: otherSessionId, sdp: "v=0\r\n" }
      })
    );
    const err = await waitForType(first.adminWs, "error");
    expect((err.payload as { code: string }).code).toBe("FORBIDDEN");
    first.adminWs.close();
    first.deviceWs.close();
  });

  it("forwards duplicate offers and rejects late signaling after terminate", async () => {
    const { admin, sessionId, adminWs, deviceWs } = await authedPair();
    const first = onceMessage(deviceWs);
    adminWs.send(
      JSON.stringify({ v: 1, type: "signaling.offer", payload: { sessionId, sdp: "v=0\r\noffer-1\r\n" } })
    );
    expect((await first).type).toBe("signaling.offer");
    const second = onceMessage(deviceWs);
    adminWs.send(
      JSON.stringify({ v: 1, type: "signaling.offer", payload: { sessionId, sdp: "v=0\r\noffer-2\r\n" } })
    );
    expect((await second).type).toBe("signaling.offer");

    await app.inject({
      method: "POST",
      url: `/v1/sessions/${sessionId}/terminate`,
      headers: { authorization: `Bearer ${admin.tokens.accessToken}` }
    });
    adminWs.send(
      JSON.stringify({ v: 1, type: "signaling.ice", payload: { sessionId, candidate: { sdpMid: "0" } } })
    );
    const late = await waitForType(adminWs, "error");
    expect((late.payload as { code: string }).code).toBe("SESSION_ENDED");
    adminWs.close();
    deviceWs.close();
  });
});
