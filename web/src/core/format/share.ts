import { FileFormatError } from '../zip';
import { nbt, readNbt, Tag, writeNbt } from './nbt';
import { fromNbt, isLayered, toNbt, type Blueprint, type PawprintMeta } from './pawprint';

/** Share strings: a whole blueprint as one line of text for chat. See docs/FORMAT_PAWPRINT.md §7. */

export const SHARE_PREFIX = 'PAW1:';
const MAX_SHARE_CHARS = 8 * 1024 * 1024;

export function toShareString(bp: Blueprint): string {
  const root = toNbt(bp);
  root.Name = bp.meta.name;
  root.Description = bp.meta.description;
  root.Tags = nbt.list(Tag.String, bp.meta.tags);
  if (isLayered(bp)) root.Layers = JSON.stringify({ layers: bp.layers, layerOrder: bp.layerOrder });
  return SHARE_PREFIX + toBase64Url(writeNbt(root));
}

/** Whether a text holds a share string somewhere (people paste whole chat lines). */
export function hasShareString(text: string): boolean {
  return text.includes(SHARE_PREFIX);
}

/**
 * Reads the share string found anywhere in `text`. The result is a new blueprint: `meta` gives its new id, author
 * and times; name, description, tags and layers come from the string.
 */
export function fromShareString(text: string, meta: PawprintMeta): Blueprint {
  const start = text.indexOf(SHARE_PREFIX);
  if (start < 0) throw new FileFormatError('error.share.notFound');
  const payload = text.slice(start + SHARE_PREFIX.length).split(/\s/)[0]!;
  if (payload.length > MAX_SHARE_CHARS) throw new FileFormatError('error.share.tooLong');
  let bytes: Uint8Array;
  try {
    bytes = fromBase64Url(payload);
  } catch {
    throw new FileFormatError('error.share.damaged');
  }
  let root;
  try {
    root = readNbt(bytes);
  } catch {
    throw new FileFormatError('error.share.damaged');
  }
  const next: PawprintMeta = {
    ...meta,
    format: 1,
    name: typeof root.Name === 'string' && root.Name.trim() ? root.Name : meta.name,
    description: typeof root.Description === 'string' ? root.Description : '',
    tags: Array.isArray(root.Tags) ? root.Tags.filter((t): t is string => typeof t === 'string') : [],
    dataVersion: typeof root.DataVersion === 'number' ? root.DataVersion : 0,
  };
  if (typeof root.Layers === 'string') {
    try {
      const layers = JSON.parse(root.Layers) as { layers?: unknown; layerOrder?: unknown };
      if (Array.isArray(layers?.layers)) {
        next.format = 2;
        next.layers = layers.layers;
        next.layerOrder = Array.isArray(layers.layerOrder) ? layers.layerOrder : undefined;
      }
    } catch {
      // A damaged layer list only loses the layers, not the blocks.
    }
  }
  return fromNbt(next, root, null);
}

function toBase64Url(bytes: Uint8Array): string {
  let binary = '';
  for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(binary).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
}

function fromBase64Url(text: string): Uint8Array {
  if (!/^[A-Za-z0-9_-]*$/.test(text)) throw new Error('not base64url');
  const binary = atob(text.replaceAll('-', '+').replaceAll('_', '/'));
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i);
  return out;
}
