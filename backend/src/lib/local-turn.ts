import type { FastifyBaseLogger } from "fastify";
import { createServer } from "turn-server";
import type { AppConfig } from "../config.js";
import { LOCAL_TURN_PORT, LOCAL_TURN_REALM, shouldEmbedLocalTurn } from "./ice-servers.js";
import { detectLanIpv4 } from "./pairing-uri.js";

export function startEmbeddedLocalTurn(
  config: AppConfig,
  log: FastifyBaseLogger
): { close: () => void } | undefined {
  if (!shouldEmbedLocalTurn(config)) {
    return undefined;
  }
  const lan = detectLanIpv4();
  const server = createServer({
    auth: {
      mechanism: "long-term",
      realm: LOCAL_TURN_REALM,
      credentials: { [config.localTurnUsername]: config.localTurnCredential }
    },
    relay: {
      ip: "0.0.0.0",
      portRange: [49152, 49200],
      ...(lan ? { externalIp: lan } : {})
    },
    allowLoopback: true
  });
  server.listen([
    { port: LOCAL_TURN_PORT, transport: "udp", address: "0.0.0.0" },
    { port: LOCAL_TURN_PORT, transport: "tcp", address: "0.0.0.0" }
  ]);
  log.info(
    { port: LOCAL_TURN_PORT, lan: lan || undefined, emulator: "10.0.2.2" },
    "embedded local TURN listening (development only; credentials are not logged)"
  );
  return {
    close: () => {
      server.stop();
    }
  };
}
