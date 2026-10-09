/**
 * Color themes (WEB_DESIGN.md §11.1). A theme sets every color CSS variable; built-in ones can be copied and edited.
 * No browser APIs here, so it is tested in Node.
 */

/** Editable colors, grouped as the theme editor shows them. Each is the CSS variable `--<key>`. */
export const THEME_GROUPS = {
  page: ['bg', 'grid'],
  chrome: ['chrome', 'chrome-2', 'chrome-raised', 'chrome-border', 'chrome-text', 'chrome-muted', 'outline'],
  panel: ['panel', 'panel-2', 'panel-border', 'panel-input', 'text', 'text-muted'],
  accent: ['accent', 'accent-soft', 'on-accent'],
  viewport: ['viewport-bg', 'selection', 'preview', 'erase'],
  status: ['danger', 'warning', 'warning-bg', 'success'],
} as const;

export type ThemeKey = (typeof THEME_GROUPS)[keyof typeof THEME_GROUPS][number];
export const THEME_KEYS = Object.values(THEME_GROUPS).flat() as ThemeKey[];
export type ThemeColors = Record<ThemeKey, string>;

export const BUILT_IN_THEMES = ['blueprint', 'dark', 'light'] as const;
export type BuiltInTheme = (typeof BUILT_IN_THEMES)[number];

export interface CustomTheme {
  id: string;
  name: string;
  /** The built-in theme it was copied from: "reset" goes back to it. */
  base: BuiltInTheme;
  colors: ThemeColors;
}

const BLUEPRINT: ThemeColors = {
  bg: '#2a6fb5',
  grid: 'rgba(255, 255, 255, 0.22)',
  chrome: '#1a5694',
  'chrome-2': '#1f61a3',
  'chrome-raised': '#2e73ba',
  'chrome-border': '#4a8bcc',
  'chrome-text': '#faeeda',
  'chrome-muted': '#d2e3f4',
  outline: '#12477d',
  panel: '#faeeda',
  'panel-2': '#f3e3c7',
  'panel-border': '#e3cfa8',
  'panel-input': '#fff8ec',
  text: '#2c2c2a',
  'text-muted': '#6b5a3e',
  accent: '#ef9f27',
  'accent-soft': '#f6d9a8',
  'on-accent': '#412402',
  'viewport-bg': '#2a6fb5',
  selection: '#faeeda',
  preview: '#ef9f27',
  erase: '#e86a5c',
  danger: '#a33a2c',
  warning: '#7a3e00',
  'warning-bg': '#fce3b8',
  success: '#3d7a47',
};

const DARK: ThemeColors = {
  bg: '#1b1f27',
  grid: 'rgba(255, 255, 255, 0.1)',
  chrome: '#12151b',
  'chrome-2': '#181c23',
  'chrome-raised': '#2a303b',
  'chrome-border': '#3a4150',
  'chrome-text': '#e8e6e3',
  'chrome-muted': '#a9b0bc',
  outline: '#07090c',
  panel: '#232831',
  'panel-2': '#2b313b',
  'panel-border': '#3a4150',
  'panel-input': '#1b1f27',
  text: '#e8e6e3',
  'text-muted': '#a3a9b3',
  accent: '#ef9f27',
  'accent-soft': '#4a3a22',
  'on-accent': '#2a1700',
  'viewport-bg': '#20252e',
  selection: '#e8e6e3',
  preview: '#ef9f27',
  erase: '#e86a5c',
  danger: '#e0705f',
  warning: '#f0b860',
  'warning-bg': '#3d3020',
  success: '#6cc07a',
};

const LIGHT: ThemeColors = {
  bg: '#e6ecf3',
  grid: 'rgba(26, 86, 148, 0.18)',
  chrome: '#ffffff',
  'chrome-2': '#f2f5f9',
  'chrome-raised': '#e1e8f0',
  'chrome-border': '#c3cfdc',
  'chrome-text': '#1f2a37',
  'chrome-muted': '#566476',
  outline: '#a9b8c9',
  panel: '#ffffff',
  'panel-2': '#f4f6f9',
  'panel-border': '#d6dee8',
  'panel-input': '#ffffff',
  text: '#1f2a37',
  'text-muted': '#5b6778',
  accent: '#ef9f27',
  'accent-soft': '#fde7c4',
  'on-accent': '#412402',
  'viewport-bg': '#cfdceb',
  selection: '#1a5694',
  preview: '#d9800f',
  erase: '#c2412f',
  danger: '#b3261e',
  warning: '#8a4b00',
  'warning-bg': '#fff0d6',
  success: '#2f7a3d',
};

export const BUILT_IN_COLORS: Record<BuiltInTheme, ThemeColors> = { blueprint: BLUEPRINT, dark: DARK, light: LIGHT };

