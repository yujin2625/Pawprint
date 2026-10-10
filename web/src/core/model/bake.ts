import { BlockDefinition } from 'deepslate/render';
import { utf8 } from '../zip';
import { blockstatePath, modelPath, splitLocation, texturePath } from '../pack/resources';
import type { BlockDef, RenderLayer, Tint } from '../pack/types';
import { parseState } from './state';

/**
 * Turns a block state into a list of textured quads in block space, once per state. Variant selection comes from
 * deepslate; model flattening, element/variant rotation, uvlock and face shading follow the game's rules.
 */

export const DIRS = ['down', 'up', 'north', 'south', 'west', 'east'] as const;
export type Dir = (typeof DIRS)[number];
export const DIR_VECTORS: Record<Dir, [number, number, number]> = {
  down: [0, -1, 0], up: [0, 1, 0], north: [0, 0, -1], south: [0, 0, 1], west: [-1, 0, 0], east: [1, 0, 0],
};
export const OPPOSITE: Record<Dir, Dir> = { down: 'up', up: 'down', north: 'south', south: 'north', west: 'east', east: 'west' };

export interface BakedQuad {
  /** 4 corners, x y z each, in block units (0..1), counter-clockwise seen from the front. */
  pos: number[];
  /** 4 corners, u v each, in texture pixels of a 16×16 tile (0..16). */
  uv: number[];
  /** Texture resource path, e.g. `assets/minecraft/textures/block/stone.png`, or a generated key. */
  texture: string;
  /** Directional brightness (1 = top). */
  shade: number;
  tint: [number, number, number] | null;
  /** Skip this quad when the neighbor in this direction hides it. */
  cull: Dir | null;
}

export interface BakedModel {
  quads: BakedQuad[];
  /** Directions in which the model covers the whole block face (used to hide neighbor faces). */
  fullFaces: Set<Dir>;
  layer: RenderLayer;
  blockId: string;
  /** True for the stand-in shown when the pack has no model for this block. */
  missing: boolean;
}

export interface ModelSource {
  file(path: string): Uint8Array | undefined;
  block(id: string): BlockDef | undefined;
  /** Whether the pack has an item icon for the block (icons.png). */
  hasIcon?(id: string): boolean;
}

export const MISSING_TEXTURE = 'pawprint:missing';
/** Generated flat color texture: `pawprint:color/#RRGGBB`. */
export const colorTexture = (hex: string): string => 'pawprint:color/' + hex;
/** A block's item icon from the pack's icons.png: `pawprint:icon/<block id>`. */
export const iconTexture = (id: string): string => 'pawprint:icon/' + id;

interface ModelElement {
  from: number[];
  to: number[];
  rotation?: { origin: number[]; axis: 'x' | 'y' | 'z'; angle: number; rescale?: boolean };
  shade?: boolean;
  faces?: Partial<Record<Dir, { texture: string; uv?: number[]; cullface?: Dir; rotation?: number; tintindex?: number }>>;
}

interface FlatModel {
  textures: Record<string, string>;
  elements: ModelElement[];
}

const SHADE: Record<Dir, number> = { up: 1, down: 0.5, north: 0.8, south: 0.8, west: 0.6, east: 0.6 };

export class ModelBaker {
  private readonly models = new Map<string, FlatModel | null>();
  private readonly definitions = new Map<string, BlockDefinition | null>();
  private readonly cache = new Map<string, BakedModel>();

  constructor(private readonly source: ModelSource | null) {}

  bake(state: string): BakedModel {
    let baked = this.cache.get(state);
    if (!baked) {
      try {
        baked = this.bakeUncached(state);
      } catch {
        // A model this code cannot read (mods write surprising JSON) must not take the pack or the view down.
        baked = colorBox(parseState(state).id, true);
      }
      this.cache.set(state, baked);
    }
    return baked;
  }

