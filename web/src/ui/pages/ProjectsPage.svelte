<script lang="ts">
  import { t } from '../../i18n/i18n.svelte';
</script>

<h1>{t('projects.title')}</h1>

<section class="empty panel">
  <img class="pixel" src="./brand/mascot-192.png" alt="" width="192" height="192" />
  <div class="text">
    <h2>{t('projects.empty.title')}</h2>
    <p class="muted">{t('projects.empty.body')}</p>
    <a class="btn primary" href="#/packs">{t('projects.empty.addPack')}</a>
  </div>
</section>

<style>
  h1 {
    margin-bottom: 20px;
  }

  .empty {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 28px;
    padding: 28px 32px;
  }

  .text {
    flex: 1;
    min-width: 260px;
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }
</style>
