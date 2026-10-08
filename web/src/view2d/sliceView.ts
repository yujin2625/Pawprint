import { EMPTY, REMOVAL, type EditableBlueprint } from '../core/blueprint/editable';
import { brush, ellipse, flood, rect, strokeLine, toSlice, toWorld, unique, type BrushShape, type Cell2, type Plane } from '../core/edit/shapes';
import type { BlockResources } from '../render/resources';

export type Tool = 'pencil' | 'eraser' | 'fill' | 'picker' | 'line' | 'rect' | 'ellipse';

/** What the 2D view needs from the editor each frame. */
export interface SliceSettings {
  tool: Tool;
  block: string;
  brushShape: BrushShape;
  brushSize: number;
  filled: boolean;
  plane: Plane;
  slice: number;
  onionBelow: boolean;
  onionAbove: boolean;
  onionOpacity: number;
}

export interface SliceCallbacks {
  settings(): SliceSettings;
  onPick(state: string): void;
  onCursor(world: [number, number, number] | null, state: string | null): void;
  onMessage(key: string): void;
}

/**
 * The 2D slice editor drawn with Canvas 2D: one layer of the blueprint (floor plan or elevation) with the layers
 * next to it faded in (onion skin), a grid, and live previews of the tool under the cursor.
 */
export class SliceView {
  readonly canvas: HTMLCanvasElement;
  private readonly ctx: CanvasRenderingContext2D;
  /** Pixels per block. */
  private zoom = 24;
  /** Slice coordinates at the middle of the view. */
  private center: [number, number] = [0, 0];
  private hover: Cell2 | null = null;
  private preview: Cell2[] = [];
  private anchor: Cell2 | null = null;
  private last: Cell2 | null = null;
  private drawing = false;
  private panning: { x: number; y: number } | null = null;
  private dirty = true;
  private frameHandle = 0;
  private readonly prepared = new Set<string>();
  private resourcesVersion = -1;
  private readonly resizeObserver: ResizeObserver;
  private readonly unsubscribe: () => void;
  private readonly style: CSSStyleDeclaration;

  constructor(
    private readonly container: HTMLElement,
    private readonly resources: BlockResources,
    private readonly blueprint: EditableBlueprint,
    private readonly callbacks: SliceCallbacks,
  ) {
    this.canvas = document.createElement('canvas');
    this.canvas.tabIndex = 0;
    this.canvas.style.display = 'block';
    this.canvas.style.outline = 'none';
    this.canvas.style.touchAction = 'none';
    container.appendChild(this.canvas);
    this.ctx = this.canvas.getContext('2d')!;
    this.style = getComputedStyle(container);
    this.unsubscribe = blueprint.onChange(() => (this.dirty = true));
    this.bind();
    this.resizeObserver = new ResizeObserver(() => this.resize());
    this.resizeObserver.observe(container);
    this.resize();
    this.centerOnBlueprint();
    this.frameHandle = requestAnimationFrame(this.loop);
  }

  invalidate(): void {
    this.dirty = true;
  }

  /** Puts the blueprint's middle at the center of the view for the current plane. */
  centerOnBlueprint(): void {
    const s = this.callbacks.settings();
    const b = this.blueprint.bounds();
    if (!b) {
      this.center = [0, 0];
    } else {
      const lo = toSlice(s.plane, b.min[0], b.min[1], b.min[2]);
      const hi = toSlice(s.plane, b.max[0], b.max[1], b.max[2]);
      this.center = [(lo.u + hi.u + 1) / 2, (lo.v + hi.v + 1) / 2];
      const w = Math.abs(hi.u - lo.u) + 1, h = Math.abs(hi.v - lo.v) + 1;
      const fit = Math.min(this.canvas.clientWidth / (w + 4), this.canvas.clientHeight / (h + 4));
      this.zoom = Math.max(4, Math.min(48, Math.floor(fit)));
    }
    this.dirty = true;
  }

  // Coordinates

  private cellAt(clientX: number, clientY: number): Cell2 {
    const r = this.canvas.getBoundingClientRect();
    const u = (clientX - r.left - r.width / 2) / this.zoom + this.center[0];
    const v = (clientY - r.top - r.height / 2) / this.zoom + this.center[1];
    return [Math.floor(u), Math.floor(v)];
  }

  private screenOf(u: number, v: number): [number, number] {
    const w = this.canvas.clientWidth, h = this.canvas.clientHeight;
    return [Math.round((u - this.center[0]) * this.zoom + w / 2), Math.round((v - this.center[1]) * this.zoom + h / 2)];
  }

