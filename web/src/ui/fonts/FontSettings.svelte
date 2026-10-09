<script lang="ts">
  import { languages, locale, t } from '../../i18n/i18n.svelte';
  import { SILVER, addFont, applyFonts, familyFromFile, fonts, moveFont, orderFor, removeFont } from './userFonts.svelte';

  let fileInput: HTMLInputElement;
  let url = $state('');
  let family = $state('');
  let message = $state<string | null>(null);

  // The order shown: the current language's, then fonts not placed yet.
  const rows = $derived.by(() => {
    void fonts.list;
    const order = orderFor(locale.code);
    const rest = fonts.list.map((f) => f.id).filter((id) => !order.includes(id));
    return [...order, ...rest].map((id) => ({ id, name: id === SILVER ? 'Silver' : (fonts.list.find((f) => f.id === id)?.family ?? id), inOrder: order.includes(id) }));
  });
  const language = $derived(languages.find((l) => l.code === locale.code)?.name ?? locale.code);

  $effect(() => {
    void locale.code;
    if (fonts.loaded) applyFonts();
  });

  async function addFiles() {
    const files = [...(fileInput.files ?? [])];
    fileInput.value = '';
    for (const file of files) {
      if (file.size > 30 * 1024 * 1024) {
        message = 'fonts.tooLarge';
        continue;
      }
      await addFont({ family: familyFromFile(file.name), kind: 'file', file });
    }
  }

  async function addUrl() {
    const link = url.trim();
    if (!/^https:\/\//i.test(link) || !family.trim()) {
      message = 'fonts.badUrl';
      return;
    }
    await addFont({ family: family.trim(), kind: 'url', url: link });
    url = family = '';
    message = null;
  }
</script>

<p class="muted">{t('fonts.help', { language })}</p>
<ol class="list">
  {#each rows as row, i (row.id)}
    <li>
      <span class="name" style:font-family={row.id === SILVER ? "'Silver Pawprint'" : `'${row.name}'`}>{row.name} — {t('fonts.sample')}</span>
      {#if row.id === SILVER}<span class="muted">{t('fonts.builtIn')}</span>{/if}
      <span class="tools">
        <button class="mini" type="button" disabled={i === 0} aria-label={t('fonts.up')} title={t('fonts.up')} onclick={() => moveFont(row.id, -1)}>▲</button>
        <button class="mini" type="button" disabled={i === rows.length - 1} aria-label={t('fonts.down')} title={t('fonts.down')} onclick={() => moveFont(row.id, 1)}>▼</button>
        {#if row.id !== SILVER}
          <button class="mini" type="button" aria-label={t('packs.delete')} title={t('packs.delete')} onclick={() => removeFont(row.id)}>×</button>
        {/if}
      </span>
    </li>
  {/each}
</ol>

<div class="row">
  <button class="btn light" type="button" onclick={() => fileInput.click()}>{t('fonts.addFile')}</button>
  <input bind:this={fileInput} type="file" accept=".ttf,.otf,.woff,.woff2" multiple hidden onchange={addFiles} />
</div>
<form class="row" onsubmit={(e) => (e.preventDefault(), addUrl())}>
  <input class="input grow" placeholder="https://fonts.googleapis.com/css2?family=…" aria-label={t('fonts.url')} bind:value={url} />
  <input class="input" placeholder={t('fonts.family')} aria-label={t('fonts.family')} bind:value={family} />
  <button class="btn light" type="submit">{t('fonts.addUrl')}</button>
</form>
{#if message}<p class="warn">{t(message)}</p>{/if}

<style>
  .list {
    width: 100%;
    margin: 0;
    padding-left: 24px;
    display: flex;
    flex-direction: column;
    gap: 6px;
  }

  li {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  .name {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .tools {
    margin-left: auto;
    display: flex;
    gap: 4px;
  }

  .mini {
    padding: 2px 8px;
    background: var(--panel-2);
    color: var(--text);
    border: 1px solid var(--panel-border);
    cursor: pointer;
  }

  .mini:disabled {
    opacity: 0.4;
    cursor: default;
  }

  .row {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    width: 100%;
  }

  .grow {
    flex: 1 1 320px;
  }

  .warn {
    color: var(--warning);
  }
</style>
