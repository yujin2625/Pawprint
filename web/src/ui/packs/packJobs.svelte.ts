import { FileFormatError } from '../../core/zip';
import { getPack, listPacks, putPack, requestPersistence, type StoredPack } from '../../storage/db';

/**
 * Adding a block pack, kept outside the packs page: reading a modpack takes a while, and it goes on (and reports
 * back) while the user looks at other pages.
 */

/** Messages are kept as keys so they follow a language change. */
export interface Message {
  key: string;
  params: Record<string, string | number>;
}

export const packJob = $state({
  busy: null as Message | null,
  error: null as Message | null,
  notice: null as Message | null,
  /** A pack with the same ID is already there: the app asks whether to replace it, on whatever page is open. */
  replacing: null as { name: string; resolve: (yes: boolean) => void } | null,
  /** Bumped when the stored packs change. */
  version: 0,
});

export function describeError(e: unknown): Message {
  if (e instanceof FileFormatError) return { key: e.key, params: e.params };
  return { key: 'error.unknown', params: { message: e instanceof Error ? e.message : String(e) } };
}

export function setBusy(message: Message): void {
  if (packJob.busy) packJob.busy = message;
}

export function answerReplace(yes: boolean): void {
  packJob.replacing?.resolve(yes);
  packJob.replacing = null;
}

/** Makes a pack and stores it. One at a time: callers disable their buttons while `packJob.busy` is set. */
export async function addPack(name: string, make: () => Promise<StoredPack>, busyKey: string): Promise<void> {
  if (packJob.busy) return;
  packJob.error = packJob.notice = null;
  packJob.busy = { key: busyKey, params: { name } };
  // Let the busy message paint before the synchronous unzip blocks the page.
  await new Promise((r) => setTimeout(r, 30));
  try {
    const pack = await make();
    const existing = await getPack(pack.id);
    if (existing) {
      const replace = await new Promise<boolean>((resolve) => (packJob.replacing = { name: existing.info.name, resolve }));
      if (!replace) return;
      pack.isDefault = existing.isDefault;
      pack.info.name = existing.info.name;
    } else {
      pack.isDefault = (await listPacks()).length === 0;
    }
    await putPack(pack);
    await requestPersistence();
    packJob.notice = { key: 'packs.added', params: { name: pack.info.name, count: pack.info.blockCount } };
    packJob.version++;
  } catch (e) {
    console.error(e);
    packJob.error = describeError(e);
  } finally {
    packJob.busy = null;
  }
}
