# WebSocket protocol

Endpoint: `GET /v1/ws` (HTTP upgrade).

All messages are JSON objects:

```json
{
  "v": 1,
  "id": "correlation-uuid",
  "type": "auth",
  "payload": {}
}
```

- `v` must be `1`.
- `id` is echoed on replies when provided.
- Unknown types yield `error` with code `UNKNOWN_TYPE`.
- Unauthenticated sockets may only send `auth`. They are closed after 10 seconds without a successful auth.

## Client → server

| type | payload | notes |
| --- | --- | --- |
| `auth` | `{ "role": "admin" \| "device", "accessToken": string }` | Required first |
| `heartbeat` | `{}` | Updates presence for devices |
| `signaling.offer` | `{ "sessionId": string, "sdp": string }` | WebRTC offer |
| `signaling.answer` | `{ "sessionId": string, "sdp": string }` | WebRTC answer |
| `signaling.ice` | `{ "sessionId": string, "candidate": object }` | ICE candidate |
| `session.telemetry` | `{ "sessionId": string, "rttMs": number, "packetLoss": number, "bitrateKbps": number }` | Quality |
| `session.reconnect` | `{ "sessionId": string }` | Reconnect intent |
| `interaction.command` | See [INTERACTION.md](INTERACTION.md) | Admin only; server-authorized remote interaction |
| `interaction.result` | `{ "commandId", "sessionId", "ok", "code?", "message?", "latencyMs?" }` | Device only |

## Server → client

| type | payload |
| --- | --- |
| `auth.ok` | `{ "principal": { "role": "...", "id": "..." } }` |
| `heartbeat.ack` | `{ "serverTime": "iso" }` |
| `device.presence` | `{ "deviceId": string, "connectionState": "ONLINE" \| "OFFLINE", "lastSeenAt": "iso", "device"?: Device }` |
| `device.capabilities` | `{ "deviceId": string, "capabilities": Capabilities }` |
| `session.updated` | `{ "session": RemoteSession }` |
| `signaling.offer` / `answer` / `ice` | Same shape as inbound; forwarded to the peer |
| `interaction.command` | Forwarded to the session device after authorization |
| `interaction.result` | Forwarded to the session admin after authorization |
| `backup.updated` | `{ "backupId": string, "status": string }` |

Signaling is forwarded only if:

- The sender is authenticated.
- The `RemoteSession` exists and is not in a terminal state.
- The sender is the session admin or the session device.
- The recipient currently has a WebSocket connection.

`interaction.command` is forwarded only if the sender is the session admin, the session is `AUTHENTICATED`/`ACTIVE`/`RECONNECTING`, the device reports `REMOTE_INTERACTION` available, the payload matches the command schema, the timestamp is within 30 seconds, and the `commandId` has not been seen. Oversized envelopes still fail the 64 KiB limit.

The backend does not interpret SDP beyond size limits (max 64 KiB per message).
