/// <reference lib="webworker" />
import { buildSection, VoxelMap, type MeshPaletteEntry, type SectionMesh } from '../core/mesh/mesher';

/**
 * Meshing worker. The main thread sends the blueprint once (`load`), then asks for sections in the order it wants
 * them (nearest first). Each finished section comes back with its buffers transferred, not copied.
 */
export type MesherRequest =
  | { type: 'load'; positions: Int32Array; states: Int32Array; palette: MeshPaletteEntry[] }
  | { type: 'build'; keys: string[] };

export type MesherResponse =
  | { type: 'loaded'; sections: { key: string; origin: [number, number, number] }[] }
  | { type: 'section'; mesh: SectionMesh }
  | { type: 'empty'; key: string };

let map = new VoxelMap();
let palette: MeshPaletteEntry[] = [];

self.onmessage = (event: MessageEvent<MesherRequest>) => {
  const msg = event.data;
  if (msg.type === 'load') {
    map = VoxelMap.fromArrays(msg.positions, msg.states);
    palette = msg.palette;
    const sections = [...map.sections.entries()].map(([key, s]) => ({ key, origin: s.origin }));
    post({ type: 'loaded', sections });
    return;
  }
  for (const key of msg.keys) {
    const mesh = buildSection(map, key, palette);
    if (!mesh) {
      post({ type: 'empty', key });
      continue;
    }
    const transfer: Transferable[] = [];
    for (const layer of mesh.layers) {
      if (layer) transfer.push(layer.positions.buffer, layer.uvs.buffer, layer.colors.buffer, layer.indices.buffer);
    }
    post({ type: 'section', mesh }, transfer);
  }
};

function post(message: MesherResponse, transfer: Transferable[] = []): void {
  (self as unknown as DedicatedWorkerGlobalScope).postMessage(message, transfer);
}
