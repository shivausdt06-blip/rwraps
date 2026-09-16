import type { AppConfig } from "../config.js";
import { detectLanIpv4 } from "./pairing-uri.js";

export type IceServerJson = {
  urls: string[];
  username?: string;
  credential?: string;
};

export type IceServerClientType = "device" | "emulator" | "all";

export const LOCAL_TURN_PORT = 3478;
export const LOCAL_TURN_REALM = "arl.local";

export function advertisedTurnUrls(
  lanIpv4: string | undefined,
  port = LOCAL_TURN_PORT,
  clientType: IceServerClientType = "all"
): string[] {
  const urls: string[] = [];

  if (clientType === "device") {
    if (lanIpv4 && lanIpv4 !== "10.0.2.2") {
      urls.push(`turn:${lanIpv4}:${port}?transport=udp`, `turn:${lanIpv4}:${port}?transport=tcp`);
    }
    return urls;
  }

  if (clientType === "emulator") {
    urls.push(`turn:10.0.2.2:${port}?transport=udp`, `turn:10.0.2.2:${port}?transport=tcp`);
    return urls;
  }

  // clientType === "all"
  if (lanIpv4 && lanIpv4 !== "10.0.2.2") {
    urls.push(`turn:${lanIpv4}:${port}?transport=udp`, `turn:${lanIpv4}:${port}?transport=tcp`);
  }
  urls.push(`turn:10.0.2.2:${port}?transport=udp`, `turn:10.0.2.2:${port}?transport=tcp`);
  return urls;
}

export function shouldEmbedLocalTurn(config: AppConfig): boolean {
  return config.NODE_ENV === "development" && config.turnUrls.length === 0;
}

export function buildIceServersPayload(
  config: AppConfig,
  lanIpv4 = detectLanIpv4(),
  clientType: IceServerClientType = "all"
): IceServerJson[] {
  const iceServers: IceServerJson[] = [];
  if (config.stunUrls.length > 0) {
    iceServers.push({ urls: config.stunUrls });
  }
  if (config.turnUrls.length > 0 && config.TURN_USERNAME && config.TURN_CREDENTIAL) {
    for (const url of config.turnUrls) {
      iceServers.push({
        urls: [url],
        username: config.TURN_USERNAME,
        credential: config.TURN_CREDENTIAL
      });
    }
    return iceServers;
  }
  if (shouldEmbedLocalTurn(config)) {
    const urls = advertisedTurnUrls(lanIpv4, LOCAL_TURN_PORT, clientType);
    for (const url of urls) {
      iceServers.push({
        urls: [url],
        username: config.localTurnUsername,
        credential: config.localTurnCredential
      });
    }
  }
  return iceServers;
}
