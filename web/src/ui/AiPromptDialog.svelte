<script lang="ts">
  import { blockLanguage, locale, t } from '../i18n/i18n.svelte';
  import { listPacks, type StoredPack } from '../storage/db';
  import { aiSource, rememberAiChoice, type AiMode, type AiSource } from './projects';
  import { AI_HUGE_TOKENS, AI_LARGE_TOKENS, aiPromptForBlocks, aiPromptForPalette, estimateTokens, type AiBlock } from '../core/format/textBlueprint';
  import { blockName } from '../core/pack/pawpack';
  import { search } from '../core/search/blockSearch';

  interface Props {
    /** Called with the notice to show, or null when cancelled. Throws are shown by the caller. */
    onclose: (notice: string | null, error?: unknown) => void;
  }

  /** At most this many search results are shown; a narrower search finds the rest. */
  const SHOWN = 150;

  let { onclose }: Props = $props();
  let dialog: HTMLDialogElement;
  let packs = $state<StoredPack[]>([]);
  let packId = $state<string | null>(null);
  let source = $state.raw<AiSource | null>(null);
  let mode = $state<AiMode>('mods');
  /** Mods left out in the mods mode. */
  let excluded = $state<Set<string>>(new Set());
  /** Blocks of the build palette, in the order they were added. */
  let palette = $state<string[]>([]);
  let query = $state('');
  let colors = $state(true);
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
        const known = new Set(s.blocks.map((b) => b.id));
        source = s;
        mode = s.choice.mode;
        excluded = new Set(s.choice.excluded.filter((ns) => s.mods.some((m) => m.namespace === ns)));
        palette = s.choice.palette.filter((id) => known.has(id));
        colors = s.choice.colors;
        query = '';
      },
      (e) => current && onclose(null, e),
    );
    return () => (current = false);
  });

  const byId = $derived(new Map(source?.blocks.map((b) => [b.id, b])));
  const lang = $derived((locale.code, blockLanguage()));
  const nameOf = (id: string): string => (source ? blockName(source.languages, id, lang) : id);
  const inPalette = $derived(new Set(palette));
  const found = $derived(source && mode === 'palette' ? search(source.index, query) : []);

  const result = $derived.by(() => {
    if (!source) return null;
    const plain = (b: AiBlock): AiBlock => (colors ? b : { id: b.id, item: b.item });
    if (mode === 'palette') {
      const { text, listing } = aiPromptForPalette(source.mcVersion, palette.flatMap((id) => byId.get(id) ?? []).map(plain));
      return { text, listing, tokens: estimateTokens(text) };
    }
    const chosen = new Set(source.mods.map((m) => m.namespace).filter((ns) => !excluded.has(ns)));
    const { text, listing } = aiPromptForBlocks(source.mcVersion, colors ? source.blocks : source.blocks.map(plain), chosen);
    return { text, listing, tokens: estimateTokens(text) };
  });
  const chosen = $derived(packs.find((p) => p.id === packId) ?? null);
  const canCopy = $derived(!!result && !copying && (mode === 'mods' || palette.length > 0));

  function toggleMod(namespace: string, on: boolean) {
    const next = new Set(excluded);
    if (on) next.delete(namespace);
    else next.add(namespace);
    excluded = next;
  }

  function setAllMods(on: boolean) {
    excluded = on ? new Set() : new Set(source?.mods.map((m) => m.namespace));
  }

  function toggleBlock(id: string, on: boolean) {
    palette = on ? (inPalette.has(id) ? palette : [...palette, id]) : palette.filter((p) => p !== id);
  }

  function addShown() {
    palette = [...palette, ...found.slice(0, SHOWN).filter((id) => !inPalette.has(id))];
  }

  async function copy() {
    if (!result || !source) return;
    copying = true;
    try {
      await navigator.clipboard.writeText(result.text);
      await rememberAiChoice(source.packId, { mode, excluded: [...excluded], palette: [...palette], colors });
      onclose(source.packId ? 'projects.ai.promptCopied' : 'projects.ai.promptCopiedNoPack');
    } catch (e) {
      onclose(null, e);
    }
  }
</script>

