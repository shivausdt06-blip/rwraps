import { mkdtemp, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { LocalStorageProvider } from "../../src/storage/local.js";
import { sanitizeFilename, sanitizeStorageKey } from "../../src/storage/sanitize.js";

describe("storage sanitization", () => {
  it("strips path traversal from filenames", () => {
    expect(sanitizeFilename("../etc/passwd")).toBe("passwd");
    expect(sanitizeFilename("..\\notes.txt")).toBe("notes.txt");
    expect(sanitizeFilename("...")).toBe("_");
  });

  it("rejects empty and parent storage keys", () => {
    expect(sanitizeStorageKey("backup-id/file-id")).toEqual(["backup-id", "file-id"]);
    expect(() => sanitizeStorageKey("")).toThrow();
    expect(() => sanitizeStorageKey("../secret")).toThrow();
  });
});

describe("local storage provider", () => {
  it("writes chunks, resumes, and refuses traversal", async () => {
    const dir = await mkdtemp(path.join(os.tmpdir(), "arl-store-"));
    try {
      const storage = new LocalStorageProvider(dir);
      const key = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
      await storage.putChunk(key, 0, Buffer.from("hel"));
      await storage.putChunk(key, 3, Buffer.from("lo"));
      expect((await storage.readObject(key)).toString()).toBe("hello");
      expect((await storage.readRange(key, 1, 3)).toString()).toBe("ell");
      expect(() => storage.absolutePath("..\\windows\\system32")).toThrow(/traversal|Invalid/i);
      await storage.deleteObject(key);
      expect(await storage.exists(key)).toBe(false);
    } finally {
      await rm(dir, { recursive: true, force: true });
    }
  });
});
