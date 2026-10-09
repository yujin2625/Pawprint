import * as THREE from 'three';
import type { LoadedPack } from '../core/pack/pawpack';
import { ModelBaker, MISSING_TEXTURE, type BakedModel, type BakedQuad, DIR_VECTORS, type Dir } from '../core/model/bake';
import { toMeshPalette, type AtlasRect } from '../core/mesh/prepare';
import type { MeshPaletteEntry } from '../core/mesh/mesher';
import type { Plane } from '../core/edit/shapes';

/**
 * Block drawing resources shared by the 3D and 2D views: baked models, one texture atlas that grows as new blocks
 * are used (tile positions never move, so existing meshes stay valid), and small face icons for the 2D view.
 */
export class BlockResources {
  readonly baker: ModelBaker;
  readonly texture: THREE.CanvasTexture;
  readonly canvas: HTMLCanvasElement;
  readonly cell: number;
  private readonly ctx: CanvasRenderingContext2D;
  private readonly cols: number;
  private readonly slots = new Map<string, number>();
  private readonly loading = new Map<string, Promise<void>>();
  private readonly entries = new Map<string, MeshPaletteEntry>();
  private readonly icons = new Map<string, HTMLCanvasElement>();
  /** Bumped when the atlas image changes; 2D views redraw then. */
  version = 0;

  constructor(readonly pack: LoadedPack | null, maxTextureSize: number) {
    const blocks = pack ? new Map(pack.blocks.map((b) => [b.id, b])) : null;
    this.baker = new ModelBaker(
      pack && blocks ? { file: (p) => pack.files.get(p), block: (id) => blocks.get(id), hasIcon: (id) => pack.icons?.icons[id] !== undefined } : null,
    );

    // Size the atlas for every texture in the pack up front, so it never has to be rebuilt.
    let count = 64;
    let largest = 16;
    if (pack) {
      for (const [name, data] of pack.files) {
        if (!name.endsWith('.png') || !name.includes('/textures/')) continue;
        count++;
        if (data.length > 24) largest = Math.max(largest, (data[16]! << 24) | (data[17]! << 16) | (data[18]! << 8) | data[19]!);
      }
    }
    const limit = Math.min(4096, maxTextureSize);
    let cell = Math.min(64, largest);
    let side = 256;
    for (;;) {
      side = Math.max(256, 2 ** Math.ceil(Math.log2(Math.ceil(Math.sqrt(count)) * cell)));
      if (side <= limit || cell <= 16) break;
      cell /= 2;
    }
    side = Math.min(side, limit);
    this.cell = cell;
    this.cols = side / cell;
    this.canvas = document.createElement('canvas');
    this.canvas.width = this.canvas.height = side;
    this.ctx = this.canvas.getContext('2d', { willReadFrequently: true })!;
    this.ctx.imageSmoothingEnabled = false;
    this.texture = new THREE.CanvasTexture(this.canvas);
    this.texture.flipY = false;
    this.texture.magFilter = THREE.NearestFilter;
    this.texture.minFilter = THREE.NearestFilter;
    this.texture.generateMipmaps = false;
    this.texture.colorSpace = THREE.NoColorSpace;
    this.slot(MISSING_TEXTURE);
    drawMissing(this.ctx, 0, 0, cell);
  }

  bake(state: string): BakedModel {
    return this.baker.bake(state);
  }

  /** Loads every texture these states need into the atlas. Resolves once they are drawn. */
  async prepare(states: Iterable<string>): Promise<void> {
    const waits: Promise<void>[] = [];
    for (const state of states) {
      for (const quad of this.baker.bake(state).quads) {
        if (!this.slots.has(quad.texture)) waits.push(this.load(quad.texture));
        else if (this.loading.has(quad.texture)) waits.push(this.loading.get(quad.texture)!);
      }
    }
    if (waits.length) {
      await Promise.all(waits);
      this.texture.needsUpdate = true;
      this.version++;
      this.icons.clear();
    }
  }

  /** Mesher palette entries for these states; call prepare() first so their textures are in the atlas. */
  meshEntries(states: string[]): MeshPaletteEntry[] {
    return states.map((state) => {
      let entry = this.entries.get(state);
      if (!entry) {
        entry = toMeshPalette([this.baker.bake(state)], (t) => this.rect(t))[0]!;
        this.entries.set(state, entry);
      }
      return entry;
    });
  }