  private valueAt(u: number, v: number, offset = 0): number {
    const s = this.callbacks.settings();
    const [x, y, z] = toWorld(s.plane, s.slice + offset, u, v);
    return this.blueprint.get(x, y, z);
  }

  // Input

  private bind(): void {
    const c = this.canvas;
    c.addEventListener('contextmenu', (e) => e.preventDefault());
    c.addEventListener('pointerdown', (e) => {
      c.focus();
      c.setPointerCapture(e.pointerId);
      const cell = this.cellAt(e.clientX, e.clientY);
      if (e.button === 1 || e.button === 2) {
        this.panning = { x: e.clientX, y: e.clientY };
        return;
      }
      if (e.button !== 0) return;
      const s = this.callbacks.settings();
      if (e.altKey || s.tool === 'picker') {
        this.pick(cell);
        return;
      }
      this.start(cell, s);
    });
    c.addEventListener('pointermove', (e) => {
      if (this.panning) {
        this.center[0] -= (e.clientX - this.panning.x) / this.zoom;
        this.center[1] -= (e.clientY - this.panning.y) / this.zoom;
        this.panning = { x: e.clientX, y: e.clientY };
        this.dirty = true;
        return;
      }
      const cell = this.cellAt(e.clientX, e.clientY);
      if (!this.hover || cell[0] !== this.hover[0] || cell[1] !== this.hover[1]) {
        this.hover = cell;
        this.reportCursor();
        if (this.drawing) this.drag(cell);
        else this.updateHoverPreview();
        this.dirty = true;
      }
    });
    const up = (e: PointerEvent) => {
      if (this.panning && (e.button === 1 || e.button === 2)) {
        this.panning = null;
        return;
      }
      if (e.button === 0 && this.drawing) this.finish();
    };
    c.addEventListener('pointerup', up);
    c.addEventListener('pointercancel', () => {
      if (this.drawing) {
        this.blueprint.abort();
        this.drawing = false;
        this.anchor = null;
        this.preview = [];
        this.dirty = true;
      }
      this.panning = null;
    });
    c.addEventListener('pointerleave', () => {
      if (this.drawing) return;
      this.hover = null;
      this.preview = [];
      this.callbacks.onCursor(null, null);
      this.dirty = true;
    });
    c.addEventListener('wheel', (e) => {
      e.preventDefault();
      const r = c.getBoundingClientRect();
      const before = [(e.clientX - r.left - r.width / 2) / this.zoom + this.center[0], (e.clientY - r.top - r.height / 2) / this.zoom + this.center[1]];
      const factor = e.deltaY < 0 ? 1.15 : 1 / 1.15;
      this.zoom = Math.max(3, Math.min(96, this.zoom * factor));
      // Keep the point under the cursor where it is.
      this.center[0] = before[0]! - (e.clientX - r.left - r.width / 2) / this.zoom;
      this.center[1] = before[1]! - (e.clientY - r.top - r.height / 2) / this.zoom;
      this.dirty = true;
    }, { passive: false });
  }

  private reportCursor(): void {
    if (!this.hover) return;
    const s = this.callbacks.settings();
    const world = toWorld(s.plane, s.slice, this.hover[0], this.hover[1]);
    this.callbacks.onCursor(world, this.blueprint.stateAt(...world));
  }

  private pick(cell: Cell2): void {
    const s = this.callbacks.settings();
    const state = this.blueprint.stateAt(...toWorld(s.plane, s.slice, cell[0], cell[1]));
    if (state) this.callbacks.onPick(state);
  }

  private paintValue(s: SliceSettings): number {
    return s.tool === 'eraser' ? EMPTY : this.blueprint.stateIndex(s.block) + 1;
  }

  private start(cell: Cell2, s: SliceSettings): void {
    if (s.tool === 'fill') {
      this.fill(cell, s);
      return;
    }
    this.drawing = true;
    this.anchor = cell;
    this.last = cell;
    this.blueprint.begin(s.tool);
    if (s.tool === 'pencil' || s.tool === 'eraser') this.apply(brush(cell[0], cell[1], s.brushShape, s.brushSize), s);
    else this.preview = [cell];
    this.dirty = true;
  }

  private drag(cell: Cell2): void {
    const s = this.callbacks.settings();
    if (s.tool === 'pencil' || s.tool === 'eraser') {
      this.apply(strokeLine(this.last ?? cell, cell, s.brushShape, s.brushSize), s);
      this.last = cell;
    } else if (this.anchor) {
      this.preview = this.shape(this.anchor, cell, s);
    }
  }

