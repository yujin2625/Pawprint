import { DEFAULT_LAYER, type Blueprint, type Layer, type PawprintMeta } from '../format/pawprint';

/**
 * A blueprint being edited. Cells live in 16³ sections (the render cache unit). A cell holds 0 (empty),
 * REMOVAL (this spot must be cleared when building) or palette index + 1. Every edit goes through a change batch so
 * it can be undone; listeners hear which cells changed.
 */

export const SECTION = 16;
export const EMPTY = 0;
export const REMOVAL = -1;

interface Section {
  origin: [number, number, number];
  cells: Int32Array;
  layers: Uint16Array;
  count: number;
}

/** x, y, z, before, after, layerBefore, layerAfter for each changed cell. */
export interface ChangeBatch {
  label: string;
  changes: number[];
}

export type ChangeListener = (cells: Int32Array) => void;

const MAX_HISTORY = 200;

export class EditableBlueprint {
  meta: PawprintMeta;
  layers: Layer[];
  layerOrder: number[];
  thumbnail: Uint8Array | null;
  readonly palette: string[] = [];
  private readonly paletteIndex = new Map<string, number>();
  private readonly sections = new Map<string, Section>();
  private readonly undoStack: ChangeBatch[] = [];
  private readonly redoStack: ChangeBatch[] = [];
  private recording: ChangeBatch | null = null;
  private readonly listeners = new Set<ChangeListener>();
  /** Layer new cells go to. */
  currentLayer = 0;
  private boundsCache: { min: [number, number, number]; max: [number, number, number] } | null | undefined;
  revision = 0;

  constructor(meta: PawprintMeta, layers: Layer[] = [{ ...DEFAULT_LAYER }], layerOrder: number[] = [0]) {
    this.meta = meta;
    this.layers = layers;
    this.layerOrder = layerOrder;
    this.thumbnail = null;
  }

  static fromBlueprint(bp: Blueprint): EditableBlueprint {
    const e = new EditableBlueprint({ ...bp.meta }, bp.layers.map((l) => ({ ...l })), [...bp.layerOrder]);
    e.thumbnail = bp.thumbnail;
    const map = bp.palette.map((s) => e.stateIndex(s) + 1);
    for (let i = 0; i < bp.states.length; i++) {
      e.write(bp.positions[i * 3]!, bp.positions[i * 3 + 1]!, bp.positions[i * 3 + 2]!, map[bp.states[i]!]!, bp.blockLayers[i]!);
    }
    for (let i = 0; i < bp.removals.length / 3; i++) {
      e.write(bp.removals[i * 3]!, bp.removals[i * 3 + 1]!, bp.removals[i * 3 + 2]!, REMOVAL, bp.removalLayers[i]!);
    }
    e.boundsCache = undefined;
    return e;
  }

  /** Flat arrays with the minimum corner moved to (0, 0, 0); the world origin moves along with it. */
  toBlueprint(): Blueprint {
    const bounds = this.bounds();
    const [mx, my, mz] = bounds ? bounds.min : [0, 0, 0];
    const positions: number[] = [];
    const states: number[] = [];
    const blockLayers: number[] = [];
    const removals: number[] = [];
    const removalLayers: number[] = [];
    // Only palette entries still in use are written.
    const used = new Map<number, number>();
    const palette: string[] = [];
    for (const section of this.sections.values()) {
      if (section.count === 0) continue;
      const [ox, oy, oz] = section.origin;
      for (let i = 0; i < section.cells.length; i++) {
        const v = section.cells[i]!;
        if (v === EMPTY) continue;
        const x = ox + (i % SECTION) - mx;
        const z = oz + (Math.floor(i / SECTION) % SECTION) - mz;
        const y = oy + Math.floor(i / (SECTION * SECTION)) - my;
        if (v === REMOVAL) {
          removals.push(x, y, z);
          removalLayers.push(section.layers[i]!);
        } else {
          let idx = used.get(v);
          if (idx === undefined) {
            idx = palette.length;
            palette.push(this.palette[v - 1]!);
            used.set(v, idx);
          }
          positions.push(x, y, z);
          states.push(idx);
          blockLayers.push(section.layers[i]!);
        }
      }
    }
    const meta = { ...this.meta };
    if (meta.origin && (mx || my || mz)) {
      meta.origin = { ...meta.origin, pos: [meta.origin.pos[0] + mx, meta.origin.pos[1] + my, meta.origin.pos[2] + mz] };
    }
    return {
      meta,
      palette,
      positions: new Int32Array(positions),
      states: new Int32Array(states),
      blockLayers: new Int32Array(blockLayers),
      removals: new Int32Array(removals),
      removalLayers: new Int32Array(removalLayers),
      layers: this.layers,
      layerOrder: this.layerOrder,
      thumbnail: this.thumbnail,
    };
  }

