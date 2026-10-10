import { parseState } from '../model/state';
import { FileFormatError } from '../zip';
import { nbt, readNbt, Tag, writeNbt, type NbtCompound, type NbtValue, type NbtWrite } from './nbt';
import { boundsSize, DEFAULT_LAYER, type Blueprint, type PawprintMeta } from './pawprint';

/**
 * Other mods' blueprint formats: Litematica (`.litematic`), Sponge schematics (`.schem`, WorldEdit) and vanilla
 * structures (`.nbt`, structure blocks and Create). Same rules as the mod's converters (`format/convert` in the mod):
 * air only fills the box in these formats, so it is skipped when reading; layers are lost when writing. Unlike the
 * mod, the web cannot upgrade block states saved by an older game version: they are kept as written.
 */

export type ExternalFormat = 'litematic' | 'schem' | 'nbt';

export const EXTERNAL_FORMATS: { id: ExternalFormat; extension: string; name: string }[] = [
  { id: 'litematic', extension: '.litematic', name: 'Litematica' },
  { id: 'schem', extension: '.schem', name: 'WorldEdit' },
  { id: 'nbt', extension: '.nbt', name: 'Structure' },
];

const MAX_BLOCKS = 16_000_000;
/** Cells of a box these formats store one by one. */
const MAX_VOLUME = 1 << 28;
const AIR = new Set(['minecraft:air', 'minecraft:cave_air', 'minecraft:void_air', 'minecraft:structure_void']);

export function formatOfFile(fileName: string): ExternalFormat | null {
  const lower = fileName.toLowerCase();
  return EXTERNAL_FORMATS.find((f) => lower.endsWith(f.extension))?.id ?? null;
}

/** Reads a file of another format into a new blueprint. `meta` gives the new blueprint its id, times and fallback name. */
export function readExternal(format: ExternalFormat, bytes: Uint8Array, meta: PawprintMeta): Blueprint {
  const root = readNbt(bytes);
  try {
    if (format === 'litematic') return readLitematic(root, meta);
    if (format === 'schem') return readSchem(root, meta);
    return readStructure(root, meta);
  } catch (e) {
    if (e instanceof FileFormatError) throw e;
    throw new FileFormatError('error.convert.invalid', { format: '.' + format });
  }
}

export function writeExternal(format: ExternalFormat, bp: Blueprint): Uint8Array {
  if (format === 'litematic') return writeNbt(writeLitematic(bp));
  if (format === 'schem') return writeNbt(writeSchem(bp));
  return writeNbt(writeStructure(bp));
}

// ---------------------------------------------------------------------------------------------------------------

/** `{Name: "minecraft:oak_stairs", Properties: {facing: "east"}}` → `minecraft:oak_stairs[facing=east]` (properties sorted). */
function stateOfTag(tag: NbtValue | undefined): string {
  const t = compound(tag);
  let name = typeof t.Name === 'string' ? t.Name : 'minecraft:air';
  if (!name.includes(':')) name = 'minecraft:' + name;
  return withProps(name, Object.entries(compound(t.Properties)).map(([k, v]) => [k, String(v)]));
}

function tagOfState(state: string): { [key: string]: NbtWrite } {
  const { id, props } = parseState(state);
  const keys = Object.keys(props).sort();
  return keys.length ? { Name: id, Properties: Object.fromEntries(keys.map((k) => [k, props[k]!])) } : { Name: id };
}

/** A state string with its namespace and its properties in alphabetical order. */
function normalized(state: string): string {
  const { id, props } = parseState(state);
  return withProps(id, Object.entries(props));
}

function withProps(id: string, props: [string, string][]): string {
  if (!props.length) return id;
  return id + '[' + props.sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0)).map(([k, v]) => `${k}=${v}`).join(',') + ']';
}

