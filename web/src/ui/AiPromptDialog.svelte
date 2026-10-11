<script lang="ts">
  import { t } from '../i18n/i18n.svelte';
  import { listPacks, type StoredPack } from '../storage/db';
  import { aiSource, rememberAiChoice, type AiSource } from './projects';
  import { AI_HUGE_TOKENS, AI_LARGE_TOKENS, aiPromptForBlocks, estimateTokens } from '../core/format/textBlueprint';

  interface Props {
    /** Called with the notice to show, or null when cancelled. Throws are shown by the caller. */
    onclose: (notice: string | null, error?: unknown) => void;
  }

  let { onclose }: Props = $props();
  let dialog: HTMLDialogElement;
  let packs = $state<StoredPack[]>([]);
  let packId = $state<string | null>(null);
  let source = $state.raw<AiSource | null>(null);
  /** Mods left out of the instructions. */
  let excluded = $state<Set<string>>(new Set());
  let copying = $state(false);

  $effect(() => {
    dialog.showModal();
    void listPacks().then((list) => {
      packs = list;
      packId = (list.find((p) => p.isDefault) ?? list[0])?.id ?? null;
    });
  });

  // The chosen pack's blocks; reading a big pack takes a moment.
  $effect(() => {
    const id = packId;
    source = null;
    let current = true;
    aiSource(id ?? undefined).then(
      (s) => {
        if (!current) return;
        source = s;
        excluded = new Set(s.excluded.filter((ns) => s.mods.some((m) => m.namespace === ns)));
      },
      (e) => current && onclose(null, e),
    );
    return () => (current = false);
  });

  const result = $derived.by(() => {
    if (!source) return null;
    const chosen = new Set(source.mods.map((m) => m.namespace).filter((ns) => !excluded.has(ns)));
    const { text, listing } = aiPromptForBlocks(source.mcVersion, source.blocks, chosen);
    return { text, listing, tokens: estimateTokens(text) };
  });
  const chosen = $derived(packs.find((p) => p.id === packId) ?? null);

  function toggle(namespace: string, on: boolean) {
    const next = new Set(excluded);
    if (on) next.delete(namespace);
    else next.add(namespace);
    excluded = next;
  }

  function setAll(on: boolean) {
    excluded = on ? new Set() : new Set(source?.mods.map((m) => m.namespace));
  }

  async function copy() {
    if (!result || !source) return;
    copying = true;
    try {
      await navigator.clipboard.writeText(result.text);
      await rememberAiChoice(source.packId, [...excluded]);
      onclose(source.packId ? 'projects.ai.promptCopied' : 'projects.ai.promptCopiedNoPack');
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

    {#if !source}
      <p class="hint" aria-live="polite">{t('projects.ai.reading')}</p>
    {:else if !source.mods.length}
      <p class="hint">{t('projects.ai.noModded')}</p>
    {:else}
      <div class="mods-head">
        <span id="ai-mods">{t('projects.ai.mods')}</span>
        <span class="spacer"></span>
        <button class="btn light small" type="button" onclick={() => setAll(true)}>{t('projects.ai.all')}</button>
        <button class="btn light small" type="button" onclick={() => setAll(false)}>{t('projects.ai.none')}</button>
      </div>
      <ul class="mods" aria-labelledby="ai-mods">
        {#each source.mods as mod (mod.namespace)}
          <li>
            <label>
              <input type="checkbox" checked={!excluded.has(mod.namespace)} onchange={(e) => toggle(mod.namespace, e.currentTarget.checked)} />
              <span class="name">{mod.name ?? mod.namespace}{#if mod.name && mod.name !== mod.namespace}<span class="ns">{mod.namespace}</span>{/if}</span>
              <span class="count">{t('projects.ai.modBlocks', { count: mod.blocks })}</span>
            </label>
          </li>
        {/each}
      </ul>
    {/if}
  {:else}
    <p class="hint">{t('projects.ai.noPack')}</p>
  {/if}

  {#if result && source?.mods.length}
    <p class="hint" aria-live="polite">
      {result.listing.listed
        ? t('projects.ai.size', { blocks: result.listing.listed, chars: result.text.length, tokens: result.tokens })
        : t('projects.ai.noneChosen')}
    </p>
    {#if result.tokens > AI_HUGE_TOKENS}
      <p class="warn huge" role="alert">{t('projects.ai.warnHuge')}</p>
    {:else if result.tokens > AI_LARGE_TOKENS}
      <p class="warn" role="alert">{t('projects.ai.warnLarge')}</p>
    {/if}
  {/if}

  <div class="buttons">
    <button class="btn light" type="button" onclick={() => onclose(null)}>{t('packs.cancel')}</button>
    <button class="btn primary" type="button" disabled={!result || copying} onclick={copy}>{t('projects.ai.copy')}</button>
  </div>
</dialog>

<style>
  dialog {
    width: min(560px, calc(100vw - 32px));
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

  .mods-head {
    display: flex;
    align-items: center;
    gap: 6px;
    margin-top: 12px;
  }

  .spacer {
    flex: 1;
  }

  .btn.small {
    padding: 2px 8px;
    font-size: 0.85rem;
  }

  .mods {
    list-style: none;
    margin: 6px 0 0;
    padding: 4px 0;
    max-height: min(280px, 40vh);
    overflow-y: auto;
    border: 2px solid var(--panel-border);
  }

  .mods label {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 2px 8px;
  }

  .mods .name {
    flex: 1;
    min-width: 0;
    overflow-wrap: anywhere;
  }

  .ns,
  .count {
    color: var(--text-muted);
    font-size: 0.85rem;
  }

  .ns {
    margin-left: 0.5em;
  }

  .hint {
    font-size: 0.9rem;
    color: var(--text-muted);
    margin: 8px 0 0;
  }

  .warn {
    font-size: 0.9rem;
    margin: 8px 0 0;
    padding: 6px 8px;
    background: var(--warning-bg);
    color: var(--warning);
  }

  .warn.huge {
    border-left: 4px solid var(--danger);
  }

  .buttons {
    display: flex;
    justify-content: flex-end;
    gap: 8px;
    margin-top: 18px;
  }
</style>
