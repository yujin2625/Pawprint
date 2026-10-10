import type { EditableBlueprint } from '../core/blueprint/editable';
import { writePawprint } from '../core/format/pawprint';
import type { LoadedPack } from '../core/pack/pawpack';
import { subsetPack } from '../core/pack/subset';
import { locale } from '../i18n/i18n.svelte';
import { safeFileName, saveBytes } from '../platform/platform';
import { toBase64, viewerHtml } from '../viewer/html';

/**
 * Saves the blueprint as one .html file anyone can open in a browser to look at it in 3D: the viewer runtime, the
 * blueprint and the part of the block pack it uses, all inside.
 */
export async function exportViewer(bp: EditableBlueprint, pack: LoadedPack | null): Promise<boolean> {
  const runtime = (await import('../../viewer-dist/viewer.js?raw')).default;
  const flat = bp.toBlueprint();
  const states = [...new Set(Array.from(flat.states, (i) => flat.palette[i]!))];
  const html = viewerHtml(bp.meta.name || 'Blueprint', {
    blueprint: toBase64(writePawprint(flat)),
    pack: pack ? toBase64(subsetPack(pack, states)) : null,
    lang: locale.code === 'ko' ? 'ko' : 'en',
  }, runtime);
  const name = safeFileName(bp.meta.name, 'blueprint') + '.html';
  return saveBytes(name, new TextEncoder().encode(html), { name: 'HTML', extensions: ['html'] }, 'text/html');
}
