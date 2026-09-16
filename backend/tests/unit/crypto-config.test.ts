import { describe, expect, it } from "vitest";
import { generatePairingCode, hashPairingCode, hashOpaqueToken, safeEqual } from "../../src/lib/crypto.js";
import { loadConfig } from "../../src/config.js";

describe("crypto", () => {
  it("generates pairing codes from the safe alphabet", () => {
    const code = generatePairingCode();
    expect(code).toHaveLength(8);
    expect(code).toMatch(/^[A-HJKLMNP-Z2-9]+$/);
  });

  it("hashes pairing codes case-insensitively", () => {
    const pepper = "pepper-pepper-pepper-pepper-pepper";
    expect(hashPairingCode("abcDEF12", pepper)).toBe(hashPairingCode("ABCDEF12", pepper));
  });

  it("hashes opaque tokens deterministically", () => {
    const secret = "secret-secret-secret-secret-secret";
    expect(hashOpaqueToken("token", secret)).toBe(hashOpaqueToken("token", secret));
    expect(safeEqual("abc", "abc")).toBe(true);
    expect(safeEqual("abc", "abd")).toBe(false);
  });
});

const productionEnv = (): NodeJS.ProcessEnv => ({
  NODE_ENV: "production",
  HOST: "0.0.0.0",
  PORT: "8080",
  LOG_LEVEL: "info",
  CORS_ORIGIN: "https://admin.example.com",
  PUBLIC_BASE_URL: "https://api.example.com",
  DATABASE_URL: "postgresql://user:secret-secret-secret-secret-secret@db.example.com:5432/postgres?sslmode=require",
  JWT_ACCESS_SECRET: "prod-access-secret-32-characters-min",
  JWT_REFRESH_SECRET: "prod-refresh-secret-32-characters-min",
  PAIRING_PEPPER: "prod-pairing-pepper-32-characters-min",
  STUN_URLS: "stun:stun.example.com:3478"
});

describe("config", () => {
  it("rejects short secrets", () => {
    expect(() =>
      loadConfig({
        ...process.env,
        JWT_ACCESS_SECRET: "short"
      })
    ).toThrow(/Invalid environment configuration/);
  });

  it("loads production configuration when constraints are met", () => {
    const config = loadConfig(productionEnv());
    expect(config.allowAdminRegistration).toBe(false);
    expect(config.corsOrigins).toEqual(["https://admin.example.com"]);
    expect(config.publicWsUrl).toBe("wss://api.example.com");
    expect(config.BACKUP_PAYLOAD_DESTINATION).toBe("LOCAL_ADMIN");
  });

  it("rejects wildcard CORS in production", () => {
    expect(() => loadConfig({ ...productionEnv(), CORS_ORIGIN: "*" })).toThrow(
      /Invalid production configuration/
    );
  });

  it("rejects localhost CORS and database URLs in production", () => {
    expect(() =>
      loadConfig({ ...productionEnv(), CORS_ORIGIN: "http://localhost:8080" })
    ).toThrow(/Invalid production configuration/);
    expect(() =>
      loadConfig({
        ...productionEnv(),
        DATABASE_URL: "postgresql://lab:lab_dev_only@localhost:5432/android_remote_lab"
      })
    ).toThrow(/Invalid production configuration/);
  });

  it("rejects placeholder JWT secrets in production", () => {
    expect(() =>
      loadConfig({
        ...productionEnv(),
        JWT_ACCESS_SECRET: "replace-with-32-plus-char-access-secret"
      })
    ).toThrow(/Invalid production configuration/);
  });

  it("requires TURN credentials when TURN URLs are set", () => {
    expect(() => loadConfig({ ...productionEnv(), TURN_URLS: "turn:turn.example.com:3478" })).toThrow(
      /TURN_USERNAME/
    );
  });
});
