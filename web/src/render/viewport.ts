import * as THREE from 'three';
import type { EditableBlueprint } from '../core/blueprint/editable';
import type { SectionMesh } from '../core/mesh/mesher';
import type { Plane } from '../core/edit/shapes';
import type { MesherRequest, MesherResponse } from '../workers/mesher.worker';
import type { BlockResources } from './resources';
import { FlyCamera } from './camera';

// Colors are used as-is, like the game: no sRGB ↔ linear conversion anywhere.
THREE.ColorManagement.enabled = false;

export interface ViewportStats {
  sectionsDone: number;
  sectionsTotal: number;
  quads: number;
  missingBlocks: string[];
}

/**
 * The 3D view of an editable blueprint: section meshes built in a worker (nearest first) and rebuilt where edits
 * land, the fly camera, and helpers (ground grid, bounds, the 2D view's current slice). Renders only on change.
 */
export class Viewport {
  readonly renderer: THREE.WebGLRenderer;
  readonly controls: FlyCamera;
  onStats: (stats: ViewportStats) => void = () => {};

  private readonly scene = new THREE.Scene();
  private readonly world = new THREE.Group();
  private readonly helpers = new THREE.Group();
  private readonly sections = new Map<string, THREE.Mesh[]>();
  private readonly materials: THREE.Material[];
  private worker: Worker | null = null;
  private blueprint: EditableBlueprint | null = null;
  private unsubscribe: (() => void) | null = null;
  private sentPalette = 0;
  private hidden: ReadonlySet<number> = new Set();
  private pendingCells: number[] = [];
  private flushing = false;
  private gridKey = '';
  private slicePlane: THREE.Mesh | null = null;
  private stats: ViewportStats = { sectionsDone: 0, sectionsTotal: 0, quads: 0, missingBlocks: [] };
  private dirty = true;
  private frameHandle = 0;
  private lastTime = 0;
  private readonly resizeObserver: ResizeObserver;
  private queue: string[] = [];
  private inFlight = 0;

  constructor(
    private readonly container: HTMLElement,
    private readonly resources: BlockResources,
  ) {
    this.renderer = new THREE.WebGLRenderer({ antialias: false, powerPreference: 'high-performance', preserveDrawingBuffer: true });
    this.renderer.outputColorSpace = THREE.LinearSRGBColorSpace;
    this.renderer.setPixelRatio(Math.min(2, window.devicePixelRatio));
    const canvas = this.renderer.domElement;
    canvas.tabIndex = 0;
    canvas.style.display = 'block';
    canvas.style.outline = 'none';
    container.appendChild(canvas);

    const bg = getComputedStyle(container).getPropertyValue('--viewport-bg').trim() || '#2a6fb5';
    this.scene.background = new THREE.Color(bg);
    this.scene.add(this.world, this.helpers);

    const map = resources.texture;
    this.materials = [
      new THREE.MeshBasicMaterial({ map, vertexColors: true }),
      new THREE.MeshBasicMaterial({ map, vertexColors: true, alphaTest: 0.5 }),
      new THREE.MeshBasicMaterial({ map, vertexColors: true, transparent: true, depthWrite: false }),
    ];

    this.controls = new FlyCamera(canvas);
    this.controls.onChange = () => (this.dirty = true);
    window.addEventListener('keydown', this.onKey);

    this.resizeObserver = new ResizeObserver(() => this.resize());
    this.resizeObserver.observe(container);
    this.resize();
    this.frameHandle = requestAnimationFrame(this.loop);
  }

  async show(bp: EditableBlueprint): Promise<void> {
    this.clear();
    this.blueprint = bp;
    await this.resources.prepare(bp.palette);
    this.sentPalette = bp.palette.length;
    const entries = this.resources.meshEntries(bp.palette);
    this.stats = { sectionsDone: 0, sectionsTotal: 0, quads: 0, missingBlocks: this.missingIn(bp.palette) };
    this.updateHelpers();
    this.frame();

    this.worker = new Worker(new URL('../workers/mesher.worker.ts', import.meta.url), { type: 'module' });
    this.worker.onmessage = (event: MessageEvent<MesherResponse>) => this.onWorker(event.data);
    this.load(entries);
    this.unsubscribe = bp.onChange((changed) => {
      for (let i = 0; i < changed.length; i += 3) {
        const x = changed[i]!, y = changed[i + 1]!, z = changed[i + 2]!;
        this.pendingCells.push(x, y, z, this.hidden.has(bp.layerAt(x, y, z)) ? 0 : bp.get(x, y, z));
      }
      void this.flush();
    });
  }

