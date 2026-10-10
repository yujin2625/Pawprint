import { unzlibSync } from 'fflate';

/**
 * How transparent a PNG texture is, read without a browser: enough of a PNG decoder to look at the alpha of every
 * pixel. Used to guess a block's render layer when a pack is made from game files.
 */

export type Opacity = 'opaque' | 'cutout' | 'translucent';

const SIGNATURE = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
const MAX_PIXELS = 4096 * 4096;
/** Share of half-transparent pixels from which a texture counts as translucent (below it: smoothed cutout edges). */
const TRANSLUCENT_SHARE = 0.05;

/**
 * `opaque`: every pixel is solid. `cutout`: some pixels are fully transparent. `translucent`: a real share of the
 * pixels is half transparent (glass, ice). Null when the file cannot be read (not a PNG, interlaced, damaged).
 */
export function pngOpacity(bytes: Uint8Array): Opacity | null {
  if (bytes.length < 33 || SIGNATURE.some((b, i) => bytes[i] !== b)) return null;
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let width = 0, height = 0, depth = 0, color = -1, interlace = 0;
  let trns: Uint8Array | null = null;
  const idat: Uint8Array[] = [];
  for (let at = 8; at + 12 <= bytes.length; ) {
    const length = view.getUint32(at);
    const type = String.fromCharCode(bytes[at + 4]!, bytes[at + 5]!, bytes[at + 6]!, bytes[at + 7]!);
    const data = bytes.subarray(at + 8, at + 8 + length);
    if (data.length < length) return null;
    if (type === 'IHDR') {
      if (length < 13) return null;
      width = view.getUint32(at + 8);
      height = view.getUint32(at + 12);
      depth = data[8]!;
      color = data[9]!;
      interlace = data[12]!;
    } else if (type === 'tRNS') trns = data;
    else if (type === 'IDAT') idat.push(data);
    else if (type === 'IEND') break;
    at += 12 + length;
  }
  const channels = ({ 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 } as Record<number, number>)[color];
  if (!channels || !width || !height || width * height > MAX_PIXELS || interlace !== 0 || !idat.length) return null;
  if (![1, 2, 4, 8, 16].includes(depth) || (depth < 8 && color !== 0 && color !== 3)) return null;
  // Without an alpha channel or a tRNS chunk nothing can be transparent.
  const hasAlpha = color === 4 || color === 6;
  if (!hasAlpha && !trns) return 'opaque';

  const bitsPerPixel = channels * depth;
  const stride = Math.ceil((width * bitsPerPixel) / 8);
  const bpp = Math.max(1, bitsPerPixel >> 3);
  let raw: Uint8Array;
  try {
    raw = unzlibSync(concat(idat));
  } catch {
    return null;
  }
  if (raw.length < (stride + 1) * height) return null;

  let clear = 0, partial = 0;
  const count = (alpha: number): void => {
    if (alpha === 0) clear++;
    else if (alpha < 255) partial++;
  };
  // A color named by tRNS is fully transparent (gray and RGB images): compare samples as stored.
  const key = trns && !hasAlpha && color !== 3 ? trns.subarray(0, channels * 2) : null;
  const sampleBytes = depth === 16 ? 2 : 1;
  let prev = new Uint8Array(stride);
  let line = new Uint8Array(stride);
  for (let y = 0; y < height; y++) {
    const start = y * (stride + 1);
    const filter = raw[start]!;
    for (let i = 0; i < stride; i++) {
      const x = raw[start + 1 + i]!;
      const a = i >= bpp ? line[i - bpp]! : 0;
      const b = prev[i]!;
      const c = i >= bpp ? prev[i - bpp]! : 0;
      let value: number;
      if (filter === 0) value = x;
      else if (filter === 1) value = x + a;
      else if (filter === 2) value = x + b;
      else if (filter === 3) value = x + ((a + b) >> 1);
      else if (filter === 4) value = x + paeth(a, b, c);
      else return null;
      line[i] = value & 255;
    }
    if (hasAlpha) {
      // The alpha sample is last; for 16 bits its high byte is enough.
      const offset = (channels - 1) * sampleBytes;
      for (let px = 0; px < width; px++) count(line[px * bpp + offset]!);
    } else if (color === 3) {
      for (let px = 0; px < width; px++) {
        const bit = px * depth;
        const index = depth === 8 ? line[px]! : (line[bit >> 3]! >> (8 - depth - (bit & 7))) & ((1 << depth) - 1);
        count(index < trns!.length ? trns![index]! : 255);
      }
    } else if (key && depth >= 8) {
      for (let px = 0; px < width; px++) {
        let same = true;
        for (let ch = 0; ch < channels && same; ch++) {
          const at = px * bpp + ch * sampleBytes;
          const stored = depth === 16 ? (line[at]! << 8) | line[at + 1]! : line[at]!;
          same = stored === ((key[ch * 2]! << 8) | key[ch * 2 + 1]!);
        }
        count(same ? 0 : 255);
      }
    } else if (key) {
      const transparent = (key[0]! << 8) | key[1]!;
      for (let px = 0; px < width; px++) {
        const bit = px * depth;
        count(((line[bit >> 3]! >> (8 - depth - (bit & 7))) & ((1 << depth) - 1)) === transparent ? 0 : 255);
      }
    }
    [prev, line] = [line, prev];
  }
  if (partial >= Math.max(1, width * height * TRANSLUCENT_SHARE)) return 'translucent';
  return clear + partial > 0 ? 'cutout' : 'opaque';
}

function paeth(a: number, b: number, c: number): number {
  const p = a + b - c;
  const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
  return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
}

function concat(parts: Uint8Array[]): Uint8Array {
  if (parts.length === 1) return parts[0]!;
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let at = 0;
  for (const p of parts) {
    out.set(p, at);
    at += p.length;
  }
  return out;
}
