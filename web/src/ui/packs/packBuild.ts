import { buildPackFromAssets, type AssetPackOptions, type AssetSource } from '../../core/pack/assetPack';
import { readPawpack } from '../../core/pack/pawpack';
import { pickPreviews } from '../../core/pack/previews';
import type { PackInfo } from '../../core/pack/types';
import { buildPackFromJar } from '../../core/pack/vanillaJar';
import { readZip } from '../../core/zip';
import { renderPackIcons } from '../../render/packIcons';

/**
 * The heavy part of adding a block pack: unzipping, reading thousands of models, drawing icons, zipping. It has no
 * page or app calls of its own, so it runs in a worker (packBuilder.worker.ts) and the page stays smooth; what it
 * needs from the app (files of a modpack folder) it asks its host for.
 */

export type PackJob =
  /** A `.pawpack` to check and index. */
  | { kind: 'file'; bytes: Uint8Array }
  /** A vanilla client jar. */
  | { kind: 'jar'; bytes: Uint8Array; id: string; now: string; languages?: Record<string, Record<string, string>> }
  /** Game files the host reads on request (the desktop app's index of a modpack folder). */
  | { kind: 'assets'; names: string[]; tags: Record<string, string[]>; options: Omit<AssetPackOptions, 'renderIcons'> };

export type PackStep = 'blocks' | 'icons' | 'pack';

export interface PackHost {
  /** For `assets` jobs: these paths as one uncompressed zip. */
  read(paths: string[]): Promise<Uint8Array>;
  step(step: PackStep): void;
}

/** A finished pack as plain data (it crosses from the worker to the page). */
export interface PackParts {
  bytes: Uint8Array;
  info: PackInfo;
  previews: { id: string; png: Uint8Array }[];
}

const READ_LIMITS = { maxEntries: 400_000, maxEntryBytes: 64 * 1024 * 1024, maxTotalBytes: 1024 * 1024 * 1024 };
/** Paths per read, so no single transfer gets huge. */
const BATCH = 3000;

export async function runPackJob(job: PackJob, host: PackHost): Promise<PackParts> {
  const renderIcons: AssetPackOptions['renderIcons'] = (input) => {
    host.step('icons');
    return renderPackIcons(input);
  };
  let bytes: Uint8Array;
  if (job.kind === 'file') {
    bytes = job.bytes;
  } else if (job.kind === 'jar') {
    host.step('blocks');
    bytes = (await buildPackFromJar(job.bytes, { id: job.id, now: job.now, generator: 'pawprint-web', languages: job.languages, renderIcons })).bytes;
  } else {
    host.step('blocks');
    const source: AssetSource = {
      names: job.names,
      read: async (paths) => {
        const out = new Map<string, Uint8Array>();
        for (let i = 0; i < paths.length; i += BATCH) {
          for (const [name, data] of readZip(await host.read(paths.slice(i, i + BATCH)), READ_LIMITS)) out.set(name, data);
        }
        return out;
      },
      blockTags: async () => job.tags,
    };
    bytes = (await buildPackFromAssets(source, { ...job.options, renderIcons })).bytes;
  }
  host.step('pack');
  const pack = readPawpack(bytes);
  return { bytes, info: pack.info, previews: pickPreviews(pack.blocks, pack.files) };
}
