<script lang="ts">
  import { t } from '../../i18n/i18n.svelte';

  /** Third-party code and assets shipped in the app. Keep in step with package.json and src-tauri/Cargo.toml. */
  const LIBRARIES = [
    { name: 'Svelte', url: 'https://svelte.dev', license: 'MIT' },
    { name: 'three.js', url: 'https://threejs.org', license: 'MIT' },
    { name: 'deepslate', url: 'https://github.com/misode/deepslate', license: 'MIT' },
    { name: 'gl-matrix', url: 'https://glmatrix.net', license: 'MIT' },
    { name: 'pako', url: 'https://github.com/nodeca/pako', license: 'MIT / Zlib' },
    { name: 'md5', url: 'https://github.com/pvorb/node-md5', license: 'BSD-3-Clause' },
    { name: 'fflate', url: 'https://github.com/101arrowz/fflate', license: 'MIT' },
    { name: 'dockview', url: 'https://dockview.dev', license: 'MIT' },
    { name: 'Tauri', url: 'https://tauri.app', license: 'MIT / Apache-2.0', desktop: true },
  ];
</script>

<h1>{t('credits.title')}</h1>

<div class="sections">
  <section class="panel box">
    <h2>Pawprint {__APP_VERSION__}</h2>
    <p>{t('credits.app')}</p>
    <p>
      <a href="https://github.com/yujin2625/Pawprint" target="_blank" rel="noopener">github.com/yujin2625/Pawprint</a> ·
      <a href="https://polyformproject.org/licenses/noncommercial/1.0.0/" target="_blank" rel="noopener">PolyForm Noncommercial 1.0.0</a>
    </p>
    <p class="muted">{t('app.disclaimer')} {t('credits.minecraft')}</p>
  </section>

  <section class="panel box">
    <h2>{t('credits.font')}</h2>
    <p>
      <a href="https://poppyworks.itch.io/silver" target="_blank" rel="noopener">Silver</a> — Poppy Works,
      <a href="https://creativecommons.org/licenses/by/4.0/" target="_blank" rel="noopener">CC BY 4.0</a>
    </p>
    <p class="muted">{t('credits.fontChanges')}</p>
  </section>

  <section class="panel box">
    <h2>{t('credits.libraries')}</h2>
    <ul>
      {#each LIBRARIES as lib (lib.name)}
        <li>
          <a href={lib.url} target="_blank" rel="noopener">{lib.name}</a>
          <span class="muted">— {lib.license}{lib.desktop ? ' · ' + t('credits.desktopOnly') : ''}</span>
        </li>
      {/each}
    </ul>
  </section>
</div>

<style>
  h1 {
    margin-bottom: 20px;
  }

  .sections {
    display: flex;
    flex-direction: column;
    gap: 16px;
    max-width: 760px;
  }

  .box {
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding: 16px 18px;
    line-height: 1.35;
  }

  ul {
    margin: 0;
    padding-left: 20px;
  }

  a {
    color: inherit;
  }
</style>
