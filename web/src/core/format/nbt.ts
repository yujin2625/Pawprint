import { gunzipSync, gzipSync } from 'fflate';
import { FileFormatError } from '../zip';

/**
 * Minimal big-endian NBT (Java edition) reader and writer. Arrays come back as typed arrays so large blueprints
 * stay fast: int arrays as Int32Array, long arrays as BigInt64Array.
 */

export const Tag = {
  End: 0,
  Byte: 1,
  Short: 2,
  Int: 3,
  Long: 4,
  Float: 5,
  Double: 6,
  ByteArray: 7,
  String: 8,
  List: 9,
  Compound: 10,
  IntArray: 11,
  LongArray: 12,
} as const;
export type Tag = (typeof Tag)[keyof typeof Tag];

export type NbtValue =
  | number
  | bigint
  | string
  | Int8Array
  | Int32Array
  | BigInt64Array
  | NbtValue[]
  | NbtCompound;

export interface NbtCompound {
  [key: string]: NbtValue;
}

/** Limits keep a crafted file from allocating huge arrays or nesting forever. */
const MAX_DEPTH = 64;
const MAX_ARRAY = 64 * 1024 * 1024;

class Reader {
  private pos = 0;
  private readonly view: DataView;
  private readonly text = new TextDecoder();

  constructor(private readonly bytes: Uint8Array) {
    this.view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  }

  private need(n: number): void {
    if (n < 0 || this.pos + n > this.bytes.length) throw new FileFormatError('error.nbt.truncated');
  }

  u8(): number {
    this.need(1);
    return this.bytes[this.pos++]!;
  }

  i16(): number {
    this.need(2);
    const v = this.view.getInt16(this.pos);
    this.pos += 2;
    return v;
  }

  i32(): number {
    this.need(4);
    const v = this.view.getInt32(this.pos);
    this.pos += 4;
    return v;
  }

  string(): string {
    const length = this.view.getUint16((this.need(2), this.pos));
    this.pos += 2;
    this.need(length);
    const s = this.text.decode(this.bytes.subarray(this.pos, this.pos + length));
    this.pos += length;
    return s;
  }

  count(): number {
    const n = this.i32();
    if (n < 0 || n > MAX_ARRAY) throw new FileFormatError('error.nbt.tooLarge');
    return n;
  }

  payload(type: number, depth: number): NbtValue {
    if (depth > MAX_DEPTH) throw new FileFormatError('error.nbt.tooDeep');
    switch (type) {
      case Tag.Byte:
        return (this.u8() << 24) >> 24;
      case Tag.Short:
        return this.i16();
      case Tag.Int:
        return this.i32();
      case Tag.Long: {
        this.need(8);
        const v = this.view.getBigInt64(this.pos);
        this.pos += 8;
        return v;
      }
      case Tag.Float: {
        this.need(4);
        const v = this.view.getFloat32(this.pos);
        this.pos += 4;
        return v;
      }
      case Tag.Double: {
        this.need(8);
        const v = this.view.getFloat64(this.pos);
        this.pos += 8;
        return v;
      }
      case Tag.ByteArray: {
        const n = this.count();
        this.need(n);
        const v = new Int8Array(this.bytes.slice(this.pos, this.pos + n).buffer);
        this.pos += n;
        return v;
      }
      case Tag.String:
        return this.string();
      case Tag.List: {
        const itemType = this.u8();
        const n = this.count();
        if (itemType === Tag.End && n > 0) throw new FileFormatError('error.nbt.invalid');
        const list: NbtValue[] = [];
        for (let i = 0; i < n; i++) list.push(this.payload(itemType, depth + 1));
        return list;
      }
      case Tag.Compound: {
        const out: NbtCompound = {};
        for (;;) {
          const t = this.u8();
          if (t === Tag.End) return out;
          out[this.string()] = this.payload(t, depth + 1);
        }
      }
      case Tag.IntArray: {
        const n = this.count();
        this.need(n * 4);
        const v = new Int32Array(n);
        for (let i = 0; i < n; i++, this.pos += 4) v[i] = this.view.getInt32(this.pos);
        return v;
      }
      case Tag.LongArray: {
        const n = this.count();
        this.need(n * 8);
        const v = new BigInt64Array(n);
        for (let i = 0; i < n; i++, this.pos += 8) v[i] = this.view.getBigInt64(this.pos);
        return v;
      }
      default:
        throw new FileFormatError('error.nbt.invalid');
    }
  }
}

/** Reads gzip-compressed NBT whose root is a compound (the usual Java file layout). */
export function readNbt(compressed: Uint8Array, maxBytes = 512 * 1024 * 1024): NbtCompound {
  let bytes: Uint8Array;
  try {
    bytes = gunzipSync(compressed);
  } catch {
    throw new FileFormatError('error.nbt.invalid');
  }
  if (bytes.length > maxBytes) throw new FileFormatError('error.nbt.tooLarge');
  const reader = new Reader(bytes);
  if (reader.u8() !== Tag.Compound) throw new FileFormatError('error.nbt.invalid');
  reader.string();
  return reader.payload(Tag.Compound, 0) as NbtCompound;
}

