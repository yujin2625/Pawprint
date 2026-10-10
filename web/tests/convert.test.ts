import { describe, expect, it } from 'vitest';
import { formatOfFile, readExternal, writeExternal, type ExternalFormat } from '../src/core/format/convert';
import { nbt, readNbt, Tag, writeNbt, type NbtCompound, type NbtWrite } from '../src/core/format/nbt';
import { DEFAULT_LAYER, type Blueprint, type PawprintMeta } from '../src/core/format/pawprint';
import { fromShareString, hasShareString, toShareString } from '../src/core/format/share';
import { FileFormatError } from '../src/core/zip';

const meta = (name = 'Fallback'): PawprintMeta => ({
  format: 1, id: 'new-id', name, description: '', author: 'me', tags: [], created: '2026-10-10T00:00:00Z', modified: '2026-10-10T00:00:00Z',
  mcVersion: '', dataVersion: 0, size: [0, 0, 0], blockCount: 0, removalCount: 0, mods: [], blocks: [],
});

/** 30 blocks in a 5×3×4 box with 6 states, so Litematica entries (3 bits) cross from one long into the next. */
function sample(): Blueprint {
  const palette = [
    'minecraft:stone', 'minecraft:oak_stairs[facing=east,half=bottom]', 'create:andesite_casing', 'minecraft:oak_log[axis=y]',
    'minecraft:glass', 'minecraft:oak_slab[type=top,waterlogged=false]',
  ];
  const positions: number[] = [], states: number[] = [];
  for (let i = 0; i < 30; i++) {
    positions.push(i % 5, Math.floor(i / 20), Math.floor(i / 5) % 4);
    states.push((i * 7) % palette.length);
  }
  positions.push(4, 2, 3);
  states.push(2);
  return {
    meta: { ...meta('Small cabin'), author: 'yujin', description: 'A test', dataVersion: 3955, tags: ['house'] },
    palette,
    positions: new Int32Array(positions),
    states: new Int32Array(states),
    blockLayers: new Int32Array(states.length),
    removals: new Int32Array(0),
    removalLayers: new Int32Array(0),
    layers: [{ ...DEFAULT_LAYER }],
    layerOrder: [0],
    thumbnail: null,
  };
}

/** Position → state, order-independent. */
function cells(bp: Blueprint): Record<string, string> {
  const out: Record<string, string> = {};
  for (let i = 0; i < bp.states.length; i++) out[[bp.positions[i * 3], bp.positions[i * 3 + 1], bp.positions[i * 3 + 2]].join(',')] = bp.palette[bp.states[i]!]!;
  return out;
}

