import type { DockviewApi, SerializedDockview } from 'dockview-core';
import { t } from '../../i18n/i18n.svelte';
import { getSetting, setSetting } from '../../storage/db';
import { panelDef } from '../panels';
import { isDesktop } from '../../platform/platform';

/** Built-in layouts, the user's saved ones, and the last layout used (restored on reload). */

export const BUILT_IN = ['default', 'wide', 'drawing', 'dual'] as const;
export type BuiltIn = (typeof BUILT_IN)[number];

export interface SavedLayout {
  name: string;
  layout: SerializedDockview;
}

const CURRENT = 'layout.current';
const PRESETS = 'layout.presets';
const SIDE = ['palette', 'layers', 'materials', 'stamps'];

let counter = 0;
/** Panel ids are unique per instance: the component name plus a number. */
export function panelId(component: string): string {
  return `${component}-${Date.now().toString(36)}-${counter++}`;
}

export function title(component: string, instance = 0): string {
  const def = panelDef(component);
  const base = def ? t(def.title) : component;
  return instance > 0 ? `${base} ${instance + 1}` : base;
}

function addSide(api: DockviewApi, reference: string, direction: 'right' | 'below', width: number): void {
  let first: string | null = null;
  for (const component of SIDE) {
    const id = panelId(component);
    api.addPanel({
      id,
      component,
      title: title(component),
      inactive: first !== null,
      ...(first ? { position: { referencePanel: first, direction: 'within' } } : { position: { referencePanel: reference, direction }, initialWidth: width }),
    });
    first ??= id;
  }
  api.getPanel(first!)?.api.setActive();
}

export function applyBuiltIn(api: DockviewApi, preset: BuiltIn): void {
  api.clear();
  const width = api.width;
  if (preset === 'default') {
    const v3 = panelId('view3d');
    api.addPanel({ id: v3, component: 'view3d', title: title('view3d') });
    const v2 = panelId('view2d');
    api.addPanel({ id: v2, component: 'view2d', title: title('view2d'), params: { plane: 'y' }, position: { referencePanel: v3, direction: 'right' } });
    addSide(api, v2, 'right', 300);
    api.getPanel(v3)?.group.api.setSize({ width: Math.max(300, (width - 300) / 2) });
  } else if (preset === 'wide') {
    const v3 = panelId('view3d');
    api.addPanel({ id: v3, component: 'view3d', title: title('view3d') });
    addSide(api, v3, 'right', 300);
  } else if (preset === 'dual') {
    // Two monitors: 3D and the side panels here, plan and front views in a second window to drag to the other
    // screen (desktop app), or in a floating group on the web.
    const v3 = panelId('view3d');
    api.addPanel({ id: v3, component: 'view3d', title: title('view3d') });
    addSide(api, v3, 'right', 300);
    const plan = panelId('view2d');
    api.addPanel({ id: plan, component: 'view2d', title: title('view2d'), params: { plane: 'y' }, position: { referencePanel: v3, direction: 'below' } });
    const front = panelId('view2d');
    // Tabs in one group: a floating group cannot be split.
    api.addPanel({ id: front, component: 'view2d', title: title('view2d', 1), params: { plane: 'z' }, inactive: true, position: { referencePanel: plan, direction: 'within' } });
    const group = api.getPanel(plan)!.group;
    const box = { width: 900, height: 600 };
    if (isDesktop) void api.addPopoutGroup(group, { position: { top: 80, left: 80, ...box } });
    else api.addFloatingGroup(group, { position: { top: 60, left: 60, ...box } });
  } else {
    // Drawing: a big plan view, a front elevation and a small 3D view beside it.
    const plan = panelId('view2d');
    api.addPanel({ id: plan, component: 'view2d', title: title('view2d'), params: { plane: 'y' } });
    const v3 = panelId('view3d');
    api.addPanel({ id: v3, component: 'view3d', title: title('view3d'), position: { referencePanel: plan, direction: 'right' }, initialWidth: Math.max(260, width * 0.3) });
    const front = panelId('view2d');
    api.addPanel({ id: front, component: 'view2d', title: title('view2d', 1), params: { plane: 'z' }, position: { referencePanel: v3, direction: 'below' } });
    addSide(api, v3, 'right', 280);
  }
}

/** Adds one panel, or brings an existing single-instance panel to the front. */
export function openPanel(api: DockviewApi, component: string): void {
  const def = panelDef(component);
  if (!def) return;
  const existing = api.panels.filter((p) => p.api.component === component);
  if (!def.multiple && existing[0]) {
    existing[0].api.setActive();
    return;
  }
  const anchor = existing[existing.length - 1] ?? api.activePanel;
  api.addPanel({
    id: panelId(component),
    component,
    title: title(component, existing.length),
    // Another 2D view usually means an elevation next to the plan.
    params: component === 'view2d' ? { plane: existing.length % 2 ? 'z' : 'y' } : undefined,
    ...(anchor ? { position: { referencePanel: anchor.id, direction: def.multiple ? 'right' : 'within' } } : {}),
  });
}

/** Re-titles tabs after a language change. */
export function retitle(api: DockviewApi): void {
  const seen = new Map<string, number>();
  for (const panel of api.panels) {
    const component = panel.api.component;
    const n = seen.get(component) ?? 0;
    seen.set(component, n + 1);
    panel.api.setTitle(title(component, n));
  }
}

export async function loadCurrent(): Promise<SerializedDockview | null> {
  return (await getSetting<SerializedDockview>(CURRENT)) ?? null;
}

export async function saveCurrent(layout: SerializedDockview): Promise<void> {
  await setSetting(CURRENT, layout);
}

export async function loadPresets(): Promise<SavedLayout[]> {
  return (await getSetting<SavedLayout[]>(PRESETS)) ?? [];
}

export async function savePresets(presets: SavedLayout[]): Promise<void> {
  // A plain copy: IndexedDB cannot store the reactive proxies the UI keeps the list in.
  await setSetting(PRESETS, JSON.parse(JSON.stringify(presets)) as SavedLayout[]);
}

/** A layout file: one saved layout in JSON, for sharing. */
export function toFile(preset: SavedLayout): Blob {
  return new Blob([JSON.stringify({ pawprintLayout: 1, ...preset }, null, 2)], { type: 'application/json' });
}

export function fromFile(text: string): SavedLayout {
  const data = JSON.parse(text) as { pawprintLayout?: number; name?: unknown; layout?: unknown };
  if (data.pawprintLayout !== 1 || typeof data.name !== 'string' || !data.layout || typeof data.layout !== 'object') {
    throw new Error('not a Pawprint layout file');
  }
  return { name: data.name, layout: data.layout as SerializedDockview };
}

/** Panels a layout refers to must all exist in this version, or restoring it would leave holes. */
export function isUsable(layout: SerializedDockview): boolean {
  const panels = (layout as { panels?: Record<string, { contentComponent?: string }> }).panels ?? {};
  return Object.values(panels).every((p) => !!p.contentComponent && !!panelDef(p.contentComponent));
}
