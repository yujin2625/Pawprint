import { utf8 } from '../zip';
import { pngPixels } from './png';
import { blockstateModels, blockstatePath, modelPath, texturePath } from './resources';
import { DEFAULT_COLORS, type BlockDef } from './types';

/**
 * What a block looks like, in words an AI can use: the average color of its main texture and whether light passes
 * through it. Read from the pack's models and textures, without a browser.
 */
export interface BlockLook {
  /** `#RRGGBB`, the average of the texture's visible pixels (with the block's tint). */
  color: string;
  /** `translucent`: colored light passes (stained glass, ice); `see-through`: has clear gaps (glass, leaves, doors). */
  look: 'translucent' | 'see-through' | null;
}

/** Texture keys that show the block best, in order; after them any other texture of the model. */
const KEYS = ['all', 'side', 'texture', 'front', 'wall', 'end', 'top', 'cross', 'plant', 'pattern', 'layer0', 'particle'];
/** Tinted blocks: the tint is on the top (grass) or the whole texture (leaves, plants), not on the sides. */
const TINTED_KEYS = ['top', 'all', 'cross', 'plant', 'layer0', 'texture', 'particle'];
const MAX_PARENTS = 16;
/** Share of half-transparent pixels from which a texture counts as translucent (below it: smoothed edges). */
const TRANSLUCENT_SHARE = 0.05;

interface TextureStats {
  rgb: [number, number, number];
  clear: number;
  partial: number;
  pixels: number;
}

/** The look of every block whose main texture can be found; others are left out. */
export function blockLooks(blocks: BlockDef[], files: Map<string, Uint8Array>): Map<string, BlockLook> {
  const models = new Map<string, Record<string, string> | null>();
  const textures = new Map<string, TextureStats | null>();
  const out = new Map<string, BlockLook>();
  for (const block of blocks) {
    if (block.renderShape === 'invisible') continue;
    const ref = mainTexture(block.id, block.tint ? TINTED_KEYS : KEYS, files, models);
    if (!ref) continue;
    let stats = textures.get(ref);
    if (stats === undefined) {
      const png = files.get(texturePath(ref));
      stats = png ? textureStats(png) : null;
      textures.set(ref, stats);
    }
    if (!stats) continue;
    let [r, g, b] = stats.rgb;
    const tint = block.tint ? (block.tint.kind === 'grass' || block.tint.kind === 'foliage' || block.tint.kind === 'water' ? block.tint.color || DEFAULT_COLORS[block.tint.kind] : block.tint.color) : null;
    if (tint && /^#[0-9a-f]{6}$/i.test(tint)) {
      const n = parseInt(tint.slice(1), 16);
      r = (r * (n >> 16)) / 255;
      g = (g * ((n >> 8) & 255)) / 255;
      b = (b * (n & 255)) / 255;
    }
    let look: BlockLook['look'] = null;
    if (block.renderLayer === 'translucent' || (block.renderLayer !== 'solid' && stats.partial >= stats.pixels * TRANSLUCENT_SHARE)) look = 'translucent';
    else if (block.renderLayer !== 'solid' && stats.clear + stats.partial > 0) look = 'see-through';
    out.set(block.id, { color: hex(r, g, b), look });
  }
  return out;
}

const hex = (r: number, g: number, b: number): string => '#' + [r, g, b].map((v) => Math.round(v).toString(16).padStart(2, '0')).join('').toUpperCase();

/** The texture that shows a block best: from its first model, following parents and `#key` references. */
function mainTexture(id: string, keys: string[], files: Map<string, Uint8Array>, models: Map<string, Record<string, string> | null>): string | null {
  const state = files.get(blockstatePath(id));
  if (!state) return null;
  let ref: string | undefined;
  try {
    ref = blockstateModels(JSON.parse(utf8(state)))[0];
  } catch {
    return null;
  }
  if (!ref) return null;
  let textures = models.get(ref);
  if (textures === undefined) {
    textures = modelTextures(ref, files);
    models.set(ref, textures);
  }
  if (!textures) return null;
  const resolve = (value: string | undefined): string | null => {
    for (let i = 0; value !== undefined && i < MAX_PARENTS; i++) {
      if (!value.startsWith('#')) return value;
      value = textures![value.slice(1)];
    }
    return null;
  };
  for (const key of keys) {
    const found = resolve(textures[key]);
    if (found) return found;
  }
  for (const value of Object.values(textures)) {
    const found = resolve(value);
    if (found) return found;
  }
  return null;
}

/** A model's textures with its parents' underneath (the child wins). */
function modelTextures(ref: string, files: Map<string, Uint8Array>): Record<string, string> | null {
  const merged: Record<string, string> = {};
  let found = false;
  for (let i = 0, current: string | undefined = ref; current && i < MAX_PARENTS; i++) {
    const file = files.get(modelPath(current));
    if (!file) break;
    let model: { parent?: unknown; textures?: Record<string, unknown> };
    try {
      model = JSON.parse(utf8(file)) as typeof model;
    } catch {
      break;
    }
    found = true;
    for (const [key, value] of Object.entries(model.textures ?? {})) {
      if (typeof value === 'string' && !(key in merged)) merged[key] = value;
    }
    current = typeof model.parent === 'string' ? model.parent : undefined;
  }
  return found ? merged : null;
}

/** Average color of the visible pixels, weighted by how opaque they are, and how many are (half) transparent. */
function textureStats(png: Uint8Array): TextureStats | null {
  const image = pngPixels(png);
  if (!image) return null;
  const { rgba } = image;
  let r = 0, g = 0, b = 0, weight = 0, clear = 0, partial = 0;
  for (let i = 0; i < rgba.length; i += 4) {
    const a = rgba[i + 3]!;
    if (a === 0) clear++;
    else if (a < 255) partial++;
    r += rgba[i]! * a;
    g += rgba[i + 1]! * a;
    b += rgba[i + 2]! * a;
    weight += a;
  }
  if (!weight) return null;
  return { rgb: [r / weight, g / weight, b / weight], clear, partial, pixels: rgba.length / 4 };
}
