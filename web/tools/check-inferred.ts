// Manual check of what a pack made from game files infers (render layers, categories, items), against client jars
// installed on this PC (nothing is copied or committed).
// Run: npx vite-node tools/check-inferred.ts <path to client jar>...
import { readFileSync } from 'node:fs';
import { buildPackFromJar } from '../src/core/pack/vanillaJar';
import { readPawpack } from '../src/core/pack/pawpack';

for (const path of process.argv.slice(2)) {
  const started = Date.now();
  let flat = 0, offered = 0;
  const pack = await buildPackFromJar(new Uint8Array(readFileSync(path)), {
    id: 'check',
    now: new Date().toISOString(),
    renderIcons: async (input) => {
      flat = input.flat.size;
      offered = input.blocks.length;
      return null;
    },
  });
  const loaded = readPawpack(pack.bytes);
  const short = (id: string) => id.replace('minecraft:', '');
  const count = (key: (b: (typeof loaded.blocks)[number]) => string[]): string => {
    const seen: Record<string, number> = {};
    for (const block of loaded.blocks) for (const k of key(block)) seen[k] = (seen[k] ?? 0) + 1;
    return Object.entries(seen).map(([k, n]) => `${k.replace('pawprint:', '')} ${n}`).join(', ');
  };
  console.log(path.split(/[\\/]/).pop(), loaded.info.mcVersion, 'blocks', loaded.blocks.length, 'ms', Date.now() - started);
  console.log('  layers:', count((b) => [b.renderLayer]));
  for (const layer of ['cutout', 'translucent'] as const) {
    console.log(`  ${layer}:`, loaded.blocks.filter((b) => b.renderLayer === layer).map((b) => short(b.id)).join(' '));
  }
  console.log('  categories:', count((b) => b.tabs));
  console.log('  uncategorized:', loaded.blocks.filter((b) => !b.tabs.length).length);
  console.log('  item from loot:', loaded.blocks.filter((b) => b.item && b.item !== b.id).map((b) => `${short(b.id)}→${short(b.item!)}`).join(' '));
  console.log('  no item:', loaded.blocks.filter((b) => !b.item).map((b) => short(b.id)).join(' '));
  console.log('  icons: flat', flat, 'of', offered);
}
