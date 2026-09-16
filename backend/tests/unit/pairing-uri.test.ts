import { describe, expect, it } from "vitest";
import { advertisedPairingApiBase, buildPairingQrPayload } from "../../src/lib/pairing-uri.js";
import { loadConfig } from "../../src/config.js";

describe("pairing URI", () => {
  it("includes code, session, and encoded api", () => {
    const uri = buildPairingQrPayload(
      "8DA2TPGY",
      "11111111-2222-3333-4444-555555555555",
      "http://10.59.57.35:8080"
    );
    expect(uri.startsWith("arl://pair?")).toBe(true);
    const query = new URLSearchParams(uri.slice("arl://pair?".length));
    expect(query.get("code")).toBe("8DA2TPGY");
    expect(query.get("session")).toBe("11111111-2222-3333-4444-555555555555");
    expect(query.get("api")).toBe("http://10.59.57.35:8080");
  });

  it("omits api when not advertised", () => {
    const uri = buildPairingQrPayload("8DA2TPGY", "sess-1");
    expect(uri).not.toContain("api=");
  });

  it("does not advertise LAN URLs in production without PUBLIC_BASE_URL", () => {
    expect(
      advertisedPairingApiBase(
        loadConfig({
          NODE_ENV: "production",
          CORS_ORIGIN: "https://admin.example.com",
          PUBLIC_BASE_URL: "https://api.example.com",
          DATABASE_URL:
            "postgresql://user:secret-secret-secret-secret-secret@db.example.com:5432/postgres?sslmode=require",
          JWT_ACCESS_SECRET: "prod-access-secret-32-characters-min",
          JWT_REFRESH_SECRET: "prod-refresh-secret-32-characters-min",
          PAIRING_PEPPER: "prod-pairing-pepper-32-characters-min",
          STUN_URLS: "stun:stun.example.com:3478"
        })
      )
    ).toBe("https://api.example.com");
  });
});