describe('other blueprint formats', () => {
  it('knows them by extension', () => {
    expect(formatOfFile('House.LITEMATIC')).toBe('litematic');
    expect(formatOfFile('a.schem')).toBe('schem');
    expect(formatOfFile('a.nbt')).toBe('nbt');
    expect(formatOfFile('a.schematic')).toBeNull();
    expect(formatOfFile('a.pawprint')).toBeNull();
  });

  for (const format of ['litematic', 'schem', 'nbt'] as ExternalFormat[]) {
    it(`writes and reads .${format} with the same blocks`, () => {
      const original = sample();
      const back = readExternal(format, writeExternal(format, original), meta());
      expect(cells(back)).toEqual(cells(original));
      expect(back.meta.size).toEqual([5, 3, 4]);
      expect(back.meta.dataVersion).toBe(3955);
      expect(back.meta.id).toBe('new-id');
      // Structure files carry no name; the others do.
      expect(back.meta.name).toBe(format === 'nbt' ? 'Fallback' : 'Small cabin');
      expect(back.layers).toHaveLength(1);
    });
  }

  it('writes removals as air and leaves air out when reading', () => {
    const bp = { ...sample(), removals: new Int32Array([0, 2, 0]), removalLayers: new Int32Array(1) };
    const structure = readNbt(writeExternal('nbt', bp));
    const palette = structure.palette as NbtCompound[];
    expect(palette[palette.length - 1]).toEqual({ Name: 'minecraft:air' });
    expect((structure.blocks as NbtCompound[]).filter((b) => b.state === palette.length - 1)).toHaveLength(1);
    expect(cells(readExternal('nbt', writeExternal('nbt', bp), meta()))).toEqual(cells(bp));
  });

  it('packs Litematica block states like Litematica does', () => {
    // The mod's own bit layout (LitematicFormat.set), with BigInt.
    const original = sample();
    const root = readNbt(writeExternal('litematic', original));
    const region = (root.Regions as NbtCompound)['Small cabin'] as NbtCompound;
    const palette = (region.BlockStatePalette as NbtCompound[]).map((t) => t.Name);
    expect(palette[0]).toBe('minecraft:air');
    expect(palette).toHaveLength(7);
    const longs = region.BlockStates as BigInt64Array;
    const bits = 3n;
    expect(longs).toHaveLength(Math.ceil((5 * 3 * 4 * 3) / 64));
    const at = (index: number): number => {
      const start = BigInt(index) * bits;
      const word = Number(start >> 6n), offset = start & 63n;
      let value = BigInt.asUintN(64, longs[word]!) >> offset;
      if (offset + bits > 64n) value |= BigInt.asUintN(64, longs[word + 1]!) << (64n - offset);
      return Number(value & 7n);
    };
    // x=1, y=1, z=0 is block 21: state (21*7)%6 = 3 → oak_log, palette entry 4 (air is 0).
    expect(palette[at((1 * 4 + 0) * 5 + 1)]).toBe('minecraft:oak_log');
    expect(at((2 * 4 + 3) * 5 + 3)).toBe(0);
    expect((region.BlockStatePalette as NbtCompound[])[4]).toEqual({ Name: 'minecraft:oak_log', Properties: { axis: 'y' } });
  });

  it('merges Litematica regions, negative sizes included', () => {
    const xyz = (x: number, y: number, z: number): NbtWrite => ({ x: nbt.int(x), y: nbt.int(y), z: nbt.int(z) });
    const region = (position: NbtWrite, size: NbtWrite, block: string, cellsSet: bigint): NbtWrite => ({
      Position: position,
      Size: size,
      BlockStatePalette: nbt.list(Tag.Compound, [{ Name: 'minecraft:air' }, { Name: block }]),
      BlockStates: new BigInt64Array([cellsSet]),
    });
    const file = writeNbt({
      MinecraftDataVersion: nbt.int(3955),
      Metadata: { Name: 'Two parts', Author: 'someone' },
      Regions: {
        // 2×1×1 at x 10..11: both cells stone (2 bits each: 01 01).
        a: region(xyz(10, 64, 0), xyz(2, 1, 1), 'minecraft:stone', 0b0101n),
        // Size -2 from x=11: covers x 10..11 again; only its first cell (x=10) is dirt.
        b: region(xyz(11, 64, 0), xyz(-2, 1, 1), 'dirt', 0b0001n),
      },
    });
    const bp = readExternal('litematic', file, meta());
    expect(cells(bp)).toEqual({ '0,0,0': 'minecraft:dirt', '1,0,0': 'minecraft:stone' });
    expect(bp.meta.name).toBe('Two parts');
    expect(bp.meta.author).toBe('someone');
  });

  it('reads Sponge schematics version 3', () => {
    const file = writeNbt({
      Schematic: {
        Version: nbt.int(3),
        DataVersion: nbt.int(3955),
        Width: { tag: Tag.Short, value: 2 },
        Height: { tag: Tag.Short, value: 1 },
        Length: { tag: Tag.Short, value: 2 },
        Blocks: {
          Palette: { 'minecraft:air': nbt.int(0), 'minecraft:oak_stairs[half=bottom,facing=east]': nbt.int(1) },
          Data: new Int8Array([0, 1, 1, 0]),
        },
      },
    });
    const bp = readExternal('schem', file, meta('From file name'));
    // Properties come back in alphabetical order whatever order the file used.
    expect(cells(bp)).toEqual({ '1,0,0': 'minecraft:oak_stairs[facing=east,half=bottom]', '0,0,1': 'minecraft:oak_stairs[facing=east,half=bottom]' });
    expect(bp.meta.name).toBe('From file name');
  });

  it('reads structures with several palettes and skips bad entries', () => {
    const ints = (...v: number[]): NbtWrite => nbt.list(Tag.Int, v.map((n) => nbt.int(n)));
    const file = writeNbt({
      DataVersion: nbt.int(3955),
      palettes: nbt.list(Tag.List, [nbt.list(Tag.Compound, [{ Name: 'minecraft:structure_void' }, { Name: 'minecraft:chest', Properties: { facing: 'west' } }])]),
      blocks: nbt.list(Tag.Compound, [
        { pos: ints(3, 4, 5), state: nbt.int(1) },
        { pos: ints(2, 4, 5), state: nbt.int(0) },
        { pos: ints(9, 9), state: nbt.int(1) },
        { pos: ints(4, 4, 5), state: nbt.int(7) },
      ]),
    });
    expect(cells(readExternal('nbt', file, meta()))).toEqual({ '0,0,0': 'minecraft:chest[facing=west]' });
  });

  it('says so when a file has no blocks or is something else', () => {
    const empty = writeNbt({ palette: nbt.list(Tag.Compound, [{ Name: 'minecraft:air' }]), blocks: nbt.list(Tag.Compound, []) });
    expect(() => readExternal('nbt', empty, meta())).toThrowError(/error\.convert\.empty/);
    expect(() => readExternal('litematic', writeNbt({ Hello: 'world' }), meta())).toThrowError(/error\.convert\.invalid/);
    expect(() => readExternal('schem', new Uint8Array([1, 2, 3]), meta())).toThrowError(FileFormatError);
  });

  it('refuses a .schem longer than the format allows', () => {
    const long = { ...sample(), positions: new Int32Array([0, 0, 0, 70000, 0, 0]), states: new Int32Array([0, 0]), blockLayers: new Int32Array(2) };
    expect(() => writeExternal('schem', long)).toThrowError(/error\.convert\.tooLarge/);
  });
});

