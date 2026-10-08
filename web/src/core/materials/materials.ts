import type { EditableBlueprint } from '../blueprint/editable';
import type { BlockDef } from '../pack/types';
import { parseState } from '../model/state';

/**
 * Materials needed to build, counted like the mod does: the upper half of doors, beds and tall plants is free,
 * double slabs take two, candles and sea pickles count what they hold, wall-mounted blocks use their item.
 */

export interface MaterialLine {
  /** Item to gather (or the block ID when it has no item). */
  id: string;
  count: number;
}

export interface Materials {
  lines: MaterialLine[];
  /** Blocks that cannot be placed from an item (fire, portals, …). */
  noItem: MaterialLine[];
  total: number;
}

const MULTI: Record<string, string> = { candles: 'candles', pickles: 'pickles', eggs: 'eggs', flower_amount: 'flower_amount', segment_amount: 'segment_amount' };

/** How many items one block state needs (0 for the second half of two-block blocks). */
export function itemsPerBlock(state: string): number {
  const { id, props } = parseState(state);
  if (props.half === 'upper' || props.part === 'head') return 0;
  if (props.type === 'double' && id.endsWith('_slab')) return 2;
  for (const key of Object.keys(MULTI)) {
    if (props[key] !== undefined && /^\d+$/.test(props[key]!)) return Number(props[key]);
  }
  return 1;
}

/** The item that places a block, guessing wall variants (wall_torch → torch) when the pack has no item. */
export function itemFor(id: string, blocks: Map<string, BlockDef> | null): string | null {
  if (!blocks) return id;
  const def = blocks.get(id);
  if (def?.item) return def.item;
  if (id.includes('wall_')) {
    const base = id.replace('_wall_', '_').replace(':wall_', ':');
    const baseDef = blocks.get(base);
    if (baseDef?.item) return baseDef.item;
  }
  if (id.includes(':potted_')) return null;
  return null;
}

export function countMaterials(
  bp: EditableBlueprint,
  include: (x: number, y: number, z: number, layer: number) => boolean,
  blocks: Map<string, BlockDef> | null,
): Materials {
  const perState = new Map<number, number>();
  bp.forEachCell((x, y, z, value, layer) => {
    if (value > 0 && include(x, y, z, layer)) perState.set(value, (perState.get(value) ?? 0) + 1);
  });
  const items = new Map<string, number>();
  const noItem = new Map<string, number>();
  let total = 0;
  for (const [value, cells] of perState) {
    const state = bp.palette[value - 1]!;
    const n = itemsPerBlock(state) * cells;
    if (n === 0) continue;
    const id = parseState(state).id;
    const item = itemFor(id, blocks);
    if (item) items.set(item, (items.get(item) ?? 0) + n);
    else noItem.set(id, (noItem.get(id) ?? 0) + n);
    total += n;
  }
  const sort = (m: Map<string, number>) => [...m].map(([id, count]) => ({ id, count })).sort((a, b) => b.count - a.count || a.id.localeCompare(b.id));
  return { lines: sort(items), noItem: sort(noItem), total };
}

/** "3 stacks + 10" style split (64 per stack). */
export function stacks(count: number): { stacks: number; rest: number } {
  return { stacks: Math.floor(count / 64), rest: count % 64 };
}
