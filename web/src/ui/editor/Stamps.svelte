<script lang="ts">
  import { t } from '../../i18n/i18n.svelte';
  import { listProjects, type StoredProject } from '../../storage/db';
  import { loadClip } from '../projects';
  import { editor } from './editor.svelte';

  let { currentId, refreshKey = 0 }: { currentId: string; refreshKey?: number } = $props();

  let projects = $state<StoredProject[]>([]);
  let query = $state('');
  let loading = $state<string | null>(null);

  $effect(() => {
    void refreshKey;
    void listProjects().then((all) => (projects = all.filter((p) => p.id !== currentId)));
  });

  const shown = $derived.by(() => {
    const q = query.trim().toLowerCase();
    const list = q ? projects.filter((p) => p.name.toLowerCase().includes(q)) : projects;
    // Saved stamps first, then other projects.
    return [...list].sort((a, b) => Number(isStamp(b)) - Number(isStamp(a)));
  });

  const thumbs = $derived(new Map(projects.map((p) => [p.id, p.thumbnail ? URL.createObjectURL(p.thumbnail) : null])));
  $effect(() => {
    const current = thumbs;
    return () => current.forEach((url) => url && URL.revokeObjectURL(url));
  });

  function isStamp(p: StoredProject): boolean {
    return p.name.startsWith('★');
  }

  async function use(p: StoredProject) {
    loading = p.id;
    const clip = await loadClip(p.id);
    loading = null;
    if (!clip) return;
    editor.clip = clip;
    editor.tool = 'stamp';
  }
</script>

<section class="stamps" aria-label={t('editor.stamps')}>
  <input class="input" type="search" placeholder={t('editor.searchStamps')} aria-label={t('editor.searchStamps')} bind:value={query} />
  <p class="muted">{t('editor.stampsHelp')}</p>
  <div class="grid">
    {#each shown as p (p.id)}
      <button type="button" class:busy={loading === p.id} onclick={() => use(p)} title={p.name}>
        <span class="thumb">{#if thumbs.get(p.id)}<img src={thumbs.get(p.id)} alt="" />{/if}</span>
        <span class="name">{p.name}</span>
        <span class="muted">{p.size.join('×')}</span>
      </button>
    {:else}
      <p class="muted">{t('editor.noStamps')}</p>
    {/each}
  </div>
</section>

<style>
  .stamps {
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding: 10px 12px;
    min-height: 0;
    flex: 1;
  }

  .muted {
    color: var(--text-muted);
  }

  .grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 6px;
    overflow-y: auto;
    align-content: start;
  }

  .grid button {
    display: flex;
    flex-direction: column;
    gap: 2px;
    padding: 4px;
    border: 2px solid var(--panel-border);
    background: var(--panel-input);
    color: var(--text);
    text-align: left;
    cursor: pointer;
  }

  .grid button:hover {
    border-color: var(--accent);
  }

  .busy {
    opacity: 0.6;
  }

  .thumb {
    height: 72px;
    background: var(--viewport-bg);
  }

  .thumb img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    image-rendering: pixelated;
  }

  .name {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
</style>
