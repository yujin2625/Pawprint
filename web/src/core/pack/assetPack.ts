import { FileFormatError, jsonBytes, utf8, writeZip } from '../zip';
import { categorize, categoryByName } from './categories';
import { pngOpacity, type Opacity } from './png';
import { blockstateModels, modelPath, splitLocation, texturePath } from './resources';
import { DEFAULT_COLORS, PACK_FORMAT, type BlockDef, type PackIcons, type PackInfo, type PackSource, type RenderLayer, type Tint } from './types';

/**
 * Builds a `.pawpack` from game assets without running the game: a vanilla jar, or a whole modded instance (every
 * namespace). Blocks and their properties are inferred from blockstate files, so the pack is marked
 * `propertiesComplete: false`. What the files do say is used: a model's `render_type`, how transparent the textures
 * are, block tags (palette categories) and loot tables (the item of a block without one of its own).
 * See docs/FORMAT_PAWPACK.md §8.
 */

/** Where the assets come from. Paths are `assets/<namespace>/...`; the highest-priority copy of each is used. */
export interface AssetSource {
  /** Every blockstate, model, item model and block loot table path (textures need not be listed). */
  names: string[];
  read(paths: string[]): Promise<Map<string, Uint8Array>>;
  /** Block tags merged across every pack: tag ID → block IDs and `#tag` references. */
  blockTags?(): Promise<Record<string, string[]>>;
}

/** What drawing the palette icons needs; the drawing itself needs a canvas, so the app passes it in. */
export interface IconInput {
  blocks: BlockDef[];
  /** The pack's blockstates, models and textures. */
  files: Map<string, Uint8Array>;
  /** Blocks whose item is a flat picture (flowers, doors, signs): block ID → its texture layers, bottom first. */
  flat: Map<string, Uint8Array[]>;
}

export interface IconSheet extends PackIcons {
  png: Uint8Array;
}

export type IconRenderer = (input: IconInput) => Promise<IconSheet | null>;

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
  /** Draws `icons.png`. Without it the pack has no icons. */
  renderIcons?: IconRenderer;
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
  const textures = await source.read([...models.textures].flatMap((t) => [t, t + '.mcmeta']));
  const opacities = new Map<string, Opacity | null>();
  const opacity = (texture: string): Opacity | null => {
    if (!opacities.has(texture)) {
      const data = textures.get(texture);
      opacities.set(texture, data ? pngOpacity(data) : null);
    }
    return opacities.get(texture)!;
  };

  const hasItem = (ns: string, path: string): boolean => names.has(`assets/${ns}/models/item/${path}.json`) || names.has(`assets/${ns}/items/${path}.json`);
  const loot = await lootItems(source, names, parsed.filter((p) => !hasItem(p.ns, p.path)));
  const categories = source.blockTags ? categorize(await source.blockTags()) : new Map<string, string[]>();

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
      item: hasItem(ns, path) ? id : (loot.get(id) ?? null),
      renderLayer: renderLayer(vanilla, path, models.renderType(refs), models.blockTextures(refs).map(opacity)),
      renderShape: refs.length === 0 || (vanilla && INVISIBLE.has(path)) ? 'invisible' : shape.elements ? 'model' : 'entity',
      tint: vanilla ? vanillaTint(path) : shape.tinted ? guessTint(path) : null,
      tabs: categories.get(id) ?? categoryByName(path),
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
  for (const [name, data] of textures) out.set(name, data);
  if (options.renderIcons) {
    const sheet = await options.renderIcons({ blocks, files: out, flat: await flatItems(source, names, blocks) });
    if (sheet) {
      const { png, ...icons } = sheet;
      out.set('icons.png', png);
      out.set('icons.json', jsonBytes(icons));
    }
  }

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

/**
 * The item of blocks that have none of their own, from what they drop (redstone wire → redstone, cocoa → cocoa
 * beans). Only when the loot table names one item, or one that clearly places the block among several.
 */