const isAir = (state: string): boolean => AIR.has(state.split('[')[0]!);
const compound = (v: NbtValue | undefined): NbtCompound => (v && typeof v === 'object' && !Array.isArray(v) && !ArrayBuffer.isView(v) ? (v as NbtCompound) : {});
const int = (v: NbtValue | undefined): number => (typeof v === 'number' ? v : typeof v === 'bigint' ? Number(v) : 0);
const text = (v: NbtValue | undefined): string => (typeof v === 'string' ? v : '');

/** Collects blocks at any coordinates (a later block replaces an earlier one), then shifts them to start at 0, 0, 0. */
class Collector {
  private readonly palette: string[] = [];
  private readonly ids = new Map<string, number>();
  private xs: number[] = [];
  private ys: number[] = [];
  private zs: number[] = [];
  private states: number[] = [];

  state(text: string): number {
    let id = this.ids.get(text);
    if (id === undefined) {
      id = this.palette.length;
      this.palette.push(text);
      this.ids.set(text, id);
    }
    return id;
  }

  put(x: number, y: number, z: number, state: number): void {
    if (this.states.length >= MAX_BLOCKS) throw new FileFormatError('error.pawprint.tooLarge');
    this.xs.push(x);
    this.ys.push(y);
    this.zs.push(z);
    this.states.push(state);
  }

  build(meta: PawprintMeta, overlapping: boolean): Blueprint {
    const n = this.states.length;
    if (n === 0) throw new FileFormatError('error.convert.empty');
    let minX = Infinity, minY = Infinity, minZ = Infinity, maxY = -Infinity, maxZ = -Infinity;
    for (let i = 0; i < n; i++) {
      minX = Math.min(minX, this.xs[i]!);
      minY = Math.min(minY, this.ys[i]!);
      minZ = Math.min(minZ, this.zs[i]!);
      maxY = Math.max(maxY, this.ys[i]!);
      maxZ = Math.max(maxZ, this.zs[i]!);
    }
    // Regions (Litematica) or repeated entries may name a cell twice: the last one stays.
    let keep: number[] | null = null;
    if (overlapping) {
      const sy = maxY - minY + 1, sz = maxZ - minZ + 1;
      const last = new Map<number, number>();
      for (let i = 0; i < n; i++) last.set(((this.xs[i]! - minX) * sy + (this.ys[i]! - minY)) * sz + (this.zs[i]! - minZ), i);
      if (last.size < n) keep = [...last.values()].sort((a, b) => a - b);
    }
    const count = keep ? keep.length : n;
    const positions = new Int32Array(count * 3);
    const states = new Int32Array(count);
    for (let k = 0; k < count; k++) {
      const i = keep ? keep[k]! : k;
      positions[k * 3] = this.xs[i]! - minX;
      positions[k * 3 + 1] = this.ys[i]! - minY;
      positions[k * 3 + 2] = this.zs[i]! - minZ;
      states[k] = this.states[i]!;
    }
    // Only the states that are used, in first-use order.
    const remap = new Map<number, number>();
    const palette: string[] = [];
    for (let k = 0; k < count; k++) {
      let id = remap.get(states[k]!);
      if (id === undefined) {
        id = palette.length;
        palette.push(this.palette[states[k]!]!);
        remap.set(states[k]!, id);
      }
      states[k] = id;
    }
    return {
      meta: { ...meta, format: 1, size: boundsSize(positions), blockCount: count, removalCount: 0 },
      palette,
      positions,
      states,
      blockLayers: new Int32Array(count),
      removals: new Int32Array(0),
      removalLayers: new Int32Array(0),
      layers: [{ ...DEFAULT_LAYER }],
      layerOrder: [0],
      thumbnail: null,
    };
  }
}

