import { describe, expect, it } from 'vitest';
import type { Blueprint, PawprintMeta } from '../src/core/format/pawprint';
import { aiPrompt, fixRequest, looksLikeTextBlueprint, readTextBlueprint, TextFormatError, type KnownBlocks } from '../src/core/format/textBlueprint';
import type { BlockDef } from '../src/core/pack/types';

const meta = (): PawprintMeta => ({
  format: 1, id: 'new-id', name: '', description: '', author: 'me', tags: [], created: '2026-10-10T00:00:00Z', modified: '2026-10-10T00:00:00Z',
  mcVersion: '', dataVersion: 0, size: [0, 0, 0], blockCount: 0, removalCount: 0, mods: [], blocks: [],
});

function cells(bp: Blueprint): Record<string, string> {
  const out: Record<string, string> = {};
  for (let i = 0; i < bp.states.length; i++) out[[bp.positions[i * 3], bp.positions[i * 3 + 1], bp.positions[i * 3 + 2]].join(',')] = bp.palette[bp.states[i]!]!;
  for (let i = 0; i < bp.removals.length; i += 3) out[[bp.removals[i], bp.removals[i + 1], bp.removals[i + 2]].join(',')] = 'air';
  return out;
}

const read = (value: unknown, known: KnownBlocks | null = null) => readTextBlueprint(typeof value === 'string' ? value : JSON.stringify(value), meta(), known);
const fails = (value: unknown, message: RegExp, known: KnownBlocks | null = null): void => {
  let error: unknown;
  try {
    read(value, known);
  } catch (e) {
    error = e;
  }
  expect(error).toBeInstanceOf(TextFormatError);
  expect((error as Error).message).toMatch(message);
};

describe('AI instructions', () => {
  it('names the game version and the mods of the pack', () => {
    const text = aiPrompt('1.21.1', ['create', 'minecraft', 'aether', 'create']);
    expect(text).toContain('Only use blocks that exist in Minecraft 1.21.1 and these mods (namespaces): aether, create. If unsure');
    expect(text).toContain('"pawprint": 1');
    expect(text.endsWith('Build request: ')).toBe(true);
  });

  it('asks for vanilla blocks when there is no pack', () => {
    expect(aiPrompt('', [])).toContain('Only use blocks that exist in the latest Minecraft Java Edition.');
  });
});

