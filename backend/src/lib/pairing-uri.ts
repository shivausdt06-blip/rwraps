import { networkInterfaces } from "node:os";
import type { AppConfig } from "../config.js";

function isPrivateIpv4(ip: string): boolean {
  const parts = ip.split(".").map((item) => Number(item));
  if (parts.length !== 4 || parts.some((part) => Number.isNaN(part) || part < 0 || part > 255)) {
    return false;
  }
  const [a, b] = parts;
  if (a === 10) {
    return true;
  }
  if (a === 192 && b === 168) {
    return true;
  }
  if (a === 172 && b !== undefined && b >= 16 && b <= 31) {
    return true;
  }
  return false;
}

function skipInterface(name: string): boolean {
  return /virtual|vethernet|docker|wsl|hyper-v|vmware|loopback|bluetooth|vpn|tun|tap|pseudo/i.test(
    name
  );
}

function interfaceRank(name: string, ip: string): number {
  if (/wi-?fi|wlan|wireless|ethernet|wi-fi|local area connection/i.test(name) && isPrivateIpv4(ip)) {
    return 0;
  }
  if (ip.startsWith("10.")) {
    return 1;
  }
  if (ip.startsWith("192.168.")) {
    return 2;
  }
  return 3;
}

export function detectLanIpv4(): string | undefined {
  const nets = networkInterfaces();
  const candidates: Array<{ name: string; ip: string }> = [];
  for (const [name, addrs] of Object.entries(nets)) {
    if (skipInterface(name)) {
      continue;
    }
    for (const addr of addrs ?? []) {
      const family = addr.family;
      const isV4 = family === "IPv4" || String(family) === "4";
      if (!isV4 || addr.internal) {
        continue;
      }
      if (addr.address.startsWith("172.17.")) {
        continue;
      }
      if (isPrivateIpv4(addr.address)) {
        candidates.push({ name, ip: addr.address });
      }
    }
  }
  candidates.sort((left, right) => interfaceRank(left.name, left.ip) - interfaceRank(right.name, right.ip));
  return candidates[0]?.ip;
}

/** URL physical Target devices should use. Production uses PUBLIC_BASE_URL (https). */
export function advertisedPairingApiBase(config: AppConfig): string | undefined {
  if (config.publicBaseUrl) {
    return config.publicBaseUrl;
  }
  if (config.NODE_ENV === "production") {
    return undefined;
  }
  const ip = detectLanIpv4();
  if (!ip) {
    return undefined;
  }
  return `http://${ip}:${config.PORT}`;
}

export function buildPairingQrPayload(code: string, sessionId: string, apiBase?: string): string {
  const params = new URLSearchParams();
  params.set("code", code);
  params.set("session", sessionId);
  if (apiBase) {
    params.set("api", apiBase);
  }
  return `arl://pair?${params.toString()}`;
}
