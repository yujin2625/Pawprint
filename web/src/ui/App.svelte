<script lang="ts">
  import { languages, locale, setLanguage, t } from '../i18n/i18n.svelte';
  import EditorPage from './pages/EditorPage.svelte';
  import PacksPage from './pages/PacksPage.svelte';
  import DownloadPage from './pages/DownloadPage.svelte';
  import SettingsPage from './pages/SettingsPage.svelte';
  import CreditsPage from './pages/CreditsPage.svelte';
  import ReceivePage from './pages/ReceivePage.svelte';
  import Intro from './Intro.svelte';
  import PixelIcon from './editor/PixelIcon.svelte';
  import { intro } from './tour.svelte';
  import { colorsOf, isLight, themes } from './theme/theme.svelte';
  import ProjectsPage from './pages/ProjectsPage.svelte';
  import { isDesktop } from '../platform/platform';
  import { watchOpenFiles } from './openFiles';
  import ConfirmDialog from './ConfirmDialog.svelte';
  import { answerReplace, packJob } from './packs/packJobs.svelte';

  let hash = $state(location.hash);

  $effect(() => {
    const update = () => (hash = location.hash);
    window.addEventListener('hashchange', update);
    return () => window.removeEventListener('hashchange', update);
  });

  $effect(() => {
    if (!isDesktop) return;
    let off: (() => void) | null = null;
    let gone = false;
    void watchOpenFiles().then((unlisten) => (gone ? unlisten() : (off = unlisten)));
    return () => {
      gone = true;
      off?.();
    };
  });

  const lightBars = $derived((void themes.version, isLight(colorsOf(themes.current).chrome)));
  const ROUTES = ['packs', 'download', 'settings', 'credits', 'receive'] as const;
  const page = $derived(hash.startsWith('#/editor/') ? 'editor' : (ROUTES.find((r) => hash.startsWith('#/' + r)) ?? 'projects'));
  const projectId = $derived(page === 'editor' ? decodeURIComponent(hash.slice('#/editor/'.length)) : '');
</script>

<div class={["shell", { full: page === "editor" }]}>
  <header>
    <a class="logo" href="#/projects"><img class="pixel" src={lightBars ? './brand/logo-horizontal-light@2x.png' : './brand/logo-horizontal-dark@2x.png'} alt={t('app.name')} width="108" height="24" /></a>
    <nav aria-label={t('nav.main')}>
      <a href="#/projects" aria-current={page === 'projects' ? 'page' : undefined}>{t('nav.projects')}</a>
      <a href="#/packs" aria-current={page === 'packs' ? 'page' : undefined}>{t('nav.packs')}</a>
      <a href="#/download" aria-current={page === 'download' ? 'page' : undefined}>{t('nav.download')}</a>
    </nav>
    <div class="spacer"></div>
    {#if packJob.busy}
      <span role="status"><a class="job busy" href="#/packs">{t(packJob.busy.key, packJob.busy.params)}</a></span>
    {:else if page !== 'packs' && packJob.notice}
      <span role="status"><a class="job" href="#/packs">{t(packJob.notice.key, packJob.notice.params)}</a></span>
    {:else if page !== 'packs' && packJob.error}
      <span role="alert"><a class="job failed" href="#/packs">{t('packs.failedShort')}</a></span>
    {/if}
    <a class="settings" href="#/settings" title={t('settings.title')} aria-label={t('settings.title')} aria-current={page === 'settings' ? 'page' : undefined}><PixelIcon name="gear" /></a>
    <label>
      <span class="visually-hidden">{t('app.language')}</span>
      <select class="lang" value={locale.code} onchange={(e) => setLanguage(e.currentTarget.value)}>
        {#each languages as language (language.code)}
          <option value={language.code}>{language.name}</option>
        {/each}
      </select>
    </label>
  </header>

  <main>
    {#if page === 'packs'}
      <PacksPage />
    {:else if page === 'download'}
      <DownloadPage />
    {:else if page === 'settings'}
      <SettingsPage />
    {:else if page === 'credits'}
      <CreditsPage />
    {:else if page === 'receive'}
      {#key hash}<ReceivePage query={hash.split('?')[1] ?? ''} />{/key}
    {:else if page === 'editor'}
      {#key projectId}<EditorPage {projectId} />{/key}
    {:else}
      <ProjectsPage />
    {/if}
  </main>

  {#if page !== 'editor'}
  <footer>
    <span>{t('app.disclaimer')}</span>
    <a href="#/credits">{t('app.credits')}</a>
    <span class="spacer"></span>
    <span>{t('app.localOnly')}</span>
  </footer>
  {/if}
</div>

{#if intro.open}<Intro />{/if}

{#if packJob.replacing}
  <ConfirmDialog message={t('packs.confirmReplace', { name: packJob.replacing.name })} confirmLabel={t('packs.replace')} onanswer={answerReplace} />
{/if}

<style>
  .shell {
    min-height: 100vh;
    display: flex;
    flex-direction: column;
  }

  header {
    min-height: 52px;
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 8px 24px;
    padding: 0 24px;
    background: var(--chrome);
    border-bottom: 2px solid var(--outline);
  }

  .full {
    height: 100vh;
    min-height: 0;
  }

  .full main {
    max-width: none;
    padding: 0;
    min-height: 0;
    display: flex;
    flex-direction: column;
  }

  .logo {
    display: flex;
  }

  nav {
    display: flex;
    align-self: stretch;
    gap: 4px;
  }

  nav a {
    display: flex;
    align-items: center;
    padding: 0 14px;
    color: var(--chrome-muted);
    text-decoration: none;
    border-bottom: 3px solid transparent;
  }

  nav a[aria-current='page'] {
    color: var(--chrome-text);
    border-bottom-color: var(--accent);
  }

  nav a:hover {
    color: var(--chrome-text);
  }

  .spacer {
    flex: 1;
  }

  /* A pack being made (or just made) while another page is open. */
  .job {
    display: block;
    max-width: 44ch;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    padding: 2px 10px;
    border: 2px solid var(--outline);
    background: var(--chrome-raised);
    color: var(--chrome-text);
    text-decoration: none;
  }

  .job.busy {
    animation: job-pulse 1.2s steps(2, jump-none) infinite;
  }

  .job.failed {
    border-color: var(--danger);
  }

  @keyframes job-pulse {
    50% {
      border-color: var(--accent);
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .job.busy {
      animation: none;
      border-color: var(--accent);
    }
  }

  .settings {
    display: flex;
    padding: 6px;
    color: var(--chrome-muted);
  }

  .settings:hover,
  .settings[aria-current='page'] {
    color: var(--chrome-text);
  }

  footer a {
    color: inherit;
  }

  .lang {
    padding: 4px 8px;
    background: var(--chrome-raised);
    color: var(--chrome-text);
    border: 0;
    clip-path: var(--notch);
  }

  main {
    flex: 1;
    width: 100%;
    max-width: 1240px;
    margin: 0 auto;
    padding: 28px 24px 40px;
  }

  footer {
    display: flex;
    flex-wrap: wrap;
    gap: 8px 16px;
    padding: 10px 24px;
    background: var(--chrome);
    color: var(--chrome-muted);
  }
</style>
