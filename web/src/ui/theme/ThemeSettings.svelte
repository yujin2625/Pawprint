<script lang="ts">
  import { t } from '../../i18n/i18n.svelte';
  import { safeFileName, saveBytes } from '../../platform/platform';
  import { BUILT_IN_THEMES, THEME_GROUPS, contrastProblems, themeFromFile, themeToFile, toHex, type ThemeKey } from './themes';
  import { addTheme, colorsOf, copyTheme, deleteTheme, renameTheme, resetTheme, setColor, themes, useTheme } from './theme.svelte';

  let fileInput: HTMLInputElement;
  let message = $state<string | null>(null);

  const all = $derived([
    ...BUILT_IN_THEMES.map((id) => ({ id, name: t('theme.builtIn.' + id), custom: false })),
    ...themes.custom.map((c) => ({ id: c.id, name: c.name, custom: true })),
  ]);
  const editing = $derived(themes.custom.find((c) => c.id === themes.current) ?? null);
  const current = $derived(colorsOf(themes.current));
  const problems = $derived(contrastProblems(current));

  function copy() {
    const from = all.find((a) => a.id === themes.current);
    copyTheme(themes.current, t('theme.copyName', { name: from?.name ?? '' }));
  }

  function rename() {
    if (!editing) return;
    const next = prompt(t('theme.renamePrompt'), editing.name)?.trim();
    if (next) renameTheme(editing.id, next);
  }

  async function exportTheme() {
    const name = all.find((a) => a.id === themes.current)?.name ?? 'theme';
    const base = editing?.base ?? (BUILT_IN_THEMES.find((b) => b === themes.current) ?? 'blueprint');
    const bytes = new TextEncoder().encode(themeToFile({ name, base, colors: current }));
    await saveBytes(safeFileName(name, 'theme') + '.pawprint-theme.json', bytes, { name: 'JSON', extensions: ['json'] }, 'application/json');
  }

  async function importTheme() {
    const file = fileInput.files?.[0];
    fileInput.value = '';
    if (!file) return;
    try {
      addTheme(themeFromFile(await file.text()));
      message = 'theme.imported';
    } catch {
      message = 'theme.importFailed';
    }
  }

  function swatches(id: string): string[] {
    const c = colorsOf(id);
    return [c.bg, c.chrome, c.panel, c.accent, c.text];
  }

  function onPick(key: ThemeKey, value: string) {
    if (editing) setColor(editing.id, key, value);
  }
</script>

<div class="themes" role="radiogroup" aria-label={t('theme.title')}>
  {#each all as theme (theme.id)}
    <button class={['theme', { on: theme.id === themes.current }]} type="button" role="radio" aria-checked={theme.id === themes.current} onclick={() => useTheme(theme.id)}>
      <span class="sw">{#each swatches(theme.id) as c, i (i)}<span style:background={c}></span>{/each}</span>
      <span>{theme.name}</span>
    </button>
  {/each}
</div>

<div class="row">
  <button class="btn light" type="button" onclick={copy}>{t('theme.copy')}</button>
  {#if editing}
    <button class="btn light" type="button" onclick={rename}>{t('packs.rename')}</button>
    <button class="btn light" type="button" onclick={() => editing && resetTheme(editing.id)}>{t('theme.reset')}</button>
    <button class="btn light" type="button" onclick={() => editing && deleteTheme(editing.id)}>{t('packs.delete')}</button>
  {/if}
  <button class="btn light" type="button" onclick={exportTheme}>{t('theme.export')}</button>
  <button class="btn light" type="button" onclick={() => fileInput.click()}>{t('theme.import')}</button>
  <input bind:this={fileInput} type="file" accept=".json,application/json" hidden onchange={importTheme} />
</div>
{#if message}<p class="muted">{t(message)}</p>{/if}

{#if problems.length}
  <div class="warn" role="status">
    <strong>{t('theme.contrast')}</strong>
    {#each problems as p (p.text + p.bg)}
      <span>{t('theme.contrastPair', { text: t('theme.key.' + p.text), bg: t('theme.key.' + p.bg), ratio: p.ratio.toFixed(1), min: p.min })}</span>
    {/each}
  </div>
{/if}

{#if editing}
  <p class="muted">{t('theme.editHelp')}</p>
  <div class="groups">
    {#each Object.entries(THEME_GROUPS) as [group, keys] (group)}
      <fieldset>
        <legend>{t('theme.group.' + group)}</legend>
        {#each keys as key (key)}
          <label class="color">
            <input type="color" value={toHex(editing.colors[key]) ?? '#000000'} oninput={(e) => onPick(key, e.currentTarget.value)} aria-label={t('theme.key.' + key)} />
            <span class="name">{t('theme.key.' + key)}</span>
            <input class="input code" value={editing.colors[key]} spellcheck="false" onchange={(e) => onPick(key, e.currentTarget.value.trim())} aria-label="{t('theme.key.' + key)} CSS" />
          </label>
        {/each}
      </fieldset>
    {/each}
  </div>
{:else}
  <p class="muted">{t('theme.builtInHelp')}</p>
{/if}

<style>
  .themes {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }

  .theme {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 6px;
    padding: 8px 10px;
    background: var(--panel-2);
    color: var(--text);
    border: 2px solid var(--panel-border);
    cursor: pointer;
  }

  .theme.on {
    border-color: var(--accent);
  }

  .sw {
    display: flex;
  }

  .sw span {
    width: 18px;
    height: 18px;
    border: 1px solid var(--panel-border);
  }

  .row {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }

  .warn {
    display: flex;
    flex-direction: column;
    gap: 4px;
    padding: 8px 12px;
    background: var(--warning-bg);
    color: var(--warning);
    line-height: 1.3;
  }

  .groups {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
    gap: 12px;
    width: 100%;
  }

  fieldset {
    margin: 0;
    padding: 8px 12px 12px;
    border: 2px solid var(--panel-border);
    display: flex;
    flex-direction: column;
    gap: 6px;
  }

  legend {
    color: var(--text-muted);
    padding: 0 4px;
  }

  .color {
    display: grid;
    grid-template-columns: 32px 1fr 150px;
    align-items: center;
    gap: 8px;
  }

  .color input[type='color'] {
    width: 32px;
    height: 24px;
    padding: 0;
    border: 1px solid var(--panel-border);
    background: none;
  }

  .code {
    min-width: 0;
    font-size: var(--text-size);
  }
</style>