/**
 * Values to write. Plain JS numbers are ambiguous, so the writer takes explicit tags for everything except
 * strings, compounds and typed arrays.
 */
export type NbtWrite =
  | string
  | Int32Array
  | BigInt64Array
  | Int8Array
  | { tag: typeof Tag.Byte | typeof Tag.Short | typeof Tag.Int | typeof Tag.Float | typeof Tag.Double; value: number }
  | { tag: typeof Tag.Long; value: bigint }
  | { tag: typeof Tag.List; type: Tag; items: NbtWrite[] }
  | { [key: string]: NbtWrite };

export const nbt = {
  int: (value: number): NbtWrite => ({ tag: Tag.Int, value }),
  list: (type: Tag, items: NbtWrite[]): NbtWrite => ({ tag: Tag.List, type, items }),
};

class Writer {
  private buf = new Uint8Array(1 << 16);
  private view = new DataView(this.buf.buffer);
  private pos = 0;
  private readonly text = new TextEncoder();

  private grow(n: number): void {
    if (this.pos + n <= this.buf.length) return;
    let size = this.buf.length * 2;
    while (size < this.pos + n) size *= 2;
    const next = new Uint8Array(size);
    next.set(this.buf.subarray(0, this.pos));
    this.buf = next;
    this.view = new DataView(next.buffer);
  }

  u8(v: number): void {
    this.grow(1);
    this.buf[this.pos++] = v & 0xff;
  }

  i32(v: number): void {
    this.grow(4);
    this.view.setInt32(this.pos, v);
    this.pos += 4;
  }

  string(s: string): void {
    const bytes = this.text.encode(s);
    if (bytes.length > 0xffff) throw new Error('NBT string too long');
    this.grow(2 + bytes.length);
    this.view.setUint16(this.pos, bytes.length);
    this.pos += 2;
    this.buf.set(bytes, this.pos);
    this.pos += bytes.length;
  }

  static typeOf(v: NbtWrite): Tag {
    if (typeof v === 'string') return Tag.String;
    if (v instanceof Int32Array) return Tag.IntArray;
    if (v instanceof BigInt64Array) return Tag.LongArray;
    if (v instanceof Int8Array) return Tag.ByteArray;
    if ('tag' in v && typeof v.tag === 'number') return v.tag as Tag;
    return Tag.Compound;
  }

  payload(v: NbtWrite): void {
    if (typeof v === 'string') return this.string(v);
    if (v instanceof Int32Array) {
      this.i32(v.length);
      this.grow(v.length * 4);
      for (const x of v) (this.view.setInt32(this.pos, x), (this.pos += 4));
      return;
    }
    if (v instanceof BigInt64Array) {
      this.i32(v.length);
      this.grow(v.length * 8);
      for (const x of v) (this.view.setBigInt64(this.pos, x), (this.pos += 8));
      return;
    }
    if (v instanceof Int8Array) {
      this.i32(v.length);
      this.grow(v.length);
      this.buf.set(new Uint8Array(v.buffer, v.byteOffset, v.length), this.pos);
      this.pos += v.length;
      return;
    }
    if ('tag' in v && typeof v.tag === 'number') {
      const t = v as Exclude<NbtWrite, string | Int32Array | BigInt64Array | Int8Array | { [key: string]: NbtWrite }>;
      this.grow(8);
      switch (t.tag) {
        case Tag.Byte:
          return this.u8(t.value);
        case Tag.Short:
          this.view.setInt16(this.pos, t.value);
          this.pos += 2;
          return;
        case Tag.Int:
          return this.i32(t.value);
        case Tag.Long:
          this.view.setBigInt64(this.pos, t.value);
          this.pos += 8;
          return;
        case Tag.Float:
          this.view.setFloat32(this.pos, t.value);
          this.pos += 4;
          return;
        case Tag.Double:
          this.view.setFloat64(this.pos, t.value);
          this.pos += 8;
          return;
        case Tag.List:
          this.u8(t.items.length ? t.type : Tag.End);
          this.i32(t.items.length);
          for (const item of t.items) this.payload(item);
          return;
      }
    }
    for (const [key, value] of Object.entries(v as { [key: string]: NbtWrite })) {
      this.u8(Writer.typeOf(value));
      this.string(key);
      this.payload(value);
    }
    this.u8(Tag.End);
  }

  bytes(): Uint8Array {
    return this.buf.slice(0, this.pos);
  }
}

/** Writes a gzip-compressed NBT file with an unnamed compound root. */
export function writeNbt(root: { [key: string]: NbtWrite }): Uint8Array {
  const w = new Writer();
  w.u8(Tag.Compound);
  w.string('');
  w.payload(root);
  return gzipSync(w.bytes());
}
