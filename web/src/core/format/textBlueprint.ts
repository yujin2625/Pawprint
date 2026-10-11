import { parseState } from '../model/state';
import type { BlockDef } from '../pack/types';
import { boundsSize, DEFAULT_LAYER, type Blueprint, type PawprintMeta } from './pawprint';

/**
 * Blueprints written as text by an AI (or a person): the instructions to paste into an AI chat, and the reader for
 * the JSON it answers with. Same format and rules as the mod (docs/AI_BLUEPRINT_FORMAT.md, TextBlueprintReader).
 */

export const TEXT_VERSION = 1;
const MAX_POSITIONS = 1_000_000;
const MAX_SHAPE_CELLS = 262_144;
const MAX_COORDINATE = 100_000;
const MAX_Y = 1_000;
/** Marks a position that must be empty. */
const AIR = 'air';

/** The same text the mod copies; only the version and mod list are filled in differently (from the block pack). */
const PROMPT = `You are designing a Minecraft build for the Pawprint mod. Reply with ONE JSON object only, no prose.

Format "Pawprint Text Blueprint v1":
- Axes: x = east, y = up, z = south. Integer coordinates. Ranges are inclusive.
- Top level: {"pawprint": 1, "name": str, "description": str?, "tags": [str]?,
  "palette": {char: block}?, "operations": [op]?, "layers": {"origin": [x,y,z]?, "grid": [[row]]}?}
- Block strings use /setblock syntax: "minecraft:oak_stairs[facing=east,half=bottom]".
  "air" means the spot must be empty. Only use blocks that exist in {game}{mods}.
- Operations run in order, then layers; later writes overwrite earlier ones. Shapes:
  single{at}, line{from,to}, box{from,to}, hollow_box{from,to}, walls{from,to},
  sphere{center,radius}, cylinder{base,radius,height}; each has "block" (palette char or block string).
- Layers: grid = list of layers bottom->top; each layer = list of rows north->south;
  each row = string west->east; one character per block; "." or space = nothing.
  Palette keys are single characters other than "." and space.
- Use operations for large regular parts and layers for details (doors, windows, decoration).
- Set facing/half/axis/hinge properties for stairs, doors, logs and slabs so the build looks right
  when its front faces south. Doors and beds need both halves.

Build request: `;

/** Above this many tokens some AI chats cut a message or skim the end of a long list. */
export const AI_LARGE_TOKENS = 20_000;
/** Above this many tokens only AIs with a large context window take the instructions whole. */
export const AI_HUGE_TOKENS = 60_000;

/** A rough token count for AI chats: about four characters per token for this kind of text. */
export function estimateTokens(text: string): number {
  return Math.ceil(text.length / 4);
}

/** A block for the instructions: its ID and whether it has an item of its own. */
export interface AiBlock {
  id: string;
  item: boolean;
}

const namespaceOf = (id: string): string => (id.includes(':') ? id.slice(0, id.indexOf(':')) : 'minecraft');

/** Modded block IDs written into the instructions, and how many the pack has. */
export interface BlockListing {
  /** Lines "namespace: name, name, …", one per namespace. */
  lines: string[];
  listed: number;
  total: number;
}

/** `count` entries spread evenly over the list, in its order (all of them when it is short enough). */
function spread<T>(list: T[], count: number): T[] {
  if (count >= list.length) return list;
  return Array.from({ length: count }, (_, i) => list[Math.floor((i * list.length) / count)]!);
}

/**
 * Groups modded block IDs by namespace for the instructions, all of them by default. Over `max` (the mod keeps a
 * limit since it has no way to pick mods), every namespace gets an equal share (small
 * ones keep all their blocks), filled with blocks that have an item first; within each kind the kept IDs are spread
 * over the whole list, so a pack sorted by name does not lose everything after "d".
 */