  private finish(): void {
    const s = this.callbacks.settings();
    if (this.anchor && (s.tool === 'line' || s.tool === 'rect' || s.tool === 'ellipse')) {
      this.apply(this.shape(this.anchor, this.hover ?? this.anchor, s), s);
    }
    this.blueprint.commit();
    this.drawing = false;
    this.anchor = null;
    this.last = null;
    this.preview = [];
    this.updateHoverPreview();
    this.dirty = true;
  }

  private shape(a: Cell2, b: Cell2, s: SliceSettings): Cell2[] {
    if (s.tool === 'line') return strokeLine(a, b, s.brushShape, s.brushSize);
    if (s.tool === 'rect') return rect(a, b, s.filled);
    return ellipse(a, b, s.filled);
  }

  private apply(cells: Cell2[], s: SliceSettings): void {
    const value = this.paintValue(s);
    for (const [u, v] of unique(cells)) {
      const [x, y, z] = toWorld(s.plane, s.slice, u, v);
      this.blueprint.set(x, y, z, value);
    }
  }

  /** Fills the area of same cells around the click, within the blueprint's box plus a margin. */
  private fill(cell: Cell2, s: SliceSettings): void {
    const b = this.blueprint.bounds();
    let box = { u0: cell[0] - 32, v0: cell[1] - 32, u1: cell[0] + 32, v1: cell[1] + 32 };
    if (b) {
      const lo = toSlice(s.plane, ...b.min), hi = toSlice(s.plane, ...b.max);
      box = { u0: Math.min(lo.u, hi.u) - 1, v0: Math.min(lo.v, hi.v) - 1, u1: Math.max(lo.u, hi.u) + 1, v1: Math.max(lo.v, hi.v) + 1 };
    }
    const area = flood(cell, (u, v) => this.valueAt(u, v), box);
    if (!area) {
      this.callbacks.onMessage('editor.fillTooLarge');
      return;
    }
    this.blueprint.begin('fill');
    this.apply(area, s);
    this.blueprint.commit();
  }

  private updateHoverPreview(): void {
    const s = this.callbacks.settings();
    if (!this.hover || s.tool === 'fill' || s.tool === 'picker') {
      this.preview = this.hover ? [this.hover] : [];
      return;
    }
    this.preview = s.tool === 'pencil' || s.tool === 'eraser' || s.tool === 'line'
      ? brush(this.hover[0], this.hover[1], s.brushShape, s.brushSize)
      : [this.hover];
  }

  /** Called when tool or brush settings change. */
  settingsChanged(): void {
    if (!this.drawing) this.updateHoverPreview();
    this.dirty = true;
  }

  // Drawing

  private loop = (): void => {
    if (this.resources.version !== this.resourcesVersion) {
      this.resourcesVersion = this.resources.version;
      this.dirty = true;
    }
    if (this.dirty) {
      this.dirty = false;
      this.draw();
    }
    this.frameHandle = requestAnimationFrame(this.loop);
  };

  private icon(state: string, plane: Plane): HTMLCanvasElement | null {
    if (!this.prepared.has(state)) {
      this.prepared.add(state);
      void this.resources.prepare([state]).then(() => (this.dirty = true));
      return null;
    }
    return this.resources.icon(state, plane);
  }

