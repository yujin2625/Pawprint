import { EMPTY, type EditableBlueprint } from '../blueprint/editable';
import { formatState, parseState } from '../model/state';
import { mirrorState, rotateState } from './transform';
import type { World } from './tools';

/** Selections (boxes) and the clipboard: copy, cut, paste, turn, mirror, fill, replace. */

export interface Box {
  min: World;
  max: World;
}

export function boxOf(a: World, b: World): Box {
  return { min: [Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2])], max: [Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2])] };
}

export function boxSize(box: Box): World {
  return [box.max[0] - box.min[0] + 1, box.max[1] - box.min[1] + 1, box.max[2] - box.min[2] + 1];
}

function* cellsIn(box: Box): Generator<World> {
  for (let y = box.min[1]; y <= box.max[1]; y++)
    for (let z = box.min[2]; z <= box.max[2]; z++)
      for (let x = box.min[0]; x <= box.max[0]; x++) yield [x, y, z];
}

/** Blocks relative to the clip's minimum corner. */
export interface Clip {
  size: World;
  cells: { p: World; state: string }[];
}

export function copy(bp: EditableBlueprint, box: Box): Clip {
  const cells: Clip['cells'] = [];
  for (const [x, y, z] of cellsIn(box)) {
    const state = bp.stateAt(x, y, z);
    if (state) cells.push({ p: [x - box.min[0], y - box.min[1], z - box.min[2]], state });
  }
  return { size: boxSize(box), cells };
}

/** Clears every cell in the box (one undo step when called inside a batch). */
export function clearBox(bp: EditableBlueprint, box: Box): void {
  for (const [x, y, z] of cellsIn(box)) bp.set(x, y, z, EMPTY);
}

export function fillBox(bp: EditableBlueprint, box: Box, state: string): void {
  const value = bp.stateIndex(state) + 1;
  for (const [x, y, z] of cellsIn(box)) bp.set(x, y, z, value);
}

/**
 * Swaps every block of one kind for another inside the box, carrying over properties both share (stairs keep
 * their facing), like the mod's replace. `keep` decides which properties the new block accepts.
 */
export function replaceInBox(bp: EditableBlueprint, box: Box, fromId: string, toState: string, keep: (prop: string) => boolean): number {
  const target = parseState(toState);
  let count = 0;
  for (const [x, y, z] of cellsIn(box)) {
    const state = bp.stateAt(x, y, z);
    if (!state) continue;
    const { id, props } = parseState(state);
    if (id !== fromId) continue;
    const merged = { ...target.props };
    for (const [k, v] of Object.entries(props)) if (keep(k)) merged[k] = v;
    bp.set(x, y, z, bp.stateIndex(formatState(target.id, merged)) + 1, true);
    count++;
  }
  return count;
}

/** Places a clip with its minimum corner at `origin`. Empty cells in the clip leave the target alone. */
export function paste(bp: EditableBlueprint, clip: Clip, origin: World): void {
  for (const { p, state } of clip.cells) bp.set(origin[0] + p[0], origin[1] + p[1], origin[2] + p[2], bp.stateIndex(state) + 1);
}

/** Turns a clip clockwise (seen from above) by quarter turns, blocks included. */
export function rotateClip(clip: Clip, quarters: number): Clip {
  const q = ((quarters % 4) + 4) % 4;
  let out = clip;
  for (let i = 0; i < q; i++) {
    const [sx, sy, sz] = out.size;
    // Clockwise from above: x' = (sz - 1) - z, z' = x.
    out = { size: [sz, sy, sx], cells: out.cells.map(({ p, state }) => ({ p: [sz - 1 - p[2], p[1], p[0]], state: rotateState(state, 1) })) };
  }
  return out;
}

export function mirrorClip(clip: Clip, axis: 'x' | 'z'): Clip {
  const [sx, , sz] = clip.size;
  return {
    size: clip.size,
    cells: clip.cells.map(({ p, state }) => ({
      p: axis === 'x' ? [sx - 1 - p[0], p[1], p[2]] : [p[0], p[1], sz - 1 - p[2]],
      state: mirrorState(state, axis),
    })),
  };
}

/** Distinct block IDs in a box, most common first (for "replace what"). */
export function blocksIn(bp: EditableBlueprint, box: Box): { id: string; count: number }[] {
  const counts = new Map<string, number>();
  for (const [x, y, z] of cellsIn(box)) {
    const state = bp.stateAt(x, y, z);
    if (state) {
      const id = parseState(state).id;
      counts.set(id, (counts.get(id) ?? 0) + 1);
    }
  }
  return [...counts].map(([id, count]) => ({ id, count })).sort((a, b) => b.count - a.count);
}
