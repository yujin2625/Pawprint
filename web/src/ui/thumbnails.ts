import { Viewport } from '../render/viewport';
import { BlockResources } from '../render/resources';
import type { EditableBlueprint } from '../core/blueprint/editable';
import { loadDefaultPack } from './packs/activePack';
import { loadProject } from './projects';
import { getProject, putProject } from '../storage/db';

/**
 * Pictures for the project list. The editor takes one when it saves; projects that were only opened or imported
 * (never saved from the editor) get theirs drawn here, off screen, one at a time.
 */

const SIZE = 256;
/** A blueprint whose meshes take longer than this is pictured as far as it got. */
const MAX_WAIT_MS = 20_000;

/** Draws a blueprint as the editor's 3D view would, in a view nobody sees. */
export async function renderThumbnail(bp: EditableBlueprint, resources: BlockResources): Promise<Blob | null> {
  const host = document.createElement('div');
  Object.assign(host.style, { position: 'fixed', left: '-10000px', top: '0', width: `${SIZE}px`, height: `${SIZE}px`, pointerEvents: 'none' });
  document.body.appendChild(host);
  const view = new Viewport(host, resources);
  try {
    const meshed = new Promise<void>((resolve) => {
      view.onStats = (s) => {
        if (s.sectionsDone >= s.sectionsTotal) resolve();
      };
    });
    await view.show(bp);
    await Promise.race([meshed, new Promise((resolve) => setTimeout(resolve, MAX_WAIT_MS))]);
    return await view.snapshot(SIZE);
  } finally {
    view.dispose();
    host.remove();
  }
}

/** Stores a picture for a project without touching anything else (its modified time stays). */
export async function setThumbnail(id: string, thumbnail: Blob): Promise<void> {
  const project = await getProject(id);
  if (project) await putProject({ ...project, thumbnail });
}

/**
 * Draws the missing pictures of these projects with the default block pack, calling `done` after each one.
 * Stops between projects once `cancelled` returns true.
 */
export async function fillThumbnails(ids: string[], done: (id: string, thumbnail: Blob) => void, cancelled: () => boolean): Promise<void> {
  if (!ids.length) return;
  const pack = await loadDefaultPack().catch(() => null);
  const resources = new BlockResources(pack, 4096);
  try {
    for (const id of ids) {
      if (cancelled()) return;
      try {
        const loaded = await loadProject(id);
        if (!loaded || cancelled()) continue;
        const thumbnail = await renderThumbnail(loaded.blueprint, resources);
        if (!thumbnail || cancelled()) continue;
        await setThumbnail(id, thumbnail);
        done(id, thumbnail);
      } catch (e) {
        console.warn('Could not draw the picture of a project', id, e);
      }
    }
  } finally {
    resources.dispose();
  }
}
