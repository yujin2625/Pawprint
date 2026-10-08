import { utf8 } from '../zip';
import { blockstatePath, blockstateModels, modelPath, texturePath } from './resources';
import type { BlockDef } from './types';

export interface PackPreview {
  id: string;
  png: Uint8Array;
}

/**
 * Picks up to `count` full-cube blocks spread across the pack (models with an `all` texture) and returns their
 * texture images, for the pack card.
 */
export function pickPreviews(blocks: BlockDef[], files: Map<string, Uint8Array>, count = 12): PackPreview[] {
  const candidates: PackPreview[] = [];
  for (const block of blocks) {
    if (block.renderShape !== 'model' || block.renderLayer !== 'solid' || block.tint) continue;
    const png = cubeTexture(block.id, files);
    if (png) candidates.push({ id: block.id, png });
  }
  if (candidates.length <= count) return candidates;
  const step = candidates.length / count;
  return Array.from({ length: count }, (_, i) => candidates[Math.floor(i * step)]!);
}

function cubeTexture(id: string, files: Map<string, Uint8Array>): Uint8Array | null {
  const state = files.get(blockstatePath(id));
  if (!state) return null;
  try {
    const ref = blockstateModels(JSON.parse(utf8(state)))[0];
    if (!ref) return null;
    const model = files.get(modelPath(ref));
    if (!model) return null;
    const all = (JSON.parse(utf8(model)) as { textures?: Record<string, unknown> }).textures?.all;
    return typeof all === 'string' && !all.startsWith('#') ? files.get(texturePath(all)) ?? null : null;
  } catch {
    return null;
  }
}
