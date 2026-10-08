import { describe, expect, it } from 'vitest';
import { EditableBlueprint, EMPTY, REMOVAL, newMeta } from '../src/core/blueprint/editable';
import { brush, ellipse, flood, line, rect, toSlice, toWorld, type Plane } from '../src/core/edit/shapes';
import { buildIndex, chosung, search } from '../src/core/search/blockSearch';
import { readPawprint, writePawprint, DEFAULT_LAYER } from '../src/core/format/pawprint';
import type { BlockDef } from '../src/core/pack/types';

describe('EditableBlueprint', () => {
  it('edits, undoes and redoes in batches', () => {
    const bp = new EditableBlueprint(newMeta('t'));
    const stone = bp.stateIndex('minecraft:stone') + 1;
    const changed: number[] = [];
    bp.onChange((cells) => changed.push(cells.length / 3));
    bp.begin('paint');
    bp.set(0, 0, 0, stone);
    bp.set(-3, 20, 5, stone);
    bp.set(0, 0, 0, stone); // no change
    expect(bp.commit()).toBe(true);
    expect(bp.get(-3, 20, 5)).toBe(stone);
    expect(bp.blockCount).toBe(2);
    expect(bp.bounds()).toEqual({ min: [-3, 0, 0], max: [0, 20, 5] });
    bp.undo();
    expect(bp.get(-3, 20, 5)).toBe(EMPTY);
    expect(bp.blockCount).toBe(0);
    expect(bp.bounds()).toBeNull();
    bp.redo();
    expect(bp.blockCount).toBe(2);
    expect(changed).toEqual([2, 2, 2]);
  });

  it('moves a painted cell to the current layer; block swaps keep it', () => {
    const bp = new EditableBlueprint(newMeta('t'));
    const a = bp.stateIndex('a:a') + 1, b = bp.stateIndex('a:b') + 1;
    bp.currentLayer = 2;
    bp.begin('x');
    bp.set(1, 1, 1, a);
    bp.set(2, 1, 1, a);
    bp.commit();
    bp.currentLayer = 0;
    bp.begin('y');
    bp.set(1, 1, 1, b);
    bp.set(2, 1, 1, b, true);
    bp.commit();
    expect(bp.layerAt(1, 1, 1)).toBe(0);
    expect(bp.layerAt(2, 1, 1)).toBe(2);
    // Painting the same block onto another layer's cell still moves it.
    bp.currentLayer = 3;
    bp.begin('z');
    bp.set(1, 1, 1, b);
    bp.commit();
    expect(bp.layerAt(1, 1, 1)).toBe(3);
    bp.undo();
    expect(bp.layerAt(1, 1, 1)).toBe(0);
  });

  it('leaves protected layers alone and moves blocks between layers', () => {
    const bp = new EditableBlueprint(newMeta('t'));
    const a = bp.stateIndex('a:a') + 1;
    bp.currentLayer = 1;
    bp.begin('x');
    bp.set(0, 0, 0, a);
    bp.commit();
    bp.protectedLayers = new Set([1]);
    bp.currentLayer = 0;
    bp.begin('erase');
    bp.set(0, 0, 0, EMPTY);
    bp.commit();
    expect(bp.get(0, 0, 0)).toBe(a);
    bp.currentLayer = 1;
    bp.begin('paint');
    bp.set(5, 0, 0, a);
    bp.commit();
    expect(bp.get(5, 0, 0)).toBe(EMPTY);
    bp.protectedLayers = new Set();
    bp.begin('move');
    bp.setLayer(0, 0, 0, 4);
    bp.commit();
    expect(bp.layerAt(0, 0, 0)).toBe(4);
    expect(bp.layerCounts()).toEqual(new Map([[4, 1]]));
    bp.undo();
    expect(bp.layerAt(0, 0, 0)).toBe(1);
  });

  it('round-trips through .pawprint, moving the minimum corner to 0 and dropping unused palette entries', () => {
    const bp = new EditableBlueprint({ ...newMeta('t'), origin: { server: 's', dimension: 'd', pos: [100, 64, 100] } });
    bp.layers = [{ ...DEFAULT_LAYER }, { id: 1, name: 'Roof', color: '#FFB347', visible: true, locked: false, parent: null }];
    bp.layerOrder = [1, 0];
    const stone = bp.stateIndex('minecraft:stone') + 1;
    bp.stateIndex('minecraft:unused');
    bp.begin('x');
    bp.set(-2, 5, 3, stone);
    bp.currentLayer = 1;
    bp.set(0, 6, 3, REMOVAL);
    bp.commit();
    const back = readPawprint(writePawprint(bp.toBlueprint()));
    expect(back.palette).toEqual(['minecraft:stone']);
    expect([...back.positions]).toEqual([0, 0, 0]);
    expect([...back.removals]).toEqual([2, 1, 0]);
    expect([...back.removalLayers]).toEqual([1]);
    expect(back.meta.origin?.pos).toEqual([98, 69, 103]);
    const again = EditableBlueprint.fromBlueprint(back);
    expect(again.get(0, 0, 0)).toBe(again.stateIndex('minecraft:stone') + 1);
    expect(again.get(2, 1, 0)).toBe(REMOVAL);
  });

  it('abort undoes an unfinished batch without history', () => {
    const bp = new EditableBlueprint(newMeta('t'));
    const s = bp.stateIndex('a:a') + 1;
    bp.begin('drag');
    bp.set(0, 0, 0, s);
    bp.abort();
    expect(bp.get(0, 0, 0)).toBe(EMPTY);
    expect(bp.canUndo).toBe(false);
  });
});

