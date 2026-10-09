<script lang="ts">
  import { formatBytes, formatDate, t } from '../../i18n/i18n.svelte';
  import { guessSystem, readDownloads, type AppSystem, type Downloads } from '../../core/releases';
  import { isDesktop } from '../../platform/platform';

  const REPO = 'https://github.com/yujin2625/Pawprint';
  const API = 'https://api.github.com/repos/yujin2625/Pawprint/releases?per_page=30';
  const LOADERS: Record<string, string> = { neoforge: 'NeoForge', forge: 'Forge', fabric: 'Fabric' };
  const SYSTEMS: AppSystem[] = ['windows', 'macos', 'linux'];

  let downloads = $state<Downloads | null>(null);
  let failed = $state(false);
  const mine = guessSystem(navigator.userAgent);

  $effect(() => {
    let cancelled = false;
    fetch(API, { headers: { Accept: 'application/vnd.github+json' } })
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
      .then((list) => !cancelled && (downloads = readDownloads(list)))
      .catch(() => !cancelled && (failed = true));
    return () => {
      cancelled = true;
    };
  });

  /** Jars grouped by Minecraft version, newest first (already sorted). */
  const versions = $derived.by(() => {
    const groups = new Map<string, NonNullable<Downloads['mod']>['jars']>();
    for (const jar of downloads?.mod?.jars ?? []) groups.set(jar.mcVersion, [...(groups.get(jar.mcVersion) ?? []), jar]);
    return [...groups];
  });

  /** The visitor's system first. */
  const systems = $derived(mine ? [mine, ...SYSTEMS.filter((s) => s !== mine)] : SYSTEMS);
</script>

<h1>{t('download.title')}</h1>

{#if failed}
  <p class="panel note">{t('download.failed')} <a href="{REPO}/releases" target="_blank" rel="noopener">{t('download.allReleases')}</a></p>
{:else if !downloads}
  <p class="muted">{t('download.loading')}</p>
{:else}
  <div class="layout">
    <section class="panel box" aria-labelledby="mod-title">
      <h2 id="mod-title">{t('download.mod.title')}</h2>
      <p class="muted">{t('download.mod.body')}</p>
      {#if downloads.mod}
        <p class="meta">{t('download.version', { version: downloads.mod.version })}{downloads.mod.date ? ' · ' + formatDate(downloads.mod.date) : ''}</p>
        {#each versions as [mc, jars] (mc)}
          <div class="row">
            <span class="mc">Minecraft {mc}</span>
            <span class="buttons">
              {#each jars as jar (jar.name)}
                <a class="btn primary" href={jar.url} target="_blank" rel="noopener" title="{jar.name} · {formatBytes(jar.size)}">{LOADERS[jar.loader] ?? jar.loader}</a>
              {/each}
            </span>
          </div>
        {/each}
        <p class="help">{t('download.mod.fabricApi')}</p>
        <p class="help">{t('download.mod.install')}</p>
      {:else}
        <p class="note">{t('download.none')}</p>
      {/if}
    </section>

    {#if !isDesktop}
      <section class="panel box" aria-labelledby="app-title">
        <h2 id="app-title">{t('download.app.title')}</h2>
        <p class="muted">{t('download.app.body')}</p>
        {#if downloads.app && downloads.app.installers.length}
          <p class="meta">{t('download.version', { version: downloads.app.version })}{downloads.app.date ? ' · ' + formatDate(downloads.app.date) : ''}</p>
          {#each systems as system (system)}
            {@const files = downloads.app.installers.filter((i) => i.system === system)}
            {#if files.length}
              <div class="row">
                <span class="mc">{t('download.system.' + system)}{system === mine ? ' · ' + t('download.yours') : ''}</span>
                <span class="buttons">
                  {#each files as file (file.name)}
                    <a class={['btn', { primary: system === mine }]} href={file.url} target="_blank" rel="noopener" title={file.name}>{file.kind} · {formatBytes(file.size)}</a>
                  {/each}
                </span>
              </div>
            {/if}
          {/each}
          <p class="help">{t('download.app.unsigned')}</p>
        {:else}
          <p class="note">{t('download.none')}</p>
        {/if}
      </section>
    {/if}
  </div>
  <p class="all"><a href="{REPO}/releases" target="_blank" rel="noopener">{t('download.allReleases')}</a></p>
{/if}

<style>
  h1 {
    margin-bottom: 20px;
  }

  .layout {
    display: flex;
    flex-wrap: wrap;
    gap: 24px;
    align-items: flex-start;
  }

  .box {
    flex: 1 1 420px;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 12px;
    padding: 16px 18px;
  }

  .meta {
    color: var(--text-muted);
  }

  .row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    flex-wrap: wrap;
    gap: 8px 16px;
    padding: 8px 0;
    border-top: 1px dashed var(--panel-border);
  }

  .buttons {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }

  .help {
    color: var(--text-muted);
    line-height: 1.3;
  }

  .note {
    padding: 10px 12px;
    line-height: 1.3;
  }

  .all {
    margin-top: 16px;
  }

  .all a,
  .note a {
    color: inherit;
  }
</style>
