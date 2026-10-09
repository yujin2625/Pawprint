/** Types for the `.pawpack` block pack format. See docs/FORMAT_PAWPACK.md. */

export const PACK_FORMAT = 1;

export type PackSource = 'mod-export' | 'vanilla-jar' | 'instance-folder';
export type RenderLayer = 'solid' | 'cutout' | 'cutout_mipped' | 'translucent';
export type RenderShape = 'model' | 'entity' | 'invisible';

export interface PackInfo {
  format: number;
  id: string;
  name: string;
  created: string;
  generator?: string;
  source: PackSource;
  mcVersion: string;
  dataVersion: number;
  loader?: string;
  mods?: { id: string; name: string; version: string }[];
  resourcePacks?: string[];
  languages: string[];
  blockCount: number;
  propertiesComplete: boolean;
}

export interface Tint {
  kind: 'grass' | 'foliage' | 'water' | 'constant' | 'other';
  color: string;
  byState?: Record<string, string>;
}

export interface BlockDef {
  id: string;
  properties: Record<string, string[]>;
  default: Record<string, string>;
  item: string | null;
  renderLayer: RenderLayer;
  renderShape: RenderShape;
  tint: Tint | null;
  tabs: string[];
  order?: number;
  fluid?: string;
}

/** Block ID → display name, one map per language code (`en_us`, `ko_kr`). */
export type PackLanguages = Record<string, Record<string, string>>;

export interface PackIcons {
  cell: number;
  columns: number;
  icons: Record<string, number>;
}

export const DEFAULT_COLORS = { grass: '#91BD59', foliage: '#77AB2F', water: '#3F76E4' };

export const PACK_LIMITS = {
  maxEntries: 200_000,
  maxEntryBytes: 64 * 1024 * 1024,
  maxTotalBytes: 1024 * 1024 * 1024,
  maxBlocks: 100_000,
};
