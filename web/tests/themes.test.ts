import { describe, expect, it } from 'vitest';
import { BUILT_IN_COLORS, BUILT_IN_THEMES, THEME_KEYS, contrast, contrastProblems, parseRgb, themeFromFile, themeToFile, toHex } from '../src/ui/theme/themes';

describe('themes', () => {
  it('reads hex and rgb colors', () => {
    expect(parseRgb('#fff')).toEqual({ r: 255, g: 255, b: 255, a: 1 });
    expect(parseRgb('rgba(26, 86, 148, 0.5)')).toEqual({ r: 26, g: 86, b: 148, a: 0.5 });
    expect(parseRgb('rgb(1 2 3 / 50%)')).toEqual({ r: 1, g: 2, b: 3, a: 0.5 });
    expect(parseRgb('red')).toBeNull();
    expect(toHex('rgba(255, 0, 16, 0.2)')).toBe('#ff0010');
  });

  it('computes WCAG contrast', () => {
    expect(contrast('#000', '#fff')).toBeCloseTo(21, 1);
    expect(contrast('#777', '#777')).toBeCloseTo(1, 5);
  });

  it('gives every built-in theme every color, readable', () => {
    for (const id of BUILT_IN_THEMES) {
      const colors = BUILT_IN_COLORS[id];
      for (const key of THEME_KEYS) expect(parseRgb(colors[key]), `${id} ${key}`).not.toBeNull();
      expect(contrastProblems(colors), id).toEqual([]);
    }
  });

  it('flags text that is hard to read', () => {
    const colors = { ...BUILT_IN_COLORS.blueprint, text: '#eeddcc' };
    expect(contrastProblems(colors).map((p) => p.text)).toEqual(['text']);
  });

  it('round-trips theme files and fills gaps from the base', () => {
    const file = themeToFile({ name: 'Mine', base: 'dark', colors: { ...BUILT_IN_COLORS.dark, accent: '#00ff00' } });
    expect(themeFromFile(file)).toEqual({ name: 'Mine', base: 'dark', colors: { ...BUILT_IN_COLORS.dark, accent: '#00ff00' } });
    const partial = themeFromFile(JSON.stringify({ pawprintTheme: 1, name: 'P', base: 'nope', colors: { panel: '#123456', bad: '#000', text: 'url(x)' } }));
    expect(partial.base).toBe('blueprint');
    expect(partial.colors.panel).toBe('#123456');
    expect(partial.colors.text).toBe(BUILT_IN_COLORS.blueprint.text);
    expect(() => themeFromFile('{"colors":{}}')).toThrow();
  });
});
