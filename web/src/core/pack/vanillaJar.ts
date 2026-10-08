import { FileFormatError, jsonBytes, readZip, utf8, writeZip } from '../zip';
import { blockstateModels, modelPath, splitLocation, texturePath } from './resources';
import { DEFAULT_COLORS, PACK_FORMAT, type BlockDef, type PackInfo, type RenderLayer, type Tint } from './types';

/**
 * Builds a `.pawpack` from a vanilla client jar (`.minecraft/versions/<v>/<v>.jar`). Only English names are in the
 * jar (the desktop app passes other languages from the launcher's assets), and block properties are inferred from blockstate files, so the pack is marked `propertiesComplete: false`.
 * See docs/FORMAT_PAWPACK.md §8.
 */
export interface JarPackOptions {
  id: string;
  now: string;
  name?: string;
  generator?: string;
  /** More languages (code → the game's language table, at least its `block.*` keys); the jar itself only has `en_us`. */
  languages?: Record<string, Record<string, string>>;
}

export interface JarPack {
  bytes: Uint8Array;
  info: PackInfo;
}

const JAR_LIMITS = { maxEntries: 100_000, maxEntryBytes: 32 * 1024 * 1024, maxTotalBytes: 512 * 1024 * 1024 };
const BLOCKSTATES = 'assets/minecraft/blockstates/';

export function buildPackFromJar(jar: Uint8Array, options: JarPackOptions): JarPack {
  // Pass 1: everything except textures (small JSON files).
  const meta = readZip(jar, JAR_LIMITS, (name) =>
    name === 'version.json' ||
    name === 'assets/minecraft/lang/en_us.json' ||
    name.startsWith(BLOCKSTATES) ||
    name.startsWith('assets/minecraft/models/') ||
    name.startsWith('assets/minecraft/items/'),
  );
  const versionData = meta.get('version.json');
  if (!versionData) throw new FileFormatError('error.jar.notMinecraft');
  const version = parse(versionData) as { id?: string; world_version?: number; name?: string };
  const mcVersion = String(version.id ?? version.name ?? '');
  if (!mcVersion) throw new FileFormatError('error.jar.notMinecraft');
  const lang = (meta.has('assets/minecraft/lang/en_us.json') ? parse(meta.get('assets/minecraft/lang/en_us.json')!) : {}) as Record<string, string>;

  const models = new ModelScanner(meta);
  const blocks: BlockDef[] = [];
  const names: Record<string, string> = {};
  const extra = Object.entries(options.languages ?? {}).filter(([code]) => /^[a-z0-9_]+$/.test(code) && code !== 'en_us');
  const extraNames = new Map<string, Record<string, string>>(extra.map(([code]) => [code, {}]));
  const out = new Map<string, Uint8Array>();

  const stateFiles = [...meta.keys()].filter((n) => n.startsWith(BLOCKSTATES) && n.endsWith('.json')).sort();
  for (const file of stateFiles) {
    const path = file.slice(BLOCKSTATES.length, -'.json'.length);
    const id = 'minecraft:' + path;
    let blockstate: unknown;
    try {
      blockstate = parse(meta.get(file)!);
    } catch {
      continue;
    }
    out.set(file, meta.get(file)!);

    const refs = blockstateModels(blockstate);
    let hasElements = false;
    for (const ref of refs) hasElements = models.include(ref) || hasElements;
    const properties = inferProperties(blockstate);
    const defaults = Object.fromEntries(Object.entries(properties).map(([k, v]) => [k, guessDefault(k, v)]));
    const fluid = path === 'water' || path === 'lava' ? id : undefined;
    blocks.push({
      id,
      properties,
      default: defaults,
      item: hasItem(meta, path) ? id : null,
      renderLayer: guessRenderLayer(path),
      renderShape: refs.length === 0 || INVISIBLE.has(path) ? 'invisible' : hasElements ? 'model' : 'entity',
      tint: vanillaTint(path),
      tabs: [],
      order: blocks.length,
      ...(fluid ? { fluid } : {}),
    });
    names[id] = lang['block.minecraft.' + path] ?? humanize(path);
    for (const [code, table] of extra) {
      const name = table['block.minecraft.' + path];
      if (name) extraNames.get(code)![id] = name;
    }
  }
  if (blocks.length === 0) throw new FileFormatError('error.jar.notMinecraft');

  for (const [name, data] of models.files) out.set(name, data);

  // Pass 2: only the textures the models use, plus animation metadata.
  const textures = readZip(jar, JAR_LIMITS, (name) => models.textures.has(name) || (name.endsWith('.mcmeta') && models.textures.has(name.slice(0, -'.mcmeta'.length))));
  for (const [name, data] of textures) out.set(name, data);

  const info: PackInfo = {
    format: PACK_FORMAT,
    id: options.id,
    name: options.name ?? `Minecraft ${mcVersion}`,
    created: options.now,
    generator: options.generator,
    source: 'vanilla-jar',
    mcVersion,
    dataVersion: Number(version.world_version ?? 0),
    loader: 'vanilla',
    mods: [],
    resourcePacks: ['vanilla'],
    languages: ['en_us', ...[...extraNames.keys()].filter((c) => Object.keys(extraNames.get(c)!).length > 0)].sort(),
    blockCount: blocks.length,
    propertiesComplete: false,
  };
  out.set('pack.json', jsonBytes(info));
  out.set('blocks.json', jsonBytes(blocks));
  out.set('lang/en_us.json', jsonBytes(names));
  for (const code of info.languages) if (code !== 'en_us') out.set(`lang/${code}.json`, jsonBytes(extraNames.get(code)!));
  out.set('colors.json', jsonBytes(DEFAULT_COLORS));
  return { bytes: writeZip(out), info };
}

