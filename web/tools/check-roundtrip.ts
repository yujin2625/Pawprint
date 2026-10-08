// Compares web-made blueprints with the copies the mod edited and saved back (selftest-web/out), to check that
// layers survive a trip through the mod. Run: npx vite-node tools/check-roundtrip.ts <selftest-web folder>
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { readPawprint, type Blueprint } from '../src/core/format/pawprint';

const folder = process.argv[2]!;
const key = (bp: Blueprint, i: number) => `${bp.positions[i * 3]},${bp.positions[i * 3 + 1]},${bp.positions[i * 3 + 2]}`;
for (const name of readdirSync(join(folder, 'out')).filter((n) => n.endsWith('.pawprint'))) {
  const before = readPawprint(new Uint8Array(readFileSync(join(folder, name))));
  const after = readPawprint(new Uint8Array(readFileSync(join(folder, 'out', name))));
  const layersBefore = new Map(Array.from({ length: before.states.length }, (_, i) => [key(before, i), before.blockLayers[i]]));
  let sameLayer = 0, moved = 0;
  for (let i = 0; i < after.states.length; i++) {
    if (layersBefore.get(key(after, i)) === after.blockLayers[i]) sameLayer++;
    else moved++;
  }
  const sameTree = JSON.stringify(before.layers) === JSON.stringify(after.layers) && JSON.stringify(before.layerOrder) === JSON.stringify(after.layerOrder);
  const replaced = after.palette.some((s) => s.startsWith('minecraft:spruce_planks')) && !after.palette.some((s) => s.startsWith('minecraft:oak_planks'));
  console.log(`${name}: format ${before.meta.format}→${after.meta.format}, blocks ${before.states.length}→${after.states.length}, ` +
    `same layer ${sameLayer}, changed layer ${moved}, layer tree ${sameTree ? 'same' : 'DIFFERENT'}, oak→spruce ${replaced ? 'done' : 'missing'}`);
  if (!sameTree) console.log('  before', JSON.stringify(before.layers), '\n  after ', JSON.stringify(after.layers));
}