  /** Sends every visible cell to the worker and rebuilds all sections. */
  private load(entries = this.resources.meshEntries(this.blueprint?.palette.slice(0, this.sentPalette) ?? [])): void {
    const bp = this.blueprint;
    if (!bp) return;
    const positions: number[] = [];
    const values: number[] = [];
    bp.forEachCell((x, y, z, value, layer) => {
      if (value > 0 && !this.hidden.has(layer)) positions.push(x, y, z), values.push(value);
    });
    for (const key of [...this.sections.keys()]) this.removeSection(key);
    this.queue = [];
    this.inFlight = 0;
    this.post({ type: 'load', positions: new Int32Array(positions), values: new Int32Array(values), palette: entries });
  }

  /** Layers not drawn (hidden in the layer panel, or not the soloed one). */
  setHiddenLayers(hidden: ReadonlySet<number>): void {
    const same = hidden.size === this.hidden.size && [...hidden].every((id) => this.hidden.has(id));
    this.hidden = new Set(hidden);
    if (!same && this.worker) this.load();
  }

  /** Sends edits to the worker, after loading textures for blocks used for the first time. */
  private async flush(): Promise<void> {
    if (this.flushing || !this.blueprint) return;
    this.flushing = true;
    try {
      while (this.pendingCells.length) {
        const bp = this.blueprint;
        const start = this.sentPalette;
        const added = bp.palette.slice(start);
        if (added.length) await this.resources.prepare(added);
        const cells = new Int32Array(this.pendingCells);
        this.pendingCells = [];
        // Values may point at palette entries added during the await; send everything up to now.
        const palette = bp.palette.slice(start);
        if (palette.length > added.length) await this.resources.prepare(palette);
        this.sentPalette = start + palette.length;
        if (palette.length) this.stats.missingBlocks = this.missingIn(bp.palette);
        this.post({ type: 'update', paletteStart: start, palette: this.resources.meshEntries(palette), cells });
      }
      this.updateHelpers();
    } finally {
      this.flushing = false;
    }
  }

  private missingIn(states: string[]): string[] {
    return [...new Set(states.map((s) => this.resources.bake(s)).filter((b) => b.missing).map((b) => b.blockId))];
  }

  frame(): void {
    const bounds = this.blueprint?.bounds();
    const min = bounds ? new THREE.Vector3(...bounds.min) : new THREE.Vector3(-8, 0, -8);
    const max = bounds ? new THREE.Vector3(...bounds.max).addScalar(1) : new THREE.Vector3(8, 4, 8);
    this.controls.frame(min, max);
  }

  /** Shows where the 2D view's slice is (a translucent sheet), or hides it with null. */
  setSlice(plane: Plane | null, slice = 0): void {
    if (this.slicePlane) {
      this.helpers.remove(this.slicePlane);
      this.slicePlane.geometry.dispose();
      (this.slicePlane.material as THREE.Material).dispose();
      this.slicePlane = null;
    }
    if (plane) {
      const b = this.blueprint?.bounds();
      const min = b ? b.min : [-8, 0, -8], max = b ? b.max : [8, 4, 8];
      const pad = 4;
      const size = [max[0]! - min[0]! + 1 + pad * 2, max[1]! - min[1]! + 1 + pad * 2, max[2]! - min[2]! + 1 + pad * 2];
      const center = [(min[0]! + max[0]! + 1) / 2, (min[1]! + max[1]! + 1) / 2, (min[2]! + max[2]! + 1) / 2];
      const geometry = new THREE.PlaneGeometry(plane === 'x' ? size[2]! : size[0]!, plane === 'y' ? size[2]! : size[1]!);
      const mesh = new THREE.Mesh(geometry, new THREE.MeshBasicMaterial({ color: 0xef9f27, transparent: true, opacity: 0.18, side: THREE.DoubleSide, depthWrite: false }));
      if (plane === 'y') {
        mesh.rotation.x = -Math.PI / 2;
        mesh.position.set(center[0]!, slice + 0.5, center[2]!);
      } else if (plane === 'z') {
        mesh.position.set(center[0]!, center[1]!, slice + 0.5);
      } else {
        mesh.rotation.y = Math.PI / 2;
        mesh.position.set(slice + 0.5, center[1]!, center[2]!);
      }
      mesh.renderOrder = 10;
      this.slicePlane = mesh;
      this.helpers.add(mesh);
    }
    this.dirty = true;
  }

