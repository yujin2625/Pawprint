import { FileFormatError, readJson, readZip } from '../zip';
import {
  PACK_FORMAT,
  PACK_LIMITS,
  type BlockDef,
  type PackIcons,
  type PackInfo,
  type PackLanguages,
  type RenderLayer,
  type RenderShape,
} from './types';

/** A pack with its catalog parsed; textures and models stay in `files` and are read on demand. */
export interface LoadedPack {
  info: PackInfo;
  blocks: BlockDef[];
  languages: PackLanguages;
  icons: PackIcons | null;
  files: Map<string, Uint8Array>;
}

const LAYERS: RenderLayer[] = ['solid', 'cutout', 'cutout_mipped', 'translucent'];
const SHAPES: RenderShape[] = ['model', 'entity', 'invisible'];
const LANG_ENTRY = /^lang\/([a-z0-9_]+)\.json$/;

/** Reads a `.pawpack` and checks the catalog. Throws {@link FileFormatError} with a message key on bad input. */
export function readPawpack(bytes: Uint8Array): LoadedPack {
  const files = readZip(bytes, PACK_LIMITS);
  const info = checkInfo(readJson<Record<string, unknown>>(files, 'pack.json', 'error.pack.missingEntry'));
  const rawBlocks = readJson<unknown>(files, 'blocks.json', 'error.pack.missingEntry');
  if (!Array.isArray(rawBlocks)) throw new FileFormatError('error.pack.badField', { field: 'blocks.json' });
  if (rawBlocks.length > PACK_LIMITS.maxBlocks) throw new FileFormatError('error.pack.tooManyBlocks', { max: PACK_LIMITS.maxBlocks });
  const blocks = rawBlocks.map(checkBlock);

  const languages: PackLanguages = {};
  for (const name of files.keys()) {
    const match = LANG_ENTRY.exec(name);
    if (!match) continue;
    const table = readJson<unknown>(files, name, 'error.pack.missingEntry');
    if (table && typeof table === 'object' && !Array.isArray(table)) {
      languages[match[1]!] = Object.fromEntries(
        Object.entries(table as Record<string, unknown>).filter((e): e is [string, string] => typeof e[1] === 'string'),
      );
    }
  }
  if (Object.keys(languages).length === 0) throw new FileFormatError('error.pack.noLanguage');
  // The catalog is the truth; pack.json only summarizes it.
  info.languages = Object.keys(languages).sort();
  info.blockCount = blocks.length;

  let icons: PackIcons | null = null;
  if (files.has('icons.json') && files.has('icons.png')) {
    const raw = readJson<Partial<PackIcons>>(files, 'icons.json', 'error.pack.missingEntry');
    if (typeof raw.cell === 'number' && typeof raw.columns === 'number' && raw.icons && typeof raw.icons === 'object') {
      icons = { cell: raw.cell, columns: raw.columns, icons: raw.icons };
    }
  }
  return { info, blocks, languages, icons, files };
}

function checkInfo(raw: Record<string, unknown>): PackInfo {
  if (typeof raw.format !== 'number') throw new FileFormatError('error.pack.badField', { field: 'format' });
  if (raw.format > PACK_FORMAT) throw new FileFormatError('error.pack.tooNew', { format: raw.format });
  for (const field of ['id', 'name', 'mcVersion'] as const) {
    if (typeof raw[field] !== 'string' || raw[field] === '') throw new FileFormatError('error.pack.badField', { field });
  }
  return {
    format: raw.format,
    id: raw.id as string,
    name: raw.name as string,
    created: typeof raw.created === 'string' ? raw.created : '',
    generator: typeof raw.generator === 'string' ? raw.generator : undefined,
    source: raw.source === 'vanilla-jar' || raw.source === 'instance-folder' ? raw.source : 'mod-export',
    mcVersion: raw.mcVersion as string,
    dataVersion: typeof raw.dataVersion === 'number' ? raw.dataVersion : 0,
    loader: typeof raw.loader === 'string' ? raw.loader : undefined,
    mods: Array.isArray(raw.mods) ? (raw.mods as PackInfo['mods']) : [],
    resourcePacks: Array.isArray(raw.resourcePacks) ? (raw.resourcePacks as string[]) : [],
    languages: [],
    blockCount: 0,
    propertiesComplete: raw.propertiesComplete === true,
  };
}

function checkBlock(raw: unknown, index: number): BlockDef {
  const b = raw as Record<string, unknown>;
  if (!b || typeof b.id !== 'string' || !b.id.includes(':')) {
    throw new FileFormatError('error.pack.badBlock', { index });
  }
  const properties: Record<string, string[]> = {};
  if (b.properties && typeof b.properties === 'object') {
    for (const [name, values] of Object.entries(b.properties as Record<string, unknown>)) {
      if (Array.isArray(values)) properties[name] = values.map(String);
    }
  }
  const defaults: Record<string, string> = {};
  for (const [name, values] of Object.entries(properties)) {
    const given = (b.default as Record<string, unknown> | undefined)?.[name];
    defaults[name] = typeof given === 'string' && values.includes(given) ? given : values[0] ?? '';
  }
  return {
    id: b.id,
    properties,
    default: defaults,
    item: typeof b.item === 'string' ? b.item : null,
    renderLayer: LAYERS.includes(b.renderLayer as RenderLayer) ? (b.renderLayer as RenderLayer) : 'solid',
    renderShape: SHAPES.includes(b.renderShape as RenderShape) ? (b.renderShape as RenderShape) : 'model',
    tint: b.tint && typeof b.tint === 'object' && typeof (b.tint as { color?: unknown }).color === 'string' ? (b.tint as BlockDef['tint']) : null,
    tabs: Array.isArray(b.tabs) ? b.tabs.map(String) : [],
    order: typeof b.order === 'number' ? b.order : undefined,
    fluid: typeof b.fluid === 'string' ? b.fluid : undefined,
  };
}

/** Display name: preferred language → English → block ID. */
export function blockName(languages: PackLanguages, id: string, lang: string): string {
  return languages[lang]?.[id] ?? languages.en_us?.[id] ?? id;
}