  private bakeUncached(state: string): BakedModel {
    const { id, props } = parseState(state);
    if (!this.source) return colorBox(id, false);
    const def = this.source.block(id);
    if (!def) return colorBox(id, true);
    const full = { ...def.default, ...props };
    const tint = tintFor(def.tint, full);

    if (def.renderShape === 'invisible') {
      // Signs and some other block-entity blocks are "invisible" to the chunk renderer; their icon still says what
      // they are. Air and the like have no icon.
      if (this.source.hasIcon?.(id)) return insetBox(id, iconTexture(id), 'cutout');
      return { quads: [], fullFaces: new Set(), layer: def.renderLayer, blockId: id, missing: false };
    }
    if (def.fluid) return fluidBox(def, tint, this.particle(id, full));

    const definition = this.definition(id);
    const quads: BakedQuad[] = [];
    if (definition) {
      for (const variant of definition.getModelVariants(full)) {
        const model = this.model(variant.model);
        if (!model) continue;
        for (const element of model.elements) {
          bakeElement(element, model.textures, variant.x ?? 0, variant.y ?? 0, variant.uvlock === true, tint, quads);
        }
      }
    }
    if (quads.length === 0) {
      // Chests, signs, beds… are drawn by the game in code. Show a slightly inset box with the item icon the mod
      // exported, or else the particle texture.
      if (this.source.hasIcon?.(id)) return insetBox(id, iconTexture(id), 'cutout');
      const particle = this.particle(id, full);
      if (def.renderShape === 'entity' && particle) return insetBox(id, particle, def.renderLayer);
      return colorBox(id, true);
    }
    return { quads, fullFaces: fullFaces(quads), layer: def.renderLayer, blockId: id, missing: false };
  }

  private definition(id: string): BlockDefinition | null {
    if (!this.definitions.has(id)) {
      const data = this.source?.file(blockstatePath(id));
      let def: BlockDefinition | null = null;
      try {
        if (data) def = BlockDefinition.fromJson(stringConditions(JSON.parse(utf8(data))));
      } catch {
        def = null;
      }
      this.definitions.set(id, def);
    }
    return this.definitions.get(id) ?? null;
  }

  private particle(id: string, props: Record<string, string>): string | null {
    const definition = this.definition(id);
    const variant = definition?.getModelVariants(props)[0];
    const model = variant ? this.model(variant.model) : null;
    const ref = model ? resolveTexture(model.textures, '#particle') : null;
    return ref ? texturePath(ref) : null;
  }

  /** Follows parents: textures merge (child wins), elements come from the nearest model that has them. */
  private model(ref: string): FlatModel | null {
    const key = normalizeModelRef(ref);
    if (this.models.has(key)) return this.models.get(key) ?? null;
    this.models.set(key, null);
    const chain: { textures?: Record<string, unknown>; elements?: ModelElement[] }[] = [];
    let current: string | null = key;
    for (let depth = 0; current && depth < 32; depth++) {
      if (splitLocation(current)[1].startsWith('builtin/')) break;
      const data = this.source?.file(modelPath(current));
      if (!data) break;
      let json: { parent?: unknown; textures?: Record<string, unknown>; elements?: ModelElement[] };
      try {
        json = JSON.parse(utf8(data));
      } catch {
        break;
      }
      chain.push(json);
      current = typeof json.parent === 'string' ? normalizeModelRef(json.parent) : null;
    }
    if (chain.length === 0) return null;
    const textures: Record<string, string> = {};
    for (let i = chain.length - 1; i >= 0; i--) {
      for (const [k, v] of Object.entries(chain[i]!.textures ?? {})) {
        const value = typeof v === 'string' ? v : (v as { sprite?: string } | null)?.sprite;
        if (typeof value === 'string') textures[k] = value;
      }
    }
    const elements = chain.find((m) => Array.isArray(m.elements))?.elements ?? [];
    const flat = { textures, elements };
    this.models.set(key, flat);
    return flat;
  }
}

/**
 * Multipart conditions with their values as strings. The game also accepts `"north": true` and `"age": 3`, which
 * some mods write; the blockstate reader expects `"true"` and `"3"`.
 */
function stringConditions(blockstate: unknown): unknown {
  const multipart = (blockstate as { multipart?: unknown } | null)?.multipart;
  if (!Array.isArray(multipart)) return blockstate;
  const fix = (when: unknown): unknown => {
    if (!when || typeof when !== 'object' || Array.isArray(when)) return when;
    return Object.fromEntries(
      Object.entries(when).map(([key, value]) => [key, (key === 'OR' || key === 'AND') && Array.isArray(value) ? value.map(fix) : String(value)]),
    );
  };
  return { ...(blockstate as object), multipart: multipart.map((part) => (part && typeof part === 'object' && 'when' in part ? { ...part, when: fix(part.when) } : part)) };
}

