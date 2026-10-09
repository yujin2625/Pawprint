import { EditableBlueprint, newMeta } from '../core/blueprint/editable';
import { readPawprint, writePawprint } from '../core/format/pawprint';
import { getProject, listProjects, putProject, type StoredProject } from '../storage/db';
import { copy, paste, type Clip } from '../core/edit/clip';
import { askYesNo, baseName, fileModified, pickSavePath, readFile, safeFileName, saveBytes, writeFile } from '../platform/platform';
import { t } from '../i18n/i18n.svelte';

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
    filePath: existing?.filePath,
    fileSynced: existing?.fileSynced,
  });
}

const PAWPRINT = { name: 'Pawprint', extensions: ['pawprint'] };
const samePath = (a: string, b: string) => a.replaceAll('\\', '/').toLowerCase() === b.replaceAll('\\', '/').toLowerCase();

/**
 * Desktop: opens a `.pawprint` from disk. A file opened before goes back to its project; if the file changed since
 * (saved by the mod, say), the user picks the file or the app's copy. Returns the project id.
 */
export async function openPath(path: string): Promise<string> {
  const [bytes, mtime] = await Promise.all([readFile(path), fileModified(path)]);
  const existing = (await listProjects()).find((p) => p.filePath && samePath(p.filePath, path));
  if (existing) {
    const changed = mtime !== null && existing.fileSynced !== undefined && mtime > existing.fileSynced + 1000;
    if (changed && (await askYesNo(t('projects.fileChanged', { name: baseName(path) }), t('projects.useFile'), t('projects.keepApp')))) {
      // An editor open on this project would save its copy when it closes: close it first.
      if (location.hash === '#/editor/' + existing.id) {
        location.hash = '#/projects';
        await new Promise((r) => setTimeout(r, 500));
      }
      const bp = EditableBlueprint.fromBlueprint(readPawprint(bytes));
      bp.meta.name ||= existing.name;
      await saveProject(existing.id, bp, null);
      await linkFile(existing.id, path, mtime);
    }
    return existing.id;
  }
  const id = await importFile(bytes, baseName(path).replace(/\.pawprint$/i, ''));
  await linkFile(id, path, mtime);
  return id;
}

async function linkFile(id: string, path: string, mtime: number | null): Promise<void> {
  const project = await getProject(id);
  if (project) await putProject({ ...project, filePath: path, fileSynced: mtime ?? Date.now() });
}

/**
 * Desktop: writes the blueprint to its linked file, or asks where (always when `saveAs`). Returns the path written,
 * or null if the user cancelled.
 */
export async function saveToFile(id: string, bp: EditableBlueprint, saveAs = false): Promise<string | null> {
  const project = await getProject(id);
  const path = !saveAs && project?.filePath ? project.filePath : await pickSavePath(safeFileName(bp.meta.name, 'blueprint') + '.pawprint', PAWPRINT);
  if (!path) return null;
  await writeFile(path, writePawprint(bp.toBlueprint()));
  await linkFile(id, path, await fileModified(path));
  return path;
}

/** Saves a `.pawprint` file: to the downloads on the web, wherever the user picks in the desktop app. */
export function exportFile(bp: EditableBlueprint): Promise<boolean> {
  const bytes = writePawprint(bp.toBlueprint());
  return saveBytes(safeFileName(bp.meta.name, 'blueprint') + '.pawprint', bytes, { name: 'Pawprint', extensions: ['pawprint'] }, 'application/zip');
}

/** The whole blueprint of a project, ready to stamp. */
export async function loadClip(id: string): Promise<Clip | null> {
  const loaded = await loadProject(id);
  const bounds = loaded?.blueprint.bounds();
  return loaded && bounds ? copy(loaded.blueprint, bounds) : null;
}

/** Saves a clip as its own project tagged "stamp", so it shows in the stamp list (and can be edited like any blueprint). */
export async function saveStamp(clip: Clip, name: string): Promise<string> {
  const bp = new EditableBlueprint({ ...newMeta(name), tags: ['stamp'] });
  bp.begin('stamp');
  paste(bp, clip, [0, 0, 0]);
  bp.commit();
  return createProject(bp);
}
