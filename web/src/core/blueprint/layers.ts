import type { Layer } from '../format/pawprint';
import { EMPTY, type EditableBlueprint } from './editable';

/**
 * The layer tree of a blueprint: top-level order in `layerOrder`, groups list their children in `children`.
 * Layer 0 always exists and is never a group (docs/FORMAT_PAWPRINT.md §5).
 */

const COLORS = ['#7FB3FF', '#FFB347', '#7FC48A', '#E86A5C', '#B07CD8', '#F2D16B', '#5FB3A1', '#D98CB3'];

export interface LayerRow {
  layer: Layer;
  depth: number;
  /** Hidden because it or a group above it is hidden. */
  hiddenByTree: boolean;
  lockedByTree: boolean;
}

function byId(bp: EditableBlueprint, id: number): Layer | undefined {
  return bp.layers.find((l) => l.id === id);
}

/** Siblings list the layer sits in (top level or its group's children). */
function siblings(bp: EditableBlueprint, parent: number | null): number[] {
  if (parent === null) return bp.layerOrder;
  const group = byId(bp, parent);
  if (group) group.children ??= [];
  return group?.children ?? bp.layerOrder;
}

/** Rows in display order, top to bottom, skipping inside collapsed groups. */
export function rows(bp: EditableBlueprint): LayerRow[] {
  const out: LayerRow[] = [];
  const walk = (ids: number[], depth: number, hidden: boolean, locked: boolean) => {
    for (const id of ids) {
      const layer = byId(bp, id);
      if (!layer) continue;
      const h = hidden || !layer.visible, l = locked || layer.locked;
      out.push({ layer, depth, hiddenByTree: h, lockedByTree: l });
      if (layer.group && !layer.collapsed) walk(layer.children ?? [], depth + 1, h, l);
    }
  };
  walk(bp.layerOrder, 0, false, false);
  return out;
}

/** Layers whose blocks may not be edited (hidden or locked, directly or through a group). */
export function protectedLayers(bp: EditableBlueprint): Set<number> {
  const out = new Set<number>();
  const walk = (ids: number[], blocked: boolean) => {
    for (const id of ids) {
      const layer = byId(bp, id);
      if (!layer) continue;
      const b = blocked || !layer.visible || layer.locked;
      if (b && !layer.group) out.add(id);
      if (layer.group) walk(layer.children ?? [], b);
    }
  };
  walk(bp.layerOrder, false);
  return out;
}

/** Layers not shown (hidden directly or through a group), or every layer but `solo` when soloing. */
export function hiddenLayers(bp: EditableBlueprint, solo: number | null): Set<number> {
  const out = new Set<number>();
  if (solo !== null) {
    for (const l of bp.layers) if (!l.group && l.id !== solo) out.add(l.id);
    return out;
  }
  for (const row of allRows(bp)) if (row.hiddenByTree && !row.layer.group) out.add(row.layer.id);
  return out;
}

function allRows(bp: EditableBlueprint): LayerRow[] {
  const out: LayerRow[] = [];
  const walk = (ids: number[], depth: number, hidden: boolean, locked: boolean) => {
    for (const id of ids) {
      const layer = byId(bp, id);
      if (!layer) continue;
      const h = hidden || !layer.visible, l = locked || layer.locked;
      out.push({ layer, depth, hiddenByTree: h, lockedByTree: l });
      if (layer.group) walk(layer.children ?? [], depth + 1, h, l);
    }
  };
  walk(bp.layerOrder, 0, false, false);
  return out;
}

function nextId(bp: EditableBlueprint): number {
  return Math.max(0, ...bp.layers.map((l) => l.id)) + 1;
}

/** Adds a layer above `above` (same parent), or at the top. Returns its id. */
export function addLayer(bp: EditableBlueprint, name: string, above: number | null, group = false): number {
  const id = nextId(bp);
  const ref = above !== null ? byId(bp, above) : undefined;
  // Adding while a group is selected puts the new layer inside it.
  const parent = ref?.group ? ref.id : ref?.parent ?? null;
  const layer: Layer = { id, name, color: COLORS[id % COLORS.length]!, visible: true, locked: false, parent };
  if (group) Object.assign(layer, { group: true, collapsed: false, children: [] });
  bp.layers.push(layer);
  const list = siblings(bp, parent);
  const at = ref && !ref.group ? list.indexOf(ref.id) : 0;
  list.splice(Math.max(0, at), 0, id);
  bp.layersChanged();
  return id;
}