export function listModdedBlocks(blocks: Iterable<AiBlock>, max = Infinity): BlockListing {
  const groups = new Map<string, { withItem: string[]; without: string[] }>();
  const seen = new Set<string>();
  for (const { id, item } of blocks) {
    if (seen.has(id)) continue;
    seen.add(id);
    const ns = namespaceOf(id);
    if (ns === 'minecraft') continue;
    let group = groups.get(ns);
    if (!group) groups.set(ns, (group = { withItem: [], without: [] }));
    (item ? group.withItem : group.without).push(id.slice(ns.length + 1));
  }
  const size = (g: { withItem: string[]; without: string[] }) => g.withItem.length + g.without.length;
  // Equal shares: namespaces smaller than the share give their leftover to the rest.
  const share = new Map<string, number>();
  let left = max;
  const bySize = [...groups].sort((a, b) => size(a[1]) - size(b[1]));
  bySize.forEach(([ns, group], i) => {
    const take = Math.min(size(group), Math.floor(left / (bySize.length - i)));
    share.set(ns, take);
    left -= take;
  });
  const lines: string[] = [];
  let listed = 0, total = 0;
  for (const ns of [...groups.keys()].sort()) {
    const group = groups.get(ns)!;
    const take = share.get(ns)!;
    const items = spread(group.withItem, take);
    const kept = [...items, ...spread(group.without, take - items.length)].sort();
    total += size(group);
    listed += kept.length;
    if (!kept.length) continue;
    const more = size(group) - kept.length;
    lines.push(`${ns}: ${kept.join(', ')}${more ? ` (+${more} more not listed)` : ''}`);
  }
  return { lines, listed, total };
}

/**
 * The instructions for an AI chat. `mcVersion` and `mods` (namespaces of mods with blocks) come from the block
 * pack in use; without a pack the AI is told to stay with current vanilla blocks. With `blocks` (from
 * {@link listModdedBlocks}) the modded block IDs are listed, so the AI does not have to guess them.
 */
export function aiPrompt(mcVersion: string, mods: string[], blocks?: BlockListing): string {
  const namespaces = [...new Set(mods)].filter((m) => m && m !== 'minecraft').sort();
  const listed = !!blocks?.lines.length;
  const modText = !namespaces.length
    ? ''
    : listed
      ? ` and these mods (namespaces): ${namespaces.join(', ')}. Modded blocks are listed at the end; use only those modded IDs` +
        (blocks.listed < blocks.total ? ', and vanilla blocks where none of them fits' : '')
      : ` and these mods (namespaces): ${namespaces.join(', ')}. If unsure whether a modded block ID exists, prefer vanilla blocks`;
  const game = mcVersion ? `Minecraft ${mcVersion}` : 'the latest Minecraft Java Edition';
  let text = PROMPT.replace('{game}', game).replace('{mods}', modText);
  if (listed) {
    const list = `Modded blocks in this game ("namespace: names"; write them as "namespace:name"). Any vanilla block of ${game} may be used too:\n${blocks.lines.join('\n')}\n\n`;
    text = text.replace('Build request: ', list + 'Build request: ');
  }
  return text;
}

/** The mods (namespaces) that have blocks in a list, with how many, by name. */
export function moddedNamespaces(blocks: Iterable<AiBlock>): { namespace: string; blocks: number }[] {
  const counts = new Map<string, number>();
  const seen = new Set<string>();
  for (const { id } of blocks) {
    const ns = namespaceOf(id);
    if (ns === 'minecraft' || seen.has(id)) continue;
    seen.add(id);
    counts.set(ns, (counts.get(ns) ?? 0) + 1);
  }
  return [...counts].sort((a, b) => (a[0] < b[0] ? -1 : 1)).map(([namespace, n]) => ({ namespace, blocks: n }));
}

/**
 * The instructions for a block list, naming and listing only the chosen mods (all of them when `mods` is not
 * given). The AI is told to use only listed modded IDs, so a mod left out is not used at all.
 */
export function aiPromptForBlocks(mcVersion: string, blocks: AiBlock[], mods?: ReadonlySet<string>): { text: string; listing: BlockListing } {
  const chosen = blocks.filter((b) => {
    const ns = namespaceOf(b.id);
    return ns !== 'minecraft' && (!mods || mods.has(ns));
  });
  const listing = listModdedBlocks(chosen);
  return { text: aiPrompt(mcVersion, chosen.map((b) => namespaceOf(b.id)), listing), listing };
}

/** Whether a text looks like an AI's answer in this format (possibly inside a code fence or with chatter around it). */
export function looksLikeTextBlueprint(text: string): boolean {
  return /"pawprint"\s*:/.test(text) && text.indexOf('{') >= 0;
}

/** The message is in English and names the exact spot, so it can be pasted back to the AI that wrote the JSON. */
export class TextFormatError extends Error {}

/** What to paste back to the AI when its JSON was rejected. */
export function fixRequest(error: TextFormatError): string {
  return `The Pawprint importer rejected the JSON: ${error.message}\nPlease fix it and reply with the corrected JSON only.`;
}

