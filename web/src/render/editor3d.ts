import * as THREE from 'three';
import type { EditableBlueprint } from '../core/blueprint/editable';
import { raycast, rayAxisPlane } from '../core/edit/raycast';
import { toSlice, type Cell2 } from '../core/edit/shapes';
import { fillPlane, hoverCells, planeFromHit, Stroke, type PlaneRef, type ToolSettings, type World } from '../core/edit/tools';
import { boxOf, paste, type Box, type Clip } from '../core/edit/clip';
import type { Viewport } from './viewport';

export interface Edit3DSettings extends ToolSettings {
  selection: Box | null;
  clip: Clip | null;
  /** Layers not drawn: the cursor passes through them. */
  hiddenLayers: ReadonlySet<number>;
}

export interface Edit3DCallbacks {
  settings(): Edit3DSettings;
  onPick(state: string): void;
  onSelect(box: Box): void;
  onCursor(world: World | null, state: string | null): void;
  onMessage(key: string): void;
}

const MAX_PREVIEW = 20000;

/**
 * Left-button editing in the 3D view. The block face under the cursor gives the plane the shared tools work on
 * (see core/edit/tools.ts), so 3D edits match 2D ones. Right and middle buttons stay with the camera.
 */
export class Editor3D {
  private readonly raycaster = new THREE.Raycaster();
  private readonly preview: THREE.InstancedMesh;
  private readonly previewMaterial: THREE.MeshBasicMaterial;
  private readonly selectionBox: THREE.LineSegments;
  private stroke: Stroke | null = null;
  private selectAnchor: World | null = null;
  private lockedPlane: PlaneRef | null = null;
  private readonly matrix = new THREE.Matrix4();
  private lastPointer: { x: number; y: number } | null = null;
  private readonly cleanup: (() => void)[] = [];

  constructor(
    private readonly view: Viewport,
    private readonly bp: EditableBlueprint,
    private readonly callbacks: Edit3DCallbacks,
  ) {
    this.previewMaterial = new THREE.MeshBasicMaterial({ color: view.themeColors.preview, transparent: true, opacity: 0.35, depthWrite: false });
    this.preview = new THREE.InstancedMesh(new THREE.BoxGeometry(1.02, 1.02, 1.02), this.previewMaterial, MAX_PREVIEW);
    this.preview.count = 0;
    this.preview.renderOrder = 20;
    this.preview.frustumCulled = false;
    view.addOverlay(this.preview);

    this.selectionBox = new THREE.LineSegments(new THREE.EdgesGeometry(new THREE.BoxGeometry(1, 1, 1)), new THREE.LineBasicMaterial({ color: view.themeColors.selection }));
    this.selectionBox.visible = false;
    this.selectionBox.renderOrder = 21;
    view.addOverlay(this.selectionBox);

    const c = view.canvas;
    const on = <K extends keyof HTMLElementEventMap>(type: K, fn: (e: HTMLElementEventMap[K]) => void) => {
      c.addEventListener(type, fn as EventListener);
      this.cleanup.push(() => c.removeEventListener(type, fn as EventListener));
    };
    on('pointermove', (e) => this.move(e));
    on('pointerdown', (e) => this.down(e));
    on('pointerup', (e) => this.up(e));
    on('pointerleave', () => {
      if (!this.stroke && !this.selectAnchor) this.showPreview([]);
      this.callbacks.onCursor(null, null);
    });
    this.cleanup.push(bp.onChange(() => this.refresh()));
  }

  /** Re-evaluates the cursor after settings or the blueprint changed. */
  refresh(): void {
    (this.selectionBox.material as THREE.LineBasicMaterial).color.copy(this.view.themeColors.selection);
    this.updateSelection();
    if (this.lastPointer && !this.stroke) this.hover(this.lastPointer.x, this.lastPointer.y);
  }

  private ray(x: number, y: number): { origin: World; dir: World } {
    const r = this.view.canvas.getBoundingClientRect();
    this.raycaster.setFromCamera(new THREE.Vector2(((x - r.left) / r.width) * 2 - 1, -((y - r.top) / r.height) * 2 + 1), this.view.camera);
    const o = this.raycaster.ray.origin, d = this.raycaster.ray.direction;
    return { origin: [o.x, o.y, o.z], dir: [d.x, d.y, d.z] };
  }

  /** The block under the cursor, or the ground below the blueprint when nothing is hit. */
  private hit(x: number, y: number): { cell: World; normal: World; ground: boolean } | null {
    const { origin, dir } = this.ray(x, y);
    const hidden = this.callbacks.settings().hiddenLayers;
    const found = raycast(origin, dir, 2000, (cx, cy, cz) => this.bp.get(cx, cy, cz) > 0 && !(hidden.size && hidden.has(this.bp.layerAt(cx, cy, cz))));
    if (found) return { cell: found.cell, normal: found.normal, ground: false };
    const ground = this.bp.bounds()?.min[1] ?? 0;
    const point = rayAxisPlane(origin, dir, 1, ground);
    if (!point) return null;
    // Pretend there is a block just under the ground so placing tools put blocks on it.
    return { cell: [Math.floor(point[0]), ground - 1, Math.floor(point[2])], normal: [0, 1, 0], ground: true };
  }

  private placing(s: ToolSettings): boolean {
    return s.tool !== 'eraser' && s.tool !== 'picker' && s.tool !== 'fill' && s.tool !== 'select';
  }

