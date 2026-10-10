import { FileFormatError, jsonBytes, readJson, readZip, writeZip } from '../zip';
import { nbt, readNbt, Tag, writeNbt, type NbtCompound, type NbtWrite } from './nbt';

/** `.pawprint` files. See docs/FORMAT_PAWPRINT.md. */

export const PAWPRINT_FORMAT = 2;

export interface Layer {
  id: number;
  name: string;
  color: string;
  visible: boolean;
  locked: boolean;
  parent: number | null;
  group?: boolean;
  collapsed?: boolean;
  children?: number[];
}

export interface PawprintMeta {
  format: number;
  id: string;
  name: string;
  description: string;
  author: string;
  tags: string[];
  created: string;
  modified: string;
  mcVersion: string;
  dataVersion: number;
  size: [number, number, number];
  blockCount: number;
  removalCount: number;
  mods: string[];
  blocks: string[];
  origin?: { server: string; dimension: string; pos: [number, number, number] } | null;
  layers?: Layer[];
  layerOrder?: number[];
  packHint?: { id: string; name: string } | null;
  /** Fields this version does not know, kept so a save does not drop them. */
  [extra: string]: unknown;
}

/** Block data in flat arrays: positions are x, y, z triples relative to the minimum corner. */
export interface Blueprint {
  meta: PawprintMeta;
  palette: string[];
  positions: Int32Array;
  states: Int32Array;
  blockLayers: Int32Array;
  removals: Int32Array;
  removalLayers: Int32Array;
  layers: Layer[];
  layerOrder: number[];
  thumbnail: Uint8Array | null;
}

const LIMITS = { maxEntries: 64, maxEntryBytes: 512 * 1024 * 1024, maxTotalBytes: 600 * 1024 * 1024 };
const MAX_BLOCKS = 16_000_000;
const MAX_PALETTE = 1 << 20;

export const DEFAULT_LAYER: Layer = { id: 0, name: 'Default', color: '#7FB3FF', visible: true, locked: false, parent: null };

// BlockPos.asLong: x 26 bits at 38, z 26 bits at 12, y 12 bits at 0.
export function packPos(x: number, y: number, z: number): bigint {
  return BigInt.asIntN(64, (BigInt(x & 0x3ffffff) << 38n) | (BigInt(z & 0x3ffffff) << 12n) | BigInt(y & 0xfff));
}

export function unpackPositions(packed: BigInt64Array): Int32Array {
  const out = new Int32Array(packed.length * 3);
  for (let i = 0; i < packed.length; i++) {
    const p = packed[i]!;
    out[i * 3] = Number(p >> 38n);
    out[i * 3 + 1] = Number(BigInt.asIntN(12, p));
    out[i * 3 + 2] = Number(BigInt.asIntN(26, p >> 12n));
  }
  return out;
}

function packPositions(xyz: Int32Array): BigInt64Array {
  const out = new BigInt64Array(xyz.length / 3);
  for (let i = 0; i < out.length; i++) out[i] = packPos(xyz[i * 3]!, xyz[i * 3 + 1]!, xyz[i * 3 + 2]!);
  return out;
}

export function readPawprint(bytes: Uint8Array): Blueprint {
  const files = readZip(bytes, LIMITS, (name) => name === 'meta.json' || name === 'blueprint.nbt' || name === 'thumbnail.png');
  const rawMeta = readJson<Record<string, unknown>>(files, 'meta.json', 'error.pawprint.missingEntry');
  if (!rawMeta || typeof rawMeta !== 'object') throw new FileFormatError('error.pawprint.badMeta');
  const format = typeof rawMeta.format === 'number' ? rawMeta.format : 1;
  if (format > PAWPRINT_FORMAT) throw new FileFormatError('error.pawprint.tooNew', { format });
  const data = files.get('blueprint.nbt');
  if (!data) throw new FileFormatError('error.pawprint.missingEntry', { name: 'blueprint.nbt' });
  return fromNbt(normalizeMeta(rawMeta, format), readNbt(data), files.get('thumbnail.png') ?? null);
}

