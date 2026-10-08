<script lang="ts">
  import { blockLanguage, locale, t } from '../../i18n/i18n.svelte';
  import { blockName, type LoadedPack } from '../../core/pack/pawpack';
  import type { EditableBlueprint } from '../../core/blueprint/editable';
  import { countMaterials, stacks, type MaterialLine } from '../../core/materials/materials';
  import type { BlockDef } from '../../core/pack/types';
  import { editor } from './editor.svelte';

  let { blueprint, pack, revision }: { blueprint: EditableBlueprint; pack: LoadedPack | null; revision: number } = $props();

  let scope = $state<'all' | 'layer' | 'selection'>('all');
  let copied = $state(false);

  const blocks = $derived(pack ? new Map<string, BlockDef>(pack.blocks.map((b) => [b.id, b])) : null);
  const lang = $derived((locale.code, blockLanguage()));
  const nameOf = (id: string) => (pack ? blockName(pack.languages, id, lang) : id);

  const result = $derived.by(() => {
    void revision;
    const layer = editor.currentLayer;
    const box = editor.selection;
    return countMaterials(blueprint, (x, y, z, l) => {
      if (scope === 'layer') return l === layer;
      if (scope === 'selection') return !!box && x >= box.min[0] && x <= box.max[0] && y >= box.min[1] && y <= box.max[1] && z >= box.min[2] && z <= box.max[2];
      return true;
    }, blocks);
  });

  const layerName = $derived(blueprint.layers.find((l) => l.id === editor.currentLayer)?.name ?? '');

  function amount(count: number): string {
    const s = stacks(count);
    return s.stacks ? t('materials.stacks', { stacks: s.stacks, rest: s.rest }) : '';
  }

  function line(m: MaterialLine): string {
    const extra = amount(m.count);
    return `${nameOf(m.id)} (${m.id}): ${t('materials.count', { count: m.count })}${extra ? ' — ' + extra : ''}`;
  }

  async function copyText() {
    const title = blueprint.meta.name + (scope === 'layer' ? ` · ${layerName}` : scope === 'selection' ? ` · ${t('editor.selection')}` : '');
    const parts = [title, ...result.lines.map(line)];
    if (result.noItem.length) parts.push('', t('materials.noItem'), ...result.noItem.map(line));
    await navigator.clipboard.writeText(parts.join('\n'));
    copied = true;
    setTimeout(() => (copied = false), 2000);
  }
</script>

<section class="materials" aria-label={t('materials.title')}>
  <div class="scope" role="group" aria-label={t('materials.scope')}>
    <button type="button" class:on={scope === 'all'} onclick={() => (scope = 'all')}>{t('materials.all')}</button>
    <button type="button" class:on={scope === 'layer'} onclick={() => (scope = 'layer')} title={layerName}>{t('materials.layer')}</button>
    <button type="button" class:on={scope === 'selection'} disabled={!editor.selection} onclick={() => (scope = 'selection')}>{t('materials.selection')}</button>
  </div>
  {#if scope === 'layer'}<p class="muted">{t('materials.ofLayer', { name: layerName })}</p>{/if}
  <ul class="list">
    {#each result.lines as m (m.id)}
      <li>
        <span class="name" title={m.id}>{nameOf(m.id)}</span>
        <span class="muted">{amount(m.count)}</span>
        <span class="count">{m.count}</span>
      </li>
    {:else}
      <li class="muted">{t('materials.empty')}</li>
    {/each}
  </ul>
  {#if result.noItem.length}
    <p class="muted">{t('materials.noItem')}</p>
    <ul class="list small">
      {#each result.noItem as m (m.id)}
        <li><span class="name" title={m.id}>{nameOf(m.id)}</span><span class="count">{m.count}</span></li>
      {/each}
    </ul>
  {/if}
  <div class="foot">
    <span class="muted">{t('materials.total', { count: result.total })}</span>
    <button class="btn light" type="button" disabled={!result.lines.length} onclick={copyText}>{copied ? t('materials.copied') : t('materials.copy')}</button>
  </div>
</section>

<style>
  .materials {
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding: 10px 12px;
    min-height: 0;
    flex: 1;
  }

  .scope {
    display: flex;
    border: 2px solid var(--panel-border);
  }

  .scope button {
    flex: 1;
    padding: 2px 0;
    border: 0;
    background: transparent;
    color: var(--text);
    cursor: pointer;
  }

  .scope button.on {
    background: var(--text);
    color: var(--panel);
  }

  .scope button:disabled {
    opacity: 0.45;
    cursor: default;
  }

  .list {
    list-style: none;
    margin: 0;
    padding: 0;
    overflow-y: auto;
    flex: 1;
  }

  .list.small {
    flex: none;
    max-height: 120px;
  }

  li {
    display: flex;
    gap: 8px;
    padding: 2px 0;
    border-bottom: 1px dashed var(--panel-border);
  }

  .name {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .count {
    min-width: 36px;
    text-align: right;
  }

  .muted {
    color: var(--text-muted);
  }

  .foot {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 8px;
  }
</style>
