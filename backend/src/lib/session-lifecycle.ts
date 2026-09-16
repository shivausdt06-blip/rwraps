import type { AppConfig } from "../config.js";
import { prisma } from "./prisma.js";
import type { RemoteSessionStatus } from "@prisma/client";

const ACTIVE_SESSION_STATUSES: RemoteSessionStatus[] = [
  "CREATED",
  "AUTHENTICATED",
  "ACTIVE",
  "RECONNECTING"
];

/** Next idle deadline from `fromMs` using configured inactivity window. */
export function nextSessionTimeoutAt(config: AppConfig, fromMs = Date.now()): Date {
  return new Date(fromMs + config.SESSION_TIMEOUT_SECONDS * 1000);
}

/** Slide the session idle deadline forward while the session remains non-terminal. */
export async function extendSessionIdleDeadline(
  config: AppConfig,
  sessionId: string,
  fromMs = Date.now()
): Promise<void> {
  const timeoutAt = nextSessionTimeoutAt(config, fromMs);
  await prisma.remoteSession.updateMany({
    where: {
      id: sessionId,
      status: { in: ACTIVE_SESSION_STATUSES }
    },
    data: { timeoutAt }
  });
}
