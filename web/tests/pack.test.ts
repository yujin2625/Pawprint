import { describe, expect, it } from 'vitest';
import { zipSync, strToU8 } from 'fflate';
import { buildPackFromJar, inferProperties } from '../src/core/pack/vanillaJar';
import { readPawpack, blockName } from '../src/core/pack/pawpack';
import { pickPreviews } from '../src/core/pack/previews';
import { FileFormatError } from '../src/core/zip';

/** A tiny fake client jar made of our own test data (no Mojang assets). */
function fakeJar(): Uint8Array {
  const json = (v: unknown) => strToU8(JSON.stringify(v));
  const png = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 1, 2, 3]);
  return zipSync({
    'version.json': json({ id: '1.21.1', world_version: 3955 }),
    'assets/minecraft/lang/en_us.json': json({ 'block.minecraft.test_stone': 'Test Stone', 'block.minecraft.test_stairs': 'Test Stairs' }),
    'assets/minecraft/blockstates/test_stone.json': json({ variants: { '': { model: 'minecraft:block/test_stone' } } }),
    'assets/minecraft/blockstates/test_stairs.json': json({
      variants: {
        'facing=east,half=bottom': { model: 'minecraft:block/test_stairs' },
        'facing=west,half=top': [{ model: 'minecraft:block/test_stairs', x: 180 }, { model: 'minecraft:block/test_stairs' }],
      },
    }),
    'assets/minecraft/blockstates/test_fence.json': json({
      multipart: [
        { apply: { model: 'minecraft:block/test_post' } },
        { when: { north: 'true' }, apply: { model: 'minecraft:block/test_side' } },
        { when: { OR: [{ east: 'true' }, { west: 'low|tall' }] }, apply: { model: 'minecraft:block/test_side' } },
      ],
    }),
    'assets/minecraft/blockstates/test_chest.json': json({ variants: { 'facing=north': { model: 'minecraft:block/test_chest' } } }),
    'assets/minecraft/blockstates/oak_leaves.json': json({ variants: { '': { model: 'minecraft:block/test_stone' } } }),
    'assets/minecraft/models/block/cube.json': json({ elements: [{ from: [0, 0, 0], to: [16, 16, 16], faces: {} }] }),
    'assets/minecraft/models/block/cube_all.json': json({ parent: 'block/cube', textures: { particle: '#all' } }),
    'assets/minecraft/models/block/test_stone.json': json({ parent: 'minecraft:block/cube_all', textures: { all: 'minecraft:block/test_stone' } }),
    'assets/minecraft/models/block/test_stairs.json': json({ parent: 'block/cube', textures: { side: 'block/test_planks' } }),
    'assets/minecraft/models/block/test_post.json': json({ parent: 'block/cube' }),
    'assets/minecraft/models/block/test_side.json': json({ parent: 'block/cube' }),
    'assets/minecraft/models/block/test_chest.json': json({ textures: { particle: 'block/test_planks' } }),
    'assets/minecraft/models/item/test_stone.json': json({ parent: 'block/test_stone' }),
    'assets/minecraft/items/test_stairs.json': json({ model: { type: 'minecraft:model', model: 'block/test_stairs' } }),
    'assets/minecraft/textures/block/test_stone.png': png,
    'assets/minecraft/textures/block/test_planks.png': png,
    'assets/minecraft/textures/block/test_planks.png.mcmeta': json({ animation: {} }),
    'assets/minecraft/textures/block/unused.png': png,
    'data/minecraft/whatever.json': json({}),
  });
}

