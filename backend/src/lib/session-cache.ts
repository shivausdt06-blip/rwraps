import type { RemoteSession, Device } from "@prisma/client";

export interface CachedSession {
  session: RemoteSession;
  device: Device | null;
  cachedAt: number;
}

const sessionAuthCache = new Map<string, CachedSession>();
const lastIdleExtension = new Map<string, number>();

export function getCachedSession(sessionId: string, maxAgeMs = 15_000): CachedSession | undefined {
  const cached = sessionAuthCache.get(sessionId);
  if (!cached) return undefined;
  if (Date.now() - cached.cachedAt > maxAgeMs) {
    sessionAuthCache.delete(sessionId);
    return undefined;
  }
  return cached;
}

export function setCachedSession(sessionId: string, session: RemoteSession, device: Device | null): void {
  sessionAuthCache.set(sessionId, { session, device, cachedAt: Date.now() });
}

export function invalidateSessionAuthCache(sessionId: string): void {
  sessionAuthCache.delete(sessionId);
  lastIdleExtension.delete(sessionId);
}

export function shouldExtendIdle(sessionId: string, minIntervalMs = 10_000): boolean {
  const now = Date.now();
  const last = lastIdleExtension.get(sessionId) ?? 0;
  if (now - last > minIntervalMs) {
    lastIdleExtension.set(sessionId, now);
    return true;
  }
  return false;
}
