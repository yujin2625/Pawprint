import { getPack, listPacks, putPack } from '../storage/db';
import { baseName, onOpenFiles, readFile, takeOpenFiles } from '../platform/platform';
import { toStoredPack } from './packs/addPack';
import { importFile } from './projects';
import { openProject } from './session.svelte';

/**
 * Desktop: files double-clicked in the file manager. A `.pawprint` becomes a project and opens in the editor; a
 * `.pawpack` is added to the packs (replacing the same pack, keeping its name and default mark).
 */
export async function openPaths(paths: string[]): Promise<void> {
  for (const path of paths) {
    const name = baseName(path);
    try {
      const bytes = await readFile(path);
      if (name.toLowerCase().endsWith('.pawpack')) {
        const pack = toStoredPack(bytes);
        const existing = await getPack(pack.id);
        pack.isDefault = existing ? existing.isDefault : (await listPacks()).length === 0;
        if (existing) pack.info.name = existing.info.name;
        await putPack(pack);
        location.hash = '#/packs';
      } else {
        openProject(await importFile(bytes, name.replace(/\.pawprint$/i, '')));
      }
    } catch (e) {
      console.error(e);
      const { message } = await import('@tauri-apps/plugin-dialog');
      await message(`${name}\n\n${e instanceof Error ? e.message : String(e)}`, { title: 'Pawprint', kind: 'error' });
    }
  }
}

/** Opens the files the app was started with, then any double-clicked while it runs. */
export async function watchOpenFiles(): Promise<() => void> {
  await openPaths(await takeOpenFiles());
  return onOpenFiles((paths) => void openPaths(paths));
}