function normalizeModelRef(ref: string): string {
  const [ns, path] = splitLocation(ref);
  return `${ns}:${path}`;
}

function resolveTexture(textures: Record<string, string>, ref: string): string | null {
  let value: string | undefined = ref;
  for (let i = 0; i < 16 && value?.startsWith('#'); i++) value = textures[value.slice(1)];
  return value && !value.startsWith('#') ? value : null;
}

function tintFor(tint: Tint | null, props: Record<string, string>): [number, number, number] | null {
  if (!tint) return null;
  let hex = tint.color;
  if (tint.byState) {
    for (const [key, color] of Object.entries(tint.byState)) {
      if (key.split(',').every((pair) => {
        const [k, v] = pair.split('=');
        return k !== undefined && props[k] === v;
      })) {
        hex = color;
        break;
      }
    }
  }
  return hexToRgb(hex);
}

export function hexToRgb(hex: string): [number, number, number] {
  const n = parseInt(hex.slice(1), 16);
  return [((n >> 16) & 255) / 255, ((n >> 8) & 255) / 255, (n & 255) / 255];
}

/** Corners of each face in cyclic order (winding is fixed afterwards). */
function corners(dir: Dir, f: number[], t: number[]): number[][] {
  const [x0, y0, z0] = f as [number, number, number];
  const [x1, y1, z1] = t as [number, number, number];
  switch (dir) {
    case 'up': return [[x0, y1, z0], [x1, y1, z0], [x1, y1, z1], [x0, y1, z1]];
    case 'down': return [[x0, y0, z0], [x1, y0, z0], [x1, y0, z1], [x0, y0, z1]];
    case 'north': return [[x0, y0, z0], [x1, y0, z0], [x1, y1, z0], [x0, y1, z0]];
    case 'south': return [[x0, y0, z1], [x1, y0, z1], [x1, y1, z1], [x0, y1, z1]];
    case 'west': return [[x0, y0, z0], [x0, y0, z1], [x0, y1, z1], [x0, y1, z0]];
    case 'east': return [[x1, y0, z0], [x1, y0, z1], [x1, y1, z1], [x1, y1, z0]];
  }
}

/** The game's default texture mapping for a face, as (u, v) from a point in pixels. */
function defaultUv(dir: Dir, p: number[]): [number, number] {
  const [x, y, z] = p as [number, number, number];
  switch (dir) {
    case 'up': return [x, z];
    case 'down': return [x, 16 - z];
    case 'north': return [16 - x, 16 - y];
    case 'south': return [x, 16 - y];
    case 'west': return [z, 16 - y];
    case 'east': return [16 - z, 16 - y];
  }
}

type Mat = number[]; // 3×3 row-major

function rotX(deg: number): Mat {
  const r = (deg * Math.PI) / 180, c = Math.cos(r), s = Math.sin(r);
  return [1, 0, 0, 0, c, -s, 0, s, c];
}
function rotY(deg: number): Mat {
  const r = (deg * Math.PI) / 180, c = Math.cos(r), s = Math.sin(r);
  return [c, 0, s, 0, 1, 0, -s, 0, c];
}
function rotZ(deg: number): Mat {
  const r = (deg * Math.PI) / 180, c = Math.cos(r), s = Math.sin(r);
  return [c, -s, 0, s, c, 0, 0, 0, 1];
}
function mul(a: Mat, b: Mat): Mat {
  const o = new Array(9).fill(0);
  for (let i = 0; i < 3; i++) for (let j = 0; j < 3; j++) for (let k = 0; k < 3; k++) o[i * 3 + j] += a[i * 3 + k]! * b[k * 3 + j]!;
  return o;
}
function apply(m: Mat, v: number[]): number[] {
  return [m[0]! * v[0]! + m[1]! * v[1]! + m[2]! * v[2]!, m[3]! * v[0]! + m[4]! * v[1]! + m[5]! * v[2]!, m[6]! * v[0]! + m[7]! * v[1]! + m[8]! * v[2]!];
}

function nearestDir(v: number[]): Dir {
  let best: Dir = 'up';
  let bestDot = -Infinity;
  for (const d of DIRS) {
    const n = DIR_VECTORS[d];
    const dot = n[0] * v[0]! + n[1] * v[1]! + n[2] * v[2]!;
    if (dot > bestDot) (bestDot = dot), (best = d);
  }
  return best;
}

