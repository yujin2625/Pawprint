/// <reference lib="webworker" />
import { buildSection, sectionKey, SECTION, VoxelMap, type MeshPaletteEntry, type SectionMesh } from '../core/mesh/mesher';

/**
 * Meshing worker. The main thread sends the blueprint once (`load`), asks for sections in the order it wants them
 * (nearest first), and sends edits (`update`): the changed sections and their neighbors come back rebuilt.
 * Buffers are transferred, not copied.
 */
export type MesherRequest =
  | { type: 'load'; positions: Int32Array; values: Int32Array; palette: MeshPaletteEntry[] }
  | { type: 'build'; keys: string[] }
  | { type: 'update'; paletteStart: number; palette: MeshPaletteEntry[]; cells: Int32Array };

export type MesherResponse =
  | { type: 'loaded'; sections: { key: string; origin: [number, number, number] }[] }
  | { type: 'section'; mesh: SectionMesh; update: boolean }
  | { type: 'empty'; key: string; update: boolean };

let map = new VoxelMap();
let palette: MeshPaletteEntry[] = [];

self.onmessage = (event: MessageEvent<MesherRequest>) => {
  const msg = event.data;
  if (msg.type === 'load') {
    map = new VoxelMap();
    for (let i = 0; i < msg.values.length; i++) {
      // Removal cells (negative) are not drawn.
      if (msg.values[i]! > 0) map.set(msg.positions[i * 3]!, msg.positions[i * 3 + 1]!, msg.positions[i * 3 + 2]!, msg.values[i]!);
    }
    palette = msg.palette;
    post({ type: 'loaded', sections: [...map.sections.entries()].map(([key, s]) => ({ key, origin: s.origin })) });
    return;
  }
  if (msg.type === 'update') {
    msg.palette.forEach((entry, i) => (palette[msg.paletteStart + i] = entry));
    const dirty = new Set<string>();
    const c = msg.cells;
    for (let i = 0; i < c.length; i += 4) {
      const x = c[i]!, y = c[i + 1]!, z = c[i + 2]!;
      map.set(x, y, z, Math.max(0, c[i + 3]!));
      // A change on a section border can show or hide faces in the neighbor too.
      const sx = Math.floor(x / SECTION), sy = Math.floor(y / SECTION), sz = Math.floor(z / SECTION);
      const lx = x - sx * SECTION, ly = y - sy * SECTION, lz = z - sz * SECTION;
      dirty.add(sectionKey(sx, sy, sz));
      if (lx === 0) dirty.add(sectionKey(sx - 1, sy, sz));
      if (lx === SECTION - 1) dirty.add(sectionKey(sx + 1, sy, sz));
      if (ly === 0) dirty.add(sectionKey(sx, sy - 1, sz));
      if (ly === SECTION - 1) dirty.add(sectionKey(sx, sy + 1, sz));
      if (lz === 0) dirty.add(sectionKey(sx, sy, sz - 1));
      if (lz === SECTION - 1) dirty.add(sectionKey(sx, sy, sz + 1));
    }
    build([...dirty].filter((k) => map.sections.has(k)), true);
    return;
  }
  build(msg.keys, false);
};

function build(keys: string[], update: boolean): void {
  for (const key of keys) {
    const mesh = buildSection(map, key, palette);
    if (!mesh) {
      post({ type: 'empty', key, update });
      continue;
    }
    const transfer: Transferable[] = [];
    for (const layer of mesh.layers) {
      if (layer) transfer.push(layer.positions.buffer, layer.uvs.buffer, layer.colors.buffer, layer.indices.buffer);
    }
    post({ type: 'section', mesh, update }, transfer);
  }
}

function post(message: MesherResponse, transfer: Transferable[] = []): void {
  (self as unknown as DedicatedWorkerGlobalScope).postMessage(message, transfer);
}
