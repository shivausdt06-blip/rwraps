export const PROTOCOL_VERSION = 1 as const;

export type AdminDto = {
  id: string;
  email: string;
  displayName: string;
  createdAt: string;
  updatedAt: string;
};

export type TokenPair = {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
};

export type CapabilityState = "AVAILABLE" | "NOT_GRANTED" | "RESTRICTED" | "UNAVAILABLE";

export type CapabilityKey =
  | "SCREEN_CAPTURE"
  | "ACCESSIBILITY_CONTROL"
  | "REMOTE_INTERACTION"
  | "FILE_BACKUP"
  | "BACKGROUND_SESSION";

export type DeviceCapabilities = {
  screenCapture: boolean;
  fileBackup: boolean;
  remoteInput: boolean;
  accessibilityControl?: boolean;
  backgroundSession?: boolean;
  states?: Partial<Record<CapabilityKey, CapabilityState>>;
};

export type InteractionOperation =
  | "TAP"
  | "LONG_PRESS"
  | "SWIPE"
  | "SCROLL"
  | "BACK"
  | "HOME"
  | "RECENTS"
  | "NODE_CLICK"
  | "NODE_FOCUS"
  | "TEXT_ENTRY";

export type InteractionCommand = {
  commandId: string;
  sessionId: string;
  timestamp: number;
  operation: InteractionOperation;
  capability: "REMOTE_INTERACTION";
  params?: {
    nx?: number;
    ny?: number;
    nx2?: number;
    ny2?: number;
    durationMs?: number;
    direction?: "UP" | "DOWN" | "LEFT" | "RIGHT";
    viewId?: string;
    text?: string;
  };
};

export type DeviceDto = {
  id: string;
  name: string;
  enrollmentState: "PENDING" | "ACTIVE" | "REVOKED";
  authorizationState: "NONE" | "GRANTED" | "REVOKED";
  platform: string;
  androidVersion: string | null;
  manufacturer: string | null;
  model: string | null;
  sdkInt: number | null;
  lastSeenAt: string | null;
  connectionState: "OFFLINE" | "ONLINE";
  capabilities: DeviceCapabilities;
  createdAt: string;
  updatedAt: string;
};

export type WsEnvelope = {
  v: typeof PROTOCOL_VERSION;
  id?: string;
  type: string;
  payload?: unknown;
};
