<script lang="ts">
  import { t } from '../i18n/i18n.svelte';
  import { listPacks, type StoredPack } from '../storage/db';
  import { aiInstructions, rememberAiPack } from './projects';
  import type { BlockListing } from '../core/format/textBlueprint';

  interface Props {
    /** Called with the notice to show, or null when cancelled. Throws are shown by the caller. */
    onclose: (notice: string | null, error?: unknown) => void;
  }

  let { onclose }: Props = $props();
  let dialog: HTMLDialogElement;
  let packs = $state<StoredPack[]>([]);
  let packId = $state<string | null>(null);
  let listBlocks = $state(true);
  let preview = $state<{ text: string; blocks: BlockListing | null } | null>(null);
  let copying = $state(false);

  $effect(() => {
    dialog.showModal();
    void listPacks().then((list) => {
      packs = list;
      packId = (list.find((p) => p.isDefault) ?? list[0])?.id ?? null;
    });
  });

  // The text for the chosen pack; reading a big pack takes a moment.
  $effect(() => {
    const id = packId, list = listBlocks;
    preview = null;
    let current = true;
    aiInstructions(id ?? undefined, list).then(
      (p) => current && (preview = p),
      (e) => current && onclose(null, e),
    );
    return () => (current = false);
  });

  const chosen = $derived(packs.find((p) => p.id === packId) ?? null);
  const moddedCount = $derived(preview?.blocks?.total ?? 0);

  async function copy() {
    if (!preview) return;
    copying = true;
    try {
      await navigator.clipboard.writeText(preview.text);
      await rememberAiPack(packId);
      onclose(packId ? 'projects.ai.promptCopied' : 'projects.ai.promptCopiedNoPack');
    } catch (e) {
      onclose(null, e);
    }
  }
</script>

<dialog bind:this={dialog} class="panel" aria-labelledby="ai-title" oncancel={() => onclose(null)}>
  <h2 id="ai-title">{t('projects.ai.copyPrompt')}</h2>
  <p class="muted">{t('projects.ai.dialogHelp')}</p>

  {#if packs.length}
    <label class="field">
      <span>{t('projects.ai.pack')}</span>
      <select class="input" bind:value={packId}>
        {#each packs as pack (pack.id)}
          <option value={pack.id}>{pack.info.name}{pack.isDefault ? ` (${t('packs.default')})` : ''}</option>
        {/each}
      </select>
    </label>
    {#if chosen && !chosen.isDefault}<p class="hint">{t('projects.ai.notDefault')}</p>{/if}

    <label class="check">
      <input type="checkbox" bind:checked={listBlocks} disabled={!moddedCount} />
      <span>{t('projects.ai.listBlocks')}</span>
    </label>
    <p class="hint" aria-live="polite">
      {#if !preview}
        {t('projects.ai.reading')}
      {:else if !moddedCount}
        {t('projects.ai.noModded')}
      {:else if !listBlocks}
        {t('projects.ai.namespacesOnly', { total: moddedCount })}
      {:else if preview.blocks!.listed < moddedCount}
        {t('projects.ai.listedSome', { listed: preview.blocks!.listed, total: moddedCount })}
      {:else}
        {t('projects.ai.listedAll', { total: moddedCount })}
      {/if}
    </p>
  {:else}
    <p class="hint">{t('projects.ai.noPack')}</p>
  {/if}

  <div class="buttons">
    <button class="btn light" type="button" onclick={() => onclose(null)}>{t('packs.cancel')}</button>
    <button class="btn primary" type="button" disabled={!preview || copying} onclick={copy}>{t('projects.ai.copy')}</button>
  </div>
</dialog>

<style>
  dialog {
    width: min(520px, calc(100vw - 32px));
    padding: 20px 22px;
    box-shadow: 8px 8px 0 var(--shadow);
  }

  dialog::backdrop {
    background: var(--shadow);
  }

  h2 {
    margin: 0 0 8px;
    font-size: 1.1rem;
  }

  p {
    line-height: 1.4;
    margin: 0 0 12px;
  }

  .field {
    display: grid;
    gap: 4px;
    margin-bottom: 6px;
  }

  .field select {
    width: 100%;
  }

  .check {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-top: 10px;
  }

  .hint {
    font-size: 0.9rem;
    color: var(--text-muted);
    margin: 4px 0 0;
  }

  .buttons {
    display: flex;
    justify-content: flex-end;
    gap: 8px;
    margin-top: 18px;
  }
</style>
