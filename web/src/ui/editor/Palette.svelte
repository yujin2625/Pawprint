<script lang="ts">
  import { blockLanguage, locale, t } from '../../i18n/i18n.svelte';
  import { blockName, type LoadedPack } from '../../core/pack/pawpack';
  import { buildIndex, search } from '../../core/search/blockSearch';
  import { formatState, parseState } from '../../core/model/state';
  import type { BlockResources } from '../../render/resources';
  import type { BlockDef } from '../../core/pack/types';
  import { editor } from './editor.svelte';

  let { pack, resources }: { pack: LoadedPack | null; resources: BlockResources } = $props();

  const MAX_SHOWN = 240;
  let query = $state('');
  let tab = $state<string | null>(null);
  let manualId = $state('');

  const blocks = $derived(new Map<string, BlockDef>(pack ? pack.blocks.map((b) => [b.id, b]) : []));
  const index = $derived(pack ? buildIndex(pack.blocks.filter((b) => (b.renderShape !== 'invisible' || pack.icons?.icons[b.id] !== undefined) && !b.id.endsWith(':air')), pack.languages) : []);
  const tabs = $derived.by(() => {
    const seen = new Map<string, number>();
    for (const b of pack?.blocks ?? []) for (const id of b.tabs) seen.set(id, (seen.get(id) ?? 0) + 1);
    return [...seen.entries()].filter(([, count]) => count >= 4).map(([id]) => id);
  });
  const results = $derived.by(() => {
    let ids = search(index, query);
    if (tab) ids = ids.filter((id) => blocks.get(id)?.tabs.includes(tab!));
    if (!query.trim()) ids.sort((a, b) => (blocks.get(a)?.order ?? 1e9) - (blocks.get(b)?.order ?? 1e9));
    return ids.slice(0, MAX_SHOWN);
  });

  const selected = $derived(parseState(editor.block));
  const selectedDef = $derived(blocks.get(selected.id));
  const lang = $derived((locale.code, blockLanguage()));
  const nameOf = (id: string) => (pack ? blockName(pack.languages, id, lang) : id);

  function choose(id: string) {
    const def = blocks.get(id);
    editor.block = formatState(id, def ? { ...def.default } : {});
    if (editor.tool === 'eraser' || editor.tool === 'picker') editor.tool = 'pencil';
  }

  function setProperty(name: string, value: string) {
    const props = { ...(selectedDef?.default ?? {}), ...selected.props, [name]: value };
    editor.block = formatState(selected.id, props);
  }

  function tabLabel(id: string): string {
    const path = id.split(':')[1] ?? id;
    return path.replace(/_/g, ' ');
  }

  /** Draws a block's front view into a tile once its textures are loaded. */
  function blockIcon(node: HTMLCanvasElement, state: string) {
    let current = state;
    const draw = async (s: string) => {
      current = s;
      await resources.prepare([s]);
      if (current !== s) return;
      const ctx = node.getContext('2d')!;
      ctx.imageSmoothingEnabled = false;
      ctx.clearRect(0, 0, node.width, node.height);
      ctx.drawImage(resources.icon(s, 'z'), 0, 0, node.width, node.height);
    };
    void draw(state);
    return { update: (s: string) => void draw(s) };
  }
</script>

<section class="palette" aria-label={t('editor.palette')}>
  <div class="head">
    <h2>{t('editor.palette')}</h2>
    {#if pack}<span class="muted">{t('packs.blocks', { count: pack.blocks.length })}</span>{/if}
  </div>

  {#if pack}
    <label class="visually-hidden" for="block-search">{t('editor.searchBlocks')}</label>
    <input id="block-search" class="input" type="search" placeholder={t('editor.searchBlocks')} bind:value={query} />
    {#if tabs.length}
      <label class="tab">
        <span class="visually-hidden">{t('editor.category')}</span>
        <select class="input" value={tab ?? ''} onchange={(e) => (tab = e.currentTarget.value || null)}>
          <option value="">{t('editor.allBlocks')}</option>
          {#each tabs as id (id)}<option value={id}>{tabLabel(id)}</option>{/each}
        </select>
      </label>
    {/if}
    <div class="grid" role="listbox" aria-label={t('editor.palette')}>
      {#each results as id (id)}
        {@const def = blocks.get(id)}
        <button type="button" role="option" aria-selected={selected.id === id} class:on={selected.id === id} title={nameOf(id) + '\n' + id} onclick={() => choose(id)}>
          <canvas width="32" height="32" use:blockIcon={def ? formatState(id, def.default) : id}></canvas>
        </button>
      {/each}
    </div>
  {:else}
    <p class="muted">{t('editor.noPackPalette')}</p>
    <form onsubmit={(e) => (e.preventDefault(), manualId.trim() && (editor.block = manualId.trim()))}>
      <input class="input" placeholder="minecraft:stone" bind:value={manualId} aria-label={t('editor.blockId')} />
      <button class="btn light" type="submit">{t('editor.use')}</button>
    </form>
  {/if}

  <div class="selected">
    <div class="name">
      <span>{nameOf(selected.id)}</span>
      <span class="id">{selected.id}</span>
    </div>
    {#if selectedDef}
      {#each Object.entries(selectedDef.properties) as [prop, values] (prop)}
        <div class="prop">
          <span class="muted">{prop}</span>
          {#if values.length <= 6}
            <div class="choices">
              {#each values as value (value)}
                <button type="button" class:on={(selected.props[prop] ?? selectedDef.default[prop]) === value} onclick={() => setProperty(prop, value)}>{value}</button>
              {/each}
            </div>
          {:else}
            <select class="input" value={selected.props[prop] ?? selectedDef.default[prop]} onchange={(e) => setProperty(prop, e.currentTarget.value)}>
              {#each values as value (value)}<option {value}>{value}</option>{/each}
            </select>
          {/if}
        </div>
      {/each}
    {/if}
  </div>
</section>

<style>
  .palette {
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding: 10px 12px;
    min-height: 0;
  }

  .head {
    display: flex;
    justify-content: space-between;
    align-items: baseline;
  }

  .muted {
    color: var(--text-muted);
  }

  .tab select {
    width: 100%;
  }

  .choices button {
    padding: 0 7px;
    border: 1px solid var(--panel-border);
    background: transparent;
    color: var(--text);
    cursor: pointer;
  }

  .choices button.on {
    background: var(--accent);
    border-color: var(--accent);
    color: var(--on-accent);
  }

  .grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(40px, 1fr));
    gap: 3px;
    overflow-y: auto;
    min-height: 120px;
    flex: 1;
    align-content: start;
  }

  .grid button {
    height: 40px;
    padding: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    background: var(--panel-input);
    border: 2px solid var(--panel-border);
    cursor: pointer;
  }

  .grid button.on {
    border-color: var(--accent);
    background: var(--accent-soft);
  }

  .grid canvas {
    width: 32px;
    height: 32px;
    image-rendering: pixelated;
  }

  .selected {
    display: flex;
    flex-direction: column;
    gap: 6px;
    padding: 8px 10px;
    background: var(--panel-2);
  }

  .name {
    display: flex;
    flex-direction: column;
  }

  .id {
    color: var(--text-muted);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .prop {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  .choices {
    display: flex;
    flex-wrap: wrap;
    gap: 3px;
  }

  form {
    display: flex;
    gap: 6px;
  }

  form .input {
    flex: 1;
    min-width: 0;
  }
</style>
