<script lang="ts">
  import { t } from '../../i18n/i18n.svelte';
  import type { EditableBlueprint } from '../../core/blueprint/editable';
  import { addLayer, mergeInto, move, remove, rows, update } from '../../core/blueprint/layers';
  import ConfirmDialog from '../ConfirmDialog.svelte';
  import { editor } from './editor.svelte';
  import PixelIcon from './PixelIcon.svelte';

  let { blueprint, revision }: { blueprint: EditableBlueprint; revision: number } = $props();

  let selected = $state<number>(editor.currentLayer);
  let renaming = $state<number | null>(null);
  let draftName = $state('');
  let dragging = $state<number | null>(null);
  let dropTarget = $state<{ id: number; into: boolean } | null>(null);
  let confirmDelete = $state<number | null>(null);

  /** Rows with their block counts (a group counts everything inside it). */
  const list = $derived.by(() => {
    void revision;
    const counts = blueprint.layerCounts();
    const byId = new Map(blueprint.layers.map((l) => [l.id, l]));
    const total = (id: number): number => {
      const layer = byId.get(id);
      if (!layer?.group) return counts.get(id) ?? 0;
      return (layer.children ?? []).reduce((sum, child) => sum + total(child), 0);
    };
    return rows(blueprint).map((row) => ({ ...row, count: total(row.layer.id) }));
  });
  const selectedLayer = $derived(list.find((r) => r.layer.id === selected)?.layer ?? null);

  function choose(id: number, group: boolean) {
    selected = id;
    if (!group) {
      blueprint.currentLayer = id;
      editor.currentLayer = id;
    }
  }

  function add(group: boolean) {
    const id = addLayer(blueprint, group ? t('layers.newGroup') : t('layers.newLayer'), selected, group);
    choose(id, group);
    startRename(id);
  }

  function startRename(id: number) {
    renaming = id;
    draftName = blueprint.layers.find((l) => l.id === id)?.name ?? '';
  }

  function finishRename() {
    if (renaming !== null && draftName.trim()) update(blueprint, renaming, { name: draftName.trim() });
    renaming = null;
  }

  /** Merges the selected layer into the next layer below it (same level). */
  function mergeDown() {
    const index = list.findIndex((r) => r.layer.id === selected);
    const below = list.slice(index + 1).find((r) => !r.layer.group && r.layer.id !== selected);
    if (below && selected !== 0) {
      mergeInto(blueprint, selected, below.layer.id);
      choose(below.layer.id, false);
    }
  }

  function askDelete() {
    if (selected !== 0) confirmDelete = selected;
  }

  function answerDelete(yes: boolean) {
    const id = confirmDelete;
    confirmDelete = null;
    if (yes && id !== null) {
      remove(blueprint, id);
      choose(0, false);
    }
  }

  function onDragOver(e: DragEvent, id: number, group: boolean) {
    if (dragging === null || dragging === id) return;
    e.preventDefault();
    const box = (e.currentTarget as HTMLElement).getBoundingClientRect();
    const middle = e.clientY > box.top + box.height * 0.3 && e.clientY < box.bottom - box.height * 0.3;
    dropTarget = { id, into: group && middle };
  }

  function onDrop(e: DragEvent) {
    e.preventDefault();
    if (dragging !== null && dropTarget) move(blueprint, dragging, dropTarget.id, dropTarget.into);
    dragging = null;
    dropTarget = null;
  }

  const deletingName = $derived(confirmDelete === null ? '' : blueprint.layers.find((l) => l.id === confirmDelete)?.name ?? '');
</script>

