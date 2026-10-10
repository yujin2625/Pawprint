/// <reference lib="webworker" />
import { FileFormatError } from '../core/zip';
import { runPackJob, type PackJob, type PackParts, type PackStep } from '../ui/packs/packBuild';

/**
 * Builds a block pack off the page's thread. The page starts one job per worker; for a modpack folder the worker
 * asks the page for files (`read`), since only the page can call the desktop app.
 */
export type PackBuilderRequest =
  | { type: 'start'; job: PackJob }
  | { type: 'files'; id: number; zip: Uint8Array }
  | { type: 'filesFailed'; id: number; message: string };

export type PackBuilderResponse =
  | { type: 'read'; id: number; paths: string[] }
  | { type: 'step'; step: PackStep }
  | { type: 'done'; parts: PackParts }
  /** `key` and `params` when it is a message for the user (a FileFormatError). */
  | { type: 'failed'; message: string; key?: string; params?: Record<string, string | number> };

const scope = self as unknown as DedicatedWorkerGlobalScope;
const post = (message: PackBuilderResponse, transfer: Transferable[] = []): void => scope.postMessage(message, transfer);
const reads = new Map<number, { resolve: (zip: Uint8Array) => void; reject: (e: Error) => void }>();
let nextRead = 0;

scope.onmessage = (event: MessageEvent<PackBuilderRequest>) => {
  const message = event.data;
  if (message.type === 'files') {
    reads.get(message.id)?.resolve(message.zip);
    reads.delete(message.id);
  } else if (message.type === 'filesFailed') {
    reads.get(message.id)?.reject(new Error(message.message));
    reads.delete(message.id);
  } else {
    runPackJob(message.job, {
      read: (paths) => new Promise((resolve, reject) => {
        const id = nextRead++;
        reads.set(id, { resolve, reject });
        post({ type: 'read', id, paths });
      }),
      step: (step) => post({ type: 'step', step }),
    }).then(
      (parts) => post({ type: 'done', parts }, [parts.bytes.buffer as ArrayBuffer]),
      (e: unknown) => post(e instanceof FileFormatError
        ? { type: 'failed', message: e.message, key: e.key, params: e.params }
        : { type: 'failed', message: e instanceof Error ? e.message : String(e) }),
    );
  }
};
