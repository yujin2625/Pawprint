import { describe, expect, it } from 'vitest';
import { strToU8 } from 'fflate';
import { buildPackFromAssets, type AssetSource } from '../src/core/pack/assetPack';
import { readPawpack } from '../src/core/pack/pawpack';

/** A tiny modded "instance": vanilla plus two mods, our own test data. */
function source(): AssetSource & { reads: number } {
  const json = (v: unknown) => strToU8(JSON.stringify(v));
  const png = new Uint8Array([0x89, 0x50, 0x4e, 0x47]);
  const files = new Map<string, Uint8Array>([
    ['assets/minecraft/blockstates/stone.json', json({ variants: { '': { model: 'minecraft:block/stone' } } })],
    ['assets/minecraft/models/block/cube.json', json({ elements: [{ from: [0, 0, 0], to: [16, 16, 16], faces: { up: {} } }] })],
    ['assets/minecraft/models/block/stone.json', json({ parent: 'block/cube', textures: { all: 'block/stone' } })],
    ['assets/minecraft/models/block/leaves.json', json({ elements: [{ from: [0, 0, 0], to: [16, 16, 16], faces: { up: { tintindex: 0 } } }] })],
    ['assets/minecraft/textures/block/stone.png', png],
    ['assets/mymod/blockstates/maple_leaves.json', json({ variants: { 'persistent=false': { model: 'mymod:block/maple_leaves' }, 'persistent=true': { model: 'mymod:block/maple_leaves' } } })],
    ['assets/mymod/models/block/maple_leaves.json', json({ parent: 'minecraft:block/leaves', textures: { all: 'mymod:block/maple' } })],
    ['assets/mymod/textures/block/maple.png', png],
    ['assets/mymod/items/maple_leaves.json', json({})],
    ['assets/other/blockstates/deco/lamp.json', json({ variants: { '': { model: 'other:block/lamp' } } })],
    ['assets/other/models/block/lamp.json', json({ parent: 'minecraft:block/stone' })],
  ]);
  const s = {
    reads: 0,
    names: [...files.keys()].filter((n) => !n.includes('/textures/')),
    read: async (paths: string[]) => {
      s.reads++;
      return new Map(paths.filter((p) => files.has(p)).map((p) => [p, files.get(p)!]));
    },
  };
  return s;
}

describe('pack from game assets', async () => {
  const src = source();
  const built = await buildPackFromAssets(src, {
    id: 'i', now: '2026-10-09T00:00:00Z', name: 'Test instance', source: 'instance-folder', mcVersion: '1.21.1', dataVersion: 3955,
    loader: 'neoforge',
    mods: [{ id: 'mymod', name: 'My Mod', version: '1.0' }, { id: 'nothing', name: 'No blocks', version: '1' }],
    languages: {
      en_us: { 'block.minecraft.stone': 'Stone', 'block.mymod.maple_leaves': 'Maple Leaves' },
      ko_kr: { 'block.mymod.maple_leaves': '단풍잎', 'block.other.deco.lamp': '등' },
    },
  });
  const pack = readPawpack(built.bytes);
  const byId = Object.fromEntries(pack.blocks.map((b) => [b.id, b]));

  it('finds blocks in every namespace, nested paths included', () => {
    expect(Object.keys(byId).sort()).toEqual(['minecraft:stone', 'mymod:maple_leaves', 'other:deco/lamp']);
    expect(pack.info.source).toBe('instance-folder');
    expect(pack.info.loader).toBe('neoforge');
  });

  it('names blocks from the language tables, English fallback from the path', () => {
    expect(pack.languages.en_us!['mymod:maple_leaves']).toBe('Maple Leaves');
    expect(pack.languages.en_us!['other:deco/lamp']).toBe('Lamp');
    expect(pack.languages.ko_kr).toEqual({ 'mymod:maple_leaves': '단풍잎', 'other:deco/lamp': '등' });
  });

  it('follows parents across namespaces and guesses modded tints', () => {
    expect(byId['other:deco/lamp']!.renderShape).toBe('model');
    expect(byId['mymod:maple_leaves']!.tint?.kind).toBe('foliage');
    expect(byId['mymod:maple_leaves']!.renderLayer).toBe('cutout_mipped');
    expect(byId['mymod:maple_leaves']!.item).toBe('mymod:maple_leaves');
    expect(byId['mymod:maple_leaves']!.properties).toEqual({ persistent: ['false', 'true'] });
  });

  it('keeps only mods with blocks and the textures models use', () => {
    expect(pack.info.mods?.map((m) => m.id)).toEqual(['mymod']);
    expect(pack.files.has('assets/mymod/textures/block/maple.png')).toBe(true);
    expect(pack.files.has('assets/minecraft/textures/block/stone.png')).toBe(true);
  });

  it('reads models in a few waves, not one by one', () => {
    expect(src.reads).toBeLessThanOrEqual(6);
  });
});