  rect(texture: string): AtlasRect {
    const i = this.slots.get(texture) ?? 0;
    const side = this.canvas.width;
    const x = (i % this.cols) * this.cell, y = Math.floor(i / this.cols) * this.cell;
    return [x / side, y / side, (x + this.cell) / side, (y + this.cell) / side];
  }

  private slot(key: string): number | null {
    let i = this.slots.get(key);
    if (i !== undefined) return i;
    i = this.slots.size;
    if (i >= this.cols * this.cols) return null; // Atlas full: the texture shows as missing.
    this.slots.set(key, i);
    return i;
  }

  private load(key: string): Promise<void> {
    const i = this.slot(key);
    if (i === null) return Promise.resolve();
    const x = (i % this.cols) * this.cell, y = Math.floor(i / this.cols) * this.cell;
    const done = (async () => {
      if (key.startsWith('pawprint:color/')) {
        this.ctx.fillStyle = key.slice('pawprint:color/'.length);
        this.ctx.fillRect(x, y, this.cell, this.cell);
        this.ctx.fillStyle = 'rgba(0,0,0,0.12)';
        const edge = Math.max(1, this.cell / 16);
        this.ctx.fillRect(x, y + this.cell - edge, this.cell, edge);
        this.ctx.fillRect(x + this.cell - edge, y, edge, this.cell);
        return;
      }
      if (key.startsWith('pawprint:icon/')) {
        const sheet = await this.iconSheet();
        const icons = this.pack?.icons;
        const index = icons?.icons[key.slice('pawprint:icon/'.length)];
        this.ctx.clearRect(x, y, this.cell, this.cell);
        if (sheet && icons && index !== undefined) {
          const sx = (index % icons.columns) * icons.cell, sy = Math.floor(index / icons.columns) * icons.cell;
          this.ctx.drawImage(sheet, sx, sy, icons.cell, icons.cell, x, y, this.cell, this.cell);
        } else {
          drawMissing(this.ctx, x, y, this.cell);
        }
        return;
      }
      const bytes = this.pack?.files.get(key);
      let image: ImageBitmap | null = null;
      try {
        if (bytes) image = await createImageBitmap(new Blob([bytes as BlobPart], { type: 'image/png' }));
      } catch {
        image = null;
      }
      if (image) {
        const frame = Math.min(image.width, image.height);
        this.ctx.clearRect(x, y, this.cell, this.cell);
        this.ctx.drawImage(image, 0, 0, frame, frame, x, y, this.cell, this.cell);
        image.close();
      } else {
        drawMissing(this.ctx, x, y, this.cell);
      }
    })();
    this.loading.set(key, done);
    return done.finally(() => this.loading.delete(key));
  }

  private sheet: Promise<ImageBitmap | null> | null = null;

  /** icons.png, decoded once. */
  private iconSheet(): Promise<ImageBitmap | null> {
    const bytes = this.pack?.files.get('icons.png');
    this.sheet ??= bytes ? createImageBitmap(new Blob([bytes as BlobPart], { type: 'image/png' })).catch(() => null) : Promise.resolve(null);
    return this.sheet;
  }