  get camera(): THREE.PerspectiveCamera {
    return this.controls.camera;
  }

  get canvas(): HTMLCanvasElement {
    return this.renderer.domElement;
  }

  /** Adds an object drawn over the blueprint (cursor previews, selection). */
  addOverlay(object: THREE.Object3D): void {
    this.helpers.add(object);
    this.dirty = true;
  }

  removeOverlay(object: THREE.Object3D): void {
    this.helpers.remove(object);
    this.dirty = true;
  }

  invalidate(): void {
    this.dirty = true;
  }

  /** A PNG of the current view, for project thumbnails. */
  snapshot(size = 256): Promise<Blob | null> {
    this.renderer.render(this.scene, this.controls.camera);
    const source = this.renderer.domElement;
    const canvas = document.createElement('canvas');
    canvas.width = canvas.height = size;
    const s = Math.min(source.width, source.height);
    canvas.getContext('2d')!.drawImage(source, (source.width - s) / 2, (source.height - s) / 2, s, s, 0, 0, size, size);
    return new Promise((resolve) => canvas.toBlob(resolve, 'image/png'));
  }

  private onWorker(msg: MesherResponse): void {
    if (msg.type === 'loaded') {
      const eye = this.controls.camera.position;
      const center = (o: [number, number, number]) => new THREE.Vector3(o[0] + 8, o[1] + 8, o[2] + 8);
      this.queue = msg.sections.sort((a, b) => center(a.origin).distanceToSquared(eye) - center(b.origin).distanceToSquared(eye)).map((s) => s.key);
      this.stats.sectionsTotal = this.queue.length;
      this.onStats({ ...this.stats });
      this.pump();
      return;
    }
    if (msg.type === 'section') this.addSection(msg.mesh);
    else this.removeSection(msg.key);
    if (!msg.update) {
      this.inFlight--;
      this.stats.sectionsDone++;
      this.pump();
    }
    this.onStats({ ...this.stats });
  }

  private pump(): void {
    while (this.inFlight < 4 && this.queue.length) {
      const keys = this.queue.splice(0, 8);
      this.inFlight += keys.length;
      this.post({ type: 'build', keys });
    }
  }

  private addSection(mesh: SectionMesh): void {
    this.removeSection(mesh.key);
    const meshes: THREE.Mesh[] = [];
    mesh.layers.forEach((layer, i) => {
      if (!layer) return;
      const geometry = new THREE.BufferGeometry();
      geometry.setAttribute('position', new THREE.BufferAttribute(layer.positions, 3));
      geometry.setAttribute('uv', new THREE.BufferAttribute(layer.uvs, 2));
      geometry.setAttribute('color', new THREE.BufferAttribute(layer.colors, 3, true));
      geometry.setIndex(new THREE.BufferAttribute(layer.indices, 1));
      geometry.computeBoundingSphere();
      const object = new THREE.Mesh(geometry, this.materials[i]);
      object.position.set(...mesh.origin);
      object.matrixAutoUpdate = false;
      object.updateMatrix();
      object.renderOrder = i;
      object.userData.quads = layer.indices.length / 6;
      this.world.add(object);
      meshes.push(object);
      this.stats.quads += object.userData.quads;
    });
    this.sections.set(mesh.key, meshes);
    this.dirty = true;
  }

  private removeSection(key: string): void {
    for (const mesh of this.sections.get(key) ?? []) {
      this.stats.quads -= mesh.userData.quads ?? 0;
      mesh.geometry.dispose();
      this.world.remove(mesh);
    }
    this.sections.delete(key);
    this.dirty = true;
  }

