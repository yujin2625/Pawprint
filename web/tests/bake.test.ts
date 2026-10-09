import { describe, expect, it } from 'vitest';
import { strToU8 } from 'fflate';
import { ModelBaker, MISSING_TEXTURE, type BakedQuad, type ModelSource } from '../src/core/model/bake';
import { parseState, formatState } from '../src/core/model/state';
import type { BlockDef } from '../src/core/pack/types';

const block = (id: string, extra: Partial<BlockDef> = {}): BlockDef => ({
  id, properties: {}, default: {}, item: id, renderLayer: 'solid', renderShape: 'model', tint: null, tabs: [], ...extra,
});

/** Our own tiny test models (no Mojang assets). */
function source(): ModelSource {
  const files: Record<string, unknown> = {
    'assets/minecraft/blockstates/stone.json': { variants: { '': { model: 'block/stone' } } },
    'assets/minecraft/models/block/cube_all.json': {
      textures: { particle: '#all' },
      elements: [{ from: [0, 0, 0], to: [16, 16, 16], faces: Object.fromEntries(['down', 'up', 'north', 'south', 'west', 'east'].map((d) => [d, { texture: '#all', cullface: d }])) }],
    },
    'assets/minecraft/models/block/stone.json': { parent: 'minecraft:block/cube_all', textures: { all: 'minecraft:block/stone' } },
    // A slab-like half block on the bottom, and a "step" whose north side is open.
    'assets/minecraft/blockstates/step.json': {
      variants: { 'facing=north': { model: 'block/step' }, 'facing=east': { model: 'block/step', y: 90, uvlock: true } },
    },
    'assets/minecraft/models/block/step.json': {
      textures: { t: 'block/plank' },
      elements: [{ from: [0, 0, 8], to: [16, 8, 16], faces: { south: { texture: '#t', cullface: 'south' }, up: { texture: '#t' }, west: { texture: '#t', cullface: 'west' } } }],
    },
    'assets/minecraft/blockstates/grass.json': { variants: { '': { model: 'block/tinted' } } },
    'assets/minecraft/models/block/tinted.json': {
      textures: { t: 'block/grass' },
      elements: [{ from: [0, 0, 0], to: [16, 16, 16], faces: { up: { texture: '#t', tintindex: 0 }, north: { texture: '#t' } } }],
    },
    'assets/minecraft/blockstates/tilted.json': { variants: { '': { model: 'block/tilted' } } },
    'assets/minecraft/models/block/tilted.json': {
      textures: { t: 'block/x' },
      elements: [{ from: [0, 0, 8], to: [16, 16, 8], rotation: { origin: [8, 8, 8], axis: 'y', angle: 45, rescale: true }, faces: { north: { texture: '#t' } } }],
    },
    'assets/minecraft/blockstates/chest.json': { variants: { 'facing=north': { model: 'block/chest' } } },
    'assets/minecraft/models/block/chest.json': { textures: { particle: 'block/oak_planks' } },
  };
  const defs: Record<string, BlockDef> = {
    'minecraft:stone': block('minecraft:stone'),
    'minecraft:step': block('minecraft:step', { properties: { facing: ['north', 'east'] }, default: { facing: 'north' } }),
    'minecraft:grass': block('minecraft:grass', { tint: { kind: 'grass', color: '#80FF00' } }),
    'minecraft:tilted': block('minecraft:tilted', { renderLayer: 'cutout' }),
    'minecraft:chest': block('minecraft:chest', { renderShape: 'entity', properties: { facing: ['north'] }, default: { facing: 'north' } }),
  };
  return {
    file: (path) => (files[path] ? strToU8(JSON.stringify(files[path])) : undefined),
    block: (id) => defs[id],
  };
}

const bounds = (q: BakedQuad) => [0, 1, 2].map((axis) => {
  const v = [q.pos[axis]!, q.pos[axis + 3]!, q.pos[axis + 6]!, q.pos[axis + 9]!];
  return [Math.min(...v), Math.max(...v)].map((n) => Math.round(n * 1000) / 1000);
});

const normal = (q: BakedQuad) => {
  const p = q.pos;
  const ab = [p[3]! - p[0]!, p[4]! - p[1]!, p[5]! - p[2]!];
  const ac = [p[6]! - p[0]!, p[7]! - p[1]!, p[8]! - p[2]!];
  const n = [ab[1]! * ac[2]! - ab[2]! * ac[1]!, ab[2]! * ac[0]! - ab[0]! * ac[2]!, ab[0]! * ac[1]! - ab[1]! * ac[0]!];
  const len = Math.hypot(...n);
  return n.map((v) => Math.round((v / len) * 1000) / 1000 + 0);
};

describe('state strings', () => {
  it('parses and formats', () => {
    expect(parseState('oak_stairs[facing=east,half=top]')).toEqual({ id: 'minecraft:oak_stairs', props: { facing: 'east', half: 'top' } });
    expect(parseState('create:casing')).toEqual({ id: 'create:casing', props: {} });
    expect(formatState('minecraft:a', { b: 'c' })).toBe('minecraft:a[b=c]');
  });
});

