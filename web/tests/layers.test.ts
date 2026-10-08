import { describe, expect, it } from 'vitest';
import { EditableBlueprint, newMeta } from '../src/core/blueprint/editable';
import { addLayer, hiddenLayers, mergeInto, move, protectedLayers, remove, rows, update } from '../src/core/blueprint/layers';
import { readPawprint, writePawprint } from '../src/core/format/pawprint';
import { countMaterials, itemsPerBlock } from '../src/core/materials/materials';
import type { BlockDef } from '../src/core/pack/types';

function sample() {
  const bp = new EditableBlueprint(newMeta('t'));
  const walls = addLayer(bp, 'Walls', null);
  const floor = addLayer(bp, 'Floor 1', null, true);
  const roof = addLayer(bp, 'Roof', floor);
  const stone = bp.stateIndex('minecraft:stone') + 1;
  bp.begin('x');
  bp.currentLayer = walls;
  bp.set(0, 0, 0, stone);
  bp.set(1, 0, 0, stone);
  bp.currentLayer = roof;
  bp.set(0, 5, 0, stone);
  bp.commit();
  bp.currentLayer = 0;
  return { bp, walls, floor, roof };
}

describe('layer tree', () => {
  it('lists rows top to bottom with groups and depth', () => {
    const { bp } = sample();
    expect(rows(bp).map((r) => [r.layer.name, r.depth])).toEqual([['Floor 1', 0], ['Roof', 1], ['Walls', 0], ['Default', 0]]);
    expect(bp.layerCounts()).toEqual(new Map([[1, 2], [3, 1]]));
  });

  it('hides and locks through groups; solo shows one layer', () => {
    const { bp, floor, roof, walls } = sample();
    update(bp, floor, { visible: false });
    expect(hiddenLayers(bp, null)).toEqual(new Set([roof]));
    expect(protectedLayers(bp)).toEqual(new Set([roof]));
    update(bp, floor, { visible: true });
    update(bp, walls, { locked: true });
    expect(protectedLayers(bp)).toEqual(new Set([walls]));
    expect(hiddenLayers(bp, walls)).toEqual(new Set([0, roof]));
  });

  it('moves layers between levels but never a group into itself', () => {
    const { bp, walls, floor, roof } = sample();
    move(bp, walls, floor, true);
    expect(rows(bp).map((r) => r.layer.name)).toEqual(['Floor 1', 'Walls', 'Roof', 'Default']);
    move(bp, floor, roof, false);
    expect(bp.layerOrder).toContain(floor);
    move(bp, roof, 0, false);
    expect(bp.layerOrder.indexOf(roof)).toBe(bp.layerOrder.indexOf(0) - 1);
  });

  it('merges blocks into another layer and deletes with undo', () => {
    const { bp, walls, roof } = sample();
    mergeInto(bp, roof, walls);
    expect(bp.layers.some((l) => l.id === roof)).toBe(false);
    expect(bp.layerAt(0, 5, 0)).toBe(walls);
    remove(bp, walls);
    expect(bp.blockCount).toBe(0);
    bp.undo();
    expect(bp.blockCount).toBe(3);
  });

  it('round-trips through format 2', () => {
    const { bp, roof } = sample();
    const back = EditableBlueprint.fromBlueprint(readPawprint(writePawprint(bp.toBlueprint())));
    expect(back.meta.format).toBe(2);
    expect(rows(back).map((r) => r.layer.name)).toEqual(['Floor 1', 'Roof', 'Walls', 'Default']);
    expect(back.layerAt(0, 5, 0)).toBe(roof);
  });
});


describe('materials', () => {
  it('counts like the mod', () => {
    expect(itemsPerBlock('minecraft:oak_door[facing=north,half=upper]')).toBe(0);
    expect(itemsPerBlock('minecraft:oak_door[facing=north,half=lower]')).toBe(1);
    expect(itemsPerBlock('minecraft:stone_slab[type=double]')).toBe(2);
    expect(itemsPerBlock('minecraft:candle[candles=3,lit=false]')).toBe(3);
    expect(itemsPerBlock('minecraft:red_bed[part=head]')).toBe(0);
  });

  it('totals per layer, maps wall blocks to items and lists blocks without items', () => {
    const { bp, walls } = sample();
    bp.begin('more');
    bp.currentLayer = walls;
    bp.set(3, 0, 0, bp.stateIndex('minecraft:wall_torch[facing=north]') + 1);
    bp.set(4, 0, 0, bp.stateIndex('minecraft:fire') + 1);
    bp.commit();
    const def = (id: string, item: string | null) => [id, { id, item } as BlockDef] as const;
    const blocks = new Map([def('minecraft:stone', 'minecraft:stone'), def('minecraft:torch', 'minecraft:torch'), def('minecraft:wall_torch', null), def('minecraft:fire', null)]);
    const all = countMaterials(bp, () => true, blocks);
    expect(all.lines).toEqual([{ id: 'minecraft:stone', count: 3 }, { id: 'minecraft:torch', count: 1 }]);
    expect(all.noItem).toEqual([{ id: 'minecraft:fire', count: 1 }]);
    const onlyWalls = countMaterials(bp, (_x, _y, _z, layer) => layer === walls, blocks);
    expect(onlyWalls.lines).toEqual([{ id: 'minecraft:stone', count: 2 }, { id: 'minecraft:torch', count: 1 }]);
  });
});
