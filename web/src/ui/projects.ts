import { EditableBlueprint, newMeta } from '../core/blueprint/editable';
import { readPawprint, writePawprint } from '../core/format/pawprint';
import { getProject, putProject, type StoredProject } from '../storage/db';

/** Projects: blueprints kept in this browser, stored as whole `.pawprint` files. */

export async function createProject(bp: EditableBlueprint): Promise<string> {
  const id = crypto.randomUUID();
  await saveProject(id, bp, null);
  return id;
}

export function newBlueprint(name: string): EditableBlueprint {
  return new EditableBlueprint(newMeta(name));
}

export async function importFile(bytes: Uint8Array, fallbackName: string): Promise<string> {
  const bp = EditableBlueprint.fromBlueprint(readPawprint(bytes));
  if (!bp.meta.name) bp.meta.name = fallbackName;
  return createProject(bp);
}

export async function loadProject(id: string): Promise<{ project: StoredProject; blueprint: EditableBlueprint } | null> {
  const project = await getProject(id);
  if (!project) return null;
  const blueprint = EditableBlueprint.fromBlueprint(readPawprint(new Uint8Array(await project.file.arrayBuffer())));
  blueprint.meta.name = project.name || blueprint.meta.name;
  return { project, blueprint };
}

export async function saveProject(id: string, bp: EditableBlueprint, thumbnail: Blob | null): Promise<void> {
  const existing = await getProject(id);
  const now = new Date().toISOString();
  bp.meta.modified = now;
  const flat = bp.toBlueprint();
  const bytes = writePawprint(flat);
  const b = bp.bounds();
  await putProject({
    id,
    name: bp.meta.name,
    created: existing?.created ?? now,
    modified: now,
    blockCount: flat.states.length,
    size: b ? [b.max[0] - b.min[0] + 1, b.max[1] - b.min[1] + 1, b.max[2] - b.min[2] + 1] : [0, 0, 0],
    thumbnail: thumbnail ?? existing?.thumbnail ?? null,
    file: new Blob([bytes as BlobPart], { type: 'application/zip' }),
  });
}

/** Saves a `.pawprint` to the user's downloads. */
export function download(bp: EditableBlueprint): void {
  const bytes = writePawprint(bp.toBlueprint());
  const url = URL.createObjectURL(new Blob([bytes as BlobPart], { type: 'application/zip' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = (bp.meta.name || 'blueprint').replace(/[\\/:*?"<>|]+/g, '_') + '.pawprint';
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}
