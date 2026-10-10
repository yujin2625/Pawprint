import { buildPackFromAssets, type AssetSource } from '../../core/pack/assetPack';
import { readZip } from '../../core/zip';
import { baseName, instanceBlockTags, instanceDone, instanceIndex, instanceLanguages, instanceRead, minecraftLanguages, type InstanceInfo } from '../../platform/platform';
import { renderPackIcons } from '../../render/packIcons';
import { toStoredPack } from './addPack';
import type { StoredPack } from '../../storage/db';

const LIMITS = { maxEntries: 400_000, maxEntryBytes: 64 * 1024 * 1024, maxTotalBytes: 1024 * 1024 * 1024 };
/** Paths per read, so no single transfer gets huge. */
const BATCH = 3000;

export type InstanceStep = 'index' | 'blocks' | 'pack';

/**
 * Desktop: a block pack from a modded game folder without starting the game. Blocks, models, textures and names
 * come from the vanilla jar, the mod jars, cached generated assets and the enabled resource packs.
 */
export async function packFromInstance(dir: string, info: InstanceInfo, vanillaJar: string, step: (s: InstanceStep) => void): Promise<StoredPack> {
  step('index');
  try {
    const stats = await instanceIndex(dir, vanillaJar);
    const source: AssetSource = {
      names: stats.names,
      read: async (paths) => {
        const out = new Map<string, Uint8Array>();
        for (let i = 0; i < paths.length; i += BATCH) {
          const zip = await instanceRead(paths.slice(i, i + BATCH));
          for (const [name, data] of readZip(zip, LIMITS)) out.set(name, data);
        }
        return out;
      },
      blockTags: instanceBlockTags,
    };
    step('blocks');
    // Vanilla names in other languages are in the launcher's assets, under the mods' and resource packs' ones.
    const [modded, launcher] = await Promise.all([
      instanceLanguages(),
      info.launcherRoot && info.assetIndex
        ? minecraftLanguages({ version: '', jar: vanillaJar, root: info.launcherRoot, assetIndex: info.assetIndex, modified: 0 }).catch(() => ({}))
        : Promise.resolve({}),
    ]);
    const languages: Record<string, Record<string, string>> = { ...launcher };
    for (const [code, table] of Object.entries(modded)) languages[code] = { ...languages[code], ...table };
    const built = await buildPackFromAssets(source, {
      id: crypto.randomUUID(),
      now: new Date().toISOString(),
      name: baseName(dir.replace(/[\/]+$/, '')),
      generator: 'pawprint-app',
      source: 'instance-folder',
      mcVersion: info.mcVersion ?? 'unknown',
      dataVersion: info.dataVersion ?? 0,
      loader: info.loader ?? 'unknown',
      mods: stats.mods,
      resourcePacks: [...info.resourcePacks].reverse(),
      languages,
      renderIcons: renderPackIcons,
    });
    step('pack');
    return toStoredPack(built.bytes);
  } finally {
    await instanceDone().catch(() => {});
  }
}