async function lootItems(source: AssetSource, names: Set<string>, blocks: { id: string; ns: string; path: string }[]): Promise<Map<string, string>> {
  const fileOfBlock = new Map<string, string>();
  for (const { id, ns, path } of blocks) {
    const file = [`data/${ns}/loot_table/blocks/${path}.json`, `data/${ns}/loot_tables/blocks/${path}.json`].find((f) => names.has(f));
    if (file) fileOfBlock.set(id, file);
  }
  const out = new Map<string, string>();
  if (!fileOfBlock.size) return out;
  const data = await source.read([...fileOfBlock.values()]);
  for (const [id, file] of fileOfBlock) {
    const bytes = data.get(file);
    if (!bytes) continue;
    const items = new Set<string>();
    const walk = (node: unknown): void => {
      if (Array.isArray(node)) node.forEach(walk);
      else if (node && typeof node === 'object') {
        const entry = node as { type?: unknown; name?: unknown };
        if (typeof entry.name === 'string' && typeof entry.type === 'string' && /^(minecraft:)?item$/.test(entry.type)) {
          items.add(entry.name.includes(':') ? entry.name : 'minecraft:' + entry.name);
        }
        Object.values(node).forEach(walk);
      }
    };
    try {
      walk(JSON.parse(utf8(bytes)));
    } catch {
      continue;
    }
    // Several drops: the seeds plant a crop; else the one the block is named after (potatoes → potato).
    const seeds = [...items].filter((i) => /seeds?$/.test(i));
    const named = [...items].filter((i) => id.startsWith(i));
    const item = items.size === 1 ? [...items][0] : seeds.length === 1 ? seeds[0] : named.length === 1 ? named[0] : undefined;
    if (item) out.set(id, item);
  }
  return out;
}

/** Texture layers of the items that are flat pictures (`item/generated` with `layer0`…), per block with that item. */
async function flatItems(source: AssetSource, names: Set<string>, blocks: BlockDef[]): Promise<Map<string, Uint8Array[]>> {
  const own = blocks.filter((b) => b.item === b.id).map((b) => ({ id: b.id, loc: splitLocation(b.id) }));
  // 1.21.4+: `items/<path>.json` names the model. Before: the model is `models/item/<path>.json`.
  const itemFiles = own.map(({ loc: [ns, path] }) => `assets/${ns}/items/${path}.json`).filter((f) => names.has(f));
  const definitions = itemFiles.length ? await source.read(itemFiles) : new Map<string, Uint8Array>();
  const refs = new Map<string, string>();
  for (const { id, loc: [ns, path] } of own) {
    let ref: string | null = null;
    const definition = definitions.get(`assets/${ns}/items/${path}.json`);
    if (definition) {
      try {
        ref = firstModel(JSON.parse(utf8(definition)));
      } catch {
        ref = null;
      }
    }
    refs.set(id, ref ?? `${ns}:item/${path}`);
  }
  const scanner = new ModelScanner(source, names);
  await scanner.load([...refs.values()]);
  const layers = new Map<string, string[]>();
  for (const [id, ref] of refs) {
    const found = scanner.flatLayers(ref);
    if (found) layers.set(id, found);
  }
  const wanted = [...new Set([...layers.values()].flat())];
  const images = wanted.length ? await source.read(wanted) : new Map<string, Uint8Array>();
  const out = new Map<string, Uint8Array[]>();
  for (const [id, files] of layers) {
    const found = files.map((f) => images.get(f));
    if (found.every((f): f is Uint8Array => !!f)) out.set(id, found);
  }
  return out;
}

/** The first plain model an item definition names (the default look of conditional or selected models). */
function firstModel(node: unknown): string | null {
  if (!node || typeof node !== 'object') return null;
  if (Array.isArray(node)) {
    for (const child of node) {
      const found = firstModel(child);
      if (found) return found;
    }
    return null;
  }
  const entry = node as { type?: unknown; model?: unknown };
  if (typeof entry.model === 'string' && typeof entry.type === 'string' && /^(minecraft:)?model$/.test(entry.type)) return entry.model;
  for (const child of Object.values(node)) {
    const found = firstModel(child);
    if (found) return found;
  }
  return null;
}

interface ModelData {
  parent?: unknown;
  /** Forge and NeoForge: the render layer, e.g. `minecraft:cutout`. */
  render_type?: unknown;
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

  /** Each model with its parents, nearest first. */
  private chain(ref: string): ModelData[] {
    const out: ModelData[] = [];
    let file = fileOf(ref);
    for (let depth = 0; file && depth < 32; depth++) {
      const model = this.models.get(file);
      if (!model) break;
      out.push(model);
      file = typeof model.parent === 'string' ? fileOf(model.parent) : null;
    }
    return out;
  }