describe('share strings', () => {
  it('carries blocks, name, description and tags', () => {
    const original = sample();
    const text = toShareString(original);
    expect(text).toMatch(/^PAW1:[A-Za-z0-9_-]+$/);
    expect(hasShareString(`look at this ${text} nice?`)).toBe(true);
    const back = fromShareString(`Look: ${text} (my house)`, meta('Shared blueprint'));
    expect(cells(back)).toEqual(cells(original));
    expect(back.meta.name).toBe('Small cabin');
    expect(back.meta.description).toBe('A test');
    expect(back.meta.tags).toEqual(['house']);
    // A received blueprint is a new one: its own id and the receiver as author.
    expect(back.meta.id).toBe('new-id');
    expect(back.meta.author).toBe('me');
    expect(back.meta.dataVersion).toBe(3955);
  });

  it('carries layers and removals', () => {
    const original: Blueprint = {
      ...sample(),
      blockLayers: Int32Array.from({ length: 31 }, (_, i) => (i < 10 ? 1 : 0)),
      removals: new Int32Array([0, 2, 0]),
      removalLayers: new Int32Array([1]),
      layers: [{ ...DEFAULT_LAYER }, { id: 1, name: 'Roof', color: '#FFB347', visible: true, locked: false, parent: null }],
      layerOrder: [1, 0],
    };
    const back = fromShareString(toShareString(original), meta());
    expect(back.layers.map((l) => l.name)).toEqual(['Default', 'Roof']);
    expect(back.layerOrder).toEqual([1, 0]);
    expect([...back.blockLayers]).toEqual([...original.blockLayers]);
    expect([...back.removals]).toEqual([0, 2, 0]);
    expect([...back.removalLayers]).toEqual([1]);
  });

  it('uses the given name when the string has none', () => {
    const unnamed = { ...sample(), meta: { ...sample().meta, name: '' } };
    expect(fromShareString(toShareString(unnamed), meta('Shared blueprint')).meta.name).toBe('Shared blueprint');
  });

  it('explains what is wrong with a bad string', () => {
    expect(() => fromShareString('just some text', meta())).toThrowError(/error\.share\.notFound/);
    expect(() => fromShareString('PAW1:not*base64', meta())).toThrowError(/error\.share\.damaged/);
    expect(() => fromShareString(toShareString(sample()).slice(0, 60), meta())).toThrowError(/error\.share\.damaged/);
  });
});
