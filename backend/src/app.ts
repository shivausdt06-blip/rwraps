import Fastify, { type FastifyInstance } from "fastify";
import cors from "@fastify/cors";
import helmet from "@fastify/helmet";
import rateLimit from "@fastify/rate-limit";
import websocket from "@fastify/websocket";
import { ZodError } from "zod";
import type { AppConfig } from "./config.js";
import { AppError } from "./errors.js";
import { prisma } from "./lib/prisma.js";
import { authRoutes } from "./modules/auth.js";
import { pairingRoutes } from "./modules/pairing.js";
import { deviceRoutes } from "./modules/devices.js";
import { sessionRoutes } from "./modules/sessions.js";
import { backupRoutes } from "./modules/backups.js";
import { auditRoutes } from "./modules/audit.js";
import { websocketRoutes } from "./modules/websocket.js";
import { createStorage } from "./storage/index.js";
import { startSweepers } from "./jobs/sweep.js";

export async function buildApp(config: AppConfig): Promise<FastifyInstance> {
  const app = Fastify({
    logger:
      config.NODE_ENV === "test"
        ? false
        : {
            level: config.LOG_LEVEL,
            redact: {
              paths: [
                "req.headers.authorization",
                "req.headers.cookie",
                "*.password",
                "*.refreshToken",
                "*.accessToken",
                "*.pairingCode",
                "*.claimToken",
                "*.TURN_CREDENTIAL",
                "*.credential"
              ],
              censor: "[redacted]"
            },
            ...(config.NODE_ENV === "development"
              ? {
                  transport: {
                    target: "pino-pretty",
                    options: { translateTime: "SYS:standard", ignore: "pid,hostname" }
                  }
                }
              : {})
          },
    bodyLimit: 2 * 1024 * 1024,
    trustProxy: true
  });

  const storage = createStorage(config);

  app.addContentTypeParser(
    "application/octet-stream",
    { parseAs: "buffer" },
    (_request, body, done) => {
      done(null, body);
    }
  );

  await app.register(helmet, { global: true, contentSecurityPolicy: false });
  await app.register(cors, {
    origin: (origin, callback) => {
      if (!origin) {
        callback(null, true);
        return;
      }
      callback(null, config.corsOrigins.includes(origin));
    },
    credentials: true
  });
  await app.register(rateLimit, {
    max: 300,
    timeWindow: "1 minute"
  });
  await app.register(websocket);

  app.setErrorHandler((error, request, reply) => {
    if (error instanceof ZodError) {
      return reply.code(400).send({
        error: {
          code: "VALIDATION_ERROR",
          message: error.issues.map((issue) => issue.message).join("; ")
        }
      });
    }
    if (error instanceof AppError) {
      return reply.code(error.statusCode).send({
        error: { code: error.code, message: error.expose ? error.message : "Request failed." }
      });
    }
    request.log.error({ err: error }, "unhandled error");
    const status =
      error instanceof Error && "statusCode" in error && typeof error.statusCode === "number"
        ? error.statusCode
        : 500;
    if (status === 429) {
      return reply.code(429).send({
        error: { code: "RATE_LIMITED", message: "Too many requests." }
      });
    }
    const message = error instanceof Error ? error.message : "Internal server error.";
    return reply.code(status >= 400 && status < 600 ? status : 500).send({
      error: {
        code: "INTERNAL_ERROR",
        message: config.NODE_ENV === "production" ? "Internal server error." : message
      }
    });
  });

  app.get("/health", async () => ({
    status: "ok",
    service: "android-remote-lab-api",
    time: new Date().toISOString()
  }));

  app.get("/ready", async (_request, reply) => {
    try {
      await prisma.$queryRaw`SELECT 1`;
      return { status: "ready" };
    } catch {
      return reply.code(503).send({ status: "not_ready", reason: "database" });
    }
  });

  await authRoutes(app, config);
  await pairingRoutes(app, config);
  await deviceRoutes(app, config);
  await sessionRoutes(app, config);
  await backupRoutes(app, config, storage);
  await auditRoutes(app, config);
  await websocketRoutes(app, config);

  if (config.NODE_ENV !== "test") {
    const stop = startSweepers(config, app.log);
    app.addHook("onClose", async () => {
      stop();
    });
  }

  return app;
}
