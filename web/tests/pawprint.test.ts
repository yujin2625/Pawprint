import { describe, expect, it } from 'vitest';
import { strToU8, zipSync } from 'fflate';
import { DEFAULT_LAYER, packPos, readPawprint, unpackPositions, writePawprint, type Blueprint } from '../src/core/format/pawprint';
import { nbt, readNbt, Tag, writeNbt } from '../src/core/format/nbt';
import { FileFormatError } from '../src/core/zip';

function blueprint(overrides: Partial<Blueprint> = {}): Blueprint {
  return {
    meta: {
      format: 1, id: 'id-1', name: 'Test', description: '', author: 'me', tags: ['t'], created: '2026-10-08T00:00:00Z',
      modified: '2026-10-08T00:00:00Z', mcVersion: '1.21.1', dataVersion: 3955, size: [0, 0, 0], blockCount: 0,
      removalCount: 0, mods: [], blocks: [], someFutureField: { keep: true },
    },
    palette: ['minecraft:stone', 'minecraft:oak_stairs[facing=east,half=bottom]', 'create:andesite_casing'],
    positions: new Int32Array([0, 0, 0, 3, 1, 2, 1000, 255, 70000]),
    states: new Int32Array([0, 1, 2]),
    blockLayers: new Int32Array(3),
    removals: new Int32Array([5, 5, 5]),
    removalLayers: new Int32Array(1),
    layers: [{ ...DEFAULT_LAYER }],
    layerOrder: [0],
    thumbnail: null,
    ...overrides,
  };
}

describe('positions', () => {
  it('matches BlockPos.asLong', () => {
    // Values from Minecraft: BlockPos(1, 2, 3).asLong() == 274877919234, BlockPos(-1, -1, -1) == -1.
    expect(packPos(1, 2, 3)).toBe(274877919234n);
    expect(packPos(-1, -1, -1)).toBe(-1n);
    expect([...unpackPositions(new BigInt64Array([274877919234n, -1n]))]).toEqual([1, 2, 3, -1, -1, -1]);
  });
});

describe('NBT', () => {
  it('round-trips the types blueprints use', () => {
    const root = readNbt(writeNbt({
      I: nbt.int(-7),
      S: 'héllo',
      L: nbt.list(Tag.String, ['a', 'b']),
      E: nbt.list(Tag.String, []),
      IA: new Int32Array([1, -2, 3]),
      LA: new BigInt64Array([1n, -1n, 1n << 60n]),
      C: { nested: nbt.int(1) },
    }));
    expect(root.I).toBe(-7);
    expect(root.S).toBe('héllo');
    expect(root.L).toEqual(['a', 'b']);
    expect(root.E).toEqual([]);
    expect([...(root.IA as Int32Array)]).toEqual([1, -2, 3]);
    expect([...(root.LA as BigInt64Array)]).toEqual([1n, -1n, 1n << 60n]);
    expect(root.C).toEqual({ nested: 1 });
  });

  it('rejects truncated data', () => {
    const good = writeNbt({ A: new Int32Array(100) });
    expect(() => readNbt(good.slice(0, good.length - 8))).toThrowError(FileFormatError);
  });
});

describe('.pawprint', () => {
  it('writes format 1 when only the default layer is used, and reads it back', () => {
    const bytes = writePawprint(blueprint());
    const back = readPawprint(bytes);
    expect(back.meta.format).toBe(1);
    expect(back.meta.layers).toBeUndefined();
    expect(back.palette).toEqual(blueprint().palette);
    expect([...back.positions]).toEqual([0, 0, 0, 3, 1, 2, 1000, 255, 70000]);
    expect([...back.states]).toEqual([0, 1, 2]);
    expect([...back.removals]).toEqual([5, 5, 5]);
    expect(back.meta.size).toEqual([1001, 256, 70001]);
    expect(back.meta.blocks).toEqual(['create:andesite_casing', 'minecraft:oak_stairs', 'minecraft:stone']);
    expect(back.meta.mods).toEqual(['create', 'minecraft']);
    expect(back.meta.someFutureField).toEqual({ keep: true });
    expect(back.layers).toEqual([DEFAULT_LAYER]);
  });

  it('writes format 2 with layers and keeps them', () => {
    const layers = [
      { ...DEFAULT_LAYER },
      { id: 1, name: 'Roof', color: '#FFB347', visible: false, locked: true, parent: 2 },
      { id: 2, name: 'Floor 1', color: '#7FB3FF', visible: true, locked: false, parent: null, group: true, collapsed: false, children: [1] },
    ];
    const bytes = writePawprint(blueprint({ layers, layerOrder: [2, 0], blockLayers: new Int32Array([0, 1, 1]), removalLayers: new Int32Array([1]) }));
    const back = readPawprint(bytes);
    expect(back.meta.format).toBe(2);
    expect(back.layers).toEqual(layers);
    expect(back.layerOrder).toEqual([2, 0]);
    expect([...back.blockLayers]).toEqual([0, 1, 1]);
    expect([...back.removalLayers]).toEqual([1]);
  });

  it('falls back to layer 0 for unknown or group layer ids', () => {
    const layers = [{ ...DEFAULT_LAYER }, { id: 3, name: 'G', color: '#000000', visible: true, locked: false, parent: null, group: true, children: [] }];
    const back = readPawprint(writePawprint(blueprint({ layers, layerOrder: [3, 0], blockLayers: new Int32Array([9, 3, 0]) })));
    expect([...back.blockLayers]).toEqual([0, 0, 0]);
  });

  it('breaks parent loops and adds a missing layer 0', () => {
    const meta = {
      format: 2, id: 'x', size: [1, 1, 1],
      layers: [
        { id: 1, name: 'A', color: '#000000', visible: true, locked: false, parent: 2, group: true, children: [] },
        { id: 2, name: 'B', color: '#000000', visible: true, locked: false, parent: 1, group: true, children: [] },
      ],
      layerOrder: [],
    };
    const bytes = zipSync({
      'meta.json': strToU8(JSON.stringify(meta)),
      'blueprint.nbt': writeNbt({ Palette: nbt.list(Tag.String, []), Positions: new BigInt64Array(0), States: new Int32Array(0), Removals: new BigInt64Array(0) }),
    });
    const back = readPawprint(bytes);
    expect(back.layers.map((l) => l.id).sort()).toEqual([0, 1, 2]);
    expect(back.layers.some((l) => l.parent === null && (l.id === 1 || l.id === 2))).toBe(true);
    expect(back.layerOrder).toContain(0);
  });

  it('rejects newer formats and bad palette indexes', () => {
    const tooNew = zipSync({ 'meta.json': strToU8('{"format":3}'), 'blueprint.nbt': writeNbt({}) });
    expect(() => readPawprint(tooNew)).toThrowError(/error\.pawprint\.tooNew/);
    const bad = zipSync({
      'meta.json': strToU8('{"format":1}'),
      'blueprint.nbt': writeNbt({ Palette: nbt.list(Tag.String, ['a']), Positions: new BigInt64Array([0n]), States: new Int32Array([5]) }),
    });
    expect(() => readPawprint(bad)).toThrowError(/error\.pawprint\.badData/);
  });
});
