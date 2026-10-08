import { EMPTY, type EditableBlueprint } from '../blueprint/editable';
import { brush, ellipse, flood, rect, strokeLine, toSlice, toWorld, unique, type BrushShape, type Cell2, type Plane } from './shapes';

/**
 * Painting tools shared by the 2D and 3D views, so both give the same result. A tool works on a plane (a 2D slice,
 * or in 3D the face plane under the cursor); 3D shapes extrude from that plane along its normal.
 */

export type Tool =
  | 'pencil' | 'eraser' | 'fill' | 'picker' | 'line' | 'rect' | 'ellipse'
  | 'box' | 'hollow' | 'wall' | 'sphere' | 'cylinder'
  | 'select' | 'stamp';

export const SOLID_SHAPES: Tool[] = ['box', 'hollow', 'wall', 'sphere', 'cylinder'];

export interface ToolSettings {
  tool: Tool;
  block: string;
  brushShape: BrushShape;
  brushSize: number;
  filled: boolean;
  /** Layers 3D shapes extrude through. */
  height: number;
}

export interface PlaneRef {
  plane: Plane;
  slice: number;
  /** Direction 3D shapes grow from the slice: +1 or -1 along the plane's axis. */
  extrude: 1 | -1;
}

export type World = [number, number, number];

/** Cells a click-and-drag tool would change, from anchor a to b on the plane. */
export function shapeCells(s: ToolSettings, ref: PlaneRef, a: Cell2, b: Cell2): World[] {
  const flat = (cells: Cell2[], slice = ref.slice) => cells.map(([u, v]) => toWorld(ref.plane, slice, u, v));
  switch (s.tool) {
    case 'line':
      return flat(strokeLine(a, b, s.brushShape, s.brushSize));
    case 'rect':
    case 'select':
      return flat(rect(a, b, s.filled || s.tool === 'select'));
    case 'ellipse':
      return flat(ellipse(a, b, s.filled));
    case 'pencil':
    case 'eraser':
      return flat(brush(b[0], b[1], s.brushShape, s.brushSize));
  }
  // 3D shapes: the dragged rectangle is the footprint, `height` layers along the plane's normal.
  const h = Math.max(1, Math.round(s.height));
  const out: World[] = [];
  const u0 = Math.min(a[0], b[0]), u1 = Math.max(a[0], b[0]);
  const v0 = Math.min(a[1], b[1]), v1 = Math.max(a[1], b[1]);
  for (let d = 0; d < h; d++) {
    const slice = ref.slice + d * ref.extrude;
    const edgeLayer = d === 0 || d === h - 1;
    let cells: Cell2[];
    switch (s.tool) {
      case 'box':
        cells = rect(a, b, true);
        break;
      case 'hollow':
        cells = edgeLayer ? rect(a, b, true) : rect(a, b, false);
        break;
      case 'wall':
        cells = rect(a, b, false);
        break;
      case 'cylinder':
        cells = ellipse(a, b, s.filled);
        break;
      case 'sphere': {
        // Ellipsoid inside the footprint × height box.
        const cu = (u0 + u1) / 2, cv = (v0 + v1) / 2, cd = (h - 1) / 2;
        const ru = (u1 - u0) / 2 + 0.5, rv = (v1 - v0) / 2 + 0.5, rd = (h - 1) / 2 + 0.5;
        const inside = (u: number, v: number, dd: number, shrink: number) =>
          ((u - cu) / Math.max(0.01, ru - shrink)) ** 2 + ((v - cv) / Math.max(0.01, rv - shrink)) ** 2 + ((dd - cd) / Math.max(0.01, rd - shrink)) ** 2 <= 1;
        cells = [];
        for (let v = v0; v <= v1; v++) for (let u = u0; u <= u1; u++) {
          if (inside(u, v, d, 0) && (s.filled || !inside(u, v, d, 1))) cells.push([u, v]);
        }
        break;
      }
      default:
        cells = [];
    }
    out.push(...flat(cells, slice));
  }
  return out;
}

