/**
 * UI text. Each `<code>.json` in this folder is a language; adding a file adds it to the language menu.
 * English (`en.json`) is the reference and the fallback for missing keys. See WEB_IMPLEMENTATION.md §4.10.
 */

type Messages = Record<string, string>;

const files = import.meta.glob<Messages>('./*.json', { eager: true, import: 'default' });
const catalogs: Record<string, Messages> = {};
for (const [path, messages] of Object.entries(files)) {
  catalogs[path.slice(2, -'.json'.length)] = messages;
}

const FALLBACK = 'en';
const STORAGE_KEY = 'pawprint.language';

export interface Language {
  code: string;
  name: string;
  /** Minecraft language code used for block names (`ko_kr`). */
  minecraft: string;
}

export const languages: Language[] = Object.keys(catalogs)
  .map((code) => ({ code, name: catalogs[code]!['meta.languageName'] ?? code, minecraft: catalogs[code]!['meta.minecraftCode'] ?? 'en_us' }))
  .sort((a, b) => (a.code === FALLBACK ? -1 : b.code === FALLBACK ? 1 : a.name.localeCompare(b.name)));

function initial(): string {
  try {
    const saved = localStorage.getItem(STORAGE_KEY);
    if (saved && catalogs[saved]) return saved;
  } catch {
    // Storage blocked: use the default.
  }
  return FALLBACK;
}

export const locale = $state({ code: initial() });

export function setLanguage(code: string): void {
  if (!catalogs[code]) return;
  locale.code = code;
  document.documentElement.lang = code;
  try {
    localStorage.setItem(STORAGE_KEY, code);
  } catch {
    // Not remembered; still applies for this visit.
  }
}

/** Minecraft language code for block names in the current UI language. */
export function blockLanguage(): string {
  return catalogs[locale.code]?.['meta.minecraftCode'] ?? 'en_us';
}

/** Translates `key`, filling `{name}` placeholders. Missing keys fall back to English, then to the key itself. */
export function t(key: string, params: Record<string, string | number> = {}): string {
  const text = catalogs[locale.code]?.[key] ?? catalogs[FALLBACK]?.[key] ?? key;
  return text.replace(/\{(\w+)\}/g, (match, name: string) => (name in params ? format(params[name]!) : match));
}

function format(value: string | number): string {
  return typeof value === 'number' ? new Intl.NumberFormat(locale.code).format(value) : value;
}

export function formatDate(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  return new Intl.DateTimeFormat(locale.code, { dateStyle: 'medium' }).format(date);
}

export function formatBytes(bytes: number): string {
  const units = ['B', 'KB', 'MB', 'GB'];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return new Intl.NumberFormat(locale.code, { maximumFractionDigits: unit >= 2 ? 1 : 0 }).format(value) + ' ' + units[unit];
}
