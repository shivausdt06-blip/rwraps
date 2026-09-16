import type { AppConfig } from "../config.js";
import { LocalStorageProvider, type StorageProvider } from "./local.js";

/** Optional future provider. The lab demo uses local staging, not S3. */
export class CloudStorageProvider implements StorageProvider {
  constructor(private readonly inner: StorageProvider) {}

  putChunk(storageKey: string, offset: number, data: Buffer) {
    return this.inner.putChunk(storageKey, offset, data);
  }
  readObject(storageKey: string) {
    return this.inner.readObject(storageKey);
  }
  readRange(storageKey: string, offset: number, length: number) {
    return this.inner.readRange(storageKey, offset, length);
  }
  deleteObject(storageKey: string) {
    return this.inner.deleteObject(storageKey);
  }
  exists(storageKey: string) {
    return this.inner.exists(storageKey);
  }
  absolutePath(storageKey: string) {
    return this.inner.absolutePath(storageKey);
  }
}

export function createStorage(config: AppConfig): StorageProvider {
  const local = new LocalStorageProvider(config.STORAGE_DIR);
  if (config.STORAGE_PROVIDER === "cloud") {
    return new CloudStorageProvider(local);
  }
  return local;
}

export type { StorageProvider } from "./local.js";
