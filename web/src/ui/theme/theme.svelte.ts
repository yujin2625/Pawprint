import { BUILT_IN_COLORS, BUILT_IN_THEMES, THEME_KEYS, type BuiltInTheme, type CustomTheme, type ThemeColors } from './themes';

/**
 * The theme in use. Kept in localStorage (not IndexedDB) so it can be applied before the first paint.
 * Panel windows of the desktop app have their own document; they are registered here and get the same colors.
 */

const CURRENT = 'pawprint.theme';
const CUSTOM = 'pawprint.themes';

function read<T>(key: string, fallback: T): T {
  try {
    const text = localStorage.getItem(key);
    return text ? (JSON.parse(text) as T) : fallback;
  } catch {
    return fallback;
  }
}

function write(key: string, value: unknown): void {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // Not remembered; still applies for this visit.
  }
}

export const themes = $state({
  current: read<string>(CURRENT, 'blueprint'),
  custom: read<CustomTheme[]>(CUSTOM, []),
  /** Goes up on every change, for views that paint theme colors themselves (3D and 2D). */
  version: 0,
});

const documents = new Set<Document>();

export function colorsOf(id: string): ThemeColors {
  if (BUILT_IN_THEMES.includes(id as BuiltInTheme)) return BUILT_IN_COLORS[id as BuiltInTheme];
  const custom = themes.custom.find((t) => t.id === id);
  return custom ? { ...BUILT_IN_COLORS[custom.base], ...custom.colors } : BUILT_IN_COLORS.blueprint;
}

function paint(doc: Document, colors: ThemeColors): void {
  const root = doc.documentElement;
  for (const key of THEME_KEYS) root.style.setProperty('--' + key, colors[key]);
  // Native controls (scroll bars, select lists) follow light or dark.
  root.style.colorScheme = themes.current === 'light' || (themes.current !== 'dark' && isLight(colors.panel) && isLight(colors.bg)) ? 'light' : 'dark';
}

/** Whether a color is light (dark text and the light logo go on it). */
export function isLight(css: string): boolean {
  const hex = /^#([0-9a-f]{6})$/i.exec(css.trim());
  if (!hex) return true;
  const n = parseInt(hex[1]!, 16);
  return ((n >> 16) & 255) * 0.299 + ((n >> 8) & 255) * 0.587 + (n & 255) * 0.114 > 140;
}

export function applyTheme(): void {
  const colors = colorsOf(themes.current);
  paint(document, colors);
  for (const doc of documents) paint(doc, colors);
  themes.version++;
}

/** A panel window's document; it gets the theme now and on every change. Returns the unregister function. */
export function themeDocument(doc: Document): () => void {
  documents.add(doc);
  paint(doc, colorsOf(themes.current));
  return () => documents.delete(doc);
}

export function useTheme(id: string): void {
  themes.current = id;
  write(CURRENT, id);
  applyTheme();
}

function saveCustom(): void {
  write(CUSTOM, themes.custom);
}

/** Copies a theme into a new editable one and switches to it. */
export function copyTheme(fromId: string, name: string): string {
  const from = themes.custom.find((t) => t.id === fromId);
  const base: BuiltInTheme = from ? from.base : BUILT_IN_THEMES.includes(fromId as BuiltInTheme) ? (fromId as BuiltInTheme) : 'blueprint';
  const theme: CustomTheme = { id: crypto.randomUUID(), name, base, colors: { ...colorsOf(fromId) } };
  themes.custom = [...themes.custom, theme];
  saveCustom();
  useTheme(theme.id);
  return theme.id;
}

export function addTheme(theme: Omit<CustomTheme, 'id'>): string {
  const id = crypto.randomUUID();
  themes.custom = [...themes.custom, { ...theme, id }];
  saveCustom();
  useTheme(id);
  return id;
}

export function setColor(id: string, key: keyof ThemeColors, value: string): void {
  const theme = themes.custom.find((t) => t.id === id);
  if (!theme) return;
  theme.colors[key] = value;
  saveCustom();
  if (themes.current === id) applyTheme();
}

export function renameTheme(id: string, name: string): void {
  const theme = themes.custom.find((t) => t.id === id);
  if (!theme || !name.trim()) return;
  theme.name = name.trim();
  saveCustom();
}

/** Back to the colors of the built-in theme it was copied from. */
export function resetTheme(id: string): void {
  const theme = themes.custom.find((t) => t.id === id);
  if (!theme) return;
  theme.colors = { ...BUILT_IN_COLORS[theme.base] };
  saveCustom();
  if (themes.current === id) applyTheme();
}

export function deleteTheme(id: string): void {
  const theme = themes.custom.find((t) => t.id === id);
  themes.custom = themes.custom.filter((t) => t.id !== id);
  saveCustom();
  if (themes.current === id) useTheme(theme?.base ?? 'blueprint');
}
