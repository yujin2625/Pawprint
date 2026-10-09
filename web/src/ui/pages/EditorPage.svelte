<script lang="ts">
  import type { DockviewApi } from 'dockview-core';
  import { blockLanguage, locale, t } from '../../i18n/i18n.svelte';
  import { blockName } from '../../core/pack/pawpack';
  import { parseState } from '../../core/model/state';
  import { BlockResources } from '../../render/resources';
  import { SOLID_SHAPES, type Tool } from '../../core/edit/tools';
  import { blocksIn, boxSize, clearBox, copy, fillBox, mirrorClip, replaceInBox, rotateClip, type Box } from '../../core/edit/clip';
  import { hiddenLayers, moveCellsTo, protectedLayers, rows } from '../../core/blueprint/layers';
  import { getSetting, setSetting } from '../../storage/db';
  import { loadDefaultPack } from '../packs/activePack';
  import { exportFile, loadProject, saveProject, saveStamp } from '../projects';
  import { isDesktop, saveBytes } from '../../platform/platform';
  import { editor, TOOL_KEYS } from '../editor/editor.svelte';
  import { ctx, activeSlice, viewports } from '../editor/context.svelte';
  import { PANELS } from '../panels';
  import {
    applyBuiltIn, BUILT_IN, fromFile, isUsable, loadCurrent, loadPresets, openPanel, retitle, saveCurrent, savePresets, toFile,
    type BuiltIn, type SavedLayout,
  } from '../editor/layouts';
  import Dock from '../editor/Dock.svelte';
  import Menu from '../Menu.svelte';
  import PixelIcon from '../editor/PixelIcon.svelte';

  let { projectId }: { projectId: string } = $props();

  let failed = $state<string | null>(null);
  let saveState = $state<'saved' | 'saving' | 'unsaved'>('saved');
  let name = $state('');
  let moveTo = $state<number>(0);
  let replaceFrom = $state('');
  let saveTimer: ReturnType<typeof setTimeout> | undefined;
  let dock = $state.raw<DockviewApi | null>(null);
  let presets = $state<SavedLayout[]>([]);
  let locked = $state(false);
  let presetName = $state('');
  let layoutInput: HTMLInputElement | undefined = $state();
  /** Set while the desktop window closes: panel windows close with it, and that must not end up in the saved layout. */
  let closing = false;

  const TOOLS: ({ id: Tool; key: string } | null)[] = [
    { id: 'select', key: 'V' }, null,
    { id: 'pencil', key: 'B' }, { id: 'eraser', key: 'E' }, { id: 'fill', key: 'G' }, { id: 'picker', key: 'I' }, null,
    { id: 'line', key: 'L' }, { id: 'rect', key: 'R' }, { id: 'ellipse', key: 'O' }, null,
    { id: 'box', key: 'U' }, { id: 'hollow', key: '' }, { id: 'wall', key: 'K' }, { id: 'sphere', key: '' }, { id: 'cylinder', key: '' }, null,
    { id: 'stamp', key: 'S' },
  ];

  // Load the pack, then the project. Panels pick everything up from the shared context.
  $effect(() => {
    const id = projectId;
    let cancelled = false;
    ctx.projectId = id;
    (async () => {
      try {
        const p = await loadDefaultPack().catch(() => null);
        const loaded = await loadProject(id);
        if (cancelled) return;
        if (!loaded) {
          failed = 'editor.notFound';
          return;
        }
        name = loaded.blueprint.meta.name;
        editor.solo = null;
        editor.selection = null;
        editor.currentLayer = loaded.blueprint.currentLayer = 0;
        ctx.pack = p;
        ctx.resources = new BlockResources(p, 4096);
        ctx.blueprint = loaded.blueprint;
        if (import.meta.env.DEV) (window as unknown as { __blueprint?: unknown }).__blueprint = loaded.blueprint;
      } catch (e) {
        failed = 'editor.loadFailed';
        console.error(e);
      }
    })();
    return () => {
      cancelled = true;
      ctx.blueprint = null;
      ctx.resources?.dispose();
      ctx.resources = null;
      ctx.stats = null;
    };
  });

  // Autosave a moment after edits; also keeps the undo/redo buttons and panels current.
  $effect(() => {
    const bp = ctx.blueprint;
    if (!bp) return;
    const off = bp.onChange(() => {
      ctx.revision++;
      saveState = 'unsaved';
      clearTimeout(saveTimer);
      saveTimer = setTimeout(save, 2000);
    });
    return () => {
      off();
      clearTimeout(saveTimer);
      if (saveState === 'unsaved') void save();
    };
  });

  // Hidden and locked layers: what is drawn and what may be edited.
  $effect(() => {
    const bp = ctx.blueprint;
    void ctx.revision;
    const solo = editor.solo;
    if (!bp) return;
    if (solo !== null && !bp.layers.some((l) => l.id === solo)) {
      editor.solo = null;
      return;
    }
    const hidden = hiddenLayers(bp, solo);
    bp.protectedLayers = new Set([...protectedLayers(bp), ...hidden]);
    editor.hiddenLayers = hidden;
    if (!bp.layers.some((l) => l.id === editor.currentLayer)) editor.currentLayer = bp.currentLayer = 0;
  });

  $effect(() => {
    if (!editor.message) return;
    const timer = setTimeout(() => (editor.message = null), 4000);
    return () => clearTimeout(timer);
  });

  // Layout: restore the last one (or the default), save every change, follow the language.
  async function ready(api: DockviewApi) {
    dock = api;
    if (import.meta.env.DEV) (window as unknown as { __dock?: DockviewApi }).__dock = api;
    presets = await loadPresets();
    locked = (await getSetting<boolean>('layout.locked')) ?? false;
    const saved = await loadCurrent();
    try {
      if (saved && isUsable(saved)) api.fromJSON(saved);
      else applyBuiltIn(api, 'default');
    } catch (e) {
      console.warn('Saved layout could not be restored', e);
      applyBuiltIn(api, 'default');
    }
    if (api.panels.length === 0) applyBuiltIn(api, 'default');
    let timer: ReturnType<typeof setTimeout> | undefined;
    api.onDidLayoutChange(() => {
      clearTimeout(timer);
      timer = setTimeout(() => closing || void saveCurrent(api.toJSON()), 400);
    });
  }

  $effect(() => {
    void locale.code;
    if (dock) retitle(dock);
  });

  function shortcut(preset: BuiltIn | SavedLayout): string {
    const i = layoutKeys.indexOf(preset);
    return i >= 0 && i < 9 ? 'Alt+' + (i + 1) : '';
  }

  function usePreset(preset: BuiltIn | SavedLayout, close: () => void) {
    if (!dock) return;
    if (typeof preset === 'string') applyBuiltIn(dock, preset);
    else if (isUsable(preset.layout)) dock.fromJSON(preset.layout);
    retitle(dock);
    close();
  }

  async function savePreset() {
    if (!dock || !presetName.trim()) return;
    const entry = { name: presetName.trim(), layout: dock.toJSON() };
    presets = [...presets.filter((p) => p.name !== entry.name), entry];
    await savePresets(presets);
    presetName = '';
    editor.message = 'layout.saved';
  }

  async function deletePreset(preset: SavedLayout) {
    presets = presets.filter((p) => p !== preset);
    await savePresets(presets);
  }

  async function renamePreset(preset: SavedLayout) {
    const next = prompt(t('layout.renamePrompt'), preset.name)?.trim();
    if (!next) return;
    presets = presets.map((p) => (p === preset ? { ...p, name: next } : p));
    await savePresets(presets);
  }

  async function exportLayout() {
    if (!dock) return;
    const bytes = new Uint8Array(await toFile({ name: t('layout.mine'), layout: dock.toJSON() }).arrayBuffer());
    await saveBytes('pawprint-layout.json', bytes, { name: 'JSON', extensions: ['json'] }, 'application/json');
  }

  /** Desktop: the active panel's group moves to its own window. */
  function popOut() {
    const group = dock?.activePanel?.group;
    if (!dock || !group || group.api.location.type === 'popout') return;
    void dock.addPopoutGroup(group);
  }

  // Desktop: before the window closes, keep the layout as it is (with its panel windows) and save the blueprint.
  $effect(() => {
    if (!isDesktop) return;
    let off: (() => void) | null = null;
    let gone = false;
    void import('@tauri-apps/api/window').then(async ({ getCurrentWindow }) => {
      const unlisten = await getCurrentWindow().onCloseRequested(async () => {
        closing = true;
        if (dock) await saveCurrent(dock.toJSON());
        if (saveState !== 'saved') await save();
      });
      if (gone) unlisten();
      else off = unlisten;
    });
    return () => {
      gone = true;
      off?.();
    };
  });

  async function importLayout() {
    const file = layoutInput?.files?.[0];
    if (layoutInput) layoutInput.value = '';
    if (!file || !dock) return;
    try {
      const preset = fromFile(await file.text());
      if (!isUsable(preset.layout)) throw new Error('unknown panels');
      dock.fromJSON(preset.layout);
      retitle(dock);
      presets = [...presets.filter((p) => p.name !== preset.name), preset];
      await savePresets(presets);
      editor.message = 'layout.imported';
    } catch {
      editor.message = 'layout.importFailed';
    }
  }

  async function toggleLock() {
    locked = !locked;
    await setSetting('layout.locked', locked);
  }

  async function save() {
    const bp = ctx.blueprint;
    if (!bp) return;
    saveState = 'saving';
    try {
      const thumbnail = viewports[0] ? await viewports[0].snapshot() : null;
      await saveProject(projectId, bp, thumbnail);
      saveState = 'saved';
    } catch (e) {
      console.error(e);
      saveState = 'unsaved';
      editor.message = 'editor.saveFailed';
    }
  }

  function rename() {
    const bp = ctx.blueprint;
    if (!bp) return;
    bp.meta.name = name.trim() || bp.meta.name;
    name = bp.meta.name;
    saveState = 'unsaved';
    void save();
  }

  /** Runs an edit on the selection as one undo step. */
  function onSelection(label: string, run: (box: Box) => void) {
    const box = editor.selection, bp = ctx.blueprint;
    if (!box || !bp) return;
    bp.begin(label);
    run(box);
    bp.commit();
  }

  function copySelection(cut: boolean) {
    const box = editor.selection, bp = ctx.blueprint;
    if (!box || !bp) return;
    editor.clip = copy(bp, box);
    if (cut) onSelection('cut', (b) => clearBox(bp, b));
    editor.message = cut ? 'editor.cutDone' : 'editor.copyDone';
  }

  async function saveSelectionAsStamp() {
    const box = editor.selection, bp = ctx.blueprint;
    if (!box || !bp) return;
    const clip = copy(bp, box);
    if (!clip.cells.length) return;
    await saveStamp(clip, '★ ' + (bp.meta.name || t('projects.untitled')) + ' ' + boxSize(box).join('×'));
    ctx.stampsKey++;
    editor.message = 'editor.stampSaved';
  }

  function replaceSelection() {
    const bp = ctx.blueprint;
    if (!editor.selection || !bp || !replaceFrom) return;
    const target = parseState(editor.block);
    const def = ctx.pack?.blocks.find((b) => b.id === target.id);
    // Keep properties the new block also has (stairs keep their facing); without a pack keep them all.
    const keep = (prop: string) => !def || prop in def.properties;
    onSelection('replace', (b) => replaceInBox(bp, b, replaceFrom, editor.block, keep));
  }

  const layerChoices = $derived.by(() => {
    void ctx.revision;
    return ctx.blueprint ? rows(ctx.blueprint).filter((r) => !r.layer.group).map((r) => r.layer) : [];
  });
  const currentLayerName = $derived(layerChoices.find((l) => l.id === editor.currentLayer)?.name ?? '');

  function moveSelectionToLayer() {
    const box = editor.selection, bp = ctx.blueprint;
    if (!box || !bp) return;
    const cells: [number, number, number][] = [];
    for (let y = box.min[1]; y <= box.max[1]; y++)
      for (let z = box.min[2]; z <= box.max[2]; z++)
        for (let x = box.min[0]; x <= box.max[0]; x++) if (bp.get(x, y, z) !== 0 && bp.editable(x, y, z)) cells.push([x, y, z]);
    moveCellsTo(bp, cells, moveTo);
  }

  const selectionBlocks = $derived.by(() => {
    void ctx.revision;
    return editor.selection && ctx.blueprint ? blocksIn(ctx.blueprint, editor.selection).slice(0, 30) : [];
  });
  $effect(() => {
    if (selectionBlocks.length && !selectionBlocks.some((b) => b.id === replaceFrom)) replaceFrom = selectionBlocks[0]!.id;
  });

  function stepSlice(delta: number) {
    activeSlice.set?.(activeSlice.slice + delta);
  }

  /** Alt+1… switches layouts: the built-in ones first, then your own, in menu order. */
  const layoutKeys = $derived([...BUILT_IN, ...presets]);

  function onKey(e: KeyboardEvent) {
    const target = e.target as HTMLElement | null;
    if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT' || target.isContentEditable)) return;
    const digit = /^Digit([1-9])$/.exec(e.code);
    if (digit && e.altKey && !e.ctrlKey && !e.metaKey && !e.shiftKey) {
      const preset = layoutKeys[Number(digit[1]) - 1];
      if (preset && !locked) {
        e.preventDefault();
        usePreset(preset, () => {});
      }
      return;
    }
    const key = e.key.toLowerCase();
    const bp = ctx.blueprint;
    // While the right button is held in 3D, letters move the camera.
    if (viewports.some((v) => v.controls.isLooking)) return;
    if (editor.tool === 'stamp' && editor.clip && !e.ctrlKey && !e.metaKey && (key === 'r' || key === 'x' || key === 'z')) {
      e.preventDefault();
      editor.clip = key === 'r' ? rotateClip(editor.clip, e.shiftKey ? 3 : 1) : mirrorClip(editor.clip, key as 'x' | 'z');
      return;
    }
    if ((e.ctrlKey || e.metaKey) && (key === 'c' || key === 'x')) {
      if (editor.selection) {
        e.preventDefault();
        copySelection(key === 'x');
      }
      return;
    }
    if ((e.ctrlKey || e.metaKey) && key === 'v') {
      e.preventDefault();
      if (editor.clip) editor.tool = 'stamp';
      return;
    }
    if (e.key === 'Delete' || e.key === 'Backspace') {
      if (editor.selection && bp) {
        e.preventDefault();
        onSelection('delete', (b) => clearBox(bp, b));
      }
      return;
    }
    if (e.key === 'Escape') {
      editor.selection = null;
      if (editor.tool === 'stamp') editor.tool = 'pencil';
      return;
    }
    if ((e.ctrlKey || e.metaKey) && key === 'z') {
      e.preventDefault();
      if (e.shiftKey) bp?.redo();
      else bp?.undo();
    } else if ((e.ctrlKey || e.metaKey) && key === 'y') {
      e.preventDefault();
      bp?.redo();
    } else if ((e.ctrlKey || e.metaKey) && key === 's') {
      e.preventDefault();
      void save();
    } else if (e.key === 'PageUp' || e.key === ']') {
      e.preventDefault();
      stepSlice(1);
    } else if (e.key === 'PageDown' || e.key === '[') {
      e.preventDefault();
      stepSlice(-1);
    } else if (!e.ctrlKey && !e.metaKey && !e.altKey && TOOL_KEYS[key]) {
      editor.tool = TOOL_KEYS[key]!;
    }
  }

  const lang = $derived((locale.code, blockLanguage()));
  const nameOf = (state: string) => {
    const id = parseState(state).id;
    return ctx.pack ? blockName(ctx.pack.languages, id, lang) : id;
  };
  const canUndo = $derived.by(() => {
    void ctx.revision;
    return ctx.blueprint?.canUndo ?? false;
  });
  const canRedo = $derived.by(() => {
    void ctx.revision;
    return ctx.blueprint?.canRedo ?? false;
  });
  const shapeTool = $derived(editor.tool === 'rect' || editor.tool === 'ellipse' || editor.tool === 'cylinder' || editor.tool === 'sphere');
  const solidTool = $derived(SOLID_SHAPES.includes(editor.tool));
  const brushTool = $derived(editor.tool === 'pencil' || editor.tool === 'eraser' || editor.tool === 'line');