describe('ModelBaker', () => {
  const baker = new ModelBaker(source());

  it('bakes a full cube with outward-facing quads and full faces', () => {
    const stone = baker.bake('minecraft:stone');
    expect(stone.quads).toHaveLength(6);
    expect(stone.fullFaces.size).toBe(6);
    const up = stone.quads.find((q) => q.cull === 'up')!;
    expect(normal(up)).toEqual([0, 1, 0]);
    expect(up.texture).toBe('assets/minecraft/textures/block/stone.png');
    expect(up.shade).toBe(1);
    expect(stone.quads.find((q) => q.cull === 'down')!.shade).toBe(0.5);
    for (const q of stone.quads) expect(normal(q)).toEqual(
      { up: [0, 1, 0], down: [0, -1, 0], north: [0, 0, -1], south: [0, 0, 1], west: [-1, 0, 0], east: [1, 0, 0] }[q.cull!],
    );
  });

  it('caches by state', () => {
    expect(baker.bake('minecraft:stone')).toBe(baker.bake('minecraft:stone'));
  });

  it('rotates y=90 clockwise seen from above: south moves to west', () => {
    const east = baker.bake('minecraft:step[facing=east]');
    const culls = east.quads.map((q) => q.cull).sort();
    // Unrotated culls are south and west; after y=90 they become west and north.
    expect(culls).toEqual(['north', null, 'west']);
    const solidHalf = east.quads.find((q) => q.cull === 'west')!;
    expect(bounds(solidHalf)).toEqual([[0, 0], [0, 0.5], [0, 1]]);
  });

  it('uses the default state for missing properties', () => {
    const north = baker.bake('minecraft:step');
    expect(north.quads.map((q) => q.cull).sort()).toEqual([null, 'south', 'west']);
  });

  it('keeps uv world-aligned with uvlock', () => {
    const top = baker.bake('minecraft:step[facing=east]').quads.find((q) => q.cull === null)!;
    // The top of the rotated step covers x 0..8, z 0..16: with uvlock, u follows x (0..8) and v follows z (0..16).
    const us = [top.uv[0]!, top.uv[2]!, top.uv[4]!, top.uv[6]!];
    const vs = [top.uv[1]!, top.uv[3]!, top.uv[5]!, top.uv[7]!];
    expect([Math.min(...us), Math.max(...us)]).toEqual([0, 8]);
    expect([Math.min(...vs), Math.max(...vs)]).toEqual([0, 16]);
  });

  it('tints only faces with a tint index', () => {
    const grass = baker.bake('minecraft:grass');
    expect(grass.quads.find((q) => q.shade === 1)!.tint).toEqual([128 / 255, 1, 0]);
    expect(grass.quads.find((q) => q.shade === 0.8)!.tint).toBeNull();
  });

  it('applies element rotation with rescale', () => {
    const q = baker.bake('minecraft:tilted').quads[0]!;
    const b = bounds(q);
    // A full-width plane turned 45° and rescaled spans the block diagonal: corner to corner in x and z.
    expect(b[0]).toEqual([0, 1]);
    expect(b[2]).toEqual([0, 1]);
    expect(q.cull).toBeNull();
  });

  it('shows entity-rendered blocks as an inset box with the particle texture', () => {
    const chest = baker.bake('minecraft:chest');
    expect(chest.quads).toHaveLength(6);
    expect(chest.quads[0]!.texture).toBe('assets/minecraft/textures/block/oak_planks.png');
    expect(chest.fullFaces.size).toBe(0);
  });

  it('marks blocks the pack does not have', () => {
    const unknown = baker.bake('othermod:thing');
    expect(unknown.missing).toBe(true);
    expect(unknown.quads[0]!.texture).toBe(MISSING_TEXTURE);
  });

  it('draws plain colored boxes without a pack', () => {
    const noPack = new ModelBaker(null).bake('minecraft:stone');
    expect(noPack.missing).toBe(false);
    expect(noPack.quads[0]!.texture).toMatch(/^pawprint:color\/#[0-9A-F]{6}$/);
  });
});

describe('item icons from the pack', () => {
  const base = source();
  const defs: Record<string, BlockDef> = {
    'minecraft:sign': block('minecraft:sign', { renderShape: 'invisible' }),
    'minecraft:air': block('minecraft:air', { renderShape: 'invisible', item: null }),
  };
  const baker = new ModelBaker({
    file: base.file,
    block: (id) => defs[id] ?? base.block(id),
    hasIcon: (id) => id === 'minecraft:chest' || id === 'minecraft:sign',
  });

  it('shows code-drawn blocks with their icon instead of the particle texture', () => {
    const chest = baker.bake('minecraft:chest');
    expect(new Set(chest.quads.map((q) => q.texture))).toEqual(new Set(['pawprint:icon/minecraft:chest']));
    expect(chest.missing).toBe(false);
  });

  it('draws "invisible" blocks that have an icon, not the ones without', () => {
    expect(baker.bake('minecraft:sign').quads[0]?.texture).toBe('pawprint:icon/minecraft:sign');
    expect(baker.bake('minecraft:air').quads).toHaveLength(0);
  });
});
