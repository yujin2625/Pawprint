<script lang="ts">
  import { untrack } from 'svelte';
  import { t } from '../../i18n/i18n.svelte';
  import type { Plane } from '../../core/edit/shapes';
  import { SliceView, type SliceSettings } from '../../view2d/sliceView';
  import { ctx, activeSlice, nextViewId } from './context.svelte';
  import { editor } from './editor.svelte';

  interface PanelApi {
    updateParameters(params: Record<string, unknown>): void;
  }

  let { params = {}, api }: { params?: { plane?: Plane; slice?: number }; api?: PanelApi } = $props();

  /** Each 2D view keeps its own direction and layer, saved with the layout (read once, when the panel opens). */
  const initial = untrack(() => params);
  const local = $state({ plane: (initial.plane ?? 'y') as Plane, slice: initial.slice ?? 0, initialized: initial.slice !== undefined });
  let host: HTMLDivElement | undefined = $state();
  let view = $state.raw<SliceView | null>(null);
  const me = nextViewId();

  // Tool settings come from the editor; plane and layer from this view.
  const settings = Object.setPrototypeOf(
    {
      get plane() {
        return local.plane;
      },
      get slice() {
        return local.slice;
      },
    },
    editor,
  ) as SliceSettings;

  function activate() {
    activeSlice.owner = me;
    activeSlice.plane = local.plane;
    activeSlice.slice = local.slice;
    activeSlice.set = (slice: number) => {
      local.slice = slice;
    };
  }

  $effect(() => {
    // Counted without tracking, or the effect would re-run on its own write.
    untrack(() => ctx.slicePanels++);
    return () => {
      untrack(() => ctx.slicePanels--);
      if (activeSlice.owner === me) {
        activeSlice.owner = null;
        activeSlice.set = null;
      }
    };
  });

  $effect(() => {
    const bp = ctx.blueprint, resources = ctx.resources;
    if (!bp || !resources || !host) return;
    if (!local.initialized) {
      const b = bp.bounds();
      local.slice = b ? (local.plane === 'y' ? b.min[1] : local.plane === 'z' ? b.max[2] : b.max[0]) : 0;
      local.initialized = true;
    }
    const v = new SliceView(host, resources, bp, {
      settings: () => settings,
      onPick: (state) => {
        editor.block = state;
        editor.tool = 'pencil';
      },
      onSelect: (box) => (editor.selection = box),
      onCursor: (world, state) => {
        editor.cursor = world;
        editor.cursorState = state;
      },
      onMessage: (key) => (editor.message = key),
    });
    view = v;
    if (import.meta.env.DEV) (window as unknown as { __slice?: SliceView }).__slice = v;
    v.canvas.addEventListener('pointerdown', activate);
    if (!activeSlice.owner) activate();
    return () => {
      v.canvas.removeEventListener('pointerdown', activate);
      v.dispose();
      view = null;
    };
  });

  // Keep the layout and the 3D marker in step with this view.
  $effect(() => {
    const { plane, slice } = local;
    api?.updateParameters({ plane, slice });
    // Only the view the user worked in last drives the 3D marker and the layer keys.
    if (activeSlice.owner === me) {
      activeSlice.plane = plane;
      activeSlice.slice = slice;
    }
    view?.settingsChanged();
  });

  $effect(() => {
    void [editor.tool, editor.block, editor.brushShape, editor.brushSize, editor.filled, editor.height, editor.selection, editor.clip, editor.hiddenLayers, editor.onionBelow, editor.onionAbove, editor.onionOpacity, ctx.revision];
    view?.settingsChanged();
  });

  function setPlane(plane: Plane) {
    if (local.plane === plane) return;
    const c = editor.cursor;
    const b = ctx.blueprint?.bounds();
    const point = c ?? (b ? [(b.min[0] + b.max[0]) >> 1, (b.min[1] + b.max[1]) >> 1, (b.min[2] + b.max[2]) >> 1] : [0, 0, 0]);
    local.plane = plane;
    local.slice = plane === 'y' ? point[1]! : plane === 'z' ? point[2]! : point[0]!;
    activate();
    queueMicrotask(() => view?.centerOnBlueprint());
  }

  function step(delta: number) {
    local.slice += delta;
    activate();
  }

  const axis = $derived(local.plane === 'y' ? 'Y' : local.plane === 'z' ? 'Z' : 'X');
</script>

<section class="view" aria-label={t('editor.view2d')}>
  <div class="slice-bar">
    <div class="segmented" role="group" aria-label={t('editor.plane')}>
      <button type="button" class:on={local.plane === 'y'} onclick={() => setPlane('y')}>{t('editor.plane.y')}</button>
      <button type="button" class:on={local.plane === 'z'} onclick={() => setPlane('z')}>{t('editor.plane.z')}</button>
      <button type="button" class:on={local.plane === 'x'} onclick={() => setPlane('x')}>{t('editor.plane.x')}</button>
    </div>
    <div class="slice-step">
      <button class="btn icon" type="button" aria-label={t('editor.sliceDown')} title={t('editor.sliceDown')} onclick={() => step(-1)}>-</button>
      <span class="value">{axis} = {local.slice}</span>
      <button class="btn icon" type="button" aria-label={t('editor.sliceUp')} title={t('editor.sliceUp')} onclick={() => step(1)}>+</button>
    </div>
    <span class="spacer"></span>
    <span>{t('editor.onion')}</span>
    <button type="button" class={['toggle', { on: editor.onionBelow }]} aria-pressed={editor.onionBelow} onclick={() => (editor.onionBelow = !editor.onionBelow)}>{t('editor.onionBelow')}</button>
    <button type="button" class={['toggle', { on: editor.onionAbove }]} aria-pressed={editor.onionAbove} onclick={() => (editor.onionAbove = !editor.onionAbove)}>{t('editor.onionAbove')}</button>
    <input type="range" min="0.1" max="0.8" step="0.05" bind:value={editor.onionOpacity} aria-label={t('editor.onionOpacity')} />
  </div>
  <div class="host" bind:this={host}></div>
  <div class="overlay">{t('editor.help2d')}</div>
</section>

<style>
  .view {
    position: relative;
    height: 100%;
    display: flex;
    flex-direction: column;
    background: var(--viewport-bg);
  }

  .slice-bar {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 6px 12px;
    padding: 4px 8px;
    background: var(--chrome);
    color: var(--chrome-muted);
  }

  .slice-step {
    display: flex;
    align-items: center;
    gap: 6px;
  }

  .value {
    color: var(--chrome-text);
  }

  .spacer {
    flex: 1;
  }

  .segmented {
    display: flex;
    border: 2px solid var(--outline);
  }

  .segmented button,
  .toggle {
    padding: 1px 10px;
    border: 0;
    background: var(--chrome);
    color: var(--chrome-text);
    cursor: pointer;
  }

  .segmented button.on,
  .toggle.on {
    background: var(--accent);
    color: var(--on-accent);
  }

  .toggle {
    border: 2px solid var(--outline);
  }

  .btn.icon {
    padding: 4px 6px;
    min-width: 30px;
  }

  .host {
    position: relative;
    flex: 1;
    min-height: 0;
    overflow: hidden;
  }

  .overlay {
    position: absolute;
    left: 10px;
    bottom: 8px;
    max-width: calc(100% - 20px);
    padding: 2px 8px;
    background: rgba(26, 86, 148, 0.85);
    color: var(--chrome-muted);
    pointer-events: none;
  }
</style>
