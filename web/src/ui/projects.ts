import { EditableBlueprint, newMeta } from '../core/blueprint/editable';
import { readPawprint, writePawprint, type Blueprint } from '../core/format/pawprint';
import { EXTERNAL_FORMATS, formatOfFile, readExternal, writeExternal, type ExternalFormat } from '../core/format/convert';
import { fromShareString, toShareString } from '../core/format/share';
import { aiPrompt, listModdedBlocks, readTextBlueprint, type BlockListing } from '../core/format/textBlueprint';
import { loadDefaultPack, loadPack } from './packs/activePack';
import type { LoadedPack } from '../core/pack/pawpack';
import { getProject, getSetting, listProjects, putProject, setSetting, type StoredProject } from '../storage/db';
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

/** The block pack the AI instructions were last copied for; its blocks check what the AI answers. */
const AI_PACK = 'aiPack';

/**
 * The instructions to paste into an AI chat (the same text the mod copies), for the game version and mods of a
 * block pack (the default one when `packId` is not given). With `listBlocks` the pack's modded block IDs are
 * listed (blocks with an item first when they do not all fit), so the AI uses real IDs. `blocks` counts them whether listed or not; `packId`
 * is null when there is no pack: the AI is then told to use vanilla blocks.
 */
export async function aiInstructions(packId?: string, listBlocks = true): Promise<{ text: string; packId: string | null; blocks: BlockListing | null }> {
  const pack = (packId ? await loadPack(packId) : null) ?? (await loadDefaultPack());
  if (!pack) return { text: aiPrompt('', []), packId: null, blocks: null };
  const solid = pack.blocks.filter((b) => !b.fluid);
  const namespaces = solid.map((b) => (b.id.includes(':') ? b.id.slice(0, b.id.indexOf(':')) : 'minecraft'));
  const blocks = listModdedBlocks(solid.map((b) => ({ id: b.id, item: !!b.item })));
  return { text: aiPrompt(pack.info.mcVersion, namespaces, listBlocks ? blocks : undefined), packId: pack.info.id, blocks };
}

/** Called once the instructions are copied: the AI's answer will be checked against this pack. */
export async function rememberAiPack(packId: string | null): Promise<void> {
  await setSetting(AI_PACK, packId ?? undefined).catch(() => undefined);
}

/** The pack to check an AI's answer against: the one its instructions were copied for, else the default one. */
async function aiPack(): Promise<LoadedPack | null> {
  const id = await getSetting<string>(AI_PACK).catch(() => undefined);
  return ((id ? await loadPack(id).catch(() => null) : null) ?? (await loadDefaultPack().catch(() => null)));
}

/**
 * Makes a project from the JSON an AI wrote (docs/AI_BLUEPRINT_FORMAT.md). Blocks are checked against the block
 * pack the instructions were copied for (or the default one). Throws TextFormatError with a message for the AI.
 */
export async function importText(text: string): Promise<{ id: string; warnings: string[] }> {
  const pack = await aiPack();
  const known = pack ? { blocks: new Map(pack.blocks.map((b) => [b.id, b])), complete: pack.info.propertiesComplete } : null;
  const result = readTextBlueprint(text, newMeta(''), known);
  if (pack) result.blueprint.meta = { ...result.blueprint.meta, mcVersion: pack.info.mcVersion, dataVersion: pack.info.dataVersion };
  return { id: await createProject(EditableBlueprint.fromBlueprint(result.blueprint)), warnings: result.warnings };
}

/** Extensions the app opens as blueprints: its own and other mods' formats. */
export const BLUEPRINT_EXTENSIONS = ['pawprint', ...EXTERNAL_FORMATS.map((f) => f.extension.slice(1))];

const withoutExtension = (fileName: string): string => fileName.replace(/\.(pawprint|litematic|schem|nbt)$/i, '');

/** A blueprint file of any format we read (`.pawprint`, `.litematic`, `.schem`, `.nbt`), by its name. */
function readBlueprintFile(bytes: Uint8Array, fileName: string): EditableBlueprint {
  const format = formatOfFile(fileName);
  const name = withoutExtension(fileName);
  const bp = EditableBlueprint.fromBlueprint(format ? readExternal(format, bytes, newMeta(name)) : readPawprint(bytes));
  if (!bp.meta.name) bp.meta.name = name;
  return bp;
}

/** Makes a project from a blueprint file. `fileName` tells the format and names blueprints that have no name. */
export async function importFile(bytes: Uint8Array, fileName: string): Promise<string> {
  return createProject(readBlueprintFile(bytes, fileName));
}

/** Makes a project from a share string (`PAW1:…`) found anywhere in the text. */
export async function importShare(text: string): Promise<string> {
  return createProject(EditableBlueprint.fromBlueprint(fromShareString(text, newMeta(t('projects.shared')))));
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
      const bp = readBlueprintFile(bytes, baseName(path));
      bp.meta.name ||= existing.name;
      await saveProject(existing.id, bp, null);
      await linkFile(existing.id, path, mtime);
    }
    return existing.id;
  }
  const id = await importFile(bytes, baseName(path));
  // Saving writes a .pawprint, so only a .pawprint stays linked to its file.
  if (!formatOfFile(path)) await linkFile(id, path, mtime);
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

/** The blueprint as other tools get it; a blueprint made here has no game version of its own, so the pack's is used. */
function flatFor(bp: EditableBlueprint, packDataVersion: number): Blueprint {
  const flat = bp.toBlueprint();
  if (!flat.meta.dataVersion) flat.meta = { ...flat.meta, dataVersion: packDataVersion };
  return flat;
}

/** Saves the blueprint in another mod's format. Layers do not exist there and are left out. */
export function exportAs(bp: EditableBlueprint, format: ExternalFormat, packDataVersion = 0): Promise<boolean> {
  const info = EXTERNAL_FORMATS.find((f) => f.id === format)!;
  const bytes = writeExternal(format, flatFor(bp, packDataVersion));
  return saveBytes(safeFileName(bp.meta.name, 'blueprint') + info.extension, bytes, { name: info.name, extensions: [info.extension.slice(1)] });
}

/** The blueprint as one line of text for chat (`PAW1:…`); the mod and this editor read it back. */
export function shareString(bp: EditableBlueprint, packDataVersion = 0): string {
  return toShareString(flatFor(bp, packDataVersion));
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
