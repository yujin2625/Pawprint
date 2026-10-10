import { FileFormatError } from '../../core/zip';
import type { StoredPack } from '../../storage/db';
import type { PackBuilderRequest, PackBuilderResponse } from '../../workers/packBuilder.worker';
import { runPackJob, type PackHost, type PackJob, type PackParts, type PackStep } from './packBuild';

/** Adding block packs: the work runs in a worker so the page stays usable while a big pack is made. */

export interface PackHooks {
  /** For jobs that read game files through the app. */
  read?: (paths: string[]) => Promise<Uint8Array>;
  step?: (step: PackStep) => void;
}

/** Runs a pack job in a worker and returns the record to keep in IndexedDB. */
export async function buildPack(job: PackJob, hooks: PackHooks = {}): Promise<StoredPack> {
  const host: PackHost = {
    read: hooks.read ?? (() => Promise.reject(new Error('this job has no files to read'))),
    step: hooks.step ?? (() => {}),
  };
  let worker: Worker;
  try {
    worker = new Worker(new URL('../../workers/packBuilder.worker.ts', import.meta.url), { type: 'module' });
  } catch {
    // No workers here (an unusual browser setup): do the work on the page.
    return toStoredPack(await runPackJob(job, host));
  }
  try {
    return toStoredPack(await inWorker(worker, job, host));
  } finally {
    worker.terminate();
  }
}

function inWorker(worker: Worker, job: PackJob, host: PackHost): Promise<PackParts> {
  return new Promise((resolve, reject) => {
    const send = (message: PackBuilderRequest, transfer: Transferable[] = []): void => worker.postMessage(message, transfer);
    worker.onerror = (event) => reject(new Error(event.message || 'The pack worker stopped'));
    worker.onmessage = (event: MessageEvent<PackBuilderResponse>) => {
      const message = event.data;
      if (message.type === 'step') host.step(message.step);
      else if (message.type === 'done') resolve(message.parts);
      else if (message.type === 'failed') reject(message.key ? new FileFormatError(message.key, message.params) : new Error(message.message));
      else {
        host.read(message.paths).then(
          (zip) => send({ type: 'files', id: message.id, zip }, [zip.buffer as ArrayBuffer]),
          (e: unknown) => send({ type: 'filesFailed', id: message.id, message: e instanceof Error ? e.message : String(e) }),
        );
      }
    };
    // The job's bytes move to the worker instead of being copied.
    send({ type: 'start', job }, 'bytes' in job ? [job.bytes.buffer as ArrayBuffer] : []);
  });
}

function toStoredPack(parts: PackParts): StoredPack {
  return {
    id: parts.info.id,
    info: parts.info,
    addedAt: new Date().toISOString(),
    sizeBytes: parts.bytes.length,
    isDefault: false,
    previews: parts.previews.map((p) => ({ id: p.id, png: new Blob([p.png as BlobPart], { type: 'image/png' }) })),
    file: new Blob([parts.bytes as BlobPart], { type: 'application/zip' }),
  };
}

/** A `.pawpack` (checked and indexed). */
export function packFromBytes(bytes: Uint8Array): Promise<StoredPack> {
  return buildPack({ kind: 'file', bytes });
}

export async function packFromFile(file: File): Promise<StoredPack> {
  return packFromBytes(new Uint8Array(await file.arrayBuffer()));
}

export async function packFromJar(file: File): Promise<StoredPack> {
  return packFromJarBytes(new Uint8Array(await file.arrayBuffer()));
}

/** `languages`: names in other languages (the desktop app reads them from the launcher's assets). */
export function packFromJarBytes(jar: Uint8Array, languages?: Record<string, Record<string, string>>): Promise<StoredPack> {
  return buildPack({ kind: 'jar', bytes: jar, id: crypto.randomUUID(), now: new Date().toISOString(), languages });
}