/** Parses `#rgb`, `#rrggbb`, `rgb()` and `rgba()`; null for anything else. */
export function parseRgb(css: string): { r: number; g: number; b: number; a: number } | null {
  const text = css.trim().toLowerCase();
  const hex = /^#([0-9a-f]{3}|[0-9a-f]{6})$/.exec(text);
  if (hex) {
    const h = hex[1]!.length === 3 ? [...hex[1]!].map((c) => c + c).join('') : hex[1]!;
    return { r: parseInt(h.slice(0, 2), 16), g: parseInt(h.slice(2, 4), 16), b: parseInt(h.slice(4, 6), 16), a: 1 };
  }
  const fn = /^rgba?\(\s*([\d.]+)[\s,]+([\d.]+)[\s,]+([\d.]+)(?:[\s,/]+([\d.]+)(%?))?\s*\)$/.exec(text);
  if (fn) {
    const a = fn[4] === undefined ? 1 : parseFloat(fn[4]) / (fn[5] ? 100 : 1);
    const [r, g, b] = [fn[1], fn[2], fn[3]].map((v) => Math.min(255, parseFloat(v!)));
    return { r: r!, g: g!, b: b!, a: Math.min(1, a) };
  }
  return null;
}

/** `#rrggbb` for a color picker (alpha dropped); null if the color cannot be read. */
export function toHex(css: string): string | null {
  const c = parseRgb(css);
  return c ? '#' + [c.r, c.g, c.b].map((v) => Math.round(v).toString(16).padStart(2, '0')).join('') : null;
}

function luminance(c: { r: number; g: number; b: number }): number {
  const lin = (v: number) => {
    const s = v / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * lin(c.r) + 0.7152 * lin(c.g) + 0.0722 * lin(c.b);
}

/** WCAG contrast ratio (1–21), alpha ignored; null if a color cannot be read. */
export function contrast(a: string, b: string): number | null {
  const ca = parseRgb(a), cb = parseRgb(b);
  if (!ca || !cb) return null;
  const [hi, lo] = [luminance(ca), luminance(cb)].sort((x, y) => y - x);
  return (hi! + 0.05) / (lo! + 0.05);
}

/** Text and background pairs the editor checks, with the ratio each needs. */
export const CONTRAST_PAIRS: { text: ThemeKey; bg: ThemeKey; min: number }[] = [
  { text: 'text', bg: 'panel', min: 4.5 },
  { text: 'text-muted', bg: 'panel', min: 3 },
  { text: 'chrome-text', bg: 'chrome', min: 4.5 },
  { text: 'chrome-muted', bg: 'chrome', min: 3 },
  { text: 'chrome-text', bg: 'bg', min: 3 },
  { text: 'on-accent', bg: 'accent', min: 4.5 },
  { text: 'warning', bg: 'warning-bg', min: 4.5 },
];

export function contrastProblems(colors: ThemeColors): { text: ThemeKey; bg: ThemeKey; ratio: number; min: number }[] {
  const out = [];
  for (const pair of CONTRAST_PAIRS) {
    const ratio = contrast(colors[pair.text], colors[pair.bg]);
    if (ratio !== null && ratio < pair.min) out.push({ ...pair, ratio });
  }
  return out;
}

/** A theme file for sharing: `{ "pawprintTheme": 1, "name", "base", "colors" }`. */
export function themeToFile(theme: Pick<CustomTheme, 'name' | 'base' | 'colors'>): string {
  return JSON.stringify({ pawprintTheme: 1, name: theme.name, base: theme.base, colors: theme.colors }, null, 2);
}

/** Reads a theme file. Unknown keys are dropped; missing or unreadable colors come from the base theme. */
export function themeFromFile(text: string): Pick<CustomTheme, 'name' | 'base' | 'colors'> {
  const data = JSON.parse(text) as { pawprintTheme?: unknown; name?: unknown; base?: unknown; colors?: unknown };
  if (data.pawprintTheme !== 1 || !data.colors || typeof data.colors !== 'object') throw new Error('not a Pawprint theme file');
  const base = BUILT_IN_THEMES.includes(data.base as BuiltInTheme) ? (data.base as BuiltInTheme) : 'blueprint';
  const given = data.colors as Record<string, unknown>;
  const colors = { ...BUILT_IN_COLORS[base] };
  for (const key of THEME_KEYS) {
    const value = given[key];
    if (typeof value === 'string' && parseRgb(value)) colors[key] = value.trim();
  }
  const name = typeof data.name === 'string' && data.name.trim() ? data.name.trim().slice(0, 60) : 'Theme';
  return { name, base, colors };
}
