import { describe, expect, it } from 'vitest';
import { EditableBlueprint, newMeta } from '../src/core/blueprint/editable';
import { raycast } from '../src/core/edit/raycast';
import { mirrorState, rotateState } from '../src/core/edit/transform';
import { fillPlane, planeFromHit, shapeCells, Stroke, type ToolSettings } from '../src/core/edit/tools';
import { blocksIn, boxOf, clearBox, copy, mirrorClip, paste, replaceInBox, rotateClip } from '../src/core/edit/clip';
import { writePawprint } from '../src/core/format/pawprint';

const settings = (over: Partial<ToolSettings> = {}): ToolSettings => ({
  tool: 'rect', block: 'minecraft:stone', brushShape: 'square', brushSize: 1, filled: true, height: 3, ...over,
});

const fixed = (bp: EditableBlueprint) => {
  bp.meta = { ...bp.meta, id: 'same', created: 'c', modified: 'm' };
  return writePawprint(bp.toBlueprint());
};

describe('raycast', () => {
  const solid = (x: number, y: number, z: number) => x === 2 && y === 0 && z === 0;
  it('finds the first solid cell and the face it entered', () => {
    expect(raycast([0.5, 0.5, 0.5], [1, 0, 0], 10, solid)).toEqual({ cell: [2, 0, 0], normal: [-1, 0, 0], distance: 1.5 });
    expect(raycast([2.5, 5.5, 0.5], [0, -1, 0], 10, solid)?.normal).toEqual([0, 1, 0]);
    expect(raycast([0.5, 0.5, 0.5], [-1, 0, 0], 10, solid)).toBeNull();
  });
});

describe('block state transforms', () => {
  it('turns facing, axis, rotation and sides clockwise', () => {
    expect(rotateState('minecraft:oak_stairs[facing=north,half=bottom]', 1)).toBe('minecraft:oak_stairs[facing=east,half=bottom]');
    expect(rotateState('minecraft:oak_log[axis=x]', 1)).toBe('minecraft:oak_log[axis=z]');
    expect(rotateState('minecraft:oak_sign[rotation=15]', 1)).toBe('minecraft:oak_sign[rotation=3]');
    expect(rotateState('minecraft:oak_fence[east=false,north=true,south=false,west=false]', 1)).toBe('minecraft:oak_fence[east=true,north=false,south=false,west=false]');
    expect(rotateState('minecraft:stone', 1)).toBe('minecraft:stone');
  });

  it('mirrors and swaps left/right shapes', () => {
    expect(mirrorState('minecraft:oak_stairs[facing=east,shape=inner_left]', 'x')).toBe('minecraft:oak_stairs[facing=west,shape=inner_right]');
    expect(mirrorState('minecraft:oak_stairs[facing=east,shape=straight]', 'z')).toBe('minecraft:oak_stairs[facing=east,shape=straight]');
    expect(mirrorState('minecraft:oak_door[facing=north,hinge=left]', 'z')).toBe('minecraft:oak_door[facing=south,hinge=right]');
  });
});

describe('2D and 3D give the same result', () => {
  it('a rectangle drawn on a floor slice equals one drawn on the top face of blocks below', () => {
    const in2d = new EditableBlueprint(newMeta('t'));
    const in3d = new EditableBlueprint(newMeta('t'));
    // 2D: plan view, slice y = 3.
    const s2 = new Stroke(in2d, settings(), { plane: 'y', slice: 3, extrude: 1 }, [0, 0]);
    s2.move([4, 2]);
    s2.finish();
    // 3D: the cursor is on the top face of a block at y = 2; the plane is the layer above it.
    const a = planeFromHit([0, 2, 0], [0, 1, 0], true);
    const b = planeFromHit([4, 2, 2], [0, 1, 0], true);
    expect(a.ref).toEqual({ plane: 'y', slice: 3, extrude: 1 });
    const s3 = new Stroke(in3d, settings(), a.ref, a.uv);
    s3.move(b.uv);
    s3.finish();
    expect(fixed(in3d)).toEqual(fixed(in2d));
  });

  it('works on walls too: a front elevation equals painting on a south-facing face', () => {
    const in2d = new EditableBlueprint(newMeta('t'));
    const in3d = new EditableBlueprint(newMeta('t'));
    const s2 = new Stroke(in2d, settings({ tool: 'pencil', brushSize: 3, brushShape: 'circle' }), { plane: 'z', slice: 5, extrude: 1 }, [2, -4]);
    s2.finish();
    const hit = planeFromHit([2, 4, 4], [0, 0, 1], true);
    expect(hit.target).toEqual([2, 4, 5]);
    const s3 = new Stroke(in3d, settings({ tool: 'pencil', brushSize: 3, brushShape: 'circle' }), hit.ref, hit.uv);
    s3.finish();
    expect(fixed(in3d)).toEqual(fixed(in2d));
  });
});

