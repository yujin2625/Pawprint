import type { EditableBlueprint } from '../../core/blueprint/editable';
import type { Plane } from '../../core/edit/shapes';
import type { LoadedPack } from '../../core/pack/pawpack';
import type { BlockResources } from '../../render/resources';
import type { Viewport, ViewportStats } from '../../render/viewport';

/**
 * What every editor panel shares. Panels never talk to each other directly (WEB_IMPLEMENTATION §4.11): they read
 * this and the editor settings, so they work wherever the layout puts them, and more than once.
 */
export const ctx = $state({
  projectId: '',
  blueprint: null as EditableBlueprint | null,
  pack: null as LoadedPack | null,
  resources: null as BlockResources | null,
  /** Bumped on every edit and layer change. */
  revision: 0,
  stats: null as ViewportStats | null,
  /** Stamps list reloads when this changes. */
  stampsKey: 0,
  /** Open 2D views; the 3D slice marker shows while there is one. */
  slicePanels: 0,
});

/** Open 3D views, most recently used first (non-reactive: used for actions, not display). */
export const viewports: Viewport[] = [];

export function useViewport(view: Viewport): void {
  const i = viewports.indexOf(view);
  if (i >= 0) viewports.splice(i, 1);
  viewports.unshift(view);
}

export function dropViewport(view: Viewport): void {
  const i = viewports.indexOf(view);
  if (i >= 0) viewports.splice(i, 1);
}

let viewIds = 0;
export function nextViewId(): number {
  return ++viewIds;
}

/** Plane and layer of the 2D view used last; keyboard layer stepping and the 3D slice marker follow it. */
export const activeSlice = $state({
  plane: 'y' as Plane,
  slice: 0,
  /** The 2D view these values come from (a number: an object would be wrapped in a proxy and never compare equal). */
  owner: null as number | null,
  set: null as ((slice: number) => void) | null,
});
