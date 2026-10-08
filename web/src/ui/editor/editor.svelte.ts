import type { BrushShape, Plane } from '../../core/edit/shapes';
import type { Tool } from '../../view2d/sliceView';

/** Editor settings shared by the panels (they never talk to each other directly; see WEB_IMPLEMENTATION §4.11). */
export const editor = $state({
  tool: 'pencil' as Tool,
  /** Full block state the brush places, e.g. `minecraft:oak_stairs[facing=east,half=bottom]`. */
  block: 'minecraft:stone',
  brushShape: 'square' as BrushShape,
  brushSize: 1,
  filled: true,
  plane: 'y' as Plane,
  slice: 0,
  onionBelow: true,
  onionAbove: false,
  onionOpacity: 0.35,
  view: 'split' as '3d' | '2d' | 'split',
  cursor: null as [number, number, number] | null,
  cursorState: null as string | null,
  message: null as string | null,
});

export const TOOL_KEYS: Record<string, Tool> = { b: 'pencil', e: 'eraser', g: 'fill', i: 'picker', l: 'line', r: 'rect', o: 'ellipse' };
