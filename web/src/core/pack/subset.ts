import { ModelBaker } from '../model/bake';
import { parseState } from '../model/state';
import { jsonBytes, writeZip } from '../zip';
import type { LoadedPack } from './pawpack';

/**
 * A small `.pawpack` with only what drawing these block states needs: their block entries and names, the blockstate
 * and model files the baker reads (parents included), the textures it uses, and the icon sheet if a block needs it.
 * For files that carry one blueprint, like the HTML viewer.
 */
export function subsetPack(pack: LoadedPack, states: string[]): Uint8Array {
  const used = new Set<string>();
  const blocks = new Map(pack.blocks.map((b) => [b.id, b]));
  const baker = new ModelBaker({
    file: (path) => {
      const data = pack.files.get(path);
      if (data) used.add(path);
      return data;
    },
    block: (id) => blocks.get(id),
    hasIcon: (id) => pack.icons?.icons[id] !== undefined,
  });
  let icons = false;
  for (const state of states) {
    for (const quad of baker.bake(state).quads) {
      if (quad.texture.startsWith('pawprint:icon/')) icons = true;
      else if (!quad.texture.startsWith('pawprint:')) {
        used.add(quad.texture);
        if (pack.files.has(quad.texture + '.mcmeta')) used.add(quad.texture + '.mcmeta');
      }
    }
  }

  const ids = new Set(states.map((s) => parseState(s).id));
  const keptBlocks = pack.blocks.filter((b) => ids.has(b.id));
  const out = new Map<string, Uint8Array>();
  for (const path of used) {
    const data = pack.files.get(path);
    if (data) out.set(path, data);
  }
  out.set('pack.json', jsonBytes({ ...pack.info, blockCount: keptBlocks.length, languages: undefined }));
  out.set('blocks.json', jsonBytes(keptBlocks));
  for (const [code, names] of Object.entries(pack.languages)) {
    const kept = Object.fromEntries(Object.entries(names).filter(([id]) => ids.has(id)));
    if (Object.keys(kept).length) out.set(`lang/${code}.json`, jsonBytes(kept));
  }
  if (!out.has('lang/en_us.json')) out.set('lang/en_us.json', jsonBytes({}));
  const colors = pack.files.get('colors.json');
  if (colors) out.set('colors.json', colors);
  if (icons && pack.icons) {
    out.set('icons.png', pack.files.get('icons.png')!);
    out.set('icons.json', jsonBytes(pack.icons));
  }
  return writeZip(out);
}
