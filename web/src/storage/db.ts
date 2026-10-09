import type { PackInfo } from '../core/pack/types';

/** Everything is stored in this browser only (IndexedDB). See WEB_IMPLEMENTATION.md §4.9. */

const DB_NAME = 'pawprint';
const DB_VERSION = 2;

export interface StoredPack {
  id: string;
  info: PackInfo;
  addedAt: string;
  sizeBytes: number;
  isDefault: boolean;
  previews: { id: string; png: Blob }[];
  /** The original `.pawpack` file. */
  file: Blob;
}

let opening: Promise<IDBDatabase> | null = null;

function open(): Promise<IDBDatabase> {
  opening ??= new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains('packs')) db.createObjectStore('packs', { keyPath: 'id' });
      if (!db.objectStoreNames.contains('settings')) db.createObjectStore('settings', { keyPath: 'key' });
      if (!db.objectStoreNames.contains('projects')) db.createObjectStore('projects', { keyPath: 'id' });
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => {
      opening = null;
      reject(request.error);
    };
  });
  return opening;
}

function done<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

async function store(name: 'packs' | 'settings' | 'projects', mode: IDBTransactionMode): Promise<IDBObjectStore> {
  return (await open()).transaction(name, mode).objectStore(name);
}

export async function listPacks(): Promise<StoredPack[]> {
  const packs = await done((await store('packs', 'readonly')).getAll() as IDBRequest<StoredPack[]>);
  return packs.sort((a, b) => Number(b.isDefault) - Number(a.isDefault) || a.addedAt.localeCompare(b.addedAt));
}

export async function getPack(id: string): Promise<StoredPack | undefined> {
  return done((await store('packs', 'readonly')).get(id) as IDBRequest<StoredPack | undefined>);
}

export async function putPack(pack: StoredPack): Promise<void> {
  await done((await store('packs', 'readwrite')).put(pack));
}

export async function deletePack(id: string): Promise<void> {
  await done((await store('packs', 'readwrite')).delete(id));
}

/** Makes one pack the default (used for new blueprints). */
export async function setDefaultPack(id: string): Promise<void> {
  const db = await open();
  const tx = db.transaction('packs', 'readwrite');
  const packs = tx.objectStore('packs');
  const all = await done(packs.getAll() as IDBRequest<StoredPack[]>);
  for (const pack of all) {
    if (pack.isDefault !== (pack.id === id)) packs.put({ ...pack, isDefault: pack.id === id });
  }
  await new Promise<void>((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
}

/** A blueprint being worked on in the web editor; `file` is the whole `.pawprint`. */
export interface StoredProject {
  id: string;
  name: string;
  created: string;
  modified: string;
  blockCount: number;
  size: [number, number, number];
  thumbnail: Blob | null;
  file: Blob;
  /** Desktop: the `.pawprint` on disk this project was opened from or saved to. */
  filePath?: string;
  /** Desktop: that file's modification time right after the app last read or wrote it. */
  fileSynced?: number;
}

/** Project list without the files (the list only needs names and thumbnails). */
export async function listProjects(): Promise<StoredProject[]> {
  const projects = await done((await store('projects', 'readonly')).getAll() as IDBRequest<StoredProject[]>);
  return projects.sort((a, b) => b.modified.localeCompare(a.modified));
}

export async function getProject(id: string): Promise<StoredProject | undefined> {
  return done((await store('projects', 'readonly')).get(id) as IDBRequest<StoredProject | undefined>);
}

export async function putProject(project: StoredProject): Promise<void> {
  await done((await store('projects', 'readwrite')).put(project));
}

export async function deleteProject(id: string): Promise<void> {
  await done((await store('projects', 'readwrite')).delete(id));
}

export async function getSetting<T>(key: string): Promise<T | undefined> {
  const row = await done((await store('settings', 'readonly')).get(key) as IDBRequest<{ key: string; value: T } | undefined>);
  return row?.value;
}

export async function setSetting<T>(key: string, value: T): Promise<void> {
  await done((await store('settings', 'readwrite')).put({ key, value }));
}

export interface StorageUse {
  used: number;
  quota: number;
}

export async function storageUse(): Promise<StorageUse | null> {
  if (!navigator.storage?.estimate) return null;
  const { usage = 0, quota = 0 } = await navigator.storage.estimate();
  return { used: usage, quota };
}

/** Asks the browser not to evict our data under storage pressure. Safe to call repeatedly. */
export async function requestPersistence(): Promise<void> {
  try {
    if (navigator.storage?.persist && !(await navigator.storage.persisted())) await navigator.storage.persist();
  } catch {
    // Not supported or refused; data still works, it just may be evicted.
  }
}
