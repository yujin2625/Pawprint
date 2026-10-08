import { readPawpack, type LoadedPack } from '../../core/pack/pawpack';
import { listPacks } from '../../storage/db';

let cache: { id: string; pack: LoadedPack } | null = null;

/** The default block pack, unpacked once and kept in memory. Null when no pack has been added. */
export async function loadDefaultPack(): Promise<LoadedPack | null> {
  const packs = await listPacks();
  const chosen = packs.find((p) => p.isDefault) ?? packs[0];
  if (!chosen) return null;
  if (cache?.id === chosen.id) return cache.pack;
  const pack = readPawpack(new Uint8Array(await chosen.file.arrayBuffer()));
  pack.info.name = chosen.info.name;
  cache = { id: chosen.id, pack };
  return pack;
}
