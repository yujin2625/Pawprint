<script lang="ts">
  import { languages, locale, setLanguage, t } from '../../i18n/i18n.svelte';
  import { showIntro } from '../tour.svelte';
  import ThemeSettings from '../theme/ThemeSettings.svelte';
</script>

<h1>{t('settings.title')}</h1>

<div class="sections">
  <section class="panel box" aria-labelledby="set-language">
    <h2 id="set-language">{t('app.language')}</h2>
    <select class="input" value={locale.code} onchange={(e) => setLanguage(e.currentTarget.value)} aria-labelledby="set-language">
      {#each languages as language (language.code)}
        <option value={language.code}>{language.name}</option>
      {/each}
    </select>
    <p class="muted">{t('settings.languageHelp')}</p>
  </section>

  <section class="panel box" aria-labelledby="set-theme">
    <h2 id="set-theme">{t('theme.title')}</h2>
    <ThemeSettings />
  </section>

  <section class="panel box" aria-labelledby="set-help">
    <h2 id="set-help">{t('settings.help')}</h2>
    <div class="row">
      <button class="btn light" type="button" onclick={showIntro}>{t('settings.showIntro')}</button>
      <a class="btn light" href="#/credits">{t('app.credits')}</a>
    </div>
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
    max-width: 1080px;
  }

  .box {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 10px;
    padding: 16px 18px;
  }

  .row {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }
</style>
