import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

const dir = join(__dirname, '..', 'src', 'i18n');
const load = (file: string) => JSON.parse(readFileSync(join(dir, file), 'utf8')) as Record<string, string>;
const placeholders = (text: string) => [...text.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort();

describe('language files', () => {
  const en = load('en.json');
  const others = readdirSync(dir).filter((f) => f.endsWith('.json') && f !== 'en.json');

  for (const file of others) {
    const other = load(file);
    it(`${file} has every English key`, () => {
      expect(Object.keys(en).filter((key) => !(key in other))).toEqual([]);
    });
    it(`${file} has no keys English lacks`, () => {
      expect(Object.keys(other).filter((key) => !(key in en))).toEqual([]);
    });
    it(`${file} keeps the same placeholders`, () => {
      const wrong = Object.keys(en).filter((key) => key in other && placeholders(en[key]!).join() !== placeholders(other[key]!).join());
      expect(wrong).toEqual([]);
    });
  }

  it('every error key thrown by core has a message', () => {
    const src = join(__dirname, '..', 'src', 'core');
    const keys = new Set<string>();
    const walk = (d: string) => {
      for (const entry of readdirSync(d, { withFileTypes: true })) {
        const path = join(d, entry.name);
        if (entry.isDirectory()) walk(path);
        else for (const m of readFileSync(path, 'utf8').matchAll(/'(error\.[\w.]+)'/g)) keys.add(m[1]!);
      }
    };
    walk(src);
    expect([...keys].filter((key) => !(key in en))).toEqual([]);
  });
});