  /** Palette index of a state string, adding it if new. */
  stateIndex(state: string): number {
    let idx = this.paletteIndex.get(state);
    if (idx === undefined) {
      idx = this.palette.length;
      this.palette.push(state);
      this.paletteIndex.set(state, idx);
    }
    return idx;
  }

  /** 0 empty, REMOVAL, or palette index + 1. */
  get(x: number, y: number, z: number): number {
    const s = this.sections.get(key(x, y, z));
    return s ? s.cells[cellIndex(s, x, y, z)]! : EMPTY;
  }

  layerAt(x: number, y: number, z: number): number {
    const s = this.sections.get(key(x, y, z));
    return s ? s.layers[cellIndex(s, x, y, z)]! : 0;
  }

  stateAt(x: number, y: number, z: number): string | null {
    const v = this.get(x, y, z);
    return v > 0 ? this.palette[v - 1]! : null;
  }

  get blockCount(): number {
    let n = 0;
    for (const s of this.sections.values()) for (const v of s.cells) if (v > 0) n++;
    return n;
  }

  bounds(): { min: [number, number, number]; max: [number, number, number] } | null {
    if (this.boundsCache !== undefined) return this.boundsCache;
    const min = [Infinity, Infinity, Infinity];
    const max = [-Infinity, -Infinity, -Infinity];
    for (const s of this.sections.values()) {
      if (s.count === 0) continue;
      for (let i = 0; i < s.cells.length; i++) {
        if (s.cells[i] === EMPTY) continue;
        const p = [s.origin[0] + (i % SECTION), s.origin[1] + Math.floor(i / (SECTION * SECTION)), s.origin[2] + (Math.floor(i / SECTION) % SECTION)];
        for (let a = 0; a < 3; a++) {
          if (p[a]! < min[a]!) min[a] = p[a]!;
          if (p[a]! > max[a]!) max[a] = p[a]!;
        }
      }
    }
    this.boundsCache = min[0] === Infinity ? null : { min: min as [number, number, number], max: max as [number, number, number] };
    return this.boundsCache;
  }

  /** Every non-empty cell: x, y, z, value (for meshing). */
  allCells(): { positions: Int32Array; values: Int32Array } {
    const positions: number[] = [];
    const values: number[] = [];
    for (const s of this.sections.values()) {
      if (s.count === 0) continue;
      for (let i = 0; i < s.cells.length; i++) {
        const v = s.cells[i]!;
        if (v === EMPTY) continue;
        positions.push(s.origin[0] + (i % SECTION), s.origin[1] + Math.floor(i / (SECTION * SECTION)), s.origin[2] + (Math.floor(i / SECTION) % SECTION));
        values.push(v);
      }
    }
    return { positions: new Int32Array(positions), values: new Int32Array(values) };
  }

  // Editing