/** What hovering shows before clicking. */
export function hoverCells(s: ToolSettings, ref: PlaneRef, cell: Cell2): World[] {
  if (s.tool === 'pencil' || s.tool === 'eraser' || s.tool === 'line') return shapeCells({ ...s, tool: 'pencil' }, ref, cell, cell);
  return [toWorld(ref.plane, ref.slice, cell[0], cell[1])];
}

export function paintValue(bp: EditableBlueprint, s: ToolSettings): number {
  return s.tool === 'eraser' ? EMPTY : bp.stateIndex(s.block) + 1;
}

export function applyCells(bp: EditableBlueprint, cells: World[], value: number): void {
  const seen = new Set<string>();
  for (const [x, y, z] of cells) {
    const k = x + ',' + y + ',' + z;
    if (seen.has(k)) continue;
    seen.add(k);
    bp.set(x, y, z, value);
  }
}

/** One press-drag-release of a tool on a plane. */
export class Stroke {
  private anchor: Cell2;
  private last: Cell2;
  private current: Cell2;

  constructor(
    private readonly bp: EditableBlueprint,
    private readonly s: ToolSettings,
    readonly ref: PlaneRef,
    start: Cell2,
  ) {
    this.anchor = this.last = this.current = start;
    bp.begin(s.tool);
    if (this.freehand) applyCells(bp, shapeCells(s, ref, start, start), paintValue(bp, s));
  }

  private get freehand(): boolean {
    return this.s.tool === 'pencil' || this.s.tool === 'eraser';
  }

  move(cell: Cell2): void {
    this.current = cell;
    if (this.freehand) {
      const cells = strokeLine(this.last, cell, this.s.brushShape, this.s.brushSize).map(([u, v]) => toWorld(this.ref.plane, this.ref.slice, u, v));
      applyCells(this.bp, cells, paintValue(this.bp, this.s));
      this.last = cell;
    }
  }

  /** Cells the finished stroke will change (for shapes) — shown while dragging. */
  preview(): World[] {
    return this.freehand ? [] : shapeCells(this.s, this.ref, this.anchor, this.current);
  }

  finish(): void {
    if (!this.freehand) applyCells(this.bp, this.preview(), paintValue(this.bp, this.s));
    this.bp.commit();
  }

  cancel(): void {
    this.bp.abort();
  }
}

/** Fills the area of same cells around the start on the plane, within the blueprint's box (plus a margin). */
export function fillPlane(bp: EditableBlueprint, s: ToolSettings, ref: PlaneRef, start: Cell2): boolean {
  const b = bp.bounds();
  let box = { u0: start[0] - 32, v0: start[1] - 32, u1: start[0] + 32, v1: start[1] + 32 };
  if (b) {
    const lo = toSlice(ref.plane, ...b.min), hi = toSlice(ref.plane, ...b.max);
    box = { u0: Math.min(lo.u, hi.u) - 1, v0: Math.min(lo.v, hi.v) - 1, u1: Math.max(lo.u, hi.u) + 1, v1: Math.max(lo.v, hi.v) + 1 };
  }
  const area = flood(start, (u, v) => bp.get(...toWorld(ref.plane, ref.slice, u, v)), box);
  if (!area) return false;
  bp.begin('fill');
  applyCells(bp, unique(area).map(([u, v]) => toWorld(ref.plane, ref.slice, u, v)), paintValue(bp, s));
  bp.commit();
  return true;
}

/**
 * The plane a 3D tool works on, from the block face under the cursor. Placing tools work on the cell in front of the
 * face (`onFace`), erasing and picking on the block itself. Shapes extrude away from the face.
 */
export function planeFromHit(cell: World, normal: World, onFace: boolean): { ref: PlaneRef; uv: Cell2; target: World } {
  const axis = normal[0] !== 0 ? 0 : normal[1] !== 0 ? 1 : 2;
  const target: World = onFace ? [cell[0] + normal[0], cell[1] + normal[1], cell[2] + normal[2]] : [...cell];
  const plane: Plane = axis === 0 ? 'x' : axis === 1 ? 'y' : 'z';
  const { slice, u, v } = toSlice(plane, ...target);
  const extrude = (normal[axis]! >= 0 ? 1 : -1) * (onFace ? 1 : -1) as 1 | -1;
  return { ref: { plane, slice, extrude }, uv: [u, v], target };
}
