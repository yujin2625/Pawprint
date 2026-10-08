<script lang="ts">
  import { formatBytes, formatDate, t } from '../../i18n/i18n.svelte';
  import type { StoredPack } from '../../storage/db';

  interface Props {
    pack: StoredPack;
    onrename: (name: string) => void;
    ondelete: () => void;
    onmakedefault: () => void;
  }

  let { pack, onrename, ondelete, onmakedefault }: Props = $props();
  let editing = $state(false);
  let draft = $state('');

  const urls = $derived(pack.previews.map((p) => ({ id: p.id, url: URL.createObjectURL(p.png) })));
  $effect(() => {
    const current = urls;
    return () => current.forEach((p) => URL.revokeObjectURL(p.url));
  });

  const info = $derived(pack.info);
  const chips = $derived(
    [
      'MC ' + info.mcVersion,
      info.loader && info.loader !== 'vanilla' ? info.loader : null,
      info.mods?.length ? t('packs.mods', { count: info.mods.length }) : null,
      info.languages.join(' · '),
      t('packs.source.' + info.source),
    ].filter((c): c is string => !!c),
  );

  function startRename() {
    draft = info.name;
    editing = true;
  }

  function finishRename(event: SubmitEvent) {
    event.preventDefault();
    const name = draft.trim();
    if (name) onrename(name);
    editing = false;
  }
</script>

<article class="panel">
  <div class="swatches" aria-hidden="true">
    {#each urls as preview (preview.id)}
      <span style:background-image="url({preview.url})" title={preview.id}></span>
    {/each}
  </div>
  <div class="body">
    <div class="title">
      {#if editing}
        <form onsubmit={finishRename}>
          <label class="visually-hidden" for="name-{pack.id}">{t('packs.nameLabel')}</label>
          <!-- svelte-ignore a11y_autofocus -->
          <input id="name-{pack.id}" class="input" bind:value={draft} autofocus maxlength="80" />
          <button class="btn primary" type="submit">{t('packs.save')}</button>
          <button class="btn light" type="button" onclick={() => (editing = false)}>{t('packs.cancel')}</button>
        </form>
      {:else}
        <h2>{info.name}</h2>
        {#if pack.isDefault}<span class="badge">{t('packs.default')}</span>{/if}
      {/if}
    </div>
    <div class="chips">
      {#each chips as chip (chip)}<span>{chip}</span>{/each}
    </div>
    <p class="muted">
      {t('packs.blocks', { count: info.blockCount })} · {formatBytes(pack.sizeBytes)} · {t('packs.made', { date: formatDate(info.created) })}
    </p>
    {#if !info.propertiesComplete}
      <p class="note">{t('packs.partialProperties')}</p>
    {/if}
    <div class="actions">
      {#if !pack.isDefault}
        <button class="btn light" type="button" onclick={onmakedefault}>{t('packs.makeDefault')}</button>
      {/if}
      <button class="btn light" type="button" onclick={startRename}>{t('packs.rename')}</button>
      <button class="btn danger" type="button" onclick={ondelete}>{t('packs.delete')}</button>
    </div>
  </div>
</article>

<style>
  article {
    display: flex;
    flex-wrap: wrap;
  }

  .swatches {
    width: 168px;
    flex: none;
    padding: 12px;
    background: var(--viewport-bg);
    display: grid;
    grid-template-columns: repeat(4, 32px);
    grid-auto-rows: 32px;
    gap: 4px;
    align-content: center;
    justify-content: center;
  }

  .swatches span {
    background-size: 100% auto;
    background-position: top;
    image-rendering: pixelated;
  }

  .body {
    flex: 1;
    min-width: 260px;
    padding: 14px 16px;
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  .title,
  form {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 10px;
  }

  .badge {
    padding: 0 8px;
    background: var(--accent);
    color: var(--on-accent);
  }

  .chips {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }

  .chips span {
    padding: 0 8px;
    border: 1px solid var(--panel-border);
    background: var(--panel-input);
  }

  .note {
    padding: 2px 8px;
    background: var(--warning-bg);
    color: var(--warning);
  }

  .actions {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin-top: 4px;
  }
</style>