/** A blueprint from its NBT (`blueprint.nbt`, or a share string's payload) and the metadata that goes with it. */
export function fromNbt(meta: PawprintMeta, root: NbtCompound, thumbnail: Uint8Array | null): Blueprint {
  const paletteTag = root.Palette;
  const palette = Array.isArray(paletteTag) ? paletteTag.map(String) : [];
  if (palette.length > MAX_PALETTE) throw new FileFormatError('error.pawprint.tooLarge');
  const packed = root.Positions instanceof BigInt64Array ? root.Positions : new BigInt64Array(0);
  const states = root.States instanceof Int32Array ? root.States : new Int32Array(0);
  const removalsPacked = root.Removals instanceof BigInt64Array ? root.Removals : new BigInt64Array(0);
  if (packed.length !== states.length) throw new FileFormatError('error.pawprint.badData');
  if (packed.length + removalsPacked.length > MAX_BLOCKS) throw new FileFormatError('error.pawprint.tooLarge');
  for (const s of states) if (s < 0 || s >= palette.length) throw new FileFormatError('error.pawprint.badData');

  const layers = meta.format >= 2 && Array.isArray(meta.layers) && meta.layers.length ? sanitizeLayers(meta.layers) : [{ ...DEFAULT_LAYER }];
  const known = new Set(layers.filter((l) => !l.group).map((l) => l.id));
  const layerArray = (tag: NbtCompound[string] | undefined, length: number): Int32Array => {
    const out = new Int32Array(length);
    if (meta.format >= 2 && tag instanceof Int32Array && tag.length === length) {
      for (let i = 0; i < length; i++) out[i] = known.has(tag[i]!) ? tag[i]! : 0;
    }
    return out;
  };

  const size = root.Size instanceof Int32Array && root.Size.length === 3 ? ([root.Size[0]!, root.Size[1]!, root.Size[2]!] as [number, number, number]) : meta.size;
  return {
    meta: { ...meta, size, blockCount: states.length, removalCount: removalsPacked.length },
    palette,
    positions: unpackPositions(packed),
    states,
    blockLayers: layerArray(root.BlockLayers, states.length),
    removals: unpackPositions(removalsPacked),
    removalLayers: layerArray(root.RemovalLayers, removalsPacked.length),
    layers,
    layerOrder: fixOrder(meta.layerOrder, layers),
    thumbnail,
  };
}

function normalizeMeta(raw: Record<string, unknown>, format: number): PawprintMeta {
  const str = (v: unknown) => (typeof v === 'string' ? v : '');
  const strings = (v: unknown) => (Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : []);
  const size = Array.isArray(raw.size) && raw.size.length === 3 ? (raw.size.map(Number) as [number, number, number]) : ([0, 0, 0] as [number, number, number]);
  return {
    ...raw,
    format,
    id: str(raw.id),
    name: str(raw.name),
    description: str(raw.description),
    author: str(raw.author),
    tags: strings(raw.tags),
    created: str(raw.created),
    modified: str(raw.modified),
    mcVersion: str(raw.mcVersion),
    dataVersion: typeof raw.dataVersion === 'number' ? raw.dataVersion : 0,
    size,
    blockCount: 0,
    removalCount: 0,
    mods: strings(raw.mods),
    blocks: strings(raw.blocks),
  };
}