function parse(data: Uint8Array): unknown {
  return JSON.parse(utf8(data));
}

/** Follows model parents, remembering the files and textures they use. */
class ModelScanner {
  readonly files = new Map<string, Uint8Array>();
  readonly textures = new Set<string>();
  private readonly elements = new Map<string, boolean>();

  constructor(private readonly source: Map<string, Uint8Array>) {}

  /** Adds a model and its parents; returns whether the model (or a parent) has elements. */
  include(ref: string, depth = 0): boolean {
    const [, path] = splitLocation(ref);
    if (path.startsWith('builtin/') || depth > 32) return false;
    const file = modelPath(ref);
    const known = this.elements.get(file);
    if (known !== undefined) return known;
    this.elements.set(file, false); // guards against parent loops
    const data = this.source.get(file);
    if (!data) return false;
    let model: { parent?: unknown; textures?: Record<string, unknown>; elements?: unknown };
    try {
      model = parse(data) as typeof model;
    } catch {
      return false;
    }
    this.files.set(file, data);
    for (const value of Object.values(model.textures ?? {})) {
      if (typeof value === 'string' && !value.startsWith('#')) this.textures.add(texturePath(value));
    }
    let has = Array.isArray(model.elements) && model.elements.length > 0;
    if (typeof model.parent === 'string') has = this.include(model.parent, depth + 1) || has;
    this.elements.set(file, has);
    return has;
  }
}

/** Property names and values seen in `variants` keys and `multipart` conditions, in first-seen order. */
export function inferProperties(blockstate: unknown): Record<string, string[]> {
  const props = new Map<string, string[]>();
  const add = (name: string, value: string): void => {
    if (!name) return;
    const list = props.get(name) ?? [];
    if (!list.includes(value)) list.push(value);
    props.set(name, list);
  };
  const state = blockstate as { variants?: Record<string, unknown>; multipart?: { when?: unknown }[] };
  if (state?.variants) {
    for (const key of Object.keys(state.variants)) {
      for (const pair of key.split(',')) {
        const [name, value] = pair.split('=');
        if (name && value !== undefined) add(name, value);
      }
    }
  }
  const walk = (when: unknown): void => {
    if (!when || typeof when !== 'object') return;
    for (const [name, value] of Object.entries(when as Record<string, unknown>)) {
      if (name === 'OR' || name === 'AND') {
        if (Array.isArray(value)) value.forEach(walk);
      } else {
        for (const v of String(value).split('|')) add(name, v.replace(/^!/, ''));
      }
    }
  };
  if (Array.isArray(state?.multipart)) state.multipart.forEach((part) => walk(part?.when));
  return Object.fromEntries(props);
}

const INVISIBLE = new Set(['air', 'cave_air', 'void_air', 'barrier', 'light', 'structure_void', 'moving_piston']);

/** Usual game defaults; blockstate files do not say which value is the default. */
const PREFERRED: Record<string, string> = {
  facing: 'north', horizontal_facing: 'north', half: 'bottom', shape: 'straight', type: 'bottom', axis: 'y',
  waterlogged: 'false', powered: 'false', open: 'false', lit: 'false', snowy: 'false', hinge: 'left',
  attachment: 'floor', face: 'wall', part: 'foot', occupied: 'false', triggered: 'false', inverted: 'false',
  north: 'false', east: 'false', south: 'false', west: 'false', up: 'false', down: 'false',
};