export interface TextResult {
  blueprint: Blueprint;
  /** Such as blocks the open pack does not have. */
  warnings: string[];
}

/** What the reader may know about blocks: the open pack's catalog, and whether its property lists are complete. */
export interface KnownBlocks {
  blocks: Map<string, BlockDef>;
  /** True when the pack came from the game: states can then be checked like the game would. */
  complete: boolean;
}

type Json = Record<string, unknown>;
type Pos = [number, number, number];

/**
 * Reads the JSON found in `text`: everything from the first `{` to the last `}`. `meta` gives the new blueprint its
 * id, author and times. Throws {@link TextFormatError}.
 */
export function readTextBlueprint(text: string, meta: PawprintMeta, known: KnownBlocks | null = null): TextResult {
  const start = text.indexOf('{'), end = text.lastIndexOf('}');
  if (start < 0 || end <= start) throw new TextFormatError('No JSON object found. The reply must contain one JSON object.');
  let root: unknown;
  try {
    root = JSON.parse(text.slice(start, end + 1));
  } catch (e) {
    throw new TextFormatError('Invalid JSON: ' + (e instanceof Error ? e.message : String(e)));
  }
  if (!root || typeof root !== 'object' || Array.isArray(root)) throw new TextFormatError('The top level must be a JSON object.');
  return new Reader(known).read(root as Json, meta);
}

class Reader {
  private readonly palette = new Map<string, string>();
  /** "x,y,z" → block (or AIR). Insertion order is kept; a later write replaces the value in place. */
  private readonly cells = new Map<string, string>();
  private readonly unknown = new Set<string>();
  private readonly resolved = new Map<string, string>();

  constructor(private readonly known: KnownBlocks | null) {}

  read(root: Json, meta: PawprintMeta): TextResult {
    const version = intField(root, 'pawprint', 'top level');
    if (version !== TEXT_VERSION) throw new TextFormatError(`"pawprint" must be ${TEXT_VERSION}, got ${version}.`);
    const name = stringField(root, 'name', 'top level').trim();
    if (!name) throw new TextFormatError('"name" must not be empty.');
    if ('palette' in root) this.readPalette(object(root.palette, 'palette'));
    let hasContent = false;
    if ('operations' in root) {
      array(root.operations, 'operations').forEach((op, i) => this.readOperation(object(op, `operations[${i}]`), `operations[${i}]`));
      hasContent = true;
    }
    if ('layers' in root) {
      this.readLayers(object(root.layers, 'layers'));
      hasContent = true;
    }
    if (!hasContent) throw new TextFormatError('Either "operations" or "layers" is required.');
    if (!this.cells.size) throw new TextFormatError('The blueprint contains no blocks.');
    const tags = 'tags' in root ? array(root.tags, 'tags').map((tag, i) => string(tag, `tags[${i}]`)) : [];
    const description = 'description' in root ? stringField(root, 'description', 'top level') : '';
    return {
      blueprint: this.build({ ...meta, format: 1, name, description, tags }),
      warnings: [...this.unknown].map((block) => `Unknown block kept as is (not in the block pack): ${block}`),
    };
  }

  private readPalette(palette: Json): void {
    for (const [key, value] of Object.entries(palette)) {
      const where = `palette "${key}"`;
      if ([...key].length !== 1) throw new TextFormatError(`${where}: palette keys must be exactly one character.`);
      if (key === '.' || key === ' ') throw new TextFormatError(`${where}: "." and space are reserved for "nothing" and cannot be keys.`);
      this.palette.set(key, this.block(string(value, where), where));
    }
  }

