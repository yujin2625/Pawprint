import * as THREE from 'three';
import type { Blueprint } from '../core/format/pawprint';
import type { LoadedPack } from '../core/pack/pawpack';
import { ModelBaker } from '../core/model/bake';
import { texturesOf, toMeshPalette } from '../core/mesh/prepare';
import type { SectionMesh } from '../core/mesh/mesher';
import type { MesherRequest, MesherResponse } from '../workers/mesher.worker';
import { buildAtlas } from './atlas';
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
 * The 3D view: one three.js scene, section meshes built in a worker (nearest sections first), and the fly camera.
 * Renders only when something changed.
 */
export class Viewport {
  readonly renderer: THREE.WebGLRenderer;
  readonly controls: FlyCamera;
  onStats: (stats: ViewportStats) => void = () => {};

  private readonly scene = new THREE.Scene();
  private readonly world = new THREE.Group();
  private readonly helpers = new THREE.Group();
  private readonly sections = new Map<string, THREE.Mesh[]>();
  private materials: THREE.Material[] = [];
  private texture: THREE.Texture | null = null;
  private worker: Worker | null = null;
  private bounds = { min: new THREE.Vector3(), max: new THREE.Vector3(1, 1, 1) };
  private stats: ViewportStats = { sectionsDone: 0, sectionsTotal: 0, quads: 0, missingBlocks: [] };
  private dirty = true;
  private frameHandle = 0;
  private lastTime = 0;
  private readonly resizeObserver: ResizeObserver;

  constructor(private readonly container: HTMLElement) {
    this.renderer = new THREE.WebGLRenderer({ antialias: false, powerPreference: 'high-performance' });
    // Shading is done on color values the way the game does (no linear-space conversion).
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

    this.controls = new FlyCamera(canvas);
    this.controls.onChange = () => (this.dirty = true);
    window.addEventListener('keydown', this.onKey);

    this.resizeObserver = new ResizeObserver(() => this.resize());
    this.resizeObserver.observe(container);
    this.resize();
    this.frameHandle = requestAnimationFrame(this.loop);
  }

  async show(bp: Blueprint, pack: LoadedPack | null): Promise<void> {
    this.clear();
    const blocks = pack ? new Map(pack.blocks.map((b) => [b.id, b])) : null;
    const baker = new ModelBaker(pack && blocks ? { file: (p) => pack.files.get(p), block: (id) => blocks.get(id) } : null);
    const baked = bp.palette.map((state) => baker.bake(state));
    this.stats = { sectionsDone: 0, sectionsTotal: 0, quads: 0, missingBlocks: [...new Set(baked.filter((b) => b.missing).map((b) => b.blockId))] };

    const maxSize = Math.min(8192, this.renderer.capabilities.maxTextureSize);
    const atlas = await buildAtlas(texturesOf(baked), (p) => pack?.files.get(p), maxSize);
    const texture = new THREE.CanvasTexture(atlas.canvas);
    texture.flipY = false;
    texture.magFilter = THREE.NearestFilter;
    texture.minFilter = THREE.NearestFilter;
    texture.generateMipmaps = false;
    texture.colorSpace = THREE.NoColorSpace;
    this.texture = texture;
    this.materials = [
      new THREE.MeshBasicMaterial({ map: texture, vertexColors: true }),
      new THREE.MeshBasicMaterial({ map: texture, vertexColors: true, alphaTest: 0.5 }),
      new THREE.MeshBasicMaterial({ map: texture, vertexColors: true, transparent: true, depthWrite: false }),
    ];

    const palette = toMeshPalette(baked, atlas.rect);
    this.setBounds(bp);
    this.frame();

    this.worker = new Worker(new URL('../workers/mesher.worker.ts', import.meta.url), { type: 'module' });
    this.worker.onmessage = (event: MessageEvent<MesherResponse>) => this.onWorker(event.data);
    this.post({ type: 'load', positions: bp.positions, states: bp.states, palette });
  }

  frame(): void {
    this.controls.frame(this.bounds.min, this.bounds.max);
  }

  private queue: string[] = [];
  private inFlight = 0;