/** For writers: state per cell of the blueprint's box, as an index into `palette` (0 = air; removals are air too). */
function grid(bp: Blueprint, keyOf: (state: string) => string): { size: [number, number, number]; cells: Int32Array; palette: string[] } {
  const size = boundsSize(bp.positions, bp.removals);
  const volume = size[0] * size[1] * size[2];
  if (volume > MAX_VOLUME) throw new FileFormatError('error.convert.tooLarge');
  const palette = ['minecraft:air'];
  const ids = new Map<string, number>([['minecraft:air', 0]]);
  const map = bp.palette.map((state) => {
    const key = keyOf(state);
    let id = ids.get(key);
    if (id === undefined) {
      id = palette.length;
      palette.push(key);
      ids.set(key, id);
    }
    return id;
  });
  const cells = new Int32Array(volume);
  for (let i = 0; i < bp.states.length; i++) {
    cells[(bp.positions[i * 3 + 1]! * size[2] + bp.positions[i * 3 + 2]!) * size[0] + bp.positions[i * 3]!] = map[bp.states[i]!]!;
  }
  return { size, cells, palette };
}

// --- Litematica ------------------------------------------------------------------------------------------------

/** Litematica packs entries tightly: one may start in one long and end in the next. */
const bitsFor = (paletteSize: number): number => Math.max(2, 32 - Math.clz32(Math.max(1, paletteSize - 1)));

/** Longs as 32-bit halves (low half first), which JavaScript can shift without BigInt. */
function halves(longs: BigInt64Array): Uint32Array {
  const out = new Uint32Array(longs.length * 2);
  for (let i = 0; i < longs.length; i++) {
    out[i * 2] = Number(longs[i]! & 0xffffffffn);
    out[i * 2 + 1] = Number((longs[i]! >> 32n) & 0xffffffffn);
  }
  return out;
}

function getBits(words: Uint32Array, index: number, bits: number): number {
  const start = index * bits;
  const word = Math.floor(start / 32), offset = start % 32;
  let value = words[word]! >>> offset;
  if (offset + bits > 32) value |= words[word + 1]! << (32 - offset);
  return bits === 32 ? value >>> 0 : (value & ((1 << bits) - 1)) >>> 0;
}

function setBits(words: Uint32Array, index: number, bits: number, value: number): void {
  const start = index * bits;
  const word = Math.floor(start / 32), offset = start % 32;
  words[word] = (words[word]! | (value << offset)) >>> 0;
  if (offset + bits > 32) words[word + 1] = (words[word + 1]! | (value >>> (32 - offset))) >>> 0;
}

function readLitematic(root: NbtCompound, meta: PawprintMeta): Blueprint {
  const regions = compound(root.Regions);
  const names = Object.keys(regions);
  if (!names.length) throw new FileFormatError('error.convert.invalid', { format: '.litematic' });
  const collector = new Collector();
  for (const regionName of names) {
    const region = compound(regions[regionName]);
    const position = compound(region.Position), size = compound(region.Size);
    let sx = int(size.x), sy = int(size.y), sz = int(size.z);
    // Negative sizes extend from the position toward smaller coordinates.
    const minX = int(position.x) + (sx < 0 ? sx + 1 : 0);
    const minY = int(position.y) + (sy < 0 ? sy + 1 : 0);
    const minZ = int(position.z) + (sz < 0 ? sz + 1 : 0);
    sx = Math.abs(sx);
    sy = Math.abs(sy);
    sz = Math.abs(sz);
    const volume = sx * sy * sz;
    if (volume > MAX_VOLUME) throw new FileFormatError('error.pawprint.tooLarge');
    const paletteTag = Array.isArray(region.BlockStatePalette) ? region.BlockStatePalette : [];
    const palette = paletteTag.map((tag) => {
      const state = stateOfTag(tag);
      return isAir(state) ? -1 : collector.state(state);
    });
    const bits = bitsFor(palette.length);
    const longs = region.BlockStates instanceof BigInt64Array ? region.BlockStates : new BigInt64Array(0);
    if (longs.length < Math.ceil((volume * bits) / 64)) throw new FileFormatError('error.convert.invalid', { format: '.litematic' });
    const words = halves(longs);
    let index = 0;
    for (let y = 0; y < sy; y++) {
      for (let z = 0; z < sz; z++) {
        for (let x = 0; x < sx; x++, index++) {
          const id = getBits(words, index, bits);
          const state = id > 0 && id < palette.length ? palette[id]! : -1;
          if (state >= 0) collector.put(minX + x, minY + y, minZ + z, state);
        }
      }
    }
  }
  const metadata = compound(root.Metadata);
  return collector.build({
    ...meta,
    name: text(metadata.Name) || meta.name,
    author: text(metadata.Author) || meta.author,
    description: text(metadata.Description),
    dataVersion: int(root.MinecraftDataVersion),
  }, names.length > 1);
}

