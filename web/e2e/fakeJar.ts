import { strToU8, zipSync, zlibSync } from 'fflate';

/** A tiny client jar made of our own test data (no Mojang assets), with real PNGs so the page can draw them. */
export function fakeJar(): Uint8Array {
  const json = (v: unknown) => strToU8(JSON.stringify(v));
  const cube = (name: string) => ({
    [`assets/minecraft/blockstates/${name}.json`]: json({ variants: { '': { model: `minecraft:block/${name}` } } }),
    [`assets/minecraft/models/block/${name}.json`]: json({ parent: 'minecraft:block/cube_all', textures: { all: `minecraft:block/${name}` } }),
    [`assets/minecraft/models/item/${name}.json`]: json({ parent: `minecraft:block/${name}` }),
  });
  const face = { texture: '#all' };
  return zipSync({
    'version.json': json({ id: '1.21.1', world_version: 3955 }),
    'assets/minecraft/lang/en_us.json': json({ 'block.minecraft.test_stone': 'Test Stone', 'block.minecraft.test_planks': 'Test Planks' }),
    'assets/minecraft/models/block/cube_all.json': json({
      textures: { particle: '#all' },
      elements: [{ from: [0, 0, 0], to: [16, 16, 16], faces: { down: face, up: face, north: face, south: face, west: face, east: face } }],
    }),
    ...cube('test_stone'),
    ...cube('test_planks'),
    'assets/minecraft/textures/block/test_stone.png': solidPng(0x80, 0x80, 0x88),
    'assets/minecraft/textures/block/test_planks.png': solidPng(0xb0, 0x80, 0x48),
  });
}

/** A 16×16 PNG of one opaque color. */
function solidPng(r: number, g: number, b: number): Uint8Array {
  const size = 16;
  const raw = new Uint8Array(size * (1 + size * 4));
  for (let y = 0; y < size; y++) {
    const row = y * (1 + size * 4); // filter byte 0, then pixels
    for (let x = 0; x < size; x++) raw.set([r, g, b, 255], row + 1 + x * 4);
  }
  const header = new Uint8Array(13);
  const view = new DataView(header.buffer);
  view.setUint32(0, size);
  view.setUint32(4, size);
  header.set([8, 6, 0, 0, 0], 8); // 8 bits, RGBA
  return concat([new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', header), chunk('IDAT', zlibSync(raw)), chunk('IEND', new Uint8Array())]);
}

function chunk(type: string, data: Uint8Array): Uint8Array {
  const out = new Uint8Array(12 + data.length);
  const view = new DataView(out.buffer);
  view.setUint32(0, data.length);
  out.set(strToU8(type), 4);
  out.set(data, 8);
  view.setUint32(8 + data.length, crc32(out.subarray(4, 8 + data.length)));
  return out;
}

function crc32(bytes: Uint8Array): number {
  let c = ~0;
  for (const byte of bytes) {
    c ^= byte;
    for (let k = 0; k < 8; k++) c = c & 1 ? (c >>> 1) ^ 0xedb88320 : c >>> 1;
  }
  return ~c >>> 0;
}

function concat(parts: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let at = 0;
  for (const p of parts) {
    out.set(p, at);
    at += p.length;
  }
  return out;
}
