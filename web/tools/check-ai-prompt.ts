import { readFileSync } from 'node:fs';
import { readPawpack } from '../src/core/pack/pawpack';
import { aiPrompt, listModdedBlocks } from '../src/core/format/textBlueprint';

/** Prints how big the AI instructions get for a block pack: `npx vite-node tools/check-ai-prompt.ts <file.pawpack>`. */
const pack = readPawpack(new Uint8Array(readFileSync(process.argv[2]!)));
const solid = pack.blocks.filter((b) => !b.fluid);
const blocks = listModdedBlocks(solid.map((b) => ({ id: b.id, item: !!b.item })));
const text = aiPrompt(pack.info.mcVersion, solid.map((b) => b.id.split(':')[0]!), blocks);
console.log(`${pack.info.name} (${pack.info.mcVersion}): ${pack.blocks.length} blocks, ${blocks.total} modded, ${blocks.listed} listed in ${blocks.lines.length} namespaces`);
console.log(`${text.length} characters, about ${Math.round(text.length / 4)} tokens`);
console.log(blocks.lines.map((l) => (l.length > 140 ? l.slice(0, 140) + '…' : l)).slice(0, 12).join('\n'));
