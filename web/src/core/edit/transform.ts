import { formatState, parseState } from '../model/state';

/**
 * Turning and mirroring block states the way the game's Rotation and Mirror do for common properties: facing,
 * axis, 16-step rotation, fence/wall/pane sides, stair shapes, door hinges.
 */

const HORIZONTAL = ['north', 'east', 'south', 'west'];
const SIDES = ['north', 'east', 'south', 'west'];

/** Turns clockwise (seen from above) by quarter turns. */
export function rotateState(state: string, quarters: number): string {
  const q = ((quarters % 4) + 4) % 4;
  if (q === 0) return state;
  const { id, props } = parseState(state);
  const out: Record<string, string> = { ...props };
  for (const key of ['facing', 'horizontal_facing']) {
    const i = HORIZONTAL.indexOf(props[key] ?? '');
    if (i >= 0) out[key] = HORIZONTAL[(i + q) % 4]!;
  }
  if (props.axis === 'x' || props.axis === 'z') {
    if (q % 2 === 1) out.axis = props.axis === 'x' ? 'z' : 'x';
  }
  if (props.rotation !== undefined && /^\d+$/.test(props.rotation)) out.rotation = String((Number(props.rotation) + q * 4) % 16);
  // Sides (fences, panes, walls, redstone, vines): the value moves to the turned direction.
  const sides = SIDES.filter((s) => props[s] !== undefined);
  if (sides.length) {
    for (const s of SIDES) if (props[s] !== undefined) delete out[s];
    for (const s of sides) out[SIDES[(SIDES.indexOf(s) + q) % 4]!] = props[s]!;
  }
  return formatState(id, order(props, out));
}

/** Mirrors across the X axis ('x': east ↔ west) or the Z axis ('z': north ↔ south). */
export function mirrorState(state: string, axis: 'x' | 'z'): string {
  const { id, props } = parseState(state);
  const out: Record<string, string> = { ...props };
  const swap = axis === 'x' ? { east: 'west', west: 'east' } : { north: 'south', south: 'north' };
  for (const key of ['facing', 'horizontal_facing']) {
    const v = props[key];
    if (v && v in swap) out[key] = swap[v as keyof typeof swap]!;
  }
  // Side values (fences, panes, walls) swap places.
  for (const [from, to] of Object.entries(swap)) {
    if (props[from] !== undefined) out[to] = props[from]!;
    else if (props[to] !== undefined) delete out[to];
  }
  if (props.rotation !== undefined && /^\d+$/.test(props.rotation)) {
    const r = Number(props.rotation);
    out.rotation = String(axis === 'x' ? (16 - r) % 16 : (24 - r) % 16);
  }
  // Mirroring swaps left and right.
  if (props.shape) out.shape = props.shape.replace(/left|right/, (m) => (m === 'left' ? 'right' : 'left'));
  if (props.hinge) out.hinge = props.hinge === 'left' ? 'right' : 'left';
  return formatState(id, order(props, out));
}

/** Keeps the original property order so the strings stay comparable. */
function order(original: Record<string, string>, values: Record<string, string>): Record<string, string> {
  const out: Record<string, string> = {};
  for (const k of Object.keys(original)) if (values[k] !== undefined) out[k] = values[k]!;
  for (const k of Object.keys(values)) if (out[k] === undefined) out[k] = values[k]!;
  return out;
}
