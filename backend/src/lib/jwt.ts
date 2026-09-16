import { SignJWT, jwtVerify, type JWTPayload } from "jose";
import type { AppConfig } from "../config.js";

export type AccessRole = "admin" | "device" | "pairing";

export type AccessClaims = JWTPayload & {
  sub: string;
  role: AccessRole;
  typ: "access" | "claim";
  ver?: number;
  nonce?: string;
};

function secretKey(secret: string): Uint8Array {
  return new TextEncoder().encode(secret);
}

export async function signAccessToken(
  config: AppConfig,
  input: { subject: string; role: "admin" | "device"; tokenVersion: number }
): Promise<string> {
  const ttl =
    input.role === "admin" ? config.ACCESS_TOKEN_TTL_SECONDS : config.DEVICE_ACCESS_TOKEN_TTL_SECONDS;
  return new SignJWT({ role: input.role, typ: "access", ver: input.tokenVersion })
    .setProtectedHeader({ alg: "HS256" })
    .setSubject(input.subject)
    .setIssuedAt()
    .setExpirationTime(`${ttl}s`)
    .sign(secretKey(config.JWT_ACCESS_SECRET));
}

export async function signClaimToken(
  config: AppConfig,
  input: { pairingSessionId: string; nonce: string; expiresAt: Date }
): Promise<string> {
  return new SignJWT({ role: "pairing", typ: "claim", nonce: input.nonce })
    .setProtectedHeader({ alg: "HS256" })
    .setSubject(input.pairingSessionId)
    .setIssuedAt()
    .setExpirationTime(input.expiresAt)
    .sign(secretKey(config.JWT_ACCESS_SECRET));
}

export async function verifyAccessToken(config: AppConfig, token: string): Promise<AccessClaims> {
  const { payload } = await jwtVerify(token, secretKey(config.JWT_ACCESS_SECRET));
  const role = payload.role;
  const sub = payload.sub;
  const typ = payload.typ;
  if (typeof sub !== "string" || (role !== "admin" && role !== "device" && role !== "pairing")) {
    throw new Error("invalid token");
  }
  if (typ !== "access" && typ !== "claim") {
    throw new Error("invalid token type");
  }
  return payload as AccessClaims;
}