  onChange(listener: ChangeListener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  begin(label: string): void {
    if (this.recording) this.commit();
    this.recording = { label, changes: [] };
  }

  /** Sets one cell inside the current batch. New blocks go to the current layer; a changed block keeps its layer. */
  set(x: number, y: number, z: number, value: number): void {
    if (!this.recording) throw new Error('set() outside begin()/commit()');
    const before = this.get(x, y, z);
    if (before === value) return;
    const layerBefore = this.layerAt(x, y, z);
    const layerAfter = value === EMPTY ? 0 : before === EMPTY ? this.currentLayer : layerBefore;
    this.write(x, y, z, value, layerAfter);
    this.recording.changes.push(x, y, z, before, value, layerBefore, layerAfter);
  }

  /** Ends the batch; returns false when nothing changed. */
  commit(): boolean {
    const batch = this.recording;
    this.recording = null;
    if (!batch || batch.changes.length === 0) return false;
    this.undoStack.push(batch);
    if (this.undoStack.length > MAX_HISTORY) this.undoStack.shift();
    this.redoStack.length = 0;
    this.changed(batch.changes);
    return true;
  }

  /** Undoes a batch that is still recording (e.g. a cancelled drag). */
  abort(): void {
    const batch = this.recording;
    this.recording = null;
    if (!batch) return;
    this.applyBatch(batch, true);
  }

  get canUndo(): boolean {
    return this.undoStack.length > 0;
  }

  get canRedo(): boolean {
    return this.redoStack.length > 0;
  }

  get history(): readonly ChangeBatch[] {
    return this.undoStack;
  }

  undo(): ChangeBatch | null {
    const batch = this.undoStack.pop();
    if (!batch) return null;
    this.applyBatch(batch, true);
    this.redoStack.push(batch);
    return batch;
  }

  redo(): ChangeBatch | null {
    const batch = this.redoStack.pop();
    if (!batch) return null;
    this.applyBatch(batch, false);
    this.undoStack.push(batch);
    return batch;
  }

  private applyBatch(batch: ChangeBatch, backwards: boolean): void {
    const c = batch.changes;
    const n = c.length / 7;
    for (let k = 0; k < n; k++) {
      const i = (backwards ? n - 1 - k : k) * 7;
      this.write(c[i]!, c[i + 1]!, c[i + 2]!, backwards ? c[i + 3]! : c[i + 4]!, backwards ? c[i + 5]! : c[i + 6]!);
    }
    this.changed(c);
  }

  private changed(changes: number[]): void {
    this.boundsCache = undefined;
    this.revision++;
    const cells = new Int32Array(changes.length / 7 * 3);
    for (let i = 0, j = 0; i < changes.length; i += 7, j += 3) {
      cells[j] = changes[i]!;
      cells[j + 1] = changes[i + 1]!;
      cells[j + 2] = changes[i + 2]!;
    }
    this.listeners.forEach((l) => l(cells));
  }

  private write(x: number, y: number, z: number, value: number, layer: number): void {
    const k = key(x, y, z);
    let s = this.sections.get(k);
    if (!s) {
      if (value === EMPTY) return;
      s = {
        origin: [Math.floor(x / SECTION) * SECTION, Math.floor(y / SECTION) * SECTION, Math.floor(z / SECTION) * SECTION],
        cells: new Int32Array(SECTION ** 3),
        layers: new Uint16Array(SECTION ** 3),
        count: 0,
      };
      this.sections.set(k, s);
    }
    const i = cellIndex(s, x, y, z);
    const before = s.cells[i]!;
    if (before === EMPTY && value !== EMPTY) s.count++;
    else if (before !== EMPTY && value === EMPTY) s.count--;
    s.cells[i] = value;
    s.layers[i] = value === EMPTY ? 0 : layer;
    this.boundsCache = undefined;
  }
}

function key(x: number, y: number, z: number): string {
  return Math.floor(x / SECTION) + ',' + Math.floor(y / SECTION) + ',' + Math.floor(z / SECTION);
}

/** Same layout as the mesher: index = (y * 16 + z) * 16 + x within the section. */
function cellIndex(s: Section, x: number, y: number, z: number): number {
  return ((y - s.origin[1]) * SECTION + (z - s.origin[2])) * SECTION + (x - s.origin[0]);
}

export function newMeta(name: string, author = ''): PawprintMeta {
  const now = new Date().toISOString();
  return {
    format: 1, id: crypto.randomUUID(), name, description: '', author, tags: [], created: now, modified: now,
    mcVersion: '', dataVersion: 0, size: [0, 0, 0], blockCount: 0, removalCount: 0, mods: [], blocks: [],
  };
}
