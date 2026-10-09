import type { Component } from 'svelte';
import View3DPanel from './editor/View3DPanel.svelte';
import View2DPanel from './editor/View2DPanel.svelte';
import DockPalette from './editor/DockPalette.svelte';
import DockLayers from './editor/DockLayers.svelte';
import DockMaterials from './editor/DockMaterials.svelte';
import DockStamps from './editor/DockStamps.svelte';

/**
 * Every editor panel, as the layout system sees it (WEB_IMPLEMENTATION §4.11). Panels only read shared state,
 * so the layout can put them anywhere and open views more than once.
 */
export interface PanelDef {
  /** Component name stored in saved layouts; never rename. */
  id: string;
  /** i18n key of the tab title. */
  title: string;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  component: Component<any>;
  /** Views can be opened several times (e.g. a plan and an elevation side by side). */
  multiple: boolean;
}

export const PANELS: PanelDef[] = [
  { id: 'view3d', title: 'editor.view3d', component: View3DPanel, multiple: true },
  { id: 'view2d', title: 'editor.view2d', component: View2DPanel, multiple: true },
  { id: 'palette', title: 'editor.palette', component: DockPalette, multiple: false },
  { id: 'layers', title: 'layers.title', component: DockLayers, multiple: false },
  { id: 'materials', title: 'materials.title', component: DockMaterials, multiple: false },
  { id: 'stamps', title: 'editor.stamps', component: DockStamps, multiple: false },
];

export function panelDef(id: string): PanelDef | undefined {
  return PANELS.find((p) => p.id === id);
}