function writeLitematic(bp: Blueprint): { [key: string]: NbtWrite } {
  const { size, cells, palette } = grid(bp, normalized);
  const bits = bitsFor(palette.length);
  const words = new Uint32Array(Math.ceil((cells.length * bits) / 64) * 2);
  for (let i = 0; i < cells.length; i++) if (cells[i] !== 0) setBits(words, i, bits, cells[i]!);
  const longs = new BigInt64Array(words.length / 2);
  for (let i = 0; i < longs.length; i++) longs[i] = BigInt.asIntN(64, (BigInt(words[i * 2 + 1]!) << 32n) | BigInt(words[i * 2]!));
  const xyz = (x: number, y: number, z: number): NbtWrite => ({ x: nbt.int(x), y: nbt.int(y), z: nbt.int(z) });
  const long = (value: number): NbtWrite => ({ tag: Tag.Long, value: BigInt(value) });
  const now = Date.now();
  const empty = (): NbtWrite => nbt.list(Tag.Compound, []);
  return {
    Version: nbt.int(6),
    MinecraftDataVersion: nbt.int(bp.meta.dataVersion),
    Metadata: {
      Name: bp.meta.name,
      Author: bp.meta.author,
      Description: bp.meta.description,
      RegionCount: nbt.int(1),
      TotalVolume: long(cells.length),
      TotalBlocks: long(bp.states.length),
      TimeCreated: long(now),
      TimeModified: long(now),
      EnclosingSize: xyz(...size),
    },
    Regions: {
      [bp.meta.name || 'Main']: {
        Position: xyz(0, 0, 0),
        Size: xyz(...size),
        BlockStatePalette: nbt.list(Tag.Compound, palette.map(tagOfState)),
        BlockStates: longs,
        TileEntities: empty(),
        Entities: empty(),
        PendingBlockTicks: empty(),
        PendingFluidTicks: empty(),
      },
    },
  };
}

// --- Sponge schematic (WorldEdit) ------------------------------------------------------------------------------

/** Reads versions 2 and 3. */
function readSchem(root: NbtCompound, meta: PawprintMeta): Blueprint {
  const schematic = 'Schematic' in root ? compound(root.Schematic) : root;
  const version = int(schematic.Version);
  const width = int(schematic.Width) & 0xffff, height = int(schematic.Height) & 0xffff, length = int(schematic.Length) & 0xffff;
  const blocks = compound(schematic.Blocks);
  const paletteTag = compound(version >= 3 ? blocks.Palette : schematic.Palette);
  const dataTag = version >= 3 ? blocks.Data : schematic.BlockData;
  const data = dataTag instanceof Int8Array ? dataTag : new Int8Array(0);
  const collector = new Collector();
  const palette = new Map<number, number>();
  for (const [key, id] of Object.entries(paletteTag)) {
    const state = normalized(key);
    palette.set(int(id), isAir(state) ? -1 : collector.state(state));
  }
  const volume = width * height * length;
  const layer = width * length;
  let index = 0;
  for (let i = 0; i < data.length && index < volume; index++) {
    // Each cell is a varint: 7 bits per byte, low bits first.
    let value = 0, shift = 0, b: number;
    do {
      if (i >= data.length) throw new FileFormatError('error.convert.invalid', { format: '.schem' });
      b = data[i++]!;
      value |= (b & 0x7f) << shift;
      shift += 7;
    } while (b & 0x80);
    const state = palette.get(value) ?? -1;
    if (state >= 0) collector.put(index % width, Math.floor(index / layer), Math.floor((index % layer) / width), state);
  }
  const metadata = compound(schematic.Metadata);
  return collector.build({ ...meta, name: text(metadata.Name) || meta.name, author: text(metadata.Author) || meta.author, dataVersion: int(schematic.DataVersion) }, false);
}