  /** The render layer the models declare (`render_type`, a child's wins over its parent's); the most transparent of them. */
  renderType(refs: string[]): RenderLayer | null {
    let best: RenderLayer | null = null;
    for (const ref of refs) {
      const declared = this.chain(ref).find((m) => typeof m.render_type === 'string')?.render_type as string | undefined;
      const layer = declared ? RENDER_TYPES[declared.replace(/^[a-z0-9_.-]+:/, '')] : undefined;
      if (layer && (!best || LAYER_RANK[layer] > LAYER_RANK[best])) best = layer;
    }
    return best;
  }

  /** Texture files the models draw with. The particle texture only counts when it is all there is. */
  blockTextures(refs: string[]): string[] {
    const drawn = new Set<string>(), particles = new Set<string>();
    for (const ref of refs) {
      for (const model of this.chain(ref)) {
        for (const [key, value] of Object.entries(model.textures ?? {})) {
          if (typeof value === 'string' && !value.startsWith('#')) (key === 'particle' ? particles : drawn).add(texturePath(value));
        }
      }
    }
    return [...(drawn.size ? drawn : particles)];
  }

  /** For an item model that is a flat picture: its `layer0`, `layer1`… texture files. Null for anything else. */
  flatLayers(ref: string): string[] | null {
    const chain = this.chain(ref);
    if (!chain.length || chain.some((m) => Array.isArray(m.elements) && m.elements.length)) return null;
    const textures: Record<string, string> = {};
    for (let i = chain.length - 1; i >= 0; i--) {
      for (const [key, value] of Object.entries(chain[i]!.textures ?? {})) if (typeof value === 'string') textures[key] = value;
    }
    const layers: string[] = [];
    for (let i = 0; i < 8; i++) {
      const value = textures['layer' + i];
      if (!value || value.startsWith('#')) break;
      layers.push(texturePath(value));
    }
    return layers.length ? layers : null;
  }
}

const RENDER_TYPES: Record<string, RenderLayer> = {
  solid: 'solid', cutout: 'cutout', cutout_mipped: 'cutout_mipped', cutout_mipped_all: 'cutout_mipped', translucent: 'translucent', tripwire: 'translucent',
};
const LAYER_RANK: Record<RenderLayer, number> = { solid: 0, cutout: 1, cutout_mipped: 2, translucent: 3 };

/**
 * A block's render layer, from the best evidence there is: what its models declare; for the game's own blocks the
 * list of known names; then how transparent its textures are; then its name.
 */
function renderLayer(vanilla: boolean, path: string, declared: RenderLayer | null, textures: (Opacity | null)[]): RenderLayer {
  if (declared) return declared;
  const byName = guessRenderLayer(path);
  if (vanilla && byName !== 'solid') return byName;
  const known = textures.filter((t): t is Opacity => t !== null);
  if (!known.length) return byName;
  // The game's own translucent blocks are all known by name; a half-transparent texture on another one is an overlay.
  if (known.includes('translucent')) return vanilla ? 'cutout' : 'translucent';
  if (known.includes('cutout')) return byName === 'cutout_mipped' ? byName : 'cutout';
  return 'solid';
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

const TRANSLUCENT = /(^|_)(stained_glass(_pane)?|slime_block|honey_block|tinted_glass|water|nether_portal|bubble_column)$|^(frosted_)?ice$/;
const CUTOUT_MIPPED = /(_leaves|^grass_block)$/;
const CUTOUT =
  /(^glass(_pane)?$|sapling|flower|tulip|orchid|allium|bluet|daisy|poppy|dandelion|cornflower|lily_of_the_valley|wither_rose|rose_bush|lilac|peony|sunflower|torch|^(soul_)?lantern$|door|ladder|rail|vine|fern|^short_grass$|^grass$|^tall_grass|cobweb|_bars$|chain$|scaffolding|fire$|campfire|wheat|carrots|potatoes|beetroots|(melon|pumpkin)_stem$|sugar_cane|^kelp(_plant)?$|seagrass|_coral(_fan|_wall_fan)?$|sweet_berry_bush|^bamboo(_sapling)?$|^potted_|cactus|spawner|beacon|mushroom$|_roots$|_fungus$|_sprouts$|dead_bush|pitcher|torchflower|spore_blossom|hanging_roots|azalea|dripleaf|glow_lichen|sculk_vein|frogspawn|cave_vines|weeping_vines|twisting_vines|^nether_wart$|cocoa|lily_pad|repeater|comparator|redstone_wire|tripwire|lever|trial_spawner|vault|_petals$|leaf_litter|bush$|firefly_bush|cactus_flower|_glass(_pane)?$|trapdoor|_crop$|_sapling$|_plant$)/;

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
