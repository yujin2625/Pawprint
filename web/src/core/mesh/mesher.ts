/**
 * Builds render buffers for 16×16×16 sections of a blueprint. Pure data in, typed arrays out, so it runs in a
 * Web Worker and in tests. Palette entries come pre-baked with atlas texture coordinates (see prepare.ts).
 */

export const SECTION = 16;
export const LAYERS = ['solid', 'cutout', 'translucent'] as const;
export type LayerIndex = 0 | 1 | 2;

/** Direction order shared with prepare.ts: down, up, north, south, west, east. */
export const DIR_OFFSETS: [number, number, number][] = [[0, -1, 0], [0, 1, 0], [0, 0, -1], [0, 0, 1], [-1, 0, 0], [1, 0, 0]];
const OPPOSITE_DIR = [1, 0, 3, 2, 5, 4];

/**
 * One palette entry, ready to mesh. `quads` holds QUAD_FLOATS numbers per quad:
 * 12 position (block units), 8 atlas uv (0..1), 3 color (0..1, shade × tint), 1 cull direction (-1 = never).
 */
export interface MeshPaletteEntry {
  quads: Float32Array;
  /** Bit i set: this block covers its whole face in direction i. */
  fullFaces: number;
  layer: LayerIndex;
  /** Same number = same block ID; used to hide faces between glass panes, water, … */
  blockKey: number;
  /** Faces against the same block are hidden even if not full (fluids). */
  joinSame: boolean;
}

export const QUAD_FLOATS = 24;

export interface LayerBuffers {
  positions: Float32Array;
  uvs: Float32Array;
  colors: Uint8Array;
  indices: Uint32Array;
}

export interface SectionMesh {
  key: string;
  origin: [number, number, number];
  layers: (LayerBuffers | null)[];
  quadCount: number;
}

export function sectionKey(sx: number, sy: number, sz: number): string {
  return sx + ',' + sy + ',' + sz;
}

/** Block cells grouped by section; each cell holds palette index + 1 (0 = empty). */
export class VoxelMap {
  readonly sections = new Map<string, { origin: [number, number, number]; cells: Uint32Array }>();

  static fromArrays(positions: Int32Array, states: Int32Array, hidden?: (i: number) => boolean): VoxelMap {
    const map = new VoxelMap();
    for (let i = 0; i < states.length; i++) {
      if (hidden?.(i)) continue;
      map.set(positions[i * 3]!, positions[i * 3 + 1]!, positions[i * 3 + 2]!, states[i]! + 1);
    }
    return map;
  }

  set(x: number, y: number, z: number, value: number): void {
    const sx = Math.floor(x / SECTION), sy = Math.floor(y / SECTION), sz = Math.floor(z / SECTION);
    const key = sectionKey(sx, sy, sz);
    let section = this.sections.get(key);
    if (!section) {
      if (value === 0) return;
      section = { origin: [sx * SECTION, sy * SECTION, sz * SECTION], cells: new Uint32Array(SECTION ** 3) };
      this.sections.set(key, section);
    }
    section.cells[((y - section.origin[1]) * SECTION + (z - section.origin[2])) * SECTION + (x - section.origin[0])] = value;
  }

  get(x: number, y: number, z: number): number {
    const section = this.sections.get(sectionKey(Math.floor(x / SECTION), Math.floor(y / SECTION), Math.floor(z / SECTION)));
    if (!section) return 0;
    return section.cells[((y - section.origin[1]) * SECTION + (z - section.origin[2])) * SECTION + (x - section.origin[0])]!;
  }
}

class Growable {
  positions: Float32Array;
  uvs: Float32Array;
  colors: Uint8Array;
  indices: Uint32Array;
  quads = 0;

  constructor(capacity = 256) {
    this.positions = new Float32Array(capacity * 12);
    this.uvs = new Float32Array(capacity * 8);
    this.colors = new Uint8Array(capacity * 12);
    this.indices = new Uint32Array(capacity * 6);
  }