describe('blueprints written as text', () => {
  it('finds the JSON in a chat reply', () => {
    const reply = 'Sure! Here is your hut:\n```json\n{"pawprint": 1, "name": "Hut", "operations": [{"shape": "single", "at": [0, 0, 0], "block": "minecraft:stone"}]}\n```\nEnjoy.';
    expect(looksLikeTextBlueprint(reply)).toBe(true);
    expect(looksLikeTextBlueprint('PAW1:abc')).toBe(false);
    const { blueprint } = read(reply);
    expect(blueprint.meta.name).toBe('Hut');
    expect(blueprint.meta.id).toBe('new-id');
    expect(cells(blueprint)).toEqual({ '0,0,0': 'minecraft:stone' });
  });

  it('runs operations in order, then layers, and shifts to start at zero', () => {
    const { blueprint } = read({
      pawprint: 1,
      name: 'House',
      description: 'd',
      tags: ['a', 'b'],
      palette: { W: 'oak_planks', D: 'minecraft:oak_door[half=lower,facing=south]', _: 'air' },
      operations: [
        { shape: 'box', from: [-1, 0, -1], to: [1, 0, 1], block: 'minecraft:stone' },
        { shape: 'single', at: [0, 0, 0], block: 'W' },
      ],
      layers: { origin: [-1, 1, -1], grid: [['W.W', ' D ', 'W_W']] },
    });
    expect(blueprint.meta.description).toBe('d');
    expect(blueprint.meta.tags).toEqual(['a', 'b']);
    expect(blueprint.meta.size).toEqual([3, 2, 3]);
    const got = cells(blueprint);
    expect(got['0,0,0']).toBe('minecraft:stone');
    expect(got['1,0,1']).toBe('minecraft:oak_planks');
    expect(got['0,1,0']).toBe('minecraft:oak_planks');
    expect(got['1,1,0']).toBeUndefined();
    expect(got['1,1,1']).toBe('minecraft:oak_door[half=lower,facing=south]');
    // "air" is a spot that must be empty: a removal.
    expect(got['1,1,2']).toBe('air');
    expect(blueprint.meta.removalCount).toBe(1);
    expect(Object.keys(got)).toHaveLength(9 + 6);
  });

  it('draws the shapes like the mod', () => {
    const count = (op: object): number => Object.keys(cells(read({ pawprint: 1, name: 'n', operations: [{ block: 'minecraft:stone', ...op }] }).blueprint)).length;
    expect(count({ shape: 'box', from: [0, 0, 0], to: [3, 2, 4] })).toBe(4 * 3 * 5);
    expect(count({ shape: 'hollow_box', from: [0, 0, 0], to: [3, 2, 4] })).toBe(4 * 3 * 5 - 2 * 1 * 3);
    expect(count({ shape: 'walls', from: [0, 0, 0], to: [3, 2, 4] })).toBe((4 * 5 - 2 * 3) * 3);
    expect(count({ shape: 'line', from: [0, 0, 0], to: [5, 2, -3] })).toBe(6);
    // Radius 1: the center, 6 neighbors, and the 12 edge cells (distance² 2 ≤ 1.5² = 2.25).
    expect(count({ shape: 'sphere', center: [0, 0, 0], radius: 1 })).toBe(19);
    expect(count({ shape: 'sphere', center: [5, 5, 5], radius: 0 })).toBe(1);
    expect(count({ shape: 'cylinder', base: [0, 0, 0], radius: 1, height: 4 })).toBe(9 * 4);
    const line = cells(read({ pawprint: 1, name: 'n', operations: [{ shape: 'line', from: [0, 0, 0], to: [2, 1, 0], block: 'minecraft:stone' }] }).blueprint);
    // Halfway (y = 0.5) rounds up, as Java's Math.round does.
    expect(Object.keys(line).sort()).toEqual(['0,0,0', '1,1,0', '2,1,0']);
  });

  it('counts a palette key of one emoji as one character', () => {
    const { blueprint } = read({ pawprint: 1, name: 'n', palette: { '🧱': 'minecraft:bricks' }, layers: { grid: [['🧱.🧱']] } });
    expect(cells(blueprint)).toEqual({ '0,0,0': 'minecraft:bricks', '2,0,0': 'minecraft:bricks' });
  });

  it('says exactly what is wrong, for the AI to fix', () => {
    fails('no json here', /No JSON object found/);
    fails('{"pawprint": 1, "name": "x", }', /^Invalid JSON: /);
    fails({ pawprint: 2, name: 'x', layers: { grid: [] } }, /"pawprint" must be 1, got 2/);
    fails({ pawprint: 1, name: ' ', layers: { grid: [] } }, /"name" must not be empty/);
    fails({ pawprint: 1, name: 'x' }, /Either "operations" or "layers" is required/);
    fails({ pawprint: 1, name: 'x', layers: { grid: [['...']] } }, /contains no blocks/);
    fails({ pawprint: 1, name: 'x', palette: { ab: 'stone' }, layers: { grid: [] } }, /palette "ab": palette keys must be exactly one character/);
    fails({ pawprint: 1, name: 'x', palette: { '.': 'stone' }, layers: { grid: [] } }, /reserved/);
    fails({ pawprint: 1, name: 'x', palette: { S: 'stone' }, layers: { grid: [['SS'], ['S?']] } }, /layers\.grid\[1\]\[0\] column 1: character "\?" is not in the palette/);
    fails({ pawprint: 1, name: 'x', operations: [{ shape: 'box', from: [0, 0], to: [1, 1, 1], block: 'minecraft:stone' }] }, /operations\[0\]: "from" must be an array of three integers/);
    fails({ pawprint: 1, name: 'x', operations: [{ shape: 'pyramid', block: 'minecraft:stone' }] }, /unknown shape "pyramid"/);
    fails({ pawprint: 1, name: 'x', operations: [{ shape: 'single', at: [0, 0, 0], block: 'stone' }] }, /neither a palette key nor a block ID/);
    fails({ pawprint: 1, name: 'x', operations: [{ shape: 'single', at: [0, 0, 0], block: 'minecraft:Stone Bricks' }] }, /is not a valid block ID/);
    fails({ pawprint: 1, name: 'x', operations: [{ shape: 'box', from: [0, 0, 0], to: [99, 99, 99], block: 'minecraft:stone' }] }, /shape covers about 1000000 blocks; the limit is 262144/);
    fails({ pawprint: 1, name: 'x', operations: [{ shape: 'cylinder', base: [0, 0, 0], radius: 2, height: 0, block: 'minecraft:stone' }] }, /"height" must be at least 1/);
    fails({ pawprint: 1, name: 'x', operations: [{ shape: 'single', at: [0, 2000, 0], block: 'minecraft:stone' }] }, /out of range/);
    const error = new TextFormatError('operations[0]: bad');
    expect(fixRequest(error)).toBe('The Pawprint importer rejected the JSON: operations[0]: bad\nPlease fix it and reply with the corrected JSON only.');
  });

  describe('with a block pack', () => {
    const def = (id: string, properties: Record<string, string[]> = {}): BlockDef => ({
      id, properties, default: {}, item: id, renderLayer: 'solid', renderShape: 'model', tint: null, tabs: [],
    });
    const blocks = new Map([def('minecraft:stone'), def('minecraft:oak_stairs', { facing: ['north', 'south', 'west', 'east'], half: ['top', 'bottom'] })].map((b) => [b.id, b]));
    const build = (block: string) => ({ pawprint: 1, name: 'x', operations: [{ shape: 'single', at: [0, 0, 0], block }] });

    it('keeps blocks the pack does not have and says so', () => {
      const { blueprint, warnings } = read(build('create:andesite_casing'), { blocks, complete: true });
      expect(cells(blueprint)).toEqual({ '0,0,0': 'create:andesite_casing' });
      expect(warnings).toEqual(['Unknown block kept as is (not in the block pack): create:andesite_casing']);
      expect(read(build('minecraft:stone'), { blocks, complete: true }).warnings).toEqual([]);
    });

    it('checks states like the game when the pack lists every property', () => {
      fails(build('minecraft:oak_stairs[facing=up]'), /invalid state for minecraft:oak_stairs.*"facing" cannot be "up" \(use north, south, west, east\)/, { blocks, complete: true });
      fails(build('minecraft:oak_stairs[waterlogged=false]'), /has no property "waterlogged"/, { blocks, complete: true });
      // A pack made without the game may miss properties: nothing is refused then.
      expect(read(build('minecraft:oak_stairs[waterlogged=false]'), { blocks, complete: false }).warnings).toEqual([]);
    });
  });
});
