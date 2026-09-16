import { z } from "zod";

const csv = (value: string | undefined): string[] =>
  (value ?? "")
    .split(",")
    .map((item) => item.trim())
    .filter((item) => item.length > 0);

const PLACEHOLDER_SECRET = /replace-with|change.?me_lab|example\.invalid|your-secret|lab_dev_only/i;

const envSchema = z.object({
  NODE_ENV: z.enum(["development", "test", "production"]).default("development"),
  HOST: z.string().default("0.0.0.0"),
  PORT: z.coerce.number().int().positive().default(8080),
  LOG_LEVEL: z.string().default("info"),
  CORS_ORIGIN: z.string().default("http://localhost:8080"),
  PUBLIC_BASE_URL: z.string().optional().default(""),
  PUBLIC_WS_URL: z.string().optional().default(""),
  DATABASE_URL: z.string().min(1),
  DIRECT_URL: z.string().optional().default(""),
  JWT_ACCESS_SECRET: z.string().min(32),
  JWT_REFRESH_SECRET: z.string().min(32),
  PAIRING_PEPPER: z.string().min(32),
  ACCESS_TOKEN_TTL_SECONDS: z.coerce.number().int().positive().default(900),
  REFRESH_TOKEN_TTL_SECONDS: z.coerce.number().int().positive().default(604800),
  DEVICE_ACCESS_TOKEN_TTL_SECONDS: z.coerce.number().int().positive().default(900),
  DEVICE_REFRESH_TOKEN_TTL_SECONDS: z.coerce.number().int().positive().default(2592000),
  PAIRING_TTL_SECONDS: z.coerce.number().int().positive().default(600),
  /** Idle timeout for non-terminal remote sessions; extended on activity (default 30 days). */
  SESSION_TIMEOUT_SECONDS: z.coerce.number().int().positive().default(2592000),
  PRESENCE_OFFLINE_AFTER_SECONDS: z.coerce.number().int().positive().default(45),
  MAX_DEVICES_PER_ADMIN: z.coerce.number().int().positive().default(50),
  ALLOW_ADMIN_REGISTRATION: z.string().optional(),
  ADMIN_BOOTSTRAP_EMAIL: z.string().email().optional(),
  ADMIN_BOOTSTRAP_PASSWORD: z.string().min(12).optional(),
  STORAGE_PROVIDER: z.enum(["local", "cloud"]).default("local"),
  STORAGE_DIR: z.string().default("./data/backups"),
  BACKUP_PAYLOAD_DESTINATION: z.enum(["LOCAL_ADMIN", "CLOUD"]).default("LOCAL_ADMIN"),
  STUN_URLS: z.string().default("stun:stun.l.google.com:19302"),
  TURN_URLS: z.string().optional().default(""),
  TURN_USERNAME: z.string().optional().default(""),
  TURN_CREDENTIAL: z.string().optional().default("")
});

export type AppConfig = z.infer<typeof envSchema> & {
  stunUrls: string[];
  turnUrls: string[];
  corsOrigins: string[];
  allowAdminRegistration: boolean;
  publicBaseUrl: string;
  publicWsUrl: string;
  localTurnUsername: string;
  localTurnCredential: string;
};

function assertProduction(data: z.infer<typeof envSchema>, corsOrigins: string[]): void {
  if (data.NODE_ENV !== "production") {
    return;
  }
  const failures: string[] = [];
  if (corsOrigins.length === 0) {
    failures.push("CORS_ORIGIN is required in production");
  }
  if (corsOrigins.some((origin) => origin === "*")) {
    failures.push("CORS_ORIGIN must not be a wildcard in production");
  }
  if (corsOrigins.some((origin) => /localhost|127\.0\.0\.1/i.test(origin))) {
    failures.push("CORS_ORIGIN must not use localhost in production");
  }
  if (corsOrigins.some((origin) => origin.startsWith("http://"))) {
    failures.push("CORS_ORIGIN must use https in production");
  }
  if (!data.PUBLIC_BASE_URL || !/^https:\/\//i.test(data.PUBLIC_BASE_URL)) {
    failures.push("PUBLIC_BASE_URL must be an https URL in production");
  }
  if (data.PUBLIC_WS_URL && !/^wss:\/\//i.test(data.PUBLIC_WS_URL)) {
    failures.push("PUBLIC_WS_URL must be a wss URL when set in production");
  }
  if (/localhost|127\.0\.0\.1/i.test(data.DATABASE_URL) || PLACEHOLDER_SECRET.test(data.DATABASE_URL)) {
    failures.push("DATABASE_URL must be the production database (not localhost or lab_dev_only)");
  }
  for (const [name, value] of [
    ["JWT_ACCESS_SECRET", data.JWT_ACCESS_SECRET],
    ["JWT_REFRESH_SECRET", data.JWT_REFRESH_SECRET],
    ["PAIRING_PEPPER", data.PAIRING_PEPPER]
  ] as const) {
    if (PLACEHOLDER_SECRET.test(value)) {
      failures.push(`${name} must not be a placeholder value`);
    }
  }
  if (data.TURN_URLS && (!data.TURN_USERNAME || !data.TURN_CREDENTIAL)) {
    failures.push("TURN_USERNAME and TURN_CREDENTIAL are required when TURN_URLS is set");
  }
  if (failures.length > 0) {
    throw new Error(`Invalid production configuration: ${failures.join("; ")}`);
  }
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): AppConfig {
  const parsed = envSchema.safeParse(env);
  if (!parsed.success) {
    const details = parsed.error.issues
      .map((issue) => `${issue.path.join(".")}: ${issue.message}`)
      .join("; ");
    throw new Error(`Invalid environment configuration: ${details}`);
  }
  const data = parsed.data;
  const corsOrigins = csv(data.CORS_ORIGIN);
  assertProduction(data, corsOrigins);
  const allowAdminRegistration =
    data.ALLOW_ADMIN_REGISTRATION === undefined
      ? data.NODE_ENV !== "production"
      : data.ALLOW_ADMIN_REGISTRATION === "true" || data.ALLOW_ADMIN_REGISTRATION === "1";
  return {
    ...data,
    stunUrls: csv(data.STUN_URLS),
    turnUrls: csv(data.TURN_URLS),
    corsOrigins,
    allowAdminRegistration,
    publicBaseUrl: (data.PUBLIC_BASE_URL || "").replace(/\/$/, ""),
    publicWsUrl: (
      data.PUBLIC_WS_URL ||
      (data.PUBLIC_BASE_URL || "").replace(/^http/i, "ws")
    ).replace(/\/$/, ""),
    localTurnUsername: data.TURN_USERNAME || "arl-local",
    localTurnCredential: data.TURN_CREDENTIAL || "arl-local-turn-lab-only"
  };
}