  private grow(): void {
    const cap = (this.positions.length / 12) * 2;
    const next = new Growable(cap);
    next.positions.set(this.positions);
    next.uvs.set(this.uvs);
    next.colors.set(this.colors);
    next.indices.set(this.indices);
    Object.assign(this, { positions: next.positions, uvs: next.uvs, colors: next.colors, indices: next.indices });
  }

  add(src: Float32Array, at: number, x: number, y: number, z: number): void {
    if ((this.quads + 1) * 12 > this.positions.length) this.grow();
    const q = this.quads, p = q * 12, t = q * 8, c = q * 12, v = q * 4;
    for (let k = 0; k < 4; k++) {
      this.positions[p + k * 3] = src[at + k * 3]! + x;
      this.positions[p + k * 3 + 1] = src[at + k * 3 + 1]! + y;
      this.positions[p + k * 3 + 2] = src[at + k * 3 + 2]! + z;
      this.uvs[t + k * 2] = src[at + 12 + k * 2]!;
      this.uvs[t + k * 2 + 1] = src[at + 12 + k * 2 + 1]!;
      this.colors[c + k * 3] = src[at + 20]! * 255;
      this.colors[c + k * 3 + 1] = src[at + 21]! * 255;
      this.colors[c + k * 3 + 2] = src[at + 22]! * 255;
    }
    const i = q * 6;
    this.indices[i] = v;
    this.indices[i + 1] = v + 1;
    this.indices[i + 2] = v + 2;
    this.indices[i + 3] = v;
    this.indices[i + 4] = v + 2;
    this.indices[i + 5] = v + 3;
    this.quads++;
  }

  finish(): LayerBuffers | null {
    if (this.quads === 0) return null;
    return {
      positions: this.positions.slice(0, this.quads * 12),
      uvs: this.uvs.slice(0, this.quads * 8),
      colors: this.colors.slice(0, this.quads * 12),
      indices: this.indices.slice(0, this.quads * 6),
    };
  }
}

/** Positions in the output are relative to the section origin (keeps float precision for far-away sections). */
export function buildSection(map: VoxelMap, key: string, palette: MeshPaletteEntry[]): SectionMesh | null {
  const section = map.sections.get(key);
  if (!section) return null;
  const [ox, oy, oz] = section.origin;
  const out = [new Growable(), new Growable(), new Growable()];
  const cells = section.cells;
  let quadCount = 0;
  for (let ly = 0; ly < SECTION; ly++) {
    for (let lz = 0; lz < SECTION; lz++) {
      for (let lx = 0; lx < SECTION; lx++) {
        const value = cells[(ly * SECTION + lz) * SECTION + lx]!;
        if (value === 0) continue;
        const entry = palette[value - 1];
        if (!entry) continue;
        const quads = entry.quads;
        for (let at = 0; at < quads.length; at += QUAD_FLOATS) {
          const cull = quads[at + 23]!;
          if (cull >= 0) {
            const d = DIR_OFFSETS[cull]!;
            const nx = lx + d[0], ny = ly + d[1], nz = lz + d[2];
            const neighbor =
              nx >= 0 && nx < SECTION && ny >= 0 && ny < SECTION && nz >= 0 && nz < SECTION
                ? cells[(ny * SECTION + nz) * SECTION + nx]!
                : map.get(ox + nx, oy + ny, oz + nz);
            if (neighbor !== 0) {
              const other = palette[neighbor - 1];
              if (other && hides(entry, other, cull)) continue;
            }
          }
          out[entry.layer]!.add(quads, at, lx, ly, lz);
          quadCount++;
        }
      }
    }
  }
  return { key, origin: [ox, oy, oz], layers: out.map((g) => g.finish()), quadCount };
}

function hides(self: MeshPaletteEntry, other: MeshPaletteEntry, dir: number): boolean {
  const facing = (other.fullFaces >> OPPOSITE_DIR[dir]!) & 1;
  if (facing && other.layer === 0) return true;
  if (other.blockKey === self.blockKey && self.layer !== 0) return facing === 1 || self.joinSame;
  return false;
}