function bakeElement(
  e: ModelElement,
  textures: Record<string, string>,
  vx: number,
  vy: number,
  uvlock: boolean,
  tint: [number, number, number] | null,
  out: BakedQuad[],
): void {
  if (!Array.isArray(e.from) || !Array.isArray(e.to) || !e.faces) return;
  // Element rotation (about its origin, optional rescale), then variant rotation about the block center: x first, then y.
  let elementMat: Mat | null = null;
  let origin = [8, 8, 8];
  if (e.rotation && e.rotation.angle) {
    const { axis, angle } = e.rotation;
    origin = e.rotation.origin ?? origin;
    elementMat = axis === 'x' ? rotX(angle) : axis === 'y' ? rotY(angle) : rotZ(angle);
    if (e.rotation.rescale) {
      const s = 1 / Math.cos((Math.abs(angle) * Math.PI) / 180);
      const scale = axis === 'x' ? [1, s, s] : axis === 'y' ? [s, 1, s] : [s, s, 1];
      elementMat = mul(elementMat, [scale[0]!, 0, 0, 0, scale[1]!, 0, 0, 0, scale[2]!]);
    }
  }
  const variantMat = mul(rotY(-vy), rotX(-vx));
  const rotated = vx !== 0 || vy !== 0;

  for (const dir of DIRS) {
    const face = e.faces[dir];
    if (!face?.texture) continue;
    // Face textures are always variable names; the game accepts them with or without '#'.
    const ref = resolveTexture(textures, face.texture.startsWith('#') ? face.texture : '#' + face.texture);
    const texture = ref ? texturePath(ref) : MISSING_TEXTURE;
    let pts = corners(dir, e.from, e.to);

    // Fix winding so the quad faces outward.
    const n = DIR_VECTORS[dir];
    const a = pts[0]!, b = pts[1]!, c = pts[2]!;
    const ab = [b[0]! - a[0]!, b[1]! - a[1]!, b[2]! - a[2]!];
    const ac = [c[0]! - a[0]!, c[1]! - a[1]!, c[2]! - a[2]!];
    const cross = [ab[1]! * ac[2]! - ab[2]! * ac[1]!, ab[2]! * ac[0]! - ab[0]! * ac[2]!, ab[0]! * ac[1]! - ab[1]! * ac[0]!];
    if (cross[0]! * n[0] + cross[1]! * n[1] + cross[2]! * n[2] < 0) pts = [pts[0]!, pts[3]!, pts[2]!, pts[1]!];

    // Texture coordinates: default mapping, remapped into the face's uv box, then the face's own rotation.
    const def = pts.map((p) => defaultUv(dir, p));
    const du0 = Math.min(...def.map((d) => d[0])), du1 = Math.max(...def.map((d) => d[0]));
    const dv0 = Math.min(...def.map((d) => d[1])), dv1 = Math.max(...def.map((d) => d[1]));
    const box = face.uv && face.uv.length === 4 ? face.uv : [du0, dv0, du1, dv1];
    const rotation = ((face.rotation ?? 0) % 360 + 360) % 360;
    let uv = def.flatMap(([u, v]) => {
      let fu = du1 > du0 ? (u - du0) / (du1 - du0) : 0;
      let fv = dv1 > dv0 ? (v - dv0) / (dv1 - dv0) : 0;
      for (let r = 0; r < rotation; r += 90) [fu, fv] = [fv, 1 - fu];
      return [box[0]! + fu * (box[2]! - box[0]!), box[1]! + fv * (box[3]! - box[1]!)];
    });

    // Positions: element rotation, then variant rotation.
    pts = pts.map((p) => {
      let q = p;
      if (elementMat) q = apply(elementMat, [q[0]! - origin[0]!, q[1]! - origin[1]!, q[2]! - origin[2]!]).map((v, i) => v + origin[i]!);
      q = apply(variantMat, [q[0]! - 8, q[1]! - 8, q[2]! - 8]).map((v) => v + 8);
      return q;
    });
    let normal = apply(variantMat, elementMat ? apply(elementMat, n) : n);
    const finalDir = nearestDir(normal);

    // uvlock keeps the texture aligned to the world: map again from the rotated positions.
    if (uvlock && rotated && !elementMat) uv = pts.flatMap((p) => defaultUv(finalDir, p));

    const cull = face.cullface ? nearestDir(apply(variantMat, DIR_VECTORS[face.cullface])) : null;
    out.push({
      pos: pts.flatMap((p) => p.map((v) => v / 16)),
      uv,
      texture,
      shade: e.shade === false ? 1 : shadeFor(normal),
      tint: face.tintindex !== undefined && face.tintindex >= 0 ? tint : null,
      cull,
    });
    normal = [];
  }
}

