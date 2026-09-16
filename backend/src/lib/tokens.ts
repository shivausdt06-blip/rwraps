import type { AppConfig } from "../config.js";
import { prisma } from "./prisma.js";
import { generateOpaqueToken, hashOpaqueToken } from "./crypto.js";
import { signAccessToken } from "./jwt.js";

export async function issueAdminTokens(config: AppConfig, adminId: string, tokenVersion: number) {
  const accessToken = await signAccessToken(config, {
    subject: adminId,
    role: "admin",
    tokenVersion
  });
  const refreshToken = generateOpaqueToken();
  const tokenHash = hashOpaqueToken(refreshToken, config.JWT_REFRESH_SECRET);
  const expiresAt = new Date(Date.now() + config.REFRESH_TOKEN_TTL_SECONDS * 1000);
  await prisma.refreshToken.create({
    data: { adminId, tokenHash, expiresAt }
  });
  return {
    accessToken,
    refreshToken,
    expiresIn: config.ACCESS_TOKEN_TTL_SECONDS
  };
}

export async function issueDeviceTokens(config: AppConfig, deviceId: string, tokenVersion: number) {
  const accessToken = await signAccessToken(config, {
    subject: deviceId,
    role: "device",
    tokenVersion
  });
  const refreshToken = generateOpaqueToken();
  const tokenHash = hashOpaqueToken(refreshToken, config.JWT_REFRESH_SECRET);
  const expiresAt = new Date(Date.now() + config.DEVICE_REFRESH_TOKEN_TTL_SECONDS * 1000);
  await prisma.deviceRefreshToken.create({
    data: { deviceId, tokenHash, expiresAt }
  });
  return {
    accessToken,
    refreshToken,
    expiresIn: config.DEVICE_ACCESS_TOKEN_TTL_SECONDS
  };
}