function sanitizeLayers(raw: unknown[]): Layer[] {
  const layers: Layer[] = [];
  const seen = new Set<number>();
  for (const item of raw) {
    const l = item as Partial<Layer>;
    if (typeof l?.id !== 'number' || l.id < 0 || seen.has(l.id)) continue;
    seen.add(l.id);
    layers.push({
      id: l.id,
      name: typeof l.name === 'string' ? l.name : 'Layer ' + l.id,
      color: typeof l.color === 'string' && /^#[0-9a-fA-F]{6}$/.test(l.color) ? l.color : '#7FB3FF',
      visible: l.visible !== false,
      locked: l.locked === true,
      parent: typeof l.parent === 'number' ? l.parent : null,
      ...(l.group ? { group: true, collapsed: l.collapsed === true, children: Array.isArray(l.children) ? l.children.filter((c) => typeof c === 'number') : [] } : {}),
    });
  }
  const zero = layers.find((l) => l.id === 0);
  if (!zero) layers.unshift({ ...DEFAULT_LAYER });
  else if (zero.group) Object.assign(zero, { group: undefined, children: undefined, collapsed: undefined });
  const groups = new Set(layers.filter((l) => l.group).map((l) => l.id));
  // A parent must be an existing group, and following parents must not loop.
  for (const layer of layers) {
    if (layer.parent !== null && !groups.has(layer.parent)) layer.parent = null;
    const visited = new Set<number>([layer.id]);
    let p = layer.parent;
    while (p !== null) {
      if (visited.has(p)) {
        layer.parent = null;
        break;
      }
      visited.add(p);
      p = layers.find((l) => l.id === p)?.parent ?? null;
    }
  }
  return layers;
}

function fixOrder(order: unknown, layers: Layer[]): number[] {
  const top = layers.filter((l) => l.parent === null).map((l) => l.id);
  const given = Array.isArray(order) ? order.filter((id): id is number => top.includes(id as number)) : [];
  return [...new Set([...given, ...top])];
}

/** Whether a blueprint uses layers (more than the default one), which makes it format 2. */
export function isLayered(bp: Blueprint): boolean {
  return bp.layers.length > 1 || bp.blockLayers.some((l) => l !== 0) || bp.removalLayers.some((l) => l !== 0);
}

/** The block data as NBT: what `blueprint.nbt` holds, and the base of a share string. */
export function toNbt(bp: Blueprint): { [key: string]: NbtWrite } {
  const root: { [key: string]: NbtWrite } = {
    DataVersion: nbt.int(bp.meta.dataVersion),
    Size: new Int32Array(boundsSize(bp.positions, bp.removals)),
    Palette: nbt.list(Tag.String, bp.palette),
    Positions: packPositions(bp.positions),
    States: bp.states,
    Removals: packPositions(bp.removals),
  };
  if (isLayered(bp)) {
    root.BlockLayers = bp.blockLayers;
    root.RemovalLayers = bp.removalLayers;
  }
  return root;
}

/** Writes a `.pawprint`. With only the default layer it writes format 1 so older mods can open it. */
export function writePawprint(bp: Blueprint): Uint8Array {
  const layered = isLayered(bp);
  const format = layered ? 2 : 1;
  const size = boundsSize(bp.positions, bp.removals);
  const blockIds = new Set<string>();
  const mods = new Set<string>();
  for (const entry of bp.palette) {
    const id = entry.split('[')[0]!;
    const full = id.includes(':') ? id : 'minecraft:' + id;
    blockIds.add(full);
    mods.add(full.split(':')[0]!);
  }
  const meta: PawprintMeta = {
    ...bp.meta,
    format,
    size,
    blockCount: bp.states.length,
    removalCount: bp.removals.length / 3,
    mods: [...mods].sort(),
    blocks: [...blockIds].sort(),
  };
  delete meta.layers;
  delete meta.layerOrder;
  if (layered) {
    meta.layers = bp.layers;
    meta.layerOrder = bp.layerOrder;
  }
  const files = new Map<string, Uint8Array>([
    ['meta.json', jsonBytes(meta)],
    ['blueprint.nbt', writeNbt(toNbt(bp))],
  ]);
  if (bp.thumbnail) files.set('thumbnail.png', bp.thumbnail);
  return writeZip(files);
}

export function boundsSize(...lists: Int32Array[]): [number, number, number] {
  const max = [-1, -1, -1];
  for (const xyz of lists) {
    for (let i = 0; i < xyz.length; i++) max[i % 3] = Math.max(max[i % 3]!, xyz[i]!);
  }
  return [max[0]! + 1, max[1]! + 1, max[2]! + 1];
}
