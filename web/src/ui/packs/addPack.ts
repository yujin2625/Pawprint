import { readPawpack } from '../../core/pack/pawpack';
import { pickPreviews } from '../../core/pack/previews';
import { buildPackFromJar } from '../../core/pack/vanillaJar';
import type { StoredPack } from '../../storage/db';

/** Turns a `.pawpack` (or a freshly built one) into the record kept in IndexedDB. */
export function toStoredPack(bytes: Uint8Array): StoredPack {
  const pack = readPawpack(bytes);
  return {
    id: pack.info.id,
    info: pack.info,
    addedAt: new Date().toISOString(),
    sizeBytes: bytes.length,
    isDefault: false,
    previews: pickPreviews(pack.blocks, pack.files).map((p) => ({ id: p.id, png: new Blob([p.png as BlobPart], { type: 'image/png' }) })),
    file: new Blob([bytes as BlobPart], { type: 'application/zip' }),
  };
}

export async function packFromFile(file: File): Promise<StoredPack> {
  return toStoredPack(new Uint8Array(await file.arrayBuffer()));
}

export async function packFromJar(file: File): Promise<StoredPack> {
  return packFromJarBytes(new Uint8Array(await file.arrayBuffer()));
}

/** `languages`: names in other languages (the desktop app reads them from the launcher's assets). */
export async function packFromJarBytes(jar: Uint8Array, languages?: Record<string, Record<string, string>>): Promise<StoredPack> {
  const built = await buildPackFromJar(jar, {
    id: crypto.randomUUID(),
    now: new Date().toISOString(),
    generator: 'pawprint-web',
    languages,
  });
  return toStoredPack(built.bytes);
}
