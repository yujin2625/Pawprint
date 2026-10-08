import { DIRS, type BakedModel } from '../model/bake';
import { QUAD_FLOATS, type LayerIndex, type MeshPaletteEntry } from './mesher';

/** Atlas rectangle of a texture in 0..1 texture space: [u0, v0, u1, v1], v going down. */
export type AtlasRect = [number, number, number, number];

const LAYER_INDEX: Record<string, LayerIndex> = { solid: 0, cutout: 1, cutout_mipped: 1, translucent: 2 };

/** Every texture a set of baked models needs, so the atlas can be built before meshing. */
export function texturesOf(models: Iterable<BakedModel>): Set<string> {
  const out = new Set<string>();
  for (const model of models) for (const quad of model.quads) out.add(quad.texture);
  return out;
}

/** Converts baked models to the mesher's packed form, mapping texture pixels into the atlas. */
export function toMeshPalette(models: BakedModel[], rect: (texture: string) => AtlasRect): MeshPaletteEntry[] {
  const keys = new Map<string, number>();
  return models.map((model) => {
    const quads = new Float32Array(model.quads.length * QUAD_FLOATS);
    model.quads.forEach((q, i) => {
      const at = i * QUAD_FLOATS;
      quads.set(q.pos, at);
      const [u0, v0, u1, v1] = rect(q.texture);
      for (let k = 0; k < 4; k++) {
        quads[at + 12 + k * 2] = u0 + (q.uv[k * 2]! / 16) * (u1 - u0);
        quads[at + 12 + k * 2 + 1] = v0 + (q.uv[k * 2 + 1]! / 16) * (v1 - v0);
      }
      const tint = q.tint ?? [1, 1, 1];
      quads[at + 20] = q.shade * tint[0];
      quads[at + 21] = q.shade * tint[1];
      quads[at + 22] = q.shade * tint[2];
      quads[at + 23] = q.cull ? DIRS.indexOf(q.cull) : -1;
    });
    let fullFaces = 0;
    DIRS.forEach((d, i) => {
      if (model.fullFaces.has(d)) fullFaces |= 1 << i;
    });
    if (!keys.has(model.blockId)) keys.set(model.blockId, keys.size + 1);
    return {
      quads,
      fullFaces,
      layer: LAYER_INDEX[model.layer] ?? 0,
      blockKey: keys.get(model.blockId)!,
      joinSame: model.layer === 'translucent' && model.fullFaces.size === 0,
    };
  });
}
