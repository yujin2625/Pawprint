import { unzipSync, zipSync, type Zippable } from 'fflate';

/** Thrown for files that are not usable; `key` is an i18n key for the message shown to the user. */
export class FileFormatError extends Error {
  constructor(
    readonly key: string,
    readonly params: Record<string, string | number> = {},
  ) {
    super(key + (Object.keys(params).length ? ' ' + JSON.stringify(params) : ''));
  }
}

export interface ZipLimits {
  maxEntries: number;
  maxEntryBytes: number;
  maxTotalBytes: number;
}

/** Entry names that could escape a folder or confuse lookups are skipped. */
export function isSafeEntryName(name: string): boolean {
  return !(name.startsWith('/') || name.includes('\\') || name.split('/').includes('..'));
}

/**
 * Unzips the entries `want` accepts, enforcing size limits on both the declared and the actual sizes so a crafted
 * archive cannot exhaust memory.
 */
export function readZip(bytes: Uint8Array, limits: ZipLimits, want: (name: string) => boolean = () => true): Map<string, Uint8Array> {
  let entries = 0;
  let declared = 0;
  let files: Record<string, Uint8Array>;
  try {
    files = unzipSync(bytes, {
      filter: (file) => {
        if (file.name.endsWith('/') || !isSafeEntryName(file.name) || !want(file.name)) return false;
        if (++entries > limits.maxEntries) throw new FileFormatError('error.zip.tooManyEntries', { max: limits.maxEntries });
        if (file.originalSize > limits.maxEntryBytes) {
          throw new FileFormatError('error.zip.entryTooLarge', { name: file.name, max: limits.maxEntryBytes });
        }
        declared += file.originalSize;
        if (declared > limits.maxTotalBytes) throw new FileFormatError('error.zip.tooLarge', { max: limits.maxTotalBytes });
        return true;
      },
    });
  } catch (e) {
    if (e instanceof FileFormatError) throw e;
    throw new FileFormatError('error.zip.unreadable');
  }
  const out = new Map<string, Uint8Array>();
  let total = 0;
  for (const [name, data] of Object.entries(files)) {
    total += data.length;
    if (data.length > limits.maxEntryBytes || total > limits.maxTotalBytes) {
      throw new FileFormatError('error.zip.tooLarge', { max: limits.maxTotalBytes });
    }
    out.set(name, data);
  }
  return out;
}

/** Builds a zip. PNG files are stored as-is (already compressed); everything else is deflated. */
export function writeZip(files: Map<string, Uint8Array>): Uint8Array {
  const tree: Zippable = {};
  for (const [name, data] of files) {
    tree[name] = [data, { level: name.endsWith('.png') ? 0 : 6 }];
  }
  return zipSync(tree);
}

const decoder = new TextDecoder('utf-8', { fatal: false });
const encoder = new TextEncoder();

export function readJson<T = unknown>(files: Map<string, Uint8Array>, name: string, errorKey: string): T {
  const data = files.get(name);
  if (!data) throw new FileFormatError(errorKey, { name });
  try {
    return JSON.parse(decoder.decode(data)) as T;
  } catch {
    throw new FileFormatError('error.json.invalid', { name });
  }
}

export function jsonBytes(value: unknown): Uint8Array {
  return encoder.encode(JSON.stringify(value));
}

export function utf8(data: Uint8Array): string {
  return decoder.decode(data);
}
