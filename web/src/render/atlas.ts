import { MISSING_TEXTURE } from '../core/model/bake';
import type { AtlasRect } from '../core/mesh/prepare';

/**
 * Packs block textures into one canvas on a square grid. Animated textures (tall strips) use their first frame.
 * All tiles are drawn at one cell size (the largest texture, capped) with nearest-neighbor scaling.
 */
export interface Atlas {
  canvas: HTMLCanvasElement;
  rect(texture: string): AtlasRect;
}

const MAX_CELL = 64;

export async function buildAtlas(textures: Iterable<string>, file: (path: string) => Uint8Array | undefined, maxSize: number): Promise<Atlas> {
  const keys = [...new Set([MISSING_TEXTURE, ...textures])];
  const images = await Promise.all(keys.map((key) => load(key, file)));

  let cell = 16;
  for (const image of images) if (image && 'width' in image) cell = Math.max(cell, Math.min(MAX_CELL, image.width));
  let cols = Math.ceil(Math.sqrt(keys.length));
  while (cols * cell > maxSize && cell > 16) cell /= 2;
  cols = Math.min(cols, Math.floor(maxSize / cell));
  const rows = Math.ceil(keys.length / cols);
  if (rows * cell > maxSize) throw new Error(`Too many textures for one atlas (${keys.length})`);

  const canvas = document.createElement('canvas');
  canvas.width = cols * cell;
  canvas.height = rows * cell;
  const ctx = canvas.getContext('2d')!;
  ctx.imageSmoothingEnabled = false;

  const rects = new Map<string, AtlasRect>();
  keys.forEach((key, i) => {
    const x = (i % cols) * cell;
    const y = Math.floor(i / cols) * cell;
    const image = images[i];
    if (key.startsWith('pawprint:color/')) {
      ctx.fillStyle = key.slice('pawprint:color/'.length);
      ctx.fillRect(x, y, cell, cell);
      // A faint inner edge keeps neighboring plain boxes readable.
      ctx.fillStyle = 'rgba(0,0,0,0.12)';
      ctx.fillRect(x, y + cell - cell / 16, cell, cell / 16);
      ctx.fillRect(x + cell - cell / 16, y, cell / 16, cell);
    } else if (image && 'width' in image) {
      const frame = Math.min(image.width, image.height);
      ctx.drawImage(image, 0, 0, frame, frame, x, y, cell, cell);
    } else {
      drawMissing(ctx, x, y, cell);
    }
    rects.set(key, [x / canvas.width, y / canvas.height, (x + cell) / canvas.width, (y + cell) / canvas.height]);
  });
  images.forEach((image) => image && 'close' in image && image.close());

  const missing = rects.get(MISSING_TEXTURE)!;
  return { canvas, rect: (texture) => rects.get(texture) ?? missing };
}

async function load(key: string, file: (path: string) => Uint8Array | undefined): Promise<ImageBitmap | null> {
  if (key.startsWith('pawprint:')) return null;
  const bytes = file(key);
  if (!bytes) return null;
  try {
    return await createImageBitmap(new Blob([bytes as BlobPart], { type: 'image/png' }));
  } catch {
    return null;
  }
}

/** The game's missing-texture look: magenta and black checkers. */
function drawMissing(ctx: CanvasRenderingContext2D, x: number, y: number, cell: number): void {
  const half = cell / 2;
  ctx.fillStyle = '#F800F8';
  ctx.fillRect(x, y, cell, cell);
  ctx.fillStyle = '#000000';
  ctx.fillRect(x + half, y, half, half);
  ctx.fillRect(x, y + half, half, half);
}
