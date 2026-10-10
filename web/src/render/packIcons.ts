import { ModelBaker, type BakedQuad } from '../core/model/bake';
import { formatState } from '../core/model/state';
import type { IconInput, IconSheet } from '../core/pack/assetPack';

/**
 * Draws the palette icons of a pack made from game files, the way the game's inventory shows blocks: the block
 * model seen from above at an angle, or the item's flat picture (flowers, doors, signs). Models are drawn with a
 * 2D canvas: seen without perspective every face is a sheared rectangle, which a canvas transform draws exactly.
 */

const CELL = 32;
/** The game's inventory view of a block: turned 225° around Y, tilted 30°, at 0.625 of the slot. */
const SCALE = 0.625 * CELL;
const SIN_Y = Math.sin((225 * Math.PI) / 180), COS_Y = Math.cos((225 * Math.PI) / 180);
const SIN_X = Math.sin((30 * Math.PI) / 180), COS_X = Math.cos((30 * Math.PI) / 180);
/** Faces overlap their neighbors by this much (texture pixels of 16), so no background shows through the edges. */
const BLEED = 0.2;

interface Job {
  id: string;
  flat?: Uint8Array[];
  quads?: BakedQuad[];
  tint: string | null;
}

export async function renderPackIcons(input: IconInput): Promise<IconSheet | null> {
  const blocks = new Map(input.blocks.map((b) => [b.id, b]));
  const baker = new ModelBaker({ file: (path) => input.files.get(path), block: (id) => blocks.get(id) });
  const jobs: Job[] = [];
  for (const block of input.blocks) {
    const flat = input.flat.get(block.id);
    if (flat) {
      jobs.push({ id: block.id, flat, tint: block.tint?.color ?? null });
      continue;
    }
    if (block.renderShape === 'invisible') continue;
    const baked = baker.bake(formatState(block.id, block.default));
    const quads = baked.missing ? [] : baked.quads.filter((q) => !q.texture.startsWith('pawprint:'));
    if (quads.length) jobs.push({ id: block.id, quads, tint: null });
  }
  if (!jobs.length) return null;

  const columns = Math.min(256, Math.max(16, Math.ceil(Math.sqrt(jobs.length))));
  const sheet = document.createElement('canvas');
  sheet.width = columns * CELL;
  sheet.height = Math.ceil(jobs.length / columns) * CELL;
  const ctx = sheet.getContext('2d');
  if (!ctx) return null;
  ctx.imageSmoothingEnabled = false;
  const scratch = document.createElement('canvas');
  const images = new ImageCache(input.files);
  const icons: Record<string, number> = {};

  for (let i = 0; i < jobs.length; i++) {
    const job = jobs[i]!;
    const x = (i % columns) * CELL, y = Math.floor(i / columns) * CELL;
    ctx.save();
    ctx.beginPath();
    ctx.rect(x, y, CELL, CELL);
    ctx.clip();
    const drawn = job.flat ? await drawFlat(ctx, scratch, job.flat, job.tint, x, y) : await drawModel(ctx, scratch, images, job.quads!, x, y);
    ctx.restore();
    if (drawn) icons[job.id] = i;
    // Thousands of blocks: let the page breathe.
    if (i % 250 === 249) await new Promise((r) => setTimeout(r));
  }
  images.close();
  if (!Object.keys(icons).length) return null;
  const blob = await new Promise<Blob | null>((resolve) => sheet.toBlob(resolve, 'image/png'));
  if (!blob) return null;
  return { png: new Uint8Array(await blob.arrayBuffer()), cell: CELL, columns, icons };
}

class ImageCache {
  private readonly cache = new Map<string, Promise<ImageBitmap | null>>();

  constructor(private readonly files: Map<string, Uint8Array>) {}

  get(path: string): Promise<ImageBitmap | null> {
    let image = this.cache.get(path);
    if (!image) {
      image = decode(this.files.get(path));
      this.cache.set(path, image);
    }
    return image;
  }

  close(): void {
    for (const image of this.cache.values()) void image.then((bitmap) => bitmap?.close());
    this.cache.clear();
  }
}

async function decode(bytes: Uint8Array | undefined): Promise<ImageBitmap | null> {
  if (!bytes) return null;
  try {
    return await createImageBitmap(new Blob([bytes as BlobPart], { type: 'image/png' }));
  } catch {
    return null;
  }
}

/** Screen position in the cell (y down) and depth (larger is nearer) of a point in block space. */
function project(px: number, py: number, pz: number): [number, number, number] {
  const x = px - 0.5, y = py - 0.5, z = pz - 0.5;
  const x1 = x * COS_Y + z * SIN_Y, z1 = -x * SIN_Y + z * COS_Y;
  const y2 = y * COS_X - z1 * SIN_X, z2 = y * SIN_X + z1 * COS_X;
  return [CELL / 2 + x1 * SCALE, CELL / 2 - y2 * SCALE, z2];
}

/**
 * Copies part of an image into the scratch canvas, multiplied by a color (shading, tint). The picture's own
 * transparency is kept.
 */
