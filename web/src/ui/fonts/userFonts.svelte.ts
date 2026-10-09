import { getSetting, setSetting } from '../../storage/db';
import { locale } from '../../i18n/i18n.svelte';

/**
 * Fonts the user adds (WEB_DESIGN.md §11.3): files kept in IndexedDB, or web font links. Each UI language has its
 * own order; characters a font lacks fall through to the next, then to Silver, then to system fonts.
 */

export const SILVER = 'silver';
const SYSTEM = "'Malgun Gothic', 'Apple SD Gothic Neo', sans-serif";
const FONTS = 'fonts.user';
const ORDER = 'fonts.order';

export interface UserFont {
  id: string;
  /** The CSS family name it is registered under. */
  family: string;
  kind: 'file' | 'url';
  file?: Blob;
  /** A font file, or a stylesheet (Google Fonts and the like) that defines `family`. */
  url?: string;
}

export const fonts = $state({
  list: [] as UserFont[],
  /** UI language code → font ids, first preferred; `silver` marks where Silver goes. */
  order: {} as Record<string, string[]>,
  loaded: false,
});

const registered = new Set<string>();

function quote(family: string): string {
  return `'${family.replace(/['\\]/g, '')}'`;
}

function isStylesheet(url: string): boolean {
  return /\.css(\?|$)/i.test(url) || /fonts\.googleapis\.com\/css/i.test(url);
}

async function register(font: UserFont): Promise<void> {
  if (registered.has(font.id)) return;
  registered.add(font.id);
  try {
    if (font.kind === 'url' && font.url && isStylesheet(font.url)) {
      const link = document.createElement('link');
      link.rel = 'stylesheet';
      link.href = font.url;
      link.dataset.userFont = font.id;
      document.head.appendChild(link);
      return;
    }
    const source = font.kind === 'file' && font.file ? await font.file.arrayBuffer() : `url(${JSON.stringify(font.url ?? '')})`;
    const face = new FontFace(font.family, source);
    document.fonts.add(await face.load());
  } catch (e) {
    console.warn('Font could not be loaded', font.family, e);
  }
}

/** The font order for a language: the saved one, or Silver alone. */
export function orderFor(code: string): string[] {
  const saved = fonts.order[code]?.filter((id) => id === SILVER || fonts.list.some((f) => f.id === id));
  return saved?.length ? saved : [SILVER];
}

/** Sets `--font` for the current UI language. */
export function applyFonts(): void {
  const families = orderFor(locale.code).map((id) => (id === SILVER ? "'Silver Pawprint'" : quote(fonts.list.find((f) => f.id === id)!.family)));
  if (!families.includes("'Silver Pawprint'")) families.push("'Silver Pawprint'");
  document.documentElement.style.setProperty('--font', `${families.join(', ')}, ${SYSTEM}`);
}

export async function loadFonts(): Promise<void> {
  fonts.list = (await getSetting<UserFont[]>(FONTS).catch(() => undefined)) ?? [];
  fonts.order = (await getSetting<Record<string, string[]>>(ORDER).catch(() => undefined)) ?? {};
  fonts.loaded = true;
  await Promise.all(fonts.list.map(register));
  applyFonts();
}

async function save(): Promise<void> {
  // Plain copies: IndexedDB cannot store the reactive proxies.
  await setSetting(FONTS, fonts.list.map((f) => ({ ...f })));
  await setSetting(ORDER, JSON.parse(JSON.stringify(fonts.order)) as Record<string, string[]>);
}

/** Adds a font and puts it first for the current language. */
export async function addFont(font: Omit<UserFont, 'id'>): Promise<void> {
  const added: UserFont = { ...font, id: crypto.randomUUID() };
  fonts.list = [...fonts.list, added];
  fonts.order = { ...fonts.order, [locale.code]: [added.id, ...orderFor(locale.code)] };
  await register(added);
  await save();
  applyFonts();
}

export async function removeFont(id: string): Promise<void> {
  fonts.list = fonts.list.filter((f) => f.id !== id);
  fonts.order = Object.fromEntries(Object.entries(fonts.order).map(([code, ids]) => [code, ids.filter((i) => i !== id)]));
  document.querySelector(`link[data-user-font="${id}"]`)?.remove();
  await save();
  applyFonts();
}

/** Moves a font up or down in the current language's order; fonts not in it yet are added. */
export async function moveFont(id: string, delta: number): Promise<void> {
  const order = orderFor(locale.code);
  const all = [...order, ...fonts.list.map((f) => f.id).filter((f) => !order.includes(f))];
  const i = all.indexOf(id);
  const j = Math.max(0, Math.min(all.length - 1, i + delta));
  [all[i], all[j]] = [all[j]!, all[i]!];
  fonts.order = { ...fonts.order, [locale.code]: all };
  await save();
  applyFonts();
}

/** Family name from a file name: "My Font-Regular.ttf" → "My Font". */
export function familyFromFile(name: string): string {
  return name.replace(/\.(ttf|otf|woff2?)$/i, '').replace(/[-_ ](regular|bold|italic|light|medium)$/i, '').trim() || 'User font';
}
