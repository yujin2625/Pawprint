import { FileFormatError, jsonBytes, utf8, writeZip } from '../zip';
import { blockstateModels, modelPath, splitLocation, texturePath } from './resources';
import { DEFAULT_COLORS, PACK_FORMAT, type BlockDef, type PackInfo, type PackSource, type RenderLayer, type Tint } from './types';

/**
 * Builds a `.pawpack` from game assets without running the game: a vanilla jar, or a whole modded instance (every
 * namespace). Blocks and their properties are inferred from blockstate files, so the pack is marked
 * `propertiesComplete: false`. See docs/FORMAT_PAWPACK.md §8.
 */

/** Where the assets come from. Paths are `assets/<namespace>/...`; the highest-priority copy of each is used. */
export interface AssetSource {
  /** Every blockstate, model and item model path (textures need not be listed). */
  names: string[];
  read(paths: string[]): Promise<Map<string, Uint8Array>>;
}

export interface AssetPackOptions {
  id: string;
  now: string;
  name: string;
  generator?: string;
  source: PackSource;
  mcVersion: string;
  dataVersion: number;
  loader: string;
  mods?: { id: string; name: string; version: string }[];
  resourcePacks?: string[];
  /** Language code → the game's language table (at least its `block.*` keys). `en_us` names the blocks first. */
  languages: Record<string, Record<string, string>>;
}

export interface BuiltPack {
  bytes: Uint8Array;
  info: PackInfo;
}

const BLOCKSTATE = /^assets\/([a-z0-9_.-]+)\/blockstates\/([a-z0-9_./-]+)\.json$/;

export async function buildPackFromAssets(source: AssetSource, options: AssetPackOptions): Promise<BuiltPack> {
  const names = new Set(source.names);
  const stateFiles = source.names.filter((n) => BLOCKSTATE.test(n)).sort();
  const states = await source.read(stateFiles);
  const models = new ModelScanner(source, names);

  const blocks: BlockDef[] = [];
  const out = new Map<string, Uint8Array>();
  const parsed: { id: string; ns: string; path: string; refs: string[]; blockstate: unknown }[] = [];
  for (const file of stateFiles) {
    const [, ns, path] = BLOCKSTATE.exec(file)!;
    const data = states.get(file);
    if (!data) continue;
    let blockstate: unknown;
    try {
      blockstate = JSON.parse(utf8(data));
    } catch {
      continue;
    }
    out.set(file, data);
    parsed.push({ id: `${ns}:${path}`, ns: ns!, path: path!, refs: blockstateModels(blockstate), blockstate });
  }
  await models.load(parsed.flatMap((p) => p.refs));

  const langCodes = Object.keys(options.languages).filter((c) => /^[a-z0-9_]+$/.test(c));
  const tables = new Map<string, Record<string, string>>(langCodes.map((c) => [c, {}]));
  if (!tables.has('en_us')) tables.set('en_us', {});
  const modIds = new Set<string>();

  for (const { id, ns, path, refs, blockstate } of parsed) {
    const shape = models.shape(refs);
    const properties = inferProperties(blockstate);
    const defaults = Object.fromEntries(Object.entries(properties).map(([k, v]) => [k, guessDefault(k, v)]));
    const vanilla = ns === 'minecraft';
    const fluid = vanilla && (path === 'water' || path === 'lava') ? id : undefined;
    blocks.push({
      id,
      properties,
      default: defaults,
      item: names.has(`assets/${ns}/models/item/${path}.json`) || names.has(`assets/${ns}/items/${path}.json`) ? id : null,
      renderLayer: guessRenderLayer(path),
      renderShape: refs.length === 0 || (vanilla && INVISIBLE.has(path)) ? 'invisible' : shape.elements ? 'model' : 'entity',
      tint: vanilla ? vanillaTint(path) : shape.tinted ? guessTint(path) : null,
      tabs: [],
      order: blocks.length,
      ...(fluid ? { fluid } : {}),
    });
    if (!vanilla) modIds.add(ns);
    const key = `block.${ns}.${path.replaceAll('/', '.')}`;
    for (const [code, table] of tables) {
      const name = options.languages[code]?.[key];
      if (name) table[id] = name;
      else if (code === 'en_us') table[id] = humanize(path);
    }
  }
  if (blocks.length === 0) throw new FileFormatError('error.jar.notMinecraft');

  for (const [name, data] of models.files) out.set(name, data);
  const textures = await source.read([...models.textures].flatMap((t) => [t, t + '.mcmeta']));
  for (const [name, data] of textures) out.set(name, data);

  const languages = [...tables].filter(([, t]) => Object.keys(t).length > 0).map(([c]) => c).sort();
  const info: PackInfo = {
    format: PACK_FORMAT,
    id: options.id,
    name: options.name,
    created: options.now,
    generator: options.generator,
    source: options.source,
    mcVersion: options.mcVersion,
    dataVersion: options.dataVersion,
    loader: options.loader,
    mods: (options.mods ?? []).filter((m) => modIds.has(m.id)),
    resourcePacks: options.resourcePacks ?? ['vanilla'],
    languages,
    blockCount: blocks.length,
    propertiesComplete: false,
  };
  out.set('pack.json', jsonBytes(info));
  out.set('blocks.json', jsonBytes(blocks));
  for (const code of languages) out.set(`lang/${code}.json`, jsonBytes(tables.get(code)!));
  out.set('colors.json', jsonBytes(DEFAULT_COLORS));
  return { bytes: writeZip(out), info };
}

