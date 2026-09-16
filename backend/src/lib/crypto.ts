import { createHmac, randomBytes, randomInt, timingSafeEqual } from "node:crypto";
import argon2 from "argon2";

const PAIRING_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

export async function hashPassword(password: string): Promise<string> {
  return argon2.hash(password, { type: argon2.argon2id });
}

export async function verifyPassword(hash: string, password: string): Promise<boolean> {
  try {
    return await argon2.verify(hash, password);
  } catch {
    return false;
  }
}

export function generatePairingCode(length = 8): string {
  let out = "";
  for (let i = 0; i < length; i += 1) {
    out += PAIRING_ALPHABET[randomInt(PAIRING_ALPHABET.length)];
  }
  return out;
}

export function hashPairingCode(code: string, pepper: string): string {
  return createHmac("sha256", pepper).update(code.trim().toUpperCase()).digest("hex");
}

export function generateOpaqueToken(): string {
  return randomBytes(32).toString("base64url");
}

export function hashOpaqueToken(token: string, secret: string): string {
  return createHmac("sha256", secret).update(token).digest("hex");
}

export function safeEqual(a: string, b: string): boolean {
  const left = Buffer.from(a);
  const right = Buffer.from(b);
  if (left.length !== right.length) {
    return false;
  }
  return timingSafeEqual(left, right);
}

export function generateNonce(): string {
  return randomBytes(16).toString("hex");
}