<section class="layers" aria-label={t('layers.title')}>
  <div class="actions">
    <button class="btn light" type="button" title={t('layers.add')} onclick={() => add(false)}>+ {t('layers.layer')}</button>
    <button class="btn light" type="button" title={t('layers.addGroup')} onclick={() => add(true)}>+ {t('layers.group')}</button>
    <span class="spacer"></span>
    <button class="btn light" type="button" title={t('layers.mergeDown')} disabled={selected === 0 || selectedLayer?.group} onclick={mergeDown}>{t('layers.merge')}</button>
    <button class="btn danger" type="button" title={t('layers.delete')} disabled={selected === 0} onclick={askDelete}>{t('packs.delete')}</button>
  </div>

  <ul class="rows" role="tree" aria-label={t('layers.title')}>
    {#each list as row (row.layer.id)}
      {@const l = row.layer}
      <li
        role="treeitem"
        aria-selected={selected === l.id}
        aria-expanded={l.group ? !l.collapsed : undefined}
        class={['row', { on: selected === l.id, current: !l.group && editor.currentLayer === l.id, dim: row.hiddenByTree, before: dropTarget?.id === l.id && !dropTarget.into, into: dropTarget?.id === l.id && dropTarget.into }]}
        style:padding-left="{6 + row.depth * 16}px"
        draggable={l.id !== 0 && renaming !== l.id}
        ondragstart={() => (dragging = l.id)}
        ondragend={() => ((dragging = null), (dropTarget = null))}
        ondragover={(e) => onDragOver(e, l.id, !!l.group)}
        ondrop={onDrop}
      >
        <button type="button" class="icon" aria-label={t('layers.visible')} aria-pressed={l.visible} title={t('layers.visible')} onclick={() => update(blueprint, l.id, { visible: !l.visible })}><PixelIcon name={l.visible ? 'eye' : 'eyeOff'} size={16} /></button>
        <button type="button" class="icon" aria-label={t('layers.locked')} aria-pressed={l.locked} title={t('layers.locked')} onclick={() => update(blueprint, l.id, { locked: !l.locked })}><PixelIcon name={l.locked ? 'lock' : 'unlock'} size={16} /></button>
        <label class="swatch" style:background={l.color} title={t('layers.color')}>
          <input type="color" value={l.color} onchange={(e) => update(blueprint, l.id, { color: e.currentTarget.value.toUpperCase() })} aria-label={t('layers.color')} />
        </label>
        {#if l.group}
          <button type="button" class="icon" aria-label={t('layers.toggleGroup')} onclick={() => update(blueprint, l.id, { collapsed: !l.collapsed })}><PixelIcon name={l.collapsed ? 'closed' : 'open'} size={16} /></button>
        {/if}
        {#if renaming === l.id}
          <!-- svelte-ignore a11y_autofocus -->
          <input class="input name-edit" bind:value={draftName} autofocus onblur={finishRename} onkeydown={(e) => e.key === 'Enter' ? finishRename() : e.key === 'Escape' && (renaming = null)} aria-label={t('layers.rename')} />
        {:else}
          <button type="button" class="name" onclick={() => choose(l.id, !!l.group)} ondblclick={() => startRename(l.id)} title={t('layers.renameHint')}>{l.name}</button>
        {/if}
        {#if !l.group}
          <button type="button" class={['icon', 'solo', { active: editor.solo === l.id }]} aria-pressed={editor.solo === l.id} title={t('layers.solo')} onclick={() => (editor.solo = editor.solo === l.id ? null : l.id)}>S</button>
        {/if}
        <span class="count">{row.count}</span>
      </li>
    {/each}
  </ul>
  <p class="muted">{t('layers.help')}</p>
</section>

{#if confirmDelete !== null}
  <ConfirmDialog message={t('layers.confirmDelete', { name: deletingName })} confirmLabel={t('packs.delete')} danger onanswer={answerDelete} />
{/if}

<style>
  .layers {
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding: 10px 12px;
    min-height: 0;
    flex: 1;
  }

  .actions {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
  }

  .actions .btn {
    padding: 3px 8px;
  }

  .spacer {
    flex: 1;
  }

  .rows {
    list-style: none;
    margin: 0;
    padding: 0;
    overflow-y: auto;
    flex: 1;
  }

  .row {
    display: flex;
    align-items: center;
    gap: 4px;
    height: 28px;
    padding-right: 6px;
    border-top: 2px solid transparent;
    border-bottom: 2px solid transparent;
  }

  .row.on {
    background: var(--panel-2);
  }

  .row.current {
    background: var(--accent-soft);
  }

  .row.dim {
    opacity: 0.55;
  }

  .row.before {
    border-top-color: var(--accent);
  }

  .row.into {
    outline: 2px solid var(--accent);
    outline-offset: -2px;
  }

  .icon {
    width: 22px;
    height: 22px;
    padding: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    flex: none;
    border: 0;
    background: transparent;
    color: var(--text);
    cursor: pointer;
  }

  .solo {
    color: var(--text-muted);
  }

  .solo.active {
    background: var(--accent);
    color: var(--on-accent);
  }

  .swatch {
    width: 8px;
    height: 18px;
    cursor: pointer;
    flex: none;
  }

  .swatch input {
    width: 0;
    height: 0;
    padding: 0;
    border: 0;
    opacity: 0;
  }

  .name {
    flex: 1;
    min-width: 0;
    padding: 0;
    border: 0;
    background: transparent;
    color: var(--text);
    text-align: left;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    cursor: pointer;
  }

  .name-edit {
    flex: 1;
    min-width: 0;
    padding: 0 4px;
  }

  .count {
    min-width: 28px;
    text-align: right;
    color: var(--text-muted);
  }

  .muted {
    color: var(--text-muted);
  }
</style>