  private hover(x: number, y: number): void {
    const s = this.callbacks.settings();
    const h = this.hit(x, y);
    if (!h) {
      this.showPreview([]);
      this.callbacks.onCursor(null, null);
      return;
    }
    this.callbacks.onCursor(h.ground ? null : h.cell, h.ground ? null : this.bp.stateAt(...h.cell));
    if (h.ground && !this.placing(s)) {
      this.showPreview([]);
      return;
    }
    const { ref, uv, target } = planeFromHit(h.cell, h.normal, this.placing(s));
    if (s.tool === 'stamp') {
      const clip = s.clip;
      this.showPreview(clip ? clip.cells.map(({ p }) => [target[0] + p[0], target[1] + p[1], target[2] + p[2]] as World) : [target]);
    } else if (s.tool === 'select' || s.tool === 'picker' || s.tool === 'fill') {
      this.showPreview([target], 'selection');
    } else {
      this.showPreview(hoverCells(s, ref, uv), s.tool === 'eraser' ? 'erase' : 'preview');
    }
  }

  private move(e: PointerEvent): void {
    this.lastPointer = { x: e.clientX, y: e.clientY };
    if (e.buttons & 6) return; // camera drag
    const s = this.callbacks.settings();
    if (this.stroke && this.lockedPlane) {
      const uv = this.planeUv(e.clientX, e.clientY, this.lockedPlane);
      if (uv) {
        this.stroke.move(uv);
        const preview = this.stroke.preview();
        this.showPreview(preview, s.tool === 'eraser' ? 'erase' : 'preview');
      }
      return;
    }
    if (this.selectAnchor) {
      const h = this.hit(e.clientX, e.clientY);
      if (h) this.showBox(boxOf(this.selectAnchor, h.ground ? [h.cell[0], h.cell[1] + 1, h.cell[2]] : h.cell));
      return;
    }
    this.hover(e.clientX, e.clientY);
  }

  /** Where the cursor ray meets a locked plane, in that plane's u/v cells. */
  private planeUv(x: number, y: number, ref: PlaneRef): Cell2 | null {
    const { origin, dir } = this.ray(x, y);
    const axis = ref.plane === 'x' ? 0 : ref.plane === 'y' ? 1 : 2;
    const level = ref.slice + 0.5;
    const p = rayAxisPlane(origin, dir, axis, level);
    if (!p) return null;
    const { u, v } = toSlice(ref.plane, Math.floor(p[0]), Math.floor(p[1]), Math.floor(p[2]));
    return [u, v];
  }

  private down(e: PointerEvent): void {
    if (e.button !== 0) return;
    const s = this.callbacks.settings();
    const h = this.hit(e.clientX, e.clientY);
    if (!h) return;
    if ((e.altKey || s.tool === 'picker') && !h.ground) {
      const state = this.bp.stateAt(...h.cell);
      if (state) this.callbacks.onPick(state);
      return;
    }
    if (h.ground && !this.placing(s)) return;
    const { ref, uv, target } = planeFromHit(h.cell, h.normal, this.placing(s));
    capture(this.view.canvas, e.pointerId);
    if (s.tool === 'fill') {
      if (!fillPlane(this.bp, s, ref, uv)) this.callbacks.onMessage('editor.fillTooLarge');
      return;
    }
    if (s.tool === 'stamp') {
      if (!s.clip) return;
      this.bp.begin('stamp');
      paste(this.bp, s.clip, target);
      this.bp.commit();
      return;
    }
    if (s.tool === 'select') {
      this.selectAnchor = target;
      this.showBox(boxOf(target, target));
      return;
    }
    this.lockedPlane = ref;
    this.stroke = new Stroke(this.bp, s, ref, uv);
    this.showPreview(this.stroke.preview());
  }

  private up(e: PointerEvent): void {
    if (e.button !== 0) return;
    if (this.stroke) {
      this.stroke.finish();
      this.stroke = null;
      this.lockedPlane = null;
    }
    if (this.selectAnchor) {
      const h = this.hit(e.clientX, e.clientY);
      const end = h ? (h.ground ? [h.cell[0], h.cell[1] + 1, h.cell[2]] as World : h.cell) : this.selectAnchor;
      this.callbacks.onSelect(boxOf(this.selectAnchor, end));
      this.selectAnchor = null;
    }
    this.hover(e.clientX, e.clientY);
  }

  private showPreview(cells: World[], color: 'preview' | 'erase' | 'selection' = 'preview'): void {
    this.previewMaterial.color.copy(this.view.themeColors[color]);
    const n = Math.min(cells.length, MAX_PREVIEW);
    for (let i = 0; i < n; i++) {
      const [x, y, z] = cells[i]!;
      this.matrix.makeTranslation(x + 0.5, y + 0.5, z + 0.5);
      this.preview.setMatrixAt(i, this.matrix);
    }
    this.preview.count = n;
    this.preview.instanceMatrix.needsUpdate = true;
    this.view.invalidate();
  }

  private showBox(box: Box | null): void {
    if (!box) {
      this.selectionBox.visible = false;
    } else {
      const size = [box.max[0] - box.min[0] + 1, box.max[1] - box.min[1] + 1, box.max[2] - box.min[2] + 1];
      this.selectionBox.scale.set(size[0]! + 0.04, size[1]! + 0.04, size[2]! + 0.04);
      this.selectionBox.position.set(box.min[0] + size[0]! / 2, box.min[1] + size[1]! / 2, box.min[2] + size[2]! / 2);
      this.selectionBox.visible = true;
    }
    this.view.invalidate();
  }

  updateSelection(): void {
    if (!this.selectAnchor) this.showBox(this.callbacks.settings().selection);
  }

  dispose(): void {
    this.cleanup.forEach((fn) => fn());
    this.view.removeOverlay(this.preview);
    this.view.removeOverlay(this.selectionBox);
    this.preview.geometry.dispose();
    this.previewMaterial.dispose();
    this.selectionBox.geometry.dispose();
    (this.selectionBox.material as THREE.Material).dispose();
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
