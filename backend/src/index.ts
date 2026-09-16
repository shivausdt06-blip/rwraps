import { loadEnvFiles } from "./lib/load-env.js";
import { loadConfig } from "./config.js";
import { buildApp } from "./app.js";
import { bootstrapAdmin } from "./modules/auth.js";
import { prisma } from "./lib/prisma.js";
import { startEmbeddedLocalTurn } from "./lib/local-turn.js";

async function main(): Promise<void> {
  loadEnvFiles();
  const config = loadConfig();
  const app = await buildApp(config);
  await bootstrapAdmin(config);

  const localTurn: { current?: { close: () => void } } = {};
  let shuttingDown = false;
  const shutdown = (signal: string): void => {
    if (shuttingDown) {
      return;
    }
    shuttingDown = true;
    localTurn.current?.close();
    app.log.info({ signal }, "graceful shutdown");
    void app
      .close()
      .then(() => prisma.$disconnect())
      .then(() => process.exit(0))
      .catch((err: unknown) => {
        console.error(err);
        process.exit(1);
      });
  };
  process.on("SIGINT", () => shutdown("SIGINT"));
  process.on("SIGTERM", () => shutdown("SIGTERM"));

  await app.listen({ host: config.HOST, port: config.PORT });
  try {
    localTurn.current = startEmbeddedLocalTurn(config, app.log);
  } catch (err) {
    app.log.error(
      { err },
      "embedded local TURN failed to bind; emulator↔phone WebRTC will likely ICE-fail"
    );
  }
  app.log.info(
    {
      host: config.HOST,
      port: config.PORT,
      publicBaseUrl: config.publicBaseUrl || undefined,
      publicWsUrl: config.publicWsUrl || undefined
    },
    "API listening"
  );
}

main().catch(async (err) => {
  console.error(err);
  await prisma.$disconnect();
  process.exit(1);
});
