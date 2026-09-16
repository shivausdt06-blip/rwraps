import { mkdir, rm, open, readFile, type FileHandle } from "node:fs/promises";
import path from "node:path";
import { sanitizeStorageKey } from "./sanitize.js";

export interface StorageProvider {
  putChunk(storageKey: string, offset: number, data: Buffer): Promise<number>;
  readObject(storageKey: string): Promise<Buffer>;
  readRange(storageKey: string, offset: number, length: number): Promise<Buffer>;
  deleteObject(storageKey: string): Promise<void>;
  exists(storageKey: string): Promise<boolean>;
  absolutePath(storageKey: string): string;
}

export class LocalStorageProvider implements StorageProvider {
  constructor(private readonly rootDir: string) {}

  absolutePath(storageKey: string): string {
    const root = path.resolve(this.rootDir);
    const resolved = path.resolve(root, ...sanitizeStorageKey(storageKey));
    const relative = path.relative(root, resolved);
    if (relative.startsWith("..") || path.isAbsolute(relative)) {
      throw new Error("Path traversal is not allowed.");
    }
    return resolved;
  }

  async putChunk(storageKey: string, offset: number, data: Buffer): Promise<number> {
    const filePath = this.absolutePath(storageKey);
    await mkdir(path.dirname(filePath), { recursive: true });
    let handle: FileHandle;
    try {
      handle = await open(filePath, "r+");
    } catch {
      handle = await open(filePath, "w+");
    }
    try {
      await handle.write(data, 0, data.length, offset);
      const stat = await handle.stat();
      return stat.size;
    } finally {
      await handle.close();
    }
  }

  async readObject(storageKey: string): Promise<Buffer> {
    return readFile(this.absolutePath(storageKey));
  }

  async readRange(storageKey: string, offset: number, length: number): Promise<Buffer> {
    const handle = await open(this.absolutePath(storageKey), "r");
    try {
      const buffer = Buffer.alloc(length);
      const { bytesRead } = await handle.read(buffer, 0, length, offset);
      return buffer.subarray(0, bytesRead);
    } finally {
      await handle.close();
    }
  }

  async deleteObject(storageKey: string): Promise<void> {
    await rm(this.absolutePath(storageKey), { force: true });
  }

  async exists(storageKey: string): Promise<boolean> {
    try {
      await open(this.absolutePath(storageKey), "r").then((handle) => handle.close());
      return true;
    } catch {
      return false;
    }
  }
}
