<script lang="ts">
  import { blockLanguage, locale, t } from '../../i18n/i18n.svelte';
  import { blockName, type LoadedPack } from '../../core/pack/pawpack';
  import { parseState } from '../../core/model/state';
  import type { EditableBlueprint } from '../../core/blueprint/editable';
  import { BlockResources } from '../../render/resources';
  import { Viewport, type ViewportStats } from '../../render/viewport';
  import { SliceView } from '../../view2d/sliceView';
  import { Editor3D } from '../../render/editor3d';
  import { SOLID_SHAPES, type Tool } from '../../core/edit/tools';
  import { blocksIn, boxSize, clearBox, copy, fillBox, mirrorClip, replaceInBox, rotateClip, type Box } from '../../core/edit/clip';
  import { loadDefaultPack } from '../packs/activePack';
  import { download, loadProject, saveProject, saveStamp } from '../projects';
  import { editor, TOOL_KEYS } from '../editor/editor.svelte';
  import Palette from '../editor/Palette.svelte';
  import PixelIcon from '../editor/PixelIcon.svelte';
  import Stamps from '../editor/Stamps.svelte';
  import LayersPanel from '../editor/LayersPanel.svelte';
  import MaterialsPanel from '../editor/MaterialsPanel.svelte';
  import { hiddenLayers, moveCellsTo, protectedLayers, rows } from '../../core/blueprint/layers';

  let { projectId }: { projectId: string } = $props();

  let host3d: HTMLDivElement | undefined = $state();
  let host2d: HTMLDivElement | undefined = $state();
  let pack = $state.raw<LoadedPack | null>(null);
  let resources = $state.raw<BlockResources | null>(null);
  let blueprint = $state.raw<EditableBlueprint | null>(null);
  let failed = $state<string | null>(null);
  let stats = $state<ViewportStats | null>(null);
  let revision = $state(0);
  let saveState = $state<'saved' | 'saving' | 'unsaved'>('saved');
  let name = $state('');
  let viewport: Viewport | null = null;
  let slice: SliceView | null = null;
  let edit3d: Editor3D | null = null;
  let panelTab = $state<'palette' | 'layers' | 'materials' | 'stamps'>('palette');
  let moveTo = $state<number>(0);
  let stampsKey = $state(0);
  let replaceFrom = $state('');
  let saveTimer: ReturnType<typeof setTimeout> | undefined;

  const TOOLS: ({ id: Tool; key: string } | null)[] = [
    { id: 'select', key: 'V' }, null,
    { id: 'pencil', key: 'B' }, { id: 'eraser', key: 'E' }, { id: 'fill', key: 'G' }, { id: 'picker', key: 'I' }, null,
    { id: 'line', key: 'L' }, { id: 'rect', key: 'R' }, { id: 'ellipse', key: 'O' }, null,
    { id: 'box', key: 'U' }, { id: 'hollow', key: '' }, { id: 'wall', key: 'K' }, { id: 'sphere', key: '' }, { id: 'cylinder', key: '' }, null,
    { id: 'stamp', key: 'S' },
  ];

  // Load the pack, then the project.
  $effect(() => {
    const id = projectId;
    let cancelled = false;
    (async () => {
      try {
        const p = await loadDefaultPack().catch(() => null);
        const loaded = await loadProject(id);
        if (cancelled) return;
        if (!loaded) {
          failed = 'editor.notFound';
          return;
        }
        pack = p;
        resources = new BlockResources(p, 4096);
        name = loaded.blueprint.meta.name;
        editor.solo = null;
        editor.selection = null;
        editor.currentLayer = loaded.blueprint.currentLayer = 0;
        const b = loaded.blueprint.bounds();
        editor.slice = b ? (editor.plane === 'y' ? b.min[1] : editor.plane === 'z' ? b.max[2] : b.max[0]) : 0;
        blueprint = loaded.blueprint;
      } catch (e) {
        failed = 'editor.loadFailed';
        console.error(e);
      }
    })();
    return () => {
      cancelled = true;
    };
  });

  // 3D view.
  $effect(() => {
    if (!blueprint || !resources || !host3d) return;
    const view = new Viewport(host3d, resources);
    viewport = view;
    if (import.meta.env.DEV) (window as unknown as { __viewport?: Viewport }).__viewport = view;
    view.onStats = (s) => (stats = s);
    view.setHiddenLayers(editor.hiddenLayers);
    void view.show(blueprint);
    const editing = new Editor3D(view, blueprint, {
      settings: () => editor,
      onPick: (state) => {
        editor.block = state;
        editor.tool = 'pencil';
      },
      onSelect: (box) => (editor.selection = box),
      onCursor: (world, state) => {
        editor.cursor = world;
        editor.cursorState = state;
      },
      onMessage: (key) => (editor.message = key),
    });
    edit3d = editing;
    if (import.meta.env.DEV) (window as unknown as { __edit3d?: Editor3D }).__edit3d = editing;
    return () => {
      editing.dispose();
      edit3d = null;
      view.dispose();
      viewport = null;
    };
  });

  // 2D view.
  $effect(() => {
    if (!blueprint || !resources || !host2d) return;
    const view = new SliceView(host2d, resources, blueprint, {
      settings: () => editor,
      onPick: (state) => {
        editor.block = state;
        editor.tool = 'pencil';
      },
      onSelect: (box) => (editor.selection = box),
      onCursor: (world, state) => {
        editor.cursor = world;
        editor.cursorState = state;
      },
      onMessage: (key) => (editor.message = key),
    });
    slice = view;
    if (import.meta.env.DEV) (window as unknown as { __slice?: SliceView; __blueprint?: EditableBlueprint }).__slice = view;
    if (import.meta.env.DEV) (window as unknown as { __blueprint?: EditableBlueprint }).__blueprint = blueprint;
    return () => {
      view.dispose();
      slice = null;
    };
  });

  // Autosave a moment after edits; also keeps the undo/redo buttons current.
  $effect(() => {
    const bp = blueprint;
    if (!bp) return;
    const off = bp.onChange(() => {
      revision++;
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
    const bp = blueprint;
    void revision;
    const solo = editor.solo;
    if (!bp) return;
    if (solo !== null && !bp.layers.some((l) => l.id === solo)) {
      editor.solo = null;
      return;
    }
    const hidden = hiddenLayers(bp, solo);
    bp.protectedLayers = new Set([...protectedLayers(bp), ...hidden]);
    editor.hiddenLayers = hidden;
    viewport?.setHiddenLayers(hidden);
    if (!bp.layers.some((l) => l.id === editor.currentLayer)) editor.currentLayer = bp.currentLayer = 0;
  });

  // Tool and slice settings reach the views.
  $effect(() => {
    void [editor.tool, editor.block, editor.brushShape, editor.brushSize, editor.filled, editor.height, editor.selection, editor.clip, editor.hiddenLayers, editor.plane, editor.slice, editor.onionBelow, editor.onionAbove, editor.onionOpacity];
    slice?.settingsChanged();
    edit3d?.refresh();
  });
  $effect(() => {
    const showSlice = editor.view !== '3d';
    void [editor.plane, editor.slice, revision, blueprint];
    viewport?.setSlice(showSlice ? editor.plane : null, editor.slice);
  });
  $effect(() => {
    if (!editor.message) return;
    const timer = setTimeout(() => (editor.message = null), 4000);
    return () => clearTimeout(timer);
  });

  async function save() {
    const bp = blueprint;
    if (!bp) return;
    saveState = 'saving';
    try {
      const thumbnail = viewport && editor.view !== '2d' ? await viewport.snapshot() : null;
      await saveProject(projectId, bp, thumbnail);
      saveState = 'saved';
    } catch (e) {
      console.error(e);
      saveState = 'unsaved';
      editor.message = 'editor.saveFailed';
    }
  }

  function rename() {
    if (!blueprint) return;
    blueprint.meta.name = name.trim() || blueprint.meta.name;
    name = blueprint.meta.name;
    saveState = 'unsaved';
    void save();
  }

  function setPlane(plane: 'y' | 'z' | 'x') {
    if (editor.plane === plane) return;
    const c = editor.cursor;
    editor.plane = plane;
    // Keep looking at the same spot: the new slice goes through the cursor (or the blueprint's middle).
    const b = blueprint?.bounds();
    const point = c ?? (b ? [(b.min[0] + b.max[0]) >> 1, (b.min[1] + b.max[1]) >> 1, (b.min[2] + b.max[2]) >> 1] : [0, 0, 0]);
    editor.slice = plane === 'y' ? point[1]! : plane === 'z' ? point[2]! : point[0]!;
    queueMicrotask(() => slice?.centerOnBlueprint());
  }

  /** Runs an edit on the selection as one undo step. */
  function onSelection(label: string, run: (box: Box) => void) {
    const box = editor.selection;
    if (!box || !blueprint) return;
    blueprint.begin(label);
    run(box);
    blueprint.commit();
  }

  function copySelection(cut: boolean) {
    const box = editor.selection;
    if (!box || !blueprint) return;
    editor.clip = copy(blueprint, box);
    if (cut) onSelection('cut', (b) => clearBox(blueprint!, b));
    editor.message = cut ? 'editor.cutDone' : 'editor.copyDone';
  }

  function pasteClip() {
    if (editor.clip) editor.tool = 'stamp';
  }

  async function saveSelectionAsStamp() {
    const box = editor.selection;
    if (!box || !blueprint) return;
    const clip = copy(blueprint, box);
    if (!clip.cells.length) return;
    await saveStamp(clip, '★ ' + (blueprint.meta.name || t('projects.untitled')) + ' ' + boxSize(box).join('×'));
    stampsKey++;
    editor.message = 'editor.stampSaved';
  }

  function replaceSelection() {
    const box = editor.selection;
    if (!box || !blueprint || !replaceFrom) return;
    const target = parseState(editor.block);
    const def = pack?.blocks.find((b) => b.id === target.id);
    // Keep properties the new block also has (stairs keep their facing); without a pack keep them all.
    const keep = (prop: string) => !def || prop in def.properties;
    onSelection('replace', (b) => replaceInBox(blueprint!, b, replaceFrom, editor.block, keep));
  }

  const layerChoices = $derived.by(() => {
    void revision;
    return blueprint ? rows(blueprint).filter((r) => !r.layer.group).map((r) => r.layer) : [];
  });
  const currentLayerName = $derived(layerChoices.find((l) => l.id === editor.currentLayer)?.name ?? '');

  function moveSelectionToLayer() {
    const box = editor.selection;
    if (!box || !blueprint) return;
    const cells: [number, number, number][] = [];
    for (let y = box.min[1]; y <= box.max[1]; y++)
      for (let z = box.min[2]; z <= box.max[2]; z++)
        for (let x = box.min[0]; x <= box.max[0]; x++) if (blueprint.get(x, y, z) !== 0 && blueprint.editable(x, y, z)) cells.push([x, y, z]);
    moveCellsTo(blueprint, cells, moveTo);
  }

  const selectionBlocks = $derived.by(() => {
    void revision;
    return editor.selection && blueprint ? blocksIn(blueprint, editor.selection).slice(0, 30) : [];
  });
  $effect(() => {
    if (selectionBlocks.length && !selectionBlocks.some((b) => b.id === replaceFrom)) replaceFrom = selectionBlocks[0]!.id;
  });

  function onKey(e: KeyboardEvent) {
    const target = e.target as HTMLElement | null;
    if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT' || target.isContentEditable)) return;
    const key = e.key.toLowerCase();
    // While the right button is held in 3D, letters move the camera.
    if (viewport?.controls.isLooking) return;
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
      pasteClip();
      return;
    }
    if (e.key === 'Delete' || e.key === 'Backspace') {
      if (editor.selection) {
        e.preventDefault();
        onSelection('delete', (b) => clearBox(blueprint!, b));
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
      if (e.shiftKey) blueprint?.redo();
      else blueprint?.undo();
    } else if ((e.ctrlKey || e.metaKey) && key === 'y') {
      e.preventDefault();
      blueprint?.redo();
    } else if ((e.ctrlKey || e.metaKey) && key === 's') {
      e.preventDefault();
      void save();
    } else if (e.key === 'PageUp' || e.key === ']') {
      e.preventDefault();
      editor.slice++;
    } else if (e.key === 'PageDown' || e.key === '[') {
      e.preventDefault();
      editor.slice--;
    } else if (!e.ctrlKey && !e.metaKey && !e.altKey && TOOL_KEYS[key]) {
      editor.tool = TOOL_KEYS[key]!;
    }
  }

  const lang = $derived((locale.code, blockLanguage()));
  const nameOf = (state: string) => {
    const id = parseState(state).id;
    return pack ? blockName(pack.languages, id, lang) : id;
  };
  const canUndo = $derived.by(() => {
    void revision;
    return blueprint?.canUndo ?? false;
  });
  const canRedo = $derived.by(() => {
    void revision;
    return blueprint?.canRedo ?? false;
  });
  const shapeTool = $derived(editor.tool === 'rect' || editor.tool === 'ellipse' || editor.tool === 'cylinder' || editor.tool === 'sphere');
  const solidTool = $derived(SOLID_SHAPES.includes(editor.tool));
  const brushTool = $derived(editor.tool === 'pencil' || editor.tool === 'eraser' || editor.tool === 'line');
  const sliceLabel = $derived(editor.plane === 'y' ? 'Y' : editor.plane === 'z' ? 'Z' : 'X');
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
      <button class="btn icon" type="button" title={t('editor.undo')} aria-label={t('editor.undo')} disabled={!canUndo} onclick={() => blueprint?.undo()}><PixelIcon name="undo" /></button>
      <button class="btn icon" type="button" title={t('editor.redo')} aria-label={t('editor.redo')} disabled={!canRedo} onclick={() => blueprint?.redo()}><PixelIcon name="redo" /></button>
      <div class="segmented" role="group" aria-label={t('editor.viewMode')}>
        <button type="button" class:on={editor.view === '3d'} onclick={() => (editor.view = '3d')}>3D</button>
        <button type="button" class:on={editor.view === '2d'} onclick={() => (editor.view = '2d')}>2D</button>
        <button type="button" class:on={editor.view === 'split'} onclick={() => (editor.view = 'split')}>3D + 2D</button>
      </div>
      <button class="btn" type="button" onclick={() => viewport?.frame()}>{t('editor.frame')}</button>
      <button class="btn primary" type="button" disabled={!blueprint} onclick={() => blueprint && download(blueprint)}>{t('editor.download')}</button>
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
      <button class="link" type="button" onclick={() => (panelTab = 'layers')}>{t('layers.title')}: <span class="value">{currentLayerName}</span>{#if editor.solo !== null} · {t('layers.soloOn')}{/if}</button>
      <span>{t('editor.block')}: <span class="value">{nameOf(editor.block)}</span></span>
    </div>

    {#if editor.selection}
      <div class="options selection">
        <span class="tool-name">{t('editor.selection')}</span>
        <span class="value">{boxSize(editor.selection).join(' × ')}</span>
        <button class="btn" type="button" onclick={() => onSelection('delete', (b) => clearBox(blueprint!, b))}>{t('editor.sel.delete')}</button>
        <button class="btn" type="button" onclick={() => onSelection('fill', (b) => fillBox(blueprint!, b, editor.block))}>{t('editor.sel.fill')}</button>
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

      <div class={['views', 'v' + editor.view]}>
        <section class="view3d" aria-label={t('editor.view3d')}>
          <div class="host" bind:this={host3d}></div>
          <div class="overlay top">
            {#if !blueprint}<span class="chip">{t('editor.loading.pack')}</span>{/if}
            {#if stats && stats.sectionsDone < stats.sectionsTotal}<span class="chip">{t('editor.sections', { done: stats.sectionsDone, total: stats.sectionsTotal })}</span>{/if}
            {#if blueprint && !pack}<span class="chip warn">{t('editor.noPack')}</span>{/if}
            {#if stats && stats.missingBlocks.length && pack}<span class="chip warn" title={stats.missingBlocks.join('\n')}>{t('editor.missing', { count: stats.missingBlocks.length })}</span>{/if}
          </div>
          <div class="overlay bottom">{t('editor.help')}</div>
        </section>
        <div class="divider"></div>
        <section class="view2d" aria-label={t('editor.view2d')}>
          <div class="slice-bar">
            <div class="segmented" role="group" aria-label={t('editor.plane')}>
              <button type="button" class:on={editor.plane === 'y'} onclick={() => setPlane('y')}>{t('editor.plane.y')}</button>
              <button type="button" class:on={editor.plane === 'z'} onclick={() => setPlane('z')}>{t('editor.plane.z')}</button>
              <button type="button" class:on={editor.plane === 'x'} onclick={() => setPlane('x')}>{t('editor.plane.x')}</button>
            </div>
            <div class="slice-step">
              <button class="btn icon" type="button" aria-label={t('editor.sliceDown')} title={t('editor.sliceDown')} onclick={() => editor.slice--}>-</button>
              <span class="value">{sliceLabel} = {editor.slice}</span>
              <button class="btn icon" type="button" aria-label={t('editor.sliceUp')} title={t('editor.sliceUp')} onclick={() => editor.slice++}>+</button>
            </div>
            <span class="spacer"></span>
            <span>{t('editor.onion')}</span>
            <button type="button" class={['toggle', { on: editor.onionBelow }]} aria-pressed={editor.onionBelow} onclick={() => (editor.onionBelow = !editor.onionBelow)}>{t('editor.onionBelow')}</button>
            <button type="button" class={['toggle', { on: editor.onionAbove }]} aria-pressed={editor.onionAbove} onclick={() => (editor.onionAbove = !editor.onionAbove)}>{t('editor.onionAbove')}</button>
            <input type="range" min="0.1" max="0.8" step="0.05" bind:value={editor.onionOpacity} aria-label={t('editor.onionOpacity')} />
          </div>
          <div class="host" bind:this={host2d}></div>
          <div class="overlay bottom">{t('editor.help2d')}</div>
        </section>
      </div>

      <aside class="panel side">
        <div class="tabs" role="tablist">
          <button type="button" role="tab" aria-selected={panelTab === 'palette'} class:on={panelTab === 'palette'} onclick={() => (panelTab = 'palette')}>{t('editor.palette')}</button>
          <button type="button" role="tab" aria-selected={panelTab === 'layers'} class:on={panelTab === 'layers'} onclick={() => (panelTab = 'layers')}>{t('layers.title')}</button>
          <button type="button" role="tab" aria-selected={panelTab === 'materials'} class:on={panelTab === 'materials'} onclick={() => (panelTab = 'materials')}>{t('materials.title')}</button>
          <button type="button" role="tab" aria-selected={panelTab === 'stamps'} class:on={panelTab === 'stamps'} onclick={() => (panelTab = 'stamps')}>{t('editor.stamps')}</button>
        </div>
        {#if panelTab === 'palette'}
          {#if resources}<Palette {pack} {resources} />{/if}
        {:else if panelTab === 'layers'}
          {#if blueprint}<LayersPanel {blueprint} {revision} />{/if}
        {:else if panelTab === 'materials'}
          {#if blueprint}<MaterialsPanel {blueprint} {pack} {revision} />{/if}
        {:else}
          <Stamps currentId={projectId} refreshKey={stampsKey} />
        {/if}
      </aside>
    </div>

    <footer class="status">
      {#if editor.cursor}
        <span>X {editor.cursor[0]} Y {editor.cursor[1]} Z {editor.cursor[2]}</span>
        <span>{editor.cursorState ? nameOf(editor.cursorState) : t('editor.emptyCell')}</span>
      {/if}
      <span class="spacer"></span>
      {#if editor.message}<span class="message">{t(editor.message)}</span>{/if}
      {#if stats}<span>{t('editor.quads', { count: stats.quads })}</span>{/if}
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
  .options,
  .slice-bar {
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

  .segmented {
    display: flex;
    border: 2px solid var(--outline);
  }

  .segmented button,
  .toggle {
    padding: 1px 10px;
    border: 0;
    background: var(--chrome);
    color: var(--chrome-text);
    cursor: pointer;
  }

  .segmented button.on,
  .toggle.on {
    background: var(--accent);
    color: var(--on-accent);
  }

  .toggle {
    border: 2px solid var(--outline);
  }

  .btn.icon {
    padding: 4px 6px;
    min-width: 30px;
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
    overflow-y: auto;
    border-right: 2px solid var(--outline);
  }

  .tools button {
    width: 38px;
    height: 34px;
    display: flex;
    align-items: center;
    justify-content: center;
    border: 0;
    background: transparent;
    color: var(--chrome-text);
    cursor: pointer;
    clip-path: var(--notch);
  }

  .sep {
    width: 28px;
    height: 2px;
    margin: 3px 0;
    background: var(--outline);
  }

  .link {
    padding: 0;
    border: 0;
    background: transparent;
    color: var(--chrome-muted);
    cursor: pointer;
  }

  .num {
    width: 56px;
    padding: 1px 4px;
    border: 0;
    background: var(--chrome-raised);
    color: var(--chrome-text);
  }

  .selection {
    background: var(--chrome-2);
  }

  .input.small {
    padding: 1px 4px;
    max-width: 200px;
  }

  .tabs {
    display: flex;
    border-bottom: 2px solid var(--panel-border);
  }

  .tabs button {
    flex: 1;
    padding: 4px 0;
    border: 0;
    background: transparent;
    color: var(--text);
    cursor: pointer;
  }

  .tabs button.on {
    background: var(--text);
    color: var(--panel);
  }

  .tools button:hover {
    background: var(--chrome-raised);
  }

  .tools button.on {
    background: var(--accent);
    color: var(--on-accent);
  }

  .views {
    flex: 1;
    min-width: 0;
    display: flex;
  }

  .views > section {
    position: relative;
    flex: 1 1 50%;
    min-width: 0;
    display: flex;
    flex-direction: column;
  }

  .v3d .view2d,
  .v2d .view3d,
  .v3d .divider,
  .v2d .divider {
    display: none;
  }

  .divider {
    width: 4px;
    flex: none;
    background: var(--outline);
  }

  .host {
    position: relative;
    flex: 1;
    min-height: 0;
    overflow: hidden;
  }

  .slice-bar {
    padding: 4px 8px;
    background: var(--chrome);
    color: var(--chrome-muted);
  }

  .slice-step {
    display: flex;
    align-items: center;
    gap: 6px;
  }

  .overlay {
    position: absolute;
    left: 10px;
    right: 10px;
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    pointer-events: none;
  }

  .top {
    top: 10px;
  }

  .bottom {
    bottom: 8px;
    width: fit-content;
    max-width: calc(100% - 20px);
    padding: 2px 8px;
    background: rgba(26, 86, 148, 0.85);
    color: var(--chrome-muted);
  }

  .chip {
    padding: 0 8px;
    background: var(--chrome);
    border: 2px solid var(--outline);
    pointer-events: auto;
  }

  .warn {
    background: var(--warning-bg);
    color: var(--warning);
  }

  .side {
    width: 300px;
    flex: none;
    display: flex;
    flex-direction: column;
    min-height: 0;
    border-width: 0 0 0 2px;
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