describe('vanilla jar → pack', () => {
  const pack = buildPackFromJar(fakeJar(), { id: 'test-id', now: '2026-10-08T00:00:00Z' });
  const loaded = readPawpack(pack.bytes);
  const byId = Object.fromEntries(loaded.blocks.map((b) => [b.id, b]));

  it('reads the version and counts blocks', () => {
    expect(loaded.info.mcVersion).toBe('1.21.1');
    expect(loaded.info.dataVersion).toBe(3955);
    expect(loaded.info.source).toBe('vanilla-jar');
    expect(loaded.info.propertiesComplete).toBe(false);
    expect(loaded.info.blockCount).toBe(5);
    expect(loaded.info.languages).toEqual(['en_us']);
  });

  it('adds languages from the launcher assets, keeping only known blocks', () => {
    const multi = readPawpack(
      buildPackFromJar(fakeJar(), {
        id: 'test-id',
        now: '2026-10-08T00:00:00Z',
        languages: {
          ko_kr: { 'block.minecraft.test_stone': '시험 돌', 'block.minecraft.gone': '없는 블럭', 'item.minecraft.x': '아이템' },
          de_de: {},
          '../bad': { 'block.minecraft.test_stone': 'x' },
        },
      }).bytes,
    );
    expect(multi.info.languages).toEqual(['en_us', 'ko_kr']);
    expect(multi.languages.ko_kr).toEqual({ 'minecraft:test_stone': '시험 돌' });
    expect(blockName(multi.languages, 'minecraft:test_stairs', 'ko_kr')).toBe('Test Stairs');
  });

  it('infers properties from variants and multipart', () => {
    expect(byId['minecraft:test_stairs']!.properties).toEqual({ facing: ['east', 'west'], half: ['bottom', 'top'] });
    expect(byId['minecraft:test_fence']!.properties).toEqual({ north: ['true'], east: ['true'], west: ['low', 'tall'] });
    expect(byId['minecraft:test_stone']!.properties).toEqual({});
  });

  it('guesses usual defaults', () => {
    expect(byId['minecraft:test_stairs']!.default).toEqual({ facing: 'east', half: 'bottom' });
    expect(byId['minecraft:test_fence']!.default).toEqual({ north: 'true', east: 'true', west: 'low' });
  });

  it('marks blocks without model elements as entity-rendered', () => {
    expect(byId['minecraft:test_chest']!.renderShape).toBe('entity');
    expect(byId['minecraft:test_stone']!.renderShape).toBe('model');
  });

  it('finds items in both item model layouts', () => {
    expect(byId['minecraft:test_stone']!.item).toBe('minecraft:test_stone');
    expect(byId['minecraft:test_stairs']!.item).toBe('minecraft:test_stairs');
    expect(byId['minecraft:test_fence']!.item).toBeNull();
  });

  it('applies known tints and render layers', () => {
    expect(byId['minecraft:oak_leaves']!.tint?.kind).toBe('foliage');
    expect(byId['minecraft:oak_leaves']!.renderLayer).toBe('cutout_mipped');
  });

  it('keeps only referenced textures, with animation metadata', () => {
    expect(loaded.files.has('assets/minecraft/textures/block/test_stone.png')).toBe(true);
    expect(loaded.files.has('assets/minecraft/textures/block/test_planks.png.mcmeta')).toBe(true);
    expect(loaded.files.has('assets/minecraft/textures/block/unused.png')).toBe(false);
    expect(loaded.files.has('data/minecraft/whatever.json')).toBe(false);
  });

  it('names blocks from the jar language file', () => {
    expect(blockName(loaded.languages, 'minecraft:test_stone', 'ko_kr')).toBe('Test Stone');
    expect(blockName(loaded.languages, 'minecraft:test_fence', 'en_us')).toBe('Test Fence');
    expect(blockName(loaded.languages, 'mod:unknown', 'en_us')).toBe('mod:unknown');
  });

  it('picks full-cube previews', () => {
    expect(pickPreviews(loaded.blocks, loaded.files).map((p) => p.id)).toEqual(['minecraft:test_stone']);
  });
});

describe('inferProperties', () => {
  it('ignores the empty variant key', () => {
    expect(inferProperties({ variants: { '': {} } })).toEqual({});
  });
});

describe('readPawpack', () => {
  const json = (v: unknown) => strToU8(JSON.stringify(v));
  const info = { format: 1, id: 'x', name: 'P', mcVersion: '1.21.1', source: 'mod-export', languages: [], blockCount: 0 };

  it('rejects newer formats', () => {
    const bytes = zipSync({ 'pack.json': json({ ...info, format: 2 }), 'blocks.json': json([]), 'lang/en_us.json': json({}) });
    expect(() => readPawpack(bytes)).toThrowError(FileFormatError);
    try {
      readPawpack(bytes);
    } catch (e) {
      expect((e as FileFormatError).key).toBe('error.pack.tooNew');
    }
  });

  it('takes counts and languages from the catalog, not pack.json', () => {
    const bytes = zipSync({
      'pack.json': json({ ...info, blockCount: 99, languages: ['de_de'] }),
      'blocks.json': json([{ id: 'a:b', properties: { p: ['x', 'y'] }, default: { p: 'z' } }]),
      'lang/en_us.json': json({ 'a:b': 'B' }),
      'lang/ko_kr.json': json({ 'a:b': '비' }),
    });
    const pack = readPawpack(bytes);
    expect(pack.info.blockCount).toBe(1);
    expect(pack.info.languages).toEqual(['en_us', 'ko_kr']);
    expect(pack.blocks[0]!.default).toEqual({ p: 'x' });
  });

  it('rejects garbage', () => {
    expect(() => readPawpack(new Uint8Array([1, 2, 3]))).toThrowError(FileFormatError);
  });

  it('skips unsafe entry names', () => {
    const bytes = zipSync({
      'pack.json': json(info),
      'blocks.json': json([]),
      'lang/en_us.json': json({}),
      '../evil.txt': strToU8('x'),
    });
    expect(readPawpack(bytes).files.has('../evil.txt')).toBe(false);
  });
});
