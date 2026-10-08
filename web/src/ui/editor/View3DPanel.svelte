<script lang="ts">
  import { t } from '../../i18n/i18n.svelte';
  import { Viewport } from '../../render/viewport';
  import { Editor3D } from '../../render/editor3d';
  import { ctx, activeSlice, dropViewport, useViewport } from './context.svelte';
  import { editor } from './editor.svelte';

  let host: HTMLDivElement | undefined = $state();
  let viewport = $state.raw<Viewport | null>(null);
  let editing = $state.raw<Editor3D | null>(null);

  $effect(() => {
    const bp = ctx.blueprint, resources = ctx.resources;
    if (!bp || !resources || !host) return;
    const view = new Viewport(host, resources);
    viewport = view;
    useViewport(view);
    if (import.meta.env.DEV) (window as unknown as { __viewport?: Viewport }).__viewport = view;
    view.onStats = (s) => (ctx.stats = s);
    view.setHiddenLayers(editor.hiddenLayers);
    void view.show(bp);
    const edit = new Editor3D(view, bp, {
      settings: () => editor,
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
    editing = edit;
    if (import.meta.env.DEV) (window as unknown as { __edit3d?: Editor3D }).__edit3d = edit;
    const focus = () => useViewport(view);
    view.canvas.addEventListener('pointerdown', focus);
    return () => {
      view.canvas.removeEventListener('pointerdown', focus);
      edit.dispose();
      editing = null;
      dropViewport(view);
      view.dispose();
      viewport = null;
    };
  });

  $effect(() => {
    void [editor.tool, editor.block, editor.brushShape, editor.brushSize, editor.filled, editor.height, editor.selection, editor.clip, editor.hiddenLayers];
    editing?.refresh();
  });

  $effect(() => {
    viewport?.setHiddenLayers(editor.hiddenLayers);
  });

  $effect(() => {
    void ctx.revision;
    const show = ctx.slicePanels > 0;
    viewport?.setSlice(show ? activeSlice.plane : null, activeSlice.slice);
  });
</script>

<section class="view" aria-label={t('editor.view3d')}>
  <div class="host" bind:this={host}></div>
  <div class="overlay top">
    {#if ctx.stats && ctx.stats.sectionsDone < ctx.stats.sectionsTotal}<span class="chip">{t('editor.sections', { done: ctx.stats.sectionsDone, total: ctx.stats.sectionsTotal })}</span>{/if}
    {#if ctx.blueprint && !ctx.pack}<span class="chip warn">{t('editor.noPack')}</span>{/if}
    {#if ctx.stats && ctx.stats.missingBlocks.length && ctx.pack}<span class="chip warn" title={ctx.stats.missingBlocks.join('\n')}>{t('editor.missing', { count: ctx.stats.missingBlocks.length })}</span>{/if}
  </div>
  <div class="overlay bottom">{t('editor.help')}</div>
</section>

<style>
  .view {
    position: relative;
    height: 100%;
    display: flex;
    flex-direction: column;
    background: var(--viewport-bg);
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
    right: 10px;
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    pointer-events: none;
  }

  .top {
    top: 10px;
  }

  .bottom {
    bottom: 8px;
    width: fit-content;
    max-width: calc(100% - 20px);
    padding: 2px 8px;
    background: rgba(26, 86, 148, 0.85);
    color: var(--chrome-muted);
  }

  .chip {
    padding: 0 8px;
    background: var(--chrome);
    color: var(--chrome-text);
    border: 2px solid var(--outline);
    pointer-events: auto;
  }

  .warn {
    background: var(--warning-bg);
    color: var(--warning);
  }
</style>