  /**
   * What a block looks like from the 2D view's direction: its faces toward the viewer, projected onto one tile and
   * tinted. Call prepare() for the state first.
   */
  icon(state: string, plane: Plane): HTMLCanvasElement {
    const key = plane + '|' + state;
    let icon = this.icons.get(key);
    if (icon) return icon;
    icon = document.createElement('canvas');
    icon.width = icon.height = this.cell;
    const ctx = icon.getContext('2d')!;
    ctx.imageSmoothingEnabled = false;
    const facing: Dir = plane === 'y' ? 'up' : plane === 'z' ? 'south' : 'east';
    const n = DIR_VECTORS[facing];
    const baked = this.baker.bake(state);
    const faces = baked.quads.filter((q) => dot(normal(q), n) > 0.7);
    const depth = (q: BakedQuad) => avg(q, plane === 'y' ? 1 : plane === 'z' ? 2 : 0);
    const draw = faces.length ? faces.sort((a, b) => depth(a) - depth(b)) : baked.quads.slice(0, 1);
    for (const quad of draw) {
      const [a0, b0, a1, b1] = faces.length ? projected(quad, plane) : [0.2, 0.2, 0.8, 0.8];
      const [u0, v0, u1, v1] = this.uvBox(quad);
      const w = Math.max(1, Math.round((a1 - a0) * this.cell)), h = Math.max(1, Math.round((b1 - b0) * this.cell));
      const tile = document.createElement('canvas');
      tile.width = w;
      tile.height = h;
      const t = tile.getContext('2d', { willReadFrequently: true })!;
      t.imageSmoothingEnabled = false;
      t.drawImage(this.canvas, u0, v0, Math.max(1, u1 - u0), Math.max(1, v1 - v0), 0, 0, w, h);
      if (quad.tint) {
        const data = t.getImageData(0, 0, w, h);
        for (let p = 0; p < data.data.length; p += 4) {
          data.data[p] = data.data[p]! * quad.tint[0];
          data.data[p + 1] = data.data[p + 1]! * quad.tint[1];
          data.data[p + 2] = data.data[p + 2]! * quad.tint[2];
        }
        t.putImageData(data, 0, 0);
      }
      ctx.drawImage(tile, Math.round(a0 * this.cell), Math.round(b0 * this.cell));
    }
    this.icons.set(key, icon);
    return icon;
  }

  /** Atlas pixel box of the part of the texture a quad shows. */
  private uvBox(q: BakedQuad): [number, number, number, number] {
    const [r0, s0, r1, s1] = this.rect(q.texture);
    const side = this.canvas.width;
    const us = [q.uv[0]!, q.uv[2]!, q.uv[4]!, q.uv[6]!], vs = [q.uv[1]!, q.uv[3]!, q.uv[5]!, q.uv[7]!];
    const px = (r: number, a: number, b: number) => (r + (a / 16) * (b - r)) * side;
    return [px(r0, Math.min(...us), r1), px(s0, Math.min(...vs), s1), px(r0, Math.max(...us), r1), px(s0, Math.max(...vs), s1)];
  }

  dispose(): void {
    this.texture.dispose();
  }
}

function normal(q: BakedQuad): number[] {
  const p = q.pos;
  const ab = [p[3]! - p[0]!, p[4]! - p[1]!, p[5]! - p[2]!];
  const ac = [p[6]! - p[0]!, p[7]! - p[1]!, p[8]! - p[2]!];
  const n = [ab[1]! * ac[2]! - ab[2]! * ac[1]!, ab[2]! * ac[0]! - ab[0]! * ac[2]!, ab[0]! * ac[1]! - ab[1]! * ac[0]!];
  const len = Math.hypot(n[0]!, n[1]!, n[2]!) || 1;
  return n.map((v) => v / len);
}

const dot = (a: number[], b: number[]) => a[0]! * b[0]! + a[1]! * b[1]! + a[2]! * b[2]!;
const avg = (q: BakedQuad, axis: number) => (q.pos[axis]! + q.pos[axis + 3]! + q.pos[axis + 6]! + q.pos[axis + 9]!) / 4;

/** The quad's box on the icon (0..1), in the 2D view's u/v directions. */
function projected(q: BakedQuad, plane: Plane): [number, number, number, number] {
  const pts = [0, 1, 2, 3].map((k) => [q.pos[k * 3]!, q.pos[k * 3 + 1]!, q.pos[k * 3 + 2]!]);
  const ab = pts.map(([x, y, z]) => (plane === 'y' ? [x!, z!] : plane === 'z' ? [x!, 1 - y!] : [1 - z!, 1 - y!]));
  const a = ab.map((p) => p[0]!), b = ab.map((p) => p[1]!);
  return [Math.min(...a), Math.min(...b), Math.max(...a), Math.max(...b)];
}

function drawMissing(ctx: CanvasRenderingContext2D, x: number, y: number, cell: number): void {
  const half = cell / 2;
  ctx.fillStyle = '#F800F8';
  ctx.fillRect(x, y, cell, cell);
  ctx.fillStyle = '#000000';
  ctx.fillRect(x + half, y, half, half);
  ctx.fillRect(x, y + half, half, half);
}