  private readOperation(op: Json, where: string): void {
    const shape = stringField(op, 'shape', where).toLowerCase();
    const block = this.blockField(op, where);
    const put = (x: number, y: number, z: number): void => void this.cells.set(`${x},${y},${z}`, block);
    const limit = (cells: number): void => {
      if (cells > MAX_SHAPE_CELLS) throw new TextFormatError(`${where}: shape covers about ${cells} blocks; the limit is ${MAX_SHAPE_CELLS}.`);
    };
    if (shape === 'single') {
      const at = pos(op, 'at', where);
      put(...at);
    } else if (shape === 'line') {
      const a = pos(op, 'from', where), b = pos(op, 'to', where);
      // Steps along the longest axis so the line has no gaps.
      const d = [b[0] - a[0], b[1] - a[1], b[2] - a[2]] as Pos;
      const steps = Math.max(Math.abs(d[0]), Math.abs(d[1]), Math.abs(d[2]));
      limit(steps + 1);
      for (let i = 0; i <= steps; i++) {
        const t = steps ? i / steps : 0;
        put(javaRound(a[0] + d[0] * t), javaRound(a[1] + d[1] * t), javaRound(a[2] + d[2] * t));
      }
    } else if (shape === 'box' || shape === 'hollow_box' || shape === 'walls') {
      const a = pos(op, 'from', where), b = pos(op, 'to', where);
      const min = a.map((v, i) => Math.min(v, b[i]!)) as Pos, max = a.map((v, i) => Math.max(v, b[i]!)) as Pos;
      limit((max[0] - min[0] + 1) * (max[1] - min[1] + 1) * (max[2] - min[2] + 1));
      for (let y = min[1]; y <= max[1]; y++) {
        for (let z = min[2]; z <= max[2]; z++) {
          for (let x = min[0]; x <= max[0]; x++) {
            const sideX = x === min[0] || x === max[0], sideZ = z === min[2] || z === max[2], sideY = y === min[1] || y === max[1];
            if (shape === 'box' || sideX || sideZ || (shape === 'hollow_box' && sideY)) put(x, y, z);
          }
        }
      }
    } else if (shape === 'sphere') {
      const c = pos(op, 'center', where), r = nonNegative(op, 'radius', where);
      limit((2 * r + 1) ** 3);
      const within = (r + 0.5) * (r + 0.5);
      for (let y = -r; y <= r; y++) for (let z = -r; z <= r; z++) for (let x = -r; x <= r; x++) if (x * x + y * y + z * z <= within) put(c[0] + x, c[1] + y, c[2] + z);
    } else if (shape === 'cylinder') {
      const base = pos(op, 'base', where), r = nonNegative(op, 'radius', where);
      const height = intField(op, 'height', where);
      if (height < 1) throw new TextFormatError(`${where}: "height" must be at least 1.`);
      limit((2 * r + 1) ** 2 * height);
      const within = (r + 0.5) * (r + 0.5);
      for (let y = 0; y < height; y++) for (let z = -r; z <= r; z++) for (let x = -r; x <= r; x++) if (x * x + z * z <= within) put(base[0] + x, base[1] + y, base[2] + z);
    } else {
      throw new TextFormatError(`${where}: unknown shape "${shape}". Use single, line, box, hollow_box, walls, sphere or cylinder.`);
    }
    this.checkTotal(where);
  }

  private readLayers(layers: Json): void {
    const origin: Pos = 'origin' in layers ? pos(layers, 'origin', 'layers') : [0, 0, 0];
    array(layers.grid, 'layers.grid').forEach((rows, layer) => {
      const layerWhere = `layers.grid[${layer}]`;
      array(rows, layerWhere).forEach((row, z) => {
        const rowWhere = `${layerWhere}[${z}]`;
        // One character per block, counted as whole characters (an emoji key is one).
        [...string(row, rowWhere)].forEach((char, column) => {
          if (char === '.' || char === ' ') return;
          const block = this.palette.get(char);
          if (block === undefined) throw new TextFormatError(`${rowWhere} column ${column}: character "${char}" is not in the palette.`);
          this.cells.set(`${origin[0] + column},${origin[1] + layer},${origin[2] + z}`, block);
        });
      });
      this.checkTotal(layerWhere);
    });
  }

  private checkTotal(where: string): void {
    if (this.cells.size > MAX_POSITIONS) throw new TextFormatError(`After ${where} the blueprint has more than ${MAX_POSITIONS} blocks.`);
  }

  private build(meta: PawprintMeta): Blueprint {
    const points: { at: Pos; block: string }[] = [];
    const min: Pos = [Infinity, Infinity, Infinity];
    for (const [key, block] of this.cells) {
      const at = key.split(',').map(Number) as Pos;
      points.push({ at, block });
      for (let i = 0; i < 3; i++) min[i] = Math.min(min[i]!, at[i]!);
    }
    const palette: string[] = [];
    const ids = new Map<string, number>();
    const positions: number[] = [], states: number[] = [], removals: number[] = [];
    for (const { at, block } of points) {
      const p = [at[0] - min[0], at[1] - min[1], at[2] - min[2]];
      if (block === AIR) {
        removals.push(...p);
        continue;
      }
      let id = ids.get(block);
      if (id === undefined) {
        id = palette.length;
        palette.push(block);
        ids.set(block, id);
      }
      positions.push(...p);
      states.push(id);
    }
    const flat = { positions: new Int32Array(positions), removals: new Int32Array(removals) };
    return {
      meta: { ...meta, size: boundsSize(flat.positions, flat.removals), blockCount: states.length, removalCount: removals.length / 3 },
      palette,
      positions: flat.positions,
      states: new Int32Array(states),
      blockLayers: new Int32Array(states.length),
      removals: flat.removals,
      removalLayers: new Int32Array(removals.length / 3),
      layers: [{ ...DEFAULT_LAYER }],
      layerOrder: [0],
      thumbnail: null,
    };
  }

