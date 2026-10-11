import { describe, expect, it } from 'vitest';
import { strToU8, zlibSync } from 'fflate';
import { blockLooks } from '../src/core/pack/blockLooks';
import { pngPixels } from '../src/core/pack/png';
import type { BlockDef } from '../src/core/pack/types';

/** A PNG of RGBA pixels (color type 6), made here so no Mojang texture is needed. */
function png(width: number, height: number, pixel: (x: number, y: number) => [number, number, number, number]): Uint8Array {
  const raw = new Uint8Array(height * (1 + width * 4));
  for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) raw.set(pixel(x, y), y * (1 + width * 4) + 1 + x * 4);
  const chunk = (type: string, data: Uint8Array) => {
    const out = new Uint8Array(12 + data.length);
    const view = new DataView(out.buffer);
    view.setUint32(0, data.length);
    out.set(strToU8(type), 4);
    out.set(data, 8);
    let c = ~0;
    for (const byte of out.subarray(4, 8 + data.length)) {
      c ^= byte;
      for (let k = 0; k < 8; k++) c = c & 1 ? (c >>> 1) ^ 0xedb88320 : c >>> 1;
    }
    view.setUint32(8 + data.length, ~c >>> 0);
    return out;
  };
  const header = new Uint8Array(13);
  new DataView(header.buffer).setUint32(0, width);
  new DataView(header.buffer).setUint32(4, height);
  header.set([8, 6, 0, 0, 0], 8);
  const parts = [new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', header), chunk('IDAT', zlibSync(raw)), chunk('IEND', new Uint8Array())];
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let at = 0;
  for (const p of parts) (out.set(p, at), (at += p.length));
  return out;
}

const json = (v: unknown) => strToU8(JSON.stringify(v));
const block = (id: string, extra: Partial<BlockDef> = {}): BlockDef => ({
  id, properties: {}, default: {}, item: id, renderLayer: 'solid', renderShape: 'model', tint: null, tabs: [], ...extra,
});

describe('block looks', () => {
  it('decodes PNG pixels', () => {
    const image = pngPixels(png(2, 1, (x) => (x ? [0, 0, 255, 128] : [255, 0, 0, 255])))!;
    expect(image.width).toBe(2);
    expect([...image.rgba]).toEqual([255, 0, 0, 255, 0, 0, 255, 128]);
  });

  it('averages the main texture through model parents, with tint and see-through parts', () => {
    const files = new Map<string, Uint8Array>([
      ['assets/minecraft/models/block/cube_all.json', json({ textures: { particle: '#all' } })],
      ['assets/test/blockstates/bricks.json', json({ variants: { '': { model: 'test:block/bricks' } } })],
      ['assets/test/models/block/bricks.json', json({ parent: 'minecraft:block/cube_all', textures: { all: 'test:block/bricks' } })],
      // Half red, half blue: purple.
      ['assets/test/textures/block/bricks.png', png(2, 2, (x) => (x ? [0, 0, 200, 255] : [200, 0, 0, 255]))],
      ['assets/test/blockstates/grass.json', json({ variants: { '': { model: 'test:block/grass' } } })],
      ['assets/test/models/block/grass.json', json({ textures: { side: 'test:block/dirt', top: 'test:block/gray' } })],
      ['assets/test/textures/block/dirt.png', png(1, 1, () => [120, 80, 40, 255])],
      ['assets/test/textures/block/gray.png', png(1, 1, () => [255, 255, 255, 255])],
      ['assets/test/blockstates/pane.json', json({ variants: { '': { model: 'test:block/pane' } } })],
      ['assets/test/models/block/pane.json', json({ textures: { texture: 'test:block/pane' } })],
      ['assets/test/textures/block/pane.png', png(2, 1, (x) => (x ? [0, 0, 0, 0] : [100, 200, 100, 255]))],
    ]);
    const looks = blockLooks([
      block('test:bricks'),
      block('test:grass', { tint: { kind: 'grass', color: '#80C040' } }),
      block('test:pane', { renderLayer: 'cutout' }),
      block('test:missing'),
    ], files);
    expect(looks.get('test:bricks')).toEqual({ color: '#640064', look: null });
    // Tinted: the top texture, multiplied by the tint.
    expect(looks.get('test:grass')).toEqual({ color: '#80C040', look: null });
    // Clear pixels do not darken the color; the gaps make it see-through.
    expect(looks.get('test:pane')).toEqual({ color: '#64C864', look: 'see-through' });
    expect(looks.has('test:missing')).toBe(false);
  });
});
