#!/usr/bin/env node
/**
 * Local E2E helper: register admin + issue pairing session.
 * Prints arl://pair URI for physical Target (LAN api=).
 */
import { randomUUID } from "node:crypto";

const API = process.env.ARL_API_BASE ?? "http://127.0.0.1:8080";
const TARGET_API = process.env.ARL_TARGET_API ?? "http://192.168.1.18:8080";
const EMAIL = process.env.E2E_TEST_EMAIL ?? process.env.ARL_ADMIN_EMAIL ?? `e2e-${Date.now()}@lab.test`;
const PASSWORD = process.env.E2E_TEST_PASSWORD ?? process.env.ARL_ADMIN_PASSWORD ?? "ChangeMe_LabOnly_12";
const DISPLAY = process.env.ARL_ADMIN_NAME ?? "E2E Operator";

async function post(path, body, token) {
  const headers = { "content-type": "application/json" };
  if (token) headers.authorization = `Bearer ${token}`;
  const res = await fetch(`${API}${path}`, {
    method: "POST",
    headers,
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json;
  try {
    json = JSON.parse(text);
  } catch {
    json = { raw: text };
  }
  if (!res.ok) {
    throw new Error(`${path} -> ${res.status}: ${text}`);
  }
  return json;
}

let token;
try {
  const reg = await post("/v1/auth/register", {
    email: EMAIL,
    password: PASSWORD,
    displayName: DISPLAY,
  });
  token = reg.tokens.accessToken;
} catch {
  const login = await post("/v1/auth/login", {
    email: EMAIL,
    password: PASSWORD,
  });
  token = login.tokens.accessToken;
}

const pairing = await post("/v1/pairing-sessions", {}, token);
const { id, pairingCode } = pairing.pairingSession;

const apiEnc = encodeURIComponent(TARGET_API);
const uri = `arl://pair?code=${pairingCode}&session=${id}&api=${apiEnc}`;

console.log(JSON.stringify({
  email: EMAIL,
  password: PASSWORD,
  pairingCode,
  pairingSessionId: id,
  targetApi: TARGET_API,
  pairUri: uri,
}, null, 2));