  /** A "block" field holds a palette key or a block string. */
  private blockField(op: Json, where: string): string {
    const value = stringField(op, 'block', where);
    if (value === AIR || value.includes(':')) return this.block(value, where);
    if ([...value].length === 1) {
      const block = this.palette.get(value);
      if (block !== undefined) return block;
    }
    throw new TextFormatError(`${where}: "block" is "${value}", which is neither a palette key nor a block ID like "minecraft:stone".`);
  }

  /** Checks a block string and returns it with its namespace; a block the pack does not have is kept and reported. */
  private block(value: string, where: string): string {
    const trimmed = value.trim();
    if (trimmed.toLowerCase() === AIR || trimmed === 'minecraft:air') return AIR;
    const cached = this.resolved.get(trimmed);
    if (cached !== undefined) return cached;
    if (!/^([a-z0-9_.-]+:)?[a-z0-9_./-]+(\[[^\[\]]*\])?$/.test(trimmed)) throw new TextFormatError(`${where}: "${trimmed}" is not a valid block ID.`);
    const { id, props } = parseState(trimmed);
    const keys = Object.keys(props);
    const result = keys.length ? `${id}[${keys.map((k) => `${k}=${props[k]}`).join(',')}]` : id;
    const def = this.known?.blocks.get(id);
    if (this.known && !def) this.unknown.add(trimmed);
    if (def && this.known?.complete) {
      // As the game would: a property the block does not have, or a value it cannot take, is an error.
      for (const key of keys) {
        const values = def.properties[key];
        if (!values) throw new TextFormatError(`${where}: invalid state for ${id} in "${trimmed}": the block has no property "${key}".`);
        if (!values.includes(props[key]!)) {
          throw new TextFormatError(`${where}: invalid state for ${id} in "${trimmed}": "${key}" cannot be "${props[key]}" (use ${values.join(', ')}).`);
        }
      }
    }
    this.resolved.set(trimmed, result);
    return result;
  }
}

/** Java's Math.round: halves go up, also for negative numbers. */
const javaRound = (value: number): number => Math.floor(value + 0.5);

function object(value: unknown, where: string): Json {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new TextFormatError(`${where} must be an object.`);
  return value as Json;
}

function array(value: unknown, where: string): unknown[] {
  if (!Array.isArray(value)) throw new TextFormatError(`${where} must be an array.`);
  return value;
}

function string(value: unknown, where: string): string {
  if (typeof value !== 'string') throw new TextFormatError(`${where} must be a string.`);
  return value;
}

function stringField(parent: Json, field: string, where: string): string {
  return string(parent[field], `${where}: "${field}"`);
}

function intField(parent: Json, field: string, where: string): number {
  const value = parent[field];
  if (typeof value !== 'number') throw new TextFormatError(`${where}: "${field}" must be an integer.`);
  if (!Number.isInteger(value) || Math.abs(value) > 30_000_000) throw new TextFormatError(`${where}: "${field}" must be an integer, got ${value}.`);
  return value;
}

function nonNegative(parent: Json, field: string, where: string): number {
  const value = intField(parent, field, where);
  if (value < 0) throw new TextFormatError(`${where}: "${field}" must not be negative.`);
  return value;
}

function pos(parent: Json, field: string, where: string): Pos {
  const value = parent[field];
  const label = `${where}: "${field}"`;
  if (!Array.isArray(value) || value.length !== 3 || !value.every((v) => typeof v === 'number' && Number.isInteger(v))) {
    throw new TextFormatError(`${label} must be an array of three integers [x, y, z].`);
  }
  const [x, y, z] = value as Pos;
  if (Math.abs(x) > MAX_COORDINATE || Math.abs(y) > MAX_Y || Math.abs(z) > MAX_COORDINATE) {
    throw new TextFormatError(`${label} is out of range (|x|, |z| <= ${MAX_COORDINATE}, |y| <= ${MAX_Y}).`);
  }
  return [x, y, z];
}
