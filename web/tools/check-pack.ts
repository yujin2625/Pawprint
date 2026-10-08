// Reads a .pawpack (e.g. one exported by the mod) with the web code and bakes every block's default state.
// Run: npx vite-node tools/check-pack.ts <file.pawpack>
import { readFileSync } from 'node:fs';
import { readPawpack } from '../src/core/pack/pawpack';
import { ModelBaker } from '../src/core/model/bake';
import { formatState } from '../src/core/model/state';

for (const path of process.argv.slice(2)) {
  const pack = readPawpack(new Uint8Array(readFileSync(path)));
  const blocks = new Map(pack.blocks.map((b) => [b.id, b]));
  const baker = new ModelBaker({ file: (p) => pack.files.get(p), block: (id) => blocks.get(id) });
  let quads = 0, missing = 0, standIn = 0, missingTextures = 0;
  const missingIds: string[] = [];
  for (const block of pack.blocks) {
    const baked = baker.bake(formatState(block.id, block.default));
    quads += baked.quads.length;
    if (baked.missing) (missing++, missingIds.push(block.id));
    else if (block.renderShape === 'model' && baked.quads.length > 0 && baked.fullFaces.size === 0 && block.id.includes('chest')) standIn++;
    for (const q of baked.quads) if (!q.texture.startsWith('pawprint:') && !pack.files.has(q.texture)) missingTextures++;
  }
  const info = pack.info;
  console.log(path.split(/[\\/]/).pop(), `${info.mcVersion} ${info.loader} source=${info.source} complete=${info.propertiesComplete}`,
    `blocks ${pack.blocks.length}, languages ${info.languages.join(',')}, mods ${info.mods?.length ?? 0}`);
  console.log(`  baked default states: ${quads} quads, ${missing} without a model (${missingIds.slice(0, 6).join(' ')}), ${missingTextures} quads pointing at missing textures`);
  console.log(`  ko: ${pack.languages.ko_kr?.['minecraft:oak_stairs']}, tabs on stone: ${JSON.stringify(blocks.get('minecraft:stone')?.tabs)}, grass tint: ${JSON.stringify(blocks.get('minecraft:grass_block')?.tint)}`);
  console.log(`  redstone tint states: ${Object.keys(blocks.get('minecraft:redstone_wire')?.tint?.byState ?? {}).length}`);
}
