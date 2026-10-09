import { parseColor } from '../render/sceneColors';
import { EMPTY, REMOVAL, type EditableBlueprint } from '../core/blueprint/editable';
import { toSlice, toWorld, type Cell2, type Plane } from '../core/edit/shapes';
import { fillPlane, hoverCells, Stroke, type PlaneRef, type World } from '../core/edit/tools';
import { boxOf, paste, type Box } from '../core/edit/clip';
import type { Edit3DSettings } from '../render/editor3d';
import type { BlockResources } from '../render/resources';

/** What the 2D view needs from the editor each frame. */
export interface SliceSettings extends Edit3DSettings {
  plane: Plane;
  slice: number;
  onionBelow: boolean;
  onionAbove: boolean;
  onionOpacity: number;
}

export interface SliceCallbacks {
  settings(): SliceSettings;
  onPick(state: string): void;
  onSelect(box: Box): void;
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
  private stroke: Stroke | null = null;
  private selectAnchor: Cell2 | null = null;
  private panning: { x: number; y: number } | null = null;
  private dirty = true;
  private frameHandle = 0;
  private readonly prepared = new Set<string>();
  private resourcesVersion = -1;
  private readonly resizeObserver: ResizeObserver;
  /** The window the view is in: the main one, or a panel window of the desktop app. */
  private readonly win: Window & typeof globalThis;
  private readonly unsubscribe: () => void;
  private readonly style: CSSStyleDeclaration;

