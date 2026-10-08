<script lang="ts">
  import { t } from '../../i18n/i18n.svelte';
  import { readPawprint } from '../../core/format/pawprint';
  import { FileFormatError } from '../../core/zip';
  import { openBlueprint } from '../session.svelte';

  let input: HTMLInputElement;
  let error = $state<{ key: string; params: Record<string, string | number> } | null>(null);
  let busy = $state(false);
  let dragging = $state(false);

  async function open(file: File) {
    error = null;
    busy = true;
    try {
      const bp = readPawprint(new Uint8Array(await file.arrayBuffer()));
      openBlueprint(bp, file.name.replace(/\.pawprint$/i, ''));
      location.hash = '#/editor';
    } catch (e) {
      error = e instanceof FileFormatError ? { key: e.key, params: e.params } : { key: 'error.unknown', params: { message: String(e) } };
    } finally {
      busy = false;
    }
  }

  function picked() {
    const file = input.files?.[0];
    input.value = '';
    if (file) open(file);
  }

  function dropped(e: DragEvent) {
    e.preventDefault();
    dragging = false;
    const file = e.dataTransfer?.files[0];
    if (file) open(file);
  }
</script>

<div class="head">
  <h1>{t('projects.title')}</h1>
  <button class="btn primary" type="button" disabled={busy} onclick={() => input.click()}>{t('projects.open')}</button>
  <input bind:this={input} type="file" accept=".pawprint" hidden onchange={picked} />
</div>

{#if error}
  <div class="error" role="alert">
    <strong>{t('projects.openFailed')}</strong>
    <span>{t(error.key, error.params)}</span>
  </div>
{/if}

<section class="empty panel">
  <img class="pixel" src="./brand/mascot-192.png" alt="" width="192" height="192" />
  <div class="text">
    <h2>{t('projects.empty.title')}</h2>
    <p class="muted">{t('projects.empty.body')}</p>
    <a class="btn light" href="#/packs">{t('projects.empty.addPack')}</a>
  </div>
</section>

<div
  class={['drop', { dragging }]}
  role="region"
  aria-label={t('projects.drop')}
  ondragover={(e) => (e.preventDefault(), (dragging = true))}
  ondragleave={() => (dragging = false)}
  ondrop={dropped}
>
  {t('projects.drop')}
</div>

<style>
  .head {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 12px 20px;
    margin-bottom: 20px;
  }

  .head h1 {
    flex: 1;
  }

  .error {
    display: flex;
    flex-direction: column;
    gap: 4px;
    margin-bottom: 16px;
    padding: 8px 12px;
    border: 2px solid var(--outline);
    background: var(--warning-bg);
    color: var(--warning);
  }

  .empty {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 28px;
    padding: 28px 32px;
  }

  .text {
    flex: 1;
    min-width: 260px;
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }

  .drop {
    margin-top: 20px;
    padding: 18px;
    text-align: center;
    border: 2px dashed rgba(250, 238, 218, 0.45);
    color: var(--chrome-muted);
  }

  .dragging {
    border-color: var(--accent);
    background: rgba(239, 159, 39, 0.15);
  }
</style>
