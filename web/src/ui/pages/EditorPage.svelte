<script lang="ts">
  import { t } from '../../i18n/i18n.svelte';
  import { Viewport, type ViewportStats } from '../../render/viewport';
  import { session } from '../session.svelte';
  import { loadDefaultPack } from '../packs/activePack';

  let host: HTMLDivElement | undefined = $state();
  let stats = $state<ViewportStats | null>(null);
  let speed = $state(10);
  let packName = $state<string | null>(null);
  let loading = $state<string | null>(null);
  let viewport: Viewport | null = null;

  $effect(() => {
    const bp = session.blueprint;
    if (!host || !bp) return;
    const view = new Viewport(host);
    viewport = view;
    // Development only: lets automated checks reach the viewport (stripped from production builds).
    if (import.meta.env.DEV) (window as unknown as { __viewport?: Viewport }).__viewport = view;
    view.onStats = (s) => (stats = s);
    view.controls.onSpeed = (s) => (speed = s);
    let cancelled = false;
    (async () => {
      loading = 'editor.loading.pack';
      const pack = await loadDefaultPack().catch(() => null);
      if (cancelled) return;
      packName = pack?.info.name ?? null;
      loading = 'editor.loading.meshes';
      await view.show(bp, pack);
      loading = null;
    })();
    return () => {
      cancelled = true;
      view.dispose();
      viewport = null;
    };
  });

  const progress = $derived(stats && stats.sectionsTotal > 0 && stats.sectionsDone < stats.sectionsTotal ? stats : null);
</script>

{#if session.blueprint}
  <div class="editor">
    <div class="bar">
      <a href="#/projects">{t('nav.projects')}</a>
      <span class="sep">/</span>
      <span class="name">{session.blueprint.meta.name || session.fileName}</span>
      <span class="muted">{t('editor.size', { x: session.blueprint.meta.size[0], y: session.blueprint.meta.size[1], z: session.blueprint.meta.size[2] })}</span>
      <span class="muted">{t('editor.blocks', { count: session.blueprint.states.length })}</span>
      <span class="spacer"></span>
      <span class="muted">{t('editor.speed', { speed: Math.round(speed * 10) / 10 })}</span>
      <button class="btn" type="button" onclick={() => viewport?.frame()}>{t('editor.frame')}</button>
    </div>
    <div class="view" bind:this={host}>
      <div class="overlay top">
        {#if loading}<span class="chip">{t(loading)}</span>{/if}
        {#if progress}<span class="chip">{t('editor.sections', { done: progress.sectionsDone, total: progress.sectionsTotal })}</span>{/if}
        {#if !loading && packName === null}<span class="chip warn">{t('editor.noPack')}</span>{/if}
        {#if stats && stats.missingBlocks.length > 0 && packName !== null}
          <span class="chip warn" title={stats.missingBlocks.join('\n')}>{t('editor.missing', { count: stats.missingBlocks.length })}</span>
        {/if}
      </div>
      <div class="overlay bottom">{t('editor.help')}</div>
    </div>
  </div>
{:else}
  <section class="none panel">
    <p>{t('editor.none')}</p>
    <a class="btn primary" href="#/projects">{t('editor.openOne')}</a>
  </section>
{/if}

<style>
  .editor {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
    background: var(--chrome);
  }

  .bar {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 6px 14px;
    padding: 6px 12px;
    background: var(--chrome-2);
    border-bottom: 2px solid var(--outline);
  }

  .bar a {
    color: var(--chrome-muted);
  }

  .sep,
  .muted {
    color: var(--chrome-muted);
  }

  .spacer {
    flex: 1;
  }

  .view {
    position: relative;
    flex: 1;
    min-height: 0;
    overflow: hidden;
  }

  .overlay {
    position: absolute;
    left: 12px;
    right: 12px;
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    pointer-events: none;
  }

  .top {
    top: 12px;
  }

  .bottom {
    bottom: 10px;
    width: fit-content;
    max-width: calc(100% - 24px);
    padding: 4px 10px;
    background: rgba(26, 86, 148, 0.85);
    color: var(--chrome-muted);
  }

  .chip {
    padding: 2px 10px;
    background: var(--chrome);
    border: 2px solid var(--outline);
    pointer-events: auto;
  }

  .warn {
    background: var(--warning-bg);
    color: var(--warning);
  }

  .none {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
    padding: 24px;
  }
</style>