describe('shapes', () => {
  it('brushes', () => {
    expect(brush(0, 0, 'square', 1)).toEqual([[0, 0]]);
    expect(brush(0, 0, 'square', 3)).toHaveLength(9);
    expect(brush(0, 0, 'diamond', 3)).toHaveLength(5);
    expect(brush(0, 0, 'circle', 5).length).toBeGreaterThan(13);
    expect(brush(0, 0, 'circle', 5).length).toBeLessThan(25);
  });

  it('lines, rectangles and ellipses', () => {
    expect(line([0, 0], [3, 1])).toEqual([[0, 0], [1, 0], [2, 1], [3, 1]]);
    expect(rect([0, 0], [2, 2], false)).toHaveLength(8);
    expect(rect([2, 2], [0, 0], true)).toHaveLength(9);
    const filled = ellipse([0, 0], [6, 4], true);
    const outline = ellipse([0, 0], [6, 4], false);
    expect(outline.length).toBeLessThan(filled.length);
    expect(filled).toContainEqual([3, 2]);
    expect(outline).not.toContainEqual([3, 2]);
  });

  it('flood fills a bounded area and gives up past the limit', () => {
    const wall = (u: number, v: number) => (u === 3 || v === 3 ? 1 : 0);
    expect(flood([0, 0], wall, { u0: 0, v0: 0, u1: 10, v1: 10 })).toHaveLength(9);
    expect(flood([0, 0], () => 0, { u0: -1000, v0: -1000, u1: 1000, v1: 1000 }, 100)).toBeNull();
  });

  it('maps slice coordinates to the world and back', () => {
    for (const plane of ['y', 'z', 'x'] as Plane[]) {
      const [x, y, z] = toWorld(plane, 7, 3, -2);
      expect(toSlice(plane, x, y, z)).toEqual({ slice: 7, u: 3, v: -2 });
    }
    expect(toWorld('z', 0, 1, -5)).toEqual([1, 5, 0]); // v up is y up
  });
});

describe('block search', () => {
  const blocks = ['minecraft:oak_planks', 'minecraft:oak_stairs', 'minecraft:stone', 'minecraft:spruce_planks'].map(
    (id) => ({ id }) as BlockDef,
  );
  const index = buildIndex(blocks, {
    en_us: { 'minecraft:oak_planks': 'Oak Planks', 'minecraft:oak_stairs': 'Oak Stairs', 'minecraft:stone': 'Stone', 'minecraft:spruce_planks': 'Spruce Planks' },
    ko_kr: { 'minecraft:oak_planks': '참나무 판자', 'minecraft:oak_stairs': '참나무 계단', 'minecraft:stone': '돌', 'minecraft:spruce_planks': '가문비나무 판자' },
  });

  it('extracts initial consonants', () => {
    expect(chosung('참나무 판자')).toBe('ㅊㄴㅁ ㅍㅈ');
  });

  it('finds by English, Korean, ID and initials regardless of UI language', () => {
    expect(search(index, 'planks')).toEqual(['minecraft:oak_planks', 'minecraft:spruce_planks']);
    expect(search(index, '판자')).toEqual(['minecraft:oak_planks', 'minecraft:spruce_planks']);
    expect(search(index, 'ㅊㄴㅁ')).toEqual(['minecraft:oak_planks', 'minecraft:oak_stairs']);
    expect(search(index, 'ㅍㅈ')).toEqual(['minecraft:oak_planks', 'minecraft:spruce_planks']);
    expect(search(index, 'minecraft:stone')).toEqual(['minecraft:stone']);
    expect(search(index, 'st')[0]).toBe('minecraft:stone');
  });
});