</script>

<svelte:window onkeydown={onKey} />

{#if failed}
  <section class="none panel">
    <p>{t(failed)}</p>
    <a class="btn primary" href="#/projects">{t('nav.projects')}</a>
  </section>
{:else}
  <div class="editor">
    <div class="bar">
      <a href="#/projects">{t('nav.projects')}</a>
      <span class="muted">/</span>
      <input class="title" aria-label={t('editor.name')} bind:value={name} onchange={rename} onkeydown={(e) => e.key === 'Enter' && e.currentTarget.blur()} />
      <span class={['save', saveState]}>{t('editor.save.' + saveState)}</span>
      <span class="spacer"></span>
      <button class="btn icon" type="button" title={t('editor.undo')} aria-label={t('editor.undo')} disabled={!canUndo} onclick={() => ctx.blueprint?.undo()}><PixelIcon name="undo" /></button>
      <button class="btn icon" type="button" title={t('editor.redo')} aria-label={t('editor.redo')} disabled={!canRedo} onclick={() => ctx.blueprint?.redo()}><PixelIcon name="redo" /></button>
      <Menu label={t('window.title')}>
        {#snippet children(close)}
          <button class="item" type="button" onclick={() => (dock && openPanel(dock, 'view3d'), close())}>+ {t('window.add3d')}</button>
          <button class="item" type="button" onclick={() => (dock && openPanel(dock, 'view2d'), close())}>+ {t('window.add2d')}</button>
          <div class="sep"></div>
          {#each PANELS.filter((p) => !p.multiple) as p (p.id)}
            <button class="item" type="button" onclick={() => (dock && openPanel(dock, p.id), close())}>{t(p.title)}</button>
          {/each}
          {#if isDesktop}
            <div class="sep"></div>
            <button class="item" type="button" title={t('window.popoutHelp')} onclick={() => (popOut(), close())}>⧉ {t('window.popout')}</button>
          {/if}
        {/snippet}
      </Menu>
      <Menu label={t('layout.title')} align="right">
        {#snippet children(close)}
          <div class="heading">{t('layout.builtIn')}</div>
          {#each BUILT_IN as preset (preset)}
            <button class="item" type="button" onclick={() => usePreset(preset, close)}>{t('layout.preset.' + preset)}<span class="key">{shortcut(preset)}</span></button>
          {/each}
          {#if presets.length}
            <div class="sep"></div>
            <div class="heading">{t('layout.mine')}</div>
            {#each presets as preset (preset.name)}
              <div class="row">
                <button class="item" type="button" onclick={() => usePreset(preset, close)}>{preset.name}<span class="key">{shortcut(preset)}</span></button>
                <button class="mini" type="button" title={t('packs.rename')} aria-label={t('packs.rename')} onclick={() => renamePreset(preset)}>✎</button>
                <button class="mini" type="button" title={t('packs.delete')} aria-label={t('packs.delete')} onclick={() => deletePreset(preset)}>×</button>
              </div>
            {/each}
          {/if}
          <div class="sep"></div>
          <form class="save-row" onsubmit={(e) => (e.preventDefault(), savePreset())}>
            <input class="input" placeholder={t('layout.namePlaceholder')} aria-label={t('layout.namePlaceholder')} bind:value={presetName} />
            <button class="btn light" type="submit" disabled={!presetName.trim()}>{t('layout.save')}</button>
          </form>
          <button class="item" type="button" onclick={() => (exportLayout(), close())}>{t('layout.export')}</button>
          <button class="item" type="button" onclick={() => (layoutInput?.click(), close())}>{t('layout.import')}</button>
          <button class={['item', { on: locked }]} type="button" aria-pressed={locked} onclick={toggleLock}><PixelIcon name={locked ? 'lock' : 'unlock'} size={16} /> {t('layout.lock')}</button>
          <button class="item" type="button" onclick={() => usePreset('default', close)}>{t('layout.reset')}</button>
        {/snippet}
      </Menu>
      <input bind:this={layoutInput} type="file" accept=".json,application/json" hidden onchange={importLayout} />
      <button class="btn" type="button" onclick={() => viewports[0]?.frame()}>{t('editor.frame')}</button>
      <button class="btn primary" type="button" disabled={!ctx.blueprint} onclick={() => ctx.blueprint && exportFile(ctx.blueprint)}>{t(isDesktop ? 'editor.saveFile' : 'editor.download')}</button>
    </div>

    <div class="options">
      <span class="tool-name">{t('editor.tool.' + editor.tool)}</span>
      {#if brushTool}
        <div class="segmented" role="group" aria-label={t('editor.brush')}>
          {#each ['square', 'circle', 'diamond'] as const as shape (shape)}
            <button type="button" class:on={editor.brushShape === shape} onclick={() => (editor.brushShape = shape)}>{t('editor.brush.' + shape)}</button>
          {/each}
        </div>
        <label class="field">{t('editor.size')} <input type="range" min="1" max="16" bind:value={editor.brushSize} /> <span class="value">{editor.brushSize}</span></label>
      {/if}
      {#if shapeTool}
        <div class="segmented" role="group">
          <button type="button" class:on={editor.filled} onclick={() => (editor.filled = true)}>{t('editor.filled')}</button>
          <button type="button" class:on={!editor.filled} onclick={() => (editor.filled = false)}>{t('editor.outline')}</button>
        </div>
      {/if}
      {#if solidTool}
        <label class="field">{t('editor.height')} <input class="num" type="number" min="1" max="256" bind:value={editor.height} /></label>
        <span>{t('editor.solidHelp')}</span>
      {/if}
      {#if editor.tool === 'select'}<span>{t('editor.selectHelp')}</span>{/if}
      {#if editor.tool === 'stamp'}
        {#if editor.clip}
          <span class="value">{editor.clip.size.join(' × ')}</span>
          <button class="btn icon" type="button" title={t('editor.rotate')} onclick={() => editor.clip && (editor.clip = rotateClip(editor.clip, 1))}>↻</button>
          <button class="btn" type="button" onclick={() => editor.clip && (editor.clip = mirrorClip(editor.clip, 'x'))}>{t('editor.mirrorX')}</button>
          <button class="btn" type="button" onclick={() => editor.clip && (editor.clip = mirrorClip(editor.clip, 'z'))}>{t('editor.mirrorZ')}</button>
          <span>{t('editor.stampHelp')}</span>
        {:else}
          <span>{t('editor.noClip')}</span>
        {/if}
      {/if}
      {#if editor.tool === 'fill'}<span>{t('editor.fillHelp')}</span>{/if}
      {#if editor.tool === 'picker'}<span>{t('editor.pickerHelp')}</span>{/if}
      <span class="spacer"></span>
      <button class="link" type="button" onclick={() => dock && openPanel(dock, 'layers')}>{t('layers.title')}: <span class="value">{currentLayerName}</span>{#if editor.solo !== null} · {t('layers.soloOn')}{/if}</button>
      <span>{t('editor.block')}: <span class="value">{nameOf(editor.block)}</span></span>
    </div>

    {#if editor.selection}
      <div class="options selection">
        <span class="tool-name">{t('editor.selection')}</span>
        <span class="value">{boxSize(editor.selection).join(' × ')}</span>
        <button class="btn" type="button" onclick={() => ctx.blueprint && onSelection('delete', (b) => clearBox(ctx.blueprint!, b))}>{t('editor.sel.delete')}</button>
        <button class="btn" type="button" onclick={() => ctx.blueprint && onSelection('fill', (b) => fillBox(ctx.blueprint!, b, editor.block))}>{t('editor.sel.fill')}</button>
        <span class="field">
          <select class="input small" bind:value={replaceFrom} aria-label={t('editor.sel.replaceFrom')}>
            {#each selectionBlocks as b (b.id)}<option value={b.id}>{nameOf(b.id)} ({b.count})</option>{/each}
          </select>
          <button class="btn" type="button" disabled={!replaceFrom} onclick={replaceSelection}>{t('editor.sel.replace')}</button>
        </span>
        <button class="btn" type="button" onclick={() => copySelection(false)}>{t('editor.sel.copy')}</button>
        <button class="btn" type="button" onclick={() => copySelection(true)}>{t('editor.sel.cut')}</button>
        <span class="field">
          <select class="input small" bind:value={moveTo} aria-label={t('layers.moveTo')}>
            {#each layerChoices as l (l.id)}<option value={l.id}>{l.name}</option>{/each}
          </select>
          <button class="btn" type="button" onclick={moveSelectionToLayer}>{t('layers.moveTo')}</button>
        </span>
        <button class="btn" type="button" onclick={saveSelectionAsStamp}>{t('editor.sel.saveStamp')}</button>
        <span class="spacer"></span>
        <button class="btn" type="button" onclick={() => (editor.selection = null)}>{t('editor.sel.clear')}</button>
      </div>
    {/if}

    <div class="body">
      <nav class="tools" aria-label={t('editor.tools')}>
        {#each TOOLS as tool, i (tool?.id ?? 'sep' + i)}
          {#if tool}
            <button type="button" class:on={editor.tool === tool.id} title={t('editor.tool.' + tool.id) + (tool.key ? ` (${tool.key})` : '')} aria-label={t('editor.tool.' + tool.id)} onclick={() => (editor.tool = tool.id)}>
              <PixelIcon name={tool.id} />
            </button>
          {:else}
            <span class="sep"></span>
          {/if}
        {/each}
      </nav>
      {#if ctx.blueprint}
        <Dock onready={ready} {locked} />
      {:else}
        <div class="loading"><span class="chip">{t('editor.loading.pack')}</span></div>
      {/if}
    </div>

    <footer class="status">
      {#if editor.cursor}
        <span>X {editor.cursor[0]} Y {editor.cursor[1]} Z {editor.cursor[2]}</span>
        <span>{editor.cursorState ? nameOf(editor.cursorState) : t('editor.emptyCell')}</span>
      {/if}
      <span class="spacer"></span>
      {#if editor.message}<span class="message">{t(editor.message)}</span>{/if}
      {#if locked}<span>{t('layout.lockedNote')}</span>{/if}
      {#if ctx.stats}<span>{t('editor.quads', { count: ctx.stats.quads })}</span>{/if}
    </footer>
  </div>
{/if}

<style>
  .editor {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
    background: var(--chrome);
  }

  .bar,
  .options {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 6px 12px;
  }

  .bar {
    padding: 5px 12px;
    background: var(--chrome-2);
    border-bottom: 2px solid var(--outline);
  }

  .bar a {
    color: var(--chrome-muted);
  }

  .title {
    min-width: 120px;
    max-width: 320px;
    padding: 2px 6px;
    border: 1px solid transparent;
    background: transparent;
    color: var(--chrome-text);
  }

  .title:hover,
  .title:focus {
    border-color: var(--chrome-border);
    background: var(--chrome);
    outline: none;
  }

  .save {
    color: var(--chrome-muted);
  }

  .save.unsaved {
    color: var(--accent);
  }

  .options {
    padding: 4px 12px;
    background: var(--chrome);
    border-bottom: 2px solid var(--outline);
    color: var(--chrome-muted);
  }

  .selection {
    background: var(--chrome-2);
  }

  .tool-name {
    color: var(--accent);
    min-width: 100px;
  }

  .value {
    color: var(--chrome-text);
  }

  .muted {
    color: var(--chrome-muted);
  }

  .spacer {
    flex: 1;
  }

  .field {
    display: flex;
    align-items: center;
    gap: 6px;
  }

  .num {
    width: 56px;
    padding: 1px 4px;
    border: 0;
    background: var(--chrome-raised);
    color: var(--chrome-text);
  }

  .input.small {
    padding: 1px 4px;
    max-width: 200px;
  }

  .link {
    padding: 0;
    border: 0;
    background: transparent;
    color: var(--chrome-muted);
    cursor: pointer;
  }

  .segmented {
    display: flex;
    border: 2px solid var(--outline);
  }

  .segmented button {
    padding: 1px 10px;
    border: 0;
    background: var(--chrome);
    color: var(--chrome-text);
    cursor: pointer;
  }

  .segmented button.on {
    background: var(--accent);
    color: var(--on-accent);
  }

  .btn.icon {
    padding: 4px 6px;
    min-width: 30px;
  }

  .row {
    display: flex;
    align-items: center;
  }

  .mini {
    width: 26px;
    height: 26px;
    border: 0;
    background: transparent;
    color: var(--text);
    cursor: pointer;
    flex: none;
  }

  .save-row {
    display: flex;
    gap: 6px;
    padding: 4px 12px;
  }

  .save-row .input {
    flex: 1;
    min-width: 0;
  }

  .body {
    flex: 1;
    min-height: 0;
    display: flex;
  }

  .tools {
    width: 48px;
    flex: none;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 2px;
    padding: 6px 0;
    border-right: 2px solid var(--outline);
    overflow-y: auto;
  }

  .tools button {
    width: 38px;
    height: 34px;
    flex: none;
    display: flex;
    align-items: center;
    justify-content: center;
    border: 0;
    background: transparent;
    color: var(--chrome-text);
    cursor: pointer;
    clip-path: var(--notch);
  }

  .tools button:hover {
    background: var(--chrome-raised);
  }

  .tools button.on {
    background: var(--accent);
    color: var(--on-accent);
  }

  .sep {
    width: 28px;
    height: 2px;
    margin: 3px 0;
    flex: none;
    background: var(--outline);
  }

  .loading {
    flex: 1;
    display: flex;
    align-items: center;
    justify-content: center;
    background: var(--viewport-bg);
  }

  .chip {
    padding: 0 8px;
    background: var(--chrome);
    border: 2px solid var(--outline);
  }

  .status {
    display: flex;
    gap: 16px;
    padding: 2px 12px;
    background: var(--chrome);
    border-top: 2px solid var(--outline);
    color: var(--chrome-muted);
    white-space: nowrap;
    overflow: hidden;
  }

  .message {
    color: var(--accent);
  }

  .none {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
    padding: 24px;
    margin: 24px;
  }
</style>