function guessDefault(name: string, values: string[]): string {
  const preferred = PREFERRED[name];
  if (preferred !== undefined && values.includes(preferred)) return preferred;
  if (values.every((v) => /^\d+$/.test(v))) return String(Math.min(...values.map(Number)));
  return values[0]!;
}

function hasItem(files: Map<string, Uint8Array>, path: string): boolean {
  return files.has(`assets/minecraft/models/item/${path}.json`) || files.has(`assets/minecraft/items/${path}.json`);
}

function humanize(path: string): string {
  return path.split('_').map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(' ');
}

const TRANSLUCENT = /(^|_)(stained_glass(_pane)?|ice|slime_block|honey_block|tinted_glass|water|nether_portal|bubble_column)$/;
const CUTOUT_MIPPED = /(_leaves|^grass_block)$/;
const CUTOUT =
  /(^glass(_pane)?$|sapling|flower|tulip|orchid|allium|bluet|daisy|poppy|dandelion|cornflower|lily_of_the_valley|wither_rose|rose_bush|lilac|peony|sunflower|torch|lantern|door|ladder|rail|vine|fern|^short_grass$|^grass$|^tall_grass|cobweb|_bars$|chain$|scaffolding|fire$|campfire|wheat|carrots|potatoes|beetroots|_stem$|sugar_cane|kelp|seagrass|_coral(_fan|_wall_fan)?$|sweet_berry_bush|bamboo|cactus|spawner|beacon|mushroom$|_roots$|_fungus$|_sprouts$|dead_bush|pitcher|torchflower|spore_blossom|hanging_roots|azalea|dripleaf|glow_lichen|sculk_vein|frogspawn|cave_vines|weeping_vines|twisting_vines|nether_wart|cocoa|lily_pad|repeater|comparator|redstone_wire|tripwire|lever|trial_spawner|vault|_petals$|leaf_litter|bush$|firefly_bush|cactus_flower)/;

function guessRenderLayer(path: string): RenderLayer {
  if (TRANSLUCENT.test(path)) return 'translucent';
  if (CUTOUT_MIPPED.test(path)) return 'cutout_mipped';
  if (CUTOUT.test(path)) return 'cutout';
  return 'solid';
}

const GRASS = new Set(['grass_block', 'short_grass', 'grass', 'tall_grass', 'fern', 'large_fern', 'potted_fern', 'sugar_cane']);
const FOLIAGE = new Set(['oak_leaves', 'jungle_leaves', 'acacia_leaves', 'dark_oak_leaves', 'mangrove_leaves', 'vine']);
const CONSTANT: Record<string, string> = {
  spruce_leaves: '#619961',
  birch_leaves: '#80A755',
  lily_pad: '#208030',
  attached_melon_stem: '#E0C71C',
  attached_pumpkin_stem: '#E0C71C',
};

function vanillaTint(path: string): Tint | null {
  if (GRASS.has(path)) return { kind: 'grass', color: DEFAULT_COLORS.grass };
  if (FOLIAGE.has(path)) return { kind: 'foliage', color: DEFAULT_COLORS.foliage };
  if (path === 'water' || path === 'water_cauldron' || path === 'bubble_column') return { kind: 'water', color: DEFAULT_COLORS.water };
  const constant = CONSTANT[path];
  if (constant) return { kind: 'constant', color: constant };
  if (path === 'redstone_wire') return { kind: 'other', color: redstoneColor(0), byState: redstoneByState() };
  if (path === 'melon_stem' || path === 'pumpkin_stem') return { kind: 'other', color: stemColor(0), byState: stemByState() };
  return null;
}

const hex = (r: number, g: number, b: number): string =>
  '#' + [r, g, b].map((v) => Math.round(Math.max(0, Math.min(1, v)) * 255).toString(16).padStart(2, '0')).join('').toUpperCase();

/** Same formula as the game's redstone wire color. */
function redstoneColor(power: number): string {
  const f = power / 15;
  return hex(f * 0.6 + (f > 0 ? 0.4 : 0.3), f * f * 0.7 - 0.5, f * f * 0.6 - 0.7);
}

function redstoneByState(): Record<string, string> {
  return Object.fromEntries(Array.from({ length: 16 }, (_, p) => [`power=${p}`, redstoneColor(p)]));
}

function stemColor(age: number): string {
  return hex((age * 32) / 255, (255 - age * 8) / 255, (age * 4) / 255);
}

function stemByState(): Record<string, string> {
  return Object.fromEntries(Array.from({ length: 8 }, (_, a) => [`age=${a}`, stemColor(a)]));
}
