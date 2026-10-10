import { baseName, instanceBlockTags, instanceDone, instanceIndex, instanceLanguages, instanceRead, minecraftLanguages, type InstanceInfo } from '../../platform/platform';
import { buildPack } from './addPack';
import type { StoredPack } from '../../storage/db';

export type InstanceStep = 'index' | 'blocks' | 'icons' | 'pack';

/**
 * Desktop: a block pack from a modded game folder without starting the game. Blocks, models, textures and names
 * come from the vanilla jar, the mod jars, cached generated assets and the enabled resource packs. The app indexes
 * and reads the files; a worker turns them into the pack.
 */
export async function packFromInstance(dir: string, info: InstanceInfo, vanillaJar: string, step: (s: InstanceStep) => void): Promise<StoredPack> {
  step('index');
  try {
    const stats = await instanceIndex(dir, vanillaJar);
    // Vanilla names in other languages are in the launcher's assets, under the mods' and resource packs' ones.
    const [modded, launcher, tags] = await Promise.all([
      instanceLanguages(),
      info.launcherRoot && info.assetIndex
        ? minecraftLanguages({ version: '', jar: vanillaJar, root: info.launcherRoot, assetIndex: info.assetIndex, modified: 0 }).catch(() => ({}))
        : Promise.resolve({}),
      instanceBlockTags(),
    ]);
    const languages: Record<string, Record<string, string>> = { ...launcher };
    for (const [code, table] of Object.entries(modded)) languages[code] = { ...languages[code], ...table };
    return await buildPack({
      kind: 'assets',
      names: stats.names,
      tags,
      options: {
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
      },
    }, { read: instanceRead, step });
  } finally {
    await instanceDone().catch(() => {});
  }
}
