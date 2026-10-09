import * as THREE from 'three';

/** Viewport colors from the theme's CSS variables (WEB_DESIGN.md §11.1), read where the view is. */
export interface SceneColors {
  background: THREE.Color;
  /** Grid lines; `gridAlpha` is the alpha the theme gave them. */
  grid: THREE.Color;
  gridAlpha: number;
  accent: THREE.Color;
  preview: THREE.Color;
  erase: THREE.Color;
  selection: THREE.Color;
}

/** `#rgb`, `#rrggbb`, `rgb()` or `rgba()` → color and alpha (THREE.Color drops the alpha). */
export function parseColor(css: string, fallback: string): { color: THREE.Color; alpha: number } {
  const text = css.trim() || fallback;
  const rgba = /^rgba?\(\s*([\d.]+)[\s,]+([\d.]+)[\s,]+([\d.]+)(?:[\s,/]+([\d.]+%?))?\s*\)$/i.exec(text);
  if (rgba) {
    const a = rgba[4] === undefined ? 1 : rgba[4].endsWith('%') ? parseFloat(rgba[4]) / 100 : parseFloat(rgba[4]);
    return { color: new THREE.Color(+rgba[1]! / 255, +rgba[2]! / 255, +rgba[3]! / 255), alpha: a };
  }
  const color = new THREE.Color();
  try {
    color.setStyle(text);
  } catch {
    color.setStyle(fallback);
  }
  return { color, alpha: 1 };
}

export function readSceneColors(element: Element): SceneColors {
  const style = getComputedStyle(element);
  const get = (name: string, fallback: string) => parseColor(style.getPropertyValue(name), fallback);
  const grid = get('--grid', 'rgba(255, 255, 255, 0.22)');
  return {
    background: get('--viewport-bg', '#2a6fb5').color,
    grid: grid.color,
    gridAlpha: grid.alpha,
    accent: get('--accent', '#ef9f27').color,
    preview: get('--preview', '#ef9f27').color,
    erase: get('--erase', '#e86a5c').color,
    selection: get('--selection', '#faeeda').color,
  };
}