function shadeFor(normal: number[]): number {
  // Blend the axis brightnesses by the normal so tilted faces (rotated elements) fall in between.
  const x = Math.abs(normal[0]!), y = normal[1]!, z = Math.abs(normal[2]!);
  const len = x + Math.abs(y) + z || 1;
  return (x * SHADE.east + z * SHADE.north + Math.abs(y) * (y > 0 ? SHADE.up : SHADE.down)) / len;
}

function fullFaces(quads: BakedQuad[]): Set<Dir> {
  const out = new Set<Dir>();
  for (const q of quads) {
    if (!q.cull) continue;
    const axis = DIR_VECTORS[q.cull].findIndex((v) => v !== 0);
    const others = [0, 1, 2].filter((i) => i !== axis);
    const covers = others.every((i) => {
      const vals = [q.pos[i]!, q.pos[i + 3]!, q.pos[i + 6]!, q.pos[i + 9]!];
      return Math.min(...vals) <= 0.001 && Math.max(...vals) >= 0.999;
    });
    if (covers) out.add(q.cull);
  }
  return out;
}

/** A unit cube with one texture on every face. */
function cube(texture: string, inset: number, tint: [number, number, number] | null, cullFaces: boolean, height = 1): BakedQuad[] {
  const lo = inset * 16, hi = 16 - inset * 16;
  const element: ModelElement = { from: [lo, 0, lo], to: [hi, height * 16 - (inset ? inset * 16 : 0), hi], faces: {} };
  for (const d of DIRS) element.faces![d] = { texture: '#t', tintindex: tint ? 0 : undefined, ...(cullFaces ? { cullface: d } : {}) };
  const quads: BakedQuad[] = [];
  bakeElement(element, { t: texture.startsWith('pawprint:') ? texture : texture }, 0, 0, false, tint, quads);
  // bakeElement resolves '#t' to a resource location; put our texture key back.
  for (const q of quads) q.texture = texture;
  return quads;
}

function colorBox(id: string, missing: boolean): BakedModel {
  const texture = missing ? MISSING_TEXTURE : colorTexture(colorForId(id));
  const quads = cube(texture, 0, null, true);
  return { quads, fullFaces: new Set(DIRS), layer: 'solid', blockId: id, missing };
}

function insetBox(id: string, texture: string, layer: RenderLayer): BakedModel {
  return { quads: cube(texture, 1 / 16, null, false), fullFaces: new Set(), layer: layer === 'translucent' ? layer : 'cutout', blockId: id, missing: false };
}

function fluidBox(def: BlockDef, tint: [number, number, number] | null, particle: string | null): BakedModel {
  const [ns, path] = splitLocation(def.id);
  const still = `assets/${ns}/textures/block/${path}_still.png`;
  const quads = cube(particle && !particle.endsWith('_still.png') ? still : particle ?? still, 0, tint, true, 14 / 16);
  for (const q of quads) if (q.cull === 'up') q.cull = null; // the surface is lower than a full block
  return { quads, fullFaces: new Set(), layer: path === 'lava' ? 'solid' : 'translucent', blockId: def.id, missing: false };
}

/** Stable color from a block ID, for when no pack is open. */
export function colorForId(id: string): string {
  let h = 2166136261;
  for (let i = 0; i < id.length; i++) h = Math.imul(h ^ id.charCodeAt(i), 16777619);
  const hue = (h >>> 0) % 360;
  const light = 45 + ((h >>> 9) % 20);
  return hslToHex(hue, 35, light);
}

function hslToHex(h: number, s: number, l: number): string {
  const a = (s / 100) * Math.min(l / 100, 1 - l / 100);
  const f = (n: number) => {
    const k = (n + h / 30) % 12;
    const c = l / 100 - a * Math.max(-1, Math.min(k - 3, 9 - k, 1));
    return Math.round(c * 255).toString(16).padStart(2, '0');
  };
  return ('#' + f(0) + f(8) + f(4)).toUpperCase();
}
