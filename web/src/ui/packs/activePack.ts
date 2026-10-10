import { readPawpack, type LoadedPack } from '../../core/pack/pawpack';
import { getPack, listPacks, type StoredPack } from '../../storage/db';

let cache: { id: string; pack: LoadedPack } | null = null;

/** A stored block pack, unpacked once and kept in memory (the last one used). */
async function unpack(stored: StoredPack): Promise<LoadedPack> {
  if (cache?.id === stored.id) return cache.pack;
  const pack = readPawpack(new Uint8Array(await stored.file.arrayBuffer()));
  pack.info.name = stored.info.name;
  cache = { id: stored.id, pack };
  return pack;
}

/** The default block pack. Null when no pack has been added. */
export async function loadDefaultPack(): Promise<LoadedPack | null> {
  const packs = await listPacks();
  const chosen = packs.find((p) => p.isDefault) ?? packs[0];
  return chosen ? unpack(chosen) : null;
}

/** A block pack by its id; null when it was deleted. */
export async function loadPack(id: string): Promise<LoadedPack | null> {
  const stored = await getPack(id);
  return stored ? unpack(stored) : null;
}