function tinted(scratch: HTMLCanvasElement, image: CanvasImageSource, sx: number, sy: number, sw: number, sh: number, color: string | null): void {
  const w = Math.max(1, Math.round(sw)), h = Math.max(1, Math.round(sh));
  scratch.width = w;
  scratch.height = h;
  const s = scratch.getContext('2d')!;
  s.imageSmoothingEnabled = false;
  s.drawImage(image, sx, sy, sw, sh, 0, 0, w, h);
  if (!color) return;
  s.globalCompositeOperation = 'multiply';
  s.fillStyle = color;
  s.fillRect(0, 0, w, h);
  s.globalCompositeOperation = 'destination-in';
  s.drawImage(image, sx, sy, sw, sh, 0, 0, w, h);
  s.globalCompositeOperation = 'source-over';
}

function shadeColor(shade: number, tint: [number, number, number] | null): string | null {
  if (shade >= 1 && !tint) return null;
  const c = (v: number) => Math.round(Math.max(0, Math.min(1, v * shade)) * 255);
  return `rgb(${c(tint?.[0] ?? 1)}, ${c(tint?.[1] ?? 1)}, ${c(tint?.[2] ?? 1)})`;
}

async function drawModel(ctx: CanvasRenderingContext2D, scratch: HTMLCanvasElement, images: ImageCache, quads: BakedQuad[], ox: number, oy: number): Promise<boolean> {
  const faces: { quad: BakedQuad; points: [number, number, number][]; depth: number }[] = [];
  for (const quad of quads) {
    const points = [0, 1, 2, 3].map((k) => project(quad.pos[k * 3]!, quad.pos[k * 3 + 1]!, quad.pos[k * 3 + 2]!));
    // Corners run counter-clockwise seen from the front; on a screen with y down that is a negative area.
    let area = 0;
    for (let k = 0; k < 4; k++) {
      const a = points[k]!, b = points[(k + 1) % 4]!;
      area += a[0] * b[1] - b[0] * a[1];
    }
    if (area >= -1e-6) continue;
    faces.push({ quad, points, depth: (points[0]![2] + points[1]![2] + points[2]![2] + points[3]![2]) / 4 });
  }
  faces.sort((a, b) => a.depth - b.depth);
  let drawn = false;
  for (const { quad, points } of faces) {
    const image = await images.get(quad.texture);
    if (!image) continue;
    const us = [quad.uv[0]!, quad.uv[2]!, quad.uv[4]!, quad.uv[6]!], vs = [quad.uv[1]!, quad.uv[3]!, quad.uv[5]!, quad.uv[7]!];
    const u0 = Math.min(...us), u1 = Math.max(...us), v0 = Math.min(...vs), v1 = Math.max(...vs);
    // The transform that takes texture coordinates to the screen, from three corners.
    const du1 = us[1]! - us[0]!, dv1 = vs[1]! - vs[0]!, du2 = us[2]! - us[0]!, dv2 = vs[2]! - vs[0]!;
    const det = du1 * dv2 - du2 * dv1;
    if (Math.abs(det) < 1e-9 || u1 - u0 <= 0 || v1 - v0 <= 0) continue;
    const dx1 = points[1]![0] - points[0]![0], dy1 = points[1]![1] - points[0]![1];
    const dx2 = points[2]![0] - points[0]![0], dy2 = points[2]![1] - points[0]![1];
    const a = (dx1 * dv2 - dx2 * dv1) / det, c = (dx2 * du1 - dx1 * du2) / det;
    const b = (dy1 * dv2 - dy2 * dv1) / det, d = (dy2 * du1 - dy1 * du2) / det;
    const e = points[0]![0] - a * us[0]! - c * vs[0]!, f = points[0]![1] - b * us[0]! - d * vs[0]!;
    // Animated textures stack their frames: the first one is the square at the top.
    const pixels = Math.min(image.width, image.height) / 16;
    tinted(scratch, image, u0 * pixels, v0 * pixels, (u1 - u0) * pixels, (v1 - v0) * pixels, shadeColor(quad.shade, quad.tint));
    ctx.setTransform(a, b, c, d, e + ox, f + oy);
    ctx.drawImage(scratch, 0, 0, scratch.width, scratch.height, u0 - BLEED, v0 - BLEED, u1 - u0 + 2 * BLEED, v1 - v0 + 2 * BLEED);
    drawn = true;
  }
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  return drawn;
}

async function drawFlat(ctx: CanvasRenderingContext2D, scratch: HTMLCanvasElement, layers: Uint8Array[], tint: string | null, ox: number, oy: number): Promise<boolean> {
  let drawn = false;
  for (let i = 0; i < layers.length; i++) {
    const image = await decode(layers[i]);
    if (!image) continue;
    const frame = Math.min(image.width, image.height);
    // The block's color (grass, leaves) goes on the first layer, as the game does for these items.
    tinted(scratch, image, 0, 0, frame, frame, i === 0 ? tint : null);
    ctx.drawImage(scratch, 0, 0, scratch.width, scratch.height, ox, oy, CELL, CELL);
    image.close();
    drawn = true;
  }
  return drawn;
}
