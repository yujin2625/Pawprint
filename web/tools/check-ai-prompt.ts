import { readFileSync } from 'node:fs';
import { readPawpack } from '../src/core/pack/pawpack';
import { aiPrompt, listModdedBlocks } from '../src/core/format/textBlueprint';

/** Prints how big the AI instructions get for a block pack: `npx vite-node tools/check-ai-prompt.ts <file.pawpack>`. */
const pack = readPawpack(new Uint8Array(readFileSync(process.argv[2]!)));
const ids = pack.blocks.filter((b) => !b.fluid).sort((a, b) => Number(!a.item) - Number(!b.item)).map((b) => b.id);
const blocks = listModdedBlocks(ids);
const text = aiPrompt(pack.info.mcVersion, ids.map((id) => id.split(':')[0]!), blocks);
console.log(`${pack.info.name} (${pack.info.mcVersion}): ${pack.blocks.length} blocks, ${blocks.total} modded, ${blocks.listed} listed in ${blocks.lines.length} namespaces`);
console.log(`${text.length} characters, about ${Math.round(text.length / 4)} tokens`);
console.log(blocks.lines.map((l) => (l.length > 140 ? l.slice(0, 140) + '…' : l)).slice(0, 12).join('\n'));
