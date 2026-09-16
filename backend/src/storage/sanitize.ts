export function sanitizeFilename(input: string): string {
  const base = input.replaceAll("\\", "/").split("/").pop() ?? "file";
  const cleaned = base
    .replace(/[^\w.\-+ ()[\]]+/g, "_")
    .replace(/^\.+/g, "_")
    .slice(0, 180)
    .trim();
  return cleaned.length > 0 ? cleaned : "file";
}

export function sanitizeStorageKey(storageKey: string): string[] {
  if (storageKey.includes("\0") || storageKey.includes("..")) {
    throw new Error("Invalid storage key.");
  }
  const parts = storageKey.split(/[/\\]+/).filter((part) => part.length > 0 && part !== ".");
  if (parts.length === 0) {
    throw new Error("Invalid storage key.");
  }
  return parts;
}
