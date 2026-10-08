<script lang="ts">
  import { mount, unmount } from 'svelte';
  import { createDockview, themeDark, type DockviewApi, type IContentRenderer } from 'dockview-core';
  import { panelDef } from '../panels';
  import '../vendor/dockview.css';

  let { onready, locked = false }: { onready: (api: DockviewApi) => void; locked?: boolean } = $props();

  let host: HTMLDivElement | undefined = $state();
  let api = $state.raw<DockviewApi | null>(null);

  /** A Svelte component inside a dockview panel. */
  function renderer(component: string): IContentRenderer {
    const element = document.createElement('div');
    element.className = 'dock-panel';
    let instance: Record<string, unknown> | null = null;
    return {
      element,
      init(parameters) {
        const def = panelDef(component);
        if (!def) return;
        instance = mount(def.component, { target: element, props: { params: parameters.params, api: parameters.api } });
      },
      dispose() {
        if (instance) void unmount(instance);
        instance = null;
      },
    };
  }

  $effect(() => {
    if (!host) return;
    const dock = createDockview(host, {
      createComponent: ({ name }) => renderer(name),
      theme: themeDark,
      floatingGroupBounds: 'boundedWithinViewport',
    });
    api = dock;
    onready(dock);
    return () => {
      dock.dispose();
      api = null;
    };
  });

  $effect(() => {
    api?.updateOptions({ locked, disableDnd: locked });
  });
</script>

<div class="dock" bind:this={host}></div>

<style>
  .dock {
    flex: 1;
    min-width: 0;
    min-height: 0;
    height: 100%;
  }

  /* The Blueprint theme over dockview's dark theme: pixel tabs, outlines, amber drop hints. */
  .dock :global(.dockview-theme-dark) {
    --dv-group-view-background-color: var(--panel);
    --dv-tabs-and-actions-container-background-color: var(--chrome-2);
    --dv-tabs-and-actions-container-height: 30px;
    --dv-tabs-and-actions-container-font-size: var(--text-size);
    --dv-activegroup-visiblepanel-tab-background-color: var(--panel);
    --dv-activegroup-hiddenpanel-tab-background-color: var(--chrome-2);
    --dv-inactivegroup-visiblepanel-tab-background-color: var(--panel-2);
    --dv-inactivegroup-hiddenpanel-tab-background-color: var(--chrome-2);
    --dv-activegroup-visiblepanel-tab-color: var(--text);
    --dv-activegroup-hiddenpanel-tab-color: var(--chrome-text);
    --dv-inactivegroup-visiblepanel-tab-color: var(--text);
    --dv-inactivegroup-hiddenpanel-tab-color: var(--chrome-muted);
    --dv-tab-divider-color: var(--outline);
    --dv-separator-border: var(--outline);
    --dv-paneview-header-border-color: var(--outline);
    --dv-sash-color: var(--outline);
    --dv-active-sash-color: var(--accent);
    --dv-drag-over-background-color: rgba(239, 159, 39, 0.3);
    --dv-drag-over-border-color: var(--accent);
    --dv-floating-border: 2px solid var(--outline);
    --dv-floating-box-shadow: 6px 6px 0 rgba(18, 71, 125, 0.55);
    --dv-floating-titlebar-background-color: var(--chrome);
    --dv-icon-hover-background-color: var(--chrome-raised);
    font-family: var(--font);
  }

  .dock :global(.dock-panel) {
    height: 100%;
    overflow: hidden;
    color: var(--text);
  }

  .dock :global(.panel-body) {
    height: 100%;
    display: flex;
    flex-direction: column;
    background: var(--panel);
    color: var(--text);
  }

  .dock :global(.dv-tab) {
    font-size: var(--text-size);
  }
</style>