  constructor(
    private readonly container: HTMLElement,
    private readonly resources: BlockResources,
    private readonly blueprint: EditableBlueprint,
    private readonly callbacks: SliceCallbacks,
  ) {
    this.win = (container.ownerDocument.defaultView ?? window) as Window & typeof globalThis;
    this.canvas = container.ownerDocument.createElement('canvas');
    this.canvas.tabIndex = 0;
    this.canvas.style.display = 'block';
    this.canvas.style.outline = 'none';
    this.canvas.style.touchAction = 'none';
    container.appendChild(this.canvas);
    this.ctx = this.canvas.getContext('2d')!;
    this.style = getComputedStyle(container);
    this.unsubscribe = blueprint.onChange(() => (this.dirty = true));
    this.bind();
    this.resizeObserver = new this.win.ResizeObserver(() => this.resize());
    this.resizeObserver.observe(container);
    this.resize();
    this.centerOnBlueprint();
    this.frameHandle = this.win.requestAnimationFrame(this.loop);
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

  /** What is drawn at a cell: hidden layers show as empty. */
  private valueAt(u: number, v: number, offset = 0): number {
    const s = this.callbacks.settings();
    const [x, y, z] = toWorld(s.plane, s.slice + offset, u, v);
    const value = this.blueprint.get(x, y, z);
    return value !== EMPTY && s.hiddenLayers.size && s.hiddenLayers.has(this.blueprint.layerAt(x, y, z)) ? EMPTY : value;
  }

  // Input

  private bind(): void {
    const c = this.canvas;
    c.addEventListener('contextmenu', (e) => e.preventDefault());
    c.addEventListener('pointerdown', (e) => {
      c.focus();
      capture(c, e.pointerId);
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
      this.stroke?.cancel();
      this.stroke = null;
      this.selectAnchor = null;
      this.preview = [];
      this.dirty = true;
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

  private ref(s: SliceSettings): PlaneRef {
    return { plane: s.plane, slice: s.slice, extrude: 1 };
  }

  /** World cells of a tool, as cells of the current slice (3D shapes show their first layer). */
  private onSlice(cells: World[], s: SliceSettings): Cell2[] {
    const out: Cell2[] = [];
    for (const [x, y, z] of cells) {
      const c = toSlice(s.plane, x, y, z);
      if (c.slice === s.slice) out.push([c.u, c.v]);
    }
    return out;
  }

  private start(cell: Cell2, s: SliceSettings): void {
    if (s.tool === 'fill') {
      if (!fillPlane(this.blueprint, s, this.ref(s), cell)) this.callbacks.onMessage('editor.fillTooLarge');
      return;
    }
    if (s.tool === 'stamp') {
      if (!s.clip) return;
      this.blueprint.begin('stamp');
      paste(this.blueprint, s.clip, toWorld(s.plane, s.slice, cell[0], cell[1]));
      this.blueprint.commit();
      return;
    }
    if (s.tool === 'select') {
      this.selectAnchor = cell;
      this.preview = [cell];
      this.dirty = true;
      return;
    }
    this.stroke = new Stroke(this.blueprint, s, this.ref(s), cell);
    this.preview = this.onSlice(this.stroke.preview(), s);
    this.dirty = true;
  }

  private get drawing(): boolean {
    return this.stroke !== null || this.selectAnchor !== null;
  }

  private drag(cell: Cell2): void {
    const s = this.callbacks.settings();
    if (this.stroke) {
      this.stroke.move(cell);
      this.preview = this.onSlice(this.stroke.preview(), s);
    } else if (this.selectAnchor) {
      const a = this.selectAnchor;
      const u0 = Math.min(a[0], cell[0]), u1 = Math.max(a[0], cell[0]), v0 = Math.min(a[1], cell[1]), v1 = Math.max(a[1], cell[1]);
      this.preview = [];
      for (let v = v0; v <= v1; v++) for (let u = u0; u <= u1; u++) if (u === u0 || u === u1 || v === v0 || v === v1) this.preview.push([u, v]);
    }
  }

  private finish(): void {
    const s = this.callbacks.settings();
    if (this.stroke) {
      this.stroke.finish();
      this.stroke = null;
    }
    if (this.selectAnchor) {
      const a = this.selectAnchor, b = this.hover ?? a;
      this.callbacks.onSelect(boxOf(toWorld(s.plane, s.slice, a[0], a[1]), toWorld(s.plane, s.slice, b[0], b[1])));
      this.selectAnchor = null;
    }
    this.preview = [];
    this.updateHoverPreview();
    this.dirty = true;
  }

  private updateHoverPreview(): void {
    const s = this.callbacks.settings();
    if (!this.hover) {
      this.preview = [];
      return;
    }
    if (s.tool === 'stamp' && s.clip) {
      const [ox, oy, oz] = toWorld(s.plane, s.slice, this.hover[0], this.hover[1]);
      this.preview = this.onSlice(s.clip.cells.map(({ p }) => [ox + p[0], oy + p[1], oz + p[2]] as World), s);
      return;
    }
    this.preview = this.onSlice(hoverCells(s, this.ref(s), this.hover), s);
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
    this.frameHandle = this.win.requestAnimationFrame(this.loop);
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
    /** A theme color with its alpha scaled (theme colors may carry their own alpha). */
    const faded = (name: string, fallback: string, scale: number) => {
      const { color: c, alpha } = parseColor(this.style.getPropertyValue(name), fallback);
      return `rgba(${Math.round(c.r * 255)}, ${Math.round(c.g * 255)}, ${Math.round(c.b * 255)}, ${Math.min(1, alpha * scale)})`;
    };
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
      ctx.strokeStyle = faded('--selection', '#faeeda', Math.min(0.9, s.onionOpacity + 0.25));
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
    const gridMajor = faded('--grid', 'rgba(255, 255, 255, 0.22)', 1.6);
    const gridMinor = faded('--grid', 'rgba(255, 255, 255, 0.22)', 0.6);
    for (let u = u0; u <= u1; u++) {
      const major = u % 16 === 0;
      if (!major && z < 8) continue;
      ctx.strokeStyle = major ? gridMajor : gridMinor;
      const [x] = this.screenOf(u, 0);
      ctx.beginPath();
      ctx.moveTo(x + 0.5, 0);
      ctx.lineTo(x + 0.5, h);
      ctx.stroke();
    }
    for (let v = v0; v <= v1; v++) {
      const major = v % 16 === 0;
      if (!major && z < 8) continue;
      ctx.strokeStyle = major ? gridMajor : gridMinor;
      const [, y] = this.screenOf(0, v);
      ctx.beginPath();
      ctx.moveTo(0, y + 0.5);
      ctx.lineTo(w, y + 0.5);
      ctx.stroke();
    }

    // Selection where it crosses this slice.
    if (s.selection) {
      const lo = toSlice(s.plane, ...s.selection.min), hi = toSlice(s.plane, ...s.selection.max);
      const sMin = Math.min(lo.slice, hi.slice), sMax = Math.max(lo.slice, hi.slice);
      if (s.slice >= sMin && s.slice <= sMax) {
        const [x0, y0] = this.screenOf(Math.min(lo.u, hi.u), Math.min(lo.v, hi.v));
        const [x1, y1] = this.screenOf(Math.max(lo.u, hi.u) + 1, Math.max(lo.v, hi.v) + 1);
        ctx.strokeStyle = color('--selection', '#faeeda');
        ctx.lineWidth = 2;
        ctx.setLineDash([6, 4]);
        ctx.strokeRect(x0 + 1, y0 + 1, x1 - x0 - 2, y1 - y0 - 2);
        ctx.setLineDash([]);
      }
    }

    // Tool preview and hovered cell.
    const erase = s.tool === 'eraser';
    ctx.fillStyle = erase ? faded('--erase', '#e86a5c', 0.35) : faded('--preview', '#ef9f27', 0.35);
    ctx.strokeStyle = erase ? color('--erase', '#e86a5c') : color('--preview', '#ef9f27');
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
      ctx.fillStyle = this.style.getPropertyValue('--panel').trim() || '#faeeda';
      ctx.globalAlpha *= 0.5;
      ctx.fillRect(x, y, size, size);
      ctx.globalAlpha /= 0.5;
    }
  }

  private resize(): void {
    const dpr = Math.min(2, this.win.devicePixelRatio || 1);
    const w = this.container.clientWidth, h = this.container.clientHeight;
    if (!w || !h) return;
    this.canvas.style.width = w + 'px';
    this.canvas.style.height = h + 'px';
    this.canvas.width = Math.round(w * dpr);
    this.canvas.height = Math.round(h * dpr);
    this.dirty = true;
  }

  dispose(): void {
    this.win.cancelAnimationFrame(this.frameHandle);
    this.unsubscribe();
    this.resizeObserver.disconnect();
    this.canvas.remove();
  }
}

/** Keeps getting pointer events while dragging outside the canvas; harmless if the pointer is already gone. */
function capture(element: HTMLElement, pointerId: number): void {
  try {
    element.setPointerCapture(pointerId);
  } catch {
    // The pointer was released before we could capture it.
  }
}
