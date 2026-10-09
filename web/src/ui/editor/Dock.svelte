<script lang="ts">
  import { mount, unmount } from 'svelte';
  import { createDockview, themeDark, type DockviewApi, type IContentRenderer } from 'dockview-core';
  import { panelDef } from '../panels';
  import { themeDocument } from '../theme/theme.svelte';
  import '../vendor/dockview.css';

  let { onready, locked = false }: { onready: (api: DockviewApi) => void; locked?: boolean } = $props();

  let host: HTMLDivElement | undefined = $state();
  let api = $state.raw<DockviewApi | null>(null);

  /**
   * A Svelte component inside a dockview panel. When the panel moves to or from its own window the component is
   * mounted again, so canvases, observers and animation frames belong to the window the panel is now in.
   */
  function renderer(component: string): IContentRenderer {
    const element = document.createElement('div');
    element.className = 'dock-panel';
    let instance: Record<string, unknown> | null = null;
    let moved: { dispose(): void } | null = null;
    let remount: ReturnType<typeof setTimeout> | undefined;
    return {
      element,
      init(parameters) {
        const def = panelDef(component);
        if (!def) return;
        // The latest parameters: a remounted 2D view keeps its plane and layer.
        const start = () => (instance = mount(def.component, { target: element, props: { params: parameters.api.getParameters(), api: parameters.api } }));
        start();
        let where = parameters.api.location.type;
        moved = parameters.api.onDidLocationChange((e) => {
          const now = e.location.type;
          if ((now === 'popout') === (where === 'popout')) return;
          where = now;
          clearTimeout(remount);
          remount = setTimeout(() => {
            if (instance) void unmount(instance);
            start();
          });
        });
      },
      dispose() {
        clearTimeout(remount);
        moved?.dispose();
        if (instance) void unmount(instance);
        instance = null;
      },
    };
  }

  /** Shortcuts typed in a panel window act like in the main window. */
  function forwardKeys(win: Window): void {
    const forward = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null;
      if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT' || target.isContentEditable)) return;
      const copy = new KeyboardEvent(e.type, e);
      window.dispatchEvent(copy);
      if (copy.defaultPrevented) e.preventDefault();
    };
    win.addEventListener('keydown', forward);
    win.addEventListener('keyup', forward);
    win.addEventListener('blur', () => window.dispatchEvent(new FocusEvent('blur')));
  }

  $effect(() => {
    if (!host) return;
    const dock = createDockview(host, {
      createComponent: ({ name }) => renderer(name),
      theme: themeDark,
      floatingGroupBounds: 'boundedWithinViewport',
      popoutUrl: 'popout.html',
    });
    dock.onDidAddPopoutGroup((popout) => {
      forwardKeys(popout.window);
      const untheme = themeDocument(popout.window.document);
      popout.window.addEventListener('pagehide', untheme);
      popout.window.document.title = 'Pawprint · ' + (popout.group.activePanel?.title ?? '');
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
  /* Global, not under .dock: panel windows hold their group outside it. */
  :global(.dockview-theme-dark) {
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
    --dv-drag-over-background-color: color-mix(in srgb, var(--accent) 30%, transparent);
    --dv-drag-over-border-color: var(--accent);
    --dv-floating-border: 2px solid var(--outline);
    --dv-floating-box-shadow: 6px 6px 0 var(--shadow);
    --dv-floating-titlebar-background-color: var(--chrome);
    --dv-icon-hover-background-color: var(--chrome-raised);
    font-family: var(--font);
  }

  :global(.dock-panel) {
    height: 100%;
    overflow: hidden;
    color: var(--text);
  }

  :global(.dock-panel .panel-body) {
    height: 100%;
    display: flex;
    flex-direction: column;
    background: var(--panel);
    color: var(--text);
  }

  :global(.dockview-theme-dark .dv-tab) {
    font-size: var(--text-size);
  }
</style>