describe('3D shapes', () => {
  const ref = { plane: 'y' as const, slice: 0, extrude: 1 as const };
  it('box, hollow box, wall, cylinder and sphere', () => {
    const count = (tool: ToolSettings['tool'], filled = true) => shapeCells(settings({ tool, filled, height: 3 }), ref, [0, 0], [2, 2]).length;
    expect(count('box')).toBe(27);
    expect(count('hollow')).toBe(26);
    expect(count('wall')).toBe(24);
    const big = (tool: ToolSettings['tool']) => shapeCells(settings({ tool, height: 5 }), ref, [0, 0], [4, 4]).length;
    expect(big('cylinder')).toBeLessThan(big('box'));
    expect(big('sphere')).toBeLessThan(big('cylinder'));
    const ys = shapeCells(settings({ tool: 'box', height: 3 }), { plane: 'y', slice: 5, extrude: -1 }, [0, 0], [0, 0]).map((c) => c[1]);
    expect(ys).toEqual([5, 4, 3]);
  });

  it('fills a closed area on a face plane', () => {
    const bp = new EditableBlueprint(newMeta('t'));
    const ring = new Stroke(bp, settings({ filled: false, block: 'minecraft:stone_bricks' }), ref, [0, 0]);
    ring.move([4, 4]);
    ring.finish();
    expect(fillPlane(bp, settings({ tool: 'fill', block: 'minecraft:oak_planks' }), ref, [2, 2])).toBe(true);
    expect(bp.stateAt(2, 0, 2)).toBe('minecraft:oak_planks');
    expect(bp.stateAt(0, 0, 0)).toBe('minecraft:stone_bricks');
  });
});

describe('selection and clipboard', () => {
  function sample(): EditableBlueprint {
    const bp = new EditableBlueprint(newMeta('t'));
    bp.begin('x');
    bp.set(0, 0, 0, bp.stateIndex('minecraft:oak_stairs[facing=north,half=bottom]') + 1);
    bp.set(1, 0, 0, bp.stateIndex('minecraft:oak_planks') + 1);
    bp.set(1, 1, 0, bp.stateIndex('minecraft:oak_planks') + 1);
    bp.commit();
    return bp;
  }

  it('copies, turns and pastes with block states turned too', () => {
    const bp = sample();
    const clip = copy(bp, boxOf([0, 0, 0], [1, 1, 0]));
    expect(clip.size).toEqual([2, 2, 1]);
    const turned = rotateClip(clip, 1);
    expect(turned.size).toEqual([1, 2, 2]);
    bp.begin('paste');
    paste(bp, turned, [10, 0, 10]);
    bp.commit();
    expect(bp.stateAt(10, 0, 10)).toBe('minecraft:oak_stairs[facing=east,half=bottom]');
    expect(bp.stateAt(10, 0, 11)).toBe('minecraft:oak_planks');
    expect(rotateClip(turned, 3)).toEqual(clip);
    expect(mirrorClip(mirrorClip(clip, 'x'), 'x')).toEqual(clip);
  });

  it('replaces one block kind keeping shared properties, then clears', () => {
    const bp = sample();
    const box = boxOf([0, 0, 0], [1, 1, 0]);
    expect(blocksIn(bp, box)).toEqual([{ id: 'minecraft:oak_planks', count: 2 }, { id: 'minecraft:oak_stairs', count: 1 }]);
    bp.begin('replace');
    expect(replaceInBox(bp, box, 'minecraft:oak_stairs', 'minecraft:stone_stairs[facing=south,half=top]', () => true)).toBe(1);
    bp.commit();
    expect(bp.stateAt(0, 0, 0)).toBe('minecraft:stone_stairs[facing=north,half=bottom]');
    bp.begin('clear');
    clearBox(bp, box);
    bp.commit();
    expect(bp.blockCount).toBe(0);
    bp.undo();
    expect(bp.blockCount).toBe(3);
  });
});