/** Writes version 2, which both old and new WorldEdit read. */
function writeSchem(bp: Blueprint): { [key: string]: NbtWrite } {
  const { size, cells, palette } = grid(bp, normalized);
  if (size.some((s) => s > 0xffff)) throw new FileFormatError('error.convert.tooLarge');
  const bytes: number[] = [];
  for (let value of cells) {
    while (value & ~0x7f) {
      bytes.push((value & 0x7f) | 0x80);
      value >>>= 7;
    }
    bytes.push(value);
  }
  const short = (value: number): NbtWrite => ({ tag: Tag.Short, value: (value << 16) >> 16 });
  return {
    Version: nbt.int(2),
    DataVersion: nbt.int(bp.meta.dataVersion),
    Width: short(size[0]),
    Height: short(size[1]),
    Length: short(size[2]),
    PaletteMax: nbt.int(palette.length),
    Palette: Object.fromEntries(palette.map((state, id) => [state, nbt.int(id)])),
    BlockData: new Int8Array(bytes),
    Offset: new Int32Array(3),
    Metadata: { Name: bp.meta.name, Author: bp.meta.author },
  };
}

// --- Vanilla structure -----------------------------------------------------------------------------------------

function readStructure(root: NbtCompound, meta: PawprintMeta): Blueprint {
  // `palettes` holds several looks of one structure (shipwrecks); the first is as good as any.
  const first = Array.isArray(root.palettes) ? root.palettes[0] : root.palette;
  const paletteTag = Array.isArray(first) ? first : [];
  if (!Array.isArray(root.blocks)) throw new FileFormatError('error.convert.invalid', { format: '.nbt' });
  const collector = new Collector();
  const palette = paletteTag.map((tag) => {
    const state = stateOfTag(tag);
    return isAir(state) ? -1 : collector.state(state);
  });
  for (const entry of root.blocks) {
    const block = compound(entry);
    const pos = block.pos;
    const at = pos instanceof Int32Array ? Array.from(pos) : Array.isArray(pos) ? pos.map(int) : [];
    const state = palette[int(block.state)] ?? -1;
    if (at.length === 3 && state >= 0) collector.put(at[0]!, at[1]!, at[2]!, state);
  }
  return collector.build({ ...meta, dataVersion: int(root.DataVersion) }, true);
}

function writeStructure(bp: Blueprint): { [key: string]: NbtWrite } {
  const ints = (a: number, b: number, c: number): NbtWrite => nbt.list(Tag.Int, [nbt.int(a), nbt.int(b), nbt.int(c)]);
  const palette = bp.palette.map(tagOfState);
  const air = palette.length;
  if (bp.removals.length) palette.push({ Name: 'minecraft:air' });
  const blocks: NbtWrite[] = [];
  for (let i = 0; i < bp.states.length; i++) {
    blocks.push({ pos: ints(bp.positions[i * 3]!, bp.positions[i * 3 + 1]!, bp.positions[i * 3 + 2]!), state: nbt.int(bp.states[i]!) });
  }
  for (let i = 0; i < bp.removals.length; i += 3) blocks.push({ pos: ints(bp.removals[i]!, bp.removals[i + 1]!, bp.removals[i + 2]!), state: nbt.int(air) });
  return {
    DataVersion: nbt.int(bp.meta.dataVersion),
    size: ints(...boundsSize(bp.positions, bp.removals)),
    palette: nbt.list(Tag.Compound, palette),
    blocks: nbt.list(Tag.Compound, blocks),
    entities: nbt.list(Tag.Compound, []),
  };
}
