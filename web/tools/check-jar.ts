// Manual check of the jar pack generator against client jars installed on this PC (nothing is copied or committed).
// Run: npx vite-node tools/check-jar.ts <path to client jar>...
import { readFileSync } from 'node:fs';
import { buildPackFromJar } from '../src/core/pack/vanillaJar';
import { readPawpack } from '../src/core/pack/pawpack';
import { pickPreviews } from '../src/core/pack/previews';

for (const path of process.argv.slice(2)) {
  const started = Date.now();
  const pack = buildPackFromJar(new Uint8Array(readFileSync(path)), { id: 'check', now: new Date().toISOString() });
  const loaded = readPawpack(pack.bytes);
  const shapes: Record<string, number> = {};
  for (const block of loaded.blocks) shapes[block.renderShape] = (shapes[block.renderShape] ?? 0) + 1;
  const textures = [...loaded.files.keys()].filter((name) => name.endsWith('.png')).length;
  const stairs = loaded.blocks.find((block) => block.id === 'minecraft:oak_stairs');
  const short = (id: string) => id.replace('minecraft:', '');
  console.log(path.split(/[\\/]/).pop(), loaded.info.mcVersion, loaded.info.dataVersion, 'blocks', loaded.blocks.length,
    JSON.stringify(shapes), 'textures', textures, 'MB', (pack.bytes.length / 1e6).toFixed(1), 'ms', Date.now() - started);
  console.log('  oak_stairs', JSON.stringify(stairs?.properties), 'item', stairs?.item, 'name', loaded.languages.en_us?.['minecraft:oak_stairs']);
  console.log('  entity:', loaded.blocks.filter((b) => b.renderShape === 'entity').map((b) => short(b.id)).slice(0, 14).join(' '));
  console.log('  invisible:', loaded.blocks.filter((b) => b.renderShape === 'invisible').map((b) => short(b.id)).join(' '));
  console.log('  no item:', loaded.blocks.filter((b) => !b.item).length, '| previews', pickPreviews(loaded.blocks, loaded.files).map((p) => short(p.id)).join(' '));
}
