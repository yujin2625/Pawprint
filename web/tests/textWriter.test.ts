import { describe, expect, it } from 'vitest';
import type { Blueprint, PawprintMeta } from '../src/core/format/pawprint';
import { aiEditPrompt, readTextBlueprint, writeTextBlueprint } from '../src/core/format/textBlueprint';

const meta = (): PawprintMeta => ({
  format: 1, id: 'new-id', name: '', description: '', author: 'me', tags: [], created: '2026-10-12T00:00:00Z', modified: '2026-10-12T00:00:00Z',
  mcVersion: '', dataVersion: 0, size: [0, 0, 0], blockCount: 0, removalCount: 0, mods: [], blocks: [],
});

function cells(bp: Blueprint): Record<string, string> {
  const out: Record<string, string> = {};
  for (let i = 0; i < bp.states.length; i++) out[[bp.positions[i * 3], bp.positions[i * 3 + 1], bp.positions[i * 3 + 2]].join(',')] = bp.palette[bp.states[i]!]!;
  for (let i = 0; i < bp.removals.length; i += 3) out[[bp.removals[i], bp.removals[i + 1], bp.removals[i + 2]].join(',')] = 'air';
  return out;
}

const read = (text: string) => readTextBlueprint(text, meta()).blueprint;

describe('blueprints written as text', () => {
  it('writes layers that read back to the same blocks and empty spots', () => {
    const bp = read(JSON.stringify({
      pawprint: 1, name: 'Hut', description: 'Small', tags: ['test'],
      operations: [
        { shape: 'hollow_box', from: [0, 0, 0], to: [4, 3, 3], block: 'minecraft:oak_planks' },
        { shape: 'single', at: [2, 1, 0], block: 'air' },
        { shape: 'single', at: [2, 0, 1], block: 'minecraft:oak_stairs[facing=east,half=bottom]' },
      ],
    }));
    const text = writeTextBlueprint(bp);
    const json = JSON.parse(text);
    expect(json).toMatchObject({ pawprint: 1, name: 'Hut', description: 'Small', tags: ['test'] });
    expect(json.palette).toMatchObject({ _: 'air' });
    expect(json.operations).toBeUndefined();
    // Rows lose their trailing "."; the bottom layer starts with the north wall.
    expect(json.layers.grid[0][0]).toBe('#####');
    expect(cells(read(text))).toEqual(cells(bp));
  });

  it('takes characters past the readable ones when a build has many kinds of blocks', () => {
    const operations = Array.from({ length: 100 }, (_, i) => ({ shape: 'single', at: [i, 0, 0], block: `test:block_${i}` }));
    const bp = read(JSON.stringify({ pawprint: 1, name: 'Many', operations }));
    const json = JSON.parse(writeTextBlueprint(bp));
    expect(Object.keys(json.palette)).toHaveLength(100);
    expect(Object.keys(json.palette)).toContain('À');
    expect(cells(read(writeTextBlueprint(bp)))).toEqual(cells(bp));
  });

  it('asks an AI to change a build, with the build and the rules', () => {
    const bp = read(JSON.stringify({ pawprint: 1, name: 'Wall', operations: [{ shape: 'line', from: [0, 0, 0], to: [2, 0, 0], block: 'create:brass_casing' }] }));
    const text = aiEditPrompt('1.20.1', ['create'], bp);
    expect(text).toContain('Only use blocks that exist in Minecraft 1.20.1 and these mods (namespaces): create.');
    expect(text).toContain('Here is the current build in this format.');
    expect(text).toContain('"#": "create:brass_casing"');
    expect(text.endsWith('```\n\nChange request: ')).toBe(true);
    expect(text).not.toContain('Build request:');
  });
});