interface ModelData {
  parent?: unknown;
  textures?: Record<string, unknown>;
  elements?: { faces?: Record<string, { tintindex?: unknown }> }[];
}

/** Loads models and their parents in waves (one read per level), remembering files and textures they use. */
class ModelScanner {
  readonly files = new Map<string, Uint8Array>();
  readonly textures = new Set<string>();
  private readonly models = new Map<string, ModelData | null>();

  constructor(
    private readonly source: AssetSource,
    private readonly names: Set<string>,
  ) {}

  async load(refs: string[]): Promise<void> {
    let wave = [...new Set(refs.map(fileOf).filter((f): f is string => !!f))];
    for (let depth = 0; wave.length && depth < 32; depth++) {
      const todo = wave.filter((f) => !this.models.has(f));
      for (const f of todo) this.models.set(f, null);
      const data = await this.source.read(todo.filter((f) => this.names.has(f)));
      const next: string[] = [];
      for (const file of todo) {
        const bytes = data.get(file);
        if (!bytes) continue;
        let model: ModelData;
        try {
          model = JSON.parse(utf8(bytes)) as ModelData;
        } catch {
          continue;
        }
        this.models.set(file, model);
        this.files.set(file, bytes);
        for (const value of Object.values(model.textures ?? {})) {
          if (typeof value === 'string' && !value.startsWith('#')) this.textures.add(texturePath(value));
        }
        const parent = typeof model.parent === 'string' ? fileOf(model.parent) : null;
        if (parent && !this.models.has(parent)) next.push(parent);
      }
      wave = [...new Set(next)];
    }
  }

  /** Whether any of the models (or their parents) has elements, and whether any face is tinted. */
  shape(refs: string[]): { elements: boolean; tinted: boolean } {
    let elements = false, tinted = false;
    for (const ref of refs) {
      let file = fileOf(ref);
      for (let depth = 0; file && depth < 32; depth++) {
        const model = this.models.get(file);
        if (!model) break;
        if (Array.isArray(model.elements) && model.elements.length) {
          elements = true;
          if (model.elements.some((e) => Object.values(e?.faces ?? {}).some((f) => f && f.tintindex !== undefined))) tinted = true;
          break;
        }
        file = typeof model.parent === 'string' ? fileOf(model.parent) : null;
      }
    }
    return { elements, tinted };
  }
}

function fileOf(ref: string): string | null {
  const [, path] = splitLocation(ref);
  return path.startsWith('builtin/') ? null : modelPath(ref);
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

function humanize(path: string): string {
  const last = path.split('/').pop()!;
  return last.split('_').map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(' ');
}

const TRANSLUCENT = /(^|_)(stained_glass(_pane)?|ice|slime_block|honey_block|tinted_glass|water|nether_portal|bubble_column)$/;
const CUTOUT_MIPPED = /(_leaves|^grass_block)$/;
const CUTOUT =
  /(^glass(_pane)?$|sapling|flower|tulip|orchid|allium|bluet|daisy|poppy|dandelion|cornflower|lily_of_the_valley|wither_rose|rose_bush|lilac|peony|sunflower|torch|lantern|door|ladder|rail|vine|fern|^short_grass$|^grass$|^tall_grass|cobweb|_bars$|chain$|scaffolding|fire$|campfire|wheat|carrots|potatoes|beetroots|_stem$|sugar_cane|kelp|seagrass|_coral(_fan|_wall_fan)?$|sweet_berry_bush|bamboo|cactus|spawner|beacon|mushroom$|_roots$|_fungus$|_sprouts$|dead_bush|pitcher|torchflower|spore_blossom|hanging_roots|azalea|dripleaf|glow_lichen|sculk_vein|frogspawn|cave_vines|weeping_vines|twisting_vines|nether_wart|cocoa|lily_pad|repeater|comparator|redstone_wire|tripwire|lever|trial_spawner|vault|_petals$|leaf_litter|bush$|firefly_bush|cactus_flower|_glass(_pane)?$|trapdoor|_crop$|_sapling$|_plant$)/;

function guessRenderLayer(path: string): RenderLayer {
  const last = path.split('/').pop()!;
  if (TRANSLUCENT.test(last)) return 'translucent';
  if (CUTOUT_MIPPED.test(last)) return 'cutout_mipped';
  if (CUTOUT.test(last)) return 'cutout';
  return 'solid';
}

/** A modded block with a tinted face: grass or foliage color by its name, else none (the texture's own color). */
function guessTint(path: string): Tint | null {
  if (/grass|fern/.test(path)) return { kind: 'grass', color: DEFAULT_COLORS.grass };
  if (/leaves|leaf|vine|ivy|hedge|bush/.test(path)) return { kind: 'foliage', color: DEFAULT_COLORS.foliage };
  if (/water/.test(path)) return { kind: 'water', color: DEFAULT_COLORS.water };
  return null;
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
