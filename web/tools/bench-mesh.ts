// Times baking and meshing a real blueprint with a pack built from a local client jar.
// Run: npx vite-node tools/bench-mesh.ts <client.jar> <file.pawprint>
import { readFileSync } from 'node:fs';
import { buildPackFromJar } from '../src/core/pack/vanillaJar';
import { readPawpack } from '../src/core/pack/pawpack';
import { readPawprint } from '../src/core/format/pawprint';
import { ModelBaker } from '../src/core/model/bake';
import { toMeshPalette, texturesOf } from '../src/core/mesh/prepare';
import { buildSection, VoxelMap } from '../src/core/mesh/mesher';

const [jarPath, bpPath] = process.argv.slice(2);
const pack = readPawpack((await buildPackFromJar(new Uint8Array(readFileSync(jarPath!)), { id: 'b', now: '' })).bytes);
const blocks = new Map(pack.blocks.map((b) => [b.id, b]));
const bp = readPawprint(new Uint8Array(readFileSync(bpPath!)));

let t = performance.now();
const baker = new ModelBaker({ file: (p) => pack.files.get(p), block: (id) => blocks.get(id) });
const baked = bp.palette.map((s) => baker.bake(s));
const missing = baked.filter((b) => b.missing).map((b) => b.blockId);
const palette = toMeshPalette(baked, () => [0, 0, 1, 1]);
console.log(`bake ${bp.palette.length} states: ${(performance.now() - t).toFixed(0)}ms, textures ${texturesOf(baked).size}, missing ${new Set(missing).size} (${[...new Set(missing)].slice(0, 5).join(' ')})`);

t = performance.now();
const map = VoxelMap.fromArrays(bp.positions, bp.states);
console.log(`voxel map ${bp.states.length} blocks, ${map.sections.size} sections: ${(performance.now() - t).toFixed(0)}ms`);

t = performance.now();
let quads = 0;
let bytes = 0;
for (const key of map.sections.keys()) {
  const mesh = buildSection(map, key, palette)!;
  quads += mesh.quadCount;
  for (const l of mesh.layers) if (l) bytes += l.positions.byteLength + l.uvs.byteLength + l.colors.byteLength + l.indices.byteLength;
}
console.log(`mesh: ${(performance.now() - t).toFixed(0)}ms, ${quads} quads (${(quads / bp.states.length).toFixed(2)} per block), ${(bytes / 1e6).toFixed(1)} MB`);