  private draw(): void {
    const ctx = this.ctx;
    const w = this.canvas.clientWidth, h = this.canvas.clientHeight;
    const s = this.callbacks.settings();
    const color = (name: string, fallback: string) => this.style.getPropertyValue(name).trim() || fallback;
    ctx.setTransform(this.canvas.width / Math.max(1, w), 0, 0, this.canvas.height / Math.max(1, h), 0, 0);
    ctx.imageSmoothingEnabled = false;
    ctx.fillStyle = color('--viewport-bg', '#2a6fb5');
    ctx.fillRect(0, 0, w, h);

    const z = this.zoom;
    const u0 = Math.floor(this.center[0] - w / 2 / z) - 1, u1 = Math.ceil(this.center[0] + w / 2 / z) + 1;
    const v0 = Math.floor(this.center[1] - h / 2 / z) - 1, v1 = Math.ceil(this.center[1] + h / 2 / z) + 1;
    const size = Math.ceil(z);

    // Onion skin: the layer below (or behind) faded, the layer above (or in front) as outlines.
    if (s.onionBelow) {
      ctx.globalAlpha = s.onionOpacity;
      for (let v = v0; v <= v1; v++) for (let u = u0; u <= u1; u++) {
        if (this.valueAt(u, v) !== EMPTY) continue;
        const below = this.valueAt(u, v, -1);
        if (below > 0) this.drawCell(ctx, u, v, size, this.blueprint.palette[below - 1]!, s.plane);
      }
      ctx.globalAlpha = 1;
    }
    for (let v = v0; v <= v1; v++) {
      for (let u = u0; u <= u1; u++) {
        const value = this.valueAt(u, v);
        if (value > 0) this.drawCell(ctx, u, v, size, this.blueprint.palette[value - 1]!, s.plane);
        else if (value === REMOVAL) {
          const [x, y] = this.screenOf(u, v);
          ctx.strokeStyle = color('--danger', '#a33a2c');
          ctx.lineWidth = Math.max(1, z / 12);
          ctx.beginPath();
          ctx.moveTo(x + z * 0.2, y + z * 0.2);
          ctx.lineTo(x + z * 0.8, y + z * 0.8);
          ctx.moveTo(x + z * 0.8, y + z * 0.2);
          ctx.lineTo(x + z * 0.2, y + z * 0.8);
          ctx.stroke();
        }
      }
    }
    if (s.onionAbove) {
      ctx.strokeStyle = `rgba(250, 238, 218, ${Math.min(0.9, s.onionOpacity + 0.25)})`;
      ctx.lineWidth = 1;
      ctx.setLineDash([Math.max(2, z / 6), Math.max(2, z / 6)]);
      for (let v = v0; v <= v1; v++) for (let u = u0; u <= u1; u++) {
        if (this.valueAt(u, v, 1) > 0) {
          const [x, y] = this.screenOf(u, v);
          ctx.strokeRect(x + 1.5, y + 1.5, z - 3, z - 3);
        }
      }
      ctx.setLineDash([]);
    }

    // Grid: every block when zoomed in enough, every 16 always.
    ctx.lineWidth = 1;
    for (let u = u0; u <= u1; u++) {
      const major = u % 16 === 0;
      if (!major && z < 8) continue;
      ctx.strokeStyle = major ? 'rgba(255,255,255,0.35)' : 'rgba(255,255,255,0.13)';
      const [x] = this.screenOf(u, 0);
      ctx.beginPath();
      ctx.moveTo(x + 0.5, 0);
      ctx.lineTo(x + 0.5, h);
      ctx.stroke();
    }
    for (let v = v0; v <= v1; v++) {
      const major = v % 16 === 0;
      if (!major && z < 8) continue;
      ctx.strokeStyle = major ? 'rgba(255,255,255,0.35)' : 'rgba(255,255,255,0.13)';
      const [, y] = this.screenOf(0, v);
      ctx.beginPath();
      ctx.moveTo(0, y + 0.5);
      ctx.lineTo(w, y + 0.5);
      ctx.stroke();
    }

    // Tool preview and hovered cell.
    const erase = s.tool === 'eraser';
    ctx.fillStyle = erase ? 'rgba(163, 58, 44, 0.35)' : 'rgba(239, 159, 39, 0.35)';
    ctx.strokeStyle = erase ? '#E86A5C' : '#FAC775';
    ctx.lineWidth = Math.max(1, Math.min(2, z / 12));
    for (const [u, v] of this.preview) {
      const [x, y] = this.screenOf(u, v);
      ctx.fillRect(x, y, size, size);
      ctx.strokeRect(x + 0.5, y + 0.5, size - 1, size - 1);
    }
    if (this.hover) {
      const [x, y] = this.screenOf(this.hover[0], this.hover[1]);
      ctx.strokeStyle = color('--selection', '#faeeda');
      ctx.lineWidth = 2;
      ctx.strokeRect(x + 1, y + 1, size - 2, size - 2);
    }
  }

  private drawCell(ctx: CanvasRenderingContext2D, u: number, v: number, size: number, state: string, plane: Plane): void {
    const [x, y] = this.screenOf(u, v);
    const icon = this.icon(state, plane);
    if (icon) ctx.drawImage(icon, x, y, size, size);
    else {
      ctx.fillStyle = 'rgba(250, 238, 218, 0.5)';
      ctx.fillRect(x, y, size, size);
    }
  }

  private resize(): void {
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    const w = this.container.clientWidth, h = this.container.clientHeight;
    if (!w || !h) return;
    this.canvas.style.width = w + 'px';
    this.canvas.style.height = h + 'px';
    this.canvas.width = Math.round(w * dpr);
    this.canvas.height = Math.round(h * dpr);
    this.dirty = true;
  }

  dispose(): void {
    cancelAnimationFrame(this.frameHandle);
    this.unsubscribe();
    this.resizeObserver.disconnect();
    this.canvas.remove();
  }
}