  /** Ground grid and bounds box follow the blueprint's size. */
  private updateHelpers(): void {
    const b = this.blueprint?.bounds();
    const min = b ? b.min : [-8, 0, -8], max = b ? b.max : [7, 0, 7];
    const key = min.join() + '|' + max.join();
    if (key === this.gridKey) return;
    this.gridKey = key;
    for (const child of [...this.helpers.children]) {
      if (child === this.slicePlane || !child.userData.helper) continue;
      this.helpers.remove(child);
      if (child instanceof THREE.LineSegments) (child.geometry.dispose(), (child.material as THREE.Material).dispose());
    }
    const grid = groundGrid(min[0]!, min[2]!, max[0]! + 1, max[2]! + 1, min[1]!);
    grid.userData.helper = true;
    this.helpers.add(grid);
    if (b) {
      const size = [max[0]! - min[0]! + 1, max[1]! - min[1]! + 1, max[2]! - min[2]! + 1];
      const box = new THREE.LineSegments(
        new THREE.EdgesGeometry(new THREE.BoxGeometry(size[0], size[1], size[2])),
        new THREE.LineBasicMaterial({ color: 0xfac775, transparent: true, opacity: 0.6 }),
      );
      box.position.set(min[0]! + size[0]! / 2, min[1]! + size[1]! / 2, min[2]! + size[2]! / 2);
      box.userData.helper = true;
      this.helpers.add(box);
    }
    this.dirty = true;
  }

  private onKey = (e: KeyboardEvent): void => {
    if (e.key.toLowerCase() !== 'f' || e.ctrlKey || e.metaKey || e.altKey) return;
    const target = e.target as HTMLElement | null;
    if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)) return;
    this.frame();
  };

  private loop = (time: number): void => {
    const dt = Math.min(0.1, (time - (this.lastTime || time)) / 1000);
    this.lastTime = time;
    if (this.controls.update(dt)) this.dirty = true;
    if (this.resources.texture.needsUpdate) this.dirty = true;
    if (this.dirty) {
      this.dirty = false;
      this.renderer.render(this.scene, this.controls.camera);
    }
    this.frameHandle = requestAnimationFrame(this.loop);
  };

  private sized = false;

  private resize(): void {
    const { clientWidth: w, clientHeight: h } = this.container;
    if (w === 0 || h === 0) return;
    this.renderer.setSize(w, h);
    this.controls.resize(w, h);
    // A view created before its panel had a size framed the blueprint for the wrong shape: frame again once.
    if (!this.sized) {
      this.sized = true;
      if (this.blueprint) this.frame();
    }
    this.dirty = true;
  }

  private post(msg: MesherRequest): void {
    this.worker?.postMessage(msg);
  }

  private clear(): void {
    this.unsubscribe?.();
    this.unsubscribe = null;
    this.worker?.terminate();
    this.worker = null;
    this.queue = [];
    this.inFlight = 0;
    this.pendingCells = [];
    for (const key of [...this.sections.keys()]) this.removeSection(key);
  }

  dispose(): void {
    cancelAnimationFrame(this.frameHandle);
    this.clear();
    this.setSlice(null);
    this.resizeObserver.disconnect();
    window.removeEventListener('keydown', this.onKey);
    this.controls.dispose();
    this.materials.forEach((m) => m.dispose());
    this.renderer.dispose();
    this.renderer.domElement.remove();
  }
}

/** Blueprint-paper grid on the ground: a line every block, a stronger one every 16. */
function groundGrid(x0b: number, z0b: number, x1b: number, z1b: number, y: number): THREE.LineSegments {
  const margin = 8;
  const x0 = x0b - margin, x1 = x1b + margin, z0 = z0b - margin, z1 = z1b + margin;
  const minor: number[] = [];
  const major: number[] = [];
  for (let x = x0; x <= x1; x++) (x % 16 === 0 ? major : minor).push(x, 0, z0, x, 0, z1);
  for (let z = z0; z <= z1; z++) (z % 16 === 0 ? major : minor).push(x0, 0, z, x1, 0, z);
  const all = [...minor, ...major];
  const geometry = new THREE.BufferGeometry();
  geometry.setAttribute('position', new THREE.Float32BufferAttribute(all, 3));
  const colors = new Float32Array(all.length);
  for (let i = 0; i < all.length / 3; i++) colors.set(i >= minor.length / 3 ? [0.72, 0.84, 0.96] : [0.45, 0.62, 0.84], i * 3);
  geometry.setAttribute('color', new THREE.BufferAttribute(colors, 3));
  const grid = new THREE.LineSegments(geometry, new THREE.LineBasicMaterial({ vertexColors: true, transparent: true, opacity: 0.5 }));
  grid.position.y = y - 0.002;
  return grid;
}
