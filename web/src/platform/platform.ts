/**
 * Differences between the web page and the desktop app (Tauri). Everything here works in both; the desktop app
 * uses real file dialogs and can look inside the user's Minecraft folder.
 */

export const isDesktop = typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window;

export interface FileFilter {
  name: string;
  extensions: string[];
}

async function invoke<T>(cmd: string, args?: Record<string, unknown> | Uint8Array, options?: { headers: Record<string, string> }): Promise<T> {
  const { invoke } = await import('@tauri-apps/api/core');
  return invoke<T>(cmd, args, options);
}

/** Saves bytes as a file: a save dialog in the desktop app, a download on the web. False if the user cancelled. */
export async function saveBytes(fileName: string, bytes: Uint8Array, filter: FileFilter, type = 'application/octet-stream'): Promise<boolean> {
  if (isDesktop) {
    const { save } = await import('@tauri-apps/plugin-dialog');
    const path = await save({ defaultPath: fileName, filters: [filter] });
    if (!path) return false;
    await writeFile(path, bytes);
    return true;
  }
  const url = URL.createObjectURL(new Blob([bytes as BlobPart], { type }));
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
  return true;
}

/** A name that is safe as a file name on every system. */
export function safeFileName(name: string, fallback: string): string {
  return (name || fallback).replace(/[\\/:*?"<>|]+/g, '_');
}

export function baseName(path: string): string {
  return path.split(/[\\/]/).pop() ?? path;
}

// Desktop only below.

export async function readFile(path: string): Promise<Uint8Array> {
  return new Uint8Array(await invoke<ArrayBuffer>('read_file', { path }));
}

export async function writeFile(path: string, bytes: Uint8Array): Promise<void> {
  await invoke('write_file', bytes, { headers: { path: encodeURIComponent(path) } });
}

export async function fileModified(path: string): Promise<number | null> {
  return invoke<number | null>('file_modified', { path });
}

/** A save dialog alone (desktop): the chosen path, or null. */
export async function pickSavePath(fileName: string, filter: FileFilter): Promise<string | null> {
  const { save } = await import('@tauri-apps/plugin-dialog');
  return save({ defaultPath: fileName, filters: [filter] });
}

export async function askYesNo(message: string, yes: string, no: string): Promise<boolean> {
  const { ask } = await import('@tauri-apps/plugin-dialog');
  return ask(message, { title: 'Pawprint', kind: 'warning', okLabel: yes, cancelLabel: no });
}

export async function pickFolder(): Promise<string | null> {
  const { open } = await import('@tauri-apps/plugin-dialog');
  const picked = await open({ directory: true });
  return typeof picked === 'string' ? picked : null;
}

/** Files the app was started with (a double-clicked `.pawprint` / `.pawpack`), handed out once. */
export async function takeOpenFiles(): Promise<string[]> {
  return isDesktop ? invoke<string[]>('take_open_files') : [];
}

/** Files double-clicked while the app is already running. */
export async function onOpenFiles(handler: (paths: string[]) => void): Promise<() => void> {
  if (!isDesktop) return () => {};
  const { listen } = await import('@tauri-apps/api/event');
  return listen<string[]>('open-files', (event) => handler(event.payload));
}

export interface MinecraftInstall {
  version: string;
  jar: string;
  root: string;
  assetIndex: string | null;
  modified: number;
}

/** The launcher's game folder, if it exists. */
export async function minecraftRoot(): Promise<string | null> {
  return invoke<string | null>('minecraft_default_root');
}

export async function minecraftInstalls(root: string): Promise<MinecraftInstall[]> {
  return invoke<MinecraftInstall[]>('minecraft_installs', { root });
}

/** `block.*` names per language code, from the launcher's downloaded assets (all languages except `en_us`). */
export async function minecraftLanguages(install: MinecraftInstall): Promise<Record<string, Record<string, string>>> {
  if (!install.assetIndex) return {};
  return invoke('minecraft_languages', { root: install.root, assetIndex: install.assetIndex });
}

export interface InstanceInfo {
  mcVersion: string | null;
  loader: string | null;
  vanillaJar: string | null;
  dataVersion: number | null;
  /** Where the launcher keeps `assets/` for the vanilla jar, and its asset index (non-English names). */
  launcherRoot: string | null;
  assetIndex: string | null;
  resourcePacks: string[];
  modFiles: number;
}

export interface InstanceIndexStats {
  sources: number;
  files: number;
  mods: { id: string; name: string; version: string }[];
  names: string[];
}

/** What a modded game folder holds: game version, loader, the vanilla jar (if found), mods and resource packs. */
export async function instanceInfo(dir: string): Promise<InstanceInfo> {
  return invoke('instance_info', { dir });
}

export async function jarDataVersion(jar: string): Promise<number | null> {
  return invoke('jar_data_version', { jar });
}

export async function pickFile(filter: FileFilter): Promise<string | null> {
  const { open } = await import('@tauri-apps/plugin-dialog');
  const picked = await open({ filters: [filter] });
  return typeof picked === 'string' ? picked : null;
}

/** Indexes the instance's assets in the app; then `instanceRead` / `instanceLanguages` read from that index. */
export async function instanceIndex(dir: string, vanillaJar: string): Promise<InstanceIndexStats> {
  return invoke('instance_index', { dir, vanillaJar });
}

/** The indexed files as an uncompressed zip. */
export async function instanceRead(paths: string[]): Promise<Uint8Array> {
  return new Uint8Array(await invoke<ArrayBuffer>('instance_read', { paths }));
}

export async function instanceLanguages(): Promise<Record<string, Record<string, string>>> {
  return invoke('instance_languages');
}

/** Block tags merged across the indexed files: tag ID → block IDs and `#tag` references. */
export async function instanceBlockTags(): Promise<Record<string, string[]>> {
  return invoke('instance_block_tags');
}

export async function instanceDone(): Promise<void> {
  await invoke('instance_done');
}
