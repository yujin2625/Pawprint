import { unzipSync } from 'fflate';
import { FileFormatError, isSafeEntryName, readZip, utf8 } from '../zip';
import { buildPackFromAssets, type AssetSource, type BuiltPack, type IconRenderer } from './assetPack';
import { mergeTagFile, tagIdOf } from './categories';

export { inferProperties } from './assetPack';

/**
 * Builds a `.pawpack` from a vanilla client jar (`.minecraft/versions/<v>/<v>.jar`). Only English names are in the
 * jar (the desktop app passes other languages from the launcher's assets). See docs/FORMAT_PAWPACK.md §8.
 */
export interface JarPackOptions {
  id: string;
  now: string;
  name?: string;
  generator?: string;
  /** More languages (code → the game's language table, at least its `block.*` keys). */
  languages?: Record<string, Record<string, string>>;
  /** Draws the palette icons (needs a canvas, so the app passes it in). */
  renderIcons?: IconRenderer;
}

export type JarPack = BuiltPack;

const JAR_LIMITS = { maxEntries: 100_000, maxEntryBytes: 32 * 1024 * 1024, maxTotalBytes: 512 * 1024 * 1024 };
const LANG = 'assets/minecraft/lang/en_us.json';

/** Entry names of a zip without unpacking it. */
export function zipNames(bytes: Uint8Array): string[] {
  const names: string[] = [];
  try {
    unzipSync(bytes, {
      filter: (file) => {
        if (!file.name.endsWith('/') && isSafeEntryName(file.name)) names.push(file.name);
        return false;
      },
    });
  } catch {
    throw new FileFormatError('error.zip.unreadable');
  }
  return names;
}

const LOOT_TABLE = /^data\/[a-z0-9_.-]+\/loot_tables?\/blocks\//;

/** An in-memory zip as an asset source. */
export function zipSource(bytes: Uint8Array): AssetSource {
  const all = zipNames(bytes);
  return {
    names: all.filter((n) => (n.startsWith('assets/') && !n.includes('/textures/')) || LOOT_TABLE.test(n)),
    read: async (paths) => {
      const set = new Set(paths);
      return readZip(bytes, JAR_LIMITS, (n) => set.has(n));
    },
    blockTags: async () => {
      const files = new Set(all.filter((n) => tagIdOf(n)));
      const tags: Record<string, string[]> = {};
      for (const [name, data] of readZip(bytes, JAR_LIMITS, (n) => files.has(n))) {
        try {
          mergeTagFile(tags, tagIdOf(name)!, JSON.parse(utf8(data)));
        } catch {
          // A damaged tag file only loses that tag.
        }
      }
      return tags;
    },
  };
}

export async function buildPackFromJar(jar: Uint8Array, options: JarPackOptions): Promise<JarPack> {
  const meta = readZip(jar, JAR_LIMITS, (n) => n === 'version.json' || n === LANG);
  const versionData = meta.get('version.json');
  if (!versionData) throw new FileFormatError('error.jar.notMinecraft');
  let version: { id?: string; world_version?: number; name?: string };
  try {
    version = JSON.parse(utf8(versionData)) as typeof version;
  } catch {
    throw new FileFormatError('error.jar.notMinecraft');
  }
  const mcVersion = String(version.id ?? version.name ?? '');
  if (!mcVersion) throw new FileFormatError('error.jar.notMinecraft');
  let english: Record<string, string> = {};
  try {
    if (meta.has(LANG)) english = JSON.parse(utf8(meta.get(LANG)!)) as Record<string, string>;
  } catch {
    // Names fall back to the block IDs.
  }
  const source = zipSource(jar);
  // Only the game's own blocks: a jar with other namespaces (a modded jar) is not a vanilla jar.
  source.names = source.names.filter((n) => n.startsWith('assets/minecraft/') || n.startsWith('data/minecraft/'));
  return buildPackFromAssets(source, {
    id: options.id,
    now: options.now,
    name: options.name ?? `Minecraft ${mcVersion}`,
    generator: options.generator,
    source: 'vanilla-jar',
    mcVersion,
    dataVersion: Number(version.world_version ?? 0),
    loader: 'vanilla',
    resourcePacks: ['vanilla'],
    languages: { ...options.languages, en_us: english },
    renderIcons: options.renderIcons,
  });
}