  private onWorker(msg: MesherResponse): void {
    if (msg.type === 'loaded') {
      // Nearest sections first so the part you look at appears right away.
      const eye = this.controls.camera.position;
      const center = (o: [number, number, number]) => new THREE.Vector3(o[0] + 8, o[1] + 8, o[2] + 8);
      this.queue = msg.sections.sort((a, b) => center(a.origin).distanceToSquared(eye) - center(b.origin).distanceToSquared(eye)).map((s) => s.key);
      this.stats.sectionsTotal = this.queue.length;
      this.onStats({ ...this.stats });
      this.pump();
      return;
    }
    this.inFlight--;
    if (msg.type === 'section') this.addSection(msg.mesh);
    this.stats.sectionsDone++;
    this.onStats({ ...this.stats });
    this.pump();
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
      this.world.add(object);
      meshes.push(object);
    });
    this.sections.set(mesh.key, meshes);
    this.stats.quads += mesh.quadCount;
    this.dirty = true;
  }

  private removeSection(key: string): void {
    for (const mesh of this.sections.get(key) ?? []) {
      mesh.geometry.dispose();
      this.world.remove(mesh);
    }
    this.sections.delete(key);
  }

  private setBounds(bp: Blueprint): void {
    const [sx, sy, sz] = bp.meta.size;
    this.bounds = { min: new THREE.Vector3(0, 0, 0), max: new THREE.Vector3(Math.max(1, sx), Math.max(1, sy), Math.max(1, sz)) };
    for (const child of [...this.helpers.children]) {
      this.helpers.remove(child);
      if (child instanceof THREE.LineSegments) (child.geometry.dispose(), (child.material as THREE.Material).dispose());
    }
    this.helpers.add(groundGrid(Math.max(1, sx), Math.max(1, sz)));
    const box = new THREE.LineSegments(
      new THREE.EdgesGeometry(new THREE.BoxGeometry(this.bounds.max.x, this.bounds.max.y, this.bounds.max.z)),
      new THREE.LineBasicMaterial({ color: 0xfac775, transparent: true, opacity: 0.6 }),
    );
    box.position.copy(this.bounds.max).multiplyScalar(0.5);
    this.helpers.add(box);
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
    if (this.dirty) {
      this.dirty = false;
      this.renderer.render(this.scene, this.controls.camera);
    }
    this.frameHandle = requestAnimationFrame(this.loop);
  };

  private resize(): void {
    const { clientWidth: w, clientHeight: h } = this.container;
    if (w === 0 || h === 0) return;
    this.renderer.setSize(w, h);
    this.controls.resize(w, h);
    this.dirty = true;
  }

  private post(msg: MesherRequest): void {
    this.worker?.postMessage(msg);
  }

  private clear(): void {
    this.worker?.terminate();
    this.worker = null;
    this.queue = [];
    this.inFlight = 0;
    for (const key of [...this.sections.keys()]) this.removeSection(key);
    this.materials.forEach((m) => m.dispose());
    this.texture?.dispose();
  }

  dispose(): void {
    cancelAnimationFrame(this.frameHandle);
    this.clear();
    this.resizeObserver.disconnect();
    window.removeEventListener('keydown', this.onKey);
    this.controls.dispose();
    this.renderer.dispose();
    this.renderer.domElement.remove();
  }
}

/** Blueprint-paper grid on the ground: a line every block, a stronger one every 16. */
function groundGrid(sx: number, sz: number): THREE.LineSegments {
  const margin = 8;
  const x0 = -margin, x1 = sx + margin, z0 = -margin, z1 = sz + margin;
  const minor: number[] = [];
  const major: number[] = [];
  for (let x = x0; x <= x1; x++) (x % 16 === 0 ? major : minor).push(x, 0, z0, x, 0, z1);
  for (let z = z0; z <= z1; z++) (z % 16 === 0 ? major : minor).push(x0, 0, z, x1, 0, z);
  const geometry = new THREE.BufferGeometry();
  const all = [...minor, ...major];
  geometry.setAttribute('position', new THREE.Float32BufferAttribute(all, 3));
  const colors = new Float32Array(all.length);
  for (let i = 0; i < all.length / 3; i++) {
    const strong = i >= minor.length / 3;
    colors.set(strong ? [0.72, 0.84, 0.96] : [0.45, 0.62, 0.84], i * 3);
  }
  geometry.setAttribute('color', new THREE.BufferAttribute(colors, 3));
  const grid = new THREE.LineSegments(geometry, new THREE.LineBasicMaterial({ vertexColors: true, transparent: true, opacity: 0.5 }));
  grid.position.y = -0.002;
  return grid;
}
