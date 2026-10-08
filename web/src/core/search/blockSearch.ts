import type { BlockDef, PackLanguages } from '../pack/types';

/**
 * Block search over every language the pack has, the block ID, and Korean initial consonants (ㅊㄴㅁ → 참나무),
 * the same way the mod's palette search works.
 */

const CHOSUNG = 'ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ';

export function chosung(text: string): string {
  let out = '';
  for (const ch of text) {
    const code = ch.charCodeAt(0) - 0xac00;
    out += code >= 0 && code <= 11171 ? CHOSUNG[Math.floor(code / 588)] : ch;
  }
  return out;
}

function isChosungOnly(text: string): boolean {
  return text.length > 0 && [...text].every((ch) => CHOSUNG.includes(ch) || ch === ' ');
}

export interface SearchEntry {
  id: string;
  /** Lowercased searchable texts: ID, path, every language's name. */
  texts: string[];
  initials: string[];
}

export function buildIndex(blocks: BlockDef[], languages: PackLanguages): SearchEntry[] {
  return blocks.map((b) => {
    const names = Object.values(languages).map((table) => table[b.id]).filter((n): n is string => !!n);
    const texts = [b.id, b.id.split(':')[1] ?? b.id, ...names].map((t) => t.toLowerCase());
    return { id: b.id, texts, initials: names.map((n) => chosung(n).replace(/\s+/g, '')) };
  });
}

/** Matching block IDs, best matches first (name starts with the query, then contains it). */
export function search(index: SearchEntry[], query: string): string[] {
  const q = query.trim().toLowerCase();
  if (!q) return index.map((e) => e.id);
  const initialsQuery = isChosungOnly(q) ? q.replace(/\s+/g, '') : null;
  const scored: { id: string; score: number }[] = [];
  for (const entry of index) {
    let score = -1;
    for (const t of entry.texts) {
      const at = t.indexOf(q);
      if (at === 0) score = Math.max(score, 3);
      else if (at > 0) score = Math.max(score, t[at - 1] === ' ' || t[at - 1] === '_' || t[at - 1] === ':' ? 2 : 1);
    }
    if (initialsQuery && score < 0) {
      for (const i of entry.initials) if (i.includes(initialsQuery)) score = Math.max(score, i.startsWith(initialsQuery) ? 2 : 1);
    }
    if (score >= 0) scored.push({ id: entry.id, score });
  }
  return scored.sort((a, b) => b.score - a.score).map((s) => s.id);
}