export function update(bp: EditableBlueprint, id: number, change: Partial<Pick<Layer, 'name' | 'color' | 'visible' | 'locked' | 'collapsed'>>): void {
  const layer = byId(bp, id);
  if (!layer) return;
  Object.assign(layer, change);
  bp.layersChanged();
}

/** Whether `id` is `group` or inside it (a group cannot move into itself). */
function within(bp: EditableBlueprint, id: number, group: number): boolean {
  for (let p: number | null = id; p !== null; p = byId(bp, p)?.parent ?? null) if (p === group) return true;
  return false;
}

/**
 * Moves a layer (or group) next to another row: before it, or into it when the target is a group and `into` is set.
 */
export function move(bp: EditableBlueprint, id: number, target: number, into: boolean): void {
  const layer = byId(bp, id), dest = byId(bp, target);
  if (!layer || !dest || id === target) return;
  const parent = into && dest.group ? dest.id : dest.parent;
  if (parent !== null && within(bp, parent, id)) return;
  const from = siblings(bp, layer.parent);
  from.splice(from.indexOf(id), 1);
  layer.parent = parent;
  const list = siblings(bp, parent);
  const at = into && dest.group ? 0 : list.indexOf(target);
  list.splice(at < 0 ? list.length : at, 0, id);
  bp.layersChanged();
}

/** Moves every block of `from` into `into` (one undo step) and removes `from`. Groups merge their children. */
export function mergeInto(bp: EditableBlueprint, from: number, into: number): void {
  const source = byId(bp, from), target = byId(bp, into);
  if (!source || !target || from === 0 || target.group) return;
  const ids = source.group ? leafIds(bp, from) : [from];
  bp.begin('merge');
  for (const id of ids) {
    const cells = bp.cellsOfLayer(id);
    for (let i = 0; i < cells.length; i += 3) bp.setLayer(cells[i]!, cells[i + 1]!, cells[i + 2]!, into);
  }
  bp.commit();
  removeEntry(bp, from);
}

/** Deletes a layer and its blocks (the blocks come back with undo); a group deletes its contents too. */
export function remove(bp: EditableBlueprint, id: number): void {
  if (id === 0 || !byId(bp, id)) return;
  const ids = byId(bp, id)!.group ? leafIds(bp, id) : [id];
  const saved = bp.protectedLayers;
  bp.protectedLayers = new Set();
  bp.begin('delete layer');
  for (const leaf of ids) {
    const cells = bp.cellsOfLayer(leaf);
    for (let i = 0; i < cells.length; i += 3) bp.set(cells[i]!, cells[i + 1]!, cells[i + 2]!, EMPTY);
  }
  bp.commit();
  bp.protectedLayers = saved;
  removeEntry(bp, id);
}

function leafIds(bp: EditableBlueprint, group: number): number[] {
  const out: number[] = [];
  for (const child of byId(bp, group)?.children ?? []) {
    const l = byId(bp, child);
    if (!l) continue;
    if (l.group) out.push(...leafIds(bp, child));
    else out.push(child);
  }
  return out;
}

function removeEntry(bp: EditableBlueprint, id: number): void {
  const layer = byId(bp, id);
  if (!layer) return;
  const doomed = new Set([id, ...(layer.group ? allChildren(bp, id) : [])]);
  const list = siblings(bp, layer.parent);
  list.splice(list.indexOf(id), 1);
  bp.layers = bp.layers.filter((l) => !doomed.has(l.id));
  if (doomed.has(bp.currentLayer)) bp.currentLayer = 0;
  bp.layersChanged();
}

function allChildren(bp: EditableBlueprint, group: number): number[] {
  const out: number[] = [];
  for (const child of byId(bp, group)?.children ?? []) {
    out.push(child);
    if (byId(bp, child)?.group) out.push(...allChildren(bp, child));
  }
  return out;
}

/** Moves the blocks in a set of cells to a layer (one undo step). */
export function moveCellsTo(bp: EditableBlueprint, cells: [number, number, number][], layer: number): void {
  bp.begin('move to layer');
  for (const [x, y, z] of cells) bp.setLayer(x, y, z, layer);
  bp.commit();
}