{#snippet swatch(id: string)}
  {@const block = byId.get(id)}
  <span class={['swatch', { none: !block?.color }]} style:background={block?.color ?? null} title={block?.color ?? ''}></span>
{/snippet}

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
    {:else}
      <div class="modes" role="group" aria-label={t('projects.ai.mode')}>
        <button type="button" class:on={mode === 'mods'} aria-pressed={mode === 'mods'} onclick={() => (mode = 'mods')}>{t('projects.ai.mode.mods')}</button>
        <button type="button" class:on={mode === 'palette'} aria-pressed={mode === 'palette'} onclick={() => (mode = 'palette')}>{t('projects.ai.mode.palette')}</button>
      </div>
      <p class="hint">{t(mode === 'mods' ? 'projects.ai.mode.modsHelp' : 'projects.ai.mode.paletteHelp')}</p>
      <label class="check">
        <input type="checkbox" bind:checked={colors} />
        <span>{t('projects.ai.colors')}</span>
      </label>

      {#if mode === 'mods'}
        {#if !source.mods.length}
          <p class="hint">{t('projects.ai.noModded')}</p>
        {:else}
          <div class="list-head">
            <span id="ai-mods">{t('projects.ai.mods')}</span>
            <span class="spacer"></span>
            <button class="btn light small" type="button" onclick={() => setAllMods(true)}>{t('projects.ai.all')}</button>
            <button class="btn light small" type="button" onclick={() => setAllMods(false)}>{t('projects.ai.none')}</button>
          </div>
          <ul class="rows" aria-labelledby="ai-mods">
            {#each source.mods as mod (mod.namespace)}
              <li>
                <label>
                  <input type="checkbox" checked={!excluded.has(mod.namespace)} onchange={(e) => toggleMod(mod.namespace, e.currentTarget.checked)} />
                  <span class="name">{mod.name ?? mod.namespace}{#if mod.name && mod.name !== mod.namespace}<span class="sub">{mod.namespace}</span>{/if}</span>
                  <span class="sub">{t('projects.ai.modBlocks', { count: mod.blocks })}</span>
                </label>
              </li>
            {/each}
          </ul>
        {/if}
      {:else}
        <div class="list-head">
          <input class="input search" type="search" bind:value={query} placeholder={t('editor.searchBlocks')} aria-label={t('editor.searchBlocks')} />
          <button class="btn light small" type="button" disabled={!found.length} onclick={addShown}>{t('projects.ai.addShown')}</button>
        </div>
        <ul class="rows" aria-label={t('projects.ai.results')}>
          {#each found.slice(0, SHOWN) as id (id)}
            <li>
              <label>
                <input type="checkbox" checked={inPalette.has(id)} onchange={(e) => toggleBlock(id, e.currentTarget.checked)} />
                {@render swatch(id)}
                <span class="name">{nameOf(id)}<span class="sub">{id}</span></span>
              </label>
            </li>
          {/each}
        </ul>
        {#if found.length > SHOWN}<p class="hint">{t('projects.ai.moreResults', { count: found.length - SHOWN })}</p>{/if}

        <div class="list-head">
          <span>{t('projects.ai.paletteCount', { count: palette.length })}</span>
          <span class="spacer"></span>
          <button class="btn light small" type="button" disabled={!palette.length} onclick={() => (palette = [])}>{t('projects.ai.clear')}</button>
        </div>
        {#if palette.length}
          <ul class="chips" aria-label={t('projects.ai.mode.palette')}>
            {#each palette as id (id)}
              <li>
                {@render swatch(id)}
                <span>{nameOf(id)}</span>
                <button type="button" class="remove" aria-label="{t('projects.ai.remove')}: {nameOf(id)}" onclick={() => toggleBlock(id, false)}>×</button>
              </li>
            {/each}
          </ul>
        {/if}
      {/if}
    {/if}
  {:else}
    <p class="hint">{t('projects.ai.noPack')}</p>
  {/if}

  {#if result && source?.packId}
    <p class="hint" aria-live="polite">
      {#if result.listing.listed}
        {t(mode === 'palette' ? 'projects.ai.sizePalette' : 'projects.ai.size', { blocks: result.listing.listed, chars: result.text.length, tokens: result.tokens })}
      {:else if mode === 'palette'}
        {t('projects.ai.paletteEmpty')}
      {:else if source.mods.length}
        {t('projects.ai.noneChosen')}
      {/if}
    </p>
    {#if result.tokens > AI_HUGE_TOKENS}
      <p class="warn huge" role="alert">{t('projects.ai.warnHuge')}</p>
    {:else if result.tokens > AI_LARGE_TOKENS}
      <p class="warn" role="alert">{t('projects.ai.warnLarge')}</p>
    {/if}
  {/if}

  <div class="buttons">
    <button class="btn light" type="button" onclick={() => onclose(null)}>{t('packs.cancel')}</button>
    <button class="btn primary" type="button" disabled={!canCopy} onclick={copy}>{t('projects.ai.copy')}</button>
  </div>
</dialog>

<style>
  dialog {
    width: min(600px, calc(100vw - 32px));
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

  .modes {
    display: flex;
    margin-top: 12px;
    border: 2px solid var(--outline);
  }

  .modes button {
    flex: 1;
    padding: 3px 10px;
    border: 0;
    background: var(--panel-input);
    color: var(--text);
    cursor: pointer;
  }

  .modes button.on {
    background: var(--accent);
    color: var(--on-accent);
  }

  .check {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-top: 8px;
  }

  .list-head {
    display: flex;
    align-items: center;
    gap: 6px;
    margin-top: 12px;
  }

  .search {
    flex: 1;
    min-width: 0;
  }

  .spacer {
    flex: 1;
  }

  .btn.small {
    padding: 2px 8px;
    font-size: 0.85rem;
  }

  .rows {
    list-style: none;
    margin: 6px 0 0;
    padding: 4px 0;
    max-height: min(240px, 32vh);
    overflow-y: auto;
    border: 2px solid var(--panel-border);
  }

  .rows label {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 2px 8px;
  }

  .rows .name {
    flex: 1;
    min-width: 0;
    overflow-wrap: anywhere;
  }

  .sub {
    color: var(--text-muted);
    font-size: 0.85rem;
    margin-left: 0.5em;
  }

  .swatch {
    flex: none;
    width: 14px;
    height: 14px;
    border: 1px solid var(--panel-border);
  }

  .swatch.none {
    background: repeating-linear-gradient(45deg, transparent 0 3px, var(--panel-border) 3px 4px);
  }

  .chips {
    list-style: none;
    margin: 6px 0 0;
    padding: 0;
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
    max-height: 120px;
    overflow-y: auto;
  }

  .chips li {
    display: flex;
    align-items: center;
    gap: 4px;
    padding: 1px 2px 1px 6px;
    border: 1px solid var(--panel-border);
    font-size: 0.85rem;
  }

  .remove {
    border: 0;
    background: none;
    color: inherit;
    cursor: pointer;
    padding: 0 4px;
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
