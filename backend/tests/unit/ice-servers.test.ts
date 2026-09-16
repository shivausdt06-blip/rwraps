import { describe, expect, it } from "vitest";
import { loadConfig } from "../../src/config.js";
import { advertisedTurnUrls, buildIceServersPayload, shouldEmbedLocalTurn } from "../../src/lib/ice-servers.js";

const labEnv = (): NodeJS.ProcessEnv => ({
  NODE_ENV: "development",
  DATABASE_URL: "postgresql://lab:lab_dev_only@localhost:5432/android_remote_lab?schema=public",
  JWT_ACCESS_SECRET: "test-access-secret-32-characters-min",
  JWT_REFRESH_SECRET: "test-refresh-secret-32-characters-min",
  PAIRING_PEPPER: "test-pairing-pepper-32-characters-min",
  STUN_URLS: "stun:stun.l.google.com:19302",
  TURN_URLS: ""
});

describe("ice servers", () => {
  it("advertises LAN and emulator TURN URLs without secrets in the URL", () => {
    const urls = advertisedTurnUrls("10.59.57.35");
    expect(urls.some((u) => u.startsWith("turn:10.59.57.35:3478"))).toBe(true);
    expect(urls.some((u) => u.startsWith("turn:10.0.2.2:3478"))).toBe(true);
    expect(urls.every((u) => !u.includes("arl-local"))).toBe(true);
  });

  it("embeds local TURN in development with cleanly separated UDP and TCP entries", () => {
    const config = loadConfig(labEnv());
    expect(shouldEmbedLocalTurn(config)).toBe(true);
    const payload = buildIceServersPayload(config, "10.59.57.35");
    expect(payload[0]?.urls[0]).toMatch(/^stun:/);
    const turnEntries = payload.filter((entry) => entry.urls.some((u) => u.startsWith("turn:")));
    expect(turnEntries.length).toBeGreaterThanOrEqual(2);
    expect(turnEntries.every((e) => e.username === "arl-local" && Boolean(e.credential))).toBe(true);
    // Each TURN entry should have a single URL (separating UDP and TCP)
    expect(turnEntries.every((e) => e.urls.length === 1)).toBe(true);
    expect(payload.some((e) => e.urls.includes("turn:10.59.57.35:3478?transport=udp"))).toBe(true);
    expect(payload.some((e) => e.urls.includes("turn:10.59.57.35:3478?transport=tcp"))).toBe(true);
    expect(payload.some((e) => e.urls.includes("turn:10.0.2.2:3478?transport=udp"))).toBe(true);
    expect(payload.some((e) => e.urls.includes("turn:10.0.2.2:3478?transport=tcp"))).toBe(true);
  });

  it("provides physical device with only LAN TURN (no 10.0.2.2)", () => {
    const config = loadConfig(labEnv());
    const payload = buildIceServersPayload(config, "10.59.57.35", "device");
    expect(payload.some((e) => e.urls.includes("turn:10.59.57.35:3478?transport=udp"))).toBe(true);
    expect(payload.some((e) => e.urls.includes("turn:10.59.57.35:3478?transport=tcp"))).toBe(true);
    expect(payload.some((e) => e.urls.some((u) => u.includes("10.0.2.2")))).toBe(false);
  });

  it("provides emulator with 10.0.2.2 TURN", () => {
    const config = loadConfig(labEnv());
    const payload = buildIceServersPayload(config, "10.59.57.35", "emulator");
    expect(payload.some((e) => e.urls.includes("turn:10.0.2.2:3478?transport=udp"))).toBe(true);
    expect(payload.some((e) => e.urls.includes("turn:10.0.2.2:3478?transport=tcp"))).toBe(true);
    expect(payload.some((e) => e.urls.some((u) => u.includes("10.59.57.35")))).toBe(false);
  });

  it("uses operator TURN_URLS instead of the embedded lab relay", () => {
    const config = loadConfig({
      ...labEnv(),
      TURN_URLS: "turn:turn.example.com:3478",
      TURN_USERNAME: "op",
      TURN_CREDENTIAL: "secret-secret"
    });
    expect(shouldEmbedLocalTurn(config)).toBe(false);
    const payload = buildIceServersPayload(config, "10.59.57.35");
    expect(payload.some((e) => e.urls.includes("turn:turn.example.com:3478"))).toBe(true);
    expect(payload.some((e) => e.urls.some((u) => u.includes("10.0.2.2")))).toBe(false);
  });

  it("does not embed TURN in test or production without TURN_URLS", () => {
    const testConfig = loadConfig({ ...labEnv(), NODE_ENV: "test" });
    expect(shouldEmbedLocalTurn(testConfig)).toBe(false);
    expect(buildIceServersPayload(testConfig).every((e) => e.urls.every((u) => u.startsWith("stun:")))).toBe(
      true
    );
  });
});
