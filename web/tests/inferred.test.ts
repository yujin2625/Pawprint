import { describe, expect, it } from 'vitest';
import { strToU8, zlibSync } from 'fflate';
import { buildPackFromAssets, type AssetSource, type IconInput } from '../src/core/pack/assetPack';
import { categorize, mergeTagFile, tagIdOf } from '../src/core/pack/categories';
import { readPawpack } from '../src/core/pack/pawpack';
import { pngOpacity } from '../src/core/pack/png';

/** A PNG of our own making: `alpha(x, y)` per pixel. Chunk checksums are left zero (the reader does not check them). */
function png(width: number, height: number, alpha: (x: number, y: number) => number, colorType: 6 | 3 | 2 = 6, filter = 0): Uint8Array {
  const chunk = (type: string, data: Uint8Array): Uint8Array => {
    const out = new Uint8Array(12 + data.length);
    new DataView(out.buffer).setUint32(0, data.length);
    out.set(strToU8(type), 4);
    out.set(data, 8);
    return out;
  };
  const header = new Uint8Array(13);
  const view = new DataView(header.buffer);
  view.setUint32(0, width);
  view.setUint32(4, height);
  header[8] = 8;
  header[9] = colorType;
  const channels = colorType === 6 ? 4 : colorType === 2 ? 3 : 1;
  const stride = width * channels;
  const raw = new Uint8Array((stride + 1) * height);
  const palette: number[] = [];
  for (let y = 0; y < height; y++) {
    const line = new Uint8Array(stride);
    for (let x = 0; x < width; x++) {
      const a = alpha(x, y);
      if (colorType === 6) line.set([120, 90, 60, a], x * 4);
      else if (colorType === 2) line.set([120, 90, 60], x * 3);
      else {
        if (!palette.includes(a)) palette.push(a);
        line[x] = palette.indexOf(a);
      }
    }
    raw[y * (stride + 1)] = filter;
    for (let i = 0; i < stride; i++) {
      const left = i >= channels ? line[i - channels]! : 0;
      raw[y * (stride + 1) + 1 + i] = filter === 1 ? (line[i]! - left) & 255 : line[i]!;
    }
  }
  const parts = [new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', header)];
  if (colorType === 3) {
    parts.push(chunk('PLTE', new Uint8Array(palette.length * 3)), chunk('tRNS', new Uint8Array(palette)));
  }
  parts.push(chunk('IDAT', zlibSync(raw)), chunk('IEND', new Uint8Array(0)));
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let at = 0;
  for (const p of parts) {
    out.set(p, at);
    at += p.length;
  }
  return out;
}

const OPAQUE = png(16, 16, () => 255);
const HOLES = png(16, 16, (x, y) => ((x + y) % 3 === 0 ? 0 : 255));
const GLASSY = png(16, 16, () => 140);

describe('texture opacity', () => {
  it('tells solid, cutout and translucent textures apart', () => {
    expect(pngOpacity(OPAQUE)).toBe('opaque');
    expect(pngOpacity(HOLES)).toBe('cutout');
    expect(pngOpacity(GLASSY)).toBe('translucent');
  });

  it('reads filtered rows, palettes and images without alpha', () => {
    expect(pngOpacity(png(16, 16, (x) => (x < 4 ? 0 : 255), 6, 1))).toBe('cutout');
    expect(pngOpacity(png(16, 16, () => 255, 6, 1))).toBe('opaque');
    expect(pngOpacity(png(8, 8, (x) => (x === 0 ? 0 : 255), 3))).toBe('cutout');
    expect(pngOpacity(png(8, 8, () => 255, 3))).toBe('opaque');
    expect(pngOpacity(png(8, 8, () => 255, 2))).toBe('opaque');
  });

  it('keeps smoothed cutout edges as cutout', () => {
    // 4 half-transparent pixels of 256 (under 5%).
    expect(pngOpacity(png(16, 16, (x, y) => (y === 0 && x < 4 ? 128 : y < 4 ? 0 : 255)))).toBe('cutout');
  });

  it('gives up on files it cannot read', () => {
    expect(pngOpacity(new Uint8Array([0x89, 0x50, 0x4e, 0x47]))).toBeNull();
    expect(pngOpacity(strToU8('not a picture, just some text that is long enough'))).toBeNull();
  });
});

describe('categories from block tags', () => {
  it('merges tag files in pack order and honors replace', () => {
    const tags: Record<string, string[]> = {};
    mergeTagFile(tags, 'minecraft:stairs', { values: ['minecraft:oak_stairs'] });
    mergeTagFile(tags, 'minecraft:stairs', { values: ['mymod:maple_stairs', { id: 'other:maybe_stairs', required: false }] });
    expect(tags['minecraft:stairs']).toEqual(['minecraft:oak_stairs', 'mymod:maple_stairs', 'other:maybe_stairs']);
    mergeTagFile(tags, 'minecraft:stairs', { replace: true, values: ['mymod:only'] });
    expect(tags['minecraft:stairs']).toEqual(['mymod:only']);
  });

  it('reads tag IDs from both folder spellings', () => {
    expect(tagIdOf('data/minecraft/tags/block/stairs.json')).toBe('minecraft:stairs');
    expect(tagIdOf('data/c/tags/blocks/storage_blocks/iron.json')).toBe('c:storage_blocks/iron');
    expect(tagIdOf('data/minecraft/tags/item/stairs.json')).toBeNull();
  });

  it('follows tag references, even circular ones', () => {
    const byBlock = categorize({
      'minecraft:logs': ['#minecraft:oak_logs', '#mymod:maple_logs'],
      'minecraft:oak_logs': ['oak_log'],
      'mymod:maple_logs': ['mymod:maple_log', '#minecraft:logs'],
      'minecraft:slabs': ['mymod:maple_slab'],
      'minecraft:wooden_slabs': ['mymod:maple_slab'],
    });
    expect(byBlock.get('minecraft:oak_log')).toEqual(['pawprint:logs']);
    expect(byBlock.get('mymod:maple_log')).toEqual(['pawprint:logs']);
    expect(byBlock.get('mymod:maple_slab')).toEqual(['pawprint:slabs']);
  });
});

describe('pack from game files: what the files say', async () => {
  const json = (v: unknown) => strToU8(JSON.stringify(v));
  const cube = { elements: [{ from: [0, 0, 0], to: [16, 16, 16], faces: { up: { texture: '#all' } } }] };
  const state = (model: string) => json({ variants: { '': { model } } });
  const files = new Map<string, Uint8Array>([
    ['assets/minecraft/models/block/cube_all.json', json(cube)],
    ['assets/minecraft/models/item/generated.json', json({ parent: 'builtin/generated' })],
    // A glass-like block whose name says nothing.
    ['assets/mymod/blockstates/crystal.json', state('mymod:block/crystal')],
    ['assets/mymod/models/block/crystal.json', json({ parent: 'minecraft:block/cube_all', textures: { all: 'mymod:block/crystal' } })],
    ['assets/mymod/textures/block/crystal.png', GLASSY],
    ['assets/mymod/models/item/crystal.json', json({ parent: 'mymod:block/crystal' })],
    // A grate: holes in the texture.
    ['assets/mymod/blockstates/grate.json', state('mymod:block/grate')],
    ['assets/mymod/models/block/grate.json', json({ parent: 'minecraft:block/cube_all', textures: { all: 'mymod:block/grate', particle: 'mymod:block/crystal' } })],
    ['assets/mymod/textures/block/grate.png', HOLES],
    // The model says its layer; the texture would say otherwise.
    ['assets/mymod/blockstates/panel.json', state('mymod:block/panel')],
    ['assets/mymod/models/block/panel_base.json', json({ parent: 'minecraft:block/cube_all', render_type: 'minecraft:translucent' })],
    ['assets/mymod/models/block/panel.json', json({ parent: 'mymod:block/panel_base', render_type: 'cutout', textures: { all: 'mymod:block/solid' } })],
    ['assets/mymod/textures/block/solid.png', OPAQUE],
    // A plain block, and a crop whose item is its seeds.
    ['assets/mymod/blockstates/bricks.json', state('mymod:block/bricks')],
    ['assets/mymod/models/block/bricks.json', json({ parent: 'minecraft:block/cube_all', textures: { all: 'mymod:block/solid' } })],
    ['assets/mymod/blockstates/rice.json', state('mymod:block/rice')],
    ['assets/mymod/models/block/rice.json', json({ parent: 'minecraft:block/cube_all', textures: { all: 'mymod:block/grate' } })],
    ['data/mymod/loot_table/blocks/rice.json', json({ pools: [{ entries: [{ type: 'minecraft:alternatives', children: [{ type: 'minecraft:item', name: 'mymod:rice' }, { type: 'minecraft:item', name: 'mymod:rice_seeds' }] }] }] })],
    // A flower: a flat item picture, found through a 1.21.4 item definition.
    ['assets/mymod/blockstates/tulip.json', state('mymod:block/tulip')],
    ['assets/mymod/models/block/tulip.json', json({ parent: 'minecraft:block/cube_all', textures: { all: 'mymod:block/grate' } })],
    ['assets/mymod/items/tulip.json', json({ model: { type: 'minecraft:model', model: 'mymod:item/tulip_flat' } })],
    ['assets/mymod/models/item/tulip_flat.json', json({ parent: 'minecraft:item/generated', textures: { layer0: 'mymod:item/tulip' } })],
    ['assets/mymod/textures/item/tulip.png', HOLES],
  ]);
  const source: AssetSource = {
    names: [...files.keys()].filter((n) => !n.includes('/textures/')),
    read: async (paths) => new Map(paths.filter((p) => files.has(p)).map((p) => [p, files.get(p)!])),
    blockTags: async () => ({ 'minecraft:flowers': ['#minecraft:small_flowers'], 'minecraft:small_flowers': ['mymod:tulip'], 'c:glass_blocks': ['mymod:crystal'] }),
  };
  let iconInput: IconInput | null = null;
  const built = await buildPackFromAssets(source, {
    id: 'i', now: '2026-10-10T00:00:00Z', name: 'Test', source: 'instance-folder', mcVersion: '1.21.4', dataVersion: 4189, loader: 'neoforge',
    languages: { en_us: {} },
    renderIcons: async (input) => {
      iconInput = input;
      return { png: OPAQUE, cell: 32, columns: 16, icons: { 'mymod:tulip': 0 } };
    },
  });
  const pack = readPawpack(built.bytes);
  const byId = Object.fromEntries(pack.blocks.map((b) => [b.id, b]));

  it('takes the render layer from the textures', () => {
    expect(byId['mymod:crystal']!.renderLayer).toBe('translucent');
    expect(byId['mymod:grate']!.renderLayer).toBe('cutout');
    expect(byId['mymod:bricks']!.renderLayer).toBe('solid');
  });

  it('believes the model over the texture, the nearest model first', () => {
    expect(byId['mymod:panel']!.renderLayer).toBe('cutout');
  });

  it('groups blocks by their tags', () => {
    expect(byId['mymod:tulip']!.tabs).toEqual(['pawprint:plants']);
    expect(byId['mymod:crystal']!.tabs).toEqual(['pawprint:glass']);
    expect(byId['mymod:bricks']!.tabs).toEqual([]);
  });

  it('finds the item of a block without one in its loot table', () => {
    expect(byId['mymod:rice']!.item).toBe('mymod:rice_seeds');
    expect(byId['mymod:crystal']!.item).toBe('mymod:crystal');
    expect(byId['mymod:bricks']!.item).toBeNull();
  });

  it('hands the icon painter flat item pictures and stores its sheet', () => {
    expect([...iconInput!.flat.keys()]).toEqual(['mymod:tulip']);
    expect(iconInput!.flat.get('mymod:tulip')).toEqual([HOLES]);
    expect(iconInput!.files.has('assets/mymod/textures/block/crystal.png')).toBe(true);
    expect(pack.icons).toEqual({ cell: 32, columns: 16, icons: { 'mymod:tulip': 0 } });
    // Item models and pictures are only for the icons: they are not part of the pack.
    expect(pack.files.has('assets/mymod/models/item/tulip_flat.json')).toBe(false);
    expect(pack.files.has('assets/mymod/textures/item/tulip.png')).toBe(false);
  });
});
