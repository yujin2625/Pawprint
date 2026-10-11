import { readFileSync } from 'node:fs';
import { readPawpack } from '../src/core/pack/pawpack';
import { aiPromptForBlocks, estimateTokens, moddedNamespaces } from '../src/core/format/textBlueprint';

/**
 * Prints how big the AI instructions get for a block pack, with every mod or only some:
 * `npx vite-node tools/check-ai-prompt.ts <file.pawpack> [namespace,namespace…]`.
 */
const pack = readPawpack(new Uint8Array(readFileSync(process.argv[2]!)));
const blocks = pack.blocks.filter((b) => !b.fluid).map((b) => ({ id: b.id, item: !!b.item }));
const mods = process.argv[3] ? new Set(process.argv[3].split(',')) : undefined;
const { text, listing } = aiPromptForBlocks(pack.info.mcVersion, blocks, mods);
console.log(`${pack.info.name} (${pack.info.mcVersion}): ${blocks.length} blocks, ${listing.listed} modded listed`);
console.log(`${text.length} characters, about ${estimateTokens(text)} tokens`);
console.log(moddedNamespaces(blocks).map((m) => `${m.namespace} ${m.blocks}`).join(', '));
